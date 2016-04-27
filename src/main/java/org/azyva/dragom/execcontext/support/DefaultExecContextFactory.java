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

package org.azyva.dragom.execcontext.support;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.azyva.dragom.execcontext.ExecContext;
import org.azyva.dragom.execcontext.ExecContextFactory;
import org.azyva.dragom.execcontext.ToolLifeCycleExecContext;
import org.azyva.dragom.execcontext.WorkspaceExecContext;
import org.azyva.dragom.execcontext.WorkspaceExecContextFactory;
import org.azyva.dragom.execcontext.plugin.ExecContextPlugin;
import org.azyva.dragom.execcontext.plugin.ExecContextPluginFactory;
import org.azyva.dragom.execcontext.plugin.ToolLifeCycleExecContextPlugin;
import org.azyva.dragom.execcontext.plugin.support.ExecContextPluginFactoryHolder;
import org.azyva.dragom.model.Model;
import org.azyva.dragom.model.ModelFactory;
import org.azyva.dragom.model.support.ModelFactoryHolder;
import org.azyva.dragom.util.RuntimeExceptionUserError;

/**
 * {@link Model}.
 * Default {@link ExecContextFactory} implementation that manages
 * {@link ExecContext} using a workspace directory in the file system. Here the
 * term "workspace" is used to refer to the root workspace directory. Elsewhere
 * the term "workspace directory" is generally used to refer to a directory within
 * the root workspace directory. We hope that no confusion will arise.
 * <p>
 * A static Map of workspace Path to ExecContext instances is used in order to
 * reuse ExecContext instances. This is useful in case a single JVM instance is
 * used for multiple tool executions
 * (<a href="http://www.martiansoftware.com/nailgun/">NaigGun<a> can be useful in
 * that regard).
 * <p>
 * This ExecContextFactory supports the workspace directory concept and therefore
 * implements {@link WorkspaceExecContextFactory}.
 * <p>
 * The ExecContext implementation implement {@link ToolLifeCycleExecContext} and
 * therefore honor {@link ToolLifeCycleExecContextPlugin} implemented by
 * {@link ExecContextPlugin}'s.
 * <p>
 * When obtaining an ExecContext using
 * {@link DefaultExecContextFactory#getExecContext} the workspace directory does
 * not need to exist and is automatically created and initialized if necessary.
 *
 * @author David Raymond
 */
public class DefaultExecContextFactory implements ExecContextFactory, WorkspaceExecContextFactory {
	/**
	 * Initialization property specifying the workspace directory.
	 */
	private static final String WORKSPACE_DIR_INIT_PROP = "org.azyva.dragom.WorkspacePath";

	/**
	 * Metadata directory.
	 */
	private static final String DRAGOM_METADATA_DIR = ".dragom";

	/**
	 * Properties file within the Dragom metadata directory.
	 * <p>
	 * Workspace properties are stored within this file.
	 */
	private static final String PROPERTIES_FILE = "exec-context.properties";

	/**
	 * Map of workspace Path to DefaultExecContextImpl.
	 */
	private static Map<Path, DefaultExecContextImpl> mapPathWorkspaceDirDefaultExecContextImpl = new HashMap<Path, DefaultExecContextImpl>();

	/**
	 * {@link ExecContext} implementation class.
	 * <p>
	 * The {@link Model} is obtained using the {@link ModelFactory} obtained from
	 * {@link ModelFactoryHolder}.
	 * <p>
	 * The {link ExecContextPlugin}'s are obtained using the
	 * {@link ExecContextPluginFactory}'s obtained from
	 * {@link ExecContextPluginFactoryHolder}.
	 * <p>
	 * This implementation maintains a Map of ExecContextPlugin interfaces to
	 * ExecContextPlugin instances so that plugin instances are unique (whithin an
	 * ExecContext).
	 */
	private static class DefaultExecContextImpl implements ExecContext, WorkspaceExecContext, ToolLifeCycleExecContext {
		/**
		 * Path to the workspace directory.
		 */
		private Path pathWorkspaceDir;

		/**
		 * Path to the workspace directory containing the metadata.
		 */
		private Path pathMetadataDir;

		/**
		 * Path to the properties file within the metadata workspace directory.
		 */
		private Path pathPropertiesFile;

		/**
		 * Model to be returned by this {@link ExecContext}.
		 */
		private Model model;

		/**
		 * Map of {@link ExecContextPlugin} that are already instantiated.
		 */
		private Map<Class<? extends ExecContextPlugin>, ExecContextPlugin> mapExecContextPluginInstantiated;

		/**
		 * Map of transient {@link ExecContextPlugin} that are already instantiated.
		 */
		private Map<Class<? extends ExecContextPlugin>, ExecContextPlugin> mapExecContextPluginTransientInstantiated;

		/**
		 * Initialization Properties. Passed when instantiating the {@link ExecContext}.
		 */
		private Properties propertiesInit;

		/**
		 * Properties. Loaded from the properties file within the metadata workspace
		 * directory.
		 */
		private Properties properties;

		/**
		 * Transient data.
		 */
		private Map<String, Object> mapTransientData;

		/**
		 * Initialization Properties passed by a tool.
		 * <p>
		 * These are not the same as initialization Properties passed to
		 * {@link ExecContextFactory#getExecContext} since the ExecContext is
		 * implicitly bound to the workspace scope and these initialization Properties
		 * are when the workspace is created.
		 */
		private Properties propertiesTool;

		/**
		 * Constructor.
		 *
		 * @param pathWorkspaceDir Path to the workspace directory. Can be obtained
		 *   from propertiesInit, but {@link DefaultExecContextFactory#getExecContext}
		 *   has already obtained it, so might as well reuse it.
		 * @param propertiesInit Initialization properties.
		 */
		private DefaultExecContextImpl(Path pathWorkspaceDir, Properties propertiesInit) {
			this.propertiesInit = propertiesInit;
			this.pathWorkspaceDir = pathWorkspaceDir;
			this.pathMetadataDir = this.pathWorkspaceDir.resolve(DefaultExecContextFactory.DRAGOM_METADATA_DIR);
			this.pathPropertiesFile = this.pathMetadataDir.resolve(DefaultExecContextFactory.PROPERTIES_FILE);

			this.model = ModelFactoryHolder.getModelFactory().getModel(propertiesInit);

			this.properties = new Properties();

			// The existence of the Properties file is used as an indication of whether the
			// workspace directory is initialized. If the file does not exist the workspace
			// directory is automatically initialized.

			if (this.pathPropertiesFile.toFile().isFile()) {
				try {
					this.properties.load(new FileInputStream(this.pathPropertiesFile.toFile()));
				} catch (IOException ioe) {
					throw new RuntimeException(ioe);
				}
			} else {
				if (!this.pathPropertiesFile.getParent().toFile().mkdirs()) {
					throw new RuntimeException("Parent directory of " + this.pathPropertiesFile + " could not be created.");
				}

				this.saveProperties();
			}

			this.mapTransientData = new HashMap<String, Object>();

			this.mapExecContextPluginInstantiated = new HashMap<Class<? extends ExecContextPlugin>, ExecContextPlugin>();
			this.mapExecContextPluginTransientInstantiated = new HashMap<Class<? extends ExecContextPlugin>, ExecContextPlugin>();
		}

		/**
		 * Returns the Model.
		 *
		 * See the description of this class for the strategy for obtaining the Model.
		 *
		 *  @return Model.
		 */
		@Override
		public Model getModel() {
			return this.model;
		}

		/**
		 * Gets a ExecContextPlugin.
		 *
		 * See the description of this class for the strategy for obtaining
		 * ExecContextPlugin.
		 *
		 * @param classExecContextPluginInterface Plugin ID.
		 * @return ExecContextPlugin. null if not found.
		 */
		@Override
		public <ExecContextPluginInterface extends ExecContextPlugin> ExecContextPluginInterface getExecContextPlugin(Class<ExecContextPluginInterface> classExecContextPluginInterface) {
			ExecContextPlugin execContextPlugin;

			if (this.mapExecContextPluginInstantiated.containsKey(classExecContextPluginInterface)) {
				execContextPlugin = this.mapExecContextPluginInstantiated.get(classExecContextPluginInterface);
			}
			else if (this.mapExecContextPluginTransientInstantiated.containsKey(classExecContextPluginInterface)) {
				execContextPlugin = this.mapExecContextPluginTransientInstantiated.get(classExecContextPluginInterface);
			} else {
				ExecContextPluginFactory<ExecContextPluginInterface> execContextPluginFactory;

				execContextPluginFactory = ExecContextPluginFactoryHolder.getExecContextPluginFactory(classExecContextPluginInterface);

				execContextPlugin = execContextPluginFactory.getExecContextPlugin(this);

				if (execContextPlugin instanceof ToolLifeCycleExecContextPlugin) {
					ToolLifeCycleExecContextPlugin toolLifeCycleExecContextPlugin;

					toolLifeCycleExecContextPlugin = (ToolLifeCycleExecContextPlugin)execContextPlugin;

					if (toolLifeCycleExecContextPlugin.isTransient()) {
						this.mapExecContextPluginTransientInstantiated.put(classExecContextPluginInterface, execContextPlugin);
					} else {
						this.mapExecContextPluginInstantiated.put(classExecContextPluginInterface, execContextPlugin);
					}
				} else {
					this.mapExecContextPluginInstantiated.put(classExecContextPluginInterface, execContextPlugin);
				}
			}

			// Seems to be the only way to avoid warnings without a SuppressWarnings
			// annotation.
			return execContextPlugin.getClass().asSubclass(classExecContextPluginInterface).cast(execContextPlugin);
		}

		@Override
		public Set<String> getSetInitProperty() {
			Set<String> setInitProperty;

			setInitProperty = new HashSet<String>();

			for (Object key: this.propertiesInit.keySet()) {
				setInitProperty.add((String)key);
			}

			return setInitProperty;
		}

		@Override
		public String getInitProperty(String name) {
			return this.propertiesInit.getProperty(name);
		}

		@Override
		public String getProperty(String name) {
			return this.properties.getProperty(name);
		}

		@Override
		public void setProperty(String name, String value) {
			if (value == null) {
				this.properties.remove(name);
			} else {
				this.properties.setProperty(name,  value);
			}

			this.saveProperties();
		}

		@Override
		public Set<String> getSetProperty(String prefix) {
			Set<String> setProperty;
			Iterator<String> iterProperty;

			setProperty = this.properties.stringPropertyNames();

			if (prefix != null) {
				iterProperty = setProperty.iterator();

				while (iterProperty.hasNext()) {
					if (iterProperty.next().startsWith(prefix)) {
						iterProperty.remove();
					}
				}
			}

			return setProperty;
		}

		@Override
		public void removeProperty(String name) {
			this.properties.remove(name);

			this.saveProperties();
		}

		@Override
		public void removeProperties(String prefix) {
			Set<String> setProperty;

			setProperty = this.getSetProperty(prefix);

			if (!setProperty.isEmpty()) {
				for (String property: setProperty) {
					this.properties.remove(property);
				}

				this.saveProperties();
			}
		}

		/**
		 * Saves the Properties.
		 */
		private void saveProperties() {
			try {
				this.properties.store(new FileOutputStream(this.pathPropertiesFile.toFile()), null);
			} catch (IOException ioe) {
				throw new RuntimeException(ioe);
			}
		}

		@Override
		public Object getTransientData(String name) {
			return this.mapTransientData.get(name);
		}

		@Override
		public void setTransientData(String name, Object value) {
			this.mapTransientData.put(name, value);
		}

		/**
		 * @return Simply returns the Path to the workspace directory associated with the
		 *   ExecContext.
		 */
		@Override
		public String getName() {
			return this.pathWorkspaceDir.toString();
		}

		@Override
		public Path getPathWorkspaceDir() {
			return this.pathWorkspaceDir;
		}

		@Override
		public Path getPathMetadataDir() {
			return this.pathMetadataDir;
		}

		@Override
		public void startTool(Properties propertiesTool) {
			// Normally when a tool starts, the ExecContext should not have any transient
			// ExecContextPlugin's since they should have been removed when endTool was
			// called and in theory, endTool not being called could be considered a bug. But
			// we decide to simply do what endTool should have done here to avoid useless
			// complications in handling abnormal tool termination.
			if  (!this.mapExecContextPluginTransientInstantiated.isEmpty()) {
				for (ExecContextPlugin execContextPlugin: this.mapExecContextPluginTransientInstantiated.values()) {
					if (execContextPlugin instanceof ToolLifeCycleExecContextPlugin) {
						((ToolLifeCycleExecContextPlugin)execContextPlugin).endTool();
					}
				}

				this.mapExecContextPluginTransientInstantiated.clear();
			}

			this.propertiesTool = propertiesTool;

			for (ExecContextPlugin execContextPlugin: this.mapExecContextPluginInstantiated.values()) {
				if (execContextPlugin instanceof ToolLifeCycleExecContextPlugin) {
					((ToolLifeCycleExecContextPlugin)execContextPlugin).startTool();
				}
			}

			// Similar comment as above for transient data.
			this.mapTransientData.clear();
		}

		@Override
		public void endTool() {
			for (ExecContextPlugin execContextPlugin: this.mapExecContextPluginInstantiated.values()) {
				if (execContextPlugin instanceof ToolLifeCycleExecContextPlugin) {
					((ToolLifeCycleExecContextPlugin)execContextPlugin).endTool();
				}
			}

			for (ExecContextPlugin execContextPlugin: this.mapExecContextPluginTransientInstantiated.values()) {
				if (execContextPlugin instanceof ToolLifeCycleExecContextPlugin) {
					((ToolLifeCycleExecContextPlugin)execContextPlugin).endTool();
				}
			}

			this.mapExecContextPluginTransientInstantiated.clear();

			this.mapTransientData.clear();

			this.propertiesTool = null;
		}

		@Override
		public String getToolProperty(String name) {
			if (this.propertiesTool != null) {
				return this.propertiesTool.getProperty(name);
			} else {
				return null;
			}
		}
	}

	/**
	 * Since this {@link ExecContextFactory} implements WorkspaceExecContextFactory,
	 * this method returns an {@link ExecContext} corresponding to the workspace
	 * directory.
	 *
	 * @param Properties propertiesInit Initialization properties.
	 * @return ExecContext.
	 */
	@Override
	public ExecContext getExecContext(Properties propertiesInit) {
		String workspaceDir;
		Path pathWorkspaceDir;
		DefaultExecContextImpl defaultExecContextImpl;

		workspaceDir = propertiesInit.getProperty(DefaultExecContextFactory.WORKSPACE_DIR_INIT_PROP);

		if (workspaceDir == null) {
			workspaceDir = System.getProperty("user.dir");
		}

		pathWorkspaceDir = Paths.get(workspaceDir).normalize();

		for (Path pathWorkspaceDirParent = pathWorkspaceDir.getParent(); pathWorkspaceDirParent != null; pathWorkspaceDirParent = pathWorkspaceDirParent.getParent()) {
			File fileDragomMetadataDir;

			fileDragomMetadataDir = pathWorkspaceDirParent.resolve(DefaultExecContextFactory.DRAGOM_METADATA_DIR).toFile();

			if (fileDragomMetadataDir.exists()) {
				throw new RuntimeExceptionUserError("Workspace directory " + pathWorkspaceDir + " is itself within a workspace directory (one of its parent contains a .dragom directory or file).");
			}
		}

		defaultExecContextImpl = DefaultExecContextFactory.mapPathWorkspaceDirDefaultExecContextImpl.get(pathWorkspaceDir);

		if (defaultExecContextImpl == null) {
			defaultExecContextImpl = new DefaultExecContextImpl(pathWorkspaceDir, propertiesInit);
			DefaultExecContextFactory.mapPathWorkspaceDirDefaultExecContextImpl.put(pathWorkspaceDir, defaultExecContextImpl);
		}

		return defaultExecContextImpl;
	}

	@Override
	public String getWorkspaceDirInitProp() {
		return DefaultExecContextFactory.WORKSPACE_DIR_INIT_PROP;
	}
}
