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

package org.azyva.dragom.model.config.impl.simple;

import org.azyva.dragom.model.Model;
import org.azyva.dragom.model.Node;
import org.azyva.dragom.model.config.NodeConfig;
import org.azyva.dragom.model.config.NodeConfigTransferObject;
import org.azyva.dragom.model.config.PropertyDefConfig;

/**
 * Simple implementation for {@link PropertyDefConfig}.
 * <p>
 * Can be used as a simple PropertyDefConfig within {@link Node},
 * {@link NodeConfig} and {@link NodeConfigTransferObject} implementations.
 * <p>
 * See org.azyva.dragom.model.config.impl.simple from dragom-core.
 *
 * @author David Raymond
 */
public class SimplePropertyDefConfig implements PropertyDefConfig {
  /**
   * Property name.
   */
  private String name;

  /**
   * Property value.
   */
  private String value;

  /**
   * Indicates that this {@link PropertyDefConfig} applies specifically to the
   * {@link NodeConfig} on which it is defined, as opposed to being inherited by
   * child NodeConfig when interpreted by the {@link Model}.
   */
  private boolean indOnlyThisNode;

  /**
   * Default constructor.
   *
   * <p>This is not meant to be used by the caller. It is required when this class is
   * used with frameworks which automatically create instances, such as JPA.
   */
  protected SimplePropertyDefConfig() {
  }

  /**
   * Constructor.
   *
   * @param name Name.
   * @param value Value.
   * @param indOnlyThisNode Indicates that this property applies specifically to the
   *   {@link NodeConfig} on which it is defined, as opposed to being inherited by
   *   child NodeConfig when interpreted by the {@link Model}.
   */
  public SimplePropertyDefConfig(String name, String value, boolean indOnlyThisNode) {
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
   * @return String to help recognize the
   *   {@link PropertyDefConfig} instance, in logs for example.
   */
  @Override
  public String toString() {
    return "SimplePropertyDefConfig [name=" + this.name + ", value=" + this.value + ", indOnlyThisNode=" + this.indOnlyThisNode + "]";
  }
}
