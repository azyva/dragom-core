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

package org.azyva.dragom.job;

import java.util.List;

import org.azyva.dragom.model.ModuleVersion;
import org.azyva.dragom.reference.Reference;
import org.azyva.dragom.reference.ReferenceGraph;
import org.azyva.dragom.reference.ReferencePath;
import org.azyva.dragom.reference.ReferencePathMatcher;
import org.azyva.dragom.reference.support.SimpleReferenceGraph;

/**
 * The principle of operation of this class is to traverse reference graphs in the
 * standard way using a List of root {@link ModuleVersion}'s and a
 * {@link ReferencePathMatcher} to build the {@link ReferenceGraph}.
 * <p>
 * This is implemented in a way that is similar to other job classes, but it is
 * only intended to be used internally as building a ReferenceGraph is not really
 * useful in itself. If can be useful however to produce a ReferenceGraph report,
 * as {@link ReferenceGraphReport} does.
 *
 * @author David Raymond
 */
public class BuildReferenceGraph extends RootModuleVersionJobAbstractImpl {
	/**
	 * ReferenceGraph that will be built.
	 */
	private ReferenceGraph referenceGraph;

	/**
	 * Constructor.
	 *
	 * @param referenceGraph ReferenceGraph that will be built or completed. Can be
	 *   null in which case a new initially empty SimpleReferenceGraph is used.
	 * @param listModuleVersionRoot List of root ModuleVersion's on which to initiate
	 *   the traversal of the reference graphs.
	 */
	public BuildReferenceGraph(ReferenceGraph referenceGraph, List<ModuleVersion> listModuleVersionRoot) {
		super(listModuleVersionRoot);

		if (referenceGraph == null) {
			this.referenceGraph = new SimpleReferenceGraph();
		}
	}

	/**
	 * @return ReferenceGraph that was provided to or created by the constructor.
	 */
	public ReferenceGraph getReferenceGraph() {
		return this.referenceGraph;
	}

	/**
	 * This method will be called only for matched {@link ModuleVersion}'s. But the
	 * {@link ReferenceGraph} needs to include the {@link ReferencePath}'s leading to
	 * them. It must also identify those ModuleVersion's which are the roots. This is
	 * all taken care of by {@link ReferenceGraph#addReferencePath}.
	 *
	 * @param reference Reference to the matched ModuleVersion.
	 * @return Indicates if children must be visited. true is returned.
	 */
	@Override
	protected boolean visitMatchedModuleVersion(Reference reference) {
		this.referencePath.add(reference);

		try {
			this.referenceGraph.addReferencePath(this.referencePath);
		} finally {
			this.referencePath.removeLeafReference();
		}

		return true;
	}

}
