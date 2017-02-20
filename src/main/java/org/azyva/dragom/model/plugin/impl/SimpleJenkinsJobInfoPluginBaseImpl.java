package org.azyva.dragom.model.plugin.impl;

import org.azyva.dragom.execcontext.ExecContext;
import org.azyva.dragom.execcontext.plugin.RuntimePropertiesPlugin;
import org.azyva.dragom.execcontext.support.ExecContextHolder;
import org.azyva.dragom.job.SetupJenkinsJobs;
import org.azyva.dragom.model.ClassificationNode;
import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.ModuleVersion;
import org.azyva.dragom.model.NodePath;
import org.azyva.dragom.model.Version;
import org.azyva.dragom.model.plugin.JenkinsJobInfoPlugin;
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
   * Runtime property specifying the {@link Module}-specific subfolder where the job
   * for the Module is created.
   * <p>
   * If not defined, the parent {@link NodePath} of the Module is used. For example,
   * if the NodePath of the module is Domain/SubDomain/module, the subfolder used is
   * Domain/Subdomain.
   * <p>
   * Concatenated with the Jenkins base URL managed by {@link SetupJenkinsJobs} and root
   * folder ({@link #RUNTIME_PROPERTY_JOBS_ROOT_FOLDER}) with "/" as separator.
   * <p>
   * Can be defined to the empty string to avoid introducing a module-specific
   * subfolder.
   * <p>
   * Accessed on the {@link Module} for which a job is to be created.
   */
  private static final String RUNTIME_PROPERTY_MODULE_SUBFOLDER = "JENKINS_MODULE_SUBFOLDER";

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
  private static final String RUNTIME_PROPERTY_IND_INCLUDE_VERSION = "JENKINS_IND_INCLUDE_VERSION";


  public SimpleJenkinsJobInfoPluginBaseImpl(Module module) {
    super(module);
    // TODO Auto-generated constructor stub
  }

  @Override
  public String getJobFullName(Version versionDynamic) {
    ExecContext execContext;
    RuntimePropertiesPlugin runtimePropertiesPlugin;
    String jobRootFolder;
    String moduleSubfolder;
    String project;
    boolean indIncludeVersion;
    StringBuilder stringBuilder;

    execContext = ExecContextHolder.get();
    runtimePropertiesPlugin = execContext.getExecContextPlugin(RuntimePropertiesPlugin.class);

    jobRootFolder = runtimePropertiesPlugin.getProperty(null,  SimpleJenkinsJobInfoPluginBaseImpl.RUNTIME_PROPERTY_JOBS_ROOT_FOLDER);
    moduleSubfolder = runtimePropertiesPlugin.getProperty(this.getModule(),  SimpleJenkinsJobInfoPluginBaseImpl.RUNTIME_PROPERTY_MODULE_SUBFOLDER);
    project = runtimePropertiesPlugin.getProperty(this.getModule(),  SimpleJenkinsJobInfoPluginBaseImpl.RUNTIME_PROPERTY_PROJECT);
    indIncludeVersion = Util.isNotNullAndTrue(runtimePropertiesPlugin.getProperty(this.getModule(),  SimpleJenkinsJobInfoPluginBaseImpl.RUNTIME_PROPERTY_IND_INCLUDE_VERSION));

    stringBuilder = new StringBuilder();

    if (jobRootFolder != null) {
      stringBuilder.append(jobRootFolder);
    }

    if (moduleSubfolder != null) {
      if (stringBuilder.length() != 0) {
        stringBuilder.append('/');
      }

      stringBuilder.append(moduleSubfolder);
    } else {
      if (stringBuilder.length() != 0) {
        stringBuilder.append('/');
      }

      // The NodePath literal ends with "/". We remove it since what follows assumes
      // no trailing "/".
      stringBuilder.append(this.getModule().getNodePath().getNodePathParent());
      stringBuilder.setLength(stringBuilder.length() - 1);
    }

    // In all cases, we have a subfolder separation here.
    if (stringBuilder.length() != 0) {
      stringBuilder.append('/');
    }

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

    return stringBuilder.toString();
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
