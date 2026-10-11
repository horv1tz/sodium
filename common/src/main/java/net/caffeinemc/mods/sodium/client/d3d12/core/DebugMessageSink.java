// Owner: WP4 (diagnostics)
package net.caffeinemc.mods.sodium.client.d3d12.core;

import net.caffeinemc.mods.sodium.client.d3d12.backend.DeviceOptions;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12Device;

import java.util.List;

/**
 * Bridge from {@code ID3D12InfoQueue} (debug layer) to SLF4J and to {@code GpuDeviceBackend.getLastDebugMessages()}.
 *
 * <p>Behaviour (spec 03 section 14.3, proposal A section 2.k.3):
 * <ul>
 *     <li>Created right after the device. Without a debug layer ({@code device.getInfoQueue() == null}) {@link #create}
 *     returns {@link #disabled()}; every method is then a cheap no-op.</li>
 *     <li>The retrieval filter follows {@code options.logLevel()} (1 error, 2 +warning, 3 +info, 4 all) but ERROR and
 *     CORRUPTION are always kept when the layer is on. Messages that the backend causes on purpose
 *     are denied (storage filter, {@code Enums.MessageId}):
 *     <ul>
 *         <li>{@code CLEARRENDERTARGETVIEW_MISMATCHINGCLEARVALUE}, {@code CLEARDEPTHSTENCILVIEW_MISMATCHINGCLEARVALUE}
 *         (render targets carry an optimised clear value that real clears often differ from);</li>
 *         <li>{@code EXECUTECOMMANDLISTS_GPU_WRITTEN_READBACK_RESOURCE_MAPPED}: READBACK buffers (screenshots, Tracy
 *         capture, the query-pool resolve buffer) are persistently mapped by design while the GPU writes into them - the
 *         layer would report this on every {@code ExecuteCommandLists} that touches one, which strict mode (ERROR -> ISE)
 *         or the "zero errors" release gate would count as a failure;</li>
 *         <li>{@code MAP_INVALID_NULLRANGE}, {@code UNMAP_INVALID_NULLRANGE}: the empty-range Map/Unmap pairs that bracket
 *         CPU reads of READBACK buffers ({@code D3D12Buffer.beginCpuRead/endCpuRead}).</li>
 *     </ul></li>
 *     <li>{@link #drainToLog()} runs at the end of every {@code submit()} and after any failing native call; severities
 *     map to SLF4J levels, consecutive duplicates collapse to {@code "<text> x N"}.</li>
 *     <li>The last 32 distinct messages are kept for {@link #recentMessages()} (crash reports, device-loss text).</li>
 *     <li>{@link #takeErrorFlag()} reports whether an ERROR/CORRUPTION message was drained since the last call; in strict
 *     mode {@code submit()} turns that into an {@link IllegalStateException}.</li>
 * </ul>
 *
 * <p>Threading: {@link #drainToLog()} on the render thread only; {@link #recentMessages()} from any thread.
 */
public final class DebugMessageSink implements AutoCloseable {
    private static final DebugMessageSink DISABLED = new DebugMessageSink();

    private DebugMessageSink() {
    }

    /** @return the shared no-op sink */
    public static DebugMessageSink disabled() {
        return DISABLED;
    }

    /**
     * @return a sink bound to the device's info queue, or {@link #disabled()} when the debug layer is not active
     */
    public static DebugMessageSink create(D3D12Device device, DeviceOptions options) {
        throw new UnsupportedOperationException("WP4: wrap D3D12Device.getInfoQueue(), filters, ring of 32 messages");
    }

    public boolean active() {
        return this != DISABLED;
    }

    /** Drains the info queue into the log; no-op when inactive or empty. Render thread. */
    public void drainToLog() {
        if (this.active()) {
            throw new UnsupportedOperationException("WP4: drain InfoQueue, coalesce duplicates, map severities");
        }
    }

    /** @return a snapshot of the last (up to 32) distinct messages, never null */
    public List<String> recentMessages() {
        return List.of();
    }

    /** @return true once if an ERROR or CORRUPTION message was drained since the previous call */
    public boolean takeErrorFlag() {
        return false;
    }

    @Override
    public void close() {
    }
}
