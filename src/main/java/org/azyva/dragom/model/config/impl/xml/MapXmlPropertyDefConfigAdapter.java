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

import javax.xml.bind.JAXBElement;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAnyElement;
import javax.xml.bind.annotation.adapters.XmlAdapter;
import javax.xml.namespace.QName;

import org.azyva.dragom.model.config.impl.xml.MapXmlPropertyDefConfigAdapter.ListProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * XmlAdapter for mapping the Map of XmlPropertyDefConfig in {@link XmlNodeConfig}
 * to a simple list parameters in the XML file since JAXB does not directly
 * support Map's, but mostly so that properties can be expressed with simple
 * parameters.
 * <p>
 * By "simple parameters" we mean something convenient like this:
 * <p>
 * <pre>{@code
 * <A_PROPERTY>a_value</A_PROPERTY>
 * }</pre>
 * <p>
 * instead of the more strict and verbose:
 * <p>
 * <pre>{@code
 * <property>
 *   <name>A_PROPERTY</name>
 *   <value>a_value</value>
 * </property>
 * }</pre>
 * <p>
 * or:
 * <p>
 * <pre>{@code
 * <property name="A_PROPERTY" value="a_value"/>
 * }</pre>
 * <p>
 * Note that this makes specifying the indOnlyThisChild property of the
 * XmlPropertyDefConfig more awkward, but nevertheless supported using an
 * attribute:
 * <p>
 * <pre>{@code
 * <A_PROPERTY ind-only-this-node="true">a_value</A_PROPERTY>
 * }</pre>
 *
 * @author David Raymond
 */
public class MapXmlPropertyDefConfigAdapter extends XmlAdapter<ListProperty, Map<String, XmlPropertyDefConfig>> {
	private static final Logger logger = LoggerFactory.getLogger(MapXmlPropertyDefConfigAdapter.class);

	/**
	 * We need to introduce a class for wrapping the List of JAXBElement's, else JAXB
	 * does not know what to do with the List itself.
	 */
	@XmlAccessorType(XmlAccessType.NONE)
	public static class ListProperty {
		/*
		 * The fact of having List<JAXBElement> is the optimal and most logical
		 * configuration. It works for marshal since JAXB knows how to marshal a
		 * JAXBElement. But for unmarshal, since the elements are variable, JAXB cannot
		 * map them to a known type and does not attempt to create JAXBElement's. In
		 * unmarshal we therefore have a List<Node>, despite the declaration.
		 */
		@XmlAnyElement
		private List<JAXBElement<String>> listProperty;

		/**
		 * Default constructor required by JAXB.
		 */
		public ListProperty() {
		}

		/**
		 * Constructor taking a List of properties used when marshalling.
		 *
		 * @param listProperty List of properties.
		 */
		public ListProperty(List<JAXBElement<String>> listProperty) {
			this.listProperty = listProperty;
		}

		/**
		 * @return List of properties.
		 */
		public List<JAXBElement<String>> getListProperty() {
			return this.listProperty;
		}
	}

	/**
	 * TODO:
	 * This method is not really useful for now since modification of
	 * {@link XmlConfig} is not currently supported.
	 * <p>
	 * In fact this method is incomplete as it does not support the indOnlyThisNode
	 * property which must be marshalled as an attribute of the property.
	 * <p>
	 * I believe the solution revolves around:
	 * <p>
	 * <li>Making the type of listProperty org.w3c.dom.Node instead of JAXBelement;<li>
	 * <li>Using DocumentBuilderFactory (.newInstance()) to build a Node representing
	 *     property with the correct attribute;</li>
	 */
	@Override
	public ListProperty marshal(Map<String, XmlPropertyDefConfig> mapPropertyDefConfigXml) {
		List<JAXBElement<String>> listProperty;

		if ((mapPropertyDefConfigXml == null) || mapPropertyDefConfigXml.isEmpty()) {
			return null;
		}

		listProperty = new ArrayList<JAXBElement<String>>();

		for (XmlPropertyDefConfig xmlPropertyDefConfig: mapPropertyDefConfigXml.values()) {
			listProperty.add(new JAXBElement<String>(new QName(xmlPropertyDefConfig.getName()), String.class, xmlPropertyDefConfig.getValue()));
		}

		return new ListProperty(listProperty);
	}

	@Override
	@SuppressWarnings("unchecked")
	public Map<String, XmlPropertyDefConfig> unmarshal(ListProperty listProperty) {
		Map<String, XmlPropertyDefConfig> mapPropertyDefConfigXml;


		// LinkedHashMap is used to preserve insertion order.
		mapPropertyDefConfigXml = new LinkedHashMap<String, XmlPropertyDefConfig>();

		// In unmarshal, the List is in fact a List<Node> and not a List<JAXBElement> as
		// declared. It seems we must live with this incoherence if elements with variable
		// names are to be supported.
		for (org.w3c.dom.Node property: (List<org.w3c.dom.Node>)(List<?>)listProperty.getListProperty()) {
			try {
				org.w3c.dom.Node attributeIndOnlyThisNode;
				XmlPropertyDefConfig xmlPropertyDefConfig;

				attributeIndOnlyThisNode = property.getAttributes().getNamedItem("ind-only-this-node");

				xmlPropertyDefConfig = new XmlPropertyDefConfig(property.getLocalName(), property.getFirstChild().getTextContent(), (attributeIndOnlyThisNode == null) ? false : Boolean.parseBoolean(attributeIndOnlyThisNode.getTextContent()));

				if (mapPropertyDefConfigXml.containsKey(property.getLocalName())) {
					throw new RuntimeException("Duplicate property definition " + xmlPropertyDefConfig + '.');
				}

				mapPropertyDefConfigXml.put(property.getLocalName(), xmlPropertyDefConfig);
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
