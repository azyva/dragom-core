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

import org.azyva.dragom.model.config.ModuleConfig;
import org.azyva.dragom.model.config.ModuleConfigValue;
import org.azyva.dragom.model.config.MutableModuleConfig;
import org.azyva.dragom.model.config.NodeConfigValue;
import org.azyva.dragom.model.config.NodeType;

/**
 * Simple implementation for {@link ModuleConfig}.
 *
 * @author David Raymond
 * @see org.azyva.dragom.model.config.simple
 */
public class SimpleModuleConfig extends SimpleNodeConfig implements ModuleConfig, MutableModuleConfig  {
	/**
	 * Constructor.
	 *
	 * @param simpleClassificationNodeConfigParent Parent
	 *   SimpleClassificationNodeConfig.
	 */
	SimpleModuleConfig(SimpleClassificationNodeConfig simpleClassificationNodeConfigParent) {
		super(simpleClassificationNodeConfigParent);
	}

	@Override
	public NodeType getNodeType() {
		return NodeType.MODULE;
	}

	@Override
	public NodeConfigValue getNodeConfigValue() {
		return this.getModuleConfigValue();
	}

	@Override
	public void setNodeConfigValue(NodeConfigValue nodeConfigValue) {
		this.setModuleConfigValue((ModuleConfigValue)nodeConfigValue);
	}

	@Override
	public ModuleConfigValue getModuleConfigValue() {
		ModuleConfigValue moduleConfigValue;

		moduleConfigValue = new ModuleConfigValue();

		this.fillNodeConfigValue(moduleConfigValue);

		return moduleConfigValue;
	}

	@Override
	public void setModuleConfigValue(ModuleConfigValue moduleConfigValue) {
		this.extractNodeConfigValue(classificationNodeConfigValue);

		if (this.indNew) {

		}
	}

}
