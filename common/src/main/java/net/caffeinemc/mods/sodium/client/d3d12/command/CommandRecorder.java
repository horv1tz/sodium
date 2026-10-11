// Owner: WP2 (commands + transient memory)
package net.caffeinemc.mods.sodium.client.d3d12.command;

import net.caffeinemc.mods.sodium.client.d3d12.core.DeviceContext;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12GraphicsCommandList;

/**
 * Owner of the one command list and the two command allocators (invariants I1 and I6).
 *
 * <p>Model: one {@code ID3D12GraphicsCommandList} object, reused for the whole life of the device, plus one allocator
 * per frame slot ({@code slot = frame & 1}). Frame {@code f} records into the list reset on allocator {@code f & 1}; at
 * the end of the frame the list is closed and executed (the only {@code ExecuteCommandLists} per frame); a list may be
 * {@code Reset} right after {@code ExecuteCommandLists} as long as it is bound to the <em>other</em> allocator. An
 * allocator is reset only after the frame that last used it completed - {@code submit()} guarantees this by waiting for
 * frame {@code f - 1} before calling {@link #beginFrame()}.
 *
 * <p>A freshly reset list has no state at all (no heaps, root signature, pipeline, targets, viewport...). The render-pass
 * binder re-establishes everything at pass begin (invariant I5); commands outside passes (copies, clears) need none.
 *
 * <p>This is the only class that calls {@code closeList()}, {@code reset()} and {@code executeCommandLists}.
 *
 * <p>Threading: render thread.
 */
public final class CommandRecorder implements AutoCloseable {
    private final DeviceContext ctx;

    public CommandRecorder(DeviceContext ctx) {
        this.ctx = ctx;
    }

    /** Creates the allocators and the list (recording, bound to the allocator of {@code FrameClock.FIRST_FRAME}). */
    public void initialize() {
        throw new UnsupportedOperationException("WP2: 2 DIRECT allocators, 1 list, open for the first frame, labels");
    }

    /** @return the open list of the frame being recorded */
    public D3D12GraphicsCommandList list() {
        throw new UnsupportedOperationException("WP2");
    }

    /** @return {@code clock.current() & 1}: the slot of the frame being recorded */
    public int slot() {
        return this.ctx.clock().slot();
    }

    /**
     * Starts the next frame: resets the allocator of the new slot, resets the list on it and rewinds the descriptor rings
     * ({@code ctx.descriptors().beginFrame(slot)}). Precondition: the frame that used this slot completed.
     */
    public void beginFrame() {
        throw new UnsupportedOperationException("WP2: allocator.reset(), list.reset(allocator), descriptors.beginFrame(slot)");
    }

    /**
     * Ends the frame: {@code tracker.flush(list)} (trailing barriers), {@code VarHandle.fullFence()} (write-combined
     * stores visible), {@code closeList()}, {@code queue.executeCommandLists(list)}. Does <b>not</b> signal; the encoder
     * calls {@code FrameClock.signalAndAdvance} next. A failure here puts the backend into the FAILED state.
     */
    public void endFrameAndExecute() {
        throw new UnsupportedOperationException("WP2");
    }

    /** Shutdown: closes the open list without executing it (the unsubmitted frame is dropped, like Vulkan). */
    public void discardFrame() {
        throw new UnsupportedOperationException("WP2: tracker.discardPending(), closeList()");
    }

    @Override
    public void close() {
        throw new UnsupportedOperationException("WP2: release list and allocators (GPU idle)");
    }
}
