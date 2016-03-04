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

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.azyva.dragom.apiutil.ByReference;
import org.azyva.dragom.execcontext.ExecContext;
import org.azyva.dragom.execcontext.plugin.RuntimePropertiesPlugin;
import org.azyva.dragom.execcontext.plugin.UserInteractionCallbackPlugin;
import org.azyva.dragom.execcontext.plugin.WorkspaceDirUserModuleVersion;
import org.azyva.dragom.execcontext.plugin.WorkspacePlugin;
import org.azyva.dragom.execcontext.plugin.WorkspacePlugin.WorkspaceDirAccessMode;
import org.azyva.dragom.execcontext.support.ExecContextHolder;
import org.azyva.dragom.model.ArtifactVersion;
import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.ModuleVersion;
import org.azyva.dragom.model.Version;
import org.azyva.dragom.model.VersionType;
import org.azyva.dragom.model.plugin.ArtifactVersionManagerPlugin;
import org.azyva.dragom.model.plugin.ArtifactVersionMapperPlugin;
import org.azyva.dragom.model.plugin.BuilderPlugin;
import org.azyva.dragom.model.plugin.NewStaticVersionPlugin;
import org.azyva.dragom.model.plugin.ReferenceManagerPlugin;
import org.azyva.dragom.model.plugin.ScmPlugin;
import org.azyva.dragom.reference.Reference;
import org.azyva.dragom.util.AlwaysNeverAskUserResponse;
import org.azyva.dragom.util.RuntimeExceptionUserError;
import org.azyva.dragom.util.Util;
import org.azyva.dragom.util.YesAlwaysNoUserResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * See the help information displayed by the CreateStaticVersionTool.help method.
 *
 * The principle of operation of this class is to traverse reference graphs in the
 * standard way using a List of root ModuleVersion's and a ReferencePathMatcher to
 * identify ModuleVersion's for which a static Version must be created. Once such
 * a ModuleVersion is found, the static Version creation process begins which does
 * also involve some traversal of the reference graph in order to ensure that no
 * reference to dynamic Version's remain in the graph and to recursively create
 * static Version's out of them. But this other traversal is not handled through
 * the main traversal. It starts with the matched ModuleVersion and does not take
 * into consideration of List of root ModuleVersion's nor the
 * ReferencePathMatcher.
 *
 * If a static Version must be created for a ModuleVersion outside of a
 * ReferencePath context, listModuleVersionRoot passed to the constructor can
 * contain that single ModuleVersion and no ReferencePathMatcher can be set
 * (thereby using ReferencePathMatcherAll).
 *
 * Note that only ReferencePath's corresponding to dynamic Version's are actually
 * considered, even though the ReferencePathMatcher may match other Version's.
 * Version's.
 *
 * @author David Raymond
 */
public class CreateStaticVersion extends RootModuleVersionJobAbstractImpl {
	/**
	 * Logger for the class.
	 */
	private static final Logger logger = LoggerFactory.getLogger(CreateStaticVersion.class);

	/**
	 * Runtime property specifying the build context to pass to
	 * {@link BuilderPlugin#build}.
	 */
	private static final String RUNTIME_PROPERTY_CREATE_STATIC_VERSION_BUILD_CONTEXT = "CREATE_STATIC_VERSION_BUILD_CONTEXT";

	private static final String RUNTIME_PROPERTY_NO_PRE_CREATE_STATIC_VERSION_VALIDATION_BUILD = "NO_PRE_CREATE_STATIC_VERSION_VALIDATION_BUILD";

	private static final String RUNTIME_PROPERTY_REVERT_ARTIFACT_VERSION = "REVERT_ARTIFACT_VERSION";

	/**
	 * Map of all ModuleVersion which were created during the processing. The value
	 * of map elements is the static version created. We record that information so
	 * that if we encounter again the same module during the processing, we assume the
	 * user will want to create that same version and avoid interacting with him.
	 *
	 * Normally within a ReferencePath we expect to encounter at most a single dynamic
	 * Version for a given module, so that the Map could have been from NodePath to
	 * Version. But in theory there could be multiple Version's for a Module and the
	 * user may decide to create a different static Version.
	 */
	private Map<ModuleVersion, Version> mapModuleVersionStatic;

	/**
	 * Default response for reverting the ArtifactVersion when the mode is to ask each
	 * time.
	 *
	 * By symmetry with other runtime properties, this information could be stored as
	 * a runtime property. But since it is only a boolean we simply keep a default
	 * value for each execution of the job.
	 */
	private boolean indRevertArtifactVersionDefault;

	/**
	 * Constructor.
	 *
	 * @param listModuleVersionRoot List of root ModuleVersion's within which new
	 *   static Version's must be created.
	 */
	public CreateStaticVersion(List<ModuleVersion> listModuleVersionRoot) {
		super(listModuleVersionRoot);

		this.mapModuleVersionStatic = new HashMap<ModuleVersion, Version>();
	}

	/**
	 * Called by the base class when visiting a root ModuleVersion. This method
	 * performs the main traversal of the reference graph rooted at this root
	 * ModuleVersion to find ModuleVersion's for which a static Version must be
	 * created. It is not the method which actually creates these static
	 * Version's. When a ModuleVersion is found, visitModuleForCreateStaticVersion
	 * is called.
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
		Module module;
		WorkspacePlugin workspacePlugin;
		Path pathModuleWorkspace = null;
		ScmPlugin scmPlugin;
		boolean indReferencePathAlreadyReverted;

		this.referencePath.add(referenceParent);
		indReferencePathAlreadyReverted = false;

		workspacePlugin = ExecContextHolder.get().getExecContextPlugin(WorkspacePlugin.class);

		// We use a try-finally construct to ensure that the current module version
		// always gets removed for the current ReferencePath.
		try {
			CreateStaticVersion.logger.info("Visiting leaf module version of reference path " + this.referencePath + '.');

			module = ExecContextHolder.get().getModel().getModule(referenceParent.getModuleVersion().getNodePath());

			scmPlugin = module.getNodePlugin(ScmPlugin.class, null);

			if (referenceParent.getModuleVersion().getVersion().getVersionType() == VersionType.DYNAMIC) {
				UserInteractionCallbackPlugin userInteractionCallbackPlugin;

				userInteractionCallbackPlugin = ExecContextHolder.get().getExecContextPlugin(UserInteractionCallbackPlugin.class);

				if (this.referencePathMatcher.matches(this.referencePath)) {
					// As an optimization we first verify if a new Version was already created or
					// established for the ModuleVersion during the execution of the job. If so, we
					// must reuse it. It is not essential to perform this verification here since it
					// would be naturally performed later in processCreateStaticVersion. But it avoid
					// useless processing and more importantly it is less confusing for the user.
					if (this.handleAlreadyCreatedStaticVersion(referenceParent.getModuleVersion(), byReferenceVersion)) {
						userInteractionCallbackPlugin.provideInfo("The reference path " + this.referencePath + " of the current module version is matched by the reference path matcher. But a static version " + byReferenceVersion.object + " was already established for the leaf module version. We simply reuse that version.");
						return true;
					}

					userInteractionCallbackPlugin.provideInfo("The reference path " + this.referencePath + " of the current module version is matched by the reference path matcher. Initiating the process for creating a static version for module version " + referenceParent.getModuleVersion() + '.');

					// We are about to delegate to visitModuleForCreateStaticVersion for the rest of
					// the processing. This method starts working on the same current module and also
					// manages the graph path. We must therefore reset it now so that it can re-add
					// the current reference. And we must prevent the finally block to reset it.
					this.referencePath.remove(this.referencePath.size() - 1);
					indReferencePathAlreadyReverted = true;

					// When the Version of the ModuleVersion is dynamic and the ReferencePath is
					// matched by the ReferencePathMatcher, we delegate the processing to the
					// visitModuleForCreateStaticVersion method for actually creating the static
					// Version.
					// We do not process the references of the ModuleVersion in that case since by
					// definition, all Version's within the reference graph rooted at this
					// ModuleVersion are now static and are considered has having been processed.
					if (this.visitModuleForCreateStaticVersion(referenceParent, byReferenceVersion)) {
						return true;
					} else {
						Util.setAbort();
						return false;
					}
				} else if (this.referencePathMatcher.canMatchChildren(this.referencePath)) {
					boolean indUserWorkspaceDir;
					ReferenceManagerPlugin referenceManagerPlugin = null;
					List<Reference> listReference;

					workspacePlugin = ExecContextHolder.get().getExecContextPlugin(WorkspacePlugin.class);

					// Here we need to have access to the sources of the module so that we can obtain
					// the list of references and iterate over them. If the user already has the
					// correct version of the module checked out, we need to use it. If not, we need
					// an internal working directory which we will not modify (for now).
					// ScmPlugin.checkoutSystem does that.

					pathModuleWorkspace = scmPlugin.checkoutSystem(referenceParent.getModuleVersion().getVersion());

					if (!scmPlugin.isSync(pathModuleWorkspace, ScmPlugin.IsSyncFlagEnum.ALL_CHANGES)) {
						throw new RuntimeExceptionUserError("The directory " + pathModuleWorkspace + " is not synchronized with the SCM. Please synchronize all directories before using this job.");
					}

					indUserWorkspaceDir = workspacePlugin.getWorkspaceDirFromPath(pathModuleWorkspace) instanceof WorkspaceDirUserModuleVersion;

					if (!module.isNodePluginExists(ReferenceManagerPlugin.class, null)) {
						listReference = Collections.emptyList();
					} else {
						referenceManagerPlugin = module.getNodePlugin(ReferenceManagerPlugin.class, null);
						listReference = referenceManagerPlugin.getListReference(pathModuleWorkspace);
					}

					for (Reference referenceChild: listReference) {
						ByReference<Version> byReferenceVersionChild;
						boolean indVersionChanged;

						if (referenceChild.getModuleVersion() == null) {
							CreateStaticVersion.logger.info("Reference " + referenceChild + " within reference path " + this.referencePath + " does not include a source reference known to Dragom. It is not processed.");
							continue;
						}

						CreateStaticVersion.logger.info("Processing reference " + referenceChild + " within reference path " + this.referencePath + '.');

						byReferenceVersionChild = new ByReference<Version>();

						indVersionChanged = this.visitModuleVersion(referenceChild, byReferenceVersionChild);

						if (Util.isAbort()) {
							return false;
						}

						// indVersionChanged can be true only if a static Version was created for the
						// reference child that was just visited. In such as case, we must update the
						// Version within the parent, which is necessarily dynamic.
						if (indVersionChanged) {
							String message;

							userInteractionCallbackPlugin.provideInfo("The version of reference " + referenceChild + " within reference path " + this.referencePath + " has been changed to " + byReferenceVersionChild.object + ". We must update the parent.");

							if (indUserWorkspaceDir) {
								userInteractionCallbackPlugin.provideInfo("This module version is already checked out in " + pathModuleWorkspace + ". The change will be performed in this directory.");
							}

							if (referenceManagerPlugin.updateReferenceVersion(pathModuleWorkspace, referenceChild, byReferenceVersionChild.object)) {
								Map<String, String> mapCommitAttr;

								message = "Reference " + referenceChild + " within reference path " + this.referencePath + " was changed to version " + byReferenceVersionChild.object + '.';
								mapCommitAttr = new HashMap<String, String>();
								mapCommitAttr.put(ScmPlugin.COMMIT_ATTR_REFERENCE_VERSION_CHANGE, "true");
								scmPlugin.commit(pathModuleWorkspace, message, mapCommitAttr);
								userInteractionCallbackPlugin.provideInfo(message);
								this.listActionsPerformed.add(message);

								if (indUserWorkspaceDir) {
									message = "The previous change was performed in " + pathModuleWorkspace + " which belongs to the user (you) and was committed to the SCM.";
									userInteractionCallbackPlugin.provideInfo(message);
									this.listActionsPerformed.add(message);
								} else {
									CreateStaticVersion.logger.info("The previous change was performed in " + pathModuleWorkspace + " which belongs to the system and was committed to the SCM.");
								}
							} else {
								userInteractionCallbackPlugin.provideInfo("Reference " + referenceChild + " within reference path " + this.referencePath + " needed to be changed to version " + byReferenceVersionChild.object + ", but this did not result in a real change in the reference. No change was performed.");
							}
						}
					}
				}
			}
		} finally {
			if (pathModuleWorkspace != null) {
				workspacePlugin.releaseWorkspaceDir(pathModuleWorkspace);
			}

			if (!indReferencePathAlreadyReverted) {
				this.referencePath.remove(this.referencePath.size() - 1);
			}
		}

		return false;
	}

	/**
	 * Visits a ModuleVersion in the context of traversing the ReferencePath for
	 * creating a static Version. The Version of the module must be dynamic.
	 *
	 * This method looks similar to visitModuleVersion, but does not serve the same
	 * purpose. visitModuleVersion is used to traverse the reference graph to find
	 * ModuleVersion's for which a static Version needs to be created. This method
	 * takes over during that traversal when we know a static Version needs to be
	 * created for the ModuleVersion.
	 *
	 * @param referenceParent Reference referring to the ModuleVersion for which a new
	 *   static Version needs to be created.
	 * @param byReferenceVersion If the method returns true, contains the new static
	 *   Version of the ModuleVersion.
	 * @return Indicates if the Version of the ModuleVersion was changed. If
	 *   false is returned it must be considered the user aborted the process.
	 */
	private boolean visitModuleForCreateStaticVersion(Reference referenceParent, ByReference<Version> byReferenceVersion) {
		Path pathModuleWorkspace;
		UserInteractionCallbackPlugin userInteractionCallbackPlugin;
		Module module;
		ScmPlugin scmPlugin;
		WorkspacePlugin workspacePlugin = null;
		ReferenceManagerPlugin referenceManagerPlugin = null;
		List<Reference> listReference;

		pathModuleWorkspace = null;
		this.referencePath.add(referenceParent);

		// We use a try - finally construct to ensure that the current module version
		// always gets removed for the current ReferencePath.
		try {
			if (referenceParent.getModuleVersion().getVersion().getVersionType() != VersionType.DYNAMIC) {
				// This should not happen since this method is not supposed to be called on static
				// version.
				throw new RuntimeException("Module version " + referenceParent.getModuleVersion() + " within reference path " + this.referencePath + " is not dynamic.");
			}

			CreateStaticVersion.logger.info("Visiting leaf module version of reference path " + this.referencePath + '.');

			userInteractionCallbackPlugin = ExecContextHolder.get().getExecContextPlugin(UserInteractionCallbackPlugin.class);

			// As an optimization we first verify if a new Version was already created or
			// established for the ModuleVersion during the execution of the job. If so, we
			// must reuse it. It is not essential to perform this verification here since it
			// would be naturally performed later in processCreateStaticVersion. But it avoid
			// useless processing and more importantly it is less confusing for the user.
			if (this.handleAlreadyCreatedStaticVersion(referenceParent.getModuleVersion(), byReferenceVersion)) {
				userInteractionCallbackPlugin.provideInfo("A static version " + byReferenceVersion.object + " was already created for module version " + referenceParent.getModuleVersion() + ". We automatically reuse that same version.");
				return true;
			}

			// Before creating a static version for the module we must ensure that it refers
			// only to static Version of other modules.

			module = ExecContextHolder.get().getModel().getModule(referenceParent.getModuleVersion().getNodePath());

			scmPlugin = module.getNodePlugin(ScmPlugin.class, null);
			workspacePlugin = ExecContextHolder.get().getExecContextPlugin(WorkspacePlugin.class);

			// Here we need to have access to the sources of the module so that we can obtain
			// the list of references and iterate over them. If the user already has the
			// correct version of the module checked out, we need to use it. If not, we need an
			// internal working directory which we will not modify (for now).
			// ScmPlugin.checkoutSystem does that.

			pathModuleWorkspace = scmPlugin.checkoutSystem(referenceParent.getModuleVersion().getVersion());

			if (!scmPlugin.isSync(pathModuleWorkspace, ScmPlugin.IsSyncFlagEnum.ALL_CHANGES)) {
				throw new RuntimeExceptionUserError("The directory " + pathModuleWorkspace + " is not synchronized with the SCM. Please synchronize all directories before using this job.");
			}

			if (!module.isNodePluginExists(ReferenceManagerPlugin.class, null)) {
				listReference = Collections.emptyList();
			} else {
				referenceManagerPlugin = module.getNodePlugin(ReferenceManagerPlugin.class, null);
				listReference = referenceManagerPlugin.getListReference(pathModuleWorkspace);
			}

			// We must first ensure that all references are to static Version, offering the
			// user the opportunity to create static Version.

			for (Reference referenceChild: listReference) {
				ByReference<Version> byReferenceVersionReference;
				boolean indUserWorkspaceDir;
				String message;

				if (referenceChild.getModuleVersion() == null) {
					if (referenceChild.getArtifactVersion().getVersionType() == VersionType.DYNAMIC) {
						//TODO: Maybe handle that (simply ask the user for new static version to use).
						throw new RuntimeExceptionUserError("Reference " + referenceChild + " within reference path " + this.referencePath + " is refers to a dynamic version of an external module.");
					}

					continue;
				}

				if (referenceChild.getModuleVersion().getVersion().getVersionType() == VersionType.STATIC) {
					continue;
				}

				CreateStaticVersion.logger.info("Processing reference " + referenceChild + " within reference path " + this.referencePath + '.');

				byReferenceVersionReference = new ByReference<Version>();

				if (!this.visitModuleForCreateStaticVersion(referenceChild, byReferenceVersionReference)) {
					return false;
				}

				userInteractionCallbackPlugin.provideInfo("A static version " + byReferenceVersionReference.object + " for reference " + referenceChild + " within reference path " + this.referencePath + " was created during its visit. We must update the parent.");

				// Since we will be updating the module, we must interact with the user about the
				// workspace directory in which the module is checked out.

				indUserWorkspaceDir = workspacePlugin.getWorkspaceDirFromPath(pathModuleWorkspace) instanceof WorkspaceDirUserModuleVersion;

				userInteractionCallbackPlugin.provideInfo("Reference " + referenceChild + " will be updated in order to change the version to " + byReferenceVersionReference.object + '.');

				if (indUserWorkspaceDir) {
					userInteractionCallbackPlugin.provideInfo("This module version is already checked out in " + pathModuleWorkspace + ". The change will be performed in this directory.");
				}

				if (!Util.handleDoYouWantToContinue(Util.DO_YOU_WANT_TO_CONTINUE_CONTEXT_UPDATE_REFERENCE)) {
					return false;
				}

				if (referenceManagerPlugin.updateReferenceVersion(pathModuleWorkspace, referenceChild, byReferenceVersionReference.object)) {
					Map<String, String> mapCommitAttr;

					message = "Reference " + referenceChild + " within reference path " + this.referencePath + " was changed to version " + byReferenceVersionReference.object + '.';
					mapCommitAttr = new HashMap<String, String>();
					mapCommitAttr.put(ScmPlugin.COMMIT_ATTR_REFERENCE_VERSION_CHANGE, "true");
					scmPlugin.commit(pathModuleWorkspace, message, mapCommitAttr);
					userInteractionCallbackPlugin.provideInfo(message);
					this.listActionsPerformed.add(message);

					if (indUserWorkspaceDir) {
						message = "The previous change was performed in " + pathModuleWorkspace + " which belongs to the user (you) and was committed to the SCM.";
						userInteractionCallbackPlugin.provideInfo(message);
						this.listActionsPerformed.add(message);
					} else {
						CreateStaticVersion.logger.info("The previous change was performed in " + pathModuleWorkspace + " which belongs to the system and was committed to the SCM.");
					}
				} else {
					userInteractionCallbackPlugin.provideInfo("Reference " + referenceChild + " within reference path " + this.referencePath + " needed to be changed to version " + byReferenceVersionReference.object + ", but this did not result in a real change in the reference. No change was performed.");
				}

			}

			// We must release the workspace directory since it will be requested again by
			// processCreateStaticVersion below.
			workspacePlugin.releaseWorkspaceDir(pathModuleWorkspace);
			pathModuleWorkspace = null;

			// Then we must change the version of the module itself to a static version (it is
			// necessarily dynamic).
			return this.processCreateStaticVersion(referenceParent.getModuleVersion(), byReferenceVersion);

			// The version of the module was changed but we do not need to update the
			// reference path since we are anyway exiting from this module and removing it
			// from the ReferencePath.
		} finally {
			if (pathModuleWorkspace != null) {
				workspacePlugin.releaseWorkspaceDir(pathModuleWorkspace);
			}

			this.referencePath.remove(this.referencePath.size() - 1);
		}
	}

	/**
	 * Processes the creation of a static version for a given ModuleVersion.
	 *
	 * The ModuleVersion must be dynamic.
	 *
	 * The user may be required to confirm and may refuse. In that case false is
	 * returned and the whole process is aborted since every creation need to be
	 * performed.
	 *
	 * @param moduleVersion ModuleVersion for which to create a static version.
	 * @param byReferenceVersion If a static version was created, the new version is
	 *   recorded in this object.
	 * @return Indicates if a version was created or if the user cancelled.
	 */
	private boolean processCreateStaticVersion(ModuleVersion moduleVersion, ByReference<Version> byReferenceVersion) {
		ExecContext execContext;
		UserInteractionCallbackPlugin userInteractionCallbackPlugin;
		RuntimePropertiesPlugin runtimePropertiesPlugin;
		Version versionStaticNew;
		Module module;
		ScmPlugin scmPlugin;
		NewStaticVersionPlugin newStaticVersionPlugin;
		YesAlwaysNoUserResponse yesAlwaysNoUserResponse;
		WorkspacePlugin workspacePlugin;
		WorkspaceDirUserModuleVersion workspaceDirUserModuleVersion;
		Path pathModuleWorkspace = null;
		boolean indUserWorkspaceDir;
		ArtifactVersion artifactVersion = null;
		ArtifactVersionManagerPlugin artifactVersionManagerPlugin = null;
		ArtifactVersionMapperPlugin artifactVersionMapperPlugin = null;
		boolean indCommitRequired;
		String message;

		execContext = ExecContextHolder.get();
		userInteractionCallbackPlugin = execContext.getExecContextPlugin(UserInteractionCallbackPlugin.class);
		runtimePropertiesPlugin = execContext.getExecContextPlugin(RuntimePropertiesPlugin.class);

		CreateStaticVersion.logger.info("Processing request to create a static version for module version " + moduleVersion + '.');

		// New versions stored in mapModuleVersionStatic take precedence. If a mew version
		// is found in this Map we are sure it has been created for the module and we can
		// simply return it.
		if (this.handleAlreadyCreatedStaticVersion(moduleVersion, byReferenceVersion)) {
			userInteractionCallbackPlugin.provideInfo("A static version " + byReferenceVersion.object + " was already created for module version " + moduleVersion + ". We automatically reuse that same version.");
			return true;
		}

		module = execContext.getModel().getModule(moduleVersion.getNodePath());
		scmPlugin = module.getNodePlugin(ScmPlugin.class, null);
		newStaticVersionPlugin = module.getNodePlugin(NewStaticVersionPlugin.class, null);

		versionStaticNew = newStaticVersionPlugin.getVersionNewStatic(moduleVersion.getVersion());

		if (scmPlugin.isVersionExists(versionStaticNew)) {
			userInteractionCallbackPlugin.provideInfo("An existing static version " + versionStaticNew + " for module version " + moduleVersion + " was selected. The process will continue as if that version had just been created. It probably was in a previous iteration of this same process.");
			this.mapModuleVersionStatic.put(moduleVersion, versionStaticNew);
			byReferenceVersion.object = versionStaticNew;
			return true;
		}

		// Here versionStaticNew holds the new version to create (it does not exist).

		workspacePlugin = execContext.getExecContextPlugin(WorkspacePlugin.class);
		workspaceDirUserModuleVersion = new WorkspaceDirUserModuleVersion(moduleVersion);
		indUserWorkspaceDir = workspacePlugin.isWorkspaceDirExist(workspaceDirUserModuleVersion);
		userInteractionCallbackPlugin.provideInfo("The new static version " + versionStaticNew + " will be created for module version " + moduleVersion + '.');

		try {
			if (indUserWorkspaceDir) {
				pathModuleWorkspace = workspacePlugin.getWorkspaceDir(workspaceDirUserModuleVersion, WorkspacePlugin.GetWorkspaceDirModeEnum.GET_EXISTING, WorkspaceDirAccessMode.READ_WRITE);

				userInteractionCallbackPlugin.provideInfo("Original module version " + moduleVersion + " is already checked out in " + pathModuleWorkspace + ". The actions for creating the new static version " + versionStaticNew + " will be performed in this directory .");

				if (!scmPlugin.isSync(pathModuleWorkspace, ScmPlugin.IsSyncFlagEnum.ALL_CHANGES)) {
					throw new RuntimeExceptionUserError("The directory " + pathModuleWorkspace + " is not synchronized with the SCM. Please synchronize all directories before using this job.");
				}
			} else {
				pathModuleWorkspace = scmPlugin.checkoutSystem(moduleVersion.getVersion());
				CreateStaticVersion.logger.info("Checked out current version " + moduleVersion.getVersion() + " of module " + moduleVersion.getNodePath() + " in " + pathModuleWorkspace + '.');
			}

			userInteractionCallbackPlugin.provideInfo("The new static version " + versionStaticNew + " does not exist and will be created based on the current version " + moduleVersion.getVersion() + '.');

			if (!Util.handleDoYouWantToContinue(Util.DO_YOU_WANT_TO_CONTINUE_CONTEXT_CREATE_STATIC_VERSION)) {
				return false;
			}

			indCommitRequired = false;

			if (!module.isNodePluginExists(ArtifactVersionManagerPlugin.class, null)) {
				CreateStaticVersion.logger.info("The module " + module + " does not expose the ArtifactVersionManagerPlugin plugin which implies that it does not manage artifact version and that there is no need to update it.");
			} else {
				artifactVersionManagerPlugin = module.getNodePlugin(ArtifactVersionManagerPlugin.class, null);

				// If a new static version is to be created, we need to temporarily update the
				// ArtifactVersion to reflect it. After creating the new version we may need to
				// revert back (see below).

				artifactVersionMapperPlugin = module.getNodePlugin(ArtifactVersionMapperPlugin.class, null);
				artifactVersion = artifactVersionMapperPlugin.mapVersionToArtifactVersion(versionStaticNew);

				indCommitRequired = artifactVersionManagerPlugin.setArtifactVersion(pathModuleWorkspace, artifactVersion);

				// If ArtifactVersionManagerPlugin.setArtifactVersion returned true, we need to
				// commit the change. But we want to do so only after we ensured that building the
				// Module is successful.

				if (!indCommitRequired) {
					userInteractionCallbackPlugin.provideInfo("Artifact version " + artifactVersion + " within module " + module + " is already the correct target version in preparation for creating the new static version " + versionStaticNew + ". No change is required.");
				}
			}

			// Before creating the Version, ensure that building the Module is successful.

			if (   !Util.isNotNullAndTrue(runtimePropertiesPlugin.getProperty(module, CreateStaticVersion.RUNTIME_PROPERTY_NO_PRE_CREATE_STATIC_VERSION_VALIDATION_BUILD))
			    && module.isNodePluginExists(BuilderPlugin.class, null)) {

				BuilderPlugin builderPlugin;
				String buildContext;
				boolean indBuildSuccessful;

				builderPlugin = module.getNodePlugin(BuilderPlugin.class,  null);
				buildContext = runtimePropertiesPlugin.getProperty(module, CreateStaticVersion.RUNTIME_PROPERTY_CREATE_STATIC_VERSION_BUILD_CONTEXT);

				try (Writer writerLog = userInteractionCallbackPlugin.provideInfoWithWriter("Initiating the build process for module version " + moduleVersion + " with new artifact version " + artifactVersion + " in workspace directory " + pathModuleWorkspace + " to ensure the building the module is successful before creating the new static version " + versionStaticNew + '.')) {
					indBuildSuccessful = builderPlugin.build(pathModuleWorkspace, buildContext, writerLog);
				} catch (IOException ioe) {
					throw new RuntimeException(ioe);
				}

				if (!indBuildSuccessful) {
					userInteractionCallbackPlugin.provideInfo("The build for module version " + moduleVersion + " with new artifact version " + artifactVersion + " in workspace directory " + pathModuleWorkspace + " failed. Aborting the process for creating new static version " + versionStaticNew + '.');
				}
			}

			if (indCommitRequired) {
				Map<String, String> mapCommitAttr;

				message = "Artifact version within module " + module + " has been updated to " + artifactVersion + " in preparation for creating the new static version " + versionStaticNew + '.';
				mapCommitAttr = new HashMap<String, String>();
				mapCommitAttr.put(ScmPlugin.COMMIT_ATTR_VERSION_CHANGE, "true");
				scmPlugin.commit(pathModuleWorkspace, message, mapCommitAttr);
				userInteractionCallbackPlugin.provideInfo(message);
				this.listActionsPerformed.add(message);

				if (indUserWorkspaceDir) {
					message = "The pevious change was performed in " + pathModuleWorkspace + " and was committed to the SCM.";
					userInteractionCallbackPlugin.provideInfo(message);
					this.listActionsPerformed.add(message);
				} else {
					CreateStaticVersion.logger.info("The pevious change was performed in the system directory " + pathModuleWorkspace + " and was committed to the SCM.");
				}
			}

			// Perform the actual creation of the version.

			scmPlugin.createVersion(pathModuleWorkspace, versionStaticNew, false);
			message = "Static version " + versionStaticNew + " was created for module " + module + " based on current version " + moduleVersion.getVersion() + " without switching to it.";
			userInteractionCallbackPlugin.provideInfo(message);
			this.listActionsPerformed.add(message);

			if (indUserWorkspaceDir) {
				message = "The pevious change was performed in " + pathModuleWorkspace + " and was committed to the SCM.";
				userInteractionCallbackPlugin.provideInfo(message);
				this.listActionsPerformed.add(message);
			} else {
				CreateStaticVersion.logger.info("The pevious change was performed in the system directory " + pathModuleWorkspace + " and was committed to the SCM.");
			}

			if (artifactVersionManagerPlugin != null) {
				AlwaysNeverAskUserResponse alwaysNeverAskUserResponsRevertArtifactVersion;
				boolean indRevertArtifactVersion;
				Map<String, String> mapCommitAttr;

				// Finally handle reverting the ArtifactVersion. Note that is some cases the
				// ArtifactVersion does not need to be updated above if it is already the correct
				// value. This can happen in phase developpement (see
				// NewDynamicVersionPhasePluginFactory). We may be tempted to not attempt to
				// revert the ArtifactVersion in that case arguing that if it was not adjusted
				// prior to creating the new static Version, it will not need to be reverted. But
				// the ArtifactVersionMapperPlugin can behave in a non-deterministic manner where
				// the target ArtifactVersion depends on runtime properties. Again, this can
				// happen in phase development. We therefore handle reverting regardless of
				// whether or not the ArtifactVersion was adjusted above.

				alwaysNeverAskUserResponsRevertArtifactVersion = AlwaysNeverAskUserResponse.valueOfWithAskDefault(runtimePropertiesPlugin.getProperty(module, CreateStaticVersion.RUNTIME_PROPERTY_REVERT_ARTIFACT_VERSION));

				if (!alwaysNeverAskUserResponsRevertArtifactVersion.isNever()) {
					userInteractionCallbackPlugin.provideInfo("Determining the new artifact version of module " + module + " to potentially revert to following the creation of the new static version " + versionStaticNew + '.');

					artifactVersion = artifactVersionMapperPlugin.mapVersionToArtifactVersion(moduleVersion.getVersion());

					if (!artifactVersion.equals(artifactVersionManagerPlugin.getArtifactVersion(pathModuleWorkspace))) {
						// Here we would have liked to reuse the standard idiom related to the
						// AlwaysNeverAskUserResponse type of user input, but the case is a little bit
						// different. It is rather à 4-state input like "Yes always", "Never",
						// "Yes this time, but ask again" and "No this time, but ask again". This is not
						// easily supported by the Util.getInfoAlwaysNeverAskUserResponseAndHandleAsk
						// method.

						alwaysNeverAskUserResponsRevertArtifactVersion = AlwaysNeverAskUserResponse.valueOfWithAskDefault(runtimePropertiesPlugin.getProperty(module, CreateStaticVersion.RUNTIME_PROPERTY_REVERT_ARTIFACT_VERSION));

						if (alwaysNeverAskUserResponsRevertArtifactVersion.isAsk()) {
							yesAlwaysNoUserResponse = Util.getInfoYesNoUserResponse(userInteractionCallbackPlugin, "Do you want to revert the artifact version to " + artifactVersion + " corresponding to the original dynamic version " + moduleVersion.getVersion() + " after creating the static version " + versionStaticNew + " for module " + module + "*", this.indRevertArtifactVersionDefault ? YesAlwaysNoUserResponse.YES : YesAlwaysNoUserResponse.NO);

							indRevertArtifactVersion = yesAlwaysNoUserResponse.isYes();
							this.indRevertArtifactVersionDefault = indRevertArtifactVersion;

							yesAlwaysNoUserResponse = Util.getInfoYesNoUserResponse(userInteractionCallbackPlugin, "Do you want to automatically apply that answer for all subsequent creation of a static version*", YesAlwaysNoUserResponse.YES);

							if (yesAlwaysNoUserResponse.isYes()) {
								if (indRevertArtifactVersion) {
									runtimePropertiesPlugin.setProperty(null, CreateStaticVersion.RUNTIME_PROPERTY_REVERT_ARTIFACT_VERSION, AlwaysNeverAskUserResponse.ALWAYS.toString());
								} else {
									runtimePropertiesPlugin.setProperty(null, CreateStaticVersion.RUNTIME_PROPERTY_REVERT_ARTIFACT_VERSION, AlwaysNeverAskUserResponse.NEVER.toString());
								}
							}
						} else {
							indRevertArtifactVersion = true;
						}


						if (indRevertArtifactVersion) {
							// Here we do not check if the version was actually changed since we made the
							// verification above.
							artifactVersionManagerPlugin.setArtifactVersion(pathModuleWorkspace, artifactVersion);

							// The commit we are about to perform needs to have a special marker so that the
							// dynamic Version is considered equivalent to the static Version that was just
							// created.

							message = "Artifact version within module " + module + " has been reverted to " + artifactVersion + " corresponding to original dynamic version " + moduleVersion.getVersion() + " following the creation of the new static version " + versionStaticNew + '.';
							mapCommitAttr = new HashMap<String, String>();
							mapCommitAttr.put(ScmPlugin.COMMIT_ATTR_EQUIVALENT_STATIC_VERSION, versionStaticNew.toString());
							mapCommitAttr.put(ScmPlugin.COMMIT_ATTR_VERSION_CHANGE, "true");
							scmPlugin.commit(pathModuleWorkspace, message, mapCommitAttr);
							userInteractionCallbackPlugin.provideInfo(message);
							this.listActionsPerformed.add(message);

							if (indUserWorkspaceDir) {
								message = "The pevious change was performed in " + pathModuleWorkspace + " and was committed to the SCM.";
								userInteractionCallbackPlugin.provideInfo(message);
								this.listActionsPerformed.add(message);
							} else {
								CreateStaticVersion.logger.info("The pevious change was performed in the system directory " + pathModuleWorkspace + " and was committed to the SCM.");
							}
						}
					} else {
						userInteractionCallbackPlugin.provideInfo("Artifact version within module " + module + " is already the correct reverted version " + artifactVersion + " corresponding to original dynamic version " + moduleVersion.getVersion() + " before the creation of the new static version " + versionStaticNew + ". No change is required.");
					}
				}
			}
		} finally {
			if (pathModuleWorkspace != null) {
				workspacePlugin.releaseWorkspaceDir(pathModuleWorkspace);
			}
		}

		this.mapModuleVersionStatic.put(moduleVersion, versionStaticNew);
		byReferenceVersion.object = versionStaticNew;

		return true;
	}

	/**
	 * There are multiple occurrences above where we check if a static Version was
	 * already created during the same job execution for a given ModuleVersion. This
	 * method factors this out.
	 *
	 * @param moduleVersion ModuleVersion.
	 * @param byReferenceVersion If a static version was already created for the
	 *   ModuleVersion, the new version is recorded in this object.
	 * @return Indicates if a version was already created for the ModuleVersion.
	 */
	private boolean handleAlreadyCreatedStaticVersion(ModuleVersion moduleVersion, ByReference<Version> byReferenceVersion) {
		Version versionStaticNew;

		versionStaticNew = this.mapModuleVersionStatic.get(moduleVersion);

		if (versionStaticNew != null) {
			byReferenceVersion.object = versionStaticNew;
			return true;
		} else {
			return false;
		}
	}

}
