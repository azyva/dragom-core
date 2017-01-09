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

import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.azyva.dragom.model.Model;
import org.azyva.dragom.model.ModelFactory;
import org.azyva.dragom.model.config.impl.xml.XmlConfig;
import org.azyva.dragom.model.impl.simple.SimpleModel;
import org.azyva.dragom.util.Util;

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
 * The model URL is defined by the initialization property property
 * URL_MODEL which must be defined (if this class is used as the ModelFactory).
 *
 * @author David Raymond
 */
public class DefaultModelFactory implements ModelFactory {
  /**
   * Initialization property specifying the model URL.
   *
   * <p>As a convenience, this can also be a file path, relative or absolute.
   */
  private static final String INIT_PROP_URL_MODEL = "URL_MODEL";

  /**
   * Map of URLs (of {@link XmlConfig} XML configuration) to Model.
   */
  private static Map<URL, Model> mapUrlXmlConfigModel = new HashMap<URL, Model>();

  /**
   * Initialization property indicating to ignore any cached Model and instantiate a
   * new one, essentially causing a reload of the {@link XmlConfig}.
   */
  private static final String INIT_PROPERTY_IND_IGNORE_CACHED_MODEL = "IND_IGNORE_CACHED_MODEL";

  @Override
  public Model getModel(Properties propertiesInit) {
    String stringUrlXmlConfig;
    boolean indIgnoreCachedModel;
    Model model;

    stringUrlXmlConfig = propertiesInit.getProperty(DefaultModelFactory.INIT_PROP_URL_MODEL);

    if (stringUrlXmlConfig == null) {
      throw new RuntimeException("Initialization property " + DefaultModelFactory.INIT_PROP_URL_MODEL + " is not defined.");
    }

    indIgnoreCachedModel = Util.isNotNullAndTrue(propertiesInit.getProperty(DefaultModelFactory.INIT_PROPERTY_IND_IGNORE_CACHED_MODEL));

    if (indIgnoreCachedModel) {
      DefaultModelFactory.mapUrlXmlConfigModel.remove(stringUrlXmlConfig);
    }

    model = DefaultModelFactory.mapUrlXmlConfigModel.get(stringUrlXmlConfig);

    if (model == null) {
      URL urlXmlConfig;
      XmlConfig xmlConfig;

      try {
        urlXmlConfig = new URL(stringUrlXmlConfig);
      } catch (MalformedURLException mue1) {
        try {
          urlXmlConfig = Paths.get(stringUrlXmlConfig).toUri().toURL();
        } catch (MalformedURLException mue2) {
            throw new RuntimeException(mue1);
        }
      }

      xmlConfig = XmlConfig.load(urlXmlConfig);
      model = new SimpleModel(xmlConfig, propertiesInit);

      DefaultModelFactory.mapUrlXmlConfigModel.put(urlXmlConfig, model);
    }

    return model;
  }
}
