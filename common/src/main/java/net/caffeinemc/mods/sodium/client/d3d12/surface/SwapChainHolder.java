// Owner: WP4 (surface + entry)
package net.caffeinemc.mods.sodium.client.d3d12.surface;

import net.caffeinemc.mods.sodium.client.d3d12.core.DeviceContext;
import net.caffeinemc.mods.sodium.client.d3d12.resource.RenderTargetRef;

/**
 * The swap chain, its back buffers and their RTVs (decision D10, spec 05 section 5.4).
 *
 * <ul>
 *     <li>Created by the first {@link #configure}: {@code DXGI_SWAP_EFFECT_FLIP_DISCARD}, {@code R8G8B8A8_UNORM}, 3 buffers,
 *     usage {@code RENDER_TARGET_OUTPUT}, scaling {@code STRETCH}, alpha {@code UNSPECIFIED}, flags {@code ALLOW_TEARING}
 *     iff {@code caps.tearingSupported()} (plus {@code FRAME_LATENCY_WAITABLE_OBJECT} only if
 *     {@code options.waitableSwapChain()}, default off), created with the device's DIRECT queue, then
 *     {@code makeWindowAssociation(hwnd, NO_WINDOW_CHANGES | NO_ALT_ENTER)} <em>after</em> creation; never
 *     {@code SetFullscreenState}. {@code IDXGISwapChain3} is required ({@code supportsBackBufferIndex()}); otherwise
 *     {@code configure} fails and the creation probe has already refused the backend.</li>
 *     <li>Back buffers are wrapped as {@code TrackedResource} (initial state {@code PRESENT}) with one RTV each from
 *     {@code ctx.descriptors().rtv()}; they are exposed as {@link RenderTargetRef}s (no texture object).</li>
 *     <li>Resize: {@code clock.flushGpu} (covers queued presents; waiting for frame {@code current - 1} alone would not),
 *     release the RTVs and every {@code GetBuffer} reference, {@code ResizeBuffers(3, w, h, UNKNOWN, sameFlags)} with
 *     explicit non-zero sizes (never 0: Wine aborts the process), re-fetch the buffers.</li>
 * </ul>
 * Render thread only.
 */
final class SwapChainHolder implements AutoCloseable {
    private final DeviceContext ctx;
    private final long hwnd;

    SwapChainHolder(DeviceContext ctx, long hwnd) {
        this.ctx = ctx;
        this.hwnd = hwnd;
    }

    boolean exists() {
        throw new UnsupportedOperationException("WP4");
    }

    int width() {
        throw new UnsupportedOperationException("WP4");
    }

    int height() {
        throw new UnsupportedOperationException("WP4");
    }

    /**
     * Creates the swap chain or resizes it to {@code width x height} (both in {@code 1..16384}, the caller validated).
     *
     * @throws RuntimeException {@code D3D12Exception}; the caller maps it to {@code SurfaceException} / device loss
     */
    void configure(int width, int height) {
        throw new UnsupportedOperationException("WP4: create or flushGpu + ResizeBuffers, fetch back buffers + RTVs");
    }

    /** @return {@code GetCurrentBackBufferIndex()} */
    int currentIndex() {
        throw new UnsupportedOperationException("WP4");
    }

    /** @return the back buffer {@code index} as an attachment (tracked, PRESENT between frames) */
    RenderTargetRef backBuffer(int index) {
        throw new UnsupportedOperationException("WP4");
    }

    /** @return the raw HRESULT of {@code Present(syncInterval, flags)} */
    int present(int syncInterval, int flags) {
        throw new UnsupportedOperationException("WP4");
    }

    /** @return true if tearing flags may be used with {@code Present(0, ALLOW_TEARING)} on this swap chain */
    boolean allowsTearing() {
        throw new UnsupportedOperationException("WP4");
    }

    /**
     * Only with {@code options.waitableSwapChain()}: waits on the frame-latency object for at most {@code timeoutMillis}
     * (250) and proceeds on timeout (the later {@code Present} is the real throttle); otherwise a no-op.
     */
    void waitForFrameLatency(long timeoutMillis) {
        throw new UnsupportedOperationException("WP4");
    }

    /** Idle GPU, free RTVs, release back buffers, release the swap chain (before the queue). Idempotent. */
    @Override
    public void close() {
        throw new UnsupportedOperationException("WP4");
    }

    DeviceContext context() {
        return this.ctx;
    }

    long hwnd() {
        return this.hwnd;
    }
}
