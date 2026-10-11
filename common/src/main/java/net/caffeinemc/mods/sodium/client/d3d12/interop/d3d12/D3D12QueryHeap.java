package net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12;

import java.lang.foreign.MemorySegment;

/**
 * ID3D12QueryHeap: storage for occlusion, timestamp and pipeline statistics query results before they are resolved
 * into a buffer with {@link D3D12GraphicsCommandList#resolveQueryData}. Created by
 * {@link D3D12Device#createQueryHeap}.
 *
 * <p>Thread safety: free-threaded to hold, but queries are recorded through a (single-threaded) command list.
 */
public final class D3D12QueryHeap extends D3D12Object.DeviceChild {
    public D3D12QueryHeap(MemorySegment pointer) {
        super(pointer);
    }
}
