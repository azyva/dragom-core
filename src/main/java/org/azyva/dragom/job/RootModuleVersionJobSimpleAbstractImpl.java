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

import java.util.List;

import org.azyva.dragom.execcontext.plugin.RuntimePropertiesPlugin;
import org.azyva.dragom.execcontext.support.ExecContextHolder;
import org.azyva.dragom.model.ClassificationNode;
import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.ModuleVersion;
import org.azyva.dragom.model.Version;
import org.azyva.dragom.model.plugin.ScmPlugin;
import org.azyva.dragom.reference.ReferenceGraph;
import org.azyva.dragom.reference.ReferencePath;
import org.azyva.dragom.reference.ReferencePathMatcher;
import org.azyva.dragom.reference.ReferencePathMatcherAll;
import org.azyva.dragom.reference.ReferencePathMatcherAnd;
import org.azyva.dragom.reference.ReferencePathMatcherVersionAttribute;
import org.azyva.dragom.util.ModuleReentryAvoider;

/**
 * Base class for implementing jobs based on root {@link ModuleVersion}'s.
 *
 * <p>It does not implement any generic traversal of the reference graphs rooted
 * at this ModuleVersion's. {@link RootModuleVersionJobAbstractImpl} does. This
 * class only provide the basic setters and getters.
 *
 * <p>It factors out code that is often encountered in these types of tasks.
 *
 * <p>This class does not attempt to completely encapsulate its implementation. It
 * has protected instance variables available to subclasses to simplify
 * implementation.
 *
 * <p>This class implements RootModuleVersionJob so that subclasses can be used with
 * GenericRootModuleVersionJobInvokerTool from dragom-cli-tools.
 *
 * @author David Raymond
 */
public abstract class RootModuleVersionJobSimpleAbstractImpl implements RootModuleVersionJob {
  /**
   * Runtime property specifying a project code which many job honour when
   * traversing {@link ReferenceGraph}'s.
   * <p>
   * This provides a matching mechanism for {@link ModuleVersion}'s within a
   * ReferenceGraph. It also has impacts on {@link Version} creations.
   * <p>
   * The idea is that in some cases, Dragom will be used to manage ReferenceGraph's
   * in the context of a project, in the sense of a time-constrained development
   * effort. When switching to dynamic Version's of {@link Module}'s,
   * (@link SwitchToDynamicVesion} can optionnally specify a project code as a
   * Version attribute for newly created dynamic Versions. Similarly with
   * {@link Release} for static Versions. And for may other jobs which traverse
   * a ReferenceGraph ({@link Checkout}, {@link MergeMain}, etc.), this same project
   * code specified by this runtime property is used for matching
   * {@link ModuleVersion}'s based on their Version's project code attribute, in
   * addition to the matching performed by {@link ReferencePathMatcher}'s
   * (implied "and").
   * <p>
   * Accessed on the root {@link ClassificationNode}.
   */
  // TODO: Eventually this may be handled with generic expression-language-based matchers.
  protected static final String RUNTIME_PROPERTY_PROJECT_CODE = "PROJECT_CODE";

  /**
   * List root ModuleVersion's on which to initiate the traversal of the reference
   * graphs.
   */
  protected List<ModuleVersion> listModuleVersionRoot;

  /**
   * ReferencePathMatcher defining on which ModuleVersion's in the reference graphs
   * the job will be applied.
   */
  private ReferencePathMatcher referencePathMatcherProvided;

  /**
   * ReferencePathMatcher for filtering based on the project code.
   */
  private ReferencePathMatcher referencePathMatcherProjectCode;

  /**
   * "and"-combined {@link ReferencePathMatcher} for
   * {@link #referencePathMatcherProvided} and
   * {@link #referencePathMatcherProjectCode}.
   * <p>
   * Calculated once the first time it is used and cached in this variable
   * afterward.
   */
  private ReferencePathMatcher referencePathMatcherCombined;

  /**
   * Indicates that dynamic {@link Version}'s must be considered during the
   * traversal of the reference graphs. The default value is true.
   */
  protected boolean indHandleDynamicVersion = true;

  /**
   * Indicates that static {@link Version}'s must be considered during the
   * traversal of the reference graphs. The default value is true.
   */
  protected boolean indHandleStaticVersion = true;

  /**
   * Indicates to avoid reentry by using {@link ModuleReentryAvoider}.
   *
   * <p>The default value is true.
   */
  protected boolean indAvoidReentry = true;

  /**
   * {@link ModuleReentryAvoider}.
   * <p>
   * Used by this class when matching {@link ReferencePath} if
   * {@link #indAvoidReentry}. Available to subclasses as well, independently
   * of indAvoidReentry.
   */
  protected ModuleReentryAvoider moduleReentryAvoider;

  /**
   * Indicates that traversal must be depth first, as opposed to parent first.
   */
  protected boolean indDepthFirst;

  /**
   * Specifies the behavior related to unsynchronized local changes.
   */
  protected UnsyncChangesBehavior unsyncChangesBehaviorLocal;

  /**
   * Specifies the behavior related to unsynchronized remote changes.
   */
  protected UnsyncChangesBehavior unsyncChangesBehaviorRemote;

  /**
   * Subclasses can use this variable during the traversal of a reference graph to
   * maintain the current ReferencePath being visited. Upon entry in a method that
   * visits a ModuleVersion, this variable represents the ReferencePath of the
   * parent. During processing it is modified to represent the ReferencePath of the
   * current ModuleVersion and referenced modules as the graph is traversed. Upon
   * exit it is reset to what it was upon entry.
   *
   * This is used mainly in messages since it is useful for the user to know from
   * which Reference a ModuleVersion within a ReferencePath comes from. A root
   * ModuleVersion is wrapped in a Reference.
   */
  protected ReferencePath referencePath;

  /**
   * Indicates that the List of root {@link ModuleVersion} passed to the constructor
   * was changed and should be saved by the caller if persisted.
   */
  protected boolean indListModuleVersionRootChanged;

  /**
   * Constructor.
   *
   * @param listModuleVersionRoot List of root ModuleVersion's on which to initiate
   *   the traversal of the reference graphs.
   */
  protected RootModuleVersionJobSimpleAbstractImpl(List<ModuleVersion> listModuleVersionRoot) {
    this.listModuleVersionRoot = listModuleVersionRoot;

    this.moduleReentryAvoider = new ModuleReentryAvoider();

    this.unsyncChangesBehaviorLocal = UnsyncChangesBehavior.DO_NOT_HANDLE;
    this.unsyncChangesBehaviorRemote = UnsyncChangesBehavior.DO_NOT_HANDLE;

    this.referencePath = new ReferencePath();
  }

  @Override
  public void setReferencePathMatcherProvided(ReferencePathMatcher referencePathMatcherProvided) {
    this.referencePathMatcherProvided = referencePathMatcherProvided;
  }

  /**
   * @return The project code specified by the user with the PROJECT_CODE runtime
   *   property.
   */
  protected String getProjectCode() {
    return ExecContextHolder.get().getExecContextPlugin(RuntimePropertiesPlugin.class).getProperty(null, RootModuleVersionJobSimpleAbstractImpl.RUNTIME_PROPERTY_PROJECT_CODE);
  }

  /**
   * Setup the {@link ReferencePathMatcher} so that only {@link ModuleVersion}'s
   * having the {@link Version} attribute dragom-project-code equal to that
   * defined by the runtime property PROJECT_CODE are matched.
   */
  protected void setupReferencePathMatcherForProjectCode() {
    String projectCode;

    projectCode = this.getProjectCode();

    if (projectCode != null) {
      this.referencePathMatcherProjectCode = new ReferencePathMatcherVersionAttribute(ScmPlugin.VERSION_ATTR_PROJECT_CODE, projectCode, ExecContextHolder.get().getModel());
    }
  }

  /**
   * @return ReferencePathMatcher to use for matching the {@link ReferencePath}'s.
   *   "and" combination of the ReferencePathMatcher provided with
   *   {@link #setReferencePathMatcherProvided} and that setup by
   *   {@link #setupReferencePathMatcherForProjectCode}.
   */
  protected ReferencePathMatcher getReferencePathMatcher() {
    if (this.referencePathMatcherCombined == null) {
      if ((this.referencePathMatcherProvided != null) && (this.referencePathMatcherProjectCode != null)) {
        ReferencePathMatcherAnd referencePathMatcherAnd;

        referencePathMatcherAnd = new ReferencePathMatcherAnd();

        referencePathMatcherAnd.addReferencePathMatcher(this.referencePathMatcherProvided);
        referencePathMatcherAnd.addReferencePathMatcher(this.referencePathMatcherProjectCode);

        this.referencePathMatcherCombined = referencePathMatcherAnd;
      } else if (this.referencePathMatcherProvided != null) {
        this.referencePathMatcherCombined = this.referencePathMatcherProvided;
      } else if (this.referencePathMatcherProjectCode != null) {
        // This case if rather rare since tools generally require the user to specify a
        // ReferencePathMatcher.
        this.referencePathMatcherCombined = this.referencePathMatcherProjectCode;
      } else {
        // If absolutely no ReferencePathMatcher is specified, which is also rare, we
        // match everything.
        this.referencePathMatcherCombined = new ReferencePathMatcherAll();
      }
    }

    return this.referencePathMatcherCombined;
  }

  /**
   * @param indHandleDynamicVersion Specifies to handle or not dynamic
   *   {@link Version}'s. The default is to handle dynamic {@link Version}.
   */
  protected void setIndHandleDynamicVersion(boolean indHandleDynamicVersion) {
    this.indHandleDynamicVersion = indHandleDynamicVersion;
  }

  /**
   * @param indHandleStaticVersion Specifies to handle or not static
   *   {@link Version}'s. The default is to handle static {@link Version}.
   */
  protected void setIndHandleStaticVersion(boolean indHandleStaticVersion) {
    this.indHandleStaticVersion = indHandleStaticVersion;
  }

  /**
   * @param indAvoidReentry Specifies to avoid reentry by using
   *   {@link ModuleReentryAvoider}. The default is to avoid reentry.
   */
  protected void setIndAvoidReentry(boolean indAvoidReentry) {
    this.indAvoidReentry = indAvoidReentry;
  }

  /**
   * @param indDepthFirst Specifies to traverse depth first. The default is to
   *   traverse parent-first.
   */
  protected void setIndDepthFirst(boolean indDepthFirst) {
    this.indDepthFirst = indDepthFirst;
  }

  @Override
  public void setUnsyncChangesBehaviorLocal(UnsyncChangesBehavior unsyncChangesBehaviorLocal) {
    this.unsyncChangesBehaviorLocal = unsyncChangesBehaviorLocal;
  }

  @Override
  public void setUnsyncChangesBehaviorRemote(UnsyncChangesBehavior unsyncChangesBehaviorRemote) {
    this.unsyncChangesBehaviorRemote = unsyncChangesBehaviorRemote;
  }

  /**
   * Called by methods of this class or subclasses to indicate that the List of root
   * {@link ModuleVersion} passed to the constructor was changed and should be
   * saved by the caller if persisted.
   */
  protected void setIndListModuleVersionRootChanged() {
    this.indListModuleVersionRootChanged = true;
  }

  @Override
  public boolean isListModuleVersionRootChanged() {
    return this.indListModuleVersionRootChanged;
  }
}
