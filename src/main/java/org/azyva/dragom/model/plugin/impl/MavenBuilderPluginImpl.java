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

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.azyva.dragom.execcontext.ExecContext;
import org.azyva.dragom.execcontext.WorkspaceExecContext;
import org.azyva.dragom.execcontext.plugin.RuntimePropertiesPlugin;
import org.azyva.dragom.execcontext.support.ExecContextHolder;
import org.azyva.dragom.model.Model;
import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.impl.DefaultNode;
import org.azyva.dragom.model.plugin.BuilderPlugin;
import org.azyva.dragom.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link BuilderPlugin} that supports Maven.
 * <p>
 * This plugin uses many properties to build the command line for invoking Maven.
 * Although many such properties are generally expected to be defined at the
 * {@link Model} level, most, if not all, of them can be overridden at many levels
 * so they are accessed as runtime properties using
 * {@link RuntimePropertiesPlugin}.
 * <p>
 * Also, many properties can also be overridden by {@link Module} instances checked
 * out from the SCM, allowing the definition of Module's, including how to build
 * them, to be self-contained. Such properties are read from an optional
 * dragom.properties file at the root of the workspace directory for for the
 * Module.
 * <p>
 * This implementation interprets the build context passed to the {@link #build}
 * method as a comma-separated list of Maven profiles that are passed to Maven.
 * These profiles are combined with those specified by the MAVEN_PROFILES runtime
 * property.
 * <p>
 * This implementation does not support user input during the build process.
 *
 * @author David Raymond
 */
public class MavenBuilderPluginImpl extends ModulePluginAbstractImpl implements BuilderPlugin {
  /**
   * Logger for the class.
   */
  private static final Logger logger = LoggerFactory.getLogger(MavenBuilderPluginImpl.class);

  /**
   * Properties source file within a {@link Module}.
   */
  private static final String DRAGOM_PROPERTIES_FILE = "dragom.properties";

  /**
   * Runtime property that identifies the Maven installation to use.
   * <p>
   * Can also be overridden in the dragom.properties source file of a {@link Module}.
   * <p>
   * Used as a suffix for the other properties MAVEN_HOME, JDK, MAVEN_LOCAL_REPO,
   * MAVEN_SETTINGS and MAVEN_GLOBAL_SETTINGS.
   */
  private static final String RUNTIME_PROPERTY_MAVEN_INSTALLATION = "MAVEN_INSTALLATION";

  /**
   * Runtime property for the Maven home directory. If the runtime property
   * MAVEN_INSTALLATION is defined, it is appended to "MAVEN_HOME." to build a new
   * runtime property to obtain the Maven home directory. If either of these two
   * properties is not defined, MAVEN_HOME is used alone.
   */
  private static final String RUNTIME_PROPERTY_MAVEN_HOME_DIR = "MAVEN_HOME";

  /**
   * Runtime property for the JDK home directory. If the runtime property
   * MAVEN_INSTALLATION is defined, it is appended to "MAVEN_JDK_HOME." to build a
   * new runtime property to obtain the JDK home directory. If either of these two
   * properties is not defined, JDK_HOME is used alone.
   */
  private static final String RUNTIME_PROPERTY_JDK_HOME_DIR = "MAVEN_JDK_HOME";

  /**
   * Runtime property for the Maven local repository directory. If the runtime
   * property MAVEN_INSTALLATION is defined, it is appended to "MAVEN_LOCAL_REPO."
   * to build a new runtime property to obtain the Maven local repository directory.
   * If either of these two properties is not defined, MAVEN_LOCAL_REPO is used
   * alone.
   */
  private static final String RUNTIME_PROPERTY_LOCAL_REPO_DIR = "MAVEN_LOCAL_REPO";

  /**
   * Runtime property for the Maven (user) settings file. If the runtime property
   * MAVEN_INSTALLATION is defined, it is appended to "MAVEN_SETTINGS." to build a
   * new runtime property to obtain the Maven settings file. If either of these two
   * properties is not defined, MAVEN_SETTINGS is used alone.
   * <p>
   * The file path can be absolute or relative. If it is relative it is evaluated
   * based on the root workspace directory (not the Module's).
   */
  private static final String RUNTIME_PROPERTY_SETTINGS_FILE_PATH = "MAVEN_SETTINGS";

  /**
   * Runtime property for the Maven global settings file. If the runtime property
   * MAVEN_INSTALLATION is defined, it is appended to "MAVEN_GLOBAL_SETTINGS." to
   * build a new runtime property to obtain the Maven global settings file. If
   * either of these two properties is not defined, MAVEN_GLOBAL_SETTINGS is used
   * alone.
   * <p>
   * The file path can be absolute or relative. If it is relative it is evaluated
   * based on the root workspace directory (not the Module's).
   */
  private static final String RUNTIME_PROPERTY_GLOBAL_SETTINGS_FILE_PATH = "MAVEN_GLOBAL_SETTINGS";

  /**
   * Runtime property for the Maven targets (lifecycle phases and/or goals). No
   * special treatment is done to the value of this property and it is simply
   * included at the end of the command line. Multiple targets must therefore be
   * separated by spaces.
   * <p>
   * Can also be overridden in the dragom.properties source file of a {@link Module}.
   */
  private static final String RUNTIME_PROPERTY_TARGETS = "MAVEN_TARGETS";

  /**
   * Runtime property that indicates that the lifecycle phase clean should be
   * included before any other target when performing a build.
   */
  private static final String RUNTIME_PROPERTY_IND_CLEAN_BEFORE_BUILD = "MAVEN_IND_CLEAN_BEFORE_BUILD";

  /**
   * Runtime property specifying the Maven properties to include on the command
   * line. Multiple properties must be separated by ",".
   * <p>
   * Can also be overridden in the dragom.properties source file of a {@link Module}.
   * <p>
   * Only the names of the properties must be included. Their values are defined
   * with the MAVEN_PROPERTY runtime property.
   * <p>
   * When evaluating this property it is possible that multiple occurrences of the
   * same Maven property be specified, especially if the $parent$ token is used in
   * the value of the runtime property. See {@link DefaultNode#getProperty}. In that
   * case, duplicate properties (based on the property names) are eliminated.
   * <p>
   * If a property (name) starts with "-", it is removed from the list of
   * properties. This is useful when the $parent$ token is used in the value of the
   * runtime property to cumulate properties, but a property added by a parent
   * {@link DefaultNode} needs to be removed by a child DefaultNode.
   */
  private static final String RUNTIME_PROPERTY_PROPERTIES = "MAVEN_PROPERTIES";

  /**
   * Runtime property specifying the value of a Maven property to include on the
   * command line. See {@link #RUNTIME_PROPERTY_PROPERTIES}.
   * <p>
   * Can also be overridden in the dragom.properties source file of a {@link Module}.
   * <p>
   * It is acceptable for a Maven property identified by
   * RUNTIME_PROPERTY_PROPERTIES to not be defined with MAVEN_PROPERTY since
   * Maven allows to simply define a property without a value, as in
   * "--define skipTests".
   */
  private static final String RUNTIME_PROPERTY_PREFIX_PROPERTY = "MAVEN_PROPERTY.";

  /**
   * Runtime property specifying the Maven profiles to activate on the command line.
   * Multiple profiles must be separated by ",".
   * <p>
   * Can also be overridden in the dragom.properties source file of a {@link Module}.
   * <p>
   * When evaluating this property it is possible that multiple occurrences of the
   * same profile be specified, especially if the $parent$ token is used in the value
   * of the runtime property. See {@link DefaultNode#getProperty}. Duplicates are
   * simply eliminated in that case to avoid confusing Maven, although it would
   * probably itself ignore duplicates.
   * <p>
   * If a profile starts with "-", it is removed from the list of profiles. This is
   * useful when the $parent$ token is used in the value of the runtime property to
   * cumulate profiles, but a profile added by a parent {@link DefaultNode} needs to
   * be removed by a child DefaultNode.
   * <p>
   * The profiles specified by this property are appended to the profiles inferred
   * from the build context passed to {@link #build}, and can be removed using the
   * prefix "-" as described above.
   */
  private static final String RUNTIME_PROPERTY_PROFILES = "MAVEN_PROFILES";

  /**
   * Runtime property that indicates to include the --update-snapshots (-U) option
   * on the command line.
   * <p>
   * Can also be overridden in the dragom.properties source file of a {@link Module}.
   */
  private static final String RUNTIME_PROPERTY_IND_UPDATE_SNAPSHOTS = "MAVEN_IND_UPDATE_SNAPSHOTS";

  /**
   * Runtime property that indicates to include the --fail-fast (-ff) option on the
   * command line.
   * <p>
   * Can also be overridden in the dragom.properties source file of a {@link Module}.
   */
  private static final String RUNTIME_PROPERTY_IND_FAIL_FAST = "MAVEN_IND_FAIL_FAST";

  /**
   * Runtime property to specify a POM file on the command line with the --file (-f)
   * option.
   * <p>
   * Can also be overridden in the dragom.properties source file of a {@link Module}.
   */
  private static final String RUNTIME_PROPERTY_POM_FILE = "MAVEN_POM_FILE";

  /**
   * Runtime property that indicates to include the --offline (-o) option on the
   * command line.
   * <p>
   * Can also be overridden in the dragom.properties source file of a {@link Module}.
   */
  private static final String RUNTIME_PROPERTY_IND_OFFLINE = "MAVEN_IND_OFFLINE";

  /**
   * Runtime property that indicates to include the --show-version (-V) option on the
   * command line.
   * <p>
   * Can also be overridden in the dragom.properties source file of a {@link Module}.
   */
  private static final String RUNTIME_PROPERTY_IND_SHOW_VERSION = "MAVEN_IND_SHOW_VERSION";

  /**
   * Runtime property to specify extra Maven options on the command line.  No
   * special treatment is done to the value of this property and it is simply
   * included at the end of the command line (before the targets). Multiple options
   * must therefore be separated by spaces.
   * <p>
   * Can also be overridden in the dragom.properties source file of a {@link Module}.
   * <p>
   * Currently options containing spaces (enclosed within double-quotes on the
   * command line) are not supported.
   */
  private static final String RUNTIME_PROPERTY_EXTRA_OPTIONS = "MAVEN_EXTRA_OPTIONS";

  /**
   * Runtime property that indicates to write the log of the build process to a
   * file, in addition to writing it to the Writer if provided by the caller.
   * <p>
   * Can also be overridden in the dragom.properties source file of a {@link Module}.
   */
  private static final String RUNTIME_PROPERTY_IND_WRITE_LOG_TO_FILE = "MAVEN_IND_WRITE_LOG_TO_FILE";

  /**
   * File to write the log of the build process to when the
   * MAVEN_IND_WRITE_LOG_TO_FILE runtime property is true.
   * <p>
   * Can also be overridden in the dragom.properties source file of a {@link Module}.
   * <p>
   * The file path can be absolute or relative. If it is relative it is evaluated
   * based either on the root workspace directory or the Module workspace directory,
   * depending on the value of the MAVEN_RELATIVE_LOG_FILE_BASE runtime property.
   * <p>
   * It is an error to have MAVEN_IND_WRITE_LOG_TO_FILE set to true and
   * MAVEN_LOG_FILE not specified. However if MAVEN_IND_WRITE_TO_LOG_FILE is false
   * no log file is written to, regardless of the value of MAVEN_LOG_FILE.
   */
  private static final String RUNTIME_PROPERTY_LOG_FILE_PATH = "MAVEN_LOG_FILE";

  /**
   * Base with respect to which a relative log file path is evaluated. The possible
   * values are:
   * <ul>
   * <li>WORKSPACE: Represents the root workspace directory. This is the default;
   * <li>MODULE: Represents the module workspace directory.
   * </ul>
   * Can also be overridden in the dragom.properties source file of a {@link Module}.
   */
  private static final String RUNTIME_PROPERTY_RELATIVE_LOG_FILE_BASE = "MAVEN_RELATIVE_LOG_FILE_BASE";

  /**
   * Size of the buffer for transferring the output of a build process to the
   * Writer. 8192 is simply a nice adequate binary number.
   */
  private static final int READER_BUFFER_SIZE = 8192;

  /**
   * Enumerates the possible relative log file bases.
   * <p>
   * These are the possible values of the MAVEN_RELATIVE_LOG_FILE_BASE runtime
   * property.
   */
  private enum RelativeLogFileBase {
    /**
     * Represents the root workspace directory. This is the default.
     */
    WORKSPACE,

    /**
     * Represents the module workspace directory.
     */
    MODULE,

    /**
     * Same as {@link #MODULE}, except that if the log file already exists, it is
     * replaced.
     */
    MODULE_REPLACE
  }

  /**
   * Constructor.
   * <p>
   * This constructor is trivial since all properties are accessed as runtime
   * properties.
   *
   * @param module Module.
   */
  public MavenBuilderPluginImpl(Module module) {
    super(module);
  }

  /**
   * @return true since Maven does not really implement build avoidance aka
   *   incremental builds. Individual Maven plugins (e.g., Java compiler) can/may be
   *   optimized in this regard, but the knowledge of whether there is actually
   *   something to build is kept within each such plugins, and not exposed to the
   *   outside world (the one who invokes Maven). Therefore from the point of view
   *   of this plugin implementation, there is always something to build.
   */
  @Override
  public boolean isSomethingToBuild(Path pathModuleWorkspace) {
    return true;
  }

  @Override
  public boolean build(Path pathModuleWorkspace, String buildContext, Writer writerLog) {
    return this.invokeMaven(pathModuleWorkspace, buildContext, writerLog, false);
  }

  /**
   * @return true since Maven builds always generate files that can be cleaned.
   */
  @Override
  public boolean isCleanSupported() {
    return true;
  }

  /**
   * @return true since it is hard to tell from the outside world (the one who
   *   invokes Maven) whether or not there is actually something to clean. It may
   *   actually be possible since generally Maven stores all generated files in a
   *   target sub-directory of each module and submodule, but for now we consider
   *   it is not worth trying to make that optimization.
   */
  @Override
  public boolean isSomethingToClean(Path pathModuleWorkspace) {
    return true;
  }

  @Override
  public boolean clean(Path pathModuleWorkspace, Writer writerLog) {
    return this.invokeMaven(pathModuleWorkspace, null, writerLog, true);
  }

  /**
   * Main method that invokes Maven either for building or cleaning.
   * <p>
   * For we invoke Maven in nearly the same way given the runtime properties,
   * whether we are building or cleaning, hence the factoring of the code in a
   * single method.
   *
   * @param pathModuleWorkspace Path to the Module within the workspace.
   * @param buildContext Build context.
   * @param writerLog Writer where the log of the build or clean process can be
   *   written. Can be null if the caller is not interested in the log.
   * @param indCleanOnly Indicates to clean only.
   * @return Indicates if the build or clean process was successful.
   */
  public boolean invokeMaven(Path pathModuleWorkspace, String buildContext, Writer writerLog, boolean indCleanOnly) {
    Properties propertiesModule;
    ExecContext execContext;
    RuntimePropertiesPlugin runtimePropertiesPlugin;
    String mavenInstallation;
    String mavenHomeDir;
    String jdkHomeDir;
    String localRepoDir;
    String settingsFilePath;
    String globalSettingsFilePath;
    String targets;
    boolean indCleanBeforeBuild;
    String properties;
    String profiles;
    boolean indUpdateSnapshots;
    boolean indFailFast;
    String pomFile;
    boolean indOffline;
    boolean indShowVersion;
    String extraOptions;
    boolean indWriteLogToFile;
    String logFilePath;
    String stringRelativeLogFileBase;
    MavenBuilderPluginImpl.RelativeLogFileBase relativeLogFileBase;
    List<String> listCommandLine;
    ProcessBuilder processBuilder;
    BufferedWriter bufferedWriterLogFile;
    Process process;
    Reader reader;
    int nbCharsRead;
    char[] buffer;

    propertiesModule = this.loadModuleProperties(pathModuleWorkspace);
    execContext = ExecContextHolder.get();
    runtimePropertiesPlugin = execContext.getExecContextPlugin(RuntimePropertiesPlugin.class);

    mavenHomeDir = null;
    jdkHomeDir = null;
    localRepoDir = null;
    settingsFilePath = null;
    globalSettingsFilePath = null;

    mavenInstallation = this.getRuntimeOrModuleProperty(propertiesModule, MavenBuilderPluginImpl.RUNTIME_PROPERTY_MAVEN_INSTALLATION);

    if (mavenInstallation != null) {
      mavenHomeDir = runtimePropertiesPlugin.getProperty(this.getModule(), MavenBuilderPluginImpl.RUNTIME_PROPERTY_MAVEN_HOME_DIR + '.' + mavenInstallation);
      jdkHomeDir = runtimePropertiesPlugin.getProperty(this.getModule(), MavenBuilderPluginImpl.RUNTIME_PROPERTY_JDK_HOME_DIR + '.' + mavenInstallation);
      localRepoDir = runtimePropertiesPlugin.getProperty(this.getModule(), MavenBuilderPluginImpl.RUNTIME_PROPERTY_LOCAL_REPO_DIR + '.' + mavenInstallation);
      settingsFilePath = runtimePropertiesPlugin.getProperty(this.getModule(), MavenBuilderPluginImpl.RUNTIME_PROPERTY_SETTINGS_FILE_PATH + '.' + mavenInstallation);
      globalSettingsFilePath = runtimePropertiesPlugin.getProperty(this.getModule(), MavenBuilderPluginImpl.RUNTIME_PROPERTY_GLOBAL_SETTINGS_FILE_PATH + '.' + mavenInstallation);
    }

    if (mavenHomeDir == null) {
      mavenHomeDir = runtimePropertiesPlugin.getProperty(this.getModule(), MavenBuilderPluginImpl.RUNTIME_PROPERTY_MAVEN_HOME_DIR);
    }

    if (mavenHomeDir == null) {
      mavenHomeDir = System.getenv("MAVEN_HOME");

      if (mavenHomeDir != null) {
        MavenBuilderPluginImpl.logger.info("Maven home directory taken from MAVEN_HOME environment variable.");
      }
    }

    if (mavenHomeDir == null) {
      throw new RuntimeException("Maven home directory not set for module " + this.getModule() + '.');
    }

    MavenBuilderPluginImpl.logger.info("Maven home directory: " + mavenHomeDir);

    if (jdkHomeDir == null) {
      jdkHomeDir = runtimePropertiesPlugin.getProperty(this.getModule(), MavenBuilderPluginImpl.RUNTIME_PROPERTY_JDK_HOME_DIR);
    }

    if (jdkHomeDir == null) {
      jdkHomeDir = System.getenv("JAVA_HOME");

      if (jdkHomeDir != null) {
        MavenBuilderPluginImpl.logger.info("JDK home directory taken from JAVA_HOME environment variable.");
      }
    }

    if (jdkHomeDir == null) {
      throw new RuntimeException("JDK home directory not set for module " + this.getModule() + '.');
    }

    MavenBuilderPluginImpl.logger.info("JDK home directory: " + jdkHomeDir);

    if (localRepoDir == null) {
      localRepoDir = runtimePropertiesPlugin.getProperty(this.getModule(), MavenBuilderPluginImpl.RUNTIME_PROPERTY_LOCAL_REPO_DIR);
    }

    if (settingsFilePath == null) {
      settingsFilePath = runtimePropertiesPlugin.getProperty(this.getModule(), MavenBuilderPluginImpl.RUNTIME_PROPERTY_SETTINGS_FILE_PATH);
    }

    if (globalSettingsFilePath == null) {
      globalSettingsFilePath = runtimePropertiesPlugin.getProperty(this.getModule(), MavenBuilderPluginImpl.RUNTIME_PROPERTY_GLOBAL_SETTINGS_FILE_PATH);
    }

    if (!indCleanOnly) {
      targets = this.getRuntimeOrModuleProperty(propertiesModule, MavenBuilderPluginImpl.RUNTIME_PROPERTY_TARGETS);
    } else {
      targets = null;
    }

    indCleanBeforeBuild = Boolean.parseBoolean(this.getRuntimeOrModuleProperty(propertiesModule, MavenBuilderPluginImpl.RUNTIME_PROPERTY_IND_CLEAN_BEFORE_BUILD));
    properties = this.getRuntimeOrModuleProperty(propertiesModule, MavenBuilderPluginImpl.RUNTIME_PROPERTY_PROPERTIES);
    profiles = this.getRuntimeOrModuleProperty(propertiesModule, MavenBuilderPluginImpl.RUNTIME_PROPERTY_PROFILES);
    indUpdateSnapshots = Boolean.parseBoolean(this.getRuntimeOrModuleProperty(propertiesModule, MavenBuilderPluginImpl.RUNTIME_PROPERTY_IND_UPDATE_SNAPSHOTS));
    indFailFast = Boolean.parseBoolean(this.getRuntimeOrModuleProperty(propertiesModule, MavenBuilderPluginImpl.RUNTIME_PROPERTY_IND_FAIL_FAST));
    pomFile = this.getRuntimeOrModuleProperty(propertiesModule, MavenBuilderPluginImpl.RUNTIME_PROPERTY_POM_FILE);;
    indOffline = Boolean.parseBoolean(this.getRuntimeOrModuleProperty(propertiesModule, MavenBuilderPluginImpl.RUNTIME_PROPERTY_IND_OFFLINE));
    indShowVersion = Boolean.parseBoolean(this.getRuntimeOrModuleProperty(propertiesModule, MavenBuilderPluginImpl.RUNTIME_PROPERTY_IND_SHOW_VERSION));
    extraOptions = this.getRuntimeOrModuleProperty(propertiesModule, MavenBuilderPluginImpl.RUNTIME_PROPERTY_EXTRA_OPTIONS);

    indWriteLogToFile = Boolean.parseBoolean(this.getRuntimeOrModuleProperty(propertiesModule, MavenBuilderPluginImpl.RUNTIME_PROPERTY_IND_WRITE_LOG_TO_FILE));
    logFilePath = this.getRuntimeOrModuleProperty(propertiesModule, MavenBuilderPluginImpl.RUNTIME_PROPERTY_LOG_FILE_PATH);

    if (indWriteLogToFile && (logFilePath == null)) {
      throw new RuntimeException("Logging the build process is activated but the log file is not specified for module " + this.getModule() + '.');
    }

    stringRelativeLogFileBase = this.getRuntimeOrModuleProperty(propertiesModule, MavenBuilderPluginImpl.RUNTIME_PROPERTY_RELATIVE_LOG_FILE_BASE);

    if (stringRelativeLogFileBase != null) {
      relativeLogFileBase = MavenBuilderPluginImpl.RelativeLogFileBase.valueOf(stringRelativeLogFileBase);
    } else {
      relativeLogFileBase = null;
    }

    listCommandLine = new ArrayList<String>();

    if (Util.isWindows()) {
      listCommandLine.add(mavenHomeDir + "/bin/mvn.cmd");
    } else {
      listCommandLine.add(mavenHomeDir + "/bin/mvn");
    }

    listCommandLine.add("--batch-mode");

    if (localRepoDir != null) {
      listCommandLine.add("--define");
      listCommandLine.add("maven.repo.local=" + localRepoDir);
    }

    if (settingsFilePath != null) {
      listCommandLine.add("--settings");
      listCommandLine.add(this.convertWorkspaceRelative(((WorkspaceExecContext)execContext).getPathWorkspaceDir(), settingsFilePath));
    }

    if (globalSettingsFilePath != null) {
      listCommandLine.add("--global-settings");
      listCommandLine.add(this.convertWorkspaceRelative(((WorkspaceExecContext)execContext).getPathWorkspaceDir(), globalSettingsFilePath));
    }

    if (properties != null) {
      String[] tabProperty;
      Set<String> setProperty;

      tabProperty = properties.trim().split("\\s*,\\s*");

      setProperty = new HashSet<String>();

      for (String property: tabProperty) {
        if (property.length() != 0) {
          if (property.charAt(0) == '-') {
            setProperty.remove(property.substring(1));
          } else {
            setProperty.add(property);

          }
        }
      }

      for (String property: setProperty) {
        String value;

        listCommandLine.add("--define");

        value = this.getRuntimeOrModuleProperty(propertiesModule, MavenBuilderPluginImpl.RUNTIME_PROPERTY_PREFIX_PROPERTY + property);

        if (value == null) {
          listCommandLine.add(property);
        } else {
          listCommandLine.add(property + '=' + value);
        }
      }
    }

    if (buildContext != null) {
      if (profiles != null) {
        profiles = buildContext + "," + profiles;
      } else {
        profiles = buildContext;
      }
    }

    if (profiles != null) {
      String[] tabProfile;
      Set<String> setProfile;

      tabProfile = profiles.trim().split("\\s*,\\s*");

      setProfile = new HashSet<String>();

      for (String profile: tabProfile) {
        if (profile.length() != 0) {
          if (profile.charAt(0) == '-') {
            setProfile.remove(profile.substring(1));
          } else {
            setProfile.add(profile);

          }
        }
      }

      listCommandLine.add("--activate-profiles");
      listCommandLine.add(String.join(",", setProfile));
    }

    if (indUpdateSnapshots) {
      listCommandLine.add("--update-snapshots");
    }

    if (indFailFast) {
      listCommandLine.add("--fail-fast");
    }

    if (pomFile != null) {
      listCommandLine.add("--file");
      listCommandLine.add(pomFile);

    }

    if (indOffline) {
      listCommandLine.add("--offline");
    }

    if (indShowVersion) {
      listCommandLine.add("--show-version");
    }

    if (extraOptions != null) {
      listCommandLine.addAll(Arrays.asList(extraOptions.trim().split("\\s+")));
    }

    if (indCleanOnly) {
      targets = "clean";
    } else {
      if (targets == null) {
        targets = "install";
      }

      if (indCleanBeforeBuild) {
        targets = "clean " + targets;
      }
    }

    listCommandLine.addAll(Arrays.asList(targets.trim().split("\\s+")));

    processBuilder = new ProcessBuilder(listCommandLine);
    processBuilder.environment().put("JAVA_HOME", jdkHomeDir);
    processBuilder.directory(pathModuleWorkspace.toFile());
    processBuilder.redirectErrorStream(true);

    if (MavenBuilderPluginImpl.logger.isInfoEnabled()) {
      MavenBuilderPluginImpl.logger.info("Invoking Maven with JAVA_HOME set to " + jdkHomeDir + ", " + pathModuleWorkspace + " as the current working directory and with the following command and arguments: " + listCommandLine);
    }

    try {
      if (indWriteLogToFile) {
        if ((relativeLogFileBase == null) || relativeLogFileBase.equals(MavenBuilderPluginImpl.RelativeLogFileBase.WORKSPACE)) {
          logFilePath = this.convertWorkspaceRelative(((WorkspaceExecContext)execContext).getPathWorkspaceDir(), logFilePath);
        } else {
          logFilePath = this.convertWorkspaceRelative(pathModuleWorkspace, logFilePath);
        }

        // Generally, we want to append to the log file. But we replace it if the
        // RelativeLogFileBase is MODULE_REPLACE.
        bufferedWriterLogFile = new BufferedWriter(new FileWriter(logFilePath, (relativeLogFileBase == null) || (relativeLogFileBase != RelativeLogFileBase.MODULE_REPLACE)));
      } else {
        bufferedWriterLogFile = null;
      }

      process = processBuilder.start();

      // It seems like closing the OutputStream to a process avoids the process hanging
      // if it ever waits for user input, which is not supported by this implementation.
      process.getOutputStream().close();

      buffer = new char[MavenBuilderPluginImpl.READER_BUFFER_SIZE];
      reader = new InputStreamReader(process.getInputStream());

      while ((nbCharsRead = reader.read(buffer)) != -1) {
        // We must pump the output of the build process whether or not we have somewhere
        // to write it to otherwise the bould process would hang when its buffer is full.

        if (writerLog != null) {
          writerLog.write(buffer, 0, nbCharsRead);
        }

        if (bufferedWriterLogFile != null) {
          bufferedWriterLogFile.write(buffer, 0, nbCharsRead);
        }
      }

      if (bufferedWriterLogFile != null) {
        bufferedWriterLogFile.close();
      }

      // The build process is supposed to be terminated here since the loop above
      // terminates when reader.read returns -1 and that happens when the process
      // exits.
      return process.exitValue() == 0;
    } catch (IOException ioe) {
      throw new RuntimeException(ioe);
    }
  }

  /**
   * Instantiates the Properties corresponding to the dragom.properties source
   * file within the {@link Module} workspace directory.
   * <p>
   * This method is not meant to be called repeatedly everytime a single property
   * needs to be retrived as it instantiates a new Properties every time. It is
   * intended to be called once at the beginning of a main method (build or clean)
   * of this plugin.
   *
   * @param pathModuleWorkspace Path to the Module in the workspace.
   * @return See description.
   */
  private Properties loadModuleProperties(Path pathModuleWorkspace) {
    File fileProperties;

    fileProperties = pathModuleWorkspace.resolve(MavenBuilderPluginImpl.DRAGOM_PROPERTIES_FILE).toFile();

    if (fileProperties.isFile()) {
      Properties propertiesModule;

      propertiesModule = new Properties();

      try {
        propertiesModule.load(new FileInputStream(fileProperties));
      } catch (IOException ioe) {
        throw new RuntimeException(ioe);
      }

      return propertiesModule;
    }

    return null;
  }

  /**
   * Returns a runtime property first from the dragom.properties source file within
   * the {@link Module} workspace directory, and then using the
   * {@link RuntimePropertiesPlugin} if not defined by the Module.
   * <p>
   * Despite the desire to abstract access to these the properties in the
   * dragom.properties file as much as possible, we need to pass the Properties as
   * an argument since the scope of these Properties is a single invocation of this
   * plugin. Currently, no attempt is made to cache this information and manage, for
   * example, dragom.properties file timestamp.
   *
   * @param propertiesModule Properties in the dragom.properties source file. Can be
   *   null (if the Module does not provide the dragom.properties file).
   * @param name Name of the runtime property.
   * @return Value of the runtime property.
   */
  private String getRuntimeOrModuleProperty(Properties propertiesModule, String name) {
    String value;

    value = null;

    if (propertiesModule != null) {
      value = propertiesModule.getProperty(name);
    }

    if (value == null) {
      ExecContext execContext;
      RuntimePropertiesPlugin runtimePropertiesPlugin;

      execContext = ExecContextHolder.get();
      runtimePropertiesPlugin = execContext.getExecContextPlugin(RuntimePropertiesPlugin.class);

      value = runtimePropertiesPlugin.getProperty(this.getModule(), name);
    }

    return value;
  }

  /**
   * Converts a path that may be relative by resolving it against another directory.
   *
   * @param pathBaseDir Path to the base directory.
   * @param filePath file path.
   * @return Converted file path.
   */
  private String convertWorkspaceRelative(Path pathBaseDir, String filePath) {
    Path pathFile;

    pathFile = Paths.get(filePath);

    if (pathFile.isAbsolute()) {
      return filePath;
    }

    return pathBaseDir.resolve(pathFile).toString();
  }
}
