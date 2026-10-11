// Owner: WP4 (surface + entry) - implemented (new)
package net.caffeinemc.mods.sodium.client.d3d12.surface;

import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Enums;

/**
 * The single definition of the production swap-chain parameters, shared by {@code SwapChainHolder} (the real swap chain)
 * and {@code HiddenWindowProbe} (which must exercise exactly what {@code configure} will later do, spec 05 section 3.6).
 * Pure constants and arithmetic: loads safely on every platform.
 */
public final class SwapChainFlags {
    /** Back buffers of the flip-discard swap chain (decision D10). */
    public static final int BUFFER_COUNT = 3;

    /** The back-buffer format; matches the main target, no gamma conversion. */
    public static final int FORMAT = Enums.Format.R8G8B8A8_UNORM;

    /** {@code DXGI_MWA_NO_WINDOW_CHANGES | DXGI_MWA_NO_ALT_ENTER}: DXGI must not hook SDL's window or react to Alt+Enter. */
    public static final int WINDOW_ASSOCIATION = 1 | 2;

    /** Cap of the optional frame-latency wait (milliseconds); the later {@code Present} is the real throttle. */
    public static final long FRAME_LATENCY_WAIT_MILLIS = 250L;

    private SwapChainFlags() {
    }

    /**
     * @param tearingSupported {@code DeviceCaps.tearingSupported()} (the factory's {@code isTearingSupported()})
     * @param waitable         {@code DeviceOptions.waitableSwapChain()}
     * @return the {@code DXGI_SWAP_CHAIN_FLAG_*} bits of the production swap chain, also passed to every {@code ResizeBuffers}
     */
    public static int production(boolean tearingSupported, boolean waitable) {
        int flags = 0;

        if (tearingSupported) {
            flags |= Enums.SwapChainFlag.ALLOW_TEARING;
        }

        if (waitable) {
            flags |= Enums.SwapChainFlag.FRAME_LATENCY_WAITABLE_OBJECT;
        }

        return flags;
    }
}
