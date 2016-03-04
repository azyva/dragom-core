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

package org.azyva.dragom.model.config.impl.simple;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.azyva.dragom.model.config.ClassificationNodeConfig;
import org.azyva.dragom.model.config.NodeConfig;
import org.azyva.dragom.model.config.NodeTypeEnum;

/**
 * Simple implementation for {@link ClassificationNodeConfig}.
 *
 * @author David Raymond
 * @see org.azyva.dragom.model.config.simple
 */
public class SimpleClassificationNodeConfig extends SimpleNodeConfig implements ClassificationNodeConfig {
	private Map<String, NodeConfig> mapNodeConfigChild;

	/**
	 * Constructor.
	 *
	 * @param name Name.
	 */
	public SimpleClassificationNodeConfig(String name) {
		super(name);

		// LinkedHashMap is used to preserve insertion order.
		this.mapNodeConfigChild = new LinkedHashMap<String, NodeConfig>();
	}

	@Override
	public NodeTypeEnum getNodeType() {
		return NodeTypeEnum.CLASSIFICATION;
	}

	@Override
	public List<NodeConfig> getListChildNodeConfig() {
		// A copy is returned to prevent the internal Map from being modified by the
		// caller.
		return new ArrayList<NodeConfig>(this.mapNodeConfigChild.values());
	}

	@Override
	public NodeConfig getNodeConfigChild(String name) {
		return this.mapNodeConfigChild.get(name);
	}

	/**
	 * Adds a child NodeConfig.
	 * <p>
	 * Any implementation of NodeConfig can be added.
	 *
	 * @param nodeConfig NodeConfig.
	 */
	public void addNodeConfigChild(NodeConfig nodeConfigChild) {
		if (this.mapNodeConfigChild.containsKey(nodeConfigChild.getName())) {
			throw new RuntimeException("Child node configuration " + nodeConfigChild.getName() + " alreaady present in configuration for node " + this.getName() + '.');
		}

		this.mapNodeConfigChild.put(nodeConfigChild.getName(), nodeConfigChild);
	}
}
