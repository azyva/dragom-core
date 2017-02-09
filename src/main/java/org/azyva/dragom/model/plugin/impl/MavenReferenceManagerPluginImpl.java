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
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.ResourceBundle;
import java.util.Set;

import org.azyva.dragom.apiutil.ByReference;
import org.azyva.dragom.execcontext.ExecContext;
import org.azyva.dragom.execcontext.plugin.RuntimePropertiesPlugin;
import org.azyva.dragom.execcontext.plugin.UserInteractionCallbackPlugin;
import org.azyva.dragom.execcontext.support.ExecContextHolder;
import org.azyva.dragom.maven.Pom;
import org.azyva.dragom.maven.PomAggregation;
import org.azyva.dragom.model.ArtifactGroupId;
import org.azyva.dragom.model.ArtifactVersion;
import org.azyva.dragom.model.Model;
import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.ModuleVersion;
import org.azyva.dragom.model.Version;
import org.azyva.dragom.model.plugin.ArtifactVersionMapperPlugin;
import org.azyva.dragom.model.plugin.ReferenceManagerPlugin;
import org.azyva.dragom.reference.Reference;
import org.azyva.dragom.util.RuntimeExceptionUserError;
import org.azyva.dragom.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for ReferenceManagerPlugin that supports Maven modules.
 *
 * Maven aggregator modules are supported but all submodules must specify the same
 * version as the main module. Submodules may have any parent, but if the parent
 * of a submodule is its containing module it should inherit its version. If it
 * does not, its version must be the same as that of its parent. If the parent of
 * a submodule is not its containing module, then it should not be a submodule
 * within the main module and it must specify its version (which must be the same
 * as that of the main module).
 *
 * This class uses class Pom to manage the references expressed within the  POM
 * files. The types of references it can manage is thefore limited to those
 * managed by that class.
 *
 * @author David Raymond
 */
public class MavenReferenceManagerPluginImpl extends ModulePluginAbstractImpl implements ReferenceManagerPlugin {
  /**
   * Logger for the class.
   */
  private static final Logger logger = LoggerFactory.getLogger(MavenArtifactVersionManagerPluginImpl.class);

  /**
   * Model property which specifies the base groupId that a referenced artifact
   * must have to be identified as a {@link Module}. If a referenced artifact has a
   * groupId which starts with the value of this property, but no Module can be
   * found corresponding to this referenced artifact, an error message is written to
   * the log'.
   *
   * <p>See {@link #RUNTIME_PROPERTY_IND_EXCEPTION_WHEN_MODULE_NOT_FOUND}.
   *
   * <p>If this property is not specified, finding a corresponding Module is always
   * attempted.
   */
  private static final String MODEL_PROPERTY_BASE_GROUP_ID_MODULE = "BASE_GROUP_ID_MODULE";

  /**
   * Runtime property that specifies how to handle the situations where a
   * {@link Module} cannot be found corresponding to a referenced artifact. See
   * {@link #MODEL_PROPERTY_BASE_GROUP_ID_MODULE}.
   *
   * <p>If true, an exception is raised and processing stops. If false the process
   * recovers by treating the artifact as not being known to Dragom by setting the
   * ModuleVersion to null.
   */
  private static final String RUNTIME_PROPERTY_IND_EXCEPTION_WHEN_MODULE_NOT_FOUND = "IND_EXCEPTION_WHEN_MODULE_NOT_FOUND";

  /**
   * Exceptional condition representing an artifact that was found to be produced by
   * a Module, but the POM(s) of the ModuleVersion Module that could not be found
   * corresponding to an artifact specified by the user.
   */
  private static final String EXECEPTIONAL_COND_ARTIFACT_IN_MODULE_BUT_NOT_IN_POM = "ARTIFACT_IN_MODULE_BUT_NOT_IN_POM";

  /**
   * See description in ResourceBundle.
   */
  protected static final String MSG_PATTERN_KEY_ERROR_INVALID_REFERENCED_ARTIFACT = "ERROR_INVALID_REFERENCED_ARTIFACT";

  /**
   * ResourceBundle specific to this class.
   */
  protected static final ResourceBundle resourceBundle = ResourceBundle.getBundle(MavenReferenceManagerPluginImpl.class.getName() + "ResourceBundle");


  /**
   * Base groupId that a referenced artifact must have to be identified as a
   * {@link Module}. See {@link #MODEL_PROPERTY_BASE_GROUP_ID_MODULE}.
   */
  private String baseGroupIdModule;

  /**
   * Extra implementation data to be attached to {@link Reference}'s.
   * <p>
   * This will allow the methods updateReferenceVersion and
   * updateReferenceArtifactVersion to locate the reference.
   * <p>
   * See Reference for more information about constrained imposed on the extra
   * implementation data.
   * <p>
   * Note this is not a static class, so instances have an implicit reference to the
   * outer class which created it.
   */
  private class ReferenceImplData {
    /**
     * Path to the POM.
     */
    private Path pathPom;

    /**
     * ReferencedArtifact which include the information required for updating
     * references within {@link Pom}, and allows providing a useful string
     * representation of internal {@link Reference} details.
     */
    private Pom.ReferencedArtifact referencedArtifact;

    /**
     * Constructor.
     *
     * @param pathPom Path to the pom.
     * @param referencedArtifact ReferencedArtifact.
     */
    public ReferenceImplData(Path pathPom, Pom.ReferencedArtifact referencedArtifact) {
      this.pathPom = pathPom;
      this.referencedArtifact = referencedArtifact;
    }

    /**
     * @return The outer class instance for this referene. Allows validating that
     * {@link Reference}'s passed back to {@link #updateReferenceVersion} and
     * {@link #updateReferenceArtifactVersion} are Reference's created by this same
     * NodePlugin instance.
     */
    private MavenReferenceManagerPluginImpl getReferenceManagerPluginImpl() {
      return MavenReferenceManagerPluginImpl.this;
    }

    /**
     * References are often displayed to the user and need to be shown in a human-
     * friendly and not too cryptic way.
     *
     * Here ReferenceImplSpecific is specific to Maven and knowing this we can make
     * its textual representation as human-friendly as possible.
     */
    @Override
    public String toString() {
      StringBuilder stringBuilder;
      Pom.ReferencedArtifactType referencedArtifactType;

      stringBuilder = new StringBuilder();

      referencedArtifactType = this.referencedArtifact.getReferencedArtifactType();
      stringBuilder.append("ref ").append(this.pathPom).append(' ').append(referencedArtifactType);

      if (   (referencedArtifactType == Pom.ReferencedArtifactType.PROFILE_DEPENDENCY)
          || (referencedArtifactType == Pom.ReferencedArtifactType.PROFILE_DEPENDENCY_MANAGEMENT)) {

        stringBuilder.append('(').append(this.referencedArtifact.getProfile()).append(')');
      }

      return stringBuilder.toString();
    }

    @Override
    public int hashCode() {
      final int prime = 31;
      int result;

      result = 1;
      result = (prime * result) + this.pathPom.hashCode();
      result = (prime * result) + this.referencedArtifact.hashCode();

      return result;
    }

    @Override
    public boolean equals(Object other) {
      ReferenceImplData referenceImplDataOther;

      if (this == other) {
        return true;
      }

      if (!(other instanceof ReferenceImplData)) {
        return false;
      }

      referenceImplDataOther = (ReferenceImplData)other;

      if (!this.pathPom.equals(referenceImplDataOther.pathPom)) {
        return false;
      }

      if (!this.referencedArtifact.getReferencedArtifactType().equals(referenceImplDataOther.referencedArtifact.getReferencedArtifactType())) {
        return false;
      }

      if (this.referencedArtifact.getProfile() == null) {
        if (referenceImplDataOther.referencedArtifact.getProfile() != null) {
          return false;
        }
      } else if (!this.referencedArtifact.getProfile().equals(referenceImplDataOther.referencedArtifact.getProfile())) {
        return false;
      }

      return true;
    }
  }

  /**
   * Constructor.
   *
   * @param module Module.
   */
  public MavenReferenceManagerPluginImpl(Module module) {
    super(module);

    this.baseGroupIdModule = module.getProperty(MavenReferenceManagerPluginImpl.MODEL_PROPERTY_BASE_GROUP_ID_MODULE);
  }

  @Override
  public List<Reference> getListReference(Path pathModuleWorkspace) {
    Path pathPom;
    PomAggregation pomAggregation;
    String aggregationVersion;
    Set<ArtifactGroupId> setArtifactGroupIdAggregation;
    ExecContext execContext;
    UserInteractionCallbackPlugin userInteractionCallbackPlugin;
    RuntimePropertiesPlugin runtimePropertiesPlugin;
    boolean indExceptionWhenModuleNotFound;
    ArrayList<Reference> listReference;
    Model model;
    Pom.PomResolver pomResolver;

    pathPom = pathModuleWorkspace.resolve("pom.xml");
    pomAggregation = new PomAggregation(pathPom);
    aggregationVersion = pomAggregation.getPomMain().getVersion();

    setArtifactGroupIdAggregation = pomAggregation.getSetArtifactGroupId();

    execContext = ExecContextHolder.get();
    userInteractionCallbackPlugin = execContext.getExecContextPlugin(UserInteractionCallbackPlugin.class);
    runtimePropertiesPlugin = execContext.getExecContextPlugin(RuntimePropertiesPlugin.class);

    indExceptionWhenModuleNotFound = Util.isNotNullAndTrue(runtimePropertiesPlugin.getProperty(this.getModule(), MavenReferenceManagerPluginImpl.RUNTIME_PROPERTY_IND_EXCEPTION_WHEN_MODULE_NOT_FOUND));

    listReference = new ArrayList<Reference>();

    model = this.getModule().getModel();

    pomResolver = new SimplePomResolver(pomAggregation);

    for(Pom pom: pomAggregation.getCollectionPom()) {
      List<Pom.ReferencedArtifact> listReferencedArtifact;
      String version;

      version = pom.getEffectiveVersion();

      if (version == null) {
        throw new RuntimeException("The module " + this.getModule() + " does not define its artifact version in the POM " + pathPom + '.');
      }

      if (!version.equals(aggregationVersion)) {
        throw new RuntimeException("The submodule POM " + pathPom + " of module " + this.getModule() + " has version " + version + " which is not the same as that of its container " + aggregationVersion + '.');
      }

      listReferencedArtifact = pom.getListReferencedArtifact(EnumSet.allOf(Pom.ReferencedArtifactType.class), null ,null, null);

      for(Pom.ReferencedArtifact referencedArtifact: listReferencedArtifact) {
        ArtifactGroupId artifactGroupId;
        ArtifactVersion artifactVersion;
        Module module;
        ModuleVersion moduleVersion;
        Reference reference;

        try {
          artifactGroupId = new ArtifactGroupId(pom.resolveProperties(referencedArtifact.getGroupId(), pomResolver), pom.resolveProperties(referencedArtifact.getArtifactId(), pomResolver));
          artifactVersion = new ArtifactVersion(pom.resolveProperties(referencedArtifact.getVersion(), pomResolver));
        } catch (RuntimeException re) {
          MavenReferenceManagerPluginImpl.logger.error("ReferencedArtifact " + referencedArtifact + " could not be resolved and is ignored. Reason:", re);
          continue;
        }

        // We are not interested in references internal to the module.
        if (setArtifactGroupIdAggregation.contains(artifactGroupId)) {
          continue;
        }

        if ((this.baseGroupIdModule == null) || artifactGroupId.getGroupId().startsWith(this.baseGroupIdModule)) {
          module = model.findModuleByArtifactGroupId(artifactGroupId);

          if (module != null) {
            ArtifactVersionMapperPlugin artifactVersionMapperPlugin;

            artifactVersionMapperPlugin = module.getNodePlugin(ArtifactVersionMapperPlugin.class, null);

            moduleVersion = new ModuleVersion(module.getNodePath(), artifactVersionMapperPlugin.mapArtifactVersionToVersion(artifactVersion));

            // We probably could simply perform an object equality here since Module's are
            // singletons. But just in case, we compare their NodePath.
            if (module.getNodePath().equals(this.getNode().getNodePath())) {
              if (Util.handleToolResultAndContinueForExceptionalCond(this.getNode(), MavenReferenceManagerPluginImpl.EXECEPTIONAL_COND_ARTIFACT_IN_MODULE_BUT_NOT_IN_POM)) {
                userInteractionCallbackPlugin.provideInfo(MessageFormat.format(MavenReferenceManagerPluginImpl.resourceBundle.getString(MavenReferenceManagerPluginImpl.MSG_PATTERN_KEY_ERROR_INVALID_REFERENCED_ARTIFACT), pathModuleWorkspace, referencedArtifact.toString() + " (" + artifactGroupId + ':' + artifactVersion + ')', module));
                continue;
              } else {
                throw new RuntimeExceptionUserError(MessageFormat.format(MavenReferenceManagerPluginImpl.resourceBundle.getString(MavenReferenceManagerPluginImpl.MSG_PATTERN_KEY_ERROR_INVALID_REFERENCED_ARTIFACT), pathModuleWorkspace, referencedArtifact.toString() + " (" + artifactGroupId + ':' + artifactVersion + ')', module));
              }
            }
          } else {
            if (indExceptionWhenModuleNotFound) {
              throw new RuntimeException("A Module could not be found corresponding to ReferencedArtifact " + referencedArtifact + " ");
            } else {
              MavenReferenceManagerPluginImpl.logger.error("A Module could not be found corresponding to ReferencedArtifact " + referencedArtifact + "  Treating as an external artifact.");
              moduleVersion = null;
            }
          }
        } else {
          moduleVersion = null;
        }

        reference = new Reference(moduleVersion, artifactGroupId, artifactVersion, new ReferenceImplData(pathModuleWorkspace.relativize(pom.getPathPom()), referencedArtifact));

        listReference.add(reference);
      }
    }

    return listReference;
  }

  @Override
  public boolean updateReferenceVersion(Path pathModuleWorkspace, Reference reference, Version version, ByReference<Reference> byReferenceReference) {
    ReferenceImplData referenceImplData;
    Path pathPom;
    Pom pom;
    ArtifactVersionMapperPlugin artifactVersionMapperPlugin;
    ArtifactVersion artifactVersion;

    if (!(reference.getImplData() instanceof ReferenceImplData)) {
      throw new RuntimeException("Within " + this + ", reference extra implementation data must be of type ReferenceImplData.");
    }

    referenceImplData = (ReferenceImplData)reference.getImplData();

    if (referenceImplData.getReferenceManagerPluginImpl() != this) {
      // TODO: Have a more precise message. Maybe have a toString on a plugin.
      throw new RuntimeException("Within " + this + ", reference must have been produced by the same plugin instance.");
    }

    // TODO: Are we sure the path to the POM will be in a known location in the workspace?
    pathPom = pathModuleWorkspace.resolve(referenceImplData.pathPom);
    pom = new Pom();
    pom.setPathPom(pathPom);
    pom.loadPom();

    // We need to get the ArtifactVersionMapperPlugin associated with the referred-to
    // Module, not the current referencing Module.
    artifactVersionMapperPlugin = this.getModule().getModel().getModule(reference.getModuleVersion().getNodePath()).getNodePlugin(ArtifactVersionMapperPlugin.class, null);
    artifactVersion = artifactVersionMapperPlugin.mapVersionToArtifactVersion(version);

    if (byReferenceReference != null) {
      byReferenceReference.object = new Reference(new ModuleVersion(reference.getModuleVersion().getNodePath(), version), reference.getArtifactGroupId(), artifactVersion, referenceImplData);
    }

    if (!artifactVersion.toString().equals(reference.getArtifactVersion())) {
      // We need to recreate a Pom.ReferencedArtifact since we do not keep it around.
      // That is OK since we have all the required information.
      pom.setReferencedArtifactVersion(referenceImplData.referencedArtifact, artifactVersion.toString());
      pom.savePom();

      return true;
    } else {
      return false;
    }
  }

  @Override
  public boolean updateReferenceArtifactVersion(Path pathModuleWorkspace, Reference reference, ArtifactVersion artifactVersion, ByReference<Reference> byReferenceReference) {
    // TODO To be implemented
    throw new RuntimeException("Not implemented yet.");

  }
}
