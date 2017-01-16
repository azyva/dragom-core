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

package org.azyva.dragom.model.config.impl.simple;

import org.azyva.dragom.model.Model;
import org.azyva.dragom.model.Node;
import org.azyva.dragom.model.config.NodeConfig;
import org.azyva.dragom.model.config.NodeConfigTransferObject;
import org.azyva.dragom.model.config.PluginDefConfig;
import org.azyva.dragom.model.plugin.NodePlugin;
import org.azyva.dragom.util.Util;

/**
 * Simple implementation for {@link PluginDefConfig}.
 * <p>
 * Can be used as a simple PluginDefConfig within {@link Node}, {@link NodeConfig}
 * and {@link NodeConfigTransferObject} implementations.
 * <p>
 * See org.azyva.dragom.model.config.impl.simple from dragom-core.

 *
 * @author David Raymond
 */
public class SimplePluginDefConfig implements PluginDefConfig {
  /**
   * {@link NodePlugin} interface class.
   */
  private Class<? extends NodePlugin> classNodePlugin;

  /**
   * Plugin ID.
   */
  private String pluginId;

  /**
   * Plugin implementation class.
   */
  private String pluginClass;

  /**
   * Indicates that this {@link PluginDefConfig} applies specifically to the
   * {@link NodeConfig} on which it is defined, as opposed to being inherited by
   * child NodeConfig when interpreted by the {@link Model}.
   */
  private boolean indOnlyThisNode;

  /**
   * Default constructor.
   *
   * <p>This is not meant to be used by the caller. It is required when this class is
   * used with frameworks which automatically create instances, such as JPA.
   */
  protected SimplePluginDefConfig() {
  }

  /**
   * Constructor.
   *
   * @param classNodePlugin Class of the NodePlugin.
   * @param pluginId ID of the plugin. Can be null.
   * @param pluginClass Name of the implementaiton class of the plugin. Can be null
   *   to avoid inheritance.
   * @param indOnlyThisNode Indicates that this property applies specifically to the
   *   {@link NodeConfig} on which it is defined, as opposed to being inherited by
   *   child NodeConfig when interpreted by the {@link Model}.
   */
  public SimplePluginDefConfig(Class<? extends NodePlugin> classNodePlugin, String pluginId, String pluginClass, boolean indOnlyThisNode) {
    if ((this.pluginClass == null) || this.pluginClass.isEmpty()){
      throw new RuntimeException("PluginDef cannot specify a null or empty plugin class.");
    }

    this.pluginId = pluginId;
    this.pluginClass = pluginClass;

    try {
      Class.forName(this.pluginClass);
    } catch (ClassNotFoundException cnfe) {
      throw new RuntimeException(cnfe);
    }

    if (classNodePlugin == null) {
      this.classNodePlugin = Util.getDefaultClassNodePlugin(this.pluginClass);
    } else {
      classNodePlugin.asSubclass(NodePlugin.class);
      this.classNodePlugin = classNodePlugin;
    }

    this.pluginClass = pluginClass;

    // TODO: Should we handle default pluginId?
  }

  @Override
  public Class<? extends NodePlugin> getClassNodePlugin() {
    return this.classNodePlugin;
  }

  @Override
  public String getPluginId() {
    return this.pluginId;
  }

  @Override
  public String getPluginClass() {
    return this.pluginClass;
  }

  @Override
  public boolean isOnlyThisNode() {
    return this.indOnlyThisNode;
  }

  /**
   * @return String to help recognize the
   *   {@link PluginDefConfig} instance, in logs for example.
   */
  @Override
  public String toString() {
    return "SimplePluginDefConfig [classNodePlugin=" + this.classNodePlugin.getName() + ", pluginId=" + this.pluginId + ", pluginClass=" + this.pluginClass + ", indOnlyThisNode=" + this.indOnlyThisNode + "]";
  }
}
