// Owner: WP1 (device core) - implemented; WP1 owner adds tests (FrameClockTest with a fake FrameFence)
package net.caffeinemc.mods.sodium.client.d3d12.core;

import com.mojang.renderpearl.api.commands.GpuFence;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12CommandQueue;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12Device;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12Fence;

/**
 * The frame counter and its fence: "frame" == one {@code CommandEncoderBackend.submit()}, fence value == frame index
 * (invariant I1; spec 01 section 1.4).
 *
 * <ul>
 *     <li>{@code current()} starts at {@link #FIRST_FRAME} (2); the fence starts at 1. Frame {@code f} is recorded while
 *     {@code current() == f}; {@link #signalAndAdvance} signals {@code f} on the queue and makes {@code f + 1} current.</li>
 *     <li>At most {@link #FRAMES_IN_FLIGHT} (2) frames are in flight: after signalling {@code f}, {@code submit()} waits for
 *     {@code f - 1}. Slot of a frame = {@code frame & 1}.</li>
 *     <li>{@code completed()} is monotonic ({@code max}, unlike Vulkan's assignment).</li>
 *     <li>{@link #flushGpu()} waits for <em>all</em> work queued so far, including {@code Present}, by signalling a
 *     private auxiliary fence. It must never use the frame fence: signalling {@code current()} early would make the
 *     {@code GpuFence}s of the frame being recorded look complete. Used for swap-chain resize/close, shutdown and probes.</li>
 * </ul>
 *
 * <p>Threading: {@code current()} and {@code completed()} are volatile reads from any thread; everything else runs on
 * the render thread.
 */
public final class FrameClock implements AutoCloseable {
    public static final int FRAMES_IN_FLIGHT = 2;
    public static final long FIRST_FRAME = 2L;

    /** How long a "wait forever" is sliced so that a removed device is detected instead of hanging. */
    private static final long INFINITE_SLICE_MILLIS = 5_000L;

    /** The seam between the clock and a native {@code ID3D12Fence}; unit tests provide a fake. */
    public interface FrameFence extends AutoCloseable {
        /** @return the raw {@code GetCompletedValue()}; {@code -1L} means the device was removed */
        long completedValue();

        /** @param timeoutMillis negative = infinite, 0 = poll @return true if the fence reached {@code value} */
        boolean waitFor(long value, long timeoutMillis);

        /** Queues {@code Signal(fence, value)} on the direct queue. */
        void signalOnQueue(long value);

        @Override
        void close();
    }

    /** {@link FrameFence} over a real fence and queue. */
    public static final class NativeFrameFence implements FrameFence {
        private final D3D12Fence fence;
        private final D3D12CommandQueue queue;

        public NativeFrameFence(D3D12Fence fence, D3D12CommandQueue queue) {
            this.fence = fence;
            this.queue = queue;
        }

        @Override
        public long completedValue() {
            return this.fence.getCompletedValue();
        }

        @Override
        public boolean waitFor(long value, long timeoutMillis) {
            return this.fence.waitForValue(value, timeoutMillis);
        }

        @Override
        public void signalOnQueue(long value) {
            this.queue.signal(this.fence, value);
        }

        @Override
        public void close() {
            this.fence.close();
        }
    }

    private final FrameFence frameFence;
    private final FrameFence auxFence;
    private final ErrorPolicy errors;

    private volatile long current = FIRST_FRAME;
    private volatile long completed = FIRST_FRAME - 1;
    private long auxValue;

    public FrameClock(FrameFence frameFence, FrameFence auxFence, ErrorPolicy errors) {
        this.frameFence = frameFence;
        this.auxFence = auxFence;
        this.errors = errors;
    }

    /** Creates the two native fences (frame fence initial value 1, aux fence 0). */
    public static FrameClock create(D3D12Device device, D3D12CommandQueue queue, ErrorPolicy errors) {
        D3D12Fence frame = device.createFence(FIRST_FRAME - 1);
        D3D12Fence aux = device.createFence(0L);

        return new FrameClock(new NativeFrameFence(frame, queue), new NativeFrameFence(aux, queue), errors);
    }

    /** @return the index of the frame being recorded */
    public long current() {
        return this.current;
    }

    /** @return the highest frame known to be complete on the GPU (cached; see {@link #poll()}) */
    public long completed() {
        return this.completed;
    }

    /** @return {@code current() & 1}: the per-frame slot of allocators and descriptor rings */
    public int slot() {
        return (int) (this.current & 1L);
    }

    /**
     * Refreshes {@link #completed()} from the fence without blocking.
     *
     * @throws com.mojang.renderpearl.api.device.GpuDeviceLossException if the fence reports device removal
     */
    public long poll() {
        long value = this.frameFence.completedValue();

        if (value == -1L) {
            this.errors.checkDeviceRemoved("ID3D12Fence::GetCompletedValue");
            throw new IllegalStateException("ID3D12Fence reported UINT64_MAX but the device was not removed");
        }

        if (value > this.completed) {
            this.completed = value;
        }

        return this.completed;
    }

    /**
     * {@link GpuFence#awaitCompletion} semantics for the fence of {@code frame} (spec 01 section 9.1):
     * <ul>
     *     <li>already complete: {@code true} for any timeout;</li>
     *     <li>{@code frame >= current()} (the frame is not submitted yet): {@code timeoutNs == 0} returns {@code false},
     *     anything else throws {@link IllegalStateException} ("Cannot wait on a fence for the current submit");</li>
     *     <li>{@code timeoutNs == 0}: poll; {@code timeoutNs < 0} ({@link GpuFence#NO_TIMEOUT}): wait forever in
     *     {@value #INFINITE_SLICE_MILLIS} ms slices, testing for device removal between slices;
     *     positive: wait, converting nanoseconds to milliseconds <em>rounding up</em>.</li>
     * </ul>
     * Device removal discovered while polling or after a blocking wait throws {@code GpuDeviceLossException}.
     *
     * @return true if the frame completed within the timeout
     */
    public boolean await(long frame, long timeoutNs) {
        if (this.completed >= frame || this.poll() >= frame) {
            return true;
        }

        if (frame >= this.current) {
            if (timeoutNs == 0L) {
                return false;
            }

            throw new IllegalStateException("Cannot wait on a fence for the current submit");
        }

        if (timeoutNs == 0L) {
            return false;
        }

        boolean reached;

        if (timeoutNs < 0L) {
            do {
                reached = this.frameFence.waitFor(frame, INFINITE_SLICE_MILLIS);

                if (!reached) {
                    this.errors.checkDeviceRemoved("fence wait");
                }
            } while (!reached);
        } else {
            long millis = Math.max(1L, (timeoutNs + 999_999L) / 1_000_000L);
            reached = this.frameFence.waitFor(frame, millis);
        }

        this.poll();
        this.errors.checkDeviceRemoved("fence wait");

        return reached && this.completed >= frame;
    }

    /** Queues {@code Signal(frame fence, current)} and makes the next frame current. Called by {@code submit()} only. */
    public void signalAndAdvance() {
        long frame = this.current;

        this.frameFence.signalOnQueue(frame);
        this.current = frame + 1;
    }

    /**
     * Blocks until every command queued on the direct queue so far (including presents) has finished. Not for the
     * per-frame path.
     *
     * @param timeoutNs cap in nanoseconds; on expiry the device-loss check runs and an exception is thrown
     */
    public void flushGpu(long timeoutNs) {
        long value = ++this.auxValue;

        this.auxFence.signalOnQueue(value);

        long millis = Math.max(1L, (timeoutNs + 999_999L) / 1_000_000L);

        if (!this.auxFence.waitFor(value, millis)) {
            throw this.errors.timeout("flushing the GPU");
        }

        this.errors.checkDeviceRemoved("flushing the GPU");
        this.poll();
    }

    @Override
    public void close() {
        this.frameFence.close();
        this.auxFence.close();
    }
}
