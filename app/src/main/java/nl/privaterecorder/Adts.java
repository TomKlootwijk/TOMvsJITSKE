package nl.privaterecorder;

/** ADTS header for one AAC-LC access unit, 48 kHz, mono. */
final class Adts {
    private Adts() {}

    static byte[] header(int payloadBytes) {
        if (payloadBytes <= 0 || payloadBytes > 8184) {
            throw new IllegalArgumentException("Invalid AAC frame length: " + payloadBytes);
        }
        int length = payloadBytes + 7;
        return new byte[] {
                (byte) 0xff, (byte) 0xf1,
                (byte) ((1 << 6) | (3 << 2)), // AAC LC profile index 1; 48 kHz index 3
                (byte) ((1 << 6) | (length >> 11)), // mono channel configuration
                (byte) (length >> 3),
                (byte) (((length & 7) << 5) | 0x1f),
                (byte) 0xfc
        };
    }
}
