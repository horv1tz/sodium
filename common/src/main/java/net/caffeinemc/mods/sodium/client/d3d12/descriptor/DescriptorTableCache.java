// Owner: WP4 (descriptors) - fully implemented pure logic, OPTIONAL optimisation used by WP3's PassBinder
package net.caffeinemc.mods.sodium.client.d3d12.descriptor;

import java.util.Arrays;

/**
 * Content-keyed cache of already built descriptor tables: if the 16 source descriptors of a table are identical to a
 * table built earlier <em>in the same heap page of the same frame slot</em>, the earlier GPU handle is bound again
 * instead of allocating and filling 16 new descriptors.
 *
 * <p><b>Ownership.</b> Every {@link ShaderVisibleRing} owns exactly one cache (its {@code tableCache()}) and clears it
 * itself whenever the selected heap changes (page flip, {@code beginFrame}, including the switch to the other frame
 * slot). Consumers never clear it and never keep entries across a generation change (the binder compares
 * {@code ring.epoch()}). A cache hit therefore can never name a table of another heap or of a rewound page.
 *
 * <p>Correctness rules (the reason this is safe):
 * <ul>
 *     <li>Keys are the CPU staging handles of the 16 descriptors. An entry that is not owned by a resource (the null
 *     descriptor substituted for it) is encoded <em>structurally by kind</em> as {@link #nullKey(int)} =
 *     {@code -(kind + 1)}: a {@code Texture2D} null, a cube null, the three typed-buffer nulls and the default sampler
 *     are different keys, because binding a null of the wrong dimension (a {@code Texture2D} null where the shader
 *     declares a {@code Buffer<int4>}) is undefined behaviour on D3D12. Real staging handles are positive addresses, a
 *     {@code 0} entry is a bug.</li>
 *     <li>A staging slot is only freed through the deferral queue, i.e. after the frame ended, so within one frame a
 *     handle identifies one content.</li>
 *     <li>The full key is compared on every hit (a hash collision must never bind the wrong texture).</li>
 *     <li>Tables that contain a texel-buffer SRV (created in the ring from the buffer, no staging handle: the key cannot
 *     see them) are never looked up and never inserted; the binder's "key equals the table already bound" skip does not
 *     apply to them either. The sampler table of such a layout has no such entry (its texel slots hold the default
 *     sampler), so it may use the cache.</li>
 * </ul>
 *
 * <p>Allocation-free after construction, open addressing, not thread-safe (render thread).
 */
public final class DescriptorTableCache {
    public static final int TABLE_SIZE = 16;

    /** Null-descriptor kinds for {@link #nullKey(int)}; one per distinct null descriptor of {@code NullDescriptors}. */
    public static final int NULL_TEXTURE_2D = 0;
    public static final int NULL_TEXTURE_CUBE = 1;
    public static final int NULL_BUFFER_INT = 2;
    public static final int NULL_BUFFER_UINT = 3;
    public static final int NULL_BUFFER_FLOAT = 4;
    public static final int NULL_SAMPLER = 5;

    private final int mask;
    private final long[] keys;
    private final long[] values;
    private int size;

    /** @param capacity number of table slots, rounded up to a power of two (>= 16) */
    public DescriptorTableCache(int capacity) {
        int pow2 = Math.max(16, Integer.highestOneBit(Math.max(1, capacity - 1)) << 1);

        this.mask = pow2 - 1;
        this.keys = new long[pow2 * TABLE_SIZE];
        this.values = new long[pow2];
    }

    /** @return the key entry for "the null descriptor of {@code kind}" ({@code NULL_*}): negative, never a real handle */
    public static long nullKey(int kind) {
        return -(kind + 1L);
    }

    /**
     * @param key a {@link #TABLE_SIZE}-element array of source handles
     * @return the GPU handle of an identical table built earlier this frame, or 0 if there is none
     */
    public long find(long[] key) {
        int slot = hash(key) & this.mask;

        while (this.values[slot] != 0L) {
            if (this.matches(slot, key)) {
                return this.values[slot];
            }

            slot = (slot + 1) & this.mask;
        }

        return 0L;
    }

    /**
     * Remembers a built table. A full cache silently ignores the insert (the cache is an optimisation only).
     *
     * @param gpuHandle non-zero GPU handle of the table's first descriptor
     */
    public void insert(long[] key, long gpuHandle) {
        if (this.size * 2 >= this.values.length || gpuHandle == 0L) {
            return;
        }

        int slot = hash(key) & this.mask;

        while (this.values[slot] != 0L) {
            if (this.matches(slot, key)) {
                this.values[slot] = gpuHandle;
                return;
            }

            slot = (slot + 1) & this.mask;
        }

        System.arraycopy(key, 0, this.keys, slot * TABLE_SIZE, TABLE_SIZE);
        this.values[slot] = gpuHandle;
        this.size++;
    }

    /** Forgets everything (called by the owning ring; consumers do not call it). */
    public void clear() {
        if (this.size != 0) {
            Arrays.fill(this.values, 0L);
            this.size = 0;
        }
    }

    public int size() {
        return this.size;
    }

    private boolean matches(int slot, long[] key) {
        int base = slot * TABLE_SIZE;

        for (int i = 0; i < TABLE_SIZE; i++) {
            if (this.keys[base + i] != key[i]) {
                return false;
            }
        }

        return true;
    }

    private static int hash(long[] key) {
        long h = 0x9E3779B97F4A7C15L;

        for (int i = 0; i < TABLE_SIZE; i++) {
            h = (h ^ key[i]) * 0xBF58476D1CE4E5B9L;
            h ^= h >>> 29;
        }

        return (int) (h ^ (h >>> 32));
    }
}
