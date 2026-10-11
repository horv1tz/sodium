// Owner: WP4 (surface + entry) - implemented
package net.caffeinemc.mods.sodium.client.d3d12.surface;

import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import net.caffeinemc.mods.sodium.client.d3d12.command.D3D12CommandEncoder;
import net.caffeinemc.mods.sodium.client.d3d12.core.D3D12Log;
import net.caffeinemc.mods.sodium.client.d3d12.core.DeviceContext;
import net.caffeinemc.mods.sodium.client.d3d12.pass.PassSpec;
import net.caffeinemc.mods.sodium.client.d3d12.pipeline.BuiltinPipelines;
import net.caffeinemc.mods.sodium.client.d3d12.pipeline.D3D12RenderPipeline;
import net.caffeinemc.mods.sodium.client.d3d12.resource.D3D12TextureView;
import net.caffeinemc.mods.sodium.client.d3d12.resource.RenderTargetRef;
import net.caffeinemc.mods.sodium.client.d3d12.state.ResourceStates;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.OptionalDouble;

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
 * <p>The pass is always ended, also when a call inside it throws, so a failure never leaves the encoder "inside a render
 * pass" for the next {@code submit()}. Per frame this allocates nothing: the {@code PassSpec} is cached per back buffer (the chain rotates through all of them),
 * the push-constant buffer and the sampler binding are reused.
 *
 * <p>Render thread only.
 */
final class PresentBlit implements BlitOps {
    private final DeviceContext ctx;
    /** {@code copyW, copyH}: native-order direct buffer, rewritten for each blit. */
    private final ByteBuffer pushConstants = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder());

    private @Nullable D3D12RenderPipeline pipeline;
    private @Nullable GpuSampler pointSampler;
    /** One cached {@code PassSpec} per back buffer (identity of the {@code RenderTargetRef}); the chain rotates through all of them. */
    private final @Nullable RenderTargetRef[] cachedTargets = new RenderTargetRef[SwapChainFlags.BUFFER_COUNT];
    private final @Nullable PassSpec[] cachedSpecs = new PassSpec[SwapChainFlags.BUFFER_COUNT];
    private int nextSpecSlot;
    private @Nullable GpuTextureView cachedView;
    private @Nullable TextureViewAndSampler cachedBinding;
    private boolean closed;

    PresentBlit(DeviceContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void ensure() {
        this.pipeline();
    }

    private D3D12RenderPipeline pipeline() {
        D3D12RenderPipeline current = this.pipeline;

        if (current == null) {
            // memoised per device: the instance createDevice built; the shared pipeline is never closed here
            current = BuiltinPipelines.fullscreenBlit(this.ctx, SwapChainFlags.FORMAT);
            this.pipeline = current;
        }

        return current;
    }

    private GpuSampler pointSampler() {
        GpuSampler current = this.pointSampler;

        if (current == null) {
            current = this.ctx.resources().createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.NEAREST,
                    FilterMode.NEAREST, 1, OptionalDouble.empty());
            this.pointSampler = current;
        }

        return current;
    }

    @Override
    public void record(CommandEncoderBackend encoder, GpuTextureView source, RenderTargetRef backBuffer, int swapWidth, int swapHeight) {
        if (!(encoder instanceof D3D12CommandEncoder d3dEncoder)) {
            throw new IllegalArgumentException("The surface can only blit with the Direct3D 12 command encoder, not " + encoder);
        }

        if (!(source instanceof D3D12TextureView view)) {
            throw new IllegalArgumentException("The surface can only blit a Direct3D 12 texture view, not " + source);
        }

        this.record(d3dEncoder, backBuffer, view, swapWidth, swapHeight);
    }

    /**
     * @param swapWidth  swap chain width (pixels)
     * @param swapHeight swap chain height (pixels)
     */
    void record(D3D12CommandEncoder encoder, RenderTargetRef backBuffer, D3D12TextureView source, int swapWidth, int swapHeight) {
        if (this.closed) {
            throw new IllegalStateException("The present blit is closed");
        }

        // everything that can fail without touching the stream comes first (validate, then mutate)
        D3D12RenderPipeline blit = this.pipeline();
        GpuSampler sampler = this.pointSampler();
        int copyWidth = Math.min(swapWidth, source.getWidth(0));
        int copyHeight = Math.min(swapHeight, source.getHeight(0));

        if (copyWidth < 1 || copyHeight < 1) {
            throw new IllegalArgumentException("Nothing to blit: the source view is " + source.getWidth(0) + "x" + source.getHeight(0));
        }

        this.pushConstants.putInt(0, copyWidth);
        this.pushConstants.putInt(4, copyHeight);
        this.pushConstants.clear();

        PassSpec spec = this.specFor(backBuffer);

        TextureViewAndSampler binding = this.cachedBinding;

        if (binding == null || this.cachedView != source || binding.sampler() != sampler) {
            binding = new TextureViewAndSampler(source, sampler);
            this.cachedBinding = binding;
            this.cachedView = source;
        }

        RenderPassBackend pass = encoder.beginInternalPass(spec);
        Throwable failure = null;

        try {
            pass.setPipeline(blit);
            pass.setUniform(0, binding);
            pass.pushConstants(this.pushConstants);
            pass.draw(3, 1, 0, 0);
        } catch (Throwable t) {
            failure = t;
        } finally {
            if (encoder.passOpen()) {
                try {
                    encoder.submitRenderPass();
                } catch (Throwable t) {
                    if (failure == null) {
                        failure = t;
                    } else {
                        failure.addSuppressed(t);
                    }
                }
            }
        }

        if (failure != null) {
            if (failure instanceof RuntimeException e) {
                throw e;
            }

            if (failure instanceof Error e) {
                throw e;
            }

            throw new IllegalStateException(failure);
        }

        // the pending RT -> PRESENT transition is flushed by submit() right before Close()
        this.ctx.tracker().require(backBuffer.resource(), backBuffer.subresource(), ResourceStates.PRESENT);
    }

    /** The cached pass description of {@code backBuffer}; allocates only the first time a back buffer is seen (after a resize). */
    private PassSpec specFor(RenderTargetRef backBuffer) {
        for (int i = 0; i < this.cachedTargets.length; i++) {
            if (this.cachedTargets[i] == backBuffer) {
                return this.cachedSpecs[i];
            }
        }

        PassSpec spec = PassSpec.backBuffer(backBuffer);
        int slot = this.nextSpecSlot;

        this.cachedTargets[slot] = backBuffer;
        this.cachedSpecs[slot] = spec;
        this.nextSpecSlot = (slot + 1) % this.cachedTargets.length;

        return spec;
    }

    /** Releases the point sampler; the shared blit pipeline stays (it is closed with the device). Idempotent. */
    @Override
    public void close() {
        if (this.closed) {
            return;
        }

        this.closed = true;
        GpuSampler sampler = this.pointSampler;
        this.pointSampler = null;
        this.pipeline = null;
        this.cachedBinding = null;
        this.cachedView = null;
        Arrays.fill(this.cachedSpecs, null);
        Arrays.fill(this.cachedTargets, null);

        if (sampler != null) {
            try {
                sampler.close();
            } catch (RuntimeException e) {
                D3D12Log.warn("Could not release the present blit sampler: {}", e.toString());
            }
        }
    }

    DeviceContext context() {
        return this.ctx;
    }
}
