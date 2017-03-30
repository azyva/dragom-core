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

package org.azyva.dragom.model.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.azyva.dragom.model.ClassificationNode;
import org.azyva.dragom.model.Model;
import org.azyva.dragom.model.ModelNodeBuilderFactory;
import org.azyva.dragom.model.Module;
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
import org.azyva.dragom.model.plugin.UndefinedDescendantNodeManagerPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default implementation of {@link ClassificationNode} and
 * {@link MutableClassificationNode}.
 *
 * @author David Raymond
 */
public class DefaultClassificationNode extends DefaultNode implements ClassificationNode, MutableClassificationNode {
  private static final Logger logger = LoggerFactory.getLogger(DefaultClassificationNode.class);

  /**
   * Map of child {@link DefaultNode}'s.
   * <p>
   * For a DefaultClassificationNode base on {@link ClassificationNodeConfig}
   * this is initially null which causes it to be lazily created by
   * {@link #createChildNodesFromConfig}. If dynamically created, it is initially
   * assigned to an empty Map so that it does not get initialized with a null
   * {@link ClassificationNodeConfig}.
   */
  private Map<String, DefaultNode> mapDefaultNodeChild;

  /**
   * Constructor used when dynamically completing a {@link DefaultModel}.
   * <p>
   * This constructor has package scope to enforce the use of
   * {@link ModelNodeBuilderFactory#createClassificationNodeBuilder} implemented
   * by DefaultModel to create new {@link DefaultClassificationNode}'s.
   *
   * @param defaultModel DefaultModel.
   */
  DefaultClassificationNode(DefaultModel defaultModel) {
    super(defaultModel);

    // This ensures that createChildNodesFromConfig does not attempt to create the
    // child DefaultNode's since a Config is not available.
    this.mapDefaultNodeChild = new LinkedHashMap<String, DefaultNode>();
  }

  /**
   * Constructor for the root DefaultClassificationNode when creating a
   * {@link Model} from {@link Config}.
   * <p>
   * Must not be used for DefaultClassificationNode's other than the root
   * DefaultClassificationNode. Use
   * {@link #DefaultClassificationNode(ClassificationNodeConfig, DefaultClassificationNode)}
   * for these DefaultClassificationNode's.
   * <p>
   * This constructor is expected to be called by {@link DefaultModel}'s constructor
   * when creating the root DefaultClassificationNode.
   * <p>
   * This constructor has package scope to enforce the use of
   * {@link DefaultModel#DefaultModel} to create a complete Model from
   * {@link Config}.
   *
   * @param classificationNodeConfig ClassificationNodeConfig.
   * @param defaultModel DefaultModel.
   */
  DefaultClassificationNode(ClassificationNodeConfig classificationNodeConfig, DefaultModel defaultModel) {
    super(classificationNodeConfig, defaultModel);
  }

  /**
   * Constructor for DefaultClassificationNode's other than the root
   * DefaultClassificationNode when creating a {@link Model} from {@link Config}.
   * <p>
   * Must not be used for the root DefaultClassificationNode. Use
   * {@link #DefaultClassificationNode(ClassificationNodeConfig, DefaultModel)} for
   * the root DefaultClassificationNode.
   * <p>
   * This constructor has package scope to enforce the use of
   * {@link DefaultModel#DefaultModel} to create a complete Model from {@link Config}.
   *
   * @param classificationNodeConfig ClassificationNodeConfig.
   * @param defaultClassificationNodeParent Parent DefaultClassificationNode.
   */
  DefaultClassificationNode(ClassificationNodeConfig classificationNodeConfig, DefaultClassificationNode defaultClassificationNodeParent) {
    super(classificationNodeConfig, defaultClassificationNodeParent);
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
   * The order of the child DefaultNode's is as defined by the underlying
   * ClassificationNodeConfig, with child DefaultNode's inserted at runtime by
   * {@link #addNodeChild} are included at the end of the List.
   *
   * @return See description.
   */
  @Override
  public List<Node> getListChildNode() {
    if ((this.state != DefaultNode.State.CONFIG) && (this.state != DefaultNode.State.DYNAMICALLY_CREATED)) {
      throw new IllegalStateException("State must be CONFIG or DYNAMICALLY_BEING_COMPLETED. State: " + this.state);
    }

    this.createChildNodesFromConfig();

    // A copy is returned to prevent the internal Map from being modified by the
    // caller. Ideally, an unmodifiable List view of the Collection returned by
    // Map.values should be returned, but that does not seem possible.
    return new ArrayList<Node>(this.mapDefaultNodeChild.values());
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
    if ((this.state != DefaultNode.State.CONFIG) && (this.state != DefaultNode.State.DYNAMICALLY_CREATED)) {
      throw new IllegalStateException("State must be CONFIG or DYNAMICALLY_BEING_COMPLETED. State: " + this.state);
    }

    this.createChildNodesFromConfig();

    return this.mapDefaultNodeChild.get(name);
  }

  /**
   * Creates the child {@link Node}'s from the ClassificationNodeConfig, if not
   * already done.
   */
  private void createChildNodesFromConfig() {
    // We simply use mapDefaultNodeChild being null as an indicator of the fact that
    // child DefaultNode's have not been created from ClassificationNodeConfig.
    // In the case the DefaultClassificationNode has been dynamically created
    // mapDefaultNodeChild has been assigned in the constructor and is not null.
    // Therefore there is no risk of accessing a null Config.
    if (this.mapDefaultNodeChild == null) {
      List<NodeConfig> listNodeConfigChild;

      // We used a LinkedHashMap to preserve insertion order.
      this.mapDefaultNodeChild = new LinkedHashMap<String, DefaultNode>();

      listNodeConfigChild = ((ClassificationNodeConfig)this.getNodeConfig()).getListChildNodeConfig();

      for (NodeConfig nodeConfigChild: listNodeConfigChild) {
        switch (nodeConfigChild.getNodeType()) {
        case CLASSIFICATION:
          DefaultClassificationNode defaultClassificationNode;

          defaultClassificationNode = new DefaultClassificationNode((ClassificationNodeConfig)nodeConfigChild, this);
          this.addNodeChild(defaultClassificationNode);
          defaultClassificationNode.init();
          break;

        case MODULE:
          DefaultModule defaultModule;

          defaultModule = new DefaultModule((ModuleConfig)nodeConfigChild, this);
          this.addNodeChild(defaultModule);
          defaultModule.init();
          break;
        }
      }
    }
  }

  @Override
  public NodeVisitor.VisitControl traverseNodeHierarchy(NodeType nodeTypeFilter, boolean indDepthFirst, NodeVisitor nodeVisitor) {
    NodeVisitor.VisitControl visitControl;
    Set<Map.Entry<String, DefaultNode>> setMapEntry;

    if ((this.state != DefaultNode.State.CONFIG) && (this.state != DefaultNode.State.DYNAMICALLY_CREATED)) {
      throw new IllegalStateException("State must be CONFIG or DYNAMICALLY_BEING_COMPLETED. State: " + this.state);
    }

    this.createChildNodesFromConfig();

    visitControl = nodeVisitor.visitNode(NodeVisitor.VisitAction.STEP_IN, this);

    try {
      switch (visitControl) {
      case CONTINUE:
        break;
      case ABORT:
        return NodeVisitor.VisitControl.ABORT;
      case SKIP_CHILDREN:
        return NodeVisitor.VisitControl.CONTINUE;
      case SKIP_CURRENT_BASE:
        return NodeVisitor.VisitControl.SKIP_CURRENT_BASE;
      }

      if (!indDepthFirst && ((nodeTypeFilter == null) || (nodeTypeFilter == NodeType.CLASSIFICATION))) {
        visitControl = nodeVisitor.visitNode(NodeVisitor.VisitAction.VISIT, this);

        switch (visitControl) {
        case CONTINUE:
          break;
        case ABORT:
          return NodeVisitor.VisitControl.ABORT;
        case SKIP_CHILDREN:
          return NodeVisitor.VisitControl.CONTINUE;
        case SKIP_CURRENT_BASE:
          return NodeVisitor.VisitControl.SKIP_CURRENT_BASE;
        }
      }

      setMapEntry = this.mapDefaultNodeChild.entrySet();

      for (Map.Entry<String, DefaultNode> mapEntry: setMapEntry) {
        DefaultNode defaultNode;

        defaultNode = mapEntry.getValue();

        switch (defaultNode.getNodeType()) {
        case CLASSIFICATION:
          // If the children is a classification node, it must be traversed recursively. The
          // visiting of this node will be handled by its own traversal.
          visitControl = ((DefaultClassificationNode)defaultNode).traverseNodeHierarchy(nodeTypeFilter, indDepthFirst, nodeVisitor);

          switch (visitControl) {
          case CONTINUE:
            break;
          case ABORT:
            return NodeVisitor.VisitControl.ABORT;
          case SKIP_CHILDREN:
            throw new RuntimeException("Unexpected SKIP_CHILDREN returned from traverseNodeHierarchy.");
          case SKIP_CURRENT_BASE:
            return NodeVisitor.VisitControl.SKIP_CURRENT_BASE;
          }

          break;

        case MODULE:
          // If the children is a module, it cannot be traversed so its visiting must be
          // handled here.
          if ((nodeTypeFilter == null) || (nodeTypeFilter == NodeType.MODULE)) {
            visitControl = nodeVisitor.visitNode(NodeVisitor.VisitAction.VISIT, defaultNode);

            switch (visitControl) {
            case CONTINUE:
              break;
            case ABORT:
              return NodeVisitor.VisitControl.ABORT;
            case SKIP_CHILDREN:
              throw new RuntimeException("Unexpected SKIP_CHILDREN for a Module.");
            case SKIP_CURRENT_BASE:
              return NodeVisitor.VisitControl.SKIP_CURRENT_BASE;
            }
          }

          break;
        }
      }

      // The visiting of the current node, which is necessarily a classification node,
      // must be handled separately in its own traversal.
      if (indDepthFirst && ((nodeTypeFilter == null) || (nodeTypeFilter == NodeType.CLASSIFICATION))) {
        visitControl = nodeVisitor.visitNode(NodeVisitor.VisitAction.VISIT, this);

        switch (visitControl) {
        case CONTINUE:
          break;
        case ABORT:
          return NodeVisitor.VisitControl.ABORT;
        case SKIP_CHILDREN:
          throw new RuntimeException("Unexpected SKIP_CHILDREN for a depth-first traversal.");
        case SKIP_CURRENT_BASE:
          return NodeVisitor.VisitControl.SKIP_CURRENT_BASE;
        }
      }
    } finally {
      visitControl = nodeVisitor.visitNode(NodeVisitor.VisitAction.STEP_OUT, this);
    }

    // If we get here, visitControl necessarily has the value assigned in the finally
    // block above and we are exiting normally, expecting to return CONTINUE. But
    // STEP_OUT can override that return. If the code above has already called return,
    // we still do the STEP_OUT (because of the finally block), but we will not get
    // here and will ignore the return corresponding to STEP_OUT.

    switch (visitControl) {
    case CONTINUE:
      break;
    case ABORT:
      return NodeVisitor.VisitControl.ABORT;
    case SKIP_CHILDREN:
      throw new RuntimeException("Unexpected SKIP_CHILDREN for STEP_OUT.");
    case SKIP_CURRENT_BASE:
      return NodeVisitor.VisitControl.SKIP_CURRENT_BASE;
    }

    return NodeVisitor.VisitControl.CONTINUE;
  }

  /**
   * Adds a child {@link DefaultNode}.
   * <p>
   * This method can be called in various circumstances, both internal and external
   * to this class.
   * <p>
   * When accessing the children of this DefaultClassificationNode, new child
   * SiimpleNodes can be created and thus added according to the
   * ClassificationNodeConfig. This is done by {@link #createChildNodesFromConfig}.
   * <p>
   * {@link UndefinedDescendantNodeManagerPlugin} can add new child Nodes
   * dynamically at runtime, generally based on information obtained from an
   * external system such as a SCM by using {@link NodeBuilder}.
   * <p>
   * This method has package scope to enforce the use of
   * {@link ModelNodeBuilderFactory} implemented by {@link DefaultModel} to create
   * new DefaultNode's.
   *
   * @param defaultNode Child DefaultNode.
   */
  void addNodeChild(DefaultNode defaultNode) {
    if ((this.state != DefaultNode.State.CONFIG) && (this.state != DefaultNode.State.DYNAMICALLY_CREATED)) {
      throw new IllegalStateException("State must be CONFIG or DYNAMICALLY_BEING_COMPLETED. State: " + this.state);
    }

    if (defaultNode.getClassificationNodeParent() != this) {
      throw new RuntimeException("The current node " + this + " is not the parent of the new node " + defaultNode + '.');
    }

    this.createChildNodesFromConfig();

    if (this.mapDefaultNodeChild.containsKey(defaultNode.getName())) {
      throw new RuntimeException("A child node with the same name " + defaultNode.getName() + " exists in classification node " + this + '.');
    }

    this.mapDefaultNodeChild.put(defaultNode.getName(), defaultNode);
  }

  /**
   * Returns a child {@link ClassificationNode}, dynamically creating it if it does not
   * exist.
   * <p>
   * This method has package scope since DefaultModel can only be completed
   * dynamically with new {@link DefaultClassificationNode}'s using
   * {@link DefaultModel#getClassificationNode}.
   *
   * @param name Name of the child ClassificationNode.
   * @return Child ClassificationNode. null if no child of the specified name is
   *   currently defined and none can be dynamically created.
   */
  DefaultClassificationNode getDefaultClassificationNodeChildDynamic(String name) {
    DefaultNode defaultNode;

    if ((this.state != DefaultNode.State.CONFIG) && (this.state != DefaultNode.State.DYNAMICALLY_CREATED)) {
      throw new IllegalStateException("State must be CONFIG or DYNAMICALLY_BEING_COMPLETED. State: " + this.state);
    }

    defaultNode = (DefaultNode)this.getNodeChild(name);

    if (defaultNode == null) {
      UndefinedDescendantNodeManagerPlugin undefinedDescendantNodeManagerPlugin;
      DefaultClassificationNode defaultClassificationNode;

      if (!this.isNodePluginExists(UndefinedDescendantNodeManagerPlugin.class, null)) {
        DefaultClassificationNode.logger.trace("Dynamic creation request for child classification node " + name + " of parent classification node " + this + " denied since the UndefinedDescendantNodeManagerPlugin plugin is not defined for the node.");
        return null;
      }

      undefinedDescendantNodeManagerPlugin = this.getNodePlugin(UndefinedDescendantNodeManagerPlugin.class, null);

      defaultClassificationNode = (DefaultClassificationNode)undefinedDescendantNodeManagerPlugin.requestClassificationNode(name);

      if (defaultClassificationNode == null) {
        DefaultClassificationNode.logger.trace("Dynamic creation request for child classification node " + name + " of parent classification node " + this + " denied, probably because classification nodes must be preconfigured.");
      }

      return defaultClassificationNode;
    }

    if (defaultNode.getNodeType() == NodeType.CLASSIFICATION){
      return (DefaultClassificationNode)defaultNode;
    } else {
      throw new RuntimeException("The child node " + name + " is not a classification node.");
    }
  }

  /**
   * Returns a child {@link Module}, dynamically creating it if it does not exist.
   * <p>
   * This method has package scope since DefaultModel can only be completed
   * dynamically with new {@link DefaultModule}'s using
   * {@link DefaultModel#getModule}.
   *
   * @param name Name of the child Module.
   * @return Child Module. null if no child of the specified name is currently
   *   defined and none can be dynamically created.
   */
  DefaultModule getDefaultModuleChildDynamic(String name) {
    DefaultNode defaultNode;

    if ((this.state != DefaultNode.State.CONFIG) && (this.state != DefaultNode.State.DYNAMICALLY_CREATED)) {
      throw new IllegalStateException("State must be CONFIG or DYNAMICALLY_BEING_COMPLETED. State: " + this.state);
    }

    defaultNode = (DefaultNode)this.getNodeChild(name);

    if (defaultNode == null) {
      UndefinedDescendantNodeManagerPlugin undefinedDescendantNodeManagerPlugin;
      DefaultModule module;

      if (!this.isNodePluginExists(UndefinedDescendantNodeManagerPlugin.class, null)) {
        DefaultClassificationNode.logger.trace("Dynamic creation request for child module " + name + " of parent classification node " + this + " denied since the UndefinedDescendantNodeManagerPlugin plugin is not defined for the node.");
        return null;
      }

      undefinedDescendantNodeManagerPlugin = this.getNodePlugin(UndefinedDescendantNodeManagerPlugin.class, null);

      module = (DefaultModule)undefinedDescendantNodeManagerPlugin.requestModule(name);

      if (module == null) {
        DefaultClassificationNode.logger.trace("Dynamic creation request for child module " + name + " of parent classification node " + this + " denied, probably because the module does not exist in the SCM.");
      }

      return module;
    }

    if (defaultNode.getNodeType() == NodeType.MODULE) {
      return (DefaultModule)defaultNode;
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
    // DefaultClassificationNode, in which case we must update it in the DefaultModel.
    if (indConfigNew && (this.getClassificationNodeParent() == null)) {
      ((DefaultModel)this.getModel()).setDefaultClassificationNodeRoot(this);
    }

    this.state = State.CONFIG;
    this.init();
  }

  /**
   * Sets a child {@link DefaultNode}.
   * <p>
   * In the case of duplicate DefaultNode, this method throws RuntimeException and
   * not DuplicateNodeException. The caller is responsible to handle such case.
   * <p>
   * This method is called by {@link DefaultNode#extractNodeConfigTransferObject}.
   *
   * @param name Name of the Child DefaultNode. Passed by the caller so that getName
   *   does not need to be called, which would be invalid when the DefaultNode is
   *   being created.
   * @param defaultNodeChild Child DefaultNode.
   */
  void setDefaultNodeChild(String name, DefaultNode defaultNodeChild) {
    this.checkMutable();

    if ((this.state != State.CONFIG) && (this.state != State.CONFIG_NEW)) {
      throw new IllegalStateException("State must be CONFIG or CONFIG_NEW. State: " + this.state);
    }

    this.createChildNodesFromConfig();

    if (this.mapDefaultNodeChild.containsKey(name)) {
      throw new RuntimeException("DefaultNode with name " + name + " already exists.");
    }

    this.mapDefaultNodeChild.put(name, defaultNodeChild);
  }

  /**
   * Renames a child {@link DefaultNode}.
   * <p>
   * In the case of duplicate DefaultNode, this method throws RuntimeException and
   * not DuplicateNodeException. The caller is responsible to handle such case.
   * <p>
   * This method is called by
   * {@link DefaultNode#setNodeConfigTransferObject}.
   *
   * @param currentName Current name.
   * @param newName New name.
   * @throws DuplicateNodeException If the renaming would introduce a duplicate
   *   DefaultNode.
   */
  void renameDefaultNodeChild(String currentName, String newName) throws DuplicateNodeException {
    this.checkMutable();

    if ((this.state != State.CONFIG) && (this.state != State.CONFIG_NEW)) {
      throw new IllegalStateException("State must be CONFIG or CONFIG_NEW. State: " + this.state);
    }

    this.createChildNodesFromConfig();

    if (!this.mapDefaultNodeChild.containsKey(currentName)) {
      throw new RuntimeException("DefaultNode with current name " + currentName + " not found.");
    }

    if (this.mapDefaultNodeChild.containsKey(newName)) {
      throw new RuntimeException("DefaultNode with new name " + newName + " already exists.");
    }

    this.mapDefaultNodeChild.put(newName, this.mapDefaultNodeChild.remove(currentName));
  }

  /**
   * Removes a child {@link Node}.
   * <p>
   * This method is intended to be called by
   * {@link DefaultNode#delete}.
   *
   * @param childNodeName Name of the child Node.
   */
  void removeChildNode(String childNodeName) {
    this.checkMutable();

    if ((this.state != State.CONFIG) && (this.state != State.CONFIG_NEW)) {
      throw new IllegalStateException("State must be CONFIG or CONFIG_NEW. State: " + this.state);
    }

    this.createChildNodesFromConfig();

    if (this.mapDefaultNodeChild.remove(childNodeName) == null) {
      throw new RuntimeException("DefaultNode with name " + childNodeName + " not found.");
    }
  }

  @Override
  public MutableClassificationNode createChildMutableClassificationNode() {
    this.checkMutable();
    this.checkNotDeleted();

    return new DefaultClassificationNode(((MutableClassificationNodeConfig)this.getNodeConfig()).createChildMutableClassificationNodeConfig(), this);
  }

  @Override
  public MutableModule createChildMutableModule() {
    this.checkMutable();
    this.checkNotDeleted();

    return new DefaultModule(((MutableClassificationNodeConfig)this.getNodeConfig()).createChildMutableModuleConfig(), this);
  }

  @Override
  protected void cleanCaches(boolean indDelete) {
    // It would seem like using traverseNodeHierarchyDepthFirst would be cleaner since
    // that method already exists. But the problem is that when cleanCaches is
    // recursively called we would have to know it is being called by
    // traverseNodeHierarchyDepthFirst and not perform another traversal. It is
    // therefore much easier to simple iterate over the immediate children and let the
    // method itself handle recursion.
    // Note that traversal is depth first in order to handle the DefaultNode's from
    // bottom to top.
    if (this.mapDefaultNodeChild != null) {
      for(DefaultNode defaultNode: this.mapDefaultNodeChild.values()) {
        defaultNode.cleanCaches(indDelete);
      }
    }

    super.cleanCaches(indDelete);
  }
}
