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

import java.text.MessageFormat;
import java.util.Collections;
import java.util.List;
import java.util.ResourceBundle;

import org.azyva.dragom.execcontext.plugin.RuntimePropertiesPlugin;
import org.azyva.dragom.execcontext.plugin.UserInteractionCallbackPlugin;
import org.azyva.dragom.execcontext.support.ExecContextHolder;
import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.ModuleVersion;
import org.azyva.dragom.model.Version;
import org.azyva.dragom.model.VersionType;
import org.azyva.dragom.model.plugin.ScmPlugin;
import org.azyva.dragom.model.plugin.SelectStaticVersionPlugin;
import org.azyva.dragom.model.plugin.VersionClassifierPlugin;
import org.azyva.dragom.util.AlwaysNeverAskUserResponse;
import org.azyva.dragom.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for SelectStaticVersionPlugin that implements a strategy based on semantic
 * versioning.
 *
 * Specific static Version and static Version prefix are also supported for maximum
 * flexibility.
 *
 * The semantic versions are assumed to have the typical M.m.r format (major, minor,
 * revision), with all 3 components present. When interpreting semantic versions,
 * trailing components can be absent. When generating a semantic version, all 3
 * components will be present.
 *
 * The initial value for the revision component is 0 and the default number of
 * decimal positions for revision is 1, as in 1.0.0.
 *
 * @author David Raymond
 */
public class SemanticSelectStaticVersionPluginImpl extends SelectStaticVersionPluginBaseImpl implements SelectStaticVersionPlugin {
  private static final Logger logger = LoggerFactory.getLogger(SemanticSelectStaticVersionPluginImpl.class);

  /**
   * Model property specifying the prefix for semantic Version's. Static Versions
   * which do not start with this prefix are not considered. New semantic Versions
   * will have this as a prefix.
   *
   * If this property is not defined, semantic Versions are assumed to have no
   * prefix, which is generally not desirable since we generally want to
   * distinguish between different types of Versions. But this plugin still allows
   * this property to be undefined.
   */
  private static final String MODEL_PROPERTY_SEMANTIC_VERSION_PREFIX = "SEMANTIC_VERSION_PREFIX";

  /**
   * Runtime property specifying the semantic Version type based on which to create
   * new Version's. The possible values are "minor" and "major".
   */
  private static final String RUNTIME_PROPERTY_NEW_SEMANTIC_VERSION_TYPE = "NEW_SEMANTIC_VERSION_TYPE";

  /**
   * Runtime property of type AlwaysNeverAskUserResponse that indicates if a
   * previously selected semantic Version type can be reused.
   */
  private static final String RUNTIME_PROPERTY_CAN_REUSE_NEW_SEMANTIC_VERSION_TYPE = "CAN_REUSE_NEW_SEMANTIC_VERSION_TYPE";

  /**
   * Runtime property that specifies the semantic Version type to reuse.
   */
  private static final String RUNTIME_PROPERTY_REUSE_NEW_SEMANTIC_VERSION_TYPE = "REUSE_NEW_SEMANTIC_VERSION_TYPE";

  /**
   * Default initial value of the revision part of a new semantic Version.
   */
  private static final int DEFAULT_INITIAL_REVISION = 0;

  /**
   * Default number of decimal positions to use when generating the revision part of
   * a new semantic Version.
   */
  private static final int DEFAULT_REVISION_DECIMAL_POSITION_COUNT = 1;

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_NEW_SEMANTIC_VERSION_TYPE_SPECIFIED = "NEW_SEMANTIC_VERSION_TYPE_SPECIFIED";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_NEW_SEMANTIC_VERSION_TYPE_AUTOMATICALLY_REUSED = "NEW_SEMANTIC_VERSION_TYPE_AUTOMATICALLY_REUSED";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_INPUT_NEW_SEMANTIC_VERSION_TYPE = "INPUT_NEW_SEMANTIC_VERSION_TYPE";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_INPUT_NEW_SEMANTIC_VERSION_TYPE_WITH_DEFAULT = "INPUT_NEW_SEMANTIC_VERSION_TYPE_WITH_DEFAULT";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_NEW_SEMANTIC_VERSION_TYPE_INVALID = "NEW_SEMANTIC_VERSION_TYPE_INVALID";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_AUTOMATICALLY_REUSE_NEW_SEMANTIC_VERSION_TYPE = "AUTOMATICALLY_REUSE_NEW_SEMANTIC_VERSION_TYPE";

  /**
   * ResourceBundle specific to this class.
   */
  private static final ResourceBundle resourceBundle = ResourceBundle.getBundle(SemanticSelectStaticVersionPluginImpl.class.getName() + "ResourceBundle");

  private enum NewSemanticVersionType {
    MINOR,
    MAJOR
  };

  /**
   * Semantic Version prefix.
   */
  private String semanticVersionPrefix;

  public SemanticSelectStaticVersionPluginImpl(Module module) {
    super(module);

    this.setDefaultInitialRevision(SemanticSelectStaticVersionPluginImpl.DEFAULT_INITIAL_REVISION);
    this.setDefaultRevisionDecimalPositionCount(SemanticSelectStaticVersionPluginImpl.DEFAULT_REVISION_DECIMAL_POSITION_COUNT);

    this.semanticVersionPrefix = module.getProperty(SemanticSelectStaticVersionPluginImpl.MODEL_PROPERTY_SEMANTIC_VERSION_PREFIX);
  }

  @Override
  public Version selectStaticVersion(Version versionDynamic) {
    Version versionStaticPrefix;
    Version versionNewStatic;

    this.validateVersionDynamic(versionDynamic);

    versionNewStatic = this.handleSpecificStaticVersion(versionDynamic);

    if (versionNewStatic != null) {
      return versionNewStatic;
    }

    versionNewStatic = this.handleExistingEquivalentStaticVersion(versionDynamic);

    if (versionNewStatic != null) {
      return versionNewStatic;
    }

    // Here we know we do not have a new static Version. We therefore need to get a
    // static Version prefix so that we can calculate a new static Version.

    versionStaticPrefix = this.handleSpecificStaticVersionPrefix(versionDynamic);

    if (versionStaticPrefix == null) {
      // If we do not have a static version prefix, interact with the user to establish
      // one. This may involve reusing a previously specified one.

      versionStaticPrefix = this.getStaticVersionPrefix(versionDynamic);

      if (versionStaticPrefix == null) {
        return null;
      }
    }

    return this.getNewStaticVersionFromPrefix(this.getVersionLatestMatchingVersionStaticPrefix(this.getListVersionStaticForDynamicVersion(versionDynamic), versionStaticPrefix), versionStaticPrefix);
  }

  /**
   * Gets a new static version by considering existing versions semantically and
   * based on a new semantic version type (major vs minor) specified by the user.
   *
   * @param versionDynamic Dynamic Version.
   * @return New static Version.
   */
  private Version getStaticVersionPrefix(Version versionDynamic) {
    RuntimePropertiesPlugin runtimePropertiesPlugin;
    UserInteractionCallbackPlugin userInteractionCallbackPlugin;
    Module module;
    ScmPlugin scmPlugin;
    VersionClassifierPlugin versionClassifierPlugin;
    String runtimeProperty;
    List<Version> listVersionStatic;
    int[] arraySemanticVersionComponentMax;
    Version versionStaticMax;
    String semanticVersionPrefixNotNull;

    runtimePropertiesPlugin = ExecContextHolder.get().getExecContextPlugin(RuntimePropertiesPlugin.class);
    userInteractionCallbackPlugin = ExecContextHolder.get().getExecContextPlugin(UserInteractionCallbackPlugin.class);
    module = this.getModule();
    scmPlugin = module.getNodePlugin(ScmPlugin.class, null);
    versionClassifierPlugin = module.getNodePlugin(VersionClassifierPlugin.class, null);

    listVersionStatic = scmPlugin.getListVersionStatic();
    Collections.sort(listVersionStatic, versionClassifierPlugin);

    SemanticSelectStaticVersionPluginImpl.logger.info("Sorted list of available static Version's for Module " + module + ": " + listVersionStatic);

    do {
      if (listVersionStatic.isEmpty()) {
        versionStaticMax = null;
        arraySemanticVersionComponentMax = null;
      } else {
        versionStaticMax = listVersionStatic.get(listVersionStatic.size() - 1);

        SemanticSelectStaticVersionPluginImpl.logger.info("Largest available static Version for Module " + module + ": " + versionStaticMax);

        // Using VersionClassifierPlugin above is a convenient way to find the largest
        // static Version. But here we interpret Version's in a numeric semantic way and
        // there is no guarantee that the largest Version found can be interpreted in
        // this way. We must therefore validate it.

        arraySemanticVersionComponentMax = this.getArraySemanticVersionComponent(versionStaticMax);

        if (arraySemanticVersionComponentMax == null) {
          SemanticSelectStaticVersionPluginImpl.logger.info("The largest available static Version " + versionStaticMax + " for Module " + module + " cannot be interpreted numerically and semantically. We do not use it and try the next one.");

          versionStaticMax = null;

          // If the largest static Version cannot be interpreted semantically, we try with
          // the next largest, and so one. We do this simply by removing the last Version
          // from the List.
          listVersionStatic.remove(listVersionStatic.size() - 1);
        }
      }
    } while ((versionStaticMax == null) && !listVersionStatic.isEmpty());

    semanticVersionPrefixNotNull = this.semanticVersionPrefix == null ? "" : this.semanticVersionPrefix;

    if (versionStaticMax == null) {
      return new Version(VersionType.STATIC, semanticVersionPrefixNotNull + "1.0");
    } else {
      // Here we have the static Version which represents the greatest semantic version.
      // We check if this static Version happens to be one on the current dynamic
      // Version.

      listVersionStatic = this.getListVersionStaticForDynamicVersion(versionDynamic);

      if (listVersionStatic.contains(versionStaticMax)) {
        // If the max static Version is one on the current dynamic version we simply
        // use a new revision of the Version. We could in principle immediately compute
        // the new revision by adding 1 to the last component of
        // arraySemanticVersionComponentMax but we instead simply set the prefix and
        // let the logic compute the new revision which should in principle be the same.
        return new Version(VersionType.STATIC, semanticVersionPrefixNotNull + String.valueOf(arraySemanticVersionComponentMax[0]) + '.' + String.valueOf(arraySemanticVersionComponentMax[1]));
      } else {
        NewSemanticVersionType newSemanticVersionType;

        // First we check if a specific new semantic version type is specified for the module.

        runtimeProperty = runtimePropertiesPlugin.getProperty(module, SemanticSelectStaticVersionPluginImpl.RUNTIME_PROPERTY_NEW_SEMANTIC_VERSION_TYPE);

        if (runtimeProperty != null) {
          newSemanticVersionType = NewSemanticVersionType.valueOf(runtimeProperty);
          userInteractionCallbackPlugin.provideInfo(MessageFormat.format(SemanticSelectStaticVersionPluginImpl.resourceBundle.getString(SemanticSelectStaticVersionPluginImpl.MSG_PATTERN_KEY_NEW_SEMANTIC_VERSION_TYPE_SPECIFIED), new ModuleVersion(module.getNodePath(), versionDynamic), newSemanticVersionType));
        } else {
          String stringNewSemanticVersionType;
          AlwaysNeverAskUserResponse alwaysNeverAskUserResponseCanReuseNewSemanticVersionType;
          NewSemanticVersionType newSemanticVersionTypeReuse;

          // If not, we check for reusing a previously specified semantic version type.

          alwaysNeverAskUserResponseCanReuseNewSemanticVersionType = AlwaysNeverAskUserResponse.valueOfWithAskDefault(runtimePropertiesPlugin.getProperty(module, SemanticSelectStaticVersionPluginImpl.RUNTIME_PROPERTY_CAN_REUSE_NEW_SEMANTIC_VERSION_TYPE));

          runtimeProperty = runtimePropertiesPlugin.getProperty(module, SemanticSelectStaticVersionPluginImpl.RUNTIME_PROPERTY_REUSE_NEW_SEMANTIC_VERSION_TYPE);

          if (runtimeProperty != null) {
            newSemanticVersionTypeReuse = NewSemanticVersionType.valueOf(runtimeProperty);
          } else {
            newSemanticVersionTypeReuse = null;

            if (alwaysNeverAskUserResponseCanReuseNewSemanticVersionType.isAlways()) {
              // Normally if the runtime property CAN_REUSE_NEW_SEMANTIC_VERSION_TYPE is ALWAYS
              // the REUSE_NEW_SEMANTIC_VERSION_TYPE runtime property should also be set. But
              // since these properties are independent and stored externally, it can happen
              // that they are not synchronized. We make an adjustment here to avoid problems.
              alwaysNeverAskUserResponseCanReuseNewSemanticVersionType = AlwaysNeverAskUserResponse.ASK;
            }
          }

          if (alwaysNeverAskUserResponseCanReuseNewSemanticVersionType.isAlways()) {
            userInteractionCallbackPlugin.provideInfo(MessageFormat.format(SemanticSelectStaticVersionPluginImpl.resourceBundle.getString(SemanticSelectStaticVersionPluginImpl.MSG_PATTERN_KEY_NEW_SEMANTIC_VERSION_TYPE_AUTOMATICALLY_REUSED), new ModuleVersion(module.getNodePath(), versionDynamic), newSemanticVersionTypeReuse));
            newSemanticVersionType = newSemanticVersionTypeReuse;
          } else {
            do {
              if (newSemanticVersionTypeReuse == null) {
                stringNewSemanticVersionType = userInteractionCallbackPlugin.getInfo(MessageFormat.format(SemanticSelectStaticVersionPluginImpl.resourceBundle.getString(SemanticSelectStaticVersionPluginImpl.MSG_PATTERN_KEY_INPUT_NEW_SEMANTIC_VERSION_TYPE), new ModuleVersion(module.getNodePath(), versionDynamic), versionStaticMax));
              } else {
                stringNewSemanticVersionType = userInteractionCallbackPlugin.getInfoWithDefault(MessageFormat.format(SemanticSelectStaticVersionPluginImpl.resourceBundle.getString(SemanticSelectStaticVersionPluginImpl.MSG_PATTERN_KEY_INPUT_NEW_SEMANTIC_VERSION_TYPE_WITH_DEFAULT), new ModuleVersion(module.getNodePath(), versionDynamic), versionStaticMax, newSemanticVersionTypeReuse), newSemanticVersionTypeReuse.toString());
              }

              stringNewSemanticVersionType = stringNewSemanticVersionType.toUpperCase().trim();

              newSemanticVersionTypeReuse = null;

              try {
                newSemanticVersionTypeReuse = NewSemanticVersionType.valueOf(stringNewSemanticVersionType);
              } catch (IllegalArgumentException iae) {
                if (stringNewSemanticVersionType.equals("A")) {
                  newSemanticVersionTypeReuse = NewSemanticVersionType.MAJOR;
                } else if (stringNewSemanticVersionType.equals("I")) {
                  newSemanticVersionTypeReuse = NewSemanticVersionType.MINOR;
                }
              }

              if (newSemanticVersionTypeReuse == null) {
                userInteractionCallbackPlugin.provideInfo(MessageFormat.format(SemanticSelectStaticVersionPluginImpl.resourceBundle.getString(SemanticSelectStaticVersionPluginImpl.MSG_PATTERN_KEY_NEW_SEMANTIC_VERSION_TYPE_INVALID), stringNewSemanticVersionType));
                continue;
              }

              break;
            } while (true);

            runtimePropertiesPlugin.setProperty(null, SemanticSelectStaticVersionPluginImpl.RUNTIME_PROPERTY_REUSE_NEW_SEMANTIC_VERSION_TYPE, newSemanticVersionTypeReuse.toString());
            newSemanticVersionType = newSemanticVersionTypeReuse;

            // The result is not useful. We only want to adjust the runtime property which
            // will be reused the next time around.
            Util.getInfoAlwaysNeverAskUserResponseAndHandleAsk(
                runtimePropertiesPlugin,
                SemanticSelectStaticVersionPluginImpl.RUNTIME_PROPERTY_CAN_REUSE_NEW_SEMANTIC_VERSION_TYPE,
                userInteractionCallbackPlugin,
                MessageFormat.format(SemanticSelectStaticVersionPluginImpl.resourceBundle.getString(SemanticSelectStaticVersionPluginImpl.MSG_PATTERN_KEY_AUTOMATICALLY_REUSE_NEW_SEMANTIC_VERSION_TYPE), newSemanticVersionTypeReuse));
          }
        }

        // Here newSemanticVersionType is properly set.

        switch (newSemanticVersionType) {
        case MAJOR:
          return new Version(VersionType.STATIC, semanticVersionPrefixNotNull + String.valueOf(arraySemanticVersionComponentMax[0] + 1) + ".0");

        case MINOR:
          return new Version(VersionType.STATIC, semanticVersionPrefixNotNull + String.valueOf(arraySemanticVersionComponentMax[0]) + '.' + String.valueOf(arraySemanticVersionComponentMax[1] + 1));

        default:
          throw new RuntimeException("Must not get here.");
        }
      }
    }
  }

  /**
   * Gets the semantic version components of a static Version.
   *
   * @param versionStatic Static Version.
   * @return Semantic version components. Null if a semantic version cannot be the
   *   inferred from the static Version.
   */
  private int[] getArraySemanticVersionComponent(Version versionStatic) {
    String version;
    String[] arrayStringSemanticVersionComponent;
    int[] arraySemanticVersionComponent;

    if (versionStatic.getVersionType() != VersionType.STATIC) {
      throw new RuntimeException("Version + " + versionStatic + " must be static");
    }

    version = versionStatic.getVersion();

    if (this.semanticVersionPrefix != null) {
      if (version.startsWith(this.semanticVersionPrefix)) {
        version = version.substring(this.semanticVersionPrefix.length());
      } else {
        SemanticSelectStaticVersionPluginImpl.logger.info("Static version " + versionStatic + " of module " + this.getModule() + " cannot be parsed into a semantic version because it does not start with the prefix " + this.semanticVersionPrefix + ". It is ignored.");
        return null;
      }
    }

    arrayStringSemanticVersionComponent = version.split("\\.");

    if ((arrayStringSemanticVersionComponent.length < 2) || (arrayStringSemanticVersionComponent.length > 3)) {
      SemanticSelectStaticVersionPluginImpl.logger.info("Static version " + versionStatic + " of module " + this.getModule() + " cannot be parsed into a semantic version because it does not have 2 or 3 components. It is ignored.");
      return null;
    }

    // We always allocate 3 elements. If the Version does not contain 3 components,
    // the remaining ones will remain at their default values, 0.

    arraySemanticVersionComponent = new int[3];

    for (int i = 0; i < arrayStringSemanticVersionComponent.length; i++) {
      try {
        arraySemanticVersionComponent[i] = Integer.parseInt(arrayStringSemanticVersionComponent[i]);
      } catch (NumberFormatException nfe) {
        SemanticSelectStaticVersionPluginImpl.logger.info("Static version " + versionStatic + " of module " + this.getModule() + " cannot be parsed into a semantic version because component " + (i + 1) + " cannot be parsed into an integer. It is ignored.");
        return null;
      }
    }

    return arraySemanticVersionComponent;
  }
}
