package org.lightsolutions.slcan.canbus;

import java.nio.charset.StandardCharsets;

/**
 * SLCAN ASCII version response.
 * @param versionString the version string
 */
public record CanVersion(String versionString) {

    private static final byte[] COMMAND_BYTES = new byte[] { 'V', '\r' };

    /**
     * Decodes a raw SLCAN ASCII frame into a {@link CanVersion}.
     * @param message raw SLCAN frame bytes (without transport metadata)
     * @return decoded frame, or {@code null} when input is null/empty
     */
    public static CanVersion decode(byte[] message) {
        if (message == null || message.length < 1) {
            return null;
        }

        byte type = message[0];
        if (type != 'V' && type != 'v') {
            throw new IllegalArgumentException("Not a version response: " + (char) type);
        }

        if (message.length == 1) {
            return new CanVersion("Unknown");
        }

        final String parsedVersion = new String(message, 1, message.length - 1, StandardCharsets.US_ASCII);

        return new CanVersion(parsedVersion);
    }

    /**
     * Encodes this frame as a raw SLCAN ASCII byte array terminated with CR.
     * @return encoded frame bytes
     */
    public byte[] encodeAsBytes() {
        return COMMAND_BYTES;
    }
}