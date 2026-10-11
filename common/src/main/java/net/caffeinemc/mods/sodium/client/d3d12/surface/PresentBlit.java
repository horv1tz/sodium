// Owner: WP4 (surface + entry)
package net.caffeinemc.mods.sodium.client.d3d12.surface;

import net.caffeinemc.mods.sodium.client.d3d12.command.D3D12CommandEncoder;
import net.caffeinemc.mods.sodium.client.d3d12.core.DeviceContext;
import net.caffeinemc.mods.sodium.client.d3d12.resource.D3D12TextureView;
import net.caffeinemc.mods.sodium.client.d3d12.resource.RenderTargetRef;

/**
 * Records the flipped copy of the main target into the acquired back buffer as an <b>ordinary render pass</b>
 * (decision D10, spec 05 section 5.10): no special blit path in the encoder.
 *
 * <pre>
 * copyW = min(swapWidth,  view.getWidth(0));  copyH = min(swapHeight, view.getHeight(0))      // sizes of mip view.baseMipLevel()
 * pass  = encoder.beginInternalPass(PassSpec.backBuffer(backBuffer))     // tracker: back buffer PRESENT -> RT at pass begin
 * pass.setPipeline(blitPipeline)                                          // BuiltinPipelines.fullscreenBlit(ctx, R8G8B8A8_UNORM)
 * pass.setUniform(0, new TextureViewAndSampler(view, pointSampler))       // tracker: source mips -> SRV_ALL at the draw
 * pass.pushConstants(copyW, copyH as two ints)
 * pass.draw(3, 1, 0, 0)
 * encoder.submitRenderPass()
 * ctx.tracker().require(backBuffer.resource(), subresource, PRESENT)      // flushed by submit() before Close()
 * </pre>
 * The pixel shader returns black for pixels outside the copied rectangle and otherwise
 * {@code Src.Load(int3(x, copyH - 1 - y, 0))}: no filtering, no scaling, vertical flip, anchored top-left, a defined colour
 * in every back-buffer pixel (flip-model DISCARD contents are undefined; the borderless window is one pixel wider than the
 * main target). The SRV uses {@code MostDetailedMip = view.baseMipLevel()}. The blit pipeline is the device-wide memoised
 * instance of {@code BuiltinPipelines.fullscreenBlit} (already built, and thereby proven to work, by
 * {@code D3D12Backend.createDevice}; shared, so {@link #close()} must <b>not</b> close it). Only the point sampler
 * (clamp, NEAREST) belongs to this object: created lazily on first use, released by {@link #close()}.
 *
 * <p>Render thread only.
 */
final class PresentBlit implements AutoCloseable {
    private final DeviceContext ctx;

    PresentBlit(DeviceContext ctx) {
        this.ctx = ctx;
    }

    /**
     * @param swapWidth  swap chain width (pixels)
     * @param swapHeight swap chain height (pixels)
     */
    void record(D3D12CommandEncoder encoder, RenderTargetRef backBuffer, D3D12TextureView source, int swapWidth, int swapHeight) {
        throw new UnsupportedOperationException("WP4: see class comment");
    }

    @Override
    public void close() {
        throw new UnsupportedOperationException("WP4: close the point sampler (NOT the shared blit pipeline)");
    }

    DeviceContext context() {
        return this.ctx;
    }
}
