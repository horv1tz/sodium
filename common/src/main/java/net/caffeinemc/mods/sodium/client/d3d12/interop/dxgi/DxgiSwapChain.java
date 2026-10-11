package net.caffeinemc.mods.sodium.client.d3d12.interop.dxgi;

import net.caffeinemc.mods.sodium.client.d3d12.interop.Com;
import net.caffeinemc.mods.sodium.client.d3d12.interop.ComObject;
import net.caffeinemc.mods.sodium.client.d3d12.interop.D3D12Exception;
import net.caffeinemc.mods.sodium.client.d3d12.interop.Hr;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Defines;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Enums;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Iids;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Vtbl;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.DxgiPresentParameters;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.DxgiSampleDesc;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.DxgiSwapChainDesc1;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Wraps a DXGI swap chain through the highest of {@code IDXGISwapChain1} to {@code IDXGISwapChain4} the object
 * supports. Swap chains are created by {@link DxgiFactory#createSwapChainForHwnd}.
 *
 * <h2>Failure policy</h2>
 * {@link #present}, {@link #present1}, {@link #resizeBuffers} and {@link #setFullscreenState} return the raw
 * {@code HRESULT}, because the caller has to react to specific values: {@link Hr#DXGI_STATUS_OCCLUDED},
 * {@link Hr#DXGI_ERROR_WAS_STILL_DRAWING} and {@link Hr#DXGI_ERROR_INVALID_CALL} are routine, and the device-loss
 * codes ({@link Hr#isDeviceLoss(int)}) mean the whole device has to be recreated. Everything else throws a
 * {@link D3D12Exception}.
 *
 * <h2>Lifetime</h2>
 * <ul>
 *     <li>Every back buffer obtained from {@link #getBuffer} holds a reference to the swap chain's buffers. All of
 *     them have to be released, and the GPU must be done with them, before {@link #resizeBuffers} is called or the
 *     swap chain is released.</li>
 *     <li>Leave full screen ({@code setFullscreenState(false, ...)}) before releasing the swap chain.</li>
 *     <li>Release the swap chain before the command queue it was created with.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * Not thread-safe. Call all methods from one thread (the render thread) at a time; the swap chain must be created
 * from the thread that owns the window's message loop, and {@code Present}/{@code ResizeBuffers} must not run while
 * another thread is inside a window message handler for the same window that touches the swap chain.
 */
public final class DxgiSwapChain extends ComObject {
    /** {@code DXGI_PRESENT_TEST}: validate the call and the swap chain state without presenting. */
    public static final int PRESENT_TEST = 0x00000001;
    /** {@code DXGI_PRESENT_DO_NOT_SEQUENCE}. */
    public static final int PRESENT_DO_NOT_SEQUENCE = 0x00000002;
    /** {@code DXGI_PRESENT_RESTART}. */
    public static final int PRESENT_RESTART = 0x00000004;
    /** {@code DXGI_PRESENT_DO_NOT_WAIT}: return {@link Hr#DXGI_ERROR_WAS_STILL_DRAWING} instead of blocking. */
    public static final int PRESENT_DO_NOT_WAIT = 0x00000008;
    /** {@code DXGI_PRESENT_STEREO_PREFER_RIGHT}. */
    public static final int PRESENT_STEREO_PREFER_RIGHT = 0x00000010;
    /** {@code DXGI_PRESENT_STEREO_TEMPORARY_MONO}. */
    public static final int PRESENT_STEREO_TEMPORARY_MONO = 0x00000020;
    /** {@code DXGI_PRESENT_RESTRICT_TO_OUTPUT}. */
    public static final int PRESENT_RESTRICT_TO_OUTPUT = 0x00000040;
    /** {@code DXGI_PRESENT_USE_DURATION}. */
    public static final int PRESENT_USE_DURATION = 0x00000100;
    /**
     * {@code DXGI_PRESENT_ALLOW_TEARING}: present without waiting for vertical blank. Only valid with sync interval 0
     * on a swap chain created with {@link Enums.SwapChainFlag#ALLOW_TEARING}.
     */
    public static final int PRESENT_ALLOW_TEARING = 0x00000200;

    /**
     * Swap chain interfaces ordered by version: the index is the version reported by {@link #version()}.
     */
    private static final MemorySegment[] INTERFACES = {
            Iids.IDXGISwapChain, Iids.IDXGISwapChain1, Iids.IDXGISwapChain2, Iids.IDXGISwapChain3, Iids.IDXGISwapChain4
    };

    /**
     * A zeroed {@code DXGI_PRESENT_PARAMETERS}, passed to {@code Present1} when the caller does not supply one.
     */
    private static final MemorySegment NO_PRESENT_PARAMETERS = DxgiPresentParameters.allocate(Arena.global());

    private static final MethodHandle PRESENT = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle PRESENT1 = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
    private static final MethodHandle GET_BUFFER = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle RESIZE_BUFFERS = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle SET_FULLSCREEN_STATE = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
    private static final MethodHandle GET_FULLSCREEN_STATE = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle GET_DESC1 = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle GET_HWND = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle SET_MAXIMUM_FRAME_LATENCY = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle GET_MAXIMUM_FRAME_LATENCY = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle GET_FRAME_LATENCY_WAITABLE_OBJECT = Com.method(FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle GET_CURRENT_BACK_BUFFER_INDEX = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle CHECK_COLOR_SPACE_SUPPORT = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
    private static final MethodHandle SET_COLOR_SPACE1 = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle SET_HDR_META_DATA = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));

    private final int level;

    /**
     * Swap chain parameters, matching {@code DXGI_SWAP_CHAIN_DESC1}. Used to create a swap chain and returned by
     * {@link DxgiSwapChain#getDesc1()}.
     *
     * @param width        back buffer width in pixels (0 = take it from the window)
     * @param height       back buffer height in pixels (0 = take it from the window)
     * @param format       {@code DXGI_FORMAT} of the back buffers (see {@link Enums.Format})
     * @param stereo       whether to create a stereo swap chain
     * @param sampleCount  multisample count; must be 1 for flip-model swap chains
     * @param sampleQuality multisample quality; must be 0 for flip-model swap chains
     * @param bufferUsage  {@code DXGI_USAGE_*} flags (see {@link Defines#DXGI_USAGE_RENDER_TARGET_OUTPUT})
     * @param bufferCount  number of back buffers (2 to 16 for flip model)
     * @param scaling      {@code DXGI_SCALING_*} (see {@link Enums.Scaling})
     * @param swapEffect   {@code DXGI_SWAP_EFFECT_*} (see {@link Enums.SwapEffect}); D3D12 requires a flip model
     * @param alphaMode    {@code DXGI_ALPHA_MODE_*} (see {@link Enums.AlphaMode})
     * @param flags        {@code DXGI_SWAP_CHAIN_FLAG_*} (see {@link Enums.SwapChainFlag})
     */
    public record Desc(int width, int height, int format, boolean stereo, int sampleCount, int sampleQuality,
                       int bufferUsage, int bufferCount, int scaling, int swapEffect, int alphaMode, int flags) {
        /**
         * A typical D3D12 swap chain: render target output, stretch scaling, {@code DXGI_SWAP_EFFECT_FLIP_DISCARD},
         * no multisampling, no stereo.
         */
        public static Desc flipDiscard(int width, int height, int format, int bufferCount, int flags) {
            return new Desc(width, height, format, false, 1, 0, Defines.DXGI_USAGE_RENDER_TARGET_OUTPUT, bufferCount,
                    Enums.Scaling.STRETCH, Enums.SwapEffect.FLIP_DISCARD, Enums.AlphaMode.UNSPECIFIED, flags);
        }

        /**
         * Writes this description into a {@code DXGI_SWAP_CHAIN_DESC1}.
         *
         * @param out at least {@link DxgiSwapChainDesc1#SIZE} bytes
         */
        public void writeTo(MemorySegment out) {
            DxgiSwapChainDesc1.setWidth(out, this.width);
            DxgiSwapChainDesc1.setHeight(out, this.height);
            DxgiSwapChainDesc1.setFormat(out, this.format);
            DxgiSwapChainDesc1.setStereo(out, this.stereo);

            MemorySegment sample = DxgiSwapChainDesc1.sliceSampleDesc(out);
            DxgiSampleDesc.setCount(sample, this.sampleCount);
            DxgiSampleDesc.setQuality(sample, this.sampleQuality);

            DxgiSwapChainDesc1.setBufferUsage(out, this.bufferUsage);
            DxgiSwapChainDesc1.setBufferCount(out, this.bufferCount);
            DxgiSwapChainDesc1.setScaling(out, this.scaling);
            DxgiSwapChainDesc1.setSwapEffect(out, this.swapEffect);
            DxgiSwapChainDesc1.setAlphaMode(out, this.alphaMode);
            DxgiSwapChainDesc1.setFlags(out, this.flags);
        }

        /**
         * Reads a {@code DXGI_SWAP_CHAIN_DESC1}.
         */
        public static Desc readFrom(MemorySegment in) {
            MemorySegment sample = DxgiSwapChainDesc1.sliceSampleDesc(in);

            return new Desc(DxgiSwapChainDesc1.getWidth(in), DxgiSwapChainDesc1.getHeight(in),
                    DxgiSwapChainDesc1.getFormat(in), DxgiSwapChainDesc1.getStereo(in),
                    DxgiSampleDesc.getCount(sample), DxgiSampleDesc.getQuality(sample),
                    DxgiSwapChainDesc1.getBufferUsage(in), DxgiSwapChainDesc1.getBufferCount(in),
                    DxgiSwapChainDesc1.getScaling(in), DxgiSwapChainDesc1.getSwapEffect(in),
                    DxgiSwapChainDesc1.getAlphaMode(in), DxgiSwapChainDesc1.getFlags(in));
        }
    }

    /**
     * @param pointer a pointer to any {@code IDXGISwapChain*} interface; ownership of one reference is transferred.
     *                The wrapper keeps the highest swap chain interface the object supports.
     */
    public DxgiSwapChain(MemorySegment pointer) {
        this(Dxgi.upgrade(pointer, INTERFACES));
    }

    private DxgiSwapChain(Dxgi.Upgraded upgraded) {
        super(upgraded.pointer());

        this.level = upgraded.level();
    }

    /**
     * @return the version of the highest supported swap chain interface: 0 for {@code IDXGISwapChain}, 1 to 4 for
     * {@code IDXGISwapChain1} to {@code IDXGISwapChain4}
     */
    public int version() {
        return this.level;
    }

    /**
     * @return true if {@link #setMaximumFrameLatency}, {@link #getMaximumFrameLatency} and
     * {@link #getFrameLatencyWaitableObject} are available ({@code IDXGISwapChain2})
     */
    public boolean supportsFrameLatency() {
        return this.level >= 2;
    }

    /**
     * @return true if {@link #getCurrentBackBufferIndex}, {@link #setColorSpace1} and
     * {@link #checkColorSpaceSupport} are available ({@code IDXGISwapChain3})
     */
    public boolean supportsBackBufferIndex() {
        return this.level >= 3;
    }

    /**
     * Presents the current back buffer. Does not allocate.
     *
     * @param syncInterval 0 = present immediately, 1 to 4 = wait for that many vertical blanks
     * @param flags        {@code PRESENT_*} flags
     * @return the raw HRESULT: {@code S_OK}, a status such as {@link Hr#DXGI_STATUS_OCCLUDED}, or an error such as
     * {@link Hr#DXGI_ERROR_WAS_STILL_DRAWING} or a device-loss code
     */
    public int present(int syncInterval, int flags) {
        try {
            return (int) PRESENT.invokeExact(this.fn(Vtbl.IDXGISwapChain.PRESENT), this.ptr(), syncInterval, flags);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Presents with {@code IDXGISwapChain1::Present1}. Does not allocate. Requires {@code IDXGISwapChain1}.
     *
     * @param syncInterval      see {@link #present(int, int)}
     * @param flags             {@code PRESENT_*} flags
     * @param presentParameters a {@code DXGI_PRESENT_PARAMETERS} (dirty rectangles, scroll), or
     *                          {@link MemorySegment#NULL} to present the whole frame
     * @return the raw HRESULT, as for {@link #present(int, int)}
     */
    public int present1(int syncInterval, int flags, MemorySegment presentParameters) {
        this.require(1, "Present1");

        if (presentParameters.address() == 0L) {
            presentParameters = NO_PRESENT_PARAMETERS;
        }

        try {
            return (int) PRESENT1.invokeExact(this.fn(Vtbl.IDXGISwapChain1.PRESENT1), this.ptr(), syncInterval, flags, presentParameters);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Gets a back buffer. The returned interface pointer carries a new reference that <b>the caller owns</b>; wrap it
     * (for example {@code new D3D12Resource(swapChain.getBuffer(i, Iids.ID3D12Resource))}) and release it before
     * resizing the buffers.
     *
     * @param bufferIndex index of the buffer; with flip-model swap chains only buffer 0 can be fetched on Windows 7,
     *                    all buffers on newer systems
     * @param iid         the interface to request, for example {@code Iids.ID3D12Resource}
     * @return the raw interface pointer
     * @throws D3D12Exception if the call fails
     */
    public MemorySegment getBuffer(int bufferIndex, MemorySegment iid) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            int hr = (int) GET_BUFFER.invokeExact(this.fn(Vtbl.IDXGISwapChain.GET_BUFFER), this.ptr(), bufferIndex, iid, out);
            Hr.check(hr, "IDXGISwapChain::GetBuffer");

            return out.get(ADDRESS, 0);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Changes the size, format or buffer count of the swap chain. All references to the back buffers must have been
     * released first, and the GPU must be done with them.
     *
     * @param bufferCount new buffer count, or 0 to keep the current one
     * @param width       new width, or 0 to use the client area of the window
     * @param height      new height, or 0 to use the client area of the window
     * @param format      new format, or {@link Enums.Format#UNKNOWN} to keep the current one
     * @param flags       {@code DXGI_SWAP_CHAIN_FLAG_*}; must be the same as at creation
     * @return the raw HRESULT; {@link Hr#DXGI_ERROR_INVALID_CALL} means a back buffer is still referenced
     */
    public int resizeBuffers(int bufferCount, int width, int height, int format, int flags) {
        try {
            return (int) RESIZE_BUFFERS.invokeExact(this.fn(Vtbl.IDXGISwapChain.RESIZE_BUFFERS), this.ptr(),
                    bufferCount, width, height, format, flags);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Switches between windowed and full-screen mode.
     *
     * @param fullscreen true for full screen
     * @param output     the {@code IDXGIOutput} to go full screen on, or {@link MemorySegment#NULL} to let DXGI choose
     * @return the raw HRESULT; for example {@link Hr#DXGI_ERROR_NOT_CURRENTLY_AVAILABLE} when the window cannot go
     * full screen right now
     */
    public int setFullscreenState(boolean fullscreen, MemorySegment output) {
        try {
            return (int) SET_FULLSCREEN_STATE.invokeExact(this.fn(Vtbl.IDXGISwapChain.SET_FULLSCREEN_STATE), this.ptr(),
                    fullscreen ? 1 : 0, output);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * @return true if the swap chain is in exclusive full-screen mode
     * @throws D3D12Exception if the call fails
     */
    public boolean isFullscreen() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(JAVA_INT);
            int hr = (int) GET_FULLSCREEN_STATE.invokeExact(this.fn(Vtbl.IDXGISwapChain.GET_FULLSCREEN_STATE), this.ptr(), out, MemorySegment.NULL);
            Hr.check(hr, "IDXGISwapChain::GetFullscreenState");

            return out.get(JAVA_INT, 0) != 0;
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Reads the swap chain description. Requires {@code IDXGISwapChain1}.
     */
    public Desc getDesc1() {
        this.require(1, "GetDesc1");

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = DxgiSwapChainDesc1.allocate(arena);
            int hr = (int) GET_DESC1.invokeExact(this.fn(Vtbl.IDXGISwapChain1.GET_DESC1), this.ptr(), out);
            Hr.check(hr, "IDXGISwapChain1::GetDesc1");

            return Desc.readFrom(out);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * @return the window handle ({@code HWND}) the swap chain presents to. Requires {@code IDXGISwapChain1}.
     * @throws D3D12Exception if the swap chain was not created for a window
     */
    public MemorySegment getHwnd() {
        this.require(1, "GetHwnd");

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            int hr = (int) GET_HWND.invokeExact(this.fn(Vtbl.IDXGISwapChain1.GET_HWND), this.ptr(), out);
            Hr.check(hr, "IDXGISwapChain1::GetHwnd");

            return out.get(ADDRESS, 0);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Limits how many frames may be queued for presentation. Requires {@code IDXGISwapChain2} and a swap chain created
     * with {@link Enums.SwapChainFlag#FRAME_LATENCY_WAITABLE_OBJECT}.
     *
     * @param maxLatency the maximum number of queued frames (default 1 to 3)
     * @throws D3D12Exception if the call fails
     */
    public void setMaximumFrameLatency(int maxLatency) {
        this.require(2, "SetMaximumFrameLatency");

        try {
            int hr = (int) SET_MAXIMUM_FRAME_LATENCY.invokeExact(this.fn(Vtbl.IDXGISwapChain2.SET_MAXIMUM_FRAME_LATENCY), this.ptr(), maxLatency);
            Hr.check(hr, "IDXGISwapChain2::SetMaximumFrameLatency");
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * @return the current maximum frame latency. Requires {@code IDXGISwapChain2}.
     * @throws D3D12Exception if the call fails
     */
    public int getMaximumFrameLatency() {
        this.require(2, "GetMaximumFrameLatency");

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(JAVA_INT);
            int hr = (int) GET_MAXIMUM_FRAME_LATENCY.invokeExact(this.fn(Vtbl.IDXGISwapChain2.GET_MAXIMUM_FRAME_LATENCY), this.ptr(), out);
            Hr.check(hr, "IDXGISwapChain2::GetMaximumFrameLatency");

            return out.get(JAVA_INT, 0);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Gets a waitable handle that is signalled when the swap chain can accept another frame. Requires
     * {@code IDXGISwapChain2} and a swap chain created with
     * {@link Enums.SwapChainFlag#FRAME_LATENCY_WAITABLE_OBJECT}.
     *
     * <p>Every call returns a new handle that <b>the caller owns</b> and must close with
     * {@code Kernel32.closeHandle}. Wait on it with {@code Kernel32.waitForSingleObject} before recording a frame.
     *
     * @return the event handle, or a null segment ({@code address() == 0}) if the swap chain has no waitable object
     */
    public MemorySegment getFrameLatencyWaitableObject() {
        this.require(2, "GetFrameLatencyWaitableObject");

        try {
            return (MemorySegment) GET_FRAME_LATENCY_WAITABLE_OBJECT.invokeExact(
                    this.fn(Vtbl.IDXGISwapChain2.GET_FRAME_LATENCY_WAITABLE_OBJECT), this.ptr());
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Gets the index of the back buffer the next {@link #present} call will present, which is the one to render to
     * now. Requires {@code IDXGISwapChain3}. Does not allocate.
     */
    public int getCurrentBackBufferIndex() {
        this.require(3, "GetCurrentBackBufferIndex");

        try {
            return (int) GET_CURRENT_BACK_BUFFER_INDEX.invokeExact(this.fn(Vtbl.IDXGISwapChain3.GET_CURRENT_BACK_BUFFER_INDEX), this.ptr());
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Checks whether the swap chain can use a colour space. Requires {@code IDXGISwapChain3}.
     *
     * @param colorSpace a {@code DXGI_COLOR_SPACE_TYPE} (see {@link Enums.ColorSpaceType})
     * @return a combination of {@link Enums.SwapChainColorSpaceSupportFlag} bits; 0 if the colour space is unsupported
     * @throws D3D12Exception if the call fails
     */
    public int checkColorSpaceSupport(int colorSpace) {
        this.require(3, "CheckColorSpaceSupport");

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(JAVA_INT);
            int hr = (int) CHECK_COLOR_SPACE_SUPPORT.invokeExact(this.fn(Vtbl.IDXGISwapChain3.CHECK_COLOR_SPACE_SUPPORT), this.ptr(), colorSpace, out);
            Hr.check(hr, "IDXGISwapChain3::CheckColorSpaceSupport");

            return out.get(JAVA_INT, 0);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Sets the colour space the back buffers are interpreted in. Requires {@code IDXGISwapChain3}.
     *
     * @param colorSpace a {@code DXGI_COLOR_SPACE_TYPE} (see {@link Enums.ColorSpaceType})
     * @throws D3D12Exception if the call fails, for example because the colour space is not supported
     */
    public void setColorSpace1(int colorSpace) {
        this.require(3, "SetColorSpace1");

        try {
            int hr = (int) SET_COLOR_SPACE1.invokeExact(this.fn(Vtbl.IDXGISwapChain3.SET_COLOR_SPACE1), this.ptr(), colorSpace);
            Hr.check(hr, "IDXGISwapChain3::SetColorSpace1");
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Sets HDR metadata for the swap chain. Requires {@code IDXGISwapChain4}.
     *
     * @param type     a {@code DXGI_HDR_METADATA_TYPE} (see {@link Enums.HdrMetadataType})
     * @param metadata the metadata structure, or {@link MemorySegment#NULL} with type NONE to clear it
     * @throws D3D12Exception if the call fails
     */
    public void setHdrMetaData(int type, MemorySegment metadata) {
        this.require(4, "SetHDRMetaData");

        try {
            int hr = (int) SET_HDR_META_DATA.invokeExact(this.fn(Vtbl.IDXGISwapChain4.SET_HDR_META_DATA), this.ptr(),
                    type, (int) metadata.byteSize(), metadata);
            Hr.check(hr, "IDXGISwapChain4::SetHDRMetaData");
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    private void require(int minimumLevel, String method) {
        if (this.level < minimumLevel) {
            throw new D3D12Exception("IDXGISwapChain" + minimumLevel + "::" + method + " is not available (swap chain only supports IDXGISwapChain"
                    + (this.level == 0 ? "" : this.level) + ")", Hr.E_NOINTERFACE);
        }
    }
}
