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
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

import org.azyva.dragom.model.config.ClassificationNodeConfig;
import org.azyva.dragom.model.config.NodeConfig;
import org.azyva.dragom.model.config.NodeType;

/**
 * Implementation of {@link ClassificationNodeConfig}
 * that allows reading from an XML file.
 *
 * @author David Raymond
 * @see org.azyva.dragom.model.config.xml
 */
@XmlAccessorType(XmlAccessType.NONE)
@XmlRootElement(name = "classification-node")
public class XmlClassificationNodeConfig extends XmlNodeConfig implements ClassificationNodeConfig {
  @XmlElement(name = "child-nodes", type = MapXmlNodeConfigXmAdapter.ListNode.class)
  @XmlJavaTypeAdapter(MapXmlNodeConfigXmAdapter.class)
  Map<String, NodeConfig> mapNodeConfigChild;

  @Override
  protected void afterUnmarshal(Unmarshaller unmarshaller, Object parent) {
    super.afterUnmarshal(unmarshaller, parent);

    // mapNodeConfigChild is assumed to not be null, but after unmarshalling, if no
    // child node is defined (which is often the case) it will not have been assigned.
    if (this.mapNodeConfigChild == null) {
      // If modification of XmlConfig and its members is eventually possible and
      // XmlClassificationNodeConfig becomes mutable, a LinkedHashMap should be used
      // to preserve insertion order.
      this.mapNodeConfigChild = Collections.emptyMap();
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
    return new ArrayList<NodeConfig>(this.mapNodeConfigChild.values());
  }

  @Override
  public NodeConfig getNodeConfigChild(String name) {
    return this.mapNodeConfigChild.get(name);
  }
}
