// Owner: WP2 (commands + transient memory)
package net.caffeinemc.mods.sodium.client.d3d12.command;

import com.mojang.renderpearl.api.commands.GpuQueryPool;
import net.caffeinemc.mods.sodium.client.d3d12.core.DeviceContext;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12GraphicsCommandList;

import java.util.OptionalLong;

/**
 * Timestamp query pool (spec 01 section 9.2): an {@code ID3D12QueryHeap(TIMESTAMP)} of {@code size} slots plus a
 * persistently mapped READBACK buffer that receives the resolved values, and a {@code long[] writtenInFrame} per slot.
 *
 * <ul>
 *     <li>{@link #write}: bounds check {@code 0 <= index < size} in the backend (the frontend's check is off by one:
 *     {@code index == size} reaches us -> {@link IndexOutOfBoundsException}), {@code EndQuery(TIMESTAMP, index)}, then
 *     {@code ResolveQueryData(index, 1)} into the readback buffer at {@code index * 8} immediately, so a later rewrite
 *     cannot race an earlier resolve; the slot's {@code writtenInFrame} becomes the current frame. The readback buffer is
 *     permanently {@code COPY_DEST}: no barrier.</li>
 *     <li>{@code getValue}: non-blocking; empty until {@code writtenInFrame <= clock.completed()} (never written, or a
 *     rewrite is still pending); otherwise the raw tick value, read between {@code resource.map(0, readRange, ..)} and
 *     {@code unmap(0, emptyRange)} (an extra reference-counted Map; the persistent mapping stays) so that the CPU view is
 *     coherent after the GPU write on platforms that do not snoop CPU caches. {@code getValues(i, n)}:
 *     {@link IndexOutOfBoundsException} if {@code i + n > size}; one Map/Unmap pair for the whole range.</li>
 *     <li>Registered with {@code ObjectRegistry}: the Tracy pool (size 1024) is never closed by the frontend and is
 *     released by the device. {@code close()} is idempotent and deferred.</li>
 * </ul>
 * Must work before the first frame (Tracy creates the pool in the {@code FrontendGpuDevice} constructor).
 */
public final class D3D12QueryPool implements GpuQueryPool {
    private final DeviceContext ctx;
    private final int size;

    public D3D12QueryPool(DeviceContext ctx, int size) {
        this.ctx = ctx;
        this.size = size;
    }

    @Override
    public int size() {
        return this.size;
    }

    /** Records a timestamp into slot {@code index} of the open list; used by the encoder and by passes. */
    public void write(D3D12GraphicsCommandList list, int index) {
        throw new UnsupportedOperationException("WP2: EndQuery + ResolveQueryData(index, 1), writtenInFrame[index] = current frame");
    }

    @Override
    public OptionalLong getValue(int index) {
        throw new UnsupportedOperationException("WP2");
    }

    @Override
    public OptionalLong[] getValues(int index, int count) {
        throw new UnsupportedOperationException("WP2");
    }

    @Override
    public void close() {
        throw new UnsupportedOperationException("WP2: idempotent, unregister, defer query heap + readback buffer release");
    }

    DeviceContext context() {
        return this.ctx;
    }
}
