package org.azyva.dragom.model.plugin.impl;

import org.azyva.dragom.job.SetupJenkinsJobs;
import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.NodePath;
import org.azyva.dragom.model.Version;
import org.azyva.dragom.model.plugin.JenkinsJobInfoPlugin;

public abstract class SimpleJenkinsJobInfoPluginBaseImpl extends ModulePluginAbstractImpl implements JenkinsJobInfoPlugin {
	/**
	 * Runtime property specifying the root folder where jobs are created (e.g.:
	 * "build/ci"). Concatenated with the Jenkins base URL managed by
	 * {@link SetupJenkinsJobs} with "/" as separator. Accessed on the root
	 * {@link NodePath}.
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
	 * Accessed on the NodePath of the Module for which a job is to be created.
	 */
	private static final String RUNTIME_PROPERTY_JOB_SUBFOLDER = "JENKINS_MODULE_SUBFOLDER";

	/**
	 * Runtime property specifying a project name to include in the path of the job.
	 * Can be not specified.
	 * <p>
	 * If it ends with "/", it is considered to be a subfolder.
	 * <p>
	 * Accessed on the NodePath of the Module for which a job is to be created.
	 */
	private static final String RUNTIME_PROPERTY_PROJECT = "JENKINS_PROJECT";

	/**
	 * Runtime property indicating to include the {@link Vesion} of the {@link Module}
	 * as a subfolder. The possible values are "true" and "false". If not defined,
	 * "false" is assumed.
	 * <p>
	 * If {@link #RUNTIME_PROPERTY_PROJECT} defines a folder (ends with "/"), the
	 * Version is a subfolder of that subfolder. Otherwise, it is concatenated. If
	 * the runtime property "JENKINS_PROJECT" is not defined, the Version is a
	 * subfolder of the path being built.
	 * <p>
	 * Accessed on the NodePath of the Module for which a job is to be created.
	 */
	private static final String RUNTIME_PROPERTY_IND_INCLUDE_VERSION_AS_SUBFOLDER = "JENKINS_IND_INCLUDE_VERSION_AS_SUBFOLDER";


	public SimpleJenkinsJobInfoPluginBaseImpl(Module module) {
		super(module);
		// TODO Auto-generated constructor stub
	}

	@Override
	public String getJobFullName(Version version) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public boolean isHandleParentFolderCreation() {
		// TODO Auto-generated method stub
		return false;
	}
}
