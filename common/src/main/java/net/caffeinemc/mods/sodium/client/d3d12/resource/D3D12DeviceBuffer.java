// Owner: WP1 (device core) - implemented
package net.caffeinemc.mods.sodium.client.d3d12.resource;

import net.caffeinemc.mods.sodium.client.d3d12.core.D3D12Log;
import net.caffeinemc.mods.sodium.client.d3d12.core.DeviceContext;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12Range;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12Resource;
import net.caffeinemc.mods.sodium.client.d3d12.state.TrackedResource;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A committed buffer created through {@code GpuDeviceBackend.createBuffer}: modes DEFAULT, UPLOAD or READBACK.
 *
 * <p>Creation order (nothing may leak on failure; {@code E_OUTOFMEMORY} becomes {@code GpuOutOfMemoryException}):
 * validate arguments -> {@code CreateCommittedResource} (heap and initial state from the mode: DEFAULT in
 * {@code GENERIC_READ}, or {@code COPY_DEST} when created with data; UPLOAD in {@code GENERIC_READ}; READBACK in
 * {@code COPY_DEST}; width from {@link BufferModes#d3dWidth}) -> persistent {@code Map} for host-visible modes (read
 * range empty for UPLOAD, full for READBACK) -> arena-bound {@code MemorySegment} -> label ({@code SetName}, only when
 * labels are on). {@code createBuffer(data)} ORs {@code USAGE_COPY_DST} into {@code usage()}; UPLOAD data is a
 * {@code memcpy} into the mapping, DEFAULT data goes through {@code D3D12CommandEncoder.uploadInitialData}.
 *
 * <p>{@code close()}: idempotent; sets {@code closed}; logs one WARN (never throws) if views are still open; defers
 * "close arena, release resource" via {@code DeviceContext.defer}. After the device is closed it is a no-op.
 *
 * <p>READBACK buffers override {@code beginCpuRead(offset, length)} / {@code endCpuRead()} of {@link D3D12Buffer}: an extra
 * {@code resource.map(0, offset, offset + length, size)} before the first CPU read of a view and a matching
 * {@code unmap(0, empty range)} when the view closes (skipped when the buffer was destroyed meanwhile; both swallow and
 * log failures). The persistent mapping created at creation time is untouched (Map/Unmap are reference counted).
 *
 * <p>Implementation notes:
 * <ul>
 *     <li>{@link #mapped()} covers the whole D3D12 resource ({@link #width()} bytes, i.e. including the 256 byte rounding
 *     and the uniform tail pad), not only {@code size()}; the {@code map()} bounds checks use {@code size()}.</li>
 *     <li>The native release order in the deferred destroy is: close the arena (stale {@code ByteBuffer}s now throw
 *     {@code IllegalStateException}, invariant I7) -> {@code Unmap} -> {@code Release}. Every step is isolated.</li>
 *     <li>The Map/Unmap bracket of a CPU read uses a per-buffer native scratch (a {@code D3D12_RANGE} and the out pointer)
 *     taken from the mapping arena, so reads allocate nothing on the Java heap beyond the {@code MappedView}.</li>
 * </ul>
 */
public final class D3D12DeviceBuffer extends D3D12Buffer {
    private final DeviceContext ctx;
    private final String label;
    private final BufferMode mode;
    private final D3D12Resource resource;
    private final @Nullable TrackedResource tracked;
    private final @Nullable MemorySegment mapped;
    private final @Nullable Arena mappedArena;
    private final long gpuVa;
    private final long width;
    private final @Nullable ResourceCounters counters;
    private final @Nullable MemorySegment readScratch;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger cpuReadMaps = new AtomicInteger();
    private volatile boolean destroyed;

    D3D12DeviceBuffer(DeviceContext ctx, String label, int usage, long size, BufferMode mode, D3D12Resource resource,
                      @Nullable TrackedResource tracked, @Nullable MemorySegment mapped, @Nullable Arena mappedArena,
                      long width, @Nullable ResourceCounters counters) {
        super(usage, size);

        this.ctx = ctx;
        this.label = label;
        this.mode = mode;
        this.resource = resource;
        this.tracked = tracked;
        this.mapped = mapped;
        this.mappedArena = mappedArena;
        this.width = width;
        this.counters = counters;
        this.gpuVa = resource.getGPUVirtualAddress();
        this.readScratch = mode == BufferMode.READBACK && mappedArena != null
                ? mappedArena.allocate(D3D12Range.SIZE + 8L, 8L) : null;
    }

    public String label() {
        return this.label;
    }

    /** @return the label, or a placeholder for unnamed buffers (log messages) */
    private String displayName() {
        return this.label.isEmpty() ? "<unnamed>" : this.label;
    }

    /** @return the {@code Width} of the D3D12 resource (requested size rounded up to 256 plus the uniform pad) */
    public long width() {
        return this.width;
    }

    @Override
    public D3D12Resource resource() {
        return this.resource;
    }

    @Override
    public long gpuVa() {
        return this.gpuVa;
    }

    @Override
    public BufferMode mode() {
        return this.mode;
    }

    @Override
    public @Nullable TrackedResource tracked() {
        return this.tracked;
    }

    @Override
    public @Nullable MemorySegment mapped() {
        return this.mapped;
    }

    @Override
    public boolean isUsable() {
        return !this.closed.get() && !this.ctx.isClosed();
    }

    @Override
    public boolean isClosed() {
        return this.closed.get();
    }

    @Override
    protected void beginCpuRead(long offset, long length) {
        MemorySegment scratch = this.readScratch;

        if (scratch == null || this.destroyed) {
            return;
        }

        try {
            MemorySegment range = scratch.asSlice(0L, D3D12Range.SIZE);

            D3D12Range.setBegin(range, offset);
            D3D12Range.setEnd(range, offset + length);

            int hr = this.resource.map(0, range, scratch.asSlice(D3D12Range.SIZE, 8L));

            if (hr >= 0) {
                this.cpuReadMaps.incrementAndGet();
            } else {
                D3D12Log.warnOnce("readback-map", "ID3D12Resource::Map for a CPU read failed with HRESULT 0x{}; reading through the persistent mapping",
                        Integer.toHexString(hr));
            }
        } catch (RuntimeException e) {
            // the buffer was destroyed or the arena closed in between: the view is stale anyway
            D3D12Log.debug("CPU read bracket skipped for buffer '{}': {}", this.displayName(), e.toString());
        }
    }

    @Override
    protected void endCpuRead() {
        if (this.destroyed || this.cpuReadMaps.getAndUpdate(count -> count > 0 ? count - 1 : 0) <= 0) {
            return;
        }

        try {
            this.resource.unmapUnwritten(0);
        } catch (RuntimeException e) {
            D3D12Log.debug("CPU read Unmap skipped for buffer '{}': {}", this.displayName(), e.toString());
        }
    }

    /**
     * Idempotent and thread-safe ({@code closed.compareAndSet(false, true)}); never throws. Commands recorded earlier in
     * the same frame stay valid because the native release is deferred.
     */
    @Override
    public void close() {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }

        try {
            int open = this.openViewCount();

            if (open > 0) {
                D3D12Log.warn("Direct3D 12 buffer '{}' was closed with {} open mapped view(s); they become invalid when the buffer is released",
                        this.displayName(), open);
            }

            this.ctx.defer(this::destroy);
        } catch (RuntimeException e) {
            D3D12Log.error("Failed to schedule the release of Direct3D 12 buffer '{}'", this.displayName(), e);
        }
    }

    /** Runs from the deferral queue (or inline once the GPU is idle), exactly once. */
    private void destroy() {
        this.destroyed = true;

        try {
            if (this.mappedArena != null) {
                this.mappedArena.close();
            }
        } catch (RuntimeException e) {
            D3D12Log.warn("Failed to close the mapping arena of buffer '{}': {}", this.displayName(), e.toString());
        }

        try {
            if (this.mapped != null) {
                if (this.mode == BufferMode.READBACK) {
                    this.resource.unmapUnwritten(0);
                } else {
                    this.resource.unmap(0);
                }
            }
        } catch (RuntimeException e) {
            D3D12Log.debug("Unmap of buffer '{}' failed: {}", this.displayName(), e.toString());
        }

        try {
            this.resource.release();
        } catch (RuntimeException e) {
            D3D12Log.warn("Failed to release buffer '{}': {}", this.displayName(), e.toString());
        }

        if (this.counters != null) {
            this.counters.bufferDestroyed();
        }
    }
}
