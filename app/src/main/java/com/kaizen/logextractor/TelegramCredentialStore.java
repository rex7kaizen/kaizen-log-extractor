package com.kaizen.logextractor;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Stores Telegram credentials and the TDLib database key encrypted with Android Keystore AES-GCM. */
final class TelegramCredentialStore {
    private static final String PREFS = "kaizen_telegram_secure";
    private static final String KEY_ALIAS = "kaizen_telegram_aes_gcm_v1";
    private static final String KEY_API_ID = "api_id";
    private static final String KEY_API_HASH = "api_hash_enc";
    private static final String KEY_DATABASE = "database_key_enc";
    private static final int DATABASE_KEY_BYTES = 32;

    private final SharedPreferences preferences;

    TelegramCredentialStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    int getApiId() {
        return preferences.getInt(KEY_API_ID, 0);
    }

    String getApiHash() throws Exception {
        String encrypted = preferences.getString(KEY_API_HASH, null);
        return encrypted == null ? null : decrypt(encrypted);
    }

    void saveCredentials(int apiId, String apiHash) throws Exception {
        if (apiId <= 0 || apiHash == null || !apiHash.matches("(?i)[0-9a-f]{32}")) {
            throw new IllegalArgumentException("API ID pozitif bir sayı, API Hash ise 32 karakterlik hexadecimal değer olmalı.");
        }
        String encryptedHash = encrypt(apiHash);
        boolean saved = preferences.edit()
                .putInt(KEY_API_ID, apiId)
                .putString(KEY_API_HASH, encryptedHash)
                .commit();
        if (!saved) throw new java.io.IOException("Telegram API bilgileri güvenli depolamaya yazılamadı.");
    }

    byte[] getOrCreateDatabaseKey() throws Exception {
        String encrypted = preferences.getString(KEY_DATABASE, null);
        if (encrypted != null) {
            byte[] decoded = Base64.decode(decrypt(encrypted), Base64.NO_WRAP);
            if (decoded.length != DATABASE_KEY_BYTES) {
                Arrays.fill(decoded, (byte) 0);
                throw new IllegalStateException("Kaydedilmiş TDLib veritabanı anahtarı geçersiz.");
            }
            return decoded;
        }
        byte[] key = new byte[DATABASE_KEY_BYTES];
        new SecureRandom().nextBytes(key);
        String value = Base64.encodeToString(key, Base64.NO_WRAP);
        boolean saved = preferences.edit().putString(KEY_DATABASE, encrypt(value)).commit();
        if (!saved) {
            Arrays.fill(key, (byte) 0);
            throw new java.io.IOException("TDLib veritabanı anahtarı güvenli depolamaya yazılamadı.");
        }
        return key;
    }

    void clear() throws Exception {
        if (!preferences.edit().clear().commit()) {
            throw new java.io.IOException("Telegram güvenli ayarları silinemedi.");
        }
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS);
    }

    private String encrypt(String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] iv = cipher.getIV();
        byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        ByteBuffer payload = ByteBuffer.allocate(1 + iv.length + ciphertext.length);
        payload.put((byte) iv.length).put(iv).put(ciphertext);
        return Base64.encodeToString(payload.array(), Base64.NO_WRAP);
    }

    private String decrypt(String encoded) throws Exception {
        byte[] payload = Base64.decode(encoded, Base64.NO_WRAP);
        if (payload.length < 1 + 12 + 16) throw new IllegalStateException("Şifreli Telegram ayar verisi bozuk.");
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        int ivLength = buffer.get() & 0xff;
        if (ivLength < 12 || ivLength > 16 || buffer.remaining() <= ivLength) {
            throw new IllegalStateException("Şifreli Telegram ayar verisi geçersiz.");
        }
        byte[] iv = new byte[ivLength];
        buffer.get(iv);
        byte[] ciphertext = new byte[buffer.remaining()];
        buffer.get(ciphertext);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        java.security.Key existing = keyStore.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;

        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        KeyGenParameterSpec specification = new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build();
        generator.init(specification);
        return generator.generateKey();
    }
}
