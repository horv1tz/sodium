// Owner: WP1 (device core) - implemented
package net.caffeinemc.mods.sodium.client.d3d12.backend;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.GpuDeviceLossException;
import com.mojang.renderpearl.api.device.GpuOutOfMemoryException;
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
import net.caffeinemc.mods.sodium.client.d3d12.core.D3D12Log;
import net.caffeinemc.mods.sodium.client.d3d12.core.DebugMessageSink;
import net.caffeinemc.mods.sodium.client.d3d12.core.DeferralQueue;
import net.caffeinemc.mods.sodium.client.d3d12.core.DeviceCaps;
import net.caffeinemc.mods.sodium.client.d3d12.core.DeviceContext;
import net.caffeinemc.mods.sodium.client.d3d12.core.ErrorPolicy;
import net.caffeinemc.mods.sodium.client.d3d12.core.FrameClock;
import net.caffeinemc.mods.sodium.client.d3d12.core.ObjectRegistry;
import net.caffeinemc.mods.sodium.client.d3d12.core.Qpc;
import net.caffeinemc.mods.sodium.client.d3d12.descriptor.DescriptorServices;
import net.caffeinemc.mods.sodium.client.d3d12.interop.D3D12Exception;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Enums;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Iids;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12CommandQueue;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12Debug;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12Device;
import net.caffeinemc.mods.sodium.client.d3d12.interop.dxgi.Dxgi;
import net.caffeinemc.mods.sodium.client.d3d12.interop.dxgi.DxgiAdapter;
import net.caffeinemc.mods.sodium.client.d3d12.interop.dxgi.DxgiFactory;
import net.caffeinemc.mods.sodium.client.d3d12.pipeline.D3D12PipelineCompiler;
import net.caffeinemc.mods.sodium.client.d3d12.pipeline.RootSignatures;
import net.caffeinemc.mods.sodium.client.d3d12.probe.StartupProbes;
import net.caffeinemc.mods.sodium.client.d3d12.resource.ResourceCounters;
import net.caffeinemc.mods.sodium.client.d3d12.resource.ResourceFactory;
import net.caffeinemc.mods.sodium.client.d3d12.state.ResourceStateTracker;
import net.caffeinemc.mods.sodium.client.d3d12.surface.D3D12Surface;
import net.caffeinemc.mods.sodium.client.d3d12.surface.WindowHandles;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.BiConsumer;
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
    /** Test seam: called with (step name, the object just built) after each construction step of {@link #create}; may throw. */
    private static volatile @Nullable BiConsumer<String, Object> constructionHook;

    private final DeviceContext ctx;
    private final D3D12PipelineCompiler compiler;
    private final boolean ownsFactory;
    private volatile boolean closed;

    D3D12GpuDevice(DeviceContext ctx, D3D12PipelineCompiler compiler, boolean ownsFactory) {
        this.ctx = ctx;
        this.compiler = compiler;
        this.ownsFactory = ownsFactory;
    }

    /**
     * Builds the device as described above.
     *
     * <p>Every failure - including unchecked exceptions and errors such as {@code UnsatisfiedLinkError} or
     * {@code IllegalCallerException} - releases what was built so far and is rethrown as a
     * {@link BackendCreationException} (the original is its cause); only {@link VirtualMachineError}s propagate as they
     * are (after the release). If {@code ownsFactory} the factory is closed on failure as well.
     *
     * @param factory     the DXGI factory (the backend owns it when {@code ownsFactory} is false: the device only borrows)
     * @param ownsFactory true if {@link #close()} must close the factory
     */
    public static D3D12GpuDevice create(DeviceOptions options, DxgiFactory factory, boolean ownsFactory) throws BackendCreationException {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(factory, "factory");

        AdapterSelector.Selection selection;

        try {
            selection = AdapterSelector.select(factory, options);
        } catch (BackendCreationException e) {
            closeQuietly(ownsFactory ? factory : null);

            throw e;
        } catch (VirtualMachineError e) {
            closeQuietly(ownsFactory ? factory : null);

            throw e;
        } catch (Throwable t) {
            closeQuietly(ownsFactory ? factory : null);

            throw translateCreationFailure(t, "<no adapter>");
        }

        Construction construction = new Construction(options, factory, ownsFactory, selection);

        try {
            return construction.run();
        } catch (VirtualMachineError e) {
            construction.rollback();

            throw e;
        } catch (Throwable t) {
            construction.rollback();

            throw translateCreationFailure(t, selection.caps().adapterName());
        } finally {
            // the device does not need the adapter wrapper any more (it holds its own references)
            closeQuietly(selection.adapter());
        }
    }

    /**
     * Test entry: debug layer per options, own factory (closed with the device), no window, no presentation probe, no
     * frontend wrapper. The returned object is used through the public {@code GpuDeviceBackend} /
     * {@code CommandEncoderBackend} / {@code RenderPassBackend} / {@code TransientMemory} interfaces.
     */
    public static D3D12GpuDevice createForTests(DeviceOptions options) throws BackendCreationException {
        boolean debugLayer = options.useValidationLayers() && enableDebugLayer(options);
        DxgiFactory factory;

        try {
            factory = Dxgi.createFactory(debugLayer);
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable t) {
            throw translateCreationFailure(t, "<no adapter>");
        }

        return create(options, factory, true);
    }

    /**
     * Enables the D3D12 debug layer (and GPU-based validation when requested) before any device exists.
     *
     * @return true if the layer is on; a missing layer ("Graphics Tools" not installed) or a failure is a WARN, never fatal
     */
    static boolean enableDebugLayer(DeviceOptions options) {
        try (D3D12Debug debug = D3D12.tryGetDebugInterface()) {
            if (debug == null) {
                D3D12Log.warn("The Direct3D 12 debug layer was requested but is not installed (Windows optional feature \"Graphics Tools\")");

                return false;
            }

            debug.enableDebugLayer();

            if (options.gpuBasedValidation() && !debug.setEnableGPUBasedValidation(true)) {
                D3D12Log.warn("GPU-based validation was requested but is not supported by this runtime");
            }

            return true;
        } catch (VirtualMachineError e) {
            throw e;
        } catch (RuntimeException e) {
            D3D12Log.warn("Failed to enable the Direct3D 12 debug layer: {}", e.toString());

            return false;
        }
    }

    /**
     * Everything {@link #create} builds, in construction order, plus the reverse-order release of whatever exists. Kept in
     * one place so the failure path cannot drift away from the success path.
     */
    private static final class Construction {
        private final DeviceOptions options;
        private final DxgiFactory factory;
        private final boolean ownsFactory;
        private final AdapterSelector.Selection selection;

        private D3D12CommandQueue queue;
        private ErrorPolicy errors;
        private DebugMessageSink debug;
        private FrameClock clock;
        private DeferralQueue deferral;
        private ObjectRegistry registry;
        private DeviceContext ctx;
        private DescriptorServices descriptors;
        private RootSignatures rootSignatures;
        private D3D12CommandEncoder encoder;
        private D3D12PipelineCompiler compiler;
        private boolean succeeded;

        Construction(DeviceOptions options, DxgiFactory factory, boolean ownsFactory, AdapterSelector.Selection selection) {
            this.options = options;
            this.factory = factory;
            this.ownsFactory = ownsFactory;
            this.selection = selection;
        }

        private static void step(String name, Object built) {
            BiConsumer<String, Object> hook = constructionHook;

            if (hook != null) {
                hook.accept(name, built);
            }
        }

        D3D12GpuDevice run() {
            D3D12Device device = this.selection.device();
            step("device", device);
            DeviceCaps caps = this.selection.caps();

            this.queue = device.createCommandQueue(Enums.CommandListType.DIRECT);
            step("queue", this.queue);
            this.errors = new ErrorPolicy(device, this.options.strict());
            step("errors", this.errors);
            this.debug = this.options.useValidationLayers() ? DebugMessageSink.create(device, this.options) : DebugMessageSink.disabled();
            this.errors.attachDebug(this.debug);
            step("debug", this.debug);
            this.clock = FrameClock.create(device, this.queue, this.errors);
            step("clock", this.clock);
            this.deferral = new DeferralQueue();
            this.registry = new ObjectRegistry();

            DeviceInfo info = DeviceInfoBuilder.build(caps, this.options, driverInfo(this.selection.adapter(), caps), extensions(caps));

            this.ctx = new DeviceContext(device, this.queue, this.factory, caps, info, this.options, Thread.currentThread());
            this.ctx.attachErrors(this.errors);
            this.ctx.attachDebug(this.debug);
            this.ctx.attachClock(this.clock);
            this.ctx.attachDeferral(this.deferral);
            this.ctx.attachRegistry(this.registry);
            step("context", this.ctx);

            this.descriptors = DescriptorServices.create(device, this.options);
            this.ctx.attachDescriptors(this.descriptors);
            step("descriptors", this.descriptors);
            this.ctx.attachTracker(new ResourceStateTracker());
            step("tracker", this.ctx.tracker());
            this.ctx.attachResources(new ResourceFactory(this.ctx));
            step("resources", this.ctx.resources());

            this.rootSignatures = RootSignatures.create(device);
            this.ctx.attachRootSignatures(this.rootSignatures);
            step("rootSignatures", this.rootSignatures);

            // the encoder's constructor builds the recorder, engines, transient memory and pass manager from explicit
            // references: ctx.encoder() does not resolve until the attach below
            this.encoder = new D3D12CommandEncoder(this.ctx);
            this.ctx.attachEncoder(this.encoder);
            step("encoder", this.encoder);

            this.compiler = new D3D12PipelineCompiler(this.ctx);
            this.ctx.attachPipelineCompiler(this.compiler);
            step("compiler", this.compiler);

            // a measurement failure keeps VertexIdSemantics.DEFAULT inside the probe; a lost device is rethrown by it and
            // both checks below run outside any catch-all, so the failure becomes a BackendCreationException
            this.ctx.setVertexIdSemantics(StartupProbes.measure(this.ctx));
            step("probe", this.ctx.vertexIdSemantics());
            this.errors.checkDeviceRemoved("start-up probe");
            this.errors.checkHealthy();

            D3D12GpuDevice result = new D3D12GpuDevice(this.ctx, this.compiler, this.ownsFactory);

            this.succeeded = true;

            return result;
        }

        /** Releases everything that exists, in reverse construction order; every step isolated, never throws. */
        void rollback() {
            if (this.succeeded) {
                return;
            }

            if (this.ctx != null) {
                // nothing but the (idling) start-up probe was ever submitted: deferred releases may run inline
                attempt("mark the GPU idle", this.ctx::markGpuIdle);
            }

            boolean drained = true;

            if (this.compiler != null) {
                attempt("close the pipeline compiler", this.compiler::close);
                drained = this.compiler.isDrained();
            }

            if (this.encoder != null) {
                attempt("shut down the command encoder", this.encoder::shutdown);
            }

            if (this.ctx != null) {
                attempt("drain the debug messages", () -> this.ctx.debug().drainToLog());
            }

            if (this.registry != null) {
                attempt("close registered objects", this.registry::closeAll);
            }

            if (this.deferral != null) {
                attempt("drain the deferral queue", this.deferral::drainAll);
            }

            if (this.debug != null) {
                // the info queue holds a reference to the device: without this the device would never be released
                attempt("release the debug message sink", () -> {
                    this.debug.drainToLog();
                    this.debug.close();
                });
            }

            if (this.rootSignatures != null && drained) {
                attempt("release the root signatures", this.rootSignatures::close);
            }

            if (this.descriptors != null) {
                attempt("release the descriptor services", this.descriptors::close);
            }

            if (this.clock != null) {
                attempt("release the frame fences", this.clock::close);
            }

            if (this.queue != null) {
                attempt("release the command queue", this.queue::close);
            }

            if (drained) {
                attempt("release the device", this.selection.device()::close);
            } else {
                D3D12Log.warn("The Direct3D 12 device is deliberately not released: a pipeline compile did not finish");
            }

            if (this.ownsFactory) {
                attempt("release the DXGI factory", this.factory::close);
            }
        }
    }

    private static void attempt(String what, Runnable step) {
        try {
            step.run();
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable t) {
            D3D12Log.warn("Direct3D 12 device cleanup step failed ({}): {}", what, t.toString());
        }
    }

    private static void closeQuietly(@Nullable AutoCloseable object) {
        if (object != null) {
            try {
                object.close();
            } catch (Exception e) {
                D3D12Log.debug("close failed: {}", e.toString());
            }
        }
    }

    /**
     * Maps an arbitrary start-up failure to the one exception type Minecraft's backend loop catches. The messages are
     * short and human readable (they are shown to the user); the original exception is kept as the cause.
     */
    private static BackendCreationException translateCreationFailure(Throwable t, String adapter) {
        if (t instanceof BackendCreationException e) {
            return e;
        }

        String message;

        if (t instanceof IllegalCallerException) {
            message = "Native access to Direct3D 12 is not permitted: start the game with --enable-native-access=ALL-UNNAMED";
        } else if (t instanceof GpuDeviceLossException) {
            message = "The Direct3D 12 device was lost while it was being initialised (adapter '" + adapter + "'): "
                    + firstLine(t.getMessage());
        } else if (t instanceof GpuOutOfMemoryException) {
            message = "Out of memory while initialising Direct3D 12 on adapter '" + adapter + "': " + firstLine(t.getMessage());
        } else if (t instanceof D3D12Exception) {
            message = "Cannot initialise Direct3D 12 on adapter '" + adapter + "': " + firstLine(t.getMessage());
        } else if (t instanceof LinkageError) {
            message = "Direct3D 12 is not available: " + firstLine(t.toString());
        } else {
            message = "Cannot initialise Direct3D 12 on adapter '" + adapter + "': " + firstLine(t.toString());
        }

        BackendCreationException exception = new BackendCreationException(message, BackendCreationException.Reason.PLATFORM_ERROR);

        exception.initCause(t);

        return exception;
    }

    private static String firstLine(@Nullable String text) {
        if (text == null) {
            return "(no message)";
        }

        int end = text.indexOf('\n');

        return end < 0 ? text : text.substring(0, end);
    }

    /** @return {@code "Direct3D 12 FL 12_1 driver 31.0.15.4601"}; the driver part is omitted when the adapter does not tell */
    private static String driverInfo(@Nullable DxgiAdapter adapter, DeviceCaps caps) {
        StringBuilder text = new StringBuilder("Direct3D 12 FL ").append(featureLevelName(caps.featureLevel()));

        if (adapter != null) {
            try {
                var version = adapter.checkInterfaceSupport(Iids.IDXGIDevice);

                if (version.isPresent()) {
                    long packed = version.getAsLong();

                    text.append(" driver ").append((packed >>> 48) & 0xFFFFL).append('.').append((packed >>> 32) & 0xFFFFL)
                            .append('.').append((packed >>> 16) & 0xFFFFL).append('.').append(packed & 0xFFFFL);
                }
            } catch (RuntimeException e) {
                D3D12Log.debug("The driver version of '{}' is not available: {}", caps.adapterName(), e.toString());
            }
        }

        return text.toString();
    }

    private static Set<String> extensions(DeviceCaps caps) {
        Set<String> extensions = new LinkedHashSet<>();

        extensions.add("D3D_FEATURE_LEVEL_" + featureLevelName(caps.featureLevel()));
        extensions.add("RESOURCE_BINDING_TIER_" + caps.resourceBindingTier());
        extensions.add("ROOT_SIGNATURE_1_" + (caps.rootSignatureVersion() >= 2 ? "1" : "0"));

        if (caps.uma()) {
            extensions.add("UMA");
        }

        if (caps.tearingSupported()) {
            extensions.add("PRESENT_ALLOW_TEARING");
        }

        if (caps.softwareAdapter()) {
            extensions.add("SOFTWARE_ADAPTER");
        }

        return extensions;
    }

    private static String featureLevelName(int level) {
        return switch (level) {
            case Enums.FeatureLevel._12_1 -> "12_1";
            case Enums.FeatureLevel._12_0 -> "12_0";
            case Enums.FeatureLevel._11_1 -> "11_1";
            case Enums.FeatureLevel._11_0 -> "11_0";
            default -> "0x" + Integer.toHexString(level);
        };
    }

    /**
     * Installs a hook that observes (and may fail) every construction step of {@link #create}: it receives the step name
     * ({@code "device"}, {@code "queue"}, {@code "errors"}, {@code "debug"}, {@code "clock"}, {@code "context"},
     * {@code "descriptors"}, {@code "tracker"}, {@code "resources"}, {@code "rootSignatures"}, {@code "encoder"},
     * {@code "compiler"}, {@code "probe"}) and the object that step built. Used by the failure-injection tests to prove the reverse-order
     * release of a half-built device; {@code null} removes it. Production code never sets it.
     */
    public static void setConstructionHookForTests(@Nullable BiConsumer<String, Object> hook) {
        constructionHook = hook;
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

    /**
     * Shutdown in the order of the class comment; idempotent, never throws, every step in its own try/catch.
     */
    @Override
    public void close() {
        synchronized (this) {
            if (this.closed) {
                return;
            }

            this.closed = true;
        }

        DeviceContext ctx = this.ctx;

        // (1) the compile gate: workers outlive the renderer and use the device and the root signatures
        attempt("close the pipeline compiler", this.compiler::close);

        boolean drained = this.compiler.isDrained();

        // (2) closing: new work refused, but defer still queues while frames may be in flight
        attempt("begin close", ctx::beginClose);

        // (3) the encoder owns the whole teardown of what it built; it marks the GPU idle after flushing
        attempt("shut down the command encoder", () -> ctx.encoder().shutdown());

        if (!ctx.isGpuIdle()) {
            // the encoder did not reach its idle point (it failed before): do what it would have done
            attempt("wait for the GPU", () -> ctx.clock().flushGpu(10_000_000_000L));
            attempt("mark the GPU idle", ctx::markGpuIdle);
        }

        // (4) diagnostics, then everything the frontend may never have closed
        attempt("drain the debug messages", () -> ctx.debug().drainToLog());
        attempt("close registered objects", () -> ctx.registry().closeAll());
        attempt("drain the deferral queue", () -> ctx.deferral().drainAll());
        attempt("release the debug message sink", () -> {
            if (ctx.debug() != null) {
                // one last drain: releasing the objects above may have produced messages; the sink holds a reference
                // to the device through the InfoQueue, so it must be closed before the device is released
                ctx.debug().drainToLog();
                ctx.debug().close();
            }
        });
        attempt("report unreleased resources", () -> {
            ResourceCounters counters = ctx.resources().counters();

            if (counters.buffers() + counters.textures() + counters.views() + counters.samplers() > 0) {
                D3D12Log.warn("Direct3D 12 device closed with {} buffer(s), {} texture(s), {} texture view(s) and {} sampler(s) that their owners never closed",
                        counters.buffers(), counters.textures(), counters.views(), counters.samplers());
            }
        });

        // (5) reverse construction order
        if (drained) {
            attempt("release the root signatures", () -> ctx.rootSignatures().close());
        }

        attempt("release the descriptor services", () -> ctx.descriptors().close());
        attempt("release the frame fences", () -> ctx.clock().close());
        attempt("release the command queue", () -> ctx.queue().close());

        // (6) the device last, and only when no compile worker can still be using it (a leak at exit beats a crash).
        // ID3D12DebugDevice::ReportLiveDeviceObjects is not wrapped by the frozen interop layer.
        if (drained) {
            attempt("release the device", () -> ctx.d3d().close());
        } else {
            D3D12Log.warn("The Direct3D 12 device and root signatures are deliberately not released: a pipeline compile did not finish within the shutdown timeout");
        }

        if (this.ownsFactory && ctx.factory() != null) {
            attempt("release the DXGI factory", () -> ctx.factory().close());
        }
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
        long[] calibration;

        try {
            calibration = this.ctx.queue().getClockCalibration();
        } catch (D3D12Exception e) {
            if (e.isDeviceLoss()) {
                throw this.ctx.errors().translate(e, "ID3D12CommandQueue::GetClockCalibration");
            }

            // the offset only feeds the profiler's GPU timeline; an arbitrary offset is better than failing the device
            D3D12Log.warnOnce("clock-calibration", "ID3D12CommandQueue::GetClockCalibration failed ({}); GPU profiler timestamps are not aligned with the CPU clock",
                    e.getMessage());

            return 0L;
        }

        double period = this.ctx.info().timestampPeriod();
        long gpuNanos = period == 1.0 ? calibration[0] : (long) ((double) calibration[0] * period);

        return Qpc.toNanos(calibration[1]) - gpuNanos;
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
