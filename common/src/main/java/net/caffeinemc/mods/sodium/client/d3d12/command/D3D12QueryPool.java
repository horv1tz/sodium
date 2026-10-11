// Owner: WP2 (commands + transient memory) - implemented
package net.caffeinemc.mods.sodium.client.d3d12.command;

import com.mojang.renderpearl.api.commands.GpuQueryPool;
import net.caffeinemc.mods.sodium.client.d3d12.core.D3D12Log;
import net.caffeinemc.mods.sodium.client.d3d12.core.DeviceContext;
import net.caffeinemc.mods.sodium.client.d3d12.interop.Hr;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Enums;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12Range;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12GraphicsCommandList;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12QueryHeap;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12Resource;
import net.caffeinemc.mods.sodium.client.d3d12.resource.BufferModes;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Timestamp query pool (spec 01 section 9.2): an {@code ID3D12QueryHeap(TIMESTAMP)} of {@code size} slots plus a
 * persistently mapped READBACK buffer that receives the resolved values, and a {@code long[] writtenInFrame} per slot.
 *
 * <ul>
 *     <li>{@link #write}: bounds check {@code 0 <= index < size} in the backend (the frontend's check is off by one:
 *     {@code index == size} reaches us -> {@link IndexOutOfBoundsException}), {@code EndQuery(TIMESTAMP, index)}, then
 *     {@code ResolveQueryData(index, 1)} into the readback buffer at {@code index * 8} immediately, so a later rewrite
 *     cannot race an earlier resolve; the slot's {@code writtenInFrame} becomes the current frame. The readback buffer is
 *     permanently {@code COPY_DEST}: no barrier.</li>
 *     <li>{@code getValue}: non-blocking; empty until {@code writtenInFrame <= clock.completed()} (never written, or a
 *     rewrite is still pending; a pending slot makes the call refresh {@code completed} once with {@code clock.poll()},
 *     a single non-blocking {@code GetCompletedValue}); otherwise the raw tick value, read between {@code resource.map(0, readRange, ..)} and
 *     {@code unmap(0, emptyRange)} (an extra reference-counted Map; the persistent mapping stays) so that the CPU view is
 *     coherent after the GPU write on platforms that do not snoop CPU caches. {@code getValues(i, n)}:
 *     {@link IndexOutOfBoundsException} if {@code i + n > size}; one Map/Unmap pair for the whole range.</li>
 *     <li>Registered with {@code ObjectRegistry}: the Tracy pool (size 1024) is never closed by the frontend and is
 *     released by the device. {@code close()} is idempotent and deferred.</li>
 * </ul>
 * Must work before the first frame (Tracy creates the pool in the {@code FrontendGpuDevice} constructor).
 *
 * <p>Every access to the native objects after {@code close()} is refused with {@link IllegalStateException}; the mapping
 * lives in a per-pool shared arena that the deferred destroy closes <em>before</em> the COM release (invariant I7).
 *
 * <p>Threading: {@code write} / {@code getValue(s)} on the render thread; {@code close()} from any thread.
 */
public final class D3D12QueryPool implements GpuQueryPool {
    private static final long TIMESTAMP_BYTES = 8L;

    private final DeviceContext ctx;
    private final int size;
    private final D3D12QueryHeap heap;
    private final D3D12Resource readback;
    private final Arena arena;
    private final MemorySegment mapped;
    private final MemorySegment readRange;
    private final MemorySegment emptyRange;
    private final MemorySegment mapOut;
    private final long[] writtenInFrame;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates the query heap and the readback buffer. Nothing is left behind when a step fails (the heap, the buffer and
     * the arena are released before the exception propagates) and the pool is registered only after it is complete.
     *
     * @throws IllegalArgumentException if {@code size < 1}
     */
    public D3D12QueryPool(DeviceContext ctx, int size) {
        if (size < 1) {
            throw new IllegalArgumentException("A query pool needs at least one slot: " + size);
        }

        this.ctx = ctx;
        this.size = size;

        long width = BufferModes.d3dWidth(size * TIMESTAMP_BYTES, false);
        D3D12QueryHeap createdHeap = null;
        D3D12Resource createdBuffer = null;
        Arena createdArena = null;

        try {
            createdHeap = ctx.d3d().createQueryHeap(Enums.QueryHeapType.TIMESTAMP, size);
            createdBuffer = ctx.d3d().createCommittedBuffer(Enums.HeapType.READBACK, width, Enums.ResourceFlags.NONE,
                    Enums.ResourceStates.COPY_DEST);
            createdArena = Arena.ofShared();

            this.readRange = D3D12Range.allocate(createdArena);
            this.emptyRange = D3D12Range.allocate(createdArena);
            this.mapOut = createdArena.allocate(ValueLayout.ADDRESS);

            // persistent mapping of the whole buffer, the CPU may read all of it (null range)
            Hr.check(createdBuffer.map(0, null, this.mapOut), "ID3D12Resource::Map");
            this.mapped = this.mapOut.get(ValueLayout.ADDRESS, 0).reinterpret(width, createdArena, null);

            if (ctx.labels()) {
                createdHeap.setName("Sodium timestamp query heap");
                createdBuffer.setName("Sodium timestamp readback");
            }

            this.heap = createdHeap;
            this.readback = createdBuffer;
            this.arena = createdArena;
        } catch (Throwable t) {
            releaseQuietly(createdArena, createdBuffer, createdHeap);

            throw t;
        }

        this.writtenInFrame = new long[size];
        Arrays.fill(this.writtenInFrame, Long.MAX_VALUE);

        ctx.registry().register(this, "timestamp query pool");
    }

    @Override
    public int size() {
        return this.size;
    }

    /**
     * Records a timestamp into slot {@code index} of the open list; used by the encoder and by passes.
     *
     * @throws IndexOutOfBoundsException if {@code index} is outside {@code [0, size)}
     * @throws IllegalStateException     if the pool was closed
     */
    public void write(D3D12GraphicsCommandList list, int index) {
        Objects.checkIndex(index, this.size);
        this.checkOpen();

        list.endQuery(this.heap, Enums.QueryType.TIMESTAMP, index);
        list.resolveQueryData(this.heap, Enums.QueryType.TIMESTAMP, index, 1, this.readback, index * TIMESTAMP_BYTES);

        // the slot reads as "pending" until this frame completed: an old value is never reported for a pending rewrite
        this.writtenInFrame[index] = this.ctx.clock().current();
    }

    @Override
    public OptionalLong getValue(int index) {
        Objects.checkIndex(index, this.size);
        this.checkOpen();

        if (!this.isComplete(index, this.ctx.clock().completed(), true)) {
            return OptionalLong.empty();
        }

        long begin = index * TIMESTAMP_BYTES;

        this.beginCpuRead(begin, begin + TIMESTAMP_BYTES);

        try {
            return OptionalLong.of(this.mapped.get(ValueLayout.JAVA_LONG, begin));
        } finally {
            this.endCpuRead();
        }
    }

    @Override
    public OptionalLong[] getValues(int index, int count) {
        if (index < 0 || count < 0 || (long) index + count > this.size) {
            throw new IndexOutOfBoundsException("Query range [" + index + ", " + ((long) index + count) + ") is outside the pool of " + this.size);
        }

        this.checkOpen();

        OptionalLong[] values = new OptionalLong[count];
        long completed = this.ctx.clock().completed();
        boolean polled = false;
        boolean any = false;

        for (int i = 0; i < count; i++) {
            long written = this.writtenInFrame[index + i];

            // a pending slot may have completed since the cached value was refreshed: look once per call, never block
            if (!polled && written != Long.MAX_VALUE && written > completed) {
                completed = this.ctx.clock().poll();
                polled = true;
            }

            if (written <= completed) {
                any = true;
            } else {
                values[i] = OptionalLong.empty();
            }
        }

        if (any) {
            long begin = index * TIMESTAMP_BYTES;

            this.beginCpuRead(begin, begin + count * TIMESTAMP_BYTES);

            try {
                for (int i = 0; i < count; i++) {
                    if (values[i] == null) {
                        values[i] = OptionalLong.of(this.mapped.get(ValueLayout.JAVA_LONG, begin + i * TIMESTAMP_BYTES));
                    }
                }
            } finally {
                this.endCpuRead();
            }
        }

        return values;
    }

    /** Idempotent and thread-safe; the native objects are released after the frame that recorded the close completed. */
    @Override
    public void close() {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }

        this.ctx.registry().unregister(this);

        Arena arenaToClose = this.arena;
        D3D12Resource bufferToRelease = this.readback;
        D3D12QueryHeap heapToRelease = this.heap;

        this.ctx.defer(() -> releaseQuietly(arenaToClose, bufferToRelease, heapToRelease));
    }

    /**
     * @return true if the last write of slot {@code index} has completed on the GPU (a never-written slot never has)
     * @param poll refresh the cached completed frame once (one {@code GetCompletedValue}, never a wait) if the slot is
     *             written but not yet known to be complete
     */
    private boolean isComplete(int index, long completed, boolean poll) {
        long written = this.writtenInFrame[index];

        if (written <= completed) {
            return true;
        }

        return poll && written != Long.MAX_VALUE && written <= this.ctx.clock().poll();
    }

    /** {@code Map(0, readRange)}: the extra reference-counted map makes the CPU view coherent on non-snooping platforms. */
    private void beginCpuRead(long begin, long end) {
        D3D12Range.setBegin(this.readRange, begin);
        D3D12Range.setEnd(this.readRange, end);

        Hr.check(this.readback.map(0, this.readRange, this.mapOut), "ID3D12Resource::Map");
    }

    /** {@code Unmap(0, emptyRange)}: nothing was written by the CPU. */
    private void endCpuRead() {
        this.readback.unmap(0, this.emptyRange);
    }

    private void checkOpen() {
        if (this.closed.get()) {
            throw new IllegalStateException("The timestamp query pool is closed");
        }
    }

    private static void releaseQuietly(Arena arena, D3D12Resource buffer, D3D12QueryHeap heap) {
        try {
            if (arena != null) {
                arena.close();
            }
        } catch (Throwable t) {
            D3D12Log.error("Failed to close the mapping of a query pool", t);
        }

        try {
            if (buffer != null) {
                buffer.release();
            }
        } catch (Throwable t) {
            D3D12Log.error("Failed to release the readback buffer of a query pool", t);
        }

        try {
            if (heap != null) {
                heap.release();
            }
        } catch (Throwable t) {
            D3D12Log.error("Failed to release a query heap", t);
        }
    }

    DeviceContext context() {
        return this.ctx;
    }
}
