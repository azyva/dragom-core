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
import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.Version;
import org.azyva.dragom.model.VersionType;
import org.azyva.dragom.model.plugin.NewDynamicVersionPlugin;
import org.azyva.dragom.model.plugin.ScmPlugin;

/**
 * Factory for NewDynamicVersionPlugin that implements a strategy for development
 * workflow that uses development phases.
 *
 * See the NewStaticVersionPhasePlugin sibling for general information about the
 * concept of development phases and about freezing the current phase.
 *
 * After having frozen the current phase, SwitchToDynamicVersion and this plugin
 * are used to change the ArtifactVersion to that corresponding to the new phase
 * while keeping the same original dynamic Version. This is done in a deceptively
 * simple manner by simply returning that the current dynamic Version must be kept
 * and let SwitchToDynamicVersion simply proceed to ensure that the
 * ArtifactVersion is as determined by the ArtifactVersionMapperPlugin which is
 * expected to take into account the new phase.
 *
 * If the current Version is not dynamic, it is expected to be the static Version
 * created when freezing the previous phase. In that case, the (existing) base
 * Version of that static Version is returned, which is expected to the the
 * development version for the project on which various static Version
 * corresponding to the phases are created.
 *
 * @author David Raymond
 */
public class PhaseNewDynamicVersionPluginImpl extends ModulePluginAbstractImpl implements NewDynamicVersionPlugin {

	public PhaseNewDynamicVersionPluginImpl(Module module) {
		super(module);
	}

	@Override
	public Version getVersionNewDynamic(Version version, ByReference<Version> byReferenceVersionBase) {
		ScmPlugin scmPlugin;
		Version versionNewDynamic;
		ScmPlugin.BaseVersion baseVersion;

		if (version.getVersionType() == VersionType.DYNAMIC) {
			return version;
		}

		scmPlugin = this.getModule().getNodePlugin(ScmPlugin.class, null);

		baseVersion = scmPlugin.getBaseVersion(version);

		if (baseVersion == null) {
			throw new RuntimeException("Base version for version " + version + " of module " + this.getModule() + " could not be found.");
		}

		versionNewDynamic = baseVersion.versionBase;

		if (versionNewDynamic.getVersionType() != VersionType.DYNAMIC) {
			throw new RuntimeException("Base version " + versionNewDynamic + " of current version " + version + " of module " + this.getModule() + " is not dynamic.");
		}

		return versionNewDynamic;
	}
}
