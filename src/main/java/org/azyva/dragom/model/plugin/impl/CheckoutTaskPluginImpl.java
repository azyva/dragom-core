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

package org.azyva.dragom.model.plugin.impl;

import java.nio.file.Path;

import org.azyva.dragom.execcontext.plugin.UserInteractionCallbackPlugin;
import org.azyva.dragom.execcontext.plugin.WorkspaceDirUserModuleVersion;
import org.azyva.dragom.execcontext.plugin.WorkspacePlugin;
import org.azyva.dragom.execcontext.plugin.WorkspacePlugin.GetWorkspaceDirModeEnum;
import org.azyva.dragom.execcontext.plugin.WorkspacePlugin.WorkspaceDirAccessMode;
import org.azyva.dragom.execcontext.support.ExecContextHolder;
import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.ModuleVersion;
import org.azyva.dragom.model.Version;
import org.azyva.dragom.model.plugin.ScmPlugin;
import org.azyva.dragom.model.plugin.TaskPlugin;
import org.azyva.dragom.reference.ReferencePath;
import org.azyva.dragom.util.RuntimeExceptionUserError;

/**
 * Factory for TaskPlugin that implements the checkout functionality.
 *
 * @author David Raymond
 */
public class CheckoutTaskPluginImpl extends ModulePluginAbstractImpl implements TaskPlugin {
	/**
	 * Logger for the class.
	 */
	//private static final Logger logger = LoggerFactory.getLogger(TaskCheckoutPluginFactory.class);

	/**
	 * ID of the only task supported by this TaskPlugin.
	 */
	public static final String TASK_ID = "checkout";

	/**
	 * Default ID of this plugin.
	 */
	public static final String DEFAULT_PLUGIN_ID = "checkout";

	public CheckoutTaskPluginImpl(Module module) {
		super(module);
	}

	/**
	 * It is logical to checkout the parent first when traversing the graph.
	 */
	@Override
	public boolean isDepthFirst() {
		return false;
	}

	@Override
	public boolean isAvoidReentry() {
		return true;
	}

	@Override
	public TaskPlugin.TaskEffects performTask(String taskId, Version version, ReferencePath referencePath) {
		UserInteractionCallbackPlugin userInteractionCallbackPlugin;
		TaskPlugin.TaskEffects taskEffects;
		ModuleVersion moduleVersion;
		WorkspacePlugin workspacePlugin;
		WorkspaceDirUserModuleVersion workspaceDirUserModuleVersion;
		Path pathModuleWorkspace = null;
		Module module;
		ScmPlugin scmPlugin;

		if ((taskId != null) && !taskId.equals(CheckoutTaskPluginImpl.TASK_ID)) {
			throw new RuntimeException("Unsupported task ID " + taskId + '.');
		}

		userInteractionCallbackPlugin = ExecContextHolder.get().getExecContextPlugin(UserInteractionCallbackPlugin.class);

		taskEffects = new TaskPlugin.TaskEffects();

		moduleVersion = referencePath.getLeafModuleVersion();

		workspacePlugin = ExecContextHolder.get().getExecContextPlugin(WorkspacePlugin.class);

		workspaceDirUserModuleVersion = new WorkspaceDirUserModuleVersion(moduleVersion);

		if (workspacePlugin.isWorkspaceDirExist(workspaceDirUserModuleVersion)) {
			// We need to call workspacePlugin.getWorkspaceDir simply to obtain the workspace
			// directory. But we do not need to reserve it.
			pathModuleWorkspace = workspacePlugin.getWorkspaceDir(workspaceDirUserModuleVersion, GetWorkspaceDirModeEnum.GET_EXISTING, WorkspaceDirAccessMode.PEEK);

			userInteractionCallbackPlugin.provideInfo("Workspace directory " + pathModuleWorkspace + " for " + workspaceDirUserModuleVersion + " already exists. It is assumed to already contain the checked out sources for ModuleVersion " + moduleVersion + ". No action taken.");
		} else {
			pathModuleWorkspace = workspacePlugin.getWorkspaceDir(workspaceDirUserModuleVersion, GetWorkspaceDirModeEnum.CREATE_NEW_NO_PATH, WorkspaceDirAccessMode.READ_WRITE);

			if (pathModuleWorkspace == null) {
				throw new RuntimeExceptionUserError("A workspace directory for " + workspaceDirUserModuleVersion + " could not be obtained, probably because of a conflict.");
			}

			try {
				userInteractionCallbackPlugin.provideInfo("Checking out ModuleVersion " + moduleVersion + " into " + pathModuleWorkspace + '.');

				module = this.getModule();
				scmPlugin = module.getNodePlugin(ScmPlugin.class, null);

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

		return taskEffects;
	}

	public static String getDefaultPluginId() {
		return CheckoutTaskPluginImpl.DEFAULT_PLUGIN_ID;
	}
}
