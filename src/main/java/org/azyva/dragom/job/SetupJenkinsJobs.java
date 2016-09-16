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

import java.nio.file.Path;
import java.util.EnumSet;

import org.azyva.dragom.model.Module;
import org.azyva.dragom.reference.ReferenceGraph;
import org.azyva.dragom.reference.ReferencePath;

/**
 * Sets up jobs in Jenkins based on the {@link ModuleVersion's} in a
 * {@link ReferenceGraph}.
 * <p>
 * While most job classes derive from {@link RootModuleVersionJobAbstractImpl},
 * this class works with a {@link ReferenceGraph} which was presumably created
 * using {@link BuildReferenceGraph}. It works with a ReferenceGraph since for
 * each ModuleVersion it must know the ModuleVersion's which depend on it.
 *
 * @author David Raymond
 */
public class SetupJenkinsJobs {
	/**
	 * {@link ReferenceGraph}.
	 */
	private ReferenceGraph referenceGraph;

	/**
	 * Path to the file containing the items created.
	 */
	private Path pathItemsCreatedFile;

	/**
	 * Constructor.
	 *
	 * @param referenceGraph ReferenceGraph.
	 */
	public SetupJenkinsJobs(ReferenceGraph referenceGraph, Path pathItemsCreatedFile) {
		this.referenceGraph = referenceGraph;
	}

	/**
	 * Sets the output file Path.
	 * <p>
	 * Either the output file Path or the output Writer can be set, but not both.
	 *
	 * @param pathOutputFile Output file Path.
	 */

	/**
	 * {@link ReferenceGraph.Visitor} used to build the {@link Report}.
	 * <p>
	 * Both the reference graph report and the list of {@link Module}'s are build
	 * simultaneously.
	 * <p>
	 * For the list of Module's, all Module's and their Version's are included.
	 * However the Report is expected to be adjusted after the traversal in order to
	 * remove unwanted Module's if {@link SetupJenkinsJobs#moduleFilter} is such
	 * that not all Module's need to be included, and generate the list of
	 * ReferencePath literals.
	 */
	private class ReferenceGraphVisitorZzz implements ReferenceGraph.Visitor {
		/**
		 * Constructor.
		 */
		public ReferenceGraphVisitorZzz() {
		}

		@Override
		public void visit(ReferencePath referencePath, EnumSet<ReferenceGraph.VisitAction> enumSetVisitAction) {
		}
	}

	/**
	 * Main method for performing the job.
	 */
	public void performJob() {
		SetupJenkinsJobs.ReferenceGraphVisitorZzz referenceGraphVisitorZzz;

		referenceGraphVisitorZzz = new SetupJenkinsJobs.ReferenceGraphVisitorZzz();

		// For the reference graph report, we always avoid reentry, regardless of the
		// value of this.referenceGraphMode. The idea is that even if
		// this.referenceGraphMode is TREE_NO_REDUNDANCY, existing
		// ReportReferenceGraphNode are reused. It is just that ReportReference with
		// jumpToReferenceGraphNodeBookmark are generated when appropriate.
		this.referenceGraph.traverseReferenceGraph(null, false, true, referenceGraphVisitorZzz);
	}
}
