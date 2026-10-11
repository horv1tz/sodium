package net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12;

import net.caffeinemc.mods.sodium.client.d3d12.interop.Com;
import net.caffeinemc.mods.sodium.client.d3d12.interop.Hr;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Enums;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Vtbl;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12HeapProperties;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12Range;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12ResourceDesc;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.DxgiSampleDesc;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * ID3D12Resource: a buffer or texture. Created by {@link D3D12Device#createCommittedResource} /
 * {@link D3D12Device#createPlacedResource} (or the buffer/texture helpers next to them).
 *
 * <p>Thread safety: free-threaded. Mapping the same subresource from several threads is allowed by D3D12 (the map is
 * reference counted) but the caller is responsible for synchronising access to the memory.
 */
public final class D3D12Resource extends D3D12Object.Pageable {
    /** A {@code D3D12_RANGE} of {0, 0}: "nothing read" when mapping, "nothing written" when unmapping. */
    private static final MemorySegment EMPTY_RANGE = D3D12Range.allocate(Arena.global());

    private static final MethodHandle MAP = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle UNMAP = Com.method(FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS));
    private static final MethodHandle GET_DESC = Com.method(FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS));
    private static final MethodHandle GET_GPU_VIRTUAL_ADDRESS = Com.method(FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    private static final MethodHandle GET_HEAP_PROPERTIES = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

    private volatile long gpuVirtualAddress;

    public D3D12Resource(MemorySegment pointer) {
        super(pointer);
    }

    /**
     * Maps a subresource and writes the CPU address of its first byte to {@code dataOut}. Does not allocate.
     *
     * @param subresource the subresource index (0 for buffers)
     * @param readRange   a {@code D3D12_RANGE} ({@link D3D12Range}) with the byte range the CPU may read, an empty range
     *                    ({@code begin == end}) if it will not read, or {@code null} if it may read everything
     * @param dataOut     8 bytes of native memory receiving the {@code void*}
     * @return the raw HRESULT; {@code E_OUTOFMEMORY} means the address space for the mapping could not be reserved
     */
    public int map(int subresource, MemorySegment readRange, MemorySegment dataOut) {
        requireSizeIfPresent(readRange, D3D12Range.SIZE, "readRange");
        requireSize(dataOut, 8, "dataOut");

        try {
            return (int) MAP.invokeExact(this.fn(Vtbl.ID3D12Resource.MAP), this.ptr(), subresource, orNull(readRange), dataOut);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Maps a subresource that the CPU will only write (the usual case for upload heaps). Allocates a few bytes of temporary
     * native memory and queries the resource description: map upload buffers once and keep them mapped, and use the raw
     * {@link #map(int, MemorySegment, MemorySegment)} where that matters.
     *
     * @param size the number of bytes of the mapped region to expose (for a buffer, its width)
     * @return a view of the mapped memory, valid until the matching {@link #unmap}
     * @throws net.caffeinemc.mods.sodium.client.d3d12.interop.D3D12Exception if the map fails
     * @throws IllegalArgumentException if this is a buffer and {@code size} exceeds its width
     */
    public MemorySegment mapForWrite(int subresource, long size) {
        return this.map(subresource, EMPTY_RANGE, size);
    }

    /**
     * Maps a subresource that the CPU may read (readback heaps). The whole subresource is assumed to be read. Allocates, like
     * {@link #mapForWrite(int, long)}.
     *
     * @param size the number of bytes of the mapped region to expose
     * @return a view of the mapped memory, valid until the matching {@link #unmap}
     * @throws net.caffeinemc.mods.sodium.client.d3d12.interop.D3D12Exception if the map fails
     * @throws IllegalArgumentException if this is a buffer and {@code size} exceeds its width
     */
    public MemorySegment mapForRead(int subresource, long size) {
        return this.map(subresource, null, size);
    }

    /**
     * Maps a subresource with an explicit read range. Allocates a few bytes of temporary native memory.
     *
     * @param readBegin first byte the CPU may read; {@code readBegin == readEnd} means nothing is read
     * @param size      the number of bytes of the mapped region to expose
     * @throws IllegalArgumentException if this is a buffer and {@code size} exceeds its width
     */
    public MemorySegment map(int subresource, long readBegin, long readEnd, long size) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment range = D3D12Range.allocate(arena);
            D3D12Range.setBegin(range, readBegin);
            D3D12Range.setEnd(range, readEnd);

            return this.map(subresource, range, size);
        }
    }

    private MemorySegment map(int subresource, MemorySegment readRange, long size) {
        try (Arena arena = Arena.ofConfined()) {
            // the returned view is as large as the caller says; for a buffer, refuse a view that reaches past the end of the
            // resource (writing there would corrupt native memory)
            MemorySegment desc = D3D12ResourceDesc.allocate(arena);
            this.getDesc(desc);

            if (size < 0L || (D3D12ResourceDesc.getDimension(desc) == Enums.ResourceDimension.BUFFER && Long.compareUnsigned(size, D3D12ResourceDesc.getWidth(desc)) > 0)) {
                throw new IllegalArgumentException("Cannot expose " + size + " bytes of a resource that is "
                        + (size < 0L ? "being mapped with a negative size" : D3D12ResourceDesc.getWidth(desc) + " bytes wide"));
            }

            MemorySegment out = arena.allocate(ADDRESS);
            Hr.check(this.map(subresource, readRange, out), "ID3D12Resource::Map");

            return out.get(ADDRESS, 0).reinterpret(size);
        }
    }

    /**
     * Unmaps a subresource (the maps are reference counted). Does not allocate.
     *
     * @param writtenRange a {@code D3D12_RANGE} with the bytes the CPU wrote, an empty range if nothing was written,
     *                     or {@code null} if the whole subresource may have been written
     */
    public void unmap(int subresource, MemorySegment writtenRange) {
        requireSizeIfPresent(writtenRange, D3D12Range.SIZE, "writtenRange");

        try {
            UNMAP.invokeExact(this.fn(Vtbl.ID3D12Resource.UNMAP), this.ptr(), subresource, orNull(writtenRange));
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Unmaps a subresource, declaring that the whole subresource may have been written.
     */
    public void unmap(int subresource) {
        this.unmap(subresource, null);
    }

    /**
     * Unmaps a subresource that was only read (for example a readback buffer), declaring that nothing was written.
     */
    public void unmapUnwritten(int subresource) {
        this.unmap(subresource, EMPTY_RANGE);
    }

    /**
     * Unmaps a subresource, declaring the written byte range. Allocates a few bytes of temporary native memory; use
     * {@link #unmap(int, MemorySegment)} on hot paths.
     */
    public void unmap(int subresource, long writtenBegin, long writtenEnd) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment range = D3D12Range.allocate(arena);
            D3D12Range.setBegin(range, writtenBegin);
            D3D12Range.setEnd(range, writtenEnd);
            this.unmap(subresource, range);
        }
    }

    /**
     * @return the GPU virtual address of a buffer (cached after the first call), or 0 for resources that have none
     * (textures)
     * @throws IllegalStateException if the resource has been released
     */
    public long getGPUVirtualAddress() {
        // fails for a released resource even when the address is cached
        this.ptr();

        long address = this.gpuVirtualAddress;

        if (address == 0L) {
            try {
                address = (long) GET_GPU_VIRTUAL_ADDRESS.invokeExact(this.fn(Vtbl.ID3D12Resource.GET_GPU_VIRTUAL_ADDRESS), this.ptr());
            } catch (Throwable t) {
                throw Com.rethrow(t);
            }

            this.gpuVirtualAddress = address;
        }

        return address;
    }

    /**
     * Fills a {@code D3D12_RESOURCE_DESC} (see {@link D3D12ResourceDesc}). Does not allocate.
     *
     * @param out at least {@link D3D12ResourceDesc#SIZE} bytes of native memory
     */
    public void getDesc(MemorySegment out) {
        requireSize(out, D3D12ResourceDesc.SIZE, "out");

        try {
            long ignored = (long) GET_DESC.invokeExact(this.fn(Vtbl.ID3D12Resource.GET_DESC), this.ptr(), out);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Decoded {@code D3D12_RESOURCE_DESC}.
     *
     * @param dimension        {@code D3D12_RESOURCE_DIMENSION} ({@code Enums.ResourceDimension})
     * @param alignment        placement alignment in bytes
     * @param width            width in texels, or the size in bytes for a buffer
     * @param height           height in texels
     * @param depthOrArraySize depth for 3D textures, array size otherwise
     * @param mipLevels        number of mip levels
     * @param format           a {@code DXGI_FORMAT} ({@code Enums.Format})
     * @param sampleCount      MSAA sample count
     * @param sampleQuality    MSAA quality level
     * @param layout           {@code D3D12_TEXTURE_LAYOUT} ({@code Enums.TextureLayout})
     * @param flags            {@code D3D12_RESOURCE_FLAGS} ({@code Enums.ResourceFlags})
     */
    public record Desc(int dimension, long alignment, long width, int height, int depthOrArraySize, int mipLevels,
                       int format, int sampleCount, int sampleQuality, int layout, int flags) {
    }

    /**
     * Allocates a temporary native struct; use {@link #getDesc(MemorySegment)} on hot paths.
     */
    public Desc getDesc() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment desc = D3D12ResourceDesc.allocate(arena);
            this.getDesc(desc);

            MemorySegment sample = D3D12ResourceDesc.sliceSampleDesc(desc);

            return new Desc(D3D12ResourceDesc.getDimension(desc), D3D12ResourceDesc.getAlignment(desc),
                    D3D12ResourceDesc.getWidth(desc), D3D12ResourceDesc.getHeight(desc),
                    D3D12ResourceDesc.getDepthOrArraySize(desc) & 0xFFFF, D3D12ResourceDesc.getMipLevels(desc) & 0xFFFF,
                    D3D12ResourceDesc.getFormat(desc), DxgiSampleDesc.getCount(sample), DxgiSampleDesc.getQuality(sample),
                    D3D12ResourceDesc.getLayout(desc), D3D12ResourceDesc.getFlags(desc));
        }
    }

    /**
     * Retrieves the heap properties and flags a committed resource was created with. Does not allocate.
     *
     * @param propertiesOut at least {@link D3D12HeapProperties#SIZE} bytes of native memory
     * @param flagsOut      4 bytes of native memory receiving the {@code D3D12_HEAP_FLAGS}
     * @return the raw HRESULT; {@code E_INVALIDARG} for placed and reserved resources, which have no committed heap
     */
    public int getHeapProperties(MemorySegment propertiesOut, MemorySegment flagsOut) {
        requireSizeIfPresent(propertiesOut, D3D12HeapProperties.SIZE, "propertiesOut");
        requireSizeIfPresent(flagsOut, 4, "flagsOut");

        try {
            return (int) GET_HEAP_PROPERTIES.invokeExact(this.fn(Vtbl.ID3D12Resource.GET_HEAP_PROPERTIES), this.ptr(),
                    orNull(propertiesOut), orNull(flagsOut));
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Decoded heap properties of a committed resource.
     *
     * @param type                 {@code D3D12_HEAP_TYPE} ({@code Enums.HeapType})
     * @param cpuPageProperty      {@code D3D12_CPU_PAGE_PROPERTY}
     * @param memoryPoolPreference {@code D3D12_MEMORY_POOL}
     * @param creationNodeMask     GPU node mask
     * @param visibleNodeMask      GPU node mask
     * @param heapFlags            {@code D3D12_HEAP_FLAGS} ({@code Enums.HeapFlags})
     */
    public record HeapInfo(int type, int cpuPageProperty, int memoryPoolPreference, int creationNodeMask,
                           int visibleNodeMask, int heapFlags) {
    }

    /**
     * @throws net.caffeinemc.mods.sodium.client.d3d12.interop.D3D12Exception if the resource is not committed
     */
    public HeapInfo getHeapProperties() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment props = D3D12HeapProperties.allocate(arena);
            MemorySegment flags = arena.allocate(JAVA_INT);
            Hr.check(this.getHeapProperties(props, flags), "ID3D12Resource::GetHeapProperties");

            return new HeapInfo(D3D12HeapProperties.getType(props), D3D12HeapProperties.getCPUPageProperty(props),
                    D3D12HeapProperties.getMemoryPoolPreference(props), D3D12HeapProperties.getCreationNodeMask(props),
                    D3D12HeapProperties.getVisibleNodeMask(props), flags.get(JAVA_INT, 0));
        }
    }
}
