package net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12;

import net.caffeinemc.mods.sodium.client.d3d12.interop.Com;
import net.caffeinemc.mods.sodium.client.d3d12.interop.D3D12Exception;
import net.caffeinemc.mods.sodium.client.d3d12.interop.Hr;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Defines;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Enums;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Iids;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Vtbl;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12Box;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12IndexBufferView;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12PlacedSubresourceFootprint;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12ResourceBarrier;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12ResourceTransitionBarrier;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12SubresourceFootprint;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12TextureCopyLocation;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12VertexBufferView;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12Viewport;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.Rect;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * ID3D12GraphicsCommandList: records GPU commands. Created by {@link D3D12Device#createCommandList}.
 *
 * <p>Every command is available in two forms:
 * <ul>
 *     <li>a <i>raw</i> form taking caller-provided native structs ({@code D3D12_VIEWPORT[]}, {@code D3D12_RESOURCE_BARRIER[]},
 *     ...), which is the form to use for batches; and</li>
 *     <li>for the most frequent single-item commands (one viewport, one scissor rectangle, one barrier, one vertex
 *     buffer, clears, root constants...) a <i>convenience</i> form taking primitive arguments, which fills a small
 *     scratch area owned by this wrapper.</li>
 * </ul>
 * Neither form allocates while recording (the string overloads of the debug markers are the only exception). D3D12
 * copies all argument data during the call, so the structs may be reused immediately.
 *
 * <p>Handles: CPU/GPU descriptor handles and GPU virtual addresses are passed as {@code long}; an optional
 * descriptor handle is expressed as {@link #NO_DESCRIPTOR} (0).
 *
 * <p>Thread safety: <b>not</b> thread safe. A command list (and its scratch area) may be used by one thread at a time,
 * and only while it is in the recording state; the allocator it records into must not be reset until the GPU has
 * finished executing the list.
 *
 * <p>Naming exception: the COM method {@code Close} is exposed as {@link #closeList()}, because {@code close()} is
 * the {@link AutoCloseable} method that releases the wrapper's reference.
 */
public final class D3D12GraphicsCommandList extends D3D12Object.DeviceChild {
    /** Passed where an optional CPU descriptor handle is not provided. */
    public static final long NO_DESCRIPTOR = 0L;

    /** {@code D3D12_RESOURCE_BARRIER_ALL_SUBRESOURCES}. */
    public static final int ALL_SUBRESOURCES = Defines.D3D12_RESOURCE_BARRIER_ALL_SUBRESOURCES;

    // Layout of the per-list scratch area used by the convenience overloads.
    private static final long SCRATCH_SIZE = 512;
    private static final long S_LOCATION_DST = 0;                                       // D3D12_TEXTURE_COPY_LOCATION (48)
    private static final long S_LOCATION_SRC = 48;                                      // D3D12_TEXTURE_COPY_LOCATION (48)
    private static final long S_BARRIER = 96;                                           // D3D12_RESOURCE_BARRIER (32)
    private static final long S_VIEWPORT = 128;                                         // D3D12_VIEWPORT (24)
    private static final long S_RECT = 160;                                             // RECT (16)
    private static final long S_COLOR = 176;                                            // float[4] (16)
    private static final long S_BUFFER_VIEW = 192;                                      // vertex/index buffer view (16)
    private static final long S_POINTERS = 208;                                         // up to two pointers (16)
    private static final long S_HANDLES = 224;                                          // up to two descriptor handles (16)

    private static final MethodHandle QUERY_INTERFACE = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle RELEASE = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS));

    private static final MethodHandle GET_TYPE = cmd(FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle CLOSE = cmd(FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle RESET = cmd(FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle V_P = cmd(FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    private static final MethodHandle V_PP = cmd(FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle V_I = cmd(FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
    private static final MethodHandle V_IP = cmd(FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS));
    private static final MethodHandle V_NONE = cmd(FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle DRAW_INSTANCED = cmd(FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle DRAW_INDEXED_INSTANCED = cmd(FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle DISPATCH = cmd(FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle COPY_BUFFER_REGION = cmd(FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_LONG));
    private static final MethodHandle COPY_TEXTURE_REGION = cmd(FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle RESOLVE_SUBRESOURCE = cmd(FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle SET_ROOT_LONG = cmd(FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_LONG));
    private static final MethodHandle SET_ROOT_32BIT_CONSTANT = cmd(FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle SET_ROOT_32BIT_CONSTANTS = cmd(FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle V_IIP = cmd(FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
    private static final MethodHandle OM_SET_RENDER_TARGETS = cmd(FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
    private static final MethodHandle CLEAR_DEPTH_STENCIL_VIEW = cmd(FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, JAVA_INT, JAVA_FLOAT, JAVA_BYTE, JAVA_INT, ADDRESS));
    private static final MethodHandle CLEAR_RENDER_TARGET_VIEW = cmd(FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS, JAVA_INT, ADDRESS));
    private static final MethodHandle CLEAR_UNORDERED_ACCESS_VIEW = cmd(FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, JAVA_LONG, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
    private static final MethodHandle QUERY = cmd(FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle RESOLVE_QUERY_DATA = cmd(FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_LONG));
    private static final MethodHandle SET_PREDICATION = cmd(FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT));
    private static final MethodHandle MARKER = cmd(FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle EXECUTE_INDIRECT = cmd(FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG));
    private static final MethodHandle OM_SET_DEPTH_BOUNDS = cmd(FunctionDescriptor.ofVoid(ADDRESS, JAVA_FLOAT, JAVA_FLOAT));

    private final MemorySegment scratch = Arena.ofAuto().allocate(SCRATCH_SIZE, 16);

    /** 0 = not queried yet, 1 = ID3D12GraphicsCommandList1 methods usable, 2 = not supported. */
    private int list1State;

    public D3D12GraphicsCommandList(MemorySegment pointer) {
        super(pointer);
    }

    /**
     * Single place that creates the handles of the recording methods, which are hot: to try the cheaper
     * {@code Linker.Option.critical} calling convention for all of them, change only this method.
     */
    private static MethodHandle cmd(FunctionDescriptor descriptor) {
        return Com.method(descriptor);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------------------------------------------------

    /**
     * @return the {@code D3D12_COMMAND_LIST_TYPE} ({@code Enums.CommandListType}) this list was created with
     */
    public int getType() {
        try {
            return (int) GET_TYPE.invokeExact(this.fn(Vtbl.ID3D12GraphicsCommandList.GET_TYPE), this.ptr());
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Ends recording (the COM method {@code Close}); the list can then be passed to
     * {@link D3D12CommandQueue#executeCommandLists(D3D12GraphicsCommandList)} or {@link #reset} again.
     *
     * @throws D3D12Exception if recording left the list in an invalid state (the debug layer says why)
     */
    public void closeList() {
        try {
            int hr = (int) CLOSE.invokeExact(this.fn(Vtbl.ID3D12GraphicsCommandList.CLOSE), this.ptr());
            Hr.check(hr, "ID3D12GraphicsCommandList::Close");
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Returns a closed list to the recording state, recording into {@code allocator}.
     *
     * @param initialState the pipeline state to start with, or {@code null}
     * @throws D3D12Exception if the call fails
     */
    public void reset(D3D12CommandAllocator allocator, D3D12PipelineState initialState) {
        try {
            int hr = (int) RESET.invokeExact(this.fn(Vtbl.ID3D12GraphicsCommandList.RESET), this.ptr(), allocator.ptr(),
                    D3D12Object.ptrOrNull(initialState));
            Hr.check(hr, "ID3D12GraphicsCommandList::Reset");
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * {@link #reset(D3D12CommandAllocator, D3D12PipelineState)} without an initial pipeline state.
     */
    public void reset(D3D12CommandAllocator allocator) {
        this.reset(allocator, null);
    }

    /**
     * Resets all state of the list to the defaults.
     *
     * @param pipelineState the initial pipeline state, or {@code null}
     */
    public void clearState(D3D12PipelineState pipelineState) {
        try {
            V_P.invokeExact(this.fn(Vtbl.ID3D12GraphicsCommandList.CLEAR_STATE), this.ptr(), D3D12Object.ptrOrNull(pipelineState));
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Draw / dispatch
    // ------------------------------------------------------------------------------------------------------------

    public void drawInstanced(int vertexCountPerInstance, int instanceCount, int startVertexLocation, int startInstanceLocation) {
        try {
            DRAW_INSTANCED.invokeExact(this.fn(Vtbl.ID3D12GraphicsCommandList.DRAW_INSTANCED), this.ptr(),
                    vertexCountPerInstance, instanceCount, startVertexLocation, startInstanceLocation);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    public void drawIndexedInstanced(int indexCountPerInstance, int instanceCount, int startIndexLocation, int baseVertexLocation, int startInstanceLocation) {
        try {
            DRAW_INDEXED_INSTANCED.invokeExact(this.fn(Vtbl.ID3D12GraphicsCommandList.DRAW_INDEXED_INSTANCED), this.ptr(),
                    indexCountPerInstance, instanceCount, startIndexLocation, baseVertexLocation, startInstanceLocation);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    public void dispatch(int threadGroupCountX, int threadGroupCountY, int threadGroupCountZ) {
        try {
            DISPATCH.invokeExact(this.fn(Vtbl.ID3D12GraphicsCommandList.DISPATCH), this.ptr(),
                    threadGroupCountX, threadGroupCountY, threadGroupCountZ);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Executes the commands described by argument records in a buffer.
     *
     * @param commandSignature the layout of the argument records
     * @param maxCommandCount  the maximum number of records to execute (or the exact count if {@code countBuffer} is
     *                         {@code null})
     * @param argumentBuffer   buffer in {@code INDIRECT_ARGUMENT} state holding the records
     * @param countBuffer      buffer holding a 32-bit actual command count, or {@code null}
     */
    public void executeIndirect(D3D12CommandSignature commandSignature, int maxCommandCount, D3D12Resource argumentBuffer,
                                long argumentBufferOffset, D3D12Resource countBuffer, long countBufferOffset) {
        try {
            EXECUTE_INDIRECT.invokeExact(this.fn(Vtbl.ID3D12GraphicsCommandList.EXECUTE_INDIRECT), this.ptr(),
                    commandSignature.ptr(), maxCommandCount, argumentBuffer.ptr(), argumentBufferOffset,
                    D3D12Object.ptrOrNull(countBuffer), countBufferOffset);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Copies
    // ------------------------------------------------------------------------------------------------------------

    public void copyBufferRegion(D3D12Resource dstBuffer, long dstOffset, D3D12Resource srcBuffer, long srcOffset, long byteCount) {
        try {
            COPY_BUFFER_REGION.invokeExact(this.fn(Vtbl.ID3D12GraphicsCommandList.COPY_BUFFER_REGION), this.ptr(),
                    dstBuffer.ptr(), dstOffset, srcBuffer.ptr(), srcOffset, byteCount);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Raw form of CopyTextureRegion.
     *
     * @param dst    a {@code D3D12_TEXTURE_COPY_LOCATION} ({@link D3D12TextureCopyLocation})
     * @param src    a {@code D3D12_TEXTURE_COPY_LOCATION}
     * @param srcBox a {@code D3D12_BOX} ({@link D3D12Box}) limiting the source region, or {@code null} for all of it
     */
    public void copyTextureRegion(MemorySegment dst, int dstX, int dstY, int dstZ, MemorySegment src, MemorySegment srcBox) {
        try {
            COPY_TEXTURE_REGION.invokeExact(this.fn(Vtbl.ID3D12GraphicsCommandList.COPY_TEXTURE_REGION), this.ptr(),
                    dst, dstX, dstY, dstZ, src, D3D12Object.orNull(srcBox));
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Copies the rows of a buffer (laid out as a placed footprint) into a texture subresource.
     *
     * @param dstTexture     the destination texture, in {@code COPY_DEST} state
     * @param dstSubresource subresource index (mip + array slice * mip count)
     * @param srcBuffer      the source buffer, in {@code COPY_SOURCE} (or {@code GENERIC_READ}) state
     * @param srcOffset      byte offset of the first row in the buffer ({@code D3D12_TEXTURE_DATA_PLACEMENT_ALIGNMENT}-aligned)
     * @param format         the {@code DXGI_FORMAT} of the data in the buffer
     * @param width          width of the data in texels
     * @param height         height of the data in texels
     * @param depth          depth of the data in texels
     * @param rowPitch       bytes between rows ({@code D3D12_TEXTURE_DATA_PITCH_ALIGNMENT}-aligned)
     * @param srcBox         region of the buffer data to copy ({@link D3D12Box}), or {@code null} for all of it
     */
    public void copyBufferToTexture(D3D12Resource dstTexture, int dstSubresource, int dstX, int dstY, int dstZ,
                                    D3D12Resource srcBuffer, long srcOffset, int format, int width, int height, int depth,
                                    int rowPitch, MemorySegment srcBox) {
        MemorySegment dst = this.scratch.asSlice(S_LOCATION_DST, D3D12TextureCopyLocation.SIZE);
        MemorySegment src = this.scratch.asSlice(S_LOCATION_SRC, D3D12TextureCopyLocation.SIZE);

        fillSubresourceLocation(dst, dstTexture, dstSubresource);
        fillPlacedLocation(src, srcBuffer, srcOffset, format, width, height, depth, rowPitch);

        this.copyTextureRegion(dst, dstX, dstY, dstZ, src, srcBox);
    }

    /**
     * Copies a texture subresource into a buffer, laid out as a placed footprint.
     *
     * @param dstBuffer      the destination buffer, in {@code COPY_DEST} state
     * @param dstOffset      byte offset of the first row in the buffer ({@code D3D12_TEXTURE_DATA_PLACEMENT_ALIGNMENT}-aligned)
     * @param format         the {@code DXGI_FORMAT} of the data written to the buffer
     * @param width          width of the data in texels
     * @param height         height of the data in texels
     * @param depth          depth of the data in texels
     * @param rowPitch       bytes between rows ({@code D3D12_TEXTURE_DATA_PITCH_ALIGNMENT}-aligned)
     * @param srcTexture     the source texture, in {@code COPY_SOURCE} state
     * @param srcSubresource subresource index
     * @param srcBox         region of the texture to copy ({@link D3D12Box}), or {@code null} for all of it
     */
    public void copyTextureToBuffer(D3D12Resource dstBuffer, long dstOffset, int format, int width, int height, int depth,
                                    int rowPitch, D3D12Resource srcTexture, int srcSubresource, MemorySegment srcBox) {
        MemorySegment dst = this.scratch.asSlice(S_LOCATION_DST, D3D12TextureCopyLocation.SIZE);
        MemorySegment src = this.scratch.asSlice(S_LOCATION_SRC, D3D12TextureCopyLocation.SIZE);

        fillPlacedLocation(dst, dstBuffer, dstOffset, format, width, height, depth, rowPitch);
        fillSubresourceLocation(src, srcTexture, srcSubresource);

        this.copyTextureRegion(dst, 0, 0, 0, src, srcBox);
    }

    /**
     * Copies a region of one texture subresource into another texture.
     *
     * @param srcBox region of the source to copy ({@link D3D12Box}), or {@code null} for all of it
     */
    public void copyTexture(D3D12Resource dstTexture, int dstSubresource, int dstX, int dstY, int dstZ,
                            D3D12Resource srcTexture, int srcSubresource, MemorySegment srcBox) {
        MemorySegment dst = this.scratch.asSlice(S_LOCATION_DST, D3D12TextureCopyLocation.SIZE);
        MemorySegment src = this.scratch.asSlice(S_LOCATION_SRC, D3D12TextureCopyLocation.SIZE);

        fillSubresourceLocation(dst, dstTexture, dstSubresource);
        fillSubresourceLocation(src, srcTexture, srcSubresource);

        this.copyTextureRegion(dst, dstX, dstY, dstZ, src, srcBox);
    }

    private static void fillSubresourceLocation(MemorySegment location, D3D12Resource resource, int subresource) {
        D3D12TextureCopyLocation.setPResource(location, resource.ptr());
        D3D12TextureCopyLocation.setType(location, Enums.TextureCopyType.SUBRESOURCE_INDEX);
        D3D12TextureCopyLocation.setSubresourceIndex(location, subresource);
    }

    private static void fillPlacedLocation(MemorySegment location, D3D12Resource buffer, long offset, int format,
                                           int width, int height, int depth, int rowPitch) {
        D3D12TextureCopyLocation.setPResource(location, buffer.ptr());
        D3D12TextureCopyLocation.setType(location, Enums.TextureCopyType.PLACED_FOOTPRINT);

        MemorySegment placed = D3D12TextureCopyLocation.slicePlacedFootprint(location);
        D3D12PlacedSubresourceFootprint.setOffset(placed, offset);

        MemorySegment footprint = D3D12PlacedSubresourceFootprint.sliceFootprint(placed);
        D3D12SubresourceFootprint.setFormat(footprint, format);
        D3D12SubresourceFootprint.setWidth(footprint, width);
        D3D12SubresourceFootprint.setHeight(footprint, height);
        D3D12SubresourceFootprint.setDepth(footprint, depth);
        D3D12SubresourceFootprint.setRowPitch(footprint, rowPitch);
    }

    public void copyResource(D3D12Resource dst, D3D12Resource src) {
        try {
            V_PP.invokeExact(this.fn(Vtbl.ID3D12GraphicsCommandList.COPY_RESOURCE), this.ptr(), dst.ptr(), src.ptr());
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Resolves a multisampled subresource into a single-sample one.
     *
     * @param format the {@code DXGI_FORMAT} used for the resolve
     */
    public void resolveSubresource(D3D12Resource dst, int dstSubresource, D3D12Resource src, int srcSubresource, int format) {
        try {
            RESOLVE_SUBRESOURCE.invokeExact(this.fn(Vtbl.ID3D12GraphicsCommandList.RESOLVE_SUBRESOURCE), this.ptr(),
                    dst.ptr(), dstSubresource, src.ptr(), srcSubresource, format);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Fixed-function state
    // ------------------------------------------------------------------------------------------------------------

    /**
     * @param topology a {@code D3D_PRIMITIVE_TOPOLOGY} ({@code Enums.PrimitiveTopology.D3D10_PRIMITIVE_TOPOLOGY_*})
     */
    public void iaSetPrimitiveTopology(int topology) {
        try {
            V_I.invokeExact(this.fn(Vtbl.ID3D12GraphicsCommandList.IA_SET_PRIMITIVE_TOPOLOGY), this.ptr(), topology);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * @param viewports native array of {@code count} {@code D3D12_VIEWPORT} ({@link D3D12Viewport})
     */
    public void rsSetViewports(int count, MemorySegment viewports) {
        try {
            V_IP.invokeExact(this.fn(Vtbl.ID3D12GraphicsCommandList.RS_SET_VIEWPORTS), this.ptr(), count, viewports);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Sets a single viewport.
     */
    public void rsSetViewport(float topLeftX, float topLeftY, float width, float height, float minDepth, float maxDepth) {
        MemorySegment viewport = this.scratch.asSlice(S_VIEWPORT, D3D12Viewport.SIZE);
        D3D12Viewport.setTopLeftX(viewport, topLeftX);
        D3D12Viewport.setTopLeftY(viewport, topLeftY);
        D3D12Viewport.setWidth(viewport, width);
        D3D12Viewport.setHeight(viewport, height);
        D3D12Viewport.setMinDepth(viewport, minDepth);
        D3D12Viewport.setMaxDepth(viewport, maxDepth);
        this.rsSetViewports(1, viewport);
    }

    /**
     * @param rects native array of {@code count} {@code D3D12_RECT} (16 bytes each: left, top, right, bottom)
     */
    public void rsSetScissorRects(int count, MemorySegment rects) {
        try {
            V_IP.invokeExact(this.fn(Vtbl.ID3D12GraphicsCommandList.RS_SET_SCISSOR_RECTS), this.ptr(), count, rects);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Sets a single scissor rectangle (exclusive right/bottom edges, like a Win32 RECT).
     */
    public void rsSetScissorRect(int left, int top, int right, int bottom) {
        MemorySegment rect = this.scratch.asSlice(S_RECT, Rect.SIZE);
        Rect.setLeft(rect, left);
        Rect.setTop(rect, top);
        Rect.setRight(rect, right);
        Rect.setBottom(rect, bottom);
        this.rsSetScissorRects(1, rect);
    }

    /**
     * @param blendFactor native {@code float[4]}, or {@code null} to use the blend factor of the pipeline
     */
    public void omSetBlendFactor(MemorySegment blendFactor) {
        try {
            V_P.invokeExact(this.fn(Vtbl.ID3D12GraphicsCommandList.OM_SET_BLEND_FACTOR), this.ptr(), D3D12Object.orNull(blendFactor));
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    public void omSetBlendFactor(float red, float green, float blue, float alpha) {
        MemorySegment color = this.scratch.asSlice(S_COLOR, 16);
        color.set(JAVA_FLOAT, 0, red);
        color.set(JAVA_FLOAT, 4, green);
        color.set(JAVA_FLOAT, 8, blue);
        color.set(JAVA_FLOAT, 12, alpha);
        this.omSetBlendFactor(color);
    }

    public void omSetStencilRef(int stencilRef) {
        try {
            V_I.invokeExact(this.fn(Vtbl.ID3D12GraphicsCommandList.OM_SET_STENCIL_REF), this.ptr(), stencilRef);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    public void setPipelineState(D3D12PipelineState pipelineState) {
        try {
            V_P.invokeExact(this.fn(Vtbl.ID3D12GraphicsCommandList.SET_PIPELINE_STATE), this.ptr(), pipelineState.ptr());
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Barriers
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Raw form of ResourceBarrier.
     *
     * @param barriers native array of {@code count} {@code D3D12_RESOURCE_BARRIER} ({@link D3D12ResourceBarrier})
     */
    public void resourceBarrier(int count, MemorySegment barriers) {
        try {
            V_IP.invokeExact(this.fn(Vtbl.ID3D12GraphicsCommandList.RESOURCE_BARRIER), this.ptr(), count, barriers);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Transitions all subresources of a resource between two states.
     *
     * @param stateBefore a {@code D3D12_RESOURCE_STATES} value ({@code Enums.ResourceStates})
     * @param stateAfter  a {@code D3D12_RESOURCE_STATES} value
     */
    public void resourceBarrierTransition(D3D12Resource resource, int stateBefore, int stateAfter) {
        this.resourceBarrierTransition(resource, ALL_SUBRESOURCES, stateBefore, stateAfter, Enums.ResourceBarrierFlags.NONE);
    }

    /**
     * Transitions one subresource (or {@link #ALL_SUBRESOURCES}) of a resource between two states.
     *
     * @param flags {@code D3D12_RESOURCE_BARRIER_FLAGS} ({@code Enums.ResourceBarrierFlags}: NONE, BEGIN_ONLY, END_ONLY)
     */
    public void resourceBarrierTransition(D3D12Resource resource, int subresource, int stateBefore, int stateAfter, int flags) {
        MemorySegment barrier = this.scratch.asSlice(S_BARRIER, D3D12ResourceBarrier.SIZE);
        writeTransitionBarrier(barrier, resource, subresource, stateBefore, stateAfter, flags);

        this.resourceBarrier(1, barrier);
    }

    /**
     * Fills one {@code D3D12_RESOURCE_BARRIER} with a transition barrier, for building the arrays passed to
     * {@link #resourceBarrier(int, MemorySegment)}. Does not allocate.
     *
     * @param barrier     a {@link D3D12ResourceBarrier#SIZE}-byte slice of the barrier array
     * @param subresource a subresource index, or {@link #ALL_SUBRESOURCES}
     * @param flags       {@code D3D12_RESOURCE_BARRIER_FLAGS} ({@code Enums.ResourceBarrierFlags})
     */
    public static void writeTransitionBarrier(MemorySegment barrier, D3D12Resource resource, int subresource, int stateBefore, int stateAfter, int flags) {
        D3D12ResourceBarrier.setType(barrier, Enums.ResourceBarrierType.TRANSITION);
        D3D12ResourceBarrier.setFlags(barrier, flags);

        MemorySegment transition = D3D12ResourceBarrier.sliceTransition(barrier);
        D3D12ResourceTransitionBarrier.setPResource(transition, resource.ptr());
        D3D12ResourceTransitionBarrier.setSubresource(transition, subresource);
        D3D12ResourceTransitionBarrier.setStateBefore(transition, stateBefore);
        D3D12ResourceTransitionBarrier.setStateAfter(transition, stateAfter);
    }

    /**
     * Orders UAV accesses to a resource.
     *
     * @param resource the resource, or {@code null} for a barrier on all UAV accesses
     */
    public void resourceBarrierUav(D3D12Resource resource) {
        MemorySegment barrier = this.scratch.asSlice(S_BARRIER, D3D12ResourceBarrier.SIZE);
        D3D12ResourceBarrier.setType(barrier, Enums.ResourceBarrierType.UAV);
        D3D12ResourceBarrier.setFlags(barrier, Enums.ResourceBarrierFlags.NONE);
        barrier.set(ADDRESS, D3D12ResourceBarrier.OFFSET_UAV, D3D12Object.ptrOrNull(resource));

        this.resourceBarrier(1, barrier);
    }

    /**
     * Switches between two placed resources that alias the same heap memory.
     *
     * @param before the resource whose use ends, or {@code null}
     * @param after  the resource whose use begins, or {@code null}
     */
    public void resourceBarrierAliasing(D3D12Resource before, D3D12Resource after) {
        MemorySegment barrier = this.scratch.asSlice(S_BARRIER, D3D12ResourceBarrier.SIZE);
        D3D12ResourceBarrier.setType(barrier, Enums.ResourceBarrierType.ALIASING);
        D3D12ResourceBarrier.setFlags(barrier, Enums.ResourceBarrierFlags.NONE);
        barrier.set(ADDRESS, D3D12ResourceBarrier.OFFSET_ALIASING, D3D12Object.ptrOrNull(before));
        barrier.set(ADDRESS, D3D12ResourceBarrier.OFFSET_ALIASING + 8, D3D12Object.ptrOrNull(after));

        this.resourceBarrier(1, barrier);
    }

    public void executeBundle(D3D12GraphicsCommandList bundle) {
        try {
            V_P.invokeExact(this.fn(Vtbl.ID3D12GraphicsCommandList.EXECUTE_BUNDLE), this.ptr(), bundle.ptr());
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Root signature / descriptors
    // ------------------------------------------------------------------------------------------------------------

    /**
     * @param heaps native array of {@code count} {@code ID3D12DescriptorHeap*}; at most one CBV/SRV/UAV heap and one
     *              sampler heap may be set
     */
    public void setDescriptorHeaps(int count, MemorySegment heaps) {
        try {
            V_IP.invokeExact(this.fn(Vtbl.ID3D12GraphicsCommandList.SET_DESCRIPTOR_HEAPS), this.ptr(), count, heaps);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Sets one shader-visible descriptor heap.
     */
    public void setDescriptorHeaps(D3D12DescriptorHeap heap) {
        MemorySegment pointers = this.scratch.asSlice(S_POINTERS, 16);
        pointers.set(ADDRESS, 0, heap.ptr());
        this.setDescriptorHeaps(1, pointers);
    }

    /**
     * Sets a CBV/SRV/UAV heap and a sampler heap.
     */
    public void setDescriptorHeaps(D3D12DescriptorHeap cbvSrvUavHeap, D3D12DescriptorHeap samplerHeap) {
        MemorySegment pointers = this.scratch.asSlice(S_POINTERS, 16);
        pointers.set(ADDRESS, 0, cbvSrvUavHeap.ptr());
        pointers.set(ADDRESS, 8, samplerHeap.ptr());
        this.setDescriptorHeaps(2, pointers);
    }

    public void setComputeRootSignature(D3D12RootSignature rootSignature) {
        try {
            V_P.invokeExact(this.fn(Vtbl.ID3D12GraphicsCommandList.SET_COMPUTE_ROOT_SIGNATURE), this.ptr(), rootSignature.ptr());
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    public void setGraphicsRootSignature(D3D12RootSignature rootSignature) {
        try {
            V_P.invokeExact(this.fn(Vtbl.ID3D12GraphicsCommandList.SET_GRAPHICS_ROOT_SIGNATURE), this.ptr(), rootSignature.ptr());
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * @param baseDescriptor the {@code D3D12_GPU_DESCRIPTOR_HANDLE} of the first descriptor of the table
     */
    public void setComputeRootDescriptorTable(int rootParameterIndex, long baseDescriptor) {
        try {
            SET_ROOT_LONG.invokeExact(this.fn(Vtbl.ID3D12GraphicsCommandList.SET_COMPUTE_ROOT_DESCRIPTOR_TABLE), this.ptr(), rootParameterIndex, baseDescriptor);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * @param baseDescriptor the {@code D3D12_GPU_DESCRIPTOR_HANDLE} of the first descriptor of the table
     */
    public void setGraphicsRootDescriptorTable(int rootParameterIndex, long baseDescriptor) {
        try {
            SET_ROOT_LONG.invokeExact(this.fn(Vtbl.ID3D12GraphicsCommandList.SET_GRAPHICS_ROOT_DESCRIPTOR_TABLE), this.ptr(), rootParameterIndex, baseDescriptor);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    public void setComputeRoot32BitConstant(int rootParameterIndex, int data, int destOffsetIn32BitValues) {
        try {
            SET_ROOT_32BIT_CONSTANT.invokeExact(this.fn(Vtbl.ID3D12GraphicsCommandList.SET_COMPUTE_ROOT32_BIT_CONSTANT), this.ptr(),
                    rootParameterIndex, data, destOffsetIn32BitValues);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    public void setGraphicsRoot32BitConstant(int rootParameterIndex, int data, int destOffsetIn32BitValues) {
        try {
            SET_ROOT_32BIT_CONSTANT.invokeExact(this.fn(Vtbl.ID3D12GraphicsCommandList.SET_GRAPHICS_ROOT32_BIT_CONSTANT), this.ptr(),
                    rootParameterIndex, data, destOffsetIn32BitValues);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * @param count                   number of 32-bit values
     * @param data                    native memory with {@code count} 32-bit values
     * @param destOffsetIn32BitValues offset in 32-bit values into the root constants of the parameter
     */
    public void setComputeRoot32BitConstants(int rootParameterIndex, int count, MemorySegment data, int destOffsetIn32BitValues) {
        try {
            SET_ROOT_32BIT_CONSTANTS.invokeExact(this.fn(Vtbl.ID3D12GraphicsCommandList.SET_COMPUTE_ROOT32_BIT_CONSTANTS), this.ptr(),
                    rootParameterIndex, count, data, destOffsetIn32BitValues);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * @param count                   number of 32-bit values
     * @param data                    native memory with {@code count} 32-bit values
     * @param destOffsetIn32BitValues offset in 32-bit values into the root constants of the parameter
     */
    public void setGraphicsRoot32BitConstants(int rootParameterIndex, int count, MemorySegment data, int destOffsetIn32BitValues) {
        try {
            SET_ROOT_32BIT_CONSTANTS.invokeExact(this.fn(Vtbl.ID3D12GraphicsCommandList.SET_GRAPHICS_ROOT32_BIT_CONSTANTS), this.ptr(),
                    rootParameterIndex, count, data, destOffsetIn32BitValues);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * @param gpuVirtualAddress the {@code D3D12_GPU_VIRTUAL_ADDRESS} of the constant buffer
     */
    public void setComputeRootConstantBufferView(int rootParameterIndex, long gpuVirtualAddress) {
        try {
            SET_ROOT_LONG.invokeExact(this.fn(Vtbl.ID3D12GraphicsCommandList.SET_COMPUTE_ROOT_CONSTANT_BUFFER_VIEW), this.ptr(), rootParameterIndex, gpuVirtualAddress);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * @param gpuVirtualAddress the {@code D3D12_GPU_VIRTUAL_ADDRESS} of the constant buffer
     */
    public void setGraphicsRootConstantBufferView(int rootParameterIndex, long gpuVirtualAddress) {
        try {
            SET_ROOT_LONG.invokeExact(this.fn(Vtbl.ID3D12GraphicsCommandList.SET_GRAPHICS_ROOT_CONSTANT_BUFFER_VIEW), this.ptr(), rootParameterIndex, gpuVirtualAddress);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    public void setComputeRootShaderResourceView(int rootParameterIndex, long gpuVirtualAddress) {
        try {
            SET_ROOT_LONG.invokeExact(this.fn(Vtbl.ID3D12GraphicsCommandList.SET_COMPUTE_ROOT_SHADER_RESOURCE_VIEW), this.ptr(), rootParameterIndex, gpuVirtualAddress);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    public void setGraphicsRootShaderResourceView(int rootParameterIndex, long gpuVirtualAddress) {
        try {
            SET_ROOT_LONG.invokeExact(this.fn(Vtbl.ID3D12GraphicsCommandList.SET_GRAPHICS_ROOT_SHADER_RESOURCE_VIEW), this.ptr(), rootParameterIndex, gpuVirtualAddress);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    public void setComputeRootUnorderedAccessView(int rootParameterIndex, long gpuVirtualAddress) {
        try {
            SET_ROOT_LONG.invokeExact(this.fn(Vtbl.ID3D12GraphicsCommandList.SET_COMPUTE_ROOT_UNORDERED_ACCESS_VIEW), this.ptr(), rootParameterIndex, gpuVirtualAddress);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    public void setGraphicsRootUnorderedAccessView(int rootParameterIndex, long gpuVirtualAddress) {
        try {
            SET_ROOT_LONG.invokeExact(this.fn(Vtbl.ID3D12GraphicsCommandList.SET_GRAPHICS_ROOT_UNORDERED_ACCESS_VIEW), this.ptr(), rootParameterIndex, gpuVirtualAddress);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Input assembler / stream output / output merger
    // ------------------------------------------------------------------------------------------------------------

    /**
     * @param view a {@code D3D12_INDEX_BUFFER_VIEW} ({@link D3D12IndexBufferView}), or {@code null} to unbind
     */
    public void iaSetIndexBuffer(MemorySegment view) {
        try {
            V_P.invokeExact(this.fn(Vtbl.ID3D12GraphicsCommandList.IA_SET_INDEX_BUFFER), this.ptr(), D3D12Object.orNull(view));
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Binds an index buffer.
     *
     * @param bufferLocation the GPU virtual address of the first index
     * @param sizeInBytes    size of the bound index range in bytes
     * @param format         {@code DXGI_FORMAT_R16_UINT} or {@code DXGI_FORMAT_R32_UINT}
     */
    public void iaSetIndexBuffer(long bufferLocation, int sizeInBytes, int format) {
        MemorySegment view = this.scratch.asSlice(S_BUFFER_VIEW, D3D12IndexBufferView.SIZE);
        D3D12IndexBufferView.setBufferLocation(view, bufferLocation);
        D3D12IndexBufferView.setSizeInBytes(view, sizeInBytes);
        D3D12IndexBufferView.setFormat(view, format);
        this.iaSetIndexBuffer(view);
    }

    /**
     * @param views native array of {@code count} {@code D3D12_VERTEX_BUFFER_VIEW} ({@link D3D12VertexBufferView}), or
     *              {@code null} to unbind the slots
     */
    public void iaSetVertexBuffers(int startSlot, int count, MemorySegment views) {
        try {
            V_IIP.invokeExact(this.fn(Vtbl.ID3D12GraphicsCommandList.IA_SET_VERTEX_BUFFERS), this.ptr(), startSlot, count, D3D12Object.orNull(views));
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Binds a single vertex buffer.
     *
     * @param bufferLocation the GPU virtual address of the first vertex
     * @param sizeInBytes    size of the bound vertex range in bytes
     * @param strideInBytes  size of one vertex in bytes
     */
    public void iaSetVertexBuffer(int slot, long bufferLocation, int sizeInBytes, int strideInBytes) {
        MemorySegment view = this.scratch.asSlice(S_BUFFER_VIEW, D3D12VertexBufferView.SIZE);
        D3D12VertexBufferView.setBufferLocation(view, bufferLocation);
        D3D12VertexBufferView.setSizeInBytes(view, sizeInBytes);
        D3D12VertexBufferView.setStrideInBytes(view, strideInBytes);
        this.iaSetVertexBuffers(slot, 1, view);
    }

    /**
     * @param views native array of {@code count} {@code D3D12_STREAM_OUTPUT_BUFFER_VIEW}, or {@code null} to unbind
     */
    public void soSetTargets(int startSlot, int count, MemorySegment views) {
        try {
            V_IIP.invokeExact(this.fn(Vtbl.ID3D12GraphicsCommandList.SO_SET_TARGETS), this.ptr(), startSlot, count, D3D12Object.orNull(views));
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Raw form of OMSetRenderTargets.
     *
     * @param count                        number of render targets
     * @param renderTargetDescriptors      native array of {@code D3D12_CPU_DESCRIPTOR_HANDLE} (8 bytes each); with
     *                                     {@code singleHandleToDescriptorRange} just one handle, the first of a
     *                                     contiguous range of {@code count} descriptors; may be {@code null} if count is 0
     * @param singleHandleToDescriptorRange see above
     * @param depthStencilDescriptor       native memory holding one {@code D3D12_CPU_DESCRIPTOR_HANDLE}, or {@code null}
     *                                     for no depth-stencil view
     */
    public void omSetRenderTargets(int count, MemorySegment renderTargetDescriptors, boolean singleHandleToDescriptorRange,
                                   MemorySegment depthStencilDescriptor) {
        try {
            OM_SET_RENDER_TARGETS.invokeExact(this.fn(Vtbl.ID3D12GraphicsCommandList.OM_SET_RENDER_TARGETS), this.ptr(), count,
                    D3D12Object.orNull(renderTargetDescriptors), singleHandleToDescriptorRange ? 1 : 0,
                    D3D12Object.orNull(depthStencilDescriptor));
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Binds one render target and optionally a depth-stencil view.
     *
     * @param rtvHandle the CPU handle of the render target view, or {@link #NO_DESCRIPTOR} for depth-only rendering
     * @param dsvHandle the CPU handle of the depth-stencil view, or {@link #NO_DESCRIPTOR}
     */
    public void omSetRenderTarget(long rtvHandle, long dsvHandle) {
        this.omSetRenderTargetRange(rtvHandle == NO_DESCRIPTOR ? 0 : 1, rtvHandle, dsvHandle);
    }

    /**
     * Binds {@code count} render targets that occupy consecutive descriptors of one RTV heap
     * ({@code RTsSingleHandleToDescriptorRange = TRUE}), and optionally a depth-stencil view.
     *
     * @param firstRtvHandle the CPU handle of the first render target view
     * @param dsvHandle      the CPU handle of the depth-stencil view, or {@link #NO_DESCRIPTOR}
     */
    public void omSetRenderTargetRange(int count, long firstRtvHandle, long dsvHandle) {
        MemorySegment handles = this.scratch.asSlice(S_HANDLES, 16);
        handles.set(JAVA_LONG, 0, firstRtvHandle);
        handles.set(JAVA_LONG, 8, dsvHandle);

        this.omSetRenderTargets(count, count == 0 ? MemorySegment.NULL : handles, true,
                dsvHandle == NO_DESCRIPTOR ? MemorySegment.NULL : handles.asSlice(8, 8));
    }

    /**
     * Raw form of ClearDepthStencilView.
     *
     * @param dsvHandle the CPU handle of the depth-stencil view
     * @param clearFlags {@code D3D12_CLEAR_FLAGS} ({@code Enums.ClearFlags}: DEPTH, STENCIL)
     * @param rectCount number of rectangles, 0 to clear the whole view
     * @param rects     native array of {@code D3D12_RECT}, or {@code null}
     */
    public void clearDepthStencilView(long dsvHandle, int clearFlags, float depth, int stencil, int rectCount, MemorySegment rects) {
        try {
            CLEAR_DEPTH_STENCIL_VIEW.invokeExact(this.fn(Vtbl.ID3D12GraphicsCommandList.CLEAR_DEPTH_STENCIL_VIEW), this.ptr(),
                    dsvHandle, clearFlags, depth, (byte) stencil, rectCount, D3D12Object.orNull(rects));
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Clears the whole depth-stencil view.
     */
    public void clearDepthStencilView(long dsvHandle, int clearFlags, float depth, int stencil) {
        this.clearDepthStencilView(dsvHandle, clearFlags, depth, stencil, 0, null);
    }

    /**
     * Raw form of ClearRenderTargetView.
     *
     * @param rtvHandle the CPU handle of the render target view
     * @param color     native {@code float[4]} (r, g, b, a)
     * @param rectCount number of rectangles, 0 to clear the whole view
     * @param rects     native array of {@code D3D12_RECT}, or {@code null}
     */
    public void clearRenderTargetView(long rtvHandle, MemorySegment color, int rectCount, MemorySegment rects) {
        try {
            CLEAR_RENDER_TARGET_VIEW.invokeExact(this.fn(Vtbl.ID3D12GraphicsCommandList.CLEAR_RENDER_TARGET_VIEW), this.ptr(),
                    rtvHandle, color, rectCount, D3D12Object.orNull(rects));
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Clears the whole render target view.
     */
    public void clearRenderTargetView(long rtvHandle, float red, float green, float blue, float alpha) {
        MemorySegment color = this.scratch.asSlice(S_COLOR, 16);
        color.set(JAVA_FLOAT, 0, red);
        color.set(JAVA_FLOAT, 4, green);
        color.set(JAVA_FLOAT, 8, blue);
        color.set(JAVA_FLOAT, 12, alpha);
        this.clearRenderTargetView(rtvHandle, color, 0, null);
    }

    /**
     * @param gpuHandle the shader-visible GPU handle of the UAV
     * @param cpuHandle the CPU handle of the same UAV in a non-shader-visible heap
     * @param values    native {@code UINT[4]}
     */
    public void clearUnorderedAccessViewUint(long gpuHandle, long cpuHandle, D3D12Resource resource, MemorySegment values,
                                             int rectCount, MemorySegment rects) {
        try {
            CLEAR_UNORDERED_ACCESS_VIEW.invokeExact(this.fn(Vtbl.ID3D12GraphicsCommandList.CLEAR_UNORDERED_ACCESS_VIEW_UINT), this.ptr(),
                    gpuHandle, cpuHandle, resource.ptr(), values, rectCount, D3D12Object.orNull(rects));
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * @param gpuHandle the shader-visible GPU handle of the UAV
     * @param cpuHandle the CPU handle of the same UAV in a non-shader-visible heap
     * @param values    native {@code float[4]}
     */
    public void clearUnorderedAccessViewFloat(long gpuHandle, long cpuHandle, D3D12Resource resource, MemorySegment values,
                                              int rectCount, MemorySegment rects) {
        try {
            CLEAR_UNORDERED_ACCESS_VIEW.invokeExact(this.fn(Vtbl.ID3D12GraphicsCommandList.CLEAR_UNORDERED_ACCESS_VIEW_FLOAT), this.ptr(),
                    gpuHandle, cpuHandle, resource.ptr(), values, rectCount, D3D12Object.orNull(rects));
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Tells the driver that the contents of a resource are no longer needed.
     *
     * @param region a {@code D3D12_DISCARD_REGION}, or {@code null} to discard everything
     */
    public void discardResource(D3D12Resource resource, MemorySegment region) {
        try {
            V_PP.invokeExact(this.fn(Vtbl.ID3D12GraphicsCommandList.DISCARD_RESOURCE), this.ptr(), resource.ptr(), D3D12Object.orNull(region));
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Queries / predication
    // ------------------------------------------------------------------------------------------------------------

    /**
     * @param type a {@code D3D12_QUERY_TYPE} ({@code Enums.QueryType})
     */
    public void beginQuery(D3D12QueryHeap heap, int type, int index) {
        try {
            QUERY.invokeExact(this.fn(Vtbl.ID3D12GraphicsCommandList.BEGIN_QUERY), this.ptr(), heap.ptr(), type, index);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * @param type a {@code D3D12_QUERY_TYPE} ({@code Enums.QueryType}); timestamp queries only have an end
     */
    public void endQuery(D3D12QueryHeap heap, int type, int index) {
        try {
            QUERY.invokeExact(this.fn(Vtbl.ID3D12GraphicsCommandList.END_QUERY), this.ptr(), heap.ptr(), type, index);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Copies query results (64-bit values) into a buffer.
     *
     * @param dstBuffer          a buffer in {@code COPY_DEST} state
     * @param alignedDstOffset   byte offset in the buffer, a multiple of 8
     */
    public void resolveQueryData(D3D12QueryHeap heap, int type, int startIndex, int queryCount, D3D12Resource dstBuffer, long alignedDstOffset) {
        try {
            RESOLVE_QUERY_DATA.invokeExact(this.fn(Vtbl.ID3D12GraphicsCommandList.RESOLVE_QUERY_DATA), this.ptr(),
                    heap.ptr(), type, startIndex, queryCount, dstBuffer.ptr(), alignedDstOffset);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Makes the following draws, dispatches and copies conditional on a 64-bit value in a buffer. With
     * {@code PredicationOp.EQUAL_ZERO} the commands are <b>skipped</b> while the value is zero (the usual "draw only if the
     * occlusion query saw samples" setup); with {@code NOT_EQUAL_ZERO} they are skipped while it is non-zero.
     *
     * @param buffer          a buffer holding a 64-bit predicate, in {@code PREDICATION} (= {@code INDIRECT_ARGUMENT}) or
     *                        {@code GENERIC_READ} state, or {@code null} to disable predication
     * @param alignedOffset   byte offset of the predicate, a multiple of 8
     * @param operation       a {@code D3D12_PREDICATION_OP} ({@code Enums.PredicationOp})
     */
    public void setPredication(D3D12Resource buffer, long alignedOffset, int operation) {
        try {
            SET_PREDICATION.invokeExact(this.fn(Vtbl.ID3D12GraphicsCommandList.SET_PREDICATION), this.ptr(),
                    D3D12Object.ptrOrNull(buffer), alignedOffset, operation);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Debug markers
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Inserts a PIX/debugger marker.
     *
     * @param metadata 0 if {@code data} is a UTF-16 string, 1 if it is an ANSI string
     * @param size     size of {@code data} in bytes, including the terminator
     */
    public void setMarker(int metadata, MemorySegment data, int size) {
        try {
            MARKER.invokeExact(this.fn(Vtbl.ID3D12GraphicsCommandList.SET_MARKER), this.ptr(), metadata, data, size);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Opens a named PIX/debugger event region; close it with {@link #endEvent()}.
     *
     * @param metadata 0 if {@code data} is a UTF-16 string, 1 if it is an ANSI string
     * @param size     size of {@code data} in bytes, including the terminator
     */
    public void beginEvent(int metadata, MemorySegment data, int size) {
        try {
            MARKER.invokeExact(this.fn(Vtbl.ID3D12GraphicsCommandList.BEGIN_EVENT), this.ptr(), metadata, data, size);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    public void endEvent() {
        try {
            V_NONE.invokeExact(this.fn(Vtbl.ID3D12GraphicsCommandList.END_EVENT), this.ptr());
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * {@link #setMarker(int, MemorySegment, int)} with an ANSI text. Allocates; for debugging only.
     */
    public void setMarker(String text) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bytes = arena.allocateFrom(text, StandardCharsets.US_ASCII);
            this.setMarker(1, bytes, (int) bytes.byteSize());
        }
    }

    /**
     * {@link #beginEvent(int, MemorySegment, int)} with an ANSI text. Allocates; for debugging only.
     */
    public void beginEvent(String text) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bytes = arena.allocateFrom(text, StandardCharsets.US_ASCII);
            this.beginEvent(1, bytes, (int) bytes.byteSize());
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // ID3D12GraphicsCommandList1 (depth bounds, view instancing)
    // ------------------------------------------------------------------------------------------------------------

    /**
     * @return true if the object also implements {@code ID3D12GraphicsCommandList1} (so that
     * {@link #omSetDepthBounds} and {@link #setViewInstanceMask} may be used). The answer is cached.
     */
    public boolean supportsGraphicsCommandList1() {
        if (this.list1State == 0) {
            this.list1State = this.queryList1() ? 1 : 2;
        }

        return this.list1State == 1;
    }

    /**
     * The D3D12 runtime and vkd3d expose all interface versions through the same pointer and vtable, so the version 1
     * methods are called through this wrapper's own pointer after verifying (with QueryInterface) that it is so.
     */
    private boolean queryList1() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            int hr = (int) QUERY_INTERFACE.invokeExact(this.fn(Vtbl.ID3D12GraphicsCommandList.QUERY_INTERFACE), this.ptr(),
                    Iids.ID3D12GraphicsCommandList1, out);

            if (hr < 0) {
                return false;
            }

            MemorySegment queried = out.get(ADDRESS, 0);
            // we keep using our own reference; drop the one QueryInterface added
            int ignored = (int) RELEASE.invokeExact(this.fn(Vtbl.ID3D12GraphicsCommandList.RELEASE), queried);

            return queried.address() == this.address();
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    private void requireList1() {
        if (!this.supportsGraphicsCommandList1()) {
            throw new D3D12Exception("ID3D12GraphicsCommandList1 is not supported by this command list", Hr.E_NOINTERFACE);
        }
    }

    /**
     * Sets the depth bounds test range (requires the {@code DepthBoundsTestSupported} capability, see
     * {@code D3D12_FEATURE_DATA_D3D12_OPTIONS2}).
     *
     * @throws D3D12Exception if {@link #supportsGraphicsCommandList1()} is false
     */
    public void omSetDepthBounds(float min, float max) {
        this.requireList1();

        try {
            OM_SET_DEPTH_BOUNDS.invokeExact(this.fn(Vtbl.ID3D12GraphicsCommandList1.OM_SET_DEPTH_BOUNDS), this.ptr(), min, max);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Sets the view instance mask (requires view instancing support).
     *
     * @throws D3D12Exception if {@link #supportsGraphicsCommandList1()} is false
     */
    public void setViewInstanceMask(int mask) {
        this.requireList1();

        try {
            V_I.invokeExact(this.fn(Vtbl.ID3D12GraphicsCommandList1.SET_VIEW_INSTANCE_MASK), this.ptr(), mask);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }
}
