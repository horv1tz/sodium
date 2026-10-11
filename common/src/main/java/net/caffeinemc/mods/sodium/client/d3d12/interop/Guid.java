package net.caffeinemc.mods.sodium.client.d3d12.interop;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Helper for 16-byte GUIDs / IIDs in their in-memory Windows layout (Data1 u32, Data2 u16, Data3 u16, Data4 u8[8]).
 */
public final class Guid {
    public static final long SIZE = 16;

    private Guid() {
    }

    /**
     * Creates a process-lifetime GUID from its canonical text form, e.g. "189819f1-1db6-4b57-be54-1821339b85f7".
     * The segment is allocated once and is meant to be stored in a static final field.
     */
    public static MemorySegment of(String text) {
        String[] parts = text.split("-");

        if (parts.length != 5 || parts[0].length() != 8 || parts[1].length() != 4 || parts[2].length() != 4
                || parts[3].length() != 4 || parts[4].length() != 12) {
            throw new IllegalArgumentException("Malformed GUID: " + text);
        }

        MemorySegment segment = Arena.global().allocate(SIZE, 8);
        segment.set(ValueLayout.JAVA_INT_UNALIGNED, 0, (int) Long.parseLong(parts[0], 16));
        segment.set(ValueLayout.JAVA_SHORT_UNALIGNED, 4, (short) Integer.parseInt(parts[1], 16));
        segment.set(ValueLayout.JAVA_SHORT_UNALIGNED, 6, (short) Integer.parseInt(parts[2], 16));

        String tail = parts[3] + parts[4];

        for (int i = 0; i < 8; i++) {
            segment.set(ValueLayout.JAVA_BYTE, 8 + i, (byte) Integer.parseInt(tail.substring(i * 2, i * 2 + 2), 16));
        }

        return segment;
    }

    /**
     * Formats a GUID segment back to its canonical lowercase text form (for logging and tests).
     */
    public static String toString(MemorySegment guid) {
        StringBuilder sb = new StringBuilder(36);
        sb.append(String.format("%08x-%04x-%04x-", guid.get(ValueLayout.JAVA_INT_UNALIGNED, 0),
                guid.get(ValueLayout.JAVA_SHORT_UNALIGNED, 4) & 0xFFFF, guid.get(ValueLayout.JAVA_SHORT_UNALIGNED, 6) & 0xFFFF));

        for (int i = 0; i < 8; i++) {
            if (i == 2) {
                sb.append('-');
            }

            sb.append(String.format("%02x", guid.get(ValueLayout.JAVA_BYTE, 8 + i) & 0xFF));
        }

        return sb.toString();
    }
}
