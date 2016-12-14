/*
 * Copyright 2015 - 2017 AZYVA INC. INC.
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
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.azyva.dragom.model.ArtifactGroupId;
import org.azyva.dragom.model.ModuleVersion;


/**
 * Represents an aggregation of {@link Pom}'s organized as a main Maven module
 * with its submodules and its submodule's submodules, etc.
 *
 * <p>All these Maven modules are expected to comme from the same
 * {@link ModuleVersion} within Dragom.
 *
 * <p>PomAggregation ensures that the Pom's (within the aggregation) all share the
 * same version.
 *
 * @author David Raymond
 */
public class PomAggregation {
  /**
   * Main Pom.
   */
  Pom pomMain;

  /**
   * Map of ArtifactGroupId to Pom's.
   */
  Map<ArtifactGroupId, Pom> mapArtifactGroupIdPom;

  /**
   * Constructor.
   *
   * @param pathMainPom Path to the main {@link Pom}.
   */
  public PomAggregation(Path pathMainPom) {
    // We use a LinkedHashMap to preserve insertion order, and in particular, to
    // ensure the main Pom is enumerated first.
    this.mapArtifactGroupIdPom = new LinkedHashMap<ArtifactGroupId, Pom>();
    this.pomMain = this.loadPom(pathMainPom);
  }

  /**
   * Loads a {@link Pom}.
   *
   * @param pathPom Path to the Pom.
   * @return Pom.
   */
  private Pom loadPom(Path pathPom) {
    Pom pom;

    pom = new Pom();
    pom.setPathPom(pathPom);
    pom.loadPom();

    this.mapArtifactGroupIdPom.put(new ArtifactGroupId(pom.getEffectiveGroupId(), pom.getArtifactId()), pom);

    for (String submodule: pom.getListSubmodule()) {
      this.loadPom(pathPom.getParent().resolve(submodule).resolve("pom.xml"));
    }

    return pom;
  }

  /**
   * @return Main Pom.
   */
  public Pom getPomMain() {
    return this.pomMain;
  }

  /**
   * @return The Set of ArtifactGroupId for this PomAggregation.
   */
  public Set<ArtifactGroupId> getSetArtifactGroupId() {
    return Collections.unmodifiableSet(this.mapArtifactGroupIdPom.keySet());
  }

  /**
   * @return Collection of the Pom's within the PomAggregation. The first one is the
   *   main one.
   */
  public Collection<Pom> getCollectionPom() {
    return Collections.unmodifiableCollection(this.mapArtifactGroupIdPom.values());
  }

  /**
   * Returns the Pom corresponding to an ArtifactGroupId.
   *
   * @param artifactGroupId ArtifactGroupId.
   * @return See description.
   */
  public Pom getPom(ArtifactGroupId artifactGroupId) {
    return this.mapArtifactGroupIdPom.get(artifactGroupId);
  }
}
