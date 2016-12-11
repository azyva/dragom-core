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

import java.io.OutputStream;
import java.net.URL;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;

import org.azyva.dragom.model.config.ClassificationNodeConfig;
import org.azyva.dragom.model.config.Config;
import org.azyva.dragom.model.config.MutableClassificationNodeConfig;
import org.azyva.dragom.model.config.MutableConfig;


/**
 * Implementation of {@link Config} that allows reading
 * from an XML file.
 *
 * @author David Raymond
 * @see org.azyva.dragom.model.config.impl.xml
 */
@XmlAccessorType(XmlAccessType.NONE)
@XmlRootElement(name = "config")
public class XmlConfig implements Config, MutableConfig {
  @XmlElement(name = "root-classification-node")
  XmlClassificationNodeConfig xmlClassificationNodeConfigRoot;

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

  /**
   * Saves this XmlConfig to an OutputStream.
   *
   * @param outputStreamXmlConfig OutputStream.
   */
  public void save(OutputStream outputStreamXmlConfig) {
    JAXBContext jaxbContext;
    Marshaller marshaller;

    try {
      // We include XmlModuleConfig, but not other classes since XmlModuleConfig is the
      // only one that is not explicitly referenced (directly or indirectly) by
      // XmlConfig.
      jaxbContext = JAXBContext.newInstance(XmlConfig.class, XmlModuleConfig.class);
      marshaller = jaxbContext.createMarshaller();
      marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
      marshaller.marshal(this, outputStreamXmlConfig);
    } catch (JAXBException je) {
      throw new RuntimeException(je);
    }
  }

  @Override
  public ClassificationNodeConfig getClassificationNodeConfigRoot() {
    return this.xmlClassificationNodeConfigRoot;
  }

  /**
   * Sets the root {@link XmlClassificationNodeConfig}.
   * <p>
   * This method is intended to be called by
   * {@link XmlNodeConfig#setNodeConfigTransferObject}.
   *
   * @param xmlClassificationNodeConfigRoot Root XmlClassificationNodeConfig.
   */
  void setXmlClassificationNodeConfigRoot(XmlClassificationNodeConfig xmlClassificationNodeConfigRoot) {
    if (this.xmlClassificationNodeConfigRoot != null && xmlClassificationNodeConfigRoot != null) {
      throw new RuntimeException("Replacing the root XmlClassificationNodeConfig is not allowed.");
    }

    // Setting this.simplClassificationNodeRoot to null is allowed since this
    // can happen when deleting the root XmlClassificationNode.
    this.xmlClassificationNodeConfigRoot = xmlClassificationNodeConfigRoot;
  }

  @Override
  public MutableClassificationNodeConfig createMutableClassificationNodeConfigRoot() {
    return new XmlClassificationNodeConfig(this);
  }
}
