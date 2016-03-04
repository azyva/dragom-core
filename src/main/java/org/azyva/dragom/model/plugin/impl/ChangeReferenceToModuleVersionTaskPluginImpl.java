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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.azyva.dragom.execcontext.ExecContext;
import org.azyva.dragom.execcontext.ToolLifeCycleExecContext;
import org.azyva.dragom.execcontext.plugin.UserInteractionCallbackPlugin;
import org.azyva.dragom.execcontext.plugin.WorkspaceDirUserModuleVersion;
import org.azyva.dragom.execcontext.plugin.WorkspacePlugin;
import org.azyva.dragom.execcontext.support.ExecContextHolder;
import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.ModuleVersion;
import org.azyva.dragom.model.Version;
import org.azyva.dragom.model.VersionType;
import org.azyva.dragom.model.plugin.ReferenceManagerPlugin;
import org.azyva.dragom.model.plugin.ScmPlugin;
import org.azyva.dragom.model.plugin.TaskPlugin;
import org.azyva.dragom.reference.Reference;
import org.azyva.dragom.reference.ReferencePath;
import org.azyva.dragom.util.RuntimeExceptionUserError;
import org.azyva.dragom.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for TaskPlugin that allows changing references to a ModuleVersion.
 *
 * This TaskPlugin operates on dynamic Version only. References within static
 * Version are not modified. But a dynamic Version reference can be changed
 * (within a parent Module dynamic Version).
 *
 * The Version replacements are expected to be provided in the task parameter
 * org.azyva.dragom.model.plugin.impl.TaskChangeReferenceToModuleVersionPluginFactory.MapModuleVersionChange
 * of type Map<ModuleVersion, Version>.
 *
 * @author David Raymond
 */
public class ChangeReferenceToModuleVersionTaskPluginImpl extends ModulePluginAbstractImpl implements TaskPlugin {
	/**
	 * Logger for the class.
	 */
	private static final Logger logger = LoggerFactory.getLogger(ChangeReferenceToModuleVersionTaskPluginImpl.class);

	/**
	 * Prefix for the tool properties which define the {@link ModuleVersion} to
	 * {@link Version} mappings.
	 */
	private static final String TOOL_PROPERTY_PREFIX_MAP_MODULE_VERSION_CHANGE = "MAP_MODULE_VERSION.";

	/**
	 * The Map of {@link ModuleVersion} to {@link Version} is actually stored as a
	 * transient data that is initialized from the initialization properties
	 * MAP_MODULE_VERSION.* if not defined.
	 */
	public static final String TRANSIENT_DATA_MAP_MODULE_VERSION_CHANGE = ChangeReferenceToModuleVersionTaskPluginImpl.class.getName() + ".MapModuleVersionChange";

	/**
	 * Tool property indicate if a confirmation should be aked to the user before any
	 * change.
	 */
	private static final String TOOL_PROPERTY_IND_NO_CONFIRM = "IND_NO_CONFIRM";

	/**
	 * ID of the only task supported by this TaskPlugin.
	 */
	public static final String TASK_ID = "change-reference-to-module-version";

	/**
	 * Default ID of this plugin.
	 */
	public static final String DEFAULT_PLUGIN_ID = "change-reference-to-module-version";

	public ChangeReferenceToModuleVersionTaskPluginImpl(Module module) {
		super(module);
	}

	/**
	 * We need to traverse the graph parent first since when a Version is changed we
	 * want to skip the children.
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
	@SuppressWarnings("unchecked")
	public TaskPlugin.TaskEffects performTask(String taskId, Version version, ReferencePath referencePath) {
		TaskPlugin.TaskEffects taskEffects;
		Module module;
		ScmPlugin scmPlugin;
		ExecContext execContext;
		Map<ModuleVersion, Version> mapModuleVersionChange;
		ReferenceManagerPlugin referenceManagerPlugin = null;
		Path pathModuleWorkspace;
		UserInteractionCallbackPlugin userInteractionCallbackPlugin;
		List<Reference> listReference;
		WorkspacePlugin workspacePlugin;
		boolean indUserWorkspaceDir;

		if ((taskId != null) && !taskId.equals(ChangeReferenceToModuleVersionTaskPluginImpl.TASK_ID)) {
			throw new RuntimeException("Unsupported task ID " + taskId + '.');
		}

		ChangeReferenceToModuleVersionTaskPluginImpl.initFromInitProperties();

		taskEffects = new TaskPlugin.TaskEffects();

		if (version.getVersionType() == VersionType.STATIC) {
			return taskEffects.skipChildren();
		}

		module = this.getModule();

		execContext = ExecContextHolder.get();
		mapModuleVersionChange = (Map<ModuleVersion, Version>)execContext.getTransientData(ChangeReferenceToModuleVersionTaskPluginImpl.TRANSIENT_DATA_MAP_MODULE_VERSION_CHANGE);

		// We do not make changes to nor visit a Module whose Version was changed. We
		// indeed assume that if the caller specified to change a version to another
		// Version, the new Version, along with its own references, is as desired and does
		// not need to be visited.
		// We detect this situation by comparing the Module and its Version being
		// processed to new Version within the Map. This does not really guarantee that
		// the ModuleVersion was changed to that new Version as it may already be at that
		// Version. But if it is already at that Version, we assume there is no need to
		// process the Module.
		for (Map.Entry<ModuleVersion, Version> mapEntry: mapModuleVersionChange.entrySet()) {
			if (   mapEntry.getKey().getNodePath().equals(module.getNodePath())
				&& mapEntry.getValue().equals(version)) {

				return taskEffects;
			}
		}

		scmPlugin = module.getNodePlugin(ScmPlugin.class, null);

		// Here we need to have access to the sources of the module so that we can obtain
		// the list of references and iterate over them. If the user already has the
		// correct version of the module checked out, we need to use it. If not, we need
		// an internal working directory which we will not modify (for now).
		// ScmPlugin.checkoutSystem does that.

		pathModuleWorkspace = scmPlugin.checkoutSystem(version);

		if (!scmPlugin.isSync(pathModuleWorkspace, ScmPlugin.IsSyncFlagEnum.ALL_CHANGES)) {
			throw new RuntimeExceptionUserError("The directory " + pathModuleWorkspace + " is not synchronized with the SCM. Please synchronize all directories before using this task.");
		}

		if (!module.isNodePluginExists(ReferenceManagerPlugin.class, null)) {
			listReference = Collections.emptyList();
		} else {
			referenceManagerPlugin = module.getNodePlugin(ReferenceManagerPlugin.class, null);
			listReference = referenceManagerPlugin.getListReference(pathModuleWorkspace);
		}

		userInteractionCallbackPlugin = ExecContextHolder.get().getExecContextPlugin(UserInteractionCallbackPlugin.class);

		for (Reference referenceChild: listReference) {
			Version versionNew;

			if (referenceChild.getModuleVersion() == null) {
				ChangeReferenceToModuleVersionTaskPluginImpl.logger.info("Reference " + referenceChild + " within reference path " + referencePath + " does not include a source reference known to Dragom. It cannot be processed.");
				continue;
			}

			versionNew = mapModuleVersionChange.get(referenceChild.getModuleVersion());

			if (versionNew != null) {
				String message;

				userInteractionCallbackPlugin.provideInfo("Reference " + referenceChild + " within reference path " + referencePath + " will be changed to version " + versionNew + '.');

				workspacePlugin = ExecContextHolder.get().getExecContextPlugin(WorkspacePlugin.class);
				indUserWorkspaceDir = workspacePlugin.getWorkspaceDirFromPath(pathModuleWorkspace) instanceof WorkspaceDirUserModuleVersion;


				if (indUserWorkspaceDir) {
					userInteractionCallbackPlugin.provideInfo("This module version is already checked out in " + pathModuleWorkspace + ". The change will be performed in this directory.");
				}

				if (!Util.handleDoYouWantToContinue(Util.DO_YOU_WANT_TO_CONTINUE_CONTEXT_UPDATE_REFERENCE)) {
					return taskEffects.abort();
				}

				if (referenceManagerPlugin.updateReferenceVersion(pathModuleWorkspace, referenceChild, versionNew)) {
					Map<String, String> mapCommitAttr;
					taskEffects.referenceChanged();

					message = "Reference " + referenceChild + " within reference path " + referencePath + " was changed to version " + versionNew + '.';
					mapCommitAttr = new HashMap<String, String>();
					mapCommitAttr.put(ScmPlugin.COMMIT_ATTR_REFERENCE_VERSION_CHANGE, "true");
					scmPlugin.commit(pathModuleWorkspace, message, mapCommitAttr);
					userInteractionCallbackPlugin.provideInfo(message);
					taskEffects.actionPerformed(message);

					if (indUserWorkspaceDir) {
						message = "The previous change was performed in " + pathModuleWorkspace + " which belongs to the user (you) and was committed to the SCM.";
						userInteractionCallbackPlugin.provideInfo(message);
					} else {
						message = "The previous change was performed in " + pathModuleWorkspace + " which belongs to the system and was committed to the SCM.";
						ChangeReferenceToModuleVersionTaskPluginImpl.logger.info(message);
					}

					taskEffects.actionPerformed(message);
				} else {
					userInteractionCallbackPlugin.provideInfo("Reference " + referenceChild + " within reference path " + referencePath + " needed to be changed to version " + versionNew + ", but this did not result in a real change in the reference. No change was performed.");
				}
			}
		}

		return taskEffects;
	}

	@SuppressWarnings("unchecked")
	private static void initFromInitProperties() {
		ExecContext execContext;
		ToolLifeCycleExecContext toolLifeCycleExecContext;
		Map<ModuleVersion, Version> mapModuleVersionChange;

		execContext = ExecContextHolder.get();
		toolLifeCycleExecContext = (ToolLifeCycleExecContext)execContext;

		mapModuleVersionChange = (Map<ModuleVersion, Version>)execContext.getTransientData(ChangeReferenceToModuleVersionTaskPluginImpl.TRANSIENT_DATA_MAP_MODULE_VERSION_CHANGE);

		if (mapModuleVersionChange == null) {
			int index;

			mapModuleVersionChange = new HashMap<ModuleVersion, Version>();

			index = 1;

			do {
				String stringMapModuleVersionChange;
				int indexSeparatorKeyValue;
				ModuleVersion moduleVersion;
				Version version;

				// TODO: Should that be a RuntimeProperty?
				// Should we handle parseexception?
				stringMapModuleVersionChange = toolLifeCycleExecContext.getToolProperty(ChangeReferenceToModuleVersionTaskPluginImpl.TOOL_PROPERTY_PREFIX_MAP_MODULE_VERSION_CHANGE + index);

				if (stringMapModuleVersionChange == null) {
					index = 0;
				}

				indexSeparatorKeyValue = stringMapModuleVersionChange.indexOf("->");

				moduleVersion = new ModuleVersion(stringMapModuleVersionChange.substring(0, indexSeparatorKeyValue).trim());
				version = new Version(stringMapModuleVersionChange.substring(indexSeparatorKeyValue + 2).trim());

				mapModuleVersionChange.put(moduleVersion,  version);
			} while (index++ != 0);

			execContext.setTransientData(ChangeReferenceToModuleVersionTaskPluginImpl.TRANSIENT_DATA_MAP_MODULE_VERSION_CHANGE, mapModuleVersionChange);
		}
	}

	public static String getDefaultPluginId() {
		return CheckoutTaskPluginImpl.DEFAULT_PLUGIN_ID;
	}
}
