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
import org.azyva.dragom.util.Util;
import org.azyva.dragom.util.YesAlwaysNoUserResponse;

/**
 * Factory for NewDynamicVersionPlugin that implements a strategy involving uniform
 * versions across multiple modules for which a new dynamic Version needs to be
 * created.
 *
 * This is appropriate when dynamic Version are related to a development effort, a
 * project. Often a project code or number is used as or within the Version.
 *
 * @author David Raymond
 */
public class UniformNewDynamicVersionPluginImpl extends ModulePluginAbstractImpl implements NewDynamicVersionPlugin {
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
	 * Runtime property of type AlwaysNeverAskUserResponse that indicates if Version's
	 * that are already dynamic should be processed.
	 */
	private static final String RUNTIME_PROPERTY_ALSO_PROCESS_DYNAMIC_VERSION = "ALSO_PROCESS_DYNAMIC_VERSION";

	/**
	 * Runtime property of type AlwaysNeverAskUserResponse that indicates if a
	 * previously established base Version used when new dynamic Version's need to be
	 * created can be reused.
	 */
	private static final String RUNTIME_PROPERTY_CAN_REUSE_BASE_VERSION = "CAN_REUSE_BASE_VERSION";

	/**
	 * Runtime property that specifies the base Version to reuse.
	 */
	private static final String RUNTIME_PROPERTY_REUSE_BASE_VERSION = "REUSE_BASE_VERSION";

	public UniformNewDynamicVersionPluginImpl(Module module) {
		super(module);
	}

	@Override
	public Version getVersionNewDynamic(Version version, ByReference<Version> byReferenceVersionBase) {
		RuntimePropertiesPlugin runtimePropertiesPlugin;
		UserInteractionCallbackPlugin userInteractionCallbackPlugin;
		Module module;
		ScmPlugin scmPlugin;
		String runtimeProperty;
		YesAlwaysNoUserResponse yesAlwaysNoUserResponse;
		AlwaysNeverAskUserResponse alwaysNeverAskUserResponseCanReuseDynamicVersion;
		Version versionReuseDynamic;
		AlwaysNeverAskUserResponse alwaysNeverAskUserResponseAlsoProcessDynamicVersion;
		Version versionNewDynamic = null;
		AlwaysNeverAskUserResponse alwaysNeverAskUserResponseCanReuseBaseVersion;
		Version versionReuseBase;
		Version versionBase = null;

		runtimePropertiesPlugin = ExecContextHolder.get().getExecContextPlugin(RuntimePropertiesPlugin.class);
		userInteractionCallbackPlugin = ExecContextHolder.get().getExecContextPlugin(UserInteractionCallbackPlugin.class);
		module = this.getModule();
		scmPlugin = module.getNodePlugin(ScmPlugin.class, null);

		alwaysNeverAskUserResponseCanReuseDynamicVersion = AlwaysNeverAskUserResponse.valueOfWithAskDefault(runtimePropertiesPlugin.getProperty(module, UniformNewDynamicVersionPluginImpl.RUNTIME_PROPERTY_CAN_REUSE_DYNAMIC_VERSION));

		runtimeProperty = runtimePropertiesPlugin.getProperty(module, UniformNewDynamicVersionPluginImpl.RUNTIME_PROPERTY_REUSE_DYNAMIC_VERSION);

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

		if (version.getVersionType() == VersionType.DYNAMIC) {
			alwaysNeverAskUserResponseAlsoProcessDynamicVersion = AlwaysNeverAskUserResponse.valueOfWithAskDefault(runtimePropertiesPlugin.getProperty(module, UniformNewDynamicVersionPluginImpl.RUNTIME_PROPERTY_ALSO_PROCESS_DYNAMIC_VERSION));

			// If already dynamic Version's must not be changed, we return immediately with the
			// same Version.
			if (alwaysNeverAskUserResponseAlsoProcessDynamicVersion.isNever()) {
				userInteractionCallbackPlugin.provideInfo("Version " + version + " of module " + module + " is already dyanmic and is indicated to not be processed.");
				return version;
			}

			if (alwaysNeverAskUserResponseAlsoProcessDynamicVersion.isAlways()) {
				userInteractionCallbackPlugin.provideInfo("Version " + version + " of module " + module + " is already dyanmic and is indicated to be processed.");
			} else { // if (alwaysNeverAskUserResponseAlsoProcessDynamicVersion.isAsk())
				yesAlwaysNoUserResponse = Util.getInfoYesNoUserResponse(userInteractionCallbackPlugin, "Version " + version + " of module " + module + " is already dynamic. Do you want to process it*", YesAlwaysNoUserResponse.YES);

				alwaysNeverAskUserResponseAlsoProcessDynamicVersion =
					Util.getInfoAlwaysNeverAskUserResponseAndHandleAsk(
							runtimePropertiesPlugin,
							UniformNewDynamicVersionPluginImpl.RUNTIME_PROPERTY_ALSO_PROCESS_DYNAMIC_VERSION,
							userInteractionCallbackPlugin,
							"Do you want to automatically process already dynamic versions of subsequent modules*",
							yesAlwaysNoUserResponse.isYes() ? AlwaysNeverAskUserResponse.ALWAYS : AlwaysNeverAskUserResponse.NEVER);

				if (yesAlwaysNoUserResponse.isNo()) {
					return version;
				}
			}
		}

		// Here we know a new version is required (even if the current Version is
		// dynamic).

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

			runtimePropertiesPlugin.setProperty(null, UniformNewDynamicVersionPluginImpl.RUNTIME_PROPERTY_REUSE_DYNAMIC_VERSION, versionNewDynamic.toString());

			alwaysNeverAskUserResponseCanReuseDynamicVersion =
					Util.getInfoAlwaysNeverAskUserResponseAndHandleAsk(
							runtimePropertiesPlugin,
							UniformNewDynamicVersionPluginImpl.RUNTIME_PROPERTY_CAN_REUSE_DYNAMIC_VERSION,
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
		// establish based on what existing version it must be created.

		if (!scmPlugin.isVersionExists(versionNewDynamic)) {
			alwaysNeverAskUserResponseCanReuseBaseVersion = AlwaysNeverAskUserResponse.valueOfWithAskDefault(runtimePropertiesPlugin.getProperty(module, UniformNewDynamicVersionPluginImpl.RUNTIME_PROPERTY_CAN_REUSE_BASE_VERSION));

			runtimeProperty = runtimePropertiesPlugin.getProperty(module, UniformNewDynamicVersionPluginImpl.RUNTIME_PROPERTY_REUSE_BASE_VERSION);

			if (runtimeProperty != null) {
				versionReuseBase = new Version(runtimeProperty);
			} else {
				versionReuseBase = null;

				if (alwaysNeverAskUserResponseCanReuseBaseVersion.isAlways()) {
					// Normally if the runtime property CAN_REUSE_BASE_VERSION is ALWAYS the
					// REUSE_BASE_VERSION runtime property should also be set. But since these
					// properties are independent and stored externally, it can happen that they
					// are not synchronized. We make an adjustment here to avoid problems.
					alwaysNeverAskUserResponseCanReuseBaseVersion = AlwaysNeverAskUserResponse.ASK;
				}
			}

			userInteractionCallbackPlugin.provideInfo("The dynamic version " + versionNewDynamic + " does not exist in module " + module + '.');

			if (alwaysNeverAskUserResponseCanReuseBaseVersion.isAlways()) {
				userInteractionCallbackPlugin.provideInfo("Base version " + versionReuseBase + " is automatically reused as a base for that new version.");
				versionBase = versionReuseBase;

				if (!scmPlugin.isVersionExists(versionBase)) {
					userInteractionCallbackPlugin.provideInfo("Automatically reused base version " + versionBase + " does not exist in module " + module + '.');
					userInteractionCallbackPlugin.provideInfo("Please explicitly specify an existing base version only for this module.");

					versionBase =
							Util.getInfoVersion(
									null,
									scmPlugin,
									userInteractionCallbackPlugin,
									"From which existing dynamic or static version do you want to create that new version*",
									version);
				}
			} else {
				versionBase =
						Util.getInfoVersion(
								null,
								scmPlugin,
								userInteractionCallbackPlugin,
								"From which existing dynamic or static version do you want to create that new version*",
								(versionReuseBase != null) ? versionReuseBase : version);

				runtimePropertiesPlugin.setProperty(null, UniformNewDynamicVersionPluginImpl.RUNTIME_PROPERTY_REUSE_BASE_VERSION, versionBase.toString());

				alwaysNeverAskUserResponseCanReuseBaseVersion =
						Util.getInfoAlwaysNeverAskUserResponseAndHandleAsk(
								runtimePropertiesPlugin,
								UniformNewDynamicVersionPluginImpl.RUNTIME_PROPERTY_CAN_REUSE_BASE_VERSION,
								userInteractionCallbackPlugin,
								"Do you want to automatically reuse base version " + versionBase + " for all subsequent modules for which a new version needs to be created*",
								AlwaysNeverAskUserResponse.ALWAYS);
			}

			byReferenceVersionBase.object = versionBase;
		}

		return versionNewDynamic;
	}
}
