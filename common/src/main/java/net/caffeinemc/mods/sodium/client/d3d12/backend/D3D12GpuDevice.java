// Owner: WP1 (device core)
package net.caffeinemc.mods.sodium.client.d3d12.backend;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import net.caffeinemc.mods.sodium.client.d3d12.command.D3D12CommandEncoder;
import net.caffeinemc.mods.sodium.client.d3d12.core.DeviceContext;
import net.caffeinemc.mods.sodium.client.d3d12.interop.dxgi.DxgiFactory;
import net.caffeinemc.mods.sodium.client.d3d12.pipeline.D3D12PipelineCompiler;
import net.caffeinemc.mods.sodium.client.d3d12.surface.D3D12Surface;
import net.caffeinemc.mods.sodium.client.d3d12.surface.WindowHandles;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.OptionalDouble;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The composition root and facade of the backend: <b>the class name contains {@code D3D12}</b>, which is Sodium's second
 * {@code DrawBackend} detection path (the simple class name is normalised and checked in addition to
 * {@code DeviceInfo.backendName}; do not rename it).
 *
 * <h2>{@link #create}: construction order</h2>
 * Any exception releases everything built so far (reverse order; call {@code ctx.markGpuIdle()} first so deferred
 * releases run inline - nothing but the start-up probe, which idles, was submitted) and is rethrown as
 * {@link BackendCreationException} ({@code OTHER}/{@code PLATFORM_ERROR}); Minecraft's backend loop catches nothing else.
 * <ol>
 *     <li>(done by the caller) debug layer enabled, {@code DxgiFactory} created;</li>
 *     <li>{@code AdapterSelector.select} -> adapter, {@code D3D12Device}, {@code DeviceCaps};</li>
 *     <li>DIRECT command queue; {@code ErrorPolicy}; {@code DebugMessageSink.create}; {@code FrameClock.create};
 *     {@code DeferralQueue}; {@code ObjectRegistry}; {@code DeviceInfoBuilder.build};</li>
 *     <li>{@code DeviceContext} (render thread = the creating thread), then the attaches in dependency order:
 *     {@code DescriptorServices.create}, {@code ResourceStateTracker}, {@code ResourceFactory},
 *     {@code RootSignatures.create}, {@code D3D12CommandEncoder} (so {@code getDeviceInfo()},
 *     {@code createTimestampQueryPool(1024)}, {@code getTimestampCalibrationOffset()} work before the frontend asks for the
 *     encoder - Tracy does exactly that), {@code D3D12PipelineCompiler} (also attached to the context with
 *     {@code attachPipelineCompiler}: {@code BuiltinPipelines} and {@code StartupProbes} use its layouts and HLSL compiler);</li>
 *     <li>{@code StartupProbes.measure(ctx)} -> {@code ctx.setVertexIdSemantics(..)}. A <em>measurement</em> failure is
 *     swallowed (keep the default), a <em>lost device</em> is not: inside the outer try, <b>outside</b> the probe's
 *     catch, {@code ctx.errors().checkDeviceRemoved("start-up probe")} and {@code ctx.errors().checkHealthy()} follow, so a
 *     device that the probe's draw removed (TDR) becomes a {@code BackendCreationException(PLATFORM_ERROR)} and the
 *     backend selection can fall back, instead of a {@code GpuDeviceLossException} at the first real frame.</li>
 * </ol>
 * The presentation probe ({@code HiddenWindowProbe}), the mandatory present-pipeline check
 * ({@code BuiltinPipelines.fullscreenBlit}) and {@code new FrontendGpuDevice(..)} are done by
 * {@code D3D12Backend.createDevice}, not here, so that the device can be created without SDL in tests.
 *
 * <h2>{@link #close}: shutdown order (idempotent, never throws, never hangs)</h2>
 * <ol>
 *     <li><b>{@code compiler.close()} first</b>: pipeline compiles run on worker threads that outlive the renderer
 *     (Minecraft shuts the executors down after the device). The gate rejects new compiles, waits up to 15 s for the
 *     running ones and releases the never-adopted PSOs. If it reports {@code !isDrained()}, steps 5 and 6 skip the root
 *     signatures and the device (leak at process exit instead of a native crash).</li>
 *     <li>{@code ctx.beginClose()}: <em>closing</em> - {@code isUsable()} fails, no new work - but {@code defer} still
 *     queues (a frame may be in flight; a worker closing a pipeline must not release it under the GPU).</li>
 *     <li>{@code encoder.shutdown()}: discard the unsubmitted frame, {@code clock.flushGpu(10 s)}, <b>then</b>
 *     {@code ctx.markGpuIdle()} (from here {@code defer} runs inline), drain the deferral queue until empty (read-back
 *     callbacks of submitted frames run), close the pass manager (fan buffer), free transient memory, drain again, release
 *     the command list and allocators. The device never touches the encoder's internals.</li>
 *     <li>{@code debug.drainToLog()}, then {@code ObjectRegistry.closeAll()} (the never-closed Tracy pool, leaked
 *     pipelines/samplers, the built-in blit; their deferred releases now run inline) with a WARN count.</li>
 *     <li>Release in reverse construction order: root signatures (only if the compiler drained), descriptor services,
 *     clock/fences, queue.</li>
 *     <li>The device ({@code ReportLiveObjects} when the debug layer is on; only if the compiler drained), and the factory
 *     only if this device owns it.</li>
 * </ol>
 * Every step has its own try/catch so one failure does not skip the rest. The surface was closed earlier by Minecraft (it
 * idled the queue). After this nothing is called.
 *
 * <p>Threading: the render thread, except {@link #compilePipeline} (worker threads) and {@link #getDeviceInfo} (any).
 */
public final class D3D12GpuDevice implements GpuDeviceBackend {
    private final DeviceContext ctx;
    private final D3D12PipelineCompiler compiler;
    private final boolean ownsFactory;
    private boolean closed;

    D3D12GpuDevice(DeviceContext ctx, D3D12PipelineCompiler compiler, boolean ownsFactory) {
        this.ctx = ctx;
        this.compiler = compiler;
        this.ownsFactory = ownsFactory;
    }

    /**
     * Builds the device as described above.
     *
     * @param factory     the DXGI factory (the backend owns it when {@code ownsFactory} is false: the device only borrows)
     * @param ownsFactory true if {@link #close()} must close the factory
     */
    public static D3D12GpuDevice create(DeviceOptions options, DxgiFactory factory, boolean ownsFactory) throws BackendCreationException {
        throw new UnsupportedOperationException("WP1: construction order in the class comment, cleanup on every failure");
    }

    /**
     * Test entry: debug layer per options, own factory (closed with the device), no window, no presentation probe, no
     * frontend wrapper. The returned object is used through the public {@code GpuDeviceBackend} /
     * {@code CommandEncoderBackend} / {@code RenderPassBackend} / {@code TransientMemory} interfaces.
     */
    public static D3D12GpuDevice createForTests(DeviceOptions options) throws BackendCreationException {
        throw new UnsupportedOperationException("WP1: D3D12.enableDebugLayer per options, Dxgi.createFactory, create(options, factory, true)");
    }

    /** @return the service hub (tests and the surface/probes use it) */
    public DeviceContext context() {
        return this.ctx;
    }

    @Override
    public GpuSurfaceBackend createSurface(long windowHandle, BooleanSupplier isIconified) {
        return new D3D12Surface(this.ctx, WindowHandles.hwnd(windowHandle), isIconified);
    }

    @Override
    public CommandEncoderBackend createCommandEncoder() {
        return this.ctx.encoder();
    }

    @Override
    public GpuSampler createSampler(AddressMode addressModeU, AddressMode addressModeV, FilterMode minFilter,
                                    FilterMode magFilter, int maxAnisotropy, OptionalDouble maxLod) {
        return this.ctx.resources().createSampler(addressModeU, addressModeV, minFilter, magFilter, maxAnisotropy, maxLod);
    }

    @Override
    public GpuTexture createTexture(@Nullable String label, int usage, GpuFormat format, int width, int height,
                                    int depthOrLayers, int mipLevels) {
        return this.ctx.resources().createTexture(label, usage, format, width, height, depthOrLayers, mipLevels);
    }

    @Override
    public GpuTextureView createTextureView(GpuTexture texture, int baseMipLevel, int mipLevels) {
        return this.ctx.resources().createView(texture, baseMipLevel, mipLevels);
    }

    @Override
    public GpuBuffer createBuffer(@Nullable Supplier<String> label, int usage, long size) {
        return this.ctx.resources().createBuffer(label, usage, size);
    }

    @Override
    public GpuBuffer createBuffer(@Nullable Supplier<String> label, int usage, ByteBuffer data) {
        return this.ctx.resources().createBuffer(label, usage, data);
    }

    @Override
    public List<String> getLastDebugMessages() {
        return this.ctx.debug().recentMessages();
    }

    @Override
    public boolean isDebuggingEnabled() {
        return this.ctx.options().debuggingEnabled();
    }

    /** Worker threads. Returns {@code Pending.NULL} on any failure; never throws. */
    @Override
    public BackendRenderPipeline.Pending compilePipeline(BackendRenderPipeline.CreateInfo pipelineCreateInfo) {
        return this.compiler.compile(pipelineCreateInfo);
    }

    @Override
    public void close() {
        throw new UnsupportedOperationException("WP1: shutdown order in the class comment");
    }

    @Override
    public GpuQueryPool createTimestampQueryPool(int size) {
        return this.ctx.encoder().createQueryPool(size);
    }

    /**
     * {@code ticks * timestampPeriod + offset} is in the {@code System.nanoTime()} base:
     * {@code offset = Qpc.toNanos(cpuTicks) - gpuTicks * period} from {@code queue.getClockCalibration()}. Works before the
     * first frame; called at construction by Tracy and every 5 s afterwards.
     */
    @Override
    public long getTimestampCalibrationOffset() {
        throw new UnsupportedOperationException("WP1: queue.getClockCalibration(), Qpc.toNanos, info.timestampPeriod()");
    }

    /** Immutable, thread-safe, identical on every call. */
    @Override
    public DeviceInfo getDeviceInfo() {
        return this.ctx.info();
    }

    boolean ownsFactory() {
        return this.ownsFactory;
    }

    boolean closedFlag() {
        return this.closed;
    }
}
