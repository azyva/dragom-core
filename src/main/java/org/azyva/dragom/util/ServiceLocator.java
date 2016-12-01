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

package org.azyva.dragom.util;

import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.Map;

import org.azyva.dragom.jenkins.JenkinsClient;


/**
 * Implements a simple and generic service locator.
 * <p>
 * This class allows a separation of concern between Dragom and clients regarding
 * the use of some service-like interfaces whose implementation should be selectable
 * at runtime.
 * <p>
 * An example of such a service-like interface is {@link JenkinsClient}.
 * <p>
 * One motivation is to allow a testing client to inject test doubles for such
 * service-like interfaces.
 * <p>
 * Although a dependency injection framework is not used to avoid imposing external
 * dependencies on clients, such a framework can be used by clients if desired.
 * <p>
 * This class implements the following algorithm to provide service
 * implementations to callers:
 * <ul>
 * <li>Services factories can be registered using {@link #setServiceFactory}. This
 *     method could be called by a dependency injection framework such as
 *     Spring. If a service factory is registered for a service interface, it is used
 *     to create instances of the service interface;
 * <li>If a requested service does not have a registered factory, a system property
 *     "org.azyva.dragom.DefaultServiceFactory.&lt;service interface name&gt;" is
 *     used to obtain the name of a service factory class which must have a static
 *     "getService" method that returns an instance of the service interface;
 * <li>If a system property for the service factory class is not defined, a system
 *     property "org.azyva.dragom.DefaultServiceImpl.&lt;service interface name&gt;"
 *     is used to obtain the name of an implementation class which must have a
 *     no-argument constructor;
 * <li>If a system property for the service implementation class is not defined, a
 *     default service factory class
 *     "&lt;service interface package&gt;.impl.Default&lt;service interface simple name&gt;Factory"
 *     is used;
 * <li>If a default factory class is not available, a default implementation class
 *     "&lt;service interface package&gt;.impl.Default&lt;service interface simple name&gt;Impl" is
 *     used.
 * </ul>
 *
 * @author David Raymond
 */
public class ServiceLocator {
  /**
   * Prefix of the system property specifying the default factory class for a
   * service interface whose name is used as the suffix.
   */
  private static final String SYS_PROPERTY_PREFIX_DEFAULT_SERVICE_FACTORY = "org.azyva.dragom.DefaultServiceFactory.";

  /**
   * Prefix of the system property specifying the default service implementation
   * class for a service interface whose name is used as the suffix.
   */
  private static final String SYS_PROPERTY_PREFIX_DEFAULT_SERVICE_IMPL = "org.azyva.dragom.DefaultServiceImpl.";

  /**
   * Interface which must be implemented by registered service factories.
   *
   * @param <ServiceInterface> Service interface.
   */
  public interface ServiceFactory<ServiceInterface> {
    /**
     *
     * @return Service instance. Instantiation semantics (singleton, new instance,
     *   etc.) are not defined.
     */
    ServiceInterface getService();
  }

  private static Map<Class<?>, ServiceFactory<?>> mapServiceFactory = new HashMap<Class<?>, ServiceFactory<?>>();

  static {
    Util.applyDragomSystemProperties();
  }

  /**
   * Constructor.
   * <p>
   * This class has only static methods.
   */
  private ServiceLocator() {
  }

  public static <ServiceInterface> void setServiceFactory(Class<ServiceInterface> classServiceInterface, ServiceFactory<ServiceInterface> serviceFactory) {
    ServiceLocator.mapServiceFactory.put(classServiceInterface, serviceFactory);
  }

  @SuppressWarnings("unchecked")
  public static <ServiceInterface> ServiceInterface getService(Class<ServiceInterface> classServiceInterface) {
    ServiceFactory<ServiceInterface> serviceFactory;
    String serviceFactoryClassName;
    String serviceImplClassName;

    serviceFactory = (ServiceFactory<ServiceInterface>)ServiceLocator.mapServiceFactory.get(classServiceInterface);

    if (serviceFactory != null) {
      return serviceFactory.getService();
    }

    serviceFactoryClassName = System.getProperty(ServiceLocator.SYS_PROPERTY_PREFIX_DEFAULT_SERVICE_FACTORY + classServiceInterface.getName());

    if (serviceFactoryClassName != null) {
      try {
        return (ServiceInterface)Class.forName(serviceFactoryClassName).getMethod("getService").invoke(null);
      } catch (NoSuchMethodException | ClassNotFoundException | InvocationTargetException | IllegalAccessException e) {
        throw new RuntimeException(e);
      }
    }

    serviceImplClassName = System.getProperty(ServiceLocator.SYS_PROPERTY_PREFIX_DEFAULT_SERVICE_IMPL + classServiceInterface.getName());

    if (serviceImplClassName != null) {
      try {
        return (ServiceInterface)Class.forName(serviceImplClassName).newInstance();
      } catch (ClassNotFoundException | IllegalAccessException | InstantiationException e) {
        throw new RuntimeException(e);
      }
    }

    try {
      return (ServiceInterface)Class.forName(classServiceInterface.getPackage() + ".impl.Default" + classServiceInterface.getSimpleName() + "Factory").getMethod("getService").invoke(null);
    } catch (ClassNotFoundException cnfe) {
      try {
        return (ServiceInterface)Class.forName(classServiceInterface.getPackage() + ".impl.Default" + classServiceInterface.getSimpleName() + "Impl").newInstance();
      } catch (ClassNotFoundException | IllegalAccessException | InstantiationException e) {
        throw new RuntimeException(e);
      }
    } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }
}
