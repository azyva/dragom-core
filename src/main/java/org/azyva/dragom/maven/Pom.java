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

package org.azyva.dragom.maven;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

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
 * - Anything before the root element of the document is not preserved. This
 *   includes the XML header ("XML declaration") and <doctype> declaration. This
 *   should not be a problem as POM files generally do not include the XML header
 *   nor a <doctype> declaration;
 * - Attribute order is not preserved;
 * - Whitespace between attribute declarations is not preserved;
 *
 * It is believed that these limitations will not cause major problems.
 *
 * A solution would be to use another parser such as VTD-XML. But such a parser is
 * not usable transparently through the JDK XML parser API.
 *
 * This class manages the following references within the POM file:
 *
 * - Parent
 * - DependencyManagement
 * - Dependencies
 *
 * References could exists elsewhere by using more advanced Maven features. These
 * are not supported.
 *
 * Referenced versions are not interpreted in any way. If a version reference uses
 * a property expression such as ${project.version}, the expression itself is
 * returned.
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
	 * Enumeration of the different types of referenced artifacts managed by this
	 * class.
	 */
	public static enum ReferencedArtifactTypeEnum {
		PARENT, // Parent.
		DEPENDENCY, // A dependency in the dependencies element.
		DEPENDENCY_MANAGEMENT // A dependency in the dependencyManagement element.
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
		private ReferencedArtifactTypeEnum referencedArtifactTypeEnum;

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
		 * @param referencedArtifactTypeEnum ReferenceArtifactTypeEnum.
		 * @param groupId GroupId.
		 * @param artifactId ArtifactId.
		 * @param version Version.
		 */
		public ReferencedArtifact(ReferencedArtifactTypeEnum referencedArtifactTypeEnum, String groupId, String artifactId, String version) {
			this.referencedArtifactTypeEnum = referencedArtifactTypeEnum;
			this.groupId = groupId;
			this.artifactId = artifactId;
			this.version = version;
		}

		/**
		 * @return ReferencedArtifactTypeEnum.
		 */
		public ReferencedArtifactTypeEnum getReferencedArtifactTypeEnum() {
			return this.referencedArtifactTypeEnum;
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
			return this.referencedArtifactTypeEnum + "/" + this.groupId + ":" + this.artifactId + ":" + this.version;
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result;

			result = 1;
			result = (prime * result) + ((this.artifactId == null) ? 0 : this.artifactId.hashCode());
			result = (prime * result) + ((this.groupId == null) ? 0 : this.groupId.hashCode());
			result = (prime * result) + ((this.referencedArtifactTypeEnum == null) ? 0 : this.referencedArtifactTypeEnum.hashCode());
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

			if (this.artifactId == null) {
				if (referencedArtifactOther.artifactId != null) {
					return false;
				}
			} else if (!this.artifactId.equals(referencedArtifactOther.artifactId)) {
				return false;
			}

			if (this.groupId == null) {
				if (referencedArtifactOther.groupId != null) {
					return false;
				}
			} else if (!this.groupId.equals(referencedArtifactOther.groupId)) {
				return false;
			}

			if (this.referencedArtifactTypeEnum != referencedArtifactOther.referencedArtifactTypeEnum) {
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

		public boolean equalsNoVersion(ReferencedArtifact referencedArtifactOther) {
			if (this == referencedArtifactOther) {
				return true;
			}

			if (referencedArtifactOther == null) {
				return false;
			}

			if (this.artifactId == null) {
				if (referencedArtifactOther.artifactId != null) {
					return false;
				}
			} else if (!this.artifactId.equals(referencedArtifactOther.artifactId)) {
				return false;
			}

			if (this.groupId == null) {
				if (referencedArtifactOther.groupId != null) {
					return false;
				}
			} else if (!this.groupId.equals(referencedArtifactOther.groupId)) {
				return false;
			}

			if (this.referencedArtifactTypeEnum != referencedArtifactOther.referencedArtifactTypeEnum) {
				return false;
			}

			return true;
		}
	}

	// Path to the POM file to be loaded or saved.
	private Path pathPom;

	// Document corresponding to the loaded POM file.
	private Document documentPom;

	// Path to the POM file that was last loaded. Used for error reporting. pathPom
	// may be changed after the POM is loaded but errors would relate to the original
	// file.
	private Path pathPomLoaded;

	/**
	 * Constructor.
	 */
	public Pom() {
	}

	/**
	 * @param Path to the POM.
	 */
	public void setPathPom(Path pathPom) {
		this.pathPom = pathPom;
	}

	/**
	 * Loads the POM file identified by the pathPom property.
	 */
	public void loadPom() {
		DocumentBuilderFactory documentBuilderFactory;
		DocumentBuilder documentBuilder;

		if (this.pathPom == null) {
			throw new RuntimeException("pathPom is null.");
		}

		documentBuilderFactory = DocumentBuilderFactory.newInstance();

		// We want the parser to preserve the original file. The default values for the
		// properties of the DocumentBuilderFactory are generally adequate for this,
		// except for those that are explicitly set below.
		documentBuilderFactory.setExpandEntityReferences(false);

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

		this.pathPomLoaded = this.pathPom;
	}

	/**
	 * Saves the loaded POM into the file identified by the pathPom property.
	 */
	public void savePom() {
		TransformerFactory transformerFactory;
		Transformer transformer;

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
			transformer.transform(new DOMSource(this.documentPom), new StreamResult(this.pathPom.toFile()));
		} catch (TransformerException te) {
			throw new RuntimeException(te);
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
	public String getResolvedGroupId() {
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
	public String getResolvedVersion() {
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
	 * @param enumSetReferencedArtifactTypeEnum EnumSet of the types of referenced
	 *   artifact to return. For example, if only the parent is needed, specify
	 *   EnumSet.of(ReferencedArtifactTypeEnum.PARENT). If all types of referenced
	 *   artifacts are needed, specify EnumSet.allOf(ReferencedArtifactTypeEnum.class).
	 * @param filterGroupId Restrict retrieved referenced artifacts to those matching
	 *   this groupId. If null, no filtering is applied on the groupId.
	 * @param filterArtifactId Restrict retrieved referenced artifacts to those
	 *   matching this artifactId. If null, no filtering is applied on the artifactId.
	 * @param filterVersion Restrict retrieved referenced artifacts to those matching
	 *   this version. If null, no filtering is applied on the version.
	 * @return List of ReferencedArtifact.
	 */
	public List<ReferencedArtifact> getListReferencedArtifact(
			EnumSet<ReferencedArtifactTypeEnum> enumSetReferencedArtifactTypeEnum,
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

		if (enumSetReferencedArtifactTypeEnum.contains(ReferencedArtifactTypeEnum.PARENT)) {
			try {
				nodeParent = (Node)xPath.evaluate("/project/parent", this.documentPom, XPathConstants.NODE);
			} catch (XPathExpressionException xpee) {
				throw new RuntimeException(xpee);
			}

			if (nodeParent != null) {
				try {
					referencedArtifact = new ReferencedArtifact(ReferencedArtifactTypeEnum.PARENT, xPath.evaluate("groupId", nodeParent), xPath.evaluate("artifactId", nodeParent), xPath.evaluate("version", nodeParent));
				} catch (XPathExpressionException xpee) {
					throw new RuntimeException(xpee);
				}

				if (Pom.referencedArtifactFiltered(referencedArtifact, filterGroupId, filterArtifactId, filterVersion)) {
					listReferencedArtifact.add(referencedArtifact);
				}

			}
		}

		if (enumSetReferencedArtifactTypeEnum.contains(ReferencedArtifactTypeEnum.DEPENDENCY)) {
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
				//The caller may have accessed to a database of parent references that could allow such retrievals.
				if (version == null) {
					continue;
				}


				try {
					referencedArtifact = new ReferencedArtifact(ReferencedArtifactTypeEnum.DEPENDENCY, xPath.evaluate("groupId", nodeDependency), xPath.evaluate("artifactId", nodeDependency), version);
				} catch (XPathExpressionException xpee) {
					throw new RuntimeException(xpee);
				}

				if (Pom.referencedArtifactFiltered(referencedArtifact, filterGroupId, filterArtifactId, filterVersion)) {
					listReferencedArtifact.add(referencedArtifact);
				}

			}
		}

		if (enumSetReferencedArtifactTypeEnum.contains(ReferencedArtifactTypeEnum.DEPENDENCY_MANAGEMENT)) {
			try {
				nodeListDependencies = (NodeList)xPath.evaluate("/project/dependencyManagement/dependency", this.documentPom, XPathConstants.NODESET);
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


				// The version of a dependency can be not specified. SUch dependencies are not
				// pertinent here.
				if (version == null) {
					continue;
				}

				try {
					referencedArtifact = new ReferencedArtifact(ReferencedArtifactTypeEnum.DEPENDENCY_MANAGEMENT, xPath.evaluate("groupId", nodeDependency), xPath.evaluate("artifactId", nodeDependency), version);
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

		List<ReferencedArtifact> listReferencedArtifact = this.getListReferencedArtifact(EnumSet.of(Pom.ReferencedArtifactTypeEnum.PARENT), null,  null,  null);

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

		switch (referencedArtifact.referencedArtifactTypeEnum) {
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
						  "/project/dependencyManagement/dependency[groupId='"
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
				throw new RuntimeException("The POM " + this.pathPomLoaded + " does not contain a dependencyManagement/dependency element which matches the specified GAV " + referencedArtifact.groupId + ":" + referencedArtifact.artifactId + ":" + referencedArtifact.version + " to modify.");
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

			listSubmodule.add(nodeSubmodule.getTextContent());
		}

		return listSubmodule;
	}

	public static void main(String[] args) {
		Pom pom;

		pom = new Pom();

		pom.setPathPom(Paths.get("C:/Projects/test.xml"));
		pom.loadPom();
		System.out.println("Version: " + pom.getVersion());
		List<ReferencedArtifact> list = pom.getListReferencedArtifact(EnumSet.allOf(ReferencedArtifactTypeEnum.class),  null,  null,  null);
		System.out.println("List: " + list);
		ReferencedArtifact referencedArtifact = new Pom.ReferencedArtifact(ReferencedArtifactTypeEnum.DEPENDENCY_MANAGEMENT, "org.azyva", "dependency-management", "1.2");
		pom.setReferencedArtifactVersion(referencedArtifact, "1.10");
		System.out.println("Submodules: " + pom.getListSubmodule());
		pom.savePom();
	}
}
