/*
 * Copyright 2015 AZYVA INC.
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

import java.util.ResourceBundle;

/**
 * RuntimeException used to report some error condition to the user.
 * <p>
 * Contrary to other RuntimeException, RuntimeExceptionUserError are not meant
 * to be unexpected and cause a stack trace to be logged. They are meant to allow
 * reusing the exception mechanism of the language to report an expected
 * condition.
 * <p>
 * It could be argued that in such a case a checked exception (not deriving from
 * RuntimeException) should be used. But since these conditions are still expected
 * to cause the tool to abort (after having displayed the message to the user), it
 * is more convenient to remain faithful to the unchecked exception strategy.
 *
 * @author David Raymond
 *
 */
public class RuntimeExceptionUserError extends RuntimeException {
	// To keep the compiler from complaining.
	static final long serialVersionUID = 0;

	/**
	 * Constructor.
	 * <p>
	 * The message passed to the constructor is used as is. The caller has to take
	 * care of localization, if required.
	 *
	 * @param message Message.
	 */
	public RuntimeExceptionUserError(String message) {
		super(message);
	}

	/**
	 * Constructor.
	 * <p>
	 * This constructor uses {@link Util.formatMessage} to format the message.
	 *
	 * @param resourceBundle ResourceBundle containing the MessageFormat pattern used
	 *   to format the message.
	 * @param patternKey Key of the MessageFormat pattern in the ResourceBundle.
	 * @param arrayArgument Array of arguments passed to MessageFormat.
	 */
	public RuntimeExceptionUserError(ResourceBundle resourceBundle, String patternKey, Object... arrayArgument) {
		super(Util.formatMessage(resourceBundle, patternKey, arrayArgument));
	}
}
