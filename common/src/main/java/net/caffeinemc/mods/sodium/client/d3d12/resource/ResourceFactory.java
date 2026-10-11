// Owner: WP1 (device core) - implemented
package net.caffeinemc.mods.sodium.client.d3d12.resource;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import net.caffeinemc.mods.sodium.client.d3d12.core.D3D12Log;
import net.caffeinemc.mods.sodium.client.d3d12.core.DeviceContext;
import net.caffeinemc.mods.sodium.client.d3d12.core.FormatInfo;
import net.caffeinemc.mods.sodium.client.d3d12.core.Formats;
import net.caffeinemc.mods.sodium.client.d3d12.descriptor.CpuDescriptorAllocator;
import net.caffeinemc.mods.sodium.client.d3d12.interop.D3D12Exception;
import net.caffeinemc.mods.sodium.client.d3d12.interop.Hr;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Enums;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12DepthStencilViewDesc;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12Range;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12RenderTargetViewDesc;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12SamplerDesc;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12ShaderResourceViewDesc;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12Tex2dDsv;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12Tex2dRtv;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12Tex2dSrv;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12TexcubeSrv;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12Device;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12Resource;
import net.caffeinemc.mods.sodium.client.d3d12.state.ResourceStates;
import net.caffeinemc.mods.sodium.client.d3d12.state.TrackedResource;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Creates every buffer, texture, view and sampler. The one place that knows heap types, initial states, resource flags
 * and the out-of-memory policy; {@code D3D12GpuDevice} delegates its five creation methods here, and the surface uses
 * {@link #createSampler} for the present blit's point sampler.
 *
 * <p>Guarantees for every method: arguments are validated before anything is created; a failure leaves no native object
 * and no descriptor behind (resource created last, descriptors allocated after it); {@code E_OUTOFMEMORY} and
 * over-limit sizes become {@code GpuOutOfMemoryException} in {@code createTexture} and both {@code createBuffer}
 * overloads only; labels are evaluated only when {@code DeviceContext.labels()} is true and applied with {@code SetName}.
 *
 * <p>Out-of-memory policy in detail (ARCHITECTURE 2.k.2): a texture side outside {@code [1, 16384]} and any resource whose
 * size is absurd (a buffer above 1 TiB, a texture above 16 GiB) are refused before Direct3D is called;
 * {@code E_OUTOFMEMORY} from {@code CreateCommittedResource} or {@code Map} is out of memory; an {@code E_INVALIDARG} for
 * a texture of at least 1 GiB (above the largest resource Direct3D 12 can create on most adapters) is treated the same,
 * because the size is then the only plausible cause and "retry smaller" ({@code MainTarget}) is the right reaction. Every
 * other failure is an {@code IllegalStateException}, a lost device a {@code GpuDeviceLossException}.
 *
 * <p>The factory also owns the native scratch memory used by the lazy view creation of {@link D3D12Texture} and
 * {@link D3D12TextureView} (descriptor structs, the {@code Map} out pointer): allocated once, guarded by a lock because
 * the creation paths are cold, so nothing is allocated per call.
 *
 * <p>Thread: render thread (the lazy descriptor creation tolerates other threads through its lock).
 */
public final class ResourceFactory {
    /** Largest texture side the backend reports and accepts ({@code D3D12_REQ_TEXTURE2D_U_OR_V_DIMENSION}). */
    public static final int MAX_TEXTURE_SIZE = 16384;

    /** Buffers above this are refused as out of memory before Direct3D is called (also guards the width arithmetic). */
    private static final long MAX_BUFFER_BYTES = 1L << 40;
    /** Textures above this estimated size are refused as out of memory before Direct3D is called. */
    private static final long MAX_TEXTURE_BYTES = 1L << 34;
    /** An {@code E_INVALIDARG} for a texture or buffer of at least this size is reported as out of memory. */
    private static final long LARGE_RESOURCE_BYTES = 1L << 30;

    /** {@code D3D12_DEFAULT_SHADER_4_COMPONENT_MAPPING}: (0, 1, 2, 3) plus the always-set bit (header-checked). */
    private static final int DEFAULT_COMPONENT_MAPPING = 0x1688;

    private final DeviceContext ctx;
    private final ResourceCounters counters = new ResourceCounters();
    private final AtomicInteger textureSequence = new AtomicInteger();

    // native scratch, guarded by scratchLock (cold paths only)
    private final Object scratchLock = new Object();
    private final Arena scratchArena = Arena.ofAuto();
    private final MemorySegment mapOut = this.scratchArena.allocate(8L, 8L);
    private final MemorySegment mapRange = D3D12Range.allocate(this.scratchArena);
    private final MemorySegment srvDesc = D3D12ShaderResourceViewDesc.allocate(this.scratchArena);
    private final MemorySegment rtvDesc = D3D12RenderTargetViewDesc.allocate(this.scratchArena);
    private final MemorySegment dsvDesc = D3D12DepthStencilViewDesc.allocate(this.scratchArena);
    private final MemorySegment samplerDesc = D3D12SamplerDesc.allocate(this.scratchArena);
    private final Map<Long, MemorySegment> clearValues = new HashMap<>();

    public ResourceFactory(DeviceContext ctx) {
        this.ctx = ctx;
    }

    /** @return the live-object counters (for the shutdown report and the leak tests) */
    public ResourceCounters counters() {
        return this.counters;
    }

    /** @return a copy of the {@code D3D12_SAMPLER_DESC} that the last {@link #createSampler} wrote (tests only) */
    MemorySegment copyOfLastSamplerDesc() {
        synchronized (this.scratchLock) {
            return Arena.ofAuto().allocate(D3D12SamplerDesc.SIZE, 8L).copyFrom(this.samplerDesc);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // buffers
    // ------------------------------------------------------------------------------------------------------------

    /**
     * {@code createBuffer(label, usage, size)}: mode from {@link BufferModes#select}; contents undefined (UPLOAD and
     * READBACK memory is not zeroed by us). Size must be {@code > 0} (the frontend checks).
     *
     * @throws IllegalArgumentException      for {@code size <= 0} or {@code MAP_READ} combined with a GPU-read bit
     * @throws com.mojang.renderpearl.api.device.GpuOutOfMemoryException if the allocation or the mapping does not fit
     */
    public D3D12DeviceBuffer createBuffer(@Nullable Supplier<String> label, int usage, long size) {
        if (size <= 0L) {
            throw new IllegalArgumentException("Buffer size must be positive: " + size);
        }

        return this.createBufferInternal(label, usage, size, null);
    }

    /**
     * {@code createBuffer(label, usage, data)}: size {@code data.remaining()}, contents bytes {@code [position, limit)};
     * {@code usage() | COPY_DST}; the data is captured before returning (the position is not consumed). UPLOAD: memcpy
     * into the mapping. DEFAULT (and READBACK, which the copy engine writes like a DEFAULT buffer): created in
     * {@code COPY_DEST}, then {@code ctx.encoder().uploadInitialData(buffer, data)} records the copy inline at the call
     * position (legal even while a render pass is open; the buffer cannot have been bound yet).
     */
    public D3D12DeviceBuffer createBuffer(@Nullable Supplier<String> label, int usage, ByteBuffer data) {
        long size = data.remaining();

        if (size <= 0L) {
            throw new IllegalArgumentException("Initial data of a buffer must not be empty");
        }

        return this.createBufferInternal(label, usage | GpuBuffer.USAGE_COPY_DST, size, data);
    }

    private D3D12DeviceBuffer createBufferInternal(@Nullable Supplier<String> label, int usage, long size, @Nullable ByteBuffer data) {
        this.checkUsable();

        BufferMode mode = BufferModes.select(usage);
        boolean withData = data != null;

        if (size > MAX_BUFFER_BYTES) {
            throw this.ctx.errors().outOfMemory("creating a buffer of " + size + " bytes", null);
        }

        String name = this.resolveLabel(label);
        long width = BufferModes.d3dWidth(size, BufferModes.needsUniformPad(usage));

        int heapType;
        int initialState;

        switch (mode) {
            case UPLOAD -> {
                heapType = Enums.HeapType.UPLOAD;
                initialState = ResourceStates.GR;
            }
            case READBACK -> {
                heapType = Enums.HeapType.READBACK;
                initialState = ResourceStates.CD;
            }
            default -> {
                heapType = Enums.HeapType.DEFAULT;
                initialState = withData ? ResourceStates.CD : ResourceStates.GR;
            }
        }

        D3D12Resource resource;

        try {
            resource = this.ctx.d3d().createCommittedBuffer(heapType, width, Enums.ResourceFlags.NONE, initialState);
        } catch (D3D12Exception e) {
            throw this.creationFailure("creating a buffer of " + size + " bytes", e, width >= LARGE_RESOURCE_BYTES);
        }

        Arena arena = null;
        MemorySegment mapped = null;
        D3D12DeviceBuffer buffer;

        try {
            if (mode.hostVisible()) {
                arena = Arena.ofShared();
                mapped = this.mapPersistent(resource, width, mode == BufferMode.READBACK, arena);
            }

            TrackedResource tracked = mode.tracked() ? new TrackedResource(resource, 1, initialState) : null;

            if (name != null) {
                resource.setName(name);
            }

            buffer = new D3D12DeviceBuffer(this.ctx, name == null ? "" : name, usage, size, mode, resource, tracked, mapped, arena,
                    width, this.counters);
        } catch (D3D12Exception e) {
            discard(resource, arena);

            throw this.creationFailure("mapping a buffer of " + size + " bytes", e, false);
        } catch (Throwable t) {
            discard(resource, arena);

            throw t;
        }

        this.counters.bufferCreated();

        if (withData) {
            try {
                this.writeInitialData(buffer, mode, data);
            } catch (Throwable t) {
                buffer.close();

                throw t;
            }
        }

        return buffer;
    }

    private void writeInitialData(D3D12DeviceBuffer buffer, BufferMode mode, ByteBuffer data) {
        if (mode == BufferMode.UPLOAD) {
            MemorySegment source = MemorySegment.ofBuffer(data);
            MemorySegment target = buffer.mapped();

            MemorySegment.copy(source, 0L, target, 0L, source.byteSize());
        } else {
            this.ctx.encoder().uploadInitialData(buffer, data);
        }
    }

    /** Maps subresource 0 for the life of the buffer and returns the whole resource as a segment bound to {@code arena}. */
    private MemorySegment mapPersistent(D3D12Resource resource, long width, boolean readable, Arena arena) {
        synchronized (this.scratchLock) {
            MemorySegment readRange = null;

            if (!readable) {
                // UPLOAD memory is write-combined: tell the runtime the CPU reads nothing
                D3D12Range.setBegin(this.mapRange, 0L);
                D3D12Range.setEnd(this.mapRange, 0L);
                readRange = this.mapRange;
            }

            int hr = resource.map(0, readRange, this.mapOut);

            if (hr < 0) {
                throw new D3D12Exception("ID3D12Resource::Map failed", hr);
            }

            return this.mapOut.get(ValueLayout.ADDRESS, 0L).reinterpret(width, arena, null);
        }
    }

    private static void discard(D3D12Resource resource, @Nullable Arena arena) {
        // the resource was never used by the GPU: release it right away
        try {
            if (arena != null) {
                arena.close();
            }
        } catch (RuntimeException ignored) {
            // nothing sensible to do
        }

        try {
            resource.release();
        } catch (RuntimeException e) {
            D3D12Log.warn("Failed to release a Direct3D 12 buffer after a failed creation: {}", e.toString());
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // textures
    // ------------------------------------------------------------------------------------------------------------

    /**
     * See {@link D3D12Texture} for the full creation recipe. A depth format with {@code USAGE_RENDER_ATTACHMENT} other
     * than {@code D32_FLOAT} is rejected here with {@code IllegalArgumentException("only D32_FLOAT depth attachments are
     * supported: ..")} ({@link net.caffeinemc.mods.sodium.client.d3d12.core.Formats#requireDepthAttachment}): every PSO
     * bakes {@code DSVFormat = D32_FLOAT} and a different bound DSV format is undefined behaviour. This is the safe place
     * to fail (a creation call, unlike {@code createRenderPass}, can throw without wedging the frontend).
     */
    public D3D12Texture createTexture(@Nullable String label, int usage, GpuFormat format, int width, int height,
                                      int depthOrLayers, int mipLevels) {
        this.checkUsable();

        FormatInfo info = Formats.requireTexture(format);
        boolean attachment = (usage & GpuTexture.USAGE_RENDER_ATTACHMENT) != 0;
        boolean cube = (usage & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0;

        if (attachment && info.depth()) {
            Formats.requireDepthAttachment(format);
        }

        if (width < 1 || height < 1 || width > MAX_TEXTURE_SIZE || height > MAX_TEXTURE_SIZE) {
            throw this.ctx.errors().outOfMemory("creating a " + width + "x" + height + " texture (the maximum side is "
                    + MAX_TEXTURE_SIZE + ")", null);
        }

        if (depthOrLayers != 1 && !(cube && depthOrLayers == 6)) {
            throw new IllegalArgumentException("Only 2D textures and 6-layer cube maps are supported, got " + depthOrLayers
                    + " layer(s) with usage 0x" + Integer.toHexString(usage));
        }

        if (cube && width != height) {
            throw new IllegalArgumentException("A cube map must be square: " + width + "x" + height);
        }

        int fullChain = 32 - Integer.numberOfLeadingZeros(Math.max(width, height));

        if (mipLevels < 1 || mipLevels > fullChain) {
            throw new IllegalArgumentException("A " + width + "x" + height + " texture has between 1 and " + fullChain
                    + " mip levels, got " + mipLevels);
        }

        long estimate = (long) width * height * info.bytesPerTexel() * depthOrLayers;

        if (mipLevels > 1) {
            estimate += estimate / 3L;
        }

        if (estimate > MAX_TEXTURE_BYTES) {
            throw this.ctx.errors().outOfMemory("creating a " + width + "x" + height + " texture of about " + estimate + " bytes", null);
        }

        String name = label != null ? label : "texture#" + this.textureSequence.incrementAndGet();
        int flags = Enums.ResourceFlags.NONE;

        if (attachment) {
            flags = info.depth() ? Enums.ResourceFlags.ALLOW_DEPTH_STENCIL : Enums.ResourceFlags.ALLOW_RENDER_TARGET;
        }

        D3D12Resource resource;

        try {
            MemorySegment clear = null;

            if (attachment && this.ctx.options().optimizedClearValues()) {
                clear = this.clearValue(info.depth() ? info.dsvFormat() : info.rtvFormat(), info.depth());
            }

            resource = this.ctx.d3d().createCommittedTexture2D(info.resourceFormat(), width, height, depthOrLayers, mipLevels, 1,
                    flags, ResourceStates.COMMON, clear);
        } catch (D3D12Exception e) {
            throw this.creationFailure("creating a " + width + "x" + height + " texture '" + name + "'", e, estimate >= LARGE_RESOURCE_BYTES);
        }

        D3D12Texture texture;

        try {
            if (this.ctx.labels()) {
                resource.setName(name);
            }

            texture = new D3D12Texture(this.ctx, name, usage, format, width, height, depthOrLayers, mipLevels, info, resource,
                    new TrackedResource(resource, mipLevels * depthOrLayers, ResourceStates.COMMON), this.counters);
        } catch (Throwable t) {
            try {
                resource.release();
            } catch (RuntimeException ignored) {
                // nothing sensible to do
            }

            throw t;
        }

        this.counters.textureCreated();

        return texture;
    }

    /**
     * @param texture a {@link D3D12Texture} created by this factory (foreign objects: {@code IllegalArgumentException})
     */
    public D3D12TextureView createView(GpuTexture texture, int baseMipLevel, int mipLevels) {
        this.checkUsable();

        if (!(texture instanceof D3D12Texture d3dTexture) || d3dTexture.context() != this.ctx) {
            throw new IllegalArgumentException("Texture was not created by this Direct3D 12 device: " + texture);
        }

        if (d3dTexture.isClosed()) {
            throw new IllegalArgumentException("Texture is closed: " + d3dTexture.getLabel());
        }

        // long arithmetic: baseMipLevel + mipLevels wraps around for huge arguments and must not look valid
        if (baseMipLevel < 0 || mipLevels < 1 || (long) baseMipLevel + mipLevels > d3dTexture.getMipLevels()) {
            throw new IllegalArgumentException("Invalid mip range [" + baseMipLevel + ", +" + mipLevels + ") for a texture with "
                    + d3dTexture.getMipLevels() + " mip level(s)");
        }

        try {
            return new D3D12TextureView(this.ctx, d3dTexture, baseMipLevel, mipLevels, this.counters);
        } catch (IllegalStateException e) {
            // the last reference of the texture went away between the closed check and the registration
            throw new IllegalArgumentException("Texture is closed: " + d3dTexture.getLabel(), e);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // lazily created descriptors (called by D3D12Texture / D3D12TextureView, cold paths)
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Allocates a staging descriptor and writes the SRV of {@code mipLevels} mips starting at {@code baseMip}:
     * {@code TEXTURECUBE} (all 6 faces) for cube maps, {@code TEXTURE2D} otherwise. The descriptor is freed again if the
     * write throws.
     */
    long createSrv(D3D12Texture texture, int baseMip, int mipLevels) {
        long handle = this.ctx.descriptors().cbvSrvUav().allocate();

        try {
            synchronized (this.scratchLock) {
                MemorySegment desc = this.srvDesc;

                desc.fill((byte) 0);
                D3D12ShaderResourceViewDesc.setFormat(desc, texture.formatInfo().srvFormat());
                D3D12ShaderResourceViewDesc.setShader4ComponentMapping(desc, DEFAULT_COMPONENT_MAPPING);

                if (texture.isCube()) {
                    D3D12ShaderResourceViewDesc.setViewDimension(desc, Enums.SrvDimension.TEXTURECUBE);

                    MemorySegment cube = D3D12ShaderResourceViewDesc.sliceTextureCube(desc);

                    D3D12TexcubeSrv.setMostDetailedMip(cube, baseMip);
                    D3D12TexcubeSrv.setMipLevels(cube, mipLevels);
                } else {
                    D3D12ShaderResourceViewDesc.setViewDimension(desc, Enums.SrvDimension.TEXTURE2D);

                    MemorySegment tex = D3D12ShaderResourceViewDesc.sliceTexture2D(desc);

                    D3D12Tex2dSrv.setMostDetailedMip(tex, baseMip);
                    D3D12Tex2dSrv.setMipLevels(tex, mipLevels);
                }

                this.ctx.d3d().createShaderResourceView(texture.resource(), desc, handle);
            }

            return handle;
        } catch (Throwable t) {
            this.freeNow(this.ctx.descriptors().cbvSrvUav(), handle);

            throw t;
        }
    }

    /** Allocates a staging descriptor and writes the RTV of {@code mip}, layer 0. */
    long createRtv(D3D12Texture texture, int mip) {
        long handle = this.ctx.descriptors().rtv().allocate();

        try {
            synchronized (this.scratchLock) {
                MemorySegment desc = this.rtvDesc;

                desc.fill((byte) 0);
                D3D12RenderTargetViewDesc.setFormat(desc, texture.formatInfo().rtvFormat());
                D3D12RenderTargetViewDesc.setViewDimension(desc, Enums.RtvDimension.TEXTURE2D);
                D3D12Tex2dRtv.setMipSlice(D3D12RenderTargetViewDesc.sliceTexture2D(desc), mip);

                this.ctx.d3d().createRenderTargetView(texture.resource(), desc, handle);
            }

            return handle;
        } catch (Throwable t) {
            this.freeNow(this.ctx.descriptors().rtv(), handle);

            throw t;
        }
    }

    /** Allocates a staging descriptor and writes the DSV of {@code mip}, layer 0 (read/write depth). */
    long createDsv(D3D12Texture texture, int mip) {
        long handle = this.ctx.descriptors().dsv().allocate();

        try {
            synchronized (this.scratchLock) {
                MemorySegment desc = this.dsvDesc;

                desc.fill((byte) 0);
                D3D12DepthStencilViewDesc.setFormat(desc, texture.formatInfo().dsvFormat());
                D3D12DepthStencilViewDesc.setViewDimension(desc, Enums.DsvDimension.TEXTURE2D);
                D3D12DepthStencilViewDesc.setFlags(desc, Enums.DsvFlags.NONE);
                D3D12Tex2dDsv.setMipSlice(D3D12DepthStencilViewDesc.sliceTexture2D(desc), mip);

                this.ctx.d3d().createDepthStencilView(texture.resource(), desc, handle);
            }

            return handle;
        } catch (Throwable t) {
            this.freeNow(this.ctx.descriptors().dsv(), handle);

            throw t;
        }
    }

    /**
     * The optimised clear value of attachments of {@code format}: colour (0, 0, 0, 0) or depth 0.0 / stencil 0 (reverse-Z:
     * 0.0 is "far"). Memoised per format in the factory's scratch arena, so creating a texture allocates no native memory.
     */
    private MemorySegment clearValue(int format, boolean depth) {
        synchronized (this.scratchLock) {
            long key = ((long) format << 1) | (depth ? 1L : 0L);
            MemorySegment value = this.clearValues.get(key);

            if (value == null) {
                value = depth
                        ? D3D12Device.depthStencilClearValue(this.scratchArena, format, 0.0f, 0)
                        : D3D12Device.colorClearValue(this.scratchArena, format, 0.0f, 0.0f, 0.0f, 0.0f);
                this.clearValues.put(key, value);
            }

            return value;
        }
    }

    private void freeNow(CpuDescriptorAllocator allocator, long handle) {
        // the descriptor was never published: no GPU or table can refer to it
        try {
            allocator.free(handle);
        } catch (RuntimeException e) {
            D3D12Log.warn("Failed to free a staging descriptor after a failed view creation: {}", e.toString());
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // samplers
    // ------------------------------------------------------------------------------------------------------------

    /** Sampler recipe: see {@link D3D12Sampler}. */
    public D3D12Sampler createSampler(AddressMode addressModeU, AddressMode addressModeV, FilterMode minFilter,
                                      FilterMode magFilter, int maxAnisotropy, OptionalDouble maxLod) {
        this.checkUsable();

        int limit = this.ctx.info().limits().maxAnisotropy();

        if (maxAnisotropy < 1 || maxAnisotropy > limit) {
            throw new IllegalArgumentException("maxAnisotropy must be in [1, " + limit + "], got " + maxAnisotropy);
        }

        float lod = Float.MAX_VALUE;

        if (maxLod.isPresent()) {
            double value = maxLod.getAsDouble();

            if (Double.isNaN(value)) {
                throw new IllegalArgumentException("maxLod must not be NaN");
            }

            // D3D12_SAMPLER_DESC.MaxLOD is a finite float: clamp to [0, FLT_MAX] (infinity or a huge double would otherwise
            // become +Infinity, which the runtime / debug layer rejects)
            lod = (float) Math.min(Math.max(0.0, value), (double) Float.MAX_VALUE);
        }

        CpuDescriptorAllocator allocator = this.ctx.descriptors().sampler();
        long handle = allocator.allocate();

        try {
            synchronized (this.scratchLock) {
                MemorySegment desc = this.samplerDesc;

                desc.fill((byte) 0);
                D3D12SamplerDesc.setFilter(desc, SamplerMapping.filter(minFilter, magFilter, maxAnisotropy));
                D3D12SamplerDesc.setAddressU(desc, SamplerMapping.address(addressModeU));
                D3D12SamplerDesc.setAddressV(desc, SamplerMapping.address(addressModeV));
                D3D12SamplerDesc.setAddressW(desc, Enums.TextureAddressMode.CLAMP);
                D3D12SamplerDesc.setMipLODBias(desc, 0.0f);
                D3D12SamplerDesc.setMaxAnisotropy(desc, maxAnisotropy);
                D3D12SamplerDesc.setComparisonFunc(desc, Enums.ComparisonFunc.NEVER);
                D3D12SamplerDesc.setMinLOD(desc, 0.0f);
                D3D12SamplerDesc.setMaxLOD(desc, lod);

                this.ctx.d3d().createSampler(desc, handle);
            }

            D3D12Sampler sampler = new D3D12Sampler(this.ctx, addressModeU, addressModeV, minFilter, magFilter, maxAnisotropy,
                    maxLod, handle, this.counters);

            this.counters.samplerCreated();

            return sampler;
        } catch (Throwable t) {
            this.freeNow(allocator, handle);

            throw t;
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------------------------------------------------

    private void checkUsable() {
        this.ctx.errors().checkHealthy();

        if (this.ctx.isClosed()) {
            throw new IllegalStateException("The Direct3D 12 device is closed");
        }
    }

    /** @return the evaluated label, or null when labels are off or none was given */
    private @Nullable String resolveLabel(@Nullable Supplier<String> label) {
        if (label == null || !this.ctx.labels()) {
            return null;
        }

        String value = label.get();

        return value == null || value.isEmpty() ? null : value;
    }

    /**
     * Turns a failing creation call into the exception the contract names.
     *
     * @param largeResource true if the resource is large enough that an {@code E_INVALIDARG} means "too big"
     */
    private RuntimeException creationFailure(String what, D3D12Exception e, boolean largeResource) {
        if (e.isOutOfMemory() || (largeResource && e.getHresult() == Hr.E_INVALIDARG)) {
            return this.ctx.errors().outOfMemory(what, e);
        }

        return this.ctx.errors().translate(e, what);
    }
}
