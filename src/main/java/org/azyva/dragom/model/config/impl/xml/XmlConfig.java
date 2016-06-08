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

import java.net.URL;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;

import org.azyva.dragom.model.config.ClassificationNodeConfig;
import org.azyva.dragom.model.config.Config;


/**
 * Implementation of {@link Config} that allows reading
 * from an XML file.
 *
 * @author David Raymond
 * @see org.azyva.dragom.model.config.xml
 */
@XmlAccessorType(XmlAccessType.NONE)
@XmlRootElement(name = "config")
public class XmlConfig implements Config {
	@XmlElement(name = "root-classification-node")
	XmlClassificationNodeConfig classificationNodeConfigXmlRoot;

	/**
	 * Loads a XmlConfig from a URL.
	 *
	 * @param urlXmlConfig URL.
	 * @return XmlConfig.
	 */
	public static XmlConfig load(URL urlXmlConfig) {
		JAXBContext jaxbContext;
		Unmarshaller unmarshaller;
		XmlConfig xmlConfig;

		try {
			// We include XmlModuleConfig, but not other classes since XmlModuleConfig is the
			// only one that is not explicitly referenced (directly or indirectly) by
			// XmlConfig.
			jaxbContext = JAXBContext.newInstance(XmlConfig.class, XmlModuleConfig.class);
			unmarshaller = jaxbContext.createUnmarshaller();
			xmlConfig = (XmlConfig)unmarshaller.unmarshal(urlXmlConfig);

			if (xmlConfig.getClassificationNodeConfigRoot() == null) {
				throw new RuntimeException("root-classification-node is not present in " + urlXmlConfig + '.');
			}

			return xmlConfig;
		} catch (JAXBException je) {
			throw new RuntimeException(je);
		}
	}

	@Override
	public ClassificationNodeConfig getClassificationNodeConfigRoot() {
		return this.classificationNodeConfigXmlRoot;
	}
}
