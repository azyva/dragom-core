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

package org.azyva.dragom.model.plugin.impl;

import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.Version;
import org.azyva.dragom.model.VersionType;
import org.azyva.dragom.model.plugin.VersionClassifierPlugin;
import org.azyva.dragom.util.Util;

/**
 * Simple VersionClassifierPlugin that happens to support a wide range of
 * versioning strategies, including semantic. Here are some details:
 * <p>
 * <li>Evolution paths are not supported;</li>
 * <li>If the Version's are equal as per Version.equals, they are considered
 *     equal;</li>
 * <li>If the Version's are of different {@link VersionType}, dynamic Version's
 *     are considered greater than static Version's;</li>
 * <li>If the Version's are dynamic, they are compared lexicographically;</li>
 * <li>If the Version's are static they are expected to start with the prefix
 *     defined within the STATIC_VERSION_PREFIX model property;</li>
 * <li>If one version starts with the prefix and not the other, the one that does
 *     is considered smaller;<li>
 * <li>If no version start with the prefix, they are compared
 *     lexicographically;</li>
 * <li>If they both start with the prefix (or if no prefix is defined), they are
 *     split into tokens on "." and "-";</li>
 * <li>The tokens are considered in sequence;</li>
 * <li>If 2 tokens to be compared are numeric, they are compared numerically;</li>
 * <li>Otherwise they are compared lexicographically;</li>
 * <li>As soon as 2 tokens to be compared are not deemed equal, the comparison
 *     stops with the result of the comparison;</li>
 * <li>If 2 tokens are equal, if they are both separated from the next token with
 *     the same separator ("." or "-"), comparison continues with the following
 *     tokens;</li>
 * <li>If they are not separated with the same separator, the one which has the
 *     "." separator is deemed greater than the other one;</li>
 * <li>If one Version is out of tokens before the other, the one that has more
 *     tokens is deemed greater.</li>
 * <p>
 * Note that this resembles the comparison algorithm used by Maven (see
 * <a href="https://maven.apache.org/ref/3.3.3/maven-artifact/apidocs/org/apache/maven/artifact/versioning/ComparableVersion.html">ComparableVersion</a>)
 * but does not break tokens on transition between characters and digits, and does
 * not give special meaning to such tokens as "alpha", "beta", etc.
 *
 * @author David Raymond
 */
public class SimpleVersionClassifierPluginImpl extends ModulePluginAbstractImpl implements VersionClassifierPlugin {
	/**
	 * Model property specifying the prefix used for static {@link Version}'s.
	 */
	private static final String MODEL_PROPERTY_STATIC_VERSION_PREFIX = "STATIC_VERSION_PREFIX";

	/**
	 * Static {@link Version} prefix.
	 */
	private String staticVersionPrefix;

	public SimpleVersionClassifierPluginImpl(Module module) {
		super(module);

		this.staticVersionPrefix = module.getProperty(SimpleVersionClassifierPluginImpl.MODEL_PROPERTY_STATIC_VERSION_PREFIX);
	}

	@Override
	public int compare(Version version1, Version version2) {
		String stringVersion1;
		String stringVersion2;
		String[] arrayVersion1Token;
		String[] arrayVersion2Token;
		int index;

		if (version1.equals(version2)) {
			return 0;
		}

		if (version1.getVersionType() != version2.getVersionType()) {
			if (version1.getVersionType() == VersionType.STATIC){
				return -1;
			} else {
				return 1;
			}
		}

		if (version1.getVersionType() == VersionType.DYNAMIC) { // && (version2.getVersionType() == VersionType.DYNAMIC)
			return version1.getVersion().compareTo(version1.getVersion());
		}

		stringVersion1 = version1.getVersion();
		stringVersion2 = version2.getVersion();

		if (this.staticVersionPrefix != null) {
			boolean indVersion1Prefix;
			boolean indVersion2Prefix;

			if (version1.getVersion().startsWith(this.staticVersionPrefix)) {
				stringVersion1 = stringVersion1.substring(this.staticVersionPrefix.length());
				indVersion1Prefix = true;
			} else {
				indVersion1Prefix = false;
			}

			if (stringVersion2.startsWith(this.staticVersionPrefix)) {
				stringVersion2 = stringVersion2.substring(this.staticVersionPrefix.length());
				indVersion2Prefix = true;
			} else {
				indVersion2Prefix = false;
			}

			if (indVersion1Prefix != indVersion2Prefix) {
				if (indVersion1Prefix) {
					return -1;
				} else {
					return 1;
				}
			}

			if (!indVersion1Prefix) { // && !indVersion2Prefix
				return stringVersion1.compareTo(stringVersion2);
			}
		}

		arrayVersion1Token = stringVersion1.split("((?<=\\.|-)|(?=\\.|-))");
		arrayVersion2Token = stringVersion2.split("((?<=\\.|-)|(?=\\.|-))");
		index = 0;

		for(;;) {
			String token1;
			String token2;
			int compareResult;

			if (index >= arrayVersion1Token.length) {
				if (index > arrayVersion2Token.length) {
					return 0;
				} else {
					return 1;
				}
			} else if (index >= arrayVersion2Token.length) {
				return -1;
			}

			token1 = arrayVersion1Token[index];
			token2 = arrayVersion2Token[index];

			if (!Util.isDigits(token1) || !Util.isDigits(token2)) {
				compareResult = token1.compareTo(token2);
			} else {
				compareResult = new Integer(token1).compareTo(new Integer(token2));
			}

			if (compareResult != 0) {
				return compareResult;
			}

			index++;

			// Eat all separators, if there are multiple consecutive separators.
			for (;;) {
				if (index >= arrayVersion1Token.length) {
					if (index > arrayVersion2Token.length) {
						return 0;
					} else {
						return 1;
					}
				} else if (index >= arrayVersion2Token.length) {
					return -1;
				}

				token1 = arrayVersion1Token[index];
				token2 = arrayVersion2Token[index];

				if (token1.equals(".")) {
					if (!token2.equals(".")) {
						// token2 should be "-" here.
						return 1;
					}
				} else if (token1.equals("-")) {
					if (token2.equals(".")) {
						return -1;
					} else if (!token2.equals("-")) {
						// token2 should be "." here.
						return 1;
					}
				} else if (token2.equals(".") || token2.equals("-")) {
					return -1;
				} else {
					// Break out of this inner loop since we have regular tokens.
					break;
				}

				index++;
			}
		}
	}

	@Override
	public String getEvolutionPath(Version version) {
		return null;
	}

	@Override
	public int compareEvolutionPaths(String evolutionPath1, String evolutionPath2) {
		throw new UnsupportedOperationException();
	}

	public static void main(String[] args) {
		VersionClassifierPlugin vc;
		//Not clean. Need to disable fetching model property in constructor.
		vc = new SimpleVersionClassifierPluginImpl(null);
		System.out.println("S/1.2.3, S/1.2.3: " + vc.compare(new Version("S/1.2.3"),  new Version("S/1.2.3")));
		System.out.println("S/1.2.3, S/v-1.2.3: " + vc.compare(new Version("S/1.2.3"),  new Version("S/v-1.2.3")));
		System.out.println("S/v-1.2.3, S/v-1.2.3: " + vc.compare(new Version("S/v-1.2.3"),  new Version("S/v-1.2.3")));
		System.out.println("S/v-1.2.3, S/1.2.3: " + vc.compare(new Version("S/v-1.2.3"),  new Version("S/1.2.3")));
		System.out.println("S/v-1.2.3, S/v-1.2.4: " + vc.compare(new Version("S/v-1.2.3"),  new Version("S/v-1.2.4")));
		System.out.println("S/v-1.2.5, S/v-1.2.4: " + vc.compare(new Version("S/v-1.2.5"),  new Version("S/v-1.2.4")));
		System.out.println("S/v-1.2.3, S/v-1.2-3: " + vc.compare(new Version("S/v-1.2.3"),  new Version("S/v-1.2-3")));
		System.out.println("S/v-1.2-3, S/v-1.2.3: " + vc.compare(new Version("S/v-1.2-3"),  new Version("S/v-1.2.3")));
		System.out.println("S/v-1.10, S/v-1.2.3: " + vc.compare(new Version("S/v-1.10"),  new Version("S/v-1.2.3")));
		System.out.println("S/v-1..1, S/v-1.1: " + vc.compare(new Version("S/v-1..1"),  new Version("S/v-1.1")));
		System.out.println("S/v-1.1, S/v-1..1: " + vc.compare(new Version("S/v-1.1"),  new Version("S/v-1..1")));
		System.out.println("S/v-1.alpha.3, S/1.beta.3: " + vc.compare(new Version("S/v-1.alpha.3"),  new Version("S/1.beta.3")));
	}
}
