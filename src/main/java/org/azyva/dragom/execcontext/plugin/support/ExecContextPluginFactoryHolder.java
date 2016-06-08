/*
 * Copyright 2015 AZYVA INC.
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

package org.azyva.dragom.execcontext.plugin.support;

import java.util.HashMap;
import java.util.Map;

import org.azyva.dragom.execcontext.plugin.ExecContextPlugin;
import org.azyva.dragom.execcontext.plugin.ExecContextPluginFactory;
import org.azyva.dragom.util.Util;

/**
 * Holds and provides access to {@link ExecContextPluginFactory}'s.
 * <p>
 * Allows generic tools to be provided by Dragom or other third party that can be
 * integrated as a client tool by providing a way for client tools to easily
 * provide the ExecContextPluginFactory's to use. Tools provided by Dragom make
 * use of this strategy.
 * <p>
 * Dragom attempts to remain as independent as possible from external frameworks
 * whose use would have impacts on clients, such as Spring. But at the same time
 * Dragom must not prevent the use of such frameworks. This class allows, but
 * does not force, ExecContextFactory's to be set using a dependency injection
 * framework such as Spring.
 * <p>
 * ExecContextPluginFactoryHolder can also be used by tools without using a
 * dependency injection framework. ExecContextPluginFactory's can simply be set by
 * bootstrapping code before invoking the tool.
 * <p>
 * The ExecContextPluginFactory's are simply held in a static Map.
 * <p>
 * As a convenience, if no ExecContextPluginFactory is set for a given
 * {@link ExecContextPlugin} interface, the following strategy is used:
 * <p>
 * <li>If the org.azyva.dragom.DefaultExecContextPluginFactory.&lt;interface&gt;
 *     system property is defined the identified class is used and instantiated
 *     using the default constructor. Example:
 *     org.azyva.dragom.DefaultExecContextPluginFactory.org.azyva.dragom.execontext.plugin.WorkspacePlugin=com.acme.MyWorkspacePluginFactory;<li>;</li>
 * <li>Otherwise, if the
 *     org.azyva.dragom.DefaultExecContextPluginImpl.&lt;interface&gt; system
 *     property is defined, {@link GenericExecContextPluginFactory} is used to
 *     wrap the identified ExecContextPlugin implementation class as an
 *     ExecContextPluginFactory. Example:
 *     org.azyva.dragom.DefaultExecContextPluginImpl.org.azyva.dragom.execontext.plugin.WorkspacePlugin=com.acme.MyWorkspaePluginImpl;<li>
 * <li>Otherwise an ExecContextPluginFactory implementation class name is
 *     constructed by taking the ExecContextPlugin interface name, adding the
 *     "impl" sub-package and completing the name with the "Default" prefix and
 *     "Factory" suffix. If such a class exists, it is used as is. Example: Given
 *     the ExecContextPlugin interface
 *     org.azyva.dragom.execcontext.plugin.WorkspacePlugin, the
 *     ExecContextPluginFactory class would be
 *     org.azyva.dragom.execcontext.plugin.impl.DefaultWorkspacePluginFactory.</li>
 * <li>Otherwise an ExecContextPlugin implementation class name is constructed by
 *     taking the ExecContextPlugin interface name, adding the "impl" sub-package
 *     and completing the name with the "Default" prefix and "Impl" suffix and the
 *     resulting ExecContextPlugin implementation is wrapped as an
 *     ExecContextPluginFactory using GenericExecContextPluginFactory. Example:
 *     Given the ExecContextPlugin interface
 *     org.azyva.dragom.execcontext.plugin.WorkspacePlugin, the ExecContextPlugin
 *     implementation class would be
 *     org.azyva.dragom.execcontext.plugin.impl.DefaultWorkspacePluginImpl.</li>
 *
 * @author David Raymond
 */
public class ExecContextPluginFactoryHolder {
	/**
	 * Prefix of the system property specifying the default
	 * {@link ExecContextPluginFactory} implementation class to use for a given
	 * {@link ExecContextPlugin} interface whose name is used as the suffix.
	 */
	private static final String SYS_PROP_PREFIX_DEFAULT_EXEC_CONTEXT_PLUGIN_FACTORY = "org.azyva.dragom.DefaultExecContextPluginFactory.";

	/**
	 * Prefix of the system property specifying the default {@link ExecContextPlugin}
	 * implementation class to use for a given ExecContextPlugin interface whose name
	 * is used as the suffix.
	 */
	private static final String SYS_PROP_PREFIX_DEFAULT_EXEC_CONTEXT_PLUGIN_IMPL = "org.azyva.dragom.DefaultExecContextPluginImpl.";

	private static Map<Class<? extends ExecContextPlugin>, ExecContextPluginFactory<? extends ExecContextPlugin>> mapExecContextPluginFactory = new HashMap<Class<? extends ExecContextPlugin>, ExecContextPluginFactory<? extends ExecContextPlugin>>();

	// Ensures that the Dragom properties are loaded into the system properties.
	static {
		Util.setDragomSystemProperties();
	}

	/**
	 * Sets the {@link ExecContextPluginFactory} for a given {@link ExecContextPlugin}
	 * interface.
	 *
	 * @param classExecContextPlugin Class of the ExecContextPlugin interface.
	 * @param execContextPluginFactory ExecContextPluginFactory.
	 */
	public static <ExecContextPluginClass extends ExecContextPlugin> void setExecContextPluginFactory(Class<ExecContextPluginClass> classExecContextPlugin, ExecContextPluginFactory<ExecContextPluginClass> execContextPluginFactory) {
		ExecContextPluginFactoryHolder.mapExecContextPluginFactory.put(classExecContextPlugin, execContextPluginFactory);
	}


	@SuppressWarnings("unchecked")
	public static <ExecContextPluginClass extends ExecContextPlugin> ExecContextPluginFactory<ExecContextPluginClass> getExecContextPluginFactory(Class<ExecContextPluginClass> classExecContextPlugin) {
		if (ExecContextPluginFactoryHolder.mapExecContextPluginFactory.containsKey(classExecContextPlugin)) {
			return (ExecContextPluginFactory<ExecContextPluginClass>)ExecContextPluginFactoryHolder.mapExecContextPluginFactory.get(classExecContextPlugin);
		} else {
			ExecContextPluginFactory<ExecContextPluginClass> execContextPluginFactory;
			String execContextPluginInterface;
			String execContextPluginFactoryClass;
			Class<ExecContextPluginFactory<ExecContextPluginClass>> classExecContextPluginFactory;
			String execContextPluginImplClass;
			Class<ExecContextPluginClass> classExecContextPluginImpl;

			try {
				execContextPluginFactory = null;

				execContextPluginInterface = classExecContextPlugin.getName();

				execContextPluginFactoryClass = System.getProperty(ExecContextPluginFactoryHolder.SYS_PROP_PREFIX_DEFAULT_EXEC_CONTEXT_PLUGIN_FACTORY + execContextPluginInterface);

				if (execContextPluginFactoryClass != null) {
					classExecContextPluginFactory = (Class<ExecContextPluginFactory<ExecContextPluginClass>>)Class.forName(execContextPluginFactoryClass);

					execContextPluginFactory = classExecContextPluginFactory.newInstance();
				} else {
					execContextPluginImplClass = System.getProperty(ExecContextPluginFactoryHolder.SYS_PROP_PREFIX_DEFAULT_EXEC_CONTEXT_PLUGIN_IMPL + execContextPluginInterface);

					if (execContextPluginImplClass != null) {
						classExecContextPluginImpl = (Class<ExecContextPluginClass>)Class.forName(execContextPluginImplClass);

						execContextPluginFactory = new GenericExecContextPluginFactory<ExecContextPluginClass>(classExecContextPluginImpl);
					} else {
						execContextPluginFactoryClass =
								classExecContextPlugin.getPackage().getName() +
								".impl.Default" +
								classExecContextPlugin.getSimpleName() +
								"Factory";

						try {
							classExecContextPluginFactory = (Class<ExecContextPluginFactory<ExecContextPluginClass>>)Class.forName(execContextPluginFactoryClass);
						} catch (ClassNotFoundException cnfe) {
							classExecContextPluginFactory = null;
						}

						if (classExecContextPluginFactory != null) {
							execContextPluginFactory = classExecContextPluginFactory.newInstance();
						} else {
							execContextPluginImplClass =
									classExecContextPlugin.getPackage().getName() +
									".impl.Default" +
									classExecContextPlugin.getSimpleName() +
									"Impl";

							classExecContextPluginImpl = (Class<ExecContextPluginClass>)Class.forName(execContextPluginImplClass);

							execContextPluginFactory = new GenericExecContextPluginFactory<ExecContextPluginClass>(classExecContextPluginImpl);
						}
					}
				}
			} catch (ClassNotFoundException | IllegalAccessException | InstantiationException e) {
				throw new RuntimeException(e);
			}

			if (execContextPluginFactory == null) {
				throw new RuntimeException("ExecContextPluginFactory for " + classExecContextPlugin + " not  defined.");
			}

			ExecContextPluginFactoryHolder.mapExecContextPluginFactory.put(classExecContextPlugin, execContextPluginFactory);

			return execContextPluginFactory;
		}
	}
}
