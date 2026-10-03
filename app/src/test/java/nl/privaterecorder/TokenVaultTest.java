package nl.privaterecorder;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.junit.Test;

public final class TokenVaultTest {
    private SecretKey key() throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        return generator.generateKey();
    }

    @Test public void oauthStateRoundTripsWithoutPlaintext() throws Exception {
        SecretKey key = key();
        byte[] state = "{\"refreshToken\":\"private-token-é\"}".getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = TokenVault.encrypt(key, "microsoft", state);
        assertArrayEquals(state, TokenVault.decrypt(key, "microsoft", encrypted));
        assertFalse(new String(encrypted, StandardCharsets.ISO_8859_1).contains("private-token"));
        assertFalse(Arrays.equals(encrypted, TokenVault.encrypt(key, "microsoft", state)));
    }

    @Test public void modifiedCiphertextCannotBeAccepted() throws Exception {
        SecretKey key = key();
        byte[] encoded = TokenVault.encrypt(key, "microsoft", new byte[]{1, 2, 3});
        encoded[encoded.length - 1] ^= 1;
        assertThrows(GeneralSecurityException.class, () -> TokenVault.decrypt(key, "microsoft", encoded));
    }

    @Test public void tokensCannotBeMovedBetweenSlotsOrDevices() throws Exception {
        SecretKey key = key();
        byte[] encoded = TokenVault.encrypt(key, "microsoft", new byte[]{1, 2, 3});
        assertThrows(GeneralSecurityException.class, () -> TokenVault.decrypt(key, "google", encoded));
        SecretKey differentKey = key();
        assertThrows(GeneralSecurityException.class, () -> TokenVault.decrypt(differentKey, "microsoft", encoded));
    }

    @Test public void corruptHeadersAndTruncatedFilesAreRejected() throws Exception {
        SecretKey key = key();
        for (byte[] value : new byte[][] {null, new byte[0], new byte[29], new byte[50]}) {
            assertThrows(GeneralSecurityException.class, () -> TokenVault.decrypt(key, "google", value));
        }
        byte[] encoded = TokenVault.encrypt(key, "google", new byte[]{1});
        encoded[1] = 13;
        assertThrows(GeneralSecurityException.class, () -> TokenVault.decrypt(key, "google", encoded));
    }
}
