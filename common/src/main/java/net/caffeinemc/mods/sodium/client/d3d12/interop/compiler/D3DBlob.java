package net.caffeinemc.mods.sodium.client.d3d12.interop.compiler;

import net.caffeinemc.mods.sodium.client.d3d12.interop.Com;
import net.caffeinemc.mods.sodium.client.d3d12.interop.ComObject;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Vtbl;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * ID3DBlob: an immutable byte buffer owned by native code (compiled shader bytecode, serialized root signatures, error text).
 */
public final class D3DBlob extends ComObject {
    private static final MethodHandle GET_BUFFER_POINTER = Com.method(FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle GET_BUFFER_SIZE = Com.method(FunctionDescriptor.of(JAVA_LONG, ADDRESS));

    public D3DBlob(MemorySegment pointer) {
        super(pointer);
    }

    public long size() {
        try {
            return (long) GET_BUFFER_SIZE.invokeExact(this.fn(Vtbl.ID3D10Blob.GET_BUFFER_SIZE), this.ptr());
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * @return a view of the blob's memory, valid until this blob is released
     */
    public MemorySegment data() {
        try {
            MemorySegment pointer = (MemorySegment) GET_BUFFER_POINTER.invokeExact(this.fn(Vtbl.ID3D10Blob.GET_BUFFER_POINTER), this.ptr());

            return pointer.reinterpret(this.size());
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * @return a copy of the blob's contents that stays valid after the blob is released
     */
    public byte[] toByteArray() {
        return this.data().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
    }

    /**
     * @return a direct buffer viewing the blob's memory, valid until this blob is released
     */
    public ByteBuffer asByteBuffer() {
        return this.data().asByteBuffer();
    }

    /**
     * Interprets the blob as text (D3DCompile error messages are ASCII/UTF-8).
     */
    public String asString() {
        return new String(this.toByteArray(), StandardCharsets.UTF_8).stripTrailing().replace("\0", "");
    }
}
