// Owner: WP1 (device core) - implemented; WP1 owner adds tests
package net.caffeinemc.mods.sodium.client.d3d12.core;

import com.mojang.renderpearl.api.device.GpuDeviceLossException;
import com.mojang.renderpearl.api.device.GpuOutOfMemoryException;
import net.caffeinemc.mods.sodium.client.d3d12.interop.D3D12Exception;
import net.caffeinemc.mods.sodium.client.d3d12.interop.Hr;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12Device;
import org.jspecify.annotations.Nullable;

/**
 * The single error policy of the backend (proposal A section 2.k, invariants I8/I9).
 *
 * <p>Translation table:
 * <ul>
 *     <li>device removed / hung / reset / driver internal error ({@code Hr.isDeviceLoss}), a completed fence value of
 *     {@code -1}, a wait timeout while {@code GetDeviceRemovedReason() != S_OK}: {@link GpuDeviceLossException} with the
 *     removed reason and the last debug-layer messages; the policy becomes FAILED.</li>
 *     <li>{@code E_OUTOFMEMORY} in {@code createTexture} / {@code createBuffer} (both) and over-limit texture sizes:
 *     {@link GpuOutOfMemoryException} (nothing may leak, {@code MainTarget} retries with a smaller size).</li>
 *     <li>any other failing HRESULT: {@link IllegalStateException} naming the operation and the HRESULT.</li>
 *     <li>argument errors ({@link IllegalArgumentException}, {@link IllegalStateException}) pass through unchanged and
 *     never change the policy state: they are thrown before anything was mutated.</li>
 * </ul>
 *
 * <p><b>Sticky FAILED state:</b> a native failure <em>after</em> command-list or tracker mutation (a failed
 * {@code Close()}, {@code Reset()}, {@code Signal}, ...) cannot be repaired - the tracker and the recorded stream
 * disagree. {@link #fail} marks the policy failed; afterwards {@link #checkHealthy()} throws on every encoder, pass and
 * surface call, while {@code close()} paths keep working and never throw.
 *
 * <p>Translation happens at five choke points only: {@code submit()}, texture/buffer creation, {@code configure} and
 * {@code present}, {@code compilePipeline} (which never throws at all), and {@code createDevice}. Everywhere else the
 * wrapper's {@link D3D12Exception} propagates.
 *
 * <p>Threading: any thread (state is volatile).
 */
public final class ErrorPolicy {
    private final @Nullable D3D12Device device;
    private final boolean strict;
    private volatile DebugMessageSink debug = DebugMessageSink.disabled();
    private volatile @Nullable Throwable failure;

    /** @param device may be null in unit tests; {@code GetDeviceRemovedReason} then reads as {@code S_OK} */
    public ErrorPolicy(@Nullable D3D12Device device, boolean strict) {
        this.device = device;
        this.strict = strict;
    }

    /** Called once by {@code D3D12GpuDevice.create} after the sink exists. */
    public void attachDebug(DebugMessageSink sink) {
        this.debug = sink;
    }

    public boolean strict() {
        return this.strict;
    }

    public boolean isFailed() {
        return this.failure != null;
    }

    /** @throws IllegalStateException if an earlier native failure put the backend into the FAILED state */
    public void checkHealthy() {
        Throwable cause = this.failure;

        if (cause != null) {
            throw new IllegalStateException("The Direct3D 12 backend failed earlier and cannot continue: " + cause, cause);
        }
    }

    /**
     * Translates a throwable according to the table above. Never returns null. {@link Error}s are rethrown.
     *
     * @param op short description of the failing operation, e.g. {@code "ExecuteCommandLists"}
     */
    public RuntimeException translate(Throwable t, String op) {
        if (t instanceof Error error) {
            throw error;
        }

        if (t instanceof GpuDeviceLossException || t instanceof GpuOutOfMemoryException) {
            return (RuntimeException) t;
        }

        if (t instanceof D3D12Exception e) {
            if (e.isDeviceLoss()) {
                return this.deviceLoss(op, e.getHresult(), e);
            }

            return new IllegalStateException(op + " failed: " + e.getMessage(), e);
        }

        if (t instanceof RuntimeException runtime) {
            return runtime;
        }

        return new IllegalStateException(op + " failed: " + t, t);
    }

    /**
     * Marks the backend FAILED and returns the exception to throw (translated like {@link #translate}).
     * The first failure wins.
     */
    public RuntimeException fail(Throwable t) {
        RuntimeException translated = this.translate(t, "Direct3D 12 command");

        if (this.failure == null) {
            this.failure = translated;
            D3D12Log.error("The Direct3D 12 backend entered the FAILED state", translated);
        }

        return translated;
    }

    /** @return a {@link GpuOutOfMemoryException} for creation paths; the caller guarantees nothing leaked */
    public GpuOutOfMemoryException outOfMemory(String what, @Nullable Throwable cause) {
        GpuOutOfMemoryException exception = new GpuOutOfMemoryException("Out of memory while " + what);

        if (cause != null) {
            exception.initCause(cause);
        }

        return exception;
    }

    /**
     * Builds the exception for a wait that exceeded {@code submit()}'s cap: device loss if the device was removed,
     * otherwise an {@link IllegalStateException}. A device loss makes the policy FAILED (it is sticky, like every other
     * device loss); the plain timeout does not change the state by itself - the caller decides with {@link #fail}.
     */
    public RuntimeException timeout(String what) {
        int reason = this.removedReason();

        if (reason != Hr.S_OK) {
            return this.deviceLoss(what + " timed out", reason, null);
        }

        return new IllegalStateException(what + ": timeout reached while waiting for the GPU. Last debug messages: "
                + this.debug.recentMessages());
    }

    /**
     * Eager device-loss detection: call after every blocking wait, every {@code Present} and whenever a fence reports
     * {@code -1}.
     *
     * @throws GpuDeviceLossException if {@code GetDeviceRemovedReason() != S_OK}
     */
    public void checkDeviceRemoved(String op) {
        int reason = this.removedReason();

        if (reason != Hr.S_OK) {
            throw this.deviceLoss(op, reason, null);
        }
    }

    /**
     * The tolerance policy for caller bugs the frontend does not catch in production (missing uniform, unset vertex
     * buffer, ...): strict mode throws, otherwise the event is logged once per {@code key} and the caller substitutes
     * a safe value or skips the draw.
     */
    public void tolerate(String key, String message) {
        if (this.strict) {
            throw new IllegalStateException(message);
        }

        D3D12Log.warnOnce(key, "{}", message);
    }

    private int removedReason() {
        if (this.device == null) {
            return Hr.S_OK;
        }

        try {
            return this.device.getDeviceRemovedReason();
        } catch (RuntimeException e) {
            return Hr.DXGI_ERROR_DEVICE_REMOVED;
        }
    }

    private GpuDeviceLossException deviceLoss(String op, int hresult, @Nullable Throwable cause) {
        int reason = this.removedReason();
        StringBuilder message = new StringBuilder(op).append(": Direct3D 12 device lost (HRESULT 0x")
                .append(Integer.toHexString(hresult)).append(", removed reason 0x").append(Integer.toHexString(reason)).append(')');

        for (String line : this.debug.recentMessages()) {
            message.append('\n').append(line);
        }

        GpuDeviceLossException exception = new GpuDeviceLossException(message.toString());

        if (cause != null) {
            exception.initCause(cause);
        }

        if (this.failure == null) {
            this.failure = exception;
        }

        return exception;
    }
}
