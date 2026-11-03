package com.alex.minimalhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.net.URI;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Device pairing only. OpenAI project keys never belong in this application. */
final class VoiceServiceConfig {
    private static final String PREFS = "chatgpt_voice_service";
    private static final String KEY = "com.alex.minimalhome.voice.pairing.v1";
    final String endpoint, certificateSha256, token;
    final boolean readOnly;

    VoiceServiceConfig(String endpoint, String certificateSha256, String token, boolean readOnly) {
        String clean = endpoint.trim();
        while (clean.endsWith("/")) clean = clean.substring(0, clean.length() - 1);
        URI uri;
        try { uri = new URI(clean); } catch (Exception e) { throw new IllegalArgumentException("invalid_endpoint"); }
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getPath() == null || uri.getPath().isEmpty())
                || uri.getPort() == 0 || uri.getPort() > 65535 || clean.length() > 512) {
            throw new IllegalArgumentException("invalid_endpoint");
        }
        String pin = certificateSha256.trim().toLowerCase(java.util.Locale.ROOT);
        if (!pin.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("invalid_certificate");
        if (!token.matches("[A-Za-z0-9_-]{32,256}")) throw new IllegalArgumentException("invalid_pairing");
        this.endpoint = clean; this.certificateSha256 = pin; this.token = token; this.readOnly = readOnly;
    }

    static VoiceServiceConfig load(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!p.contains("token_cipher")) return null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(128,
                    Base64.decode(p.getString("token_iv", ""), Base64.NO_WRAP)));
            String token = new String(cipher.doFinal(Base64.decode(p.getString("token_cipher", ""),
                    Base64.NO_WRAP)), java.nio.charset.StandardCharsets.UTF_8);
            return new VoiceServiceConfig(p.getString("endpoint", ""), p.getString("certificate_sha256", ""),
                    token, p.getBoolean("read_only", true));
        } catch (Exception e) { return null; }
    }

    void save(Context context) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey());
        String encrypted = Base64.encodeToString(cipher.doFinal(token.getBytes(
                java.nio.charset.StandardCharsets.UTF_8)), Base64.NO_WRAP);
        boolean saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("endpoint", endpoint).putString("certificate_sha256", certificateSha256)
                .putString("token_cipher", encrypted)
                .putString("token_iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .putBoolean("read_only", readOnly).commit();
        if (!saved) throw new java.io.IOException("pairing_not_saved");
    }

    boolean sameConnection(VoiceServiceConfig other) {
        return other != null && endpoint.equals(other.endpoint) && certificateSha256.equals(other.certificateSha256)
                && token.equals(other.token) && readOnly == other.readOnly;
    }

    /** Explicit setup action reads one app-scoped provisioning file, then removes it. */
    static boolean importPairing(Context context) throws Exception {
        File directory = context.getExternalFilesDir(null);
        if (directory == null) return false;
        File input = new File(directory, "voice-pairing.json");
        if (!input.exists()) return false;
        try {
            if (!input.isFile() || !input.getCanonicalFile().equals(input.getAbsoluteFile())
                    || input.length() <= 0 || input.length() > 8192) throw new java.io.IOException("invalid_pairing_file");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (FileInputStream stream = new FileInputStream(input)) {
                byte[] buffer = new byte[1024]; int size;
                while ((size = stream.read(buffer)) >= 0) {
                    bytes.write(buffer, 0, size);
                    if (bytes.size() > 8192) throw new java.io.IOException("pairing_file_too_large");
                }
            }
            JSONObject json = new JSONObject(new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
            new VoiceServiceConfig(json.getString("endpoint"), json.getString("cert_sha256"),
                    json.getString("token"), json.optBoolean("readOnly", true)).save(context);
            return true;
        } finally { if (input.exists() && !input.delete()) input.deleteOnExit(); }
    }

    private static synchronized SecretKey secretKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias(KEY)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey(KEY, null);
    }
}
