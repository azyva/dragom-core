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

import org.azyva.dragom.model.config.ClassificationNodeConfig;
import org.azyva.dragom.model.config.Config;
import org.azyva.dragom.model.config.DuplicateNodeException;
import org.azyva.dragom.model.config.MutableClassificationNodeConfig;
import org.azyva.dragom.model.config.MutableModuleConfig;
import org.azyva.dragom.model.config.NodeConfig;
import org.azyva.dragom.model.config.NodeConfigTransferObject;
import org.azyva.dragom.model.config.NodeType;
import org.azyva.dragom.model.config.OptimisticLockException;
import org.azyva.dragom.model.config.OptimisticLockHandle;

/**
 * Simple implementation for {@link ClassificationNodeConfig}.
 *
 * @author David Raymond
 * @see org.azyva.dragom.model.config.impl.simple
 */
public class SimpleClassificationNodeConfig extends SimpleNodeConfig implements ClassificationNodeConfig, MutableClassificationNodeConfig {
  /**
   * Containing SimpleConfig. null if this SimpleClassificationNodeConfig is not
   * the root SimpleClassificationNodeConfig.
   */
  SimpleConfig simpleConfig;

  /**
   * Map of child {@link NodeConfig}.
   */
  private Map<String, SimpleNodeConfig> mapSimpleNodeConfigChild;

  /**
   * Constructor for root ClassificationNodeConfig.
   *
   * @param simpleConfig SimpleConfig holding this root ClassificationNodeConfig.
   */
  public SimpleClassificationNodeConfig(SimpleConfig simpleConfig) {
    super(null);

    this.simpleConfig = simpleConfig;

    // LinkedHashMap is used to preserve insertion order.
    this.mapSimpleNodeConfigChild = new LinkedHashMap<String, SimpleNodeConfig>();
  }

  /**
   * Constructor for non-root ClassificationNodeConfig.
   *
   * @param simpleClassificationNodeConfigParent Parent
   *   SimpleClassificationNodeConfig.
   */
  public SimpleClassificationNodeConfig(SimpleClassificationNodeConfig simpleClassificationNodeConfigParent) {
    super(simpleClassificationNodeConfigParent);

    // LinkedHashMap is used to preserve insertion order.
    this.mapSimpleNodeConfigChild = new LinkedHashMap<String, SimpleNodeConfig>();
  }

  @Override
  public NodeType getNodeType() {
    return NodeType.CLASSIFICATION;
  }

  @Override
  public List<NodeConfig> getListChildNodeConfig() {
    // A copy is returned to prevent the internal Map from being modified by the
    // caller. Ideally, an unmodifiable List view of the Collection returned by
    // Map.values should be returned, but that does not seem possible.
    return new ArrayList<NodeConfig>(this.mapSimpleNodeConfigChild.values());
  }

  @Override
  public NodeConfig getNodeConfigChild(String name) {
    return this.mapSimpleNodeConfigChild.get(name);
  }

  @Override
  public void setNodeConfigTransferObject(NodeConfigTransferObject NodeConfigTransferObject, OptimisticLockHandle optimisticLockHandle) throws OptimisticLockException, DuplicateNodeException {
    this.extractNodeConfigTransferObject(NodeConfigTransferObject, optimisticLockHandle);

    if (this.indNew) {
      if (this.simpleConfig != null) {
        this.simpleConfig.setSimpleClassificationNodeConfigRoot(this);
      }

      this.indNew = false;
    }
  }

  /**
   * Sets a child {@link NodeConfig}.
   * <p>
   * This method is called by
   * {@link SimpleNodeConfig#extractNodeConfigTransferObject}.
   *
   * @param simpleNodeConfigChild Child SimpleNodeConfig.
   * @throws DuplicateNodeException When a SimpleNodeConfig already exists with the
   *   same name.
   */
  void setSimpleNodeConfigChild(SimpleNodeConfig simpleNodeConfigChild) throws DuplicateNodeException {
    if (this.mapSimpleNodeConfigChild.containsKey(simpleNodeConfigChild.getName())) {
      throw new DuplicateNodeException();
    }

    this.mapSimpleNodeConfigChild.put(simpleNodeConfigChild.getName(), simpleNodeConfigChild);
  }

  /**
   * Renames a child {@link NodeConfig}.
   * <p>
   * This method is called by
   * {@link SimpleNodeConfig#extractNodeConfigTransferObject}.
   *
   * @param currentName Current name.
   * @param newName New name.
   * @throws DuplicateNodeException When a SimpleNodeConfig already exists with the
   *   same name.
   */
  void renameSimpleNodeConfigChild(String currentName, String newName) throws DuplicateNodeException {
    if (!this.mapSimpleNodeConfigChild.containsKey(currentName)) {
      throw new RuntimeException("SimpleNodeConfig with current name " + currentName + " not found.");
    }

    if (this.mapSimpleNodeConfigChild.containsKey(newName)) {
      throw new DuplicateNodeException();
    }

    this.mapSimpleNodeConfigChild.put(newName, this.mapSimpleNodeConfigChild.remove(currentName));
  }

  /**
   * Removes a child {@link NodeConfig}.
   * <p>
   * This method is called by {@link SimpleNodeConfig#delete}.
   *
   * @param childNodeName Name of the child NodeConig.
   */
  void removeChildNodeConfig(String childNodeName) {
    if (this.mapSimpleNodeConfigChild.remove(childNodeName) == null) {
      throw new RuntimeException("SimpleNodeConfig with name " + childNodeName + " not found.");
    }
  }

  /**
   * We need to override delete which is already defined in {@link SimpleNodeConfig}
   * since only a SimpleClassificationNodeConfig can be a root ClassificationNodeConfig
   * within a {@link Config}.
   */
  @Override
  public void delete() {
    super.delete();

    if (this.simpleConfig != null) {
      this.simpleConfig.setSimpleClassificationNodeConfigRoot(null);
      this.simpleConfig = null;
    }
  }

  @Override
  public MutableClassificationNodeConfig createChildMutableClassificationNodeConfig() {
    return new SimpleClassificationNodeConfig(this);
  }

  @Override
  public MutableModuleConfig createChildMutableModuleConfig() {
    return new SimpleModuleConfig(this);
  }
}
