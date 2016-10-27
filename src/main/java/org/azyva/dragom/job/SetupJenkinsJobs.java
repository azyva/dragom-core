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

import java.nio.file.Path;
import java.util.EnumSet;

import org.azyva.dragom.reference.ReferenceGraph;
import org.azyva.dragom.reference.ReferencePath;

/**
 * Sets up jobs in Jenkins based on the {@link ModuleVersion's} in a
 * {@link ReferenceGraph}.
 * <p>
 * Although creating jobs in a tool such as Jenkins based on a ReferenceGraph is a
 * common that should probably be abstracted, this class is currently specific to
 * Jenkins. Time, experience and maturity will tell if, when and how which process
 * should be abstracted info plugins. But for now at this early stage, it is not
 * deemed pertinent to undertake such a task.
 * <p>
 * Note however that although this class is specific to Jenkins, it still makes
 * use of {@link JenkinsJobCreatePlugin} to abstract the actual job creation
 * details, namely the config.xml file or the template parameters.
 * <p>
 * While most job classes derive from {@link RootModuleVersionJobAbstractImpl},
 * this class works with a {@link ReferenceGraph} which was presumably created
 * using {@link BuildReferenceGraph}. It works with a ReferenceGraph since for
 * each ModuleVersion it must know the ModuleVersion's which depend on it.
 *
 * @author David Raymond
 */
public class SetupJenkinsJobs {
	/**
	 * {@link ReferenceGraph}.
	 */
	private ReferenceGraph referenceGraph;

	/**
	 * Path to the file containing the items created.
	 */
	private Path pathItemsCreatedFile;

	/**
	 * Constructor.
	 *
	 * @param referenceGraph ReferenceGraph.
	 */
	public SetupJenkinsJobs(ReferenceGraph referenceGraph, Path pathItemsCreatedFile) {
		this.referenceGraph = referenceGraph;
		this.pathItemsCreatedFile = pathItemsCreatedFile;
	}

	/**
	 * {@link ReferenceGraph.Visitor} used to
	 */
	private class ReferenceGraphVisitorZzz implements ReferenceGraph.Visitor {
		/**
		 * Constructor.
		 */
		public ReferenceGraphVisitorZzz() {
		}

		@Override
		public void visit(ReferencePath referencePath, EnumSet<ReferenceGraph.VisitAction> enumSetVisitAction) {
		}
	}

	/**
	 * Main method for performing the job.
	 */
	public void performJob() {
		SetupJenkinsJobs.ReferenceGraphVisitorZzz referenceGraphVisitorZzz;

		referenceGraphVisitorZzz = new SetupJenkinsJobs.ReferenceGraphVisitorZzz();

		//??? reentry...
		this.referenceGraph.traverseReferenceGraph(null, false, true, referenceGraphVisitorZzz);
	}
}



/*
runtime properties:
- (global) Jenkins base URL (https://<server>/jenkins)
- (global) Root folder for continuous integration jobs (assemblage/ic)

- Root node path for jobs (can be not specified, in which case the NodePath of Module's is used) (Domain/SubDomain)
- Sub-folder, which can not exist. If ends with /, a folder. If not and below is true, simply concatenate
- Include Version as sub-folder (true/false), which may not exist.

parameters:
- Report path to produce. May already exist, in which case merge, delete and replace or simply replace
- Existig report. If not specified use above
- Report mode: merge, delete and replace or replace
  (can delete by specifying delete and replace with non-matching ReferencePathMatcher)


Take information from dragom.properties file in module (maven version, etc.)
Need to have site-specific fonctionality
- GroupId, artifactId is too specific to Desjardins.
Maybe have some kind of simple plugin that simply builds the config.xml file.
*/