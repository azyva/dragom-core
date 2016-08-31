/*
 * Copyright 2015, 2016 AZYVA INC.
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
import org.azyva.dragom.execcontext.plugin.RuntimePropertiesPlugin;
import org.azyva.dragom.execcontext.plugin.UserInteractionCallbackPlugin;
import org.azyva.dragom.execcontext.support.ExecContextHolder;
import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.ModuleVersion;
import org.azyva.dragom.model.Version;
import org.azyva.dragom.model.VersionType;
import org.azyva.dragom.model.plugin.NewDynamicVersionPlugin;
import org.azyva.dragom.model.plugin.ScmPlugin;
import org.azyva.dragom.reference.ReferencePath;
import org.azyva.dragom.util.AlwaysNeverAskUserResponse;
import org.azyva.dragom.util.AlwaysNeverYesNoAskUserResponse;
import org.azyva.dragom.util.Util;

/**
 * {@link NewDynamicVersionPlugin} that implements a strategy involving uniform
 * {@link Version}'s across multiple modules for which a new dynamic Version needs
 * to be created.
 * <p>
 * This is appropriate when dynamic Version's are related to a development effort,
 * a project. Often a project code or number is used as or within the Version.
 * <p>
 * A reuse mechanism is also implemented for the base Version when new dynamic
 * Version's need to be created. If a reuse base Version is not available, the
 * default Version for the {@link Module} is used. This may not be optimal for
 * Module's with multiple main development branches, such as Web services for
 * which multiple versions can be deployed and maintained simultaneously. Another
 * NewDynamicVersionPlugin may be better suited in such cases.
 * TODO: Another NewDynamicVersionPlugin suited for multiple main development branches.
 *
 * @author David Raymond
 */
public class UniformNewDynamicVersionPluginImpl extends NewDynamicVersionPluginBaseImpl implements NewDynamicVersionPlugin {
	/**
	 * Runtime property of type AlwaysNeverYesNoAskUserResponse that indicates if
	 * Version's that are already dynamic should be processed.
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

	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_DYNAMIC_VERSION_NOT_TO_BE_PROCESSED = "DYNAMIC_VERSION_NOT_TO_BE_PROCESSED";

	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_DYNAMIC_VERSION_TO_BE_PROCESSED = "DYNAMIC_VERSION_TO_BE_PROCESSED";

	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_DO_YOU_WANT_TO_PROCESS_DYNAMIC_VERSION = "DO_YOU_WANT_TO_PROCESS_DYNAMIC_VERSION";

	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_NEW_DYNAMIC_VERSION_DOES_NOT_EXIST = "NEW_DYNAMIC_VERSION_DOES_NOT_EXIST";

	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_BASE_VERSION_AUTOMATICALLY_REUSED = "BASE_VERSION_AUTOMATICALLY_REUSED";

	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_AUTOMATICALLY_REUSED_BASE_VERSION_DOES_NOT_EXIST = "AUTOMATICALLY_REUSED_BASE_VERSION_DOES_NOT_EXIST";

	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_INPUT_NEW_BASE_VERSION = "INPUT_NEW_BASE_VERSION";

	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_AUTOMATICALLY_REUSE_BASE_VERSION = "AUTOMATICALLY_REUSE_BASE_VERSION";

	/**
	 * ResourceBundle specific to this class.
	 */
	private static final ResourceBundle resourceBundle = ResourceBundle.getBundle(UniformNewDynamicVersionPluginImpl.class.getName() + "ResourceBundle");

	public UniformNewDynamicVersionPluginImpl(Module module) {
		super(module);
	}

	@Override
	public Version getVersionNewDynamic(Version version, ByReference<Version> byReferenceVersionBase, ReferencePath referencePath) {
		RuntimePropertiesPlugin runtimePropertiesPlugin;
		UserInteractionCallbackPlugin userInteractionCallbackPlugin;
		Version versionNewDynamic;
		Module module;
		ScmPlugin scmPlugin;
		String runtimeProperty;
		AlwaysNeverYesNoAskUserResponse alwaysNeverYesNoAskUserResponseAlsoProcessDynamicVersion;
		AlwaysNeverAskUserResponse alwaysNeverAskUserResponseCanReuseBaseVersion;
		Version versionReuseBase;
		Version versionBase = null;

		runtimePropertiesPlugin = ExecContextHolder.get().getExecContextPlugin(RuntimePropertiesPlugin.class);
		userInteractionCallbackPlugin = ExecContextHolder.get().getExecContextPlugin(UserInteractionCallbackPlugin.class);
		module = this.getModule();
		scmPlugin = module.getNodePlugin(ScmPlugin.class, null);

		// We start by checking if a specific new dynamic Version is specified. If do and
		// and it is the same as the current one, we return it immediately without checking
		// if dynamic Version's must be processed.

		versionNewDynamic = this.handleSpecificDynamicVersion(version);

		if ((versionNewDynamic != null) && versionNewDynamic.equals(version)) {
			return version;
		}

		// The main algorithm is after this "if". This "if" is for taking care of the case
		// where the Version of the module is dynamic in which case it is possible that no
		// switch is required. But if a switch is required, the algorithm after this "if"
		// gets executed regardless of whether the Version is dynamic or static.

		if (version.getVersionType() == VersionType.DYNAMIC) {
			ModuleVersion moduleVersion;

			moduleVersion = new ModuleVersion(module.getNodePath(), version);

			alwaysNeverYesNoAskUserResponseAlsoProcessDynamicVersion = Util.getInfoAlwaysNeverYesNoAskUserResponseAndHandleAsk(
					runtimePropertiesPlugin,
					UniformNewDynamicVersionPluginImpl.RUNTIME_PROPERTY_ALSO_PROCESS_DYNAMIC_VERSION,
					userInteractionCallbackPlugin,
					MessageFormat.format(UniformNewDynamicVersionPluginImpl.resourceBundle.getString(UniformNewDynamicVersionPluginImpl.MSG_PATTERN_KEY_DO_YOU_WANT_TO_PROCESS_DYNAMIC_VERSION), moduleVersion));

			// If already dynamic Version's must not be changed, we return immediately with the
			// same Version.
			if (alwaysNeverYesNoAskUserResponseAlsoProcessDynamicVersion.isNever()) {
				userInteractionCallbackPlugin.provideInfo(MessageFormat.format(UniformNewDynamicVersionPluginImpl.resourceBundle.getString(UniformNewDynamicVersionPluginImpl.MSG_PATTERN_KEY_DYNAMIC_VERSION_NOT_TO_BE_PROCESSED), moduleVersion));
				return version;
			} else if (alwaysNeverYesNoAskUserResponseAlsoProcessDynamicVersion.isNoAsk()) {
				return version;
			}

			if (alwaysNeverYesNoAskUserResponseAlsoProcessDynamicVersion.isAlways()) {
				userInteractionCallbackPlugin.provideInfo(MessageFormat.format(UniformNewDynamicVersionPluginImpl.resourceBundle.getString(UniformNewDynamicVersionPluginImpl.MSG_PATTERN_KEY_DYNAMIC_VERSION_TO_BE_PROCESSED), moduleVersion));
			}
		}

		// Here we know a new version is required (even if the current Version is
		// dynamic). versionNewDynamic will have been initialized above, but may be null.

		if (versionNewDynamic == null) {
			// null cannot be returned here.
			versionNewDynamic = this.handleReuseDynamicVersion(version);
		}

		// If the new dynamic Version is equal to the current one, the user will have
		// been informed by this.handleSpecificDynamicVersion.
		if (versionNewDynamic.equals(version)) {
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

			userInteractionCallbackPlugin.provideInfo(MessageFormat.format(UniformNewDynamicVersionPluginImpl.resourceBundle.getString(UniformNewDynamicVersionPluginImpl.MSG_PATTERN_KEY_NEW_DYNAMIC_VERSION_DOES_NOT_EXIST), module, versionNewDynamic));

			if (alwaysNeverAskUserResponseCanReuseBaseVersion.isAlways()) {
				userInteractionCallbackPlugin.provideInfo(MessageFormat.format(UniformNewDynamicVersionPluginImpl.resourceBundle.getString(UniformNewDynamicVersionPluginImpl.MSG_PATTERN_KEY_BASE_VERSION_AUTOMATICALLY_REUSED), new ModuleVersion(module.getNodePath(), version), versionNewDynamic, versionReuseBase));
				versionBase = versionReuseBase;

				if (!scmPlugin.isVersionExists(versionBase)) {
					userInteractionCallbackPlugin.provideInfo(MessageFormat.format(UniformNewDynamicVersionPluginImpl.resourceBundle.getString(UniformNewDynamicVersionPluginImpl.MSG_PATTERN_KEY_AUTOMATICALLY_REUSED_BASE_VERSION_DOES_NOT_EXIST), module.getNodePath(), versionNewDynamic, versionReuseBase));

					versionBase =
							Util.getInfoVersion(
									null,
									scmPlugin,
									userInteractionCallbackPlugin,
									MessageFormat.format(UniformNewDynamicVersionPluginImpl.resourceBundle.getString(UniformNewDynamicVersionPluginImpl.MSG_PATTERN_KEY_INPUT_NEW_BASE_VERSION), new ModuleVersion(module.getNodePath(), version), versionNewDynamic),
									scmPlugin.getDefaultVersion());
				}
			} else {
				versionBase =
						Util.getInfoVersion(
								null,
								scmPlugin,
								userInteractionCallbackPlugin,
								MessageFormat.format(UniformNewDynamicVersionPluginImpl.resourceBundle.getString(UniformNewDynamicVersionPluginImpl.MSG_PATTERN_KEY_INPUT_NEW_BASE_VERSION), new ModuleVersion(module.getNodePath(), version), versionNewDynamic),
								(versionReuseBase != null) ? versionReuseBase : scmPlugin.getDefaultVersion());

				runtimePropertiesPlugin.setProperty(null, UniformNewDynamicVersionPluginImpl.RUNTIME_PROPERTY_REUSE_BASE_VERSION, versionBase.toString());

				// The result is not useful. We only want to adjust the runtime property which
				// will be reused the next time around.
				Util.getInfoAlwaysNeverAskUserResponseAndHandleAsk(
						runtimePropertiesPlugin,
						UniformNewDynamicVersionPluginImpl.RUNTIME_PROPERTY_CAN_REUSE_BASE_VERSION,
						userInteractionCallbackPlugin,
						MessageFormat.format(UniformNewDynamicVersionPluginImpl.resourceBundle.getString(UniformNewDynamicVersionPluginImpl.MSG_PATTERN_KEY_AUTOMATICALLY_REUSE_BASE_VERSION), versionBase));
			}

			byReferenceVersionBase.object = versionBase;
		}

		return versionNewDynamic;
	}
}
