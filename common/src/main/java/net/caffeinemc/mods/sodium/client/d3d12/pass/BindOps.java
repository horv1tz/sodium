// Owner: WP3 (pipelines + render pass) - implemented
package net.caffeinemc.mods.sodium.client.d3d12.pass;

import com.mojang.renderpearl.api.commands.GpuQueryPool;
import net.caffeinemc.mods.sodium.client.d3d12.descriptor.ShaderVisibleRing;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12PipelineState;
import net.caffeinemc.mods.sodium.client.d3d12.resource.D3D12Buffer;
import net.caffeinemc.mods.sodium.client.d3d12.resource.RenderTargetRef;

/**
 * The native side of the {@link PassBinder}: every call that reaches the command list, the device or the descriptor
 * services goes through this interface, so that the binder's logic (dirty flags, the descriptor-table protocol, the
 * tolerance policy) runs on Linux against a recording fake ({@code PassBinderLogicTest}). The production implementation
 * is {@link ListBindOps}.
 *
 * <p>Allocation: the arrays handed to the methods are owned by the binder and reused; implementations must not retain
 * them. Hot-path methods allocate nothing.
 */
interface BindOps {
    // ---- descriptor services

    ShaderVisibleRing srvRing();

    ShaderVisibleRing samplerRing();

    /** @return CPU handle of the null {@code Texture2D} SRV */
    long nullSrv2d();

    /** @return CPU handle of the null {@code TextureCube} SRV */
    long nullSrvCube();

    /** @param sampledBaseType {@code Formats.BASE_INT / BASE_UINT / BASE_FLOAT} */
    long nullSrvBuffer(int sampledBaseType);

    /** @return CPU handle of the default sampler descriptor */
    long nullSampler();

    /** @return CPU handle of the null render target view (holes of the colour attachment list) */
    long nullRtv();

    /** @return GPU address of the zeroed constant buffer that stands in for every unset root CBV */
    long zeroCbvVa();

    // ---- pass begin

    /** {@code SetDescriptorHeaps(srvRing.currentHeap(), samplerRing.currentHeap())} then {@code SetGraphicsRootSignature(global)}. */
    void setHeapsAndRootSignature();

    /** {@code SetDescriptorHeaps} for the current pages of both rings. */
    void setDescriptorHeaps();

    /**
     * @param rtv   CPU handles of the colour render target views (holes already replaced by the null RTV)
     * @param count number of render targets
     * @param dsv   CPU handle of the depth view, 0 for none
     */
    void setRenderTargets(long[] rtv, int count, long dsv);

    /** Load-op clear of a colour attachment; {@code w <= 0} clears the whole attachment, else the rect {@code {x, y, x + w, y + h}}. */
    void clearRtv(RenderTargetRef target, float r, float g, float b, float a, int x, int y, int w, int h);

    void clearDsv(RenderTargetRef target, float depth, int x, int y, int w, int h);

    /** {@code RSSetViewports} with {@code {0, 0, width, height, 0, 1}}. */
    void setViewport(int width, int height);

    /** {@code RSSetScissorRects} with {@code {left, top, right, bottom}}. */
    void setScissor(int left, int top, int right, int bottom);

    /** {@code OMSetBlendFactor(0, 0, 0, 0)}. */
    void setBlendFactorZero();

    void beginEvent(String label);

    void endEvent();

    /** {@code tracker.flush(list)}: records the pending barriers. */
    void flushBarriers();

    // ---- draw state

    void setPipelineState(D3D12PipelineState pso);

    /** {@code IASetPrimitiveTopology}. */
    void setTopology(int topology);

    /** {@code SetGraphicsRootConstantBufferView(PARAM_CBV0 + register, gpuVa)}. */
    void setRootCbv(int register, long gpuVa);

    /**
     * One {@code CopyDescriptors} call: 16 one-wide source ranges into one 16-wide destination range of a shader-visible ring.
     *
     * @param heapType {@code Enums.DescriptorHeapType}
     * @param dstCpu   CPU handle of the first destination descriptor
     * @param sources  16 CPU handles of non-shader-visible descriptors
     */
    void copyTable(int heapType, long dstCpu, long[] sources);

    /** {@code CreateShaderResourceView} of a typed buffer straight into the ring. */
    void createTexelSrv(long dstCpu, D3D12Buffer buffer, int dxgiFormat, long firstElement, int numElements);

    void setSrvTable(long gpuHandle);

    void setSamplerTable(long gpuHandle);

    /** {@code SetGraphicsRoot32BitConstants(PARAM_PUSH, dwords, bytes, 0)}; copies at call time. */
    void setPushConstants(byte[] bytes, int dwords);

    /** {@code SetGraphicsRoot32BitConstants(PARAM_VERTEX_INFO, 2, {baseVertex, firstInstance}, 0)}. */
    void setVertexInfo(int baseVertex, int firstInstance);

    /**
     * {@code IASetVertexBuffers(first, count, views)} where view {@code i} is built from {@code gpuVa[first + i]},
     * {@code sizeInBytes[first + i]}, {@code strideInBytes[first + i]}.
     */
    void setVertexBuffers(int first, int count, long[] gpuVa, int[] sizeInBytes, int[] strideInBytes);

    /** {@code IASetIndexBuffer}; {@code dxgiFormat} is {@code R16_UINT} or {@code R32_UINT}. */
    void setIndexBuffer(long gpuVa, int sizeInBytes, int dxgiFormat);

    void drawInstanced(int vertexCount, int instanceCount, int firstVertex, int firstInstance);

    void drawIndexedInstanced(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance);

    /** {@code ExecuteIndirect(signature, count, buffer.resource(), offset, null, 0)} with the draw (non-indexed) or draw-indexed signature. */
    void executeIndirect(boolean indexed, int count, D3D12Buffer arguments, long offset);

    /** {@code D3D12QueryPool.write(list, index)}. */
    void writeTimestamp(GpuQueryPool pool, int index);
}
