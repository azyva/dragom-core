/*
 * Copyright 2015 - 2017 AZYVA INC. INC.
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.azyva.dragom.model.ClassificationNode;
import org.azyva.dragom.model.Model;
import org.azyva.dragom.model.ModelNodeBuilderFactory;
import org.azyva.dragom.model.MutableClassificationNode;
import org.azyva.dragom.model.MutableModule;
import org.azyva.dragom.model.Node;
import org.azyva.dragom.model.NodeBuilder;
import org.azyva.dragom.model.NodeVisitor;
import org.azyva.dragom.model.config.ClassificationNodeConfig;
import org.azyva.dragom.model.config.Config;
import org.azyva.dragom.model.config.DuplicateNodeException;
import org.azyva.dragom.model.config.ModuleConfig;
import org.azyva.dragom.model.config.MutableClassificationNodeConfig;
import org.azyva.dragom.model.config.NodeConfig;
import org.azyva.dragom.model.config.NodeConfigTransferObject;
import org.azyva.dragom.model.config.NodeType;
import org.azyva.dragom.model.config.OptimisticLockException;
import org.azyva.dragom.model.config.OptimisticLockHandle;
import org.azyva.dragom.model.config.impl.simple.SimpleClassificationNodeConfig;
import org.azyva.dragom.model.plugin.UndefinedDescendantNodeManagerPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple implementation of {@link ClassificationNode} and
 * {@link MutableClassificationNode}.
 *
 * @author David Raymond
 */
public class SimpleClassificationNode extends SimpleNode implements ClassificationNode, MutableClassificationNode {
	private static final Logger logger = LoggerFactory.getLogger(SimpleClassificationNode.class);

	/**
	 * Map of child {@link SimpleNode}'s.
	 * <p>
	 * For a SimpleClassificationNode base on {@link SimpleClassificationNodeConfig}
	 * this is initially null which causes it to be lazily created by
	 * {@link #createChildNodesFromConfig}. If dynamically created, it is initially
	 * assigned to an empty Map so that it does not get initialized with a null
	 * {@link ClassificationNodeConfig}.
	 */
	private Map<String, SimpleNode> mapSimpleNodeChild;

	/**
	 * Constructor used when dynamically completing a {@link SimpleModel}.
	 * <p>
	 * This constructor has package scope to enforce the use of
	 * {@link ModelNodeBuilderFactory#createClassificationNodeBuilder} implemented
	 * by SimpleModel to create new {@link SimpleClassificationNode}'s.
	 *
	 * @param simpleModel
	 */
	SimpleClassificationNode(SimpleModel simpleModel) {
		super(simpleModel);

		// This ensures that createChildNodesFromConfig does not attempt to create the
		// child SimpleNode's since a Config is not available.
		this.mapSimpleNodeChild = new LinkedHashMap<String, SimpleNode>();
	}

	/**
	 * Constructor for the root SimpleClassificationNode when creating a
	 * {@link Model} from {@link Config}.
	 * <p>
	 * Must not be used for SimpleClassificationNode's other than the root
	 * SimpleClassificationNode. Use
	 * {@link SimpleClassificationNode(ClassificationNodeConfig, SimpleClassificationNode)}
	 * for these SimpleClassificationNode's.
	 * <p>
	 * This constructor is expected to be called by {@link SimpleModel}'s constructor
	 * when creating the root SimpleClassificationNode.
	 * <p>
	 * This constructor has package scope to enforce the use of
	 * {@link SimpleModel#SimpleModel(Config)} to create a complete Model from
	 * {@link Config}.
	 *
	 * @param classificationNodeConfig ClassificationNodeConfig.
	 * @param model Model.
	 */
	SimpleClassificationNode(ClassificationNodeConfig classificationNodeConfig, SimpleModel simpleModel) {
		super(classificationNodeConfig, simpleModel);
	}

	/**
	 * Constructor for SimpleClassificationNode's other than the root
	 * SimpleClassificationNode when creating a {@link Model} from {@link Config}.
	 * <p>
	 * Must not be used for the root SimpleClassificationNode. Use
	 * {@link SimpleClassificationNode(ClassificationNodeConfig, SimpleModel)} for
	 * the root SimpleClassificationNode.
	 * <p>
	 * This constructor has package scope to enforce the use of
	 * {@link SimpleModel#SimpleModel(Config)} to create a complete Model from
	 * {@link Config}.
	 *
	 * @param classificationNodeConfig ClassificationNodeConfig.
	 * @param simpleClassificatonNodeParent Parent SimpleClassificationNode.
	 */
	SimpleClassificationNode(ClassificationNodeConfig classificationNodeConfig, SimpleClassificationNode simpleClassificationNodeParent) {
		super(classificationNodeConfig, simpleClassificationNodeParent);
	}

	/**
	 * Returns {@link NodeType#CLASSIFICATION}.
	 *
	 * @return See description.
	 */
	@Override
	public NodeType getNodeType() {
		// This may seem overkill for such a simple method, but it is better to fail fast.
		this.checkNotDeleted();

		return NodeType.CLASSIFICATION;
	}

	/**
	 * Returns a List of all the child {@link Node}'s.
	 * <p>
	 * The order of the child SimpleNode's is as defined by the underlying
	 * ClassificationNodeConfig, with child SimpleNodes inserted at runtime by
	 * {@link #addChildNode} are included at the end of the List.
	 *
	 * @return See description.
	 */
	@Override
	public List<Node> getListChildNode() {
		if ((this.state != SimpleNode.State.CONFIG) && (this.state != SimpleNode.State.DYNAMICALLY_CREATED)) {
			throw new IllegalStateException("State must be CONFIG or DYNAMICALLY_BEING_COMPLETED. State: " + this.state);
		}

		this.createChildNodesFromConfig();

		// A copy is returned to prevent the internal Map from being modified by the
		// caller. Ideally, an unmodifiable List view of the Collection returned by
		// Map.values should be returned, but that does not seem possible.
		return new ArrayList<Node>(this.mapSimpleNodeChild.values());
	}

	/**
	 * Returns a child {@link Node}.
	 *
	 * @param name Name of the child Node.
	 * @return Child Node. null if no child of the specified name is currently
	 *   defined.
	 */
	@Override
	public Node getNodeChild(String name) {
		if ((this.state != SimpleNode.State.CONFIG) && (this.state != SimpleNode.State.DYNAMICALLY_CREATED)) {
			throw new IllegalStateException("State must be CONFIG or DYNAMICALLY_BEING_COMPLETED. State: " + this.state);
		}

		this.createChildNodesFromConfig();

		return this.mapSimpleNodeChild.get(name);
	}

	/**
	 * Creates the child {@link Node}'s from the ClassificationNodeConfig, if not
	 * already done.
	 */
	private void createChildNodesFromConfig() {
		// We simply use mapSimpleNodeChild being null as an indicator of the fact that
		// child SimpleNodes have not been created from ClassificationNodeConfig.
		// In the case the SimpleClassificationNode has been dynamically created
		// mapSimpleNodeChild has been assigned in the constructor and is not null.
		// Therefore there is no risk of accessing a null Config.
		if (this.mapSimpleNodeChild == null) {
			List<NodeConfig> listNodeConfigChild;

			// We used a LinkedHashMap to preserve insertion order.
			this.mapSimpleNodeChild = new LinkedHashMap<String, SimpleNode>();

			listNodeConfigChild = ((ClassificationNodeConfig)this.getNodeConfig()).getListChildNodeConfig();

			for (NodeConfig nodeConfigChild: listNodeConfigChild) {
				switch (nodeConfigChild.getNodeType()) {
				case CLASSIFICATION:
					SimpleClassificationNode simpleClassificationNode;

					simpleClassificationNode = new SimpleClassificationNode((ClassificationNodeConfig)nodeConfigChild, this);
					this.addNodeChild(simpleClassificationNode);
					simpleClassificationNode.init();
					break;

				case MODULE:
					SimpleModule simpleModule;

					simpleModule = new SimpleModule((ModuleConfig)nodeConfigChild, this);
					this.addNodeChild(simpleModule);
					simpleModule.init();
					break;
				}
			}
		}
	}

	@Override
	public boolean traverseNodeHierarchyDepthFirst(NodeType nodeTypeFilter, NodeVisitor nodeVisitor) {
		Set<Map.Entry<String, SimpleNode>> setMapEntry;

		if ((this.state != SimpleNode.State.CONFIG) && (this.state != SimpleNode.State.DYNAMICALLY_CREATED)) {
			throw new IllegalStateException("State must be CONFIG or DYNAMICALLY_BEING_COMPLETED. State: " + this.state);
		}

		this.createChildNodesFromConfig();

		setMapEntry = this.mapSimpleNodeChild.entrySet();

		for (Map.Entry<String, SimpleNode> mapEntry: setMapEntry) {
			SimpleNode simpleNode;

			simpleNode = mapEntry.getValue();

			switch (simpleNode.getNodeType()) {
			case CLASSIFICATION:
				/* If the children is a classification node, it must be traversed recursively. The
				 * visiting of this node will be handled by its own traversal.
				 */
				if (((SimpleClassificationNode)simpleNode).traverseNodeHierarchyDepthFirst(nodeTypeFilter, nodeVisitor)) {
					return true;
				}
				break;

			case MODULE:
				/* If the children is a module, it cannot be traversed so its visiting must be
				 * handled here.
				 */
				if ((nodeTypeFilter == null) || (nodeTypeFilter == NodeType.MODULE)) {
					if (nodeVisitor.visitNode(simpleNode)) {
						return true;
					}
				}

				break;
			}
		}

		/* The visiting of the current node, which is necessarily a classification node,
		 * must be handed separately in its own traversal.
		 */
		if ((nodeTypeFilter == null) || (nodeTypeFilter == NodeType.CLASSIFICATION)) {
			if (nodeVisitor.visitNode(this)) {
				return true;
			}
		}

		return false;
	}

	/**
	 * Adds a child {@link SimpleNode}.
	 * <p>
	 * This method can be called in various circumstances, both internal and external
	 * to this class.
	 * <p>
	 * When accessing the children of this SimpleClassificationNode, new child
	 * SiimpleNodes can be created and thus added according to the
	 * ClassificationNodeConfig. This is done by {@link #createChildNodesFromConfig}.
	 * <p>
	 * {@link UndefinedDescendantNodeManagerPlugin} can add new child Nodes
	 * dynamically at runtime, generally based on information obtained from an
	 * external system such as a SCM by using {@link NodeBuilder}.
	 * <p>
	 * This method has package scope to enforce the use of
	 * {@link ModelNodeBuilderFactory} implemented by {@link SimpleModel} to create
	 * new SimpleNode's.
	 *
	 * @param simpleNode Child SimpleNode.
	 */
	void addNodeChild(SimpleNode simpleNode) {
		if ((this.state != SimpleNode.State.CONFIG) && (this.state != SimpleNode.State.DYNAMICALLY_CREATED)) {
			throw new IllegalStateException("State must be CONFIG or DYNAMICALLY_BEING_COMPLETED. State: " + this.state);
		}

		if (simpleNode.getClassificationNodeParent() != this) {
			throw new RuntimeException("The current node " + this + " is not the parent of the new node " + simpleNode + '.');
		}

		this.createChildNodesFromConfig();

		if (this.mapSimpleNodeChild.containsKey(simpleNode.getName())) {
			throw new RuntimeException("A child node with the same name " + simpleNode.getName() + " exists in classification node " + this + '.');
		}

		this.mapSimpleNodeChild.put(simpleNode.getName(), simpleNode);
	}

	/**
	 * Returns a child {@link ClassificationNode}, dynamically creating it if it does not
	 * exist.
	 * <p>
	 * This method has package scope since SimpleModel can only be completed
	 * dynamically with new {@link SimpleClassificationNode}'s using
	 * {@link SimpleModel.getClassificationNode}.
	 *
	 * @param name Name of the child ClassificationNode.
	 * @return Child ClassificationNode. null if no child of the specified name is
	 *   currently defined and none can be dynamically created.
	 */
	SimpleClassificationNode getSimpleClassificationNodeChildDynamic(String name) {
		SimpleNode simpleNode;

		if ((this.state != SimpleNode.State.CONFIG) && (this.state != SimpleNode.State.DYNAMICALLY_CREATED)) {
			throw new IllegalStateException("State must be CONFIG or DYNAMICALLY_BEING_COMPLETED. State: " + this.state);
		}

		simpleNode = (SimpleNode)this.getNodeChild(name);

		if (simpleNode == null) {
			UndefinedDescendantNodeManagerPlugin undefinedDescendantNodeManagerPlugin;
			SimpleClassificationNode simpleClassificationNode;

			if (!this.isNodePluginExists(UndefinedDescendantNodeManagerPlugin.class, null)) {
				SimpleClassificationNode.logger.trace("Dynamic creation request for child classification node " + name + " of parent classification node " + this + " denied since the UndefinedDescendantNodeManagerPlugin plugin is not defined for the node.");
				return null;
			}

			undefinedDescendantNodeManagerPlugin = this.getNodePlugin(UndefinedDescendantNodeManagerPlugin.class, null);

			simpleClassificationNode = (SimpleClassificationNode)undefinedDescendantNodeManagerPlugin.requestClassificationNode(name);

			if (simpleClassificationNode == null) {
				SimpleClassificationNode.logger.trace("Dynamic creation request for child classification node " + name + " of parent classification node " + this + " denied, probably because classification nodes must be preconfigured.");
			}

			return simpleClassificationNode;
		}

		if (simpleNode.getNodeType() == NodeType.CLASSIFICATION){
			return (SimpleClassificationNode)simpleNode;
		} else {
			throw new RuntimeException("The child node " + name + " is not a classification node.");
		}
	}

	/**
	 * Returns a child {@link Module}, dynamically creating it if it does not exist.
	 * <p>
	 * This method has package scope since SimpleModel can only be completed
	 * dynamically with new {@link SimpleModule's using
	 * {@link SimpleModel.getModule}.
	 *
	 * @param name Name of the child Module.
	 * @return Child Module. null if no child of the specified name is currently
	 *   defined and none can be dynamically created.
	 */
	SimpleModule getSimpleModuleChildDynamic(String name) {
		SimpleNode simpleNode;

		if ((this.state != SimpleNode.State.CONFIG) && (this.state != SimpleNode.State.DYNAMICALLY_CREATED)) {
			throw new IllegalStateException("State must be CONFIG or DYNAMICALLY_BEING_COMPLETED. State: " + this.state);
		}

		simpleNode = (SimpleNode)this.getNodeChild(name);

		if (simpleNode == null) {
			UndefinedDescendantNodeManagerPlugin undefinedDescendantNodeManagerPlugin;
			SimpleModule module;

			if (!this.isNodePluginExists(UndefinedDescendantNodeManagerPlugin.class, null)) {
				SimpleClassificationNode.logger.trace("Dynamic creation request for child module " + name + " of parent classification node " + this + " denied since the UndefinedDescendantNodeManagerPlugin plugin is not defined for the node.");
				return null;
			}

			undefinedDescendantNodeManagerPlugin = this.getNodePlugin(UndefinedDescendantNodeManagerPlugin.class, null);

			module = (SimpleModule)undefinedDescendantNodeManagerPlugin.requestModule(name);

			if (module == null) {
				SimpleClassificationNode.logger.trace("Dynamic creation request for child module " + name + " of parent classification node " + this + " denied, probably because the module does not exist in the SCM.");
			}

			return module;
		}

		if (simpleNode.getNodeType() == NodeType.MODULE) {
			return (SimpleModule)simpleNode;
		} else {
			throw new RuntimeException("The child node " + name + " is not a module.");
		}
	}

	@Override
	public void setNodeConfigTransferObject(NodeConfigTransferObject nodeConfigTransferObject, OptimisticLockHandle optimisticLockHandle)
			throws OptimisticLockException, DuplicateNodeException {
		boolean indConfigNew;

		// We need to save whether the state was State.CONFIG_NEW since
		// super.setNodeConfigValue transitions the state to State.CONFIG.
		indConfigNew = this.state == State.CONFIG_NEW;

		// Validates the state so we do not need to do it here.
		// here.
		super.extractNodeConfigTransferObject(nodeConfigTransferObject, optimisticLockHandle);

		// If the parent SimpldClassificationNode is null it means this is the root
		// SimpleClassificationNode, in which case we must update it in the SimpleModel.
		if (indConfigNew && (this.getClassificationNodeParent() == null)) {
			((SimpleModel)this.getModel()).setSimpleClassificationNodeRoot(this);
		}

		this.state = State.CONFIG;
		this.init();
	}

	/**
	 * Sets a child {@link SimpleNode}.
	 * <p>
	 * In the case of duplicate SimpleNode, this method throws RuntimeException and
	 * not DuplicateNodeException. The caller is responsible to handle such case.
	 * <p>
	 * This method is called by {@link SimpleNode#extractNodeConfigTransferObject}.
	 *
	 * @param name Name of the Child SimpleNode. Passed by the caller so that getName
	 *   does not need to be called, which would be invalid when the SimpleNode is
	 *   being created.
	 * @param simpleNodeChild Child SimpleNode.
	 */
	void setSimpleNodeChild(String name, SimpleNode simpleNodeChild) {
		this.checkMutable();

		if ((this.state != State.CONFIG) && (this.state != State.CONFIG_NEW)) {
			throw new IllegalStateException("State must be CONFIG or CONFIG_NEW. State: " + this.state);
		}

		this.createChildNodesFromConfig();

		if (this.mapSimpleNodeChild.containsKey(name)) {
			throw new RuntimeException("SimpleNode with name " + name + " already exists.");
		}

		this.mapSimpleNodeChild.put(name, simpleNodeChild);
	}

	/**
	 * Renames a child {@link SimpleNode}.
	 * <p>
	 * In the case of duplicate SimpleNode, this method throws RuntimeException and
	 * not DuplicateNodeException. The caller is responsible to handle such case.
	 * <p>
	 * This method is called by
	 * {@link SimpleNode#setNodeConfigTransferObject}.
	 *
	 * @param currentName Current name.
	 * @param newName New name.
	 */
	void renameSimpleNodeChild(String currentName, String newName) throws DuplicateNodeException {
		this.checkMutable();

		if ((this.state != State.CONFIG) && (this.state != State.CONFIG_NEW)) {
			throw new IllegalStateException("State must be CONFIG or CONFIG_NEW. State: " + this.state);
		}

		this.createChildNodesFromConfig();

		if (!this.mapSimpleNodeChild.containsKey(currentName)) {
			throw new RuntimeException("SimpleNode with current name " + currentName + " not found.");
		}

		if (this.mapSimpleNodeChild.containsKey(newName)) {
			throw new RuntimeException("SimpleNode with new name " + newName + " already exists.");
		}

		this.mapSimpleNodeChild.put(newName, this.mapSimpleNodeChild.remove(currentName));
	}

	/**
	 * Removes a child {@link Node}.
	 * <p>
	 * This method is intended to be called by
	 * {@link SimpleNode#delete}.
	 *
	 * @param childNodeName
	 */
	void removeChildNode(String childNodeName) {
		this.checkMutable();

		if ((this.state != State.CONFIG) && (this.state != State.CONFIG_NEW)) {
			throw new IllegalStateException("State must be CONFIG or CONFIG_NEW. State: " + this.state);
		}

		this.createChildNodesFromConfig();

		if (this.mapSimpleNodeChild.remove(childNodeName) == null) {
			throw new RuntimeException("SimpleNode with name " + childNodeName + " not found.");
		}
	}

	@Override
	public MutableClassificationNode createChildMutableClassificationNode() {
		this.checkMutable();
		this.checkNotDeleted();

		return new SimpleClassificationNode(((MutableClassificationNodeConfig)this.getNodeConfig()).createChildMutableClassificationNodeConfig(), this);
	}

	@Override
	public MutableModule createChildMutableModule() {
		this.checkMutable();
		this.checkNotDeleted();

		return new SimpleModule(((MutableClassificationNodeConfig)this.getNodeConfig()).createChildMutableModuleConfig(), this);
	}

	@Override
	protected void cleanCaches(boolean indDelete) {
		// It would seem like using traverseNodeHierarchyDepthFirst would be cleaner since
		// that method already exists. But the problem is that when cleanCaches is
		// recursively called we would have to know it is being called by
		// traverseNodeHierarchyDepthFirst and not perform another traversal. It is
		// therefore much easier to simple iterate over the immediate children and let the
		// method itself handle recursion.
		// Note that traversal is depth first in order to handle the SimpleNode's from
		// bottom to top.
		if (this.mapSimpleNodeChild != null) {
			for(SimpleNode simpleNode: this.mapSimpleNodeChild.values()) {
				simpleNode.cleanCaches(indDelete);
			}
		}

		super.cleanCaches(indDelete);
	}
}
