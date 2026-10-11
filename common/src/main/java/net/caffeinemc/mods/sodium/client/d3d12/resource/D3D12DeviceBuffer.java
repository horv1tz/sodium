// Owner: WP1 (device core)
package net.caffeinemc.mods.sodium.client.d3d12.resource;

import net.caffeinemc.mods.sodium.client.d3d12.core.DeviceContext;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12Resource;
import net.caffeinemc.mods.sodium.client.d3d12.state.TrackedResource;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final AtomicBoolean closed = new AtomicBoolean();

    D3D12DeviceBuffer(DeviceContext ctx, String label, int usage, long size, BufferMode mode, D3D12Resource resource,
                      @Nullable TrackedResource tracked, @Nullable MemorySegment mapped, @Nullable Arena mappedArena) {
        super(usage, size);

        this.ctx = ctx;
        this.label = label;
        this.mode = mode;
        this.resource = resource;
        this.tracked = tracked;
        this.mapped = mapped;
        this.mappedArena = mappedArena;
        this.gpuVa = resource.getGPUVirtualAddress();
    }

    public String label() {
        return this.label;
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
    public void close() {
        throw new UnsupportedOperationException("WP1: if (!closed.compareAndSet(false, true)) return; warn on open views, defer arena close + resource release");
    }
}
