// Owner: WP2 (commands + transient memory) - implemented
package net.caffeinemc.mods.sodium.client.d3d12.command;

import com.mojang.renderpearl.api.textures.GpuTexture;
import net.caffeinemc.mods.sodium.client.d3d12.core.DeviceContext;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Enums;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.Rect;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12GraphicsCommandList;
import net.caffeinemc.mods.sodium.client.d3d12.resource.D3D12Texture;
import net.caffeinemc.mods.sodium.client.d3d12.resource.RenderTargetRef;
import net.caffeinemc.mods.sodium.client.d3d12.state.ResourceStateTracker;
import net.caffeinemc.mods.sodium.client.d3d12.state.ResourceStates;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * The four encoder clears plus the in-pass load-op clears (spec 01 section 4.5, spec 04 section 10.5).
 *
 * <p>Every method: validate -> {@code tracker.require(.., RT | DW)} -> {@code tracker.flush(list)} -> record
 * ({@code ClearRenderTargetView} / {@code ClearDepthStencilView(CLEAR_FLAG_DEPTH)}); stencil is never touched; colour
 * values are the raw floats (not clamped); write masks and scissors do not apply to D3D12 clears.
 * <ul>
 *     <li>{@code clearColorTexture}, {@code clearDepthTexture} and the whole-texture combination clear <b>every mip</b>
 *     (of both textures) of layer 0; no rects. (The GL "depth mip 0 only" quirk is deliberately not copied.)</li>
 *     <li>The region form clears the single rect {@code {x, y, x + w, y + h}} of mip {@code mipLevel} of colour <em>and</em>
 *     depth, each rect clipped to <em>that texture's own</em> mip extent ({@code max(1, size >> mip)}); an empty
 *     clipped rect skips that texture. Rows are counted from texture row 0 (no flip).</li>
 * </ul>
 * RTV/DSV descriptors per (texture, mip) come from {@code D3D12Texture.rtv/dsv}; they are created (and cached) during
 * validation, before the first state request, so that a failing descriptor allocation leaves the tracker untouched.
 *
 * <p>The scratch structs for the colour and the rectangle are allocated once per engine; nothing is allocated per call.
 *
 * <p>Threading: render thread.
 */
public final class ClearEngine {
    private final DeviceContext ctx;
    private final @Nullable CommandRecorder recorder;
    private final MemorySegment color;
    private final MemorySegment rect;

    /** Resolves the recorder lazily through {@code ctx.encoder()} (usable only after {@code ctx.attachEncoder}). */
    public ClearEngine(DeviceContext ctx) {
        this(ctx, null);
    }

    /** Explicit recorder; {@code ctx.encoder()} is never touched (the encoder is not attached while it constructs this). */
    public ClearEngine(DeviceContext ctx, @Nullable CommandRecorder recorder) {
        this.ctx = ctx;
        this.recorder = recorder;

        Arena arena = Arena.ofAuto();

        this.color = arena.allocate(16, 16);
        this.rect = Rect.allocate(arena);
    }

    public void clearColorTexture(GpuTexture colorTexture, Vector4fc clearColor) {
        D3D12Texture texture = colorAttachment(colorTexture);

        this.prepareColor(texture);
        this.clearAllMips(texture, clearColor);
    }

    public void clearDepthTexture(GpuTexture depthTexture, double clearDepth) {
        D3D12Texture texture = depthAttachment(depthTexture);

        this.prepareDepth(texture);
        this.clearAllMipsDepth(texture, (float) clearDepth);
    }

    public void clearColorAndDepthTextures(GpuTexture colorTexture, Vector4fc clearColor, GpuTexture depthTexture, double clearDepth) {
        D3D12Texture colorTex = colorAttachment(colorTexture);
        D3D12Texture depthTex = depthAttachment(depthTexture);

        this.prepareColor(colorTex);
        this.prepareDepth(depthTex);

        ResourceStateTracker tracker = this.ctx.tracker();

        // all the state requests of both textures, then one flush, then the clears
        tracker.requireRange(colorTex.tracked(), 0, colorTex.getMipLevels(), ResourceStates.RT);
        tracker.requireRange(depthTex.tracked(), 0, depthTex.getMipLevels(), ResourceStates.DW);

        D3D12GraphicsCommandList list = this.list();

        tracker.flush(list);
        this.recordColorMips(list, colorTex, clearColor);
        this.recordDepthMips(list, depthTex, (float) clearDepth);
    }

    public void clearColorAndDepthTextures(GpuTexture colorTexture, Vector4fc clearColor, GpuTexture depthTexture,
                                           double clearDepth, int regionX, int regionY, int regionWidth,
                                           int regionHeight, int mipLevel) {
        D3D12Texture colorTex = colorAttachment(colorTexture);
        D3D12Texture depthTex = depthAttachment(depthTexture);

        if (mipLevel < 0 || mipLevel >= colorTex.getMipLevels() || mipLevel >= depthTex.getMipLevels()) {
            throw new IllegalArgumentException("Invalid mipLevel " + mipLevel + " for textures with " + colorTex.getMipLevels() + " and "
                    + depthTex.getMipLevels() + " mips");
        }

        if (regionX < 0 || regionY < 0 || regionWidth <= 0 || regionHeight <= 0) {
            throw new IllegalArgumentException("Invalid clear region " + regionWidth + "x" + regionHeight + " at (" + regionX + ", " + regionY + ")");
        }

        // each texture is clipped to its own mip extent; an empty rect skips that texture
        int colorW = clipped(regionX, regionWidth, colorTex.mipWidth(mipLevel));
        int colorH = clipped(regionY, regionHeight, colorTex.mipHeight(mipLevel));
        int depthW = clipped(regionX, regionWidth, depthTex.mipWidth(mipLevel));
        int depthH = clipped(regionY, regionHeight, depthTex.mipHeight(mipLevel));
        boolean doColor = colorW > 0 && colorH > 0;
        boolean doDepth = depthW > 0 && depthH > 0;

        if (doColor) {
            colorTex.rtv(mipLevel);
        }

        if (doDepth) {
            depthTex.dsv(mipLevel);
        }

        ResourceStateTracker tracker = this.ctx.tracker();

        if (doColor) {
            tracker.require(colorTex.tracked(), colorTex.subresource(mipLevel, 0), ResourceStates.RT);
        }

        if (doDepth) {
            tracker.require(depthTex.tracked(), depthTex.subresource(mipLevel, 0), ResourceStates.DW);
        }

        D3D12GraphicsCommandList list = this.list();

        tracker.flush(list);

        if (doColor) {
            this.setColor(clearColor);
            this.setRect(regionX, regionY, regionX + colorW, regionY + colorH);
            list.clearRenderTargetView(colorTex.rtv(mipLevel), this.color, 1, this.rect);
        }

        if (doDepth) {
            this.setRect(regionX, regionY, regionX + depthW, regionY + depthH);
            list.clearDepthStencilView(depthTex.dsv(mipLevel), Enums.ClearFlags.DEPTH, (float) clearDepth, 0, 1, this.rect);
        }
    }

    /**
     * Load-op clear of one colour attachment at pass begin, also used by the present blit.
     *
     * @param target the attachment ({@code tracker.require(RT)} + flush are done by the caller or here: this method
     *               itself requires {@code RT} and flushes, so callers need not)
     * @param w      {@code <= 0} clears the whole attachment; otherwise the rect {@code {x, y, x + w, y + h}} (clipped to
     *               the attachment; nothing happens if the clipped rect is empty)
     */
    public void clearRtv(RenderTargetRef target, float r, float g, float b, float a, int x, int y, int w, int h) {
        boolean whole = w <= 0;

        if (!whole && !this.setClippedRect(x, y, w, h, target.width(), target.height())) {
            return;
        }

        ResourceStateTracker tracker = this.ctx.tracker();
        D3D12GraphicsCommandList list = this.list();

        tracker.require(target.resource(), target.subresource(), ResourceStates.RT);
        tracker.flush(list);

        this.color.set(ValueLayout.JAVA_FLOAT, 0, r);
        this.color.set(ValueLayout.JAVA_FLOAT, 4, g);
        this.color.set(ValueLayout.JAVA_FLOAT, 8, b);
        this.color.set(ValueLayout.JAVA_FLOAT, 12, a);

        list.clearRenderTargetView(target.cpuHandle(), this.color, whole ? 0 : 1, whole ? null : this.rect);
    }

    /** Depth counterpart of {@link #clearRtv}; requires {@code DW}. */
    public void clearDsv(RenderTargetRef target, float depth, int x, int y, int w, int h) {
        boolean whole = w <= 0;

        if (!whole && !this.setClippedRect(x, y, w, h, target.width(), target.height())) {
            return;
        }

        ResourceStateTracker tracker = this.ctx.tracker();
        D3D12GraphicsCommandList list = this.list();

        tracker.require(target.resource(), target.subresource(), ResourceStates.DW);
        tracker.flush(list);

        list.clearDepthStencilView(target.cpuHandle(), Enums.ClearFlags.DEPTH, depth, 0, whole ? 0 : 1, whole ? null : this.rect);
    }

    // ------------------------------------------------------------------------------------------------------------

    private void clearAllMips(D3D12Texture texture, Vector4fc clearColor) {
        ResourceStateTracker tracker = this.ctx.tracker();

        tracker.requireRange(texture.tracked(), 0, texture.getMipLevels(), ResourceStates.RT);

        D3D12GraphicsCommandList list = this.list();

        tracker.flush(list);
        this.recordColorMips(list, texture, clearColor);
    }

    private void clearAllMipsDepth(D3D12Texture texture, float depth) {
        ResourceStateTracker tracker = this.ctx.tracker();

        tracker.requireRange(texture.tracked(), 0, texture.getMipLevels(), ResourceStates.DW);

        D3D12GraphicsCommandList list = this.list();

        tracker.flush(list);
        this.recordDepthMips(list, texture, depth);
    }

    private void recordColorMips(D3D12GraphicsCommandList list, D3D12Texture texture, Vector4fc clearColor) {
        this.setColor(clearColor);

        for (int mip = 0; mip < texture.getMipLevels(); mip++) {
            list.clearRenderTargetView(texture.rtv(mip), this.color, 0, null);
        }
    }

    private void recordDepthMips(D3D12GraphicsCommandList list, D3D12Texture texture, float depth) {
        for (int mip = 0; mip < texture.getMipLevels(); mip++) {
            list.clearDepthStencilView(texture.dsv(mip), Enums.ClearFlags.DEPTH, depth, 0, 0, null);
        }
    }

    /** Creates (and caches) the RTV of every mip: a failing allocation must happen before any state request. */
    private void prepareColor(D3D12Texture texture) {
        for (int mip = 0; mip < texture.getMipLevels(); mip++) {
            texture.rtv(mip);
        }
    }

    private void prepareDepth(D3D12Texture texture) {
        for (int mip = 0; mip < texture.getMipLevels(); mip++) {
            texture.dsv(mip);
        }
    }

    private void setColor(Vector4fc value) {
        this.color.set(ValueLayout.JAVA_FLOAT, 0, value.x());
        this.color.set(ValueLayout.JAVA_FLOAT, 4, value.y());
        this.color.set(ValueLayout.JAVA_FLOAT, 8, value.z());
        this.color.set(ValueLayout.JAVA_FLOAT, 12, value.w());
    }

    private void setRect(int left, int top, int right, int bottom) {
        Rect.setLeft(this.rect, left);
        Rect.setTop(this.rect, top);
        Rect.setRight(this.rect, right);
        Rect.setBottom(this.rect, bottom);
    }

    /** Fills the rect scratch with {@code {x, y, x + w, y + h}} clipped to {@code width x height}; false if that is empty. */
    private boolean setClippedRect(int x, int y, int w, int h, int width, int height) {
        long left = Math.max(0, x);
        long top = Math.max(0, y);
        long right = Math.min((long) x + w, width);
        long bottom = Math.min((long) y + h, height);

        if (left >= right || top >= bottom) {
            return false;
        }

        this.setRect((int) left, (int) top, (int) right, (int) bottom);

        return true;
    }

    /** @return the number of texels of {@code [start, start + length)} that lie inside {@code [0, extent)} */
    private static int clipped(int start, int length, int extent) {
        long end = Math.min((long) start + length, extent);

        return (int) Math.max(0L, end - start);
    }

    private static D3D12Texture colorAttachment(GpuTexture texture) {
        D3D12Texture d3d = attachment(texture);

        if (d3d.isDepth()) {
            throw new IllegalArgumentException("Not a color texture: " + d3d.getLabel());
        }

        return d3d;
    }

    private static D3D12Texture depthAttachment(GpuTexture texture) {
        D3D12Texture d3d = attachment(texture);

        if (!d3d.isDepth()) {
            throw new IllegalArgumentException("Not a depth texture: " + d3d.getLabel());
        }

        return d3d;
    }

    private static D3D12Texture attachment(GpuTexture texture) {
        if (!(texture instanceof D3D12Texture d3d)) {
            throw new IllegalArgumentException("The texture was not created by the Direct3D 12 backend: " + texture);
        }

        if (!d3d.isUsable()) {
            throw new IllegalStateException("The texture is closed: " + d3d.getLabel());
        }

        if ((d3d.usage() & GpuTexture.USAGE_RENDER_ATTACHMENT) == 0) {
            throw new IllegalArgumentException("Texture needs USAGE_RENDER_ATTACHMENT to be cleared: " + d3d.getLabel());
        }

        if (d3d.getDepthOrLayers() != 1) {
            throw new IllegalArgumentException("Only single-layer textures can be cleared: " + d3d.getLabel());
        }

        return d3d;
    }

    private D3D12GraphicsCommandList list() {
        CommandRecorder explicit = this.recorder;

        return (explicit != null ? explicit : this.ctx.encoder().recorder()).list();
    }

    DeviceContext context() {
        return this.ctx;
    }
}
