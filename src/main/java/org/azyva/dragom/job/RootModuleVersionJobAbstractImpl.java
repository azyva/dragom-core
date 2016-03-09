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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang.StringUtils;
import org.azyva.dragom.apiutil.ByReference;
import org.azyva.dragom.execcontext.plugin.UserInteractionCallbackPlugin;
import org.azyva.dragom.execcontext.plugin.WorkspaceDir;
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
import org.azyva.dragom.reference.ReferencePath;
import org.azyva.dragom.reference.ReferencePathMatcher;
import org.azyva.dragom.reference.ReferencePathMatcherAll;
import org.azyva.dragom.util.ModuleReentryAvoider;
import org.azyva.dragom.util.RuntimeExceptionUserError;
import org.azyva.dragom.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class for implementing tasks based on root {@link ModuleVersions}'s.
 * <p>
 * It factors out code that is often encountered in these types of tasks.
 * <p>
 * After validating the root ModuleVersion's, it iterates over them. For each
 * ModuleVersion it calls {@link #visitModuleVersion}.
 * <p>
 * This class does not attempt to completely encapsulate its implementation. It
 * has protected instance variables available to subclasses to simplify
 * implementation.
 *
 * @author David Raymond
 */
public abstract class RootModuleVersionJobAbstractImpl {
	/**
	 * Logger for the class.
	 */
	private static final Logger logger = LoggerFactory.getLogger(RootModuleVersionJobAbstractImpl.class);

	/**
	 * List root ModuleVersion's on which to initiate the traversal of the reference
	 * graphs.
	 */
	protected List<ModuleVersion> listModuleVersionRoot;

	/**
	 * ReferencePathMatcher defining on which ModuleVersion's in the reference graphs
	 * the job will be applied.
	 */
	protected ReferencePathMatcher referencePathMatcher;

	/**
	 * Indicates that dynamic {@link Version}'s must be considered during the
	 * traversal of the reference graphs. The default value is true.
	 */
	protected boolean indHandleDynamicVersion = true;

	/**
	 * Indicates that static {@link Version}'s must be considered during the
	 * traversal of the reference graphs. The default value is true.
	 */
	protected boolean indHandleStaticVersion = true;

	/**
	 * Indicates that {@link #visitModuleVersion} avoids reentry by using
	 * {@link ModuleReentryAvoider}. The default value is true.
	 */
	protected boolean indAvoidReentry = true;

	/*
	 * {@link ModuleReentryAvoider}.
	 */
	protected ModuleReentryAvoider moduleReentryAvoider;

	/**
	 * Subclasses can use this variable during the traversal of a reference graph to
	 * maintain the current ReferencePath being visited. Upon entry in a method that
	 * visits a ModuleVersion, this variable represents the ReferencePath of the
	 * parent. During processing it is modified to represent the ReferencePath of the
	 * current ModuleVersion and referenced modules as the graph is traversed. Upon
	 * exit it is reset to what it was upon entry.
	 *
	 * This is used mainly in messages since it is useful for the user to know from
	 * which Reference a ModuleVersion within a ReferencePath comes from. A root
	 * ModuleVersion is wrapped in a Reference.
	 */
	protected ReferencePath referencePath;

	/**
	 * Used to accumulate a description for the actions performed.
	 */
	protected List<String> listActionsPerformed;

	/**
	 * Constructor.
	 *
	 * @param listModuleVersionRoot List of root ModuleVersion's on which to initiate
	 *   the traversal of the reference graphs.
	 */
	protected RootModuleVersionJobAbstractImpl(List<ModuleVersion> listModuleVersionRoot) {
		this.listModuleVersionRoot = listModuleVersionRoot;

		this.moduleReentryAvoider = new ModuleReentryAvoider();

		// By default assume all ReferencePath's are to be matched.
		this.referencePathMatcher = new ReferencePathMatcherAll();

		this.referencePath = new ReferencePath();
		this.listActionsPerformed = new ArrayList<String>();
	}

	/**
	 * Sets the ReferencePathMatcher defining on which ModuleVersion's in the
	 * reference graphs the job will be applied.
	 *
	 * @param referencePathMatcher See description.
	 */
	public void setReferencePathMatcher(ReferencePathMatcher referencePathMatcher) {
		this.referencePathMatcher = referencePathMatcher;
	}

	/**
	 * @param indHandleDynamicVersion Specifies to handle or not dynamic
	 *   {@link Version}'s. The default is to handle dynamic {@link Version}.
	 */
	protected void setIndHandleDynamicVersion(boolean indHandleDynamicVersion) {
		this.indHandleDynamicVersion = indHandleDynamicVersion;
	}

	/**
	 * @param indHandleStaticVersion Specifies to handle or not static
	 *   {@link Version}'s. The default is to handle static {@link Version}.
	 */
	protected void setIndHandleStaticVersion(boolean indHandleStaticVersion) {
		this.indHandleStaticVersion = indHandleStaticVersion;
	}

	/**
	 * @param indHandleStaticVersion Specifies to handle or not static
	 *   {@link Version}'s. The default is to handle static {@link Version}.
	 */
	protected void setIndAvoidReentry(boolean indAvoidReentry) {
		this.indAvoidReentry = indAvoidReentry;
	}

	/**
	 * Main method for performing the job. It is expected that caller invoke this
	 * method to perform the job.
	 *
	 * This class provides a default implementation which calls
	 * validateListModuleVersionRoot and then iterateListThroughModuleVersionRoot.
	 * If this behavior is not appropriate for the job, subclasses can simply
	 * override the method. Alternatively, the methods mentioned above can be
	 * overridden individually.
	 *
	 * @return Indicates if the List of root ModuleVersion's passed to the constructor
	 *   of the class was modified and should be saved by the caller if it is
	 *   persisted.
	 */
	public boolean performTask() {
		// A regular | operator is used to force both methods to be called. The ||
		// operator would avoid calling the second method if the first one returns
		// true.
		return this.validateListModuleVersionRoot() | this.iterateThroughListModuleVersionRoot();
	}

	/**
	 * Validates the root ModuleVersion's.
	 *
	 * This performs a first pass to validate the root ModuleVersion's. The reason
	 * is that if one ModuleVersion is invalid (module not known to the model or
	 * Version does not exist), many actions may have already been performed and
	 * it is better for the user to detect the error before doing anything.
	 *
	 * @return Indicates if the List of root ModuleVersion's passed to the constructor
	 *   of the class was modified and should be saved by the caller if it is
	 *   persisted.
	 */
	protected boolean validateListModuleVersionRoot() {
		boolean indListRootModuleVersionChanged;
		int indexModuleVersionRoot;
		ModuleVersion moduleVersion;
		Module module;
		ScmPlugin scmPlugin;

		RootModuleVersionJobAbstractImpl.logger.info("Starting the iteration among the root ModuleVersion's " + this.listModuleVersionRoot + " to validate them.");

		indListRootModuleVersionChanged = false;

		for (indexModuleVersionRoot = 0; indexModuleVersionRoot < this.listModuleVersionRoot.size(); indexModuleVersionRoot++) {
			moduleVersion = this.listModuleVersionRoot.get(indexModuleVersionRoot);

			RootModuleVersionJobAbstractImpl.logger.info("Validating root module " + moduleVersion.getNodePath() + '.');

			module = ExecContextHolder.get().getModel().getModule(moduleVersion.getNodePath());

			if (module == null) {
				throw new RuntimeExceptionUserError("Root module " + moduleVersion.getNodePath() + " is not known to the model.");
			}

			scmPlugin = module.getNodePlugin(ScmPlugin.class, null);

			// The Version can be null to indicate the main version.
			if (moduleVersion.getVersion() == null) {
				WorkspacePlugin workspacePlugin;
				Set<WorkspaceDir> setWorkspaceDir;
				Version version;

				workspacePlugin = ExecContextHolder.get().getExecContextPlugin(WorkspacePlugin.class);
				setWorkspaceDir = workspacePlugin.getSetWorkspaceDir(new WorkspaceDirUserModuleVersion(moduleVersion));

				if (setWorkspaceDir.size() > 1) {
					throw new RuntimeExceptionUserError("The root ModuleVersion " + moduleVersion + " does not specify a version and multiple workspace directories contain versions of this module.");
				}

				if (setWorkspaceDir.size() == 1) {
					version = ((WorkspaceDirUserModuleVersion)setWorkspaceDir.iterator().next()).getModuleVersion().getVersion();
					RootModuleVersionJobAbstractImpl.logger.info("Root ModuleVersion " + moduleVersion + " does not specify a version. We update it to the version " + version + " of the module within the workspace.");
				} else {
					version = scmPlugin.getDefaultVersion();
					RootModuleVersionJobAbstractImpl.logger.info("Root ModuleVersion " + moduleVersion + " does not specify a version. We update it to the default version " + version + '.');
				}

				// ModuleVersion is immutable. We need to create a new one.
				this.listModuleVersionRoot.set(indexModuleVersionRoot,  moduleVersion = new ModuleVersion(moduleVersion.getNodePath(), version));
				indListRootModuleVersionChanged = true;
			}

			if (!scmPlugin.isVersionExists(moduleVersion.getVersion())) {
				throw new RuntimeExceptionUserError("Version " + moduleVersion.getVersion() + " of root module " + moduleVersion.getNodePath() + " does not exist.");
			}
		}

		RootModuleVersionJobAbstractImpl.logger.info("Iteration among all root ModuleVersion's " + this.listModuleVersionRoot + " completed for validation.");

		return indListRootModuleVersionChanged;
	}

	/**
	 * Iterates through the List of root ModuleVersion's calling
	 * visitModuleVersion for each root ModuleVersion.
	 *
	 * @return Indicates if the List of root ModuleVersion's passed to the constructor
	 *   of the class was modified and should be saved by the caller if it is
	 *   persisted.
	 */
	protected boolean iterateThroughListModuleVersionRoot() {
		boolean indListRootModuleVersionChanged;
		UserInteractionCallbackPlugin userInteractionCallbackPlugin;
		ByReference<Version> byReferenceVersion;
		int indexModuleVersionRoot;
		ModuleVersion moduleVersion;

		userInteractionCallbackPlugin = ExecContextHolder.get().getExecContextPlugin(UserInteractionCallbackPlugin.class);

		indListRootModuleVersionChanged = false;
		byReferenceVersion = new ByReference<Version>();

		RootModuleVersionJobAbstractImpl.logger.info("Starting the iteration among the root ModuleVersion's " + this.listModuleVersionRoot + '.');

		for (indexModuleVersionRoot = 0; indexModuleVersionRoot < this.listModuleVersionRoot.size(); indexModuleVersionRoot++) {
			boolean indVersionChanged;

			moduleVersion = this.listModuleVersionRoot.get(indexModuleVersionRoot);

			RootModuleVersionJobAbstractImpl.logger.info("Initiating a traversal of the reference graph rooted at ModuleVersion " + moduleVersion + '.');

			indVersionChanged = this.visitModuleVersion(new Reference(moduleVersion), byReferenceVersion);

			RootModuleVersionJobAbstractImpl.logger.info("The current traversal of the reference graph rooted at ModuleVersion " + moduleVersion + " is completed.");

			if (indVersionChanged) {
				RootModuleVersionJobAbstractImpl.logger.info("During this traversal the version of the root ModuleVersion " + moduleVersion + " was changed to " + byReferenceVersion.object + ". We update the root ModuleVersion.");

				// We must create a new ModuleVersion as it is immutable.
				this.listModuleVersionRoot.set(indexModuleVersionRoot, moduleVersion = new ModuleVersion(moduleVersion.getNodePath(), byReferenceVersion.object));
				indListRootModuleVersionChanged = true;
			}

			if (Util.isAbort()) {
				RootModuleVersionJobAbstractImpl.logger.info("During this traversal the user has indicated to abort the whole process.");
				break;
			}
		}

		RootModuleVersionJobAbstractImpl.logger.info("Iteration among all root ModuleVersions " + this.listModuleVersionRoot + " completed.");

		if (this.listActionsPerformed.size() != 0) {
			userInteractionCallbackPlugin.provideInfo("The following significant actions were performed:\n" + StringUtils.join(this.listActionsPerformed, '\n'));
		} else {
			userInteractionCallbackPlugin.provideInfo("No significant action was performed.");
		}

		userInteractionCallbackPlugin.provideInfo("Job execution completed.");

		return indListRootModuleVersionChanged;
	}

	/**
	 * This method is called for each root {@link ModuleVersion}. Subclasses can
	 * override it to provide a job-specific behavior at the root ModuleVersion
	 * level.
	 * <p>
	 * This implementation traverses the reference graph rooted at the root
	 * ModuleVersion by recursively invoking itself and for each ModuleVersion
	 * matching the {@link ReferencePathMatcher} provided to the class using
	 * {@link #setReferencePathMatcher}, it calls {@link #visitMatchedModuleVersion}.
	 * <p>
	 * This implementation returns false as it does not handle {@link Version}
	 * changes.
	 *
	 * @param referenceParent Root ModuleVersion passed as a Reference so that the
	 *   initial parent element of the ReferencePath can be created.
	 * @param byReferenceVersion If the method returns true, contains the new Version
	 *   of the root ModuleVersion.
	 * @return Indicates if the Version of the root ModuleVersion was changed and this
	 *   change deserves to be reflected in the List of root ModuleVersion's provided
	 *   by the caller.
	 */
	protected boolean visitModuleVersion(Reference referenceParent, ByReference<Version> byReferenceVersion) {
		Module module;
		UserInteractionCallbackPlugin userInteractionCallbackPlugin;
		WorkspacePlugin workspacePlugin;
		ScmPlugin scmPlugin;
		boolean indReferencePathAlreadyReverted;
		boolean indVisitChildren;

		this.referencePath.add(referenceParent);
		indReferencePathAlreadyReverted = false;

		userInteractionCallbackPlugin = ExecContextHolder.get().getExecContextPlugin(UserInteractionCallbackPlugin.class);
		workspacePlugin = ExecContextHolder.get().getExecContextPlugin(WorkspacePlugin.class);

		// We use a try-finally construct to ensure that the current ModuleVersion always
		// gets removed for the current ReferencePath.
		try {
			RootModuleVersionJobAbstractImpl.logger.info("Visiting leaf ModuleVersion of ReferencePath " + this.referencePath + '.');

			module = ExecContextHolder.get().getModel().getModule(referenceParent.getModuleVersion().getNodePath());

			scmPlugin = module.getNodePlugin(ScmPlugin.class, null);

			if ((referenceParent.getModuleVersion().getVersion().getVersionType() == VersionType.DYNAMIC) && !this.indHandleDynamicVersion) {
				userInteractionCallbackPlugin.provideInfo("ModuleVersion " + referenceParent.getModuleVersion() + " is dynamic and is not to be handled.");
				return false;
			}

			if ((referenceParent.getModuleVersion().getVersion().getVersionType() == VersionType.STATIC) && !this.indHandleStaticVersion) {
				userInteractionCallbackPlugin.provideInfo("ModuleVersion " + referenceParent.getModuleVersion() + " is static and is not to be handled.");
				return false;
			}

			indVisitChildren = true;

			if (this.referencePathMatcher.matches(this.referencePath)) {
				if (this.indAvoidReentry && !this.moduleReentryAvoider.processModule(referenceParent.getModuleVersion())) {
					userInteractionCallbackPlugin.provideInfo("The ReferencePath " + this.referencePath + " of the current ModuleVersion is matched by the ReferencePathMatcher. But that ModuleVersion was already processed and is skipped.");
					return false;
				} else {
					userInteractionCallbackPlugin.provideInfo("The ReferencePath " + this.referencePath + " of the current ModuleVersion is matched by the ReferencePathMatcher. Initiating the process for ModuleVersion " + referenceParent.getModuleVersion() + '.');
				}

				// We are about to delegate to visitMatchedModuleVersion for the rest of the
				// processing. This method starts working on the same current module and also
				// manages the graph path. We must therefore reset it now so that it can re-add
				// the current reference. And we must prevent the finally block to reset it.
				this.referencePath.removeLeafReference();;
				indReferencePathAlreadyReverted = true;

				// Util.isAbort() may be set, but it is not necessary to handle it since we are
				// done after this call.
				indVisitChildren = this.visitMatchedModuleVersion(referenceParent);

				if (Util.isAbort()) {
					return false;
				}
			}

			if (indVisitChildren && this.referencePathMatcher.canMatchChildren(this.referencePath)) {
				Path pathModuleWorkspace;

				ReferenceManagerPlugin referenceManagerPlugin = null;
				List<Reference> listReference;

				// Here we need to have access to the sources of the module so that we can obtain
				// the list of references and iterate over them. If the user already has the
				// correct version of the module checked out, we need to use it. If not, we need
				// an internal working directory which we will not modify (for now).
				// ScmPlugin.checkoutSystem does that.

				pathModuleWorkspace = scmPlugin.checkoutSystem(referenceParent.getModuleVersion().getVersion());

				try {
					if (!scmPlugin.isSync(pathModuleWorkspace, ScmPlugin.IsSyncFlagEnum.ALL_CHANGES)) {
						throw new RuntimeExceptionUserError("The directory " + pathModuleWorkspace + " is not synchronized with the SCM. Please synchronize all directories before using this job.");
					}

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
					if (referenceChild.getModuleVersion() == null) {
						RootModuleVersionJobAbstractImpl.logger.info("Reference " + referenceChild + " within ReferencePath " + this.referencePath + " does not include a source reference known to Dragom. It is not processed.");
						continue;
					}

					RootModuleVersionJobAbstractImpl.logger.info("Processing reference " + referenceChild + " within ReferencePath " + this.referencePath + '.');

					// Generally the byReferenceVersion parameter must not be null. But here we are
					// recursively invoking the same non-overridden method and we know this parameter
					// is actually not used.
					this.visitModuleVersion(referenceChild, null);

					if (Util.isAbort()) {
						return false;
					}
				}
			}
		} finally {
			if (!indReferencePathAlreadyReverted) {
				this.referencePath.removeLeafReference();
			}
		}

		return false;
	}

	/**
 	 * Called by {@link #visitModuleVersion} for each matched
 	 * {@link ModuleVersion}. Subclasses can override it to provide a job-specific
 	 * behavior at the matched ModuleVersion level.
 	 * <p>
 	 * If the ReferencePathMatcher is such that a ModuleVeresion is matched and this
 	 * method is called, it can signal the caller that children must not be visited by
 	 * returning false. But if the ModuleVersion is not matched, this method is not
 	 * called and children will be visited. In all cases, the ReferencePathMatcher
 	 * will be applied to children as well.
 	 * <p>
 	 * This implementation raises an exception. This method is not abstract since if
 	 * the subclass overrides visitModuleVersion, it may not be called or need to be overridden at all.
 	 *
	 * @param referenceParent Reference referring to the matched ModuleVersion.
	 * @return Indicates if children must be visited.
	 */
	protected boolean visitMatchedModuleVersion(Reference referenceParent) {
		throw new RuntimeException("Must not get here.");
	}
}
