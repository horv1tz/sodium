// Owner: WP4 (surface + entry) - implemented
package net.caffeinemc.mods.sodium.client.d3d12.probe;

import net.caffeinemc.mods.sodium.client.d3d12.backend.DeviceOptions;
import net.caffeinemc.mods.sodium.client.d3d12.core.D3D12Log;
import net.caffeinemc.mods.sodium.client.d3d12.interop.D3D12Exception;
import net.caffeinemc.mods.sodium.client.d3d12.interop.Hr;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Iids;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12CommandQueue;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12Device;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12Fence;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12Resource;
import net.caffeinemc.mods.sodium.client.d3d12.interop.dxgi.DxgiFactory;
import net.caffeinemc.mods.sodium.client.d3d12.interop.dxgi.DxgiSwapChain;
import net.caffeinemc.mods.sodium.client.d3d12.surface.SwapChainFlags;
import net.caffeinemc.mods.sodium.client.d3d12.surface.WindowHandles;
import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLEvents;
import org.lwjgl.sdl.SDLVideo;

import java.lang.foreign.MemorySegment;

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
 * {@code options.waitableSwapChain()}; {@link SwapChainFlags#production}) and 3 buffers, then
 * {@code makeWindowAssociation(NO_WINDOW_CHANGES | NO_ALT_ENTER)} like the real surface -> require {@code IDXGISwapChain3}
 * ({@code supportsBackBufferIndex()}), {@code GetCurrentBackBufferIndex}, {@code GetBuffer} of that buffer, one
 * {@code Present(0, 0)} -> <b>idle the queue</b> -> release the back-buffer reference -> close the swap chain ->
 * {@code SDL_DestroyWindow}. The window is destroyed on <b>every</b> path ({@code finally}). Afterwards flush the stale
 * window events the probe left in SDL's queue: {@code SDL_PumpEvents(); SDL_FlushEvents(WINDOW_FIRST, WINDOW_LAST)} (514 ..
 * 538, window events only; do not widen to the display events 337..344).
 *
 * <p><b>Success of the Present is {@code hr >= 0}</b> ({@code S_OK} and {@code DXGI_STATUS_OCCLUDED}, which is what a
 * never-shown window returns on native Windows); testing {@code hr == S_OK} would refuse the backend on every machine.
 * Only a negative HRESULT fails the probe.
 *
 * <p><b>Idle before release.</b> The signature carries no {@code FrameClock}/fence, and D3D12/DXGI require the queue to
 * be done with a swap chain's back buffers before they are released and the window is destroyed (otherwise the debug layer
 * reports {@code OBJECT_DELETED_WHILE_STILL_IN_USE}, and some drivers hang in {@code DestroyWindow}). The probe therefore
 * creates a <em>private</em> fence from {@code queue.getDevice()} (a wrapper with its own reference, closed afterwards),
 * calls {@code queue.signal(fence, 1)} after the {@code Present} and {@code fence.waitForValue(1, 5000)}; a timeout or a
 * removed device ({@code getDeviceRemovedReason() != S_OK}) fails the probe, the cleanup still runs. The signature stays
 * {@code (factory, queue, options)}.
 *
 * <p>The steps live behind the package-private {@link Steps} seam and the order is fixed in {@link #execute(Steps)}, so
 * that {@code HiddenProbeOrderTest} (X28) can record "present, idle, release buffers, close swap chain, destroy window,
 * flush events" and the cleanup after a failure in each step.
 *
 * <p>Render thread (the game thread). Needs SDL initialised (it is, {@code loadLibrary} ran after {@code SDL_Init}).
 */
public final class HiddenWindowProbe {
    /** {@code SDL_WINDOW_NOT_FOCUSABLE | SDL_WINDOW_UTILITY | SDL_WINDOW_HIDDEN}: the flags GL's hidden windows use. */
    static final long WINDOW_FLAGS = 0x80020008L;
    static final int WINDOW_WIDTH = 320;
    static final int WINDOW_HEIGHT = 480;
    static final String WINDOW_TITLE = "Minecraft - Direct3D 12 Hidden Probe Window";
    /** Size of the probe swap chain's back buffers (the real surface resizes on the first configure anyway). */
    static final int PROBE_SIZE = 16;
    static final long IDLE_TIMEOUT_MILLIS = 5000L;

    private HiddenWindowProbe() {
    }

    /**
     * @throws RuntimeException describing the first failing step (the caller converts it to a
     *                          {@code BackendCreationException(PLATFORM_ERROR)}); the window is destroyed and the SDL
     *                          event queue flushed on every path
     */
    public static void run(DxgiFactory factory, D3D12CommandQueue queue, DeviceOptions options) {
        execute(new NativeSteps(factory, queue, options));
    }

    /**
     * The probe's steps, each one native operation. Implementations keep the state (window, swap chain, back buffer)
     * between calls; every method throws on failure. The order of the calls is fixed by {@link #execute(Steps)}.
     */
    interface Steps {
        /** Creates the hidden window. */
        void createWindow();

        /** Creates the swap chain on the window with the production flags and associates the window. */
        void createSwapChain();

        /** Requires {@code IDXGISwapChain3}, reads the current back-buffer index and takes a reference to that buffer. */
        void acquireBackBuffer();

        /** @return the raw HRESULT of {@code Present(0, 0)} */
        int present();

        /** Signals a private fence on the queue and waits for it; throws on timeout or a removed device. */
        void idle();

        /** Releases the back-buffer reference. */
        void releaseBackBuffer();

        void closeSwapChain();

        void destroyWindow();

        /** {@code SDL_PumpEvents(); SDL_FlushEvents(window range)}. */
        void flushWindowEvents();
    }

    /**
     * The order contract: create window, create swap chain, acquire back buffer, present ({@code hr >= 0}), idle, release
     * buffer, close swap chain, destroy window, flush events. After the first failure nothing else is attempted except the
     * cleanup: idle (only if a present may have been queued and the idle did not run yet), release, close, destroy, flush -
     * each only if its resource exists, each in its own try/catch.
     */
    static void execute(Steps steps) {
        boolean windowCreated = false;
        boolean chainCreated = false;
        boolean backBufferTaken = false;
        boolean presentIssued = false;
        boolean idleAttempted = false;
        Throwable failure = null;

        try {
            steps.createWindow();
            windowCreated = true;

            steps.createSwapChain();
            chainCreated = true;

            steps.acquireBackBuffer();
            backBufferTaken = true;

            presentIssued = true;
            int hr = steps.present();

            if (hr < 0) {
                throw new D3D12Exception("Present on the hidden probe window failed", hr);
            }

            idleAttempted = true;
            steps.idle();

            // a step that throws has released what it could itself: the flags say "may still exist", so they drop first
            backBufferTaken = false;
            steps.releaseBackBuffer();

            chainCreated = false;
            steps.closeSwapChain();
        } catch (Throwable t) {
            failure = t;
        } finally {
            failure = cleanup(steps, failure, windowCreated, chainCreated, backBufferTaken, presentIssued, idleAttempted);
        }

        if (failure != null) {
            if (failure instanceof RuntimeException e) {
                throw e;
            }

            if (failure instanceof Error e) {
                throw e;
            }

            throw new IllegalStateException(failure);
        }
    }

    private static @Nullable Throwable cleanup(Steps steps, @Nullable Throwable failure, boolean windowCreated, boolean chainCreated,
                                               boolean backBufferTaken, boolean presentIssued, boolean idleAttempted) {
        if (chainCreated || backBufferTaken) {
            if (presentIssued && !idleAttempted) {
                failure = attempt(steps::idle, failure, "idle the queue");
            }

            if (backBufferTaken) {
                failure = attempt(steps::releaseBackBuffer, failure, "release the back buffer");
            }

            if (chainCreated) {
                failure = attempt(steps::closeSwapChain, failure, "close the swap chain");
            }
        }

        if (windowCreated) {
            failure = attempt(steps::destroyWindow, failure, "destroy the window");
            failure = attempt(steps::flushWindowEvents, failure, "flush the window events");
        }

        return failure;
    }

    /** Runs a cleanup step; its failure is attached to the first failure (or becomes it if the probe had succeeded). */
    private static @Nullable Throwable attempt(Runnable step, @Nullable Throwable failure, String what) {
        try {
            step.run();
        } catch (Throwable t) {
            if (failure == null) {
                return new IllegalStateException("Hidden presentation probe: could not " + what + ": " + t, t);
            }

            failure.addSuppressed(t);
            D3D12Log.warn("Hidden presentation probe: could not {} after an earlier failure: {}", what, t.toString());
        }

        return failure;
    }

    // ------------------------------------------------------------------------------------------------------------

    /** The real thing: SDL3 window, DXGI swap chain on the device's queue. */
    private static final class NativeSteps implements Steps {
        private final DxgiFactory factory;
        private final D3D12CommandQueue queue;
        private final DeviceOptions options;

        private long window;
        private @Nullable DxgiSwapChain swapChain;
        private @Nullable D3D12Resource backBuffer;
        private int flags;

        NativeSteps(DxgiFactory factory, D3D12CommandQueue queue, DeviceOptions options) {
            this.factory = factory;
            this.queue = queue;
            this.options = options;
        }

        @Override
        public void createWindow() {
            long handle = SDLVideo.SDL_CreateWindow(WINDOW_TITLE, WINDOW_WIDTH, WINDOW_HEIGHT, WINDOW_FLAGS);

            if (handle == 0L) {
                throw new IllegalStateException("SDL_CreateWindow failed for the hidden probe window: " + SDLError.SDL_GetError());
            }

            this.window = handle;
        }

        @Override
        public void createSwapChain() {
            long hwnd = WindowHandles.hwnd(this.window);
            MemorySegment hwndPointer = MemorySegment.ofAddress(hwnd);

            this.flags = SwapChainFlags.production(this.factory.isTearingSupported(), this.options.waitableSwapChain());

            DxgiSwapChain chain = this.factory.createSwapChainForHwnd(this.queue, hwndPointer,
                    DxgiSwapChain.Desc.flipDiscard(PROBE_SIZE, PROBE_SIZE, SwapChainFlags.FORMAT, SwapChainFlags.BUFFER_COUNT, this.flags));

            try {
                this.factory.makeWindowAssociation(hwndPointer, SwapChainFlags.WINDOW_ASSOCIATION);
            } catch (Throwable t) {
                // this step failed as a whole: the orchestrator does not know about the chain yet, so release it here
                chain.close();

                throw t;
            }

            this.swapChain = chain;
        }

        @Override
        public void acquireBackBuffer() {
            DxgiSwapChain chain = this.swapChain;

            if (chain == null) {
                throw new IllegalStateException("no swap chain");
            }

            if (!chain.supportsBackBufferIndex()) {
                throw new IllegalStateException("IDXGISwapChain3 is required by the Direct3D 12 backend but the runtime offers only version "
                        + chain.version());
            }

            int index = chain.getCurrentBackBufferIndex();

            if (index < 0 || index >= SwapChainFlags.BUFFER_COUNT) {
                throw new IllegalStateException("GetCurrentBackBufferIndex returned " + index);
            }

            this.backBuffer = new D3D12Resource(chain.getBuffer(index, Iids.ID3D12Resource));
        }

        @Override
        public int present() {
            DxgiSwapChain chain = this.swapChain;

            if (chain == null) {
                throw new IllegalStateException("no swap chain");
            }

            int hr = chain.present(0, 0);
            D3D12Log.debug("Hidden probe window: Present(0, 0) returned HRESULT 0x{}", Integer.toHexString(hr));

            return hr;
        }

        @Override
        public void idle() {
            D3D12Device device = this.queue.getDevice();

            try (D3D12Fence fence = device.createFence(0L)) {
                this.queue.signal(fence, 1L);
                boolean reached = fence.waitForValue(1L, IDLE_TIMEOUT_MILLIS);
                int removed = device.getDeviceRemovedReason();

                if (removed != Hr.S_OK) {
                    throw new D3D12Exception("The device was removed while the hidden probe window was presented", removed);
                }

                if (!reached) {
                    throw new IllegalStateException("The GPU did not finish the hidden probe window's present within "
                            + IDLE_TIMEOUT_MILLIS + " ms");
                }
            } finally {
                device.close();
            }
        }

        @Override
        public void releaseBackBuffer() {
            D3D12Resource buffer = this.backBuffer;
            this.backBuffer = null;

            if (buffer != null) {
                buffer.close();
            }
        }

        @Override
        public void closeSwapChain() {
            DxgiSwapChain chain = this.swapChain;
            this.swapChain = null;

            if (chain != null) {
                chain.close();
            }
        }

        @Override
        public void destroyWindow() {
            long handle = this.window;
            this.window = 0L;

            if (handle != 0L) {
                SDLVideo.SDL_DestroyWindow(handle);
            }
        }

        @Override
        public void flushWindowEvents() {
            SDLEvents.SDL_PumpEvents();
            SDLEvents.SDL_FlushEvents(SDLEvents.SDL_EVENT_WINDOW_FIRST, SDLEvents.SDL_EVENT_WINDOW_LAST);
        }
    }
}
