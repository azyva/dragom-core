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

import org.azyva.dragom.model.ArtifactVersion;
import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.Version;
import org.azyva.dragom.model.plugin.ArtifactVersionMapperPlugin;

/**
 * Factory for ArtifactVersionMapperPlugin that assumes a simple equivalence
 * between artifact versions and global versions.
 *
 * The ArtifactVersion class is used to manage artifact versions. See the
 * description of this class for some important assumptions made.
 *
 * @author David Raymond
 */
public class SimpleArtifactVersionMapperPluginImpl extends ModulePluginAbstractImpl implements ArtifactVersionMapperPlugin {
	public SimpleArtifactVersionMapperPluginImpl(Module module) {
		super(module);
	}

	@Override
	public Version mapArtifactVersionToVersion(ArtifactVersion artifactVersion) {
		return artifactVersion.getCorrespondingVersion();
	}

	@Override
	public ArtifactVersion mapVersionToArtifactVersion(Version version) {
		return version.getCorrespondingArtifactVersion();
	}
}