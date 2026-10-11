package net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12;

import net.caffeinemc.mods.sodium.client.d3d12.interop.Com;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Vtbl;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12DescriptorHeapDesc;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * ID3D12DescriptorHeap: a table of descriptors (RTV, DSV, CBV/SRV/UAV or sampler). Created by
 * {@link D3D12Device#createDescriptorHeap}.
 *
 * <p>The descriptor handles are plain addresses/offsets: the handle of descriptor {@code i} is
 * {@code start + i * incrementSize}, see {@link #handleAt(long, int, int)}.
 *
 * <p>Thread safety: free-threaded; the handles returned are immutable for the lifetime of the heap and are cached
 * with safe publication, so any thread may ask for them.
 */
public final class D3D12DescriptorHeap extends D3D12Object.Pageable {
    private static final MethodHandle GET_DESC = Com.method(FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS));
    private static final MethodHandle GET_HANDLE = Com.method(FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS));

    // The "known" flags are volatile and written after the handle, so a thread that sees a flag also sees its handle.
    private long cpuStart;
    private long gpuStart;
    private volatile boolean cpuStartKnown;
    private volatile boolean gpuStartKnown;

    public D3D12DescriptorHeap(MemorySegment pointer) {
        super(pointer);
    }

    /**
     * Fills a {@code D3D12_DESCRIPTOR_HEAP_DESC} (see {@link D3D12DescriptorHeapDesc}). Does not allocate.
     *
     * @param out at least {@link D3D12DescriptorHeapDesc#SIZE} bytes of native memory
     */
    public void getDesc(MemorySegment out) {
        requireSize(out, D3D12DescriptorHeapDesc.SIZE, "out");

        try {
            long ignored = (long) GET_DESC.invokeExact(this.fn(Vtbl.ID3D12DescriptorHeap.GET_DESC), this.ptr(), out);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Decoded {@code D3D12_DESCRIPTOR_HEAP_DESC}.
     *
     * @param type           a {@code D3D12_DESCRIPTOR_HEAP_TYPE} ({@code Enums.DescriptorHeapType})
     * @param numDescriptors capacity of the heap
     * @param flags          {@code D3D12_DESCRIPTOR_HEAP_FLAGS} ({@code Enums.DescriptorHeapFlags})
     * @param nodeMask       the GPU node mask
     */
    public record Desc(int type, int numDescriptors, int flags, int nodeMask) {
    }

    /**
     * Reads the description of this heap. Allocates temporary native memory; use {@link #getDesc(MemorySegment)} to avoid that.
     */
    public Desc getDesc() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment desc = D3D12DescriptorHeapDesc.allocate(arena);
            this.getDesc(desc);

            return new Desc(D3D12DescriptorHeapDesc.getType(desc), D3D12DescriptorHeapDesc.getNumDescriptors(desc),
                    D3D12DescriptorHeapDesc.getFlags(desc), D3D12DescriptorHeapDesc.getNodeMask(desc));
        }
    }

    /**
     * @return the {@code D3D12_CPU_DESCRIPTOR_HANDLE} of the first descriptor (cached after the first call)
     * @throws IllegalStateException if the heap has been released
     */
    public long getCPUDescriptorHandleForHeapStart() {
        this.ptr();

        if (!this.cpuStartKnown) {
            this.cpuStart = this.queryHandle(Vtbl.ID3D12DescriptorHeap.GET_CPU_DESCRIPTOR_HANDLE_FOR_HEAP_START);
            this.cpuStartKnown = true;
        }

        return this.cpuStart;
    }

    /**
     * @return the {@code D3D12_GPU_DESCRIPTOR_HANDLE} of the first descriptor, or 0 if the heap is not shader visible
     * (cached after the first call)
     * @throws IllegalStateException if the heap has been released
     */
    public long getGPUDescriptorHandleForHeapStart() {
        this.ptr();

        if (!this.gpuStartKnown) {
            this.gpuStart = this.queryHandle(Vtbl.ID3D12DescriptorHeap.GET_GPU_DESCRIPTOR_HANDLE_FOR_HEAP_START);
            this.gpuStartKnown = true;
        }

        return this.gpuStart;
    }

    private long queryHandle(int slot) {
        try (Arena arena = Arena.ofConfined()) {
            // the header declares these as "D3D12_xxx_DESCRIPTOR_HANDLE *GetXxx(This, D3D12_xxx_DESCRIPTOR_HANDLE *ret)"
            MemorySegment ret = arena.allocate(JAVA_LONG);
            long ignored = (long) GET_HANDLE.invokeExact(this.fn(slot), this.ptr(), ret);

            return ret.get(JAVA_LONG, 0);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Computes the handle of descriptor {@code index} of a heap.
     *
     * @param start         {@link #getCPUDescriptorHandleForHeapStart()} or {@link #getGPUDescriptorHandleForHeapStart()}
     * @param index         descriptor index
     * @param incrementSize {@link D3D12Device#getDescriptorHandleIncrementSize(int)} for this heap's type
     */
    public static long handleAt(long start, int index, int incrementSize) {
        return start + (long) index * (long) incrementSize;
    }
}
