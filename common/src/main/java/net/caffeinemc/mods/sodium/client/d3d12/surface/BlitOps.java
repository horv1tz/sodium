// Owner: WP4 (surface + entry) - implemented (new)
package net.caffeinemc.mods.sodium.client.d3d12.surface;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import net.caffeinemc.mods.sodium.client.d3d12.resource.RenderTargetRef;

/**
 * What {@link D3D12Surface} needs from the present blit: the seam between the surface state machine and
 * {@link PresentBlit} (which records an ordinary render pass). Render thread only.
 */
interface BlitOps extends AutoCloseable {
    /**
     * Makes sure the blit pipeline is available (the device-wide memoised instance that {@code createDevice} already
     * built and thereby proved), called by every successful {@code configure}.
     *
     * @throws RuntimeException if the pipeline cannot be obtained (the surface reports a {@code SurfaceException})
     */
    void ensure();

    /**
     * Records the flipped copy of {@code source} into {@code backBuffer}.
     *
     * @param encoder must be the backend's command encoder, {@code source} one of its texture views
     * @throws IllegalArgumentException for foreign objects (nothing was recorded)
     */
    void record(CommandEncoderBackend encoder, GpuTextureView source, RenderTargetRef backBuffer, int swapWidth, int swapHeight);

    /** Releases what the blit owns (not the shared pipeline). Idempotent; never throws. */
    @Override
    void close();
}
