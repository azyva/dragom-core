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

import org.azyva.dragom.execcontext.plugin.RuntimePropertiesPlugin;
import org.azyva.dragom.execcontext.plugin.UserInteractionCallbackPlugin;
import org.azyva.dragom.execcontext.support.ExecContextHolder;
import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.ModuleVersion;
import org.azyva.dragom.model.Version;
import org.azyva.dragom.model.VersionType;
import org.azyva.dragom.model.plugin.NewStaticVersionPlugin;
import org.azyva.dragom.util.AlwaysNeverAskUserResponse;
import org.azyva.dragom.util.Util;
/**
 * Factory for NewStaticVersionPlugin that implements a strategy involving uniform
 * versions across multiple modules for which a new static Version needs to be
 * created.
 *
 * This is appropriate when static Version are associated with a specific release.
 * Often the release date is used as the Version.
 *
 * The initial value for the revision component is 1 and the default number of
 * decimal positions for revision is 2, as in 2015-08-01.01.
 *
 * @author David Raymond
 */
public class UniformNewStaticVersionPluginImpl extends NewStaticVersionPluginBaseImpl implements NewStaticVersionPlugin {
	private static final String RUNTIME_PROPERTY_CAN_REUSE_STATIC_VERSION_PREFIX = "CAN_REUSE_STATIC_VERSION_PREFIX";
	private static final String RUNTIME_PROPERTY_REUSE_STATIC_VERSION_PREFIX = "REUSE_STATIC_VERSION_PREFIX";
	private static final int INITIAL_REVISION = 1;
	private static final int DEFAULT_REVISION_DECIMAL_POSITION_COUNT = 2;

	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_NEW_STATIC_VERSION_PREFIX_AUTOMATICALLY_REUSED = "NEW_STATIC_VERSION_PREFIX_AUTOMATICALLY_REUSED";

	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_INPUT_NEW_STATIC_VERSION_PREFIX = "INPUT_NEW_STATIC_VERSION_PREFIX";

	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_AUTOMATICALLY_REUSE_NEW_STATIC_VERSION_PREFIX = "AUTOMATICALLY_REUSE_NEW_STATIC_VERSION_PREFIX";

	/**
	 * ResourceBundle specific to this class.
	 */
	private static final ResourceBundle resourceBundle = ResourceBundle.getBundle(UniformNewStaticVersionPluginImpl.class.getName() + "ResourceBundle");

	public UniformNewStaticVersionPluginImpl(Module module) {
		super(module);

		this.setInitialRevision(UniformNewStaticVersionPluginImpl.INITIAL_REVISION);
		this.setDefaultRevisionDecimalPositionCount(UniformNewStaticVersionPluginImpl.DEFAULT_REVISION_DECIMAL_POSITION_COUNT);
	}

	@Override
	public Version getVersionNewStatic(Version versionDynamic) {
		Version versionStaticPrefix;
		Version versionNewStatic;

		this.validateVersionDynamic(versionDynamic);

		versionNewStatic = this.handleSpecificStaticVersion(versionDynamic);

		if (versionNewStatic != null) {
			return versionNewStatic;
		}

		versionNewStatic = this.handleExistingEquivalentStaticVersion(versionDynamic);

		if (versionNewStatic != null) {
			return versionNewStatic;
		}

		// Here we know we do not have a new static Version. We therefore need to get a
		// static Version prefix so that we can calculate a new static Version.

		versionStaticPrefix = this.handleSpecificStaticVersionPrefix(versionDynamic);

		if (versionStaticPrefix == null) {
			// If we do not have a static version prefix, interact with the user to establish
			// one. This may involve reusing a previously specified one.

			versionStaticPrefix = this.getStaticVersionPrefix(versionDynamic);

			if (versionStaticPrefix == null) {
				return null;
			}
		}

		return this.getNewStaticVersionFromPrefix(versionDynamic, versionStaticPrefix);
	}

	/**
	 * Interact with the user to get a static Version prefix to use for getting a
	 * new static Version.
	 *
	 * @param versionDynamic Dynamic Version.
	 * @return Static Version prefix.
	 */
	private Version getStaticVersionPrefix(Version versionDynamic) {
		RuntimePropertiesPlugin runtimePropertiesPlugin;
		UserInteractionCallbackPlugin userInteractionCallbackPlugin;
		String runtimeProperty;
		AlwaysNeverAskUserResponse alwaysNeverAskUserResponseCanReuseStaticVersionPrefix;
		Version versionReuseStaticPrefix;
		Version versionStaticPrefix;

		runtimePropertiesPlugin = ExecContextHolder.get().getExecContextPlugin(RuntimePropertiesPlugin.class);
		userInteractionCallbackPlugin = ExecContextHolder.get().getExecContextPlugin(UserInteractionCallbackPlugin.class);

		alwaysNeverAskUserResponseCanReuseStaticVersionPrefix = AlwaysNeverAskUserResponse.valueOfWithAskDefault(runtimePropertiesPlugin.getProperty(this.getModule(), UniformNewStaticVersionPluginImpl.RUNTIME_PROPERTY_CAN_REUSE_STATIC_VERSION_PREFIX));

		runtimeProperty = runtimePropertiesPlugin.getProperty(this.getModule(), UniformNewStaticVersionPluginImpl.RUNTIME_PROPERTY_REUSE_STATIC_VERSION_PREFIX);

		if (runtimeProperty != null) {
			versionReuseStaticPrefix = new Version(runtimeProperty);
		} else {
			versionReuseStaticPrefix = null;

			if (alwaysNeverAskUserResponseCanReuseStaticVersionPrefix.isAlways()) {
				// Normally if the runtime property CAN_REUSE_STATIC_VERSION_PREFIX is ALWAYS the
				// REUSE_STATIC_VERSION_PREFIX runtime property should also be set. But since these
				// properties are independent and stored externally, it can happen that they
				// are not synchronized. We make an adjustment here to avoid problems.
				alwaysNeverAskUserResponseCanReuseStaticVersionPrefix = AlwaysNeverAskUserResponse.ASK;
			}
		}

		if (alwaysNeverAskUserResponseCanReuseStaticVersionPrefix.isAlways()) {
			userInteractionCallbackPlugin.provideInfo(MessageFormat.format(UniformNewStaticVersionPluginImpl.resourceBundle.getString(UniformNewStaticVersionPluginImpl.MSG_PATTERN_KEY_NEW_STATIC_VERSION_PREFIX_AUTOMATICALLY_REUSED), new ModuleVersion(this.getModule().getNodePath(), versionDynamic), versionReuseStaticPrefix));
			return versionReuseStaticPrefix;
		} else {
			versionReuseStaticPrefix =
					Util.getInfoVersion(
							VersionType.STATIC,
							null,
							userInteractionCallbackPlugin,
							MessageFormat.format(UniformNewStaticVersionPluginImpl.resourceBundle.getString(UniformNewStaticVersionPluginImpl.MSG_PATTERN_KEY_INPUT_NEW_STATIC_VERSION_PREFIX), new ModuleVersion(this.getModule().getNodePath(), versionDynamic)),
							versionReuseStaticPrefix);

			runtimePropertiesPlugin.setProperty(null, UniformNewStaticVersionPluginImpl.RUNTIME_PROPERTY_REUSE_STATIC_VERSION_PREFIX, versionReuseStaticPrefix.toString());
			versionStaticPrefix = versionReuseStaticPrefix;

			// The result is not useful. We only want to adjust the runtime property which
			// will be reused the next time around.
			Util.getInfoAlwaysNeverAskUserResponseAndHandleAsk(
					runtimePropertiesPlugin,
					UniformNewStaticVersionPluginImpl.RUNTIME_PROPERTY_CAN_REUSE_STATIC_VERSION_PREFIX,
					userInteractionCallbackPlugin,
					MessageFormat.format(UniformNewStaticVersionPluginImpl.resourceBundle.getString(UniformNewStaticVersionPluginImpl.MSG_PATTERN_KEY_AUTOMATICALLY_REUSE_NEW_STATIC_VERSION_PREFIX), versionReuseStaticPrefix));

			return versionStaticPrefix;
		}
	}
}
