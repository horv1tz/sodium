package net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12;

import net.caffeinemc.mods.sodium.client.d3d12.interop.Com;
import net.caffeinemc.mods.sodium.client.d3d12.interop.Hr;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Vtbl;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12CommandQueueDesc;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * ID3D12CommandQueue: the submission endpoint of the GPU (direct, compute or copy engine). Created by
 * {@link D3D12Device#createCommandQueue}.
 *
 * <p>Thread safety: free-threaded. {@code ExecuteCommandLists}, {@code Signal} and {@code Wait} may be called from
 * any thread; they are ordered by the order in which the calls reach the queue.
 */
public final class D3D12CommandQueue extends D3D12Object.DeviceChild {
    /** Number of command list pointers the shared scratch area holds; larger submissions allocate. */
    private static final int SCRATCH_LISTS = 16;

    private static final MethodHandle EXECUTE_COMMAND_LISTS = Com.method(FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS));
    private static final MethodHandle SIGNAL = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG));
    private static final MethodHandle GET_TIMESTAMP_FREQUENCY = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle GET_CLOCK_CALIBRATION = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle GET_DESC = Com.method(FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS));
    private static final MethodHandle MARKER = Com.method(FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle END_EVENT = Com.method(FunctionDescriptor.ofVoid(ADDRESS));

    /** Holds the pointer array of the short submissions; guarded by itself. */
    private final MemorySegment scratch = Arena.ofAuto().allocate(8L * SCRATCH_LISTS, 8);

    public D3D12CommandQueue(MemorySegment pointer) {
        super(pointer);
    }

    /**
     * Submits one command list (which must be closed). Does not allocate.
     */
    public void executeCommandLists(D3D12GraphicsCommandList list) {
        synchronized (this.scratch) {
            this.scratch.set(ADDRESS, 0, list.ptr());
            this.executeCommandLists(1, this.scratch);
        }
    }

    /**
     * Submits two command lists in order (which must be closed). Does not allocate.
     */
    public void executeCommandLists(D3D12GraphicsCommandList first, D3D12GraphicsCommandList second) {
        synchronized (this.scratch) {
            this.scratch.set(ADDRESS, 0, first.ptr());
            this.scratch.set(ADDRESS, 8, second.ptr());
            this.executeCommandLists(2, this.scratch);
        }
    }

    /**
     * Submits several command lists in order (which must be closed). Does not allocate for up to 16 lists.
     */
    public void executeCommandLists(D3D12GraphicsCommandList... lists) {
        if (lists.length <= SCRATCH_LISTS) {
            synchronized (this.scratch) {
                for (int i = 0; i < lists.length; i++) {
                    this.scratch.set(ADDRESS, i * 8L, lists[i].ptr());
                }

                this.executeCommandLists(lists.length, this.scratch);
            }
        } else {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment array = arena.allocate(8L * lists.length, 8);

                for (int i = 0; i < lists.length; i++) {
                    array.set(ADDRESS, i * 8L, lists[i].ptr());
                }

                this.executeCommandLists(lists.length, array);
            }
        }
    }

    /**
     * Raw form of ExecuteCommandLists.
     *
     * @param count number of lists
     * @param lists native array of {@code count} {@code ID3D12CommandList*} (8 bytes each)
     */
    public void executeCommandLists(int count, MemorySegment lists) {
        try {
            EXECUTE_COMMAND_LISTS.invokeExact(this.fn(Vtbl.ID3D12CommandQueue.EXECUTE_COMMAND_LISTS), this.ptr(), count, lists);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Queues a GPU-side update of {@code fence} to {@code value} once all previously submitted work has finished.
     *
     * @throws net.caffeinemc.mods.sodium.client.d3d12.interop.D3D12Exception if the call fails (for example because
     *                                                                         the device was removed)
     */
    public void signal(D3D12Fence fence, long value) {
        try {
            int hr = (int) SIGNAL.invokeExact(this.fn(Vtbl.ID3D12CommandQueue.SIGNAL), this.ptr(), fence.ptr(), value);
            Hr.check(hr, "ID3D12CommandQueue::Signal");
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Makes this queue wait on the GPU until {@code fence} has reached {@code value}; later submissions to this queue
     * do not start before that.
     *
     * @throws net.caffeinemc.mods.sodium.client.d3d12.interop.D3D12Exception if the call fails
     */
    public void wait(D3D12Fence fence, long value) {
        try {
            int hr = (int) SIGNAL.invokeExact(this.fn(Vtbl.ID3D12CommandQueue.WAIT), this.ptr(), fence.ptr(), value);
            Hr.check(hr, "ID3D12CommandQueue::Wait");
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * @return the frequency in Hz of the GPU timestamps written by timestamp queries on this queue
     * @throws net.caffeinemc.mods.sodium.client.d3d12.interop.D3D12Exception if the queue does not support timestamps
     */
    public long getTimestampFrequency() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(JAVA_LONG);
            int hr = (int) GET_TIMESTAMP_FREQUENCY.invokeExact(this.fn(Vtbl.ID3D12CommandQueue.GET_TIMESTAMP_FREQUENCY), this.ptr(), out);
            Hr.check(hr, "ID3D12CommandQueue::GetTimestampFrequency");

            return out.get(JAVA_LONG, 0);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Samples the GPU and CPU clocks at the same moment, to correlate GPU timestamps with CPU time.
     *
     * @return {gpuTimestamp, cpuTimestamp}; the GPU value is in units of {@link #getTimestampFrequency()}, the CPU
     * value in {@code QueryPerformanceCounter} units
     * @throws net.caffeinemc.mods.sodium.client.d3d12.interop.D3D12Exception if the call fails
     */
    public long[] getClockCalibration() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment gpu = arena.allocate(JAVA_LONG);
            MemorySegment cpu = arena.allocate(JAVA_LONG);
            int hr = (int) GET_CLOCK_CALIBRATION.invokeExact(this.fn(Vtbl.ID3D12CommandQueue.GET_CLOCK_CALIBRATION), this.ptr(), gpu, cpu);
            Hr.check(hr, "ID3D12CommandQueue::GetClockCalibration");

            return new long[]{gpu.get(JAVA_LONG, 0), cpu.get(JAVA_LONG, 0)};
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Fills a {@code D3D12_COMMAND_QUEUE_DESC} (see {@link D3D12CommandQueueDesc}). Does not allocate.
     *
     * @param out at least {@link D3D12CommandQueueDesc#SIZE} bytes of native memory
     */
    public void getDesc(MemorySegment out) {
        try {
            long ignored = (long) GET_DESC.invokeExact(this.fn(Vtbl.ID3D12CommandQueue.GET_DESC), this.ptr(), out);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Decoded {@code D3D12_COMMAND_QUEUE_DESC}.
     *
     * @param type     {@code D3D12_COMMAND_LIST_TYPE} ({@code Enums.CommandListType})
     * @param priority {@code D3D12_COMMAND_QUEUE_PRIORITY}
     * @param flags    {@code D3D12_COMMAND_QUEUE_FLAGS}
     * @param nodeMask GPU node mask
     */
    public record Desc(int type, int priority, int flags, int nodeMask) {
    }

    public Desc getDesc() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment desc = D3D12CommandQueueDesc.allocate(arena);
            this.getDesc(desc);

            return new Desc(D3D12CommandQueueDesc.getType(desc), D3D12CommandQueueDesc.getPriority(desc),
                    D3D12CommandQueueDesc.getFlags(desc), D3D12CommandQueueDesc.getNodeMask(desc));
        }
    }

    /**
     * Inserts a PIX/debugger marker into the queue timeline.
     *
     * @param metadata 0 if {@code data} is a UTF-16 string, 1 if it is an ANSI string
     * @param data     the marker payload
     * @param size     size of {@code data} in bytes, including the terminator
     */
    public void setMarker(int metadata, MemorySegment data, int size) {
        try {
            MARKER.invokeExact(this.fn(Vtbl.ID3D12CommandQueue.SET_MARKER), this.ptr(), metadata, data, size);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Opens a named PIX/debugger event region on the queue; close it with {@link #endEvent()}.
     *
     * @param metadata 0 if {@code data} is a UTF-16 string, 1 if it is an ANSI string
     * @param size     size of {@code data} in bytes, including the terminator
     */
    public void beginEvent(int metadata, MemorySegment data, int size) {
        try {
            MARKER.invokeExact(this.fn(Vtbl.ID3D12CommandQueue.BEGIN_EVENT), this.ptr(), metadata, data, size);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    public void endEvent() {
        try {
            END_EVENT.invokeExact(this.fn(Vtbl.ID3D12CommandQueue.END_EVENT), this.ptr());
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Convenience for {@link #setMarker(int, MemorySegment, int)} with an ANSI text. Allocates.
     */
    public void setMarker(String text) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bytes = arena.allocateFrom(text, StandardCharsets.US_ASCII);
            this.setMarker(1, bytes, (int) bytes.byteSize());
        }
    }

    /**
     * Convenience for {@link #beginEvent(int, MemorySegment, int)} with an ANSI text. Allocates.
     */
    public void beginEvent(String text) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bytes = arena.allocateFrom(text, StandardCharsets.US_ASCII);
            this.beginEvent(1, bytes, (int) bytes.byteSize());
        }
    }
}
