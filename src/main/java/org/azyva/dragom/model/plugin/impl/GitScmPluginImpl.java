/*
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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;
import org.azyva.dragom.execcontext.ExecContext;
import org.azyva.dragom.execcontext.plugin.CredentialStorePlugin;
import org.azyva.dragom.execcontext.plugin.RuntimePropertiesPlugin;
import org.azyva.dragom.execcontext.plugin.UserInteractionCallbackPlugin;
import org.azyva.dragom.execcontext.plugin.WorkspaceDir;
import org.azyva.dragom.execcontext.plugin.WorkspaceDirSystemModule;
import org.azyva.dragom.execcontext.plugin.WorkspaceDirUserModuleVersion;
import org.azyva.dragom.execcontext.plugin.WorkspacePlugin;
import org.azyva.dragom.execcontext.plugin.WorkspacePlugin.WorkspaceDirAccessMode;
import org.azyva.dragom.execcontext.support.ExecContextHolder;
import org.azyva.dragom.git.Git;
import org.azyva.dragom.git.Git.AllowExitCode;
import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.ModuleVersion;
import org.azyva.dragom.model.NodePath;
import org.azyva.dragom.model.Version;
import org.azyva.dragom.model.VersionType;
import org.azyva.dragom.model.event.DynamicVersionCreatedEvent;
import org.azyva.dragom.model.event.StaticVersionCreatedEvent;
import org.azyva.dragom.model.plugin.ScmPlugin;
import org.azyva.dragom.util.RuntimeExceptionUserError;
import org.azyva.dragom.util.ServiceLocator;
import org.azyva.dragom.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * ScmPlugin implementation that supports Git repositories using the git command
 * line client installed locally.
 * <p>
 * Credential handling is supported for the HTTP[S] protocol.
 * <p>
 * No credential handling (private key) is supported for the SSH protocol. It is
 * up to the user to correctly setup his git command line client.
 *
 * @author David Raymond
 */
public class GitScmPluginImpl extends ModulePluginAbstractImpl implements ScmPlugin {
  /**
   * Logger for the class.
   */
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
  private static final String EXEC_CONTEXT_PROPERTY_PREFIX_MAIN_WORKSPACE_DIR = "MAIN_WORKSPACE_DIR.";

  /**
   * Runtime property specifying how to handle HTTP[S] credentials. The possible
   * values are defined by {@link HttpCredentialHandling}.
   */
  private static final String RUNTIME_PROPERTY_GIT_HTTP_CREDENTIAL_HANDLING = "GIT_HTTP_CREDENTIAL_HANDLING";

  /**
   * Runtime property specifying the user to use for HTTP[S] credentials.
   */
  private static final String RUNTIME_PROPERTY_GIT_HTTP_USER = "GIT_HTTP_USER";

  /**
   * Runtime property specifying the path to the git executable.
   */
  private static final String RUNTIME_PROPERTY_GIT_PATH_EXECUTABLE = "GIT_PATH_EXECUTABLE";
  /**
   * Runtime property indicating the fetch and push behavior. The possible values
   * are defined by {@link FetchPushBehavior}.
   */
  private static final String RUNTIME_PROPERTY_GIT_FETCH_PUSH_BEHAVIOR = "GIT_FETCH_PUSH_BEHAVIOR";

  /**
   * The user may specify one of the FetchPushBehavior.*_NO_PUSH values for the
   * runtime property GIT_FETCH_PUSH_BEHAVIOR. In such as case, multiple unpushed
   * changes may accumulate in the repositories of the workspace. Furthermore these
   * changes can be in multiple branches of the local repositories. In order to help
   * the user perform these multiples pushes later, if this property is true, the
   * isSync method when enumSetIsSyncFlag contains IsSyncFlag.LOCAL_CHANGES pushes
   * all unpushed commits on all branches.
   * This property is intended to be used with the status command of
   * WorkspaceManagerTool.
   * The reason for not having an explicit method to perform such pushes is that
   * Dragom is not aware of Git's distributed nature. Specifically ScmPlugin does
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

  /**
   * Transient data that is a Set of Path's that have already been fetched and used
   * to optimize fetching. See {@link FetchPushBehavior}.
   */
  private static final String TRANSIENT_DATA_PATH_ALREADY_FETCHED = GitScmPluginImpl.class.getName() + ".PathAlreadyFetched";

  /**
   * Transient data prefix that caches {@link Git} for each {@link Module}. The
   * suffix is the {@link NodePath} of the Module.
   */
  private static final String TRANSIENT_DATA_PREFIX_GIT = GitScmPluginImpl.class.getName() + ".Git.";

  /**
   * Transient data prefix for keeping track of temporary dynamic Versions. The
   * suffix is the path to the module within the workspace. The value is the Version
   * based on which the temporary dynamic Version is created. The transient data is
   * not defined otherwise if there is no temporary dynamic Version.
   */
  private static final String TRANSIENT_DATA_PREFIX_TEMP_DYNAMIC_VERSION_BASE = GitScmPluginImpl.class.getName() + ".TempDynamicVersionBase.";

  /**
   * The base {@link Version} of a Version is stored as a commit attribute (commit
   * message) on the initial dummy commit of new branch branch and as a version
   * attribute (tag message) for tags. This is an implementation detail of
   * GitScmPluginImpl.
   * <p>
   * This Version attribute is used by {@link #createVersion} to store the base
   * Version, and by {@link #getBaseVersion} to retrieve it.
   */
  private static final String VERSION_ATTR_BASE_VERSION = "dragom-base-version";

  /**
   * ID of the commit at which a base {@link Version} was when a new Version for
   * which we are looking for the Version attributes was created. This is an
   * implementation detail of GitScmPluginImpl.
   * <p>
   * This information is used by {@link #getBaseVersion} to complete
   * {@link org.azyva.dragom.model.plugin.ScmPlugin.BaseVersion} fields.
   */
  private static final String VERSION_ATTR_BASE_VERSION_COMMIT_ID = "dragom-base-version-commit-id";

  /**
   * Default {@link Version}.
   */
  private static final Version VERSION_DEFAULT = new Version(VersionType.DYNAMIC, "master");

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_ACCESS_REMOTE_REPOS_FROM_WORKSPACE = "ACCESS_REMOTE_REPOS_FROM_WORKSPACE";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_ACCESS_REMOTE_REPOS = "ACCESS_REMOTE_REPOS";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_WARNING_MERGE_CONFLICTS = "WARNING_MERGE_CONFLICTS";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_PUSHING_UNPUSHED_COMMITS = "PUSHING_UNPUSHED_COMMITS";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_WARNING_UNPUSHED_COMMITS = "WARNING_UNPUSHED_COMMITS";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_NO_DIVERGING_COMMITS = "NO_DIVERGING_COMMITS";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_VERSIONS_EQUAL = "VERSIONS_EQUAL";

  /**
   * ResourceBundle specific to this class.
   */
  private static final ResourceBundle resourceBundle = ResourceBundle.getBundle(GitScmPluginImpl.class.getName() + "ResourceBundle");

  /**
   * Pattern to test if repository URL uses the HTTP[S] protocol.
   */
  private static final Pattern patternTestHttpProtocol = Pattern.compile("[hH][tT][tT][pP][sS]?://.*");

  /**
   * Defines the possible HTTP[S] credential handling modes.
   */
  private enum HttpCredentialHandling {
    /**
     * No credential handling is performed. It is up to the user to correctly setup
     * his git command line client, whatever the protocol used in the repository URLs.
     * <p>
     * The git client attempting to interact with the user to obtain credentials may
     * or may not work correctly, depending on how Dragom is deployed (GUI interface,
     * batch mode, etc.).
     */
    NONE,

    /**
     * Credential handling is performed only if the protocol used in the repository
     * URL is HTTP[S]. In this case, if the GIT_HTTP_USER runtime property is defined,
     * this user is imposed. Otherwise {@link CredentialStorePlugin} is used to obtain
     * the credentials.
     * <p>
     * If the protocol is not HTTP[S], no credential handling is performed (see
     * {@link #NONE}).
     * <p>
     * This is the default.
     */
    ONLY_IF_HTTP,

    /**
     * Repository URL must be HTTP[S] and the user defined by the GIT_HTTP_USER
     * runtime property is imposed.
     */
    ALWAYS_HTTP
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
     * to the remote repository, except for {@link Module}'s which are not in the
     * workspace for which a clone is required.
     */
    NO_FETCH_NO_PUSH,

    /**
     * Indicates to fetch, but not push. This implies working with the current state
     * of the remote repository, but not update it. Presumably the user will push
     * multiple changes after validating them.
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

  private String gitReposCompleteUrl;

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

  @Override
  public boolean isModuleExists() {
    Git git;

    git = this.getGit();

    ExecContextHolder.get().getExecContextPlugin(UserInteractionCallbackPlugin.class).provideInfo(MessageFormat.format(GitScmPluginImpl.resourceBundle.getString(GitScmPluginImpl.MSG_PATTERN_KEY_ACCESS_REMOTE_REPOS), this.gitReposCompleteUrl, "ls-remote (isModuleExists)"));

    return git.isReposExists();
  }

  @Override
  public Version getDefaultVersion() {
    return GitScmPluginImpl.VERSION_DEFAULT;
  }

  private void gitClone(Version version, Path pathRemote, Path pathModuleWorkspace) {
    Git git;
    String reposUrl;

    git = this.getGit();

    if (pathRemote != null) {
      this.gitFetch(pathRemote, null, null, false);

      reposUrl = "file://" + pathRemote.toAbsolutePath();

      // If the Path to a local remote repository was specified, we cannot checkout a
      // Version since only local branches of the local remote repository are considered
      // (as remotes of the cloned repository) and the branch corresponding to the
      // requested Version may not have been checked out and thus may not be available
      // as a remote, although it does exist in the true remote.
      git.clone(reposUrl, null, pathModuleWorkspace);

      // If the Path to a local remote repository was specified, we update that path to
      // the true URL of the remote repository for consistency. We want to have all of
      // the repositories to have as their origin remote that true URL and when we want
      // to use another local remote, we specify it explicitly.
      git.config(pathModuleWorkspace, "remote.origin.url", this.gitReposCompleteUrl);

      // Since we updated the remote repository above, we need to update all references
      // in order to obtain the true remote references.
      git.fetch(pathModuleWorkspace, reposUrl, "refs/remotes/origin/*:refs/remotes/origin/*", false, true);

      if (version != null) {
        git.checkout(pathModuleWorkspace, version);
      }
    } else {
      ExecContextHolder.get().getExecContextPlugin(UserInteractionCallbackPlugin.class).provideInfo(MessageFormat.format(GitScmPluginImpl.resourceBundle.getString(GitScmPluginImpl.MSG_PATTERN_KEY_ACCESS_REMOTE_REPOS_FROM_WORKSPACE), GitScmPluginImpl.this.gitReposCompleteUrl, pathModuleWorkspace, "clone"));

      // version can be null.
      git.clone(null, version, pathModuleWorkspace);

      // If pathRemote is null it means we cloned from the real remote repository. We
      // can therefore conclude that we have fetched from the remote.
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
      this.gitFetch(pathMainUserWorkspaceDir, null, null, false);

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
      this.gitFetch(pathModuleWorkspace, pathMainUserWorkspaceDir, "refs/remotes/origin/*:refs/remotes/origin/*", false);
    } else {
      // If the Workspace directory is the main one, we perform a regular fetch.
      // Note that depending on the GIT_FETCH_PUSH_BEHAVIOR runtime property it may be
      // the case that no fetch is actually performed by this call.
      this.gitFetch(pathModuleWorkspace, null, null, false);
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
   *   module and fetch must occur from the real remote repository (assumed to be
   *   named "origin").
   * @param refspec The Git refspec to pass to git fetch. See the documentation for
   *   git fetch for more information. Can be null if no refspec is to be passed to
   *   git, letting git essentially use the default
   *   refs/heads/*:refs/remotes/origin/heads/* refspec. Required if pathRemote is
   *   not null since this case is for the internal fetch optimization using a main
   *   repository and in such a case the refs to be updated must always be
   *   explicitly controlled and specified.
   * @param indFetchingIntoCurrentBranch This is to handle the special case where
   *   the caller knows what is fetched into is the current branch, meaning that
   *   refspec is specifed and ends with ":refs/heads/...". In such a case this
   *   method specifies the --update-head-ok option to "git fetch" (otherwise git
   *   complains that fetching into the current local branch is not allowed), and
   *   performs "git reset --hard HEAD" to ensure the working copy (and index)
   *   represent the potentially changed HEAD.
   */
  private void gitFetch(Path pathModuleWorkspace, Path pathRemote, String refspec, boolean indFetchingIntoCurrentBranch) {
    Git git;
    String reposUrl;

    git = this.getGit();

    if (pathRemote != null) {
      if (refspec == null) {
        throw new RuntimeException("refspec must not be null.");
      }

      reposUrl = "file://" + pathRemote.toAbsolutePath();
    } else {
      if (!this.mustFetch(pathModuleWorkspace)) {
        return;
      }

      ExecContextHolder.get().getExecContextPlugin(UserInteractionCallbackPlugin.class).provideInfo(MessageFormat.format(GitScmPluginImpl.resourceBundle.getString(GitScmPluginImpl.MSG_PATTERN_KEY_ACCESS_REMOTE_REPOS_FROM_WORKSPACE), GitScmPluginImpl.this.gitReposCompleteUrl, pathModuleWorkspace, "fetch" + ((refspec != null) ? (" refspec=" + refspec) : "")));

      // Causes the remote named "origin" to be used.
      reposUrl = null;
    }

    git.fetch(pathModuleWorkspace, reposUrl, refspec, indFetchingIntoCurrentBranch, false);

    if (indFetchingIntoCurrentBranch) {
      git.executeGitCommand(new String[] {"reset", "--hard", "HEAD"}, false, Git.AllowExitCode.NONE, pathModuleWorkspace, null, false);
    }

    // If pathRemote is null it means we cloned from the real remote repository. We
    // can therefore conclude that we have fetched from the remote.
    if (pathRemote == null) {
      this.hasFetched(pathModuleWorkspace);
    }
  }

  private boolean gitPull(Path pathModuleWorkspace) {
    Git git;
    String branch;
    RuntimePropertiesPlugin runtimePropertiesPlugin;
    Boolean indPullRebase;

    git = this.getGit();

    branch = git.getBranch(pathModuleWorkspace);

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
      return git.rebaseSimple(pathModuleWorkspace);
    } else {
      return git.mergeSimple(pathModuleWorkspace);
    }

    // We do not pull new commits that may exist in the same branch in the main
    // Workspace directory. See comment about same branch in main Workspace directory
    // in isSync method.
  }

  // TODO: The way push is handed is not symetric with fetch.
  // We first push the repository to the real remote, regardless of the main
  // workspace directory for the module.
  // Then, if there is a main workspace directory for the module, we fetch from
  // the one we just pushed, into the main one so that subsequent fetchs from the
  // main one will consider the new changes.
  private void push(Path pathModuleWorkspace, String gitRef) {
    Git git;
    String branch;
    NodePath nodePathModule;
    Path pathMainUserWorkspaceDir;

    git = this.getGit();

    branch = git.getBranch(pathModuleWorkspace);

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
      // supposed to be the current one so there is no danger of disturbing it.
      this.gitFetch(pathMainUserWorkspaceDir, pathModuleWorkspace, "refs/heads/" + branch + ":refs/heads/" + branch, false);

      // Then we perform a regular push within the main Workspace directory, but for the
      // current branch.
      // Note that depending on the GIT_FETCH_PUSH_BEHAVIOR runtime property it may be
      // the case that no push is actually performed by this call.
      this.gitPush(pathMainUserWorkspaceDir, "refs/heads/" + branch);

      // We also perform a relatively useless push from the current workspace directory.
      // Since the remote repository has already been pushed-to above, the only benefit
      // for this push is to update the remote tracking branch in the current workspace
      // directory.
      // TODO: May be optimized by only updating the remote tracking branch and not actually pushing nothing.
      this.gitPush(pathModuleWorkspace, "refs/heads/" + branch);

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
      this.gitFetch(pathModuleWorkspace, pathMainUserWorkspaceDir, "refs/remotes/origin/*:refs/remotes/origin/*", false);
    } else {
      // If the Workspace directory is the main one, we perform a regular push.
      // Note that depending on the GIT_FETCH_PUSH_BEHAVIOR runtime property it may be
      // the case that no push is actually performed by this call.
      this.gitPush(pathModuleWorkspace, gitRef);
    }
  }

  private void gitPush(Path pathModuleWorkspace, String gitRef) {
    Git git;

    git = this.getGit();

    if (!this.getFetchPushBehavior().isPush()) {
      GitScmPluginImpl.logger.trace("Pushing is disabled for module " + this.getModule() + " within " + pathModuleWorkspace + '.');
      return;
    }

    ExecContextHolder.get().getExecContextPlugin(UserInteractionCallbackPlugin.class).provideInfo(MessageFormat.format(GitScmPluginImpl.resourceBundle.getString(GitScmPluginImpl.MSG_PATTERN_KEY_ACCESS_REMOTE_REPOS_FROM_WORKSPACE), GitScmPluginImpl.this.gitReposCompleteUrl, pathModuleWorkspace, "push" + ((gitRef != null) ? (" gitRef=" + gitRef) : "")));

    // We always set the upstream tracking information (by passing gitRef) because
    // pushes can be delayed and new branches can be pushed on the call to this method
    // other than the one immediately after creating the branch. In the case gitRef is
    // a tag, --set-upstream used in Git.push has no effect.
    git.push(pathModuleWorkspace, gitRef);
  }

  @Override
  public void checkout(Version version, Path pathModuleWorkspace) {
    WorkspacePlugin workspacePlugin;
    NodePath nodePathModule;
    WorkspaceDirSystemModule workspaceDirSystemModule;
    Path pathMainUserWorkspaceDir;
    Path pathModuleWorkspaceRemote;

    try {
      // pathModuleWorkspace should be empty here, so this is not really useful.
      this.validateTempDynamicVersion(pathModuleWorkspace, false);

      workspacePlugin = ExecContextHolder.get().getExecContextPlugin(WorkspacePlugin.class);
      nodePathModule = this.getModule().getNodePath();

      if (workspacePlugin.getWorkspaceDirAccessMode(pathModuleWorkspace) != WorkspacePlugin.WorkspaceDirAccessMode.READ_WRITE) {
        throw new RuntimeException(pathModuleWorkspace.toString() + " must be accessed for writing.");
      }

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
    } catch (RuntimeExceptionUserError reue) {
      throw reue;
    } catch (RuntimeException re) {
      throw new RuntimeException("Could not checkout Version " + version + " of Module " + this.getModule() + '.');
    }
  }

  //TODO: The path must come from the workspace so that caller can check what kind it is.
  //TODO: Caller must release workspace directory. Was accessed READ_WRITE.
  // As a public method from the ScmPlugin interface, version must not be null.
  // But it is allowed to be null since internally it occurs that we need a directory
  // containing the module, but not a specific version.
  @Override
  public Path checkoutSystem(Version version) {
    Git git;
    WorkspacePlugin workspacePlugin;
    NodePath nodePathModule;
    WorkspaceDirUserModuleVersion workspaceDirUserModuleVersion;
    Path pathModuleWorkspace;
    WorkspaceDirSystemModule workspaceDirSystemModule;
    WorkspaceDirSystemModule workspaceDirSystemModuleConflict;
    Path pathMainUserWorkspaceDir;

    try {
      git = this.getGit();

      workspacePlugin = ExecContextHolder.get().getExecContextPlugin(WorkspacePlugin.class);
      nodePathModule = this.getModule().getNodePath();

      if (version != null) {
        // We first check if a user workspace directory exists for the ModuleVersion.

        workspaceDirUserModuleVersion = new WorkspaceDirUserModuleVersion(new ModuleVersion(nodePathModule, version));

        if (workspacePlugin.isWorkspaceDirExist(workspaceDirUserModuleVersion)) {
          // If the module is already checked out for the user, the path is reused as is,
          // without caring to make sure it is up to date. It belongs to the user who is
          // responsible for this, or the caller acting on behalf of the user.
          return workspacePlugin.getWorkspaceDir(workspaceDirUserModuleVersion, WorkspacePlugin.GetWorkspaceDirMode.ENUM_SET_GET_EXISTING, WorkspaceDirAccessMode.READ_WRITE);
        }
      } else {
        Set<WorkspaceDir> setWorkspaceDir;

        // If version is null, any user workspace directory for the Module will do.
        // Generally there will be only one, but some WorkspacePlugin implementations
        // could support multiple.
        setWorkspaceDir = workspacePlugin.getSetWorkspaceDir(new WorkspaceDirUserModuleVersion(new ModuleVersion(nodePathModule)));

        if (setWorkspaceDir.size() >= 1) {
          return workspacePlugin.getWorkspaceDir(setWorkspaceDir.iterator().next(), WorkspacePlugin.GetWorkspaceDirMode.ENUM_SET_GET_EXISTING, WorkspaceDirAccessMode.READ_WRITE);
        }
      }

      // If not, we check if a system workspace directory exists for the module.

      workspaceDirSystemModule = new WorkspaceDirSystemModule(nodePathModule);

      if (workspacePlugin.isWorkspaceDirExist(workspaceDirSystemModule)) {
        Version versionTempDynamicBase;

        // If a system workspace directory already exists for the module, we reuse it.
        // But it may not be up-to-date and may not have the requested version checked
        // out.

        pathModuleWorkspace = workspacePlugin.getWorkspaceDir(workspaceDirSystemModule, WorkspacePlugin.GetWorkspaceDirMode.ENUM_SET_GET_EXISTING, WorkspaceDirAccessMode.READ_WRITE);

        try {
          versionTempDynamicBase = this.getVersionTempDynamicBase(pathModuleWorkspace);

          // If a temporary dynamic Version is in effect in the module workspace directory,
          // we return immediately that directory, provided the requested Version
          // corresponds to the base Version of the temporary dynamic Version.
          // We expect the caller to not need to be aware of temporary dynamic Version
          // fact and not need use methods that are not allowed in which context, or
          // be aware of that fact and behave accordingly.
          if (versionTempDynamicBase != null) {
            if ((version != null) && !versionTempDynamicBase.equals(version)) {
              throw new RuntimeException("A temporary dynamic Version " + versionTempDynamicBase + " is in effect in " + pathModuleWorkspace + " but is not the same as the requested Version " + version + '.');
            }

            return pathModuleWorkspace;
          }

          this.fetch(pathModuleWorkspace);

          if (version != null) {
            git.checkout(pathModuleWorkspace, version);

            // If the version is dynamic (a branch), we might have actually checked out the
            // local version of it and it may not be up to date.
            if (version.getVersionType() == VersionType.DYNAMIC) {
            pathMainUserWorkspaceDir = this.getPathMainUserWorkspaceDir(nodePathModule);

              if ((pathMainUserWorkspaceDir != null) && !pathMainUserWorkspaceDir.equals(pathModuleWorkspace)) {
                // We add "--" as a last argument since when a ref does no exist, Git complains
                // about the fact that the command is ambiguous.
                if (git.executeGitCommand(new String[] {"rev-parse", "refs/heads/" + version.getVersion(), "--"}, false, AllowExitCode.ALL, pathMainUserWorkspaceDir, null, false) == 0) {
                  // If the Workspace directory is not the main one, we fetch the same branch from
                  // the main Workspace directory into the current Workspace directory.
                  // This is the special handling of the synchronization between a workspace
                  // directory and the main one mentioned in the isSync method.
                  this.gitFetch(pathModuleWorkspace, pathMainUserWorkspaceDir, "refs/heads/" + version.getVersion() + ":refs/heads/" + version.getVersion(), true);
                }
              }

              // And in all cases, we pull changes from the corresponding remote tracking
              // branch.
              if (this.gitPull(pathModuleWorkspace)) {
                throw new RuntimeException("Conflicts were encountered while pulling changes into " + pathModuleWorkspace + ". This is not expected here.");
              }
            }
          }
        } catch (Exception e) {
          // If an exception is thrown, we must not leave the workspace directory locked.
          if (pathModuleWorkspace != null) {
            workspacePlugin.releaseWorkspaceDir(pathModuleWorkspace);
          }

          throw e;
        }

        return pathModuleWorkspace;
      }

      // If not we know we will create a new system workspace directory.

      pathModuleWorkspace = null;

      try {
        // If there is already another Module which maps to the same workspace directory
        // we replace the Module. Note that this is not possible for user workspace
        // directories.

        workspaceDirSystemModuleConflict = (WorkspaceDirSystemModule)workspacePlugin.getWorkspaceDirConflict(workspaceDirSystemModule);

        if (workspaceDirSystemModuleConflict != null) {
          pathModuleWorkspace = workspacePlugin.getWorkspaceDir(workspaceDirSystemModuleConflict,  WorkspacePlugin.GetWorkspaceDirMode.ENUM_SET_GET_EXISTING, WorkspaceDirAccessMode.READ_WRITE);

          workspacePlugin.deleteWorkspaceDir(workspaceDirSystemModuleConflict);
        }

        // Here we are sure the workspace directory does not exist and can be created.

        pathModuleWorkspace = workspacePlugin.getWorkspaceDir(workspaceDirSystemModule,  WorkspacePlugin.GetWorkspaceDirMode.ENUM_SET_CREATE_NEW_NO_PATH, WorkspaceDirAccessMode.READ_WRITE);

        // But if there is a main user workspace directory for the Module (whatever the
        // Version) we want to clone from this directory instead of from the remote
        // repository to avoid network access.

        pathMainUserWorkspaceDir = this.getPathMainUserWorkspaceDir(nodePathModule);

        this.gitClone(version, pathMainUserWorkspaceDir, pathModuleWorkspace);
      } catch (Exception e) {
        if (pathModuleWorkspace != null) {
          workspacePlugin.deleteWorkspaceDir(workspaceDirSystemModule);

          FileUtils.deleteQuietly(pathModuleWorkspace.toFile());
        }

        throw e;
      }

      return pathModuleWorkspace;
    } catch (RuntimeExceptionUserError reue) {
      throw reue;
    } catch (RuntimeException re) {
      throw new RuntimeException("Could not checkout (system directory) Version " + version + " of Module " + this.getModule() + '.');
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
   * TODO: Need to talk about temporary dynamic Version and its impact here.
   *
   * @return Path to the module within the workspace.
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
      if (this.getVersionTempDynamicBase(pathModuleWorkspace) == null) {
        this.fetch(pathModuleWorkspace);
      }

      return pathModuleWorkspace;
    }

    workspaceDirSystemModule = new WorkspaceDirSystemModule(nodePathModule);

    if (workspacePlugin.isWorkspaceDirExist(workspaceDirSystemModule)) {
      pathModuleWorkspace = workspacePlugin.getWorkspaceDir(workspaceDirSystemModule,  WorkspacePlugin.GetWorkspaceDirMode.ENUM_SET_GET_EXISTING, WorkspaceDirAccessMode.PEEK);

      if (this.getVersionTempDynamicBase(pathModuleWorkspace) == null) {
        this.fetch(pathModuleWorkspace);
      }

      return pathModuleWorkspace;
    }

    // We are not interested in a specific Version at this point.
    pathModuleWorkspace = this.checkoutSystem(null);
    workspacePlugin.releaseWorkspaceDir(pathModuleWorkspace);
    this.fetch(pathModuleWorkspace);
    return pathModuleWorkspace;
  }

  @Override
  public boolean isVersionExists(Version version) {
    Git git;

    git = this.getGit();

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
    return git.isVersionExists(this.getPathModuleWorkspace(), version);
  }

  // TODO: Comments to review.
  // Handling the fact that the user may or may not want to push changes to the
  // remote repository during commits is not obvious. In the case where the user
  // does want to push changes we may be tempted here to verify if there are
  // unpushed local commits and return false if so. But the problem is that in
  // in response to this the caller (on behalf of the user) may call the commit
  // method in which case there may be nothing to commit (only already performed
  // local commits to push, which is not the same). So what we do instead in the
  // case where there are unpushed local commits is to simply perform the push and
  // return that the local repository is synchronized.
  // indExternal: To indicate the call comes from outside and issue the special warning.
  private boolean isSync(Path pathModuleWorkspace, EnumSet<IsSyncFlag> enumSetIsSyncFlag, boolean indExternal) {
    Git git;
    Git.AheadBehindInfo aheadBehindInfo;

    git = this.getGit();

    // If the Version in the workspace directory is not dynamic (a branch), we
    // immediately conclude that everything is synchronize since we rightfully
    // assume tags are immutable.
    if (git.getBranch(pathModuleWorkspace) == null) {
      return true;
    }

    // Note that depending on the GIT_FETCH_PUSH_BEHAVIOR runtime property it may be
    // the case that no fetch is actually performed by this call.
    this.fetch(pathModuleWorkspace);

    aheadBehindInfo = git.getAheadBehindInfo(pathModuleWorkspace);

    if (enumSetIsSyncFlag.contains(IsSyncFlag.REMOTE_CHANGES)) {
      if (aheadBehindInfo.behind != 0) {
        return false;
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

    if (enumSetIsSyncFlag.contains(IsSyncFlag.LOCAL_CHANGES)) {
      UserInteractionCallbackPlugin userInteractionCallbackPlugin;
      boolean indPushAll;

      userInteractionCallbackPlugin = ExecContextHolder.get().getExecContextPlugin(UserInteractionCallbackPlugin.class);

      // See GitScmPluginImpl.RUNTIME_PROPERTY_GIT_IND_PUSH_ALL
      if (indPushAll = this.isPushAll()) {
        userInteractionCallbackPlugin.provideInfo(MessageFormat.format(GitScmPluginImpl.resourceBundle.getString(GitScmPluginImpl.MSG_PATTERN_KEY_PUSHING_UNPUSHED_COMMITS), pathModuleWorkspace));
        git.push(pathModuleWorkspace);
      }

      if (git.isLocalChanges(pathModuleWorkspace)) {
        return false;
      }

      // If the special push behavior has not been performed, we want to issue a warning
      // if there are unpushed commits. In such a case we could be tempted to conclude
      // that the workspace directory is not synchronized. But this would be wrong since
      // Dragom is not aware of the the distributed nature of Git and indicating that
      // there are unsynchronized local changes could lead the caller to call commit in
      // order to commit the local changes, but no such commit would be performed. The
      // handling of unpushed changes is done under the hood using runtime properties
      // known only to this plugin and the users who use the tools developped with
      // Dragom.
      if (indExternal && !indPushAll && aheadBehindInfo.ahead != 0) {
        // It is not clear if it is OK for this plugin to use
        // UserInteractionCallbackPlugin as it would seem this plugin should operate at a
        // low level. But for now this seems to be the only way to properly inform the
        // user about what to do with the merge conflicts.
        userInteractionCallbackPlugin.provideInfo(MessageFormat.format(GitScmPluginImpl.resourceBundle.getString(GitScmPluginImpl.MSG_PATTERN_KEY_WARNING_UNPUSHED_COMMITS), pathModuleWorkspace));
      }
    }

    return true;
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
  // return that the local repository is synchronized.
  public boolean isSync(Path pathModuleWorkspace, EnumSet<IsSyncFlag> enumSetIsSyncFlag) {
    this.validateTempDynamicVersion(pathModuleWorkspace, false);

    return this.isSync(pathModuleWorkspace, enumSetIsSyncFlag, true);
  }

  @Override
  public boolean update(Path pathModuleWorkspace) {
    WorkspacePlugin workspacePlugin;

    this.validateTempDynamicVersion(pathModuleWorkspace, false);

    workspacePlugin = ExecContextHolder.get().getExecContextPlugin(WorkspacePlugin.class);

    if (workspacePlugin.getWorkspaceDirAccessMode(pathModuleWorkspace) != WorkspacePlugin.WorkspaceDirAccessMode.READ_WRITE) {
      throw new RuntimeException(pathModuleWorkspace.toString() + " must be accessed for writing.");
    }

    return this.gitPull(pathModuleWorkspace);
  }

  @Override
  public Version getVersion(Path pathModuleWorkspace) {
    Git git;

    git = this.getGit();

    this.validateTempDynamicVersion(pathModuleWorkspace, false);

    return git.getVersion(pathModuleWorkspace);
  }

  @Override
  public List<Commit> getListCommit(Version version, CommitPaging commitPaging, EnumSet<GetListCommitFlag> enumSetGetListCommitFlag) {
    return this.getListCommitDiverge(version, null, commitPaging, enumSetGetListCommitFlag);
  }

  // An internal implementation detail of this method is that if versionDest is null it behaves like getListCommit (to factor out common functionality).
  @Override
  @SuppressWarnings("unchecked")
  public List<Commit> getListCommitDiverge(Version versionSrc, Version versionDest, CommitPaging commitPaging, EnumSet<GetListCommitFlag> enumSetGetListCommitFlag) {
    Git git;
    Path pathModuleWorkspace;
    List<Commit> listCommit;
    StringBuilder stringBuilderCommits;
    List<String> listArg;
    String revisionRange;
    BufferedReader bufferedReaderCommits;
    Map<String, Object> mapTag = null; // Map value can be a simple tag name (String) or a list of tag names (List<String>) in the case more than one tag is associated with the same commit.
    String commitString;

    git = this.getGit();

    if ((commitPaging != null) && commitPaging.indDone) {
      throw new RuntimeException("getListCommit called after commit enumeration completed.");
    }

    pathModuleWorkspace = this.getPathModuleWorkspace();

    try {
      listCommit = new ArrayList<Commit>();

      stringBuilderCommits = new StringBuilder();
      listArg = new ArrayList<String>();
      listArg.add("rev-list");
      listArg.add("--pretty=oneline");

      if (commitPaging != null) {
        if (commitPaging.startIndex != 0) {
          listArg.add("--skip=" + commitPaging.startIndex);
        }

        if (commitPaging.maxCount != -1) {
          listArg.add("--max-count=" + commitPaging.maxCount);
        }
      }

      revisionRange = git.convertToRef(pathModuleWorkspace,  versionSrc);

      if (versionDest != null) {
        revisionRange = git.convertToRef(pathModuleWorkspace, versionDest) + ".." + revisionRange;
      }

      listArg.add(revisionRange);

      // We add "--" as a last argument since when a ref does no exist, Git complains
      // about the fact that the command is ambiguous.
      listArg.add("--");

      // The empty String[] argument to toArray is required for proper typing in Java.
      git.executeGitCommand(listArg.toArray(new String[] {}), false, AllowExitCode.NONE, pathModuleWorkspace, stringBuilderCommits, true);

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

        stringBuilderTags = new StringBuilder();

        // It seems show-ref returns 1 when no reference is returned. This is not an
        // exception.
        git.executeGitCommand(new String[] {"show-ref", "--tag", "-d"}, false, Git.AllowExitCode.ONE, pathModuleWorkspace, stringBuilderTags, true);

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

        mapCommitAttr = Util.getJsonAttr(commitMessage, null);

        // For dynamic Versions, the Version attribute dragom-base-version is actually
        // stored as a commit attribute in Git since Git does not support messages for
        // branches as it does for (annotated) tags.
        // This commit attribute is specified for a dummy commit introduced when a new
        // branch is created and we use that attribute as an indication of the start of
        // the branch, which is otherwise not known to Git.
        if (mapCommitAttr.get(GitScmPluginImpl.VERSION_ATTR_BASE_VERSION) != null) {
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
                commit.arrayVersionStatic[i] = new Version(VersionType.STATIC, listTagName.get(i));
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
    Map<String, String> mapVersionAttr;
    String stringBaseVersion;

    mapVersionAttr = this.getMapVersionAttr(version);
    stringBaseVersion = mapVersionAttr.get(GitScmPluginImpl.VERSION_ATTR_BASE_VERSION);

    if (stringBaseVersion != null) {
      BaseVersion baseVersion;

      baseVersion = new BaseVersion();

      baseVersion.version = version;
      baseVersion.versionBase = new Version(stringBaseVersion);
      baseVersion.commitId = mapVersionAttr.get(GitScmPluginImpl.VERSION_ATTR_BASE_VERSION_COMMIT_ID);

      return baseVersion;
    } else {
      return null;
    }
  }

  @Override
  public List<Version> getListVersionStatic() {
    Git git;

    git = this.getGit();

    return git.getListVersionStatic(this.getPathModuleWorkspace());
  }

  @Override
  public void switchVersion(Path pathModuleWorkspace, Version version) {
    Git git;
    WorkspacePlugin workspacePlugin;
    WorkspaceDir workspaceDir;

    git = this.getGit();

    this.validateTempDynamicVersion(pathModuleWorkspace, false);

    /* gitFetch will have been called by isSync.
     */
    if (!this.isSync(pathModuleWorkspace, IsSyncFlag.ALL_CHANGES, false)) {
      throw new RuntimeException("Working directory " + pathModuleWorkspace + " must be synchronized before switching to a new version.");
    }

    workspacePlugin = ExecContextHolder.get().getExecContextPlugin(WorkspacePlugin.class);
    workspaceDir = workspacePlugin.getWorkspaceDirFromPath(pathModuleWorkspace);

    if (!(workspaceDir instanceof WorkspaceDirUserModuleVersion)) {
      throw new RuntimeException("Workspace directory " + pathModuleWorkspace + " must be a user workspace directory.");
    }

    // This will implicitly be validated by the call to
    // workspacePlugin.updateWorkspaceDir below. But it is cleaner to validate
    // explicitly before.
    if (workspacePlugin.getWorkspaceDirAccessMode(pathModuleWorkspace) != WorkspacePlugin.WorkspaceDirAccessMode.READ_WRITE) {
      throw new RuntimeException(pathModuleWorkspace.toString() + " must be accessed for writing.");
    }

    // If the new version is the same as the current one, there is nothing to do.
    if (version.equals(this.getVersion(pathModuleWorkspace))) {
      return;
    }

    git.checkout(pathModuleWorkspace, version);

    // After switching to a dynamic Version, the workspace may not be synchronized
    // since the corresponding branch may already be present locally in the workspace
    // and changes may have been performed outside of the workspace. We therefore
    // ensure that it is up to date.
    if ((version.getVersionType() == VersionType.DYNAMIC) && this.gitPull(pathModuleWorkspace)) {
      throw new RuntimeException("Conflicts were encountered while pulling changes into " + pathModuleWorkspace + ". This is not expected here.");
    }

    /* The WorkspaceDir belongs to the user and it is specific to a version and the
     * new version must be reflected in the workspace.
     */
    // TODO: Eventually must think about the implications of this if workspace allows for multiple versions of same module.
    // What if the new version already exists? We will probably have to delete one of them, but which one.
    // Have to deal with main repository if ever it is the one deleted.
    workspacePlugin.updateWorkspaceDir(workspaceDir, new WorkspaceDirUserModuleVersion(new ModuleVersion(((WorkspaceDirUserModuleVersion)workspaceDir).getModuleVersion().getNodePath(), version)));
  }

  @Override
  public void createVersion(Path pathModuleWorkspace, Version versionTarget, Map<String, String> mapVersionAttr, boolean indSwitch) {
    Git git;
    Version versionTempDynamicBase;
    WorkspacePlugin workspacePlugin;
    Map<String, String> mapVersionAttr2;
    String message;
    WorkspaceDir workspaceDir;
    boolean indUserWorkspaceDir;

    git = this.getGit();

    versionTempDynamicBase = this.getVersionTempDynamicBase(pathModuleWorkspace);

    // In the case of a temporary dynamic Version we do not check for synchronization
    // since by definition the temporary dynamic Version is not synchronized with the
    // remote.
    if (versionTempDynamicBase == null) {
      /* gitFetch will have been called by isSync.
       */
      if (!this.isSync(pathModuleWorkspace, IsSyncFlag.ALL_CHANGES, false)) {
        throw new RuntimeException("Working directory " + pathModuleWorkspace + " must be synchronized before creating a new version.");
      }
    }

    workspacePlugin = ExecContextHolder.get().getExecContextPlugin(WorkspacePlugin.class);

    if (workspacePlugin.getWorkspaceDirAccessMode(pathModuleWorkspace) != WorkspacePlugin.WorkspaceDirAccessMode.READ_WRITE) {
      throw new RuntimeException(pathModuleWorkspace.toString() + " must be accessed for writing.");
    }

    workspaceDir = workspacePlugin.getWorkspaceDirFromPath(pathModuleWorkspace);
    indUserWorkspaceDir = workspaceDir instanceof WorkspaceDirUserModuleVersion;

    // We prepare the prefix commit or tag message specifying the base version since
    // it is the same for both Version types.
    // See commit() method for comments on commit message, more specifically the need
    // to escape double quotes.

    if (mapVersionAttr != null) {
      // If Version attributes are specified, we simply make a copy of the Map provided
      // by the caller to avoid modifying it.
      mapVersionAttr2 = new HashMap<String, String>(mapVersionAttr);
    } else {
      mapVersionAttr2 = new HashMap<String, String>();
    }

    // If there is a temporary dynamic Version, it's base it taken as the base of the
    // new Version. Otherwise, the base of the new Version is the current Version
    // itself.
    if (versionTempDynamicBase == null) {
      mapVersionAttr2.put(GitScmPluginImpl.VERSION_ATTR_BASE_VERSION, this.getVersion(pathModuleWorkspace).toString());
    } else {
      mapVersionAttr2.put(GitScmPluginImpl.VERSION_ATTR_BASE_VERSION, versionTempDynamicBase.toString());
    }

    try {
      message = (new ObjectMapper()).writeValueAsString(mapVersionAttr2);
    } catch (IOException ioe) {
      throw new RuntimeException(ioe);
    }

    message = message.replace("\"", "\\\"");

    switch (versionTarget.getVersionType()) {
    case DYNAMIC:
      String branch;

      branch = versionTarget.getVersion();

      git.createBranch(pathModuleWorkspace, branch, indSwitch);

      // In all cases, creating a new Version based on a tempoary dynamic Version
      // releases it.
      if (versionTempDynamicBase != null) {
        ExecContextHolder.get().setTransientData(GitScmPluginImpl.TRANSIENT_DATA_PREFIX_TEMP_DYNAMIC_VERSION_BASE + pathModuleWorkspace, null);
      }

      if (indSwitch) {
        /* If the WorkspaceDir belongs to the user, it is specific to a version and the
         * new version must be reflected in the workspace.
         */
        if (workspaceDir instanceof WorkspaceDirUserModuleVersion) {
          workspacePlugin.updateWorkspaceDir(workspaceDir, new WorkspaceDirUserModuleVersion(new ModuleVersion(((WorkspaceDirUserModuleVersion)workspaceDir).getModuleVersion().getNodePath(), versionTarget)));
        }
      }

      if (!indSwitch) {
        // If no switch was requested and we were on a temporary dynamic Version, we must
        // revert back to the original Version, but this is pertinent only in the case of
        // a user workspace directory since for a system workspace directory, the contents
        // will be replaced anyways below.
        if (versionTempDynamicBase != null && indUserWorkspaceDir) {
          git.checkout(pathModuleWorkspace, versionTempDynamicBase);
        }

        // If the caller did not ask to switch we must not. But we still need access to
        // the new Version in order to introduce the dummy base-version commit.
        pathModuleWorkspace = this.checkoutSystem(versionTarget);
      }

      try {
        message += " Dummy commit introduced to record the version attributes including the base version of the newly created version " + versionTarget + '.';

        git.executeGitCommand(new String[] {"commit", "--allow-empty", "-m", message}, false, AllowExitCode.NONE, pathModuleWorkspace, null, false);

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

      git.createTag(pathModuleWorkspace, tag, message);

      // In all cases, creating a new Version based on a temporary dynamic Version
      // releases it.
      if (versionTempDynamicBase != null) {
        ExecContextHolder.get().setTransientData(GitScmPluginImpl.TRANSIENT_DATA_PREFIX_TEMP_DYNAMIC_VERSION_BASE + pathModuleWorkspace, null);
      }

      if (indSwitch) {
        /* The following essentially performs the same thing as the method switchVersion,
         * but switchVersion calls isSync which fails when a new unpushed branch is
         * present.
         * TODO: Irrelevant comment, unless this is true also for tags. Need to test.
         */
        git.checkout(pathModuleWorkspace, versionTarget);

        /* If the WorkspaceDir belongs to the user, it is specific to a version and the
         * new version must be reflected in the workspace.
         */
        if (workspaceDir instanceof WorkspaceDirUserModuleVersion) {
          workspacePlugin.updateWorkspaceDir(workspaceDir, new WorkspaceDirUserModuleVersion(new ModuleVersion(((WorkspaceDirUserModuleVersion)workspaceDir).getModuleVersion().getNodePath(), versionTarget)));
        }
      } else if (versionTempDynamicBase != null) {
        // If no switch was requested and we were on a temporary dynamic Version, we must
        // revert back to the original Version.
        git.checkout(pathModuleWorkspace, versionTempDynamicBase);
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
  public Map<String, String> getMapVersionAttr(Version version) {
    Git git;
    Path pathModuleWorkspace;
    Map<String, String> mapVersionAttr;
    String stringBaseVersion;

    git = this.getGit();

    pathModuleWorkspace = this.getPathModuleWorkspace();

    // We must preallocate the Map since we want to append to it after.
    mapVersionAttr = new HashMap<String, String>();

    switch (version.getVersionType()) {
    case DYNAMIC:
      StringBuilder stringBuilderCommits;
      BufferedReader bufferedReaderCommits;
      String commitString;

      stringBuilderCommits = new StringBuilder();
      git.executeGitCommand(new String[] {"rev-list", "--pretty=oneline", git.convertToRef(pathModuleWorkspace, version)}, false, Git.AllowExitCode.NONE, pathModuleWorkspace, stringBuilderCommits, true);

      bufferedReaderCommits = new BufferedReader(new StringReader(stringBuilderCommits.toString()));

      try {
        while ((commitString = bufferedReaderCommits.readLine()) != null) {
          int indexSplit;
          String commitMessage;

          indexSplit = commitString.indexOf(' ');
          commitMessage = commitString.substring(indexSplit + 1);

          // We create the Map in advance since if we let Util.getJsonAttr do it, it may be
          // empty and immutable.
          mapVersionAttr = new HashMap<String, String>();

          Util.getJsonAttr(commitMessage, mapVersionAttr);

          // For dynamic Versions, the Version attribute dragom-base-version is actually
          // stored as a commit attribute in Git since Git does not support messages for
          // branches as it does for (annotated) tags.

          // For dynamic Versions, Version attributes are stored as commit attributes on the
          // first dummy commit of the branch. We locate that first commit given the Version
          // attribute dragom-base-version.
          stringBaseVersion = mapVersionAttr.get(GitScmPluginImpl.VERSION_ATTR_BASE_VERSION);

          if (stringBaseVersion != null) {
            commitString = bufferedReaderCommits.readLine();
            mapVersionAttr.put(GitScmPluginImpl.VERSION_ATTR_BASE_VERSION_COMMIT_ID, commitString.substring(0, commitString.indexOf(' ')));

            return mapVersionAttr;
          }
        }
      } catch (IOException ioe) {
        throw new RuntimeException(ioe);
      }

      // Generally we do not expect to get here since for all Version's created by
      // Dragom, at least the dragom-base-version Version attribute is created.
      // But it is too risky to raise a RuntimeException since Dragom could be used with
      // existing repositories.
      return Collections.<String, String>emptyMap();

    case STATIC:
      StringBuilder stringBuilder;
      String tagMessage;

      stringBuilder = new StringBuilder();
      git.executeGitCommand(new String[] {"tag", "-n", "-l", version.getVersion()}, false, Git.AllowExitCode.NONE, pathModuleWorkspace, stringBuilder, true);

      if (stringBuilder.toString().isEmpty()) {
        throw new RuntimeException("Static version " + version + " does not exist.");
      }

      tagMessage = stringBuilder.toString().split("\\s+")[1];

      // We create the Map in advance since if we let Util.getJsonAttr do it, it may be
      // empty and immutable.
      mapVersionAttr = new HashMap<String, String>();

      Util.getJsonAttr(tagMessage, mapVersionAttr);

      stringBuilder.setLength(0);
      git.executeGitCommand(new String[] {"rev-parse", version.getVersion() + "^{}"}, false, Git.AllowExitCode.NONE, pathModuleWorkspace, stringBuilder, true);

      if (stringBuilder.toString().isEmpty()) {
        throw new RuntimeException("Static version " + version + " does not exist.");
      }

      mapVersionAttr.put(GitScmPluginImpl.VERSION_ATTR_BASE_VERSION_COMMIT_ID, stringBuilder.toString());

      return mapVersionAttr;

    default:
      throw new RuntimeException("Invalid version type.");
    }
  }

  @Override
  public void createTempDynamicVersion(Path pathModuleWorkspace) {
    Git git;
    WorkspacePlugin workspacePlugin;
    Version versionCurrent;

    git = this.getGit();

    this.validateTempDynamicVersion(pathModuleWorkspace, false);

    workspacePlugin = ExecContextHolder.get().getExecContextPlugin(WorkspacePlugin.class);

    if (workspacePlugin.getWorkspaceDirAccessMode(pathModuleWorkspace) != WorkspacePlugin.WorkspaceDirAccessMode.READ_WRITE) {
      throw new RuntimeException(pathModuleWorkspace.toString() + " must be accessed for writing.");
    }

    versionCurrent = this.getVersion(pathModuleWorkspace);

    git.executeGitCommand(new String[] {"checkout", "--detach"}, false, Git.AllowExitCode.NONE, pathModuleWorkspace, null, false);

    ExecContextHolder.get().setTransientData(GitScmPluginImpl.TRANSIENT_DATA_PREFIX_TEMP_DYNAMIC_VERSION_BASE + pathModuleWorkspace, versionCurrent);
  }

  @Override
  public void releaseTempDynamicVersion(Path pathModuleWorkspace) {
    Git git;
    ExecContext execContext;
    WorkspacePlugin workspacePlugin;
    Version versionTempDynamicBase;

    git = this.getGit();

    this.validateTempDynamicVersion(pathModuleWorkspace, true);

    versionTempDynamicBase = this.getVersionTempDynamicBase(pathModuleWorkspace);

    execContext = ExecContextHolder.get();
    workspacePlugin = execContext.getExecContextPlugin(WorkspacePlugin.class);

    if (workspacePlugin.getWorkspaceDirAccessMode(pathModuleWorkspace) != WorkspacePlugin.WorkspaceDirAccessMode.READ_WRITE) {
      throw new RuntimeException(pathModuleWorkspace.toString() + " must be accessed for writing.");
    }

    // Releasing the temporary dynamic Version implies switching batck to the original
    // Version.
    git.checkout(pathModuleWorkspace, versionTempDynamicBase);

    execContext.setTransientData(GitScmPluginImpl.TRANSIENT_DATA_PREFIX_TEMP_DYNAMIC_VERSION_BASE + pathModuleWorkspace,  null);
  }

  /**
   * Returns the base of the temporary dynamic Version or null if there is no
   * temporary dynamic Version.
   *
   * @param pathModuleWorkspace Path to the module within the workspaace.
   * @return See description.
   */
  private Version getVersionTempDynamicBase(Path pathModuleWorkspace) {
    return (Version)ExecContextHolder.get().getTransientData(GitScmPluginImpl.TRANSIENT_DATA_PREFIX_TEMP_DYNAMIC_VERSION_BASE + pathModuleWorkspace);
  }

  @Override
  public boolean isTempDynamicVersion(Version versionBase) {
    WorkspacePlugin workspacePlugin;
    NodePath nodePathModule;
    Path pathModuleWorkspace;
    WorkspaceDirSystemModule workspaceDirSystemModule;

    workspacePlugin = ExecContextHolder.get().getExecContextPlugin(WorkspacePlugin.class);
    nodePathModule = this.getModule().getNodePath();

    pathModuleWorkspace = this.getPathMainUserWorkspaceDir(nodePathModule);

    if ((pathModuleWorkspace != null) && (this.getVersionTempDynamicBase(pathModuleWorkspace) != null)) {
      return true;
    }

    workspaceDirSystemModule = new WorkspaceDirSystemModule(nodePathModule);

    if (workspacePlugin.isWorkspaceDirExist(workspaceDirSystemModule)) {
      pathModuleWorkspace = workspacePlugin.getWorkspaceDir(workspaceDirSystemModule,  WorkspacePlugin.GetWorkspaceDirMode.ENUM_SET_GET_EXISTING, WorkspaceDirAccessMode.PEEK);

      return this.getVersionTempDynamicBase(pathModuleWorkspace) != null;
    }

    return false;
  }

  /**
   * Validates the state of the temporary dynamic Version being created or not based
   * on a provided expected state.
   * <p>
   * An exception is thrown if the states do not match.
   *
   * @param pathModuleWorkspace Path to the module within the workspaace.
   * @param indTempDynamicVersionRequired Expected state.
   */
  private void validateTempDynamicVersion(Path pathModuleWorkspace, boolean indTempDynamicVersionRequired) {
    boolean indTempDynamicVersion;

    indTempDynamicVersion = this.getVersionTempDynamicBase(pathModuleWorkspace) != null;

    if (indTempDynamicVersion ^ indTempDynamicVersion) {
      throw new RuntimeException("Mismatch between current temporary dynamic Version state " + indTempDynamicVersion + " and expected state " + indTempDynamicVersionRequired + " for workspace module path " + pathModuleWorkspace + '.');
    }
  }

  @Override
  //TODO Document Push as well...
  // Should the caller be interested in knowing commit failed because unsynced, or caller must update before?
  // Caller should not specify version attributes, especially dragom-base-version, for a commit attribute, even if in Git version attributes are stored as commit attributes on the first dummy commit.
  public void commit(Path pathModuleWorkspace, String message, Map<String, String> mapCommitAttr) {
    Git git;
    boolean indTempDynamicVersion;
    WorkspacePlugin workspacePlugin;

    git = this.getGit();

    indTempDynamicVersion = this.getVersionTempDynamicBase(pathModuleWorkspace) != null;

    // In the case of a temporary dynamic Version we do not check for synchronization
    // since by definition the temporary dynamic Version is not synchronized with the
    // remote.
    if (indTempDynamicVersion) {
      if (!this.isSync(pathModuleWorkspace, IsSyncFlag.REMOTE_CHANGES_ONLY, false)) {
        throw new RuntimeException("Working directory " + pathModuleWorkspace + " must be synchronized with remote changes before committing.");
      }
    }

    workspacePlugin = ExecContextHolder.get().getExecContextPlugin(WorkspacePlugin.class);

    if (workspacePlugin.getWorkspaceDirAccessMode(pathModuleWorkspace) != WorkspacePlugin.WorkspaceDirAccessMode.READ_WRITE) {
      throw new RuntimeException(pathModuleWorkspace.toString() + " must be accessed for writing.");
    }

    // We pass false for indPush since we are pushing explicitly below in order
    // to handle the main workspace directory.
    git.addCommit(pathModuleWorkspace, message, mapCommitAttr, false);

    // We push only if the version is not a temporay dynamic one.
    if (!indTempDynamicVersion) {
      // TODO: Maybe we couild pass null for gitRef since the upstream may always already be set in the case of a commit. But not sure.
      this.push(pathModuleWorkspace, "refs/heads/" + git.getBranch(pathModuleWorkspace));
    }
  }


  @Override
  public MergeResult merge(Path pathModuleWorkspace, Version versionSrc, String message) {
    Git git;
    Version versionDest;
    WorkspacePlugin workspacePlugin;
    UserInteractionCallbackPlugin userInteractionCallbackPlugin;
    List<ScmPlugin.Commit> listCommit;
    String mergeMessage;

    git = this.getGit();

    this.validateTempDynamicVersion(pathModuleWorkspace, false);

    versionDest = this.getVersion(pathModuleWorkspace);

    if (versionDest.getVersionType() == VersionType.STATIC) {
      throw new RuntimeException("Current version " + versionDest + " in working directory " + pathModuleWorkspace + " must be dynamic for merging into.");
    }

    // gitFetch will have been called by isSync.
    if (!this.isSync(pathModuleWorkspace, IsSyncFlag.ALL_CHANGES, false)) {
      throw new RuntimeException("Working directory " + pathModuleWorkspace + " must be synchronized before merging.");
    }

    workspacePlugin = ExecContextHolder.get().getExecContextPlugin(WorkspacePlugin.class);

    if (workspacePlugin.getWorkspaceDirAccessMode(pathModuleWorkspace) != WorkspacePlugin.WorkspaceDirAccessMode.READ_WRITE) {
      throw new RuntimeException(pathModuleWorkspace.toString() + " must be accessed for writing.");
    }

    userInteractionCallbackPlugin = ExecContextHolder.get().getExecContextPlugin(UserInteractionCallbackPlugin.class);

    listCommit = this.getListCommitDiverge(versionSrc, versionDest, null, null);

    if (listCommit.isEmpty()) {
      userInteractionCallbackPlugin.provideInfo(MessageFormat.format(GitScmPluginImpl.resourceBundle.getString(GitScmPluginImpl.MSG_PATTERN_KEY_NO_DIVERGING_COMMITS), pathModuleWorkspace, versionSrc, versionDest));
      return MergeResult.NOTHING_TO_MERGE;
    }

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

    if (git.executeGitCommand(new String[] {"merge", "--no-edit", "--no-ff", "-m", mergeMessage, git.convertToRef(pathModuleWorkspace, versionSrc)}, false, Git.AllowExitCode.ONE, pathModuleWorkspace, null, false) == 1) {
      return MergeResult.CONFLICTS;
    }

    this.push(pathModuleWorkspace, "refs/heads/" + versionDest.getVersion());

    return MergeResult.MERGED;
  }

  @Override
  public MergeResult mergeExcludeCommits(Path pathModuleWorkspace, Version versionSrc, List<Commit> listCommitExclude, String message) {
    Git git;
    Version versionDest;
    WorkspacePlugin workspacePlugin;
    UserInteractionCallbackPlugin userInteractionCallbackPlugin;
    StringBuilder stringBuilderMergeMessage;
    List<ScmPlugin.Commit> listCommit;
    String commitIdRangeStart;
    Iterator<Commit> iteratorCommit;
    int patchCount;

    git = this.getGit();

    this.validateTempDynamicVersion(pathModuleWorkspace, false);

    versionDest = this.getVersion(pathModuleWorkspace);

    if (versionDest.getVersionType() == VersionType.STATIC) {
      throw new RuntimeException("Current version " + versionDest + " in working directory " + pathModuleWorkspace + " must be dynamic for merging into.");
    }

    // gitFetch will have been called by isSync.
    if (!this.isSync(pathModuleWorkspace, IsSyncFlag.ALL_CHANGES, false)) {
      throw new RuntimeException("Working directory " + pathModuleWorkspace + " must be synchronized before merging.");
    }

    workspacePlugin = ExecContextHolder.get().getExecContextPlugin(WorkspacePlugin.class);

    if (workspacePlugin.getWorkspaceDirAccessMode(pathModuleWorkspace) != WorkspacePlugin.WorkspaceDirAccessMode.READ_WRITE) {
      throw new RuntimeException(pathModuleWorkspace.toString() + " must be accessed for writing.");
    }

    userInteractionCallbackPlugin = ExecContextHolder.get().getExecContextPlugin(UserInteractionCallbackPlugin.class);

    try {
      //*********************************************************************************
      // Step 1: Obtain the list of commits to merge.
      //*********************************************************************************

      listCommit = this.getListCommitDiverge(versionSrc, versionDest, null, null);

      if (listCommit.isEmpty()) {
        userInteractionCallbackPlugin.provideInfo(MessageFormat.format(GitScmPluginImpl.resourceBundle.getString(GitScmPluginImpl.MSG_PATTERN_KEY_NO_DIVERGING_COMMITS), pathModuleWorkspace, versionSrc, versionDest));
        return MergeResult.NOTHING_TO_MERGE;
      }

      //*********************************************************************************
      // Step 2: Generate the patches.
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
      commitIdRangeStart = git.convertToRef(pathModuleWorkspace, versionDest);

      // getListCommitDiverge return the most recent commits first. But we need to apply
      // the patches from the oldest commits.
      Collections.reverse(listCommit);

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

          stringBuilderPatch = new StringBuilder();

          // We pass false for the indTrimOutput since the generated patch file must be
          // intact. In particular, removing the trailing newline character makes it
          // unusable for git apply.
          git.executeGitCommand(new String[] {"diff", "--binary", commitIdRangeStart + ".." + commitIdRangeEnd}, false, Git.AllowExitCode.NONE, pathModuleWorkspace, stringBuilderPatch, false);

          patchCount++;

          try {
            outputStreamWriterPatch = new OutputStreamWriter(new FileOutputStream(pathModuleWorkspace.resolve("dragom-patch-" + String.format("%02d", patchCount) + ".patch").toFile()));
            outputStreamWriterPatch.append(stringBuilderPatch);
            outputStreamWriterPatch.close();
          } catch (IOException ioe) {
            throw new RuntimeException(ioe);
          }
        }

        commitIdRangeStart = lastCommitIdExclude;
      } while (commitIdRangeStart != null);

      if (patchCount == 0) {
        return MergeResult.NOTHING_TO_MERGE;
      }

      //*********************************************************************************
      // Step 3: "prepare" a merge commit, but without actually performing any merge
      // since we will perform the merge steps explicitly after. We need to prepare the
      // merge commit before performing the merge steps since issuing this command with
      // the index modified (the merge steps modify the index) fails.
      //*********************************************************************************

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

      if (!listCommitExclude.isEmpty()) {
        // Remove the useless trailing newline.
        stringBuilderMergeMessage.setLength(stringBuilderMergeMessage.length() - 1);
      }

      git.executeGitCommand(new String[] {"merge", "--no-commit", "--strategy", "ours", "-m", stringBuilderMergeMessage.toString(), git.convertToRef(pathModuleWorkspace, versionSrc)}, false, Git.AllowExitCode.NONE, pathModuleWorkspace, null, false);

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
        String patchFileNameCurrent;

        patchFileName = "dragom-patch-" + String.format("%02d", patchIndex) + ".patch";
        patchFileNameCurrent = patchFileName + ".current";

        try {
          FileUtils.moveFile(pathModuleWorkspace.resolve(patchFileName).toFile(), pathModuleWorkspace.resolve(patchFileNameCurrent).toFile());
        } catch (IOException ioe) {
          throw new RuntimeException(ioe);
        }

        if (git.executeGitCommand(new String[] {"apply", "--3way", "--whitespace=nowarn", patchFileNameCurrent}, false, Git.AllowExitCode.ONE, pathModuleWorkspace, null, false) == 1) {
          // It is not clear if it is OK for this plugin to use
          // UserInteractionCallbackPlugin as it would seem this plugin should operate at a
          // low level. But for now this seems to be the only way to properly inform the
          // user about what to do with the merge conflicts.
          userInteractionCallbackPlugin.provideInfo(MessageFormat.format(GitScmPluginImpl.resourceBundle.getString(GitScmPluginImpl.MSG_PATTERN_KEY_WARNING_MERGE_CONFLICTS), pathModuleWorkspace, versionSrc, versionDest));

          return MergeResult.CONFLICTS;
        }

        try {
          FileUtils.moveFile(pathModuleWorkspace.resolve(patchFileNameCurrent).toFile(), pathModuleWorkspace.resolve(patchFileName + ".done").toFile());
        } catch (IOException ioe) {
          throw new RuntimeException(ioe);
        }
      }

      //*********************************************************************************
      // Step 5: Suppress the patch files.
      //*********************************************************************************

      for (int patchIndex = 1; patchIndex <= patchCount; patchIndex++) {
        pathModuleWorkspace.resolve("dragom-patch-" + String.format("%02d", patchIndex) + ".patch.done").toFile().delete();
      }

      //*********************************************************************************
      // Step 6: Perform the commit.
      //*********************************************************************************

      git.executeGitCommand(new String[] {"commit", "--no-edit"}, false, Git.AllowExitCode.NONE, pathModuleWorkspace, null, false);

      this.push(pathModuleWorkspace, "refs/heads/" + versionDest.getVersion());

      return MergeResult.MERGED;
    } catch (RuntimeException re) {
      throw new RuntimeException(
          "An unexpected exception occurred during the merge of version " + versionSrc + " into version " + versionDest + " within " + pathModuleWorkspace + ".\n"
        + "The merge has not been aborted and dragom-patch-##.patch files may still be present in the root of the workspace directory for the module.\n"
        + "IT IS VERY IMPORTANT that the merge operation not be completed with \"git commit\" as is.\n"
        + "If it is, the merge commit will tell Git that the merge is complete, whereas unmerged changes probably exist.\n"
        + "It MAY be possible to complete the merge process by manually applying the remaining patch files with \"git apply --3way <patch file>\" and \"git commit\".\n"
        + "But after investigating the problem it is preferable to abort the merge operation with \"git merge --abort\", reset the workspace directory with \"git reset --hard HEAD\" and perform the merge again.",
        re);
    }
  }

  @Override
  public MergeResult replace(Path pathModuleWorkspace, Version versionSrc, String message) {
    Git git;
    Version versionDest;
    WorkspacePlugin workspacePlugin;
    UserInteractionCallbackPlugin userInteractionCallbackPlugin;
    String mergeMessage;

    git = this.getGit();

    this.validateTempDynamicVersion(pathModuleWorkspace, false);

    versionDest = this.getVersion(pathModuleWorkspace);

    if (versionDest.getVersionType() == VersionType.STATIC) {
      throw new RuntimeException("Current version " + versionDest + " in working directory " + pathModuleWorkspace + " must be dynamic for merging into.");
    }

    // gitFetch will have been called by isSync.
    if (!this.isSync(pathModuleWorkspace, IsSyncFlag.ALL_CHANGES, false)) {
      throw new RuntimeException("Working directory " + pathModuleWorkspace + " must be synchronized before merging.");
    }

    workspacePlugin = ExecContextHolder.get().getExecContextPlugin(WorkspacePlugin.class);

    if (workspacePlugin.getWorkspaceDirAccessMode(pathModuleWorkspace) != WorkspacePlugin.WorkspaceDirAccessMode.READ_WRITE) {
      throw new RuntimeException(pathModuleWorkspace.toString() + " must be accessed for writing.");
    }

    userInteractionCallbackPlugin = ExecContextHolder.get().getExecContextPlugin(UserInteractionCallbackPlugin.class);

    if (git.executeGitCommand(new String[] {"diff", "--quiet", git.convertToRef(pathModuleWorkspace, versionSrc)}, false, Git.AllowExitCode.ONE, pathModuleWorkspace, null, false) == 0) {
      userInteractionCallbackPlugin.provideInfo(MessageFormat.format(GitScmPluginImpl.resourceBundle.getString(GitScmPluginImpl.MSG_PATTERN_KEY_VERSIONS_EQUAL), pathModuleWorkspace, versionSrc, versionDest));
      return MergeResult.NOTHING_TO_MERGE;
    }

    mergeMessage = "Replaced version " + versionDest + " with version " + versionSrc + '.';

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

    // Doing a replace in Git is not straight forward. Essentially, we would need a
    // merge strategy "theirs" symetrical with "ours", but Git does not provide this.
    // Inspired by simulation #5 in
    // http://stackoverflow.com/questions/4911794/git-command-for-making-one-branch-like-another/4912267#4912267
    // we perform the following sequences of commands:

    // First prepare the merge, without performing the commit and doing as if we
    // wanted to keep the destination Version.
    git.executeGitCommand(new String[] {"merge", "--strategy", "ours", "--no-commit", "-m", mergeMessage, git.convertToRef(pathModuleWorkspace, versionSrc)}, false, Git.AllowExitCode.NONE, pathModuleWorkspace, null, false);

    // Remove all files from working copy and from the index.
    git.executeGitCommand(new String[] {"rm", "-r", "."}, false, Git.AllowExitCode.NONE, pathModuleWorkspace, null, false);

    // Get all files from source Version. This also updates the index.
    git.executeGitCommand(new String[] {"checkout", git.convertToRef(pathModuleWorkspace, versionSrc), "."}, false, Git.AllowExitCode.NONE, pathModuleWorkspace, null, false);

    // Resume the merge by performing the final commit, but with a modified index
    // which represents the source Version state, effectivement replacing the
    // destination Version.
    git.executeGitCommand(new String[] {"commit", "--no-edit"}, false, Git.AllowExitCode.NONE, pathModuleWorkspace, null, false);

    this.push(pathModuleWorkspace, "refs/heads/" + versionDest.getVersion());

    return MergeResult.MERGED;
  }

  @Override
  public String getScmType() {
    return "git";
  }

  @Override
  public String getScmUrl(Path pathModuleWorkspace) {
    return this.gitReposCompleteUrl;
  }

  /**
   * @return Git interface.
   */
  private Git getGit() {
    ExecContext execContext;
    Git git;
    RuntimePropertiesPlugin runtimePropertiesPlugin;
    String runtimeProperty;
    HttpCredentialHandling httpCredentialHandling;

    execContext = ExecContextHolder.get();

    git = (Git)execContext.getTransientData(GitScmPluginImpl.TRANSIENT_DATA_PREFIX_GIT + this.getModule().getNodePath().toString());

    if (git != null) {
      return git;
    }

    git = ServiceLocator.getService(Git.class);

    git.setReposUrl(this.gitReposCompleteUrl);

    runtimePropertiesPlugin = execContext.getExecContextPlugin(RuntimePropertiesPlugin.class);

    runtimeProperty = runtimePropertiesPlugin.getProperty(this.getModule(), GitScmPluginImpl.RUNTIME_PROPERTY_GIT_PATH_EXECUTABLE);

    if (runtimeProperty != null) {
      git.setPathExecutable(Paths.get(runtimeProperty));
    }

    // We exceptionnally declare a final variable in the middle of the code here so
    // that it can be used by the anonymous CredentialStorePlugin.CredentialValidator
    // class below. This is required by Java.
    final String gitPathExecutable = runtimeProperty;

    runtimeProperty = runtimePropertiesPlugin.getProperty(this.getModule(), GitScmPluginImpl.RUNTIME_PROPERTY_GIT_HTTP_CREDENTIAL_HANDLING);

    if (runtimeProperty == null) {
      httpCredentialHandling = HttpCredentialHandling.ONLY_IF_HTTP;
    } else {
      httpCredentialHandling = HttpCredentialHandling.valueOf(runtimeProperty);
    }

    if (httpCredentialHandling != HttpCredentialHandling.NONE) {
      boolean isHttpProtocol;

      isHttpProtocol = GitScmPluginImpl.patternTestHttpProtocol.matcher(this.gitReposCompleteUrl).matches();

      if (httpCredentialHandling == HttpCredentialHandling.ALWAYS_HTTP && !isHttpProtocol) {
        throw new RuntimeException("Git repository URL " + this.gitReposCompleteUrl + " does not use the HTTP[S] protocol but the runtime property GIT_CREDENTIAL_HANDLING is ALWAYS_HTTP.");
      }

      if (isHttpProtocol) {
        UserInteractionCallbackPlugin userInteractionCallbackPlugin;
        CredentialStorePlugin credentialStorePlugin;
        CredentialStorePlugin.Credentials credentials;

        runtimeProperty = runtimePropertiesPlugin.getProperty(this.getModule(), GitScmPluginImpl.RUNTIME_PROPERTY_GIT_HTTP_USER);

        userInteractionCallbackPlugin = execContext.getExecContextPlugin(UserInteractionCallbackPlugin.class);

        credentialStorePlugin = execContext.getExecContextPlugin(CredentialStorePlugin.class);

        credentials = credentialStorePlugin.getCredentials(
            this.gitReposCompleteUrl,
            runtimeProperty,
            new CredentialStorePlugin.CredentialValidator() {
              @Override
              public boolean validateCredentials(String resource, String user, String password) {
                Git git;

                git = ServiceLocator.getService(Git.class);

                if (gitPathExecutable != null) {
                  git.setPathExecutable(Paths.get(gitPathExecutable));
                }

                git.setReposUrl(GitScmPluginImpl.this.gitReposCompleteUrl);
                git.setUser(user);
                git.setPassword(password);

                userInteractionCallbackPlugin.provideInfo(MessageFormat.format(GitScmPluginImpl.resourceBundle.getString(GitScmPluginImpl.MSG_PATTERN_KEY_ACCESS_REMOTE_REPOS), GitScmPluginImpl.this.gitReposCompleteUrl, "ls-remote (validateCredentials), " + user));

                return git.validateCredentials();
              }
            });

        git.setUser(credentials.user);
        git.setPassword(credentials.password);
      }
    }

    execContext.setTransientData(GitScmPluginImpl.TRANSIENT_DATA_PREFIX_GIT + this.getModule().getNodePath().toString(), git);

    return git;
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

        if (!setWorkspaceDir.isEmpty()) {
          workspaceDirUserModuleVersion = (WorkspaceDirUserModuleVersion)(setWorkspaceDir.iterator().next());
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
}
