// Owner: WP1 (device core) - fully implemented
package net.caffeinemc.mods.sodium.client.d3d12.resource;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import net.caffeinemc.mods.sodium.client.d3d12.core.Alignment;

/**
 * Usage bits to heap/mode mapping (decision D2 of ARCHITECTURE.md).
 *
 * <pre>
 *   MAP_READ set      and no GPU-read bit  -> READBACK   (Screenshot, Tracy capture: MAP_READ | COPY_DST)
 *   MAP_READ set      and any GPU-read bit -> IllegalArgumentException (no caller exists; D3D12 has no such heap)
 *   MAP_WRITE set                          -> UPLOAD     (including MAP_WRITE | COPY_DST: SharedQuadIndexBuffer,
 *                                             MappedStagingBuffer, sectionTimeInfo, vanilla uniform rings)
 *   otherwise                              -> DEFAULT    (HINT_CLIENT_STORAGE alone is ignored)
 * </pre>
 *
 * <p>UPLOAD buffers stay in GENERIC_READ forever and can never be a GPU copy destination. The GPU-side writes that
 * Sodium and vanilla would perform into a {@code MAP_WRITE | COPY_DST} buffer are emulated on the CPU by
 * {@code CopyEngine} (see the copy rules in ARCHITECTURE.md section 2.b).
 */
public final class BufferModes {
    /** Usage bits that mean "the GPU reads this buffer": incompatible with a READBACK heap. */
    public static final int GPU_READ_MASK = GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_INDEX
            | GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_INDIRECT_PARAMETERS;

    private BufferModes() {
    }

    /**
     * @param usage the <em>final</em> usage ({@code createBuffer(data)} has already OR-ed in {@code USAGE_COPY_DST})
     * @return the mode (never a transient mode)
     * @throws IllegalArgumentException for {@code MAP_READ} combined with a GPU-read bit
     */
    public static BufferMode select(int usage) {
        if ((usage & GpuBuffer.USAGE_MAP_READ) != 0) {
            if ((usage & GPU_READ_MASK) != 0) {
                throw new IllegalArgumentException("A MAP_READ buffer cannot also be read by the GPU on Direct3D 12 (usage 0x"
                        + Integer.toHexString(usage) + ")");
            }

            return BufferMode.READBACK;
        }

        if ((usage & GpuBuffer.USAGE_MAP_WRITE) != 0) {
            return BufferMode.UPLOAD;
        }

        return BufferMode.DEFAULT;
    }

    /**
     * @return the {@code Width} of the committed D3D12 buffer: the requested size rounded up to 256 (root CBV and texel
     * view granularity), plus {@link Alignment#UNIFORM_TAIL_PAD} when the buffer can be bound as a uniform buffer
     * ({@code padForUniform}), because a root CBV has no range and the last slice must stay readable for its whole
     * declared size. {@code size()} still reports the requested size.
     */
    public static long d3dWidth(long size, boolean padForUniform) {
        long width = Alignment.alignUp(size, Alignment.CONSTANT_BUFFER);

        return padForUniform ? width + Alignment.UNIFORM_TAIL_PAD : width;
    }

    /** @return true if buffers with this usage get the uniform tail pad */
    public static boolean needsUniformPad(int usage) {
        return (usage & GpuBuffer.USAGE_UNIFORM) != 0;
    }
}
