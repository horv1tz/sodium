// Owner: WP4 (surface + entry) - implemented (new)
package net.caffeinemc.mods.sodium.client.d3d12.surface;

import net.caffeinemc.mods.sodium.client.d3d12.resource.RenderTargetRef;

/**
 * What {@link D3D12Surface} needs from the swap chain: the seam between the surface state machine (pure logic, tested
 * on Linux with a fake) and {@link SwapChainHolder} (DXGI). Render thread only.
 */
interface SwapChainOps extends AutoCloseable {
    /** @return true once a swap chain with usable back buffers exists (false after a failed create/resize) */
    boolean exists();

    /** @return the width of the back buffers in pixels; only meaningful while {@link #exists()} */
    int width();

    int height();

    /**
     * Creates the swap chain or resizes it to {@code width x height} (both in {@code 1..16384}, the caller validated).
     *
     * @throws RuntimeException {@code D3D12Exception} for a native failure; the holder is then not {@link #exists()}
     */
    void configure(int width, int height);

    /** @return {@code GetCurrentBackBufferIndex()} */
    int currentIndex();

    /** @return the back buffer {@code index} as an attachment (tracked, PRESENT between frames) */
    RenderTargetRef backBuffer(int index);

    /** @return the raw HRESULT of {@code Present(syncInterval, flags)} */
    int present(int syncInterval, int flags);

    /** @return true if {@code Present(0, ALLOW_TEARING)} is legal on this swap chain */
    boolean allowsTearing();

    /** Only with the waitable option: waits at most {@code timeoutMillis} for the frame-latency object; otherwise a no-op. */
    void waitForFrameLatency(long timeoutMillis);

    /** Idle the GPU, release back buffers and swap chain. Idempotent; never throws. */
    @Override
    void close();
}
