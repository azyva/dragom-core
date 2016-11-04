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

package org.azyva.dragom.job;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Map;

import org.azyva.dragom.execcontext.ExecContext;
import org.azyva.dragom.execcontext.WorkspaceExecContext;
import org.azyva.dragom.execcontext.support.ExecContextHolder;
import org.azyva.dragom.jenkins.JenkinsClient;
import org.azyva.dragom.model.Model;
import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.NodePath;
import org.azyva.dragom.model.plugin.JenkinsJobCreationPlugin;
import org.azyva.dragom.reference.ReferenceGraph;
import org.azyva.dragom.reference.ReferencePath;

/**
 * Sets up jobs in Jenkins based on the {@link ModuleVersion's} in a
 * {@link ReferenceGraph}.
 * <p>
 * Although creating jobs in a tool such as Jenkins based on a ReferenceGraph is a
 * common that should probably be abstracted, this class is currently specific to
 * Jenkins. Time, experience and maturity will tell if, when and how this process
 * should be abstracted into plugins. But for now at this early stage, it is not
 * deemed pertinent to undertake such a task.
 * <p>
 * Note however that although this class is specific to Jenkins, it still makes
 * use of {@link JenkinsJobCreationPlugin} to abstract the actual job creation
 * details, namely the config.xml file or the template parameters.
 * <p>
 * Jobs created by this class are recorded in the jobs created file, whose content
 * is also used as input if it already exists, in conjunction with a
 * {@link JobsCreatedFileMode}. The default jobs created file, if
 * {@link #setPathJobsCreatedFile} is not called, is jenkins-jobs-created.txt in
 * the metadata directory of the workspace. The default JobsCreatedFileMode, if
 * {@link #setJobsCreatedFileMode} is not called, is
 * {@link JobsCreatedFileMode#MERGE}.
 * <p>
 * While most job classes derive from {@link RootModuleVersionJobAbstractImpl},
 * this class works with a {@link ReferenceGraph} which was presumably created
 * using {@link BuildReferenceGraph}. It works with a ReferenceGraph since for
 * each ModuleVersion it must know the ModuleVersion's which depend on it.
 *
 * @author David Raymond
 */
public class SetupJenkinsJobs {
	/**
	 * Runtime property specifying the Jenkins base URL (e.g.:
	 * https://acme.com/jenkins). Accessed on the root {@link NodePath}.
	 */
	private static String RUNTIME_PROPERTY_JENKINS_BASE_URL = "JENKINS_BASE_URL";

	/**
	 * Runtime property specifying the root folder where jobs are created (e.g.:
	 * "build/ci"). Concatenated with the Jenkins base URL
	 * ({@link #RUNTIME_PROPERTY_JENKINS_BASE_URL}) with "/" as separator. Accessed
	 * on the root {@link NodePath}.
	 */
	private static String RUNTIME_PROPERTY_JOBS_ROOT_FOLDER = "JENKINS_JOBS_ROOT_FOLDER";

	/**
	 * Runtime property specifying the {@link Module}-specific subfolder where the job
	 * for the Module is created.
	 * <p>
	 * If not defined, the parent {@link NodePath} of the Module is used. For example,
	 * if the NodePath of the module is Domain/SubDomain/module, the subfolder used is
	 * Domain/Subdomain.
	 * <p>
	 * Concatenated with the Jenkins base URL
	 * ({@link #RUNTIME_PROPERTY_JENKINS_BASE_URL}) and root folder
	 * ({@link #RUNTIME_PROPERTY_JOBS_ROOT_FOLDER}) with "/" as separator.
	 * <p>
	 * Accessed on the NodePath of the Module for which a job is to be created.
	 */
	private static String RUNTIME_PROPERTY_JOB_SUBFOLDER = "JENKINS_MODULE_SUBFOLDER";

	/**
	 * Runtime property specifying a project name to include in the path of the job.
	 * Can be not specified.
	 * <p>
	 * If it ends with "/", it is considered to be a subfolder.
	 * <p>
	 * Accessed on the NodePath of the Module for which a job is to be created.
	 */
	private static String RUNTIME_PROPERTY_PROJECT = "JENKINS_PROJECT";

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
	private static String RUNTIME_PROPERTY_IND_INCLUDE_VERSION_AS_SUBFOLDER = "JENKINS_IND_INCLUDE_VERSION_AS_SUBFOLDER";

	/**
	 * Default file in the workspace metadata directory containing the jobs created.
	 */
	private static String DEFAULT_JOBS_CREATED_FILE = "jenkins-jobs-created.txt";

	/**
	 * {@link ReferenceGraph}.
	 */
	private ReferenceGraph referenceGraph;

	/**
	 * Path to the file containing the jobs created.
	 * <p>
	 * If null, a default file "jenkins-jobs-created.txt" in the workspace metadata
	 * directory is used.
	 */
	private Path pathJobsCreatedFile;

	/**
	 * Modes for handling the jobs created file if it already exists.
	 */
	private enum JobsCreatedFileMode {
		/**
		 * Add new jobs, replace existing.
		 */
		MERGE,

		/**
		 * Delete previous jobs before adding new ones.
		 * <p>
		 * Complete cleanup can be performed with this mode and specifying a non-matching
		 * {@link ReferencePathMatcher).
		 */
		DELETE_REPLACE,

		/**
		 * Similar to {@link #DELETE_REPLACE}, but also deletes empty subfolders
		 * introduced from {@link SetupJenkinsJobs#RUNTIME_PROPERTY_PROJECT} and
		 * {@link SetupJenkinsJobs#RUNTIME_PROPERTY_IND_INCLUDE_VERSION_AS_SUBFOLDER}.
		 * <p>
		 * No distinction is made between whether such a subfolder initially existed or
		 * not. They are always deleted (if empty).
		 */
		DELETE_EMPTY_REPLACE,

		/**
		 * Replace jobs created file, ignoring its current contents.
		 */
		REPLACE
	}

	/**
	 * Jobs created file mode.
	 */
	private JobsCreatedFileMode jobsCreatedFileMode;

	/**
	 * JenkinsClient.
	 */
	private JenkinsClient jenkinsClient;
	/**
	 * Constructor.
	 *
	 * @param referenceGraph ReferenceGraph.
	 */
	public SetupJenkinsJobs(ReferenceGraph referenceGraph) {
		this.referenceGraph = referenceGraph;
		this.pathJobsCreatedFile = ((WorkspaceExecContext)ExecContextHolder.get()).getPathMetadataDir().resolve(SetupJenkinsJobs.DEFAULT_JOBS_CREATED_FILE);
		this.jobsCreatedFileMode = JobsCreatedFileMode.MERGE;

		??? get base URL from runtime properties.
		??? get user,password from CredentialStorePlugin.
		??? should user be the default from CredentialStorePlugin?
		??? or should we have a RT prop? RT prop would be more flexible, but if not defined, use default.
		this.jenkinsClient = new JenkinsClient(url, user, password);
	}

	/**
	 * @param pathJobsCreatedFile Path to the jobs created file. Can be null to
	 *   disable jobs created file handling. If not called, the default jobs created
	 *   file is jenkins-jobs-created.txt in the metadata directory of the workspace.
	 */
	public void setPathJobsCreatedFile(Path pathJobsCreatedFile) {
		this.pathJobsCreatedFile = pathJobsCreatedFile;
	}

	/**
	 * @param jobsCreatedFileMode JobsCreatedFileMode. If not called, the default
	 *   JobsCreatedFileMode is JobsCreatedFileMode.MERGE.
	 */
	public void setJobsCreatedFileMode(JobsCreatedFileMode jobsCreatedFileMode) {
		this.jobsCreatedFileMode = jobsCreatedFileMode;
	}

	/**
	 * {@link ReferenceGraph.Visitor} used to
	 */
	private class ReferenceGraphVisitorSetupJob implements ReferenceGraph.Visitor {
		/**
		 * Constructor.
		 */
		public ReferenceGraphVisitorSetupJob() {
		}

		@Override
		public ReferenceGraph.VisitControl visit(ReferenceGraph referenceGraph, ReferencePath referencePath, EnumSet<ReferenceGraph.VisitAction> enumSetVisitAction) {
			ExecContext execContext;
			Model model;
			Module module;
			JenkinsJobCreationPlugin jenkinsJobCreationPlugin;
			String template;
			Map<String, String> mapTemplateParam;

			execContext = ExecContextHolder.get();
			model = execContext.getModel();
			module = model.getModule(referencePath.getLeafModuleVersion().getNodePath());
			jenkinsJobCreationPlugin = module.getNodePlugin(JenkinsJobCreationPlugin.class, null);
			template = jenkinsJobCreationPlugin.getTemplate();

			if (template != null) {
				mapTemplateParam = jenkinsJobCreationPlugin.getMapTemplateParam(referenceGraph, referencePath.getLeafModuleVersion().getVersion());
				??? job can be calculated by this class with the runtime properties. Fine.
				??? but somehow, mapTemplateParam must contain the parameter for the list of downstream jobs and the plugin does not know the job names!
				??? probably the plugin must anticipate these downstream references and accept a map of ModuleVersion to job names.
				SetupJenkinsJobs.this.jenkinsClient.createUpdateJobFromTemplate(template, job, mapTemplateParam);

				??? append to jobs created file
			}
		}
	}

	/**
	 * Main method for performing the job.
	 */
	public void performJob() {
		SetupJenkinsJobs.ReferenceGraphVisitorSetupJob referenceGraphVisitorSetupJob;

		??? handle already existing jobs created file.
		??? may need to delete jobs and folders.

		referenceGraphVisitorSetupJob = new SetupJenkinsJobs.ReferenceGraphVisitorSetupJob();

		// Traversal is not depth-first as jobs will often refer to downstream jobs
		// which are actually jobs that correspond to ModuleVersion's higher in the
		// ReferenceGraph.
		this.referenceGraph.traverseReferenceGraph(null, false, ReferenceGraph.ReentryMode.NO_REENTRY, referenceGraphVisitorSetupJob);
	}
}



/*
Take information from dragom.properties file in module (maven version, etc.)
Need to have site-specific fonctionality
- GroupId, artifactId is too specific to Desjardins.
Maybe have some kind of simple plugin that simply builds the config.xml file.
*/