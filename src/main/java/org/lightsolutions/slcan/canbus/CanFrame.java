package org.lightsolutions.slcan.canbus;

import java.nio.charset.StandardCharsets;

/**
 * Immutable CAN/CAN-FD frame model with SLCAN byte-level encode/decode helpers.
 */
public record CanFrame(
        int id,
        int dlc,
        byte[] data,
        boolean isExtended,
        boolean isRemote,
        boolean isFd,
        boolean isBrs
) {

    /** Maps DLC value (0-15) to payload length in bytes for CAN-FD. */
    private static final int[] DLC_TO_LENGTH = {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 12, 16, 20, 24, 32, 48, 64
    };

    /** Lookup table used for fast hex encoding. */
    private static final byte[] HEX_ARRAY = "0123456789ABCDEF".getBytes(StandardCharsets.US_ASCII);

    /**
     * Decodes a raw SLCAN ASCII frame into a {@link CanFrame}.
     *
     * @param message raw SLCAN frame bytes (without transport metadata)
     * @return decoded frame, or {@code null} when input is null/empty
     * @throws IllegalArgumentException when frame type/format is invalid
     */
    public static CanFrame decode(final byte[] message) {
        if (message == null || message.length == 0) {
            return null;
        }

        final byte type = message[0];
        boolean extended = false;
        boolean remote = false;
        boolean fd = false;
        boolean brs = false;
        int idLength = 3;

        switch (type) {
            case 't':
                break;
            case 'T':
                extended = true;
                idLength = 8;
                break;
            case 'r':
                remote = true;
                break;
            case 'R':
                extended = true;
                remote = true;
                idLength = 8;
                break;
            case 'd':
                fd = true;
                break;
            case 'D':
                extended = true;
                fd = true;
                idLength = 8;
                break;
            case 'b':
                fd = true;
                brs = true;
                break;
            case 'B':
                extended = true;
                fd = true;
                brs = true;
                idLength = 8;
                break;
            default:
                throw new IllegalArgumentException("Unknown CAN frame type: " + (char) type);
        }

        int id = 0;
        for (int i = 1; i <= idLength; i++) {
            if (i >= message.length) {
                throw new IllegalArgumentException("Frame truncated in ID section");
            }
            id = (id << 4) | hexCharToInt(message[i]);
        }

        final int dlcIndex = 1 + idLength;
        if (dlcIndex >= message.length) {
            throw new IllegalArgumentException("Frame truncated before DLC");
        }

        final int dlcHex = hexCharToInt(message[dlcIndex]);
        final int actualDataLength = DLC_TO_LENGTH[dlcHex];
        final byte[] payload = new byte[actualDataLength];

        if (!remote) {
            final int dataStart = dlcIndex + 1;
            for (int i = 0; i < actualDataLength; i++) {
                final int hexIndex = dataStart + (i * 2);
                if (hexIndex + 1 >= message.length) {
                    break;
                }

                final int highNibble = hexCharToInt(message[hexIndex]);
                final int lowNibble = hexCharToInt(message[hexIndex + 1]);
                payload[i] = (byte) ((highNibble << 4) | lowNibble);
            }
        }

        return new CanFrame(id, dlcHex, payload, extended, remote, fd, brs);
    }

    /**
     * Converts a single ASCII hex character to its integer value.
     *
     * @param b ASCII byte for 0-9, A-F, or a-f
     * @return hex value in range 0-15
     * @throws IllegalArgumentException when the character is not valid hex
     */
    private static int hexCharToInt(final byte b) {
        if (b >= '0' && b <= '9') {
            return b - '0';
        }
        if (b >= 'A' && b <= 'F') {
            return b - 'A' + 10;
        }
        if (b >= 'a' && b <= 'f') {
            return b - 'a' + 10;
        }
        throw new IllegalArgumentException("Invalid HEX character: " + (char) b);
    }

    /**
     * Encodes this frame as a raw SLCAN ASCII byte array terminated with CR.
     *
     * @return encoded frame bytes
     */
    public byte[] encodeAsBytes() {
        final byte cmd;
        if (isFd) {
            cmd = (byte) (isBrs ? (isExtended ? 'B' : 'b') : (isExtended ? 'D' : 'd'));
        } else if (isRemote) {
            cmd = (byte) (isExtended ? 'R' : 'r');
        } else {
            cmd = (byte) (isExtended ? 'T' : 't');
        }

        final int idLength = isExtended ? 8 : 3;
        final int dataHexLength = isRemote || data == null ? 0 : (data.length * 2);
        final int totalLength = 1 + idLength + 1 + dataHexLength + 1;

        final byte[] buffer = new byte[totalLength];
        int pos = 0;

        buffer[pos++] = cmd;

        int idShift = (idLength - 1) * 4;
        for (int i = 0; i < idLength; i++) {
            buffer[pos++] = HEX_ARRAY[(id >> idShift) & 0x0F];
            idShift -= 4;
        }

        buffer[pos++] = HEX_ARRAY[dlc & 0x0F];

        if (!isRemote && data != null) {
            for (final byte b : data) {
                buffer[pos++] = HEX_ARRAY[(b >> 4) & 0x0F];
                buffer[pos++] = HEX_ARRAY[b & 0x0F];
            }
        }

        buffer[pos] = '\r';
        return buffer;
    }
}
