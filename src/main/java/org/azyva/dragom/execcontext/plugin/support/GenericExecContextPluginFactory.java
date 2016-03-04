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

package org.azyva.dragom.execcontext.plugin.support;

import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.Map;

import org.azyva.dragom.execcontext.ExecContext;
import org.azyva.dragom.execcontext.plugin.ExecContextPlugin;
import org.azyva.dragom.execcontext.plugin.ExecContextPluginFactory;

/**
 * Generic implementation of {@link ExecContextPluginFactory} based on a simple
 * class that implements (a sub-interface of) {@link ExecContextPlugin}.
 * <p>
 * ExecContextPlugin instances are obtained from {@link ExecContextPluginFactory}.
 * But in many cases an ExecContextPlugin can be instantiated by creating an
 * instance of a class with a constructor having an {@link ExecContext} as its
 * only argument. This generic class simply wraps such a class inside a simple
 * ExecContextPluginFactory.
 * <p>
 * This class ensures that for a given ExecContextPlugin implementation class and
 * ExecContext, the same ExecContextPlugin instance is returned. This to make it
 * so that if a single ExecContextPlugin implementation class that extends
 * multiple ExecContextPlugin interfaces, the same instance is actually used for
 * all of them (for a given ExecContext), which is generally what such an
 * implementation class would expect.
 *
 * @author David Raymond
 * @param <ExecContextPluginInterface> ExecContextPlugin sub-interface returned by
 *   the ExecContextPluginFactory.
 */
public class GenericExecContextPluginFactory<ExecContextPluginInterface extends ExecContextPlugin> implements ExecContextPluginFactory<ExecContextPluginInterface> {
	/**
	 * Keys in the Map of instantiated {@link ExecContextPlugin}'s to reuse
	 * ExecContextPlugin instances.
	 */
	private static class ExecContextPluginKey {
		/**
		 * {@link ExecContextPlugin} implementation class.
		 */
		Class<? extends ExecContextPlugin> classExecContextPluginImpl;

		/**
		 * {@link ExecContext}.
		 */
		ExecContext execContext;

		ExecContextPluginKey(Class<? extends ExecContextPlugin> classExecContextPluginImpl, ExecContext execContext) {
			this.classExecContextPluginImpl = classExecContextPluginImpl;
			this.execContext = execContext;
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result;

			result = 1;
			result = (prime * result) + this.classExecContextPluginImpl.hashCode();
			result = (prime * result) + this.execContext.hashCode();

			return result;
		}

		@Override
		public boolean equals(Object other) {
			ExecContextPluginKey execContextPluginKeyOther;

			if (this == other) {
				return true;
			}

			if (other == null) {
				return false;
			}

			if (!(other instanceof ExecContextPluginKey)) {
				return false;
			}

			execContextPluginKeyOther = (ExecContextPluginKey)other;

			if (!this.classExecContextPluginImpl
					.equals(execContextPluginKeyOther.classExecContextPluginImpl)) {
				return false;
			}

			if (!this.execContext.equals(execContextPluginKeyOther.execContext)) {
				return false;
			}

			return true;
		}
	}

	private static Map<ExecContextPluginKey, ExecContextPlugin> mapExecContextPlugin = new HashMap<ExecContextPluginKey, ExecContextPlugin>();

	Class<ExecContextPluginInterface> classExecContextPluginImpl;

	/**
	 * Constructor.
	 *
	 * @param classExecContextPluginImpl Class implementing the {@link ExecContextPlugin}.
	 */
	public GenericExecContextPluginFactory(Class<ExecContextPluginInterface> classExecContextPluginImpl) {
		this.classExecContextPluginImpl = classExecContextPluginImpl;
	}

	@Override
	@SuppressWarnings("unchecked")
	public ExecContextPluginInterface getExecContextPlugin(ExecContext execContext) {
		ExecContextPluginKey execContextPluginKey;
		ExecContextPluginInterface execContextPlugin;

		execContextPluginKey = new ExecContextPluginKey(this.classExecContextPluginImpl, execContext);
		execContextPlugin = (ExecContextPluginInterface)GenericExecContextPluginFactory.mapExecContextPlugin.get(execContextPluginKey);

		if (execContextPlugin != null) {
			return execContextPlugin;
		} else {
			try {
				execContextPlugin = this.classExecContextPluginImpl.getConstructor(ExecContext.class).newInstance(execContext);
				GenericExecContextPluginFactory.mapExecContextPlugin.put(execContextPluginKey, execContextPlugin);

				return execContextPlugin;
			} catch (NoSuchMethodException | IllegalAccessException | InstantiationException | InvocationTargetException e) {
				throw new RuntimeException(e);
			}
		}
	}
}
