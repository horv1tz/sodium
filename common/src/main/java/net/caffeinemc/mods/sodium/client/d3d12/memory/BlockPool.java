// Owner: WP2 (commands + transient memory)
package net.caffeinemc.mods.sodium.client.d3d12.memory;

import net.caffeinemc.mods.sodium.client.d3d12.core.DeviceContext;
import net.caffeinemc.mods.sodium.client.d3d12.resource.BufferMode;

/**
 * Size-class pool for <em>special</em> blocks (requests larger than 512 KiB, e.g. a big {@code writeToBuffer}).
 * Creating a committed resource per big upload costs page allocation and zeroing, so special blocks are rounded up to the
 * next power of two (>= 1 MiB) and recycled instead of destroyed: {@link #release} is called from a deferral entry after
 * the frame of use completed; blocks idle for 120 frames are destroyed by {@link #trim}; at most 256 MiB are retained.
 *
 * <p>Render thread only.
 */
public final class BlockPool {
    private final DeviceContext ctx;

    public BlockPool(DeviceContext ctx) {
        this.ctx = ctx;
    }

    /**
     * @param size requested bytes ({@code > 512 KiB}); the returned block may be larger (power-of-two size class) but
     *             callers use only {@code size} bytes
     */
    public TransientBlock acquire(BufferMode mode, long size) {
        throw new UnsupportedOperationException("WP2: size-class free lists per mode");
    }

    /** Returns a block to its class. Called from a deferral entry (the GPU is done with it). */
    public void release(TransientBlock block) {
        throw new UnsupportedOperationException("WP2");
    }

    /** Destroys blocks that were not acquired for 120 frames. Called at {@code rotate}. */
    public void trim(long currentFrame) {
        throw new UnsupportedOperationException("WP2");
    }

    /** Destroys everything (GPU idle). */
    public void close() {
        throw new UnsupportedOperationException("WP2");
    }

    DeviceContext context() {
        return this.ctx;
    }
}
