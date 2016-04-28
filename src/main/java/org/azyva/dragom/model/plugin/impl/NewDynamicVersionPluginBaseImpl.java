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

import java.text.MessageFormat;
import java.util.ResourceBundle;

import org.azyva.dragom.execcontext.plugin.RuntimePropertiesPlugin;
import org.azyva.dragom.execcontext.plugin.UserInteractionCallbackPlugin;
import org.azyva.dragom.execcontext.support.ExecContextHolder;
import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.ModuleVersion;
import org.azyva.dragom.model.Version;
import org.azyva.dragom.model.VersionType;
import org.azyva.dragom.util.AlwaysNeverAskUserResponse;
import org.azyva.dragom.util.Util;

/**
 * Base class for NewDynamicVersionPlugin that provide useful helper methods that
 * are common to many such plugins.
 *
 * @author David Raymond
 */

public class NewDynamicVersionPluginBaseImpl extends ModulePluginAbstractImpl {
	/**
	 * Runtime property that spécifies the specific dynamic Version to use.
	 */
	private static final String RUNTIME_PROPERTY_SPECIFIC_DYNAMIC_VERSION = "SPECIFIC_DYNAMIC_VERSION";

	/**
	 * Runtime property of type AlwaysNeverAskUserResponse that indicates if a
	 * previously established dynamic Version can be reused.
	 */
	private static final String RUNTIME_PROPERTY_CAN_REUSE_DYNAMIC_VERSION = "CAN_REUSE_DYNAMIC_VERSION";

	/**
	 * Runtime property that specifies the dynamic Version to reuse.
	 */
	private static final String RUNTIME_PROPERTY_REUSE_DYNAMIC_VERSION = "REUSE_DYNAMIC_VERSION";

	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_NEW_DYNAMIC_VERSION_SPECIFIED = "NEW_DYNAMIC_VERSION_SPECIFIED";

	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_NEW_DYNAMIC_VERSION_SAME_AS_CURRENT = "NEW_DYNAMIC_VERSION_SAME_AS_CURRENT";

	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_DYNAMIC_VERSION_AUTOMATICALLY_REUSED = "DYNAMIC_VERSION_AUTOMATICALLY_REUSED";

	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_INPUT_NEW_DYNAMIC_VERSION = "INPUT_NEW_DYNAMIC_VERSION";

	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_AUTOMATICALLY_REUSE_DYNAMIC_VERSION = "AUTOMATICALLY_REUSE_DYNAMIC_VERSION";

	/**
	 * ResourceBundle specific to this class.
	 */
	private static final ResourceBundle resourceBundle = ResourceBundle.getBundle(NewDynamicVersionPluginBaseImpl.class.getName() + "ResourceBundle");

	public NewDynamicVersionPluginBaseImpl(Module module) {
		super(module);
	}

	/**
	 * Handles the case where a specific dynamic version is specified for the
	 * module.
	 *
	 * @param version Version.
	 * @return Specific dynamic Version. null if none specified.
	 */
	protected Version handleSpecificDynamicVersion(Version version) {
		RuntimePropertiesPlugin runtimePropertiesPlugin;
		UserInteractionCallbackPlugin userInteractionCallbackPlugin;
		String stringSpecificDynamicVersion;
		Version versionNewDynamic;

		runtimePropertiesPlugin = ExecContextHolder.get().getExecContextPlugin(RuntimePropertiesPlugin.class);
		userInteractionCallbackPlugin = ExecContextHolder.get().getExecContextPlugin(UserInteractionCallbackPlugin.class);

		stringSpecificDynamicVersion = runtimePropertiesPlugin.getProperty(this.getModule(), NewDynamicVersionPluginBaseImpl.RUNTIME_PROPERTY_SPECIFIC_DYNAMIC_VERSION);

		if (stringSpecificDynamicVersion != null) {
			versionNewDynamic = new Version(stringSpecificDynamicVersion);

			if (versionNewDynamic.getVersionType() != VersionType.DYNAMIC) {
				throw new RuntimeException("Version " + versionNewDynamic + " must be dynamic.");
			}

			userInteractionCallbackPlugin.provideInfo(MessageFormat.format(NewDynamicVersionPluginBaseImpl.resourceBundle.getString(NewDynamicVersionPluginBaseImpl.MSG_PATTERN_KEY_NEW_DYNAMIC_VERSION_SPECIFIED), new ModuleVersion(this.getModule().getNodePath(), version), versionNewDynamic));

			// After all this, the new version may be the same as the current version. In this
			// case we expect the caller to automatically use this version. We simply inform
			// the user here.
			if (versionNewDynamic.equals(version)) {
				userInteractionCallbackPlugin.provideInfo(MessageFormat.format(NewDynamicVersionPluginBaseImpl.resourceBundle.getString(NewDynamicVersionPluginBaseImpl.MSG_PATTERN_KEY_NEW_DYNAMIC_VERSION_SAME_AS_CURRENT), new ModuleVersion(this.getModule().getNodePath(), version), versionNewDynamic));
			}

			return versionNewDynamic;
		} else {
			return null;
		}
	}

	/**
	 * Handles reusing an existing dynamic Version that was previously specified.
	 * <p>
	 * If there is no existing dynamic Version to reuse automatically, the user is
	 * asked for a dynamic Version and this Version is stored as the dynamic Version
	 * to be reused thereafter.
	 * <p>
	 * The user is also asked whether this Version should always or never be
	 * automatically reused, or if he should be asked again the next time.
	 *
	 * @param version Version to switch.
	 * @return New dynamic Version to switch to. null if no dynamic Version is to be
	 *   reused.
	 */
	protected Version handleReuseDynamicVersion(Version version) {
		RuntimePropertiesPlugin runtimePropertiesPlugin;
		UserInteractionCallbackPlugin userInteractionCallbackPlugin;
		Module module;
		String runtimeProperty;
		AlwaysNeverAskUserResponse alwaysNeverAskUserResponseCanReuseDynamicVersion;
		Version versionReuseDynamic;
		Version versionNewDynamic;

		runtimePropertiesPlugin = ExecContextHolder.get().getExecContextPlugin(RuntimePropertiesPlugin.class);
		userInteractionCallbackPlugin = ExecContextHolder.get().getExecContextPlugin(UserInteractionCallbackPlugin.class);
		module = this.getModule();

		alwaysNeverAskUserResponseCanReuseDynamicVersion = AlwaysNeverAskUserResponse.valueOfWithAskDefault(runtimePropertiesPlugin.getProperty(module, NewDynamicVersionPluginBaseImpl.RUNTIME_PROPERTY_CAN_REUSE_DYNAMIC_VERSION));

		runtimeProperty = runtimePropertiesPlugin.getProperty(module, NewDynamicVersionPluginBaseImpl.RUNTIME_PROPERTY_REUSE_DYNAMIC_VERSION);

		if (runtimeProperty != null) {
			versionReuseDynamic = new Version(runtimeProperty);

			if (versionReuseDynamic.getVersionType() != VersionType.DYNAMIC) {
				throw new RuntimeException("Version " + versionReuseDynamic + " must be dynamic.");
			}
		} else {
			versionReuseDynamic = null;

			if (alwaysNeverAskUserResponseCanReuseDynamicVersion.isAlways()) {
				// Normally if the runtime property CAN_REUSE_DYNAMIC_VERSION is ALWAYS the
				// REUSE_DYNAMIC_VERSION runtime property should also be set. But since these
				// properties are independent and stored externally, it can happen that they
				// are not synchronized. We make an adjustment here to avoid problems.
				alwaysNeverAskUserResponseCanReuseDynamicVersion = AlwaysNeverAskUserResponse.ASK;
			}
		}

		if (alwaysNeverAskUserResponseCanReuseDynamicVersion.isAlways()) {
			userInteractionCallbackPlugin.provideInfo(MessageFormat.format(NewDynamicVersionPluginBaseImpl.resourceBundle.getString(NewDynamicVersionPluginBaseImpl.MSG_PATTERN_KEY_DYNAMIC_VERSION_AUTOMATICALLY_REUSED), new ModuleVersion(module.getNodePath(), version), versionReuseDynamic));
			versionNewDynamic = versionReuseDynamic;
		} else {
			versionNewDynamic =
					Util.getInfoVersion(
							VersionType.DYNAMIC,
							null,
							userInteractionCallbackPlugin,
							MessageFormat.format(NewDynamicVersionPluginBaseImpl.resourceBundle.getString(NewDynamicVersionPluginBaseImpl.MSG_PATTERN_KEY_INPUT_NEW_DYNAMIC_VERSION), new ModuleVersion(module.getNodePath(), version)),
							versionReuseDynamic);

			runtimePropertiesPlugin.setProperty(null, NewDynamicVersionPluginBaseImpl.RUNTIME_PROPERTY_REUSE_DYNAMIC_VERSION, versionNewDynamic.toString());

			// The result is not useful. We only want to adjust the runtime property which
			// will be reused the next time around.
			Util.getInfoAlwaysNeverAskUserResponseAndHandleAsk(
					runtimePropertiesPlugin,
					NewDynamicVersionPluginBaseImpl.RUNTIME_PROPERTY_CAN_REUSE_DYNAMIC_VERSION,
					userInteractionCallbackPlugin,
					MessageFormat.format(NewDynamicVersionPluginBaseImpl.resourceBundle.getString(NewDynamicVersionPluginBaseImpl.MSG_PATTERN_KEY_AUTOMATICALLY_REUSE_DYNAMIC_VERSION), versionNewDynamic));
		}

		// After all this, the new version may be the same as the current version. In this
		// case we expect the caller to automatically use this version. We simply inform
		// the user here.
		if (versionNewDynamic.equals(version)) {
			userInteractionCallbackPlugin.provideInfo(MessageFormat.format(NewDynamicVersionPluginBaseImpl.resourceBundle.getString(NewDynamicVersionPluginBaseImpl.MSG_PATTERN_KEY_NEW_DYNAMIC_VERSION_SAME_AS_CURRENT), new ModuleVersion(module.getNodePath(), version), versionNewDynamic));
		}

		return versionNewDynamic;
	}
}
