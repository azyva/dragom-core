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

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.commons.lang.NotImplementedException;
import org.azyva.dragom.execcontext.ExecContext;
import org.azyva.dragom.execcontext.ExecContextFactory;
import org.azyva.dragom.execcontext.WorkspaceExecContextFactory;
import org.azyva.dragom.execcontext.plugin.CredentialStorePlugin;
import org.azyva.dragom.execcontext.plugin.ExecContextPlugin;
import org.azyva.dragom.execcontext.plugin.ExecContextPluginFactory;
import org.azyva.dragom.execcontext.plugin.ToolLifeCycleExecContextPlugin;
import org.azyva.dragom.execcontext.plugin.support.ExecContextPluginFactoryHolder;
import org.azyva.dragom.model.Model;
import org.azyva.dragom.model.ModelFactory;
import org.azyva.dragom.model.support.ModelFactoryHolder;

/**
 * Simple {@link ExecContextFactory} implementation that manages
 * {@link ExecContext} without a workspace. It therefore does not support the
 * workspace directory concept and does not implement
 * {@link WorkspaceExecContextFactory}.
 * <p>
 * Since it does not support a workspace, it has nowhere to persistently store
 * properties. It therefore does not support ExecContext properties.
 * <p>
 * It is used as a base class for the full-featured
 * {@link DefaultExecContextFactory}. It is also useful in cases where an
 * external system integrates some functionality from Dragom, such as that offered
 * by {@link CredentialStorePlugin}, but does not need to have a complete
 * {@link ExecContext} implementation.
 *
 * @author David Raymond
 */
public class SimpleExecContextFactory implements ExecContextFactory {
  /**
   * {@link ExecContext} implementation class.
   * <p>
   * The {@link Model} is obtained using the {@link ModelFactory} obtained from
   * {@link ModelFactoryHolder}.
   * <p>
   * The {link ExecContextPlugin}'s are obtained using the
   * {@link ExecContextPluginFactory}'s obtained from
   * {@link ExecContextPluginFactoryHolder}.
   */
  protected class SimpleExecContextImpl implements ExecContext {
    /**
     * Model to be returned by this {@link ExecContext}.
     */
    private Model model;

    /**
     * Map of {@link ExecContextPlugin} that are already instantiated.
     */
    protected Map<Class<? extends ExecContextPlugin>, ExecContextPlugin> mapExecContextPluginInstantiated;

    /**
     * Map of transient {@link ExecContextPlugin} that are already instantiated.
     */
    protected Map<Class<? extends ExecContextPlugin>, ExecContextPlugin> mapExecContextPluginTransientInstantiated;

    /**
     * Initialization Properties. Passed when instantiating the {@link ExecContext}.
     */
    private Properties propertiesInit;

    /**
     * Transient data.
     */
    protected Map<String, Object> mapTransientData;

    /**
     * Constructor.
     *
     * @param propertiesInit Initialization properties.
     */
    protected SimpleExecContextImpl(Properties propertiesInit) {
      this.propertiesInit = propertiesInit;

      this.model = ModelFactoryHolder.getModelFactory().getModel(propertiesInit);

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
      return null;
    }

    @Override
    public void setProperty(String name, String value) {
      throw new NotImplementedException();
    }

    @Override
    public Set<String> getSetProperty(String prefix) {
      return Collections.emptySet();
    }

    @Override
    public void removeProperty(String name) {
      throw new NotImplementedException();
    }

    @Override
    public void removeProperties(String prefix) {
      throw new NotImplementedException();
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
      return "SimpleExecContext";
    }

    @Override
    public void release() {
    }
  }

  @Override
  public ExecContext getExecContext(Properties propertiesInit) {
    return new SimpleExecContextImpl(propertiesInit);
  }
}
