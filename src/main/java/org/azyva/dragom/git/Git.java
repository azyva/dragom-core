/*
 * Copyright 2015 - 2017 AZYVA INC. INC.
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

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.azyva.dragom.git.impl.DefaultGitImpl;
import org.azyva.dragom.model.Version;
import org.azyva.dragom.model.plugin.impl.GitScmPluginImpl;
import org.azyva.dragom.util.ServiceLocator;

/**
 * Git interface.
 * <p>
 * This interface defines the calls that are useful for Dragom. It is not meant to
 * be a full-featured implementation of the Git API, nor is it meant to be fully
 * polished.
 * <p>
 * Implementations of this interface are intended to be obtained using
 * {@link ServiceLocator} so they can easily be doubled for testing purposes. The
 * main implementation is {@link DefaultGitImpl} which therefore has a no-argument
 * constructor. This is why setup methods such as {@link #setPathExecutable} are
 * part of this interface.
 * <p>
 * Mainly used by {@link GitScmPluginImpl}. But also useful for Dragom test
 * scripts that need to act on repositories outside of Dragom tools themselves, to
 * simulate remote changes for instance.
 * <p>
 * Although an interface design is a good practice, its utility here is rather low
 * since implementing a double for testing purposes can be quite complex.
 * <p>
 * This class relies on Git being installed locally. It interfaces with the Git
 * command line.
 *
 * @author David Raymond
 */
public interface Git {
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
   * Ahead/behind information returned by {@link #getAheadBehindInfo}.
   */
  public static class AheadBehindInfo {
    public int ahead;
    public int behind;
  }

  /**
   * Sets the Path to the git executable. If not set, git is invoked with no
   * Path, relying on it being available in the environment PATH.
   *
   * @param pathExecutable See description.
   */
  void setPathExecutable(Path pathExecutable);

  /**
   * Sets the repository URL.
   *
   * @param reposUrl See description.
   */
  void setReposUrl(String reposUrl);

  /**
   * Sets the user to access Git repositories. If null (or not set) Git is
   * accessed without specifying credentials, which may or may not cause
   * credentials to be used, depending on how Git is configured.
   * <p>
   * If not null and the repository URL contains a user (i.e.:
   * https://jsmith@acme.com/my-repos.git), the user specified must be the same.
   *
   * @param user See description.
   */
  void setUser(String user);

  /**
   * Sets the password to access Git repositories. null if user (see
   * {@link #setUser}) is null.
   *
   * @param password See description.
   */
  void setPassword(String password);

  /**
   * Set the configured user name.
   *
   * <p>Used when creating commits.
   *
   * <p>If not set, the default behavior or the Git installation is used.
   *
   * @param configUserName See description.
   */
  void setConfigUserName(String configUserName);

  /**
   * Set the configured user email.
   *
   * <p>Used when creating commits.
   *
   * <p>If not set, the default behavior or the Git installation is used.
   *
   * @param configUserEmail See description.
   */
  void setConfigUserEmail(String configUserEmail);

  /**
   * Helper method to execute a Git command.
   * <p>
   * Mostly used internally, but can be used by callers in order to submit a Git
   * command that is not directly supported by this class.
   *
   * @param arrayArg Command line arguments to Git.
   * @param indProvideCredentials Indicates to provide the user credentials to
   *   Git, if available.
   * @param allowExitCode Specifies which exit codes are allowed and will not
   *   trigger an exception.
   * @param pathWorkingDirectory Path to the working directory. The command will be
   *   executed with this current working directory. If null current working directy
   *   is used.
   * @param stringBuilderOutput If not null, any output (stdout) of the command will
   *   be copied in this StringBuilder.
   * @param indTrimOutput Indicates to trim the output written to
   *   stringBuilderOutput (remove leading and trailing whitespace).
   * @return The exit code of the command.
   */
  int executeGitCommand(String[] arrayArg, boolean indProvideCredentials, AllowExitCode allowExitCode, Path pathWorkingDirectory, StringBuilder stringBuilderOutput, boolean indTrimOutput);

  /**
   * @return Indicates if the credentials provided are valid.
   */
  boolean validateCredentials();

  /**
   * @return Indicates if the Git repository exists.
   */
  boolean isReposExists();

  /**
   * Returns the current branch or null if detached.
   * <p>
   * Can be used to check if the current Version is branch by comparing to null.
   *
   * @param pathWorkspace Path to the workspace.
   * @return See description.
   */
  String getBranch(Path pathWorkspace);

  /**
   * Sets a configuration parameter within the Git workspace (local
   * repository).
   *
   * @param pathWorkspace Path to the workspace.
   * @param param Parameter name.
   * @param value Parameter value.
   */
  void config(Path pathWorkspace, String param, String value);

  /**
   * Git clone.
   *
   * @param reposUrl Repository URL. Can be null in which case the repository URL
   *   specified with {@link #setReposUrl} is used. Specifying the repository URL
   *   allows the caller to clone from a local repository. When reposUrl is
   *   specified it must use the file:// protocol.
   * @param version Version to checkout after clone. If null, no version is
   *   checked out, not even master.
   * @param pathWorkspace Path to the workspace.
   */
  void clone(String reposUrl, Version version, Path pathWorkspace);

  /**
   * Git fetch.
   *
   * @param path Path.
   * @param reposUrl Repository URL. Can be null in which case fetch occurs from
   *   the remote named "origin" within the configuration of the workspace, and thus
   *   implicitly from the remote repository specified with {@link #setReposUrl}.
   *   Can be a repository URL in which case refspec should be specified otherwise
   *   Git will not know which refs to update. This allows the caller to fetch
   *   between local repositories. When reposUrl is specified, it must use the
   *   file:// protocol.
   * @param refspec The Git refspec to pass to git fetch. See the documentation for
   *   git fetch for more information. Can be null if no refspec is to be passed to
   *   git, letting git essentially use the default
   *   refs/heads/*:refs/remotes/origin/heads/* refspec.
   * @param indFetchingIntoCurrentBranch This is to handle the special case where
   *   the caller knows what is fetched into is the current branch, meaning that
   *   refspec is specifed and ends with ":refs/heads/...". In such a case this
   *   method specifies the --update-head-ok option to "git fetch" (otherwise git
   *   complains that fetching into the current local branch is not allowed).
   * @param indForce Indicates to include the --force option. This is to handle the
   *   special case where fetch is used to update the remote references in a main
   *   workspace directory which has just been cloned from a system workspace
   *   directory. This special clone has made the remote references the main
   *   references in the original system workspace directory, which may be ahead
   *   of the true remote ones.
   */
  void fetch(Path path, String reposUrl, String refspec, boolean indFetchingIntoCurrentBranch, boolean indForce);

  /**
   * Git pull.
   *
   * @param pathWorkspace Path to the workspace.
   * @return true if conflicts are encountered.
   */
  boolean pull(Path pathWorkspace);

  /**
   * Rebases the current branch on its remote counterpart.
   *
   * @param pathWorkspace Path to the workspace.
   * @return true if conflicts are encountered.
   */
  boolean rebaseSimple(Path pathWorkspace);

  /**
   * Merges the current branch's remote counterpart into the current branch.
   *
   * @param pathWorkspace Path to the workspace.
   * @return true if conflicts are encountered.
   */
  boolean mergeSimple(Path pathWorkspace);

  // If gitRef is not null we --set-upstream. This is to allow:
  // We always set the
  /**
   * Git push.
   * <p>
   * If gitRef is not null --set-upstream used. This is to allow to always set
   * the upstream tracking information because the push can be delayed and new
   * branches can be pushed on the call to this method other than the one
   * immediately after creating the branch.
   * <p>
   * In the case gitRef is a tag, --set-upstream has no effect.
   * <p>
   * There are many opportunities for a push to fail, including rejection because of
   * non-fast-forward et lack of permissions. But it is hard to distinguish the
   * various cases. So this method considers any failure as unexpected.
   *
   * @param pathWorkspace Path to the workspace.
   * @param gitRef Git reference to push. If null, the current branch is pushed.
   */
  void push(Path pathWorkspace, String gitRef);

  /**
   * Git checkout.
   *
   * @param pathWorkspace Path to the workspace.
   * @param version Version to checkout.
   */
  void checkout(Path pathWorkspace, Version version);

  /**
   * Tests if a Version exists.
   *
   * @param pathWorkspace Path to the workspace.
   * @param version Version.
   * @return See description.
   */
  boolean isVersionExists(Path pathWorkspace, Version version);

  /**
   * Determines if the remote repository contains changes that are not in the
   * local repository (behind) and/or if the local repository contains changes
   * that are not in the remote repository (ahead).
   *
   * @param pathWorkspace Path to the workspace.
   * @return AheadBehindInfo.
   */
  Git.AheadBehindInfo getAheadBehindInfo(Path pathWorkspace);

  /**
   * Verifies there are local changes that are not pushed.
   *
   * @param pathWorkspace Path to the workspace.
   * @return See description.
   */
  boolean isLocalChanges(Path pathWorkspace);

  /**
   * Pushes all refs, branches and tags.
   * <p>
   * There are many opportunities for a push to fail, including rejection because of
   * non-fast-forward et lack of permissions. But it is hard to distinguish the
   * various cases. So this method considers any failure as unexpected.
   *
   * @param pathWorkspace Path to the workspace.
   */
  void push(Path pathWorkspace);

  /**
   * Returns the current version.
   * <p>
   * If not on a branch, returns the tag corresponding to the current commit,
   * if any.
   *
   * @param pathWorkspace Path to the workspace.
   * @return See description.
   */
  Version getVersion(Path pathWorkspace);

  /**
   * Returns the List of all static Version's (tags).
   *
   * @param pathWorkspace Path to the workspace.
   * @return See description.
   */
  List<Version> getListVersionStatic(Path pathWorkspace);

  /**
   * Creates a branch.
   *
   * @param pathWorkspace Path to the workspace.
   * @param branch Branch.
   * @param indSwitch Indicates whether we must switch (checkout) to the new
   *   branch.
   */
  void createBranch(Path pathWorkspace, String branch, boolean indSwitch);

  /**
   * Creates a tag.
   *
   * @param pathWorkspace Path to the workspace.
   * @param tag Tag.
   * @param message Tag message.
   */
  void createTag(Path pathWorkspace, String tag, String message);

  /**
   * Synchronizes the working copy with the index and commits.
   *
   * @param pathWorkspace Path to the workspace.
   * @param message Commit message.
   * @param mapCommitAttr Map of commit attributes.
   */
  // TODO: Should the caller be interested in knowing commit failed because unsynced, or caller must update before?
  void addCommit(Path pathWorkspace, String message, Map<String, String> mapCommitAttr);

  /**
   * Converts a {@link Version} to a Git reference such as refs/tags/&lt;tag&gt; if
   * the Version is static, or refs/remotes/origin/&lt;branch&gt; or
   * refs/heads/&lt;branch&gt; if it is dynamic.
   * <p>
   * In the case of a dynamic Version, the local branch is favored, unless it does
   * not exist, in which case the remote branch is used.
   *
   * @param pathWorkspace Path to the workspace.
   * @param version Version.
   * @return Git reference.
   */
  String convertToRef(Path pathWorkspace, Version version);
}