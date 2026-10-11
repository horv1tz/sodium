// Owner: WP3 (pipelines + render pass)
package net.caffeinemc.mods.sodium.client.d3d12.pass;

import net.caffeinemc.mods.sodium.client.d3d12.core.DeviceContext;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12Resource;

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
    private final DeviceContext ctx;

    FanIndexBuffer(DeviceContext ctx) {
        this.ctx = ctx;
    }

    /** Makes sure the buffer holds the fan for {@code vertexCount} vertices ({@code (vertexCount - 2) * 3} indices). */
    void ensure(int vertexCount) {
        throw new UnsupportedOperationException("WP3: grow by doubling, fill (0, k, k+1) through the persistent mapping");
    }

    D3D12Resource resource() {
        throw new UnsupportedOperationException("WP3");
    }

    long gpuVa() {
        throw new UnsupportedOperationException("WP3");
    }

    /** @return {@code SizeInBytes} of the index buffer view */
    int sizeInBytes() {
        throw new UnsupportedOperationException("WP3");
    }

    DeviceContext context() {
        return this.ctx;
    }

    @Override
    public void close() {
        throw new UnsupportedOperationException("WP3");
    }
}
