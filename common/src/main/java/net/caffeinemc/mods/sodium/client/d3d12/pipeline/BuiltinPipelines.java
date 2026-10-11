// Owner: WP3 (pipelines + render pass) - implemented; consumers: WP4 (PresentBlit, StartupProbes)
package net.caffeinemc.mods.sodium.client.d3d12.pipeline;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PolygonMode;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import net.caffeinemc.mods.sodium.client.d3d12.core.DeviceContext;
import net.caffeinemc.mods.sodium.client.d3d12.core.FormatInfo;
import net.caffeinemc.mods.sodium.client.d3d12.core.Formats;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Enums;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12PipelineState;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Pipelines the backend needs for itself, built from hand-written HLSL with the same {@code HlslCompiler}, root
 * signature, {@code SlotLayout} model and {@code D3D12RenderPipeline} type as game pipelines (so the present blit is an
 * ordinary pass). They reach the shared compiler services through {@code ctx.pipelineCompiler()}: {@code hlsl()} for
 * compiling the hand-written HLSL (same cache and semaphore as game shaders) and {@code layouts()} to intern their
 * {@code SlotLayout}, so built-in and game layouts never share an id by accident.
 *
 * <p><b>The present pipeline is a mandatory capability of the device.</b> {@code D3D12Backend.createDevice} builds
 * {@link #fullscreenBlit} right after {@code D3D12GpuDevice.create} - outside the swallow of {@code StartupProbes} - and
 * turns any failure into a {@code BackendCreationException} (Minecraft can still fall back to OpenGL/Vulkan there; a
 * failure first discovered in {@code D3D12Surface.configure} would be retried every frame with a black window and no way
 * out). The blit is therefore <em>memoised per device</em> ({@code D3D12PipelineCompiler.builtin}); every later caller
 * ({@code PresentBlit}) gets the same instance.
 */
public final class BuiltinPipelines {
    /** The present blit shader pair (see {@link #fullscreenBlit}). */
    static final String BLIT_HLSL = """
            cbuffer PC : register(b13)
            {
                uint2 copySize;
            };

            Texture2D<float4> Src : register(t0);

            float4 vs(uint id : SV_VertexID) : SV_Position
            {
                float2 t = float2((id << 1) & 2, id & 2);
                return float4(t * 2.0 - 1.0, 0.0, 1.0);
            }

            float4 ps(float4 p : SV_Position) : SV_Target0
            {
                uint2 q = (uint2) p.xy;
                if (q.x >= copySize.x || q.y >= copySize.y)
                {
                    return float4(0.0, 0.0, 0.0, 1.0);
                }
                return Src.Load(int3(q.x, copySize.y - 1 - q.y, 0));
            }
            """;

    /** Tests only: rewrites the built-in HLSL before it is compiled (to inject a compile failure). */
    private static volatile @Nullable UnaryOperator<String> sourceTransformForTests;

    private BuiltinPipelines() {
    }

    /** Tests only (null clears it): see {@link #sourceTransformForTests}. */
    public static void setSourceTransformForTests(@Nullable UnaryOperator<String> transform) {
        sourceTransformForTests = transform;
    }

    /** @return {@code hlsl}, rewritten by the test hook if one is installed */
    static String transformSource(String hlsl) {
        UnaryOperator<String> transform = sourceTransformForTests;

        return transform == null ? hlsl : transform.apply(hlsl);
    }

    /**
     * The present blit (spec 05 section 5.10): full-screen triangle, point-sampled flipped copy, black outside the copied
     * rectangle. Layout: slot 0 {@code TEXTURE} ({@code t0}, sampler unused), push constants 2 DWORDs
     * ({@code uint2 copySize}). No depth, no blend, cull none, one RTV of {@code rtvDxgiFormat}.
     * <pre>
     * cbuffer PC : register(b13) { uint2 copySize; };
     * Texture2D&lt;float4&gt; Src : register(t0);
     * float4 vs(uint id : SV_VertexID) : SV_Position { float2 t = float2((id &lt;&lt; 1) &amp; 2, id &amp; 2); return float4(t * 2.0 - 1.0, 0.0, 1.0); }
     * float4 ps(float4 p : SV_Position) : SV_Target0 {
     *     uint2 q = (uint2) p.xy;                                        // back buffer pixel, row 0 = top
     *     if (q.x &gt;= copySize.x || q.y &gt;= copySize.y) return float4(0, 0, 0, 1);
     *     return Src.Load(int3(q.x, copySize.y - 1 - q.y, 0));          // main target row 0 = bottom: flip, 1:1, top-left anchored
     * }
     * </pre>
     * Compile the entry points as {@code vs_5_0}/{@code ps_5_0} (the HLSL passes through {@link #transformSource}). The
     * vertex shader uses no base-vertex built-in, so no vertex-info root constants are involved.
     *
     * <p>Memoised per device and format through {@code ctx.pipelineCompiler().builtin(..)}: the first call (made by
     * {@code D3D12Backend.createDevice}) compiles and creates the PSO, every later call returns the same instance. The
     * result is shared: <b>callers must not close it</b> (it is registered in the {@code ObjectRegistry} and closed with
     * the device).
     *
     * @throws RuntimeException compiler text or HRESULT of the first failing step (no partial state is kept)
     */
    public static D3D12RenderPipeline fullscreenBlit(DeviceContext ctx, int rtvDxgiFormat) {
        D3D12PipelineCompiler compiler = ctx.pipelineCompiler();

        return compiler.builtin("present-blit:" + rtvDxgiFormat, () -> {
            SlotLayout layout = compiler.layouts().intern(SlotLayout.of(
                    List.of(new SlotInfo("blit_src", SlotKind.TEXTURE, 0, true, false, Formats.BASE_FLOAT, null)), 2, false));

            return build(ctx, "present blit", BLIT_HLSL, layout, rtvDxgiFormat, Enums.PrimitiveTopologyType.TRIANGLE,
                    Enums.PrimitiveTopology.D3D10_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
        });
    }

    /**
     * General hook for diagnostics (the {@code SV_VertexID} probe in {@code StartupProbes}).
     *
     * @param hlsl     one text containing both entry points {@code vs} and {@code ps} (compiled separately)
     * @param layout   the slot layout the shaders were written against (must use the global root signature registers)
     * @param topology {@code D3D_PRIMITIVE_TOPOLOGY} for the pipeline's draws
     * @param topologyType {@code D3D12_PRIMITIVE_TOPOLOGY_TYPE} for the PSO
     */
    public static D3D12RenderPipeline fromHlsl(DeviceContext ctx, String name, String hlsl, SlotLayout layout,
                                               int rtvDxgiFormat, int topologyType, int topology) {
        D3D12PipelineCompiler compiler = ctx.pipelineCompiler();

        return compiler.guarded("built-in pipeline " + name, () -> build(ctx, name, hlsl, compiler.layouts().intern(layout),
                rtvDxgiFormat, topologyType, topology));
    }

    /**
     * Compiles {@code vs} / {@code ps} of {@code hlsl} and creates both PSO variants (the DSV format is baked into the PSO;
     * these pipelines have no depth state, so the variant without a depth format exists too). Nothing is kept on failure.
     */
    private static D3D12RenderPipeline build(DeviceContext ctx, String name, String hlsl, SlotLayout layout, int rtvDxgiFormat,
                                             int topologyType, int topology) {
        D3D12PipelineCompiler compiler = ctx.pipelineCompiler();
        String text = transformSource(hlsl);
        byte[] vertex = compiler.hlsl().compile(text, "vs", "vs_5_0", name + "/vs");
        byte[] pixel = compiler.hlsl().compile(text, "ps", "ps_5_0", name + "/ps");
        List<BindGroupLayout.UniformDescription> uniforms = layout.slots().stream()
                .map(slot -> new BindGroupLayout.UniformDescription(slot.name(), UniformType.COMBINED_IMAGE_SAMPLER))
                .toList();
        BackendRenderPipeline.CreateInfo info = new BackendRenderPipeline.CreateInfo("builtin:" + name, List.of(), List.of(), List.of(),
                uniforms, layout.pushDwords() * 4, null, PolygonMode.FILL, false,
                List.of(new ColorTargetState(Optional.empty(), colorFormat(rtvDxgiFormat), ColorTargetState.WRITE_ALL)),
                PrimitiveTopology.TRIANGLES);

        D3D12PipelineState withDepth = PipelineStateBuilder.create(ctx, info, vertex, pixel, true, topologyType);
        D3D12PipelineState withoutDepth = null;

        try {
            withoutDepth = PipelineStateBuilder.create(ctx, info, vertex, pixel, false, topologyType);

            return new D3D12RenderPipeline(ctx, "builtin:" + name, withDepth, withoutDepth, layout, new int[16], 0, topology, false, false);
        } catch (Throwable t) {
            // never recorded: released at once
            withDepth.close();

            if (withoutDepth != null) {
                withoutDepth.close();
            }

            throw t;
        }
    }

    /** @return the {@link GpuFormat} whose render-target view format is {@code dxgiFormat} (the PSO builder takes {@code GpuFormat}s) */
    private static GpuFormat colorFormat(int dxgiFormat) {
        for (GpuFormat format : GpuFormat.values()) {
            FormatInfo info = Formats.of(format);

            if (!info.depth() && info.textureOk() && info.rtvFormat() == dxgiFormat) {
                return format;
            }
        }

        throw new IllegalArgumentException("DXGI format " + dxgiFormat + " is not a render-target format of the backend");
    }
}
