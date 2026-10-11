// Owner: WP3 (pipelines + render pass) - implemented
package net.caffeinemc.mods.sodium.client.d3d12.pass;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import net.caffeinemc.mods.sodium.client.d3d12.command.ClearEngine;
import net.caffeinemc.mods.sodium.client.d3d12.command.CommandRecorder;
import net.caffeinemc.mods.sodium.client.d3d12.core.D3D12Log;
import net.caffeinemc.mods.sodium.client.d3d12.core.DeviceContext;
import net.caffeinemc.mods.sodium.client.d3d12.core.FormatInfo;
import net.caffeinemc.mods.sodium.client.d3d12.core.Formats;
import net.caffeinemc.mods.sodium.client.d3d12.descriptor.DescriptorTableCache;
import net.caffeinemc.mods.sodium.client.d3d12.descriptor.ShaderVisibleRing;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Enums;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12PipelineState;
import net.caffeinemc.mods.sodium.client.d3d12.pipeline.D3D12RenderPipeline;
import net.caffeinemc.mods.sodium.client.d3d12.pipeline.SlotInfo;
import net.caffeinemc.mods.sodium.client.d3d12.pipeline.SlotKind;
import net.caffeinemc.mods.sodium.client.d3d12.pipeline.SlotLayout;
import net.caffeinemc.mods.sodium.client.d3d12.resource.BufferMode;
import net.caffeinemc.mods.sodium.client.d3d12.resource.D3D12Buffer;
import net.caffeinemc.mods.sodium.client.d3d12.resource.RenderTargetRef;
import net.caffeinemc.mods.sodium.client.d3d12.state.ResourceStateTracker;
import net.caffeinemc.mods.sodium.client.d3d12.state.ResourceStates;
import net.caffeinemc.mods.sodium.client.d3d12.state.TrackedResource;
import net.caffeinemc.mods.sodium.client.d3d12.pipeline.SlotAssignment;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
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
    private static final int TABLE = DescriptorTableCache.TABLE_SIZE;
    private static final int MAX_SLOTS = SlotAssignment.MAX_CBV + SlotAssignment.MAX_SRV;
    private static final int VERTEX_SLOTS = 16;
    private static final int PUSH_BYTES = 128;
    private static final int MAX_COLOR_TARGETS = 8;
    private static final int INDIRECT_INDEXED_STRIDE = 20;
    private static final int INDIRECT_STRIDE = 16;

    /** Why a texture value cannot be bound (the reason of the substituted null descriptor). */
    private static final int TEXTURE_OK = 0;
    private static final int TEXTURE_UNSET = 1;
    private static final int TEXTURE_WRONG_TYPE = 2;
    private static final int TEXTURE_CLOSED = 3;
    private static final int TEXTURE_DIMENSION = 4;
    private static final int TEXTURE_FEEDBACK = 5;

    private final DeviceContext ctx;
    private final @Nullable CommandRecorder recorder;
    private final @Nullable ClearEngine clears;
    private final BindOps ops;
    private final ValueAccess values;
    private final FanIndexBuffer fan;

    private ResourceStateTracker tracker;

    // ---- the open pass
    private boolean open;
    private boolean passHasDepth;
    private boolean passEvent;
    private int groupDepth;
    private PassSpec spec;
    private final TrackedResource[] attachmentResource = new TrackedResource[MAX_COLOR_TARGETS + 1];
    private final int[] attachmentSubresource = new int[MAX_COLOR_TARGETS + 1];
    private int attachmentCount;

    // ---- pipeline and uniforms
    private D3D12RenderPipeline pipeline;
    private SlotLayout layout;
    private int layoutId = -1;
    private D3D12PipelineState pso;
    private boolean undrawable;
    private boolean usesSrvTable;
    private boolean usesSamplerTable;
    private final Object[] slotValues = new Object[MAX_SLOTS];

    // ---- geometry inputs
    private final GpuBufferSlice[] vertexSlices = new GpuBufferSlice[VERTEX_SLOTS];
    private final int[] issuedStride = new int[VERTEX_SLOTS];
    private GpuBuffer indexBuffer;
    private IndexType indexType = IndexType.INT;
    private boolean fanIndexBound;
    /** The view the fan index buffer was last bound with (the fan buffer is replaced when it grows). */
    private long boundFanVa;
    private int boundFanSize;
    private final byte[] push = new byte[PUSH_BYTES];

    // ---- dirty flags and what the list currently holds
    private int dirtyCbv;
    private int dirtyVb;
    private boolean dirtySrv;
    private boolean dirtySmp;
    private boolean dirtyPso;
    private boolean dirtyIb;
    private boolean dirtyPush;
    private D3D12PipelineState boundPso;
    private int boundTopology = -1;
    private int boundSrvEpoch;
    private int boundSmpEpoch;
    private boolean boundSrvKeyValid;
    private boolean boundSmpKeyValid;
    private final long[] boundSrvKey = new long[TABLE];
    private final long[] boundSmpKey = new long[TABLE];
    private long validatedWriteSerial;
    private boolean vertexInfoValid;
    private int lastBaseVertex;
    private int lastFirstInstance;

    // ---- scratch (allocated once)
    private final long[] srvKey = new long[TABLE];
    private final long[] srvSource = new long[TABLE];
    private final long[] smpKey = new long[TABLE];
    private final long[] smpSource = new long[TABLE];
    private final long[] vbVa = new long[VERTEX_SLOTS];
    private final int[] vbSize = new int[VERTEX_SLOTS];
    private final int[] vbStride = new int[VERTEX_SLOTS];
    private final long[] rtv = new long[MAX_COLOR_TARGETS];

    /**
     * @param recorder the owner of the list ({@code recorder.list()} is the open list of the frame being recorded)
     * @param clears   the engine of the load-op clears at pass begin
     * @param fan      the {@code TRIANGLE_FAN} emulation index buffer (owned and closed by {@link PassManager})
     */
    PassBinder(DeviceContext ctx, CommandRecorder recorder, ClearEngine clears, FanIndexBuffer fan) {
        this(ctx, recorder, clears, new ListBindOps(ctx, recorder, clears), new FrontendValues(), fan);
    }

    /** Test seam: the native side and the value access are given. */
    PassBinder(DeviceContext ctx, BindOps ops, ValueAccess values, FanIndexBuffer fan) {
        this(ctx, null, null, ops, values, fan);
    }

    private PassBinder(DeviceContext ctx, @Nullable CommandRecorder recorder, @Nullable ClearEngine clears, BindOps ops,
                       ValueAccess values, FanIndexBuffer fan) {
        this.ctx = ctx;
        this.recorder = recorder;
        this.clears = clears;
        this.ops = ops;
        this.values = values;
        this.fan = fan;
    }

    FanIndexBuffer fan() {
        return this.fan;
    }

    @Nullable CommandRecorder recorder() {
        return this.recorder;
    }

    @Nullable ClearEngine clears() {
        return this.clears;
    }

    DeviceContext context() {
        return this.ctx;
    }

    boolean isOpen() {
        return this.open;
    }

    /** Releases the native scratch of the production {@link ListBindOps}; idempotent. */
    void close() {
        if (this.ops instanceof ListBindOps list) {
            list.close();
        }
    }

    // =================================================================================================================
    // Pass begin / end
    // =================================================================================================================

    /** Resets all state for a new pass and establishes the list state (see {@link PassManager#begin}). */
    void begin(PassSpec spec, D3D12RenderPass pass) {
        if (this.open) {
            throw new IllegalStateException("A render pass is already open");
        }

        this.tracker = this.ctx.tracker();
        this.spec = spec;
        this.passHasDepth = spec.hasDepth();

        // attachments: requested in their write state, remembered for the feedback-loop test
        this.attachmentCount = 0;

        List<PassSpec.@Nullable Color> colors = spec.colors();

        for (int i = 0; i < colors.size(); i++) {
            PassSpec.Color color = colors.get(i);

            if (color != null) {
                RenderTargetRef target = color.target();

                this.tracker.require(target.resource(), target.subresource(), ResourceStates.RT);
                this.attachmentResource[this.attachmentCount] = target.resource();
                this.attachmentSubresource[this.attachmentCount++] = target.subresource();
            }
        }

        PassSpec.Depth depth = spec.depth();

        if (depth != null) {
            RenderTargetRef target = depth.target();

            this.tracker.require(target.resource(), target.subresource(), ResourceStates.DW);
            this.attachmentResource[this.attachmentCount] = target.resource();
            this.attachmentSubresource[this.attachmentCount++] = target.subresource();
        }

        this.ops.flushBarriers();
        this.ops.setHeapsAndRootSignature();
        this.resetState();

        int count = colors.size();
        long nullRtv = 0L;

        for (int i = 0; i < count; i++) {
            PassSpec.Color color = colors.get(i);

            if (color != null) {
                this.rtv[i] = color.target().cpuHandle();
            } else {
                if (nullRtv == 0L) {
                    nullRtv = this.ops.nullRtv();
                }

                this.rtv[i] = nullRtv;
            }
        }

        this.ops.setRenderTargets(this.rtv, count, depth == null ? 0L : depth.target().cpuHandle());

        // load-op clears inside the render area (the whole attachment when the area covers it)
        for (int i = 0; i < count; i++) {
            PassSpec.Color color = colors.get(i);

            if (color != null && color.clear() != null) {
                RenderTargetRef target = color.target();
                float[] value = color.clear();
                boolean whole = spec.areaFills(target.width(), target.height());

                this.ops.clearRtv(target, value[0], value[1], value[2], value[3], spec.areaX(), spec.areaY(),
                        whole ? 0 : spec.areaWidth(), whole ? 0 : spec.areaHeight());
            }
        }

        if (depth != null && depth.clear()) {
            RenderTargetRef target = depth.target();
            boolean whole = spec.areaFills(target.width(), target.height());

            this.ops.clearDsv(target, depth.clearValue(), spec.areaX(), spec.areaY(), whole ? 0 : spec.areaWidth(), whole ? 0 : spec.areaHeight());
        }

        // the viewport covers the whole attachment; only the scissor is restricted to the render area
        this.ops.setViewport(spec.viewportWidth(), spec.viewportHeight());
        this.setScissorToArea();
        this.ops.setBlendFactorZero();

        if (this.ctx.labels()) {
            String label = spec.label();

            this.ops.beginEvent(label == null || label.isEmpty() ? "render pass" : label);
            this.passEvent = true;
        }

        this.open = true;
    }

    private void resetState() {
        Arrays.fill(this.slotValues, null);
        Arrays.fill(this.vertexSlices, null);
        Arrays.fill(this.issuedStride, 0);
        Arrays.fill(this.push, (byte) 0);

        this.pipeline = null;
        this.layout = null;
        this.layoutId = -1;
        this.pso = null;
        this.undrawable = false;
        this.usesSrvTable = false;
        this.usesSamplerTable = false;
        this.indexBuffer = null;
        this.indexType = IndexType.INT;
        this.fanIndexBound = false;
        this.boundFanVa = 0L;
        this.boundFanSize = 0;
        this.groupDepth = 0;
        this.passEvent = false;

        this.dirtyCbv = 0;
        this.dirtyVb = 0xFFFF;
        this.dirtySrv = false;
        this.dirtySmp = false;
        this.dirtyPso = false;
        this.dirtyIb = true;
        this.dirtyPush = false;
        this.boundPso = null;
        this.boundTopology = -1;
        this.boundSrvKeyValid = false;
        this.boundSmpKeyValid = false;
        this.vertexInfoValid = false;
        this.boundSrvEpoch = this.ops.srvRing().epoch();
        this.boundSmpEpoch = this.ops.samplerRing().epoch();
        this.validatedWriteSerial = this.tracker.writeSerial();
    }

    void end() {
        try {
            while (this.groupDepth > 0) {
                this.groupDepth--;
                this.ops.endEvent();
            }

            if (this.passEvent) {
                this.passEvent = false;
                this.ops.endEvent();
            }
        } finally {
            // nothing may be retained after the pass: the next pass starts from scratch and the frontend may free everything
            this.open = false;
            this.spec = null;
            this.attachmentCount = 0;
            this.pipeline = null;
            this.layout = null;
            this.pso = null;
            this.indexBuffer = null;
            Arrays.fill(this.slotValues, null);
            Arrays.fill(this.vertexSlices, null);
            Arrays.fill(this.attachmentResource, null);
        }
    }

    // =================================================================================================================
    // State setters
    // =================================================================================================================

    void setPipeline(BackendRenderPipeline pipeline) {
        if (!(pipeline instanceof D3D12RenderPipeline candidate)) {
            throw new IllegalArgumentException("The pipeline was not created by the Direct3D 12 backend: " + pipeline);
        }

        if (candidate.isClosed()) {
            throw new IllegalArgumentException("The pipeline '" + candidate.name() + "' is closed");
        }

        SlotLayout newLayout = candidate.layout();
        boolean sameLayout = newLayout.id() >= 0 && newLayout.id() == this.layoutId;

        this.pipeline = candidate;
        this.pso = candidate.pso(this.passHasDepth);
        this.undrawable = this.pso == null;
        this.dirtyPso = true;
        this.dirtyPush = newLayout.pushDwords() > 0;

        if (candidate.isFan()) {
            this.dirtyIb = true;
        }

        if (sameLayout) {
            // a sibling pipeline: the frontend's replay stores identical values, so nothing is rebuilt or rebound
            this.layout = newLayout;
            return;
        }

        Arrays.fill(this.slotValues, null);

        this.layout = newLayout;
        this.layoutId = newLayout.id();
        this.usesSrvTable = false;
        this.usesSamplerTable = false;

        for (int i = 0; i < newLayout.size(); i++) {
            SlotKind kind = newLayout.slot(i).kind();

            this.usesSrvTable |= kind.usesSrvTable();
            this.usesSamplerTable |= kind == SlotKind.TEXTURE;
        }

        this.dirtyCbv = (1 << newLayout.cbvCount()) - 1;
        this.dirtySrv = this.usesSrvTable;
        this.dirtySmp = this.usesSamplerTable;
        this.boundSrvKeyValid = false;
        this.boundSmpKeyValid = false;
    }

    void setUniform(int index, @Nullable Object value) {
        SlotLayout layout = this.layout;

        if (layout == null) {
            D3D12Log.warnOnce("uniform-without-pipeline", "setUniform({}) was called before setPipeline and is ignored", index);
            return;
        }

        if (index < 0 || index >= layout.size()) {
            D3D12Log.warnOnce("uniform-range:" + this.pipeline.name() + ":" + index, "setUniform({}) is outside the {} uniforms of pipeline {} and is ignored",
                    index, layout.size(), this.pipeline.name());
            return;
        }

        Object previous = this.slotValues[index];

        if (previous == value || (previous != null && previous.equals(value))) {
            return;
        }

        this.slotValues[index] = value;

        SlotInfo slot = layout.slot(index);

        if (!slot.active()) {
            return;
        }

        switch (slot.kind()) {
            case UBO -> {
                this.requireBufferValue(value);
                this.dirtyCbv |= 1 << slot.register();
            }
            case TEXEL -> {
                this.requireBufferValue(value);
                this.dirtySrv = true;
            }
            case TEXTURE -> {
                this.requireTextureValue(slot, value);
                this.dirtySrv = true;
                this.dirtySmp = true;
            }
        }
    }

    void pushConstants(ByteBuffer value) {
        SlotLayout layout = this.layout;

        if (layout == null || layout.pushDwords() == 0) {
            return;
        }

        int bytes = Math.min(value.remaining(), layout.pushDwords() * 4);

        // raw bytes at call time (the caller frees its buffer right after): absolute bulk get, independent of the byte order
        Arrays.fill(this.push, (byte) 0);
        value.get(value.position(), this.push, 0, bytes);
        this.dirtyPush = true;
    }

    void enableScissor(int x, int y, int width, int height) {
        this.ops.setScissor(x, y, x + width, y + height);
    }

    void disableScissor() {
        this.setScissorToArea();
    }

    private void setScissorToArea() {
        PassSpec spec = this.spec;

        this.enableScissor(spec.areaX(), spec.areaY(), spec.areaWidth(), spec.areaHeight());
    }

    void setVertexBuffer(int slot, @Nullable GpuBufferSlice slice) {
        if (slot < 0 || slot >= VERTEX_SLOTS) {
            D3D12Log.warnOnce("vertex-slot:" + slot, "setVertexBuffer({}) is outside the {} vertex buffer slots and is ignored", slot, VERTEX_SLOTS);
            return;
        }

        GpuBufferSlice previous = this.vertexSlices[slot];

        this.vertexSlices[slot] = slice;

        if (slice != null && slice.buffer() instanceof D3D12Buffer buffer) {
            this.requireBuffer(buffer);
        }

        boolean same = previous != null && slice != null && previous.buffer() == slice.buffer() && previous.offset() == slice.offset();

        if (!same) {
            this.dirtyVb |= 1 << slot;
        }
    }

    void setIndexBuffer(GpuBuffer buffer, IndexType type) {
        boolean same = this.indexBuffer == buffer && this.indexType == type;

        this.indexBuffer = buffer;
        this.indexType = type;

        if (buffer instanceof D3D12Buffer d3d) {
            this.requireBuffer(d3d);
        }

        if (!same) {
            this.dirtyIb = true;
        }
    }

    void pushDebugGroup(Supplier<String> label) {
        if (!this.ctx.labels()) {
            return;
        }

        this.ops.beginEvent(label.get());
        this.groupDepth++;
    }

    void popDebugGroup() {
        if (!this.ctx.labels() || this.groupDepth == 0) {
            return;
        }

        this.groupDepth--;
        this.ops.endEvent();
    }

    void writeTimestamp(GpuQueryPool pool, int index) {
        this.ops.writeTimestamp(pool, index);
    }

    // =================================================================================================================
    // Bind-time state requests
    // =================================================================================================================

    private static boolean usable(D3D12Buffer buffer) {
        return buffer.isUsable() && buffer.mode() != BufferMode.READBACK;
    }

    private void requireBuffer(D3D12Buffer buffer) {
        TrackedResource tracked = buffer.tracked();

        if (tracked != null && usable(buffer)) {
            this.tracker.require(tracked, 0, ResourceStates.GR);
        }
    }

    private void requireBufferValue(@Nullable Object value) {
        if (value instanceof GpuBufferSlice slice && slice.buffer() instanceof D3D12Buffer buffer) {
            this.requireBuffer(buffer);
        }
    }

    private void requireTextureValue(SlotInfo slot, @Nullable Object value) {
        if (this.textureStatus(slot, value) != TEXTURE_OK) {
            return;
        }

        this.tracker.requireMips(this.values.tracked(value), this.values.textureMipLevels(value), this.values.baseMip(value),
                this.values.mipCount(value), this.values.layers(value), ResourceStates.SRV_ALL);
    }

    private int textureStatus(SlotInfo slot, @Nullable Object value) {
        if (value == null) {
            return TEXTURE_UNSET;
        }

        if (!this.values.isTexture(value)) {
            return TEXTURE_WRONG_TYPE;
        }

        if (!this.values.textureUsable(value)) {
            return TEXTURE_CLOSED;
        }

        if (this.values.cube(value) != slot.cube()) {
            return TEXTURE_DIMENSION;
        }

        // the view's subresources (mip range x all layers) against every attachment subresource of the same resource
        TrackedResource tracked = this.values.tracked(value);
        int mips = this.values.textureMipLevels(value);
        int base = this.values.baseMip(value);
        int count = this.values.mipCount(value);
        int layers = this.values.layers(value);

        for (int i = 0; i < this.attachmentCount; i++) {
            if (this.attachmentResource[i] == tracked
                    && ResourceStateTracker.subresourceInMipRange(this.attachmentSubresource[i], mips, base, count, layers)) {
                return TEXTURE_FEEDBACK;
            }
        }

        return TEXTURE_OK;
    }

    /** Safety net of the draw: re-requests every bound resource after something was moved away from a read state. */
    private void rerequireBound() {
        SlotLayout layout = this.layout;

        for (int i = 0; i < layout.size(); i++) {
            SlotInfo slot = layout.slot(i);

            if (!slot.active() || this.slotValues[i] == null) {
                continue;
            }

            if (slot.kind() == SlotKind.TEXTURE) {
                this.requireTextureValue(slot, this.slotValues[i]);
            } else {
                this.requireBufferValue(this.slotValues[i]);
            }
        }

        int mask = this.pipeline.vertexBufferMask();

        for (int slot = 0; slot < VERTEX_SLOTS; slot++) {
            GpuBufferSlice slice = this.vertexSlices[slot];

            if ((mask & (1 << slot)) != 0 && slice != null && slice.buffer() instanceof D3D12Buffer buffer) {
                this.requireBuffer(buffer);
            }
        }

        if (this.indexBuffer instanceof D3D12Buffer buffer) {
            this.requireBuffer(buffer);
        }
    }

    // =================================================================================================================
    // Draws
    // =================================================================================================================

    void drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance) {
        if (indexCount <= 0 || instanceCount <= 0) {
            return;
        }

        if (this.pipeline != null && this.pipeline.isFan()) {
            // an indexed fan draw assumes the identity index buffer the game uses for fans
            if (indexCount < 3) {
                return;
            }

            this.fan.ensure(indexCount);

            if (this.prepareDraw(true, firstIndex + vertexOffset, firstInstance, true)) {
                this.ops.drawIndexedInstanced((indexCount - 2) * 3, instanceCount, 0, firstIndex + vertexOffset, firstInstance);
            }

            return;
        }

        if (this.prepareDraw(true, vertexOffset, firstInstance, false)) {
            this.ops.drawIndexedInstanced(indexCount, instanceCount, firstIndex, vertexOffset, firstInstance);
        }
    }

    void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
        if (vertexCount <= 0 || instanceCount <= 0) {
            return;
        }

        if (this.pipeline != null && this.pipeline.isFan()) {
            if (vertexCount < 3) {
                return;
            }

            this.fan.ensure(vertexCount);

            if (this.prepareDraw(true, firstVertex, firstInstance, true)) {
                this.ops.drawIndexedInstanced((vertexCount - 2) * 3, instanceCount, 0, firstVertex, firstInstance);
            }

            return;
        }

        if (this.prepareDraw(false, firstVertex, firstInstance, false)) {
            this.ops.drawInstanced(vertexCount, instanceCount, firstVertex, firstInstance);
        }
    }

    void drawIndexedIndirect(GpuBufferSlice commands, int drawCount) {
        this.indirect(commands, drawCount, true);
    }

    void drawIndirect(GpuBufferSlice commands, int drawCount) {
        this.indirect(commands, drawCount, false);
    }

    private void indirect(GpuBufferSlice commands, int drawCount, boolean indexed) {
        if (drawCount <= 0) {
            return;
        }

        if (commands.offset() % 4 != 0) {
            throw new IllegalArgumentException("The indirect argument offset must be a multiple of 4: " + commands.offset());
        }

        if (this.pipeline == null) {
            this.ctx.errors().tolerate("indirect-no-pipeline", "An indirect draw without a pipeline is skipped");
            return;
        }

        if (this.pipeline.isFan()) {
            throw new UnsupportedOperationException("Indirect draws with a TRIANGLE_FAN pipeline are not supported by the Direct3D 12 backend (pipeline "
                    + this.pipeline.name() + ")");
        }

        if (!(commands.buffer() instanceof D3D12Buffer buffer) || !usable(buffer)) {
            this.ctx.errors().tolerate("indirect-buffer:" + this.pipeline.name(), "The indirect argument buffer of pipeline " + this.pipeline.name()
                    + " is closed or not a GPU-readable Direct3D 12 buffer: the draw is skipped");
            return;
        }

        int stride = indexed ? INDIRECT_INDEXED_STRIDE : INDIRECT_STRIDE;
        long fit = Math.min(commands.length() / stride, (buffer.size() - commands.offset()) / stride);
        int count = (int) Math.min(drawCount, Math.max(0L, fit));

        if (count <= 0) {
            return;
        }

        this.requireBuffer(buffer);

        if (this.pipeline.layout().usesVertexInfo()) {
            D3D12Log.warnOnce("indirect-vertex-info:" + this.pipeline.name(), "Pipeline {} reads gl_VertexIndex / gl_InstanceIndex: an indirect draw cannot supply "
                    + "per-draw values, so the base vertex and first instance are 0", this.pipeline.name());
        }

        if (this.prepareDraw(indexed, 0, 0, false)) {
            this.ops.executeIndirect(indexed, count, buffer, commands.offset());
        }
    }

    /**
     * The draw-time flush. Returns false when the draw is skipped (the tolerance policy warned once; strict mode throws).
     *
     * @param indexed    DrawIndexedInstanced semantics (selects the base-vertex rule of the vertex info)
     * @param base       {@code firstVertex} (non-indexed) or {@code BaseVertexLocation} (indexed)
     * @param useFan     bind the fan index buffer instead of the caller's
     */
    private boolean prepareDraw(boolean indexed, int base, int firstInstance, boolean useFan) {
        D3D12RenderPipeline pipeline = this.pipeline;
        SlotLayout layout = this.layout;

        // phase 1: validation without mutation
        if (pipeline == null) {
            this.ctx.errors().tolerate("draw-no-pipeline", "A draw without a pipeline is skipped");
            return false;
        }

        if (this.undrawable) {
            this.ctx.errors().tolerate("undrawable:" + pipeline.name(), "Pipeline " + pipeline.name()
                    + " has a depth state but the render pass has no depth attachment: its draws are skipped");
            return false;
        }

        int vertexMask = pipeline.vertexBufferMask();

        for (int slot = 0; slot < VERTEX_SLOTS; slot++) {
            if ((vertexMask & (1 << slot)) != 0 && !this.vertexUsable(slot)) {
                this.ctx.errors().tolerate("vertex-buffer:" + pipeline.name() + ":" + slot, "Vertex buffer slot " + slot + " of pipeline " + pipeline.name()
                        + " is not set or its buffer is closed: the draw is skipped");
                return false;
            }
        }

        if (indexed && !useFan && !(this.indexBuffer instanceof D3D12Buffer index && usable(index))) {
            this.ctx.errors().tolerate("index-buffer:" + pipeline.name(), "Pipeline " + pipeline.name()
                    + " draws indexed but no usable index buffer is set: the draw is skipped");
            return false;
        }

        // phase 2: something was moved away from a read state since the last validation (createBuffer(data) in a pass, ...)
        long serial = this.tracker.writeSerial();

        if (serial != this.validatedWriteSerial) {
            this.rerequireBound();
            this.validatedWriteSerial = serial;
        }

        // phase 3a: heaps - before ANY table is filled (a table handle must lie in the heap that is set, and only reserve flips)
        ShaderVisibleRing srvRing = this.ops.srvRing();
        ShaderVisibleRing samplerRing = this.ops.samplerRing();
        boolean heapsStale = srvRing.epoch() != this.boundSrvEpoch || samplerRing.epoch() != this.boundSmpEpoch;

        if (this.dirtySrv || this.dirtySmp || heapsStale) {
            srvRing.reserve(TABLE);
            samplerRing.reserve(TABLE);

            if (srvRing.epoch() != this.boundSrvEpoch || samplerRing.epoch() != this.boundSmpEpoch) {
                this.ops.setDescriptorHeaps();
                this.boundSrvEpoch = srvRing.epoch();
                this.boundSmpEpoch = samplerRing.epoch();
                // tables set earlier point into the old heap; the rings cleared their table caches themselves
                this.dirtySrv = this.usesSrvTable;
                this.dirtySmp = this.usesSamplerTable;
                this.boundSrvKeyValid = false;
                this.boundSmpKeyValid = false;
            }
        }

        // phase 3b
        if (this.dirtyPso) {
            if (this.pso != this.boundPso) {
                this.ops.setPipelineState(this.pso);
                this.boundPso = this.pso;
            }

            if (pipeline.topology() != this.boundTopology) {
                this.ops.setTopology(pipeline.topology());
                this.boundTopology = pipeline.topology();
            }

            this.dirtyPso = false;
        }

        if (this.dirtyCbv != 0) {
            this.bindConstantBuffers();
        }

        if (this.dirtySrv) {
            this.fillSrvTable(srvRing);
        }

        if (this.dirtySmp) {
            this.fillSamplerTable(samplerRing);
        }

        this.bindVertexBuffers(pipeline, vertexMask);

        if (indexed) {
            this.bindIndexBuffer(useFan);
        }

        if (this.dirtyPush && layout.pushDwords() > 0) {
            this.ops.setPushConstants(this.push, layout.pushDwords());
        }

        this.dirtyPush = false;

        if (layout.usesVertexInfo()) {
            int baseVertex = this.ctx.vertexIdSemantics().rootBaseVertex(indexed, base);

            if (!this.vertexInfoValid || baseVertex != this.lastBaseVertex || firstInstance != this.lastFirstInstance) {
                this.ops.setVertexInfo(baseVertex, firstInstance);
                this.lastBaseVertex = baseVertex;
                this.lastFirstInstance = firstInstance;
                this.vertexInfoValid = true;
            }
        }

        this.ops.flushBarriers();

        return true;
    }

    private boolean vertexUsable(int slot) {
        GpuBufferSlice slice = this.vertexSlices[slot];

        return slice != null && slice.buffer() instanceof D3D12Buffer buffer && usable(buffer) && slice.offset() >= 0 && slice.offset() < buffer.size();
    }

    // ---- root CBVs

    private void bindConstantBuffers() {
        SlotLayout layout = this.layout;
        int dirty = this.dirtyCbv;

        for (int register = 0; register < layout.cbvCount(); register++) {
            if ((dirty & (1 << register)) != 0) {
                int owner = layout.cbvOwner(register);

                this.ops.setRootCbv(register, owner < 0 ? this.ops.zeroCbvVa() : this.constantBufferAddress(owner));
            }
        }

        this.dirtyCbv = 0;
    }

    private long constantBufferAddress(int slotIndex) {
        SlotInfo slot = this.layout.slot(slotIndex);
        Object value = this.slotValues[slotIndex];

        if (!slot.active()) {
            // never bound: no transition, no lifetime; the register still holds a valid address
            return this.ops.zeroCbvVa();
        }

        if (value instanceof GpuBufferSlice slice && slice.buffer() instanceof D3D12Buffer buffer && usable(buffer)) {
            long address = buffer.gpuVa() + slice.offset();

            if (slice.offset() >= 0 && (address & 255L) == 0L) {
                return address;
            }

            this.substituted(slot, "uniform buffer '" + slot.name() + "' is bound at the misaligned address " + Long.toHexString(address) + " (offset "
                    + slice.offset() + "): a constant buffer view needs 256 byte alignment");
        } else if (value == null) {
            this.substituted(slot, "uniform '" + slot.name() + "' (" + slot.kind() + ") was never set");
        } else {
            this.substituted(slot, "uniform '" + slot.name() + "' was given " + describe(value) + " but is a uniform buffer, or the buffer is closed");
        }

        return this.ops.zeroCbvVa();
    }

    // ---- descriptor tables

    private void fillSrvTable(ShaderVisibleRing ring) {
        SlotLayout layout = this.layout;
        boolean cacheable = !layout.hasTexel();
        int texelMask = 0;

        for (int register = 0; register < TABLE; register++) {
            int slotIndex = layout.srvOwner(register);

            if (slotIndex < 0) {
                this.srvKey[register] = DescriptorTableCache.nullKey(DescriptorTableCache.NULL_TEXTURE_2D);
                this.srvSource[register] = this.ops.nullSrv2d();
                continue;
            }

            SlotInfo slot = layout.slot(slotIndex);
            Object value = this.slotValues[slotIndex];

            if (slot.kind() == SlotKind.TEXEL) {
                // the typed-buffer SRV is created in the ring after the copy; the copy source is a null SRV of the right class
                this.srvKey[register] = DescriptorTableCache.nullKey(bufferKind(slot.sampledBaseType()));
                this.srvSource[register] = this.ops.nullSrvBuffer(slot.sampledBaseType());
                texelMask |= 1 << register;
                continue;
            }

            int status = slot.active() ? this.textureStatus(slot, value) : TEXTURE_UNSET;

            if (status == TEXTURE_OK) {
                long handle = this.values.srv(value);

                this.srvKey[register] = handle;
                this.srvSource[register] = handle;
            } else {
                if (slot.active()) {
                    this.substituted(slot, textureProblem(slot, value, status));
                }

                this.srvKey[register] = DescriptorTableCache.nullKey(slot.cube() ? DescriptorTableCache.NULL_TEXTURE_CUBE : DescriptorTableCache.NULL_TEXTURE_2D);
                this.srvSource[register] = slot.cube() ? this.ops.nullSrvCube() : this.ops.nullSrv2d();
            }
        }

        // a table that is already bound, or one built earlier in this heap generation, is not rebuilt (never for texel layouts:
        // the key cannot see the typed-buffer SRV, which is created in the ring)
        if (cacheable) {
            if (this.boundSrvKeyValid && Arrays.equals(this.boundSrvKey, this.srvKey)) {
                this.dirtySrv = false;
                return;
            }

            long cached = ring.tableCache().find(this.srvKey);

            if (cached != 0L) {
                this.ops.setSrvTable(cached);
                System.arraycopy(this.srvKey, 0, this.boundSrvKey, 0, TABLE);
                this.boundSrvKeyValid = true;
                this.dirtySrv = false;
                return;
            }
        }

        long cpu = ring.allocateReserved(TABLE);
        long gpu = ring.gpuHandle(cpu);

        this.ops.copyTable(Enums.DescriptorHeapType.CBV_SRV_UAV, cpu, this.srvSource);

        for (int register = 0; register < TABLE; register++) {
            if ((texelMask & (1 << register)) != 0) {
                this.createTexelView(layout.slot(layout.srvOwner(register)), this.slotValues[layout.srvOwner(register)], cpu + (long) register * ring.incrementSize());
            }
        }

        if (cacheable) {
            ring.tableCache().insert(this.srvKey, gpu);
            System.arraycopy(this.srvKey, 0, this.boundSrvKey, 0, TABLE);
            this.boundSrvKeyValid = true;
        } else {
            this.boundSrvKeyValid = false;
        }

        this.ops.setSrvTable(gpu);
        this.dirtySrv = false;
    }

    private void createTexelView(SlotInfo slot, @Nullable Object value, long destination) {
        if (!slot.active()) {
            return;
        }

        if (value instanceof GpuBufferSlice slice && slice.buffer() instanceof D3D12Buffer buffer && usable(buffer) && slot.texelFormat() != null) {
            FormatInfo format = Formats.of(slot.texelFormat());
            long element = format.bytesPerTexel();
            long available = Math.min(slice.length(), buffer.size() - slice.offset());
            long count = available / element;

            if (slice.offset() >= 0 && count > 0) {
                this.ops.createTexelSrv(destination, buffer, format.srvFormat(), slice.offset() / element, (int) Math.min(count, Integer.MAX_VALUE));
                return;
            }

            this.substituted(slot, "texel buffer '" + slot.name() + "' has no whole element in the bound range");
            return;
        }

        this.substituted(slot, value == null ? "texel buffer '" + slot.name() + "' was never set"
                : "texel buffer '" + slot.name() + "' was given " + describe(value) + " or the buffer is closed");
    }

    private void fillSamplerTable(ShaderVisibleRing ring) {
        SlotLayout layout = this.layout;

        for (int register = 0; register < TABLE; register++) {
            int slotIndex = layout.srvOwner(register);

            this.smpKey[register] = DescriptorTableCache.nullKey(DescriptorTableCache.NULL_SAMPLER);
            this.smpSource[register] = this.ops.nullSampler();

            if (slotIndex < 0) {
                continue;
            }

            SlotInfo slot = layout.slot(slotIndex);

            if (slot.kind() != SlotKind.TEXTURE || !slot.active()) {
                continue;
            }

            // the SRV table reports a missing or unusable texture; the sampler follows the same verdict
            if (this.textureStatus(slot, this.slotValues[slotIndex]) == TEXTURE_OK) {
                long handle = this.values.samplerHandle(this.slotValues[slotIndex]);

                this.smpKey[register] = handle;
                this.smpSource[register] = handle;
            }
        }

        if (this.boundSmpKeyValid && Arrays.equals(this.boundSmpKey, this.smpKey)) {
            this.dirtySmp = false;
            return;
        }

        long cached = ring.tableCache().find(this.smpKey);

        if (cached != 0L) {
            this.ops.setSamplerTable(cached);
        } else {
            long cpu = ring.allocateReserved(TABLE);
            long gpu = ring.gpuHandle(cpu);

            this.ops.copyTable(Enums.DescriptorHeapType.SAMPLER, cpu, this.smpSource);
            ring.tableCache().insert(this.smpKey, gpu);
            this.ops.setSamplerTable(gpu);
        }

        System.arraycopy(this.smpKey, 0, this.boundSmpKey, 0, TABLE);
        this.boundSmpKeyValid = true;
        this.dirtySmp = false;
    }

    private static int bufferKind(int sampledBaseType) {
        return switch (sampledBaseType) {
            case Formats.BASE_INT -> DescriptorTableCache.NULL_BUFFER_INT;
            case Formats.BASE_UINT -> DescriptorTableCache.NULL_BUFFER_UINT;
            default -> DescriptorTableCache.NULL_BUFFER_FLOAT;
        };
    }

    // ---- geometry

    private void bindVertexBuffers(D3D12RenderPipeline pipeline, int mask) {
        int need = this.dirtyVb & mask;

        // the stride is part of the view: a pipeline with other strides re-issues the slots even if the buffers did not change
        for (int slot = 0; slot < VERTEX_SLOTS; slot++) {
            if ((mask & (1 << slot)) != 0 && this.issuedStride[slot] != pipeline.vertexStride(slot)) {
                need |= 1 << slot;
            }
        }

        if (need == 0) {
            return;
        }

        int slot = 0;

        while (slot < VERTEX_SLOTS) {
            if ((need & (1 << slot)) == 0) {
                slot++;
                continue;
            }

            int first = slot;

            while (slot < VERTEX_SLOTS && (need & (1 << slot)) != 0) {
                GpuBufferSlice slice = this.vertexSlices[slot];
                D3D12Buffer buffer = (D3D12Buffer) slice.buffer();

                this.vbVa[slot] = buffer.gpuVa() + slice.offset();
                this.vbSize[slot] = (int) Math.min(buffer.size() - slice.offset(), 0x7FFFFFFFL);
                this.vbStride[slot] = pipeline.vertexStride(slot);
                this.issuedStride[slot] = this.vbStride[slot];
                slot++;
            }

            this.ops.setVertexBuffers(first, slot - first, this.vbVa, this.vbSize, this.vbStride);
        }

        this.dirtyVb &= ~mask;
    }

    private void bindIndexBuffer(boolean useFan) {
        if (useFan) {
            long va = this.fan.gpuVa();
            int size = this.fan.sizeInBytes();

            // growing replaces the buffer: the view must follow, or indices beyond the old size would read as 0
            if (!this.fanIndexBound || va != this.boundFanVa || size != this.boundFanSize) {
                this.ops.setIndexBuffer(va, size, Enums.Format.R32_UINT);
                this.boundFanVa = va;
                this.boundFanSize = size;
                this.fanIndexBound = true;
                // the caller's index buffer must be bound again before its next indexed draw
                this.dirtyIb = true;
            }

            return;
        }

        if (!this.dirtyIb && !this.fanIndexBound) {
            return;
        }

        D3D12Buffer buffer = (D3D12Buffer) this.indexBuffer;

        this.ops.setIndexBuffer(buffer.gpuVa(), (int) Math.min(buffer.size(), Integer.MAX_VALUE),
                this.indexType == IndexType.SHORT ? Enums.Format.R16_UINT : Enums.Format.R32_UINT);
        this.fanIndexBound = false;
        this.dirtyIb = false;
    }

    // ---- diagnostics

    private void substituted(SlotInfo slot, String problem) {
        this.ctx.errors().tolerate("uniform:" + this.pipeline.name() + ":" + slot.name(),
                "Pipeline " + this.pipeline.name() + ": " + problem + "; a null / zero resource is substituted");
    }

    private static String textureProblem(SlotInfo slot, @Nullable Object value, int status) {
        return switch (status) {
            case TEXTURE_UNSET -> "sampler '" + slot.name() + "' was never set";
            case TEXTURE_WRONG_TYPE -> "sampler '" + slot.name() + "' was given " + describe(value) + " instead of a texture view and sampler";
            case TEXTURE_CLOSED -> "the texture view or the sampler of '" + slot.name() + "' is closed or was not created by this backend";
            case TEXTURE_DIMENSION -> "the texture of '" + slot.name() + "' is " + (slot.cube() ? "not a cube map" : "a cube map")
                    + " but the shader declares " + (slot.cube() ? "a cube" : "a 2D") + " sampler";
            default -> "the texture of '" + slot.name() + "' is also an attachment of this render pass (feedback loop)";
        };
    }

    private static String describe(@Nullable Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }
}
