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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;

import org.azyva.dragom.model.Model;
import org.azyva.dragom.model.ModelFactory;
import org.azyva.dragom.model.config.impl.xml.XmlConfig;
import org.azyva.dragom.model.impl.simple.SimpleModel;
import org.azyva.dragom.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link ModelFactory} implementation that loads a {@link Model} from
 * configuration stored in an XML resource identified by an arbitrary URL.
 * {@link XmlConfig} is used to read the XML resource and {@link SimpleModel} is
 * used as the Model implementation.
 * <p>
 * A static Map of URL to Model instances is used in order to reuse Model
 * instances. This is useful in case a single JVM instance is used for multiple
 * tool executions (<a href="http://www.martiansoftware.com/nailgun/">NaigGun</a>
 * can be useful in that regard).
 * <p>
 * The model URL is defined by the initialization property URL_MODEL which must
 * be defined (if this class is used as the ModelFactory). This URL can also be a
 * simple file path, which is internally converted into an URL.
 * <p>
 * If the MODEL_CACHE_FILE initialization property is defined the XML resource is
 * cached in this file for quicker access. When the XML resource needs to be
 * accessed, its last modification timestamp is used to know if it changed and if
 * the cached version needs to be updated. If the IND_FORCE_UPDATE_CACHE_FILE is
 * true, the cache file is updated whatever its last modification timestamp.
 *
 * @author David Raymond
 */
public class DefaultModelFactory implements ModelFactory {
  /**
   * Logger for the class.
   */
  private static final Logger logger = LoggerFactory.getLogger(DefaultModelFactory.class);

  /**
   * Initialization property specifying the model URL.
   *
   * <p>As a convenience, this can also be a file path, relative or absolute.
   */
  private static final String INIT_PROP_URL_MODEL = "URL_MODEL";

  /**
   * Initialization property indicating to ignore any cached Model and instantiate a
   * new one, essentially causing a reload of the {@link XmlConfig}. This relates to
   * the in-memory cache, not the cache file.
   */
  private static final String INIT_PROPERTY_IND_IGNORE_CACHED_MODEL = "IND_IGNORE_CACHED_MODEL";

  /**
   * Initialization property specifying a file to use for caching the XML resource
   * of the Model. "~" in the value of this property is replaced by the user home
   * directory.
   */
  private static final String INIT_PROP_MODEL_CACHE_FILE = "MODEL_CACHE_FILE";

  /**
   * Initialization property indicating to force the update of the cache file for
   * the XML resource of the Model.
   */
  private static final String INIT_PROP_IND_FORCE_UPDATE_CACHE_FILE = "IND_FORCE_UPDATE_CACHE_FILE";

  /**
   * Map of URLs (of {@link XmlConfig} XML configuration) to Model.
   */
  private static Map<URL, Model> mapUrlXmlConfigModel = new HashMap<URL, Model>();

  @Override
  public Model getModel(Properties propertiesInit) {
    String stringUrlXmlConfig;
    boolean indIgnoreCachedModel;
    Model model;
    URL urlXmlConfig;
    XmlConfig xmlConfig;
    String xmlConfigCacheFile;

    stringUrlXmlConfig = propertiesInit.getProperty(DefaultModelFactory.INIT_PROP_URL_MODEL);

    if (stringUrlXmlConfig == null) {
      throw new RuntimeException("Initialization property " + DefaultModelFactory.INIT_PROP_URL_MODEL + " is not defined.");
    }

    indIgnoreCachedModel = Util.isNotNullAndTrue(propertiesInit.getProperty(DefaultModelFactory.INIT_PROPERTY_IND_IGNORE_CACHED_MODEL));

    if (indIgnoreCachedModel) {
      DefaultModelFactory.mapUrlXmlConfigModel.remove(stringUrlXmlConfig);
    }

    model = DefaultModelFactory.mapUrlXmlConfigModel.get(stringUrlXmlConfig);

    if (model != null) {
      return model;
    }

    try {
      urlXmlConfig = new URL(stringUrlXmlConfig);
    } catch (MalformedURLException mue1) {
      try {
        urlXmlConfig = Paths.get(stringUrlXmlConfig).toUri().toURL();
      } catch (MalformedURLException mue2) {
          throw new RuntimeException(mue1);
      }
    }

    DefaultModelFactory.logger.info("URL of XmlConfig: " + urlXmlConfig);

    xmlConfigCacheFile = propertiesInit.getProperty(DefaultModelFactory.INIT_PROP_MODEL_CACHE_FILE);

    if (xmlConfigCacheFile != null) {
      boolean indRefreshXmlConfigCacheFile;
      long lastModificationTimestampXmlConfig;

      xmlConfigCacheFile = xmlConfigCacheFile.replace("~", Matcher.quoteReplacement(System.getProperty("user.home")));

      indRefreshXmlConfigCacheFile = Util.isNotNullAndTrue(propertiesInit.getProperty(DefaultModelFactory.INIT_PROP_IND_FORCE_UPDATE_CACHE_FILE));

      if (!indRefreshXmlConfigCacheFile) {
        File fileXmlConfigCache;

        fileXmlConfigCache = new File(xmlConfigCacheFile);

        if (fileXmlConfigCache.isFile()) {
          long lastModificationTimestampXmlConfigCacheFile;
          URLConnection urlConnection;

          lastModificationTimestampXmlConfigCacheFile = fileXmlConfigCache.lastModified();

          try {
            urlConnection = urlXmlConfig.openConnection();
            urlConnection.setUseCaches(false);
            urlConnection.setDoInput(false);
            urlConnection.setDoOutput(false);
            lastModificationTimestampXmlConfig = urlConnection.getLastModified();

            // This seems to be the only way to properly release the resource. In particular
            // if the resource is a file (rather rare since in that case using a cache file is
            // not really usefull), the file remains open unless the InputStream is obtained
            // and closed. But since we call setDoInput(false) avoid actually opening the
            // resource InputStream if possible, calling getInputStream may not be valid. So
            // we ignore all exceptions that may be thrown.
            try {
              urlConnection.getInputStream().close();
            } catch (Exception e) {
            }
          } catch (IOException ioe) {
            throw new RuntimeException(ioe);
          }

          indRefreshXmlConfigCacheFile = lastModificationTimestampXmlConfig > lastModificationTimestampXmlConfigCacheFile;

          if (indRefreshXmlConfigCacheFile) {
            DefaultModelFactory.logger.info("XmlConfig cache file " + xmlConfigCacheFile + " stale and will be refreshed.");
          }
        } else {
          indRefreshXmlConfigCacheFile = true;

          DefaultModelFactory.logger.info("XmlConfig cache file " + xmlConfigCacheFile + " does not exist and will be creted.");
        }
      } else {
        DefaultModelFactory.logger.info("Refreshing of the XmlConfig cache file " + xmlConfigCacheFile + " is forced.");
      }

      if (indRefreshXmlConfigCacheFile) {
        URLConnection urlConnection;
        InputStream inputStreamXmlConfig;
        Path pathXmlConfigCacheFileNew;
        File fileXmlConfigCacheFile;

        inputStreamXmlConfig = null;

        try {
          urlConnection = urlXmlConfig.openConnection();
          urlConnection.setUseCaches(false);
          urlConnection.setDoInput(true);
          urlConnection.setDoOutput(false);

          // We will not have obtained the last modification timestamp above if the cache
          // file does not exist yet, and we need it to set it on the cache file after
          // getting it.
          lastModificationTimestampXmlConfig = urlConnection.getLastModified();

          inputStreamXmlConfig = urlConnection.getInputStream();

          // We get the new XmlConfig in a temporary new file so that if ever getting
          // the file fails, we do not loose the previous version.

          pathXmlConfigCacheFileNew = Paths.get(xmlConfigCacheFile + ".new");

          Files.copy(inputStreamXmlConfig, pathXmlConfigCacheFileNew, StandardCopyOption.REPLACE_EXISTING);
          Files.move(pathXmlConfigCacheFileNew, Paths.get(xmlConfigCacheFile), StandardCopyOption.REPLACE_EXISTING);
          fileXmlConfigCacheFile = new File(xmlConfigCacheFile);
          fileXmlConfigCacheFile.setLastModified(lastModificationTimestampXmlConfig);
        } catch (IOException ioe) {
          throw new RuntimeException(ioe);
        } finally {
          if (inputStreamXmlConfig != null) {
            try {
              inputStreamXmlConfig.close();
            } catch (IOException ioe) {
            }
          }
        }
      }

      try {
        urlXmlConfig = Paths.get(xmlConfigCacheFile).toUri().toURL();
      } catch (MalformedURLException mue) {
        throw new RuntimeException(mue);
      }
    }

    xmlConfig = XmlConfig.load(urlXmlConfig);
    model = new SimpleModel(xmlConfig, propertiesInit);

    DefaultModelFactory.mapUrlXmlConfigModel.put(urlXmlConfig, model);

    return model;
  }
}
