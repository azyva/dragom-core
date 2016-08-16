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

import org.azyva.dragom.model.config.ClassificationNodeConfig;
import org.azyva.dragom.model.config.Config;
import org.azyva.dragom.model.config.MutableClassificationNodeConfig;
import org.azyva.dragom.model.config.MutableConfig;


/**
 * Simple implementation of {@link Config}.
 *
 * @author David Raymond
 * @see org.azyva.dragom.model.config.simple
 */
public class SimpleConfig implements Config, MutableConfig {
	ClassificationNodeConfig classificationNodeConfigRoot;

	@Override
	public ClassificationNodeConfig getClassificationNodeConfigRoot() {
		return this.classificationNodeConfigRoot;
	}

	void setClassificationNodeConfigRoot(ClassificationNodeConfig classificationNodeConfigRoot) {
		this.classificationNodeConfigRoot = classificationNodeConfigRoot;
	}

	@Override
	public MutableClassificationNodeConfig createClassificationNodeConfigRoot() {
		return new SimpleClassificationNodeConfig(this);
	}
}
