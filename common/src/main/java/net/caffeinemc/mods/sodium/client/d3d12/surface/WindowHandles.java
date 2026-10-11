// Owner: WP4 (surface + entry) - fully implemented (spec 05 section 3.5)
package net.caffeinemc.mods.sodium.client.d3d12.surface;

import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.sdl.SDLVideo;

/**
 * SDL window handle to Win32 {@code HWND}. LWJGL's SDL classes are only touched inside the method body, so loading this
 * class is safe on every platform.
 */
public final class WindowHandles {
    private WindowHandles() {
    }

    /**
     * @param sdlWindow the {@code SDL_Window*} that {@code Window.handle()} / {@code createSurface(windowHandle, ..)} carry
     * @return the HWND (stable for the life of the process: Minecraft never recreates the window)
     * @throws IllegalStateException if SDL has no Win32 window for the handle (e.g. the video driver is not {@code windows})
     */
    public static long hwnd(long sdlWindow) {
        if (sdlWindow == 0L) {
            throw new IllegalStateException("The SDL window handle is null");
        }

        int properties = SDLVideo.SDL_GetWindowProperties(sdlWindow);

        if (properties == 0) {
            throw new IllegalStateException("SDL_GetWindowProperties failed: " + SDLError.SDL_GetError());
        }

        long hwnd = SDLProperties.SDL_GetPointerProperty(properties, SDLVideo.SDL_PROP_WINDOW_WIN32_HWND_POINTER, 0L);

        if (hwnd == 0L) {
            throw new IllegalStateException("The SDL window has no Win32 HWND (video driver " + SDLVideo.SDL_GetCurrentVideoDriver() + ")");
        }

        return hwnd;
    }
}
