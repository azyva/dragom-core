/*
 * Copyright 2015 AZYVA INC.
 *
 * This file is part of Dragom.
 *
 * Dragom is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Dragom is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Dragom.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.azyva.dragom.model.plugin.impl;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;

import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.PumpStreamHandler;
import org.apache.commons.io.FileUtils;
import org.azyva.dragom.execcontext.ExecContext;
import org.azyva.dragom.execcontext.plugin.RuntimePropertiesPlugin;
import org.azyva.dragom.execcontext.plugin.UserInteractionCallbackPlugin;
import org.azyva.dragom.execcontext.plugin.WorkspaceDir;
import org.azyva.dragom.execcontext.plugin.WorkspaceDirSystemModule;
import org.azyva.dragom.execcontext.plugin.WorkspaceDirUserModuleVersion;
import org.azyva.dragom.execcontext.plugin.WorkspacePlugin;
import org.azyva.dragom.execcontext.plugin.WorkspacePlugin.WorkspaceDirAccessMode;
import org.azyva.dragom.execcontext.support.ExecContextHolder;
import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.ModuleVersion;
import org.azyva.dragom.model.NodePath;
import org.azyva.dragom.model.Version;
import org.azyva.dragom.model.VersionType;
import org.azyva.dragom.model.event.DynamicVersionCreatedEvent;
import org.azyva.dragom.model.event.StaticVersionCreatedEvent;
import org.azyva.dragom.model.plugin.ScmPlugin;
import org.azyva.dragom.util.Util;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for ScmPlugin that supports Git repositories using the git command line
 * client installed locally.
 *
 * @author David Raymond
 */
public class GitScmPluginImpl extends ModulePluginAbstractImpl implements ScmPlugin {
	private static final Logger logger = LoggerFactory.getLogger(GitScmPluginImpl.class);

	/**
	 * Model property specifying the complete URL of the Git repository for the
	 * Module.
	 *
	 * Useful if a Module has a specific URL that cannot be inferred from the other
	 * properties using inheritance.
	 *
	 * If that property is not defined for a Module, the URL of the Git repository for
	 * the module is inferred using the other model properties below.
	 */
	private static final String MODEL_PROPERTY_GIT_REPOS_COMPLETE_URL = "GIT_REPOS_COMPLETE_URL";

	/**
	 * Base URL of the Git repository for the Module.
	 *
	 * This property is intended to be specified on a root ClassificationNode so that
	 * inheritance can be used to determine the complete URL of Git repositories based
	 * on NodePath.
	 *
	 * If the property GIT_REPOS_COMPLETE_URL is not defined for the Module, this
	 * property must be defined for the Module.
	 */
	private static final String MODEL_PROPERTY_GIT_REPOS_BASE_URL = "GIT_REPOS_BASE_URL";

	/**
	 * Model property specifying the "folder" within the URL of the Git repository for
	 * the Module.
	 *
	 * This property is intended to be specified on an intermediate ClassificationNode
	 * under which all Module Git repositories share the same base URL.
	 *
	 * If this property is not defined for the module, the NodePath of the parent
	 * Node of the Module is used.
	 */
	private static final String MODEL_PROPERTY_GIT_REPOS_DOMAIN_FOLDER = "GIT_REPOS_DOMAIN_FOLDER";

	/**
	 * Model property specifying the repository name within the URL of the Git
	 * repository for the Module.
	 *
	 * This property can be useful if the Module Git repository shares the same base
	 * URL as most other Module Git repositories within the same ClassificationNode,
	 * so that most of the URL can be inferred using the other inherited model
	 * properties, but the actual repository name cannot be inferred from the Module
	 * name.
	 *
	 * If this property is not defined for the module, its name is used.
	 */
	private static final String MODEL_PROPERTY_GIT_REPOS_NAME = "GIT_REPOS_NAME";

	/**
	 * Model property specifying the suffix of the repository name within the URL of
	 * the Git repository for the Module.
	 *
	 * This property can be useful if the Module Git repository shares the same base
	 * URL as most other Module Git repositories within the same ClassificationNode,
	 * so that most of the URL can be inferred using the other inherited model
	 * properties, but the actual repository name cannot be inferred from the Module
	 * name.
	 *
	 * If this property is not defined for the module, ".git" is used.
	 */
	private static final String MODEL_PROPERTY_GIT_REPOS_SUFFIX = "GIT_REPOS_SUFFIX";

	/**
	 * Prefix for the ExecContext property that holds the path of the main user
	 * workspace directory for a given NodePath.
	 *
	 * This plugin supports WorkspacePlugin implementation that allow for multiple
	 * Version's of the same Module. In such as case, it must elect one of these
	 * directories as being the main one so that when pushes are disabled, at least
	 * sharing within the workspace itself is possible.
	 *
	 * If the WorkspacePlugin implementation does not allow for multiple Version's of
	 * the same Module, than that property for a given Module will point to the only
	 * directory for that Module.
	 *
	 * If no user workspace directory exists for a given Module, then by this plugin's
	 * design, there is at most one system workspace directory so sharing is not a
	 * problem.
	 */
	private static final String EXEC_CONTEXT_PROPERTY_PREFIX_MAIN_WORKSPACE_DIR = "main-workspace-dir.";

	/**
	 * Runtime property indicating the fetch and push behavior. The possible values
	 * are defined by FetchPushBehavior.
	 */
	private static final String RUNTIME_PROPERTY_GIT_FETCH_PUSH_BEHAVIOR = "GIT_FETCH_PUSH_BEHAVIOR";

	/**
	 * The user may specify one of the FetchPushBehavior.*_NO_PUSH values for the
	 * runtime property GIT_FETCH_PUSH_BEHAVIOR. In such as case, multiple unpushed
	 * changes may accumulate in the repositories of the workspace. Furthermore these
	 * changes can be in multiple branches of the local repositories. In order to help
	 * the user perform these multiples pushes later, if this property is true, the
	 * isSync method when enumSetIsSyncFlag contains IsSyncFlag.LOCAL_CHANGES,
	 * it pushes all unpushed commits on all branches.
	 * This property is intended to be used with the status command of
	 * WorkspaceManagerTool.
	 * The reason for not having an explicit method to perform such pushes is that
	 * Dragom is not aware of Git's distributed nature. In particular, ScmPlugin does
	 * not offer any push method.
	 * The rational for implementing this behavior in the isSync method is that when
	 * calling isSync with the IsSyncFlag.LOCAL_CHANGES, the caller means to check
	 * for uncommitted local changes, which in the case of a distributed SCM like Git
	 * is similar to checking for unpushed local commits, but the semantics of
	 * unpushed local commits is not the same as that of uncommitted local changes and
	 * the presence of unpushed local commits cannot cause the isSync method to return
	 * false since calling commit would not actually commit anything. We therefore
	 * piggyback the push all unpushed commits functionality transparently on top of
	 * the isSync method.
	 */
	private static final String RUNTIME_PROPERTY_GIT_IND_PUSH_ALL = "GIT_IND_PUSH_ALL";

	/**
	 * Runtime property indicating to perform a pull with a rebase mode instead of a
	 * merge.
	 */
	private static final String RUNTIME_PROPERTY_IND_PULL_REBASE = "GIT_IND_PULL_REBASE";

	private static final String TRANSIENT_DATA_PATH_ALREADY_FETCHED = GitScmPluginImpl.class.getName() + ".PathAlreadyFetched";

	private static final Version VERSION_DEFAULT = new Version(VersionType.DYNAMIC, "master");

	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_WARNING_MERGE_CONFLICTS = "WARNING_MERGE_CONFLICTS";

	/**
	 * ResourceBundle specific to this class.
	 */
	private static final ResourceBundle resourceBundle = ResourceBundle.getBundle(GitScmPluginImpl.class.getName() + "ResourceBundle");

	/**
	 * Enumerates the possible values for the allowExitCode parameter of the
	 * executeGitCommand method.
	 */
	private enum AllowExitCode {
		/**
		 * Does not allow any exit code (other than 0).
		 */
		NONE,

		/**
		 * Allows exit code 1 (and 0).
		 */
		ONE,

		/**
		 * Allows all exit codes.
		 */
		ALL
	}

	/**
	 * Enumerates the possible fetch and push behaviors.
	 *
	 * Note that when fetch is enabled, fetching does not actually occurs only once
	 * per tool execution for a given repository within the workspace.
	 */
	private enum FetchPushBehavior {
		/**
		 * Indicates to not fetch nor push, thus working in total isolation with respect
		 * to the remote repository.
		 */
		NO_FETCH_NO_PUSH,

		/**
		 * Indicates to fetch, but not push. This implies working with the current state
		 * of the remote repository, but not update it. Presumably the user will push
		 * multiple changes after validated them.
		 */
		FETCH_NO_PUSH,

		/**
		 * Indicates to fetch and push. This is the typical way of working with the
		 * current state of the remote repository and immediately sharing changes made to
		 * it.
		 */
		FETCH_PUSH;

		private boolean isFetch() {
			return (this != FetchPushBehavior.NO_FETCH_NO_PUSH);
		}

		private boolean isPush() {
			return (this == FetchPushBehavior.FETCH_PUSH);
		}
	};

	String gitReposCompleteUrl;

	public GitScmPluginImpl(Module module) {
		super(module);

		String property;

		property = module.getProperty(GitScmPluginImpl.MODEL_PROPERTY_GIT_REPOS_COMPLETE_URL);

		if (property != null) {
			this.gitReposCompleteUrl = property;
		} else {
			StringBuilder stringBuilderGitReposCompleteUrl;

			stringBuilderGitReposCompleteUrl = new StringBuilder();

			property = module.getProperty(GitScmPluginImpl.MODEL_PROPERTY_GIT_REPOS_BASE_URL);

			if (property == null) {
				throw new RuntimeException("The property GIT_REPOS_BASE_URL is not defined for plugin " + this.toString() + '.');
			}

			stringBuilderGitReposCompleteUrl.append(property);
			stringBuilderGitReposCompleteUrl.append('/');

			property = module.getProperty(GitScmPluginImpl.MODEL_PROPERTY_GIT_REPOS_DOMAIN_FOLDER);

			if (property == null) {
				stringBuilderGitReposCompleteUrl.append(module.getNodePath().getNodePathParent().toString());
			} else {
				stringBuilderGitReposCompleteUrl.append(property);
				stringBuilderGitReposCompleteUrl.append('/');
			}

			property = module.getProperty(GitScmPluginImpl.MODEL_PROPERTY_GIT_REPOS_NAME);

			if (property == null) {
				stringBuilderGitReposCompleteUrl.append(module.getName());
			} else {
				stringBuilderGitReposCompleteUrl.append(property);
			}

			property = module.getProperty(GitScmPluginImpl.MODEL_PROPERTY_GIT_REPOS_SUFFIX);

			if (property == null) {
				stringBuilderGitReposCompleteUrl.append(".git");
			} else {
				stringBuilderGitReposCompleteUrl.append(property);
			}

			this.gitReposCompleteUrl = stringBuilderGitReposCompleteUrl.toString();
		}
	}

	//TODO Allows exit code 1 if isAllowExitCode1. Useful for commands such as symbolic-ref which returns 1 if HEAD is detached (or something like that)
	//Returns the exit code.
	//Logs an error of the output if error (exit code >= 2 or 1 and isAllowExitCode1 is false)
	/**
	 * Helper method to execute a Git command.
	 *
	 * @param commandLine The Git CommandLine.
	 * @param allowExitCode Specifies which exit codes are allowed and will not
	 *   trigger an exception.
	 * @param isAllowExitCode1 If true, if the command returns an exit code 1, no
	 *   trace will be written to the log.
	 * @param pathWorkingDirectory Path to the working directory. The command will be
	 *   executed with this current working directory.
	 * @param stringBuilderOutput If not null, any output (stdout) of the command will
	 *   be copied in this StringBuilder.
	 * @return The exit code of the command.
	 */
	private int executeGitCommand(CommandLine commandLine, AllowExitCode allowExitCode, Path pathWorkingDirectory, StringBuilder stringBuilderOutput) {
		DefaultExecutor defaultExecutor;
		ByteArrayOutputStream byteArrayOutputStreamOut;
		ByteArrayOutputStream byteArrayOutputStreamErr;
		int exitCode;

		GitScmPluginImpl.logger.trace(commandLine.toString());

		defaultExecutor = new DefaultExecutor();
		byteArrayOutputStreamOut = new ByteArrayOutputStream();
		byteArrayOutputStreamErr = new ByteArrayOutputStream();
		defaultExecutor.setStreamHandler(new PumpStreamHandler(byteArrayOutputStreamOut, byteArrayOutputStreamErr));
		defaultExecutor.setExitValues(null); // To not check for exit values.

		if (pathWorkingDirectory != null) {
			defaultExecutor.setWorkingDirectory(pathWorkingDirectory.toFile());
			GitScmPluginImpl.logger.trace("Invoking Git command " + commandLine + " within " + pathWorkingDirectory + '.');
		} else {
			GitScmPluginImpl.logger.trace("Invoking Git command " + commandLine + '.');
		}

		try {
			exitCode = defaultExecutor.execute(commandLine);
		} catch (IOException ioe) {
			throw new RuntimeException(ioe);
		}

		if (!(   (exitCode == 0)
		      || ((exitCode == 1) && allowExitCode == AllowExitCode.ONE)
		      || ((exitCode != 0) && allowExitCode == AllowExitCode.ALL))) {

			GitScmPluginImpl.logger.error("Git command returned " + exitCode + '.');
			GitScmPluginImpl.logger.error("Output of the command:");
			GitScmPluginImpl.logger.error(byteArrayOutputStreamOut.toString());
			GitScmPluginImpl.logger.error("Error output of the command:");
			GitScmPluginImpl.logger.error(byteArrayOutputStreamErr.toString());
			throw new RuntimeException("Git command " + commandLine + " failed.");
		}

		if (stringBuilderOutput != null) {
			stringBuilderOutput.append(byteArrayOutputStreamOut.toString().trim());
		}

		return exitCode;
	}

	@Override
	public boolean isModuleExists() {
		CommandLine commandLine;
		boolean isReposExists;

		commandLine = new CommandLine("git");
		commandLine.addArgument("ls-remote").addArgument(this.gitReposCompleteUrl).addArgument("dummy");
		isReposExists = (this.executeGitCommand(commandLine, AllowExitCode.ALL, null, null) == 0);

		if (isReposExists) {
			GitScmPluginImpl.logger.trace("Git repository " + this.gitReposCompleteUrl + " of module " + this.getModule() + " exists.");
			return true;
		} else {
			GitScmPluginImpl.logger.trace("Git repository " + this.gitReposCompleteUrl + " of module " + this.getModule() + " does not exist.");
			return false;
		}
	}

	@Override
	public Version getDefaultVersion() {
		return GitScmPluginImpl.VERSION_DEFAULT;
	}

	// Branch or null if detached.
	private String gitGetBranch(Path pathModuleWorkspace) {
		CommandLine commandLine;
		StringBuilder stringBuilder;
		int exitCode;

		commandLine = new CommandLine("git");
		commandLine.addArgument("symbolic-ref").addArgument("-q").addArgument("HEAD");
		stringBuilder = new StringBuilder();
		exitCode = this.executeGitCommand(commandLine, AllowExitCode.ONE, pathModuleWorkspace, stringBuilder);

		if (exitCode == 0) {
			String branch;

			branch = stringBuilder.toString();

			if (branch.startsWith("refs/heads/")) {
				return branch.substring(11);
			} else {
				throw new RuntimeException("Unrecognized branch reference " + branch + " returned by git symbolic-ref.");
			}
		} else {
			return null;
		}
	}

	private void gitConfig(Path pathModuleWorkspace, String param, String value) {
		CommandLine commandLine;

		commandLine = new CommandLine("git");
		commandLine.addArgument("config").addArgument(param).addArgument(value);
		this.executeGitCommand(commandLine, AllowExitCode.NONE, pathModuleWorkspace, null);
	}

	private void gitClone(Version version, Path pathRemote, Path pathModuleWorkspace) {
		CommandLine commandLine;
		String reposUrl;
		boolean isDetachedHead;

		commandLine = new CommandLine("git");

		if (pathRemote != null) {
			reposUrl = "file://" + pathRemote;
		} else {
			reposUrl = this.gitReposCompleteUrl;
		}

		// The -b option takes a branch or tag name, but without the complete reference
		// prefix such as heads/master or tags/v-1.2.3. This means that it is not
		// straightforward to distinguish between branches and tags.
		commandLine.addArgument("clone").addArgument("-b").addArgument(version.getVersion()).addArgument(reposUrl).addArgument(pathModuleWorkspace.toString());
		this.executeGitCommand(commandLine, AllowExitCode.NONE, null, null);

		// If the path to a local remote repository was specified, we update that path to
		// the true URL of the remote repository for consistency. We want to have all of
		// the repositories to have as their origin remote that true URL and when we want
		// to use another local remote, we specify it explicitly.
		if (pathRemote != null) {
			this.gitConfig(pathModuleWorkspace, "remote.origin.url", this.gitReposCompleteUrl);
		}

		// We need to verify the type of version checked out by checking whether we are in
		// a detached head state (tag) or not (branch).
		isDetachedHead = (this.gitGetBranch(pathModuleWorkspace) == null);

		if ((version.getVersionType() == VersionType.DYNAMIC) && isDetachedHead) {
			try {
				FileUtils.deleteDirectory(pathModuleWorkspace.toFile());
			} catch (IOException ioe) {}

			throw new RuntimeException("Requested version is dynamic but checked out version is a tag.");
		}

		if ((version.getVersionType() == VersionType.STATIC) && !isDetachedHead) {
			try {
				FileUtils.deleteDirectory(pathModuleWorkspace.toFile());
			} catch (IOException ioe) {}

			throw new RuntimeException("Requested version is static but checked out version is a branch.");
		}

		// If pathRemote is null it means we cloned from the remote repository. We can
		// therefore conclude that we have fetched from the remote.
		if (pathRemote == null) {
			this.hasFetched(pathModuleWorkspace);
		}
	}

	private void fetch(Path pathModuleWorkspace) {
		NodePath nodePathModule;
		Path pathMainUserWorkspaceDir;

		nodePathModule = this.getModule().getNodePath();
		pathMainUserWorkspaceDir = this.getPathMainUserWorkspaceDir(nodePathModule);

		if ((pathMainUserWorkspaceDir != null) && !pathMainUserWorkspaceDir.equals(pathModuleWorkspace)) {
			// If the Workspace directory is not the main one, we first perform a regular
			// fetch within the main Workspace directory.
			// Note that depending on the GIT_FETCH_PUSH_BEHAVIOR runtime property it may be
			// the case that no fetch is actually performed by this call.
			this.gitFetch(pathMainUserWorkspaceDir, null, null);

			// Then we perform a local fetch from the main to the current Workspace directory
			// directory. For this special fetch, we need to update the remote tracking
			// branches from the main Workspace directory to the remote tracking branches in
			// the current directory. The default behavior if not specifying the refspec would
			// be to take the regular branches from the main Workspace directory which is not
			// what we want.
			// We could be tempted to not perform that fetch if the first fetch above was
			// avoided because of the GIT_FETCH_PUSH_BEHAVIOR runtime property. But there
			// could be cases where the remote tracking branches are not up to date in the
			// current Workspace directory and we want to ensure they are updated.
			this.gitFetch(pathModuleWorkspace, pathMainUserWorkspaceDir, "refs/remotes/origin/*:refs/remotes/origin/*");
		} else {
			// If the Workspace directory is the main one, we perform a regular fetch.
			// Note that depending on the GIT_FETCH_PUSH_BEHAVIOR runtime property it may be
			// the case that no fetch is actually performed by this call.
			this.gitFetch(pathModuleWorkspace, null, null);
		}
	}

	/**
	 * This method handles both case of fetches:
	 *
	 * - Regular fetch from remote repository
	 * - Fetch from main repository to non-main repository
	 *
	 * When pathRemote is null, indicating a fetch from a remote repository into the
	 * main repository for a module, the mustFetch and hasFetched methods are used to
	 * determine if fetching must actually be performed based on the
	 * GIT_FETCH_PUSH_BEHAVIOR runtime property.
	 *
	 * @param pathModuleWorkspace Path to the Workspace directory for the Module where
	 *   fetching is actually required.
	 * @param pathRemote Path to the main directory for the Module within the
	 *   Workspace. null when pathModuleWorkspace is the main repository for the
	 *   module.
	 * @param refspec The Git refspec to pass to git fetch. See the documentation for
	 *   git fetch for more information. Can be null if no refspec is to be passed to
	 *   git, letting git essentially use the default
	 *   refs/heads/*:refs/remotes/origin/heads/* refspec.
	 */
	private void gitFetch(Path pathModuleWorkspace, Path pathRemote, String refspec) {
		String reposUrl;
		CommandLine commandLine;

		commandLine = new CommandLine("git");

		if (pathRemote != null) {
			reposUrl = "file://" + pathRemote;
		} else {
			if (!this.mustFetch(pathModuleWorkspace)) {
				return;
			}

			reposUrl = this.gitReposCompleteUrl;
		}

		commandLine.addArgument("fetch").addArgument(reposUrl);

		if (refspec != null) {
			commandLine.addArgument(refspec);
		}

		this.executeGitCommand(commandLine, AllowExitCode.NONE, pathModuleWorkspace, null);

		// If pathRemote is null it means we fetched from the remote repository.
		if (pathRemote == null) {
			this.hasFetched(pathModuleWorkspace);
		}
	}

	private boolean gitPull(Path pathModuleWorkspace) {
		String branch;
		CommandLine commandLine;
		RuntimePropertiesPlugin runtimePropertiesPlugin;
		Boolean indPullRebase;
		int exitCode;

		branch = this.gitGetBranch(pathModuleWorkspace);

		if (branch == null) {
			throw new RuntimeException("Within " + pathModuleWorkspace + " the HEAD is not a branch.");
		}

		// Note that depending on the GIT_FETCH_PUSH_BEHAVIOR runtime property it may be
		// the case that no fetch is actually performed.
		// We perform the pull in two steps also because the second step may be a regular
		// merge or a rebase.
		this.fetch(pathModuleWorkspace);

		runtimePropertiesPlugin = ExecContextHolder.get().getExecContextPlugin(RuntimePropertiesPlugin.class);
		indPullRebase = Boolean.valueOf(runtimePropertiesPlugin.getProperty(this.getModule(), GitScmPluginImpl.RUNTIME_PROPERTY_IND_PULL_REBASE));

		if (indPullRebase) {
			commandLine = new CommandLine("git");

			// Rebase onto the upstream tracking branch corresponding to the current branch.
			// This is the default behavior, but specifying @{u} is more symetrical with the
			// merge mode below.
			commandLine.addArgument("rebase").addArgument("@{u}");
			exitCode = this.executeGitCommand(commandLine, AllowExitCode.ONE, pathModuleWorkspace, null);
		} else {
			commandLine = new CommandLine("git");

			// Merge the upstream tracking branch corresponding to the current branch.
			commandLine.addArgument("merge").addArgument("@{u}");
			exitCode = this.executeGitCommand(commandLine, AllowExitCode.ONE, pathModuleWorkspace, null);
		}

		// We do not pull new commits that may exist in the same branch in the main
		// Workspace directory. See comment about same branch in main Workspace directory
		// in isSync method.

		return exitCode == 1;
	}

	// TODO: The way push is handed is not symetric with fetch.
	// We first push the repository to the real remote, regardless of the main
	// workspace directory for the module.
	// Then, if there is a main workspace directory for the module, we fetch from
	// the one we just pushed, into the main one so that subsequent fetchs from the
	// main one will consider the new changes.
	private void push(Path pathModuleWorkspace, String gitRef) {
		String branch;
		NodePath nodePathModule;
		Path pathMainUserWorkspaceDir;

		branch = this.gitGetBranch(pathModuleWorkspace);

		if (branch == null) {
			throw new RuntimeException("Within " + pathModuleWorkspace + " the HEAD is not a branch.");
		}

		nodePathModule = this.getModule().getNodePath();
		pathMainUserWorkspaceDir = this.getPathMainUserWorkspaceDir(nodePathModule);

		if ((pathMainUserWorkspaceDir != null) && !pathMainUserWorkspaceDir.equals(pathModuleWorkspace)) {
			// If the Workspace directory is not the main one we first perform a local fetch
			// from the current to the main Workspace directory directory in order to bring the
			// new commits into the main Workspace directory. For this special fetch, we need
			// to update only the current branch (nothing to do with the remote tracking
			// branches yet). This current branch in the main Workspace directory is not
			// supposed to be the current one so there is not danger of disturbing it.
			this.gitFetch(pathMainUserWorkspaceDir, pathModuleWorkspace, "refs/heads/" + branch + ":refs/heads/" + branch);

			// Then we perform a regular push within the main Workspace directory, but for the
			// current branch.
			// Note that depending on the GIT_FETCH_PUSH_BEHAVIOR runtime property it may be
			// the case that no push is actually performed by this call.
			this.gitPush(pathMainUserWorkspaceDir, "refs/heads/" + branch);

			// Finally we perform a local fetch from the main to the current Workspace
			// directory. For this special fetch, we need to update the remote tracking
			// branches from the main Workspace directory to the remote tracking branches in
			// the current directory. The default behavior if not specifying the refspec would
			// be to take the regular branches from the main Workspace directory which is not
			// what we want.
			// We could be tempted to not perform that fetch if the push above was avoided
			// because of the GIT_FETCH_PUSH_BEHAVIOR runtime property. But there could be
			// cases where the remote tracking branches are not up to date in the current
			// Workspace directory and we want to ensure they are updated, even if this is not
			// strictly related to the push.
			this.gitFetch(pathModuleWorkspace, pathMainUserWorkspaceDir, "refs/remotes/origin/*:refs/remotes/origin/*");
		} else {
			// If the Workspace directory is the main one, we perform a regular push.
			// Note that depending on the GIT_FETCH_PUSH_BEHAVIOR runtime property it may be
			// the case that no push is actually performed by this call.
			this.gitPush(pathModuleWorkspace, gitRef);
		}
	}

	private void gitPush(Path pathModuleWorkspace, String gitRef) {
		CommandLine commandLine;

		if (!this.getFetchPushBehavior().isPush()) {
			GitScmPluginImpl.logger.trace("Pushing is disabled for module " + this.getModule() + " within " + pathModuleWorkspace + '.');
			return;
		}

		commandLine = new CommandLine("git");
		commandLine.addArgument("push");

		// We always set the upstream tracking information because because pushes can be
		// delayed and new branches can be pushed on the call to this method other than
		// the one immediately after creating the branch.
		// In the case gitRef is a tag, --set-upstream has no effect.
		commandLine.addArgument("--set-upstream").addArgument("origin").addArgument(gitRef + ":" + gitRef);
		this.executeGitCommand(commandLine, AllowExitCode.NONE, pathModuleWorkspace, null);
	}

	@Override
	public void checkout(Version version, Path pathModuleWorkspace) {
		WorkspacePlugin workspacePlugin;
		NodePath nodePathModule;
		WorkspaceDirSystemModule workspaceDirSystemModule;
		Path pathMainUserWorkspaceDir;
		Path pathModuleWorkspaceRemote;

		workspacePlugin = ExecContextHolder.get().getExecContextPlugin(WorkspacePlugin.class);
		nodePathModule = this.getModule().getNodePath();

		// If there is a main user workspace directory for the Module (whatever the
		// Version) we want to clone from this directory instead of from the remote
		// repository to avoid network access.

		// We expect pathMainUserWorkspaceDir to not be equal to pathModuleWorkspace since
		// when the caller calls this method, the pathModuleWorkspace must not exist yet
		// as it is meant to be created by this method.
		pathMainUserWorkspaceDir = this.getPathMainUserWorkspaceDir(nodePathModule);

		if (pathMainUserWorkspaceDir != null) {
			this.gitClone(version,  pathMainUserWorkspaceDir, pathModuleWorkspace);
			return;
		}

		// If not, we check if a system workspace directory exists for the module, in
		// which case we simply clone from it instead of from the remote repository.
		// Furthermore, we make the new user workspace directory the main one.

		workspaceDirSystemModule = new WorkspaceDirSystemModule(nodePathModule);

		if (workspacePlugin.isWorkspaceDirExist(workspaceDirSystemModule)) {
			pathModuleWorkspaceRemote = workspacePlugin.getWorkspaceDir(workspaceDirSystemModule,  WorkspacePlugin.GetWorkspaceDirMode.ENUM_SET_GET_EXISTING, WorkspaceDirAccessMode.READ);
		} else {
			pathModuleWorkspaceRemote = null;
		}

		try {
			this.gitClone(version, pathModuleWorkspaceRemote, pathModuleWorkspace);
		} finally {
			if (pathModuleWorkspaceRemote != null) {
				workspacePlugin.releaseWorkspaceDir(pathModuleWorkspaceRemote);
			}
		}

		this.setPathMainUserWorkspaceDir(nodePathModule, pathModuleWorkspace);
	}

	//TODO: The path must come from the workspace so that caller can check what kind it is.
	//TODO: Caller must release workspace directory. Was accessed READ_WRITE.
	@Override
	public Path checkoutSystem(Version version) {
		WorkspacePlugin workspacePlugin;
		NodePath nodePathModule;
		WorkspaceDirUserModuleVersion workspaceDirUserModuleVersion;
		Path pathModuleWorkspace;
		WorkspaceDirSystemModule workspaceDirSystemModule;
		Path pathMainUserWorkspaceDir;

		workspacePlugin = ExecContextHolder.get().getExecContextPlugin(WorkspacePlugin.class);
		nodePathModule = this.getModule().getNodePath();

		// We first check if a user workspace directory exists for the ModuleVersion.

		workspaceDirUserModuleVersion = new WorkspaceDirUserModuleVersion(new ModuleVersion(nodePathModule, version));

		if (workspacePlugin.isWorkspaceDirExist(workspaceDirUserModuleVersion)) {
			// If the module is already checked out for the user, the path is reused as is,
			// without caring to make sure it is up to date. It belongs to the user who is
			// responsible for this, or the caller acting on behalf of the user.
			return workspacePlugin.getWorkspaceDir(workspaceDirUserModuleVersion, WorkspacePlugin.GetWorkspaceDirMode.ENUM_SET_GET_EXISTING, WorkspaceDirAccessMode.READ_WRITE);
		}

		// If not, we check if a system workspace directory exists for the module.

		workspaceDirSystemModule = new WorkspaceDirSystemModule(nodePathModule);

		if (workspacePlugin.isWorkspaceDirExist(workspaceDirSystemModule)) {
			// If a system workspace directory already exists for the module, we reuse it.
			// But it may not be up-to-date and may not have the requested version checked
			// out.

			pathModuleWorkspace = workspacePlugin.getWorkspaceDir(workspaceDirSystemModule,  WorkspacePlugin.GetWorkspaceDirMode.ENUM_SET_GET_EXISTING, WorkspaceDirAccessMode.READ_WRITE);

			this.fetch(pathModuleWorkspace);
			this.gitCheckout(pathModuleWorkspace, version);

			// If the version is dynamic (a branch), we might have actually checked out the
			// local version of it and it may not be up to date.
			if (version.getVersionType() == VersionType.DYNAMIC) {
				pathMainUserWorkspaceDir = this.getPathMainUserWorkspaceDir(nodePathModule);

				if ((pathMainUserWorkspaceDir != null) && !pathMainUserWorkspaceDir.equals(pathModuleWorkspace)) {
					// If the Workspace directory is not the main one, we fetch the same branch from
					// the main Workspace directory into the current Workspace directory.
					// This is the special handling of the synchronization between a workspace
					// directory and the main one mentioned in the isSync method.
					this.gitFetch(pathModuleWorkspace, pathMainUserWorkspaceDir, "refs/heads/" + version.getVersion() + "*:refs/heads/" + version.getVersion());
				}

				if (this.gitPull(pathModuleWorkspace)) {
					throw new RuntimeException("Conflicts were encountered while pulling changes into " + pathModuleWorkspace + ". This is not expected here.");
				}
			}

			return pathModuleWorkspace;
		}

		// If not we know we will create a new system workspace directory.

		pathModuleWorkspace = workspacePlugin.getWorkspaceDir(workspaceDirSystemModule,  WorkspacePlugin.GetWorkspaceDirMode.ENUM_SET_CREATE_NEW_NO_PATH, WorkspaceDirAccessMode.READ_WRITE);

		// But if there is a main user workspace directory for the Module (whatever the
		// Version) we want to clone from this directory instead of from the remote
		// repository to avoid network access.

		pathMainUserWorkspaceDir = this.getPathMainUserWorkspaceDir(nodePathModule);

		this.gitClone(version, pathMainUserWorkspaceDir, pathModuleWorkspace);

		return pathModuleWorkspace;
	}

	private void gitCheckout(Path pathModuleWorkspace, Version version) {
		CommandLine commandLine;
		boolean isDetachedHead;

		commandLine = new CommandLine("git");

		/* The checkout command takes a branch or tag name, but without the complete
		 * reference prefix such as heads/master or tags/v-1.2.3. This means that it is
		 * not straightforward to distinguish between branches and tags.
		 */
		commandLine.addArgument("checkout").addArgument(version.getVersion());
		this.executeGitCommand(commandLine, AllowExitCode.NONE, pathModuleWorkspace, null);

		/* We need to verify the type of version checked out by checking whether we are in
		 * a detached head state (tag) or not (branch).
		 */
		isDetachedHead = (this.gitGetBranch(pathModuleWorkspace) == null);

		if ((version.getVersionType() == VersionType.DYNAMIC) && isDetachedHead) {
			try {
				FileUtils.deleteDirectory(pathModuleWorkspace.toFile());
			} catch (IOException ioe) {}

			throw new RuntimeException("Requested version is dynamic but checked out version is a tag.");
		}

		if ((version.getVersionType() == VersionType.STATIC) && !isDetachedHead) {
			try {
				FileUtils.deleteDirectory(pathModuleWorkspace.toFile());
			} catch (IOException ioe) {}

			throw new RuntimeException("Requested version is static but checked out version is a branch.");
		}
	}

	/**
	 * Returns the Path to the Module in the Workspace without reserving access to it.
	 *
	 * There are methods in this plugin, such as isVersionExists, which require access
	 * to a Workspace directory for the Module in order to obtain various information.
	 * Always using checkoutSystem is not possible even if access to the Workspace
	 * directory was WorkspaceDirAccessMode.READ since this would prevent other
	 * accesses.
	 *
	 * This method therefore returns an existing Workspace directory for the Module
	 * and only if no such directory exists calls checkoutSystem to obtain one. And if
	 * checkoutSystem is called, the Path to the Workspace directory is immediately
	 * released so that other callers can obtain the directory without any problem.
	 *
	 * If more than one Workspace directory exists for the Module, the main one is
	 * returned.
	 *
	 * Note that the Workspace directory is returned with no specific Version checked
	 * out, and the caller is not expected to modify the directory in any way, except
	 * for transparent changes such as fetching from the remote repository.
	 *
	 * Note also a fetch is performed by this method on the returned workspace
	 * directory.
	 *
	 * @return
	 */
	private Path getPathModuleWorkspace() {
		WorkspacePlugin workspacePlugin;
		NodePath nodePathModule;
		Path pathModuleWorkspace;
		WorkspaceDirSystemModule workspaceDirSystemModule;

		workspacePlugin = ExecContextHolder.get().getExecContextPlugin(WorkspacePlugin.class);
		nodePathModule = this.getModule().getNodePath();

		pathModuleWorkspace = this.getPathMainUserWorkspaceDir(nodePathModule);

		if (pathModuleWorkspace != null) {
			this.fetch(pathModuleWorkspace);
			return pathModuleWorkspace;
		}

		workspaceDirSystemModule = new WorkspaceDirSystemModule(nodePathModule);

		if (workspacePlugin.isWorkspaceDirExist(workspaceDirSystemModule)) {
			pathModuleWorkspace = workspacePlugin.getWorkspaceDir(workspaceDirSystemModule,  WorkspacePlugin.GetWorkspaceDirMode.ENUM_SET_GET_EXISTING, WorkspaceDirAccessMode.PEEK);
			this.fetch(pathModuleWorkspace);
			return pathModuleWorkspace;
		}

		pathModuleWorkspace = this.checkoutSystem(this.getDefaultVersion());
		workspacePlugin.releaseWorkspaceDir(pathModuleWorkspace);
		this.fetch(pathModuleWorkspace);
		return pathModuleWorkspace;
	}

	@Override
	public boolean isVersionExists(Version version) {
		Path pathModuleWorkspace;
		CommandLine commandLine;

		// GitScmPluginImpl.getPathModuleWorkspace either returns an existing path to the
		// Module in the Workspace, or call checkoutSystem on the default version
		// ("master") to get that path. It may seem awkward that we checkout the default
		// version ("master") in order to check for the existence of another version. Why
		// not simply checking out the specified Version? We think this is a better
		// approach because attempting to checkout the Version will actually throw an
		// unexpected exception if the version does not exist. checkoutSystem is meant to
		// be called on an existing version.
		// We could also have used the "ls-remote" command to query the remote repository
		// directly. But this can cause inconsistencies when pushes to the remote
		// repository are delayed. It is better to always work locally.
		pathModuleWorkspace = this.getPathModuleWorkspace();

		commandLine = new CommandLine("git");

		// We add "--" as a last argument since when a ref does no exist, Git complains
		// about the fact that the command is ambiguous.
		commandLine.addArgument("rev-parse").addArgument(GitScmPluginImpl.convertToRef(version)).addArgument("--");

		if (this.executeGitCommand(commandLine, AllowExitCode.ALL, pathModuleWorkspace, null) == 0) {
			GitScmPluginImpl.logger.trace("Version " + version + " of module " + this.getModule() + " exists.");
			return true;
		} else {
			GitScmPluginImpl.logger.trace("Version " + version + " of module " + this.getModule() + " does not exist.");
			return false;
		}
	}

	@Override
	// TODO: Comments to review.
	// Handling the fact that the user may or may not want to push changes to the
	// remote repository during commits is not obvious. In the case where the user
	// does want to push changes we may be tempted here to verify if there are
	// unpushed local commits and return false if so. But the problem is that in
	// in response to this the caller (on behalf of the user) may call the commit
	// method in which case there may be nothing to commit (only already performed
	// local commits to push, which is not the same). So what we do instead in the
	// case where there are unpushed local commits is to simply perform the push and
	// return that the local repository is synchronized.`
	public boolean isSync(Path pathModuleWorkspace, EnumSet<IsSyncFlag> enumSetIsSyncFlag) {
		String branch;
		CommandLine commandLine;
		StringBuilder stringBuilder;

		branch = this.gitGetBranch(pathModuleWorkspace);

		if (branch == null) {
			throw new RuntimeException("Within " + pathModuleWorkspace + " the HEAD is not a branch.");
		}

		// Note that depending on the GIT_FETCH_PUSH_BEHAVIOR runtime property it may be
		// the case that no fetch is actually performed by this call.
		this.fetch(pathModuleWorkspace);

		// Verifying if the remote repository contains changes that are not in the local
		// repository involves verifying if there are remote commits that have not been
		// pulled into the local branch. This is done with the "git for-each-ref" command
		// which will provide us with "behind" information for the desired branch.
		// Note that "git status -sb" also provides this information, but only for the
		// current branch, whereas "git for-each-ref" can provide that information for any
		// branch.

		if (enumSetIsSyncFlag.contains(IsSyncFlag.REMOTE_CHANGES)) {
			stringBuilder = new StringBuilder();
			commandLine = new CommandLine("git");
			commandLine.addArgument("for-each-ref").addArgument("--format=%(upstream:track)").addArgument("refs/heads/" + branch);
			this.executeGitCommand(commandLine, AllowExitCode.NONE, pathModuleWorkspace, stringBuilder);

			if (enumSetIsSyncFlag.contains(IsSyncFlag.REMOTE_CHANGES)) {
				if (stringBuilder.indexOf("behind") != -1) {
					return false;
				}
			}

			// We could be tempted to also check if the same branch in the main Workspace
			// directory (if this directory is not the main one) contains new commits. But the
			// handling of the synchronization between a Workspace directory and the main one
			// for a Module is special. Things are done in such a way that this synchronization
			// is transparent. This synchronization actually happens in checkoutSystem so that
			// the caller can assume that the Workspace directory is up to date.
			// Nevertheless, there may be exceptional cases where the 2 Workspace directories
			// become unsynchronized. But these rare cases are neglected for the sake of
			// simplicity.
		}

		// Verifying if a Git local repository contains changes that are not in the remote
		// repository involves verifying in the repository specified if there are local
		// changes that have not been committed. This is done with the "git status"
		// command.

		if (enumSetIsSyncFlag.contains(IsSyncFlag.LOCAL_CHANGES)) {
			// See GitScmPluginImpl.RUNTIME_PROPERTY_GIT_INT_PUSH_ALL
			if (this.isPushAll()) {
				commandLine = new CommandLine("git");
				commandLine.addArgument("push");

				// We always set the upstream tracking information because because pushes can be
				// delayed and new branches can be pushed on the call to this method other than
				// the one immediately after creating the branch.
				commandLine.addArgument("--all").addArgument("--set-upstream").addArgument("origin");
				this.executeGitCommand(commandLine, AllowExitCode.NONE, pathModuleWorkspace, null);

				commandLine = new CommandLine("git");
				commandLine.addArgument("push");

				// Unfortunately we cannot specify --all and --tags in the same command.
				commandLine.addArgument("--tags");
				this.executeGitCommand(commandLine, AllowExitCode.NONE, pathModuleWorkspace, null);
			}

			stringBuilder = new StringBuilder();
			commandLine = new CommandLine("git");
			commandLine.addArgument("status").addArgument("-s");
			this.executeGitCommand(commandLine, AllowExitCode.NONE, pathModuleWorkspace, stringBuilder);

			if (stringBuilder.length() != 0) {
				return false;
			}

			// We could be tempted to also check if there are unpushed commits and if so
			// conclude that the Workspace directory is not synchronized. But this would be
			// wrong since Dragom is not aware of the the distributed nature of Git and
			// indicating that there are unsynchronized local changes would lead the caller to
			// potentially call commit in order to commit the local changes, but no such
			// would be performed. The handling of unpushed changes is done under the hood
			// using runtime properties known only to this plugin and the users who use the
			// tools developped with Dragom.
		}

		return true;
	}

	@Override
	public boolean update(Path pathModuleWorkspace) {
		return this.gitPull(pathModuleWorkspace);
	}

	@Override
	public Version getVersion(Path pathModuleWorkspace) {
		String branch;
		CommandLine commandLine;
		StringBuilder stringBuilder;

		branch = this.gitGetBranch(pathModuleWorkspace);

		if (branch != null) {
			return new Version(VersionType.DYNAMIC, branch);
		}

		// branch is null it means we are in detached HEAD state and thus probably on a
		// tag.

		commandLine = new CommandLine("git");
		commandLine.addArgument("describe").addArgument("--exact-match");
		stringBuilder = new StringBuilder();
		this.executeGitCommand(commandLine, AllowExitCode.NONE, pathModuleWorkspace, stringBuilder);

		return new Version(VersionType.STATIC, stringBuilder.toString());
	}

	@Override
	public List<Commit> getListCommit(Version version, CommitPaging commitPaging, EnumSet<GetListCommitFlag> enumSetGetListCommitFlag) {
		return this.getListCommitInternal(GitScmPluginImpl.convertToRef(version), commitPaging, enumSetGetListCommitFlag);
	}

	@Override
	public List<Commit> getListCommitDiverge(Version versionSrc, Version versionDest, CommitPaging commitPaging, EnumSet<GetListCommitFlag> enumSetGetListCommitFlag) {
		return this.getListCommitInternal(GitScmPluginImpl.convertToRef(versionDest) + ".." + GitScmPluginImpl.convertToRef(versionSrc), commitPaging, enumSetGetListCommitFlag);
	}

	@SuppressWarnings("unchecked")
	private List<Commit> getListCommitInternal(String revisionRange, CommitPaging commitPaging, EnumSet<GetListCommitFlag> enumSetGetListCommitFlag) {
		Path pathModuleWorkspace;
		CommandLine commandLine;
		List<Commit> listCommit;
		StringBuilder stringBuilderCommits;
		BufferedReader bufferedReaderCommits;
		Map<String, Object> mapTag = null; // Map value can be a simple tag name (String) or a list of tag names (List<String>) in the case more than one tag is associated with the same commit.
		String commitString;

		if ((commitPaging != null) && commitPaging.indDone) {
			throw new RuntimeException("getListCommit called after commit enumeration completed.");
		}

		pathModuleWorkspace = this.getPathModuleWorkspace();

		try {
			listCommit = new ArrayList<Commit>();

			stringBuilderCommits = new StringBuilder();
			commandLine = new CommandLine("git");
			commandLine.addArgument("rev-list").addArgument("--pretty=oneline");

			if (commitPaging != null) {
				if (commitPaging.startIndex != 0) {
					commandLine.addArgument("--skip=" + commitPaging.startIndex);
				}

				if (commitPaging.maxCount != -1) {
					commandLine.addArgument("--max-count=" + commitPaging.maxCount);
				}
			}

			// We add "--" as a last argument since when a ref does no exist, Git complains
			// about the fact that the command is ambiguous.
			commandLine.addArgument(revisionRange).addArgument("--");

			this.executeGitCommand(commandLine, AllowExitCode.NONE, pathModuleWorkspace, stringBuilderCommits);

			bufferedReaderCommits = new BufferedReader(new StringReader(stringBuilderCommits.toString()));

			// If we must return the static Version associated with each commit, we use the
			// following efficient strategy:
			// We use "git show-ref --tags -d" to list all tags together with the commit IDs.
			// The -d option will allow us to differentiate annotated and lightweight tags:
			// Annotated tags will be suffixed with ^{}.
			// When looping through the commits we can then easily find the annotated tags that
			// point to the commits.
			// So overall, we invoke only one additional git command, as opposed to a less
			// optimum solution involving invoking a git command for each commit.
			// Note that the getListVersionStatic method also invokes the "show-ref" command
			// and parses the resulting list of tags. But it does not extract the commid IDs
			// that are required in this algorithm.
			if ((enumSetGetListCommitFlag != null) && enumSetGetListCommitFlag.contains(GetListCommitFlag.IND_INCLUDE_VERSION_STATIC)) {
				StringBuilder stringBuilderTags;
				BufferedReader bufferedReaderTags;
				String tagLine;

				commandLine = new CommandLine("git");
				commandLine.addArgument("show-ref").addArgument("--tag").addArgument("-d");
				stringBuilderTags = new StringBuilder();
				this.executeGitCommand(commandLine, AllowExitCode.NONE, pathModuleWorkspace, stringBuilderTags);

				bufferedReaderTags = new BufferedReader(new StringReader(stringBuilderTags.toString()));
				mapTag = new HashMap<String, Object>();

				while ((tagLine = bufferedReaderTags.readLine()) != null) {
					String[] arrayTagLineComponent;
					String commitId;
					String tagRef;

					arrayTagLineComponent = tagLine.split("\\s+");

					commitId = arrayTagLineComponent[0];
					tagRef = arrayTagLineComponent[1];

					if (tagRef.endsWith("^{}")) {
						String tagName;
						Object value;

						// A few magic numbers here, but not worth having constants.
						// 10 is the length of "refs/tags/" that prefixes each tag name.
						// 3 is the length of "^{}" that suffixes each tag name.
						tagName = tagRef.substring(10, tagRef.length() - 3);

						value = mapTag.get(commitId);

						if (value != null) {
							if (value instanceof String) {
								List<String> listTagName;

								listTagName = new ArrayList<String>();
								listTagName.add((String)value);
								listTagName.add(tagName);
								mapTag.put(commitId,  listTagName);
							} else {
								((List<String>)value).add(tagName);
							}
						} else {
							mapTag.put(commitId, tagName);
						}
					}
				}
			}

			while ((commitString = bufferedReaderCommits.readLine()) != null) {
				int indexSplit;
				String commitMessage;
				Map<String, String> mapCommitAttr;
				Commit commit;

				indexSplit = commitString.indexOf(' ');
				commitMessage = commitString.substring(indexSplit + 1);

				mapCommitAttr = Util.getCommitAttr(commitMessage);

				if (mapCommitAttr.get(ScmPlugin.COMMIT_ATTR_BASE_VERSION) != null) {
					if (commitPaging != null) {
						commitPaging.indDone = true;
					}

					break;
				}

				commit = new Commit();

				commit.id = commitString.substring(0, indexSplit);

				if ((enumSetGetListCommitFlag != null) && enumSetGetListCommitFlag.contains(GetListCommitFlag.IND_INCLUDE_MESSAGE)) {
					commit.message = Util.getCommitMessageWithoutAttr(commitMessage);
				}

				if ((enumSetGetListCommitFlag != null) && enumSetGetListCommitFlag.contains(GetListCommitFlag.IND_INCLUDE_MAP_ATTR)) {
					commit.mapAttr = mapCommitAttr;
				}

				if ((enumSetGetListCommitFlag != null) && enumSetGetListCommitFlag.contains(GetListCommitFlag.IND_INCLUDE_VERSION_STATIC)) {
					Object value;

					value = mapTag.get(commit.id);

					if (value != null) {
						if (value instanceof String) {
							commit.arrayVersionStatic = new Version[1];

							commit.arrayVersionStatic[0] = new Version(VersionType.STATIC, (String)value);
						} else {
							List<String> listTagName;

							listTagName = (List<String>)value;

							commit.arrayVersionStatic = new Version[listTagName.size()];

							for(int i = 0; i < listTagName.size(); i++) {
								commit.arrayVersionStatic[0] = new Version(VersionType.STATIC, listTagName.get(i));
							}
						}
					} else {
						commit.arrayVersionStatic = new Version[0];
					}
				}

				listCommit.add(commit);
			}
		} catch (IOException ioe) {
			throw new RuntimeException(ioe);
		}

		if (commitPaging != null) {
			commitPaging.returned = listCommit.size();

			if (commitPaging.returned == 0) {
				commitPaging.indDone = true;
			}
		}

		if ((enumSetGetListCommitFlag != null) && enumSetGetListCommitFlag.contains(GetListCommitFlag.IND_UPDATE_START_INDEX)) {
			if (commitPaging == null) {
				throw new RuntimeException("Request to update startIndex but commitPaging is null.");
			}

			commitPaging.startIndex += commitPaging.returned;
		}

		return listCommit;
	}

	@Override
	public BaseVersion getBaseVersion(Version version) {
		Path pathModuleWorkspace;
		CommandLine commandLine;
		String stringBaseVersion;
		BaseVersion baseVersion;

		pathModuleWorkspace = this.getPathModuleWorkspace();

		switch (version.getVersionType()) {
		case DYNAMIC:
			StringBuilder stringBuilderCommits;
			BufferedReader bufferedReaderCommits;
			String commitString;

			stringBuilderCommits = new StringBuilder();
			commandLine = new CommandLine("git");
			commandLine.addArgument("rev-list").addArgument("--pretty=oneline").addArgument(version.getVersion());
			this.executeGitCommand(commandLine, AllowExitCode.NONE, pathModuleWorkspace, stringBuilderCommits);

			bufferedReaderCommits = new BufferedReader(new StringReader(stringBuilderCommits.toString()));

			try {
				while ((commitString = bufferedReaderCommits.readLine()) != null) {
					int indexSplit;
					String commitMessage;
					Map<String, String> mapCommitAttr;

					indexSplit = commitString.indexOf(' ');
					commitMessage = commitString.substring(indexSplit + 1);

					mapCommitAttr = Util.getCommitAttr(commitMessage);

					stringBaseVersion = mapCommitAttr.get(ScmPlugin.COMMIT_ATTR_BASE_VERSION);

					if (stringBaseVersion != null) {
						baseVersion = new BaseVersion();

						baseVersion.version = version;
						baseVersion.versionBase = new Version(stringBaseVersion);
						commitString = bufferedReaderCommits.readLine();
						indexSplit = commitString.indexOf(' ');
						baseVersion.commitId = commitString.substring(0, indexSplit);

						return baseVersion;
					}
				}
			} catch (IOException ioe) {
				throw new RuntimeException(ioe);
			}

			return null;

		case STATIC:
			StringBuilder stringBuilder;
			String tagMessage;
			Map<String, String> mapTagAttr;

			stringBuilder = new StringBuilder();
			commandLine = new CommandLine("git");
			commandLine.addArgument("tag").addArgument("-n").addArgument("-l").addArgument(version.getVersion());
			this.executeGitCommand(commandLine, AllowExitCode.NONE, pathModuleWorkspace, stringBuilder);

			if (stringBuilder.toString().isEmpty()) {
				throw new RuntimeException("Static version " + version + " does not exist.");
			}

			tagMessage = stringBuilder.toString().split("\\s+")[1];

			mapTagAttr = Util.getCommitAttr(tagMessage);

			stringBaseVersion = mapTagAttr.get(ScmPlugin.COMMIT_ATTR_BASE_VERSION);

			if (stringBaseVersion == null) {
				return null;
			}

			baseVersion = new BaseVersion();

			baseVersion.version = version;

			baseVersion.versionBase = new Version(stringBaseVersion);

			stringBuilder.setLength(0);
			commandLine = new CommandLine("git");
			commandLine.addArgument("rev-parse").addArgument(version.getVersion() + "^{}");
			this.executeGitCommand(commandLine, AllowExitCode.NONE, pathModuleWorkspace, stringBuilder);

			if (stringBuilder.toString().isEmpty()) {
				throw new RuntimeException("Static version " + version + " does not exist.");
			}

			baseVersion.commitId = stringBuilder.toString();

			return baseVersion;

		default:
			throw new RuntimeException("Invalid version type.");
		}
	}

	@Override
	public List<Version> getListVersionStatic() {
		Path pathModuleWorkspace;
		CommandLine commandLine;
		StringBuilder stringBuilder;
		BufferedReader bufferedReader;
		String tagLine;
		List<Version> listVersionStatic;

		pathModuleWorkspace = this.getPathModuleWorkspace();

		try {
			commandLine = new CommandLine("git");
			commandLine.addArgument("show-ref").addArgument("--tag").addArgument("-d");
			stringBuilder = new StringBuilder();
			this.executeGitCommand(commandLine, AllowExitCode.NONE, pathModuleWorkspace, stringBuilder);

			bufferedReader = new BufferedReader(new StringReader(stringBuilder.toString()));
			listVersionStatic = new ArrayList<Version>();

			while ((tagLine = bufferedReader.readLine()) != null) {
				String[] arrayTagLineComponent;
				String tagRef;

				arrayTagLineComponent = tagLine.split("\\s+");

				tagRef = arrayTagLineComponent[1];

				if (tagRef.endsWith("^{}")) {
					String tagName;

					// A few magic numbers here, but not worth having constants.
					// 10 is the length of "refs/tags/" that prefixes each tag name.
					// 3 is the length of "^{}" that suffixes each tag name.
					tagName = tagRef.substring(10, tagRef.length() - 3);
					listVersionStatic.add(new Version(VersionType.STATIC, tagName));
				}
			}
		} catch (IOException ioe) {
			throw new RuntimeException(ioe);
		}

		return listVersionStatic;
	}

	@Override
	public void switchVersion(Path pathModuleWorkspace, Version version) {
		WorkspacePlugin workspacePlugin;
		WorkspaceDir workspaceDir;

		/* gitFetch will have been called by isSync.
		 */
		if (!this.isSync(pathModuleWorkspace, IsSyncFlag.ALL_CHANGES)) {
			throw new RuntimeException("Working directory " + pathModuleWorkspace + " must be synchronized before switching to a new version.");
		}

		this.gitCheckout(pathModuleWorkspace, version);

		workspacePlugin = ExecContextHolder.get().getExecContextPlugin(WorkspacePlugin.class);
		workspaceDir = workspacePlugin.getWorkspaceDirFromPath(pathModuleWorkspace);

		/* If the WorkspaceDir belongs to the user, it is specific to a version and the
		 * new version must be reflected in the workspace.
		 */
		if (workspaceDir instanceof WorkspaceDirUserModuleVersion) {
			// TODO: Eventually must think about the implications of this if workspace allows for multiple versions of same module.
			// What if the new version already exists? We will probably have to delete one of them, but which one.
			// Have to deal with main repository if ever it is the one deleted.
			workspacePlugin.updateWorkspaceDir(workspaceDir, new WorkspaceDirUserModuleVersion(new ModuleVersion(((WorkspaceDirUserModuleVersion)workspaceDir).getModuleVersion().getNodePath(), version)));
		}

		/* After a "git checkout" the workspace should be synchronized. But just in case
		 * there may be unpushed changes, we still verify this.
		 * No need to check for synchronisation for tags since we assume tags are
		 * immutable.
		 */
		if ((version.getVersionType() == VersionType.DYNAMIC) && !this.isSync(pathModuleWorkspace, IsSyncFlag.ALL_CHANGES)) {
			throw new RuntimeException("Working directory " + pathModuleWorkspace + " is not synchronized after checking out a version.");
		}
	}

	@Override
	public void createVersion(Path pathModuleWorkspace, Version versionTarget, boolean indSwitch) {
		CommandLine commandLine;
		Map<String, String> mapCommitAttr;
		String message;
		WorkspacePlugin workspacePlugin;
		WorkspaceDir workspaceDir;

		/* gitFetch will have been called by isSync.
		 */
		if (!this.isSync(pathModuleWorkspace, IsSyncFlag.ALL_CHANGES)) {
			throw new RuntimeException("Working directory " + pathModuleWorkspace + " must be synchronized before creating a new version.");
		}

		// We prepare the prefix commit or tag message specifying the base version since
		// it is the same for both Version types.
		// See commit() method for comments on commit message, more specifically the need
		// to escape double quotes.
		mapCommitAttr = new HashMap<String, String>();
		mapCommitAttr.put(ScmPlugin.COMMIT_ATTR_BASE_VERSION, this.getVersion(pathModuleWorkspace).toString());
		message = (new JSONObject(mapCommitAttr)).toString();
		message = message.replace("\"", "\\\"");

		commandLine = new CommandLine("git");

		workspacePlugin = ExecContextHolder.get().getExecContextPlugin(WorkspacePlugin.class);

		switch (versionTarget.getVersionType()) {
		case DYNAMIC:
			String branch;

			branch = versionTarget.getVersion();
			commandLine.addArgument("branch").addArgument(branch);
			this.executeGitCommand(commandLine, AllowExitCode.NONE, pathModuleWorkspace, null);

			try {
				if (indSwitch) {
					this.switchVersion(pathModuleWorkspace, versionTarget);
				} else {
					// If the caller did not ask to switch we must not. But we still need access to
					// the new Version in order to introduce the dummy base-version commit.
					pathModuleWorkspace = this.checkoutSystem(versionTarget);
				}

				message += " Dummy commit introduced to record the base version of the newly created version.";

				commandLine = new CommandLine("git");
				commandLine.addArgument("commit").addArgument("--allow-empty").addArgument("-m").addArgument(message, false);
				this.executeGitCommand(commandLine, AllowExitCode.NONE, pathModuleWorkspace, null);

				this.push(pathModuleWorkspace, "refs/heads/" + branch);

				this.getModule().raiseNodeEvent(new DynamicVersionCreatedEvent(this.getModule(), versionTarget));
			} finally {
				if (!indSwitch) {
					// If the caller did not ask to switch, checkoutSystem was called above and we
					// need to release the workspace directory.

					workspacePlugin = ExecContextHolder.get().getExecContextPlugin(WorkspacePlugin.class);
					workspacePlugin.releaseWorkspaceDir(pathModuleWorkspace);
				}
			}

			break;

		case STATIC:
			String tag;

			tag = versionTarget.getVersion();

			commandLine.addArgument("tag").addArgument("-m").addArgument(message, false).addArgument(tag);
			this.executeGitCommand(commandLine, AllowExitCode.NONE, pathModuleWorkspace, null);

			if (indSwitch) {
				/* The following essentially performs the same thing as the method switchVersion,
				 * but switchVersion calls isSync which fails when a new unpushed branch is
				 * present.
				 */
				this.gitCheckout(pathModuleWorkspace, versionTarget);

				workspaceDir = workspacePlugin.getWorkspaceDirFromPath(pathModuleWorkspace);

				/* If the WorkspaceDir belongs to the user, it is specific to a version and the
				 * new version must be reflected in the workspace.
				 */
				if (workspaceDir instanceof WorkspaceDirUserModuleVersion) {
					workspacePlugin.updateWorkspaceDir(workspaceDir, new WorkspaceDirUserModuleVersion(new ModuleVersion(((WorkspaceDirUserModuleVersion)workspaceDir).getModuleVersion().getNodePath(), versionTarget)));
				}
			}

			// To push the new tag.
			this.push(pathModuleWorkspace, "refs/tags/" + versionTarget.getVersion());

			this.getModule().raiseNodeEvent(new StaticVersionCreatedEvent(this.getModule(), versionTarget));

			break;

		default:
			throw new RuntimeException("Invalid version type.");
		}
	}

	@Override
	//TODO Document Push as well...
	// Should the caller be interested in knowing commit failed because unsynced, or caller must update before?
	public void commit(Path pathModuleWorkspace, String message, Map<String, String> mapCommitAttr) {
		String branch;
		CommandLine commandLine;

		branch = this.gitGetBranch(pathModuleWorkspace);

		if (branch == null) {
			throw new RuntimeException("Within " + pathModuleWorkspace + " the HEAD is not a branch.");
		}

		if (!this.isSync(pathModuleWorkspace, IsSyncFlag.REMOTE_CHANGES_ONLY)) {
			throw new RuntimeException("Working directory " + pathModuleWorkspace + " must be synchronized with remote changes before committing.");
		}

		commandLine = new CommandLine("git");
		commandLine.addArgument("add").addArgument("--all");
		this.executeGitCommand(commandLine, AllowExitCode.NONE, pathModuleWorkspace, null);

		commandLine = new CommandLine("git");

		// Git does not natively support commit attributes. It does support "notes" which
		// could be used to keep commit attributes. But it looks like this is not a robust
		// solution as notes are independent of commits and can easily be modified. And
		// also not all Git repository managers support Git notes. In particular, Stash
		// does not seem to have full support for git notes.
		// For these reasons commit attributes are stored within the commit messages
		// themselves.
		if (mapCommitAttr != null) {
			message = (new JSONObject(mapCommitAttr)).toString() + ' ' + message;
		}

		// Commons Exec ends up calling Runtime.exec(String[], ...) with the command line
		// arguments. It looks like along the way double quotes within the arguments get
		// removed which, apart from being undesirable, causes a bug with the commit
		// messages that included a JSONObject for the commit attributes. At the very
		// least it is required to use the addArgument(String, boolean handleQuote) method
		// to disable quote handling. Otherwise Commons Exec surrounds the argument with
		// single quotes when it contains double quotes, which we do not want. But we must
		// also escape double quotes to prevent Runtime.exec from removing them. I did not
		// find any reference to this behavior, and I am not 100% sure that it is
		// Runtime.exec's fault. But escaping the double quotes works.
		message = message.replace("\"", "\\\"");

		commandLine.addArgument("commit").addArgument("-m").addArgument(message, false);
		this.executeGitCommand(commandLine, AllowExitCode.NONE, pathModuleWorkspace, null);

		this.push(pathModuleWorkspace, "refs/heads/" + branch);
	}


	@Override
	public boolean merge(Path pathModuleWorkspace, Version versionSrc, String message) {
		Version versionDest;
		CommandLine commandLine;
		String mergeMessage;

		versionDest = this.getVersion(pathModuleWorkspace);

		if (versionDest.getVersionType() == VersionType.STATIC) {
			throw new RuntimeException("Current version " + versionDest + " in working directory " + pathModuleWorkspace + " must be dynamic for merging into.");
		}

		// gitFetch will have been called by isSync.
		if (!this.isSync(pathModuleWorkspace, IsSyncFlag.ALL_CHANGES)) {
			throw new RuntimeException("Working directory " + pathModuleWorkspace + " must be synchronized before merging.");
		}

		commandLine = new CommandLine("git");

		commandLine.addArgument("merge").addArgument("--no-edit").addArgument("--no-ff");

		mergeMessage = "Merged " + versionSrc + " into " + versionDest + '.';

		if (message != null) {
			// Commons Exec ends up calling Runtime.exec(String[], ...) with the command line
			// arguments. It looks like along the way double quotes within the arguments get
			// removed which is undesirable. At the very least it is required to use the
			// addArgument(String, boolean handleQuote) method to disable quote handling.
			// Otherwise Commons Exec surrounds the argument with single quotes when it
			// contains double quotes, which we do not want. But we must also escape double
			// quotes to prevent Runtime.exec from removing them. I did not find any reference
			// to this behavior, and I am not 100% sure that it is Runtime.exec's fault. But
			// escaping the double quotes works.
			message = message.replace("\"", "\\\"");

			mergeMessage = message + '\n' + mergeMessage;
		}

		commandLine.addArgument("-m").addArgument(mergeMessage, false).addArgument(GitScmPluginImpl.convertToRef(versionSrc));

		if (this.executeGitCommand(commandLine, AllowExitCode.ONE, pathModuleWorkspace, null) == 1) {
			return false;
		}

		this.push(pathModuleWorkspace, "refs/heads/" + versionDest.getVersion());

		return true;
	}

	@Override
	public boolean merge(Path pathModuleWorkspace, Version versionSrc, List<Commit> listCommitExclude, String message) {
		Version versionDest;
		CommandLine commandLine;
		StringBuilder stringBuilderMergeMessage;
		List<ScmPlugin.Commit> listCommit;
		String commitIdRangeStart;
		Iterator<Commit> iteratorCommit;
		int patchCount;

		versionDest = this.getVersion(pathModuleWorkspace);

		if (versionDest.getVersionType() == VersionType.STATIC) {
			throw new RuntimeException("Current version " + versionDest + " in working directory " + pathModuleWorkspace + " must be dynamic for merging into.");
		}

		// gitFetch will have been called by isSync.
		if (!this.isSync(pathModuleWorkspace, IsSyncFlag.ALL_CHANGES)) {
			throw new RuntimeException("Working directory " + pathModuleWorkspace + " must be synchronized before merging.");
		}

		try {
			//*********************************************************************************
			// Step 1: "prepare" a merge commit, but without actually performing any merge
			// since we will perform the merge steps explicitly after. We need to prepare the
			// merge commit before performing the merge steps since issuing this command with
			// the index modified (the merge steps modify the index) fails.
			//*********************************************************************************

			commandLine = new CommandLine("git");

			commandLine.addArgument("merge").addArgument("--no-commit").addArgument("--strategy").addArgument("ours");

			stringBuilderMergeMessage = new StringBuilder();

			if (message != null) {
				// Commons Exec ends up calling Runtime.exec(String[], ...) with the command line
				// arguments. It looks like along the way double quotes within the arguments get
				// removed which is undesirable. At the very least it is required to use the
				// addArgument(String, boolean handleQuote) method to disable quote handling.
				// Otherwise Commons Exec surrounds the argument with single quotes when it
				// contains double quotes, which we do not want. But we must also escape double
				// quotes to prevent Runtime.exec from removing them. I did not find any reference
				// to this behavior, and I am not 100% sure that it is Runtime.exec's fault. But
				// escaping the double quotes works.
				message = message.replace("\"", "\\\"");

				stringBuilderMergeMessage.append(message).append('\n');
			}

			stringBuilderMergeMessage.append("Merged ").append(versionSrc).append(" into ").append(versionDest).append(" excluding the following commits:\n");

			for (ScmPlugin.Commit commit: listCommitExclude) {
				stringBuilderMergeMessage.append(commit.id);

				if (commit.message != null) {
					stringBuilderMergeMessage.append(' ').append(commit.message);
				}

				stringBuilderMergeMessage.append('\n');
			}

			// Remove the useless trailing newline.
			stringBuilderMergeMessage.setLength(stringBuilderMergeMessage.length() - 1);

			commandLine.addArgument("-m").addArgument(stringBuilderMergeMessage.toString(), false).addArgument(GitScmPluginImpl.convertToRef(versionSrc));

			this.executeGitCommand(commandLine, AllowExitCode.NONE, pathModuleWorkspace, null);

			//*********************************************************************************
			// Step 2: Obtain the list of commits to merge.
			//*********************************************************************************

			listCommit = this.getListCommitDiverge(versionSrc, versionDest, null, null);

			//*********************************************************************************
			// Step 3: Generate the patches.
			// The logic is as follows. Suppose the list of commits to merge is A, B, C, D,
			// E and the list of commits to exlude is B and D, then patches will be generated
			// and applied for the ranges <dest>..A, B..C and D..E. If the list of commits to
			// exclude is rather A and E (the edges cases), the only range is A..D. <dest>
			// above refers to the destination Version itself. See comment below about the
			// initial commit range start.
			//*********************************************************************************

			// The initial commit range start is the destination Version. In Git, a range
			// expressed as start..end means all commits reachable by end but not by start.
			// Technically, the initial range start is the parent commit of the very first
			// commit in the list of commits to merge. But since that commit is necessarily
			// part of the parent hierarchy of the destination Version, it is equivalent.
			commitIdRangeStart = GitScmPluginImpl.convertToRef(versionDest);

			iteratorCommit = listCommit.iterator();

			patchCount = 0;

			// During each iteration of this loop we have a patch to apply which generally
			// spans multiple commits (in between commits to be excluded). Therefore
			// iterations do not correspond to the elements in the list of commits.
			do {
				String commitId;
				String lastCommitIdExclude;
				String commitIdRangeEnd;

				lastCommitIdExclude = null;
				commitIdRangeEnd = null;

				patch:
				while (iteratorCommit.hasNext()) {
					commitId = iteratorCommit.next().id;

					for (ScmPlugin.Commit commitExclude: listCommitExclude) {
						if (commitId.equals(commitExclude.id)) {
							lastCommitIdExclude = commitId;
							break patch;
						}
					}

					commitIdRangeEnd = commitId;
				}

				// commitIdRangeEnd will be null if the first commit encountered during this
				// iteration happens to be excluded. In that case, we have nothing to merge and
				// must simply go to the next range.
				if (commitIdRangeEnd != null) {
					StringBuilder stringBuilderPatch;
					OutputStreamWriter outputStreamWriterPatch;

					commandLine = new CommandLine("git");
					commandLine.addArgument("diff").addArgument(commitIdRangeStart + ".." + commitIdRangeEnd);
					stringBuilderPatch = new StringBuilder();
					this.executeGitCommand(commandLine, GitScmPluginImpl.AllowExitCode.NONE, pathModuleWorkspace, stringBuilderPatch);

					patchCount++;
					String.valueOf(patchCount);
					try {
						outputStreamWriterPatch = new OutputStreamWriter(new FileOutputStream("dragom-patch-" + String.format("%2d", patchCount) + ".patch"));
						outputStreamWriterPatch.append(stringBuilderPatch);
						outputStreamWriterPatch.close();
					} catch (IOException ioe) {
						throw new RuntimeException(ioe);
					}
				}

				commitIdRangeStart = lastCommitIdExclude;
			} while (commitIdRangeStart != null);

			//*********************************************************************************
			// Step 4: Apply the patches.
			// In principle we could apply the patches as they are generated, but in case a
			// conflict is encountered while applying the patches, having all the patch files
			// available allows the user to resolve the conflict and continue applying the
			// remaining patches. This is imporant as applying some patches and not other is
			// not really an option as the merge commit that concludes the merge process
			// assumes that the merge is complete.
			//*********************************************************************************

			for (int patchIndex = 1; patchIndex <= patchCount; patchIndex++) {
				String patchFileName;

				patchFileName = "dragom-patch-" + String.format("%2d", patchIndex) + ".patch";

				commandLine = new CommandLine("git");
				commandLine.addArgument("apply").addArgument("--3way").addArgument(patchFileName);

				try {
					FileUtils.moveFile(new File(patchFileName), new File(patchFileName + ".current"));
				} catch (IOException ioe) {
					throw new RuntimeException(ioe);
				}

				if (this.executeGitCommand(commandLine, GitScmPluginImpl.AllowExitCode.ONE, pathModuleWorkspace, null) == 1) {
					UserInteractionCallbackPlugin userInteractionCallbackPlugin;

					userInteractionCallbackPlugin = ExecContextHolder.get().getExecContextPlugin(UserInteractionCallbackPlugin.class);

					// It is not clear if it is OK for this plugin to use
					// UserInteractionCallbackPlugin as it would seem this plugin should operate at a
					// low level. But for now this seems to be the only way to properly inform the
					// user about what to do with the merge conflicts.
					userInteractionCallbackPlugin.provideInfo(MessageFormat.format(GitScmPluginImpl.resourceBundle.getString(GitScmPluginImpl.MSG_PATTERN_KEY_WARNING_MERGE_CONFLICTS), pathModuleWorkspace, versionSrc, versionDest));

					return false;
				}

				try {
					FileUtils.moveFile(new File(patchFileName + ".current"), new File(patchFileName + ".done"));
				} catch (IOException ioe) {
					throw new RuntimeException(ioe);
				}
			}

			//*********************************************************************************
			// Step 5: Suppress the patch files.
			//*********************************************************************************

			for (int patchIndex = 1; patchIndex <= patchCount; patchIndex++) {
				new File("dragom-patch-" + String.format("%2d", patchIndex) + ".patch.done").delete();
			}

			//*********************************************************************************
			// Step 6: Perform the commit.
			//*********************************************************************************

			commandLine = new CommandLine("git");
			commandLine.addArgument("commit").addArgument("--no-edit");
			this.executeGitCommand(commandLine, GitScmPluginImpl.AllowExitCode.NONE, pathModuleWorkspace, null);

			this.push(pathModuleWorkspace, "refs/heads/" + versionDest.getVersion());

			return true;
		} catch (RuntimeException re) {
			throw new RuntimeException(
				  "An expected exception occurred during the merge of version " + versionSrc + " into version " + versionDest + " within " + pathModuleWorkspace + ".\n"
				+ "The merge has not been aborted and dragom-patch-##.patch files may still be present in the root of the workspace directory for the module.\n"
				+ "IT IS VERY IMPORTANT that the merge operation not be completed with \"git commit\" as is.\n"
				+ "If it is, the merge commit will tell Git that the merge is complete, whereas unmerged changes probably exist.\n"
				+ "It MAY be possible to complete the merge process by manually applying the remaining patch files with \"git apply --3way <patch file>\" and \"git commit\".\n"
				+ "But after investigating the problem it is preferable to abort the merge operation with \"git merge --abort\", reset the workspace directory with \"git reset --hard HEAD\" and perform the merge again.",
				re);
		}
	}

	@Override
	public String getScmType() {
		return "git";
	}

	@Override
	public String getScmUrl(Path pathModuleWorkspace) {
		return this.gitReposCompleteUrl;
	}

	//TODO: Probably should validate the path
	// It may not exist anymore if the caller has deleted the workspace dir.
	// Or...
	private Path getPathMainUserWorkspaceDir(NodePath nodePathModule) {
		ExecContext execContext;
		WorkspacePlugin workspacePlugin;
		String stringPathMainWorkspaceDir;
		Path pathMainUserWorkspaceDir;

		execContext = ExecContextHolder.get();
		workspacePlugin = execContext.getExecContextPlugin(WorkspacePlugin.class);

		stringPathMainWorkspaceDir = execContext.getProperty(GitScmPluginImpl.EXEC_CONTEXT_PROPERTY_PREFIX_MAIN_WORKSPACE_DIR + this.getModule().getNodePath().getPropertyNameSegment());

		if (stringPathMainWorkspaceDir == null) {
			return null;
		} else {
			pathMainUserWorkspaceDir = workspacePlugin.getPathWorkspace().resolve(stringPathMainWorkspaceDir);

			// There is no mechanism to keep the property synchronized with changes which may
			// occur within the workspace. We therefore need to validate the main workspace
			// directory for a Module.
			//
			// It is not valid if:
			// - It is not known to the workspace, or
			// - It is known to the workspace but is not a WorkspaceDirUserModuleVersion, or
			// - It is know to the workspace, is a WorkspaceDirUserModuleVersion, but the
			//   NodePath does not match the one for which we need to get the
			//   main workspace directory.
			if (   !workspacePlugin.isPathWorkspaceDirExists(pathMainUserWorkspaceDir)
				|| !(workspacePlugin.getWorkspaceDirFromPath(pathMainUserWorkspaceDir) instanceof WorkspaceDirUserModuleVersion)
				|| !((WorkspaceDirUserModuleVersion)workspacePlugin.getWorkspaceDirFromPath(pathMainUserWorkspaceDir)).getModuleVersion().getNodePath().equals(nodePathModule)) {

				Set<WorkspaceDir> setWorkspaceDir;
				WorkspaceDirUserModuleVersion workspaceDirUserModuleVersion;

				// If the main workspace directory is not valid, we try to find another workspace
				// directory for the module and elect it as being the main one.

				setWorkspaceDir = workspacePlugin.getSetWorkspaceDir(new WorkspaceDirUserModuleVersion(new ModuleVersion(nodePathModule)));

				workspaceDirUserModuleVersion = (WorkspaceDirUserModuleVersion)(setWorkspaceDir.iterator().next());

				if (workspaceDirUserModuleVersion != null) {
					pathMainUserWorkspaceDir = workspacePlugin.getWorkspaceDir(workspaceDirUserModuleVersion, WorkspacePlugin.GetWorkspaceDirMode.ENUM_SET_GET_EXISTING, WorkspaceDirAccessMode.READ);
					workspacePlugin.releaseWorkspaceDir(pathMainUserWorkspaceDir);
				} else {
					pathMainUserWorkspaceDir = null;
				}

				this.setPathMainUserWorkspaceDir(nodePathModule, pathMainUserWorkspaceDir);
			}

			return pathMainUserWorkspaceDir;
		}
	}

	private void setPathMainUserWorkspaceDir(NodePath nodePathModule, Path pathMainUserWorkspaceDir) {
		ExecContext execContext;
		WorkspacePlugin workspacePlugin;

		execContext = ExecContextHolder.get();
		workspacePlugin = execContext.getExecContextPlugin(WorkspacePlugin.class);

		if (pathMainUserWorkspaceDir == null) {
			execContext.setProperty(GitScmPluginImpl.EXEC_CONTEXT_PROPERTY_PREFIX_MAIN_WORKSPACE_DIR + this.getModule().getNodePath().getPropertyNameSegment(), null);
		} else {
			execContext.setProperty(GitScmPluginImpl.EXEC_CONTEXT_PROPERTY_PREFIX_MAIN_WORKSPACE_DIR + this.getModule().getNodePath().getPropertyNameSegment(), workspacePlugin.getPathWorkspace().relativize(pathMainUserWorkspaceDir).toString());
		}
	}

	/**
	 * Indicates if changes must be pushed.
	 *
	 * Uses the GIT_DONT_PUSH_CHANGES RuntimeProperties with reversed
	 * polarity.
	 *
	 * @return See description.
	 */
	private FetchPushBehavior getFetchPushBehavior() {
		RuntimePropertiesPlugin runtimePropertiesPlugin;
		String fetchPushBehavior;

		runtimePropertiesPlugin = ExecContextHolder.get().getExecContextPlugin(RuntimePropertiesPlugin.class);
		fetchPushBehavior = runtimePropertiesPlugin.getProperty(this.getModule(), GitScmPluginImpl.RUNTIME_PROPERTY_GIT_FETCH_PUSH_BEHAVIOR);

		if (fetchPushBehavior == null) {
			return FetchPushBehavior.FETCH_PUSH;
		}

		return FetchPushBehavior.valueOf(fetchPushBehavior);
	}

	@SuppressWarnings("unchecked")
	private boolean mustFetch(Path pathModuleWorkspace) {
		switch (this.getFetchPushBehavior()) {
		case NO_FETCH_NO_PUSH:
			GitScmPluginImpl.logger.trace("Fetching is disabled for module " + this.getModule() + " within " + pathModuleWorkspace + '.');
			return false;
		case FETCH_NO_PUSH:
		case FETCH_PUSH:
			ExecContext execContext;
			Set<Path> setPathAlreadyFetched;
			boolean indMustFetch;

			execContext = ExecContextHolder.get();

			setPathAlreadyFetched = (Set<Path>)execContext.getTransientData(GitScmPluginImpl.TRANSIENT_DATA_PATH_ALREADY_FETCHED);

			if (setPathAlreadyFetched == null) {
				indMustFetch = true;
			} else {
				indMustFetch = !setPathAlreadyFetched.contains(pathModuleWorkspace);
			}

			if (indMustFetch) {
				GitScmPluginImpl.logger.trace("Fetching is enabled only once for module " + this.getModule() + " within " + pathModuleWorkspace + '.');
			} else {
				GitScmPluginImpl.logger.trace("Fetching is now disabled for module " + this.getModule() + " within " + pathModuleWorkspace + '.');
			}

			return indMustFetch;

		default:
			throw new RuntimeException("Invalid fetch behavior.");
		}
	}

	@SuppressWarnings("unchecked")
	private void hasFetched(Path pathWorkspace) {
		if (this.getFetchPushBehavior().isFetch()) {
			ExecContext execContext;
			Set<Path> setPathAlreadyFetched;

			execContext = ExecContextHolder.get();

			setPathAlreadyFetched = (Set<Path>)execContext.getTransientData(GitScmPluginImpl.TRANSIENT_DATA_PATH_ALREADY_FETCHED);

			if (setPathAlreadyFetched == null) {
				setPathAlreadyFetched = new HashSet<Path>();
				execContext.setTransientData(GitScmPluginImpl.TRANSIENT_DATA_PATH_ALREADY_FETCHED, setPathAlreadyFetched);
			}

			setPathAlreadyFetched.add(pathWorkspace);
		}
	}

	/**
	 * Indicates isSync should push all changes.
	 *
	 * See GitScmPluginImpl.RUNTIME_PROPERTY_GIT_IND_PUSH_ALL.
	 *
	 * @return See description.
	 */
	private boolean isPushAll() {
		RuntimePropertiesPlugin runtimePropertiesPlugin;

		runtimePropertiesPlugin = ExecContextHolder.get().getExecContextPlugin(RuntimePropertiesPlugin.class);

		return Util.isNotNullAndTrue(runtimePropertiesPlugin.getProperty(this.getModule(), GitScmPluginImpl.RUNTIME_PROPERTY_GIT_IND_PUSH_ALL));
	}

	/**
	 * Converts a {@link Version} to a Git reference such as refs/tags/&lt;tag&gt; or
	 * refs/remotes/origin/&lt;branch&gt;.
	 * <p>
	 * In the case of a dynamic Version, the remove branch is used.
	 *
	 * @param version Version.
	 * @return Git reference.
	 */
	private static String convertToRef(Version version) {
		if (version.getVersionType() == VersionType.STATIC) {
			// "^{tag}" ensures we consider only annotated tags.
			return "refs/tags/" + version.getVersion() + "^{tag}";
		} else {
			return "refs/remotes/origin/" + version.getVersion();
		}
	}
}
