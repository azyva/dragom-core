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

package org.azyva.dragom.git.impl;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.PumpStreamHandler;
import org.apache.commons.io.FileUtils;
import org.azyva.dragom.execcontext.ExecContext;
import org.azyva.dragom.execcontext.plugin.impl.DefaultWorkspacePluginFactory;
import org.azyva.dragom.execcontext.support.ExecContextHolder;
import org.azyva.dragom.git.Git;
import org.azyva.dragom.model.Version;
import org.azyva.dragom.model.VersionType;
import org.azyva.dragom.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Main implementation of {@link Git}.
 *
 * @author David Raymond
 */
public class DefaultGitImpl implements Git {
  /**
   * Logger for the class.
   */
  private static final Logger logger = LoggerFactory.getLogger(DefaultGitImpl.class);

  /**
   * Transient data to cache modification timestamp and current {@link Version} for
   * workspace paths.
   *
   * <p>Is an optimization to avoid useless calls to the Git checkout and
   * symbolic-ref commands.
   *
   * <p>The key of the Map is the path. The element is
   * {@link ModTimestampVersion}.
   */
  private static final String TRANSIENT_DATA_MAP_PATH_MOD_TIMESTAMP_VERSION = DefaultGitImpl.class + ".MapPathModTimestampVersion";

  /**
   * Elements of the Map that caches modification timestamp and current
   * {@link Version} for workspace paths.
   */
  private static class ModTimestampVersion {
    /**
     * Modification timestamp.
     */
    public long modTimestamp;

    /**
     * Version.
     */
    public Version version;
  }

  /**
   * Pattern to extract the user from a HTTP[S] repository URL.
   */
  private static final Pattern patternExtractHttpReposUrlUser = Pattern.compile("([hH][tT][tT][pP][sS]?://)(?:([a-zA-Z0-9_\\.\\-]+)@)?([^/]+)/.*");

  /**
   * Set of paths to delete on shutdown.
   *
   * <p>Paths are added to this list during clone operations and removed once they
   * complete. A shutdown hook is registered to delete those paths upon forceful
   * shutdown.
   *
   * <p>This is to prevent having a partial invalid Git local repository if the user
   * halts the process during a clone operation (SIGINT, Ctrl-C).
   *
   * <p>Arguably there are many other cases where cleanup would be pertinent upon
   * forceful shutdown. But it is not reasonable to handle them all. Forcefully
   * shutting down during a lengthy clone operation is easy and renders the
   * directory useless, so this case is specifically handled.
   *
   * <p>The directory may not have been created by this class, so deleting it may
   * not be the logical thing to do. But we expect other classes that manage
   * directories, such as {@link DefaultWorkspacePluginFactory}, to handle the case
   * of missing directories.
   */
  private static Set<Path> setPathToDeleteOnShutdown = Collections.synchronizedSet(new HashSet<Path>());

  static {
    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      public void run() {
        for (Path path: DefaultGitImpl.setPathToDeleteOnShutdown) {
          FileUtils.deleteQuietly(path.toFile());
        }
      }
    });
  }

  /**
   * Path to the git Executable.
   *
   * <p>Defaults to "git" with no actual Path.
   */
  private Path pathExecutable = Paths.get("git");

  /**
   * Repository URL.
   */
  private String reposUrl;

  /**
   * User.
   */
  private String user;

  /**
   * Password.
   */
  private String password;

  /**
   * Configured user name.
   */
  private String configUserName;

  /**
   * Configured user email.
   */
  private String configUserEmail;

  /**
   * HTTP credentials to include in the credentials file provided to git through the
   * "store" credential helper.
   * <p>
   * The fact that this is not null is also used as an indicator of if the user
   * specified in the repository URL, if any, has been validated against the user
   * provided with {@link #setUser}.
   * <p>
   * This is required only if the user is provided, in which case the protocol used
   * in the repository URL must be HTTP[S].
   */
  private String httpCredentials;

  @Override
  public void setPathExecutable(Path pathExecutable) {
    this.pathExecutable = pathExecutable;
  }

  @Override
  public void setReposUrl(String reposUrl) {
    this.reposUrl = reposUrl;
  }

  @Override
  public void setUser(String user) {
    this.user = user;
  }

  @Override
  public void setPassword(String password) {
    this.password = password;
  }

  @Override
  public void setConfigUserName(String configUserName) {
    this.configUserName = configUserName;
  }

  @Override
  public void setConfigUserEmail(String configUserEmail) {
    this.configUserEmail = configUserEmail;
  }

  @Override
  public int executeGitCommand(String[] arrayArg, boolean indProvideCredentials, AllowExitCode allowExitCode, Path pathWorkingDirectory, StringBuilder stringBuilderOutput, boolean indTrimOutput) {
    CommandLine commandLine;
    Path pathFileCredentials;
    DefaultExecutor defaultExecutor;
    ByteArrayOutputStream byteArrayOutputStreamOut;
    ByteArrayOutputStream byteArrayOutputStreamErr;
    int exitCode;
    String stderr;

    pathFileCredentials = null;

    try {
      commandLine = new CommandLine(this.pathExecutable.toString());

      if (indProvideCredentials && (this.user != null)) {
        Writer writer;

        if (this.httpCredentials == null) {
          Matcher matcher;
          String userFromReposUrl;

          matcher = DefaultGitImpl.patternExtractHttpReposUrlUser.matcher(this.reposUrl);

          if (!matcher.matches()) {
            throw new RuntimeException("Repository URL " + this.reposUrl + " does not match credential extraction pattern " + DefaultGitImpl.patternExtractHttpReposUrlUser.toString() + '.');
          }

          userFromReposUrl = matcher.group(2);

          if ((userFromReposUrl != null) && !userFromReposUrl.equals(this.user)) {
            throw new RuntimeException("User " + userFromReposUrl + " extracted from repository URL " + this.reposUrl + " does not correspond to user provided in credentials " + this.user + '.');
          }

          try {
            this.httpCredentials = matcher.group(1) + URLEncoder.encode(this.user, "UTF-8") + ':' + URLEncoder.encode(this.password, "UTF-8") + '@' + matcher.group(3);
          } catch (UnsupportedEncodingException uee) {
            throw new RuntimeException(uee);
          }
        }

        try {
          if (Util.isPosix()) {
            Set<PosixFilePermission> setPosixFilePermission;

            setPosixFilePermission = new HashSet<PosixFilePermission>();

            setPosixFilePermission.add(PosixFilePermission.OWNER_READ);
            setPosixFilePermission.add(PosixFilePermission.OWNER_WRITE);

            pathFileCredentials = Files.createTempFile((String)null, (String)null, PosixFilePermissions.asFileAttribute(setPosixFilePermission));
          } else {
            pathFileCredentials = Files.createTempFile((String)null, (String)null);
          }

          writer = new FileWriter(pathFileCredentials.toFile());
          writer.append(this.httpCredentials);
          writer.close();
        } catch (IOException ioe) {
          throw new RuntimeException(ioe);
        }

        // It seems like Git, being a Linux based tool, does not like having \ in paths,
        // at least for the store credential helper file.
        commandLine.addArgument("-c").addArgument(("credential.helper=store --file=" + pathFileCredentials.toString()).replace("\\", "/"), false);
      }

      if (this.configUserName != null) {
        commandLine.addArgument("-c").addArgument("user.name=" + this.configUserName);
      }

      if (this.configUserEmail != null) {
        if (this.configUserEmail.length() == 0) {
          // This is the trick to support empty email.
          commandLine.addArgument("-c").addArgument("user.email=<>");
        } else {
          commandLine.addArgument("-c").addArgument("user.email=" + this.configUserEmail);
        }
      }

      for (String arg: arrayArg) {
        commandLine.addArgument(arg, false);
      }

      defaultExecutor = new DefaultExecutor();
      byteArrayOutputStreamOut = new ByteArrayOutputStream();
      byteArrayOutputStreamErr = new ByteArrayOutputStream();
      defaultExecutor.setStreamHandler(new PumpStreamHandler(byteArrayOutputStreamOut, byteArrayOutputStreamErr));
      defaultExecutor.setExitValues(null); // To not check for exit values.

      if (pathWorkingDirectory != null) {
        defaultExecutor.setWorkingDirectory(pathWorkingDirectory.toFile());
        DefaultGitImpl.logger.info("Invoking Git command " + commandLine + " within " + pathWorkingDirectory + '.');
      } else {
        DefaultGitImpl.logger.info("Invoking Git command " + commandLine + '.');
      }

      try {
        exitCode = defaultExecutor.execute(commandLine);
      } catch (IOException ioe) {
        throw new RuntimeException(ioe);
      }

      // We need this at more than one place below.
      stderr = byteArrayOutputStreamErr.toString();

      if (!(   (exitCode == 0)
            || ((exitCode == 1) && allowExitCode == AllowExitCode.ONE)
            || ((exitCode != 0) && allowExitCode == AllowExitCode.ALL))) {

        StringBuilder stringBuilderException;

        stringBuilderException = new StringBuilder();

        stringBuilderException.append("Git command ").append(commandLine).append(" executed in ").append(pathWorkingDirectory).append(" failed with exit code: ").append(exitCode).append('\n');

        stringBuilderException.append("Repository URL: ").append(this.reposUrl).append('\n');

        if (byteArrayOutputStreamOut.size() != 0) {
          stringBuilderException.append("Standard output:\n");
          stringBuilderException.append(byteArrayOutputStreamOut.toString()).append('\n');
        }

        if (stderr.length() != 0) {
          stringBuilderException.append("Error output:\n");
          stringBuilderException.append(stderr).append('\n');
        }

        // Get rid of the trailing newline.
        stringBuilderException.setLength(stringBuilderException.length() - 1);

        throw new RuntimeException(stringBuilderException.toString());
      } else if (!stderr.isEmpty()) {
        if (exitCode != 0) {
          DefaultGitImpl.logger.error("Git command returned " + exitCode + '.');
          DefaultGitImpl.logger.error("Caller indicated to not treat this exit code as an error, but information was returned in stderr which may indicate an abnormal situation:");
        } else {
          DefaultGitImpl.logger.warn("Git command returned 0 and information in stderr which may indicate an abnormal situation:");
        }

        DefaultGitImpl.logger.warn(stderr);
      }

      if (stringBuilderOutput != null) {
        if (indTrimOutput) {
          stringBuilderOutput.append(byteArrayOutputStreamOut.toString().trim());
        } else {
          stringBuilderOutput.append(byteArrayOutputStreamOut.toString());
        }

        // We concatenate stderr since in some cases it is of interest to the caller,
        // such as in validateCredentials, where the text allowing the method to
        // distinguish various cases is returned therein. For most cases the caller is
        // interested in stdout only, and fortunately, when a command exists
        // successfully with output to stdout, no output is sent to stderr.
        stringBuilderOutput.append(byteArrayOutputStreamErr.toString().trim());
      }

      return exitCode;
    } finally {
      if (pathFileCredentials != null) {
        pathFileCredentials.toFile().delete();
      }
    }
  }

  @Override
  public boolean validateCredentials() {
    StringBuilder stringBuilderOutput;

    stringBuilderOutput = new StringBuilder();

    // The most convenient way to validate the credentials is to use the ls-remote
    // command. Unfortunately, if the remove repository does not exist, the command
    // fails, which is expected. The only way to distinguish between the credentials
    // not being valid and the repository not existing is to look for some pattern in
    // the error message (which by the way is returned in stderr, which
    // executeGitCommand does include in the output StreamBuilder). This is admittedly
    // not robust, especially if the git client is configured to returned localized
    // messages, but seems to be the only way to do it.
    // The complete message returned when the credentials are not valid is:
    //   remote: Invalid username or password. If you log in via a third party service you must ensure you have an account password set in your account profile.
    //   fatal: Authentication failed for 'https://azyva@bitbucket.org/azyva/dragom-api.git/'
    // And when the remote repository does not exist:
    //   remote: Not Found
    //   fatal: repository '<repository url>' not found

    if (this.executeGitCommand(new String[] {"ls-remote", this.reposUrl, "dummy"}, true, AllowExitCode.ALL, null, stringBuilderOutput, true) != 0) {
      String error;

      error = stringBuilderOutput.toString();

      // We know "Authentication" is included in the English message. We attempt to
      // cover the French case by testing for "Authentification" and "authentification"
      // as well, but the behavior of the git client with French messages, if ever that
      // exists, has not been tested.
      return !(error.contains("Authentication") || error.contains("Authentification") || error.contains("authentification"));
    } else {
      return true;
    }
  }

  @Override
  public boolean isReposExists() {
    boolean isReposExists;

    isReposExists = (this.executeGitCommand(new String[] {"ls-remote", this.reposUrl, "dummy"}, true, AllowExitCode.ALL, null, null, false) == 0);

    if (isReposExists) {
      DefaultGitImpl.logger.info("Git repository " + this.reposUrl + " exists.");
      return true;
    } else {
      DefaultGitImpl.logger.info("Git repository " + this.reposUrl + " does not exist.");
      return false;
    }
  }

  @Override
  public String getBranch(Path pathWorkspace) {
    Version version;
    StringBuilder stringBuilder;
    int exitCode;

    version = this.getPathWorkspaceVersion(pathWorkspace);

    if (version != null) {
      if (version.getVersionType() == VersionType.DYNAMIC) {
        return version.getVersion();
      } else {
        return null;
      }
    }

    stringBuilder = new StringBuilder();
    exitCode = this.executeGitCommand(new String[] {"symbolic-ref", "-q", "HEAD"}, false, AllowExitCode.ONE, pathWorkspace, stringBuilder, true);

    if (exitCode == 0) {
      String branch;

      branch = stringBuilder.toString();

      if (branch.startsWith("refs/heads/")) {
        String stringVersion;

        stringVersion = branch.substring(11);
        this.setPathWorkspaceVersion(pathWorkspace, new Version(VersionType.DYNAMIC, stringVersion));
        return stringVersion;
      } else {
        throw new RuntimeException("Unrecognized branch reference " + branch + " returned by git symbolic-ref.");
      }
    } else {
      return null;
    }
  }

  @Override
  public void config(Path pathWorkspace, String param, String value) {
    this.executeGitCommand(new String[] {"config", param, value}, false, AllowExitCode.NONE, pathWorkspace, null, false);
  }

  @Override
  public void clone(String reposUrl, Version version, Path pathWorkspace) {
    boolean isConfiguredReposUrl;
    boolean isDetachedHead;

    if (reposUrl == null) {
      reposUrl = this.reposUrl;
      isConfiguredReposUrl = true;
    } else {
      isConfiguredReposUrl = false;
    }

    DefaultGitImpl.setPathToDeleteOnShutdown.add(pathWorkspace);

    try {
      // We always specify --no-local in order to prevent Git from implementing its
      // optimizations when the remote is a file-based repository (or a simple path).
      // This is necessary in the context of Dragom since repositories within a
      // workspace can be used as remotes of each other for improving the performance of
      // some operations, but can come and go as the workspace evolves.
      if (version == null) {
        this.executeGitCommand(
            new String[] {"clone", "--no-local", "--no-checkout", reposUrl, pathWorkspace.toString()},
            isConfiguredReposUrl,
            AllowExitCode.NONE,
            null,
            null,
            false);
      } else {
        // The -b option takes a branch or tag name, but without the complete reference
        // prefix such as heads/master or tags/v-1.2.3. This means that it is not
        // straightforward to distinguish between branches and tags.
        this.executeGitCommand(
            new String[] {"clone", "--no-local", "-b", version.getVersion(), reposUrl, pathWorkspace.toString()},
            isConfiguredReposUrl,
            AllowExitCode.NONE,
            null,
            null,
            false);
      }
    } finally {
      DefaultGitImpl.setPathToDeleteOnShutdown.remove(pathWorkspace);
    }

    if (version != null) {
      // We need to verify the type of version checked out by checking whether we are in
      // a detached head state (tag) or not (branch).
      isDetachedHead = (this.getBranch(pathWorkspace) == null);

      if ((version.getVersionType() == VersionType.DYNAMIC) && isDetachedHead) {
        try {
          FileUtils.deleteDirectory(pathWorkspace.toFile());
        } catch (IOException ioe) {}

        throw new RuntimeException("Requested version is dynamic but checked out version is a tag.");
      }

      if ((version.getVersionType() == VersionType.STATIC) && !isDetachedHead) {
        try {
          FileUtils.deleteDirectory(pathWorkspace.toFile());
        } catch (IOException ioe) {}

        throw new RuntimeException("Requested version is static but checked out version is a branch.");
      }
    }
  }

  @Override
  public void fetch(Path pathWorkspace, String reposUrl, String refspec, boolean indFetchingIntoCurrentBranch, boolean indForce) {
    List<String> listArg;

    listArg = new ArrayList<String>();

    listArg.add("fetch");

    if (indFetchingIntoCurrentBranch) {
      listArg.add("--update-head-ok");
    }

    if (indForce) {
      listArg.add("--force");
    }

    if (reposUrl != null) {
      listArg.add(reposUrl);
    }

    if (refspec != null) {
      if (reposUrl == null) {
        listArg.add("origin");
      }

      listArg.add(refspec);
    }

    // The empty String[] argument to toArray is required for proper typing in Java.
    this.executeGitCommand(listArg.toArray(new String[] {}), reposUrl == null, AllowExitCode.NONE, pathWorkspace, null, false);
  }

  @Override
  public boolean pull(Path pathWorkspace) {
    String branch;
    int exitCode;

    branch = this.getBranch(pathWorkspace);

    if (branch == null) {
      throw new RuntimeException("Within " + pathWorkspace + " the HEAD is not a branch.");
    }

    exitCode = this.executeGitCommand(new String[] {"pull"}, true, AllowExitCode.ONE, pathWorkspace, null, false);

    return exitCode == 1;
  }

  @Override
  public boolean rebaseSimple(Path pathWorkspace) {
    String branch;
    int exitCode;

    branch = this.getBranch(pathWorkspace);

    if (branch == null) {
      throw new RuntimeException("Within " + pathWorkspace + " the HEAD is not a branch.");
    }

    branch = "refs/remotes/origin/" + branch;

    // We add "--" as a last argument since when a ref does no exist, Git complains
    // about the fact that the command is ambiguous.
    if (this.executeGitCommand(new String[] {"rev-parse", branch, "--"}, false, AllowExitCode.ALL, pathWorkspace, null, false) != 0) {
      DefaultGitImpl.logger.info("No rebase performed in " + pathWorkspace + " since upstream branch " + branch + " does not exist.");
      return false;
    }

    // Rebase onto the upstream tracking branch corresponding to the current branch.
    // This is the default behavior, but specifying the branch is more symmetrical
    // with merge mode below.
    exitCode = this.executeGitCommand(new String[] {"rebase", branch}, false, AllowExitCode.ONE, pathWorkspace, null, false);

    return exitCode == 1;
  }

  @Override
  public boolean mergeSimple(Path pathWorkspace) {
    String branch;
    int exitCode;

    branch = this.getBranch(pathWorkspace);

    if (branch == null) {
      throw new RuntimeException("Within " + pathWorkspace + " the HEAD is not a branch.");
    }

    branch = "refs/remotes/origin/" + branch;

    // We add "--" as a last argument since when a ref does no exist, Git complains
    // about the fact that the command is ambiguous.
    if (this.executeGitCommand(new String[] {"rev-parse", branch, "--"}, false, AllowExitCode.ALL, pathWorkspace, null, false) != 0) {
      DefaultGitImpl.logger.info("No merge performed in " + pathWorkspace + " since upstream branch " + branch + " does not exist.");
      return false;
    }

    exitCode = this.executeGitCommand(new String[] {"merge", branch}, false, AllowExitCode.ONE, pathWorkspace, null, false);

    return exitCode == 1;
  }

  @Override
  public void push(Path pathWorkspace, String gitRef) {
    if (gitRef != null) {
      this.executeGitCommand(new String[] {"push", "--set-upstream", "origin", gitRef + ':' + gitRef}, true, AllowExitCode.NONE, pathWorkspace, null, false);
    } else {
      this.executeGitCommand(new String[] {"push"}, true, AllowExitCode.NONE, pathWorkspace, null, false);
    }
  }

  @Override
  public void checkout(Path pathWorkspace, Version version) {
    Version versionCurrent;
    boolean isDetachedHead;

    versionCurrent = this.getPathWorkspaceVersion(pathWorkspace);

    if ((versionCurrent != null)  && versionCurrent.equals(version)) {
      return;
    }

    // The checkout command takes a branch or tag name, but without the complete
    // reference prefix such as heads/master or tags/v-1.2.3. This means that it is
    // not straightforward to distinguish between branches and tags.
    this.executeGitCommand(new String[] {"checkout", version.getVersion()}, false, AllowExitCode.NONE, pathWorkspace, null, false);

    // We need to verify the type of version checked out by checking whether we are in
    // a detached head state (tag) or not (branch).
    isDetachedHead = (this.getBranch(pathWorkspace) == null);

    if ((version.getVersionType() == VersionType.DYNAMIC) && isDetachedHead) {
      try {
        FileUtils.deleteDirectory(pathWorkspace.toFile());
      } catch (IOException ioe) {}

      throw new RuntimeException("Requested version is dynamic but checked out version is a tag.");
    }

    if ((version.getVersionType() == VersionType.STATIC) && !isDetachedHead) {
      try {
        FileUtils.deleteDirectory(pathWorkspace.toFile());
      } catch (IOException ioe) {}

      throw new RuntimeException("Requested version is static but checked out version is a branch.");
    }

    this.setPathWorkspaceVersion(pathWorkspace, version);
  }

  @Override
  public boolean isVersionExists(Path pathWorkspace, Version version) {
    // We add "--" as a last argument since when a ref does no exist, Git complains
    // about the fact that the command is ambiguous.
    if (this.executeGitCommand(new String[] {"rev-parse", this.convertToRef(pathWorkspace, version), "--"}, false, AllowExitCode.ALL, pathWorkspace, null, false) == 0) {
      DefaultGitImpl.logger.info("Version " + version + " exists.");
      return true;
    } else {
      DefaultGitImpl.logger.info("Version " + version + " does not exist.");
      return false;
    }
  }

  @Override
  public AheadBehindInfo getAheadBehindInfo(Path pathWorkspace) {
    String branch;
    StringBuilder stringBuilder;
    AheadBehindInfo aheadBehindInfo;
    int index;

    branch = this.getBranch(pathWorkspace);

    if (branch == null) {
      throw new RuntimeException("Within " + pathWorkspace + " the HEAD is not a branch.");
    }

    // Getting ahead/behind information is done with the "git for-each-ref" command
    // which will provide us with "behind" information for the desired branch.
    // Note that "git status -sb" also provides this information, but only for the
    // current branch, whereas "git for-each-ref" can provide that information for any
    // branch.

    stringBuilder = new StringBuilder();
    this.executeGitCommand(new String[] {"for-each-ref", "--format=%(upstream:track)", "refs/heads/" + branch}, false, AllowExitCode.NONE, pathWorkspace, stringBuilder, true);

    aheadBehindInfo = new AheadBehindInfo();

    // The result in stringBuilder is either empty (if local and remote repositories
    // are synchronized) or one of:
    // - [ahead #]
    // - [behind #]
    // - [ahead #, behind #]
    // where # is an integer specifying how far ahead or behind. The following parses
    // this information in a relatively efficient manner. Using Pattern to extract the
    // required information may have been more elegant, but certainly not as efficient.

    if ((index = stringBuilder.indexOf("ahead")) != -1) {
      int index2;

      index2 = stringBuilder.indexOf(",", index + 6);

      if (index2 == -1) {
        index2 = stringBuilder.indexOf("]", index + 6);
      }

      aheadBehindInfo.ahead = Integer.parseInt(stringBuilder.substring(index + 6, index2));
    }

    if ((index = stringBuilder.indexOf("behind")) != -1) {
      aheadBehindInfo.behind = Integer.parseInt(stringBuilder.substring(index + 7, stringBuilder.indexOf("]", index + 7)));
    }

    return aheadBehindInfo;
  }

  @Override
  public boolean isLocalChanges(Path pathWorkspace) {
    String branch;
    StringBuilder stringBuilder;

    branch = this.getBranch(pathWorkspace);

    if (branch == null) {
      throw new RuntimeException("Within " + pathWorkspace + " the HEAD is not a branch.");
    }

    // Verifying if a Git local repository contains changes that are not in the remote
    // repository involves verifying in the repository specified if there are local
    // changes that have not been committed. This is done with the "git status"
    // command.

    stringBuilder = new StringBuilder();
    this.executeGitCommand(new String[] {"status", "-s"}, false, AllowExitCode.NONE, pathWorkspace, stringBuilder, true);

    return (stringBuilder.length() != 0);
  }

  @Override
  public void push(Path pathWorkspace) {
    // We always set the upstream tracking information because because pushes can be
    // delayed and new branches can be pushed on the call to this method other than
    // the one immediately after creating the branch.
    this.executeGitCommand(new String[] {"push", "--all", "--set-upstream", "origin"}, true, AllowExitCode.NONE, pathWorkspace, null, false);

    // Unfortunately we cannot specify --all and --tags in the same command.
    // Maybe it is possible to specify --follow-tags with --all, but even if so,
    // this is not what we need since there may be exceptional cases where tags
    // are not reachable from a branch.
    this.executeGitCommand(new String[] {"push", "--tags"}, true, AllowExitCode.NONE, pathWorkspace, null, false);
  }

  @Override
  public Version getVersion(Path pathWorkspace) {
    Version version;
    String branch;
    StringBuilder stringBuilder;

    version = this.getPathWorkspaceVersion(pathWorkspace);

    if (version != null) {
      return version;
    }

    branch = this.getBranch(pathWorkspace);

    if (branch != null) {
      return new Version(VersionType.DYNAMIC, branch);
    }

    // branch is null it means we are in detached HEAD state and thus probably on a
    // tag.

    stringBuilder = new StringBuilder();
    this.executeGitCommand(new String[] {"describe", "--exact-match"}, false, AllowExitCode.NONE, pathWorkspace, stringBuilder, true);

    version = new Version(VersionType.STATIC, stringBuilder.toString());

    this.setPathWorkspaceVersion(pathWorkspace, version);

    return version;
  }

  @Override
  public List<Version> getListVersionStatic(Path pathWorkspace) {
    StringBuilder stringBuilder;
    BufferedReader bufferedReader;
    String tagLine;
    List<Version> listVersionStatic;

    try {
      stringBuilder = new StringBuilder();
      this.executeGitCommand(new String[] {"show-ref", "--tag", "-d"}, false, AllowExitCode.NONE, pathWorkspace, stringBuilder, true);

      bufferedReader = new BufferedReader(new StringReader(stringBuilder.toString()));
      listVersionStatic = new ArrayList<Version>();

      while ((tagLine = bufferedReader.readLine()) != null) {
        String[] arrayTagLineComponent;
        String tagRef;

        arrayTagLineComponent = tagLine.split("\\s+");

        tagRef = arrayTagLineComponent[1];

        if (tagRef.endsWith("^{}")) {
          String tagName;

          // A few magic numbers here, but not worth having constants.
          // 10 is the length of "refs/tags/" that prefixes each tag name.
          // 3 is the length of "^{}" that suffixes each tag name.
          tagName = tagRef.substring(10, tagRef.length() - 3);
          listVersionStatic.add(new Version(VersionType.STATIC, tagName));
        }
      }
    } catch (IOException ioe) {
      throw new RuntimeException(ioe);
    }

    return listVersionStatic;
  }

  @Override
  public void createBranch(Path pathWorkspace, String branch, boolean indSwitch) {
    this.executeGitCommand(new String[] {"branch", branch}, false, AllowExitCode.NONE, pathWorkspace, null, false);

    if (indSwitch) {
      this.checkout(pathWorkspace, new Version(VersionType.DYNAMIC, branch));
    }
  }

  @Override
  public void createTag(Path pathWorkspace, String tag, String message) {
    this.executeGitCommand(new String[] {"tag", "-m", message, tag}, false, AllowExitCode.NONE, pathWorkspace, null, false);
  }

  @Override
  public void addCommit(Path pathWorkspace, String message, Map<String, String> mapCommitAttr, boolean indPush) {
    String branch;

    branch = this.getBranch(pathWorkspace);

    if (branch == null) {
      throw new RuntimeException("Within " + pathWorkspace + " the HEAD is not a branch.");
    }

    this.executeGitCommand(new String[] {"add", "--all"}, false, AllowExitCode.NONE, pathWorkspace, null, false);

    // Git does not natively support commit attributes. It does support "notes" which
    // could be used to keep commit attributes. But it looks like this is not a robust
    // solution as notes are independent of commits and can easily be modified. And
    // also not all Git repository managers support Git notes. Specifically, Stash
    // does not seem to have full support for git notes.
    // For these reasons commit attributes are stored within the commit messages
    // themselves.
    if (mapCommitAttr != null) {
      try {
        message = (new ObjectMapper()).writeValueAsString(mapCommitAttr) + ' ' + message;
      } catch (IOException ioe) {
        throw new RuntimeException(ioe);
      }
    }

    // Commons Exec ends up calling Runtime.exec(String[], ...) with the command line
    // arguments. It looks like along the way double quotes within the arguments get
    // removed which, apart from being undesirable, causes a bug with the commit
    // messages that included a JSON object for the commit attributes. At the very
    // least it is required to use the addArgument(String, boolean handleQuote) method
    // to disable quote handling. Otherwise Commons Exec surrounds the argument with
    // single quotes when it contains double quotes, which we do not want. But we must
    // also escape double quotes to prevent Runtime.exec from removing them. I did not
    // find any reference to this behavior, and I am not 100% sure that it is
    // Runtime.exec's fault. But escaping the double quotes works.
    message = message.replace("\"", "\\\"");

    this.executeGitCommand(new String[] {"commit", "-m", message}, false, AllowExitCode.NONE, pathWorkspace, null, false);

    if (indPush) {
      this.push(pathWorkspace, "refs/heads/" + branch);
    }
  }

  @Override
  public String convertToRef(Path pathWorkspace, Version version) {
    if (version.getVersionType() == VersionType.STATIC) {
      // "^{tag}" ensures we consider only annotated tags.
      return "refs/tags/" + version.getVersion() + "^{tag}";
    } else {
      String ref;

      ref = "refs/heads/" + version.getVersion();

      // We add "--" as a last argument since when a ref does no exist, Git complains
      // about the fact that the command is ambiguous.
      if (this.executeGitCommand(new String[] {"rev-parse", ref, "--"}, false, AllowExitCode.ALL, pathWorkspace, null, false) == 0) {
        return ref;
      } else {
        return "refs/remotes/origin/" + version.getVersion();
      }
    }
  }

  @SuppressWarnings("unchecked")
  private Version getPathWorkspaceVersion(Path pathWorkspace) {
    ExecContext execContext;
    Map<Path, ModTimestampVersion> mapPathModTimestampVersion;
    ModTimestampVersion modTimestampVersion;

    execContext = ExecContextHolder.get();

    mapPathModTimestampVersion = (Map<Path, ModTimestampVersion>)execContext.getTransientData(DefaultGitImpl.TRANSIENT_DATA_MAP_PATH_MOD_TIMESTAMP_VERSION);

    if (mapPathModTimestampVersion == null) {
      mapPathModTimestampVersion = new HashMap<Path, ModTimestampVersion>();
      execContext.setTransientData(DefaultGitImpl.TRANSIENT_DATA_MAP_PATH_MOD_TIMESTAMP_VERSION, mapPathModTimestampVersion);
    }

    modTimestampVersion = mapPathModTimestampVersion.get(pathWorkspace);

    if (modTimestampVersion == null) {
      return null;
    }

    if (pathWorkspace.toFile().lastModified() != modTimestampVersion.modTimestamp) {
      return null;
    }

    return modTimestampVersion.version;
  }

  private void setPathWorkspaceVersion(Path pathWorkspace, Version version) {
    ExecContext execContext;
    Map<Path, ModTimestampVersion> mapPathModTimestampVersion;
    ModTimestampVersion modTimestampVersion;

    execContext = ExecContextHolder.get();

    mapPathModTimestampVersion = (Map<Path, ModTimestampVersion>)execContext.getTransientData(DefaultGitImpl.TRANSIENT_DATA_MAP_PATH_MOD_TIMESTAMP_VERSION);

    if (mapPathModTimestampVersion == null) {
      mapPathModTimestampVersion = new HashMap<Path, ModTimestampVersion>();
      execContext.setTransientData(DefaultGitImpl.TRANSIENT_DATA_MAP_PATH_MOD_TIMESTAMP_VERSION, mapPathModTimestampVersion);
    }

    modTimestampVersion = new ModTimestampVersion();

    modTimestampVersion.modTimestamp = pathWorkspace.toFile().lastModified();
    modTimestampVersion.version = version;

    mapPathModTimestampVersion.put(pathWorkspace, modTimestampVersion);
  }
}
