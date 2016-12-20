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

import org.azyva.dragom.util.ModuleReentryAvoider;

/**
 * Implemented by jobs subclassing {@link RootModuleVersionJobAbstractImpl} which
 * need to expose {@link RootModuleVersionJobAbstractImpl#setIndAvoidReentry} so
 * that generic tools such as GenericRootModuleVersionjobInvokerTool from
 * dragom-cli-tools can provide an opion allowing the user to control whether
 * reentry should be avoided.
 *
 * @author David Raymond
 */
public interface ConfigReentryAvoider {
  /**
   * This method is essentially the same as
   * {@link RootModuleVersionJobAbstractImpl#setIndAvoidReentry}
   *
   * @param indAvoidReentry Specifies to avoid reentry by using
   *   {@link ModuleReentryAvoider}. The default is to avoid reentry.
   */
  void setIndAvoidReentry(boolean indAvoidReentry);
}
