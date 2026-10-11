// Owner: WP4 (surface + entry) - implemented
package net.caffeinemc.mods.sodium.client.d3d12.probe;

import com.mojang.renderpearl.api.device.GpuDeviceLossException;
import net.caffeinemc.mods.sodium.client.d3d12.core.D3D12Log;
import net.caffeinemc.mods.sodium.client.d3d12.core.DeviceContext;
import net.caffeinemc.mods.sodium.client.d3d12.core.VertexIdSemantics;
import net.caffeinemc.mods.sodium.client.d3d12.interop.D3D12Exception;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Enums;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12CommandAllocator;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12DescriptorHeap;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12Device;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12GraphicsCommandList;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12Resource;
import net.caffeinemc.mods.sodium.client.d3d12.pipeline.BuiltinPipelines;
import net.caffeinemc.mods.sodium.client.d3d12.pipeline.D3D12RenderPipeline;
import net.caffeinemc.mods.sodium.client.d3d12.pipeline.SlotLayout;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

/**
 * Device-level self-tests that remove an unknown from the correctness argument by measuring it on the adapter in use.
 *
 * <h2>{@code SV_VertexID} semantics (decision D12)</h2>
 * Vulkan's {@code gl_VertexIndex} includes {@code firstVertex} / {@code vertexOffset}; HLSL's {@code SV_VertexID} does so
 * on vkd3d: <b>not</b> (measured, spec 02 section 6.6), and native D3D12 is documented but unverified. One vanilla shader
 * depends on it: {@code animate_sprite.vsh} reads the interpolation progress from {@code firstVertex}
 * ({@code draw(6, 1, progress << 3, 0)}). {@code measure} runs a micro-test on the real runtime: two 16x1 {@code R32_UINT}
 * targets, a built-in pipeline (through {@code BuiltinPipelines.fromHlsl}) whose vertex shader places a point at pixel
 * {@code SV_VertexID} and whose pixel shader writes {@code id + 1} (so that id 0 is distinguishable from "not written"),
 * three points drawn with {@code DrawInstanced(.., StartVertexLocation = 5)} into the first target and, indexed with the
 * indices 0, 1, 2, {@code DrawIndexedInstanced(.., BaseVertexLocation = 7)} into the second; the result is read back and
 * decides {@link VertexIdSemantics#nonIndexedIncludesStart()} / {@link VertexIdSemantics#indexedIncludesBase()} (observed
 * ids {0,1,2} or {5,6,7} resp. {0,1,2} or {7,8,9}; the largest id, 9, is below the 16 pixels, and one target per draw
 * keeps the two patterns from aliasing).
 *
 * <p>Isolation rules: uses its own command allocator + list, its own descriptors, textures, buffers and its own idle wait
 * on the clock's auxiliary fence ({@code flushGpu}: it never touches the frame fence, the frame list, the tracker or the
 * rings - the probe runs before the first frame); everything it creates is released before returning. A failure of the
 * <em>measurement</em> (compile, PSO, readback, garbage pattern) is logged and answered with
 * {@link VertexIdSemantics#DEFAULT} (the vkd3d measurement). A <b>lost device</b> is different:
 * {@code GpuDeviceLossException} (and {@code VirtualMachineError}) are rethrown, never swallowed, and
 * {@code D3D12GpuDevice.create} additionally calls {@code errors().checkDeviceRemoved("start-up probe")} +
 * {@code checkHealthy()} outside the probe's catch (a device removed by the probe's draw can also surface only as a failed
 * fence wait or an HRESULT), so such a device never reaches the first frame.
 *
 * <p>This is the one documented exception to the rule that GPU commands are issued by a short list of classes
 * (invariant I3): the probe records on its <em>own</em> allocator + list and never touches the frame list, the tracker or
 * the rings.
 */
public final class StartupProbes {
    /** Pixels per probe target; the largest observable id (9) must be smaller. */
    static final int TARGET_WIDTH = 16;
    static final int DRAW_START_VERTEX = 5;
    static final int DRAW_BASE_VERTEX = 7;
    static final long IDLE_TIMEOUT_NS = 5_000_000_000L;

    /** The probe shaders use no resource, so no root argument besides the root signature itself is bound. */
    static final String HLSL = """
            struct VsOut
            {
                float4 position : SV_Position;
                nointerpolation uint id : TEXCOORD0;
            };

            VsOut vs(uint id : SV_VertexID)
            {
                VsOut o;
                o.position = float4((id + 0.5) / 16.0 * 2.0 - 1.0, 0.0, 0.0, 1.0);
                o.id = id;
                return o;
            }

            uint ps(VsOut i) : SV_Target0
            {
                return i.id + 1u;
            }
            """;

    private StartupProbes() {
    }

    /**
     * @return the measured semantics, or {@link VertexIdSemantics#DEFAULT} if the probe could not run
     * @throws GpuDeviceLossException if the device was lost while probing
     */
    public static VertexIdSemantics measure(DeviceContext ctx) {
        return measure(ctx, () -> measureOnDevice(ctx));
    }

    /**
     * The failure policy around {@code body}: a measurement failure is logged and answered with
     * {@link VertexIdSemantics#DEFAULT}; {@link GpuDeviceLossException}, a device-loss {@link D3D12Exception} (translated)
     * and {@link VirtualMachineError} are rethrown.
     */
    static VertexIdSemantics measure(DeviceContext ctx, Supplier<VertexIdSemantics> body) {
        try {
            return body.get();
        } catch (GpuDeviceLossException | VirtualMachineError e) {
            throw e;
        } catch (D3D12Exception e) {
            if (e.isDeviceLoss()) {
                throw ctx.errors().translate(e, "start-up probe");
            }

            D3D12Log.warn("The Direct3D 12 start-up probe failed, assuming SV_VertexID excludes the base vertex: {}", e.toString());

            return VertexIdSemantics.DEFAULT;
        } catch (Throwable t) {
            D3D12Log.warn("The Direct3D 12 start-up probe failed, assuming SV_VertexID excludes the base vertex", t);

            return VertexIdSemantics.DEFAULT;
        }
    }

    /**
     * The decision from the observed vertex ids.
     *
     * @param nonIndexed ids seen by {@code DrawInstanced(3, 1, 5, 0)}: {0,1,2} = start not included, {5,6,7} = included
     * @param indexed    ids seen by {@code DrawIndexedInstanced(3, 1, 0, 7, 0)} with indices 0,1,2: {0,1,2} = base not
     *                   included, {7,8,9} = included
     * @return the semantics, or {@link VertexIdSemantics#DEFAULT} (with a WARN) if either pattern matches neither answer
     */
    static VertexIdSemantics decide(int[] nonIndexed, int[] indexed) {
        int[] a = nonIndexed.clone();
        int[] b = indexed.clone();
        Arrays.sort(a);
        Arrays.sort(b);

        Boolean start = classify(a, DRAW_START_VERTEX);
        Boolean base = classify(b, DRAW_BASE_VERTEX);

        if (start == null || base == null) {
            D3D12Log.warn("The Direct3D 12 start-up probe observed unexpected SV_VertexID patterns {} (DrawInstanced, start {}) and {} "
                    + "(DrawIndexedInstanced, base {}); assuming SV_VertexID excludes the base vertex",
                    Arrays.toString(a), DRAW_START_VERTEX, Arrays.toString(b), DRAW_BASE_VERTEX);

            return VertexIdSemantics.DEFAULT;
        }

        return new VertexIdSemantics(start, base);
    }

    /** @return false for {0,1,2}, true for {offset, offset+1, offset+2}, null for anything else */
    private static @Nullable Boolean classify(int[] sorted, int offset) {
        if (Arrays.equals(sorted, new int[]{0, 1, 2})) {
            return Boolean.FALSE;
        }

        if (Arrays.equals(sorted, new int[]{offset, offset + 1, offset + 2})) {
            return Boolean.TRUE;
        }

        return null;
    }

    /** The micro-test itself; see the class comment. Any exception is a measurement failure for {@link #measure}. */
    private static VertexIdSemantics measureOnDevice(DeviceContext ctx) {
        D3D12Device device = ctx.d3d();
        List<AutoCloseable> owned = new ArrayList<>();
        D3D12RenderPipeline pipeline = null;
        boolean gpuMayStillUseObjects = false;

        try {
            pipeline = BuiltinPipelines.fromHlsl(ctx, "start-up probe", HLSL, SlotLayout.of(List.of(), 0, false),
                    Enums.Format.R32_UINT, Enums.PrimitiveTopologyType.POINT,
                    Enums.PrimitiveTopology.D3D10_PRIMITIVE_TOPOLOGY_POINTLIST);

            if (pipeline.psoNoDepth() == null) {
                throw new IllegalStateException("The probe pipeline has no depth-less variant");
            }

            D3D12Resource[] targets = new D3D12Resource[2];

            for (int i = 0; i < targets.length; i++) {
                targets[i] = device.createCommittedTexture2D(Enums.Format.R32_UINT, TARGET_WIDTH, 1, 1, 1, 1,
                        Enums.ResourceFlags.ALLOW_RENDER_TARGET, Enums.ResourceStates.COMMON, MemorySegment.NULL);
                owned.add(targets[i]);
            }

            D3D12DescriptorHeap rtvHeap = device.createDescriptorHeap(Enums.DescriptorHeapType.RTV, 2, false);
            owned.add(rtvHeap);
            int increment = device.getDescriptorHandleIncrementSize(Enums.DescriptorHeapType.RTV);
            long[] rtv = {
                    D3D12DescriptorHeap.handleAt(rtvHeap.getCPUDescriptorHandleForHeapStart(), 0, increment),
                    D3D12DescriptorHeap.handleAt(rtvHeap.getCPUDescriptorHandleForHeapStart(), 1, increment)
            };

            device.createRenderTargetView(targets[0], null, rtv[0]);
            device.createRenderTargetView(targets[1], null, rtv[1]);

            // indices 0, 1, 2 (R16_UINT) for the indexed draw
            D3D12Resource indices = device.createCommittedBuffer(Enums.HeapType.UPLOAD, 256, Enums.ResourceFlags.NONE,
                    Enums.ResourceStates.GENERIC_READ);
            owned.add(indices);
            MemorySegment indexData = indices.mapForWrite(0, 256);
            indexData.set(ValueLayout.JAVA_SHORT_UNALIGNED, 0, (short) 0);
            indexData.set(ValueLayout.JAVA_SHORT_UNALIGNED, 2, (short) 1);
            indexData.set(ValueLayout.JAVA_SHORT_UNALIGNED, 4, (short) 2);
            indices.unmap(0, 0L, 6L);

            // two 256-byte pitched rows at footprint offsets 0 and 512
            D3D12Resource readback = device.createCommittedBuffer(Enums.HeapType.READBACK, 1024, Enums.ResourceFlags.NONE,
                    Enums.ResourceStates.COPY_DEST);
            owned.add(readback);

            D3D12CommandAllocator allocator = device.createCommandAllocator(Enums.CommandListType.DIRECT);
            owned.add(allocator);
            D3D12GraphicsCommandList list = device.createCommandList(Enums.CommandListType.DIRECT, allocator);
            owned.add(list);

            record(ctx, list, pipeline, targets, rtv, indices, readback);

            list.closeList();
            gpuMayStillUseObjects = true;
            ctx.queue().executeCommandLists(list);
            ctx.clock().flushGpu(IDLE_TIMEOUT_NS);
            gpuMayStillUseObjects = false;

            int[] first = observedIds(readback, 0);
            int[] second = observedIds(readback, 512);
            VertexIdSemantics result = decide(first, second);

            D3D12Log.info("Direct3D 12 SV_VertexID semantics: DrawInstanced(start {}) observed {}, DrawIndexedInstanced(base {}) observed {} -> {}",
                    DRAW_START_VERTEX, Arrays.toString(first), DRAW_BASE_VERTEX, Arrays.toString(second), result);

            return result;
        } finally {
            if (gpuMayStillUseObjects) {
                // the idle wait did not complete: releasing what the GPU may still read is worse than leaking a few objects
                D3D12Log.warn("The start-up probe did not finish on the GPU; its {} objects are not released", owned.size());
            } else {
                for (int i = owned.size() - 1; i >= 0; i--) {
                    try {
                        owned.get(i).close();
                    } catch (Exception e) {
                        D3D12Log.warn("Could not release a start-up probe object: {}", e.toString());
                    }
                }
            }

            if (pipeline != null) {
                try {
                    pipeline.close();
                } catch (RuntimeException e) {
                    D3D12Log.warn("Could not release the start-up probe pipeline: {}", e.toString());
                }
            }
        }
    }

    private static void record(DeviceContext ctx, D3D12GraphicsCommandList list, D3D12RenderPipeline pipeline,
                               D3D12Resource[] targets, long[] rtv, D3D12Resource indices, D3D12Resource readback) {
        list.resourceBarrierTransition(targets[0], Enums.ResourceStates.COMMON, Enums.ResourceStates.RENDER_TARGET);
        list.resourceBarrierTransition(targets[1], Enums.ResourceStates.COMMON, Enums.ResourceStates.RENDER_TARGET);

        list.setGraphicsRootSignature(ctx.rootSignatures().global());
        list.setPipelineState(pipeline.psoNoDepth());
        list.iaSetPrimitiveTopology(pipeline.topology());
        list.rsSetViewport(0.0F, 0.0F, TARGET_WIDTH, 1.0F, 0.0F, 1.0F);
        list.rsSetScissorRect(0, 0, TARGET_WIDTH, 1);

        // draw 1: non-indexed, StartVertexLocation = 5
        list.omSetRenderTarget(rtv[0], D3D12GraphicsCommandList.NO_DESCRIPTOR);
        list.clearRenderTargetView(rtv[0], 0.0F, 0.0F, 0.0F, 0.0F);
        list.drawInstanced(3, 1, DRAW_START_VERTEX, 0);

        // draw 2: indexed (0, 1, 2), BaseVertexLocation = 7, into the other target
        list.omSetRenderTarget(rtv[1], D3D12GraphicsCommandList.NO_DESCRIPTOR);
        list.clearRenderTargetView(rtv[1], 0.0F, 0.0F, 0.0F, 0.0F);
        list.iaSetIndexBuffer(indices.getGPUVirtualAddress(), 6, Enums.Format.R16_UINT);
        list.drawIndexedInstanced(3, 1, 0, DRAW_BASE_VERTEX, 0);

        list.resourceBarrierTransition(targets[0], Enums.ResourceStates.RENDER_TARGET, Enums.ResourceStates.COPY_SOURCE);
        list.resourceBarrierTransition(targets[1], Enums.ResourceStates.RENDER_TARGET, Enums.ResourceStates.COPY_SOURCE);
        list.copyTextureToBuffer(readback, 0L, Enums.Format.R32_UINT, TARGET_WIDTH, 1, 1, 256, targets[0], 0, MemorySegment.NULL);
        list.copyTextureToBuffer(readback, 512L, Enums.Format.R32_UINT, TARGET_WIDTH, 1, 1, 256, targets[1], 0, MemorySegment.NULL);
    }

    /** @return the vertex ids (pixel value minus one) of the written pixels of one target, left to right */
    private static int[] observedIds(D3D12Resource readback, long offset) {
        MemorySegment data = readback.map(0, 0L, 1024L, 1024L);
        int[] ids = new int[TARGET_WIDTH];
        int count = 0;

        try {
            for (int x = 0; x < TARGET_WIDTH; x++) {
                int value = data.get(ValueLayout.JAVA_INT_UNALIGNED, offset + 4L * x);

                if (value != 0) {
                    ids[count++] = value - 1;
                }
            }
        } finally {
            readback.unmapUnwritten(0);
        }

        return Arrays.copyOf(ids, count);
    }
}
