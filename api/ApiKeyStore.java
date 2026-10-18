package org.jarvis.mobile.api;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import android.util.Base64;

public final class ApiKeyStore {
    public static final String GROQ = "groq";
    public static final String OPENAI = "openai";

    private static final String PREFERENCES = "jarvis_credentials";
    private static final String KEY_ALIAS = "jarvis_api_key_encryption_v1";
    private static final String GROQ_VALUE = "groq_key";
    private static final String OPENAI_VALUE = "openai_key";
    private static final String PREFERRED_PROVIDER = "preferred_provider";
    private final SharedPreferences preferences;

    public ApiKeyStore(Context context) {
        preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    public static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public synchronized void saveKeys(String groqKey, String openAiKey) throws Exception {
        SharedPreferences.Editor editor = preferences.edit();
        if (groqKey != null && !groqKey.trim().isEmpty()) {
            editor.putString(GROQ_VALUE, encrypt(groqKey.trim()));
        }
        if (openAiKey != null && !openAiKey.trim().isEmpty()) {
            editor.putString(OPENAI_VALUE, encrypt(openAiKey.trim()));
        }
        editor.apply();
    }

    public synchronized String getKey(String provider) throws Exception {
        String name = GROQ.equals(provider) ? GROQ_VALUE : OPENAI_VALUE;
        String encrypted = preferences.getString(name, null);
        return encrypted == null ? null : decrypt(encrypted);
    }

    public synchronized boolean hasKey(String provider) throws Exception {
        return getKey(provider) != null;
    }

    public synchronized boolean isConfigured() throws Exception {
        return hasKey(GROQ) && hasKey(OPENAI);
    }

    public synchronized String getPreferredProvider() {
        return preferences.getString(PREFERRED_PROVIDER, GROQ);
    }

    public synchronized void setPreferredProvider(String provider) {
        preferences.edit().putString(PREFERRED_PROVIDER, provider).apply();
    }

    public synchronized void clearKeys() {
        preferences.edit().clear().apply();
        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            keyStore.deleteEntry(KEY_ALIAS);
        } catch (Exception ignored) {
        }
    }

    private String encrypt(String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        byte[] iv = cipher.getIV();
        byte[] payload = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, payload, 0, iv.length);
        System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
        return Base64.encodeToString(payload, Base64.NO_WRAP);
    }

    private String decrypt(String encoded) throws Exception {
        byte[] payload = Base64.decode(encoded, Base64.NO_WRAP);
        byte[] iv = Arrays.copyOfRange(payload, 0, 12);
        byte[] encrypted = Arrays.copyOfRange(payload, 12, payload.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return ((SecretKey) keyStore.getKey(KEY_ALIAS, null));
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}