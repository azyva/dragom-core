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

package org.azyva.dragom.execcontext.plugin.impl;

import org.azyva.dragom.execcontext.ExecContext;
import org.azyva.dragom.execcontext.ToolLifeCycleExecContext;
import org.azyva.dragom.execcontext.plugin.RuntimePropertiesPlugin;
import org.azyva.dragom.execcontext.support.ExecContextHolder;
import org.azyva.dragom.model.Model;
import org.azyva.dragom.model.Node;
import org.azyva.dragom.model.NodePath;

/**
 * This default implementation of {@link RuntimePropertiesPlugin} considers the
 * following sources for the properties, in precedence order (the first one that
 * provides a requested property is used):
 * <ul>
 * <li>Transient data within the {@link ExecContext}
 *     ({@link ExecContext#getTransientData}) having the prefix "runtime-property"
 * <li>Tool properties ({@link ToolLifeCycleExecContext#getToolProperty}) having
 *     the prefix "runtime-property", if the ExecContext implements
 *     {@link ToolLifeCycleExecContext}
 * <li>ExecContext properties ({@link ExecContext#getProperty}) having the prefix
 *     "runtime-property"
 * <li>ExecContext initialization properties
 *     ({@link ExecContext#getInitProperty}) having the prefix "runtime-property"
 * <li>System properties (System.getProperty) having the prefix
 *     "org.azyva.dragom.runtime-property"
 * <li>{@link Node} properties within the {@link Model} (@link Node#getProperty}.
 * </ul>
 * The name of the property following the prefix is the the NodePath of the node
 * with "." separating the nodes and with the last token being the actual property
 * name. For example the following tool initialization property:
 * <p>
 * org.azyva.dragom.runtime-property.Domain1.app-a.MY_PROPERTY=my-value
 * <p>
 * defines a property MY_PROPERTY associated with the module Domain1/app-a whose
 * value is my-value.
 * <p>
 * Property inheritance is considered, meaning that a property defined for a
 * parent Node is used if the property is not defined specifically for the
 * specified Node. For all sources except the Model this is done by sequentially
 * removing Node's from the end of the NodePath. For the Model, this is
 * automatically done by the fact that the property is requested on the Node
 * specified by the Node.
 * <p>
 * A property being defined only for a specific Node is not explicitly supported,
 * also the Model may support this.
 * <p>
 * Properties are always set as transient data
 * ({@link ExecContext#setTransientData}).
 *
 * @author David Raymond
 */
public class DefaultRuntimePropertiesPluginImpl implements RuntimePropertiesPlugin {
  public DefaultRuntimePropertiesPluginImpl(ExecContext execContext) {
  }

  @Override
  public String getProperty(Node node, String name) {
    NodePath nodePath;
    String[] arrayNodeName;
    StringBuilder stringBuilder;
    String foundPropertyValue;
    String propertyValue;
    ExecContext execContext;

    execContext = ExecContextHolder.get();

    nodePath = (node == null) ? null : node.getNodePath();

    if (nodePath == null) {
      arrayNodeName = new String[0];
    } else {
      arrayNodeName = nodePath.getArrayNodeName();
    }

    stringBuilder = new StringBuilder("runtime-property.");
    foundPropertyValue = (String)execContext.getTransientData(stringBuilder.toString() + name);

    for (String nodeName: arrayNodeName) {
      stringBuilder.append(nodeName).append('.');

      propertyValue = (String)execContext.getTransientData(stringBuilder.toString() + name);

      if (propertyValue != null) {
        foundPropertyValue = propertyValue;
      }
    }

    if (foundPropertyValue != null) {
      return foundPropertyValue;
    }

    if (execContext instanceof ToolLifeCycleExecContext) {
      ToolLifeCycleExecContext toolLifeCycleExecContext;

      toolLifeCycleExecContext = (ToolLifeCycleExecContext)execContext;

      stringBuilder = new StringBuilder("runtime-property.");
      foundPropertyValue = toolLifeCycleExecContext.getToolProperty(stringBuilder.toString() + name);

      for (String nodeName: arrayNodeName) {
        stringBuilder.append(nodeName).append('.');

        propertyValue = toolLifeCycleExecContext.getToolProperty(stringBuilder.toString() + name);

        if (propertyValue != null) {
          foundPropertyValue = propertyValue;
        }
      }

      if (foundPropertyValue != null) {
        return foundPropertyValue;
      }
    }

    execContext = ExecContextHolder.get();

    stringBuilder = new StringBuilder("runtime-property.");
    foundPropertyValue = execContext.getProperty(stringBuilder.toString() + name);

    for (String nodeName: arrayNodeName) {
      stringBuilder.append(nodeName).append('.');

      propertyValue = execContext.getProperty(stringBuilder.toString() + name);

      if (propertyValue != null) {
        foundPropertyValue = propertyValue;
      }
    }

    if (foundPropertyValue != null) {
      return foundPropertyValue;
    }

    stringBuilder = new StringBuilder("runtime-property.");
    foundPropertyValue = execContext.getInitProperty(stringBuilder.toString() + name);

    for (String nodeName: arrayNodeName) {
      stringBuilder.append(nodeName).append('.');

      propertyValue = execContext.getInitProperty(stringBuilder.toString() + name);

      if (propertyValue != null) {
        foundPropertyValue = propertyValue;
      }
    }

    if (foundPropertyValue != null) {
      return foundPropertyValue;
    }

    stringBuilder = new StringBuilder("org.azyva.dragom.runtime-property.");
    foundPropertyValue = System.getProperty(stringBuilder.toString() + name);

    for (String nodeName: arrayNodeName) {
      stringBuilder.append(nodeName).append('.');

      propertyValue = System.getProperty(stringBuilder.toString() + name);

      if (propertyValue != null) {
        foundPropertyValue = propertyValue;
      }
    }

    if (foundPropertyValue != null) {
      return foundPropertyValue;
    }

    if (node != null) {
      return node.getProperty(name);
    } else {
      return execContext.getModel().getClassificationNodeRoot().getProperty(name);
    }
  }

  @Override
  public void setProperty(Node node, String name, String value) {
    NodePath nodePath;
    String[] arrayNodeName;
    StringBuilder stringBuilder;
    ExecContext execContext;

    execContext = ExecContextHolder.get();

    nodePath = (node == null) ? null : node.getNodePath();

    if (nodePath == null) {
      arrayNodeName = new String[0];
    } else {
      arrayNodeName = nodePath.getArrayNodeName();
    }

    stringBuilder = new StringBuilder("runtime-property.");

    for (String nodeName: arrayNodeName) {
      stringBuilder.append(nodeName).append('.');
    }

    execContext.setTransientData(stringBuilder.toString() + name, value);
  }
}