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

package org.azyva.dragom.model.plugin.impl;

import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.plugin.RemoteBuilderPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link RemoteBuilderPlugin} that triggers the builds on Jenkins.
 * <p>
 * In some setups, while development occurs on developers' personal environments,
 * official release are triggered by developers, but are performed on a controlled
 * environment often provided by a build automation tool. This plugin supports
 * this use case using Jenkins as the build automation tool.
 * <p>
 * This plugin assumes a Jenkins job exists for each {@link Module} whose a
 * release {@link Version} may need to be built. The job is assumed to be
 * configured correctly for the Module and is assumed to require as the only
 * parameter the Version.
 *
 * @author David Raymond
 */
public class JenkinsRemoteBuilderPluginImpl extends ModulePluginAbstractImpl implements RemoteBuilderPlugin {
  /**
   * Logger for the class.
   */
  private static final Logger logger = LoggerFactory.getLogger(JenkinsRemoteBuilderPluginImpl.class);

  /**
   * Constructor.
   *
   * @param module Module.
   */
  public JenkinsRemoteBuilderPluginImpl(Module module) {
    super(module);
  }

}
