// Owner: WP2 (commands + transient memory)
package net.caffeinemc.mods.sodium.client.d3d12.memory;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import net.caffeinemc.mods.sodium.client.d3d12.core.DeviceContext;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * {@code TransientMemory} over the block allocator (spec 01 section 10, spec 03 section 10; decision D6).
 *
 * <table>
 *   <caption>The four allocation kinds</caption>
 *   <tr><th>call</th><th>backing</th><th>returned</th><th>recycling</th></tr>
 *   <tr><td>{@code allocateCpu} (alignment &lt;= 16, else IAE)</td><td>plain native memory, 512 KiB blocks</td>
 *       <td>direct native-order {@code ByteBuffer}</td><td>immediately at {@link #rotate}</td></tr>
 *   <tr><td>{@code allocateStaging}</td><td>UPLOAD blocks</td><td>{@code MappedView}; slice buffer is a
 *       {@link D3D12TransientBuffer}</td><td>deferral, tag = frame</td></tr>
 *   <tr><td>{@code allocateGpuMapped}</td><td><b>the same UPLOAD blocks</b> ("mode C"): CPU writes before
 *       {@code submit()} are visible to the whole frame, GPU reads cross the bus; no copy, no extra list</td>
 *       <td>as staging</td><td>as staging</td></tr>
 *   <tr><td>{@code allocateGpu}</td><td>DEFAULT blocks (tracked)</td><td>{@code GpuBufferSlice}</td><td>deferral</td></tr>
 * </table>
 *
 * <p>Semantics that callers rely on (all from the reference): 512 KiB blocks; {@code size == block} takes a whole block;
 * larger requests get a special block from {@link BlockPool}; the shorter-allocation rule driven by
 * {@code minimumAllocation / elementSize}; {@code uploadStaging/uploadGpu(List)} copy each source
 * {@code [position, limit)} back to back at {@code alignment}-rounded offsets without moving source positions and clip to
 * the slice length; {@code multiUpload*} return one aligned slice per input in input order (the reference's
 * largest-first packing is not reproduced: only content visibility, alignment and order are contractual). The
 * {@code MappedView.onClose} is a no-op (nothing to flush: UPLOAD memory needs no explicit flush).
 *
 * <p>Alignment: block bases are 64 KiB aligned; an {@code alignment} above 64 KiB is honoured only at offset 0 of a fresh
 * block, above the block size -> {@link IllegalArgumentException}. A slice that will be bound as a UBO must be requested
 * with {@code alignment >= 256}.
 *
 * <p>Lifetime: the frame's used blocks are returned by {@code ctx.defer(...)} at {@link #rotate} (never recycled before
 * the GPU finished the frame, unlike Vulkan's immediate reuse of device blocks). The {@code TransientBuffer}s die with
 * the frame ({@code isClosed()}).
 */
public final class D3D12TransientMemory implements TransientMemory {
    private final DeviceContext ctx;
    private final UploadArena uploads;

    public D3D12TransientMemory(DeviceContext ctx) {
        this.ctx = ctx;
        this.uploads = new UploadArena(this);
    }

    /** @return the backend-internal upload facade over the same allocators */
    public UploadArena uploads() {
        return this.uploads;
    }

    /**
     * Ends frame {@code frame} (called by {@code submit()} before the frame counter advances): CPU blocks are recycled
     * now, UPLOAD/DEFAULT/special blocks of the frame via {@code ctx.defer} (tag = {@code frame}).
     */
    public void rotate(long frame) {
        throw new UnsupportedOperationException("WP2: rotate the four allocators, defer the returned actions, BlockPool.trim");
    }

    /** Frees everything. The GPU must be idle; called by {@code D3D12CommandEncoder.shutdown()}. */
    public void close() {
        throw new UnsupportedOperationException("WP2: BlockAllocator.close() x4, BlockPool.close()");
    }

    DeviceContext context() {
        return this.ctx;
    }

    @Override
    public ByteBuffer allocateCpu(long size, long alignment, long minimumAllocation, long elementSize) {
        throw new UnsupportedOperationException("WP2: CPU block allocator (native memory), alignment <= 16");
    }

    @Override
    public GpuBufferSlice.MappedView allocateStaging(long size, long alignment, int usage, long minimumAllocation, long elementSize) {
        throw new UnsupportedOperationException("WP2: UPLOAD allocator + D3D12TransientBuffer + native-order ByteBuffer");
    }

    @Override
    public GpuBufferSlice allocateGpu(long size, long alignment, int usage, long minimumAllocation, long elementSize) {
        throw new UnsupportedOperationException("WP2: DEFAULT allocator + D3D12TransientBuffer");
    }

    @Override
    public GpuBufferSlice.MappedView allocateGpuMapped(long size, long alignment, int usage, long minimumAllocation, long elementSize) {
        throw new UnsupportedOperationException("WP2: same UPLOAD allocator as allocateStaging (mode C)");
    }

    @Override
    public GpuBufferSlice uploadStaging(List<ByteBuffer> data, long alignment, @GpuBuffer.Usage int usage, long minimumAllocation, long elementSize) {
        throw new UnsupportedOperationException("WP2");
    }

    @Override
    public GpuBufferSlice uploadGpu(List<ByteBuffer> data, long alignment, @GpuBuffer.Usage int usage, long minimumAllocation, long elementSize) {
        throw new UnsupportedOperationException("WP2");
    }

    @Override
    public List<GpuBufferSlice> multiUploadStaging(List<ByteBuffer> data, long alignment, @GpuBuffer.Usage int usage) {
        throw new UnsupportedOperationException("WP2");
    }

    @Override
    public List<GpuBufferSlice> multiUploadGpu(List<ByteBuffer> data, long alignment, @GpuBuffer.Usage int usage) {
        throw new UnsupportedOperationException("WP2");
    }
}
