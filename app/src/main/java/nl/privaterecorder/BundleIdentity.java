package nl.privaterecorder;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** A receipt covers every named file in the bundle, including its metadata and event journal. */
final class BundleIdentity {
    static String digest(List<File> files) throws IOException {
        if (files.isEmpty()) throw new IOException("Empty cloud bundle");
        List<File> sorted = new ArrayList<>(files);
        sorted.sort(Comparator.comparing(File::getName));
        MessageDigest bundle = sha256();
        String previous = null;
        for (File file : sorted) {
            if (file.getName().equals(previous)) throw new IOException("Duplicate bundle filename");
            previous = file.getName();
            if (!file.isFile()) throw new IOException("Missing bundle file");
            MessageDigest content = sha256();
            try (FileInputStream input = new FileInputStream(file)) {
                byte[] bytes = new byte[65536];
                int count;
                while ((count = input.read(bytes)) != -1) content.update(bytes, 0, count);
            }
            bundle.update(file.getName().getBytes(StandardCharsets.UTF_8));
            bundle.update((byte) 0);
            bundle.update(content.digest());
        }
        return hex(bundle.digest());
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    private static String hex(byte[] data) {
        StringBuilder text = new StringBuilder();
        for (byte value : data) text.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return text.toString();
    }
}
