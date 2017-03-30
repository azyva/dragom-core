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

import org.azyva.dragom.model.Model;
import org.azyva.dragom.model.Node;

/**
 * Represents jobs which perform actions on {@link Node}'s by traversing the
 * {@link Model} Node hierarchy.
 *
 * <p>It allows such classes to be used generically by
 * GenericModelVisitorJobInvokerTool from dragom-cli-tools.
 *
 * <p>{@link ModelVisitorJobAbstractImpl} provides a useful base class for
 * implementing such jobs. But jobs can also simply implement this interface if
 * that base class is not suitable.
 *
 * @author David Raymond
 */
public interface ModelVisitorJob {
  /**
   * Main method for performing the job.
   */
  void performJob();
}