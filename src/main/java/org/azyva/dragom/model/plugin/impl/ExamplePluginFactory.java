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

package org.azyva.dragom.model.plugin.impl;

import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.Node;
import org.azyva.dragom.model.config.NodeTypeEnum;
import org.azyva.dragom.model.plugin.ModulePlugin;
import org.azyva.dragom.model.plugin.NodePlugin;
import org.azyva.dragom.model.plugin.PluginFactory;

/**
 * Example implementation of a {@link PluginFactory}.
 * <p>
 * Generally {@link NodePlugin}'s are easier to implement using the constructor
 * idiom. Most NodePlugin's provided with Dragom use this idiom.
 * <p>
 * This NodePlugin provides an example implementation of a {@link PluginFactory},
 * the other NodePlugin implementation idiom supported by Dragom.
 * <p>
 * A useless generic {@link ModulePlugin} is actually implemented.
 *
 * @author David Raymond
 */
public class ExamplePluginFactory implements PluginFactory {
	private class ExamplePluginImpl extends ModulePluginAbstractImpl implements ModulePlugin {
		ExamplePluginImpl (Module module) {
			super(module);
		}

		/**
		 * This is a sample method of the {@link NodePlugin}. It should override a method
		 * defined by a ModulePlugin sub-interface.
		 */
		@SuppressWarnings("unused")
		public void examplePluginMethod() {
		}
	}

	@Override
	public Class<? extends NodePlugin> getDefaultClassNodePlugin() {
		 // Should return the Class representing a sub-interface of ModulePlugin.
		return ModulePlugin.class;
	}

	@Override
	public String getDefaultPluginId(Class<? extends NodePlugin> classNodePlugin) {
		// Should test with a sub-interface of ModulePlugin.
		if (classNodePlugin != ModulePlugin.class) {
			throw new RuntimeException("Unsupported plugin " + classNodePlugin + '.');
		}

		return null;
	}

	@Override
	public <NodePluginInterface extends NodePlugin> boolean isPluginSupported(Class<NodePluginInterface> classNodePlugin) {
		// Should test with a sub-interface of ModulePlugin.
		return classNodePlugin == ModulePlugin.class;
	}

	@Override
	public <NodePluginInterface extends NodePlugin> NodePluginInterface getPlugin(Class<NodePluginInterface> classNodePlugin, Node node) {
		if (node.getNodeType() != NodeTypeEnum.MODULE) {
			throw new RuntimeException("Node " + node + " must be a module.");
		}

		// Should test with a sub-interface of ModulePlugin.
		if (classNodePlugin != ModulePlugin.class) {
			throw new RuntimeException("Unsupported plugin " + classNodePlugin + '.');
		}

		return classNodePlugin.cast(new ExamplePluginImpl((Module)node));
	}
}
