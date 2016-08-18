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
import org.azyva.dragom.model.config.MutableClassificationNodeConfig;
import org.azyva.dragom.model.config.MutableConfig;
import org.azyva.dragom.model.config.NodeType;
import org.azyva.dragom.model.plugin.ArtifactInfoPlugin;
import org.azyva.dragom.model.plugin.FindModuleByArtifactGroupIdPlugin;
import org.azyva.dragom.model.plugin.NodePlugin;

/**
 * Simple implementation of a {@link Model} that is based on {@link Config}.
 * <p>
 * In addition to Config, initialization Properties can be provided to an instance
 * of SimpleModel in order to override properties defined within Config. This
 * allows customizing a Model locally, such as specifying a different base URL for
 * an SCM repository.
 * <p>
 * Initialization Properties that have the "org.azyva.dragom.model-property." prefix are
 * considered first, with inheritance. Only if the property is not found among
 * initialization Properties will real {@link Model} properties be considered.
 * Note that properties defined in such a way cannot be defined only for a
 * specific node, contrary to real Model properties.
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
 */
public class SimpleModel implements Model, MutableModel, ModelNodeBuilderFactory {
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
	 * {@link Config} for the Model.
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

					break;
				}

				if (artifactInfoPlugin.isArtifactGroupIdPossiblyProduced(this.artifactGroupId)) {
					if (modulePossiblyFound == null) {
						modulePossiblyFound = module;
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
	 */
	public SimpleModel(Config config, Properties propertiesInit) {
		this.propertiesInit = propertiesInit;
		this.config = config;
		this.indMutable = (this.config instanceof MutableConfig);

		if (!this.indMutable || (config.getClassificationNodeConfigRoot() != null)) {
			this.simpleClassificationNodeRoot = new SimpleClassificationNode(config.getClassificationNodeConfigRoot(), this);
		}

		this.mapArtifactGroupIdModule = new HashMap<ArtifactGroupId, SimpleModule>();
		??? this map may need to be flushed when mutating the model (all of it?)
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
	public Node getClassificationNodeRoot() {
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

	/**
	 * Sets the root {@link SimpleClassificationNode}.
	 * <p>
	 * If one is already set, it is overwritten.
	 * <p>
	 * This method is intended to be called by
	 * {@link SimpleNode#setNodeConfigValue}.
	 *
	 * @param simpleClassificationNodeRoot Root SimpleClassificationNode.
	 */
	void setSimpleClassificationNodeRoot(SimpleClassificationNode simpleClassificationNodeRoot) {
		if (!this.indMutable) {
			throw new IllegalStateException("SimpleModel must be mutable.");
		}

		this.simpleClassificationNodeRoot = simpleClassificationNodeRoot;
		??? if replace, destroy recursive.
	}

	@Override
	public MutableClassificationNode createClassificationNodeConfigRoot() {
		if (!this.indMutable) {
			throw new IllegalStateException("SimpleModel must be mutable.");
		}

		return new SimpleClassificationNode(((MutableConfig)this.config).createClassificationNodeConfigRoot(), this);
	}

	@Override
	public ClassificationNodeBuilder createClassificationNodeBuilder() {
		if (!this.indMutable) {
			throw new IllegalStateException("SimpleModel must be mutable.");
		}

		return new SimpleClassificationNodeBuilder(this);
	}

	@Override
	public ModuleBuilder createModuleBuilder() {
		return new SimpleModuleBuilder(this);
	}
}
