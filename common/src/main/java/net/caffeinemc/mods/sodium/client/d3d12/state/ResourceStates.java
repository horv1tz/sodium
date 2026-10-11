// Owner: WP1 (device core) - fully implemented shared helper
package net.caffeinemc.mods.sodium.client.d3d12.state;

/**
 * The only resource states the backend ever uses (header-checked against {@code D3D12_RESOURCE_STATES}, spec 03 6.2).
 *
 * <ul>
 *     <li>Buffers (DEFAULT heap) rest in {@link #GR}: a superset of vertex/constant, index, SRV, indirect-argument and
 *     copy-source use, so those never need a transition. Their only transitions are {@code GR <-> CD}.</li>
 *     <li>Textures are created in {@link #COMMON}, are read through {@link #SRV_ALL} or {@link #CS}, and written
 *     through {@link #CD}, {@link #RT} or {@link #DW}.</li>
 *     <li>UPLOAD buffers (GENERIC_READ) and READBACK buffers (COPY_DEST) never change state and are not tracked.</li>
 *     <li>{@code DEPTH_READ}, UAV, resolve and video states are never used.</li>
 * </ul>
 */
public final class ResourceStates {
    public static final int COMMON = 0;
    public static final int PRESENT = 0;
    public static final int RT = 0x4;
    public static final int DW = 0x10;
    public static final int CD = 0x400;
    public static final int CS = 0x800;
    /** {@code GENERIC_READ}: VERTEX_AND_CONSTANT_BUFFER | INDEX_BUFFER | NON_PIXEL | PIXEL | INDIRECT_ARGUMENT | COPY_SOURCE. */
    public static final int GR = 0xAC3;
    /** {@code NON_PIXEL_SHADER_RESOURCE | PIXEL_SHADER_RESOURCE}: a texture sampled from any stage. */
    public static final int SRV_ALL = 0xC0;

    /** Subresource selector meaning "every subresource" for {@code ResourceStateTracker.require}. */
    public static final int ALL = -1;

    private static final int WRITE_BITS = RT | DW | CD | 0x8 /* UAV */ | 0x100 /* stream out */ | 0x1000 /* resolve dest */;

    private ResourceStates() {
    }

    /** @return true if the state contains only read bits and is not COMMON/PRESENT (0) */
    public static boolean isReadOnly(int state) {
        return state != 0 && (state & WRITE_BITS) == 0;
    }

    /**
     * Whether a resource currently in {@code before} must be transitioned for a use that needs {@code after}.
     * Rule: no transition if the states are equal, or if both are read-only and {@code before} already contains every
     * bit of {@code after} (e.g. {@code GR} covers {@code CS}). {@code COMMON} (0) is never "covered".
     */
    public static boolean needsTransition(int before, int after) {
        if (before == after) {
            return false;
        }

        return !(after != 0 && isReadOnly(before) && isReadOnly(after) && (before & after) == after);
    }
}
