// Owner: WP1 (device core)
package net.caffeinemc.mods.sodium.client.d3d12.resource;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.backend.common.BaseGpuTexture;
import net.caffeinemc.mods.sodium.client.d3d12.core.DeviceContext;
import net.caffeinemc.mods.sodium.client.d3d12.core.FormatInfo;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12Resource;
import net.caffeinemc.mods.sodium.client.d3d12.state.TrackedResource;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A committed 2D texture (1 layer) or cube map (6 layers) in the DEFAULT heap.
 *
 * <p>Creation ({@code ResourceFactory.createTexture}): {@code width/height} outside {@code [1, 16384]} ->
 * {@code GpuOutOfMemoryException} (so {@code MainTarget} falls back to 854x480); unmappable format ->
 * {@code IllegalArgumentException("<format> format cannot be used to create textures")}. {@code TEXTURE2D},
 * {@code DepthOrArraySize = depthOrLayers}, {@code MipLevels} as given, {@code SampleDesc {1,0}},
 * {@code Layout UNKNOWN}, flags {@code ALLOW_RENDER_TARGET} (colour + {@code RENDER_ATTACHMENT}) or
 * {@code ALLOW_DEPTH_STENCIL} (depth + {@code RENDER_ATTACHMENT}), <b>never</b> {@code DENY_SHADER_RESOURCE}; initial
 * state {@code COMMON}; an optimised clear value (colour 0,0,0,0 / depth 0.0) for {@code RENDER_ATTACHMENT} textures
 * unless {@code DeviceOptions.optimizedClearValues} is off. Depth is always typeless (see {@code Formats}); a depth
 * texture with {@code RENDER_ATTACHMENT} must be {@code D32_FLOAT} ({@code Formats.requireDepthAttachment}; {@code D16_UNORM}
 * is legal for sampled/copied-only textures). The label
 * is never null (the base class requires it): {@code "texture#N"} when the frontend passes none.
 *
 * <p>Mip extents: {@code BaseGpuTexture.getWidth/getHeight} return raw shifts that can reach 0 for the short side of a
 * non-square texture; this backend uses {@link #mipWidth}/{@link #mipHeight} ({@code max(1, size >> mip)}) for all
 * footprint, box, rect and viewport maths. Subresource index = {@code mip + layer * mipLevels}.
 *
 * <p>Lifetime: reference counted exactly like the Vulkan backend (1 for the texture + 1 per open view); the native
 * resource is destroyed (deferred) when the count reaches 0, so a view keeps the texture alive after
 * {@code texture.close()}. RTV/DSV descriptors per mip are created lazily, cached, and freed by the deferred destroy.
 *
 * <p>Threading: render thread (descriptor creation is lazy and unsynchronised); {@code close()} may come from any thread
 * while the render thread opens or closes views, so the protocol is lock-free and exact: {@code refs} is an
 * {@link AtomicInteger} (starts at 1 = the texture's own reference), {@code closed} an {@link AtomicBoolean} flipped by
 * {@code compareAndSet} (only the winner drops the texture's reference), {@link #viewAdded()} increments only while
 * {@code refs > 0}, and whoever performs the decrement that reaches 0 enqueues <b>exactly one</b> deferred
 * {@link #destroy()} (additionally guarded by {@code destroyed}). A plain int or volatile boolean here would let a worker's
 * {@code texture.close()} and the render thread's last {@code view.close()} both see "unreferenced" and double-release the
 * COM resource, or both see "still referenced" and leak it.
 */
public final class D3D12Texture extends BaseGpuTexture {
    private final DeviceContext ctx;
    private final FormatInfo formatInfo;
    private final D3D12Resource resource;
    private final TrackedResource tracked;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger refs = new AtomicInteger(1);
    private final AtomicBoolean destroyed = new AtomicBoolean();

    D3D12Texture(DeviceContext ctx, String label, int usage, GpuFormat format, int width, int height, int depthOrLayers,
                 int mipLevels, FormatInfo formatInfo, D3D12Resource resource, TrackedResource tracked) {
        super(usage, label, format, width, height, depthOrLayers, mipLevels);

        this.ctx = ctx;
        this.formatInfo = formatInfo;
        this.resource = resource;
        this.tracked = tracked;
    }

    public D3D12Resource resource() {
        return this.resource;
    }

    public TrackedResource tracked() {
        return this.tracked;
    }

    public FormatInfo formatInfo() {
        return this.formatInfo;
    }

    public boolean isDepth() {
        return this.formatInfo.depth();
    }

    /** @return true for the 6-layer cube-compatible shape (sampled through a cube SRV) */
    public boolean isCube() {
        return this.getDepthOrLayers() == 6 && (this.usage() & USAGE_CUBEMAP_COMPATIBLE) != 0;
    }

    /** @return {@code max(1, width >> mip)} */
    public int mipWidth(int mip) {
        return Math.max(1, this.getWidth(0) >> mip);
    }

    /** @return {@code max(1, height >> mip)} */
    public int mipHeight(int mip) {
        return Math.max(1, this.getHeight(0) >> mip);
    }

    /** @return the D3D12 subresource index of {@code (mip, layer)} */
    public int subresource(int mip, int layer) {
        return mip + layer * this.getMipLevels();
    }

    /** @return CPU handle of the RTV of {@code mip} (layer 0), created on first use; colour textures only */
    public long rtv(int mip) {
        throw new UnsupportedOperationException("WP1: lazily create + cache one RTV per mip in the RTV staging allocator");
    }

    /** @return CPU handle of the DSV of {@code mip} (layer 0), created on first use; depth textures only */
    public long dsv(int mip) {
        throw new UnsupportedOperationException("WP1: lazily create + cache one DSV per mip in the DSV staging allocator");
    }

    /**
     * Called by {@link D3D12TextureView}'s constructor.
     *
     * @throws IllegalStateException if the texture was already destroyed (the last reference is gone)
     */
    void viewAdded() {
        int count;

        do {
            count = this.refs.get();

            if (count == 0) {
                throw new IllegalStateException("Texture was already destroyed: " + this.getLabel());
            }
        } while (!this.refs.compareAndSet(count, count + 1));
    }

    /** Called by {@link D3D12TextureView#close()} (once per view); destroys the resource (deferred) at 0 references. */
    void viewRemoved() {
        this.release();
    }

    private void release() {
        if (this.refs.decrementAndGet() == 0 && this.destroyed.compareAndSet(false, true)) {
            this.ctx.defer(this::destroy);
        }
    }

    /**
     * Frees the cached RTV/DSV staging descriptors and releases the native resource. Runs from the deferral queue (or
     * inline after the GPU idle wait), exactly once.
     */
    private void destroy() {
        throw new UnsupportedOperationException("WP1: free the RTV/DSV staging descriptors, release the resource");
    }

    /** @return not closed and device alive (a texture with open views stays <em>usable as an attachment/SRV</em> through them) */
    public boolean isUsable() {
        return !this.closed.get() && !this.ctx.isClosed();
    }

    DeviceContext context() {
        return this.ctx;
    }

    @Override
    public boolean isClosed() {
        return this.closed.get();
    }

    /** Idempotent and thread-safe: only the call that wins the {@code closed} CAS drops the texture's own reference. */
    @Override
    public void close() {
        if (this.closed.compareAndSet(false, true)) {
            this.release();
        }
    }
}
