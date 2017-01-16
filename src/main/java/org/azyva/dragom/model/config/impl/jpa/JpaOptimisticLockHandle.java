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

package org.azyva.dragom.model.config.impl.jpa;

import java.sql.Timestamp;

import org.azyva.dragom.model.config.OptimisticLockHandle;

/**
 * Simple implementation of {@link OptimisticLockHandle} used by
 * {@link JpaNodeConfig} that is based on a last modification timestamp.
 *
 * @author David Raymond
 */
public class JpaOptimisticLockHandle implements OptimisticLockHandle {
  /**
   * Last modification timesatmp.
   */
  Timestamp timestampLastMod;

  /**
   * Constructor.
   *
   * @param timestampLastMod Last modification timestamp.
   */
  JpaOptimisticLockHandle(Timestamp timestampLastMod) {
    this.timestampLastMod = timestampLastMod;
  }

  @Override
  public boolean isLocked() {
    return this.timestampLastMod != null;
  }

  @Override
  public void clearLock() {
    this.timestampLastMod = null;
  }

  /**
   * @return Last modification timestamp.
   */
  Timestamp getTimestampLastMod() {
    return this.timestampLastMod;
  }

  /**
   * Sets the last modification timestamp.
   *
   * @param timestampLastMod See description.
   */
  void setTimestampLastMod(Timestamp timestampLastMod) {
    this.timestampLastMod = timestampLastMod;
  }
}
