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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.azyva.dragom.model.config.MutableConfig;
import org.azyva.dragom.model.config.NodeConfigTransferObject;
import org.azyva.dragom.model.config.PluginDefConfig;
import org.azyva.dragom.model.config.PluginKey;
import org.azyva.dragom.model.config.PropertyDefConfig;
import org.azyva.dragom.model.plugin.NodePlugin;

/**
 * Simple implementation for {@link NodeConfigTransferObject}.
 *
 * @author David Raymond
 */
public class SimpleNodeConfigTransferObject implements NodeConfigTransferObject {
  /**
   * Name.
   */
  private String name;

  /**
   * Map of PropertyDefConfig.
   */
  private Map<String, PropertyDefConfig> mapPropertyDefConfig;

  /**
   * Map of PluginDefConfig.
   */
  private Map<PluginKey, PluginDefConfig> mapPluginDefConfig;

  /**
   * Constructor.
   * <p>
   * This constructor is public since instances can be created by classes in
   * different Dragom packages. Instances are meant to be created only by
   * implementations of {@link MutableConfig} and child interfaces. We could
   * enforce this by making the constructor protected and requiring specific
   * subclasses with a package-scope constructor to be implemented for each
   * implementation package, but for now, we keep it simple.
   */
  public SimpleNodeConfigTransferObject() {
    // LinkedHashMap are used to preserve insertion order.
    this.mapPropertyDefConfig = new LinkedHashMap<String, PropertyDefConfig>();
    this.mapPluginDefConfig = new LinkedHashMap<PluginKey, PluginDefConfig>();
  }

  @Override
  public String getName() {
    return this.name;
  }

  @Override
  public void setName(String name) {
    this.name = name;
  }

  @Override
  public PropertyDefConfig getPropertyDefConfig(String name) {
    return this.mapPropertyDefConfig.get(name);
  }

  @Override
  public boolean isPropertyExists(String name) {
    return this.mapPropertyDefConfig.containsKey(name);
  }

  @Override
  public List<PropertyDefConfig> getListPropertyDefConfig() {
    // A copy is returned to prevent the internal Map from being modified by the
    // caller. Ideally, an unmodifiable List view of the Collection returned by
    // Map.values should be returned, but that does not seem possible.
    return new ArrayList<PropertyDefConfig>(this.mapPropertyDefConfig.values());
  }

  @Override
  public void removePropertyDefConfig(String name) {
    this.mapPropertyDefConfig.remove(name);
  }

  @Override
  public boolean setPropertyDefConfig(PropertyDefConfig propertyDefConfig) {
    return (this.mapPropertyDefConfig.put(propertyDefConfig.getName(), propertyDefConfig) == null);
  }

  @Override
  public PluginDefConfig getPluginDefConfig(Class<? extends NodePlugin> classNodePlugin, String pluginId) {
    return this.mapPluginDefConfig.get(new PluginKey(classNodePlugin, pluginId));
  }

  @Override
  public boolean isPluginDefConfigExists(Class<? extends NodePlugin> classNodePlugin, String pluginId) {
    return this.mapPluginDefConfig.containsKey(new PluginKey(classNodePlugin, pluginId));
  }

  @Override
  public List<PluginDefConfig> getListPluginDefConfig() {
    // A copy is returned to prevent the internal Map from being modified by the
    // caller. Ideally, an unmodifiable List view of the Collection returned by
    // Map.values should be returned, but that does not seem possible.
    return new ArrayList<PluginDefConfig>(this.mapPluginDefConfig.values());
  }

  @Override
  public void removePlugingDefConfig(Class<? extends NodePlugin> classNodePlugin, String pluginId) {
    this.mapPluginDefConfig.remove(new PluginKey(classNodePlugin, pluginId));
  }

  @Override
  public boolean setPluginDefConfig(PluginDefConfig pluginDefConfig) {
    PluginKey pluginKey;

    pluginKey = new PluginKey(pluginDefConfig.getClassNodePlugin(), pluginDefConfig.getPluginId());

    return (this.mapPluginDefConfig.put(pluginKey, pluginDefConfig) == null);
  }
}
