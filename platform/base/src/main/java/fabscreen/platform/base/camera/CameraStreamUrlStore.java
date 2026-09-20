package fabscreen.platform.base.camera;

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

/** Keystore-backed, source-specific storage for camera URLs, which may include credentials. */
final class CameraStreamUrlStore {
    private static final String ALIAS = "fabscreen_camera_stream_url_v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String SOURCE_ID = "source_id";
    private static final String LEGACY_URL = "stream_url";
    private static final String CIPHERTEXT = "stream_url_ciphertext_v1";
    private static final String IV = "stream_url_iv_v1";
    private final SharedPreferences preferences;

    CameraStreamUrlStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(
                "artisan_uvc_camera", Context.MODE_PRIVATE);
    }

    synchronized String load(String sourceId) {
        if (!isNetworkSource(sourceId)) return "";
        String ciphertext = preferences.getString(ciphertextKey(sourceId), "");
        String iv = preferences.getString(ivKey(sourceId), "");
        if ((ciphertext != null && !ciphertext.isEmpty())
                || (iv != null && !iv.isEmpty())) {
            try {
                return decrypt(ciphertext, iv);
            } catch (GeneralSecurityException | IllegalArgumentException ignored) {
                // A lost/invalidated key must not trigger a plaintext fallback.
                return "";
            }
        }

        // The former single URL belonged to whichever network source was selected
        // when it was saved. Migrate it without ever copying credentials to the UI.
        String formerSource = preferences.getString(SOURCE_ID, "");
        if (!sourceId.equals(formerSource)) {
            // An unassigned legacy plaintext URL must not linger after a USB switch.
            if (!isNetworkSource(formerSource) && preferences.contains(LEGACY_URL)) {
                preferences.edit().remove(LEGACY_URL).commit();
            }
            return "";
        }
        String oldCiphertext = preferences.getString(CIPHERTEXT, "");
        String oldIv = preferences.getString(IV, "");
        if ((oldCiphertext != null && !oldCiphertext.isEmpty())
                || (oldIv != null && !oldIv.isEmpty())) {
            try {
                String url = decrypt(oldCiphertext, oldIv);
                try {
                    save(sourceId, url);
                } catch (IllegalStateException ignored) {
                    // Do not claim the URL was saved for later source switching.
                    // The old encrypted value remains for the next migration attempt.
                    return "";
                }
                return url;
            } catch (GeneralSecurityException | IllegalArgumentException ignored) {
                return "";
            }
        }
        String legacy = preferences.getString(LEGACY_URL, "");
        if (legacy == null || legacy.isEmpty()) return "";
        try {
            save(sourceId, legacy);
            return legacy;
        } catch (IllegalStateException ignored) {
            // Avoid retaining a legacy plaintext camera credential indefinitely.
            preferences.edit().remove(LEGACY_URL).commit();
            return "";
        }
    }

    synchronized void save(String sourceId, String url) {
        if (!isNetworkSource(sourceId)) throw new IllegalArgumentException("Unsupported camera source");
        SharedPreferences.Editor editor = preferences.edit()
                .remove(LEGACY_URL).remove(CIPHERTEXT).remove(IV);
        if (url == null || url.isEmpty()) {
            editor.remove(ciphertextKey(sourceId)).remove(ivKey(sourceId));
        } else {
            try {
                Cipher cipher = Cipher.getInstance(TRANSFORMATION);
                cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
                byte[] encrypted = cipher.doFinal(url.getBytes(StandardCharsets.UTF_8));
                editor.putString(ciphertextKey(sourceId),
                        Base64.encodeToString(encrypted, Base64.NO_WRAP));
                editor.putString(ivKey(sourceId), Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
            } catch (GeneralSecurityException failure) {
                // Never include exception details: providers sometimes echo input data.
                throw new IllegalStateException("Camera URL could not be secured");
            }
        }
        if (!editor.commit()) throw new IllegalStateException("Camera URL could not be saved");
    }

    private static boolean isNetworkSource(String sourceId) {
        return UvcCameraManager.MJPEG_SOURCE_ID.equals(sourceId)
                || UvcCameraManager.RTSP_SOURCE_ID.equals(sourceId);
    }

    private static String ciphertextKey(String sourceId) {
        return "stream_url_" + sourceId + "_ciphertext_v2";
    }

    private static String ivKey(String sourceId) {
        return "stream_url_" + sourceId + "_iv_v2";
    }

    private String decrypt(String encodedCiphertext, String encodedIv)
            throws GeneralSecurityException {
        if (encodedCiphertext == null || encodedIv == null
                || encodedCiphertext.isEmpty() || encodedIv.isEmpty()
                || encodedCiphertext.length() > 8192 || encodedIv.length() > 64) {
            throw new GeneralSecurityException("Invalid encrypted camera URL");
        }
        byte[] ciphertext = Base64.decode(encodedCiphertext, Base64.NO_WRAP);
        byte[] iv = Base64.decode(encodedIv, Base64.NO_WRAP);
        if (iv.length < 12 || iv.length > 16 || ciphertext.length < 16) {
            throw new GeneralSecurityException("Invalid encrypted camera URL");
        }
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, existingKey(), new GCMParameterSpec(128, iv));
        byte[] plain = cipher.doFinal(ciphertext);
        if (plain.length == 0 || plain.length > 2048) {
            throw new GeneralSecurityException("Invalid encrypted camera URL");
        }
        return new String(plain, StandardCharsets.UTF_8);
    }

    private static SecretKey getOrCreateKey() throws GeneralSecurityException {
        KeyStore store = keyStore();
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private static SecretKey existingKey() throws GeneralSecurityException {
        KeyStore store = keyStore();
        if (!store.containsAlias(ALIAS)) throw new GeneralSecurityException("Camera key unavailable");
        SecretKey key = (SecretKey) store.getKey(ALIAS, null);
        if (key == null) throw new GeneralSecurityException("Camera key unavailable");
        return key;
    }

    private static KeyStore keyStore() throws GeneralSecurityException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        try {
            store.load(null);
        } catch (java.io.IOException | java.security.cert.CertificateException failure) {
            throw new GeneralSecurityException("Camera keystore unavailable");
        }
        return store;
    }
}
