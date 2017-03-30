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

import org.azyva.dragom.model.config.ClassificationNodeConfig;
import org.azyva.dragom.model.config.Config;
import org.azyva.dragom.model.config.MutableClassificationNodeConfig;
import org.azyva.dragom.model.config.MutableConfig;


/**
 * Simple implementation of {@link Config} and {@link MutableConfig}.
 *
 * @author David Raymond
 * @see org.azyva.dragom.model.config.impl.simple
 */
public class SimpleConfig implements Config, MutableConfig {
  /**
   * Root SimpleClassificationNodeConfig.
   */
  SimpleClassificationNodeConfig simpleClassificationNodeConfigRoot;

  @Override
  public ClassificationNodeConfig getClassificationNodeConfigRoot() {
    return this.simpleClassificationNodeConfigRoot;
  }

  /**
   * Sets the root {@link SimpleClassificationNodeConfig}.
   * <p>
   * This method is intended to be called by
   * {@link SimpleNodeConfig#setNodeConfigTransferObject}.
   *
   * @param simpleClassificationNodeConfigRoot Root SimpleClassificationNodeConfig.
   */
  void setSimpleClassificationNodeConfigRoot(SimpleClassificationNodeConfig simpleClassificationNodeConfigRoot) {
    if (this.simpleClassificationNodeConfigRoot != null && simpleClassificationNodeConfigRoot != null) {
      throw new RuntimeException("Replacing the root SimpleClassificationNodeConfig is not allowed.");
    }

    // Setting this.simplClassificationNodeRoot to null is allowed since this
    // can happen when deleting the root DefaultClassificationNode.
    this.simpleClassificationNodeConfigRoot = simpleClassificationNodeConfigRoot;
  }

  @Override
  public MutableClassificationNodeConfig createMutableClassificationNodeConfigRoot() {
    return new SimpleClassificationNodeConfig(this);
  }

  @Override
  public void flush() {
    // There is nothing to flush with this memory-based MutableConfig.
  }
}
