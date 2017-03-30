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

package org.azyva.dragom.job;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;

import org.apache.commons.lang.StringUtils;
import org.azyva.dragom.execcontext.ExecContext;
import org.azyva.dragom.execcontext.plugin.UserInteractionCallbackPlugin;
import org.azyva.dragom.execcontext.support.ExecContextHolder;
import org.azyva.dragom.model.ClassificationNode;
import org.azyva.dragom.model.Model;
import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.Node;
import org.azyva.dragom.model.NodePath;
import org.azyva.dragom.model.NodeVisitor;
import org.azyva.dragom.model.config.NodeType;
import org.azyva.dragom.util.RuntimeExceptionAbort;
import org.azyva.dragom.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class for implementing jobs implementing {@link ModelVisitorJob}.
 *
 * <p>It factors out code that is often encountered in these types of jobs.
 *
 * <p>This class does not attempt to completely encapsulate its implementation. It
 * has protected instance variables available to subclasses to simplify
 * implementation.
 *
 * @author David Raymond
 */
public abstract class ModelVisitorJobAbstractImpl implements ModelVisitorJob {
  /**
   * Logger for the class.
   */
  private static final Logger logger = LoggerFactory.getLogger(ModelVisitorJobAbstractImpl.class);

  /**
   * Exceptional condition representing the fact that an exception is thrown during
   * the visit of Node.
   */
  protected static final String EXCEPTIONAL_COND_EXCEPTION_THROWN_WHILE_VISITING_NODE = "EXCEPTION_THROWN_WHILE_VISITING_NODE";

  /**
   * Exceptional condition representing the fact that a Node could not be found for
   * a NodePath.
   */
  protected static final String EXCEPTIONAL_COND_NODE_NOT_FOUND = "NODE_NOT_FOUND";

  /**
   * See description in ResourceBundle.
   */
  protected static final String MSG_PATTERN_KEY_INITIATING_TRAVERSAL_BASE_NODE_PATH = "INITIATING_TRAVERSAL_BASE_NODE_PATH";

  /**
   * See description in ResourceBundle.
   */
  protected static final String MSG_PATTERN_KEY_EXCEPTION_THROWN_WHILE_VISITING_NODE = "EXCEPTION_THROWN_WHILE_VISITING_NODE";

  /**
   * See description in ResourceBundle.
   */
  protected static final String MSG_PATTERN_KEY_TRAVERSAL_BASE_NODE_PATH_COMPLETED = "TRAVERSAL_BASE_NODE_PATH_COMPLETED";

  /**
   * See description in ResourceBundle.
   */
  protected static final String MSG_PATTERN_KEY_ACTIONS_PERFORMED = "ACTIONS_PERFORMED";

  /**
   * See description in ResourceBundle.
   */
  protected static final String MSG_PATTERN_KEY_NO_ACTIONS_PERFORMED = "NO_ACTIONS_PERFORMED";

  /**
   * See description in ResourceBundle.
   */
  protected static final String MSG_PATTERN_KEY_NODE_NOT_FOUND = "NODE_NOT_FOUND";

  /**
   * See description in ResourceBundle.
   */
  protected static final String MSG_PATTERN_KEY_ = "";

  /**
   * ResourceBundle specific to this class.
   */
  protected static final ResourceBundle resourceBundle = ResourceBundle.getBundle(ModelVisitorJobAbstractImpl.class.getName() + "ResourceBundle");

  /**
   * Indicates to perform a depth-first traversal instead of parent-first.
   */
  protected boolean indDepthFirst;

  /**
   * NodeType to visit. If null, all NodeType's are visited.
   */
  protected NodeType nodeTypeFilter;

  /**
   * List of base NodePath's from which the traversal is to be performed.
   */
  protected List<NodePath> listNodePathBase;
  /**
   * Used to accumulate a description for the actions performed.
   */
  protected List<String> listActionsPerformed;

  /**
   * NodeVisitor to implement the {@link Node} visits.
   */
  private class Visitor implements NodeVisitor {
    @Override
    public NodeVisitor.VisitControl visitNode(NodeVisitor.VisitAction visitAction, Node node) {
      NodeVisitor.VisitControl visitControl;


      if (visitAction != NodeVisitor.VisitAction.VISIT) {
        return NodeVisitor.VisitControl.CONTINUE;
      }

      visitControl = NodeVisitor.VisitControl.CONTINUE;

      try {
        if ((node.getNodeType() == NodeType.CLASSIFICATION) && (ModelVisitorJobAbstractImpl.this.nodeTypeFilter == null || ModelVisitorJobAbstractImpl.this.nodeTypeFilter == NodeType.CLASSIFICATION)) {
          visitControl = ModelVisitorJobAbstractImpl.this.visitClassificationNode((ClassificationNode)node);
        }

        if ((node.getNodeType() == NodeType.MODULE) && (ModelVisitorJobAbstractImpl.this.nodeTypeFilter == null || ModelVisitorJobAbstractImpl.this.nodeTypeFilter == NodeType.MODULE)) {
          visitControl = ModelVisitorJobAbstractImpl.this.visitModule((Module)node);
        }
      } catch (RuntimeException re) {
        Util.ToolExitStatusAndContinue toolExitStatusAndContinue;

        toolExitStatusAndContinue = Util.handleToolExitStatusAndContinueForExceptionalCond(null, ModelVisitorJobAbstractImpl.EXCEPTIONAL_COND_EXCEPTION_THROWN_WHILE_VISITING_NODE);

        if (toolExitStatusAndContinue.indContinue) {
          ExecContextHolder.get().getExecContextPlugin(UserInteractionCallbackPlugin.class).provideInfo(MessageFormat.format(ModelVisitorJobAbstractImpl.resourceBundle.getString(ModelVisitorJobAbstractImpl.MSG_PATTERN_KEY_EXCEPTION_THROWN_WHILE_VISITING_NODE), toolExitStatusAndContinue.toolExitStatus, node.getNodePath(), Util.getStackTrace(re)));
        } else {
          throw new RuntimeExceptionAbort(MessageFormat.format(ModelVisitorJobAbstractImpl.resourceBundle.getString(ModelVisitorJobAbstractImpl.MSG_PATTERN_KEY_EXCEPTION_THROWN_WHILE_VISITING_NODE), toolExitStatusAndContinue.toolExitStatus, node.getNodePath(), Util.getStackTrace(re)));
        }
      }

      if (visitControl == NodeVisitor.VisitControl.ABORT) {
        // It may already have been done, but it does not matter to do it again.
        Util.setAbort();
      }

      return visitControl;
    }
  }

  /**
   * Constructor.
   *
   * @param listNodePathBase List of base NodePath's from which the traversal is
   *   to be performed.
   */
  protected ModelVisitorJobAbstractImpl(List<NodePath> listNodePathBase) {
    if (listNodePathBase == null) {
      listNodePathBase = new ArrayList<NodePath>();
      listNodePathBase.add(NodePath.ROOT);
    }

    this.listNodePathBase = listNodePathBase;

    this.listActionsPerformed = new ArrayList<String>();
  }

  /**
   * Indicates if the traversal must be depth-first instead of parent-first.
   *
   * @param indDepthFirst See description.
   */
  protected void setIndDepthFirst(boolean indDepthFirst) {
    this.indDepthFirst = indDepthFirst;
  }

  /**
   * Sets the NodeType filter.
   *
   * <p>If null, all NodeType's are visited.
   *
   * @param nodeTypeFilter See descrption.
   */
  protected void setNodeTypeFilter(NodeType nodeTypeFilter) {
    this.nodeTypeFilter = nodeTypeFilter;
  }

 /*
  * This class provides a default implementation which calls
  * {@link #beforeIterateListNodePathBase}, {@link #iterateListNodePathBase} and
  * {@link #afterIterateListNodePathBase}. If ever this behavior is not appropriate
  * for the job, subclasses can simply override the method. Alternatively, the
  * methods mentioned above can be overridden individually.
  */
  @Override
  public void performJob() {
    this.beforeIterateListNodePathBase();
    this.iterateListNodePathBase();
    this.afterIterateListNodePathBase();
  }

  /**
   * Called by {@link #performJob} before the iteration through the List of base
   * {@link NodePath}'s.
   */
  protected void beforeIterateListNodePathBase() {
  }

  /**
   * Called by {@link #performJob} to iterate through the List of base
   * {@link NodePath}'s calling visitNode for the {@link Node}'s
   * corresponding to each NodePath.
   */
  protected void iterateListNodePathBase() {
    UserInteractionCallbackPlugin userInteractionCallbackPlugin;

    userInteractionCallbackPlugin = ExecContextHolder.get().getExecContextPlugin(UserInteractionCallbackPlugin.class);

    userInteractionCallbackPlugin.provideInfo(MessageFormat.format(Util.getLocalizedMsgPattern(Util.MSG_PATTERN_KEY_STARTING_JOB), this.getClass().getSimpleName()));

    ModelVisitorJobAbstractImpl.logger.info("Starting the iteration among the base NodePath's " + this.listNodePathBase + '.');

    for (NodePath nodePathBase: this.listNodePathBase) {
      userInteractionCallbackPlugin.provideInfo(MessageFormat.format(ModelVisitorJobAbstractImpl.resourceBundle.getString(ModelVisitorJobAbstractImpl.MSG_PATTERN_KEY_INITIATING_TRAVERSAL_BASE_NODE_PATH), nodePathBase));

      try {
        this.initiateVisitBaseNodePath(nodePathBase);
      } catch (RuntimeExceptionAbort rea) {
        throw rea;
      } catch (RuntimeException re) {
        Util.ToolExitStatusAndContinue toolExitStatusAndContinue;

        toolExitStatusAndContinue = Util.handleToolExitStatusAndContinueForExceptionalCond(null, ModelVisitorJobAbstractImpl.EXCEPTIONAL_COND_EXCEPTION_THROWN_WHILE_VISITING_NODE);

        if (toolExitStatusAndContinue.indContinue) {
          userInteractionCallbackPlugin.provideInfo(MessageFormat.format(ModelVisitorJobAbstractImpl.resourceBundle.getString(ModelVisitorJobAbstractImpl.MSG_PATTERN_KEY_EXCEPTION_THROWN_WHILE_VISITING_NODE), toolExitStatusAndContinue.toolExitStatus, nodePathBase, Util.getStackTrace(re)));
          continue;
        } else {
          throw new RuntimeExceptionAbort(MessageFormat.format(ModelVisitorJobAbstractImpl.resourceBundle.getString(ModelVisitorJobAbstractImpl.MSG_PATTERN_KEY_EXCEPTION_THROWN_WHILE_VISITING_NODE), toolExitStatusAndContinue.toolExitStatus, nodePathBase, Util.getStackTrace(re)));
        }
      }

      userInteractionCallbackPlugin.provideInfo(MessageFormat.format(ModelVisitorJobAbstractImpl.resourceBundle.getString(ModelVisitorJobAbstractImpl.MSG_PATTERN_KEY_TRAVERSAL_BASE_NODE_PATH_COMPLETED), nodePathBase));

      if (Util.isAbort()) {
        userInteractionCallbackPlugin.provideInfo(Util.getLocalizedMsgPattern(Util.MSG_PATTERN_KEY_JOB_ABORTED_BY_USER));
        break;
      }
    }

    ModelVisitorJobAbstractImpl.logger.info("Iteration among all base NodePath's " + this.listNodePathBase + " completed.");

    if (this.listActionsPerformed.size() != 0) {
      userInteractionCallbackPlugin.provideInfo(MessageFormat.format(ModelVisitorJobAbstractImpl.resourceBundle.getString(ModelVisitorJobAbstractImpl.MSG_PATTERN_KEY_ACTIONS_PERFORMED), StringUtils.join(this.listActionsPerformed, '\n')));
    } else {
      userInteractionCallbackPlugin.provideInfo(ModelVisitorJobAbstractImpl.resourceBundle.getString(ModelVisitorJobAbstractImpl.MSG_PATTERN_KEY_NO_ACTIONS_PERFORMED));
    }

    userInteractionCallbackPlugin.provideInfo(MessageFormat.format(Util.getLocalizedMsgPattern(Util.MSG_PATTERN_KEY_JOB_COMPLETED), this.getClass().getSimpleName()));
  }

  /**
   * Initiates the traversal of a base NodePath by invoking
   * {@link ClassificationNode#traverseNodeHierarchy} if the base NodePath refers to
   * a {@link ClassificationNode}.
   *
   * <p>The {@link NodeVisitor} used calls back {@link #visitClassificationNode} or
   * {@link #visitModule} for each {@link Node} for
   * {@link org.azyva.dragom.model.NodeVisitor.VisitAction#VISIT}. The other
   * {@link org.azyva.dragom.model.NodeVisitor.VisitAction} are not
   * handled.
   *
   * <p>If the base NodePath refers to a {@link Module}, visitModule is simply
   * called.
   *
   * @param nodePathBase Base NodePath.
   */
  protected void initiateVisitBaseNodePath(NodePath nodePathBase) {
    ExecContext execContext;
    UserInteractionCallbackPlugin userInteractionCallbackPlugin;
    Model model;

    execContext = ExecContextHolder.get();
    userInteractionCallbackPlugin = execContext.getExecContextPlugin(UserInteractionCallbackPlugin.class);
    model = execContext.getModel();

    if (nodePathBase.isPartial()) {
      ClassificationNode classificationNode;

      classificationNode = model.getClassificationNode(nodePathBase);

      if (classificationNode == null) {
        Util.ToolExitStatusAndContinue toolExitStatusAndContinue;

        toolExitStatusAndContinue = Util.handleToolExitStatusAndContinueForExceptionalCond(null, ModelVisitorJobAbstractImpl.EXCEPTIONAL_COND_NODE_NOT_FOUND);

        if (toolExitStatusAndContinue.indContinue) {
          userInteractionCallbackPlugin.provideInfo(MessageFormat.format(ModelVisitorJobAbstractImpl.resourceBundle.getString(ModelVisitorJobAbstractImpl.MSG_PATTERN_KEY_NODE_NOT_FOUND), toolExitStatusAndContinue.toolExitStatus, nodePathBase));
        } else {
          throw new RuntimeExceptionAbort(MessageFormat.format(ModelVisitorJobAbstractImpl.resourceBundle.getString(ModelVisitorJobAbstractImpl.MSG_PATTERN_KEY_NODE_NOT_FOUND), toolExitStatusAndContinue.toolExitStatus, nodePathBase));
        }
      } else {
        classificationNode.traverseNodeHierarchy(this.nodeTypeFilter, this.indDepthFirst, new Visitor());
      }
    } else {
      Module module;

      module = model.getModule(nodePathBase);

      if (module == null) {
        Util.ToolExitStatusAndContinue toolExitStatusAndContinue;

        toolExitStatusAndContinue = Util.handleToolExitStatusAndContinueForExceptionalCond(null, ModelVisitorJobAbstractImpl.EXCEPTIONAL_COND_NODE_NOT_FOUND);

        if (toolExitStatusAndContinue.indContinue) {
          userInteractionCallbackPlugin.provideInfo(MessageFormat.format(ModelVisitorJobAbstractImpl.resourceBundle.getString(ModelVisitorJobAbstractImpl.MSG_PATTERN_KEY_NODE_NOT_FOUND), toolExitStatusAndContinue.toolExitStatus, nodePathBase));
        } else {
          throw new RuntimeExceptionAbort(MessageFormat.format(ModelVisitorJobAbstractImpl.resourceBundle.getString(ModelVisitorJobAbstractImpl.MSG_PATTERN_KEY_NODE_NOT_FOUND), toolExitStatusAndContinue.toolExitStatus, nodePathBase));
        }
      } else {
        if ((this.nodeTypeFilter == null) || (this.nodeTypeFilter == NodeType.MODULE)) {
          if (this.visitModule(module) == NodeVisitor.VisitControl.ABORT) {
            Util.setAbort();
          }
        }
      }
    }
  }

  /**
   * Called by {@link #initiateVisitBaseNodePath} for ClassificationNode's during
   * the traversal.
   *
   * <p>Subclasses must override (if initiateVisitBaseNodePath is not overridden).
   *
   * <p>It is not made abstract since it does not need to be implemented if never
   * called.
   *
   * @param classificationNode ClassificationNode.
   * @return NodeVisitor.VisitControl.
   */
  protected NodeVisitor.VisitControl visitClassificationNode(ClassificationNode classificationNode) {
    throw new RuntimeException("Must not get here.");
  }

  /**
   * Called by {@link #initiateVisitBaseNodePath} for Module's during the
   * traversal.
   *
   * <p>Subclasses must override (if initiateVisitBaseNodePath is not overridden).
   *
   * <p>It is not made abstract since it does not need to be implemented if never
   * called.
   *
   * @param module Module.
   * @return NodeVisitor.VisitControl.
   */
  protected NodeVisitor.VisitControl visitModule(Module module) {
    throw new RuntimeException("Must not get here.");
  }

  /**
   * Called by {@link #performJob} after the iteration through the List of base
   * {@link NodePath}'s.
   */
  protected void afterIterateListNodePathBase() {
  }

}
