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
 * Enumerates the possible responses to a Always/Never/Yes/No/Ask type of user
 * input.
 * <p>
 * More precisely, this is a 4-state input:
 * <p>
 * <li>Yes, always (do not ask again)</li>
 * <li>No, never (do not ask again)</li>
 * <li>Yes (but ask again)</li>
 * <li>No (but ask again)</li>
 */
public enum AlwaysNeverYesNoAskUserResponse {
	/**
	 * Indicates to silently assume a positive response.
	 */
	ALWAYS,

	/**
	 * Indicates to silently assume a negative response.
	 */
	NEVER,

	/**
	 * Indicates to ask the user, but with a positive default response.
	 */
	YES_ASK,

	/**
	 * Indicates to ask the user, but with a negative default response.
	 */
	NO_ASK;

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
	 * @return Indicates if YES_ASK.
	 */
	public boolean isYesAsk() {
		return this == YES_ASK;
	}

	/**
	 * @return Indicates if NO_ASK.
	 */
	public boolean isNoAsk() {
		return this == NO_ASK;
	}

	/**
	 * @return Indicates if ALWAYS or YES_ASK.
	 */
	public boolean isYes() {
		return (this == ALWAYS) || (this == YES_ASK);
	}

	/**
	 * @return Indicates if NEVER or NO_ASK.
	 */
	public boolean isNo() {
		return (this == NEVER) || (this == NO_ASK);
	}

	/**
	 * @return Indicates if YES_ASK or NO_ASK.
	 */
	public boolean isAsk() {
		return (this == YES_ASK) || (this == NO_ASK);
	}

	/**
	 * Facilitates parsing a String representation of this enum when the String can be
	 * null.
	 *
	 * @param value Value. Can be null.
	 * @return AlwaysNeverYesNoAskUserResponse. YES_ASK if value is null.
	 */
	public static AlwaysNeverYesNoAskUserResponse valueOfWithYesAskDefault(String value) {
		if (value == null) {
			return AlwaysNeverYesNoAskUserResponse.YES_ASK;
		} else {
			return AlwaysNeverYesNoAskUserResponse.valueOf(value);
		}
	}
};
