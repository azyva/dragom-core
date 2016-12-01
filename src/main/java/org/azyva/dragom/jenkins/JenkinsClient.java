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

package org.azyva.dragom.jenkins;

import java.io.Reader;
import java.util.Map;

import org.azyva.dragom.jenkins.impl.DefaultJenkinsClientImpl;
import org.azyva.dragom.util.ServiceLocator;

/**
 * Jenkins client interface which represents interactions with Jenkins through its
 * REST API.
 * <p>
 * This interface defines the calls that are useful for Dragom. It is not meant to
 * be a full-featured implementation of the Jenkins API.
 * <p>
 * Implementations of this interface are intended to be obtained using
 * {@link ServiceLocator} so they can easily be doubled for testing purposes. The
 * main implementation is {@link DefaultJenkinsClientImpl} which therefore has a
 * no-argument constructor. This is why setup methods such as {@link #setBaseUrl}
 * are part of this interface.
 *
 * @author David Raymond
 */
public interface JenkinsClient {
  /**
   * Exception that wraps an HTTP status code.
   * <p>
   * Similar classes exist in different framework, but such frameworks are not used
   * here and introducing an otherwise useless dependency is not desirable.
   * <p>
   * javax.xml.ws.http.HTTPException might have been a good fit, but it does not
   * allow providing a description of the context, which is required here.
   */
  public class HttpStatusException extends RuntimeException {
    // To keep the compiler from complaining.
    private static final long serialVersionUID = 0;

    /**
     * Status code.
     */
    private int statusCode;

    /**
     * Constructor.
     *
     * @param message Message.
     * @param statusCode Status code.
     */
    public HttpStatusException(String message, int statusCode) {
      super(message);

      this.statusCode = statusCode;
    }

    /**
     * @return Status code.
     */
    public int getStatusCode() {
      return this.statusCode;
    }
  }

  /**
   * Types of items.
   */
  public enum ItemType {
    /**
     * Item is a folder.
     */
    FOLDER,

    /**
     * Item is not a folder. It is probably a job, but could be anything among the set
     * of item types supported by Jenkins (job template, job, etc.).
     */
    NOT_FOLDER
  }

  /**
   * Build states.
   */
  public enum BuildState {
    /**
     * Build is queued and not running yet.
     */
    QUEUED,

    /**
     * Build has been cancelled while it was queued.
     * <p>
     * This is similar to ABORTED, except that the build was never RUNNING.
     */
    CANCELLED,

    /**
     * Build is running.
     */
    RUNNING,

    /**
     * Build has been aborted while it was running.
     * <p>
     * This is similar to CANCELLED, except that the build was RUNNING.
     */
    ABORTED,

    /**
     * Build is completed and is FAILED.
     */
    FAILED,

    /**
     * Build is completed and is UNSTABLE.
     */
    UNSTABLE,

    /**
     * Build completed with SUCCESS.
     */
    SUCCESS;

    /**
     * @return Indicates if the build is out of the queue (is running or completed).
     */
    public boolean isOutOfQueue() {
      return (this != QUEUED) && (this != CANCELLED);
    }

    /**
     * @return Indicates if the build is completed.
     */
    public boolean isCompleted() {
      return (this == ABORTED) || (this == FAILED) || (this == UNSTABLE) || (this == SUCCESS);
    }
  }

  /**
   * Represents a build.
   * <p>
   * Instances are created by {@link JenkinsClient#build}.
   * <p>
   * Allows getting information about the build while it progresses to completion.
   */
  public interface Build {
    /**
     * Updates and returns the BuildState.
     *
     * @return See description.
     */
    BuildState getBuildState();

    /**
     * @return Job URL.
     */
    String getJobUrl();

    /**
     * @return Job (full name).
     */
    String getJob();

    /**
     * @return Build URL.
     */
    String getBuildUrl();

    /**
     * @return Build number.
     */
    int getBuildNumber();

    /**
     * @return Build (display) name.
     */
    String getBuildName();

    /**
     * Cancels or aborts the build, depending on whether its {@link BuildState} is
     * {@link BuildState#QUEUED} or {@link BuildState#RUNNING}.
     * <p>
     * A build in any other BuildState cannot be cancelled and false is returned.
     *
     * @return Indicates if the build has been aborted or cancelled successfully.
     *   Generally this can be assumed to be true since if an error occurs, an
     *   exception is thrown.
     */
    boolean cancel();

    /**
     * @return Returns the next chunk of console output or null if not
     *   {@link BuildState#isOutOfQueue} or if no more data is available.
     */
    String getNextConsoleChunk();

    /**
     * @return Complete console output or null if not {@link BuildState#isOutOfQueue}.
     */
    String getFullConsole();
  }

  /**
   * Sets the Jenkins base URL.
   *
   * @param baseUrl See description.
   */
  void setBaseUrl(String baseUrl);

  /**
   * Sets the user to access Jenkins. If null (or not set) Jenkins is accessed
   * anonymously.
   *
   * @param user See description.
   */
  void setUser(String user);

  /**
   * Sets the password to access Jenkins. null if user (see {@link #setUser}) is
   * null.
   *
   * @param password See description.
   */
  void setPassword(String password);

  /**
   * @return Indicates if the credentials provided are valid.
   */
  boolean validateCredentials();

  /**
   * Returns the {@link ItemType}.
   * <p>
   * Returns null if the item does not exist.
   *
   * @param item Item (full name).
   * @return See description.
   */
  ItemType getItemType(String item);

  /**
   * Deletes an item.
   *
   * @param item Item (full name).
   * @return Indicates if the item existed and was deleted.
   */
  boolean deleteItem(String item);

  /**
   * Created a job from a template.
   *
   * @param template Template (full name).
   * @param job New job (full name).
   * @param mapTemplateParam Template parameters.
   */
  void createUpdateJobFromTemplate(String template, String job, Map<String, String> mapTemplateParam);

  /**
   * Creates a regular non-templatized job.
   *
   * @param job New job (full name).
   * @param readerConfig Reader providing the configuration of the job.
   */
  void createJob(String job, Reader readerConfig);

  /**
   * Updates a regular non-templatized job.
   *
   * @param job Job (full name).
   * @param readerConfig Reader providing the configuration of the job.
   */
  void updateJob(String job, Reader readerConfig);

  /**
   * Creates or updates a regular non-templatized job.
   *
   * @param job Job (full name).
   * @param readerConfig Reader providing the configuration of the job.
   */
  void createUpdateJob(String job, Reader readerConfig);

  /**
   * Triggers a build for a job.
   *
   * @param job Job (full name).
   * @param mapBuildParam Build parameters. Can be null.
   * @return Build.
   */
  Build build(String job, Map<String, String> mapBuildParam);

  boolean isFolderEmpty(String folder);

  /**
   * Creates a simple folder.
   *
   * @param folder Folder (full name).
   * @return Indicates if the folder was created. false is returned if it already
   *   exited.
   */
  boolean createSimpleFolder(String folder);
}