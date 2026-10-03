package nl.privaterecorder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.json.JSONObject;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class EvidenceStoreTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test public void sessionIdentifiersCannotInjectPathsOrAmbiguousNames() {
        EvidenceStore.validateSessionId("20260925_a1-b2");
        for (String invalid : new String[]{null, "", "../escape", "a/b", "a\\b", ".hidden", "two words", "a:b", "é"}) {
            assertThrows(IllegalArgumentException.class, () -> EvidenceStore.validateSessionId(invalid));
        }
    }

    @Test public void archiveBasenamesRejectTraversalAndControlCharacters() {
        EvidenceStore.validateBasename("session_000001_1790341123000.interrupted.aac.json");
        for (String invalid : new String[]{"..", "a..b", "../x", "/x", "C:\\x", "x\nname", "x\u0000"}) {
            assertThrows(IllegalArgumentException.class, () -> EvidenceStore.validateBasename(invalid));
        }
    }

    @Test public void snapshotDigestIgnoresLaterJournalAppends() throws Exception {
        File file = folder.newFile();
        try (FileOutputStream stream = new FileOutputStream(file)) {
            stream.write("abc".getBytes(StandardCharsets.UTF_8));
        }
        String expected = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        assertEquals(expected, EvidenceStore.sha256Prefix(file, 3));
        try (FileOutputStream stream = new FileOutputStream(file, true)) {
            stream.write("later event\n".getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(expected, EvidenceStore.sha256Prefix(file, 3));
    }

    @Test public void truncationIsAnErrorInsteadOfAValidShorterSnapshot() throws Exception {
        File file = folder.newFile();
        try (FileOutputStream stream = new FileOutputStream(file)) { stream.write(new byte[]{1, 2}); }
        assertThrows(IOException.class, () -> EvidenceStore.sha256Prefix(file, 3));
        assertThrows(IllegalArgumentException.class, () -> EvidenceStore.sha256Prefix(file, -1));
    }

    @Test public void emptySnapshotHasStandardSha256() throws Exception {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                EvidenceStore.sha256Prefix(folder.newFile(), 0));
    }

    @Test public void syncSnapshotExcludesActiveAndUncommittedFilesAndPreservesLegacySegments() throws Exception {
        File directory = folder.newFolder();
        EvidenceStore store = new EvidenceStore(directory, () -> 123L);
        Files.write(new File(directory, "active.aac.partial").toPath(), "active bytes".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(directory, "renamed.aac").toPath(), "uncommitted bytes".getBytes(StandardCharsets.UTF_8));
        File legacy = new File(directory, "legacy.aac");
        Files.write(legacy.toPath(), "old completed bytes".getBytes(StandardCharsets.UTF_8));
        JSONObject metadata = new JSONObject().put("filename", legacy.getName())
                .put("sha256", EvidenceStore.sha256Prefix(legacy, legacy.length()))
                .put("size_bytes", legacy.length());
        Files.write(new File(directory, "legacy.aac.json").toPath(), metadata.toString().getBytes(StandardCharsets.UTF_8));
        Files.write(new File(directory, "renamed.aac.json.tmp-test").toPath(), metadata.toString().getBytes(StandardCharsets.UTF_8));
        EvidenceStore.SyncSnapshot snapshot = store.syncSnapshot();
        List<EvidenceStore.SyncBundle> bundles = snapshot.bundles;
        assertEquals(1, bundles.size());
        assertEquals("legacy.aac", bundles.get(0).key);
        assertEquals(2, bundles.get(0).files.size());
        assertEquals(1, snapshot.errors.size());
        assertThrows(UnsupportedOperationException.class, () -> bundles.clear());
        assertThrows(UnsupportedOperationException.class, () -> bundles.get(0).files.clear());
    }

    @Test public void segmentAndTerminalCheckpointCoverExactJournalRangesWithoutRepeatedFullCopies() throws Exception {
        File directory = folder.newFolder();
        EvidenceStore store = new EvidenceStore(directory, () -> 123L);
        store.event("session_start", "test");
        finishTestSegment(store, directory, "first");
        JSONObject first = readJson(new File(directory, "first.aac.json"));
        JSONObject firstRange = first.getJSONObject("journal_snapshot");
        assertEquals(0, firstRange.getLong("start_byte"));
        store.event("segment_open", "second");
        finishTestSegment(store, directory, "second");
        JSONObject secondRange = readJson(new File(directory, "second.aac.json")).getJSONObject("journal_snapshot");
        assertEquals(firstRange.getLong("end_byte"), secondRange.getLong("start_byte"));
        store.event("session_stop", "user");
        long beforeCheckpoint = new File(directory, "events.jsonl").length();
        store.createJournalCheckpoint();
        List<EvidenceStore.SyncBundle> bundles = store.syncBundles();
        assertEquals(3, bundles.size());
        EvidenceStore.SyncBundle checkpoint = bundles.stream().filter(b -> b.key.startsWith("journal_")).findFirst().get();
        JSONObject lastRange = readJson(checkpoint.files.get(1)).getJSONObject("journal_snapshot");
        assertEquals(secondRange.getLong("end_byte"), lastRange.getLong("start_byte"));
        assertEquals(beforeCheckpoint, lastRange.getLong("end_byte"));
        byte[] journal = Files.readAllBytes(new File(directory, "events.jsonl").toPath());
        for (JSONObject range : new JSONObject[]{firstRange, secondRange, lastRange}) {
            byte[] expected = java.util.Arrays.copyOfRange(journal,
                    (int) range.getLong("start_byte"), (int) range.getLong("end_byte"));
            File snapshot = new File(directory, range.getString("filename"));
            assertArrayEquals(expected, Files.readAllBytes(snapshot.toPath()));
            assertEquals(range.getString("sha256"), EvidenceStore.sha256Prefix(snapshot, snapshot.length()));
        }
        store.createJournalCheckpoint();
        assertEquals(3, store.syncBundles().size());
        assertEquals(beforeCheckpoint, new File(directory, "events.jsonl").length());
    }

    @Test public void corruptCursorDuplicatesEventsSafelyRatherThanSkippingThem() throws Exception {
        File directory = folder.newFolder();
        EvidenceStore store = new EvidenceStore(directory, () -> 123L);
        store.event("start", "test");
        finishTestSegment(store, directory, "first");
        Files.write(new File(directory, "journal-sync-cursor.json").toPath(), "corrupt cursor".getBytes(StandardCharsets.UTF_8));
        store.event("stop", "test");
        store.createJournalCheckpoint();
        EvidenceStore.SyncBundle checkpoint = store.syncBundles().stream()
                .filter(b -> b.key.startsWith("journal_")).findFirst().get();
        JSONObject range = readJson(checkpoint.files.get(1)).getJSONObject("journal_snapshot");
        assertEquals(0, range.getLong("start_byte"));
        assertArrayEquals(Files.readAllBytes(new File(directory, "events.jsonl").toPath()),
                Files.readAllBytes(checkpoint.files.get(0).toPath()));
    }

    @Test public void crashBeforeFinalSidecarIsRecoverableAndNeverAdvertisesAnIncompleteBundle() throws Exception {
        File directory = folder.newFolder();
        EvidenceStore store = new EvidenceStore(directory, () -> 123L);
        store.event("start", "test");
        finishTestSegment(store, directory, "first");
        Files.delete(new File(directory, "first.aac.json").toPath());
        assertTrue(store.syncSnapshot().bundles.isEmpty());
        store.recoverInterruptedSegments();
        EvidenceStore.SyncBundle recovered = store.syncBundles().get(0);
        assertEquals("first.aac", recovered.key);
        JSONObject metadata = readJson(new File(directory, "first.aac.json"));
        assertTrue(metadata.getBoolean("recovered_after_rename"));
        assertEquals("interrupted", metadata.getString("status"));
        assertEquals(0, metadata.getJSONObject("journal_snapshot").getLong("start_byte"));
    }

    @Test public void damagedCommittedJournalSnapshotFailsClosed() throws Exception {
        File directory = folder.newFolder();
        EvidenceStore store = new EvidenceStore(directory, () -> 123L);
        finishTestSegment(store, directory, "first");
        JSONObject range = readJson(new File(directory, "first.aac.json")).getJSONObject("journal_snapshot");
        Files.write(new File(directory, range.getString("filename")).toPath(), "truncated".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, store::syncBundles);
    }

    @Test public void missingOrCorruptOldSidecarDoesNotPreventSyncingOtherCompletedEvidence() throws Exception {
        File directory = folder.newFolder();
        EvidenceStore store = new EvidenceStore(directory, () -> 123L);
        finishTestSegment(store, directory, "good");
        finishTestSegment(store, directory, "corrupt");
        finishTestSegment(store, directory, "missing");
        Files.write(new File(directory, "corrupt.aac.json").toPath(), "broken JSON".getBytes(StandardCharsets.UTF_8));
        Files.delete(new File(directory, "missing.aac.json").toPath());
        EvidenceStore.SyncSnapshot snapshot = store.syncSnapshot();
        assertEquals(1, snapshot.bundles.size());
        assertEquals("good.aac", snapshot.bundles.get(0).key);
        assertEquals(2, snapshot.errors.size());
        assertTrue(snapshot.errors.stream().anyMatch(error -> error.contains("corrupt.aac")));
        assertTrue(snapshot.errors.stream().anyMatch(error -> error.contains("missing.aac")));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.errors.clear());
        assertThrows(IOException.class, store::syncBundles);
    }

    @Test public void exportIncludesCommittedJournalDeltaAndCoversEveryMemberWithItsManifest() throws Exception {
        File directory = folder.newFolder();
        EvidenceStore store = new EvidenceStore(directory, () -> 123L);
        store.event("session_start", "test");
        finishTestSegment(store, directory, "first");
        store.event("session_stop", "test");
        store.createJournalCheckpoint();
        Files.write(new File(directory, "active.aac.partial").toPath(), new byte[]{1, 2});
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        store.exportZip(output);
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(output.toByteArray()))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                ByteArrayOutputStream content = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                while ((count = zip.read(buffer)) != -1) content.write(buffer, 0, count);
                assertFalse(entries.containsKey(entry.getName()));
                entries.put(entry.getName(), content.toByteArray());
            }
        }
        JSONObject sidecar = new JSONObject(new String(entries.get("recordings/first.aac.json"), StandardCharsets.UTF_8));
        JSONObject delta = sidecar.getJSONObject("journal_snapshot");
        byte[] deltaBytes = entries.get("recordings/" + delta.getString("filename"));
        assertEquals(delta.getLong("size_bytes"), deltaBytes.length);
        assertEquals(delta.getString("sha256"), hexDigest(deltaBytes));
        assertArrayEquals(Files.readAllBytes(new File(directory, "events.jsonl").toPath()), entries.get("events.jsonl"));
        JSONObject export = new JSONObject(new String(entries.get("export_metadata.json"), StandardCharsets.UTF_8));
        assertEquals(1, export.getInt("schema_version"));
        assertEquals(1, export.getInt("finalized_segments"));
        assertEquals(1, export.getInt("excluded_partial_segments"));
        assertFalse(entries.containsKey("recordings/active.aac.partial"));
        String[] manifest = new String(entries.remove("SHA256SUMS"), StandardCharsets.UTF_8).split("\n");
        assertEquals(entries.size(), manifest.length);
        for (String line : manifest) {
            String[] fields = line.split("  ", 2);
            assertTrue(entries.containsKey(fields[1]));
            assertEquals(fields[0], hexDigest(entries.remove(fields[1])));
        }
        assertTrue(entries.isEmpty());
    }

    @Test public void pendingBundleVerificationDetectsSameSizeChangesToAudioJournalAndOpeningMetadata() throws Exception {
        File directory = folder.newFolder();
        EvidenceStore store = new EvidenceStore(directory, () -> 123L);
        File opening = new File(directory, "first.open.json");
        Files.write(opening.toPath(), "{\"start_epoch_ms\":123}".getBytes(StandardCharsets.UTF_8));
        finishTestSegment(store, directory, "first");
        EvidenceStore.SyncBundle bundle = store.syncBundles().get(0);
        bundle.verifyIntegrity();
        JSONObject metadata = readJson(new File(directory, "first.aac.json"));
        File events = new File(directory, metadata.getJSONObject("journal_snapshot").getString("filename"));
        for (File file : new File[]{new File(directory, "first.aac"), events, opening}) {
            byte[] original = Files.readAllBytes(file.toPath());
            Files.write(file.toPath(), new byte[original.length]);
            assertThrows(IOException.class, bundle::verifyIntegrity);
            Files.write(file.toPath(), original);
            bundle.verifyIntegrity();
        }
    }

    private static void finishTestSegment(EvidenceStore store, File directory, String base) throws Exception {
        File partial = new File(directory, base + ".aac.partial");
        Files.write(partial.toPath(), ("immutable test audio " + base).getBytes(StandardCharsets.UTF_8));
        store.finishSegment(partial, 500, 400, 300, true);
    }

    private static JSONObject readJson(File file) throws Exception {
        return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }

    private static String hexDigest(byte[] content) throws Exception {
        StringBuilder text = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(content)) {
            text.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        }
        return text.toString();
    }
}
