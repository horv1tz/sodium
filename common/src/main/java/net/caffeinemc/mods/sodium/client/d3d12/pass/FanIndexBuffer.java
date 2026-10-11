// Owner: WP3 (pipelines + render pass) - implemented
package net.caffeinemc.mods.sodium.client.d3d12.pass;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import net.caffeinemc.mods.sodium.client.d3d12.core.D3D12Log;
import net.caffeinemc.mods.sodium.client.d3d12.core.DeviceContext;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12Resource;
import net.caffeinemc.mods.sodium.client.d3d12.resource.D3D12Buffer;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Backend-owned static index buffer that emulates {@code TRIANGLE_FAN} (D3D12 has no fan topology; spec 02 section 7.5):
 * an UPLOAD buffer ({@code GENERIC_READ} forever, untracked) of {@code R32_UINT} indices holding {@code (0, k, k+1)} for
 * {@code k = 1, 2, ...}, grown by doubling (the old buffer is released through {@code ctx.defer}).
 *
 * <p>Used by {@code PassBinder}: {@code draw(n, inst, first, firstInst)} on a fan pipeline binds this buffer and issues
 * {@code DrawIndexedInstanced((n - 2) * 3, inst, 0, first, firstInst)}; {@code drawIndexed(count, inst, firstIndex,
 * vertexOffset, firstInst)} assumes the <em>identity</em> index buffer the game uses for fans and issues
 * {@code DrawIndexedInstanced((count - 2) * 3, inst, 0, firstIndex + vertexOffset, firstInst)} (documented limit: not
 * valid for non-identity index buffers); {@code n < 3} draws nothing; indirect and multi draws on fan pipelines throw
 * {@code UnsupportedOperationException}. After a fan draw the user's index buffer must be re-bound before the next
 * indexed draw (the binder sets its {@code dirtyIb}).
 *
 * <p>Render thread only.
 */
final class FanIndexBuffer implements AutoCloseable {
    /** Creates the host-visible index buffer; replaceable in tests. */
    @FunctionalInterface
    interface BufferSource {
        D3D12Buffer create(DeviceContext ctx, long sizeInBytes);
    }

    /** First capacity, in triangles (the sky draws 8 and 16). */
    private static final int INITIAL_TRIANGLES = 256;
    private static final int INDEX_BYTES = 4;

    private final DeviceContext ctx;
    private final BufferSource source;
    private D3D12Buffer buffer;
    private int capacityTriangles;

    FanIndexBuffer(DeviceContext ctx) {
        this(ctx, FanIndexBuffer::createBuffer);
    }

    FanIndexBuffer(DeviceContext ctx, BufferSource source) {
        this.ctx = ctx;
        this.source = source;
    }

    private static D3D12Buffer createBuffer(DeviceContext ctx, long sizeInBytes) {
        return ctx.resources().createBuffer(() -> "Sodium Direct3D 12 triangle fan indices",
                GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_INDEX, sizeInBytes);
    }

    /**
     * Makes sure the buffer holds the fan for {@code vertexCount} vertices ({@code (vertexCount - 2) * 3} indices). Grows by
     * doubling: the new buffer is filled completely before it replaces the old one, whose release is deferred by
     * {@code close()}, so a frame in flight keeps reading the old indices (their content never changes anyway).
     *
     * @throws IllegalArgumentException if {@code vertexCount < 3} or the fan would exceed the 32-bit index range
     */
    void ensure(int vertexCount) {
        if (vertexCount < 3) {
            throw new IllegalArgumentException("A triangle fan needs at least 3 vertices: " + vertexCount);
        }

        int triangles = vertexCount - 2;

        if (this.buffer != null && this.capacityTriangles >= triangles) {
            return;
        }

        long capacity = Math.max(INITIAL_TRIANGLES, this.capacityTriangles);

        while (capacity < triangles) {
            capacity <<= 1;
        }

        long bytes = capacity * 3L * INDEX_BYTES;

        if (bytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("A triangle fan of " + vertexCount + " vertices is too large");
        }

        D3D12Buffer created = this.source.create(this.ctx, bytes);
        MemorySegment mapped = created.mapped();

        if (mapped == null) {
            created.close();

            throw new IllegalStateException("The fan index buffer is not host visible");
        }

        // triangle t of the fan: (0, t + 1, t + 2)
        long offset = 0L;

        for (int t = 0; t < capacity; t++) {
            mapped.set(ValueLayout.JAVA_INT_UNALIGNED, offset, 0);
            mapped.set(ValueLayout.JAVA_INT_UNALIGNED, offset + INDEX_BYTES, t + 1);
            mapped.set(ValueLayout.JAVA_INT_UNALIGNED, offset + 2L * INDEX_BYTES, t + 2);
            offset += 3L * INDEX_BYTES;
        }

        D3D12Buffer previous = this.buffer;

        this.buffer = created;
        this.capacityTriangles = (int) capacity;

        if (previous != null) {
            previous.close();
        }
    }

    D3D12Resource resource() {
        return this.current().resource();
    }

    long gpuVa() {
        return this.current().gpuVa();
    }

    /** @return {@code SizeInBytes} of the index buffer view */
    int sizeInBytes() {
        this.current();

        return this.capacityTriangles * 3 * INDEX_BYTES;
    }

    private D3D12Buffer current() {
        D3D12Buffer current = this.buffer;

        if (current == null) {
            throw new IllegalStateException("The fan index buffer was not created yet (call ensure first)");
        }

        return current;
    }

    DeviceContext context() {
        return this.ctx;
    }

    /** Idempotent; the buffer's native release is deferred by its own {@code close()}. */
    @Override
    public void close() {
        D3D12Buffer released = this.buffer;

        this.buffer = null;
        this.capacityTriangles = 0;

        if (released != null) {
            try {
                released.close();
            } catch (RuntimeException e) {
                D3D12Log.error("Failed to close the fan index buffer", e);
            }
        }
    }
}
