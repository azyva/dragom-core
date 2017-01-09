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

package org.azyva.dragom.execcontext.plugin.impl;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.azyva.dragom.execcontext.ExecContext;
import org.azyva.dragom.execcontext.WorkspaceExecContext;
import org.azyva.dragom.execcontext.plugin.CredentialStorePlugin;
import org.azyva.dragom.execcontext.plugin.RuntimePropertiesPlugin;
import org.azyva.dragom.execcontext.plugin.UserInteractionCallbackPlugin;
import org.azyva.dragom.execcontext.support.ExecContextHolder;
import org.azyva.dragom.security.CredentialStore;
import org.azyva.dragom.security.CredentialStore.ResourcePatternRealmUser;
import org.azyva.dragom.util.RuntimeExceptionUserError;
import org.azyva.dragom.util.Util;

/**
 * This default implementation of {@link CredentialStorePlugin} uses
 * {@link CredentialStore} to manage credentials.
 * <p>
 * The MASTER_KEY_FILE initialization property specifies the Path of the master
 * key file.
 * <p>
 * If the CREDENTIAL_FILE initialization property is defined, it
 * specifies the Path of the credential file. The default credential file is
 * credentials.properties in the workspace metadata directory.
 * <p>
 * This mappings between resource Pattern's and corresponding realms and users are
 * defined using runtime properties defined on the root ClassificationNode:
 * <ul>
 * <li>RESOURCE_PATTERN_REALM_USER_MAPPINGS: ","-separated list of
 *     resource-realm-user mapping names. For each such name, a
 *     {@link org.azyva.dragom.security.CredentialStore.ResourcePatternRealmUser} is
 *     created.
 * <li>RESOURCE_PATTERN_REALM_USER_MAPPING_RESOURCE_PATTERN.&lt;name&gt;: Literal
 *     value of
 *     {@link org.azyva.dragom.security.CredentialStore.ResourcePatternRealmUser#patternResource}.
 * <li>RESOURCE_PATTERN_REALM_USER_MAPPING_REALM.&lt;name&gt;: Value of
 *     {@link org.azyva.dragom.security.CredentialStore.ResourcePatternRealmUser#realm}.
 * <li>RESOURCE_PATTERN_REALM_USER_MAPPING_USER.&lt;name&gt;: Value of
 *     {@link org.azyva.dragom.security.CredentialStore.ResourcePatternRealmUser#user}.
 * </ul>
 * If {@link UserInteractionCallbackPlugin#isBatchMode} returns false, this class
 * interacts with the user when appropriate to obtain missing passwords, as
 * recommended in CredentialStorePlugin.
 * <p>
 * In addition to implementing CredentialStorePlugin, this class publicly provides
 * access to the CredentialStore instance allowing to obtain information about
 * realms and explicitly modify the passwords, operations which are not supported
 * by the interface. This is meant to be used by a tool that would assume this
 * specific implementation of the CredentialStorePlugin to allow the user to
 * manage the credential store. CredentialManagerTool from dragom-cli-tools is such
 * a CLI tool.
 *
 * @author David Raymond
 */
public class DefaultCredentialStorePluginImpl implements CredentialStorePlugin {
  /**
   * Initialization property that specifies the file containing the credentials. "~"
   * in the value of this property is replaced by the user home directory.
   */
  private static final String INIT_PROPERTY_CREDENTIAL_FILE = "CREDENTIAL_FILE";

  /**
   * Default credential file (within the workspace metadata directory) when the
   * CREDENTIAL_FILE initialization property is not defined.
   */
  private static final String DEFAULT_CREDENTIAL_FILE = "credentials.properties";

  /**
   * Initialization property that specifies the master key file. "~" in the
   * value of this property is replaced by the user home directory.
   */
  public static final String INIT_PROPERTY_MASTER_KEY_FILE = "MASTER_KEY_FILE";

  /**
   * Initialization property for the list of resource-pattern-realm-user mappings.
   */
  public static final String INIT_PROPERTY_RESOURCE_PATTERN_REALM_USER_MAPPINGS = "RESOURCE_PATTERN_REALM_USER_MAPPINGS";

  /**
   * Initialization property prefix for the resource Pattern for a given
   * resource-pattern-realm-user mapping.
   */
  public static final String INIT_PROPERTY_PREFIX_RESOURCE_PATTERN_REALM_USER_MAPPING_RESOURCE_PATTERN = "RESOURCE_PATTERN_REALM_USER_MAPPING_RESOURCE_PATTERN.";

  /**
   * Initialization property prefix for the realm for a given
   * resource-pattern-realm-user mapping.
   */
  public static final String INIT_PROPERTY_PREFIX_RESOURCE_PATTERN_REALM_USER_MAPPING_REALM = "RESOURCE_PATTERN_REALM_USER_MAPPING_REALM.";

  /**
   * Initialization property prefix for the user for a given
   * resource-pattern-realm-user mapping.
   */
  public static final String INIT_PROPERTY_PREFIX_RESOURCE_PATTERN_REALM_USER_MAPPING_USER = "RESOURCE_PATTERN_REALM_USER_MAPPING_USER.";

  /**
   * Transient data prefix that stores whether credentials for a given realm and
   * user have already been validated and should not be validated again during the
   * same tool execution. The suffix is &lt;realm&gt;.&lt;user&gt; and the value is
   * Boolean.TRUE or null (absent, implying false).
   */
  private static final String TRANSIENT_DATA_PREFIX_CREDENTIALS_ALREADY_VALIDATED = DefaultCredentialStorePluginImpl.class.getName() + ".CredentialsAlreadyValidated.";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_INPUT_USER_FOR_RESOURCE = "INPUT_USER_FOR_RESOURCE";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_INPUT_PASSWORD_FOR_USER_RESOURCE = "INPUT_PASSWORD_FOR_USER_RESOURCE";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_USER_PASSWORD_INVALID = "USER_PASSWORD_INVALID";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_USER_NOT_SAME_AS_RESOURCE = "USER_NOT_SAME_AS_RESOURCE";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_NO_RESOURCE_REALM_MAPPING_FOUND = "NO_RESOURCE_REALM_MAPPING_FOUND";

  /**
   * See description in ResourceBundle.
   */
  private static final String MSG_PATTERN_KEY_PASSWORD_NOT_AVAILABLE = "PASSWORD_NOT_AVAILABLE";

  /**
   * ResourceBundle specific to this class.
   */
  private static final ResourceBundle resourceBundle = ResourceBundle.getBundle(DefaultCredentialStorePluginImpl.class.getName() + "ResourceBundle");

  /**
   * CredentialStore.
   */
  private CredentialStore credentialStore;

  /**
   * Constructor.
   *
   * @param execContext ExecContext.
   */
  public DefaultCredentialStorePluginImpl(ExecContext execContext) {
    String stringCredentialFile;
    Path pathMetadataDir;
    Path pathCredentialFile;
    String stringMasterKeyFile;
    Path pathMasterKeyFile;
    List<CredentialStore.ResourcePatternRealmUser> listResourcePatternRealmUser;
    RuntimePropertiesPlugin runtimePropertiesPlugin;
    String stringRuntimeProperty;

    Util.applyDragomSystemProperties();

    /*
     * Compute the Path to the credential file.
     */

    stringCredentialFile = System.getProperty(DefaultCredentialStorePluginImpl.INIT_PROPERTY_CREDENTIAL_FILE);

    if (stringCredentialFile == null) {
      pathMetadataDir = ((WorkspaceExecContext)ExecContextHolder.get()).getPathMetadataDir();
      pathCredentialFile = pathMetadataDir.resolve(DefaultCredentialStorePluginImpl.DEFAULT_CREDENTIAL_FILE);
    } else {
      stringCredentialFile = stringCredentialFile.replace("~", Matcher.quoteReplacement(System.getProperty("user.home")));
      pathCredentialFile = Paths.get(stringCredentialFile);
    }

    /*
     * Compute the Path to the master key file.
     */

    stringMasterKeyFile = System.getProperty(DefaultCredentialStorePluginImpl.INIT_PROPERTY_MASTER_KEY_FILE);

    if (stringMasterKeyFile == null) {
      throw new RuntimeException("Initialization property " + DefaultCredentialStorePluginImpl.INIT_PROPERTY_MASTER_KEY_FILE + " is not defined.");
    } else {
      stringMasterKeyFile = stringMasterKeyFile.replaceAll("~", Matcher.quoteReplacement(System.getProperty("user.home")));
      pathMasterKeyFile = Paths.get(stringMasterKeyFile);
    }

    /*
     * Reads the ResourcePatternRealmUser mappings from runtime properties.
     */

    listResourcePatternRealmUser = new ArrayList<ResourcePatternRealmUser>();

    runtimePropertiesPlugin = ExecContextHolder.get().getExecContextPlugin(RuntimePropertiesPlugin.class);

    stringRuntimeProperty = runtimePropertiesPlugin.getProperty(null, DefaultCredentialStorePluginImpl.INIT_PROPERTY_RESOURCE_PATTERN_REALM_USER_MAPPINGS);

    for (String mapping: stringRuntimeProperty.split(",")) {
      ResourcePatternRealmUser resourcePatternRealmUser;

      resourcePatternRealmUser = new ResourcePatternRealmUser();

      mapping = mapping.trim();

      stringRuntimeProperty = runtimePropertiesPlugin.getProperty(null, DefaultCredentialStorePluginImpl.INIT_PROPERTY_PREFIX_RESOURCE_PATTERN_REALM_USER_MAPPING_RESOURCE_PATTERN + mapping);
      resourcePatternRealmUser.patternResource = Pattern.compile(stringRuntimeProperty);

      resourcePatternRealmUser.realm = runtimePropertiesPlugin.getProperty(null, DefaultCredentialStorePluginImpl.INIT_PROPERTY_PREFIX_RESOURCE_PATTERN_REALM_USER_MAPPING_REALM + mapping);

      if (resourcePatternRealmUser.realm == null) {
        throw new RuntimeException("Realm cannot be null for mapping " + mapping + '.');
      }

      resourcePatternRealmUser.user = runtimePropertiesPlugin.getProperty(null, DefaultCredentialStorePluginImpl.INIT_PROPERTY_PREFIX_RESOURCE_PATTERN_REALM_USER_MAPPING_USER + mapping);

      listResourcePatternRealmUser.add(resourcePatternRealmUser);
    }

    /*
     * Construct CredentialStore.
     */

    this.credentialStore = new CredentialStore(pathCredentialFile, pathMasterKeyFile, listResourcePatternRealmUser);
  }

  /**
   * @return CredentialStore.
   */
  public CredentialStore getCredentialStore() {
    return this.credentialStore;
  }

  @Override
  public boolean isCredentialsExist(String resource, String user, CredentialValidator credentialValidator) {
    return this.getCredentialsInternal(resource, user, credentialValidator) != null;
  }

  @Override
  public Credentials getCredentials(String resource, String user, CredentialValidator credentialValidator) {
    Credentials credentials;

    credentials = this.getCredentialsInternal(resource, user, credentialValidator);

    if (credentials == null) {
      throw new RuntimeExceptionUserError(MessageFormat.format(DefaultCredentialStorePluginImpl.resourceBundle.getString(DefaultCredentialStorePluginImpl.MSG_PATTERN_KEY_PASSWORD_NOT_AVAILABLE), user, resource));
    }

    return credentials;
  }

  /**
   * The code for {@link #isCredentialsExist} and {@link #getCredentials} is very
   * similar and is factored out here.
   * <p>
   * The difference between this method and getCredentials is that this one returns
   * null if the requested credentials are not available, making it usable for
   * isCredentialsExist.
   *
   * @param resource Resource.
   * @param user User. Can be null.
   * @param credentialValidator CredentialValidator.
   * @return Credentials. null if not available.s
   */
  private Credentials getCredentialsInternal(String resource, String user, CredentialValidator credentialValidator) {
    ExecContext execContext;
    UserInteractionCallbackPlugin userInteractionCallbackPlugin;
    String password;
    Credentials credentials;
    CredentialStore.ResourceInfo resourceInfo;
    boolean indSetDefaultUser;

    execContext = ExecContextHolder.get();
    userInteractionCallbackPlugin = execContext.getExecContextPlugin(UserInteractionCallbackPlugin.class);

    resourceInfo = this.credentialStore.getResourceInfo(resource);

    if (resourceInfo == null) {
      throw new RuntimeExceptionUserError(MessageFormat.format(DefaultCredentialStorePluginImpl.resourceBundle.getString(DefaultCredentialStorePluginImpl.MSG_PATTERN_KEY_NO_RESOURCE_REALM_MAPPING_FOUND), resource));
    }

    if ((user != null) && (resourceInfo.user != null) && !user.equals(resourceInfo.user)) {
      throw new RuntimeExceptionUserError(MessageFormat.format(DefaultCredentialStorePluginImpl.resourceBundle.getString(DefaultCredentialStorePluginImpl.MSG_PATTERN_KEY_USER_NOT_SAME_AS_RESOURCE), user, resourceInfo.user, resource));
    }

    if (user == null) {
      user = resourceInfo.user;
    }

    if (user == null) {
      user = this.credentialStore.getDefaultUser(resource);
    }

    password = this.credentialStore.getPassword(resource, user);

    if ((password != null) && (credentialValidator != null)) {
      if (!Util.isNotNullAndTrue((Boolean)execContext.getTransientData(DefaultCredentialStorePluginImpl.TRANSIENT_DATA_PREFIX_CREDENTIALS_ALREADY_VALIDATED + resourceInfo.realm + '.' + user))) {
        if (!credentialValidator.validateCredentials(resource, user, password)) {
          userInteractionCallbackPlugin.provideInfo(MessageFormat.format(DefaultCredentialStorePluginImpl.resourceBundle.getString(DefaultCredentialStorePluginImpl.MSG_PATTERN_KEY_USER_PASSWORD_INVALID), user, resource));
          password = null;
        }

        execContext.setTransientData(DefaultCredentialStorePluginImpl.TRANSIENT_DATA_PREFIX_CREDENTIALS_ALREADY_VALIDATED + resourceInfo.realm + '.' + user, true);
      }
    }

    /*
     * Here, if password != null, user cannot be null either.
     */

    if (password != null) {
      credentials = new Credentials();
      credentials.resource = resource;
      credentials.user = user;
      credentials.password = password;
      credentials.indUserSpecificResource = (resourceInfo.user != null);
      return credentials;
    }

    indSetDefaultUser = (user == null);

    do {
      boolean indSetPassword;

      if (user == null) {
        if (userInteractionCallbackPlugin.isBatchMode()) {
          return null;
        } else {
          String defaultUser;

          defaultUser = System.getProperty("user.name");

          user = userInteractionCallbackPlugin.getInfoWithDefault(MessageFormat.format(DefaultCredentialStorePluginImpl.resourceBundle.getString(DefaultCredentialStorePluginImpl.MSG_PATTERN_KEY_INPUT_USER_FOR_RESOURCE), resource, resourceInfo.realm, defaultUser), defaultUser);
        }
      }

      indSetPassword = false;

      password = this.credentialStore.getPassword(resource, user);

      if (password == null) {
        if (userInteractionCallbackPlugin.isBatchMode()) {
          return null;
        } else {
          password = userInteractionCallbackPlugin.getInfoPassword(MessageFormat.format(DefaultCredentialStorePluginImpl.resourceBundle.getString(DefaultCredentialStorePluginImpl.MSG_PATTERN_KEY_INPUT_PASSWORD_FOR_USER_RESOURCE), user, resource, resourceInfo.realm));
          indSetPassword = true;

          // If the password was obtained from the user, it is necessarily not validated
          // yet.
          execContext.setTransientData(DefaultCredentialStorePluginImpl.TRANSIENT_DATA_PREFIX_CREDENTIALS_ALREADY_VALIDATED + resourceInfo.realm + '.' + user, null);
        }
      }

      if (credentialValidator != null) {
        if (!Util.isNotNullAndTrue((Boolean)execContext.getTransientData(DefaultCredentialStorePluginImpl.TRANSIENT_DATA_PREFIX_CREDENTIALS_ALREADY_VALIDATED + resourceInfo.realm + '.' + user))) {
          if (!credentialValidator.validateCredentials(resource, user, password)) {
            userInteractionCallbackPlugin.provideInfo(MessageFormat.format(DefaultCredentialStorePluginImpl.resourceBundle.getString(DefaultCredentialStorePluginImpl.MSG_PATTERN_KEY_USER_PASSWORD_INVALID), user, resource));
            continue;
          }

          execContext.setTransientData(DefaultCredentialStorePluginImpl.TRANSIENT_DATA_PREFIX_CREDENTIALS_ALREADY_VALIDATED + resourceInfo.realm + '.' + user, true);
        }
      }

      if (indSetDefaultUser) {
        this.credentialStore.setDefaultUser(resource, user);
      }

      if (indSetPassword) {
        this.credentialStore.setPassword(resource, user, password);
      }

      credentials = new Credentials();
      credentials.resource = resource;
      credentials.user = user;
      credentials.password = password;
      credentials.indUserSpecificResource = (resourceInfo.user != null);
      return credentials;
    } while (true);
  }

  @Override
  public void resetCredentials(String resource, String user) {
    this.credentialStore.deletePassword(resource, user);
  }
}