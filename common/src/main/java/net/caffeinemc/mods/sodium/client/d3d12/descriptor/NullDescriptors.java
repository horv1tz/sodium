// Owner: WP4 (descriptors) - implemented
package net.caffeinemc.mods.sodium.client.d3d12.descriptor;

import net.caffeinemc.mods.sodium.client.d3d12.core.D3D12Log;
import net.caffeinemc.mods.sodium.client.d3d12.core.Formats;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Enums;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12BufferSrv;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12RenderTargetViewDesc;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12SamplerDesc;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12ShaderResourceViewDesc;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12Tex2dRtv;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12Tex2dSrv;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12TexcubeSrv;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12Device;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12Resource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * The valid-but-empty descriptors that fill every table entry and root argument no resource owns, so that every root
 * parameter always holds a legal value (invariant I5) - required on Resource Binding Tier 1, harmless elsewhere, and the
 * mechanism behind the "substitute and warn once" tolerance policy.
 *
 * <p>Created once at device construction, CPU-only (they are copied into the rings like any other descriptor):
 * <ul>
 *     <li>null SRV for {@code Texture2D<float4>} (RGBA8) and {@code TextureCube};</li>
 *     <li>null typed-buffer SRVs for {@code Buffer<int4>} ({@code R32_SINT}), {@code Buffer<uint4>} ({@code R32_UINT}) and
 *     {@code Buffer<float4>} ({@code R32_FLOAT}), selected by the slot's sampled base type ({@code Formats.BASE_*});</li>
 *     <li>a null RTV ({@code R8G8B8A8_UNORM}, NULL resource) for holes in the colour attachment list;</li>
 *     <li>a default sampler (point, clamp, {@code MaxLOD 0});</li>
 *     <li>{@link #zeroCbvVa()}: the GPU virtual address of a permanent zeroed 64 KiB UPLOAD buffer that every unset or
 *     unusable root CBV points to. The buffer is created in {@code GENERIC_READ}, filled with zeros once by the CPU at
 *     creation (D3D12 zero-initialises committed memory as well, but the contract must not depend on it) and never
 *     written afterwards; being an UPLOAD resource in {@code GENERIC_READ} it is readable by every root CBV without a
 *     barrier.</li>
 * </ul>
 * Each null descriptor is a distinct <em>kind</em> ({@code DescriptorTableCache.NULL_TEXTURE_2D, NULL_TEXTURE_CUBE,
 * NULL_BUFFER_INT, NULL_BUFFER_UINT, NULL_BUFFER_FLOAT, NULL_SAMPLER}): binding a null of the wrong dimension where the
 * shader declares another resource type is undefined behaviour, so the binder substitutes by the slot's expected kind and
 * the descriptor-table cache keys null entries structurally by kind ({@code DescriptorTableCache.nullKey}).
 *
 * <p>The staging descriptors live in the allocators handed to {@link #create}; {@link #close()} returns them there and
 * releases the zero buffer (call it only when the GPU is idle, before the allocators are closed). {@code close()} is
 * idempotent and never throws. Render thread (creation and close), read-only afterwards.
 */
public final class NullDescriptors implements AutoCloseable {
    /** {@code D3D12_DEFAULT_SHADER_4_COMPONENT_MAPPING} = {@code ENCODE(0, 1, 2, 3)} with the always-set bit 12. */
    static final int DEFAULT_COMPONENT_MAPPING = 0 | (1 << 3) | (2 << 6) | (3 << 9) | (1 << 12);

    /** Size of the zero constant buffer: the largest range a root CBV can name (64 KiB). */
    static final long ZERO_BUFFER_BYTES = 64L * 1024L;

    private final CpuDescriptorAllocator cbvSrvUav;
    private final CpuDescriptorAllocator rtvAllocator;
    private final CpuDescriptorAllocator samplerAllocator;
    private final long srv2d;
    private final long srvCube;
    private final long srvBufferInt;
    private final long srvBufferUint;
    private final long srvBufferFloat;
    private final long rtv;
    private final long sampler;
    private final D3D12Resource zeroBuffer;
    private final long zeroCbvVa;
    private boolean closed;

    private NullDescriptors(CpuDescriptorAllocator cbvSrvUav, CpuDescriptorAllocator rtvAllocator,
                            CpuDescriptorAllocator samplerAllocator, long[] srvs, long rtv, long sampler,
                            D3D12Resource zeroBuffer, long zeroCbvVa) {
        this.cbvSrvUav = cbvSrvUav;
        this.rtvAllocator = rtvAllocator;
        this.samplerAllocator = samplerAllocator;
        this.srv2d = srvs[0];
        this.srvCube = srvs[1];
        this.srvBufferInt = srvs[2];
        this.srvBufferUint = srvs[3];
        this.srvBufferFloat = srvs[4];
        this.rtv = rtv;
        this.sampler = sampler;
        this.zeroBuffer = zeroBuffer;
        this.zeroCbvVa = zeroCbvVa;
    }

    /**
     * Creates the descriptors and the zero buffer. On failure everything built so far is returned/released and the
     * exception is rethrown (no descriptor and no COM object is left behind).
     */
    public static NullDescriptors create(D3D12Device device, CpuDescriptorAllocator cbvSrvUav, CpuDescriptorAllocator rtv,
                                         CpuDescriptorAllocator sampler) {
        long[] srvs = new long[5];
        int srvCount = 0;
        long rtvHandle = 0L;
        long samplerHandle = 0L;
        D3D12Resource zero = null;

        try (Arena arena = Arena.ofConfined()) {
            // ---- null SRVs. A null resource needs a description: the format and the view dimension decide which kind of
            // resource the shader may legally declare for this descriptor.
            srvs[srvCount++] = cbvSrvUav.allocate();
            device.createShaderResourceView(null, texture2dSrv(arena), srvs[0]);

            srvs[srvCount++] = cbvSrvUav.allocate();
            device.createShaderResourceView(null, cubeSrv(arena), srvs[1]);

            srvs[srvCount++] = cbvSrvUav.allocate();
            device.createShaderResourceView(null, bufferSrv(arena, Enums.Format.R32_SINT), srvs[2]);

            srvs[srvCount++] = cbvSrvUav.allocate();
            device.createShaderResourceView(null, bufferSrv(arena, Enums.Format.R32_UINT), srvs[3]);

            srvs[srvCount++] = cbvSrvUav.allocate();
            device.createShaderResourceView(null, bufferSrv(arena, Enums.Format.R32_FLOAT), srvs[4]);

            // ---- null RTV (holes in the colour attachment list)
            rtvHandle = rtv.allocate();
            device.createRenderTargetView(null, nullRtv(arena), rtvHandle);

            // ---- default sampler
            samplerHandle = sampler.allocate();
            device.createSampler(defaultSampler(arena), samplerHandle);

            // ---- zero constant buffer
            zero = device.createCommittedBuffer(Enums.HeapType.UPLOAD, ZERO_BUFFER_BYTES, Enums.ResourceFlags.NONE,
                    Enums.ResourceStates.GENERIC_READ);

            MemorySegment mapped = zero.mapForWrite(0, ZERO_BUFFER_BYTES);
            mapped.fill((byte) 0);
            zero.unmap(0, 0L, ZERO_BUFFER_BYTES);

            long va = zero.getGPUVirtualAddress();

            if (va == 0L || (va & 255L) != 0L) {
                throw new IllegalStateException("The zero constant buffer has an unusable GPU address 0x" + Long.toHexString(va));
            }

            return new NullDescriptors(cbvSrvUav, rtv, sampler, srvs, rtvHandle, samplerHandle, zero, va);
        } catch (Throwable t) {
            // return what was built, in reverse order; nothing here may mask the original failure
            if (zero != null) {
                releaseQuietly(zero);
            }

            if (samplerHandle != 0L) {
                freeQuietly(sampler, samplerHandle);
            }

            if (rtvHandle != 0L) {
                freeQuietly(rtv, rtvHandle);
            }

            for (int i = srvCount - 1; i >= 0; i--) {
                freeQuietly(cbvSrvUav, srvs[i]);
            }

            throw t;
        }
    }

    /** @return CPU handle of the null {@code Texture2D} SRV */
    public long srv2d() {
        return this.srv2d;
    }

    /** @return CPU handle of the null {@code TextureCube} SRV */
    public long srvCube() {
        return this.srvCube;
    }

    /**
     * @param sampledBaseType {@code Formats.BASE_INT}, {@code BASE_UINT} or {@code BASE_FLOAT}
     * @return CPU handle of the null typed-buffer SRV of that element class
     * @throws IllegalArgumentException for any other value
     */
    public long srvBuffer(int sampledBaseType) {
        return switch (sampledBaseType) {
            case Formats.BASE_INT -> this.srvBufferInt;
            case Formats.BASE_UINT -> this.srvBufferUint;
            case Formats.BASE_FLOAT -> this.srvBufferFloat;
            default -> throw new IllegalArgumentException("Unsupported sampled base type " + sampledBaseType
                    + " for a null buffer descriptor (expected " + Formats.BASE_INT + ", " + Formats.BASE_UINT + " or "
                    + Formats.BASE_FLOAT + ")");
        };
    }

    /** @return CPU handle of the null RTV */
    public long rtv() {
        return this.rtv;
    }

    /** @return CPU handle of the default sampler descriptor */
    public long sampler() {
        return this.sampler;
    }

    /** @return GPU virtual address (256 aligned, 64 KiB readable) of the zero constant buffer */
    public long zeroCbvVa() {
        return this.zeroCbvVa;
    }

    /** Names the zero buffer for the debug layer / PIX; called by {@code DescriptorServices} when debugging is on. */
    void label() {
        try {
            this.zeroBuffer.setName("Sodium D3D12 zero constant buffer");
        } catch (RuntimeException e) {
            D3D12Log.debug("Could not name the zero constant buffer: {}", e.toString());
        }
    }

    /** Returns the descriptors to their allocators and releases the zero buffer. GPU idle assumed. Idempotent. */
    @Override
    public void close() {
        if (this.closed) {
            return;
        }

        this.closed = true;

        releaseQuietly(this.zeroBuffer);
        freeQuietly(this.samplerAllocator, this.sampler);
        freeQuietly(this.rtvAllocator, this.rtv);
        freeQuietly(this.cbvSrvUav, this.srvBufferFloat);
        freeQuietly(this.cbvSrvUav, this.srvBufferUint);
        freeQuietly(this.cbvSrvUav, this.srvBufferInt);
        freeQuietly(this.cbvSrvUav, this.srvCube);
        freeQuietly(this.cbvSrvUav, this.srv2d);
    }

    private static MemorySegment texture2dSrv(Arena arena) {
        MemorySegment desc = D3D12ShaderResourceViewDesc.allocate(arena);
        D3D12ShaderResourceViewDesc.setFormat(desc, Enums.Format.R8G8B8A8_UNORM);
        D3D12ShaderResourceViewDesc.setViewDimension(desc, Enums.SrvDimension.TEXTURE2D);
        D3D12ShaderResourceViewDesc.setShader4ComponentMapping(desc, DEFAULT_COMPONENT_MAPPING);

        MemorySegment tex = D3D12ShaderResourceViewDesc.sliceTexture2D(desc);
        D3D12Tex2dSrv.setMostDetailedMip(tex, 0);
        D3D12Tex2dSrv.setMipLevels(tex, 1);

        return desc;
    }

    private static MemorySegment cubeSrv(Arena arena) {
        MemorySegment desc = D3D12ShaderResourceViewDesc.allocate(arena);
        D3D12ShaderResourceViewDesc.setFormat(desc, Enums.Format.R8G8B8A8_UNORM);
        D3D12ShaderResourceViewDesc.setViewDimension(desc, Enums.SrvDimension.TEXTURECUBE);
        D3D12ShaderResourceViewDesc.setShader4ComponentMapping(desc, DEFAULT_COMPONENT_MAPPING);

        MemorySegment cube = D3D12ShaderResourceViewDesc.sliceTextureCube(desc);
        D3D12TexcubeSrv.setMostDetailedMip(cube, 0);
        D3D12TexcubeSrv.setMipLevels(cube, 1);

        return desc;
    }

    private static MemorySegment bufferSrv(Arena arena, int format) {
        MemorySegment desc = D3D12ShaderResourceViewDesc.allocate(arena);
        D3D12ShaderResourceViewDesc.setFormat(desc, format);
        D3D12ShaderResourceViewDesc.setViewDimension(desc, Enums.SrvDimension.BUFFER);
        D3D12ShaderResourceViewDesc.setShader4ComponentMapping(desc, DEFAULT_COMPONENT_MAPPING);

        MemorySegment buffer = D3D12ShaderResourceViewDesc.sliceBuffer(desc);
        D3D12BufferSrv.setFirstElement(buffer, 0L);
        D3D12BufferSrv.setNumElements(buffer, 1);
        D3D12BufferSrv.setStructureByteStride(buffer, 0);
        D3D12BufferSrv.setFlags(buffer, Enums.BufferSrvFlags.NONE);

        return desc;
    }

    private static MemorySegment nullRtv(Arena arena) {
        MemorySegment desc = D3D12RenderTargetViewDesc.allocate(arena);
        D3D12RenderTargetViewDesc.setFormat(desc, Enums.Format.R8G8B8A8_UNORM);
        D3D12RenderTargetViewDesc.setViewDimension(desc, Enums.RtvDimension.TEXTURE2D);

        MemorySegment tex = D3D12RenderTargetViewDesc.sliceTexture2D(desc);
        D3D12Tex2dRtv.setMipSlice(tex, 0);
        D3D12Tex2dRtv.setPlaneSlice(tex, 0);

        return desc;
    }

    private static MemorySegment defaultSampler(Arena arena) {
        MemorySegment desc = D3D12SamplerDesc.allocate(arena);
        D3D12SamplerDesc.setFilter(desc, Enums.Filter.MIN_MAG_MIP_POINT);
        D3D12SamplerDesc.setAddressU(desc, Enums.TextureAddressMode.CLAMP);
        D3D12SamplerDesc.setAddressV(desc, Enums.TextureAddressMode.CLAMP);
        D3D12SamplerDesc.setAddressW(desc, Enums.TextureAddressMode.CLAMP);
        D3D12SamplerDesc.setMipLODBias(desc, 0.0F);
        D3D12SamplerDesc.setMaxAnisotropy(desc, 1);
        D3D12SamplerDesc.setComparisonFunc(desc, Enums.ComparisonFunc.NEVER);
        D3D12SamplerDesc.setMinLOD(desc, 0.0F);
        D3D12SamplerDesc.setMaxLOD(desc, 0.0F);

        return desc;
    }

    private static void freeQuietly(CpuDescriptorAllocator allocator, long handle) {
        try {
            allocator.free(handle);
        } catch (RuntimeException e) {
            D3D12Log.warn("Could not free a null descriptor: {}", e.toString());
        }
    }

    private static void releaseQuietly(D3D12Resource resource) {
        try {
            resource.close();
        } catch (RuntimeException e) {
            D3D12Log.warn("Could not release the zero constant buffer: {}", e.toString());
        }
    }
}
