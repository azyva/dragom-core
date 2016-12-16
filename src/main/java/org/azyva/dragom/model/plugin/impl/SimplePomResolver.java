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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.azyva.dragom.execcontext.plugin.WorkspacePlugin;
import org.azyva.dragom.execcontext.support.ExecContextHolder;
import org.azyva.dragom.maven.Pom;
import org.azyva.dragom.maven.PomAggregation;
import org.azyva.dragom.model.ArtifactGroupId;
import org.azyva.dragom.model.ArtifactVersion;
import org.azyva.dragom.model.Model;
import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.Version;
import org.azyva.dragom.model.plugin.ArtifactVersionMapperPlugin;
import org.azyva.dragom.model.plugin.NodePlugin;
import org.azyva.dragom.model.plugin.ScmPlugin;

/**
 * Simple implementation of {@link org.azyva.dragom.maven.Pom.PomResolver}.
 *
 * <p>While we are in the {@link NodePlugin} implementation package, this is not a
 * NodePlugin. Is is useful for NodePlugin's related to Maven.
 *
 * <p>This class maintains a List of {@link PomAggregation} from which
 * {@link Pom}'s can be resolved. For each resolved Pom given a GAV, it remembers
 * the resolved Pom. For a new GAV, it asks each PomAggregation in turn until the
 * Pom can be resolved. If no PomAggregation can resolve the requested Pom, it
 * interacts with Dragom to find and load the new PomAggregation which will
 * resolve the requested Pom.
 *
 * <p>No assumption is made about the similar groupIds and artifactIds which are
 * generally coverred by a given PomAggregation. The only assumption is that
 * within a given PomAggregation, all Pom's have the same version, which is that
 * of the PomAggregation itself.
 *
 * <p>Versions are threfore also considered since multiple versions of the same
 * groupId and artifactId can exist.
 *
 * @author David Raymond
 */
public class SimplePomResolver implements Pom.PomResolver {
  /**
   * List of PomAggregation.
   */
  List<PomAggregation> listPomAggregation;

  /**
   * Represents a GAV.
   */
  private static class Gav {
    /**
     * ArtifactGroupId.
     */
    ArtifactGroupId artifactGroupId;

    /**
     * Version.
     */
    String version;

    /**
     * Constructor.
     *
     * @param artifactGroupId ArtifactGroupId.
     * @param version Version.
     */
    public Gav(ArtifactGroupId artifactGroupId, String version) {
      this.artifactGroupId = artifactGroupId;
      this.version = version;
    }

    @Override
    public int hashCode() {
      final int prime = 31;
      int result;

      result = 1;

      result = prime * result + this.artifactGroupId.hashCode();
      result = prime * result + this.version.hashCode();

      return result;
    }

    @Override
    public boolean equals(Object other) {
      Gav gavOther;

      if (this == other) {
        return true;
      }

      gavOther = (Gav)other;

      return    this.artifactGroupId.equals(gavOther.artifactGroupId)
             && this.version.equals(gavOther.version);
    }
  }

  /**
   * Map of GAV to resolved Pom.
   */
  private Map<Gav, Pom> mapGavPom;

  /**
   * Model.
   */
  Model model;

  /**
   * Constructor.
   *
   * @param pomAggregationInitial Initial PomAggregation. Can be null.
   */
  public SimplePomResolver(PomAggregation pomAggregationInitial) {
    this.listPomAggregation = new ArrayList<PomAggregation>();

    if (pomAggregationInitial != null) {
      this.listPomAggregation.add(pomAggregationInitial);
    }

    this.mapGavPom = new HashMap<Gav, Pom>();
    this.model = ExecContextHolder.get().getModel();
  }

  @Override
  public Pom resolve(String groupId, String artifactId, String stringVersion) throws Pom.ResolveException {
    ArtifactGroupId artifactGroupId;
    Gav gav;
    Pom pom;
    Module module;
    ArtifactVersionMapperPlugin artifactVersionMapperPlugin;
    ScmPlugin scmPlugin;
    WorkspacePlugin workspacePlugin;
    Version version;
    Path pathModuleWorkspace;
    PomAggregation pomAggregation;

    artifactGroupId = new ArtifactGroupId(groupId, artifactId);
    gav = new Gav(artifactGroupId, stringVersion);

    pom = this.mapGavPom.get(gav);

    if (pom != null) {
      return pom;
    }

    for(PomAggregation pomAggregation2: this.listPomAggregation) {
      pom = pomAggregation2.getPom(artifactGroupId);

      if ((pom != null) && (pom.getEffectiveVersion().equals(stringVersion))) {
        this.mapGavPom.put(gav, pom);
        return pom;
      }
    }

    module = this.model.findModuleByArtifactGroupId(artifactGroupId);

    if (module == null) {
      throw new Pom.ResolveException(artifactGroupId);
    }

    scmPlugin = module.getNodePlugin(ScmPlugin.class, null);
    artifactVersionMapperPlugin = module.getNodePlugin(ArtifactVersionMapperPlugin.class, null);
    workspacePlugin = ExecContextHolder.get().getExecContextPlugin(WorkspacePlugin.class);

    version = artifactVersionMapperPlugin.mapArtifactVersionToVersion(new ArtifactVersion(stringVersion));

    pathModuleWorkspace = scmPlugin.checkoutSystem(version);

    try {
      pomAggregation = new PomAggregation(pathModuleWorkspace.resolve("pom.xml"));
    } finally {
    	workspacePlugin.releaseWorkspaceDir(pathModuleWorkspace);
    }

    this.listPomAggregation.add(pomAggregation);

    pom = pomAggregation.getPom(artifactGroupId);

    this.mapGavPom.put(gav, pom);

    return pom;
  }
}
