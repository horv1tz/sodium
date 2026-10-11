// Owner: WP1 (device core) - implemented
package net.caffeinemc.mods.sodium.client.d3d12.resource;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.backend.common.BaseGpuTexture;
import com.mojang.renderpearl.api.textures.GpuTexture;
import net.caffeinemc.mods.sodium.client.d3d12.core.D3D12Log;
import net.caffeinemc.mods.sodium.client.d3d12.core.DeviceContext;
import net.caffeinemc.mods.sodium.client.d3d12.core.FormatInfo;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12Resource;
import net.caffeinemc.mods.sodium.client.d3d12.state.TrackedResource;
import org.jspecify.annotations.Nullable;

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
    private final @Nullable ResourceCounters counters;

    /** Lazily created RTV / DSV staging descriptors, one slot per mip (0 = not created). Render thread only. */
    private long @Nullable [] rtvHandles;
    private long @Nullable [] dsvHandles;

    /** Without leak counters; kept for the skeleton self tests and for tests that build textures around no native resource. */
    D3D12Texture(DeviceContext ctx, String label, int usage, GpuFormat format, int width, int height, int depthOrLayers,
                 int mipLevels, FormatInfo formatInfo, @Nullable D3D12Resource resource, TrackedResource tracked) {
        this(ctx, label, usage, format, width, height, depthOrLayers, mipLevels, formatInfo, resource, tracked, null);
    }

    D3D12Texture(DeviceContext ctx, String label, int usage, GpuFormat format, int width, int height, int depthOrLayers,
                 int mipLevels, FormatInfo formatInfo, @Nullable D3D12Resource resource, TrackedResource tracked,
                 @Nullable ResourceCounters counters) {
        super(usage, label, format, width, height, depthOrLayers, mipLevels);

        this.ctx = ctx;
        this.formatInfo = formatInfo;
        this.resource = resource;
        this.tracked = tracked;
        this.counters = counters;
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

    /**
     * @return CPU handle of the RTV of {@code mip} (layer 0), created on first use and cached until the texture is
     * destroyed; colour textures created with {@code USAGE_RENDER_ATTACHMENT} only
     * @throws IllegalStateException    for depth textures, textures without {@code RENDER_ATTACHMENT} and destroyed textures
     * @throws IllegalArgumentException for a mip outside the chain
     */
    public long rtv(int mip) {
        if (this.formatInfo.depth()) {
            throw new IllegalStateException("Texture '" + this.getLabel() + "' is a depth texture and has no render target view");
        }

        this.checkTargetUsage(mip);

        long[] cache = this.rtvHandles;

        if (cache == null) {
            this.rtvHandles = cache = new long[this.getMipLevels()];
        }

        long handle = cache[mip];

        if (handle == 0L) {
            this.checkAlive();
            cache[mip] = handle = this.ctx.resources().createRtv(this, mip);
        }

        return handle;
    }

    /**
     * @return CPU handle of the DSV of {@code mip} (layer 0), created on first use and cached until the texture is
     * destroyed; depth textures created with {@code USAGE_RENDER_ATTACHMENT} only
     * @throws IllegalStateException    for colour textures, textures without {@code RENDER_ATTACHMENT} and destroyed textures
     * @throws IllegalArgumentException for a mip outside the chain
     */
    public long dsv(int mip) {
        if (!this.formatInfo.depth()) {
            throw new IllegalStateException("Texture '" + this.getLabel() + "' is a colour texture and has no depth stencil view");
        }

        this.checkTargetUsage(mip);

        long[] cache = this.dsvHandles;

        if (cache == null) {
            this.dsvHandles = cache = new long[this.getMipLevels()];
        }

        long handle = cache[mip];

        if (handle == 0L) {
            this.checkAlive();
            cache[mip] = handle = this.ctx.resources().createDsv(this, mip);
        }

        return handle;
    }

    private void checkTargetUsage(int mip) {
        if (mip < 0 || mip >= this.getMipLevels()) {
            throw new IllegalArgumentException("Mip level " + mip + " is outside [0, " + this.getMipLevels() + ") of texture '" + this.getLabel() + "'");
        }

        if ((this.usage() & GpuTexture.USAGE_RENDER_ATTACHMENT) == 0) {
            throw new IllegalStateException("Texture '" + this.getLabel() + "' was not created with USAGE_RENDER_ATTACHMENT");
        }
    }

    private void checkAlive() {
        if (this.refs.get() == 0) {
            throw new IllegalStateException("Texture was already destroyed: " + this.getLabel());
        }
    }

    /** @return the current reference count (1 for the texture itself while open, plus one per open view); for tests */
    int referenceCount() {
        return this.refs.get();
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
        try {
            this.freeHandles(this.rtvHandles, true);
            this.freeHandles(this.dsvHandles, false);
        } finally {
            this.rtvHandles = null;
            this.dsvHandles = null;
        }

        if (this.resource != null) {
            try {
                this.resource.release();
            } catch (RuntimeException e) {
                D3D12Log.warn("Failed to release texture '{}': {}", this.getLabel(), e.toString());
            }
        }

        if (this.counters != null) {
            this.counters.textureDestroyed();
        }
    }

    private void freeHandles(long @Nullable [] handles, boolean color) {
        if (handles == null) {
            return;
        }

        for (long handle : handles) {
            if (handle == 0L) {
                continue;
            }

            try {
                (color ? this.ctx.descriptors().rtv() : this.ctx.descriptors().dsv()).free(handle);
            } catch (RuntimeException e) {
                // the allocator may already be closed when a texture is closed after the device
                D3D12Log.debug("Failed to free a target view descriptor of texture '{}': {}", this.getLabel(), e.toString());
            }
        }
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
