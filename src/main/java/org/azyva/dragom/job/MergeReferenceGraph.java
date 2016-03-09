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
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.azyva.dragom.apiutil.ByReference;
import org.azyva.dragom.execcontext.ExecContext;
import org.azyva.dragom.execcontext.plugin.RuntimePropertiesPlugin;
import org.azyva.dragom.execcontext.plugin.UserInteractionCallbackPlugin;
import org.azyva.dragom.execcontext.plugin.WorkspaceDirUserModuleVersion;
import org.azyva.dragom.execcontext.plugin.WorkspacePlugin;
import org.azyva.dragom.execcontext.plugin.WorkspacePlugin.GetWorkspaceDirModeEnum;
import org.azyva.dragom.execcontext.plugin.WorkspacePlugin.WorkspaceDirAccessMode;
import org.azyva.dragom.execcontext.support.ExecContextHolder;
import org.azyva.dragom.model.Model;
import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.ModuleVersion;
import org.azyva.dragom.model.NodePath;
import org.azyva.dragom.model.Version;
import org.azyva.dragom.model.VersionType;
import org.azyva.dragom.model.plugin.ReferenceManagerPlugin;
import org.azyva.dragom.model.plugin.ScmPlugin;
import org.azyva.dragom.reference.Reference;
import org.azyva.dragom.reference.ReferencePath;
import org.azyva.dragom.reference.ReferencePathMatcher;
import org.azyva.dragom.util.AlwaysNeverAskUserResponse;
import org.azyva.dragom.util.RuntimeExceptionUserError;
import org.azyva.dragom.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * See the help information displayed by the {@link MergeReferenceGraphTool#help}
 * method.
 * <p>
 * The principle of operation of this class is to traverse reference graphs in the
 * standard way using a List of root {@link ModuleVersion}'s and a
 * {@link ReferencePathMatcher} to identify destination ModuleVersion's into which
 * a merge operation must be performed.
 * <p>
 * Generally the ReferencePathMatcher will be such that all ModuleVersion's in the
 * reference graph under a root ModuleVersion are to be merged but the generic use
 * of a ReferencePathMatcher allows to be selective about exactly which
 * ModuleVersion's and reference graph subsets are to be merged. Furthermore, once
 * a merge is performed into a destination ModuleVersion, traversal into this
 * ModuleVersion is not performed, regardless of the ModuleVersion's matched by
 * the ReferencePathMatcher.
 * <p>
 * There are actually two types of merge operations supported by Dragom. This
 * class implements the most common one. The other one is implemented by
 * {@link MergeBase}.
 * <p>
 * The type of merge performed by this class is used, for example, when:
 * <p>
 * <li>Retrofitting production changes present in the main dynamic {@link Version}
 *     or the release static Version of a {@link Module}, into a dynamic Version
 *     used for an ongoing development effort;</li>
 * <li>Integrating changes done on a project dynamic Version of a Module, that
 *     ModuleVersion being the root of a complete reference graph, into a release
 *     dynamic Version with it own reference graph.</li>
 * <p>
 * For each main ModuleVersion that is matched in the main traversal, a merge
 * process is initiated. This merge process is driven by traversing the reference
 * graph rooted at the matched destination ModuleVersion, considering only
 * Module's known to the {@link Model}.
 * <p>
 * For each destination ModuleVersion visited during the traversal, a
 * corresponding source ModuleVersion is selected. This source ModuleVersion is
 * the one in the parallel reference graph rooted at the same Module corresponding
 * to the initial matched destination ModuleVersion, but a dynamic Version
 * initially specified externally (by the user). It is as if for each
 * ReferencePath in the destination reference graph, the corresponding
 * ModuleVersion is accessed in the source reference graph not considering the
 * Version's within the source ReferencePath.
 * <p>
 * The destination Version will always be dynamic since the initial source Version
 * specified by the user must be dynamic and the merge process is such that the
 * other Version's encountered during the traversal are switched to dynamic if
 * required before recursively invoking the merge process.
 * <p>
 * The destination ModuleVersion must be in a user workspace directory so that if
 * merge conflicts are encountered, the user has a workspace where they can be
 * resolved. If a destination ModuleVersion is not in a user workspace directory,
 * it is checked out for the user.
 * <p>
 * In all cases below where merge or diverging commits are mentioned, commits that
 * simply change the ArtifactVersion of the Module or the Version of its
 * references are not considered. These commits are recognized with the commit
 * attributes "dragom-version-change" and "dragom-reference-version-change".
 * <p>
 * We start by performing a merge using {@link ScmPlugin#merge}. We then iterate
 * through the child references in the destination ModuleVersion. For each child
 * reference that is a Module known to the Model and exists in the source
 * ModuleVersion without considering the Version, we perform the following
 * algorithm. Note that a corresponding reference in the source ModuleVersion
 * may not exist and there is nothing we can do. The presence or absence of a
 * corresponding reference is handled by the merge process itself.
 * <p>
 * <h2>Source and destination are static</h2>
 * <p>
 * If the source and destination reference Versions are static and the same, no
 * merge is required. If they are not the same we establish whether source and/or
 * destination references have diverging commits. This is done recursively on each
 * ModuleVersion that exist in both the source and destination reference graphs,
 * without regard to the actual Version's. If any ModuleVersion in the source has
 * diverging commits then the whole source reference graph is considered as having
 * diverging commits. Similarly for ModuleVersion's in the destination.
 * <p>
 * If source and destination reference graphs do not have diverging commits, no
 * merge is required. This case is not likely since we are generally talking about
 * different Version's and given different Version's, we expect to have different
 * source code.
 * <p>
 * If only the source reference graph diverges, the Version of the reference in
 * the destination reference graph is changed to that of the source. This is
 * possible since the parent is necessarily a dynamic Version.
 * <p>
 * If only the destination reference graph diverges, no merge is required since
 * the destination contains all changes of the source.
 * <p>
 * If both the source and destination reference graphs diverge we have a merge
 * conflict at the reference graph level. We inform the user and abort the merge
 * process.
 * <p>
 * <h2>Source is dynamic and destination static</h2>
 * <p>
 * If the source reference Version is dynamic and destination static, establish
 * whether source and/or destination reference have diverging commits in the same
 * way as above.
 * <p>
 * If source and destination reference graphs do not have diverging commits, no
 * merge is required. This case is not likely, but possible if the source dynamic
 * Version has just been created.
 * <p>
 * If the source reference graph diverges, use {@link SwitchToDynamicVersion} to
 * switch the static Version of the reference in the destination to a dynamic
 * Version, potentially creating a new dynamic Version, and continue with the case
 * below (both source and destination reference Version's dynamic). If the
 * destination reference graph also diverges issue a warning that the user should
 * expect the switched-to dynamic Version to also include these diverging commits.
 * <p>
 * If only the destination reference graph diverges, no merge is required.
 * <p>
 * <h2>Destination is dynamic (regardless of source)</h2>
 * <p>
 * Recurse using the two ModuleVersion corresponding to the source and destination
 * child references.
 *
 * @author David Raymond
 */
public class MergeReferenceGraph extends RootModuleVersionJobAbstractImpl {
	/**
	 * Logger for the class.
	 */
	private static final Logger logger = LoggerFactory.getLogger(MergeReferenceGraph.class);

	/**
	 * Runtime property of type {@link AlwaysNeverAskUserResponse} that indicates if a
	 * previously established source {@link Version} can be reused.
	 */
	private static final String RUNTIME_PROPERTY_CAN_REUSE_SRC_VERSION = "CAN_REUSE_SRC_VERSION";

	/**
	 * Runtime property that specifies the source {@link Version} to reuse.
	 */
	private static final String RUNTIME_PROPERTY_REUSE_SRC_VERSION = "REUSE_SRC_VERSION";

	/**
	 * Specifies the behavior when merge conflicts are encountered.
	 * <p>
	 * <li>ALWAYS: Always continue to the next matching destination module
	 *     version.</li>
	 * <li>NEVER: Abort the whole process when merge conflicts occur.</li>
	 * <li>ASK: Ask the user</li>
	 * <p>
	 * The default value is AlwaysNeverAskUserResponse.ASK.
	 */
	private AlwaysNeverAskUserResponse alwaysNeverAskUserResponseContinueOnMergeConflicts = AlwaysNeverAskUserResponse.ASK;

	/**
	 * Constructor.
	 *
	 * @param listModuleVersionRoot List of root ModuleVersion's within which new
	 *   static Version's must be created.
	 */
	public MergeReferenceGraph(List<ModuleVersion> listModuleVersionRoot) {
		super(listModuleVersionRoot);

		this.setIndHandleStaticVersion(false);
	}

	/**
	 * Indicates to continue to the next matching destination module version when
	 * merge conflicts are encountered (if indContinueOnMergeConflicts is true) or
	 * abort the whole process (if indContinueOnMergeConflicts is false).
	 * <p>
	 * If this method is not called, the default is to ask the user.
	 *
	 * @param indContinueOnMergeConflicts See description.
	 */
	public void setContinueOnMergeConflicts(boolean indContinueOnMergeConflicts) {
		this.alwaysNeverAskUserResponseContinueOnMergeConflicts = indContinueOnMergeConflicts ? AlwaysNeverAskUserResponse.ALWAYS : AlwaysNeverAskUserResponse.NEVER;
	}

	/**
	 * Visits a {@link ModuleVersion} in the context of traversing the
	 * ReferencePath for performing a merge. The {@link Version} of the module must be
	 * dynamic.
	 * <p>
	 * This method has similarities with
	 * {@link RootModuleVersionJobAbstractImpl#visitModuleVersion} in that they both
	 * traverse a reference graph in some way. But they serve very different purposes.
	 * <p>
	 * visitModuleVersion is used to traverse the reference graph to find
	 * ModuleVersion's for which a merge has to be performed. This method takes over
	 * during that traversal when we know a merge has to be performed for the
	 * ModuleVersion.
	 * <p>
	 * This method does not perform the actual merge since for each matched
	 * ModuleVersion it needs to perform setup operations once, after which it
	 * delegates to {@link #mergeModuleVersion}.
	 *
	 * @param referenceParent Reference to the matched ModuleVersion for which a merge
	 *   has to be performed.
	 * @return Indicates if children must be visited. false is always returned since
	 *   when a merge is performed on some ModuleVersion, the children will
	 *   necessarily have been recursively merged as well.
	 */
	@Override
	protected boolean visitMatchedModuleVersion(Reference referenceParent) {
		boolean indReferencePathAlreadyReverted;
		ExecContext execContext;
		RuntimePropertiesPlugin runtimePropertiesPlugin;
		UserInteractionCallbackPlugin userInteractionCallbackPlugin;
		WorkspacePlugin workspacePlugin;
		Model model;
		Module module;
		ScmPlugin scmPlugin;
		String runtimeProperty;
		AlwaysNeverAskUserResponse alwaysNeverAskUserResponseCanReuseSrcVersion;
		ModuleVersion moduleVersionRootDest;
		Version versionReuseSrc;
		Version versionSrc;
		ModuleVersion moduleVersionSrc;
		Path pathModuleWorkspace;
		ReferencePath referencePathSrc;

		this.referencePath.add(referenceParent);
		indReferencePathAlreadyReverted = false;

		execContext = ExecContextHolder.get();
		runtimePropertiesPlugin = execContext.getExecContextPlugin(RuntimePropertiesPlugin.class);
		userInteractionCallbackPlugin = execContext.getExecContextPlugin(UserInteractionCallbackPlugin.class);
		workspacePlugin = execContext.getExecContextPlugin(WorkspacePlugin.class);
		model = execContext.getModel();

		try {
			//********************************************************************************
			// Determine the source Version corresponding to the root destination
			// ModuleVersion to merge into.
			//********************************************************************************

			// We always start with the root ModuleVersion of the reference path and this is
			// the ModuleVersion for which we require the user to provide a source Version,
			// even if the first merge operation will not be performed on that ModuleVersion,
			// but rather on a child reference because of the ReferencePathMatcher.
			moduleVersionRootDest = this.referencePath.get(0).getModuleVersion();
			module = model.getModule(moduleVersionRootDest.getNodePath());
			scmPlugin = module.getNodePlugin(ScmPlugin.class, null);

			alwaysNeverAskUserResponseCanReuseSrcVersion = AlwaysNeverAskUserResponse.valueOfWithAskDefault(runtimePropertiesPlugin.getProperty(module, MergeReferenceGraph.RUNTIME_PROPERTY_CAN_REUSE_SRC_VERSION));

			runtimeProperty = runtimePropertiesPlugin.getProperty(module, MergeReferenceGraph.RUNTIME_PROPERTY_REUSE_SRC_VERSION);

			if (runtimeProperty != null) {
				versionReuseSrc = new Version(runtimeProperty);
			} else {
				versionReuseSrc = null;

				if (alwaysNeverAskUserResponseCanReuseSrcVersion.isAlways()) {
					// Normally if the runtime property CAN_REUSE_SRC_VERSION is ALWAYS the
					// REUSE_SRC_VERSION runtime property should also be set. But since these
					// properties are independent and stored externally, it can happen that they
					// are not synchronized. We make an adjustment here to avoid problems.
					alwaysNeverAskUserResponseCanReuseSrcVersion = AlwaysNeverAskUserResponse.ASK;
				}
			}

			if (alwaysNeverAskUserResponseCanReuseSrcVersion.isAlways()) {
				userInteractionCallbackPlugin.provideInfo("Source version " + versionReuseSrc + " is automatically reused for module " + module + " whose destination version is " + moduleVersionRootDest.getVersion() + '.');
				versionSrc = versionReuseSrc;
			} else {
				versionSrc =
						Util.getInfoVersion(
								null,
								scmPlugin,
								userInteractionCallbackPlugin,
								"Which source version do you want to merge module " + module + " from*",
								versionReuseSrc);

				runtimePropertiesPlugin.setProperty(null, MergeReferenceGraph.RUNTIME_PROPERTY_REUSE_SRC_VERSION, versionSrc.toString());

				alwaysNeverAskUserResponseCanReuseSrcVersion =
						Util.getInfoAlwaysNeverAskUserResponseAndHandleAsk(
								runtimePropertiesPlugin,
								MergeReferenceGraph.RUNTIME_PROPERTY_CAN_REUSE_SRC_VERSION,
								userInteractionCallbackPlugin,
								"Do you want to automatically reuse source version " + versionSrc + " for all subsequent modules for which a source version needs to be specified*",
								AlwaysNeverAskUserResponse.ALWAYS);
			}

			moduleVersionSrc = new ModuleVersion(moduleVersionRootDest.getNodePath(), versionSrc);

			//********************************************************************************
			// From the source Version determined above, follow the path to the ModuleVersion
			// by considering the current ReferencePath and without considering the Version's
			// in the source, constructing along the way the source  ReferencePath.
			//********************************************************************************

			userInteractionCallbackPlugin.provideInfo("Locating source module version within reference graph rooted at module version " + moduleVersionSrc + " corresponding to destination reference path " + this.referencePath + '.');

			referencePathSrc = new ReferencePath();
			referencePathSrc.add(new Reference(moduleVersionSrc));

			// We start at 1 since we have already handled the root ModuleVersion above as a
			// special case since we obtained the Version externally.
			for (int i = 1; i < this.referencePath.size(); i++) {
				Reference referenceDest;
				List<Reference> listReferenceSrc;
				ReferenceManagerPlugin referenceManagerPlugin;
				Reference referenceChildSrc;

				referenceDest = this.referencePath.get(i);

				module = model.getModule(moduleVersionSrc.getNodePath());
				scmPlugin = module.getNodePlugin(ScmPlugin.class, null);
				pathModuleWorkspace = scmPlugin.checkoutSystem(moduleVersionSrc.getVersion());

				try {
					if (!module.isNodePluginExists(ReferenceManagerPlugin.class, null)) {
						listReferenceSrc = Collections.emptyList();
					} else {
						referenceManagerPlugin = module.getNodePlugin(ReferenceManagerPlugin.class, null);
						listReferenceSrc = referenceManagerPlugin.getListReference(pathModuleWorkspace);
					}
				} finally {
					workspacePlugin.releaseWorkspaceDir(pathModuleWorkspace);
				}

				referenceChildSrc = null;

				for (Reference referenceSrc: listReferenceSrc) {
					if (referenceSrc.equalsNoVersion(referenceDest)) {
						referenceChildSrc = referenceSrc;
						break;
					}
				}

				if (referenceChildSrc == null) {
					userInteractionCallbackPlugin.provideInfo("A source module version corresponding to destination reference " + referenceDest + " could not be found within source reference path " + referencePathSrc + '.');
					return false;
				}

				referencePathSrc.add(referenceChildSrc);
			}

			//********************************************************************************
			// We have the source and destination ModuleVersion's. The source  ReferencePath
			// in referencePathSrc is complete, and this.referencePath represents
			// the destination ReferencePath. We are ready to invoke the actual merge process.
			//********************************************************************************

			// We are about to delegate to mergeModuleVersion for the rest of the processing.
			// This method starts working on the same current module and also manages the
			// graph path. We must therefore reset it now so that it can re-add
			// the current reference. And we must prevent the finally block to reset it.
			this.referencePath.removeLeafReference();
			indReferencePathAlreadyReverted = true;

			this.mergeModuleVersion(referencePathSrc, referenceParent);
		} finally {
			if (!indReferencePathAlreadyReverted) {
				this.referencePath.removeLeafReference();
			}
		}

		return false;
	}

	/**
	 * Performs the actual recursive merge.
	 * <p>
	 * For the destination, a {@link Reference} is passed. For symmetry purposes it
	 * would have been logical to have a {@link ReferencePath} here, but most tasks
	 * that derive from {@link RootModuleVersionJobAbstractImpl} maintain the current
	 * ReferencePath in this.referencePath and only pass the child Reference that
	 * must be visited to methods. In the context of this job which also derive from
	 * RootModuleVersionJobAbstractImpl this.referencePath is used as the destination.
	 * <p>
	 * The last Reference in referencePathSrc is expected to be for the same
	 * {@link Module} as the one in referenceDest. But the {@link Version} should be
	 * different.
	 *
	 * @param referencePathSrc Source ReferencePath.
	 * @param referenceDest Destination Reference.
	 * @return Indicates to abort the recursive merge.
	 */
	private boolean mergeModuleVersion(ReferencePath referencePathSrc, Reference referenceDest) {
		ExecContext execContext;
		UserInteractionCallbackPlugin userInteractionCallbackPlugin;
		WorkspacePlugin workspacePlugin;
		Model model;
		ModuleVersion moduleVersionDest;
		ModuleVersion moduleVersionSrc;
		WorkspaceDirUserModuleVersion workspaceDirUserModuleVersion;
		Path pathModuleWorkspace;
		Module module;
		ScmPlugin scmPlugin;
		List<ScmPlugin.Commit> listCommit;
		Iterator<ScmPlugin.Commit> iterCommit;
		Path pathModuleWorkspaceSrc;

		this.referencePath.add(referenceDest);

		execContext = ExecContextHolder.get();
		userInteractionCallbackPlugin = execContext.getExecContextPlugin(UserInteractionCallbackPlugin.class);
		workspacePlugin = execContext.getExecContextPlugin(WorkspacePlugin.class);
		model = execContext.getModel();

		pathModuleWorkspace = null;
		pathModuleWorkspaceSrc = null;

		try {
			moduleVersionDest = referenceDest.getModuleVersion();
			moduleVersionSrc = referencePathSrc.getLeafModuleVersion();

			module = model.getModule(moduleVersionDest.getNodePath());
			scmPlugin = module.getNodePlugin(ScmPlugin.class, null);

			userInteractionCallbackPlugin.provideInfo("Merging leaf module version of source reference path " + referencePathSrc + " into " + this.referencePath + '.');

			//********************************************************************************
			// Ensure destination ModuleVersion is in a user workspace directory.
			//********************************************************************************

			workspaceDirUserModuleVersion = new WorkspaceDirUserModuleVersion(moduleVersionDest);

			if (!workspacePlugin.isWorkspaceDirExist(workspaceDirUserModuleVersion)) {
				pathModuleWorkspace = workspacePlugin.getWorkspaceDir(workspaceDirUserModuleVersion, GetWorkspaceDirModeEnum.CREATE_NEW_NO_PATH, WorkspaceDirAccessMode.READ_WRITE);

				if (pathModuleWorkspace == null) {
					throw new RuntimeExceptionUserError("A workspace directory for " + workspaceDirUserModuleVersion + " could not be obtained, probably because of a conflict.");
				}

				try {
					userInteractionCallbackPlugin.provideInfo("Checking out module version " + moduleVersionDest + " into " + pathModuleWorkspace + '.');

					scmPlugin.checkout(moduleVersionDest.getVersion(), pathModuleWorkspace);
				} catch (RuntimeException re) {
					workspacePlugin.deleteWorkspaceDir(workspaceDirUserModuleVersion);
					pathModuleWorkspace = null; // To prevent the call to workspacePlugin.releaseWorkspaceDir below.
					throw re;
				} finally {
					if (pathModuleWorkspace != null) {
						workspacePlugin.releaseWorkspaceDir(pathModuleWorkspace);
					}
				}
			} else {
				pathModuleWorkspace = workspacePlugin.getWorkspaceDir(workspaceDirUserModuleVersion, GetWorkspaceDirModeEnum.GET_EXISTING, WorkspaceDirAccessMode.READ_WRITE);
			}

			//********************************************************************************
			// Shallow-merge source ModuleVersion into destination.
			//********************************************************************************

			MergeReferenceGraph.logger.info("Building list of version-changing commits to exclude before merging source module version " + moduleVersionSrc + " into destination module version " + moduleVersionDest + '.');

			listCommit = scmPlugin.getListCommitDiverge(moduleVersionSrc.getVersion(), moduleVersionDest.getVersion(), null, EnumSet.of(ScmPlugin.GetListCommitFlagEnum.IND_INCLUDE_MAP_ATTR));
			iterCommit = listCommit.iterator();

			while (iterCommit.hasNext()) {
				ScmPlugin.Commit commit;

				commit = iterCommit.next();

				if (!commit.mapAttr.containsKey(ScmPlugin.COMMIT_ATTR_VERSION_CHANGE) && !commit.mapAttr.containsKey(ScmPlugin.COMMIT_ATTR_REFERENCE_VERSION_CHANGE)) {
					iterCommit.remove();
				}
			}

			if (listCommit.isEmpty()) {
				userInteractionCallbackPlugin.provideInfo("About to shallow-merge source module version " + moduleVersionSrc + " into destination module version " + moduleVersionDest + '.');
			} else {
				userInteractionCallbackPlugin.provideInfo("About to shallow-merge source module version " + moduleVersionSrc + " into destination module version " + moduleVersionDest + " excluding version-changing commits " + listCommit + '.');
			}

			if (!Util.handleDoYouWantToContinue(Util.DO_YOU_WANT_TO_CONTINUE_CONTEXT_MERGE)) {
				return true;
			}

			// ScmPlugin.merge ensures that the working directory is synchronized.
			if (!scmPlugin.merge(pathModuleWorkspace, moduleVersionSrc.getVersion(), listCommit, null)) {
				userInteractionCallbackPlugin.provideInfo("WARNING: Merge conflicts occurred in " + pathModuleWorkspace + " while merging source module version " + moduleVersionSrc + " into destination module version " + moduleVersionDest + ". Merge process for current matched destination module version aborted.");

				switch (this.alwaysNeverAskUserResponseContinueOnMergeConflicts) {
				case ALWAYS:
					userInteractionCallbackPlugin.provideInfo("Automaticallying continuing to the next matching destination module version, if any.");
					break;
				case NEVER:
					Util.setAbort();
					break;
				case ASK:
					this.alwaysNeverAskUserResponseContinueOnMergeConflicts = Util.getInfoAlwaysNeverAskUserResponse(userInteractionCallbackPlugin, "Do you want to continue with the next matching destination module version, if any*", AlwaysNeverAskUserResponse.ASK);

					if (this.alwaysNeverAskUserResponseContinueOnMergeConflicts == AlwaysNeverAskUserResponse.NEVER) {
						Util.setAbort();
					}
				}

				return true;
			}

			//********************************************************************************
			// Handle matching child references and recurse.
			//********************************************************************************

			if (module.isNodePluginExists(ReferenceManagerPlugin.class, null)) {
				ReferenceManagerPlugin referenceManagerPlugin;
				List<Reference> listReferenceDest;
				List<Reference> listReferenceSrc;
				Reference referenceChildSrc;

				referenceManagerPlugin = module.getNodePlugin(ReferenceManagerPlugin.class, null);
				listReferenceDest = referenceManagerPlugin.getListReference(pathModuleWorkspace);
				pathModuleWorkspaceSrc = scmPlugin.checkoutSystem(moduleVersionSrc.getVersion());
				listReferenceSrc = referenceManagerPlugin.getListReference(pathModuleWorkspaceSrc);

				for (Reference referenceChildDest: listReferenceDest) {
					referenceChildSrc = null;
					ByReference<Boolean> byReferenceBooleanSrcDiverges;
					ByReference<Boolean> byReferenceBooleanDestDiverges;

					if (referenceChildDest.getModuleVersion() == null) {
						MergeReferenceGraph.logger.info("Reference " + referenceChildDest + " within reference path " + this.referencePath + " does not include a source reference known to Dragom. It is not processed.");
						continue;
					}

					for (Reference referenceSrc: listReferenceSrc) {
						if (referenceSrc.equalsNoVersion(referenceDest)) {
							referenceChildSrc = referenceSrc;
							break;
						}
					}

					if (referenceChildSrc == null) {
						MergeReferenceGraph.logger.info("A reference in the source module version " + moduleVersionSrc + " in " + pathModuleWorkspaceSrc + " corresponding to reference " + referenceChildDest + " in the destination module version " + moduleVersionDest + " in " + pathModuleWorkspace + " could not be found. Destination reference skipped.");
						continue;
					}

					// We take care of the source and destination Version's being the same outside of
					// the various cases below.
					if (referenceChildSrc.getModuleVersion().getVersion().equals(referenceChildDest.getModuleVersion().getVersion())) {
						MergeReferenceGraph.logger.info("The reference source module version " + referenceChildSrc.getModuleVersion() + " is the same as that in the destination. Not recursing.");
						continue;
					}

					if (   (referenceChildSrc.getModuleVersion().getVersion().getVersionType() == VersionType.STATIC)
					    && (referenceChildDest.getModuleVersion().getVersion().getVersionType() == VersionType.STATIC)) {

						//********************************************************************************
						// Source and destination Version's are static (and different).
						//********************************************************************************

						byReferenceBooleanSrcDiverges = new ByReference<Boolean>();
						byReferenceBooleanDestDiverges = new ByReference<Boolean>();

						this.verifyDivergences(referenceChildDest.getModuleVersion().getNodePath(), referenceChildSrc.getModuleVersion().getVersion(), referenceChildDest.getModuleVersion().getVersion(), byReferenceBooleanSrcDiverges, byReferenceBooleanDestDiverges);

						if (byReferenceBooleanSrcDiverges.object.booleanValue()) {
							if (byReferenceBooleanDestDiverges.object.booleanValue()) {
								userInteractionCallbackPlugin.provideInfo("WARNING: Both the reference static source module version " + referenceChildSrc.getModuleVersion() + " from reference path " + referencePathSrc + " and destination static module version " + referenceChildDest.getModuleVersion() + " from reference path " + this.referencePath + " diverge. This is a conflict at the reference graph level. Merge process for current matched destination module version aborted.");

								switch (this.alwaysNeverAskUserResponseContinueOnMergeConflicts) {
								case ALWAYS:
									userInteractionCallbackPlugin.provideInfo("Automaticallying continuing to the next matching destination module version, if any.");
									break;
								case NEVER:
									Util.setAbort();
									break;
								case ASK:
									this.alwaysNeverAskUserResponseContinueOnMergeConflicts = Util.getInfoAlwaysNeverAskUserResponse(userInteractionCallbackPlugin, "Do you want to continue with the next matching destination module version, if any*", AlwaysNeverAskUserResponse.ASK);

									if (this.alwaysNeverAskUserResponseContinueOnMergeConflicts == AlwaysNeverAskUserResponse.NEVER) {
										Util.setAbort();
									}
								}

								return true;
							} else {
								String message;
								Map<String, String> mapCommitAttr;

								userInteractionCallbackPlugin.provideInfo("Reference source static module version " + referenceChildSrc.getModuleVersion() + " from reference path " + referencePathSrc + " diverges compared to destination static module version " + referenceChildDest.getModuleVersion() + " from reference path " + this.referencePath + ". Destination version will be updated to source version.");

								if (!Util.handleDoYouWantToContinue(Util.DO_YOU_WANT_TO_CONTINUE_CONTEXT_UPDATE_REFERENCE)) {
									return true;
								}

								referenceManagerPlugin.updateReferenceVersion(pathModuleWorkspace, referenceChildDest, referenceChildSrc.getModuleVersion().getVersion());

								message = "Reference " + referenceChildDest + " within reference path " + this.referencePath + " was changed to version " + referenceChildSrc.getModuleVersion().getVersion() + '.';
								mapCommitAttr = new HashMap<String, String>();
								mapCommitAttr.put(ScmPlugin.COMMIT_ATTR_REFERENCE_VERSION_CHANGE, "true");
								scmPlugin.commit(pathModuleWorkspace, message, mapCommitAttr);
								userInteractionCallbackPlugin.provideInfo(message);

								message = "The previous change was performed in " + pathModuleWorkspace + " which belongs to the user (you) and was committed to the SCM.";
								userInteractionCallbackPlugin.provideInfo(message);
							}
						}
					} else if (   (referenceChildSrc.getModuleVersion().getVersion().getVersionType() == VersionType.DYNAMIC)
					           && (referenceChildDest.getModuleVersion().getVersion().getVersionType() == VersionType.STATIC)) {

						String message;
						Map<String, String> mapCommitAttr;

						//********************************************************************************
						// Source Version is dynamic and destination is static.
						//********************************************************************************

						byReferenceBooleanSrcDiverges = new ByReference<Boolean>();
						byReferenceBooleanDestDiverges = new ByReference<Boolean>();

						this.verifyDivergences(referenceChildDest.getModuleVersion().getNodePath(), referenceChildSrc.getModuleVersion().getVersion(), referenceChildDest.getModuleVersion().getVersion(), byReferenceBooleanSrcDiverges, byReferenceBooleanDestDiverges);

						if (byReferenceBooleanSrcDiverges.object.booleanValue()) {
							List<ModuleVersion> listModuleVersion;
							SwitchToDynamicVersion switchToDynamicVersion;
							Version versionDynamicNew;
							ByReference<Boolean> byReferenceBooleanOldDestDiverges;

							userInteractionCallbackPlugin.provideInfo("Reference source dynamic module version " + referenceChildSrc.getModuleVersion() + " from reference path " + referencePathSrc + " diverge compared to destination static module version " + referenceChildDest.getModuleVersion() + " from reference path " + this.referencePath + ". Destination will be switched to a dynamic version so that changes from the source can be merged.");

							if (byReferenceBooleanDestDiverges.object.booleanValue()) {
								userInteractionCallbackPlugin.provideInfo("WARNING: Destination static version also diverges compared to source. Selected dynamic version to switch to should include these changes.");
							}

							listModuleVersion = new ArrayList<ModuleVersion>();
							listModuleVersion.add(referenceChildDest.getModuleVersion());
							switchToDynamicVersion = new SwitchToDynamicVersion(listModuleVersion);

							if (!switchToDynamicVersion.performTask()) {
								userInteractionCallbackPlugin.provideInfo("Destination static version " + referenceChildDest.getModuleVersion() + " within reference path " + this.referencePath + " was not switched. Merge process for current matched destination module version aborted.");

								switch (this.alwaysNeverAskUserResponseContinueOnMergeConflicts) {
								case ALWAYS:
									userInteractionCallbackPlugin.provideInfo("Automaticallying continuing to the next matching destination module version, if any.");
									break;
								case NEVER:
									Util.setAbort();
									break;
								case ASK:
									this.alwaysNeverAskUserResponseContinueOnMergeConflicts = Util.getInfoAlwaysNeverAskUserResponse(userInteractionCallbackPlugin, "Do you want to continue with the next matching destination module version, if any*", AlwaysNeverAskUserResponse.ASK);

									if (this.alwaysNeverAskUserResponseContinueOnMergeConflicts == AlwaysNeverAskUserResponse.NEVER) {
										Util.setAbort();
									}
								}

								return true;
							}

							versionDynamicNew = listModuleVersion.get(0).getVersion();

							byReferenceBooleanOldDestDiverges = new ByReference<Boolean>();
							this.verifyDivergences(referenceChildDest.getModuleVersion().getNodePath(), referenceChildDest.getModuleVersion().getVersion(), versionDynamicNew, byReferenceBooleanOldDestDiverges, null);

							if (byReferenceBooleanOldDestDiverges.object.booleanValue()) {
								userInteractionCallbackPlugin.provideInfo("WARNING: Destination static module version " + referenceChildDest.getModuleVersion()+ " was diverging compared to source. New selected dynamic version " + versionDynamicNew + " does not include these changes which may be lost.");
							}

							userInteractionCallbackPlugin.provideInfo("Reference destination static module version " + referenceChildDest.getModuleVersion() + " from reference path " + this.referencePath + " was switched to version " + versionDynamicNew + " and will be updated.");

							if (!Util.handleDoYouWantToContinue(Util.DO_YOU_WANT_TO_CONTINUE_CONTEXT_UPDATE_REFERENCE)) {
								return true;
							}

							referenceManagerPlugin.updateReferenceVersion(pathModuleWorkspace, referenceChildDest, versionDynamicNew);

							message = "Reference " + referenceChildDest + " within reference path " + this.referencePath + " was changed to version " + versionDynamicNew + '.';
							mapCommitAttr = new HashMap<String, String>();
							mapCommitAttr.put(ScmPlugin.COMMIT_ATTR_REFERENCE_VERSION_CHANGE, "true");
							scmPlugin.commit(pathModuleWorkspace, message, mapCommitAttr);
							userInteractionCallbackPlugin.provideInfo(message);

							message = "The previous change was performed in " + pathModuleWorkspace + " which belongs to the user (you) and was committed to the SCM.";
							userInteractionCallbackPlugin.provideInfo(message);

							// We change the destination reference to reflect the new switched-to dynamic
							// Version to fall through to the recursive call.
							referenceChildDest = new Reference(new ModuleVersion(referenceChildDest.getModuleVersion().getNodePath(), versionDynamicNew));
						}
					}

					if (referenceChildDest.getModuleVersion().getVersion().getVersionType() == VersionType.DYNAMIC) {
						//********************************************************************************
						// Destination Version is dynamic (regardless of source)
						// This may be the case following a switch of the previously static Version above.
						//********************************************************************************

						referencePathSrc.add(referenceChildSrc);

						userInteractionCallbackPlugin.provideInfo("Reference destination module version " + referenceChildDest.getModuleVersion() + " from reference path " + this.referencePath + " is dynamic. Recursively merging source module version " + referenceChildSrc.getModuleVersion() + " from reference path " + referencePathSrc + '.');

						try {
							if (this.mergeModuleVersion(referencePathSrc, referenceChildDest)) {
								return true;
							}
						} finally {
							referencePathSrc.removeLeafReference();
						}
					}
				}
			}
		} finally {
			if (pathModuleWorkspace != null) {
				workspacePlugin.releaseWorkspaceDir(pathModuleWorkspace);
			}

			if (pathModuleWorkspaceSrc != null) {
				workspacePlugin.releaseWorkspaceDir(pathModuleWorkspaceSrc);
			}

			this.referencePath.removeLeafReference();
		}

		return false;
	}

	/**
	 * Verifies the divergences between a source and destination
	 * {@link Version} of a {@link Module}.
	 *
	 * @param nodePathModule {@link NodePath} of the Module.
	 * @param versionSrc Source Version.
	 * @param versionDest Destination Version.
	 * @param byReferenceBooleanSrcDiverges Upon return indicates if source has
	 *   diverging (non-version-changing) commits. Can be null to disable
	 *   verification.
	 * @param byReferenceBooleanDestDiverges Upon return indicates if destination has
	 *   diverging (non-version-changing) commits. Can be null to disable
	 *   verification.
	 */
	private void verifyDivergences(NodePath nodePathModule, Version versionSrc, Version versionDest, ByReference<Boolean> byReferenceBooleanSrcDiverges, ByReference<Boolean> byReferenceBooleanDestDiverges) {
		ExecContext execContext;
		UserInteractionCallbackPlugin userInteractionCallbackPlugin;
		WorkspacePlugin workspacePlugin;
		Model model;
		Module module;
		ScmPlugin scmPlugin;
		ModuleVersion moduleVersionDest;
		ModuleVersion moduleVersionSrc;
		Path pathModuleWorkspaceDest;
		Path pathModuleWorkspaceSrc;
		List<ScmPlugin.Commit> listCommit;
		Iterator<ScmPlugin.Commit> iterCommit;

		execContext = ExecContextHolder.get();
		userInteractionCallbackPlugin = execContext.getExecContextPlugin(UserInteractionCallbackPlugin.class);
		workspacePlugin = execContext.getExecContextPlugin(WorkspacePlugin.class);
		model = execContext.getModel();

		pathModuleWorkspaceDest = null;
		pathModuleWorkspaceSrc = null;

		try {
			moduleVersionDest = new ModuleVersion(nodePathModule, versionDest);
			moduleVersionSrc = new ModuleVersion(nodePathModule, versionSrc);

			module = model.getModule(nodePathModule);
			scmPlugin = module.getNodePlugin(ScmPlugin.class, null);

			if (byReferenceBooleanSrcDiverges != null) {
				userInteractionCallbackPlugin.provideInfo("Verifying divergences of source module version " + moduleVersionSrc + " compared to destination module version " + moduleVersionDest + '.');

				listCommit = scmPlugin.getListCommitDiverge(versionSrc, versionDest, null, EnumSet.of(ScmPlugin.GetListCommitFlagEnum.IND_INCLUDE_MAP_ATTR, ScmPlugin.GetListCommitFlagEnum.IND_INCLUDE_MESSAGE));
				iterCommit = listCommit.iterator();

				while (iterCommit.hasNext()) {
					ScmPlugin.Commit commit;

					commit = iterCommit.next();

					if (!commit.mapAttr.containsKey(ScmPlugin.COMMIT_ATTR_VERSION_CHANGE) && !commit.mapAttr.containsKey(ScmPlugin.COMMIT_ATTR_REFERENCE_VERSION_CHANGE)) {
						iterCommit.remove();
					}
				}

				if (listCommit.isEmpty()) {
					userInteractionCallbackPlugin.provideInfo("No divergence found in source module version " + moduleVersionSrc + " compared to destination module version " + moduleVersionDest + '.');
				} else {
					userInteractionCallbackPlugin.provideInfo("Divergences found in source module version " + moduleVersionSrc + " compared to destination module version " + moduleVersionDest + '.');
				}

				byReferenceBooleanSrcDiverges.object = Boolean.valueOf(!listCommit.isEmpty());
			}

			if (byReferenceBooleanDestDiverges != null) {
				userInteractionCallbackPlugin.provideInfo("Verifying divergences of destination module version " + moduleVersionDest + " compared to source module version " + moduleVersionSrc + '.');

				listCommit = scmPlugin.getListCommitDiverge(versionDest, versionSrc, null, EnumSet.of(ScmPlugin.GetListCommitFlagEnum.IND_INCLUDE_MAP_ATTR, ScmPlugin.GetListCommitFlagEnum.IND_INCLUDE_MESSAGE));
				iterCommit = listCommit.iterator();

				while (iterCommit.hasNext()) {
					ScmPlugin.Commit commit;

					commit = iterCommit.next();

					if (!commit.mapAttr.containsKey(ScmPlugin.COMMIT_ATTR_VERSION_CHANGE) && !commit.mapAttr.containsKey(ScmPlugin.COMMIT_ATTR_REFERENCE_VERSION_CHANGE)) {
						iterCommit.remove();
					}
				}

				if (listCommit.isEmpty()) {
					userInteractionCallbackPlugin.provideInfo("No divergence found in destination module version " + moduleVersionDest + " compared to source module version " + moduleVersionSrc + '.');
				} else {
					userInteractionCallbackPlugin.provideInfo("Divergences found in destination module version " + moduleVersionDest + " compared to source module version " + moduleVersionSrc + '.');
				}

				byReferenceBooleanDestDiverges.object = Boolean.valueOf(!listCommit.isEmpty());
			}

			// If no divergence could be found, we recurse.
			if (   ((byReferenceBooleanSrcDiverges != null) && !byReferenceBooleanSrcDiverges.object.booleanValue())
			    || ((byReferenceBooleanDestDiverges != null) && !byReferenceBooleanDestDiverges.object.booleanValue())) {

				userInteractionCallbackPlugin.provideInfo("No divergence found either in source or destination. Considering common references.");

				pathModuleWorkspaceDest = scmPlugin.checkoutSystem(versionDest);
				pathModuleWorkspaceSrc = scmPlugin.checkoutSystem(versionSrc);

				if (module.isNodePluginExists(ReferenceManagerPlugin.class, null)) {
					ReferenceManagerPlugin referenceManagerPlugin;
					List<Reference> listReferenceDest;
					List<Reference> listReferenceSrc;
					Reference referenceChildSrc;

					referenceManagerPlugin = module.getNodePlugin(ReferenceManagerPlugin.class, null);
					listReferenceDest = referenceManagerPlugin.getListReference(pathModuleWorkspaceDest);
					listReferenceSrc = referenceManagerPlugin.getListReference(pathModuleWorkspaceSrc);

					for (Reference referenceChildDest: listReferenceDest) {
						referenceChildSrc = null;

						if (referenceChildDest.getModuleVersion() == null) {
							MergeReferenceGraph.logger.info("Reference " + referenceChildDest + " does not include a source reference known to Dragom. It is not processed.");
							continue;
						}

						for (Reference referenceSrc: listReferenceSrc) {
							if (referenceSrc.equalsNoVersion(referenceChildDest)) {
								referenceChildSrc = referenceSrc;
								break;
							}
						}

						if (referenceChildSrc == null) {
							MergeReferenceGraph.logger.info("A reference in the source module version " + moduleVersionSrc + " in " + pathModuleWorkspaceSrc + " corresponding to reference " + referenceChildDest + " in the destination module version " + moduleVersionDest + " in " + pathModuleWorkspaceDest + " could not be found. Destination reference skipped.");
							continue;
						}

						if (referenceChildSrc.getModuleVersion().getVersion().equals(referenceChildDest.getModuleVersion().getVersion())) {
							MergeReferenceGraph.logger.info("The reference source module version " + referenceChildSrc.getModuleVersion() + " is the same as that in the destination. Not recursing.");
							continue;
						}

						userInteractionCallbackPlugin.provideInfo("Found common source reference " + referenceChildSrc + " and destination reference " + referenceChildDest + ". Recursing to find divergences between them.");

						this.verifyDivergences(referenceChildDest.getModuleVersion().getNodePath(), referenceChildSrc.getModuleVersion().getVersion(), referenceChildDest.getModuleVersion().getVersion(), byReferenceBooleanSrcDiverges, byReferenceBooleanDestDiverges);

						// If we found divergences in both the source and destination, we exit
						// the loop since it is useless to consider the other references. If divergences
						// could not be found in one of the source or destination, we may still find some
						// in the children.
						if (   ((byReferenceBooleanSrcDiverges != null) && byReferenceBooleanSrcDiverges.object.booleanValue())
						    || ((byReferenceBooleanDestDiverges != null) && byReferenceBooleanDestDiverges.object.booleanValue())) {

							break;
						}
					}
				}
			}
		} finally {
			if (pathModuleWorkspaceSrc != null) {
				workspacePlugin.releaseWorkspaceDir(pathModuleWorkspaceSrc);
			}

			if (pathModuleWorkspaceDest != null) {
				workspacePlugin.releaseWorkspaceDir(pathModuleWorkspaceDest);
			}
		}
	}
}
//TODO: ??? record important actions.