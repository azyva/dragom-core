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

package org.azyva.dragom.maven;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.azyva.dragom.model.ArtifactGroupId;

public class PomUtil {
	public static Set<ArtifactGroupId> getSetArtifactGroupIdAggregator(Path pathPom) {
		Set<ArtifactGroupId> setArtifactGroupIdAggregator;

		setArtifactGroupIdAggregator = new HashSet<ArtifactGroupId>();

		PomUtil.traverseSubmodulesForSetArtifactGroupIdAggregator(pathPom, setArtifactGroupIdAggregator);

		return setArtifactGroupIdAggregator;
	}

	private static void traverseSubmodulesForSetArtifactGroupIdAggregator(Path pathPom, Set<ArtifactGroupId> setArtifactGroupIdAggregator) {
		Pom pom;
		String groupId;
		String artifactId;
		List<String> listSubmodules;

		pom = new Pom();
		pom.setPathPom(pathPom);
		pom.loadPom();

		groupId = pom.getResolvedGroupId();
		artifactId = pom.getArtifactId();

		if ((groupId == null) || (artifactId == null)) {
			throw new RuntimeException("The POM " + pathPom + " does not specify a groupId or an artifactId.");
		}

		setArtifactGroupIdAggregator.add(new ArtifactGroupId(groupId, artifactId));

		listSubmodules = pom.getListSubmodule();

		for (String submodule: listSubmodules) {
			PomUtil.traverseSubmodulesForSetArtifactGroupIdAggregator(pathPom.getParent().resolve(submodule).resolve("pom.xml"), setArtifactGroupIdAggregator);
		}
	}
}
