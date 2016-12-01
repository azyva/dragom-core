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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.azyva.dragom.execcontext.ExecContext;
import org.azyva.dragom.execcontext.plugin.RuntimePropertiesPlugin;
import org.azyva.dragom.execcontext.plugin.impl.MainModuleVersionWorkspacePluginFactory;
import org.azyva.dragom.execcontext.support.ExecContextHolder;
import org.azyva.dragom.model.ArtifactVersion;
import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.ModuleVersion;
import org.azyva.dragom.model.NodePath;
import org.azyva.dragom.model.Version;
import org.azyva.dragom.model.plugin.ArtifactVersionMapperPlugin;
import org.azyva.dragom.model.plugin.SelectStaticVersionPlugin;
import org.azyva.dragom.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link SelectStaticVersionPlugin} implementation that selects the most recent
 * static {@link Version} corresponding to the dynamic Version.
 * <p>
 * This is useful in a continuous delivery context where the release of each
 * {@link ModuleVersion} in a dependency graph is managed by a job in a build
 * automation tool (i.e., Jenkins) and where when releasing a ModuleVersion we
 * expect the referenced ModuleVersion to have already been released by an
 * upstream job and have a static Version already available.
 * <p>
 * This is generally used in conjunction with
 * {@link MainModuleVersionWorkspacePluginFactory} since each job performs the
 * release of a single ModuleVersin.
 * <p>
 * The selection of a static Version given a dynamic Version is performed using
 * mappings. A mapping specifies a regular expression that matches the dynamic
 * Version and a replacement that specifies the corresponding static Version
 * prefix.
 * <p>
 * In a pure continuous delivery context only one dynamic Version would be used
 * (e.g., D/master) and for each build a new unique static Version would be
 * released (e.g., S/v-1.#####). In such a case, the mapping could be:
 * <p>
 * D/master -&gt; S/v-1.
 * <p>
 * In practice, it is often required to distinguish multiple development lines
 * producing incompatible versions of the {@link Module}. In such a case,
 * development can be done on development line dynamic Versions and the mapping
 * could be:
 * <p>
 * D/develop-cd-(.+) -&gt; S/v-$1.
 * <p>
 * If development occurs on dynamic Version D/develop-cd-2 (branch develop-cd-2),
 * the released static Version could be S/v-2.#####, where ##### is an incremental
 * build number. {@link ArtifactVersionMapperPlugin} could be confidered in such a
 * way as to produce corresponding {@link ArtifactVersion}'s 2.####.
 * <p>
 * It is also possible to always use the main dynamic Version for on-going
 * development and switch to a maintenance dynamic Version when a new major
 * incompatible Version line is started on the main dynamic Version. But this
 * generally requires adjustments in referring ModuleVersion if they are to keep
 * referencing the older maintenance line.
 *
 * @author David Raymond
 */
public class ContinuousReleaseSelectStaticVersionPluginImpl extends SelectStaticVersionPluginBaseImpl implements SelectStaticVersionPlugin {
  /**
   * Logger for the class.
   */
  private static final Logger logger = LoggerFactory.getLogger(ContinuousReleaseSelectStaticVersionPluginImpl.class);

  /**
   * Runtime property defining the continuous release mapping keys.
   * <p>
   * The mapping keys are separated by ",".
   */
  private static final String RUNTIME_PROPERTY_CONTINUOUS_RELEASE_MAPPINGS = "CONTINOUS_RELEASE_MAPPINGS";

  /**
   * Runtime property prefix defining a continuous release mapping. The suffixe is
   * the mapping key.
   * <p>
   * A mapping is a regular expression and a replacement separated by ":". The
   * regular expression cannot contain ":". The replacement can contain references
   * to captured groups within the regular expression.
   * <p>
   * The regular expression matches dynamic {@link Version}'s. The replacement is
   * the corresponding static Version prefix to use.
   */
  private static final String RUNTIME_PROPERTY_PREFIX_CONTINUOUS_RELEASE_MAPPING = "CONTINUOUS_RELEASE_MAPPING.";

  /**
   * Runtime property indicating to force the reuse of an existing static
   * {@link Version} instead of creating a new one.
   * <p>
   * When releasing a {@link ModuleVersion}, this is generally specified for all
   * but the main ModuleVersion being released since it is expected that referenced
   * ModuleVersion were released before.
   * <p>
   * It is not specified for the main ModuleVersion being released since its
   * references generally need to be updated to their newer Version's.
   */
  private static final String RUNTIME_PROPERTY_IND_FORCE_REUSE_EXISTING_STATIC_VERSION = "FORCE_REUSE_STATIC_VERSION";

  /**
   * Default initial value of the revision part of a new Version.
   */
  private static final int DEFAULT_INITIAL_REVISION = 1;

  /**
   * Default number of decimal positions to use when generating the revision part of
   * a new semantic Version. With 10 builds per day, this provides à 20+ years
   * before reaching the limit.
   */
  private static final int DEFAULT_REVISION_DECIMAL_POSITION_COUNT = 5;

  /**
   * Transient data prefix that caches the list of continuous release mappings. The
   * suffix is the {@link NodePath} of the {@link Module}.
   */
  private static final String TRANSIENT_DATA_PREFIX_LIST_CONTINUOUS_RELEASE_MAPPING = ContinuousReleaseSelectStaticVersionPluginImpl.class.getName() + ".ListContinuousReleaseMapping.";

  /**
   * Holds one continuous release mapping.
   */
  private static class ContinuousReleaseMapping {
    /**
     * Pattern for matching the String representation of the source dynamic Version.
     */
    Pattern patternSrcDynamicVersion;

    /**
     * Destination static Version prefix literal. Can contain references to captured
     * subsequences within the matching source dynamic version. See
     * Matcher.replaceAll.
     */
    String destinationStaticVersionPrefix;
  }

  /**
   * Constructor.
   *
   * @param module Module.
   */
  public ContinuousReleaseSelectStaticVersionPluginImpl(Module module) {
    super(module);

    this.setDefaultInitialRevision(ContinuousReleaseSelectStaticVersionPluginImpl.DEFAULT_INITIAL_REVISION);
    this.setDefaultRevisionDecimalPositionCount(ContinuousReleaseSelectStaticVersionPluginImpl.DEFAULT_REVISION_DECIMAL_POSITION_COUNT);
  }

  @Override
  public Version selectStaticVersion(Version versionDynamic) {
    Version versionStaticPrefix;
    Version versionNewStatic;
    Version versionLatestStatic;
    RuntimePropertiesPlugin runtimePropertiesPlugin;

    this.validateVersionDynamic(versionDynamic);

    // We support having a specific static Version specified, although this is not in
    // the spirit of this plugin.
    versionNewStatic = this.handleSpecificStaticVersion(versionDynamic);

    if (versionNewStatic != null) {
      return versionNewStatic;
    }

    // Handling an existing equivalent static Version is always pertinent, whatever
    // the static Version selection strategy.
    versionNewStatic = this.handleExistingEquivalentStaticVersion(versionDynamic);

    if (versionNewStatic != null) {
      return versionNewStatic;
    }

    // Here we know we do not have a new static Version. We therefore need to get a
    // static Version prefix so that we can calculate a new static Version.

    // We support having a specific static Version prefix, although this is not in
    // the spirit of this plugin.
    versionStaticPrefix = this.handleSpecificStaticVersionPrefix(versionDynamic);

    if (versionStaticPrefix == null) {
      versionStaticPrefix = this.mapDynamicVersionToStaticVersionPrefix(versionDynamic);
    }

    versionLatestStatic = this.getVersionLatestMatchingVersionStaticPrefix(this.getListVersionStaticGlobal(), versionStaticPrefix);

    runtimePropertiesPlugin = ExecContextHolder.get().getExecContextPlugin(RuntimePropertiesPlugin.class);

    if (Util.isNotNullAndTrue(runtimePropertiesPlugin.getProperty(this.getModule(), ContinuousReleaseSelectStaticVersionPluginImpl.RUNTIME_PROPERTY_IND_FORCE_REUSE_EXISTING_STATIC_VERSION))) {
      // versionLatestStatic can be null and this is OK (will stop processing).
      // While we are returning the latest existing static Version, it is possible that
      // the dynamic Version contains commits which are not included in that Version,
      // which implies that the returned static Version is not a proper release Version
      // for the dynamic Version. But this is possibly still a correct behavior as in a
      // continuous delivery context where the build of each Module is handled
      // independently, it could happen that before a downstream Module build
      // terminates, new commits get introduced in an upstream Module. In that case, the
      // best is probably to assume that the new commits will trigger a build of the
      // upstream Module, which in turn will eventually trigger a new build for the
      // downstream Module, which will catch new the static Version. This is the nature
      // of continuous delivery performed on a Module dependency graph.
      return versionLatestStatic;
    } else {
      return this.getNewStaticVersionFromPrefix(versionLatestStatic, versionStaticPrefix);
    }
  }

  /**
   * Maps a dynamic {@link Version} to a corresponding static Version prefix.
   *
   * @param versionDynamic Dynamic Version.
   * @return Static Version prefix.
   */
  private Version mapDynamicVersionToStaticVersionPrefix(Version versionDynamic) {
    for (ContinuousReleaseMapping continuousReleaseMapping: this.getListContinuousReleaseMapping()) {
      Matcher matcher;

      ContinuousReleaseSelectStaticVersionPluginImpl.logger.debug("Attempting to match dynamic Version {} to Version matching pattern {}.", versionDynamic, continuousReleaseMapping.patternSrcDynamicVersion);

      matcher = continuousReleaseMapping.patternSrcDynamicVersion.matcher(versionDynamic.toString());

      if (matcher.matches()) {
        String stringStaticVersionPrefix;

        stringStaticVersionPrefix = matcher.replaceAll(continuousReleaseMapping.destinationStaticVersionPrefix);

        ContinuousReleaseSelectStaticVersionPluginImpl.logger.debug("Version {} mapped to static Version prefix {}.", versionDynamic, stringStaticVersionPrefix);

        return new Version(stringStaticVersionPrefix);
      }
    }

    throw new RuntimeException("No corresponding static Version prefix is mapped to dynamic Version " + versionDynamic + " for Module " + this.getModule() + '.');
  }

  /**
   * Computes and returns the continuous release mappings from the runtime
   * properties.
   * <p>
   * The result is cached in transient data.
   *
   * @return See description.
   */
  @SuppressWarnings("unchecked")
  private List<ContinuousReleaseMapping> getListContinuousReleaseMapping() {
    ExecContext execContext;
    Module module;
    RuntimePropertiesPlugin runtimePropertiesPlugin;
    List<ContinuousReleaseMapping> listContinuousReleaseMapping;
    String continuousReleaseMappingKeys;
    String[] arrayContinuousReleaseMappingKey;

    execContext = ExecContextHolder.get();
    module = this.getModule();

    listContinuousReleaseMapping = (List<ContinuousReleaseMapping>)execContext.getTransientData(ContinuousReleaseSelectStaticVersionPluginImpl.TRANSIENT_DATA_PREFIX_LIST_CONTINUOUS_RELEASE_MAPPING + module.getNodePath());

    if (listContinuousReleaseMapping != null) {
      return listContinuousReleaseMapping;
    }

    runtimePropertiesPlugin = execContext.getExecContextPlugin(RuntimePropertiesPlugin.class);

    listContinuousReleaseMapping = new ArrayList<ContinuousReleaseMapping>();

    continuousReleaseMappingKeys = runtimePropertiesPlugin.getProperty(module, ContinuousReleaseSelectStaticVersionPluginImpl.RUNTIME_PROPERTY_CONTINUOUS_RELEASE_MAPPINGS);

    if (continuousReleaseMappingKeys == null) {
      throw new RuntimeException("The runtime property " + ContinuousReleaseSelectStaticVersionPluginImpl.RUNTIME_PROPERTY_CONTINUOUS_RELEASE_MAPPINGS + " is not defined for module " + module + '.');
    }

    arrayContinuousReleaseMappingKey = continuousReleaseMappingKeys.split(",");

    for (String continuousReleaseMappingKey: arrayContinuousReleaseMappingKey) {
      String property;
      String[] arrayMappingComponent;
      ContinuousReleaseMapping continuousReleaseMapping;

      property = runtimePropertiesPlugin.getProperty(module, ContinuousReleaseSelectStaticVersionPluginImpl.RUNTIME_PROPERTY_PREFIX_CONTINUOUS_RELEASE_MAPPING + continuousReleaseMappingKey);

      if (property == null) {
        throw new RuntimeException("The runtime property " + ContinuousReleaseSelectStaticVersionPluginImpl.RUNTIME_PROPERTY_PREFIX_CONTINUOUS_RELEASE_MAPPING + continuousReleaseMappingKey + " is not defined for module " + module + '.');
      }

      arrayMappingComponent = property.split(":");

      if (arrayMappingComponent.length != 2) {
        throw new RuntimeException("The mapping " + property + " is not composed of two components separated by \":\".");
      }

      continuousReleaseMapping = new ContinuousReleaseMapping();

      continuousReleaseMapping.patternSrcDynamicVersion = Pattern.compile(arrayMappingComponent[0]);
      continuousReleaseMapping.destinationStaticVersionPrefix = arrayMappingComponent[1];

      listContinuousReleaseMapping.add(continuousReleaseMapping);
    }

    execContext.setTransientData(ContinuousReleaseSelectStaticVersionPluginImpl.TRANSIENT_DATA_PREFIX_LIST_CONTINUOUS_RELEASE_MAPPING + module.getNodePath(), listContinuousReleaseMapping);

    return listContinuousReleaseMapping;
  }
}
