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

package org.azyva.dragom.model.plugin.impl;

import org.azyva.dragom.model.ClassificationNode;
import org.azyva.dragom.model.Model;
import org.azyva.dragom.model.ModelNodeBuilderFactory;
import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.ModuleBuilder;
import org.azyva.dragom.model.plugin.ScmPlugin;
import org.azyva.dragom.model.plugin.UndefinedDescendantNodeManagerPlugin;

/**
 * Simple implementation of {@link UndefinedDescendantNodeManagerPlugin}.
 * <p>
 * This UndefinedDescendantNodeManagerPlugin does not allow creating undefined
 * ClassificationNodes.
 * <p>
 * It allows creating {@link Module}'s and it validates their existence with their
 * {@link ScmPlugin}.
 *
 * @author David Raymond
 */
public class UndefinedDescendantNodeManagerSimpleDynamicModulePluginImpl extends ClassificationNodePluginAbstractImpl implements UndefinedDescendantNodeManagerPlugin {
	public UndefinedDescendantNodeManagerSimpleDynamicModulePluginImpl(ClassificationNode classificationNode) {
		super(classificationNode);
	}

	@Override
	public ClassificationNode requestClassificationNode(String name) {
		/*
		 * This UndefinedDescendantNodeManagerPlugin does not support dynamically creating
		 * ClassificationNode's as validating the existence of a ClassificationNode is not
		 * generically possible. Other implementations may be possible though.
		 */
		return null;
	}

	@Override
	public Module requestModule(String name) {
		Model model;
		ModelNodeBuilderFactory modelNodeBuilderFactory;
		ModuleBuilder moduleBuilder;
		Module module;
		ScmPlugin scmPlugin;

		model = this.getClassificationNode().getModel();

		if (!(model instanceof ModelNodeBuilderFactory)) {
			return null;
		}

		modelNodeBuilderFactory = (ModelNodeBuilderFactory)model;
		moduleBuilder = modelNodeBuilderFactory.createModuleBuilder();
		moduleBuilder.setClassificationNodeParent(this.getClassificationNode());
		moduleBuilder.setName(name);
		module = moduleBuilder.getPartial();

		/*
		 * At this point, the setup for the new Module is not complete as its parent does
		 * not include it as a child. This is sufficient for the ScmPlugin.isModuleExists
		 * method. If ever the Module is not valid, it will not be added within the parent
		 * and will remain unreferenced.
		 */

		scmPlugin = module.getNodePlugin(ScmPlugin.class, null);

		if (!scmPlugin.isModuleExists()) {
			return null;
		}

		/*
		 * Here we know the Module is valid. Before returning it we must add it within the
		 * parent.
		 */

		return moduleBuilder.create();
	}
}
