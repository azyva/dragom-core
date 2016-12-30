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
import javax.xml.bind.annotation.XmlElementRef;
import javax.xml.bind.annotation.adapters.XmlAdapter;

import org.azyva.dragom.model.config.impl.xml.MapXmlNodeConfigXmAdapter.ListNode;

/**
 * XmlAdapter for mapping the Map of XmlNodeConfig in
 * {@link XmlClassificationNodeConfig} to a List of XmlNodeConfig since JAXB does
 * not directly support Map's.
 *
 * @author David Raymond
 */
public class MapXmlNodeConfigXmAdapter extends XmlAdapter<ListNode, Map<String, XmlNodeConfig>> {
  @XmlAccessorType(XmlAccessType.NONE)
  public static class ListNode {
    @XmlElementRef
    private List<XmlNodeConfig> listXmlNodeConfig;

    /**
     * Default constructor required by JAXB.
     */
    public ListNode() {
    }

    /**
     * Constructor taking a List of XmlNodeConfig used for marshalling.
     *
     * @param listXmlNodeConfig List of XmlNodeConfig.
     */
    public ListNode(List<XmlNodeConfig> listXmlNodeConfig) {
      this.listXmlNodeConfig = listXmlNodeConfig;
    }

    /**
     * @return List of XmlNodeConfig.
     */
    public List<XmlNodeConfig> getListXmlNodeConfig() {
      return this.listXmlNodeConfig;
    }
  }

  /**
   * This method is not really useful for now since modification of
   * {@link XmlConfig} is not currently supported.
   */
  @Override
  public ListNode marshal(Map<String, XmlNodeConfig> mapNodeConfigXml) {
    if ((mapNodeConfigXml == null) || mapNodeConfigXml.isEmpty()) {
      return null;
    }

    return new ListNode(new ArrayList<XmlNodeConfig>(mapNodeConfigXml.values()));
  }

  @Override
  public Map<String, XmlNodeConfig> unmarshal(ListNode listNode) {
    Map<String, XmlNodeConfig> mapNodeConfigXml;

    // LinkedHashMap is used to preserve insertion order.
    mapNodeConfigXml = new LinkedHashMap<String, XmlNodeConfig>();

    // May be null when the XML file contains an empty containing element.
    if (listNode.getListXmlNodeConfig() == null) {
    	return mapNodeConfigXml;
    }

    for (XmlNodeConfig xmlNodeConfig: listNode.getListXmlNodeConfig()) {
      mapNodeConfigXml.put(xmlNodeConfig.getName(), xmlNodeConfig);
    }

    return mapNodeConfigXml;
  }
}
