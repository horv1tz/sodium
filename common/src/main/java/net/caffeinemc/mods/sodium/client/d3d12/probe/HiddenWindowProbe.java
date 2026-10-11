// Owner: WP4 (surface + entry)
package net.caffeinemc.mods.sodium.client.d3d12.probe;

import net.caffeinemc.mods.sodium.client.d3d12.backend.DeviceOptions;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12CommandQueue;
import net.caffeinemc.mods.sodium.client.d3d12.interop.dxgi.DxgiFactory;
import net.caffeinemc.mods.sodium.client.d3d12.surface.WindowHandles;

/**
 * The presentation probe of {@code D3D12Backend.createDevice} (spec 05 section 3.6). The device is created before the
 * window exists and anything that fails later (window creation, {@code createSurface}, swap-chain creation) can no longer
 * fall back to another backend - Minecraft would crash. So the path "SDL window + flip-model swap chain on our queue" is
 * exercised once, on a hidden window, inside {@code createDevice}, and any failure becomes a
 * {@code BackendCreationException} that triggers the fallback.
 *
 * <p>Recipe: create a hidden window (flags {@code 0x80020008 = NOT_FOCUSABLE | UTILITY | HIDDEN}, 320x480, via
 * {@code SDLVideo.SDL_CreateWindow}; the backend's own {@code createWindow} must not be used because it is the public
 * entry the game calls) -> HWND through {@link WindowHandles#hwnd} -> {@code factory.createSwapChainForHwnd(queue, hwnd,
 * Desc.flipDiscard(16, 16, R8G8B8A8_UNORM, 3, productionFlags))} with the <b>production</b> flags
 * ({@code ALLOW_TEARING} iff supported; {@code FRAME_LATENCY_WAITABLE_OBJECT} only if
 * {@code options.waitableSwapChain()}) and 3 buffers -> require {@code IDXGISwapChain3} ({@code supportsBackBufferIndex()}),
 * {@code GetCurrentBackBufferIndex}, one {@code Present(0, 0)} -> <b>idle the queue</b> -> release the back-buffer
 * references -> close the swap chain -> {@code SDL_DestroyWindow}. The window is destroyed on <b>every</b> path
 * ({@code finally}). Afterwards flush the stale window events the probe left in SDL's queue:
 * {@code SDL_PumpEvents(); SDL_FlushEvents(514, 538)} (window events only; do not widen to the display events 337..344).
 *
 * <p><b>Success of the Present is {@code hr >= 0}</b> ({@code S_OK} and {@code DXGI_STATUS_OCCLUDED}, which is what a
 * never-shown window returns on native Windows); testing {@code hr == S_OK} would refuse the backend on every machine.
 * Only a negative HRESULT fails the probe.
 *
 * <p><b>Idle before release.</b> The signature carries no {@code FrameClock}/fence, and D3D12/DXGI require the queue to
 * be done with a swap chain's back buffers before they are released and the window is destroyed (otherwise the debug layer
 * reports {@code OBJECT_DELETED_WHILE_STILL_IN_USE}, and some drivers hang in {@code DestroyWindow}). The probe therefore
 * creates a <em>private</em> fence from {@code queue.getDevice()} (a wrapper with its own reference: close it), calls
 * {@code queue.signal(fence, 1)} after the {@code Present} and {@code fence.waitForValue(1, 5000)}; a timeout or a removed
 * device ({@code getDeviceRemovedReason() != S_OK}) fails the probe, the cleanup still runs. The signature stays
 * {@code (factory, queue, options)}. Implement the steps behind a small package-private seam (create window / create swap
 * chain / present / signal+wait / release buffers / close swap chain / destroy window) so that an L0 test can assert the
 * order "wait before release" ({@code HiddenProbeOrderTest}).
 *
 * <p>Render thread (the game thread). Needs SDL initialised (it is, {@code loadLibrary} ran after {@code SDL_Init}).
 */
public final class HiddenWindowProbe {
    private HiddenWindowProbe() {
    }

    /**
     * @throws RuntimeException describing the first failing step (the caller converts it to a
     *                          {@code BackendCreationException(PLATFORM_ERROR)})
     */
    public static void run(DxgiFactory factory, D3D12CommandQueue queue, DeviceOptions options) {
        throw new UnsupportedOperationException("WP4: hidden window, swap chain with production flags, present once, destroy, flush window events");
    }
}
