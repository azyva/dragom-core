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
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.adapters.XmlAdapter;

import org.azyva.dragom.model.config.PluginKey;
import org.azyva.dragom.model.config.impl.xml.MapXmlPluginDefConfigAdapter.ListPluginDefConfigXml;
import org.azyva.dragom.model.plugin.NodePlugin;

/**
 * XmlAdapter for mapping the Map of XmlPluginDefConfig in {@link NodeConfiXml} to
 * List of PluginDefConfig since JAXB does not directly support Map's.
 *
 * @author David Raymond
 */
public class MapXmlPluginDefConfigAdapter extends	XmlAdapter<ListPluginDefConfigXml, Map<PluginKey, XmlPluginDefConfig>> {
	/**
	 * We need to introduce a class for wrapping the List of
	 * {@link XmlPluginDefConfig}'s, else JAXB does not know what to do with the List
	 * itself.
	 */
	@XmlAccessorType(XmlAccessType.NONE)
	public static class ListPluginDefConfigXml {
		@XmlElement(name = "plugin")
		private List<XmlPluginDefConfig> listPluginDefConfigXml;

		/**
		 * Default constructor required by JAXB.
		 */
		public ListPluginDefConfigXml() {
		}

		/**
		 * Constructor taking a List of XmlPluginDefConfig used when marshalling.
		 *
		 * @param listPluginDefConfigXml List of XmlPluginDefConfig.
		 */
		public ListPluginDefConfigXml(List<XmlPluginDefConfig> listPluginDefConfigXml) {
			this.listPluginDefConfigXml = listPluginDefConfigXml;
		}

		/**
		 * @return List of XmlPluginDefConfig.
		 */
		public List<XmlPluginDefConfig> getListPluginDefConfigXml() {
			return this.listPluginDefConfigXml;
		}
	}

	/**
	 * This method is not really useful for now since modification of
	 * {@link XmlConfig} is not currently supported.
	 */
	@Override
	public ListPluginDefConfigXml marshal(Map<PluginKey, XmlPluginDefConfig> mapPluginDefConfigXml) {
		if ((mapPluginDefConfigXml == null) || mapPluginDefConfigXml.isEmpty()) {
			return null;
		}

		return new ListPluginDefConfigXml(new ArrayList<XmlPluginDefConfig>(mapPluginDefConfigXml.values()));
	}

	@Override
	public Map<PluginKey, XmlPluginDefConfig> unmarshal(ListPluginDefConfigXml listPluginDefConfigXml) {
		Map<PluginKey, XmlPluginDefConfig> mapPluginDefConfigXml;

		// LinkedHashMap is used to preserve insertion order.
		mapPluginDefConfigXml = new LinkedHashMap<PluginKey, XmlPluginDefConfig>();

		for (XmlPluginDefConfig xmlPluginDefConfig: listPluginDefConfigXml.getListPluginDefConfigXml()) {
			PluginKey pluginKey;

			pluginKey = new PluginKey(xmlPluginDefConfig.getClassNodePlugin().asSubclass(NodePlugin.class), xmlPluginDefConfig.getPluginId());

			if (mapPluginDefConfigXml.containsKey(pluginKey)) {
				throw new RuntimeException("Duplicate pluging definition " + xmlPluginDefConfig + '.');
			}

			mapPluginDefConfigXml.put(pluginKey, xmlPluginDefConfig);
		}

		return mapPluginDefConfigXml;
	}
}
