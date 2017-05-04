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

package org.azyva.dragom.model.impl;

import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.ResourceBundle;
import java.util.regex.Pattern;

import org.azyva.dragom.execcontext.plugin.RuntimePropertiesPlugin;
import org.azyva.dragom.execcontext.plugin.UserInteractionCallbackPlugin;
import org.azyva.dragom.execcontext.support.ExecContextHolder;
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
import org.azyva.dragom.util.RuntimeExceptionAbort;
import org.azyva.dragom.util.Util;

/**
 * Default {@link Model} and {@link MutableModel} implementation based on
 * {@link Config} and optionally {@link MutableConfig}.
 * <p>
 * In addition to Config, initialization Properties can be provided to an instance
 * of DefaultModel in order to override properties defined within Config. This
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
 * @see org.azyva.dragom.model.impl
 */
public class DefaultModel implements Model, ModelNodeBuilderFactory, MutableModel {
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
   * Model property which specifies a regular expression matching the
   * ArtifactGroupId that are in the scope of the Model and thus have a
   * corresponding {@link Module}.
   *
   * <p>Inclusions are processed before exclusions defined by
   * {@link #MODEL_PROPERTY_EXCLUDE_ARTIFACT_GROUP_ID_REGEX}.
   *
   * <p>See {@link #EXCEPTIONAL_COND_MODULE_NOT_FOUND}.
   *
   * <p>If this property is not specified, all ArtifactGroupId's are considered as
   * matching.
   *
   * <p>This model property is always evaluated on the root
   * {@link ClassificationNode}.
   */
  private static final String MODEL_PROPERTY_INCLUDE_ARTIFACT_GROUP_ID_REGEX = "INCLUDE_ARTIFACT_GROUP_ID_REGEX";

  /**
   * Model property which specifies a regular expression matching the
   * ArtifactGroupId that are not in the scope of the Model and thus do not have a
   * corresponding {@link Module}.
   *
   * <p>Exclusions are processed after inclusions defined by
   * {@link #MODEL_PROPERTY_INCLUDE_ARTIFACT_GROUP_ID_REGEX}.
   *
   * <p>See {@link #EXCEPTIONAL_COND_MODULE_NOT_FOUND}.
   *
   * <p>If this property is not specified, no exclusion is performed.
   *
   * <p>This model property is always evaluated on the root ClassificationNode.
   */
  private static final String MODEL_PROPERTY_EXCLUDE_ARTIFACT_GROUP_ID_REGEX = "EXCLUDE_ARTIFACT_GROUP_ID_REGEX";

  /**
   * Exceptional condition representing a {@link Module} that cannot be found but
   * should, corresponding to an {@link ArtifactGroupId}. See
   * {@link #MODEL_PROPERTY_INCLUDE_ARTIFACT_GROUP_ID_REGEX} and
   * {@link #MODEL_PROPERTY_EXCLUDE_ARTIFACT_GROUP_ID_REGEX}.
   *
   * <p>If the configuration of the exceptional condition is such that processing
   * continues, the process recovers by treating the artifact as not being known to
   * Dragom by making {@link #findModuleByArtifactGroupId} return null.
   *
   * <p>The runtime properties related to this exceptional condition are evaluated
   * on the root Node (null).
   */
  private static final String EXCEPTIONAL_COND_MODULE_NOT_FOUND = "MODULE_NOT_FOUND";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_MODULE_NOT_FOUND = "MODULE_NOT_FOUND";

  /**
   * ResourceBundle specific to this class.
   */
  private static final ResourceBundle resourceBundle = ResourceBundle.getBundle(DefaultModel.class.getName() + "ResourceBundle");

  /**
   * Indicates if the DefaultModel is mutable, based on whether the {@link Config}
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
   * {@link DefaultClassificationNode} representing the root
   * {@link ClassificationNode}.
   */
  private DefaultClassificationNode defaultClassificationNodeRoot;

  /**
   * Map of known ArtifactoryGrouipId to Module. This is to avoid having to perform
   * a costly search every time the {@link #findModuleByArtifactGroupId} method is
   * called.
   */
  private Map<ArtifactGroupId, DefaultModule> mapArtifactGroupIdModule;

  /**
   * Pattern that an ArtifactGroupId literal must match to be considered in the
   * scope of the Model and thus have corresponding {@link Module}. See
   * {@link #MODEL_PROPERTY_INCLUDE_ARTIFACT_GROUP_ID_REGEX}.
   */
  private Pattern patternIncludeArtifactGroupId;

  /**
   * Pattern that an ArtifactGroupId literal already matched by
   * {@link #patternIncludeArtifactGroupId} must not match to still be considered in
   * the scope of the Model. See
   * {@link #MODEL_PROPERTY_EXCLUDE_ARTIFACT_GROUP_ID_REGEX}.
   */
  private Pattern patternExcludeArtifactGroupId;

  /**
   * {@link NodeVisitor} for finding the {@link Module} whose build produces a given
   * {@link ArtifactGroupId} by using {@link ArtifactInfoPlugin}.
   * <p>
   * The nodes visited by this NodeVisitor must be Module's.
   * {@link NodeType#MODULE} must be passed to
   * {@link ClassificationNode#traverseNodeHierarchy}.
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
    public VisitControl visitNode(VisitAction visitAction, Node node) {
      ArtifactInfoPlugin artifactInfoPlugin;

      if (visitAction != VisitAction.VISIT) {
        return VisitControl.CONTINUE;
      }

      if (!node.isNodePluginExists(ArtifactInfoPlugin.class, null)) {
        return VisitControl.CONTINUE;
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

      return VisitControl.CONTINUE;
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
   * {@link ClassificationNode#traverseNodeHierarchy}.
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
    public VisitControl visitNode(VisitAction visitAction, Node node) {
      FindModuleByArtifactGroupIdPlugin findModuleByArtifactGroupIdPlugin;
      List<NodePath> listNodePath;
      Module modulePossiblyFound = null;

      if (visitAction != VisitAction.VISIT) {
        return VisitControl.CONTINUE;
      }

      if (!node.isNodePluginExists(FindModuleByArtifactGroupIdPlugin.class, null)) {
        return VisitControl.CONTINUE;
      }

      findModuleByArtifactGroupIdPlugin = node.getNodePlugin(FindModuleByArtifactGroupIdPlugin.class, null);

      listNodePath = findModuleByArtifactGroupIdPlugin.getListModulePossiblyProduceArtifactGroupId(this.artifactGroupId);

      if (listNodePath == null) {
        return VisitControl.CONTINUE;
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

          if (Util.isNotNullAndTrue(module.getProperty(DefaultModel.MODEL_PROPERTY_OPTIMISTIC_ARTIFACT_GROUP_ID_PRODUCED_MAPPING))) {
            break;
          }
        }

        if (artifactInfoPlugin.isArtifactGroupIdPossiblyProduced(this.artifactGroupId)) {
          if (modulePossiblyFound == null) {
            modulePossiblyFound = module;

            if (Util.isNotNullAndTrue(module.getProperty(DefaultModel.MODEL_PROPERTY_OPTIMISTIC_ARTIFACT_GROUP_ID_PRODUCED_MAPPING))) {
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

      return VisitControl.CONTINUE;
    }
  }

  /**
   * Constructor.
   * <p>
   * This is the only way to instantiate a new {@link DefaultModel}. However, once
   * instantiated, a DefaultModel can be completed dynamically using
   * {@link ModelNodeBuilderFactory} methods to create new {@link DefaultNode}'s.
   *
   * @param config Config.
   * @param propertiesInit Initialization properties.
   */
  public DefaultModel(Config config, Properties propertiesInit) {
    String modelProperty;

    this.propertiesInit = propertiesInit;
    this.config = config;
    this.indMutable = (this.config instanceof MutableConfig);

    if (this.indMutable) {
      if (config.getClassificationNodeConfigRoot() != null) {
        this.defaultClassificationNodeRoot = new DefaultClassificationNode(config.getClassificationNodeConfigRoot(), this);
      }
    } else {
      if (config.getClassificationNodeConfigRoot() == null) {
        throw new RuntimeException("Root ClassificationNodeConfig must be specified.");
      }

      this.defaultClassificationNodeRoot = new DefaultClassificationNode(config.getClassificationNodeConfigRoot(), this);
    }

    this.mapArtifactGroupIdModule = new HashMap<ArtifactGroupId, DefaultModule>();

    modelProperty = this.defaultClassificationNodeRoot.getProperty(DefaultModel.MODEL_PROPERTY_INCLUDE_ARTIFACT_GROUP_ID_REGEX);

    if (modelProperty != null) {
      this.patternIncludeArtifactGroupId = Pattern.compile(modelProperty);
    }

    modelProperty = this.defaultClassificationNodeRoot.getProperty(DefaultModel.MODEL_PROPERTY_EXCLUDE_ARTIFACT_GROUP_ID_REGEX);

    if (modelProperty != null) {
      this.patternExcludeArtifactGroupId = Pattern.compile(modelProperty);
    }
  }

  /**
   * Returns the initialization properties that were used when creating this
   * DefaultModel.
   * <p>
   * This method is not part of {@link Model} and is therefore intended to be used
   * by the other classes that make up the DefaultModel, namely {@link DefaultNode}.
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
    return this.defaultClassificationNodeRoot;
  }

  /**
   * At each step while following the path of {DefaultClassificationNode},
   * {@link DefaultClassificationNode#getDefaultClassificationNodeChildDynamic} is
   * used so that new DefaultClassificationNode can be dynamically created if
   * required.
   *
   * @param nodePath NodePath of the ClassificationNode to return. Must be partial.
   * @return ClassificationNode. null if no ClassificationNode corresponding to the
   *   specified NodePath exist.
   * @throws IllegalArgumentException If the {@link Node} identified by nodePath is
   *   not a ClassificationNode.
   */
  @Override
  public ClassificationNode getClassificationNode(NodePath nodePath) throws IllegalArgumentException {
    DefaultClassificationNode defaultClassificationNodeCurrent;

    if (!nodePath.isPartial()) {
      throw new RuntimeException("Cannot get a classification node given a complete non-partial NodsePath " + nodePath + '.');
    }

    defaultClassificationNodeCurrent = this.defaultClassificationNodeRoot;

    for (int i = 0; i < nodePath.getNodeCount(); i++) {
      defaultClassificationNodeCurrent = defaultClassificationNodeCurrent.getDefaultClassificationNodeChildDynamic(nodePath.getNodeName(i));

      if (defaultClassificationNodeCurrent == null) {
        return null;
      }
    }

    return defaultClassificationNodeCurrent;
  }

  /**
   * This method uses {@link #getClassificationNode} to get the parent
   * {@link DefaultClassificationNode}. It then uses
   * {@link DefaultClassificationNode#getDefaultModuleChildDynamic} to get the
   * requested DefaultModule which therefore can be dynamically created if required.
   *
   * @param nodePath NodePath of the Module to return. Must not be partial.
   * @return Module. null if no Module corresponding to the specified NodePath
   *   exist.
   * @throws IllegalArgumentException If the {@link Node} identified by nodePath is
   *   not a Module.
   */
  @Override
  public Module getModule(NodePath nodePath) throws IllegalArgumentException {
    DefaultClassificationNode classificationNode;

    if (nodePath.isPartial()) {
      throw new RuntimeException("Cannot get a module given a partial NodsePath " + nodePath + '.');
    }

    classificationNode = (DefaultClassificationNode)this.getClassificationNode(nodePath.getNodePathParent());

    if (classificationNode == null) {
      return null;
    }

    return classificationNode.getDefaultModuleChildDynamic(nodePath.getModuleName());
  }

  /**
   * First, {@link FindModuleByArtifactGroupIdModuleNodeVisitor} is used to find a
   * Module among those that are already created.
   *
   * <p>If an existing Module could not be found,
   * {@link FindModuleThroughClassificationNodeByArtifactGroupIdClassificationNodeVisitor}
   * is used to ask the existing {@link ClassificationNode}'s about such a Module.
   *
   * <p>In all cases the mapping between the ArtifactGroupId and the found Module is
   * cached and used to speed up subsequent requests for the same ArtifactGroupId.
   *
   * <p>It is possible that no Module corresponding to the ArtifactGroupId is found.
   *
   * <p>This can happen if the ArtifactGroupId is not within the scope of the Model,
   * meaning that the ArtifactGroupId should be treated as being external to Dragom.
   * This is not considered unexpected and null is returned in that case.
   *
   * <p>This can also happen unexpectedly if no Module can be mapped to the
   * ArtifactGroupId, indicating a bad or incomplete configuration. This corresponds
   * to {@link #EXCEPTIONAL_COND_MODULE_NOT_FOUND}.
   *
   * {@link #isArtifactGroupIdIncluded} is used to know if an ArtifactGroupId is
   * within the scope of the Model.
   *
   * @param artifactGroupId ArtifactGroupId for which to find a {@link Module}.
   * @return Module. null if no Module found.
   */
  @Override
  public Module findModuleByArtifactGroupId(ArtifactGroupId artifactGroupId) {
    FindModuleByArtifactGroupIdModuleNodeVisitor findModuleByArtifactGroupIdModuleNodeVisitor;
    DefaultModule moduleFound;
    FindModuleThroughClassificationNodeByArtifactGroupIdClassificationNodeVisitor findModuleThroughClassificationNodeByArtifactGroupIdClassificationNodeVisitor;
    Util.ToolExitStatusAndContinue toolExitStatusAndContinue;

    // First check if the Module whose build produces the specified ArtifactGroupId
    // would not already be known.
    //
    // The Map may contain null to indicate that the ArtifactGroupId has already been
    // requested and is known to not have any corresponding Module. This means we
    // cannot just check for null returned by Map.get.

    if (this.mapArtifactGroupIdModule.containsKey(artifactGroupId)) {
      return this.mapArtifactGroupIdModule.get(artifactGroupId);
    }

    if (!this.isArtifactGroupIdIncluded(artifactGroupId)) {
      this.mapArtifactGroupIdModule.put(artifactGroupId, null);
      return null;
    }

    // If not, traverse the hierarchy of known Module's in an attempt to find one
    // whose build produces the specified ArtifactGroupId. See the description of
    // FindModuleByArtifactGroupIdModuleNodeVisitor for more information.

    findModuleByArtifactGroupIdModuleNodeVisitor = new FindModuleByArtifactGroupIdModuleNodeVisitor(artifactGroupId);

    this.defaultClassificationNodeRoot.traverseNodeHierarchy(NodeType.MODULE, true, findModuleByArtifactGroupIdModuleNodeVisitor);

    moduleFound = (DefaultModule)findModuleByArtifactGroupIdModuleNodeVisitor.getModuleFound();

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

    this.defaultClassificationNodeRoot.traverseNodeHierarchy(NodeType.CLASSIFICATION, true, findModuleThroughClassificationNodeByArtifactGroupIdClassificationNodeVisitor);

    moduleFound = (DefaultModule)findModuleThroughClassificationNodeByArtifactGroupIdClassificationNodeVisitor.getModuleFound();

    if (moduleFound != null) {
      this.mapArtifactGroupIdModule.put(artifactGroupId, moduleFound);
      return moduleFound;
    }

    toolExitStatusAndContinue = Util.handleToolExitStatusAndContinueForExceptionalCond(null, DefaultModel.EXCEPTIONAL_COND_MODULE_NOT_FOUND);

    if (toolExitStatusAndContinue.indContinue) {
      ExecContextHolder.get().getExecContextPlugin(UserInteractionCallbackPlugin.class).provideInfo(MessageFormat.format(DefaultModel.resourceBundle.getString(DefaultModel.MSG_PATTERN_KEY_MODULE_NOT_FOUND), toolExitStatusAndContinue.toolExitStatus, artifactGroupId));

      this.mapArtifactGroupIdModule.put(artifactGroupId, null);
      return null;
    } else {
      throw new RuntimeExceptionAbort(MessageFormat.format(DefaultModel.resourceBundle.getString(DefaultModel.MSG_PATTERN_KEY_MODULE_NOT_FOUND), toolExitStatusAndContinue.toolExitStatus, artifactGroupId));
    }
  }

  /**
   * Used by {@link #findModuleByArtifactGroupId(ArtifactGroupId)} to verify if an
   * {@link ArtifactGroupId} is within the scope of this Model, meaning that a
   * corresponding {@link Module} should be found.
   *
   * <p>To be included, either
   * {@link #MODEL_PROPERTY_INCLUDE_ARTIFACT_GROUP_ID_REGEX} is not defined or it
   * matches the ArtifactGroupId in literal form, and either
   * {@link #MODEL_PROPERTY_EXCLUDE_ARTIFACT_GROUP_ID_REGEX} is not defined or it
   * does not match the ArtifactGroupId in literal form.
   *
   * @param artifactGroupId artifactGroupId.
   * @return See description.
   */
  @Override
  public boolean isArtifactGroupIdIncluded(ArtifactGroupId artifactGroupId) {
    if (this.patternIncludeArtifactGroupId != null) {
      if (!this.patternIncludeArtifactGroupId.matcher(artifactGroupId.toString()).matches()) {
        return false;
      }
    }

    if (this.patternExcludeArtifactGroupId != null) {
      if (this.patternExcludeArtifactGroupId.matcher(artifactGroupId.toString()).matches()) {
        return false;
      }
    }

    return true;
  }

  @Override
  public ClassificationNodeBuilder createClassificationNodeBuilder() {
    return new DefaultClassificationNodeBuilder(this);
  }

  @Override
  public ModuleBuilder createModuleBuilder() {
    return new DefaultModuleBuilder(this);
  }

  /**
   * Sets the root {@link DefaultClassificationNode}.
   * <p>
   * This method is intended to be called by
   * {@link DefaultNode#setNodeConfigTransferObject}.
   *
   * @param defaultClassificationNodeRoot Root DefaultClassificationNode.
   */
  void setDefaultClassificationNodeRoot(DefaultClassificationNode defaultClassificationNodeRoot) {
    if (!this.indMutable) {
      throw new IllegalStateException("DefaultModel must be mutable.");
    }

    if (this.defaultClassificationNodeRoot != null && defaultClassificationNodeRoot != null) {
      throw new RuntimeException("Replacing the root DefaultClassificationNode is not allowed.");
    }

    // Setting this.simplClassificationNodeRoot to null is allowed since this
    // can happen when deleting the root DefaultClassificationNode.
    this.defaultClassificationNodeRoot = defaultClassificationNodeRoot;
  }

  @Override
  public MutableClassificationNode createMutableClassificationNodeRoot() {
    if (!this.indMutable) {
      throw new IllegalStateException("DefaultModel must be mutable.");
    }

    return new DefaultClassificationNode(((MutableConfig)this.config).createMutableClassificationNodeConfigRoot(), this);
  }

  /**
   * Called by {@link DefaultNode#cleanCaches} for a {@link DefaultNode} being
   * cleaned so that the DefaultModel can clean any cached reference to the
   * DefaultNode.
   *
   * @param defaultNode DefaultModel.
   */
  void cleanCaches(DefaultNode defaultNode) {
    if (defaultNode.getNodeType() == NodeType.MODULE) {
      Iterator<Map.Entry<ArtifactGroupId, DefaultModule>> iteratorArtifactGroupIdModule;

      /*
       * We need to remove any entry in this.mapArtifactGroupIdModule which refers to
       * the DefaultNode.
       */

      iteratorArtifactGroupIdModule = this.mapArtifactGroupIdModule.entrySet().iterator();

      while (iteratorArtifactGroupIdModule.hasNext()) {
        Map.Entry<ArtifactGroupId, DefaultModule> mapEntry;

        mapEntry = iteratorArtifactGroupIdModule.next();

        // We use identity comparison since only one copy of a given DefaultNode is kept.
        if (mapEntry.getValue() == defaultNode) {
          iteratorArtifactGroupIdModule.remove();
        }
      }
    }
  }

  @Override
  public void flush() {
    if (this.indMutable) {
      ((MutableConfig)this.config).flush();
    }
  }
}
