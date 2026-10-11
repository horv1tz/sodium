package net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12;

import net.caffeinemc.mods.sodium.client.d3d12.interop.Com;
import net.caffeinemc.mods.sodium.client.d3d12.interop.ComObject;
import net.caffeinemc.mods.sodium.client.d3d12.interop.Hr;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Iids;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Vtbl;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Base class of every wrapper around an interface derived from {@code ID3D12Object} (vtable slots 0..6: IUnknown plus
 * the private-data and name methods).
 *
 * <p>All wrappers in this package follow the same rules:
 * <ul>
 *     <li>The public constructor takes the raw interface pointer and takes ownership of one reference, which is
 *     dropped by {@link #release()} / {@link #close()}.</li>
 *     <li>Methods throw {@link net.caffeinemc.mods.sodium.client.d3d12.interop.D3D12Exception} (through
 *     {@link Hr#check(int, String)}) when the native call fails, except where the caller has to react to a specific
 *     HRESULT. Those methods return the raw {@code int} HRESULT and say so in their documentation.</li>
 *     <li>Struct arguments are passed as native {@link MemorySegment}s laid out as described by the generated
 *     {@code abi.structs} accessors. {@code null} and {@link MemorySegment#NULL} are both accepted wherever a pointer
 *     parameter is documented as optional.</li>
 *     <li>Descriptor handles ({@code D3D12_CPU_DESCRIPTOR_HANDLE} / {@code D3D12_GPU_DESCRIPTOR_HANDLE}) are passed
 *     as {@code long}.</li>
 * </ul>
 */
public abstract class D3D12Object extends ComObject {
    private static final MethodHandle SET_NAME = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle GET_DEVICE = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

    protected D3D12Object(MemorySegment pointer) {
        super(pointer);
    }

    /**
     * Assigns a debug name to the object (shown by the debug layer, PIX and RenderDoc). Allocates; meant for object
     * creation time, not for per-frame use.
     *
     * <p>Thread safety: free-threaded.
     */
    public final void setName(String name) {
        try (Arena arena = Arena.ofConfined()) {
            // allocateFrom appends the two-byte UTF-16 terminator that SetName(const WCHAR *) requires
            MemorySegment chars = arena.allocateFrom(name, StandardCharsets.UTF_16LE);
            int hr = (int) SET_NAME.invokeExact(this.fn(Vtbl.ID3D12Object.SET_NAME), this.ptr(), chars);
            Hr.check(hr, "ID3D12Object::SetName");
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * @return the pointer for {@code object}, or {@link MemorySegment#NULL} if it is {@code null}
     */
    static MemorySegment ptrOrNull(ComObject object) {
        return object == null ? MemorySegment.NULL : object.ptr();
    }

    /**
     * Rejects argument memory that is smaller than what the native call reads or writes. A segment that is too small
     * would otherwise turn into an out-of-bounds native access (heap corruption or an access violation that takes the
     * whole process down). Segments of raw pointers must be given their size first ({@code reinterpret}).
     *
     * @param required the number of bytes the call needs from {@code segment}
     * @param what     the parameter name for the message
     * @throws IllegalArgumentException if the segment is {@code null} or smaller than {@code required}
     */
    static void requireSize(MemorySegment segment, long required, String what) {
        if (segment == null || segment.byteSize() < required) {
            throw sizeError(segment, required, what);
        }
    }

    /**
     * Like {@link #requireSize} for an optional parameter: {@code null} and {@link MemorySegment#NULL} pass.
     */
    static void requireSizeIfPresent(MemorySegment segment, long required, String what) {
        if (segment != null && segment.address() != 0L && segment.byteSize() < required) {
            throw sizeError(segment, required, what);
        }
    }

    /**
     * Checks a (count, array of {@code stride}-byte elements) pair. A count of 0 accepts any segment ({@link MemorySegment#NULL} for
     * "no array").
     *
     * @throws IllegalArgumentException if {@code count} is negative or the array is too small
     */
    static void requireArray(int count, MemorySegment array, long stride, String what) {
        if (count < 0) {
            throw new IllegalArgumentException(what + ": negative element count " + count);
        }

        if (count > 0) {
            requireSize(array, stride * count, what);
        }
    }

    /**
     * Like {@link #requireArray} for an optional array.
     */
    static void requireArrayIfPresent(int count, MemorySegment array, long stride, String what) {
        if (count < 0) {
            throw new IllegalArgumentException(what + ": negative element count " + count);
        }

        if (count > 0) {
            requireSizeIfPresent(array, stride * count, what);
        }
    }

    private static IllegalArgumentException sizeError(MemorySegment segment, long required, String what) {
        return new IllegalArgumentException(what + " must be native memory of at least " + required + " bytes, but is "
                + (segment == null ? "null" : segment.byteSize() + " bytes"));
    }

    /**
     * @return {@code segment}, or {@link MemorySegment#NULL} if it is {@code null}
     */
    static MemorySegment orNull(MemorySegment segment) {
        return segment == null ? MemorySegment.NULL : segment;
    }

    /**
     * Base class of the interfaces derived from {@code ID3D12DeviceChild} (everything except the device itself):
     * vtable slot 7 is {@code GetDevice}.
     */
    public abstract static class DeviceChild extends D3D12Object {
        protected DeviceChild(MemorySegment pointer) {
            super(pointer);
        }

        /**
         * @return a new wrapper (owning its own reference) for the device that created this object
         */
        public final D3D12Device getDevice() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment out = arena.allocate(ADDRESS);
                int hr = (int) GET_DEVICE.invokeExact(this.fn(Vtbl.ID3D12DeviceChild.GET_DEVICE), this.ptr(),
                        Iids.ID3D12Device, out);
                Hr.check(hr, "ID3D12DeviceChild::GetDevice");

                return new D3D12Device(out.get(ADDRESS, 0));
            } catch (Throwable t) {
                throw Com.rethrow(t);
            }
        }
    }

    /**
     * Base class of the interfaces derived from {@code ID3D12Pageable}: the objects that occupy GPU-accessible memory and
     * can therefore be passed to {@link D3D12Device#makeResident(Pageable)} / {@link D3D12Device#evict(Pageable)} (heaps,
     * resources, descriptor heaps, query heaps, pipeline states, command signatures, command allocators, command
     * queues and fences). Root signatures and command lists are device children but not pageable.
     */
    public abstract static class Pageable extends DeviceChild {
        protected Pageable(MemorySegment pointer) {
            super(pointer);
        }
    }
}
