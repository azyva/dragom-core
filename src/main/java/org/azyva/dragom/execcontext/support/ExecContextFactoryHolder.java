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

package org.azyva.dragom.execcontext.support;

import org.azyva.dragom.execcontext.ExecContextFactory;
import org.azyva.dragom.util.Util;


/**
 * Holds and provides access to an {@link ExecContextFactory}.
 * <p>
 * Allows generic tools to be provided by Dragom or other third party that can be
 * integrated as a client tool by providing a way for client tools to easily
 * provide the ExecContextFactory to use. Tools provided by Dragom make use of
 * this strategy.
 * <p>
 * Dragom attempts to remain as independent as possible from external frameworks
 * whose use would have impacts on clients, such as Spring. But at the same time
 * Dragom must not prevent the use of such frameworks. This class allows, but
 * does not force, the ExecContextFactory to be set using a dependency injection
 * framework such as Spring.
 * <p>
 * ExecContextFactoryHolder can also be used by tools without using a dependency
 * injection framework. The ExecContextFactory can simply be set by bootstrapping
 * code before invoking the tool.
 * <p>
 * The ExecContextFactory is simply held in a static variable.
 * <p>
 * As a convenience, if no ExecContextFactory is set, the class identified by the
 * org.azyva.dragom.DefaultExecContextFactory system property is used or
 * {@link DefaultExecContextFactory} if not specified.
 * <p>
 * {@link Util#setDragomSystemProperties} is called during initialization of this
 * class.
 *
 * @author David Raymond
 */
public class ExecContextFactoryHolder {
	/**
	 * System property specifying the name of the default {@link ExecContextFactory}
	 * implementation class to use.
	 */
	private static final String SYS_PROP_DEFAULT_EXEC_CONTEXT_FACTORY = "org.azyva.dragom.DefaultExecContextFactory";

	private static ExecContextFactory execContextFactory;

	// Ensures that the Dragom properties are loaded into the system properties.
	static {
		Util.applyDragomSystemProperties();
	}

	/**
	 * Sets the {@link ExecContextFactory}.
	 *
	 * @param execContextFactory See description.
	 */
	public static void setExecContextFactory(ExecContextFactory execContextFactory) {
		ExecContextFactoryHolder.execContextFactory = execContextFactory;
	}

	/**
	 * @return {@link ExecContextFactory} set with {@link #setExecContextFactory} or
	 *   the default ExecContextFactory if none has been set.
	 */
	public static ExecContextFactory getExecContextFactory() {
		if (ExecContextFactoryHolder.execContextFactory == null) {
			String execContextFactoryClass;

			execContextFactoryClass = System.getProperty(ExecContextFactoryHolder.SYS_PROP_DEFAULT_EXEC_CONTEXT_FACTORY);

			if (execContextFactoryClass != null) {
				try {
					Class<? extends ExecContextFactory> classExecContextFactory;

					classExecContextFactory = Class.forName(execContextFactoryClass).asSubclass(ExecContextFactory.class);
					return classExecContextFactory.newInstance();
				} catch (ClassNotFoundException | IllegalAccessException | InstantiationException e) {
					throw new RuntimeException(e);
				}
			} else {
				ExecContextFactoryHolder.execContextFactory = new DefaultExecContextFactory();
			}
		}

		return ExecContextFactoryHolder.execContextFactory;
	}
}
