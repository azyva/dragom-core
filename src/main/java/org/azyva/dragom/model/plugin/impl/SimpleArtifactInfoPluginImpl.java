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

package org.azyva.dragom.model.plugin.impl;

import java.util.HashSet;
import java.util.Set;

import org.azyva.dragom.model.ArtifactGroupId;
import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.plugin.ArtifactInfoPlugin;
import org.azyva.dragom.util.Util;
import org.json.JSONArray;

/**
 * Factory for ArtifactInfoPlugin that assumes a simple equivalence between a
 * module node path and the ArtifactGroupId using inference rules.
 *
 * Rule 1: Base artifactId
 * -----------------------
 *
 * The value of the property BASE_GROUP_ID is used as the base groupId. Then each
 * node name in the node path (up to but not the module name) is converted into an
 * element of the groupId. The conversion algorithm is to convert from PascalCase
 * to lowercase-with-dash notation.
 *
 * The artifactId of the ArtifactGroupId is the module name.
 *
 * For example if the value of the BASE_GROUP_ID property is com.acme and the
 * module node path is Foo/Bar/PascalCaseNodeName/my-module, the ArtifactGroupId
 * "com.acme.foo.bar.pascal-case-node-name:my-module" is assumed to be
 * definitively produced by builds of the module.
 *
 * Rule 2: Submodules
 * ------------------
 *
 * Also, any ArtifactGroupId with the same groupId as above and an artifactId
 * starting with the module name followed by - (my-module- following the example)
 * is considered as being possibly produced by builds of the module. This is to
 * support modules having submodules as with Maven aggregator builds.
 *
 * Rule selection
 * --------------
 *
 * The value of the MODULE_NODE_PATH_INFERENCE_RULES property affects the
 * inference rules above:
 *
 * - Undefined (null) or "ALL": All inference rules are applied.
 * - ONLY_BASE_ARTIFACT_ID: Only rule 1 is applied.
 * - NONE: No rule is applied (only exceptions below).
 *
 * Exceptions
 * ----------
 *
 * Exceptions are supported. The value of the
 * ARRAY_DEFINITE_ARTIFACT_GROUP_ID_PRODUCED property is a JSON array of strings
 * representing the ArtifactGroupId of the artifacts definitively produced
 * by builds of the module.
 *
 * For example if the ARRAY_DEFINITE_ARTIFACT_GROUP_ID_PRODUCED property has the
 * following value:
 *
 * ["com.acme.exception:legacy-module","com.acme.other-exception.other-legacy-module"]
 *
 * the listed ArtifactGroupId are assumed to be definitively produced by build of
 * the module, on top of any ArtifactGroupId inferred by the rules above.
 *
 *  @author David Raymond
 */
public class SimpleArtifactInfoPluginImpl extends ModulePluginAbstractImpl implements ArtifactInfoPlugin {
	boolean isInferRuleSubmodules;
	String groupIdInferredSubmodules;
	Set<ArtifactGroupId> setDefiniteArtifactGroupIdProduced;

	public SimpleArtifactInfoPluginImpl(Module module) {
		super(module);

		String inferenceRules;
		boolean isInferRuleBaseArtifactId = false;
		String baseGroupId;
		String groupId = null;
		String stringJsonArrayDefiniteArtifactGroupIdProduced;

		inferenceRules = module.getProperty("MODULE_NODE_PATH_INFERENCE_RULES");

		if ((inferenceRules == null) || inferenceRules.equals("ALL")) {
			isInferRuleBaseArtifactId = true;
			this.isInferRuleSubmodules = true;
		} else if (inferenceRules != null) {
			if (inferenceRules.equals("ONLY_BASE_ARTIFACT_ID")) {
				isInferRuleBaseArtifactId = true;
			} else if (!inferenceRules.equals("NONE")) {
				throw new RuntimeException("Invalid value for the property " + inferenceRules + " read by plugin " + this.toString() + '.');
			}
		}

		/* If either of the two rules must be applied, we need to know the groupId
		 * inferred from the module node path.
		 */
		if (isInferRuleBaseArtifactId || this.isInferRuleSubmodules) {
			baseGroupId = module.getProperty("BASE_GROUP_ID");

			if (baseGroupId == null) {
				groupId = Util.inferGroupIdSegmentFromNodePath(module.getNodePath());
			} else {
				groupId = baseGroupId + '.' + Util.inferGroupIdSegmentFromNodePath(module.getNodePath());
			}
		}

		this.setDefiniteArtifactGroupIdProduced = new HashSet<ArtifactGroupId>();

		/* If the base artifactId rule must be applied, we simply add the ArtifactGroupId
		 * to the set of definitively produced ArtifactGroupId.
		 */

		if (isInferRuleBaseArtifactId) {
			this.setDefiniteArtifactGroupIdProduced.add(new ArtifactGroupId(groupId, module.getName()));
		}

		if (this.isInferRuleSubmodules) {
			this.groupIdInferredSubmodules = groupId;
		}

		stringJsonArrayDefiniteArtifactGroupIdProduced = module.getProperty("ARRAY_DEFINITE_ARTIFACT_GROUP_ID_PRODUCED");

		if (stringJsonArrayDefiniteArtifactGroupIdProduced != null) {
			JSONArray jsonArrayDefiniteArtifactGroupIdProduced;

			this.setDefiniteArtifactGroupIdProduced = new HashSet<ArtifactGroupId>();

			jsonArrayDefiniteArtifactGroupIdProduced = new JSONArray(stringJsonArrayDefiniteArtifactGroupIdProduced);

			for (int i = 0; i < jsonArrayDefiniteArtifactGroupIdProduced.length(); i++) {
				this.setDefiniteArtifactGroupIdProduced.add(new ArtifactGroupId((String)jsonArrayDefiniteArtifactGroupIdProduced.get(i)));
			}
		}

	}

	@Override
	public boolean isArtifactGroupIdProduced(ArtifactGroupId artifactGroupId) {
		return this.setDefiniteArtifactGroupIdProduced.contains(artifactGroupId);
	}

	@Override
	public boolean isArtifactGroupIdPossiblyProduced(ArtifactGroupId artifactGroupId) {
		if (this.isArtifactGroupIdProduced(artifactGroupId)) {
			return true;
		}

		if (!artifactGroupId.getGroupId().equals(this.groupIdInferredSubmodules)) {
			return false;
		}

		if (artifactGroupId.getArtifactId().startsWith(this.getModule().getName() + '-')) {
			return true;
		}

		return false;
	}

	//??? maybe make the set immutable? Would be safer and more contract-binding, but maybe uselessly overkill.
	@Override
	public Set<ArtifactGroupId> getSetDefiniteArtifactGroupIdProduced() {
		return this.setDefiniteArtifactGroupIdProduced;
	}
}
