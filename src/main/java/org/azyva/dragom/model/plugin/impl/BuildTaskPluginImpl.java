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

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ResourceBundle;

import org.azyva.dragom.execcontext.ExecContext;
import org.azyva.dragom.execcontext.ToolLifeCycleExecContext;
import org.azyva.dragom.execcontext.plugin.RuntimePropertiesPlugin;
import org.azyva.dragom.execcontext.plugin.UserInteractionCallbackPlugin;
import org.azyva.dragom.execcontext.plugin.WorkspaceDirUserModuleVersion;
import org.azyva.dragom.execcontext.plugin.WorkspacePlugin;
import org.azyva.dragom.execcontext.plugin.WorkspacePlugin.GetWorkspaceDirMode;
import org.azyva.dragom.execcontext.plugin.WorkspacePlugin.WorkspaceDirAccessMode;
import org.azyva.dragom.execcontext.support.ExecContextHolder;
import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.ModuleVersion;
import org.azyva.dragom.model.Version;
import org.azyva.dragom.model.plugin.ArtifactInfoPlugin;
import org.azyva.dragom.model.plugin.BuilderPlugin;
import org.azyva.dragom.model.plugin.ScmPlugin;
import org.azyva.dragom.model.plugin.TaskPlugin;
import org.azyva.dragom.reference.ReferencePath;

/**
 * TaskPlugin that implements the build functionality.
 *
 * @author David Raymond
 */
public class BuildTaskPluginImpl extends ModulePluginAbstractImpl implements TaskPlugin {
	/**
	 * Tool property that specifies the {@link BuildScope}. The default value is
	 * {@link BuildScope#ONLY_USER} if not defined.
	 */
	public static final String TOOL_PROP_BUILD_SCOPE = "BUILD_SCOPE";

	/**
	 * Runtime property specifying the build context to pass to
	 * {@link BuilderPlugin#build}.
	 */
	private static final String RUNTIME_PROPERTY_BUILD_CONTEXT = "BUILD_CONTEXT";

	/**
	 * ID of the build task.
	 */
	public static final String TASK_ID_BUILD = "build";

	/**
	 * Default ID of this plugin.
	 */
	public static final String DEFAULT_PLUGIN_ID = "build";

	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_IGNORING_MODULE_VERSION_ONLY_USER = "IGNORING_MODULE_VERSION_ONLY_USER";

	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_ABORTING_BUILD_ONLY_USER_ABORT_IF_SYSTEM = "ABORTING_BUILD_ONLY_USER_ABORT_IF_SYSTEM";

	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_ABORTING_BUILD_ALL_ABORT_IF_SYSTEM_AND_NO_ARTIFACT = "ABORTING_BUILD_ALL_ABORT_IF_SYSTEM_AND_NO_ARTIFACT";

	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_INITIATING_BUILD = "INITIATING_BUILD";

	/**
	 * ResourceBundle specific to this class.
	 */
	private static final ResourceBundle resourceBundle = ResourceBundle.getBundle(BuildTaskPluginImpl.class.getName() + "ResourceBundle");

	/**
	 * Defines the build scopes.
	 * <p>
	 * Used to parse the BUILD_SCOPE initialization property.
	 */
	public enum BuildScope {
		/**
		 * Build only {@link ModuleVersion}'s that are in a user workspace directory and
		 * ignore ModuleVersion's that are not. This is the default value of the
		 * BUILD_SCOPE tool property if not defined.
		 */
		ONLY_USER,

		/**
		 * Build only {@link ModuleVersion}'s that are in a user workspace directory and
		 * fail if a ModuleVersion needing to be built is not.
		 */
		ONLY_USER_ABORT_IF_SYSTEM,

		/**
		 * Build all {@link ModuleVersion}'s, whether they are in a user workspace
		 * directory or not. This is useful if it is desired to rebuild a ModuleVersion
		 * along with its child ModuleVersion's without having to explicitly check them
		 * out in the workspace.
		 */
		ALL,

		/**
		 * Build all {@link ModuleVersion}'s, but if a ModuleVersion is not in a user
		 * workspace directory, fail if the {@link Module} does not expose the
		 * {@link ArtifactInfoPlugin}, indicating that it does not produce artifacts and
		 * that its build is likely required in a user workspace directory so that
		 * ModuleVersion's that depend on it can in turn build successfully.
		 */
		ALL_ABORT_IF_SYSTEM_AND_NO_ARTIFACT
	}

	/**
	 * Constructor.
	 *
	 * @param module Module.
	 */
	public BuildTaskPluginImpl(Module module) {
		super(module);
	}

	/**
	 * Builds must be performed depth first.
	 */
	@Override
	public boolean isDepthFirst() {
		return true;
	}

	@Override
	public boolean isAvoidReentry() {
		return true;
	}

	@Override
	public TaskPlugin.TaskEffects performTask(String taskId, Version version, ReferencePath referencePath) {
		ExecContext execContext;
		ToolLifeCycleExecContext toolLifeCycleExecContext;
		String stringBuildScope;
		BuildTaskPluginImpl.BuildScope buildScope;
		RuntimePropertiesPlugin runtimePropertiesPlugin;
		String buildContext;
		UserInteractionCallbackPlugin userInteractionCallbackPlugin;
		TaskPlugin.TaskEffects taskEffects;
		ModuleVersion moduleVersion;
		WorkspacePlugin workspacePlugin;
		WorkspaceDirUserModuleVersion workspaceDirUserModuleVersion;
		Path pathModuleWorkspace = null;
		Module module;
		ScmPlugin scmPlugin;
		BuilderPlugin builderPlugin;

		if ((taskId != null) && !taskId.equals(BuildTaskPluginImpl.TASK_ID_BUILD)) {
			throw new RuntimeException("Unsupported task ID " + taskId + '.');
		}

		execContext = ExecContextHolder.get();

		toolLifeCycleExecContext = (ToolLifeCycleExecContext)execContext;

		// TODO: Why is this an INIT_PROP? Shouldn't it be a RuntimeProperty.?
		stringBuildScope = toolLifeCycleExecContext.getToolProperty(BuildTaskPluginImpl.TOOL_PROP_BUILD_SCOPE);

		if (stringBuildScope == null) {
			buildScope = BuildTaskPluginImpl.BuildScope.ONLY_USER;
		} else {
			buildScope = BuildTaskPluginImpl.BuildScope.valueOf(stringBuildScope);
		}

		runtimePropertiesPlugin = execContext.getExecContextPlugin(RuntimePropertiesPlugin.class);

		buildContext = runtimePropertiesPlugin.getProperty(this.getNode(), BuildTaskPluginImpl.RUNTIME_PROPERTY_BUILD_CONTEXT);

		userInteractionCallbackPlugin = ExecContextHolder.get().getExecContextPlugin(UserInteractionCallbackPlugin.class);

		taskEffects = new TaskPlugin.TaskEffects();

		moduleVersion = referencePath.getLeafModuleVersion();

		workspacePlugin = ExecContextHolder.get().getExecContextPlugin(WorkspacePlugin.class);

		workspaceDirUserModuleVersion = new WorkspaceDirUserModuleVersion(moduleVersion);

		try {
			if (workspacePlugin.isWorkspaceDirExist(workspaceDirUserModuleVersion)) {
				pathModuleWorkspace = workspacePlugin.getWorkspaceDir(workspaceDirUserModuleVersion, GetWorkspaceDirMode.GET_EXISTING, WorkspaceDirAccessMode.READ_WRITE);
			} else {
				module = this.getModule();
				scmPlugin = module.getNodePlugin(ScmPlugin.class, null);

				switch (buildScope) {
				case ONLY_USER:
					userInteractionCallbackPlugin.provideInfo(MessageFormat.format(BuildTaskPluginImpl.resourceBundle.getString(BuildTaskPluginImpl.MSG_PATTERN_KEY_IGNORING_MODULE_VERSION_ONLY_USER), moduleVersion));
					return taskEffects;

				case ONLY_USER_ABORT_IF_SYSTEM:
					userInteractionCallbackPlugin.provideInfo(MessageFormat.format(BuildTaskPluginImpl.resourceBundle.getString(BuildTaskPluginImpl.MSG_PATTERN_KEY_ABORTING_BUILD_ONLY_USER_ABORT_IF_SYSTEM), moduleVersion));
					return taskEffects.abort();

				case ALL:
					pathModuleWorkspace = scmPlugin.checkoutSystem(moduleVersion.getVersion());
					break;

				case ALL_ABORT_IF_SYSTEM_AND_NO_ARTIFACT:
					if (!module.isNodePluginExists(ArtifactInfoPlugin.class, null)) {
						userInteractionCallbackPlugin.provideInfo(MessageFormat.format(BuildTaskPluginImpl.resourceBundle.getString(BuildTaskPluginImpl.MSG_PATTERN_KEY_ABORTING_BUILD_ALL_ABORT_IF_SYSTEM_AND_NO_ARTIFACT), moduleVersion));
						return taskEffects.abort();
					}

					pathModuleWorkspace = scmPlugin.checkoutSystem(moduleVersion.getVersion());
					break;
				}
			}

			builderPlugin = this.getModule().getNodePlugin(BuilderPlugin.class,  null);

			if (taskId.equals(BuildTaskPluginImpl.TASK_ID_BUILD)) {
				if (builderPlugin.isSomethingToBuild(pathModuleWorkspace)) {
					try (Writer writerLog = userInteractionCallbackPlugin.provideInfoWithWriter(MessageFormat.format(BuildTaskPluginImpl.resourceBundle.getString(BuildTaskPluginImpl.MSG_PATTERN_KEY_INITIATING_BUILD), moduleVersion, pathModuleWorkspace))) {
						if (!builderPlugin.build(pathModuleWorkspace, buildContext, writerLog)) {
							taskEffects.abort();
						}
					} catch (IOException ioe) {
						throw new RuntimeException(ioe);
					}
				}
			}
		} finally {
			if (pathModuleWorkspace != null) {
				workspacePlugin.releaseWorkspaceDir(pathModuleWorkspace);
			}
		}

		return taskEffects;
	}

	public static String getDefaultPluginId() {
		return BuildTaskPluginImpl.DEFAULT_PLUGIN_ID;
	}
}
