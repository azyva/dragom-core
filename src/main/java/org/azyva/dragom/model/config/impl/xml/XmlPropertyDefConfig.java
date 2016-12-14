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

import org.azyva.dragom.model.Model;
import org.azyva.dragom.model.config.Config;
import org.azyva.dragom.model.config.NodeConfig;
import org.azyva.dragom.model.config.PropertyDefConfig;

/**
 * Implementation for {@link PropertyDefConfig} that allows reading from an XML
 * file.
 * <p>
 * This class is currently immutable and
 * {@link XmlNodeConfig#getPropertyDefConfig} relies on this immutability to
 * ensure {@link XmlConfig} and its members cannot be modified in an uncontrolled
 * manner. If modification of the {@link Config} is eventually possible and if
 * this class becomes mutable, XmlNodeConfig.getPropertyDefConfig will need to be
 * revised.
 * <p>
 * Although this class is used within a JAXB context, it does not need XML
 * annotations since properties are stored within the XML file in another format.
 * See {@link MapXmlPropertyDefConfigAdapter}.
 *
 * @author David Raymond
 * @see org.azyva.dragom.model.config.impl.xml
 */
public class XmlPropertyDefConfig implements PropertyDefConfig {
  String name;

  String value;

  boolean indOnlyThisNode;

  /**
   * Constructor.
   *
   * @param name Name.
   * @param value Value.
   * @param indOnlyThisNode Indicates that this property applies specifically to the
   *   {@link NodeConfig} on which it is defined, as opposed to being inherited by
   *   child NodeConfig when interpreted by the {@link Model}.
   */
  public XmlPropertyDefConfig(String name, String value, boolean indOnlyThisNode) {
    if ((name == null) || name.isEmpty()) {
      throw new RuntimeException("PropertyDef cannot have null or empty name.");
    }

    this.name = name;
    this.value = value;
    this.indOnlyThisNode = indOnlyThisNode;
  }

  @Override
  public String getName() {
    return this.name;
  }

  @Override
  public String getValue() {
    return this.value;
  }

  @Override
  public boolean isOnlyThisNode() {
    return this.indOnlyThisNode;
  }

  /**
   * @return String to help recognize the {@link PropertyDefConfig} instance, in
   *   logs for example.
   */
  @Override
  public String toString() {
    return "XmlPropertyDefConfig [name=" + this.name + ", value=" + this.value + ", indOnlyThisNode=" + this.indOnlyThisNode + "]";
  }
}
