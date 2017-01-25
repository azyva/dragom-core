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

package org.azyva.dragom.job;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;

import org.azyva.dragom.execcontext.ExecContext;
import org.azyva.dragom.execcontext.support.ExecContextHolder;
import org.azyva.dragom.model.Model;
import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.ModuleVersion;
import org.azyva.dragom.model.NodePath;
import org.azyva.dragom.model.plugin.ScmPlugin;
import org.azyva.dragom.reference.ReferencePathMatcher;
import org.azyva.dragom.reference.ReferencePathMatcherByElement;
import org.azyva.dragom.reference.ReferencePathMatcherOr;
import org.azyva.dragom.util.RuntimeExceptionUserError;

/**
 * Class for managing root ModuleVersion's and the global ReferencePathMatcherOr
 * within the ExecContext.
 *
 * The root ModuleVersion's are used by many tools to establish the
 * ModuleVersion's on which the tool will operate. These root ModuleVersion's
 * are generally used by tools when the user does not specify a specific root
 * when invoking the tool.
 *
 * The global ReferencePathMatcherOr is also used by many tools to restrict the
 * ReferencePath's visited while traversing the reference graphs rooted at the
 * root ModuleVersion's. This ReferencePathMatcherOr is generally used within a
 * ReferencePathMatcherAnd so that a ReferencePath is visited if it is matched
 * both by this global ReferencePathMatcherOr and another ReferencePathMatcherOr
 * provided when invoking a tool.
 *
 * This class is not really a job but is a helper class to help manage roots
 * for tasks and the RootManagerTool.
 *
 * All methods are static since roots are defined globally within an ExecContext.
 *
 * @author David Raymond
 */
public class RootManager {
  /**
   * Prefix for the ExecContext properties that hold the list of root
   * ModuleVersion's.
   *
   * <p>Each root ModuleVersion is defined with a property having this prefix plus
   * an incremental numeric suffix.
   */
  private static final String EXEC_CONTEXT_PROPERTY_PREFIX_ROOT_MODULE_VERSION = "ROOT_MODULE_VERSIONS.";

  /**
   * Prefix of the properties that hold the global ReferencePathMatcherOr.
   *
   * This prefix suffixed with a 1-based index specifies a
   * ReferencePathMatcherByElement.
   *
   * The ReferencePathMatcherByElement's are represented with their literal form.
   */
  private static final String EXEC_CONTEXT_PROPERTY_PREFIX_REFERENCE_PATH_MATCHER = "REFERENCE_PATH_MATCHER.";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_MODULE_DOES_NOT_EXIST = "MODULE_DOES_NOT_EXIST";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_VERSION_DOES_NOT_EXIST = "VERSION_DOES_NOT_EXIST";

  /**
   * ResourceBundle specific to this class.
   */
  private static final ResourceBundle resourceBundle = ResourceBundle.getBundle(RootManager.class.getName() + "ResourceBundle");

  /**
   * @return List of root {@link ModuleVersion}'s.
   */
  @SuppressWarnings("unchecked")
  public static List<ModuleVersion> getListModuleVersion() {
    ExecContext execContext;
    List<ModuleVersion> listModuleVersion;

    execContext = ExecContextHolder.get();

    listModuleVersion = (List<ModuleVersion>)execContext.getTransientData(RootManager.class.getName() + ".ListModuleVersion");

    if (listModuleVersion == null) {
      int index;

      listModuleVersion = new ArrayList<ModuleVersion>();

      index = 1;

      do {
        String rootModuleVersion;

        rootModuleVersion = execContext.getProperty(RootManager.EXEC_CONTEXT_PROPERTY_PREFIX_ROOT_MODULE_VERSION + String.format("%03d", index));

        if (rootModuleVersion != null) {
          listModuleVersion.add(new ModuleVersion(rootModuleVersion));
        } else {
          index = 0;
        }
      } while (index++ != 0);

      execContext.setTransientData(RootManager.class.getName() + ".ListModuleVersion", listModuleVersion);
    }

    return listModuleVersion;
  }

  /**
   * Verifies if a ModuleVersion is contained in the list of ModuleVersion.
   *
   * @param moduleVersion ModuleVersion.
   * @return See description.
   */
  public static boolean containsModuleVersion(ModuleVersion moduleVersion) {
    return RootManager.getListModuleVersion().contains(moduleVersion);
  }

  /**
   * Returns the ModuleVersion corresponding to a NodePath or null
   * if none.
   *
   * Useful for managing the list when duplicate modules are not allowed.
   *
   * @param nodePathModule NodePath of the ModuleVersion.
   * @return See description.
   */
  public static ModuleVersion getModuleVersion(NodePath nodePathModule) {
    List<ModuleVersion> listModuleVersion;

    listModuleVersion = RootManager.getListModuleVersion();

    for (ModuleVersion moduleVersion: listModuleVersion) {
      if (moduleVersion.getNodePath().equals(nodePathModule)) {
        return moduleVersion;
      }
    }

    return null;
  }

  /**
   * Adds a ModuleVersion to the list of root ModuleVersion.
   *
   * If a ModuleVersion for the same Module is already a root and indAllowDuplicate
   * is false, the ModuleVersion is not added. replaceModuleVersion must be called in
   * that case.
   *
   * @param moduleVersion New root ModuleVersion.
   * @param indAllowDuplicateModule Indicates if duplicate Module are allowed.
   * @return Indicates if the ModuleVersion was actually added (it maybe a
   *   duplicate).
   */
  public static boolean addModuleVersion(ModuleVersion moduleVersion, boolean indAllowDuplicateModule) {
    List<ModuleVersion> listModuleVersion;

    //TODO: Probably should remove alltogether. Wastes time and not really useful.
    //RootManager.validateModuleVersion(moduleVersion);

    listModuleVersion = RootManager.getListModuleVersion();

    if (listModuleVersion.contains(moduleVersion)) {
      return false;
    }

    if (!indAllowDuplicateModule) {
      for (int i = 0; i < listModuleVersion.size(); i++) {
        if (listModuleVersion.get(i).getNodePath().equals(moduleVersion.getNodePath())) {
          return false;
        }
      }
    }

    listModuleVersion.add(moduleVersion);
    RootManager.saveListModuleVersion();

    return true;
  }

  /**
   * Remove a ModuleVersion from the list of root ModuleVersion.
   *
   * @param moduleVersion ModuleVersion to remove.
   * @return Indicates if the ModuleVersion was actually removed (it may not be
   *   present in the list).
   */
  public static boolean removeModuleVersion(ModuleVersion moduleVersion) {
    List<ModuleVersion> listModuleVersion;

    listModuleVersion = RootManager.getListModuleVersion();

    if (!listModuleVersion.contains(moduleVersion)) {
      return false;
    }

    listModuleVersion.remove(moduleVersion);
    RootManager.saveListModuleVersion();

    return true;
  }

  /**
   * Remove all ModuleVersion's from the list of root ModuleVersion.
   */
  public static void removeAllModuleVersion() {
    RootManager.getListModuleVersion().clear();
    RootManager.saveListModuleVersion();
  }

  /**
   * Replaces a ModuleVersion with a new one (presumably having the same Module but
   * a different Version) in the list of root ModuleVersion.
   *
   * @param moduleVersionOrg Original ModuleVersion.
   * @param moduleVersionNew New ModuleVersion.
   * @return Indicates if the ModuleVersion was actually replaced (it may not be
   *   present in the list).
   */
  public static boolean replaceModuleVersion(ModuleVersion moduleVersionOrg, ModuleVersion moduleVersionNew) {
    List<ModuleVersion> listModuleVersion;

    //TODO: Probably should remove alltogether. Wastes time and not really useful.
    //RootManager.validateModuleVersion(moduleVersionNew);

    listModuleVersion = RootManager.getListModuleVersion();

    if (!listModuleVersion.contains(moduleVersionOrg)) {
      return false;
    }

    listModuleVersion.set(listModuleVersion.indexOf(moduleVersionOrg), moduleVersionNew);
    RootManager.saveListModuleVersion();

    return true;
  }

  /**
   * Moves a ModuleVersion first in the list of root ModuleVersion.
   *
   * @param moduleVersion ModuleVersion to move first.
   * @return Indicates if the ModuleVersion was actually moved (it may not be
   *   present in the list).
   */
  public static boolean moveFirst(ModuleVersion moduleVersion) {
    List<ModuleVersion> listModuleVersion;

    listModuleVersion = RootManager.getListModuleVersion();

    if (!listModuleVersion.contains(moduleVersion)) {
      return false;
    }

    listModuleVersion.remove(moduleVersion);
    listModuleVersion.add(0, moduleVersion);
    RootManager.saveListModuleVersion();

    return true;
  }

  /**
   * Moves a ModuleVersion last in the list of root ModuleVersion.
   *
   * @param moduleVersion ModuleVersion to move last.
   * @return Indicates if the ModuleVersion was actually moved (it may not be
   *   present in the list).
   */
  public static boolean moveLast(ModuleVersion moduleVersion) {
    List<ModuleVersion> listModuleVersion;

    listModuleVersion = RootManager.getListModuleVersion();

    if (!listModuleVersion.contains(moduleVersion)) {
      return false;
    }

    listModuleVersion.remove(moduleVersion);
    listModuleVersion.add(listModuleVersion.size(), moduleVersion);
    RootManager.saveListModuleVersion();

    return true;
  }

  /**
   * Validates a ModuleVersion.
   *
   * @param moduleVersion ModuleVersion.
   */
  public static void validateModuleVersion(ModuleVersion moduleVersion) {
    ExecContext execContext;
    Model model;
    Module module;
    ScmPlugin scmPlugin;

    execContext = ExecContextHolder.get();
    model = execContext.getModel();
    module = model.getModule(moduleVersion.getNodePath());

    if (module == null) {
      throw new RuntimeExceptionUserError(MessageFormat.format(RootManager.resourceBundle.getString(RootManager.MSG_PATTERN_KEY_MODULE_DOES_NOT_EXIST), moduleVersion));
    }

    scmPlugin = module.getNodePlugin(ScmPlugin.class, null);

    // We assume the default version, when getVersion returns null, always exists.
    if (moduleVersion.getVersion() != null) {
      if (!scmPlugin.isVersionExists(moduleVersion.getVersion())) {
        throw new RuntimeExceptionUserError(MessageFormat.format(RootManager.resourceBundle.getString(RootManager.MSG_PATTERN_KEY_VERSION_DOES_NOT_EXIST), moduleVersion));
      }
    }
  }

  /**
   * Persists the list of root ModuleVersion's in the ExecContext.
   *
   * This class does not provide a safe encapsulation of the List of root
   * ModuleVersion's. If the caller wants to modify the List other than with the
   * methods provided by this class, it is expected to obtain it with the
   * getListModuleVersion method and then call this method after having modified
   * it. Is it not possible for the caller to create a new List and set it as
   * the List of root ModuleVesion's within the class.
   */
  public static void saveListModuleVersion() {
    List<ModuleVersion> listModuleVersion;
    ExecContext execContext;
    int index;

    execContext = ExecContextHolder.get();
    listModuleVersion = RootManager.getListModuleVersion();

    execContext.removeProperties(RootManager.EXEC_CONTEXT_PROPERTY_PREFIX_ROOT_MODULE_VERSION);

    index = 1;

    for (ModuleVersion moduleVersion: listModuleVersion) {
      execContext.setProperty(RootManager.EXEC_CONTEXT_PROPERTY_PREFIX_ROOT_MODULE_VERSION + String.format("%03d", index++), moduleVersion.toString());
    }
  }

  /**
   * Returns the global ReferencePathMatcherOr.
   *
   * If the caller modifies the ReferencePathMatcherOr returned it should call the
   * setReferencePathMatcherOr to ensure that it is persisted within the
   * ExecContext.
   *
   * @return See description.
   */
  public static ReferencePathMatcherOr getReferencePathMatcherOr() {
    ExecContext execContext;
    ReferencePathMatcherOr referencePathMatcherOr;

    execContext = ExecContextHolder.get();

    referencePathMatcherOr = (ReferencePathMatcherOr)execContext.getTransientData(RootManager.class.getName() + ".ReferencePathMatcherOr");

    if (referencePathMatcherOr == null) {
      int index;

      referencePathMatcherOr = new ReferencePathMatcherOr();

      index = 1;

      do {
        String stringReferencePathMatcherByElement;

        stringReferencePathMatcherByElement = execContext.getProperty(RootManager.EXEC_CONTEXT_PROPERTY_PREFIX_REFERENCE_PATH_MATCHER + String.format("%03d", index));

        if (stringReferencePathMatcherByElement != null) {
            referencePathMatcherOr.addReferencePathMatcher(new ReferencePathMatcherByElement(stringReferencePathMatcherByElement, execContext.getModel()));
        } else {
            index = 0;
        }
      } while (index++ != 0);

      execContext.setTransientData(RootManager.class.getName() + ".ReferencePathMatcherOr", referencePathMatcherOr);
    }

    return referencePathMatcherOr;
  }

  /**
   * Persists the ReferencePathMatcherOr within the ExecContext.
   *
   * This class does not provide a safe encapsulation of the ReferencePathMatcherOr.
   * If the caller wants to modify the ReferencePathMatcherOr other than with the
   * methods provided by this class, it is expected to obtain it with the
   * getReferencePathMatcherOr method and then call this method after having
   * modified it. Is it not possible for the caller to create a new
   * ReferencePathMatcherOr and set it as the global ReferencePathMatcherOr within
   * the class.
   *
   * Also, only ReferencePathMatcherByElement are supported in this List.
   */
  public static void saveReferencePathMatcherOr() {
    ReferencePathMatcherOr referencePathMatcherOr;
    ExecContext execContext;
    int index;

    referencePathMatcherOr = RootManager.getReferencePathMatcherOr();

    execContext = ExecContextHolder.get();

    execContext.removeProperties(RootManager.EXEC_CONTEXT_PROPERTY_PREFIX_REFERENCE_PATH_MATCHER);

    index = 1;

    for (ReferencePathMatcher referencePathMatcher: referencePathMatcherOr.getListReferencePathMatcher()) {
      execContext.setProperty(RootManager.EXEC_CONTEXT_PROPERTY_PREFIX_REFERENCE_PATH_MATCHER + String.format("%03d", index++), ReferencePathMatcherByElement.class.cast(referencePathMatcher).toString());
    }
  }
}
