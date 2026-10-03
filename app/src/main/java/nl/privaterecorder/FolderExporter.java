package nl.privaterecorder;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Incremental, read-back-verified export of individual evidence files through Android's picker. */
final class FolderExporter {
    private static final AtomicBoolean ACTIVE = new AtomicBoolean();
    interface Progress { void update(int completedBundles, int totalBundles, String filename); }
    interface IntegrityCheck { void verify() throws IOException; }

    static final class SourceBundle {
        final String key;
        final List<File> files;
        final IntegrityCheck check;
        SourceBundle(String key, List<File> files, IntegrityCheck check) {
            this.key = key;
            this.files = Collections.unmodifiableList(new ArrayList<>(files));
            this.check = check;
        }
    }

    static final class Entry {
        final String id, name;
        final boolean directory, renameSupported;
        Entry(String id, String name, boolean directory, boolean renameSupported) {
            this.id = id;
            this.name = name;
            this.directory = directory;
            this.renameSupported = renameSupported;
        }
    }

    /** Small provider seam also used by file-backed export tests. */
    interface Destination {
        List<Entry> list() throws IOException;
        InputStream read(Entry entry) throws IOException;
        Entry create(String name, String mimeType) throws IOException;
        OutputStream writeNew(Entry entry) throws IOException;
        Entry rename(Entry entry, String name) throws IOException;
    }

    static final class Result {
        int copiedFiles, reusedFiles, verifiedBundles, audioSegments, errorCount;
        String manifestName;
        final List<String> errors = new ArrayList<>();
        void error(String message) {
            errorCount++;
            if (errors.size() < 20) errors.add(message);
        }
    }

    static boolean isRunning() { return ACTIVE.get(); }

    static Result export(Context context, Uri tree, Progress progress) throws IOException {
        if (!ACTIVE.compareAndSet(false, true)) throw new IOException("Another folder export is still running");
        try {
            EvidenceStore store = new EvidenceStore(context);
            List<String> sourceErrors = new ArrayList<>();
            try { store.createJournalCheckpoint(); }
            catch (IOException failure) { sourceErrors.add("Trailing event log was not sealed: " + message(failure)); }
            EvidenceStore.SyncSnapshot snapshot = store.syncSnapshot();
            sourceErrors.addAll(snapshot.errors);
            List<SourceBundle> bundles = new ArrayList<>();
            for (EvidenceStore.SyncBundle bundle : snapshot.bundles) {
                bundles.add(new SourceBundle(bundle.key, bundle.files, bundle::verifyIntegrity));
            }
            return exportBundles(bundles, sourceErrors, new AndroidDestination(context.getContentResolver(), tree),
                    context.getCacheDir(), progress);
        } finally {
            ACTIVE.set(false);
        }
    }

    static Result exportBundles(List<SourceBundle> bundles, List<String> sourceErrors,
            Destination destination, File cacheDirectory, Progress progress) throws IOException {
        Result result = new Result();
        for (String sourceError : sourceErrors) result.error(sourceError);
        // Already-exported files are indexed once; new files are checked again before publishing.
        Map<String, List<Entry>> index = index(destination.list());
        File manifest = File.createTempFile("folder-export-", ".sha256", cacheDirectory);
        try {
            try (FileOutputStream manifestStream = new FileOutputStream(manifest);
                    BufferedWriter lines = new BufferedWriter(new OutputStreamWriter(manifestStream, StandardCharsets.UTF_8))) {
                int completed = 0;
                for (SourceBundle bundle : bundles) {
                    Map<String, String> hashes = new HashMap<>();
                    boolean verified = false;
                    try {
                        bundle.check.verify();
                        // Check every name before starting a bundle, preserving existing conflicting files.
                        for (File source : bundle.files) {
                            EvidenceStore.validateBasename(source.getName());
                            if (!source.isFile()) throw new IOException("Source file is missing: " + source.getName());
                            String hash = EvidenceStore.sha256Prefix(source, source.length());
                            hashes.put(source.getName(), hash);
                            Entry existing = unique(index, source.getName());
                            if (existing != null) verify(destination, existing, source.length(), hash);
                        }
                        for (File source : bundle.files) {
                            if (progress != null) progress.update(completed, bundles.size(), source.getName());
                            String hash = hashes.get(source.getName());
                            Entry existing = unique(index, source.getName());
                            if (existing != null) result.reusedFiles++;
                            else {
                                Entry copied = copyNew(destination, source, source.getName(), hash, index);
                                index.computeIfAbsent(copied.name.toLowerCase(Locale.ROOT), ignored -> new ArrayList<>()).add(copied);
                                result.copiedFiles++;
                            }
                        }
                        // Recheck the immutable source before claiming success in the manifest.
                        bundle.check.verify();
                        for (File source : bundle.files) {
                            String hash = hashes.get(source.getName());
                            if (!hash.equals(EvidenceStore.sha256Prefix(source, source.length()))) {
                                throw new IOException("Source changed during export: " + source.getName());
                            }
                        }
                        verified = true;
                    } catch (IOException | IllegalArgumentException failure) {
                        result.error(bundle.key + ": " + message(failure));
                    }
                    if (verified) {
                        // A manifest I/O error aborts publication rather than leaving a partial line
                        // in a later apparently successful integrity list.
                        for (File source : bundle.files) {
                            lines.write(hashes.get(source.getName()) + "  " + source.getName() + "\n");
                        }
                        result.verifiedBundles++;
                        if (bundle.key.endsWith(".aac")) result.audioSegments++;
                    }
                    completed++;
                    if (progress != null) progress.update(completed, bundles.size(), "");
                }
                lines.flush();
                manifestStream.getFD().sync();
            }
            if (result.verifiedBundles > 0) {
                String name = "SHA256SUMS_" + System.currentTimeMillis() + "_" + UUID.randomUUID() + ".txt";
                try {
                    copyNew(destination, manifest, name, EvidenceStore.sha256Prefix(manifest, manifest.length()), index);
                    result.manifestName = name;
                } catch (IOException failure) {
                    result.error("Integrity manifest was not saved: " + message(failure));
                }
            }
            return result;
        } finally {
            // Only the private, generated cache file is removed. Destination files and recordings remain.
            if (!manifest.delete()) manifest.deleteOnExit();
        }
    }

    private static Entry copyNew(Destination destination, File source, String name, String hash,
            Map<String, List<Entry>> index) throws IOException {
        if (unique(index, name) != null) throw new IOException("Destination name already exists: " + name);
        String pendingName = "Pending_" + UUID.randomUUID() + "_" + name;
        Entry pending = destination.create(pendingName, mimeType(name));
        if (pending.directory || !pending.renameSupported) {
            throw new IOException("This folder cannot safely rename completed files. Choose a device folder; pending files are retained.");
        }
        try (InputStream in = new FileInputStream(source); OutputStream out = destination.writeNew(pending)) {
            if (out == null) throw new IOException("Destination cannot be opened");
            byte[] buffer = new byte[65536];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            out.flush();
        }
        verify(destination, pending, source.length(), hash);
        // Check again before publishing. No pre-existing document is ever opened for writing.
        if (unique(index(destination.list()), name) != null) {
            throw new IOException("A conflicting destination appeared during export: " + name);
        }
        Entry finished = destination.rename(pending, name);
        if (finished == null || !name.equals(finished.name)) {
            throw new IOException("The folder changed a filename; incomplete export retained for inspection: " + name);
        }
        verify(destination, finished, source.length(), hash);
        return finished;
    }

    private static void verify(Destination destination, Entry entry, long length, String hash) throws IOException {
        if (entry.directory) throw new IOException("Destination name belongs to a directory: " + entry.name);
        MessageDigest digest = newDigest();
        long size = 0;
        try (InputStream in = destination.read(entry)) {
            if (in == null) throw new IOException("Destination cannot be read back: " + entry.name);
            byte[] buffer = new byte[65536];
            int count;
            while ((count = in.read(buffer)) != -1) {
                size += count;
                if (size > length) throw new IOException("Existing file differs; left unchanged: " + entry.name);
                digest.update(buffer, 0, count);
            }
        }
        if (size != length || !hash.equals(hex(digest.digest()))) {
            throw new IOException("File integrity differs; left unchanged: " + entry.name);
        }
    }

    private static Map<String, List<Entry>> index(List<Entry> entries) {
        Map<String, List<Entry>> index = new HashMap<>();
        for (Entry entry : entries) index.computeIfAbsent(entry.name.toLowerCase(Locale.ROOT), ignored -> new ArrayList<>()).add(entry);
        return index;
    }

    private static Entry unique(Map<String, List<Entry>> index, String name) throws IOException {
        List<Entry> entries = index.get(name.toLowerCase(Locale.ROOT));
        if (entries == null || entries.isEmpty()) return null;
        if (entries.size() != 1 || !name.equals(entries.get(0).name)) {
            throw new IOException("Ambiguous or case-conflicting destination name; left unchanged: " + name);
        }
        return entries.get(0);
    }

    private static String mimeType(String name) {
        if (name.endsWith(".aac")) return "audio/aac";
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".jsonl")) return "application/x-ndjson";
        return "text/plain";
    }

    private static String message(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private static MessageDigest newDigest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }

    private static final class AndroidDestination implements Destination {
        private final ContentResolver resolver;
        private final Uri tree, parent;
        private static final String[] COLUMNS = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS};

        AndroidDestination(ContentResolver resolver, Uri tree) throws IOException {
            this.resolver = resolver;
            this.tree = tree;
            if (!DocumentsContract.isTreeUri(tree)) throw new IOException("Select a folder, not a file");
            parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            if (!queryOne(parent).directory) throw new IOException("Selected location is not a folder");
        }

        @Override public List<Entry> list() throws IOException {
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent));
            List<Entry> result = new ArrayList<>();
            try (Cursor cursor = resolver.query(children, COLUMNS, null, null, null)) {
                if (cursor == null) throw new IOException("Cannot list selected folder");
                while (cursor.moveToNext()) result.add(fromCursor(cursor));
            } catch (RuntimeException failure) { throw new IOException("Cannot access selected folder", failure); }
            return result;
        }

        @Override public InputStream read(Entry entry) throws IOException { return resolver.openInputStream(Uri.parse(entry.id)); }
        @Override public OutputStream writeNew(Entry entry) throws IOException { return resolver.openOutputStream(Uri.parse(entry.id), "w"); }
        @Override public Entry create(String name, String mime) throws IOException {
            Uri created = DocumentsContract.createDocument(resolver, parent, mime, name);
            if (created == null) throw new IOException("Cannot create file in selected folder");
            return queryOne(created);
        }
        @Override public Entry rename(Entry entry, String name) throws IOException {
            Uri renamed = DocumentsContract.renameDocument(resolver, Uri.parse(entry.id), name);
            if (renamed == null) throw new IOException("Folder provider could not finish the file");
            return queryOne(renamed);
        }

        private Entry queryOne(Uri document) throws IOException {
            try (Cursor cursor = resolver.query(document, COLUMNS, null, null, null)) {
                if (cursor == null || !cursor.moveToFirst()) throw new IOException("Cannot inspect selected document");
                return fromCursor(cursor);
            } catch (RuntimeException failure) { throw new IOException("Cannot inspect selected document", failure); }
        }

        private Entry fromCursor(Cursor cursor) throws IOException {
            String id = cursor.getString(0), name = cursor.getString(1), mime = cursor.getString(2);
            if (id == null || name == null) throw new IOException("Folder provider returned incomplete file details");
            Uri document = DocumentsContract.buildDocumentUriUsingTree(tree, id);
            return new Entry(document.toString(), name, DocumentsContract.Document.MIME_TYPE_DIR.equals(mime),
                    (cursor.getInt(3) & DocumentsContract.Document.FLAG_SUPPORTS_RENAME) != 0);
        }
    }
}
