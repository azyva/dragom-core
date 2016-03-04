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

package org.azyva.dragom.model.config.impl.simple;

import org.azyva.dragom.model.Model;
import org.azyva.dragom.model.config.NodeConfig;
import org.azyva.dragom.model.config.PropertyDefConfig;

/**
 * Simple implementation for {@link PropertyDefConfig}.
 *
 * @author David Raymond
 * @see org.azyva.dragom.model.config.simple
 */
public class SimplePropertyDefConfig implements PropertyDefConfig {
	private String name;
	private String value;
	private boolean indOnlyThisNode;

	/**
	 * Constructor.
	 *
	 * @param name Name.
	 * @param value Value.
	 * @param indOnlyThisNode Indicates that this property applies specifically to the
	 *   {@link NodeConfig} on which it is defined, as opposed to being inherited by
	 *   child NodeConfig when interpreted by the {@link Model}.
	 */
	public SimplePropertyDefConfig(String name, String value, boolean indOnlyThisNode) {
		this.name = name;
		this.value = value;
		this.indOnlyThisNode = indOnlyThisNode;
	}

	@Override
	public String getName() {
		return this.name;
	}

	@Override
	public String getValue() {
		return this.value;
	}

	@Override
	public boolean isOnlyThisNode() {
		return this.indOnlyThisNode;
	}

	/**
	 * @return String to help recognize the
	 *   {@link PropertyDefConfig} instance, in logs for example.
	 */
	@Override
	public String toString() {
		return "SimplePropertyDefConfig [name=" + this.name + ", value=" + this.value + ", indOnlyThisNode=" + this.indOnlyThisNode + "]";
	}
}
