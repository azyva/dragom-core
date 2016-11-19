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

import java.nio.file.Path;

import org.azyva.dragom.execcontext.plugin.RuntimePropertiesPlugin;
import org.azyva.dragom.execcontext.plugin.WorkspacePlugin;
import org.azyva.dragom.execcontext.support.ExecContextHolder;
import org.azyva.dragom.model.ArtifactVersion;
import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.Version;
import org.azyva.dragom.model.VersionType;
import org.azyva.dragom.model.plugin.ArtifactVersionManagerPlugin;
import org.azyva.dragom.model.plugin.ArtifactVersionMapperPlugin;
import org.azyva.dragom.model.plugin.ScmPlugin;
import org.azyva.dragom.model.plugin.SelectStaticVersionPlugin;

/**
 * Factory for SelectStaticVersionPlugin that implements a strategy for a development
 * workflow that uses development phases.
 *
 * The idea is as follows. When a (development) project starts, a new dynamic
 * Version specific to the project is created and used throughout the development
 * effort. Assume that dynamic version is D/develop-myproject.
 *
 * The ArtifactVersion within that Version is variable and contains a suffix
 * specific to the current development phase. Assume that the initial development
 * phase is iteration01, so that the ArtifactVersion is
 * develop-myproject-iteration01-SNAPSHOT. During that phase artifacts having that
 * ArtifactVersion are therefore created (by continuous integration builds).
 *
 * At some point development for that project proceeds to a new phase
 * iteration02. Release and this SelectStaticVersionPlugin are used to
 * freeze the previous phase iteration01 before making that transition. This
 * plugin is therefore invoked with the runtime property CURRENT_PHASE set to the
 * current phase iteration01 so that a new static Version
 * S/develop-myproject-iteration01 is created.
 *
 * During the creation of that Version, Release attempts to adjust the
 * ArtifactVersion within the module. But the ArtifactVersionMapperPlugin is
 * expected to be configured in such a way that the ArtifactVersion corresponding
 * to the new static Version is so that no adjustment is required. Here we
 * actually map a dynamic ArtifactVersion to the new static Version, which is
 * generally considered incorrect. But since static Version's representing phases
 * are not released versions, using a static ArtifactVersion is not appropriate.
 * We use a dynamic ArtifactVersion instead, which happens to not really violate
 * any important release engineering rules. Specifically the important rule that a
 * static ArtifactVersion correspond to a static Version remains respected.
 *
 * In order to continue development in the new phase iteration02,
 * SwitchToDynamicVersion and the NewDynamicVersionPhasePlugin sibling are used to
 * change the ArtifactVersion to develop-myproject-iteration02-SNAPSHOT (while
 * keeping the same dynamic Version D/develop-myproject). See
 * PhaseSelectDynamicVersionPluginImpl for more information.
 *
 * @author David Raymond
 */
public class PhaseSelectStaticVersionPluginImpl extends SelectStaticVersionPluginBaseImpl implements SelectStaticVersionPlugin {
	private static final String RUNTIME_PROPERTY_CURRENT_PHASE = "CURRENT_PHASE";

	public PhaseSelectStaticVersionPluginImpl(Module module) {
		super(module);
	}

	@Override
	public Version selectStaticVersion(Version versionDynamic) {
		Version versionStaticSelected;
		RuntimePropertiesPlugin runtimePropertiesPlugin;
		String currentPhase;
		ScmPlugin scmPlugin;

		this.validateVersionDynamic(versionDynamic);

		runtimePropertiesPlugin = ExecContextHolder.get().getExecContextPlugin(RuntimePropertiesPlugin.class);
		currentPhase = runtimePropertiesPlugin.getProperty(this.getModule(), PhaseSelectStaticVersionPluginImpl.RUNTIME_PROPERTY_CURRENT_PHASE);

		if (currentPhase == null) {
			throw new RuntimeException("The property " + PhaseSelectStaticVersionPluginImpl.RUNTIME_PROPERTY_CURRENT_PHASE + " must be defined in the context of module " + this.getModule() + '.');
		}

		versionStaticSelected = new Version(VersionType.STATIC, versionDynamic.getVersion() + '-' + currentPhase);

		scmPlugin = this.getModule().getNodePlugin(ScmPlugin.class, null);

		if (!scmPlugin.isVersionExists(versionStaticSelected)) {
			ArtifactVersionManagerPlugin artifactVersionManagerPlugin;

			artifactVersionManagerPlugin = this.getModule().getNodePlugin(ArtifactVersionManagerPlugin.class, null);

			if (artifactVersionManagerPlugin != null) {
				Path pathModuleWorkspace;
				ArtifactVersion artifactVersion;
				ArtifactVersion artifactVersionFromSelectedStaticVersion;
				ArtifactVersionMapperPlugin artifactVersionMapperPlugin;
				WorkspacePlugin workspacePlugin;

				pathModuleWorkspace = scmPlugin.checkoutSystem(versionDynamic);
				artifactVersion = artifactVersionManagerPlugin.getArtifactVersion(pathModuleWorkspace);
				workspacePlugin = ExecContextHolder.get().getExecContextPlugin(WorkspacePlugin.class);
				workspacePlugin.releaseWorkspaceDir(pathModuleWorkspace);
				artifactVersionMapperPlugin = this.getModule().getNodePlugin(ArtifactVersionMapperPlugin.class, null);
				artifactVersionFromSelectedStaticVersion = artifactVersionMapperPlugin.mapVersionToArtifactVersion(versionStaticSelected);

				if (!artifactVersion.equals(artifactVersionFromSelectedStaticVersion)) {
					throw new RuntimeException("The current artifact version " + artifactVersion + " for the dynamic version " + versionDynamic + " of module " + this.getModule() + " does not correspond to the artifact version " + artifactVersionFromSelectedStaticVersion + " mapped from the new static version " + versionStaticSelected + '.');
				}
			}
		}

		return versionStaticSelected;
	}
}
