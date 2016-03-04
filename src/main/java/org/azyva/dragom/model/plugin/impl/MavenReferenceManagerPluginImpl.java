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

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.azyva.dragom.maven.Pom;
import org.azyva.dragom.maven.Pom.ReferencedArtifact;
import org.azyva.dragom.maven.PomUtil;
import org.azyva.dragom.model.ArtifactGroupId;
import org.azyva.dragom.model.ArtifactVersion;
import org.azyva.dragom.model.Model;
import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.ModuleVersion;
import org.azyva.dragom.model.Version;
import org.azyva.dragom.model.plugin.ArtifactVersionMapperPlugin;
import org.azyva.dragom.model.plugin.ReferenceManagerPlugin;
import org.azyva.dragom.reference.Reference;

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
	 * We override the class Reference to add implementation-specific fields that will
	 * allow the methods updateReferenceVersion and updateReferenceArtifactVersion to
	 * locate the reference.
	 *
	 * We preserve the immutable property of the base class.
	 */
	private class ReferenceImplSpecific extends Reference {
		/**
		 * Path to the pom.
		 */
		private Path pathPom;

		/**
		 * ReferencedArtifact.
		 */
		private Pom.ReferencedArtifact referencedArtifact;

		/**
		 * Constructor.
		 *
		 * @param moduleVersion ModuleVersion.
		 * @param artifactGroupId ArtifactGroupId.
		 * @param artifactVersion Artifact version.
		 * @param pathPom Path to the pom.
		 * @param referencedArtifact ReferencedArtifact.
		 */
		public ReferenceImplSpecific(ModuleVersion moduleVersion, ArtifactGroupId artifactGroupId, ArtifactVersion artifactVersion, Path pathPom, ReferencedArtifact referencedArtifact) {
			super(moduleVersion, artifactGroupId, artifactVersion);
			this.pathPom = pathPom;
			this.referencedArtifact = referencedArtifact;
		}

		/* To allow validating that references passed back to updateReferenceVersion and
		 * updateReferenceArtifactVersion are references created by this same plugin
		 * instance.
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

			stringBuilder = new StringBuilder();

			if (this.getModuleVersion() != null) {
				stringBuilder.append(this.getModuleVersion());

				stringBuilder.append(" (from parent ").append(this.pathPom).append("->").append(this.referencedArtifact).append(")");
			} else {
				stringBuilder.append(this.pathPom).append("->").append(this.referencedArtifact);
			}

			return stringBuilder.toString();
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result;

			result = 1;
			result = (prime * result) + super.hashCode();
			result = (prime * result) + this.pathPom.hashCode();
			result = (prime * result) + this.referencedArtifact.hashCode();

			return result;
		}

		@Override
		public boolean equals(Object other) {
			ReferenceImplSpecific referenceImplSpecificOther;

			if (this == other) {
				return true;
			}

			if (!(other instanceof ReferenceImplSpecific)) {
				return false;
			}

			referenceImplSpecificOther = (ReferenceImplSpecific)other;

			if (this.getArtifactGroupId() == null) {
				if (referenceImplSpecificOther.getArtifactGroupId() != null) {
					return false;
				}
			} else if (!this.getArtifactGroupId().equals(referenceImplSpecificOther.getArtifactGroupId())) {
				return false;
			}

			if (this.getArtifactVersion() == null) {
				if (referenceImplSpecificOther.getArtifactVersion() != null) {
					return false;
				}
			} else if (!this.getArtifactVersion().equals(referenceImplSpecificOther.getArtifactVersion())) {
				return false;
			}

			if (this.getModuleVersion() == null) {
				if (referenceImplSpecificOther.getModuleVersion() != null) {
					return false;
				}
			} else if (!this.getModuleVersion().equals(referenceImplSpecificOther.getModuleVersion())) {
				return false;
			}

			if (!this.pathPom.equals(referenceImplSpecificOther.pathPom)) {
				return false;
			}

			if (!this.referencedArtifact.equals(referenceImplSpecificOther.referencedArtifact)) {
				return false;
			}

			return true;
		}

		@Override
		public boolean equalsNoVersion(Reference referenceOther) {
			ReferenceImplSpecific referenceImplSpecificOther;

			if (this == referenceOther) {
				return true;
			}

			if (!(referenceOther instanceof ReferenceImplSpecific)) {
				return false;
			}

			referenceImplSpecificOther = (ReferenceImplSpecific)referenceOther;

			if (this.getArtifactGroupId() == null) {
				if (referenceImplSpecificOther.getArtifactGroupId() != null) {
					return false;
				}
			} else if (!this.getArtifactGroupId().equals(referenceImplSpecificOther.getArtifactGroupId())) {
				return false;
			}

			if (this.getModuleVersion() == null) {
				if (referenceImplSpecificOther.getModuleVersion() != null) {
					return false;
				}
			} else if (!this.getModuleVersion().getNodePath().equals(referenceImplSpecificOther.getModuleVersion().getNodePath())) {
				return false;
			}

			if (!this.pathPom.equals(referenceImplSpecificOther.pathPom)) {
				return false;
			}

			if (!this.referencedArtifact.equalsNoVersion(referenceImplSpecificOther.referencedArtifact)) {
				return false;
			}

			return true;
		}
	}

	public MavenReferenceManagerPluginImpl(Module module) {
		super(module);
	}

	@Override
	public List<Reference> getListReference(Path pathModuleWorkspace) {
		Path pathPom;
		Set<ArtifactGroupId> setArtifactGroupIdAggregator;

		pathPom = pathModuleWorkspace.resolve("pom.xml");

		setArtifactGroupIdAggregator = PomUtil.getSetArtifactGroupIdAggregator(pathPom);

		return this.getListReference(pathModuleWorkspace, setArtifactGroupIdAggregator, Paths.get("pom.xml"), null);
	}

	private List<Reference> getListReference(Path pathModuleWorkspace, Set<ArtifactGroupId> setArtifactGroupIdAggregator, Path pathPomRelative, String versionContainer) {
		ArrayList<Reference> listReference;
		Pom pom;
		Path pathPom;
		String version;
		Model model;

		model = this.getModule().getModel();

		listReference = new ArrayList<Reference>();

		pom = new Pom();

		pathPom = pathModuleWorkspace.resolve(pathPomRelative);
		pom.setPathPom(pathPom);
		pom.loadPom();

		version = pom.getResolvedVersion();

		if (version == null) {
			throw new RuntimeException("The module " + this.getModule() + " does not define its artifact version in the POM " + pathPom + '.');
		}

		if ((versionContainer != null) && !version.equals(versionContainer)) {
			throw new RuntimeException("The submodule POM " + pathPom + " of module " + this.getModule() + " has version " + version + " which is not the same as that of its container " + versionContainer + '.');
		}

		for(Pom.ReferencedArtifact referencedArtifact: pom.getListReferencedArtifact(EnumSet.allOf(Pom.ReferencedArtifactTypeEnum.class),  null,  null,  null)) {
			ArtifactGroupId artifactGroupId;
			Module module;
			ModuleVersion moduleVersion;
			ReferenceImplSpecific referenceImplSpecific;

			if (referencedArtifact.getGroupId().contains("${") || referencedArtifact.getArtifactId().contains("${")) {
				throw new RuntimeException("A property reference was found within referenced artifact " + referencedArtifact + " in the POM " + pathPom + ".");
			}

			artifactGroupId = new ArtifactGroupId(referencedArtifact.getGroupId(), referencedArtifact.getArtifactId());

			/* We are not interested in references internal to the module.
			 */
			if (setArtifactGroupIdAggregator.contains(artifactGroupId)) {
				continue;
			}

			if (referencedArtifact.getVersion().contains("${")) {
				throw new RuntimeException("A property reference was found within referenced artifact " + referencedArtifact + " in the POM " + pathPom + ".");
			}

			module = model.findModuleByArtifactGroupId(artifactGroupId);

			if (module != null) {
				ArtifactVersionMapperPlugin artifactVersionMapperPlugin;

				artifactVersionMapperPlugin = module.getNodePlugin(ArtifactVersionMapperPlugin.class, null);

				moduleVersion = new ModuleVersion(module.getNodePath(), artifactVersionMapperPlugin.mapArtifactVersionToVersion(new ArtifactVersion(referencedArtifact.getVersion())));
			} else {
				moduleVersion = null;
			}

			referenceImplSpecific = new ReferenceImplSpecific(moduleVersion, artifactGroupId, new ArtifactVersion(referencedArtifact.getVersion()), pathPomRelative, referencedArtifact);

			listReference.add(referenceImplSpecific);
		}

		for(String submodule: pom.getListSubmodule()) {
			listReference.addAll(this.getListReference(pathModuleWorkspace, setArtifactGroupIdAggregator, pathPomRelative.resolveSibling(submodule).resolve("pom.xml"), version));
		}

		return listReference;
	}

	@Override
	public boolean updateReferenceVersion(Path pathModuleWorkspace, Reference reference, Version version) {
		ReferenceImplSpecific referenceImplSpecific;
		Path pathPom;
		Pom pom;
		ArtifactVersionMapperPlugin artifactVersionMapperPlugin;
		ArtifactVersion artifactVersion;

		if (!(reference instanceof ReferenceImplSpecific)) {
			throw new RuntimeException("Within " + this + ", reference must be of type ReferenceImplSpecific.");
		}

		referenceImplSpecific = (ReferenceImplSpecific)reference;

		if (referenceImplSpecific.getReferenceManagerPluginImpl() != this) {
			// TODO: Have a more precise message. Maybe have a toString on a plugin.
			throw new RuntimeException("Within " + this + ", reference must have been produced by the same plugin instance.");
		}

		pathPom = pathModuleWorkspace.resolve(referenceImplSpecific.pathPom);
		pom = new Pom();
		pom.setPathPom(pathPom);
		pom.loadPom();

		// We need to get the ArtifactVersionMapperPlugin associated with the referred-to
		// Module, not the current referencing Module.
		artifactVersionMapperPlugin = this.getModule().getModel().getModule(reference.getModuleVersion().getNodePath()).getNodePlugin(ArtifactVersionMapperPlugin.class, null);
		artifactVersion = artifactVersionMapperPlugin.mapVersionToArtifactVersion(version);

		if (!artifactVersion.toString().equals(referenceImplSpecific.referencedArtifact.getVersion())) {
			pom.setReferencedArtifactVersion(referenceImplSpecific.referencedArtifact, artifactVersion.toString());
			pom.savePom();
			return true;
		} else {
			return false;
		}
	}

	@Override
	public boolean updateReferenceArtifactVersion(Path pathModuleWorkspace, Reference reference, ArtifactVersion artifactVersion) {
		// TODO To be implemented
		throw new RuntimeException("Not implemented yet.");

	}
}
