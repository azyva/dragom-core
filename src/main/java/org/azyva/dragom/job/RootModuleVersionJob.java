/*
 * Copyright 2015 - 2017 AZYVA INC. INC.
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

import org.azyva.dragom.model.ModuleVersion;
import org.azyva.dragom.model.plugin.ReferenceManagerPlugin;
import org.azyva.dragom.reference.Reference;
import org.azyva.dragom.reference.ReferencePathMatcher;
import org.azyva.dragom.util.RuntimeExceptionUserError;

/**
 * Represents jobs based on root {@link ModuleVersion}'s and which traverse
 * the reference graph by checking out the {@link ModuleVersion} source code and
 * using {@link ReferenceManagerPlugin} and other (@link NodePlugin}'s to obtain
 * {@link Reference}'s.
 *
 * <p>It allows such classes to be used generically by
 * GenericRootModuleVersionJobInvokerTool from dragom-cli-tools.
 *
 * <p>{@link RootModuleVersionJobAbstractImpl} provides a useful base class for
 * implementing such jobs. But jobs can also simply implement this interface if
 * that base class is not suitable.
 *
 * @author David Raymond
 */
public interface RootModuleVersionJob {
  /**
   * Possible behaviors related to unsynchronized changes in a user working
   * directory.
   */
  public static enum UnsyncChangesBehavior {
    /**
     * Do not test or handle unsynchronized changes.
     */
    DO_NOT_HANDLE,

    /**
     * Throws {@link RuntimeExceptionUserError} if unsynchronized changes are
     * detected.
     */
    USER_ERROR,

    /**
     * Interact with the user if unsynchronized changes are detected. In the case of
     * local changes, "do you want to continue"-style interaction. In the case of
     * remote changes, interaction for updating.
     */
    INTERACT
  }

  /**
   * Sets the {@link ReferencePathMatcher} profided by the caller defining on which
   * ModuleVersion's in the reference graphs the job will be applied.
   *
   * @param referencePathMatcherProvided See description.
   */
  void setReferencePathMatcherProvided(ReferencePathMatcher referencePathMatcherProvided);

  /**
   * @param unsyncChangesBehaviorLocal Behavior related to unsynchronized local
   *   changes. The default is {@link UnsyncChangesBehavior#DO_NOT_HANDLE}.
   */
  void setUnsyncChangesBehaviorLocal(UnsyncChangesBehavior unsyncChangesBehaviorLocal);

  /**
   * @param unsyncChangesBehaviorRemote Behavior related to unsynchronized remote
   *   changes. The default is {@link UnsyncChangesBehavior#DO_NOT_HANDLE}.
   */
  void setUnsyncChangesBehaviorRemote(UnsyncChangesBehavior unsyncChangesBehaviorRemote);

  /**
   * @return Indicate that the List of root {@link ModuleVersion} passed to the
   *   constructor was changed and should be saved by the caller if persisted.
   */
  boolean isListModuleVersionRootChanged();

  /**
   * Main method for performing the job.
   */
  void performJob();

}