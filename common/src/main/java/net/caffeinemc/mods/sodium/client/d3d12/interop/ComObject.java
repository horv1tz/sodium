package net.caffeinemc.mods.sodium.client.d3d12.interop;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.function.Function;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Base class for wrappers around a COM interface pointer. A wrapper owns exactly one reference to the object, which
 * is dropped by {@link #release()} / {@link #close()}.
 *
 * <p>Concrete wrappers expose one method per COM method they need, calling through {@link #fn(int)} with the slot
 * index from the interface's vtable (IUnknown occupies slots 0..2, interfaces derived from other interfaces continue
 * after the base interface's methods).
 */
public abstract class ComObject implements AutoCloseable {
    private static final long VTABLE_BYTES = 8L * 160;

    private static final MethodHandle ADD_REF = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle RELEASE = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle QUERY_INTERFACE = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

    private MemorySegment self;
    private final MemorySegment vtable;

    /**
     * @param pointer the interface pointer; ownership of one reference is transferred to this wrapper
     */
    protected ComObject(MemorySegment pointer) {
        if (pointer.address() == 0L) {
            throw new D3D12Exception("Null COM interface pointer");
        }

        this.self = MemorySegment.ofAddress(pointer.address());

        long vtableAddress = this.self.reinterpret(8).get(JAVA_LONG, 0);
        this.vtable = MemorySegment.ofAddress(vtableAddress).reinterpret(VTABLE_BYTES);
    }

    /**
     * @return the raw interface pointer, to pass as the {@code this} argument of native calls
     * @throws IllegalStateException if the wrapper has already been released
     */
    public final MemorySegment ptr() {
        MemorySegment self = this.self;

        if (self == null) {
            throw new IllegalStateException(this.getClass().getSimpleName() + " used after release");
        }

        return self;
    }

    public final long address() {
        return this.ptr().address();
    }

    public final boolean isReleased() {
        return this.self == null;
    }

    /**
     * Reads the function pointer stored in the given vtable slot.
     */
    protected final MemorySegment fn(int slot) {
        return this.vtable.get(ADDRESS, slot * 8L);
    }

    public final int addRef() {
        try {
            return (int) ADD_REF.invokeExact(this.fn(1), this.ptr());
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Drops this wrapper's reference. Calling it again is a no-op.
     *
     * @return the object's remaining reference count as reported by the object (for diagnostics only), or 0 if the
     * wrapper had already been released
     */
    public final int release() {
        MemorySegment self = this.self;

        if (self == null) {
            return 0;
        }

        this.self = null;

        try {
            return (int) RELEASE.invokeExact(this.vtable.get(ADDRESS, 2 * 8L), self);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    @Override
    public void close() {
        this.release();
    }

    /**
     * Queries another interface of the same object.
     *
     * @param iid  the IID of the requested interface (see {@link Guid#of(String)})
     * @param wrap constructor of the wrapper for the requested interface; receives the new interface pointer
     */
    public final <T extends ComObject> T queryInterface(MemorySegment iid, Function<MemorySegment, T> wrap) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            int hr = (int) QUERY_INTERFACE.invokeExact(this.fn(0), this.ptr(), iid, out);
            Hr.check(hr, "QueryInterface");

            return wrap.apply(out.get(ADDRESS, 0));
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Like {@link #queryInterface(MemorySegment, Function)} but returns {@code null} instead of throwing when the
     * object does not support the interface (E_NOINTERFACE).
     */
    public final <T extends ComObject> T tryQueryInterface(MemorySegment iid, Function<MemorySegment, T> wrap) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            int hr = (int) QUERY_INTERFACE.invokeExact(this.fn(0), this.ptr(), iid, out);

            if (hr < 0) {
                return null;
            }

            return wrap.apply(out.get(ADDRESS, 0));
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }
}
