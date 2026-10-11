// Owner: WP3 (pipelines + render pass) - fully implemented tables (spec 02 section 7); WP3 owner adds StateMapperTest
package net.caffeinemc.mods.sodium.client.d3d12.pipeline;

import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendOp;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Enums;

/**
 * Fixed-function state tables from {@code CreateInfo} to D3D12 enumerations (spec 02 section 7). Pure, thread-safe.
 *
 * <p>Other fixed rules for the PSO builder (spec 02 section 7.2-7.7): {@code FrontCounterClockwise = FALSE} together
 * with the vertex-shader Y flip; {@code CullMode = cull ? BACK : NONE}; {@code DepthClipEnable = TRUE}; depth bias
 * {@code DepthBias = round(constant)} (WARN if not integral), {@code SlopeScaledDepthBias = scaleFactor}, clamp 0, only
 * when a depth state exists; write mask {@code writeMask & 0xF} (Sodium passes {@code 0xFFFFFFFF});
 * {@code IndependentBlendEnable = TRUE}; holes in the target list get format UNKNOWN and mask 0; {@code IBStripCutValue =
 * DISABLED}; input layout semantic {@code "TEXCOORD"} with index = shader location; PSO A has {@code DSVFormat =
 * D32_FLOAT} (always), PSO B {@code UNKNOWN} (only when {@code depthStencilState == null}).
 */
public final class StateMapper {
    private StateMapper() {
    }

    public static int comparison(CompareOp op) {
        return switch (op) {
            case ALWAYS_PASS -> Enums.ComparisonFunc.ALWAYS;
            case LESS_THAN -> Enums.ComparisonFunc.LESS;
            case LESS_THAN_OR_EQUAL -> Enums.ComparisonFunc.LESS_EQUAL;
            case EQUAL -> Enums.ComparisonFunc.EQUAL;
            case NOT_EQUAL -> Enums.ComparisonFunc.NOT_EQUAL;
            case GREATER_THAN_OR_EQUAL -> Enums.ComparisonFunc.GREATER_EQUAL;
            case GREATER_THAN -> Enums.ComparisonFunc.GREATER;
            case NEVER_PASS -> Enums.ComparisonFunc.NEVER;
        };
    }

    /** Blend factor for the <b>colour</b> equation. */
    public static int colorBlendFactor(BlendFactor factor) {
        return switch (factor) {
            case ZERO -> Enums.Blend.ZERO;
            case ONE -> Enums.Blend.ONE;
            case SRC_COLOR -> Enums.Blend.SRC_COLOR;
            case ONE_MINUS_SRC_COLOR -> Enums.Blend.INV_SRC_COLOR;
            case DST_COLOR -> Enums.Blend.DEST_COLOR;
            case ONE_MINUS_DST_COLOR -> Enums.Blend.INV_DEST_COLOR;
            case SRC_ALPHA -> Enums.Blend.SRC_ALPHA;
            case ONE_MINUS_SRC_ALPHA -> Enums.Blend.INV_SRC_ALPHA;
            case DST_ALPHA -> Enums.Blend.DEST_ALPHA;
            case ONE_MINUS_DST_ALPHA -> Enums.Blend.INV_DEST_ALPHA;
            case SRC_ALPHA_SATURATE -> Enums.Blend.SRC_ALPHA_SAT;
            case CONSTANT_COLOR, CONSTANT_ALPHA -> Enums.Blend.BLEND_FACTOR;
            case ONE_MINUS_CONSTANT_COLOR, ONE_MINUS_CONSTANT_ALPHA -> Enums.Blend.INV_BLEND_FACTOR;
        };
    }

    /**
     * Blend factor for the <b>alpha</b> equation: D3D12 forbids {@code *_COLOR} factors there; GL and Vulkan use the A
     * component of the colour, which is exactly the corresponding {@code *_ALPHA} factor.
     */
    public static int alphaBlendFactor(BlendFactor factor) {
        return switch (factor) {
            case SRC_COLOR -> Enums.Blend.SRC_ALPHA;
            case ONE_MINUS_SRC_COLOR -> Enums.Blend.INV_SRC_ALPHA;
            case DST_COLOR -> Enums.Blend.DEST_ALPHA;
            case ONE_MINUS_DST_COLOR -> Enums.Blend.INV_DEST_ALPHA;
            default -> colorBlendFactor(factor);
        };
    }

    public static int blendOp(BlendOp op) {
        return switch (op) {
            case ADD -> Enums.BlendOp.ADD;
            case SUBTRACT -> Enums.BlendOp.SUBTRACT;
            case REVERSE_SUBTRACT -> Enums.BlendOp.REV_SUBTRACT;
            case MIN -> Enums.BlendOp.MIN;
            case MAX -> Enums.BlendOp.MAX;
        };
    }

    /** @return {@code D3D12_PRIMITIVE_TOPOLOGY_TYPE} for the PSO */
    public static int topologyType(PrimitiveTopology topology) {
        return switch (topology) {
            case POINTS -> Enums.PrimitiveTopologyType.POINT;
            case LINES, QUADS, TRIANGLES, TRIANGLE_STRIP, TRIANGLE_FAN -> Enums.PrimitiveTopologyType.TRIANGLE;
            case DEBUG_LINES, DEBUG_LINE_STRIP -> Enums.PrimitiveTopologyType.LINE;
        };
    }

    /**
     * @return {@code D3D_PRIMITIVE_TOPOLOGY} for {@code IASetPrimitiveTopology}. {@code QUADS} and {@code LINES} are plain
     * triangle lists (the caller's index buffer expands them), {@code TRIANGLE_FAN} is emulated as a triangle list over
     * the backend's fan index buffer.
     */
    public static int inputAssemblerTopology(PrimitiveTopology topology) {
        return switch (topology) {
            case POINTS -> Enums.PrimitiveTopology.D3D10_PRIMITIVE_TOPOLOGY_POINTLIST;
            case DEBUG_LINES -> Enums.PrimitiveTopology.D3D10_PRIMITIVE_TOPOLOGY_LINELIST;
            case DEBUG_LINE_STRIP -> Enums.PrimitiveTopology.D3D10_PRIMITIVE_TOPOLOGY_LINESTRIP;
            case TRIANGLE_STRIP -> Enums.PrimitiveTopology.D3D10_PRIMITIVE_TOPOLOGY_TRIANGLESTRIP;
            case LINES, QUADS, TRIANGLES, TRIANGLE_FAN -> Enums.PrimitiveTopology.D3D10_PRIMITIVE_TOPOLOGY_TRIANGLELIST;
        };
    }

    /** @return true if draws must go through the fan index buffer ({@code D3D12} has no triangle fan) */
    public static boolean isFan(PrimitiveTopology topology) {
        return topology == PrimitiveTopology.TRIANGLE_FAN;
    }
}
