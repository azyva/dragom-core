/*
 * Copyright 2015, 2016 AZYVA INC.
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
 */
public enum AlwaysNeverAskUserResponse {
	/**
	 * Indicates to silently assume a positive response.
	 */
	ALWAYS,

	/**
	 * Indicates to silently assume a negative response.
	 */
	NEVER,

	/**
	 * Indicates to ask the user.
	 */
	ASK;

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
	 * @return Indicats if ASK.
	 */
	public boolean isAsk() {
		return this == ASK;
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
			return AlwaysNeverAskUserResponse.ASK;
		} else {
			return AlwaysNeverAskUserResponse.valueOf(value);
		}
	}
};
