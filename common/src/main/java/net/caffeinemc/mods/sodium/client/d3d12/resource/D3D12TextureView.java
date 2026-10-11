// Owner: WP1 (device core)
package net.caffeinemc.mods.sodium.client.d3d12.resource;

import com.mojang.renderpearl.backend.common.BaseGpuTextureView;
import net.caffeinemc.mods.sodium.client.d3d12.core.DeviceContext;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A view of a {@link D3D12Texture}: it plays up to three roles - shader resource, colour/depth attachment, blit source.
 *
 * <ul>
 *     <li>{@link #srv()}: a CPU-only SRV descriptor created lazily for {@code (baseMip, mipLevels)}: {@code TEXTURE2D}
 *     or, for cube-compatible textures, {@code TEXTURECUBE} over all 6 faces. Depth textures are viewed through the
 *     typeless-compatible float format ({@code Formats.srvFormat}). The view owns the descriptor and frees it through the
 *     deferral queue on {@code close()}.</li>
 *     <li>{@link #colorTarget()} / {@link #depthTarget()}: the attachment of mip {@code baseMipLevel}, layer 0
 *     (the frontend defines an attachment as "mip {@code baseMipLevel} of the texture").</li>
 * </ul>
 *
 * <p>{@code isClosed()} reports the view's own state; a view of a closed texture remains a valid attachment/SRV.
 * {@code close()} is idempotent (compare-and-set, any thread), drops one reference of the texture and defers the
 * descriptor release.
 */
public final class D3D12TextureView extends BaseGpuTextureView {
    private final DeviceContext ctx;
    private final D3D12Texture d3dTexture;
    private final AtomicBoolean closed = new AtomicBoolean();

    D3D12TextureView(DeviceContext ctx, D3D12Texture texture, int baseMipLevel, int mipLevels) {
        super(texture, baseMipLevel, mipLevels);

        this.ctx = ctx;
        this.d3dTexture = texture;
    }

    public D3D12Texture d3dTexture() {
        return this.d3dTexture;
    }

    /** @return CPU handle of the SRV staging descriptor, created on first call (render thread) */
    public long srv() {
        throw new UnsupportedOperationException("WP1: lazy SRV (2D or cube) in the CBV/SRV/UAV staging allocator");
    }

    /** @return true if the SRV is a cube SRV, i.e. the texture is cube-compatible */
    public boolean isCube() {
        return this.d3dTexture.isCube();
    }

    /**
     * @return the colour attachment description of this view (mip {@code baseMipLevel}, layer 0)
     * @throws IllegalStateException for depth textures
     */
    public RenderTargetRef colorTarget() {
        throw new UnsupportedOperationException("WP1: RenderTargetRef(tracked, subresource, rtv(baseMip), mip extent, format)");
    }

    /**
     * @return the depth attachment description of this view
     * @throws IllegalStateException for colour textures
     */
    public RenderTargetRef depthTarget() {
        throw new UnsupportedOperationException("WP1: RenderTargetRef(tracked, subresource, dsv(baseMip), mip extent, format)");
    }

    /** @return the view is not closed and the device is alive */
    public boolean isUsable() {
        return !this.closed.get() && !this.ctx.isClosed();
    }

    @Override
    public boolean isClosed() {
        return this.closed.get();
    }

    /** Idempotent and thread-safe: {@code closed.compareAndSet(false, true)}, then defer the SRV free and {@code texture.viewRemoved()} once. */
    @Override
    public void close() {
        throw new UnsupportedOperationException("WP1: if (!closed.compareAndSet(false, true)) return; defer SRV free; texture.viewRemoved()");
    }
}
