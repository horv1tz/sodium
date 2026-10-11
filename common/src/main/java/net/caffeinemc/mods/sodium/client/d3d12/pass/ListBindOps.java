// Owner: WP3 (pipelines + render pass) - implemented
package net.caffeinemc.mods.sodium.client.d3d12.pass;

import com.mojang.renderpearl.api.commands.GpuQueryPool;
import net.caffeinemc.mods.sodium.client.d3d12.command.ClearEngine;
import net.caffeinemc.mods.sodium.client.d3d12.command.CommandRecorder;
import net.caffeinemc.mods.sodium.client.d3d12.command.D3D12QueryPool;
import net.caffeinemc.mods.sodium.client.d3d12.core.D3D12Log;
import net.caffeinemc.mods.sodium.client.d3d12.core.DeviceContext;
import net.caffeinemc.mods.sodium.client.d3d12.descriptor.NullDescriptors;
import net.caffeinemc.mods.sodium.client.d3d12.descriptor.ShaderVisibleRing;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Enums;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12BufferSrv;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12ShaderResourceViewDesc;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12VertexBufferView;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12GraphicsCommandList;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12PipelineState;
import net.caffeinemc.mods.sodium.client.d3d12.pipeline.RootSignatures;
import net.caffeinemc.mods.sodium.client.d3d12.resource.D3D12Buffer;
import net.caffeinemc.mods.sodium.client.d3d12.resource.RenderTargetRef;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * The production {@link BindOps}: forwards to the open command list of the {@link CommandRecorder}, to the device and
 * to the descriptor services of the context. The list is fetched per call ({@code recorder.list()}) because it is
 * {@code Reset} every frame; the native argument scratch (render target handles, vertex buffer views, the root constant
 * array, the descriptor range arrays, the SRV description) is allocated once, on first use, in one shared arena that
 * {@link #close()} releases (the constructor touches nothing native).
 *
 * <p>Threading: render thread.
 */
final class ListBindOps implements BindOps, AutoCloseable {
    private static final int TABLE = 16;
    /** {@code D3D12_DEFAULT_SHADER_4_COMPONENT_MAPPING} (header-checked: 0 | 1 << 3 | 2 << 6 | 3 << 9 | 1 << 12). */
    private static final int DEFAULT_COMPONENT_MAPPING = 0x1688;

    private final DeviceContext ctx;
    private final CommandRecorder recorder;
    private final ClearEngine clears;

    private Arena arena;
    private MemorySegment rtvHandles;
    private MemorySegment dsvHandle;
    private MemorySegment vertexViews;
    private MemorySegment pushScratch;
    private MemorySegment vertexInfo;
    private MemorySegment dstStart;
    private MemorySegment dstSize;
    private MemorySegment srcStarts;
    private MemorySegment srvDesc;

    ListBindOps(DeviceContext ctx, CommandRecorder recorder, ClearEngine clears) {
        this.ctx = ctx;
        this.recorder = recorder;
        this.clears = clears;
    }

    private void scratch() {
        if (this.arena != null) {
            return;
        }

        Arena created = Arena.ofShared();

        this.rtvHandles = created.allocate(8L * 8, 8);
        this.dsvHandle = created.allocate(8, 8);
        this.vertexViews = D3D12VertexBufferView.allocateArray(created, 16);
        this.pushScratch = created.allocate(128, 8);
        this.vertexInfo = created.allocate(8, 8);
        this.dstStart = created.allocate(8, 8);
        this.dstSize = created.allocate(4, 4);
        this.srcStarts = created.allocate(8L * TABLE, 8);
        this.srvDesc = D3D12ShaderResourceViewDesc.allocate(created);
        this.arena = created;
    }

    private D3D12GraphicsCommandList list() {
        return this.recorder.list();
    }

    // ---- descriptor services

    @Override
    public ShaderVisibleRing srvRing() {
        return this.ctx.descriptors().srvRing();
    }

    @Override
    public ShaderVisibleRing samplerRing() {
        return this.ctx.descriptors().samplerRing();
    }

    @Override
    public long nullSrv2d() {
        return this.ctx.descriptors().nulls().srv2d();
    }

    @Override
    public long nullSrvCube() {
        return this.ctx.descriptors().nulls().srvCube();
    }

    @Override
    public long nullSrvBuffer(int sampledBaseType) {
        NullDescriptors nulls = this.ctx.descriptors().nulls();

        return nulls.srvBuffer(sampledBaseType);
    }

    @Override
    public long nullSampler() {
        return this.ctx.descriptors().nulls().sampler();
    }

    @Override
    public long nullRtv() {
        return this.ctx.descriptors().nulls().rtv();
    }

    @Override
    public long zeroCbvVa() {
        return this.ctx.descriptors().nulls().zeroCbvVa();
    }

    // ---- pass begin

    @Override
    public void setHeapsAndRootSignature() {
        D3D12GraphicsCommandList list = this.list();

        list.setDescriptorHeaps(this.srvRing().currentHeap(), this.samplerRing().currentHeap());
        list.setGraphicsRootSignature(this.ctx.rootSignatures().global());
    }

    @Override
    public void setDescriptorHeaps() {
        this.list().setDescriptorHeaps(this.srvRing().currentHeap(), this.samplerRing().currentHeap());
    }

    @Override
    public void setRenderTargets(long[] rtv, int count, long dsv) {
        this.scratch();

        for (int i = 0; i < count; i++) {
            this.rtvHandles.setAtIndex(ValueLayout.JAVA_LONG, i, rtv[i]);
        }

        MemorySegment depth = MemorySegment.NULL;

        if (dsv != 0L) {
            this.dsvHandle.set(ValueLayout.JAVA_LONG, 0, dsv);
            depth = this.dsvHandle;
        }

        this.list().omSetRenderTargets(count, count == 0 ? MemorySegment.NULL : this.rtvHandles, false, depth);
    }

    @Override
    public void clearRtv(RenderTargetRef target, float r, float g, float b, float a, int x, int y, int w, int h) {
        this.clears.clearRtv(target, r, g, b, a, x, y, w, h);
    }

    @Override
    public void clearDsv(RenderTargetRef target, float depth, int x, int y, int w, int h) {
        this.clears.clearDsv(target, depth, x, y, w, h);
    }

    @Override
    public void setViewport(int width, int height) {
        this.list().rsSetViewport(0.0f, 0.0f, width, height, 0.0f, 1.0f);
    }

    @Override
    public void setScissor(int left, int top, int right, int bottom) {
        this.list().rsSetScissorRect(left, top, right, bottom);
    }

    @Override
    public void setBlendFactorZero() {
        this.list().omSetBlendFactor(0.0f, 0.0f, 0.0f, 0.0f);
    }

    @Override
    public void beginEvent(String label) {
        this.list().beginEvent(label);
    }

    @Override
    public void endEvent() {
        this.list().endEvent();
    }

    @Override
    public void flushBarriers() {
        this.ctx.tracker().flush(this.list());
    }

    // ---- draw state

    @Override
    public void setPipelineState(D3D12PipelineState pso) {
        this.list().setPipelineState(pso);
    }

    @Override
    public void setTopology(int topology) {
        this.list().iaSetPrimitiveTopology(topology);
    }

    @Override
    public void setRootCbv(int register, long gpuVa) {
        this.list().setGraphicsRootConstantBufferView(RootSignatures.PARAM_CBV0 + register, gpuVa);
    }

    @Override
    public void copyTable(int heapType, long dstCpu, long[] sources) {
        this.scratch();

        this.dstStart.set(ValueLayout.JAVA_LONG, 0, dstCpu);
        this.dstSize.set(ValueLayout.JAVA_INT, 0, TABLE);

        for (int i = 0; i < TABLE; i++) {
            this.srcStarts.setAtIndex(ValueLayout.JAVA_LONG, i, sources[i]);
        }

        this.ctx.d3d().copyDescriptors(1, this.dstStart, this.dstSize, TABLE, this.srcStarts, MemorySegment.NULL, heapType);
    }

    @Override
    public void createTexelSrv(long dstCpu, D3D12Buffer buffer, int dxgiFormat, long firstElement, int numElements) {
        this.scratch();

        MemorySegment desc = this.srvDesc;
        MemorySegment view = D3D12ShaderResourceViewDesc.sliceBuffer(desc);

        D3D12ShaderResourceViewDesc.setFormat(desc, dxgiFormat);
        D3D12ShaderResourceViewDesc.setViewDimension(desc, Enums.SrvDimension.BUFFER);
        D3D12ShaderResourceViewDesc.setShader4ComponentMapping(desc, DEFAULT_COMPONENT_MAPPING);
        D3D12BufferSrv.setFirstElement(view, firstElement);
        D3D12BufferSrv.setNumElements(view, numElements);
        D3D12BufferSrv.setStructureByteStride(view, 0);
        D3D12BufferSrv.setFlags(view, 0);

        this.ctx.d3d().createShaderResourceView(buffer.resource(), desc, dstCpu);
    }

    @Override
    public void setSrvTable(long gpuHandle) {
        this.list().setGraphicsRootDescriptorTable(RootSignatures.PARAM_SRV_TABLE, gpuHandle);
    }

    @Override
    public void setSamplerTable(long gpuHandle) {
        this.list().setGraphicsRootDescriptorTable(RootSignatures.PARAM_SAMPLER_TABLE, gpuHandle);
    }

    @Override
    public void setPushConstants(byte[] bytes, int dwords) {
        this.scratch();

        MemorySegment.copy(bytes, 0, this.pushScratch, ValueLayout.JAVA_BYTE, 0, dwords * 4);
        this.list().setGraphicsRoot32BitConstants(RootSignatures.PARAM_PUSH, dwords, this.pushScratch, 0);
    }

    @Override
    public void setVertexInfo(int baseVertex, int firstInstance) {
        this.scratch();

        this.vertexInfo.set(ValueLayout.JAVA_INT, 0, baseVertex);
        this.vertexInfo.set(ValueLayout.JAVA_INT, 4, firstInstance);
        this.list().setGraphicsRoot32BitConstants(RootSignatures.PARAM_VERTEX_INFO, RootSignatures.VERTEX_INFO_DWORDS, this.vertexInfo, 0);
    }

    @Override
    public void setVertexBuffers(int first, int count, long[] gpuVa, int[] sizeInBytes, int[] strideInBytes) {
        this.scratch();

        for (int i = 0; i < count; i++) {
            MemorySegment view = this.vertexViews.asSlice(i * D3D12VertexBufferView.SIZE, D3D12VertexBufferView.SIZE);

            D3D12VertexBufferView.setBufferLocation(view, gpuVa[first + i]);
            D3D12VertexBufferView.setSizeInBytes(view, sizeInBytes[first + i]);
            D3D12VertexBufferView.setStrideInBytes(view, strideInBytes[first + i]);
        }

        this.list().iaSetVertexBuffers(first, count, this.vertexViews);
    }

    @Override
    public void setIndexBuffer(long gpuVa, int sizeInBytes, int dxgiFormat) {
        this.list().iaSetIndexBuffer(gpuVa, sizeInBytes, dxgiFormat);
    }

    @Override
    public void drawInstanced(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
        this.list().drawInstanced(vertexCount, instanceCount, firstVertex, firstInstance);
    }

    @Override
    public void drawIndexedInstanced(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance) {
        this.list().drawIndexedInstanced(indexCount, instanceCount, firstIndex, vertexOffset, firstInstance);
    }

    @Override
    public void executeIndirect(boolean indexed, int count, D3D12Buffer arguments, long offset) {
        RootSignatures signatures = this.ctx.rootSignatures();

        this.list().executeIndirect(indexed ? signatures.drawIndexed() : signatures.draw(), count, arguments.resource(), offset, null, 0L);
    }

    @Override
    public void writeTimestamp(GpuQueryPool pool, int index) {
        if (!(pool instanceof D3D12QueryPool query)) {
            throw new IllegalArgumentException("The query pool was not created by the Direct3D 12 backend: " + pool);
        }

        query.write(this.list(), index);
    }

    /** Releases the argument scratch; idempotent. */
    @Override
    public void close() {
        Arena released = this.arena;

        this.arena = null;

        if (released != null) {
            try {
                released.close();
            } catch (RuntimeException e) {
                D3D12Log.error("Failed to release the pass binder scratch memory", e);
            }
        }
    }
}
