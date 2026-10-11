// Owner: WP2 (commands + transient memory) - implemented
package net.caffeinemc.mods.sodium.client.d3d12.memory;

import net.caffeinemc.mods.sodium.client.d3d12.core.Alignment;
import net.caffeinemc.mods.sodium.client.d3d12.core.DeviceContext;
import net.caffeinemc.mods.sodium.client.d3d12.resource.BufferMode;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

/**
 * Size-class pool for <em>special</em> blocks (requests larger than 512 KiB, e.g. a big {@code writeToBuffer}).
 * Creating a committed resource per big upload costs page allocation and zeroing, so special blocks are rounded up to the
 * next power of two (>= 1 MiB) and recycled instead of destroyed: {@link #release} is called from a deferral entry after
 * the frame of use completed; blocks idle for 120 frames are destroyed by {@link #trim}; at most 256 MiB are retained.
 *
 * <p>Policy details:
 * <ul>
 *     <li>One free list per (mode, size class). {@link #acquire} takes the most recently released block of the class
 *     (best chance that it is still resident), else creates one of the class size.</li>
 *     <li>{@link #release} keeps the block only if the retained total stays within {@link #MAX_RETAINED_BYTES}; otherwise
 *     it is destroyed at once (the caller guarantees the GPU is done with it).</li>
 *     <li>A class is at most twice the request, so a 300 MiB upload commits a 512 MiB block; that is the price of reuse
 *     without fragmentation (documented trade-off).</li>
 * </ul>
 *
 * <p>Render thread only.
 */
public final class BlockPool {
    /** Smallest size class: special blocks are larger than the 512 KiB normal block, the first class is 1 MiB. */
    public static final long MIN_CLASS_BYTES = 1L << 20;

    /** Free blocks are kept at most this long (in frames) without being acquired. */
    public static final long MAX_IDLE_FRAMES = 120L;

    /** Upper bound of the bytes held in free lists. */
    public static final long MAX_RETAINED_BYTES = 256L << 20;

    /** Creates the native block; replaced in tests. */
    @FunctionalInterface
    public interface BlockFactory {
        TransientBlock create(BufferMode mode, long size);
    }

    private record Idle(TransientBlock block, long releasedInFrame) {
    }

    private final DeviceContext ctx;
    private final BlockFactory factory;
    private final Map<BufferMode, TreeMap<Long, ArrayDeque<Idle>>> free = new EnumMap<>(BufferMode.class);
    private long retainedBytes;
    private int created;
    private boolean closed;

    public BlockPool(DeviceContext ctx) {
        this(ctx, (mode, size) -> TransientBlock.create(ctx, mode, size));
    }

    /** Test seam: blocks come from {@code factory}. */
    public BlockPool(DeviceContext ctx, BlockFactory factory) {
        this.ctx = ctx;
        this.factory = factory;
    }

    /** @return the size class (a power of two, at least {@link #MIN_CLASS_BYTES}) that serves a request of {@code size} bytes */
    public static long sizeClass(long size) {
        if (size <= 0L) {
            throw new IllegalArgumentException("size must be positive: " + size);
        }

        return Alignment.ceilPowerOfTwo(Math.max(size, MIN_CLASS_BYTES));
    }

    /**
     * @param size requested bytes ({@code > 512 KiB}); the returned block may be larger (power-of-two size class) but
     *             callers use only {@code size} bytes
     * @throws com.mojang.renderpearl.api.device.GpuOutOfMemoryException if a new block cannot be committed
     */
    public TransientBlock acquire(BufferMode mode, long size) {
        if (this.closed) {
            throw new IllegalStateException("The block pool is closed");
        }

        long sizeClass = sizeClass(size);
        TreeMap<Long, ArrayDeque<Idle>> classes = this.free.get(mode);

        if (classes != null) {
            ArrayDeque<Idle> idle = classes.get(sizeClass);

            if (idle != null && !idle.isEmpty()) {
                Idle taken = idle.pollLast();

                this.retainedBytes -= taken.block().size();

                if (idle.isEmpty()) {
                    classes.remove(sizeClass);
                }

                return taken.block();
            }
        }

        this.created++;

        return this.factory.create(mode, sizeClass);
    }

    /** Returns a block to its class. Called from a deferral entry (the GPU is done with it). */
    public void release(TransientBlock block) {
        if (this.closed || block.isDestroyed()) {
            block.destroy();

            return;
        }

        if (this.retainedBytes + block.size() > MAX_RETAINED_BYTES) {
            block.destroy();

            return;
        }

        this.free.computeIfAbsent(block.mode(), mode -> new TreeMap<>())
                .computeIfAbsent(block.size(), size -> new ArrayDeque<>())
                .addLast(new Idle(block, this.ctx.clock().current()));

        this.retainedBytes += block.size();
    }

    /** Destroys blocks that were not acquired for 120 frames. Called at {@code rotate}. */
    public void trim(long currentFrame) {
        if (this.retainedBytes == 0L) {
            return;
        }

        for (Iterator<TreeMap<Long, ArrayDeque<Idle>>> modes = this.free.values().iterator(); modes.hasNext(); ) {
            TreeMap<Long, ArrayDeque<Idle>> classes = modes.next();

            for (Iterator<ArrayDeque<Idle>> lists = classes.values().iterator(); lists.hasNext(); ) {
                ArrayDeque<Idle> idle = lists.next();

                // oldest first: stop at the first block that is young enough
                while (!idle.isEmpty() && currentFrame - idle.peekFirst().releasedInFrame() >= MAX_IDLE_FRAMES) {
                    TransientBlock block = idle.pollFirst().block();

                    this.retainedBytes -= block.size();
                    block.destroy();
                }

                if (idle.isEmpty()) {
                    lists.remove();
                }
            }

            if (classes.isEmpty()) {
                modes.remove();
            }
        }
    }

    /** Destroys everything (GPU idle). Idempotent. */
    public void close() {
        this.closed = true;

        for (TreeMap<Long, ArrayDeque<Idle>> classes : this.free.values()) {
            for (ArrayDeque<Idle> idle : classes.values()) {
                for (Idle entry : idle) {
                    entry.block().destroy();
                }
            }
        }

        this.free.clear();
        this.retainedBytes = 0L;
    }

    /** @return bytes currently kept in the free lists */
    public long retainedBytes() {
        return this.retainedBytes;
    }

    /** @return the number of blocks this pool had to create (not satisfied from a free list) */
    public int createdBlocks() {
        return this.created;
    }

    /** @return the number of blocks in the free lists */
    public int idleBlocks() {
        int count = 0;

        for (TreeMap<Long, ArrayDeque<Idle>> classes : this.free.values()) {
            for (ArrayDeque<Idle> idle : classes.values()) {
                count += idle.size();
            }
        }

        return count;
    }

    DeviceContext context() {
        return this.ctx;
    }
}
