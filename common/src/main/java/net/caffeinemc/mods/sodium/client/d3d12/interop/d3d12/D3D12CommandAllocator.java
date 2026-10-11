package net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12;

import net.caffeinemc.mods.sodium.client.d3d12.interop.Com;
import net.caffeinemc.mods.sodium.client.d3d12.interop.Hr;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Vtbl;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * ID3D12CommandAllocator: the memory behind recorded command lists.
 *
 * <p>Thread safety: an allocator must not be used by several threads at once, and may back only one command list in
 * the recording state at a time.
 */
public final class D3D12CommandAllocator extends D3D12Object.DeviceChild {
    private static final MethodHandle RESET = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS));

    public D3D12CommandAllocator(MemorySegment pointer) {
        super(pointer);
    }

    /**
     * Reclaims the allocator's memory. The GPU must have finished executing every command list recorded with this
     * allocator (track that with a fence), otherwise the runtime reports a failure.
     *
     * @throws net.caffeinemc.mods.sodium.client.d3d12.interop.D3D12Exception if the call fails
     */
    public void reset() {
        try {
            int hr = (int) RESET.invokeExact(this.fn(Vtbl.ID3D12CommandAllocator.RESET), this.ptr());
            Hr.check(hr, "ID3D12CommandAllocator::Reset");
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }
}
