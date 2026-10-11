package net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12;

import java.lang.foreign.MemorySegment;

/**
 * ID3D12RootSignature: the layout of the resources a pipeline reads. Created by
 * {@link D3D12Device#createRootSignature(net.caffeinemc.mods.sodium.client.d3d12.interop.compiler.D3DBlob)}.
 *
 * <p>Thread safety: immutable once created, free-threaded.
 */
public final class D3D12RootSignature extends D3D12Object.DeviceChild {
    public D3D12RootSignature(MemorySegment pointer) {
        super(pointer);
    }
}
