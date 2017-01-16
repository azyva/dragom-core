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

package org.azyva.dragom.model.config.impl.jpa;

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
 * JPA implementation for {@link ClassificationNodeConfig}.
 *
 * @author David Raymond
 * @see org.azyva.dragom.model.config.impl.jpa
 */
public class JpaClassificationNodeConfig extends JpaNodeConfig implements ClassificationNodeConfig, MutableClassificationNodeConfig {
  /**
   * Containing JpaConfig. null if this JpaClassificationNodeConfig is not
   * the root JpaClassificationNodeConfig.
   */
  JpaConfig jpaConfig;

  /**
   * Map of child {@link NodeConfig}.
   */
  private Map<String, JpaNodeConfig> mapJpaNodeConfigChild;

  /**
   * Default constructor.
   *
   * <p>Required for JPA.
   */
  protected JpaClassificationNodeConfig() {
  }

  /**
   * Constructor for root ClassificationNodeConfig.
   *
   * @param jpaConfig JpaConfig holding this root ClassificationNodeConfig.
   */
  JpaClassificationNodeConfig(JpaConfig jpaConfig) {
    super(null);

    this.jpaConfig = jpaConfig;

    // LinkedHashMap is used to preserve insertion order.
    this.mapJpaNodeConfigChild = new LinkedHashMap<String, JpaNodeConfig>();
  }

  /**
   * Constructor for non-root ClassificationNodeConfig.
   *
   * @param jpaClassificationNodeConfigParent Parent
   *   JpaClassificationNodeConfig.
   */
  public JpaClassificationNodeConfig(JpaClassificationNodeConfig jpaClassificationNodeConfigParent) {
    super(jpaClassificationNodeConfigParent);

    // LinkedHashMap is used to preserve insertion order.
    this.mapJpaNodeConfigChild = new LinkedHashMap<String, JpaNodeConfig>();
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
    return new ArrayList<NodeConfig>(this.mapJpaNodeConfigChild.values());
  }

  @Override
  public NodeConfig getNodeConfigChild(String name) {
    return this.mapJpaNodeConfigChild.get(name);
  }

  @Override
  public void setNodeConfigTransferObject(NodeConfigTransferObject NodeConfigTransferObject, OptimisticLockHandle optimisticLockHandle) throws OptimisticLockException, DuplicateNodeException {
    this.extractNodeConfigTransferObject(NodeConfigTransferObject, optimisticLockHandle);

    if (this.indNew) {
      if (this.jpaConfig != null) {
        this.jpaConfig.setJpaClassificationNodeConfigRoot(this);
      }

      this.indNew = false;
    }
  }

  /**
   * Sets a child {@link NodeConfig}.
   * <p>
   * This method is called by
   * {@link JpaNodeConfig#extractNodeConfigTransferObject}.
   *
   * @param jpaNodeConfigChild Child jpaNodeConfig.
   * @throws DuplicateNodeException When a JpaNodeConfig already exists with the
   *   same name.
   */
  void setJpaNodeConfigChild(JpaNodeConfig jpaNodeConfigChild) throws DuplicateNodeException {
    if (this.mapJpaNodeConfigChild.containsKey(jpaNodeConfigChild.getName())) {
      throw new DuplicateNodeException();
    }

    this.mapJpaNodeConfigChild.put(jpaNodeConfigChild.getName(), jpaNodeConfigChild);
  }

  /**
   * Renames a child {@link NodeConfig}.
   * <p>
   * This method is called by
   * {@link JpaNodeConfig#extractNodeConfigTransferObject}.
   *
   * @param currentName Current name.
   * @param newName New name.
   * @throws DuplicateNodeException When a JpaNodeConfig already exists with the
   *   same name.
   */
  void renameJpaNodeConfigChild(String currentName, String newName) throws DuplicateNodeException {
    if (!this.mapJpaNodeConfigChild.containsKey(currentName)) {
      throw new RuntimeException("JpaNodeConfig with current name " + currentName + " not found.");
    }

    if (this.mapJpaNodeConfigChild.containsKey(newName)) {
      throw new DuplicateNodeException();
    }

    this.mapJpaNodeConfigChild.put(newName, this.mapJpaNodeConfigChild.remove(currentName));
  }

  /**
   * Removes a child {@link NodeConfig}.
   * <p>
   * This method is called by {@link JpaNodeConfig#delete}.
   *
   * @param childNodeName Name of the child NodeConig.
   */
  void removeChildNodeConfig(String childNodeName) {
    if (this.mapJpaNodeConfigChild.remove(childNodeName) == null) {
      throw new RuntimeException("JpaNodeConfig with name " + childNodeName + " not found.");
    }
  }

  /**
   * We need to override delete which is already defined in {@link JpaNodeConfig}
   * since only a JpaClassificationNodeConfig can be a root ClassificationNodeConfig
   * within a {@link Config}.
   */
  @Override
  public void delete() {
    super.delete();

    if (this.jpaConfig != null) {
      this.jpaConfig.setJpaClassificationNodeConfigRoot(null);
      this.jpaConfig = null;
    }
  }

  @Override
  public MutableClassificationNodeConfig createChildMutableClassificationNodeConfig() {
    return new JpaClassificationNodeConfig(this);
  }

  @Override
  public MutableModuleConfig createChildMutableModuleConfig() {
    return new JpaModuleConfig(this);
  }
}
