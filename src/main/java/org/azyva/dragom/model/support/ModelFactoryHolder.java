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

package org.azyva.dragom.model.support;

import java.util.Properties;

import org.azyva.dragom.execcontext.ExecContextFactory;
import org.azyva.dragom.execcontext.support.DefaultExecContextFactory;
import org.azyva.dragom.model.Model;
import org.azyva.dragom.model.ModelFactory;
import org.azyva.dragom.util.Util;


/**
 * Holds and provides access to a {@link ModelFactory}.
 * <p>
 * Allows generic tools to be provided by Dragom or other third party that can be
 * integrated as a client tool by providing a way for client tools to easily
 * provide the ModelFactory to use. Tools provided by Dragom make use of this
 * strategy.
 * <p>
 * Generally the {@link ExecContextFactory} implementation takes care of
 * initializing the {@link Model} using a ModelFactory.
 * {@link DefaultExecContextFactory} makes use of this class. Other
 * ExecContextFactory implementations should do the same.
 * <p>
 * Dragom attempts to remain as independent as possible from external frameworks
 * whose use would have impacts on clients, such as Spring. But at the same time
 * Dragom must not prevent the use of such frameworks. This class allows, but
 * does not force, the ModelFactory to be set using a dependency injection
 * framework such as Spring.
 * <p>
 * ModelFactoryHolder can also be used by tools without using a dependency
 * injection framework. The ModelFactory can simply be set by bootstrapping code
 * before invoking the tool.
 * <p>
 * The ModelFactory is simply held in a static variable.
 * <p>
 * Alternatively a Model can be set within this class, which is also held in a
 * static variable. In this case a very simple ModelFactory is created that simply
 * returns that Model. This provides even more flexibility when a dependency
 * injection framework is used, allowing the Model itself to be provided in such
 * a way. This works only when the Model is the same for all tool executions.
 * <p>
 * As a convenience, if no ModelFactory or Model is set, the class identified by
 * the org.azyva.dragom.DefaultModelFactory system property is used or
 * {@link DefaultModelFactory} if not specified.
 * <p>
 * {@link Util#applyDragomSystemProperties} is called during initialization of this
 * class.
 *
 * @author David Raymond
 */
public class ModelFactoryHolder {
  /**
   * System property specifying the name of the default {@link ModelFactory}
   * implementation class to use.
   */
  private static final String SYS_PROPERTY_DEFAULT_MODEL_FACTORY = "org.azyva.dragom.DefaultModelFactory";

  /**
   * {@link ModelFactory} set with {@link #setModelFactory} which
   * should be returned.
   */
  private static ModelFactory modelFactory;

  /**
   * {@link Model} set with {@link #setModel} for which a simple factory returning
   * that Model should be returned.
   */
  private static Model model;

  /**
   * Simple {@link ModelFactory} returned when a Model is set with
   * {@link #setModel}.
   */
  private static ModelFactory modelFactoryForLocalModel = new ModelFactory() {
    @Override
    public Model getModel(Properties propertiesInit) {
      return ModelFactoryHolder.model;
    }
  };

  // Ensures that the Dragom properties are loaded into the system properties.
  static {
    Util.applyDragomSystemProperties();
  }

  /**
   * Sets the {@link ModelFactory}.
   *
   * @param modelFactory See description.
   */
  public static void setModelFactory(ModelFactory modelFactory) {
    ModelFactoryHolder.modelFactory = modelFactory;
  }

  /**
   * Sets the {@link Model}.
   *
   * @param model See description.
   */
  public static void setModel(Model model) {
    ModelFactoryHolder.model = model;
  }

  /**
   * @return {@link ModelFactory} set with {@link #setModelFactory} or the default
   *   ModelFactory if none has been set.
   */
  public static ModelFactory getModelFactory() {
    if (ModelFactoryHolder.modelFactory != null) {
      return ModelFactoryHolder.modelFactory;
    } else if (ModelFactoryHolder.model != null) {
      return ModelFactoryHolder.modelFactoryForLocalModel;
    } else {
      String modelFactoryClass;

      modelFactoryClass = System.getProperty(ModelFactoryHolder.SYS_PROPERTY_DEFAULT_MODEL_FACTORY);

      if (modelFactoryClass != null) {
        try {
          Class<? extends ModelFactory> classModelFactory;

          classModelFactory = Class.forName(modelFactoryClass).asSubclass(ModelFactory.class);
          return classModelFactory.newInstance();
        } catch (ClassNotFoundException | IllegalAccessException | InstantiationException e) {
          throw new RuntimeException(e);
        }
      } else {
        ModelFactoryHolder.modelFactory = new DefaultModelFactory();
      }
    }

    return ModelFactoryHolder.modelFactory;
  }
}
