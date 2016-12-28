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

package org.azyva.dragom.model.plugin.impl;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.regex.Matcher;

import org.azyva.dragom.execcontext.ExecContext;
import org.azyva.dragom.execcontext.support.ExecContextHolder;
import org.azyva.dragom.model.ClassificationNode;
import org.azyva.dragom.model.Model;
import org.azyva.dragom.model.ModelNodeBuilderFactory;
import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.ModuleBuilder;
import org.azyva.dragom.model.plugin.ScmPlugin;
import org.azyva.dragom.model.plugin.UndefinedDescendantNodeManagerPlugin;
import org.azyva.dragom.util.Util;
import org.azyva.dragom.util.WormFile;

/**
 * Simple implementation of {@link UndefinedDescendantNodeManagerPlugin}.
 * <p>
 * This UndefinedDescendantNodeManagerPlugin does not allow creating undefined
 * ClassificationNodes.
 * <p>
 * It allows creating {@link Module}'s and it validates their existence with their
 * {@link ScmPlugin}.
 *
 * @author David Raymond
 */
public class UndefinedDescendantNodeManagerSimpleDynamicModulePluginImpl extends ClassificationNodePluginAbstractImpl implements UndefinedDescendantNodeManagerPlugin {
  /**
   * Initialization property specifying to cache module existence in a file.
   *
   * <p>This is useful when module existence verification is costly.
   */
  private static final String INIT_PROPERTY_IND_CACHE_MODULE_EXISTENCE = "org.azyva.dragom.IndCacheModuleExistence";

  /**
   * Initialization property specifying the module existence cache file.
   *
   * <p> "~" in the value of this property is replaced by the user home directory.
   *
   * <p>If not defined (and module existence should be cache), the file
   * "dragom-module-existence" in the user home durectory is used.
   */
  private static final String INIT_PROPERTY_MODULE_EXISTENCE_CACHE_FILE = "org.azyva.dragom.ModuleExistenceCacheFile";

  /**
   * Default module existence cache file, in the user home directory.
   */
  private static final String DEFAULT_MODULE_EXISTENCE_CACHE_FILE = "dragom-module-existence-cache.properties";

  /**
   * Transient data for storing the module existence cache. It is a Properties.
   */
  private static final String TRANSIENT_DATA_MODULE_EXISTENCE_CACHE = UndefinedDescendantNodeManagerSimpleDynamicModulePluginImpl.class.getName() + ".ModuleExistenceCache";

  /**
   * Transient data for storing the module existence cache file name. This is to
   * avoid having to recompute its name each time it is needed.
   *
   * <p>This is a {@link org.azyva.dragom.util.WormFile.WormFileCache}.
   */
  private static final String TRANSIENT_DATA_MODULE_EXISTENCE_CACHE_FILE = UndefinedDescendantNodeManagerSimpleDynamicModulePluginImpl.class.getName() + ".ModuleExistenceCacheFile";

  public UndefinedDescendantNodeManagerSimpleDynamicModulePluginImpl(ClassificationNode classificationNode) {
    super(classificationNode);
  }

  @Override
  public ClassificationNode requestClassificationNode(String name) {
    /*
     * This UndefinedDescendantNodeManagerPlugin does not support dynamically creating
     * ClassificationNode's as validating the existence of a ClassificationNode is not
     * generically possible. Other implementations may be possible though.
     */
    return null;
  }

  @Override
  public Module requestModule(String name) {
    Model model;
    ModelNodeBuilderFactory modelNodeBuilderFactory;
    ModuleBuilder moduleBuilder;
    Module module;

    model = this.getClassificationNode().getModel();

    if (!(model instanceof ModelNodeBuilderFactory)) {
      return null;
    }

    modelNodeBuilderFactory = (ModelNodeBuilderFactory)model;
    moduleBuilder = modelNodeBuilderFactory.createModuleBuilder();
    moduleBuilder.setClassificationNodeParent(this.getClassificationNode());
    moduleBuilder.setName(name);
    module = moduleBuilder.getPartial();

    /*
     * At this point, the setup for the new Module is not complete as its parent does
     * not include it as a child. This is sufficient for the ScmPlugin.isModuleExists
     * method. If ever the Module is not valid, it will not be added within the parent
     * and will remain unreferenced.
     */

    if (!this.isModuleExists(module)) {
      return null;
    }

    /*
     * Here we know the Module is valid. Before returning it we must add it within the
     * parent.
     */

    return moduleBuilder.create();
  }

  /**
   * Verifies if a {@link Module} exists, using the module existence cache if
   * configured.
   *
   * @param module Module.
   * @return See description.
   */
  private boolean isModuleExists(Module module) {
    ExecContext execContext;
    Properties propertiesModuleExist;
    String moduleExistenceCacheFile;
    WormFile.WormFileCache wormFileCacheModuleExistanceCache;
    WormFile.AccessHandle accessHandle;
    String stringBoolean;
    ScmPlugin scmPlugin;

    execContext = ExecContextHolder.get();

    if (Util.isNotNullAndTrue(execContext.getInitProperty(UndefinedDescendantNodeManagerSimpleDynamicModulePluginImpl.INIT_PROPERTY_IND_CACHE_MODULE_EXISTENCE))) {
      propertiesModuleExist = (Properties)execContext.getTransientData(UndefinedDescendantNodeManagerSimpleDynamicModulePluginImpl.TRANSIENT_DATA_MODULE_EXISTENCE_CACHE);

      if (propertiesModuleExist == null) {
        moduleExistenceCacheFile = execContext.getInitProperty(UndefinedDescendantNodeManagerSimpleDynamicModulePluginImpl.INIT_PROPERTY_MODULE_EXISTENCE_CACHE_FILE);

        if (moduleExistenceCacheFile == null) {
          moduleExistenceCacheFile = System.getProperty("user.home") + '/' + UndefinedDescendantNodeManagerSimpleDynamicModulePluginImpl.DEFAULT_MODULE_EXISTENCE_CACHE_FILE;
        } else {
          moduleExistenceCacheFile = moduleExistenceCacheFile.replaceAll("~", Matcher.quoteReplacement(System.getProperty("user.home")));
        }

        wormFileCacheModuleExistanceCache = WormFile.getCache(Paths.get(moduleExistenceCacheFile));
        execContext.setTransientData(UndefinedDescendantNodeManagerSimpleDynamicModulePluginImpl.TRANSIENT_DATA_MODULE_EXISTENCE_CACHE_FILE, wormFileCacheModuleExistanceCache);

        propertiesModuleExist = new Properties();

        execContext.setTransientData(UndefinedDescendantNodeManagerSimpleDynamicModulePluginImpl.TRANSIENT_DATA_MODULE_EXISTENCE_CACHE, propertiesModuleExist);
      } else {
        wormFileCacheModuleExistanceCache = (WormFile.WormFileCache)execContext.getTransientData(UndefinedDescendantNodeManagerSimpleDynamicModulePluginImpl.TRANSIENT_DATA_MODULE_EXISTENCE_CACHE_FILE);
      }

      if (wormFileCacheModuleExistanceCache.isModified() && wormFileCacheModuleExistanceCache.isExists()) {
        accessHandle = wormFileCacheModuleExistanceCache.reserveAccess(false);

        try {
          propertiesModuleExist.clear();
          propertiesModuleExist.load(wormFileCacheModuleExistanceCache.getInputStream());
        } catch (IOException ioe) {
          throw new RuntimeException(ioe);
        } finally {
          accessHandle.release();
        }
      }

      stringBoolean = propertiesModuleExist.getProperty(module.getNodePath().toString());

      if (stringBoolean == null) {
        boolean indModuleExists;

        accessHandle = wormFileCacheModuleExistanceCache.reserveAccess(true);

        try {
          if (wormFileCacheModuleExistanceCache.isModified() && wormFileCacheModuleExistanceCache.isExists()) {
            propertiesModuleExist.clear();
            propertiesModuleExist.load(wormFileCacheModuleExistanceCache.getInputStream());
          }

          scmPlugin = module.getNodePlugin(ScmPlugin.class, null);
          indModuleExists = scmPlugin.isModuleExists();
          propertiesModuleExist.setProperty(module.getNodePath().toString(), Boolean.toString(indModuleExists));
          propertiesModuleExist.store(wormFileCacheModuleExistanceCache.getOutputStream(), null);
        } catch (IOException ioe) {
          throw new RuntimeException(ioe);
        } finally {
          accessHandle.release();
        }

        return indModuleExists;
      } else {
        return Boolean.parseBoolean(stringBoolean);
      }
    } else {
      scmPlugin = module.getNodePlugin(ScmPlugin.class, null);

      return scmPlugin.isModuleExists();
    }
  }
}
