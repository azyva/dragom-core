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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

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
 * Implementation of {@link ClassificationNodeConfig}
 * that allows reading from an XML file.
 *
 * @author David Raymond
 * @see org.azyva.dragom.model.config.impl.xml
 */
@XmlAccessorType(XmlAccessType.NONE)
@XmlRootElement(name = "classification-node")
public class XmlClassificationNodeConfig extends XmlNodeConfig implements ClassificationNodeConfig, MutableClassificationNodeConfig {
  /**
   * Containing XmlConfig. null if this XmlClassificationNodeConfig is not
   * the root XmlClassificationNodeConfig.
   */
  XmlConfig xmlConfig;

  @XmlElement(name = "child-nodes", type = MapXmlNodeConfigXmAdapter.ListNode.class)
  @XmlJavaTypeAdapter(MapXmlNodeConfigXmAdapter.class)
  Map<String, XmlNodeConfig> mapXmlNodeConfigChild;

  XmlClassificationNodeConfig() {
  }

  /**
   * Constructor for root ClassificationNodeConfig.
   *
   * @param xmlConfig XmlConfig holding this root ClassificationNodeConfig.
   */
  XmlClassificationNodeConfig(XmlConfig xmlConfig) {
    super(null);

    this.xmlConfig = xmlConfig;

    // LinkedHashMap is used to preserve insertion order.
    this.mapXmlNodeConfigChild = new LinkedHashMap<String, XmlNodeConfig>();
  }

  /**
   * Constructor for non-root ClassificationNodeConfig.
   *
   * @param xmlClassificationNodeConfigParent Parent XmlClassificationNodeConfig.
   */
  public XmlClassificationNodeConfig(XmlClassificationNodeConfig xmlClassificationNodeConfigParent) {
    super(xmlClassificationNodeConfigParent);

    // LinkedHashMap is used to preserve insertion order.
    this.mapXmlNodeConfigChild = new LinkedHashMap<String, XmlNodeConfig>();
  }

  @Override
  protected void afterUnmarshal(Unmarshaller unmarshaller, Object parent) {
    super.afterUnmarshal(unmarshaller, parent);

    if (parent instanceof XmlConfig) {
      this.xmlConfig = (XmlConfig)parent;
    }

    // mapNodeConfigChild is assumed to not be null, but after unmarshalling, if no
    // child node is defined (which is often the case) it will not have been assigned.
    if (this.mapXmlNodeConfigChild == null) {
      // If modification of XmlConfig and its members is eventually possible and
      // XmlClassificationNodeConfig becomes mutable, a LinkedHashMap should be used
      // to preserve insertion order.
      this.mapXmlNodeConfigChild = Collections.emptyMap();
    }
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
    return new ArrayList<NodeConfig>(this.mapXmlNodeConfigChild.values());
  }

  @Override
  public NodeConfig getNodeConfigChild(String name) {
    return this.mapXmlNodeConfigChild.get(name);
  }

  @Override
  public void setNodeConfigTransferObject(NodeConfigTransferObject NodeConfigTransferObject, OptimisticLockHandle optimisticLockHandle) throws OptimisticLockException, DuplicateNodeException {
    this.extractNodeConfigTransferObject(NodeConfigTransferObject, optimisticLockHandle);

    if (this.indNew) {
      if (this.xmlConfig != null) {
        this.xmlConfig.setXmlClassificationNodeConfigRoot(this);
      }

      this.indNew = false;
    }
  }

  /**
   * Sets a child {@link NodeConfig}.
   * <p>
   * This method is called by
   * {@link XmlNodeConfig#extractNodeConfigTransferObject}.
   *
   * @param xmlNodeConfigChild Child XmlNodeConfig.
   * @throws DuplicateNodeException When a XmlNodeConfig already exists with the
   *   same name.
   */
  void setXmlNodeConfigChild(XmlNodeConfig xmlNodeConfigChild) throws DuplicateNodeException {
    if (this.mapXmlNodeConfigChild.containsKey(xmlNodeConfigChild.getName())) {
      throw new DuplicateNodeException();
    }

    this.mapXmlNodeConfigChild.put(xmlNodeConfigChild.getName(), xmlNodeConfigChild);
  }

  /**
   * Renames a child {@link NodeConfig}.
   * <p>
   * This method is called by
   * {@link XmlNodeConfig#extractNodeConfigTransferObject}.
   *
   * @param currentName Current name.
   * @param newName New name.
   * @throws DuplicateNodeException When a XmlNodeConfig already exists with the
   *   same name.
   */
  void renameXmlNodeConfigChild(String currentName, String newName) throws DuplicateNodeException {
    if (!this.mapXmlNodeConfigChild.containsKey(currentName)) {
      throw new RuntimeException("XmlNodeConfig with current name " + currentName + " not found.");
    }

    if (this.mapXmlNodeConfigChild.containsKey(newName)) {
      throw new DuplicateNodeException();
    }

    this.mapXmlNodeConfigChild.put(newName, this.mapXmlNodeConfigChild.remove(currentName));
  }

  /**
   * Removes a child {@link NodeConfig}.
   * <p>
   * This method is called by {@link XmlNodeConfig#delete}.
   *
   * @param childNodeName Name of the child NodeConig.
   */
  void removeChildNodeConfig(String childNodeName) {
    if (this.mapXmlNodeConfigChild.remove(childNodeName) == null) {
      throw new RuntimeException("XmlNodeConfig with name " + childNodeName + " not found.");
    }
  }

  /**
   * We need to override delete which is already defined in {@link XmlNodeConfig}
   * since only a XmlClassificationNodeConfig can be a root ClassificationNodeConfig
   * within a {@link Config}.
   */
  @Override
  public void delete() {
    super.delete();

    if (this.xmlConfig != null) {
      this.xmlConfig.setXmlClassificationNodeConfigRoot(null);
      this.xmlConfig = null;
    }
  }

  @Override
  public MutableClassificationNodeConfig createChildMutableClassificationNodeConfig() {
    return new XmlClassificationNodeConfig(this);
  }

  @Override
  public MutableModuleConfig createChildMutableModuleConfig() {
    return new XmlModuleConfig(this);
  }
}
