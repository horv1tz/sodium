// Owner: WP2 (commands + transient memory)
package net.caffeinemc.mods.sodium.client.d3d12.command;

import net.caffeinemc.mods.sodium.client.d3d12.core.DeviceContext;
import org.jspecify.annotations.Nullable;

/**
 * Timing and delivery of read-back callbacks (spec 01 section 4.10, proposal A section 2.g).
 *
 * <p>Contract the callers (Screenshot, TracyFrameCapture, TextureUtil) rely on:
 * <ul>
 *     <li>the callback runs <b>exactly once</b>, on the render thread, never synchronously inside the copy call;</li>
 *     <li>it runs inside the {@code deferral.drain} of the {@code submit()} that follows the completion of the frame that
 *     recorded the copy, i.e. at least one frame later, <em>after</em> {@code beginFrame()} - so a callback may map, close
 *     buffers, create buffers with data, write textures and issue copies (the Vulkan backend cannot offer that);</li>
 *     <li>when it runs, the destination buffer already contains the tightly packed pixels (row 0 = texture row {@code y};
 *     {@code Screenshot} reads {@code getInt((x + y*width)*4)} in native byte order);</li>
 *     <li>entries added by a callback get the newer frame tag and run in a later drain;</li>
 *     <li>an exception thrown by a callback is logged; later callbacks still run;</li>
 *     <li>at shutdown, callbacks whose frame was <em>submitted</em> run after the GPU idle wait; callbacks recorded in the
 *     frame that was never submitted are <b>dropped</b> (their data would be garbage) - the entry checks
 *     {@code clock.completed() >= tag} before delivering and always releases its scratch.</li>
 * </ul>
 *
 * <p>Threading: render thread.
 */
public final class ReadbackSupport {
    private final DeviceContext ctx;

    public ReadbackSupport(DeviceContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Queues {@code callback} for delivery as described above.
     *
     * @param callback the frontend's callback
     * @param cleanup  releases transient resources of the copy (may be null); runs whether or not the callback is delivered
     */
    public void deliverAfterCompletion(Runnable callback, @Nullable Runnable cleanup) {
        throw new UnsupportedOperationException("WP2: ctx.deferral().add(ctx.clock().current(), entry) with the completed-check");
    }

    DeviceContext context() {
        return this.ctx;
    }
}
