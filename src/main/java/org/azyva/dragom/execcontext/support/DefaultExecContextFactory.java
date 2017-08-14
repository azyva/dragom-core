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

package org.azyva.dragom.execcontext.support;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.ResourceBundle;
import java.util.Set;

import org.azyva.dragom.execcontext.ExecContext;
import org.azyva.dragom.execcontext.ExecContextFactory;
import org.azyva.dragom.execcontext.ToolLifeCycleExecContext;
import org.azyva.dragom.execcontext.WorkspaceExecContext;
import org.azyva.dragom.execcontext.WorkspaceExecContextFactory;
import org.azyva.dragom.execcontext.plugin.ExecContextPlugin;
import org.azyva.dragom.execcontext.plugin.ExecContextPluginFactory;
import org.azyva.dragom.execcontext.plugin.ToolLifeCycleExecContextPlugin;
import org.azyva.dragom.execcontext.plugin.UserInteractionCallbackPlugin;
import org.azyva.dragom.execcontext.plugin.support.ExecContextPluginFactoryHolder;
import org.azyva.dragom.model.Model;
import org.azyva.dragom.model.ModelFactory;
import org.azyva.dragom.model.support.ModelFactoryHolder;
import org.azyva.dragom.util.RuntimeExceptionUserError;
import org.azyva.dragom.util.SortedProperties;
import org.azyva.dragom.util.Util;

/**
 * Default {@link ExecContextFactory} implementation that manages
 * {@link ExecContext} using a workspace directory in the file system. Here the
 * term "workspace" is used to refer to the root workspace directory. Elsewhere
 * the term "workspace directory" is generally used to refer to a directory within
 * the root workspace directory. We hope that no confusion will arise.
 * <p>
 * A static Map of workspace Path to ExecContext instances is used in order to
 * reuse ExecContext instances. This is useful in case a single JVM instance is
 * used for multiple tool executions
 * (<a href="http://www.martiansoftware.com/nailgun/">NaigGun</a> can be useful in
 * that regard).
 * <p>
 * This ExecContextFactory supports the workspace directory concept and therefore
 * implements {@link WorkspaceExecContextFactory}.
 * <p>
 * The ExecContext implementation implements {@link ToolLifeCycleExecContext} and
 * therefore honors {@link ToolLifeCycleExecContextPlugin} implemented by
 * {@link ExecContextPlugin}'s.
 * <p>
 * When obtaining an ExecContext using
 * {@link DefaultExecContextFactory#getExecContext} the workspace directory does
 * not need to exist and is automatically created and initialized if necessary.
 * <p>
 * Initialization of the workspace directory is lazy and is performed only when
 * really needed. This avoids getting the workspace directory created and
 * initialized by tools which do not really require it, such as the
 * credential-manager tool when the credential file is not kept in the workspace.
 *
 * @author David Raymond
 */
public class DefaultExecContextFactory extends SimpleExecContextFactory implements ExecContextFactory, WorkspaceExecContextFactory {
  /**
   * Initialization property specifying the workspace directory.
   */
  private static final String INIT_PROPERTY_WORKSPACE_DIR = "WORKSPACE_PATH";

  /**
   * Metadata directory.
   */
  private static final String DRAGOM_METADATA_DIR = ".dragom";

  /**
   * {@link ExecContext} property specifying the format of the workspace data.
   */
  private static final String EXEC_CONTEXT_PROPERTY_WORKSPACE_FORMAT = "WORKSPACE_FORMAT";

  /**
   * {@link ExecContext} property specifying the version of the workspace data.
   */
  private static final String EXEC_CONTEXT_PROPERTY_WORKSPACE_VERSION = "WORKSPACE_VERSION";

  /**
   * Properties file within the Dragom metadata directory.
   * <p>
   * Workspace properties are stored within this file.
   */
  private static final String PROPERTIES_FILE = "exec-context.properties";

  /**
   * Initialization property indicating to ignore any cached {@link ExecContext} and
   * instantiate a new one.
   */
  private static final String INIT_PROPERTY_IND_IGNORE_CACHED_EXEC_CONTEXT = "IND_IGNORE_CACHED_EXEC_CONTEXT";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_WORKSPACE_DIR_INIT = "WORKSPACE_DIR_INIT";

  /**
   * ResourceBundle specific to this class.
   */
  private static final ResourceBundle resourceBundle = ResourceBundle.getBundle(DefaultExecContextFactory.class.getName() + "ResourceBundle");

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
  private class DefaultExecContextImpl extends SimpleExecContextFactory.SimpleExecContextImpl implements ExecContext, WorkspaceExecContext, ToolLifeCycleExecContext {
    /**
     * Indicates if the workspace directory has been initialized.
     */
    private boolean indWorkspaceDirInit;

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
     * Properties. Loaded from the properties file within the metadata workspace
     * directory.
     */
    private Properties properties;

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
      super(propertiesInit);

      this.pathWorkspaceDir = pathWorkspaceDir;
      this.pathMetadataDir = this.pathWorkspaceDir.resolve(DefaultExecContextFactory.DRAGOM_METADATA_DIR);
      this.pathPropertiesFile = this.pathMetadataDir.resolve(DefaultExecContextFactory.PROPERTIES_FILE);

      this.properties = new SortedProperties();

      // The existence of the Properties file is used as an indication of whether the
      // workspace directory is initialized. If the file does not exist the workspace
      // directory is automatically initialized.

      if (this.pathPropertiesFile.toFile().isFile()) {
        try (InputStream inputStreamProperties = new FileInputStream(this.pathPropertiesFile.toFile())) {
          this.properties.load(inputStreamProperties);
        } catch (IOException ioe) {
          throw new RuntimeException(ioe);
        }

        this.indWorkspaceDirInit = true;
      }
    }

    /**
     * Initializes the workspace directory if not already initialized.
     *
     * <p>If the workspace directory is already initialized, this method does nothing
     * and quickly returns.
     */
    private void initWorkspaceDir() {
      if (!this.indWorkspaceDirInit) {
        synchronized(this) {
          if (!this.indWorkspaceDirInit) {
            if (!this.pathMetadataDir.toFile().isDirectory()) {
              if (!this.pathMetadataDir.toFile().mkdirs()) {
                throw new RuntimeException("Directory " + this.pathMetadataDir + " could not be created.");
              }
            }

            this.saveProperties();

            this.indWorkspaceDirInit = true;

            // Using UserInteractionCallbackPlugin here seems a little bit inappropriate given
            // that we are in the ExecContext implementation itself which is responsible for
            // providing such plugins. Nevertheless it works and still seems clean.
            this.getExecContextPlugin(UserInteractionCallbackPlugin.class).provideInfo(MessageFormat.format(DefaultExecContextFactory.resourceBundle.getString(DefaultExecContextFactory.MSG_PATTERN_KEY_WORKSPACE_DIR_INIT), this.pathWorkspaceDir));
          }
        }
      }
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

      this.initWorkspaceDir();

      this.saveProperties();
    }

    @Override
    public Set<String> getSetProperty(String prefix) {
      Set<String> setProperty;
      Iterator<String> iteratorProperty;

      setProperty = this.properties.stringPropertyNames();

      if (prefix != null) {
        iteratorProperty = setProperty.iterator();

        while (iteratorProperty.hasNext()) {
          if (!(iteratorProperty.next().startsWith(prefix))) {
            iteratorProperty.remove();
          }
        }
      }

      return setProperty;
    }

    @Override
    public void removeProperty(String name) {
      if (this.properties.remove(name) != null) {;
        // No need to initialize the workspace since by definition, if there is a
        // property to remove, it has already been initialized.

        this.saveProperties();
      }
    }

    @Override
    public void removeProperties(String prefix) {
      Set<String> setProperty;

      setProperty = this.getSetProperty(prefix);

      if (!setProperty.isEmpty()) {
        for (String property: setProperty) {
          this.properties.remove(property);
        }

        // No need to initialize the workspace since by definition, if there is a
        // property to remove, it has already been initialized.

        this.saveProperties();
      }
    }

    /**
     * Saves the Properties.
     */
    private void saveProperties() {
      try (OutputStream outputStreamProperties = new FileOutputStream(this.pathPropertiesFile.toFile())){
        this.properties.store(outputStreamProperties, null);
      } catch (IOException ioe) {
        throw new RuntimeException(ioe);
      }
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
    public void release() {
      DefaultExecContextFactory.mapPathWorkspaceDirDefaultExecContextImpl.remove(this.pathWorkspaceDir);
    }

    @Override
    public Path getPathWorkspaceDir() {
      // If the caller requests the Path to the workspace directory, we presume it expects
      // it to exist.
      this.initWorkspaceDir();

      return this.pathWorkspaceDir;
    }

    @Override
    public Path getPathMetadataDir() {
      // If the caller requests the Path to the workspace metadata directory, we presume
      // it expects it to exist.
      this.initWorkspaceDir();

      return this.pathMetadataDir;
    }

    @Override
    public WorkspaceFormatVersion getWorkspaceFormatVersion() {
      String workspaceFormat;
      WorkspaceFormatVersion workspaceFormatVersion;

      workspaceFormat = this.getProperty(DefaultExecContextFactory.EXEC_CONTEXT_PROPERTY_WORKSPACE_FORMAT);

      if (workspaceFormat == null) {
        return null;
      }

      workspaceFormatVersion = new WorkspaceFormatVersion(workspaceFormat, this.getProperty(DefaultExecContextFactory.EXEC_CONTEXT_PROPERTY_WORKSPACE_VERSION));

      return workspaceFormatVersion;
    }

    @Override
    public void setWorkspaceFormatVersion(WorkspaceFormatVersion workspaceFormatVersion) {
      this.setProperty(DefaultExecContextFactory.EXEC_CONTEXT_PROPERTY_WORKSPACE_FORMAT, workspaceFormatVersion.format);
      this.setProperty(DefaultExecContextFactory.EXEC_CONTEXT_PROPERTY_WORKSPACE_VERSION, workspaceFormatVersion.version);
    };

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
    public Set<String> getSetToolProperty() {
      if (this.propertiesTool == null) {
        return Collections.emptySet();
      } else {
        Set<String> setToolProperty;

        setToolProperty = new HashSet<String>();

        for (Object key: this.propertiesTool.keySet()) {
          setToolProperty.add((String)key);
        }

        return setToolProperty;
      }
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
   * @param propertiesInit Initialization properties.
   * @return ExecContext.
   */
  @Override
  public ExecContext getExecContext(Properties propertiesInit) {
    String workspaceDir;
    Path pathWorkspaceDir;
    File fileDragomMetadataDir;
    boolean indIgnoreCachedExecContext;
    DefaultExecContextImpl defaultExecContextImpl;

    workspaceDir = propertiesInit.getProperty(DefaultExecContextFactory.INIT_PROPERTY_WORKSPACE_DIR);

    if (workspaceDir == null) {
      workspaceDir = System.getProperty("user.dir");

      pathWorkspaceDir = Paths.get(workspaceDir).normalize();

      fileDragomMetadataDir = pathWorkspaceDir.resolve(DefaultExecContextFactory.DRAGOM_METADATA_DIR).toFile();

      if (!fileDragomMetadataDir.exists()) {
        for (Path pathWorkspaceDirParent = pathWorkspaceDir.getParent(); pathWorkspaceDirParent != null; pathWorkspaceDirParent = pathWorkspaceDirParent.getParent()) {
          fileDragomMetadataDir = pathWorkspaceDirParent.resolve(DefaultExecContextFactory.DRAGOM_METADATA_DIR).toFile();

          if (fileDragomMetadataDir.exists()) {
            throw new RuntimeExceptionUserError("Workspace directory " + pathWorkspaceDir + " is itself within a workspace directory (one of its parent contains a .dragom directory or file).");
          }
        }
      }
    } else {
        pathWorkspaceDir = Paths.get(workspaceDir).normalize();
    }

    indIgnoreCachedExecContext = Util.isNotNullAndTrue(propertiesInit.getProperty(DefaultExecContextFactory.INIT_PROPERTY_IND_IGNORE_CACHED_EXEC_CONTEXT));

    if (indIgnoreCachedExecContext) {
      DefaultExecContextFactory.mapPathWorkspaceDirDefaultExecContextImpl.remove(pathWorkspaceDir);
    }

    defaultExecContextImpl = DefaultExecContextFactory.mapPathWorkspaceDirDefaultExecContextImpl.get(pathWorkspaceDir);

    if (defaultExecContextImpl == null) {
      defaultExecContextImpl = new DefaultExecContextImpl(pathWorkspaceDir, propertiesInit);
      DefaultExecContextFactory.mapPathWorkspaceDirDefaultExecContextImpl.put(pathWorkspaceDir, defaultExecContextImpl);
    }

    return defaultExecContextImpl;
  }

  @Override
  public String getWorkspaceDirInitProperty() {
    return DefaultExecContextFactory.INIT_PROPERTY_WORKSPACE_DIR;
  }
}
