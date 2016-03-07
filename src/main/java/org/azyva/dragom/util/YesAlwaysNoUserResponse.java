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
 * Enumerates the possible responses to a Yes/Yes always/No type of user input.
 */
public enum YesAlwaysNoUserResponse {
	/**
	 * Yes.
	 */
	YES,

	/**
	 * Yes and assume that response thereafter.
	 */
	YES_ALWAYS,

	/**
	 * No.
	 */
	NO;

	/**
	 * @return Indicates if YES.
	 */
	public boolean isYes() {
		return this == YES;
	}

	/**
	 * @return Indicates if YES_ALWAYS.
	 */
	public boolean isYesAlways() {
		return this == YES_ALWAYS;
	}

	/**
	 * @return Indicates if NO.
	 */
	public boolean isNo() {
		return this == NO;
	}
};
