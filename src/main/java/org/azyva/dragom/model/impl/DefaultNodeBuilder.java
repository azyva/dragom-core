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

package org.azyva.dragom.model.impl;

import org.azyva.dragom.model.ClassificationNode;
import org.azyva.dragom.model.Node;
import org.azyva.dragom.model.NodeBuilder;

/**
 *
 * @param <NodeSubType> {@link Node} subclass.
 * @author David Raymond
 */
public class DefaultNodeBuilder<NodeSubType extends Node> implements NodeBuilder<NodeSubType> {
  /**
   * DefaultNode being built.
   */
  private DefaultNode defaultNode;

  /**
   * Constructor.
   */
  protected DefaultNodeBuilder() {}


  /**
   * Sets the {@link DefaultNode} being built.
   *
   * <p>To be used by the constructor of subclasses.
   *
   * @param defaultNode DefaultNode.
   */
  protected void setDefaultNode(DefaultNode defaultNode) {
    this.defaultNode = defaultNode;
  }

  @Override
  public NodeBuilder<NodeSubType> setClassificationNodeParent(ClassificationNode classificationNodeParent) {
    this.defaultNode.setDefaultClassificationNodeParent((DefaultClassificationNode)classificationNodeParent);

    return this;
  }

  @Override
  public NodeBuilder<NodeSubType> setName(String name) {
    this.defaultNode.setName(name);

    return this;
  }

  @Override
  public NodeBuilder<NodeSubType> setProperty(String name, String value, boolean indOnlyThisNode) {
    this.defaultNode.setProperty(name, value, indOnlyThisNode);

    return this;
  }

  @Override
  @SuppressWarnings("unchecked")
  public NodeSubType getPartial() {
    if (this.defaultNode.getName() == null) {
      throw new RuntimeException("The name of the node has not been set.");
    }

    if (this.defaultNode.getClassificationNodeParent() == null) {
      throw new RuntimeException("The parent classification node has not been set.");
    }

    return (NodeSubType)this.defaultNode;
  }


  @Override
  @SuppressWarnings("unchecked")
  public NodeSubType create() {
    DefaultNode defaultNode;

    // To take advantage of the validations performed by getPartial.
    this.getPartial();

    ((DefaultClassificationNode)this.defaultNode.getClassificationNodeParent()).addNodeChild(this.defaultNode);
    this.defaultNode.init();

    defaultNode = this.defaultNode;

    // Once the Node is created it must not be possible to use the NodeBuilder
    // anymore.
    this.defaultNode = null;

    return (NodeSubType)defaultNode;
  }
}
