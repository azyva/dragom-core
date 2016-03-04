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

import org.azyva.dragom.model.Model;
import org.azyva.dragom.model.config.NodeConfig;
import org.azyva.dragom.model.config.PluginDefConfig;
import org.azyva.dragom.model.plugin.NodePlugin;

/**
 * Simple implementation for {@link PluginDefConfig}.
 *
 * @author David Raymond
 * @see org.azyva.dragom.model.config.simple
 */
public class SimplePluginDefConfig implements PluginDefConfig {
	private Class<? extends NodePlugin> classNodePlugin;
	private String pluginId;
	private String pluginClass;
	private boolean indOnlyThisNode;

	/**
	 * Constructor.
	 *
	 * @param classNodePlugin Class of the NodePlugin.
	 * @param pluginId ID of the plugin. Can be null.
	 * @param pluginClass Name of the implementaiton class of the plugin. Can be null
	 *   to avoid inheritane.
	 * @param indOnlyThisNode Indicates that this property applies specifically to the
	 *   {@link NodeConfig} on which it is defined, as opposed to being inherited by
	 *   child NodeConfig when interpreted by the {@link Model}.
	 */
	public SimplePluginDefConfig(Class<? extends NodePlugin> classNodePlugin, String pluginId, String pluginClass, boolean indOnlyThisNode) {
		this.classNodePlugin = classNodePlugin;
		this.pluginId = pluginId;
		this.pluginClass = pluginClass;
		this.indOnlyThisNode = indOnlyThisNode;
	}

	@Override
	public Class<? extends NodePlugin> getClassNodePlugin() {
		return this.classNodePlugin;
	}

	@Override
	public String getPluginId() {
		return this.pluginId;
	}

	@Override
	public String getPluginClass() {
		return this.pluginClass;
	}

	@Override
	public boolean isOnlyThisNode() {
		return this.indOnlyThisNode;
	}

	/**
	 * @return String to help recognize the
	 *   {@link PluginDefConfig} instance, in logs for example.
	 */
	@Override
	public String toString() {
		return "SimplePluginDefConfig [classNodePlugin=" + this.classNodePlugin.getName() + ", pluginId=" + this.pluginId + ", pluginClass=" + this.pluginClass + ", indOnlyThisNode=" + this.indOnlyThisNode + "]";
	}
}
