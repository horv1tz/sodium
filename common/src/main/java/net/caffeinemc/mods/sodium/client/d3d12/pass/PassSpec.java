// Owner: WP3 (pipelines + render pass) - fully implemented value type
package net.caffeinemc.mods.sodium.client.d3d12.pass;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.caffeinemc.mods.sodium.client.d3d12.core.Formats;
import net.caffeinemc.mods.sodium.client.d3d12.resource.D3D12TextureView;
import net.caffeinemc.mods.sodium.client.d3d12.resource.RenderTargetRef;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * An immutable, backend-typed description of a render pass: what {@code PassManager.begin} needs, independent of the
 * frontend's {@link RenderPassDescriptor}. Built by {@link #from} for game passes and by {@link #backBuffer} for the
 * present blit (which has no texture view).
 *
 * <p>Load/store semantics (spec 01 section 11.1): a clear value present = CLEAR inside the render area only, empty =
 * LOAD, store is always preserve (nothing to record).
 */
public final class PassSpec {
    /**
     * A colour attachment.
     *
     * @param target the attachment
     * @param clear  {@code {r, g, b, a}} to clear to inside the render area, or null to keep the contents
     */
    public record Color(RenderTargetRef target, float @Nullable [] clear) {
    }

    /**
     * The depth attachment.
     *
     * @param clear true to clear to {@code clearValue}, false to keep the contents
     */
    public record Depth(RenderTargetRef target, boolean clear, float clearValue) {
    }

    private final String label;
    private final List<@Nullable Color> colors;
    private final @Nullable Depth depth;
    private final int areaX;
    private final int areaY;
    private final int areaWidth;
    private final int areaHeight;
    private final int viewportWidth;
    private final int viewportHeight;

    private PassSpec(String label, List<@Nullable Color> colors, @Nullable Depth depth, int areaX, int areaY,
                     int areaWidth, int areaHeight, int viewportWidth, int viewportHeight) {
        this.label = label;
        // NOT List.copyOf: it throws NullPointerException on the null entries that mark unused colour slots (holes)
        this.colors = Collections.unmodifiableList(new ArrayList<>(colors));
        this.depth = depth;
        this.areaX = areaX;
        this.areaY = areaY;
        this.areaWidth = areaWidth;
        this.areaHeight = areaHeight;
        this.viewportWidth = viewportWidth;
        this.viewportHeight = viewportHeight;
    }

    /**
     * Direct construction (tests and internal callers); {@code null} entries of {@code colors} are holes. Same invariants
     * as {@link #from}: a depth target must have the {@link Formats#PSO_DSV_FORMAT}.
     */
    public static PassSpec of(String label, List<@Nullable Color> colors, @Nullable Depth depth, int areaX, int areaY,
                              int areaWidth, int areaHeight, int viewportWidth, int viewportHeight) {
        if (depth != null && depth.target().dxgiFormat() != Formats.PSO_DSV_FORMAT) {
            throw new IllegalArgumentException("only D32_FLOAT depth attachments are supported (DSV format "
                    + depth.target().dxgiFormat() + ")");
        }

        return new PassSpec(label, colors, depth, areaX, areaY, areaWidth, areaHeight, viewportWidth, viewportHeight);
    }

    /**
     * Converts the frontend descriptor. Views are down-cast to {@link D3D12TextureView} (a foreign view is an
     * {@link IllegalArgumentException}); {@code null} colour entries stay holes (null RTV at pass begin; the list
     * therefore must tolerate nulls - see the constructor). A depth attachment whose format is not {@code D32_FLOAT} is
     * rejected as well (defensive: {@code ResourceFactory.createTexture} already refuses to create such an attachment
     * texture, because a pass that has begun cannot be unwound).
     *
     * @param evaluateLabel true if {@code DeviceContext.labels()}; otherwise the label supplier is not called
     */
    public static PassSpec from(RenderPassDescriptor descriptor, boolean evaluateLabel) {
        List<@Nullable Color> colors = new ArrayList<>(descriptor.colorAttachments().size());
        RenderTargetRef viewportSource = null;

        for (RenderPassDescriptor.Attachment<Optional<Vector4fc>> attachment : descriptor.colorAttachments()) {
            if (attachment == null) {
                colors.add(null);
                continue;
            }

            RenderTargetRef target = cast(attachment.textureView()).colorTarget();
            Optional<Vector4fc> clear = attachment.clearValue();
            float[] clearValue = clear.isPresent()
                    ? new float[]{clear.get().x(), clear.get().y(), clear.get().z(), clear.get().w()}
                    : null;

            colors.add(new Color(target, clearValue));
            viewportSource = target;
        }

        Depth depth = null;
        RenderPassDescriptor.Attachment<OptionalDouble> depthAttachment = descriptor.depthAttachment();

        if (depthAttachment != null) {
            RenderTargetRef target = cast(depthAttachment.textureView()).depthTarget();
            OptionalDouble clear = depthAttachment.clearValue();

            depth = new Depth(target, clear.isPresent(), clear.isPresent() ? (float) clear.getAsDouble() : 0.0f);

            if (viewportSource == null) {
                viewportSource = target;
            }
        }

        if (viewportSource == null) {
            throw new IllegalArgumentException("A render pass needs at least one attachment");
        }

        RenderPass.RenderArea area = descriptor.renderArea();
        String label = evaluateLabel && descriptor.label() != null ? descriptor.label().get() : "";

        // The viewport covers the whole attachment of the LAST non-null colour view (else the depth view), not the render area.
        return of(label, colors, depth, area.x(), area.y(), area.width(), area.height(),
                viewportSource.width(), viewportSource.height());
    }

    /** The present blit: one colour attachment (a back buffer), no clear, no depth, area = viewport = the whole buffer. */
    public static PassSpec backBuffer(RenderTargetRef backBuffer) {
        List<@Nullable Color> colors = new ArrayList<>(1);

        colors.add(new Color(backBuffer, null));

        return of("present blit", colors, null, 0, 0, backBuffer.width(), backBuffer.height(),
                backBuffer.width(), backBuffer.height());
    }

    private static D3D12TextureView cast(GpuTextureView view) {
        if (view instanceof D3D12TextureView d3d) {
            return d3d;
        }

        throw new IllegalArgumentException("Texture view was not created by the Direct3D 12 backend: " + view);
    }

    public String label() {
        return this.label;
    }

    /** @return the colour attachments in slot order; {@code null} entries are holes */
    public List<@Nullable Color> colors() {
        return this.colors;
    }

    public @Nullable Depth depth() {
        return this.depth;
    }

    public int areaX() {
        return this.areaX;
    }

    public int areaY() {
        return this.areaY;
    }

    public int areaWidth() {
        return this.areaWidth;
    }

    public int areaHeight() {
        return this.areaHeight;
    }

    public int viewportWidth() {
        return this.viewportWidth;
    }

    public int viewportHeight() {
        return this.viewportHeight;
    }

    /** @return true if the render area covers a whole attachment extent of {@code width x height} (then clears need no rect) */
    public boolean areaFills(int width, int height) {
        return this.areaX == 0 && this.areaY == 0 && this.areaWidth == width && this.areaHeight == height;
    }

    /** @return true if the pass has a depth attachment (selects the PSO variant) */
    public boolean hasDepth() {
        return this.depth != null;
    }
}
