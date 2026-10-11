// Owner: WP1 (device core) - common base of normal and transient buffers; map() fully implemented
package net.caffeinemc.mods.sodium.client.d3d12.resource;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.backend.common.BaseGpuBuffer;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12Resource;
import net.caffeinemc.mods.sodium.client.d3d12.state.TrackedResource;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The one buffer type every other package works with. Every {@code GpuBuffer} the frontend hands back to the backend
 * (vertex/index buffers, UBO slices, indirect arguments, copy sources and destinations) is a {@code D3D12Buffer}:
 * either a {@link D3D12DeviceBuffer} (what {@code createBuffer} returns) or a
 * {@code memory.D3D12TransientBuffer} (what {@code TransientMemory} slices point to). The frontend extends
 * {@link BaseGpuBuffer} for strict-mode checks, so the base class is mandatory.
 *
 * <p><b>What other packages may rely on</b>
 * <ul>
 *     <li>{@link #resource()} / {@link #gpuVa()}: the native resource that GPU commands must use and its GPU virtual
 *     address; for transient blocks the resource of the block, so {@code gpuVa() + slice.offset()} is the address of a
 *     slice. Committed buffers are 64 KiB aligned, hence a 256-aligned slice offset gives a 256-aligned address.</li>
 *     <li>{@link #tracked()}: non-null only for {@link BufferMode#DEFAULT} and {@link BufferMode#TRANSIENT_DEFAULT};
 *     such a buffer must be passed through {@code ResourceStateTracker.require(.., GR)} before every GPU read and
 *     {@code require(.., CD)} before every GPU write. {@code null} means "permanently in a legal state" (UPLOAD
 *     GENERIC_READ, READBACK COPY_DEST).</li>
 *     <li>{@link #mapped()}: base of the persistent CPU mapping of the <em>whole</em> resource for host-visible modes;
 *     slices add their offset. {@code null} for device-local modes.</li>
 *     <li>{@link #isUsable()}: false after {@code close()}, after the device closed and, for transient buffers, once the
 *     frame of the allocation has been submitted. Commands must refuse (or skip, see the tolerance policy) unusable
 *     buffers: the native resource may be released at any later {@code submit()}.</li>
 * </ul>
 *
 * <p>Lifetime: {@code close()} is idempotent, never throws, marks the buffer closed immediately and defers the native
 * release until the frame that recorded the close has completed ({@code DeviceContext.defer}). Commands recorded earlier
 * in the same frame stay valid. Mapped memory is wrapped in a per-buffer {@code Arena} that is closed in the deferred
 * destroy <em>before</em> the COM release, so a stale {@code ByteBuffer} throws instead of crashing (raw
 * {@code memAddress} writes are not protected; documented limit).
 *
 * <p><b>CPU reads of READBACK buffers.</b> The mapping of a READBACK buffer is persistent while the GPU writes into the
 * resource, and the D3D12 memory model expects the CPU to {@code Map(read range)} <em>after</em> the GPU write to obtain a
 * coherent view on platforms that do not snoop CPU caches. {@link #map} therefore brackets every read view of a READBACK
 * buffer with the hooks {@link #beginCpuRead} / {@link #endCpuRead} (implemented by {@code D3D12DeviceBuffer} as an extra,
 * reference-counted {@code Map(0, readRange)} / {@code Unmap(0, emptyRange)}; the persistent mapping stays valid). The
 * frontend maps inside the read-back callback, i.e. after the fence completed, which is exactly "immediately before the
 * CPU read". The debug-layer messages that persistent READBACK mapping provokes on purpose are denied in
 * {@code DebugMessageSink}.
 *
 * <p>Threading: render thread; {@code close()} may come from any thread; {@code isClosed()} is a volatile read.
 */
public abstract class D3D12Buffer extends BaseGpuBuffer {
    private final AtomicInteger openViews = new AtomicInteger();

    protected D3D12Buffer(int usage, long size) {
        super(usage, size);
    }

    /** @return the native resource GPU commands must reference (never null while {@link #isUsable()}) */
    public abstract D3D12Resource resource();

    /** @return {@code resource().getGPUVirtualAddress()} (cached) */
    public abstract long gpuVa();

    public abstract BufferMode mode();

    /** @return the state record for tracked modes, null for UPLOAD / READBACK / TRANSIENT_UPLOAD */
    public abstract @Nullable TrackedResource tracked();

    /** @return base of the persistent mapping of the whole resource, null for device-local modes */
    public abstract @Nullable MemorySegment mapped();

    /** @return not closed, device alive and (transient) allocated in the current frame */
    public abstract boolean isUsable();

    /**
     * Hook: a read view of {@code [offset, offset + length)} of a READBACK buffer is about to be handed out; the
     * implementation issues {@code Map(0, readRange)} on the resource. Default: nothing (UPLOAD / transient buffers).
     */
    protected void beginCpuRead(long offset, long length) {
    }

    /**
     * Hook: the matching read view was closed; the implementation issues {@code Unmap(0, emptyRange)} unless the resource
     * was destroyed in the meantime (the view may outlive the buffer; then the arena is closed and there is nothing to
     * unmap). Never throws. Default: nothing.
     */
    protected void endCpuRead() {
    }

    /** @return the GPU virtual address of byte {@code offset} of this buffer */
    public final long gpuAddress(long offset) {
        return this.gpuVa() + offset;
    }

    /** @return the number of {@link GpuBufferSlice.MappedView}s that have not been closed yet */
    public final int openViewCount() {
        return this.openViews.get();
    }

    @Override
    public void checkCanBeUsed() {
        if (!this.isUsable()) {
            throw new IllegalStateException("Buffer is closed or no longer valid");
        }
    }

    /**
     * Validation order of the Vulkan reference (spec 03 section 7.2): closed -> ISE; neither read nor write -> IAE;
     * read without {@code MAP_READ} -> ISE; write without {@code MAP_WRITE} -> ISE; range / length / negative -> IAE.
     * The returned buffer is a direct, <b>native-order</b> {@link ByteBuffer} (position 0, limit == capacity ==
     * {@code length}) aliasing the persistent mapping (game code uses absolute multi-byte accessors, and an FFM
     * {@code asByteBuffer()} is big-endian by default). Several views may be open at once; {@code onClose} is idempotent.
     * No GPU synchronisation is performed.
     */
    @Override
    public GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) {
        if (this.isClosed()) {
            throw new IllegalStateException("Buffer already closed");
        }

        if (!read && !write) {
            throw new IllegalArgumentException("At least one of read / write must be true");
        }

        if (read && (this.usage() & GpuBuffer.USAGE_MAP_READ) == 0) {
            throw new IllegalStateException("Buffer is not readable");
        }

        if (write && (this.usage() & GpuBuffer.USAGE_MAP_WRITE) == 0) {
            throw new IllegalStateException("Buffer is not writable");
        }

        if (offset < 0L || length < 0L || offset > this.size() - length) {
            throw new IllegalArgumentException("Mapped range at offset " + offset + " with length " + length + " is outside the buffer of size " + this.size());
        }

        if (length > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Cannot map more than Integer.MAX_VALUE bytes at once: " + length);
        }

        MemorySegment base = this.mapped();

        if (base == null) {
            throw new IllegalStateException("Buffer is not CPU accessible");
        }

        ByteBuffer data = base.asSlice(offset, length).asByteBuffer().order(ByteOrder.nativeOrder());
        AtomicBoolean closed = new AtomicBoolean();
        boolean cpuRead = read && this.mode() == BufferMode.READBACK;

        if (cpuRead) {
            this.beginCpuRead(offset, length);
        }

        this.openViews.incrementAndGet();

        return new GpuBufferSlice.MappedView(this.slice(offset, length), data, () -> {
            if (closed.compareAndSet(false, true)) {
                this.openViews.decrementAndGet();

                if (cpuRead) {
                    this.endCpuRead();
                }
            }
        });
    }
}
