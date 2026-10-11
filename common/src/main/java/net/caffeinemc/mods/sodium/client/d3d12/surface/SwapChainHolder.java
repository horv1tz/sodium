// Owner: WP4 (surface + entry) - implemented
package net.caffeinemc.mods.sodium.client.d3d12.surface;

import net.caffeinemc.mods.sodium.client.d3d12.core.D3D12Log;
import net.caffeinemc.mods.sodium.client.d3d12.core.DeviceContext;
import net.caffeinemc.mods.sodium.client.d3d12.interop.D3D12Exception;
import net.caffeinemc.mods.sodium.client.d3d12.interop.Hr;
import net.caffeinemc.mods.sodium.client.d3d12.interop.Kernel32;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Enums;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Iids;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12Resource;
import net.caffeinemc.mods.sodium.client.d3d12.interop.dxgi.DxgiFactory;
import net.caffeinemc.mods.sodium.client.d3d12.interop.dxgi.DxgiSwapChain;
import net.caffeinemc.mods.sodium.client.d3d12.resource.RenderTargetRef;
import net.caffeinemc.mods.sodium.client.d3d12.state.ResourceStates;
import net.caffeinemc.mods.sodium.client.d3d12.state.TrackedResource;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;

/**
 * The swap chain, its back buffers and their RTVs (decision D10, spec 05 section 5.4).
 *
 * <ul>
 *     <li>Created by the first {@link #configure}: {@code DXGI_SWAP_EFFECT_FLIP_DISCARD}, {@code R8G8B8A8_UNORM}, 3 buffers,
 *     usage {@code RENDER_TARGET_OUTPUT}, scaling {@code STRETCH}, alpha {@code UNSPECIFIED}, flags {@code ALLOW_TEARING}
 *     iff {@code caps.tearingSupported()} (plus {@code FRAME_LATENCY_WAITABLE_OBJECT} only if
 *     {@code options.waitableSwapChain()}, default off; {@link SwapChainFlags#production}), created with the device's
 *     DIRECT queue, then {@code makeWindowAssociation(hwnd, NO_WINDOW_CHANGES | NO_ALT_ENTER)} <em>after</em> creation;
 *     never {@code SetFullscreenState}. {@code IDXGISwapChain3} is required ({@code supportsBackBufferIndex()});
 *     otherwise {@code configure} fails and the creation probe has already refused the backend.</li>
 *     <li>Back buffers are wrapped as {@code TrackedResource} (initial state {@code PRESENT}) with one RTV each from
 *     {@code ctx.descriptors().rtv()}; they are exposed as {@link RenderTargetRef}s (no texture object).</li>
 *     <li>Resize: {@code clock.flushGpu} (covers queued presents; waiting for frame {@code current - 1} alone would not),
 *     release the RTVs and every {@code GetBuffer} reference, {@code ResizeBuffers(3, w, h, UNKNOWN, sameFlags)} with
 *     explicit non-zero sizes (never 0: Wine aborts the process), re-fetch the buffers.</li>
 * </ul>
 *
 * <p><b>Failure state.</b> A configure that fails leaves the holder without usable back buffers ({@link #exists()} false,
 * all references and RTVs released) but with the swap chain object, if it was created, so that the next
 * {@link #configure} takes the resize path; creation failures leave nothing behind. {@link #close()} is idempotent and
 * never throws.
 *
 * <p>Render thread only.
 */
final class SwapChainHolder implements SwapChainOps {
    /** The frame-latency cap handed to the optional waitable object (matches the two frame slots). */
    private static final int MAXIMUM_FRAME_LATENCY = 2;
    private static final long FLUSH_TIMEOUT_NS = 10_000_000_000L;

    private final DeviceContext ctx;
    private final long hwnd;

    private @Nullable DxgiSwapChain chain;
    private int flags;
    private int width;
    private int height;
    private boolean buffersReady;
    private final D3D12Resource[] buffers = new D3D12Resource[SwapChainFlags.BUFFER_COUNT];
    private final RenderTargetRef[] refs = new RenderTargetRef[SwapChainFlags.BUFFER_COUNT];
    private final long[] rtvs = new long[SwapChainFlags.BUFFER_COUNT];
    private @Nullable MemorySegment waitable;
    private boolean closed;

    SwapChainHolder(DeviceContext ctx, long hwnd) {
        this.ctx = ctx;
        this.hwnd = hwnd;
    }

    @Override
    public boolean exists() {
        return this.buffersReady;
    }

    @Override
    public int width() {
        return this.width;
    }

    @Override
    public int height() {
        return this.height;
    }

    /**
     * Creates the swap chain or resizes it to {@code width x height} (both in {@code 1..16384}, the caller validated).
     *
     * @throws RuntimeException {@code D3D12Exception}; the caller maps it to {@code SurfaceException} / device loss
     */
    @Override
    public void configure(int width, int height) {
        if (this.closed) {
            throw new IllegalStateException("The swap chain holder is closed");
        }

        if (width < 1 || height < 1 || width > 16384 || height > 16384) {
            throw new IllegalArgumentException("Invalid swap chain size " + width + "x" + height);
        }

        if (this.chain == null) {
            this.create(width, height);
        } else {
            this.resize(width, height);
        }
    }

    private void create(int width, int height) {
        DxgiFactory factory = this.ctx.factory();
        MemorySegment window = MemorySegment.ofAddress(this.hwnd);
        this.flags = SwapChainFlags.production(this.ctx.caps().tearingSupported(), this.ctx.options().waitableSwapChain());

        DxgiSwapChain created = factory.createSwapChainForHwnd(this.ctx.queue(), window,
                DxgiSwapChain.Desc.flipDiscard(width, height, SwapChainFlags.FORMAT, SwapChainFlags.BUFFER_COUNT, this.flags));

        try {
            factory.makeWindowAssociation(window, SwapChainFlags.WINDOW_ASSOCIATION);

            if (!created.supportsBackBufferIndex()) {
                throw new D3D12Exception("IDXGISwapChain3 is required by the Direct3D 12 backend but the runtime offers only version "
                        + created.version(), Hr.E_NOINTERFACE);
            }

            if (this.waitableRequested(created)) {
                created.setMaximumFrameLatency(MAXIMUM_FRAME_LATENCY);
            }

            this.chain = created;
            this.refreshWaitable();
            this.fetchBuffers(width, height);
        } catch (Throwable t) {
            this.releaseBuffers();
            this.closeWaitable();
            this.chain = null;
            created.close();

            throw t;
        }
    }

    private void resize(int width, int height) {
        DxgiSwapChain current = this.chain;

        if (current == null) {
            throw new IllegalStateException("no swap chain");
        }

        // the GPU may still be presenting from / rendering into the buffers; queued presents are covered by the aux fence
        this.ctx.clock().flushGpu(FLUSH_TIMEOUT_NS);
        this.releaseBuffers();

        int hr = current.resizeBuffers(SwapChainFlags.BUFFER_COUNT, width, height, Enums.Format.UNKNOWN, this.flags);

        if (hr < 0) {
            throw new D3D12Exception("IDXGISwapChain::ResizeBuffers(" + width + "x" + height + ") failed", hr);
        }

        this.refreshWaitable();

        try {
            this.fetchBuffers(width, height);
        } catch (Throwable t) {
            this.releaseBuffers();

            throw t;
        }
    }

    private boolean waitableRequested(DxgiSwapChain candidate) {
        return (this.flags & Enums.SwapChainFlag.FRAME_LATENCY_WAITABLE_OBJECT) != 0 && candidate.supportsFrameLatency();
    }

    /** Takes a new frame-latency handle (every call returns a new owned handle) and closes the previous one. */
    private void refreshWaitable() {
        DxgiSwapChain current = this.chain;

        if (current == null || !this.waitableRequested(current)) {
            return;
        }

        MemorySegment handle = current.getFrameLatencyWaitableObject();
        this.closeWaitable();

        if (handle != null && handle.address() != 0L) {
            this.waitable = handle;
        }
    }

    private void closeWaitable() {
        MemorySegment handle = this.waitable;
        this.waitable = null;

        if (handle != null) {
            try {
                Kernel32.closeHandle(handle);
            } catch (RuntimeException e) {
                D3D12Log.warn("Could not close the frame-latency handle: {}", e.toString());
            }
        }
    }

    private void fetchBuffers(int width, int height) {
        DxgiSwapChain current = this.chain;

        if (current == null) {
            throw new IllegalStateException("no swap chain");
        }

        for (int i = 0; i < SwapChainFlags.BUFFER_COUNT; i++) {
            D3D12Resource buffer = new D3D12Resource(current.getBuffer(i, Iids.ID3D12Resource));
            this.buffers[i] = buffer;

            if (this.ctx.labels()) {
                buffer.setName("Sodium D3D12 back buffer " + i);
            }

            long rtv = this.ctx.descriptors().rtv().allocate();
            this.rtvs[i] = rtv;
            this.ctx.d3d().createRenderTargetView(buffer, null, rtv);

            this.refs[i] = new RenderTargetRef(new TrackedResource(buffer, 1, ResourceStates.PRESENT), 0, rtv, width, height,
                    SwapChainFlags.FORMAT, 0);
        }

        this.width = width;
        this.height = height;
        this.buffersReady = true;
    }

    /** Frees the RTVs and drops every {@code GetBuffer} reference. The GPU must be idle (or must never have seen them). */
    private void releaseBuffers() {
        this.buffersReady = false;
        this.width = 0;
        this.height = 0;

        for (int i = 0; i < SwapChainFlags.BUFFER_COUNT; i++) {
            long rtv = this.rtvs[i];
            D3D12Resource buffer = this.buffers[i];
            this.rtvs[i] = 0L;
            this.buffers[i] = null;
            this.refs[i] = null;

            if (rtv != 0L) {
                try {
                    this.ctx.descriptors().rtv().free(rtv);
                } catch (RuntimeException e) {
                    D3D12Log.warn("Could not free a back buffer RTV: {}", e.toString());
                }
            }

            if (buffer != null) {
                try {
                    buffer.close();
                } catch (RuntimeException e) {
                    D3D12Log.warn("Could not release a back buffer: {}", e.toString());
                }
            }
        }
    }

    /** @return {@code GetCurrentBackBufferIndex()} */
    @Override
    public int currentIndex() {
        return this.requireChain().getCurrentBackBufferIndex();
    }

    /** @return the back buffer {@code index} as an attachment (tracked, PRESENT between frames) */
    @Override
    public RenderTargetRef backBuffer(int index) {
        RenderTargetRef ref = index >= 0 && index < this.refs.length ? this.refs[index] : null;

        if (ref == null) {
            throw new IllegalStateException("Back buffer " + index + " is not available (the swap chain is not configured)");
        }

        return ref;
    }

    /** @return the raw HRESULT of {@code Present(syncInterval, flags)} */
    @Override
    public int present(int syncInterval, int flags) {
        return this.requireChain().present(syncInterval, flags);
    }

    /** @return true if tearing flags may be used with {@code Present(0, ALLOW_TEARING)} on this swap chain */
    @Override
    public boolean allowsTearing() {
        return (this.flags & Enums.SwapChainFlag.ALLOW_TEARING) != 0;
    }

    /**
     * Only with {@code options.waitableSwapChain()}: waits on the frame-latency object for at most {@code timeoutMillis}
     * (250) and proceeds on timeout (the later {@code Present} is the real throttle); otherwise a no-op.
     */
    @Override
    public void waitForFrameLatency(long timeoutMillis) {
        MemorySegment handle = this.waitable;

        if (handle == null) {
            return;
        }

        int result = Kernel32.waitForSingleObject(handle, (int) Math.max(0L, Math.min(timeoutMillis, Integer.MAX_VALUE)));

        if (result == Kernel32.WAIT_TIMEOUT) {
            D3D12Log.warnOnce("swap-chain-latency-timeout", "The frame-latency object was not signalled within {} ms; presenting anyway",
                    timeoutMillis);
        } else if (result != Kernel32.WAIT_OBJECT_0) {
            D3D12Log.warnOnce("swap-chain-latency-failed", "Waiting for the frame-latency object failed (0x{})", Integer.toHexString(result));
        }
    }

    /** Idle GPU, free RTVs, release back buffers, release the swap chain (before the queue). Idempotent. */
    @Override
    public void close() {
        if (this.closed) {
            return;
        }

        this.closed = true;

        if (this.chain == null && !this.buffersReady) {
            return;
        }

        try {
            this.ctx.clock().flushGpu(FLUSH_TIMEOUT_NS);
        } catch (RuntimeException e) {
            // continue: the process is shutting down and the alternative is a leak that DXGI would complain about anyway
            D3D12Log.warn("Could not idle the GPU before releasing the swap chain: {}", e.toString());
        }

        this.releaseBuffers();
        this.closeWaitable();

        DxgiSwapChain current = this.chain;
        this.chain = null;

        if (current != null) {
            try {
                current.close();
            } catch (RuntimeException e) {
                D3D12Log.warn("Could not release the swap chain: {}", e.toString());
            }
        }
    }

    private DxgiSwapChain requireChain() {
        DxgiSwapChain current = this.chain;

        if (current == null) {
            throw new IllegalStateException("The swap chain does not exist");
        }

        return current;
    }

    DeviceContext context() {
        return this.ctx;
    }

    long hwnd() {
        return this.hwnd;
    }
}
