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

package org.azyva.dragom.security;

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
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

import org.azyva.dragom.execcontext.plugin.impl.DefaultCredentialStorePluginImpl;

/**
 * This class allows managing credentials securely. It is used by
 * {@link DefaultCredentialStorePluginImpl}. Although both classes are similar,
 * the low-level logic for managing the credentials has been factored out in this
 * class for increased flexibility. Note however that contrary to
 * DefaultCredentialStorePluginImpl, this class does not interact with the user.
 * <p>
 * Credentials are managed in a Properties file whose containing 2 types of
 * entries:
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
 * <li>A random password stored in a master password file</li>
 * <p>
 * If the master password file does not exist, it is created with newly generated
 * random password.
 * <p>
 * This is not the highest security, but is considered sufficient in the current
 * context. Properly securing passwords that must be stored locally is hard. No
 * matter the logic used, one always ends up having to store some decryption key
 * somewhere. The fact that the master password file can be stored in the user
 * home directory, not accessible for reading to others makes the solution
 * sufficiently secure.
 * <p>
 * This class uses a sequence of mappings between resource Pattern's and
 * corresponding realms and users. These mapping are specified when the class is
 * initialized.
 * <p>
 * See
 * <a href="https://docs.oracle.com/javase/7/docs/api/java/util/regex/package-summary.html" target="_blank">
 * java.util.regex</a> for information about regular expressions in Java.
 * <p>
 * A resource is typically the URL of a service, such as
 * https://john.smith@acme.com/my-git-repository.git.
 * <p>
 * When a method takes a resource, it maps this resource to a realm using these
 * mappings. The first mapping whose resource Pattern matches the resource is
 * used. A mapping must correspond to a resource. A catch-all mapping can be used
 * if necessary.
 * <p>
 * Passwords are associated with realms obtained from the mappings, not resources
 * directory.
 * <p>
 * A resource can also include a user. Mappings can specify a captured group to
 * extract it.
 * <p>
 * Passwords are associated with realms, not resources directly. Therefore no
 * method takes a realm as an argument. However, in some cases, such as for a tool
 * that allows the user to manage the credentials, it can be convenient to let the
 * user explicitly specify realms. This can be achieved generically by introducing
 * a mapping that, for example, maps "REALM:(.*)" to the realm $1 (the first
 * captured group).
 *
 * @author David Raymond
 */
public class CredentialStore {
	/**
	 * Default credential file. When no credential file is specified during
	 * initialization this file is used in the user home directory.
	 * <p>
	 * The caller can use this constant to construct a credential file Path which
	 * uses that same file name, but a different directory.
	 */
	public static final String DEFAULT_CREDENTIAL_FILE = "dragom-credentials.properties";

	/**
	 * Default master password file. When no master password file is specified during
	 * initialization this file is used in the user home directory.
	 * <p>
	 * The caller can use this constant to construct a master password file Path which
	 * uses that same file name, but a different directory.
	 */
	public static final String DEFAULT_MASTER_KEY_FILE = "dragom-master-key";

	/**
	 * Hardcoded password generated using
	 * https://lastpass.com/generatepassword.php. This is one part of the key material
	 * used for encrypting and decrypting the passwords.
	 * <p>
	 * A hardcode password such as this is not secure. But this in combination
	 */
	private static final String HARDCODED_PASSWORD = "S4ag$*v6G1kMGWvK";

	/**
	 * Property suffix for the default user. The prefix is the realm.
	 */
	public static final String PROPERTY_SUFFIX_DEFAULT_USER = ".DefaultUser";

	/**
	 * Property suffix for a password. The prefix is &lt;realm&gt;.&lt;user&gt;.
	 */
	public static final String PROPERTY_SUFFIX_PASSWORD = ".Password.";

	/**
	 * Because realms are used as property keys, the characters used must be
	 * restricted so that parsing the keys is accurate. In particular, since "." is
	 * used to separate elements of the keys (realm and user), the realms should not
	 * contain ".".
	 * <p>
	 * It is OK for the user to contain "." however since if only the user can contain
	 * ".", it is possible to accurately parse keys.
	 * <p>
	 * This regular expression is used to match non-allowed characters in realms so
	 * that they can be converted to "_".
	 */
	private static final Pattern patternCleanRealm = Pattern.compile("[^A-Za-z0-9_\\-]");

	/**
	 * SecretKey for encrypting the passwords.
	 */
	private SecretKey secretKeyPasswordEncryption;

	/**
	 * Mapping from a resource Pattern to a realm and user.
	 */
	public static class ResourcePatternRealmUser {
		/**
		 * Pattern to match a resource. Can contain captured groups which can be
		 * referenced by {@link #realm} and {@link #user}.
		 */
		public Pattern patternResource;

		/**
		 * Realm. Can contain references to captured groups defined in
		 * {@link #patternResource}.
		 */
		public String realm;

		/**
		 * User. Can contain references to captured groups defined in
		 * {@link #patternResource}. Can be null.
		 */
		public String user;
	}

	/**
	 * Information about a resource.
	 */
	public static class ResourceInfo {
		/**
		 * The resource.
		 */
		public String resource;

		/**
		 * Realm the resource corresponds to. Cannot be null. At worst there must be a
		 * mapping to a constant realm, or a realm that is the resource itself.
		 */
		public String realm;

		/**
		 * User specified within the resource. null if none.
		 */
		public String user;
	}

	/**
	 * Realm and user tupple.
	 */
	public static class RealmUser {
		/**
		 * Realm.
		 */
		public String realm;

		/**
		 * User.
		 */
		public String user;
	}

	/**
	 * Path of the credential file.
	 */
	private Path pathCredentialFile;

	/**
	 * Path of the mater password file.
	 */
	private Path pathMasterKeyFile;

	/**
	 * List of ResourcePatternRealmUser mappings.
	 */
	private List<ResourcePatternRealmUser> listResourcePatternRealmUser;

	/**
	 * Properties containing the credentials. Read from the credential file.
	 * <p>
	 * null indicates the credentials are not loaded and causes
	 * {@link #readPropertiesCredentials} to load them.
	 * <p>
	 * {@link #resetCredentialFile} sets it to null so that it is reloaded when
	 * next required.
	 */
	private Properties propertiesCredentials;

	/**
	 * Constructor.
	 * <p>
	 * If pathMasterPassworedFile is null, the master password file is
	 * "dragom-master-password" in the user home directory.
	 * <p>
	 * If pathCredentialFile is null, the credential file is
	 * "dragom-credentials.properties" in the user home directory.
	 * <p>
	 * After initialization, listResourcePatternRealmUser is considered as belonging
	 * to this class and should not be modified by the caller. This ciass does not
	 * make a copy for efficiency reasons.
	 *
	 * @param pathMasterKeyFile Path of the master password file. Can be null.
	 * @param pathCredentialFile Path of the credential file. Can be null.
	 * @param listResourcePatternRealmUser List of {@link ResourcePatternRealmUser}.
	 */
	public CredentialStore(Path pathCredentialFile, Path pathMasterKeyFile, List<ResourcePatternRealmUser> listResourcePatternRealmUser) {
		byte[] arrayByteMasterKey;

		if (pathMasterKeyFile == null) {
			pathMasterKeyFile = Paths.get(System.getProperty("user.home")).resolve(CredentialStore.DEFAULT_MASTER_KEY_FILE);
		}

		this.pathMasterKeyFile = pathMasterKeyFile;

		if (pathCredentialFile == null) {
			pathCredentialFile = Paths.get(System.getProperty("user.home")).resolve(CredentialStore.DEFAULT_CREDENTIAL_FILE);
		}

		this.pathCredentialFile = pathCredentialFile;

		this.listResourcePatternRealmUser = listResourcePatternRealmUser;

		arrayByteMasterKey = new byte[16];

		if (!this.pathMasterKeyFile.toFile().isFile()) {
			SecureRandom secureRandom;
			OutputStream outputStreamMasterKeyFile;

			secureRandom = new SecureRandom();

			for (int i = 0; i < 16; i++) {
				arrayByteMasterKey[i] = (byte)(33 + secureRandom.nextInt(94));
			}

			try {
				this.pathMasterKeyFile.getParent().toFile().mkdirs();
				outputStreamMasterKeyFile = new FileOutputStream(pathMasterKeyFile.toFile());
				outputStreamMasterKeyFile.write(arrayByteMasterKey);
				outputStreamMasterKeyFile.close();
			} catch (IOException ioe) {
				throw new RuntimeException(ioe);
			}
		} else {
			InputStream inputStreamMasterKeyFile;

			try {
				inputStreamMasterKeyFile = new FileInputStream(this.pathMasterKeyFile.toFile());
				inputStreamMasterKeyFile.read(arrayByteMasterKey);
				inputStreamMasterKeyFile.close();
			} catch (IOException ioe) {
				throw new RuntimeException(ioe);
			}
		}

		try {
			this.secretKeyPasswordEncryption = SecretKeyFactory.getInstance("PBEWithMD5AndDES").generateSecret(
					new PBEKeySpec((new String(arrayByteMasterKey) + System.getProperty("user.name") + CredentialStore.HARDCODED_PASSWORD).toCharArray()));
		} catch (InvalidKeySpecException | NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Reads the credential Properties from the credential file.
	 */
	private void readPropertiesCredentials() {
		if (this.propertiesCredentials == null) {
			this.propertiesCredentials = new Properties();

			try {
				this.propertiesCredentials.load(new FileInputStream(this.pathCredentialFile.toFile()));
			} catch (FileNotFoundException fnfe) {
			} catch (IOException ioe) {
				throw new RuntimeException(ioe);
			}
		}
	}

	/**
	 * Saves the properties for the credentials to the credential file.
	 */
	private void savePropertiesCredentials() {
		try {
			this.propertiesCredentials.store(new FileOutputStream(this.pathCredentialFile.toFile()), null);
		} catch (IOException ioe) {
			throw new RuntimeException(ioe);
		}
	}


	/**
	 * Resets the credential file so that it is reloaded when next required.
	 */
	public void resetCredentialFile() {
		this.propertiesCredentials = null;
	}

	/**
	 * Returns information about a resource.
	 *
	 * @param resource Resource.
	 * @return ResourceInfo. null if no resource Pattern to realm and user mapping is
	 *   found.
	 */
	public ResourceInfo getResourceInfo(String resource) {
		ResourceInfo resourceInfo;

		resourceInfo = new ResourceInfo();

		resourceInfo.resource = resource;

		for (ResourcePatternRealmUser resourcePatternRealmUser: this.listResourcePatternRealmUser) {
			Matcher matcher;

			matcher = resourcePatternRealmUser.patternResource.matcher(resource);

			if (matcher.matches()) {
				Matcher matcherCleanRealm;
				StringBuffer stringBuffer;

				stringBuffer = new StringBuffer();

				matcher.appendReplacement(stringBuffer, resourcePatternRealmUser.realm);
				resourceInfo.realm = stringBuffer.toString();

				if ((resourceInfo.realm == null) || (resourceInfo.realm.length() == 0)) {
					// If a resource matches, the mapping must extract a realm. If not, this is
					// considered a configuration error in the mappings.
					throw new RuntimeException("No realm extracted from resource " + resource + '.');
				}

				matcherCleanRealm = CredentialStore.patternCleanRealm.matcher(resourceInfo.realm);

				resourceInfo.realm = matcherCleanRealm.replaceAll("_");

				if (resourcePatternRealmUser.user != null) {
					// We need to reexecute the match since the call to appendReplacement above has
					// moved the append position in the input sequence. Unfortunately, there does not
					// seem to be a method to simply evaluate an expression containing captured group
					// references, which is what we would ideally need.
					matcher.reset();
					matcher.matches();

					stringBuffer.setLength(0);

					matcher.appendReplacement(stringBuffer, resourcePatternRealmUser.user);
					resourceInfo.user = stringBuffer.toString();

					if ((resourceInfo.user != null) && (resourceInfo.user.length() == 0)) {
						// The user may not be specified in the resource and in that case the captured
						// group by be empty. This is reported as the empty string and not null. But in
						// the code, we represent a non-specified user as null.
						resourceInfo.user = null;
					}
				}

				return resourceInfo;
			}
		}

		return null;
	}

	/**
	 * Returns a password from the credential store.
	 * <p>
	 * If no resourcePattern mapping to a realm and user is found, null is returned.
	 * <p>
	 * If user is null, a user must be inferred. If it is specified within the
	 * resource, this user is used. Otherwise, if a default user is defined in the
	 * credentials for the realm corresponding to the resource, this user is used.
	 * Otherwise, null is returned.
	 * <p>
	 * If user is not null and a user is specified within the resource, they must
	 * match, otherwise null is returned.
	 * <p>
	 * If null is returned, {@link #getResourceInfo} can be called to know more about
	 * the resource.
	 *
	 * @param resource Resource.
	 * @param user User. Can be null.
	 * @return Password. null if requested credentials are not defined in the
	 *   store.
	 */
	public String getPassword(String resource, String user) {
		ResourceInfo resourceInfo;
		String passwordEncrypted;

		resourceInfo = this.getResourceInfo(resource);

		if (resourceInfo == null) {
			return null;
		}

		if ((user != null) && (resourceInfo.user != null) && !user.equals(resourceInfo.user)) {
			return null;
		}

		if (user == null) {
			user = resourceInfo.user;
		}

		this.readPropertiesCredentials();

		if (user == null) {
			user = this.propertiesCredentials.getProperty(resourceInfo.realm + CredentialStore.PROPERTY_SUFFIX_DEFAULT_USER);
		}

		if (user == null) {
			return null;
		}

		passwordEncrypted = this.propertiesCredentials.getProperty(resourceInfo.realm + '.' + user + CredentialStore.PROPERTY_SUFFIX_PASSWORD);

		if (passwordEncrypted == null) {
			return null;
		}

		return this.decryptPassword(passwordEncrypted);
	}

	/**
	 * Sets a password in the credential store.
	 * <p>
	 * If no resourcePattern mapping to a realm and user is found, false is returned.
	 * <p>
	 * If user is null, a user must be inferred. If it is specified within the
	 * resource, this user is used. Otherwise, if a default user is defined in the
	 * credentials for the realm corresponding to the resource, this user is used.
	 * Otherwise, false is returned.
	 * <p>
	 * If user is not null and a user is specified within the resource, they must
	 * match, otherwise false is returned.
	 * <p>
	 * If false is returned, {@link #getResourceInfo} can be called to know more about
	 * the resource.
	 * <p>
	 * If a password is already set for the realm and the user, it is overwritten.
	 *
	 * @param resource Resource.
	 * @param user User. Can be null.
	 * @param password Password.
	 * @return Indicates if the password was successfully set.
	 */
	public boolean setPassword(String resource, String user, String password) {
		ResourceInfo resourceInfo;
		String passwordEncrypted;

		resourceInfo = this.getResourceInfo(resource);

		if (resourceInfo == null) {
			return false;
		}

		if ((user != null) && (resourceInfo.user != null) && !user.equals(resourceInfo.user)) {
			return false;
		}

		if (user == null) {
			user = resourceInfo.user;
		}

		this.readPropertiesCredentials();

		if (user == null) {
			user = this.propertiesCredentials.getProperty(resourceInfo.realm + CredentialStore.PROPERTY_SUFFIX_DEFAULT_USER);
		}

		if (user == null) {
			return false;
		}

		passwordEncrypted = this.encryptPassword(password);

		this.propertiesCredentials.setProperty(resourceInfo.realm + '.' + user + CredentialStore.PROPERTY_SUFFIX_PASSWORD, passwordEncrypted);

		this.savePropertiesCredentials();

		return true;
	}

	/**
	 * Deletes a password from the credential store.
	 * <p>
	 * If no resourcePattern mapping to a realm and user is found, false is returned.
	 * <p>
	 * If user is null, a user must be inferred. If it is specified within the
	 * resource, this user is used. Otherwise, if a default user is defined in the
	 * credentials for the realm corresponding to the resource, this user is used.
	 * Otherwise, false is returned.
	 * <p>
	 * If user is not null and a user is specified within the resource, they must
	 * match, otherwise false is returned.
	 * <p>
	 * If false is returned, {@link #getResourceInfo} can be called to know more about
	 * the resource.
	 * <p>
	 *
	 * @param resource Resource.
	 * @param user User. Can be null.
	 * @return Indicates if the password was successfully deleted.
	 */
	public boolean deletePassword(String resource, String user) {
		ResourceInfo resourceInfo;

		resourceInfo = this.getResourceInfo(resource);

		if (resourceInfo == null) {
			return false;
		}

		if ((user != null) && (resourceInfo.user != null) && !user.equals(resourceInfo.user)) {
			return false;
		}

		if (user == null) {
			user = resourceInfo.user;
		}

		this.readPropertiesCredentials();

		if (user == null) {
			user = this.propertiesCredentials.getProperty(resourceInfo.realm + CredentialStore.PROPERTY_SUFFIX_DEFAULT_USER);
		}

		if (user == null) {
			return false;
		}

		this.propertiesCredentials.remove(resourceInfo.realm + '.' + user + CredentialStore.PROPERTY_SUFFIX_PASSWORD);

		this.savePropertiesCredentials();

		return true;
	}

	/**
	 * Decrypts an encrypted password.
	 * <p>
	 * The encrypted password is the base64 encoding of the actual encypted password
	 * followed by the random salt used during encryption.
	 *
	 * @param passwordEncrypted Encrypted passsword.
	 * @return Plain text password.
	 */
	private String decryptPassword(String passwordEncrypted) {
		Cipher cipherPbe;
		byte[] arrayBytePasswordEncryptedAndSalt;
		byte[] arrayBytePasswordEncrypted;
		byte[] arrayByteSalt;

		try {
			cipherPbe = Cipher.getInstance("PBEWithMD5AndDES");

			/*
			 * In order to have readable characters in the credential file, we use Base 64
			 * encoding. Many dedicated libraries exist for performing Base 64 encoding, but
			 * DatatypeCOnverter in JAXB does the job.
			 */

			arrayBytePasswordEncryptedAndSalt = DatatypeConverter.parseBase64Binary(passwordEncrypted);
			arrayBytePasswordEncrypted = new byte[arrayBytePasswordEncryptedAndSalt.length - 8];
			System.arraycopy(arrayBytePasswordEncryptedAndSalt, 0, arrayBytePasswordEncrypted, 0, arrayBytePasswordEncrypted.length);
			arrayByteSalt = new byte[8];
			System.arraycopy(arrayBytePasswordEncryptedAndSalt, arrayBytePasswordEncrypted.length, arrayByteSalt, 0, 8);

			cipherPbe.init(Cipher.DECRYPT_MODE, this.secretKeyPasswordEncryption, new PBEParameterSpec(arrayByteSalt, 8));

			return new String(cipherPbe.doFinal(arrayBytePasswordEncrypted), "UTF-8");
		} catch (NoSuchPaddingException|NoSuchAlgorithmException|InvalidKeyException|InvalidAlgorithmParameterException|BadPaddingException|IllegalBlockSizeException|UnsupportedEncodingException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Encrypts a password.
	 * <p>
	 * The encrypted password is the base64 encoding of the actual encypted password
	 * followed by the random salt used for encryption.

	 * @param password Plain text password.
	 * @return Encrypted password.
	 */
	private String encryptPassword(String password) {
		Cipher cipherPbe;
		SecureRandom secureRandom;
		byte[] arrayByteSalt;
		byte[] arrayBytePasswordEncryptedAndSalt;
		byte[] arrayBytePasswordEncrypted;

		try {
			cipherPbe = Cipher.getInstance("PBEWithMD5AndDES");
			cipherPbe.init(Cipher.ENCRYPT_MODE, this.secretKeyPasswordEncryption);
			secureRandom = new SecureRandom();
			arrayByteSalt = new byte[8];

			/*
			 * It seems like the salt must have exactly 8 bytes.
			 *
			 * A salt is not secret and is generally used to prevent rainbow attacks. In the
			 * present context, it is probably overkill, but the encryption mechanism used
			 * here does require one. Might as well use it to maximize security.
			 */
			for (int i = 0; i < 8; i++) {
				arrayByteSalt[i] = (byte)(secureRandom.nextInt(256));
			}

			/*
			 * In order to have readable characters in the credential file, we use Base 64
			 * encoding. Many dedicated libraries exist for performing Base 64 encoding, but
			 * DatatypeCOnverter in JAXB does the job.
			 */
			cipherPbe.init(Cipher.ENCRYPT_MODE, this.secretKeyPasswordEncryption, new PBEParameterSpec(arrayByteSalt, 8));
			arrayBytePasswordEncrypted = cipherPbe.doFinal(password.getBytes("UTF-8"));
			arrayBytePasswordEncryptedAndSalt = new byte[arrayBytePasswordEncrypted.length + 8];
			System.arraycopy(arrayBytePasswordEncrypted, 0, arrayBytePasswordEncryptedAndSalt, 0, arrayBytePasswordEncrypted.length);
			System.arraycopy(arrayByteSalt, 0, arrayBytePasswordEncryptedAndSalt, arrayBytePasswordEncrypted.length, 8);
			return DatatypeConverter.printBase64Binary(arrayBytePasswordEncryptedAndSalt);
		} catch (NoSuchPaddingException|NoSuchAlgorithmException|InvalidKeyException|InvalidAlgorithmParameterException|BadPaddingException|IllegalBlockSizeException|UnsupportedEncodingException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * @return List of {@link ResourcePatternRealmUser}.
	 */
	public List<ResourcePatternRealmUser> getListResourcePatternRealmUser() {
		return Collections.unmodifiableList(this.listResourcePatternRealmUser);
	}

	/**
	 * @return List of RealmUser representing the realms and users for which a
	 *   password is defined.
	 */
	public List<RealmUser> getListRealmUser() {
		Enumeration<Object> enumKeys;
		List<RealmUser> listRealmUser;

		this.readPropertiesCredentials();
		enumKeys = this.propertiesCredentials.keys();
		listRealmUser = new ArrayList<RealmUser>();

		while (enumKeys.hasMoreElements()) {
			String key;

			key = (String)enumKeys.nextElement();

			if (key.endsWith(CredentialStore.PROPERTY_SUFFIX_PASSWORD)) {
				int indexDot;
				RealmUser realmUser;

				realmUser = new RealmUser();

				indexDot = key.lastIndexOf('.', key.length() - CredentialStore.PROPERTY_SUFFIX_PASSWORD.length() - 1);

				realmUser.realm = key.substring(0, indexDot);
				realmUser.user = key.substring(indexDot + 1, key.length() - CredentialStore.PROPERTY_SUFFIX_PASSWORD.length());

				listRealmUser.add(realmUser);
			}
		}

		return listRealmUser;
	}


	/**
	 * @return List of RealmUser representing the realms and their default users.
	 */
	public List<RealmUser> getListRealmUserDefault() {
		Enumeration<Object> enumKeys;
		List<RealmUser> listRealmUser;

		this.readPropertiesCredentials();
		enumKeys = this.propertiesCredentials.keys();
		listRealmUser = new ArrayList<RealmUser>();

		while (enumKeys.hasMoreElements()) {
			String key;

			key = (String)enumKeys.nextElement();

			if (key.endsWith(CredentialStore.PROPERTY_SUFFIX_DEFAULT_USER)) {
				RealmUser realmUser;

				realmUser = new RealmUser();

				realmUser.realm = key.substring(0, key.length() - CredentialStore.PROPERTY_SUFFIX_DEFAULT_USER.length());
				realmUser.user = this.propertiesCredentials.getProperty(key);

				listRealmUser.add(realmUser);
			}
		}

		return listRealmUser;
	}

	/**
	 * Returns the default user for a resource.
	 * <p>
	 * If no resourcePattern mapping to a realm and user is found, null is returned.
	 * <p>
	 * If the resource specifies a user, null is returned.
	 * <p>
	 * Otherwise, the default user specified for the realm corresponding to the
	 * resource is returned.
	 * <p>
	 * If no default user is defined for the realm, null is returned.
	 *
	 * @param resource Resource.
	 * @return See description.
	 */
	public String getDefaultUser(String resource) {
		ResourceInfo resourceInfo;

		resourceInfo = this.getResourceInfo(resource);

		if (resourceInfo == null) {
			return null;
		}

		if (resourceInfo.user != null) {
			return null;
		}

		this.readPropertiesCredentials();

		return this.propertiesCredentials.getProperty(resourceInfo.realm + CredentialStore.PROPERTY_SUFFIX_DEFAULT_USER);
	}

	/**
	 * Sets the default user for the realm corresponding to a resource.
	 * <p>
	 * If no resourcePattern mapping to a realm and user is found, false is returned.
	 * <p>
	 * If the resource specifies a user, false is returned.
	 * <p>
	 * If a default user is already defined for the realm corresponding to the
	 * resource, it is overwritten.
	 *
	 * @param resource Resource.
	 * @param user User.
	 * @return Indicates if the default user was successfully set.
	 */
	public boolean setDefaultUser(String resource, String user) {
		ResourceInfo resourceInfo;

		resourceInfo = this.getResourceInfo(resource);

		if (resourceInfo == null) {
			return false;
		}

		if (resourceInfo.user != null) {
			return false;
		}

		this.readPropertiesCredentials();

		this.propertiesCredentials.setProperty(resourceInfo.realm + CredentialStore.PROPERTY_SUFFIX_DEFAULT_USER, user);

		this.savePropertiesCredentials();

		return true;
	}

	/**
	 * Removes the default user for the realm corresponding to a resource.
	 * <p>
	 * If no resourcePattern mapping to a realm and user is found, false is returned.
	 * <p>
	 * If the resource specifies a user, false is returned.
	 *
	 * @param resource Resource.
	 * @return Indicates if the default user was successfully deleted.
	 */
	public boolean deleteDefaultUser(String resource) {
		ResourceInfo resourceInfo;

		resourceInfo = this.getResourceInfo(resource);

		if (resourceInfo == null) {
			return false;
		}

		if (resourceInfo.user != null) {
			return false;
		}

		this.readPropertiesCredentials();

		this.propertiesCredentials.remove(resourceInfo.realm + CredentialStore.PROPERTY_SUFFIX_DEFAULT_USER);

		this.savePropertiesCredentials();

		return true;
	}
}
