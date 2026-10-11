// Owner: WP3 (pipelines + render pass)
package net.caffeinemc.mods.sodium.client.d3d12.pipeline;

import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import net.caffeinemc.mods.sodium.client.d3d12.core.DeviceContext;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12PipelineState;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A compiled pipeline: two PSO variants (the DSV format is baked into the PSO), the slot layout and the per-pipeline
 * input-assembler facts. Immutable after construction; created by {@code finishCompile()} (which only wraps objects that
 * were built on the worker thread and cannot throw).
 *
 * <ul>
 *     <li>{@link #psoWithDepth()}: {@code DSVFormat = D32_FLOAT}, always present.</li>
 *     <li>{@link #psoNoDepth()}: {@code DSVFormat = UNKNOWN}, only when the pipeline has no depth state; for a pipeline
 *     with a depth state it is null and {@link #pso(boolean)} returns null for a pass without depth, which the binder
 *     treats as "undrawable" (skip the draws, warn once; strict mode throws).</li>
 *     <li>{@link #layout()}: slots/registers/activity, push DWORDs, vertex-info use; interned (see {@link SlotLayout}).</li>
 *     <li>{@link #vertexStride(int)} / {@link #vertexBufferMask()}: strides come from the pipeline, not from the buffer
 *     slice; the mask lists the slots the vertex format uses (a draw needs a vertex buffer in every one of them).</li>
 *     <li>{@link #topology()}: value for {@code IASetPrimitiveTopology} (not part of the PSO, only the topology type is);
 *     {@link #isFan()}: draws go through the fan index buffer.</li>
 * </ul>
 *
 * <p>{@code close()}: idempotent, sets {@code closed} immediately (volatile), defers the release of both PSOs through
 * {@code DeviceContext.defer} (recorded commands may still reference them). {@code PassBinder.setPipeline} refuses a
 * closed pipeline ({@code IllegalArgumentException}) so a pipeline closed in an earlier frame can never be recorded again.
 */
public final class D3D12RenderPipeline implements BackendRenderPipeline {
    private final DeviceContext ctx;
    private final String name;
    private final D3D12PipelineState psoWithDepth;
    private final @Nullable D3D12PipelineState psoNoDepth;
    private final SlotLayout layout;
    private final int[] vertexStrides;
    private final int vertexBufferMask;
    private final int topology;
    private final boolean fan;
    private final boolean hasDepthState;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * @param vertexStrides   16 entries: stride in bytes of each vertex buffer slot, 0 for unused slots
     * @param vertexBufferMask bit {@code i} set if slot {@code i} appears in {@code CreateInfo.vertexBuffers()}
     * @param topology        {@code D3D_PRIMITIVE_TOPOLOGY} for {@code IASetPrimitiveTopology}
     */
    public D3D12RenderPipeline(DeviceContext ctx, String name, D3D12PipelineState psoWithDepth,
                               @Nullable D3D12PipelineState psoNoDepth, SlotLayout layout, int[] vertexStrides,
                               int vertexBufferMask, int topology, boolean fan, boolean hasDepthState) {
        this.ctx = ctx;
        this.name = name;
        this.psoWithDepth = psoWithDepth;
        this.psoNoDepth = psoNoDepth;
        this.layout = layout;
        this.vertexStrides = vertexStrides.clone();
        this.vertexBufferMask = vertexBufferMask;
        this.topology = topology;
        this.fan = fan;
        this.hasDepthState = hasDepthState;

        ctx.registry().register(this, "pipeline " + name);
    }

    public String name() {
        return this.name;
    }

    public D3D12PipelineState psoWithDepth() {
        return this.psoWithDepth;
    }

    public @Nullable D3D12PipelineState psoNoDepth() {
        return this.psoNoDepth;
    }

    /** @return the PSO for a pass with / without a depth attachment, or null if this pipeline cannot draw in such a pass */
    public @Nullable D3D12PipelineState pso(boolean passHasDepth) {
        return passHasDepth ? this.psoWithDepth : this.psoNoDepth;
    }

    public SlotLayout layout() {
        return this.layout;
    }

    public int vertexStride(int slot) {
        return this.vertexStrides[slot];
    }

    public int vertexBufferMask() {
        return this.vertexBufferMask;
    }

    public int topology() {
        return this.topology;
    }

    public boolean isFan() {
        return this.fan;
    }

    /** @return true if the pipeline has a depth-stencil state (then it needs a depth attachment) */
    public boolean hasDepthState() {
        return this.hasDepthState;
    }

    @Override
    public boolean isClosed() {
        return this.closed.get();
    }

    @Override
    public void close() {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }

        this.ctx.registry().unregister(this);

        D3D12PipelineState with = this.psoWithDepth;
        D3D12PipelineState without = this.psoNoDepth;

        this.ctx.defer(() -> {
            with.close();

            if (without != null) {
                without.close();
            }
        });
    }
}
