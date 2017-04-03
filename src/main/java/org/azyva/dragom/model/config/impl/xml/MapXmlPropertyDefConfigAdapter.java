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

package org.azyva.dragom.model.config.impl.xml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAnyElement;
import javax.xml.bind.annotation.adapters.XmlAdapter;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.azyva.dragom.model.config.impl.simple.SimplePropertyDefConfig;
import org.azyva.dragom.model.config.impl.xml.MapXmlPropertyDefConfigAdapter.ListProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * XmlAdapter for mapping the Map of XmlPropertyDefConfig in {@link XmlNodeConfig}
 * to a simple list parameters in the XML file since JAXB does not directly
 * support Map's, but mostly so that properties can be expressed with simple
 * parameters.
 * <p>
 * By "simple parameters" we mean something convenient like this:
 * <pre>
 * {@code
 * <A_PROPERTY>a_value</A_PROPERTY>
 * }
 * </pre>
 * instead of the more strict and verbose:
 * <pre>
 * {@code
 * <property>
 *   <name>A_PROPERTY</name>
 *   <value>a_value</value>
 * </property>
 * }
 * </pre>
 * or:
 * <pre>
 * {@code
 * <property name="A_PROPERTY" value="a_value"/>
 * }
 * </pre>
 * Note that this makes specifying the indOnlyThisChild property of the
 * XmlPropertyDefConfig more awkward, but nevertheless supported using an
 * attribute:
 * <pre>
 * {@code
 * <A_PROPERTY ind-only-this-node="true">a_value</A_PROPERTY>
 * }
 * </pre>
 *
 * @author David Raymond
 */
public class MapXmlPropertyDefConfigAdapter extends XmlAdapter<ListProperty, Map<String, SimplePropertyDefConfig>> {
  /**
   * Logger for the class.
   */
  private static final Logger logger = LoggerFactory.getLogger(MapXmlPropertyDefConfigAdapter.class);

  /**
   * We need to introduce a class for wrapping the List of org.w3c.dom.Node's,
   * otherwise JAXB does not know what to do with the List itself.
   */
  @XmlAccessorType(XmlAccessType.NONE)
  public static class ListProperty {
    /**
     * Because we want elements with variable names, it seems like the only sensible
     * solution is to work with DOM org.w3c.dom.Node's. Using JAXBElement<String>
     * instead of org.w3c.dom.Node offers a partial solution for reading, but not for
     * writing the properties with the ind-only-this-node attribute.
     */
    @XmlAnyElement
    private List<org.w3c.dom.Node> listProperty;

    /**
     * Default constructor required by JAXB.
     */
    public ListProperty() {
    }

    /**
     * Constructor taking a List of properties used when marshaling.
     *
     * @param listProperty List of properties.
     */
    public ListProperty(List<org.w3c.dom.Node> listProperty) {
      this.listProperty = listProperty;
    }

    /**
     * @return List of properties.
     */
    public List<org.w3c.dom.Node> getListProperty() {
      return this.listProperty;
    }
  }

  @Override
  public ListProperty marshal(Map<String, SimplePropertyDefConfig> mapPropertyDefConfigXml) {
    List<org.w3c.dom.Node> listProperty;
    DocumentBuilderFactory documentBuilderFactory;
    DocumentBuilder documentBuilder;
    Document document;

    if ((mapPropertyDefConfigXml == null) || mapPropertyDefConfigXml.isEmpty()) {
      return null;
    }

    listProperty = new ArrayList<org.w3c.dom.Node>();

    documentBuilderFactory = DocumentBuilderFactory.newInstance();

    try {
      documentBuilder = documentBuilderFactory.newDocumentBuilder();
    } catch (ParserConfigurationException pce) {
      throw new RuntimeException(pce);
    }

    document = documentBuilder.newDocument();

    for (SimplePropertyDefConfig xmlPropertyDefConfig: mapPropertyDefConfigXml.values()) {
      Element property;

      property = document.createElement(xmlPropertyDefConfig.getName());
      property.setTextContent(xmlPropertyDefConfig.getValue());

      if (xmlPropertyDefConfig.isOnlyThisNode()) {
        property.setAttribute("ind-only-this-node", "true");
      }

      listProperty.add(property);
    }

    return new ListProperty(listProperty);
  }

  @Override
  public Map<String, SimplePropertyDefConfig> unmarshal(ListProperty listProperty) {
    Map<String, SimplePropertyDefConfig> mapPropertyDefConfigXml;

    // LinkedHashMap is used to preserve insertion order.
    mapPropertyDefConfigXml = new LinkedHashMap<String, SimplePropertyDefConfig>();

    // May be null when the XML file contains an empty containing element.
    if (listProperty.getListProperty() == null) {
      return mapPropertyDefConfigXml;
    }

    for (org.w3c.dom.Node property: listProperty.getListProperty()) {
      try {
        org.w3c.dom.Node attributeIndOnlyThisNode;
        SimplePropertyDefConfig simplePropertyDefConfig;

        attributeIndOnlyThisNode = property.getAttributes().getNamedItem("ind-only-this-node");

        simplePropertyDefConfig = new SimplePropertyDefConfig(property.getLocalName(), property.getFirstChild().getTextContent(), (attributeIndOnlyThisNode == null) ? false : Boolean.parseBoolean(attributeIndOnlyThisNode.getTextContent()));

        if (mapPropertyDefConfigXml.containsKey(property.getLocalName())) {
          throw new RuntimeException("Duplicate property definition " + simplePropertyDefConfig + '.');
        }

        mapPropertyDefConfigXml.put(property.getLocalName(), simplePropertyDefConfig);
      } catch (Exception e) {
        // Unfortunately it seems like JAXB silently discards these exceptions. We at
        // least log them.
        MapXmlPropertyDefConfigAdapter.logger.error("An exception was thrown in an XmlAdapter and JAXB silently discards exceptions in this context.", e);

        if (e instanceof RuntimeException) {
          throw (RuntimeException)e;
        } else {
          throw new RuntimeException(e);
        }
      }
    }

    return mapPropertyDefConfigXml;
  }
}
