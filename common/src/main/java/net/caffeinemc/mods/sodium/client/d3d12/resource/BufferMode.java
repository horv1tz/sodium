// Owner: WP1 (device core) - fully implemented
package net.caffeinemc.mods.sodium.client.d3d12.resource;

/**
 * Where a buffer lives and how its state is handled. Chosen once at creation by {@link BufferModes#select}.
 *
 * <table>
 *   <caption>Modes</caption>
 *   <tr><th>mode</th><th>heap</th><th>resting state</th><th>tracked</th><th>CPU mapping</th></tr>
 *   <tr><td>DEFAULT</td><td>DEFAULT</td><td>GENERIC_READ (CD after creation with data)</td><td>yes</td><td>none</td></tr>
 *   <tr><td>UPLOAD</td><td>UPLOAD</td><td>GENERIC_READ forever</td><td>no</td><td>persistent, write-combined</td></tr>
 *   <tr><td>READBACK</td><td>READBACK</td><td>COPY_DEST forever</td><td>no</td><td>persistent, cached</td></tr>
 *   <tr><td>TRANSIENT_UPLOAD</td><td>UPLOAD block</td><td>GENERIC_READ forever</td><td>no</td><td>persistent (block)</td></tr>
 *   <tr><td>TRANSIENT_DEFAULT</td><td>DEFAULT block</td><td>GENERIC_READ / CD</td><td>yes</td><td>none</td></tr>
 * </table>
 */
public enum BufferMode {
    DEFAULT(true, false, false),
    UPLOAD(false, true, false),
    READBACK(false, true, false),
    TRANSIENT_UPLOAD(false, true, true),
    TRANSIENT_DEFAULT(true, false, true);

    private final boolean tracked;
    private final boolean hostVisible;
    private final boolean transientBlock;

    BufferMode(boolean tracked, boolean hostVisible, boolean transientBlock) {
        this.tracked = tracked;
        this.hostVisible = hostVisible;
        this.transientBlock = transientBlock;
    }

    /** @return true if the buffer has a {@code TrackedResource} (explicit state transitions) */
    public boolean tracked() {
        return this.tracked;
    }

    /** @return true if the CPU can address the buffer's memory ({@code mapped() != null}) */
    public boolean hostVisible() {
        return this.hostVisible;
    }

    /** @return true for blocks handed out by {@code TransientMemory} */
    public boolean isTransient() {
        return this.transientBlock;
    }

    /** @return true if a GPU copy may write into the buffer (DEFAULT state tracked, or the permanent COPY_DEST readback) */
    public boolean gpuWritable() {
        return this == DEFAULT || this == READBACK || this == TRANSIENT_DEFAULT;
    }
}
