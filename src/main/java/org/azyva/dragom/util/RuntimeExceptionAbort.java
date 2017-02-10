/*
 * Copyright 2015 - 2017 AZYVA INC.
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


/**
 * RuntimeExceptionUserError which is handled in such a way as to abort the tool, with no
 * option to continue.
 *
 * @author David Raymond
 *
 */
public class RuntimeExceptionAbort extends RuntimeExceptionUserError {
  // To keep the compiler from complaining.
  static final long serialVersionUID = 0;

  /**
   * Constructor.
   *
   * @param message Message. The caller has to take care of localization, if required.
   */
  public RuntimeExceptionAbort(String message) {
    super(message);
  }
}
