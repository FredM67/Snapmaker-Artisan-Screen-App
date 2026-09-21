package fabscreen.platform.base.obico;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Persists Obico settings while keeping the printer token encrypted by Android Keystore.
 *
 * <p>There is intentionally no plaintext fallback. If the Keystore entry is lost or
 * invalidated, {@link #load()} returns an unlinked configuration and the printer must be
 * linked again.</p>
 */
public final class ObicoSettingsStore {
    private static final String PREFERENCES_NAME = "fabscreen_obico";
    private static final String KEY_ALIAS = "fabscreen_obico_auth_token_v1";
    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;

    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_REMOTE_CONTROL = "remote_control";
    private static final String KEY_CAMERA_UPLOADS = "camera_uploads";
    private static final String KEY_ALLOW_INSECURE = "allow_insecure_server";
    private static final String KEY_TOKEN_CIPHERTEXT = "token_ciphertext_v1";
    private static final String KEY_TOKEN_IV = "token_iv_v1";

    private final SharedPreferences preferences;
    private volatile String lastSecurityError = "";

    public ObicoSettingsStore(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("Context is required");
        }
        preferences = context.getApplicationContext().getSharedPreferences(
                PREFERENCES_NAME,
                Context.MODE_PRIVATE);
    }

    public synchronized ObicoSettings load() {
        String token = "";
        String ciphertext = preferences.getString(KEY_TOKEN_CIPHERTEXT, "");
        String iv = preferences.getString(KEY_TOKEN_IV, "");
        if (ciphertext != null && !ciphertext.isEmpty() && iv != null && !iv.isEmpty()) {
            try {
                token = decrypt(ciphertext, iv);
                lastSecurityError = "";
            } catch (GeneralSecurityException | IllegalArgumentException exception) {
                // Never fall back to plaintext or expose cryptographic details to callers.
                token = "";
                lastSecurityError = "Stored Obico credentials could not be decrypted; link again";
            }
        }

        return new ObicoSettings(
                preferences.getBoolean(KEY_ENABLED, false),
                preferences.getString(KEY_SERVER_URL, ObicoSettings.DEFAULT_SERVER_URL),
                token,
                preferences.getBoolean(KEY_REMOTE_CONTROL, false),
                preferences.getBoolean(KEY_CAMERA_UPLOADS, false),
                preferences.getBoolean(KEY_ALLOW_INSECURE, false));
    }

    public synchronized void save(ObicoSettings settings) {
        if (settings == null) {
            throw new IllegalArgumentException("Settings are required");
        }
        String canonicalUrl = ObicoProtocol.canonicalServerUrl(
                settings.getServerUrl(),
                settings.isAllowInsecureServer());

        SharedPreferences.Editor editor = preferences.edit()
                .putBoolean(KEY_ENABLED, settings.isEnabled())
                .putString(KEY_SERVER_URL, canonicalUrl)
                .putBoolean(KEY_REMOTE_CONTROL, settings.isRemoteControlEnabled())
                .putBoolean(KEY_CAMERA_UPLOADS, settings.isCameraUploadsEnabled())
                .putBoolean(KEY_ALLOW_INSECURE, settings.isAllowInsecureServer());

        if (settings.getAuthToken().isEmpty()) {
            editor.remove(KEY_TOKEN_CIPHERTEXT).remove(KEY_TOKEN_IV);
        } else {
            try {
                EncryptedValue encryptedValue = encrypt(settings.getAuthToken());
                editor.putString(KEY_TOKEN_CIPHERTEXT, encryptedValue.ciphertext);
                editor.putString(KEY_TOKEN_IV, encryptedValue.iv);
                lastSecurityError = "";
            } catch (GeneralSecurityException exception) {
                lastSecurityError = "Obico credentials could not be secured";
                throw new IllegalStateException(lastSecurityError, exception);
            }
        }

        if (!editor.commit()) {
            throw new IllegalStateException("Obico settings could not be saved");
        }
    }

    public synchronized void clearToken() {
        if (!preferences.edit()
                .remove(KEY_TOKEN_CIPHERTEXT)
                .remove(KEY_TOKEN_IV)
                .putBoolean(KEY_ENABLED, false)
                .commit()) {
            throw new IllegalStateException("Obico credentials could not be cleared");
        }
        lastSecurityError = "";
    }

    public String getLastSecurityError() {
        return lastSecurityError;
    }

    private EncryptedValue encrypt(String token) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
        return new EncryptedValue(
                Base64.encodeToString(encrypted, Base64.NO_WRAP),
                Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
    }

    private String decrypt(String encodedCiphertext, String encodedIv)
            throws GeneralSecurityException {
        if (encodedCiphertext == null
                || encodedCiphertext.length() > 8 * 1024
                || encodedIv == null
                || encodedIv.length() > 64) {
            throw new GeneralSecurityException("Invalid encrypted credential");
        }
        byte[] ciphertext = Base64.decode(encodedCiphertext, Base64.NO_WRAP);
        byte[] iv = Base64.decode(encodedIv, Base64.NO_WRAP);
        if (iv.length < 12 || iv.length > 16 || ciphertext.length < 16) {
            throw new GeneralSecurityException("Invalid encrypted credential");
        }
        Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, getExistingKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] plaintext = cipher.doFinal(ciphertext);
        if (plaintext.length == 0 || plaintext.length > 4096) {
            throw new GeneralSecurityException("Invalid decrypted credential");
        }
        return new String(plaintext, StandardCharsets.UTF_8).trim();
    }

    private SecretKey getOrCreateKey() throws GeneralSecurityException {
        KeyStore keyStore = loadKeyStore();
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        }

        KeyGenerator keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore");
        keyGenerator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return keyGenerator.generateKey();
    }

    private SecretKey getExistingKey() throws GeneralSecurityException {
        KeyStore keyStore = loadKeyStore();
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            throw new GeneralSecurityException("Credential key is unavailable");
        }
        SecretKey key = (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        if (key == null) {
            throw new GeneralSecurityException("Credential key is unavailable");
        }
        return key;
    }

    private static KeyStore loadKeyStore() throws GeneralSecurityException {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        try {
            keyStore.load(null);
        } catch (java.io.IOException exception) {
            throw new GeneralSecurityException("Android Keystore could not be loaded", exception);
        } catch (java.security.cert.CertificateException exception) {
            throw new GeneralSecurityException("Android Keystore could not be loaded", exception);
        }
        return keyStore;
    }

    private static final class EncryptedValue {
        final String ciphertext;
        final String iv;

        EncryptedValue(String ciphertext, String iv) {
            this.ciphertext = ciphertext;
            this.iv = iv;
        }
    }
}
