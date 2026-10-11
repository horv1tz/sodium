// Owner: WP3 (pipelines + render pass)
package net.caffeinemc.mods.sodium.client.d3d12.pass;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import net.caffeinemc.mods.sodium.client.d3d12.command.ClearEngine;
import net.caffeinemc.mods.sodium.client.d3d12.command.CommandRecorder;
import net.caffeinemc.mods.sodium.client.d3d12.core.DeviceContext;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.function.Supplier;

/**
 * All binding state and the draw-time flush of the open pass. One reusable instance (owned by {@link PassManager});
 * {@link #begin} resets everything. Hot-path discipline: <b>no Java allocation</b> in {@code setUniform},
 * {@code pushConstants}, {@code setVertexBuffer} or any draw (primitive arrays, per-owner native scratch allocated once,
 * no lambdas/varargs/boxing); {@code CopyDescriptors} with one 16-entry destination range (one downcall per table).
 *
 * <h2>State</h2>
 * pipeline, {@code SlotLayout}, stored uniform values (one {@code Object} per slot: the {@code GpuBufferSlice} or
 * {@code TextureViewAndSampler} the frontend passed), 16 vertex slices, index buffer + type, 128-byte zeroed push
 * scratch, scissor, dirty flags ({@code dirtyPso, dirtyCbv (12-bit mask), dirtySrv, dirtySmp, dirtyVb (16-bit mask),
 * dirtyIb, dirtyPush, dirtyVertexInfo}), {@code boundSrvEpoch} / {@code boundSmpEpoch} (the {@code ring.epoch()} values for
 * which {@code SetDescriptorHeaps} was last issued in this pass), the key (16 staging handles / null kinds) of the SRV and
 * sampler table currently bound, {@code validatedWriteSerial}, and the log-once set.
 *
 * <h2>Construction (no {@code ctx.encoder()})</h2>
 * The binder receives the {@link CommandRecorder} (for {@code recorder.list()}) and the {@link ClearEngine} (load-op
 * clears) explicitly: it is created inside the {@code D3D12CommandEncoder} constructor, <em>before</em>
 * {@code ctx.attachEncoder(..)} ran, so {@code ctx.encoder()} would throw "not fully constructed". It must not call
 * {@code ctx.encoder()} anywhere (constructor or later); everything else it needs ({@code tracker, descriptors,
 * rootSignatures, errors, clock}) is attached to the context before the encoder is constructed.
 *
 * <h2>Per-call semantics</h2>
 * <ul>
 *     <li>Every method starts with {@code open} (a closed pass throws {@link IllegalStateException}: the frontend guards
 *     only some methods).</li>
 *     <li>{@code setPipeline(p)}: down-cast ({@code IllegalArgumentException} for foreign or closed pipelines); select
 *     {@code pso(hasDepth)}; {@code null} -> remember "undrawable" (draws are skipped with a warn-once; strict throws);
 *     <b>if {@code p.layout().id() == currentLayoutId} keep all stored uniform values and dirty nothing</b> (the
 *     frontend's replay stores identical values), else clear the values and mark CBVs/tables dirty; always
 *     {@code dirtyPso}, {@code dirtyVb} (strides change) and, for fan pipelines, {@code dirtyIb}. Push-constant bytes,
 *     vertex slices and the index buffer persist across pipelines.</li>
 *     <li>{@code setUniform(i, v)}: out-of-range index -> ignore + warn once. Store the value; compare with the previous
 *     one and dirty only on change. State requests happen <b>here</b> ("bind time"): UBO/texel/VB/IB buffers
 *     {@code tracker.require(.., GR)} (untracked modes are skipped), textures
 *     {@code tracker.requireMips(tex.tracked(), mipLevels, baseMip, mipCount, layers, SRV_ALL)}: one range per layer,
 *     because the subresource index {@code mip + layer * mipLevels} makes "mip range x all layers" non-contiguous as soon
 *     as there is more than one layer (a cube view over a partial mip range). Feedback loop: if the view's subresource
 *     set (mip range x all layers) intersects <em>any</em> attachment subresource of the same resource
 *     ({@code ResourceStateTracker.subresourceInMipRange}, not just {@code baseMip}) the slot gets the null SRV + a
 *     warn-once and the attachment is never transitioned. Wrong type, closed object, misaligned UBO address or wrong
 *     dimension: treated as unset (zero CBV / null SRV / default sampler + warn once; strict throws). Inactive slots are
 *     stored only.</li>
 *     <li>{@code pushConstants(bb)}: no pipeline or {@code pushDwords == 0} -> ignore; zero the scratch, copy
 *     {@code min(remaining, pushBytes)} raw bytes with {@code MemorySegment.ofBuffer(bb)} (byte-order agnostic, at call
 *     time: Sodium frees the buffer right after), {@code dirtyPush}.</li>
 *     <li>{@code setVertexBuffer(slot, slice)}: store ({@code null} = slot empty, never "keep the previous" as Vulkan
 *     does), require GR, {@code dirtyVb |= 1 << slot}. {@code setIndexBuffer}: store, require GR, {@code dirtyIb}.</li>
 *     <li>{@code enableScissor}/{@code disableScissor}: {@code rsSetScissorRect} immediately (render area on disable).</li>
 * </ul>
 *
 * <h2>Draw-time flush</h2>
 * Returns {@code false} = "skip this draw" (warn once; strict throws). Phase 1 validates without mutating: undrawable
 * pipeline; a vertex slot of {@code pipeline.vertexBufferMask()} that is unset or unusable; a missing/unusable index
 * buffer for an indexed non-fan draw. Phase 2 (safety net): if {@code tracker.writeSerial() != validatedWriteSerial}
 * (something was moved away from a read state since the last validation - practically only
 * {@code createBuffer(data)} inside a pass) re-require every bound resource, then {@code validatedWriteSerial = serial}.
 * Phase 3 sets what is dirty, in this order:
 * <ol>
 *     <li><b>Heaps - before any table is filled</b> (a descriptor-table handle must lie inside the heap that is currently
 *     set, and a page flip happens inside the ring, so the flip must be forced <em>first</em>):
 * <pre>
 * heapsStale = srvRing.epoch() != boundSrvEpoch || smpRing.epoch() != boundSmpEpoch
 * if (dirtySrv || dirtySmp || heapsStale) {
 *     srvRing.reserve(16); smpRing.reserve(16);           // always both; the ONLY place where a page flips
 *     if (srvRing.epoch() != boundSrvEpoch || smpRing.epoch() != boundSmpEpoch) {
 *         list.setDescriptorHeaps(srvRing.currentHeap(), smpRing.currentHeap());
 *         boundSrvEpoch = srvRing.epoch(); boundSmpEpoch = smpRing.epoch();
 *         dirtySrv = dirtySmp = true;                     // tables set earlier point into the old heap
 *         forget the keys of the bound tables;           // (the rings cleared their table caches themselves)
 *     }
 * }
 * </pre>
 *     {@code reserve} guarantees that the following {@code allocateReserved(16)} on each ring does not flip (it throws
 *     if it would: a missing reserve is a bug, not a silent mis-bind). The epoch is the ring-global heap generation, so
 *     the check also catches {@code beginFrame}'s switch to the other slot's heap.</li>
 *     <li>{@code SetPipelineState} + {@code IASetPrimitiveTopology};</li>
 *     <li>root CBVs of dirty bits ({@code zeroCbv} for unset);</li>
 *     <li>the SRV table: fill 16 entries (owner's staging handle, else the null descriptor of the expected kind;
 *     texel buffers get {@code CreateShaderResourceView} directly into the ring); the cache key is the 16 staging handles
 *     with every null entry encoded as {@code DescriptorTableCache.nullKey(kind)} (2D, cube, buffer int/uint/float: a
 *     null of the wrong dimension is UB, so the kind is part of the key); lookup / insert in {@code srvRing.tableCache()},
 *     skip the rebuild when the key equals the table already bound, one {@code CopyDescriptors} per fill.
 *     <b>None of this applies when the layout has a TEXEL slot</b> ({@code SlotLayout.hasTexel()}): the key cannot see the
 *     texel SRV (a recreated buffer would keep the same key), so such a table is always filled fresh when dirty and is
 *     never looked up, inserted or skipped;</li>
 *     <li>the sampler table likewise from {@code samplerRing.tableCache()} (unowned and texel entries hold the default
 *     sampler, key {@code nullKey(NULL_SAMPLER)}; texel slots do not matter for this table, so the cache may be used);</li>
 *     <li>vertex buffer views (stride from the pipeline, size {@code min(buffer.size() - offset, 0x7FFFFFFF)}, contiguous
 *     runs in one call), the index buffer view (whole buffer, {@code R16_UINT}/{@code R32_UINT}; the fan buffer for fan
 *     pipelines), push constants ({@code SetGraphicsRoot32BitConstants(PARAM_PUSH, pushDwords)}), vertex info (only when
 *     changed since the last draw: {@code {vertexIdSemantics.rootBaseVertex(indexed, base), firstInstance}});</li>
 *     <li>finally {@code tracker.flush(list)}.</li>
 * </ol>
 * The call order {@code SetDescriptorHeaps -> SetGraphicsRootDescriptorTable} (never the reverse within one draw) is
 * asserted by {@code PassBinderLogicTest} with 64-descriptor pages and by {@code DescriptorPagingTest} (X8/X21).
 *
 * <h2>Draws</h2>
 * {@code instanceCount <= 0} or {@code count <= 0}: return before the flush. {@code drawIndexed} ->
 * {@code DrawIndexedInstanced(indexCount, instanceCount, firstIndex, vertexOffset, firstInstance)}; {@code draw} ->
 * {@code DrawInstanced(vertexCount, instanceCount, firstVertex, firstInstance)}; fan pipelines per {@code FanIndexBuffer}.
 * Indirect: {@code drawCount <= 0} returns (never GL's "0 draws once"); {@code slice.offset % 4 != 0} -> IAE; clamp
 * {@code n = min(count, slice.length / stride, (buffer.size - offset) / stride)}; fan -> {@code UnsupportedOperationException};
 * a {@code usesVertexInfo} pipeline gets {@code (0, 0)} and a warn once; the argument buffer is not bound through a
 * {@code setX} call, so request it here ({@code tracker.require(buffer.tracked(), GR)}, untracked modes skipped); then flush and
 * {@code ExecuteIndirect(signature, n, resource, offset, null, 0)}.
 */
final class PassBinder {
    private final DeviceContext ctx;
    private final CommandRecorder recorder;
    private final ClearEngine clears;
    private final FanIndexBuffer fan;

    /**
     * @param recorder the owner of the list ({@code recorder.list()} is the open list of the frame being recorded)
     * @param clears   the engine of the load-op clears at pass begin
     * @param fan      the {@code TRIANGLE_FAN} emulation index buffer (owned and closed by {@link PassManager})
     */
    PassBinder(DeviceContext ctx, CommandRecorder recorder, ClearEngine clears, FanIndexBuffer fan) {
        this.ctx = ctx;
        this.recorder = recorder;
        this.clears = clears;
        this.fan = fan;
    }

    FanIndexBuffer fan() {
        return this.fan;
    }

    CommandRecorder recorder() {
        return this.recorder;
    }

    ClearEngine clears() {
        return this.clears;
    }

    /** Resets all state for a new pass and establishes the list state (see {@link PassManager#begin}). */
    void begin(PassSpec spec, D3D12RenderPass pass) {
        throw new UnsupportedOperationException("WP3");
    }

    void end() {
        throw new UnsupportedOperationException("WP3: end the PIX event; nothing else is recorded");
    }

    void setPipeline(BackendRenderPipeline pipeline) {
        throw new UnsupportedOperationException("WP3");
    }

    void setUniform(int index, @Nullable Object value) {
        throw new UnsupportedOperationException("WP3");
    }

    void pushConstants(ByteBuffer value) {
        throw new UnsupportedOperationException("WP3");
    }

    void enableScissor(int x, int y, int width, int height) {
        throw new UnsupportedOperationException("WP3");
    }

    void disableScissor() {
        throw new UnsupportedOperationException("WP3");
    }

    void setVertexBuffer(int slot, @Nullable GpuBufferSlice slice) {
        throw new UnsupportedOperationException("WP3");
    }

    void setIndexBuffer(GpuBuffer buffer, IndexType type) {
        throw new UnsupportedOperationException("WP3");
    }

    void drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance) {
        throw new UnsupportedOperationException("WP3");
    }

    void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
        throw new UnsupportedOperationException("WP3");
    }

    void drawIndexedIndirect(GpuBufferSlice commands, int drawCount) {
        throw new UnsupportedOperationException("WP3");
    }

    void drawIndirect(GpuBufferSlice commands, int drawCount) {
        throw new UnsupportedOperationException("WP3");
    }

    void pushDebugGroup(Supplier<String> label) {
        throw new UnsupportedOperationException("WP3: list.beginEvent only when ctx.labels()");
    }

    void popDebugGroup() {
        throw new UnsupportedOperationException("WP3");
    }

    void writeTimestamp(GpuQueryPool pool, int index) {
        throw new UnsupportedOperationException("WP3: D3D12QueryPool.write(list, index)");
    }

    DeviceContext context() {
        return this.ctx;
    }
}
