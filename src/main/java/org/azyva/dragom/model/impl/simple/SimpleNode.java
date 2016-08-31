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

package org.azyva.dragom.model.impl.simple;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.azyva.dragom.execcontext.ExecContext;
import org.azyva.dragom.execcontext.plugin.EventPlugin;
import org.azyva.dragom.execcontext.support.ExecContextHolder;
import org.azyva.dragom.model.ClassificationNode;
import org.azyva.dragom.model.ClassificationNodeBuilder;
import org.azyva.dragom.model.Model;
import org.azyva.dragom.model.ModelNodeBuilderFactory;
import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.ModuleBuilder;
import org.azyva.dragom.model.MutableModel;
import org.azyva.dragom.model.MutableNode;
import org.azyva.dragom.model.Node;
import org.azyva.dragom.model.NodePath;
import org.azyva.dragom.model.config.ClassificationNodeConfig;
import org.azyva.dragom.model.config.Config;
import org.azyva.dragom.model.config.DuplicateNodeException;
import org.azyva.dragom.model.config.ModuleConfig;
import org.azyva.dragom.model.config.MutableConfig;
import org.azyva.dragom.model.config.MutableNodeConfig;
import org.azyva.dragom.model.config.NodeConfig;
import org.azyva.dragom.model.config.NodeConfigTransferObject;
import org.azyva.dragom.model.config.NodeType;
import org.azyva.dragom.model.config.OptimisticLockException;
import org.azyva.dragom.model.config.OptimisticLockHandle;
import org.azyva.dragom.model.config.PluginDefConfig;
import org.azyva.dragom.model.config.PropertyDefConfig;
import org.azyva.dragom.model.event.NodeEvent;
import org.azyva.dragom.model.event.NodeEventListener;
import org.azyva.dragom.model.event.support.EventManager;
import org.azyva.dragom.model.plugin.ClassificationNodePlugin;
import org.azyva.dragom.model.plugin.ModulePlugin;
import org.azyva.dragom.model.plugin.NodeInitPlugin;
import org.azyva.dragom.model.plugin.NodePlugin;
import org.azyva.dragom.model.plugin.PluginFactory;
import org.azyva.dragom.model.plugin.impl.MavenBuilderPluginImpl;
import org.azyva.dragom.util.Util;

/**
 * Simple implementation of {@link Node} and {@link MutableNode}.
 * <p>
 * Two type of SimpleNode's exist each represented by a different subclass:
 * <p>
 * <li>{@link SimpleClassificationNode}</li>
 * <li>{@link SimpleModule}</li>
 *
 * @author David Raymond
 */
public abstract class SimpleNode implements Node, MutableNode {
	private static final String PARENT_REFERENCE = "$parent";

	/**
	 * Indicates if the SimpleNode is mutable, based on whether the {@link NodeConfig}
	 * provided is mutable.
	 */
	protected boolean indMutable;

	/**
	 * Defines the possible states in which the SimpleNode can be in.
	 */
	protected static enum State {
		/**
		 * SimpleNode has been created using
		 * {@link SimpleModel#createMutableClassificationNodeConfigRoot},
		 * {@link SimpleClassificationNode#createChildMutableModule} or
		 * {@link SimpleClassificationNode#createChildMutableClassificationNode} and is not
		 * finalized, meaning that
		 * {@link SimpleClassificationNode#setNodeConfigValue},
		 * {@link SimpleClassificationNode#setClassificationNodeConfigValue},
		 * {@link SimpleModule#setNodeConfigValue} or
		 * {@link SimpleModule#setModuleConfigValue} has not been called.
		 * <p>
		 * In that state, only
		 * {@link SimpleClassificationNode#getNodeConfigValue},
		 * {@link SimpleClassificationNode#getClassificationNodeConfigValue},
		 * {@link SimpleModule#getNodeConfigValue} or
		 * {@link SimpleModule#getModuleConfigValue} or the methods mentioned above can
		 * be called.
		 * <p>
		 * When one of {@link SimpleClassificationNode#setNodeConfigValue},
		 * {@link SimpleClassificationNode#setClassificationNodeConfigValue},
		 * {@link SimpleModule#setNodeConfigValue} or
		 * {@link SimpleModule#setModuleConfigValue} is called, the SimpleNode transitions
		 * to the {@link #CONFIG} state.
		 * <p>
		 * When the SimpleNode is created from {@link ClassificationNodeConfig} or
		 * {@link ModuleConfig}, this state is not used.
		 */
		CONFIG_NEW,

		/**
		 * SimpleNode has been created using
		 * {@link SimpleModel#createMutableClassificationNodeConfigRoot},
		 * {@link SimpleClassificationNode#createChildMutableModule} or
		 * {@link SimpleClassificationNode#createChildMutableClassificationNode} and is finalized
		 * (see {@link #CONFIG_NEW}).
		 * <p>
		 * This state is also used when the SimpleNode is a {@link SimpleModule} and has
		 * been created based on ModuleConfig.
		 */
		CONFIG,

		/**
		 * SimpleNode has been created internally using
		 * {@link SimpleModel#createClassificationNodeBuilder} or
		 * {@link SimpleModel#createModuleBuilder} and is not yet finalized, meaning that
		 * {@link ClassificationNodeBuilder#create} or {@link ModuleBuilder#create} has
		 * not been called. These methods call {@link SimpleNode#init} which performs the
		 * state transition to {@link #DYNAMICALLY_CREATED}.
		 */
		DYNAMICALLY_BEING_COMPLETED,

		/**
		 * SimpleNode has been created using {@link ClassificationNodeBuilder#create} or
		 * {@link ModuleBuilder#create}.
		 */
		DYNAMICALLY_CREATED,

		/**
		 * SimpleNode has been deleted and cannot be used anymore. This happens only when
		 * {@link SimpleNode#delete} is called.
		 */
		DELETED
	}

	protected State state;

	private NodeConfig nodeConfig;
	private SimpleClassificationNode simpleClassificationNodeParent;
	private String name;
	private SimpleModel simpleModel;
	private NodePath nodePath;

	// Values in mapProperty.
	private static class Property {
		String value;
		boolean indOnlyThisNode;

		Property(String value, boolean indOnlyThisNode) {
			this.value = value;
			this.indOnlyThisNode = indOnlyThisNode;
		}
	}

	/**
	 * Internal Map of properties used when the SimpleNode was dynamically created.
	 * Not used when it was created from {@link Config}.
	 */
	private Map<String, Property> mapProperty;

	/**
	 * Map of the {@link NodePlugin}'s instantiated via their constructor (when the
	 * plugin implementation class does not implement {@link PluginFactory}).
	 * <p>
	 * For such a plugin implementation class, for a given Node, we want to have only
	 * one instance of the class per Node as if the class implements multiple
	 * NodePlugin's (extends multiple NodePlugin sub-interfaces), it is useless to
	 * have multiple instances.
	 * <p>
	 * NodePlugin's instantiated from PluginFactory are not cached since the factory
	 * design pattern specifically allows instantiation logic which may return
	 * different NodePlugin's depending on the runtime context.
	 */
	private Map<String, NodePlugin> mapNodePluginConstructor;

	/**
	 * {@link EventManager}.
	 */
	private EventManager eventManager;

	/**
	 * Constructor used when dynamically completing a {@link Model}.
	 * <p>
	 * This constructor is expected to be called by
	 * {@link SimpleClassificationNode#SimpleClassificationNode(SimpleModel)} or
	 * {@link SimpleModule#SimpleModule(SimpleModel)}.
	 *
	 * @param simpleModel
	 */
	protected SimpleNode(SimpleModel simpleModel) {
		this.indMutable = false;

		this.state = State.DYNAMICALLY_BEING_COMPLETED;

		this.simpleModel = simpleModel;
		this.mapProperty = new HashMap<String, Property>();
	}

	/**
	 * Constructor for the root SimpleClassificationNode when creating a {@link Model}
	 * from {@link Config}.
	 * <p>
	 * If nodeConfig is a {@link MutableNodeConfig} and nodeConfig.isNew(), it means
	 * we are creating a new {@link MutableNode} in a {@link MutableModel} with an
	 * initially empty {@link MutableNodeConfig}.
	 * <p>
	 * Must not be used for SimpleNode's other than the root SimpleClassificationNode.
	 * Use {@link SimpleNode(NodeConfig, SimpleNode)} for these SimpleNode's.
	 * <p>
	 * This constructor is expected to be called by
	 * {@link SimpleClassificationNode#SimpleClassificationNode(ClassificationNodeConfig, SimpleModel)}.
	 *
	 * @param nodeConfig NodeConfig.
	 * @param simpleModel SimpleModel.
	 */
	protected SimpleNode(NodeConfig nodeConfig, SimpleModel simpleModel) {
		if (simpleModel.getClassificationNodeRoot() != null) {
			throw new RuntimeException("The model for node " + nodeConfig.getName() + " must not already have a root classification node.");
		}

		this.nodeConfig = nodeConfig;
		this.indMutable = (this.nodeConfig instanceof MutableNodeConfig);

		if (this.indMutable && ((MutableNodeConfig)nodeConfig).isNew()) {
			this.state = State.CONFIG_NEW;
		} else {
			this.state = State.CONFIG;
		}

		this.name = nodeConfig.getName();
		this.simpleModel = simpleModel;
	}

	/**
	 * Constructor for Node's other than the root SimpleClassificationNode when
	 * creating a {@link Model} from {@link Config}.
	 * <p>
	 * If nodeConfig is a {@link MutableNodeConfig} and nodeConfig.isNew(), it means
	 * we are creating a new {@link MutableNode} in a {@link MutableModel} with an
	 * initially empty {@link MutableNodeConfig}.
	 * <p>
	 * Must not be used for the root SimpleClassificationNode. Use
	 * {@link SimpleNode(NodeConfig, SimpleModel)} for the root
	 * SimpleClassificationNode.
	 * <p>
	 * This constructor is expected to be called by
	 * {@link SimpleClassificationNode#SimpleClassificationNode(ClassificationNodeConfig, SimpleClassificationNode)}
	 * or {@link SimpleModule#SimpleModule(ModuleConfig, SimpleClassificationNode)}.
	 *
	 * @param nodeConfig NodeConfig.
	 * @param simpleClassificationNodeParent Parent SimpleClassificationNode.
	 */
	protected SimpleNode(NodeConfig nodeConfig, SimpleClassificationNode simpleClassificationNodeParent) {
		if (simpleClassificationNodeParent == null) {
			throw new RuntimeException("The parent of node " + nodeConfig.getName() + " cannot be null.");
		}

		this.nodeConfig = nodeConfig;

		if (this.nodeConfig instanceof MutableNodeConfig) {
			this.indMutable = true;
		}

		if (this.indMutable && ((MutableNodeConfig)nodeConfig).isNew()) {
			this.state = State.CONFIG_NEW;
		} else {
			this.state = State.CONFIG;
		}

		this.simpleClassificationNodeParent = simpleClassificationNodeParent;
		this.name = nodeConfig.getName();
		this.simpleModel = (SimpleModel)simpleClassificationNodeParent.getModel(); // This ensures that all SimpleNode's in a SimpleModel are actually part of that SimpleModel.
	}

	/**
	 * Sets the parent {@link SimpleClassificationNode}.
	 * <p>
	 * This method has package scope since fields of a SimpleNode can only be set
	 * while dynamically completing a {@link SimpleModel} using
	 * {@link ModelNodeBuilderFactory} implemented by SimpleModel.
	 *
	 * @param simpleClassificationNode See description.
	 */
	void setSimpleClassificationNodeParent(SimpleClassificationNode simpleClassificationNodeParent) {
		if (this.state != State.DYNAMICALLY_BEING_COMPLETED) {
			throw new IllegalStateException("State must be DYNAMICALLY_BEING_COMPLETED. State: " + this.state);
		}

		if (simpleClassificationNodeParent.getModel() != this.getModel()) {
			throw new RuntimeException("Parent classification node is not from the same model as the module being built.");
		}

		this.simpleClassificationNodeParent = simpleClassificationNodeParent;
	}

	/**
	 * Sets the name.
	 * <p>
	 * This method has package scope since fields of a SimpleNode can only be set
	 * while dynamically completing a {@link SimpleModel} using
	 * {@link ModelNodeBuilderFactory} implemented by SimpleModel.
	 *
	 * @param name Name.
	 */
	void setName(String name) {
		if (this.state != State.DYNAMICALLY_BEING_COMPLETED) {
			throw new IllegalStateException("State must be DYNAMICALLY_BEING_COMPLETED. State: " + this.state);
		}

		this.name = name;
	}

	/**
	 * Sets the value of a property.
	 * <p>
	 * This method has package scope since fields of a SimpleNode can only be set
	 * while dynamically completing a {@link SimpleModel} using{@code}
	 * {@link ModelNodeBuilderFactory} implemented by SimpleModel.
	 *
	 * @param name Name of the property.
	 * @param value Value of the property.
	 */
	void setProperty(String name, String value, boolean indOnlyThisNode) {
		if (this.state != State.DYNAMICALLY_BEING_COMPLETED) {
			throw new IllegalStateException("State must be DYNAMICALLY_BEING_COMPLETED. State: " + this.state);
		}

		this.mapProperty.put(name, new Property(value, indOnlyThisNode));
	}

	/**
	 * Initializes this Node.
	 * <p>
	 * This method must be called after this Node is created and before it is actually
	 * used.
	 * <p>
	 * This method invokes all the {@link NodeInitPlugin}.
	 * <p>
	 * This method has package scope since it can only be called by
	 * {@link SimpleClassificationNode#createChildNodesFromConfig},
	 * {@link SimpleNode#setNodeConfigTransferObject(NodeConfigTransferObject)
	 * and {@link SimpleNodeBuilder#create}.
	 */
	void init() {
		List<String> listPluginId;

		if ((this.state != State.CONFIG) && (this.state != State.DYNAMICALLY_BEING_COMPLETED)) {
			throw new IllegalStateException("State must be CONFIG or DYNAMICALLY_BEING_COMPLETED. State: " + this.state);
		}

		// We set the state before performing initialization since during initialization,
		// the  SimpleNode may be accessed.
		// this.state can also be State.CONFIG, in which case it must remain so.
		if (this.state == State.DYNAMICALLY_BEING_COMPLETED) {
			this.state = State.DYNAMICALLY_CREATED;
		}
		listPluginId = this.getListPluginId(NodeInitPlugin.class);

		for (String pluginId: listPluginId) {
			NodeInitPlugin nodeInitPlugin;

			nodeInitPlugin = this.getNodePlugin(NodeInitPlugin.class, pluginId);

			nodeInitPlugin.init();
		}
	}

	/**
	 * @return NodeConfig.
	 */
	protected NodeConfig getNodeConfig() {
		if ((this.state != State.CONFIG) && (this.state != State.CONFIG_NEW)){
			throw new IllegalStateException("State must be CONFIG or CONFIG_NEW. State: " + this.state);
		}

		return this.nodeConfig;
	}

	/**
	 * @return Parent {@link ClassificationNode}. null in the case of the root
	 *   ClassificationNode.
	 */
	@Override
	public ClassificationNode getClassificationNodeParent() {
		this.checkNotDeleted();

		return this.simpleClassificationNodeParent;
	}

	/**
	 * If the SimpleNode was created from {@link Config}, this is equivalent to
	 * nodeConfig.getName. Otherwise, this is the name set when building the
	 * SimpleNode.
	 *
	 * @return Name.
	 */
	@Override
	public String getName() {
		this.checkNotDeleted();

		if (this.state == State.CONFIG_NEW) {
			throw new IllegalStateException("State must not be CONFIG_NEW. State: " + this.state);
		}

		return this.name;
	}

	/**
	 * @return Model.
	 */
	@Override
	public Model getModel() {
		this.checkNotDeleted();

		return this.simpleModel;
	}

	/**
	 * @return NodeType. Must be overridden by subclasses.
	 */
	@Override
	public abstract NodeType getNodeType();

	/**
	 * @return NodePath. null for the root {@link ClassificationNode}.
	 */
	@Override
	public NodePath getNodePath() {
		this.checkNotDeleted();

		// The root ClassificationNode does not have a NodePath.
		if (this.simpleClassificationNodeParent == null) {
			return null;
		}

		if (this.nodePath == null) {
			this.nodePath = new NodePath(this.simpleClassificationNodeParent.getNodePath(), this.name, this.getNodeType() == NodeType.CLASSIFICATION);
		}

		return this.nodePath;
	}

	/**
	 * See comment about initialization Properties in {@link SimpleModel}. If the
	 * property cannot be resolved from initialization properties, it will be
	 * obtained from the {@link Model} according to below.
	 * <p>
	 * SimpleNode inheritance is considered. The value returned is that of the named
	 * property on the first SimpleNode that defines it while traversing the parent
	 * hierarchy of SimpleNode's starting with this Node.
	 * <p>
	 * If no SimpleNode in the parent hierarchy defines the property, null is
	 * returned.
	 * <p>
	 * If the first SimpleNode in the parent hierarchy that defines the property
	 * defines it as null (to avoid inheritance), null is also returned.
	 * <p>
	 * If the first SimpleNode in the parent hierarchy that defines the property
	 * defines it only for that specific SimpleNode
	 * ({@link PropertyDefConfig#isOnlyThisNode} or {@link #setProperty} called with
	 * indOnlyThisNode) and this first SimpleNode is not the one associated with this
	 * SimpleNode, null is also returned.
	 * <p>
	 * If the value of a property as evaluated on the SimpleNode using the algorithm
	 * above contains "$parent$", these parent references (generally there will be at
	 * most one such reference) are replaced with the value of the same property
	 * evaluated in the context of the SimpleNode that is the parent of the SimpleNode
	 * that defined the property, or an empty string if null. This allows defining
	 * cumulative properties, such as Maven properties. For example, if the root
	 * SimpleNode defines "MAVEN_PROPERTIES=property1,property2" and a child
	 * SimpleNode defines "MAVEN_PROPERTIES=$parent$,property3", the value of the
	 * property MAVEN_PROPERTIES evaluated on the child SimpleNode is
	 * "property1,property2,property3". See {@link MavenBuilderPluginImpl} for more
	 * information about this specific example.
	 * <p>
	 * It is conceivable that eventually property values can contain expressions
	 * expressed in an embedded expression language such as Groovy. In that case this
	 * method would evaluate these expressions. These expressions would be evaluated
	 * in the context of this SimpleNode, but the SimpleNode on which the property
	 * value was actually defined would also be provided as a root object. This would
	 * provide for a very flexible and powerful inheritance mechanism that would
	 * subsume the functionality provided by parent references described above and
	 * much more.
	 * <p>
	 * Depending on how the SimpleNode was created, either from {@link Config} or
	 * dynamically using {@link ModelNodeBuilderFactory} implemented by
	 * {@link SimpleModel}, the property of a SimpleNode in the parent hierarchy may
	 * come from the Config or may have been using setProperty.
	 *
	 * @param name Name of the property.
	 * @return Value of the property.
	 */
	@Override
	public String getProperty(String name) {
		String[] arrayNodeName;
		StringBuilder stringBuilder;
		Properties propertiesInit;
		String value;
		SimpleNode simpleNodeCurrent;

		this.checkNotDeleted();

		if (this.state == State.CONFIG_NEW) {
			throw new IllegalStateException("State must not be CONFIG_NEW. State: " + this.state);
		}

		if (this.getNodePath() == null) {
			arrayNodeName = new String[0];
		} else {
			arrayNodeName = this.getNodePath().getArrayNodeName();
		}

		stringBuilder = new StringBuilder("org.azyva.dragom.model-property.");
		propertiesInit = this.simpleModel.getInitProperties();
		value = propertiesInit.getProperty(stringBuilder.toString() + name);

		for (String nodeName: arrayNodeName) {
			String value2;

			stringBuilder.append(nodeName).append('.');

			value2 = propertiesInit.getProperty(stringBuilder.toString() + name);

			if (value2 != null) {
				value = value2;
			}
		}

		// When the property is resolved from real Model properties, the special "@parent"
		// is handled at the end of this method. But the notion of parent is not supported
		// when the property is resolved from initialization properties.
		simpleNodeCurrent = null;

		if (value == null) {
			simpleNodeCurrent = this;
			value = null;

			while ((simpleNodeCurrent != null) && (value == null)) {
				// A SimpleNode either has properties from the Config from which it was created,
				// or from properties set using setProperty when dynamically creating it. It is
				// not possible for a SimpleNode to have both since setProperty can only be called
				// by SimpleNodeBuilder which has nothing to do with Config.

				if (this.state == State.CONFIG) {
					PropertyDefConfig propertyDefConfig;

					// getNodeConfig cannot return null here since if the SimpleNode was dynamically
					// created, mapProperty is not null and we do not get here.
					propertyDefConfig = simpleNodeCurrent.getNodeConfig().getPropertyDefConfig(name);

					if (propertyDefConfig != null) {
						if (propertyDefConfig.isOnlyThisNode() && (this != simpleNodeCurrent)) {
							return null; // This corresponds to the case where the first NodeConfig has PropertyDefConfig.isOnlyThisNode and is not for this Node.
						}

						value = propertyDefConfig.getValue(); // The value can be null, which corresponds to the case where the first NodeConfig defines the property as null.
					}
				} else {
					Property property;

					property = simpleNodeCurrent.mapProperty.get(name);

					if (property != null) {
						if (property.indOnlyThisNode && (this != simpleNodeCurrent)) {
							return null; // This corresponds to the case where the first NodeConfig has PropertyDefConfig.isOnlyThisNode and is not for this Node.
						}

						value = property.value; // The value can be null, which corresponds to the case where the first NodeConfig defines the property as null.
					}
				}

				simpleNodeCurrent = (SimpleNode)simpleNodeCurrent.getClassificationNodeParent();
			};
		}

		if (value != null) {
			int indexParentReference;

			while ((indexParentReference = value.indexOf(SimpleNode.PARENT_REFERENCE)) != -1) {
				String valueParent;

				// Because of the way the loop above is constructed, simpleNodeCurrent now refers
				// to the parent SimpleNode of the SimpleNode which defines the value, or is null
				// if we reached the root SimpleNode.
				if (simpleNodeCurrent == null) {
					valueParent = "";
				} else {
					valueParent = simpleNodeCurrent.getProperty(name);
				}

				value = value.substring(0, indexParentReference) + valueParent + value.substring(indexParentReference + SimpleNode.PARENT_REFERENCE.length());
			}
		}

		return value; // If value is null, this corresponds to the case where no NodeConfig defines the property. Otherwise we found the property.
	}

	/**
	 * Gets the {@link PluginDefConfig} for a NodePlugin.
	 * <p>
	 * This method is similar to {@link #getPropertyValue} in that Node inheritance is
	 * considered. But contrary to getPropertyValue, this method is private and used
	 * only internally by {@link #getNodePlugin|.
	 * <p>
	 * The PluginDefConfig returned is the one on the first NodeConfig that defines it
	 * while traversing the parent hierarchy of Node's starting with this Node.
	 * <p>
	 * If no NodeConfig in the parent hierarchy defines the PluginDefConfig, null is
	 * returned.
	 * <p>
	 * If the first NodeConfig in the parent hierarchy that defines the PluginDefConfig
	 * defines it with {@link PluginDefConfig.getPluginClass} as null (to avoid
	 * inheritance), null is also returned.
	 * <p>
	 * If the first NodeConfig in the parent hierarchy that defines the PluginDefConfig
	 * defines it with {@link PluginDefConfig#isOnlyThisNode} and this first
	 * NodeConfig is not the one associated with this Node, null is also returned.
	 *
	 * @param classNodePlugin Class of the {@link NodePlugin}.
	 * @param pluginId Plugin ID to distinguish between multiple instances of the same
	 *   NodePlugin.
	 * @return PluginDefConfig. null can be returned.
	 */
	private PluginDefConfig getPluginDefConfig(Class<? extends NodePlugin> classNodePlugin, String pluginId) {
		SimpleNode simpleNodeCurrent;

		simpleNodeCurrent = this;

		while (simpleNodeCurrent != null) {
			PluginDefConfig pluginDefConfig;

			// PluginDefConfig's are available only if the Node is base on a NodeConfig.
			if (this.state == State.CONFIG) {
				pluginDefConfig = simpleNodeCurrent.getNodeConfig().getPluginDefConfig(classNodePlugin, pluginId);

				if (pluginDefConfig != null) {
					if (pluginDefConfig.isOnlyThisNode() && (this != simpleNodeCurrent)) {
						return null; // This corresponds to the case where the first NodeConfig has PluginDefConfig.isOnlyThisNode and is not for this Node.
					}

					return pluginDefConfig; // pluginDefConfig.getPluginClass() can be null, which corresponds to the case where the first NodeConfig defines the PluginDefConfig with getPluginClass() as null.
				}
			}

			simpleNodeCurrent = (SimpleNode)simpleNodeCurrent.getClassificationNodeParent();
		};

		return null; // This corresponds to the case where no NodeConfig defines the PluginDefConfig.
	}

	/**
	 * Gets the specified {@link NodePlugin} for this Node.
	 * <p>
	 * The NodePlugin must exist. {@link #isNodePluginExists} can be used to verify if
	 * a NodePlugin exists before getting it.
	 * <p>
	 * This method implements the logic to instantiate the NodePlugin either from
	 * {@link PluginFactory} or by instantiating a class directly as a NodePlugin with
	 * the current Node as the only constructor argument.
	 *
	 * @param classNodePlugin Class of the NodePlugin.
	 * @param pluginId Plugin ID to distinguish between multiple instances of the same
	 *   NodePlugin.
	 * @return NodePlugin. The type is as specified by classNodePlugin.
	 */
	@Override
	public <NodePluginInterface extends NodePlugin> NodePluginInterface getNodePlugin(Class<NodePluginInterface> classNodePlugin, String pluginId) {
		PluginDefConfig pluginDefConfig;
		Class<?> classPlugin;
		NodePlugin nodePlugin;

		this.checkNotDeleted();

		if (this.state == State.CONFIG_NEW) {
			throw new IllegalStateException("State must not be CONFIG_NEW. State: " + this.state);
		}

		// Validate that the type of the requested NodePlugin (ClassificationNodePlugin}
		// or ModulePlugin) corresponds to the type of Node.
		switch (this.getNodeType()) {
		case CLASSIFICATION:
			classNodePlugin.asSubclass(ClassificationNodePlugin.class);
			break;

		case MODULE:
			classNodePlugin.asSubclass(ModulePlugin.class);
			break;
		}

		pluginDefConfig = this.getPluginDefConfig(classNodePlugin, pluginId);

		if (pluginDefConfig == null) {
			throw new RuntimeException("Plugin " + classNodePlugin + ":" + pluginId + " is not defined for node " + this + '.');
		}

		try {
			classPlugin = Class.forName(pluginDefConfig.getPluginClass());
		} catch (ClassNotFoundException cnfe) {
			throw new RuntimeException(cnfe);
		}

		if (PluginFactory.class.isAssignableFrom(classPlugin)) {
			PluginFactory pluginFactory;

			pluginFactory = Util.getPluginFactory(pluginDefConfig.getPluginClass());

			nodePlugin = pluginFactory.getPlugin(pluginDefConfig.getClassNodePlugin(), this);
		} else if (NodePlugin.class.isAssignableFrom(classPlugin)) {
			// classPlugin is the Class specified in the PluginDefConfig and that implements
			// the plugin. pluginDefConfig.getClassNodePlugin() is the Class of the interface
			// that the plugin must implement. The two must not be confused.

			// We could also avoid making the following validation and let the subsequent
			// cast in getNodePlugin(Class<? extends NodePlugin>, String) fail. But it seems
			// cleaner to catch this exception here.
			if (!pluginDefConfig.getClassNodePlugin().isAssignableFrom(classPlugin)) {
				throw new RuntimeException("The plugin class " + pluginDefConfig.getPluginClass() + " cannot be instantiated as a " + pluginDefConfig.getClassNodePlugin() + '.');
			}

			try {
				if (this.mapNodePluginConstructor == null) {
					this.mapNodePluginConstructor = new HashMap<String, NodePlugin>();
				}

				nodePlugin = this.mapNodePluginConstructor.get(pluginDefConfig.getPluginClass());

				if (nodePlugin == null) {
					Constructor<? extends NodePlugin> constructorNodePlugin;

					constructorNodePlugin = classPlugin.asSubclass(NodePlugin.class).getConstructor((this.getNodeType() == NodeType.CLASSIFICATION) ? ClassificationNode.class : Module.class);
					nodePlugin = constructorNodePlugin.newInstance(this);
					this.mapNodePluginConstructor.put(pluginDefConfig.getPluginClass(), nodePlugin);
				}
			} catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException | InstantiationException e) {
				throw new RuntimeException(e);
			}
		} else {
			throw new RuntimeException("The plugin class " + pluginDefConfig.getPluginClass() + " does not implement PluginFactory and cannot be instantiated as a NodePlugin.");
		}

		return nodePlugin.getClass().asSubclass(classNodePlugin).cast(nodePlugin);
	}


	/**
	 * Verifies if a {@link NodePlugin exists for this Node, without instantiating it.
	 *
	 * @param classNodePlugin Class of the NodePlugin.
	 * @param pluginId Plugin ID to distinguish between multiple instances of the same
	 *   NodePlugin.
	 * @return Indicates if the NodePlugin exists.
	 */
	@Override
	public boolean isNodePluginExists(Class<? extends NodePlugin> classNodePlugin, String pluginId) {
		PluginDefConfig pluginDefConfig;

		this.checkNotDeleted();

		if (this.state == State.CONFIG_NEW) {
			throw new IllegalStateException("State must not be CONFIG_NEW. State: " + this.state);
		}

		// Validate that the type of the requested NodePlugin (ClassificationNodePlugin
		// ModulePlugin) corresponds to the type of Node.
		switch (this.getNodeType()) {
		case CLASSIFICATION:
			classNodePlugin.asSubclass(ClassificationNodePlugin.class);
			break;

		case MODULE:
			classNodePlugin.asSubclass(ModulePlugin.class);
			break;

		}

		pluginDefConfig = this.getPluginDefConfig(classNodePlugin, pluginId);

		// The fact that PluginDefConfig exists does not garanty that instantiating the
		// NodePlugin from it will succeed. But it not succeeding is considered exception
		// so we do not bother validating this here. It will fail later when instantiating
		// the NodePlugin.
		return (pluginDefConfig != null);
	}

	/**
	 * Returns the List of plugin IDs of all NodePlugin's of the specified class
	 * available.
	 * <p>
	 * The order in which the plugin IDs are returned is as defined by the order of
	 * the {@link PluginDefConfig} within the underlying {@link NodeConfig}'s. The
	 * NodeConfig of the parents are considered while traversing the parent hierarchy
	 * starting with this Node.
	 *
	 * @param classNodePlugin Class of the NodePlugin.
	 * @return List of plugin IDs.
	 */
	@Override
	public List<String> getListPluginId(Class<? extends NodePlugin> classNodePlugin) {
		List<String> listPluginId;
		Set<String> setPluginIdEncountered;
		SimpleNode nodeCurrent;

		this.checkNotDeleted();

		if (this.state == State.CONFIG_NEW) {
			throw new IllegalStateException("State must not be CONFIG_NEW. State: " + this.state);
		}

		listPluginId = new ArrayList<String>();
		setPluginIdEncountered = new HashSet<String>();
		nodeCurrent = this;

		while (nodeCurrent != null) {
			List<PluginDefConfig> listPluginDefConfig;

			// PluginDefConfig's are available only if the Node is base on a NodeConfig.
			if (this.state == State.CONFIG) {
				listPluginDefConfig = nodeCurrent.getNodeConfig().getListPluginDefConfig();

				for (PluginDefConfig pluginDefConfig: listPluginDefConfig) {
					if (pluginDefConfig.getClassNodePlugin() == classNodePlugin) {
						String pluginId;

						pluginId = pluginDefConfig.getPluginId();

						if (setPluginIdEncountered.contains(pluginId)) { // Plugin ID can be null (default plugin ID).
							continue;
						}

						// Whatever happens below, once we encounter a given plugin ID, we must not
						// consider other occurrences (it is the first occurrence in the parent hierarchy
						// that wins).
						setPluginIdEncountered.add(pluginId);

						if ((pluginDefConfig.getPluginClass() != null) && !(pluginDefConfig.isOnlyThisNode() && (nodeCurrent != this))) {
							listPluginId.add(pluginId);
						}
					}
				}
			}

			nodeCurrent = (SimpleNode)nodeCurrent.getClassificationNodeParent();
		};

		return listPluginId;
	}

	/**
	 * Registers a {@link NodeEventListener}.
	 * <p>
	 * The NodeEventListener is registered within the SimpleNode, and thus within the
	 * {@link SimpleModel}. It is also possible to register NodeEventListener's in the
	 * {@link ExecContext}.
	 *
	 * @param nodeEventListener NodeEventListener.
	 * @param indChildrenAlso Indicates if {@link NodeEvent} raised on children should
	 *   be dispatched to the NodeEventListener.
	 */
	@Override
	public <NodeEventClass extends NodeEvent> void registerListener(NodeEventListener<NodeEventClass> nodeEventListener, boolean indChildrenAlso) {
		if ((this.state != State.CONFIG) && (this.state != State.DYNAMICALLY_CREATED)) {
			throw new IllegalStateException("State must be CONFIG or DYNAMICALLY_CREATED. State: " + this.state);
		}

		if (this.eventManager == null) {
			this.eventManager = new EventManager();
		}

		this.eventManager.registerListener(this, nodeEventListener, indChildrenAlso);
	}

	/**
	 * Raises a {@link NodeEvent}.
	 * <p>
	 * The NodeEvent is dispatched to all registered {@link NodeEventListener}'s
	 * interested in it.
	 * <p>
	 * NodeEventListener's registered in the {@link ExecContext} using
	 * {@link EventPlugin#registerListener} are also considered.
	 * <p>
	 * The NodeEvent sub-interface (ModuleEvent or ClassificationNodeEvent) must match
	 * the type of Node.
	 * <p>
	 * The NodeEvent must be dispatched on {@link NodeEvent#getNode}.
	 *
	 * @param nodeEvent NodeEvent.
	 */
	@Override
	public void raiseNodeEvent(NodeEvent nodeEvent) {
		EventPlugin eventPlugin;

		if ((this.state != State.CONFIG) && (this.state != State.DYNAMICALLY_CREATED)) {
			throw new IllegalStateException("State must be CONFIG or DYNAMICALLY_CREATED. State: " + this.state);
		}

		if (nodeEvent.getNode() != this) {
			throw new RuntimeException("Node event must be raised on target node.");
		}

		if (this.eventManager != null) {
			this.eventManager.raiseNodeEvent(nodeEvent);
		}

		eventPlugin = ExecContextHolder.get().getExecContextPlugin(EventPlugin.class);

		if (eventPlugin != null) {
			eventPlugin.raiseNodeEvent(nodeEvent);
		}
	}

	@Override
	public boolean isCreatedDynamically() {
		this.checkNotDeleted();

		return (this.state == State.DYNAMICALLY_CREATED) || (this.state == State.DYNAMICALLY_BEING_COMPLETED);
	}

	@Override
	public boolean isNew() {
		this.checkMutable();
		this.checkNotDeleted();

		return this.state == State.CONFIG_NEW;
	}

	@Override
	public OptimisticLockHandle createOptimisticLockHandle(boolean indLock) {
		return ((MutableNodeConfig)this.getNodeConfig()).createOptimisticLockHandle(indLock);
	}

	@Override
	public boolean isOptimisticLockValid(OptimisticLockHandle optimisticLockHandle) {
		return ((MutableNodeConfig)this.getNodeConfig()).isOptimisticLockValid(optimisticLockHandle);
	}

	@Override
	public NodeConfigTransferObject getNodeConfigTransferObject(OptimisticLockHandle optimisticLockHandle)
			throws OptimisticLockException {
		// This will catch the case where the SimpleNode has been dynamically created, in
		// which case its configuration data, which does not exist, cannot be changed.
		// Note that it is still possible to convert a dynamically created SimpleNode into
		// one based on MutableNodeConfig. The caller has to detect the fact that it is
		// currently dynamically created using Node.isCreatedDynamically and if so, do as
		// if the Node does not exist yet by calling
		// MutableClassificationNode.createChildMutableClassificationNode or
		// MutableClassificationNode.createChildMutableModule. It is when ultimately
		// calling MutableNode.setNodeConfigTransferObject that the Node will be converted
		// from a dynamically created to one based on MutableNodeConfig. See
		// MutableNode.isDeleted.
		this.checkMutable();

		this.checkNotDeleted();

		if ((this.state != State.CONFIG) && (this.state != State.CONFIG_NEW)) {
			throw new IllegalStateException("State must be CONFIG or CONFIG_NEW. State: " + this.state);
		}

		return ((MutableNodeConfig)this.getNodeConfig()).getNodeConfigTransferObject(optimisticLockHandle);
	}

	/**
	 * Called by subclasses to extract the data from a {@link NodeConfigTransferObject} and set
	 * them within the configuration of the SimpleNode.
	 * <p>
	 * Does most of the processing that
	 * {@link MutableNode#setNodeConfigValueTransferObject} must do, except calling init
	 * and setting the new state.
	 * <p>
	 * The reason for not directly implementing
	 * MutableNode.setNodeConfigValueTransferObject is that subclasses can have
	 * other tasks to perform.
	 * <p>
	 * If optimisticLockHandle is null, no optimistic lock is managed.
	 * <p>
	 * If optimisticLockHandle is not null, it must be locked
	 * ({@link OptimisticLockHandle#isLocked}) and its state must correspond to the
	 * state of the data it represents, otherwise {@link OptimisticLockException} is
	 * thrown. The state of the OptimisticLockHandle is updated to the new revision of
	 * the SimpleNodeConfig.
	 *
	 * @param nodeConfigTransferObject NodeConfigTransferObject.
	 * @throws OptimisticLockException When the underlying {@link MutableNodeConfig}
	 *   detects that the configuration data was changed since the call to
	 *   {@link #getNodeConfigTransferObject}.
	 * @throws OptimisticLockException Can be thrown only if optimisticLockHandle is
	 *   not null. This is a RuntimeException that may be of interest to
	 *   the caller.
	 * @throws DuplicateNodeExcpeption When the new configuration data would introduce
	 *   a duplicate {@link MutableNode} within the parent. This is a RuntimeException
	 *   that may be of interest to the caller.
	 */
	protected void extractNodeConfigTransferObject(NodeConfigTransferObject nodeConfigTransferObject, OptimisticLockHandle optimisticLockHandle)
			throws OptimisticLockException, DuplicateNodeException {
		String newName;

		this.checkMutable();

		if ((this.state != State.CONFIG) && (this.state != State.CONFIG_NEW)) {
			throw new IllegalStateException("State must be CONFIG or CONFIG_NEW. State: " + this.state);
		}

		newName = nodeConfigTransferObject.getName();

		if (this.state == State.CONFIG) {
			if (this.simpleClassificationNodeParent != null) {
				String currentName;

				if (newName == null) {
					throw new RuntimeException("Name of NodeConfigTrnmsferObject must not be null for non-root SimpleClassificationNode.");
				}

				currentName = this.name;

				if (!newName.equals(currentName)) {
					if (this.simpleClassificationNodeParent.getNodeChild(newName) != null) {
						throw new DuplicateNodeException();
					}

					this.simpleClassificationNodeParent.renameSimpleNodeChild(currentName,  newName);
				}
			}

			try {
				((MutableNodeConfig)this.getNodeConfig()).setNodeConfigTransferObject(nodeConfigTransferObject, optimisticLockHandle);
			} catch (DuplicateNodeException dne) {
				// We have already check for duplicate above at the SimpleClassificationNode
				// level. We do not expect to get this exception at the
				// MutableClassificationNodeConfig level.
				throw new RuntimeException(dne);
			}

			this.name = newName;
		} else { // if (this.state == State.CONFIG_NEW) {
			if (this.simpleClassificationNodeParent != null) {
				SimpleNode simpleNodeExisting;

				if (newName == null) {
					throw new RuntimeException("Name of NodeConfigTrnmsferObject must not be null for non-root SimpleClassificationNode.");
				}

				simpleNodeExisting = (SimpleNode)this.simpleClassificationNodeParent.getNodeChild(newName);

				if (simpleNodeExisting != null) {
					if (!simpleNodeExisting.isCreatedDynamically()) {
						throw new DuplicateNodeException();
					}

					this.simpleClassificationNodeParent.removeChildNode(newName);

					// Sets the state to DELETED.
					simpleNodeExisting.cleanCaches(true);
				}
			}

			try {
				((MutableNodeConfig)this.getNodeConfig()).setNodeConfigTransferObject(nodeConfigTransferObject, optimisticLockHandle);
			} catch (DuplicateNodeException dne) {
				// We have already check for duplicate above at the SimpleClassificationNode
				// level. We do not expect to get this exception at the
				// MutableClassificationNodeConfig level.
				throw new RuntimeException(dne);
			}

			this.name = newName;

			if (this.simpleClassificationNodeParent != null) {
				this.simpleClassificationNodeParent.setSimpleNodeChild(newName, this);
			}
		}

		this.cleanCaches(false);
	}

	@Override
	public void delete() {
		this.checkMutable();

		if ((this.state != State.CONFIG) && (this.state != State.CONFIG_NEW)) {
			throw new IllegalStateException("State must be CONFIG or CONFIG_NEW. State: " + this.state);
		}

		if (this.state == State.CONFIG) {
			this.simpleClassificationNodeParent.removeChildNode(this.name);
			((MutableNodeConfig)(this.getNodeConfig())).delete();
		}

		// Sets the state to DELETED.
		this.cleanCaches(true);

	}

	@Override
	public boolean isDeleted() {
		this.checkMutable();

		return (this.state == State.DELETED);
	}

	/**
	 * Checks if the MutableNode is not deleted.
	 * <p>
	 * Utility method to facilitate validating the {@link SimpleNode} is not deleted
	 * at the beginning of other methods. Most methods must validate the SimpleNode
	 * is not deleted. But some methods implicitly perform this validation by
	 * verifying for specific states. In this case, calling this method is not
	 * required.
	 */
	protected void checkNotDeleted() {
		if (this.state == State.DELETED) {
			throw new IllegalStateException("MutableNode is deleted.");
		}
	}

	/**
	 * Checks if the MutableNode is really mutable.
	 */
	protected void checkMutable() {
		if (!this.indMutable) {
			throw new IllegalStateException("MutableNode must be mutable.");
		}
	}

	/**
	 * Called when underlying {@link MutableConfig} data have changed so that any
	 * cache that could contain instantiated objects whose states are dependent on
	 * this data is cleared. The current {@link SimpleNode} and its children are
	 * traversed and the same method is called on all of them because of the
	 * inheritance mechanisms implemented in Dragom.
	 * <p>
	 * Subclasses that manage such caches must override which method to add the
	 * appropriate behavior.
	 *
	 * @param indDelete Indicates to perform a full clean and put the MutableNode in
	 *   the State.DELETED to make it unusable.
	 */
	protected void cleanCaches(boolean indDelete) {
		this.nodePath = null;
		this.mapProperty = null;
		this.mapNodePluginConstructor = null;

		this.simpleModel.cleanCaches(this);

		if (indDelete) {
			this.name = null;
			this.simpleClassificationNodeParent = null;
			this.simpleModel = null;
			this.eventManager = null;

			// To allow isDeleted to be called for a MutableNode that was created dynamically
			// but was replaced with a MutableNode based on MutableNodeConfig.
			this.indMutable = true;

			this.state = State.DELETED;
		}
	}

	/**
	 * Returns the NodePath as a String representation.
	 */
	@Override
	public String toString() {
		if (this.simpleClassificationNodeParent == null) {
			return "[root]";
		} else {
			return this.getNodePath().toString();
		}
	}
}
