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
import org.azyva.dragom.execcontext.plugin.CredentialStorePlugin;
import org.azyva.dragom.execcontext.plugin.RuntimePropertiesPlugin;
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
 * use of {@link JenkinsJobInfoPlugin} to abstract the actual job creation
 * details, namely the config.xml file or the template parameters.
 * <p>
 * Jobs and folders created by this class are recorded in an items created file,
 * whose content is also used as input if it already exists, in conjunction with a
 * {@link ItemsCreatedFileMode}. The default items created file, if
 * {@link #setPathItemsCreatedFile} is not called, is jenkins-items-created.txt in
 * the metadata directory of the workspace. The default ItemsCreatedFileMode, if
 * {@link #setItemsCreatedFileMode} is not called, is
 * {@link ItemsCreatedFileMode#MERGE}.
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
	private static final String RUNTIME_PROPERTY_JENKINS_BASE_URL = "JENKINS_BASE_URL";

	/**
	 * Runtime property specifying the user to use to access Jenkins.
	 * <p>
	 * The corresponding password is obtained from {@link CredentialStorePlugin}.
	 * <p>
	 * If not specified, null is passed as the user to CredentialStorePlugin.
	 * <p>
	 * If the value is "" (the empty string), Jenkins is accessed anonymously.
	 */
	private static final String RUNTIME_PROPERTY_JENKINS_USER = "JENKINS_USER";

	/**
	 * Default file in the workspace metadata directory containing the items created.
	 */
	private static final String DEFAULT_ITEMS_CREATED_FILE = "jenkins-items-created.txt";

	/**
	 * {@link ReferenceGraph}.
	 */
	private ReferenceGraph referenceGraph;

	/**
	 * Path to the file containing the items created.
	 * <p>
	 * If null, a default file "jenkins-items-created.txt" in the workspace metadata
	 * directory is used.
	 */
	private Path pathItemsCreatedFile;

	/**
	 * Modes for handling the items created file if it already exists.
	 */
	private enum ItemsCreatedFileMode {
		/**
		 * Replace items created file, ignoring its current contents.
		 */
		IGNORE,

		/**
		 * Add new items, replace existing jobs. Existing folders are not touched, other
		 * than manipulating the jobs within them.
		 */
		MERGE,

		/**
		 * Add new items, replace existing jobs, delete items (folders and jobs) which do
		 * not exist anymore. Existing folders are not touched, other than manipulating
		 * the jobs within them.
		 */
		SYNC,

		/**
		 * Similar to {@link #SYNC}, but folders which do not exist anymore are deleted
		 * only if empty (after having deleted the jobs which do not exist anymore).
		 */
		SYNC_DELETE_FOLDER_ONLY_IF_EMPTY,

		/**
		 * Similar to {@link #SYNC}, but folders are not deleted.
		 */
		SYNC_NO_DELETE_FOLDER,
	}

	/**
	 * Items created file mode.
	 */
	private ItemsCreatedFileMode itemsCreatedFileMode;

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
		ExecContext execContext;
		RuntimePropertiesPlugin runtimePropertiesPlugin;
		CredentialStorePlugin credentialStorePlugin;
		String jenkinsBaseUrl;
		String user;
		String password;

		this.referenceGraph = referenceGraph;
		this.pathItemsCreatedFile = ((WorkspaceExecContext)ExecContextHolder.get()).getPathMetadataDir().resolve(SetupJenkinsJobs.DEFAULT_ITEMS_CREATED_FILE);
		this.itemsCreatedFileMode = ItemsCreatedFileMode.MERGE;

		execContext = ExecContextHolder.get();
		runtimePropertiesPlugin = execContext.getExecContextPlugin(RuntimePropertiesPlugin.class);
		credentialStorePlugin = execContext.getExecContextPlugin(CredentialStorePlugin.class);

		jenkinsBaseUrl = runtimePropertiesPlugin.getProperty(null,  SetupJenkinsJobs.RUNTIME_PROPERTY_JENKINS_BASE_URL);
		user = runtimePropertiesPlugin.getProperty(null,  SetupJenkinsJobs.RUNTIME_PROPERTY_JENKINS_USER);

		if ((user != null) && user.isEmpty()) {
			user = null;
			password = null;
		} else {
			CredentialStorePlugin.Credentials credentials;

			credentials = credentialStorePlugin.getCredentials(
					jenkinsBaseUrl,
					user,
					new CredentialStorePlugin.CredentialValidator() {
						@Override
						public boolean validateCredentials(String resource, String user, String password) {
							JenkinsClient jenkinsClient;

							jenkinsClient = new JenkinsClient(resource, user, password);

							return jenkinsClient.validateCredentials();
						}
					});

			user = credentials.user;
			password = credentials.password;
		}

		this.jenkinsClient = new JenkinsClient(jenkinsBaseUrl, user, password);
	}

	/**
	 * @param pathItemsCreatedFile Path to the items created file. Can be null to
	 *   disable items created file handling. If not called, the default items created
	 *   file is jenkins-items-created.txt in the metadata directory of the workspace.
	 */
	public void setPathItemsCreatedFile(Path pathItemsCreatedFile) {
		this.pathItemsCreatedFile = pathItemsCreatedFile;
	}

	/**
	 * @param itemsCreatedFileMode ItemsCreatedFileMode. If not called, the default
	 *   ItemsCreatedFileMode is {@link ItemsCreatedFileMode#MERGE}.
	 */
	public void setItemsCreatedFileMode(ItemsCreatedFileMode itemsCreatedFileMode) {
		this.itemsCreatedFileMode = itemsCreatedFileMode;
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

				??? append to items created file
			}
		}
	}

	/**
	 * Main method for performing the job.
	 */
	public void performJob() {
		SetupJenkinsJobs.ReferenceGraphVisitorSetupJob referenceGraphVisitorSetupJob;

		??? handle already existing items created file.
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