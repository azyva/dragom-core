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

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.azyva.dragom.execcontext.ExecContext;
import org.azyva.dragom.execcontext.plugin.RuntimePropertiesPlugin;
import org.azyva.dragom.execcontext.plugin.UserInteractionCallbackPlugin;
import org.azyva.dragom.execcontext.support.ExecContextHolder;
import org.azyva.dragom.model.ArtifactVersion;
import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.NodePath;
import org.azyva.dragom.model.Version;
import org.azyva.dragom.model.plugin.ArtifactVersionMapperPlugin;
import org.azyva.dragom.model.plugin.ScmPlugin;
import org.azyva.dragom.util.AlwaysNeverAskUserResponse;
import org.azyva.dragom.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configurable {@link ArtifactVersionMapperPlugin}.
 * <p>
 * This implementation uses regular expression mappings defined with runtime
 * properties, providing a high degree of flexibility.
 * <p>
 * Mapping groups are supported so that the actual mapping list for individual
 * {@link Module}'s can be constructed from reusable sublists.
 * <p>
 * It also allows some dynamic behavior such as existence-based mapping, meaning
 * that following a tentative mapping, the existence of the resulting Version is
 * verified and if the Version does not exist the next mapping is used. This
 * behavior is available for ArtifactVersion to Version mapping.
 * <p>
 * Also when mapping a Version to ArtifactVersion a "PHASE" runtime property can
 * be used to build the resulting ArtifactVersion.
 * <p>
 * These dynamic behaviors, together with the use of
 * NewDynamicVersionPhasePluginFactory and PhaseSelectStaticVersionPluginImpl, are
 * useful for implementing phased development where the ArtifactVersion associated
 * with a Version changes from one phase to the next. And during such a phase
 * transition, a static Version is created to freeze the sources for the current
 * phase and then the ArtifactVersion is transitioned to the next phase.
 * <p>
 * There is possibly room for performance optimization as for each call to a map
 * method, the mappings are built from the runtime properties, even if they have
 * not changed. If optimization if considered, care will need to be taken as
 * runtime properties can easily change.
 *
 * @author David Raymond
 */
public class ConfigArtifactVersionMapperPluginImpl extends ModulePluginAbstractImpl implements ArtifactVersionMapperPlugin {
	/**
	 * Logger for the class.
	 */
	private static final Logger logger = LoggerFactory.getLogger(ConfigArtifactVersionMapperPluginImpl.class);

	/**
	 * Runtime property defining the ArtifactVersion to Version mapping keys.
	 * <p>
	 * The mapping keys are separated by ",". A mapping key can refer to a mapping
	 * group by prefixing the group name with "$".
	 */
	private static final String RUNTIME_PROPERTY_ARTIFACT_VERSION_TO_VERSION_MAPPINGS = "ARTIFACT_VERSION_TO_VERSION_MAPPINGS";

	/**
	 * Runtime property prefix defining the ArtifactVersion to Version mapping keys
	 * within a group. The suffix is the mapping group name.
	 * <p>
	 * The mapping keys are separated by ",". A mapping key can refer to another
	 * mapping group by prefixing the group name with "$".
	 */
	private static final String RUNTIME_PROPERTY_PREFIX_ARTIFACT_VERSION_TO_VERSION_MAPPING_GROUP = "ARTIFACT_VERSION_TO_VERSION_MAPPING_GROUP.";

	/**
	 * Runtime property prefix defining an ArtifactVersion to Version mapping. The
	 * suffix is the mapping key.
	 * <p>
	 * A mapping is a regular expression and a replacement separated by ":". The
	 * regular expression cannot contain ":". The replacement can contain references
	 * to captured groups within the regular expression.
	 */
	private static final String RUNTIME_PROPERTY_PREFIX_ARTIFACT_VERSION_TO_VERSION_MAPPING = "ARTIFACT_VERSION_TO_VERSION_MAPPING.";

	/**
	 * Runtime property prefix indicating if the existence of the Version obtained
	 * from an ArtifactVersion to Version mapping must be verified. The suffix is the
	 * mapping key.
	 */
	private static final String RUNTIME_PROPERTY_PREFIX_ARTIFACT_VERSION_TO_VERSION_MAPPING_TEST_EXISTENCE = "ARTIFACT_VERSION_TO_VERSION_MAPPING_TEST_EXISTENCE.";

	/**
	 * Runtime property defining the Version to ArtifactVersion mapping keys.
	 * <p>
	 * The mapping keys are separated by ",". A mapping key can refer to a mapping
	 * group by prefixing the group name with "$".
	 */
	private static final String RUNTIME_PROPERTY_VERSION_TO_ARTIFACT_VERSION_MAPPINGS = "VERSION_TO_ARTIFACT_VERSION_MAPPINGS";

	/**
	 * Runtime property prefix defining the Version to ArtifactVersion mapping keys
	 * within a group. The suffix is the mapping group name.
	 * <p>
	 * The mapping keys are separated by ",". A mapping key can refer to another
	 * mapping group by prefixing the group name with "$".
	 */
	private static final String RUNTIME_PROPERTY_PREFIX_VERSION_TO_ARTIFACT_VERSION_MAPPING_GROUP = "VERSION_TO_ARTIFACT_VERSION_MAPPING_GROUP.";

	/**
	 * Runtime property prefix defining a Version to ArtifactVersion mapping. The
	 * suffix is the mapping key.
	 * <p>
	 * A mapping is a regular expression and a replacement separated by ":". The
	 * regular expression cannot contain ":". The replacement can contain references
	 * to captured groups within the regular expression. It can also contain "@PHASE"
	 * to refer to the current phase.
	 */
	private static final String RUNTIME_PROPERTY_PREFIX_VERSION_TO_ARTIFACT_VERSION_MAPPING = "VERSION_TO_ARTIFACT_VERSION_MAPPING.";

	/**
	 * Runtime property prefix indicating to replace "@PHASE" with the current phase
	 * within the ArtifactVersion obtained from the mapping.
	 */
	private static final String RUNTIME_PROPERTY_PREFIX_VERSION_TO_ARTIFACT_VERSION_MAPPING_ADD_PHASE = "VERSION_TO_ARTIFACT_VERSION_MAPPING_ADD_PHASE.";

	/**
	 * Runtime property holding the current phase.
	 */
	private static final String RUNTIME_PROPERTY_PHASE = "PHASE";

	/**
	 * Runtime property of type AlwaysNeverAskUserResponse indicating if the phase can
	 * be reused.
	 */
	private static final String RUNTIME_PROPERTY_CAN_REUSE_PHASE = "CAN_REUSE_PHASE";

	/**
	 * Transient data prefix that caches the list of {@link ArtifactVersion} to
	 * {@link Version} mappings. The suffix is the {@link NodePath} of the
	 * {@link Module}.
	 */
	private static final String TRANSIENT_DATA_PREFIX_LIST_ARTIFACT_VERSION_TO_VERSION_MAPPING = ConfigArtifactVersionMapperPluginImpl.class.getName() + ".ListArtifactVersionToVersionMapping.";

	/**
	 * Transient data prefix that caches the list of {@link Version} to
	 * {@link ArtifactVersion} mappings. The suffix is the {@link NodePath} of the
	 * {@link Module}.
	 */
	private static final String TRANSIENT_DATA_PREFIX_LIST_VERSION_TO_ARTIFACT_VERSION_MAPPING = ConfigArtifactVersionMapperPluginImpl.class.getName() + ".ListVersionToArtifactVersionMapping.";

	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_INPUT_PHASE = "INPUT_PHASE";

	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_INPUT_PHASE_WITH_DEFAULT = "INPUT_PHASE_WITH_DEFAULT";

	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_REUSE_PHASE = "REUSE_PHASE";

	/**
	 * ResourceBundle specific to this class.
	 */
	private static final ResourceBundle resourceBundle = ResourceBundle.getBundle(ConfigArtifactVersionMapperPluginImpl.class.getName() + "ResourceBundle");

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
		 * Destination Version literal. Can contain references to captured subsequences
		 * within the matching source version. See Matcher.replaceAll.
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

	public ConfigArtifactVersionMapperPluginImpl(Module module) {
		super(module);
	}


	@Override
	public Version mapArtifactVersionToVersion(ArtifactVersion artifactVersion) {
		ScmPlugin scmPlugin;
		Version version;

		scmPlugin = this.getModule().getNodePlugin(ScmPlugin.class, null);

		for (VersionMapping versionMapping: this.getListVersionMappingArtifactVersionToVersion()) {
			Matcher matcher;

			ConfigArtifactVersionMapperPluginImpl.logger.debug("Attempting to match ArtifactVersion {} to version matching pattern {}.", artifactVersion, versionMapping.patternSrcVersion);

			matcher = versionMapping.patternSrcVersion.matcher(artifactVersion.toString());

			if (matcher.matches()) {
				version = new Version(matcher.replaceAll(versionMapping.destinationVersion));

				if (versionMapping.indTestExistence) {
					if (scmPlugin.isVersionExists(version)) {
						ConfigArtifactVersionMapperPluginImpl.logger.debug("ArtifactVersion {} mapped to version {} which exists.", artifactVersion, version);

						return version;
					}

					ConfigArtifactVersionMapperPluginImpl.logger.debug("ArtifactVersion {} mapped to version {} which does not exist and is therefore skipped.", artifactVersion, version);
				} else {
					ConfigArtifactVersionMapperPluginImpl.logger.debug("ArtifactVersion {} mapped to version {}.", artifactVersion, version);

					return version;
				}
			}
		}

		throw new RuntimeException("No corresponding Version is mapped to ArtifactVersion " + artifactVersion + " for Module " + this.getModule() + '.');
	}

	@Override
	public ArtifactVersion mapVersionToArtifactVersion(Version version) {
		for (VersionMapping versionMapping: this.getListVersionMappingVersionToArtifactVersion()) {
			Matcher matcher;

			ConfigArtifactVersionMapperPluginImpl.logger.debug("Attempting to match Version {} to Version matching pattern {}.", version, versionMapping.patternSrcVersion);

			matcher = versionMapping.patternSrcVersion.matcher(version.toString());

			if (matcher.matches()) {
				String stringArtifactVersion;

				stringArtifactVersion = matcher.replaceAll(versionMapping.destinationVersion);

				ConfigArtifactVersionMapperPluginImpl.logger.debug("Version {} mapped to ArtifactVersion {}.", version, stringArtifactVersion);

				if (versionMapping.indAddPhase) {
					ExecContext execContext;
					RuntimePropertiesPlugin runtimePropertiesPlugin;
					UserInteractionCallbackPlugin userInteractionCallbackPlugin;
					String phase;
					AlwaysNeverAskUserResponse alwaysNeverAskUserResponseCanReusePhase;
					int indexPhase;

					execContext = ExecContextHolder.get();
					runtimePropertiesPlugin = ExecContextHolder.get().getExecContextPlugin(RuntimePropertiesPlugin.class);
					userInteractionCallbackPlugin = ExecContextHolder.get().getExecContextPlugin(UserInteractionCallbackPlugin.class);

					// For each Module for which the phase is required we store it as transient data
					// in order to avoid asking the user about the phase multiple times for a given
					// Module during a task execution. This is not the same as phase reuse which is
					// handled below. Phase reuse relates to reusing the same phase for different
					// modules, whereas here this is specific to a given module.
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
								phase = userInteractionCallbackPlugin.getInfo(MessageFormat.format(ConfigArtifactVersionMapperPluginImpl.resourceBundle.getString(ConfigArtifactVersionMapperPluginImpl.MSG_PATTERN_KEY_INPUT_PHASE), this.getModule(), version, stringArtifactVersion));
							} else {
								phase = userInteractionCallbackPlugin.getInfoWithDefault(MessageFormat.format(ConfigArtifactVersionMapperPluginImpl.resourceBundle.getString(ConfigArtifactVersionMapperPluginImpl.MSG_PATTERN_KEY_INPUT_PHASE_WITH_DEFAULT), this.getModule(), version, stringArtifactVersion, phase), phase);
							}

							runtimePropertiesPlugin.setProperty(null, ConfigArtifactVersionMapperPluginImpl.RUNTIME_PROPERTY_PHASE, phase);

							// The result is not useful. We only want to adjust the runtime property which
							// will be reused the next time around.
							Util.getInfoAlwaysNeverAskUserResponseAndHandleAsk(
									runtimePropertiesPlugin,
									ConfigArtifactVersionMapperPluginImpl.RUNTIME_PROPERTY_CAN_REUSE_PHASE,
									userInteractionCallbackPlugin,
									MessageFormat.format(ConfigArtifactVersionMapperPluginImpl.resourceBundle.getString(ConfigArtifactVersionMapperPluginImpl.MSG_PATTERN_KEY_REUSE_PHASE), phase));
						}

						execContext.setTransientData(ConfigArtifactVersionMapperPluginImpl.class.getName() + '.' + this.getModule() + ".Phase", phase);
					}

					indexPhase = stringArtifactVersion.indexOf("@PHASE");

					if (indexPhase == -1) {
						throw new RuntimeException("Version regex " + versionMapping.patternSrcVersion + " to ArtifactVersion " + versionMapping.destinationVersion + " for module " + this.getModule() + " specified to add a phase but the destination version does not contain the @{PHASE} parameter.");
					}

					ConfigArtifactVersionMapperPluginImpl.logger.debug("@PHASE within ArtifactVersion {} replaced with {}.", stringArtifactVersion, phase);

					// Magic number 6 below is the length of "@PHASE". Not worth having a constant.
					return new ArtifactVersion(stringArtifactVersion.substring(0, indexPhase) + phase + stringArtifactVersion.substring(indexPhase + 6));
				} else {
					return new ArtifactVersion(stringArtifactVersion);
				}
			}
		}

		throw new RuntimeException("No corresponding ArtifactVersion is mapped to Version " + version + " for Module " + this.getModule() + '.');
	}

	/**
	 * Computes and returns the List of ArtifactVersion to Version mappings from the
	 * runtime properties.
	 * <p>
	 * The result is cached in transient data.
	 *
	 * @return See description.
	 */
	@SuppressWarnings("unchecked")
	private List<VersionMapping> getListVersionMappingArtifactVersionToVersion() {
		ExecContext execContext;
		Module module;
		RuntimePropertiesPlugin runtimePropertiesPlugin;
		List<VersionMapping> listVersionMapping;
		List<String> listMappingKey;

		execContext = ExecContextHolder.get();
		module = this.getModule();

		listVersionMapping = (List<VersionMapping>)execContext.getTransientData(ConfigArtifactVersionMapperPluginImpl.TRANSIENT_DATA_PREFIX_LIST_VERSION_TO_ARTIFACT_VERSION_MAPPING + module.getNodePath());

		if (listVersionMapping != null) {
			return listVersionMapping;
		}

		runtimePropertiesPlugin = execContext.getExecContextPlugin(RuntimePropertiesPlugin.class);

		listVersionMapping = new ArrayList<VersionMapping>();

		listMappingKey = this.getListMappingKey(ConfigArtifactVersionMapperPluginImpl.RUNTIME_PROPERTY_ARTIFACT_VERSION_TO_VERSION_MAPPINGS, ConfigArtifactVersionMapperPluginImpl.RUNTIME_PROPERTY_PREFIX_ARTIFACT_VERSION_TO_VERSION_MAPPING_GROUP);

		for (String mappingKey: listMappingKey) {
			String property;
			String[] arrayMappingComponent;
			VersionMapping versionMapping;

			property = runtimePropertiesPlugin.getProperty(module, ConfigArtifactVersionMapperPluginImpl.RUNTIME_PROPERTY_PREFIX_ARTIFACT_VERSION_TO_VERSION_MAPPING + mappingKey);

			if (property == null) {
				throw new RuntimeException("The runtime property " + ConfigArtifactVersionMapperPluginImpl.RUNTIME_PROPERTY_PREFIX_ARTIFACT_VERSION_TO_VERSION_MAPPING + mappingKey + " is not defined for module " + module + '.');
			}

			arrayMappingComponent = property.split(":");

			if (arrayMappingComponent.length != 2) {
				throw new RuntimeException("The mapping " + property + " is not composed of two components separated by \":\".");
			}

			versionMapping = new VersionMapping();

			versionMapping.patternSrcVersion = Pattern.compile(arrayMappingComponent[0]);
			versionMapping.destinationVersion = arrayMappingComponent[1];

			property = module.getProperty(ConfigArtifactVersionMapperPluginImpl.RUNTIME_PROPERTY_PREFIX_ARTIFACT_VERSION_TO_VERSION_MAPPING_TEST_EXISTENCE + mappingKey);

			versionMapping.indTestExistence = Util.isNotNullAndTrue(property);

			listVersionMapping.add(versionMapping);
		}

		execContext.setTransientData(ConfigArtifactVersionMapperPluginImpl.TRANSIENT_DATA_PREFIX_LIST_VERSION_TO_ARTIFACT_VERSION_MAPPING + module.getNodePath(), listVersionMapping);

		return listVersionMapping;
	}

	/**
	 * Computes and returns the List of Version to ArtifactVersion mappings from the
	 * runtime properties.
	 * <p>
	 * The result is cached in transient data.
	 *
	 * @return See description.
	 */
	@SuppressWarnings("unchecked")
	private List<VersionMapping> getListVersionMappingVersionToArtifactVersion() {
		ExecContext execContext;
		Module module;
		RuntimePropertiesPlugin runtimePropertiesPlugin;
		List<VersionMapping> listVersionMapping;
		List<String> listMappingKey;

		execContext = ExecContextHolder.get();
		module = this.getModule();

		listVersionMapping = (List<VersionMapping>)execContext.getTransientData(ConfigArtifactVersionMapperPluginImpl.TRANSIENT_DATA_PREFIX_LIST_ARTIFACT_VERSION_TO_VERSION_MAPPING + module.getNodePath());

		if (listVersionMapping != null) {
			return listVersionMapping;
		}

		runtimePropertiesPlugin = execContext.getExecContextPlugin(RuntimePropertiesPlugin.class);

		listVersionMapping = new ArrayList<VersionMapping>();

		listMappingKey = this.getListMappingKey(ConfigArtifactVersionMapperPluginImpl.RUNTIME_PROPERTY_VERSION_TO_ARTIFACT_VERSION_MAPPINGS, ConfigArtifactVersionMapperPluginImpl.RUNTIME_PROPERTY_PREFIX_VERSION_TO_ARTIFACT_VERSION_MAPPING_GROUP);

		for (String mappingKey: listMappingKey) {
			String property;
			String[] arrayMappingComponent;
			VersionMapping versionMapping;

			property = runtimePropertiesPlugin.getProperty(module, ConfigArtifactVersionMapperPluginImpl.RUNTIME_PROPERTY_PREFIX_VERSION_TO_ARTIFACT_VERSION_MAPPING + mappingKey);

			if (property == null) {
				throw new RuntimeException("The runtime property " + ConfigArtifactVersionMapperPluginImpl.RUNTIME_PROPERTY_PREFIX_VERSION_TO_ARTIFACT_VERSION_MAPPING + mappingKey + " is not defined for module " + module + '.');
			}

			arrayMappingComponent = property.split(":");

			if (arrayMappingComponent.length != 2) {
				throw new RuntimeException("The mapping " + property + " is not composed of two components separated by \":\".");
			}

			versionMapping = new VersionMapping();

			versionMapping.patternSrcVersion = Pattern.compile(arrayMappingComponent[0]);
			versionMapping.destinationVersion = arrayMappingComponent[1];

			property = module.getProperty(ConfigArtifactVersionMapperPluginImpl.RUNTIME_PROPERTY_PREFIX_VERSION_TO_ARTIFACT_VERSION_MAPPING_ADD_PHASE + mappingKey);

			versionMapping.indAddPhase = Util.isNotNullAndTrue(property);

			listVersionMapping.add(versionMapping);
		}

		execContext.setTransientData(ConfigArtifactVersionMapperPluginImpl.TRANSIENT_DATA_PREFIX_LIST_ARTIFACT_VERSION_TO_VERSION_MAPPING + module.getNodePath(), listVersionMapping);

		return listVersionMapping;
	}

	/**
	 * Returns a List of mapping keys defined by a runtime property, which can also
	 * contain references to mapping groups.
	 *
	 * @param runtimePropertyMappingKeys Name of the runtime property for the mapping
	 *   keys.
	 * @param runtimePropertyMappingGroupPrefix Name of the runtime property to
	 *   extract mapping groups.
	 * @return List of mapping keys.
	 */
	private List<String> getListMappingKey(String runtimePropertyMappingKeys, String runtimePropertyMappingGroupPrefix) {
		RuntimePropertiesPlugin runtimePropertiesPlugin;
		Module module;
		List<String> listMappingKey;
		String mappingKeys;
		String[] arrayMappingKey;

		runtimePropertiesPlugin = ExecContextHolder.get().getExecContextPlugin(RuntimePropertiesPlugin.class);
		module = this.getModule();

		listMappingKey = new ArrayList<String>();

		mappingKeys = runtimePropertiesPlugin.getProperty(module, runtimePropertyMappingKeys);

		if (mappingKeys == null) {
			throw new RuntimeException("The runtime property " + runtimePropertyMappingKeys + " is not defined for module " + module + '.');
		}

		arrayMappingKey = mappingKeys.split(",");

		for (String mappingKey: arrayMappingKey) {
			mappingKey = mappingKey.trim();

			if (mappingKey.startsWith("$")) {
				listMappingKey.addAll(this.getListMappingKey(runtimePropertyMappingGroupPrefix + mappingKey.substring(1),  runtimePropertyMappingGroupPrefix));
			} else {
				listMappingKey.add(mappingKey);
			}
		}

		return listMappingKey;
	}
}