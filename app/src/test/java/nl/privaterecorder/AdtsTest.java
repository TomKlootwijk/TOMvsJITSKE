package nl.privaterecorder;

import static org.junit.Assert.*;
import org.junit.Test;

public class AdtsTest {
    @Test public void headerDescribesEverySupportedFrameLength() {
        for (int size = 1; size <= 8184; size++) {
            byte[] h = Adts.header(size);
            assertEquals(0xff, h[0] & 0xff);
            assertEquals(0xf1, h[1] & 0xff);
            assertEquals(1, (h[2] >> 6) & 3); // AAC-LC
            assertEquals(3, (h[2] >> 2) & 15); // 48 kHz
            assertEquals(1, ((h[2] & 1) << 2) | ((h[3] >> 6) & 3));
            int length = ((h[3] & 3) << 11) | ((h[4] & 255) << 3) | ((h[5] & 255) >> 5);
            assertEquals(size + 7, length);
            assertEquals(0, h[6] & 3); // One AAC block per ADTS frame
        }
    }

    @Test public void rejectsLengthsThatWouldCorruptTheStream() {
        for (int value : new int[] { -1, 0, 8185, Integer.MAX_VALUE }) {
            assertThrows(IllegalArgumentException.class, () -> Adts.header(value));
        }
    }
}
