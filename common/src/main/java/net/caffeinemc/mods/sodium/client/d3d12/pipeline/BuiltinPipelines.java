// Owner: WP3 (pipelines + render pass); consumers: WP4 (PresentBlit, StartupProbes)
package net.caffeinemc.mods.sodium.client.d3d12.pipeline;

import net.caffeinemc.mods.sodium.client.d3d12.core.DeviceContext;
import org.jspecify.annotations.Nullable;

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
        throw new UnsupportedOperationException("WP3: HLSL above -> HlslCompiler -> PSO (no depth) -> D3D12RenderPipeline");
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
        throw new UnsupportedOperationException("WP3");
    }
}
