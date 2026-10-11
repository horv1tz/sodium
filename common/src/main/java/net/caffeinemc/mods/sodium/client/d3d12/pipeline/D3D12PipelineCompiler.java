// Owner: WP3 (pipelines + render pass) - implemented
package net.caffeinemc.mods.sodium.client.d3d12.pipeline;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import net.caffeinemc.mods.sodium.client.d3d12.core.D3D12Log;
import net.caffeinemc.mods.sodium.client.d3d12.core.DeviceContext;
import net.caffeinemc.mods.sodium.client.d3d12.core.FormatInfo;
import net.caffeinemc.mods.sodium.client.d3d12.core.Formats;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Enums;
import net.caffeinemc.mods.sodium.client.d3d12.interop.compiler.D3DShaderCompileException;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12PipelineState;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
        String name = info.name();
        String stageName = "setup";
        String hlsl = null;
        D3D12PipelineState psoWithDepth = null;
        D3D12PipelineState psoNoDepth = null;

        try {
            SlotAssignment assignment = SlotAssignment.assign(info.uniforms());
            BackendRenderPipeline.CreateInfo.Shader vertexShader = stage(info, ShaderType.VERTEX);
            BackendRenderPipeline.CreateInfo.Shader fragmentShader = stage(info, ShaderType.FRAGMENT);

            stageName = "vertex";

            TranslatedStage vertex = ShaderTranslator.translate(info, vertexShader, assignment);

            hlsl = vertex.hlsl();

            byte[] vertexDxbc = this.hlsl.compile(vertex.hlsl(), "main", "vs_5_0", name + "/vertex");

            stageName = "fragment";

            TranslatedStage fragment = ShaderTranslator.translate(info, fragmentShader, assignment);

            hlsl = fragment.hlsl();

            byte[] pixelDxbc = this.hlsl.compile(fragment.hlsl(), "main", "ps_5_0", name + "/fragment");

            stageName = "pipeline state";
            hlsl = null;

            SlotLayout layout = this.layouts.intern(SlotLayout.of(mergeSlots(info, assignment, vertex, fragment),
                    (info.pushConstantsSize() + 3) / 4, vertex.usesVertexInfo()));

            int[] strides = new int[16];
            int vertexBufferMask = 0;

            for (BackendRenderPipeline.CreateInfo.VertexBuffer buffer : info.vertexBuffers()) {
                if (buffer.bufferSlot() < 0 || buffer.bufferSlot() >= strides.length || buffer.stride() <= 0) {
                    throw new IllegalArgumentException("vertex buffer slot " + buffer.bufferSlot() + " with stride " + buffer.stride() + " is out of range");
                }

                strides[buffer.bufferSlot()] = buffer.stride();
            }

            // a draw needs a vertex buffer in every slot the vertex shader really reads; a slot that only contributes to the
            // layout (an element the shader does not declare) may stay empty
            for (BackendRenderPipeline.CreateInfo.AttribBinding attribute : info.attribBindings()) {
                if (strides[attribute.bufferSlot()] == 0) {
                    throw new IllegalArgumentException("attribute at location " + attribute.location() + " reads vertex buffer slot "
                            + attribute.bufferSlot() + " which has no vertex format");
                }

                vertexBufferMask |= 1 << attribute.bufferSlot();
            }

            boolean hasDepthState = info.depthStencilState() != null;

            psoWithDepth = PipelineStateBuilder.create(this.ctx, info, vertexDxbc, pixelDxbc, true);
            this.track(psoWithDepth);

            if (!hasDepthState) {
                psoNoDepth = PipelineStateBuilder.create(this.ctx, info, vertexDxbc, pixelDxbc, false);
                this.track(psoNoDepth);
            }

            D3D12PipelineState withDepth = psoWithDepth;
            D3D12PipelineState withoutDepth = psoNoDepth;
            int topology = StateMapper.inputAssemblerTopology(info.primitiveTopology());
            boolean fan = StateMapper.isFan(info.primitiveTopology());
            int mask = vertexBufferMask;

            return () -> this.finish(name, withDepth, withoutDepth, layout, strides, mask, topology, fan, hasDepthState);
        } catch (Throwable t) {
            if (psoNoDepth != null && this.unadopted.remove(psoNoDepth)) {
                closeQuietly(psoNoDepth);
            }

            if (psoWithDepth != null && this.unadopted.remove(psoWithDepth)) {
                closeQuietly(psoWithDepth);
            }

            if (t instanceof VirtualMachineError error) {
                throw error;
            }

            this.reportFailure(name, stageName, hlsl, t);

            return BackendRenderPipeline.Pending.NULL;
        }
    }

    /** {@code finishCompile()}: never throws; null when shutdown began or the wrapper cannot be built. */
    private @Nullable BackendRenderPipeline finish(String name, D3D12PipelineState withDepth, @Nullable D3D12PipelineState withoutDepth,
                                                   SlotLayout layout, int[] strides, int vertexBufferMask, int topology, boolean fan,
                                                   boolean hasDepthState) {
        try {
            if (!this.adopt(withDepth, withoutDepth)) {
                return null;
            }
        } catch (RuntimeException e) {
            D3D12Log.error("Failed to adopt the pipeline state of {}", name, e);

            return null;
        }

        try {
            return new D3D12RenderPipeline(this.ctx, name, withDepth, withoutDepth, layout, strides, vertexBufferMask, topology, fan, hasDepthState);
        } catch (RuntimeException e) {
            // the PSOs were never recorded, so they can be released at once
            D3D12Log.error("Failed to wrap the pipeline state of {}", name, e);
            closeQuietly(withDepth);

            if (withoutDepth != null) {
                closeQuietly(withoutDepth);
            }

            return null;
        }
    }

    private static BackendRenderPipeline.CreateInfo.Shader stage(BackendRenderPipeline.CreateInfo info, ShaderType type) {
        BackendRenderPipeline.CreateInfo.Shader found = null;

        for (BackendRenderPipeline.CreateInfo.Shader shader : info.shaders()) {
            if (shader.module().type() == type) {
                if (found != null) {
                    throw new IllegalArgumentException("pipeline '" + info.name() + "' has two " + type.getName() + " shaders");
                }

                found = shader;
            }
        }

        if (found == null) {
            throw new IllegalArgumentException("pipeline '" + info.name() + "' has no " + type.getName() + " shader");
        }

        return found;
    }

    /** Merges the facts of the two stages: activity and cube-ness are ORed, the texel element class must agree. */
    private static List<SlotInfo> mergeSlots(BackendRenderPipeline.CreateInfo info, SlotAssignment assignment, TranslatedStage vertex,
                                             TranslatedStage fragment) {
        List<BindGroupLayout.UniformDescription> uniforms = info.uniforms();
        List<SlotInfo> slots = new ArrayList<>(uniforms.size());

        for (int i = 0; i < uniforms.size(); i++) {
            BindGroupLayout.UniformDescription uniform = uniforms.get(i);
            SlotKind kind = assignment.kind(i);
            boolean active = vertex.activeSlots().get(i) || fragment.activeSlots().get(i);
            boolean cube = vertex.cubeSlots().get(i) || fragment.cubeSlots().get(i);
            int baseType = kind == SlotKind.UBO ? 0 : Formats.BASE_FLOAT;
            GpuFormat texelFormat = null;

            if (kind == SlotKind.TEXEL) {
                texelFormat = uniform.gpuFormat();

                if (texelFormat == null) {
                    throw new IllegalArgumentException("texel buffer '" + uniform.name() + "' has no format");
                }

                FormatInfo format = Formats.of(texelFormat);

                if (format.depth() || format.srvFormat() == Enums.Format.UNKNOWN) {
                    throw new IllegalArgumentException("texel buffer '" + uniform.name() + "' has the format " + texelFormat
                            + " which cannot be read through a shader resource view");
                }

                int fromVertex = vertex.texelBaseTypes()[i];
                int fromFragment = fragment.texelBaseTypes()[i];

                if (fromVertex != 0 && fromFragment != 0 && fromVertex != fromFragment) {
                    throw new IllegalArgumentException("texel buffer '" + uniform.name() + "' is fetched as different element classes by the two stages");
                }

                baseType = fromVertex != 0 ? fromVertex : fromFragment != 0 ? fromFragment : format.sampledBaseType();
            }

            slots.add(new SlotInfo(uniform.name(), kind, assignment.register(i), active, cube, baseType, texelFormat));
        }

        return slots;
    }

    private void reportFailure(String name, String stage, @Nullable String hlsl, Throwable t) {
        StringBuilder message = new StringBuilder("Failed to compile the Direct3D 12 pipeline '").append(name).append("' (")
                .append(stage).append("): ").append(t.getMessage() == null ? t.toString() : t.getMessage());

        if (t instanceof D3DShaderCompileException compile && !compile.getCompilerOutput().isEmpty()
                && (t.getMessage() == null || !t.getMessage().contains(compile.getCompilerOutput()))) {
            message.append('\n').append(compile.getCompilerOutput());
        }

        if (hlsl != null) {
            message.append("\nFirst lines of the generated HLSL:");

            int from = 0;

            for (int line = 0; line < 20 && from < hlsl.length(); line++) {
                int end = hlsl.indexOf('\n', from);

                if (end < 0) {
                    end = hlsl.length();
                }

                message.append("\n  ").append(hlsl, from, end);
                from = end + 1;
            }

            Path dump = dumpHlsl(name, stage, hlsl);

            if (dump != null) {
                message.append("\nThe complete HLSL was written to ").append(dump);
            }
        }

        D3D12Log.error("{}", message, t);
    }

    private static @Nullable Path dumpHlsl(String pipeline, String stage, String hlsl) {
        try {
            Path directory = Path.of(System.getProperty("java.io.tmpdir"), "sodium-d3d12");
            String file = (pipeline + "-" + stage).replaceAll("[^A-Za-z0-9._-]", "_") + ".hlsl";

            Files.createDirectories(directory);

            return Files.write(directory.resolve(file), hlsl.getBytes(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            D3D12Log.debug("Cannot dump the HLSL of {}: {}", pipeline, e.toString());

            return null;
        }
    }

    private static void closeQuietly(D3D12PipelineState pso) {
        try {
            pso.close();
        } catch (Throwable t) {
            D3D12Log.error("Failed to release a pipeline state object", t);
        }
    }

    boolean enter() {
        synchronized (this.gate) {
            if (this.closing || this.ctx.isClosed()) {
                return false;
            }

            this.inFlight++;

            return true;
        }
    }

    void exit() {
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
        // building touches the device, the root signatures and the compilers: it passes the shutdown gate like a compile
        return this.guarded("the built-in pipeline " + key, () -> {
            synchronized (this.builtins) {
                D3D12RenderPipeline pipeline = this.builtins.get(key);

                if (pipeline == null || pipeline.isClosed()) {
                    pipeline = factory.get();
                    this.builtins.put(key, pipeline);
                }

                return pipeline;
            }
        });
    }

    /**
     * Runs {@code body} inside the shutdown gate: for work of the render thread that touches the device, the root signatures
     * or the compilers outside {@link #compile} (the built-in pipelines).
     *
     * @throws IllegalStateException if the compiler or the device is closing
     */
    <T> T guarded(String what, Supplier<T> body) {
        if (!this.enter()) {
            throw new IllegalStateException("The Direct3D 12 device is closing: cannot build " + what);
        }

        try {
            return body.get();
        } finally {
            this.exit();
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
