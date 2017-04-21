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

package org.azyva.dragom.job;

import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ResourceBundle;
import java.util.Set;

import org.azyva.dragom.execcontext.ExecContext;
import org.azyva.dragom.execcontext.plugin.RuntimePropertiesPlugin;
import org.azyva.dragom.execcontext.plugin.UserInteractionCallbackPlugin;
import org.azyva.dragom.execcontext.plugin.WorkspaceDirUserModuleVersion;
import org.azyva.dragom.execcontext.plugin.WorkspacePlugin;
import org.azyva.dragom.execcontext.plugin.WorkspacePlugin.GetWorkspaceDirMode;
import org.azyva.dragom.execcontext.plugin.WorkspacePlugin.WorkspaceDirAccessMode;
import org.azyva.dragom.execcontext.support.ExecContextHolder;
import org.azyva.dragom.model.ArtifactVersion;
import org.azyva.dragom.model.Model;
import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.ModuleVersion;
import org.azyva.dragom.model.Version;
import org.azyva.dragom.model.plugin.ScmPlugin;
import org.azyva.dragom.reference.Reference;
import org.azyva.dragom.reference.ReferencePathMatcher;
import org.azyva.dragom.util.AlwaysNeverAskUserResponse;
import org.azyva.dragom.util.RuntimeExceptionAbort;
import org.azyva.dragom.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * See the help information displayed by the MergeMainTool.help from
 * dragom-cli-tools.
 * <p>
 * The principle of operation of this class is to traverse reference graphs in the
 * standard way using a List of root {@link ModuleVersion}'s and a
 * {@link ReferencePathMatcher} to identify static source ModuleVersion's to merge
 * into some destination {@link Version}'s specified by the user. Only
 * {@link Module}'s known to Dragom are considered.
 * <p>
 * Generally the ReferencePathMatcher will be such that all ModuleVersion's in the
 * reference graph under a root ModuleVersion are to be merged but the generic use
 * of a ReferencePathMatcher allows to be selective about exactly which
 * ModuleVersion's and reference graph subsets are to be merged.
 * <p>
 * There are actually two types of merge operations supported by Dragom. The
 * most common one is implemented by {@link MergeReferenceGraph}. This class
 * implements the other one.
 * <p>
 * The type of merge performed by this class is used, for example, after a release
 * has been performed when it is required to merge changes made to ModuleVersion's
 * into their corresponding main dynamic Version's.
 * <p>
 * For each static source ModuleVersion that is matched in the main traversal, a
 * merge process is initiated into a dynamic destination ModuleVersion that is
 * specified externally (by the user) and that is not necessarily related to some
 * reference graph. Specifically a destination ModuleVersion is not necessarily
 * referenced by some previous parent destination ModuleVersion that may have been
 * merged into previously during the traversal.
 * <p>
 * The source ModuleVersion must be static. Non-static ModuleVersion's are
 * ignored, even if matched by the ReferencePathMatcher.
 * <p>
 * The destination ModuleVersion must be in a user workspace directory so that if
 * merge conflicts are encountered, the user has a workspace where they can be
 * resolved. If a destination ModuleVersion is not in a user workspace directory,
 * it is checked out for the user and deleted after the merge if no conflicts are
 * encountered.
 * <p>
 * The fact that the destination ModuleVersion must be in a user workspace
 * directory makes this class behave somewhat differently from others in that
 * generally the ModuleVersion's that are in user workspace directories are those
 * that are matched by the root ModuleVersion's and the ReferencePathMatcher,
 * whereas here, those matched ModuleVersion are the source of the merge process
 * and externally specified destination ModuleVersions are in user workspace
 * directories. For that reason, it is generally expected that when this class is
 * invoked, the workspace is empty, source ModuleVersion's are checked out in
 * system workspace directories and destination ModuleVersion's are checked out
 * during the process in user workspace directories. When checking out a
 * destination ModuleVersion in a user workspace directory, no special handling is
 * performed and if another Version of the same Module is already checked out in
 * a user workspace directory and the workspace does not support having multiple
 * Version's of the same Module in user workspace directories, a
 * RuntimeExceptionUserError will likely be raised.
 * <p>
 * The actual merges on the ModuleVersion's are performed using
 * {@link ScmPlugin#merge}, ignoring commits that simply change the
 * ArtifactVersion of the source Module. These commits are recognized with the
 * commit attribute "dragom-version-change". Commits that change the Version of
 * its references are not excluded, as they are with MergeReferenceGraph. The idea
 * is that we are not merging a reference graph into another reference graph. We
 * are merging ModuleVersion's in a source reference graph into individual
 * destination Version's, and changes in reference Version's, which are
 * necessarily static since the source ModuleVersion is static, are part of the
 * changes to be merged. This is also why contrary to MergeReferenceGraph, the
 * children of a merged ModuleVersion are not given any special treatment, other
 * than being naturally matched by the ReferencePathMatcher during traversal.
 *
 * @author David Raymond
 */
public class MergeMain extends RootModuleVersionJobAbstractImpl {
  /**
   * Logger for the class.
   */
  private static final Logger logger = LoggerFactory.getLogger(MergeMain.class);

  /**
   * Runtime property that specifies the specific destination {@link Version} to use.
   *
   * <p>This is similar to RUNTIME_PROPERTY_REUSE_DEST_VERSION, but the latter is
   * generally used for user interaction in conjunction with
   * RUNTIME_PROPERTY_CAN_REUSE_DEST_VERSION.
   */
  private static final String RUNTIME_PROPERTY_SPECIFIC_DEST_VERSION = "SPECIFIC_DEST_VERSION";

  /**
   * Runtime property of type {@link AlwaysNeverAskUserResponse} that indicates if a
   * previously established destination {@link Version} can be reused.
   */
  private static final String RUNTIME_PROPERTY_CAN_REUSE_DEST_VERSION = "CAN_REUSE_DEST_VERSION";

  /**
   * Runtime property that specifies the destination {@link Version} to reuse.
   */
  private static final String RUNTIME_PROPERTY_REUSE_DEST_VERSION = "REUSE_DEST_VERSION";

  /**
   * Merge mode.
   *
   * <p>The possible values are given by {@link MergeMainMode}.
   */
  private static final String RUNTIME_PROPERTY_MERGE_MODE = "MERGE_MAIN_MODE";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_DEST_VERSION_SPECIFIED = "DEST_VERSION_SPECIFIED";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_DEST_VERSION_AUTOMATICALLY_REUSED = "DEST_VERSION_AUTOMATICALLY_REUSED";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_INPUT_DEST_VERSION = "INPUT_DEST_VERSION";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_AUTOMATICALLY_REUSE_DEST_VERSION = "AUTOMATICALLY_REUSE_DEST_VERSION";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_SRC_MODULE_VERISON_SKIPPED_SINCE_DEST_MODULE_VERSION_MERGE_CONFLICTS = "SRC_MODULE_VERISON_SKIPPED_SINCE_DEST_MODULE_VERSION_MERGE_CONFLICTS";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_CHECKING_OUT_MODULE_VERSION = "CHECKING_OUT_MODULE_VERSION";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_SHALLOW_MERGING_SRC_MODULE_VERSION_INTO_DEST = "SHALLOW_MERGING_SRC_MODULE_VERSION_INTO_DEST";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_DIVERGING_COMMITS_IN_DEST = "DIVERGING_COMMITS_IN_DEST";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_EXCLUDED_VERSION_CHANGING_COMMITS = "EXCLUDED_VERSION_CHANGING_COMMITS";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_SRC_MERGED_INTO_DEST = "SRC_MERGED_INTO_DEST";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_SRC_MERGED_INTO_DEST_CONFLICTS = "SRC_MERGED_INTO_DEST_CONFLICTS";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_SRC_MERGED_INTO_DEST_COMMITS_OVERWRITTEN = "SRC_MERGED_INTO_DEST_COMMITS_OVERWRITTEN";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_NOTHING_TO_MERGE_SRC_INTO_DEST = "NOTHING_TO_MERGE_SRC_INTO_DEST";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_DELETING_DEST_USER_WORKSPACE_DIR_NO_CONFLICTS = "DELETING_DEST_USER_WORKSPACE_DIR_NO_CONFLICTS";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_LIST_MODULE_VERSION_MERGE = "LIST_MODULE_VERSION_MERGE";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_LIST_MODULE_VERSION_NOTHING_TO_MERGE = "LIST_MODULE_VERSION_NOTHING_TO_MERGE";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_LIST_MODULE_VERSION_MERGE_CONFLICTS = "LIST_MODULE_VERSION_MERGE_CONFLICTS";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_LIST_MODULE_VERSION_SKIPPED = "LIST_MODULE_VERSION_SKIPPED";

  /**
   * ResourceBundle specific to this class.
   */
  private static final ResourceBundle resourceBundle = ResourceBundle.getBundle(MergeMain.class.getName() + "ResourceBundle");

  /**
   * Merge modes for this job.
   */
  public enum MergeMainMode {
    /**
     * Normal merge.
     *
     * <p>This is the default.
     */
    MERGE,

    /**
     * Normal merge, excluding version-changing commits, that is those which have the
     * dragom-version-change attribute set.
     *
     * <p>This is so that each {@link Version} maintains its own
     * {@link ArtifactVersion}. The exclusion does not apply to
     * reference-version-changing commits.
     */
    MERGE_EXCLUDE_VERSION_CHANGING_COMMITS,

    /**
     * Similar to {@link #MERGE_EXCLUDE_VERSION_CHANGING_COMMITS}, but validate there
     * are no diverging commits in the destination Version.
     */
    MERGE_EXCLUDE_VERSION_CHANGING_COMMITS_NO_DIVERGING_COMMITS,

    /**
     * Take the source {@link Version} (overwriting destination), but validate there
     * are no diverging commits in the destination Version.
     */
    SRC_VALIDATE_NO_DIVERGING_COMMITS,

    /**
     * Unconditionally take the source {@link Version}, overwriting destination and
     * potentially loosing changes in destination.
     */
    SRC_UNCONDITIONAL
  }


  /**
   * Set of destination {@link ModuleVersion}'s for which merge conflicts were
   * encountered and which therefore cannot be used for another merge attempt (for
   * another source {@link Version}).
   */
  private Set<ModuleVersion> setModuleVersionDestMergeConflicts;

  /**
   * List of {@link ModuleVersion} literals which have been merged.
   */
  private List<String> listStringModuleVersionMerge;

  /**
   * List of {@link ModuleVersion} literals which were matched but for which there
   * was nothing to merge.
   */
  private List<String> listStringModuleVersionNothingToMerge;

  /**
   * List of {@link ModuleVersion} literal for which merge conflicts were
   * encountered.
   */
  private List<String> listStringModuleVersionMergeConflicts;

  /**
   * List of {@link ModuleVersion} literal skipped because destination ModuleVersion
   * has merge conflicts.
   */
  private List<String> listStringModuleVersionSkipped;

  /**
   * Constructor.
   *
   * @param listModuleVersionRoot List of root ModuleVersion's within which new
   *   static Version's must be created.
   */
  public MergeMain(List<ModuleVersion> listModuleVersionRoot) {
    super(listModuleVersionRoot);

    this.setupReferencePathMatcherForProjectCode();
    this.setIndHandleDynamicVersion(false);
    //TODO: It may be more logical to do a depth-first traversal, but it does not work well. It seems the same ModuleVersion is checked out multiple times uselessly.
    //this.setIndDepthFirst(true);

    this.setModuleVersionDestMergeConflicts = new HashSet<ModuleVersion>();
    this.listStringModuleVersionMerge = new ArrayList<String>();
    this.listStringModuleVersionNothingToMerge = new ArrayList<String>();
    this.listStringModuleVersionMergeConflicts = new ArrayList<String>();
    this.listStringModuleVersionSkipped = new ArrayList<String>();
  }

  /**
   * Visits a matched {@link ModuleVersion} in the context of traversing the
   * ReferencePath for performing a merge. The {@link Version} of the module must be
   * static.
   *
   * @param reference Reference to the matched ModuleVersion for which a merge
   *   has to be performed.
   * @return Indicates if children must be visited. true is always returned as each
   *   matched ModuleVersion is merged independently.
   */
  @Override
  protected boolean visitMatchedModuleVersion(Reference reference) {
    ExecContext execContext;
    RuntimePropertiesPlugin runtimePropertiesPlugin;
    UserInteractionCallbackPlugin userInteractionCallbackPlugin;
    WorkspacePlugin workspacePlugin;
    Model model;
    ModuleVersion moduleVersionSrc;
    Module module;
    ScmPlugin scmPlugin;
    String runtimeProperty;
    Version versionDest;
    ModuleVersion moduleVersionDest;
    MergeMainMode mergeMainMode;
    WorkspaceDirUserModuleVersion workspaceDirUserModuleVersion;
    Path pathModuleWorkspace;
    boolean indWorkspaceDirUserModuleVersionCreated;
    ScmPlugin.MergeResult mergeResult = null;
    List<ScmPlugin.Commit> listCommit = null;
    Iterator<ScmPlugin.Commit> iteratorCommit;
    StringBuilder stringBuilderListCommits = null;
    Util.ToolExitStatusAndContinue toolExitStatusAndContinue;
    String message;

    this.referencePath.add(reference);

    execContext = ExecContextHolder.get();
    runtimePropertiesPlugin = execContext.getExecContextPlugin(RuntimePropertiesPlugin.class);
    userInteractionCallbackPlugin = execContext.getExecContextPlugin(UserInteractionCallbackPlugin.class);
    workspacePlugin = execContext.getExecContextPlugin(WorkspacePlugin.class);
    model = execContext.getModel();
    moduleVersionSrc = reference.getModuleVersion();
    module = model.getModule(moduleVersionSrc.getNodePath());
    scmPlugin = module.getNodePlugin(ScmPlugin.class, null);

    pathModuleWorkspace = null;

    try {
      //********************************************************************************
      // Determine the destination Version to merge the source matched ModuleVersion
      // into.
      //********************************************************************************

      runtimeProperty = runtimePropertiesPlugin.getProperty(module, MergeMain.RUNTIME_PROPERTY_SPECIFIC_DEST_VERSION);

      if (runtimeProperty != null) {
        versionDest = new Version(runtimeProperty);
        userInteractionCallbackPlugin.provideInfo(MessageFormat.format(MergeMain.resourceBundle.getString(MergeMain.MSG_PATTERN_KEY_DEST_VERSION_SPECIFIED), moduleVersionSrc, versionDest));
      } else {
        AlwaysNeverAskUserResponse alwaysNeverAskUserResponseCanReuseDestVersion;
        Version versionReuseDest;

        alwaysNeverAskUserResponseCanReuseDestVersion = AlwaysNeverAskUserResponse.valueOfWithAskDefault(runtimePropertiesPlugin.getProperty(module, MergeMain.RUNTIME_PROPERTY_CAN_REUSE_DEST_VERSION));

        runtimeProperty = runtimePropertiesPlugin.getProperty(module, MergeMain.RUNTIME_PROPERTY_REUSE_DEST_VERSION);

        if (runtimeProperty != null) {
          versionReuseDest = new Version(runtimeProperty);
        } else {
          versionReuseDest = null;

          if (alwaysNeverAskUserResponseCanReuseDestVersion.isAlways()) {
            // Normally if the runtime property CAN_REUSE_DEST_VERSION is ALWAYS the
            // REUSE_DEST_VERSION runtime property should also be set. But since these
            // properties are independent and stored externally, it can happen that they
            // are not synchronized. We make an adjustment here to avoid problems.
            alwaysNeverAskUserResponseCanReuseDestVersion = AlwaysNeverAskUserResponse.YES_ASK;
          }
        }

        if (alwaysNeverAskUserResponseCanReuseDestVersion.isAlways()) {
          userInteractionCallbackPlugin.provideInfo(MessageFormat.format(MergeMain.resourceBundle.getString(MergeMain.MSG_PATTERN_KEY_DEST_VERSION_AUTOMATICALLY_REUSED), moduleVersionSrc, versionReuseDest));
          versionDest = versionReuseDest;
        } else {
          versionDest =
              Util.getInfoVersion(
                  null,
                  scmPlugin,
                  userInteractionCallbackPlugin,
                  MessageFormat.format(MergeMain.resourceBundle.getString(MergeMain.MSG_PATTERN_KEY_INPUT_DEST_VERSION), moduleVersionSrc),
                  versionReuseDest);

          runtimePropertiesPlugin.setProperty(null, MergeMain.RUNTIME_PROPERTY_REUSE_DEST_VERSION, versionDest.toString());

          // The result is not useful. We only want to adjust the runtime property which
          // will be reused the next time around.
          Util.getInfoAlwaysNeverAskUserResponseAndHandleAsk(
              runtimePropertiesPlugin,
              MergeMain.RUNTIME_PROPERTY_CAN_REUSE_DEST_VERSION,
              userInteractionCallbackPlugin,
              MessageFormat.format(MergeMain.resourceBundle.getString(MergeMain.MSG_PATTERN_KEY_AUTOMATICALLY_REUSE_DEST_VERSION), versionDest));
        }
      }

      moduleVersionDest = new ModuleVersion(module.getNodePath(), versionDest);

      if (this.setModuleVersionDestMergeConflicts.contains(moduleVersionDest)) {
        userInteractionCallbackPlugin.provideInfo(MessageFormat.format(MergeMain.resourceBundle.getString(MergeMain.MSG_PATTERN_KEY_SRC_MODULE_VERISON_SKIPPED_SINCE_DEST_MODULE_VERSION_MERGE_CONFLICTS), moduleVersionSrc, moduleVersionDest));
        this.listStringModuleVersionSkipped.add(moduleVersionSrc.toString());

        return true;
      }

      //********************************************************************************
      // Determine merge mode.
      //********************************************************************************

      runtimeProperty = runtimePropertiesPlugin.getProperty(module, MergeMain.RUNTIME_PROPERTY_MERGE_MODE);

      if (runtimeProperty == null) {
        mergeMainMode = MergeMainMode.MERGE;
      } else {
        mergeMainMode = MergeMainMode.valueOf(runtimeProperty);
      }

      //********************************************************************************
      // Ensure destination ModuleVersion is in a user workspace directory.
      //********************************************************************************

      workspaceDirUserModuleVersion = new WorkspaceDirUserModuleVersion(moduleVersionDest);

      if (!workspacePlugin.isWorkspaceDirExist(workspaceDirUserModuleVersion)) {
        pathModuleWorkspace = workspacePlugin.getWorkspaceDir(workspaceDirUserModuleVersion, GetWorkspaceDirMode.ENUM_SET_CREATE_NEW_NO_PATH, WorkspaceDirAccessMode.READ_WRITE);

        try {
          userInteractionCallbackPlugin.provideInfo(MessageFormat.format(MergeMain.resourceBundle.getString(MergeMain.MSG_PATTERN_KEY_CHECKING_OUT_MODULE_VERSION), moduleVersionDest, pathModuleWorkspace, scmPlugin.getScmUrl(pathModuleWorkspace)));

          scmPlugin.checkout(moduleVersionDest.getVersion(), pathModuleWorkspace);
        } catch (RuntimeException re) {
          workspacePlugin.deleteWorkspaceDir(workspaceDirUserModuleVersion);
          pathModuleWorkspace = null; // To prevent the call to workspacePlugin.releaseWorkspaceDir below.
          throw re;
        }

        indWorkspaceDirUserModuleVersionCreated = true;
      } else {
        pathModuleWorkspace = workspacePlugin.getWorkspaceDir(workspaceDirUserModuleVersion, GetWorkspaceDirMode.ENUM_SET_GET_EXISTING, WorkspaceDirAccessMode.READ_WRITE);
        indWorkspaceDirUserModuleVersionCreated = false;
      }

      //********************************************************************************
      // We have the source and destination ModuleVersion. We are ready to perform the
      // actual merge.
      //********************************************************************************

      userInteractionCallbackPlugin.provideInfo(MessageFormat.format(MergeMain.resourceBundle.getString(MergeMain.MSG_PATTERN_KEY_SHALLOW_MERGING_SRC_MODULE_VERSION_INTO_DEST), moduleVersionSrc, moduleVersionDest.getVersion(), pathModuleWorkspace, scmPlugin.getScmUrl(pathModuleWorkspace), mergeMainMode));

      if (!Util.handleDoYouWantToContinue(Util.DO_YOU_WANT_TO_CONTINUE_CONTEXT_MERGE)) {
        if (indWorkspaceDirUserModuleVersionCreated) {
          workspacePlugin.deleteWorkspaceDir(workspaceDirUserModuleVersion);
          pathModuleWorkspace = null; // To prevent the call to workspacePlugin.releaseWorkspaceDir below.
        }

        // The return value not important since the caller is expected to check for
        // Util.isAbort.
        return true;
      }

      // We need the list of diverging commits on these three cases.
      if (   (mergeMainMode == MergeMainMode.MERGE_EXCLUDE_VERSION_CHANGING_COMMITS_NO_DIVERGING_COMMITS)
          || (mergeMainMode == MergeMainMode.SRC_VALIDATE_NO_DIVERGING_COMMITS)
          || (mergeMainMode == MergeMainMode.SRC_UNCONDITIONAL)) {

        listCommit = scmPlugin.getListCommitDiverge(
            moduleVersionDest.getVersion(),
            moduleVersionSrc.getVersion(),
            null,
            EnumSet.of(ScmPlugin.GetListCommitFlag.IND_INCLUDE_MAP_ATTR, ScmPlugin.GetListCommitFlag.IND_INCLUDE_MESSAGE));

        if (!listCommit.isEmpty()) {
          stringBuilderListCommits = new StringBuilder();

          for (ScmPlugin.Commit commit: listCommit) {
            stringBuilderListCommits.append(commit.id).append(": ").append(commit.message).append('\n');
          }

          // Removing trailing newline.
          stringBuilderListCommits.setLength(stringBuilderListCommits.length() - 1);
        }
      }

      // After the block above we reuse the local variable listCommit for other purposes
      // and the local variable stringBuilderListCommits to know if there are diverging
      // commits (null or not).

      // Note that this call to getListCommitDiverge is similar to the one above for the
      // *_NO_DIVERGING_COMMITS modes, but the source and destination versions are
      // swapped.
      // This list is used below for the  *_EXCLUDE_VERSION_CHANGING_COMMITS* modes. But
      // we need it here check if there are diverging commits in the source Version
      // itself. If not, there is nothing to merge.
      // In the case of the SRC_UNCONDITIONAL mode, it may be argued that a replacement
      // is mandated in all cases. But it would be wrong to replace a destination that
      // has diverged if the source has not diverged at all.
      // It is important to make this verification before the verification implied by
      // the *_NO_DIVERGING_COMMITS modes since otherwise, that second check could fail
      // uselessly.
      listCommit = scmPlugin.getListCommitDiverge(moduleVersionSrc.getVersion(), moduleVersionDest.getVersion(), null, EnumSet.of(ScmPlugin.GetListCommitFlag.IND_INCLUDE_MAP_ATTR));

      if (listCommit.isEmpty()) {
        mergeResult = ScmPlugin.MergeResult.NOTHING_TO_MERGE;
      } else {
        // For these two cases, we need to validate that the list of diverging commits
        // (calculated above) is empty. Otherwise, abort the merge.
        // For the SRC_UNCONDITIONAL case, we need the list of diverging commits only to
        // include in the message provided to the user.
        if (   (mergeMainMode == MergeMainMode.MERGE_EXCLUDE_VERSION_CHANGING_COMMITS_NO_DIVERGING_COMMITS)
            || (mergeMainMode == MergeMainMode.SRC_VALIDATE_NO_DIVERGING_COMMITS)) {

          if (stringBuilderListCommits != null) {
            this.listStringModuleVersionMergeConflicts.add(moduleVersionSrc.toString() + " (" + scmPlugin.getScmUrl(pathModuleWorkspace) + ") - resolve conflicts in " + pathModuleWorkspace);

            toolExitStatusAndContinue = Util.handleToolExitStatusAndContinueForExceptionalCond(null, Util.EXCEPTIONAL_COND_MERGE_CONFLICTS);

            message = MessageFormat.format(MergeMain.resourceBundle.getString(MergeMain.MSG_PATTERN_KEY_DIVERGING_COMMITS_IN_DEST), toolExitStatusAndContinue.toolExitStatus, moduleVersionSrc.getVersion(), moduleVersionDest, pathModuleWorkspace, scmPlugin.getScmUrl(pathModuleWorkspace), stringBuilderListCommits.toString());

            if (!toolExitStatusAndContinue.indContinue) {
              throw new RuntimeExceptionAbort(message);
            }

            userInteractionCallbackPlugin.provideInfo(message);

            // We do not need to check the return value. The fact that Util.setAbort is
            // called is sufficient.
            Util.handleDoYouWantToContinue(Util.DO_YOU_WANT_TO_CONTINUE_CONTEXT_MERGE_CONFLICTS);

            // The return value not important since the caller is expected to check for
            // Util.isAbort.
            // If we do not return here, we get into the switch below and perform the same
            // processing as the non-NO_DIVERGING_COMMITS mode.
            return true;
          }
        }

        switch (mergeMainMode) {
        case MERGE:

        // This represents a replacement. But if we get here, it means there are no
        // diverging commits. Therefore, we are better off doing a regular merge (which
        // is equivalent to a replacement in that case) since for most SCM it is faster.
        case SRC_VALIDATE_NO_DIVERGING_COMMITS:
          // ScmPlugin.merge ensures that the working directory is synchronized.
          try {
            mergeResult = scmPlugin.merge(pathModuleWorkspace, moduleVersionSrc.getVersion(), null);
          } catch (ScmPlugin.UpdateNeededException une) {
            if (indWorkspaceDirUserModuleVersionCreated) {
              if (scmPlugin.update(pathModuleWorkspace)) {
                throw new RuntimeException("Unexpected merge conflicts occured in " + pathModuleWorkspace + " while updating newly created WorkspaceDirUserModuleVersion.");
              }

              try {
                mergeResult = scmPlugin.merge(pathModuleWorkspace, moduleVersionSrc.getVersion(), null);
              } catch (ScmPlugin.UpdateNeededException une2) {
                throw new RuntimeException(une2);
              }
            } else {
              throw new RuntimeException(une);
            }
          }

          if (mergeResult == ScmPlugin.MergeResult.CONFLICTS) {
            if (mergeMainMode == MergeMainMode.SRC_VALIDATE_NO_DIVERGING_COMMITS) {
              throw new RuntimeException("Conflicts are not expected here.");
            }

            this.setModuleVersionDestMergeConflicts.add(moduleVersionDest);
            this.listStringModuleVersionMergeConflicts.add(moduleVersionSrc.toString() + " (" + scmPlugin.getScmUrl(pathModuleWorkspace) + ") - resolve conflicts in " + pathModuleWorkspace);

            toolExitStatusAndContinue = Util.handleToolExitStatusAndContinueForExceptionalCond(null, Util.EXCEPTIONAL_COND_MERGE_CONFLICTS);

            message = MessageFormat.format(MergeMain.resourceBundle.getString(MergeMain.MSG_PATTERN_KEY_SRC_MERGED_INTO_DEST_CONFLICTS), toolExitStatusAndContinue.toolExitStatus, moduleVersionSrc, moduleVersionDest.getVersion(), pathModuleWorkspace, scmPlugin.getScmUrl(pathModuleWorkspace));

            this.listActionsPerformed.add(message);

            if (!toolExitStatusAndContinue.indContinue) {
              throw new RuntimeExceptionAbort(message);
            }

            userInteractionCallbackPlugin.provideInfo(message);

            // We do not need to check the return value. The fact that Util.setAbort is
            // called is sufficient.
            Util.handleDoYouWantToContinue(Util.DO_YOU_WANT_TO_CONTINUE_CONTEXT_MERGE_CONFLICTS);

            // The return value is not important since the caller is expected to check for
            // Util.isAbort.
            return true;
          }

          if (mergeResult == ScmPlugin.MergeResult.MERGED) {
            this.listActionsPerformed.add(MessageFormat.format(MergeMain.resourceBundle.getString(MergeMain.MSG_PATTERN_KEY_SRC_MERGED_INTO_DEST), moduleVersionSrc, moduleVersionDest.getVersion(), pathModuleWorkspace, scmPlugin.getScmUrl(pathModuleWorkspace), mergeMainMode));
          }

          break;

        case MERGE_EXCLUDE_VERSION_CHANGING_COMMITS_NO_DIVERGING_COMMITS:
        case MERGE_EXCLUDE_VERSION_CHANGING_COMMITS:
          MergeMain.logger.info("Building list of version-changing commits to exclude before merging source ModuleVersion " + moduleVersionSrc + " into destination ModuleVersion " + moduleVersionDest + '.');

          // Note that this call to getListCommitDiverge is similar to the one above for the
          // -NO_DIVERGING_COMMITS modes, but the source and destination versions are
          // swapped.
          listCommit = scmPlugin.getListCommitDiverge(moduleVersionSrc.getVersion(), moduleVersionDest.getVersion(), null, EnumSet.of(ScmPlugin.GetListCommitFlag.IND_INCLUDE_MAP_ATTR));

          iteratorCommit = listCommit.iterator();

          while (iteratorCommit.hasNext()) {
            ScmPlugin.Commit commit;

            commit = iteratorCommit.next();

            if (!commit.mapAttr.containsKey(ScmPlugin.COMMIT_ATTR_VERSION_CHANGE)) {
              iteratorCommit.remove();
            }
          }

          if (!listCommit.isEmpty()) {
            userInteractionCallbackPlugin.provideInfo(MessageFormat.format(MergeMain.resourceBundle.getString(MergeMain.MSG_PATTERN_KEY_EXCLUDED_VERSION_CHANGING_COMMITS), listCommit));
          }

          // ScmPlugin.merge ensures that the working directory is synchronized.
          try {
            mergeResult = scmPlugin.mergeExcludeCommits(pathModuleWorkspace, moduleVersionSrc.getVersion(), listCommit, null);
          } catch (ScmPlugin.UpdateNeededException une) {
            if (indWorkspaceDirUserModuleVersionCreated) {
              if (scmPlugin.update(pathModuleWorkspace)) {
                throw new RuntimeException("Unexpected merge conflicts occured in " + pathModuleWorkspace + " while updating newly created WorkspaceDirUserModuleVersion.");
              }

              try {
                mergeResult = scmPlugin.mergeExcludeCommits(pathModuleWorkspace, moduleVersionSrc.getVersion(), listCommit, null);
              } catch (ScmPlugin.UpdateNeededException une2) {
                throw new RuntimeException(une2);
              }
            } else {
              throw new RuntimeException(une);
            }
          }

          if (mergeResult == ScmPlugin.MergeResult.CONFLICTS) {
            this.setModuleVersionDestMergeConflicts.add(moduleVersionDest);
            this.listStringModuleVersionMergeConflicts.add(moduleVersionSrc.toString() + " (" + scmPlugin.getScmUrl(pathModuleWorkspace) + ") - resolve conflicts in " + pathModuleWorkspace);

            toolExitStatusAndContinue = Util.handleToolExitStatusAndContinueForExceptionalCond(null, Util.EXCEPTIONAL_COND_MERGE_CONFLICTS);

            message = MessageFormat.format(MergeMain.resourceBundle.getString(MergeMain.MSG_PATTERN_KEY_SRC_MERGED_INTO_DEST_CONFLICTS), toolExitStatusAndContinue.toolExitStatus, moduleVersionSrc, moduleVersionDest.getVersion(), pathModuleWorkspace, scmPlugin.getScmUrl(pathModuleWorkspace));

            this.listActionsPerformed.add(message);

            if (!toolExitStatusAndContinue.indContinue) {
              throw new RuntimeExceptionAbort(message);
            }

            userInteractionCallbackPlugin.provideInfo(message);

            // We do not need to check the return value. The fact that Util.setAbort is
            // called is sufficient.
            Util.handleDoYouWantToContinue(Util.DO_YOU_WANT_TO_CONTINUE_CONTEXT_MERGE_CONFLICTS);

            // The return value not important since the caller is expected to check for
            // Util.isAbort.
            return true;
          }

          if (mergeResult == ScmPlugin.MergeResult.MERGED) {
            this.listActionsPerformed.add(MessageFormat.format(MergeMain.resourceBundle.getString(MergeMain.MSG_PATTERN_KEY_SRC_MERGED_INTO_DEST), moduleVersionSrc, moduleVersionDest.getVersion(), pathModuleWorkspace, scmPlugin.getScmUrl(pathModuleWorkspace), mergeMainMode));
          }

          break;

        case SRC_UNCONDITIONAL:
          // This represents a replacement. But if there are no diverging commits, we are
          // better off doing a regular merge (which is equivalent to a replacement in that
          // case) since for most SCM it is faster.
          if (stringBuilderListCommits == null) {
            // ScmPlugin.merge ensures that the working directory is synchronized.
            try {
              mergeResult = scmPlugin.merge(pathModuleWorkspace, moduleVersionSrc.getVersion(), null);
            } catch (ScmPlugin.UpdateNeededException une) {
              if (indWorkspaceDirUserModuleVersionCreated) {
                if (scmPlugin.update(pathModuleWorkspace)) {
                  throw new RuntimeException("Unexpected merge conflicts occured in " + pathModuleWorkspace + " while updating newly created WorkspaceDirUserModuleVersion.");
                }

                try {
                  mergeResult = scmPlugin.merge(pathModuleWorkspace, moduleVersionSrc.getVersion(), null);
                } catch (ScmPlugin.UpdateNeededException une2) {
                  throw new RuntimeException(une2);
                }
              } else {
                throw new RuntimeException(une);
              }
            }

            if (mergeResult == ScmPlugin.MergeResult.CONFLICTS) {
              throw new RuntimeException("Conflicts are not expected here.");
            }

            if (mergeResult == ScmPlugin.MergeResult.MERGED) {
              this.listActionsPerformed.add(MessageFormat.format(MergeMain.resourceBundle.getString(MergeMain.MSG_PATTERN_KEY_SRC_MERGED_INTO_DEST), moduleVersionSrc, moduleVersionDest.getVersion(), pathModuleWorkspace, scmPlugin.getScmUrl(pathModuleWorkspace), mergeMainMode));
            }
          } else {
            // ScmPlugin.merge ensures that the working directory is synchronized.
            try {
              mergeResult = scmPlugin.replace(pathModuleWorkspace, moduleVersionSrc.getVersion(), null);
            } catch (ScmPlugin.UpdateNeededException une) {
              if (indWorkspaceDirUserModuleVersionCreated) {
                if (scmPlugin.update(pathModuleWorkspace)) {
                  throw new RuntimeException("Unexpected merge conflicts occured in " + pathModuleWorkspace + " while updating newly created WorkspaceDirUserModuleVersion.");
                }

                try {
                  mergeResult = scmPlugin.replace(pathModuleWorkspace, moduleVersionSrc.getVersion(), null);
                } catch (ScmPlugin.UpdateNeededException une2) {
                  throw new RuntimeException(une2);
                }
              } else {
                throw new RuntimeException(une);
              }
            }

            if (mergeResult == ScmPlugin.MergeResult.MERGED) {
              this.listActionsPerformed.add(MessageFormat.format(MergeMain.resourceBundle.getString(MergeMain.MSG_PATTERN_KEY_SRC_MERGED_INTO_DEST_COMMITS_OVERWRITTEN), moduleVersionSrc, moduleVersionDest.getVersion(), pathModuleWorkspace, scmPlugin.getScmUrl(pathModuleWorkspace), mergeMainMode, stringBuilderListCommits.toString()));
            }
          }

          break;
        }
      }

      if (mergeResult == ScmPlugin.MergeResult.NOTHING_TO_MERGE) {
        userInteractionCallbackPlugin.provideInfo(MessageFormat.format(MergeMain.resourceBundle.getString(MergeMain.MSG_PATTERN_KEY_NOTHING_TO_MERGE_SRC_INTO_DEST), moduleVersionSrc, moduleVersionDest.getVersion(), pathModuleWorkspace, scmPlugin.getScmUrl(pathModuleWorkspace)));
        this.listStringModuleVersionNothingToMerge.add(moduleVersionSrc.toString() + " (" + scmPlugin.getScmUrl(pathModuleWorkspace) + ')');
      } else if (mergeResult == ScmPlugin.MergeResult.MERGED) {
        this.listStringModuleVersionMerge.add(moduleVersionSrc.toString() + " (" + scmPlugin.getScmUrl(pathModuleWorkspace) + ')');
      }

      // We get here only if no merge conflicts were encountered. In that case and if we
      // had to checkout the destination ModuleVersion into a
      // WorkspaceDirUserModuleVersion, we delete it.
      if (indWorkspaceDirUserModuleVersionCreated) {
        userInteractionCallbackPlugin.provideInfo(MessageFormat.format(MergeMain.resourceBundle.getString(MergeMain.MSG_PATTERN_KEY_DELETING_DEST_USER_WORKSPACE_DIR_NO_CONFLICTS), moduleVersionDest.getVersion(), pathModuleWorkspace, scmPlugin.getScmUrl(pathModuleWorkspace)));

        workspacePlugin.deleteWorkspaceDir(workspaceDirUserModuleVersion);
        pathModuleWorkspace = null; // To prevent the call to workspacePlugin.releaseWorkspaceDir below.
      }
    } finally {
        if (pathModuleWorkspace != null) {
          workspacePlugin.releaseWorkspaceDir(pathModuleWorkspace);
        }

        this.referencePath.removeLeafReference();
    }

    return true;
  }

  @Override
  protected void afterIterateListModuleVersionRoot() {
    UserInteractionCallbackPlugin userInteractionCallbackPlugin;

    userInteractionCallbackPlugin = ExecContextHolder.get().getExecContextPlugin(UserInteractionCallbackPlugin.class);

    userInteractionCallbackPlugin.provideInfo("###############################################################################");

    if (!this.listStringModuleVersionMerge.isEmpty()) {
      Collections.sort(this.listStringModuleVersionMerge);

      userInteractionCallbackPlugin.provideInfo(MergeMain.resourceBundle.getString(MergeMain.MSG_PATTERN_KEY_LIST_MODULE_VERSION_MERGE));
      userInteractionCallbackPlugin.provideInfo(String.join("\n", this.listStringModuleVersionMerge));
    }

    if (!this.listStringModuleVersionMergeConflicts.isEmpty()) {
      Collections.sort(this.listStringModuleVersionMergeConflicts);

      userInteractionCallbackPlugin.provideInfo(MergeMain.resourceBundle.getString(MergeMain.MSG_PATTERN_KEY_LIST_MODULE_VERSION_MERGE_CONFLICTS));
      userInteractionCallbackPlugin.provideInfo(String.join("\n", this.listStringModuleVersionMergeConflicts));
    }

    if (!this.listStringModuleVersionNothingToMerge.isEmpty()) {
      Collections.sort(this.listStringModuleVersionNothingToMerge);

      userInteractionCallbackPlugin.provideInfo(MergeMain.resourceBundle.getString(MergeMain.MSG_PATTERN_KEY_LIST_MODULE_VERSION_NOTHING_TO_MERGE));
      userInteractionCallbackPlugin.provideInfo(String.join("\n", this.listStringModuleVersionNothingToMerge));
    }

    if (!this.listStringModuleVersionSkipped.isEmpty()) {
      Collections.sort(this.listStringModuleVersionSkipped);

      userInteractionCallbackPlugin.provideInfo(MergeMain.resourceBundle.getString(MergeMain.MSG_PATTERN_KEY_LIST_MODULE_VERSION_SKIPPED));
      userInteractionCallbackPlugin.provideInfo(String.join("\n", this.listStringModuleVersionSkipped));
    }
  }
}
