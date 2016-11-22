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

package org.azyva.dragom.execcontext.plugin.impl;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.text.ParseException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

import org.apache.commons.io.FileUtils;
import org.azyva.dragom.execcontext.ExecContext;
import org.azyva.dragom.execcontext.WorkspaceExecContext;
import org.azyva.dragom.execcontext.plugin.ExecContextPluginFactory;
import org.azyva.dragom.execcontext.plugin.RuntimePropertiesPlugin;
import org.azyva.dragom.execcontext.plugin.ToolLifeCycleExecContextPlugin;
import org.azyva.dragom.execcontext.plugin.WorkspaceDir;
import org.azyva.dragom.execcontext.plugin.WorkspaceDirSystemModule;
import org.azyva.dragom.execcontext.plugin.WorkspaceDirUserModuleVersion;
import org.azyva.dragom.execcontext.plugin.WorkspacePlugin;
import org.azyva.dragom.execcontext.plugin.support.GenericExecContextPluginFactory;
import org.azyva.dragom.model.ModuleVersion;
import org.azyva.dragom.model.NodePath;
import org.azyva.dragom.model.Version;
import org.azyva.dragom.util.RuntimeExceptionUserError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ExecContextPluginFactory} for {@link WorkspacePlugin} that assumes a
 * main {@link ModuleVersion} ({@link WorkspaceDirUserModuleVersion}) already
 * checked out in the root of the {@link ExecContext} workspace directory.
 * <p>
 * Since the root of the ExecContext workspace directory is occupied by the main
 * ModuleVersion and Dragom needs to have a metadata directory, that main
 * MmoduleVersion will have this metadata directory present at the root of its
 * workspace directory. It will not have its own totally independent workspace
 * directory.
 * <p>
 * The other ModuleVersions ({@link WorkspaceDirSystemModuleVersion}) required
 * during Dragom's execution will be within that metadata directory.
 * <p>
 * This is useful in a continuous delivery context where a single ModuleVersion
 * needs to be released, independently of the other ModuleVersion's which may be
 * present in a dependency graph.
 * <p>
 * Typically, a job would be defined in a build automation tool (e.g., Jenkins)
 * that would checkout from the SCM the version of a module and then launch Dragom
 * ReleaseTool configured with this WorkspacePlugin factory.
 * <p>
 * This is generally used in conjunction with
 * {@link ContinuousReleaseSelectStaticVersionPluginImpl} since the job must focus on
 * releasing only that single ModuleVersion and expect to find existing static
 * {@link Version}'s for referenced ModuleVersion's.
 * <p>
 * When the workspace has not been initlized yet, the main ModuleVersion must be
 * specified using the runtime property MAIN_MODULE_VERSION. Once initialized, the
 * ModuleVersion is persisted within the ExecContext and the MAIN_MODULE_VERSION
 * runtime property need not be specified anymore, but if it is, it must refer to
 * the same ModuleVersion.
 * <p>
 * This class cannot be a simple {@link WorkspacePlugin} implementation to be used
 * in conjunction with {@link GenericExecContextPluginFactory} since the creation
 * of an instance may actually require unmarshalling existing workspace data from
 * a file within the {@link ExecContext} workspace.
 * <p>
 * Requires a {@link WorkspaceExecContext}.
 * <p>
 * There are similarities between this class and
 * {@link DefaultWorkspacePluginFactory} and there are probably opportunities for
 * factoring out common code. For now, they are independent with some redundancy.
 *
 * TODO: Assumes dragom metadata dir exists, but may not be initialized based on version.
 * Multiple Versions of the same Module are not supported in user workspace directories.
 * @author David Raymond
 *
 */
public class MainModuleVersionWorkspacePluginFactory implements ExecContextPluginFactory<WorkspacePlugin> {
	private static final Logger logger = LoggerFactory.getLogger(MainModuleVersionWorkspacePluginFactory.class);

	/**
	 * Runtime property specifying the main {@link ModuleVersion} associated with this
	 * workspace.
	 * <p>
	 * This is expected to be specifed as a tool property when the workspace is not
	 * initialized yet. Once initialized, the main ModuleVersion is persisted within
	 * the {@link ExecContext}.
	 */
	private static final String RUNTIME_PROPERTY_MAIN_MODULE_VERSION = "MAIN_MODULE_VERSION";

	/**
	 * {@link ExecContext} property specifying the main {@link ModuleVersion}
	 * associated with this workspace.
	 */
	private static final String EXEC_CONTEXT_PROPERTY_MAIN_MODULE_VERSION = "MAIN_MODULE_VERSION";

	/**
	 * File indicating that the workspace is being used.
	 */
	private static final String WORKSPACE_LOCKED_INDICATOR_FILE = ".lock";

	/**
	 * Format of the workspace data.
	 */
	private static final String WORKSPACE_FORMAT = "main-module-version";

	/**
	 * Version of the workspace data.
	 */
	private static final String WORKSPACE_VERSION = "1.0";

	/**
	 * WorkspacePlugin metadata file.
	 */
	private static final String WORKSPACE_METADATA_FILE = "workspace-metadata.xml";

	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_WORKSPACE_LOCKED = "WORKSPACE_LOCKED";

	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_SYSTEM_WORKSPACE_DIRECTORY_CONFLICT = "SYSTEM_WORKSPACE_DIRECTORY_CONFLICT";

	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_MAIN_MODULE_VERSION_NOT_SPECIFIED = "MAIN_MODULE_VERSION_NOT_SPECIFIED";

	/**
	 * ResourceBundle specific to this class.
	 */
	private static final ResourceBundle resourceBundle = ResourceBundle.getBundle(MainModuleVersionWorkspacePluginFactory.class.getName() + "ResourceBundle");

	/**
	 * WorkspacePlugin implementation.
	 */
	@XmlAccessorType(XmlAccessType.NONE)
	@XmlRootElement(name = "workspace-default-impl")
	private static class DefaultWorkspaceImpl implements WorkspacePlugin, ToolLifeCycleExecContextPlugin {
		/**
		 * Path to the workspace.
		 */
		private Path pathWorkspace;

		/**
		 * Path to the Dragom metadata directory.
		 */
		private Path pathDragomMetadataDir;

		/**
		 * Main ModuleVersion for this workspace.
		 */
		private ModuleVersion moduleVersionMain;

		/**
		 * Map of {@link WorkspaceDir}'s to workspace directory Path's.
		 * <p>
		 * Does not include the main {@link ModuleVersion}.
		 * <p>
		 * What we really need is a bidirectional map. For now, we simply use two regular
		 * Map to avoid having to use a third-party library.
		 * For the actual marshalling/unmarshalling, a XmlAdapter is used so that within
		 * the XML document the workspace directory to path information is represented as
		 * a simple list of tuples, but within the Java class is becomes a Map (2 Map's
		 * actually).
		 */
		@XmlElement(name = "workspace-dirs", type = MapWorkspaceDirPathXmlAdapter.ListWorkspaceDirPath.class)
		@XmlJavaTypeAdapter(MapWorkspaceDirPathXmlAdapter.class)
		private Map<WorkspaceDir, Path> mapWorkspaceDirPath;

		/**
		 * Map of workspace directory Path's to {@link WorkspaceDir}'s.
		 * <p>
		 * Does not include the main {@link ModuleVersion}.
		 * <p>
		 * See comment for mapWorkspaceDirPath.
		 */
		private Map<Path, WorkspaceDir> mapPathWorkspaceDir;

		/**
		 * Map {@link WorkspaceDir} access modes. The entries have the following meanings:
		 * <p>
		 * <li>Key not present: No access</li>
		 * <li>0: Write access</li>
		 * <li>1+: Read access with read count</li>
		 */
		private Map<WorkspaceDir, Integer> mapWorkspaceDirAccessMode;

		/**
		 * Default constructor.
		 * <p>
		 * Use by JAXB when unmarshalling.
		 */
		public DefaultWorkspaceImpl() {
			this.mapWorkspaceDirPath = new HashMap<WorkspaceDir, Path>();
			this.mapPathWorkspaceDir = new HashMap<Path, WorkspaceDir>();
			this.mapWorkspaceDirAccessMode = new HashMap<WorkspaceDir, Integer>();
		}

		/**
		 * Constructor.
		 *
		 * @param pathWorkspace Path to the workspace directory.
		 * @param pathDragomMetadataDir Path to the Dragom metadata directory.
		 * @param moduleVersionMain Main {@link ModuleVersion].
		 */
		public DefaultWorkspaceImpl(Path pathWorkspace, Path pathDragomMetadataDir, ModuleVersion moduleVersionMain) {
			this();
			this.pathWorkspace = pathWorkspace;
			this.pathDragomMetadataDir = pathDragomMetadataDir;
			this.moduleVersionMain = moduleVersionMain;
			this.save();
		}

		/**
		 * Called by JAXB after unmarshalling.
		 *
		 * @param unmarshaller Unmarshaller.
		 * @param parent Parent (not used).
		 */
		@SuppressWarnings("unused")
		private void afterUnmarshal(Unmarshaller unmarshaller, Object parent) {
			for (Map.Entry<WorkspaceDir, Path> mapEntry: this.mapWorkspaceDirPath.entrySet()) {
				if (mapEntry.getKey() instanceof WorkspaceDirUserModuleVersion) {
					throw new RuntimeException("Unexpected WorkspaceDirUserModuleVersion " + mapEntry.getValue() + '.');
				}

				this.mapPathWorkspaceDir.put(mapEntry.getValue(), mapEntry.getKey());
			}
		}

		/**
		 * Saves the workspace data within the {@link ExecContext}.
		 */
		private void save() {
			File fileWorkspaceMetadata;
			JAXBContext jaxbContext;
			Marshaller marshaller;

			try {
				fileWorkspaceMetadata = this.pathDragomMetadataDir.resolve(MainModuleVersionWorkspacePluginFactory.WORKSPACE_METADATA_FILE).toFile();
				jaxbContext = JAXBContext.newInstance(DefaultWorkspaceImpl.class);
				marshaller = jaxbContext.createMarshaller();
				marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);

				// We need to set the MapWorkspaceDirPathXmlAdapter in advance since it requires a
				// non-default constructor.
				marshaller.setAdapter(MapWorkspaceDirPathXmlAdapter.class, new MapWorkspaceDirPathXmlAdapter(this.pathWorkspace));

				marshaller.marshal(this, fileWorkspaceMetadata);
			} catch (JAXBException je) {
				throw new RuntimeException(je);
			}
		}

		@Override
		public Path getPathWorkspace() {
			return this.pathWorkspace;
		}

		@Override
		public boolean isSupportMultipleModuleVersion() {
			return false;
		}

		@Override
		public WorkspaceDir getWorkspaceDirConflict(WorkspaceDir workspaceDir) {
			if (workspaceDir instanceof WorkspaceDirUserModuleVersion) {
				if (((WorkspaceDirUserModuleVersion)workspaceDir).getModuleVersion().equals(this.moduleVersionMain)) {
					return null;
				} else {
					return new WorkspaceDirUserModuleVersion(this.moduleVersionMain);
				}
			} else {
				Path pathWorkspaceDir;
				WorkspaceDir workspaceDirOther;

				pathWorkspaceDir = this.pathDragomMetadataDir.resolve(((WorkspaceDirSystemModule)workspaceDir).getNodePath().getModuleName());

				workspaceDirOther = this.mapPathWorkspaceDir.get(pathWorkspaceDir);

				if ((workspaceDirOther != null) && !workspaceDirOther.equals(workspaceDir)) {
					return workspaceDirOther;
				} else {
					return null;
				}
			}
		}

		@Override
		public boolean isWorkspaceDirExist(WorkspaceDir workspaceDir) {
			if (workspaceDir instanceof WorkspaceDirUserModuleVersion) {
				return ((WorkspaceDirUserModuleVersion)workspaceDir).getModuleVersion().equals(this.moduleVersionMain);
			} else {
				return this.mapWorkspaceDirPath.containsKey(workspaceDir);
			}
		}

		@Override
		public Path getWorkspaceDir(WorkspaceDir workspaceDir, EnumSet<GetWorkspaceDirMode> enumSetGetWorkspaceDirMode, WorkspaceDirAccessMode workspaceDirAccessMode) {
			Integer readCount;
			Path path;

			if (workspaceDir instanceof WorkspaceDirUserModuleVersion) {
				if (!((WorkspaceDirUserModuleVersion)workspaceDir).getModuleVersion().equals(this.moduleVersionMain)) {
					throw new RuntimeException("Request for WorkspaceDirUserModuleVersion " + workspaceDir + " which does not correspond to main ModuleVersion of workspace " + this.moduleVersionMain + '.');
				}
			}

			if (workspaceDirAccessMode != WorkspaceDirAccessMode.PEEK) {
				readCount = this.mapWorkspaceDirAccessMode.get(workspaceDir);

				if (readCount != null) {
					if (readCount == 0) {
						throw new RuntimeException("Workspace directory " + workspaceDir + " already accessed for writing (and new acces is " + workspaceDirAccessMode + ").");
					} else if (workspaceDirAccessMode == WorkspaceDirAccessMode.READ_WRITE) {
						throw new RuntimeException("New access is for writing and workspace directory " + workspaceDir + " already accessed for reading (with level " + readCount + ").");
					}

					this.mapWorkspaceDirAccessMode.put(workspaceDir, readCount + 1);
				} else {
					if (workspaceDirAccessMode == WorkspaceDirAccessMode.READ){
						this.mapWorkspaceDirAccessMode.put(workspaceDir, 1);
					} else {
						this.mapWorkspaceDirAccessMode.put(workspaceDir, 0);
					}
				}
			}

			path = this.mapWorkspaceDirPath.get(workspaceDir);

			if ((path == null) && enumSetGetWorkspaceDirMode.contains(GetWorkspaceDirMode.MUST_EXIST)) {
				throw new RuntimeException("WorkspacePlugin directory " + workspaceDir + " does not exist and is assumed to exist.");
			}

			if ((path != null) && enumSetGetWorkspaceDirMode.contains(GetWorkspaceDirMode.MUST_NOT_EXIST)) {
				throw new RuntimeException("WorkspacePlugin directory " + workspaceDir + " exists and mapped to " + path + " but is assumed to not exist.");
			}

			if (workspaceDir instanceof WorkspaceDirUserModuleVersion) {
				return this.pathWorkspace;
			} else if (workspaceDir instanceof WorkspaceDirSystemModule) {
				path = this.getWorkspaceDirSystemModule((WorkspaceDirSystemModule)workspaceDir, enumSetGetWorkspaceDirMode);

				if ((path != null) && !path.toFile().isDirectory() && !enumSetGetWorkspaceDirMode.contains(GetWorkspaceDirMode.DO_NOT_CREATE_PATH)) {
					if (!path.toFile().mkdir()) {
						throw new RuntimeException("The path " + path + " could not be created for an unknown reason.");
					}
				}

				return path;
			} else {
				throw new RuntimeException("Unknown WorkspaceDir class " + workspaceDir.getClass().getName() + '.');
			}
		}

		/**
		 * Returns the Path corresponding to a WorkspaceDirSystemModule.
		 *
		 * @param workspaceDirSystemModule WorkspaceDirSystemModule.
		 * @param enumSetGetWorkspaceDirMode EnumSet of {@link GetWorkspaceDirMode}.
		 * @return Path
		 */
		private Path getWorkspaceDirSystemModule(WorkspaceDirSystemModule workspaceDirSystemModule, EnumSet<GetWorkspaceDirMode> enumSetGetWorkspaceDirMode) {
			Path path;

			path = this.mapWorkspaceDirPath.get(workspaceDirSystemModule);

			if (path == null && enumSetGetWorkspaceDirMode.contains(GetWorkspaceDirMode.CREATE_IF_NOT_EXIST)) {
				WorkspaceDir workspaceDirOther;

				path = this.pathDragomMetadataDir.resolve(workspaceDirSystemModule.getNodePath().getModuleName());

				workspaceDirOther = this.mapPathWorkspaceDir.get(path);

				if (workspaceDirOther != null) {
					throw new RuntimeExceptionUserError(MessageFormat.format(MainModuleVersionWorkspacePluginFactory.resourceBundle.getString(MainModuleVersionWorkspacePluginFactory.MSG_PATTERN_KEY_SYSTEM_WORKSPACE_DIRECTORY_CONFLICT), workspaceDirSystemModule, path, workspaceDirOther));
				}

				MainModuleVersionWorkspacePluginFactory.logger.info("Path " + path + " is created for " + workspaceDirSystemModule + '.');

				if (path.toFile().isDirectory())  {
					throw new RuntimeException("The path " + path + " for workspace directory for " + workspaceDirSystemModule + " already exists but is unknown to the workspace.");
				}

				this.mapWorkspaceDirPath.put(workspaceDirSystemModule, path);
				this.mapPathWorkspaceDir.put(path, workspaceDirSystemModule);

				this.save();
			} else if (path != null && enumSetGetWorkspaceDirMode.contains(GetWorkspaceDirMode.RESET_IF_EXIST)) {
				MainModuleVersionWorkspacePluginFactory.logger.info("Existing path " + path + " is reset (deleted and recreated empty) for " + workspaceDirSystemModule + '.');

				try {
					FileUtils.deleteDirectory(path.toFile());
				} catch (IOException ioe) {
					throw new RuntimeException("IOException raised while trying to delete the workspace directory " + path + ": " + ioe);
				}

			}

			return path;
		}

		@Override
		public void releaseWorkspaceDir(Path pathWorkspaceDir) {
			WorkspaceDir workspaceDir;
			Integer readCount;

			if (pathWorkspaceDir.equals(this.pathWorkspace)) {
				workspaceDir = new WorkspaceDirUserModuleVersion(this.moduleVersionMain);
			} else {
				workspaceDir = this.mapPathWorkspaceDir.get(pathWorkspaceDir);
			}

			if (workspaceDir == null) {
				throw new RuntimeException("The path " + pathWorkspaceDir + " does not correspond to a workspace directory.");
			}

			readCount = this.mapWorkspaceDirAccessMode.get(workspaceDir);

			if (readCount == null) {
				throw new RuntimeException("Workspace directory " + workspaceDir + " is not accessed.");
			}

			if ((readCount == 0) || (readCount == 1)) {
				this.mapWorkspaceDirAccessMode.remove(workspaceDir);
			} else {
				this.mapWorkspaceDirAccessMode.put(workspaceDir,  readCount - 1);
			}
		}

		@Override
		public WorkspaceDirAccessMode getWorkspaceDirAccessMode(Path pathWorkspaceDir) {
			WorkspaceDir workspaceDir;
			Integer readCount;

			if (pathWorkspaceDir.equals(this.pathWorkspace)) {
				workspaceDir = new WorkspaceDirUserModuleVersion(this.moduleVersionMain);
			} else {
				workspaceDir = this.mapPathWorkspaceDir.get(pathWorkspaceDir);
			}

			if (workspaceDir == null) {
				throw new RuntimeException("The path " + pathWorkspaceDir + " does not correspond to a workspace directory.");
			}

			readCount = this.mapWorkspaceDirAccessMode.get(workspaceDir);

			if (readCount == null) {
				return WorkspaceDirAccessMode.PEEK;
			} else if (readCount >= 1) {
				return WorkspaceDirAccessMode.READ;
			} else {
				return WorkspaceDirAccessMode.READ_WRITE;
			}
		}

		@Override
		public void updateWorkspaceDir(WorkspaceDir workspaceDir, WorkspaceDir workspaceDirNew) {
			Integer readCount;
			Path path;

			if (workspaceDir instanceof WorkspaceDirUserModuleVersion) {
				throw new RuntimeException("Request for updating WorkspaceDirUserModuleVersion " + workspaceDir + " but only one WorkspaceDirUserModuleVersion is allowed and it cannot be updated.");
			}

			readCount = this.mapWorkspaceDirAccessMode.get(workspaceDir);

			if ((readCount == null) || (readCount != 0)) {
				throw new RuntimeException("Workspace directory " + workspaceDir + " must be accessed for writing to update it.");
			}

			path = this.mapWorkspaceDirPath.get(workspaceDir);

			if (path == null) {
				throw new RuntimeException("No entry exists for workspace directory " + workspaceDir + '.');
			}

			if (workspaceDir instanceof WorkspaceDirSystemModule) {
				if (!(workspaceDirNew instanceof WorkspaceDirSystemModule)) {
					throw new RuntimeException("New workspace directory " + workspaceDir + " must be of the same type as original one " + workspaceDirNew + '.');
				}

				if (!((WorkspaceDirSystemModule)workspaceDir).getNodePath().equals(((WorkspaceDirSystemModule)workspaceDirNew).getNodePath())) {
					throw new RuntimeException("New workspace directory " + workspaceDirNew + " must refer to the same module node path as original workspace directory " + workspaceDir + '.');
				}

				// For a WorkspaceDirSystemModule there is in fact nothing to do since it is not
				// specific to a Version and we do not allow changing the Module.
			} else {
				throw new RuntimeException("Invalid workspace directory type " + workspaceDir + '.');
			}
		}

		@Override
		public void deleteWorkspaceDir(WorkspaceDir workspaceDir) {
			Integer readCount;
			Path path;

			if (workspaceDir instanceof WorkspaceDirUserModuleVersion) {
				throw new RuntimeException("Request for deleting WorkspaceDirUserModuleVersion " + workspaceDir + " but only one WorkspaceDirUserModuleVersion is allowed and it cannot be deleted.");
			}

			readCount = this.mapWorkspaceDirAccessMode.get(workspaceDir);

			if ((readCount == null) || (readCount != 0)) {
				throw new RuntimeException("Workspace directory " + workspaceDir + " must be accessed for writing to delete it.");
			}

			path = this.mapWorkspaceDirPath.get(workspaceDir);

			if (path != null) {
				try {
					FileUtils.deleteDirectory(path.toFile());
				} catch (IOException ioe) {
					throw new RuntimeException("IOException raised while trying to delete the workspace directory " + path + ": " + ioe);
				}

				this.mapWorkspaceDirPath.remove(workspaceDir);
				this.mapPathWorkspaceDir.remove(path);

				this.save();

				this.mapWorkspaceDirAccessMode.put(workspaceDir, null);
			}
		}

		@Override
		public Set<WorkspaceDir> getSetWorkspaceDir(Class<? extends WorkspaceDir> workspaceDirClass) {
			Set<WorkspaceDir> setWorkspaceDir;

			if (workspaceDirClass == WorkspaceDirUserModuleVersion.class) {
				setWorkspaceDir = new HashSet<WorkspaceDir>();
				setWorkspaceDir.add(new WorkspaceDirUserModuleVersion(this.moduleVersionMain));
				return setWorkspaceDir;
			} else {
				setWorkspaceDir = new HashSet<WorkspaceDir>(this.mapWorkspaceDirPath.keySet());

				if (workspaceDirClass == null) {
					setWorkspaceDir.add(new WorkspaceDirUserModuleVersion(this.moduleVersionMain));
				}

				return setWorkspaceDir;
			}
		}

		@Override
		public Set<WorkspaceDir> getSetWorkspaceDir(WorkspaceDir workspaceDirIncomplete) {
			Set<WorkspaceDir> setWorkspaceDir;

			if ((workspaceDirIncomplete != null) && workspaceDirIncomplete instanceof WorkspaceDirUserModuleVersion) {
				WorkspaceDirUserModuleVersion workspaceDirUserModuleVersionIncomplete;
				NodePath nodePath;
				Version version;

				workspaceDirUserModuleVersionIncomplete = (WorkspaceDirUserModuleVersion)workspaceDirIncomplete;
				nodePath = workspaceDirUserModuleVersionIncomplete.getModuleVersion().getNodePath();
				version = workspaceDirUserModuleVersionIncomplete.getModuleVersion().getVersion();

				if (   ((nodePath != null) && !nodePath.equals(this.moduleVersionMain.getNodePath()))
				    || (version != null) && !version.equals(this.moduleVersionMain.getVersion())) {

					return Collections.<WorkspaceDir>emptySet();
				} else {
					setWorkspaceDir = new HashSet<WorkspaceDir>();
					setWorkspaceDir.add(new WorkspaceDirUserModuleVersion(this.moduleVersionMain));
					return setWorkspaceDir;
				}
			} else {
				setWorkspaceDir = new HashSet<WorkspaceDir>(this.mapWorkspaceDirPath.keySet());

				if (workspaceDirIncomplete == null) {
					setWorkspaceDir.add(new WorkspaceDirUserModuleVersion(this.moduleVersionMain));
				} else {
					WorkspaceDirSystemModule workspaceDirSystemModuleIncomplete;
					Iterator<WorkspaceDir> iteratorWorkspaceDir;

					workspaceDirSystemModuleIncomplete = (WorkspaceDirSystemModule)workspaceDirIncomplete;
					iteratorWorkspaceDir = setWorkspaceDir.iterator();

					while (iteratorWorkspaceDir.hasNext()) {
						WorkspaceDirSystemModule workspaceDirSystemModule;

						workspaceDirSystemModule = (WorkspaceDirSystemModule)iteratorWorkspaceDir.next();

						if (   (workspaceDirSystemModuleIncomplete.getNodePath() != null)
							&& !workspaceDirSystemModuleIncomplete.getNodePath().equals(workspaceDirSystemModule.getNodePath())) {

								iteratorWorkspaceDir.remove();
							}
						}
					}

				return setWorkspaceDir;
			}
		}

		@Override
		public boolean isPathWorkspaceDirExists(Path pathWorkspaceDir) {
			return pathWorkspaceDir.equals(this.pathWorkspace) || this.mapPathWorkspaceDir.containsKey(pathWorkspaceDir);
		}

		@Override
		public WorkspaceDir getWorkspaceDirFromPath(Path pathWorkspaceDir) {
			if (pathWorkspaceDir.equals(this.pathWorkspace)) {
				return new WorkspaceDirUserModuleVersion(this.moduleVersionMain);
			} else {
				if (!this.mapPathWorkspaceDir.containsKey(pathWorkspaceDir)) {
					throw new RuntimeException("No workspace directory corresponds to the path " + pathWorkspaceDir + '.');
				}

				return this.mapPathWorkspaceDir.get(pathWorkspaceDir);
			}
		}

		@Override
		public boolean isTransient() {
			return false;
		}

		@Override
		public void startTool() {
			File workspaceLockedIndicatorFile;

			workspaceLockedIndicatorFile = this.pathDragomMetadataDir.resolve(MainModuleVersionWorkspacePluginFactory.WORKSPACE_LOCKED_INDICATOR_FILE).toFile();

			try {
				if (!workspaceLockedIndicatorFile.createNewFile()) {
					throw new RuntimeExceptionUserError(MessageFormat.format(MainModuleVersionWorkspacePluginFactory.resourceBundle.getString(MainModuleVersionWorkspacePluginFactory.MSG_PATTERN_KEY_WORKSPACE_LOCKED), this.pathWorkspace, workspaceLockedIndicatorFile));
				}
			} catch (IOException ioe) {
				throw new RuntimeException(ioe);
			}
		}

		@Override
		public void endTool() {
			File workspaceLockedIndicatorFile;

			workspaceLockedIndicatorFile = this.pathDragomMetadataDir.resolve(MainModuleVersionWorkspacePluginFactory.WORKSPACE_LOCKED_INDICATOR_FILE).toFile();

			workspaceLockedIndicatorFile.delete();
		}
	}

	/**
	 * @return WorkspaePlugin.
	 */
	@Override
	public WorkspacePlugin getExecContextPlugin(ExecContext execContext) {
		RuntimePropertiesPlugin runtimePropertiesPlugin;
		WorkspaceExecContext workspaceExecContext;
		Path pathWorkspace;
		Path pathDragomMetadataDir;
		WorkspaceExecContext.WorkspaceFormatVersion workspaceFormatVersion;
		String stringMainModuleVersion;
		ModuleVersion moduleVersionMain = null;
		boolean indWorkspaceInit;
		DefaultWorkspaceImpl defaultWorkspaceImpl;

		if (!(execContext instanceof WorkspaceExecContext)) {
			throw new RuntimeException("An execution context supporting the concept of workspace directory is required.");
		}

		runtimePropertiesPlugin = execContext.getExecContextPlugin(RuntimePropertiesPlugin.class);

		stringMainModuleVersion = runtimePropertiesPlugin.getProperty(null, MainModuleVersionWorkspacePluginFactory.RUNTIME_PROPERTY_MAIN_MODULE_VERSION);

		if (stringMainModuleVersion != null) {
			try {
				moduleVersionMain = ModuleVersion.parse(stringMainModuleVersion);
			} catch (ParseException pe) {
				throw new RuntimeExceptionUserError(pe.getMessage());
			}
		}

		workspaceExecContext = (WorkspaceExecContext)execContext;

		pathWorkspace = workspaceExecContext.getPathWorkspaceDir();
		pathDragomMetadataDir = workspaceExecContext.getPathMetadataDir();

		workspaceFormatVersion = workspaceExecContext.getWorkspaceFormatVersion();

		if (workspaceFormatVersion == null) {
			if (stringMainModuleVersion == null) {
				throw new RuntimeExceptionUserError(MessageFormat.format(MainModuleVersionWorkspacePluginFactory.resourceBundle.getString(MainModuleVersionWorkspacePluginFactory.MSG_PATTERN_KEY_MAIN_MODULE_VERSION_NOT_SPECIFIED), pathWorkspace));
			}

			workspaceExecContext.setWorkspaceFormatVersion(new WorkspaceExecContext.WorkspaceFormatVersion(MainModuleVersionWorkspacePluginFactory.WORKSPACE_FORMAT, MainModuleVersionWorkspacePluginFactory.WORKSPACE_VERSION));
			indWorkspaceInit = false;
		} else {
			if (!workspaceFormatVersion.format.equals(MainModuleVersionWorkspacePluginFactory.WORKSPACE_FORMAT) || !workspaceFormatVersion.version.equals(MainModuleVersionWorkspacePluginFactory.WORKSPACE_VERSION)) {
				throw new RuntimeException("Unsupported workspace format version " + workspaceFormatVersion + ". + Only format version " + new WorkspaceExecContext.WorkspaceFormatVersion(MainModuleVersionWorkspacePluginFactory.WORKSPACE_FORMAT, MainModuleVersionWorkspacePluginFactory.WORKSPACE_VERSION) + " is supported by this WorkspacePlugin factory.");
			}

			stringMainModuleVersion = execContext.getProperty(MainModuleVersionWorkspacePluginFactory.EXEC_CONTEXT_PROPERTY_MAIN_MODULE_VERSION);

			if (moduleVersionMain != null) {
				if (!moduleVersionMain.equals(new ModuleVersion(stringMainModuleVersion))) {
					throw new RuntimeException("Main ModuleVersion specified as a runtime property " + moduleVersionMain + " differs from main ModuleVersion persisted within ExecContext " + stringMainModuleVersion + '.');
				}
			} else {
				moduleVersionMain = new ModuleVersion(stringMainModuleVersion);
			}

			indWorkspaceInit = true;
		}

		defaultWorkspaceImpl = null;

		if (indWorkspaceInit) {
			File fileWorkspaceMetadata;
			JAXBContext jaxbContext;
			Unmarshaller unmarshaller;

			try {
				fileWorkspaceMetadata = pathDragomMetadataDir.resolve(MainModuleVersionWorkspacePluginFactory.WORKSPACE_METADATA_FILE).toFile();

				// Normally if we get here (the version ExecContext property exists and therefore
				// the workspace has previously been initialized) the workspace-metadata.xml file
				// should exist. But if it does not, simply fall through and initialize an empty
				// workspace.
				if (fileWorkspaceMetadata.exists()) {
					jaxbContext = JAXBContext.newInstance(DefaultWorkspaceImpl.class);
					unmarshaller = jaxbContext.createUnmarshaller();

					// We need to set the MapWorkspaceDirPathXmlAdapter in advance since it requires a
					// non-default constructor.
					unmarshaller.setAdapter(MapWorkspaceDirPathXmlAdapter.class, new MapWorkspaceDirPathXmlAdapter(pathWorkspace));

					defaultWorkspaceImpl = (DefaultWorkspaceImpl)unmarshaller.unmarshal(fileWorkspaceMetadata);

					defaultWorkspaceImpl.pathWorkspace = pathWorkspace;
					defaultWorkspaceImpl.pathDragomMetadataDir = pathDragomMetadataDir;
					defaultWorkspaceImpl.moduleVersionMain = moduleVersionMain;
				}
			} catch (JAXBException e) {
				throw new RuntimeException(e);
			}
		} else {
			defaultWorkspaceImpl = new DefaultWorkspaceImpl(pathWorkspace, pathDragomMetadataDir, moduleVersionMain);
		}

		return defaultWorkspaceImpl;
	}
}
