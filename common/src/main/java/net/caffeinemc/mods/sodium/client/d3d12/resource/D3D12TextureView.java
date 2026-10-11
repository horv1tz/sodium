// Owner: WP1 (device core) - implemented
package net.caffeinemc.mods.sodium.client.d3d12.resource;

import com.mojang.renderpearl.backend.common.BaseGpuTextureView;
import net.caffeinemc.mods.sodium.client.d3d12.core.D3D12Log;
import net.caffeinemc.mods.sodium.client.d3d12.core.DeviceContext;
import org.jspecify.annotations.Nullable;

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
    private final @Nullable ResourceCounters counters;
    private final AtomicBoolean closed = new AtomicBoolean();

    /** SRV staging descriptor, 0 = not created yet. Written under {@code this} lock, read lock-free. */
    private volatile long srvHandle;
    private volatile @Nullable RenderTargetRef colorRef;
    private volatile @Nullable RenderTargetRef depthRef;

    /**
     * Registers the view with the texture (reference count + 1).
     *
     * @throws IllegalStateException if the texture was already destroyed
     */
    D3D12TextureView(DeviceContext ctx, D3D12Texture texture, int baseMipLevel, int mipLevels,
                     @Nullable ResourceCounters counters) {
        super(texture, baseMipLevel, mipLevels);

        texture.viewAdded();

        this.ctx = ctx;
        this.d3dTexture = texture;
        this.counters = counters;

        if (counters != null) {
            counters.viewCreated();
        }
    }

    public D3D12Texture d3dTexture() {
        return this.d3dTexture;
    }

    /**
     * @return CPU handle of the SRV staging descriptor, created on first call (render thread)
     * @throws IllegalStateException if the view is closed (its descriptor is gone)
     */
    public long srv() {
        long handle = this.srvHandle;

        if (handle != 0L) {
            return handle;
        }

        synchronized (this) {
            if (this.closed.get()) {
                throw new IllegalStateException("Texture view of '" + this.d3dTexture.getLabel() + "' is closed");
            }

            handle = this.srvHandle;

            if (handle == 0L) {
                handle = this.ctx.resources().createSrv(this.d3dTexture, this.baseMipLevel(), this.mipLevels());
                this.srvHandle = handle;
            }

            return handle;
        }
    }

    /** @return true if the SRV is a cube SRV, i.e. the texture is cube-compatible */
    public boolean isCube() {
        return this.d3dTexture.isCube();
    }

    /**
     * @return the colour attachment description of this view (mip {@code baseMipLevel}, layer 0)
     * @throws IllegalStateException for depth textures and for closed views
     */
    public RenderTargetRef colorTarget() {
        RenderTargetRef ref = this.colorRef;

        if (ref != null && !this.closed.get()) {
            return ref;
        }

        D3D12Texture texture = this.d3dTexture;

        if (texture.isDepth()) {
            throw new IllegalStateException("Texture '" + texture.getLabel() + "' is a depth texture and cannot be a colour attachment");
        }

        this.checkOpen();

        int mip = this.baseMipLevel();

        ref = new RenderTargetRef(texture.tracked(), texture.subresource(mip, 0), texture.rtv(mip), texture.mipWidth(mip),
                texture.mipHeight(mip), texture.formatInfo().rtvFormat(), mip);
        this.colorRef = ref;

        return ref;
    }

    /**
     * @return the depth attachment description of this view
     * @throws IllegalStateException for colour textures and for closed views
     */
    public RenderTargetRef depthTarget() {
        RenderTargetRef ref = this.depthRef;

        if (ref != null && !this.closed.get()) {
            return ref;
        }

        D3D12Texture texture = this.d3dTexture;

        if (!texture.isDepth()) {
            throw new IllegalStateException("Texture '" + texture.getLabel() + "' is a colour texture and cannot be a depth attachment");
        }

        this.checkOpen();

        int mip = this.baseMipLevel();

        ref = new RenderTargetRef(texture.tracked(), texture.subresource(mip, 0), texture.dsv(mip), texture.mipWidth(mip),
                texture.mipHeight(mip), texture.formatInfo().dsvFormat(), mip);
        this.depthRef = ref;

        return ref;
    }

    private void checkOpen() {
        if (this.closed.get()) {
            throw new IllegalStateException("Texture view of '" + this.d3dTexture.getLabel() + "' is closed");
        }
    }

    /** @return the view is not closed and the device is alive */
    public boolean isUsable() {
        return !this.closed.get() && !this.ctx.isClosed();
    }

    @Override
    public boolean isClosed() {
        return this.closed.get();
    }

    /**
     * Idempotent and thread-safe: {@code closed.compareAndSet(false, true)}, then defer the SRV free and
     * {@code texture.viewRemoved()} once. Never throws.
     */
    @Override
    public void close() {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }

        long handle;

        synchronized (this) {
            handle = this.srvHandle;
            this.srvHandle = 0L;
        }

        this.colorRef = null;
        this.depthRef = null;

        try {
            if (handle != 0L) {
                this.ctx.defer(() -> this.freeSrv(handle));
            }
        } catch (RuntimeException e) {
            D3D12Log.error("Failed to schedule the release of a texture view descriptor", e);
        }

        try {
            this.d3dTexture.viewRemoved();
        } catch (RuntimeException e) {
            D3D12Log.error("Failed to drop a texture view reference of '{}'", this.d3dTexture.getLabel(), e);
        }

        if (this.counters != null) {
            this.counters.viewClosed();
        }
    }

    private void freeSrv(long handle) {
        try {
            this.ctx.descriptors().cbvSrvUav().free(handle);
        } catch (RuntimeException e) {
            // the allocator may already be closed when a view is closed after the device
            D3D12Log.debug("Failed to free an SRV descriptor of '{}': {}", this.d3dTexture.getLabel(), e.toString());
        }
    }
}
