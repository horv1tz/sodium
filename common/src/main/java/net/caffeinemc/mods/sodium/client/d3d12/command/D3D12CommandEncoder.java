// Owner: WP2 (commands + transient memory) - implemented
package net.caffeinemc.mods.sodium.client.d3d12.command;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import net.caffeinemc.mods.sodium.client.d3d12.core.D3D12Log;
import net.caffeinemc.mods.sodium.client.d3d12.core.DebugMessageSink;
import net.caffeinemc.mods.sodium.client.d3d12.core.DeviceContext;
import net.caffeinemc.mods.sodium.client.d3d12.core.FrameClock;
import net.caffeinemc.mods.sodium.client.d3d12.interop.D3D12Exception;
import net.caffeinemc.mods.sodium.client.d3d12.memory.D3D12TransientMemory;
import net.caffeinemc.mods.sodium.client.d3d12.memory.UploadArena;
import net.caffeinemc.mods.sodium.client.d3d12.pass.D3D12RenderPass;
import net.caffeinemc.mods.sodium.client.d3d12.pass.PassManager;
import net.caffeinemc.mods.sodium.client.d3d12.pass.PassSpec;
import net.caffeinemc.mods.sodium.client.d3d12.resource.D3D12Buffer;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.function.IntConsumer;

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
 * <p>The strict-mode debug-layer error is thrown <em>after</em> the frame loop finished consistently (the encoder is
 * left open and healthy, only the exception tells the test that the layer complained), not from the middle of it.
 *
 * <h2>Rules</h2>
 * <ul>
 *     <li>Every command method first calls {@code errors.checkHealthy()}; argument errors are thrown before anything is
 *     recorded. A {@link D3D12Exception} out of the engines happens <em>after</em> tracker/list mutation and puts the
 *     backend into the FAILED state ({@code errors.fail}); argument errors do not.</li>
 *     <li>{@link #createRenderPass} must not throw except for fatal errors (the frontend sets {@code isInRenderPass}
 *     before calling and cannot unwind). {@link #submitRenderPass} ends the pass.</li>
 *     <li>{@code createBuffer(data)} work ({@link #uploadInitialData}) and {@link #beginInternalPass} (present blit) may
 *     be called by other packages; everything else is reached through the interface.</li>
 *     <li>{@link #writeTimestamp} delegates to {@link D3D12QueryPool#write}.</li>
 * </ul>
 *
 * <p>Threading: render thread.
 */
public final class D3D12CommandEncoder implements CommandEncoderBackend {
    /** How long {@link #shutdown()} waits for the GPU before it continues anyway. */
    private static final long SHUTDOWN_FLUSH_NS = 10_000_000_000L;

    private final DeviceContext ctx;
    private final CommandRecorder recorder;
    private final ClearEngine clears;
    private final ReadbackSupport readbacks;
    private final D3D12TransientMemory transientMemory;
    private final CopyEngine copies;
    private final PassManager passManager;

    private @Nullable D3D12RenderPass currentPass;
    private boolean inSubmit;
    private boolean shutDown;

    /**
     * Builds, in this order, the recorder (opens the first frame), the copy and clear engines, read-back support,
     * transient memory and {@code new PassManager(ctx, recorder, clears)} (explicit references: {@code ctx.encoder()} does
     * not resolve until {@code D3D12GpuDevice.create} attached this encoder, so nothing built here may call it). Called by
     * {@code D3D12GpuDevice.create} after the descriptor services, tracker and root signatures exist. The encoder owns
     * everything it builds and releases all of it in {@link #shutdown()}.
     *
     * <p>If the constructor fails nothing is left behind: nothing was submitted, so what was created is released at once.
     */
    public D3D12CommandEncoder(DeviceContext ctx) {
        this(ctx, null);
    }

    /**
     * Test seam: {@code frameStartHook} replaces {@code ctx.descriptors().beginFrame(slot)} in
     * {@link CommandRecorder#beginFrame()} (so that the frame loop can be exercised without descriptor services);
     * {@code null} = the production behaviour.
     */
    public D3D12CommandEncoder(DeviceContext ctx, @Nullable IntConsumer frameStartHook) {
        this.ctx = ctx;

        CommandRecorder newRecorder = new CommandRecorder(ctx, frameStartHook);
        D3D12TransientMemory newTransient = null;

        try {
            newRecorder.initialize();

            ClearEngine newClears = new ClearEngine(ctx, newRecorder);
            ReadbackSupport newReadbacks = new ReadbackSupport(ctx);

            newTransient = new D3D12TransientMemory(ctx);

            this.recorder = newRecorder;
            this.clears = newClears;
            this.readbacks = newReadbacks;
            this.transientMemory = newTransient;
            this.copies = new CopyEngine(ctx, newRecorder, newTransient.uploads(), newReadbacks);
            this.passManager = new PassManager(ctx, newRecorder, newClears);
        } catch (Throwable t) {
            if (newTransient != null) {
                newTransient.close();
            }

            newRecorder.close();

            throw t;
        }
    }

    /** @return the owner of the command list and allocators */
    public CommandRecorder recorder() {
        return this.recorder;
    }

    public CopyEngine copies() {
        return this.copies;
    }

    public ClearEngine clears() {
        return this.clears;
    }

    public ReadbackSupport readbacks() {
        return this.readbacks;
    }

    /** @return the backend-internal upload facade (same allocators as the public transient memory) */
    public UploadArena uploads() {
        return this.transientMemory.uploads();
    }

    /**
     * {@code createBuffer(data)} for DEFAULT buffers; see {@link CopyEngine#uploadInitialData}. Legal while a pass is
     * open (the copy is recorded inline at the call position).
     */
    public void uploadInitialData(D3D12Buffer dst, ByteBuffer data) {
        this.beginCommand();

        try {
            this.copies.uploadInitialData(dst, data);
        } catch (D3D12Exception e) {
            throw this.ctx.errors().fail(e);
        }
    }

    /**
     * Begins a pass that the frontend does not know about (the present blit into a back buffer). Same as
     * {@link #createRenderPass} after {@code PassSpec.from}. End it with {@link #submitRenderPass()}.
     */
    public RenderPassBackend beginInternalPass(PassSpec spec) {
        this.beginCommand();

        if (this.currentPass != null) {
            throw new IllegalStateException("Close the existing render pass before creating a new one!");
        }

        D3D12RenderPass pass;

        try {
            pass = this.passManager.begin(spec);
        } catch (D3D12Exception e) {
            // the list and the tracker were already modified: the stream cannot be repaired
            throw this.ctx.errors().fail(e);
        }

        this.currentPass = pass;

        return pass;
    }

    /** Creates a timestamp pool (usable before the first frame; Tracy needs it in the device constructor). */
    public D3D12QueryPool createQueryPool(int size) {
        this.beginCommand();

        return new D3D12QueryPool(this.ctx, size);
    }

    /** @return true while a render pass is open (the surface and tests assert on it) */
    public boolean passOpen() {
        return this.currentPass != null;
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
        if (this.shutDown) {
            return;
        }

        this.shutDown = true;
        // a pass that is still open dies with the dropped frame
        this.currentPass = null;

        this.recorder.discardFrame();

        try {
            this.ctx.clock().flushGpu(SHUTDOWN_FLUSH_NS);
        } catch (Throwable t) {
            D3D12Log.error("Waiting for the GPU at shutdown failed; continuing with the release of the device objects", t);
        }

        // the GPU is idle (or we gave up waiting): from here on DeviceContext.defer runs inline
        this.ctx.markGpuIdle();

        this.drainAll("before releasing the pass manager");

        try {
            this.passManager.close();
        } catch (Throwable t) {
            D3D12Log.error("Failed to close the pass manager", t);
        }

        try {
            this.transientMemory.close();
        } catch (Throwable t) {
            D3D12Log.error("Failed to close the transient memory", t);
        }

        this.drainAll("after releasing the transient memory");

        this.recorder.close();
    }

    private void drainAll(String when) {
        try {
            this.ctx.deferral().drainAll();
        } catch (Throwable t) {
            D3D12Log.error("Draining the deferred actions " + when + " failed", t);
        }
    }

    @Override
    public void submit() {
        if (this.inSubmit) {
            throw new IllegalStateException("submit() re-entered");
        }

        if (this.currentPass != null) {
            throw new IllegalStateException("Cannot end command buffer while inside RenderPass");
        }

        this.ctx.errors().checkHealthy();
        this.checkOpen();

        this.inSubmit = true;

        boolean debugError = false;

        try {
            FrameClock clock = this.ctx.clock();
            long frame = clock.current();

            this.recorder.endFrameAndExecute();
            this.transientMemory.rotate(frame);
            clock.signalAndAdvance();

            DebugMessageSink debug = this.ctx.debug();

            debug.drainToLog();
            debugError = this.ctx.strict() && debug.takeErrorFlag();

            if (!clock.await(frame - 1L, this.ctx.options().frameWaitTimeoutNs())) {
                throw this.ctx.errors().timeout("waiting for the previous frame");
            }

            this.recorder.beginFrame();
            this.ctx.deferral().drain(clock.completed());
        } catch (Throwable t) {
            throw this.ctx.errors().fail(t);
        } finally {
            this.inSubmit = false;
        }

        if (debugError) {
            throw new IllegalStateException("The Direct3D 12 debug layer reported errors (strict mode): " + this.ctx.debug().recentMessages());
        }
    }

    @Override
    public D3D12TransientMemory transientMemory() {
        return this.transientMemory;
    }

    @Override
    public RenderPassBackend createRenderPass(RenderPassDescriptor descriptor) {
        this.beginCommand();

        if (this.currentPass != null) {
            throw new IllegalStateException("Close the existing render pass before creating a new one!");
        }

        // pure: a foreign view or a non-D32_FLOAT depth attachment fails here, before the list is touched
        PassSpec spec = PassSpec.from(descriptor, this.ctx.labels());

        return this.beginInternalPass(spec);
    }

    @Override
    public void submitRenderPass() {
        D3D12RenderPass pass = this.currentPass;

        if (pass == null) {
            throw new IllegalStateException("Can't submit a render pass if one isn't open");
        }

        // the frontend considers the pass closed before it calls us: never stay "inside" it, even if ending it fails
        this.currentPass = null;

        this.ctx.errors().checkHealthy();

        try {
            pass.end();
        } catch (D3D12Exception e) {
            throw this.ctx.errors().fail(e);
        }
    }

    @Override
    public void clearColorTexture(GpuTexture colorTexture, Vector4fc clearColor) {
        this.beginTransfer();

        try {
            this.clears.clearColorTexture(colorTexture, clearColor);
        } catch (D3D12Exception e) {
            throw this.ctx.errors().fail(e);
        }
    }

    @Override
    public void clearColorAndDepthTextures(GpuTexture colorTexture, Vector4fc clearColor, GpuTexture depthTexture, double clearDepth) {
        this.beginTransfer();

        try {
            this.clears.clearColorAndDepthTextures(colorTexture, clearColor, depthTexture, clearDepth);
        } catch (D3D12Exception e) {
            throw this.ctx.errors().fail(e);
        }
    }

    @Override
    public void clearColorAndDepthTextures(GpuTexture colorTexture, Vector4fc clearColor, GpuTexture depthTexture,
                                           double clearDepth, int regionX, int regionY, int regionWidth,
                                           int regionHeight, int mipLevel) {
        this.beginTransfer();

        try {
            this.clears.clearColorAndDepthTextures(colorTexture, clearColor, depthTexture, clearDepth, regionX, regionY,
                    regionWidth, regionHeight, mipLevel);
        } catch (D3D12Exception e) {
            throw this.ctx.errors().fail(e);
        }
    }

    @Override
    public void clearDepthTexture(GpuTexture depthTexture, double clearDepth) {
        this.beginTransfer();

        try {
            this.clears.clearDepthTexture(depthTexture, clearDepth);
        } catch (D3D12Exception e) {
            throw this.ctx.errors().fail(e);
        }
    }

    @Override
    public void writeToBuffer(GpuBufferSlice destination, ByteBuffer data) {
        this.beginTransfer();

        try {
            this.copies.writeToBuffer(destination, data);
        } catch (D3D12Exception e) {
            throw this.ctx.errors().fail(e);
        }
    }

    @Override
    public void copyToBuffer(GpuBufferSlice source, GpuBufferSlice target) {
        this.beginTransfer();

        try {
            this.copies.copyToBuffer(source, target);
        } catch (D3D12Exception e) {
            throw this.ctx.errors().fail(e);
        }
    }

    @Override
    public void writeToTexture(GpuTexture destination, ByteBuffer source, int mipLevel, int depthOrLayer, int destX,
                               int destY, int width, int height) {
        this.beginTransfer();

        try {
            this.copies.writeToTexture(destination, source, mipLevel, depthOrLayer, destX, destY, width, height);
        } catch (D3D12Exception e) {
            throw this.ctx.errors().fail(e);
        }
    }

    @Override
    public void copyBufferToTexture(GpuBufferSlice source, int sourceX, int sourceY, int sourceWidth, int sourceHeight,
                                    GpuTexture destination, int destinationX, int destinationY, int copyWidth,
                                    int copyHeight, int mipLevel, int arrayLayer) {
        this.beginTransfer();

        try {
            this.copies.copyBufferToTexture(source, sourceX, sourceY, sourceWidth, sourceHeight, destination, destinationX,
                    destinationY, copyWidth, copyHeight, mipLevel, arrayLayer);
        } catch (D3D12Exception e) {
            throw this.ctx.errors().fail(e);
        }
    }

    /** The whole mip: the engine validates the mip level first, then derives {@code max(1, size >> mip)}. */
    @Override
    public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback, int mipLevel) {
        this.beginTransfer();

        try {
            this.copies.copyTextureToBuffer(source, destination, offset, callback, mipLevel);
        } catch (D3D12Exception e) {
            throw this.ctx.errors().fail(e);
        }
    }

    @Override
    public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback,
                                    int mipLevel, int x, int y, int width, int height) {
        this.beginTransfer();

        try {
            this.copies.copyTextureToBuffer(source, destination, offset, callback, mipLevel, x, y, width, height);
        } catch (D3D12Exception e) {
            throw this.ctx.errors().fail(e);
        }
    }

    @Override
    public void copyTextureToTexture(GpuTexture source, GpuTexture destination, int mipLevel, int destX, int destY,
                                     int sourceX, int sourceY, int width, int height) {
        this.beginTransfer();

        try {
            this.copies.copyTextureToTexture(source, destination, mipLevel, destX, destY, sourceX, sourceY, width, height);
        } catch (D3D12Exception e) {
            throw this.ctx.errors().fail(e);
        }
    }

    /** The fence of the current frame; it owns no native state and is deliberately not registered in the {@code ObjectRegistry}. */
    @Override
    public GpuFence createFence() {
        this.ctx.errors().checkHealthy();
        this.checkOpen();

        return new D3D12GpuFence(this.ctx);
    }

    @Override
    public void writeTimestamp(GpuQueryPool pool, int index) {
        this.beginCommand();

        if (!(pool instanceof D3D12QueryPool d3d)) {
            throw new IllegalArgumentException("The query pool was not created by the Direct3D 12 backend: " + pool);
        }

        try {
            d3d.write(this.recorder.list(), index);
        } catch (D3D12Exception e) {
            throw this.ctx.errors().fail(e);
        }
    }

    /** Health and liveness check shared by every command; copies and clears additionally refuse an open pass. */
    private void beginCommand() {
        this.ctx.errors().checkHealthy();
        this.checkOpen();
    }

    private void beginTransfer() {
        this.beginCommand();

        if (this.currentPass != null) {
            throw new IllegalStateException("Close the existing render pass before performing additional commands");
        }
    }

    private void checkOpen() {
        if (this.shutDown || this.ctx.isClosed()) {
            throw new IllegalStateException("The Direct3D 12 command encoder is closed");
        }
    }

    DeviceContext context() {
        return this.ctx;
    }
}
