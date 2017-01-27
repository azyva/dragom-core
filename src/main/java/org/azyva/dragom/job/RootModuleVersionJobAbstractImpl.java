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
import java.util.List;
import java.util.ResourceBundle;

import org.apache.commons.lang.StringUtils;
import org.azyva.dragom.apiutil.ByReference;
import org.azyva.dragom.execcontext.ExecContext;
import org.azyva.dragom.execcontext.plugin.RuntimePropertiesPlugin;
import org.azyva.dragom.execcontext.plugin.UserInteractionCallbackPlugin;
import org.azyva.dragom.execcontext.plugin.WorkspaceDirUserModuleVersion;
import org.azyva.dragom.execcontext.plugin.WorkspacePlugin;
import org.azyva.dragom.execcontext.support.ExecContextHolder;
import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.ModuleVersion;
import org.azyva.dragom.model.Version;
import org.azyva.dragom.model.VersionType;
import org.azyva.dragom.model.plugin.ModuleVersionMatcherPlugin;
import org.azyva.dragom.model.plugin.ReferenceManagerPlugin;
import org.azyva.dragom.model.plugin.ScmPlugin;
import org.azyva.dragom.reference.Reference;
import org.azyva.dragom.reference.ReferencePathMatcher;
import org.azyva.dragom.util.AlwaysNeverYesNoAskUserResponse;
import org.azyva.dragom.util.RuntimeExceptionUserError;
import org.azyva.dragom.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class that builds upon RootModuleVersionJobSimpleAbstractImpl for
 * implementing jobs based on root {@link ModuleVersion}'s and
 * which traverse the reference graph by checking out the {@link ModuleVersion}
 * source code and using {@link ReferenceManagerPlugin} and other
 * (@link NodePlugin}'s to obtain {@link Reference}'s.
 *
 * <p>It factors out code that is often encountered in these types of tasks.
 *
 * <p>After validating the root ModuleVersion's, it iterates over them. For each
 * ModuleVersion it calls {@link #visitModuleVersion}.
 *
 * <p>This class does not attempt to completely encapsulate its implementation. It
 * has protected instance variables available to subclasses to simplify
 * implementation.
 *
 * @author David Raymond
 */
public abstract class RootModuleVersionJobAbstractImpl extends RootModuleVersionJobSimpleAbstractImpl {
  /**
   * Logger for the class.
   */
  private static final Logger logger = LoggerFactory.getLogger(RootModuleVersionJobAbstractImpl.class);

  /**
   * Runtime property of the type {@link AlwaysNeverYesNoAskUserResponse} indicating
   * whether to synchronize the workspace directory when unsynced remote changes
   * exist.
   */
  protected static final String RUNTIME_PROPERTY_SYNC_WORKSPACE_DIR = "SYNC_WORKSPACE_DIR";

  /**
   * See description in ResourceBundle.
   */
  protected static final String MSG_PATTERN_KEY_ROOT_MODULE_VERSION_NOT_KNOWN = "ROOT_MODULE_VERSION_NOT_KNOWN";

  /**
   * See description in ResourceBundle.
   */
  protected static final String MSG_PATTERN_KEY_MULTIPLE_WORKSPACE_DIRECTORIES_FOR_MODULE = "MULTIPLE_WORKSPACE_DIRECTORIES_FOR_MODULE";

  /**
   * See description in ResourceBundle.
   */
  protected static final String MSG_PATTERN_KEY_UPDATE_ROOT_MODULE_VERSION_TO_WORKSPACE_DIRECTORY_VERSION = "UPDATE_ROOT_MODULE_VERSION_TO_WORKSPACE_DIRECTORY_VERSION";

  /**
   * See description in ResourceBundle.
   */
  protected static final String MSG_PATTERN_KEY_UPDATE_ROOT_MODULE_VERSION_TO_DEFAULT = "UPDATE_ROOT_MODULE_VERSION_TO_DEFAULT";

  /**
   * See description in ResourceBundle.
   */
  protected static final String MSG_PATTERN_KEY_VERSION_DOES_NOT_EXIST = "VERSION_DOES_NOT_EXIST";

  /**
   * See description in ResourceBundle.
   */
  protected static final String MSG_PATTERN_KEY_INITIATING_TRAVERSAL_REFERENCE_GRAPH_ROOT_MODULE_VERSION = "INITIATING_TRAVERSAL_REFERENCE_GRAPH_ROOT_MODULE_VERSION";

  /**
   * See description in ResourceBundle.
   */
  protected static final String MSG_PATTERN_KEY_TRAVERSAL_REFERENCE_GRAPH_ROOT_MODULE_VERSION_COMPLETED = "TRAVERSAL_REFERENCE_GRAPH_ROOT_MODULE_VERSION_COMPLETED";

  /**
   * See description in ResourceBundle.
   */
  protected static final String MSG_PATTERN_KEY_UPDATE_CHANGED_ROOT_MODULE_VERSION = "UPDATE_CHANGED_ROOT_MODULE_VERSION";

  /**
   * See description in ResourceBundle.
   */
  //TODO: Probably should remove altogether, including message in bundle. Redundant.
  //protected static final String MSG_PATTERN_KEY_VISITING_LEAF_MODULE_VERSION = "VISITING_LEAF_MODULE_VERSION";

  /**
   * See description in ResourceBundle.
   */
  protected static final String MSG_PATTERN_KEY_CANNOT_PROCEED_WITH_UNSYNC_LOCAL_CHANGES = "CANNOT_PROCEED_WITH_UNSYNC_LOCAL_CHANGES";

  /**
   * See description in ResourceBundle.
   */
  protected static final String MSG_PATTERN_KEY_DO_YOU_WANT_TO_UPDATE_UNSYNC_REMOTE_CHANGES = "DO_YOU_WANT_TO_UPDATE_UNSYNC_REMOTE_CHANGES";

  /**
   * See description in ResourceBundle.
   */
  protected static final String MSG_PATTERN_KEY_UPDATING = "UPDATING";

  /**
   * See description in ResourceBundle.
   */
  protected static final String MSG_PATTERN_KEY_CONFLICTS_WHILE_UPDATING = "CONFLICTS_WHILE_UPDATING";

  /**
   * See description in ResourceBundle.
   */
  protected static final String MSG_PATTERN_KEY_VISITING_LEAF_REFERENCE_MATCHED = "VISITING_LEAF_REFERENCE_MATCHED";

  /**
   * See description in ResourceBundle.
   */
  protected static final String MSG_PATTERN_KEY_ACTIONS_PERFORMED = "ACTIONS_PERFORMED";

  /**
   * See description in ResourceBundle.
   */
  protected static final String MSG_PATTERN_KEY_NO_ACTIONS_PERFORMED = "NO_ACTIONS_PERFORMED";

  /**
   * See description in ResourceBundle.
   */
  protected static final String MSG_PATTERN_KEY_MATCH_MESSAGES = "MATCH_MESSAGES";

  /**
   * ResourceBundle specific to this class.
   */
  protected static final ResourceBundle resourceBundle = ResourceBundle.getBundle(RootModuleVersionJobAbstractImpl.class.getName() + "ResourceBundle");

  /**
   * Used to accumulate a description for the actions performed.
   */
  protected List<String> listActionsPerformed;

  /**
   * Used to accumulate the messages returned by
   * {@link ModuleVersionMatcherPlugin#matches}.
   */
  protected List<String> listMatchMessages;

  /**
   * Indicates that the List of root {@link ModuleVersion} passed to the constructor
   * was changed and should be saved by the caller if persisted.
   */
  protected boolean indListModuleVersionRootChanged;

  /**
   * Constructor.
   *
   * @param listModuleVersionRoot List of root ModuleVersion's on which to initiate
   *   the traversal of the reference graphs.
   */
  protected RootModuleVersionJobAbstractImpl(List<ModuleVersion> listModuleVersionRoot) {
    super(listModuleVersionRoot);

    this.listActionsPerformed = new ArrayList<String>();
    this.listMatchMessages = new ArrayList<String>();
  }

 /*
  * This class provides a default implementation which calls
  * {@link #beforeIterateListModuleVersionRoot},
  * {@link #iterateListModuleVersionRoot} and
  * {@link #afterIterateListModuleVersionRoot}. If ever this behavior is not
  * appropriate for the job, subclasses can simply override the method.
  * Alternatively, the methods mentioned above can be overridden individually.
  */
  @Override
  public void performJob() {
//    this.beforeValidateListModuleVersionRoot();
//    this.validateListModuleVersionRoot();
    this.beforeIterateListModuleVersionRoot();
    this.iterateListModuleVersionRoot();
    this.afterIterateListModuleVersionRoot();
  }

  /*
   * Called by {@link #performJob}. Subclasses can override to introduce
   * job-specific behavior.
   * <p>
   * This implementation does nothing.
  protected void beforeValidateListModuleVersionRoot() {
  }
   */

  /*
   * NOTE: It is not believed this method is really useful. It wastes processing
   * time since when root ModuleVersion's were added to the list of root they can be
   * be assumed to have been validated then.
   *
   * Called by {@link #performJob} to validate the root ModuleVersion's.
   * <p>
   * This performs a first pass to validate the root ModuleVersion's. The reason
   * is that if one ModuleVersion is invalid (module not known to the model or
   * Version does not exist), many actions may have already been performed and
   * it is better for the user to detect the error before doing anything.
  protected void validateListModuleVersionRoot() {
    UserInteractionCallbackPlugin userInteractionCallbackPlugin;
    WorkspacePlugin workspacePlugin;
    int indexModuleVersionRoot;
    ModuleVersion moduleVersion;
    Module module;
    ScmPlugin scmPlugin;

    userInteractionCallbackPlugin = ExecContextHolder.get().getExecContextPlugin(UserInteractionCallbackPlugin.class);
    workspacePlugin = ExecContextHolder.get().getExecContextPlugin(WorkspacePlugin.class);

    RootModuleVersionJobAbstractImpl.logger.info("Starting the iteration among the root ModuleVersion's " + this.listModuleVersionRoot + " to validate them.");

    for (indexModuleVersionRoot = 0; indexModuleVersionRoot < this.listModuleVersionRoot.size(); indexModuleVersionRoot++) {
      moduleVersion = this.listModuleVersionRoot.get(indexModuleVersionRoot);

      RootModuleVersionJobAbstractImpl.logger.info("Validating root module " + moduleVersion.getNodePath() + '.');

      module = ExecContextHolder.get().getModel().getModule(moduleVersion.getNodePath());

      if (module == null) {
        throw new RuntimeExceptionUserError(MessageFormat.format(RootModuleVersionJobAbstractImpl.resourceBundle.getString(RootModuleVersionJobAbstractImpl.MSG_PATTERN_KEY_ROOT_MODULE_VERSION_NOT_KNOWN), moduleVersion));
      }

      scmPlugin = module.getNodePlugin(ScmPlugin.class, null);

      // TODO: Version cannot be null anymore, if ever this is reintroduced.
      // The Version can be null to indicate the main version.
      if (moduleVersion.getVersion() == null) {
        Set<WorkspaceDir> setWorkspaceDir;
        Version version;

        setWorkspaceDir = workspacePlugin.getSetWorkspaceDir(new WorkspaceDirUserModuleVersion(moduleVersion));

        if (setWorkspaceDir.size() > 1) {
          throw new RuntimeExceptionUserError(MessageFormat.format(RootModuleVersionJobAbstractImpl.resourceBundle.getString(RootModuleVersionJobAbstractImpl.MSG_PATTERN_KEY_MULTIPLE_WORKSPACE_DIRECTORIES_FOR_MODULE), moduleVersion));
        }

        if (setWorkspaceDir.size() == 1) {
          version = ((WorkspaceDirUserModuleVersion)setWorkspaceDir.iterator().next()).getModuleVersion().getVersion();
          userInteractionCallbackPlugin.provideInfo(MessageFormat.format(RootModuleVersionJobAbstractImpl.resourceBundle.getString(RootModuleVersionJobAbstractImpl.MSG_PATTERN_KEY_UPDATE_ROOT_MODULE_VERSION_TO_WORKSPACE_DIRECTORY_VERSION), moduleVersion, version));
        } else {
          version = scmPlugin.getDefaultVersion();
          userInteractionCallbackPlugin.provideInfo(MessageFormat.format(RootModuleVersionJobAbstractImpl.resourceBundle.getString(RootModuleVersionJobAbstractImpl.MSG_PATTERN_KEY_UPDATE_ROOT_MODULE_VERSION_TO_DEFAULT), moduleVersion, version));
        }

        // ModuleVersion is immutable. We need to create a new one.
        this.listModuleVersionRoot.set(indexModuleVersionRoot,  moduleVersion = new ModuleVersion(moduleVersion.getNodePath(), version));
        this.setIndListModuleVersionRootChanged();
      }

      if (!scmPlugin.isVersionExists(moduleVersion.getVersion())) {
        throw new RuntimeExceptionUserError(MessageFormat.format(RootModuleVersionJobAbstractImpl.resourceBundle.getString(RootModuleVersionJobAbstractImpl.MSG_PATTERN_KEY_VERSION_DOES_NOT_EXIST), moduleVersion));
      }
    }

    RootModuleVersionJobAbstractImpl.logger.info("Iteration among all root ModuleVersion's " + this.listModuleVersionRoot + " completed for validation.");
  }
   */

  /**
   * Called by {@link #performJob}. Subclasses can override to introduce
   * job-specific behavior.
   * <p>
   * This implementation does nothing.
   */
  protected void beforeIterateListModuleVersionRoot() {
  }

  /**
   * Called by {@link #performJob} to iterate through the List of root
   * ModuleVersion's calling visitModuleVersion for each root ModuleVersion.
   */
  protected void iterateListModuleVersionRoot() {
    UserInteractionCallbackPlugin userInteractionCallbackPlugin;
    ByReference<Version> byReferenceVersion;
    int indexModuleVersionRoot;
    ModuleVersion moduleVersion;

    userInteractionCallbackPlugin = ExecContextHolder.get().getExecContextPlugin(UserInteractionCallbackPlugin.class);

    byReferenceVersion = new ByReference<Version>();

    userInteractionCallbackPlugin.provideInfo(MessageFormat.format(Util.getLocalizedMsgPattern(Util.MSG_PATTERN_KEY_STARTING_JOB), this.getClass().getSimpleName()));

    RootModuleVersionJobAbstractImpl.logger.info("Starting the iteration among the root ModuleVersion's " + this.listModuleVersionRoot + '.');

    for (indexModuleVersionRoot = 0; indexModuleVersionRoot < this.listModuleVersionRoot.size(); indexModuleVersionRoot++) {
      boolean indVersionChanged;

      moduleVersion = this.listModuleVersionRoot.get(indexModuleVersionRoot);

      userInteractionCallbackPlugin.provideInfo(MessageFormat.format(RootModuleVersionJobAbstractImpl.resourceBundle.getString(RootModuleVersionJobAbstractImpl.MSG_PATTERN_KEY_INITIATING_TRAVERSAL_REFERENCE_GRAPH_ROOT_MODULE_VERSION), moduleVersion));

      try {
        indVersionChanged = this.visitModuleVersion(new Reference(moduleVersion), byReferenceVersion);
      } catch (RuntimeExceptionUserError reue) {
        throw reue;
      } catch (RuntimeException re) {
        userInteractionCallbackPlugin.provideInfo(MessageFormat.format(Util.getLocalizedMsgPattern(Util.MSG_PATTERN_KEY_EXCEPTION_THROWN_WHILE_VISITING), moduleVersion));
        RootModuleVersionJobAbstractImpl.logger.error("Exception thrown while visiting " + moduleVersion + '.', re);
        continue;
      }

      RootModuleVersionJobAbstractImpl.logger.info("The current traversal of the reference graph rooted at ModuleVersion " + moduleVersion + " is completed.");
      userInteractionCallbackPlugin.provideInfo(MessageFormat.format(RootModuleVersionJobAbstractImpl.resourceBundle.getString(RootModuleVersionJobAbstractImpl.MSG_PATTERN_KEY_TRAVERSAL_REFERENCE_GRAPH_ROOT_MODULE_VERSION_COMPLETED), moduleVersion));

      if (indVersionChanged) {
        userInteractionCallbackPlugin.provideInfo(MessageFormat.format(RootModuleVersionJobAbstractImpl.resourceBundle.getString(RootModuleVersionJobAbstractImpl.MSG_PATTERN_KEY_UPDATE_CHANGED_ROOT_MODULE_VERSION), moduleVersion, byReferenceVersion.object));

        // We must create a new ModuleVersion as it is immutable.
        this.listModuleVersionRoot.set(indexModuleVersionRoot, moduleVersion = new ModuleVersion(moduleVersion.getNodePath(), byReferenceVersion.object));
        this.setIndListModuleVersionRootChanged();
      }

      if (Util.isAbort()) {
        userInteractionCallbackPlugin.provideInfo(Util.getLocalizedMsgPattern(Util.MSG_PATTERN_KEY_JOB_ABORTED_BY_USER));
        break;
      }
    }

    RootModuleVersionJobAbstractImpl.logger.info("Iteration among all root ModuleVersions " + this.listModuleVersionRoot + " completed.");

    if (this.listActionsPerformed.size() != 0) {
      userInteractionCallbackPlugin.provideInfo(MessageFormat.format(RootModuleVersionJobAbstractImpl.resourceBundle.getString(RootModuleVersionJobAbstractImpl.MSG_PATTERN_KEY_ACTIONS_PERFORMED), StringUtils.join(this.listActionsPerformed, '\n')));
    } else {
      userInteractionCallbackPlugin.provideInfo(RootModuleVersionJobAbstractImpl.resourceBundle.getString(RootModuleVersionJobAbstractImpl.MSG_PATTERN_KEY_NO_ACTIONS_PERFORMED));
    }

    if (this.listMatchMessages.size() != 0) {
      userInteractionCallbackPlugin.provideInfo(MessageFormat.format(RootModuleVersionJobAbstractImpl.resourceBundle.getString(RootModuleVersionJobAbstractImpl.MSG_PATTERN_KEY_MATCH_MESSAGES), StringUtils.join(this.listMatchMessages, '\n')));
    }

    userInteractionCallbackPlugin.provideInfo(MessageFormat.format(Util.getLocalizedMsgPattern(Util.MSG_PATTERN_KEY_JOB_COMPLETED), this.getClass().getSimpleName()));
  }

  /**
   * This method is called for each root {@link ModuleVersion}. Subclasses can
   * override it to provide a job-specific behavior at the root ModuleVersion
   * level.
   * <p>
   * It could have been called visitRootModuleVersion to make more obvious the
   * context in which is it called, but it is expected to often be called
   * recursively by itself for the {@link Reference}'s within the reference graph,
   * which this implementation actually does.
   * <p>
   * This implementation does a traversal of the reference graph rooted at the root
   * ModuleVersion by recursively invoking itself and for each ModuleVersion
   * matching the {@link ReferencePathMatcher} provided to the class using
   * {@link #setReferencePathMatcherProvided}, it calls
   * {@link #visitMatchedModuleVersion}.
   * <p>
   * If {@link #indDepthFirst}, the traversal is depth first. Otherwise it is
   * parent first.
   * <p>
   * This implementation returns false as it does not handle {@link Version}
   * changes.
   *
   * @param reference Root ModuleVersion passed as a Reference so that the
   *   initial parent element of the ReferencePath can be created.
   * @param byReferenceVersion If the method returns true, contains the new Version
   *   of the root ModuleVersion.
   * @return Indicates if the Version of the root ModuleVersion was changed and this
   *   change deserves to be reflected in the List of root ModuleVersion's provided
   *   by the caller.
   */
  protected boolean visitModuleVersion(Reference reference, ByReference<Version> byReferenceVersion) {
    ExecContext execContext;
    UserInteractionCallbackPlugin userInteractionCallbackPlugin;
    WorkspacePlugin workspacePlugin;
    RuntimePropertiesPlugin runtimePropertiesPlugin;
    UserInteractionCallbackPlugin.IndentHandle indentHandle;
    ModuleVersion moduleVersion;
    Module module;
    ScmPlugin scmPlugin;
    Path pathModuleWorkspace;
    boolean indUserWorkspaceDirectory;
    AlwaysNeverYesNoAskUserResponse alwaysNeverYesNoAskUserResponse;
    boolean indReferencePathAlreadyReverted;
    boolean indVisitChildren;
    ModuleVersionMatcherPlugin moduleVersionMatcherPlugin;
    EnumSet<ModuleVersionMatcherPlugin.MatchFlag> enumSetMatchFlag;
    ByReference<String> byReferenceMessage;

    this.referencePath.add(reference);
    indReferencePathAlreadyReverted = false;

    execContext = ExecContextHolder.get();
    userInteractionCallbackPlugin = execContext.getExecContextPlugin(UserInteractionCallbackPlugin.class);
    workspacePlugin = execContext.getExecContextPlugin(WorkspacePlugin.class);
    runtimePropertiesPlugin = execContext.getExecContextPlugin(RuntimePropertiesPlugin.class);

    pathModuleWorkspace = null;
    indentHandle = null;

    // We use a try-finally construct to ensure that the current ModuleVersion always
    // gets removed for the current ReferencePath, and that the
    // UserInteractionCallback IndentHandle gets closed.
    try {
      RootModuleVersionJobAbstractImpl.logger.info("Visiting leaf ModuleVersion " + reference.getModuleVersion() + " of ReferencePath " + this.referencePath + '.');

      indentHandle = userInteractionCallbackPlugin.startIndent();

      // Usually startIndent is followed by provideInfo to provide an initial message
      // following the new indent. But the message here ("visiting leaf ModuleVersion")
      // would be often not useful if no particular action is performed. We therefore
      // simply start the indent and wait for the first useful information, if any.
      //TODO: Probably should remove altogether, including message in bundle. Redundant.
      //userInteractionCallbackPlugin.provideInfo(MessageFormat.format(RootModuleVersionJobAbstractImpl.resourceBundle.getString(RootModuleVersionJobAbstractImpl.MSG_PATTERN_KEY_VISITING_LEAF_MODULE_VERSION), this.referencePath, this.referencePath.getLeafModuleVersion()));

      moduleVersion = reference.getModuleVersion();
      module = execContext.getModel().getModule(moduleVersion.getNodePath());

      scmPlugin = module.getNodePlugin(ScmPlugin.class, null);

      // The verification for handling or not static Version can be done here since if
      // static Version's are not to be handled and this is a static Version, there is
      // no need to traverse the graph since by definition all directly and indirectly
      // referenced ModuleVersion's are also static.
      if ((moduleVersion.getVersion().getVersionType() == VersionType.STATIC) && !this.indHandleStaticVersion) {
        RootModuleVersionJobAbstractImpl.logger.info("ModuleVersion " + moduleVersion + " is static and is not to be handled.");
        return false;
      }

      try {
        // We need to have access to the sources of the Module at different places below:
        // - For verifying for unsynchronized local or remote changes
        // - To obtain the list of references and iterate over them
        // There are a few combinations of cases where accessing the sources is not
        // required at all, but they are few and we prefer simplicity here and to always
        // obtain the path to the workspace directory.
        // If the user already has the correct version of the module checked out, we need
        // to use it. If not, we need an internal working directory.
        // ScmPlugin.checkoutSystem does just that.
        pathModuleWorkspace = scmPlugin.checkoutSystem(moduleVersion.getVersion());
      } catch (RuntimeExceptionUserError reue) {
        throw reue;
      } catch (RuntimeException re) {
        throw new RuntimeException("Could not checkout Version " + moduleVersion.getVersion() + " of Module " + module + '.', re);
      }

      // We need to know if the workspace directory belongs to the user since system
      // workspace directories are always kept synchronized.
      indUserWorkspaceDirectory = (workspacePlugin.getWorkspaceDirFromPath(pathModuleWorkspace) instanceof WorkspaceDirUserModuleVersion);

      if (indUserWorkspaceDirectory && (this.unsyncChangesBehaviorLocal != UnsyncChangesBehavior.DO_NOT_HANDLE)) {
        if (!scmPlugin.isSync(pathModuleWorkspace, ScmPlugin.IsSyncFlag.LOCAL_CHANGES_ONLY)) {
          switch (this.unsyncChangesBehaviorLocal) {
          case USER_ERROR:
            throw new RuntimeExceptionUserError(MessageFormat.format(Util.getLocalizedMsgPattern(Util.MSG_PATTERN_KEY_WORKSPACE_DIRECTORY_NOT_SYNC), pathModuleWorkspace));

          case INTERACT:
            userInteractionCallbackPlugin.provideInfo(MessageFormat.format(RootModuleVersionJobAbstractImpl.resourceBundle.getString(RootModuleVersionJobAbstractImpl.MSG_PATTERN_KEY_CANNOT_PROCEED_WITH_UNSYNC_LOCAL_CHANGES), pathModuleWorkspace, moduleVersion));

            if (!Util.handleDoYouWantToContinue(Util.DO_YOU_WANT_TO_CONTINUE_CONTEXT_SWITCH_WITH_UNSYNC_LOCAL_CHANGES)) {
              return false;
            }
            break;

          default:
            throw new RuntimeException("Must not get here.");
          }
        }
      }

      if (indUserWorkspaceDirectory && (this.unsyncChangesBehaviorRemote != UnsyncChangesBehavior.DO_NOT_HANDLE)) {
        if (!scmPlugin.isSync(pathModuleWorkspace, ScmPlugin.IsSyncFlag.REMOTE_CHANGES_ONLY)) {
          switch (this.unsyncChangesBehaviorRemote) {
          case USER_ERROR:
            throw new RuntimeExceptionUserError(MessageFormat.format(Util.getLocalizedMsgPattern(Util.MSG_PATTERN_KEY_WORKSPACE_DIRECTORY_NOT_SYNC), pathModuleWorkspace));

          case INTERACT:
            alwaysNeverYesNoAskUserResponse = Util.getInfoAlwaysNeverYesNoAskUserResponseAndHandleAsk(
                runtimePropertiesPlugin,
                RootModuleVersionJobAbstractImpl.RUNTIME_PROPERTY_SYNC_WORKSPACE_DIR,
                userInteractionCallbackPlugin,
                MessageFormat.format(RootModuleVersionJobAbstractImpl.resourceBundle.getString(RootModuleVersionJobAbstractImpl.MSG_PATTERN_KEY_DO_YOU_WANT_TO_UPDATE_UNSYNC_REMOTE_CHANGES), pathModuleWorkspace, moduleVersion));

            if (alwaysNeverYesNoAskUserResponse.isYes()) {
              userInteractionCallbackPlugin.provideInfo(MessageFormat.format(RootModuleVersionJobAbstractImpl.resourceBundle.getString(RootModuleVersionJobAbstractImpl.MSG_PATTERN_KEY_UPDATING), pathModuleWorkspace, moduleVersion));

              if (scmPlugin.update(pathModuleWorkspace)) {
                // We thought about allowing processing to continue when update fails, so that the
                // other ModuleVersion get processed. But this does not always work since if it is
                // the pom.xml that has conflicts, it may actually not be valid XML anymore
                // because of the conflict markers.
                throw new RuntimeExceptionUserError(MessageFormat.format(RootModuleVersionJobAbstractImpl.resourceBundle.getString(RootModuleVersionJobAbstractImpl.MSG_PATTERN_KEY_CONFLICTS_WHILE_UPDATING), pathModuleWorkspace, moduleVersion));
              }
            }
            break;

          default:
            throw new RuntimeException("Must not get here.");
          }
        }
      }

      moduleVersionMatcherPlugin = module.getNodePlugin(ModuleVersionMatcherPlugin.class, null);

      if (moduleVersionMatcherPlugin != null) {
        RootModuleVersionJobAbstractImpl.logger.info("ModuleVersionMatcherPlugin defined for module " + module + ". It will be invoked for matching the ModuleVersion's.");
      }

      indVisitChildren = true;
      enumSetMatchFlag = null;
      byReferenceMessage = new ByReference<String>();

      if (!this.indDepthFirst) {
        if ((moduleVersion.getVersion().getVersionType() == VersionType.DYNAMIC) && !this.indHandleDynamicVersion) {
          RootModuleVersionJobAbstractImpl.logger.info("ModuleVersion " + moduleVersion + " is dynamic and is not to be handled.");
        } else {
          if (this.getReferencePathMatcher().matches(this.referencePath)) {
            if (this.indAvoidReentry && !this.moduleReentryAvoider.processModule(moduleVersion)) {
              RootModuleVersionJobAbstractImpl.logger.info("ModuleVersion " + moduleVersion + " has already been processed. Reentry avoided for ReferencePath " + this.referencePath + " matched by ReferencePathMather.");
              return false;
            }

            if (moduleVersionMatcherPlugin != null) {
              byReferenceMessage.object = null;

              enumSetMatchFlag = moduleVersionMatcherPlugin.matches(this.referencePath, moduleVersion, byReferenceMessage);

              RootModuleVersionJobAbstractImpl.logger.info("ModuleVersionMatcherPlugin EnumSet<MatchFlag> for module " + module + ": " + enumSetMatchFlag);

              if (byReferenceMessage.object != null) {
                userInteractionCallbackPlugin.provideInfo(byReferenceMessage.object);
                this.listMatchMessages.add(byReferenceMessage.object);
              }
            }

            if ((enumSetMatchFlag == null) || enumSetMatchFlag.contains(ModuleVersionMatcherPlugin.MatchFlag.MATCH)) {
              indentHandle = userInteractionCallbackPlugin.startIndent();
              userInteractionCallbackPlugin.provideInfo(MessageFormat.format(RootModuleVersionJobAbstractImpl.resourceBundle.getString(RootModuleVersionJobAbstractImpl.MSG_PATTERN_KEY_VISITING_LEAF_REFERENCE_MATCHED), this.referencePath, moduleVersion));

              // We are about to delegate to visitMatchedModuleVersion for the rest of the
              // processing. This method starts working on the same current module and also
              // manages the ReferencePath. We must therefore reset it now. And we must prevent
              // the finally block from resetting it.
              this.referencePath.removeLeafReference();;
              indReferencePathAlreadyReverted = true;

              // We need to release before visiting the matched ModuleVersion since the
              // workspace directory may need to be accessed again.
              workspacePlugin.releaseWorkspaceDir(pathModuleWorkspace);
              pathModuleWorkspace = null;

              indVisitChildren = this.visitMatchedModuleVersion(reference);

              if (Util.isAbort()) {
                return false;
              }

              // We redo the things that were undone before calling visitMatchedModuleVersion.
              this.referencePath.add(reference);
              indReferencePathAlreadyReverted = false;
            }
          }
        }
      }

      if (   indVisitChildren
          && this.getReferencePathMatcher().canMatchChildren(this.referencePath)
          && (   (enumSetMatchFlag == null)
              || !enumSetMatchFlag.contains(ModuleVersionMatcherPlugin.MatchFlag.SKIP_CHILDREN))) {

        ReferenceManagerPlugin referenceManagerPlugin = null;
        List<Reference> listReference;

        // The workspace directory may have been released above and we need to access it
        // again.
        if (pathModuleWorkspace == null) {
          pathModuleWorkspace = scmPlugin.checkoutSystem(moduleVersion.getVersion());
        }

        if (!module.isNodePluginExists(ReferenceManagerPlugin.class, null)) {
          listReference = Collections.emptyList();
        } else {
          referenceManagerPlugin = module.getNodePlugin(ReferenceManagerPlugin.class, null);
          listReference = referenceManagerPlugin.getListReference(pathModuleWorkspace);
        }

        // We need to release before iterating through the references since the workspace
        // directory may need to be accessed again.
        workspacePlugin.releaseWorkspaceDir(pathModuleWorkspace);
        pathModuleWorkspace = null;

        for (Reference referenceChild: listReference) {
          if (referenceChild.getModuleVersion() == null) {
            RootModuleVersionJobAbstractImpl.logger.info("Reference " + referenceChild + " within ReferencePath " + this.referencePath + " does not include a source reference known to Dragom. It is not processed.");
            continue;
          }

          RootModuleVersionJobAbstractImpl.logger.info("Processing reference " + referenceChild + " within ReferencePath " + this.referencePath + '.');

          try {
            // Generally the byReferenceVersion parameter must not be null. But here we are
            // recursively invoking the same non-overridden method and we know this parameter
            // is actually not used.
            this.visitModuleVersion(referenceChild, null);
          } catch (RuntimeExceptionUserError reue) {
            throw reue;
          } catch (RuntimeException re) {
            userInteractionCallbackPlugin.provideInfo(MessageFormat.format(Util.getLocalizedMsgPattern(Util.MSG_PATTERN_KEY_EXCEPTION_THROWN_WHILE_VISITING), referenceChild));
            RootModuleVersionJobAbstractImpl.logger.error("Exception thrown while visiting " + referenceChild + '.', re);
            continue;
          }

          if (Util.isAbort()) {
            return false;
          }
        }
      }

      if (this.indDepthFirst) {
        if ((moduleVersion.getVersion().getVersionType() == VersionType.DYNAMIC) && !this.indHandleDynamicVersion) {
          RootModuleVersionJobAbstractImpl.logger.info("ModuleVersion " + moduleVersion + " is dynamic and is not to be handled.");
        } else {
          if (this.getReferencePathMatcher().matches(this.referencePath)) {
            // This is not required since indentHandle can only be null here, but the
            // compiler does not know. This avoids a warning.
            if (indentHandle != null) {
              indentHandle.close();
            }

            if (this.indAvoidReentry && !this.moduleReentryAvoider.processModule(moduleVersion)) {
              RootModuleVersionJobAbstractImpl.logger.info("ModuleVersion " + moduleVersion + " has already been processed. Reentry avoided for ReferencePath " + this.referencePath + " matched by ReferencePathMather.");
              return false;
            } else {
              indentHandle = userInteractionCallbackPlugin.startIndent();
              userInteractionCallbackPlugin.provideInfo(MessageFormat.format(RootModuleVersionJobAbstractImpl.resourceBundle.getString(RootModuleVersionJobAbstractImpl.MSG_PATTERN_KEY_VISITING_LEAF_REFERENCE_MATCHED), this.referencePath, moduleVersion));

              if (moduleVersionMatcherPlugin != null) {
                byReferenceMessage.object = null;

                enumSetMatchFlag = moduleVersionMatcherPlugin.matches(this.referencePath, moduleVersion, byReferenceMessage);

                if (byReferenceMessage.object != null) {
                  userInteractionCallbackPlugin.provideInfo(byReferenceMessage.object);
                  this.listMatchMessages.add(byReferenceMessage.object);
                }
              }
            }

            if ((enumSetMatchFlag == null) || enumSetMatchFlag.contains(ModuleVersionMatcherPlugin.MatchFlag.MATCH)) {
              // We are about to delegate to visitMatchedModuleVersion for the rest of the
              // processing. This method starts working on the same current module and also
              // manages the ReferencePath. We must therefore reset it now. And we must prevent
              // the finally block from resetting it.
              this.referencePath.removeLeafReference();;
              indReferencePathAlreadyReverted = true;

              // We need to release before iterating through the references since the workspace
              // directory may need to be accessed again.
              if (pathModuleWorkspace != null) {
                workspacePlugin.releaseWorkspaceDir(pathModuleWorkspace);
                pathModuleWorkspace = null;
              }

              // Return value is useless when the traversal is depth first.
              this.visitMatchedModuleVersion(reference);

              if (Util.isAbort()) {
                return false;
              }

              // We redo the things that were undone before calling visitMatchedModuleVersion.
              this.referencePath.add(reference);
              indReferencePathAlreadyReverted = false;
            }
          }
        }
      }
    } finally {
      if (!indReferencePathAlreadyReverted) {
        this.referencePath.removeLeafReference();
      }

      if (indentHandle != null) {
        indentHandle.close();
      }

      if (pathModuleWorkspace != null) {
        workspacePlugin.releaseWorkspaceDir(pathModuleWorkspace);
      }
    }

    return false;
  }

  /**
    * Called by {@link #visitModuleVersion} for each matched
    * {@link ModuleVersion}. Subclasses can override it to provide a job-specific
    * behavior at the matched ModuleVersion level.
    * <p>
    * If the ReferencePathMatcher is such that a ModuleVeresion is matched and this
    * method is called, it can signal the caller that children must not be visited by
    * returning false. But if the ModuleVersion is not matched, this method is not
    * called and children will be visited. In all cases, the ReferencePathMatcher
    * will be applied to children as well. This applies only if the traversal is
    * parent first.
    * <p>
    * This implementation raises an exception. This method is not abstract since if
    * the subclass overrides visitModuleVersion, it may not be called or need to be
    * overridden at all.
    *
   * @param reference Reference to the matched ModuleVersion.
   * @return Indicates if children must be visited. The return value is ignored if
   *   the traversal is depth first.
   */
  protected boolean visitMatchedModuleVersion(Reference reference) {
    throw new RuntimeException("Must not get here.");
  }

  /**
   * Called by {@link #performJob}. Subclasses can override to introduce
   * job-specific behavior.
   * <p>
   * This implementation does nothing.
   */
  protected void afterIterateListModuleVersionRoot() {
  }
}
