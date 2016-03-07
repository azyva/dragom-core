/*
 * Copyright 2015, 2016 AZYVA INC.
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

import org.azyva.dragom.execcontext.support.ExecContextHolder;
import org.azyva.dragom.execcontext.plugin.RuntimePropertiesPlugin;
import org.azyva.dragom.model.Model;
import org.azyva.dragom.model.Node;
import org.azyva.dragom.model.impl.simple.SimpleNode;
import org.azyva.dragom.model.plugin.ModulePlugin;
import org.azyva.dragom.model.plugin.NewStaticVersionPlugin;
import org.azyva.dragom.model.plugin.NodePlugin;
import org.azyva.dragom.model.plugin.PluginFactory;

/**
 * {@link PluginFactory} which allows selecting a specific {@link NodePlugin}
 * implementation at runtime based on a runtime property.
 * <p>
 * It is not for all NodePlugin's that it makes sense to dynamically select which
 * implementation to use at runtime. For instance, only one ScmPlugin is generally
 * expected to be defined for a given {@link Node}.
 * <p>
 * However for some NodePlugin's this makes sense. For example different
 * {@link NewStaticVersionPlugin}'s can implement different algorithms for
 * selecting the new static Version (release Version, temporary development
 * Version, etc.) which need to be selected at runtime.
 * <p>
 * Here is how this NodePlugin works:
 * <p>
 * The {@link Model} must be such that for a NodePlugin class and plugin ID this
 * class is used as the implementation class. The process of instantiating the
 * NodePlugin therefore (see {@link SimpleNode#getNodePlugin}) obtains the
 * PluginFactory using the {@link #getInstance} method since it is defined.
 * <p>
 * Then, {@link #getPlugin} is called with the NodePlugin class specified in the
 * Model. This method uses the runtime property
 * PLUGIN_ID.&lt;NodePlugin class&gt; as the plugin ID and delegates to
 * {@link Node#getPlugin} with the same NodePlugin class and the plugin ID obtained
 * from the runtime property. If no such runtime property exists, null is specified as
 * the plugin ID.
 *
 * @author David Raymond
 */
public class RuntimeSelectionPluginFactory implements PluginFactory {
	/**
	 * Prefix for the runtime property specifying the plugin ID to delegate to.
	 */
	public static final String RUNTIME_PROPERTY_PLUGIN_ID_PREFIX = "PLUGIN_ID.";

	/**
	 * RuntimeSelectionPluginFactory singleton.
	 */
	private static RuntimeSelectionPluginFactory runtimeSelectionPluginFactory;

	/**
	 * @return The singleton {@link RuntimeSelectionPluginFactory}.
	 */
	public static synchronized PluginFactory getInstance() {
		if (RuntimeSelectionPluginFactory.runtimeSelectionPluginFactory == null) {
			RuntimeSelectionPluginFactory.runtimeSelectionPluginFactory = new RuntimeSelectionPluginFactory();
		}

		return RuntimeSelectionPluginFactory.runtimeSelectionPluginFactory;
	}

	@Override
	public Class<? extends NodePlugin> getDefaultClassNodePlugin() {
		return null;
	}

	@Override
	public String getDefaultPluginId(Class<? extends NodePlugin> classNodePlugin) {
		return null;
	}

	@Override
	public <NodePluginInterface extends NodePlugin> boolean isPluginSupported(Class<NodePluginInterface> classNodePlugin) {
		// Should test with a sub-interface of ModulePlugin.
		return classNodePlugin == ModulePlugin.class;
	}

	@Override
	public <NodePluginInterface extends NodePlugin> NodePluginInterface getPlugin(Class<NodePluginInterface> classNodePlugin, Node node) {
		RuntimePropertiesPlugin runtimePropertiesPlugin;
		String pluginId;

		runtimePropertiesPlugin = ExecContextHolder.get().getExecContextPlugin(RuntimePropertiesPlugin.class);

		pluginId = runtimePropertiesPlugin.getProperty(node,  RuntimeSelectionPluginFactory.RUNTIME_PROPERTY_PLUGIN_ID_PREFIX + classNodePlugin.getName());

		return node.getNodePlugin(classNodePlugin, pluginId);
	}
}
