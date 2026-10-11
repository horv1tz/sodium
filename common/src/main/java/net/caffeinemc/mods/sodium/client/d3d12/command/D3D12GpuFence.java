// Owner: WP2 (commands + transient memory) - fully implemented (delegates to FrameClock)
package net.caffeinemc.mods.sodium.client.d3d12.command;

import com.mojang.renderpearl.api.commands.GpuFence;
import net.caffeinemc.mods.sodium.client.d3d12.core.DeviceContext;

/**
 * A {@code GpuFence}: "the frame in which I was created has completed on the GPU". It holds no native resource - only
 * the frame index - so {@code close()} is trivial and idempotent.
 *
 * <p><b>Not registered in {@code ObjectRegistry}</b>: it owns no native state, so there is nothing to force-close at
 * shutdown, and Sodium's ring buffers and staging buffers create one or more fences every frame and many are never
 * closed by their consumers - a registry entry per fence would grow without bound (strong references) and make the shutdown
 * snapshot huge.
 *
 * <p>Semantics (spec 01 section 9.1), implemented by {@code FrameClock.await}: complete -> {@code true} for any timeout
 * and forever after (cached); a fence of the not-yet-submitted frame returns {@code false} for timeout 0 and throws
 * {@link IllegalStateException} for any other timeout; {@code NO_TIMEOUT} (-1) waits forever (device removal is detected,
 * not hung on); positive nanoseconds are converted to milliseconds rounding up. After {@code close()} the fence reports
 * {@code true}.
 */
public final class D3D12GpuFence implements GpuFence {
    private final DeviceContext ctx;
    private final long frame;
    private volatile boolean done;

    public D3D12GpuFence(DeviceContext ctx) {
        this.ctx = ctx;
        this.frame = ctx.clock().current();
    }

    /** @return the frame index this fence waits for */
    public long frame() {
        return this.frame;
    }

    @Override
    public boolean awaitCompletion(long timeoutNS) {
        if (this.done) {
            return true;
        }

        if (this.ctx.clock().await(this.frame, timeoutNS)) {
            this.done = true;
        }

        return this.done;
    }

    @Override
    public void close() {
        this.done = true;
    }
}
