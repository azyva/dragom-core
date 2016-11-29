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

package org.azyva.dragom.model.plugin.impl;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.azyva.dragom.maven.Pom;
import org.azyva.dragom.maven.PomUtil;
import org.azyva.dragom.model.ArtifactGroupId;
import org.azyva.dragom.model.ArtifactVersion;
import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.plugin.ArtifactVersionManagerPlugin;

/**
 * Factory for ArtifactVersionManagerPlugin that supports Maven modules.
 *
 * Maven aggregator modules are supported but all submodules must specify the same
 * version as the main module. Submodules may have any parent, but if the parent
 * of a submodule is its containing module it should inherit its version. If it
 * does not, its version must be the same as that of its parent. If the parent of
 * a submodule is not its containing module, then it should not be a submodule
 * within the main module and it must specify its version (which must be the same
 * as that of the main module).
 *
 * @author David Raymond
 */
public class MavenArtifactVersionManagerPluginImpl extends ModulePluginAbstractImpl implements ArtifactVersionManagerPlugin {
  public MavenArtifactVersionManagerPluginImpl(Module module) {
    super(module);
  }

  @Override
  public ArtifactVersion getArtifactVersion(Path pathModuleWorkspace) {
    //TODO maybe validate too. Not sure it is worth it as it will automatically be validated when saving.
    Pom pom;
    Path pathPom;
    String version;

    pom = new Pom();
    pathPom = pathModuleWorkspace.resolve("pom.xml");
    pom.setPathPom(pathPom);
    pom.loadPom();

    version = pom.getVersion();

    if (version == null) {
      throw new RuntimeException("The version of the artifacts is not explicitely set within POM " + pathPom + " of module " + pathModuleWorkspace + ". Maven allows this (the version being inherited from the parent), but Dragom does not allow this for a top-level module.");
    }

    if (version.contains("${")) {
      throw new RuntimeException("A property reference was found within version " + version + " in the POM " + pathPom + ".");
    }

    return new ArtifactVersion(version);
  }

  @Override
  public boolean setArtifactVersion(Path pathModuleWorkspace, ArtifactVersion artifactVersion) {
    Pom pom;
    Path pathPom;
    String priorVersion;
    Set<ArtifactGroupId> setArtifactGroupIdAggregator;

    pom = new Pom();
    pathPom = pathModuleWorkspace.resolve("pom.xml");
    pom.setPathPom(pathModuleWorkspace.resolve("pom.xml"));
    pom.loadPom();
    priorVersion = pom.getVersion();

    if (priorVersion == null) {
      throw new RuntimeException("The version of the artifacts is not explicitely set within POM " + pathPom + " of module " + pathModuleWorkspace + ". Maven allows this (the version being inherited from the parent), but Dragom does not allow this for a top-level module.");
    }

    if (priorVersion.contains("${")) {
      throw new RuntimeException("A property reference was found within version " + priorVersion + " in the POM " + pathPom + ".");
    }

    if (artifactVersion.toString().equals(priorVersion)) {
      // This class ensures that the version of submodules is the same as that of the
      // main module, so we do not need to worry about verifying the version of
      // submodules.
      return false;
    }

    /*
     * We need to get the set of ArtifactGroupId within the aggregation before doing
     * the actual traversal since there may be dependencies between modules and the
     * order in which they are traversed may not be such that dependencies are visited
     * before dependents.
     */
    setArtifactGroupIdAggregator = PomUtil.getSetArtifactGroupIdAggregator(pathPom);

    /* To initiate the traversal of the submodules, we simply treat the main module as
     * a submodule, after having initialized the required variables.
     */
    this.setArtifactVersionOnSubmodule(pathModuleWorkspace, setArtifactGroupIdAggregator, priorVersion, pathModuleWorkspace, artifactVersion.toString());

    return true;
  }

  /**
   * Traverses the tree of submodules within an aggregator module and sets the
   * version of each submodule.
   *
   * @param pathModuleWorkspace Path to the main aggregator module within the
   *   workspace. Used for error reporting.
   * @param setArtifactGroupIdAggregator Set of ArtifactGroupId within the aggregation.
   *   This set must be built before invoking the method. Used to validate the version
   *   of the dependencies within the aggregation, which must match the version of the
   *   main aggregator module.
   * @param priorVersion Version that submodules should have before the change. Used
   *   for validation.
   * @param pathSubmoduleWorkspace Path to the submodule within the workspace. This
   *   is within the main aggregator module.
   * @param newVersion New version to set. If null, nothing is modified and the
   *   modules are simply traversed to build setArtifactGroupIdAggregator.
   */
  private void setArtifactVersionOnSubmodule(Path pathModuleWorkspace, Set<ArtifactGroupId> setArtifactGroupIdAggregator, String priorVersion, Path pathSubmoduleWorkspace, String newVersion) {
    Pom pom;
    Path pathPom;
    String version;
    Pom.ReferencedArtifact referencedArtifactParent;
    ArtifactGroupId artifactGroupId;
    List<Pom.ReferencedArtifact> listReferencedArtifact;
    List<String> listSubmodules;

    pom = new Pom();
    pathPom = pathSubmoduleWorkspace.resolve("pom.xml");
    pom.setPathPom(pathPom);
    pom.loadPom();

    version = pom.getVersion();

    if ((version != null) && version.contains("${")) {
      throw new RuntimeException("A property reference was found within version " + version + " in the POM " + pathPom + ".");
    }

    referencedArtifactParent = pom.getParentReferencedArtifact();

    if (referencedArtifactParent != null) {
      if (referencedArtifactParent.getGroupId().contains("${") || referencedArtifactParent.getArtifactId().contains("${") || referencedArtifactParent.getVersion().contains("${")) {
        throw new RuntimeException("A property reference was found within referenced artifact " + referencedArtifactParent + " in the POM " + pathPom + ".");
      }

      artifactGroupId = new ArtifactGroupId(referencedArtifactParent.getGroupId(), referencedArtifactParent.getArtifactId());

      /* If the parent is within the aggregation its version must match that of the main
       * aggregator module. And if ever the version is also specified (generally it is
       * not specified and is inherited from the parent), it must also match.
       */
      if (setArtifactGroupIdAggregator.contains(artifactGroupId)) {
        if (!referencedArtifactParent.getVersion().equals(priorVersion)) {
          throw new RuntimeException("The parent " + referencedArtifactParent + " identified within " + pathPom + " is within the aggregation but its version does not match that of the main aggregator module " + pathModuleWorkspace + '.');
        }

        if ((version != null) && !version.equals(priorVersion)) {
          throw new RuntimeException("The parent " + referencedArtifactParent + " identified within " + pathPom + " is within the aggregation rooted at " + pathModuleWorkspace + ", the module also specifies a version but this version does not match that of the parent.");
        }

        pom.setReferencedArtifactVersion(referencedArtifactParent, newVersion);
      }
    } else {
      if (version == null) {
        throw new RuntimeException("The POM " + pathPom + " within the aggregation rooted at " + pathModuleWorkspace + " does not specify a parent and its version is not specified.");
      }

      if (!version.equals(priorVersion)) {
        throw new RuntimeException("The version of the POM " + pathPom + " does not match that of the main aggregator module " + pathModuleWorkspace + '.');
      }
    }

    if (version != null) {
      pom.setVersion(newVersion);
    }

    listReferencedArtifact = pom.getListReferencedArtifact(EnumSet.of(Pom.ReferencedArtifactType.DEPENDENCY, Pom.ReferencedArtifactType.DEPENDENCY_MANAGEMENT), null, null, null);

    for (Pom.ReferencedArtifact referencedArtifact: listReferencedArtifact) {
      if (referencedArtifact.getGroupId().contains("${") || referencedArtifact.getArtifactId().contains("${")) {
        throw new RuntimeException("A property reference was found within referenced artifact " + referencedArtifact + " in the POM " + pathPom + ".");
      }

      artifactGroupId = new ArtifactGroupId(referencedArtifact.getGroupId(), referencedArtifact.getArtifactId());

      /* If a referenced module is within the aggregation its version must match that of
       * the main aggregator module.
       */
      if (setArtifactGroupIdAggregator.contains(artifactGroupId)) {
        // We only allow these property references within the version of a reference to a
        // module within the aggregation.
        if (   !referencedArtifact.getVersion().equals("${version}")
            && !referencedArtifact.getVersion().equals("${pom.version}") // Deprecated but still supported.
            && !referencedArtifact.getVersion().equals("${project.version}")) {

          if (referencedArtifact.getVersion().contains("${")) {
            throw new RuntimeException("A property reference was found within version " + version + " in the POM " + pathPom + ".");
          }

          if (!referencedArtifact.getVersion().equals(priorVersion)) {
            throw new RuntimeException("The referenced module " + referencedArtifact + " identified within " + pathPom + " is within the aggregation but its version does not match that of the main aggregator module " + pathModuleWorkspace + " and is not one of the property references ${version}, ${pom.version} or ${project.version}.");
          }

          pom.setReferencedArtifactVersion(referencedArtifact, newVersion);
        }
      } else {
        if (referencedArtifact.getVersion().contains("${")) {
          throw new RuntimeException("A property reference was found within version " + version + " in the POM " + pathPom + ".");
        }
      }
    }

    pom.savePom();

    listSubmodules = pom.getListSubmodule();

    for (String submodule: listSubmodules) {
      this.setArtifactVersionOnSubmodule(pathModuleWorkspace, setArtifactGroupIdAggregator, priorVersion, pathSubmoduleWorkspace.resolve(submodule), newVersion);
    }
  }
}
