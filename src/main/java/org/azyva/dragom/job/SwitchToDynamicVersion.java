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
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.azyva.dragom.apiutil.ByReference;
import org.azyva.dragom.execcontext.plugin.UserInteractionCallbackPlugin;
import org.azyva.dragom.execcontext.plugin.WorkspaceDirUserModuleVersion;
import org.azyva.dragom.execcontext.plugin.WorkspacePlugin;
import org.azyva.dragom.execcontext.plugin.WorkspacePlugin.WorkspaceDirAccessMode;
import org.azyva.dragom.execcontext.support.ExecContextHolder;
import org.azyva.dragom.model.ArtifactVersion;
import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.ModuleVersion;
import org.azyva.dragom.model.NodePath;
import org.azyva.dragom.model.Version;
import org.azyva.dragom.model.VersionType;
import org.azyva.dragom.model.plugin.ArtifactVersionManagerPlugin;
import org.azyva.dragom.model.plugin.ArtifactVersionMapperPlugin;
import org.azyva.dragom.model.plugin.NewDynamicVersionPlugin;
import org.azyva.dragom.model.plugin.ReferenceManagerPlugin;
import org.azyva.dragom.model.plugin.ScmPlugin;
import org.azyva.dragom.reference.Reference;
import org.azyva.dragom.util.RuntimeExceptionUserError;
import org.azyva.dragom.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * See the help information displayed by the SwitchToDynamicVersionTool.help
 * method.
 *
 * @author David Raymond
 */
public class SwitchToDynamicVersion extends RootModuleVersionJobAbstractImpl {
	/**
	 * Logger for the class.
	 */
	private static final Logger logger = LoggerFactory.getLogger(SwitchToDynamicVersion.class);

	/**
	 * Map of all modules which were switched during the processing, or that
	 * were already dynamic versions the user decided to keep. The value of the map
	 * elements is the dynamic version switched to or kept. We record that information
	 * so that if we encounter again the same module during the processing (either
	 * during the same iteration, during another iteration or for another root
	 * ModuleVersion), we assume the user will want to switch it to that same
	 * version and avoid interacting with him.
	 */
	private Map<NodePath, Version> mapNodePathVersionDynamic;

	/**
	 * Constructor.
	 *
	 * @param listModuleVersionRoot List of root ModuleVersion's within which a switch
	 *   to a dynamic version must be performed.
	 */
	public SwitchToDynamicVersion(List<ModuleVersion> listModuleVersionRoot) {
		super(listModuleVersionRoot);

		this.mapNodePathVersionDynamic = new HashMap<NodePath, Version>();
	}

	/**
	 * Called by the base class when visiting a root ModuleVersion. It bootstraps the
	 * traversal of the graph path rooted at this ModuleVersion by calling the main
	 * visitor method visitModuleForSwitchToDynamicVersion.
	 * <p>
	 * It is not possible to reuse the default implementation of this method provided
	 * by the base class as job-specific behavior is required while traversing the
	 * reference graph.
	 *
	 * @param referenceParent Root ModuleVersion passed as a Reference so that the
	 *   initial parent element of the ReferencePath can be created.
	 * @param byReferenceVersion If the method returns true, contains the new Version
	 *   of the root ModuleVersion.
	 * @return Indicates if the Version of the root ModuleVersion was changed and this
	 *   change deserves to be reflected in the List of root ModuleVersion's provided
	 *   by the caller.
	 */
	@Override
	protected boolean visitModuleVersion(Reference referenceParent, ByReference<Version> byReferenceVersion) {
		return this.visitModuleForSwitchToDynamicVersion(referenceParent, byReferenceVersion) == VisitModuleActionPerformed.SWITCH;
	}

	/**
	 * Indicates the action performed while visiting a ModuleVersion.
	 */
	private enum VisitModuleActionPerformed {
		/**
		 * No action was performed because no ModuleVersion within the ModuleVersion being
		 * visited matched the ReferencePathMatcherAnd.
		 *
		 * This implies that the caller (same method but within the context of the parent
		 * ModuleVersion) should check if the parent ModuleVersion matches the
		 * ReferencePathMatcherAnd.
		 */
		NONE,

		/**
		 * The ModuleVersion was processed, but no switch has actually been performed.
		 *
		 * This implies that at least one ModuleVersion within the ReferencePath being
		 * visited matched the ReferencePathMatcherAnd and that therefore the whole
		 * parent ReferencePath leading to the ModuleVersion being visited must be
		 * processed for a potential switch even though the ModuleVersion do not need to
		 * be updated.
		 *
		 * Said in another way, the caller (same method but within the context of the
		 * parent ModuleVersion) must process the parent ModuleVersion for a switch
		 * without check if the ModuleVersion matches the ReferencePathMatcherAnd.
		 *
		 * This also implies that the Version of the Module is already dynamic
		 * and was kept.
		 */
		PROCESSED_NO_SWITCH,

		/**
		 * This is similar to PROCESSED_NO_SWITCH, but it indicates that the Version of
		 * the ModuleVersion being visited was switched. The caller must update the parent
		 * ModuleVersion accordingly while processing it.
		 */
		SWITCH
	}

	/**
	 * Visits a ModuleVersion in the context of traversing the ReferencePath for
	 * switching ModuleVersion to a dynamic Version.
	 *
	 * After this method returns, any ModuleVersion switch within the ReferencePath
	 * rooted at this ModuleVersion will have been processed.
	 *
	 * Note that the ModuleVersion is provided within a Reference so that the
	 * ReferencePath can be properly maintained. For a root module the ModuleVersion
	 * is expected to be wrapped in a dummy Reference.
	 *
	 * Here is a general description of the algorithm implemented by this method.
	 *
	 * mapReferenceVisitModuleActionPerformed holds the visited Reference and the
	 * VisitModuleActionPerformed on these references. The idea is that all the
	 * references are first visited once, without regard to the Version of the current
	 * ModuleVersion (which is not updated during this first pass).
	 *
	 * After this first pass, if at least one reference was processed
	 * (indReferenceProcessed will have been set), then the current ModuleVersion is
	 * actually processed for the switch. Its Version may or may not be switched
	 * depending on whether it is already dynamic and on the user decision.
	 *
	 * If no reference was processed in the first pass, the current ModuleVersion may
	 * still be processed if it matches the ReferencePathMatcherAnd. In that case and
	 * if the current ModuleVersion was switched, the second iteration below is also
	 * performed since the new Version may have different references that deserve
	 * being visited.
	 *
	 * After this processing the references are iterated again and those that were
	 * visited in the first pass (are in the Map) are not visited again and if they
	 * were actually processed (or switched) are simply updated in the current
	 * ModuleVersion. The others (which were not visited in the first pass because the
	 * Version of the current Module was switched and they were not present in the
	 * original Version) are simply visited at that time.
	 *
	 * @param referenceParent Reference referring to the ModuleVersion being visited.
	 * @param byReferenceVersionParent Upon return will contain the new version of the
	 *   module if true is returned. Can be null if the caller is not interested in
	 *   that informatin.
	 * @return VisitModuleActionPerformed.
	 */
	private VisitModuleActionPerformed visitModuleForSwitchToDynamicVersion(Reference referenceParent, ByReference<Version> byReferenceVersionParent) {
		Map<Reference, VisitModuleActionPerformed> mapReferenceVisitModuleActionPerformed;
		Module module;
		ScmPlugin scmPlugin;
		WorkspacePlugin workspacePlugin = null;
		boolean indReferenceProcessed;
		Path pathModuleWorkspace = null;
		ReferenceManagerPlugin referenceManagerPlugin = null;
		List<Reference> listReference;
		UserInteractionCallbackPlugin userInteractionCallbackPlugin;
		boolean indParentSwitched;
		VisitModuleActionPerformed visitModuleActionPerformed;
		boolean indCanMatchChildren;

		this.referencePath.add(referenceParent);

		// If the caller passes null for byReferenceVersionParent, we initialize it
		// locally since we need the information within the method.
		if (byReferenceVersionParent == null) {
			byReferenceVersionParent = new ByReference<Version>();
		}

		// We use a try-finally construct to ensure that the current ModuleVersion
		// always gets removed for the ReferencePath.
		try {
			SwitchToDynamicVersion.logger.info("Visiting leaf module version of reference path " + this.referencePath + '.');

			mapReferenceVisitModuleActionPerformed = new HashMap<Reference, VisitModuleActionPerformed>();

			// We start with the references in order to perform a depth-first traversal since
			// this is the logical thing to do in the case of this task. If we were to start
			// with the ModuleVersion itself, it would be harder to distinguish between the
			// case where it is fully processed and the case where it has been matched by the
			// ReferencePathMatcherAnd but its references still need to be visited.

			module = ExecContextHolder.get().getModel().getModule(referenceParent.getModuleVersion().getNodePath());
			scmPlugin = module.getNodePlugin(ScmPlugin.class, null);
			workspacePlugin = ExecContextHolder.get().getExecContextPlugin(WorkspacePlugin.class);

			/* *******************************************************************************
			 * First pass through the references. During that first pass we simply visit the
			 * references without regard to the current ModuleVersion.
			 * *******************************************************************************/

			indReferenceProcessed = false;

			// Here we need to have access to the sources of the module so that we can obtain
			// the list of references and iterate over them. If the user already has the
			// correct version of the module checked out, we need to use it. If not, we need
			// an internal working directory which we will not modify (for now).
			// ScmPlugin.checkoutSystem does that.
			pathModuleWorkspace = scmPlugin.checkoutSystem(referenceParent.getModuleVersion().getVersion());

			// We want to ensure the workspace directory is synchronized. But it is not always
			// necessary or permitted to do so. The path returned by ScmPlugin.checkoutSystem
			// above may be a system workspace directory in which case the ScmPlugin ensures
			// it is synchronized. And if the Vesion is not DYNAMIC, we must not call
			// ScmPlugin.isSync.

			if (   (referenceParent.getModuleVersion().getVersion().getVersionType() == VersionType.DYNAMIC)
				&& (workspacePlugin.getWorkspaceDirFromPath(pathModuleWorkspace) instanceof WorkspaceDirUserModuleVersion)
				&& !scmPlugin.isSync(pathModuleWorkspace, ScmPlugin.IsSyncFlagEnum.ALL_CHANGES)) {

				throw new RuntimeExceptionUserError("The directory " + pathModuleWorkspace + " is not synchronized with the SCM. Please synchronize all directories before using this job.");
			}

			if (!module.isNodePluginExists(ReferenceManagerPlugin.class, null)) {
				listReference = Collections.emptyList();
			} else {
				referenceManagerPlugin = module.getNodePlugin(ReferenceManagerPlugin.class, null);
				listReference = referenceManagerPlugin.getListReference(pathModuleWorkspace);
			}

			// We need to release the Workspace directory before the next steps as they
			// require access to it also.
			workspacePlugin.releaseWorkspaceDir(pathModuleWorkspace);
			pathModuleWorkspace = null;

			userInteractionCallbackPlugin = ExecContextHolder.get().getExecContextPlugin(UserInteractionCallbackPlugin.class);

			// There is no need to visit the references if the ReferencePathMatcherAnd is such
			// that it cannot match any children of the current ReferencePath. In such a case
			// it is logically not required to retrieve the List of references above, but this
			// List is required towards the end, so it is always retrieved.
			if (this.referencePathMatcher.canMatchChildren(this.referencePath)) {
				for (Reference referenceChild: listReference) {
					VisitModuleActionPerformed visitModuleActionPerformedReference;

					if (referenceChild.getModuleVersion() == null) {
						SwitchToDynamicVersion.logger.info("Reference " + referenceChild + " within reference path " + this.referencePath + " does not include a source reference known to Dragom. It is not processed.");
						continue;
					}

					SwitchToDynamicVersion.logger.info("Processing reference " + referenceChild + " within reference path " + this.referencePath + '.');

					visitModuleActionPerformedReference = this.visitModuleForSwitchToDynamicVersion(referenceChild, null);

					if (Util.isAbort()) {
						// We return NONE by default, but it does not really matter when aborting.
						return VisitModuleActionPerformed.NONE;
					}

					mapReferenceVisitModuleActionPerformed.put(referenceChild, visitModuleActionPerformedReference);

					if (visitModuleActionPerformedReference != VisitModuleActionPerformed.NONE) {
						// When a reference is actually processed, signal the fact that we will need to
						// process the current ModuleVersion this way.
						indReferenceProcessed = true;
					}
				}
			}

			visitModuleActionPerformed = VisitModuleActionPerformed.NONE;

			// Within this if checkoutSystem is called to get the path to the Workspace
			// directory for the Module and it is not released since there is no other access
			// required and it is needed after the if.

			if (indReferenceProcessed) {
				/* *******************************************************************************
				 * If during the first pass through the references at least one was processed, we
				 * need to process the current ModuleVersion. This is so whether the Version of
				 * the reference was switched or not since when a ModuleVersion is processed, the
				 * algorithm is such that all the ReferencePath leading to it must also be
				 * processed.
				 * *******************************************************************************/

				userInteractionCallbackPlugin.provideInfo("At least one reference within reference path " + this.referencePath + " was processed (and maybe switched). We must process the parent, potentially switching its version to a new dynamic version.");

				indParentSwitched = this.processSwitchToDynamicVersion(referenceParent.getModuleVersion(), byReferenceVersionParent);

				// This could have been set by processSwitchToDynamicVersion if the user decided
				// not to make the switch and not to continue processing.
				if (Util.isAbort()) {
					return visitModuleActionPerformed;
				}

				if (indParentSwitched) {
					List<Reference> listReferenceNew;
					boolean indDifferencesInReferences;

					visitModuleActionPerformed = VisitModuleActionPerformed.SWITCH;

					userInteractionCallbackPlugin.provideInfo("The current module version " + referenceParent.getModuleVersion() + " was switched to version " + byReferenceVersionParent.object + ". The new version may have different references compared to the original one. Here is a summary of the version differences and actions that will be automatically reapplied to the new versions based on what was already processed:");

					pathModuleWorkspace = scmPlugin.checkoutSystem(byReferenceVersionParent.object);
					listReferenceNew = referenceManagerPlugin.getListReference(pathModuleWorkspace);
					indDifferencesInReferences = false;

					for(Reference referenceChildOrg: listReference) {
						Reference referenceChildNewFound;

						if (referenceChildOrg.getModuleVersion() == null) {
							continue;
						}

						referenceChildNewFound = null;

						for(Reference referenceChildNew: listReferenceNew) {
							if (referenceChildNew.getModuleVersion() == null) {
								continue;
							}

							if (referenceChildNew.equalsNoVersion(referenceChildOrg)) {
								referenceChildNewFound = referenceChildNew;
							}
						}

						if (referenceChildNewFound == null) {
							userInteractionCallbackPlugin.provideInfo(  "Original reference: " + referenceChildOrg + ".\n"
							                                          + "  No new reference is found that corresponds to this original reference so that even though the original reference was visited the processing that may have been performed on it will not be reapplied in the context of the new version of the current module, at least not in the context of this reference.");
							indDifferencesInReferences = true;
						} else {
							if (!referenceChildOrg.getModuleVersion().getVersion().equals(referenceChildNewFound.getModuleVersion().getVersion())) {
								Version versionEstablishedOrg;
								String difference;

								// The established version may be null (if the original reference was not
								// processed).
								versionEstablishedOrg = this.mapNodePathVersionDynamic.get(referenceChildOrg.getModuleVersion().getNodePath());

								difference = "Original reference: " + referenceChildOrg + ".\n";

								// Since we are iterating through the original references, we expect to find an
								// entry in this Map for all of them.
								switch (mapReferenceVisitModuleActionPerformed.get(referenceChildOrg)) {
								case NONE:
									difference += "  New reference " + referenceChildNewFound + " corresponds to this original reference, but with a different version " + referenceChildNewFound.getModuleVersion().getVersion() + ".\n";
									difference += "  The original reference was not processed during its visit. But with the new different version it may be. It will therefore be visited again.";
									break;

								case PROCESSED_NO_SWITCH:
									if (referenceChildNewFound.getModuleVersion().getVersion().equals(versionEstablishedOrg)) {
										difference += "  New reference " + referenceChildNewFound + " corresponds to this original reference, but with a different version " + versionEstablishedOrg + " that happens to have been previously established as the new version for this module.\n";
										difference += "  The new established version will be kept for this reference. Note that the original reference was processed but its version was not switched during its visit.";
									} else {
										difference += "  New reference " + referenceChildNewFound + " corresponds to this original reference, but with a different version " + referenceChildNewFound.getModuleVersion().getVersion() + ".\n";
										difference += "  The original reference was processed but its version was not switched during its visit. Nevertheless the version will be changed to the established version " + versionEstablishedOrg + " for the module.";
									}

									mapReferenceVisitModuleActionPerformed.put(referenceChildNewFound, VisitModuleActionPerformed.PROCESSED_NO_SWITCH);
									break;

								case SWITCH:
									if (referenceChildNewFound.getModuleVersion().getVersion().equals(versionEstablishedOrg)) {
										difference += "  New reference " + referenceChildNewFound + " corresponds to this original reference, but with a different version " + versionEstablishedOrg + " that happens to have been previously established as the new version for this module.\n";
										difference += "  The new established version will be kept for this reference. Note that the original reference was processed and switched during its visit.";
									} else {
										difference += "  New reference " + referenceChildNewFound + " corresponds to this original reference, but with a different version " + referenceChildNewFound.getModuleVersion().getVersion() + ".\n";
										difference += "  The original reference was processed and switched during its visit. Therefore the version will be changed to the established version " + versionEstablishedOrg + " for the module.";
									}

									mapReferenceVisitModuleActionPerformed.put(referenceChildNewFound, VisitModuleActionPerformed.SWITCH);
									break;
								}

								userInteractionCallbackPlugin.provideInfo(difference);
								indDifferencesInReferences = true;
							}
						}
					}

					for(Reference referenceChildNew: listReferenceNew) {
						Reference referenceChildOrgFound;

						if (referenceChildNew.getModuleVersion() == null) {
							continue;
						}

						referenceChildOrgFound = null;

						for(Reference referenceChildOrg: listReference) {
							if (referenceChildOrg.getModuleVersion() == null) {
								continue;
							}

							if (referenceChildOrg.equalsNoVersion(referenceChildNew)) {
								referenceChildOrgFound = referenceChildOrg;
								break;
							}
						}

						if (referenceChildOrgFound == null) {
							userInteractionCallbackPlugin.provideInfo(  "New reference: " + referenceChildNew + ".\n"
							                                          + "  No original reference is found that corresponds to this new reference. It will therefore be visited.");
							indDifferencesInReferences = true;
						}
					}

					if (!indDifferencesInReferences) {
						userInteractionCallbackPlugin.provideInfo("No difference in the references where found.");
					} else {
						if (!Util.handleDoYouWantToContinue(Util.DO_YOU_WANT_TO_CONTINUE_CONTEXT_REFERENCE_CHANGE_AFTER_SWITCHING)) {
							return visitModuleActionPerformed;
						}
					}

					// The final step below iterates again through the references and uses
					// listReference which must be set to the new reference List.
					listReference = listReferenceNew;

					// The parent module version was changed so we must recreate it (it is immutable)
					// and we must update the ReferencePath that includes the module version as its
					// last element.
					//TODO: This recreates a simple reference with less information. Don't know if OK.
					referenceParent = new Reference(new ModuleVersion(referenceParent.getModuleVersion().getNodePath(), byReferenceVersionParent.object));
					this.referencePath.set(this.referencePath.size() - 1, referenceParent);
				} else {
					visitModuleActionPerformed = VisitModuleActionPerformed.PROCESSED_NO_SWITCH;
				}
			} else {
				/* *******************************************************************************
				 * If after having visited the references the current ModuleVersion still has not
				 * been processed (because no reference was processed), we must check if the
				 * current ModuleVersion is matched by the ReferencePathMatcherAnd and if so
				 * process it.
				 * *******************************************************************************/

				if (this.referencePathMatcher.matches(this.referencePath)) {
					// The current ReferencePath is matched by the ReferencePathMatcherAnd. The
					// current ModuleVersion must be processed.

					userInteractionCallbackPlugin.provideInfo("The reference path " + this.referencePath + " of the current module version is matched by the reference path matcher.");

					indParentSwitched = this.processSwitchToDynamicVersion(referenceParent.getModuleVersion(), byReferenceVersionParent);

					// This could have been set by processSwitchToDynamicVersion if the user decided
					// not to make the switch and not to continue processing.
					if (Util.isAbort()) {
						return visitModuleActionPerformed;
					}

					if (indParentSwitched) {
						visitModuleActionPerformed = VisitModuleActionPerformed.SWITCH;

						// The parent module version was changed so we must recreate it (it is immutable)
						// and we must update the ReferencePath that includes the module version as its
						// last element.
						//TODO: This recreates a simple reference with less information. Don't know if OK.
						referenceParent = new Reference(new ModuleVersion(referenceParent.getModuleVersion().getNodePath(), byReferenceVersionParent.object));
						this.referencePath.set(this.referencePath.size() - 1, referenceParent);

						pathModuleWorkspace = scmPlugin.checkoutSystem(byReferenceVersionParent.object);

						// The final step below iterates again through the references and uses
						// listReference which must be set to the new reference List.
						listReference = referenceManagerPlugin.getListReference(pathModuleWorkspace);
					} else {
						visitModuleActionPerformed = VisitModuleActionPerformed.PROCESSED_NO_SWITCH;
					}
				}
			}

			/* *******************************************************************************
			 * Finally, if references were processed during the first pass (and the current
			 * ModuleVersion has necessarily been processed or switched), or if the current
			 * ModuleVersion has been switched because it was matched by the
			 * ReferencePathMatcherAnd, we must iterate again through the references. Some of
			 * them will simply need to be updated, while other new references following a
			 * switch may need to be visited.
			 * It may be argued that in the case the current ModuleVersion was switched
			 * because it was matched by the ReferencePathMatcherAnd we do not need to iterate
			 * again through the references. But then again it does not really do any harm to
			 * do so, and another ModuleVersion may be matched by the ReferencePathMatcherAnd
			 * in the new ReferencePath under the new Version of the current ModuleVersion and
			 * deserve to be processed.
			 * The variable listReference will have been updated above.
			 * If no reference was processed during the first pass but the
			 * currentModuleVersion was processed (but not switched) because it was matched by
			 * the ReferencePathMatcherAnd, we do not need to iterate again through the
			 * references since they are the same as before and will no more be processed.
			 * *******************************************************************************/

			// As an optimization, we verify if the ReferencePathMatcher can potentially match
			// children of the new ReferencePath (the version of the current ModuleVersion may
			// have been switched). If no references were processed during the first passs and
			// no child can be matched, it is useless to perform the iteration through the
			// references. If references were processed, we do need to perform the iteration
			// as this is where they are actually updated. But for those references whose
			// Version were not established, if no child can be matched, it is not necessary
			// to visit them.
			indCanMatchChildren = this.referencePathMatcher.canMatchChildren(this.referencePath);

			if (indReferenceProcessed || ((visitModuleActionPerformed == VisitModuleActionPerformed.SWITCH) && indCanMatchChildren)) {
				boolean indUserWorkspaceDir;
				String message;
				boolean indReferenceUpdated;
				ByReference<Version> byReferenceVersionChild;

				// We now need to update the references within the current ModuleVersion. We must
				// interact with the user about the workspace directory in which the module is
				// checked out.

				// Not all paths above initialize pathModuleWorkspace.
				if (pathModuleWorkspace == null) {
					pathModuleWorkspace = scmPlugin.checkoutSystem(referenceParent.getModuleVersion().getVersion());
				}

				indUserWorkspaceDir = workspacePlugin.getWorkspaceDirFromPath(pathModuleWorkspace) instanceof WorkspaceDirUserModuleVersion;

				if (indUserWorkspaceDir) {
					userInteractionCallbackPlugin.provideInfo("This module version is already checked out in " + pathModuleWorkspace + ". The change will be performed in this directory.");
				}

				// We want to perform a single commit for all reference updates, but only if at
				// least one such update is performed.
				indReferenceUpdated = false;

				byReferenceVersionChild = new ByReference<Version>();

				for (Reference referenceChild: listReference) {
					if (referenceChild.getModuleVersion() == null) {
						continue;
					}

					if (mapReferenceVisitModuleActionPerformed.containsKey(referenceChild)) {
						if (mapReferenceVisitModuleActionPerformed.get(referenceChild) != VisitModuleActionPerformed.NONE) {
							Version versionNewDynamic;

							// If the reference was already processed, we expect to find an established
							// Version in this Map.
							versionNewDynamic = this.mapNodePathVersionDynamic.get(referenceChild.getModuleVersion().getNodePath());

							if (!versionNewDynamic.equals(referenceChild.getModuleVersion().getVersion())) {
								userInteractionCallbackPlugin.provideInfo("The version of the reference " + referenceChild + " within reference path " + this.referencePath + " was established at " + versionNewDynamic + " during its visit. We must update the parent.");

								if (!Util.handleDoYouWantToContinue(Util.DO_YOU_WANT_TO_CONTINUE_CONTEXT_UPDATE_REFERENCE)) {
									return visitModuleActionPerformed;
								}

								if (referenceManagerPlugin.updateReferenceVersion(pathModuleWorkspace, referenceChild, versionNewDynamic)) {
									indReferenceUpdated = true;
									message = "Reference " + referenceChild + " within reference path " + this.referencePath + " was changed to " + versionNewDynamic + '.';
									userInteractionCallbackPlugin.provideInfo(message);
									this.listActionsPerformed.add(message);
								} else {
									userInteractionCallbackPlugin.provideInfo("Reference " + referenceChild + " within reference path " + this.referencePath + " needed to be changed to version " + versionNewDynamic + ", but this did not result in a real change in the reference. No change was performed.");
								}
							}
						}
					} else if (indCanMatchChildren) {
						SwitchToDynamicVersion.logger.info("Processing reference " + referenceChild + " within reference path " + this.referencePath + '.');

						if (this.visitModuleForSwitchToDynamicVersion(referenceChild, byReferenceVersionChild) == VisitModuleActionPerformed.SWITCH) {
							userInteractionCallbackPlugin.provideInfo("The version of the reference " + referenceChild + " within reference path " + this.referencePath + " was switched to " + byReferenceVersionChild.object + " during its visit. We must update the parent.");

							if (!Util.handleDoYouWantToContinue(Util.DO_YOU_WANT_TO_CONTINUE_CONTEXT_UPDATE_REFERENCE)) {
								return visitModuleActionPerformed;
							}

							if (referenceManagerPlugin.updateReferenceVersion(pathModuleWorkspace, referenceChild, byReferenceVersionChild.object)) {
								indReferenceUpdated = true;
								message = "Reference " + referenceChild + " within reference path " + this.referencePath + " was changed to " + byReferenceVersionChild.object + '.';
								userInteractionCallbackPlugin.provideInfo(message);
								this.listActionsPerformed.add(message);
							} else {
								userInteractionCallbackPlugin.provideInfo("Reference " + referenceChild + " within reference path " + this.referencePath + " needed to be changed to version " + byReferenceVersionChild.object + ", but this did not result in a real change in the reference. No change was performed.");
							}
						}

						if (Util.isAbort()) {
							return visitModuleActionPerformed;
						}
					}
				}

				if (indReferenceUpdated) {
					Map<String, String> mapCommitAttr;

					mapCommitAttr = new HashMap<String, String>();
					mapCommitAttr.put(ScmPlugin.COMMIT_ATTR_REFERENCE_VERSION_CHANGE, "true");
					scmPlugin.commit(pathModuleWorkspace, "One or more references within reference path " + this.referencePath + " were updated following their switch to a dynamic version.", mapCommitAttr);

					if (indUserWorkspaceDir) {
						message = "The previous changes were performed in " + pathModuleWorkspace + " which belongs to the user (you) and were committed to the SCM.";
						userInteractionCallbackPlugin.provideInfo(message);
						this.listActionsPerformed.add(message);
					} else {
						SwitchToDynamicVersion.logger.info("The previous changes were performed in " + pathModuleWorkspace + " which belongs to the system and were committed to the SCM.");
					}
				}
			}

			return visitModuleActionPerformed;
		} finally {
			this.referencePath.remove(this.referencePath.size() - 1);

			if (pathModuleWorkspace != null) {
				workspacePlugin.releaseWorkspaceDir(pathModuleWorkspace);
			}
		}
	}

	/**
	 * Processes the switch to a dynamic version for a given ModuleVersion.
	 *
	 * A switch may not be performed. For example, the user may refuse to proceed.
	 * Or the version of the module may already be dynamic and the user agrees to keep
	 * it. In such cases, false is returned.
	 *
	 * But when a switch is not performed it does not mean that nothing was done. The
	 * ArtifactVersion within the module may still have been adjusted.
	 *
	 * @param moduleVersion ModuleVersion to switch to a dynamic version.
	 * @param byReferenceVersion If a switch was performed, the new switched-to
	 *   version is recorded in this object. Can be null in which case the new
	 *   switched-to Version is not returned. It can still be accessed in
	 *   mapNodePathVersionDynamic.
	 * @return Indicates if a switch was performed, so that the caller can update the
	 *   parent.
	 */
	private boolean processSwitchToDynamicVersion(ModuleVersion moduleVersion, ByReference<Version> byReferenceVersion) {
		UserInteractionCallbackPlugin userInteractionCallbackPlugin;
		Module module;
		ScmPlugin scmPlugin;
		NewDynamicVersionPlugin newDynamicVersionPlugin;
		Version versionNewDynamic;
		ByReference<Version> byReferenceVersionBase;
		boolean indSameVersion;
		boolean indCreateNewVersion;
		WorkspacePlugin workspacePlugin;
		WorkspaceDirUserModuleVersion workspaceDirUserModuleVersion;
		Path pathModuleWorkspace = null;
		boolean indUserWorkspaceDir;
		String message;

		userInteractionCallbackPlugin = ExecContextHolder.get().getExecContextPlugin(UserInteractionCallbackPlugin.class);

		SwitchToDynamicVersion.logger.info("Processing request to switch module version " + moduleVersion + " to a dynamic version.");

		module = ExecContextHolder.get().getModel().getModule(moduleVersion.getNodePath());
		scmPlugin = module.getNodePlugin(ScmPlugin.class, null);
		newDynamicVersionPlugin = module.getNodePlugin(NewDynamicVersionPlugin.class, null);

		versionNewDynamic = this.mapNodePathVersionDynamic.get(moduleVersion.getNodePath());

		if (versionNewDynamic != null) {
			// New versions stored in mapNodePathVersionDynamic take
			// precedence so we return without interacting with the user.

			if (versionNewDynamic.equals(moduleVersion.getVersion())) {
				// If the version of the module is the same as the new version stored in
				// mapNodePathVersionDynamic, simply keep it. No switch needs to
				// be performed.
				SwitchToDynamicVersion.logger.info("Module version " + moduleVersion + " is currently a dynamic version that was already switched to or previously kept by the user. We simply keep it.");
				return false;
			} else {
				// we rightfully assume the new version exists since it was inserted in
				// mapNodePathVersionDynamic. In that case we do not even have to
				// check it out in some workspace directory. It probably already is, but even if
				// not, if the caller needs the sources, it is responsible for checking it out
				// itself.
				SwitchToDynamicVersion.logger.info("Another version " + versionNewDynamic + " of module version " + moduleVersion + " was already switched to or previously kept by the user. We assume we want to switch that module version to that same version.");

				if (byReferenceVersion != null) {
					byReferenceVersion.object = versionNewDynamic;
				}

				return true;
			}
		}

		// Here we know the module was never switched to a new version as there is no
		// entry for it in mapNodePathVersionDynamic.

		byReferenceVersionBase = new ByReference<Version>();
		versionNewDynamic = newDynamicVersionPlugin.getVersionNewDynamic(moduleVersion.getVersion(), byReferenceVersionBase);

		indSameVersion = versionNewDynamic.equals(moduleVersion.getVersion());

		workspacePlugin = ExecContextHolder.get().getExecContextPlugin(WorkspacePlugin.class);

		workspaceDirUserModuleVersion = new WorkspaceDirUserModuleVersion(moduleVersion);

		indUserWorkspaceDir = workspacePlugin.isWorkspaceDirExist(workspaceDirUserModuleVersion);

		if (indSameVersion) {
			userInteractionCallbackPlugin.provideInfo("Module version " + moduleVersion + " is to be kept. The artifact version will simply be adjusted if required.");
		} else {
			userInteractionCallbackPlugin.provideInfo("Module version " + moduleVersion + " will be switched to version " + versionNewDynamic + '.');
		}

		try {
			if (indUserWorkspaceDir) {
				pathModuleWorkspace = workspacePlugin.getWorkspaceDir(workspaceDirUserModuleVersion, WorkspacePlugin.GetWorkspaceDirModeEnum.GET_EXISTING, WorkspaceDirAccessMode.READ_WRITE);

				userInteractionCallbackPlugin.provideInfo("Original module version " + moduleVersion + " is already checked out in " + pathModuleWorkspace + ". The version in this directory will be switched to the new version " + versionNewDynamic + '.');

				if (!scmPlugin.isSync(pathModuleWorkspace, ScmPlugin.IsSyncFlagEnum.ALL_CHANGES)) {
					throw new RuntimeExceptionUserError("The directory " + pathModuleWorkspace + " is not synchronized with the SCM. Please synchronize all directories before using this job.");
				}
			}

			indCreateNewVersion = !scmPlugin.isVersionExists(versionNewDynamic);

			if (indCreateNewVersion) {
				userInteractionCallbackPlugin.provideInfo("The new version " + versionNewDynamic + " does not exist and will be created based on version " + byReferenceVersionBase.object + '.');
			}

			if (!Util.handleDoYouWantToContinue(Util.DO_YOU_WANT_TO_CONTINUE_CONTEXT_CREATE_DYNAMIC_VERSION)) {
				return false;
			}

			// At this point pathModuleWorkspace is initialized only if the module was in a
			// user workspace directory. It is initialized in the other cases below.

			if (!indSameVersion) {
				if (indUserWorkspaceDir) {
					if (indCreateNewVersion) {
						scmPlugin.switchVersion(pathModuleWorkspace, byReferenceVersionBase.object);
						scmPlugin.createVersion(pathModuleWorkspace, versionNewDynamic, true);
						message = "The version in directory " + pathModuleWorkspace + " was switched to the base version " + byReferenceVersionBase.object + ", a new version " + versionNewDynamic + " was created based on it and was switched to.";
						userInteractionCallbackPlugin.provideInfo(message);
						this.listActionsPerformed.add(message);
					} else {
						SwitchToDynamicVersion.logger.info("Switching the version in directory " + pathModuleWorkspace + " to the new version " + versionNewDynamic + '.');
						scmPlugin.switchVersion(pathModuleWorkspace, versionNewDynamic);
						message = "The version in directory " + pathModuleWorkspace + " was switched to the new version " + versionNewDynamic + '.';
						userInteractionCallbackPlugin.provideInfo(message);
						this.listActionsPerformed.add(message);
					}
				} else {
					if (indCreateNewVersion) {
						pathModuleWorkspace = scmPlugin.checkoutSystem(byReferenceVersionBase.object);
						scmPlugin.createVersion(pathModuleWorkspace, versionNewDynamic, true);
						message = "New version " + versionNewDynamic + " was created based on version " + byReferenceVersionBase.object + " for module " + moduleVersion.getNodePath() + '.';
						userInteractionCallbackPlugin.provideInfo(message);
						this.listActionsPerformed.add(message);
						SwitchToDynamicVersion.logger.info("The pevious change was performed in the system directory " + pathModuleWorkspace + '.');
					} else {
						// We need to perform the checkout even if this is a system working directory
						// since the path is used below.
						pathModuleWorkspace = scmPlugin.checkoutSystem(versionNewDynamic);

						SwitchToDynamicVersion.logger.info("Checked out new version " + versionNewDynamic + " of module " + moduleVersion.getNodePath() + " into system directory " + pathModuleWorkspace + '.');
					}
				}
			} else {
				if (!indUserWorkspaceDir) {
					pathModuleWorkspace = scmPlugin.checkoutSystem(versionNewDynamic);
				}
			}

			// At this point pathModuleWorkspace is always initialized.

			if (!module.isNodePluginExists(ArtifactVersionManagerPlugin.class, null)) {
				SwitchToDynamicVersion.logger.info("The module " + module + " does not expose the ArtifactVersionManagerPlugin plugin which implies that it does not manage artifact version and that there is no need to update it.");
			} else {
				ArtifactVersion artifactVersion;
				ArtifactVersion artifactVersionNew;
				ArtifactVersionManagerPlugin artifactVersionManagerPlugin;

				// If a new Version was created, we need to update the ArtifactVersion. If the
				// version already exists, we need to ensure the ArtifactVersion corresponds to
				// the new Version, and adjust it if not. The reason is that Dragom does not
				// ensure in all cases that the ArtifactVersion corresponds to the Version, such
				// as when creating a static Version and the previous dynamic Version is not
				// reverted. And even if the previous dynamic Version is reverted, the
				// ArtifactVersionMapperPlugin may use an algorithm that includes runtime data
				// to perform the mapping, so that the ArtifactVersion corresponding to the
				// Version may need to be updated.

				ArtifactVersionMapperPlugin artifactVersionMapperPlugin;

				artifactVersionManagerPlugin = module.getNodePlugin(ArtifactVersionManagerPlugin.class, null);
				artifactVersionMapperPlugin = module.getNodePlugin(ArtifactVersionMapperPlugin.class, null);
				artifactVersion = artifactVersionManagerPlugin.getArtifactVersion(pathModuleWorkspace);
				artifactVersionNew = artifactVersionMapperPlugin.mapVersionToArtifactVersion(versionNewDynamic);

				// If the new Version did not exist and has been created, we do not expect the
				// ArtifactVersion to be already set to the right corresponding ArtifactVersion.
				// But we do not validate this and we simply do not update it in that case.
				if (artifactVersionManagerPlugin.setArtifactVersion(pathModuleWorkspace, artifactVersionNew)) {
					List<ScmPlugin.Commit> listCommit;
					String stringEquivalentStaticVersion;
					Version versionEquivalentStatic;
					Map<String, String> mapCommitAttr;

					// We are about to introduce a commit that adjusts the ArtifactVersion according
					// to the new dynamic Version. For an already existing dynamic Version this should
					// already have been done when the Version was created. But following the creation
					// of a static Version, Dragom needs to know that if no new commit was introduced
					// the dynamic Version is equivalent to the static Version just created. It does
					// that either by looking at the static Version (tag) created on the last commit of
					// the dynamic Version (branch), or if a new reverting commit was introduced, by
					// looking at an attribute of this commit that specifies the equivalent static
					// Version. If we introduce that new reverting commit here, we must then specify
					// that commit attribute.
	//TODO: Not sure if that logic should not be in some plugin. Probably it is OK here since the logic is also in CreateStaticVersion, a job.
	// But maybe the logic to retrieve that information (equivalent static Version) should be centralized. It exists in NewStaticVersionPluginBaseImpl and here.
					listCommit = scmPlugin.getListCommit(versionNewDynamic, new ScmPlugin.CommitPaging(1), EnumSet.of(ScmPlugin.GetListCommitFlagEnum.IND_INCLUDE_MAP_ATTR, ScmPlugin.GetListCommitFlagEnum.IND_INCLUDE_VERSION_STATIC));

					if (!listCommit.isEmpty()) {
						ScmPlugin.Commit commit;

						commit = listCommit.get(0);

						stringEquivalentStaticVersion = commit.mapAttr.get(ScmPlugin.COMMIT_ATTR_EQUIVALENT_STATIC_VERSION);

						if (stringEquivalentStaticVersion != null) {
							versionEquivalentStatic = new Version(stringEquivalentStaticVersion);

							if (versionEquivalentStatic.getVersionType() != VersionType.STATIC) {
								throw new RuntimeException("Version " + versionEquivalentStatic + " must be static.");
							}
						} else if (commit.arrayVersionStatic.length >= 1) {
							versionEquivalentStatic = commit.arrayVersionStatic[0];
						} else {
							versionEquivalentStatic = null;
						}
					} else {
						versionEquivalentStatic = null;
					}

					message = "Artifact version within module " + module.getNodePath() + " has been updated to " + artifactVersionNew + " from " + artifactVersion + " following the creation of, the switch to or the reuse of the new version " + versionNewDynamic + '.';

					mapCommitAttr = new HashMap<String, String>();

					if (versionEquivalentStatic != null) {
						mapCommitAttr.put(ScmPlugin.COMMIT_ATTR_EQUIVALENT_STATIC_VERSION, versionEquivalentStatic.toString());
					}

					mapCommitAttr.put(ScmPlugin.COMMIT_ATTR_VERSION_CHANGE, "true");

					scmPlugin.commit(pathModuleWorkspace, message, mapCommitAttr);
					userInteractionCallbackPlugin.provideInfo(message);
					this.listActionsPerformed.add(message);

					if (indUserWorkspaceDir) {
						message = "The pevious change was performed in " + pathModuleWorkspace + " and was committed to the SCM.";
						userInteractionCallbackPlugin.provideInfo(message);
						this.listActionsPerformed.add(message);
					} else {
						SwitchToDynamicVersion.logger.info("The pevious change was performed in the system directory " + pathModuleWorkspace + " and was committed to the SCM.");
					}
				}
			}
		} finally {
			if (pathModuleWorkspace != null) {
				workspacePlugin.releaseWorkspaceDir(pathModuleWorkspace);
			}
		}

		this.mapNodePathVersionDynamic.put(moduleVersion.getNodePath(), versionNewDynamic);

		if (byReferenceVersion != null) {
			byReferenceVersion.object = versionNewDynamic;
		}

		return !indSameVersion;
	}
}