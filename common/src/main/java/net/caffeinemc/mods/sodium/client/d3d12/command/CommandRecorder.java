// Owner: WP2 (commands + transient memory) - implemented
package net.caffeinemc.mods.sodium.client.d3d12.command;

import net.caffeinemc.mods.sodium.client.d3d12.core.D3D12Log;
import net.caffeinemc.mods.sodium.client.d3d12.core.DeviceContext;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Enums;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12CommandAllocator;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12Device;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12GraphicsCommandList;
import org.jspecify.annotations.Nullable;

import java.lang.invoke.VarHandle;
import java.util.function.IntConsumer;

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
 * <p>Failure hygiene: {@link #initialize()} leaves nothing behind when it fails. {@link #close()} and
 * {@link #discardFrame()} never throw. A native failure in {@link #beginFrame()} / {@link #endFrameAndExecute()} is
 * propagated to the caller ({@code D3D12CommandEncoder.submit}, which puts the backend into the FAILED state).
 *
 * <p>Threading: render thread.
 */
public final class CommandRecorder implements AutoCloseable {
    private final DeviceContext ctx;
    private final @Nullable IntConsumer frameStartHook;

    private D3D12CommandAllocator @Nullable [] allocators;
    private @Nullable D3D12GraphicsCommandList list;
    /** True while {@link #list} is in the recording state (between create/Reset and Close). */
    private boolean recording;
    private boolean closed;

    public CommandRecorder(DeviceContext ctx) {
        this(ctx, null);
    }

    /**
     * Test seam: {@code frameStartHook} replaces {@code ctx.descriptors().beginFrame(slot)} (so that the recorder can be
     * exercised without descriptor services); {@code null} = the production behaviour.
     */
    public CommandRecorder(DeviceContext ctx, @Nullable IntConsumer frameStartHook) {
        this.ctx = ctx;
        this.frameStartHook = frameStartHook;
    }

    /**
     * Creates the allocators and the list (recording, bound to the allocator of {@code FrameClock.FIRST_FRAME}). The
     * descriptor rings are in their initial state for that frame (slot 0), so no {@code beginFrame} is needed for it.
     *
     * @throws IllegalStateException if called twice
     */
    public void initialize() {
        if (this.list != null || this.closed) {
            throw new IllegalStateException("CommandRecorder already initialised or closed");
        }

        D3D12Device device = this.ctx.d3d();
        D3D12CommandAllocator first = null;
        D3D12CommandAllocator second = null;
        D3D12GraphicsCommandList created = null;

        try {
            first = device.createCommandAllocator(Enums.CommandListType.DIRECT);
            second = device.createCommandAllocator(Enums.CommandListType.DIRECT);

            D3D12CommandAllocator[] both = {first, second};

            created = device.createCommandList(Enums.CommandListType.DIRECT, both[this.ctx.clock().slot()]);

            if (this.ctx.labels()) {
                first.setName("Sodium frame allocator 0");
                second.setName("Sodium frame allocator 1");
                created.setName("Sodium frame command list");
            }

            this.allocators = both;
            this.list = created;
            this.recording = true;
        } catch (Throwable t) {
            releaseQuietly(created);
            releaseQuietly(second);
            releaseQuietly(first);

            throw t;
        }
    }

    /** @return the open list of the frame being recorded */
    public D3D12GraphicsCommandList list() {
        D3D12GraphicsCommandList current = this.list;

        if (current == null || !this.recording) {
            throw new IllegalStateException(this.closed ? "The command recorder is closed" : "No command list is open (not initialised, or between frames)");
        }

        return current;
    }

    /** @return {@code clock.current() & 1}: the slot of the frame being recorded */
    public int slot() {
        return this.ctx.clock().slot();
    }

    /** @return true while the list of the current frame is open for recording */
    public boolean isRecording() {
        return this.recording;
    }

    /**
     * Starts the next frame: resets the allocator of the new slot, resets the list on it and rewinds the descriptor rings
     * ({@code ctx.descriptors().beginFrame(slot)}). Precondition: the frame that used this slot completed.
     *
     * @throws IllegalStateException if the previous frame was not ended with {@link #endFrameAndExecute()}
     */
    public void beginFrame() {
        D3D12GraphicsCommandList current = this.list;
        D3D12CommandAllocator[] pair = this.allocators;

        if (current == null || pair == null || this.closed) {
            throw new IllegalStateException("The command recorder is not initialised or already closed");
        }

        if (this.recording) {
            throw new IllegalStateException("beginFrame() while the previous frame is still open");
        }

        int slot = this.ctx.clock().slot();
        D3D12CommandAllocator allocator = pair[slot];

        allocator.reset();
        current.reset(allocator);
        this.recording = true;

        if (this.frameStartHook != null) {
            this.frameStartHook.accept(slot);
        } else {
            this.ctx.descriptors().beginFrame(slot);
        }
    }

    /**
     * Ends the frame: {@code tracker.flush(list)} (trailing barriers), {@code VarHandle.fullFence()} (write-combined
     * stores visible), {@code closeList()}, {@code queue.executeCommandLists(list)}. Does <b>not</b> signal; the encoder
     * calls {@code FrameClock.signalAndAdvance} next. A failure here puts the backend into the FAILED state.
     */
    public void endFrameAndExecute() {
        D3D12GraphicsCommandList current = this.list();

        this.ctx.tracker().flush(current);

        // CPU writes into UPLOAD memory (write-combined) must be globally visible before the GPU may read them
        VarHandle.fullFence();

        current.closeList();
        this.recording = false;

        this.ctx.queue().executeCommandLists(current);
    }

    /** Shutdown: closes the open list without executing it (the unsubmitted frame is dropped, like Vulkan). Never throws. */
    public void discardFrame() {
        D3D12GraphicsCommandList current = this.list;

        try {
            this.ctx.tracker().discardPending();
        } catch (Throwable t) {
            D3D12Log.error("Failed to discard the pending resource barriers", t);
        }

        if (current != null && this.recording) {
            this.recording = false;

            try {
                current.closeList();
            } catch (Throwable t) {
                D3D12Log.error("Failed to close the unsubmitted command list", t);
            }
        }
    }

    /** Releases the list and both allocators. The GPU must be idle (called by {@code D3D12CommandEncoder.shutdown()}). Idempotent, never throws. */
    @Override
    public void close() {
        if (this.closed) {
            return;
        }

        this.closed = true;
        this.recording = false;

        D3D12GraphicsCommandList current = this.list;
        D3D12CommandAllocator[] pair = this.allocators;

        this.list = null;
        this.allocators = null;

        releaseQuietly(current);

        if (pair != null) {
            releaseQuietly(pair[1]);
            releaseQuietly(pair[0]);
        }
    }

    private static void releaseQuietly(@Nullable AutoCloseable object) {
        if (object != null) {
            try {
                object.close();
            } catch (Throwable t) {
                D3D12Log.error("Failed to release a command recorder object", t);
            }
        }
    }
}
