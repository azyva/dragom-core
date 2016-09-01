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

import java.text.MessageFormat;
import java.util.HashSet;
import java.util.ResourceBundle;
import java.util.Set;

import org.azyva.dragom.execcontext.plugin.RuntimePropertiesPlugin;
import org.azyva.dragom.execcontext.plugin.UserInteractionCallbackPlugin;
import org.azyva.dragom.execcontext.support.ExecContextHolder;
import org.azyva.dragom.model.Model;
import org.azyva.dragom.model.Node;
import org.azyva.dragom.model.impl.simple.SimpleNode;
import org.azyva.dragom.model.plugin.ModulePlugin;
import org.azyva.dragom.model.plugin.NewStaticVersionPlugin;
import org.azyva.dragom.model.plugin.NodePlugin;
import org.azyva.dragom.model.plugin.PluginFactory;
import org.azyva.dragom.util.AlwaysNeverAskUserResponse;
import org.azyva.dragom.util.Util;

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
 * SPECIFIC_PLUGIN_ID.&lt;NodePlugin class&gt; as the plugin ID and delegates to
 * {@link Node#getPlugin} with the same NodePlugin class and the plugin ID obtained
 * from the runtime property.
 * <p>
 * If no such runtime property exists and the runtime property
 * IND_ALLOW_USER_SPECIFIED_PLUGIN_ID.&lt;NodePlugin class&gt; is not true, null
 * is used as the plugin ID. If it is true, an interaction with the user is
 * performed using the CAN_REUSE_PLUGIN_ID.&lt;NodePlugin class&gt; and
 * REUSE_PLUGIN_ID.&lt;NodePlugin class&gt; runtime properties to ask the user for
 * the plugin ID and handle the reuse of a previously specified plugin ID.
 *
 * @author David Raymond
 */
public class RuntimeSelectionPluginFactory implements PluginFactory {
	/**
	 * Prefix for the runtime property specifying the specific plugin ID to delegate
	 * to.
	 */
	private static final String RUNTIME_PROPERTY_SPECIFIC_PLUGIN_ID_PREFIX = "SPECIFIC_PLUGIN_ID.";

	/**
	 * Prefix for the runtime property of that indicates if letting the user specify
	 * the plugin ID is allowed.
	 */
	private static final String RUNTIME_PROPERTY_IND_ALLOW_USER_SPECIFIED_PLUGIN_ID_PREFIX = "IND_ALLOW_USER_SPECIFIED_PLUGIN_ID.";

	/**
	 * Prefix for the runtime property of type AlwaysNeverAskUserResponse that
	 * indicates if a previously established plugin ID can be reused.
	 */
	private static final String RUNTIME_PROPERTY_CAN_REUSE_PLUGIN_ID_PREFIX = "CAN_REUSE_PLUGIN_ID.";

	/**
	 * Prefix for the runtime property that specifies the plugin ID to reuse.
	 */
	private static final String RUNTIME_PROPERTY_REUSE_PLUGIN_ID_PREFIX = "REUSE_PLUGIN_ID.";

	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_PLUGIN_ID_SPECIFIED = "PLUGIN_ID_SPECIFIED";

	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_PLUGIN_ID_AUTOMATICALLY_REUSED = "PLUGIN_ID_AUTOMATICALLY_REUSED";

	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_INPUT_PLUGIN_ID = "INPUT_PLUGIN_ID";

	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_INPUT_PLUGIN_ID_WITH_DEFAULT = "INPUT_PLUGIN_ID_WITH_DEFAULT";

	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_AUTOMATICALLY_REUSE_PLUGIN_ID = "AUTOMATICALLY_REUSE_PLUGIN_ID";

	/**
	 * ResourceBundle specific to this class.
	 */
	private static final ResourceBundle resourceBundle = ResourceBundle.getBundle(RuntimeSelectionPluginFactory.class.getName() + "ResourceBundle");

	/**
	 * RuntimeSelectionPluginFactory singleton.
	 */
	private static RuntimeSelectionPluginFactory runtimeSelectionPluginFactory;

	/**
	 * Used to detect cycles when resolving a {@link NodePlugin} at runtime.
	 */
	private static class CurrentPluginRequest {
		/**
		 * Class of the requested NodePlugin interface for which a request is being
		 * processed on the current thread.
		 */
		private Class<? extends NodePlugin> classNodePlugin;

		/**
		 * Node for which a request is being processed on the current thread.
		 */
		private Node node;

		/**
		 * Constructor.
		 *
		 * @param classNodePlugin Class of the {@link NodePlugin} interface.
		 * @param node Node.
		 */
		public CurrentPluginRequest(Class<? extends NodePlugin> classNodePlugin, Node node) {
			this.classNodePlugin = classNodePlugin;
			this.node = node;
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result;

			result = 1;

			result = (prime * result) + this.classNodePlugin.hashCode();
			result = (prime * result) + this.node.hashCode();

			return result;
		}

		@Override
		public boolean equals(Object other) {
			CurrentPluginRequest currentPluginRequestOther;

			currentPluginRequestOther = (CurrentPluginRequest)other;

			return    (this.classNodePlugin == currentPluginRequestOther.classNodePlugin)
			       && (this.node == currentPluginRequestOther.node);
		}
	}

	/**
	 * Set of current {@link NodePlugin} requests.
	 * <p>
	 * Used to avoid cycles when resolving a NodePlugin at runtime.
	 */
	private static ThreadLocal<Set<CurrentPluginRequest>> threadLocalCurrentPluginRequest = new ThreadLocal<Set<CurrentPluginRequest>>();

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
		CurrentPluginRequest currentPluginRequest;
		Set<CurrentPluginRequest> setCurrentPluginRequest;
		RuntimePropertiesPlugin runtimePropertiesPlugin;
		UserInteractionCallbackPlugin userInteractionCallbackPlugin;
		String stringClassNodePlugin;
		String specificPluginId;
		AlwaysNeverAskUserResponse alwaysNeverAskUserResponseCanReusePluginId;
		String reusePluginId;
		String pluginId;

		currentPluginRequest = new CurrentPluginRequest(classNodePlugin, node);

		if ((setCurrentPluginRequest = RuntimeSelectionPluginFactory.threadLocalCurrentPluginRequest.get()) == null) {

			setCurrentPluginRequest = new HashSet<CurrentPluginRequest>();

			RuntimeSelectionPluginFactory.threadLocalCurrentPluginRequest.set(setCurrentPluginRequest);
		}

		if (!setCurrentPluginRequest.add(currentPluginRequest)) {
			throw new RuntimeException("Cycle detected when resolving NodePlugin for class " + classNodePlugin + " and Node " + node + '.');
		}

		try {
			runtimePropertiesPlugin = ExecContextHolder.get().getExecContextPlugin(RuntimePropertiesPlugin.class);
			userInteractionCallbackPlugin = ExecContextHolder.get().getExecContextPlugin(UserInteractionCallbackPlugin.class);

			stringClassNodePlugin = classNodePlugin.getName();

			specificPluginId = runtimePropertiesPlugin.getProperty(node, RuntimeSelectionPluginFactory.RUNTIME_PROPERTY_SPECIFIC_PLUGIN_ID_PREFIX + stringClassNodePlugin);

			if (specificPluginId != null) {
				userInteractionCallbackPlugin.provideInfo(MessageFormat.format(RuntimeSelectionPluginFactory.resourceBundle.getString(RuntimeSelectionPluginFactory.MSG_PATTERN_KEY_PLUGIN_ID_SPECIFIED), stringClassNodePlugin, node, specificPluginId));

				return node.getNodePlugin(classNodePlugin, specificPluginId);
			}

			pluginId = null;

			if (Util.isNotNullAndTrue(runtimePropertiesPlugin.getProperty(node,  RuntimeSelectionPluginFactory.RUNTIME_PROPERTY_IND_ALLOW_USER_SPECIFIED_PLUGIN_ID_PREFIX + stringClassNodePlugin))) {
				alwaysNeverAskUserResponseCanReusePluginId = AlwaysNeverAskUserResponse.valueOfWithAskDefault(runtimePropertiesPlugin.getProperty(node, RuntimeSelectionPluginFactory.RUNTIME_PROPERTY_CAN_REUSE_PLUGIN_ID_PREFIX + stringClassNodePlugin));

				reusePluginId = runtimePropertiesPlugin.getProperty(node, RuntimeSelectionPluginFactory.RUNTIME_PROPERTY_REUSE_PLUGIN_ID_PREFIX + stringClassNodePlugin);

				if (reusePluginId == null) {
					if (alwaysNeverAskUserResponseCanReusePluginId.isAlways()) {
						// Normally if the runtime property CAN_REUSE_PLUGIN_ID is ALWAYS the
						// REUSE_PLUGIN_ID runtime property should also be set. But since these
						// properties are independent and stored externally, it can happen that they
						// are not synchronized. We make an adjustment here to avoid problems.
						alwaysNeverAskUserResponseCanReusePluginId = AlwaysNeverAskUserResponse.ASK;
					}
				}

				if (alwaysNeverAskUserResponseCanReusePluginId.isAlways()) {
					userInteractionCallbackPlugin.provideInfo(MessageFormat.format(RuntimeSelectionPluginFactory.resourceBundle.getString(RuntimeSelectionPluginFactory.MSG_PATTERN_KEY_PLUGIN_ID_AUTOMATICALLY_REUSED), stringClassNodePlugin, node, reusePluginId));
					pluginId = reusePluginId;
				} else {
					if (reusePluginId == null) {
						pluginId = userInteractionCallbackPlugin.getInfo(MessageFormat.format(RuntimeSelectionPluginFactory.resourceBundle.getString(RuntimeSelectionPluginFactory.MSG_PATTERN_KEY_INPUT_PLUGIN_ID), stringClassNodePlugin, node));
					} else {
						pluginId = userInteractionCallbackPlugin.getInfoWithDefault(MessageFormat.format(RuntimeSelectionPluginFactory.resourceBundle.getString(RuntimeSelectionPluginFactory.MSG_PATTERN_KEY_INPUT_PLUGIN_ID_WITH_DEFAULT), stringClassNodePlugin, node, reusePluginId), reusePluginId);
					}

					runtimePropertiesPlugin.setProperty(null, RuntimeSelectionPluginFactory.RUNTIME_PROPERTY_REUSE_PLUGIN_ID_PREFIX + stringClassNodePlugin, pluginId);

					// The result is not useful. We only want to adjust the runtime property which
					// will be reused the next time around.
					Util.getInfoAlwaysNeverAskUserResponseAndHandleAsk(
							runtimePropertiesPlugin,
							RuntimeSelectionPluginFactory.RUNTIME_PROPERTY_CAN_REUSE_PLUGIN_ID_PREFIX + stringClassNodePlugin,
							userInteractionCallbackPlugin,
							MessageFormat.format(RuntimeSelectionPluginFactory.resourceBundle.getString(RuntimeSelectionPluginFactory.MSG_PATTERN_KEY_AUTOMATICALLY_REUSE_PLUGIN_ID), stringClassNodePlugin, pluginId));
				}
			}

			return node.getNodePlugin(classNodePlugin, pluginId);
		} finally {
			setCurrentPluginRequest.remove(currentPluginRequest);
		}
	}
}
