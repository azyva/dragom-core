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

import org.azyva.dragom.model.config.DuplicateNodeException;
import org.azyva.dragom.model.config.ModuleConfig;
import org.azyva.dragom.model.config.MutableModuleConfig;
import org.azyva.dragom.model.config.NodeConfigTransferObject;
import org.azyva.dragom.model.config.NodeType;
import org.azyva.dragom.model.config.OptimisticLockException;
import org.azyva.dragom.model.config.OptimisticLockHandle;

/**
 * Simple implementation for {@link ModuleConfig}.
 *
 * @author David Raymond
 * @see org.azyva.dragom.model.config.impl.simple
 */
public class SimpleModuleConfig extends SimpleNodeConfig implements ModuleConfig, MutableModuleConfig  {
  /**
   * Constructor.
   *
   * @param simpleClassificationNodeConfigParent Parent
   *   SimpleClassificationNodeConfig.
   */
  SimpleModuleConfig(SimpleClassificationNodeConfig simpleClassificationNodeConfigParent) {
    super(simpleClassificationNodeConfigParent);
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
