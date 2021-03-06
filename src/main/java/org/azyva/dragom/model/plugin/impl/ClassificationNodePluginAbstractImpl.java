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

package org.azyva.dragom.model.plugin.impl;

import org.azyva.dragom.model.ClassificationNode;
import org.azyva.dragom.model.plugin.ClassificationNodePlugin;

/**
 * Abstract class to help implement {@link ClassificationNodePlugin}.
 *
 * @author David Raymond
 */
public abstract class ClassificationNodePluginAbstractImpl extends NodePluginAbstractImpl implements ClassificationNodePlugin {
  /**
   * Constructor.
   *
   * @param classificationNode ClassificationNode.
   */
  protected ClassificationNodePluginAbstractImpl(ClassificationNode classificationNode) {
    super(classificationNode);
  }

  @Override
  public ClassificationNode getClassificationNode() {
    return (ClassificationNode)this.getNode();
  }

  /**
   * @return String to help recognize the ClassificationNodePlugin instance, in logs
   * for example.
   */
  @Override
  public String toString() {
    return this.getClass().getName() + " extends ClassificationNodePluginAbstractImpl [classificationNode=" + this.getClassificationNode() + "]";
  }
}
