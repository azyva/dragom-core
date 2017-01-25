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

package org.azyva.dragom.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Properties;

/**
 * The standard Properties class does not define the order in which the properties
 * are written to the file by the store method.
 *
 * <p>This subclass implements the known trick of overriding the keys method to
 * sort the properties.
 *
 * @author David Raymond
 */
public class SortedProperties extends Properties {
  /**
   * To keep the compiler happy.
   */
  private static final long serialVersionUID = 0;

  @Override
  public Enumeration<Object> keys() {
    ArrayList<Object> listKeys;

    listKeys = Collections.list(super.keys());
    Collections.sort(listKeys, (a, b) -> a.toString().compareTo(b.toString()));;
    return Collections.enumeration(listKeys);
  }
}
