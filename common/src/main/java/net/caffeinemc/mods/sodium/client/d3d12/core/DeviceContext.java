// Owner: WP1 (device core) - fully implemented holder
package net.caffeinemc.mods.sodium.client.d3d12.core;

import com.mojang.renderpearl.api.device.DeviceInfo;
import net.caffeinemc.mods.sodium.client.d3d12.backend.DeviceOptions;
import net.caffeinemc.mods.sodium.client.d3d12.command.D3D12CommandEncoder;
import net.caffeinemc.mods.sodium.client.d3d12.descriptor.DescriptorServices;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12CommandQueue;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12Device;
import net.caffeinemc.mods.sodium.client.d3d12.interop.dxgi.DxgiFactory;
import net.caffeinemc.mods.sodium.client.d3d12.pipeline.D3D12PipelineCompiler;
import net.caffeinemc.mods.sodium.client.d3d12.pipeline.RootSignatures;
import net.caffeinemc.mods.sodium.client.d3d12.resource.ResourceFactory;
import net.caffeinemc.mods.sodium.client.d3d12.state.ResourceStateTracker;

/**
 * The service hub every package receives in its constructor: one object that holds references to all the long-lived
 * services of a device. Created by {@code D3D12GpuDevice.create}, which fills it in construction order with the
 * {@code attach*} methods (each callable exactly once, from the creating thread, before the context is published to
 * other threads). After construction the fields are effectively final; the getters are safe to call from any thread
 * (worker threads in {@code compilePipeline} read {@code d3d()}, {@code caps()}, {@code options()},
 * {@code rootSignatures()}, nothing else).
 *
 * <p>Construction order and what is available when:
 * {@code d3d/queue/factory/caps/options/info} -> {@code errors} -> {@code debug} -> {@code clock} -> {@code deferral}
 * -> {@code registry} -> {@code descriptors} -> {@code tracker} -> {@code resources} -> {@code rootSignatures} ->
 * {@code encoder} -> {@code pipelineCompiler}. A getter called before its attach throws {@link IllegalStateException}.
 *
 * <p>{@link #defer} is the one entry point for deferred destruction: render thread -> tag = current frame; other threads
 * -> tag = current + 1 (conservative: they cannot know whether the render thread is mid-frame).
 *
 * <p><b>Two shutdown states (invariant I4 must hold until the GPU is really idle).</b>
 * <ul>
 *     <li><em>closing</em> ({@link #beginClose()}, {@link #isClosed()} is true): new work is refused - every
 *     {@code isUsable()} check fails, the pipeline compiler rejects new compiles - but {@link #defer} still <b>queues</b>:
 *     frames that were submitted earlier may still be executing, and a worker or mod thread that closes a pipeline /
 *     resource in this window must not release it under the GPU.</li>
 *     <li><em>GPU idle</em> ({@link #markGpuIdle()}, {@link #isGpuIdle()}): set by {@code D3D12CommandEncoder.shutdown()}
 *     only after {@code flushGpu} succeeded (or timed out and was logged). From then on {@link #defer} runs the action
 *     inline; this state is final.</li>
 * </ul>
 */
public final class DeviceContext {
    private final D3D12Device d3d;
    private final D3D12CommandQueue queue;
    private final DxgiFactory factory;
    private final DeviceCaps caps;
    private final DeviceInfo info;
    private final DeviceOptions options;
    private final Thread renderThread;

    private volatile ErrorPolicy errors;
    private volatile DebugMessageSink debug = DebugMessageSink.disabled();
    private volatile FrameClock clock;
    private volatile DeferralQueue deferral;
    private volatile ObjectRegistry registry;
    private volatile DescriptorServices descriptors;
    private volatile ResourceStateTracker tracker;
    private volatile ResourceFactory resources;
    private volatile RootSignatures rootSignatures;
    private volatile D3D12CommandEncoder encoder;
    private volatile D3D12PipelineCompiler pipelineCompiler;
    private volatile VertexIdSemantics vertexIdSemantics = VertexIdSemantics.DEFAULT;
    private volatile boolean closing;
    private volatile boolean gpuIdle;

    /** @param renderThread the thread that created the device (Minecraft's game thread) */
    public DeviceContext(D3D12Device d3d, D3D12CommandQueue queue, DxgiFactory factory, DeviceCaps caps, DeviceInfo info,
                         DeviceOptions options, Thread renderThread) {
        this.d3d = d3d;
        this.queue = queue;
        this.factory = factory;
        this.caps = caps;
        this.info = info;
        this.options = options;
        this.renderThread = renderThread;
    }

    public D3D12Device d3d() {
        return this.d3d;
    }

    public D3D12CommandQueue queue() {
        return this.queue;
    }

    /** @return the DXGI factory (owned by the backend or, in tests, by the device; never close it elsewhere) */
    public DxgiFactory factory() {
        return this.factory;
    }

    public DeviceCaps caps() {
        return this.caps;
    }

    public DeviceInfo info() {
        return this.info;
    }

    public DeviceOptions options() {
        return this.options;
    }

    public Thread renderThread() {
        return this.renderThread;
    }

    public ErrorPolicy errors() {
        return require(this.errors, "errors");
    }

    public DebugMessageSink debug() {
        return this.debug;
    }

    public FrameClock clock() {
        return require(this.clock, "clock");
    }

    public DeferralQueue deferral() {
        return require(this.deferral, "deferral");
    }

    public ObjectRegistry registry() {
        return require(this.registry, "registry");
    }

    public DescriptorServices descriptors() {
        return require(this.descriptors, "descriptors");
    }

    public ResourceStateTracker tracker() {
        return require(this.tracker, "tracker");
    }

    public ResourceFactory resources() {
        return require(this.resources, "resources");
    }

    public RootSignatures rootSignatures() {
        return require(this.rootSignatures, "rootSignatures");
    }

    public D3D12CommandEncoder encoder() {
        return require(this.encoder, "encoder");
    }

    /**
     * @return the pipeline compiler: its {@code layouts()} intern table and shared {@code HlslCompiler} are also used by
     * {@code BuiltinPipelines}, so built-in and game pipelines share layout ids and the shader cache
     */
    public D3D12PipelineCompiler pipelineCompiler() {
        return require(this.pipelineCompiler, "pipelineCompiler");
    }

    /** @return what {@code SV_VertexID} contains on this adapter; {@link VertexIdSemantics#DEFAULT} until measured */
    public VertexIdSemantics vertexIdSemantics() {
        return this.vertexIdSemantics;
    }

    public boolean strict() {
        return this.options.strict();
    }

    /** @return true if debug labels are on: label suppliers are evaluated, objects are named, passes get PIX events */
    public boolean labels() {
        return this.options.debuggingEnabled();
    }

    /** @return true from {@link #beginClose()} on (closing or closed): new commands and new compiles are refused */
    public boolean isClosed() {
        return this.closing;
    }

    /** @return true once the GPU is known to be idle during shutdown: {@link #defer} then runs actions inline */
    public boolean isGpuIdle() {
        return this.gpuIdle;
    }

    public void attachErrors(ErrorPolicy errors) {
        this.errors = once(this.errors, errors, "errors");
    }

    public void attachDebug(DebugMessageSink debug) {
        this.debug = debug;
    }

    public void attachClock(FrameClock clock) {
        this.clock = once(this.clock, clock, "clock");
    }

    public void attachDeferral(DeferralQueue deferral) {
        this.deferral = once(this.deferral, deferral, "deferral");
    }

    public void attachRegistry(ObjectRegistry registry) {
        this.registry = once(this.registry, registry, "registry");
    }

    public void attachDescriptors(DescriptorServices descriptors) {
        this.descriptors = once(this.descriptors, descriptors, "descriptors");
    }

    public void attachTracker(ResourceStateTracker tracker) {
        this.tracker = once(this.tracker, tracker, "tracker");
    }

    public void attachResources(ResourceFactory resources) {
        this.resources = once(this.resources, resources, "resources");
    }

    public void attachRootSignatures(RootSignatures rootSignatures) {
        this.rootSignatures = once(this.rootSignatures, rootSignatures, "rootSignatures");
    }

    public void attachEncoder(D3D12CommandEncoder encoder) {
        this.encoder = once(this.encoder, encoder, "encoder");
    }

    public void attachPipelineCompiler(D3D12PipelineCompiler pipelineCompiler) {
        this.pipelineCompiler = once(this.pipelineCompiler, pipelineCompiler, "pipelineCompiler");
    }

    /** Called once by {@code StartupProbes} (through {@code D3D12GpuDevice.create}) with the measured result. */
    public void setVertexIdSemantics(VertexIdSemantics semantics) {
        this.vertexIdSemantics = semantics;
    }

    /**
     * Marks the device as closing: {@code isUsable()} checks fail and no new work is accepted, but {@link #defer} keeps
     * queueing until {@link #markGpuIdle()} (in-flight frames may still use what is being closed).
     */
    public void beginClose() {
        this.closing = true;
    }

    /**
     * Declares the GPU idle (every queued frame finished, or the 10 s wait timed out and was logged): from now on
     * {@link #defer} runs actions inline. Implies {@link #beginClose()}. Also called by {@code D3D12GpuDevice.create}
     * on its failure path before it releases what it built (nothing but the start-up probe, which idles, was submitted).
     */
    public void markGpuIdle() {
        this.closing = true;
        this.gpuIdle = true;
    }

    /**
     * Runs {@code action} once the frame in which the caller closed something has completed on the GPU (invariant I4).
     * An exception thrown by the action is logged by the queue, never propagated. Runs inline only after
     * {@link #markGpuIdle()}.
     */
    public void defer(Runnable action) {
        if (this.gpuIdle) {
            try {
                action.run();
            } catch (Throwable t) {
                D3D12Log.error("Deferred D3D12 action failed during shutdown", t);
            }

            return;
        }

        long frame = this.clock().current();

        this.deferral().add(Thread.currentThread() == this.renderThread ? frame : frame + 1, action);

        if (this.gpuIdle) {
            // lost the race with the shutdown drain (the GPU went idle after the check above): nobody else will run it
            this.deferral().drainAll();
        }
    }

    private static <T> T require(T service, String name) {
        if (service == null) {
            throw new IllegalStateException("The Direct3D 12 device is not fully constructed yet: " + name);
        }

        return service;
    }

    private static <T> T once(T current, T value, String name) {
        if (current != null) {
            throw new IllegalStateException("DeviceContext." + name + " attached twice");
        }

        return value;
    }
}
