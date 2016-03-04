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

import org.azyva.dragom.apiutil.ByReference;
import org.azyva.dragom.execcontext.plugin.RuntimePropertiesPlugin;
import org.azyva.dragom.execcontext.plugin.UserInteractionCallbackPlugin;
import org.azyva.dragom.execcontext.support.ExecContextHolder;
import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.Version;
import org.azyva.dragom.model.VersionType;
import org.azyva.dragom.model.plugin.NewDynamicVersionPlugin;
import org.azyva.dragom.model.plugin.ScmPlugin;
import org.azyva.dragom.util.AlwaysNeverAskUserResponse;
import org.azyva.dragom.util.RuntimeExceptionUserError;
import org.azyva.dragom.util.Util;

/**
 * Factory for NewDynamicVersionPlugin that implements a strategy for hotfixes.
 * <p>
 * The current Version must be static.
 * <p>
 * The strategy is such that the new dynamic Version is created directly from the
 * current Version itself.
 *
 * @author David Raymond
 */
public class HotfixNewDynamicVersionPluginImpl extends ModulePluginAbstractImpl implements NewDynamicVersionPlugin {
	/**
	 * Runtime property of type AlwaysNeverAskUserResponse that indicates if a
	 * previously established dynamic Version can be reused.
	 */
	private static final String RUNTIME_PROPERTY_CAN_REUSE_DYNAMIC_VERSION = "CAN_REUSE_DYNAMIC_VERSION";

	/**
	 * Runtime property that specifies the dynamic Version to reuse.
	 */
	private static final String RUNTIME_PROPERTY_REUSE_DYNAMIC_VERSION = "REUSE_DYNAMIC_VERSION";

	public HotfixNewDynamicVersionPluginImpl(Module module) {
		super(module);
	}

	@Override
	public Version getVersionNewDynamic(Version version, ByReference<Version> byReferenceVersionBase) {
		RuntimePropertiesPlugin runtimePropertiesPlugin;
		UserInteractionCallbackPlugin userInteractionCallbackPlugin;
		Module module;
		ScmPlugin scmPlugin;
		String runtimeProperty;
		AlwaysNeverAskUserResponse alwaysNeverAskUserResponseCanReuseDynamicVersion;
		Version versionReuseDynamic;
		Version versionNewDynamic = null;

		runtimePropertiesPlugin = ExecContextHolder.get().getExecContextPlugin(RuntimePropertiesPlugin.class);
		userInteractionCallbackPlugin = ExecContextHolder.get().getExecContextPlugin(UserInteractionCallbackPlugin.class);
		module = this.getModule();
		scmPlugin = module.getNodePlugin(ScmPlugin.class, null);

		alwaysNeverAskUserResponseCanReuseDynamicVersion = AlwaysNeverAskUserResponse.valueOfWithAskDefault(runtimePropertiesPlugin.getProperty(module, HotfixNewDynamicVersionPluginImpl.RUNTIME_PROPERTY_CAN_REUSE_DYNAMIC_VERSION));

		runtimeProperty = runtimePropertiesPlugin.getProperty(module, HotfixNewDynamicVersionPluginImpl.RUNTIME_PROPERTY_REUSE_DYNAMIC_VERSION);

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

		// The main algorithm is after this "if". This "if" is for taking care of the case
		// where the Version of the module is dynamic in which case it is possible that no
		// switch is required. But if a switch is required, the algorithm after this "if"
		// gets executed regardless of whether the Version is dynamic or static.

		if (version.getVersionType() != VersionType.STATIC) {
			throw new RuntimeExceptionUserError("Version " + version + " must be static.");
		}

		if (alwaysNeverAskUserResponseCanReuseDynamicVersion.isAlways()) {
			userInteractionCallbackPlugin.provideInfo("New dynamic version " + versionReuseDynamic + " is automatically reused for module " + module + " whose version is currently " + version + '.');
			versionNewDynamic = versionReuseDynamic;
		} else {
			versionNewDynamic =
					Util.getInfoVersion(
							VersionType.DYNAMIC,
							null,
							userInteractionCallbackPlugin,
							"To which dynamic version do you want to switch version " + version + " of module " + module + " to*",
							versionReuseDynamic);

			runtimePropertiesPlugin.setProperty(null, HotfixNewDynamicVersionPluginImpl.RUNTIME_PROPERTY_REUSE_DYNAMIC_VERSION, versionNewDynamic.toString());

			alwaysNeverAskUserResponseCanReuseDynamicVersion =
					Util.getInfoAlwaysNeverAskUserResponseAndHandleAsk(
							runtimePropertiesPlugin,
							HotfixNewDynamicVersionPluginImpl.RUNTIME_PROPERTY_CAN_REUSE_DYNAMIC_VERSION,
							userInteractionCallbackPlugin,
							"Do you want to automatically reuse dynamic version " + versionNewDynamic + " for all subsequent modules for which a new version needs to be created*",
							AlwaysNeverAskUserResponse.ALWAYS);
		}

		// After all this, the new version may be the same as the current version.
		if (versionNewDynamic.equals(version)) {
			userInteractionCallbackPlugin.provideInfo("The new dynamic version " + versionNewDynamic + " is the same as the current version " + version + " of module " + module + ". We simply keep it.");
			return version;
		}

		// Here versionNew holds the new version to switch to. If it does not exist we
		// specify to use the current Version as the base. If it does exist, we validate
		// that its base Version is the current one.

		if (!scmPlugin.isVersionExists(versionNewDynamic)) {
			userInteractionCallbackPlugin.provideInfo("The dynamic version " + versionNewDynamic + " does not exist in module " + module + '.');
			userInteractionCallbackPlugin.provideInfo("Current version " + version + " is used as the base for that new version.");
			byReferenceVersionBase.object = version;
		} else {
			ScmPlugin.BaseVersion baseVersion;

			baseVersion = scmPlugin.getBaseVersion(versionNewDynamic);

			if (!baseVersion.versionBase.equals(version)) {
				throw new RuntimeExceptionUserError("The dynamic version " + versionNewDynamic + " already exists in module " + module + ", but it is not based on the current version " + version + '.');
			}
		}

		return versionNewDynamic;
	}
}
