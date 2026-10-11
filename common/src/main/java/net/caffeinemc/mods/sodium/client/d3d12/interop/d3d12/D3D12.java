package net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12;

import net.caffeinemc.mods.sodium.client.d3d12.interop.Com;
import net.caffeinemc.mods.sodium.client.d3d12.interop.ComObject;
import net.caffeinemc.mods.sodium.client.d3d12.interop.D3D12Exception;
import net.caffeinemc.mods.sodium.client.d3d12.interop.Hr;
import net.caffeinemc.mods.sodium.client.d3d12.interop.Win32Libraries;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Enums;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Iids;
import net.caffeinemc.mods.sodium.client.d3d12.interop.compiler.D3DBlob;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Entry points exported by {@code d3d12.dll}.
 *
 * <p>The library is loaded lazily by the first call that needs it, so loading this class is safe on every platform;
 * on a non-Windows system the methods throw {@link D3D12Exception}. Native access must be enabled for the module
 * ({@code --enable-native-access}), otherwise a {@link D3D12Exception} explaining so is thrown as well.
 *
 * <p>A typical start-up and frame, with the wrappers of this package:
 *
 * <pre>{@code
 * D3D12.enableDebugLayer();                                           // optional, before the device exists
 * try (D3D12Device device = D3D12.createDevice();
 *      D3D12CommandQueue queue = device.createCommandQueue(Enums.CommandListType.DIRECT);
 *      D3D12CommandAllocator allocator = device.createCommandAllocator(Enums.CommandListType.DIRECT);
 *      D3D12GraphicsCommandList list = device.createCommandList(Enums.CommandListType.DIRECT, allocator);   // recording
 *      D3D12Fence fence = device.createFence(0)) {
 *     list.resourceBarrierTransition(texture, Enums.ResourceStates.COPY_DEST, Enums.ResourceStates.PIXEL_SHADER_RESOURCE);
 *     list.closeList();
 *     queue.executeCommandLists(list);
 *     queue.signal(fence, 1);
 *     fence.waitForValue(1, 5000);          // the GPU is done: allocator.reset(); list.reset(allocator); ...
 * }
 * }</pre>
 *
 * <p>Thread safety: all methods are free-threaded.
 */
public final class D3D12 {
    private static final Object LOCK = new Object();

    private static volatile MethodHandle createDeviceHandle;
    private static volatile MethodHandle getDebugInterfaceHandle;
    private static volatile MethodHandle serializeRootSignatureHandle;
    private static volatile MethodHandle serializeVersionedRootSignatureHandle;

    private D3D12() {
    }

    /**
     * @return true if the current platform can possibly run Direct3D 12 (a Windows system); does not load any library
     */
    public static boolean isSupportedPlatform() {
        return Win32Libraries.isWindows();
    }

    private static MethodHandle createDeviceFn() {
        MethodHandle handle = createDeviceHandle;

        if (handle == null) {
            synchronized (LOCK) {
                handle = createDeviceHandle;

                if (handle == null) {
                    // HRESULT D3D12CreateDevice(IUnknown *adapter, D3D_FEATURE_LEVEL minimumFeatureLevel, REFIID riid, void **device)
                    handle = Win32Libraries.function(Win32Libraries.d3d12(), "D3D12CreateDevice",
                            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
                    createDeviceHandle = handle;
                }
            }
        }

        return handle;
    }

    private static MethodHandle getDebugInterfaceFn() {
        MethodHandle handle = getDebugInterfaceHandle;

        if (handle == null) {
            synchronized (LOCK) {
                handle = getDebugInterfaceHandle;

                if (handle == null) {
                    // HRESULT D3D12GetDebugInterface(REFIID riid, void **debug)
                    handle = Win32Libraries.function(Win32Libraries.d3d12(), "D3D12GetDebugInterface",
                            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
                    getDebugInterfaceHandle = handle;
                }
            }
        }

        return handle;
    }

    private static MethodHandle serializeRootSignatureFn() {
        MethodHandle handle = serializeRootSignatureHandle;

        if (handle == null) {
            synchronized (LOCK) {
                handle = serializeRootSignatureHandle;

                if (handle == null) {
                    // HRESULT D3D12SerializeRootSignature(const D3D12_ROOT_SIGNATURE_DESC *desc, D3D_ROOT_SIGNATURE_VERSION version, ID3DBlob **blob, ID3DBlob **error)
                    handle = Win32Libraries.function(Win32Libraries.d3d12(), "D3D12SerializeRootSignature",
                            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
                    serializeRootSignatureHandle = handle;
                }
            }
        }

        return handle;
    }

    private static MethodHandle serializeVersionedRootSignatureFn() {
        MethodHandle handle = serializeVersionedRootSignatureHandle;

        if (handle == null) {
            synchronized (LOCK) {
                handle = serializeVersionedRootSignatureHandle;

                if (handle == null) {
                    // HRESULT D3D12SerializeVersionedRootSignature(const D3D12_VERSIONED_ROOT_SIGNATURE_DESC *desc, ID3DBlob **blob, ID3DBlob **error)
                    handle = Win32Libraries.function(Win32Libraries.d3d12(), "D3D12SerializeVersionedRootSignature",
                            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
                    serializeVersionedRootSignatureHandle = handle;
                }
            }
        }

        return handle;
    }

    /**
     * Creates a device on the default adapter with at least feature level 11.0.
     *
     * @throws D3D12Exception if no capable adapter exists or the call fails
     */
    public static D3D12Device createDevice() {
        return createDevice(null, Enums.FeatureLevel._11_0);
    }

    /**
     * Creates a device on the default adapter.
     *
     * @param minimumFeatureLevel a {@code D3D_FEATURE_LEVEL} ({@code Enums.FeatureLevel}); {@code _11_0} is the lowest
     *                            level D3D12 supports
     * @throws D3D12Exception if no adapter supports the level or the call fails
     */
    public static D3D12Device createDevice(int minimumFeatureLevel) {
        return createDevice(null, minimumFeatureLevel);
    }

    /**
     * Creates a device.
     *
     * @param adapter             a DXGI adapter wrapper, or {@code null} for the default adapter
     * @param minimumFeatureLevel a {@code D3D_FEATURE_LEVEL} ({@code Enums.FeatureLevel})
     * @return the device, owning one reference
     * @throws D3D12Exception if the adapter does not support the level or the call fails
     */
    public static D3D12Device createDevice(ComObject adapter, int minimumFeatureLevel) {
        return createDeviceForPointer(adapter == null ? MemorySegment.NULL : adapter.ptr(), minimumFeatureLevel);
    }

    /**
     * Like {@link #createDevice(ComObject, int)} with a raw {@code IUnknown*} adapter pointer.
     *
     * @param adapter the adapter, or {@link MemorySegment#NULL} / {@code null} for the default adapter
     */
    public static D3D12Device createDeviceForPointer(MemorySegment adapter, int minimumFeatureLevel) {
        MemorySegment adapterPointer = adapter == null ? MemorySegment.NULL : adapter;

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            int hr = (int) createDeviceFn().invokeExact(adapterPointer, minimumFeatureLevel, Iids.ID3D12Device, out);
            Hr.check(hr, "D3D12CreateDevice");

            return new D3D12Device(out.get(ADDRESS, 0));
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Checks whether a device could be created, without creating one (D3D12CreateDevice with a null output).
     * Use it to probe adapters.
     *
     * @param adapter             a DXGI adapter wrapper, or {@code null} for the default adapter
     * @param minimumFeatureLevel a {@code D3D_FEATURE_LEVEL} ({@code Enums.FeatureLevel})
     * @return the raw HRESULT; {@code S_FALSE} (a success code) means "a device could be created"
     */
    public static int checkDeviceSupport(ComObject adapter, int minimumFeatureLevel) {
        MemorySegment adapterPointer = adapter == null ? MemorySegment.NULL : adapter.ptr();

        try {
            return (int) createDeviceFn().invokeExact(adapterPointer, minimumFeatureLevel, Iids.ID3D12Device, MemorySegment.NULL);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Retrieves the debug layer interface.
     *
     * @throws D3D12Exception if the debug layer is not installed (the "Graphics Tools" optional Windows feature) or
     *                        the call fails
     */
    public static D3D12Debug getDebugInterface() {
        D3D12Debug debug = tryGetDebugInterface();

        if (debug == null) {
            throw new D3D12Exception("D3D12GetDebugInterface failed (the D3D12 debug layer is not installed?)");
        }

        return debug;
    }

    /**
     * Retrieves the debug layer interface.
     *
     * @return the interface, or {@code null} if the debug layer is not available on this system
     */
    public static D3D12Debug tryGetDebugInterface() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            int hr = (int) getDebugInterfaceFn().invokeExact(Iids.ID3D12Debug, out);

            if (hr < 0 || out.get(ADDRESS, 0).address() == 0L) {
                return null;
            }

            return new D3D12Debug(out.get(ADDRESS, 0));
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Enables the debug layer if it is installed. Must be called before the device is created.
     *
     * @return true if the debug layer was enabled
     */
    public static boolean enableDebugLayer() {
        try (D3D12Debug debug = tryGetDebugInterface()) {
            if (debug == null) {
                return false;
            }

            debug.enableDebugLayer();

            return true;
        }
    }

    /**
     * Serializes a version 1.0 root signature description.
     *
     * @param desc    a {@code D3D12_ROOT_SIGNATURE_DESC} ({@code abi.structs.D3D12RootSignatureDesc}) with its
     *                parameter and static sampler arrays
     * @param version a {@code D3D_ROOT_SIGNATURE_VERSION} ({@code Enums.RootSignatureVersion}), normally {@code _1_0}
     * @return the serialized root signature; pass it to {@link D3D12Device#createRootSignature(D3DBlob)}
     * @throws D3D12Exception if the description is invalid; the message includes the text of the error blob
     */
    public static D3DBlob serializeRootSignature(MemorySegment desc, int version) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment blob = arena.allocate(ADDRESS);
            MemorySegment error = arena.allocate(ADDRESS);
            int hr = (int) serializeRootSignatureFn().invokeExact(desc, version, blob, error);

            return finishSerialize(hr, blob, error, "D3D12SerializeRootSignature");
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Serializes a versioned (1.0 or 1.1) root signature description.
     *
     * @param desc a {@code D3D12_VERSIONED_ROOT_SIGNATURE_DESC} ({@code abi.structs.D3D12VersionedRootSignatureDesc})
     * @return the serialized root signature; pass it to {@link D3D12Device#createRootSignature(D3DBlob)}
     * @throws D3D12Exception if the description is invalid; the message includes the text of the error blob
     */
    public static D3DBlob serializeVersionedRootSignature(MemorySegment desc) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment blob = arena.allocate(ADDRESS);
            MemorySegment error = arena.allocate(ADDRESS);
            int hr = (int) serializeVersionedRootSignatureFn().invokeExact(desc, blob, error);

            return finishSerialize(hr, blob, error, "D3D12SerializeVersionedRootSignature");
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    private static D3DBlob finishSerialize(int hr, MemorySegment blob, MemorySegment error, String what) {
        MemorySegment errorPointer = error.get(ADDRESS, 0);
        String errorText = null;

        if (errorPointer.address() != 0L) {
            try (D3DBlob errorBlob = new D3DBlob(errorPointer)) {
                errorText = errorBlob.asString();
            }
        }

        MemorySegment blobPointer = blob.get(ADDRESS, 0);

        if (hr < 0) {
            if (blobPointer.address() != 0L) {
                new D3DBlob(blobPointer).release();
            }

            throw new D3D12Exception(errorText == null || errorText.isEmpty() ? what + " failed" : what + " failed: " + errorText, hr);
        }

        return new D3DBlob(blobPointer);
    }
}
