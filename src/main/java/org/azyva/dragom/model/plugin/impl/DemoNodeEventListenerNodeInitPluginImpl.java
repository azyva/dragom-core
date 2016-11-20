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

import org.azyva.dragom.model.Node;
import org.azyva.dragom.model.event.NodeEvent;
import org.azyva.dragom.model.event.NodeEventListener;
import org.azyva.dragom.model.plugin.NodeInitPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for NodeInitPlugin that registers a demo NodeEventListener that simply
 * logs all events.
 *
 * This is mostly used for testing.
 *
 * @author David Raymond
 */
public class DemoNodeEventListenerNodeInitPluginImpl extends NodePluginAbstractImpl implements NodeInitPlugin {
	/**
	 * Logger for the class.
	 */
	private static final Logger logger = LoggerFactory.getLogger(DemoNodeEventListenerNodeInitPluginImpl.class);

	private class NodeEventListenerDemo implements NodeEventListener<NodeEvent> {
		@Override
		public void onEvent(NodeEvent nodeEvent) {
			DemoNodeEventListenerNodeInitPluginImpl.logger.info("NodeEvent was received: " + nodeEvent);
		}
	}

	public DemoNodeEventListenerNodeInitPluginImpl(Node node) {
		super(node);
	}

	@Override
	public void init() {
		this.getNode().registerListener(new NodeEventListenerDemo(), true);
	}
}
