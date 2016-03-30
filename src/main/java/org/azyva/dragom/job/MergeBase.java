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

package org.azyva.dragom.job;

import java.util.ResourceBundle;

import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.Version;
import org.azyva.dragom.model.plugin.ScmPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>
 * There are two possible modes of operation that affect how the destination
 * {@link Version} when merging a ModuleVersion is selected:
 * <p>
 * <li>Navigating in the destination reference graph: This is the most common mode
 *     used, for example, when retrofitting production changes present in the
 *     main dynamic Version or the release static Version of a Module, into a
 *     dynamic Version used for an ongoing development effort, or when
 *     integrating changes done on a project dynamic Version, rooting a complete
 *     reference graph, into a release dynamic Version with its own reference
 *     graph;</li>
 * <li>Version specified explicitly: This mode is useful mostly after a release
 *     has been performed when it is required to merge changes made to all
 *     Module's into their corresponding main dynamic Version.</li>
 * <p>
 * In both cases, the merge process is driven by navigating the reference graph
 * of the source ModuleVersion. Only {@link Module}'s known to Dragom are
 * considered.
 * <p>
 * In all cases below where merge or diverging commits are mentioned, commits that
 * simply change the ArtifactVersion of the Module or the Version of its
 * references are not considered. These commits are recognized with the commit
 * attributes "dragom-version-change" and "dragom-reference-version-change".
 * <p>
 * In all cases below, the destination Version established must be in a user
 * workspace directory so that if merge conflicts are encountered, the user has a
 * workspace where he can resolve them. If a destination ModuleVersion is not in a
 * user workspace directory, it is checked out for the user.
 * <p>
 * <h1>First mode: Navigating in the destination reference graph</h1>
 * <p>
 * In this mode the destination Version is the one in the parallel reference graph
 * rooted at the same Module but a dynamic Version initially specified externally
 * (by the user). It is as if for each ReferencePath in the source reference
 * graph, the corresponding ModuleVersion is accessed in the destination reference
 * graph not considering the Version's within the source ReferencePath.
 * <p>
 * Once the source ModuleVersion and destination dynamic Version are established,
 * the following algorithm is applied.
 * <p>
 * We start by performing a merge using {@link ScmPlugin#merge}. We then iterate
 * through the child references in the source ModuleVersion. For each child
 * reference that is a Module known to Dragom and exists in the destination
 * ModuleVersion without considering the Version, we perform the following
 * algorithm. Note that a corresponding reference in the destination ModuleVersion
 * may not exist and there is nothing we can do. The presence or absence of a
 * corresponding reference is handled by the merge process itself.
 * <p>
 * <h2>Source and destination are static</h2>
 * <p>
 * If the source and destination reference Versions are static and the same, no
 * merge is required. If they are not the same we establish whether source and/or
 * destination references have diverging commits. This is done recursively on each
 * ModuleVersion that exist in both the source and destination reference graphs,
 * without regard to the actual Version's. If any ModuleVersion in the source has
 * diverging then the whole source reference graph is considered as having
 * diverging commits. Similarly for ModuleVersion's in the destination.
 * <p>
 * If source and destination reference graphs do not have diverging commits, no
 * merge is required. This case is not likely since were are generally talking
 * about different Version's and given different Version's, we expect to have
 * different source code.
 * <p>
 * If only the source reference graph diverges, the Version of the reference in
 * the destination reference graph is change to that of the source. This is
 * possible since the parent is necessarily a dynamic Version.
 * <p>
 * If only the destination reference graph diverges, no merge is required since
 * the destination contains all changes of the source.
 * <p>
 * If both the source and destination reference graphs diverge we have a merge
 * conflict at the reference graph level. We inform the user and abort the merge
 * process.
 * <p>
 * <h2>Source is dynamic and destination static</h2>
 * <p>
 * If the source reference Version is dynamic and destination static, establish
 * whether source and/or destination reference have diverging commits in the same
 * way as above.
 * <p>
 * If source and destination reference graphs do not have diverging commits, no
 * merge is required. This case is not likely, but possible if the source dynamic
 * Version has just been created.
 * <p>
 * If the source reference graph diverges, use {@link SwitchToDynamicVersion} to
 * switch the static Version of the reference in the destination to a dynamic
 * Version, potentially creating a new dynamic Version, and continue with the case
 * below (both source and destination reference Version's dynamic). If the
 * destination reference graph also diverges issue a warning that the user should
 * expect the switched-to dynamic Version to also include these diverging commits.
 * <p>
 * If only the destination reference graph diverges, no merge is required.
 * <p>
 * <h2>Destination is dynamic (regardless of source)</h2>
 * <p>
 * Recurse using the two ModuleVersion corresponding to the two source and
 * destination child references.
 * <p>
 * <h1>Second mode: Version specified explicitly</h1>
 * <p>
 * This mode applies only if the source Version is static.<p>
 * <p>
 * In this mode the destination dynamic Version is specified explicitly for each
 * ModuleVersion that must be merged while traversing the source reference graph.
 * Generally the class interacts with the user to let him specify the destination
 * dynamic Version, but default dynamic Version's can be specified using runtime
 * properties to avoid user interaction. In all cases, the destination dynamic
 * Version must exist.
 * <p>
 * Once the source ModuleVersion and destination Version are established, the
 * algorithm is the same as above, except for the following.
 * <p>
 * The source reference Version will always be static since the source Version can
 * only be static (references within a static Version are also static). Therefore
 * it will never happen that a new dynamic Version has to be switched to.
 * <p>
 * When recursing (destination reference Version is dynamic), the destination
 * Version has to be respecified. The actual Version in the destination reference
 * graph is not considered.
 *
 * @author David Raymond
 */
public class MergeBase {
	/**
	 * Logger for the class.
	 */
	private static final Logger logger = LoggerFactory.getLogger(MergeBase.class);

	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_ = "";

	/**
	 * ResourceBundle specific to this class.
	 */
	private static final ResourceBundle resourceBundle = ResourceBundle.getBundle(.class.getName() + "ResourceBundle");

}
bracket