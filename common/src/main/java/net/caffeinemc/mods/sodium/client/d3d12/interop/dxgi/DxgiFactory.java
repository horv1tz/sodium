package net.caffeinemc.mods.sodium.client.d3d12.interop.dxgi;

import net.caffeinemc.mods.sodium.client.d3d12.interop.Com;
import net.caffeinemc.mods.sodium.client.d3d12.interop.ComObject;
import net.caffeinemc.mods.sodium.client.d3d12.interop.D3D12Exception;
import net.caffeinemc.mods.sodium.client.d3d12.interop.Hr;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Defines;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Enums;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Iids;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Vtbl;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.DxgiSwapChainDesc1;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Wraps a DXGI factory through the highest of {@code IDXGIFactory} to {@code IDXGIFactory7} the runtime supports.
 * Create one with {@link Dxgi#createFactory(boolean)}.
 *
 * <p>Methods that need a newer interface than the factory has throw a {@link D3D12Exception} with
 * {@code E_NOINTERFACE}; the {@code supports*} methods tell in advance. On any Windows 10 system every interface up to
 * {@code IDXGIFactory4} exists, so only {@link #supportsFeatureCheck()} (1.5) and {@link #supportsGpuPreference()}
 * (1.6) can reasonably vary.
 *
 * <p>Thread safety: the factory is free-threaded, but {@link #createSwapChainForHwnd} and
 * {@link #makeWindowAssociation} interact with the window's message loop and should be called from the thread that
 * owns the window.
 */
public final class DxgiFactory extends ComObject {
    /**
     * Factory interfaces ordered by version: the index is the version reported by {@link #version()}.
     */
    private static final MemorySegment[] INTERFACES = {
            Iids.IDXGIFactory, Iids.IDXGIFactory1, Iids.IDXGIFactory2, Iids.IDXGIFactory3,
            Iids.IDXGIFactory4, Iids.IDXGIFactory5, Iids.IDXGIFactory6, Iids.IDXGIFactory7
    };

    private static final MethodHandle ENUM_ADAPTERS = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
    private static final MethodHandle ENUM_ADAPTER_BY_GPU_PREFERENCE = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle ENUM_ADAPTER_BY_LUID = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS));
    private static final MethodHandle ENUM_WARP_ADAPTER = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle MAKE_WINDOW_ASSOCIATION = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle GET_WINDOW_ASSOCIATION = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle CREATE_SWAP_CHAIN_FOR_HWND = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle CHECK_FEATURE_SUPPORT = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle IS_CURRENT = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle GET_CREATION_FLAGS = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS));

    private final int level;

    /**
     * @param pointer a pointer to any {@code IDXGIFactory*} interface; ownership of one reference is transferred. The
     *                wrapper keeps the highest factory interface the object supports.
     */
    public DxgiFactory(MemorySegment pointer) {
        this(Dxgi.upgrade(pointer, INTERFACES));
    }

    private DxgiFactory(Dxgi.Upgraded upgraded) {
        super(upgraded.pointer());

        this.level = upgraded.level();
    }

    /**
     * @return the version of the highest supported factory interface: 0 for {@code IDXGIFactory}, 1 to 7 for
     * {@code IDXGIFactory1} to {@code IDXGIFactory7}
     */
    public int version() {
        return this.level;
    }

    /**
     * @return true if {@link #createSwapChainForHwnd} is available ({@code IDXGIFactory2})
     */
    public boolean supportsSwapChainForHwnd() {
        return this.level >= 2;
    }

    /**
     * @return true if {@link #enumWarpAdapter()} and {@link #enumAdapterByLuid(long)} are available
     * ({@code IDXGIFactory4})
     */
    public boolean supportsWarpAndLuid() {
        return this.level >= 4;
    }

    /**
     * @return true if {@link #isTearingSupported()} can query the runtime ({@code IDXGIFactory5})
     */
    public boolean supportsFeatureCheck() {
        return this.level >= 5;
    }

    /**
     * @return true if adapters are enumerated by GPU preference ({@code IDXGIFactory6}); otherwise they come in
     * DXGI's default order
     */
    public boolean supportsGpuPreference() {
        return this.level >= 6;
    }

    /**
     * @return true if the factory was created with the DXGI debug layer ({@code DXGI_CREATE_FACTORY_DEBUG}); always
     * false when the runtime does not report it (before {@code IDXGIFactory3})
     */
    public boolean isDebug() {
        if (this.level < 3) {
            return false;
        }

        try {
            int flags = (int) GET_CREATION_FLAGS.invokeExact(this.fn(Vtbl.IDXGIFactory3.GET_CREATION_FLAGS), this.ptr());

            return (flags & Defines.DXGI_CREATE_FACTORY_DEBUG) != 0;
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * @return false once the set of adapters has changed (a GPU was added, removed or driver-updated) or the display
     * configuration changed, which means this factory is stale and adapters should be enumerated from a new one. True
     * if the factory is still current or does not support the query ({@code IDXGIFactory1} is required).
     */
    public boolean isCurrent() {
        if (this.level < 1) {
            return true;
        }

        try {
            return (int) IS_CURRENT.invokeExact(this.fn(Vtbl.IDXGIFactory1.IS_CURRENT), this.ptr()) != 0;
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Enumerates the adapters in the order of the given GPU preference, or in DXGI's default order if the factory is
     * older than {@code IDXGIFactory6}.
     *
     * @param gpuPreference {@link Enums.GpuPreference#UNSPECIFIED}, {@link Enums.GpuPreference#MINIMUM_POWER} or
     *                      {@link Enums.GpuPreference#HIGH_PERFORMANCE}; ignored before {@code IDXGIFactory6}
     * @return all adapters; each element owns one reference and has to be closed by the caller
     * @throws D3D12Exception if enumeration fails
     */
    public List<DxgiAdapter> enumAdapters(int gpuPreference) {
        List<DxgiAdapter> adapters = new ArrayList<>();

        try {
            for (int index = 0; ; index++) {
                DxgiAdapter adapter = this.enumAdapter(index, gpuPreference);

                if (adapter == null) {
                    return adapters;
                }

                adapters.add(adapter);
            }
        } catch (RuntimeException e) {
            for (DxgiAdapter adapter : adapters) {
                adapter.release();
            }

            throw e;
        }
    }

    /**
     * Enumerates the adapters with {@link Enums.GpuPreference#HIGH_PERFORMANCE} first when supported.
     *
     * @see #enumAdapters(int)
     */
    public List<DxgiAdapter> enumAdapters() {
        return this.enumAdapters(Enums.GpuPreference.HIGH_PERFORMANCE);
    }

    /**
     * Gets one adapter. Uses {@code EnumAdapterByGpuPreference} on {@code IDXGIFactory6}, {@code EnumAdapters1}
     * on {@code IDXGIFactory1} and newer and {@code EnumAdapters} otherwise.
     *
     * @param index         zero-based adapter index
     * @param gpuPreference see {@link #enumAdapters(int)}
     * @return the adapter (owning one reference), or null if {@code index} is past the last adapter
     * @throws D3D12Exception on any failure other than {@code DXGI_ERROR_NOT_FOUND}
     */
    public DxgiAdapter enumAdapter(int index, int gpuPreference) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            int hr;

            if (this.level >= 6) {
                hr = (int) ENUM_ADAPTER_BY_GPU_PREFERENCE.invokeExact(
                        this.fn(Vtbl.IDXGIFactory6.ENUM_ADAPTER_BY_GPU_PREFERENCE), this.ptr(), index, gpuPreference,
                        Iids.IDXGIAdapter1, out);
            } else if (this.level >= 1) {
                hr = (int) ENUM_ADAPTERS.invokeExact(this.fn(Vtbl.IDXGIFactory1.ENUM_ADAPTERS1), this.ptr(), index, out);
            } else {
                hr = (int) ENUM_ADAPTERS.invokeExact(this.fn(Vtbl.IDXGIFactory.ENUM_ADAPTERS), this.ptr(), index, out);
            }

            if (hr == Hr.DXGI_ERROR_NOT_FOUND) {
                return null;
            }

            Hr.check(hr, "IDXGIFactory::EnumAdapters");

            return new DxgiAdapter(out.get(ADDRESS, 0));
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Gets the Microsoft Basic Render Driver (WARP), the software rasteriser. Requires {@code IDXGIFactory4}.
     *
     * @return the adapter, owning one reference
     * @throws D3D12Exception if the call fails
     */
    public DxgiAdapter enumWarpAdapter() {
        this.require(4, "EnumWarpAdapter");

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            int hr = (int) ENUM_WARP_ADAPTER.invokeExact(this.fn(Vtbl.IDXGIFactory4.ENUM_WARP_ADAPTER), this.ptr(), Iids.IDXGIAdapter1, out);
            Hr.check(hr, "IDXGIFactory4::EnumWarpAdapter");

            return new DxgiAdapter(out.get(ADDRESS, 0));
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Gets the adapter with the given locally unique id, for example the one an existing device was created on.
     * Requires {@code IDXGIFactory4}.
     *
     * @param luid {@code LowPart} in the low 32 bits and {@code HighPart} in the high 32 bits, as in
     *             {@link DxgiAdapter.Desc#luid()}
     * @return the adapter, or null if no adapter has this LUID
     * @throws D3D12Exception on any failure other than {@code DXGI_ERROR_NOT_FOUND}
     */
    public DxgiAdapter enumAdapterByLuid(long luid) {
        this.require(4, "EnumAdapterByLuid");

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            // LUID is an 8-byte struct, passed by value in a general purpose register: ABI-identical to a long.
            int hr = (int) ENUM_ADAPTER_BY_LUID.invokeExact(this.fn(Vtbl.IDXGIFactory4.ENUM_ADAPTER_BY_LUID), this.ptr(), luid, Iids.IDXGIAdapter1, out);

            if (hr == Hr.DXGI_ERROR_NOT_FOUND) {
                return null;
            }

            Hr.check(hr, "IDXGIFactory4::EnumAdapterByLuid");

            return new DxgiAdapter(out.get(ADDRESS, 0));
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Creates a swap chain that presents to a window. Requires {@code IDXGIFactory2}. The swap chain is windowed;
     * use {@link DxgiSwapChain#setFullscreenState} to enter exclusive full screen.
     *
     * @param queue the direct command queue the swap chain presents on (an {@code ID3D12CommandQueue} wrapper). The
     *              swap chain keeps its own reference; the wrapper stays owned by the caller.
     * @param hwnd  the window handle
     * @param desc  the swap chain parameters; D3D12 needs a flip swap effect ({@link Enums.SwapEffect#FLIP_DISCARD} or
     *              {@link Enums.SwapEffect#FLIP_SEQUENTIAL}) and at least two buffers
     * @return the swap chain, owning one reference
     * @throws D3D12Exception if the call fails
     */
    public DxgiSwapChain createSwapChainForHwnd(ComObject queue, MemorySegment hwnd, DxgiSwapChain.Desc desc) {
        this.require(2, "CreateSwapChainForHwnd");

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment nativeDesc = DxgiSwapChainDesc1.allocate(arena);
            desc.writeTo(nativeDesc);

            MemorySegment out = arena.allocate(ADDRESS);
            int hr = (int) CREATE_SWAP_CHAIN_FOR_HWND.invokeExact(this.fn(Vtbl.IDXGIFactory2.CREATE_SWAP_CHAIN_FOR_HWND), this.ptr(),
                    queue.ptr(), hwnd, nativeDesc, MemorySegment.NULL, MemorySegment.NULL, out);
            Hr.check(hr, "IDXGIFactory2::CreateSwapChainForHwnd");

            return new DxgiSwapChain(out.get(ADDRESS, 0));
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Controls how DXGI reacts to Alt+Enter, Print Screen and window changes for a window.
     *
     * @param hwnd  the window handle
     * @param flags a combination of {@code Defines.DXGI_MWA_*}; use {@link Defines#DXGI_MWA_NO_ALT_ENTER} to take over
     *              full-screen switching
     * @throws D3D12Exception if the call fails
     */
    public void makeWindowAssociation(MemorySegment hwnd, int flags) {
        try {
            int hr = (int) MAKE_WINDOW_ASSOCIATION.invokeExact(this.fn(Vtbl.IDXGIFactory.MAKE_WINDOW_ASSOCIATION), this.ptr(), hwnd, flags);
            Hr.check(hr, "IDXGIFactory::MakeWindowAssociation");
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * @return the window handle set by {@link #makeWindowAssociation}, or a null segment if none was set
     * @throws D3D12Exception if the call fails
     */
    public MemorySegment getWindowAssociation() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            int hr = (int) GET_WINDOW_ASSOCIATION.invokeExact(this.fn(Vtbl.IDXGIFactory.GET_WINDOW_ASSOCIATION), this.ptr(), out);
            Hr.check(hr, "IDXGIFactory::GetWindowAssociation");

            return out.get(ADDRESS, 0);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Asks whether the system supports presenting with tearing ({@link DxgiSwapChain#PRESENT_ALLOW_TEARING}, needed for
     * variable refresh rate displays).
     *
     * @return true if tearing is supported; false if it is not, if the runtime reports the feature query itself as
     * unsupported ({@code DXGI_ERROR_UNSUPPORTED}, {@code E_NOTIMPL}), or if the factory is older than
     * {@code IDXGIFactory5}
     * @throws D3D12Exception if the query fails for any other reason
     */
    public boolean isTearingSupported() {
        if (this.level < 5) {
            return false;
        }

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(JAVA_INT);
            int hr = (int) CHECK_FEATURE_SUPPORT.invokeExact(this.fn(Vtbl.IDXGIFactory5.CHECK_FEATURE_SUPPORT), this.ptr(),
                    Enums.DxgiFeature.DXGI_FEATURE_PRESENT_ALLOW_TEARING, out, (int) JAVA_INT.byteSize());

            if (hr == Hr.DXGI_ERROR_UNSUPPORTED || hr == Hr.E_NOTIMPL) {
                return false;
            }

            Hr.check(hr, "IDXGIFactory5::CheckFeatureSupport");

            return out.get(JAVA_INT, 0) != 0;
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    private void require(int minimumLevel, String method) {
        if (this.level < minimumLevel) {
            throw new D3D12Exception("IDXGIFactory" + minimumLevel + "::" + method + " is not available (factory only supports IDXGIFactory"
                    + (this.level == 0 ? "" : this.level) + ")", Hr.E_NOINTERFACE);
        }
    }
}
