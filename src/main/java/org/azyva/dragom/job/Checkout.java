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
import java.text.MessageFormat;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;

import org.azyva.dragom.execcontext.ExecContext;
import org.azyva.dragom.execcontext.plugin.RuntimePropertiesPlugin;
import org.azyva.dragom.execcontext.plugin.UserInteractionCallbackPlugin;
import org.azyva.dragom.execcontext.plugin.WorkspaceDirUserModuleVersion;
import org.azyva.dragom.execcontext.plugin.WorkspacePlugin;
import org.azyva.dragom.execcontext.plugin.WorkspacePlugin.GetWorkspaceDirMode;
import org.azyva.dragom.execcontext.plugin.WorkspacePlugin.WorkspaceDirAccessMode;
import org.azyva.dragom.execcontext.support.ExecContextHolder;
import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.ModuleVersion;
import org.azyva.dragom.model.NodePath;
import org.azyva.dragom.model.Version;
import org.azyva.dragom.model.plugin.ScmPlugin;
import org.azyva.dragom.reference.ReferenceGraph;
import org.azyva.dragom.util.AlwaysNeverYesNoAskUserResponse;
import org.azyva.dragom.util.Util;

/**
 * Checkout job.
 * <p>
 * The main intent of this job is to checkout {@link ModuleVersion}'s in the
 * reference graphs rooted at the root ModuleVersions's and matched by the
 * ReferencePathMatcher. It basically expects to be able to checkout any
 * ModuleVersion in a user workspace directory. If the intent of the user is
 * to replace the workspace directories currently occupied by other
 * {@link Version}'s of the same {@link Module}'s, {@link WorkspaceManagerTool}
 * can be used after the checkout tool to clean user workspace directories for
 * ModuleVersion's that are not reachable from the root ModuleVersion's.
 * <p>
 * But this job nevertheless gracefully supports the case where the
 * {@link WorkspacePlugin} does not support multiple ModuleVersion's for the same
 * Module in user workspace directories by offering the user to switch the content
 * of those existing user workspace directories.
 * <p>
 * If among the selected ModuleVersion's to checkout there are Module's for which
 * multiple ModuleVersion's are selected, the user will be asked to select which
 * one will actually be checked out.
 * <p>
 * In order to do this, a List of the selected ModuleVersion's must be first
 * constructed by traversing the graph, and then the checkout operations can be
 * performed on the ModuleVersion's in the List. {@link BuildReferenceGraph} is
 * used for this purpose.
 * <p>
 * This implies that subclassing {@link RootModuleVersionJobAbstractImpl} is not
 * really useful as very little of its generic fonctionnality is actually reused.
 * But in order to be able to reuse
 * {@link GenericRootModuleVersionJobAbstractImpl} we still derive from that
 * class.
 *
 * @author David Raymond
 */
public class Checkout extends RootModuleVersionJobAbstractImpl {
	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_COMPUTING_LIST_MODULE_VERSION = "COMPUTING_LIST_MODULE_VERSION";

	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_MULTIPLE_MODULE_VERSIONS = "MULTIPLE_MODULE_VERSIONS";

	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_INPUT_MODULE_VERSION_TO_KEEP = "INPUT_MODULE_VERSION_TO_KEEP";

	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_VERSION_INDEX_OUT_OF_BOUNDS = "VERSION_INDEX_OUT_OF_BOUNDS";

	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_MODULE_VERSION_ALREADY_CHECKED_OUT = "MODULE_VERSION_ALREADY_CHECKED_OUT";

	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_CHECKING_OUT_MODULE_VERSION = "CHECKING_OUT_MODULE_VERSION";

	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_DO_YOU_WANT_TO_UPDATE_UNSYNC_REMOTE_CHANGES = "DO_YOU_WANT_TO_UPDATE_UNSYNC_REMOTE_CHANGES";

	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_UPDATING = "UPDATING";

	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_CONFLICTS_WHILE_UPDATING = "CONFLICTS_WHILE_UPDATING";

	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_CANNOT_SWITCH_UNSYNC_LOCAL_CHANGES = "CANNOT_SWITCH_UNSYNC_LOCAL_CHANGES";

	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_DO_YOU_WANT_TO_SWITCH_MODULE_VERSION = "DO_YOU_WANT_TO_SWITCH_MODULE_VERSION";

	/**
	 * Runtime property of the type {@link AlwaysNeverYesNoAskUserResponse} indicating
	 * whether to synchronize the workspace directory when unsynced remote changes
	 * exist.
	 */
	private static final String RUNTIME_PROPERTY_SYNC_WORKSPACE_DIR = "SYNC_WORKSPACE_DIR";

	/**
	 * Runtime property of the type {@link AlwaysNeverYesNoAskUserResponse} indicating
	 * to switch the {@link Version} of a {@link Module} when a workspace directory
	 * already exists for another Version of the same Module.
	 */
	private static final String RUNTIME_PROPERTY_SWITCH_MODULE_VERSION = "SWITCH_MODULE_VERSION";

	/**
	 * ResourceBundle specific to this class.
	 */
	private static final ResourceBundle resourceBundle = ResourceBundle.getBundle(Checkout.class.getName() + "ResourceBundle");

	/**
	 * Constructor.
	 *
	 * @param listModuleVersionRoot List of root ModuleVersion's on which to initiate
	 *   the traversal of the reference graphs.
	 */
	public Checkout(List<ModuleVersion> listModuleVersionRoot) {
		super(listModuleVersionRoot);
	}

	/**
	 * We override the main method since we do not really reuse this class'
	 * functionality. See main description of class.
	 */
	@Override
	public void performJob() {
		ExecContext execContext;
		UserInteractionCallbackPlugin userInteractionCallbackPlugin;
		BuildReferenceGraph buildReferenceGraph;
		ReferenceGraph referenceGraph;
		List<ModuleVersion> listModuleVersion;
		WorkspacePlugin workspacePlugin;

		execContext = ExecContextHolder.get();
		userInteractionCallbackPlugin = execContext.getExecContextPlugin(UserInteractionCallbackPlugin.class);

		/* *******************************************************************************
		 * Compute the List of ModuleVersion to checkout based on the matched
		 * ModuleVersion's and if the WorkspacePlugin does not support multiple Version's
		 * for the same Module, we let the user specify which Version's to actually
		 * checkout.
		 * We do not handle the fact that a Version of a Module may already be checked out
		 * and a switch may be required. This is handled below, independently of the
		 * selection of the single Version for each Module.
		 * *******************************************************************************/

		userInteractionCallbackPlugin.provideInfo(Checkout.resourceBundle.getString(Checkout.MSG_PATTERN_KEY_COMPUTING_LIST_MODULE_VERSION));

		buildReferenceGraph = new BuildReferenceGraph(null, this.listModuleVersionRoot);
		buildReferenceGraph.setReferencePathMatcher(this.referencePathMatcher);
		buildReferenceGraph.performJob();

		referenceGraph = buildReferenceGraph.getReferenceGraph();

		// Here, listModuleVersion contains the matched ModuleVersion's.
		listModuleVersion = referenceGraph.getListModuleVersionMatched();

		workspacePlugin = ExecContextHolder.get().getExecContextPlugin(WorkspacePlugin.class);

		if (!workspacePlugin.isSupportMultipleModuleVersion()) {
			Map<NodePath, List<Version>> mapModuleVersion;

			// If the WorkspacePlugin does not support multiple Version's for the same Module,
			// we transform the List of ModuleVersion's into a Map from the Module's
			// NodePath's to the List of Version's for each Module.

			mapModuleVersion = new LinkedHashMap<NodePath, List<Version>>();

			for (ModuleVersion moduleVersion: listModuleVersion) {
				List<Version> listVersion;

				listVersion = mapModuleVersion.get(moduleVersion.getNodePath());

				if (listVersion == null) {
					listVersion = new ArrayList<Version>();
					mapModuleVersion.put(moduleVersion.getNodePath(),  listVersion);
				}

				listVersion.add(moduleVersion.getVersion());
			}

			// We reuse listModuleVersion for the new List of selected ModuleVersion's that
			// will be built.
			listModuleVersion = new ArrayList<ModuleVersion>();

			for (Map.Entry<NodePath, List<Version>> mapEntry: mapModuleVersion.entrySet()) {
				if (mapEntry.getValue().size() > 1) {
					StringBuilder stringBuilder;
					int index;
					String selectedVersion;
					Version versionSelected;

					// If there is more than one Version, we must interact with the user to let him
					// select the desired one.

					stringBuilder = new StringBuilder();
					index = 1;

					for (Version version: mapEntry.getValue()) {
						if (index != 1) {
							stringBuilder.append('\n');
						}

						stringBuilder.append(index++).append(" - ").append(version);
					}

					userInteractionCallbackPlugin.provideInfo(MessageFormat.format(Checkout.resourceBundle.getString(Checkout.MSG_PATTERN_KEY_MULTIPLE_MODULE_VERSIONS), mapEntry.getKey(), stringBuilder));

					versionSelected = null;

					do {
						selectedVersion = userInteractionCallbackPlugin.getInfo(Checkout.resourceBundle.getString(Checkout.MSG_PATTERN_KEY_INPUT_MODULE_VERSION_TO_KEEP));

						try {
							index = Integer.parseInt(selectedVersion);

							if ((index < 1) || (index > mapEntry.getValue().size())) {
								userInteractionCallbackPlugin.provideInfo(MessageFormat.format(Checkout.resourceBundle.getString(Checkout.MSG_PATTERN_KEY_VERSION_INDEX_OUT_OF_BOUNDS), selectedVersion, 1, mapEntry.getValue().size()));
							} else {
								versionSelected = mapEntry.getValue().get(index - 1);
							}
						} catch (NumberFormatException nfe) {
						}

						if (versionSelected == null) {
							try {
								versionSelected = Version.parse(selectedVersion);
							} catch (ParseException pe) {
								userInteractionCallbackPlugin.provideInfo(pe.getMessage());
							}
						}
						if (versionSelected == null) {
							userInteractionCallbackPlugin.provideInfo(Util.getLocalizedMsgPattern(Util.MSG_PATTERN_KEY_TRY_AGAIN));
						}
					} while (versionSelected == null);


					listModuleVersion.add(new ModuleVersion(mapEntry.getKey(), versionSelected));
				} else {
					// If there is only one Version for the Module, we use it.
					listModuleVersion.add(new ModuleVersion(mapEntry.getKey(), mapEntry.getValue().get(0)));
				}
			}
		}

		/* *******************************************************************************
		 * Perform the actual checkout for the selected ModuleVersion's
		 * *******************************************************************************/

		for (ModuleVersion moduleVersion: listModuleVersion) {
			RuntimePropertiesPlugin runtimePropertiesPlugin;
			Module module;
			ScmPlugin scmPlugin;
			WorkspaceDirUserModuleVersion workspaceDirUserModuleVersion;
			WorkspaceDirUserModuleVersion workspaceDirUserModuleVersionConflict;
			AlwaysNeverYesNoAskUserResponse alwaysNeverYesNoAskUserResponse;
			Path pathModuleWorkspace;

			runtimePropertiesPlugin = execContext.getExecContextPlugin(RuntimePropertiesPlugin.class);
			module = ExecContextHolder.get().getModel().getModule(moduleVersion.getNodePath());
			scmPlugin = module.getNodePlugin(ScmPlugin.class, null);

			workspaceDirUserModuleVersion = new WorkspaceDirUserModuleVersion(moduleVersion);
			workspaceDirUserModuleVersionConflict = (WorkspaceDirUserModuleVersion)workspacePlugin.getWorkspaceDirConflict(workspaceDirUserModuleVersion);

			if ((workspaceDirUserModuleVersionConflict != null) && !workspaceDirUserModuleVersionConflict.getModuleVersion().getNodePath().equals(moduleVersion.getNodePath())) {
				throw new RuntimeException("Workspace conflicts where two different Module's share the same workspace directory (same Module name) are not supported.");
			}

			if (workspacePlugin.isSupportMultipleModuleVersion() || (workspaceDirUserModuleVersionConflict == null)) {
				if (workspacePlugin.isWorkspaceDirExist(workspaceDirUserModuleVersion)) {
					pathModuleWorkspace = workspacePlugin.getWorkspaceDir(workspaceDirUserModuleVersion, GetWorkspaceDirMode.ENUM_SET_GET_EXISTING, WorkspaceDirAccessMode.READ_WRITE);

					try {
						userInteractionCallbackPlugin.provideInfo(MessageFormat.format(Checkout.resourceBundle.getString(Checkout.MSG_PATTERN_KEY_MODULE_VERSION_ALREADY_CHECKED_OUT), pathModuleWorkspace, moduleVersion));

						// TODO: The code below is not really useful since BuildReferenceGraph that is
						// used above ensures that the workspace directories are all synchronized when
						// building the ReferenceGraph. For now we still leave it there, just in case the
						// logic eventually changes and it becomes useful.
						if (!scmPlugin.isSync(pathModuleWorkspace, ScmPlugin.IsSyncFlag.REMOTE_CHANGES_ONLY)) {
							alwaysNeverYesNoAskUserResponse = Util.getInfoAlwaysNeverYesNoAskUserResponseAndHandleAsk(
									runtimePropertiesPlugin,
									Checkout.RUNTIME_PROPERTY_SYNC_WORKSPACE_DIR,
									userInteractionCallbackPlugin,
									MessageFormat.format(Checkout.resourceBundle.getString(Checkout.MSG_PATTERN_KEY_DO_YOU_WANT_TO_UPDATE_UNSYNC_REMOTE_CHANGES), pathModuleWorkspace, moduleVersion));

							if (alwaysNeverYesNoAskUserResponse.isYes()) {
								userInteractionCallbackPlugin.provideInfo(MessageFormat.format(Checkout.resourceBundle.getString(Checkout.MSG_PATTERN_KEY_UPDATING), pathModuleWorkspace, moduleVersion));

								if (scmPlugin.update(pathModuleWorkspace)) {
									userInteractionCallbackPlugin.provideInfo(MessageFormat.format(Checkout.resourceBundle.getString(Checkout.MSG_PATTERN_KEY_CONFLICTS_WHILE_UPDATING), pathModuleWorkspace, moduleVersion));
								}
							}
						}
					} finally {
						if (pathModuleWorkspace != null) {
							workspacePlugin.releaseWorkspaceDir(pathModuleWorkspace);
						}
					}
				} else {
					pathModuleWorkspace = workspacePlugin.getWorkspaceDir(workspaceDirUserModuleVersion, GetWorkspaceDirMode.ENUM_SET_CREATE_NEW_NO_PATH, WorkspaceDirAccessMode.READ_WRITE);

					try {
						userInteractionCallbackPlugin.provideInfo(MessageFormat.format(Checkout.resourceBundle.getString(Checkout.MSG_PATTERN_KEY_CHECKING_OUT_MODULE_VERSION), moduleVersion, pathModuleWorkspace));

						scmPlugin.checkout(moduleVersion.getVersion(), pathModuleWorkspace);
					} catch (RuntimeException re) {
						workspacePlugin.deleteWorkspaceDir(workspaceDirUserModuleVersion);
						pathModuleWorkspace = null; // To prevent the call to workspacePlugin.releaseWorkspaceDir below.
						throw re;
					} finally {
						if (pathModuleWorkspace != null) {
							workspacePlugin.releaseWorkspaceDir(pathModuleWorkspace);
						}
					}
				}
			} else {
				pathModuleWorkspace = workspacePlugin.getWorkspaceDir(workspaceDirUserModuleVersionConflict, GetWorkspaceDirMode.ENUM_SET_GET_EXISTING, WorkspaceDirAccessMode.READ_WRITE);

				try {
					// TODO: The code below is not really useful since BuildReferenceGraph that is
					// used above ensures that the workspace directories are all synchronized when
					// building the ReferenceGraph. For now we still leave it there, just in case the
					// logic eventually changes and it becomes useful.
					if (!scmPlugin.isSync(pathModuleWorkspace, ScmPlugin.IsSyncFlag.LOCAL_CHANGES_ONLY)) {
						userInteractionCallbackPlugin.provideInfo(MessageFormat.format(Checkout.resourceBundle.getString(Checkout.MSG_PATTERN_KEY_CANNOT_SWITCH_UNSYNC_LOCAL_CHANGES), pathModuleWorkspace, workspaceDirUserModuleVersionConflict.getModuleVersion(),  moduleVersion.getVersion()));

						if (!Util.handleDoYouWantToContinue(Util.DO_YOU_WANT_TO_CONTINUE_CONTEXT_SWITCH_WITH_UNSYNC_LOCAL_CHANGES)) {
							return;
						}
					} else {
						alwaysNeverYesNoAskUserResponse = Util.getInfoAlwaysNeverYesNoAskUserResponseAndHandleAsk(
								runtimePropertiesPlugin,
								Checkout.RUNTIME_PROPERTY_SWITCH_MODULE_VERSION,
								userInteractionCallbackPlugin,
								MessageFormat.format(Checkout.resourceBundle.getString(Checkout.MSG_PATTERN_KEY_DO_YOU_WANT_TO_SWITCH_MODULE_VERSION), pathModuleWorkspace, workspaceDirUserModuleVersionConflict.getModuleVersion(),  moduleVersion.getVersion()));

						if (alwaysNeverYesNoAskUserResponse.isYes()) {
							scmPlugin.switchVersion(pathModuleWorkspace, moduleVersion.getVersion());
						}
					}
				} finally {
					if (pathModuleWorkspace != null) {
						workspacePlugin.releaseWorkspaceDir(pathModuleWorkspace);
					}
				}
			}
		}
	}
}