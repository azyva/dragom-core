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

package org.azyva.dragom.execcontext.plugin.impl;

import org.azyva.dragom.execcontext.ExecContext;
import org.azyva.dragom.execcontext.plugin.EventPlugin;
import org.azyva.dragom.execcontext.plugin.ToolLifeCycleExecContextPlugin;
import org.azyva.dragom.model.Node;
import org.azyva.dragom.model.event.NodeEvent;
import org.azyva.dragom.model.event.NodeEventListener;
import org.azyva.dragom.model.event.support.EventManager;

/**
 * Default {@link EventPlugin} implementation.
 * <p>
 * The {@link NodeEventListener}'s must be registered explicitly and are kept in
 * memory only, either in workspace or tool scope.
 * <p>
 * {@link ToolLifeCycleExecContextPlugin} is implemented so that the plugin is
 * aware of tool end, so that the NodeEventListener's registered in tool scope can
 * be discarded.
 * <p>
 * But NodeEventListener's are not persisted within the workspace and remain
 * instantiated as long as the workspace is cached in memory. NodeEventListener's
 * must be registered whenever a workspace is initialized.
 *
 * @author David Raymond
 */
public class DefaultEventPluginImpl implements EventPlugin, ToolLifeCycleExecContextPlugin {
  EventManager eventManager;
  EventManager eventManagerTransient;

  public DefaultEventPluginImpl(ExecContext execContext) {
  }

  @Override
  public <NodeEventClass extends NodeEvent> void registerListener(Node node, NodeEventListener<NodeEventClass> nodeEventListener, boolean indChildrenAlso, boolean indTransient) {
    EventManager eventManager;

    if (indTransient) {
      if (this.eventManagerTransient == null) {
        this.eventManagerTransient = new EventManager();
      }

      eventManager = this.eventManagerTransient;
    } else {
      if (this.eventManager == null) {
        this.eventManager = new EventManager();
      }

      eventManager = this.eventManager;
    }

    eventManager.registerListener(node, nodeEventListener, indChildrenAlso);
  }

  @Override
  public void raiseNodeEvent(NodeEvent nodeEvent) {
    if (this.eventManager != null) {
      this.eventManager.raiseNodeEvent(nodeEvent);
    }
  }

  @Override
  public boolean isTransient() {
    return false;
  }

  @Override
  public void startTool() {
  }

  @Override
  public void endTool() {
    this.eventManagerTransient = null;
  }
}
