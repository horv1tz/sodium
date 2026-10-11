// Owner: WP4 (surface + entry) - implemented
package net.caffeinemc.mods.sodium.client.d3d12.surface;

import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.device.SurfaceException;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import net.caffeinemc.mods.sodium.client.d3d12.command.D3D12CommandEncoder;
import net.caffeinemc.mods.sodium.client.d3d12.core.D3D12Log;
import net.caffeinemc.mods.sodium.client.d3d12.core.DeviceContext;
import net.caffeinemc.mods.sodium.client.d3d12.interop.D3D12Exception;
import net.caffeinemc.mods.sodium.client.d3d12.interop.Hr;
import net.caffeinemc.mods.sodium.client.d3d12.interop.dxgi.DxgiSwapChain;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * The swap-chain surface (spec 01 section 6, spec 05 sections 5.x). Created by {@code D3D12GpuDevice.createSurface} once
 * the window exists; creates <b>no</b> swap chain (that happens in the first {@code configure}). Owner of the
 * {@link SwapChainHolder} and the {@link PresentBlit}.
 *
 * <h2>Behaviour</h2>
 * <ul>
 *     <li>{@code configure} (called routinely with an unchanged size: every restore-from-minimise, vsync toggle, failed
 *     acquire): {@code width/height} outside {@code 1..16384} -> {@code SurfaceException} (never {@code ResizeBuffers(0, 0)}),
 *     before any native call. Store the present mode; <b>fast path</b> (swap chain exists, healthy, same size): clear
 *     {@code suboptimal}, any deferred error and the stale flag and return without touching the GPU. Otherwise
 *     create/resize ({@code SwapChainHolder.configure}), then ensure the blit pipeline. A native failure sets
 *     {@code stale = suboptimal = true} and throws {@code SurfaceException}; device loss throws
 *     {@code GpuDeviceLossException}.</li>
 *     <li>{@code isSuboptimal()} is a field: false after every successful configure (including the fast path), true only
 *     after a failed configure or {@code DXGI_STATUS_MODE_CHANGED}; never computed from window sizes (the borderless
 *     window is legitimately one pixel wider); valid in every state, never throws.</li>
 *     <li>{@code acquireNextTexture}: (1) {@code isIconified} -> {@code SurfaceException("Cannot acquire minimized
 *     window")}; (2) a stored deferred {@code present()} error is cleared and thrown; (3) no/stale swap chain ->
 *     {@code SurfaceException}; (4) optional frame-latency wait (250 ms cap, default off); (5)
 *     {@code index = GetCurrentBackBufferIndex()}, mark acquired. No GPU work is recorded.</li>
 *     <li>{@code blitFromTexture}: see {@link PresentBlit}; the encoder argument is the backend singleton
 *     ({@code (D3D12CommandEncoder)} cast), the view a {@code D3D12TextureView}.</li>
 *     <li>{@code present}: {@code Present(FIFO ? 1 : 0, IMMEDIATE &amp;&amp; tearing ? ALLOW_TEARING : 0)}; any
 *     {@code hr >= 0} is success ({@code S_OK}, {@code DXGI_STATUS_OCCLUDED}); {@code MODE_CHANGED} sets suboptimal;
 *     device-loss codes throw {@code GpuDeviceLossException} (with the removed reason); {@code INVALID_CALL} and other
 *     DXGI swap-chain errors are stored as a {@code SurfaceException} for the next {@code acquireNextTexture} and the chain
 *     is marked stale; any other failing HRESULT throws unchecked. It never waits on the GPU and never throws
 *     {@code SurfaceException}.</li>
 *     <li>{@code supportedPresentModes()} = {@code {FIFO, IMMEDIATE}}, constant, no DXGI call.</li>
 *     <li>{@code close()}: idle the GPU, free RTVs, release back buffers and swap chain (before the queue), close the
 *     blit (its point sampler only: the blit pipeline is the shared device-wide instance); idempotent; never touches a
 *     closed device (it is closed <em>before</em> the device).</li>
 * </ul>
 * Render thread only.
 *
 * <p>Testing seams: the swap chain and the blit are reached through {@link SwapChainOps} and {@link BlitOps}, so the whole
 * state machine runs on Linux against fakes ({@code SurfaceStateTest}).
 */
public final class D3D12Surface implements GpuSurfaceBackend {
    /** Largest swap chain edge ({@code D3D12} texture limit; also what {@code DeviceInfo.limits} reports). */
    static final int MAX_SIZE = 16384;

    private static final Set<GpuSurface.PresentMode> PRESENT_MODES = Collections.unmodifiableSet(EnumSet.of(GpuSurface.PresentMode.FIFO, GpuSurface.PresentMode.IMMEDIATE));

    /** How a {@code Present} result is handled (the HRESULT table of ARCHITECTURE 2.i). */
    enum PresentOutcome {
        /** {@code hr >= 0} except MODE_CHANGED: nothing to do. */
        SUCCESS,
        /** {@code DXGI_STATUS_MODE_CHANGED}: success, but ask Minecraft to reconfigure. */
        MODE_CHANGED,
        /** {@code DXGI_ERROR_DEVICE_REMOVED/HUNG/RESET/DRIVER_INTERNAL_ERROR}: {@code GpuDeviceLossException}. */
        DEVICE_LOSS,
        /** Any other DXGI error ({@code INVALID_CALL}, {@code NOT_CURRENTLY_AVAILABLE}, ...): deferred {@code SurfaceException}. */
        SURFACE_ERROR,
        /** A failing HRESULT outside the DXGI facility ({@code E_OUTOFMEMORY}, ...): unchecked exception. */
        FATAL
    }

    private final DeviceContext ctx;
    private final long hwnd;
    private final BooleanSupplier isIconified;
    private final SwapChainOps chain;
    private final BlitOps blit;

    private boolean closed;
    private boolean acquired;
    private int acquiredIndex;
    private boolean stale;
    private boolean suboptimal;
    private GpuSurface.PresentMode presentMode = GpuSurface.PresentMode.FIFO;
    private @Nullable SurfaceException deferredError;

    /** Creates the surface object only: no swap chain, no native call (the HWND was resolved by the caller). */
    public D3D12Surface(DeviceContext ctx, long hwnd, BooleanSupplier isIconified) {
        this(ctx, hwnd, isIconified, new SwapChainHolder(ctx, hwnd), new PresentBlit(ctx));
    }

    /**
     * Test constructor (also what the public one delegates to). The surface registers itself with the context's
     * {@code ObjectRegistry}: Minecraft closes it before the device, but on a crash path that skips the close the device
     * still releases the swap chain (in the registry's force-close, after the GPU is idle and before the queue goes).
     */
    D3D12Surface(DeviceContext ctx, long hwnd, BooleanSupplier isIconified, SwapChainOps chain, BlitOps blit) {
        this.ctx = ctx;
        this.hwnd = hwnd;
        this.isIconified = isIconified;
        this.chain = chain;
        this.blit = blit;

        ctx.registry().register(this, "surface");
    }

    @Override
    public void configure(GpuSurface.Configuration config) throws SurfaceException {
        this.requireOpen("configure");

        int width = config.width();
        int height = config.height();

        if (width < 1 || height < 1 || width > MAX_SIZE || height > MAX_SIZE) {
            throw new SurfaceException("Cannot configure a " + width + "x" + height + " swap chain (supported: 1.." + MAX_SIZE + ")");
        }

        if (!PRESENT_MODES.contains(config.presentMode())) {
            throw new SurfaceException("Unsupported present mode " + config.presentMode());
        }

        this.presentMode = config.presentMode();

        if (this.chain.exists() && !this.stale && this.chain.width() == width && this.chain.height() == height) {
            // routine no-op reconfiguration (restore from minimise, vsync toggle, duplicate triggers): no GPU idle, no resize
            this.suboptimal = false;
            this.deferredError = null;

            return;
        }

        try {
            this.chain.configure(width, height);
            this.blit.ensure();
        } catch (RuntimeException e) {
            this.stale = true;
            this.suboptimal = true;

            throw this.configureFailure(e, width, height);
        }

        this.stale = false;
        this.suboptimal = false;
        this.deferredError = null;
    }

    /** Maps a failure of the create/resize path: device loss -> unchecked, native failure -> {@code SurfaceException}. */
    private SurfaceException configureFailure(RuntimeException e, int width, int height) {
        if (e instanceof D3D12Exception d3d) {
            if (d3d.isDeviceLoss()) {
                throw this.ctx.errors().translate(d3d, "configuring the swap chain");
            }

            SurfaceException failure = new SurfaceException("Could not configure the Direct3D 12 swap chain to " + width + "x" + height
                    + ": " + d3d.getMessage());
            failure.initCause(d3d);

            return failure;
        }

        // a GpuDeviceLossException, a wait timeout (IllegalStateException) or a programming error: not recoverable here
        throw e;
    }

    @Override
    public boolean isSuboptimal() {
        return this.suboptimal;
    }

    @Override
    public void acquireNextTexture() throws SurfaceException {
        this.requireOpen("acquireNextTexture");

        if (this.isIconified.getAsBoolean()) {
            throw new SurfaceException("Cannot acquire minimized window");
        }

        SurfaceException deferred = this.deferredError;

        if (deferred != null) {
            this.deferredError = null;

            throw deferred;
        }

        if (!this.chain.exists() || this.stale) {
            throw new SurfaceException("The Direct3D 12 swap chain is " + (this.chain.exists() ? "out of date" : "not configured"));
        }

        if (this.ctx.options().waitableSwapChain()) {
            this.chain.waitForFrameLatency(SwapChainFlags.FRAME_LATENCY_WAIT_MILLIS);
        }

        this.acquiredIndex = this.chain.currentIndex();
        this.acquired = true;
    }

    @Override
    public void blitFromTexture(CommandEncoderBackend commandEncoder, GpuTextureView textureView) {
        this.requireOpen("blitFromTexture");

        if (!this.acquired) {
            throw new IllegalStateException("blitFromTexture requires an acquired surface texture");
        }

        if (commandEncoder instanceof D3D12CommandEncoder encoder && encoder.passOpen()) {
            // the frontend guarantees it; the blit would otherwise be recorded into (or collide with) somebody else's pass
            throw new IllegalStateException("blitFromTexture was called while a render pass is open");
        }

        this.blit.record(commandEncoder, textureView, this.chain.backBuffer(this.acquiredIndex), this.chain.width(), this.chain.height());
    }

    @Override
    public void present() {
        this.requireOpen("present");

        if (!this.acquired) {
            throw new IllegalStateException("present() requires an acquired surface texture");
        }

        // from here on the texture is consumed whatever Present answers (the frontend clears its flag after we return)
        this.acquired = false;

        GpuSurface.PresentMode mode = this.presentMode;
        int syncInterval = syncInterval(mode);
        int flags = presentFlags(mode, this.chain.allowsTearing());
        int hr = this.chain.present(syncInterval, flags);

        switch (classifyPresent(hr)) {
            case SUCCESS -> {
            }
            case MODE_CHANGED -> this.suboptimal = true;
            case DEVICE_LOSS -> {
                this.stale = true;

                throw this.ctx.errors().translate(new D3D12Exception("IDXGISwapChain::Present failed", hr), "Present");
            }
            case SURFACE_ERROR -> {
                this.stale = true;
                this.suboptimal = true;
                D3D12Exception cause = new D3D12Exception("IDXGISwapChain::Present failed", hr);
                SurfaceException deferred = new SurfaceException(cause.getMessage());
                deferred.initCause(cause);
                this.deferredError = deferred;
                D3D12Log.warn("Present returned HRESULT 0x{}; the swap chain is rebuilt before the next frame", Integer.toHexString(hr));
            }
            case FATAL -> throw this.ctx.errors().translate(new D3D12Exception("IDXGISwapChain::Present failed", hr), "Present");
        }
    }

    @Override
    public Collection<GpuSurface.PresentMode> supportedPresentModes() {
        return PRESENT_MODES;
    }

    /** Idle the GPU, release swap chain and blit. Idempotent; never throws. */
    @Override
    public void close() {
        if (this.closed) {
            return;
        }

        this.closed = true;
        this.acquired = false;

        try {
            this.ctx.registry().unregister(this);
        } catch (RuntimeException e) {
            D3D12Log.debug("Could not unregister the surface: {}", e.toString());
        }

        try {
            this.chain.close();
        } catch (RuntimeException e) {
            D3D12Log.warn("Could not close the swap chain: {}", e.toString());
        }

        try {
            this.blit.close();
        } catch (RuntimeException e) {
            D3D12Log.warn("Could not close the present blit: {}", e.toString());
        }
    }

    /**
     * The entry check of every surface call except {@code close}, {@code isSuboptimal} and {@code supportedPresentModes}:
     * the surface is open and the backend is not in the sticky FAILED state (a native failure after mutation cannot be
     * repaired; only {@code close()} keeps working).
     */
    private void requireOpen(String operation) {
        if (this.closed) {
            throw new IllegalStateException("The Direct3D 12 surface is closed (" + operation + ")");
        }

        this.ctx.errors().checkHealthy();
    }

    /** @return the sync interval of a present mode: FIFO waits for one vertical blank, IMMEDIATE for none */
    static int syncInterval(GpuSurface.PresentMode mode) {
        return mode == GpuSurface.PresentMode.FIFO ? 1 : 0;
    }

    /**
     * @param tearingAllowed the swap chain was created with {@code ALLOW_TEARING}
     * @return {@code ALLOW_TEARING} for IMMEDIATE on such a swap chain (legal only with sync interval 0), otherwise 0
     */
    static int presentFlags(GpuSurface.PresentMode mode, boolean tearingAllowed) {
        return mode == GpuSurface.PresentMode.IMMEDIATE && tearingAllowed ? DxgiSwapChain.PRESENT_ALLOW_TEARING : 0;
    }

    /** The HRESULT table of {@code present()} (ARCHITECTURE 2.i, spec 05 5.11); pure. */
    static PresentOutcome classifyPresent(int hr) {
        if (hr >= 0) {
            return hr == Hr.DXGI_STATUS_MODE_CHANGED ? PresentOutcome.MODE_CHANGED : PresentOutcome.SUCCESS;
        }

        if (Hr.isDeviceLoss(hr)) {
            return PresentOutcome.DEVICE_LOSS;
        }

        // FACILITY_DXGI = 0x87A: INVALID_CALL, NOT_CURRENTLY_AVAILABLE, UNSUPPORTED, WAS_STILL_DRAWING, ...
        return ((hr >>> 16) & 0x1FFF) == 0x87A ? PresentOutcome.SURFACE_ERROR : PresentOutcome.FATAL;
    }

    DeviceContext context() {
        return this.ctx;
    }

    long hwnd() {
        return this.hwnd;
    }

    BooleanSupplier isIconified() {
        return this.isIconified;
    }
}
