// Owner: WP1 (device core) - fully implemented shared helper
package net.caffeinemc.mods.sodium.client.d3d12.core;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;

/**
 * Row-wise copies between tightly packed images and pitch-aligned footprints (CPU side of the upload path).
 *
 * <p>Used by {@code CopyEngine.writeToTexture} (tightly packed {@link ByteBuffer} into an UPLOAD arena slice). It must
 * never be used to <em>read</em> UPLOAD memory: that memory is write-combined and reading it is extremely slow.
 */
public final class RowRepack {
    private RowRepack() {
    }

    /**
     * Copies {@code rows} rows of {@code rowBytes} bytes from {@code src} (rows {@code srcStride} bytes apart,
     * starting at byte {@code srcOffset}) to {@code dst} (rows {@code dstStride} bytes apart, starting at
     * {@code dstOffset}). Padding bytes between destination rows are left untouched.
     *
     * <p>One bulk copy is used when both strides equal {@code rowBytes}.
     *
     * @throws IndexOutOfBoundsException if either segment is too small for the requested rows
     */
    public static void copyRows(MemorySegment src, long srcOffset, long srcStride,
                                MemorySegment dst, long dstOffset, long dstStride,
                                int rowBytes, int rows) {
        if (rows <= 0 || rowBytes <= 0) {
            return;
        }

        if (srcStride == rowBytes && dstStride == rowBytes) {
            MemorySegment.copy(src, srcOffset, dst, dstOffset, (long) rowBytes * rows);
            return;
        }

        for (int row = 0; row < rows; row++) {
            MemorySegment.copy(src, srcOffset + row * srcStride, dst, dstOffset + row * dstStride, rowBytes);
        }
    }

    /**
     * Copies a tightly packed image that starts at {@code src.position()} into a pitch-aligned footprint. The buffer
     * position and limit are not modified; heap and direct buffers of either byte order are accepted (the bytes are
     * moved raw).
     *
     * @throws IllegalArgumentException if fewer than {@code layout.rowBytes * layout.rows} bytes remain
     */
    public static void copyPacked(ByteBuffer src, MemorySegment dst, long dstOffset, CopyLayout layout) {
        long needed = (long) layout.rowBytes() * layout.rows();

        if (src.remaining() < needed) {
            throw new IllegalArgumentException("source holds " + src.remaining() + " bytes but the image needs " + needed);
        }

        MemorySegment source = MemorySegment.ofBuffer(src);
        copyRows(source, 0L, layout.rowBytes(), dst, dstOffset, layout.rowPitch(), layout.rowBytes(), layout.rows());
    }
}
