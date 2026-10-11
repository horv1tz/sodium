// Owner: WP3 (pipelines + render pass)
package net.caffeinemc.mods.sodium.client.d3d12.pipeline;

import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import net.caffeinemc.mods.sodium.client.d3d12.core.D3D12Log;
import net.caffeinemc.mods.sodium.client.d3d12.core.DeviceContext;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12PipelineState;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * {@code GpuDeviceBackend.compilePipeline}: the worker-thread half of pipeline creation. Re-entrant and thread-safe: many
 * pipelines compile concurrently, and nothing here touches render-thread state (no tracker, no descriptors, no list).
 * Shared state is limited to the {@link ShaderCache}, the {@link SlotLayouts} intern table, the compile semaphore and the
 * free-threaded device.
 *
 * <pre>
 * compile(info):
 *   try {
 *     assignment = SlotAssignment.assign(info.uniforms())             // fails past 12 UBOs / 16 textures
 *     for stage in {VERTEX, FRAGMENT} (by module.type(), never by list position):
 *        ts = ShaderTranslator.translate(info, shader, assignment)    // always runs; milliseconds
 *        dxbc[stage] = hlslCompiler.compile(ts.hlsl(), "main", "vs_5_0"|"ps_5_0", info.name() + "/" + stage)
 *     slots  = merge the stage facts (activity = OR, cube = OR, texel base types) -> SlotInfo list
 *     layout = layouts.intern(SlotLayout.of(slots, ceil(pushConstantsSize / 4), vertexStage.usesVertexInfo))
 *     psoA = PipelineStateBuilder.create(ctx, info, vs, ps, withDepth = true)
 *     psoB = info.depthStencilState() == null ? PipelineStateBuilder.create(.., false) : null
 *     return () -> new D3D12RenderPipeline(ctx, info.name(), psoA, psoB, layout, strides, vbMask, topology, fan, hasDepthState)
 *   } catch (Throwable t) {                                            // except VirtualMachineError
 *     release whatever was created; log pipeline name, stage, compiler text, first 20 HLSL lines;
 *     dump the HLSL to &lt;tmp&gt;/sodium-d3d12/&lt;pipeline&gt;-&lt;stage&gt;.hlsl; return Pending.NULL
 *   }
 * </pre>
 * <b>Never throws</b>: an exception would fail the whole resource reload even for the two optional wireframe pipelines,
 * and would leak the frontend's {@code SpvModule}s. The returned {@code Pending.finishCompile()} only wraps objects built
 * here and never throws. The {@code SpvModule} buffers are read inside the call and never retained (the frontend frees them
 * after {@code finishCompile}).
 *
 * <h2>Shutdown gate (workers outlive the device)</h2>
 * The compile runs on {@code Util.backgroundExecutor()} threads, and Minecraft closes the renderer
 * ({@code RenderSystem.shutdownRenderer -> FrontendGpuDevice.close -> backend.close}) <em>before</em>
 * {@code Util.shutdownExecutors()}: a reload that is still compiling when the player quits would otherwise call
 * {@code CreateGraphicsPipelineState} on a released {@code ID3D12Device} (a native crash at exit). Therefore:
 * <ul>
 *     <li>{@link #compile} enters a gate first; if the compiler is closing or {@code ctx.isClosed()}, it returns
 *     {@code Pending.NULL} immediately. Otherwise it counts itself in and out (try/finally).</li>
 *     <li>{@link #close()} - called by {@code D3D12GpuDevice.close()} <b>first</b>, before the context is marked closing
 *     and before root signatures, the encoder or the device are touched - sets {@code closing}, waits (bounded, 15 s) for
 *     the in-flight compiles to leave, then releases every PSO that was created but never adopted. If the wait times out it
 *     logs and returns with {@link #isDrained()} {@code == false}; the caller must then <b>not</b> release the root
 *     signatures and the device (a leak at process exit is preferable to a crash).</li>
 *     <li>Every PSO created on a worker is {@link #track tracked} until {@code finishCompile} {@link #adopt adopts} it.
 *     A {@code finishCompile} that runs after shutdown began (reload aborted, late join) releases the PSOs instead of
 *     building a {@code D3D12RenderPipeline} on a dead context and returns {@code null} (allowed by the interface; the
 *     frontend treats it as a failed pipeline).</li>
 * </ul>
 */
public final class D3D12PipelineCompiler implements AutoCloseable {
    /** How long {@link #close()} waits for compiles that are still running. */
    private static final long DRAIN_TIMEOUT_NANOS = 15_000_000_000L;

    private final DeviceContext ctx;
    private final SlotLayouts layouts = new SlotLayouts();
    private final HlslCompiler hlsl;

    private final Object gate = new Object();
    private final Set<D3D12PipelineState> unadopted = ConcurrentHashMap.newKeySet();
    private final Map<String, D3D12RenderPipeline> builtins = new HashMap<>();
    private int inFlight;
    private boolean closing;
    private volatile boolean drained;

    /**
     * Touches nothing native: the {@link ShaderCache} only remembers its directory and the {@link HlslCompiler} loads
     * {@code d3dcompiler_47.dll} on its first {@code compile}. Called by {@code D3D12GpuDevice.create}, which then calls
     * {@code ctx.attachPipelineCompiler(..)}.
     */
    public D3D12PipelineCompiler(DeviceContext ctx) {
        this.ctx = ctx;
        this.hlsl = new HlslCompiler(ctx.options(), new ShaderCache(ctx.options().shaderCacheDir()));
    }

    /**
     * @return {@code Pending.NULL} on any failure or after shutdown began (after logging), never null, never throws
     * (except {@code VirtualMachineError})
     */
    public BackendRenderPipeline.Pending compile(BackendRenderPipeline.CreateInfo info) {
        if (!this.enter()) {
            return BackendRenderPipeline.Pending.NULL;
        }

        try {
            return this.compileEntered(info);
        } finally {
            this.exit();
        }
    }

    /**
     * The body of {@link #compile}, inside the gate: the pseudo-code of the class comment. Track every PSO with
     * {@link #track} right after creating it; the returned {@code Pending} calls {@link #adopt} first and returns
     * {@code null} when it says no.
     */
    private BackendRenderPipeline.Pending compileEntered(BackendRenderPipeline.CreateInfo info) {
        throw new UnsupportedOperationException("WP3: see class comment");
    }

    private boolean enter() {
        synchronized (this.gate) {
            if (this.closing || this.ctx.isClosed()) {
                return false;
            }

            this.inFlight++;

            return true;
        }
    }

    private void exit() {
        synchronized (this.gate) {
            if (--this.inFlight == 0) {
                this.gate.notifyAll();
            }
        }
    }

    /** Worker thread: remembers a freshly created PSO until {@link #adopt} takes it (so {@link #close()} can release it). */
    void track(D3D12PipelineState pso) {
        this.unadopted.add(pso);
    }

    /**
     * {@code finishCompile()}: takes ownership of the PSOs that {@link #compile} built.
     *
     * @return true if the caller may wrap them in a {@link D3D12RenderPipeline}; false if shutdown began (or {@code close()}
     * already released them): whatever this call still owned has been released and the caller returns {@code null}
     */
    boolean adopt(D3D12PipelineState first, @Nullable D3D12PipelineState second) {
        boolean ownsFirst = this.unadopted.remove(first);
        boolean ownsSecond = second != null && this.unadopted.remove(second);
        boolean usable = ownsFirst && (second == null || ownsSecond) && !this.isShuttingDown();

        if (!usable) {
            if (ownsFirst) {
                first.close();
            }

            if (ownsSecond) {
                second.close();
            }
        }

        return usable;
    }

    private boolean isShuttingDown() {
        synchronized (this.gate) {
            return this.closing || this.ctx.isClosed();
        }
    }

    /**
     * Device-lifetime memo of the built-in pipelines (see {@link BuiltinPipelines}): the first caller builds, later
     * callers get the same instance. The pipelines register themselves in the {@code ObjectRegistry} and are closed with
     * the device; nobody else closes them.
     */
    D3D12RenderPipeline builtin(String key, Supplier<D3D12RenderPipeline> factory) {
        synchronized (this.builtins) {
            D3D12RenderPipeline pipeline = this.builtins.get(key);

            if (pipeline == null || pipeline.isClosed()) {
                pipeline = factory.get();
                this.builtins.put(key, pipeline);
            }

            return pipeline;
        }
    }

    /** @return the intern table (also used by {@code BuiltinPipelines}, which reaches it through {@code ctx.pipelineCompiler()}) */
    public SlotLayouts layouts() {
        return this.layouts;
    }

    /** @return the shared HLSL compiler and cache (also used by {@code BuiltinPipelines} and the start-up probe) */
    public HlslCompiler hlsl() {
        return this.hlsl;
    }

    /**
     * @return true after {@link #close()} if no compile was running any more (it is then safe to release the root
     * signatures and the device); false before {@code close()} and after a drain timeout
     */
    public boolean isDrained() {
        return this.drained;
    }

    DeviceContext context() {
        return this.ctx;
    }

    /**
     * The shutdown gate (see the class comment): rejects new compiles, waits up to 15 s for running ones, releases the
     * PSOs nobody adopted. Idempotent; never throws; must be the first step of {@code D3D12GpuDevice.close()}.
     */
    @Override
    public void close() {
        long deadline = System.nanoTime() + DRAIN_TIMEOUT_NANOS;
        boolean idle;

        synchronized (this.gate) {
            this.closing = true;

            try {
                while (this.inFlight > 0) {
                    long remaining = deadline - System.nanoTime();

                    if (remaining <= 0L) {
                        break;
                    }

                    this.gate.wait(remaining / 1_000_000L, (int) (remaining % 1_000_000L));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            idle = this.inFlight == 0;
            this.drained = idle;
        }

        if (!idle) {
            D3D12Log.error("Direct3D 12 pipeline compiles were still running after {} s at shutdown; the root signatures "
                    + "and the device are deliberately not released to avoid a native crash", DRAIN_TIMEOUT_NANOS / 1_000_000_000L);
            return;
        }

        for (D3D12PipelineState pso : this.unadopted) {
            if (this.unadopted.remove(pso)) {
                try {
                    pso.close();
                } catch (Throwable t) {
                    D3D12Log.error("Failed to release an unadopted pipeline state object", t);
                }
            }
        }

        synchronized (this.builtins) {
            this.builtins.clear();
        }
    }
}
