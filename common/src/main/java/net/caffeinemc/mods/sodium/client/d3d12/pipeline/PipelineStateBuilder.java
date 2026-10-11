// Owner: WP3 (pipelines + render pass)
package net.caffeinemc.mods.sodium.client.d3d12.pipeline;

import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import net.caffeinemc.mods.sodium.client.d3d12.core.DeviceContext;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12PipelineState;

/**
 * {@code CreateInfo} + DXBC -> {@code D3D12_GRAPHICS_PIPELINE_STATE_DESC} -> {@code ID3D12PipelineState}, thread-safe
 * (each call uses its own confined {@code Arena}); the state tables are in {@link StateMapper}.
 *
 * <p>Rules (spec 02 sections 7.2-7.7): root signature = {@code ctx.rootSignatures().global()}; VS/PS bytecode only;
 * {@code SampleMask = 0xFFFFFFFF}, {@code SampleDesc {1,0}}; rasteriser {@code FillMode = polygonMode},
 * {@code CullMode = cull ? BACK : NONE}, {@code FrontCounterClockwise = FALSE}, {@code DepthClipEnable = TRUE},
 * bias only with a depth state; depth {@code DepthEnable = depthStencilState != null}; blend per target with
 * {@code IndependentBlendEnable = TRUE}, alpha-equation factors rewritten {@code *_COLOR -> *_ALPHA}, write mask
 * {@code & 0xF}; holes: {@code RTVFormats[i] = UNKNOWN}, mask 0; RGB8/RGB16 vertex formats fall back to the 4-component
 * format; an {@code AlignedByteOffset} that is not a multiple of 4 for a packed 4-byte format fails the pipeline cleanly;
 * input layout {@code "TEXCOORD"} + location, {@code PER_INSTANCE_DATA} with {@code InstanceDataStepRate = stepRate} for
 * {@code stepRate > 0}; {@code IBStripCutValue = DISABLED}; topology type from {@code StateMapper.topologyType}.
 * Required render-target / blendable format support is checked once at device creation, not here.
 */
public final class PipelineStateBuilder {
    private PipelineStateBuilder() {
    }

    /**
     * @param withDepth true: {@code DSVFormat = Formats.PSO_DSV_FORMAT} = {@code D32_FLOAT} (PSO A; depth attachments of any
     *                  other format are rejected at texture creation and in {@code PassSpec.from}); false: {@code UNKNOWN} (PSO B, only meaningful for
     *                  pipelines without a depth state)
     * @throws RuntimeException when {@code CreateGraphicsPipelineState} fails (caller logs and returns {@code Pending.NULL})
     */
    public static D3D12PipelineState create(DeviceContext ctx, BackendRenderPipeline.CreateInfo info, byte[] vertexDxbc,
                                            byte[] pixelDxbc, boolean withDepth) {
        throw new UnsupportedOperationException("WP3: fill D3D12_GRAPHICS_PIPELINE_STATE_DESC from the abi.structs accessors");
    }
}
