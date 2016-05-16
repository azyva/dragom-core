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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;

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
import org.azyva.dragom.reference.Reference;
import org.azyva.dragom.util.RuntimeExceptionUserError;
import org.azyva.dragom.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Job to change the {@link Version} of a referenced {@link ModuleVersion} within
 * a reference graph.
 * <p>
 * This jobs operates on references within dynamic Version's only. References
 * within static Version's are not modified.
 *
 * @author David Raymond
 */
public class ChangeReferenceToModuleVersion extends RootModuleVersionJobAbstractImpl {
	/**
	 * Logger for the class.
	 */
	private static final Logger logger = LoggerFactory.getLogger(ChangeReferenceToModuleVersion.class);

	/**
	 * Prefix for the tool properties which define the {@link ModuleVersion} to
	 * {@link Version} mappings.
	 * <p>
	 * Each property must be suffixed with an index starting at 1. The value of the
	 * properties are of the form {@code <module-version>-><version>}.
	 */
	private static final String TOOL_PROPERTY_PREFIX_MAP_MODULE_VERSION_CHANGE = "MAP_MODULE_VERSION.";

	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_REFERENCE_WILL_BE_CHANGED = "REFERENCE_WILL_BE_CHANGED";

	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_MODULE_VERSION_CHECKED_OUT_IN_USER_WORKSPACE_DIRECTORY = "MODULE_VERSION_CHECKED_OUT_IN_USER_WORKSPACE_DIRECTORY";

	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_CHANGE_REFERENCE_VERSION = "CHANGE_REFERENCE_VERSION";

	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_CHANGE_REFERENCE_VERSION_NO_ARTIFACT_VERSION_CHANGE = "CHANGE_REFERENCE_VERSION_NO_ARTIFACT_VERSION_CHANGE";

	/**
	 * ResourceBundle specific to this class.
	 */
	private static final ResourceBundle resourceBundle = ResourceBundle.getBundle(ChangeReferenceToModuleVersion.class.getName() + "ResourceBundle");

	/**
	 * Map of {@link ModuleVersion}'s to the new {@link Version}'s.
	 */
	private Map<ModuleVersion, Version> mapModuleVersionChange;

	/**
	 * Constructor.
	 *
	 * @param listModuleVersionRoot List of root ModuleVersion's on which to initiate
	 *   the traversal of the reference graphs.
	 */
	public ChangeReferenceToModuleVersion(List<ModuleVersion> listModuleVersionRoot) {
		super(listModuleVersionRoot);

		this.initFromInitProperties();
	}

	/**
	 * Performs the actual operation on the {@link ModuleVersion}'s.
	 *
	 * @param reference Reference.
	 */
	@Override
	protected boolean visitMatchedModuleVersion(Reference reference) {
		Version version;
		Module module;
		ScmPlugin scmPlugin;
		ExecContext execContext;
		ReferenceManagerPlugin referenceManagerPlugin = null;
		Path pathModuleWorkspace;
		UserInteractionCallbackPlugin userInteractionCallbackPlugin;
		List<Reference> listReference;
		WorkspacePlugin workspacePlugin;
		boolean indUserWorkspaceDir;

		version = reference.getModuleVersion().getVersion();

		if (version.getVersionType() == VersionType.STATIC) {
			return false;
		}

		module = ExecContextHolder.get().getModel().getModule(reference.getModuleVersion().getNodePath());

		execContext = ExecContextHolder.get();

		// We do not make changes to nor visit a Module whose Version was changed. We
		// indeed assume that if the caller specified to change a version to another
		// Version, the new Version, along with its own references, is as desired and does
		// not need to be visited.
		// We detect this situation by comparing the Module and its Version being
		// processed to new Version within the Map. This does not really guarantee that
		// the ModuleVersion was changed to that new Version as it may already be at that
		// Version. But if it is already at that Version, we assume there is no need to
		// process the Module.
		for (Map.Entry<ModuleVersion, Version> mapEntry: this.mapModuleVersionChange.entrySet()) {
			if (   mapEntry.getKey().getNodePath().equals(module.getNodePath())
				&& mapEntry.getValue().equals(version)) {

				return false;
			}
		}

		scmPlugin = module.getNodePlugin(ScmPlugin.class, null);

		// Here we need to have access to the sources of the module so that we can obtain
		// the list of references and iterate over them. If the user already has the
		// correct version of the module checked out, we need to use it. If not, we need
		// an internal working directory which we will not modify (for now).
		// ScmPlugin.checkoutSystem does that.

		pathModuleWorkspace = scmPlugin.checkoutSystem(version);

		if (!scmPlugin.isSync(pathModuleWorkspace, ScmPlugin.IsSyncFlag.ALL_CHANGES)) {
			throw new RuntimeExceptionUserError("The directory " + pathModuleWorkspace + " is not synchronized with the SCM. Please synchronize all directories before using this task.");
		}

		if (!module.isNodePluginExists(ReferenceManagerPlugin.class, null)) {
			listReference = Collections.emptyList();
		} else {
			referenceManagerPlugin = module.getNodePlugin(ReferenceManagerPlugin.class, null);
			listReference = referenceManagerPlugin.getListReference(pathModuleWorkspace);
		}

		userInteractionCallbackPlugin = execContext.getExecContextPlugin(UserInteractionCallbackPlugin.class);

		for (Reference referenceChild: listReference) {
			Version versionNew;

			if (referenceChild.getModuleVersion() == null) {
				ChangeReferenceToModuleVersion.logger.info("Reference " + referenceChild + " within ReferencePath " + this.referencePath + " does not include a source reference known to Dragom. It cannot be processed.");
				continue;
			}

			versionNew = this.mapModuleVersionChange.get(referenceChild.getModuleVersion());

			if (versionNew != null) {
				String message;

				userInteractionCallbackPlugin.provideInfo(MessageFormat.format(ChangeReferenceToModuleVersion.resourceBundle.getString(ChangeReferenceToModuleVersion.MSG_PATTERN_KEY_REFERENCE_WILL_BE_CHANGED), this.referencePath, referenceChild, versionNew));

				workspacePlugin = execContext.getExecContextPlugin(WorkspacePlugin.class);
				indUserWorkspaceDir = workspacePlugin.getWorkspaceDirFromPath(pathModuleWorkspace) instanceof WorkspaceDirUserModuleVersion;


				if (indUserWorkspaceDir) {
					userInteractionCallbackPlugin.provideInfo(MessageFormat.format(ChangeReferenceToModuleVersion.resourceBundle.getString(ChangeReferenceToModuleVersion.MSG_PATTERN_KEY_MODULE_VERSION_CHECKED_OUT_IN_USER_WORKSPACE_DIRECTORY), new ModuleVersion(module.getNodePath(), version), pathModuleWorkspace));
				}

				if (!Util.handleDoYouWantToContinue(Util.DO_YOU_WANT_TO_CONTINUE_CONTEXT_UPDATE_REFERENCE)) {
					Util.setAbort();
					return false;
				}

				if (referenceManagerPlugin.updateReferenceVersion(pathModuleWorkspace, referenceChild, versionNew, null)) {
					Map<String, String> mapCommitAttr;

					message = MessageFormat.format(ChangeReferenceToModuleVersion.resourceBundle.getString(ChangeReferenceToModuleVersion.MSG_PATTERN_KEY_CHANGE_REFERENCE_VERSION), this.referencePath, referenceChild, versionNew);
					mapCommitAttr = new HashMap<String, String>();
					mapCommitAttr.put(ScmPlugin.COMMIT_ATTR_REFERENCE_VERSION_CHANGE, "true");
					scmPlugin.commit(pathModuleWorkspace, message, mapCommitAttr);
					userInteractionCallbackPlugin.provideInfo(message);
					this.listActionsPerformed.add(message);

					if (indUserWorkspaceDir) {
						message = MessageFormat.format(Util.getLocalizedMsgPattern(Util.MSG_PATTERN_KEY_PREVIOUS_CHANGE_COMMITTED_SCM), pathModuleWorkspace);
						userInteractionCallbackPlugin.provideInfo(message);
						this.listActionsPerformed.add(message);
					} else {
						ChangeReferenceToModuleVersion.logger.info("The previous change was performed in " + pathModuleWorkspace + " and was committed to the SCM.");
					}
				} else {
					userInteractionCallbackPlugin.provideInfo(MessageFormat.format(ChangeReferenceToModuleVersion.resourceBundle.getString(ChangeReferenceToModuleVersion.MSG_PATTERN_KEY_CHANGE_REFERENCE_VERSION_NO_ARTIFACT_VERSION_CHANGE), this.referencePath, referenceChild, versionNew));
				}
			}
		}

		return true;
	}

	private void initFromInitProperties() {
		ExecContext execContext;
		ToolLifeCycleExecContext toolLifeCycleExecContext;
		int index;

		execContext = ExecContextHolder.get();
		toolLifeCycleExecContext = (ToolLifeCycleExecContext)execContext;

		this.mapModuleVersionChange = new HashMap<ModuleVersion, Version>();

		index = 1;

		do {
			String stringMapModuleVersionChange;
			int indexSeparatorKeyValue;
			ModuleVersion moduleVersion;
			Version version;

			// TODO: Should that be a RuntimeProperty?
			// Should we handle parseexception?
			stringMapModuleVersionChange = toolLifeCycleExecContext.getToolProperty(ChangeReferenceToModuleVersion.TOOL_PROPERTY_PREFIX_MAP_MODULE_VERSION_CHANGE + index);

			if (stringMapModuleVersionChange == null) {
				index = 0;
			}

			indexSeparatorKeyValue = stringMapModuleVersionChange.indexOf("->");

			moduleVersion = new ModuleVersion(stringMapModuleVersionChange.substring(0, indexSeparatorKeyValue).trim());
			version = new Version(stringMapModuleVersionChange.substring(indexSeparatorKeyValue + 2).trim());

			this.mapModuleVersionChange.put(moduleVersion,  version);
		} while (index++ != 0);
	}
}
