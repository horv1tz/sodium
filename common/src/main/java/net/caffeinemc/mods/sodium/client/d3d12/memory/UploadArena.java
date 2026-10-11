// Owner: WP2 (commands + transient memory) - implemented
package net.caffeinemc.mods.sodium.client.d3d12.memory;

import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12Resource;

import java.lang.foreign.MemorySegment;

/**
 * The backend-internal facade over the transient block allocators: every upload the encoder performs itself
 * ({@code writeToBuffer}, {@code writeToTexture}, {@code createBuffer(data)}, the repack scratch of
 * {@code copyBufferToTexture} / {@code copyTextureToBuffer}) allocates here instead of creating Mojang-visible
 * objects. It is the same memory as the public {@code TransientMemory} (one UPLOAD allocator, one DEFAULT allocator), so
 * a frame's uploads and the game's own staging allocations share blocks.
 *
 * <p>Everything returned is valid for the current frame only; it is recycled through the deferral queue after the
 * frame completes. The slices are plain values; their {@code TransientBlock} is not a {@code GpuBuffer}.
 *
 * <p>Threading: render thread.
 */
public final class UploadArena {
    /** Largest alignment the internal allocations accept: block bases are 64 KiB aligned in GPU address space. */
    public static final long MAX_ALIGNMENT = 65536L;

    /**
     * An UPLOAD slice: the GPU reads {@code block.resource()} at {@code offset}; the CPU writes through {@code cpu}
     * (write-combined: never read it).
     *
     * @param cpu the {@code size} bytes at {@code offset} of the block's persistent mapping
     */
    public record Slice(TransientBlock block, long offset, MemorySegment cpu) {
    }

    /** A DEFAULT slice used as GPU-side scratch (tracked: transition the block's {@code TrackedResource} before use). */
    public record GpuSlice(TransientBlock block, long offset) {
    }

    private final D3D12TransientMemory memory;

    UploadArena(D3D12TransientMemory memory) {
        this.memory = memory;
    }

    /**
     * @param size      bytes, {@code > 0}
     * @param alignment {@code 1..65536}; 512 for texture footprints, 256 for CBV data (larger -> IAE)
     * @return an UPLOAD slice of exactly {@code size} bytes at an offset that is a multiple of {@code alignment}
     * @throws IllegalArgumentException                                  for a non-positive size, a size above 2 GiB - 1 or an alignment outside the range
     * @throws com.mojang.renderpearl.api.device.GpuOutOfMemoryException if a new block cannot be committed
     */
    public Slice alloc(long size, long alignment) {
        checkRequest(size, alignment);

        return this.memory.allocUpload(size, alignment);
    }

    /**
     * @return a DEFAULT-heap scratch slice (never CPU visible); contents undefined. Used for the per-row GPU repack
     * of texture readbacks and of {@code copyBufferToTexture} sources that are not footprint aligned.
     * @throws IllegalArgumentException as {@link #alloc}
     */
    public GpuSlice allocGpu(long size, long alignment) {
        checkRequest(size, alignment);

        return this.memory.allocDevice(size, alignment, null);
    }

    /**
     * Like {@link #allocGpu} but guarantees that the slice lives in another native resource than {@code avoid}: a
     * resource cannot be {@code COPY_DEST} (scratch) and {@code COPY_SOURCE}/{@code GENERIC_READ} (the user's buffer) at
     * the same time, and slices of one transient block share one resource.
     *
     * @param avoid the resource the scratch must not share (may be null)
     */
    public GpuSlice allocGpuAvoiding(long size, long alignment, D3D12Resource avoid) {
        checkRequest(size, alignment);

        return this.memory.allocDevice(size, alignment, avoid);
    }

    private static void checkRequest(long size, long alignment) {
        if (size <= 0L || size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Upload size must be in [1, " + Integer.MAX_VALUE + "]: " + size);
        }

        if (alignment < 1L || alignment > MAX_ALIGNMENT) {
            throw new IllegalArgumentException("Upload alignment must be in [1, " + MAX_ALIGNMENT + "]: " + alignment);
        }
    }

    D3D12TransientMemory memory() {
        return this.memory;
    }
}
