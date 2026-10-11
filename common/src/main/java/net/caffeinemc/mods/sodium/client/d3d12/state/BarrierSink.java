// Owner: WP1 (device core)
package net.caffeinemc.mods.sodium.client.d3d12.state;

/**
 * Receiver of the transitions a {@link ResourceStateTracker} has queued. The production sink writes
 * {@code D3D12_RESOURCE_BARRIER}s into a command list; unit tests use a recording sink and check the sequence against an
 * independent model of the D3D12 rules.
 */
@FunctionalInterface
public interface BarrierSink {
    /**
     * @param resource    the tracked resource
     * @param subresource a concrete subresource index (never {@code ALL}: the tracker emits one transition per subresource)
     * @param before      the state the GPU is in at this point of the command stream
     * @param after       the state to move to
     */
    void transition(TrackedResource resource, int subresource, int before, int after);
}
