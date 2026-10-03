package nl.privaterecorder;

import static org.junit.Assert.*;
import org.junit.Test;

public final class CloudAuthTest {
    @Test public void redirectRequiresExactOriginAndPath() {
        assertTrue(CloudAuth.validMicrosoftRedirect("nl.privaterecorder://oauth/microsoft?code=abc&state=xyz"));
        for (String invalid : new String[] {null, "", "https://oauth/microsoft", "nl.privaterecorder://oauth.evil/microsoft",
                "nl.privaterecorder://oauth/microsoft/", "nl.privaterecorder://oauth/../microsoft",
                "nl.privaterecorder://oauth/microsoft#state=xyz", "nl.privaterecorder://user@oauth/microsoft",
                "nl.privaterecorder://oauth:443/microsoft", "nl.privaterecorder://oauth/%6dicrosoft"}) {
            assertFalse("Accepted redirect: " + invalid, CloudAuth.validMicrosoftRedirect(invalid));
        }
    }

    @Test public void pendingRequestExpiresOnBothClocks() {
        assertTrue(CloudAuth.isFresh(1000000, 100, 1001000, 1100, 600000));
        assertFalse(CloudAuth.isFresh(1000000, 100, 1600001, 200, 600000));
        assertFalse(CloudAuth.isFresh(1000000, 100, 1000001, 600101, 600000));
        assertFalse(CloudAuth.isFresh(1000000, 100, 999999, 200, 600000));
        assertFalse(CloudAuth.isFresh(1000000, 100, 1000001, 1, 600000));
        assertFalse(CloudAuth.isFresh(0, 0, 1, 1, 600000));
    }

    @Test public void stateMustBePresentAndExact() {
        assertTrue(CloudAuth.sameSecret("a123", "a123"));
        assertFalse(CloudAuth.sameSecret(null, null));
        assertFalse(CloudAuth.sameSecret("", ""));
        assertFalse(CloudAuth.sameSecret("a123", "a124"));
        assertFalse(CloudAuth.sameSecret("a123", "a123 "));
    }

    @Test public void onlyCanonicalPublicClientIdentifiersAreAccepted() {
        assertEquals("01234567-89ab-cdef-1234-567890abcdef", CloudAuth.normalizeMicrosoftClientId(" 01234567-89AB-CDEF-1234-567890ABCDEF "));
        for (String value : new String[]{null, "", "client-secret", "00000000-0000-0000-0000-000000000000", "1-1-1-1-1", "https://example.com"}) {
            assertThrows(IllegalArgumentException.class, () -> CloudAuth.normalizeMicrosoftClientId(value));
        }
    }

    @Test public void scopeValidationDoesNotAcceptPartialPermissionNames() {
        assertTrue(CloudAuth.hasMicrosoftScope("offline_access https://graph.microsoft.com/Files.ReadWrite.AppFolder"));
        assertTrue(CloudAuth.hasMicrosoftScope("Files.ReadWrite.AppFolder"));
        assertFalse(CloudAuth.hasMicrosoftScope("Files.ReadWrite.AppFolderFake"));
        assertFalse(CloudAuth.hasMicrosoftScope("Files.ReadWrite"));
        assertFalse(CloudAuth.hasMicrosoftScope(null));
    }
}
