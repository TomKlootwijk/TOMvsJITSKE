package nl.privaterecorder;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.UUID;

/**
 * Durable upload receipts, scoped by provider/account/destination and content fingerprint.
 * A caller may mark a bundle only after independently verifying all its remote files.
 * This class never changes or deletes recordings and never treats a partial write as success.
 */
public final class CloudLedger {
    private static final Object LEDGER_LOCK = new Object();
    private static final int MAX_RECEIPT_BYTES = 1024 * 1024;
    private final File directory;

    public CloudLedger(File directory) throws IOException {
        if (directory == null) throw new IllegalArgumentException("Missing cloud-state directory");
        this.directory = directory.getCanonicalFile();
        if (!this.directory.isDirectory() && !this.directory.mkdirs() && !this.directory.isDirectory()) {
            throw new IOException("Cannot create cloud-state directory");
        }
    }

    /** Missing, corrupt, or mismatched receipts are unsynced and safe to retry. */
    public boolean isUploaded(String scope, String bundleKey, String digest) throws IOException {
        validateIdentity(scope, "scope");
        validateIdentity(bundleKey, "bundle key");
        String normalizedDigest = normalizedDigest(digest);
        synchronized (LEDGER_LOCK) {
            File file = receiptFile(scope, bundleKey);
            if (!file.isFile()) return false;
            long length = file.length();
            if (length < 1 || length > MAX_RECEIPT_BYTES) return false;
            byte[] bytes = new byte[(int) length];
            try (FileInputStream stream = new FileInputStream(file)) {
                int offset = 0;
                while (offset < bytes.length) {
                    int count = stream.read(bytes, offset, bytes.length - offset);
                    if (count < 0) return false;
                    offset += count;
                }
                if (stream.read() != -1) return false;
            }
            try {
                JSONObject envelope = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                if (envelope.getInt("schema_version") != 1) return false;
                String payloadText = envelope.getString("payload");
                if (!sha256(payloadText).equals(envelope.getString("payload_sha256"))) return false;
                JSONObject payload = new JSONObject(payloadText);
                return payload.getInt("schema_version") == 1
                        && scope.equals(payload.getString("scope"))
                        && bundleKey.equals(payload.getString("bundle_key"))
                        && normalizedDigest.equals(payload.getString("content_sha256"))
                        && payload.getLong("verified_epoch_ms") >= 0
                        && payload.getJSONObject("receipt") != null;
            } catch (JSONException | IllegalArgumentException corrupt) {
                return false;
            }
        }
    }

    /**
     * Commits one receipt after successful remote hash verification. Existing receipts survive
     * failed or interrupted replacement; abandoned temporary files never count as uploaded.
     */
    public void markUploaded(String scope, String bundleKey, String digest, JSONObject receipt)
            throws IOException {
        validateIdentity(scope, "scope");
        validateIdentity(bundleKey, "bundle key");
        String normalizedDigest = normalizedDigest(digest);
        if (receipt == null) throw new IllegalArgumentException("Missing verified upload receipt");
        synchronized (LEDGER_LOCK) {
            final byte[] bytes;
            try {
                JSONObject payload = new JSONObject();
                payload.put("schema_version", 1);
                payload.put("scope", scope);
                payload.put("bundle_key", bundleKey);
                payload.put("content_sha256", normalizedDigest);
                payload.put("verified_epoch_ms", System.currentTimeMillis());
                payload.put("receipt", new JSONObject(receipt.toString()));
                String payloadText = payload.toString();
                JSONObject envelope = new JSONObject();
                envelope.put("schema_version", 1);
                // Preserve the exact payload serialization for a stable corruption checksum.
                envelope.put("payload", payloadText);
                envelope.put("payload_sha256", sha256(payloadText));
                bytes = (envelope.toString(2) + "\n").getBytes(StandardCharsets.UTF_8);
            } catch (JSONException malformed) {
                throw new IOException("Cannot serialize upload receipt", malformed);
            }
            if (bytes.length > MAX_RECEIPT_BYTES) throw new IOException("Upload receipt is too large");
            File target = receiptFile(scope, bundleKey);
            File temporary = new File(directory, target.getName() + ".tmp-" + UUID.randomUUID());
            try (FileOutputStream stream = new FileOutputStream(temporary)) {
                stream.write(bytes);
                stream.getFD().sync();
            }
            Files.move(temporary.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private File receiptFile(String scope, String bundleKey) {
        return new File(directory, sha256(scope + "\u0000" + bundleKey) + ".json");
    }

    private static void validateIdentity(String value, String label) {
        if (value == null || value.isEmpty() || value.length() > 4096 || value.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("Invalid cloud " + label);
        }
    }

    private static String normalizedDigest(String digest) {
        if (digest == null || !digest.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("Expected a SHA-256 content fingerprint");
        }
        return digest.toLowerCase(Locale.ROOT);
    }

    private static String sha256(String value) {
        final MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
        char[] alphabet = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            result[i * 2] = alphabet[(bytes[i] & 255) >>> 4];
            result[i * 2 + 1] = alphabet[bytes[i] & 15];
        }
        return new String(result);
    }
}
