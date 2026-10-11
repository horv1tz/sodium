// Owner: WP4 (diagnostics) - fully implemented shared helper
package net.caffeinemc.mods.sodium.client.d3d12.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The single logger of the Direct3D 12 backend ({@code Sodium/D3D12}).
 *
 * <p>{@link #warnOnce} is the logging half of the "tolerate, substitute, warn once" policy of the render-pass binder:
 * a hostile or buggy caller can produce millions of identical events and must not be able to flood the log. The set of
 * remembered keys is bounded; once it is full further new keys are logged at DEBUG only.
 *
 * <p>Thread-safe. Touches no native library.
 */
public final class D3D12Log {
    private static final Logger LOGGER = LoggerFactory.getLogger("Sodium/D3D12");
    private static final int MAX_ONCE_KEYS = 512;
    private static final Set<String> ONCE_KEYS = ConcurrentHashMap.newKeySet();

    private D3D12Log() {
    }

    public static Logger logger() {
        return LOGGER;
    }

    public static void debug(String format, Object... args) {
        LOGGER.debug(format, args);
    }

    public static void info(String format, Object... args) {
        LOGGER.info(format, args);
    }

    public static void warn(String format, Object... args) {
        LOGGER.warn(format, args);
    }

    public static void error(String format, Object... args) {
        LOGGER.error(format, args);
    }

    /**
     * Logs at WARN the first time {@code key} is seen, never again.
     *
     * @param key a bounded-cardinality identifier of the event, e.g. {@code "missing-uniform:" + pipelineName + ":" + slot}
     */
    public static void warnOnce(String key, String format, Object... args) {
        if (ONCE_KEYS.size() >= MAX_ONCE_KEYS) {
            if (!ONCE_KEYS.contains(key)) {
                LOGGER.debug(format, args);
            }

            return;
        }

        if (ONCE_KEYS.add(key)) {
            LOGGER.warn(format, args);
        }
    }

    /** Forgets all keys passed to {@link #warnOnce}; for tests. */
    public static void resetOnceKeys() {
        ONCE_KEYS.clear();
    }
}
