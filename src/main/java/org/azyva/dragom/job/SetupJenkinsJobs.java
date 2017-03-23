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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;

import org.azyva.dragom.execcontext.ExecContext;
import org.azyva.dragom.execcontext.WorkspaceExecContext;
import org.azyva.dragom.execcontext.plugin.CredentialStorePlugin;
import org.azyva.dragom.execcontext.plugin.RuntimePropertiesPlugin;
import org.azyva.dragom.execcontext.plugin.UserInteractionCallbackPlugin;
import org.azyva.dragom.execcontext.support.ExecContextHolder;
import org.azyva.dragom.jenkins.JenkinsClient;
import org.azyva.dragom.model.ClassificationNode;
import org.azyva.dragom.model.Model;
import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.ModuleVersion;
import org.azyva.dragom.model.Version;
import org.azyva.dragom.model.VersionType;
import org.azyva.dragom.model.plugin.JenkinsJobInfoPlugin;
import org.azyva.dragom.reference.ReferenceGraph;
import org.azyva.dragom.reference.ReferenceGraph.VisitControl;
import org.azyva.dragom.reference.ReferencePath;
import org.azyva.dragom.reference.ReferencePathMatcher;
import org.azyva.dragom.util.RuntimeExceptionUserError;
import org.azyva.dragom.util.ServiceLocator;
import org.azyva.dragom.util.Util;

/**
 * Sets up jobs in Jenkins based on the {@link ModuleVersion}'s in a
 * {@link ReferenceGraph}.
 * <p>
 * Although creating jobs in a tool such as Jenkins based on a ReferenceGraph is a
 * common that should probably be abstracted, this class is currently specific to
 * Jenkins. Time, experience and maturity will tell if, when and how this process
 * should be abstracted into plugins. But for now at this early stage, it is not
 * deemed pertinent to undertake such a task.
 * <p>
 * Note however that although this class is specific to Jenkins, it still makes
 * use of {@link JenkinsJobInfoPlugin} to abstract the actual job creation
 * details, namely the config.xml file or the template parameters.
 * <p>
 * Jobs and folders created by this class are recorded in an items created file,
 * whose content is also used as input if it already exists, in conjunction with a
 * {@link ExistingItemsCreatedFileMode}. The default items created file, if
 * {@link #setPathItemsCreatedFile} is not called, is jenkins-items-created.txt in
 * the metadata directory of the workspace. The default
 * ExistingItemsCreatedFileMode, if {@link #setExistingItemsCreatedFileMode} is not
 * called, is {@link ExistingItemsCreatedFileMode#MERGE}.
 *
 * @author David Raymond
 */
public class SetupJenkinsJobs extends RootModuleVersionJobSimpleAbstractImpl {
  /**
   * Context for {@link Util#handleDoYouWantToContinue} that represents creating a
   * Jenkins folder.
   */
  private static final String DO_YOU_WANT_TO_CONTINUE_CONTEXT_CREATE_JENKINS_FOLDER = "CREATE_JENKINS_FOLDER";

  /**
   * Context for {@link Util#handleDoYouWantToContinue} that represents creating or
   * updating a Jenkins job.
   */
  private static final String DO_YOU_WANT_TO_CONTINUE_CONTEXT_CREATE_UPDATE_JENKINS_JOB = "CREATE_UPDATE_JENKINS_JOB";

  /**
   * Context for {@link Util#handleDoYouWantToContinue} that represents deleting a
   * Jenkins folder.
   */
  private static final String DO_YOU_WANT_TO_CONTINUE_CONTEXT_DELETE_JENKINS_FOLDER = "DELETE_JENKINS_FOLDER";

  /**
   * Context for {@link Util#handleDoYouWantToContinue} that represents deleting a
   * Jenkins job.
   */
  private static final String DO_YOU_WANT_TO_CONTINUE_CONTEXT_DELETE_JENKINS_JOB = "DELETE_JENKINS_JOB";

  /**
   * Runtime property specifying the Jenkins base URL (e.g.:
   * https://acme.com/jenkins). Accessed on the root {@link ClassificationNode}.
   */
  private static final String RUNTIME_PROPERTY_JENKINS_BASE_URL = "JENKINS_BASE_URL";

  /**
   * Runtime property specifying the user to use to access Jenkins.
   * <p>
   * The corresponding password is obtained from {@link CredentialStorePlugin}.
   * <p>
   * If not specified, null is passed as the user to CredentialStorePlugin.
   * <p>
   * If the value is "" (the empty string), Jenkins is accessed anonymously.
   */
  private static final String RUNTIME_PROPERTY_JENKINS_USER = "JENKINS_USER";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_SKIPPING_NOT_DYNAMIC_VERSION = "SKIPPING_NOT_DYNAMIC_VERSION";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_VISITING_MODULE_VERSION = "VISITING_MODULE_VERSION";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_JOB_ALREADY_EXISTS = "JOB_ALREADY_EXISTS";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_JOB_NEEDS_CREATING_OR_UPDATING = "JOB_NEEDS_CREATING_OR_UPDATING";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_FOLDER_NEEDS_CREATING = "FOLDER_NEEDS_CREATING";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_DELETING_UNREFERENCED_FOLDER = "DELETING_UNREFERENCED_FOLDER";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_DELETING_UNREFERENCED_JOB = "DELETING_UNREFERENCED_JOB";

  /**
   * ResourceBundle specific to this class.
   */
  private static final ResourceBundle resourceBundle = ResourceBundle.getBundle(SetupJenkinsJobs.class.getName() + "ResourceBundle");

  /**
   * Default file in the workspace metadata directory containing the items created.
   */
  private static final String DEFAULT_ITEMS_CREATED_FILE = "jenkins-items-created.txt";

  /**
   * Modes for handling the items created file if it already exists.
   */
  public enum ExistingItemsCreatedFileMode {
    /**
     * Replace items created file, ignoring its current contents.
     */
    IGNORE,

    /**
     * Add new items, replace existing jobs. Existing folders are not touched, other
     * than manipulating the jobs within them.
     */
    MERGE,

    /**
     * Add new items, replace existing jobs, delete items (folders and jobs) which do
     * not exist anymore. Existing folders are not touched, other than manipulating
     * the jobs within them.
     * <p>
     * This mode can be used to perform a complete cleanup of previously created items
     * by specifying an empty {@link ReferencePathMatcher}.
     */
    REPLACE,

    /**
     * Similar to {@link #REPLACE}, but folders which do not exist anymore are deleted
     * only if empty (after having deleted the jobs which do not exist anymore).
     */
    REPLACE_DELETE_FOLDER_ONLY_IF_EMPTY,

    /**
     * Similar to {@link #REPLACE}, but folders are not deleted (only jobs).
     */
    REPLACE_NO_DELETE_FOLDER,
  }

  /**
   * Existing items created file mode.
   */
  private ExistingItemsCreatedFileMode existingItemsCreatedFileMode;

  /**
   * Manages the items created file.
   */
  private static class ItemsCreatedFileManager {
    /**
     * Set of folders present in the existing items created file when it was loaded
     * and which were not referenced during the execution of the job.
     * <p>
     * These folders become candidates for deletion for some
     * {@link ExistingItemsCreatedFileMode}.
     */
    Set<String> setFolderNotReferencedSinceLoaded;

    /**
     * Set of jobs present in the existing items created file when it was loaded
     * and which were not referenced during the execution of the job.
     * <p>
     * These jobs become candidates for deletion for some
     * {@link ExistingItemsCreatedFileMode}.
     */
    Set<String> setJobNotReferencedSinceLoaded;

    /**
     * Set of folders created during the execution of the job or from the existing
     * items created file.
     */
    Set<String> setFolderCreated;

    /**
     * Set of jobs created during the execution of the job or from the existing items
     * created file.
     */
    Set<String> setJobCreated;

    /**
     * Path to the file containing the items created.
     */
    Path pathItemsCreatedFile;

    /**
     * Indicates the items created information was modified and must be saved.
     */
    boolean indModified;

    /**
     * Constructor.
     *
     * @param pathItemsCreatedFile Path to the items created file.
     */
    public ItemsCreatedFileManager(Path pathItemsCreatedFile) {
      this.pathItemsCreatedFile = pathItemsCreatedFile;
      this.setFolderNotReferencedSinceLoaded = new LinkedHashSet<String>();
      this.setJobNotReferencedSinceLoaded = new LinkedHashSet<String>();
      this.setFolderCreated = new LinkedHashSet<String>();
      this.setJobCreated = new LinkedHashSet<String>();
    }

    /**
     * Loads the items created file if it exists.
     *
     * @return Indicates if the items created file exists.
     */
    public boolean loadIfExists() {
      BufferedReader bufferedReader;
      String line;

      if (!this.pathItemsCreatedFile.toFile().isFile()) {
        return false;
      }

      try {
        bufferedReader = new BufferedReader(new FileReader(this.pathItemsCreatedFile.toFile()));

        while ((line = bufferedReader.readLine()) != null) {
          // Within the items created file, folders and jobs are distinguished by the fact
          // that folders have a "/" at the end. But this is not exposed through the API and
          // folders names (paths) do not end with "/".
          if (line.charAt(line.length() - 1) == '/') {
            line = line.substring(0, line.length() - 1);
            this.setFolderCreated.add(line);
            this.setFolderNotReferencedSinceLoaded.add(line);
          } else {
            this.setJobCreated.add(line);
            this.setJobNotReferencedSinceLoaded.add(line);
          }
        }

        bufferedReader.close();

        return true;
      } catch (IOException ioe) {
        throw new RuntimeException(ioe);
      }
    }

    /**
     * Saves the items created file with the currently known jobs and folders created.
     * <p>
     * This includes those from the existing items created file and those added.
     * <p>
     * To {@link ExistingItemsCreatedFileMode#IGNORE} the items created file, this
     * method can be called without having called {@link #loadIfExists}.
     */
    public void save() {
      BufferedWriter bufferedWriter;

      try {
        if (this.indModified) {
          bufferedWriter = new BufferedWriter(new FileWriter(this.pathItemsCreatedFile.toFile()));

          for (String folder: this.setFolderCreated) {
            bufferedWriter.write(folder);
            bufferedWriter.write('/');
            bufferedWriter.write('\n');
          }

          for (String job: this.setJobCreated) {
            bufferedWriter.write(job);
            bufferedWriter.write('\n');
          }

          bufferedWriter.close();
        }
      } catch (IOException ioe) {
        throw new RuntimeException(ioe);
      }
    }

    /**
     * Indicates that a folder was created.
     *
     * @param folder Folder.
     */
    public void folderCreated(String folder) {
      this.indModified |= this.setFolderCreated.add(folder);
      this.setFolderNotReferencedSinceLoaded.remove(folder);
    }

    /**
     * Indicates that a job was created.
     *
     * @param job Job. Must not end with "/".
     */
    public void jobCreated(String job) {
      int indexJobName;

      this.indModified |= this.setJobCreated.add(job);
      this.setJobNotReferencedSinceLoaded.remove(job);

      indexJobName = job.lastIndexOf('/');

      if (indexJobName != -1) {
        // When a job is marked as created, certainly its parent folder is implicitly
        // referenced, but not necessarily created. Whether a folder is created or not is
        // must be handled by the caller.

        // Whether the parent folder is created or not must be handled by the caller. That
        // is why this.setFolderCreated is not modified. But when a job is marked as created
        // its parent folder, if ever it was previously created and is present in
        // this.setFolderNotReferencedSinceLoaded, it must be removed.

        this.setFolderNotReferencedSinceLoaded.remove(job.substring(0, indexJobName));
      }
    }

    /**
     * Indicates if a job has been created.
     *
     * @param job Job.
     * @return See description.
     */
    public boolean isJobCreated(String job) {
      return this.setJobCreated.contains(job);
    }

    /**
     * Returns the Set of jobs which were not referenced during this job execution
     * since the existing items created file was loaded.
     *
     * @return See description.
     */
    public Set<String> getSetJobNotReferencedSinceLoaded() {
      // A copy is returned since while the caller iterates over the jobs in the Set to
      // delete them, jobDeleted is expected to be called which modifies the Set.
      return new LinkedHashSet<String>(this.setJobNotReferencedSinceLoaded);
    }

    /**
     * Returns the Set of folders which were not referenced during this job execution
     * since the existing items created file was loaded.
     *
     * @return See description. Caller must not modify the Set.
     */
    public Set<String> getSetFolderNotReferencedSinceLoaded() {
      // A copy is returned since while the caller iterates over the folders in the Set
      // to delete them, folderDeleted is expected to be called which modifies the Set.
      return new LinkedHashSet<String>(this.setFolderNotReferencedSinceLoaded);
    }

    /**
     * Indicates that a job was deleted.
     *
     * @param job Job. Must not end with "/".
     */
    public void jobDeleted(String job) {
      this.indModified |= this.setJobCreated.remove(job);
      this.setJobNotReferencedSinceLoaded.remove(job);
    }

    /**
     * Indicates that a folder was deleted.
     *
     * @param folder Folder. Must end with "/".
     */
    public void folderDeleted(String folder) {
      Iterator<String> iteratorJob;

      this.indModified |= this.setFolderCreated.remove(folder);
      this.setFolderNotReferencedSinceLoaded.remove(folder);

      folder += '/';

      iteratorJob = this.setJobCreated.iterator();

      while (iteratorJob.hasNext()) {
        String job;

        job = iteratorJob.next();

        if (job.startsWith(folder)) {
          iteratorJob.remove();
          this.indModified = true;
        }
      }

      iteratorJob = this.setJobNotReferencedSinceLoaded.iterator();

      while (iteratorJob.hasNext()) {
        String job;

        job = iteratorJob.next();

        if (job.startsWith(folder)) {
          iteratorJob.remove();
        }
      }
    }
  }

  /**
   * ItemsCreatedFileManager.
   * <p>
   * The Path to the items created file is not kept by the class. An
   * ItemsCreatedFileManager is rather created with the Path to the items created
   * file so that it can manage it on behalf of this class.
   * <p>
   * If null, no items created file is managed.
   */
  private ItemsCreatedFileManager itemsCreatedFileManager;

  /**
   * JenkinsClient.
   */
  private JenkinsClient jenkinsClient;

  /**
   * Constructor.
   *
   * @param listModuleVersionRoot List of root ModuleVersion's.
   */
  public SetupJenkinsJobs(List<ModuleVersion> listModuleVersionRoot) {
    super(listModuleVersionRoot);

    ExecContext execContext;
    RuntimePropertiesPlugin runtimePropertiesPlugin;
    CredentialStorePlugin credentialStorePlugin;
    String jenkinsBaseUrl;
    String user;
    String password;

    this.itemsCreatedFileManager = new ItemsCreatedFileManager(((WorkspaceExecContext)ExecContextHolder.get()).getPathMetadataDir().resolve(SetupJenkinsJobs.DEFAULT_ITEMS_CREATED_FILE));
    this.existingItemsCreatedFileMode = ExistingItemsCreatedFileMode.MERGE;

    execContext = ExecContextHolder.get();
    runtimePropertiesPlugin = execContext.getExecContextPlugin(RuntimePropertiesPlugin.class);
    credentialStorePlugin = execContext.getExecContextPlugin(CredentialStorePlugin.class);

    jenkinsBaseUrl = runtimePropertiesPlugin.getProperty(null,  SetupJenkinsJobs.RUNTIME_PROPERTY_JENKINS_BASE_URL);
    user = runtimePropertiesPlugin.getProperty(null,  SetupJenkinsJobs.RUNTIME_PROPERTY_JENKINS_USER);

    if ((user != null) && user.isEmpty()) {
      user = null;
      password = null;
    } else {
      CredentialStorePlugin.Credentials credentials;

      credentials = credentialStorePlugin.getCredentials(
          jenkinsBaseUrl,
          user,
          new CredentialStorePlugin.CredentialValidator() {
            @Override
            public boolean validateCredentials(String resource, String user, String password) {
              JenkinsClient jenkinsClient;

              jenkinsClient = ServiceLocator.getService(JenkinsClient.class);
              jenkinsClient.setBaseUrl(jenkinsBaseUrl);
              jenkinsClient.setUser(user);
              jenkinsClient.setPassword(password);

              return jenkinsClient.validateCredentials();
            }
          });

      user = credentials.user;
      password = credentials.password;
    }

    this.jenkinsClient = ServiceLocator.getService(JenkinsClient.class);
    this.jenkinsClient.setBaseUrl(jenkinsBaseUrl);
    this.jenkinsClient.setUser(user);
    this.jenkinsClient.setPassword(password);
  }

  /**
   * @param pathItemsCreatedFile Path to the items created file. Can be null to
   *   disable items created file handling. If not called, the default items created
   *   file is jenkins-items-created.txt in the metadata directory of the workspace.
   */
  public void setPathItemsCreatedFile(Path pathItemsCreatedFile) {
    this.itemsCreatedFileManager = new ItemsCreatedFileManager(pathItemsCreatedFile);
  }

  /**
   * @param existingItemsCreatedFileMode ExistingItemsCreatedFileMode. If not
   *   called, the default ExistingItemsCreatedFileMode is
   *   {@link ExistingItemsCreatedFileMode#MERGE}.
   */
  public void setExistingItemsCreatedFileMode(ExistingItemsCreatedFileMode existingItemsCreatedFileMode) {
    this.existingItemsCreatedFileMode = existingItemsCreatedFileMode;
  }

  /**
   * {@link org.azyva.dragom.reference.ReferenceGraph.Visitor} used to
   */
  private class ReferenceGraphVisitorSetupJob implements ReferenceGraph.Visitor {
    /**
     * Constructor.
     */
    public ReferenceGraphVisitorSetupJob() {
    }

    @Override
    public ReferenceGraph.VisitControl visit(ReferenceGraph referenceGraph, ReferencePath referencePath, EnumSet<ReferenceGraph.VisitAction> enumSetVisitAction) {
      ExecContext execContext;
      UserInteractionCallbackPlugin userInteractionCallbackPlugin;
      Version version;
      Model model;
      Module module;
      JenkinsJobInfoPlugin jenkinsJobInfoPlugin;
      String job;
      String folder;
      String template;

      if (!enumSetVisitAction.contains(ReferenceGraph.VisitAction.VISIT)) {
        return ReferenceGraph.VisitControl.CONTINUE;
      }

      execContext = ExecContextHolder.get();
      userInteractionCallbackPlugin = execContext.getExecContextPlugin(UserInteractionCallbackPlugin.class);

      version = referencePath.getLeafModuleVersion().getVersion();

      if (version.getVersionType() != VersionType.DYNAMIC) {
        userInteractionCallbackPlugin.provideInfo(MessageFormat.format(SetupJenkinsJobs.resourceBundle.getString(SetupJenkinsJobs.MSG_PATTERN_KEY_SKIPPING_NOT_DYNAMIC_VERSION), referencePath, referencePath.getLeafModuleVersion(), version));
        return ReferenceGraph.VisitControl.SKIP_CHILDREN;
      }

      userInteractionCallbackPlugin.provideInfo(MessageFormat.format(SetupJenkinsJobs.resourceBundle.getString(SetupJenkinsJobs.MSG_PATTERN_KEY_VISITING_MODULE_VERSION), referencePath, referencePath.getLeafModuleVersion()));

      model = execContext.getModel();
      module = model.getModule(referencePath.getLeafModuleVersion().getNodePath());

      jenkinsJobInfoPlugin = module.getNodePlugin(JenkinsJobInfoPlugin.class, null);
      job = jenkinsJobInfoPlugin.getJobFullName(version);

      if ((SetupJenkinsJobs.this.jenkinsClient.getItemType(job) != null) && !SetupJenkinsJobs.this.itemsCreatedFileManager.isJobCreated(job)) {
        throw new RuntimeExceptionUserError(MessageFormat.format(SetupJenkinsJobs.resourceBundle.getString(SetupJenkinsJobs.MSG_PATTERN_KEY_JOB_ALREADY_EXISTS), referencePath.getLeafModuleVersion(), job));
      }

      userInteractionCallbackPlugin.provideInfo(MessageFormat.format(SetupJenkinsJobs.resourceBundle.getString(SetupJenkinsJobs.MSG_PATTERN_KEY_JOB_NEEDS_CREATING_OR_UPDATING), referencePath.getLeafModuleVersion(), job));

      if (!Util.handleDoYouWantToContinueWithIndividualNo(SetupJenkinsJobs.DO_YOU_WANT_TO_CONTINUE_CONTEXT_CREATE_UPDATE_JENKINS_JOB)) {
        return Util.isAbort() ? VisitControl.ABORT : VisitControl.CONTINUE;
      }

      if (jenkinsJobInfoPlugin.isHandleParentFolderCreation()) {
        int indexJobName;
        JenkinsClient.ItemType itemType;

        indexJobName = job.lastIndexOf('/');

        if (indexJobName != -1) {
          folder = job.substring(0, indexJobName);

          itemType = SetupJenkinsJobs.this.jenkinsClient.getItemType(folder);

          if ((itemType != null) && (itemType == JenkinsClient.ItemType.NOT_FOLDER)) {
            // We really do not expect to get here since we took the parent path of a job,
            // which is necessarily a folder.
            throw new RuntimeException("Unexpected type for item " + folder + '.');
          }

          if (itemType == null) {
            userInteractionCallbackPlugin.provideInfo(MessageFormat.format(SetupJenkinsJobs.resourceBundle.getString(SetupJenkinsJobs.MSG_PATTERN_KEY_FOLDER_NEEDS_CREATING), referencePath.getLeafModuleVersion(), folder));

            if (!Util.handleDoYouWantToContinueWithIndividualNo(SetupJenkinsJobs.DO_YOU_WANT_TO_CONTINUE_CONTEXT_CREATE_JENKINS_FOLDER)) {
              return Util.isAbort() ? VisitControl.ABORT : VisitControl.CONTINUE;
            }

            SetupJenkinsJobs.this.jenkinsClient.createSimpleFolder(folder);

            if (SetupJenkinsJobs.this.itemsCreatedFileManager != null) {
              SetupJenkinsJobs.this.itemsCreatedFileManager.folderCreated(folder);
            }
          }
        }
      }

      template = jenkinsJobInfoPlugin.getTemplate();

      if (template != null) {
        Map<String, String> mapTemplateParam;

        mapTemplateParam = jenkinsJobInfoPlugin.getMapTemplateParam(referenceGraph, version);
        SetupJenkinsJobs.this.jenkinsClient.createUpdateJobFromTemplate(template, job, mapTemplateParam);
      } else {
        Reader readerConfig;

        readerConfig = jenkinsJobInfoPlugin.getReaderConfig(referenceGraph, version);
        SetupJenkinsJobs.this.jenkinsClient.createUpdateJob(job, readerConfig);
      }

      if (SetupJenkinsJobs.this.itemsCreatedFileManager != null) {
        SetupJenkinsJobs.this.itemsCreatedFileManager.jobCreated(job);
      }

      return ReferenceGraph.VisitControl.CONTINUE;
    }
  }

  /**
   * Main method for performing the job.
   */
  @Override
  public void performJob() {
    BuildReferenceGraph buildReferenceGraph;
    ReferenceGraph referenceGraph;

    buildReferenceGraph = new BuildReferenceGraph(null, this.listModuleVersionRoot);
    buildReferenceGraph.setReferencePathMatcherProvided(this.getReferencePathMatcher());
    buildReferenceGraph.setIndHandleStaticVersion(false);
    buildReferenceGraph.performJob();
    referenceGraph = buildReferenceGraph.getReferenceGraph();

    UserInteractionCallbackPlugin userInteractionCallbackPlugin;
    SetupJenkinsJobs.ReferenceGraphVisitorSetupJob referenceGraphVisitorSetupJob;

    userInteractionCallbackPlugin = ExecContextHolder.get().getExecContextPlugin(UserInteractionCallbackPlugin.class);

    if (this.itemsCreatedFileManager != null) {
      if (this.existingItemsCreatedFileMode == ExistingItemsCreatedFileMode.IGNORE) {
        this.itemsCreatedFileManager.save();
      } else {
        this.itemsCreatedFileManager.loadIfExists();
      }
    } else if (this.existingItemsCreatedFileMode != ExistingItemsCreatedFileMode.IGNORE) {
      throw new RuntimeException("ExistingItemsCreatedFileMode must be IGNORE when items created file not specified.");
    }

    referenceGraphVisitorSetupJob = new SetupJenkinsJobs.ReferenceGraphVisitorSetupJob();

    try {
      // Traversal is not depth-first as jobs will often refer to downstream jobs
      // which are actually jobs that correspond to ModuleVersion's higher in the
      // ReferenceGraph.
      referenceGraph.traverseReferenceGraph(null, false, ReferenceGraph.ReentryMode.NO_REENTRY, referenceGraphVisitorSetupJob);

      if (this.existingItemsCreatedFileMode == ExistingItemsCreatedFileMode.REPLACE) {
        // We start by deleting the folders since this will delete all jobs within them at
        // once, which will be more efficient than deleting the jobs individually.
        for (String folder: this.itemsCreatedFileManager.getSetFolderNotReferencedSinceLoaded()) {
          userInteractionCallbackPlugin.provideInfo(MessageFormat.format(SetupJenkinsJobs.resourceBundle.getString(SetupJenkinsJobs.MSG_PATTERN_KEY_DELETING_UNREFERENCED_FOLDER), folder));

          if (!Util.handleDoYouWantToContinueWithIndividualNo(SetupJenkinsJobs.DO_YOU_WANT_TO_CONTINUE_CONTEXT_DELETE_JENKINS_FOLDER)) {
            if (Util.isAbort()) {
              return;
            } else {
              continue;
            }
          }

          this.jenkinsClient.deleteItem(folder);

          // This will mark the jobs within the folder as being deleted as well.
          this.itemsCreatedFileManager.folderDeleted(folder);
        }

        // The jobs that remain to be deleted are those not in folders which were deleted
        // above.
        for (String job: this.itemsCreatedFileManager.getSetJobNotReferencedSinceLoaded()) {
          userInteractionCallbackPlugin.provideInfo(MessageFormat.format(SetupJenkinsJobs.resourceBundle.getString(SetupJenkinsJobs.MSG_PATTERN_KEY_DELETING_UNREFERENCED_JOB), job));

          if (!Util.handleDoYouWantToContinueWithIndividualNo(SetupJenkinsJobs.DO_YOU_WANT_TO_CONTINUE_CONTEXT_DELETE_JENKINS_JOB)) {
            if (Util.isAbort()) {
              return;
            } else {
              continue;
            }
          }

          this.jenkinsClient.deleteItem(job);
          this.itemsCreatedFileManager.jobDeleted(job);
        }
      } else if (this.existingItemsCreatedFileMode == ExistingItemsCreatedFileMode.REPLACE_DELETE_FOLDER_ONLY_IF_EMPTY) {
        // Here, we must delete the jobs first since the folders need to be deleted only
        // if empty, and they can become empty following the deletion of jobs within them.
        for (String job: this.itemsCreatedFileManager.getSetJobNotReferencedSinceLoaded()) {
          userInteractionCallbackPlugin.provideInfo(MessageFormat.format(SetupJenkinsJobs.resourceBundle.getString(SetupJenkinsJobs.MSG_PATTERN_KEY_DELETING_UNREFERENCED_JOB), job));

          if (!Util.handleDoYouWantToContinueWithIndividualNo(SetupJenkinsJobs.DO_YOU_WANT_TO_CONTINUE_CONTEXT_DELETE_JENKINS_JOB)) {
            if (Util.isAbort()) {
              return;
            } else {
              continue;
            }
          }

          this.jenkinsClient.deleteItem(job);
          this.itemsCreatedFileManager.jobDeleted(job);
        }

        for (String folder: this.itemsCreatedFileManager.getSetFolderNotReferencedSinceLoaded()) {
          if (this.jenkinsClient.isFolderEmpty(folder)) {
            userInteractionCallbackPlugin.provideInfo(MessageFormat.format(SetupJenkinsJobs.resourceBundle.getString(SetupJenkinsJobs.MSG_PATTERN_KEY_DELETING_UNREFERENCED_FOLDER), folder));

            if (!Util.handleDoYouWantToContinueWithIndividualNo(SetupJenkinsJobs.DO_YOU_WANT_TO_CONTINUE_CONTEXT_DELETE_JENKINS_FOLDER)) {
              if (Util.isAbort()) {
                return;
              } else {
                continue;
              }
            }

            this.jenkinsClient.deleteItem(folder);
            this.itemsCreatedFileManager.folderDeleted(folder);
          }

        }
      } else if (this.existingItemsCreatedFileMode == ExistingItemsCreatedFileMode.REPLACE_NO_DELETE_FOLDER) {
        for (String job: this.itemsCreatedFileManager.getSetJobNotReferencedSinceLoaded()) {
          userInteractionCallbackPlugin.provideInfo(MessageFormat.format(SetupJenkinsJobs.resourceBundle.getString(SetupJenkinsJobs.MSG_PATTERN_KEY_DELETING_UNREFERENCED_JOB), job));

          if (!Util.handleDoYouWantToContinueWithIndividualNo(SetupJenkinsJobs.DO_YOU_WANT_TO_CONTINUE_CONTEXT_DELETE_JENKINS_FOLDER)) {
            if (Util.isAbort()) {
              return;
            } else {
              continue;
            }
          }

          this.jenkinsClient.deleteItem(job);
          this.itemsCreatedFileManager.jobDeleted(job);
        }
      }
    } finally {
      if (this.itemsCreatedFileManager != null) {
        this.itemsCreatedFileManager.save();
      }
    }
  }
}
