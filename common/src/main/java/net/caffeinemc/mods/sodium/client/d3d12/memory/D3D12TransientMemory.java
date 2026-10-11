// Owner: WP2 (commands + transient memory) - implemented
package net.caffeinemc.mods.sodium.client.d3d12.memory;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import net.caffeinemc.mods.sodium.client.d3d12.core.Alignment;
import net.caffeinemc.mods.sodium.client.d3d12.core.D3D12Log;
import net.caffeinemc.mods.sodium.client.d3d12.core.DeviceContext;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12Resource;
import net.caffeinemc.mods.sodium.client.d3d12.resource.BufferMode;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
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
 * the frame ({@code isClosed()}). Blocks nobody asked for during a whole frame are destroyed at the next rotation
 * (shrink policy of the reference); special blocks are pooled by size class ({@link BlockPool}).
 *
 * <p>Failure behaviour: all argument errors are {@link IllegalArgumentException} (negative or oversized requests,
 * alignment above the limit, element size below 1) and are thrown before any block is touched; a block that cannot be
 * committed raises {@code GpuOutOfMemoryException} and leaves the allocator unchanged.
 *
 * <p>Threading: render thread (every public method); the deferral actions it queues run on the render thread as well.
 */
public final class D3D12TransientMemory implements TransientMemory {
    /** {@code TransientBlockAllocator} block size of the reference: 512 KiB. */
    public static final long BLOCK_SIZE = 524288L;

    /** {@code allocateCpu} rejects larger alignments (the native allocation is 16 byte aligned). */
    public static final long MAX_CPU_ALIGNMENT = 16L;

    /** The GPU-side allocators honour any alignment up to the block size (offsets are relative to a 64 KiB aligned base). */
    public static final long MAX_GPU_ALIGNMENT = BLOCK_SIZE;

    private static final Runnable NO_OP = () -> {
    };

    /** One CPU-only block: a shared arena so that a stale {@code ByteBuffer} fails instead of faulting (I7). */
    record CpuBlock(Arena arena, MemorySegment memory) {
        static CpuBlock create(long size) {
            Arena arena = Arena.ofShared();

            try {
                return new CpuBlock(arena, arena.allocate(size, MAX_CPU_ALIGNMENT));
            } catch (Throwable t) {
                arena.close();

                throw t;
            }
        }

        void destroy() {
            this.arena.close();
        }
    }

    /** Block creation/destruction of one GPU-visible allocator; special blocks go through the size-class pool. */
    private final class GpuBlocks implements BlockAllocator.Source<TransientBlock> {
        private final BufferMode mode;

        GpuBlocks(BufferMode mode) {
            this.mode = mode;
        }

        @Override
        public TransientBlock alloc(long size) {
            return size > BLOCK_SIZE ? D3D12TransientMemory.this.pool.acquire(this.mode, size) : D3D12TransientMemory.this.factory.create(this.mode, size);
        }

        @Override
        public void free(TransientBlock block) {
            if (block.size() > BLOCK_SIZE) {
                D3D12TransientMemory.this.pool.release(block);
            } else {
                block.destroy();
            }
        }
    }

    private final DeviceContext ctx;
    private final BlockPool.BlockFactory factory;
    private final BlockPool pool;
    private final UploadArena uploads;
    private final BlockAllocator<CpuBlock> cpu;
    private final BlockAllocator<TransientBlock> upload;
    private final BlockAllocator<TransientBlock> device;
    private boolean uploadUsed;
    private boolean deviceUsed;
    private boolean closed;

    public D3D12TransientMemory(DeviceContext ctx) {
        this(ctx, (mode, size) -> TransientBlock.create(ctx, mode, size));
    }

    /** Test seam: GPU-visible blocks come from {@code factory}. */
    public D3D12TransientMemory(DeviceContext ctx, BlockPool.BlockFactory factory) {
        this.ctx = ctx;
        this.factory = factory;
        this.pool = new BlockPool(ctx, factory);
        this.uploads = new UploadArena(this);

        this.cpu = new BlockAllocator<>(BLOCK_SIZE, MAX_CPU_ALIGNMENT, new BlockAllocator.Source<>() {
            @Override
            public CpuBlock alloc(long size) {
                return CpuBlock.create(size);
            }

            @Override
            public void free(CpuBlock block) {
                block.destroy();
            }
        }, block -> {
        });
        this.upload = new BlockAllocator<>(BLOCK_SIZE, MAX_GPU_ALIGNMENT, new GpuBlocks(BufferMode.TRANSIENT_UPLOAD),
                block -> this.uploadUsed = true);
        this.device = new BlockAllocator<>(BLOCK_SIZE, MAX_GPU_ALIGNMENT, new GpuBlocks(BufferMode.TRANSIENT_DEFAULT),
                block -> this.deviceUsed = true);
    }

    /** @return the backend-internal upload facade over the same allocators */
    public UploadArena uploads() {
        return this.uploads;
    }

    /** @return the pool of special (larger than one block) blocks, for statistics and tests */
    public BlockPool pool() {
        return this.pool;
    }

    /**
     * Ends frame {@code frame} (called by {@code submit()} before the frame counter advances): CPU blocks are recycled
     * now, UPLOAD/DEFAULT/special blocks of the frame via {@code ctx.defer} (tag = {@code frame}).
     */
    public void rotate(long frame) {
        this.checkOpen();

        this.cpu.rotate().run();

        Runnable uploadReturn = this.upload.rotate();
        Runnable deviceReturn = this.device.rotate();

        if (this.uploadUsed) {
            this.ctx.defer(uploadReturn);
            this.uploadUsed = false;
        }

        if (this.deviceUsed) {
            this.ctx.defer(deviceReturn);
            this.deviceUsed = false;
        }

        this.pool.trim(frame);
    }

    /**
     * Frees everything. The GPU must be idle; called by {@code D3D12CommandEncoder.shutdown()} after the deferral queue
     * was drained (the returns queued by {@link #rotate} must have run before). Idempotent, never throws.
     */
    public void close() {
        if (this.closed) {
            return;
        }

        this.closed = true;

        try {
            this.cpu.close();
        } catch (Throwable t) {
            D3D12Log.error("Failed to free the CPU transient blocks", t);
        }

        try {
            this.upload.close();
        } catch (Throwable t) {
            D3D12Log.error("Failed to free the upload transient blocks", t);
        }

        try {
            this.device.close();
        } catch (Throwable t) {
            D3D12Log.error("Failed to free the device transient blocks", t);
        }

        try {
            this.pool.close();
        } catch (Throwable t) {
            D3D12Log.error("Failed to free the pooled transient blocks", t);
        }
    }

    DeviceContext context() {
        return this.ctx;
    }

    // ------------------------------------------------------------------------------------------------------------
    // backend-internal allocations (UploadArena)
    // ------------------------------------------------------------------------------------------------------------

    UploadArena.Slice allocUpload(long size, long alignment) {
        this.checkOpen();

        BlockAllocator.Allocation<TransientBlock> allocation = this.upload.allocate(size, alignment, size, 1L);

        return new UploadArena.Slice(allocation.block(), allocation.offset(), cpuView(allocation));
    }

    UploadArena.GpuSlice allocDevice(long size, long alignment, @Nullable D3D12Resource avoid) {
        this.checkOpen();

        BlockAllocator.Allocation<TransientBlock> allocation = this.device.allocate(size, alignment, size, 1L);

        if (avoid != null && allocation.block().resource() == avoid) {
            // Same native resource as the buffer the caller reads or writes, and a resource has one state at a time. Take
            // a whole fresh block: it comes from the free list or a new commit, never from a block that is in use this
            // frame. (A request above one block is a special block of its own and cannot get here.)
            allocation = this.device.allocate(BLOCK_SIZE, 1L, BLOCK_SIZE, 1L);

            if (allocation.block().resource() == avoid) {
                throw new IllegalStateException("Could not obtain a scratch block distinct from the buffer in use");
            }
        }

        return new UploadArena.GpuSlice(allocation.block(), allocation.offset());
    }

    // ------------------------------------------------------------------------------------------------------------
    // TransientMemory
    // ------------------------------------------------------------------------------------------------------------

    @Override
    public ByteBuffer allocateCpu(long size, long alignment, long minimumAllocation, long elementSize) {
        this.checkOpen();
        checkRequest(size, elementSize);

        BlockAllocator.Allocation<CpuBlock> allocation = this.cpu.allocate(size, alignment, minimumAllocation, elementSize);

        return allocation.block().memory().asSlice(allocation.offset(), allocation.size()).asByteBuffer().order(ByteOrder.nativeOrder());
    }

    @Override
    public GpuBufferSlice.MappedView allocateStaging(long size, long alignment, int usage, long minimumAllocation, long elementSize) {
        return this.mappedUpload(size, alignment, usage, minimumAllocation, elementSize);
    }

    @Override
    public GpuBufferSlice allocateGpu(long size, long alignment, int usage, long minimumAllocation, long elementSize) {
        this.checkOpen();
        checkRequest(size, elementSize);

        BlockAllocator.Allocation<TransientBlock> allocation = this.device.allocate(size, alignment, minimumAllocation, elementSize);

        return this.slice(allocation, usage);
    }

    /** "Mode C": the UPLOAD block itself is the GPU-visible memory; identical to {@link #allocateStaging}. */
    @Override
    public GpuBufferSlice.MappedView allocateGpuMapped(long size, long alignment, int usage, long minimumAllocation, long elementSize) {
        return this.mappedUpload(size, alignment, usage, minimumAllocation, elementSize);
    }

    @Override
    public GpuBufferSlice uploadStaging(List<ByteBuffer> data, long alignment, @GpuBuffer.Usage int usage, long minimumAllocation, long elementSize) {
        return this.upload(data, alignment, usage, minimumAllocation, elementSize);
    }

    @Override
    public GpuBufferSlice uploadGpu(List<ByteBuffer> data, long alignment, @GpuBuffer.Usage int usage, long minimumAllocation, long elementSize) {
        return this.upload(data, alignment, usage, minimumAllocation, elementSize);
    }

    @Override
    public List<GpuBufferSlice> multiUploadStaging(List<ByteBuffer> data, long alignment, @GpuBuffer.Usage int usage) {
        return this.multiUpload(data, alignment, usage);
    }

    @Override
    public List<GpuBufferSlice> multiUploadGpu(List<ByteBuffer> data, long alignment, @GpuBuffer.Usage int usage) {
        return this.multiUpload(data, alignment, usage);
    }

    // ------------------------------------------------------------------------------------------------------------

    private GpuBufferSlice.MappedView mappedUpload(long size, long alignment, int usage, long minimumAllocation, long elementSize) {
        this.checkOpen();
        checkRequest(size, elementSize);

        BlockAllocator.Allocation<TransientBlock> allocation = this.upload.allocate(size, alignment, minimumAllocation, elementSize);
        ByteBuffer data = cpuView(allocation).asByteBuffer().order(ByteOrder.nativeOrder());

        return new GpuBufferSlice.MappedView(this.slice(allocation, usage), data, NO_OP);
    }

    private GpuBufferSlice upload(List<ByteBuffer> data, long alignment, int usage, long minimumAllocation, long elementSize) {
        this.checkOpen();

        // validated first: the running total below rounds up to the alignment and must not overflow on a absurd one
        if (alignment < 1L || alignment > MAX_GPU_ALIGNMENT) {
            throw new IllegalArgumentException("Alignment requirement over maximum supported alignment: " + alignment);
        }

        // the total is the sum of the elements with the running offset rounded up to the alignment after each one
        long total = 0L;

        for (ByteBuffer buffer : data) {
            total = Alignment.alignUp(total + buffer.remaining(), alignment);
        }

        checkRequest(total, elementSize);

        BlockAllocator.Allocation<TransientBlock> allocation = this.upload.allocate(total, alignment, minimumAllocation, elementSize);
        MemorySegment target = cpuView(allocation);
        long offset = 0L;

        for (ByteBuffer buffer : data) {
            if (offset >= allocation.size()) {
                break;
            }

            // a shorter slice (minimumAllocation < size) silently truncates, like the reference
            long length = Math.min(buffer.remaining(), allocation.size() - offset);

            if (length > 0L) {
                MemorySegment.copy(MemorySegment.ofBuffer(buffer), 0L, target, offset, length);
            }

            offset = Alignment.alignUp(offset + buffer.remaining(), alignment);
        }

        return this.slice(allocation, usage);
    }

    private List<GpuBufferSlice> multiUpload(List<ByteBuffer> data, long alignment, int usage) {
        this.checkOpen();

        // validate every request before the first allocation so that a bad element leaves the allocator untouched
        for (ByteBuffer buffer : data) {
            checkRequest(buffer.remaining(), 1L);
        }

        List<GpuBufferSlice> result = new ArrayList<>(data.size());

        for (ByteBuffer buffer : data) {
            int length = buffer.remaining();
            BlockAllocator.Allocation<TransientBlock> allocation = this.upload.allocate(length, alignment, length, 1L);

            if (length > 0) {
                MemorySegment.copy(MemorySegment.ofBuffer(buffer), 0L, cpuView(allocation), 0L, length);
            }

            result.add(this.slice(allocation, usage));
        }

        return result;
    }

    private GpuBufferSlice slice(BlockAllocator.Allocation<TransientBlock> allocation, int usage) {
        return new GpuBufferSlice(new D3D12TransientBuffer(this.ctx, allocation.block(), usage), allocation.offset(), allocation.size());
    }

    private static MemorySegment cpuView(BlockAllocator.Allocation<TransientBlock> allocation) {
        MemorySegment mapped = allocation.block().mapped();

        if (mapped == null) {
            throw new IllegalStateException("Transient block is not CPU visible");
        }

        return mapped.asSlice(allocation.offset(), allocation.size());
    }

    private static void checkRequest(long size, long elementSize) {
        if (size < 0L || size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Transient allocation size must be in [0, " + Integer.MAX_VALUE + "]: " + size);
        }

        if (elementSize < 1L) {
            throw new IllegalArgumentException("Element size must be at least 1: " + elementSize);
        }
    }

    private void checkOpen() {
        if (this.closed) {
            throw new IllegalStateException("The transient memory of the Direct3D 12 device is closed");
        }
    }
}
