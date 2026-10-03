package nl.privaterecorder;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Device-bound, authenticated encryption for OAuth state. Never backed up or logged. */
final class TokenVault {
    private static final String KEY_ALIAS = "private_recorder_cloud_auth_v1";
    private static final int MAX_BYTES = 1024 * 1024;
    private TokenVault() { }

    static synchronized String read(Context context, String slot) throws Exception {
        AtomicFile file = file(context, slot);
        if (!file.getBaseFile().exists()
                && !new File(file.getBaseFile().getPath() + ".bak").exists()) return null;
        try (FileInputStream stream = file.openRead()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                if (bytes.size() + count > MAX_BYTES) throw new IOException("Stored cloud sign-in is too large. Reconnect the account.");
                bytes.write(buffer, 0, count);
            }
            byte[] data = bytes.toByteArray();
            return new String(decrypt(key(), slot, data), StandardCharsets.UTF_8);
        }
    }

    static synchronized void write(Context context, String slot, String value) throws Exception {
        AtomicFile file = file(context, slot);
        if (value == null) {
            file.delete();
            if (file.getBaseFile().exists()) throw new IOException("Could not remove the saved cloud sign-in.");
            return;
        }
        byte[] plain = value.getBytes(StandardCharsets.UTF_8);
        if (plain.length > MAX_BYTES - 64) throw new IOException("Cloud sign-in response is too large.");
        byte[] ciphertext = encrypt(key(), slot, plain);
        Arrays.fill(plain, (byte) 0);
        FileOutputStream stream = null;
        try {
            stream = file.startWrite();
            stream.write(ciphertext);
            file.finishWrite(stream);
        } catch (Exception error) {
            if (stream != null) file.failWrite(stream);
            throw error;
        }
    }

    private static AtomicFile file(Context context, String slot) throws IOException {
        if (!slot.matches("[a-z-]{1,32}")) throw new IllegalArgumentException("Invalid vault slot");
        File directory = new File(context.getNoBackupFilesDir(), "cloud-auth");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create private cloud sign-in storage.");
        return new AtomicFile(new File(directory, slot + ".enc"));
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) return (SecretKey) store.getKey(KEY_ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build());
        return generator.generateKey();
    }

    // Kept independent of Android storage so format, tampering and slot binding are testable.
    static byte[] encrypt(SecretKey key, String slot, byte[] plain) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        cipher.updateAAD(aad(slot));
        byte[] nonce = cipher.getIV();
        if (nonce.length != 12) throw new GeneralSecurityException("Unexpected GCM nonce length");
        byte[] encrypted = cipher.doFinal(plain);
        return ByteBuffer.allocate(2 + nonce.length + encrypted.length)
                .put((byte) 1).put((byte) nonce.length).put(nonce).put(encrypted).array();
    }

    static byte[] decrypt(SecretKey key, String slot, byte[] encoded) throws GeneralSecurityException {
        if (encoded == null || encoded.length < 30 || encoded[0] != 1 || encoded[1] != 12) {
            throw new GeneralSecurityException("Invalid encrypted cloud sign-in; reconnect the account.");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, encoded, 2, 12));
        cipher.updateAAD(aad(slot));
        return cipher.doFinal(encoded, 14, encoded.length - 14);
    }

    private static byte[] aad(String slot) {
        return ("private-recorder:cloud-auth:v1:" + slot).getBytes(StandardCharsets.UTF_8);
    }
}
