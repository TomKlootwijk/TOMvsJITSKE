package nl.privaterecorder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

public final class CloudLedgerTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private static final String HASH = "a".repeat(64);
    private static final String CHANGED = "b".repeat(64);
    private static final String SCOPE = "google|account-1|folder-1";
    private static final String KEY = "session_000001.aac";

    @Test public void verifiedReceiptSurvivesRestartAndIsScopedToAccountFolderAndContent() throws Exception {
        File directory = folder.newFolder();
        CloudLedger ledger = new CloudLedger(directory);
        assertFalse(ledger.isUploaded(SCOPE, KEY, HASH));
        ledger.markUploaded(SCOPE, KEY, HASH, new JSONObject().put("remote_id", "id-123"));
        CloudLedger restarted = new CloudLedger(directory);
        assertTrue(restarted.isUploaded(SCOPE, KEY, HASH));
        assertTrue(restarted.isUploaded(SCOPE, KEY, HASH.toUpperCase()));
        assertFalse(restarted.isUploaded("google|account-2|folder-1", KEY, HASH));
        assertFalse(restarted.isUploaded("google|account-1|folder-2", KEY, HASH));
        assertFalse(restarted.isUploaded("microsoft|account-1|folder-1", KEY, HASH));
        assertFalse(restarted.isUploaded(SCOPE, "different.aac", HASH));
        assertFalse(restarted.isUploaded(SCOPE, KEY, CHANGED));
    }

    @Test public void truncatedOrModifiedReceiptIsRetriedInsteadOfTrusted() throws Exception {
        File directory = folder.newFolder();
        CloudLedger ledger = new CloudLedger(directory);
        ledger.markUploaded(SCOPE, KEY, HASH, new JSONObject());
        File receipt = directory.listFiles()[0];
        JSONObject envelope = new JSONObject(new String(Files.readAllBytes(receipt.toPath()), StandardCharsets.UTF_8));
        envelope.put("payload", envelope.getString("payload").replace("account-1", "account-9"));
        Files.write(receipt.toPath(), envelope.toString().getBytes(StandardCharsets.UTF_8));
        assertFalse(ledger.isUploaded(SCOPE, KEY, HASH));
        Files.write(receipt.toPath(), "{\"schema_version\":".getBytes(StandardCharsets.UTF_8));
        assertFalse(new CloudLedger(directory).isUploaded(SCOPE, KEY, HASH));
        ledger.markUploaded(SCOPE, KEY, HASH, new JSONObject().put("retry", true));
        assertTrue(ledger.isUploaded(SCOPE, KEY, HASH));
    }

    @Test public void interruptedReplacementAndRejectedOversizedReceiptRetainCommittedReceipt() throws Exception {
        File directory = folder.newFolder();
        CloudLedger ledger = new CloudLedger(directory);
        ledger.markUploaded(SCOPE, KEY, HASH, new JSONObject());
        File committed = directory.listFiles()[0];
        Files.write(new File(directory, committed.getName() + ".tmp-interrupted").toPath(), "unfinished".getBytes(StandardCharsets.UTF_8));
        assertTrue(new CloudLedger(directory).isUploaded(SCOPE, KEY, HASH));
        JSONObject oversized = new JSONObject().put("detail", "x".repeat(1024 * 1024));
        assertThrows(IOException.class, () -> ledger.markUploaded(SCOPE, KEY, CHANGED, oversized));
        assertTrue(ledger.isUploaded(SCOPE, KEY, HASH));
        assertFalse(ledger.isUploaded(SCOPE, KEY, CHANGED));
    }

    @Test public void changingContentRequiresANewVerifiedReceipt() throws Exception {
        CloudLedger ledger = new CloudLedger(folder.newFolder());
        ledger.markUploaded(SCOPE, KEY, HASH, new JSONObject());
        ledger.markUploaded(SCOPE, KEY, CHANGED, new JSONObject());
        assertFalse(ledger.isUploaded(SCOPE, KEY, HASH));
        assertTrue(ledger.isUploaded(SCOPE, KEY, CHANGED));
    }

    @Test public void identitiesNeverBecomePathsAndAmbiguousIdentitiesAreRejected() throws Exception {
        File directory = folder.newFolder();
        CloudLedger ledger = new CloudLedger(directory);
        ledger.markUploaded("../../outside", "C:\\outside\\../name", HASH, new JSONObject());
        assertEquals(1, directory.listFiles().length);
        assertTrue(directory.listFiles()[0].getName().matches("[0-9a-f]{64}\\.json"));
        for (String invalid : Arrays.asList(null, "", "a\u0000b", "x".repeat(4097))) {
            assertThrows(IllegalArgumentException.class, () -> ledger.isUploaded(invalid, KEY, HASH));
            assertThrows(IllegalArgumentException.class, () -> ledger.markUploaded(SCOPE, invalid, HASH, new JSONObject()));
        }
        assertThrows(IllegalArgumentException.class, () -> ledger.isUploaded(SCOPE, KEY, "not-a-hash"));
        assertThrows(IllegalArgumentException.class, () -> ledger.markUploaded(SCOPE, KEY, HASH, null));
    }

    @Test public void temporaryReceiptAloneNeverCountsAsUploaded() throws Exception {
        File directory = folder.newFolder();
        CloudLedger ledger = new CloudLedger(directory);
        ledger.markUploaded(SCOPE, KEY, HASH, new JSONObject());
        File committed = directory.listFiles()[0];
        Files.move(committed.toPath(), new File(directory, committed.getName() + ".tmp-crash").toPath());
        assertFalse(new CloudLedger(directory).isUploaded(SCOPE, KEY, HASH));
    }
}
