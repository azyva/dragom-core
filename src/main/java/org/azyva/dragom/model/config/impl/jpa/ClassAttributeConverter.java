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

package org.azyva.dragom.model.config.impl.jpa;

import javax.persistence.AttributeConverter;

import org.azyva.dragom.model.config.impl.simple.SimplePluginDefConfig;
import org.azyva.dragom.model.plugin.NodePlugin;

/**
 * JPA AttributeConverter to convert a class name to/from a class.
 *
 * <p>Used for the {@link NodePlugin} classes in {@link SimplePluginDefConfig}.
 *
 * @author David Raymond
 */
public class ClassAttributeConverter implements AttributeConverter<Class<?>, String> {
  @Override
  public String convertToDatabaseColumn(Class<?> clazz) {
    return clazz.getName();
  }

  @Override
  public Class<?> convertToEntityAttribute(String stringClass) {
    try {
      return Class.forName(stringClass);
    } catch (ClassNotFoundException cnfe) {
      throw new RuntimeException(cnfe);
    }
  }
}