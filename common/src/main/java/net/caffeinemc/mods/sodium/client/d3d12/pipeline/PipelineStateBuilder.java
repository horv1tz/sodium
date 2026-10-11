// Owner: WP3 (pipelines + render pass) - implemented
package net.caffeinemc.mods.sodium.client.d3d12.pipeline;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BlendEquation;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PolygonMode;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import net.caffeinemc.mods.sodium.client.d3d12.core.D3D12Log;
import net.caffeinemc.mods.sodium.client.d3d12.core.DeviceContext;
import net.caffeinemc.mods.sodium.client.d3d12.core.FormatInfo;
import net.caffeinemc.mods.sodium.client.d3d12.core.Formats;
import net.caffeinemc.mods.sodium.client.d3d12.interop.D3D12Exception;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Enums;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12BlendDesc;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12DepthStencilDesc;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12DepthStencilopDesc;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12GraphicsPipelineStateDesc;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12InputElementDesc;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12InputLayoutDesc;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12RasterizerDesc;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12RenderTargetBlendDesc;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12ShaderBytecode;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12PipelineState;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;

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
    /** {@code D3D12_SIMULTANEOUS_RENDER_TARGET_COUNT}. */
    static final int MAX_RENDER_TARGETS = 8;
    private static final String SEMANTIC = "TEXCOORD";

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
        return create(ctx, info, vertexDxbc, pixelDxbc, withDepth, StateMapper.topologyType(info.primitiveTopology()));
    }

    /**
     * As {@link #create(DeviceContext, BackendRenderPipeline.CreateInfo, byte[], byte[], boolean)} with an explicit
     * {@code D3D12_PRIMITIVE_TOPOLOGY_TYPE} (the built-in pipelines are described by the raw D3D value).
     */
    static D3D12PipelineState create(DeviceContext ctx, BackendRenderPipeline.CreateInfo info, byte[] vertexDxbc,
                                     byte[] pixelDxbc, boolean withDepth, int topologyType) {
        D3D12PipelineState pso;

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment desc = describe(arena, ctx.rootSignatures().global().ptr(), info, vertexDxbc, pixelDxbc, withDepth, topologyType);

            pso = ctx.d3d().createGraphicsPipelineState(desc);
        } catch (D3D12Exception e) {
            throw new IllegalStateException("CreateGraphicsPipelineState failed for pipeline '" + info.name() + "'"
                    + (withDepth ? "" : " (variant without depth)") + ": " + e.getMessage(), e);
        }

        if (ctx.labels()) {
            try {
                pso.setName(withDepth ? info.name() : info.name() + " [no depth]");
            } catch (RuntimeException e) {
                D3D12Log.debug("Cannot name the pipeline state of {}: {}", info.name(), e.toString());
            }
        }

        return pso;
    }

    /**
     * Fills a {@code D3D12_GRAPHICS_PIPELINE_STATE_DESC} (pure memory; the shader bytecode, the input elements and the
     * semantic name live in {@code arena}). Package-private so the layout tests can run without a device.
     *
     * @param rootSignature the {@code ID3D12RootSignature*} to embed ({@code MemorySegment.NULL} in tests)
     * @param topologyType  the {@code D3D12_PRIMITIVE_TOPOLOGY_TYPE} of the PSO
     * @throws IllegalArgumentException for a create info that cannot become a legal PSO (the caller logs it and returns
     *                                  {@code Pending.NULL})
     */
    static MemorySegment describe(Arena arena, MemorySegment rootSignature, BackendRenderPipeline.CreateInfo info,
                                  byte[] vertexDxbc, byte[] pixelDxbc, boolean withDepth, int topologyType) {
        DepthStencilState depthState = info.depthStencilState();
        List<@Nullable ColorTargetState> targets = info.colorTargetStates();

        if (!withDepth && depthState != null) {
            throw new IllegalArgumentException("pipeline '" + info.name() + "' has a depth state: it cannot be built without a depth format");
        }

        if (targets.size() > MAX_RENDER_TARGETS) {
            throw new IllegalArgumentException("pipeline '" + info.name() + "' has " + targets.size() + " colour targets (maximum " + MAX_RENDER_TARGETS + ")");
        }

        MemorySegment desc = D3D12GraphicsPipelineStateDesc.allocate(arena);

        D3D12GraphicsPipelineStateDesc.setPRootSignature(desc, rootSignature);
        bytecode(arena, D3D12GraphicsPipelineStateDesc.sliceVS(desc), vertexDxbc);
        bytecode(arena, D3D12GraphicsPipelineStateDesc.slicePS(desc), pixelDxbc);

        blend(D3D12GraphicsPipelineStateDesc.sliceBlendState(desc), targets);

        D3D12GraphicsPipelineStateDesc.setSampleMask(desc, 0xFFFFFFFF);

        rasterizer(info, D3D12GraphicsPipelineStateDesc.sliceRasterizerState(desc));
        depthStencil(depthState, D3D12GraphicsPipelineStateDesc.sliceDepthStencilState(desc));
        inputLayout(arena, info, D3D12GraphicsPipelineStateDesc.sliceInputLayout(desc));

        D3D12GraphicsPipelineStateDesc.setIBStripCutValue(desc, Enums.IndexBufferStripCutValue.DISABLED);
        D3D12GraphicsPipelineStateDesc.setPrimitiveTopologyType(desc, topologyType);
        D3D12GraphicsPipelineStateDesc.setNumRenderTargets(desc, targets.size());

        for (int i = 0; i < targets.size(); i++) {
            ColorTargetState target = targets.get(i);

            D3D12GraphicsPipelineStateDesc.setRTVFormats(desc, i, target == null ? Enums.Format.UNKNOWN : renderTargetFormat(info, i, target.format()));
        }

        D3D12GraphicsPipelineStateDesc.setDSVFormat(desc, withDepth ? Formats.PSO_DSV_FORMAT : Enums.Format.UNKNOWN);

        MemorySegment sample = D3D12GraphicsPipelineStateDesc.sliceSampleDesc(desc);

        sample.set(ValueLayout.JAVA_INT_UNALIGNED, 0, 1);
        sample.set(ValueLayout.JAVA_INT_UNALIGNED, 4, 0);

        D3D12GraphicsPipelineStateDesc.setNodeMask(desc, 0);
        D3D12GraphicsPipelineStateDesc.setFlags(desc, Enums.PipelineStateFlags.NONE);

        return desc;
    }

    private static void bytecode(Arena arena, MemorySegment slot, byte[] dxbc) {
        MemorySegment copy = arena.allocate(dxbc.length);

        MemorySegment.copy(dxbc, 0, copy, ValueLayout.JAVA_BYTE, 0, dxbc.length);

        D3D12ShaderBytecode.setPShaderBytecode(slot, copy);
        D3D12ShaderBytecode.setBytecodeLength(slot, dxbc.length);
    }

    private static int renderTargetFormat(BackendRenderPipeline.CreateInfo info, int index, GpuFormat format) {
        FormatInfo formatInfo = Formats.of(format);

        if (formatInfo.depth() || !formatInfo.textureOk() || formatInfo.rtvFormat() == Enums.Format.UNKNOWN) {
            throw new IllegalArgumentException("pipeline '" + info.name() + "': colour target " + index + " format " + format + " cannot be rendered to");
        }

        return formatInfo.rtvFormat();
    }

    private static void blend(MemorySegment blend, List<@Nullable ColorTargetState> targets) {
        D3D12BlendDesc.setAlphaToCoverageEnable(blend, false);
        D3D12BlendDesc.setIndependentBlendEnable(blend, true);

        MemorySegment renderTargets = D3D12BlendDesc.sliceRenderTarget(blend);

        for (int i = 0; i < MAX_RENDER_TARGETS; i++) {
            MemorySegment rt = renderTargets.asSlice(i * D3D12RenderTargetBlendDesc.SIZE, D3D12RenderTargetBlendDesc.SIZE);
            ColorTargetState target = i < targets.size() ? targets.get(i) : null;

            // D3D12_DEFAULT: valid enumerators even where blending is off or the slot is unused
            D3D12RenderTargetBlendDesc.setBlendEnable(rt, false);
            D3D12RenderTargetBlendDesc.setLogicOpEnable(rt, false);
            D3D12RenderTargetBlendDesc.setSrcBlend(rt, Enums.Blend.ONE);
            D3D12RenderTargetBlendDesc.setDestBlend(rt, Enums.Blend.ZERO);
            D3D12RenderTargetBlendDesc.setBlendOp(rt, Enums.BlendOp.ADD);
            D3D12RenderTargetBlendDesc.setSrcBlendAlpha(rt, Enums.Blend.ONE);
            D3D12RenderTargetBlendDesc.setDestBlendAlpha(rt, Enums.Blend.ZERO);
            D3D12RenderTargetBlendDesc.setBlendOpAlpha(rt, Enums.BlendOp.ADD);
            D3D12RenderTargetBlendDesc.setLogicOp(rt, Enums.LogicOp.NOOP);
            D3D12RenderTargetBlendDesc.setRenderTargetWriteMask(rt, (byte) 0);

            if (target == null) {
                continue;
            }

            // Sodium passes 0xFFFFFFFF: only the four colour bits exist in D3D12
            D3D12RenderTargetBlendDesc.setRenderTargetWriteMask(rt, (byte) (target.writeMask() & Enums.ColorWriteEnable.ALL));

            if (target.blendFunction().isPresent()) {
                BlendFunction function = target.blendFunction().get();
                BlendEquation color = function.color();
                BlendEquation alpha = function.alpha();

                D3D12RenderTargetBlendDesc.setBlendEnable(rt, true);
                D3D12RenderTargetBlendDesc.setSrcBlend(rt, StateMapper.colorBlendFactor(color.sourceFactor()));
                D3D12RenderTargetBlendDesc.setDestBlend(rt, StateMapper.colorBlendFactor(color.destFactor()));
                D3D12RenderTargetBlendDesc.setBlendOp(rt, StateMapper.blendOp(color.op()));
                D3D12RenderTargetBlendDesc.setSrcBlendAlpha(rt, StateMapper.alphaBlendFactor(alpha.sourceFactor()));
                D3D12RenderTargetBlendDesc.setDestBlendAlpha(rt, StateMapper.alphaBlendFactor(alpha.destFactor()));
                D3D12RenderTargetBlendDesc.setBlendOpAlpha(rt, StateMapper.blendOp(alpha.op()));
            }
        }
    }

    private static void rasterizer(BackendRenderPipeline.CreateInfo info, MemorySegment rasterizer) {
        DepthStencilState depth = info.depthStencilState();

        D3D12RasterizerDesc.setFillMode(rasterizer, info.polygonMode() == PolygonMode.WIREFRAME ? Enums.FillMode.WIREFRAME : Enums.FillMode.SOLID);
        D3D12RasterizerDesc.setCullMode(rasterizer, info.cull() ? Enums.CullMode.BACK : Enums.CullMode.NONE);
        // together with the vertex-stage Y flip this reproduces Vulkan's clockwise front face on an unflipped viewport
        D3D12RasterizerDesc.setFrontCounterClockwise(rasterizer, false);
        D3D12RasterizerDesc.setDepthClipEnable(rasterizer, true);
        D3D12RasterizerDesc.setMultisampleEnable(rasterizer, false);
        D3D12RasterizerDesc.setAntialiasedLineEnable(rasterizer, false);
        D3D12RasterizerDesc.setForcedSampleCount(rasterizer, 0);
        D3D12RasterizerDesc.setConservativeRaster(rasterizer, Enums.ConservativeRasterizationMode.OFF);
        D3D12RasterizerDesc.setDepthBiasClamp(rasterizer, 0.0f);

        if (depth == null) {
            D3D12RasterizerDesc.setDepthBias(rasterizer, 0);
            D3D12RasterizerDesc.setSlopeScaledDepthBias(rasterizer, 0.0f);
            return;
        }

        float constant = depth.depthBiasConstant();
        int rounded = Math.round(constant);

        if (rounded != constant) {
            D3D12Log.warnOnce("depth-bias:" + info.name(), "Pipeline {} has the non-integral depth bias constant {}; Direct3D 12 truncates it to {}",
                    info.name(), constant, rounded);
        }

        // Vulkan's constantFactor / slopeFactor; GL's glPolygonOffset(scaleFactor, constant) is the same pair
        D3D12RasterizerDesc.setDepthBias(rasterizer, rounded);
        D3D12RasterizerDesc.setSlopeScaledDepthBias(rasterizer, depth.depthBiasScaleFactor());
    }

    private static void depthStencil(@Nullable DepthStencilState state, MemorySegment depthStencil) {
        D3D12DepthStencilDesc.setDepthEnable(depthStencil, state != null);
        D3D12DepthStencilDesc.setDepthWriteMask(depthStencil, state != null && state.writeDepth() ? Enums.DepthWriteMask.ALL : Enums.DepthWriteMask.ZERO);
        D3D12DepthStencilDesc.setDepthFunc(depthStencil, state == null ? Enums.ComparisonFunc.LESS : StateMapper.comparison(state.depthTest()));
        D3D12DepthStencilDesc.setStencilEnable(depthStencil, false);
        D3D12DepthStencilDesc.setStencilReadMask(depthStencil, (byte) 0xFF);
        D3D12DepthStencilDesc.setStencilWriteMask(depthStencil, (byte) 0xFF);

        for (long face : new long[]{D3D12DepthStencilDesc.OFFSET_FRONT_FACE, D3D12DepthStencilDesc.OFFSET_BACK_FACE}) {
            MemorySegment op = depthStencil.asSlice(face, D3D12DepthStencilopDesc.SIZE);

            D3D12DepthStencilopDesc.setStencilFailOp(op, Enums.StencilOp.KEEP);
            D3D12DepthStencilopDesc.setStencilDepthFailOp(op, Enums.StencilOp.KEEP);
            D3D12DepthStencilopDesc.setStencilPassOp(op, Enums.StencilOp.KEEP);
            D3D12DepthStencilopDesc.setStencilFunc(op, Enums.ComparisonFunc.ALWAYS);
        }
    }

    private static void inputLayout(Arena arena, BackendRenderPipeline.CreateInfo info, MemorySegment layout) {
        List<BackendRenderPipeline.CreateInfo.AttribBinding> attributes = info.attribBindings();

        if (attributes.isEmpty()) {
            D3D12InputLayoutDesc.setPInputElementDescs(layout, MemorySegment.NULL);
            D3D12InputLayoutDesc.setNumElements(layout, 0);
            return;
        }

        MemorySegment semantic = arena.allocateFrom(SEMANTIC);
        MemorySegment elements = D3D12InputElementDesc.allocateArray(arena, attributes.size());

        for (int i = 0; i < attributes.size(); i++) {
            BackendRenderPipeline.CreateInfo.AttribBinding attribute = attributes.get(i);
            BackendRenderPipeline.CreateInfo.VertexBuffer buffer = vertexBuffer(info, attribute.bufferSlot());
            int stepRate = buffer.stepRate();
            MemorySegment element = elements.asSlice(i * D3D12InputElementDesc.SIZE, D3D12InputElementDesc.SIZE);

            checkAlignment(info, attribute);

            D3D12InputElementDesc.setSemanticName(element, semantic);
            D3D12InputElementDesc.setSemanticIndex(element, attribute.location());
            D3D12InputElementDesc.setFormat(element, Formats.vertexFormat(attribute.format()));
            D3D12InputElementDesc.setInputSlot(element, attribute.bufferSlot());
            D3D12InputElementDesc.setAlignedByteOffset(element, attribute.offset());
            D3D12InputElementDesc.setInputSlotClass(element, stepRate > 0 ? Enums.InputClassification.INSTANCE_DATA : Enums.InputClassification.VERTEX_DATA);
            D3D12InputElementDesc.setInstanceDataStepRate(element, stepRate > 0 ? stepRate : 0);
        }

        D3D12InputLayoutDesc.setPInputElementDescs(layout, elements);
        D3D12InputLayoutDesc.setNumElements(layout, attributes.size());
    }

    private static BackendRenderPipeline.CreateInfo.VertexBuffer vertexBuffer(BackendRenderPipeline.CreateInfo info, int slot) {
        for (BackendRenderPipeline.CreateInfo.VertexBuffer buffer : info.vertexBuffers()) {
            if (buffer.bufferSlot() == slot) {
                return buffer;
            }
        }

        throw new IllegalArgumentException("pipeline '" + info.name() + "' reads vertex buffer slot " + slot + " which has no vertex format");
    }

    /**
     * A packed element (several components in at most 4 bytes: {@code R8G8B8A8}, {@code R16G16}, {@code R10G10B10A2}, ...) is
     * read as one unit, so its offset must be aligned to its size; the frontend only guarantees the component size.
     */
    private static void checkAlignment(BackendRenderPipeline.CreateInfo info, BackendRenderPipeline.CreateInfo.AttribBinding attribute) {
        GpuFormat format = attribute.format();
        int components = format.componentCount();
        int componentBytes = format.componentType().byteSize();
        int elementBytes = components == 3 && componentBytes < 4 ? 4 * componentBytes : format.blockSize();

        if (elementBytes <= 4 && elementBytes > componentBytes && attribute.offset() % elementBytes != 0) {
            throw new IllegalArgumentException("pipeline '" + info.name() + "': the " + format + " attribute at location " + attribute.location()
                    + " has offset " + attribute.offset() + ", which is not a multiple of " + elementBytes);
        }
    }
}
