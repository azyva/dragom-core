/*
 * Copyright 2015 AZYVA INC.
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

package org.azyva.dragom.model.impl.simple;

import org.azyva.dragom.model.ClassificationNode;
import org.azyva.dragom.model.Node;
import org.azyva.dragom.model.NodeBuilder;

/**
 *
 * @author David Raymond
 */
public class SimpleNodeBuilder<NodeSubType extends Node> implements NodeBuilder<NodeSubType> {
	private SimpleNode simpleNode;

	protected SimpleNodeBuilder() {
	}

	protected void setSimpleNode(SimpleNode simpleNode) {
		this.simpleNode = simpleNode;
	}

	@Override
	public NodeBuilder<NodeSubType> setClassificationNodeParent(ClassificationNode classificationNodeParent) {
		this.simpleNode.setSimpleClassificationNodeParent((SimpleClassificationNode)classificationNodeParent);

		return this;
	}

	@Override
	public NodeBuilder<NodeSubType> setName(String name) {
		this.simpleNode.setName(name);

		return this;
	}

	@Override
	public NodeBuilder<NodeSubType> setProperty(String name, String value, boolean indOnlyThisNode) {
		this.simpleNode.setProperty(name, value, indOnlyThisNode);

		return this;
	}

	@Override
	@SuppressWarnings("unchecked")
	public NodeSubType getPartial() {
		if (this.simpleNode.getName() == null) {
			throw new RuntimeException("The name of the node has not been set.");
		}

		if (this.simpleNode.getClassificationNodeParent() == null) {
			throw new RuntimeException("The parent classification node has not been set.");
		}

		return (NodeSubType)this.simpleNode;
	}


	@Override
	@SuppressWarnings("unchecked")
	public NodeSubType create() {
		SimpleNode simpleNode;

		// To take advantage of the validations performed by getPartial.
		this.getPartial();

		((SimpleClassificationNode)this.simpleNode.getClassificationNodeParent()).addNodeChild(this.simpleNode);
		this.simpleNode.init();

		simpleNode = this.simpleNode;

		// Once the Node is created it must not be possible to use the NodeBuilder
		// anymore.
		this.simpleNode = null;

		return (NodeSubType)simpleNode;
	}
}
