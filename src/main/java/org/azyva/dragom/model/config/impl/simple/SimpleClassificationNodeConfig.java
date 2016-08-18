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
import org.azyva.dragom.model.config.Config;
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
	void setNodeConfigChild(NodeConfig nodeConfigChild) {
		this.mapNodeConfigChild.put(nodeConfigChild.getName(), nodeConfigChild);
	}

	/**
	 * Renames a child {@link NodeConfig}.
	 * <p>
	 * This method is intended to be called by
	 * {@link SimpleNodeConfig#setNodeConfigValue}.
	 *
	 * @param currentName Current name.
	 * @param newName New name.
	 */
	void renameNodeConfigChild(String currentName, String newName) {
		NodeConfig nodeConfigChild;

		nodeConfigChild = this.mapNodeConfigChild.remove(currentName);

		if (nodeConfigChild == null) {
			throw new RuntimeException("NodeConfig with current name " + currentName + " not found.");
		}

		this.mapNodeConfigChild.put(newName, nodeConfigChild);
	}

	/**
	 * Removes a child {@link NodeConfig}.
	 * <p>
	 * This method is intended to be called by
	 * {@link SimpleNodeConfig#delete}.
	 *
	 * @param childNodeName
	 */
	void removeChildNodeConfig(String childNodeName) {
		this.mapNodeConfigChild.remove(childNodeName);
	}

	@Override
	public NodeConfigValue getNodeConfigValue() {
		return this.getClassificationNodeConfigValue();
	}

	@Override
	public void setNodeConfigValue(NodeConfigValue nodeConfigValue) {
		this.setClassificationNodeConfigValue((ClassificationNodeConfigValue)nodeConfigValue);
	}

	/**
	 * We need to override delete which is already defined in {@link SimpleNodeConfig}
	 * since only a SimpleClassificationNodeConfig can be a root ClassificationNodeConfig
	 * within a {@link Config}.
	 */
	@Override
	public void delete() {
		super.delete();

		if (this.simpleConfig != null) {
			this.simpleConfig.setClassificationNodeConfigRoot(null);
			this.simpleConfig = null;
		}
	}

	@Override
	public ClassificationNodeConfigValue getClassificationNodeConfigValue() {
		ClassificationNodeConfigValue classificationNodeConfigValue;

		classificationNodeConfigValue = new SimpleClassificationNodeConfigValue();

		this.fillNodeConfigValue(classificationNodeConfigValue);

		return classificationNodeConfigValue;
	}

	@Override
	public void setClassificationNodeConfigValue(ClassificationNodeConfigValue classificationNodeConfigValue) {
		boolean indNew;

		// We have to save the value of indNew since extractNodeConfigValue resets it.
		indNew = this.indNew;

		this.extractNodeConfigValue(classificationNodeConfigValue);

		if (indNew) {
			if (this.simpleConfig != null) {
				this.simpleConfig.setClassificationNodeConfigRoot(this);
			}
		}
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
