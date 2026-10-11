package net.caffeinemc.mods.sodium.client.d3d12.interop;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.invoke.MethodHandle;

/**
 * Support for calling COM methods through their vtables.
 *
 * <p>A COM method handle is created once per method signature with {@link #method(FunctionDescriptor)}. Its first
 * parameter is the address of the function to call (read from the object's vtable by {@link ComObject#fn(int)}), the
 * second is the {@code this} pointer, followed by the method's own parameters:
 *
 * <pre>{@code
 * private static final MethodHandle CLOSE = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS));
 * ...
 * int hr = (int) CLOSE.invokeExact(this.fn(9), this.ptr());
 * }</pre>
 *
 * <p>Handles must be invoked with {@code invokeExact}, so the Java types at the call site have to match the descriptor
 * exactly (int for HRESULT/UINT/BOOL, long for UINT64/SIZE_T, MemorySegment for pointers).
 */
public final class Com {
    private static final Linker LINKER = Linker.nativeLinker();

    private Com() {
    }

    static Linker linker() {
        return LINKER;
    }

    /**
     * Creates a downcall handle for a function whose address is only known at call time.
     */
    public static MethodHandle method(FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(descriptor);
    }

    /**
     * Converts whatever a method handle threw into an unchecked exception. Use as {@code throw Com.rethrow(t);}.
     */
    public static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException e) {
            return e;
        }

        if (t instanceof Error e) {
            throw e;
        }

        return new D3D12Exception("Native call failed", t);
    }
}
