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

package org.azyva.dragom.model.config.impl.xml;

import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;

import org.azyva.dragom.model.config.Config;
import org.azyva.dragom.model.config.PluginDefConfig;
import org.azyva.dragom.model.plugin.NodePlugin;
import org.azyva.dragom.util.Util;

/**
 * Implementation for {@link PluginDefConfig} that allows reading from an XML
 * file.
 * <p>
 * This class is currently immutable and {@link XmlNodeConfig#getPluginDefConfig}
 * relies on this immutability to ensure {@link XmlConfig} and its members cannot
 * be modified in an uncontrolled manner. If modification of the {@link Config} is
 * eventually possible and if this class becomes mutable,
 * XmlNodeConfig.getPluginDefConfig will need to be revised.
 *
 * @author David Raymond
 * @see org.azyva.dragom.model.config.xml
 */
@XmlAccessorType(XmlAccessType.NONE)
@XmlRootElement(name = "plugin")
public class XmlPluginDefConfig implements PluginDefConfig {
	/**
	 * This is the String property coming from the XML file. The actual internal
	 * property is classNodePlugin/
	 */
	@XmlElement(name = "plugin-interface")
	private String stringClassNodePluginFromXml;

	/**
	 * Being a Class, this property is not directly mapped to an XML element in the
	 * XML file. The corresponding property stringClassNodePluginFromXml is.
	 */
	private Class<? extends NodePlugin> classNodePlugin;

	@XmlElement(name = "plugin-id")
	private String pluginIdFromXml;

	private String pluginId;

	@XmlElement(name = "plugin-class")
	private String pluginClass;

	@XmlElement(name = "ind-only-this-node")
	private boolean indOnlyThisNode;

	/**
	 * Sets the values of the classNodePlugin and pluginId properties.
	 * <p>
	 * The values can come from the stringClassNodePlugin and pluginIdFromXml if they
	 * are specified. If not the strategy described in the description of
	 * {@link PluginDefConfig} is implemented using
	 * {@link Util#getDefaultClassNodePlugin} and
	 * {@link Util#getDefaultPluginId}.
	 *
	 * @param unmarshaller Unmarshaller.
	 * @param parent Parent.
	 */
	@SuppressWarnings("unused")
	private void afterUnmarshal(Unmarshaller unmarshaller, Object parent) {
		if (this.stringClassNodePluginFromXml == null) {
			this.classNodePlugin = Util.getDefaultClassNodePlugin(this.pluginClass);
		} else {
			try {
				this.classNodePlugin = Class.forName(this.stringClassNodePluginFromXml).asSubclass(NodePlugin.class);
			} catch (ClassNotFoundException cnfe) {
				throw new RuntimeException(cnfe);
			}
		}

		if (this.pluginIdFromXml == null) {
			// pluginId can end up null here if no default plugin ID is defined for the
			// NodePlugin.
			// If the configuration needs to force a null pluginId even if the NodePlugin
			// defines a default plugin ID, an empty "plugin-id" element must be specified
			// in the XML file.
			// TODO: Need to check this. Are empty elements mapped to null or empty string.
			this.pluginId = Util.getDefaultPluginId(this.classNodePlugin, this.pluginClass);
		} else {
			this.pluginId = this.pluginIdFromXml;
		}
	}

	@Override
	public Class<? extends NodePlugin> getClassNodePlugin() {
		return this.classNodePlugin;
	}

	@Override
	public String getPluginId() {
		return this.pluginId;
	}

	@Override
	public String getPluginClass() {
		return this.pluginClass;
	}

	@Override
	public boolean isOnlyThisNode() {
		return this.indOnlyThisNode;
	}

	/**
	 * @return String to help recognize the {@link PluginDefConfig} instance, in
	 *   logs for example.
	 */
	@Override
	public String toString() {
		return "XmlPluginDefConfig [classNodePlugin=" + this.classNodePlugin.getName() + ", pluginId=" + this.pluginId + ", pluginClass=" + this.pluginClass + ", indOnlyThisNode=" + this.indOnlyThisNode + "]";
	}
}
