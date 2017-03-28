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

package org.azyva.dragom.maven;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.azyva.dragom.util.RuntimeExceptionUserError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * This class allows reading and modifying the data in a POM file that are
 * pertinent for Dragom, namely the version information and referenced
 * artifacts versions (parent, dependencies and dependencyManagement elements).
 *
 * It is not meant to be a general purpose API for editing POM files.
 *
 * It works directly at the POM level. It does not attempt to infer an effective
 * POM.
 *
 * This class does its best to preserve the original POM file, modifying only what
 * needs to be modified. But given that it uses the DOM API provided in the JDK,
 * its has the following limitations in this regard:
 *
 * - Attribute order is not preserved;
 * - Whitespace between attribute declarations is not preserved;
 * - Formatting in any XML comment appearing before th root element is not
 *   preserved.
 *
 * It is believed that these limitations will not cause major problems.
 *
 * Generally using the DOM API (Transformer), any non-comment elements before the
 * root element of and anything after the close of the root element is not
 * preserved either. However this class does preserve bytes before and after the
 * root element or the first comment element with special code. This includes the
 * XML and DOCTYPE declarations, if any.
 *
 * A solution would be to use another parser such as VTD-XML. But such a parser is
 * not usable transparently through the JDK XML parser API.
 *
 * This class manages the following references within the POM file:
 *
 * - Parent
 * - Dependencies
 * - DependencyManagement
 * - Dependencies within profiles
 * - DependencyManagement within profiles
 *
 * References could exists elsewhere by using more advanced Maven features. These
 * are not supported.
 *
 * Accessors (such as {@link #getGroupId} do not evaluate property references,
 * which are returned as is (e.g.: ${my.module.group.id}). However, methods are
 * provided to help resolve properties when needed.
 *
 * Artifact versions are returned as simple String. During the design of this
 * class we considered to use the class ArtifactVersion. But we figured this was
 * at a higher level than this class.
 *
 * This class also allows listing submodules.
 *
 * @author David Raymond
 */
public class Pom {
  /**
   * Logger for the class.
   */
  private static final Logger logger = LoggerFactory.getLogger(Pom.class);

  /**
   *
   * Pattern used to resolve property references within arbitrary strings within
   * {@link Pom}'s.
   */
  private static final Pattern patternExtractPropertyReference = Pattern.compile("(.*?)\\$\\{(.+?)\\}(.*)");
  /**
   * Enumeration of the different types of referenced artifacts managed by this
   * class.
   */
  public static enum ReferencedArtifactType {
    /**
     * Parent.
     */
    PARENT,

    /**
     * A dependency in the dependencies element.
     */
    DEPENDENCY,

    /**
     * A dependency in the dependencyManagement/dependencies element.
     */
    DEPENDENCY_MANAGEMENT,

    /**
     * A dependency in the profiles/profile/dependencies element.
     */
    PROFILE_DEPENDENCY,

    /**
     * A dependency in the profiles/profile/dependencyManagement/dependencies element.
     */
    PROFILE_DEPENDENCY_MANAGEMENT
  }

  /**
   * Holds information about a referenced artifact.
   *
   * This class implements value semantics and is immutable.
   */
  public static class ReferencedArtifact {
    /**
     * Type of referenced artifact.
     */
    private ReferencedArtifactType referencedArtifactType;

    /**
     * Profile if {@link #referencedArtifactType} is
     * {@link ReferencedArtifactType#PROFILE_DEPENDENCY} or
     * {@link ReferencedArtifactType#PROFILE_DEPENDENCY_MANAGEMENT}.
     */
    private String profile;

    /**
     * GroupId.
     */
    private String groupId;

    /**
     * ArtifactId.
     */
    private String artifactId;

    /**
     * Version.
     */
    private String version;

    /**
     * Constructor.
     *
     * @param referencedArtifactType ReferenceArtifactType.
     * @param profile Profile. Null if referencedArtifactType is not
     *   {@link ReferencedArtifactType#PROFILE_DEPENDENCY} or
     *   {@link ReferencedArtifactType#PROFILE_DEPENDENCY_MANAGEMENT}.
     * @param groupId GroupId.
     * @param artifactId ArtifactId.
     * @param version Version.
     */
    public ReferencedArtifact(ReferencedArtifactType referencedArtifactType, String profile, String groupId, String artifactId, String version) {
      this.referencedArtifactType = referencedArtifactType;
      this.profile = profile;
      this.groupId = groupId;
      this.artifactId = artifactId;
      this.version = version;
    }

    /**
     * @return ReferencedArtifactType.
     */
    public ReferencedArtifactType getReferencedArtifactType() {
      return this.referencedArtifactType;
    }

    /**
     * @return Profile. null if {@link #getReferencedArtifactType} returns
     *   {@link ReferencedArtifactType#PROFILE_DEPENDENCY} or
     *   {@link ReferencedArtifactType#PROFILE_DEPENDENCY_MANAGEMENT}.
     */
    public String getProfile() {
      return this.profile;
    }

    /**
     * @return GroupId.
     */
    public String getGroupId() {
      return this.groupId;
    }

    /**
     * @return ArtifactId.
     */
    public String getArtifactId() {
      return this.artifactId;
    }

    /**
     * @return Version.
     */
    public String getVersion() {
      return this.version;
    }

    @Override
    public String toString() {
      StringBuilder stringBuilder;

      stringBuilder = new StringBuilder();

      stringBuilder.append(this.referencedArtifactType);

      if ((this.referencedArtifactType == ReferencedArtifactType.PROFILE_DEPENDENCY) || (this.referencedArtifactType == ReferencedArtifactType.PROFILE_DEPENDENCY_MANAGEMENT)) {
        stringBuilder.append('(').append(this.profile).append(')');
      }

      stringBuilder.append("/").append(this.groupId).append(':').append(this.artifactId).append(':').append(this.version);

      return stringBuilder.toString();
    }

    @Override
    public int hashCode() {
      final int prime = 31;
      int result;

      result = 1;
      result = (prime * result) + ((this.referencedArtifactType == null) ? 0 : this.referencedArtifactType.hashCode());
      result = (prime * result) + ((this.profile == null) ? 0 : this.profile.hashCode());
      result = (prime * result) + ((this.groupId == null) ? 0 : this.groupId.hashCode());
      result = (prime * result) + ((this.artifactId == null) ? 0 : this.artifactId.hashCode());
      result = (prime * result) + ((this.version == null) ? 0 : this.version.hashCode());

      return result;
    }

    @Override
    public boolean equals(Object other) {
      ReferencedArtifact referencedArtifactOther;

      if (this == other) {
        return true;
      }

      if (other == null) {
        return false;
      }

      if (!(other instanceof ReferencedArtifact)) {
        return false;
      }

      referencedArtifactOther = (ReferencedArtifact)other;

      if (this.referencedArtifactType != referencedArtifactOther.referencedArtifactType) {
        return false;
      }

      if (this.profile == null) {
        if (referencedArtifactOther.profile != null) {
          return false;
        }
      } else if (!this.profile.equals(referencedArtifactOther.profile)) {
        return false;
      }

      if (this.groupId == null) {
        if (referencedArtifactOther.groupId != null) {
          return false;
        }
      } else if (!this.groupId.equals(referencedArtifactOther.groupId)) {
        return false;
      }

      if (this.artifactId == null) {
        if (referencedArtifactOther.artifactId != null) {
          return false;
        }
      } else if (!this.artifactId.equals(referencedArtifactOther.artifactId)) {
        return false;
      }

      if (this.version == null) {
        if (referencedArtifactOther.version != null) {
          return false;
        }
      } else if (!this.version.equals(referencedArtifactOther.version)) {
        return false;
      }

      return true;
    }

    /**
     * Similar to {@link #equals}, but does not consider the version.
     *
     * @param referencedArtifactOther Other ReferencedArtifact.
     * @return Indicates if this ReferencedArtifact is equal to
     *   referencedArtifactOther.
     */
    public boolean equalsNoVersion(ReferencedArtifact referencedArtifactOther) {
      if (this == referencedArtifactOther) {
        return true;
      }

      if (referencedArtifactOther == null) {
        return false;
      }

      if (this.referencedArtifactType != referencedArtifactOther.referencedArtifactType) {
        return false;
      }

      if (this.profile == null) {
        if (referencedArtifactOther.profile != null) {
          return false;
        }
      } else if (!this.profile.equals(referencedArtifactOther.profile)) {
        return false;
      }

      if (this.groupId == null) {
        if (referencedArtifactOther.groupId != null) {
          return false;
        }
      } else if (!this.groupId.equals(referencedArtifactOther.groupId)) {
        return false;
      }

      if (this.artifactId == null) {
        if (referencedArtifactOther.artifactId != null) {
          return false;
        }
      } else if (!this.artifactId.equals(referencedArtifactOther.artifactId)) {
        return false;
      }

      return true;
    }
  }

  /**
   * Allows resolving external {@link Pom}'s.
   *
   * <p>Used for resolving property references within strings.
   */
  public interface PomResolver {
    /**
     * Resolves a Pom given a GAV.
     *
     * @param groupId GroupId.
     * @param artifactId ArtifactId.
     * @param version Version.
     * @return Pom.
     */
    Pom resolve(String groupId, String artifactId, String version);
  }

  /**
   * Path to the POM file to be loaded or saved.
   */
  private Path pathPom;

  /**
   * Document (XML) corresponding to the loaded POM file.
   */
  private Document documentPom;

  /**
   * Used to handle the reading of the XML header.
   *
   * <p>Special handling is required for the information before the body since it is
   * not preserved when writing back the DOM to a file.
   */
  enum BeforeAfterReadState {
    /**
     * Before any processing.
     */
    INIT,

    /**
     * We may have encountered the body (opening &lt;), but we are not sure it is the
     * body.
     */
    MAYBE_BODY,

    /**
     * We know we are in a XML header since character following &lt; is ? or !.
     */
    XML_HEADER,

    /**
     * We know we are in the body.
     */
    BODY
  }

  /**
   * Bytes appearing before the start of the XML document. This can include the XML
   * declaration ("<?...>"), DOCTYPE declaration (<!...>) and any whitespace before
   * the root element.
   */
  private String before;

  /**
   * Bytes appearing after the close of the root element. This includes whitespace
   * at the very end of the file.
   */
  private String after;

  /**
   * Path to the POM file that was last loaded. Used for error reporting. pathPom
   * may be changed after the POM is loaded but errors would relate to the original
   * file.
   */
  private Path pathPomLoaded;

  /**
   * Constructor.
   */
  public Pom() {
  }

  /**
   * @param pathPom Path to the POM.
   */
  public void setPathPom(Path pathPom) {
    this.pathPom = pathPom;
  }

  /**
   * @return Path to the POM.
   */
  public Path getPathPom() {
    return this.pathPom;
  }

  /**
   * Loads the POM file identified by the pathPom property.
   */
  public void loadPom() {
    DocumentBuilderFactory documentBuilderFactory;
    DocumentBuilder documentBuilder;
    StringBuilder stringBuilderBefore;
    StringBuilder stringBuilderAfter;
    InputStream inputStream;
    BeforeAfterReadState beforeAfterReadState;

    if (this.pathPom == null) {
      throw new RuntimeException("pathPom is null.");
    }

    documentBuilderFactory = DocumentBuilderFactory.newInstance();

    // We want the parser to preserve the original file. The default values for the
    // properties of the DocumentBuilderFactory are generally adequate for this,
    // except for those that are explicitly set below.
    documentBuilderFactory.setExpandEntityReferences(false);

    documentBuilderFactory.setValidating(false);

    try {
      documentBuilderFactory.setFeature("http://xml.org/sax/features/namespaces", false);
      documentBuilderFactory.setFeature("http://xml.org/sax/features/validation", false);
      documentBuilderFactory.setFeature("http://apache.org/xml/features/nonvalidating/load-dtd-grammar", false);
      documentBuilderFactory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    } catch (ParserConfigurationException pce) {
      throw new RuntimeException(pce);
    }

    try {
      documentBuilder = documentBuilderFactory.newDocumentBuilder();
    } catch (ParserConfigurationException pce) {
      throw new RuntimeException(pce);
    }

    try {
      this.documentPom = documentBuilder.parse(this.pathPom.toFile());
    } catch (SAXException | IOException e) {
      throw new RuntimeException(e);
    }

    stringBuilderBefore = new StringBuilder();
    stringBuilderAfter = new StringBuilder();

    // After parsing the XML file into a Document (and hence validating that it is
    // well-formed), we read it again to extract the bytes before and after the
    // root element since these are not preserved when writing back the DOM to a
    // file.
    try {
      inputStream = new FileInputStream(this.pathPom.toFile());
      beforeAfterReadState = BeforeAfterReadState.INIT;

      do {
        int aByte = inputStream.read();

        if (aByte == -1) {
          break;
        }

        switch (beforeAfterReadState) {
        case INIT:
          if (aByte != '<') {
            stringBuilderBefore.append((char)aByte);
          } else {
            beforeAfterReadState = BeforeAfterReadState.MAYBE_BODY;
          }
          break;
        case MAYBE_BODY:
          if (aByte == '?') {
            stringBuilderBefore.append("<?");
            beforeAfterReadState = BeforeAfterReadState.XML_HEADER;
          } else if (aByte == '!') {
            aByte = inputStream.read();

            if (aByte != '-') {
              stringBuilderBefore.append("<!").append((char)aByte);
              beforeAfterReadState = BeforeAfterReadState.XML_HEADER;
            } else {
              beforeAfterReadState = BeforeAfterReadState.BODY;
            }
          } else {
            beforeAfterReadState = BeforeAfterReadState.BODY;
          }
          break;
        case XML_HEADER:
          if (aByte != '<') {
            stringBuilderBefore.append((char)aByte);
          } else {
            beforeAfterReadState = BeforeAfterReadState.MAYBE_BODY;
          }
          break;
        case BODY:
          if (Character.isWhitespace((char)aByte)) {
            stringBuilderAfter.append((char)aByte);
          } else {
            stringBuilderAfter.setLength(0);
          }
          break;
        }
      } while (true);

      inputStream.close();
    } catch (IOException ioe) {
      throw new RuntimeException(ioe);
    }

    this.before = stringBuilderBefore.toString();
    this.after = stringBuilderAfter.toString();

    this.pathPomLoaded = this.pathPom;
  }

  /**
   * Saves the loaded POM into the file identified by the pathPom property.
   */
  public void savePom() {
    TransformerFactory transformerFactory;
    Transformer transformer;
    OutputStream outputStream;

    if (this.documentPom == null) {
      throw new RuntimeException("documentPom is null.");
    }

    if (this.pathPom == null) {
      throw new RuntimeException("pathPom is null.");
    }

    transformerFactory = TransformerFactory.newInstance();

    try {
      transformer = transformerFactory.newTransformer();
    } catch (TransformerConfigurationException tce) {
      throw new RuntimeException(tce);
    }

    transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");

    try {
      outputStream = new FileOutputStream(this.pathPom.toFile());
      outputStream.write(this.before.getBytes());
      transformer.transform(new DOMSource(this.documentPom), new StreamResult(outputStream));
      outputStream.write(this.after.getBytes());
      outputStream.close();
    } catch (TransformerException | IOException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * @return GroupId of the artifact. Can be null if the POM does not specify the
   *   groupId (if it inherits it from its parent).
   */
  public String getGroupId() {
    return this.getSimpleElement("project/groupId");
  }

  /**
   * @return GroupId of the artifact either from the groupId element or that of the
   *   parent if not provided. null can be returned if no groupId can be resolved
   *   (which is an error).
   */
  public String getEffectiveGroupId() {
    String groupId;

    groupId = this.getGroupId();

    if (groupId != null) {
      return groupId;
    }

    return this.getParentReferencedArtifact().groupId;
  }

  /**
   * @return ArtifactId of the artifact. Can be null if the POM does not specify an
   *   artifactId (which is an error).
   */
  public String getArtifactId() {
    return this.getSimpleElement("project/artifactId");
  }

  /**
   * @return Version of the artifact. Can be null if the POM does not specify the
   *   artifact version (if it inherits it from its parent).
   */
  public String getVersion() {
    XPathFactory xPathFactory;
    XPath xPath;
    String version;

    xPathFactory = XPathFactory.newInstance();
    xPath = xPathFactory.newXPath();

    try {
      version = xPath.evaluate("/project/version", this.documentPom);
    } catch (XPathExpressionException xpee) {
      throw new RuntimeException(xpee);
    }

    /* It looks like XPath.evaluate returns absent elements as empty strings.
     */
    if (version.isEmpty()) {
      return null;
    } else {
      return version;
    }
  }

  /**
   * Sets the version of the artifact.
   *
   * The version of the artifact must already be specified within the POM. It is not
   * allowed to set the artifact version for a POM that does not already set it.
   *
   * @param version See description.
   */
  public void setVersion(String version) {
    XPathFactory xPathFactory;
    XPath xPath;
    Node nodeVersion;

    xPathFactory = XPathFactory.newInstance();
    xPath = xPathFactory.newXPath();

    try {
      nodeVersion = (Node)xPath.evaluate("/project/version", this.documentPom, XPathConstants.NODE);
    } catch (XPathExpressionException xpee) {
      throw new RuntimeException(xpee);
    }

    if (nodeVersion == null) {
      throw new RuntimeException("The POM " + this.pathPomLoaded + " does not contain a version element.");
    }

    nodeVersion.setTextContent(version);
  }

  /**
   * @return Version of the artifact either from the version element or that of the
   *   parent if not provided. null can be returned if no version can be resolved
   *   (which is an error).
   */
  public String getEffectiveVersion() {
    String version;

    version = this.getVersion();

    if (version != null) {
      return version;
    }

    return this.getParentReferencedArtifact().version;
  }

  /**
   * Gets a simple element in the POM.
   *
   * This method factors the common part of other get methods in this
   * class.
   *
   * @param elementPath Path of the element within the POM.
   * @return See description. Can be null if the POM does not specify the element.
   */
  public String getSimpleElement(String elementPath) {
    XPathFactory xPathFactory;
    XPath xPath;
    String elementValue;

    xPathFactory = XPathFactory.newInstance();
    xPath = xPathFactory.newXPath();

    try {
      elementValue = xPath.evaluate(elementPath, this.documentPom);
    } catch (XPathExpressionException xpee) {
      throw new RuntimeException(xpee);
    }

    /* It looks like XPath.evaluate returns absent elements as empty strings.
     */
    if (elementValue.isEmpty()) {
      return null;
    } else {
      return elementValue;
    }
  }

  /**
   * Gets referenced artifacts.
   *
   * Only complete references are returned. If a dependency does not include the
   * version (because it is specified in a dependencyManagement element of some
   * parent, it is not returned because it is of no interest to the Dragom.
   *
   * @param enumSetReferencedArtifactType EnumSet of the types of referenced
   *   artifact to return. For example, if only the parent is needed, specify
   *   EnumSet.of(ReferencedArtifactType.PARENT). If all types of referenced
   *   artifacts are needed, specify EnumSet.allOf(ReferencedArtifactType.class).
   * @param filterGroupId Restrict retrieved referenced artifacts to those matching
   *   this groupId. If null, no filtering is applied on the groupId.
   * @param filterArtifactId Restrict retrieved referenced artifacts to those
   *   matching this artifactId. If null, no filtering is applied on the artifactId.
   * @param filterVersion Restrict retrieved referenced artifacts to those matching
   *   this version. If null, no filtering is applied on the version.
   * @return List of ReferencedArtifact.
   */
  public List<ReferencedArtifact> getListReferencedArtifact(
      EnumSet<ReferencedArtifactType> enumSetReferencedArtifactType,
      String filterGroupId,
      String filterArtifactId,
      String filterVersion) {
    List<ReferencedArtifact> listReferencedArtifact;
    XPathFactory xPathFactory;
    XPath xPath;
    Node nodeParent;
    NodeList nodeListDependencies;
    int length;
    String version;
    ReferencedArtifact referencedArtifact;

    listReferencedArtifact = new ArrayList<ReferencedArtifact>();
    xPathFactory = XPathFactory.newInstance();
    xPath = xPathFactory.newXPath();

    if (enumSetReferencedArtifactType.contains(ReferencedArtifactType.PARENT)) {
      try {
        nodeParent = (Node)xPath.evaluate("/project/parent", this.documentPom, XPathConstants.NODE);
      } catch (XPathExpressionException xpee) {
        throw new RuntimeException(xpee);
      }

      if (nodeParent != null) {
        try {
          referencedArtifact = new ReferencedArtifact(ReferencedArtifactType.PARENT, null, xPath.evaluate("groupId", nodeParent), xPath.evaluate("artifactId", nodeParent), xPath.evaluate("version", nodeParent));
        } catch (XPathExpressionException xpee) {
          throw new RuntimeException(xpee);
        }

        if (!this.isExcluded(nodeParent, referencedArtifact)) {
          if (Pom.referencedArtifactFiltered(referencedArtifact, filterGroupId, filterArtifactId, filterVersion)) {
            listReferencedArtifact.add(referencedArtifact);
          }
        }
      }
    }

    if (enumSetReferencedArtifactType.contains(ReferencedArtifactType.DEPENDENCY)) {
      try {
        nodeListDependencies = (NodeList)xPath.evaluate("/project/dependencies/dependency", this.documentPom, XPathConstants.NODESET);
      } catch (XPathExpressionException xpee) {
        throw new RuntimeException(xpee);
      }

      length = nodeListDependencies.getLength();

      for (int i = 0; i < length; i++) {
        Node nodeDependency;

        nodeDependency = nodeListDependencies.item(i);

        try {
          version = xPath.evaluate("version", nodeDependency);
        } catch (XPathExpressionException xpee) {
          throw new RuntimeException(xpee);
        }

        /* It looks like XPath.evaluate returns absent elements as empty strings.
         */
        if (version.isEmpty()) {
          version = null;
        }

        // The version of a dependency can be not specified. Such dependencies are not
        // pertinent here.
        //TODO: If we want to support references that are dependencies through parent-specified version,
        //maybe we should return these and let the caller decide what to do (null version).
        //The caller may have access to a database of parent references that could allow such retrievals.
        if (version == null) {
          continue;
        }

        try {
          referencedArtifact = new ReferencedArtifact(ReferencedArtifactType.DEPENDENCY, null, xPath.evaluate("groupId", nodeDependency), xPath.evaluate("artifactId", nodeDependency), version);
        } catch (XPathExpressionException xpee) {
          throw new RuntimeException(xpee);
        }

        if (!this.isExcluded(nodeDependency, referencedArtifact)) {
          if (Pom.referencedArtifactFiltered(referencedArtifact, filterGroupId, filterArtifactId, filterVersion)) {
            listReferencedArtifact.add(referencedArtifact);
          }
        }
      }
    }

    if (enumSetReferencedArtifactType.contains(ReferencedArtifactType.DEPENDENCY_MANAGEMENT)) {
      try {
        nodeListDependencies = (NodeList)xPath.evaluate("/project/dependencyManagement/dependencies/dependency", this.documentPom, XPathConstants.NODESET);
      } catch (XPathExpressionException xpee) {
        throw new RuntimeException(xpee);
      }

      length = nodeListDependencies.getLength();

      for (int i = 0; i < length; i++) {
        Node nodeDependency;

        nodeDependency = nodeListDependencies.item(i);

        try {
          version = xPath.evaluate("version", nodeDependency);
        } catch (XPathExpressionException xpee) {
          throw new RuntimeException(xpee);
        }

        // The version of a dependency can be not specified. Such dependencies are not
        // pertinent here.
        if (version == null) {
          continue;
        }

        try {
          referencedArtifact = new ReferencedArtifact(ReferencedArtifactType.DEPENDENCY_MANAGEMENT, null, xPath.evaluate("groupId", nodeDependency), xPath.evaluate("artifactId", nodeDependency), version);
        } catch (XPathExpressionException xpee) {
          throw new RuntimeException(xpee);
        }

        if (Pom.referencedArtifactFiltered(referencedArtifact, filterGroupId, filterArtifactId, filterVersion)) {
          listReferencedArtifact.add(referencedArtifact);
        }
      }
    }

    if (enumSetReferencedArtifactType.contains(ReferencedArtifactType.PROFILE_DEPENDENCY)) {
      try {
        nodeListDependencies = (NodeList)xPath.evaluate("/project/profiles/profile/dependencies/dependency", this.documentPom, XPathConstants.NODESET);
      } catch (XPathExpressionException xpee) {
        throw new RuntimeException(xpee);
      }

      length = nodeListDependencies.getLength();

      for (int i = 0; i < length; i++) {
        Node nodeProfileDependency;

        nodeProfileDependency = nodeListDependencies.item(i);

        try {
          version = xPath.evaluate("version", nodeProfileDependency);
        } catch (XPathExpressionException xpee) {
          throw new RuntimeException(xpee);
        }

        /* It looks like XPath.evaluate returns absent elements as empty strings.
         */
        if (version.isEmpty()) {
          version = null;
        }

        // The version of a dependency can be not specified. Such dependencies are not
        // pertinent here.
        //TODO: If we want to support references that are dependencies through parent-specified version,
        //maybe we should return these and let the caller decide what to do (null version).
        //The caller may have access to a database of parent references that could allow such retrievals.
        if (version == null) {
          continue;
        }

        try {
          referencedArtifact = new ReferencedArtifact(ReferencedArtifactType.PROFILE_DEPENDENCY, xPath.evaluate("id", nodeProfileDependency.getParentNode().getParentNode()), xPath.evaluate("groupId", nodeProfileDependency), xPath.evaluate("artifactId", nodeProfileDependency), version);
        } catch (XPathExpressionException xpee) {
          throw new RuntimeException(xpee);
        }

        if (!this.isExcluded(nodeProfileDependency, referencedArtifact)) {
          if (Pom.referencedArtifactFiltered(referencedArtifact, filterGroupId, filterArtifactId, filterVersion)) {
            listReferencedArtifact.add(referencedArtifact);
          }
        }
      }
    }

    if (enumSetReferencedArtifactType.contains(ReferencedArtifactType.PROFILE_DEPENDENCY_MANAGEMENT)) {
      try {
        nodeListDependencies = (NodeList)xPath.evaluate("/project/profiles/profile/dependencyManagement/dependencies/dependency", this.documentPom, XPathConstants.NODESET);
      } catch (XPathExpressionException xpee) {
        throw new RuntimeException(xpee);
      }

      length = nodeListDependencies.getLength();

      for (int i = 0; i < length; i++) {
        Node nodeProfileDependency;

        nodeProfileDependency = nodeListDependencies.item(i);

        try {
          version = xPath.evaluate("version", nodeProfileDependency);
        } catch (XPathExpressionException xpee) {
          throw new RuntimeException(xpee);
        }

        /* It looks like XPath.evaluate returns absent elements as empty strings.
         */
        if (version.isEmpty()) {
          version = null;
        }

        // The version of a dependency can be not specified. Such dependencies are not
        // pertinent here.
        if (version == null) {
          continue;
        }

        nodeProfileDependency.getParentNode().getParentNode().getParentNode();

        try {
          referencedArtifact = new ReferencedArtifact(ReferencedArtifactType.PROFILE_DEPENDENCY_MANAGEMENT, xPath.evaluate("id", nodeProfileDependency), xPath.evaluate("groupId", nodeProfileDependency), xPath.evaluate("artifactId", nodeProfileDependency), version);
        } catch (XPathExpressionException xpee) {
          throw new RuntimeException(xpee);
        }

        if (Pom.referencedArtifactFiltered(referencedArtifact, filterGroupId, filterArtifactId, filterVersion)) {
          listReferencedArtifact.add(referencedArtifact);
        }
      }
    }

    return listReferencedArtifact;
  }

  /**
   * Verifies if a ReferencedArtifact must be excluded.
   *
   * <p>Some ReferencedArtifact must always be exluded. Currently, this includes all
   * system scope references.
   *
   * @param node Node for the reference.
   * @param referencedArtifact ReferencedArtifact which was already built from the
   *   regular elements of the Node.
   * @return See description.
   */
  private boolean isExcluded(Node node, ReferencedArtifact referencedArtifact) {
    XPathFactory xPathFactory;
    XPath xPath;
    String scope;

    xPathFactory = XPathFactory.newInstance();
    xPath = xPathFactory.newXPath();
    try {
        scope = xPath.evaluate("scope",  node);
      } catch (XPathExpressionException xpee) {
        throw new RuntimeException(xpee);
      }

    if ((scope != null) && scope.equals("system")) {
      Pom.logger.warn("Within POM " + this.pathPom + " system scope ReferencedArtifact " + referencedArtifact + " was excluded.");
      return true;
    }

    return false;
  }

  /**
   * Internal method to verify if a referenced artifact is filtered by the specified
   * groupId, artifactId and version.
   *
   * Used by getListReferencedArtifact.
   *
   * @param referencedArtifact Referenced artifact.
   * @param filterGroupId GroupId to filter. If null, no filtering is applied on the
   *   groupId.
   * @param filterArtifactId ArtifactId to filter. If null, no filtering is applied
   *   on the artifactId.
   * @param filterVersion Version to filter. If null, no filtering is applied on the
   *   version.
   * @return Indicate if the referenced artifact is filtered.
   */
  private static boolean referencedArtifactFiltered(
      ReferencedArtifact referencedArtifact,
      String filterGroupId,
      String filterArtifactId,
      String filterVersion) {

    if (filterGroupId != null) {
      if (!referencedArtifact.groupId.equals(filterGroupId)) {
        return false;
      }
    }

    if (filterArtifactId != null) {
      if (!referencedArtifact.artifactId.equals(filterArtifactId)) {
        return false;
      }
    }

    if (filterVersion != null) {
      if (!referencedArtifact.version.equals(filterVersion)) {
        return false;
      }
    }

    return true;
  }

  /**
   * Gets the parent referenced artifact.
   *
   * This infomration can be obtained using getListReferencedArtifact, but since
   * there can be only one parent, a dedicated method is provided that is easier to
   * to use.
   *
   * @return Parent ReferencedArtifact.
   */
  public ReferencedArtifact getParentReferencedArtifact() {

    List<ReferencedArtifact> listReferencedArtifact = this.getListReferencedArtifact(EnumSet.of(Pom.ReferencedArtifactType.PARENT), null,  null,  null);

    if (listReferencedArtifact.isEmpty()) {
      return null;
    } else {
      return listReferencedArtifact.get(0);
    }
  }

  /**
   * Modifies the version of a referenced artifact.
   *
   * Note that following such a modification, referencedArtifact becomes effectively
   * invalid since the version has been modified and it does not match any
   * referenced artifact anymore.
   *
   * @param referencedArtifact Referenced artifact. Must generally have been
   *   returned by getListReferencedArtifact. There must be a matching referenced
   *   artifact in the POM.
   * @param version New version.
   */
  public void setReferencedArtifactVersion(ReferencedArtifact referencedArtifact, String version) {
    XPathFactory xPathFactory;
    XPath xPath;
    Node nodeVersion;

    xPathFactory = XPathFactory.newInstance();
    xPath = xPathFactory.newXPath();

    switch (referencedArtifact.referencedArtifactType) {
    case PARENT:
      try {
        nodeVersion = (Node)xPath.evaluate(
              "/project/parent[groupId='"
            + referencedArtifact.groupId
            + "' and artifactId='"
            + referencedArtifact.artifactId
            + "' and version='"
            + referencedArtifact.version
            + "']/version",
            this.documentPom,
            XPathConstants.NODE);
      } catch (XPathExpressionException xpee) {
        throw new RuntimeException(xpee);
      }

      if (nodeVersion == null) {
        throw new RuntimeException("The POM " + this.pathPomLoaded + " does not contain a parent element which matches the specified GAV " + referencedArtifact.groupId + ":" + referencedArtifact.artifactId + ":" + referencedArtifact.version + " to modify.");
      }

      nodeVersion.setTextContent(version);

      break;

    case DEPENDENCY:
      try {
        nodeVersion = (Node)xPath.evaluate(
              "/project/dependencies/dependency[groupId='"
            + referencedArtifact.groupId
            + "' and artifactId='"
            + referencedArtifact.artifactId
            + "' and version='"
            + referencedArtifact.version
            + "']/version",
            this.documentPom,
            XPathConstants.NODE);
      } catch (XPathExpressionException xpee) {
        throw new RuntimeException(xpee);
      }

      if (nodeVersion == null) {
        throw new RuntimeException("The POM " + this.pathPomLoaded + " does not contain a dependencies/dependency element which matches the specified GAV " + referencedArtifact.groupId + ":" + referencedArtifact.artifactId + ":" + referencedArtifact.version + " to modify.");
      }

      nodeVersion.setTextContent(version);

      break;

    case DEPENDENCY_MANAGEMENT:
      try {
        nodeVersion = (Node)xPath.evaluate(
              "/project/dependencyManagement/dependencies/dependency[groupId='"
            + referencedArtifact.groupId
            + "' and artifactId='"
            + referencedArtifact.artifactId
            + "' and version='"
            + referencedArtifact.version
            + "']/version",
            this.documentPom,
            XPathConstants.NODE);
      } catch (XPathExpressionException xpee) {
        throw new RuntimeException(xpee);
      }

      if (nodeVersion == null) {
        throw new RuntimeException("The POM " + this.pathPomLoaded + " does not contain a dependencyManagement/dependencies/dependency element which matches the specified GAV " + referencedArtifact.groupId + ":" + referencedArtifact.artifactId + ":" + referencedArtifact.version + " to modify.");
      }

      nodeVersion.setTextContent(version);

      break;

    case PROFILE_DEPENDENCY:
      try {
        nodeVersion = (Node)xPath.evaluate(
              "/project/profiles/profile[id='"
            + referencedArtifact.profile
            + "']/dependencies/dependency[groupId='"
            + referencedArtifact.groupId
            + "' and artifactId='"
            + referencedArtifact.artifactId
            + "' and version='"
            + referencedArtifact.version
            + "']/version",
            this.documentPom,
            XPathConstants.NODE);
      } catch (XPathExpressionException xpee) {
        throw new RuntimeException(xpee);
      }

      if (nodeVersion == null) {
        throw new RuntimeException("The POM " + this.pathPomLoaded + " does not contain a profiles/profile/dependencies/dependency element which matches the specified profile " + referencedArtifact.profile + " and GAV " + referencedArtifact.groupId + ":" + referencedArtifact.artifactId + ":" + referencedArtifact.version + " to modify.");
      }

      nodeVersion.setTextContent(version);

      break;

    case PROFILE_DEPENDENCY_MANAGEMENT:
      try {
        nodeVersion = (Node)xPath.evaluate(
              "/project/profiles/profile[id='"
            + referencedArtifact.profile
            + "]/dependencyManagement/dependencies/dependency[groupId='"
            + referencedArtifact.groupId
            + "' and artifactId='"
            + referencedArtifact.artifactId
            + "' and version='"
            + referencedArtifact.version
            + "']/version",
            this.documentPom,
            XPathConstants.NODE);
      } catch (XPathExpressionException xpee) {
        throw new RuntimeException(xpee);
      }

      if (nodeVersion == null) {
        throw new RuntimeException("The POM " + this.pathPomLoaded + " does not contain a profiles/profile/dependencyManagement/dependencies/dependency element which matches the specified profile " + referencedArtifact.profile + " and GAV " + referencedArtifact.groupId + ":" + referencedArtifact.artifactId + ":" + referencedArtifact.version + " to modify.");
      }

      nodeVersion.setTextContent(version);

      break;
    }

  }

  /**
   * Gets the list of submodules.
   *
   * The submodules are identified as in the POM, meaning that they actually are
   * relative directory paths from the directory of the POM.
   *
   * Presumably the POM has the packaging type "pom", but this is not enforced.
   *
   * @return See description.
   */
  public List<String> getListSubmodule() {
    XPathFactory xPathFactory;
    XPath xPath;
    NodeList nodeListSubmodules;
    int length;
    List<String> listSubmodule;

    xPathFactory = XPathFactory.newInstance();
    xPath = xPathFactory.newXPath();

    try {
      nodeListSubmodules = (NodeList)xPath.evaluate("/project/modules/module", this.documentPom, XPathConstants.NODESET);
    } catch (XPathExpressionException xpee) {
      throw new RuntimeException(xpee);
    }

    length = nodeListSubmodules.getLength();
    listSubmodule = new ArrayList<String>();

    for (int i = 0; i < length; i++) {
      Node nodeSubmodule;

      nodeSubmodule = nodeListSubmodules.item(i);

      // Maven seems to allow "\" in module references. But this causes problems on *nix
      // systems.
      listSubmodule.add(nodeSubmodule.getTextContent().replace('\\', '/'));
    }

    return listSubmodule;
  }

  // If pomResolver is null, different mode. null is returned if properties cannot be resolved locally.
  /**
   * Resolves property references (${...}) within a string.
   *
   * <p>If pomResolver is not null, it is expected that all properties can be
   * resolved. If a pom cannot be resolved, ResolveException is thrown. If we
   * successfully get to the last parent in the hierarchy and a property can
   * still not be resovled, a RuntimeException is thrown.
   *
   * <p>If pomResolver is null, this indicates we expect only local property
   * references to be encountered. If property references cannot be resolved, null
   * is returned for the whole string that contains the property references (not
   * only for the property itself). ResolveException is not thrown in this case.
   *
   * @param string String.
   * @param pomResolver PomResolver to resolve parent Pom's. Can be null.
   * @return New string with property references resolved.
   */
  public String resolveProperties(String string, PomResolver pomResolver) {
    Matcher matcher;

    do {
      String value;

      matcher = Pom.patternExtractPropertyReference.matcher(string);

      if (!matcher.matches()) {
        return string;
      }

      try {
        value = this.evaluateProperty(matcher.group(2), pomResolver);
      } catch (RuntimeExceptionUserError reue) {
        throw reue;
      } catch (RuntimeException re) {
        throw new RuntimeException("Could not evaluate property " + matcher.group(2) + " while resolving string " + string + " within artifact " + this.getEffectiveGroupId() + ':' + this.getArtifactId() + ':' + this.getEffectiveVersion() + " (" + this.getPathPom() + ").", re);
      }

      if ((value == null) && (pomResolver == null)) {
        return null;
      }

      // value is never expected to be null if pomResolver is not null.

      string = string.substring(0, matcher.end(1)) + value + string.substring(matcher.start(3));
    } while (true);
  }

  /**
   * Evaluates a property.
   *
   * <p>This is similar to resolveProperties. But here, a property name is provided,
   * not a string that contains (one or many) property references.
   *
   * <p>If a property cannot be evaluated locally and pomResolver is null, null is
   * returned and no ResolveException is thrown. If pomResolver is not null, the
   * resolved Pom is used to evaluate the property and therefore a ResolveException
   * can be thrown.
   *
   * @param property Property.
   * @param pomResolver PomResolver to resolve parant Pom's. Can be null.
   * @return See description.
   */
  public String evaluateProperty(String property, PomResolver pomResolver) {
    String value;

    if (   property.equals("project.version")
        || property.equals("pom.version")
        || property.equals("version")) {

      return this.getEffectiveVersion();
    }

    if (   property.equals("project.groupId")
        || property.equals("pom.groupId")
        || property.equals("groupId")) {

      return this.getEffectiveGroupId();
    }

    if (   property.equals("project.artifactId")
        || property.equals("pom.artifactId")
        || property.equals("artifactId")) {

      return this.getArtifactId();
    }

    if (   property.equals("project.parent.version")
        || property.equals("pom.parent.version")
        || property.equals("parent.version")) {

      return this.getParentReferencedArtifact().version;
    }

    if (   property.equals("project.parent.groupId")
        || property.equals("pom.parent.groupId")
        || property.equals("parent.groupId")) {

      return this.getParentReferencedArtifact().groupId;
    }

    if (   property.equals("project.parent.artifactId")
        || property.equals("pom.parent.artifactId")
        || property.equals("parent.artifactId")) {

      return this.getParentReferencedArtifact().artifactId;
    }

    value = this.getProperty(property);

    if (value != null) {
      return this.resolveProperties(value, pomResolver);
    }

    if ((value == null) && (pomResolver != null)) {
      ReferencedArtifact referenceArtifactParent;
      Pom pom;

      referenceArtifactParent = this.getParentReferencedArtifact();

      if (referenceArtifactParent == null) {
        throw new RuntimeException("Referenced property " + property + " not defined in " + this.pathPom + " and no parent POM is specified.");
      }

      try {
        pom = pomResolver.resolve(referenceArtifactParent.groupId, referenceArtifactParent.artifactId, referenceArtifactParent.version);
      } catch (RuntimeExceptionUserError reue) {
        throw reue;
      } catch (RuntimeException re) {
        throw new RuntimeException("POM could not be resolved for parent artifact " + referenceArtifactParent.groupId + ':' + referenceArtifactParent.artifactId + ':' + referenceArtifactParent.version + " while evaluating property " + property + " within artifact " + this.getEffectiveGroupId() + ':' + this.getArtifactId() + ':' + this.getEffectiveVersion() + " (" + this.getPathPom() + ").", re);
      }

      try {
        return pom.evaluateProperty(property, pomResolver);
      } catch (RuntimeExceptionUserError reue) {
        throw reue;
      } catch (RuntimeException re) {
        throw new RuntimeException("Could not evaluate property " + property + " within parent artifact following it missing in the context of artifact " + this.getEffectiveGroupId() + ':' + this.getArtifactId() + ':' + this.getEffectiveVersion() + " (" + this.getPathPom() + ").", re);
      }
    }

    return null;
  }

  /**
   * Returns the specified property defined in this Pom as is, with no attempt to
   * resolve property references within the value of the property.
   *
   * @param property Property.
   * @return See description.
   */
  public String getProperty(String property) {
    XPathFactory xPathFactory;
    XPath xPath;
    NodeList nodeListProperties;
    int length;

    xPathFactory = XPathFactory.newInstance();
    xPath = xPathFactory.newXPath();

    try {
      nodeListProperties = (NodeList)xPath.evaluate("/project/properties/*", this.documentPom, XPathConstants.NODESET);
    } catch (XPathExpressionException xpee) {
      throw new RuntimeException(xpee);
    }

    length = nodeListProperties.getLength();

    for (int i = 0; i < length; i++) {
      Node nodeProperty;

      nodeProperty = nodeListProperties.item(i);

      if (nodeProperty.getNodeName().equals(property)) {
//???? check if can be empty, null????
        return nodeProperty.getTextContent();
      }
    }

    return null;
  }

  /*
  public static void main(String[] args) {
    Pom pom;

    pom = new Pom();

    pom.setPathPom(Paths.get("C:/Projects/test.xml"));
    pom.loadPom();
    System.out.println("Version: " + pom.getVersion());
    List<ReferencedArtifact> list = pom.getListReferencedArtifact(EnumSet.allOf(ReferencedArtifactType.class),  null,  null,  null);
    System.out.println("List: " + list);
    ReferencedArtifact referencedArtifact = new Pom.ReferencedArtifact(ReferencedArtifactType.DEPENDENCY_MANAGEMENT, "org.azyva", "dependency-management", "1.2");
    pom.setReferencedArtifactVersion(referencedArtifact, "1.10");
    System.out.println("Submodules: " + pom.getListSubmodule());
    pom.savePom();
  }
  */
}
