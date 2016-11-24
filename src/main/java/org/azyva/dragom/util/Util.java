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

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.text.ParseException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.ResourceBundle;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.azyva.dragom.execcontext.ExecContext;
import org.azyva.dragom.execcontext.plugin.RuntimePropertiesPlugin;
import org.azyva.dragom.execcontext.plugin.UserInteractionCallbackPlugin;
import org.azyva.dragom.execcontext.support.ExecContextHolder;
import org.azyva.dragom.job.MergeReferenceGraph;
import org.azyva.dragom.model.ModuleVersion;
import org.azyva.dragom.model.NodePath;
import org.azyva.dragom.model.Version;
import org.azyva.dragom.model.VersionType;
import org.azyva.dragom.model.config.PluginDefConfig;
import org.azyva.dragom.model.impl.simple.SimpleNode;
import org.azyva.dragom.model.plugin.NodePlugin;
import org.azyva.dragom.model.plugin.PluginFactory;
import org.azyva.dragom.model.plugin.ScmPlugin;
import org.azyva.dragom.reference.ReferencePath;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Static utility methods.
 *
 * For now this class is a mixed bag of utility methods. With time and maturity
 * some groups of utility methods may be migrated to separate classes.
 *
 * @author David Raymond
 */
public final class Util {
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
	public static final String RUNTIME_PROPERTY_IND_ABORT = "IND_ABORT";

	/**
	 * Context for {@link Util#handleDoYouWantToContinue} that represents updating a
	 * reference.
	 */
	public static final String DO_YOU_WANT_TO_CONTINUE_CONTEXT_UPDATE_REFERENCE = "UPDATE_REFERENCE";

	/**
	 * Context for {@link Util#handleDoYouWantToContinue} that represents creating a
	 * new static Version.
	 */
	public static final String DO_YOU_WANT_TO_CONTINUE_CONTEXT_CREATE_STATIC_VERSION = "CREATE_STATIC_VERSION";

	/**
	 * Context for {@link Util#handleDoYouWantToContinue} that represents creating a
	 * new dynamic Version.
	 */
	public static final String DO_YOU_WANT_TO_CONTINUE_CONTEXT_CREATE_DYNAMIC_VERSION = "CREATE_DYNAMIC_VERSION";

	/**
	 * Context for {@link Util#handleDoYouWantToContinue} that represents merging a
	 * Version into another.
	 */
	public static final String DO_YOU_WANT_TO_CONTINUE_CONTEXT_MERGE = "MERGE";

	/**
	 * Context for {@link Util#handleDoYouWantToContinue} that represents reference
	 * change after switching to a dynamic Version.
	 */
	public static final String DO_YOU_WANT_TO_CONTINUE_CONTEXT_REFERENCE_CHANGE_AFTER_SWITCHING = "REFERENCE_CHANGE_AFTER_SWITCHING";

	/**
	 * Context for {@link Util#handleDoYouWantToContinue} that represents committing local
	 * unsynchronized changes.
	 */
	public static final String DO_YOU_WANT_TO_CONTINUE_CONTEXT_COMMIT = "COMMIT";

	/**
	 * Context for {@link Util#handleDoYouWantToContinue} that represents having
	 * unsynchronized remote changes while committing.
	 */
	public static final String DO_YOU_WANT_TO_CONTINUE_CONTEXT_UNSYNC_REMOTE_CHANGES_WHILE_COMMIT = "UNSYNC_REMOTE_CHANGES_WHILE_COMMIT";

	/**
	 * Context for {@link Util#handleDoYouWantToContinue} that represents deleting
	 * a workspace directory containing un synchronized changes.
	 */
	public static final String DO_YOU_WANT_TO_CONTINUE_CONTEXT_DELETE_WORKSPACE_DIRECTORY_WITH_UNSYNC_LOCAL_CHANGES = "DELETE_WORKSPACE_DIRECTORY_WITH_UNSYNC_LOCAL_CHANGES";

	/**
	 * Context for {@link Util#handleDoYouWantToContinue} that represents deleting
	 * a workspace directory (not containing any unsynchronized local changes).
	 */
	public static final String DO_YOU_WANT_TO_CONTINUE_CONTEXT_DELETE_WORKSPACE_DIRECTORY = "DELETE_WORKSPACE_DIRECTORY";

	/**
	 * Context for {@link Util#handleDoYouWantToContinue} that represents the fact
	 * that following the selection of a new dynamic Version during a merge
	 * ({@link MergeReferenceGraph}) diverging commits in the destination are not
	 * present in the new selected dynamic Version and may be lost.
	 */
	public static final String DO_YOU_WANT_TO_CONTINUE_CONTEXT_MAY_LOOSE_COMMITS = "MAY_LOOSE_COMMITS";

	/**
	 * Context for {@link Util#handleDoYouWantToContinue} that represents the fact
	 * that local changes exist in a workspace directory and switching to a new
	 * {@link Version} is not possible. Continuing here means to continue with the
	 * other {@link ModuleVersion}.
	 */
	public static final String DO_YOU_WANT_TO_CONTINUE_CONTEXT_SWITCH_WITH_UNSYNC_LOCAL_CHANGES = "SWITCH_WITH_UNSYNC_LOCAL_CHANGES";

	/**
	 * Context for {@link Util#handleDoYouWantToCOntinue} that represents the fact
	 * that during the switch to a hotfix dynamic {@link Version}, the
	 * {@link ReferencePath} contains non-static Versions. which may be OK if they
	 * were created in the context of the hotfix.
	 */
	public static final String DO_YOU_WANT_TO_CONTINUE_CONTEXT_NON_STATIC_VERSIONS_REFERENCE_PATH = "NON_STATIC_VERSIONS_REFERENCE_PATH";

	/**
	 * Context for {@link Util#handleDoYouWantToContinue} that represents the fact
	 * that during the switch to a hotfix dynamic {@link Version}, the
	 * {@link ReferencePath} contains non-static Versions. which may be OK if they
	 * were created in the context of the hotfix.
	 */
	public static final String DO_YOU_WANT_TO_CONTINUE_CONTEXT_USE_CURRENT_HOTFIX_VERSION = "USE_CURRENT_HOTFIX_VERSION";

	/**
	 * Context for {@link Util#handleDoYouWantToContinue} that represents the fact
	 * that during the creation of a static Version or the switch to a dynamic Version
	 * one or more references were changed but not committed and the user requested to
	 * abort. Should the commit be performed in that case?
	 */
	public static final String DO_YOU_WANT_TO_CONTINUE_CONTEXT_COMMIT_REFERENCE_CHANGE_AFTER_ABORT = "COMMIT_REFERENCE_CHANGE_AFTER_ABORT";

	/**
	 * Path to the static Dragom properties resource within the classpath.
	 */
	private static final String DRAGOM_PROPERTIES_RESOURCE = "/META-INF/dragom.properties";

	/**
	 * See description in ResourceBundle.
	 */
	public static final String MSG_PATTERN_KEY_ALWAYS_NEVER_ASK_RESPONSE_CHOICES = "ALWAYS_NEVER_ASK_RESPONSE_CHOICES";

	/**
	 * See description in ResourceBundle.
	 */
	public static final String MSG_PATTERN_KEY_ALWAYS_RESPONSE = "ALWAYS_RESPONSE";

	/**
	 * See description in ResourceBundle.
	 */
	public static final String MSG_PATTERN_KEY_NEVER_RESPONSE = "NEVER_RESPONSE";

	/**
	 * See description in ResourceBundle.
	 */
	public static final String MSG_PATTERN_KEY_ASK_RESPONSE = "ASK_RESPONSE";

	/**
	 * See description in ResourceBundle.
	 */
	public static final String MSG_PATTERN_KEY_ALWAYS_NEVER_YES_NO_ASK_RESPONSE_CHOICES = "ALWAYS_NEVER_YES_NO_ASK_RESPONSE_CHOICES";

	/**
	 * See description in ResourceBundle.
	 */
	public static final String MSG_PATTERN_KEY_YES_ASK_RESPONSE = "YES_ASK_RESPONSE";

	/**
	 * See description in ResourceBundle.
	 */
	public static final String MSG_PATTERN_KEY_NO_ASK_RESPONSE = "NO_ASK_RESPONSE";

	/**
	 * See description in ResourceBundle.
	 */
	public static final String MSG_PATTERN_KEY_YES_ALWAYS_NO_RESPONSE_CHOICES = "YES_ALWAYS_NO_RESPONSE_CHOICES";

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
	public static final String MSG_PATTERN_KEY_NO_RESPONSE = "NO_RESPONSE";

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
	 * ResourceBundle specific to this class.
	 * <p>
	 * Being a utility class, this ResourceBundle also contains global locale-specific
	 * resources which can be used by other classes.
	 */
	private static final ResourceBundle resourceBundle = ResourceBundle.getBundle(Util.class.getName() + "ResourceBundle");

	/**
	 * Indicates that the Dragom properties have been loaded.
	 */
	private static boolean indDragomPropertiesLoaded;

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
	 * We talk about the groupId segment since a complete groupId will generally be
	 * some prefix plus a . to which the value returned is appended.
	 *
	 * Only the node path is used. The module node path can be partial or not.
	 *
	 * @param nodePath
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
	 * Converts a PascalCase (or camelCase) string to lowercase with dashes.
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

					try {
						propertiesDragom.load(inputStream);
					} catch (IOException ioe) {
						throw new RuntimeException(ioe);
					}

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
	 * @param message
	 * @param mapAttr Map that will be filled in and returned. If null, a new Map is
	 * created, in which case it should not be modified by the caller (it may be
	 * immutable, especially if empty).
	 * @return Map of attributes.
	 */
	public static Map<String, String> getJsonAttr(String message, Map<String, String> mapAttr) {
		int indexClosingBrace;
		JSONObject jsonObjectAttributes;

		if ((message.length() == 0) || (message.charAt(0) != '{')) {
			return Collections.<String, String>emptyMap();
		}

		indexClosingBrace = message.indexOf('}');
		jsonObjectAttributes = new JSONObject(message.substring(0, indexClosingBrace + 1));

		if (mapAttr == null) {
			mapAttr = new HashMap<String, String>();
		}

		for (String name: JSONObject.getNames(jsonObjectAttributes)) {
			mapAttr.put(name, jsonObjectAttributes.getString(name));
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
	 * If info ends with "*" it is replaced with
	 * " (Y(es always), N(ever), A(sk again)) [<default>]? " where <default> is
	 * "Y", "N" or "A" depending on alwaysNeverAskUserResponseDefaultValue.
	 *
	 * As a convenience, "1" and "0" can also be entered by the user to mean "Yes
	 * always" and "Never" respectively.
	 *
	 * The user can also enter the string value of the enum constants ("ALWAYS",
	 * "NEVER" and "ASK").
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
		alwaysResponse = Util.resourceBundle.getString(Util.MSG_PATTERN_KEY_ALWAYS_RESPONSE);
		neverResponse = Util.resourceBundle.getString(Util.MSG_PATTERN_KEY_NEVER_RESPONSE);
		askResponse = Util.resourceBundle.getString(Util.MSG_PATTERN_KEY_ASK_RESPONSE);

		if (prompt.endsWith("*")) {
			String defaultValue = null;

			switch (alwaysNeverAskUserResponseDefaultValue) {
			case ALWAYS:
				defaultValue = alwaysResponse;
				break;

			case NEVER:
				defaultValue = neverResponse;
				break;

			case ASK:
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
				if (userResponse.equals(alwaysResponse)) {
					alwaysNeverAskUserResponse = AlwaysNeverAskUserResponse.ALWAYS;
				} else if (userResponse.equals(neverResponse)) {
						alwaysNeverAskUserResponse = AlwaysNeverAskUserResponse.NEVER;
				} else if (userResponse.equals(askResponse)) {
					alwaysNeverAskUserResponse = AlwaysNeverAskUserResponse.ASK;
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
			alwaysNeverAskUserResponse = Util.getInfoAlwaysNeverAskUserResponse(userInteractionCallbackPlugin, prompt, AlwaysNeverAskUserResponse.ASK);

			if (!alwaysNeverAskUserResponse.isAsk()) {
				runtimePropertiesPlugin.setProperty(null, runtimeProperty, alwaysNeverAskUserResponse.toString());
			}
		}

		return alwaysNeverAskUserResponse;
	}

	/**
	 * Facilitates letting the user input a AlwaysNeverYesNoAskUserResponse.
	 *
	 * If info ends with "*" it is replaced with
	 * " (Y(es always), N(ever), YA (Yes, Ask again), NA (No, Ask again)) [<default>]? " where <default> is
	 * "Y", "N", "YA" or "NA" depending on alwaysNeverYesNoAskUserResponseDefaultValue.
	 *
	 * As a convenience, "1" and "0" can also be entered by the user to mean "Yes
	 * always" and "Never" respectively.
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
		alwaysResponse = Util.resourceBundle.getString(Util.MSG_PATTERN_KEY_ALWAYS_RESPONSE);
		neverResponse = Util.resourceBundle.getString(Util.MSG_PATTERN_KEY_NEVER_RESPONSE);
		yesAskResponse = Util.resourceBundle.getString(Util.MSG_PATTERN_KEY_YES_ASK_RESPONSE);
		noAskResponse = Util.resourceBundle.getString(Util.MSG_PATTERN_KEY_NO_ASK_RESPONSE);

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
	 * If info ends with "*" it is replaced with
	 * " (Y(es), A(ways), N(o)) [<default>]? " where <default> is
	 * "Y", "A" or "N" depending on yesAlwaysNoUserResponseDefaultValue.
	 *
	 * As a convenience, "1" and "0" can also be entered by the user to mean "Yes"
	 * and "No" respectively.
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
				if (userResponse.equals(yesResponse) || userResponse.equals("1")) {
					yesAlwaysNoUserResponse = YesAlwaysNoUserResponse.YES;
				} else if (userResponse.equals(yesAlwaysResponse)) {
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
	 * If info ends with "*" it is replaced with
	 * " (Y(es), A(ways), N(o), B(aBort)) [<default>]? " where <default> is
	 * "Y", "A", "N" or "B" depending on yesAlwaysNoUserResponseDefaultValue.
	 *
	 * As a convenience, "1" and "0" can also be entered by the user to mean "Yes"
	 * and "No" respectively.
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
				if (userResponse.equals(yesResponse) || userResponse.equals("1")) {
					yesAlwaysNoUserResponse = YesAlwaysNoUserResponse.YES;
				} else if (userResponse.equals(yesAlwaysResponse)) {
					yesAlwaysNoUserResponse = YesAlwaysNoUserResponse.YES_ALWAYS;
				} else if (userResponse.equals(noResponse) || userResponse.equals("0")) {
					yesAlwaysNoUserResponse = YesAlwaysNoUserResponse.NO;
				} else if (userResponse.equals(noAbortResponse)) {
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
	 * does not support the "Always" response. It still returns a
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
	 * " (use the format <format>/<version>; version must exist) [<default>]? "
	 * where <format> is "S", "D" or "S/D" depending on whether versionType is
	 * VersionType.STATIC, VersionType.DYNAMIC or null, and where <default> is
	 * versionDefaultValue. If versionDefaultValue is null, the last part
	 * " [<default>]" is not included.
	 *
	 * If scmPlugin is null that text is similar, but excludes that part saying
	 * that the Version must exist.
	 *
	 * If versionType is not null, the type of Version will be validated to be as
	 * such.
	 *
	 * If scmPlugin is not null, the existence of the Version will be validated.
	 *
	 * @param versionType
	 * @param scmPlugin
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
	 * Gets an instance of PluginFactory for a plugin implementation class.
	 * <p>
	 * This method factors out the algorithm that verifies if the class has a
	 * getInstance static method, if so calls it to obtain the PluginFactory and
	 * otherwise simply instantiates the class.
	 * <p>
	 * This s used by the {@link #getDefaultClassNodePlugin} and
	 * {@link #getDefaultPluginId} methods, as well as the
	 * {@link SimpleNode#getNodePlugin method.
	 *
	 * @param pluginClass Plugin implementation class. Must implement PluginFactory.
	 * @return PluginFactory.
	 */
	public static PluginFactory getPluginFactory(String pluginClass) {
		Class<?> classPlugin;
		Method methodGetInstance;

		// Any Exception thrown that is not a RuntimeException is wrapped in a
		// RuntimeException. This is done globally to make the method simpler.
		try {
			classPlugin = Class.forName(pluginClass);
			methodGetInstance = classPlugin.getMethod("getInstance", (Class<?>[])null);

			if (methodGetInstance != null) {
				return PluginFactory.class.cast(methodGetInstance.invoke(null));
			} else {
				Class<? extends PluginFactory> classPluginFactory;

				classPluginFactory = classPlugin.asSubclass(PluginFactory.class);

				return classPluginFactory.newInstance();
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

			if (PluginFactory.class.isAssignableFrom(classPlugin)) {
				PluginFactory pluginFactory;

				pluginFactory = Util.getPluginFactory(pluginClass);

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
				throw new RuntimeException("The plugin class " + pluginClass + " does not implement PluginFactory and cannot be instantiated as a NodePlugin.");
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

			if (PluginFactory.class.isAssignableFrom(classPlugin)) {
				PluginFactory pluginFactory;

				pluginFactory = Util.getPluginFactory(pluginClass);

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
				throw new RuntimeException("The plugin class " + pluginClass + " does not implement PluginFactory and cannot be instantiated as a NodePlugin.");
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
	 * user if he wants to continue is taken into consideration. If the IND_NO_CONFIRM
	 * runtime property is true, true is returned regardless of the context. Otherwise
	 * the context-specific {@code IND_NO_CONFIRM.<context>} is used.
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

		switch (Util.getInfoYesAlwaysNoUserResponse(userInteractionCallbackPlugin, Util.resourceBundle.getString(Util.MSG_PATTERN_DO_YOU_WANT_TO_CONTINUE), YesAlwaysNoUserResponse.YES)) {
		case NO:
			Util.setAbort();
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
	 * Helper method that handles generically the typical question
	 * "Do you want to continue?".
	 * <p>
	 * Its behavior is similar to that of {@link #handleDoYouWantToContinue} except
	 * that it handle the "no" response to mean "no for this iteration" (and simply
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
	 * {@link Util.handleDoYouWantToContinue} or {@link Util#setAbort).
	 */
	public static boolean isAbort() {
		RuntimePropertiesPlugin runtimePropertiesPlugin;

		runtimePropertiesPlugin = ExecContextHolder.get().getExecContextPlugin(RuntimePropertiesPlugin.class);

		return (Boolean.valueOf(runtimePropertiesPlugin.getProperty(null, Util.RUNTIME_PROPERTY_IND_ABORT)));
	}

	/**
	 * Sets the IND_ABORT runtime property explicitly.
	 */
	public static void setAbort() {
		RuntimePropertiesPlugin runtimePropertiesPlugin;

		runtimePropertiesPlugin = ExecContextHolder.get().getExecContextPlugin(RuntimePropertiesPlugin.class);

		runtimePropertiesPlugin.setProperty(null, Util.RUNTIME_PROPERTY_IND_ABORT, "true");
	}

	/**
	 * Verifies if a token contains only ASCII digits.
	 *
	 * @param token Token.
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
	 * used is inpired from the sample shown in
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
}
