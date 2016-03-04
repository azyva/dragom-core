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

import org.azyva.dragom.model.config.NodeConfig;
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
public abstract class SimpleNodeConfig implements NodeConfig {
	private String name;
	private Map<String, PropertyDefConfig> mapPropertyDefConfig;
	private Map<PluginKey, PluginDefConfig> mapPluginDefConfig;

	/**
	 * Constructor.
	 *
	 * @param name Name.
	 */
	public SimpleNodeConfig(String name) {
		this.name = name;

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
		// caller.
		return new ArrayList<PropertyDefConfig>(this.mapPropertyDefConfig.values());
	}

	/**
	 * Adds a {@link PropertyDefConfig}.
	 * <p>
	 * Any implementation of PropertyDefConfig can be added.
	 *
	 * @param propertyDefConfig PropertyDefConfig.
	 */
	public void addPropertyDefConfig(PropertyDefConfig propertyDefConfig) {
		if (this.mapPropertyDefConfig.containsKey(propertyDefConfig.getName())) {
			throw new RuntimeException("Property " + propertyDefConfig.getName() + " alreaady present in configuration for node " + this.name + '.');
		}

		this.mapPropertyDefConfig.put(propertyDefConfig.getName(), propertyDefConfig);
	}

	@Override
	public PluginDefConfig getPluginDefConfig(Class<? extends NodePlugin> classNodePlugin, String pluginId) {
		return this.mapPluginDefConfig.get(new PluginKey(classNodePlugin, pluginId));
	}

	@Override
	public boolean isPluginDefConfigExists(			Class<? extends NodePlugin> classNodePlugin, String pluginId) {
		return this.mapPluginDefConfig.containsKey(new PluginKey(classNodePlugin, pluginId));
	}

	@Override
	public List<PluginDefConfig> getListPluginDefConfig() {
		// A copy is returned to prevent the internal Map from being modified by the
		// caller.
		return new ArrayList<PluginDefConfig>(this.mapPluginDefConfig.values());
	}

	/**
	 * Adds a {@link PluginDefConfig}.
	 * <p>
	 * Any implementation of PluginDefConfig can be added.
	 *
	 * @param pluginDefConfig PluginDefConfig.
	 */
	public void addPluginDefConfig(PluginDefConfig pluginDefConfig) {
		PluginKey pluginKey;

		pluginKey = new PluginKey(pluginDefConfig.getClassNodePlugin(), pluginDefConfig.getPluginId());
		if (this.mapPluginDefConfig.containsKey(pluginKey)) {
			throw new RuntimeException("Plugin " + pluginDefConfig.getClassNodePlugin().getName() + ':' + pluginDefConfig.getPluginId() + " alreaady present in configuration for node " + this.name + '.');
		}

		this.mapPluginDefConfig.put(pluginKey, pluginDefConfig);
	}
}
