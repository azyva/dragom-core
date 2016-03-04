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

package org.azyva.dragom.util;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.ResourceBundle;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.azyva.dragom.execcontext.ExecContext;
import org.azyva.dragom.execcontext.ExecContextFactory;
import org.azyva.dragom.execcontext.ToolLifeCycleExecContext;
import org.azyva.dragom.execcontext.WorkspaceExecContextFactory;
import org.azyva.dragom.execcontext.plugin.RuntimePropertiesPlugin;
import org.azyva.dragom.execcontext.plugin.UserInteractionCallbackPlugin;
import org.azyva.dragom.execcontext.support.ExecContextFactoryHolder;
import org.azyva.dragom.execcontext.support.ExecContextHolder;
import org.azyva.dragom.job.RootManager;
import org.azyva.dragom.model.Model;
import org.azyva.dragom.model.ModuleVersion;
import org.azyva.dragom.model.NodePath;
import org.azyva.dragom.model.Version;
import org.azyva.dragom.model.VersionType;
import org.azyva.dragom.model.config.PluginDefConfig;
import org.azyva.dragom.model.impl.simple.SimpleNode;
import org.azyva.dragom.model.plugin.NodePlugin;
import org.azyva.dragom.model.plugin.PluginFactory;
import org.azyva.dragom.model.plugin.ScmPlugin;
import org.azyva.dragom.reference.ReferencePathMatcher;
import org.azyva.dragom.reference.ReferencePathMatcherAnd;
import org.azyva.dragom.reference.ReferencePathMatcherByElement;
import org.azyva.dragom.reference.ReferencePathMatcherOr;
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
	private static Logger logger = LoggerFactory.getLogger(Util.class);

	/**
	 * Name of the ResourceBundle of the class.
	 */
	public static final String RESOURCE_BUNDLE = "org/azyva/util/UtilResourceBundle";

	/**
	 * See description in ResourceBundle.
	 */
	public static final String MSG_PATTERN_KEY_ERROR_PARSING_COMMAND_LINE = "ERROR_PARSING_COMMAND_LINE";

	/**
	 * See description in ResourceBundle.
	 */
	public static final String MSG_PATTERN_KEY_INVALID_ARGUMENT_COUNT = "INVALID_ARGUMENT_COUNT";

	/**
	 * See description in ResourceBundle.
	 */
	public static final String MSG_PATTERN_KEY_INVALID_COMMAND = "INVALID_COMMAND";

	/**
	 * System property that specifies if a user properties file is supported.
	 */
	public static final String SYS_PROP_IND_USER_PROPERTIES = "org.azyva.dragom.IndUserProperties";

	/**
	 * System property that specifies the default user properties file.
	 */
	public static final String SYS_PROP_DEFAULT_USER_PROPERTIES_FILE = "org.azyva.dragom.DefaultUserProperties";

	/**
	 * System property that specifies the command line option used to specify the user
	 * properties file.
	 */
	public static final String SYS_PROP_USER_PROPERTIES_FILE_COMMAND_LINE_OPTION = "org.azyva.dragom.UserPropertiesCommandLineOption";

	/**
	 * Default command line option for specifying the user properties file.
	 */
	public static final String DEFAULT_USER_PROPERTIES_COMMAND_LINE_OPTION = "user-properties";

	/**
	 * System property that specifies if a tool properties file is supported.
	 */
	public static final String SYS_PROP_IND_TOOL_PROPERTIES = "org.azyva.dragom.IndToolProperties";

	/**
	 * System property that specifies the tool_properties file.
	 */
	public static final String SYS_PROP_TOOL_PROPERTIES_FILE = "org.azyva.dragom.ToolProperties";

	/**
	 * System property that specifies the command line option used to specify the
	 * workspace path.
	 */
	public static final String SYS_PROP_WORKSPACE_PATH_COMMAND_LINE_OPTION = "org.azyva.dragom.WorkspacePathCommandLineOption";

	/**
	 * Default command line option for specifying the workspace path.
	 */
	public static final String DEFAULT_WORKSPACE_PATH_COMMAND_LINE_OPTION = "workspace-path";

	/**
	 * System property that specifies the command line option used to specify the tool
	 * properties file.
	 */
	public static final String SYS_PROP_TOOL_PROPERTIES_FILE_COMMAND_LINE_OPTION = "org.azyva.dragom.ToolPropertiesCommandLineOption";

	/**
	 * Default command line option for specifying the tool properties file.
	 */
	public static final String DEFAULT_TOOL_PROPERTIES_COMMAND_LINE_OPTION = "tool-properties";

	/**
	 * System property that specifies the command line option used to specify whether
	 * confirmation is required for a particular context.
	 */
	public static final String SYS_PROP_NO_CONFIRM_COMMAND_LINE_OPTION = "org.azyva.dragom.NoConfirmCommandLineOption";

	/**
	 * Default command line option for specifying whether confirmation is required.
	 */
	public static final String DEFAULT_NO_CONFIRM_COMMAND_LINE_OPTION = "no-confirm";

	/**
	 * System property that specifies the command line option used to specify whether
	 * confirmation is required.
	 */
	public static final String SYS_PROP_NO_CONFIRM_CONTEXT_COMMAND_LINE_OPTION = "org.azyva.dragom.NoConfirmContextCommandLineOption";

	/**
	 * Default command line option for specifying whether confirmation is required
	 * for a particular context.
	 */
	public static final String DEFAULT_NO_CONFIRM_CONTEXT_COMMAND_LINE_OPTION = "no-confirm-context";

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
	 * Path to the static Dragom properties resource within the classpath.
	 */
	private static final String DRAGOM_PROPERTIES_RESOURCE = "/META-INF/dragom.properties";

	/**
	 * ResourceBundle specific to this class.
	 * <p>
	 * Being a utility class, this ResourceBundle also contains global locale-specific
	 * resources which can be used by other classes.
	 */
	private static ResourceBundle resourceBundle;

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
	 * Infers a groupId segment from a module classification path.
	 *
	 * We talk about the groupId segment since a complete groupId will generally be
	 * some prefix plus a . to which the value returned is appended.
	 *
	 * Only the node classification path is used. The module classification path can
	 * be partial or not.
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
	 * Sets system properties from the META-INF/dragom.properties classpath resource.
	 * <p>
	 * This method can be called from many places where it is expected that Dragom
	 * properties be available as system properties. This class ensures that loading
	 * the system properties is done only once.
	 * <p>
	 * System properties take precedence over Dragom properties.
	 */
	public static void setDragomSystemProperties() {
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

						if (System.getProperty(key) != null) {
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
	 * Utility method to add Option's corresponding to the user-properties,
	 * tool-properties and workspace options depending on whether user properties and
	 * tool properties are supported.
	 * <p>
	 * Used by tools when initializing Options.
	 *
	 * @param options Options.
	 */
	public static void addStandardOptions(Options options) {
		Option option;

		Util.setDragomSystemProperties();

		if (Util.isNotNullAndTrue(System.getProperty(Util.SYS_PROP_IND_USER_PROPERTIES))) {
			option = new Option(null, null);
			option.setLongOpt(System.getProperty(Util.SYS_PROP_USER_PROPERTIES_FILE_COMMAND_LINE_OPTION, Util.DEFAULT_USER_PROPERTIES_COMMAND_LINE_OPTION));
			option.setArgs(1);
			options.addOption(option);
		}

		if (Util.isNotNullAndTrue(System.getProperty(Util.SYS_PROP_IND_TOOL_PROPERTIES))) {
			option = new Option(null, null);
			option.setLongOpt(System.getProperty(Util.SYS_PROP_TOOL_PROPERTIES_FILE_COMMAND_LINE_OPTION, Util.DEFAULT_TOOL_PROPERTIES_COMMAND_LINE_OPTION));
			option.setArgs(1);
			options.addOption(option);
		}

		option = new Option(null, null);
		option.setLongOpt(System.getProperty(Util.SYS_PROP_WORKSPACE_PATH_COMMAND_LINE_OPTION, Util.DEFAULT_WORKSPACE_PATH_COMMAND_LINE_OPTION));
		option.setArgs(1);
		options.addOption(option);

		option = new Option(null, null);
		option.setLongOpt(System.getProperty(Util.SYS_PROP_NO_CONFIRM_COMMAND_LINE_OPTION, Util.DEFAULT_NO_CONFIRM_COMMAND_LINE_OPTION));
		options.addOption(option);

		option = new Option(null, null);
		option.setLongOpt(System.getProperty(Util.SYS_PROP_NO_CONFIRM_CONTEXT_COMMAND_LINE_OPTION, Util.DEFAULT_NO_CONFIRM_CONTEXT_COMMAND_LINE_OPTION));
		option.setArgs(1);
		options.addOption(option);
	}

	/**
	 * Sets up an {@link ExecContext} assuming an {@link ExecContextFactory} that
	 * supports the concept of workspace directory.
	 * <p>
	 * {@link ExecContextFactoryHolder} is used to get the ExecContextFactory so it
	 * is not guaranteed that it will support the concept of workspace directory and
	 * implement {@link WorkspaceExecContextFactory}. In such as case an exception is
	 * raised.
	 * <p>
	 * The strategy for setting up the ExecContext supports a user service
	 * implementation where a single JVM remains running in the background in the
	 * context of a given user account (not system-wide) and can execute Dragom tools
	 * for multiple different workspaces while avoiding tool startup overhead.
	 * <a href="http://www.martiansoftware.com/nailgun/" target="_blank">Nailgun</a>
	 * can be useful for that purpose.
	 * <p>
	 * A user service implementation is supported by differentiating between workspace
	 * initialization Properties passed to {@link ExecContextFactory#getExecContext}
	 * and tool initialization Properties passed to {@link
	 * and {@link ToolLifeCycleExecContext#startTool}.
	 * <p>
	 * Workspace initialization Properties are constructed in the following way:
	 * <p>
	 * <li>Dragom properties are merged into System properties using
	 *     {@link Util#setDragomSystemProperties}. System properties take precedence
	 *     over Dragom properties;</li>
	 * <li>Initialize an empty Properties with system Properties as defaults. This
	 *     Properties when fully initialized will become the workspace initialization
	 *     Properties;</li>
	 * <li>If the org.azyva.IndUserProperties system property is defined, load the
	 *     Properties defined in the properties file specified by the
	 *     user-properties command line option. If not defined, use the properties
	 *     file specified by the org.azyva.DefaultUserProperties system property. If
	 *     not defined or if the properties file does not exist, do not load the
	 *     Properties;</li>
	 * <li>The workspace directory is added to the Properties created above.</li>
	 * <p>
	 * The name of the user-properties command line option can be overridden with the
	 * org.azyva.dragom.UserPropertiesCommandLineOption system property.
	 * <p>
	 * Tool initialization Properties are constructed in the following way (if indSet):
	 * <p>
	 * <li>Initialize an empty Properties. This Properties when fully initialized will
	 *     become the tool initialization Properties;</li>
	 * <li>If the org.azyva.IndTaskProperties system property is defined, load the
	 *     Properties defined in the Properties file specified by the
	 *     tool-properties command line option. If not defined or if the properties
	 *     file does not exist, do not load the Properties.</li>
	 * <p>
	 * The name of the tool-properties command line option can be overridden with the
	 * org.azyva.dragom.ToolPropertiesCommandLineOption system property.
	 * <p>
	 * It is possible that ExecContextFactory.getExecContext uses a cached ExecContext
	 * corresponding to a workspace that has already been initialized previously. In
	 * that case workspace initialization Properties not be considered since
	 * ExecContextFactory.getExecContext considers them only when a new ExecContext is
	 * created. This is not expected to be a problem or source of confusion since this
	 * can happen only if a user service implementation is actually used and in such a
	 * case Dragom and system properties are expected to be considered only once when
	 * initializing the user service and users are expected to understand that user
	 * properties are considered only when initializing a new workspace.
	 * <p>
	 * If indSet, {@link ExecContextHolder#setAndStartTool} is called with the
	 * ExecContext to make it easier for tools to prepare for execution. But it is
	 * still the tool's responsibility to call
	 * {@link ExecContextHolder#endToolAndUnset} before exiting. This is somewhat
	 * asymmetric, but is sufficiently convenient to be warranted. The case where it
	 * can be useful to set indSet to false is for subsequently calling
	 * {@link ExecContextHolder#forceUnset}.
	 * <p>
	 * If indSet, the IND_NO_CONFIRM and {@code IND_NO_CONFIRM.<context>} runtime
	 * properties are read from the CommandLine.
	 *
	 * @param commandLine CommandLine where to obtain the user and tool properties
	 *   files as well as the workspace path.
	 * @param indSet Indicates to set the ExecContext in ExecContextHolder.
	 * @return ExecContext.
	 */
	public static ExecContext setupExecContext(CommandLine commandLine, boolean indSet) {
		ExecContextFactory execContextFactory;
		WorkspaceExecContextFactory workspaceExecContextFactory;
		Properties propertiesSystem;
		Properties propertiesWorkspace;
		String workspaceDir;
		String stringPropertiesFile;
		ExecContext execContext;

		execContextFactory = ExecContextFactoryHolder.getExecContextFactory();

		if (!(execContextFactory instanceof WorkspaceExecContextFactory)) {
			throw new RuntimeException("The ExecContextFactory does not support the workspace directory concept.");
		}

		workspaceExecContextFactory = (WorkspaceExecContextFactory)execContextFactory;

		Util.setDragomSystemProperties();

		propertiesSystem = System.getProperties();
		propertiesWorkspace = propertiesSystem;

		if (Util.isNotNullAndTrue(System.getProperty(Util.SYS_PROP_IND_USER_PROPERTIES))) {
			stringPropertiesFile = commandLine.getOptionValue(System.getProperty(Util.SYS_PROP_USER_PROPERTIES_FILE_COMMAND_LINE_OPTION, Util.DEFAULT_USER_PROPERTIES_COMMAND_LINE_OPTION));

			if (stringPropertiesFile == null) {
				stringPropertiesFile = System.getProperty(Util.SYS_PROP_DEFAULT_USER_PROPERTIES_FILE);
			}

			if (stringPropertiesFile != null) {
				propertiesWorkspace = Util.loadProperties(stringPropertiesFile, propertiesWorkspace);
			}
		}

		// We do not want to add to the system properties.
		if (propertiesWorkspace == propertiesSystem) {
			propertiesWorkspace = new Properties(propertiesSystem);
		}

		workspaceDir = commandLine.getOptionValue(System.getProperty(Util.SYS_PROP_WORKSPACE_PATH_COMMAND_LINE_OPTION, Util.DEFAULT_WORKSPACE_PATH_COMMAND_LINE_OPTION));

		if (workspaceDir != null) {
			propertiesWorkspace.setProperty(workspaceExecContextFactory.getWorkspaceDirInitProp(), workspaceDir);
		}

		execContext = execContextFactory.getExecContext(propertiesWorkspace);

		if (indSet) {
			Properties propertiesTool;

			propertiesTool = null;

			if (Util.isNotNullAndTrue(System.getProperty(Util.SYS_PROP_IND_TOOL_PROPERTIES))) {
				stringPropertiesFile = commandLine.getOptionValue(System.getProperty(Util.SYS_PROP_TOOL_PROPERTIES_FILE_COMMAND_LINE_OPTION, Util.DEFAULT_TOOL_PROPERTIES_COMMAND_LINE_OPTION));

				if (stringPropertiesFile != null) {
					propertiesTool = Util.loadProperties(stringPropertiesFile, null);
				}
			}

			if (propertiesTool == null) {
				propertiesTool = new Properties();
			}


			if (commandLine.hasOption(System.getProperty(Util.SYS_PROP_NO_CONFIRM_COMMAND_LINE_OPTION, Util.DEFAULT_NO_CONFIRM_COMMAND_LINE_OPTION))) {
				propertiesTool.setProperty("runtime-property." + Util.RUNTIME_PROPERTY_IND_NO_CONFIRM, "true");
			} else {
				String[] tabNoConfirmContext;

				tabNoConfirmContext = commandLine.getOptionValues(System.getProperty(Util.SYS_PROP_NO_CONFIRM_CONTEXT_COMMAND_LINE_OPTION, Util.DEFAULT_NO_CONFIRM_CONTEXT_COMMAND_LINE_OPTION));

				for (String context: tabNoConfirmContext) {
					propertiesTool.setProperty("runtime-property."+ Util.RUNTIME_PROPERTY_IND_NO_CONFIRM + '.' + context, "true");
				}
			}

			ExecContextHolder.setAndStartTool(execContext, propertiesTool);
		}

		return execContext;
	}

	/**
	 * Helper method that factors the code for loading a Properties file.
	 * <p>
	 * All occurrences of "~" in the path to the Properties files are replaced with
	 * the value of the user.home system property.
	 * <p>
	 * If the properties file is not found, propertiesDefault is returned (may be
	 * null).
	 * <p>
	 * If propertiesDefault is null, a new Properties is created without default
	 * Properties.
	 * <p>
	 * If propertiesDefault is not null, a new Properties is created with these
	 * default Properties.
	 *
	 * @param stringPropertiesFile Path to the Properties file in String form.
	 * @param propertiesDefault Default Properties.
	 * @return Properties. May be null.
	 */
	private static Properties loadProperties(String stringPropertiesFile, Properties propertiesDefault) {
		Properties properties;

		properties = propertiesDefault;

		Util.logger.debug("Loading properties from " + stringPropertiesFile);

		stringPropertiesFile = stringPropertiesFile.replaceAll("~", System.getProperty("user.home"));

		try (InputStream inputStreamProperties = new FileInputStream(stringPropertiesFile )) {
			if (propertiesDefault == null) {
				properties = new Properties();
			} else {
				properties = new Properties(propertiesDefault);
			}
			properties.load(inputStreamProperties);
		} catch (FileNotFoundException fnfe) {
			Util.logger.debug("Properties file " + stringPropertiesFile + " not found.");
		} catch (IOException ioe) {
			throw new RuntimeException(ioe);
		}

		return properties;
	}

	/**
	 * Helper method to retrieve commit attributes from a commit message.
	 *
	 * These attributes are stored in a JSONObject at the beginning of the commit
	 * message. The commit message must therefore start with "{" and continue to a
	 * matching closing "}". For now, embedded JSONObject or "}" are not supported.
	 *
	 * If the commit message does not start with "{", an empty Map is returned.
	 *
	 * @param commitMessage
	 * @return Map of attributes.
	 */
	public static Map<String, String> getCommitAttr(String commitMessage) {
		int indexClosingBrace;
		JSONObject jsonObjectAttributes;
		Map<String, String> mapCommitAttr;

		if ((commitMessage.length() == 0) || (commitMessage.charAt(0) != '{')) {
			return Collections.<String, String>emptyMap();
		}

		indexClosingBrace = commitMessage.indexOf('}');
		jsonObjectAttributes = new JSONObject(commitMessage.substring(0, indexClosingBrace + 1));

		mapCommitAttr = new HashMap<String, String>();

		for (String name: JSONObject.getNames(jsonObjectAttributes)) {
			mapCommitAttr.put(name, jsonObjectAttributes.getString(name));
		}

		return mapCommitAttr;
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
	 * Facilitates letting the user input a AlwaysNeverAskUserResponse.
	 *
	 * If idInfo ends with "*" it is replaced with
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
	 * @param idInfo See corresponding parameter in
	 *   UserInteractionCallbackPlugin.getInfo.
	 * @param alwaysNeverAskUserResponseDefaultValue Default user response. It is
	 *   translated to the defaultValue parameter in
	 *   UserInteractionCallbackPlugin.getInfo.
	 * @param arrayParam See corresponding parameter in
	 *   UserInteractionCallbackPlugin.getInfo.
	 * @return AlwaysNeverAskUserResponse.
	 */
	public static AlwaysNeverAskUserResponse getInfoAlwaysNeverAskUserResponse(UserInteractionCallbackPlugin userInteractionCallbackPlugin, String idInfo, AlwaysNeverAskUserResponse alwaysNeverAskUserResponseDefaultValue, Object... arrayParam) {
		String userResponse;
		AlwaysNeverAskUserResponse alwaysNeverAskUserResponse;

		if (idInfo.endsWith("*")) {
			String defaultValue = null;

			switch (alwaysNeverAskUserResponseDefaultValue) {
			case ALWAYS:
				defaultValue = "Y";
				break;

			case NEVER:
				defaultValue = "N";
				break;

			case ASK:
				defaultValue = "A";
				break;
			}

			idInfo = idInfo.substring(0, idInfo.length() - 1) + " (Y(es always), N(ever), A(sk again)) [" + defaultValue + "]? ";
		}

		do {
			userResponse = userInteractionCallbackPlugin.getInfoWithDefault(idInfo, alwaysNeverAskUserResponseDefaultValue.toString(), arrayParam);

			userResponse = userResponse.toUpperCase().trim();

			alwaysNeverAskUserResponse = null;

			try {
				alwaysNeverAskUserResponse = AlwaysNeverAskUserResponse.valueOf(userResponse);
			} catch (IllegalArgumentException iae) {
				if (userResponse.equals("A")) {
					alwaysNeverAskUserResponse = AlwaysNeverAskUserResponse.ASK;
				} else if (userResponse.equals("N")) {
						alwaysNeverAskUserResponse = AlwaysNeverAskUserResponse.NEVER;
				} else if (userResponse.equals("Y")) {
					alwaysNeverAskUserResponse = AlwaysNeverAskUserResponse.ALWAYS;
				}
			}

			if (alwaysNeverAskUserResponse == null) {
				userInteractionCallbackPlugin.provideInfo("Invalid response. Please try again.");
				continue;
			}
		} while (false);

		return alwaysNeverAskUserResponse;
	}

	/**
	 * This is an extension to the method getInfoAlwaysNeverAskUserResponse that
	 * handles storing the user response in a runtime property.
	 * <p>
	 * First it gets the value of the runtime property runtimeProperty. If it is
	 * AlwaysNeverAskUserResponse.ASK it delegates to the
	 * getInfoAlwaysNeverAskUserResponse method. If the user does not respond
	 * AlwaysNeverAskUserResponse.ASK, the response is written back to the runtime
	 * property.
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
	 * @param idInfo See corresponding parameter in
	 *   getInfoAlwaysNeverAskUserResponse.
	 * @param alwaysNeverAskUserResponseDefaultValue See corresponding parameter in
	 *   getInfoAlwaysNeverAskUserResponse.
	 * @param arrayParam See corresponding parameter in
	 *   getInfoAlwaysNeverAskUserResponse.
	 * @return AlwaysNeverAskUserResponse.
	 */
	public static AlwaysNeverAskUserResponse getInfoAlwaysNeverAskUserResponseAndHandleAsk(RuntimePropertiesPlugin runtimePropertiesPlugin, String runtimeProperty, UserInteractionCallbackPlugin userInteractionCallbackPlugin, String idInfo, AlwaysNeverAskUserResponse alwaysNeverAskUserResponseDefaultValue, Object... arrayParam) {
		AlwaysNeverAskUserResponse alwaysNeverAskUserResponse;

		alwaysNeverAskUserResponse = AlwaysNeverAskUserResponse.valueOfWithAskDefault(runtimePropertiesPlugin.getProperty(null, runtimeProperty));

		if (alwaysNeverAskUserResponse.isAsk()) {
			alwaysNeverAskUserResponse = Util.getInfoAlwaysNeverAskUserResponse(userInteractionCallbackPlugin, idInfo, alwaysNeverAskUserResponseDefaultValue, arrayParam);

			if (!alwaysNeverAskUserResponse.isAsk()) {
				runtimePropertiesPlugin.setProperty(null, runtimeProperty, alwaysNeverAskUserResponse.toString());
			}
		}

		return alwaysNeverAskUserResponse;
	}

	/**
	 * Facilitates letting the user input a YesAlwaysNoUserResponse.
	 *
	 * If idInfo ends with "*" it is replaced with
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
	 * @param idInfo See corresponding parameter in
	 *   UserInteractionCallbackPlugin.getInfo.
	 * @param yesAlwaysNoUserResponseDefaultValue Default user response. It is
	 *   translated to the defaultValue parameter in
	 *   UserInteractionCallbackPlugin.getInfo.
	 * @param arrayParam See corresponding parameter in
	 *   UserInteractionCallbackPlugin.getInfo.
	 * @return YesAlwaysNoUserResponse.
	 */
	public static YesAlwaysNoUserResponse getInfoYesAlwaysNoUserResponse(UserInteractionCallbackPlugin userInteractionCallbackPlugin, String idInfo, YesAlwaysNoUserResponse yesAlwaysNoUserResponseDefaultValue, Object... arrayParam) {
		String userResponse;
		YesAlwaysNoUserResponse yesAlwaysNoUserResponse;

		if (idInfo.endsWith("*")) {
			String defaultValue = null;

			switch (yesAlwaysNoUserResponseDefaultValue) {
			case YES:
				defaultValue = "Y";
				break;

			case YES_ALWAYS:
				defaultValue = "A";
				break;

			case NO:
				defaultValue = "N";
				break;
			}

			idInfo = idInfo.substring(0, idInfo.length() - 1) + " (Y(es), A(lways), N(o)) [" + defaultValue + "]? ";
		}

		do {
			userResponse = userInteractionCallbackPlugin.getInfoWithDefault(idInfo, yesAlwaysNoUserResponseDefaultValue.toString(), arrayParam);

			userResponse = userResponse.toUpperCase().trim();

			yesAlwaysNoUserResponse = null;

			try {
				yesAlwaysNoUserResponse = YesAlwaysNoUserResponse.valueOf(userResponse);
			} catch (IllegalArgumentException iae) {
				if (userResponse.equals("Y") || userResponse.equals("1")) {
					yesAlwaysNoUserResponse = YesAlwaysNoUserResponse.YES;
				} else if (userResponse.equals("A")) {
					yesAlwaysNoUserResponse = YesAlwaysNoUserResponse.YES_ALWAYS;
				} else if (userResponse.equals("N") || userResponse.equals("0")) {
					yesAlwaysNoUserResponse = YesAlwaysNoUserResponse.NO;
				}
			}

			if (yesAlwaysNoUserResponse == null) {
				userInteractionCallbackPlugin.provideInfo("Invalid response. Please try again.");
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
	 * @param idInfo See corresponding parameter in
	 *   UserInteractionCallbackPlugin.getInfo.
	 * @param yesAlwaysNoUserResponseDefaultValue Default user response. It is
	 *   translated to the defaultValue parameter in
	 *   UserInteractionCallbackPlugin.getInfo.
	 * @param arrayParam See corresponding parameter in
	 *   UserInteractionCallbackPlugin.getInfo.
	 * @return YesAlwaysNoUserResponse.
	 */
	public static YesAlwaysNoUserResponse getInfoYesNoUserResponse(UserInteractionCallbackPlugin userInteractionCallbackPlugin, String idInfo, YesAlwaysNoUserResponse yesAlwaysNoUserResponseDefaultValue, Object... arrayParam) {
		String userResponse;
		YesAlwaysNoUserResponse yesAlwaysNoUserResponse;

		if (idInfo.endsWith("*")) {
			String defaultValue = null;

			switch (yesAlwaysNoUserResponseDefaultValue) {
			case YES:
				defaultValue = "Y";
				break;

			case YES_ALWAYS:
				throw new RuntimeException("YES_ALWAYS is not supported by this method.");

			case NO:
				defaultValue = "N";
				break;
			}

			idInfo = idInfo.substring(0, idInfo.length() - 1) + " (Y(es), N(o)) [" + defaultValue + "]? ";
		}

		do {
			userResponse = userInteractionCallbackPlugin.getInfoWithDefault(idInfo, yesAlwaysNoUserResponseDefaultValue.toString(), arrayParam);

			userResponse = userResponse.toUpperCase().trim();

			yesAlwaysNoUserResponse = null;

			try {
				yesAlwaysNoUserResponse = YesAlwaysNoUserResponse.valueOf(userResponse);

				if (yesAlwaysNoUserResponse == YesAlwaysNoUserResponse.YES_ALWAYS) {
					yesAlwaysNoUserResponse = null;
				}
			} catch (IllegalArgumentException iae) {
				if (userResponse.equals("Y") || userResponse.equals("1")) {
					yesAlwaysNoUserResponse = YesAlwaysNoUserResponse.YES;
				} else if (userResponse.equals("N") || userResponse.equals("0")) {
					yesAlwaysNoUserResponse = YesAlwaysNoUserResponse.NO;
				}
			}

			if (yesAlwaysNoUserResponse == null) {
				userInteractionCallbackPlugin.provideInfo("Invalid response. Please try again.");
				continue;
			}

			break;
		} while (true);

		return yesAlwaysNoUserResponse;
	}

	/**
	 * Facilitates letting the user input a Version.
	 *
	 * If idInfo ends with "*" it is replaced with text that gides the user in
	 * inputting the Version.
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
	 * @param idInfo See corresponding parameter in
	 *   UserInteractionCallbackPlugin.getInfo.
	 * @param versionDefaultValue Default user response. It is translated to the
	 *   defaultValue parameter in UserInteractionCallbackPlugin.getInfo.
	 * @param arrayParam See corresponding parameter in
	 *   UserInteractionCallbackPlugin.getInfo.
	 * @return Version.
	 */
	public static Version getInfoVersion(VersionType versionType, ScmPlugin scmPlugin, UserInteractionCallbackPlugin userInteractionCallbackPlugin, String idInfo, Version versionDefaultValue, Object... arrayParam) {
		String userResponse;
		Version version = null;

		if (idInfo.endsWith("*")) {
			String stringVersionType;
			String versionMayOrMayNotExist;
			String defaultValue;

			if (versionType == null) {
				stringVersionType = "S|D";
			} else if (versionType == VersionType.STATIC){
				stringVersionType = "S";
			} else if (versionType == VersionType.DYNAMIC){
				stringVersionType = "D";
			} else {
				throw new RuntimeException("Should never get here.");
			}

			if (scmPlugin != null) {
				versionMayOrMayNotExist = "; version must exist";
			} else {
				versionMayOrMayNotExist = "";
			}

			if (versionDefaultValue != null) {
				defaultValue = " [" + versionDefaultValue.toString() + "]";
			} else {
				defaultValue = "";
			}

			idInfo = idInfo.substring(0, idInfo.length() - 1) + " (use the format " + stringVersionType + "/<version>" + versionMayOrMayNotExist + ")" + defaultValue + "? ";
		}

		do {
			if (versionDefaultValue != null) {
				userResponse = userInteractionCallbackPlugin.getInfoWithDefault(idInfo, versionDefaultValue.toString(), arrayParam);
			} else {
				userResponse = userInteractionCallbackPlugin.getInfo(idInfo, arrayParam);
			}

			try {
				version = Version.parse(userResponse);
			} catch (Exception e) {
				userInteractionCallbackPlugin.provideInfo(e.getMessage());
				userInteractionCallbackPlugin.provideInfo("Please try again.");
				continue;
			}

			if ((versionType != null) && (version.getVersionType() != versionType)) {
				userInteractionCallbackPlugin.provideInfo("The version is " + version.getVersionType() + " which is not the expected type " + versionType + '.');
				userInteractionCallbackPlugin.provideInfo("Please try again.");
				continue;
			}

			if (scmPlugin != null) {
				if (!scmPlugin.isVersionExists(version)) {
					userInteractionCallbackPlugin.provideInfo("The version " + version + " does not exist.");
					userInteractionCallbackPlugin.provideInfo("Please try again.");
					continue;
				}
			}

			break;
		} while (true);

		return version;
	}

	/**
	 * Helper method to return the List of root ModuleVersion's used by many tools.
	 *
	 * If the command line specifies the --root-module-version option, no root
	 * ModuleVersions's must be specified by RootManager, and the List of root
	 * ModuleVerion's contains the single ModuleVersion specified by this option.
	 *
	 * Otherwise, RootManager must specify at least one root ModuleVersion and this
	 * List of root ModuleVersion's specified by RootManager is returned.
	 *
	 * @param commandLine CommandLine.
	 * @return List of root ModuleVersion's.
	 */
	public static List<ModuleVersion> getListModuleVersionRoot(CommandLine commandLine) {
		List<ModuleVersion> listModuleVersionRoot;

		if (commandLine.hasOption("root-module-version")) {
			if (!RootManager.getListModuleVersion().isEmpty()) {
				throw new RuntimeExceptionUserError("No root module version can be specified on the command line with the --root-module-version option when one is specified in the workspace. Use the --help option to display help information.");
			}

			 listModuleVersionRoot = new ArrayList<ModuleVersion>();

			 try {
				 listModuleVersionRoot.add(ModuleVersion.parse(commandLine.getOptionValue("root-module-version")));
			 } catch (ParseException pe) {
				 throw new RuntimeExceptionUserError(pe.getMessage());
			 }
		} else {
			if (RootManager.getListModuleVersion().isEmpty()) {
				throw new RuntimeExceptionUserError("A root module version must be specified on the command line with the --root-module-version option when none is specified in the workspace. Use the --help option to display help information.");
			}

			listModuleVersionRoot = RootManager.getListModuleVersion();
		}

		return listModuleVersionRoot;

	}

	/**
	 * Helper method to return a ReferencePathMatcherAnd that is built from the
	 * ReferencePathMatcherOr specified by RootManager and a ReferencePathMatcherOr
	 * built from the command line options --reference-path-matcher that specify
	 * ReferencePathMatcherByElement literals.
	 *
	 * @param commandLine CommandLine.
	 * @return ReferencePathMatcher.
	 */
	public static ReferencePathMatcher getReferencePathMatcher(CommandLine commandLine) {
		Model model;
		String[] arrayStringReferencePathMatcher;
		ReferencePathMatcherOr referencePathMatcherOrCommandLine;
		ReferencePathMatcherAnd referencePathMatcherAnd;

		model = ExecContextHolder.get().getModel();

		arrayStringReferencePathMatcher = commandLine.getOptionValues("reference-path-matcher");

		if (arrayStringReferencePathMatcher == null) {
			throw new RuntimeExceptionUserError("At least one --reference-path-matcher option must be specified on the command line. Use the --help option to display help information.");
		}

		referencePathMatcherOrCommandLine = new ReferencePathMatcherOr();

		for (int i = 0; i < arrayStringReferencePathMatcher.length; i++) {
			try {
				referencePathMatcherOrCommandLine.addReferencePathMatcher(ReferencePathMatcherByElement.parse(arrayStringReferencePathMatcher[i], model));
			} catch (ParseException pe) {
				throw new RuntimeExceptionUserError(pe.getMessage());
			}
		}

		referencePathMatcherAnd = new ReferencePathMatcherAnd();

		referencePathMatcherAnd.addReferencePathMatcher(RootManager.getReferencePathMatcherOr());
		referencePathMatcherAnd.addReferencePathMatcher(referencePathMatcherOrCommandLine);

		return referencePathMatcherAnd;
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

		switch (Util.getInfoYesAlwaysNoUserResponse(userInteractionCallbackPlugin, "Do you want to continue*", YesAlwaysNoUserResponse.YES)) {
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
	 * @return The ResourceBundle of this class, which also contains global locale-
	 *   specific resources that can be used by other classes.
	 */
	public static ResourceBundle getResourceBundle() {
		if (Util.resourceBundle == null) {
			Util.resourceBundle = ResourceBundle.getBundle(Util.RESOURCE_BUNDLE);
		}

		return Util.resourceBundle;
	}

	/**
	 * Factors in all the code required to localize messages in a simple manner. It
	 * uses a ResourceBundle to get a pattern corresponding to a key and uses
	 * MessageFormat to format the message using this pattern and arguments.
	 *
	 * @param resourceBundle ResourceBundle containing the MessageFormat pattern used
	 *   to format the message.
	 * @param patternKey Key of the MessageFormat pattern in the ResourceBundle.
	 * @param arrayArgument Array of arguments passed to MessageFormat.
	 */
	public static String formatMessage(ResourceBundle resourceBundle, String patternKey, Object... arrayArgument) {
		return MessageFormat.format(resourceBundle.getString(patternKey), arrayArgument);
	}
}
