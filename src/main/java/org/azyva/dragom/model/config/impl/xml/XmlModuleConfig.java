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

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlRootElement;

import org.azyva.dragom.model.config.DuplicateNodeException;
import org.azyva.dragom.model.config.ModuleConfig;
import org.azyva.dragom.model.config.MutableModuleConfig;
import org.azyva.dragom.model.config.NodeConfigTransferObject;
import org.azyva.dragom.model.config.NodeType;
import org.azyva.dragom.model.config.OptimisticLockException;
import org.azyva.dragom.model.config.OptimisticLockHandle;

/**
 * Implementation for {@link ModuleConfig} that allows reading from an XML file.
 *
 * @author David Raymond
 * @see org.azyva.dragom.model.config.impl.xml
 */
@XmlAccessorType(XmlAccessType.NONE)
@XmlRootElement(name = "module")
public class XmlModuleConfig extends XmlNodeConfig implements ModuleConfig, MutableModuleConfig {
  /**
   * Default constructor for JAXB.
   */
  public XmlModuleConfig() {
  }

  /**
   * Constructor.
   *
   * @param xmlClassificationNodeConfigParent Parent XmlClassificationNodeConfig.
   */
  XmlModuleConfig(XmlClassificationNodeConfig xmlClassificationNodeConfigParent) {
    super(xmlClassificationNodeConfigParent);
  }

  @Override
  public NodeType getNodeType() {
    return NodeType.MODULE;
  }

  @Override
  public void setNodeConfigTransferObject(NodeConfigTransferObject nodeConfigTransferObject, OptimisticLockHandle optimisticLockHandle) throws OptimisticLockException, DuplicateNodeException {
    this.extractNodeConfigTransferObject(nodeConfigTransferObject, optimisticLockHandle);

    this.indNew = false;
  }
}
