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

import org.azyva.dragom.model.config.MutableNodeConfig;
import org.azyva.dragom.model.config.NodeConfig;
import org.azyva.dragom.model.config.NodeConfigValue;
import org.azyva.dragom.model.config.PluginDefConfig;
import org.azyva.dragom.model.config.PluginKey;
import org.azyva.dragom.model.config.PropertyDefConfig;
import org.azyva.dragom.model.plugin.NodePlugin;

/**
 * Simple implementation for {@link NodeConfig}.
 *
 * @author David Raymond
 * @see org.azyva.dragom.model.config.simple
 */
public abstract class SimpleNodeConfig implements NodeConfig, MutableNodeConfig {
	private SimpleClassificationNodeConfig simpleClassificationNodeConfigParent;

	private String name;
	private Map<String, PropertyDefConfig> mapPropertyDefConfig;
	private Map<PluginKey, PluginDefConfig> mapPluginDefConfig;

	/**
	 * Constructor.
	 *
	 * @param simpleClassificationNodeConfigParent Parent
	 *   SimpleClassificationNodeConfig.
	 */
	SimpleNodeConfig(SimpleClassificationNodeConfig simpleClassificationNodeConfigParent) {
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

	/**
	 * Called by subclasses to fill a {@link NodeConfigValue} which must be created by
	 * the subclass since its real type (ModuleConfigValue or
	 * ClassificationNodeConfigValue) is determined by the subclass.
	 *
	 * @param nodeConfigValue NodeConfigValue.
	 */
	protected void fillNodeConfigValue(NodeConfigValue nodeConfigValue) {
		nodeConfigValue.setName(this.name);

		for(PropertyDefConfig propertyDefConfig: this.mapPropertyDefConfig.values()) {
			nodeConfigValue.setPropertyDefConfig(propertyDefConfig);
		}

		for(PluginDefConfig pluginDefConfig: this.mapPluginDefConfig.values()) {
			nodeConfigValue.setPluginDefConfig(pluginDefConfig);
		}
	}

	@Override
	public void setNodeConfigValue(NodeConfigValue nodeConfigValue) {
		if (nodeConfigValue.getName() == null) {
			throw new RuntimeException("Name of NodeConfigValue must not be null.");
		}

		if (this.simpleClassificationNodeConfigParent != null) {
			this.name = nodeConfigValue.getName();
			this.simpleClassificationNodeConfigParent.setNodeConfigChild(this);
			this.simpleClassificationNodeConfigParent = null;
		} else {
			if (!this.name.equals(nodeConfigValue.getName())) {
				throw new RuntimeException("NodeConfig name change not allowed (" + this.name + " to " + nodeConfigValue.getName() + ")");
			}
		}

		this.mapPropertyDefConfig.clear();

		for(PropertyDefConfig propertyDefConfig: nodeConfigValue.getListPropertyDefConfig()) {
			this.mapPropertyDefConfig.put(propertyDefConfig.getName(),  propertyDefConfig);
		}

		for(PluginDefConfig pluginDefConfig: nodeConfigValue.getListPluginDefConfig()) {
			this.mapPluginDefConfig.put(new PluginKey(pluginDefConfig.getClassNodePlugin(), pluginDefConfig.getPluginId()), pluginDefConfig);
		}

	}
}
