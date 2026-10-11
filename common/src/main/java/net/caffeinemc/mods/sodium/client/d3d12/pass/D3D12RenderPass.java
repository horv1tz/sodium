// Owner: WP3 (pipelines + render pass)
package net.caffeinemc.mods.sodium.client.d3d12.pass;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import net.caffeinemc.mods.sodium.client.d3d12.core.DeviceContext;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.function.Supplier;

/**
 * One object per pass, valid from {@code createRenderPass} to {@code submitRenderPass}. A thin shell: every method checks
 * {@code open} (the frontend guards only {@code pushDebugGroup}, {@code popDebugGroup} and the draws against use after the
 * pass ended; {@code setPipeline}, {@code setUniform}, {@code pushConstants}, the scissor methods, {@code setVertexBuffer},
 * {@code setIndexBuffer} and {@code writeTimestamp} are not guarded) and forwards to the shared {@code PassBinder}.
 *
 * <p>The four direct multi-draw methods throw {@link UnsupportedOperationException}: the device reports
 * {@code multiDrawDirectInterleaved = multiDrawDirectSeparate = false}, so the frontend never forwards them, and Sodium
 * uses {@code drawIndexedIndirect}. A CPU loop emulation was rejected as dead code.
 *
 * <p>Tolerance policy (decision D4): missing inputs never throw in production (substitute and warn once); in strict mode
 * ({@code DeviceOptions.strict}) they throw {@link IllegalStateException}, like the Vulkan reference.
 *
 * <p>Threading: render thread.
 */
public final class D3D12RenderPass implements RenderPassBackend {
    private final DeviceContext ctx;
    private final PassBinder binder;
    private final PassSpec spec;
    private boolean open = true;

    D3D12RenderPass(DeviceContext ctx, PassBinder binder, PassSpec spec) {
        this.ctx = ctx;
        this.binder = binder;
        this.spec = spec;
    }

    public PassSpec spec() {
        return this.spec;
    }

    public boolean isOpen() {
        return this.open;
    }

    /** Called by {@code D3D12CommandEncoder.submitRenderPass}: closes the pass; later calls on it throw. */
    public void end() {
        this.checkOpen();
        this.open = false;
        this.binder.end();
    }

    private void checkOpen() {
        if (!this.open) {
            throw new IllegalStateException("The render pass was already submitted");
        }
    }

    @Override
    public void pushDebugGroup(Supplier<String> label) {
        this.checkOpen();
        this.binder.pushDebugGroup(label);
    }

    @Override
    public void popDebugGroup() {
        this.checkOpen();
        this.binder.popDebugGroup();
    }

    @Override
    public void setPipeline(BackendRenderPipeline pipeline) {
        this.checkOpen();
        this.binder.setPipeline(pipeline);
    }

    @Override
    public void setUniform(int index, @Nullable Object value) {
        this.checkOpen();
        this.binder.setUniform(index, value);
    }

    @Override
    public void pushConstants(ByteBuffer value) {
        this.checkOpen();
        this.binder.pushConstants(value);
    }

    @Override
    public void enableScissor(int x, int y, int width, int height) {
        this.checkOpen();
        this.binder.enableScissor(x, y, width, height);
    }

    @Override
    public void disableScissor() {
        this.checkOpen();
        this.binder.disableScissor();
    }

    @Override
    public void setVertexBuffer(int slot, @Nullable GpuBufferSlice vertexBuffer) {
        this.checkOpen();
        this.binder.setVertexBuffer(slot, vertexBuffer);
    }

    @Override
    public void setIndexBuffer(GpuBuffer indexBuffer, IndexType indexType) {
        this.checkOpen();
        this.binder.setIndexBuffer(indexBuffer, indexType);
    }

    @Override
    public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance) {
        this.checkOpen();
        this.binder.drawIndexed(indexCount, instanceCount, firstIndex, vertexOffset, firstInstance);
    }

    @Override
    public void multiDrawIndexed(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
        throw new UnsupportedOperationException("Direct3D 12 does not support direct multi-draw (multiDrawDirectInterleaved is false)");
    }

    @Override
    public void multiDrawIndexed(PointerBuffer firstIndexOffsets, IntBuffer indexCounts, IntBuffer vertexOffsets, int drawCount) {
        throw new UnsupportedOperationException("Direct3D 12 does not support direct multi-draw (multiDrawDirectSeparate is false)");
    }

    @Override
    public void drawIndexedIndirect(GpuBufferSlice commands, int drawCount) {
        this.checkOpen();
        this.binder.drawIndexedIndirect(commands, drawCount);
    }

    @Override
    public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
        this.checkOpen();
        this.binder.draw(vertexCount, instanceCount, firstVertex, firstInstance);
    }

    @Override
    public void multiDraw(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
        throw new UnsupportedOperationException("Direct3D 12 does not support direct multi-draw (multiDrawDirectInterleaved is false)");
    }

    @Override
    public void multiDraw(IntBuffer firstVertices, IntBuffer vertexCounts, int drawCount) {
        throw new UnsupportedOperationException("Direct3D 12 does not support direct multi-draw (multiDrawDirectSeparate is false)");
    }

    @Override
    public void drawIndirect(GpuBufferSlice commands, int drawCount) {
        this.checkOpen();
        this.binder.drawIndirect(commands, drawCount);
    }

    @Override
    public void writeTimestamp(GpuQueryPool pool, int index) {
        this.checkOpen();
        this.binder.writeTimestamp(pool, index);
    }

    DeviceContext context() {
        return this.ctx;
    }
}
