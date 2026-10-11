package net.caffeinemc.mods.sodium.client.d3d12.interop;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * The few kernel32 functions needed for fence events.
 */
public final class Kernel32 {
    public static final int INFINITE = 0xFFFFFFFF;
    public static final int WAIT_OBJECT_0 = 0;
    public static final int WAIT_TIMEOUT = 258;
    public static final int WAIT_FAILED = 0xFFFFFFFF;

    /**
     * Native handles, linked on first use. They are held in an instance (not static fields) so that a failure to load
     * kernel32 surfaces as a D3D12Exception on every call instead of a one-off ExceptionInInitializerError.
     */
    private static final class Handles {
        final MethodHandle CREATE_EVENT = Win32Libraries.function(Win32Libraries.kernel32(), "CreateEventW",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
        final MethodHandle WAIT_FOR_SINGLE_OBJECT = Win32Libraries.function(Win32Libraries.kernel32(), "WaitForSingleObject",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        final MethodHandle SET_EVENT = Win32Libraries.function(Win32Libraries.kernel32(), "SetEvent",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        final MethodHandle RESET_EVENT = Win32Libraries.function(Win32Libraries.kernel32(), "ResetEvent",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        final MethodHandle CLOSE_HANDLE = Win32Libraries.function(Win32Libraries.kernel32(), "CloseHandle",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
    }

    private static volatile Handles handles;

    private Kernel32() {
    }

    private static Handles handles() {
        Handles h = handles;

        if (h == null) {
            synchronized (Kernel32.class) {
                h = handles;

                if (h == null) {
                    handles = h = new Handles();
                }
            }
        }

        return h;
    }

    /**
     * Creates an unnamed event object.
     *
     * @return the event HANDLE
     */
    public static MemorySegment createEvent(boolean manualReset, boolean initiallySignalled) {
        try {
            MemorySegment handle = (MemorySegment) handles().CREATE_EVENT.invokeExact(MemorySegment.NULL,
                    manualReset ? 1 : 0, initiallySignalled ? 1 : 0, MemorySegment.NULL);

            if (handle.address() == 0L) {
                throw new D3D12Exception("CreateEventW failed");
            }

            return handle;
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * @param milliseconds timeout, or {@link #INFINITE}
     * @return {@link #WAIT_OBJECT_0}, {@link #WAIT_TIMEOUT} or {@link #WAIT_FAILED}
     */
    public static int waitForSingleObject(MemorySegment handle, int milliseconds) {
        try {
            return (int) handles().WAIT_FOR_SINGLE_OBJECT.invokeExact(handle, milliseconds);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    public static void setEvent(MemorySegment handle) {
        try {
            int ignored = (int) handles().SET_EVENT.invokeExact(handle);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    public static void resetEvent(MemorySegment handle) {
        try {
            int ignored = (int) handles().RESET_EVENT.invokeExact(handle);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    public static void closeHandle(MemorySegment handle) {
        try {
            int ignored = (int) handles().CLOSE_HANDLE.invokeExact(handle);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }
}
