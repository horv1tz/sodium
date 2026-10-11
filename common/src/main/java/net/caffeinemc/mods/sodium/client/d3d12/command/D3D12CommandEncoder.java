// Owner: WP2 (commands + transient memory)
package net.caffeinemc.mods.sodium.client.d3d12.command;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import net.caffeinemc.mods.sodium.client.d3d12.core.DeviceContext;
import net.caffeinemc.mods.sodium.client.d3d12.memory.D3D12TransientMemory;
import net.caffeinemc.mods.sodium.client.d3d12.memory.UploadArena;
import net.caffeinemc.mods.sodium.client.d3d12.pass.PassSpec;
import net.caffeinemc.mods.sodium.client.d3d12.resource.D3D12Buffer;
import org.joml.Vector4fc;

import java.nio.ByteBuffer;

/**
 * The singleton {@code CommandEncoderBackend}: owns the command recorder, the copy/clear engines, the transient memory
 * and the (at most one) open render pass. The frontend never closes it; {@code D3D12GpuDevice.close()} calls
 * {@link #shutdown()}.
 *
 * <h2>Frame loop: {@code submit()} (render thread, not re-entrant)</h2>
 * <pre>
 *  if (inSubmit)            throw ISE("submit() re-entered");
 *  if (currentPass != null) throw ISE("Cannot end command buffer while inside RenderPass");
 *  errors.checkHealthy();   inSubmit = true;
 *  try {
 *    f = clock.current();
 *    recorder.endFrameAndExecute();                 // tracker.flush, fullFence, Close, ExecuteCommandLists
 *    transient.rotate(f);                           // tags UPLOAD/DEFAULT/special blocks with f (deferral)
 *    clock.signalAndAdvance();                      // Signal(f); current = f + 1
 *    debug.drainToLog(); (strict: debug-layer errors -> ISE)
 *    if (!clock.await(f - 1, options.frameWaitTimeoutNs())) throw errors.timeout("waiting for the previous frame");
 *    recorder.beginFrame();                         // reset allocator of slot (f+1)&1, reset list, rewind rings
 *    deferral.drain(clock.completed());             // releases, recycling, read-back callbacks (list is open: callbacks may record)
 *  } catch (Throwable t) { throw errors.fail(t); }  // sticky FAILED
 *  finally { inSubmit = false; }
 * </pre>
 * {@code submit()} with nothing recorded still executes the (empty) list and signals: pacing and callbacks never depend
 * on content. The wait happens <em>after</em> the work was queued, so the GPU stays fed while the CPU idles.
 *
 * <h2>Rules</h2>
 * <ul>
 *     <li>Every command method first calls {@code errors.checkHealthy()}; argument errors are thrown before anything is
 *     recorded.</li>
 *     <li>{@link #createRenderPass} must not throw except for fatal errors (the frontend sets {@code isInRenderPass}
 *     before calling and cannot unwind). {@link #submitRenderPass} ends the pass.</li>
 *     <li>{@code createBuffer(data)} work ({@link #uploadInitialData}) and {@link #beginInternalPass} (present blit) may
 *     be called by other packages; everything else is reached through the interface.</li>
 *     <li>{@link #writeTimestamp} delegates to {@link D3D12QueryPool#write}.</li>
 * </ul>
 */
public final class D3D12CommandEncoder implements CommandEncoderBackend {
    private final DeviceContext ctx;

    /**
     * Builds, in this order, the recorder (opens the first frame), the copy and clear engines, read-back support,
     * transient memory and {@code new PassManager(ctx, recorder, clears)} (explicit references: {@code ctx.encoder()} does
     * not resolve until {@code D3D12GpuDevice.create} attached this encoder, so nothing built here may call it). Called by
     * {@code D3D12GpuDevice.create} after the descriptor services, tracker and root signatures exist. The encoder owns
     * everything it builds and releases all of it in {@link #shutdown()}.
     */
    public D3D12CommandEncoder(DeviceContext ctx) {
        this.ctx = ctx;
    }

    /** @return the owner of the command list and allocators */
    public CommandRecorder recorder() {
        throw new UnsupportedOperationException("WP2");
    }

    public CopyEngine copies() {
        throw new UnsupportedOperationException("WP2");
    }

    public ClearEngine clears() {
        throw new UnsupportedOperationException("WP2");
    }

    public ReadbackSupport readbacks() {
        throw new UnsupportedOperationException("WP2");
    }

    /** @return the backend-internal upload facade (same allocators as the public transient memory) */
    public UploadArena uploads() {
        throw new UnsupportedOperationException("WP2");
    }

    /** {@code createBuffer(data)} for DEFAULT buffers; see {@link CopyEngine#uploadInitialData}. */
    public void uploadInitialData(D3D12Buffer dst, ByteBuffer data) {
        throw new UnsupportedOperationException("WP2: delegate to CopyEngine");
    }

    /**
     * Begins a pass that the frontend does not know about (the present blit into a back buffer). Same as
     * {@link #createRenderPass} after {@code PassSpec.from}. End it with {@link #submitRenderPass()}.
     */
    public RenderPassBackend beginInternalPass(PassSpec spec) {
        throw new UnsupportedOperationException("WP2: PassManager.begin(spec) + currentPass bookkeeping");
    }

    /** Creates a timestamp pool (usable before the first frame; Tracy needs it in the device constructor). */
    public D3D12QueryPool createQueryPool(int size) {
        throw new UnsupportedOperationException("WP2");
    }

    /** @return true while a render pass is open (the surface and tests assert on it) */
    public boolean passOpen() {
        throw new UnsupportedOperationException("WP2");
    }

    /**
     * Device shutdown (see {@code D3D12GpuDevice.close}); each sub-step in its own try/catch, idempotent, never throws:
     * <ol>
     *     <li>discard the unsubmitted frame ({@code recorder.discardFrame()});</li>
     *     <li>{@code clock.flushGpu(10 s)} (log and continue on timeout), then {@code ctx.markGpuIdle()} - only from here on
     *     does {@code DeviceContext.defer} run actions inline;</li>
     *     <li>{@code deferral.drainAll()} until empty (callbacks of submitted frames run, callbacks of the dropped frame
     *     do not);</li>
     *     <li>{@code passManager.close()}, then {@code transient.close()}, then drain again;</li>
     *     <li>{@code recorder.close()} (list and allocators) last.</li>
     * </ol>
     * {@code D3D12GpuDevice.close} calls nothing else on the encoder's internals.
     */
    public void shutdown() {
        throw new UnsupportedOperationException("WP2");
    }

    @Override
    public void submit() {
        throw new UnsupportedOperationException("WP2: see class comment");
    }

    @Override
    public D3D12TransientMemory transientMemory() {
        throw new UnsupportedOperationException("WP2");
    }

    @Override
    public RenderPassBackend createRenderPass(RenderPassDescriptor descriptor) {
        throw new UnsupportedOperationException("WP2: PassSpec.from(descriptor) -> PassManager.begin");
    }

    @Override
    public void submitRenderPass() {
        throw new UnsupportedOperationException("WP2: D3D12RenderPass.end(), currentPass = null");
    }

    @Override
    public void clearColorTexture(GpuTexture colorTexture, Vector4fc clearColor) {
        throw new UnsupportedOperationException("WP2: ClearEngine");
    }

    @Override
    public void clearColorAndDepthTextures(GpuTexture colorTexture, Vector4fc clearColor, GpuTexture depthTexture, double clearDepth) {
        throw new UnsupportedOperationException("WP2: ClearEngine");
    }

    @Override
    public void clearColorAndDepthTextures(GpuTexture colorTexture, Vector4fc clearColor, GpuTexture depthTexture,
                                           double clearDepth, int regionX, int regionY, int regionWidth,
                                           int regionHeight, int mipLevel) {
        throw new UnsupportedOperationException("WP2: ClearEngine");
    }

    @Override
    public void clearDepthTexture(GpuTexture depthTexture, double clearDepth) {
        throw new UnsupportedOperationException("WP2: ClearEngine");
    }

    @Override
    public void writeToBuffer(GpuBufferSlice destination, ByteBuffer data) {
        throw new UnsupportedOperationException("WP2: CopyEngine");
    }

    @Override
    public void copyToBuffer(GpuBufferSlice source, GpuBufferSlice target) {
        throw new UnsupportedOperationException("WP2: CopyEngine");
    }

    @Override
    public void writeToTexture(GpuTexture destination, ByteBuffer source, int mipLevel, int depthOrLayer, int destX,
                               int destY, int width, int height) {
        throw new UnsupportedOperationException("WP2: CopyEngine");
    }

    @Override
    public void copyBufferToTexture(GpuBufferSlice source, int sourceX, int sourceY, int sourceWidth, int sourceHeight,
                                    GpuTexture destination, int destinationX, int destinationY, int copyWidth,
                                    int copyHeight, int mipLevel, int arrayLayer) {
        throw new UnsupportedOperationException("WP2: CopyEngine");
    }

    @Override
    public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback, int mipLevel) {
        throw new UnsupportedOperationException("WP2: CopyEngine with the full mip rectangle max(1, size >> mip)");
    }

    @Override
    public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback,
                                    int mipLevel, int x, int y, int width, int height) {
        throw new UnsupportedOperationException("WP2: CopyEngine");
    }

    @Override
    public void copyTextureToTexture(GpuTexture source, GpuTexture destination, int mipLevel, int destX, int destY,
                                     int sourceX, int sourceY, int width, int height) {
        throw new UnsupportedOperationException("WP2: CopyEngine");
    }

    @Override
    public GpuFence createFence() {
        throw new UnsupportedOperationException("WP2: new D3D12GpuFence(ctx) - NOT registered in ObjectRegistry (no native state)");
    }

    @Override
    public void writeTimestamp(GpuQueryPool pool, int index) {
        throw new UnsupportedOperationException("WP2: D3D12QueryPool.write(list, index)");
    }

    DeviceContext context() {
        return this.ctx;
    }
}
