package nl.privaterecorder;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class BundleIdentityTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private File file(String name, String text) throws Exception {
        File result = temporary.newFile(name);
        Files.write(result.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return result;
    }
    @Test public void independentOfEnumerationOrder() throws Exception {
        File audio = file("audio.aac", "audio"), meta = file("audio.json", "metadata");
        assertEquals(BundleIdentity.digest(Arrays.asList(audio, meta)), BundleIdentity.digest(Arrays.asList(meta, audio)));
    }
    @Test public void includesMetadataAndJournalChanges() throws Exception {
        File audio = file("audio.aac", "audio"), meta = file("audio.json", "before");
        String before = BundleIdentity.digest(Arrays.asList(audio, meta));
        Files.write(meta.toPath(), "after".getBytes(StandardCharsets.UTF_8));
        assertNotEquals(before, BundleIdentity.digest(Arrays.asList(audio, meta)));
        File journal = file("events.jsonl", "event");
        assertNotEquals(before, BundleIdentity.digest(Arrays.asList(audio, meta, journal)));
    }
    @Test public void includesNamesAndRejectsDuplicateOrMissingFiles() throws Exception {
        File one = file("one.aac", "same"), two = file("two.aac", "same");
        assertNotEquals(BundleIdentity.digest(Collections.singletonList(one)), BundleIdentity.digest(Collections.singletonList(two)));
        assertThrows(java.io.IOException.class, () -> BundleIdentity.digest(Arrays.asList(one, one)));
        assertThrows(java.io.IOException.class, () -> BundleIdentity.digest(Collections.emptyList()));
        assertThrows(java.io.IOException.class, () -> BundleIdentity.digest(Collections.singletonList(new File(temporary.getRoot(), "missing"))));
    }
}
