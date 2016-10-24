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

package org.azyva.dragom.execcontext.plugin.impl;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.ResourceBundle;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.PBEParameterSpec;
import javax.xml.bind.DatatypeConverter;

import org.azyva.dragom.execcontext.ExecContext;
import org.azyva.dragom.execcontext.WorkspaceExecContext;
import org.azyva.dragom.execcontext.plugin.CredentialStorePlugin;
import org.azyva.dragom.execcontext.plugin.RuntimePropertiesPlugin;
import org.azyva.dragom.execcontext.plugin.UserInteractionCallbackPlugin;
import org.azyva.dragom.execcontext.support.ExecContextHolder;
import org.azyva.dragom.util.RuntimeExceptionUserError;
import org.azyva.dragom.util.Util;

/**
 * This default implementation of {@link CredentialStorePlugin} manages the
 * credentials in a credentials.properties file in the workspace metadata
 * directory, unless the system property org.azyva.dragom.CredentialFile is
 * defined, in which case it specifies the path to the file containing the
 * credentials.
 * <p>
 * This file is a Java Properties file which contains 2 types of entries:
 * <p>
 * <li>{@code <REALM>.<user>.Password=<encrypted-password>}</li>
 * <li>{@code <REALM>.DefaultUser=<default-user>}</li>
 * <p>
 * The first defines a password for a user in a realm. The second defines a
 * default user for a realm. See below for more information about realms.
 * <p>
 * The passwords are encrypted using a key that is constructed from three parts
 * of key material:
 * <p>
 * <li>A constant password hardcoded in this class<li>
 * <li>The current user name (system property user.name)</li>
 * <li>A random master password stored in the file specified by the
 * org.azyva.dragom.MasterPasswordFile system property</li>
 * <p>
 * If the master password file does not exist, it is created with newly generated
 * random password.
 * <p>
 * This class uses a sequence of mappings between resource Pattern's and
 * corresponding realms and user. These mappings are defined using runtime
 * properties defined on the root ClassificationNode:
 * <p>
 * <li>RESOURCE_PATTERN_REALM_USER_MAPPINGS: ","-separated list of
 *     resource-realm-user mapping names</li>
 * <li>RESOURCE_PATTERN_REALM_USER_MAPPING_RESOURCE_PATTERN.&ltname&gt;: Resource
 *     Pattern for &lt;name&gt. This is a regular expression which can contain
 *     captured groups that can be referenced by the other properties below;</li>
 * <li>RESOURCE_PATTERN_REALM_USER_MAPPING_REALM.&ltname&gt;: Realm for
 *     &lt;name&gt;. Can contain references to captured groups using $g syntax;</li>
 * <li>RESOURCE_PATTERN_REALM_USER_MAPPING_USER.&ltname&gt;: User for
 *     &lt;name&gt;. Can contain references to captured groups using $g syntax.
 *     Can be not specified for a given &lt;name&gt; which means no user is
 *     embedded in the resource.</li>
 * <p>
 * See
 * <a href="https://docs.oracle.com/javase/7/docs/api/java/util/regex/package-summary.html" target="_blank">
 * java.util.regex</a> for information about regular expressions in Java.
 * <p>
 * When a method takes a resource, it maps this resource to a realm using these
 * mappings. The first mapping whose resource Pattern matches the resource is
 * used. A mapping must correspond to a resource. A catch-all mapping can be used
 * if necessary.
 * <p>
 * If a resource starts with "REALM:", the realm is explicitly what follows this
 * prefix, provided it exists in the mappings. This is useful mostly for the
 * public methods that are not part of CredentialStorePlugin and that are intended
 * to be used by a tool which allows the user to manage the credential store.
 * <p>
 * If {@link UserInteractionCallbackPlugin#isBatchMode} returns false, this class
 * interacts with the user when appropriate to obtain missing passwords, as
 * recommended in CredentialStorePlugin.
 * <p>
 * In addition to implementing CredentialStorePlugin, this class exposes other
 * public methods allowing to obtain information about realms and explicitly
 * modify the passwords, operations which are not supported by the interface. This
 * is meant to be used by a tool that would assume this specific implementation of
 * the CredentialStorePlugin to allow the user to manage the credential store.
 * {@link CredentialManagerTool} is such a CLI tool.
 *
 * @author David Raymond
 */
public class DefaultCredentialStorePluginImpl implements CredentialStorePlugin {
	/**
	 * System property that specifies the file containing the credentials. "~" in the
	 * value of this property is replaced by the user home directory.
	 */
	private static final String SYS_PROP_CREDENTIAL_FILE = "org.azyva.dragom.CredentialFile";

	/**
	 * Default credential file (within the workspace metadata directory) when the
	 * org.azyva.dragom.CredentialFile system property is not defined.
	 */
	private static final String DEFAULT_CREDENTIAL_FILE = "credentials.properties";

	/**
	 * Hardcoded password generated using
	 * https://lastpass.com/generatepassword.php.
	 */
	private static final String HARDCODED_PASSWORD ="S4ag$*v6G1kMGWvK";

	/**
	 * System property that specifies the master password file. "~" in the
	 * value of this property is replaced by the user home directory.
	 */
	public static final String SYS_PROP_MASTER_PASSWORD_FILE = "org.azyva.dragom.MasterPasswordFile";

	/**
	 * Runtime property for the list of resource-pattern-realm-user mappings.
	 */
	public static final String RUNTIME_PROPERTY_RESOURCE_PATTERN_REALM_USER_MAPPINGS = "RESOURCE_PATTERN_REALM_USER_MAPPINGS";

	/**
	 * Runtime property prefix for the resource Pattern for a given
	 * resource-pattern-realm-user mapping.
	 */
	public static final String RUNTIME_PROPERTY_PREFIX_RESOURCE_PATTERN_REALM_USER_MAPPING_RESOURCE_PATTERN = "RESOURCE_PATTERN_REALM_USER_MAPPING_RESOURCE_PATTERN.";

	/**
	 * Runtime property prefix for the realm for a given
	 * resource-pattern-realm-user mapping.
	 */
	public static final String RUNTIME_PROPERTY_PREFIX_RESOURCE_PATTERN_REALM_USER_MAPPING_REALM = "RESOURCE_PATTERN_REALM_USER_MAPPING_REALM.";

	/**
	 * Runtime property prefix for the user for a given
	 * resource-pattern-realm-user mapping.
	 */
	public static final String RUNTIME_PROPERTY_PREFIX_RESOURCE_PATTERN_REALM_USER_MAPPING_USER = "RESOURCE_PATTERN_REALM_USER_MAPPING_USER.";

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
	private static final String MSG_PATTERN_KEY_EXPLICITLY_SPECIFED_REALM_NOT_SUPPORTED = "EXPLICITLY_SPECIFED_REALM_NOT_SUPPORTED";

	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_NO_RESOURCE_REALM_MAPPING_FOUND = "NO_RESOURCE_REALM_MAPPING_FOUND";

	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_USER_NOT_SPECIFIED = "USER_NOT_SPECIFIED";

	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_PASSWORD_NOT_AVAILABLE = "PASSWORD_NOT_AVAILABLE";

	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_NO_DEFAULT_USER_FOR_RESOURCE = "NO_DEFAULT_USER_FOR_RESOURCE";

	/**
	 * See description in ResourceBundle.
	 */
	private static final String MSG_PATTERN_KEY_RESOURCE_SPECIFIES_USER = "RESOURCE_SPECIFIES_USER";

	/**
	 * Property suffix for the default user. The prefix is the realm.
	 */
	public static final String PROPERTY_SUFFIX_DEFAULT_USER = ".DefaultUser";

	/**
	 * Property suffix for a password. The prefix is &lt;realm&gt;.&lt;user&gt;.
	 */
	public static final String PROPERTY_SUFFIX_PASSWORD = ".Password.";

	/**
	 * ResourceBundle specific to this class.
	 */
	private static final ResourceBundle resourceBundle = ResourceBundle.getBundle(DefaultCredentialStorePluginImpl.class.getName() + "ResourceBundle");

	/**
	 * SecretKey for encrypting the passwords.
	 */
	private SecretKey secretKeyPasswordEncryption;

	/**
	 * Mapping from a resource Pattern to a realm and user.
	 * <p>
	 * Also used as the return type for some methods that need to return a realm and
	 * a user, even if not related to a mapping from a resource.
	 * <p>
	 * This class is not part of {@link CredentialStorePlugin}. It is still public
	 * since it also exposes implementation-specific public methods that use it.
	 */
	public static class ResourcePatternRealmUser {
		/**
		 * Pattern to match a resource.
		 */
		public Pattern patternResource;

		/**
		 * Realm. Can contain references to captured groups.
		 */
		public String realm;

		/**
		 * User. Can contain references to captured groups. Can be null.
		 */
		public String user;
	}

	/**
	 * List of ResourcePatternRealmUser mappings.
	 */
	private List<ResourcePatternRealmUser> listResourcePatternRealmUser;

	/**
	 * Constructor.
	 *
	 * @param execContext ExecContext.
	 */
	public DefaultCredentialStorePluginImpl(ExecContext execContext) {
		RuntimePropertiesPlugin runtimePropertiesPlugin;
		String stringRuntimeProperty;
		String stringMasterPasswordFile;
		Path pathMasterPasswordFile;
		byte[] arrayByteMasterPassword;

		/*
		 * Reads the ResourcePatternRealmUser mappings from runtime properties.
		 */

		this.listResourcePatternRealmUser = new ArrayList<ResourcePatternRealmUser>();

		runtimePropertiesPlugin = ExecContextHolder.get().getExecContextPlugin(RuntimePropertiesPlugin.class);

		stringRuntimeProperty = runtimePropertiesPlugin.getProperty(null, DefaultCredentialStorePluginImpl.RUNTIME_PROPERTY_RESOURCE_PATTERN_REALM_USER_MAPPINGS);

		for (String mapping: stringRuntimeProperty.split(",")) {
			ResourcePatternRealmUser resourcePatternRealmUser;

			resourcePatternRealmUser = new ResourcePatternRealmUser();

			mapping = mapping.trim();

			stringRuntimeProperty = runtimePropertiesPlugin.getProperty(null, DefaultCredentialStorePluginImpl.RUNTIME_PROPERTY_PREFIX_RESOURCE_PATTERN_REALM_USER_MAPPING_RESOURCE_PATTERN + mapping);
			resourcePatternRealmUser.patternResource = Pattern.compile(stringRuntimeProperty);

			resourcePatternRealmUser.realm = runtimePropertiesPlugin.getProperty(null, DefaultCredentialStorePluginImpl.RUNTIME_PROPERTY_PREFIX_RESOURCE_PATTERN_REALM_USER_MAPPING_REALM + mapping);

			if (resourcePatternRealmUser.realm == null) {
				throw new RuntimeException("Realm cannot be null for mapping " + mapping + '.');
			}

			resourcePatternRealmUser.user = runtimePropertiesPlugin.getProperty(null, DefaultCredentialStorePluginImpl.RUNTIME_PROPERTY_PREFIX_RESOURCE_PATTERN_REALM_USER_MAPPING_USER + mapping);

			this.listResourcePatternRealmUser.add(resourcePatternRealmUser);
		}

		/*
		 * Construct the secret key to encrypt passwords.
		 */

		Util.applyDragomSystemProperties();

		stringMasterPasswordFile = System.getProperty(DefaultCredentialStorePluginImpl.SYS_PROP_MASTER_PASSWORD_FILE);
		stringMasterPasswordFile = stringMasterPasswordFile.replaceAll("~", Matcher.quoteReplacement(System.getProperty("user.home")));
		pathMasterPasswordFile = Paths.get(stringMasterPasswordFile);

		arrayByteMasterPassword = new byte[16];

		if (!pathMasterPasswordFile.toFile().isFile()) {
			SecureRandom secureRandom;
			OutputStream outputStreamMasterPasswordFile;

			secureRandom = new SecureRandom();

			for (int i = 0; i < 16; i++) {
				arrayByteMasterPassword[i] = (byte)(33 + secureRandom.nextInt(94));
			}

			try {
				pathMasterPasswordFile.getParent().toFile().mkdirs();
				outputStreamMasterPasswordFile = new FileOutputStream(pathMasterPasswordFile.toFile());
				outputStreamMasterPasswordFile.write(arrayByteMasterPassword);
				outputStreamMasterPasswordFile.close();
			} catch (IOException ioe) {
				throw new RuntimeException(ioe);
			}
		} else {
			InputStream inputStreamMasterPasswordFile;

			try {
				inputStreamMasterPasswordFile = new FileInputStream(pathMasterPasswordFile.toFile());
				inputStreamMasterPasswordFile.read(arrayByteMasterPassword);
				inputStreamMasterPasswordFile.close();
			} catch (IOException ioe) {
				throw new RuntimeException(ioe);
			}
		}

		try {
			this.secretKeyPasswordEncryption = SecretKeyFactory.getInstance("PBEWithMD5AndDES").generateSecret(
					new PBEKeySpec((new String(arrayByteMasterPassword) + System.getProperty("user.name") + DefaultCredentialStorePluginImpl.HARDCODED_PASSWORD).toCharArray()));
		} catch (InvalidKeySpecException | NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public boolean isCredentialsExist(String resource, String user, CredentialValidator credentialValidator) {
		return this.isCredentialsExist(resource, user, credentialValidator, false);
	}

	@Override
	public Credentials getCredentials(String resource, String user, CredentialValidator credentialValidator) {
		return this.getCredentials(resource, user, credentialValidator, false);
	}

	@Override
	public void resetCredentials(String resource, String user) {
		ResourcePatternRealmUser resourcePatternRealmUser;
		Properties propertiesCredentials;

		resourcePatternRealmUser = this.getRealmUserForResource(resource);

		if ((user != null) && (resourcePatternRealmUser.user != null) && !user.equals(resourcePatternRealmUser.user)) {
			throw new RuntimeExceptionUserError(MessageFormat.format(DefaultCredentialStorePluginImpl.resourceBundle.getString(DefaultCredentialStorePluginImpl.MSG_PATTERN_KEY_USER_NOT_SAME_AS_RESOURCE), user, resourcePatternRealmUser.user, resource));
		}

		propertiesCredentials = this.readPropertiesCredentials();

		if (user == null) {
			user = propertiesCredentials.getProperty(resourcePatternRealmUser.realm + DefaultCredentialStorePluginImpl.PROPERTY_SUFFIX_DEFAULT_USER);
		}

		if (user == null) {
			return;
		}

		propertiesCredentials.remove(resourcePatternRealmUser.realm + '.' + user + DefaultCredentialStorePluginImpl.PROPERTY_SUFFIX_PASSWORD);
		this.savePropertiesCredentials(propertiesCredentials);
	}

	/**
	 * Gets the realm and user corresponding to the specified resource.
	 *
	 * @param resource Resource.
	 * @return ResourcePatternRealmUser. We reuse this type, but in fact only the
	 *   realm and user fields are used.
	 */
	private ResourcePatternRealmUser getRealmUserForResource(String resource) {
		ResourcePatternRealmUser resourcePatternRealmUserReturn;

		resourcePatternRealmUserReturn = new ResourcePatternRealmUser();

		if (resource == null) {
			resource = "";
		}

		if (resource.startsWith("REALM:")) {
			String realm;

			realm = resource.substring(6);

			for (ResourcePatternRealmUser resourcePatternRealmUser: this.listResourcePatternRealmUser){
				if (resourcePatternRealmUser.realm.equals(realm)) {
					resourcePatternRealmUserReturn.realm = realm;
					return resourcePatternRealmUserReturn;
				}
			}

			throw new RuntimeExceptionUserError(MessageFormat.format(DefaultCredentialStorePluginImpl.resourceBundle.getString(DefaultCredentialStorePluginImpl.MSG_PATTERN_KEY_EXPLICITLY_SPECIFED_REALM_NOT_SUPPORTED), realm));
		} else {
			for (ResourcePatternRealmUser resourcePatternRealmUser: this.listResourcePatternRealmUser) {
				Matcher matcher;

				matcher = resourcePatternRealmUser.patternResource.matcher(resource);

				if (matcher.matches()) {
					StringBuffer stringBuffer;

					stringBuffer = new StringBuffer();

					matcher.appendReplacement(stringBuffer, resourcePatternRealmUser.realm);
					resourcePatternRealmUserReturn.realm = stringBuffer.toString();

					if (resourcePatternRealmUser.user != null) {
						// We need to reexecute the match since the call to appendReplacement above has
						// moved the append position in the input sequence.
						matcher.matches();

						matcher.appendReplacement(stringBuffer, resourcePatternRealmUser.user);
						resourcePatternRealmUserReturn.user = stringBuffer.toString();
					}

					return resourcePatternRealmUserReturn;
				}
			}
		}

		throw new RuntimeExceptionUserError(MessageFormat.format(DefaultCredentialStorePluginImpl.resourceBundle.getString(DefaultCredentialStorePluginImpl.MSG_PATTERN_KEY_NO_RESOURCE_REALM_MAPPING_FOUND), resource));
	}

	/**
	 * @return Properties for the credentials read from the credential file.
	 */
	private Properties readPropertiesCredentials() {
		Path pathCredentialFile;
		Properties propertiesCredentials;

		pathCredentialFile = this.getPathCredentialFile();
		propertiesCredentials = new Properties();

		try {
			propertiesCredentials.load(new FileInputStream(pathCredentialFile.toFile()));
		} catch (FileNotFoundException fnfe) {
		} catch (IOException ioe) {
			throw new RuntimeException(ioe);
		}

		return propertiesCredentials;
	}

	/**
	 * Saves the properties for the credentials to the credential file.
	 */
	private void savePropertiesCredentials(Properties propertiesCredentials) {
		Path pathCredentialFile;

		pathCredentialFile = this.getPathCredentialFile();

		try {
			propertiesCredentials.store(new FileOutputStream(pathCredentialFile.toFile()), null);
		} catch (IOException ioe) {
			throw new RuntimeException(ioe);
		}
	}

	/**
	 * @return Path to the credential file.
	 */
	private Path getPathCredentialFile() {
		String stringCredentialFile;
		Path pathMetadataDir;

		Util.applyDragomSystemProperties();

		stringCredentialFile = System.getProperty(DefaultCredentialStorePluginImpl.SYS_PROP_CREDENTIAL_FILE);

		if (stringCredentialFile == null) {
			pathMetadataDir = ((WorkspaceExecContext)ExecContextHolder.get()).getPathMetadataDir();
			return pathMetadataDir.resolve(DefaultCredentialStorePluginImpl.DEFAULT_CREDENTIAL_FILE);
		} else {
			stringCredentialFile = stringCredentialFile.replaceAll("~", Matcher.quoteReplacement(System.getProperty("user.home")));
			return Paths.get(stringCredentialFile);
		}
	}

	private String decryptPassword(String user, String passwordEncrypted) {
		Cipher cipherPbe;

		try {
			cipherPbe = Cipher.getInstance("PBEWithMD5AndDES");
			cipherPbe.init(Cipher.DECRYPT_MODE, this.secretKeyPasswordEncryption, new PBEParameterSpec(this.getSalt(user), 8));

			/*
			 * We use the user itself as a salt.
			 * In order to have readable characters in the credential file, we use Base 64
			 * encoding. Many dedicated libraries exist for performing Base 64 encoding, but
			 * DatatypeCOnverter in JAXB does the job.
			 */
			return new String(cipherPbe.doFinal(DatatypeConverter.parseBase64Binary(passwordEncrypted)), "UTF-8");
		} catch (NoSuchPaddingException|NoSuchAlgorithmException|InvalidKeyException|InvalidAlgorithmParameterException|BadPaddingException|IllegalBlockSizeException|UnsupportedEncodingException e) {
			throw new RuntimeException(e);
		}
	}

	private String encryptPassword(String user, String password) {
		Cipher cipherPbe;

		try {
			cipherPbe = Cipher.getInstance("PBEWithMD5AndDES");
			cipherPbe.init(Cipher.ENCRYPT_MODE, this.secretKeyPasswordEncryption);

			/*
			 * We use the user itself as a salt.
			 * In order to have readable characters in the credential file, we use Base 64
			 * encoding. Many dedicated libraries exist for performing Base 64 encoding, but
			 * DatatypeCOnverter in JAXB does the job.
			 */
			cipherPbe.init(Cipher.ENCRYPT_MODE, this.secretKeyPasswordEncryption, new PBEParameterSpec(this.getSalt(user), 8));
			return DatatypeConverter.printBase64Binary(cipherPbe.doFinal(password.getBytes("UTF-8")));
		} catch (NoSuchPaddingException|NoSuchAlgorithmException|InvalidKeyException|InvalidAlgorithmParameterException|BadPaddingException|IllegalBlockSizeException|UnsupportedEncodingException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Returns the salt required for encryption.
	 * <p>
	 * The salt used is in fact the user.
	 * <p>
	 * A salt is not secret and is generally used to prevent rainbow attacks. In the
	 * present context, it is probably overkill, but the encryption mechanism used
	 * here does require one.
	 *
	 * @param user User.
	 * @return salt.
	 */
	private byte[] getSalt(String user) {
		byte[] salt;
		byte[] tabByteUser;

		try {
			tabByteUser = user.getBytes("UTF-8");
		} catch (UnsupportedEncodingException usee) {
			throw new RuntimeException(usee);
		}

		/*
		 * It seems like the salt must have exactly 8 bytes.
		 */
		salt = new byte[8];

		System.arraycopy(tabByteUser, 0, salt, 0, Math.min(8, tabByteUser.length));

		return salt;
	}

	/**
	 * @return List of {@link ResourcePatternRealmUser}.
	 */
	public List<ResourcePatternRealmUser> getListResourcePatternRealmUser() {
		return Collections.unmodifiableList(this.listResourcePatternRealmUser);
	}

	/**
	 * @return List of ResourcePatternRealmUser representing the realms and users for
	 *   which a password is defined. We reuse this type with only the realm and user
	 *   fields used.
	 */
	public List<ResourcePatternRealmUser> getListRealmUser() {
		Properties propertiesCredentials;
		Enumeration<Object> enumKeys;
		List<ResourcePatternRealmUser> listResourcePatternRealmUser;

		propertiesCredentials = this.readPropertiesCredentials();
		enumKeys = propertiesCredentials.keys();
		listResourcePatternRealmUser = new ArrayList<ResourcePatternRealmUser>();

		while (enumKeys.hasMoreElements()) {
			String key;

			key = (String)enumKeys.nextElement();

			if (key.endsWith(DefaultCredentialStorePluginImpl.PROPERTY_SUFFIX_PASSWORD)) {
				String[] tabKeyComponent;
				ResourcePatternRealmUser resourcePatternRealmUser;

				resourcePatternRealmUser = new ResourcePatternRealmUser();

				tabKeyComponent = key.split("\\.");

				resourcePatternRealmUser.realm = tabKeyComponent[0];
				resourcePatternRealmUser.user = tabKeyComponent[1];

				listResourcePatternRealmUser.add(resourcePatternRealmUser);
			}
		}

		return listResourcePatternRealmUser;
	}

	/**
	 * Implementation of {@link CredentialStorePlugin#isCredentialsExist}, but with an
	 * extra parameter allowing to disable user interaction.
	 * {@link #isCredentialsExist(String, String, CredentialStorePlugin.CredentialValidator)}
	 * delegates to this method with this last parameter set to false.
	 * <p>
	 * A credential manager tool would generally specify true for this last parameter.
	 *
	 * @param resource Resource.
	 * @param user User. Can be null.
	 * @param credentialValidator CredentialValidator. Can be null.
	 * @param indNoUserInteraction Indicates if user interaction is allowed.
	 * @return See {@link CredentialStorePlugin#isCredentialsExist}.
	 */
	public boolean isCredentialsExist(String resource, String user, CredentialValidator credentialValidator, boolean indNoUserInteraction) {
		ResourcePatternRealmUser resourcePatternRealmUser;
		Properties propertiesCredentials;
		boolean indUserInput;
		boolean indPasswordInput;
		UserInteractionCallbackPlugin userInteractionCallbackPlugin;
		String passwordEncrypted;
		String password;

		resourcePatternRealmUser = this.getRealmUserForResource(resource);

		if ((user != null) && (resourcePatternRealmUser.user != null) && !user.equals(resourcePatternRealmUser.user)) {
			throw new RuntimeExceptionUserError(MessageFormat.format(DefaultCredentialStorePluginImpl.resourceBundle.getString(DefaultCredentialStorePluginImpl.MSG_PATTERN_KEY_USER_NOT_SAME_AS_RESOURCE), user, resourcePatternRealmUser.user, resource));
		}

		propertiesCredentials = this.readPropertiesCredentials();

		if (user == null) {
			user = propertiesCredentials.getProperty(resourcePatternRealmUser.realm + DefaultCredentialStorePluginImpl.PROPERTY_SUFFIX_DEFAULT_USER);
		}

		userInteractionCallbackPlugin = ExecContextHolder.get().getExecContextPlugin(UserInteractionCallbackPlugin.class);

		indUserInput = (user == null);
		indPasswordInput = false;

		do {
			if (indUserInput) {
				if (indNoUserInteraction || userInteractionCallbackPlugin.isBatchMode()) {
					return false;
				} else {
					user = userInteractionCallbackPlugin.getInfoWithDefault(MessageFormat.format(DefaultCredentialStorePluginImpl.resourceBundle.getString(DefaultCredentialStorePluginImpl.MSG_PATTERN_KEY_INPUT_USER_FOR_RESOURCE), resource, resourcePatternRealmUser.realm, System.getProperty("user.name")), System.getProperty("user.name"));
				}
			}

			passwordEncrypted = propertiesCredentials.getProperty(resourcePatternRealmUser.realm + '.' + user + DefaultCredentialStorePluginImpl.PROPERTY_SUFFIX_PASSWORD);

			if (passwordEncrypted == null) {
				if (indNoUserInteraction || userInteractionCallbackPlugin.isBatchMode()) {
					return false;
				} else {
					password = userInteractionCallbackPlugin.getInfoPassword(MessageFormat.format(DefaultCredentialStorePluginImpl.resourceBundle.getString(DefaultCredentialStorePluginImpl.MSG_PATTERN_KEY_INPUT_PASSWORD_FOR_USER_RESOURCE), user, resource, resourcePatternRealmUser.realm));
					passwordEncrypted = this.encryptPassword(user, password);
					indPasswordInput = true;
				}
			} else {
				password = this.decryptPassword(user, passwordEncrypted);
			}

			if ((credentialValidator != null) && !credentialValidator.validateCredentials(resource, user, password)) {
				userInteractionCallbackPlugin.provideInfo(MessageFormat.format(DefaultCredentialStorePluginImpl.resourceBundle.getString(DefaultCredentialStorePluginImpl.MSG_PATTERN_KEY_USER_PASSWORD_INVALID), resource));
				continue;
			}

			if (indUserInput) {
				propertiesCredentials.setProperty(resourcePatternRealmUser.realm + DefaultCredentialStorePluginImpl.PROPERTY_SUFFIX_DEFAULT_USER, user);
			}

			if (indPasswordInput) {
				propertiesCredentials.setProperty(resourcePatternRealmUser.realm + '.' + user + DefaultCredentialStorePluginImpl.PROPERTY_SUFFIX_PASSWORD, passwordEncrypted);
			}

			if (indUserInput || indPasswordInput) {
				this.savePropertiesCredentials(propertiesCredentials);
			}

			return true;
		} while (true);
	}

	/**
	 * Implementation of {@link CredentialStorePlugin#getCredentials}, but with an
	 * extra parameter allowing to disable user interaction.
	 * {@link #getCredentials(String, String, CredentialStorePlugin.CredentialValidator)}
	 * delegates to this method with this last parameter set to false.
	 * <p>
	 * A credential manager tool would generally specify true for this last parameter.
	 *
	 * @param resource Resource.
	 * @param user User. Can be null.
	 * @param credentialValidator CredentialValidator. Can be null.
	 * @param indNoUserInteraction Indicates if user interaction is allowed.
	 * @return Credentials. Cannot be null.
	 */
	public Credentials getCredentials(String resource, String user, CredentialValidator credentialValidator, boolean indNoUserInteraction) {
		ResourcePatternRealmUser resourcePatternRealmUser;
		Properties propertiesCredentials;
		boolean indUserInput;
		boolean indPasswordInput;
		UserInteractionCallbackPlugin userInteractionCallbackPlugin;
		String passwordEncrypted;
		String password;
		CredentialStorePlugin.Credentials credentials;

		resourcePatternRealmUser = this.getRealmUserForResource(resource);

		if ((user != null) && (resourcePatternRealmUser.user != null) && !user.equals(resourcePatternRealmUser.user)) {
			throw new RuntimeExceptionUserError(MessageFormat.format(DefaultCredentialStorePluginImpl.resourceBundle.getString(DefaultCredentialStorePluginImpl.MSG_PATTERN_KEY_USER_NOT_SAME_AS_RESOURCE), user, resourcePatternRealmUser.user, resource));
		}

		propertiesCredentials = this.readPropertiesCredentials();

		if (user == null) {
			user = propertiesCredentials.getProperty(resourcePatternRealmUser.realm + DefaultCredentialStorePluginImpl.PROPERTY_SUFFIX_DEFAULT_USER);
		}

		userInteractionCallbackPlugin = ExecContextHolder.get().getExecContextPlugin(UserInteractionCallbackPlugin.class);

		if ((user == null) && (indNoUserInteraction || userInteractionCallbackPlugin.isBatchMode())) {
			throw new RuntimeExceptionUserError(MessageFormat.format(DefaultCredentialStorePluginImpl.resourceBundle.getString(DefaultCredentialStorePluginImpl.MSG_PATTERN_KEY_USER_NOT_SPECIFIED), resource, resourcePatternRealmUser.realm));
		}

		indUserInput = (user == null);
		indPasswordInput = false;

		do {
			if (indUserInput) {
				user = userInteractionCallbackPlugin.getInfoWithDefault(MessageFormat.format(DefaultCredentialStorePluginImpl.resourceBundle.getString(DefaultCredentialStorePluginImpl.MSG_PATTERN_KEY_INPUT_USER_FOR_RESOURCE), resource, resourcePatternRealmUser.realm, System.getProperty("user.name")), System.getProperty("user.name"));
			}

			passwordEncrypted = propertiesCredentials.getProperty(resourcePatternRealmUser.realm + '.' + user + DefaultCredentialStorePluginImpl.PROPERTY_SUFFIX_PASSWORD);

			if ((passwordEncrypted == null) && (indNoUserInteraction || userInteractionCallbackPlugin.isBatchMode())) {
				throw new RuntimeExceptionUserError(MessageFormat.format(DefaultCredentialStorePluginImpl.resourceBundle.getString(DefaultCredentialStorePluginImpl.MSG_PATTERN_KEY_PASSWORD_NOT_AVAILABLE), user, resource, resourcePatternRealmUser.realm));
			}

			if (passwordEncrypted == null) {
				password = userInteractionCallbackPlugin.getInfoPassword(MessageFormat.format(DefaultCredentialStorePluginImpl.resourceBundle.getString(DefaultCredentialStorePluginImpl.MSG_PATTERN_KEY_INPUT_PASSWORD_FOR_USER_RESOURCE), user, resource, resourcePatternRealmUser.realm));
				passwordEncrypted = this.encryptPassword(user, password);
				indPasswordInput = true;
			} else {
				password = this.decryptPassword(user, passwordEncrypted);
			}

			if ((credentialValidator != null) && !credentialValidator.validateCredentials(resource, user, password)) {
				userInteractionCallbackPlugin.provideInfo(MessageFormat.format(DefaultCredentialStorePluginImpl.resourceBundle.getString(DefaultCredentialStorePluginImpl.MSG_PATTERN_KEY_USER_PASSWORD_INVALID), resource));
				continue;
			}

			if (indUserInput) {
				propertiesCredentials.setProperty(resourcePatternRealmUser.realm + DefaultCredentialStorePluginImpl.PROPERTY_SUFFIX_DEFAULT_USER, user);
			}

			if (indPasswordInput) {
				propertiesCredentials.setProperty(resourcePatternRealmUser.realm + '.' + user + DefaultCredentialStorePluginImpl.PROPERTY_SUFFIX_PASSWORD, passwordEncrypted);
			}

			if (indUserInput || indPasswordInput) {
				this.savePropertiesCredentials(propertiesCredentials);
			}

			credentials = new CredentialStorePlugin.Credentials();

			credentials.resource = resource;
			credentials.user = user;
			credentials.password = password;
			credentials.indUserSpecificResource = (resourcePatternRealmUser.user != null);

			return credentials;
		} while (true);
	}

	/**
	 * Sets a password. The password is requested from the user.
	 * <p>
	 * If the user is not specified and the resource specifies the user, the password
	 * for that user is set.
	 * <p>
	 * If the resource does not specify the user, the password for the default user of
	 * the realm is set.
	 * <p>
	 * If no default user is set for the realm, an exception is thrown.
	 * <p>
	 * If a password is already set for the realm and the user, it is overwritten.
	 * <p>
	 * This method is to be used by a credential manager tool.
	 *
	 * @param resource Resource.
	 * @param user User. Can be null.
	 */
	public void setPassword(String resource, String user) {
		ResourcePatternRealmUser resourcePatternRealmUser;
		Properties propertiesCredentials;
		UserInteractionCallbackPlugin userInteractionCallbackPlugin;
		String passwordEncrypted;
		String password;

		resourcePatternRealmUser = this.getRealmUserForResource(resource);

		if ((user != null) && (resourcePatternRealmUser.user != null) && !user.equals(resourcePatternRealmUser.user)) {
			throw new RuntimeExceptionUserError(MessageFormat.format(DefaultCredentialStorePluginImpl.resourceBundle.getString(DefaultCredentialStorePluginImpl.MSG_PATTERN_KEY_USER_NOT_SAME_AS_RESOURCE), user, resourcePatternRealmUser.user, resource));
		}

		propertiesCredentials = this.readPropertiesCredentials();

		if (user == null) {
			user = propertiesCredentials.getProperty(resourcePatternRealmUser.realm + DefaultCredentialStorePluginImpl.PROPERTY_SUFFIX_DEFAULT_USER);
		}

		if (user == null) {
			throw new RuntimeExceptionUserError(MessageFormat.format(DefaultCredentialStorePluginImpl.resourceBundle.getString(DefaultCredentialStorePluginImpl.MSG_PATTERN_KEY_USER_NOT_SPECIFIED), resource, resourcePatternRealmUser.realm));
		}

		userInteractionCallbackPlugin = ExecContextHolder.get().getExecContextPlugin(UserInteractionCallbackPlugin.class);

		password = userInteractionCallbackPlugin.getInfoPassword(MessageFormat.format(DefaultCredentialStorePluginImpl.resourceBundle.getString(DefaultCredentialStorePluginImpl.MSG_PATTERN_KEY_INPUT_PASSWORD_FOR_USER_RESOURCE), user, resource, resourcePatternRealmUser.realm));
		passwordEncrypted = this.encryptPassword(user, password);
		propertiesCredentials.setProperty(resourcePatternRealmUser.realm + '.' + user + DefaultCredentialStorePluginImpl.PROPERTY_SUFFIX_PASSWORD, passwordEncrypted);
		this.savePropertiesCredentials(propertiesCredentials);
	}

	/**
	 * @return List of ResourcePatternRealmUser representing the realms and their
	 * default users. We reuse this type with only the realm and user fields used.
	 */
	public List<ResourcePatternRealmUser> getListDefaultRealmUser() {
		Properties propertiesCredentials;
		Enumeration<Object> enumKeys;
		List<ResourcePatternRealmUser> listResourcePatternRealmUser;

		propertiesCredentials = this.readPropertiesCredentials();
		enumKeys = propertiesCredentials.keys();
		listResourcePatternRealmUser = new ArrayList<ResourcePatternRealmUser>();

		while (enumKeys.hasMoreElements()) {
			String key;

			key = (String)enumKeys.nextElement();

			if (key.endsWith(DefaultCredentialStorePluginImpl.PROPERTY_SUFFIX_DEFAULT_USER)) {
				String[] tabKeyComponent;
				ResourcePatternRealmUser resourcePatternRealmUser;

				resourcePatternRealmUser = new ResourcePatternRealmUser();

				tabKeyComponent = key.split("\\.");

				resourcePatternRealmUser.realm = tabKeyComponent[0];
				resourcePatternRealmUser.user = propertiesCredentials.getProperty(key);

				listResourcePatternRealmUser.add(resourcePatternRealmUser);
			}
		}

		return listResourcePatternRealmUser;
	}

	/**
	 * Returns the default user for a resource.
	 * <p>
	 * If the resource specifies a user, this user is returned.
	 * <p>
	 * Otherwise, the default user specified for the realm corresponding to the
	 * resource is returned.
	 * <p>
	 * An exception is raised if there is no default user.
	 *
	 * @param resource Resource.
	 * @return See description.
	 */
	public String getDefaultUser(String resource) {
		ResourcePatternRealmUser resourcePatternRealmUser;
		String user;
		Properties propertiesCredentials;

		resourcePatternRealmUser = this.getRealmUserForResource(resource);

		user = resourcePatternRealmUser.user;

		if (user == null) {
			propertiesCredentials = this.readPropertiesCredentials();

			user = propertiesCredentials.getProperty(resourcePatternRealmUser.realm + DefaultCredentialStorePluginImpl.PROPERTY_SUFFIX_DEFAULT_USER);
		}

		if (user == null) {
			throw new RuntimeExceptionUserError(MessageFormat.format(DefaultCredentialStorePluginImpl.resourceBundle.getString(DefaultCredentialStorePluginImpl.MSG_PATTERN_KEY_NO_DEFAULT_USER_FOR_RESOURCE), resource, resourcePatternRealmUser.realm));
		}

		return user;
	}

	/**
	 * Sets the default user for the realm corresponding to a resource.
	 * <p>
	 * If the resource specifies a user, an exception is thrown.
	 *
	 * @param resource Resource.
	 * @param user User.
	 */
	public void setDefaultUser(String resource, String user) {
		ResourcePatternRealmUser resourcePatternRealmUser;
		Properties propertiesCredentials;

		resourcePatternRealmUser = this.getRealmUserForResource(resource);

		if (resourcePatternRealmUser.user != null) {
			throw new RuntimeExceptionUserError(MessageFormat.format(DefaultCredentialStorePluginImpl.resourceBundle.getString(DefaultCredentialStorePluginImpl.MSG_PATTERN_KEY_RESOURCE_SPECIFIES_USER), resource, resourcePatternRealmUser.user));
		}

		propertiesCredentials = this.readPropertiesCredentials();

		propertiesCredentials.setProperty(resourcePatternRealmUser.realm + DefaultCredentialStorePluginImpl.PROPERTY_SUFFIX_DEFAULT_USER, user);

		this.savePropertiesCredentials(propertiesCredentials);
	}

	/**
	 * Removes the default user for the realm corresponding to a resource.
	 * <p>
	 * If the resource specifies a user, an exception is thrown.
	 *
	 * @param resource Resource.
	 */
	public void removeDefaultUser(String resource) {
		ResourcePatternRealmUser resourcePatternRealmUser;
		Properties propertiesCredentials;

		resourcePatternRealmUser = this.getRealmUserForResource(resource);

		if (resourcePatternRealmUser.user != null) {
			throw new RuntimeExceptionUserError(MessageFormat.format(DefaultCredentialStorePluginImpl.resourceBundle.getString(DefaultCredentialStorePluginImpl.MSG_PATTERN_KEY_RESOURCE_SPECIFIES_USER), resource, resourcePatternRealmUser.user));
		}

		propertiesCredentials = this.readPropertiesCredentials();

		propertiesCredentials.remove(resourcePatternRealmUser.realm + DefaultCredentialStorePluginImpl.PROPERTY_SUFFIX_DEFAULT_USER);

		this.savePropertiesCredentials(propertiesCredentials);
	}
}