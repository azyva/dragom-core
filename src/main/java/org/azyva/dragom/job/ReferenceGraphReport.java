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

import java.io.Writer;
import java.nio.file.Path;
import java.util.List;

import org.azyva.dragom.apiutil.ByReference;
import org.azyva.dragom.model.ArtifactGroupId;
import org.azyva.dragom.model.ModuleVersion;
import org.azyva.dragom.model.Version;
import org.azyva.dragom.reference.Reference;
import org.azyva.dragom.reference.ReferenceGraph;
import org.azyva.dragom.reference.ReferencePath;
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
	public enum OutputFormat {
		XML,
		JSON,
		TEXT
	}

	public enum ReferenceGraphMode {
		FULL_TREE,
		TREE_NO_REDUNDANCY
	}

	public enum ModuleFilter {
		ALL,
		ONLY_MULTIPLE_VERSIONS
	}

	/**
	 * ReferenceGraph.
	 */
	private ReferenceGraph referenceGraph;

	private OutputFormat outputFormat;

	private Path pathOutputFile;

	private Writer writerOutput;

	private boolean indIncludeReferenceGraph;

	private ReferenceGraphMode referenceGraphMode;

	private boolean indIncludeModules;

	private ModuleFilter moduleFilter;

	/**
	 * Constructor.
	 *
	 * @param referenceGraph ReferenceGraph.
	 */
	public ReferenceGraphReport(ReferenceGraph referenceGraph, ReferenceGraphReport.OutputFormat outputFormat) {
		this.referenceGraph = referenceGraph;
		this.outputFormat = outputFormat;
	}

	public void setOutputFilePath(Path pathOutputFile) {
		if (this.writerOutput != null) {
			throw new RuntimeException("Output Writer already set.");
		}

		this.pathOutputFile = pathOutputFile;
	}

	public void setOutputWriter(Writer writerOutput) {
		if (this.pathOutputFile != null) {
			throw new RuntimeException("Output file Path already set.");
		}

		this.writerOutput = writerOutput;
	}

	public void includeReferenceGraph() {
		this.indIncludeReferenceGraph = true;
	}

	public void setReferenceGraphMode(ReferenceGraphReport.ReferenceGraphMode referenceGraphMode) {
		if (!this.indIncludeReferenceGraph) {
			throw new RuntimeException("Must include ReferenceGraph before setting mode.");
		}

		this.referenceGraphMode = referenceGraphMode;
	}

	public void includeModules(ReferenceGraphReport.ModuleFilter moduleFilter) {
		this.indIncludeModules = true;
		this.moduleFilter = moduleFilter;
	}

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
	// ??? One or the other two following fields.
	public ReportReferenceGraphNode reportReferenceGraphNode;
	public ModuleVersion moduleVersion;
	public String extraInfo;
}

class ReportModule {
	public List<ReportModuleVersion> listReportModuleVersion;
}

class ReportModuleVersion {
	public List<ReferencePath> listReferencePath;
}
