package org.azyva.dragom.model.plugin.impl;

import java.text.MessageFormat;
import java.util.ResourceBundle;

import org.azyva.dragom.execcontext.ExecContext;
import org.azyva.dragom.execcontext.plugin.RuntimePropertiesPlugin;
import org.azyva.dragom.execcontext.plugin.UserInteractionCallbackPlugin;
import org.azyva.dragom.execcontext.support.ExecContextHolder;
import org.azyva.dragom.job.SetupJenkinsJobs;
import org.azyva.dragom.model.ClassificationNode;
import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.ModuleVersion;
import org.azyva.dragom.model.NodePath;
import org.azyva.dragom.model.Version;
import org.azyva.dragom.model.plugin.JenkinsJobInfoPlugin;
import org.azyva.dragom.util.AlwaysNeverAskUserResponse;
import org.azyva.dragom.util.Util;

/**
 * Base class to help implement {@link JenkinsJobInfoPlugin} using a simple
 * algorithm to determine part of the Jenkins job information for a
 * {@link ModuleVersion}.
 * <p>
 * This base class is abstract since it does not provide all the required
 * Functionality of JenkinsJobInfoPlugin.
 * <p>
 * It uses runtime properties to determine the job full name
 * ({@link #getJobFullName}) and whether the creation of the parent folder must be
 * handled ({@link #isHandleParentFolderCreation}).
 * <p>
 * However it is up to a subclass to implement the other job configuration
 * methods.
 * <p>
 * Eventually when an expression language is supported, having a complete generic
 * implementation of JenkinsJobInfoPlugin will probably be possible.
 *
 * @author David Raymond
 */
public abstract class SimpleJenkinsJobInfoPluginBaseImpl extends ModulePluginAbstractImpl implements JenkinsJobInfoPlugin {
  /**
   * Runtime property specifying the root folder where jobs are created (e.g.:
   * "build/ci"). Concatenated with the Jenkins base URL managed by
   * {@link SetupJenkinsJobs} with "/" as separator.
   * <p>
   * If not defined, or the empty string, no root folder is used.
   * <p>
   * Accessed on the root {@link ClassificationNode}.
   */
  private static final String RUNTIME_PROPERTY_JOBS_ROOT_FOLDER = "JENKINS_JOBS_ROOT_FOLDER";

  /**
   * Runtime property indicating to use the parent {@link NodePath} of the Module as
   * the subfolder where to create the job. For example, if the NodePath of the
   * module is Domain/SubDomain/module, the subfolder used is Domain/Subdomain.
   * <p>
   * If used, this path is Concatenated with the Jenkins base URL managed by
   * {@link SetupJenkinsJobs} and root folder
   * ({@link #RUNTIME_PROPERTY_JOBS_ROOT_FOLDER}) with "/" as separator.
   * <p>
   * If not defined (or false) and if {@link #RUNTIME_PROPERTY_SUBFOLDER} is not
   * defined either, interaction with the user occurs to allow him to define a
   * a subfolder.
   * <p>
   * If true, RUNTIME_PROPERTY_SUBFOLDER is ignored.
   * <p>
   * Accessed on the {@link Module} for which a job is to be created.
   */
  private static final String RUNTIME_PROPERTY_USE_NODE_PATH_SUBFOLDER = "JENKINS_USE_NODE_PATH";

  /**
   * Runtime property specifying the subfolder where the job for the Module is
   * created.
   * <p>
   * If not defined and if
   * {@link #RUNTIME_PROPERTY_USE_NODE_PATH_SUBFOLDER} is not defined
   * either, interaction with the user occurs to allow him to define a subfolder.
   * <p>
   * Concatenated with the Jenkins base URL managed by {@link SetupJenkinsJobs} and root
   * folder ({@link #RUNTIME_PROPERTY_JOBS_ROOT_FOLDER}) with "/" as separator.
   * <p>
   * Can be defined to the empty string to avoid user interacion and not introduce a
   * subfolder.
   * <p>
   * Accessed on the {@link Module} for which a job is to be created.
   */
  private static final String RUNTIME_PROPERTY_SUBFOLDER = "JENKINS_SUBFOLDER";

  /**
   * Runtime property of type {@link AlwaysNeverAskUserResponse} that indicates if a
   * previously established subfolder can be reused.
   */
  private static final String RUNTIME_PROPERTY_CAN_REUSE_SUBFOLDER = "JENKINS_CAN_REUSE_SUBFOLDER";

  /**
   * Runtime property that specifies the subfolder to reuse.
   */
  private static final String RUNTIME_PROPERTY_REUSE_SUBFOLDER = "JENKINS_REUSE_SUBFOLDER";

  /**
   * Runtime property specifying a project name to include in the path of the job.
   * Can be not specified.
   * <p>
   * In relation to the path built up to now, it is always added as a subfolder
   * (with "/" as separator), unless the path being built is currently empty.
   * <p>
   * If it ends with "/", it is considered to be a subfolder in relation to what will
   * follow. Otherwise, it is considered to be a prefix.
   * <p>
   * Accessed on the {@link Module} for which a job is to be created.
   */
  private static final String RUNTIME_PROPERTY_PROJECT = "JENKINS_PROJECT";

  /**
   * Runtime property indicating to include the {@link Version} of the
   * {@link Module}. The possible values are "true" and "false". If not defined,
   * "false" is assumed.
   * <p>
   * If included, the Version is always prefixed to the Module name that is used as
   * the last part of the job name, with "_" as separator.
   * <p>
   * If {@link #RUNTIME_PROPERTY_PROJECT} defines a subfolder (ends with "/"), the
   * Version and the Module name form the name of the job within the project
   * subfolder. Otherwise, the Version and the Module name are suffixed to the
   * project with "_" as separator to form a 3-part job name within the path being
   * built. If the runtime property "JENKINS_PROJECT" is not defined, the Version
   * and Module name form the name of the job within the path being built, with no
   * project subfolder or prefix.
   * <p>
   * If this property is not defined, then only the Module name is used as described
   * above.
   * <p>
   * Accessed on the {@link Module} for which a job is to be created.
   */
  private static final String RUNTIME_PROPERTY_INCLUDE_VERSION = "JENKINS_INCLUDE_VERSION";

  /**
   * Transient data préfix to cache the job full name. The suffix is the
   * ModuleVersion in littral form.
   */
  private static final String TRANSIENT_DATA_PREFIX_JOB_FULL_NAME = SimpleJenkinsJobInfoPluginBaseImpl.class.getName() + ".JobFullName.";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_SUBFOLDER_AUTOMATICALLY_REUSED = "SUBFOLDER_AUTOMATICALLY_REUSED";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_INPUT_SUBFOLDER = "INPUT_SUBFOLDER";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_INPUT_SUBFOLDER_WITH_DEFAULT = "INPUT_SUBFOLDER_WITH_DEFAULT";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_AUTOMATICALLY_REUSE_SUBFOLDER = "AUTOMATICALLY_REUSE_SUBFOLDER";


  /**
   * ResourceBundle specific to this class.
   */
  private static final ResourceBundle resourceBundle = ResourceBundle.getBundle(SimpleJenkinsJobInfoPluginBaseImpl.class.getName() + "ResourceBundle");



  public SimpleJenkinsJobInfoPluginBaseImpl(Module module) {
    super(module);
    // TODO Auto-generated constructor stub
  }

  @Override
  public String getJobFullName(Version versionDynamic) {
    ExecContext execContext;
    String jobFullName;
    RuntimePropertiesPlugin runtimePropertiesPlugin;
    String jobRootFolder;
    String subfolder;
    UserInteractionCallbackPlugin userInteractionCallbackPlugin;
    Module module;
    ModuleVersion moduleVersion;
    AlwaysNeverAskUserResponse alwaysNeverAskUserResponseCanReuseSubfolder;
    String project;
    boolean indIncludeVersion;
    StringBuilder stringBuilder;

    execContext = ExecContextHolder.get();

    jobFullName = (String)execContext.getTransientData(SimpleJenkinsJobInfoPluginBaseImpl.TRANSIENT_DATA_PREFIX_JOB_FULL_NAME + new ModuleVersion(this.getModule().getNodePath(), versionDynamic));

    if (jobFullName != null) {
      return jobFullName;
    }

    runtimePropertiesPlugin = execContext.getExecContextPlugin(RuntimePropertiesPlugin.class);

    jobRootFolder = runtimePropertiesPlugin.getProperty(null,  SimpleJenkinsJobInfoPluginBaseImpl.RUNTIME_PROPERTY_JOBS_ROOT_FOLDER);

    if (Util.isNotNullAndTrue(runtimePropertiesPlugin.getProperty(this.getModule(),  SimpleJenkinsJobInfoPluginBaseImpl.RUNTIME_PROPERTY_USE_NODE_PATH_SUBFOLDER))) {
      // Will contain the trailing "/".
      subfolder = this.getModule().getNodePath().getNodePathParent().toString();
    } else {
      subfolder = runtimePropertiesPlugin.getProperty(this.getModule(),  SimpleJenkinsJobInfoPluginBaseImpl.RUNTIME_PROPERTY_SUBFOLDER);

      if (subfolder != null) {
        if ((subfolder.length() != 0) && (subfolder.charAt(subfolder.length() - 1) != '/')) {
          subfolder += '/';
        }
      } else {
        // We must interact with the user to obtain a subfolder.

        userInteractionCallbackPlugin = execContext.getExecContextPlugin(UserInteractionCallbackPlugin.class);

        module = this.getModule();
        moduleVersion = new ModuleVersion(module.getNodePath(), versionDynamic);

        alwaysNeverAskUserResponseCanReuseSubfolder = AlwaysNeverAskUserResponse.valueOfWithAskDefault(runtimePropertiesPlugin.getProperty(module, SimpleJenkinsJobInfoPluginBaseImpl.RUNTIME_PROPERTY_CAN_REUSE_SUBFOLDER));

        subfolder = runtimePropertiesPlugin.getProperty(module, SimpleJenkinsJobInfoPluginBaseImpl.RUNTIME_PROPERTY_REUSE_SUBFOLDER);

        if (subfolder == null) {
          if (alwaysNeverAskUserResponseCanReuseSubfolder.isAlways()) {
            // Normally if the runtime property CAN_REUSE_DEST_VERSION is ALWAYS the
            // REUSE_DEST_VERSION runtime property should also be set. But since these
            // properties are independent and stored externally, it can happen that they
            // are not synchronized. We make an adjustment here to avoid problems.
            alwaysNeverAskUserResponseCanReuseSubfolder = AlwaysNeverAskUserResponse.YES_ASK;
          }
        }

        if (alwaysNeverAskUserResponseCanReuseSubfolder.isAlways()) {
          userInteractionCallbackPlugin.provideInfo(MessageFormat.format(SimpleJenkinsJobInfoPluginBaseImpl.resourceBundle.getString(SimpleJenkinsJobInfoPluginBaseImpl.MSG_PATTERN_KEY_SUBFOLDER_AUTOMATICALLY_REUSED), moduleVersion, subfolder));
        } else {
          if (subfolder == null) {
            subfolder = userInteractionCallbackPlugin.getInfo(MessageFormat.format(SimpleJenkinsJobInfoPluginBaseImpl.resourceBundle.getString(SimpleJenkinsJobInfoPluginBaseImpl.MSG_PATTERN_KEY_INPUT_SUBFOLDER), jobRootFolder, moduleVersion));
          } else {
            subfolder = userInteractionCallbackPlugin.getInfoWithDefault(MessageFormat.format(SimpleJenkinsJobInfoPluginBaseImpl.resourceBundle.getString(SimpleJenkinsJobInfoPluginBaseImpl.MSG_PATTERN_KEY_INPUT_SUBFOLDER_WITH_DEFAULT), jobRootFolder, moduleVersion, subfolder), subfolder);
          }

          runtimePropertiesPlugin.setProperty(null, SimpleJenkinsJobInfoPluginBaseImpl.RUNTIME_PROPERTY_REUSE_SUBFOLDER, subfolder);

          // The result is not useful. We only want to adjust the runtime property which
          // will be reused the next time around.
          Util.getInfoAlwaysNeverAskUserResponseAndHandleAsk(
              runtimePropertiesPlugin,
              SimpleJenkinsJobInfoPluginBaseImpl.RUNTIME_PROPERTY_CAN_REUSE_SUBFOLDER,
              userInteractionCallbackPlugin,
              MessageFormat.format(SimpleJenkinsJobInfoPluginBaseImpl.resourceBundle.getString(SimpleJenkinsJobInfoPluginBaseImpl.MSG_PATTERN_KEY_AUTOMATICALLY_REUSE_SUBFOLDER), subfolder));
        }

        if ((subfolder.length() != 0) && (subfolder.charAt(subfolder.length() - 1) != '/')) {
          subfolder += '/';
        }
      }
    }

    project = runtimePropertiesPlugin.getProperty(this.getModule(),  SimpleJenkinsJobInfoPluginBaseImpl.RUNTIME_PROPERTY_PROJECT);
    indIncludeVersion = Util.isNotNullAndTrue(runtimePropertiesPlugin.getProperty(this.getModule(),  SimpleJenkinsJobInfoPluginBaseImpl.RUNTIME_PROPERTY_INCLUDE_VERSION));

    stringBuilder = new StringBuilder();

    if (jobRootFolder != null) {
      stringBuilder.append(jobRootFolder);
    }

    if ((stringBuilder.length() != 0) && (stringBuilder.charAt(stringBuilder.length() - 1) != '/')) {
      stringBuilder.append('/');
    }

    // moduleSubfolder always ends with "/".
    stringBuilder.append(subfolder);

    if (project != null) {
      stringBuilder.append(project);

      if (!project.endsWith("/")) {
        stringBuilder.append('_');
      }
    }

    if (indIncludeVersion) {
      stringBuilder.append(versionDynamic.getVersion());
      stringBuilder.append('_');
    }

    stringBuilder.append(this.getModule().getName());

    jobFullName = stringBuilder.toString();

    execContext.setTransientData(SimpleJenkinsJobInfoPluginBaseImpl.TRANSIENT_DATA_PREFIX_JOB_FULL_NAME + new ModuleVersion(this.getModule().getNodePath(), versionDynamic), jobFullName);

    return jobFullName;
  }

  @Override
  public boolean isHandleParentFolderCreation() {
    ExecContext execContext;
    RuntimePropertiesPlugin runtimePropertiesPlugin;
    String project;

    execContext = ExecContextHolder.get();
    runtimePropertiesPlugin = execContext.getExecContextPlugin(RuntimePropertiesPlugin.class);

    project = runtimePropertiesPlugin.getProperty(this.getModule(),  SimpleJenkinsJobInfoPluginBaseImpl.RUNTIME_PROPERTY_PROJECT);

    return (project != null) && project.endsWith("/");
  }
}
