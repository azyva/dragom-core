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

import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlElementWrapper;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

import org.azyva.dragom.apiutil.ByReference;
import org.azyva.dragom.execcontext.plugin.impl.MapWorkspaceDirPathXmlAdapter;
import org.azyva.dragom.model.ArtifactGroupId;
import org.azyva.dragom.model.ModuleVersion;
import org.azyva.dragom.model.NodePath;
import org.azyva.dragom.model.Version;
import org.azyva.dragom.model.support.MapModuleVersionXmlAdapter;
import org.azyva.dragom.model.support.MapNodePathXmlAdapter;
import org.azyva.dragom.model.support.MapVersionXmlAdapter;
import org.azyva.dragom.reference.Reference;
import org.azyva.dragom.reference.ReferenceGraph;
import org.azyva.dragom.reference.ReferencePath;
import org.azyva.dragom.reference.support.SimpleReferenceGraph;



report [--graph] [--module-versions [--reference-graph-paths] [--most-recent-version] [--most-recent-available-version]]


/**
 * Produces a report about a {@link ReferenceGraph}.
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

	private ReferenceGraphMode referenceGraphMode;

	private boolean indIncludeModules;

	private ModuleFilter moduleFilter;

	private boolean indIncludeMostRecentVersionInScm;

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

	public void setReferenceGraphMode(ReferenceGraphReport.ReferenceGraphMode referenceGraphMode) {
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
		switch (this.outputFormat) {
		case XML:
			break;

		case JSON:
			break;

		case TEXT:
			break;

		default:
			throw new RuntimeException("Invalid output format " + this.outputFormat + '.');
		}
	}
}

/**
 * Root class representing the data in a reference graph report.
 * <p>
 * Whereas {@link ReferenceGraph} contains the raw data about a reference graph,
 * this class contains approximately the same data but structured in a way that
 * more closely matches a report meant to be consumed by humans.
 * <p>
 * A reference graph report contains two distinct sections:
 * <p>
 * <li>Reference graph itself modeled mostly as a tree
 * <p>List of {@link Module}'s with their {@link Versions}'s that occur within the
 * reference graph
 * <p>
 * In the first section, the reference graph is modeled as a tree as representing
 * it as a true graph in a report is more complex and is not currently supported.
 * <p>
 * The reference graph can be either completely unfolded, meaning that it is
 * rendered by traversing it as a tree and {@link ModuleVersions}'s and their
 * references that occur more than once in the graph occur more than once in the
 * report as well.
 * <p>
 * Alternatively, redundancy can be avoided by using bookmarks. When a
 * ModuleVersion occurs more than once, it is bookmarked and where it occurs again
 * after the first time, a jump-to-bookmark indicator is included instead of
 * repeating the sub-tree.
 * <p>
 * In the second section, the Module's are identified by their {@link NodePath}'s.
 * For each Module the list of Version's occurring within the reference graph and
 * for each Version the list of {@link ReferenceGraphPath}'s corresponding to the
 * ModuleVersion is included. Also, each Version can be tagged as being the most
 * recent Version of the Module in the reference graph or the most recent one
 * available in the SCM. If the most recent Version available in the SCM is not
 * present in the reference graph, an entry for it can still be included in the
 * report.
 * <p>
 * Dragom natively supports producing a reference graph report in XML, JSON and
 * text format. This class and the other referenced classes are designed to help
 * produce the report in these formats. If it is required that the report be
 * produced in some other format, various solutions exist based on these formats:
 * <p>
 * <li>XSLT can be used to transform the report in XML format into some other
 * format, such as PDF using FOP</li>
 * <li>A tool can be developped to interpret the report in XML or JSON format and
 * produce it in some other format</li>
 * <p>
 * The report in text format could also be produced from the report in XML format
 * using XSLT. But a simple text format is supported by Dragom natiely mainly for
 * performance reasons and as a debugging help.
 */
@XmlRootElement(name="reference-graph-report")
@XmlAccessorType(XmlAccessType.NONE)
class Report {
	@XmlElementWrapper(name="root-reference-graph-nodes")
	@XmlElement(name="root-reference-graph-node")
	public List<ReportReferenceGraphNode> listReferenceGraphNodeRoot;

	@XmlElementWrapper(name="modules")
	@XmlElement(name="module")
	public List<ReportModule> listModule;


	public void writeTextReport(Writer writer) {

	}
}

/**
 * Represents a node in the first section of a reference graph report.
 */
@XmlAccessorType(XmlAccessType.NONE)
class ReportReferenceGraphNode {
	/*
	 * Name given to the node so that it can be referenced from a
	 * {@link ReportReference} or from a {@link ReportVersion}.
	 * <p>
	 * Can be null, which indicates that this node is not referenced.
	 */
	@XmlElement(name="bookmark")
	public String bookmark;

	/**
	 * {@link ModuleVersion} represented by the node.
	 */
	@XmlElement(name="module-version", type=String.class)
	@XmlJavaTypeAdapter(MapModuleVersionXmlAdapter.class)
	public ModuleVersion moduleVersion;

	/**
	 * List of {@link ReportReference}'s for the node.
	 * <p>
	 * Can be null if node does not contain any references to {@link ModuleVersion}'s
	 * known to Dragom.
	 */
	@XmlElementWrapper(name="references")
	@XmlElement(name="reference")
	public List<ReportReference> listReference;

	public void writeTextReport(Writer writer, int level) {

	}
}

/**
 * Represents a reference in the first section of a reference graph report.
 */
@XmlAccessorType(XmlAccessType.NONE)
class ReportReference {
	/*
	 * Referenced {@link ReportReferenceGraphNode}.
	 * <p>
	 * If not null, moduleVersion and jumpToReferenceGraphNodeBookmark are null.
	 * <p>
	 * null if redundancy is avoided in the report and the referenced
	 * {@link ModuleVersion} has already occurred. In this case moduleVersion and
	 * jumpToReferenceGraphNodeBookmark are not null.
	 */
	@XmlElement(name="root-reference-graph-node")
	public ReportReferenceGraphNode reportReferenceGraphNode;

	/**
	 * Referenced {@link ModuleVersion}.
	 * <p>
	 * Not null if redundancy is avoided in the report and the referenced
	 * {@link ModuleVersion} has already occurred. In this case
	 * reportReferenceGraphNode is null moduleVersion and
	 * jumpToReferenceGraphNodeBookmark is not null.
	 * <p>
	 * null if reportReferenceGraphNode is not null.
	 */
	@XmlElement(name="module-version", type=String.class)
	@XmlJavaTypeAdapter(MapModuleVersionXmlAdapter.class)
	public ModuleVersion moduleVersion;

	/**
	 * Bookmark of {@link ReportReferenceGraphNode} that already occurred.
	 * <p>
	 * Not null if redundancy is avoided in the report and the referenced
	 * {@link ModuleVersion} has already occurred. In this case
	 * reportReferenceGraphNode is null moduleVersion and
	 * moduleVersion is not null.
	 * <p>
	 * null if reportReferenceGraphNode is not null.
	 */
	@XmlElement(name="jump-to-reference-graph-node-bookmark")
	public String jumpToReferenceGraphNodeBookmark;

	/**
	 * Extra information related to the reference obtained from
	 * {@link Reference#getImplData}, such as implementation-specific Maven reference
	 * information.
	 */
	@XmlElement(name="extra-info")
	public String extraInfo;
}

/**
 * Represents a module in the second part of the reference graph report.
 */
@XmlAccessorType(XmlAccessType.NONE)
class ReportModule {
	/**
	 * {@link NodePath} of the module.
	 */
	@XmlElement(name="module-node-path", type=String.class)
	@XmlJavaTypeAdapter(MapNodePathXmlAdapter.class)
	public NodePath nodePathModule;

	/**
	 * List of {@link ReportVersion}'s of the module that occur within the reference
	 * graph.
	 */
	@XmlElementWrapper(name="versions")
	@XmlElement(name="version")
	public List<ReportVersion> listReportVersion;
}

/**
 * Represents the {@link Version} of a module in the second part of the reference
 * graph report.
 */
@XmlAccessorType(XmlAccessType.NONE)
class ReportVersion {
	/**
	 * {@link Version}.
	 */
	@XmlElement(name="version", type=String.class)
	@XmlJavaTypeAdapter(MapVersionXmlAdapter.class)
	public Version version;

	/**
	 * Indicates that the {@link Version} is the most recent for the module within the
	 * reference graph.
	 */
	@XmlElement(name="ind-most-recent-in-reference-graph")
	public boolean indMostRecentInReferenceGraph;

	/**
	 * Indicates that the {@link Version} is the most recent for the module in the
	 * SCM. This Version is not necessarily present in the reference graph. It is
	 * added to the report if this information was requested.
	 * <p>
	 * If true and Version occurs within the reference graph, then
	 * indMostRecentInReferenceGraph is necessarily also true. Conversely, if true and
	 * indMostRecentInReferenceGraph is true, than Version occurs within the reference
	 * graph.
	 */
	@XmlElement(name="ind-most-recent-in-scm")
	public boolean indMostRecentInScm;

	/**
	 * List of {@link ReportReferenceGraphNode} bookmarks where this {@link Version}
	 * occurs.
	 * <p>
	 * If indMostRecentInScm is true, this may be null if the reference graph does not
	 * include this Version. In that case, indMostRecentInReferenceGraph is false.
	 */
	@XmlElementWrapper(name="reference-graph-node-bookmarks")
	@XmlElement(name="reference-graph-node-bookmark")
	public List<String> listStringReferenceGraphNodeBookmark;
}
