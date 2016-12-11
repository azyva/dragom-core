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

package org.azyva.dragom.model.config.impl.xml;

import org.azyva.dragom.model.config.OptimisticLockHandle;
import org.azyva.dragom.model.config.impl.simple.SimpleNodeConfig;

/**
 * Simple implementation of {@link OptimisticLockHandle} used by
 * {@link SimpleNodeConfig} that is based on a simple unique revision number.
 *
 * @author David Raymond
 */
public class XmlOptimisticLockHandle implements OptimisticLockHandle {
  /**
   * Revision number.
   * <p>
   * Starts at 1. 0 means the {@link OptimisticLockHandle} is not locked.
   */
  private int revision;

  /**
   * Constructor.
   *
   * @param revision Revision number.
   */
  XmlOptimisticLockHandle(int revision) {
    this.revision = revision;
  }

  @Override
  public boolean isLocked() {
    return this.revision != 0;
  }

  @Override
  public void clearLock() {
    this.revision = 0;
  }

  /**
   * @return Revision number.
   */
  int getRevision() {
    return this.revision;
  }

  /**
   * Sets the revision number.
   *
   * @param revision See description.
   */
  void setRevision(int revision) {
    this.revision = revision;
  }
}
