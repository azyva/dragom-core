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

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.adapters.XmlAdapter;

import org.azyva.dragom.execcontext.plugin.WorkspaceDir;
import org.azyva.dragom.execcontext.plugin.WorkspaceDirSystemModule;
import org.azyva.dragom.execcontext.plugin.WorkspaceDirUserModuleVersion;
import org.azyva.dragom.execcontext.plugin.impl.MapWorkspaceDirPathXmlAdapter.ListWorkspaceDirPath;
import org.azyva.dragom.model.ModuleVersion;
import org.azyva.dragom.model.NodePath;
import org.azyva.dragom.model.Version;

public class MapWorkspaceDirPathXmlAdapter extends XmlAdapter<ListWorkspaceDirPath, Map<WorkspaceDir, Path>> {
	@XmlAccessorType(XmlAccessType.NONE)
	public static class WorkspaceDirPath {
		@XmlElement(name = "workspace-dir-class")
		public String workspaceDirClass;

		@XmlElement(name = "module-node-path")
		public String stringNodePath;

		@XmlElement(name = "version")
		public String stringVersion;

		@XmlElement(name = "path")
		public String stringPath;
	}

	@XmlAccessorType(XmlAccessType.NONE)
	public static class ListWorkspaceDirPath {
		@XmlElement(name = "workspace-dir")
		private List<WorkspaceDirPath> listWorkspaceDirPath;

		/**
		 * Default constructor required by JAXB.
		 */
		public ListWorkspaceDirPath() {
		}

		public ListWorkspaceDirPath(List<WorkspaceDirPath> listWorkspaceDirPath) {
			this.listWorkspaceDirPath = listWorkspaceDirPath;
		}

		public List<WorkspaceDirPath> getListWorkspaceDirPath() {
			return this.listWorkspaceDirPath;
		}

	}

	/* Path to the workspace to allow relativisation of workspace directories.
	 */
	private Path pathWorkspace;

	/**
	 * MapWorkspaceDirPathXmlAdapter needs to know the workspace path in order to
	 * relativise workspace directories within the workspace.
	 *
	 * The instance of this class must be preset on the Marshaller and Unmarshaller
	 * since JAXB does not know how to instantiate a XmlAdapter with no no-arg
	 * constructor.
	 *
	 * @param pathWorkspace WorkspacePlugin path.
	 */
	public MapWorkspaceDirPathXmlAdapter(Path pathWorkspace) {
		this.pathWorkspace = pathWorkspace;
	}

	@Override
	public ListWorkspaceDirPath marshal(Map<WorkspaceDir, Path> mapWorkspaceDirPath) {
		List<WorkspaceDirPath> listWorkspaceDirPath;

		listWorkspaceDirPath = new ArrayList<WorkspaceDirPath>();

		for (Map.Entry<WorkspaceDir, Path> mapEntry: mapWorkspaceDirPath.entrySet()) {
			WorkspaceDirPath workspaceDirPath;
			WorkspaceDir workspaceDir;

			workspaceDirPath = new WorkspaceDirPath();

			workspaceDir = mapEntry.getKey();

			workspaceDirPath.workspaceDirClass = mapEntry.getKey().getClass().getName();

			if (workspaceDir instanceof WorkspaceDirUserModuleVersion) {
				workspaceDirPath.stringNodePath = ((WorkspaceDirUserModuleVersion)workspaceDir).getModuleVersion().getNodePath().toString();
				workspaceDirPath.stringVersion = ((WorkspaceDirUserModuleVersion)workspaceDir).getModuleVersion().getVersion().toString();
			} else if (workspaceDir instanceof WorkspaceDirSystemModule) {
				workspaceDirPath.stringNodePath = ((WorkspaceDirSystemModule)workspaceDir).getNodePath().toString();
			} else {
				throw new RuntimeException("Unknown WorkspaceDir class " + workspaceDir.getClass().getName() + '.');
			}

			workspaceDirPath.stringPath = this.pathWorkspace.relativize(mapEntry.getValue()).toString();

			listWorkspaceDirPath.add(workspaceDirPath);
		}

		return new ListWorkspaceDirPath(listWorkspaceDirPath);
	}

	@Override
	public Map<WorkspaceDir, Path> unmarshal(ListWorkspaceDirPath listWorkspaceDirPath) {
		Map<WorkspaceDir, Path> mapWorkspaceDirPath;

		mapWorkspaceDirPath = new HashMap<WorkspaceDir, Path>();

		for (WorkspaceDirPath workspaceDirPath: listWorkspaceDirPath.getListWorkspaceDirPath()) {
			WorkspaceDir workspaceDir;
			Class<? extends WorkspaceDir> classWorkspaceDir;
			Path path;

			try {
				classWorkspaceDir = Class.forName(workspaceDirPath.workspaceDirClass).asSubclass(WorkspaceDir.class);

				if (classWorkspaceDir == WorkspaceDirUserModuleVersion.class) {
					workspaceDir = new WorkspaceDirUserModuleVersion(new ModuleVersion(new NodePath(workspaceDirPath.stringNodePath), new Version(workspaceDirPath.stringVersion)));
				} else if (classWorkspaceDir == WorkspaceDirSystemModule.class) {
					workspaceDir = new WorkspaceDirSystemModule(new NodePath(workspaceDirPath.stringNodePath));
				} else {
					throw new RuntimeException("Unknown WorkspaceDir class " + workspaceDirPath.workspaceDirClass + '.');
				}
			} catch (ClassNotFoundException cnfe) {
				throw new RuntimeException(cnfe);
			}

			path = this.pathWorkspace.resolve(Paths.get(workspaceDirPath.stringPath));

			mapWorkspaceDirPath.put(workspaceDir, path);
		}

		return mapWorkspaceDirPath;
	}
}
