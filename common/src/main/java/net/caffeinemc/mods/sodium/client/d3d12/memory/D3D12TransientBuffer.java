// Owner: WP2 (commands + transient memory) - fully implemented (trivial)
package net.caffeinemc.mods.sodium.client.d3d12.memory;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.caffeinemc.mods.sodium.client.d3d12.core.DeviceContext;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12Resource;
import net.caffeinemc.mods.sodium.client.d3d12.resource.BufferMode;
import net.caffeinemc.mods.sodium.client.d3d12.resource.D3D12Buffer;
import net.caffeinemc.mods.sodium.client.d3d12.state.TrackedResource;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;

/**
 * The buffer object inside every slice returned by the public {@code TransientMemory} API (spec 01 section 10.2,
 * spec 03 section 10.6). One small object per allocation, so that stale use is detectable:
 * <ul>
 *     <li>{@code size()} = the <b>block</b> size, {@code usage()} = the usage the caller requested (a label only);</li>
 *     <li>{@code isClosed()} becomes true as soon as the frame of the allocation has been submitted
 *     ({@code clock.current() != frame}) or {@code close()} was called ({@code close()} only marks);</li>
 *     <li>{@code slice()}, {@code slice(o, l)} and {@code map} throw {@link IllegalStateException} (a slice is created only
 *     by the allocator, directly as a {@code GpuBufferSlice} record).</li>
 * </ul>
 * The backend's own uploads do not create these objects (they use {@code UploadArena}).
 */
public final class D3D12TransientBuffer extends D3D12Buffer {
    private final DeviceContext ctx;
    private final TransientBlock block;
    private final long frame;
    private volatile boolean closed;

    public D3D12TransientBuffer(DeviceContext ctx, TransientBlock block, int usage) {
        super(usage, block.size());

        this.ctx = ctx;
        this.block = block;
        this.frame = ctx.clock().current();
    }

    public TransientBlock block() {
        return this.block;
    }

    @Override
    public D3D12Resource resource() {
        return this.block.resource();
    }

    @Override
    public long gpuVa() {
        return this.block.gpuVa();
    }

    @Override
    public BufferMode mode() {
        return this.block.mode();
    }

    @Override
    public @Nullable TrackedResource tracked() {
        return this.block.tracked();
    }

    @Override
    public @Nullable MemorySegment mapped() {
        return this.block.mapped();
    }

    @Override
    public boolean isUsable() {
        return !this.isClosed() && !this.ctx.isClosed();
    }

    @Override
    public boolean isClosed() {
        return this.closed || this.ctx.clock().current() != this.frame;
    }

    @Override
    public void close() {
        this.closed = true;
    }

    @Override
    public GpuBufferSlice slice() {
        throw new IllegalStateException("Cannot slice transient buffer");
    }

    @Override
    public GpuBufferSlice slice(long offset, long length) {
        throw new IllegalStateException("Cannot slice transient buffer");
    }

    @Override
    public GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) {
        throw new IllegalStateException("Cannot map transient buffer");
    }
}
