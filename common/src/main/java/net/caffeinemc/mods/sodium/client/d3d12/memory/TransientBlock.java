// Owner: WP2 (commands + transient memory)
package net.caffeinemc.mods.sodium.client.d3d12.memory;

import net.caffeinemc.mods.sodium.client.d3d12.core.DeviceContext;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12Resource;
import net.caffeinemc.mods.sodium.client.d3d12.resource.BufferMode;
import net.caffeinemc.mods.sodium.client.d3d12.state.TrackedResource;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * One committed buffer handed out by the transient allocators: a 512 KiB block or a larger special block.
 *
 * <ul>
 *     <li>{@link BufferMode#TRANSIENT_UPLOAD}: UPLOAD heap, created in {@code GENERIC_READ}, persistently mapped for its
 *     whole life, never tracked. Serves {@code allocateStaging}, {@code allocateGpuMapped} (the block itself is the "GPU
 *     memory": CPU writes before {@code submit()} are visible to every GPU command of the frame, GPU reads cross the bus)
 *     and all internal uploads. Width = block size + 64 KiB tail pad (root CBV safety).</li>
 *     <li>{@link BufferMode#TRANSIENT_DEFAULT}: DEFAULT heap, tracked ({@code GR} / {@code CD}), serves
 *     {@code allocateGpu} and the repack scratch of the copy engine.</li>
 * </ul>
 * Blocks are 64 KiB aligned, so an offset that is a multiple of 256 (or 512 for texture footprints) is aligned in GPU
 * address space as well. Recycling always goes through {@code DeviceContext.defer} (tag = frame of use).
 */
public final class TransientBlock {
    private final BufferMode mode;
    private final long size;
    private final D3D12Resource resource;
    private final @Nullable TrackedResource tracked;
    private final @Nullable MemorySegment mapped;
    private final @Nullable Arena arena;
    private final long gpuVa;

    TransientBlock(BufferMode mode, long size, D3D12Resource resource, @Nullable TrackedResource tracked,
                   @Nullable MemorySegment mapped, @Nullable Arena arena) {
        this.mode = mode;
        this.size = size;
        this.resource = resource;
        this.tracked = tracked;
        this.mapped = mapped;
        this.arena = arena;
        this.gpuVa = resource.getGPUVirtualAddress();
    }

    /**
     * Creates the committed buffer of a block.
     *
     * @param mode {@code TRANSIENT_UPLOAD} or {@code TRANSIENT_DEFAULT}
     * @param size usable size in bytes (the D3D12 width is larger by the tail pad)
     * @throws com.mojang.renderpearl.api.device.GpuOutOfMemoryException on {@code E_OUTOFMEMORY}
     */
    public static TransientBlock create(DeviceContext ctx, BufferMode mode, long size) {
        throw new UnsupportedOperationException("WP2: committed UPLOAD (mapped) or DEFAULT (tracked, GR) buffer of size + tail pad");
    }

    public BufferMode mode() {
        return this.mode;
    }

    /** @return the usable size (what {@code D3D12TransientBuffer.size()} reports) */
    public long size() {
        return this.size;
    }

    public D3D12Resource resource() {
        return this.resource;
    }

    public long gpuVa() {
        return this.gpuVa;
    }

    public @Nullable TrackedResource tracked() {
        return this.tracked;
    }

    /** @return the persistent mapping of the whole block (UPLOAD only) */
    public @Nullable MemorySegment mapped() {
        return this.mapped;
    }

    /** Closes the mapping arena and releases the resource. Only called from a deferral entry or after a GPU idle. */
    public void destroy() {
        throw new UnsupportedOperationException("WP2: close arena, release the resource");
    }
}
