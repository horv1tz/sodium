// Owner: WP1 (device core) - fully implemented shared helper
package net.caffeinemc.mods.sodium.client.d3d12.core;

import com.mojang.renderpearl.util.UncheckedAutoCloseable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of the objects that own native state and that the frontend creates but may never close: the Tracy timestamp
 * query pool, pipelines (including the built-in present blit), samplers (the {@code SamplerCache}).
 * {@code D3D12GpuDevice.close()} force-closes survivors (after the GPU is idle) so that no COM object outlives the
 * device, and logs one WARN with the count per kind.
 *
 * <p><b>Not for fences</b> ({@code D3D12GpuFence} holds only a frame index and Sodium creates fences every frame; the
 * registry holds strong references) and not for buffers/textures (the device release frees them).
 *
 * <p>Objects register themselves in their constructor and unregister in {@code close()}. Thread-safe.
 */
public final class ObjectRegistry {
    private final Map<UncheckedAutoCloseable, String> live = Collections.synchronizedMap(new IdentityHashMap<>());

    public void register(UncheckedAutoCloseable object, String kind) {
        this.live.put(object, kind);
    }

    public void unregister(UncheckedAutoCloseable object) {
        this.live.remove(object);
    }

    public int size() {
        return this.live.size();
    }

    /**
     * Closes every registered object (each close is isolated: an exception is logged and the rest still closes).
     *
     * @return the number of objects that were still open
     */
    public int closeAll() {
        List<Map.Entry<UncheckedAutoCloseable, String>> snapshot;

        synchronized (this.live) {
            snapshot = new ArrayList<>(this.live.entrySet());
            this.live.clear();
        }

        for (Map.Entry<UncheckedAutoCloseable, String> entry : snapshot) {
            try {
                entry.getKey().close();
            } catch (Throwable t) {
                D3D12Log.error("Failed to close leaked Direct3D 12 {}", entry.getValue(), t);
            }
        }

        if (!snapshot.isEmpty()) {
            D3D12Log.warn("{} Direct3D 12 object(s) were still open at device shutdown and were closed by the backend", snapshot.size());
        }

        return snapshot.size();
    }
}
