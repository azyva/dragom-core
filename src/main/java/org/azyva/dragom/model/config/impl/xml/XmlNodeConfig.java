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
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

import org.azyva.dragom.model.MutableNode;
import org.azyva.dragom.model.config.ClassificationNodeConfig;
import org.azyva.dragom.model.config.DuplicateNodeException;
import org.azyva.dragom.model.config.ModuleConfig;
import org.azyva.dragom.model.config.MutableNodeConfig;
import org.azyva.dragom.model.config.NodeConfig;
import org.azyva.dragom.model.config.NodeConfigTransferObject;
import org.azyva.dragom.model.config.OptimisticLockException;
import org.azyva.dragom.model.config.OptimisticLockHandle;
import org.azyva.dragom.model.config.PluginDefConfig;
import org.azyva.dragom.model.config.PluginKey;
import org.azyva.dragom.model.config.PropertyDefConfig;
import org.azyva.dragom.model.config.impl.simple.SimpleClassificationNodeConfig;
import org.azyva.dragom.model.config.impl.simple.SimpleNodeConfig;
import org.azyva.dragom.model.config.impl.simple.SimpleNodeConfigTransferObject;
import org.azyva.dragom.model.plugin.NodePlugin;

/**
 * Implementation of {@link NodeConfig} that allows reading from an XML file.
 *
 * @author David Raymond
 * @see org.azyva.dragom.model.config.impl.xml
 */
@XmlAccessorType(XmlAccessType.NONE)
public abstract class XmlNodeConfig implements NodeConfig, MutableNodeConfig {
  /**
   * Indicates that the {@link SimpleNodeConfig} is new and has not been finalized
   * yet. This is the state in which it is after having been created using the
   * create methods of {@link XmlConfig} or
   * {@link SimpleClassificationNodeConfig}.
   */
  protected boolean indNew;

  /**
   * Parent {@link SimpleClassificationNodeConfig}.
   */
  private XmlClassificationNodeConfig xmlClassificationNodeConfigParent;

  /**
   * Unique revision number to manage optimistic locking (see
   * {@link OptimisticLockHandle}).
   * <p>
   * Starts at since 0 within OptimisticLockHandle means not locked.
   */
  protected int revision;

  @XmlElement(name = "name")
  private String name;

  @XmlElement(name = "properties", type = MapXmlPropertyDefConfigAdapter.ListProperty.class)
  @XmlJavaTypeAdapter(MapXmlPropertyDefConfigAdapter.class)
  private Map<String, PropertyDefConfig> mapPropertyDefConfig;

  @XmlElement(name = "plugins", type = MapXmlPluginDefConfigAdapter.ListPluginDefConfigXml.class)
  @XmlJavaTypeAdapter(MapXmlPluginDefConfigAdapter.class)
  private Map<PluginKey, PluginDefConfig> mapPluginDefConfig;

  /**
   * Default constructor for JAXB.
   */
  XmlNodeConfig() {
    this.revision = 1;
  }

  /**
   * Constructor.
   *
   * @param xmlClassificationNodeConfigParent Parent XmlClassificationNodeConfig.
   */
  XmlNodeConfig(XmlClassificationNodeConfig xmlClassificationNodeConfigParent) {
    this.indNew = true;

    this.xmlClassificationNodeConfigParent = xmlClassificationNodeConfigParent;

    this.revision = 1;

    // LinkedHashMap are used to preserve insertion order.
    this.mapPropertyDefConfig = new LinkedHashMap<String, PropertyDefConfig>();
    this.mapPluginDefConfig = new LinkedHashMap<PluginKey, PluginDefConfig>();
  }

  protected void afterUnmarshal(Unmarshaller unmarshaller, Object parent) {
    if (!(parent instanceof XmlConfig) && ((this.name == null) || this.name.isEmpty())) {
      throw new RuntimeException("Node cannot have null or empty name. Parent: " + parent);
    }

    // xmlClassificationNodeConfigParent is set here in the base class if parent is
    // indeed a XmlClassificationNodeConfig since this applies to all Node types.
    // The other case where parent is XmlConfig is handled in
    // XmlClassificationNodeConfig since this case is possible only for
    // XmlClassificationNodeConfig.
    if (parent instanceof XmlClassificationNodeConfig) {
      this.xmlClassificationNodeConfigParent = (XmlClassificationNodeConfig)parent;
    }

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

  @Override
  public boolean isNew() {
    return this.indNew;
  }

  private enum OptimisticLockCheckContext {
    /**
     * Getting a {@link NodeConfigTransferObject} on an existing XmlNodeConfig.
     */
    GET,

    /**
     * Getting or setting a {@link NodeConfigTransferObject} on a new
     * XmlNodeConfig.
     */
    NEW,

    /**
     * Updating an existing XmlNodeConfig by setting a
     * {@link NodeConfigTransferObject}.
     */
    UPDATE
  }

  /**
   * Check whether the {@link XmlOptimisticLockHandle} corresponds to the current
   * state of the data it represents.
   * <p>
   * If xmlOptimisticLockHandle is null, nothing is done.
   * <p>
   * If optimisticLockCheckContext is NEW, xmlOptimisticLockHandle must not be
   * locked.
   * <p>
   * If optimisticLockCheckContext is UPDATE, xmlOptimisticLockHandle must be
   * locked.
   * <p>
   * If xmlOptimisticLockHandle is not null and is locked
   * ({@link XmlOptimisticLockHandle#isLocked}), its state must correspond to the
   * state of the data it represents, otherwise {@link OptimisticLockException} is
   * thrown.
   * <p>
   * If xmlOptimisticLockHandle is not null and is not locked, it is simply locked
   * to the current state of the data.
   *
   * @param xmlOptimisticLockHandle XmlOptimisticLockHandle. Can be null.
   * @param optimisticLockCheckContext OptimisticLockCheckContext.
   */
  protected void checkOptimisticLock(XmlOptimisticLockHandle xmlOptimisticLockHandle, OptimisticLockCheckContext optimisticLockCheckContext) {
    if (xmlOptimisticLockHandle != null) {
      if (xmlOptimisticLockHandle.isLocked()) {
        if (optimisticLockCheckContext == OptimisticLockCheckContext.NEW) {
          throw new RuntimeException("OptimisticLockHandle must not be locked for a new XmlNodeConfig.");
        }

        if (xmlOptimisticLockHandle.getRevision() != this.revision) {
          throw new OptimisticLockException();
        }
      } else {
        if (optimisticLockCheckContext == OptimisticLockCheckContext.UPDATE) {
          throw new RuntimeException("OptimisticLockHandle must be locked for an existing XmlNodeConfig.");
        }

        xmlOptimisticLockHandle.setRevision(this.revision);
      }
    }
  }

  @Override
  public OptimisticLockHandle createOptimisticLockHandle(boolean indLock) {
    return new XmlOptimisticLockHandle(indLock ? this.revision : 0);
  }

  @Override
  public boolean isOptimisticLockValid(OptimisticLockHandle optimisticLockHandle) {
    return (((XmlOptimisticLockHandle)optimisticLockHandle).getRevision() == this.revision);
  }

  @Override
  public NodeConfigTransferObject getNodeConfigTransferObject(OptimisticLockHandle optimisticLockHandle)
      throws OptimisticLockException {
    NodeConfigTransferObject nodeConfigTransferObject;

    this.checkOptimisticLock((XmlOptimisticLockHandle)optimisticLockHandle, this.indNew ? OptimisticLockCheckContext.NEW : OptimisticLockCheckContext.GET);

    nodeConfigTransferObject = new SimpleNodeConfigTransferObject();

    nodeConfigTransferObject.setName(this.name);

    for(PropertyDefConfig propertyDefConfig: this.mapPropertyDefConfig.values()) {
      nodeConfigTransferObject.setPropertyDefConfig(propertyDefConfig);
    }

    for(PluginDefConfig pluginDefConfig: this.mapPluginDefConfig.values()) {
      nodeConfigTransferObject.setPluginDefConfig(pluginDefConfig);
    }

    return nodeConfigTransferObject;
  }

  /**
   * Called by subclasses to extract the data from a {@link NodeConfigTransferObject} and set
   * them within the XmlNodeConfig.
   * <p>
   * Uses the indNew variable, but does not reset it. It is intended to be reset by
   * the subclass caller method, {@link MutableNodeConfig#setNodeConfigTransferObject}.
   * <p>
   * The reason for not directly implementing
   * MutableNodeConfig.setNodeConfigValueTransferObject is that subclasses can have
   * other tasks to perform.
   * <p>
   * If optimisticLockHandle is null, no optimistic lock is managed.
   * <p>
   * If optimisticLockHandle is not null, it must be locked
   * ({@link OptimisticLockHandle#isLocked}) and its state must correspond to the
   * state of the data it represents, otherwise {@link OptimisticLockException} is
   * thrown. The state of the OptimisticLockHandle is updated to the new revision of
   * the XmlNodeConfig.
   *
   * @param nodeConfigTransferObject NodeConfigTransferObject.
   * @param optimisticLockHandle OptimisticLockHandle. Can be null.
   * @throws OptimisticLockException Can be thrown only if optimisticLockHandle is
   *   not null. This is a RuntimeException that may be of interest to
   *   the caller.
   * @throws DuplicateNodeException When the new configuration data would introduce
   *   a duplicate {@link MutableNode} within the parent. This is a RuntimeException
   *   that may be of interest to the caller.
   */
  protected void extractNodeConfigTransferObject(NodeConfigTransferObject nodeConfigTransferObject, OptimisticLockHandle optimisticLockHandle)
      throws DuplicateNodeException {
    String previousName;

    this.checkOptimisticLock((XmlOptimisticLockHandle)optimisticLockHandle, this.indNew ? OptimisticLockCheckContext.NEW : OptimisticLockCheckContext.UPDATE);

    if ((nodeConfigTransferObject.getName() == null) && (this.xmlClassificationNodeConfigParent != null)) {
      throw new RuntimeException("Name of NodeConfigTrnmsferObject must not be null for non-root XmlClassificationNodeConfig.");
    }

    previousName = this.name;
    this.name = nodeConfigTransferObject.getName();

    if (this.indNew) {
      if (this.xmlClassificationNodeConfigParent != null) {
        this.xmlClassificationNodeConfigParent.setXmlNodeConfigChild(this);
      }
    } else {
      if ((this.xmlClassificationNodeConfigParent != null) && (!this.name.equals(previousName))) {
        this.xmlClassificationNodeConfigParent.renameXmlNodeConfigChild(previousName, this.name);
      }
    }

    this.mapPropertyDefConfig.clear();

    for(PropertyDefConfig propertyDefConfig: nodeConfigTransferObject.getListPropertyDefConfig()) {
      this.mapPropertyDefConfig.put(propertyDefConfig.getName(),  propertyDefConfig);
    }

    this.mapPluginDefConfig.clear();

    for(PluginDefConfig pluginDefConfig: nodeConfigTransferObject.getListPluginDefConfig()) {
      this.mapPluginDefConfig.put(new PluginKey(pluginDefConfig.getClassNodePlugin(), pluginDefConfig.getPluginId()), pluginDefConfig);
    }

    if (!this.indNew) {
      this.revision++;
    }

    if (optimisticLockHandle != null) {
      ((XmlOptimisticLockHandle)optimisticLockHandle).setRevision(this.revision);
    }
  }

  @Override
  public void delete() {
    if (!this.indNew && (this.xmlClassificationNodeConfigParent != null)) {
      this.xmlClassificationNodeConfigParent.removeChildNodeConfig(this.name);
      this.xmlClassificationNodeConfigParent = null;
    }
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

