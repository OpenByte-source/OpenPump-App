package org.openpump;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** Keystore-backed AES-GCM; tokens and approved records never enter preferences or backups. */
final class GrowthTrackStore implements GrowthTrackClient.Store {
    private static final String ALIAS = "org.openpump.growthtrack.v1";
    private static final int MAX_STORED_BYTES = 16_000_000;
    private final AtomicFile file;
    GrowthTrackStore(Context context) { file = new AtomicFile(new File(context.getNoBackupFilesDir(), "growthtrack-v1.enc")); }
    @Override public JSONObject read() throws IOException {
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile().getPath() + ".bak").exists()) return new JSONObject();
        try (FileInputStream in = file.openRead()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) { if (out.size() + n > MAX_STORED_BYTES) throw new IOException(); out.write(buffer, 0, n); }
            byte[] blob = out.toByteArray();
            if (blob.length < 32 || blob[0] != 'G' || blob[1] != 'T' || blob[2] != 1) throw new IOException();
            byte[] iv = java.util.Arrays.copyOfRange(blob, 3, 15);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, iv));
            cipher.updateAAD(new byte[]{'G', 'T', 1});
            byte[] clear = cipher.doFinal(blob, 15, blob.length - 15);
            try { return new JSONObject(new String(clear, StandardCharsets.UTF_8)); }
            finally { java.util.Arrays.fill(clear, (byte) 0); }
        } catch (Exception e) { throw new IOException("Secure GrowthTrack storage is unavailable. Disconnect/reset the connection to link again."); }
    }
    @Override public void write(JSONObject state) throws IOException {
        FileOutputStream out = null; byte[] clear = state.toString().getBytes(StandardCharsets.UTF_8);
        try {
            if (clear.length > MAX_STORED_BYTES - 32) throw new IOException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key(true));
            cipher.updateAAD(new byte[]{'G', 'T', 1}); byte[] iv = cipher.getIV();
            if (iv.length != 12) throw new IOException();
            byte[] encrypted = cipher.doFinal(clear);
            out = file.startWrite(); out.write(new byte[]{'G', 'T', 1}); out.write(iv); out.write(encrypted); file.finishWrite(out); out = null;
        } catch (Exception e) { if (out != null) file.failWrite(out); throw new IOException("Could not securely save the GrowthTrack connection. No plain-text fallback is used."); }
        finally { java.util.Arrays.fill(clear, (byte) 0); }
    }
    private static SecretKey key(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        if (!create) throw new IOException();
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }
    void erase() throws IOException {
        file.delete();
        boolean remains = file.getBaseFile().exists() || new File(file.getBaseFile().getPath() + ".bak").exists()
            || new File(file.getBaseFile().getPath() + ".new").exists();
        // Destroy the decryption key even if the filesystem could not remove an old blob.
        try { KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null); if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS); }
        catch (Exception e) { throw new IOException("Could not remove the GrowthTrack encryption key"); }
        if (remains) throw new IOException("Could not remove GrowthTrack connection storage. Retry reset before linking again.");
    }
}
