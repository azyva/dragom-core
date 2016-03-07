/*
 * Copyright 2015, 2016 AZYVA INC.
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
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.azyva.dragom.model.Model;
import org.azyva.dragom.model.ModelFactory;
import org.azyva.dragom.model.config.impl.xml.XmlConfig;
import org.azyva.dragom.model.impl.simple.SimpleModel;

/**
 * Default {@link ModelFactory} implementation that loads a {@link Model} from
 * configuration stored in an XML resource identified by an arbitrary URL.
 * {@link XmlConfig} is used to read the XML resource and {@link SimpleModel} is
 * used as the Model implementation.
 * <p>
 * A static Map of URL to Model instances is used in order to reuse Model
 * instances. This is useful in case a single JVM instance is used for multiple
 * tool executions (<a href="http://www.martiansoftware.com/nailgun/">NaigGun<a>
 * can be useful in that regard).
 * <p>
 * The model URL is defined by the initialization property property
 * org.azyva.dragom.UrlModel which must be defined (if this class is used as the
 * ModelFactory).
 *
 * @author David Raymond
 */
public class DefaultModelFactory implements ModelFactory {
	/**
	 * Initialization property specifying the model URL.
	 */
	private static final String URL_MODEL_INIT_PROP = "org.azyva.dragom.UrlModel";

	/**
	 * Map of URLs (of {@link XmlConfig} XML configuration) to Model.
	 */
	private static Map<URL, Model> mapUrlXmlConfigModel = new HashMap<URL, Model>();

	@Override
	public Model getModel(Properties propertiesInit) {
		String stringUrlXmlConfig;
		Model model;

		stringUrlXmlConfig = System.getProperty(DefaultModelFactory.URL_MODEL_INIT_PROP);

		if (stringUrlXmlConfig == null) {
			throw new RuntimeException("Initialization property " + DefaultModelFactory.URL_MODEL_INIT_PROP + " is not defined.");
		}

		model = DefaultModelFactory.mapUrlXmlConfigModel.get(stringUrlXmlConfig);

		if (model == null) {
			URL urlXmlConfig;
			XmlConfig xmlConfig;

			try {
				urlXmlConfig = new URL(stringUrlXmlConfig);
			} catch (MalformedURLException mue) {
				throw new RuntimeException(mue);
			}

			xmlConfig = XmlConfig.load(urlXmlConfig);
			model = new SimpleModel(xmlConfig);

			DefaultModelFactory.mapUrlXmlConfigModel.put(urlXmlConfig, model);
		}

		return model;
	}
}
