// Owner: WP1 (device core) - fully implemented shared helper
package net.caffeinemc.mods.sodium.client.d3d12.core;

/**
 * Alignment constants of the Direct3D 12 specification and the arithmetic every package needs.
 *
 * <p>Pure logic, no native access, safe on every platform. Thread-safe (stateless).
 */
public final class Alignment {
    /** {@code D3D12_TEXTURE_DATA_PITCH_ALIGNMENT}: every row pitch of a buffer/texture copy footprint is a multiple of this. */
    public static final int TEXTURE_ROW_PITCH = 256;

    /** {@code D3D12_TEXTURE_DATA_PLACEMENT_ALIGNMENT}: the offset of a copy footprint inside a buffer is a multiple of this. */
    public static final int TEXTURE_PLACEMENT = 512;

    /** {@code D3D12_CONSTANT_BUFFER_DATA_PLACEMENT_ALIGNMENT}: GPU virtual addresses of root CBVs. */
    public static final int CONSTANT_BUFFER = 256;

    /** {@code D3D12_DEFAULT_RESOURCE_PLACEMENT_ALIGNMENT}: committed resources start at a multiple of this. */
    public static final long RESOURCE_PLACEMENT = 65536L;

    /**
     * Extra bytes appended to every buffer that can be read through a root CBV. A root CBV has no range, so a slice
     * close to the end of a buffer is still read for the whole declared cbuffer size (at most 64 KiB).
     */
    public static final long UNIFORM_TAIL_PAD = 65536L;

    private Alignment() {
    }

    /**
     * Rounds {@code value} up to a multiple of {@code alignment}.
     *
     * @param alignment any positive value (powers of two take a fast path)
     * @throws ArithmeticException if the rounded value does not fit a {@code long} (a wrapped, negative size must never
     *                             reach a resource description)
     */
    public static long alignUp(long value, long alignment) {
        if (alignment <= 0) {
            throw new IllegalArgumentException("alignment must be positive: " + alignment);
        }

        long remainder = (alignment & (alignment - 1)) == 0 ? value & (alignment - 1) : Math.floorMod(value, alignment);

        return remainder == 0L ? value : Math.addExact(value, alignment - remainder);
    }

    /** Integer overload of {@link #alignUp(long, long)}; the caller guarantees that the result fits in an int. */
    public static int alignUp(int value, int alignment) {
        return Math.toIntExact(alignUp((long) value, (long) alignment));
    }

    /** @return the row pitch of a copy footprint holding rows of {@code rowBytes} bytes (a multiple of 256) */
    public static int rowPitch(int rowBytes) {
        return alignUp(rowBytes, TEXTURE_ROW_PITCH);
    }

    public static boolean isAligned(long value, long alignment) {
        return value % alignment == 0;
    }

    /** @return true if {@code value} is a power of two (and positive) */
    public static boolean isPowerOfTwo(long value) {
        return value > 0 && (value & (value - 1)) == 0;
    }

    /** @return the smallest power of two that is {@code >= value} (value must be in {@code 1..2^62}) */
    public static long ceilPowerOfTwo(long value) {
        if (value <= 1) {
            return 1;
        }

        return 1L << (64 - Long.numberOfLeadingZeros(value - 1));
    }
}
