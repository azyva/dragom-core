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

import org.azyva.dragom.model.MutableNode;
import org.azyva.dragom.model.config.DuplicateNodeException;
import org.azyva.dragom.model.config.MutableNodeConfig;
import org.azyva.dragom.model.config.NodeConfig;
import org.azyva.dragom.model.config.NodeConfigTransferObject;
import org.azyva.dragom.model.config.PluginDefConfig;
import org.azyva.dragom.model.config.PluginKey;
import org.azyva.dragom.model.config.PropertyDefConfig;
import org.azyva.dragom.model.config.SimpleNodeConfigTransferObject;
import org.azyva.dragom.model.plugin.NodePlugin;

/**
 * Simple implementation for {@link NodeConfig} and {@link MutableNodeConfig}.
 *
 * @author David Raymond
 * @see org.azyva.dragom.model.config.simple
 */
public abstract class SimpleNodeConfig implements NodeConfig, MutableNodeConfig {
	/**
	 * Indicates that the {@link SimpleNodeConfig} is new and has not been finalized
	 * yet. This is the state in which it is after having been created using the
	 * create methods of {@link SimpleConfig} or
	 * {@link SimpleClassificationNodeConfig}.
	 */
	protected boolean indNew;

	/**
	 * Parent {@link SimpleClassificationNodeConfig}.
	 */
	private SimpleClassificationNodeConfig simpleClassificationNodeConfigParent;

	/**
	 * Name.
	 */
	private String name;

	/**
	 * Map of {@link PropertyDefConfig}.
	 */
	private Map<String, PropertyDefConfig> mapPropertyDefConfig;

	/**
	 * Map of {@link PluginDefConfig}.
	 */
	private Map<PluginKey, PluginDefConfig> mapPluginDefConfig;

	/**
	 * Constructor.
	 *
	 * @param simpleClassificationNodeConfigParent Parent
	 *   SimpleClassificationNodeConfig.
	 */
	SimpleNodeConfig(SimpleClassificationNodeConfig simpleClassificationNodeConfigParent) {
		this.indNew = true;

		this.simpleClassificationNodeConfigParent = simpleClassificationNodeConfigParent;

		// LinkedHashMap are used to preserve insertion order.
		this.mapPropertyDefConfig = new LinkedHashMap<String, PropertyDefConfig>();
		this.mapPluginDefConfig = new LinkedHashMap<PluginKey, PluginDefConfig>();
	}

	@Override
	public String getName() {
		return this.name;
	}

	@Override
	public PropertyDefConfig getPropertyDefConfig(String name) {
		return this.mapPropertyDefConfig.get(name);
	}

	@Override
	public boolean isPropertyExists(String name) {
		return this.mapPropertyDefConfig.containsKey(name);
	}

	@Override
	public List<PropertyDefConfig> getListPropertyDefConfig() {
		// A copy is returned to prevent the internal Map from being modified by the
		// caller. Ideally, an unmodifiable List view of the Collection returned by
		// Map.values should be returned, but that does not seem possible.
		return new ArrayList<PropertyDefConfig>(this.mapPropertyDefConfig.values());
	}

	@Override
	public PluginDefConfig getPluginDefConfig(Class<? extends NodePlugin> classNodePlugin, String pluginId) {
		return this.mapPluginDefConfig.get(new PluginKey(classNodePlugin, pluginId));
	}

	@Override
	public boolean isPluginDefConfigExists(Class<? extends NodePlugin> classNodePlugin, String pluginId) {
		return this.mapPluginDefConfig.containsKey(new PluginKey(classNodePlugin, pluginId));
	}

	@Override
	public List<PluginDefConfig> getListPluginDefConfig() {
		// A copy is returned to prevent the internal Map from being modified by the
		// caller. Ideally, an unmodifiable List view of the Collection returned by
		// Map.values should be returned, but that does not seem possible.
		return new ArrayList<PluginDefConfig>(this.mapPluginDefConfig.values());
	}

	@Override
	public boolean isNew() {
		return this.indNew;
	}

	@Override
	public NodeConfigTransferObject getNodeConfigTransferObject() {
		NodeConfigTransferObject nodeConfigTransferObject;

		nodeConfigTransferObject = new SimpleNodeConfigTransferObject();

		nodeConfigTransferObject.setName(this.name);

		for(PropertyDefConfig propertyDefConfig: this.mapPropertyDefConfig.values()) {
			nodeConfigTransferObject.setPropertyDefConfig(propertyDefConfig);
		}

		for(PluginDefConfig pluginDefConfig: this.mapPluginDefConfig.values()) {
			nodeConfigTransferObject.setPluginDefConfig(pluginDefConfig);
		}

		return nodeConfigTransferObject;
	}

	/**
	 * Called by subclasses to extract the data from a {@link NodeConfigTransferObject} and set
	 * them within the {@link NodeConfig}.
	 * <p>
	 * Uses the indNew variable, but does not reset it. It is intended to be reset by
	 * the subclass caller method, {@link MutableNodeConfig#setNodeConfigValueTransferObject}.
	 * <p>
	 * The reason for not directly implementing
	 * MutableNodeConfig.setNodeConfigValueTransferObject is that subclasses can have
	 * other tasks to perform.
	 *
	 * @param nodeConfigTransferObject
	 * @throws DuplicateNodeExcpeption When the new configuration data would introduce
	 *   a duplicate {@link MutableNode} within the parent.
	 */
	protected void extractNodeConfigTransferObject(NodeConfigTransferObject nodeConfigTransferObject) throws DuplicateNodeException {
		String previousName;

		if ((nodeConfigTransferObject.getName() == null) && (this.simpleClassificationNodeConfigParent != null)) {
			throw new RuntimeException("Name of NodeConfigTrnmsferObject must not be null for non-root SimpleClassificationNodeConfig.");
		}

		previousName = this.name;
		this.name = nodeConfigTransferObject.getName();

		if (this.indNew) {
			if (this.simpleClassificationNodeConfigParent != null) {
				this.simpleClassificationNodeConfigParent.setSimpleNodeConfigChild(this);
			}
		} else {
			if ((this.simpleClassificationNodeConfigParent != null) && (!this.name.equals(previousName))) {
				this.simpleClassificationNodeConfigParent.renameSimpleNodeConfigChild(previousName, this.name);
			}
		}

		this.mapPropertyDefConfig.clear();

		for(PropertyDefConfig propertyDefConfig: nodeConfigTransferObject.getListPropertyDefConfig()) {
			this.mapPropertyDefConfig.put(propertyDefConfig.getName(),  propertyDefConfig);
		}

		this.mapPluginDefConfig.clear();

		for(PluginDefConfig pluginDefConfig: nodeConfigTransferObject.getListPluginDefConfig()) {
			this.mapPluginDefConfig.put(new PluginKey(pluginDefConfig.getClassNodePlugin(), pluginDefConfig.getPluginId()), pluginDefConfig);
		}

	}

	@Override
	public void delete() {
		if (!this.indNew && (this.simpleClassificationNodeConfigParent != null)) {
			this.simpleClassificationNodeConfigParent.removeChildNodeConfig(this.name);
			this.simpleClassificationNodeConfigParent = null;
		}
	}
}
