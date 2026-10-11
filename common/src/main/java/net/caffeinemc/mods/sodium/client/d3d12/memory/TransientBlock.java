// Owner: WP2 (commands + transient memory) - implemented
package net.caffeinemc.mods.sodium.client.d3d12.memory;

import net.caffeinemc.mods.sodium.client.d3d12.core.D3D12Log;
import net.caffeinemc.mods.sodium.client.d3d12.core.DeviceContext;
import net.caffeinemc.mods.sodium.client.d3d12.interop.D3D12Exception;
import net.caffeinemc.mods.sodium.client.d3d12.interop.Hr;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12Range;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Enums;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12Resource;
import net.caffeinemc.mods.sodium.client.d3d12.resource.BufferMode;
import net.caffeinemc.mods.sodium.client.d3d12.resource.BufferModes;
import net.caffeinemc.mods.sodium.client.d3d12.state.ResourceStates;
import net.caffeinemc.mods.sodium.client.d3d12.state.TrackedResource;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

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
    private volatile boolean destroyed;

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
     * Creates the committed buffer of a block. Nothing leaks when it fails: the resource and the mapping arena are
     * released before the exception propagates.
     *
     * @param mode {@code TRANSIENT_UPLOAD} or {@code TRANSIENT_DEFAULT}
     * @param size usable size in bytes (the D3D12 width is larger by the tail pad)
     * @throws com.mojang.renderpearl.api.device.GpuOutOfMemoryException on {@code E_OUTOFMEMORY}
     * @throws IllegalArgumentException                                  for another mode or a non-positive size
     */
    public static TransientBlock create(DeviceContext ctx, BufferMode mode, long size) {
        if (mode != BufferMode.TRANSIENT_UPLOAD && mode != BufferMode.TRANSIENT_DEFAULT) {
            throw new IllegalArgumentException("Not a transient block mode: " + mode);
        }

        if (size <= 0L) {
            throw new IllegalArgumentException("Transient block size must be positive: " + size);
        }

        boolean upload = mode == BufferMode.TRANSIENT_UPLOAD;
        // every block can be read through a root CBV, which has no range: keep 64 KiB readable behind the last byte
        long width = BufferModes.d3dWidth(size, true);

        D3D12Resource resource = null;
        Arena arena = null;

        try {
            resource = ctx.d3d().createCommittedBuffer(upload ? Enums.HeapType.UPLOAD : Enums.HeapType.DEFAULT, width,
                    Enums.ResourceFlags.NONE, Enums.ResourceStates.GENERIC_READ);

            MemorySegment mapped = null;
            TrackedResource tracked = null;

            if (upload) {
                arena = Arena.ofShared();
                mapped = mapPersistently(resource, width, arena);
            } else {
                tracked = new TrackedResource(resource, 1, ResourceStates.GR);
            }

            if (ctx.labels()) {
                resource.setName(upload ? "Sodium transient upload block" : "Sodium transient device block");
            }

            return new TransientBlock(mode, size, resource, tracked, mapped, arena);
        } catch (D3D12Exception e) {
            releaseQuietly(resource, arena);

            if (e.isOutOfMemory()) {
                throw ctx.errors().outOfMemory("allocating a " + size + " byte " + (upload ? "upload" : "device-local")
                        + " block for transient memory", e);
            }

            throw e;
        } catch (Throwable t) {
            releaseQuietly(resource, arena);

            throw t;
        }
    }

    /** Maps the whole resource for writing (empty read range) and binds the view to {@code arena} (invariant I7). */
    private static MemorySegment mapPersistently(D3D12Resource resource, long width, Arena arena) {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment emptyRange = D3D12Range.allocate(scratch);
            MemorySegment out = scratch.allocate(ValueLayout.ADDRESS);

            Hr.check(resource.map(0, emptyRange, out), "ID3D12Resource::Map");

            return out.get(ValueLayout.ADDRESS, 0).reinterpret(width, arena, null);
        }
    }

    private static void releaseQuietly(D3D12Resource resource, Arena arena) {
        try {
            if (arena != null) {
                arena.close();
            }
        } catch (Throwable t) {
            D3D12Log.error("Failed to close the mapping of a transient block", t);
        }

        try {
            if (resource != null) {
                resource.release();
            }
        } catch (Throwable t) {
            D3D12Log.error("Failed to release a transient block", t);
        }
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

    /**
     * Closes the mapping arena (stale views then throw instead of faulting) and releases the resource. Only called from a
     * deferral entry or after a GPU idle. Idempotent, never throws.
     */
    public void destroy() {
        if (this.destroyed) {
            return;
        }

        this.destroyed = true;

        releaseQuietly(this.resource, this.arena);
    }

    public boolean isDestroyed() {
        return this.destroyed;
    }
}
