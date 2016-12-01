/*
 * Copyright 2015 - 2017 AZYVA INC.
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

import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Formatter;
import java.util.List;
import java.util.ResourceBundle;

import org.azyva.dragom.execcontext.plugin.RuntimePropertiesPlugin;
import org.azyva.dragom.execcontext.plugin.UserInteractionCallbackPlugin;
import org.azyva.dragom.execcontext.plugin.WorkspacePlugin;
import org.azyva.dragom.execcontext.support.ExecContextHolder;
import org.azyva.dragom.job.SwitchToDynamicVersion;
import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.ModuleVersion;
import org.azyva.dragom.model.Version;
import org.azyva.dragom.model.VersionType;
import org.azyva.dragom.model.plugin.ReferenceManagerPlugin;
import org.azyva.dragom.model.plugin.ScmPlugin;
import org.azyva.dragom.model.plugin.SelectStaticVersionPlugin;
import org.azyva.dragom.model.plugin.VersionClassifierPlugin;
import org.azyva.dragom.reference.Reference;
import org.azyva.dragom.util.AlwaysNeverAskUserResponse;
import org.azyva.dragom.util.Util;
import org.azyva.dragom.util.YesAlwaysNoUserResponse;

/**
 * Base class for SelectStaticVersionPlugin that provide useful helper methods that
 * are common to many such plugins.
 *
 * @author David Raymond
 */
public abstract class SelectStaticVersionPluginBaseImpl extends ModulePluginAbstractImpl implements SelectStaticVersionPlugin {
  private static final String RUNTIME_PROPERTY_SPECIFIC_STATIC_VERSION = "SPECIFIC_STATIC_VERSION";
  private static final String RUNTIME_PROPERTY_CAN_REUSE_EXISTING_EQUIVALENT_STATIC_VERSION = "CAN_REUSE_EXISTING_EQUIVALENT_STATIC_VERSION";
  private static final String RUNTIME_PROPERTY_SPECIFIC_STATIC_VERSION_PREFIX = "SPECIFIC_STATIC_VERSION_PREFIX";

  /**
   * Runtime property specifying the initial revision to use for the first static
   * Version created with a given prefix.
   * <p>
   * Also defines the lower bound for new revisions. For example, if the current
   * most recent static Version is S/v-1.123 and this runtime property specifies 1000,
   * the next static Version would be S/v-1.1000 (if the prefix is S/v-1.).
   */
  private static final String RUNTIME_PROPERTY_INITIAL_REVISION = "INITIAL_REVISION";

  private static final String RUNTIME_PROPERTY_REVISION_DECIMAL_POSITION_COUNT = "REVISION_DECIMAL_POSITION_COUNT";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_STATIC_VERSION_SPECIFIED = "STATIC_VERSION_SPECIFIED";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_EQUIVALENT_STATIC_VERSION_AUTOMATICALLY_REUSED = "EQUIVALENT_STATIC_VERSION_AUTOMATICALLY_REUSED";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_REUSE_EQUIVALENT_STATIC_VERSION = "REUSE_EQUIVALENT_STATIC_VERSION";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_AUTOMATICALLY_REUSE_EQUIVALENT_STATIC_VERSION = "AUTOMATICALLY_REUSE_EQUIVALENT_STATIC_VERSION";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_NEW_STATIC_VERSION_PREFIX_SPECIFIED = "NEW_STATIC_VERSION_PREFIX_SPECIFIED";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_EXISTING_EQUIVALENT_STATIC_VERSION_EXCLUDE_VESION_CHANGING_COMMITS = "EXISTING_EQUIVALENT_STATIC_VERSION_EXCLUDE_VESION_CHANGING_COMMITS";

  /**
   * ResourceBundle specific to this class.
   */
  private static final ResourceBundle resourceBundle = ResourceBundle.getBundle(SelectStaticVersionPluginBaseImpl.class.getName() + "ResourceBundle");

  private int defaultInitialRevision;
  private int defaultRevisionDecimalPositionCount;

  public SelectStaticVersionPluginBaseImpl(Module module) {
    super(module);
  }

  protected void setDefaultInitialRevision(int defaultInitialRevision) {
    this.defaultInitialRevision = defaultInitialRevision;
  }

  protected void setDefaultRevisionDecimalPositionCount(int defaultRevisionDecimalPositionCount) {
    this.defaultRevisionDecimalPositionCount = defaultRevisionDecimalPositionCount;
  }

  /**
   * Validates that a Version is dynamic.
   *
   * @param versionDynamic Version that is supposed to be dynamic.
   */
  protected void validateVersionDynamic(Version versionDynamic) {
    if (versionDynamic.getVersionType() != VersionType.DYNAMIC) {
      // This should not happen since this method is not supposed to be called on static
      // version. It is the responsibility of the caller to validate user parameters and to
      // invoke only on dynamic versions.
      throw new RuntimeException("Version " + versionDynamic + " of module " + this.getModule() + " is not dynamic.");
    }
  }

  /**
   * Handles the case where a specific static version is specified for the module.
   *
   * @param versionDynamic Dynamic version.
   * @return Specific static Version. null if none specified.
   */
  protected Version handleSpecificStaticVersion(Version versionDynamic) {
    RuntimePropertiesPlugin runtimePropertiesPlugin;
    UserInteractionCallbackPlugin userInteractionCallbackPlugin;
    String stringSpecificStaticVersion;
    Version versionNewStatic;

    runtimePropertiesPlugin = ExecContextHolder.get().getExecContextPlugin(RuntimePropertiesPlugin.class);
    userInteractionCallbackPlugin = ExecContextHolder.get().getExecContextPlugin(UserInteractionCallbackPlugin.class);

    if (versionDynamic.getVersionType() != VersionType.DYNAMIC) {
      // This should not happen since this method is not supposed to be called on static
      // version. It is the responsibility of the caller to validate user parameters and to
      // invoke only on dynamic versions.
      throw new RuntimeException("Version " + versionDynamic + " of module " + this.getModule() + " is not dynamic.");
    }

    stringSpecificStaticVersion = runtimePropertiesPlugin.getProperty(this.getModule(), SelectStaticVersionPluginBaseImpl.RUNTIME_PROPERTY_SPECIFIC_STATIC_VERSION);

    if (stringSpecificStaticVersion != null) {
      versionNewStatic = new Version(stringSpecificStaticVersion);

      if (versionNewStatic.getVersionType() != VersionType.STATIC) {
        throw new RuntimeException("Version " + versionNewStatic + " must be static.");
      }

      userInteractionCallbackPlugin.provideInfo(MessageFormat.format(SelectStaticVersionPluginBaseImpl.resourceBundle.getString(SelectStaticVersionPluginBaseImpl.MSG_PATTERN_KEY_STATIC_VERSION_SPECIFIED), new ModuleVersion(this.getModule().getNodePath(), versionDynamic), versionNewStatic));
      return versionNewStatic;
    } else {
      return null;
    }
  }

  /**
   * Handles the case where an existing equivalent static Version exists that is
   * equivalent to the dynamic Version.
   * <p>
   * When getting a new static version for the current dynamic version, it may be
   * pertinent to verify if there is already a static Version corresponding to the
   * current dynamic Version. This is important since if the process is restarted by
   * the user after having been aborted after some Version creations have been
   * performed, we need to make it as if we were continuing from where we left off,
   * and reusing existing static Version that may have been created during the
   * previous execution does the trick.
   * <p>
   * Also, even ignoring the case where the process is restarted, if the user ran
   * this job once, used {@link SwitchToDynamicVersion} with the intent of
   * performing additional changes and runs the process again without having
   * actually performed any work, it is expected that the static Version that was
   * created in the first execution gets reused.
   *
   * @param versionDynamic Dynamic Version.
   * @return Existing equivalent static Version. null if none.
   */
  protected Version handleExistingEquivalentStaticVersion(Version versionDynamic) {
    RuntimePropertiesPlugin runtimePropertiesPlugin;
    UserInteractionCallbackPlugin userInteractionCallbackPlugin;
    AlwaysNeverAskUserResponse alwaysNeverAskUserResponseCanReuseExistingEquivalentStaticVersion;
    YesAlwaysNoUserResponse yesAlwaysNoUserResponse;

    runtimePropertiesPlugin = ExecContextHolder.get().getExecContextPlugin(RuntimePropertiesPlugin.class);
    userInteractionCallbackPlugin = ExecContextHolder.get().getExecContextPlugin(UserInteractionCallbackPlugin.class);

    alwaysNeverAskUserResponseCanReuseExistingEquivalentStaticVersion = AlwaysNeverAskUserResponse.valueOfWithAskDefault(runtimePropertiesPlugin.getProperty(this.getModule(), SelectStaticVersionPluginBaseImpl.RUNTIME_PROPERTY_CAN_REUSE_EXISTING_EQUIVALENT_STATIC_VERSION));

    if (!alwaysNeverAskUserResponseCanReuseExistingEquivalentStaticVersion.isNever()) {
      Version versionExistingEquivalentStatic;

      versionExistingEquivalentStatic = this.getVersionExistingEquivalentStatic(versionDynamic);

      if (versionExistingEquivalentStatic != null) {
        if (alwaysNeverAskUserResponseCanReuseExistingEquivalentStaticVersion.isAlways()) {
          userInteractionCallbackPlugin.provideInfo(MessageFormat.format(SelectStaticVersionPluginBaseImpl.resourceBundle.getString(SelectStaticVersionPluginBaseImpl.MSG_PATTERN_KEY_EQUIVALENT_STATIC_VERSION_AUTOMATICALLY_REUSED), new ModuleVersion(this.getModule().getNodePath(), versionDynamic), versionExistingEquivalentStatic));
          return versionExistingEquivalentStatic;
        }

        // Here, alwaysNeverAskUserResponseCanReuseExistingVersion is necessarily ASK.

        yesAlwaysNoUserResponse =
            Util.getInfoYesNoUserResponse(
                userInteractionCallbackPlugin,
                MessageFormat.format(SelectStaticVersionPluginBaseImpl.resourceBundle.getString(SelectStaticVersionPluginBaseImpl.MSG_PATTERN_KEY_REUSE_EQUIVALENT_STATIC_VERSION), new ModuleVersion(this.getModule().getNodePath(), versionDynamic), versionExistingEquivalentStatic),
                YesAlwaysNoUserResponse.YES);

        // The result is not useful. We only want to adjust the runtime property which
        // will be reused the next time around.
        Util.getInfoAlwaysNeverAskUserResponseAndHandleAsk(
            runtimePropertiesPlugin,
            SelectStaticVersionPluginBaseImpl.RUNTIME_PROPERTY_CAN_REUSE_EXISTING_EQUIVALENT_STATIC_VERSION,
            userInteractionCallbackPlugin,
            SelectStaticVersionPluginBaseImpl.resourceBundle.getString(SelectStaticVersionPluginBaseImpl.MSG_PATTERN_KEY_AUTOMATICALLY_REUSE_EQUIVALENT_STATIC_VERSION));

        // This is the user response from the first question above, not about
        // automatically reusing the response.
        if (yesAlwaysNoUserResponse.isYes()) {
          return versionExistingEquivalentStatic;
        }
      }
    }

    return null;
  }

  /**
   * Handles the case where a specific static version prefix is specified for the
   * module.
   *
   * @param versionDynamic Dynamic Version.
   * @return Specific static Version prefix.
   */
  protected Version handleSpecificStaticVersionPrefix(Version versionDynamic) {
    RuntimePropertiesPlugin runtimePropertiesPlugin;
    String stringStaticVersionPrefix;
    UserInteractionCallbackPlugin userInteractionCallbackPlugin;

    runtimePropertiesPlugin = ExecContextHolder.get().getExecContextPlugin(RuntimePropertiesPlugin.class);
    userInteractionCallbackPlugin = ExecContextHolder.get().getExecContextPlugin(UserInteractionCallbackPlugin.class);

    stringStaticVersionPrefix = runtimePropertiesPlugin.getProperty(this.getModule(), SelectStaticVersionPluginBaseImpl.RUNTIME_PROPERTY_SPECIFIC_STATIC_VERSION_PREFIX);

    if (stringStaticVersionPrefix != null) {
      userInteractionCallbackPlugin.provideInfo(MessageFormat.format(SelectStaticVersionPluginBaseImpl.resourceBundle.getString(SelectStaticVersionPluginBaseImpl.MSG_PATTERN_KEY_NEW_STATIC_VERSION_PREFIX_SPECIFIED), new ModuleVersion(this.getModule().getNodePath(), versionDynamic), stringStaticVersionPrefix));
      return new Version(stringStaticVersionPrefix);
    }

    return null;
  }

  /**
   * Gets the existing static Version that is equivalent to a dynamic Version.
   *
   * @param versionDynamic Dynamic Version.
   * @return Equivalent static Version. null if there is no equivalent static
   *   Version.
   */
  private Version getVersionExistingEquivalentStatic(Version versionDynamic) {
    Module module;
    ScmPlugin scmPlugin;
    List<ScmPlugin.Commit> listCommit;
    String stringEquivalentStaticVersion;
    Version versionExistingEquivalentStatic;

    module = this.getModule();
    scmPlugin = module.getNodePlugin(ScmPlugin.class, null);

    // If there ia a temporary dynamic Version in effect which has the current Version
    // as its base, we conclude there is no equivalent static Version.
    // This is close to being a hack since the main reason for having added this
    // verification is that the algorithm for discovering an equivalent static
    // Version uses methods that are not allowed, or does not return the correct
    // result when a temporary dynamic Version is in effect, and instead of finding a
    // way to make the code work, we simply disable it.
    // But if we think about it, it is not that much of a hack since when a temporary
    // dynamic Version exists, and is kind of an extention to the base Version, it is
    // very unlikely to have an equivalent static Version, unless it has just been
    // created. But {@link Release} ensures that the temporary dynamic Version gets
    // created only when necessary, and that is when at least one new commit needs to
    // be introduced, negating the possibility of it having an equivalent static
    // Version.
    if (scmPlugin.isTempDynamicVersion(versionDynamic)) {
      return null;
    }

    // In most cases we need only the current commit.
    listCommit = scmPlugin.getListCommit(versionDynamic, new ScmPlugin.CommitPaging(1), EnumSet.of(ScmPlugin.GetListCommitFlag.IND_INCLUDE_MAP_ATTR, ScmPlugin.GetListCommitFlag.IND_INCLUDE_VERSION_STATIC));

    versionExistingEquivalentStatic = null;

    if (!listCommit.isEmpty()) {
      ScmPlugin.Commit commitCurrent;

      commitCurrent = listCommit.get(0);

      // In order to check for an existing static version corresponding to the current
      // dynamic version we consider the message of the last commit of the current
      // dynamic version which, if there is a corresponding static version, will contain
      // the special attribute equivalent-static-version at the beginning. This
      // attribute will have been included when creating the static Version to revert
      // the changes to the ArtifactVersion.
      // This is necessary since an existing static version will generally not correspond
      // to the last commit of a dynamic version because of that revert commit.
      stringEquivalentStaticVersion = commitCurrent.mapAttr.get(ScmPlugin.COMMIT_ATTR_EQUIVALENT_STATIC_VERSION);

      if (stringEquivalentStaticVersion != null) {
        versionExistingEquivalentStatic = new Version(stringEquivalentStaticVersion);

        if (versionExistingEquivalentStatic.getVersionType() != VersionType.STATIC) {
          throw new RuntimeException("Version " + versionExistingEquivalentStatic + " must be static.");
        }
      }

      // If the equivalent-static-version attribute is not specified for the current
      // commit, in most cases it means that other commits were performed between some
      // previous commit which do have this attribute and the current commit, and in
      // such cases there is no static Version that is equivalent to the current dynamic
      // Version. But in the exceptional case were these other intervening commits are
      // only Version-changing commits introduced by Dragom, and the list of references
      // in the current Version are identical to those of this potentially equivalent
      // static Version, we can conclude that the current dynamic Version is equivalent
      // to that static Version which was previously created. Performing this
      // verification is not trivial but is worth it to avoid uselessly creating static
      // Version's.
      if (versionExistingEquivalentStatic == null) {
        // For this part of the algorithm we need more commits.
        // We use the nice binary magic number 16 here as the maximum number of commits to
        // return. We do not want to leave it unbounded, and it does not seem worth paging
        // the results since past a few commits, it is unlikely
        listCommit = scmPlugin.getListCommit(versionDynamic, new ScmPlugin.CommitPaging(16), EnumSet.of(ScmPlugin.GetListCommitFlag.IND_INCLUDE_MAP_ATTR, ScmPlugin.GetListCommitFlag.IND_INCLUDE_VERSION_STATIC));

        // We have handled the current commit above, but we need to include it in the loop
        // below since if that commit is a regular commit, there is no existing equivalent
        // static version
        for (ScmPlugin.Commit commit: listCommit) {
          stringEquivalentStaticVersion = commit.mapAttr.get(ScmPlugin.COMMIT_ATTR_EQUIVALENT_STATIC_VERSION);

          if (stringEquivalentStaticVersion != null) {
            break;
          }

          // If a non-Version-changing commit is encountered, it is useless to perform the
          // reference comparison.
          if (   (commit.mapAttr.get(ScmPlugin.COMMIT_ATTR_REFERENCE_VERSION_CHANGE) == null)
              && (commit.mapAttr.get(ScmPlugin.COMMIT_ATTR_VERSION_CHANGE) == null)) {
            break;
          }
        }

        if ((stringEquivalentStaticVersion != null) && module.isNodePluginExists(ReferenceManagerPlugin.class, null)) {
          Version versionStatic;
          WorkspacePlugin workspacePlugin;
          ReferenceManagerPlugin referenceManagerPlugin;
          Path pathModuleWorkspace;
          List<Reference> listReferenceStaticVersion;
          List<Reference> listReferenceDynamicVersion;
          UserInteractionCallbackPlugin userInteractionCallbackPlugin;

          versionStatic = new Version(stringEquivalentStaticVersion);

          if (versionStatic.getVersionType() != VersionType.STATIC) {
            throw new RuntimeException("Version " + versionStatic + " must be static.");
          }

          workspacePlugin = ExecContextHolder.get().getExecContextPlugin(WorkspacePlugin.class);
          referenceManagerPlugin = module.getNodePlugin(ReferenceManagerPlugin.class, null);

          pathModuleWorkspace = scmPlugin.checkoutSystem(versionStatic);
          listReferenceStaticVersion = referenceManagerPlugin.getListReference(pathModuleWorkspace);
          workspacePlugin.releaseWorkspaceDir(pathModuleWorkspace);

          pathModuleWorkspace = scmPlugin.checkoutSystem(versionDynamic);
          listReferenceDynamicVersion = referenceManagerPlugin.getListReference(pathModuleWorkspace);
          workspacePlugin.releaseWorkspaceDir(pathModuleWorkspace);

          if (listReferenceStaticVersion.equals(listReferenceDynamicVersion)) {
            userInteractionCallbackPlugin = ExecContextHolder.get().getExecContextPlugin(UserInteractionCallbackPlugin.class);

            userInteractionCallbackPlugin.provideInfo(MessageFormat.format(SelectStaticVersionPluginBaseImpl.resourceBundle.getString(SelectStaticVersionPluginBaseImpl.MSG_PATTERN_KEY_EXISTING_EQUIVALENT_STATIC_VERSION_EXCLUDE_VESION_CHANGING_COMMITS), new ModuleVersion(module.getNodePath(), versionDynamic), versionStatic));
            versionExistingEquivalentStatic = versionStatic;
          }
        }
      }

      // This algorithm is valid for modules which store ArtifactVersion within their
      // build script (Maven). In other cases, a revert commit may not be required, nor a
      // commit for adjusting the ArtifactVersion in the first place. In such a case we
      // revert to the list of static Version on the current commit.
      if ((versionExistingEquivalentStatic == null) && (commitCurrent.arrayVersionStatic.length >= 1)) {
        versionExistingEquivalentStatic = commitCurrent.arrayVersionStatic[0];
      }
    }

    return versionExistingEquivalentStatic;
  }

  /**
   * Gets a new static {@link Version} based on a prefix and a latest static
   * Version.
   * <p>
   * The caller is responsible for providing a latest static Version which matches
   * the static Version prefix.
   * <p>
   * This class provides methods to help the caller in obtaining this latest static
   * Version depending on the context. It could be the latest static Version created
   * on a given dynamic Version. It could also be the latest static Version
   * globally.
   *
   * @param versionLatestStatic Latest static Version. Can be null, indicating there
   *   is no latest static Version.
   * @param versionStaticPrefix Static version prefix.
   * @return New static Version.
   */
  protected Version getNewStaticVersionFromPrefix(Version versionLatestStatic, Version versionStaticPrefix) {
    RuntimePropertiesPlugin runtimePropertiesPlugin;
    ScmPlugin scmPlugin;
    String runtimeProperty;
    int revisionDecimalPositionCount;
    int initialRevision;
    String revisionFormat;
    int revision;
    Formatter formatter;
    Version versionNewStatic;

    if (versionStaticPrefix.getVersionType() != VersionType.STATIC) {
      throw new RuntimeException("Version " + versionStaticPrefix + " must be static.");
    }

    if ((versionLatestStatic != null) && ((versionLatestStatic.getVersionType() != VersionType.STATIC) || !versionLatestStatic.getVersion().startsWith(versionStaticPrefix.getVersion()))) {
      throw new RuntimeException("Version " + versionLatestStatic + " must be static and must have as a prefix Version " + versionStaticPrefix + '.');
    }

    runtimePropertiesPlugin = ExecContextHolder.get().getExecContextPlugin(RuntimePropertiesPlugin.class);
    scmPlugin = this.getModule().getNodePlugin(ScmPlugin.class, null);

    runtimeProperty = runtimePropertiesPlugin.getProperty(this.getModule(), SelectStaticVersionPluginBaseImpl.RUNTIME_PROPERTY_REVISION_DECIMAL_POSITION_COUNT);

    if (runtimeProperty == null) {
      revisionDecimalPositionCount = this.defaultRevisionDecimalPositionCount;
    } else {
      revisionDecimalPositionCount = Integer.parseInt(runtimeProperty);
    }

    if (revisionDecimalPositionCount == 0) {
      revisionFormat = "%d";
    } else {
      revisionFormat = "%0" + revisionDecimalPositionCount + "d";
    }

    runtimeProperty = runtimePropertiesPlugin.getProperty(this.getModule(), SelectStaticVersionPluginBaseImpl.RUNTIME_PROPERTY_INITIAL_REVISION);

    if (runtimeProperty == null) {
      initialRevision = this.defaultInitialRevision;
    } else {
      initialRevision = Integer.parseInt(runtimeProperty);
    }

    if (versionLatestStatic == null) {
      revision = initialRevision;
    } else {
      String suffix;

      suffix = versionLatestStatic.getVersion().substring(versionStaticPrefix.getVersion().length());

      revision = -1;

      if (suffix.matches("\\.\\d+")) {
        try {
          revision = Integer.parseInt(suffix.substring(1));
        } catch (NumberFormatException nfe) {
        }
      }

      if (revision == -1) {
        throw new RuntimeException("The suffix " + suffix + " of the latest static version " + versionLatestStatic + " is not in the format \".<decimal revision>\".");
      }

      // initialRevision is also used as a lower bound.
      if (revision < initialRevision) {
        revision = initialRevision;
      }

      if (revisionDecimalPositionCount != 0) {
        if (revision == Math.pow(10, revisionDecimalPositionCount) - 1) {
          throw new RuntimeException("The suffix " + suffix + " of the latest static version " + versionLatestStatic + " is already the maximum revision allowed.");
        }
      }

      revision++;

    }

    formatter = new Formatter();
    formatter.format(revisionFormat, new Integer(revision));
    versionNewStatic = new Version(VersionType.STATIC, versionStaticPrefix.getVersion() + '.' + formatter.out().toString());
    formatter.close();

    if (scmPlugin.isVersionExists(versionNewStatic)) {
      throw new RuntimeException("New static version " + versionNewStatic + " already exists for module " + this.getModule() + '.');
    }

    return versionNewStatic;
  }

  /**
   * Gets the latest static {@link Version} matching a static Version prefix among a
   * List of static Version's.
   *
   * @param listVersionStatic List of static Version's, ordered latest first.
   * @param versionStaticPrefix Static Version prefix.
   * @return Latest matching static Version or null if none.
   */
  protected Version getVersionLatestMatchingVersionStaticPrefix(List<Version> listVersionStatic, Version versionStaticPrefix) {
    for (Version version: listVersionStatic) {
      if (version.getVersion().startsWith(versionStaticPrefix.getVersion())) {
        return version;
      }
    }

    return null;
  }

  /**
   * Gets the List of static {@link Version}'s created on a dynamic Version, ordered
   * latest first.
   *
   * @param versionDynamic Dynamic Version.
   * @return List of static Version.
   */
  protected List<Version> getListVersionStaticForDynamicVersion(Version versionDynamic) {
    ScmPlugin scmPlugin;
    List<ScmPlugin.Commit> listCommit;
    List<Version> listVersionStatic;

    scmPlugin = this.getModule().getNodePlugin(ScmPlugin.class, null);
    listVersionStatic = new ArrayList<Version>();
    listCommit = scmPlugin.getListCommit(versionDynamic, null, EnumSet.of(ScmPlugin.GetListCommitFlag.IND_INCLUDE_VERSION_STATIC));

    for (ScmPlugin.Commit commit: listCommit) {
      listVersionStatic.addAll(Arrays.asList(commit.arrayVersionStatic));
    }

    return listVersionStatic;
  }

  /**
   * Gets the List of static {@link Version}'s globally, ordered latest first.
   *
   * @return See description.
   */
  protected List<Version> getListVersionStaticGlobal() {
    Module module;
    ScmPlugin scmPlugin;
    VersionClassifierPlugin versionClassifierPlugin;
    List<Version> listVersionStatic;

    module = this.getModule();
    scmPlugin = module.getNodePlugin(ScmPlugin.class, null);
    versionClassifierPlugin = module.getNodePlugin(VersionClassifierPlugin.class, null);

    listVersionStatic = scmPlugin.getListVersionStatic();
    Collections.sort(listVersionStatic, versionClassifierPlugin);

    return listVersionStatic;
  }
}
