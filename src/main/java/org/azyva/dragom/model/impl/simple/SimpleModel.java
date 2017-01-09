/*
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

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.azyva.dragom.execcontext.plugin.RuntimePropertiesPlugin;
import org.azyva.dragom.model.ArtifactGroupId;
import org.azyva.dragom.model.ClassificationNode;
import org.azyva.dragom.model.ClassificationNodeBuilder;
import org.azyva.dragom.model.Model;
import org.azyva.dragom.model.ModelNodeBuilderFactory;
import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.ModuleBuilder;
import org.azyva.dragom.model.MutableClassificationNode;
import org.azyva.dragom.model.MutableModel;
import org.azyva.dragom.model.Node;
import org.azyva.dragom.model.NodePath;
import org.azyva.dragom.model.NodeVisitor;
import org.azyva.dragom.model.config.Config;
import org.azyva.dragom.model.config.MutableConfig;
import org.azyva.dragom.model.config.NodeType;
import org.azyva.dragom.model.plugin.ArtifactInfoPlugin;
import org.azyva.dragom.model.plugin.FindModuleByArtifactGroupIdPlugin;
import org.azyva.dragom.model.plugin.NodePlugin;
import org.azyva.dragom.util.Util;

/**
 * Simple implementation of a {@link Model} and {@link MutableModel} based on
 * {@link Config} and optionnally {@link MutableConfig}.
 * <p>
 * In addition to Config, initialization Properties can be provided to an instance
 * of SimpleModel in order to override properties defined within Config. This
 * allows customizing a Model locally, such as specifying a different base URL for
 * an SCM repository.
 * <p>
 * Initialization Properties are considered first, with inheritance. Only if the
 * property is not found among initialization Properties will real {@link Model}
 * properties be considered. Note that properties defined in such a way cannot be
 * defined only for a specific node, contrary to real Model properties.
 * <p>
 * The fact that initialization Properties can be specified does not violate the
 * general principle that a Model is static since initialization Properties are
 * not meant to change between tool invocations.
 * <p>
 * Note that {@link NodePlugin}'s provided by Model can use runtime properties
 * provided by {@link RuntimePropertiesPlugin} since the behavior of NodePlugin's
 * can be tool-invocation-specific.
 *
 * @author David Raymond
 * @see org.azyva.dragom.model.impl.simple
 */
public class SimpleModel implements Model, ModelNodeBuilderFactory, MutableModel {
  /**
   * Model property which specifies if the determination of whether a {@link Module}
   * produces an {@link ArtifactGroupId} is optimistic.
   *
   * <p>For example if we try to find the Module which produced ArtifactGroupId
   * com.acme:my-module-sub, and
   * {@link FindModuleByArtifactGroupIdPlugin#getListModulePossiblyProduceArtifactGroupId}
   * returns my-module-sub, my-module and my as possible artifactId, if my-module
   * does correspond to an existing Module, the search will stop there and the
   * Module corresponding to artifactId my will not be tried.
   */
  private static final String MODEL_PROPERTY_OPTIMISTIC_ARTIFACT_GROUP_ID_PRODUCED_MAPPING = "OPTIMISTIC_ARTIFACT_GROUP_ID_PRODUCED_MAPPING";

  /**
   * Indicates if the SimpleModel is mutable, based on whether the {@link Config}
   * provided is mutable.
   */
  private boolean indMutable;

  /**
   * Initialization Properties that can override properties specified within the
   * {@link Model} {@link Config}.
   */
  private Properties propertiesInit;

  /**
   * {@link Config} for the Model. May also implement {@link MutableConfig}.
   */
  private Config config;

  /**
   * {@link SimpleClassificationNode} representing the root
   * {@link ClassificationNode}.
   */
  private SimpleClassificationNode simpleClassificationNodeRoot;

  /**
   * Map of known ArtifactoryGrouipId to Module. This is to avoid having to perform
   * a costly search every time the {@link #findModuleByArtifactGroupId} method is
   * called.
   */
  private Map<ArtifactGroupId, SimpleModule> mapArtifactGroupIdModule;

  /**
   * {@link NodeVisitor} for finding the {@link Module} whose build produces a given
   * {@link ArtifactGroupId} by using {@link ArtifactInfoPlugin}.
   * <p>
   * The nodes visited by this NodeVisitor must be Module's.
   * {@link NodeType#MODULE} must be passed to
   * {@link ClassificationNode#traverseNodeHierarchyDepthFirst}.
   * <p>
   * Used by {@link Model#findModuleByArtifactGroupId}.
   *
   * @author David Raymond
   */
  private static class FindModuleByArtifactGroupIdModuleNodeVisitor implements NodeVisitor {
    private ArtifactGroupId artifactGroupId;
    private Module moduleFound;

    /**
     * Constructor.
     *
     * @param artifactGroupId ArtifactGroupId for which to find a {@link Module}.
     */
    public FindModuleByArtifactGroupIdModuleNodeVisitor(ArtifactGroupId artifactGroupId) {
      this.artifactGroupId = artifactGroupId;
    }

    /**
     * @return Module found. null if no Module was found.
     */
    public Module getModuleFound() {
      return this.moduleFound;
    }

    @Override
    public boolean visitNode(Node node) {
      ArtifactInfoPlugin artifactInfoPlugin;

      if (!node.isNodePluginExists(ArtifactInfoPlugin.class, null)) {
        return false;
      }

      artifactInfoPlugin = node.getNodePlugin(ArtifactInfoPlugin.class, null);

      if (artifactInfoPlugin.isArtifactGroupIdProduced(this.artifactGroupId)) {
        // Once we find a Module we could abort the traversal by returning true, but we
        // don't so that we can detect the abnormal situation where more than one Module
        // would match.

        if (this.moduleFound != null) {
          throw new RuntimeException("More than one module produces the same ArtifactGroupId " + this.artifactGroupId + ". The first one encountered was " + this.moduleFound + ". The one just encountered is " + node + '.');
        }

        this.moduleFound = (Module)node;
      }

      return false;
    }
  }

  /**
   * {@link NodeVisitor} for finding a module whose build produces a given
   * {@link ArtifactGroupId} by going through each {@link ClassificationNode} and
   * asking if if knows of a child {@link Module} whose build would possibly
   * produces the specified ArtifactGroupId by using
   * {@link FindModuleByArtifactGroupIdPlugin}.
   * <p>
   * For each such child Module, verify if the build of the Module definitively
   * produces the specified ArtifactGroupId by using
   * {@link ArtifactInfoPlugin}. If no child Module does produce the specified
   * ArtifactGroupId, take the first one whose build may produce the specified
   * ArtifactGroupId.
   * <p>
   * The nodes visited by this NodeVisitor must be ClassificationNode.
   * {@link NodeType#CLASSIFICATION} must be passed to
   * {@link ClassificationNode#traverseNodeHierarchyDepthFirst}.
   * <p>
   * Used by {@link Model#findModuleByArtifactGroupId}.
   *
   * @author David Raymond
   */
  private static class FindModuleThroughClassificationNodeByArtifactGroupIdClassificationNodeVisitor implements NodeVisitor {
    private ArtifactGroupId artifactGroupId;
    private Module moduleFound;

    /**
     * Constructor.
     *
     * @param artifactGroupId ArtifactGroupId for which to find a {@link Module}.
     */
    public FindModuleThroughClassificationNodeByArtifactGroupIdClassificationNodeVisitor(ArtifactGroupId artifactGroupId) {
      this.artifactGroupId = artifactGroupId;
    }

    /**
     * @return Module found. null if no Module was found.
     */
    public Module getModuleFound() {
      return this.moduleFound;
    }

    @Override
    public boolean visitNode(Node node) {
      FindModuleByArtifactGroupIdPlugin findModuleByArtifactGroupIdPlugin;
      List<NodePath> listNodePath;
      Module modulePossiblyFound = null;

      if (!node.isNodePluginExists(FindModuleByArtifactGroupIdPlugin.class, null)) {
        return false;
      }

      findModuleByArtifactGroupIdPlugin = node.getNodePlugin(FindModuleByArtifactGroupIdPlugin.class, null);

      listNodePath = findModuleByArtifactGroupIdPlugin.getListModulePossiblyProduceArtifactGroupId(this.artifactGroupId);

      if (listNodePath == null) {
        return false;
      }

      for (NodePath nodePath: listNodePath) {
        Module module;
        ArtifactInfoPlugin artifactInfoPlugin;

        module = node.getModel().getModule(nodePath);

        if (module == null) {
          continue;
        }

        artifactInfoPlugin = module.getNodePlugin(ArtifactInfoPlugin.class, null);

        if (artifactInfoPlugin == null) {
          continue;
        }

        if (artifactInfoPlugin.isArtifactGroupIdProduced(this.artifactGroupId)) {
          // Once we find a Module we could abort the traversal by returning true, but we
          // don't so that we can detect the abnormal situation where more than one Module
          // would match.

          if (this.moduleFound != null) {
            throw new RuntimeException("More than one module produces the same ArtifactGroupId " + this.artifactGroupId + ". The first one encountered was " + this.moduleFound + ". The one just encountered is " + module + '.');
          }

          this.moduleFound = module;

          if (Util.isNotNullAndTrue(module.getProperty(SimpleModel.MODEL_PROPERTY_OPTIMISTIC_ARTIFACT_GROUP_ID_PRODUCED_MAPPING))) {
            break;
          }
        }

        if (artifactInfoPlugin.isArtifactGroupIdPossiblyProduced(this.artifactGroupId)) {
          if (modulePossiblyFound == null) {
            modulePossiblyFound = module;

            if (Util.isNotNullAndTrue(module.getProperty(SimpleModel.MODEL_PROPERTY_OPTIMISTIC_ARTIFACT_GROUP_ID_PRODUCED_MAPPING))) {
              break;
            }
          }
        }
      }

      if (modulePossiblyFound != null) {
        // Among the list of Module's returned by a ClassificationNode whose build may
        // possibly produce the given ArtifactGroupId, the first one is considered a
        // match and is treated as if it definitively produced that ArtifactGroupId. This
        // means that there cannot be multiple ClassificationNode's in the hierarchy that
        // return the same Module whose build can possibly produce the same
        // ArtifactGroupId.
        //
        // Once we find a Module we could abort the traversal by returning true, but we
        // don't so that we can detect the abnormal situation where more than one Module
        // would match.
        //
        // However, since an ArtifactGroupId that is produced is also considered possibly
        // produced, moduleFound can be equal to modulePossiblyFound.

        if ((this.moduleFound != null) && (this.moduleFound != modulePossiblyFound)) {
          throw new RuntimeException("More than one module produces the same ArtifactGroupId " + this.artifactGroupId + ". The first one encountered was " + this.moduleFound + ". The one just encountered is " + modulePossiblyFound + '.');
        }

        this.moduleFound = modulePossiblyFound;
      }

      return false;
    }
  }

  /**
   * Constructor.
   * <p>
   * This is the only way to instantiate a new {@link SimpleModel}. However, once
   * instantiated, a SimpleModel can be completed dynamically using
   * {@link ModelNodeBuilderFactory} methods to create new {@link SimpleNode}'s.
   *
   * @param config Config.
   * @param propertiesInit Initialization properties.
   */
  public SimpleModel(Config config, Properties propertiesInit) {
    this.propertiesInit = propertiesInit;
    this.config = config;
    this.indMutable = (this.config instanceof MutableConfig);

    if (this.indMutable) {
      if (config.getClassificationNodeConfigRoot() != null) {
        this.simpleClassificationNodeRoot = new SimpleClassificationNode(config.getClassificationNodeConfigRoot(), this);
      }
    } else {
      if (config.getClassificationNodeConfigRoot() == null) {
        throw new RuntimeException("Root ClassificationNodeConfig must be specified.");
      }

      this.simpleClassificationNodeRoot = new SimpleClassificationNode(config.getClassificationNodeConfigRoot(), this);
    }


    this.mapArtifactGroupIdModule = new HashMap<ArtifactGroupId, SimpleModule>();
  }

  /**
   * Returns the initialization properties that were used when creating this
   * SimpleModel.
   * <p>
   * This method is not part of {@link Model} and is therefore intended to be used
   * by the other classes that make up the SimpleModel, namely {@link SimpleNode}.
   * <p>
   * Initialization properties should be considered read-only.
   *
   * @return Initialization Properties.
   */
  public Properties getInitProperties() {
    return this.propertiesInit;
  }

  /**
   * @return Root ClassificationNode.
   */
  @Override
  public ClassificationNode getClassificationNodeRoot() {
    return this.simpleClassificationNodeRoot;
  }

  /**
   * At each step while following the path of {SimpleClassificationNode},
   * {@link SimpleClassificationNode#getSimpleClassificationNodeChildDynamic} is
   * used so that new SimpleClassificationNode can be dynamically created if
   * required.
   *
   * @param nodePath NodePath of the ClassificationNode to return. Must be partial.
   * @return ClassificationNode. null if no ClassificationNode corresponding to the
   *   specified NodePath exist.
   */
  @Override
  public ClassificationNode getClassificationNode(NodePath nodePath) {
    SimpleClassificationNode simpleClassificationNodeCurrent;

    if (!nodePath.isPartial()) {
      throw new RuntimeException("Cannot get a classification node given a complete non-partial NodsePath " + nodePath + '.');
    }

    simpleClassificationNodeCurrent = this.simpleClassificationNodeRoot;

    for (int i = 0; i < nodePath.getNodeCount(); i++) {
      simpleClassificationNodeCurrent = simpleClassificationNodeCurrent.getSimpleClassificationNodeChildDynamic(nodePath.getNodeName(i));

      if (simpleClassificationNodeCurrent == null) {
        return null;
      }
    }

    return simpleClassificationNodeCurrent;
  }

  /**
   * This method uses {@link #getClassificationNode} to get the parent
   * {@link SimpleClassificationNode}. It then uses
   * {@link SimpleClassificationNode#getSimpleModuleChildDynamic} to get the
   * requested SimpleModule which therefore can be dynamically created if required.
   *
   * @param nodePath NodePath of the Module to return. Must not be partial.
   * @return Module. null if no Module corresponding to the specified NodePath
   *   exist.
   */
  @Override
  public Module getModule(NodePath nodePath) {
    SimpleClassificationNode classificationNode;

    if (nodePath.isPartial()) {
      throw new RuntimeException("Cannot get a module given a partial NodsePath " + nodePath + '.');
    }

    classificationNode = (SimpleClassificationNode)this.getClassificationNode(nodePath.getNodePathParent());

    if (classificationNode == null) {
      return null;
    }

    return classificationNode.getSimpleModuleChildDynamic(nodePath.getModuleName());
  }

  /**
   * Finds and returns the {@link Module} whose build produces an
   * {@link ArtifactGroupId}.
   * <p>
   * First, {@link FindModuleByArtifactGroupIdModuleNodeVisitor} is used to find a
   * Module among those that are already created.
   * <p>
   * If an existing Module could not be found,
   * {@link FindModuleThroughClassificationNodeByArtifactGroupIdClassificationNodeVisitor}
   * is used to ask the exising {@link ClassificationNode}'s about such a Module.
   * <p>
   * In all cases the mapping between the ArtifactGroupId and the found Module is
   * cached and used to speed up subsequent requests for the same ArtifactGroupId.
   *
   * @param artifactGroupId ArtifactGroupId for which to find a {@link Module}.
   * @return Module. null if no Module found.
   */
  @Override
  public Module findModuleByArtifactGroupId(ArtifactGroupId artifactGroupId) {
    FindModuleByArtifactGroupIdModuleNodeVisitor findModuleByArtifactGroupIdModuleNodeVisitor;
    SimpleModule moduleFound;
    FindModuleThroughClassificationNodeByArtifactGroupIdClassificationNodeVisitor findModuleThroughClassificationNodeByArtifactGroupIdClassificationNodeVisitor;

    // First check if the Module whose build produces the specified ArtifactGroupId
    // would not already be known.
    //
    // The Map may contain null to indicate that the ArtifactGroupId has already been
    // requested and is known to not have any corresponding Module. This means we
    // cannot just check for null returned by Map.get.

    if (this.mapArtifactGroupIdModule.containsKey(artifactGroupId)) {
      return this.mapArtifactGroupIdModule.get(artifactGroupId);
    }

    // If not, traverse the hierarchy of known Module's in an attempt to find one
    // whose build produces the specified ArtifactGroupId. See the description of
    // FindModuleByArtifactGroupIdModuleNodeVisitor for more information.

    findModuleByArtifactGroupIdModuleNodeVisitor = new FindModuleByArtifactGroupIdModuleNodeVisitor(artifactGroupId);

    this.simpleClassificationNodeRoot.traverseNodeHierarchyDepthFirst(NodeType.MODULE, findModuleByArtifactGroupIdModuleNodeVisitor);

    moduleFound = (SimpleModule)findModuleByArtifactGroupIdModuleNodeVisitor.getModuleFound();

    if (moduleFound != null) {
      this.mapArtifactGroupIdModule.put(artifactGroupId, moduleFound);
      return moduleFound;
    }

    // If no such Module is found, traverse the hierarchy of known
    // ClassificationNode's in an attempt to find one who knows about a child Module
    // whose build produces the specified ArtifactGroupId. See the description of
    // FindModuleThroughClassificationNodeByArtifactGroupIdClassificationNodeVisitor
    // for more information.

    findModuleThroughClassificationNodeByArtifactGroupIdClassificationNodeVisitor = new FindModuleThroughClassificationNodeByArtifactGroupIdClassificationNodeVisitor(artifactGroupId);

    this.simpleClassificationNodeRoot.traverseNodeHierarchyDepthFirst(NodeType.CLASSIFICATION, findModuleThroughClassificationNodeByArtifactGroupIdClassificationNodeVisitor);

    moduleFound = (SimpleModule)findModuleThroughClassificationNodeByArtifactGroupIdClassificationNodeVisitor.getModuleFound();

    if (moduleFound != null) {
      this.mapArtifactGroupIdModule.put(artifactGroupId, moduleFound);
      return moduleFound;
    }

    this.mapArtifactGroupIdModule.put(artifactGroupId, null);

    return null;
  }

  @Override
  public ClassificationNodeBuilder createClassificationNodeBuilder() {
    return new SimpleClassificationNodeBuilder(this);
  }

  @Override
  public ModuleBuilder createModuleBuilder() {
    return new SimpleModuleBuilder(this);
  }

  /**
   * Sets the root {@link SimpleClassificationNode}.
   * <p>
   * This method is intended to be called by
   * {@link SimpleNode#setNodeConfigTransferObject}.
   *
   * @param simpleClassificationNodeRoot Root SimpleClassificationNode.
   */
  void setSimpleClassificationNodeRoot(SimpleClassificationNode simpleClassificationNodeRoot) {
    if (!this.indMutable) {
      throw new IllegalStateException("SimpleModel must be mutable.");
    }

    if (this.simpleClassificationNodeRoot != null && simpleClassificationNodeRoot != null) {
      throw new RuntimeException("Replacing the root SimpleClassificationNode is not allowed.");
    }

    // Setting this.simplClassificationNodeRoot to null is allowed since this
    // can happen when deleting the root SimpleClassificationNode.
    this.simpleClassificationNodeRoot = simpleClassificationNodeRoot;
  }

  @Override
  public MutableClassificationNode createMutableClassificationNodeRoot() {
    if (!this.indMutable) {
      throw new IllegalStateException("SimpleModel must be mutable.");
    }

    return new SimpleClassificationNode(((MutableConfig)this.config).createMutableClassificationNodeConfigRoot(), this);
  }

  /**
   * Called by {@link SimpleNode#cleanCaches} for a {@link SimpleNode} being
   * cleaned so that the SimpleModel can clean any cached reference to the
   * SimpleNode.
   *
   * @param simpleNode SimpleModel.
   */
  void cleanCaches(SimpleNode simpleNode) {
    if (simpleNode.getNodeType() == NodeType.MODULE) {
      Iterator<Map.Entry<ArtifactGroupId, SimpleModule>> iteratorArtifactGroupIdModule;

      /*
       * We need to remove any entry in this.mapArtifactGroupIdModule which refers to
       * the SimpleNode.
       */

      iteratorArtifactGroupIdModule = this.mapArtifactGroupIdModule.entrySet().iterator();

      while (iteratorArtifactGroupIdModule.hasNext()) {
        Map.Entry<ArtifactGroupId, SimpleModule> mapEntry;

        mapEntry = iteratorArtifactGroupIdModule.next();

        // We use identity comparison since only one copy of a given SimpleNode is kept.
        if (mapEntry.getValue() == simpleNode) {
          iteratorArtifactGroupIdModule.remove();
        }
      }
    }
  }
}
