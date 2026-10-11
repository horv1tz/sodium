package net.caffeinemc.mods.sodium.client.d3d12.interop.dxgi;

import net.caffeinemc.mods.sodium.client.d3d12.interop.Com;
import net.caffeinemc.mods.sodium.client.d3d12.interop.D3D12Exception;
import net.caffeinemc.mods.sodium.client.d3d12.interop.Hr;
import net.caffeinemc.mods.sodium.client.d3d12.interop.Win32Libraries;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Defines;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Iids;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Static entry points of {@code dxgi.dll}.
 *
 * <p>Nothing in this class touches a native library until a method asks for it, so loading the class is safe on
 * every platform. Everything else in this package is built on the factory returned by {@link #createFactory(boolean)}.
 *
 * <p>Thread safety: all methods may be called from any thread.
 */
public final class Dxgi {
    private static final MethodHandle QUERY_INTERFACE = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle RELEASE = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /**
     * Linked on first use (not in a static initialiser), so that a failure to load {@code dxgi.dll} surfaces as a
     * {@link D3D12Exception} at the call site every time instead of as a class initialisation error.
     */
    private static volatile MethodHandle createDxgiFactory2;

    private Dxgi() {
    }

    private static MethodHandle createDxgiFactory2() {
        MethodHandle handle = createDxgiFactory2;

        if (handle == null) {
            synchronized (Dxgi.class) {
                handle = createDxgiFactory2;

                if (handle == null) {
                    handle = Win32Libraries.function(Win32Libraries.dxgi(), "CreateDXGIFactory2",
                            FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
                    createDxgiFactory2 = handle;
                }
            }
        }

        return handle;
    }

    /**
     * @return true if this process can call into {@code dxgi.dll} (Windows, library present, native access enabled)
     */
    public static boolean isAvailable() {
        try {
            return createDxgiFactory2() != null;
        } catch (D3D12Exception e) {
            return false;
        }
    }

    /**
     * Creates a DXGI factory without the debug layer.
     *
     * @see #createFactory(boolean)
     */
    public static DxgiFactory createFactory() {
        return createFactory(false);
    }

    /**
     * Calls {@code CreateDXGIFactory2} and wraps the result in the highest {@code IDXGIFactoryN} interface the
     * runtime supports (see {@link DxgiFactory#version()}).
     *
     * <p>If {@code debug} is true the factory is created with {@code DXGI_CREATE_FACTORY_DEBUG}. On machines without
     * the graphics tools the runtime may refuse that flag; in this case the call is repeated without it. Use
     * {@link DxgiFactory#isDebug()} to find out what was actually obtained.
     *
     * @param debug whether to request the DXGI debug layer
     * @return a new factory that owns one reference
     * @throws D3D12Exception if DXGI cannot be loaded or the factory cannot be created
     */
    public static DxgiFactory createFactory(boolean debug) {
        MethodHandle create = createDxgiFactory2();

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            int hr = (int) create.invokeExact(debug ? Defines.DXGI_CREATE_FACTORY_DEBUG : 0, Iids.IDXGIFactory1, out);

            if (hr < 0 && debug) {
                hr = (int) create.invokeExact(0, Iids.IDXGIFactory1, out);
            }

            Hr.check(hr, "CreateDXGIFactory2");

            return new DxgiFactory(out.get(ADDRESS, 0));
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * The result of {@link #upgrade(MemorySegment, MemorySegment[])}.
     *
     * @param pointer the interface pointer of the highest supported interface; owns the one reference the caller
     *                handed in
     * @param level   index into the IID table of the interface {@code pointer} points to
     */
    record Upgraded(MemorySegment pointer, int level) {
    }

    /**
     * Finds the highest interface of {@code iids} an object supports. The caller's reference is transferred to the
     * returned pointer: if a higher interface was found the original pointer is released.
     *
     * @param pointer an interface pointer of the object, of the interface at index 0 of {@code iids} or higher
     * @param iids    interface IDs ordered from the base to the most derived interface; the index is the level
     * @throws D3D12Exception if {@code pointer} is a null pointer (checked before anything is dereferenced)
     */
    static Upgraded upgrade(MemorySegment pointer, MemorySegment[] iids) {
        if (pointer.address() == 0L) {
            throw new D3D12Exception("Null COM interface pointer");
        }

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);

            for (int level = iids.length - 1; level >= 1; level--) {
                int hr = (int) QUERY_INTERFACE.invokeExact(slot(pointer, 0), pointer, iids[level], out);

                if (hr >= 0) {
                    int ignored = (int) RELEASE.invokeExact(slot(pointer, 2), pointer);

                    return new Upgraded(out.get(ADDRESS, 0), level);
                }
            }

            return new Upgraded(pointer, 0);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * DXGI dereferences a {@code REFIID} / {@code REFGUID} argument without checking it, so a null one crashes the
     * process instead of returning an error.
     *
     * @return {@code iid}
     * @throws IllegalArgumentException if {@code iid} is a null pointer
     */
    static MemorySegment requireIid(MemorySegment iid) {
        if (iid.address() == 0L) {
            throw new IllegalArgumentException("iid must not be a null pointer");
        }

        return iid;
    }

    private static MemorySegment slot(MemorySegment pointer, int slot) {
        MemorySegment self = MemorySegment.ofAddress(pointer.address());
        long vtable = self.reinterpret(8).get(JAVA_LONG, 0);

        return MemorySegment.ofAddress(vtable).reinterpret(8L * (slot + 1)).get(ADDRESS, 8L * slot);
    }
}
