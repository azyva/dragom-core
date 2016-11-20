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

package org.azyva.dragom.job;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.List;
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
import org.azyva.dragom.model.plugin.ArtifactInfoPlugin;
import org.azyva.dragom.model.plugin.BuilderPlugin;
import org.azyva.dragom.model.plugin.ScmPlugin;
import org.azyva.dragom.reference.Reference;
import org.azyva.dragom.util.Util;

/**
 * Build job.
 * <p>
 * The reference graph is traversed depth first and the {@link BuilderPlugin} is
 * used to build each {@link ModuleVersion} sequentially.
 * <p>
 * Currently, parallelism is not supported.
 *
 * @author David Raymond
 */
public class Build extends RootModuleVersionJobAbstractImpl {
	/**
	 * Runtime property that specifies the {@link BuildScope}. The default value is
	 * {@link BuildScope#ONLY_USER} if not defined.
	 * <p>
	 * A runtime property is used so that it is possible to set a default in a context
	 * that is more global than the tool invocation. But often this runtime property
	 * will be provided by the user as a tool initialization property for each tool
	 * invocation.
	 * <p>
	 * It is accessed in the context of each {@link Module} so that its value can be
	 * different for each Module. But in general it will be defined for the root
	 * NodePath only.
	 */
	public static final String RUNTIME_PROPERTY_BUILD_SCOPE = "BUILD_SCOPE";

	/**
	 * Runtime property specifying the build context to pass to
	 * {@link BuilderPlugin#build}.
	 */
	private static final String RUNTIME_PROPERTY_BUILD_CONTEXT = "BUILD_CONTEXT";

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
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_MODULE_VERSION_DOES_NOT_NEED_BUILDING = "MODULE_VERSION_DOES_NOT_NEED_BUILDING";

	/**
	 * ResourceBundle specific to this class.
	 */
	private static final ResourceBundle resourceBundle = ResourceBundle.getBundle(Build.class.getName() + "ResourceBundle");

	/**
	 * Defines the build scopes.
	 * <p>
	 * Used to parse the BUILD_SCOPE initialization property.
	 */
	private enum BuildScope {
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
	 * @param listModuleVersionRoot List of root ModuleVersion's on which to initiate
	 *   the traversal of the reference graphs.
	 */
	public Build(List<ModuleVersion> listModuleVersionRoot) {
		super(listModuleVersionRoot);

		this.setIndDepthFirst(true);
	}

	/**
	 * Performs the actual operation on the {@link ModuleVersion}'s.
	 *
	 * @param reference Reference.
	 * @return false. The return value is not used by RootModuleVersionAbstractImpl
	 *   since the traversal is depth first.
	 */
	@Override
	protected boolean visitMatchedModuleVersion(Reference reference) {
		ExecContext execContext;
		String stringBuildScope;
		Build.BuildScope buildScope;
		RuntimePropertiesPlugin runtimePropertiesPlugin;
		Module module;
		String buildContext;
		UserInteractionCallbackPlugin userInteractionCallbackPlugin;
		ModuleVersion moduleVersion;
		WorkspacePlugin workspacePlugin;
		WorkspaceDirUserModuleVersion workspaceDirUserModuleVersion;
		Path pathModuleWorkspace = null;
		ScmPlugin scmPlugin;
		BuilderPlugin builderPlugin;

		execContext = ExecContextHolder.get();

		runtimePropertiesPlugin = execContext.getExecContextPlugin(RuntimePropertiesPlugin.class);

		module = ExecContextHolder.get().getModel().getModule(reference.getModuleVersion().getNodePath());

		stringBuildScope = runtimePropertiesPlugin.getProperty(module, Build.RUNTIME_PROPERTY_BUILD_SCOPE);

		if (stringBuildScope == null) {
			buildScope = Build.BuildScope.ONLY_USER;
		} else {
			buildScope = Build.BuildScope.valueOf(stringBuildScope);
		}

		buildContext = runtimePropertiesPlugin.getProperty(module, Build.RUNTIME_PROPERTY_BUILD_CONTEXT);

		userInteractionCallbackPlugin = ExecContextHolder.get().getExecContextPlugin(UserInteractionCallbackPlugin.class);

		moduleVersion = reference.getModuleVersion();

		workspacePlugin = ExecContextHolder.get().getExecContextPlugin(WorkspacePlugin.class);

		workspaceDirUserModuleVersion = new WorkspaceDirUserModuleVersion(moduleVersion);

		this.referencePath.add(reference);

		try {
			if (workspacePlugin.isWorkspaceDirExist(workspaceDirUserModuleVersion)) {
				pathModuleWorkspace = workspacePlugin.getWorkspaceDir(workspaceDirUserModuleVersion, GetWorkspaceDirMode.ENUM_SET_GET_EXISTING, WorkspaceDirAccessMode.READ_WRITE);
			} else {
				scmPlugin = module.getNodePlugin(ScmPlugin.class, null);

				switch (buildScope) {
				case ONLY_USER:
					userInteractionCallbackPlugin.provideInfo(MessageFormat.format(Build.resourceBundle.getString(Build.MSG_PATTERN_KEY_IGNORING_MODULE_VERSION_ONLY_USER), moduleVersion));
					return false;

				case ONLY_USER_ABORT_IF_SYSTEM:
					userInteractionCallbackPlugin.provideInfo(MessageFormat.format(Build.resourceBundle.getString(Build.MSG_PATTERN_KEY_ABORTING_BUILD_ONLY_USER_ABORT_IF_SYSTEM), moduleVersion));
					Util.setAbort();
					return false;

				case ALL:
					pathModuleWorkspace = scmPlugin.checkoutSystem(moduleVersion.getVersion());
					break;

				case ALL_ABORT_IF_SYSTEM_AND_NO_ARTIFACT:
					if (!module.isNodePluginExists(ArtifactInfoPlugin.class, null)) {
						userInteractionCallbackPlugin.provideInfo(MessageFormat.format(Build.resourceBundle.getString(Build.MSG_PATTERN_KEY_ABORTING_BUILD_ALL_ABORT_IF_SYSTEM_AND_NO_ARTIFACT), moduleVersion));
						Util.setAbort();
						return false;
					}

					pathModuleWorkspace = scmPlugin.checkoutSystem(moduleVersion.getVersion());
					break;
				}
			}

			builderPlugin = module.getNodePlugin(BuilderPlugin.class,  null);

			if (builderPlugin.isSomethingToBuild(pathModuleWorkspace)) {
				try (Writer writerLog = userInteractionCallbackPlugin.provideInfoWithWriter(MessageFormat.format(Build.resourceBundle.getString(Build.MSG_PATTERN_KEY_INITIATING_BUILD), moduleVersion, pathModuleWorkspace))) {
					if (!builderPlugin.build(pathModuleWorkspace, buildContext, writerLog)) {
						Util.setAbort();
					}
				} catch (IOException ioe) {
					throw new RuntimeException(ioe);
				}
			} else {
				userInteractionCallbackPlugin.provideInfo(MessageFormat.format(Build.resourceBundle.getString(Build.MSG_PATTERN_KEY_MODULE_VERSION_DOES_NOT_NEED_BUILDING), moduleVersion, pathModuleWorkspace));
			}
		} finally {
			if (pathModuleWorkspace != null) {
				workspacePlugin.releaseWorkspaceDir(pathModuleWorkspace);
			}

			this.referencePath.removeLeafReference();
		}

		return false;
	}
}
