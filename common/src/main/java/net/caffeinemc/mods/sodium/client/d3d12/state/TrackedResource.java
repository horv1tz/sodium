// Owner: WP1 (device core) - fully implemented data holder
package net.caffeinemc.mods.sodium.client.d3d12.state;

import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12Resource;
import org.jspecify.annotations.Nullable;

/**
 * The CPU-side state of one D3D12 resource that needs explicit transitions: DEFAULT buffers (1 subresource),
 * textures ({@code mip + layer * mipLevels}), swap-chain back buffers and DEFAULT transient blocks.
 *
 * <p>Owned by the object that owns the resource (buffer, texture, surface); the tracker only reads and writes the
 * package-private state. Render thread only. {@code resource} may be null in Linux unit tests that drain the tracker
 * into a recording {@link BarrierSink}.
 */
public final class TrackedResource {
    private final @Nullable D3D12Resource resource;
    private final int subresources;

    /** State of every subresource while {@link #perSub} is null. */
    int uniformState;
    /** Per-subresource states, allocated on the first transition that makes them differ. */
    int @Nullable [] perSub;
    /** Index of the pending barrier of each subresource inside the tracker's batch, -1 = none; allocated lazily. */
    int @Nullable [] pendingIdx;

    /**
     * @param resource     the native resource
     * @param subresources number of subresources (1 for buffers)
     * @param initialState the state the resource was created in (the state of every subresource)
     */
    public TrackedResource(@Nullable D3D12Resource resource, int subresources, int initialState) {
        if (subresources < 1) {
            throw new IllegalArgumentException("subresources must be >= 1: " + subresources);
        }

        this.resource = resource;
        this.subresources = subresources;
        this.uniformState = initialState;
    }

    public @Nullable D3D12Resource resource() {
        return this.resource;
    }

    public int subresourceCount() {
        return this.subresources;
    }

    /** @return the tracked state of one subresource, including transitions that are still pending */
    public int state(int subresource) {
        return this.perSub == null ? this.uniformState : this.perSub[subresource];
    }
}
