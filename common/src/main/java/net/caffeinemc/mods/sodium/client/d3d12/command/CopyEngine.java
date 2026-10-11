// Owner: WP2 (commands + transient memory) - implemented
package net.caffeinemc.mods.sodium.client.d3d12.command;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import net.caffeinemc.mods.sodium.client.d3d12.core.Alignment;
import net.caffeinemc.mods.sodium.client.d3d12.core.CopyLayout;
import net.caffeinemc.mods.sodium.client.d3d12.core.DeviceContext;
import net.caffeinemc.mods.sodium.client.d3d12.core.FormatInfo;
import net.caffeinemc.mods.sodium.client.d3d12.core.RowRepack;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12Box;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12GraphicsCommandList;
import net.caffeinemc.mods.sodium.client.d3d12.memory.UploadArena;
import net.caffeinemc.mods.sodium.client.d3d12.resource.BufferMode;
import net.caffeinemc.mods.sodium.client.d3d12.resource.D3D12Buffer;
import net.caffeinemc.mods.sodium.client.d3d12.resource.D3D12Texture;
import net.caffeinemc.mods.sodium.client.d3d12.state.ResourceStateTracker;
import net.caffeinemc.mods.sodium.client.d3d12.state.ResourceStates;
import net.caffeinemc.mods.sodium.client.d3d12.state.TrackedResource;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
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
 * <p>Scratch slices (tier C, via-scratch read-backs, the same-resource bounce) never share a native resource with the
 * buffer they are copied from or to ({@code UploadArena.allocGpuAvoiding}): a resource has one state at a time.
 *
 * <p>Mip extents are {@code max(1, size >> mip)} everywhere. Argument errors are thrown before the list or the tracker
 * is touched (invariant I8): {@link IllegalArgumentException} for arguments that are wrong (foreign objects, ranges,
 * usage), {@link IllegalStateException} for closed objects and for the device-local-into-host-visible rule. A native
 * failure while recording is a {@code D3D12Exception} that the encoder turns into the FAILED state.
 *
 * <p>Threading: render thread.
 */
public final class CopyEngine {
    /** How a buffer-to-buffer copy is executed; see {@link #classify}. */
    public enum Mode {
        /** One {@code CopyBufferRegion} from the source to the destination. */
        GPU_COPY,
        /** Source and destination are the same native resource: {@code src -> scratch -> dst}. */
        GPU_BOUNCE,
        /** Host-visible destination: {@code MemorySegment.copy} at record time. */
        CPU_COPY,
        /** A READBACK buffer (permanently {@code COPY_DEST}) cannot be the source of a GPU copy. */
        REJECT_READBACK_SOURCE,
        /** The destination is host-visible but the source is device-local: cannot be emulated, refused before recording. */
        REJECT_DEVICE_LOCAL_SOURCE
    }

    /**
     * Where the bytes of a {@code copyBufferToTexture} come from and whether D3D12 can read them in place.
     *
     * @param start  byte offset of the first texel in the source buffer
     * @param stride bytes between source rows
     * @param end    one past the last byte read ({@code start + (rows - 1) * stride + rowBytes})
     */
    public record SourceWindow(long start, long stride, long end) {
        /** Tier A: the footprint can point straight into the source buffer. */
        public boolean direct() {
            return isDirectTextureSource(this.start, this.stride);
        }
    }

    /** Alignment of the staging slices of {@code writeToBuffer}: CopyBufferRegion needs none, 16 keeps vector copies happy. */
    private static final long STAGING_ALIGNMENT = 16L;

    private final DeviceContext ctx;
    private final @Nullable CommandRecorder recorder;
    private final @Nullable UploadArena uploads;
    private final @Nullable ReadbackSupport readbacks;
    private final MemorySegment box = D3D12Box.allocate(Arena.ofAuto());

    /**
     * Resolves the recorder, the upload arena and the read-back support lazily through {@code ctx.encoder()} (usable only
     * after {@code ctx.attachEncoder}); the encoder itself uses the explicit constructor.
     */
    public CopyEngine(DeviceContext ctx) {
        this(ctx, null, null, null);
    }

    /** Explicit references; {@code ctx.encoder()} is never touched (the encoder is not attached while it constructs this). */
    public CopyEngine(DeviceContext ctx, @Nullable CommandRecorder recorder, @Nullable UploadArena uploads,
                      @Nullable ReadbackSupport readbacks) {
        this.ctx = ctx;
        this.recorder = recorder;
        this.uploads = uploads;
        this.readbacks = readbacks;
    }

    // ------------------------------------------------------------------------------------------------------------
    // pure decision functions (tested on Linux)
    // ------------------------------------------------------------------------------------------------------------

    /**
     * The buffer-destination rule table of the class comment.
     *
     * @param sameResource whether source and destination are the same <em>native</em> resource
     */
    public static Mode classify(BufferMode dst, BufferMode src, boolean sameResource) {
        if (dst.gpuWritable()) {
            if (src == BufferMode.READBACK) {
                return Mode.REJECT_READBACK_SOURCE;
            }

            return sameResource ? Mode.GPU_BOUNCE : Mode.GPU_COPY;
        }

        return src.hostVisible() ? Mode.CPU_COPY : Mode.REJECT_DEVICE_LOCAL_SOURCE;
    }

    /**
     * @return tier A of {@code copyBufferToTexture}: the row stride is 256-aligned (and fits the {@code UINT RowPitch} of a
     * footprint as a positive {@code int}) and the start 512-aligned
     */
    public static boolean isDirectTextureSource(long start, long stride) {
        return stride % Alignment.TEXTURE_ROW_PITCH == 0L && stride <= Integer.MAX_VALUE && start % Alignment.TEXTURE_PLACEMENT == 0L;
    }

    /** @return the direct path of {@code copyTextureToBuffer}: READBACK destination, 256-aligned rows, 512-aligned offset */
    public static boolean isDirectReadback(BufferMode dst, long rowBytes, long offset) {
        return dst == BufferMode.READBACK && rowBytes % Alignment.TEXTURE_ROW_PITCH == 0L
                && offset % Alignment.TEXTURE_PLACEMENT == 0L;
    }

    /**
     * Geometry of the source window of a {@code copyBufferToTexture}: the slice holds an image of {@code sourceWidth}
     * texels per row; the window starts at texel {@code (sourceX, sourceY)} and is {@code copyWidth x copyHeight}.
     */
    public static SourceWindow sourceWindow(long sliceOffset, int sourceX, int sourceY, int sourceWidth, int copyWidth,
                                            int copyHeight, int bytesPerTexel) {
        // sourceY * sourceWidth * bytesPerTexel needs up to 66 bits for int arguments: refuse instead of wrapping around
        try {
            long stride = Math.multiplyExact((long) sourceWidth, (long) bytesPerTexel);
            long texels = Math.addExact((long) sourceX, Math.multiplyExact((long) sourceY, (long) sourceWidth));
            long start = Math.addExact(sliceOffset, Math.multiplyExact(texels, (long) bytesPerTexel));
            long rows = Math.multiplyExact(copyHeight - 1L, stride);
            long end = Math.addExact(start, Math.addExact(rows, Math.multiplyExact((long) copyWidth, (long) bytesPerTexel)));

            return new SourceWindow(start, stride, end);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("The copy window of " + copyWidth + "x" + copyHeight + " texels at (" + sourceX + ", " + sourceY
                    + ") of a " + sourceWidth + " texel wide source at offset " + sliceOffset + " does not fit 64 bits", e);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // buffers
    // ------------------------------------------------------------------------------------------------------------

    /** See the class comment; copies {@code data.remaining()} bytes at {@code destination.offset()}; position is not consumed. */
    public void writeToBuffer(GpuBufferSlice destination, ByteBuffer data) {
        D3D12Buffer buffer = usableBuffer(destination.buffer(), "destination");
        long offset = destination.offset();
        int length = data.remaining();

        // "offset + length > size" would wrap around for offsets near Long.MAX_VALUE (the frontend's own check does)
        if (offset < 0L || destination.length() < 0L || length > destination.length() || offset > buffer.size() - length) {
            throw new IllegalArgumentException("Writing " + length + " bytes at offset " + offset + " (slice length "
                    + destination.length() + ") does not fit the buffer of size " + buffer.size());
        }

        if (length == 0) {
            return;
        }

        this.writeBytes(buffer, offset, data, length);
    }

    /** See the class comment; copies {@code source.length()} bytes. */
    public void copyToBuffer(GpuBufferSlice source, GpuBufferSlice target) {
        D3D12Buffer src = usableBuffer(source.buffer(), "source");
        D3D12Buffer dst = usableBuffer(target.buffer(), "target");
        long length = source.length();

        if (length < 0L || target.length() < length || source.offset() < 0L || target.offset() < 0L
                || source.offset() > src.size() - length || target.offset() > dst.size() - length) {
            throw new IllegalArgumentException("Copying " + length + " bytes from offset " + source.offset() + " of a buffer of size "
                    + src.size() + " to offset " + target.offset() + " (slice length " + target.length() + ") of a buffer of size " + dst.size());
        }

        Mode mode = classify(dst.mode(), src.mode(), src.resource() == dst.resource());

        switch (mode) {
            case REJECT_READBACK_SOURCE -> throw new IllegalArgumentException(
                    "A READBACK buffer (MAP_READ) cannot be the source of a GPU copy");
            case REJECT_DEVICE_LOCAL_SOURCE -> throw new IllegalStateException(
                    "cannot copy GPU-only data into a host-visible MAP_WRITE buffer");
            default -> {
            }
        }

        if (length == 0L) {
            return;
        }

        switch (mode) {
            case CPU_COPY -> MemorySegment.copy(mappedOf(src), source.offset(), mappedOf(dst), target.offset(), length);
            case GPU_COPY -> {
                ResourceStateTracker tracker = this.ctx.tracker();
                TrackedResource srcState = src.tracked();
                TrackedResource dstState = dst.tracked();

                if (srcState != null) {
                    tracker.require(srcState, 0, ResourceStates.GR);
                }

                if (dstState != null) {
                    tracker.require(dstState, 0, ResourceStates.CD);
                }

                D3D12GraphicsCommandList list = this.list();

                tracker.flush(list);
                list.copyBufferRegion(dst.resource(), target.offset(), src.resource(), source.offset(), length);
            }
            case GPU_BOUNCE -> this.bounce(src, source.offset(), dst, target.offset(), length);
            default -> throw new AssertionError(mode);
        }
    }

    /**
     * {@code createBuffer(data)} for DEFAULT buffers: copies {@code data} (position untouched) through the UploadArena
     * into {@code dst} at offset 0, recorded <b>inline at the call position</b> (legal while a pass is open: copies between
     * draws are allowed and the new buffer cannot have been bound yet). {@code dst} was created in {@code COPY_DEST}
     * and stays there until its first reader pulls it to {@code GR}.
     */
    public void uploadInitialData(D3D12Buffer dst, ByteBuffer data) {
        usable(dst, "destination");

        int length = data.remaining();

        if (length > dst.size()) {
            throw new IllegalArgumentException("Initial data of " + length + " bytes does not fit the buffer of size " + dst.size());
        }

        if (length == 0) {
            return;
        }

        this.writeBytes(dst, 0L, data, length);
    }

    /**
     * Writes {@code length} bytes (already validated) at {@code offset}: a GPU copy from an UPLOAD slice for GPU-writable
     * buffers, a CPU copy at record time for host-visible ones.
     */
    private void writeBytes(D3D12Buffer buffer, long offset, ByteBuffer data, int length) {
        MemorySegment source = MemorySegment.ofBuffer(data);

        if (!buffer.mode().gpuWritable()) {
            MemorySegment.copy(source, 0L, mappedOf(buffer), offset, length);

            return;
        }

        UploadArena.Slice staging = this.uploads().alloc(length, STAGING_ALIGNMENT);

        MemorySegment.copy(source, 0L, staging.cpu(), 0L, length);

        TrackedResource state = buffer.tracked();

        if (state != null) {
            this.ctx.tracker().require(state, 0, ResourceStates.CD);
        }

        D3D12GraphicsCommandList list = this.list();

        this.ctx.tracker().flush(list);
        list.copyBufferRegion(buffer.resource(), offset, staging.block().resource(), staging.offset(), length);
    }

    /** {@code src -> scratch -> dst} for two ranges of one native resource (disjoint by the callers' guarantee). */
    private void bounce(D3D12Buffer src, long srcOffset, D3D12Buffer dst, long dstOffset, long length) {
        UploadArena.GpuSlice scratch = this.uploads().allocGpuAvoiding(length, STAGING_ALIGNMENT, src.resource());
        TrackedResource scratchState = requireTracked(scratch);
        TrackedResource srcState = requireTrackedBuffer(src);
        TrackedResource dstState = requireTrackedBuffer(dst);
        ResourceStateTracker tracker = this.ctx.tracker();
        D3D12GraphicsCommandList list = this.list();

        tracker.require(srcState, 0, ResourceStates.GR);
        tracker.require(scratchState, 0, ResourceStates.CD);
        tracker.flush(list);
        list.copyBufferRegion(scratch.block().resource(), scratch.offset(), src.resource(), srcOffset, length);

        tracker.require(scratchState, 0, ResourceStates.GR);
        tracker.require(dstState, 0, ResourceStates.CD);
        tracker.flush(list);
        list.copyBufferRegion(dst.resource(), dstOffset, scratch.block().resource(), scratch.offset(), length);
    }

    // ------------------------------------------------------------------------------------------------------------
    // buffer -> texture
    // ------------------------------------------------------------------------------------------------------------

    public void writeToTexture(GpuTexture destination, ByteBuffer source, int mipLevel, int depthOrLayer, int destX,
                               int destY, int width, int height) {
        D3D12Texture texture = usableTexture(destination, "destination");
        FormatInfo format = texture.formatInfo();

        if (texture.isDepth()) {
            throw new IllegalArgumentException("Depth textures cannot be written with writeToTexture: " + texture.getLabel());
        }

        checkMip(texture, mipLevel);
        checkLayer(texture, depthOrLayer);
        checkRect(destX, destY, width, height, texture.mipWidth(mipLevel), texture.mipHeight(mipLevel), "destination");

        long needed = (long) width * height * format.bytesPerTexel();

        if (source.remaining() < needed) {
            throw new IllegalArgumentException("The source holds " + source.remaining() + " bytes but a " + width + "x" + height
                    + " image of " + format.format() + " needs " + needed);
        }

        if (width == 0 || height == 0) {
            return;
        }

        CopyLayout layout = CopyLayout.of(width, format.bytesPerTexel(), height);
        UploadArena.Slice staging = this.uploads().alloc(layout.totalBytes(), Alignment.TEXTURE_PLACEMENT);

        RowRepack.copyPacked(source, staging.cpu(), 0L, layout);

        int subresource = texture.subresource(mipLevel, depthOrLayer);

        this.ctx.tracker().require(texture.tracked(), subresource, ResourceStates.CD);

        D3D12GraphicsCommandList list = this.list();

        this.ctx.tracker().flush(list);
        list.copyBufferToTexture(texture.resource(), subresource, destX, destY, 0, staging.block().resource(),
                staging.offset(), format.copyFormat(), width, height, 1, layout.rowPitch(), null);
    }

    public void copyBufferToTexture(GpuBufferSlice source, int sourceX, int sourceY, int sourceWidth, int sourceHeight,
                                    GpuTexture destination, int destinationX, int destinationY, int copyWidth,
                                    int copyHeight, int mipLevel, int arrayLayer) {
        D3D12Buffer buffer = usableBuffer(source.buffer(), "source");
        D3D12Texture texture = usableTexture(destination, "destination");
        FormatInfo format = texture.formatInfo();

        if (texture.isDepth()) {
            throw new IllegalArgumentException("Depth textures cannot be written with copyBufferToTexture: " + texture.getLabel());
        }

        if (buffer.mode() == BufferMode.READBACK) {
            throw new IllegalArgumentException("A READBACK buffer cannot be the source of a GPU copy");
        }

        checkMip(texture, mipLevel);
        checkLayer(texture, arrayLayer);
        checkRect(destinationX, destinationY, copyWidth, copyHeight, texture.mipWidth(mipLevel), texture.mipHeight(mipLevel), "destination");

        if (sourceX < 0 || sourceY < 0 || sourceWidth < 0 || sourceHeight < 0 || sourceX + (long) copyWidth > sourceWidth
                || sourceY + (long) copyHeight > sourceHeight) {
            throw new IllegalArgumentException("The copy window " + copyWidth + "x" + copyHeight + " at (" + sourceX + ", " + sourceY
                    + ") does not fit the " + sourceWidth + "x" + sourceHeight + " source image");
        }

        if (copyWidth == 0 || copyHeight == 0) {
            return;
        }

        SourceWindow window = sourceWindow(source.offset(), sourceX, sourceY, sourceWidth, copyWidth, copyHeight, format.bytesPerTexel());

        // the frontend only checks sourceWidth * copyHeight * bpp <= length (it ignores sourceY): check the real extent
        if (source.offset() < 0L || window.end() > buffer.size()) {
            throw new IllegalArgumentException("The copy window reads up to byte " + window.end() + " but the source buffer has "
                    + buffer.size() + " bytes");
        }

        int subresource = texture.subresource(mipLevel, arrayLayer);
        ResourceStateTracker tracker = this.ctx.tracker();

        if (window.direct()) {
            TrackedResource sourceState = buffer.tracked();

            if (sourceState != null) {
                tracker.require(sourceState, 0, ResourceStates.GR);
            }

            tracker.require(texture.tracked(), subresource, ResourceStates.CD);

            D3D12GraphicsCommandList list = this.list();

            tracker.flush(list);
            list.copyBufferToTexture(texture.resource(), subresource, destinationX, destinationY, 0, buffer.resource(), window.start(),
                    format.copyFormat(), copyWidth, copyHeight, 1, (int) window.stride(), null);

            return;
        }

        // tier C: per-row copy into a pitch-aligned scratch, then one footprint copy from the scratch
        int rowBytes = copyWidth * format.bytesPerTexel();
        CopyLayout layout = CopyLayout.of(copyWidth, format.bytesPerTexel(), copyHeight);
        UploadArena.GpuSlice scratch = this.uploads().allocGpuAvoiding(layout.totalBytes(), Alignment.TEXTURE_PLACEMENT, buffer.resource());
        TrackedResource scratchState = requireTracked(scratch);
        TrackedResource sourceState = buffer.tracked();
        D3D12GraphicsCommandList list = this.list();

        tracker.require(scratchState, 0, ResourceStates.CD);

        if (sourceState != null) {
            tracker.require(sourceState, 0, ResourceStates.GR);
        }

        tracker.flush(list);

        for (int row = 0; row < copyHeight; row++) {
            list.copyBufferRegion(scratch.block().resource(), scratch.offset() + (long) row * layout.rowPitch(), buffer.resource(),
                    window.start() + row * window.stride(), rowBytes);
        }

        tracker.require(scratchState, 0, ResourceStates.GR);
        tracker.require(texture.tracked(), subresource, ResourceStates.CD);
        tracker.flush(list);
        list.copyBufferToTexture(texture.resource(), subresource, destinationX, destinationY, 0, scratch.block().resource(),
                scratch.offset(), format.copyFormat(), copyWidth, copyHeight, 1, layout.rowPitch(), null);
    }

    // ------------------------------------------------------------------------------------------------------------
    // texture -> buffer
    // ------------------------------------------------------------------------------------------------------------

    /** The 5-argument encoder form: the whole mip. The mip is validated here, before its extent is computed. */
    public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback, int mipLevel) {
        D3D12Texture texture = usableTexture(source, "source");

        checkMip(texture, mipLevel);
        this.copyTextureToBuffer(source, destination, offset, callback, mipLevel, 0, 0, texture.mipWidth(mipLevel), texture.mipHeight(mipLevel));
    }

    /** The region form; the 5-argument encoder method passes the full mip rectangle. */
    public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback,
                                    int mipLevel, int x, int y, int width, int height) {
        if (callback == null) {
            throw new IllegalArgumentException("callback");
        }

        D3D12Texture texture = usableTexture(source, "source");
        D3D12Buffer buffer = usableBuffer(destination, "destination");
        FormatInfo format = texture.formatInfo();

        if ((texture.usage() & GpuTexture.USAGE_COPY_SRC) == 0) {
            throw new IllegalArgumentException("Texture needs USAGE_COPY_SRC to be a source for a read-back: " + texture.getLabel());
        }

        if ((buffer.usage() & GpuBuffer.USAGE_COPY_DST) == 0) {
            throw new IllegalArgumentException("Buffer needs USAGE_COPY_DST to be a destination for a read-back");
        }

        if (!buffer.mode().gpuWritable()) {
            throw new IllegalArgumentException("A host-visible MAP_WRITE (UPLOAD) buffer cannot be the destination of a GPU read-back");
        }

        if (texture.getDepthOrLayers() != 1) {
            throw new IllegalArgumentException("Only single-layer textures can be read back: " + texture.getLabel());
        }

        checkMip(texture, mipLevel);

        int mipWidth = texture.mipWidth(mipLevel);
        int mipHeight = texture.mipHeight(mipLevel);

        checkRect(x, y, width, height, mipWidth, mipHeight, "source");

        long bytes = (long) width * height * format.bytesPerTexel();

        if (offset < 0L || offset > buffer.size() - bytes) {
            throw new IllegalArgumentException("Reading " + bytes + " bytes to offset " + offset + " does not fit the buffer of size " + buffer.size());
        }

        boolean whole = x == 0 && y == 0 && width == mipWidth && height == mipHeight;

        if (texture.isDepth() && !whole) {
            throw new UnsupportedOperationException("Partial read-back of a depth texture is not supported (sub-rectangle copies of depth resources)");
        }

        if (bytes > 0L) {
            this.recordReadback(texture, mipLevel, buffer, offset, x, y, width, height, whole);
        }

        this.readbacks().deliverAfterCompletion(callback, null);
    }

    private void recordReadback(D3D12Texture texture, int mipLevel, D3D12Buffer buffer, long offset, int x, int y, int width,
                                int height, boolean whole) {
        FormatInfo format = texture.formatInfo();
        int rowBytes = width * format.bytesPerTexel();
        int subresource = texture.subresource(mipLevel, 0);
        MemorySegment sourceBox = whole ? null : this.window(x, y, width, height);
        ResourceStateTracker tracker = this.ctx.tracker();

        if (isDirectReadback(buffer.mode(), rowBytes, offset)) {
            tracker.require(texture.tracked(), subresource, ResourceStates.CS);

            D3D12GraphicsCommandList list = this.list();

            tracker.flush(list);
            list.copyTextureToBuffer(buffer.resource(), offset, format.copyFormat(), width, height, 1, rowBytes, texture.resource(),
                    subresource, sourceBox);

            return;
        }

        CopyLayout layout = CopyLayout.of(width, format.bytesPerTexel(), height);
        UploadArena.GpuSlice scratch = this.uploads().allocGpuAvoiding(layout.totalBytes(), Alignment.TEXTURE_PLACEMENT, buffer.resource());
        TrackedResource scratchState = requireTracked(scratch);
        TrackedResource destinationState = buffer.tracked();
        D3D12GraphicsCommandList list = this.list();

        tracker.require(texture.tracked(), subresource, ResourceStates.CS);
        tracker.require(scratchState, 0, ResourceStates.CD);
        tracker.flush(list);
        list.copyTextureToBuffer(scratch.block().resource(), scratch.offset(), format.copyFormat(), width, height, 1,
                layout.rowPitch(), texture.resource(), subresource, sourceBox);

        tracker.require(scratchState, 0, ResourceStates.GR);

        if (destinationState != null) {
            tracker.require(destinationState, 0, ResourceStates.CD);
        }

        tracker.flush(list);

        for (int row = 0; row < height; row++) {
            list.copyBufferRegion(buffer.resource(), offset + (long) row * rowBytes, scratch.block().resource(),
                    scratch.offset() + (long) row * layout.rowPitch(), rowBytes);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // texture -> texture
    // ------------------------------------------------------------------------------------------------------------

    public void copyTextureToTexture(GpuTexture source, GpuTexture destination, int mipLevel, int destX, int destY,
                                     int sourceX, int sourceY, int width, int height) {
        D3D12Texture src = usableTexture(source, "source");
        D3D12Texture dst = usableTexture(destination, "destination");

        if ((src.usage() & GpuTexture.USAGE_COPY_SRC) == 0) {
            throw new IllegalArgumentException("Texture needs USAGE_COPY_SRC to be a source for a copy: " + src.getLabel());
        }

        if ((dst.usage() & GpuTexture.USAGE_COPY_DST) == 0) {
            throw new IllegalArgumentException("Texture needs USAGE_COPY_DST to be a destination for a copy: " + dst.getLabel());
        }

        if (src.getFormat() != dst.getFormat()) {
            throw new IllegalArgumentException("copyTextureToTexture needs identical formats, but " + src.getFormat() + " -> " + dst.getFormat());
        }

        if (src.getDepthOrLayers() != 1 || dst.getDepthOrLayers() != 1) {
            throw new IllegalArgumentException("copyTextureToTexture handles layer 0 of single-layer textures only");
        }

        checkMip(src, mipLevel);
        checkMip(dst, mipLevel);

        int srcWidth = src.mipWidth(mipLevel);
        int srcHeight = src.mipHeight(mipLevel);
        int dstWidth = dst.mipWidth(mipLevel);
        int dstHeight = dst.mipHeight(mipLevel);

        checkRect(sourceX, sourceY, width, height, srcWidth, srcHeight, "source");
        checkRect(destX, destY, width, height, dstWidth, dstHeight, "destination");

        if (src.resource() == dst.resource()) {
            throw new IllegalArgumentException("Source and destination of copyTextureToTexture are the same subresource");
        }

        boolean wholeSource = sourceX == 0 && sourceY == 0 && width == srcWidth && height == srcHeight;

        if (src.isDepth() && !(wholeSource && destX == 0 && destY == 0 && width == dstWidth && height == dstHeight)) {
            throw new UnsupportedOperationException("Partial copies of depth textures are not supported (sub-rectangle copies of depth resources)");
        }

        if (width == 0 || height == 0) {
            return;
        }

        int subresource = src.subresource(mipLevel, 0);
        ResourceStateTracker tracker = this.ctx.tracker();

        tracker.require(src.tracked(), subresource, ResourceStates.CS);
        tracker.require(dst.tracked(), subresource, ResourceStates.CD);

        D3D12GraphicsCommandList list = this.list();

        tracker.flush(list);
        list.copyTexture(dst.resource(), subresource, destX, destY, 0, src.resource(), subresource,
                wholeSource ? null : this.window(sourceX, sourceY, width, height));
    }

    // ------------------------------------------------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------------------------------------------------

    /** Fills the shared {@code D3D12_BOX} scratch with a 2D window (the command copies the box during the call). */
    private MemorySegment window(int x, int y, int width, int height) {
        D3D12Box.setLeft(this.box, x);
        D3D12Box.setTop(this.box, y);
        D3D12Box.setFront(this.box, 0);
        D3D12Box.setRight(this.box, x + width);
        D3D12Box.setBottom(this.box, y + height);
        D3D12Box.setBack(this.box, 1);

        return this.box;
    }

    private D3D12GraphicsCommandList list() {
        return this.recorder().list();
    }

    private CommandRecorder recorder() {
        CommandRecorder explicit = this.recorder;

        return explicit != null ? explicit : this.ctx.encoder().recorder();
    }

    private UploadArena uploads() {
        UploadArena explicit = this.uploads;

        return explicit != null ? explicit : this.ctx.encoder().uploads();
    }

    private ReadbackSupport readbacks() {
        ReadbackSupport explicit = this.readbacks;

        return explicit != null ? explicit : this.ctx.encoder().readbacks();
    }

    private static D3D12Buffer usableBuffer(GpuBuffer buffer, String what) {
        if (!(buffer instanceof D3D12Buffer d3d)) {
            throw new IllegalArgumentException("The " + what + " buffer was not created by the Direct3D 12 backend: " + buffer);
        }

        usable(d3d, what);

        return d3d;
    }

    private static void usable(D3D12Buffer buffer, String what) {
        if (!buffer.isUsable()) {
            throw new IllegalStateException("The " + what + " buffer is closed or no longer valid");
        }
    }

    private static D3D12Texture usableTexture(GpuTexture texture, String what) {
        if (!(texture instanceof D3D12Texture d3d)) {
            throw new IllegalArgumentException("The " + what + " texture was not created by the Direct3D 12 backend: " + texture);
        }

        if (!d3d.isUsable()) {
            throw new IllegalStateException("The " + what + " texture is closed: " + d3d.getLabel());
        }

        return d3d;
    }

    private static MemorySegment mappedOf(D3D12Buffer buffer) {
        MemorySegment mapped = buffer.mapped();

        if (mapped == null) {
            throw new IllegalStateException("The host-visible buffer has no CPU mapping");
        }

        return mapped;
    }

    private static TrackedResource requireTracked(UploadArena.GpuSlice slice) {
        TrackedResource tracked = slice.block().tracked();

        if (tracked == null) {
            throw new IllegalStateException("A device-local scratch block must be tracked");
        }

        return tracked;
    }

    private static TrackedResource requireTrackedBuffer(D3D12Buffer buffer) {
        TrackedResource tracked = buffer.tracked();

        if (tracked == null) {
            throw new IllegalStateException("A GPU-writable device-local buffer must be tracked: " + buffer.mode());
        }

        return tracked;
    }

    private static void checkMip(D3D12Texture texture, int mip) {
        if (mip < 0 || mip >= texture.getMipLevels()) {
            throw new IllegalArgumentException("Invalid mipLevel " + mip + ", must be >= 0 and < " + texture.getMipLevels());
        }
    }

    private static void checkLayer(D3D12Texture texture, int layer) {
        if (layer < 0 || layer >= texture.getDepthOrLayers()) {
            throw new IllegalArgumentException("Invalid layer " + layer + ", must be >= 0 and < " + texture.getDepthOrLayers());
        }
    }

    private static void checkRect(int x, int y, int width, int height, int mipWidth, int mipHeight, String what) {
        if (x < 0 || y < 0 || width < 0 || height < 0 || (long) x + width > mipWidth || (long) y + height > mipHeight) {
            throw new IllegalArgumentException("The " + what + " rectangle " + width + "x" + height + " at (" + x + ", " + y
                    + ") is outside the " + mipWidth + "x" + mipHeight + " mip");
        }
    }

    DeviceContext context() {
        return this.ctx;
    }
}
