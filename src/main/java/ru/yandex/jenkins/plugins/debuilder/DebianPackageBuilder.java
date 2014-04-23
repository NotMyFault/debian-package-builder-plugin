package ru.yandex.jenkins.plugins.debuilder;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.BuildListener;
import hudson.model.Environment;
import hudson.model.AbstractBuild;
import hudson.model.Cause;
import hudson.model.Cause.UserIdCause;
import hudson.model.Descriptor;
import hudson.model.Project;
import hudson.scm.SCM;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.DescribableList;
import hudson.util.VariableResolver;

import java.io.IOException;
import java.io.PrintStream;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jedi.functional.FunctionalPrimitives;
import jedi.functional.Functor;
import net.sf.json.JSONObject;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import static ru.yandex.jenkins.plugins.debuilder.ChangesExtractor.Change;
import static ru.yandex.jenkins.plugins.debuilder.DebUtils.Runner;

public class DebianPackageBuilder extends Builder {
	public static final String DEBIAN_SOURCE_PACKAGE = "DEBIAN_SOURCE_PACKAGE";
	public static final String DEBIAN_PACKAGE_VERSION = "DEBIAN_PACKAGE_VERSION";
	public static final String ABORT_MESSAGE = "[{0}] Aborting: {1} ";
	private static final String PREFIX = "debian-package-builder";

	// location of debian catalog relative to the workspace root
	private final String pathToDebian;
	private final boolean generateChangelog;
	private final boolean buildEvenWhenThereAreNoChanges;

	@DataBoundConstructor
	public DebianPackageBuilder(String pathToDebian, Boolean generateChangelog, Boolean buildEvenWhenThereAreNoChanges) {
		this.pathToDebian = pathToDebian;
		this.generateChangelog = generateChangelog;
		this.buildEvenWhenThereAreNoChanges = buildEvenWhenThereAreNoChanges;
	}


	@Override
	public boolean perform(@SuppressWarnings("rawtypes") AbstractBuild build, Launcher launcher, BuildListener listener) {
		PrintStream logger = listener.getLogger();

		FilePath workspace = build.getWorkspace();
		String remoteDebian = getRemoteDebian(workspace);

		Runner runner = new Runner(build, launcher, listener, PREFIX);

		try {
			runner.runCommand("sudo apt-get update");
			runner.runCommand("sudo apt-get install aptitude pbuilder");

			importKeys(workspace, runner);

			Map<String, String> changelog = parseChangelog(runner, remoteDebian);

			String source = changelog.get("Source");
			String latestVersion = changelog.get("Version");
			runner.announce("Determined latest version to be {0}", latestVersion);

			if (generateChangelog) {
				Pair<VersionHelper, List<Change>> changes = generateChangelog(latestVersion, runner, build, remoteDebian);

				if (isTriggeredAutomatically(build) && changes.getRight().isEmpty() && !buildEvenWhenThereAreNoChanges) {
					runner.announce("There are no creditable changes for this build - not building package.");
					return true;
				}

				latestVersion = changes.getLeft().toString();
				writeChangelog(build, listener, remoteDebian, runner, changes);
			}

			runner.runCommand("cd ''{0}'' && sudo /usr/lib/pbuilder/pbuilder-satisfydepends --control control", remoteDebian);
			runner.runCommand("cd ''{0}'' && debuild --check-dirname-level 0 --no-tgz-check -k{1} -p''gpg --no-tty --passphrase {2}''", remoteDebian, getDescriptor().getAccountEmail(), getDescriptor().getPassphrase());

			archiveArtifacts(build, runner, latestVersion);

			build.addAction(new DebianBadge(latestVersion, remoteDebian));
			EnvVars envVars = new EnvVars(DEBIAN_SOURCE_PACKAGE, source, DEBIAN_PACKAGE_VERSION, latestVersion);
			build.getEnvironments().add(Environment.create(envVars));
		} catch (InterruptedException e) {
			logger.println(MessageFormat.format(ABORT_MESSAGE, PREFIX, e.getMessage()));
			return false;
		} catch (DebianizingException e) {
			logger.println(MessageFormat.format(ABORT_MESSAGE, PREFIX, e.getMessage()));
			return false;
		} catch (IOException e) {
			logger.println(MessageFormat.format(ABORT_MESSAGE, PREFIX, e.getMessage()));
			return false;
		}

		return true;
	}

	@SuppressWarnings("rawtypes")
	private void archiveArtifacts(AbstractBuild build, Runner runner, String latestVersion) throws IOException, InterruptedException {
		FilePath path = build.getWorkspace().child(pathToDebian).child("..");
		String mask = "*" + latestVersion + "*.deb";
		for (FilePath file:path.list(mask)) {
			runner.announce("Archiving file <{0}> as a build artifact", file.getName());
		}
		path.copyRecursiveTo(mask, new FilePath(build.getArtifactsDir()));
	}

	public String getRemoteDebian(FilePath workspace) {
		if (pathToDebian.endsWith("debian") || pathToDebian.endsWith("debian/")) {
			return workspace.child(pathToDebian).getRemote();
		} else {
			return workspace.child(pathToDebian).child("debian").getRemote();
		}
	}

	/**
	 * Parses changelog and updates it with next version and it's changes
	 *
	 * @param latestVersion
	 * @param runner
	 * @param build
	 * @param remoteDebian
	 * @return
	 * @throws DebianizingException
	 * @throws InterruptedException
	 * @throws IOException
	 */
	@SuppressWarnings({ "rawtypes" })
	private Pair<VersionHelper, List<Change>> generateChangelog(String latestVersion, Runner runner, AbstractBuild build, String remoteDebian) throws DebianizingException, InterruptedException, IOException {
		VersionHelper helper = new VersionHelper(latestVersion);
		runner.announce("Determined latest revision to be {0}", helper.getRevision());
		helper.setMinorVersion(helper.getMinorVersion() + 1);

		SCM scm = build.getProject().getScm();
		String ourMessage = DebianPackagePublisher.getUsedCommitMessage(build);
		List<Change> changes = ChangesExtractor.getChanges(build, runner, scm, remoteDebian, ourMessage, helper);

		return new ImmutablePair<VersionHelper, List<Change>>(helper, changes);
	}

	/**
	 * Writes down changelog contained in <b>changes</b>
	 *
	 * @param build
	 * @param listener
	 * @param remoteDebian
	 * @param runner
	 * @param changes
	 * @throws IOException
	 * @throws InterruptedException
	 * @throws DebianizingException
	 */
	@SuppressWarnings("rawtypes")
	private void writeChangelog(AbstractBuild build, BuildListener listener, String remoteDebian, Runner runner, Pair<VersionHelper, List<Change>> changes) throws IOException,
			InterruptedException, DebianizingException {

		String versionMessage = getCausedMessage(build);

		String newVersionMessage = Util.replaceMacro(versionMessage, new VariableResolver.ByMap<String>(build.getEnvironment(listener)));
		startVersion(runner, remoteDebian, changes.getLeft(), newVersionMessage);

		for (Change change: changes.getRight()) {
			addChange(runner, remoteDebian, change);
		}
	}

	@SuppressWarnings("rawtypes")
	private boolean isTriggeredAutomatically (AbstractBuild build) {
		for (Object cause: build.getCauses()) {
			if (cause instanceof UserIdCause) {
				return false;
			}
		}

		return true;
	}

	/**
	 * Returns message based on causes of the build
	 * @param build
	 * @return
	 */
	@SuppressWarnings("rawtypes")
	private String getCausedMessage(AbstractBuild build) {
		String firstPart = "Build #${BUILD_NUMBER}. ";

		@SuppressWarnings("unchecked")
		List<Cause> causes = build.getCauses();

		List<String> causeMessages = FunctionalPrimitives.map(causes, new Functor<Cause, String>() {

			@Override
			public String execute(Cause value) {
				return value.getShortDescription();
			}
		});

		Set<String> uniqueCauses = new HashSet<String>(causeMessages);

		return firstPart + FunctionalPrimitives.join(uniqueCauses, ". ") + ".";

	}

	private String clearMessage(String message) {
		return message.replaceAll("\\'", "");
	}

	private void addChange(Runner runner, String remoteDebian, Change change) throws InterruptedException, DebianizingException {
		runner.announce("Got changeset entry: {0} by {1}", clearMessage(change.getMessage()), change.getAuthor());
		runner.runCommand("export DEBEMAIL={0} && export DEBFULLNAME={1} && cd ''{2}'' && dch --check-dirname-level 0 --distributor debian --append ''{3}''", getDescriptor().getAccountEmail(), change.getAuthor(), remoteDebian, clearMessage(change.getMessage()));
	}

	private void startVersion(Runner runner, String remoteDebian, VersionHelper helper, String message) throws InterruptedException, DebianizingException {
		runner.announce("Starting version <{0}> with message <{1}>", helper, clearMessage(message));
		runner.runCommand("export DEBEMAIL={0} && export DEBFULLNAME={1} && cd ''{2}'' && dch --check-dirname-level 0 -b --distributor debian --newVersion {3} ''{4}''", getDescriptor().getAccountEmail(), getDescriptor().getAccountName(), remoteDebian, helper, clearMessage(message));
	}

	/**
	 * FIXME Doesn't work with multi-line entries
	 */
	private Map<String, String> parseChangelog(Runner runner, String remoteDebian) throws DebianizingException {
		String changelogOutput = runner.runCommandForOutput("cd \"{0}\" && dpkg-parsechangelog -lchangelog", remoteDebian);
		Map<String, String> changelog = new HashMap<String, String>();
		Pattern changelogFormat = Pattern.compile("(\\w+):\\s*(.*)");

		for(String row: changelogOutput.split("\n")) {
			Matcher matcher = changelogFormat.matcher(row);
			if (matcher.matches()) {
				 changelog.put(matcher.group(1), matcher.group(2));
			}
		}

		return changelog;
	}

	private void importKeys(FilePath workspace, Runner runner)
			throws InterruptedException, DebianizingException, IOException {
		if (!runner.runCommandForResult("gpg --list-key {0}", getDescriptor().getAccountEmail())) {
			FilePath publicKey = workspace.createTextTempFile("public", "key", getDescriptor().getPublicKey());
			runner.runCommand("gpg --import ''{0}''", publicKey.getRemote());
			publicKey.delete();
		}

		if (!runner.runCommandForResult("gpg --list-secret-key {0}", getDescriptor().getAccountEmail())) {
			FilePath privateKey = workspace.createTextTempFile("private", "key", getDescriptor().getPrivateKey());
			runner.runCommand("gpg --import ''{0}''", privateKey.getRemote());
			privateKey.delete();
		}
	}

	public boolean isGenerateChangelog() {
		return generateChangelog;
	}

	public String getPathToDebian() {
		return pathToDebian;
	}

	@Override
	public DescriptorImpl getDescriptor() {
		return (DescriptorImpl)super.getDescriptor();
	}

	@Extension
	public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

		private String publicKey;
		private String privateKey;
		private String accountName;
		private String accountEmail;
		private String passphrase;

		public DescriptorImpl() {
			load();
		}

		@Override
		public String getDisplayName() {
			return "Build debian package";
		}

		public String getPublicKey() {
			return publicKey;
		}

		@Override
		public boolean isApplicable(@SuppressWarnings("rawtypes") Class type) {
			return true;
		}

		@Override
		public boolean configure(StaplerRequest staplerRequest, JSONObject json) throws FormException {
			setPrivateKey(json.getString("privateKey"));
			setPublicKey(json.getString("publicKey"));
			setAccountName("Jenkins");
			setAccountEmail(json.getString("accountEmail"));
			setPassphrase(json.getString("passphrase"));

			save();
			return true; // indicate that everything is good so far
		}

		public String getPrivateKey() {
			return privateKey;
		}

		public void setPrivateKey(String privateKey) {
			this.privateKey = privateKey;
		}

		public void setPublicKey(String publicKey) {
			this.publicKey = publicKey;
		}

		public String getAccountName() {
			return accountName;
		}

		public void setAccountName(String accountName) {
			this.accountName = accountName;
		}

		public String getAccountEmail() {
			return accountEmail;
		}

		public void setAccountEmail(String accountEmail) {
			this.accountEmail = accountEmail;
		}

		public String getPassphrase() {
			return passphrase;
		}

		public void setPassphrase(String passphrase) {
			this.passphrase = passphrase;
		}

	}


	/**
	 * @param build
	 * @return all the paths to remote module roots declared in given build by {@link DebianPackageBuilder}s
	 */
	public static Collection<String> getRemoteModules(AbstractBuild<?, ?> build) {
		ArrayList<String> result = new ArrayList<String>();

		for (DebianPackageBuilder builder: getDPBuilders(build)) {
			result.add(new FilePath(build.getWorkspace().getChannel(), builder.getRemoteDebian(build.getWorkspace())).child("..").getRemote());
		}

		return result;
	}

	/**
	 * @param build
	 * @return all the {@link DebianPackageBuilder}s participating in this build
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public static Collection<DebianPackageBuilder> getDPBuilders(AbstractBuild<?, ?> build) {
		ArrayList<DebianPackageBuilder> result = new ArrayList<DebianPackageBuilder>();

		if (build.getProject() instanceof Project) {
			DescribableList<Builder, Descriptor<Builder>> builders = ((Project)build.getProject()).getBuildersList();
			for (Builder builder: builders) {
				if (builder instanceof DebianPackageBuilder) {
					result.add((DebianPackageBuilder) builder);
				}
			}
		}

		return result;
	}

	public boolean isBuildEvenWhenThereAreNoChanges() {
		return buildEvenWhenThereAreNoChanges;
	}

}
