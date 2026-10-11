package net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12;

import net.caffeinemc.mods.sodium.client.d3d12.interop.Com;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Vtbl;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12HeapDesc;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * ID3D12Heap: a block of GPU memory that placed resources can be created in
 * ({@link D3D12Device#createPlacedResource}). Created by {@link D3D12Device#createHeap}.
 *
 * <p>Thread safety: free-threaded.
 */
public final class D3D12Heap extends D3D12Object.DeviceChild {
    private static final MethodHandle GET_DESC = Com.method(FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS));

    public D3D12Heap(MemorySegment pointer) {
        super(pointer);
    }

    /**
     * Fills a {@code D3D12_HEAP_DESC} (see {@link D3D12HeapDesc}) describing this heap. Does not allocate.
     *
     * @param out at least {@link D3D12HeapDesc#SIZE} bytes of native memory
     */
    public void getDesc(MemorySegment out) {
        try {
            // the method returns its hidden result pointer, which is not needed
            long ignored = (long) GET_DESC.invokeExact(this.fn(Vtbl.ID3D12Heap.GET_DESC), this.ptr(), out);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * @return the size of the heap in bytes
     */
    public long getSizeInBytes() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment desc = D3D12HeapDesc.allocate(arena);
            this.getDesc(desc);

            return D3D12HeapDesc.getSizeInBytes(desc);
        }
    }
}
