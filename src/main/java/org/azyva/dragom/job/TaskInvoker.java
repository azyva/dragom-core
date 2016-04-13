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
import java.util.List;
import java.util.ResourceBundle;

import org.azyva.dragom.apiutil.ByReference;
import org.azyva.dragom.execcontext.plugin.UserInteractionCallbackPlugin;
import org.azyva.dragom.execcontext.plugin.WorkspacePlugin;
import org.azyva.dragom.execcontext.support.ExecContextHolder;
import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.ModuleVersion;
import org.azyva.dragom.model.Version;
import org.azyva.dragom.model.plugin.ReferenceManagerPlugin;
import org.azyva.dragom.model.plugin.ScmPlugin;
import org.azyva.dragom.model.plugin.TaskPlugin;
import org.azyva.dragom.reference.Reference;
import org.azyva.dragom.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TODO: To review completely. THis was written prior to subclassing from RootModuleVersionJobAbstractImpl.
 * Iterates through modules in reference graphs, invoking an arbitrary TaskPlugin
 * for each of them.
 *
 * This class is designed as other job classes in this package so that tools can
 * easily be built on top of it to allow the user to invoke specific TaskPlugin.
 * In such a case the parameters to the class are generally constructed from user
 * input. See for example the CheckoutTool class.
 *
 * A generic tool can also be built to allow the user to invoke an arbitrary
 * TaskPlugin.
 *
 * It can also be used by other job classes or tools to invoke specific
 * TaskPlugin on Module. In such a case the parameters to the class are generally
 * constructed from other data available to the other job class. See for example
 * the CreateStaticVersion class which uses this class to invoke the
 * TaskChangeReferenceToModuleVersionPlugin.
 *
 * In general TaskPlugin use parameters during their processing. Being generic
 * this class cannot know these parameters in advance. The way for callers to pass
 * parameters to TaskPlugin is generally through the TaskParamsPlugin in the
 * ExecContext. TaskPlugin's are expected to properly document these parameters.
 *
 * Similarly if a TaskPlugin is expected to return information it generally does
 * so through the TaskParamsPlugin.
 * TODO: TaskParamsPlugin does not exist anymore.
 *
 * This class is not aware of such job parameters, but they can be set and
 * retrieved by the caller of this class. Subclasses can be aware of these job
 * parameters however (see below).
 *
 * TaskPlugin can report TaskEffects. Not all TaskEffects are taken into account
 * by this class. In particular the isChanged and isReferenceChanged effects are
 * not taken into account. If such effects are of interest to a job a more
 * specific class may be required.
 *
 * This class implements a simple subclassing mechanism to facilitate the
 * development of other classes making use of TaskPlugin.
 *
 * This class has similarities with {@link RootModuleVersionJobAbstractImpl} from
 * which it derives, but should not be confused. See
 * RootModuleVersionJobAbstractImpl for more information.
 *
 * @author David Raymond
 */
public class TaskInvoker extends RootModuleVersionJobAbstractImpl {
	/**
	 * Logger for the class.
	 */
	private static final Logger logger = LoggerFactory.getLogger(TaskInvoker.class);

	/**
	 * ResourceBundle specific to this class.
	 */
	private static final ResourceBundle resourceBundle = ResourceBundle.getBundle(TaskInvoker.class.getName() + "ResourceBundle");

	/**
	 * ID of the TaskPlugin to invoke.
	 */
	private String taskPluginId;

	/**
	 * ID of the task to invoke within the TaskPlugin.
	 */
	private String taskId;

	/**
	 * Constructor.
	 *
	 * @param taskPluginId ID of the TaskPlugin to invoke.
	 * @param taskId ID of the task to invoke within the TaskPlugin.
	 * @param listModuleVersionRoot List of root ModuleVersion's on which to initiate
	 *   the traversal of the reference graphs.
	 */
	public TaskInvoker(String taskPluginId, String taskId, List<ModuleVersion> listModuleVersionRoot) {
		super(listModuleVersionRoot);

		this.taskPluginId = taskPluginId;
		this.taskId = taskId;
	}

	/**
	 * Visits a ModuleVersion in the context of traversing the reference graph for
	 * invoking the TaskPlugin. This method is called by the base class as well as
	 * recursively by this class.
	 * TODO: Explain why the main loop could not be done within base class.
	 *
	 * @param reference Root ModuleVersion passed as a Reference so that the initial
	 *   parent element of the ReferencePath can be created.
	 * @param byReferenceVersion Not used.
	 * @return false. Currently this class does not handle the case where the Version
	 *   of a root ModuleVersion is changed.
	 */
	@Override
	protected boolean visitModuleVersion(Reference reference, ByReference<Version> byReferenceVersion) {
		UserInteractionCallbackPlugin userInteractionCallbackPlugin;
		UserInteractionCallbackPlugin.BracketHandle bracketHandle;
		WorkspacePlugin workspacePlugin;
		Module module;
		ScmPlugin scmPlugin;
		TaskPlugin taskPlugin;
		TaskPlugin.TaskEffects taskEffects;
		ReferenceManagerPlugin referenceManagerPlugin = null;
		Path pathModuleWorkspace = null;
		List<Reference> listReference;

		this.referencePath.add(reference);

		userInteractionCallbackPlugin = ExecContextHolder.get().getExecContextPlugin(UserInteractionCallbackPlugin.class);
		workspacePlugin = ExecContextHolder.get().getExecContextPlugin(WorkspacePlugin.class);

		bracketHandle = null;

		// We use a try-finally construct to ensure that the current ModuleVersion always
		// gets removed for the current ReferencePath, and that the
		// UserInteractionCallback BracketHandle gets closed.
		try {
			bracketHandle = userInteractionCallbackPlugin.startBracket(MessageFormat.format(RootModuleVersionJobAbstractImpl.resourceBundle.getString(RootModuleVersionJobAbstractImpl.MSG_PATTERN_KEY_VISITING_LEAF_MODULE_VERSION), this.referencePath, this.referencePath.getLeafModuleVersion()));

			module = ExecContextHolder.get().getModel().getModule(reference.getModuleVersion().getNodePath());

			scmPlugin = module.getNodePlugin(ScmPlugin.class, null);
			taskPlugin = module.getNodePlugin(TaskPlugin.class, this.taskPluginId);

			taskEffects = null;

			if (!taskPlugin.isDepthFirst()) {
				if (this.referencePathMatcher.matches(this.referencePath)) {
					if (this.moduleReentryAvoider.processModule(reference.getModuleVersion())) {
						userInteractionCallbackPlugin.provideInfo(MessageFormat.format(RootModuleVersionJobAbstractImpl.resourceBundle.getString(RootModuleVersionJobAbstractImpl.MSG_PATTERN_KEY_VISITING_LEAF_REFERENCE_MATCHED), this.referencePath, this.taskPluginId, this.taskId));

						taskEffects = taskPlugin.performTask(this.taskId, reference.getModuleVersion().getVersion(), this.referencePath);

						if (this.handleTaskEffects(taskEffects)) {
							return false;
						}
					} else {
						userInteractionCallbackPlugin.provideInfo(MessageFormat.format(RootModuleVersionJobAbstractImpl.resourceBundle.getString(RootModuleVersionJobAbstractImpl.MSG_PATTERN_KEY_MODULE_VERSION_ALREADY_PROCESSED), reference.getModuleVersion()));
						return false;
					}
				}
			}

			if (this.referencePathMatcher.canMatchChildren(this.referencePath)) {
				// Here we need to have access to the sources of the module so that we can obtain
				// the list of references and iterate over them. If the user already has the
				// correct version of the module checked out, we need to use it. If not, we need
				// an internal working directory which we will not modify (for now).
				// ScmPlugin.checkoutSystem does that.

				pathModuleWorkspace = scmPlugin.checkoutSystem(reference.getModuleVersion().getVersion());

				try {
					if (!module.isNodePluginExists(ReferenceManagerPlugin.class, null)) {
						listReference = Collections.emptyList();
					} else {
						referenceManagerPlugin = module.getNodePlugin(ReferenceManagerPlugin.class, null);
						listReference = referenceManagerPlugin.getListReference(pathModuleWorkspace);
					}
				} finally {
					workspacePlugin.releaseWorkspaceDir(pathModuleWorkspace);
				}

				for (Reference referenceChild: listReference) {
					if ((taskEffects != null) && taskEffects.getListReferenceSkip().contains(referenceChild)) {
						continue;
					}

					if (referenceChild.getModuleVersion() == null) {
						TaskInvoker.logger.info("Reference " + referenceChild + " within ReferencePath " + this.referencePath + " does not include a source reference known to Dragom. It is not processed.");
						continue;
					}

					TaskInvoker.logger.info("Processing reference " + referenceChild + " within ReferencePath " + this.referencePath + '.');

					this.visitModuleVersion(referenceChild, null);

					if (Util.isAbort()) {
						return false;
					}
				}
			}

			if (taskPlugin.isDepthFirst()) {
				if (this.referencePathMatcher.matches(this.referencePath)) {
					if (this.moduleReentryAvoider.processModule(reference.getModuleVersion())) {
						taskEffects = taskPlugin.performTask(this.taskId, reference.getModuleVersion().getVersion(), this.referencePath);

						this.handleTaskEffects(taskEffects); // No need to test return value since we are done anyways.
					} else {
						userInteractionCallbackPlugin.provideInfo(MessageFormat.format(TaskInvoker.resourceBundle.getString(RootModuleVersionJobAbstractImpl.MSG_PATTERN_KEY_MODULE_VERSION_ALREADY_PROCESSED), reference.getModuleVersion()));
					}
				}
			}
		} finally {
			this.referencePath.removeLeafReference();

			if (bracketHandle != null) {
				bracketHandle.close();
			}
		}

		return false;
	}

	/**
	 * Called after invoking the TaskPlugin. Return value simply indicates if
	 * processing of the current module should stop. If the indAbort flag has been
	 * set, the whole process will also be aborted.
	 *
	 * Subclasses can override this method.
	 *
	 * This default implementation adds taskEffects.getListStringActionPerformed() to
	 * the List of actions performed, sets the indAbort flag according to the
	 * corresponding flag in TaskEffects (taskEffects.isAbort()) and returns the
	 * taskEffects.isSkipChildren().
	 *
	 * @param taskEffects TaskEffects.
	 * @return taskEffects.isSkipChildren().
	 */
	protected boolean handleTaskEffects(TaskPlugin.TaskEffects taskEffects) {
		this.listActionsPerformed.addAll(taskEffects.getListActionPerformed());

		if (taskEffects.isAbort()) {
			Util.setAbort();
		}

		return taskEffects.isSkipChildren();
	}
}
