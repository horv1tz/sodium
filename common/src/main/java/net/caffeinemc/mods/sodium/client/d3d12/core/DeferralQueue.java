// Owner: WP1 (device core) - fully implemented shared helper
package net.caffeinemc.mods.sodium.client.d3d12.core;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * "Run this after frame {@code tag} has completed on the GPU" - the one mechanism for deferred destruction, descriptor
 * release, block recycling and read-back callbacks (invariant I4: nothing a recorded command may still reference is
 * ever released directly).
 *
 * <p>Contract:
 * <ul>
 *     <li><b>Tags are not monotonic.</b> {@code DeviceContext.defer} tags render-thread entries with the current frame
 *     {@code f} and foreign-thread entries with {@code f + 1}, so a foreign entry can sit in front of later render-thread
 *     entries with a smaller tag. {@link #drain} therefore runs <em>every</em> entry whose tag is {@code <= completed},
 *     in insertion order among those, and leaves the others where they are: one foreign-thread entry (a shader worker
 *     closing a pipeline every frame) can never delay a later render-thread entry such as a read-back callback or a
 *     block return. (An earlier prefix-only drain did exactly that.)</li>
 *     <li>Entries are detached <em>before</em> they run: an entry that adds more entries (a read-back callback that
 *     closes its buffer) neither corrupts the iteration nor runs in the same drain - the new entry carries the newer
 *     frame tag.</li>
 *     <li>Every entry runs in its own try/catch; an exception is logged and the rest of the batch still runs (a lost
 *     {@code Release} is a leak, a lost callback is a lost screenshot). {@link VirtualMachineError}s are rethrown.</li>
 *     <li>{@link #add} is thread-safe; {@link #drain} and {@link #drainAll} run on the render thread (at shutdown
 *     {@code DeviceContext.defer} may call {@link #drainAll} from a foreign thread after the GPU went idle).</li>
 *     <li>Cost: {@code drain} is O(1) when nothing is due (the smallest pending tag is tracked) and O(n) in the number of
 *     pending entries otherwise; pending entries are at most two frames' worth.</li>
 * </ul>
 */
public final class DeferralQueue {
    private record Entry(long tag, Runnable action) {
    }

    private final Object lock = new Object();
    private final ArrayList<Entry> entries = new ArrayList<>();
    private long minTag = Long.MAX_VALUE;

    /** The batch list of {@link #drain}, kept between calls so that a frame with due entries allocates no list. */
    private @Nullable ArrayList<Entry> spare = new ArrayList<>();

    /**
     * @param tag    the frame index after whose completion {@code action} may run
     * @param action must not throw checked exceptions; runs on the render thread
     */
    public void add(long tag, Runnable action) {
        Entry entry = new Entry(tag, action);

        synchronized (this.lock) {
            this.entries.add(entry);

            if (tag < this.minTag) {
                this.minTag = tag;
            }
        }
    }

    /**
     * Runs every entry whose tag is {@code <= completed}, in insertion order.
     *
     * @return the number of entries run
     */
    public int drain(long completed) {
        ArrayList<Entry> ready;

        synchronized (this.lock) {
            if (this.minTag > completed) {
                return 0;
            }

            // a re-entrant or concurrent drain finds the spare list taken and simply allocates its own
            ready = this.spare != null ? this.spare : new ArrayList<>();
            this.spare = null;

            int kept = 0;
            long newMin = Long.MAX_VALUE;

            for (int i = 0; i < this.entries.size(); i++) {
                Entry entry = this.entries.get(i);

                if (entry.tag() <= completed) {
                    ready.add(entry);
                } else {
                    this.entries.set(kept++, entry);

                    if (entry.tag() < newMin) {
                        newMin = entry.tag();
                    }
                }
            }

            for (int i = this.entries.size() - 1; i >= kept; i--) {
                this.entries.remove(i);
            }

            this.minTag = newMin;
        }

        int count = ready.size();

        try {
            runAll(ready);
        } finally {
            ready.clear();

            synchronized (this.lock) {
                if (this.spare == null) {
                    this.spare = ready;
                }
            }
        }

        return count;
    }

    /**
     * Shutdown: runs everything regardless of tags, repeatedly, until the queue is truly empty (entries queued by
     * running entries are not stranded). Call only after the GPU is idle.
     */
    public void drainAll() {
        while (true) {
            List<Entry> batch;

            synchronized (this.lock) {
                if (this.entries.isEmpty()) {
                    return;
                }

                batch = new ArrayList<>(this.entries);
                this.entries.clear();
                this.minTag = Long.MAX_VALUE;
            }

            runAll(batch);
        }
    }

    public boolean isEmpty() {
        synchronized (this.lock) {
            return this.entries.isEmpty();
        }
    }

    public int size() {
        synchronized (this.lock) {
            return this.entries.size();
        }
    }

    private static void runAll(List<Entry> batch) {
        for (int i = 0; i < batch.size(); i++) {
            try {
                batch.get(i).action().run();
            } catch (VirtualMachineError error) {
                throw error;
            } catch (Throwable t) {
                D3D12Log.error("Deferred D3D12 action failed", t);
            }
        }
    }
}
