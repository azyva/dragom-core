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

package org.azyva.dragom.model.plugin.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Formatter;
import java.util.List;

import org.azyva.dragom.execcontext.support.ExecContextHolder;
import org.azyva.dragom.execcontext.plugin.RuntimePropertiesPlugin;
import org.azyva.dragom.execcontext.plugin.UserInteractionCallbackPlugin;
import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.Version;
import org.azyva.dragom.model.VersionType;
import org.azyva.dragom.model.plugin.ScmPlugin;
import org.azyva.dragom.util.AlwaysNeverAskUserResponse;
import org.azyva.dragom.util.Util;
import org.azyva.dragom.util.YesAlwaysNoUserResponse;

/**
 * Base class for NewStaticVersionPlugin that provide useful helper methods that
 * are common to many such plugins.
 *
 * @author David Raymond
 */

public class NewStaticVersionPluginBaseImpl extends ModulePluginAbstractImpl {
	private static final String RUNTIME_PROPERTY_SPECIFIC_STATIC_VERSION = "SPECIFIC_STATIC_VERSION";
	private static final String RUNTIME_PROPERTY_CAN_REUSE_EXISTING_STATIC_VERSION = "CAN_REUSE_EXISTING_STATIC_VERSION";
	private static final String RUNTIME_PROPERTY_SPECIFIC_STATIC_VERSION_PREFIX = "SPECIFIC_STATIC_VERSION_PREFIX";
	private static final String RUNTIME_PROPERTY_REVISION_DECIMAL_POSITION_COUNT = "REVISION_DECIMAL_POSITION_COUNT";

	private int initialRevision;
	private int defaultRevisionDecimalPositionCount;

	public NewStaticVersionPluginBaseImpl(Module module) {
		super(module);
	}

	protected void setInitialRevision(int initialRevision) {
		this.initialRevision = initialRevision;
	}

	protected void setDefaultRevisionDecimalPositionCount(int defaultRevisionDecimalPositionCount) {
		this.defaultRevisionDecimalPositionCount = defaultRevisionDecimalPositionCount;
	}

	/**
	 * Validates that a Version is dynamic.
	 *
	 * @param versionDynamic Version that is supposed to be dynamic.
	 */
	protected void validateVersionDynamic(Version versionDynamic) {
		if (versionDynamic.getVersionType() != VersionType.DYNAMIC) {
			// This should not happen since this method is not supposed to be called on static
			// version. It is the responsibility of the caller to validate user parameters and to
			// invoke only on dynamic versions.
			throw new RuntimeException("Version " + versionDynamic + " of module " + this.getModule() + " is not dynamic.");
		}
	}

	/**
	 * Handles the case where a specific static version is specified for the module.
	 *
	 * @param versionDynamic Dynamic version.
	 * @return Specific static Version. null if none specified.
	 */
	protected Version handleSpecificStaticVersion(Version versionDynamic) {
		RuntimePropertiesPlugin runtimePropertiesPlugin;
		UserInteractionCallbackPlugin userInteractionCallbackPlugin;
		String stringSpecificStaticVersion;
		Version versionNewStatic;

		runtimePropertiesPlugin = ExecContextHolder.get().getExecContextPlugin(RuntimePropertiesPlugin.class);
		userInteractionCallbackPlugin = ExecContextHolder.get().getExecContextPlugin(UserInteractionCallbackPlugin.class);

		if (versionDynamic.getVersionType() != VersionType.DYNAMIC) {
			// This should not happen since this method is not supposed to be called on static
			// version. It is the responsibility of the caller to validate user parameters and to
			// invoke only on dynamic versions.
			throw new RuntimeException("Version " + versionDynamic + " of module " + this.getModule() + " is not dynamic.");
		}

		// We first check if a specific static version is specified for the module.

		stringSpecificStaticVersion = runtimePropertiesPlugin.getProperty(this.getModule(), NewStaticVersionPluginBaseImpl.RUNTIME_PROPERTY_SPECIFIC_STATIC_VERSION);

		if (stringSpecificStaticVersion != null) {
			versionNewStatic = new Version(stringSpecificStaticVersion);

			if (versionNewStatic.getVersionType() != VersionType.STATIC) {
				throw new RuntimeException("Version " + versionNewStatic + " must be static.");
			}

			userInteractionCallbackPlugin.provideInfo("The specific static version " + versionNewStatic + " is specified for the module " + this.getModule() + ". It is used as is.");
			return versionNewStatic;
		} else {
			return null;
		}
	}

	/**
	 * Handles the case where an existing static Version exists that is equivalent to
	 * the dynamic Version.
	 *
	 * When getting a new static version for the current dynamic version, it may be
	 * pertinent to verify if there is already a static Version corresponding to the
	 * current dynamic Version. This is important since if the process is restarted by
	 * the user after having been aborted after some Version creations have been
	 * performed, we need to make it as if we were continuing from where we left off,
	 * and reusing existing static Version that may have been created during the
	 * previous execution does the trick.
	 *
	 * @param versionDynamic Dynamic Version.
	 * @return Existing static Version. null if none.
	 */
	protected Version handleExistingEquivalentStaticVersion(Version versionDynamic) {
		RuntimePropertiesPlugin runtimePropertiesPlugin;
		UserInteractionCallbackPlugin userInteractionCallbackPlugin;
		AlwaysNeverAskUserResponse alwaysNeverAskUserResponseCanReuseExistingStaticVersion;
		YesAlwaysNoUserResponse yesAlwaysNoUserResponse;

		runtimePropertiesPlugin = ExecContextHolder.get().getExecContextPlugin(RuntimePropertiesPlugin.class);
		userInteractionCallbackPlugin = ExecContextHolder.get().getExecContextPlugin(UserInteractionCallbackPlugin.class);

		alwaysNeverAskUserResponseCanReuseExistingStaticVersion = AlwaysNeverAskUserResponse.valueOfWithAskDefault(runtimePropertiesPlugin.getProperty(this.getModule(), NewStaticVersionPluginBaseImpl.RUNTIME_PROPERTY_CAN_REUSE_EXISTING_STATIC_VERSION));

		if (!alwaysNeverAskUserResponseCanReuseExistingStaticVersion.isNever()) {
			Version versionEquivalentStatic;

			versionEquivalentStatic = this.getVersionEquivalentStatic(versionDynamic);

			if (versionEquivalentStatic != null) {
				if (alwaysNeverAskUserResponseCanReuseExistingStaticVersion.isAlways()) {
					userInteractionCallbackPlugin.provideInfo("The existing static version " + versionEquivalentStatic + " is automatically reused for module " + this.getModule() + '.');
					return versionEquivalentStatic;
				}

				// Here, alwaysNeverAskUserResponseCanReuseExistingVersion is necessarily ASK.

				yesAlwaysNoUserResponse = Util.getInfoYesNoUserResponse(userInteractionCallbackPlugin, "Do you want to reuse the existing static version " + versionEquivalentStatic + " for module " + this.getModule() + "*", YesAlwaysNoUserResponse.YES);

				alwaysNeverAskUserResponseCanReuseExistingStaticVersion =
						Util.getInfoAlwaysNeverAskUserResponseAndHandleAsk(
								runtimePropertiesPlugin,
								NewStaticVersionPluginBaseImpl.RUNTIME_PROPERTY_CAN_REUSE_EXISTING_STATIC_VERSION,
								userInteractionCallbackPlugin,
								"Do you want to apply that response (automatically reuse the existing static version or not) for all subsequent modules for which a new static version needs to be created*",
								AlwaysNeverAskUserResponse.ALWAYS);

				// This is the user response from the first question above, not about
				// automatically reusing the response.
				if (yesAlwaysNoUserResponse.isYes()) {
					return versionEquivalentStatic;
				}
			}
		}

		return null;
	}

	/**
	 * Handles the case where a specific static version prefix is specified for the
	 * module.
	 *
	 * @param versionDynamic Dynamic Version.
	 * @return Specific static Version prefix.
	 */
	protected Version handleSpecificStaticVersionPrefix(Version versionDynamic) {
		RuntimePropertiesPlugin runtimePropertiesPlugin;
		String stringStaticVersionPrefix;
		UserInteractionCallbackPlugin userInteractionCallbackPlugin;

		runtimePropertiesPlugin = ExecContextHolder.get().getExecContextPlugin(RuntimePropertiesPlugin.class);
		userInteractionCallbackPlugin = ExecContextHolder.get().getExecContextPlugin(UserInteractionCallbackPlugin.class);

		stringStaticVersionPrefix = runtimePropertiesPlugin.getProperty(this.getModule(), NewStaticVersionPluginBaseImpl.RUNTIME_PROPERTY_SPECIFIC_STATIC_VERSION_PREFIX);

		if (stringStaticVersionPrefix != null) {
			userInteractionCallbackPlugin.provideInfo("The specific static version prefix " + stringStaticVersionPrefix + " is specified for the module " + this.getModule() + ". It is used as is.");
			return new Version(stringStaticVersionPrefix);
		}

		return null;
	}

	/**
	 * Gets a new static Version based on a prefix.
	 *
	 * @param versionDynamic Dynamic Version.
	 * @param versionStaticPrefix Static version prefix.
	 * @return New static Version.
	 */
	protected Version getNewStaticVersionFromPrefix(Version versionDynamic, Version versionStaticPrefix) {
		RuntimePropertiesPlugin runtimePropertiesPlugin;
		ScmPlugin scmPlugin;
		String runtimeProperty;
		int revisionDecimalPositionCount;
		String revisionFormat;
		Version versionLatestStatic;
		int revision;
		Formatter formatter;
		Version versionNewStatic;

		runtimePropertiesPlugin = ExecContextHolder.get().getExecContextPlugin(RuntimePropertiesPlugin.class);
		scmPlugin = this.getModule().getNodePlugin(ScmPlugin.class, null);

		runtimeProperty = runtimePropertiesPlugin.getProperty(this.getModule(), NewStaticVersionPluginBaseImpl.RUNTIME_PROPERTY_REVISION_DECIMAL_POSITION_COUNT);

		if (runtimeProperty == null) {
			revisionDecimalPositionCount = this.defaultRevisionDecimalPositionCount;
		} else {
			revisionDecimalPositionCount = Integer.parseInt(runtimeProperty);
		}

		if (revisionDecimalPositionCount == 0) {
			revisionFormat = "%d";
		} else {
			revisionFormat = "%0" + revisionDecimalPositionCount + "d";
		}

		versionLatestStatic = this.getVersionLatestMatchingVersionStaticPrefix(versionDynamic, versionStaticPrefix);

		if (versionLatestStatic == null) {
			revision = this.initialRevision;
		} else {
			String suffix;

			suffix = versionLatestStatic.getVersion().substring(versionStaticPrefix.getVersion().length());

			revision = -1;

			if (suffix.matches("\\.\\d+")) {
				try {
					revision = Integer.parseInt(suffix.substring(1));
				} catch (NumberFormatException nfe) {
				}
			}

			if (revision == -1) {
				throw new RuntimeException("The suffix " + suffix + " of the latest static version " + versionLatestStatic + " is not in the format \".<decimal revision>\".");
			}

			if (revisionDecimalPositionCount != 0) {
				if (revision == Math.pow(10, revisionDecimalPositionCount) - 1) {
					throw new RuntimeException("The suffix " + suffix + " of the latest static version " + versionLatestStatic + " is already the maximum revision allowed.");
				}
			}

			revision++;

		}

		formatter = new Formatter();
		formatter.format(revisionFormat, new Integer(revision));
		versionNewStatic = new Version(VersionType.STATIC, versionStaticPrefix.getVersion() + '.' + formatter.out().toString());
		formatter.close();

		if (scmPlugin.isVersionExists(versionNewStatic)) {
			throw new RuntimeException("New static version " + versionNewStatic + " already exists for module " + this.getModule() + '.');
		}

		return versionNewStatic;
	}

	/**
	 * Gets the static Version that is equivalent to a dynamic Version.
	 *
	 * @param versionDynamic Dynamic Version.
	 * @return Equivalent static Version. null if there is no equivalent static
	 *   Version.
	 */
	private Version getVersionEquivalentStatic(Version versionDynamic) {
		ScmPlugin scmPlugin;
		List<ScmPlugin.Commit> listCommit;
		String stringEquivalentStaticVersion;
		Version versionEquivalentStatic;

		// In order to check for an existing static version corresponding to the current
		// dynamic version we consider the message of the last commit of the current
		// dynamic version which, if there is a corresponding static version, will contain
		// the special attribute equivalent-static-version at the beginning. This attribute
		// will have been included when creating the static Version to revert the changes
		// to the ArtifactVersion.
		// This is necessary since an existing static version will generally not correspond
		// to the last commit of a dynamic version because of that revert commit.
		// This algorithm is valid for modules which store ArtifactVersion within their
		// build script (Maven). In other cases, a revert commit may not be required, nor a
		// commit for adjusting the ArtifactVersion in the first place. In such a case we
		// revert to the list of static Version on the current commit..

		scmPlugin = this.getModule().getNodePlugin(ScmPlugin.class, null);

		listCommit = scmPlugin.getListCommit(versionDynamic, new ScmPlugin.CommitPaging(1), EnumSet.of(ScmPlugin.GetListCommitFlagEnum.IND_INCLUDE_MAP_ATTR, ScmPlugin.GetListCommitFlagEnum.IND_INCLUDE_VERSION_STATIC));

		if (!listCommit.isEmpty()) {
			ScmPlugin.Commit commit;

			commit = listCommit.get(0);

			stringEquivalentStaticVersion = commit.mapAttr.get(ScmPlugin.COMMIT_ATTR_EQUIVALENT_STATIC_VERSION);

			if (stringEquivalentStaticVersion != null) {
				versionEquivalentStatic = new Version(stringEquivalentStaticVersion);

				if (versionEquivalentStatic.getVersionType() != VersionType.STATIC) {
					throw new RuntimeException("Version " + versionEquivalentStatic + " must be static.");
				}
			} else if (commit.arrayVersionStatic.length >= 1) {
				versionEquivalentStatic = commit.arrayVersionStatic[0];
			} else {
				versionEquivalentStatic = null;
			}
		} else {
			versionEquivalentStatic = null;
		}

		return versionEquivalentStatic;
	}

	/**
	 * Gets the latest static Version matching a static Version prefix created on a
	 * dynamic Version.
	 *
	 * @param versionDynamic Dynamic Version.
	 * @param versionStaticPrefix Static Version prefix.
	 * @return Latest matching static Version or null if none.
	 */
	protected Version getVersionLatestMatchingVersionStaticPrefix(Version versionDynamic, Version versionStaticPrefix) {
		List<Version> listVersionStatic;

		listVersionStatic = this.getListVersionStatic(versionDynamic);

		for (Version version: listVersionStatic) {
			if (version.getVersion().startsWith(versionStaticPrefix.getVersion())) {
				return version;
			}
		}

		return null;
	}

	/**
	 * Gets the list of static Version created on a dynamic Version, with the most
	 * recent ones first.
	 *
	 * @param versionDynamic Dynamic Version.
	 * @return List of static Version.
	 */
	protected List<Version> getListVersionStatic(Version versionDynamic) {
		ScmPlugin scmPlugin;
		List<ScmPlugin.Commit> listCommit;
		List<Version> listVersionStatic;

		scmPlugin = this.getModule().getNodePlugin(ScmPlugin.class, null);
		listVersionStatic = new ArrayList<Version>();
		listCommit = scmPlugin.getListCommit(versionDynamic, null, EnumSet.of(ScmPlugin.GetListCommitFlagEnum.IND_INCLUDE_VERSION_STATIC));

		for (ScmPlugin.Commit commit: listCommit) {
			listVersionStatic.addAll(Arrays.asList(commit.arrayVersionStatic));
		}

		return listVersionStatic;

	}
}
