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

package org.azyva.dragom.model.config.impl.xml;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

import org.azyva.dragom.model.config.ClassificationNodeConfig;
import org.azyva.dragom.model.config.ModuleConfig;
import org.azyva.dragom.model.config.NodeConfig;
import org.azyva.dragom.model.config.PluginDefConfig;
import org.azyva.dragom.model.config.PluginKey;
import org.azyva.dragom.model.config.PropertyDefConfig;
import org.azyva.dragom.model.plugin.NodePlugin;

/**
 * Implementation of {@link NodeConfig} that allows reading from an XML file.
 *
 * @author David Raymond
 * @see org.azyva.dragom.model.config.impl.xml
 */
@XmlAccessorType(XmlAccessType.NONE)
public abstract class XmlNodeConfig implements NodeConfig {
  @XmlElement(name = "name")
  private String name;

  @XmlElement(name = "properties", type = MapXmlPropertyDefConfigAdapter.ListProperty.class)
  @XmlJavaTypeAdapter(MapXmlPropertyDefConfigAdapter.class)
  private Map<String, PropertyDefConfig> mapPropertyDefConfig;

  @XmlElement(name = "plugins", type = MapXmlPluginDefConfigAdapter.ListPluginDefConfigXml.class)
  @XmlJavaTypeAdapter(MapXmlPluginDefConfigAdapter.class)
  private Map<PluginKey, PluginDefConfig> mapPluginDefConfig;

  protected void afterUnmarshal(Unmarshaller unmarshaller, Object parent) {
    // mapPropertyDefConfig and mapPluginDefConfig are assumed to not be null, but
    // after unmarshalling, if no property or plugin is defined (which is often the
    // case) they will not have been assigned.

    if (this.mapPropertyDefConfig == null) {
      // If modification of XmlConfig and its members is eventually possible and
      // XmlNodeConfig becomes mutable, a LinkedHashMap should be used to preserve
      // insertion order.
      this.mapPropertyDefConfig = Collections.emptyMap();
    }

    if (this.mapPluginDefConfig == null) {
      // Same comment as for mapPropertyDefConfig above.
      this.mapPluginDefConfig = Collections.emptyMap();
    }
  }

  @Override
  public String getName() {
    return this.name;
  }

  @Override
  public PropertyDefConfig getPropertyDefConfig(String name) {
    // XmlPropertyDefConfig is currently immutable. If modification of XmlConfig and
    // its members is eventually possible and XmlPropertyDefConfig becomes mutable, a
    // copy may need to be returned.
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
  public PluginDefConfig getPluginDefConfig(Class<? extends NodePlugin> classNodePlugin, String pluginId) {
    // XmlPluginDefConfig is currently immutable. If modification of XmlConfig and its
    // members is eventually possible and XmlPluginDefConfig becomes mutable, a copy
    // may need to be returned.
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

  /**
   * @return String to help recognize the
   *   {@link NodeConfig} instance, in logs for example. The subclasses
   *   {@link ClassificationNodeConfig} and {@link ModuleConfig} do not need to
   *   override this method as it includes the actual class name of the instance.
   */
  @Override
  public String toString() {
    return this.getClass().getSimpleName() + " [name=" + this.name + "]";
  }
}

