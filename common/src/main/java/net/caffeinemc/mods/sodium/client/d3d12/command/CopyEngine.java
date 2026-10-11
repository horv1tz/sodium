// Owner: WP2 (commands + transient memory)
package net.caffeinemc.mods.sodium.client.d3d12.command;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import net.caffeinemc.mods.sodium.client.d3d12.core.DeviceContext;
import net.caffeinemc.mods.sodium.client.d3d12.resource.D3D12Buffer;

import java.nio.ByteBuffer;

/**
 * Every buffer and texture transfer (invariant I3: each method is "validate -> require states -> flush -> record").
 * Together with {@link ClearEngine} and the pass binder it is the only code that records copy commands.
 *
 * <h2>Buffer destinations (decision D2)</h2>
 * The mode of the destination decides the mechanism; the observable result is the same.
 * <table>
 *   <caption>writeToBuffer / copyToBuffer</caption>
 *   <tr><th>destination mode</th><th>writeToBuffer(data)</th><th>copyToBuffer(source)</th></tr>
 *   <tr><td>DEFAULT, TRANSIENT_DEFAULT</td>
 *       <td>UploadArena slice (memcpy), {@code require(dst, CD)}, {@code CopyBufferRegion}</td>
 *       <td>{@code require(dst, CD)}; source {@code GR} (no barrier when resting), {@code CopyBufferRegion}.
 *           <b>Same native resource</b> ({@code src.buffer().resource() == dst.buffer().resource()}, never Java object
 *           identity: every {@code TransientMemory} slice has its own {@code D3D12TransientBuffer} but slices of one block
 *           share one resource): bounce through a GPU scratch ({@code src -> scratch -> dst}) because a resource cannot be
 *           {@code COPY_SOURCE} and {@code COPY_DEST} at once; the ranges are guaranteed disjoint by the callers.</td></tr>
 *   <tr><td>READBACK</td><td>as DEFAULT (permanently {@code COPY_DEST}, untracked)</td>
 *       <td>as DEFAULT; a READBACK <em>source</em> is rejected ({@code IllegalArgumentException})</td></tr>
 *   <tr><td>UPLOAD, TRANSIENT_UPLOAD (never GPU-writable)</td>
 *       <td>CPU {@code memcpy} into the mapping <b>at record time</b></td>
 *       <td>CPU copy at record time if the source is host readable (UPLOAD / TRANSIENT_UPLOAD / READBACK; overlap-safe
 *           {@code MemorySegment.copy}); a device-local source throws {@code IllegalStateException("cannot copy
 *           GPU-only data into a host-visible MAP_WRITE buffer")} before anything is recorded</td></tr>
 * </table>
 * Why the CPU path is acceptable: a {@code MAP_WRITE} buffer is host-visible memory the GPU reads when it executes, so CPU
 * writes are "seen when the GPU executes the reading commands" (the Vulkan semantic, spec 01 section 14.3) - a record-time
 * write is indistinguishable for every known caller. The only GPU write into a {@code MAP_WRITE | COPY_DST} buffer in
 * Sodium or vanilla is {@code UniformBufferManager.resizeIfNeeded} (old buffer -> new buffer, both host-visible).
 * Known deviation: a draw recorded <em>before</em> a {@code writeToBuffer} into the same host-visible buffer in the same
 * frame sees the new data (spec 04 test T19 only holds for device-local buffers).
 *
 * <h2>Texture transfers</h2>
 * D3D12 needs {@code RowPitch % 256 == 0} and footprint offsets {@code % 512 == 0}; the API is tightly packed at any offset.
 * <ul>
 *     <li>{@code writeToTexture}: CPU repack of the tightly packed source into an UploadArena slice (pitch 256, offset
 *     512 aligned; bulk copy when already aligned; reads exactly {@code w*h*bpp} bytes; never retains the buffer), then
 *     {@code require(tex[mip + layer*mips], CD)} + {@code CopyTextureRegion}. Depth textures -> IAE.</li>
 *     <li>{@code copyBufferToTexture}: {@code start = slice.offset + (sx + sy*sw)*bpp}, {@code stride = sw*bpp};
 *     bounds-check {@code start + (ch-1)*stride + cw*bpp <= buffer.size()} (the frontend check is weaker). <b>Tier A</b>
 *     (direct): {@code stride % 256 == 0 && start % 512 == 0} -> one {@code CopyTextureRegion} straight from the source
 *     buffer. <b>Tier C</b> (otherwise): GPU scratch (DEFAULT, pitch 256, 512 aligned), one {@code CopyBufferRegion} per
 *     row, scratch {@code CD -> GR}, one {@code CopyTextureRegion}. The CPU never reads the source (it may be
 *     write-combined). A source in READBACK mode is rejected.</li>
 *     <li>{@code copyTextureToBuffer}: validated here (the frontend skips the 5-argument form: closed, {@code COPY_SRC},
 *     mip range, region inside the mip, destination {@code COPY_DST}, {@code offset + w*h*bpp <= size}, destination GPU
 *     writable - an UPLOAD destination is rejected). <b>Direct</b> when the destination is READBACK,
 *     {@code w*bpp % 256 == 0} and the destination offset is {@code % 512 == 0} (1280x720 and 1920x1080 RGBA8 qualify);
 *     otherwise via a GPU scratch plus one {@code CopyBufferRegion} per row into the destination, so the destination holds
 *     the <em>tightly packed</em> pixels as soon as the frame completes, with no CPU access to the heap. Depth sources
 *     use {@code FormatInfo.copyFormat} and whole-subresource copies only. The callback is handed to
 *     {@link ReadbackSupport}.</li>
 *     <li>{@code copyTextureToTexture}: destination coordinates come first; same {@code mipLevel} for both; formats
 *     must be equal ({@code IllegalArgumentException} otherwise); a full-subresource rect passes a NULL box (required for
 *     depth); a partial depth rect throws {@code UnsupportedOperationException}; source and destination subresource must
 *     differ.</li>
 * </ul>
 *
 * <p>Mip extents are {@code max(1, size >> mip)} everywhere. Argument errors are thrown before the list or the tracker
 * is touched (invariant I8).
 *
 * <p>Threading: render thread.
 */
public final class CopyEngine {
    private final DeviceContext ctx;

    public CopyEngine(DeviceContext ctx) {
        this.ctx = ctx;
    }

    /** See the class comment; copies {@code data.remaining()} bytes at {@code destination.offset()}; position is not consumed. */
    public void writeToBuffer(GpuBufferSlice destination, ByteBuffer data) {
        throw new UnsupportedOperationException("WP2");
    }

    /** See the class comment; copies {@code source.length()} bytes. */
    public void copyToBuffer(GpuBufferSlice source, GpuBufferSlice target) {
        throw new UnsupportedOperationException("WP2");
    }

    public void writeToTexture(GpuTexture destination, ByteBuffer source, int mipLevel, int depthOrLayer, int destX,
                               int destY, int width, int height) {
        throw new UnsupportedOperationException("WP2");
    }

    public void copyBufferToTexture(GpuBufferSlice source, int sourceX, int sourceY, int sourceWidth, int sourceHeight,
                                    GpuTexture destination, int destinationX, int destinationY, int copyWidth,
                                    int copyHeight, int mipLevel, int arrayLayer) {
        throw new UnsupportedOperationException("WP2");
    }

    /** The region form; the 5-argument encoder method passes the full mip rectangle. */
    public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback,
                                    int mipLevel, int x, int y, int width, int height) {
        throw new UnsupportedOperationException("WP2");
    }

    public void copyTextureToTexture(GpuTexture source, GpuTexture destination, int mipLevel, int destX, int destY,
                                     int sourceX, int sourceY, int width, int height) {
        throw new UnsupportedOperationException("WP2");
    }

    /**
     * {@code createBuffer(data)} for DEFAULT buffers: copies {@code data} (position untouched) through the UploadArena
     * into {@code dst} at offset 0, recorded <b>inline at the call position</b> (legal while a pass is open: copies between
     * draws are allowed and the new buffer cannot have been bound yet). {@code dst} was created in {@code COPY_DEST}
     * and stays there until its first reader pulls it to {@code GR}.
     */
    public void uploadInitialData(D3D12Buffer dst, ByteBuffer data) {
        throw new UnsupportedOperationException("WP2");
    }

    DeviceContext context() {
        return this.ctx;
    }
}
