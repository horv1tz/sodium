// Owner: WP4 (surface + entry) - implemented
package net.caffeinemc.mods.sodium.client.d3d12.backend;

import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.device.GpuDeviceLossException;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import net.caffeinemc.mods.sodium.client.d3d12.core.D3D12Log;
import net.caffeinemc.mods.sodium.client.d3d12.core.DeviceContext;
import net.caffeinemc.mods.sodium.client.d3d12.interop.D3D12Exception;
import net.caffeinemc.mods.sodium.client.d3d12.interop.Win32Libraries;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Enums;
import net.caffeinemc.mods.sodium.client.d3d12.interop.compiler.D3DCompiler;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12Debug;
import net.caffeinemc.mods.sodium.client.d3d12.interop.dxgi.Dxgi;
import net.caffeinemc.mods.sodium.client.d3d12.interop.dxgi.DxgiFactory;
import net.caffeinemc.mods.sodium.client.d3d12.pipeline.BuiltinPipelines;
import net.caffeinemc.mods.sodium.client.d3d12.probe.HiddenWindowProbe;
import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLVideo;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * The {@code GpuBackend} the Sodium integration (WP5) prepends to Minecraft's backend list. Safe to <b>load and
 * instantiate on every platform</b>: the constructor and all static initialisers touch no native library; everything
 * native is behind {@link #loadLibrary()} / {@link #createDevice}.
 *
 * <h2>Contract (spec 05 sections 3, 4)</h2>
 * <ul>
 *     <li>{@link #getName()} = {@code "Direct3D 12"}.</li>
 *     <li>{@link #loadLibrary()} (game thread, after {@code SDL_Init}): idempotent, a failure is cached and rethrown on
 *     later calls; checks in order, each failing with {@code BackendCreationException(msg, PLATFORM_ERROR)}:
 *     (1) {@code Win32Libraries.isWindows()}; (2) {@code SDL_GetCurrentVideoDriver()} equals {@code "windows"};
 *     (3) FFM usable - lazily load {@code d3d12.dll}, {@code dxgi.dll}, {@code d3dcompiler_47.dll} through
 *     {@code Win32Libraries} (catch {@code IllegalCallerException} from {@code --illegal-native-access=deny},
 *     {@code UnsatisfiedLinkError}, {@code D3D12Exception}, every {@code Throwable} except {@code VirtualMachineError});
 *     cleans up after itself when it fails (Minecraft calls {@code unloadLibrary} only after a success).</li>
 *     <li>{@link #unloadLibrary()}: idempotent; releases the backend-global COM objects (DXGI factory, debug interface,
 *     {@code ReportLiveObjects} in debug); also called right after a failed {@code createDevice}.</li>
 *     <li>{@link #createWindow}: {@code SDL_CreateWindow(title, w, h, flags)} with the flags <b>unchanged</b> (no API
 *     flag; a window that never had a GL/Vulkan pixel format is what DXGI wants); {@code 0} on failure; {@code title} is
 *     nullable.</li>
 *     <li>{@link #createDevice}: debug layer if {@code useValidationLayers} (a failure to enable is a WARN, never fatal)
 *     -> {@code Dxgi.createFactory(debug)} (owned by the backend, borrowed by the device) ->
 *     {@code D3D12GpuDevice.create(resolveOptions(options), factory, false)} ->
 *     <b>{@code BuiltinPipelines.fullscreenBlit(ctx, R8G8B8A8_UNORM)}</b> -> {@code HiddenWindowProbe.run(..)} ->
 *     {@code new FrontendGpuDevice(device)}; the whole sequence in one try/catch that
 *     closes everything created and rethrows <b>every</b> failure as {@code BackendCreationException} ({@code OTHER} with
 *     {@code missingCapabilities} for feature problems, {@code PLATFORM_ERROR} otherwise; short human-readable message
 *     with the adapter name and the HRESULT in hex). No window is needed or created for the device itself.
 *     <p>The rule is "anything that can fail after {@code createDevice} cannot fall back, so prove it inside
 *     {@code createDevice}". The swap-chain half is proven by the probe; the <em>present pipeline half</em> (global root
 *     signature + HLSL -> {@code D3DCompile} -> graphics PSO) is proven by the {@code fullscreenBlit} call, which is
 *     deliberately <b>outside</b> the swallow of {@code StartupProbes} (those only measure) and fails with
 *     {@code BackendCreationException(PLATFORM_ERROR, "Cannot build the Direct3D 12 present pipeline: <compiler text /
 *     HRESULT hex>")}. The result is memoised per device, so {@code PresentBlit} reuses it. Without this step a rejected
 *     blit PSO would surface in the first {@code configure()} as a {@code SurfaceException} that Minecraft retries every
 *     frame: a black window with no fallback.</p></li>
 *     <li>{@link #resolveOptions}: the options of the device are {@code optionsHook.apply(DeviceOptions.from(options))};
 *     the hook (constructor argument, identity by default) is how the Sodium integration injects what only it knows, e.g.
 *     {@code o -> o.withShaderCacheDir(gameDir.resolve(".sodium/d3d12/dxbc"))}, the adapter filter from the options
 *     screen or {@code strict}.</li>
 * </ul>
 * Never use {@code Reason.OPENGL_MISSING} or the Vulkan reasons.
 */
public final class D3D12Backend implements GpuBackend {
    /** Longest message handed to Minecraft's error box; the complete text goes to the log and to the exception cause. */
    static final int MAX_MESSAGE_LENGTH = 400;

    /** The stages of {@link #createDevice}; the failing one decides the wording of the message. */
    enum Stage {
        DEVICE("create the device"),
        PRESENT_PIPELINE("build the present pipeline"),
        PRESENT("present"),
        FRONTEND("wrap the device");

        final String text;

        Stage(String text) {
            this.text = text;
        }
    }

    private final UnaryOperator<DeviceOptions> optionsHook;

    /** Guards the library state below; never held while a device is being created. */
    private final Object lock = new Object();
    private boolean libraryLoaded;
    private @Nullable BackendCreationException loadFailure;
    /** Factories and debug interfaces handed out by {@link #createDevice}; owned until {@link #unloadLibrary()}. */
    private final List<AutoCloseable> globals = new ArrayList<>();

    /** Does nothing native; the device options are exactly {@code DeviceOptions.from(debugOptions)}. */
    public D3D12Backend() {
        this(UnaryOperator.identity());
    }

    /**
     * Does nothing native.
     *
     * @param optionsHook adjusts the options derived from {@code GpuDebugOptions} and the system properties before the
     *                    device is created (must not return null); this is the integration seam for the shader-cache
     *                    directory and other lead-decided settings
     */
    public D3D12Backend(UnaryOperator<DeviceOptions> optionsHook) {
        this.optionsHook = Objects.requireNonNull(optionsHook, "optionsHook");
    }

    /** @return {@code optionsHook.apply(DeviceOptions.from(debugOptions))}; pure, no native access */
    public DeviceOptions resolveOptions(GpuDebugOptions debugOptions) {
        return Objects.requireNonNull(this.optionsHook.apply(DeviceOptions.from(debugOptions)), "optionsHook returned null");
    }

    @Override
    public String getName() {
        return "Direct3D 12";
    }

    @Override
    public void loadLibrary() throws BackendCreationException {
        synchronized (this.lock) {
            if (this.libraryLoaded) {
                return;
            }

            BackendCreationException cached = this.loadFailure;

            if (cached != null) {
                throw cached;
            }

            try {
                this.checkPlatform();
                this.libraryLoaded = true;
            } catch (BackendCreationException e) {
                this.loadFailure = e;

                throw e;
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError error) {
                    throw error;
                }

                BackendCreationException failure = platformError("Direct3D 12 cannot be used: " + describe(t), t);
                this.loadFailure = failure;

                throw failure;
            }
        }
    }

    /**
     * The checks of {@link #loadLibrary()}, in order: Windows, SDL video driver, usable FFM and the three DLLs. Nothing is
     * allocated that would need cleanup (library lookups live in the global arena and cannot be unloaded).
     */
    private void checkPlatform() throws BackendCreationException {
        if (!D3D12.isSupportedPlatform()) {
            throw platformError("Direct3D 12 requires Windows (this is " + System.getProperty("os.name", "an unknown system") + ")", null);
        }

        String driver = SDLVideo.SDL_GetCurrentVideoDriver();

        if (!"windows".equals(driver)) {
            throw platformError("Direct3D 12 needs SDL's Windows video driver, but the video driver is "
                    + (driver == null ? "not initialised" : "\"" + driver + "\""), null);
        }

        try {
            requireSymbol(Win32Libraries.d3d12(), "d3d12.dll", "D3D12CreateDevice");
            requireSymbol(Win32Libraries.dxgi(), "dxgi.dll", "CreateDXGIFactory2");
            requireSymbol(Win32Libraries.d3dCompiler(), "d3dcompiler_47.dll", "D3DCompile");

            // creating a downcall handle is the restricted operation that --illegal-native-access=deny refuses
            if (!Dxgi.isAvailable()) {
                throw platformError("dxgi.dll cannot be called from Java (is native access enabled? "
                        + "add --enable-native-access=ALL-UNNAMED)", null);
            }

            if (!D3DCompiler.isAvailable()) {
                throw platformError("d3dcompiler_47.dll cannot be called from Java (is native access enabled? "
                        + "add --enable-native-access=ALL-UNNAMED)", null);
            }
        } catch (IllegalCallerException e) {
            throw platformError("Native access is not enabled for the Direct3D 12 backend (add --enable-native-access=ALL-UNNAMED)", e);
        } catch (UnsatisfiedLinkError | D3D12Exception e) {
            throw platformError("Direct3D 12 system libraries are not usable: " + describe(e), e);
        }
    }

    private static void requireSymbol(java.lang.foreign.SymbolLookup library, String dll, String symbol) throws BackendCreationException {
        if (library.find(symbol).isEmpty()) {
            throw platformError(dll + " does not export " + symbol, null);
        }
    }

    @Override
    public void unloadLibrary() {
        List<AutoCloseable> release;

        synchronized (this.lock) {
            release = new ArrayList<>(this.globals);
            this.globals.clear();
            this.libraryLoaded = false;
        }

        for (int i = release.size() - 1; i >= 0; i--) {
            try {
                release.get(i).close();
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError error) {
                    throw error;
                }

                D3D12Log.warn("Could not release a Direct3D 12 backend object: {}", t.toString());
            }
        }
    }

    @Override
    public long createWindow(@Nullable String title, int width, int height, long flags) {
        return SDLVideo.SDL_CreateWindow(title, width, height, flags);
    }

    @Override
    public GpuDevice createDevice(GpuDebugOptions debugOptions) throws BackendCreationException {
        this.loadLibrary();

        D3D12Debug debugInterface = null;
        DxgiFactory factory = null;
        D3D12GpuDevice device = null;
        GpuDevice frontend = null;
        String adapter = null;
        Stage stage = Stage.DEVICE;

        try {
            DeviceOptions options = this.resolveOptions(debugOptions);
            boolean debugLayer = false;

            if (options.useValidationLayers()) {
                debugInterface = this.enableDebugLayer(options);
                debugLayer = debugInterface != null;
            }

            factory = Dxgi.createFactory(debugLayer);
            device = D3D12GpuDevice.create(options, factory, false);

            DeviceContext ctx = device.context();
            adapter = ctx.caps().adapterName();

            stage = Stage.PRESENT_PIPELINE;
            BuiltinPipelines.fullscreenBlit(ctx, Enums.Format.R8G8B8A8_UNORM);

            stage = Stage.PRESENT;
            HiddenWindowProbe.run(factory, ctx.queue(), options);

            stage = Stage.FRONTEND;
            frontend = new FrontendGpuDevice(device);

            synchronized (this.lock) {
                // released by unloadLibrary(): the factory after the device, which the frontend's close() has released by then
                if (debugInterface != null) {
                    this.globals.add(debugInterface);
                }

                this.globals.add(factory);
            }

            return frontend;
        } catch (Throwable t) {
            if (frontend != null) {
                closeQuietly(frontend::close);
            } else if (device != null) {
                closeQuietly(device::close);
            }

            closeQuietly(factory);
            closeQuietly(debugInterface);

            if (t instanceof BackendCreationException e) {
                throw e;
            }

            if (t instanceof VirtualMachineError error) {
                throw error;
            }

            D3D12Log.error("Could not create the Direct3D 12 device (failed to {})", stage.text, t);

            throw failure(stage, adapter, t);
        }
    }

    /**
     * Enables the debug layer (and GPU-based validation when requested) before the device exists. A failure is a WARN and
     * leaves the device creation to proceed without validation.
     *
     * @return the debug interface (kept open until {@link #unloadLibrary()}), or null if the layer is not available
     */
    private @Nullable D3D12Debug enableDebugLayer(DeviceOptions options) {
        D3D12Debug debug = null;

        try {
            debug = D3D12.tryGetDebugInterface();

            if (debug == null) {
                D3D12Log.warn("The Direct3D 12 debug layer was requested but is not installed (Windows optional feature \"Graphics Tools\")");

                return null;
            }

            debug.enableDebugLayer();

            if (options.gpuBasedValidation() && !debug.setEnableGPUBasedValidation(true)) {
                D3D12Log.warn("GPU-based validation was requested but the runtime does not offer ID3D12Debug1");
            }

            return debug;
        } catch (RuntimeException e) {
            D3D12Log.warn("Could not enable the Direct3D 12 debug layer: {}", e.toString());
            closeQuietly(debug);

            return null;
        }
    }

    /**
     * The conversion of an unexpected failure during {@link #createDevice}: a short message with the adapter (when it is
     * known) and the HRESULT in hex (which {@code D3D12Exception} already carries), the full failure as the cause.
     */
    static BackendCreationException failure(Stage stage, @Nullable String adapter, Throwable t) {
        String where = adapter == null ? "" : " on " + adapter;
        String text;

        if (stage == Stage.PRESENT_PIPELINE) {
            text = "Cannot build the Direct3D 12 present pipeline" + where + ": " + describe(t);
        } else if (stage == Stage.PRESENT) {
            text = "Cannot present with Direct3D 12" + where + ": " + describe(t);
        } else if (t instanceof GpuDeviceLossException) {
            text = "The Direct3D 12 device was lost while starting" + where + ": " + describe(t);
        } else {
            text = "Cannot " + stage.text + " with Direct3D 12" + where + ": " + describe(t);
        }

        return platformError(text, t);
    }

    /** @return {@code message} limited to {@value #MAX_MESSAGE_LENGTH} characters, first line only for multi-line texts */
    static String shorten(String message) {
        String text = message.strip();
        int newline = text.indexOf('\n');

        if (newline >= 0) {
            text = text.substring(0, newline).strip();
        }

        return text.length() <= MAX_MESSAGE_LENGTH ? text : text.substring(0, MAX_MESSAGE_LENGTH - 3) + "...";
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();

        return shorten(message == null || message.isBlank() ? t.getClass().getSimpleName() : message);
    }

    private static BackendCreationException platformError(String message, @Nullable Throwable cause) {
        BackendCreationException exception = new BackendCreationException(shorten(message), BackendCreationException.Reason.PLATFORM_ERROR);

        if (cause != null) {
            exception.initCause(cause);
        }

        return exception;
    }

    private static void closeQuietly(@Nullable AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }

        try {
            closeable.close();
        } catch (Throwable t) {
            D3D12Log.warn("Could not release a Direct3D 12 object after a failed start: {}", t.toString());
        }
    }
}
