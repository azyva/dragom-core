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

package org.azyva.dragom.reference.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.azyva.dragom.model.Model;
import org.azyva.dragom.model.ModuleVersion;
import org.azyva.dragom.reference.Reference;
import org.azyva.dragom.reference.ReferenceGraph;
import org.azyva.dragom.reference.ReferencePath;
import org.azyva.dragom.util.ModuleReentryAvoider;

/**
 * Simple {@link ReferenceGraph} implementation.
 *
 * @author David Raymond
 */
public class SimpleReferenceGraph implements ReferenceGraph {
	/**
	 * Nodes in the ReferenceGraph.
	 * <p>
	 * Not to be confused with {@link org.azyva.dragom.model.Node} or
	 * {@link org.azyva.dragom.model.config.NodeConfig} which relate to
	 * {@link Model} Node's as opposed to nodes within a ReferenceGraph.
	 *
	 * @author David Raymond
	 */
	private static class ReferenceGraphNode {
		/**
		 * ModuleVersion.
		 */
		public ModuleVersion moduleVersion;

		/**
		 * List of {@link ReferenceGraph.Referrer}'s.
		 */
		public List<ReferenceGraph.Referrer> listReferrer;
		public List<Reference> listReference;

		/**
		 * Constructor.
		 *
		 * @param moduleVersion ModuleVersion.
		 */
		public ReferenceGraphNode(ModuleVersion moduleVersion) {
			this.moduleVersion = moduleVersion;
		}
	}

	/*
	 * Map of the ReferenceGraphNode's in the ReferenceGraph.
	 * <p>
	 * The Map key are {@link ModuleVersion}'s. A given ModuleVersion can only occur
	 * once within a ReferenceGraph.
	 */
	private Map<ModuleVersion, ReferenceGraphNode> mapReferenceGraphNode;

	/**
	 * List of the root {@link ModuleVersion}'s.
	 */
	private List<ModuleVersion> listModuleVersionRoot;

	/**
	 * Constructor.
	 */
	public SimpleReferenceGraph() {
		// We use a LinkedHashMap to preserve insertion order.
		this.mapReferenceGraphNode = new LinkedHashMap<ModuleVersion, ReferenceGraphNode>();

		this.listModuleVersionRoot = new ArrayList<ModuleVersion>();
	}

	@Override
	public boolean moduleVersionExists(ModuleVersion moduleVersion) {
		return this.mapReferenceGraphNode.containsKey(moduleVersion);
	}

	@Override
	public List<ModuleVersion> getListModuleVersionRoot() {
		return Collections.unmodifiableList(this.listModuleVersionRoot);
	}

	@Override
	public boolean isRootModuleVersion(ModuleVersion moduleVersion) {
		return this.listModuleVersionRoot.contains(moduleVersion);
	}

	@Override
	public List<ModuleVersion> getListModuleVersionMatched(ModuleVersion moduleVersion) {
		Set<ModuleVersion> setModuleVersion;
		List<ModuleVersion> listModuleVersionMatched;

		setModuleVersion = this.mapReferenceGraphNode.keySet();
		listModuleVersionMatched = new ArrayList<ModuleVersion>();

		for (ModuleVersion moduleVersion2: setModuleVersion) {
			if (moduleVersion2.getNodePath().equals(moduleVersion.getNodePath())) {
				if (   (moduleVersion.getVersion() == null)
					|| (moduleVersion2.getVersion().equals(moduleVersion.getVersion()))) {
						listModuleVersionMatched.add(moduleVersion2);
				}
			}
		}

		return listModuleVersionMatched;
	}

	@Override
	public List<ReferenceGraph.Referrer> getListReferrer(ModuleVersion moduleVersionReferred) {
		ReferenceGraphNode referenceGraphNode;

		referenceGraphNode = this.mapReferenceGraphNode.get(moduleVersionReferred);

		if (referenceGraphNode == null) {
			throw new RuntimeException("ModuleVersion " + moduleVersionReferred + " not in ReferenceGraph.");
		}

		if (referenceGraphNode.listReferrer == null) {
			return Collections.emptyList();
		} else {
			return Collections.unmodifiableList(referenceGraphNode.listReferrer);
		}
	}

	@Override
	public List<Reference> getListReference(ModuleVersion moduleVersionReferrer) {
		ReferenceGraphNode referenceGraphNode;

		referenceGraphNode = this.mapReferenceGraphNode.get(moduleVersionReferrer);

		if (referenceGraphNode == null) {
			throw new RuntimeException("ModuleVersion " + moduleVersionReferrer + " not in ReferenceGraph.");
		}

		if (referenceGraphNode.listReference == null) {
			return Collections.emptyList();
		} else {
			return Collections.unmodifiableList(referenceGraphNode.listReference);
		}
	}

	@Override
	public void traverseReferenceGraph(ModuleVersion moduleVersion, boolean indDepthFirst, boolean indAvoidReentry, Visitor visitor) {
		ModuleReentryAvoider moduleReentryAvoider;
		ReferenceGraphNode referenceGraphNode;

		if (indAvoidReentry) {
			moduleReentryAvoider = new ModuleReentryAvoider();
		} else {
			moduleReentryAvoider = null;
		}

		if (moduleVersion == null) {
			ReferencePath referencePath;

			referencePath = new ReferencePath();

			for (ModuleVersion moduleVersion2: this.listModuleVersionRoot) {
				this.traverseReferenceGraph(referencePath, new Reference(moduleVersion2), indDepthFirst, moduleReentryAvoider, visitor);
			}
		} else {
			referenceGraphNode = this.mapReferenceGraphNode.get(moduleVersion);

			if (referenceGraphNode == null) {
				throw new RuntimeException("ModuleVersion " + moduleVersion + " not in ReferenceGraph.");
			}

			this.traverseReferenceGraph(new ReferencePath(), new Reference(referenceGraphNode.moduleVersion), indDepthFirst, moduleReentryAvoider, visitor);
		}
	}

	/**
	 * Internal traversal method.
	 *
	 * @param referencePath Current {@link ReferencePath}, not including visited
	 *   {@link Reference}.
	 * @param reference Visited Reference.
	 * @param indDepthFirst Indicates that the traversal is depth-first, as opposed to
	 *   parent-first.
	 * @param moduleReentryAvoider ModuleReentryAvoider to be used to avoid reentry.
	 *   null if reentry is not to be avoided.
	 * @param visitor Visitor.
	 */
	private void traverseReferenceGraph(ReferencePath referencePath, Reference reference, boolean indDepthFirst, ModuleReentryAvoider moduleReentryAvoider, Visitor visitor) {
		boolean isAlreadyProcessed;
		ReferenceGraphNode referenceGraphNode;

		isAlreadyProcessed = (moduleReentryAvoider != null) && !moduleReentryAvoider.processModule(reference.getModuleVersion());

		try {
			// This validates that no cycle exists in the ReferencePath.
			// TODO: But it may be better to validate the absence of cycle when building the ReferenceGraph.
			referencePath.add(reference);

			if (!indDepthFirst) {
				visitor.visit(referencePath, isAlreadyProcessed ? VisitAction.REPEATED_VISIT : VisitAction.VISIT);
			}

			if (!isAlreadyProcessed) {
				referenceGraphNode = this.mapReferenceGraphNode.get(reference.getModuleVersion());

				if (referenceGraphNode.listReference != null) {
					visitor.visit(referencePath, VisitAction.STEP_IN);

					for (Reference reference2: referenceGraphNode.listReference) {
						this.traverseReferenceGraph(referencePath, reference2, indDepthFirst, moduleReentryAvoider, visitor);
					}

					visitor.visit(referencePath, VisitAction.STEP_OUT);
				}
			}

			if (indDepthFirst) {
				visitor.visit(referencePath, isAlreadyProcessed ? VisitAction.REPEATED_VISIT : VisitAction.VISIT);
			}
		} finally {
			referencePath.removeLeafReference();
		}
	}

	@Override
	public void visitLeafModuleVersionReferencePaths(ModuleVersion moduleVersion, Visitor visitor) {
		ReferenceGraphNode referenceGraphNode;

		referenceGraphNode = this.mapReferenceGraphNode.get(moduleVersion);

		if (referenceGraphNode == null) {
			throw new RuntimeException("ModuleVersion " + moduleVersion + " not in ReferenceGraph.");
		}

		this.traverseReferenceGraphForLeafModuleVersionReferencePaths(new ReferencePath(), moduleVersion, visitor);
	}

	/**
	 * Internal traversal method for visiting the {@link ReferencePath}'s ending with
	 * a leaf {@link ModuleVersion}.
	 * <p>
	 * This traversal goes upward through the referrer and stops at root ModuleVersion's.
	 *
	 * @param referencePath Current ReferencePath, not including visited
	 *   {@link ModuleVersion}.
	 * @param moduleVersion Visited ModuleVersion.
	 * @param visitor Visitor.
	 */
	private void traverseReferenceGraphForLeafModuleVersionReferencePaths(ReferencePath referencePath, ModuleVersion moduleVersion, Visitor visitor) {
		ReferencePath referencePathIncludingParent;
		ReferenceGraphNode referenceGraphNode;

		if (this.listModuleVersionRoot.contains(moduleVersion)) {
			referencePathIncludingParent = new ReferencePath();
			referencePathIncludingParent.add(new Reference(moduleVersion));
			referencePathIncludingParent.add(referencePath);
			visitor.visit(referencePathIncludingParent, VisitAction.VISIT);
		}

		referenceGraphNode = this.mapReferenceGraphNode.get(moduleVersion);

		for (ReferenceGraph.Referrer referrer: referenceGraphNode.listReferrer) {
			referencePathIncludingParent = new ReferencePath();
			referencePathIncludingParent.add(referrer.getReference());
			referencePathIncludingParent.add(referencePath);

			this.traverseReferenceGraphForLeafModuleVersionReferencePaths(referencePath, referrer.getModuleVersion(), visitor);
		}
	}

	@Override
	public void addRootModuleVersion(ModuleVersion moduleVersionRoot) {
		if (!this.mapReferenceGraphNode.containsKey(moduleVersionRoot)) {
			this.mapReferenceGraphNode.put(moduleVersionRoot, new ReferenceGraphNode(moduleVersionRoot));
		}

		if (!this.listModuleVersionRoot.contains(moduleVersionRoot)) {
			this.listModuleVersionRoot.add(moduleVersionRoot);
		}
	}

	@Override
	// TODO: Not sure if should detect cycles here, for each reference insertion (performance).
	// Cycles are detected during traversal though, which provide higher performance since we have a current path during traversal.
	public void addReference(ModuleVersion moduleVersionReferrer, Reference reference) {
		ReferenceGraphNode referenceGraphNodeReferrer;
		ReferenceGraphNode referenceGraphNodeReference;
		ReferenceGraph.Referrer referrer;

		// Take care of referrer.

		referenceGraphNodeReferrer = this.mapReferenceGraphNode.get(moduleVersionReferrer);

		if (referenceGraphNodeReferrer == null) {
			referenceGraphNodeReferrer = new ReferenceGraphNode(moduleVersionReferrer);
			this.mapReferenceGraphNode.put(moduleVersionReferrer, referenceGraphNodeReferrer);
		}

		if (referenceGraphNodeReferrer.listReference == null) {
			referenceGraphNodeReferrer.listReference = new ArrayList<Reference>();
		}

		if (!referenceGraphNodeReferrer.listReference.contains(reference)) {
			referenceGraphNodeReferrer.listReference.add(reference);
		}

		// Take care of reference.

		referenceGraphNodeReference = this.mapReferenceGraphNode.get(reference.getModuleVersion());

		if (referenceGraphNodeReference == null) {
			referenceGraphNodeReference = new ReferenceGraphNode(reference.getModuleVersion());
			this.mapReferenceGraphNode.put(reference.getModuleVersion(), referenceGraphNodeReference);
		}

		if (referenceGraphNodeReference.listReferrer == null) {
			referenceGraphNodeReference.listReferrer = new ArrayList<ReferenceGraph.Referrer>();
		}

		referrer = new ReferenceGraph.Referrer(moduleVersionReferrer, reference);

		if (!referenceGraphNodeReference.listReferrer.contains(referrer)) {
			referenceGraphNodeReference.listReferrer.add(referrer);
		}
	}

	@Override
	//TODO: Could possibly be optimized by not reusing addReference.
	public void addReferencePath(ReferencePath referencePath) {
		ModuleVersion moduleVersionParent;

		moduleVersionParent = null;

		for (int i = 0; i < referencePath.size(); i++) {
			if (i == 0) {
				moduleVersionParent = referencePath.get(i).getModuleVersion();
				this.addRootModuleVersion(moduleVersionParent);
			} else {
				Reference reference;

				reference = referencePath.get(i);

				this.addReference(moduleVersionParent, reference);

				// For the next Reference, this one will be the parent.
				moduleVersionParent = reference.getModuleVersion();
			}
		}
	}
}
