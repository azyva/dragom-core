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

package org.azyva.dragom.util;

import java.util.HashSet;
import java.util.Set;

import org.azyva.dragom.model.ModuleVersion;

/**
 * Helps the various tools in avoiding reentry while traversing a module reference graph.
 *
 * @author David Raymond
 */

public class ModuleReentryAvoider {
  Set<ModuleVersion> setModuleVersion;

  public ModuleReentryAvoider() {
    this.setModuleVersion = new HashSet<ModuleVersion>();
  }

  /**
   * Indicates the intent of processing a module.
   *
   * @param moduleVersion ModuleVersion.
   * @return Indicates if the module can be processed, meaning that it has not
   * already been processed.
   */
  public boolean processModule(ModuleVersion moduleVersion) {
    return this.setModuleVersion.add(moduleVersion);
  }
}
