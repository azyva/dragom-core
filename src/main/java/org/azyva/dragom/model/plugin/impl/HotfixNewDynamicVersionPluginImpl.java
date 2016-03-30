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

import org.azyva.dragom.apiutil.ByReference;
import org.azyva.dragom.execcontext.plugin.UserInteractionCallbackPlugin;
import org.azyva.dragom.execcontext.support.ExecContextHolder;
import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.ModuleVersion;
import org.azyva.dragom.model.Version;
import org.azyva.dragom.model.VersionType;
import org.azyva.dragom.model.plugin.NewDynamicVersionPlugin;
import org.azyva.dragom.model.plugin.ScmPlugin;
import org.azyva.dragom.util.RuntimeExceptionUserError;

/**
 * {@link NewDynamicVersionPlugin} that implements a strategy for hotfixes.
 * <p>
 * The current {@link Version} must be static.
 * <p>
 * The strategy is such that the new dynamic Version is created directly from the
 * current Version itself.
 *
 * @author David Raymond
 */
public class HotfixNewDynamicVersionPluginImpl extends NewDynamicVersionPluginBaseImpl implements NewDynamicVersionPlugin {
	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_VERSION_MUST_BE_STATIC = "VERSION_MUST_BE_STATIC";

	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_NEW_DYNAMIC_VERSION_DOES_NOT_EXIST_CURRENT_VERSION_BASE = "NEW_DYNAMIC_VERSION_DOES_NOT_EXIST_CURRENT_VERSION_BASE";

	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_NEW_DYNAMIC_VERSION_ALREADY_EXISTS_CURRENT_VERSION_NOT_BASE = "NEW_DYNAMIC_VERSION_ALREADY_EXISTS_CURRENT_VERSION_NOT_BASE";

	/**
	 * ResourceBundle specific to this class.
	 */
	private static final ResourceBundle resourceBundle = ResourceBundle.getBundle(HotfixNewDynamicVersionPluginImpl.class.getName() + "ResourceBundle");

	public HotfixNewDynamicVersionPluginImpl(Module module) {
		super(module);
	}

	@Override
	public Version getVersionNewDynamic(Version version, ByReference<Version> byReferenceVersionBase) {
		UserInteractionCallbackPlugin userInteractionCallbackPlugin;
		Module module;
		ScmPlugin scmPlugin;
		Version versionNewDynamic;

		userInteractionCallbackPlugin = ExecContextHolder.get().getExecContextPlugin(UserInteractionCallbackPlugin.class);
		module = this.getModule();
		scmPlugin = module.getNodePlugin(ScmPlugin.class, null);

		if (version.getVersionType() != VersionType.STATIC) {
			throw new RuntimeExceptionUserError(MessageFormat.format(HotfixNewDynamicVersionPluginImpl.resourceBundle.getString(HotfixNewDynamicVersionPluginImpl.MSG_PATTERN_KEY_VERSION_MUST_BE_STATIC), new ModuleVersion(module.getNodePath(), version)));
		}

		versionNewDynamic = this.handleReuseDynamicVersion(version);

		// If the new dynamic Version is equal to the current one, the user will have
		// been informed by this.handleReuseDynamicVersion.
		if (versionNewDynamic.equals(version)) {
			return version;
		}

		// Here versionNew holds the new version to switch to. If it does not exist we
		// specify to use the current Version as the base. If it does exist, we validate
		// that its base Version is the current one.

		if (!scmPlugin.isVersionExists(versionNewDynamic)) {
			userInteractionCallbackPlugin.provideInfo(MessageFormat.format(HotfixNewDynamicVersionPluginImpl.resourceBundle.getString(HotfixNewDynamicVersionPluginImpl.MSG_PATTERN_KEY_NEW_DYNAMIC_VERSION_DOES_NOT_EXIST_CURRENT_VERSION_BASE), module, versionNewDynamic, version));
			byReferenceVersionBase.object = version;
		} else {
			ScmPlugin.BaseVersion baseVersion;

			baseVersion = scmPlugin.getBaseVersion(versionNewDynamic);

			if (!baseVersion.versionBase.equals(version)) {
				throw new RuntimeExceptionUserError(MessageFormat.format(HotfixNewDynamicVersionPluginImpl.resourceBundle.getString(HotfixNewDynamicVersionPluginImpl.MSG_PATTERN_KEY_NEW_DYNAMIC_VERSION_ALREADY_EXISTS_CURRENT_VERSION_NOT_BASE), module, versionNewDynamic, version));
			}
		}

		return versionNewDynamic;
	}
}
