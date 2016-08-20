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

import org.azyva.dragom.model.Model;
import org.azyva.dragom.model.ModelNodeBuilderFactory;
import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.MutableModule;
import org.azyva.dragom.model.config.Config;
import org.azyva.dragom.model.config.ModuleConfig;
import org.azyva.dragom.model.config.NodeType;

/**
 * Simple implementation of {@link Module} and {@link MutableModule}.
 *
 * @author David Raymond
 */
public class SimpleModule extends SimpleNode implements Module, MutableModule {
	/**
	 * Constructor used when dynamically completing a {@link Model}.
	 * <p>
	 * This constructor has package scope to enforce the use of
	 * {@link ModelNodeBuilderFactory#createModuleBuilder} to create new
	 * {@link SimpleModule}'s.
	 *
	 * @param simpleModel
	 */
	SimpleModule(SimpleModel simpleModel) {
		super(simpleModel);
	}

	/**
	 * Constructor used when creating a {@link Model} from {@link Config}.
	 * <p>
	 * This constructor has package scope to enforce the use of
	 * {@link SimpleModel#SimpleModel(Config)} to create a complete Model from
	 * {@link Config}.
	 *
	 * @param moduleConfig ModuleConfig.
	 * @param simpleClassificationNodeParent Parent SimpleClassificationNode.
	 */
	SimpleModule(ModuleConfig moduleConfig, SimpleClassificationNode simpleClassificationNodeParent) {
		super(moduleConfig, simpleClassificationNodeParent);
	}

	@Override
	public NodeType getNodeType() {
		return NodeType.MODULE;
	}
}
