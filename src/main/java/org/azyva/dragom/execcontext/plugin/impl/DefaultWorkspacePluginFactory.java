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

package org.azyva.dragom.execcontext.plugin.impl;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.text.MessageFormat;
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
import org.azyva.dragom.execcontext.plugin.ToolLifeCycleExecContextPlugin;
import org.azyva.dragom.execcontext.plugin.WorkspaceDir;
import org.azyva.dragom.execcontext.plugin.WorkspaceDirSystemModule;
import org.azyva.dragom.execcontext.plugin.WorkspaceDirUserModuleVersion;
import org.azyva.dragom.execcontext.plugin.WorkspacePlugin;
import org.azyva.dragom.util.RuntimeExceptionUserError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TODO: Assumes dragom metadata dir exists, but may not be initialized based on version.
 * Multiple Versions of the same Module are not supported in user workspace directories.
 * @author David Raymond
 *
 */
public class DefaultWorkspacePluginFactory implements ExecContextPluginFactory<WorkspacePlugin> {
	private static final Logger logger = LoggerFactory.getLogger(DefaultWorkspacePluginFactory.class);

	/**
	 * File indicating that the workspace is being used.
	 */
	private static final String WORKSPACE_LOCKED_INDICATOR_FILE = ".lock";

	/**
	 * Version of the workspace data.
	 */
	private static final String WORKSPACE_VERSION_PROPERTY = "workspace-version";

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
	private static final String MSG_PATTERN_KEY_USER_WORKSPACE_DIRECTORY_CONFLICT = "USER_WORKSPACE_DIRECTORY_CONFLICT";

	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_SYSTEM_WORKSPACE_DIRECTORY_CONFLICT = "SYSTEM_WORKSPACE_DIRECTORY_CONFLICT";


	/**
	 * ResourceBundle specific to this class.
	 */
	private static final ResourceBundle resourceBundle = ResourceBundle.getBundle(DefaultWorkspacePluginFactory.class.getName() + "ResourceBundle");

	/**
	 * Default WorkspacePlugin implementation.
	 * Simple workspace implementation.
	 *
	 * TODO: Reword. Not true anymore...
	 * This implementation does not keep track of the modules created within the
	 * workspace and simpl assumes the module path is a directory whose name is the
	 * module name within the workspace directory, regardless of the version.
	 */
	@XmlAccessorType(XmlAccessType.NONE)
	@XmlRootElement(name = "workspace-default-impl")
	private static class DefaultWorkspaceImpl implements WorkspacePlugin, ToolLifeCycleExecContextPlugin {
		Path pathWorkspace;

		Path pathDragomMetadataDir;

		/* We really need a bidirectional map. For now, simply use two regular maps to
		 * avoid having to use a third party library.
		 *
		 * We map only one bidirectional map side and use the afterUnmarshal JAXB
		 * callback to complete the other side.
		 *
		 * For the actual marshalling/unmarshalling, a XmlAdapter is used so that within
		 * the XML document the workspace directory to path information is represented as
		 * a simple list of tuples, but within the Java class is becomes a Map.
		 */
		@XmlElement(name = "workspace-dirs", type = MapWorkspaceDirPathXmlAdapter.ListWorkspaceDirPath.class)
		@XmlJavaTypeAdapter(MapWorkspaceDirPathXmlAdapter.class)
		Map<WorkspaceDir, Path> mapWorkspaceDirPath;

		Map<Path, WorkspaceDir> mapPathWorkspaceDir;

		// Key not present means no access. 0 means write. 1+ means read with read count.
		Map<WorkspaceDir, Integer> mapWorkspaceDirAccessMode;

		// Constructor used by JAXB, when init.
		public DefaultWorkspaceImpl() {
			this.mapWorkspaceDirPath = new HashMap<WorkspaceDir, Path>();
			this.mapPathWorkspaceDir = new HashMap<Path, WorkspaceDir>();
			this.mapWorkspaceDirAccessMode = new HashMap<WorkspaceDir, Integer>();
		}

		public DefaultWorkspaceImpl(Path pathWorkspace, Path pathDragomMetadataDir) {
			this();
			this.pathWorkspace = pathWorkspace;
			this.pathDragomMetadataDir = pathDragomMetadataDir;
			this.save();
		}

		@SuppressWarnings("unused")
		private void afterUnmarshal(Unmarshaller unmarshaller, Object parent) {
			for (Map.Entry<WorkspaceDir, Path> mapEntry: this.mapWorkspaceDirPath.entrySet()) {
				this.mapPathWorkspaceDir.put(mapEntry.getValue(), mapEntry.getKey());
			}
		}

		private void save() {
			File fileWorkspaceMetadata;
			JAXBContext jaxbContext;
			Marshaller marshaller;

			try {
				fileWorkspaceMetadata = this.pathDragomMetadataDir.resolve(DefaultWorkspacePluginFactory.WORKSPACE_METADATA_FILE).toFile();
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
			Path pathWorkspaceDir;
			WorkspaceDir workspaceDirOther;

			if (workspaceDir instanceof WorkspaceDirUserModuleVersion) {
				pathWorkspaceDir = this.pathWorkspace.resolve(((WorkspaceDirUserModuleVersion)workspaceDir).getModuleVersion().getNodePath().getModuleName());
			} else {
				pathWorkspaceDir = this.pathDragomMetadataDir.resolve(((WorkspaceDirSystemModule)workspaceDir).getNodePath().getModuleName());
			}

			workspaceDirOther = this.mapPathWorkspaceDir.get(pathWorkspaceDir);

			if ((workspaceDirOther != null) && !workspaceDirOther.equals(workspaceDir)) {
				return workspaceDirOther;
			} else {
				return null;
			}
		}

		@Override
		public boolean isWorkspaceDirExist(WorkspaceDir workspaceDir) {
			return this.mapWorkspaceDirPath.containsKey(workspaceDir);
		}

		//TODO if workspace dir cannot be created for whatever reason, exception, even if it is because of conflit.
		//Fow now. Eventually, maybe the caller would be interested in knowing if fail because of conflict behave
		//gracefully in that case. But for now, make it simple.
		@Override
		public Path getWorkspaceDir(WorkspaceDir workspaceDir, EnumSet<GetWorkspaceDirMode> enumSetGetWorkspaceDirMode, WorkspaceDirAccessMode workspaceDirAccessMode) {
			Integer readCount;
			Path path;

			/* Perform basic generic validation that is common to all workspace directory
			 * types.
			 */

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

			/* Get the workspace directory.
			 */

			if (workspaceDir instanceof WorkspaceDirUserModuleVersion) {
				path = this.getWorkspaceDirUserModuleVersion((WorkspaceDirUserModuleVersion)workspaceDir, enumSetGetWorkspaceDirMode);
			} else if (workspaceDir instanceof WorkspaceDirSystemModule) {
				path = this.getWorkspaceDirSystemModule((WorkspaceDirSystemModule)workspaceDir, enumSetGetWorkspaceDirMode);
			} else {
				throw new RuntimeException("Unknown WorkspaceDir class " + workspaceDir.getClass().getName() + '.');
			}

			/* Generically ensure the path is created if the caller requests it.
			 */

			if ((path != null) && !path.toFile().isDirectory() && !enumSetGetWorkspaceDirMode.contains(GetWorkspaceDirMode.DO_NOT_CREATE_PATH)) {
				if (!path.toFile().mkdir()) {
					throw new RuntimeException("The path " + path + " could not be created for an unknown reason.");
				}
			}

			return path;
		}

		private Path getWorkspaceDirUserModuleVersion(WorkspaceDirUserModuleVersion workspaceDirUserModuleVersion, EnumSet<GetWorkspaceDirMode> enumSetGetWorkspaceDirMode) {
			Path path;

			path = this.mapWorkspaceDirPath.get(workspaceDirUserModuleVersion);

			if (path == null && enumSetGetWorkspaceDirMode.contains(GetWorkspaceDirMode.CREATE_IF_NOT_EXIST)) {
				WorkspaceDir workspaceDirOther;

				path = this.pathWorkspace.resolve(workspaceDirUserModuleVersion.getModuleVersion().getNodePath().getModuleName());

				workspaceDirOther = this.mapPathWorkspaceDir.get(path);

				if (workspaceDirOther != null) {
					throw new RuntimeExceptionUserError(MessageFormat.format(DefaultWorkspacePluginFactory.resourceBundle.getString(DefaultWorkspacePluginFactory.MSG_PATTERN_KEY_USER_WORKSPACE_DIRECTORY_CONFLICT), workspaceDirUserModuleVersion, path, workspaceDirOther));
				}

				DefaultWorkspacePluginFactory.logger.info("Path " + path + " is created for " + workspaceDirUserModuleVersion + '.');

				if (path.toFile().isDirectory())  {
					throw new RuntimeException("The path " + path + " for workspace directory for " + workspaceDirUserModuleVersion + " already exists but is unknown to the workspace.");
				}

				this.mapWorkspaceDirPath.put(workspaceDirUserModuleVersion, path);
				this.mapPathWorkspaceDir.put(path, workspaceDirUserModuleVersion);

				this.save();
			} else if (path != null && enumSetGetWorkspaceDirMode.contains(GetWorkspaceDirMode.RESET_IF_EXIST)) {
				DefaultWorkspacePluginFactory.logger.info("Existing path " + path + " is reset (deleted and recreated empty) for " + workspaceDirUserModuleVersion + '.');

				try {
					FileUtils.deleteDirectory(path.toFile());
				} catch (IOException ioe) {
					throw new RuntimeException("IOException raised while trying to delete the workspace directory " + path + ": " + ioe);
				}

			}

			return path;
		}

		private Path getWorkspaceDirSystemModule(WorkspaceDirSystemModule workspaceDirSystemModule, EnumSet<GetWorkspaceDirMode> enumSetGetWorkspaceDirMode) {
			Path path;

			path = this.mapWorkspaceDirPath.get(workspaceDirSystemModule);

			if (path == null && enumSetGetWorkspaceDirMode.contains(GetWorkspaceDirMode.CREATE_IF_NOT_EXIST)) {
				WorkspaceDir workspaceDirOther;

				path = this.pathDragomMetadataDir.resolve(workspaceDirSystemModule.getNodePath().getModuleName());

				workspaceDirOther = this.mapPathWorkspaceDir.get(path);

				if (workspaceDirOther != null) {
					throw new RuntimeExceptionUserError(MessageFormat.format(DefaultWorkspacePluginFactory.resourceBundle.getString(DefaultWorkspacePluginFactory.MSG_PATTERN_KEY_SYSTEM_WORKSPACE_DIRECTORY_CONFLICT), workspaceDirSystemModule, path, workspaceDirOther));
				}

				DefaultWorkspacePluginFactory.logger.info("Path " + path + " is created for " + workspaceDirSystemModule + '.');

				if (path.toFile().isDirectory())  {
					throw new RuntimeException("The path " + path + " for workspace directory for " + workspaceDirSystemModule + " already exists but is unknown to the workspace.");
				}

				this.mapWorkspaceDirPath.put(workspaceDirSystemModule, path);
				this.mapPathWorkspaceDir.put(path, workspaceDirSystemModule);

				this.save();
			} else if (path != null && enumSetGetWorkspaceDirMode.contains(GetWorkspaceDirMode.RESET_IF_EXIST)) {
				DefaultWorkspacePluginFactory.logger.info("Existing path " + path + " is reset (deleted and recreated empty) for " + workspaceDirSystemModule + '.');

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

			workspaceDir = this.mapPathWorkspaceDir.get(pathWorkspaceDir);

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

			workspaceDir = this.mapPathWorkspaceDir.get(pathWorkspaceDir);

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

			readCount = this.mapWorkspaceDirAccessMode.get(workspaceDir);

			if ((readCount == null) || (readCount != 0)) {
				throw new RuntimeException("Workspace directory " + workspaceDir + " must be accessed for writing to update it.");
			}

			path = this.mapWorkspaceDirPath.get(workspaceDir);

			if (path == null) {
				throw new RuntimeException("No entry exists for workspace directory " + workspaceDir + '.');
			}

			if (workspaceDir instanceof WorkspaceDirUserModuleVersion) {
				if (!(workspaceDirNew instanceof WorkspaceDirUserModuleVersion)) {
					throw new RuntimeException("New workspace directory " + workspaceDir + " must be of the same type as original one " + workspaceDirNew + '.');
				}

				if (!((WorkspaceDirUserModuleVersion)workspaceDir).getModuleVersion().getNodePath().equals(((WorkspaceDirUserModuleVersion)workspaceDirNew).getModuleVersion().getNodePath())) {
					throw new RuntimeException("New workspace directory " + workspaceDirNew + " must refer to the same module node path as original workspace directory " + workspaceDir + '.');
				}
			} else if (workspaceDir instanceof WorkspaceDirSystemModule) {
				if (!(workspaceDirNew instanceof WorkspaceDirSystemModule)) {
					throw new RuntimeException("New workspace directory " + workspaceDir + " must be of the same type as original one " + workspaceDirNew + '.');
				}

				if (!((WorkspaceDirSystemModule)workspaceDir).getNodePath().equals(((WorkspaceDirSystemModule)workspaceDirNew).getNodePath())) {
					throw new RuntimeException("New workspace directory " + workspaceDirNew + " must refer to the same module node path as original workspace directory " + workspaceDir + '.');
				}
			} else {
				throw new RuntimeException("Invalid workspace directory type " + workspaceDir + '.');
			}

			if (this.mapWorkspaceDirPath.get(workspaceDirNew) != null) {
				throw new RuntimeException("New workspace directory " + workspaceDirNew + " must not exist.");
			}

			this.mapWorkspaceDirPath.remove(workspaceDir);
			this.mapWorkspaceDirPath.put(workspaceDirNew,  path);

			this.mapPathWorkspaceDir.put(path, workspaceDirNew);

			this.mapWorkspaceDirAccessMode.remove(workspaceDir);
			this.mapWorkspaceDirAccessMode.put(workspaceDirNew, 0);

			this.save();
		}

		@Override
		public void deleteWorkspaceDir(WorkspaceDir workspaceDir) {
			Integer readCount;
			Path path;

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

			setWorkspaceDir = new HashSet<WorkspaceDir>(this.mapWorkspaceDirPath.keySet());

			if (workspaceDirClass != null) {
				Iterator<WorkspaceDir> iteratorWorkspaceDir;

				iteratorWorkspaceDir = setWorkspaceDir.iterator();

				while (iteratorWorkspaceDir.hasNext()) {
					WorkspaceDir workspaceDir;

					workspaceDir = iteratorWorkspaceDir.next();

					if (workspaceDir.getClass() != workspaceDirClass) {
						iteratorWorkspaceDir.remove();
					}
				}
			}

			return setWorkspaceDir;
		}

		@Override
		public Set<WorkspaceDir> getSetWorkspaceDir(WorkspaceDir workspaceDirIncomplete) {
			Set<WorkspaceDir> setWorkspaceDir;

			setWorkspaceDir = new HashSet<WorkspaceDir>(this.mapWorkspaceDirPath.keySet());

			if (workspaceDirIncomplete != null) {
				Iterator<WorkspaceDir> iteratorWorkspaceDir;

				iteratorWorkspaceDir = setWorkspaceDir.iterator();

				while (iteratorWorkspaceDir.hasNext()) {
					WorkspaceDir workspaceDir;

					workspaceDir = iteratorWorkspaceDir.next();

					if (workspaceDir.getClass() != workspaceDirIncomplete.getClass()) {
						iteratorWorkspaceDir.remove();
					} else if (workspaceDirIncomplete instanceof WorkspaceDirUserModuleVersion) {
						WorkspaceDirUserModuleVersion workspaceDirUserModuleVersion;
						WorkspaceDirUserModuleVersion workspaceDirUserModuleVersionIncomplete;

						workspaceDirUserModuleVersion = (WorkspaceDirUserModuleVersion)workspaceDir;
						workspaceDirUserModuleVersionIncomplete = (WorkspaceDirUserModuleVersion)workspaceDirIncomplete;

						if (   (workspaceDirUserModuleVersionIncomplete.getModuleVersion().getNodePath() != null)
							&& !workspaceDirUserModuleVersionIncomplete.getModuleVersion().getNodePath().equals(workspaceDirUserModuleVersion.getModuleVersion().getNodePath())) {

							iteratorWorkspaceDir.remove();
						} else if (   (workspaceDirUserModuleVersionIncomplete.getModuleVersion().getVersion() != null)
								   && !workspaceDirUserModuleVersionIncomplete.getModuleVersion().getVersion().equals(workspaceDirUserModuleVersion.getModuleVersion().getVersion())) {

							iteratorWorkspaceDir.remove();
						}
					} else if (workspaceDirIncomplete instanceof WorkspaceDirSystemModule) {
						WorkspaceDirSystemModule workspaceDirSystemModule;
						WorkspaceDirSystemModule workspaceDirSystemModuleIncomplete;

						workspaceDirSystemModule = (WorkspaceDirSystemModule)workspaceDir;
						workspaceDirSystemModuleIncomplete = (WorkspaceDirSystemModule)workspaceDirIncomplete;

						if (   (workspaceDirSystemModuleIncomplete.getNodePath() != null)
							&& !workspaceDirSystemModuleIncomplete.getNodePath().equals(workspaceDirSystemModule.getNodePath())) {

							iteratorWorkspaceDir.remove();
						}
					}
				}
			}

			return setWorkspaceDir;
		}

		@Override
		public boolean isPathWorkspaceDirExists(Path pathWorkspaceDir) {
			return this.mapPathWorkspaceDir.containsKey(pathWorkspaceDir);
		}

		@Override
		public WorkspaceDir getWorkspaceDirFromPath(Path pathWorkspaceDir) {
			if (!this.mapPathWorkspaceDir.containsKey(pathWorkspaceDir)) {
				throw new RuntimeException("No workspace directory corresponds to the path " + pathWorkspaceDir + '.');
			}

			return this.mapPathWorkspaceDir.get(pathWorkspaceDir);
		}

		@Override
		public boolean isTransient() {
			return false;
		}

		@Override
		public void startTool() {
			File workspaceLockedIndicatorFile;

			workspaceLockedIndicatorFile = this.pathDragomMetadataDir.resolve(DefaultWorkspacePluginFactory.WORKSPACE_LOCKED_INDICATOR_FILE).toFile();

			try {
				if (!workspaceLockedIndicatorFile.createNewFile()) {
					throw new RuntimeExceptionUserError(MessageFormat.format(DefaultWorkspacePluginFactory.resourceBundle.getString(DefaultWorkspacePluginFactory.MSG_PATTERN_KEY_WORKSPACE_LOCKED), this.pathWorkspace, workspaceLockedIndicatorFile));
				}
			} catch (IOException ioe) {
				throw new RuntimeException(ioe);
			}
		}

		@Override
		public void endTool() {
			File workspaceLockedIndicatorFile;

			workspaceLockedIndicatorFile = this.pathDragomMetadataDir.resolve(DefaultWorkspacePluginFactory.WORKSPACE_LOCKED_INDICATOR_FILE).toFile();

			workspaceLockedIndicatorFile.delete();
		}
	}

	/**
	 * TODO: Review
	 * Create the WorkspacePlugin using the following strategy:
	 *
	 * - For the WorkspacePlugin, the workspace directory is considered. If the system
	 *   property org.azyva.dragom.WorkspaceDir is defined, the path specified is
	 *   taken as the workspace. Otherwise the current working directory is used.
	 * - If the system property org.azyva.dragom.WorkspaceFactory is defined, the
	 *   static method getWorkspaceInstance of the specified class is called to obtain
	 *   the WorkspacePlugin instance to set in ExecContext.
	 * - Otherwise if the workspace directory contains the
	 *   .dragom/workspace-init.properties file, the class identified by the
	 *   workspace.factory property is used as the factory.
	 * - Otherwise (if the workspace directory does not contain the
	 *   .dragom/workspace-init.properties file), if the system property
	 *   org.azyva.dragom.DefaultWorkspaceFactory is defined, the specified class is
	 *   used as the factory.
	 * - Otherwise, DefaultWorkspaceFactory is used as the factory.
	 * - In all cases once a WorkspacePlugin instance is obtained, it is initialized by
	 *   calling either init or load depending on whether the workspace directory
	 *   contained the .dragom/workspace-init.properties file.
	 *
	 * @return See description.
	 */
	@Override
	public WorkspacePlugin getExecContextPlugin(ExecContext execContext) {
		WorkspaceExecContext workspaceExecContext;
		String workspaceVersion;
		boolean indWorkspaceInit;
		Path pathWorkspace;
		Path pathDragomMetadataDir;
		DefaultWorkspaceImpl defaultWorkspaceImpl;

		if (!(execContext instanceof WorkspaceExecContext)) {
			throw new RuntimeException("An execution context supporting the concept of workspace directory is required.");
		}

		workspaceExecContext = (WorkspaceExecContext)execContext;

		workspaceVersion = execContext.getProperty(DefaultWorkspacePluginFactory.WORKSPACE_VERSION_PROPERTY);

		// We infer the fact that the workspace is initialized or not based on the
		// presence of the workspace-version property. We do not take into consideration
		// whether the workspace is empty or not. This allows for example initializing a
		// Dragom workspace within an Eclipse workspace.
		if (workspaceVersion == null) {
			execContext.setProperty(DefaultWorkspacePluginFactory.WORKSPACE_VERSION_PROPERTY, DefaultWorkspacePluginFactory.WORKSPACE_VERSION);
			indWorkspaceInit = false;
		} else {
			if (!workspaceVersion.equals(DefaultWorkspacePluginFactory.WORKSPACE_VERSION)) {
				throw new RuntimeException("Unsupported workspace version " + workspaceVersion + ". Only version " + DefaultWorkspacePluginFactory.WORKSPACE_VERSION + " is supported by this WorkspacePlugin factory.");
			}

			indWorkspaceInit = true;
		}

		pathWorkspace = workspaceExecContext.getPathWorkspaceDir();
		pathDragomMetadataDir = workspaceExecContext.getPathMetadataDir();

		defaultWorkspaceImpl = null;

		if (indWorkspaceInit) {
			File fileWorkspaceMetadata;
			JAXBContext jaxbContext;
			Unmarshaller unmarshaller;

			try {
				fileWorkspaceMetadata = pathDragomMetadataDir.resolve(DefaultWorkspacePluginFactory.WORKSPACE_METADATA_FILE).toFile();

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
				}
			} catch (JAXBException e) {
				throw new RuntimeException(e);
			}
		}

		if (defaultWorkspaceImpl == null) {
			defaultWorkspaceImpl = new DefaultWorkspaceImpl(pathWorkspace, pathDragomMetadataDir);
		}

		return defaultWorkspaceImpl;
	}
}
