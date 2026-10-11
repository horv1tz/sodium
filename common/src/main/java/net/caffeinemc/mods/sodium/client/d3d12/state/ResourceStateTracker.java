// Owner: WP1 (device core) - implemented; WP1 owner adds StateTrackerTest (oracle + random sequences)
package net.caffeinemc.mods.sodium.client.d3d12.state;

import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Enums;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12ResourceBarrier;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12ResourceTransitionBarrier;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12GraphicsCommandList;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12Resource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;

/**
 * The CPU-side truth about resource states: lazy, batched, fully explicit transitions (invariants I2/I3).
 *
 * <p><b>Why this is correct.</b> There is exactly one command list per frame and the tracker is updated in recording
 * order, which is also execution order. The state a barrier names as "before" is therefore always the real GPU state at
 * that point of the stream. Every command is preceded by {@code require(..)} for each resource it touches followed by
 * {@link #flush}; {@code submit()} flushes once more before {@code Close()} so that a trailing {@code RT -> PRESENT} is
 * never lost.
 *
 * <p><b>Rules.</b>
 * <ul>
 *     <li>{@code require(r, sub, state)} makes the tracker state of the subresource <em>exactly</em> {@code state}, unless
 *     the current state is a read state that already covers it ({@link ResourceStates#needsTransition}), in which case
 *     nothing changes.</li>
 *     <li>At most <b>one pending barrier per (resource, subresource)</b>: a later request for the same subresource
 *     merges into the pending entry ({@code A->B} then {@code B->C} becomes {@code A->C}; {@code A->B} then {@code B->A}
 *     disappears). This also guarantees that one {@code ResourceBarrier} call never names a subresource twice (a debug
 *     layer error). {@code D3D12_RESOURCE_BARRIER_ALL_SUBRESOURCES} is never emitted; {@code require(.., ALL, ..)} expands
 *     to one transition per subresource.</li>
 *     <li>{@link #writeSerial()} increases whenever a transition <em>away from a read state</em> is queued (a copy
 *     destination, render target or depth write was requested). The render-pass binder re-validates its bound resources
 *     only when this serial moved since it last validated.</li>
 *     <li>A pending barrier is never lost: an exception between {@code require} and {@code flush} cannot desynchronise
 *     tracker and GPU unless the whole list is discarded (then the device is FAILED anyway).</li>
 * </ul>
 *
 * <p>Threading: render thread only. No allocation in steady state (arrays grow geometrically).
 */
public final class ResourceStateTracker {
    private static final int INITIAL_CAPACITY = 64;

    private TrackedResource[] resources = new TrackedResource[INITIAL_CAPACITY];
    private int[] subresources = new int[INITIAL_CAPACITY];
    private int[] before = new int[INITIAL_CAPACITY];
    private int[] after = new int[INITIAL_CAPACITY];
    private int count;
    private long writeSerial;

    private MemorySegment nativeBarriers;
    private int nativeCapacity;

    /**
     * Ensures {@code subresource} (or every subresource for {@link ResourceStates#ALL}) will be in {@code state} by the
     * time the next flushed command executes.
     *
     * @throws IllegalArgumentException if the subresource does not exist (checked before anything changes: a request that
     *                                  named a non-existent subresource would otherwise desynchronise tracker and GPU)
     */
    public void require(TrackedResource resource, int subresource, int state) {
        if (subresource == ResourceStates.ALL) {
            for (int s = 0; s < resource.subresourceCount(); s++) {
                this.requireOne(resource, s, state);
            }
        } else {
            checkSubresource(resource, subresource);
            this.requireOne(resource, subresource, state);
        }
    }

    /**
     * {@link #require} for {@code count} consecutive subresources starting at {@code firstSubresource}.
     *
     * @throws IllegalArgumentException if the range is not inside the resource (nothing is applied then, not even the part
     *                                  that is inside)
     */
    public void requireRange(TrackedResource resource, int firstSubresource, int count, int state) {
        checkRange(resource, firstSubresource, count);

        for (int s = firstSubresource, end = firstSubresource + count; s < end; s++) {
            this.requireOne(resource, s, state);
        }
    }

    private static void checkSubresource(TrackedResource resource, int subresource) {
        if (subresource < 0 || subresource >= resource.subresourceCount()) {
            throw new IllegalArgumentException("subresource " + subresource + " does not exist in a resource with "
                    + resource.subresourceCount() + " subresource(s)");
        }
    }

    private static void checkRange(TrackedResource resource, int first, int count) {
        // long arithmetic: first + count must not wrap around into a valid-looking number
        if (first < 0 || count < 0 || (long) first + count > resource.subresourceCount()) {
            throw new IllegalArgumentException("subresource range [" + first + ", +" + count + ") is not inside a resource with "
                    + resource.subresourceCount() + " subresource(s)");
        }
    }

    /**
     * {@link #require} for the set {@code {mips baseMip .. baseMip + mipCount - 1} x {layers 0 .. layers - 1}}: one
     * contiguous range per layer. Needed because the subresource index is {@code mip + layer * mipLevels}: for a texture
     * with more than one layer, "a mip range of all layers" (a cube SRV over a partial mip range) is <em>not</em> a
     * contiguous range, and {@code requireRange(baseMip, mipCount)} would transition layer 0 only and leave faces 1..5 in
     * the old state while the SRV is bound.
     *
     * @param mipLevels total mip levels of the texture
     * @param layers    array layers of the texture ({@code mipLevels * layers == resource.subresourceCount()})
     * @throws IllegalArgumentException if the geometry does not match the resource or the mip range is out of bounds
     */
    public void requireMips(TrackedResource resource, int mipLevels, int baseMip, int mipCount, int layers, int state) {
        if (mipLevels < 1 || layers < 1 || (long) mipLevels * layers != resource.subresourceCount() || baseMip < 0
                || mipCount < 1 || (long) baseMip + mipCount > mipLevels) {
            throw new IllegalArgumentException("invalid mip range [" + baseMip + ", +" + mipCount + ") of " + mipLevels
                    + " mips x " + layers + " layers for a resource with " + resource.subresourceCount() + " subresources");
        }

        for (int layer = 0; layer < layers; layer++) {
            this.requireRange(resource, layer * mipLevels + baseMip, mipCount, state);
        }
    }

    /**
     * @return true if subresource {@code subresource} (index {@code mip + layer * mipLevels}) lies in the set
     * {@code {mips baseMip .. baseMip + mipCount - 1} x {layers 0 .. layers - 1}}; the feedback-loop test of the binder:
     * a texture view may not be sampled while <em>any</em> of its subresources is an attachment of the pass (not just
     * {@code baseMip} of layer 0)
     */
    public static boolean subresourceInMipRange(int subresource, int mipLevels, int baseMip, int mipCount, int layers) {
        if (subresource < 0 || mipLevels < 1 || subresource >= (long) mipLevels * layers) {
            return false;
        }

        int mip = subresource % mipLevels;

        return mip >= baseMip && mip < (long) baseMip + mipCount;
    }

    private void requireOne(TrackedResource r, int s, int wanted) {
        int current = r.perSub == null ? r.uniformState : r.perSub[s];

        if (!ResourceStates.needsTransition(current, wanted)) {
            return;
        }

        this.setState(r, s, wanted);

        if (!ResourceStates.isReadOnly(wanted)) {
            this.writeSerial++;
        }

        int pending = r.pendingIdx == null ? -1 : r.pendingIdx[s];

        if (pending >= 0) {
            if (this.before[pending] == wanted) {
                this.removePending(pending);
            } else {
                this.after[pending] = wanted;
            }

            return;
        }

        this.enqueue(r, s, current, wanted);
    }

    /**
     * The per-subresource array is allocated by the first transition that makes the states differ and then kept for the
     * life of the resource: collapsing it back to the uniform scalar when the states become equal again (what an earlier
     * version did after every flush) made every "render into one mip, then sample all mips" cycle allocate a new array
     * (about 170 bytes per frame in the steady-state test), on the render thread's hot path.
     */
    private void setState(TrackedResource r, int s, int state) {
        if (r.perSub != null) {
            r.perSub[s] = state;
        } else if (r.subresourceCount() == 1) {
            r.uniformState = state;
        } else if (state != r.uniformState) {
            r.perSub = new int[r.subresourceCount()];
            Arrays.fill(r.perSub, r.uniformState);
            r.perSub[s] = state;
        }
    }

    private void enqueue(TrackedResource r, int s, int from, int to) {
        if (this.count == this.resources.length) {
            int capacity = this.count * 2;

            this.resources = Arrays.copyOf(this.resources, capacity);
            this.subresources = Arrays.copyOf(this.subresources, capacity);
            this.before = Arrays.copyOf(this.before, capacity);
            this.after = Arrays.copyOf(this.after, capacity);
        }

        if (r.pendingIdx == null) {
            r.pendingIdx = new int[r.subresourceCount()];
            Arrays.fill(r.pendingIdx, -1);
        }

        int index = this.count++;

        this.resources[index] = r;
        this.subresources[index] = s;
        this.before[index] = from;
        this.after[index] = to;
        r.pendingIdx[s] = index;
    }

    private void removePending(int index) {
        TrackedResource removed = this.resources[index];
        removed.pendingIdx[this.subresources[index]] = -1;

        int last = --this.count;

        if (index != last) {
            this.resources[index] = this.resources[last];
            this.subresources[index] = this.subresources[last];
            this.before[index] = this.before[last];
            this.after[index] = this.after[last];
            this.resources[index].pendingIdx[this.subresources[index]] = index;
        }

        this.resources[last] = null;
    }

    /**
     * @return the tracked state of one subresource (including pending transitions). For a resource whose subresources
     * differ, {@code ALL} is not meaningful and the first subresource is returned.
     * @throws IllegalArgumentException if the subresource does not exist (the same for uniform and split resources)
     */
    public int stateOf(TrackedResource resource, int subresource) {
        return resource.state(subresource == ResourceStates.ALL ? 0 : subresource);
    }

    /** @return the number of barriers waiting for {@link #flush}; the binder skips flushing when it is 0 */
    public int pendingCount() {
        return this.count;
    }

    /** @return a counter that increases whenever a resource is moved away from a read state; see class comment */
    public long writeSerial() {
        return this.writeSerial;
    }

    /**
     * Emits all pending transitions with one {@code ResourceBarrier} call. No-op when nothing is pending.
     * Requires non-null native resources.
     */
    public void flush(D3D12GraphicsCommandList list) {
        if (this.count == 0) {
            return;
        }

        this.ensureNativeCapacity(this.count);

        for (int i = 0; i < this.count; i++) {
            writeBarrier(this.nativeBarriers, i, this.resources[i].resource(), this.subresources[i], this.before[i], this.after[i]);
        }

        list.resourceBarrier(this.count, this.nativeBarriers);
        this.clearPending();
    }

    /**
     * Writes element {@code index} of a {@code D3D12_RESOURCE_BARRIER} array with a transition barrier, using the
     * generated absolute offsets: no {@code MemorySegment} slice is created per barrier (the same bytes as
     * {@code D3D12GraphicsCommandList.writeTransitionBarrier}, which an L0 test compares against).
     */
    static void writeBarrier(MemorySegment array, int index, D3D12Resource resource, int subresource, int before, int after) {
        long barrier = (long) index * D3D12ResourceBarrier.SIZE;
        long transition = barrier + D3D12ResourceBarrier.OFFSET_TRANSITION;

        array.set(ValueLayout.JAVA_INT_UNALIGNED, barrier + D3D12ResourceBarrier.OFFSET_TYPE, Enums.ResourceBarrierType.TRANSITION);
        array.set(ValueLayout.JAVA_INT_UNALIGNED, barrier + D3D12ResourceBarrier.OFFSET_FLAGS, Enums.ResourceBarrierFlags.NONE);
        array.set(ValueLayout.ADDRESS_UNALIGNED, transition + D3D12ResourceTransitionBarrier.OFFSET_P_RESOURCE, resource.ptr());
        array.set(ValueLayout.JAVA_INT_UNALIGNED, transition + D3D12ResourceTransitionBarrier.OFFSET_SUBRESOURCE, subresource);
        array.set(ValueLayout.JAVA_INT_UNALIGNED, transition + D3D12ResourceTransitionBarrier.OFFSET_STATE_BEFORE, before);
        array.set(ValueLayout.JAVA_INT_UNALIGNED, transition + D3D12ResourceTransitionBarrier.OFFSET_STATE_AFTER, after);
    }

    /** Test seam: hands every pending transition to {@code sink} (in queue order) and clears the batch. */
    public void drain(BarrierSink sink) {
        for (int i = 0; i < this.count; i++) {
            sink.transition(this.resources[i], this.subresources[i], this.before[i], this.after[i]);
        }

        this.clearPending();
    }

    /**
     * Drops all pending transitions without emitting them. Only for a discarded (never executed) command list; the
     * tracker state then no longer matches the GPU, which is acceptable because the device is being shut down.
     */
    public void discardPending() {
        this.clearPending();
    }

    private void clearPending() {
        for (int i = 0; i < this.count; i++) {
            TrackedResource r = this.resources[i];

            r.pendingIdx[this.subresources[i]] = -1;
            this.resources[i] = null;
        }

        this.count = 0;
    }

    private void ensureNativeCapacity(int barriers) {
        if (barriers <= this.nativeCapacity) {
            return;
        }

        int capacity = Math.max(barriers, Math.max(64, this.nativeCapacity * 2));

        this.nativeBarriers = D3D12ResourceBarrier.allocateArray(Arena.ofAuto(), capacity);
        this.nativeCapacity = capacity;
    }
}
