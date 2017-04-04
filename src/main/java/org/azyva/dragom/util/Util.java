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

package org.azyva.dragom.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.text.ParseException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.ResourceBundle;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.azyva.dragom.execcontext.ExecContext;
import org.azyva.dragom.execcontext.plugin.RuntimePropertiesPlugin;
import org.azyva.dragom.execcontext.plugin.UserInteractionCallbackPlugin;
import org.azyva.dragom.execcontext.support.ExecContextHolder;
import org.azyva.dragom.model.ClassificationNode;
import org.azyva.dragom.model.Module;
import org.azyva.dragom.model.Node;
import org.azyva.dragom.model.NodePath;
import org.azyva.dragom.model.Version;
import org.azyva.dragom.model.VersionType;
import org.azyva.dragom.model.config.PluginDefConfig;
import org.azyva.dragom.model.impl.DefaultNode;
import org.azyva.dragom.model.plugin.NodePlugin;
import org.azyva.dragom.model.plugin.NodePluginFactory;
import org.azyva.dragom.model.plugin.ScmPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Static utility methods.
 *
 * For now this class is a mixed bag of utility methods. With time and maturity
 * some groups of utility methods may be migrated to separate classes.
 *
 * @author David Raymond
 */
public final class Util {
  /**
   * Logger for the class.
   */
  private static final Logger logger = LoggerFactory.getLogger(Util.class);

  /**
   * Runtime property specifying whether confirmation is required.
   * <p>
   * If used as a prefix with ".{@code <context>}" as a suffix, it specifies whether
   * confirmation is required for that particular context.
   */
  public static final String RUNTIME_PROPERTY_IND_NO_CONFIRM = "IND_NO_CONFIRM";

  /**
   * Runtime property indicating to abort.
   * <p>
   * Being a runtime property it can be set at different levels before tool
   * execution, but this would not be useful as it would indicate to abort the tool
   * immediately. If is expected to be set only within the code.
   */
  public static final String RUNTIME_PROPERTY_ABORT = "ABORT";

  /**
   * Context for {@link Util#handleDoYouWantToContinue} that represents updating a
   * reference.
   *
   * <p>Used by multiple classes.
   */
  public static final String DO_YOU_WANT_TO_CONTINUE_CONTEXT_UPDATE_REFERENCE = "UPDATE_REFERENCE";

  /**
   * Context for {@link Util#handleDoYouWantToContinue} that represents merging a
   * Version into another.
   *
   * <p>Used by multiple classes.
   */
  public static final String DO_YOU_WANT_TO_CONTINUE_CONTEXT_MERGE = "MERGE";

  /**
   * Context for {@link Util#handleDoYouWantToContinue} that represents the fact
   * that during the creation of a static Version or the switch to a dynamic Version
   * one or more references were changed but not committed and the user requested to
   * abort. Should the commit be performed in that case?
   *
   * <p>Used by multiple classes.
   */
  public static final String DO_YOU_WANT_TO_CONTINUE_CONTEXT_COMMIT_REFERENCE_CHANGE_AFTER_ABORT = "COMMIT_REFERENCE_CHANGE_AFTER_ABORT";

  /**
   * Context for {@link Util#handleDoYouWantToContinue} that represents the fact
   * that conflicts occurred during a merge operation, or that diverging commits
   * exist and should not. Should we continue in that case?
   */
  public static final String DO_YOU_WANT_TO_CONTINUE_CONTEXT_MERGE_CONFLICTS = "MERGE_CONFLICTS";

  /**
   * Path to the static Dragom properties resource within the classpath.
   */
  private static final String DRAGOM_PROPERTIES_RESOURCE = "/META-INF/dragom.properties";

  /**
   * Path to the static default initialization properties resource within the
   * classpath.
   */
  private static final String DRAGOM_DEFAULT_INIT_PROPERTIES_RESOURCE = "/META-INF/dragom-init.properties";

  /**
   * Exceptional condition representing the fact that an exception is thrown during
   * the visit of a matched ModuleVersion.
   */
  public static final String EXCEPTIONAL_COND_EXCEPTION_THROWN_WHILE_VISITING_MODULE_VERSION = "EXCEPTION_THROWN_WHILE_VISITING_MODULE_VERSION";

  /**
   * Exceptional condition representing the fact that conflicts occurred during a
   * merge operation, or that diverging commits exist and should not.
   *
   * <p>Used by more than one class.
   */
  public static final String EXCEPTIONAL_COND_MERGE_CONFLICTS = "MERGE_CONFLICTS";

  /**
   * Transient data for the current {@link ToolExitStatus}.
   */
  private static final String TRANSIENT_DATA_TOOL_EXIT_STATUS = Util.class.getName() + ".ToolExitStatus";

  /**
   * Prefix for exceptional conditions.
   */
  public static final String PREFIX_EXCEPTIONAL_COND = "EXCEPTIONAL_COND_";

  /**
   * Suffix for the ToolStatus associated with an exceptional condition.
   */
  public static final String SUFFIX_EXCEPTIONAL_COND_EXIT_STATUS = ".EXIT_STATUS";

  /**
   * Suffix for the continuation indicator associated with an exceptional condition.
   */
  public static final String SUFFIX_EXCEPTIONAL_COND_CONTINUE = ".CONTINUE";

  /**
   * See description in ResourceBundle.
   */
  public static final String MSG_PATTERN_KEY_ALWAYS_NEVER_ASK_RESPONSE_CHOICES = "ALWAYS_NEVER_ASK_RESPONSE_CHOICES";

  /**
   * See description in ResourceBundle.
   */
  public static final String MSG_PATTERN_KEY_YES_RESPONSE = "YES_RESPONSE";

  /**
   * See description in ResourceBundle.
   */
  public static final String MSG_PATTERN_KEY_YES_ALWAYS_RESPONSE = "YES_ALWAYS_RESPONSE";

  /**
   * See description in ResourceBundle.
   */
  public static final String MSG_PATTERN_KEY_NEVER_RESPONSE = "NEVER_RESPONSE";

  /**
   * See description in ResourceBundle.
   */
  public static final String MSG_PATTERN_KEY_ALWAYS_NEVER_YES_NO_ASK_RESPONSE_CHOICES = "ALWAYS_NEVER_YES_NO_ASK_RESPONSE_CHOICES";

  /**
   * See description in ResourceBundle.
   */
  public static final String MSG_PATTERN_KEY_NO_RESPONSE = "NO_RESPONSE";

  /**
   * See description in ResourceBundle.
   */
  public static final String MSG_PATTERN_KEY_YES_ALWAYS_NO_RESPONSE_CHOICES = "YES_ALWAYS_NO_RESPONSE_CHOICES";

  /**
   * See description in ResourceBundle.
   */
  public static final String MSG_PATTERN_KEY_YES_ALWAYS_NO_ABORT_RESPONSE_CHOICES = "YES_ALWAYS_NO_ABORT_RESPONSE_CHOICES";

  /**
   * See description in ResourceBundle.
   */
  public static final String MSG_PATTERN_KEY_NO_ABORT_RESPONSE = "NO_ABORT_RESPONSE";

  /**
   * See description in ResourceBundle.
   */
  public static final String MSG_PATTERN_KEY_YES_NO_RESPONSE_CHOICES = "YES_NO_RESPONSE_CHOICES";

  /**
   * See description in ResourceBundle.
   */
  public static final String MSG_PATTERN_KEY_INVALID_RESPONSE_TRY_AGAIN = "INVALID_RESPONSE_TRY_AGAIN";

  /**
   * See description in ResourceBundle.
   */
  public static final String MSG_PATTERN_KEY_VERSION_FORMAT_HELP = "VERSION_FORMAT_HELP";

  /**
   * See description in ResourceBundle.
   */
  public static final String MSG_PATTERN_KEY_VERSION_FORMAT_HELP_VERSION_MUST_EXIST = "VERSION_FORMAT_HELP_VERSION_MUST_EXIST";

  /**
   * See description in ResourceBundle.
   */
  public static final String MSG_PATTERN_KEY_INCORRECT_VERSION_TYPE = "INCORRECT_VERSION_TYPE";

  /**
   * See description in ResourceBundle.
   */
  public static final String MSG_PATTERN_KEY_VERSION_DOES_NOT_EXIST = "VERSION_DOES_NOT_EXIST";

  /**
   * See description in ResourceBundle.
   */
  public static final String MSG_PATTERN_KEY_TRY_AGAIN = "TRY_AGAIN";

  /**
   * See description in ResourceBundle.
   */
  public static final String MSG_PATTERN_DO_YOU_WANT_TO_CONTINUE = "DO_YOU_WANT_TO_CONTINUE";

  /**
   * See description in ResourceBundle.
   */
  public static final String MSG_PATTERN_KEY_STARTING_JOB = "STARTING_JOB";

  /**
   * See description in ResourceBundle.
   */
  public static final String MSG_PATTERN_KEY_JOB_COMPLETED = "JOB_COMPLETED";

  /**
   * See description in ResourceBundle.
   */
  public static final String MSG_PATTERN_KEY_JOB_ABORTED_BY_USER = "JOB_ABORTED_BY_USER";

  /**
   * See description in ResourceBundle.
   */
  public static final String MSG_PATTERN_KEY_WORKSPACE_DIRECTORY_NOT_SYNC = "WORKSPACE_DIRECTORY_NOT_SYNC";

  /**
   * See description in ResourceBundle.
   */
  public static final String MSG_PATTERN_KEY_PREVIOUS_CHANGE_COMMITTED_SCM = "PREVIOUS_CHANGE_COMMITTED_SCM";

  /**
   * See description in ResourceBundle.
   */
  public static final String MSG_PATTERN_KEY_PREVIOUS_CHANGE_SCM = "PREVIOUS_CHANGE_SCM";

  /**
   * See description in ResourceBundle.
   */
  public static final String MSG_PATTERN_KEY_EXCEPTION_THROWN_WHILE_VISITING_MODULE_VERSION = "EXCEPTION_THROWN_WHILE_VISITING_MODULE_VERSION";

  /**
   * See description in ResourceBundle.
   */
  public static final String MSG_PATTERN_KEY_SETTING_TOOL_EXIT_STATUS = "SETTING_TOOL_EXIT_STATUS";

  /**
   * ResourceBundle specific to this class.
   * <p>
   * Being a utility class, this ResourceBundle also contains global locale-specific
   * resources which can be used by other classes.
   */
  private static final ResourceBundle resourceBundle = ResourceBundle.getBundle(Util.class.getName() + "ResourceBundle");

  /**
   * Possible tool exit status'.
   *
   * <p>An int result code is associated with each constant to allow easily
   * converting from the Java type to a process result code.
   */
  public static enum ToolExitStatus {
    /**
     * Success.
     */
    SUCCESS(0),

    /**
     * Error.
     */
    ERROR(1),

    /**
     * Warning.
     *
     * <p>In some contexts, this result is represented by the "unstable" state.
     */
    WARNING(2);

    private int exitStatus;

    private ToolExitStatus(int exitStatus) {
      this.exitStatus = exitStatus;
    }

    public int getExitStatus() {
      return this.exitStatus;
    }

    /**
     * @param toolExitStatusNew New ToolExitStatus.
     * @return Indicates if a new ToolExitStatus is more severe than the current one.
     */
    public boolean isMoreSevere(ToolExitStatus toolExitStatusNew) {
      switch (this) {
      case SUCCESS:
        return (toolExitStatusNew != SUCCESS);
      case ERROR:
        return false;
      case WARNING:
        return (toolExitStatusNew == ERROR);
      default:
        throw new RuntimeException("Must not get here.");
      }
    }
  }

  /**
   * Combines the {@link ToolExitStatus} and the continue indicator.
   *
   * <p>Returned by {@link Util#handleToolExitStatusAndContinueForExceptionalCond}.
   */
  public static class ToolExitStatusAndContinue {
    /**
     * ToolExitStatus.
     */
    public ToolExitStatus toolExitStatus;

    /**
     * Continue indicator.
     */
    public boolean indContinue;
  }

  /**
   * Indicates that the Dragom properties have been loaded.
   */
  private static boolean indDragomPropertiesLoaded;

  /**
   * Default initialization properties.
   */
  private static Properties propertiesDefaultInit;

  /**
   * Indicates that we are running on Windows.
   * <p>
   * A Boolean is used in order to have 3 states and implement a cache.
   */
  private static Boolean indWindows;

  /**
   * Indicates that we are running on a Posix-compliant system.
   * <p>
   * A Boolean is used in order to have 3 states and implement a cache.
   */
  private static Boolean indPosix;

  /**
   * Used by {@link #spaces}.
   */
  private static final StringBuilder stringBuilderSpaces = new StringBuilder();

  /**
   * ReadWriteLock to protect {@link #stringBuilderSpaces} since StringBuilder's
   * are not thread-safe.
   */
  private static final ReentrantReadWriteLock readWriteLockStringBuilderSpaces = new ReentrantReadWriteLock();

  /**
   * Infers a groupId segment from a module node path.
   *
   * <p>We talk about the groupId segment since a complete groupId will generally be
   * some prefix plus a "." to which the value returned is appended.
   *
   * <p>If the NodePath is not partial (see {@link NodePath#isPartial}), meaning
   * that it refers to a {@link Module} and not a {@link ClassificationNode}, the
   * parent NodePath is used (that of the parent ClassificationNode of the Module).
   *
   * @param nodePath NodePath.
   * @return GroupId segment.
   */
  public static String inferGroupIdSegmentFromNodePath(NodePath nodePath) {
    StringBuilder stringBuilder;
    int nodeCount;

    stringBuilder = new StringBuilder();
    nodeCount = nodePath.getNodeCount();

    if (!nodePath.isPartial()) {
      nodeCount--;
    }

    for (int i = 0; i < nodeCount; i++) {
      if (i != 0) {
        stringBuilder.append('.');
      }

      stringBuilder.append(Util.convertPascalCaseToLowercaseWithDashes(nodePath.getNodeName(i)));
    }

    return stringBuilder.toString();
  }

  /**
   * Converts a PascalCase (or camelCase) string to lowercase-with-dashes.
   *
   * @param stringPascalCase See description.
   * @return See description.
   */
  public static String convertPascalCaseToLowercaseWithDashes(String stringPascalCase) {
    StringBuilder stringBuilder;
    int charCount;

    stringBuilder = new StringBuilder();
    charCount = stringPascalCase.length();

    for (int i = 0; i < charCount; i++) {
      char character = stringPascalCase.charAt(i);
      if (Character.isUpperCase(character)) {
        if (i != 0) {
          stringBuilder.append('-');
        }

        stringBuilder.append(Character.toLowerCase(character));
      } else {
        stringBuilder.append(character);
      }
    }

    return stringBuilder.toString();
  }

  /**
   * Converts a lowercase-with-dashes string to PascalCase.
   *
   * @param stringLowercaseWithDashes See description.
   * @return See description.
   */
  public static String convertLowercaseWithDashesToPascalCase(String stringLowercaseWithDashes) {
    StringBuilder stringBuilder;
    int charCount;
    boolean indNextCharUppercase;

    stringBuilder = new StringBuilder();
    charCount = stringLowercaseWithDashes.length();
    indNextCharUppercase = true;

    for (int i = 0; i < charCount; i++) {
      char character = stringLowercaseWithDashes.charAt(i);

      if (character == '-') {
        indNextCharUppercase = true;
      } else if (indNextCharUppercase) {
        stringBuilder.append(Character.toUpperCase(character));
        indNextCharUppercase = false;
      } else {
        stringBuilder.append(character);
      }
    }

    return stringBuilder.toString();
  }

  /**
   * Verifies if a directory is empty.
   *
   * @param path Path of the directory.
   * @return See description.
   */
  public static boolean isDirectoryEmpty(Path path) {
    DirectoryStream<Path> directoryStream;

    try {
      directoryStream = Files.newDirectoryStream(path);
    } catch (IOException ioe) {
      throw new RuntimeException(ioe);
    }

    return !directoryStream.iterator().hasNext();
  }

  /**
   * Applies system properties from the META-INF/dragom.properties classpath
   * resource.
   * <p>
   * This method can be called from many places where it is expected that Dragom
   * properties be available as system properties. This class ensures that loading
   * the system properties is done only once.
   * <p>
   * System properties take precedence over Dragom properties.
   */
  public static void applyDragomSystemProperties() {
    Properties propertiesDragom;

    // Ideally this should be synchronized. But since it is expected that this method
    // be called once during initialization in a single-threaded context, it is not
    // worth bothering.
    if (!Util.indDragomPropertiesLoaded) {
      try (InputStream inputStream = Util.class.getResourceAsStream(Util.DRAGOM_PROPERTIES_RESOURCE)) {
        if (inputStream != null) {
          Util.logger.debug("Loading properties from classpath resource " + Util.DRAGOM_PROPERTIES_RESOURCE + " into system properties.");

          propertiesDragom = new Properties();

          propertiesDragom.load(inputStream);

          for (Map.Entry<Object, Object> mapEntry: propertiesDragom.entrySet()) {
            String key;

            key = (String)mapEntry.getKey();

            if (System.getProperty(key) == null) {
              System.setProperty(key, (String)mapEntry.getValue());
            }
          }
        }
      } catch (IOException ioe) {
        throw new RuntimeException(ioe);
      }
    }
  }

  /**
   * @return Default initialization properties loaded from dragom-init.properties.
   */
  public static Properties getPropertiesDefaultInit() {
    // Ideally this should be synchronized. But since it is expected that this method
    // be called once during initialization in a single-threaded context, it is not
    // worth bothering.
    if (Util.propertiesDefaultInit == null) {
      try (InputStream inputStream = Util.class.getResourceAsStream(Util.DRAGOM_DEFAULT_INIT_PROPERTIES_RESOURCE)) {
        if (inputStream != null) {
          Util.logger.debug("Loading initialization properties from classpath resource " + Util.DRAGOM_DEFAULT_INIT_PROPERTIES_RESOURCE + '.');

          Util.propertiesDefaultInit = new Properties();

          Util.propertiesDefaultInit.load(inputStream);
        }
      } catch (IOException ioe) {
        throw new RuntimeException(ioe);
      }
    }

    return Util.propertiesDefaultInit;
  }

  /**
   * Helper method to retrieve attributes from a message.
   * <p>
   * These attributes are stored in a JSONObject at the beginning of the message.
   * The message must therefore start with "{" and continue to a matching closing
   * "}". For now, embedded JSONObject or "}" are not supported.
   * <p>
   * If the message does not start with "{", an empty Map is returned (not null).
   * <p>
   * This is intended to be used for storing arbitrary attributes within commit
   * and other messages in SCM's.
   *
   * @param message Message.
   * @param mapAttr Map that will be filled in and returned. If null, a new Map is
   *   created, in which case it should not be modified by the caller (it may be
   *   immutable, especially if empty).
   * @return Map of attributes.
   */
  public static Map<String, String> getJsonAttr(String message, Map<String, String> mapAttr) {
    int indexClosingBrace;
    JsonNode jsonNode;
    Iterator<String> iteratorFieldName;

    if ((message.length() == 0) || (message.charAt(0) != '{')) {
      return (mapAttr == null) ? Collections.<String, String>emptyMap() : mapAttr;
    }

    indexClosingBrace = message.indexOf('}');

    try {
      jsonNode = (new ObjectMapper()).readTree(message.substring(0, indexClosingBrace + 1));
    } catch (IOException ioe) {
      throw new RuntimeException(ioe);
    }

    if (mapAttr == null) {
      mapAttr = new HashMap<String, String>();
    }

    iteratorFieldName = jsonNode.fieldNames();

    while (iteratorFieldName.hasNext()) {
      String fieldName;
      JsonNode jsonNodeFieldValue;

      fieldName = iteratorFieldName.next();
      jsonNodeFieldValue = jsonNode.get(fieldName);

      if ((jsonNodeFieldValue != null) && jsonNodeFieldValue.isTextual()) {
        mapAttr.put(fieldName, jsonNodeFieldValue.asText());
      }
    }

    return mapAttr;
  }

  /**
   * Helper method to remove commit attributes from a commit message.
   *
   * These attributes are stored in a JSONObject at the beginning of the commit
   * message. The commit message must therefore start with "{" and continue to a
   * matching closing "}". For now, embedded JSONObject or "}" are not supported.
   *
   * If the commit message does not start with "{", the message itself is returned.
   *
   * @param commitMessage Commit message.
   * @return Commit message with attributes removed.
   */
  public static String getCommitMessageWithoutAttr(String commitMessage) {
    int indexClosingBrace;

    if ((commitMessage.length() == 0) || (commitMessage.charAt(0) != '{')) {
      return commitMessage;
    }

    indexClosingBrace = commitMessage.indexOf('}');

    return commitMessage.substring(indexClosingBrace + 1);
  }

  /**
   * Facilitates interpreting Boolean objects.
   *
   * @param aBoolean Boolean.
   * @return true if aBoolean is not null and true (null is assumed to mean false).
   */
  public static boolean isNotNullAndTrue(Boolean aBoolean) {
    return ((aBoolean != null) && aBoolean.booleanValue());
  }

  /**
   * Facilitates interpreting Boolean objects.
   *
   * @param stringBoolean null, "true" or "false".
   * @return true if stringBoolean is not null and "true" (null is assumed to mean
   *   false).
   */
  public static boolean isNotNullAndTrue(String stringBoolean) {
    return ((stringBoolean != null) && Boolean.valueOf(stringBoolean));
  }

  /**
   * @param msgPatternKey Message pattern key within the ResourceBundle.
   * @return Message pattern associated with the key.
   */
  public static String getLocalizedMsgPattern(String msgPatternKey) {
    return Util.resourceBundle.getString(msgPatternKey);
  }

  /**
   * Facilitates letting the user input a AlwaysNeverAskUserResponse.
   *
   * If info ends with "*" it is replaced with an appropriate response choice
   * string.
   *
   * As a convenience, "1" and "0" can also be entered by the user to mean "ALWAYS"
   * and "NEVER" respectively.
   *
   * The user can also enter the string value of the enum constants ("ALWAYS",
   * "NEVER" and "YES_ASK").
   *
   * @param userInteractionCallbackPlugin UserInteractionCallbackPlugin.
   * @param prompt See corresponding parameter in
   *   UserInteractionCallbackPlugin.getInfo.
   * @param alwaysNeverAskUserResponseDefaultValue Default user response. It is
   *   translated to the defaultValue parameter in
   *   UserInteractionCallbackPlugin.getInfo.
   * @return AlwaysNeverAskUserResponse.
   */
  public static AlwaysNeverAskUserResponse getInfoAlwaysNeverAskUserResponse(UserInteractionCallbackPlugin userInteractionCallbackPlugin, String prompt, AlwaysNeverAskUserResponse alwaysNeverAskUserResponseDefaultValue) {
    String alwaysNeverAskResponseChoices;
    String alwaysResponse;
    String neverResponse;
    String askResponse;
    String userResponse;
    AlwaysNeverAskUserResponse alwaysNeverAskUserResponse;

    alwaysNeverAskResponseChoices = Util.resourceBundle.getString(Util.MSG_PATTERN_KEY_ALWAYS_NEVER_ASK_RESPONSE_CHOICES);
    alwaysResponse = Util.resourceBundle.getString(Util.MSG_PATTERN_KEY_YES_ALWAYS_RESPONSE);
    neverResponse = Util.resourceBundle.getString(Util.MSG_PATTERN_KEY_NEVER_RESPONSE);
    askResponse = Util.resourceBundle.getString(Util.MSG_PATTERN_KEY_YES_RESPONSE);

    if (prompt.endsWith("*")) {
      String defaultValue = null;

      switch (alwaysNeverAskUserResponseDefaultValue) {
      case ALWAYS:
        defaultValue = alwaysResponse;
        break;

      case NEVER:
        defaultValue = neverResponse;
        break;

      case YES_ASK:
        defaultValue = askResponse;
        break;
      }

      prompt = prompt.substring(0, prompt.length() - 1) + " (" + alwaysNeverAskResponseChoices + ") [" + defaultValue + "]? ";
    }

    do {
      userResponse = userInteractionCallbackPlugin.getInfoWithDefault(prompt, alwaysNeverAskUserResponseDefaultValue.toString());

      userResponse = userResponse.toUpperCase().trim();

      alwaysNeverAskUserResponse = null;

      try {
        alwaysNeverAskUserResponse = AlwaysNeverAskUserResponse.valueOf(userResponse);
      } catch (IllegalArgumentException iae) {
        if (userResponse.equals(alwaysResponse) || userResponse.equals("1")) {
          alwaysNeverAskUserResponse = AlwaysNeverAskUserResponse.ALWAYS;
        } else if (userResponse.equals(neverResponse) || userResponse.equals("0")) {
            alwaysNeverAskUserResponse = AlwaysNeverAskUserResponse.NEVER;
        } else if (userResponse.equals(askResponse)) {
          alwaysNeverAskUserResponse = AlwaysNeverAskUserResponse.YES_ASK;
        }
      }

      if (alwaysNeverAskUserResponse == null) {
        userInteractionCallbackPlugin.provideInfo(Util.resourceBundle.getString(Util.MSG_PATTERN_KEY_INVALID_RESPONSE_TRY_AGAIN));
        continue;
      }

      break;
    } while (true);

    return alwaysNeverAskUserResponse;
  }

  /**
   * This is an extension to the method getInfoAlwaysNeverAskUserResponse that
   * handles storing the user response in a runtime property.
   * <p>
   * First it gets the value of the runtime property runtimeProperty. If it is
   * AlwaysNeverAskUserResponse.ASK (or null) it delegates to the
   * getInfoAlwaysNeverAskUserResponse method. If the user does not respond
   * AlwaysNeverAskUserResponse.ASK, the response is written back to the runtime
   * property (ASK is the default value for the property).
   * <p>
   * This is at the limit of being generic. It is very specific to one way of
   * handling AlwaysNeverAskUserResponse user input together with a runtime
   * property. But this idiom occurs in many places in Dragom and it was deemed
   * worth factoring it out.
   *
   * @param runtimePropertiesPlugin RuntimePropertiesPlugin.
   * @param runtimeProperty Runtime property.
   * @param userInteractionCallbackPlugin See corresponding parameter in
   *   getInfoAlwaysNeverAskUserResponse.
   * @param prompt See corresponding parameter in getInfoAlwaysNeverAskUserResponse.
   * @return AlwaysNeverAskUserResponse.
   */
  public static AlwaysNeverAskUserResponse getInfoAlwaysNeverAskUserResponseAndHandleAsk(RuntimePropertiesPlugin runtimePropertiesPlugin, String runtimeProperty, UserInteractionCallbackPlugin userInteractionCallbackPlugin, String prompt) {
    AlwaysNeverAskUserResponse alwaysNeverAskUserResponse;

    alwaysNeverAskUserResponse = AlwaysNeverAskUserResponse.valueOfWithAskDefault(runtimePropertiesPlugin.getProperty(null, runtimeProperty));

    if (alwaysNeverAskUserResponse.isAsk()) {
      alwaysNeverAskUserResponse = Util.getInfoAlwaysNeverAskUserResponse(userInteractionCallbackPlugin, prompt, AlwaysNeverAskUserResponse.YES_ASK);

      if (!alwaysNeverAskUserResponse.isAsk()) {
        runtimePropertiesPlugin.setProperty(null, runtimeProperty, alwaysNeverAskUserResponse.toString());
      }
    }

    return alwaysNeverAskUserResponse;
  }

  /**
   * Facilitates letting the user input a AlwaysNeverYesNoAskUserResponse.
   *
   * If info ends with "*" it is replaced with an appropriate response choice
   * string.
   *
   * As a convenience, "1" and "0" can also be entered by the user to mean "ALWAYS"
   * and "NEVER" respectively.
   *
   * The user can also enter the string value of the enum constants ("ALWAYS",
   * "NEVER", "YES_ASK" and "NO_ASK").
   *
   * @param userInteractionCallbackPlugin UserInteractionCallbackPlugin.
   * @param prompt See corresponding parameter in
   *   UserInteractionCallbackPlugin.getInfo.
   * @param alwaysNeverYesNoAskUserResponseDefaultValue Default user response. It is
   *   translated to the defaultValue parameter in
   *   UserInteractionCallbackPlugin.getInfo.
   * @return AlwaysNeverYesNoAskUserResponse.
   */
  public static AlwaysNeverYesNoAskUserResponse getInfoAlwaysNeverYesNoAskUserResponse(UserInteractionCallbackPlugin userInteractionCallbackPlugin, String prompt, AlwaysNeverYesNoAskUserResponse alwaysNeverYesNoAskUserResponseDefaultValue) {
    String alwaysNeverYesNoAskResponseChoices;
    String alwaysResponse;
    String neverResponse;
    String yesAskResponse;
    String noAskResponse;
    String userResponse;
    AlwaysNeverYesNoAskUserResponse alwaysNeverYesNoAskUserResponse;

    alwaysNeverYesNoAskResponseChoices = Util.resourceBundle.getString(Util.MSG_PATTERN_KEY_ALWAYS_NEVER_YES_NO_ASK_RESPONSE_CHOICES);
    alwaysResponse = Util.resourceBundle.getString(Util.MSG_PATTERN_KEY_YES_ALWAYS_RESPONSE);
    neverResponse = Util.resourceBundle.getString(Util.MSG_PATTERN_KEY_NEVER_RESPONSE);
    yesAskResponse = Util.resourceBundle.getString(Util.MSG_PATTERN_KEY_YES_RESPONSE);
    noAskResponse = Util.resourceBundle.getString(Util.MSG_PATTERN_KEY_NO_RESPONSE);

    if (prompt.endsWith("*")) {
      String defaultValue = null;

      switch (alwaysNeverYesNoAskUserResponseDefaultValue) {
      case ALWAYS:
        defaultValue = alwaysResponse;
        break;

      case NEVER:
        defaultValue = neverResponse;
        break;

      case YES_ASK:
        defaultValue = yesAskResponse;
        break;

      case NO_ASK:
        defaultValue = noAskResponse;
        break;
      }

      prompt = prompt.substring(0, prompt.length() - 1) + " (" + alwaysNeverYesNoAskResponseChoices + ") [" + defaultValue + "]? ";
    }

    do {
      userResponse = userInteractionCallbackPlugin.getInfoWithDefault(prompt, alwaysNeverYesNoAskUserResponseDefaultValue.toString());

      userResponse = userResponse.toUpperCase().trim();

      alwaysNeverYesNoAskUserResponse = null;

      try {
        alwaysNeverYesNoAskUserResponse = AlwaysNeverYesNoAskUserResponse.valueOf(userResponse);
      } catch (IllegalArgumentException iae) {
        if (userResponse.equals(alwaysResponse) || userResponse.equals("1")) {
          alwaysNeverYesNoAskUserResponse = AlwaysNeverYesNoAskUserResponse.ALWAYS;
        } else if (userResponse.equals(neverResponse) || userResponse.equals("0")) {
            alwaysNeverYesNoAskUserResponse = AlwaysNeverYesNoAskUserResponse.NEVER;
        } else if (userResponse.equals(yesAskResponse)) {
          alwaysNeverYesNoAskUserResponse = AlwaysNeverYesNoAskUserResponse.YES_ASK;
        } else if (userResponse.equals(noAskResponse)) {
          alwaysNeverYesNoAskUserResponse = AlwaysNeverYesNoAskUserResponse.NO_ASK;
        }
      }

      if (alwaysNeverYesNoAskUserResponse == null) {
        userInteractionCallbackPlugin.provideInfo(Util.resourceBundle.getString(Util.MSG_PATTERN_KEY_INVALID_RESPONSE_TRY_AGAIN));
        continue;
      }

      break;
    } while (true);

    return alwaysNeverYesNoAskUserResponse;
  }

  /**
   * This is an extension to the method getInfoAlwaysNeverYesNoAskUserResponse that
   * handles storing the user response in a runtime property.
   * <p>
   * First it gets the value of the runtime property runtimeProperty. If it is
   * AlwaysNeverYesNoAskUserResponse.YES_ASK (or null) or
   * AlwaysNeverYesNoAskUserResponse.NO_ASK it delegates to the
   * getInfoAlwaysNeverYesNoAskUserResponse method with YES_ASK and NO_ASK
   * respectively as the default value. The response is written back to the runtime
   * property, unless it is AlwaysNeverYesNoAskUserResponse.YES_ASK and it was null
   * to start with (YES_ASK is the default value for the property).
   * <p>
   * This is at the limit of being generic. It is very specific to one way of
   * handling AlwaysNeverYesNoAskUserResponse user input together with a runtime
   * property. But this idiom occurs in many places in Dragom and it was deemed
   * worth factoring it out.
   *
   * @param runtimePropertiesPlugin RuntimePropertiesPlugin.
   * @param runtimeProperty Runtime property.
   * @param userInteractionCallbackPlugin See corresponding parameter in
   *   getInfoAlwaysNeverYesNoAskUserResponse.
   * @param prompt See corresponding parameter in
   *   getInfoAlwaysNeverYesNoAskUserResponse.
   * @return AlwaysNeverYesNoAskUserResponse.
   */
  public static AlwaysNeverYesNoAskUserResponse getInfoAlwaysNeverYesNoAskUserResponseAndHandleAsk(RuntimePropertiesPlugin runtimePropertiesPlugin, String runtimeProperty, UserInteractionCallbackPlugin userInteractionCallbackPlugin, String prompt) {
    AlwaysNeverYesNoAskUserResponse alwaysNeverYesNoAskUserResponse;

    alwaysNeverYesNoAskUserResponse = AlwaysNeverYesNoAskUserResponse.valueOfWithYesAskDefault(runtimePropertiesPlugin.getProperty(null, runtimeProperty));

    if (alwaysNeverYesNoAskUserResponse.isAsk()) {
      AlwaysNeverYesNoAskUserResponse alwaysNeverYesNoAskUserResponseOrg;

      alwaysNeverYesNoAskUserResponseOrg = alwaysNeverYesNoAskUserResponse;
      alwaysNeverYesNoAskUserResponse = Util.getInfoAlwaysNeverYesNoAskUserResponse(userInteractionCallbackPlugin, prompt, alwaysNeverYesNoAskUserResponse);

      if (alwaysNeverYesNoAskUserResponse != alwaysNeverYesNoAskUserResponseOrg) {
        runtimePropertiesPlugin.setProperty(null, runtimeProperty, alwaysNeverYesNoAskUserResponse.toString());
      }
    }

    return alwaysNeverYesNoAskUserResponse;
  }

  /**
   * Facilitates letting the user input a YesAlwaysNoUserResponse.
   *
   * If info ends with "*" it is replaced with an appropriate response choice
   * string.
   *
   * As a convenience, "1" and "0" can also be entered by the user to mean
   * "YES_ALWAYS" and "NO" respectively.
   *
   * The user can also enter the string value of the enum constants ("YES",
   * "YES_ALWAYS" and "NO").
   *
   * @param userInteractionCallbackPlugin UserInteractionCallbackPlugin.
   * @param prompt See corresponding parameter in
   *   UserInteractionCallbackPlugin.getInfo.
   * @param yesAlwaysNoUserResponseDefaultValue Default user response. It is
   *   translated to the defaultValue parameter in
   *   UserInteractionCallbackPlugin.getInfo.
   * @return YesAlwaysNoUserResponse.
   */
  public static YesAlwaysNoUserResponse getInfoYesAlwaysNoUserResponse(UserInteractionCallbackPlugin userInteractionCallbackPlugin, String prompt, YesAlwaysNoUserResponse yesAlwaysNoUserResponseDefaultValue) {
    String yesAlwaysNoResponseChoices;
    String yesResponse;
    String yesAlwaysResponse;
    String noResponse;
    String userResponse;
    YesAlwaysNoUserResponse yesAlwaysNoUserResponse;

    yesAlwaysNoResponseChoices = Util.resourceBundle.getString(Util.MSG_PATTERN_KEY_YES_ALWAYS_NO_RESPONSE_CHOICES);
    yesResponse = Util.resourceBundle.getString(Util.MSG_PATTERN_KEY_YES_RESPONSE);
    yesAlwaysResponse = Util.resourceBundle.getString(Util.MSG_PATTERN_KEY_YES_ALWAYS_RESPONSE);
    noResponse = Util.resourceBundle.getString(Util.MSG_PATTERN_KEY_NO_RESPONSE);

    if (prompt.endsWith("*")) {
      String defaultValue = null;

      switch (yesAlwaysNoUserResponseDefaultValue) {
      case YES:
        defaultValue = yesResponse;
        break;

      case YES_ALWAYS:
        defaultValue = yesAlwaysResponse;
        break;

      case NO:
        defaultValue = noResponse;
        break;

      case NO_ABORT:
        throw new RuntimeException("NO_ABORT is not supported by this method.");
      }

      prompt = prompt.substring(0, prompt.length() - 1) + " (" + yesAlwaysNoResponseChoices + ") [" + defaultValue + "]? ";
    }

    do {
      userResponse = userInteractionCallbackPlugin.getInfoWithDefault(prompt, yesAlwaysNoUserResponseDefaultValue.toString());

      userResponse = userResponse.toUpperCase().trim();

      yesAlwaysNoUserResponse = null;

      try {
        yesAlwaysNoUserResponse = YesAlwaysNoUserResponse.valueOf(userResponse);
      } catch (IllegalArgumentException iae) {
        if (userResponse.equals(yesResponse)) {
          yesAlwaysNoUserResponse = YesAlwaysNoUserResponse.YES;
        } else if (userResponse.equals(yesAlwaysResponse) || userResponse.equals("1")) {
          yesAlwaysNoUserResponse = YesAlwaysNoUserResponse.YES_ALWAYS;
        } else if (userResponse.equals(noResponse) || userResponse.equals("0")) {
          yesAlwaysNoUserResponse = YesAlwaysNoUserResponse.NO;
        }
      }

      if (yesAlwaysNoUserResponse == null) {
        userInteractionCallbackPlugin.provideInfo(Util.resourceBundle.getString(Util.MSG_PATTERN_KEY_INVALID_RESPONSE_TRY_AGAIN));
        continue;
      }

      break;
    } while (true);

    return yesAlwaysNoUserResponse;
  }

  /**
   * Facilitates letting the user input a YesAlwaysNoUserResponse with support for
   * "no" and "abort" responses.
   *
   * If info ends with "*" it is replaced with an appropriate response choice
   * string.
   *
   * As a convenience, "1" and "0" can also be entered by the user to mean
   * "YES_ALWAYS" and "NO_ABORT" respectively.
   *
   * The user can also enter the string value of the enum constants ("YES",
   * "YES_ALWAYS", "NO" and "NO_ABORT").
   *
   * @param userInteractionCallbackPlugin UserInteractionCallbackPlugin.
   * @param prompt See corresponding parameter in
   *   UserInteractionCallbackPlugin.getInfo.
   * @param yesAlwaysNoUserResponseDefaultValue Default user response. It is
   *   translated to the defaultValue parameter in
   *   UserInteractionCallbackPlugin.getInfo.
   * @return YesAlwaysNoUserResponse.
   */
  public static YesAlwaysNoUserResponse getInfoYesAlwaysNoAbortUserResponse(UserInteractionCallbackPlugin userInteractionCallbackPlugin, String prompt, YesAlwaysNoUserResponse yesAlwaysNoUserResponseDefaultValue) {
    String yesAlwaysNoAbortResponseChoices;
    String yesResponse;
    String yesAlwaysResponse;
    String noResponse;
    String noAbortResponse;
    String userResponse;
    YesAlwaysNoUserResponse yesAlwaysNoUserResponse;

    yesAlwaysNoAbortResponseChoices = Util.resourceBundle.getString(Util.MSG_PATTERN_KEY_YES_ALWAYS_NO_ABORT_RESPONSE_CHOICES);
    yesResponse = Util.resourceBundle.getString(Util.MSG_PATTERN_KEY_YES_RESPONSE);
    yesAlwaysResponse = Util.resourceBundle.getString(Util.MSG_PATTERN_KEY_YES_ALWAYS_RESPONSE);
    noResponse = Util.resourceBundle.getString(Util.MSG_PATTERN_KEY_NO_RESPONSE);
    noAbortResponse = Util.resourceBundle.getString(Util.MSG_PATTERN_KEY_NO_ABORT_RESPONSE);

    if (prompt.endsWith("*")) {
      String defaultValue = null;

      switch (yesAlwaysNoUserResponseDefaultValue) {
      case YES:
        defaultValue = yesResponse;
        break;

      case YES_ALWAYS:
        defaultValue = yesAlwaysResponse;
        break;

      case NO:
        defaultValue = noResponse;
        break;

      case NO_ABORT:
        defaultValue = noAbortResponse;
        break;
      }

      prompt = prompt.substring(0, prompt.length() - 1) + " (" + yesAlwaysNoAbortResponseChoices + ") [" + defaultValue + "]? ";
    }

    do {
      userResponse = userInteractionCallbackPlugin.getInfoWithDefault(prompt, yesAlwaysNoUserResponseDefaultValue.toString());

      userResponse = userResponse.toUpperCase().trim();

      yesAlwaysNoUserResponse = null;

      try {
        yesAlwaysNoUserResponse = YesAlwaysNoUserResponse.valueOf(userResponse);
      } catch (IllegalArgumentException iae) {
        if (userResponse.equals(yesResponse)) {
          yesAlwaysNoUserResponse = YesAlwaysNoUserResponse.YES;
        } else if (userResponse.equals(yesAlwaysResponse) || userResponse.equals("1")) {
          yesAlwaysNoUserResponse = YesAlwaysNoUserResponse.YES_ALWAYS;
        } else if (userResponse.equals(noResponse)) {
          yesAlwaysNoUserResponse = YesAlwaysNoUserResponse.NO;
        } else if (userResponse.equals(noAbortResponse) || userResponse.equals("0")) {
          yesAlwaysNoUserResponse = YesAlwaysNoUserResponse.NO_ABORT;
        }
      }

      if (yesAlwaysNoUserResponse == null) {
        userInteractionCallbackPlugin.provideInfo(Util.resourceBundle.getString(Util.MSG_PATTERN_KEY_INVALID_RESPONSE_TRY_AGAIN));
        continue;
      }

      break;
    } while (true);

    return yesAlwaysNoUserResponse;
  }

  /**
   * This method is very similar to getInfoYesAlwaysNoUserResponse except that it
   * does not support the "YES_ALWAYS" response. It still returns a
   * YesAlwaysNoUserResponse but YesAlwaysNoUserResponse.YES_ALWAYS will not be
   * returned.
   *
   * @param userInteractionCallbackPlugin UserInteractionCallbackPlugin.
   * @param prompt See corresponding parameter in
   *   UserInteractionCallbackPlugin.getInfo.
   * @param yesAlwaysNoUserResponseDefaultValue Default user response. It is
   *   translated to the defaultValue parameter in
   *   UserInteractionCallbackPlugin.getInfo.
   * @return YesAlwaysNoUserResponse.
   */
  public static YesAlwaysNoUserResponse getInfoYesNoUserResponse(UserInteractionCallbackPlugin userInteractionCallbackPlugin, String prompt, YesAlwaysNoUserResponse yesAlwaysNoUserResponseDefaultValue) {
    String yesNoResponseChoices;
    String yesResponse;
    String noResponse;
    String userResponse;
    YesAlwaysNoUserResponse yesAlwaysNoUserResponse;

    yesNoResponseChoices = Util.resourceBundle.getString(Util.MSG_PATTERN_KEY_YES_NO_RESPONSE_CHOICES);
    yesResponse = Util.resourceBundle.getString(Util.MSG_PATTERN_KEY_YES_RESPONSE);
    noResponse = Util.resourceBundle.getString(Util.MSG_PATTERN_KEY_NO_RESPONSE);

    if (prompt.endsWith("*")) {
      String defaultValue = null;

      switch (yesAlwaysNoUserResponseDefaultValue) {
      case YES:
        defaultValue = yesResponse;
        break;

      case YES_ALWAYS:
        throw new RuntimeException("YES_ALWAYS is not supported by this method.");

      case NO:
        defaultValue = noResponse;
        break;

      case NO_ABORT:
        throw new RuntimeException("NO_ABORT is not supported by this method.");
      }

      prompt = prompt.substring(0, prompt.length() - 1) + " (" + yesNoResponseChoices + ") [" + defaultValue + "]? ";
    }

    do {
      userResponse = userInteractionCallbackPlugin.getInfoWithDefault(prompt, yesAlwaysNoUserResponseDefaultValue.toString());

      userResponse = userResponse.toUpperCase().trim();

      yesAlwaysNoUserResponse = null;

      try {
        yesAlwaysNoUserResponse = YesAlwaysNoUserResponse.valueOf(userResponse);

        if (yesAlwaysNoUserResponse == YesAlwaysNoUserResponse.YES_ALWAYS) {
          yesAlwaysNoUserResponse = null;
        }
      } catch (IllegalArgumentException iae) {
        if (userResponse.equals(yesResponse) || userResponse.equals("1")) {
          yesAlwaysNoUserResponse = YesAlwaysNoUserResponse.YES;
        } else if (userResponse.equals(noResponse) || userResponse.equals("0")) {
          yesAlwaysNoUserResponse = YesAlwaysNoUserResponse.NO;
        }
      }

      if (yesAlwaysNoUserResponse == null) {
        userInteractionCallbackPlugin.provideInfo(Util.resourceBundle.getString(Util.MSG_PATTERN_KEY_INVALID_RESPONSE_TRY_AGAIN));
        continue;
      }

      break;
    } while (true);

    return yesAlwaysNoUserResponse;
  }

  /**
   * Facilitates letting the user input a Version.
   *
   * If info ends with "*" it is replaced with text that guides the user in inputting
   * the Version.
   *
   * If scmPlugin is not null that text is
   * " (use the format &lt;format&gt;/&lt;version&gt;; version must exist) [&lt;default&gt;]? "
   * where &lt;format&gt; is "S", "D" or "S/D" depending on whether versionType is
   * VersionType.STATIC, VersionType.DYNAMIC or null, and where &lt;default&gt; is
   * versionDefaultValue. If versionDefaultValue is null, the last part
   * " [&lt;default&gt;]" is not included.
   *
   * If scmPlugin is null that text is similar, but excludes that part saying
   * that the Version must exist.
   *
   * If versionType is not null, the type of Version will be validated to be as
   * such.
   *
   * If scmPlugin is not null, the existence of the Version will be validated.
   *
   * @param versionType VersionType.
   * @param scmPlugin ScmPlugin.
   * @param userInteractionCallbackPlugin UserInteractionCallbackPlugin.
   * @param prompt See corresponding parameter in
   *   UserInteractionCallbackPlugin.getInfo.
   * @param versionDefaultValue Default user response. It is translated to the
   *   defaultValue parameter in UserInteractionCallbackPlugin.getInfo.
   * @return Version.
   */
  public static Version getInfoVersion(VersionType versionType, ScmPlugin scmPlugin, UserInteractionCallbackPlugin userInteractionCallbackPlugin, String prompt, Version versionDefaultValue) {
    String msgPatternVersionFormatHelp;
    String userResponse;
    Version version = null;

    if (scmPlugin != null) {
      msgPatternVersionFormatHelp = Util.resourceBundle.getString(Util.MSG_PATTERN_KEY_VERSION_FORMAT_HELP_VERSION_MUST_EXIST);
    } else {
      msgPatternVersionFormatHelp = Util.resourceBundle.getString(Util.MSG_PATTERN_KEY_VERSION_FORMAT_HELP);
    }

    if (prompt.endsWith("*")) {
      String stringVersionType;
      String defaultValue;

      if (versionType == null) {
        stringVersionType = "S|D";
      } else if (versionType == VersionType.STATIC){
        stringVersionType = "S";
      } else if (versionType == VersionType.DYNAMIC){
        stringVersionType = "D";
      } else {
        throw new RuntimeException("Must not get here.");
      }

      if (versionDefaultValue != null) {
        defaultValue = " [" + versionDefaultValue.toString() + "]";
      } else {
        defaultValue = "";
      }

      prompt = prompt.substring(0, prompt.length() - 1) + " (" + MessageFormat.format(msgPatternVersionFormatHelp, stringVersionType) + ")" + defaultValue + "? ";

    }

    do {
      if (versionDefaultValue != null) {
        userResponse = userInteractionCallbackPlugin.getInfoWithDefault(prompt, versionDefaultValue.toString());
      } else {
        userResponse = userInteractionCallbackPlugin.getInfo(prompt);
      }

      try {
        version = Version.parse(userResponse);
      } catch (ParseException pe) {
        userInteractionCallbackPlugin.provideInfo(pe.getMessage());
        userInteractionCallbackPlugin.provideInfo(Util.resourceBundle.getString(Util.MSG_PATTERN_KEY_TRY_AGAIN));
        continue;
      }

      if ((versionType != null) && (version.getVersionType() != versionType)) {
        userInteractionCallbackPlugin.provideInfo(MessageFormat.format(Util.resourceBundle.getString(Util.MSG_PATTERN_KEY_INCORRECT_VERSION_TYPE), version, versionType));
        userInteractionCallbackPlugin.provideInfo(Util.resourceBundle.getString(Util.MSG_PATTERN_KEY_TRY_AGAIN));
        continue;
      }

      if (scmPlugin != null) {
        if (!scmPlugin.isVersionExists(version)) {
          userInteractionCallbackPlugin.provideInfo(MessageFormat.format(Util.resourceBundle.getString(Util.MSG_PATTERN_KEY_VERSION_DOES_NOT_EXIST), version));
          userInteractionCallbackPlugin.provideInfo(Util.resourceBundle.getString(Util.MSG_PATTERN_KEY_TRY_AGAIN));
          continue;
        }
      }

      break;
    } while (true);

    return version;
  }

  /**
   * Gets an instance of NodePluginFactory for a plugin implementation class.
   *
   * <p>It is assumed the NodePlugin implementation class implements
   * NodePluginFactory. The caller must have verified if the NodePlugin
   * implementation class uses the constructor pattern and if so, not call this
   * method.
   *
   * <p>This method factors out the algorithm that verifies if the class has a
   * getInstance static method, if so calls it to obtain the NodePluginFactory and
   * otherwise simply instantiates the class.
   * <p>
   * This s used by {@link #getDefaultClassNodePlugin} and
   * {@link #getDefaultPluginId}, as well as {@link DefaultNode#getNodePlugin}.
   *
   * @param stringClassNodePlugin NodePlugin implementation class. Must implement
   *   NodePluginFactory.
   * @return NodePluginFactory.
   */
  public static NodePluginFactory getNodePluginFactory(String stringClassNodePlugin) {
    Class<?> classNodePlugin;
    Method methodGetInstance;

    // Any Exception thrown that is not a RuntimeException is wrapped in a
    // RuntimeException. This is done globally to make the method simpler.
    try {
      classNodePlugin = Class.forName(stringClassNodePlugin);
      methodGetInstance = classNodePlugin.getMethod("getInstance", (Class<?>[])null);

      if (methodGetInstance != null) {
        return NodePluginFactory.class.cast(methodGetInstance.invoke(null));
      } else {
        Class<? extends NodePluginFactory> classNodePluginFactory;

        classNodePluginFactory = classNodePlugin.asSubclass(NodePluginFactory.class);

        return classNodePluginFactory.newInstance();
      }
    } catch (RuntimeException rte) {
      throw rte;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Returns the default NodePlugin interface for a plugin implementation class.
   * <p>
   * This method implements the strategy described in the description of
   * {@link PluginDefConfig}.
   *
   * @param pluginClass Name of the plugin implementation class.
   * @return Default Class of the NodePlugin.
   */
  public static Class<? extends NodePlugin> getDefaultClassNodePlugin(String pluginClass) {
    Class<?> classPlugin;

    // Any Exception thrown that is not a RuntimeException is wrapped in a
    // RuntimeException. This is done globally to make the method simpler.
    try {
      classPlugin = Class.forName(pluginClass);

      if (NodePluginFactory.class.isAssignableFrom(classPlugin)) {
        NodePluginFactory pluginFactory;

        pluginFactory = Util.getNodePluginFactory(pluginClass);

        return pluginFactory.getDefaultClassNodePlugin();
      } else if (NodePlugin.class.isAssignableFrom(classPlugin)) {
        Method methodGetDefaultClassNodePlugin;

        try {
          methodGetDefaultClassNodePlugin = classPlugin.getMethod("getDefaultClassNodePlugin", (Class<?>[])null);
        } catch (NoSuchMethodException nsme) {
          methodGetDefaultClassNodePlugin = null;
        }

        if (methodGetDefaultClassNodePlugin != null) {
          return ((Class<?>)Class.class.cast(methodGetDefaultClassNodePlugin.invoke(null))).asSubclass(NodePlugin.class);
        } else {
          Class<?>[] arrayClassInterface;

          arrayClassInterface = classPlugin.getInterfaces();

          for (Class<?> classInterface: arrayClassInterface) {
            if (NodePlugin.class.isAssignableFrom(classInterface)) {
              return classInterface.asSubclass(NodePlugin.class);
            }
          }

          throw new RuntimeException("The plugin class " + pluginClass + " does not implement a sub-interface of NodePlugin.");
        }
      } else {
        throw new RuntimeException("The plugin class " + pluginClass + " does not implement NodePluginFactory and cannot be instantiated as a NodePlugin.");
      }
    } catch (RuntimeException rte) {
      throw rte;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Returns the default plugin ID for NodePlugin interface supported by a
   * plugin implementation class.
   * <p>
   * This method implements the strategy described in the description of
   * {@link PluginDefConfig}.
   *
   * @param classNodePlugin Class of the NodePlugin.
   * @param pluginClass Name of the plugin implementation class.
   * @return Default plugin ID.
   */
  public static String getDefaultPluginId(Class<? extends NodePlugin> classNodePlugin, String pluginClass) {
    Class<?> classPlugin;

    // Any Exception thrown that is not a RuntimeException is wrapped in a
    // RuntimeException. This is done globally to make the method simpler.
    try {
      classPlugin = Class.forName(pluginClass);

      if (NodePluginFactory.class.isAssignableFrom(classPlugin)) {
        NodePluginFactory pluginFactory;

        pluginFactory = Util.getNodePluginFactory(pluginClass);

        return pluginFactory.getDefaultPluginId(classNodePlugin);
      } else if (NodePlugin.class.isAssignableFrom(classPlugin)) {
        Method methodGetDefaultClassNodePlugin;

        try {
          methodGetDefaultClassNodePlugin = classPlugin.getMethod("getDefaultPluginId", Class.class);
        } catch (NoSuchMethodException nsme) {
          methodGetDefaultClassNodePlugin = null;
        }

        if (methodGetDefaultClassNodePlugin != null) {
          return (String)methodGetDefaultClassNodePlugin.invoke(null, classNodePlugin);
        } else {
          return null;
        }
      } else {
        throw new RuntimeException("The plugin class " + pluginClass + " does not implement NodePluginFactory and cannot be instantiated as a NodePlugin.");
      }
    } catch (RuntimeException rte) {
      throw rte;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * @return Indicates if we are running on Windows. If not, we can conclude we are
   *   running on a *nix.
   */
  public static boolean isWindows() {
    if (Util.indWindows == null) {
      Util.indWindows = Boolean.valueOf(System.getProperty("os.name").startsWith("Windows"));
    }

    return Util.indWindows.booleanValue();
  }

  /**
   * Helper method that handles generically the typical question
   * "Do you want to continue?".
   * <p>
   * The context of the action having just been performed and for which we ask the
   * user if he wants to continue is taken into consideration. If the
   * context-specific {@code IND_NO_CONFIRM.<context>} runtime property is defined
   * and true, true is returned. If it is defined and false, interaction with the
   * user occurs. If it is not defined, the {@code IND_NO_CONFIRM} runtime property
   * is used. If it is true, true is returned. Otherwise interaction with the user
   * occurs.
   * <p>
   * This allows either specifying to continue only for specific contexts, or
   * globally specifying to continue, except for specific contexts.
   * <p>
   * If the user responds to always continue, it is understood to be for the
   * specified context.
   * <p>
   * If the user responds to not continue, false is returned and the IND_ABORT
   * runtime property is set to true. See {@link Util#isAbort}.
   *
   * @param context Context.
   * @return true if continue, false if abort.
   */
  public static boolean handleDoYouWantToContinue(String context) {
    ExecContext execContext;
    RuntimePropertiesPlugin runtimePropertiesPlugin;
    UserInteractionCallbackPlugin userInteractionCallbackPlugin;
    String runtimeProperty;

    if (context == null) {
      throw new RuntimeException("context must not be null.");
    }

    execContext = ExecContextHolder.get();
    runtimePropertiesPlugin = execContext.getExecContextPlugin(RuntimePropertiesPlugin.class);
    userInteractionCallbackPlugin = execContext.getExecContextPlugin(UserInteractionCallbackPlugin.class);

    runtimeProperty = runtimePropertiesPlugin.getProperty(null, Util.RUNTIME_PROPERTY_IND_NO_CONFIRM + '.' + context);

    if ((runtimeProperty != null) && Boolean.valueOf(runtimeProperty)) {
      return true;
    } else if (Boolean.valueOf(runtimePropertiesPlugin.getProperty(null, Util.RUNTIME_PROPERTY_IND_NO_CONFIRM))) {
      return true;
    }

    Util.logger.info("Interacting with the user for handling \"Do you want to continue?\" for context " + context + ". You can set the runtime property " + Util.RUNTIME_PROPERTY_IND_NO_CONFIRM + '.' + context + " to true to continue without user interaction.");

    switch (Util.getInfoYesAlwaysNoUserResponse(userInteractionCallbackPlugin, Util.resourceBundle.getString(Util.MSG_PATTERN_DO_YOU_WANT_TO_CONTINUE), YesAlwaysNoUserResponse.YES)) {
    case NO:
      Util.setAbort();
      return false;
    case YES_ALWAYS:
      runtimePropertiesPlugin.setProperty(null, Util.RUNTIME_PROPERTY_IND_NO_CONFIRM + '.' + context, "true");
    case YES:
      return true;
    default:
      throw new RuntimeException("Must not get here.");
    }
  }

  /**
   * Helper method that handles generically the typical question
   * "Do you want to continue?".
   * <p>
   * Its behavior is similar to that of {@link #handleDoYouWantToContinue} except
   * that it handles the "no" response to mean "no for this iteration" (and simply
   * return false), and provides a separate "abort" response to mean "no and abort"
   * (and return false and set the IND_ABORT runtime property to true).
   *
   * @param context Context.
   * @return true if continue, false if not.
   */
  public static boolean handleDoYouWantToContinueWithIndividualNo(String context) {
    ExecContext execContext;
    RuntimePropertiesPlugin runtimePropertiesPlugin;
    UserInteractionCallbackPlugin userInteractionCallbackPlugin;

    if (context == null) {
      throw new RuntimeException("context must not be null.");
    }

    execContext = ExecContextHolder.get();
    runtimePropertiesPlugin = execContext.getExecContextPlugin(RuntimePropertiesPlugin.class);
    userInteractionCallbackPlugin = execContext.getExecContextPlugin(UserInteractionCallbackPlugin.class);

    if (Boolean.valueOf(runtimePropertiesPlugin.getProperty(null, Util.RUNTIME_PROPERTY_IND_NO_CONFIRM))) {
      return true;
    } else if (Boolean.valueOf(runtimePropertiesPlugin.getProperty(null, Util.RUNTIME_PROPERTY_IND_NO_CONFIRM + '.' + context))) {
      return true;
    }

    Util.logger.info("Interacting with the user for handling \"Do you want to continue?\" for context " + context + ". You can set the runtime property " + Util.RUNTIME_PROPERTY_IND_NO_CONFIRM + '.' + context + " to true to continue without user interaction.");

    switch (Util.getInfoYesAlwaysNoAbortUserResponse(userInteractionCallbackPlugin, Util.resourceBundle.getString(Util.MSG_PATTERN_DO_YOU_WANT_TO_CONTINUE), YesAlwaysNoUserResponse.YES)) {
    case NO_ABORT:
      Util.setAbort();
    case NO:
      return false;
    case YES_ALWAYS:
      runtimePropertiesPlugin.setProperty(null,  Util.RUNTIME_PROPERTY_IND_NO_CONFIRM + '.' + context, "true");
    case YES:
      return true;
    default:
      throw new RuntimeException("Must not get here.");
    }
  }

  /**
   * @return The value of the IND_ABORT runtime property which can be set by
   *   {@link Util#handleDoYouWantToContinue} or {@link Util#setAbort}.
   */
  public static boolean isAbort() {
    RuntimePropertiesPlugin runtimePropertiesPlugin;

    runtimePropertiesPlugin = ExecContextHolder.get().getExecContextPlugin(RuntimePropertiesPlugin.class);

    return (Boolean.valueOf(runtimePropertiesPlugin.getProperty(null, Util.RUNTIME_PROPERTY_ABORT)));
  }

  /**
   * Sets the IND_ABORT runtime property explicitly.
   */
  public static void setAbort() {
    RuntimePropertiesPlugin runtimePropertiesPlugin;

    runtimePropertiesPlugin = ExecContextHolder.get().getExecContextPlugin(RuntimePropertiesPlugin.class);

    runtimePropertiesPlugin.setProperty(null, Util.RUNTIME_PROPERTY_ABORT, "true");
  }

  /**
   * Verifies if a String contains only ASCII digits.
   *
   * @param string String.
   * @return true if the token contains only ASCII digits.
   */
  public static boolean isDigits(String string) {
    for (char character : string.toCharArray()) {
      if ((character < '0') || (character > '9')) {
        return false;
      }
    }

    return true;
  }

  /**
   * Returns a string of length spaces.
   * <p>
   * Internally manages a single StringBuilder from which substring of the
   * requested lengths can be obtained.
   * <p>
   * A ReadWriteLock is used to protect the non-thread-safe StringBuilder. The code
   * used is inspired from the sample shown in
   * <a href="https://docs.oracle.com/javase/7/docs/api/java/util/concurrent/locks/ReentrantReadWriteLock.html">ReentrantReadWriteLock</a>.
   *
   * @param length Number of spaces.
   * @return String of length spaces.
   */
  public static String spaces(int length) {
    int currentLength;
    String spaces;

    Util.readWriteLockStringBuilderSpaces.readLock().lock();

    currentLength = Util.stringBuilderSpaces.length();

    if (length > currentLength) {
      Util.readWriteLockStringBuilderSpaces.readLock().unlock();
      Util.readWriteLockStringBuilderSpaces.writeLock().lock();

      // Current length may have changed between test and lock acquisition.
      currentLength = Util.stringBuilderSpaces.length();

      if (length > currentLength) {
        Util.stringBuilderSpaces.setLength(length);

        for (int i = currentLength; i < length; i++) {
          Util.stringBuilderSpaces.setCharAt(i, ' ');
        }
      }

      Util.readWriteLockStringBuilderSpaces.readLock().lock();
      Util.readWriteLockStringBuilderSpaces.writeLock().unlock();
    }

    spaces = Util.stringBuilderSpaces.substring(0, length);

    Util.readWriteLockStringBuilderSpaces.readLock().unlock();

    return spaces;
  }

  /**
   * @return Indicates if the system is Posix compliant, for file operations at
   *   least.
   */
  public static boolean isPosix() {
    if (Util.indPosix == null) {
      Util.indPosix = Boolean.valueOf(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
    }

    return Util.indPosix.booleanValue();
  }

  /**
   * Records the current tool exit status in {@link ExecContext} transient data.
   *
   * <p>The default tool exit status is {@link ToolExitStatus#SUCCESS} if this
   * method is never called.
   *
   * <p>ToolExitStatus escalation is performed, meaning that the ToolExitStatus is
   * set only if more severe than the current one.
   *
   * @param toolExitStatus ToolExitStatus.
   * @return Indicates if the new ToolExitStatus is more severe than the current one
   *   which has been updated.
   */
  public static boolean setExitStatus(ToolExitStatus toolExitStatus) {
    ExecContext execContext;
    ToolExitStatus toolExitStatusCurrent;

    execContext = ExecContextHolder.get();

    toolExitStatusCurrent = (ToolExitStatus)execContext.getTransientData(Util.TRANSIENT_DATA_TOOL_EXIT_STATUS);

    if (toolExitStatusCurrent == null) {
      toolExitStatusCurrent = ToolExitStatus.SUCCESS;
    }

    if (toolExitStatusCurrent.isMoreSevere(toolExitStatus)) {
      execContext.setTransientData(Util.TRANSIENT_DATA_TOOL_EXIT_STATUS, toolExitStatus);
      return true;
    } else {
      return false;
    }
  }

  /**
   * @return Current {@link ToolExitStatus} from {@link ExecContext} transient data.
   */
  public static ToolExitStatus getToolExitStatus() {
    ExecContext execContext;
    ToolExitStatus toolExitStatus;

    execContext = ExecContextHolder.get();

    // Depending on the state of the tool which calls this method, it can happen that
    // the ExecContext is not initialized.
    if (execContext == null) {
      return ToolExitStatus.SUCCESS;
    }

    toolExitStatus = (ToolExitStatus)execContext.getTransientData(Util.TRANSIENT_DATA_TOOL_EXIT_STATUS);

    if (toolExitStatus == null) {
      return ToolExitStatus.SUCCESS;
    } else {
      return toolExitStatus;
    }
  }

  /**
   * Returns the current tool exit status code and if not 0 emits the reason to the
   * log.
   *
   * @return See description.
   */
  public static int getExitStatusAndShowReason() {
    ToolExitStatus toolExitStatus;

    toolExitStatus = Util.getToolExitStatus();

    if (toolExitStatus != ToolExitStatus.SUCCESS) {
      UserInteractionCallbackPlugin userInteractionCallbackPlugin;

      userInteractionCallbackPlugin = ExecContextHolder.get().getExecContextPlugin(UserInteractionCallbackPlugin.class);

      userInteractionCallbackPlugin.provideInfo(MessageFormat.format(Util.resourceBundle.getString(Util.MSG_PATTERN_KEY_SETTING_TOOL_EXIT_STATUS), toolExitStatus));
    }

    return toolExitStatus.getExitStatus();
  }

  /**
   * Following an exceptional condition that occurred, combines the following
   * functionalities:
   * <ul>
   * <li>Update the current {@link ToolExitStatus} in {@link ExecContext} transient
   * data;
   * <li>Returns to the caller whether processing should continue or not, and the
   * ToolExitStatus contributed for this exceptional condition.
   * </ul>
   * The ToolExitStatus is by default {@link ToolExitStatus#WARNING}, unless the
   * runtime property named after the specified exceptional condition with the
   * suffix ".TOOL_EXIT_STATUS" indicates otherwise.
   *
   * <p>By default the return value indicates to continue processing if and only if
   * the ToolExitStatus is not ERROR, unless the runtime property named after the
   * exceptional condition with the suffix ".IND_CONTINUE" indicates otherwise.
   *
   * @param node Current Node in the context of which the exceptional condition is
   *   met. Used to access the runtime properties. Can be null.
   * @param exceptionalCond Exceptional condition (e.g.:
   *   EXCEPTIONAL_COND_MODULE_NOT_FOUND_FOR_ARTIFACT)
   * @return ToolExitStatusAndContinue.
   */
  public static ToolExitStatusAndContinue handleToolExitStatusAndContinueForExceptionalCond(Node node, String exceptionalCond) {
    ExecContext execContext;
    RuntimePropertiesPlugin runtimePropertiesPlugin;
    ToolExitStatusAndContinue toolExitStatusAndContinue;
    StringBuilder stringBuilder;
    String nodeName;
    String runtimeProperty;

    execContext = ExecContextHolder.get();
    runtimePropertiesPlugin = execContext.getExecContextPlugin(RuntimePropertiesPlugin.class);

    stringBuilder = new StringBuilder();

    if (node == null) {
      nodeName = "null";
    } else {
      nodeName = node.toString();
    }

    toolExitStatusAndContinue = new ToolExitStatusAndContinue();

    stringBuilder.append("Exceptional condition ").append(exceptionalCond).append(" met. ");

    runtimeProperty = runtimePropertiesPlugin.getProperty(node, Util.PREFIX_EXCEPTIONAL_COND + exceptionalCond + Util.SUFFIX_EXCEPTIONAL_COND_EXIT_STATUS);

    if (runtimeProperty == null) {
      stringBuilder.append("Default ToolExitStatus.WARNING used. ");

      toolExitStatusAndContinue.toolExitStatus = ToolExitStatus.WARNING;
    } else {
      toolExitStatusAndContinue.toolExitStatus = ToolExitStatus.valueOf(runtimeProperty);

      stringBuilder.append("ToolExitStatus.").append(toolExitStatusAndContinue.toolExitStatus).append(" specified for Node ").append(nodeName).append(" used. ");
    }

    if (Util.setExitStatus(toolExitStatusAndContinue.toolExitStatus)) {
      stringBuilder.append("Current ToolExitStatus updated since less severe. ");
    } else {
      stringBuilder.append("Current ToolExitStatus not updated since equal or more severe. ");
    }

    runtimeProperty = runtimePropertiesPlugin.getProperty(node, Util.PREFIX_EXCEPTIONAL_COND + exceptionalCond + Util.SUFFIX_EXCEPTIONAL_COND_CONTINUE);

    if (runtimeProperty == null) {
      toolExitStatusAndContinue.indContinue = (toolExitStatusAndContinue.toolExitStatus != ToolExitStatus.ERROR);

      if (toolExitStatusAndContinue.indContinue) {
        stringBuilder.append("Continuing the process by default since not ToolExitStatus.ERROR. ");
      } else {
        stringBuilder.append("Not continuing the process by default since TtoolExitStatus.ERROR. ");
      }
    } else {
      toolExitStatusAndContinue.indContinue = Util.isNotNullAndTrue(runtimeProperty);

      if (toolExitStatusAndContinue.indContinue) {
        stringBuilder.append("Continuing as specified for Node ").append(nodeName).append('.');
      } else {
        stringBuilder.append("Aborting as specified for Node ").append(nodeName).append('.');
      }
    }

    Util.logger.info(stringBuilder.toString());


    return toolExitStatusAndContinue;
  }

  public static String getStackTrace(Exception e) {
    ByteArrayOutputStream byteArrayOutputStream;
    PrintStream printStream;

    byteArrayOutputStream = new ByteArrayOutputStream();
    printStream = new PrintStream(byteArrayOutputStream);

    e.printStackTrace(printStream);

    printStream.close();

    return byteArrayOutputStream.toString();
  }

  public static void main(String[] args) {
    System.out.println(Util.convertPascalCaseToLowercaseWithDashes("PascalCase"));
    System.out.println(Util.convertPascalCaseToLowercaseWithDashes("123PascalCase"));
    System.out.println(Util.convertPascalCaseToLowercaseWithDashes("Pascal123Case"));
    System.out.println(Util.convertPascalCaseToLowercaseWithDashes("PascalCase123"));
    System.out.println(Util.convertPascalCaseToLowercaseWithDashes("PascalCASE"));
    System.out.println(Util.convertPascalCaseToLowercaseWithDashes("123PascalCASE"));
    System.out.println(Util.convertPascalCaseToLowercaseWithDashes("Pascal123CASE"));
    System.out.println(Util.convertPascalCaseToLowercaseWithDashes("PascalCASE123"));
    System.out.println(Util.convertPascalCaseToLowercaseWithDashes("PASCALCase"));
    System.out.println(Util.convertPascalCaseToLowercaseWithDashes("123PASCALCase"));
    System.out.println(Util.convertPascalCaseToLowercaseWithDashes("PASCAL123Case"));
    System.out.println(Util.convertPascalCaseToLowercaseWithDashes("PASCALCase123"));
  }
}
