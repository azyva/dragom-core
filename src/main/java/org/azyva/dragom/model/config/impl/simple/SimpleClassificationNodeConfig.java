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
import org.azyva.dragom.model.config.ClassificationNodeConfigValue;
import org.azyva.dragom.model.config.MutableClassificationNodeConfig;
import org.azyva.dragom.model.config.MutableModuleConfig;
import org.azyva.dragom.model.config.NodeConfig;
import org.azyva.dragom.model.config.NodeConfigValue;
import org.azyva.dragom.model.config.NodeType;

/**
 * Simple implementation for {@link ClassificationNodeConfig}.
 *
 * @author David Raymond
 * @see org.azyva.dragom.model.config.simple
 */
public class SimpleClassificationNodeConfig extends SimpleNodeConfig implements ClassificationNodeConfig, MutableClassificationNodeConfig {
	SimpleConfig simpleConfig;

	private Map<String, NodeConfig> mapNodeConfigChild;

	/**
	 * Constructor for non-root ClassificationNodeConfig.
	 *
	 * @param simpleClassificationNodeConfigParent Parent
	 *   SimpleClassificationNodeConfig.
	 */
	public SimpleClassificationNodeConfig(SimpleClassificationNodeConfig simpleClassificationNodeConfigParent) {
		super(simpleClassificationNodeConfigParent);

		// LinkedHashMap is used to preserve insertion order.
		this.mapNodeConfigChild = new LinkedHashMap<String, NodeConfig>();
	}

	/**
	 * Constructor for root ClassificationNodeConfig.
	 *
	 * @param simpleConfig SimpleConfig holding this root ClassificationNodeConfig.
	 */
	public SimpleClassificationNodeConfig(SimpleConfig simpleConfig) {
		super(null);

		this.simpleConfig = simpleConfig;

		// LinkedHashMap is used to preserve insertion order.
		this.mapNodeConfigChild = new LinkedHashMap<String, NodeConfig>();
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

	/**
	 * Sets a child {@link NodeConfig}.
	 * <p>
	 * If one already exists with the same name, it is overwritten. Otherwise it is
	 * added.
	 * <p>
	 * This method is intended to be called by
	 * {@link SimpleNodeConfig#setNodeConfigValue}.
	 *
	 * @param nodeConfigChild.
	 */
	public void setNodeConfigChild(NodeConfig nodeConfigChild) {
		this.mapNodeConfigChild.put(nodeConfigChild.getName(), nodeConfigChild);
	}

	@Override
	public NodeConfigValue getNodeConfigValue() {
		return this.getClassificationNodeConfigValue();
	}

	@Override
	public ClassificationNodeConfigValue getClassificationNodeConfigValue() {
		ClassificationNodeConfigValue classificationNodeConfigValue;

		classificationNodeConfigValue = new ClassificationNodeConfigValue();

		this.fillNodeConfigValue(classificationNodeConfigValue);

		return classificationNodeConfigValue;
	}

	@Override
	public void setClassificationNodeConfigValue(ClassificationNodeConfigValue classificationNodeConfigValue) {
		this.setNodeConfigValue(classificationNodeConfigValue);
	}

	@Override
	public void deleteChildNodeConfig(String childNodeName) {
		this.mapNodeConfigChild.remove(childNodeName);
	}

	@Override
	public MutableModuleConfig createChildModuleConfig() {
		return new SimpleModuleConfig(this);
	}

	@Override
	public MutableClassificationNodeConfig createChildClassificationNodeConfig() {
		return new SimpleClassificationNodeConfig(this);
	}
}
