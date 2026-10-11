// Owner: WP4 (diagnostics) - implemented
package net.caffeinemc.mods.sodium.client.d3d12.core;

import net.caffeinemc.mods.sodium.client.d3d12.backend.DeviceOptions;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Enums;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12Debug;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12Device;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bridge from {@code ID3D12InfoQueue} (debug layer) to SLF4J and to {@code GpuDeviceBackend.getLastDebugMessages()}.
 *
 * <p>Behaviour (spec 03 section 14.3, proposal A section 2.k.3):
 * <ul>
 *     <li>Created right after the device. Without a debug layer ({@code device.getInfoQueue() == null}) {@link #create}
 *     returns {@link #disabled()}; every method is then a cheap no-op.</li>
 *     <li>The severities follow {@code options.logLevel()} (1 error, 2 +warning, 3 +info, 4 all) but ERROR and
 *     CORRUPTION are always kept when the layer is on. The interop wrapper offers no retrieval filter, so the level is
 *     applied twice: as a storage-filter deny entry on the severities above the level (they never fill the queue's message
 *     limit) and in {@link Logic} (a backstop, and the testable part). Messages that the backend causes on purpose
 *     are denied by id (storage filter, {@code Enums.MessageId}):
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
 *     map to SLF4J levels (CORRUPTION/ERROR -> error, WARNING -> warn, INFO -> info, MESSAGE -> debug), consecutive
 *     duplicates of one drain collapse to {@code "<text> x N"}. It never throws.</li>
 *     <li>The last 32 distinct messages are kept for {@link #recentMessages()} (crash reports, device-loss text).</li>
 *     <li>{@link #takeErrorFlag()} reports whether an ERROR/CORRUPTION message was drained since the last call; in strict
 *     mode {@code submit()} turns that into an {@link IllegalStateException}.</li>
 * </ul>
 *
 * <p>Threading: {@link #drainToLog()} on the render thread only; {@link #recentMessages()} from any thread.
 */
public final class DebugMessageSink implements AutoCloseable {
    /** Messages kept for {@link #recentMessages()}. */
    public static final int RING_SIZE = 32;

    /**
     * The message ids the backend provokes on purpose and that must never reach the log or strict mode (see the class
     * comment); {@link #deniedMessageIds()} exposes a copy for the test of the deny list.
     */
    private static final int[] DENIED_IDS = {
            Enums.MessageId.CLEARRENDERTARGETVIEW_MISMATCHINGCLEARVALUE,
            Enums.MessageId.CLEARDEPTHSTENCILVIEW_MISMATCHINGCLEARVALUE,
            Enums.MessageId.EXECUTECOMMANDLISTS_GPU_WRITTEN_READBACK_RESOURCE_MAPPED,
            Enums.MessageId.MAP_INVALID_NULLRANGE,
            Enums.MessageId.UNMAP_INVALID_NULLRANGE
    };

    private static final DebugMessageSink DISABLED = new DebugMessageSink(null, null);

    private final D3D12Debug.@Nullable InfoQueue queue;
    private final @Nullable Logic logic;
    private final AtomicBoolean closed = new AtomicBoolean();

    private DebugMessageSink(D3D12Debug.@Nullable InfoQueue queue, @Nullable Logic logic) {
        this.queue = queue;
        this.logic = logic;
    }

    /** @return the shared no-op sink */
    public static DebugMessageSink disabled() {
        return DISABLED;
    }

    /** @return the message ids that are denied at the storage filter (a copy) */
    public static int[] deniedMessageIds() {
        return DENIED_IDS.clone();
    }

    /**
     * @return the severities (D3D12_MESSAGE_SEVERITY values) that are <em>dropped</em> for a log level, i.e. the ones
     * above it; CORRUPTION and ERROR are never in the result
     */
    public static int[] deniedSeverities(int logLevel) {
        if (logLevel >= 4) {
            return new int[0];
        }

        if (logLevel == 3) {
            return new int[]{Enums.MessageSeverity.MESSAGE};
        }

        if (logLevel == 2) {
            return new int[]{Enums.MessageSeverity.INFO, Enums.MessageSeverity.MESSAGE};
        }

        return new int[]{Enums.MessageSeverity.WARNING, Enums.MessageSeverity.INFO, Enums.MessageSeverity.MESSAGE};
    }

    /**
     * @return a sink bound to the device's info queue, or {@link #disabled()} when the debug layer is not active. A
     * failure to configure the queue is logged and leaves the sink working with the default filters (the layer is a
     * diagnostic aid: it must never prevent device creation).
     */
    public static DebugMessageSink create(D3D12Device device, DeviceOptions options) {
        D3D12Debug.InfoQueue queue = device.getInfoQueue();

        if (queue == null) {
            return DISABLED;
        }

        try {
            queue.addStorageFilterEntries(null, D3D12Debug.InfoQueue.Filter.ofIds(DENIED_IDS));

            int[] severities = deniedSeverities(options.logLevel());

            if (severities.length > 0) {
                queue.addStorageFilterEntries(null, D3D12Debug.InfoQueue.Filter.ofSeverities(severities));
            }

            queue.clearStoredMessages();
        } catch (RuntimeException e) {
            D3D12Log.warn("Could not configure the Direct3D 12 info queue filters: {}", e.toString());
        }

        return new DebugMessageSink(queue, new Logic(options.logLevel(), new LogOutput()));
    }

    public boolean active() {
        return this != DISABLED;
    }

    /** Drains the info queue into the log; no-op when inactive or empty. Render thread. Never throws. */
    public void drainToLog() {
        D3D12Debug.InfoQueue queue = this.queue;
        Logic logic = this.logic;

        if (queue == null || logic == null || this.closed.get()) {
            return;
        }

        try {
            if (queue.getNumStoredMessages() == 0L) {
                return;
            }

            for (D3D12Debug.InfoQueue.Message message : queue.drainMessages()) {
                logic.accept(message.severity(), message.id(), message.description());
            }
        } catch (RuntimeException e) {
            D3D12Log.warnOnce("debug-sink-drain", "Could not read the Direct3D 12 info queue: {}", e.toString());
        } finally {
            logic.flush();
        }
    }

    /** @return a snapshot of the last (up to {@value #RING_SIZE}) distinct messages, never null */
    public List<String> recentMessages() {
        Logic logic = this.logic;

        return logic == null ? List.of() : logic.recent();
    }

    /** @return true once if an ERROR or CORRUPTION message was drained since the previous call */
    public boolean takeErrorFlag() {
        Logic logic = this.logic;

        return logic != null && logic.takeErrorFlag();
    }

    @Override
    public void close() {
        if (this != DISABLED && this.closed.compareAndSet(false, true) && this.queue != null) {
            try {
                this.queue.close();
            } catch (RuntimeException e) {
                D3D12Log.warn("Could not release the Direct3D 12 info queue: {}", e.toString());
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------

    /** Where {@link Logic} sends the lines it produced. */
    @FunctionalInterface
    public interface Output {
        void log(Level level, String text);
    }

    /** The SLF4J level a debug-layer severity maps to. */
    public enum Level {
        ERROR,
        WARN,
        INFO,
        DEBUG
    }

    private static final class LogOutput implements Output {
        @Override
        public void log(Level level, String text) {
            switch (level) {
                case ERROR -> D3D12Log.error("[D3D12 debug layer] {}", text);
                case WARN -> D3D12Log.warn("[D3D12 debug layer] {}", text);
                case INFO -> D3D12Log.info("[D3D12 debug layer] {}", text);
                case DEBUG -> D3D12Log.debug("[D3D12 debug layer] {}", text);
            }
        }
    }

    /**
     * The pure part of the sink: severity filtering by log level, severity -> level mapping, coalescing of consecutive
     * duplicates ({@code "<text> x N"}), the ring of the last {@value #RING_SIZE} distinct lines and the error flag.
     * No native access, no static state: unit tests drive it directly.
     *
     * <p>Usage per drain: {@link #accept} for every message in order, then {@link #flush()}, which emits the run that is
     * still open. {@link #recent()} and {@link #takeErrorFlag()} may be called from any thread; {@link #accept} and
     * {@link #flush()} from one.
     */
    public static final class Logic {
        private final int logLevel;
        private final Output output;
        private final LinkedHashSet<String> ring = new LinkedHashSet<>();
        private final AtomicBoolean errorFlag = new AtomicBoolean();

        private int pendingSeverity;
        private @Nullable String pendingText;
        private int pendingCount;

        public Logic(int logLevel, Output output) {
            this.logLevel = logLevel;
            this.output = output;
        }

        /**
         * @param severity a {@code D3D12_MESSAGE_SEVERITY}
         * @param id       the message id (unused for filtering: ids are denied at the storage filter)
         */
        public void accept(int severity, int id, String text) {
            if (!this.passes(severity)) {
                return;
            }

            if (severity <= Enums.MessageSeverity.ERROR) {
                this.errorFlag.set(true);
            }

            String trimmed = text.strip();

            if (this.pendingCount > 0 && this.pendingSeverity == severity && trimmed.equals(this.pendingText)) {
                this.pendingCount++;

                return;
            }

            this.flush();
            this.pendingSeverity = severity;
            this.pendingText = trimmed;
            this.pendingCount = 1;
        }

        /** Emits the open run, if any. */
        public void flush() {
            String text = this.pendingText;

            if (this.pendingCount == 0 || text == null) {
                return;
            }

            String line = this.pendingCount == 1 ? text : text + " x " + this.pendingCount;
            Level level = levelOf(this.pendingSeverity);

            this.pendingCount = 0;
            this.pendingText = null;

            synchronized (this.ring) {
                this.ring.remove(line);
                this.ring.add(line);

                while (this.ring.size() > RING_SIZE) {
                    String oldest = this.ring.iterator().next();
                    this.ring.remove(oldest);
                }
            }

            this.output.log(level, line);
        }

        /** @return a snapshot of the last distinct lines, oldest first */
        public List<String> recent() {
            synchronized (this.ring) {
                return new ArrayList<>(this.ring);
            }
        }

        public boolean takeErrorFlag() {
            return this.errorFlag.getAndSet(false);
        }

        private boolean passes(int severity) {
            return switch (severity) {
                case Enums.MessageSeverity.CORRUPTION, Enums.MessageSeverity.ERROR -> true;
                case Enums.MessageSeverity.WARNING -> this.logLevel >= 2;
                case Enums.MessageSeverity.INFO -> this.logLevel >= 3;
                default -> this.logLevel >= 4;
            };
        }

        /** @return the SLF4J level of a debug-layer severity */
        public static Level levelOf(int severity) {
            return switch (severity) {
                case Enums.MessageSeverity.CORRUPTION, Enums.MessageSeverity.ERROR -> Level.ERROR;
                case Enums.MessageSeverity.WARNING -> Level.WARN;
                case Enums.MessageSeverity.INFO -> Level.INFO;
                default -> Level.DEBUG;
            };
        }
    }
}
