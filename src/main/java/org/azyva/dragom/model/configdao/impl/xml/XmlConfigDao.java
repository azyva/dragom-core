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

import org.azyva.dragom.model.config.OptimisticLockHandle;
import org.azyva.dragom.model.configdao.ConfigDao;
import org.azyva.dragom.model.configdao.MutableConfigDao;
import org.azyva.dragom.model.configdao.MutableNodeConfig;
import org.azyva.dragom.model.configdao.NodeConfig;

public class XmlConfigDao implements ConfigDao, MutableConfigDao {

  public XmlConfigDao() {
    // TODO Auto-generated constructor stub
  }

  @Override
  public List<NodeConfig> getListChildNodeConfig(NodeConfig nodeConfigClassificationParent) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public NodeConfig getChildNodeConfig(NodeConfig nodeConfigClassificationParent, String name) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public boolean isChildNodeConfigExists(NodeConfig nodeConfigClassificationParent, String name) {
    // TODO Auto-generated method stub
    return false;
  }

  @Override
  public OptimisticLockHandle createOptimisticLockHandle(NodeConfig nodeConfig) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public MutableNodeConfig getMutableNodeConfig(NodeConfig nodeConfig, OptimisticLockHandle optimisticLockHandle) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public void updateMutableNodeConfig(MutableNodeConfig mutableNodeConfig,
      OptimisticLockHandle optimisticLockHandle) {
    // TODO Auto-generated method stub

  }

  @Override
  public MutableNodeConfig createMutableNodeConfigClassification(NodeConfig nodeConfigClassificationParent) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public MutableNodeConfig createMutableNodeConfigModule(NodeConfig nodeConfigClassificationParent) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public void deleteMutableNodeConfig(MutableNodeConfig mutableNodeConfig, OptimisticLockHandle optimisticLockHandle) {
    // TODO Auto-generated method stub

  }
}
