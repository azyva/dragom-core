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

package org.azyva.dragom.git;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.PumpStreamHandler;
import org.apache.commons.io.FileUtils;
import org.azyva.dragom.model.Version;
import org.azyva.dragom.model.VersionType;
import org.azyva.dragom.model.plugin.impl.GitScmPluginImpl;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Git interface.
 * <p>
 * Mainly used by {@link GitScmPluginImpl}. But also useful for Dragom test
 * scripts that need to act on repositories outside of Dragom tools themselves, to
 * simulate remote changes for instance.
 * <p>
 * This class relies on Git being installed locally. It interfaces with the Git
 * command line.
 * <p>
 * This class does not rely on other Dragom classes.
 *
 * @author David Raymond
 */
public class Git {
	private static final Logger logger = LoggerFactory.getLogger(Git.class);

	/**
	 * Enumerates the possible values for the allowExitCode parameter of the
	 * executeGitCommand method.
	 */
	public static enum AllowExitCode {
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
	 * Helper method to execute a Git command.
	 * <p>
	 * Mostly used internally, but can be used by callers in order to submit a Git
	 * command that is not directly supported by this class.
	 *
	 * @param commandLine The Git CommandLine.
	 * @param allowExitCode Specifies which exit codes are allowed and will not
	 *   trigger an exception.
	 * @param pathWorkingDirectory Path to the working directory. The command will be
	 *   executed with this current working directory.
	 * @param stringBuilderOutput If not null, any output (stdout) of the command will
	 *   be copied in this StringBuilder.
	 * @return The exit code of the command.
	 */
	public static int executeGitCommand(CommandLine commandLine, AllowExitCode allowExitCode, Path pathWorkingDirectory, StringBuilder stringBuilderOutput) {
		DefaultExecutor defaultExecutor;
		ByteArrayOutputStream byteArrayOutputStreamOut;
		ByteArrayOutputStream byteArrayOutputStreamErr;
		int exitCode;

		Git.logger.trace(commandLine.toString());

		defaultExecutor = new DefaultExecutor();
		byteArrayOutputStreamOut = new ByteArrayOutputStream();
		byteArrayOutputStreamErr = new ByteArrayOutputStream();
		defaultExecutor.setStreamHandler(new PumpStreamHandler(byteArrayOutputStreamOut, byteArrayOutputStreamErr));
		defaultExecutor.setExitValues(null); // To not check for exit values.

		if (pathWorkingDirectory != null) {
			defaultExecutor.setWorkingDirectory(pathWorkingDirectory.toFile());
			Git.logger.trace("Invoking Git command " + commandLine + " within " + pathWorkingDirectory + '.');
		} else {
			Git.logger.trace("Invoking Git command " + commandLine + '.');
		}

		try {
			exitCode = defaultExecutor.execute(commandLine);
		} catch (IOException ioe) {
			throw new RuntimeException(ioe);
		}

		if (!(   (exitCode == 0)
		      || ((exitCode == 1) && allowExitCode == AllowExitCode.ONE)
		      || ((exitCode != 0) && allowExitCode == AllowExitCode.ALL))) {

			Git.logger.error("Git command returned " + exitCode + '.');
			Git.logger.error("Output of the command:");
			Git.logger.error(byteArrayOutputStreamOut.toString());
			Git.logger.error("Error output of the command:");
			Git.logger.error(byteArrayOutputStreamErr.toString());
			throw new RuntimeException("Git command " + commandLine + " failed.");
		}

		if (stringBuilderOutput != null) {
			stringBuilderOutput.append(byteArrayOutputStreamOut.toString().trim());
		}

		return exitCode;
	}

	public static boolean isReposExists(String reposUrl) {
		CommandLine commandLine;
		boolean isReposExists;

		commandLine = (new CommandLine("git")).addArgument("ls-remote").addArgument(reposUrl).addArgument("dummy");
		isReposExists = (Git.executeGitCommand(commandLine, AllowExitCode.ALL, null, null) == 0);

		if (isReposExists) {
			Git.logger.trace("Git repository " + reposUrl + " exists.");
			return true;
		} else {
			Git.logger.trace("Git repository " + reposUrl + " does not exist.");
			return false;
		}
	}

	// Branch or null if detached.
	public static String getBranch(Path pathWorkspace) {
		CommandLine commandLine;
		StringBuilder stringBuilder;
		int exitCode;

		commandLine = (new CommandLine("git")).addArgument("symbolic-ref").addArgument("-q").addArgument("HEAD");
		stringBuilder = new StringBuilder();
		exitCode = Git.executeGitCommand(commandLine, AllowExitCode.ONE, pathWorkspace, stringBuilder);

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

	public static void config(Path pathWorkspace, String param, String value) {
		CommandLine commandLine;

		commandLine = new CommandLine("git");
		commandLine.addArgument("config").addArgument(param).addArgument(value);
		Git.executeGitCommand(commandLine, AllowExitCode.NONE, pathWorkspace, null);
	}

	public static void clone(String reposUrl, Version version, Path pathWorkspace) {
		CommandLine commandLine;
		boolean isDetachedHead;

		// The -b option takes a branch or tag name, but without the complete reference
		// prefix such as heads/master or tags/v-1.2.3. This means that it is not
		// straightforward to distinguish between branches and tags.
		commandLine = (new CommandLine("git")).addArgument("clone").addArgument("-b").addArgument(version.getVersion()).addArgument(reposUrl).addArgument(pathWorkspace.toString());

		Git.executeGitCommand(commandLine, AllowExitCode.NONE, null, null);

		// We need to verify the type of version checked out by checking whether we are in
		// a detached head state (tag) or not (branch).
		isDetachedHead = (Git.getBranch(pathWorkspace) == null);

		if ((version.getVersionType() == VersionType.DYNAMIC) && isDetachedHead) {
			try {
				FileUtils.deleteDirectory(pathWorkspace.toFile());
			} catch (IOException ioe) {}

			throw new RuntimeException("Requested version is dynamic but checked out version is a tag.");
		}

		if ((version.getVersionType() == VersionType.STATIC) && !isDetachedHead) {
			try {
				FileUtils.deleteDirectory(pathWorkspace.toFile());
			} catch (IOException ioe) {}

			throw new RuntimeException("Requested version is static but checked out version is a branch.");
		}
	}

	/**
	 * @param path Path.
	 * @param reposUrl. Can be null in which case fetch occurs from the remote named
	 *   "origin" within the configuration of the workspace.
	 * @param refspec The Git refspec to pass to git fetch. See the documentation for
	 *   git fetch for more information. Can be null if no refspec is to be passed to
	 *   git, letting git essentially use the default
	 *   refs/heads/*:refs/remotes/origin/heads/* refspec.
	 */
	public static void fetch(Path path, String reposUrl, String refspec) {
		CommandLine commandLine;

		commandLine = (new CommandLine("git")).addArgument("fetch");

		if (reposUrl != null) {
			commandLine.addArgument(reposUrl);
		}

		if (refspec != null) {
			if (reposUrl == null) {
				commandLine.addArgument("origin");
			}

			commandLine.addArgument(refspec);
		}

		Git.executeGitCommand(commandLine, AllowExitCode.NONE, path, null);
	}

	// Returns true if conflicts.
	public static boolean pull(Path pathWorkspace) {
		String branch;
		CommandLine commandLine;
		int exitCode;

		branch = Git.getBranch(pathWorkspace);

		if (branch == null) {
			throw new RuntimeException("Within " + pathWorkspace + " the HEAD is not a branch.");
		}

		commandLine = (new CommandLine("git")).addArgument("pull");

		exitCode = Git.executeGitCommand(commandLine, AllowExitCode.ONE, pathWorkspace, null);

		return exitCode == 1;
	}

	// Returns true if conflicts.
	public static boolean rebaseSimple(Path pathWorkspace) {
		String branch;
		CommandLine commandLine;
		int exitCode;

		branch = Git.getBranch(pathWorkspace);

		if (branch == null) {
			throw new RuntimeException("Within " + pathWorkspace + " the HEAD is not a branch.");
		}

		// Rebase onto the upstream tracking branch corresponding to the current branch.
		// This is the default behavior, but specifying @{u} is more symetrical with the
		// merge mode below.
		commandLine = (new CommandLine("git")).addArgument("rebase").addArgument("@{u}");

		exitCode = Git.executeGitCommand(commandLine, AllowExitCode.ONE, pathWorkspace, null);

		return exitCode == 1;
	}

	// Returns true if conflicts.
	public static boolean mergeSimple(Path pathWorkspace) {
		String branch;
		CommandLine commandLine;
		int exitCode;

		branch = Git.getBranch(pathWorkspace);

		if (branch == null) {
			throw new RuntimeException("Within " + pathWorkspace + " the HEAD is not a branch.");
		}

		commandLine = (new CommandLine("git")).addArgument("merge").addArgument("@{u}");

		exitCode = Git.executeGitCommand(commandLine, AllowExitCode.ONE, pathWorkspace, null);

		return exitCode == 1;
	}

	// If gitRef is not null we --set-upstream. This is to allow:
	// We always set the upstream tracking information because the push can be delayed
	// and new branches can be pushed on the call to this method other than
	// the one immediately after creating the branch.
	// In the case gitRef is a tag, --set-upstream has no effect.
	public static void push(Path pathWorkspace, String gitRef) {
		CommandLine commandLine;

		commandLine = (new CommandLine("git")).addArgument("push");

		if (gitRef != null) {
			commandLine.addArgument("--set-upstream").addArgument("origin").addArgument(gitRef + ":" + gitRef);
		}

		Git.executeGitCommand(commandLine, AllowExitCode.NONE, pathWorkspace, null);
	}

	public static void checkout(Path pathWorkspace, Version version) {
		CommandLine commandLine;
		boolean isDetachedHead;

		// The checkout command takes a branch or tag name, but without the complete
		// reference prefix such as heads/master or tags/v-1.2.3. This means that it is
		// not straightforward to distinguish between branches and tags.
		commandLine = (new CommandLine("git")).addArgument("checkout").addArgument(version.getVersion());

		Git.executeGitCommand(commandLine, AllowExitCode.NONE, pathWorkspace, null);

		// We need to verify the type of version checked out by checking whether we are in
		// a detached head state (tag) or not (branch).
		isDetachedHead = (Git.getBranch(pathWorkspace) == null);

		if ((version.getVersionType() == VersionType.DYNAMIC) && isDetachedHead) {
			try {
				FileUtils.deleteDirectory(pathWorkspace.toFile());
			} catch (IOException ioe) {}

			throw new RuntimeException("Requested version is dynamic but checked out version is a tag.");
		}

		if ((version.getVersionType() == VersionType.STATIC) && !isDetachedHead) {
			try {
				FileUtils.deleteDirectory(pathWorkspace.toFile());
			} catch (IOException ioe) {}

			throw new RuntimeException("Requested version is static but checked out version is a branch.");
		}
	}

	public static boolean isVersionExists(Path pathWorkspace, Version version) {
		CommandLine commandLine;

		// We add "--" as a last argument since when a ref does no exist, Git complains
		// about the fact that the command is ambiguous.
		commandLine = (new CommandLine("git")).addArgument("rev-parse").addArgument(Git.convertToRef(version)).addArgument("--");

		if (Git.executeGitCommand(commandLine, AllowExitCode.ALL, pathWorkspace, null) == 0) {
			Git.logger.trace("Version " + version + " exists.");
			return true;
		} else {
			Git.logger.trace("Version " + version + " does not exist.");
			return false;
		}
	}

	public static boolean isRemoteChanges(Path pathWorkspace) {
		String branch;
		CommandLine commandLine;
		StringBuilder stringBuilder;

		branch = Git.getBranch(pathWorkspace);

		if (branch == null) {
			throw new RuntimeException("Within " + pathWorkspace + " the HEAD is not a branch.");
		}

		// Verifying if the remote repository contains changes that are not in the local
		// repository involves verifying if there are remote commits that have not been
		// pulled into the local branch. This is done with the "git for-each-ref" command
		// which will provide us with "behind" information for the desired branch.
		// Note that "git status -sb" also provides this information, but only for the
		// current branch, whereas "git for-each-ref" can provide that information for any
		// branch.

		stringBuilder = new StringBuilder();
		commandLine = (new CommandLine("git")).addArgument("for-each-ref").addArgument("--format=%(upstream:track)").addArgument("refs/heads/" + branch);
		Git.executeGitCommand(commandLine, AllowExitCode.NONE, pathWorkspace, stringBuilder);

		return (stringBuilder.indexOf("behind") != -1);
	}

	public static boolean isLocalChanges(Path pathWorkspace) {
		String branch;
		CommandLine commandLine;
		StringBuilder stringBuilder;

		branch = Git.getBranch(pathWorkspace);

		if (branch == null) {
			throw new RuntimeException("Within " + pathWorkspace + " the HEAD is not a branch.");
		}

		// Verifying if a Git local repository contains changes that are not in the remote
		// repository involves verifying in the repository specified if there are local
		// changes that have not been committed. This is done with the "git status"
		// command.

		stringBuilder = new StringBuilder();
		commandLine = (new CommandLine("git")).addArgument("status").addArgument("-s");
		Git.executeGitCommand(commandLine, AllowExitCode.NONE, pathWorkspace, stringBuilder);

		return (stringBuilder.length() != 0);
	}

	public static void push(Path pathWorkspace) {
		CommandLine commandLine;

		// We always set the upstream tracking information because because pushes can be
		// delayed and new branches can be pushed on the call to this method other than
		// the one immediately after creating the branch.
		commandLine = (new CommandLine("git")).addArgument("push").addArgument("--all").addArgument("--set-upstream").addArgument("origin");
		Git.executeGitCommand(commandLine, AllowExitCode.NONE, pathWorkspace, null);

		// Unfortunately we cannot specify --all and --tags in the same command.
		commandLine = (new CommandLine("git")).addArgument("push").addArgument("--tags");
		Git.executeGitCommand(commandLine, AllowExitCode.NONE, pathWorkspace, null);
	}

	public static Version getVersion(Path pathWorkspace) {
		String branch;
		CommandLine commandLine;
		StringBuilder stringBuilder;

		branch = Git.getBranch(pathWorkspace);

		if (branch != null) {
			return new Version(VersionType.DYNAMIC, branch);
		}

		// branch is null it means we are in detached HEAD state and thus probably on a
		// tag.

		commandLine = (new CommandLine("git")).addArgument("describe").addArgument("--exact-match");
		stringBuilder = new StringBuilder();
		Git.executeGitCommand(commandLine, AllowExitCode.NONE, pathWorkspace, stringBuilder);

		return new Version(VersionType.STATIC, stringBuilder.toString());
	}

	public static List<Version> getListVersionStatic(Path pathWorkspace) {
		CommandLine commandLine;
		StringBuilder stringBuilder;
		BufferedReader bufferedReader;
		String tagLine;
		List<Version> listVersionStatic;

		try {
			commandLine = (new CommandLine("git")).addArgument("show-ref").addArgument("--tag").addArgument("-d");
			stringBuilder = new StringBuilder();
			Git.executeGitCommand(commandLine, AllowExitCode.NONE, pathWorkspace, stringBuilder);

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

	//TODO Document Push as well...
	// Should the caller be interested in knowing commit failed because unsynced, or caller must update before?
	public static void addCommitPush(Path pathWorkspace, String message, Map<String, String> mapCommitAttr) {
		String branch;
		CommandLine commandLine;

		branch = Git.getBranch(pathWorkspace);

		if (branch == null) {
			throw new RuntimeException("Within " + pathWorkspace + " the HEAD is not a branch.");
		}

		commandLine = (new CommandLine("git")).addArgument("add").addArgument("--all");
		Git.executeGitCommand(commandLine, AllowExitCode.NONE, pathWorkspace, null);

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
		Git.executeGitCommand(commandLine, AllowExitCode.NONE, pathWorkspace, null);

		Git.push(pathWorkspace, "refs/heads/" + branch);
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
	public static String convertToRef(Version version) {
		if (version.getVersionType() == VersionType.STATIC) {
			// "^{tag}" ensures we consider only annotated tags.
			return "refs/tags/" + version.getVersion() + "^{tag}";
		} else {
			return "refs/remotes/origin/" + version.getVersion();
		}
	}
}
