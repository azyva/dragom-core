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

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;

import org.azyva.dragom.apiutil.ByReference;
import org.azyva.dragom.execcontext.ExecContext;
import org.azyva.dragom.execcontext.plugin.RuntimePropertiesPlugin;
import org.azyva.dragom.execcontext.plugin.UserInteractionCallbackPlugin;
import org.azyva.dragom.execcontext.plugin.WorkspaceDirUserModuleVersion;
import org.azyva.dragom.execcontext.plugin.WorkspacePlugin;
import org.azyva.dragom.execcontext.plugin.impl.MainModuleVersionWorkspacePluginFactory;
import org.azyva.dragom.execcontext.support.ExecContextHolder;
import org.azyva.dragom.model.ArtifactVersion;
import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.ModuleVersion;
import org.azyva.dragom.model.Version;
import org.azyva.dragom.model.VersionType;
import org.azyva.dragom.model.plugin.ArtifactVersionManagerPlugin;
import org.azyva.dragom.model.plugin.ArtifactVersionMapperPlugin;
import org.azyva.dragom.model.plugin.BuilderPlugin;
import org.azyva.dragom.model.plugin.ReferenceManagerPlugin;
import org.azyva.dragom.model.plugin.ScmPlugin;
import org.azyva.dragom.model.plugin.SelectStaticVersionPlugin;
import org.azyva.dragom.model.plugin.impl.ContinuousReleaseSelectStaticVersionPluginImpl;
import org.azyva.dragom.reference.Reference;
import org.azyva.dragom.reference.ReferencePathMatcher;
import org.azyva.dragom.util.AlwaysNeverYesNoAskUserResponse;
import org.azyva.dragom.util.RuntimeExceptionAbort;
import org.azyva.dragom.util.RuntimeExceptionUserError;
import org.azyva.dragom.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * See the help information displayed by the ReleaseTool.help method.
 *
 * The principle of operation of this class is to traverse reference graphs in the
 * standard way using a List of root ModuleVersion's and a ReferencePathMatcher to
 * identify ModuleVersion's for which a static Version must be created. Once such
 * a ModuleVersion is found, the static Version creation process begins which does
 * also involve some traversal of the reference graph in order to ensure that no
 * reference to dynamic Version's remain in the graph and to recursively create
 * static Version's out of them. But this other traversal is not handled through
 * the main traversal. It starts with the matched ModuleVersion and does not take
 * into consideration of List of root ModuleVersion's nor the
 * ReferencePathMatcher.
 *
 * If a static Version must be created for a ModuleVersion outside of a
 * ReferencePath context, listModuleVersionRoot passed to the constructor can
 * contain that single ModuleVersion and no ReferencePathMatcher can be set
 * (thereby using ReferencePathMatcherAll).
 *
 * Note that only ReferencePath's corresponding to dynamic Version's are actually
 * considered, even though the ReferencePathMatcher may match other Version's.
 * Version's.
 *
 * @author David Raymond
 */
public class Release extends RootModuleVersionJobAbstractImpl {
  /**
   * Logger for the class.
   */
  private static final Logger logger = LoggerFactory.getLogger(Release.class);

  /**
   * Runtime property specifying the isolation mode for the release.
   * <p>
   * The possible values are given by {@link ReleaseIsolationMode}.
   */
  private static final String RUNTIME_PROPERTY_RELEASE_ISOLATION_MODE = "RELEASE_ISOLATION_MODE";

  /**
   * Runtime property specifying the build context to pass to
   * {@link BuilderPlugin#build}.
   */
  private static final String RUNTIME_PROPERTY_RELEASE_VERSION_BUILD_CONTEXT = "RELEASE_VERSION_BUILD_CONTEXT";

  /**
   * Runtime property specifying to not perform a validation build before creating the static
   * {@link Version}.
   */
  private static final String RUNTIME_PROPERTY_IND_NO_PRE_RELEASE_VERSION_VALIDATION_BUILD = "IND_NO_PRE_RELEASE_VERSION_VALIDATION_BUILD";

  /**
   * Runtime property of type AlwaysNeverYesNoAskUserResponse that indicates if the
   * artifact Version must be reverted after the creation of the static Version.
   */
  private static final String RUNTIME_PROPERTY_REVERT_ARTIFACT_VERSION = "REVERT_ARTIFACT_VERSION";

  /**
   * Context for {@link Util#handleDoYouWantToContinue} that represents creating a
   * new static Version.
   */
  private static final String DO_YOU_WANT_TO_CONTINUE_CONTEXT_CREATE_STATIC_VERSION = "CREATE_STATIC_VERSION";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_REUSING_ALREADY_SELECTED_STATIC_VERSION = "REUSING_ALREADY_SELECTED_STATIC_VERSION";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_PARENT_WILL_BE_UPDATED_BECAUSE_REFERENCE_CHANGED = "PARENT_WILL_BE_UPDATED_BECAUSE_REFERENCE_CHANGED";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_MODULE_VERSION_CHECKED_OUT_IN_USER_WORKSPACE_DIRECTORY = "MODULE_VERSION_CHECKED_OUT_IN_USER_WORKSPACE_DIRECTORY";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_CHANGE_REFERENCE_VERSION = "CHANGE_REFERENCE_VERSION";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_CHANGE_REFERENCE_VERSION_NO_ARTIFACT_VERSION_CHANGE = "CHANGE_REFERENCE_VERSION_NO_ARTIFACT_VERSION_CHANGE";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_REFERENCE_DYNAMIC_VERSION_EXTERNAL_MODULE = "REFERENCE_DYNAMIC_VERSION_EXTERNAL_MODULE";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_COMMIT_REFERENCE_CHANGE_AFTER_ABORT = "COMMIT_REFERENCE_CHANGE_AFTER_ABORT";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_TEMP_DYNAMIC_VERSION_CREATED = "TEMP_DYNAMIC_VERSION_CREATED";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_REFERENCES_UPDATED = "REFERENCES_UPDATED";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_EXISTING_STATIC_VERSION_SELECTED = "EXISTING_STATIC_VERSION_SELECTED";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_CREATING_NEW_STATIC_VERSION = "CREATING_NEW_STATIC_VERSION";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_ARTIFACT_VERSION_CHANGED = "ARTIFACT_VERSION_CHANGED";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_NO_ARTIFACT_VERSION_CHANGE = "NO_ARTIFACT_VERSION_CHANGE";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_INITIATING_BUILD = "INITIATING_BUILD";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_BUILD_FAILED = "BUILD_FAILED";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_STATIC_VERSION_CREATED = "STATIC_VERSION_CREATED";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_DO_YOU_WANT_TO_REVERT_ARTIFACT_VERSION = "DO_YOU_WANT_TO_REVERT_ARTIFACT_VERSION";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_ARTIFACT_VERSION_REVERTED = "ARTIFACT_VERSION_REVERTED";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_NO_REVERTED_ARTIFACT_VERSION_CHANGE = "NO_REVERTED_ARTIFACT_VERSION_CHANGE";

  /**
   * ResourceBundle specific to this class.
   */
  private static final ResourceBundle resourceBundle = ResourceBundle.getBundle(Release.class.getName() + "ResourceBundle");

  /**
   * Defines the various release isolation modes.
   */
  private enum ReleaseIsolationMode {
    /**
     * No isolation. After the release, the {@link ModuleVersion} will have its
     * references updated to static {@link Version}'s and its {@link ArtifactVersion}
     * changed.
     * <p>
     * This state is somewhat inconsistent since the {@link Version} at the source
     * level, will still be dynamic, and the ArtifactVersion will be static. But
     * this is supported and {@link SwitchToDynamicVersion} can be used to "correct"
     * the ArtifactVersion.
     * <p>
     * This is the default.
     */
    NONE,

    /**
     * Revert the {@link ArtifactVersion} only.
     * <p>
     * The {@link ModuleVersion} references will have its references updated.
     */
    REVERT_ARTIFACT_VERSION,

    /**
     * Same as {@link #REVERT_ARTIFACT_VERSION_ASK}, but interact with the user for
     * confirmation.
     */
    REVERT_ARTIFACT_VERSION_ASK,

    /**
     * A temporary dynamic {@link Version} is used to completely isolate the Version
     * creation process. The {@link ModuleVersion} references will remain as before on
     * the original dynamic Version, as well as its {@link ArtifactVersion}.
     * <p>
     * This mode is useful in a continuous delivery context when used with
     * {@link MainModuleVersionWorkspacePluginFactory} and
     * {@link ContinuousReleaseSelectStaticVersionPluginImpl}.
     */
    USE_TEMP_DYNAMIC_VERSION
  }

  /**
   * Map of all ModuleVersion which were created during the processing. The value
   * of map elements is the static version created. We record that information so
   * that if we encounter again the same module during the processing, we assume the
   * user will want to create that same version and avoid interacting with him.
   *
   * Normally within a ReferencePath we expect to encounter at most a single dynamic
   * Version for a given module, so that the Map could have been from NodePath to
   * Version. But in theory there could be multiple Version's for a Module and the
   * user may decide to create a different static Version.
   */
  private Map<ModuleVersion, Version> mapModuleVersionStatic;

  /**
   * Constructor.
   *
   * @param listModuleVersionRoot List of root ModuleVersion's to release.
   */
  public Release(List<ModuleVersion> listModuleVersionRoot) {
    super(listModuleVersionRoot);

    this.mapModuleVersionStatic = new HashMap<ModuleVersion, Version>();
  }

  /**
   * Called by the base class when visiting a root {@link ModuleVersion}.
   * <p>
   * It is not possible to reuse the default implementation of this method provided
   * by the base class as job-specific behavior is required while traversing the
   * reference graph.
   * <p>
   * This implementation delegates to {@link #visitModuleVersionInternal} which
   * handles all of the required processing.
   * <p>
   * During the traversal, two cases must be considered. There is the initial
   * traversal starting at a root ModuleVersion in search for ModuleVersion's
   * matching the {@link ReferencePathMatcher}, and when a ModuleVersion matches,
   * there is the recursive release itself.
   * <p>
   * But these two cases have many similarities, such as having to update the
   * references in the parent when a referenced ModuleVersion is released. We want
   * to avoid having two different methods so we have only the
   * visitModuleVersionInternal method that takes a boolean so that it knows in
   * which context it operates.
   */
  @Override
  protected boolean visitModuleVersion(Reference reference, ByReference<Version> byReferenceVersion) {
    return this.visitModuleVersionInternal(reference, byReferenceVersion, false);
  }

  /**
   * Main method that performs the traversal of the reference graph rooted at a
   * given {@link ModuleVersion}.
   * <p>
   * In order to avoid duplication, this method operates in two modes as indicated
   * by indRelease. When false, it simply traverses the graph in search for matching
   * ModuleVersion. When true is actually performs the recursive release for a
   * matched ModuleVersion.
   * <p>
   * See also {@link #visitModuleVersion}.
   *
   * @param reference Root ModuleVersion passed as a Reference so that the
   *   initial parent element of the ReferencePath can be created.
   * @param byReferenceVersion If the method returns true, contains the new Version
   *   of the root ModuleVersion.
   * @param indRelease Indicates if we operate in release mode.
   * @return Indicates if the Version of the root ModuleVersion was changed.
   */
  protected boolean visitModuleVersionInternal(Reference reference, ByReference<Version> byReferenceVersion, boolean indRelease) {
    ExecContext execContext;
    UserInteractionCallbackPlugin userInteractionCallbackPlugin;
    WorkspacePlugin workspacePlugin;
    RuntimePropertiesPlugin runtimePropertiesPlugin;
    Module module;
    ScmPlugin scmPlugin;
    UserInteractionCallbackPlugin.IndentHandle indentHandle;
    boolean indTempDynamicVersionCreated;
    Path pathModuleWorkspace = null;
    String property;
    ReleaseIsolationMode releaseIsolationMode;

    if (reference.getModuleVersion().getVersion().getVersionType() != VersionType.DYNAMIC) {
      if (indRelease) {
        // This should not happen since this method is not supposed to be called on static
        // version with indRelease.
        throw new RuntimeException("ModuleVersion " + reference.getModuleVersion() + " within ReferencePath " + this.referencePath + " is not dynamic.");
      }

      // We are only interested in dynamic Version's.
      return false;
    }

    execContext = ExecContextHolder.get();
    userInteractionCallbackPlugin = execContext.getExecContextPlugin(UserInteractionCallbackPlugin.class);
    workspacePlugin = execContext.getExecContextPlugin(WorkspacePlugin.class);
    runtimePropertiesPlugin = execContext.getExecContextPlugin(RuntimePropertiesPlugin.class);
    module = execContext.getModel().getModule(reference.getModuleVersion().getNodePath());
    scmPlugin = module.getNodePlugin(ScmPlugin.class, null);

    this.referencePath.add(reference);
    indentHandle = null;
    indTempDynamicVersionCreated = false;

    // We use a try-finally construct to ensure that the current ModuleVersion always
    // gets removed for the current ReferencePath, and that the
    // UserInteractionCallback IndentHandle gets closed.
    try {
      Release.logger.info("Visiting leaf ModuleVersion " + reference.getModuleVersion() + " of ReferencePath " + this.referencePath + '.');

      indentHandle = userInteractionCallbackPlugin.startIndent();

      // Usually startIndent is followed by provideInfo to provide an initial message
      // following the new indent. But the message here ("visiting leaf ModuleVersion")
      // would be often not useful if no particular action is performed. We therefore
      // simply start the indent and wait for the first useful information, if any.

      //TODO: Probably should remove altogether, including message in bundle. Redundant.
      //userInteractionCallbackPlugin.provideInfo(MessageFormat.format(RootModuleVersionJobAbstractImpl.resourceBundle.getString(RootModuleVersionJobAbstractImpl.MSG_PATTERN_KEY_VISITING_LEAF_MODULE_VERSION), this.referencePath, reference.getModuleVersion()));

      // As an optimization we first verify if a Version was already selected for the
      // ModuleVersion during the execution of the job. If so, we must reuse it. It is
      // not essential to perform this verification here since it would be naturally
      // performed later. But it avoids useless processing and more importantly it is
      // less confusing for the user.
      if (this.handleAlreadyCreatedStaticVersion(reference.getModuleVersion(), byReferenceVersion)) {
        return true;
      }

      // If we are not in release mode, we must look for matching ModuleVersion's.
      if (!indRelease) {
        // If the current ModuleVersion matches, we switch to release mode and continue.
        if (this.getReferencePathMatcher().matches(this.referencePath)) {
          userInteractionCallbackPlugin.provideInfo(MessageFormat.format(RootModuleVersionJobAbstractImpl.resourceBundle.getString(RootModuleVersionJobAbstractImpl.MSG_PATTERN_KEY_VISITING_LEAF_REFERENCE_MATCHED), this.referencePath, this.referencePath.getLeafModuleVersion()));
          indRelease = true;
        }
      }

      property = runtimePropertiesPlugin.getProperty(module, Release.RUNTIME_PROPERTY_RELEASE_ISOLATION_MODE);

      if (property == null) {
        releaseIsolationMode = ReleaseIsolationMode.NONE;
      } else {
        releaseIsolationMode = ReleaseIsolationMode.valueOf(property);
      }

      // Here we handle the references. If we are in release mode, we must handle them
      // since references must be released before the main ModuleVersion can. If we are
      // not in release mode, we want to iterate among references only if there can be
      // a match. In both cases, the iteration logic is similar which is why we factor
      // it out.
      if (indRelease || this.getReferencePathMatcher().canMatchChildren(this.referencePath)) {
        boolean indUserWorkspaceDir;
        ReferenceManagerPlugin referenceManagerPlugin = null;
        List<Reference> listReference;
        boolean indReferenceUpdated;
        boolean indAbort;
        ByReference<Reference> byReferenceReference;

        // Here we need to have access to the sources of the module so that we can obtain
        // the list of references and iterate over them. If the user already has the
        // correct version of the module checked out, we need to use it. If not, we need
        // an internal working directory which we will not modify (for now).
        // ScmPlugin.checkoutSystem does that.
        pathModuleWorkspace = scmPlugin.checkoutSystem(reference.getModuleVersion().getVersion());

        if (!scmPlugin.isSync(pathModuleWorkspace, ScmPlugin.IsSyncFlag.ALL_CHANGES)) {
          throw new RuntimeExceptionUserError(MessageFormat.format(Util.getLocalizedMsgPattern(Util.MSG_PATTERN_KEY_WORKSPACE_DIRECTORY_NOT_SYNC), pathModuleWorkspace));
        }

        indUserWorkspaceDir = workspacePlugin.getWorkspaceDirFromPath(pathModuleWorkspace) instanceof WorkspaceDirUserModuleVersion;

        if (indUserWorkspaceDir) {
          userInteractionCallbackPlugin.provideInfo(MessageFormat.format(Release.resourceBundle.getString(Release.MSG_PATTERN_KEY_MODULE_VERSION_CHECKED_OUT_IN_USER_WORKSPACE_DIRECTORY), reference.getModuleVersion(), pathModuleWorkspace));
        }

        if (!module.isNodePluginExists(ReferenceManagerPlugin.class, null)) {
          listReference = Collections.emptyList();
        } else {
          referenceManagerPlugin = module.getNodePlugin(ReferenceManagerPlugin.class, null);
          listReference = referenceManagerPlugin.getListReference(pathModuleWorkspace);
        }

        // We want to perform a single commit for all reference updates, but only if at
        // least one such update is performed.
        indReferenceUpdated = false;

        indAbort = false;
        byReferenceReference = new ByReference<Reference>();

        for (Reference referenceChild: listReference) {
          ByReference<Version> byReferenceVersionChild;
          boolean indVersionChanged;

          if (referenceChild.getModuleVersion() == null) {
            if (referenceChild.getArtifactVersion().getVersionType() == VersionType.DYNAMIC) {
              //TODO: Maybe handle that (simply ask the user for new static version to use).
              throw new RuntimeExceptionUserError(MessageFormat.format(Release.resourceBundle.getString(Release.MSG_PATTERN_KEY_REFERENCE_DYNAMIC_VERSION_EXTERNAL_MODULE), this.referencePath, referenceChild));
            }

            // Appropriate message already written by ReferenceManagerPlugin.getListReference.
            continue;
          }

          if (referenceChild.getModuleVersion().getVersion().getVersionType() == VersionType.STATIC) {
            continue;
          }

          Release.logger.info("Processing reference " + referenceChild + " within ReferencePath " + this.referencePath + '.');

          byReferenceVersionChild = new ByReference<Version>();

          try {
            indVersionChanged = this.visitModuleVersionInternal(referenceChild, byReferenceVersionChild, indRelease);
          } catch (RuntimeExceptionAbort rea) {
            throw rea;
          } catch (RuntimeException re) {
            if (indRelease) {
              Release.logger.error("Exception thrown while visiting " + referenceChild + " during the release process. Winding back the call stack to abort the release of the initial matching ModuleVersion.");

              // We rethrow the exception until it is caught while not performing the actual
              // release so that the matched ModuleVersion can be safely skipped.
              throw re;
            } else {
              Release.logger.error("Exception thrown while visiting " + referenceChild + '.', re);

              if (Util.handleToolExitStatusAndContinueForExceptionalCond(module, Util.EXCEPTIONAL_COND_EXCEPTION_THROWN_WHILE_VISITING)) {
                userInteractionCallbackPlugin.provideInfo(MessageFormat.format(Util.getLocalizedMsgPattern(Util.MSG_PATTERN_KEY_EXCEPTION_THROWN_WHILE_VISITING), referenceChild));
                continue;
              } else {
                throw new RuntimeExceptionAbort(MessageFormat.format(Util.getLocalizedMsgPattern(Util.MSG_PATTERN_KEY_EXCEPTION_THROWN_WHILE_VISITING), referenceChild));
              }
            }
          }


          if (indRelease) {
            if (!indVersionChanged) {
              // If in release mode and the Version of the reference was not changed, it means
              // the user refused to perform the release on some children and asked to abort
              // (since the parent cannot be released anyways). We could go to the next matching
              // ModuleVersion, but we don't.
              // We do not return false immediately here since some references may have been
              // updated and we want to give the user the opportunity to commit these changes,
              // even if no static Version will be created.
              indAbort = true;
              break;
            }
          } else {
            // If we are not in release, the fact that the version of the reference was not
            // changed by itself does not imply aborting since it can mean it was not matched.
            // But following the visit of the reference, the user may have requested to abort,
            // in which case we must comply. When this happens, we do not expect the Version
            // of the reference to have been changed (!indVersionChanged).
            if (Util.isAbort()) {
              // We do not return false immediately here since some references may have been
              // updated and we want to give the user the opportunity to commit these changes,
              // even if no static Version will be created.
              indAbort = true;
              break;
            }
          }

          // indVersionChanged can be true only if a static Version was created for the
          // reference child that was just visited. In such as case, we must update the
          // Version within the parent, which is necessarily dynamic.
          if (indVersionChanged) {
            String message;

            userInteractionCallbackPlugin.provideInfo(MessageFormat.format(Release.resourceBundle.getString(Release.MSG_PATTERN_KEY_PARENT_WILL_BE_UPDATED_BECAUSE_REFERENCE_CHANGED), this.referencePath, referenceChild, byReferenceVersionChild.object));

            if (!Util.handleDoYouWantToContinue(Util.DO_YOU_WANT_TO_CONTINUE_CONTEXT_UPDATE_REFERENCE)) {
              // We do not return false immediately here since some references may have been
              // updated and we want to give the user the opportunity to commit these changes,
              // even if no static Version will be created.
              indAbort = true;
              break;
            }

            if (referenceManagerPlugin.updateReferenceVersion(pathModuleWorkspace, referenceChild, byReferenceVersionChild.object, byReferenceReference)) {
              message = MessageFormat.format(Release.resourceBundle.getString(Release.MSG_PATTERN_KEY_CHANGE_REFERENCE_VERSION), this.referencePath, referenceChild, byReferenceVersionChild.object, byReferenceReference);
              userInteractionCallbackPlugin.provideInfo(message);
              this.listActionsPerformed.add(message);
              indReferenceUpdated = true;
            } else {
              userInteractionCallbackPlugin.provideInfo(MessageFormat.format(Release.resourceBundle.getString(Release.MSG_PATTERN_KEY_CHANGE_REFERENCE_VERSION_NO_ARTIFACT_VERSION_CHANGE), this.referencePath, referenceChild, byReferenceVersionChild.object));
            }
          }
        }

        if (indReferenceUpdated) {
          String message;
          Map<String, String> mapCommitAttr;

          // If the user aborted, we kindly ask before committing the changes.
          if (indAbort) {
            userInteractionCallbackPlugin.provideInfo(MessageFormat.format(Release.resourceBundle.getString(Release.MSG_PATTERN_KEY_COMMIT_REFERENCE_CHANGE_AFTER_ABORT), this.referencePath));

            if (!Util.handleDoYouWantToContinue(Util.DO_YOU_WANT_TO_CONTINUE_CONTEXT_COMMIT_REFERENCE_CHANGE_AFTER_ABORT)) {
              return false;
            }
          }

          if (releaseIsolationMode == ReleaseIsolationMode.USE_TEMP_DYNAMIC_VERSION) {
            userInteractionCallbackPlugin.provideInfo(MessageFormat.format(Release.resourceBundle.getString(Release.MSG_PATTERN_KEY_TEMP_DYNAMIC_VERSION_CREATED), this.referencePath));
            scmPlugin.createTempDynamicVersion(pathModuleWorkspace);
            indTempDynamicVersionCreated = true;
          }

          message = MessageFormat.format(Release.resourceBundle.getString(Release.MSG_PATTERN_KEY_REFERENCES_UPDATED), this.referencePath);
          mapCommitAttr = new HashMap<String, String>();
          mapCommitAttr.put(ScmPlugin.COMMIT_ATTR_REFERENCE_VERSION_CHANGE, "true");
          scmPlugin.commit(pathModuleWorkspace, message, mapCommitAttr);
          userInteractionCallbackPlugin.provideInfo(message);
          this.listActionsPerformed.add(message);

          if (indUserWorkspaceDir) {
            message = MessageFormat.format(Util.getLocalizedMsgPattern(Util.MSG_PATTERN_KEY_PREVIOUS_CHANGE_COMMITTED_SCM), pathModuleWorkspace);
            userInteractionCallbackPlugin.provideInfo(message);
            this.listActionsPerformed.add(message);
          } else {
            Release.logger.info("The previous changes were performed in " + pathModuleWorkspace + " and were committed to the SCM.");
          }
        }

        if (indAbort) {
          return false;
        }
      }

      // If we are in release mode, we must now release the ModuleVersion itself.
      if (indRelease) {
        boolean indVersionChanged;

        // processSelectStaticVersion continues the work started by this method in the
        // same module workspace path, which does not need to be released.
        // This is so that if a temporary dynamic Version has been created, processing can
        // continue in that context.
        indVersionChanged = this.processSelectStaticVersion(pathModuleWorkspace, reference.getModuleVersion(), byReferenceVersion, releaseIsolationMode, indTempDynamicVersionCreated);

        // processSelectStaticVersion releases the temporary dynamic Version when it
        // completes the version change. Otherwise, we must take care of it since we
        // created the temporary dynamic Version here.
        if (indVersionChanged) {
          indTempDynamicVersionCreated = false;
        }

        return indVersionChanged;

        // The version of the module was changed but we do not need to update the
        // ReferencePath since we are anyway exiting from this module and removing it
        // from the ReferencePath.
      }
    } finally {
      if (indTempDynamicVersionCreated) {
        scmPlugin.releaseTempDynamicVersion(pathModuleWorkspace);
      }

      if (pathModuleWorkspace != null) {
        workspacePlugin.releaseWorkspaceDir(pathModuleWorkspace);
      }

      this.referencePath.removeLeafReference();

      if (indentHandle != null) {
        indentHandle.close();
      }
    }

    return false;
  }

  /**
   * Processes the creation of a static version for a given ModuleVersion.
   *
   * The ModuleVersion must be dynamic.
   *
   * The user may be required to confirm and may refuse. In that case false is
   * returned and the whole process is aborted since every creation need to be
   * performed.
   *
   * @param pathModuleWorkspace Path to the module in the workspace.
   * @param moduleVersion ModuleVersion for which to select a static version.
   * @param byReferenceVersion If a static version was created, the new version is
   *   recorded in this object.
   * @param releaseIsolationMode ReleaseIsolationMode as obtained by caller.
   * @param indTempDynamicVersionCreated Indicates if the temporary dynamic Version
   *   has already been created by the caller. Applies only if releaseIsolationMode
   *   is {@link ReleaseIsolationMode#USE_TEMP_DYNAMIC_VERSION}.
   * @return Indicates if a version was created, or if the user cancelled or the
   *   build failed.
   */
  private boolean processSelectStaticVersion(Path pathModuleWorkspace, ModuleVersion moduleVersion, ByReference<Version> byReferenceVersion, ReleaseIsolationMode releaseIsolationMode, boolean indTempDynamicVersionCreated) {
    ExecContext execContext;
    UserInteractionCallbackPlugin userInteractionCallbackPlugin;
    UserInteractionCallbackPlugin.IndentHandle indentHandle;
    boolean indTempDynamicVersionCreatedLocally;
    RuntimePropertiesPlugin runtimePropertiesPlugin;
    Version versionStaticSelected;
    Module module;
    ScmPlugin scmPlugin;
    SelectStaticVersionPlugin selectStaticVersionPlugin;
    WorkspacePlugin workspacePlugin;
    WorkspaceDirUserModuleVersion workspaceDirUserModuleVersion;
    boolean indUserWorkspaceDir;
    ArtifactVersion artifactVersion = null;
    ArtifactVersion artifactVersionPrevious = null;
    ArtifactVersionManagerPlugin artifactVersionManagerPlugin = null;
    ArtifactVersionMapperPlugin artifactVersionMapperPlugin = null;
    boolean indCommitRequired;
    String projectCode;
    Map<String, String> mapVersionAttr;
    String message = null;

    execContext = ExecContextHolder.get();
    userInteractionCallbackPlugin = execContext.getExecContextPlugin(UserInteractionCallbackPlugin.class);
    runtimePropertiesPlugin = execContext.getExecContextPlugin(RuntimePropertiesPlugin.class);

    Release.logger.info("Processing request to select a static version for ModuleVersion " + moduleVersion + '.');

    // We do not need to handle the case where a static Version was already selected
    // for the ModuleVersion since this was taken care of by visitModuleForRelease.

    module = execContext.getModel().getModule(moduleVersion.getNodePath());
    scmPlugin = module.getNodePlugin(ScmPlugin.class, null);
    selectStaticVersionPlugin = module.getNodePlugin(SelectStaticVersionPlugin.class, null);

    versionStaticSelected = selectStaticVersionPlugin.selectStaticVersion(moduleVersion.getVersion());

    // Generally, null being returned will be accompanied by a request for abort. This
    // will be handled by the caller.
    if (versionStaticSelected == null) {
      return false;
    }

    if (scmPlugin.isVersionExists(versionStaticSelected)) {
      userInteractionCallbackPlugin.provideInfo(MessageFormat.format(Release.resourceBundle.getString(Release.MSG_PATTERN_KEY_EXISTING_STATIC_VERSION_SELECTED), moduleVersion, versionStaticSelected));
      this.mapModuleVersionStatic.put(moduleVersion, versionStaticSelected);
      byReferenceVersion.object = versionStaticSelected;
      return true;
    }

    // Here versionStaticSelected holds the new version to create (it does not exist).

    workspacePlugin = execContext.getExecContextPlugin(WorkspacePlugin.class);
    workspaceDirUserModuleVersion = new WorkspaceDirUserModuleVersion(moduleVersion);
    indUserWorkspaceDir = workspacePlugin.isWorkspaceDirExist(workspaceDirUserModuleVersion);

    indentHandle = null;
    indTempDynamicVersionCreatedLocally = false;

    try {
      indentHandle = userInteractionCallbackPlugin.startIndent();
      userInteractionCallbackPlugin.provideInfo(MessageFormat.format(Release.resourceBundle.getString(Release.MSG_PATTERN_KEY_CREATING_NEW_STATIC_VERSION), moduleVersion, versionStaticSelected));

      if (!Util.handleDoYouWantToContinue(Release.DO_YOU_WANT_TO_CONTINUE_CONTEXT_CREATE_STATIC_VERSION)) {
        return false;
      }

      indCommitRequired = false;

      if (!module.isNodePluginExists(ArtifactVersionManagerPlugin.class, null)) {
        Release.logger.info("The module " + module + " does not expose the ArtifactVersionManagerPlugin plugin which implies that it does not manage artifact version and that there is no need to update it.");
      } else {
        artifactVersionManagerPlugin = module.getNodePlugin(ArtifactVersionManagerPlugin.class, null);

        // If a new static version is to be created, we need to temporarily update the
        // ArtifactVersion to reflect it. After creating the new version we may need to
        // revert back (see below).

        artifactVersionMapperPlugin = module.getNodePlugin(ArtifactVersionMapperPlugin.class, null);
        artifactVersion = artifactVersionMapperPlugin.mapVersionToArtifactVersion(versionStaticSelected);

        artifactVersionPrevious = artifactVersionManagerPlugin.getArtifactVersion(pathModuleWorkspace);
        indCommitRequired = artifactVersionManagerPlugin.setArtifactVersion(pathModuleWorkspace, artifactVersion);

        // If ArtifactVersionManagerPlugin.setArtifactVersion returned true, we need to
        // commit the change. But we want to do so only after we ensured that building the
        // Module is successful.

        if (indCommitRequired) {
          message = MessageFormat.format(Release.resourceBundle.getString(Release.MSG_PATTERN_KEY_ARTIFACT_VERSION_CHANGED), moduleVersion.getNodePath(), versionStaticSelected, artifactVersionPrevious, artifactVersion);
          userInteractionCallbackPlugin.provideInfo(message);
        } else {
          userInteractionCallbackPlugin.provideInfo(MessageFormat.format(Release.resourceBundle.getString(Release.MSG_PATTERN_KEY_NO_ARTIFACT_VERSION_CHANGE), moduleVersion, artifactVersion, versionStaticSelected));
        }
      }

      // Before creating the Version, ensure that building the Module is successful.

      if (   !Util.isNotNullAndTrue(runtimePropertiesPlugin.getProperty(module, Release.RUNTIME_PROPERTY_IND_NO_PRE_RELEASE_VERSION_VALIDATION_BUILD))
          && module.isNodePluginExists(BuilderPlugin.class, null)) {

        BuilderPlugin builderPlugin;
        String buildContext;
        boolean indBuildSuccessful;

        builderPlugin = module.getNodePlugin(BuilderPlugin.class,  null);
        buildContext = runtimePropertiesPlugin.getProperty(module, Release.RUNTIME_PROPERTY_RELEASE_VERSION_BUILD_CONTEXT);

        try (Writer writerLog = userInteractionCallbackPlugin.provideInfoWithWriter(MessageFormat.format(Release.resourceBundle.getString(Release.MSG_PATTERN_KEY_INITIATING_BUILD), moduleVersion, pathModuleWorkspace, versionStaticSelected))) {
          indBuildSuccessful = builderPlugin.build(pathModuleWorkspace, buildContext, writerLog);
        } catch (IOException ioe) {
          throw new RuntimeException(ioe);
        }

        if (!indBuildSuccessful) {
          userInteractionCallbackPlugin.provideInfo(MessageFormat.format(Release.resourceBundle.getString(Release.MSG_PATTERN_KEY_BUILD_FAILED), moduleVersion, pathModuleWorkspace, versionStaticSelected));

          // If the build failed and the ArtifactVersion was changed (as indicated by
          // indCommitRequired), we revert it so that if the user attempts again there will
          // not be any unsynchronized changes.
          if (indCommitRequired) {
            artifactVersionManagerPlugin.setArtifactVersion(pathModuleWorkspace, artifactVersionPrevious);
          }

          return false;
        }
      }

      if (indCommitRequired) {
        Map<String, String> mapCommitAttr;

        if (releaseIsolationMode == ReleaseIsolationMode.USE_TEMP_DYNAMIC_VERSION && !indTempDynamicVersionCreated) {
          userInteractionCallbackPlugin.provideInfo(MessageFormat.format(Release.resourceBundle.getString(Release.MSG_PATTERN_KEY_TEMP_DYNAMIC_VERSION_CREATED), this.referencePath));
          scmPlugin.createTempDynamicVersion(pathModuleWorkspace);
          indTempDynamicVersionCreatedLocally = true;
        }

        // message was initialized above.

        mapCommitAttr = new HashMap<String, String>();
        mapCommitAttr.put(ScmPlugin.COMMIT_ATTR_VERSION_CHANGE, "true");
        scmPlugin.commit(pathModuleWorkspace, message, mapCommitAttr);
        this.listActionsPerformed.add(message);

        if (indUserWorkspaceDir) {
          message = MessageFormat.format(Util.getLocalizedMsgPattern(Util.MSG_PATTERN_KEY_PREVIOUS_CHANGE_COMMITTED_SCM), pathModuleWorkspace);
          userInteractionCallbackPlugin.provideInfo(message);
          this.listActionsPerformed.add(message);
        } else {
          Release.logger.info("The previous changes were performed in " + pathModuleWorkspace + " and were committed to the SCM.");
        }
      }

      // Perform the actual creation of the version.

      projectCode = this.getProjectCode();

      if (projectCode != null) {
        mapVersionAttr = new HashMap<String, String>();
        mapVersionAttr.put(ScmPlugin.VERSION_ATTR_PROJECT_CODE, projectCode);
      } else {
        mapVersionAttr = null;
      }

      // If a temporary dynamic Version was created, specifying to not switch switches
      // back to the original base Version, which is what we want here. We do not want
      // to switch to the newly created static Version, and we want to do as if the
      // temporary dynamic Version never existed.
      scmPlugin.createVersion(pathModuleWorkspace, versionStaticSelected, mapVersionAttr, false);
      indTempDynamicVersionCreatedLocally = false;
      message = MessageFormat.format(Release.resourceBundle.getString(Release.MSG_PATTERN_KEY_STATIC_VERSION_CREATED), moduleVersion, versionStaticSelected);
      userInteractionCallbackPlugin.provideInfo(message);
      this.listActionsPerformed.add(message);

      if ((artifactVersionManagerPlugin != null) && (releaseIsolationMode == ReleaseIsolationMode.REVERT_ARTIFACT_VERSION_ASK) || (releaseIsolationMode == ReleaseIsolationMode.REVERT_ARTIFACT_VERSION)) {
        AlwaysNeverYesNoAskUserResponse alwaysNeverYesNoAskUserResponsRevertArtifactVersion = null;
        Map<String, String> mapCommitAttr;

        // Finally handle reverting the ArtifactVersion. Note that in some cases the
        // ArtifactVersion does not need to be updated above if it is already the correct
        // value. This can happen in phase development (see
        // PhaseSelectDynamicVersionPluginImpl). We may be tempted to not attempt to
        // revert the ArtifactVersion in that case arguing that if it was not adjusted
        // prior to creating the new static Version, it will not need to be reverted. But
        // the ArtifactVersionMapperPlugin can behave in a non-deterministic manner where
        // the target ArtifactVersion depends on runtime properties. Again, this can
        // happen in phase development. We therefore handle reverting regardless of
        // whether or not the ArtifactVersion was adjusted above.

        artifactVersion = artifactVersionMapperPlugin.mapVersionToArtifactVersion(moduleVersion.getVersion());

        if (!artifactVersion.equals(artifactVersionManagerPlugin.getArtifactVersion(pathModuleWorkspace))) {
          if (releaseIsolationMode == ReleaseIsolationMode.REVERT_ARTIFACT_VERSION_ASK) {
            alwaysNeverYesNoAskUserResponsRevertArtifactVersion = Util.getInfoAlwaysNeverYesNoAskUserResponseAndHandleAsk(
                runtimePropertiesPlugin,
                Release.RUNTIME_PROPERTY_REVERT_ARTIFACT_VERSION,
                userInteractionCallbackPlugin,
                MessageFormat.format(Release.resourceBundle.getString(Release.MSG_PATTERN_KEY_DO_YOU_WANT_TO_REVERT_ARTIFACT_VERSION), moduleVersion, artifactVersion, versionStaticSelected));
          }

          if ((releaseIsolationMode == ReleaseIsolationMode.REVERT_ARTIFACT_VERSION) || alwaysNeverYesNoAskUserResponsRevertArtifactVersion.isYes()) {
            // Here we do not check if the version was actually changed since we made the
            // verification above.
            artifactVersionManagerPlugin.setArtifactVersion(pathModuleWorkspace, artifactVersion);

            // The commit we are about to perform needs to have a special marker so that the
            // dynamic Version is considered equivalent to the static Version that was just
            // created.

            message = MessageFormat.format(Release.resourceBundle.getString(Release.MSG_PATTERN_KEY_ARTIFACT_VERSION_REVERTED), moduleVersion, artifactVersion, versionStaticSelected);
            mapCommitAttr = new HashMap<String, String>();
            mapCommitAttr.put(ScmPlugin.COMMIT_ATTR_EQUIVALENT_STATIC_VERSION, versionStaticSelected.toString());
            mapCommitAttr.put(ScmPlugin.COMMIT_ATTR_VERSION_CHANGE, "true");
            scmPlugin.commit(pathModuleWorkspace, message, mapCommitAttr);
            userInteractionCallbackPlugin.provideInfo(message);
            this.listActionsPerformed.add(message);

            if (indUserWorkspaceDir) {
              message = MessageFormat.format(Util.getLocalizedMsgPattern(Util.MSG_PATTERN_KEY_PREVIOUS_CHANGE_COMMITTED_SCM), pathModuleWorkspace);
              userInteractionCallbackPlugin.provideInfo(message);
              this.listActionsPerformed.add(message);
            } else {
              Release.logger.info("The previous changes were performed in " + pathModuleWorkspace + " and were committed to the SCM.");
            }
          }
        } else {
          userInteractionCallbackPlugin.provideInfo(MessageFormat.format(Release.resourceBundle.getString(Release.MSG_PATTERN_KEY_NO_REVERTED_ARTIFACT_VERSION_CHANGE), moduleVersion, artifactVersion, versionStaticSelected));
        }
      }
    } finally {
      if (indTempDynamicVersionCreatedLocally) {
        scmPlugin.releaseTempDynamicVersion(pathModuleWorkspace);
      }

      if (indentHandle != null) {
        indentHandle.close();
      }
    }

    this.mapModuleVersionStatic.put(moduleVersion, versionStaticSelected);
    byReferenceVersion.object = versionStaticSelected;

    return true;
  }

  /**
   * There are multiple occurrences above where we check if a static Version was
   * already created during the same job execution for a given ModuleVersion. This
   * method factors this out.
   *
   * @param moduleVersion ModuleVersion.
   * @param byReferenceVersion If a static version was already selected for the
   *   ModuleVersion, the version is recorded in this object.
   * @return Indicates if a version was already selected for the ModuleVersion.
   */
  private boolean handleAlreadyCreatedStaticVersion(ModuleVersion moduleVersion, ByReference<Version> byReferenceVersion) {
    Version versionStaticSelected;

    versionStaticSelected = this.mapModuleVersionStatic.get(moduleVersion);

    if (versionStaticSelected != null) {
      byReferenceVersion.object = versionStaticSelected;
      ExecContextHolder.get().getExecContextPlugin(UserInteractionCallbackPlugin.class).provideInfo(MessageFormat.format(Release.resourceBundle.getString(Release.MSG_PATTERN_KEY_REUSING_ALREADY_SELECTED_STATIC_VERSION), moduleVersion, byReferenceVersion.object));
      return true;
    } else {
      return false;
    }
  }

}
