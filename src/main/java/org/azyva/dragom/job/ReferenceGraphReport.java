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

import org.azyva.dragom.apiutil.ByReference;
import org.azyva.dragom.model.ModuleVersion;
import org.azyva.dragom.model.Version;
import org.azyva.dragom.reference.Reference;
import org.azyva.dragom.reference.ReferenceGraph;
import org.azyva.dragom.reference.support.SimpleReferenceGraph;



report [--graph] [--module-versions [--reference-graph-paths] [--most-recent-version] [--most-recent-available-version]]


/**
 * Produced a report about a {@link ReferenceGraph}.
 * <p>
 * While most job classes derive from {@link RootModuleVersionJobAbstractImpl},
 * this works with a {@link ReferenceGraph} which was presumably created using
 * {@link BuildReferenceGraph}.
 *
 * @author David Raymond
 */
public class ReferenceGraphReport {
	private class InternalReport {

	}

	/**
	 * ReferenceGraph.
	 */
	private ReferenceGraph referenceGraph;

	/**
	 * Constructor.
	 *
	 * @param referenceGraph ReferenceGraph.
	 */
	public ReferenceGraphReport(ReferenceGraph referenceGraph) {
		this.referenceGraph = referenceGraph;
	}

	public set

	/**
	 * Main method for performing the job.
	 */
	public void performJob() {
	}
}

class Report {
	public List<ReportReferenceGraphNode> listReferenceGraphNodeRoot;
	public List<ReportModule> listModule;
}

class ReportReferenceGraphNode {
	public ModuleVersion moduleVersion;
	public List<ReportReference> listReference;
}

class ReportReference {
	public Reference
}

class ReportModule {
}

class ReportModuleVersion {
}

class ReportModuleVersionReferencePath {
}

