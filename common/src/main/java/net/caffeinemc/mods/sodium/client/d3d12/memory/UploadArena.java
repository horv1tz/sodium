// Owner: WP2 (commands + transient memory)
package net.caffeinemc.mods.sodium.client.d3d12.memory;

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
     */
    public Slice alloc(long size, long alignment) {
        throw new UnsupportedOperationException("WP2: BlockAllocator over TRANSIENT_UPLOAD blocks, whole-block / special-block rules");
    }

    /**
     * @return a DEFAULT-heap scratch slice (never CPU visible); contents undefined. Used for the per-row GPU repack
     * of texture readbacks and of {@code copyBufferToTexture} sources that are not footprint aligned.
     */
    public GpuSlice allocGpu(long size, long alignment) {
        throw new UnsupportedOperationException("WP2: BlockAllocator over TRANSIENT_DEFAULT blocks");
    }

    D3D12TransientMemory memory() {
        return this.memory;
    }
}
