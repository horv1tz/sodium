package net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12;

import net.caffeinemc.mods.sodium.client.d3d12.interop.Com;
import net.caffeinemc.mods.sodium.client.d3d12.interop.Hr;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Vtbl;
import net.caffeinemc.mods.sodium.client.d3d12.interop.compiler.D3DBlob;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * ID3D12PipelineState: a compiled graphics or compute pipeline. Created by
 * {@link D3D12Device#createGraphicsPipelineState} / {@link D3D12Device#createComputePipelineState}.
 *
 * <p>Thread safety: immutable once created, free-threaded.
 */
public final class D3D12PipelineState extends D3D12Object.Pageable {
    private static final MethodHandle GET_CACHED_BLOB = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    public D3D12PipelineState(MemorySegment pointer) {
        super(pointer);
    }

    /**
     * Retrieves the driver-specific compiled form of the pipeline, which can later be passed back through
     * {@code D3D12_GRAPHICS_PIPELINE_STATE_DESC.CachedPSO} to skip shader compilation.
     *
     * @return a new blob (owned by the caller)
     * @throws net.caffeinemc.mods.sodium.client.d3d12.interop.D3D12Exception if the driver cannot provide a blob
     */
    public D3DBlob getCachedBlob() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            int hr = (int) GET_CACHED_BLOB.invokeExact(this.fn(Vtbl.ID3D12PipelineState.GET_CACHED_BLOB), this.ptr(), out);
            Hr.check(hr, "ID3D12PipelineState::GetCachedBlob");

            return new D3DBlob(out.get(ADDRESS, 0));
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }
}
