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

import java.util.ArrayList;
import java.util.List;

import org.azyva.dragom.model.ArtifactGroupId;
import org.azyva.dragom.model.ClassificationNode;
import org.azyva.dragom.model.NodePath;
import org.azyva.dragom.model.plugin.FindModuleByArtifactGroupIdPlugin;
import org.azyva.dragom.util.Util;

/**
 * Factory for FindModuleByArtifactGroupIdPlugin that assumes a simple equivalence
 * between a module node path and the ArtifactGroupId using inference
 * rules.
 *
 * The value of the property BASE_GROUP_ID is used as the base groupId. Then each
 * node name in the node path (up to but not the module name) is converted into an
 * element of the groupId. The conversion algorithm is to convert from PascalCase
 * to lowercase-with-dash notation.
 *
 * The groupId obtained must match the groupId of the ArtifactGroupId specified.
 *
 * Then the plugin constructs a list of module node paths all using the same node
 * path associated with the plugin. The module names start with the artifactId of
 * the ArtifactGroupId specified and reduce down to the base artifactId based on -
 * separating the tokens.
 *
 * For example if the value of the BASE_GROUP_ID property is com.acme and the node
 * node path associated with the plugin is Foo/Bar/PascalCaseNodeName/, then only
 * ArtifactGroupId whose groupId is com.acme.foo.bar.pascal-case-node-name are
 * considered.
 *
 * Then if the artifactId of the ArtifactGroupId specified is
 * my-module-submodule-name-with-many-tokens, the list of NodePath
 * returned will be in that order:
 *
 * - Foo/Bar/PascalCaseNodeName/my-module-submodule-name-with-many-tokens
 * - Foo/Bar/PascalCaseNodeName/my-module-submodule-name-with-many
 * - Foo/Bar/PascalCaseNodeName/my-module-submodule-name-with
 * - Foo/Bar/PascalCaseNodeName/my-module-submodule-name
 * - Foo/Bar/PascalCaseNodeName/my-module-submodule
 * - Foo/Bar/PascalCaseNodeName/my-module
 * - Foo/Bar/PascalCaseNodeName/my
 *
 * If one module in that list already exists (is already created), it will be
 * returned. If none of the modules already exist, they will be requested one by
 * one in order and for each of them that actually exist, they will be asked if
 * their build produces the specified ArtifactGroupId. Among the above list if we
 * try to make some sense of the module names, it is likely that only the module
 * whose name is my-module exists and that it will respond that ArtifactGroupId
 * com.acme.foo.bar.pascal-case-node-name:my-module-submodule-name-with-many-tokens
 * can possibly be produced. In such a case, this is the module that will be
 * returned. Alternatively, it may be possible that a module named
 * my-module-submodule-name-with-many-tokens exists which says it definitively
 * produces ArtifactGroupId
 * com.acme.foo.bar.pascal-cas-node-name:my-module-submodule-name-with-many-tokens
 * in which case this moduel will be returned instead since it appears before in
 * the list.
 *
 * @author David Raymond
 */
public class SimpleFindModuleByArtifactGroupIdPluginImpl extends ClassificationNodePluginAbstractImpl implements FindModuleByArtifactGroupIdPlugin {
  // Shared with SimpleArtifactInfoPluginImpl.
  private static final String MODEL_PROPERTY_BASE_GROUP_ID = "BASE_GROUP_ID";

  private String groupId;

  public SimpleFindModuleByArtifactGroupIdPluginImpl(ClassificationNode classificationNode) {
    super(classificationNode);

    String baseGroupId;

    baseGroupId = classificationNode.getProperty(SimpleFindModuleByArtifactGroupIdPluginImpl.MODEL_PROPERTY_BASE_GROUP_ID);

    if (baseGroupId == null) {
      // If there is no base groupId, we must not get here for the root ClassificationNode since an empty groupId is not allowed.
      this.groupId = Util.inferGroupIdSegmentFromNodePath(classificationNode.getNodePath());
    } else if (classificationNode != classificationNode.getModel().getClassificationNodeRoot()) {
      this.groupId = baseGroupId + '.' + Util.inferGroupIdSegmentFromNodePath(classificationNode.getNodePath());
    } else {
      // If we get here for the root ClassificationNode, the groupId is the base groupId.
      this.groupId = baseGroupId;
    }
  }

  @Override
  public List<NodePath> getListModulePossiblyProduceArtifactGroupId(ArtifactGroupId artifactGroupId) {
    List<NodePath> listNodePath;
    String artifactId;
    int lastDashPos;

    if (!artifactGroupId.getGroupId().equals(this.groupId)) {
      return null;
    }

    listNodePath = new ArrayList<NodePath>();

    artifactId = artifactGroupId.getArtifactId();

    // Not all artifactIds are valid Node names. We fix the artifactId to
    // comply with the Node name restrictions.

    if (artifactId.startsWith("-")) {
      artifactId = artifactId.substring(1);
    }

    artifactId = artifactGroupId.getArtifactId().replaceAll("\\.", "");

    do {
      listNodePath.add(new NodePath(this.getNode().getNodePath(), artifactId));
      lastDashPos = artifactId.lastIndexOf('-');
      if (lastDashPos != -1) {
        artifactId = artifactId.substring(0, lastDashPos);
      }
    } while (lastDashPos != -1);

    return listNodePath;
  }
}
