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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.azyva.dragom.execcontext.ExecContext;
import org.azyva.dragom.execcontext.plugin.RuntimePropertiesPlugin;
import org.azyva.dragom.execcontext.plugin.UserInteractionCallbackPlugin;
import org.azyva.dragom.execcontext.support.ExecContextHolder;
import org.azyva.dragom.model.ArtifactVersion;
import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.Version;
import org.azyva.dragom.model.plugin.ArtifactVersionMapperPlugin;
import org.azyva.dragom.model.plugin.ScmPlugin;
import org.azyva.dragom.util.AlwaysNeverAskUserResponse;
import org.azyva.dragom.util.Util;

/**
 * Factory for configurable ArtifactVersionMapperPlugin.
 *
 * This implementation uses regular expression mappings defined with properties
 * within the model, providing a high degree of flexibility.
 *
 * It also allows some dynamic behavior such as existence-based mapping, meaning
 * that following a tentative mapping, the existence of the resulting Version is
 * verified and if the Version does not exist the next mapping is used. This
 * behavior is available for ArtifactVersion to Version mapping.
 *
 * Also when mapping a Version to ArtifactVersion a runtime property can be used
 * to build the resulting ArtifactVersion.
 *
 * These dynamic behaviors, together with the use of
 * NewDynamicVersionPhasePluginFactory and NewStaticVersionPhasePluginFactory, are
 * useful for implementing phased development where the ArtifactVersion associated
 * with a Version changes from one phase to the next. And during such a phase
 * transition, a static Version is created to freeze the sources for the current
 * phase and then the ArtifactVersion is transitioned to the next phase.
 *
 * @author David Raymond
 */
public class ConfigArtifactVersionMapperPluginImpl extends ModulePluginAbstractImpl implements ArtifactVersionMapperPlugin {
	private static final String MODEL_PROPERTY_ARTIFACT_VERSION_TO_VERSION_MAPPINGS = "ARTIFACT_VERSION_TO_VERSION_MAPPINGS";
	private static final String MODEL_PROPERTY_ARTIFACT_VERSION_TO_VERSION_MAPPING_PREFIX = "ARTIFACT_VERSION_TO_VERSION_MAPPING_";
	private static final String MODEL_PROPERTY_ARTIFACT_VERSION_TO_VERSION_MAPPING_TEST_EXISTENCE_PREFIX = "ARTIFACT_VERSION_TO_VERSION_MAPPING_TEST_EXISTENCE_";
	private static final String MODEL_PROPERTY_VERSION_TO_ARTIFACT_VERSION_MAPPINGS = "VERSION_TO_ARTIFACT_VERSION_MAPPINGS";
	private static final String MODEL_PROPERTY_VERSION_TO_ARTIFACT_VERSION_MAPPING_PREFIX = "VERSION_TO_ARTIFACT_VERSION_MAPPING_";
	private static final String MODEL_PROPERTY_VERSION_TO_ARTIFACT_VERSION_MAPPING_ADD_PHASE_PREFIX = "VERSION_TO_ARTIFACT_VERSION_MAPPING_ADD_PHASE_";
	private static final String RUNTIME_PROPERTY_PHASE = "PHASE";
	private static final String RUNTIME_PROPERTY_CAN_REUSE_PHASE = "CAN_REUSE_PHASE";

	/**
	 * Holds one version mapping.
	 *
	 * This class is used both for ArtifactVersion to Version and Version to
	 * ArtifactVersion mappings, although not all fields are used in both cases.
	 */
	private static class VersionMapping {
		/**
		 * Pattern for matching the String representation of the source Version or
		 * ArtifactVersion.
		 */
		Pattern patternSrcVersion;

		/**
		 * Destination version in literal form. Can contain references to captured
		 * subsequences within the matching source version. See Matcher.replaceAll.
		 */
		String destinationVersion;

		/**
		 * Indicates that the destination Version corresponding to the source
		 * ArtifactVersion must exist or the next VersionMapping is used.
		 */
		boolean indTestExistence;

		/**
		 * Indicates that the destination ArtifactVersion is built by adding a phase
		 * suffix that must be specified as a runtime property.
		 */
		boolean indAddPhase;
	}

	private List<VersionMapping> listVersionMappingArtifactVersionToVersion;
	private List<VersionMapping> listVersionMappingVersionToArtifactVersion;

	public ConfigArtifactVersionMapperPluginImpl(Module module) {
		super(module);

		String property;
		String[] arrayMappingKey;

		property = module.getProperty(ConfigArtifactVersionMapperPluginImpl.MODEL_PROPERTY_ARTIFACT_VERSION_TO_VERSION_MAPPINGS);

		if (property == null) {
			throw new RuntimeException("The property ARTIFACT_VERSION_TO_VERSION_MAPPINGS is not defined for plugin " + this.toString() + '.');
		}

		this.listVersionMappingArtifactVersionToVersion = new ArrayList<VersionMapping>();

		arrayMappingKey = property.split(",");

		for (String mappingKey: arrayMappingKey) {
			String[] arrayMappingComponent;
			VersionMapping versionMapping;

			property = module.getProperty(ConfigArtifactVersionMapperPluginImpl.MODEL_PROPERTY_ARTIFACT_VERSION_TO_VERSION_MAPPING_PREFIX + mappingKey);

			if (property == null) {
				throw new RuntimeException("The property ARTIFACT_VERSION_TO_VERSION_MAPPING_ " + mappingKey + " is not defined for plugin " + this.toString() + '.');
			}

			arrayMappingComponent = property.split(":");

			if (arrayMappingComponent.length != 2) {
				throw new RuntimeException("The mapping " + property + " is not composed of two components separated by \":\".");
			}

			versionMapping = new VersionMapping();

			versionMapping.patternSrcVersion = Pattern.compile(arrayMappingComponent[0]);
			versionMapping.destinationVersion = arrayMappingComponent[1];

			property = module.getProperty(ConfigArtifactVersionMapperPluginImpl.MODEL_PROPERTY_ARTIFACT_VERSION_TO_VERSION_MAPPING_TEST_EXISTENCE_PREFIX + mappingKey);

			versionMapping.indTestExistence = (property != null) && Boolean.valueOf(property);

			this.listVersionMappingArtifactVersionToVersion.add(versionMapping);
		}

		property = module.getProperty(ConfigArtifactVersionMapperPluginImpl.MODEL_PROPERTY_VERSION_TO_ARTIFACT_VERSION_MAPPINGS);

		if (property == null) {
			throw new RuntimeException("The property VERSION_TO_ARTIFACT_VERSION_MAPPINGS is not defined for plugin " + this.toString() + '.');
		}

		this.listVersionMappingVersionToArtifactVersion = new ArrayList<VersionMapping>();

		arrayMappingKey = property.split(",");

		for (String mappingKey: arrayMappingKey) {
			String[] arrayMappingComponent;
			VersionMapping versionMapping;

			property = module.getProperty(ConfigArtifactVersionMapperPluginImpl.MODEL_PROPERTY_VERSION_TO_ARTIFACT_VERSION_MAPPING_PREFIX + mappingKey);

			if (property == null) {
				throw new RuntimeException("The property VERSION_TO_ARTIFACT_VERSION_MAPPING_ " + mappingKey + " is not defined for plugin " + this.toString() + '.');
			}

			arrayMappingComponent = property.split(":");

			if (arrayMappingComponent.length != 2) {
				throw new RuntimeException("The mapping " + property + " is not composed of two components separated by \":\".");
			}

			versionMapping = new VersionMapping();

			versionMapping.patternSrcVersion = Pattern.compile(arrayMappingComponent[0]);
			versionMapping.destinationVersion = arrayMappingComponent[1];

			property = module.getProperty(ConfigArtifactVersionMapperPluginImpl.MODEL_PROPERTY_VERSION_TO_ARTIFACT_VERSION_MAPPING_ADD_PHASE_PREFIX + mappingKey);

			versionMapping.indAddPhase = (property != null) && Boolean.valueOf(property);

			this.listVersionMappingVersionToArtifactVersion.add(versionMapping);
		}
	}


//TODO: Maybe add traces with logger (not info, but trace I think).
	@Override
	public Version mapArtifactVersionToVersion(ArtifactVersion artifactVersion) {
		ScmPlugin scmPlugin;
		Version version;

		scmPlugin = this.getModule().getNodePlugin(ScmPlugin.class, null);

		for (VersionMapping versionMapping: this.listVersionMappingArtifactVersionToVersion) {
			Matcher matcher;

			matcher = versionMapping.patternSrcVersion.matcher(artifactVersion.toString());

			if (matcher.matches()) {
				version = new Version(matcher.replaceAll(versionMapping.destinationVersion));

				if (versionMapping.indTestExistence) {
					if (scmPlugin.isVersionExists(version)) {
						return version;
					}
				} else {
					return version;
				}
			}
		}

		throw new RuntimeException("No corresponding version is mapped to artifact version " + artifactVersion + " for module " + this.getModule() + '.');
	}

	@Override
	public ArtifactVersion mapVersionToArtifactVersion(Version version) {
		ArtifactVersion artifactVersion;

		for (VersionMapping versionMapping: this.listVersionMappingVersionToArtifactVersion) {
			Matcher matcher;

			matcher = versionMapping.patternSrcVersion.matcher(version.toString());

			if (matcher.matches()) {
				artifactVersion = new ArtifactVersion(matcher.replaceAll(versionMapping.destinationVersion));

				if (versionMapping.indAddPhase) {
					ExecContext execContext;
					RuntimePropertiesPlugin runtimePropertiesPlugin;
					UserInteractionCallbackPlugin userInteractionCallbackPlugin;
					String phase;
					AlwaysNeverAskUserResponse alwaysNeverAskUserResponseCanReusePhase;
					String stringVersion;
					int indexPhase;

					execContext = ExecContextHolder.get();
					runtimePropertiesPlugin = ExecContextHolder.get().getExecContextPlugin(RuntimePropertiesPlugin.class);
					userInteractionCallbackPlugin = ExecContextHolder.get().getExecContextPlugin(UserInteractionCallbackPlugin.class);

					// For each Module for which the phase is required we store it as transient data
					// in order to avoid asking the user about the phase multiple times for a given
					// Module during a task execution.
					// TODO: Not sure if we could not only use RuntimeProperties here.
					phase = (String)execContext.getTransientData(ConfigArtifactVersionMapperPluginImpl.class.getName() + '.' + this.getModule() + ".Phase");

					if (phase == null) {
						phase = runtimePropertiesPlugin.getProperty(this.getModule(), ConfigArtifactVersionMapperPluginImpl.RUNTIME_PROPERTY_PHASE);
						alwaysNeverAskUserResponseCanReusePhase = AlwaysNeverAskUserResponse.valueOfWithAskDefault(runtimePropertiesPlugin.getProperty(this.getModule(), ConfigArtifactVersionMapperPluginImpl.RUNTIME_PROPERTY_CAN_REUSE_PHASE));

						if (phase == null) {
							if (alwaysNeverAskUserResponseCanReusePhase.isAlways()) {
							// Normally if the runtime property CAN_REUSE_PHASE is ALWAYS the PHASE runtime
							// property should also be set. But since these properties are independent and
							// stored externally, it can happen that they are not synchronized. We make an
							// adjustment here to avoid problems.
								alwaysNeverAskUserResponseCanReusePhase = AlwaysNeverAskUserResponse.ASK;
							}
						}

						if (!alwaysNeverAskUserResponseCanReusePhase.isAlways()) {
							if (phase == null) {
								phase = userInteractionCallbackPlugin.getInfo("Which phase do you want to use for mapping version " + version + " to artifact version " + artifactVersion + " for module " + this.getModule() + "? ");
							} else {
								phase = userInteractionCallbackPlugin.getInfoWithDefault("Which phase do you want to use for mapping version " + version + " to artifact version " + artifactVersion + " for module " + this.getModule() + " [" + phase + "]? ", phase);
							}

							runtimePropertiesPlugin.setProperty(null, ConfigArtifactVersionMapperPluginImpl.RUNTIME_PROPERTY_PHASE, phase);

							alwaysNeverAskUserResponseCanReusePhase =
									Util.getInfoAlwaysNeverAskUserResponseAndHandleAsk(
											runtimePropertiesPlugin,
											ConfigArtifactVersionMapperPluginImpl.RUNTIME_PROPERTY_CAN_REUSE_PHASE,
											userInteractionCallbackPlugin,
											"Do you want to automatically reuse phase " + phase + " for all subsequent version to artifact version mapping*",
											AlwaysNeverAskUserResponse.ALWAYS);
						}

						execContext.setTransientData(ConfigArtifactVersionMapperPluginImpl.class.getName() + '.' + this.getModule() + ".Phase", phase);
					}

					stringVersion = artifactVersion.getVersion();
					indexPhase = stringVersion.indexOf("@{PHASE}");

					if (indexPhase == -1) {
						throw new RuntimeException("Version regex " + versionMapping.patternSrcVersion + " to artifact version " + versionMapping.destinationVersion + " for module " + this.getModule() + " specified to add a phase but the destination version does not contain the @{PHASE} parameter.");
					}

					// Magic number 8 below is the length of "@{PHASE}". Not worth having a constant.
					return new ArtifactVersion(artifactVersion.getVersionType(), stringVersion.substring(0, indexPhase) + phase + stringVersion.substring(indexPhase + 8));
				} else {
					return artifactVersion;
				}
			}
		}

		throw new RuntimeException("No corresponding artifact version is mapped to version " + version + " for module " + this.getModule() + '.');
	}
}