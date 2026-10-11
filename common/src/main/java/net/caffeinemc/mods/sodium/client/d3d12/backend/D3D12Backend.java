// Owner: WP4 (surface + entry)
package net.caffeinemc.mods.sodium.client.d3d12.backend;

import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLVideo;

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
    private final UnaryOperator<DeviceOptions> optionsHook;

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
        throw new UnsupportedOperationException("WP4: platform, SDL video driver and FFM checks; cached failure");
    }

    @Override
    public void unloadLibrary() {
        throw new UnsupportedOperationException("WP4: release the factory and the debug interface");
    }

    @Override
    public long createWindow(@Nullable String title, int width, int height, long flags) {
        return SDLVideo.SDL_CreateWindow(title, width, height, flags);
    }

    @Override
    public GpuDevice createDevice(GpuDebugOptions debugOptions) throws BackendCreationException {
        throw new UnsupportedOperationException("WP4: see class comment");
    }
}
