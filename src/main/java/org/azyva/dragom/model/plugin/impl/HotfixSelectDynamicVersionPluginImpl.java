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

package org.azyva.dragom.model.plugin.impl;

import java.text.MessageFormat;
import java.util.ResourceBundle;

import org.azyva.dragom.apiutil.ByReference;
import org.azyva.dragom.execcontext.plugin.UserInteractionCallbackPlugin;
import org.azyva.dragom.execcontext.support.ExecContextHolder;
import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.ModuleVersion;
import org.azyva.dragom.model.Version;
import org.azyva.dragom.model.VersionType;
import org.azyva.dragom.model.plugin.ScmPlugin;
import org.azyva.dragom.model.plugin.SelectDynamicVersionPlugin;
import org.azyva.dragom.reference.ReferencePath;
import org.azyva.dragom.util.RuntimeExceptionUserError;
import org.azyva.dragom.util.Util;

/**
 * {@link SelectDynamicVersionPlugin} that implements a strategy for hotfixes.
 * <p>
 * The current {@link Version} must be static.
 * <p>
 * The strategy is such that the a new dynamic Version is created directly from
 * the current Version itself.
 *
 * @author David Raymond
 */
public class HotfixSelectDynamicVersionPluginImpl extends SelectDynamicVersionPluginBaseImpl implements SelectDynamicVersionPlugin {
  /**
   * Context for {@link Util#handleDoYouWantToContinue} that represents the fact
   * that during the switch to a hotfix dynamic {@link Version}, the
   * {@link ReferencePath} contains non-static Versions. which may be OK if they
   * were created in the context of the hotfix.
   */
  private static final String DO_YOU_WANT_TO_CONTINUE_CONTEXT_NON_STATIC_VERSIONS_REFERENCE_PATH = "NON_STATIC_VERSIONS_REFERENCE_PATH";

  /**
   * Context for {@link Util#handleDoYouWantToContinue} that represents the fact
   * that during the switch to a hotfix dynamic {@link Version}, the
   * {@link ReferencePath} contains non-static Versions. which may be OK if they
   * were created in the context of the hotfix.
   */
  private static final String DO_YOU_WANT_TO_CONTINUE_CONTEXT_USE_CURRENT_HOTFIX_VERSION = "USE_CURRENT_HOTFIX_VERSION";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_VERSIONS_REFERENCE_PATH_STATIC = "VERSIONS_REFERENCE_PATH_STATIC";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_USE_CURRENT_HOTFIX_VERSION_BASE_UNKNOWN = "USE_CURRENT_HOTFIX_VERSION_BASE_UNKNOWN";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_USE_CURRENT_HOTFIX_VERSION_FOR_BASE = "USE_CURRENT_HOTFIX_VERSION_FOR_BASE";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_SELECTED_DYNAMIC_VERSION_DOES_NOT_EXIST_CURRENT_VERSION_BASE = "SELECTED_DYNAMIC_VERSION_DOES_NOT_EXIST_CURRENT_VERSION_BASE";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_SELECTED_DYNAMIC_VERSION_ALREADY_EXISTS_CURRENT_VERSION_NOT_BASE = "SELECTED_DYNAMIC_VERSION_ALREADY_EXISTS_CURRENT_VERSION_NOT_BASE";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_SELECTED_DYNAMIC_VERSION_ALREADY_EXISTS_BASE_UNKNOWN = "SELECTED_DYNAMIC_VERSION_ALREADY_EXISTS_BASE_UNKNOWN";

  /**
   * ResourceBundle specific to this class.
   */
  private static final ResourceBundle resourceBundle = ResourceBundle.getBundle(HotfixSelectDynamicVersionPluginImpl.class.getName() + "ResourceBundle");

  public HotfixSelectDynamicVersionPluginImpl(Module module) {
    super(module);
  }

  @Override
  public Version selectDynamicVersion(Version version, ByReference<Version> byReferenceVersionBase, ReferencePath referencePath) {
    UserInteractionCallbackPlugin userInteractionCallbackPlugin;
    Module module;
    ScmPlugin scmPlugin;
    ScmPlugin.BaseVersion baseVersion;
    Version versionDynamicSelected;

    userInteractionCallbackPlugin = ExecContextHolder.get().getExecContextPlugin(UserInteractionCallbackPlugin.class);
    module = this.getModule();
    scmPlugin = module.getNodePlugin(ScmPlugin.class, null);

    // If the size of the ReferencePath is 1 it means the current ModuleVersion is the
    // only one in the ReferenceGraph and the message would be redundant with the one below.
    if ((referencePath.size() > 1) && (referencePath.get(0).getModuleVersion().getVersion().getVersionType() != VersionType.STATIC)) {
      userInteractionCallbackPlugin.provideInfo(MessageFormat.format(HotfixSelectDynamicVersionPluginImpl.resourceBundle.getString(HotfixSelectDynamicVersionPluginImpl.MSG_PATTERN_KEY_VERSIONS_REFERENCE_PATH_STATIC), new ModuleVersion(module.getNodePath(), version), referencePath));

      if (!Util.handleDoYouWantToContinue(HotfixSelectDynamicVersionPluginImpl.DO_YOU_WANT_TO_CONTINUE_CONTEXT_NON_STATIC_VERSIONS_REFERENCE_PATH)) {
        return null;
      }
    }

    // We should not need to validate this since a ReferencePath starting with a
    // static Version (validated above) should contain only static Version's.
    if (version.getVersionType() != VersionType.STATIC) {
      baseVersion = scmPlugin.getBaseVersion(version);

      if (baseVersion == null) {
        userInteractionCallbackPlugin.provideInfo(MessageFormat.format(HotfixSelectDynamicVersionPluginImpl.resourceBundle.getString(HotfixSelectDynamicVersionPluginImpl.MSG_PATTERN_KEY_USE_CURRENT_HOTFIX_VERSION_BASE_UNKNOWN), new ModuleVersion(module.getNodePath(), version)));
      } else {
        userInteractionCallbackPlugin.provideInfo(MessageFormat.format(HotfixSelectDynamicVersionPluginImpl.resourceBundle.getString(HotfixSelectDynamicVersionPluginImpl.MSG_PATTERN_KEY_USE_CURRENT_HOTFIX_VERSION_FOR_BASE), new ModuleVersion(module.getNodePath(), version), baseVersion.versionBase));
      }

      if (!Util.handleDoYouWantToContinue(HotfixSelectDynamicVersionPluginImpl.DO_YOU_WANT_TO_CONTINUE_CONTEXT_USE_CURRENT_HOTFIX_VERSION)) {
        return null;
      }

      return version;
    }

    // versionDynamicSelected is necessarily dynamic so no need to check if equal to
    // current Version which is necessarily static.
    versionDynamicSelected = this.handleReuseDynamicVersion(version);

    // Here versionNew holds the new version to switch to. If it does not exist we
    // specify to use the current Version as the base. If it does exist, we validate
    // that its base Version is the current one.

    if (!scmPlugin.isVersionExists(versionDynamicSelected)) {
      userInteractionCallbackPlugin.provideInfo(MessageFormat.format(HotfixSelectDynamicVersionPluginImpl.resourceBundle.getString(HotfixSelectDynamicVersionPluginImpl.MSG_PATTERN_KEY_SELECTED_DYNAMIC_VERSION_DOES_NOT_EXIST_CURRENT_VERSION_BASE), module, versionDynamicSelected, version));
      byReferenceVersionBase.object = version;
    } else {
      baseVersion = scmPlugin.getBaseVersion(versionDynamicSelected);

      if (baseVersion == null) {
        throw new RuntimeExceptionUserError(MessageFormat.format(HotfixSelectDynamicVersionPluginImpl.resourceBundle.getString(HotfixSelectDynamicVersionPluginImpl.MSG_PATTERN_KEY_SELECTED_DYNAMIC_VERSION_ALREADY_EXISTS_BASE_UNKNOWN), module, versionDynamicSelected, version));
      }

      if (!baseVersion.versionBase.equals(version)) {
        throw new RuntimeExceptionUserError(MessageFormat.format(HotfixSelectDynamicVersionPluginImpl.resourceBundle.getString(HotfixSelectDynamicVersionPluginImpl.MSG_PATTERN_KEY_SELECTED_DYNAMIC_VERSION_ALREADY_EXISTS_CURRENT_VERSION_NOT_BASE), module, versionDynamicSelected, version));
      }
    }

    return versionDynamicSelected;
  }
}
