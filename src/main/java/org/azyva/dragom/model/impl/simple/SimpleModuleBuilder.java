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

package org.azyva.dragom.model.impl.simple;

import org.azyva.dragom.model.ModelNodeBuilderFactory;
import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.ModuleBuilder;

/**
 *
 * @author David Raymond
 */
public class SimpleModuleBuilder extends SimpleNodeBuilder<Module> implements ModuleBuilder {
	/**
	 * This constructor has package scope to enforce the use of
	 * {@link ModelNodeBuilderFactory#createModuleBuilder} implemented
	 * by {@link SimpleModel} to create new {@link SimpleModule}'s.
	 *
	 * @param simpleModel
	 */
	protected SimpleModuleBuilder(SimpleModel simpleModel) {
		this.setSimpleNode(new SimpleModule(simpleModel));
	}
}
