package net.caffeinemc.mods.sodium.client.d3d12.interop.dxgi;

import net.caffeinemc.mods.sodium.client.d3d12.interop.Com;
import net.caffeinemc.mods.sodium.client.d3d12.interop.ComObject;
import net.caffeinemc.mods.sodium.client.d3d12.interop.D3D12Exception;
import net.caffeinemc.mods.sodium.client.d3d12.interop.Hr;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Enums;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Iids;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Vtbl;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.DxgiAdapterDesc;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.DxgiAdapterDesc1;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.DxgiQueryVideoMemoryInfo;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.OptionalLong;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Wraps a DXGI adapter (a GPU, or the software rasteriser) through the highest of {@code IDXGIAdapter},
 * {@code IDXGIAdapter1}, {@code IDXGIAdapter3} and {@code IDXGIAdapter4} the object supports. Adapters are obtained
 * from {@link DxgiFactory}; pass {@link #ptr()} as the {@code pAdapter} argument of {@code D3D12CreateDevice}.
 *
 * <p>Thread safety: adapter methods are free-threaded and may be called from any thread.
 */
public final class DxgiAdapter extends ComObject {
    /**
     * Adapter interfaces ordered by version: the index is the version reported by {@link #version()}.
     */
    private static final MemorySegment[] INTERFACES = {
            Iids.IDXGIAdapter, Iids.IDXGIAdapter1, Iids.IDXGIAdapter2, Iids.IDXGIAdapter3, Iids.IDXGIAdapter4
    };

    private static final MethodHandle GET_DESC = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle CHECK_INTERFACE_SUPPORT = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle QUERY_VIDEO_MEMORY_INFO = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
    private static final MethodHandle SET_VIDEO_MEMORY_RESERVATION = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_LONG));
    private static final MethodHandle REGISTER_BUDGET_EVENT = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle UNREGISTER_BUDGET_EVENT = Com.method(FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));

    private final int level;

    private Desc desc;

    /**
     * Description of an adapter, as reported by {@code DXGI_ADAPTER_DESC1}.
     *
     * @param name                  the adapter's marketing name
     * @param vendorId              PCI vendor id (see the {@code VENDOR_*} constants)
     * @param deviceId              PCI device id
     * @param subSysId              PCI subsystem id
     * @param revision              PCI revision
     * @param dedicatedVideoMemory  bytes of GPU-local memory not shared with the CPU
     * @param dedicatedSystemMemory bytes of system memory reserved for the GPU
     * @param sharedSystemMemory    bytes of system memory the GPU can share
     * @param luid                  locally unique id; {@code LowPart} in the low 32 bits, {@code HighPart} in the high 32
     * @param flags                 {@code DXGI_ADAPTER_FLAG_*} (see {@link Enums.AdapterFlag}); always 0 when only
     *                              {@code IDXGIAdapter} is available
     */
    public record Desc(String name, int vendorId, int deviceId, int subSysId, int revision,
                       long dedicatedVideoMemory, long dedicatedSystemMemory, long sharedSystemMemory,
                       long luid, int flags) {
        public static final int VENDOR_AMD = 0x1002;
        public static final int VENDOR_NVIDIA = 0x10DE;
        public static final int VENDOR_INTEL = 0x8086;
        public static final int VENDOR_MICROSOFT = 0x1414;

        /**
         * @return true for the software rasteriser (WARP) and other CPU adapters
         */
        public boolean isSoftware() {
            return (this.flags & Enums.AdapterFlag.SOFTWARE) != 0;
        }

        public boolean isRemote() {
            return (this.flags & Enums.AdapterFlag.REMOTE) != 0;
        }

        public int luidLowPart() {
            return (int) this.luid;
        }

        public int luidHighPart() {
            return (int) (this.luid >>> 32);
        }
    }

    /**
     * Snapshot of an adapter's video memory budget, as reported by {@code DXGI_QUERY_VIDEO_MEMORY_INFO}.
     *
     * @param budget                  bytes this process may use before the OS starts demoting its allocations
     * @param currentUsage            bytes this process currently uses
     * @param availableForReservation bytes that could still be reserved with
     *                                {@link #setVideoMemoryReservation(int, int, long)}
     * @param currentReservation      bytes currently reserved
     */
    public record MemoryInfo(long budget, long currentUsage, long availableForReservation, long currentReservation) {
    }

    /**
     * @param pointer a pointer to any {@code IDXGIAdapter*} interface; ownership of one reference is transferred. The
     *                wrapper keeps the highest adapter interface the object supports.
     */
    public DxgiAdapter(MemorySegment pointer) {
        this(Dxgi.upgrade(pointer, INTERFACES));
    }

    private DxgiAdapter(Dxgi.Upgraded upgraded) {
        super(upgraded.pointer());

        this.level = upgraded.level();
    }

    /**
     * @return the version of the highest supported adapter interface: 0 for {@code IDXGIAdapter}, 1 for
     * {@code IDXGIAdapter1}, 2 for {@code IDXGIAdapter2}, 3 for {@code IDXGIAdapter3}, 4 for {@code IDXGIAdapter4}
     */
    public int version() {
        return this.level;
    }

    /**
     * @return true if {@link #queryVideoMemoryInfo} and the video memory reservation methods are available
     * ({@code IDXGIAdapter3})
     */
    public boolean supportsVideoMemoryInfo() {
        return this.level >= 3;
    }

    /**
     * Reads the adapter description. The result is an immutable snapshot that is cached after the first call (a
     * concurrent first call may read it twice, with identical results); the cached value stays available after the
     * adapter has been released, which keeps {@link #toString()} usable for logging. The first call on a released
     * adapter throws.
     *
     * @throws IllegalStateException if the adapter was released before the description was ever read
     * @throws D3D12Exception        if the call fails
     */
    public Desc desc() {
        Desc desc = this.desc;

        if (desc == null) {
            this.desc = desc = this.readDesc();
        }

        return desc;
    }

    private Desc readDesc() {
        try (Arena arena = Arena.ofConfined()) {
            if (this.level >= 1) {
                MemorySegment s = DxgiAdapterDesc1.allocate(arena);
                int hr = (int) GET_DESC.invokeExact(this.fn(Vtbl.IDXGIAdapter1.GET_DESC1), this.ptr(), s);
                Hr.check(hr, "IDXGIAdapter1::GetDesc1");

                return new Desc(DxgiAdapterDesc1.getDescriptionString(s), DxgiAdapterDesc1.getVendorId(s),
                        DxgiAdapterDesc1.getDeviceId(s), DxgiAdapterDesc1.getSubSysId(s), DxgiAdapterDesc1.getRevision(s),
                        DxgiAdapterDesc1.getDedicatedVideoMemory(s), DxgiAdapterDesc1.getDedicatedSystemMemory(s),
                        DxgiAdapterDesc1.getSharedSystemMemory(s), DxgiAdapterDesc1.sliceAdapterLuid(s).get(JAVA_LONG, 0),
                        DxgiAdapterDesc1.getFlags(s));
            }

            MemorySegment s = DxgiAdapterDesc.allocate(arena);
            int hr = (int) GET_DESC.invokeExact(this.fn(Vtbl.IDXGIAdapter.GET_DESC), this.ptr(), s);
            Hr.check(hr, "IDXGIAdapter::GetDesc");

            return new Desc(DxgiAdapterDesc.getDescriptionString(s), DxgiAdapterDesc.getVendorId(s),
                    DxgiAdapterDesc.getDeviceId(s), DxgiAdapterDesc.getSubSysId(s), DxgiAdapterDesc.getRevision(s),
                    DxgiAdapterDesc.getDedicatedVideoMemory(s), DxgiAdapterDesc.getDedicatedSystemMemory(s),
                    DxgiAdapterDesc.getSharedSystemMemory(s), DxgiAdapterDesc.sliceAdapterLuid(s).get(JAVA_LONG, 0), 0);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Asks whether the adapter's user mode driver supports an interface (for example the IID of
     * {@code ID3D11Device}) and which driver version that is. Modern WDDM drivers report
     * {@code DXGI_ERROR_UNSUPPORTED} for interfaces they do not implement, including the D3D12 ones.
     *
     * @param iid the interface to ask about (a 16 byte GUID in native memory, see {@code Iids})
     * @return the 64-bit user mode driver version (a packed {@code LARGE_INTEGER}), or empty if the interface is not
     * supported
     * @throws IllegalArgumentException if {@code iid} is a null pointer
     * @throws D3D12Exception           on any failure other than {@code DXGI_ERROR_UNSUPPORTED}
     */
    public OptionalLong checkInterfaceSupport(MemorySegment iid) {
        Dxgi.requireIid(iid);

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment version = arena.allocate(JAVA_LONG);
            int hr = (int) CHECK_INTERFACE_SUPPORT.invokeExact(this.fn(Vtbl.IDXGIAdapter.CHECK_INTERFACE_SUPPORT), this.ptr(), iid, version);

            if (hr == Hr.DXGI_ERROR_UNSUPPORTED) {
                return OptionalLong.empty();
            }

            Hr.check(hr, "IDXGIAdapter::CheckInterfaceSupport");

            return OptionalLong.of(version.get(JAVA_LONG, 0));
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Queries the video memory budget of this adapter without allocating. Requires {@code IDXGIAdapter3}.
     *
     * @param nodeIndex    the adapter node (0 on single-GPU adapters)
     * @param segmentGroup {@link Enums.MemorySegmentGroup#LOCAL} for GPU-local memory or
     *                     {@link Enums.MemorySegmentGroup#NON_LOCAL} for system memory the GPU can reach
     * @param out          native memory that receives a {@code DXGI_QUERY_VIDEO_MEMORY_INFO}; its
     *                     {@link MemorySegment#byteSize()} has to be at least {@link DxgiQueryVideoMemoryInfo#SIZE}
     *                     (a segment made with {@code MemorySegment.ofAddress} has size 0 and must be
     *                     {@code reinterpret}ed first)
     * @throws IllegalArgumentException if {@code out} is smaller than {@link DxgiQueryVideoMemoryInfo#SIZE}, so that
     *                                  the native call can never write outside the caller's memory
     * @throws D3D12Exception           if the call fails or the adapter does not support
     *                                  {@link #supportsVideoMemoryInfo()}
     */
    public void queryVideoMemoryInfo(int nodeIndex, int segmentGroup, MemorySegment out) {
        this.requireMemoryInfo("QueryVideoMemoryInfo");

        if (out.byteSize() < DxgiQueryVideoMemoryInfo.SIZE) {
            throw new IllegalArgumentException("out has " + out.byteSize() + " bytes, DXGI_QUERY_VIDEO_MEMORY_INFO needs "
                    + DxgiQueryVideoMemoryInfo.SIZE);
        }

        try {
            int hr = (int) QUERY_VIDEO_MEMORY_INFO.invokeExact(this.fn(Vtbl.IDXGIAdapter3.QUERY_VIDEO_MEMORY_INFO), this.ptr(),
                    nodeIndex, segmentGroup, out);
            Hr.check(hr, "IDXGIAdapter3::QueryVideoMemoryInfo");
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Convenience form of {@link #queryVideoMemoryInfo(int, int, MemorySegment)} that allocates.
     */
    public MemoryInfo queryVideoMemoryInfo(int nodeIndex, int segmentGroup) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment s = DxgiQueryVideoMemoryInfo.allocate(arena);
            this.queryVideoMemoryInfo(nodeIndex, segmentGroup, s);

            return new MemoryInfo(DxgiQueryVideoMemoryInfo.getBudget(s), DxgiQueryVideoMemoryInfo.getCurrentUsage(s),
                    DxgiQueryVideoMemoryInfo.getAvailableForReservation(s), DxgiQueryVideoMemoryInfo.getCurrentReservation(s));
        }
    }

    /**
     * Tells the OS how much video memory this process wants to keep resident. Requires {@code IDXGIAdapter3}.
     *
     * @param nodeIndex    the adapter node (0 on single-GPU adapters)
     * @param segmentGroup {@link Enums.MemorySegmentGroup#LOCAL} or {@link Enums.MemorySegmentGroup#NON_LOCAL}
     * @param bytes        the reservation; must not exceed {@link MemoryInfo#availableForReservation()}
     * @throws D3D12Exception if the call fails (typically {@code E_INVALIDARG} for a reservation that is too large)
     */
    public void setVideoMemoryReservation(int nodeIndex, int segmentGroup, long bytes) {
        this.requireMemoryInfo("SetVideoMemoryReservation");

        try {
            int hr = (int) SET_VIDEO_MEMORY_RESERVATION.invokeExact(this.fn(Vtbl.IDXGIAdapter3.SET_VIDEO_MEMORY_RESERVATION), this.ptr(),
                    nodeIndex, segmentGroup, bytes);
            Hr.check(hr, "IDXGIAdapter3::SetVideoMemoryReservation");
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Registers an event that is signalled when the video memory budget changes. Requires {@code IDXGIAdapter3}.
     *
     * @param event a Win32 event handle (see {@code Kernel32.createEvent}); stays owned by the caller, who has to keep
     *              it open until the notification has been unregistered
     * @return the cookie to pass to {@link #unregisterVideoMemoryBudgetChangeNotification(int)}
     * @throws D3D12Exception if the call fails
     */
    public int registerVideoMemoryBudgetChangeNotificationEvent(MemorySegment event) {
        this.requireMemoryInfo("RegisterVideoMemoryBudgetChangeNotificationEvent");

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cookie = arena.allocate(JAVA_INT);
            int hr = (int) REGISTER_BUDGET_EVENT.invokeExact(this.fn(Vtbl.IDXGIAdapter3.REGISTER_VIDEO_MEMORY_BUDGET_CHANGE_NOTIFICATION_EVENT),
                    this.ptr(), event, cookie);
            Hr.check(hr, "IDXGIAdapter3::RegisterVideoMemoryBudgetChangeNotificationEvent");

            return cookie.get(JAVA_INT, 0);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Stops the notifications requested with {@link #registerVideoMemoryBudgetChangeNotificationEvent}. Requires
     * {@code IDXGIAdapter3}. The event handle itself stays owned by the caller. The native method returns nothing, so
     * an unknown cookie is silently ignored by DXGI.
     *
     * @param cookie the value returned by {@link #registerVideoMemoryBudgetChangeNotificationEvent}
     * @throws D3D12Exception if the adapter does not support {@link #supportsVideoMemoryInfo()}
     */
    public void unregisterVideoMemoryBudgetChangeNotification(int cookie) {
        this.requireMemoryInfo("UnregisterVideoMemoryBudgetChangeNotification");

        try {
            UNREGISTER_BUDGET_EVENT.invokeExact(this.fn(Vtbl.IDXGIAdapter3.UNREGISTER_VIDEO_MEMORY_BUDGET_CHANGE_NOTIFICATION), this.ptr(), cookie);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    private void requireMemoryInfo(String method) {
        if (this.level < 3) {
            throw new D3D12Exception("IDXGIAdapter3::" + method + " is not available (adapter only supports IDXGIAdapter"
                    + (this.level == 0 ? "" : this.level) + ")", Hr.E_NOINTERFACE);
        }
    }

    @Override
    public String toString() {
        if (this.isReleased()) {
            return "DxgiAdapter[released]";
        }

        try {
            return "DxgiAdapter[" + this.desc().name() + "]";
        } catch (RuntimeException e) {
            return "DxgiAdapter[0x" + Long.toHexString(this.address()) + "]";
        }
    }
}
