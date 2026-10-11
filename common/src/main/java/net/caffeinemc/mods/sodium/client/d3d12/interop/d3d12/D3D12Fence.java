package net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12;

import net.caffeinemc.mods.sodium.client.d3d12.interop.Com;
import net.caffeinemc.mods.sodium.client.d3d12.interop.D3D12Exception;
import net.caffeinemc.mods.sodium.client.d3d12.interop.Hr;
import net.caffeinemc.mods.sodium.client.d3d12.interop.Kernel32;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Vtbl;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * ID3D12Fence: a monotonically increasing 64-bit counter shared between the CPU and the GPU queues.
 *
 * <p>Typical use: {@link D3D12CommandQueue#signal(D3D12Fence, long)} a new value after submitting work, then
 * {@link #waitForValue(long, long)} (CPU) or {@link D3D12CommandQueue#wait(D3D12Fence, long)} (GPU) for it.
 *
 * <p>Thread safety: free-threaded, every method may be called from any thread.
 */
public final class D3D12Fence extends D3D12Object.DeviceChild {
    private static final MethodHandle GET_COMPLETED_VALUE = Com.method(FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    private static final MethodHandle SET_EVENT_ON_COMPLETION = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS));
    private static final MethodHandle SIGNAL = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG));

    public D3D12Fence(MemorySegment pointer) {
        super(pointer);
    }

    /**
     * @return the highest value the GPU has completed (signalled), or {@code -1L} ({@code UINT64_MAX}) if the device
     * has been removed
     */
    public long getCompletedValue() {
        try {
            return (long) GET_COMPLETED_VALUE.invokeExact(this.fn(Vtbl.ID3D12Fence.GET_COMPLETED_VALUE), this.ptr());
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Arranges for the given event to be signalled once the fence reaches {@code value}. If the fence has already
     * reached it, the event is signalled immediately.
     *
     * @param event a Win32 event HANDLE (for example from {@link Kernel32#createEvent(boolean, boolean)}), or
     *              {@link MemorySegment#NULL} to block inside the call until the value is reached
     * @return the raw HRESULT ({@code S_OK} on success)
     */
    public int setEventOnCompletion(long value, MemorySegment event) {
        try {
            return (int) SET_EVENT_ON_COMPLETION.invokeExact(this.fn(Vtbl.ID3D12Fence.SET_EVENT_ON_COMPLETION), this.ptr(), value, event);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * @return true if the fence has reached {@code value} (compared as unsigned 64-bit numbers), or the device was
     * removed, in which case the fence reads as {@code UINT64_MAX}
     */
    public boolean isReached(long value) {
        return Long.compareUnsigned(this.getCompletedValue(), value) >= 0;
    }

    /**
     * Sets the fence to {@code value} from the CPU (the value must not be lower than the current one).
     *
     * @throws D3D12Exception if the call fails
     */
    public void signal(long value) {
        try {
            int hr = (int) SIGNAL.invokeExact(this.fn(Vtbl.ID3D12Fence.SIGNAL), this.ptr(), value);
            Hr.check(hr, "ID3D12Fence::Signal");
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Blocks the calling thread until the fence has reached {@code value}. Creates (and destroys) a temporary event,
     * so for waits on a hot path prefer {@link #waitForValue(long, long, MemorySegment)} with a reusable event.
     *
     * @param timeoutMillis maximum time to wait; a negative value waits forever
     * @return true if the value has been reached, false on timeout
     * @throws D3D12Exception if the wait could not be set up or failed
     */
    public boolean waitForValue(long value, long timeoutMillis) {
        if (this.isReached(value)) {
            return true;
        }

        MemorySegment event = Kernel32.createEvent(false, false);

        try {
            return this.waitForValue(value, timeoutMillis, event);
        } finally {
            Kernel32.closeHandle(event);
        }
    }

    /**
     * Like {@link #waitForValue(long, long)} but uses a caller-provided auto-reset event, which must not be used by
     * another wait at the same time. A timed-out wait leaves the event armed, so it may be signalled later for a value
     * that was waited on earlier; this method therefore re-checks the fence after every wake-up and keeps waiting
     * (for the remaining time) until the requested value has really been reached.
     *
     * @param event an auto-reset event HANDLE created with {@link Kernel32#createEvent(boolean, boolean)}
     */
    public boolean waitForValue(long value, long timeoutMillis, MemorySegment event) {
        final boolean infinite = timeoutMillis < 0 || timeoutMillis >= 0xFFFFFFFEL;
        final long deadline = infinite ? 0L : System.nanoTime() + timeoutMillis * 1_000_000L;

        while (true) {
            if (this.isReached(value)) {
                return true;
            }

            Hr.check(this.setEventOnCompletion(value, event), "ID3D12Fence::SetEventOnCompletion");

            int win32Timeout = Kernel32.INFINITE;

            if (!infinite) {
                long remainingNanos = deadline - System.nanoTime();
                win32Timeout = remainingNanos <= 0 ? 0 : (int) Math.min(0xFFFFFFFDL, (remainingNanos + 999_999L) / 1_000_000L);
            }

            int wait = Kernel32.waitForSingleObject(event, win32Timeout);

            if (wait == Kernel32.WAIT_OBJECT_0) {
                continue;
            } else if (wait == Kernel32.WAIT_TIMEOUT) {
                return this.isReached(value);
            }

            throw new D3D12Exception("WaitForSingleObject failed while waiting for a fence");
        }
    }
}
