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
import java.util.List;
import java.util.ResourceBundle;
import java.util.Set;

import org.apache.commons.lang.StringUtils;
import org.azyva.dragom.apiutil.ByReference;
import org.azyva.dragom.execcontext.ExecContext;
import org.azyva.dragom.execcontext.plugin.RuntimePropertiesPlugin;
import org.azyva.dragom.execcontext.plugin.UserInteractionCallbackPlugin;
import org.azyva.dragom.execcontext.plugin.WorkspaceDir;
import org.azyva.dragom.execcontext.plugin.WorkspaceDirUserModuleVersion;
import org.azyva.dragom.execcontext.plugin.WorkspacePlugin;
import org.azyva.dragom.execcontext.support.ExecContextHolder;
import org.azyva.dragom.model.ClassificationNode;
import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.ModuleVersion;
import org.azyva.dragom.model.Version;
import org.azyva.dragom.model.VersionType;
import org.azyva.dragom.model.plugin.ReferenceManagerPlugin;
import org.azyva.dragom.model.plugin.ScmPlugin;
import org.azyva.dragom.reference.Reference;
import org.azyva.dragom.reference.ReferenceGraph;
import org.azyva.dragom.reference.ReferencePath;
import org.azyva.dragom.reference.ReferencePathMatcher;
import org.azyva.dragom.reference.ReferencePathMatcherAll;
import org.azyva.dragom.reference.ReferencePathMatcherAnd;
import org.azyva.dragom.reference.ReferencePathMatcherVersionAttribute;
import org.azyva.dragom.util.AlwaysNeverYesNoAskUserResponse;
import org.azyva.dragom.util.ModuleReentryAvoider;
import org.azyva.dragom.util.RuntimeExceptionUserError;
import org.azyva.dragom.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class for implementing jobs based on root {@link ModuleVersion}'s and
 * which traverse the reference graph by checking out the {@link ModuleVersion}
 * source code and using {@link ReferenceManagerPlugin} and other
 * (@link NodePlugin}'s to obtain {@link Reference}'s.
 * <p>
 * It factors out code that is often encountered in these types of tasks.
 * <p>
 * After validating the root ModuleVersion's, it iterates over them. For each
 * ModuleVersion it calls {@link #visitModuleVersion}.
 * <p>
 * This class does not attempt to completely encapsulate its implementation. It
 * has protected instance variables available to subclasses to simplify
 * implementation.
 *
 * @author David Raymond
 */
public abstract class RootModuleVersionJobAbstractImpl {
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
   * Runtime property specifying a project code which many job honour when
   * traversing {@link ReferenceGraph}'s.
   * <p>
   * This provides a matching mechanism for {@link ModuleVersion}'s within a
   * ReferenceGraph. It also has impacts on {@link Version} creations.
   * <p>
   * The idea is that in some cases, Dragom will be used to manage ReferenceGraph's
   * in the context of a project, in the sense of a time-constrained development
   * effort. When switching to dynamic Version's of {@link Module}'s,
   * (@link SwitchToDynamicVesion} can optionnally specify a project code as a
   * Version attribute for newly created dynamic Versions. Similarly with
   * {@link Release} for static Versions. And for may other jobs which traverse
   * a ReferenceGraph ({@link Checkout}, {@link MergeMain}, etc.), this same project
   * code specified by this runtime property is used for matching
   * {@link ModuleVersion}'s based on their Version's project code attribute, in
   * addition to the matching performed by {@link ReferencePathMatcher}'s
   * (implied "and").
   * <p>
   * Accessed on the root {@link ClassificationNode}.
   */
  // TODO: Eventually this may be handled with generic expression-language-based matchers.
  protected static final String RUNTIME_PROPERTY_PROJECT_CODE = "PROJECT_CODE";

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
  protected static final String MSG_PATTERN_KEY_VISITING_LEAF_MODULE_VERSION = "VISITING_LEAF_MODULE_VERSION";

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
  protected static final String MSG_PATTERN_KEY_MODULE_VERSION_ALREADY_PROCESSED = "MODULE_VERSION_ALREADY_PROCESSED";
  /**
   * See description in ResourceBundle.
   */
  protected static final String MSG_PATTERN_KEY_ACTIONS_PERFORMED = "ACTIONS_PERFORMED";

  /**
   * See description in ResourceBundle.
   */
  protected static final String MSG_PATTERN_KEY_NO_ACTIONS_PERFORMED = "NO_ACTIONS_PERFORMED";

  /**
   * ResourceBundle specific to this class.
   */
  protected static final ResourceBundle resourceBundle = ResourceBundle.getBundle(RootModuleVersionJobAbstractImpl.class.getName() + "ResourceBundle");

  /**
   * List root ModuleVersion's on which to initiate the traversal of the reference
   * graphs.
   */
  protected List<ModuleVersion> listModuleVersionRoot;

  /**
   * ReferencePathMatcher defining on which ModuleVersion's in the reference graphs
   * the job will be applied.
   */
  private ReferencePathMatcher referencePathMatcherProvided;

  /**
   * ReferencePathMatcher for filtering based on the project code.
   */
  private ReferencePathMatcher referencePathMatcherProjectCode;

  /**
   * "and"-combined {@link ReferencePathMatcher} for
   * {@link #referencePathMatcherProvided} and
   * {@link #referencePathMatcherProjectCode}.
   * <p>
   * Calculated once the first time it is used and cached in this variable
   * afterward.
   */
  private ReferencePathMatcher referencePathMatcherCombined;

  /**
   * Indicates that dynamic {@link Version}'s must be considered during the
   * traversal of the reference graphs. The default value is true.
   */
  protected boolean indHandleDynamicVersion = true;

  /**
   * Indicates that static {@link Version}'s must be considered during the
   * traversal of the reference graphs. The default value is true.
   */
  protected boolean indHandleStaticVersion = true;

  /**
   * Indicates that {@link #visitModuleVersion} avoids reentry by using
   * {@link ModuleReentryAvoider}. The default value is true.
   */
  protected boolean indAvoidReentry = true;

  /**
   * Indicates that traversal must be depth first, as opposed to parent first.
   */
  protected boolean indDepthFirst;

  /**
   * Possible behaviors related to unsynchronized changes in a user working
   * directory.
   */
  public static enum UnsyncChangesBehavior {
    /**
     * Do not test or handle unsynchronized changes.
     */
    DO_NOT_HANDLE,

    /**
     * Throws {@link RuntimeExceptionUserError} if unsynchronized changes are
     * detected.
     */
    USER_ERROR,

    /**
     * Interact with the user if unsynchronized changes are detected. In the case of
     * local changes, "do you want to continue"-style interaction. In the case of
     * remote changes, interaction for updating.
     */
    INTERACT
  }

  /**
   * Specifies the behavior related to unsynchronized local changes.
   */
  protected UnsyncChangesBehavior unsyncChangesBehaviorLocal;

  /**
   * Specifies the behavior related to unsynchronized remote changes.
   */
  protected UnsyncChangesBehavior unsyncChangesBehaviorRemote;

  /**
   * {@link ModuleReentryAvoider}.
   * <p>
   * Used by this class when matching {@link ReferencePath} if
   * indAvoidReentry. Available to subclasses as well independently of
   * indAvoidReentry.
   */
  protected ModuleReentryAvoider moduleReentryAvoider;

  /**
   * Subclasses can use this variable during the traversal of a reference graph to
   * maintain the current ReferencePath being visited. Upon entry in a method that
   * visits a ModuleVersion, this variable represents the ReferencePath of the
   * parent. During processing it is modified to represent the ReferencePath of the
   * current ModuleVersion and referenced modules as the graph is traversed. Upon
   * exit it is reset to what it was upon entry.
   *
   * This is used mainly in messages since it is useful for the user to know from
   * which Reference a ModuleVersion within a ReferencePath comes from. A root
   * ModuleVersion is wrapped in a Reference.
   */
  protected ReferencePath referencePath;

  /**
   * Used to accumulate a description for the actions performed.
   */
  protected List<String> listActionsPerformed;

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
    this.listModuleVersionRoot = listModuleVersionRoot;

    this.moduleReentryAvoider = new ModuleReentryAvoider();

    this.unsyncChangesBehaviorLocal = UnsyncChangesBehavior.DO_NOT_HANDLE;
    this.unsyncChangesBehaviorRemote = UnsyncChangesBehavior.DO_NOT_HANDLE;

    this.referencePath = new ReferencePath();
    this.listActionsPerformed = new ArrayList<String>();
  }

  /**
   * Sets the {@link ReferencePathMatcher} profided by the caller defining on which
   * ModuleVersion's in the reference graphs the job will be applied.
   *
   * @param referencePathMatcherProvided See description.
   */
  public void setReferencePathMatcherProvided(ReferencePathMatcher referencePathMatcherProvided) {
    this.referencePathMatcherProvided = referencePathMatcherProvided;
  }

  /**
   * @return The project code specified by the user with the PROJECT_CODE runtime
   *   property.
   */
  protected String getProjectCode() {
    return ExecContextHolder.get().getExecContextPlugin(RuntimePropertiesPlugin.class).getProperty(null, RootModuleVersionJobAbstractImpl.RUNTIME_PROPERTY_PROJECT_CODE);
  }

  /**
   * Setup the {@link ReferencePathMatcher} so that only {@link ModuleVersion}'s
   * having the {@link Version} attribute dragom-project-code equal to that
   * defined by the runtime property PROJECT_CODE are matched.
   */
  protected void setupReferencePathMatcherForProjectCode() {
    String projectCode;

    projectCode = this.getProjectCode();

    if (projectCode != null) {
      this.referencePathMatcherProjectCode = new ReferencePathMatcherVersionAttribute(ScmPlugin.VERSION_ATTR_PROJECT_CODE, projectCode, ExecContextHolder.get().getModel());
    }
  }

  /**
   * @return ReferencePathMatcher to use for matching the {@link ReferencePath}'s.
   *   "and" combination of the ReferencePathMatcher provided with
   *   {@link #setReferencePathMatcherProvided} and that setup by
   *   {@link #setupReferencePathMatcherForProjectCode}.
   */
  protected ReferencePathMatcher getReferencePathMatcher() {
    if (this.referencePathMatcherCombined == null) {
      if ((this.referencePathMatcherProvided != null) && (this.referencePathMatcherProjectCode != null)) {
        ReferencePathMatcherAnd referencePathMatcherAnd;

        referencePathMatcherAnd = new ReferencePathMatcherAnd();

        referencePathMatcherAnd.addReferencePathMatcher(this.referencePathMatcherProvided);
        referencePathMatcherAnd.addReferencePathMatcher(this.referencePathMatcherProjectCode);

        this.referencePathMatcherCombined = referencePathMatcherAnd;
      } else if (this.referencePathMatcherProvided != null) {
        this.referencePathMatcherCombined = this.referencePathMatcherProvided;
      } else if (this.referencePathMatcherProjectCode != null) {
        // This case if rather rare since tools generally require the user to specify a
        // ReferencePathMatcher.
        this.referencePathMatcherCombined = this.referencePathMatcherProjectCode;
      } else {
        // If absolutely no ReferencePathMatcher is specified, which is also rare, we
        // match everything.
        this.referencePathMatcherCombined = new ReferencePathMatcherAll();
      }
    }

    return this.referencePathMatcherCombined;
  }

  /**
   * @param indHandleDynamicVersion Specifies to handle or not dynamic
   *   {@link Version}'s. The default is to handle dynamic {@link Version}.
   */
  protected void setIndHandleDynamicVersion(boolean indHandleDynamicVersion) {
    this.indHandleDynamicVersion = indHandleDynamicVersion;
  }

  /**
   * @param indHandleStaticVersion Specifies to handle or not static
   *   {@link Version}'s. The default is to handle static {@link Version}.
   */
  protected void setIndHandleStaticVersion(boolean indHandleStaticVersion) {
    this.indHandleStaticVersion = indHandleStaticVersion;
  }

  /**
   * @param indAvoidReentry Specifies to avoid reentry by using
   *   {@link ModuleReentryAvoider}. The default is to avoid reentry.
   */
  public void setIndAvoidReentry(boolean indAvoidReentry) {
    this.indAvoidReentry = indAvoidReentry;
  }

  /**
   * @param indDepthFirst Specifies to traverse depth first. The default is to
   *   traverse parent-first.
   */
  protected void setIndDepthFirst(boolean indDepthFirst) {
    this.indDepthFirst = indDepthFirst;
  }

  /**
   * @param unsyncChangesBehaviorLocal Behavior related to unsynchronized local
   * changes. The default is {@link UnsyncChangesBehavior#DO_NOT_HANDLE}.
   */
  public void setUnsyncChangesBehaviorLocal(UnsyncChangesBehavior unsyncChangesBehaviorLocal) {
    this.unsyncChangesBehaviorLocal = unsyncChangesBehaviorLocal;
  }

  /**
   * @param unsyncChangesBehaviorRemote Behavior related to unsynchronized remote
   * changes. The default is {@link UnsyncChangesBehavior#DO_NOT_HANDLE}.
   */
  public void setUnsyncChangesBehaviorRemote(UnsyncChangesBehavior unsyncChangesBehaviorRemote) {
    this.unsyncChangesBehaviorRemote = unsyncChangesBehaviorRemote;
  }

  /**
   * Called by methods of this class or subclasses to indicate that the List of root
   * {@link ModuleVersion} passed to the constructor was changed and should be
   * saved by the caller if persisted.
   */
  protected void setIndListModuleVersionRootChanged() {
    this.indListModuleVersionRootChanged = true;
  }

  /**
   * @return Indicate that the List of root {@link ModuleVersion} passed to the
   * constructor was changed and should be saved by the caller if persisted.
   */
  public boolean isListModuleVersionRootChanged() {
    return this.indListModuleVersionRootChanged;
  }

  /**
   * Main method for performing the job.
   * <p>
   * This class provides a default implementation which calls
   * {@link #beforeValidateListModuleVersionRoot},
   * {@link #validateListModuleVersionRoot},
   * {@link #beforeIterateListModuleVersionRoot},
   * {@link #afterIterateListModuleVersionRoot}. If ever this behavior is not
   * appropriate for the job, subclasses can simply override the method.
   * Alternatively, the methods mentioned above can be overridden individually.
   */
  public void performJob() {
    this.beforeValidateListModuleVersionRoot();
    this.validateListModuleVersionRoot();
    this.beforeIterateListModuleVersionRoot();
    this.iterateListModuleVersionRoot();
    this.afterIterateListModuleVersionRoot();
  }

  /**
   * Called by {@link #performJob}. Subclasses can override to introduce
   * job-specific behavior.
   * <p>
   * This implementation does nothing.
   */
  protected void beforeValidateListModuleVersionRoot() {
  }

  /**
   * Called by {@link #performJob} to validate the root ModuleVersion's.
   * <p>
   * This performs a first pass to validate the root ModuleVersion's. The reason
   * is that if one ModuleVersion is invalid (module not known to the model or
   * Version does not exist), many actions may have already been performed and
   * it is better for the user to detect the error before doing anything.
   */
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

      indVersionChanged = this.visitModuleVersion(new Reference(moduleVersion), byReferenceVersion);

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
    UserInteractionCallbackPlugin.BracketHandle bracketHandle;
    ModuleVersion moduleVersion;
    Module module;
    ScmPlugin scmPlugin;
    Path pathModuleWorkspace;
    boolean indUserWorkspaceDirectory;
    AlwaysNeverYesNoAskUserResponse alwaysNeverYesNoAskUserResponse;
    boolean indReferencePathAlreadyReverted;
    boolean indVisitChildren;

    this.referencePath.add(reference);
    indReferencePathAlreadyReverted = false;

    execContext = ExecContextHolder.get();
    userInteractionCallbackPlugin = execContext.getExecContextPlugin(UserInteractionCallbackPlugin.class);
    workspacePlugin = execContext.getExecContextPlugin(WorkspacePlugin.class);
    runtimePropertiesPlugin = execContext.getExecContextPlugin(RuntimePropertiesPlugin.class);

    pathModuleWorkspace = null;
    bracketHandle = null;

    // We use a try-finally construct to ensure that the current ModuleVersion always
    // gets removed for the current ReferencePath, and that the
    // UserInteractionCallback BracketHandle gets closed.
    try {
      //TODO: Probably should remove altogether, including message in bundle. Redundant.
      // We bracket even if the ReferencePath will not be matched in order to better
      // show the traversal.
      //bracketHandle = userInteractionCallbackPlugin.startBracket(MessageFormat.format(RootModuleVersionJobAbstractImpl.resourceBundle.getString(RootModuleVersionJobAbstractImpl.MSG_PATTERN_KEY_VISITING_LEAF_MODULE_VERSION), this.referencePath, this.referencePath.getLeafModuleVersion()));

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

      indVisitChildren = true;

      if (!this.indDepthFirst) {
        if ((moduleVersion.getVersion().getVersionType() == VersionType.DYNAMIC) && !this.indHandleDynamicVersion) {
          RootModuleVersionJobAbstractImpl.logger.info("ModuleVersion " + moduleVersion + " is dynamic and is not to be handled.");
        } else {
          if (this.getReferencePathMatcher().matches(this.referencePath)) {
            if (this.indAvoidReentry && !this.moduleReentryAvoider.processModule(moduleVersion)) {

              // We indent even if we are immediately exiting in order to have a more intuitive
              // layout.
              bracketHandle = userInteractionCallbackPlugin.startBracket(MessageFormat.format(RootModuleVersionJobAbstractImpl.resourceBundle.getString(RootModuleVersionJobAbstractImpl.MSG_PATTERN_KEY_MODULE_VERSION_ALREADY_PROCESSED), this.referencePath, moduleVersion));
              return false;
            } else {
              bracketHandle = userInteractionCallbackPlugin.startBracket(MessageFormat.format(RootModuleVersionJobAbstractImpl.resourceBundle.getString(RootModuleVersionJobAbstractImpl.MSG_PATTERN_KEY_VISITING_LEAF_REFERENCE_MATCHED), this.referencePath, moduleVersion));
            }

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

      if (indVisitChildren && this.getReferencePathMatcher().canMatchChildren(this.referencePath)) {
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

          // Generally the byReferenceVersion parameter must not be null. But here we are
          // recursively invoking the same non-overridden method and we know this parameter
          // is actually not used.
          this.visitModuleVersion(referenceChild, null);

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
              // This is not required since bracketHandle can only be null here, but the
              // compiler does not know. This avoids a warning.
              if (bracketHandle != null) {
                bracketHandle.close();
              }

            if (this.indAvoidReentry && !this.moduleReentryAvoider.processModule(moduleVersion)) {
              // We indent even if we are immediately exiting in order to have a more intuitive
              // layout.
              bracketHandle = userInteractionCallbackPlugin.startBracket(MessageFormat.format(RootModuleVersionJobAbstractImpl.resourceBundle.getString(RootModuleVersionJobAbstractImpl.MSG_PATTERN_KEY_MODULE_VERSION_ALREADY_PROCESSED), this.referencePath, moduleVersion));
              return false;
            } else {
              bracketHandle = userInteractionCallbackPlugin.startBracket(MessageFormat.format(RootModuleVersionJobAbstractImpl.resourceBundle.getString(RootModuleVersionJobAbstractImpl.MSG_PATTERN_KEY_VISITING_LEAF_REFERENCE_MATCHED), this.referencePath, moduleVersion));
            }

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
    } finally {
      if (!indReferencePathAlreadyReverted) {
        this.referencePath.removeLeafReference();
      }

      if (bracketHandle != null) {
        bracketHandle.close();
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
