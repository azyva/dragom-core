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

package org.azyva.dragom.model.event.support;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.azyva.dragom.execcontext.ExecContext;
import org.azyva.dragom.execcontext.plugin.EventPlugin;
import org.azyva.dragom.model.Node;
import org.azyva.dragom.model.config.NodeType;
import org.azyva.dragom.model.event.ClassificationNodeEvent;
import org.azyva.dragom.model.event.ModuleEvent;
import org.azyva.dragom.model.event.NodeEvent;
import org.azyva.dragom.model.event.NodeEventListener;

/**
 * Manages {@link NodeEventListener}'s, {@link NodeEvent}'s, registration of
 * NodeEventListener's and raising of NodeEvent's.
 * <p>
 * There is more than one occurrence where NodeEventListener's and NodeEvent need
 * to be managed:
 * <p>
 * <li>Within {@link Node}'s;</li>
 * <li>Within the {@link ExecContext}, with {@link EventPlugin}.</li>
 * <p>
 * This class factors the common code.
 *
 * @author David Raymond
 */
public class EventManager {
	/**
	 * Keys in mapNodeEventListener.
	 */
	private static class NodeEventListenerKey {
		/**
		 * Node.
		 */
		private Node node;

		/**
		 * Class of the NodeEvent.
		 */
		private Class<? extends NodeEvent> classNodeEvent;

		/**
		 * Constructor.
		 *
		 * @param node Node.
		 * @param nodeEventClass Class of the NodeEvent.
		 */
		private NodeEventListenerKey(Node node, Class<? extends NodeEvent> classNodeEvent) {
			this.node = node;
			this.classNodeEvent = classNodeEvent;
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result;

			result = 1;
			result = (prime * result) + this.classNodeEvent.hashCode();
			result = (prime * result) + this.node.hashCode();

			return result;
		}

		@Override
		public boolean equals(Object other) {
			if (this == other) {
				return true;
			}

			if (other == null) {
				return false;
			}

			if (!(other instanceof NodeEventListenerKey)) {
				return false;
			}

			NodeEventListenerKey otherNodeEventListenerKey = (NodeEventListenerKey)other;

			if (this.classNodeEvent != otherNodeEventListenerKey.classNodeEvent) {
				return false;
			}

			if (!this.node.equals(otherNodeEventListenerKey.node)) {
				return false;
			}

			return true;
		}
	}

	/**
	 * Entries in mapNodeEventListener.
	 */
	private static class NodeEventListenerEntry {
		private NodeEventListener<? extends NodeEvent> nodeEventListener;
		private boolean indChildrenAlso;

		/**
		 * Constructor.
		 *
		 * @param nodeEventListener NodeEventListener.
		 * @param indChildrenAlso Indicates if the NodeEventListener is interested in
		 *   receiving events for children as well.
		 */
		private NodeEventListenerEntry(NodeEventListener<? extends NodeEvent> nodeEventListener, boolean indChildrenAlso) {
			this.nodeEventListener = nodeEventListener;
			this.indChildrenAlso = indChildrenAlso;
		}

		/**
		 * @return NodeEventListener.
		 */
		public NodeEventListener<? extends NodeEvent> getNodeEventListener() {
			return this.nodeEventListener;
		}

		/**
		 * @return Indicates if the NodeEventListener is interested in receiving events
		 * for children as well.
		 */
		public boolean isChildrenAlso() {
			return this.indChildrenAlso;
		}
	}

	private Map<NodeEventListenerKey, List<NodeEventListenerEntry>> mapNodeEventListener;

	public <NodeEventClass extends NodeEvent> void registerListener(Node node, NodeEventListener<NodeEventClass> nodeEventListener, boolean indChildrenAlso) {
		Class<NodeEventClass> classNodeEvent;
		List<NodeEventListenerEntry> listNodeEventListenerEntry;

		if (this.mapNodeEventListener == null) {
			this.mapNodeEventListener = new HashMap<NodeEventListenerKey, List<NodeEventListenerEntry>>();
		}

		classNodeEvent = EventManager.getClassNodeEvent(nodeEventListener);

		listNodeEventListenerEntry = this.mapNodeEventListener.get(classNodeEvent);

		if (listNodeEventListenerEntry == null) {
			listNodeEventListenerEntry = new ArrayList<NodeEventListenerEntry>();
			this.mapNodeEventListener.put(new NodeEventListenerKey(node, classNodeEvent), listNodeEventListenerEntry);
		}

		listNodeEventListenerEntry.add(new NodeEventListenerEntry(nodeEventListener, indChildrenAlso));
	}

	public void raiseNodeEvent(NodeEvent nodeEvent) {
		if ((nodeEvent instanceof ModuleEvent) && (nodeEvent.getNode().getNodeType() != NodeType.MODULE)) {
			throw new RuntimeException("Module events must be raised on modules.");
		} else if ((nodeEvent instanceof ClassificationNodeEvent) && (nodeEvent.getNode().getNodeType() != NodeType.CLASSIFICATION)) {
			throw new RuntimeException("Classification node events must be raised on modules.");
		}

		this.dispatchNodeEventParent(nodeEvent.getNode(), nodeEvent);
	}

	/**
	 * Internal {@link NodeEvent} dispatching method.
	 * <p>
	 * {@link #raiseNodeEvent} performs some validation whereas this method does the
	 * actual dispatching, including recursively calling the same method on the
	 * parents so that {@link NodeEventListener}'s registered on parents with
	 * indChildrenAlso get notified.
	 *
	 * @param nodeEvent NodeEvent.
	 */
	@SuppressWarnings("unchecked") // Not able to avoid that one.
	private <NodeEventClass extends NodeEvent> void dispatchNodeEventParent(Node node, NodeEventClass nodeEvent) {
		List<NodeEventListenerEntry> listNodeEventListenerEntry;

		if (this.mapNodeEventListener != null) {
			// We iterate through the base classes since NodeEventListener's may have been
			// registered for NoveEvent base classes.
			for (Class<? extends Object> classNodeEvent = nodeEvent.getClass(); classNodeEvent != Object.class; classNodeEvent = classNodeEvent.getSuperclass()) {
				listNodeEventListenerEntry = this.mapNodeEventListener.get(new NodeEventListenerKey(node, NodeEvent.class.getClass().cast(classNodeEvent)));

				if (listNodeEventListenerEntry != null) {
					for (NodeEventListenerEntry nodeEventListenerEntry: listNodeEventListenerEntry) {
						if (nodeEventListenerEntry.isChildrenAlso() || (nodeEvent.getNode() == node)) {
							((NodeEventListener<NodeEventClass>)nodeEventListenerEntry.getNodeEventListener()).onEvent(nodeEvent);
						}
					}
				}
			}
		}

		if (node.getClassificationNodeParent() != null) {
			this.dispatchNodeEventParent(node.getClassificationNodeParent(), nodeEvent);
		}
	}


	/**
	 * Gets the {@link NodeEvent} subclass for a {@link NodeEventListener}.
	 *
	 * @param nodeEventListener NodeEventListener.
	 * @return NodeEvent subclass.
	 */
	@SuppressWarnings("unchecked") // Not able to avoid that one.
	public static <NodeEventClass extends NodeEvent> Class<NodeEventClass> getClassNodeEvent(NodeEventListener<NodeEventClass> nodeEventListener) {
		Class<?> classNodeEventListener;
		Type[] arrayTypeInterface;

		classNodeEventListener = nodeEventListener.getClass();
		arrayTypeInterface = classNodeEventListener.getGenericInterfaces();

		for (Type typeInterface: arrayTypeInterface) {
			if (typeInterface instanceof ParameterizedType) {
				ParameterizedType parameterizedType = (ParameterizedType)typeInterface;

				if (parameterizedType.getRawType() == NodeEventListener.class) {
					Type[] types = parameterizedType.getActualTypeArguments();
					return (Class<NodeEventClass>)types[0];
				}
			}
		}

		throw new RuntimeException("Node event class could not be inferred.");
	}
}
