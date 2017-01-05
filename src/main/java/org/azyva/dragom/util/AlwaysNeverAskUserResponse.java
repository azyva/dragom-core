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
 * Enumerates the possible responses to a Always/Never/Ask type of user input.
 *
 * <p>Generally also persisted to introduce an automatic response to a subsequent
 * similar user input.
 */
public enum AlwaysNeverAskUserResponse {
  /**
   * When used as a user input, indicates a positive response including to
   * subsequent similar user input.
   *
   * <p>When persisted, indicates to silently assume a positive response.
   */
  ALWAYS,

  /**
   * When used as a user input, indicates a negative response including to
   * subsequent similar user input.
   *
   * <p>When persisted, indicates to silently assume a negative response.
   */
  NEVER,

  /**
   * When used as a user input, indicates a positive response, but to ask again for
   * subsequent similar user input.
   *
   * <p>When persisted, indicates to ask the user with positive response by default.
   */
  YES_ASK;

  /**
   * @return Indicates if ALWAYS.
   */
  public boolean isAlways() {
    return this == ALWAYS;
  }

  /**
   * @return Indicates if NEVER.
   */
  public boolean isNever() {
    return this == NEVER;
  }

  /**
   * @return Indicates if ASK.
   */
  public boolean isAsk() {
    return this == YES_ASK;
  }

  /**
   * @return Indicates if ALWAYS or YES_ASK.
   */
  public boolean isYes() {
    return (this == ALWAYS) || (this == YES_ASK);
  }

  /**
   * Facilitates parsing a String representation of this enum when the String can be
   * null.
   *
   * @param value Value. Can be null.
   * @return AlwaysNeverAskUserResponse. ASK if value is null.
   */
  public static AlwaysNeverAskUserResponse valueOfWithAskDefault(String value) {
    if (value == null) {
      return AlwaysNeverAskUserResponse.YES_ASK;
    } else {
      return AlwaysNeverAskUserResponse.valueOf(value);
    }
  }
};
