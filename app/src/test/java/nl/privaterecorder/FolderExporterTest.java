package nl.privaterecorder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FolderExporterTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void copiesIndividualFilesAndIncrementalRetryVerifiesInsteadOfOverwriting() throws Exception {
        FolderExporter.SourceBundle bundle = bundle("first", "audio bytes");
        MemoryDestination destination = new MemoryDestination();
        FolderExporter.Result first = export(Collections.singletonList(bundle), destination);
        assertEquals(2, first.copiedFiles);
        assertEquals(1, first.audioSegments);
        assertEquals(0, first.errorCount);
        assertNotNull(first.manifestName);
        assertArrayEquals(bytes("audio bytes"), destination.byName("first.aac"));
        String manifest = new String(destination.byName(first.manifestName), StandardCharsets.UTF_8);
        assertTrue(manifest.contains("  first.aac\n"));
        assertTrue(manifest.contains("  first.aac.json\n"));
        int writesBefore = destination.writeCount;
        FolderExporter.Result second = export(Collections.singletonList(bundle), destination);
        assertEquals(0, second.copiedFiles);
        assertEquals(2, second.reusedFiles);
        assertEquals(1, second.audioSegments);
        assertEquals(writesBefore + 1, destination.writeCount); // Only the new run's manifest.
        assertFalse(first.manifestName.equals(second.manifestName));
        assertFalse(destination.list().stream().anyMatch(entry -> entry.name.startsWith("Pending_")));
        assertTrue(bundle.files.stream().allMatch(File::isFile));
    }

    @Test public void conflictingFilesAreUntouchedWhileOtherBundlesStillExport() throws Exception {
        FolderExporter.SourceBundle conflict = bundle("first", "original recording");
        FolderExporter.SourceBundle good = bundle("second", "another recording");
        MemoryDestination destination = new MemoryDestination();
        destination.seed("first.aac", bytes("different existing file"));
        FolderExporter.Result result = export(Arrays.asList(conflict, good), destination);
        assertEquals(1, result.errorCount);
        assertEquals(1, result.audioSegments);
        assertArrayEquals(bytes("different existing file"), destination.byName("first.aac"));
        assertArrayEquals(bytes("another recording"), destination.byName("second.aac"));
        String manifest = new String(destination.byName(result.manifestName), StandardCharsets.UTF_8);
        assertFalse(manifest.contains("first.aac"));
        assertTrue(manifest.contains("second.aac"));
    }

    @Test public void interruptedCopyRetainsPendingBytesAndNextRunCanComplete() throws Exception {
        FolderExporter.SourceBundle bundle = bundle("first", "audio bytes");
        MemoryDestination destination = new MemoryDestination();
        destination.failNextWrite = true;
        FolderExporter.Result interrupted = export(Collections.singletonList(bundle), destination);
        assertEquals(1, interrupted.errorCount);
        assertEquals(0, interrupted.verifiedBundles);
        FolderExporter.Entry pending = destination.list().get(0);
        byte[] retained = destination.payloads.get(pending.id);
        assertTrue(pending.name.startsWith("Pending_"));
        assertFalse(destination.hasName("first.aac"));
        FolderExporter.Result retried = export(Collections.singletonList(bundle), destination);
        assertEquals(0, retried.errorCount);
        assertEquals(1, retried.audioSegments);
        assertArrayEquals(retained, destination.payloads.get(pending.id));
        assertArrayEquals(bytes("audio bytes"), destination.byName("first.aac"));
    }

    @Test public void changedReadBackNeverPublishesACompletedFileOrManifest() throws Exception {
        FolderExporter.SourceBundle bundle = bundle("first", "audio bytes");
        MemoryDestination destination = new MemoryDestination();
        destination.corruptReads = true;
        FolderExporter.Result result = export(Collections.singletonList(bundle), destination);
        assertEquals(1, result.errorCount);
        assertEquals(0, result.verifiedBundles);
        assertEquals(null, result.manifestName);
        assertFalse(destination.hasName("first.aac"));
    }

    @Test public void caseCollisionsAndDuplicateProviderNamesAreNotOverwritten() throws Exception {
        for (boolean duplicate : new boolean[]{false, true}) {
            FolderExporter.SourceBundle bundle = bundle(duplicate ? "duplicate" : "case", "bytes");
            MemoryDestination destination = new MemoryDestination();
            String name = bundle.files.get(0).getName();
            destination.seed(duplicate ? name : name.toUpperCase(java.util.Locale.ROOT), bytes("bytes"));
            if (duplicate) destination.seed(name, bytes("bytes"));
            FolderExporter.Result result = export(Collections.singletonList(bundle), destination);
            assertEquals(1, result.errorCount);
            assertEquals(0, destination.writeCount);
            assertEquals(0, result.audioSegments);
        }
    }

    @Test public void sourceMutationDuringExportWithholdsManifestForThatBundle() throws Exception {
        FolderExporter.SourceBundle bundle = bundle("first", "audio bytes");
        MemoryDestination destination = new MemoryDestination();
        destination.afterFirstRename = () -> Files.write(bundle.files.get(0).toPath(), bytes("other bytes"));
        FolderExporter.Result result = export(Collections.singletonList(bundle), destination);
        assertEquals(1, result.errorCount);
        assertEquals(0, result.verifiedBundles);
        assertEquals(null, result.manifestName);
        assertArrayEquals(bytes("audio bytes"), destination.byName("first.aac"));
    }

    @Test public void filesAppearingDuringCopyArePreservedInsteadOfRenamedOver() throws Exception {
        FolderExporter.SourceBundle bundle = bundle("first", "audio bytes");
        MemoryDestination destination = new MemoryDestination();
        destination.afterFirstWrite = () -> destination.seed("first.aac", bytes("concurrent existing file"));
        FolderExporter.Result result = export(Collections.singletonList(bundle), destination);
        assertEquals(1, result.errorCount);
        assertArrayEquals(bytes("concurrent existing file"), destination.byName("first.aac"));
        assertTrue(destination.list().stream().anyMatch(entry -> entry.name.startsWith("Pending_")));
    }

    private FolderExporter.Result export(List<FolderExporter.SourceBundle> bundles, MemoryDestination destination) throws Exception {
        return FolderExporter.exportBundles(bundles, Collections.emptyList(), destination, temporary.newFolder(), null);
    }

    private FolderExporter.SourceBundle bundle(String name, String text) throws Exception {
        File directory = temporary.newFolder();
        File audio = new File(directory, name + ".aac");
        File sidecar = new File(directory, name + ".aac.json");
        Files.write(audio.toPath(), bytes(text));
        Files.write(sidecar.toPath(), bytes("{\"test\":true}"));
        String original = EvidenceStore.sha256Prefix(audio, audio.length());
        return new FolderExporter.SourceBundle(audio.getName(), Arrays.asList(audio, sidecar), () -> {
            if (!original.equals(EvidenceStore.sha256Prefix(audio, audio.length()))) throw new IOException("Source changed");
        });
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private interface Action { void run() throws IOException; }

    private static final class MemoryDestination implements FolderExporter.Destination {
        final Map<String, FolderExporter.Entry> entries = new LinkedHashMap<>();
        final Map<String, byte[]> payloads = new LinkedHashMap<>();
        int nextId, writeCount;
        boolean failNextWrite, corruptReads;
        Action afterFirstRename, afterFirstWrite;

        void seed(String name, byte[] bytes) {
            String id = Integer.toString(nextId++);
            entries.put(id, new FolderExporter.Entry(id, name, false, true));
            payloads.put(id, bytes.clone());
        }
        boolean hasName(String name) { return entries.values().stream().anyMatch(entry -> entry.name.equals(name)); }
        byte[] byName(String name) {
            for (FolderExporter.Entry entry : entries.values()) if (entry.name.equals(name)) return payloads.get(entry.id);
            throw new AssertionError("Missing destination file: " + name);
        }
        @Override public List<FolderExporter.Entry> list() { return new ArrayList<>(entries.values()); }
        @Override public InputStream read(FolderExporter.Entry entry) {
            byte[] bytes = payloads.get(entry.id).clone();
            if (corruptReads && bytes.length > 0) bytes[0] ^= 1;
            return new ByteArrayInputStream(bytes);
        }
        @Override public FolderExporter.Entry create(String name, String mime) {
            seed(name, new byte[0]);
            return entries.get(Integer.toString(nextId - 1));
        }
        @Override public OutputStream writeNew(FolderExporter.Entry entry) {
            writeCount++;
            boolean fail = failNextWrite;
            failNextWrite = false;
            return new ByteArrayOutputStream() {
                @Override public void write(byte[] bytes, int offset, int count) {
                    super.write(bytes, offset, fail ? Math.min(1, count) : count);
                }
                @Override public void close() throws IOException {
                    payloads.put(entry.id, toByteArray());
                    if (afterFirstWrite != null) {
                        Action action = afterFirstWrite;
                        afterFirstWrite = null;
                        action.run();
                    }
                    if (fail) throw new IOException("Simulated interrupted write");
                }
            };
        }
        @Override public FolderExporter.Entry rename(FolderExporter.Entry entry, String name) throws IOException {
            if (hasName(name)) throw new IOException("Destination name exists");
            FolderExporter.Entry renamed = new FolderExporter.Entry(entry.id, name, false, true);
            entries.put(entry.id, renamed);
            if (afterFirstRename != null) {
                Action action = afterFirstRename;
                afterFirstRename = null;
                action.run();
            }
            return renamed;
        }
    }
}
