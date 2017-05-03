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

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlElementWrapper;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

import org.azyva.dragom.execcontext.support.ExecContextHolder;
import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.ModuleVersion;
import org.azyva.dragom.model.NodePath;
import org.azyva.dragom.model.Version;
import org.azyva.dragom.model.plugin.ScmPlugin;
import org.azyva.dragom.model.plugin.VersionClassifierPlugin;
import org.azyva.dragom.model.support.MapModuleVersionXmlAdapter;
import org.azyva.dragom.model.support.MapNodePathXmlAdapter;
import org.azyva.dragom.model.support.MapVersionXmlAdapter;
import org.azyva.dragom.model.support.ModuleVersionJsonConverter;
import org.azyva.dragom.model.support.NodePathJsonConverter;
import org.azyva.dragom.model.support.VersionJsonConverter;
import org.azyva.dragom.reference.Reference;
import org.azyva.dragom.reference.ReferenceGraph;
import org.azyva.dragom.reference.ReferencePath;
import org.azyva.dragom.util.Util;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

/**
 * Produces a report about a {@link ReferenceGraph}.
 *
 * @author David Raymond
 */
public class ReferenceGraphReport extends RootModuleVersionJobSimpleAbstractImpl {
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
    ONLY_MULTIPLE_VERSIONS,

    /**
     * Indicates to include only the {@link Module}'s for which at least one
     * {@link Version} is matched.
     */
    ONLY_MATCHED
  }

  /**
   * {@link ReferenceGraphReport.OutputFormat}.
   */
  private OutputFormat outputFormat;

  /**
   * Output file Path.
   */
  private Path pathOutputFile;

  /**
   * Output Writer.
   */
  private Writer writerOutput;

  /**
   * Indicates to include the reference graph in the report.
   */
  private boolean indIncludeReferenceGraph;

  /**
   * {@link ReferenceGraphReport.ReferenceGraphMode}.
   */
  private ReferenceGraphMode referenceGraphMode;

  /**
   * Indicates to include the {@link Module}'s and their {@link Version}'s in the
   * report.
   */
  private boolean indIncludeModules;

  /**
   * {@link ReferenceGraphReport.ModuleFilter}.
   */
  private ModuleFilter moduleFilter;

  /**
   * Indicates to include the most recent {@link Version} in the reference graph for
   * each {@link Module}.
   */
  private boolean indIncludeMostRecentVersionInReferenceGraph;

  /**
   * Indicates to include the most recent static {@link Version} in the SCM for each
   * {@link Module}.
   */
  private boolean indIncludeMostRecentStaticVersionInScm;

  /**
   * Indicates to include the List of {@link ReferencePath} literals for each
   * {@link ModuleVersion}.
   */
  private boolean indIncludeReferencePaths;

  /**
   * Constructor.
   *
   * @param listModuleVersionRoot List of root ModuleVersions's.
   * @param outputFormat OutputFormat.
   */
  public ReferenceGraphReport(List<ModuleVersion> listModuleVersionRoot, ReferenceGraphReport.OutputFormat outputFormat) {
    super(listModuleVersionRoot);
    this.outputFormat = outputFormat;
  }

  /**
   * Sets the output file Path.
   * <p>
   * Either the output file Path or the output Writer can be set, but not both.
   *
   * @param pathOutputFile Output file Path.
   */
  public void setOutputFilePath(Path pathOutputFile) {
    if (this.writerOutput != null) {
      throw new RuntimeException("Output Writer already set.");
    }

    this.pathOutputFile = pathOutputFile;
  }

  /**
   * Sets the output Writer.
   * <p>
   * Either the output file Path or the output Writer can be set, but not both.
   *
   * @param writerOutput Output Writer.
   */
  public void setOutputWriter(Writer writerOutput) {
    if (this.pathOutputFile != null) {
      throw new RuntimeException("Output file Path already set.");
    }

    this.writerOutput = writerOutput;
  }

  /**
   * Indicates to include the reference graph in the report.
   *
   * @param referenceGraphMode ReferenceGraphMode.
   */
  public void includeReferenceGraph(ReferenceGraphReport.ReferenceGraphMode referenceGraphMode) {
    this.indIncludeReferenceGraph = true;
    this.referenceGraphMode = referenceGraphMode;
  }

  /**
   * Indicates to include the {@link Module}'s and their {@link Version}'s in the
   * report.
   *
   * @param moduleFilter ModuleFilter.
   */
  public void includeModules(ReferenceGraphReport.ModuleFilter moduleFilter) {
    this.indIncludeModules = true;
    this.moduleFilter = moduleFilter;
  }

  /**
   * Indicates to include the most recent {@link Version} in the reference graph for
   * each {@link Module}.
   */
  public void includeMostRecentVersionInReferenceGraph() {
    this.indIncludeMostRecentVersionInReferenceGraph = true;
  }

  /**
   * Indicates to include the most recent static {@link Version} in the SCM for
   * each {@link Module}.
   * <p>
   * Also implies including most recent Version in the reference graph for each
   * Module (see {@link #includeMostRecentVersionInReferenceGraph}.
   */
  public void includeMostRecentStaticVersionInScm() {
    this.indIncludeMostRecentStaticVersionInScm = true;
    this.indIncludeMostRecentVersionInReferenceGraph = true;
  }

  /**
   * Indicates to include the {@link ReferencePath} literals for each
   * {@link ModuleVersion}.
   */
  public void includeReferencePaths() {
    this.indIncludeReferencePaths = true;
  }

  /**
   * {@link org.azyva.dragom.reference.ReferenceGraph.Visitor} used to build the
   * {@link Report}.
   * <p>
   * Both the reference graph report and the list of {@link Module}'s are build
   * simultaneously.
   * <p>
   * For the list of Module's, all Module's and their Version's are included.
   * However the Report is expected to be adjusted after the traversal in order to
   * remove unwanted Module's if {@link ReferenceGraphReport#moduleFilter} is such
   * that not all Module's need to be included, and generate the list of
   * ReferencePath literals.
   */
  private class ReferenceGraphVisitorReport implements ReferenceGraph.Visitor {
    /**
     * {@link Report} being built.
     */
    Report report;

    /**
     * Map of the {@link ReportReferenceGraphNode}'s currently in the {@link Report}.
     */
    Map<ModuleVersion, ReportReferenceGraphNode> mapReportReferenceGraphNode;

    /**
     * Next bookmark index.
     */
    int nextBookmarkIndex;

    /**
     * Map of the {@link ReportModule}'s currently in the {@link Report}.
     */
    Map<NodePath, ReportModule> mapReportModule;

    /**
     * Set of the {@link Module} {@link NodePath} that are matched within the
     * {@link ReferenceGraph}.
     */
    Set<NodePath> setNodePathMatched;

    /**
     * Constructor.
     */
    public ReferenceGraphVisitorReport() {
      this.report = new Report();

      if (ReferenceGraphReport.this.indIncludeReferenceGraph) {
        this.mapReportReferenceGraphNode = new HashMap<ModuleVersion, ReportReferenceGraphNode>();
        this.nextBookmarkIndex = 1;
        this.report.listReportReference = new ArrayList<ReportReference>();
      }

      if (ReferenceGraphReport.this.indIncludeModules) {
        this.mapReportModule = new HashMap<NodePath, ReportModule>();
        this.setNodePathMatched = new HashSet<NodePath>();
        this.report.listReportModule = new ArrayList<ReportModule>();
      }
    }

    @Override
    public ReferenceGraph.VisitControl visit(ReferenceGraph referenceGraph, ReferencePath referencePath, EnumSet<ReferenceGraph.VisitAction> enumSetVisitAction) {
      /* *******************************************************************************
       * Reference graph report.
       * *******************************************************************************/
      if (enumSetVisitAction.contains(ReferenceGraph.VisitAction.VISIT)) {
        if (ReferenceGraphReport.this.indIncludeReferenceGraph) {
          List<ReportReference> listReportReference;
          String extraInfo;
          ModuleVersion moduleVersion;
          ReportReferenceGraphNode reportReferenceGraphNode;
          boolean indReportReferenceGraphNodeAlreadyExists;
          ReportReference reportReference;

          /* *******************************************************************************
           * Handle the reference graph.
           * *******************************************************************************/

          if (referencePath.size() == 1) {
            listReportReference = this.report.listReportReference;
            extraInfo = null;
          } else {
            Reference referenceParent;
            ReportReferenceGraphNode reportReferenceGraphNodeParent;
            Reference referenceLeaf;
            Object objectImplData;

            referenceParent = referencePath.get(referencePath.size() - 2);

            // When the ReferencePath includes more than one element, we only need to take the
            // last and before-last elements as the previous ones will necessarily already
            // have been taken care of.
            reportReferenceGraphNodeParent = this.mapReportReferenceGraphNode.get(referenceParent.getModuleVersion());

            if (reportReferenceGraphNodeParent == null) {
              throw new RuntimeException("Parent ReportReferenceGraphNode could not be found corresponding to ModuleVersion " + referenceParent.getModuleVersion() + '.');
            }

            if (reportReferenceGraphNodeParent.listReportReference == null) {
              reportReferenceGraphNodeParent.listReportReference = new ArrayList<ReportReference>();
            }

            listReportReference = reportReferenceGraphNodeParent.listReportReference;

            referenceLeaf = referencePath.getLeafReference();

            objectImplData = referenceLeaf.getImplData();

            extraInfo = (objectImplData == null) ? null : objectImplData.toString();

            if (referenceLeaf.getArtifactGroupId() != null) {
              if (extraInfo == null) {
                extraInfo = referenceLeaf.getArtifactGroupId().toString() + ':' + referenceLeaf.getArtifactVersion().toString();
              } else {
                extraInfo += ", " + referenceLeaf.getArtifactGroupId().toString() + ':' + referenceLeaf.getArtifactVersion().toString();
              }
            }
          }

          moduleVersion = referencePath.getLeafModuleVersion();
          reportReferenceGraphNode = this.mapReportReferenceGraphNode.get(moduleVersion);

          if (reportReferenceGraphNode == null) {
            if (enumSetVisitAction.contains(ReferenceGraph.VisitAction.REPEATED)) {
              throw new RuntimeException("ReportReferenceGraphNode could not be found corresponding to ModuleVersion " + moduleVersion + '.');
            }

            reportReferenceGraphNode = new ReportReferenceGraphNode();
            reportReferenceGraphNode.moduleVersion = moduleVersion;
            this.mapReportReferenceGraphNode.put(moduleVersion, reportReferenceGraphNode);

            indReportReferenceGraphNodeAlreadyExists = false;
          } else {
            indReportReferenceGraphNodeAlreadyExists = true;
          }

          reportReference = new ReportReference();

          if (!indReportReferenceGraphNodeAlreadyExists || (ReferenceGraphReport.this.referenceGraphMode == ReferenceGraphReport.ReferenceGraphMode.FULL_TREE)) {
            reportReference.reportReferenceGraphNode = reportReferenceGraphNode;
          } else { // if (indReportReferenceGraphNodeAlreadyExists && (ReferenceGraphReport.this.referenceGraphMode == ReferenceGraphReport.ReferenceGraphMode.TREE_NO_REDUNDANCY))
            reportReference.moduleVersion = moduleVersion;

            if (reportReferenceGraphNode.bookmark == null) {
              reportReferenceGraphNode.bookmark = "REF-" + this.nextBookmarkIndex++;
            }

            reportReference.jumpToReferenceGraphNodeBookmark = reportReferenceGraphNode.bookmark;
          }

          reportReference.extraInfo = extraInfo;

          listReportReference.add(reportReference);
        }

        if (ReferenceGraphReport.this.indIncludeModules) {
          ModuleVersion moduleVersion;
          ReportModule reportModule;
          ReportVersion reportVersion;

          /* *******************************************************************************
           * Handle the List of Module's and their Version's.
           * *******************************************************************************/

          if (enumSetVisitAction.contains(ReferenceGraph.VisitAction.REPEATED)) {
            return ReferenceGraph.VisitControl.CONTINUE;
          }

          moduleVersion = referencePath.getLeafModuleVersion();
          reportModule = this.mapReportModule.get(moduleVersion.getNodePath());

          if (reportModule == null) {
            reportModule = new ReportModule();
            reportModule.nodePathModule = moduleVersion.getNodePath();
            reportModule.listReportVersion = new ArrayList<ReportVersion>();
            this.mapReportModule.put(moduleVersion.getNodePath(), reportModule);
            this.report.listReportModule.add(reportModule);
          }

          if (enumSetVisitAction.contains(ReferenceGraph.VisitAction.MATCHED)){
            this.setNodePathMatched.add(moduleVersion.getNodePath());
          }

          // The Version necessarily does not exist yet since reentry is avoided during the
          // traversal since we are only considering ReferenceGraph.VisitAction.VISIT.

          reportVersion = new ReportVersion();

          reportVersion.version = moduleVersion.getVersion();

          reportModule.listReportVersion.add(reportVersion);
        }
      }

      return ReferenceGraph.VisitControl.CONTINUE;
    }
  }

  /**
   * Main method for performing the job.
   */
  @Override
  public void performJob() {
    BuildReferenceGraph buildReferenceGraph;
    ReferenceGraph referenceGraph;

    buildReferenceGraph = new BuildReferenceGraph(null, this.listModuleVersionRoot);
    buildReferenceGraph.setReferencePathMatcherProvided(this.getReferencePathMatcher());
    buildReferenceGraph.performJob();
    referenceGraph = buildReferenceGraph.getReferenceGraph();

    ReferenceGraphReport.ReferenceGraphVisitorReport referenceGraphVisitorReport;
    Iterator<ReportModule> iteratorReportModule;

    referenceGraphVisitorReport = new ReferenceGraphReport.ReferenceGraphVisitorReport();

    // For the reference graph report, we always avoid reentry, regardless of the
    // value of this.referenceGraphMode. The idea is that even if
    // this.referenceGraphMode is TREE_NO_REDUNDANCY, existing
    // ReportReferenceGraphNode are reused. It is just that ReportReference with
    // jumpToReferenceGraphNodeBookmark are generated when appropriate.
    referenceGraph.traverseReferenceGraph(null, ReferenceGraph.TraversalOrder.PARENT_FIRST, ReferenceGraph.ReentryMode.ONLY_PARENT, referenceGraphVisitorReport);

    // The reference graph report has been created by including all ReportModule's,
    // regardless of the ModuleFilter since it is not possible to perform the
    // filtering while traversing the ReferenceGraph. Now is time to remove those
    // ReportModule's that are not meant to be included.

    if (this.indIncludeModules) {
      iteratorReportModule = referenceGraphVisitorReport.report.listReportModule.iterator();

      while (iteratorReportModule.hasNext()) {
        ReportModule reportModule;
        Module module;
        VersionClassifierPlugin versionClassifierPlugin;
        ReportVersion reportVersionMax;

        reportModule = iteratorReportModule.next();
        module = ExecContextHolder.get().getModel().getModule(reportModule.nodePathModule);

        if (   ((this.moduleFilter == ReferenceGraphReport.ModuleFilter.ONLY_MULTIPLE_VERSIONS) && (reportModule.listReportVersion.size() == 1))
          || ((this.moduleFilter == ReferenceGraphReport.ModuleFilter.ONLY_MATCHED) && !referenceGraphVisitorReport.setNodePathMatched.contains(reportModule.nodePathModule))) {

          iteratorReportModule.remove();
          continue;
        }

        if (this.indIncludeMostRecentVersionInReferenceGraph || this.indIncludeMostRecentStaticVersionInScm) {
          versionClassifierPlugin = module.getNodePlugin(VersionClassifierPlugin.class, null);

          Collections.sort(
              reportModule.listReportVersion,
              new Comparator<ReportVersion>() {
                @Override
                public int compare(ReportVersion reportVersion1, ReportVersion reportVersion2) {
                  return -versionClassifierPlugin.compare(reportVersion1.version, reportVersion2.version);
                }
              });

          reportVersionMax = reportModule.listReportVersion.get(0);
        } else {
          versionClassifierPlugin = null;
          reportVersionMax = null;
        }

        if (this.indIncludeMostRecentVersionInReferenceGraph) {
          reportVersionMax.indMostRecentInReferenceGraph = true;
        }

        if (this.indIncludeMostRecentStaticVersionInScm) {
          ScmPlugin scmPlugin;
          List<Version> listVersionStatic;

          scmPlugin = module.getNodePlugin(ScmPlugin.class, null);

          listVersionStatic = scmPlugin.getListVersionStatic();
          Collections.sort(listVersionStatic, versionClassifierPlugin);

          if (!listVersionStatic.isEmpty()) {
            Version versionStaticMaxScm;

            versionStaticMaxScm = listVersionStatic.get(listVersionStatic.size() - 1);

            if (reportVersionMax.version.equals(versionStaticMaxScm)) {
              reportVersionMax.indMostRecentInScm = true;
            } else {
              reportVersionMax = new ReportVersion();
              reportVersionMax.version = versionStaticMaxScm;
              reportVersionMax.indMostRecentInScm = true;
              reportModule.listReportVersion.add(0, reportVersionMax);
            }
          }
        }

        if (this.indIncludeReferencePaths) {
          for (ReportVersion reportVersion: reportModule.listReportVersion) {
            // If the ReportVersion is the most recent in the SCM, but not in the reference
            // graph, it means it does not occur in the reference graph. It therefore does not
            // have any reference paths.
            if (((reportVersion.indMostRecentInScm != null) && reportVersion.indMostRecentInScm) && !((reportVersion.indMostRecentInReferenceGraph != null) && reportVersion.indMostRecentInReferenceGraph)) {
              continue;
            }

            reportVersion.listReferencePathLiteral = new ArrayList<String>();

            referenceGraph.visitLeafModuleVersionReferencePaths(
                new ModuleVersion(reportModule.nodePathModule, reportVersion.version),
                new ReferenceGraph.Visitor() {
                  @Override
                  public ReferenceGraph.VisitControl visit(ReferenceGraph referenceGraph, ReferencePath referencePath, EnumSet<ReferenceGraph.VisitAction> enumSetVisitAction) {
                    reportVersion.listReferencePathLiteral.add(referencePath.toString());
                    return ReferenceGraph.VisitControl.CONTINUE;
                  }
                });
          }
        }
      }
    }

    switch (this.outputFormat) {
    case XML:
      JAXBContext jaxbContext;
      Marshaller marshaller;

      try {
        jaxbContext = JAXBContext.newInstance(Report.class);
        marshaller = jaxbContext.createMarshaller();
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);

        if (this.pathOutputFile != null) {
          marshaller.marshal(referenceGraphVisitorReport.report, this.pathOutputFile.toFile());
        } else if (this.writerOutput != null) {
          marshaller.marshal(referenceGraphVisitorReport.report, this.writerOutput);
        } else {
          throw new RuntimeException("pathOutputFile and writerOutput have not been set.");
        }
      } catch (JAXBException je) {
        throw new RuntimeException(je);
      }

      break;

    case JSON:
      ObjectMapper objectMapper;

      try {
        objectMapper = new ObjectMapper();
        if (this.pathOutputFile != null) {
          objectMapper.writeValue(this.pathOutputFile.toFile(), referenceGraphVisitorReport.report);
        } else if (this.writerOutput != null) {
          objectMapper.writeValue(this.writerOutput, referenceGraphVisitorReport.report);
        } else {
          throw new RuntimeException("pathOutputFile and writerOutput have not been set.");
        }
      } catch (IOException ioe) {
        throw new RuntimeException(ioe);
      }
      break;

    case TEXT:
      Writer writer;

      try {
        if (this.pathOutputFile != null) {
          writer = new BufferedWriter(new FileWriter(this.pathOutputFile.toFile()));
        } else if (this.writerOutput != null) {
          writer = this.writerOutput;
        } else {
          throw new RuntimeException("pathOutputFile and writerOutput have not been set.");
        }

        referenceGraphVisitorReport.report.writeTextReport(writer);

        if (this.pathOutputFile != null) {
          writer.close();
        }
      } catch (IOException ioe) {
        throw new RuntimeException(ioe);
      }


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
 * <ul>
 * <li>Reference graph itself modeled mostly as a tree
 * <li>List of {@link Module}'s with their {@link Version}'s that occur within the
 * reference graph
 * </ul>
 * In the first section, the reference graph is modeled as a tree as representing
 * it as a true graph in a report is more complex and is not currently supported.
 * <p>
 * The reference graph can be either completely unfolded, meaning that it is
 * rendered by traversing it as a tree and {@link ModuleVersion}'s and their
 * references that occur more than once in the graph occur more than once in the
 * report as well. Actually, node objects representing the same ModuleVersion's
 * are reused, but if object identity is ignore, recursize production of the
 * report produces an unfolded tree.
 * <p>
 * Alternatively, redundancy can be avoided by using bookmarks. When a
 * ModuleVersion occurs more than once, it is bookmarked and where it occurs again
 * after the first time, a jump-to-bookmark indicator is included instead of
 * repeating the sub-tree.
 * <p>
 * In the second section, the Module's are identified by their {@link NodePath}'s.
 * For each Module the list of Version's occurring within the reference graph and
 * for each Version the list of {@link ReferencePath}'s corresponding to the
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
 * <ul>
 * <li>XSLT can be used to transform the report in XML format into some other
 * format, such as PDF using FOP
 * <li>A tool can be developped to interpret the report in XML or JSON format and
 * produce it in some other format
 * </ul>
 * The report in text format could also be produced from the report in XML format
 * using XSLT. But a simple text format is supported by Dragom natiely mainly for
 * performance reasons and as a debugging help.
 */
@XmlRootElement(name="reference-graph-report")
@XmlAccessorType(XmlAccessType.NONE)
@JsonAutoDetect(fieldVisibility=JsonAutoDetect.Visibility.NONE, getterVisibility=JsonAutoDetect.Visibility.NONE, isGetterVisibility=JsonAutoDetect.Visibility.NONE)
@JsonInclude(Include.NON_NULL)
class Report {
  /**
   * List of root {link ReportReference}'s.
   * <p>
   * These are ReportReference's and not simple {@link ReportReferenceGraphNode}'s
   * since a root {@link ModuleVersion} can occur elsewhere in the Report
   * and a bookmark may need to be used.
   * <p>
   * For root ReportReference's, extraInfo is never specified since there is no
   * actual reference from another node.
   */
  @XmlElementWrapper(name="root-references")
  @XmlElement(name="root-reference")
  @JsonProperty("root-references")
  public List<ReportReference> listReportReference;

  @XmlElementWrapper(name="modules")
  @XmlElement(name="module")
  @JsonProperty("modules")
  public List<ReportModule> listReportModule;


  /**
   * Writes a ReportReference in the text format.
   *
   * @param writer Writer.
   */
  public void writeTextReport(Writer writer) {
    try {
      if (this.listReportReference != null) {
        writer.append("ReferenceGraph\n");
        writer.append("==============\n");

        for (ReportReference reportReference: this.listReportReference) {
          reportReference.writeTextReport(writer, 0);
        }

        writer.append('\n');
      }

      if (this.listReportModule != null) {
        writer.append("Modules, Versions\n");
        writer.append("=================\n");

        for (ReportModule reportModule: this.listReportModule) {
          reportModule.writeTextReport(writer);
        }
      }

    } catch (IOException ioe) {
      throw new RuntimeException(ioe);
    }
  }
}

/**
 * Represents a reference in the first section of a reference graph report.
 */
@XmlAccessorType(XmlAccessType.NONE)
@JsonAutoDetect(fieldVisibility=JsonAutoDetect.Visibility.NONE, getterVisibility=JsonAutoDetect.Visibility.NONE, isGetterVisibility=JsonAutoDetect.Visibility.NONE)
@JsonInclude(Include.NON_NULL)
class ReportReference {
  /**
   * Referenced {@link ReportReferenceGraphNode}.
   * <p>
   * If not null, moduleVersion and jumpToReferenceGraphNodeBookmark are null.
   * <p>
   * null if redundancy is avoided in the report and the referenced
   * {@link ModuleVersion} has already occurred. In this case moduleVersion and
   * jumpToReferenceGraphNodeBookmark are not null.
   */
  @XmlElement(name="reference-graph-node")
  @JsonProperty("reference-graph-node")
  public ReportReferenceGraphNode reportReferenceGraphNode;

  /**
   * Referenced {@link ModuleVersion}.
   * <p>
   * Not null if redundancy is avoided in the report and the referenced
   * {@link ModuleVersion} has already occurred. In this case
   * reportReferenceGraphNode is null and moduleVersion and
   * jumpToReferenceGraphNodeBookmark is not null.
   * <p>
   * null if reportReferenceGraphNode is not null.
   */
  @XmlElement(name="module-version", type=String.class)
  @XmlJavaTypeAdapter(MapModuleVersionXmlAdapter.class)
  @JsonProperty("module-version")
  @JsonSerialize(converter=ModuleVersionJsonConverter.class)
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
  @JsonProperty("jump-to-reference-graph-node-bookmark")
  public String jumpToReferenceGraphNodeBookmark;

  /**
   * Extra information related to the reference obtained from
   * {@link Reference#getImplData}, such as implementation-specific Maven reference
   * information (including Maven artifact coordinates).
   */
  @XmlElement(name="extra-info")
  @JsonProperty("extra-info")
  public String extraInfo;

  /**
   * Writes a ReportReference in the text format.
   *
   * @param writer Writer.
   * @param level Current level for indentation.
   */
  public void writeTextReport(Writer writer, int level) {
    try {
      if (this.reportReferenceGraphNode != null) {
        this.reportReferenceGraphNode.writeTextReport(writer, level, this.extraInfo);
      } else {
        writer.append(Util.spaces(8 + (level * 2)));
        writer.append(this.moduleVersion.toString());

        if (this.extraInfo != null) {
          writer.append(" (").append(this.extraInfo).append(')');
        }

        writer.append('\n');

        writer.append(Util.spaces(8 + (level * 2) + 2)).append("-> ").append(this.jumpToReferenceGraphNodeBookmark).append('\n');
      }
    } catch (IOException ioe) {
      throw new RuntimeException(ioe);
    }
  }
}

/**
 * Represents a node in the first section of a reference graph report.
 */
@XmlAccessorType(XmlAccessType.NONE)
@JsonAutoDetect(fieldVisibility=JsonAutoDetect.Visibility.NONE, getterVisibility=JsonAutoDetect.Visibility.NONE, isGetterVisibility=JsonAutoDetect.Visibility.NONE)
@JsonInclude(Include.NON_NULL)
class ReportReferenceGraphNode {
  /**
   * Name given to the node so that it can be referenced from a
   * {@link ReportReference} or from a {@link ReportVersion}.
   * <p>
   * Can be null, which indicates that this node is not referenced.
   */
  @XmlElement(name="bookmark")
  @JsonProperty("bookmark")
  public String bookmark;

  /**
   * {@link ModuleVersion} represented by the node.
   */
  @XmlElement(name="module-version", type=String.class)
  @XmlJavaTypeAdapter(MapModuleVersionXmlAdapter.class)
  @JsonProperty("module-version")
  @JsonSerialize(converter=ModuleVersionJsonConverter.class)
  public ModuleVersion moduleVersion;

  /**
   * List of {@link ReportReference}'s for the node.
   * <p>
   * Can be null if node does not contain any references to {@link ModuleVersion}'s
   * known to Dragom.
   */
  @XmlElementWrapper(name="references")
  @XmlElement(name="reference")
  @JsonProperty("references")
  public List<ReportReference> listReportReference;

  /**
   * Writes a ReportReferenceGraphNode in the text format.
   *
   * @param writer Writer.
   * @param level Current level for indentation.
   * @param extraInfo Extra information related to the {@link ReportReference}
   *   referring to this ReportReferenceGraphNode. Can be null.
   */
  public void writeTextReport(Writer writer, int level, String extraInfo) {
    try {
      if (this.bookmark != null) {
        writer.append(String.format("%-8s", this.bookmark));
      } else {
        writer.append(Util.spaces(8));
      }

      writer.append(Util.spaces(level * 2)).append(this.moduleVersion.toString());

      if (extraInfo != null) {
        writer.append(" (").append(extraInfo).append(')');
      }

      writer.append('\n');

      if (this.listReportReference != null) {
        for (ReportReference reportReference: this.listReportReference) {
          reportReference.writeTextReport(writer, level + 1);
        }
      }
    } catch (IOException ioe) {
      throw new RuntimeException(ioe);
    }
  }
}

/**
 * Represents a module in the second part of the reference graph report.
 */
@XmlAccessorType(XmlAccessType.NONE)
@JsonAutoDetect(fieldVisibility=JsonAutoDetect.Visibility.NONE, getterVisibility=JsonAutoDetect.Visibility.NONE, isGetterVisibility=JsonAutoDetect.Visibility.NONE)
class ReportModule {
  /**
   * {@link NodePath} of the module.
   */
  @XmlElement(name="module-node-path", type=String.class)
  @XmlJavaTypeAdapter(MapNodePathXmlAdapter.class)
  @JsonProperty("module-node-path")
  @JsonSerialize(converter=NodePathJsonConverter.class)
  public NodePath nodePathModule;

  /**
   * List of {@link ReportVersion}'s of the module that occur within the reference
   * graph.
   */
  @XmlElementWrapper(name="versions")
  @XmlElement(name="version")
  @JsonProperty("versions")
  public List<ReportVersion> listReportVersion;

  /**
   * Writes a ReportModule in the text format.
   *
   * @param writer Writer.
   */
  public void writeTextReport(Writer writer) {
    try {
      writer.append("Module: ").append(this.nodePathModule.toString()).append('\n');

      for (ReportVersion reportVersion: this.listReportVersion) {
        reportVersion.writeTextReport(writer);
      }
    } catch (IOException ioe) {
      throw new RuntimeException(ioe);
    }
  }
}

/**
 * Represents the {@link Version} of a module in the second part of the reference
 * graph report.
 */
@XmlAccessorType(XmlAccessType.NONE)
@JsonAutoDetect(fieldVisibility=JsonAutoDetect.Visibility.NONE, getterVisibility=JsonAutoDetect.Visibility.NONE, isGetterVisibility=JsonAutoDetect.Visibility.NONE)
@JsonInclude(Include.NON_NULL)
class ReportVersion {
  /**
   * {@link Version}.
   */
  @XmlElement(name="version", type=String.class)
  @XmlJavaTypeAdapter(MapVersionXmlAdapter.class)
  @JsonProperty("version")
  @JsonSerialize(converter=VersionJsonConverter.class)
  public Version version;

  /**
   * Indicates that the {@link Version} is the most recent for the module within the
   * reference graph.
   * <p>
   * Boolean is used to allow using null to exclude from report.
   */
  @XmlElement(name="ind-most-recent-in-reference-graph")
  @JsonProperty("ind-most-recent-in-reference-graph")
  public Boolean indMostRecentInReferenceGraph;

  /**
   * Indicates that the {@link Version} is the most recent for the module in the
   * SCM. This Version is not necessarily present in the reference graph. It is
   * added to the report if this information was requested.
   * <p>
   * If true and Version occurs within the reference graph, then
   * indMostRecentInReferenceGraph is necessarily also true. Conversely, if true and
   * indMostRecentInReferenceGraph is true, than Version occurs within the reference
   * graph.
   * <p>
   * Boolean is used to allow using null to exclude from report.
   */
  @XmlElement(name="ind-most-recent-in-scm")
  @JsonProperty("ind-most-recent-in-scm")
  public Boolean indMostRecentInScm;

  /**
   * List of {@link ReferencePath} literals where this {@link Version} occurs.
   * <p>
   * If indMostRecentInScm is true, this may be null if the reference graph does not
   * include this Version. In that case, indMostRecentInReferenceGraph is false.
   */
  @XmlElementWrapper(name="reference-paths")
  @XmlElement(name="reference-path")
  @JsonProperty("reference-paths")
  public List<String> listReferencePathLiteral;

  /**
   * Writes a ReportVersion in the text format.
   *
   * @param writer Writer.
   */
  public void writeTextReport(Writer writer) {
    try {
      writer.append("  Version: ").append(this.version.toString()).append('\n');
      if (this.indMostRecentInReferenceGraph != null) {
        writer.append("    MostRecentInReferenceGraph: ").append(Boolean.toString(this.indMostRecentInReferenceGraph)).append('\n');
      }

      if (this.indMostRecentInScm != null) {
        writer.append("    MostRecentInScm: ").append(Boolean.toString(this.indMostRecentInScm)).append('\n');
      }

      if (this.listReferencePathLiteral != null) {
        writer.append("    ReferencePaths:\n");

        for (String referencePathLiteral: this.listReferencePathLiteral) {
          writer.append("      ").append(referencePathLiteral).append('\n');
        }
      }
    } catch (IOException ioe) {
      throw new RuntimeException(ioe);
    }
  }
}
