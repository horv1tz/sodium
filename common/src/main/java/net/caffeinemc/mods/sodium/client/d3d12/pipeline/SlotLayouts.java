// Owner: WP3 (pipelines + render pass) - fully implemented
package net.caffeinemc.mods.sodium.client.d3d12.pipeline;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The intern table of {@link SlotLayout}s. Thread-safe: pipelines are compiled on worker threads.
 * One instance per device (owned by {@code D3D12PipelineCompiler}).
 */
public final class SlotLayouts {
    private final ConcurrentHashMap<String, SlotLayout> layouts = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger();

    /** @return the canonical instance structurally equal to {@code candidate} (adding it with a fresh id if new) */
    public SlotLayout intern(SlotLayout candidate) {
        return this.layouts.computeIfAbsent(candidate.key(), key -> candidate.withId(this.nextId.getAndIncrement()));
    }

    public int size() {
        return this.layouts.size();
    }
}
