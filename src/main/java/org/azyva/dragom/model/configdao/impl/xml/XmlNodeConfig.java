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

package org.azyva.dragom.model.configdao.impl.xml;

import java.util.List;

import org.azyva.dragom.model.config.NodeType;
import org.azyva.dragom.model.config.PluginDefConfig;
import org.azyva.dragom.model.config.PluginKey;
import org.azyva.dragom.model.config.PropertyDefConfig;
import org.azyva.dragom.model.configdao.MutableNodeConfig;
import org.azyva.dragom.model.configdao.NodeConfig;

public class XmlNodeConfig implements NodeConfig, MutableNodeConfig {

  public XmlNodeConfig() {
    // TODO Auto-generated constructor stub
  }

  @Override
  public NodeType getNodeType() {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public String getName() {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public PropertyDefConfig getPropertyDefConfig(String name) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public boolean isPropertyDefConfigExists(String name) {
    // TODO Auto-generated method stub
    return false;
  }

  @Override
  public List<PropertyDefConfig> getListPropertyDefConfig() {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public PluginDefConfig getPluginDefConfig(PluginKey pluginKey) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public boolean isPluginDefConfigExists(PluginKey pluginKey) {
    // TODO Auto-generated method stub
    return false;
  }

  @Override
  public List<PluginDefConfig> getListPluginDefConfig() {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public void setName(String name) {
    // TODO Auto-generated method stub

  }

  @Override
  public void setPropertyDefConfig(PropertyDefConfig propertyDefConfig) {
    // TODO Auto-generated method stub

  }

  @Override
  public void removePropertyDefConfig(String name) {
    // TODO Auto-generated method stub

  }

  @Override
  public void setPluginDefConfig(PluginDefConfig pluginDefConfig) {
    // TODO Auto-generated method stub

  }

  @Override
  public void removePluginDefConfig(PluginKey pluginKey) {
    // TODO Auto-generated method stub

  }

}
