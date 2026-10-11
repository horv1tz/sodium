// Owner: WP2 (commands + transient memory)
package net.caffeinemc.mods.sodium.client.d3d12.command;

import com.mojang.renderpearl.api.textures.GpuTexture;
import net.caffeinemc.mods.sodium.client.d3d12.core.DeviceContext;
import net.caffeinemc.mods.sodium.client.d3d12.resource.RenderTargetRef;
import org.joml.Vector4fc;

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
 * RTV/DSV descriptors per (texture, mip) come from {@code D3D12Texture.rtv/dsv}.
 *
 * <p>Threading: render thread.
 */
public final class ClearEngine {
    private final DeviceContext ctx;

    public ClearEngine(DeviceContext ctx) {
        this.ctx = ctx;
    }

    public void clearColorTexture(GpuTexture colorTexture, Vector4fc clearColor) {
        throw new UnsupportedOperationException("WP2: RT for all mips, ClearRenderTargetView per mip");
    }

    public void clearDepthTexture(GpuTexture depthTexture, double clearDepth) {
        throw new UnsupportedOperationException("WP2: DW for all mips, ClearDepthStencilView(DEPTH) per mip");
    }

    public void clearColorAndDepthTextures(GpuTexture colorTexture, Vector4fc clearColor, GpuTexture depthTexture, double clearDepth) {
        throw new UnsupportedOperationException("WP2");
    }

    public void clearColorAndDepthTextures(GpuTexture colorTexture, Vector4fc clearColor, GpuTexture depthTexture,
                                           double clearDepth, int regionX, int regionY, int regionWidth,
                                           int regionHeight, int mipLevel) {
        throw new UnsupportedOperationException("WP2: one clipped D3D12_RECT per texture");
    }

    /**
     * Load-op clear of one colour attachment at pass begin, also used by the present blit.
     *
     * @param target the attachment ({@code tracker.require(RT)} + flush are done by the caller or here: this method
     *               itself requires {@code RT} and flushes, so callers need not)
     * @param w      {@code <= 0} clears the whole attachment; otherwise the rect {@code {x, y, x + w, y + h}}
     */
    public void clearRtv(RenderTargetRef target, float r, float g, float b, float a, int x, int y, int w, int h) {
        throw new UnsupportedOperationException("WP2");
    }

    /** Depth counterpart of {@link #clearRtv}; requires {@code DW}. */
    public void clearDsv(RenderTargetRef target, float depth, int x, int y, int w, int h) {
        throw new UnsupportedOperationException("WP2");
    }

    DeviceContext context() {
        return this.ctx;
    }
}
