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

    private static final class Handles {
        static final MethodHandle CREATE_EVENT = Win32Libraries.function(Win32Libraries.kernel32(), "CreateEventW",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
        static final MethodHandle WAIT_FOR_SINGLE_OBJECT = Win32Libraries.function(Win32Libraries.kernel32(), "WaitForSingleObject",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        static final MethodHandle SET_EVENT = Win32Libraries.function(Win32Libraries.kernel32(), "SetEvent",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        static final MethodHandle RESET_EVENT = Win32Libraries.function(Win32Libraries.kernel32(), "ResetEvent",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        static final MethodHandle CLOSE_HANDLE = Win32Libraries.function(Win32Libraries.kernel32(), "CloseHandle",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
    }

    private Kernel32() {
    }

    /**
     * Creates an unnamed event object.
     *
     * @return the event HANDLE
     */
    public static MemorySegment createEvent(boolean manualReset, boolean initiallySignalled) {
        try {
            MemorySegment handle = (MemorySegment) Handles.CREATE_EVENT.invokeExact(MemorySegment.NULL,
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
            return (int) Handles.WAIT_FOR_SINGLE_OBJECT.invokeExact(handle, milliseconds);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    public static void setEvent(MemorySegment handle) {
        try {
            int ignored = (int) Handles.SET_EVENT.invokeExact(handle);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    public static void resetEvent(MemorySegment handle) {
        try {
            int ignored = (int) Handles.RESET_EVENT.invokeExact(handle);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    public static void closeHandle(MemorySegment handle) {
        try {
            int ignored = (int) Handles.CLOSE_HANDLE.invokeExact(handle);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }
}
