package nl.privaterecorder;

import android.content.Context;
import android.os.StatFs;
import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Append-only recording storage. A digest detects changes; it does not authenticate evidence. */
public final class EvidenceStore {
    public static final long MIN_FREE_BYTES = 256L * 1024 * 1024;
    private static final Object STORE_LOCK = new Object();
    private static final String PARTIAL_SUFFIX = ".aac.partial";
    private static final String CAVEAT = "SHA-256 detects later byte changes only. This archive "
            + "does not authenticate speakers, device time, recording completeness, provenance, "
            + "or legal admissibility. A person who can rewrite the archive can replace its hashes.";
    private final File directory;
    private final File journal;
    private final LongSupplier elapsedRealtime;

    public EvidenceStore(Context context) throws IOException {
        this(new File(context.getNoBackupFilesDir(), "recordings"), SystemClock::elapsedRealtime);
    }

    /** File-backed constructor for storage tests; production uses the Android monotonic clock. */
    EvidenceStore(File recordingDirectory, LongSupplier elapsedRealtime) throws IOException {
        directory = recordingDirectory.getCanonicalFile();
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Cannot create private recording directory");
        }
        journal = child("events.jsonl");
        this.elapsedRealtime = elapsedRealtime;
    }

    public File createSegment(String sessionId, int index,
            long startEpochMs, long startElapsedMs) throws IOException {
        synchronized (STORE_LOCK) {
            return createSegmentLocked(sessionId, index, startEpochMs, startElapsedMs);
        }
    }

    private File createSegmentLocked(String sessionId, int index,
            long startEpochMs, long startElapsedMs) throws IOException {
        validateSessionId(sessionId);
        if (index < 0 || startEpochMs < 0 || startElapsedMs < 0) {
            throw new IllegalArgumentException("Negative segment index or timestamp");
        }
        if (availableBytes() < MIN_FREE_BYTES) throw new IOException("Storage reserve reached");
        String base = sessionId + "_" + String.format(Locale.ROOT, "%06d", index)
                + "_" + startEpochMs;
        File partial = child(base + PARTIAL_SUFFIX);
        if (!partial.createNewFile()) throw new IOException("Segment already exists: " + partial.getName());
        try (FileOutputStream stream = new FileOutputStream(partial, true)) {
            stream.getFD().sync();
        }
        try {
            JSONObject opened = new JSONObject();
            opened.put("schema_version", 1);
            opened.put("session_id", sessionId);
            opened.put("segment_index", index);
            opened.put("partial_filename", partial.getName());
            opened.put("start_epoch_ms", startEpochMs);
            opened.put("start_elapsed_ms", startElapsedMs);
            opened.put("start_clock_offset_ms", startEpochMs - startElapsedMs);
            writeJsonOnce(child(base + ".open.json"), opened);
            appendEvent("segment_open", opened);
            return partial;
        } catch (JSONException e) {
            throw new IOException("Cannot record segment opening metadata; partial file retained", e);
        }
    }

    /** The caller must finish writing and close the audio file before calling this method. */
    public void finishSegment(File partial, long endEpochMs, long endElapsedMs,
            long durationUs, boolean clean) throws IOException {
        synchronized (STORE_LOCK) {
            finishSegmentLocked(partial, endEpochMs, endElapsedMs, durationUs, clean);
        }
    }

    private void finishSegmentLocked(File partial, long endEpochMs, long endElapsedMs,
            long durationUs, boolean clean) throws IOException {
        File checked = requireChild(partial);
        if (!checked.getName().endsWith(PARTIAL_SUFFIX)) {
            throw new IOException("Expected an .aac.partial segment");
        }
        if (!checked.isFile()) throw new IOException("Partial segment is missing");
        String base = checked.getName().substring(0, checked.getName().length() - PARTIAL_SUFFIX.length());
        File finished = child(base + (clean ? ".aac" : ".interrupted.aac"));
        if (finished.exists()) throw new IOException("Final segment already exists; no files overwritten");
        // Sync again even if the encoder already synced. Rename never changes the audio bytes.
        try (FileOutputStream stream = new FileOutputStream(checked, true)) {
            stream.getFD().sync();
        }
        if (!checked.renameTo(finished)) {
            throw new IOException("Cannot finalize segment; original partial file retained");
        }
        writeFinalMetadata(finished, base, endEpochMs, endElapsedMs, durationUs,
                clean ? "finalized" : "interrupted", false);
    }

    public void event(String type, String detail) throws IOException {
        synchronized (STORE_LOCK) {
            if (type == null || !type.matches("[a-zA-Z][a-zA-Z0-9_.-]{0,79}")) {
                throw new IllegalArgumentException("Invalid event type");
            }
            appendEvent(type, detail == null ? "" : detail);
        }
    }

    /** Call only on a new user start, while no recording engine is active. */
    public void recoverInterruptedSegments() throws IOException {
        synchronized (STORE_LOCK) { recoverInterruptedSegmentsLocked(); }
    }

    private void recoverInterruptedSegmentsLocked() throws IOException {
        File[] files = listFiles();
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(PARTIAL_SUFFIX)) {
                // No invented end time or duration: the last captured sample is unknown.
                finishSegment(file, -1, -1, -1, false);
            }
        }
        // Covers process death after rename but before the metadata file was committed.
        for (File file : listFiles()) {
            if (!file.isFile() || !file.getName().endsWith(".aac")) continue;
            String name = file.getName();
            String base = name.endsWith(".interrupted.aac")
                    ? name.substring(0, name.length() - ".interrupted.aac".length())
                    : name.substring(0, name.length() - ".aac".length());
            if (!child(name + ".json").exists()) {
                writeFinalMetadata(file, base, -1, -1, -1, "interrupted", true);
            }
        }
    }

    public long availableBytes() {
        return new StatFs(directory.getAbsolutePath()).getAvailableBytes();
    }

    public long totalBytes() {
        long total = 0;
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) if (file.isFile()) total += file.length();
        return total;
    }

    public int completedCount() {
        int count = 0;
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) {
            if (file.isFile() && file.getName().endsWith(".aac")
                    && new File(directory, file.getName() + ".json").isFile()) count++;
        }
        return count;
    }

    /** Immutable file snapshot; upload I/O must run after this method releases STORE_LOCK. */
    public static final class SyncBundle {
        public final String key;
        public final List<File> files;
        /** Primary audio SHA-256, or the journal delta SHA-256 for a checkpoint. */
        public final String digest;
        public final long totalBytes;
        private final List<IntegrityItem> integrityItems;

        private SyncBundle(String key, List<File> files, String digest, List<IntegrityItem> integrityItems) {
            this.key = key;
            this.files = Collections.unmodifiableList(new ArrayList<>(files));
            this.digest = digest;
            this.integrityItems = Collections.unmodifiableList(new ArrayList<>(integrityItems));
            long bytes = 0;
            for (File file : files) bytes += file.length();
            totalBytes = bytes;
        }

        /** Bulk hashing is explicit and runs only for pending uploads, outside the store lock. */
        public void verifyIntegrity() throws IOException {
            for (IntegrityItem item : integrityItems) {
                if (!item.file.isFile() || item.file.length() != item.length
                        || !item.digest.equals(sha256Prefix(item.file, item.length))) {
                    throw new IOException("Saved bytes no longer match original metadata: " + item.file.getName());
                }
            }
        }
    }

    private static final class IntegrityItem {
        final File file;
        final long length;
        final String digest;
        IntegrityItem(File file, long length, String digest) throws IOException {
            if (length < 0 || !digest.matches("[0-9a-f]{64}")) throw new IOException("Invalid integrity metadata");
            this.file = file;
            this.length = length;
            this.digest = digest;
        }
    }

    public static final class SyncSnapshot {
        public final List<SyncBundle> bundles;
        public final List<String> errors;

        private SyncSnapshot(List<SyncBundle> bundles, List<String> errors) {
            this.bundles = Collections.unmodifiableList(new ArrayList<>(bundles));
            this.errors = Collections.unmodifiableList(new ArrayList<>(errors));
        }
    }

    /** Strict convenience API for callers that require a wholly valid snapshot. */
    public List<SyncBundle> syncBundles() throws IOException {
        SyncSnapshot snapshot = syncSnapshot();
        if (!snapshot.errors.isEmpty()) throw new IOException(snapshot.errors.get(0));
        return snapshot.bundles;
    }

    /**
     * Returns committed immutable bundles and isolated errors. A damaged old recording cannot
     * block other evidence. Only directory enumeration holds the lock; sidecar I/O does not.
     */
    public SyncSnapshot syncSnapshot() throws IOException {
        final File[] files;
        synchronized (STORE_LOCK) { files = listFiles(); }
        List<SyncBundle> bundles = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File file : files) {
            String name = file.getName();
            boolean audio = name.endsWith(".aac");
            boolean checkpoint = name.startsWith("journal_") && name.endsWith(".events.jsonl");
            if (!file.isFile() || (!audio && !checkpoint)) continue;
            try {
                File sidecar = child(name + ".json");
                if (!sidecar.isFile()) {
                    errors.add("Final metadata is not committed: " + name);
                    continue;
                }
                JSONObject metadata = readSmallJson(sidecar);
                String digest = metadata.getString("sha256");
                if (!name.equals(metadata.getString("filename"))
                        || !digest.matches("[0-9a-f]{64}")
                        || metadata.getLong("size_bytes") != file.length()) {
                    throw new IOException("Invalid finalized metadata");
                }
                List<File> members = new ArrayList<>();
                List<IntegrityItem> checks = new ArrayList<>();
                members.add(requireChild(file));
                members.add(sidecar);
                checks.add(new IntegrityItem(file, metadata.getLong("size_bytes"), digest));
                if (audio) {
                    String base = name.endsWith(".interrupted.aac")
                            ? name.substring(0, name.length() - ".interrupted.aac".length())
                            : name.substring(0, name.length() - ".aac".length());
                    File opened = child(base + ".open.json");
                    if (opened.isFile()) members.add(opened);
                    if (metadata.has("opening_metadata_sha256")) {
                        if (!opened.isFile()) throw new IOException("Opening metadata is missing");
                        checks.add(new IntegrityItem(opened, metadata.getLong("opening_metadata_size_bytes"),
                                metadata.getString("opening_metadata_sha256")));
                    }
                }
                if (metadata.has("journal_snapshot")) {
                    JSONObject snapshot = metadata.getJSONObject("journal_snapshot");
                    File events = checkedJournalSnapshot(snapshot);
                    if (!events.equals(file)) members.add(events);
                    checks.add(new IntegrityItem(events, snapshot.getLong("size_bytes"), snapshot.getString("sha256")));
                }
                bundles.add(new SyncBundle(name, members, digest, checks));
            } catch (IOException | JSONException | IllegalArgumentException invalid) {
                errors.add("Cannot read committed bundle " + name + ": " + invalid.getMessage());
            }
        }
        return new SyncSnapshot(bundles, errors);
    }

    /** Saves trailing stop/error events even if no further recording segment is created. */
    public void createJournalCheckpoint() throws IOException {
        synchronized (STORE_LOCK) {
            long start = journalCursor();
            long end = journal.length();
            if (end <= start) return;
            String name = "journal_" + UUID.randomUUID() + ".events.jsonl";
            File target = child(name);
            try {
                JSONObject snapshot = writeJournalSnapshot(target, start, end);
                JSONObject metadata = new JSONObject();
                metadata.put("schema_version", 1);
                metadata.put("kind", "journal_checkpoint");
                metadata.put("filename", name);
                metadata.put("size_bytes", end - start);
                metadata.put("sha256", snapshot.getString("sha256"));
                metadata.put("journal_snapshot", snapshot);
                metadata.put("integrity_limit", CAVEAT);
                File sidecar = child(name + ".json");
                writeJsonOnce(sidecar, metadata);
                advanceJournalCursor(sidecar, end);
            } catch (JSONException e) {
                throw new IOException("Cannot create journal checkpoint; original journal retained", e);
            }
        }
    }

    /**
     * Snapshot finalized recordings without pausing capture. Does not close the caller's stream.
     * A failed export may leave a partial ZIP at the chosen destination; original files remain.
     */
    public void exportZip(OutputStream destination) throws IOException {
        final List<ExportItem> items = new ArrayList<>();
        final byte[] exportMetadata;
        final File[] files;
        final ExportItem journalSnapshot;
        synchronized (STORE_LOCK) {
            files = listFiles();
            journalSnapshot = journal.isFile() ? new ExportItem(journal, "events.jsonl") : null;
        }
        // Sidecars are immutable once committed. Preserve the captured name set so a segment
        // committed during this scan cannot enter an export whose journal prefix predates it.
        Set<String> capturedNames = new HashSet<>();
        for (File file : files) capturedNames.add(file.getName());
        {
            Arrays.sort(files, Comparator.comparing(File::getName));
            int partials = 0;
            int incompleteFinalizations = 0;
            int segments = 0;
            for (File file : files) {
                String name = file.getName();
                if (name.endsWith(PARTIAL_SUFFIX)) { partials++; continue; }
                if (!file.isFile()) continue;
                if (!name.endsWith(".aac")) continue;
                File sidecar = child(name + ".json");
                if (!capturedNames.contains(sidecar.getName()) || !sidecar.isFile()) { incompleteFinalizations++; continue; }
                items.add(new ExportItem(requireChild(file), "recordings/" + name));
                items.add(new ExportItem(sidecar, "recordings/" + sidecar.getName()));
                String base = name.endsWith(".interrupted.aac")
                        ? name.substring(0, name.length() - ".interrupted.aac".length())
                        : name.substring(0, name.length() - ".aac".length());
                File opened = child(base + ".open.json");
                if (opened.isFile()) items.add(new ExportItem(opened, "recordings/" + opened.getName()));
                try {
                    JSONObject metadata = readSmallJson(sidecar);
                    if (metadata.has("journal_snapshot")) {
                        File events = checkedJournalSnapshot(metadata.getJSONObject("journal_snapshot"));
                        items.add(new ExportItem(events, "recordings/" + events.getName()));
                    }
                } catch (JSONException e) { throw new IOException("Cannot read finalized metadata", e); }
                segments++;
            }
            if (journalSnapshot != null) items.add(journalSnapshot);
            try {
                JSONObject metadata = new JSONObject();
                metadata.put("schema_version", 1);
                metadata.put("export_epoch_ms", System.currentTimeMillis());
                metadata.put("finalized_segments", segments);
                metadata.put("excluded_partial_segments", partials);
                metadata.put("excluded_incomplete_finalizations", incompleteFinalizations);
                metadata.put("snapshot", "Finalized files and journal byte prefix captured at export start; "
                        + "active .partial files and uncommitted temporary metadata are excluded. "
                        + "Recording may continue during export.");
                metadata.put("hash_manifest", "SHA256SUMS covers every ZIP member except itself.");
                metadata.put("integrity_limit", CAVEAT);
                exportMetadata = (metadata.toString(2) + "\n").getBytes(StandardCharsets.UTF_8);
            } catch (JSONException e) {
                throw new IOException("Cannot prepare export metadata", e);
            }
        }
        // Bulk I/O runs outside the store monitor. Entries are immutable, except the journal,
        // for which the snapshot stores a fixed byte length. Two passes avoid a huge manifest buffer.
        OutputStream leaveOpen = new OutputStream() {
            @Override public void write(int value) throws IOException { destination.write(value); }
            @Override public void write(byte[] data, int offset, int count) throws IOException {
                destination.write(data, offset, count);
            }
            @Override public void flush() throws IOException { destination.flush(); }
            @Override public void close() throws IOException { destination.flush(); }
        };
        try (ZipOutputStream zip = new ZipOutputStream(leaveOpen, StandardCharsets.UTF_8)) {
            zip.setLevel(Deflater.NO_COMPRESSION);
            for (ExportItem item : items) {
                zip.putNextEntry(new ZipEntry(item.zipName));
                copyPrefix(item.file, item.length, zip);
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("export_metadata.json"));
            zip.write(exportMetadata);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("SHA256SUMS"));
            for (ExportItem item : items) {
                String line = sha256Prefix(item.file, item.length) + "  " + item.zipName + "\n";
                zip.write(line.getBytes(StandardCharsets.UTF_8));
            }
            String metaLine = hex(newDigest().digest(exportMetadata)) + "  export_metadata.json\n";
            zip.write(metaLine.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    private void writeFinalMetadata(File file, String base, long endEpochMs, long endElapsedMs,
            long durationUs, String status, boolean recoveredAfterRename) throws IOException {
        try {
            JSONObject metadata = new JSONObject();
            File opening = child(base + ".open.json");
            String openingError = null;
            if (opening.isFile()) {
                try { metadata = readSmallJson(opening); }
                catch (IOException | JSONException e) { openingError = e.getMessage(); }
                metadata.put("opening_metadata_size_bytes", opening.length());
                metadata.put("opening_metadata_sha256", sha256Prefix(opening, opening.length()));
            } else openingError = "Opening metadata missing; start timestamps are unknown";
            metadata.put("schema_version", 1);
            metadata.put("filename", file.getName());
            metadata.put("status", status);
            metadata.put("recovered_after_rename", recoveredAfterRename);
            metadata.put("opening_metadata_error", openingError == null ? JSONObject.NULL : openingError);
            metadata.put("end_epoch_ms", nullableNonnegative(endEpochMs));
            metadata.put("end_elapsed_ms", nullableNonnegative(endElapsedMs));
            metadata.put("end_clock_offset_ms", endEpochMs >= 0 && endElapsedMs >= 0
                    ? endEpochMs - endElapsedMs : JSONObject.NULL);
            metadata.put("duration_us", nullableNonnegative(durationUs));
            metadata.put("duration_basis", durationUs >= 0 ? "AAC frame count at nominal sample rate" : "unknown after interruption");
            metadata.put("timestamp_basis", "Recording-engine timeline estimates based on session device-clock "
                    + "origin and encoded frame counts. These do not establish exact real-world capture "
                    + "endpoints, capture loss, or later wall-clock adjustments.");
            metadata.put("format", "AAC-LC ADTS");
            metadata.put("sample_rate_hz", 48000);
            metadata.put("channels", 1);
            metadata.put("requested_bitrate_bps", 64000);
            metadata.put("size_bytes", file.length());
            metadata.put("sha256", sha256Prefix(file, file.length()));
            metadata.put("integrity_limit", CAVEAT);
            // The immutable journal range and the final sidecar commit before the cursor advances.
            // A crash before cursor commit can duplicate events in a later bundle, never omit them.
            appendEvent("segment_" + status, file.getName());
            long start = journalCursor();
            long end = journal.length();
            File events = child(file.getName() + ".events-" + start + "-" + end + ".jsonl");
            metadata.put("journal_snapshot", writeJournalSnapshot(events, start, end));
            File sidecar = child(file.getName() + ".json");
            writeJsonOnce(sidecar, metadata);
            advanceJournalCursor(sidecar, end);
        } catch (JSONException e) {
            throw new IOException("Cannot finalize metadata; audio file retained", e);
        }
    }

    private void appendEvent(String type, Object detail) throws IOException {
        try {
            JSONObject entry = new JSONObject();
            entry.put("schema_version", 1);
            entry.put("epoch_ms", System.currentTimeMillis());
            entry.put("elapsed_ms", elapsedRealtime.getAsLong());
            entry.put("type", type);
            entry.put("detail", detail);
            // Preserve an interrupted final line, but separate the next complete JSON record.
            boolean separator = false;
            if (journal.length() > 0) try (RandomAccessFile previous = new RandomAccessFile(journal, "r")) {
                previous.seek(previous.length() - 1);
                separator = previous.read() != '\n';
            }
            try (FileOutputStream stream = new FileOutputStream(journal, true)) {
                if (separator) stream.write('\n');
                stream.write((entry.toString() + "\n").getBytes(StandardCharsets.UTF_8));
                stream.getFD().sync();
            }
        } catch (JSONException e) { throw new IOException("Cannot write event journal", e); }
    }

    private void writeJsonOnce(File target, JSONObject json) throws IOException, JSONException {
        if (target.exists()) throw new IOException("Metadata already exists; no files overwritten");
        File temporary = child(target.getName() + ".tmp-" + UUID.randomUUID());
        if (!temporary.createNewFile()) throw new IOException("Cannot create metadata file");
        try (FileOutputStream stream = new FileOutputStream(temporary)) {
            stream.write((json.toString(2) + "\n").getBytes(StandardCharsets.UTF_8));
            stream.getFD().sync();
        }
        if (target.exists() || !temporary.renameTo(target)) {
            throw new IOException("Cannot commit metadata; temporary metadata and audio retained");
        }
    }

    private static JSONObject readSmallJson(File file) throws IOException, JSONException {
        if (file.length() > 65536) throw new IOException("Oversized opening metadata");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        copyPrefix(file, file.length(), bytes);
        return new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
    }

    private JSONObject writeJournalSnapshot(File target, long start, long end)
            throws IOException, JSONException {
        if (start < 0 || end < start || end > journal.length()) {
            throw new IOException("Invalid journal snapshot range");
        }
        File temporary = child(target.getName() + ".tmp-" + UUID.randomUUID());
        try (RandomAccessFile source = new RandomAccessFile(journal, "r");
                FileOutputStream destination = new FileOutputStream(temporary)) {
            source.seek(start);
            byte[] buffer = new byte[65536];
            long remaining = end - start;
            while (remaining > 0) {
                int count = source.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (count < 0) throw new IOException("Journal was truncated during snapshot");
                destination.write(buffer, 0, count);
                remaining -= count;
            }
            destination.getFD().sync();
        }
        if (target.exists() || !temporary.renameTo(target)) {
            throw new IOException("Cannot commit immutable journal snapshot");
        }
        JSONObject snapshot = new JSONObject();
        snapshot.put("filename", target.getName());
        snapshot.put("source", "events.jsonl");
        snapshot.put("start_byte", start);
        snapshot.put("end_byte", end);
        snapshot.put("size_bytes", end - start);
        snapshot.put("sha256", sha256Prefix(target, end - start));
        return snapshot;
    }

    private File checkedJournalSnapshot(JSONObject snapshot) throws IOException, JSONException {
        String name = snapshot.getString("filename");
        if (!name.endsWith(".jsonl") || name.equals("events.jsonl")) {
            throw new IOException("Invalid immutable journal snapshot name");
        }
        long start = snapshot.getLong("start_byte");
        long end = snapshot.getLong("end_byte");
        File file = child(name);
        if (start < 0 || end < start || !file.isFile() || file.length() != end - start
                || snapshot.getLong("size_bytes") != end - start
                || !snapshot.getString("sha256").matches("[0-9a-f]{64}")) {
            throw new IOException("Invalid immutable journal snapshot: " + name);
        }
        return file;
    }

    /** The cursor is only an optimization. Any missing/corrupt anchor restarts from zero safely. */
    private long journalCursor() throws IOException {
        File cursor = child("journal-sync-cursor.json");
        if (!cursor.isFile()) return 0;
        try {
            JSONObject value = readSmallJson(cursor);
            File anchor = child(value.getString("sidecar"));
            JSONObject snapshot = readSmallJson(anchor).getJSONObject("journal_snapshot");
            checkedJournalSnapshot(snapshot);
            long end = snapshot.getLong("end_byte");
            return end == value.getLong("end_byte") && end <= journal.length() ? end : 0;
        } catch (IOException | JSONException | IllegalArgumentException corrupt) {
            return 0;
        }
    }

    private void advanceJournalCursor(File sidecar, long end) throws IOException, JSONException {
        JSONObject value = new JSONObject();
        value.put("schema_version", 1);
        value.put("sidecar", sidecar.getName());
        value.put("end_byte", end);
        File target = child("journal-sync-cursor.json");
        File temporary = child(target.getName() + ".tmp-" + UUID.randomUUID());
        try (FileOutputStream stream = new FileOutputStream(temporary)) {
            stream.write((value.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            stream.getFD().sync();
        }
        Files.move(temporary.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private File[] listFiles() throws IOException {
        File[] files = directory.listFiles();
        if (files == null) throw new IOException("Cannot list recording directory");
        return files;
    }

    private File child(String basename) throws IOException {
        validateBasename(basename);
        File child = new File(directory, basename).getCanonicalFile();
        if (!directory.equals(child.getParentFile())) throw new IOException("Path leaves recording directory");
        return child;
    }

    private File requireChild(File file) throws IOException {
        if (file == null) throw new IOException("Missing recording path");
        File canonical = file.getCanonicalFile();
        if (!directory.equals(canonical.getParentFile())) throw new IOException("Path leaves recording directory");
        validateBasename(canonical.getName());
        return canonical;
    }

    static void validateSessionId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,79}")) {
            throw new IllegalArgumentException("Invalid session identifier");
        }
    }

    static void validateBasename(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,239}") || value.contains("..")) {
            throw new IllegalArgumentException("Invalid recording filename");
        }
    }

    static String sha256Prefix(File file, long length) throws IOException {
        if (length < 0) throw new IllegalArgumentException("Negative snapshot length");
        MessageDigest digest = newDigest();
        copyPrefix(file, length, new OutputStream() {
            @Override public void write(int value) { digest.update((byte) value); }
            @Override public void write(byte[] data, int offset, int count) { digest.update(data, offset, count); }
        });
        return hex(digest.digest());
    }

    private static void copyPrefix(File file, long length, OutputStream destination) throws IOException {
        try (FileInputStream stream = new FileInputStream(file)) {
            byte[] buffer = new byte[65536];
            long remaining = length;
            while (remaining > 0) {
                int count = stream.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (count < 0) throw new IOException("Snapshot file was truncated: " + file.getName());
                destination.write(buffer, 0, count);
                remaining -= count;
            }
        }
    }

    private static MessageDigest newDigest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static String hex(byte[] bytes) {
        char[] alphabet = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            result[i * 2] = alphabet[(bytes[i] & 255) >>> 4];
            result[i * 2 + 1] = alphabet[bytes[i] & 15];
        }
        return new String(result);
    }

    private static Object nullableNonnegative(long value) { return value < 0 ? JSONObject.NULL : value; }

    private static final class ExportItem {
        final File file;
        final String zipName;
        final long length;
        ExportItem(File file, String zipName) {
            this.file = file;
            this.zipName = zipName;
            this.length = file.length();
        }
    }
}
