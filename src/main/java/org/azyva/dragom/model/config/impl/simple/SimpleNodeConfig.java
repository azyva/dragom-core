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

package org.azyva.dragom.model.config.impl.simple;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.azyva.dragom.model.MutableNode;
import org.azyva.dragom.model.config.DuplicateNodeException;
import org.azyva.dragom.model.config.MutableNodeConfig;
import org.azyva.dragom.model.config.NodeConfig;
import org.azyva.dragom.model.config.NodeConfigTransferObject;
import org.azyva.dragom.model.config.OptimisticLockException;
import org.azyva.dragom.model.config.OptimisticLockHandle;
import org.azyva.dragom.model.config.PluginDefConfig;
import org.azyva.dragom.model.config.PluginKey;
import org.azyva.dragom.model.config.PropertyDefConfig;
import org.azyva.dragom.model.config.SimpleNodeConfigTransferObject;
import org.azyva.dragom.model.plugin.NodePlugin;

/**
 * Simple implementation for {@link NodeConfig} and {@link MutableNodeConfig}.
 *
 * @author David Raymond
 * @see org.azyva.dragom.model.config.simple
 */
public abstract class SimpleNodeConfig implements NodeConfig, MutableNodeConfig {
	/**
	 * Indicates that the {@link SimpleNodeConfig} is new and has not been finalized
	 * yet. This is the state in which it is after having been created using the
	 * create methods of {@link SimpleConfig} or
	 * {@link SimpleClassificationNodeConfig}.
	 */
	protected boolean indNew;

	/**
	 * Parent {@link SimpleClassificationNodeConfig}.
	 */
	private SimpleClassificationNodeConfig simpleClassificationNodeConfigParent;

	/**
	 * Unique revision number to manage optimistic locking (see
	 * {@link OptimisticLockHandle}).
	 * <p>
	 * Starts at since 0 within OptimisticLockHandle means not locked.
	 */
	protected int revision;

	/**
	 * Name.
	 */
	private String name;

	/**
	 * Map of {@link PropertyDefConfig}.
	 */
	private Map<String, PropertyDefConfig> mapPropertyDefConfig;

	/**
	 * Map of {@link PluginDefConfig}.
	 */
	private Map<PluginKey, PluginDefConfig> mapPluginDefConfig;

	/**
	 * Constructor.
	 *
	 * @param simpleClassificationNodeConfigParent Parent
	 *   SimpleClassificationNodeConfig.
	 */
	SimpleNodeConfig(SimpleClassificationNodeConfig simpleClassificationNodeConfigParent) {
		this.indNew = true;

		this.simpleClassificationNodeConfigParent = simpleClassificationNodeConfigParent;

		this.revision = 1;

		// LinkedHashMap are used to preserve insertion order.
		this.mapPropertyDefConfig = new LinkedHashMap<String, PropertyDefConfig>();
		this.mapPluginDefConfig = new LinkedHashMap<PluginKey, PluginDefConfig>();
	}

	@Override
	public String getName() {
		return this.name;
	}

	@Override
	public PropertyDefConfig getPropertyDefConfig(String name) {
		return this.mapPropertyDefConfig.get(name);
	}

	@Override
	public boolean isPropertyExists(String name) {
		return this.mapPropertyDefConfig.containsKey(name);
	}

	@Override
	public List<PropertyDefConfig> getListPropertyDefConfig() {
		// A copy is returned to prevent the internal Map from being modified by the
		// caller. Ideally, an unmodifiable List view of the Collection returned by
		// Map.values should be returned, but that does not seem possible.
		return new ArrayList<PropertyDefConfig>(this.mapPropertyDefConfig.values());
	}

	@Override
	public PluginDefConfig getPluginDefConfig(Class<? extends NodePlugin> classNodePlugin, String pluginId) {
		return this.mapPluginDefConfig.get(new PluginKey(classNodePlugin, pluginId));
	}

	@Override
	public boolean isPluginDefConfigExists(Class<? extends NodePlugin> classNodePlugin, String pluginId) {
		return this.mapPluginDefConfig.containsKey(new PluginKey(classNodePlugin, pluginId));
	}

	@Override
	public List<PluginDefConfig> getListPluginDefConfig() {
		// A copy is returned to prevent the internal Map from being modified by the
		// caller. Ideally, an unmodifiable List view of the Collection returned by
		// Map.values should be returned, but that does not seem possible.
		return new ArrayList<PluginDefConfig>(this.mapPluginDefConfig.values());
	}

	@Override
	public boolean isNew() {
		return this.indNew;
	}

	/**
	 * Check whether the {@link OptimisticLockHandle} corresponds to the current state
	 * of the data it represents.
	 * <p>
	 * If optimisticLockHandle is null, nothing is done.
	 * <p>
	 * If optimisticLockHandle is not null and is locked
	 * ({@link OptimisticLockHandle#isLocked}), its state must correspond to the state
	 * of the data it represents, otherwise {@link OptimisticLockException} is thrown.
	 * <p>
	 * If optimisticLockHandle is not null and is not locked, it is simply locked to
	 * the current state of the data, unless indRequireLock, in which case an
	 * exception is thrown.
	 *
	 * @param optimisticLockHandle OptimisticLockHandle. Can be null.
	 * @param indRequireLock Indicates if it is required that the OptimisticLockHandle
	 *   be locked.
	 */
	protected void checkOptimisticLock(OptimisticLockHandle optimisticLockHandle, boolean indRequireLock) {
		if (optimisticLockHandle != null) {
			if (optimisticLockHandle.isLocked()) {
				if (((SimpleOptimisticLockHandle)optimisticLockHandle).getRevision() != this.revision) {
					throw new OptimisticLockException();
				}
			} else {
				if (indRequireLock) {
					throw new RuntimeException("Lock required.");
				}

				((SimpleOptimisticLockHandle)optimisticLockHandle).setRevision(this.revision);
			}
		}
	}

	@Override
	public OptimisticLockHandle createOptimisticLockHandle(boolean indLock) {
		return new SimpleOptimisticLockHandle(indLock ? this.revision : 0);
	}

	@Override
	public boolean isOptimisticLockValid(OptimisticLockHandle optimisticLockHandle) {
		return (((SimpleOptimisticLockHandle)optimisticLockHandle).getRevision() == this.revision);
	}

	@Override
	public NodeConfigTransferObject getNodeConfigTransferObject(OptimisticLockHandle optimisticLockHandle)
			throws OptimisticLockException {
		NodeConfigTransferObject nodeConfigTransferObject;

		this.checkOptimisticLock(optimisticLockHandle, false);

		nodeConfigTransferObject = new SimpleNodeConfigTransferObject();

		nodeConfigTransferObject.setName(this.name);

		for(PropertyDefConfig propertyDefConfig: this.mapPropertyDefConfig.values()) {
			nodeConfigTransferObject.setPropertyDefConfig(propertyDefConfig);
		}

		for(PluginDefConfig pluginDefConfig: this.mapPluginDefConfig.values()) {
			nodeConfigTransferObject.setPluginDefConfig(pluginDefConfig);
		}

		return nodeConfigTransferObject;
	}

	/**
	 * Called by subclasses to extract the data from a {@link NodeConfigTransferObject} and set
	 * them within the SimpleNodeConfig.
	 * <p>
	 * Uses the indNew variable, but does not reset it. It is intended to be reset by
	 * the subclass caller method, {@link MutableNodeConfig#setNodeConfigValueTransferObject}.
	 * <p>
	 * The reason for not directly implementing
	 * MutableNodeConfig.setNodeConfigValueTransferObject is that subclasses can have
	 * other tasks to perform.
	 * <p>
	 * If optimisticLockHandle is null, no optimistic lock is managed.
	 * <p>
	 * If optimisticLockHandle is not null, it must be locked
	 * ({@link OptimisticLockHandle#isLocked}) and its state must correspond to the
	 * state of the data it represents, otherwise {@link OptimisticLockException} is
	 * thrown. The state of the OptimisticLockHandle is updated to the new revision of
	 * the SimpleNodeConfig.
	 *
	 * @param nodeConfigTransferObject NodeConfigTransferObject.
	 * @param optimisticLockHandle OptimisticLockHandle. Can be null.
	 * @throws OptimisticLockException Can be thrown only if optimisticLockHandle is
	 *   not null. This is a RuntimeException that may be of interest to
	 *   the caller.
	 * @throws DuplicateNodeExcpeption When the new configuration data would introduce
	 *   a duplicate {@link MutableNode} within the parent. This is a RuntimeException
	 *   that may be of interest to the caller.
	 */
	protected void extractNodeConfigTransferObject(NodeConfigTransferObject nodeConfigTransferObject, OptimisticLockHandle optimisticLockHandle)
			throws DuplicateNodeException {
		String previousName;

		this.checkOptimisticLock(optimisticLockHandle, !this.indNew);

		if ((nodeConfigTransferObject.getName() == null) && (this.simpleClassificationNodeConfigParent != null)) {
			throw new RuntimeException("Name of NodeConfigTrnmsferObject must not be null for non-root SimpleClassificationNodeConfig.");
		}

		previousName = this.name;
		this.name = nodeConfigTransferObject.getName();

		if (this.indNew) {
			if (this.simpleClassificationNodeConfigParent != null) {
				this.simpleClassificationNodeConfigParent.setSimpleNodeConfigChild(this);
			}
		} else {
			if ((this.simpleClassificationNodeConfigParent != null) && (!this.name.equals(previousName))) {
				this.simpleClassificationNodeConfigParent.renameSimpleNodeConfigChild(previousName, this.name);
			}
		}

		this.mapPropertyDefConfig.clear();

		for(PropertyDefConfig propertyDefConfig: nodeConfigTransferObject.getListPropertyDefConfig()) {
			this.mapPropertyDefConfig.put(propertyDefConfig.getName(),  propertyDefConfig);
		}

		this.mapPluginDefConfig.clear();

		for(PluginDefConfig pluginDefConfig: nodeConfigTransferObject.getListPluginDefConfig()) {
			this.mapPluginDefConfig.put(new PluginKey(pluginDefConfig.getClassNodePlugin(), pluginDefConfig.getPluginId()), pluginDefConfig);
		}

		if (!this.indNew) {
			this.revision++;
		}

		if (optimisticLockHandle != null) {
			((SimpleOptimisticLockHandle)optimisticLockHandle).setRevision(this.revision);
		}
	}

	@Override
	public void delete() {
		if (!this.indNew && (this.simpleClassificationNodeConfigParent != null)) {
			this.simpleClassificationNodeConfigParent.removeChildNodeConfig(this.name);
			this.simpleClassificationNodeConfigParent = null;
		}
	}
}
