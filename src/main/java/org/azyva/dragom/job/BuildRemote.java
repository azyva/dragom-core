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

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;

import org.azyva.dragom.execcontext.ExecContext;
import org.azyva.dragom.execcontext.WorkspaceExecContext;
import org.azyva.dragom.execcontext.plugin.RuntimePropertiesPlugin;
import org.azyva.dragom.execcontext.plugin.UserInteractionCallbackPlugin;
import org.azyva.dragom.execcontext.support.ExecContextHolder;
import org.azyva.dragom.model.ClassificationNode;
import org.azyva.dragom.model.Model;
import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.ModuleVersion;
import org.azyva.dragom.model.NodePath;
import org.azyva.dragom.model.VersionType;
import org.azyva.dragom.model.plugin.RemoteBuilderPlugin;
import org.azyva.dragom.reference.Reference;
import org.azyva.dragom.reference.ReferenceGraph;
import org.azyva.dragom.reference.ReferencePath;
import org.azyva.dragom.util.Util;

/**
 * Build job that uses the RemoteBuilder plugin and implicitly supports
 * parallelism.
 * <p>
 * In order to support parallelism, {@link BuildReferenceGraph} is used to build a
 * {@link ReferenceGraph} and then manage the submitting of the builds in the
 * right order. Multiple independent builds can be submitted simultaneously,
 * letting the remote build system manage the scheduling. Submitted builds are
 * monitored and new builds are submitted until all {@link ModuleVersion}'s
 * needing to be built are built.
 * <p>
 * Since BuildReferenceGraph is used, this class derives from
 * {@link RootModuleVersionJobAbstractImpl}, but performs its job by overriding
 * {@link RootModuleVersionJobAbstractImpl#performJob} and delegating to
 * {@link BuildReferenceGraph.performJob} to build the ReferenceGraph before
 * performing the rest of the processing.
 * <p>
 * Although this class requires a workspace in order to build the ReferenceGraph,
 * it is not actually used to perform the builds. The notion of build scope as
 * supported by {@link Build} is therefore not implemented and all matched
 * ModuleVersion's are built.
 * <p>
 * However this class allows building all ModuleVersion's within the
 * ReferencePath's to the matched ModuleVersion's, on top of the matched ones.
 * This allows to easily request building a ModuleVersion as well as all other
 * ModuleVersion's that depend directly or indirectly on it.
 * <p>
 * When intermediate ModuleVersion's in the ReferencePath's to the matched
 * ModuleVersions's are not also built, the submission of multiple builds
 * simultaneously will not honor the transitive relation between the
 * ModuleVersion's, meaning that in a ReferencePath A -> B -> C, if A and C are
 * matched but not B, A and C will be submitted simultaneously. A will not wait
 * for the completion of C before being submitted.
 *
 * @author David Raymond
 */
public class BuildRemote extends RootModuleVersionJobAbstractImpl {
  /**
   * Runtime property of type boolean that specifies whether to also build
   * ModuleVersion's within the ReferencePath's to the matched ModuleVersion's. The
   * default value is false.
   * <p>
   * A runtime property is used so that it is possible to set a default in a context
   * that is more global than the tool invocation. But often this runtime property
   * will be provided by the user as a tool initialization property for each tool
   * invocation.
   * <p>
   * It is accessed in the context of each {@link Module} so that its value can be
   * different for each Module. But in general it will be defined for the root
   * NodePath only.
   */
  public static final String RUNTIME_PROPERTY_IND_BUILD_REFERENCE_PATH = "IND_BUILD_REFERENCE_PATH";

  /**
   * Runtime property that specifies the delay between build monitoring cycles, in
   * milliseconds. Accessed on the root NodePath.
   * <p>
   * When the RemoteBuilderPlugin is invoked to submit a build, the monitoring of
   * the build status is performed by querying the RemoteBuildHandle object that
   * the plugin returned. This typically involves communication with the remote
   * build system and must be paced correctly to balance the need for fast feedback
   * and the desire to avoid overwhelming the remote build system with requests.
   */
  public static final String RUNTIME_PROPERTY_BUILD_MONITORING_CYCLE_DELAY = "BUILD_MONITORING_CYCLE_DELAY";

  /**
   * Runtime property that specifies the directory for build logs.
   * <p>
   * If not specified, build logs will be extracted to the root of the workspace
   * directory.
   * <p>
   * If relative, it is relative to the current directory.
   * <p>
   * It is accessed in the context of each {@link Module} so that its value can be
   * different for each Module. But in general it will be defined for the root
   * NodePath only.
   */
  public static final String RUNTIME_PROPERTY_BUILD_LOG_DIR = "BUILD_LOG_DIR";

  /**
   * Runtime property that specifies if the {@link NodePath} of the {@link Module}
   * {@link ClassificationNode} should be included in build log file names. The
   * default value is false.
   * <p>
   * It is accessed in the context of each {@link Module} so that its value can be
   * different for each Module. But in general it will be defined for the root
   * NodePath only.
   */
  public static final String RUNTIME_PROPERTY_IND_INCLUDE_NODE_PATH_IN_BUILD_LOG_FILE_NAMES = "INCLUDE_NODE_PATH_IN_BUILD_LOG_FILE_NAMES";

  /**
   * Runtime property that specifies if the {@link Version} of the
   * {@link ModuleVersion} should be included in build log file names. The default
   * value is false.
   * <p>
   * It is the version string that is included (excluding the {@link VersionType}).
   * <p>
   * It is accessed in the context of each {@link Module} so that its value can be
   * different for each Module. But in general it will be defined for the root
   * NodePath only.
   */
  public static final String RUNTIME_PROPERTY_IND_INCLUDE_VERSION_IN_BUILD_LOG_FILE_NAMES = "INCLUDE_VERSION_IN_BUILD_LOG_FILE_NAMES";

  /**
   * Suffix for build log file names.
   */
  public static final String BUILD_LOG_FILE_NAMES_SUFFIX = ".log";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_BUILD_SUBMITTED = "BUILD_SUBMITTED";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_MODULE_VERSION_DOES_NOT_NEED_BUILDING = "MODULE_VERSION_DOES_NOT_NEED_BUILDING";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_MODULE_VERSION_CANNOT_BUILD_REMOTELY = "MODULE_VERSION_CANNOT_BUILD_REMOTELY";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_BUILD_CHANGED_STATE = "BUILD_CHANGED_STATE";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_BUILD_SUCCEEDED = "BUILD_SUCCEEDED";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_BUILD_FAILED = "BUILD_FAILED";

  /**
   * ResourceBundle specific to this class.
   */
  private static final ResourceBundle resourceBundle = ResourceBundle.getBundle(BuildRemote.class.getName() + "ResourceBundle");

  /**
   * Holds a {@link RemoteBuilderPlugin.RemoteBuildHandle} as well as extra data
   * needed to manage the builds and avoid useless queries for
   * {@link RemoteBuilderPlugin.RemoteBuildHandle.RemoteBuildStatus}.
   */
  private static class RemoteBuildWrapper {
    /**
     * {@link RemoteBuilderPlugin.RemoteBuildHandle}.
     */
    public RemoteBuilderPlugin.RemoteBuildHandle remoteBuildHandle;

    /**
     * {@link RemoteBuilderPlugin.RemoteBuildHandle.RemoteBuildStatus} obtained
     * from the {@link RemoteBuilderPlugin.RemoteBuildHandle} during the last
     * monitoring cycle.
     */
    public RemoteBuilderPlugin.RemoteBuildHandle.RemoteBuildStatus remoteBuildStatusPrevious;

    /**
     * {@link RemoteBuilderPlugin.RemoteBuildHandle.RemoteBuildStatus} obtained
     * from the {@link RemoteBuilderPlugin.RemoteBuildHandle} during the current
     * monitoring cycle.
     */
    public RemoteBuilderPlugin.RemoteBuildHandle.RemoteBuildStatus remoteBuildStatusNew;

    /**
     * Returns the {@link RemoteBuilderPlugin.RemoteBuildHandle.RemoteBuildStatus}.
     * <p>
     * It uses remoteBuildStatusNew as a cache for the RemoteBuildStatus. reset is
     * expected to be called at the end of each monitoring cycle to copy it to
     * remoteBuildStatusPrevious and set it to null.
     *
     * @return RemoteBuilderPlugin.RemoteBuildHandle.RemoteBuildStatus.
     */
    public RemoteBuilderPlugin.RemoteBuildHandle.RemoteBuildStatus getRemoteBuildStatus() {
      if (this.remoteBuildStatusNew == null) {
        this.remoteBuildStatusNew = this.remoteBuildHandle.getRemoteBuildStatus();
      }

      return this.remoteBuildStatusNew;
    }

    /**
     * @return Indicates if the {@link RemoteBuilderPlugin.RemoteBuildHandle}
     *   changed state.
     */
    public boolean isChangedState() {
      return (this.getRemoteBuildStatus() != this.remoteBuildStatusPrevious);
    }

    /**
     * Copies remoteBuildStatusNew to remoteBuildStatusPrevious and sets the
     * former to null.
     * <p>
     * This is expected to be called at the end of each monitoring cycle.
     */
    public void reset() {
      if (this.remoteBuildStatusNew == null) {
        throw new RuntimeException("Must not get here.");
      }

      this.remoteBuildStatusPrevious = this.remoteBuildStatusNew;
    }
  }

  /**
   * Constructor.
   *
   * @param listModuleVersionRoot List of root ModuleVersion's on which to initiate
   *   the traversal of the reference graphs.
   */
  public BuildRemote(List<ModuleVersion> listModuleVersionRoot) {
    super(listModuleVersionRoot);

    this.setupReferencePathMatcherForProjectCode();
  }

  @Override
  public void performJob() {
    BuildReferenceGraph buildReferenceGraph;
    ReferenceGraph referenceGraph;
    ExecContext execContext;
    RuntimePropertiesPlugin runtimePropertiesPlugin;
    UserInteractionCallbackPlugin userInteractionCallbackPlugin;
    Model model;
    int buildMonitoringCycleDelay;
    boolean indStillBuilding;

    // Contains a RemoteBuildWrapper for each build that has been submitted. It can
    // also contain dummy null entries for ModuleVersion's that are not matched by
    // the ReferencePathMatcher, but are part of ReferencePath's to matched
    // ModuleVersion's, if these intermediate ModuleVersion's are not to be built
    // (the value of the runtime property IND_BUILD_REFERENCE_PATH is false). The
    // presence of such entries means that these ModuleVersions can be assumed to
    // have been built so that parent ModuleVersion's can be. Dummy null entries
    // are also inserted for ModuleVersion's that do not need to be built (have
    // already been built).
    Map<ModuleVersion, RemoteBuildWrapper> mapRemoteBuildWrapper;

    buildReferenceGraph = new BuildReferenceGraph(null, this.listModuleVersionRoot);
    buildReferenceGraph.setReferencePathMatcherProvided(this.getReferencePathMatcher());
    buildReferenceGraph.performJob();
    referenceGraph = buildReferenceGraph.getReferenceGraph();

    execContext = ExecContextHolder.get();
    runtimePropertiesPlugin = execContext.getExecContextPlugin(RuntimePropertiesPlugin.class);
    userInteractionCallbackPlugin = execContext.getExecContextPlugin(UserInteractionCallbackPlugin.class);
    model = execContext.getModel();

    buildMonitoringCycleDelay = Integer.parseInt(runtimePropertiesPlugin.getProperty(null, BuildRemote.RUNTIME_PROPERTY_BUILD_MONITORING_CYCLE_DELAY));

    indStillBuilding = true;

    // We use a LinkedHashMap to preserver build monitoring order to avoid user
    // confusion.
    mapRemoteBuildWrapper = new LinkedHashMap<ModuleVersion, RemoteBuildWrapper>();

    do {
      /* ********************************************************************************
       * First submit all builds that can be submitted.
       * ********************************************************************************/

      referenceGraph.traverseReferenceGraph(
          null, // Traverse all root ModuleVersion's.
          true, // Depth first.
          ReferenceGraph.ReentryMode.NO_REENTRY,
          new ReferenceGraph.Visitor() {
            @Override
            public ReferenceGraph.VisitControl visit(ReferenceGraph referenceGraph, ReferencePath referencePath, EnumSet<ReferenceGraph.VisitAction> enumSetVisitAction) {
              ModuleVersion moduleVersion;
              Module module;
              boolean indBuildReferencePath;

              if (!enumSetVisitAction.contains(ReferenceGraph.VisitAction.VISIT)) {
                return ReferenceGraph.VisitControl.CONTINUE;
              }

              moduleVersion = referencePath.getLeafModuleVersion();
              module = model.getModule(moduleVersion.getNodePath());

              // If the ModuleVersion has already been taken care of, continue. Note that as
              // builds are performed, the re-traversal of the ReferenceGraph will cause more
              // and more occurrences of this case, wasting a bit of time. But we do not expect
              // the ReferenceGraph to be so huge that this is significant. Ideally, entries in
              // the ReferenceGraph that have already been taken care of could be removed to
              // avoid revisiting them.
              // TODO: Maybe implement the optimization above. The entries cannot be removed
              // immediately after having been submitted. They remain useful while the builds
              // are running. And what about the dummy entries that do not correspond to actual
              // builds? Removing them can create holes in the ReferenceGraph. Maybe that is not
              // a problem.
              if (mapRemoteBuildWrapper.containsKey(moduleVersion)) {
                return ReferenceGraph.VisitControl.CONTINUE;
              }

              indBuildReferencePath = Util.isNotNullAndTrue(runtimePropertiesPlugin.getProperty(module, BuildRemote.RUNTIME_PROPERTY_IND_BUILD_REFERENCE_PATH));

              if (enumSetVisitAction.contains(ReferenceGraph.VisitAction.MATCHED) || indBuildReferencePath) {
                boolean indReferenceBuildNotCompletedAndSuccess;
                List<Reference> listReference;

                // We start by assuming that the build of all references has been completed with
                // success and look for a reference that would prove otherwise.
                indReferenceBuildNotCompletedAndSuccess = false;

                listReference = referenceGraph.getListReference(moduleVersion);

                for (Reference reference: listReference) {
                  ModuleVersion moduleVersionReference;
                  RemoteBuildWrapper remoteBuildWrapperReference;

                  moduleVersionReference = reference.getModuleVersion();
                  remoteBuildWrapperReference = mapRemoteBuildWrapper.get(moduleVersionReference);

                  if (remoteBuildWrapperReference == null) {
                    if (mapRemoteBuildWrapper.containsKey(moduleVersionReference)) {
                      // If this is the dummy entry (null, but exists), we do as if the referenced ModuleVersion had
                      // already been built.
                      continue;
                    } else {
                      // If there is no entry for the referenced ModuleVersion, it means that its build
                      // has not been scheduled yet (it depends on the build of some other
                      // ModuleVersion.
                      indReferenceBuildNotCompletedAndSuccess = true;
                      break;
                    }
                  } else {
                    if (   (remoteBuildWrapperReference.getRemoteBuildStatus() != RemoteBuilderPlugin.RemoteBuildHandle.RemoteBuildStatus.COMPLETED)
                        && (remoteBuildWrapperReference.getRemoteBuildStatus() != RemoteBuilderPlugin.RemoteBuildHandle.RemoteBuildStatus.CANNOT_BUILD_REMOTELY)) {

                      // If the build of the referenced ModuleVersion is not completed, there is no
                      // point building the parent ModuleVersion.
                      indReferenceBuildNotCompletedAndSuccess = true;
                      break;
                    }

                    // Here, we know the build of the referenced ModuleVersion is either completed or
                    // could not be submitted.

                    if (   (remoteBuildWrapperReference.getRemoteBuildStatus() == RemoteBuilderPlugin.RemoteBuildHandle.RemoteBuildStatus.CANNOT_BUILD_REMOTELY)
                      || !remoteBuildWrapperReference.remoteBuildHandle.isSuccess()) {

                      // If the build of the referenced ModuleVersion is not completed with success, we
                      // cannot build the parent. The actual failure will be handled below.
                      indReferenceBuildNotCompletedAndSuccess = true;
                      break;
                    }
                  }
                }

                // If there are no reference whose build is not completed and successful, this
                // implies that the builds of all references are completed and successful and
                // therefore we can submit the build for the current ModuleVersion.
                if (!indReferenceBuildNotCompletedAndSuccess) {
                  RemoteBuilderPlugin remoteBuilderPlugin;

                  remoteBuilderPlugin = module.getNodePlugin(RemoteBuilderPlugin.class, null);

                  if (remoteBuilderPlugin.isBuildNeeded(moduleVersion.getVersion())) {
                    RemoteBuildWrapper remoteBuildWrapper;

                    remoteBuildWrapper = new RemoteBuildWrapper();

                    remoteBuildWrapper.remoteBuildHandle = remoteBuilderPlugin.submitBuild(moduleVersion.getVersion());

                    userInteractionCallbackPlugin.provideInfo(MessageFormat.format(BuildRemote.resourceBundle.getString(BuildRemote.MSG_PATTERN_KEY_BUILD_SUBMITTED), moduleVersion, remoteBuildWrapper.remoteBuildHandle.getLocation()));

                    mapRemoteBuildWrapper.put(moduleVersion, remoteBuildWrapper);
                  } else {
                    userInteractionCallbackPlugin.provideInfo(MessageFormat.format(BuildRemote.resourceBundle.getString(BuildRemote.MSG_PATTERN_KEY_MODULE_VERSION_DOES_NOT_NEED_BUILDING), moduleVersion));

                    // If the ModuleVersion does not need to be built (has already been built), we
                    // insert a dummy entry to communicate that fact.
                    mapRemoteBuildWrapper.put(moduleVersion, null);
                  }
                }
              } else {
                // When not matched but ModuleVersion's in ReferencePath are not included, we
                // insert a dummy entry to tell us to do as if the ModuleVersion had already been
                // built.
                mapRemoteBuildWrapper.put(moduleVersion,  null);
              }

              return ReferenceGraph.VisitControl.CONTINUE;
            }
          });

      /* ********************************************************************************
       * Next handle the builds that change state and complete.
       * ********************************************************************************/

      for (RemoteBuildWrapper remoteBuildWrapper: mapRemoteBuildWrapper.values()) {
        ModuleVersion moduleVersion;
        Module module;

        moduleVersion = remoteBuildWrapper.remoteBuildHandle.getModuleVersion();
        module = model.getModule(moduleVersion.getNodePath());

        // We are only interested in builds whose state has changed. For newly submitted
        // builds, remoteBuildWrapper.remoteBuildStatusPrevious will be null and will
        // necessarily have changed in the first monitoring cycle, so that its initial
        // state will be shown to the user.
        if (remoteBuildWrapper.isChangedState()) {
          String runtimeProperty;
          Path pathBuildLogDir;
          boolean indIncludeNodePathInBuildLogFileNames;
          boolean indIncludeVersionInBuildLogFileNames;
          String buildLogFileName;
          Path pathBuildLogFile;
          File fileBuildLog;
          Writer writerLog;

          switch (remoteBuildWrapper.getRemoteBuildStatus()) {
          case CANNOT_BUILD_REMOTELY:
            userInteractionCallbackPlugin.provideInfo(MessageFormat.format(BuildRemote.resourceBundle.getString(BuildRemote.MSG_PATTERN_KEY_MODULE_VERSION_CANNOT_BUILD_REMOTELY), moduleVersion, remoteBuildWrapper.remoteBuildHandle.getCannotBuildRemotelyReason()));
            break;
          case QUEUED:
          case RUNNING:
            userInteractionCallbackPlugin.provideInfo(MessageFormat.format(BuildRemote.resourceBundle.getString(BuildRemote.MSG_PATTERN_KEY_BUILD_CHANGED_STATE), moduleVersion, remoteBuildWrapper.remoteBuildHandle.getLocation(), remoteBuildWrapper.getRemoteBuildStatus()));
            break;
          case COMPLETED:
            runtimeProperty = runtimePropertiesPlugin.getProperty(module, BuildRemote.RUNTIME_PROPERTY_BUILD_LOG_DIR);

            if (runtimeProperty == null) {
              pathBuildLogDir = ((WorkspaceExecContext)execContext).getPathWorkspaceDir();
            } else {
              pathBuildLogDir = Paths.get(runtimeProperty);
            }

            indIncludeNodePathInBuildLogFileNames = Util.isNotNullAndTrue(runtimePropertiesPlugin.getProperty(module, BuildRemote.RUNTIME_PROPERTY_IND_INCLUDE_NODE_PATH_IN_BUILD_LOG_FILE_NAMES));
            indIncludeVersionInBuildLogFileNames = Util.isNotNullAndTrue(runtimePropertiesPlugin.getProperty(module, BuildRemote.RUNTIME_PROPERTY_IND_INCLUDE_VERSION_IN_BUILD_LOG_FILE_NAMES));

            if (indIncludeNodePathInBuildLogFileNames) {
              buildLogFileName = moduleVersion.getNodePath().getPropertyNameSegment();
            } else {
              buildLogFileName = moduleVersion.getNodePath().getModuleName();
            }

            if (indIncludeVersionInBuildLogFileNames) {
              buildLogFileName = buildLogFileName + '-' + moduleVersion.getVersion().getVersion();
            }

            for (int i = 0;; i++) {
              pathBuildLogFile = pathBuildLogDir.resolve(buildLogFileName + (i == 0 ? "" : " (" + i + ")") + BuildRemote.BUILD_LOG_FILE_NAMES_SUFFIX);
              fileBuildLog = pathBuildLogDir.toFile();

              if (!fileBuildLog.exists()) {
                break;
              }
            }

            try {
              writerLog = new BufferedWriter(new FileWriter(fileBuildLog));
              remoteBuildWrapper.remoteBuildHandle.getLog(writerLog);
              writerLog.close();
            } catch (IOException ioe) {
              throw new RuntimeException(ioe);
            }

            if (remoteBuildWrapper.remoteBuildHandle.isSuccess()) {
              userInteractionCallbackPlugin.provideInfo(MessageFormat.format(BuildRemote.resourceBundle.getString(BuildRemote.MSG_PATTERN_KEY_BUILD_SUCCEEDED), moduleVersion, remoteBuildWrapper.remoteBuildHandle.getLocation(), pathBuildLogFile));
            } else {
              userInteractionCallbackPlugin.provideInfo(MessageFormat.format(BuildRemote.resourceBundle.getString(BuildRemote.MSG_PATTERN_KEY_BUILD_FAILED), moduleVersion, remoteBuildWrapper.remoteBuildHandle.getLocation(), pathBuildLogFile));
            }
          }

          // If the state of at least one build has changed, even if the state of all builds
          // is CANNOT_BUILD_REMOTELY or COMPLETED, there may be new builds that can be
          // submitted. There may be cases where we could conclude that this cannot happen,
          // such as if all builds are completed except one that changed state to
          // CANNOT_BUILD_REMOTELY. But we do not take any chances and play it safe.
          indStillBuilding = true;
        } else if (   (remoteBuildWrapper.getRemoteBuildStatus() == RemoteBuilderPlugin.RemoteBuildHandle.RemoteBuildStatus.QUEUED)
                   || (remoteBuildWrapper.getRemoteBuildStatus() == RemoteBuilderPlugin.RemoteBuildHandle.RemoteBuildStatus.RUNNING)) {

          // If the sate of the build has not changed, but it is still active (QUEUED or
          // RUNNING) then we are still building.
          indStillBuilding = true;
        }

        remoteBuildWrapper.reset();
      }

      if (indStillBuilding) {
        try {
          Thread.sleep(buildMonitoringCycleDelay);
        } catch (InterruptedException ie) {
          throw new RuntimeException(ie);
        }
      }
    } while (indStillBuilding);
  }
}
