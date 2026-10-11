package net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12;

import java.lang.foreign.MemorySegment;

/**
 * ID3D12CommandSignature: the layout of the argument records consumed by
 * {@link D3D12GraphicsCommandList#executeIndirect}. Created by {@link D3D12Device#createCommandSignature}.
 *
 * <p>Thread safety: immutable once created, free-threaded.
 */
public final class D3D12CommandSignature extends D3D12Object.DeviceChild {
    public D3D12CommandSignature(MemorySegment pointer) {
        super(pointer);
    }
}
