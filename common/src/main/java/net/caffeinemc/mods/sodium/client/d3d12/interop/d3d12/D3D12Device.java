package net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12;

import net.caffeinemc.mods.sodium.client.d3d12.interop.Com;
import net.caffeinemc.mods.sodium.client.d3d12.interop.Hr;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Enums;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Iids;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Vtbl;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12ClearValue;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12CommandQueueDesc;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12CommandSignatureDesc;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12ComputePipelineStateDesc;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12ConstantBufferViewDesc;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12DepthStencilValue;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12DepthStencilViewDesc;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12DescriptorHeapDesc;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12FeatureDataArchitecture;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12FeatureDataD3D12Options;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12FeatureDataFormatSupport;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12FeatureDataGpuVirtualAddressSupport;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12FeatureDataMultisampleQualityLevels;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12FeatureDataRootSignature;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12FeatureDataShaderModel;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12GraphicsPipelineStateDesc;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12HeapDesc;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12HeapProperties;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12PlacedSubresourceFootprint;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12QueryHeapDesc;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12RenderTargetViewDesc;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12ResourceAllocationInfo;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12ResourceDesc;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12SamplerDesc;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12ShaderResourceViewDesc;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12SubresourceFootprint;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12UnorderedAccessViewDesc;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.DxgiSampleDesc;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.Luid;
import net.caffeinemc.mods.sodium.client.d3d12.interop.compiler.D3DBlob;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * ID3D12Device: the factory for every other Direct3D 12 object. Obtain one with
 * {@link D3D12#createDevice(int)}.
 *
 * <p>Methods that create objects return a new wrapper owning one reference to the new object. Descriptors are passed
 * as native structs built with the {@code abi.structs} accessors; a few frequent cases have primitive-argument helper
 * overloads. Methods whose name starts with {@code create...View} / {@code createSampler} and the copy methods do not
 * allocate.
 *
 * <p>Thread safety: free-threaded (D3D12 devices may be called from any thread), including the creation methods and
 * the descriptor writing methods; only writes to the same descriptor must not race.
 */
public final class D3D12Device extends D3D12Object {
    /** {@code D3D12_SHADER_MODEL_5_1} (the generated ABI knows up to 6.0 because the headers it was built from are old). */
    public static final int SHADER_MODEL_5_1 = 0x51;
    /** {@code D3D_SHADER_MODEL_6_0} .. {@code D3D_SHADER_MODEL_6_9} are 0x60 .. 0x69. */
    public static final int SHADER_MODEL_6_0 = 0x60;
    public static final int SHADER_MODEL_6_6 = 0x66;
    public static final int SHADER_MODEL_6_7 = 0x67;
    public static final int SHADER_MODEL_6_9 = 0x69;

    private static final MethodHandle GET_NODE_COUNT = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle CREATE_FROM_DESC = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle CREATE_COMMAND_ALLOCATOR = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle CREATE_COMMAND_LIST = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle CHECK_FEATURE_SUPPORT = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle GET_DESCRIPTOR_HANDLE_INCREMENT_SIZE = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle CREATE_ROOT_SIGNATURE = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS));
    private static final MethodHandle CREATE_CBV = Com.method(FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_LONG));
    private static final MethodHandle CREATE_VIEW = Com.method(FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));
    private static final MethodHandle CREATE_UAV = Com.method(FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));
    private static final MethodHandle COPY_DESCRIPTORS = Com.method(FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle COPY_DESCRIPTORS_SIMPLE = Com.method(FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT));
    private static final MethodHandle GET_RESOURCE_ALLOCATION_INFO = Com.method(FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
    private static final MethodHandle GET_CUSTOM_HEAP_PROPERTIES = Com.method(FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle CREATE_COMMITTED_RESOURCE = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle CREATE_PLACED_RESOURCE = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle RESIDENCY = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
    private static final MethodHandle CREATE_FENCE = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle GET_DEVICE_REMOVED_REASON = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle GET_COPYABLE_FOOTPRINTS = Com.method(FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle CREATE_COMMAND_SIGNATURE = Com.method(FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle GET_ADAPTER_LUID = Com.method(FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS));

    public D3D12Device(MemorySegment pointer) {
        super(pointer);
    }

    /**
     * @return the number of GPU nodes (1 on non-linked-adapter systems)
     */
    public int getNodeCount() {
        try {
            return (int) GET_NODE_COUNT.invokeExact(this.fn(Vtbl.ID3D12Device.GET_NODE_COUNT), this.ptr());
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * @return the LUID of the adapter this device was created on, as {@code (HighPart << 32) | LowPart}
     */
    public long getAdapterLuid() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment luid = Luid.allocate(arena);
            long ignored = (long) GET_ADAPTER_LUID.invokeExact(this.fn(Vtbl.ID3D12Device.GET_ADAPTER_LUID), this.ptr(), luid);

            return ((long) Luid.getHighPart(luid) << 32) | (Luid.getLowPart(luid) & 0xFFFFFFFFL);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * @return the debug layer's info queue of this device (a new wrapper owning one reference, to be closed by the caller),
     * or {@code null} if the debug layer is not enabled
     */
    public D3D12Debug.InfoQueue getInfoQueue() {
        return this.tryQueryInterface(Iids.ID3D12InfoQueue, D3D12Debug.InfoQueue::new);
    }

    /**
     * @return {@code S_OK} while the device is healthy, otherwise the {@code DXGI_ERROR_DEVICE_*} reason it was
     * removed for (see {@code Hr.isDeviceLoss})
     */
    public int getDeviceRemovedReason() {
        try {
            return (int) GET_DEVICE_REMOVED_REASON.invokeExact(this.fn(Vtbl.ID3D12Device.GET_DEVICE_REMOVED_REASON), this.ptr());
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Object creation
    // ------------------------------------------------------------------------------------------------------------

    private MemorySegment createFromDesc(int slot, MemorySegment desc, long descSize, MemorySegment iid, String what) {
        requireSize(desc, descSize, "desc");

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            int hr = (int) CREATE_FROM_DESC.invokeExact(this.fn(slot), this.ptr(), desc, iid, out);
            Hr.check(hr, what);

            return out.get(ADDRESS, 0);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * @param desc a {@code D3D12_COMMAND_QUEUE_DESC} ({@link D3D12CommandQueueDesc})
     */
    public D3D12CommandQueue createCommandQueue(MemorySegment desc) {
        return new D3D12CommandQueue(this.createFromDesc(Vtbl.ID3D12Device.CREATE_COMMAND_QUEUE, desc, D3D12CommandQueueDesc.SIZE,
                Iids.ID3D12CommandQueue, "ID3D12Device::CreateCommandQueue"));
    }

    /**
     * Creates a normal-priority command queue.
     *
     * @param type a {@code D3D12_COMMAND_LIST_TYPE} ({@code Enums.CommandListType}: DIRECT, COMPUTE or COPY)
     */
    public D3D12CommandQueue createCommandQueue(int type) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment desc = D3D12CommandQueueDesc.allocate(arena);
            D3D12CommandQueueDesc.setType(desc, type);
            D3D12CommandQueueDesc.setPriority(desc, Enums.CommandQueuePriority.NORMAL);

            return this.createCommandQueue(desc);
        }
    }

    /**
     * @param type a {@code D3D12_COMMAND_LIST_TYPE} ({@code Enums.CommandListType})
     */
    public D3D12CommandAllocator createCommandAllocator(int type) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            int hr = (int) CREATE_COMMAND_ALLOCATOR.invokeExact(this.fn(Vtbl.ID3D12Device.CREATE_COMMAND_ALLOCATOR), this.ptr(),
                    type, Iids.ID3D12CommandAllocator, out);
            Hr.check(hr, "ID3D12Device::CreateCommandAllocator");

            return new D3D12CommandAllocator(out.get(ADDRESS, 0));
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Creates a command list, which starts in the recording state.
     *
     * @param nodeMask     the GPU node mask (0 or 1 on single-GPU systems)
     * @param type         a {@code D3D12_COMMAND_LIST_TYPE} ({@code Enums.CommandListType}), matching the allocator's
     * @param allocator    the allocator to record into
     * @param initialState the initial pipeline state, or {@code null}
     */
    public D3D12GraphicsCommandList createCommandList(int nodeMask, int type, D3D12CommandAllocator allocator, D3D12PipelineState initialState) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            int hr = (int) CREATE_COMMAND_LIST.invokeExact(this.fn(Vtbl.ID3D12Device.CREATE_COMMAND_LIST), this.ptr(),
                    nodeMask, type, allocator.ptr(), ptrOrNull(initialState), Iids.ID3D12GraphicsCommandList, out);
            Hr.check(hr, "ID3D12Device::CreateCommandList");

            return new D3D12GraphicsCommandList(out.get(ADDRESS, 0));
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * {@link #createCommandList(int, int, D3D12CommandAllocator, D3D12PipelineState)} for node 0 without an initial
     * pipeline state.
     */
    public D3D12GraphicsCommandList createCommandList(int type, D3D12CommandAllocator allocator) {
        return this.createCommandList(0, type, allocator, null);
    }

    /**
     * @param desc a {@code D3D12_GRAPHICS_PIPELINE_STATE_DESC} ({@code abi.structs.D3D12GraphicsPipelineStateDesc})
     */
    public D3D12PipelineState createGraphicsPipelineState(MemorySegment desc) {
        return new D3D12PipelineState(this.createFromDesc(Vtbl.ID3D12Device.CREATE_GRAPHICS_PIPELINE_STATE, desc,
                D3D12GraphicsPipelineStateDesc.SIZE, Iids.ID3D12PipelineState, "ID3D12Device::CreateGraphicsPipelineState"));
    }

    /**
     * @param desc a {@code D3D12_COMPUTE_PIPELINE_STATE_DESC} ({@code abi.structs.D3D12ComputePipelineStateDesc})
     */
    public D3D12PipelineState createComputePipelineState(MemorySegment desc) {
        return new D3D12PipelineState(this.createFromDesc(Vtbl.ID3D12Device.CREATE_COMPUTE_PIPELINE_STATE, desc,
                D3D12ComputePipelineStateDesc.SIZE, Iids.ID3D12PipelineState, "ID3D12Device::CreateComputePipelineState"));
    }

    /**
     * @param desc a {@code D3D12_DESCRIPTOR_HEAP_DESC} ({@link D3D12DescriptorHeapDesc})
     */
    public D3D12DescriptorHeap createDescriptorHeap(MemorySegment desc) {
        return new D3D12DescriptorHeap(this.createFromDesc(Vtbl.ID3D12Device.CREATE_DESCRIPTOR_HEAP, desc,
                D3D12DescriptorHeapDesc.SIZE, Iids.ID3D12DescriptorHeap, "ID3D12Device::CreateDescriptorHeap"));
    }

    /**
     * Creates a descriptor heap on node 0.
     *
     * @param type           a {@code D3D12_DESCRIPTOR_HEAP_TYPE} ({@code Enums.DescriptorHeapType})
     * @param numDescriptors capacity
     * @param shaderVisible  whether shaders can access it (only CBV/SRV/UAV and sampler heaps can be shader visible)
     */
    public D3D12DescriptorHeap createDescriptorHeap(int type, int numDescriptors, boolean shaderVisible) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment desc = D3D12DescriptorHeapDesc.allocate(arena);
            D3D12DescriptorHeapDesc.setType(desc, type);
            D3D12DescriptorHeapDesc.setNumDescriptors(desc, numDescriptors);
            D3D12DescriptorHeapDesc.setFlags(desc, shaderVisible ? Enums.DescriptorHeapFlags.SHADER_VISIBLE : Enums.DescriptorHeapFlags.NONE);

            return this.createDescriptorHeap(desc);
        }
    }

    /**
     * @param type a {@code D3D12_DESCRIPTOR_HEAP_TYPE} ({@code Enums.DescriptorHeapType})
     * @return the distance between two consecutive descriptors of a heap of this type, in bytes
     */
    public int getDescriptorHandleIncrementSize(int type) {
        try {
            return (int) GET_DESCRIPTOR_HANDLE_INCREMENT_SIZE.invokeExact(this.fn(Vtbl.ID3D12Device.GET_DESCRIPTOR_HANDLE_INCREMENT_SIZE), this.ptr(), type);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * @param nodeMask       the GPU node mask
     * @param bytecode       a serialized root signature (the contents of the blob from {@link D3D12#serializeRootSignature})
     * @param bytecodeLength size of the serialized root signature in bytes
     */
    public D3D12RootSignature createRootSignature(int nodeMask, MemorySegment bytecode, long bytecodeLength) {
        requireSize(bytecode, bytecodeLength, "bytecode");

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            int hr = (int) CREATE_ROOT_SIGNATURE.invokeExact(this.fn(Vtbl.ID3D12Device.CREATE_ROOT_SIGNATURE), this.ptr(),
                    nodeMask, bytecode, bytecodeLength, Iids.ID3D12RootSignature, out);
            Hr.check(hr, "ID3D12Device::CreateRootSignature");

            return new D3D12RootSignature(out.get(ADDRESS, 0));
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Creates a root signature from a serialized blob, on node 0.
     */
    public D3D12RootSignature createRootSignature(D3DBlob serialized) {
        return this.createRootSignature(0, serialized.data(), serialized.size());
    }

    /**
     * Creates a committed resource: a resource with its own implicit heap.
     *
     * @param heapProperties a {@code D3D12_HEAP_PROPERTIES} ({@link D3D12HeapProperties})
     * @param heapFlags      {@code D3D12_HEAP_FLAGS} ({@code Enums.HeapFlags})
     * @param desc           a {@code D3D12_RESOURCE_DESC} ({@link D3D12ResourceDesc})
     * @param initialState   {@code D3D12_RESOURCE_STATES} ({@code Enums.ResourceStates}); upload heaps require
     *                       {@code GENERIC_READ}, readback heaps {@code COPY_DEST}
     * @param clearValue     a {@code D3D12_CLEAR_VALUE} for render target / depth-stencil textures, or {@code null}
     */
    public D3D12Resource createCommittedResource(MemorySegment heapProperties, int heapFlags, MemorySegment desc,
                                                 int initialState, MemorySegment clearValue) {
        requireSize(heapProperties, D3D12HeapProperties.SIZE, "heapProperties");
        requireSize(desc, D3D12ResourceDesc.SIZE, "desc");
        requireSizeIfPresent(clearValue, D3D12ClearValue.SIZE, "clearValue");

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            int hr = (int) CREATE_COMMITTED_RESOURCE.invokeExact(this.fn(Vtbl.ID3D12Device.CREATE_COMMITTED_RESOURCE), this.ptr(),
                    heapProperties, heapFlags, desc, initialState, orNull(clearValue), Iids.ID3D12Resource, out);
            Hr.check(hr, "ID3D12Device::CreateCommittedResource");

            return new D3D12Resource(out.get(ADDRESS, 0));
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Creates a committed buffer in the default memory pool of the given heap type.
     *
     * @param heapType      a {@code D3D12_HEAP_TYPE} ({@code Enums.HeapType}: DEFAULT, UPLOAD or READBACK)
     * @param sizeInBytes   buffer size
     * @param resourceFlags {@code D3D12_RESOURCE_FLAGS} ({@code Enums.ResourceFlags}), for example ALLOW_UNORDERED_ACCESS
     * @param initialState  {@code D3D12_RESOURCE_STATES}; use {@code GENERIC_READ} for upload and {@code COPY_DEST} for
     *                      readback buffers
     * @throws IllegalArgumentException if {@code sizeInBytes} is not positive
     */
    public D3D12Resource createCommittedBuffer(int heapType, long sizeInBytes, int resourceFlags, int initialState) {
        if (sizeInBytes <= 0L) {
            throw new IllegalArgumentException("Buffer size must be positive: " + sizeInBytes);
        }

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment properties = heapProperties(arena, heapType);
            MemorySegment desc = D3D12ResourceDesc.allocate(arena);
            D3D12ResourceDesc.setDimension(desc, Enums.ResourceDimension.BUFFER);
            D3D12ResourceDesc.setWidth(desc, sizeInBytes);
            D3D12ResourceDesc.setHeight(desc, 1);
            D3D12ResourceDesc.setDepthOrArraySize(desc, (short) 1);
            D3D12ResourceDesc.setMipLevels(desc, (short) 1);
            D3D12ResourceDesc.setFormat(desc, Enums.Format.UNKNOWN);
            DxgiSampleDesc.setCount(D3D12ResourceDesc.sliceSampleDesc(desc), 1);
            D3D12ResourceDesc.setLayout(desc, Enums.TextureLayout.ROW_MAJOR);
            D3D12ResourceDesc.setFlags(desc, resourceFlags);

            return this.createCommittedResource(properties, Enums.HeapFlags.NONE, desc, initialState, null);
        }
    }

    /**
     * Creates a committed 2D texture (or texture array) in a default heap.
     *
     * @param format        a {@code DXGI_FORMAT} ({@code Enums.Format})
     * @param arraySize     number of array slices (1 for a plain texture)
     * @param mipLevels     number of mip levels
     * @param sampleCount   MSAA sample count (1 for none)
     * @param resourceFlags {@code D3D12_RESOURCE_FLAGS} ({@code Enums.ResourceFlags})
     * @param initialState  {@code D3D12_RESOURCE_STATES}
     * @param clearValue    a {@code D3D12_CLEAR_VALUE} (required for render target / depth-stencil textures to get
     *                      fast clears), or {@code null}
     * @throws IllegalArgumentException if the size, array size or sample count is not positive, or the array size or mip
     *                                  level count does not fit the 16 bits of the description ({@code mipLevels == 0}
     *                                  asks for the full mip chain)
     */
    public D3D12Resource createCommittedTexture2D(int format, long width, int height, int arraySize, int mipLevels,
                                                  int sampleCount, int resourceFlags, int initialState, MemorySegment clearValue) {
        if (width <= 0L || height <= 0 || arraySize <= 0 || arraySize > 0xFFFF || mipLevels < 0 || mipLevels > 0xFFFF || sampleCount <= 0) {
            throw new IllegalArgumentException("Invalid texture description: " + width + "x" + height + ", array size " + arraySize
                    + ", mip levels " + mipLevels + ", samples " + sampleCount);
        }

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment properties = heapProperties(arena, Enums.HeapType.DEFAULT);
            MemorySegment desc = D3D12ResourceDesc.allocate(arena);
            D3D12ResourceDesc.setDimension(desc, Enums.ResourceDimension.TEXTURE2D);
            D3D12ResourceDesc.setWidth(desc, width);
            D3D12ResourceDesc.setHeight(desc, height);
            D3D12ResourceDesc.setDepthOrArraySize(desc, (short) arraySize);
            D3D12ResourceDesc.setMipLevels(desc, (short) mipLevels);
            D3D12ResourceDesc.setFormat(desc, format);
            DxgiSampleDesc.setCount(D3D12ResourceDesc.sliceSampleDesc(desc), sampleCount);
            D3D12ResourceDesc.setLayout(desc, Enums.TextureLayout.UNKNOWN);
            D3D12ResourceDesc.setFlags(desc, resourceFlags);

            return this.createCommittedResource(properties, Enums.HeapFlags.NONE, desc, initialState, clearValue);
        }
    }

    /**
     * Builds a {@code D3D12_CLEAR_VALUE} for a colour render target, to pass as the {@code clearValue} of the texture creation
     * methods. Clears with exactly this colour are the fast path on many GPUs.
     *
     * @param allocator where to allocate the struct (an arena that outlives the creation call)
     * @param format    the {@code DXGI_FORMAT} of the render target view
     */
    public static MemorySegment colorClearValue(SegmentAllocator allocator, int format, float red, float green, float blue, float alpha) {
        MemorySegment value = D3D12ClearValue.allocate(allocator);
        D3D12ClearValue.setFormat(value, format);
        D3D12ClearValue.setColor(value, 0, red);
        D3D12ClearValue.setColor(value, 1, green);
        D3D12ClearValue.setColor(value, 2, blue);
        D3D12ClearValue.setColor(value, 3, alpha);

        return value;
    }

    /**
     * Builds a {@code D3D12_CLEAR_VALUE} for a depth-stencil texture, to pass as the {@code clearValue} of the texture creation
     * methods.
     *
     * @param allocator where to allocate the struct (an arena that outlives the creation call)
     * @param format    the {@code DXGI_FORMAT} of the depth-stencil view
     */
    public static MemorySegment depthStencilClearValue(SegmentAllocator allocator, int format, float depth, int stencil) {
        MemorySegment value = D3D12ClearValue.allocate(allocator);
        D3D12ClearValue.setFormat(value, format);

        MemorySegment depthStencil = D3D12DepthStencilValue.allocate(allocator);
        D3D12DepthStencilValue.setDepth(depthStencil, depth);
        D3D12DepthStencilValue.setStencil(depthStencil, (byte) stencil);
        D3D12ClearValue.setDepthStencil(value, depthStencil);

        return value;
    }

    private static MemorySegment heapProperties(Arena arena, int heapType) {
        MemorySegment properties = D3D12HeapProperties.allocate(arena);
        D3D12HeapProperties.setType(properties, heapType);
        D3D12HeapProperties.setCreationNodeMask(properties, 1);
        D3D12HeapProperties.setVisibleNodeMask(properties, 1);

        return properties;
    }

    /**
     * Creates a resource inside an existing heap.
     *
     * @param heap         the heap
     * @param heapOffset   byte offset in the heap, a multiple of the resource's alignment
     * @param desc         a {@code D3D12_RESOURCE_DESC}
     * @param initialState {@code D3D12_RESOURCE_STATES}
     * @param clearValue   a {@code D3D12_CLEAR_VALUE}, or {@code null}
     */
    public D3D12Resource createPlacedResource(D3D12Heap heap, long heapOffset, MemorySegment desc, int initialState, MemorySegment clearValue) {
        requireSize(desc, D3D12ResourceDesc.SIZE, "desc");
        requireSizeIfPresent(clearValue, D3D12ClearValue.SIZE, "clearValue");

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            int hr = (int) CREATE_PLACED_RESOURCE.invokeExact(this.fn(Vtbl.ID3D12Device.CREATE_PLACED_RESOURCE), this.ptr(),
                    heap.ptr(), heapOffset, desc, initialState, orNull(clearValue), Iids.ID3D12Resource, out);
            Hr.check(hr, "ID3D12Device::CreatePlacedResource");

            return new D3D12Resource(out.get(ADDRESS, 0));
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * @param desc a {@code D3D12_HEAP_DESC} ({@code abi.structs.D3D12HeapDesc})
     */
    public D3D12Heap createHeap(MemorySegment desc) {
        return new D3D12Heap(this.createFromDesc(Vtbl.ID3D12Device.CREATE_HEAP, desc, D3D12HeapDesc.SIZE, Iids.ID3D12Heap,
                "ID3D12Device::CreateHeap"));
    }

    /**
     * @param flags {@code D3D12_FENCE_FLAGS} ({@code Enums.FenceFlags})
     */
    public D3D12Fence createFence(long initialValue, int flags) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            int hr = (int) CREATE_FENCE.invokeExact(this.fn(Vtbl.ID3D12Device.CREATE_FENCE), this.ptr(),
                    initialValue, flags, Iids.ID3D12Fence, out);
            Hr.check(hr, "ID3D12Device::CreateFence");

            return new D3D12Fence(out.get(ADDRESS, 0));
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Creates a non-shared fence.
     */
    public D3D12Fence createFence(long initialValue) {
        return this.createFence(initialValue, Enums.FenceFlags.NONE);
    }

    /**
     * @param desc a {@code D3D12_QUERY_HEAP_DESC} ({@link D3D12QueryHeapDesc})
     */
    public D3D12QueryHeap createQueryHeap(MemorySegment desc) {
        return new D3D12QueryHeap(this.createFromDesc(Vtbl.ID3D12Device.CREATE_QUERY_HEAP, desc, D3D12QueryHeapDesc.SIZE,
                Iids.ID3D12QueryHeap, "ID3D12Device::CreateQueryHeap"));
    }

    /**
     * @param type  a {@code D3D12_QUERY_HEAP_TYPE} ({@code Enums.QueryHeapType})
     * @param count number of queries
     */
    public D3D12QueryHeap createQueryHeap(int type, int count) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment desc = D3D12QueryHeapDesc.allocate(arena);
            D3D12QueryHeapDesc.setType(desc, type);
            D3D12QueryHeapDesc.setCount(desc, count);

            return this.createQueryHeap(desc);
        }
    }

    /**
     * @param desc          a {@code D3D12_COMMAND_SIGNATURE_DESC} ({@code abi.structs.D3D12CommandSignatureDesc})
     * @param rootSignature the root signature, required if the signature changes root arguments, otherwise {@code null}
     */
    public D3D12CommandSignature createCommandSignature(MemorySegment desc, D3D12RootSignature rootSignature) {
        requireSize(desc, D3D12CommandSignatureDesc.SIZE, "desc");

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            int hr = (int) CREATE_COMMAND_SIGNATURE.invokeExact(this.fn(Vtbl.ID3D12Device.CREATE_COMMAND_SIGNATURE), this.ptr(),
                    desc, ptrOrNull(rootSignature), Iids.ID3D12CommandSignature, out);
            Hr.check(hr, "ID3D12Device::CreateCommandSignature");

            return new D3D12CommandSignature(out.get(ADDRESS, 0));
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Descriptors (hot path, do not allocate)
    // ------------------------------------------------------------------------------------------------------------

    /**
     * @param desc          a {@code D3D12_CONSTANT_BUFFER_VIEW_DESC} ({@code abi.structs.D3D12ConstantBufferViewDesc})
     * @param destDescriptor the CPU descriptor handle to write
     */
    public void createConstantBufferView(MemorySegment desc, long destDescriptor) {
        requireSize(desc, D3D12ConstantBufferViewDesc.SIZE, "desc");

        try {
            CREATE_CBV.invokeExact(this.fn(Vtbl.ID3D12Device.CREATE_CONSTANT_BUFFER_VIEW), this.ptr(), desc, destDescriptor);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * @param resource       the resource, or {@code null} to create a null descriptor (requires {@code desc})
     * @param desc           a {@code D3D12_SHADER_RESOURCE_VIEW_DESC}, or {@code null} for the resource's default view
     * @param destDescriptor the CPU descriptor handle to write
     */
    public void createShaderResourceView(D3D12Resource resource, MemorySegment desc, long destDescriptor) {
        requireSizeIfPresent(desc, D3D12ShaderResourceViewDesc.SIZE, "desc");

        try {
            CREATE_VIEW.invokeExact(this.fn(Vtbl.ID3D12Device.CREATE_SHADER_RESOURCE_VIEW), this.ptr(), ptrOrNull(resource), orNull(desc), destDescriptor);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * @param resource        the resource, or {@code null} to create a null descriptor
     * @param counterResource the resource holding the UAV counter, or {@code null}
     * @param desc            a {@code D3D12_UNORDERED_ACCESS_VIEW_DESC}, or {@code null} for the default view
     * @param destDescriptor  the CPU descriptor handle to write
     */
    public void createUnorderedAccessView(D3D12Resource resource, D3D12Resource counterResource, MemorySegment desc, long destDescriptor) {
        requireSizeIfPresent(desc, D3D12UnorderedAccessViewDesc.SIZE, "desc");

        try {
            CREATE_UAV.invokeExact(this.fn(Vtbl.ID3D12Device.CREATE_UNORDERED_ACCESS_VIEW), this.ptr(), ptrOrNull(resource),
                    ptrOrNull(counterResource), orNull(desc), destDescriptor);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * @param resource       the render target, or {@code null} to create a null descriptor
     * @param desc           a {@code D3D12_RENDER_TARGET_VIEW_DESC}, or {@code null} for the default view
     * @param destDescriptor the CPU descriptor handle to write
     */
    public void createRenderTargetView(D3D12Resource resource, MemorySegment desc, long destDescriptor) {
        requireSizeIfPresent(desc, D3D12RenderTargetViewDesc.SIZE, "desc");

        try {
            CREATE_VIEW.invokeExact(this.fn(Vtbl.ID3D12Device.CREATE_RENDER_TARGET_VIEW), this.ptr(), ptrOrNull(resource), orNull(desc), destDescriptor);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * @param resource       the depth-stencil texture, or {@code null} to create a null descriptor
     * @param desc           a {@code D3D12_DEPTH_STENCIL_VIEW_DESC}, or {@code null} for the default view
     * @param destDescriptor the CPU descriptor handle to write
     */
    public void createDepthStencilView(D3D12Resource resource, MemorySegment desc, long destDescriptor) {
        requireSizeIfPresent(desc, D3D12DepthStencilViewDesc.SIZE, "desc");

        try {
            CREATE_VIEW.invokeExact(this.fn(Vtbl.ID3D12Device.CREATE_DEPTH_STENCIL_VIEW), this.ptr(), ptrOrNull(resource), orNull(desc), destDescriptor);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * @param desc           a {@code D3D12_SAMPLER_DESC} ({@code abi.structs.D3D12SamplerDesc})
     * @param destDescriptor the CPU descriptor handle (in a sampler heap) to write
     */
    public void createSampler(MemorySegment desc, long destDescriptor) {
        requireSize(desc, D3D12SamplerDesc.SIZE, "desc");

        try {
            CREATE_CBV.invokeExact(this.fn(Vtbl.ID3D12Device.CREATE_SAMPLER), this.ptr(), desc, destDescriptor);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Copies ranges of descriptors between heaps (the destination ranges must lie in non-shader-visible or write
     * combined memory, the sources in non-shader-visible heaps).
     *
     * @param dstRangeCount number of destination ranges
     * @param dstRangeStarts native array of {@code dstRangeCount} CPU descriptor handles (8 bytes each)
     * @param dstRangeSizes native array of {@code dstRangeCount} UINT range sizes, or {@code null} if every range has one descriptor
     * @param srcRangeCount number of source ranges
     * @param srcRangeStarts native array of {@code srcRangeCount} CPU descriptor handles
     * @param srcRangeSizes native array of {@code srcRangeCount} UINT range sizes, or {@code null} if every range has one descriptor
     * @param heapType a {@code D3D12_DESCRIPTOR_HEAP_TYPE} ({@code Enums.DescriptorHeapType})
     */
    public void copyDescriptors(int dstRangeCount, MemorySegment dstRangeStarts, MemorySegment dstRangeSizes,
                                int srcRangeCount, MemorySegment srcRangeStarts, MemorySegment srcRangeSizes, int heapType) {
        requireArray(dstRangeCount, dstRangeStarts, 8, "dstRangeStarts");
        requireArrayIfPresent(dstRangeCount, dstRangeSizes, 4, "dstRangeSizes");
        requireArray(srcRangeCount, srcRangeStarts, 8, "srcRangeStarts");
        requireArrayIfPresent(srcRangeCount, srcRangeSizes, 4, "srcRangeSizes");

        try {
            COPY_DESCRIPTORS.invokeExact(this.fn(Vtbl.ID3D12Device.COPY_DESCRIPTORS), this.ptr(), dstRangeCount, dstRangeStarts,
                    orNull(dstRangeSizes), srcRangeCount, srcRangeStarts, orNull(srcRangeSizes), heapType);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Copies {@code count} consecutive descriptors.
     *
     * @param destStart the CPU handle of the first destination descriptor
     * @param srcStart  the CPU handle of the first source descriptor
     * @param heapType  a {@code D3D12_DESCRIPTOR_HEAP_TYPE} ({@code Enums.DescriptorHeapType})
     */
    public void copyDescriptorsSimple(int count, long destStart, long srcStart, int heapType) {
        try {
            COPY_DESCRIPTORS_SIMPLE.invokeExact(this.fn(Vtbl.ID3D12Device.COPY_DESCRIPTORS_SIMPLE), this.ptr(), count, destStart, srcStart, heapType);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Memory
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Raw form of GetResourceAllocationInfo. Does not allocate.
     *
     * @param visibleMask the GPU node mask
     * @param count       number of resource descriptions
     * @param descs       native array of {@code count} {@code D3D12_RESOURCE_DESC}
     * @param out         native {@code D3D12_RESOURCE_ALLOCATION_INFO} ({@link D3D12ResourceAllocationInfo}) receiving the result
     */
    public void getResourceAllocationInfo(int visibleMask, int count, MemorySegment descs, MemorySegment out) {
        requireArray(count, descs, D3D12ResourceDesc.SIZE, "descs");
        requireSize(out, D3D12ResourceAllocationInfo.SIZE, "out");

        try {
            long ignored = (long) GET_RESOURCE_ALLOCATION_INFO.invokeExact(this.fn(Vtbl.ID3D12Device.GET_RESOURCE_ALLOCATION_INFO),
                    this.ptr(), out, visibleMask, count, descs);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Size and alignment a resource would need in a heap.
     */
    public record AllocationInfo(long sizeInBytes, long alignment) {
    }

    /**
     * @param desc a {@code D3D12_RESOURCE_DESC}
     */
    public AllocationInfo getResourceAllocationInfo(MemorySegment desc) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = D3D12ResourceAllocationInfo.allocate(arena);
            this.getResourceAllocationInfo(0, 1, desc, out);

            return new AllocationInfo(D3D12ResourceAllocationInfo.getSizeInBytes(out), D3D12ResourceAllocationInfo.getAlignment(out));
        }
    }

    /**
     * Fills the heap properties that describe a custom heap equivalent to a standard heap type.
     *
     * @param nodeMask the GPU node mask
     * @param heapType a {@code D3D12_HEAP_TYPE} ({@code Enums.HeapType}), not CUSTOM
     * @param out      native {@code D3D12_HEAP_PROPERTIES} ({@link D3D12HeapProperties}) receiving the result
     */
    public void getCustomHeapProperties(int nodeMask, int heapType, MemorySegment out) {
        requireSize(out, D3D12HeapProperties.SIZE, "out");

        try {
            long ignored = (long) GET_CUSTOM_HEAP_PROPERTIES.invokeExact(this.fn(Vtbl.ID3D12Device.GET_CUSTOM_HEAP_PROPERTIES),
                    this.ptr(), out, nodeMask, heapType);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Makes objects resident in GPU memory. Does not allocate when given a prepared array.
     *
     * @param objectCount number of objects
     * @param objects     native array of {@code objectCount} {@code ID3D12Pageable*} (8 bytes each: heaps, committed
     *                    resources, descriptor heaps, query heaps, pipeline states, ...; see {@link Pageable})
     * @return the raw HRESULT; {@code E_OUTOFMEMORY} means there is not enough video memory and the caller should
     * evict something
     */
    public int makeResident(int objectCount, MemorySegment objects) {
        requireArray(objectCount, objects, 8, "objects");

        try {
            return (int) RESIDENCY.invokeExact(this.fn(Vtbl.ID3D12Device.MAKE_RESIDENT), this.ptr(), objectCount, objects);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Makes one object resident in GPU memory. Allocates a few bytes of temporary native memory.
     *
     * @return the raw HRESULT, see {@link #makeResident(int, MemorySegment)}
     */
    public int makeResident(Pageable pageable) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment array = arena.allocate(ADDRESS);
            array.set(ADDRESS, 0, pageable.ptr());

            return this.makeResident(1, array);
        }
    }

    /**
     * Marks objects as evictable from GPU memory.
     *
     * @param objectCount number of objects
     * @param objects     native array of {@code objectCount} {@code ID3D12Pageable*}
     * @throws net.caffeinemc.mods.sodium.client.d3d12.interop.D3D12Exception if the call fails
     */
    public void evict(int objectCount, MemorySegment objects) {
        requireArray(objectCount, objects, 8, "objects");

        try {
            int hr = (int) RESIDENCY.invokeExact(this.fn(Vtbl.ID3D12Device.EVICT), this.ptr(), objectCount, objects);
            Hr.check(hr, "ID3D12Device::Evict");
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Marks one object as evictable from GPU memory. Allocates a few bytes of temporary native memory.
     */
    public void evict(Pageable pageable) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment array = arena.allocate(ADDRESS);
            array.set(ADDRESS, 0, pageable.ptr());
            this.evict(1, array);
        }
    }

    /**
     * Queries the memory layout of texture subresources for use with buffer-to-texture copies. Does not allocate.
     *
     * @param resourceDesc     a {@code D3D12_RESOURCE_DESC}
     * @param firstSubresource index of the first subresource
     * @param subresourceCount number of subresources
     * @param baseOffset       offset added to every returned footprint offset
     * @param layouts          native array of {@code subresourceCount} {@code D3D12_PLACED_SUBRESOURCE_FOOTPRINT}
     *                         ({@link D3D12PlacedSubresourceFootprint}), or {@code null}
     * @param numRows          native array of {@code subresourceCount} UINT row counts, or {@code null}
     * @param rowSizeInBytes   native array of {@code subresourceCount} UINT64 unpadded row sizes, or {@code null}
     * @param totalBytes       8 bytes receiving the total size needed in the buffer, or {@code null}
     */
    public void getCopyableFootprints(MemorySegment resourceDesc, int firstSubresource, int subresourceCount, long baseOffset,
                                      MemorySegment layouts, MemorySegment numRows, MemorySegment rowSizeInBytes, MemorySegment totalBytes) {
        requireSize(resourceDesc, D3D12ResourceDesc.SIZE, "resourceDesc");
        requireArrayIfPresent(subresourceCount, layouts, D3D12PlacedSubresourceFootprint.SIZE, "layouts");
        requireArrayIfPresent(subresourceCount, numRows, 4, "numRows");
        requireArrayIfPresent(subresourceCount, rowSizeInBytes, 8, "rowSizeInBytes");
        requireSizeIfPresent(totalBytes, 8, "totalBytes");

        try {
            GET_COPYABLE_FOOTPRINTS.invokeExact(this.fn(Vtbl.ID3D12Device.GET_COPYABLE_FOOTPRINTS), this.ptr(), resourceDesc,
                    firstSubresource, subresourceCount, baseOffset, orNull(layouts), orNull(numRows), orNull(rowSizeInBytes), orNull(totalBytes));
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * The upload layout of one subresource.
     *
     * @param offset         byte offset of the subresource in the buffer
     * @param format         the {@code DXGI_FORMAT}
     * @param width          width in texels
     * @param height         height in texels
     * @param depth          depth in texels
     * @param rowPitch       padded size of a row in bytes
     * @param numRows        number of rows
     * @param rowSizeInBytes unpadded size of a row in bytes
     * @param totalBytes     total bytes the subresource needs in the buffer (relative to {@code baseOffset})
     */
    public record Footprint(long offset, int format, int width, int height, int depth, int rowPitch, int numRows,
                            long rowSizeInBytes, long totalBytes) {
    }

    /**
     * Convenience form of GetCopyableFootprints for one subresource. Allocates temporary native memory.
     */
    public Footprint getCopyableFootprint(MemorySegment resourceDesc, int subresource, long baseOffset) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment layout = D3D12PlacedSubresourceFootprint.allocate(arena);
            MemorySegment rows = arena.allocate(JAVA_INT);
            MemorySegment rowSize = arena.allocate(JAVA_LONG);
            MemorySegment total = arena.allocate(JAVA_LONG);
            this.getCopyableFootprints(resourceDesc, subresource, 1, baseOffset, layout, rows, rowSize, total);

            MemorySegment footprint = D3D12PlacedSubresourceFootprint.sliceFootprint(layout);

            return new Footprint(D3D12PlacedSubresourceFootprint.getOffset(layout), D3D12SubresourceFootprint.getFormat(footprint),
                    D3D12SubresourceFootprint.getWidth(footprint), D3D12SubresourceFootprint.getHeight(footprint),
                    D3D12SubresourceFootprint.getDepth(footprint), D3D12SubresourceFootprint.getRowPitch(footprint),
                    rows.get(JAVA_INT, 0), rowSize.get(JAVA_LONG, 0), total.get(JAVA_LONG, 0));
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Feature support
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Generic CheckFeatureSupport.
     *
     * @param feature  a {@code D3D12_FEATURE} ({@code Enums.Feature})
     * @param data     native memory holding the {@code D3D12_FEATURE_DATA_*} struct matching {@code feature}; input
     *                 fields are read from it and output fields written to it
     * @param dataSize size of the struct in bytes
     * @return the raw HRESULT ({@code S_OK}, or for example {@code E_INVALIDARG} when the feature or an input value is
     * unknown to the runtime)
     */
    public int checkFeatureSupport(int feature, MemorySegment data, int dataSize) {
        requireSize(data, Integer.toUnsignedLong(dataSize), "data");

        try {
            return (int) CHECK_FEATURE_SUPPORT.invokeExact(this.fn(Vtbl.ID3D12Device.CHECK_FEATURE_SUPPORT), this.ptr(), feature, data, dataSize);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Decoded {@code D3D12_FEATURE_DATA_D3D12_OPTIONS}. The ints are the raw enum values ({@code Enums.ResourceBindingTier},
     * {@code Enums.TiledResourcesTier}, {@code Enums.ResourceHeapTier}, {@code Enums.ConservativeRasterizationTier},
     * {@code Enums.CrossNodeSharingTier}, {@code Enums.ShaderMinPrecisionSupport} flags).
     */
    public record FeatureOptions(boolean doublePrecisionFloatShaderOps, boolean outputMergerLogicOp, int minPrecisionSupport,
                                 int tiledResourcesTier, int resourceBindingTier, boolean psSpecifiedStencilRefSupported,
                                 boolean typedUavLoadAdditionalFormats, boolean rovsSupported, int conservativeRasterizationTier,
                                 int maxGpuVirtualAddressBitsPerResource, boolean standardSwizzle64KbSupported,
                                 int crossNodeSharingTier, boolean crossAdapterRowMajorTextureSupported,
                                 boolean vpAndRtArrayIndexFromAnyShaderFeedingRasterizerSupportedWithoutGsEmulation,
                                 int resourceHeapTier) {
    }

    /**
     * @throws net.caffeinemc.mods.sodium.client.d3d12.interop.D3D12Exception if the query fails
     */
    public FeatureOptions checkOptions() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment s = D3D12FeatureDataD3D12Options.allocate(arena);
            Hr.check(this.checkFeatureSupport(Enums.Feature.D3D12_OPTIONS, s, (int) D3D12FeatureDataD3D12Options.SIZE),
                    "ID3D12Device::CheckFeatureSupport(D3D12_OPTIONS)");

            return new FeatureOptions(D3D12FeatureDataD3D12Options.getDoublePrecisionFloatShaderOps(s),
                    D3D12FeatureDataD3D12Options.getOutputMergerLogicOp(s), D3D12FeatureDataD3D12Options.getMinPrecisionSupport(s),
                    D3D12FeatureDataD3D12Options.getTiledResourcesTier(s), D3D12FeatureDataD3D12Options.getResourceBindingTier(s),
                    D3D12FeatureDataD3D12Options.getPSSpecifiedStencilRefSupported(s),
                    D3D12FeatureDataD3D12Options.getTypedUAVLoadAdditionalFormats(s), D3D12FeatureDataD3D12Options.getROVsSupported(s),
                    D3D12FeatureDataD3D12Options.getConservativeRasterizationTier(s),
                    D3D12FeatureDataD3D12Options.getMaxGPUVirtualAddressBitsPerResource(s),
                    D3D12FeatureDataD3D12Options.getStandardSwizzle64KBSupported(s), D3D12FeatureDataD3D12Options.getCrossNodeSharingTier(s),
                    D3D12FeatureDataD3D12Options.getCrossAdapterRowMajorTextureSupported(s),
                    D3D12FeatureDataD3D12Options.getVPAndRTArrayIndexFromAnyShaderFeedingRasterizerSupportedWithoutGSEmulation(s),
                    D3D12FeatureDataD3D12Options.getResourceHeapTier(s));
        }
    }

    /**
     * Result of a format support query.
     *
     * @param hresult  the HRESULT of the query ({@code S_OK}, or a failure such as {@code E_INVALIDARG} for a format the
     *                 runtime does not know); on failure both masks are 0
     * @param support1 {@code D3D12_FORMAT_SUPPORT1} flags ({@code Enums.FormatSupport1})
     * @param support2 {@code D3D12_FORMAT_SUPPORT2} flags ({@code Enums.FormatSupport2})
     */
    public record FormatSupport(int hresult, int support1, int support2) {
        /**
         * @param flag one or more {@code Enums.FormatSupport1} bits; all of them must be supported
         */
        public boolean supports1(int flag) {
            return this.hresult >= 0 && (this.support1 & flag) == flag;
        }

        /**
         * @param flag one or more {@code Enums.FormatSupport2} bits; all of them must be supported
         */
        public boolean supports2(int flag) {
            return this.hresult >= 0 && (this.support2 & flag) == flag;
        }
    }

    /**
     * Queries what a {@code DXGI_FORMAT} can be used for. Never throws on a failing query: see {@link FormatSupport#hresult()}.
     */
    public FormatSupport checkFormatSupport(int format) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment s = D3D12FeatureDataFormatSupport.allocate(arena);
            D3D12FeatureDataFormatSupport.setFormat(s, format);
            int hr = this.checkFeatureSupport(Enums.Feature.FORMAT_SUPPORT, s, (int) D3D12FeatureDataFormatSupport.SIZE);

            if (hr < 0) {
                return new FormatSupport(hr, 0, 0);
            }

            return new FormatSupport(hr, D3D12FeatureDataFormatSupport.getSupport1(s), D3D12FeatureDataFormatSupport.getSupport2(s));
        }
    }

    /**
     * @param format      a {@code DXGI_FORMAT}
     * @param sampleCount the MSAA sample count to test (a power of two)
     * @param flags       {@code D3D12_MULTISAMPLE_QUALITY_LEVEL_FLAGS} ({@code Enums.MultisampleQualityLevelFlags})
     * @return the number of quality levels supported for the combination, 0 if it is unsupported
     * @throws net.caffeinemc.mods.sodium.client.d3d12.interop.D3D12Exception if the query fails
     */
    public int checkMultisampleQualityLevels(int format, int sampleCount, int flags) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment s = D3D12FeatureDataMultisampleQualityLevels.allocate(arena);
            D3D12FeatureDataMultisampleQualityLevels.setFormat(s, format);
            D3D12FeatureDataMultisampleQualityLevels.setSampleCount(s, sampleCount);
            D3D12FeatureDataMultisampleQualityLevels.setFlags(s, flags);
            Hr.check(this.checkFeatureSupport(Enums.Feature.MULTISAMPLE_QUALITY_LEVELS, s, (int) D3D12FeatureDataMultisampleQualityLevels.SIZE),
                    "ID3D12Device::CheckFeatureSupport(MULTISAMPLE_QUALITY_LEVELS)");

            return D3D12FeatureDataMultisampleQualityLevels.getNumQualityLevels(s);
        }
    }

    /**
     * Decoded {@code D3D12_FEATURE_DATA_GPU_VIRTUAL_ADDRESS_SUPPORT}.
     */
    public record GpuVirtualAddressSupport(int maxBitsPerResource, int maxBitsPerProcess) {
    }

    /**
     * @throws net.caffeinemc.mods.sodium.client.d3d12.interop.D3D12Exception if the query fails
     */
    public GpuVirtualAddressSupport checkGpuVirtualAddressSupport() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment s = D3D12FeatureDataGpuVirtualAddressSupport.allocate(arena);
            Hr.check(this.checkFeatureSupport(Enums.Feature.GPU_VIRTUAL_ADDRESS_SUPPORT, s, (int) D3D12FeatureDataGpuVirtualAddressSupport.SIZE),
                    "ID3D12Device::CheckFeatureSupport(GPU_VIRTUAL_ADDRESS_SUPPORT)");

            return new GpuVirtualAddressSupport(D3D12FeatureDataGpuVirtualAddressSupport.getMaxGPUVirtualAddressBitsPerResource(s),
                    D3D12FeatureDataGpuVirtualAddressSupport.getMaxGPUVirtualAddressBitsPerProcess(s));
        }
    }

    /**
     * Decoded {@code D3D12_FEATURE_DATA_ARCHITECTURE}.
     *
     * @param tileBasedRenderer whether the GPU is a tile-based renderer
     * @param uma               whether the GPU has unified memory (integrated GPU)
     * @param cacheCoherentUma  whether CPU and GPU caches are coherent (only meaningful if {@code uma})
     */
    public record Architecture(int nodeIndex, boolean tileBasedRenderer, boolean uma, boolean cacheCoherentUma) {
    }

    /**
     * @param nodeIndex the GPU node to query (0 on single-GPU systems)
     * @throws net.caffeinemc.mods.sodium.client.d3d12.interop.D3D12Exception if the query fails
     */
    public Architecture checkArchitecture(int nodeIndex) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment s = D3D12FeatureDataArchitecture.allocate(arena);
            D3D12FeatureDataArchitecture.setNodeIndex(s, nodeIndex);
            Hr.check(this.checkFeatureSupport(Enums.Feature.ARCHITECTURE, s, (int) D3D12FeatureDataArchitecture.SIZE),
                    "ID3D12Device::CheckFeatureSupport(ARCHITECTURE)");

            return new Architecture(D3D12FeatureDataArchitecture.getNodeIndex(s), D3D12FeatureDataArchitecture.getTileBasedRenderer(s),
                    D3D12FeatureDataArchitecture.getUMA(s), D3D12FeatureDataArchitecture.getCacheCoherentUMA(s));
        }
    }

    /**
     * @return the highest root signature version the device supports ({@code Enums.RootSignatureVersion._1_1} or
     * {@code _1_0}); a runtime that does not know version 1.1 answers {@code E_INVALIDARG}, which is reported as 1.0
     * @throws net.caffeinemc.mods.sodium.client.d3d12.interop.D3D12Exception for any other failure
     */
    public int checkRootSignatureVersion() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment s = D3D12FeatureDataRootSignature.allocate(arena);
            D3D12FeatureDataRootSignature.setHighestVersion(s, Enums.RootSignatureVersion._1_1);
            int hr = this.checkFeatureSupport(Enums.Feature.ROOT_SIGNATURE, s, (int) D3D12FeatureDataRootSignature.SIZE);

            if (hr == Hr.E_INVALIDARG) {
                return Enums.RootSignatureVersion._1_0;
            }

            Hr.check(hr, "ID3D12Device::CheckFeatureSupport(ROOT_SIGNATURE)");

            return D3D12FeatureDataRootSignature.getHighestVersion(s);
        }
    }

    /**
     * Asks whether the device supports shader models up to {@code requestedHighest}.
     *
     * @param requestedHighest a shader model constant ({@link #SHADER_MODEL_6_0}, ...)
     * @return the HRESULT; on success {@code highestOut} receives the highest supported shader model. {@code E_INVALIDARG}
     * means the runtime does not know the requested model; retry with a lower one or use {@link #checkHighestShaderModel()}
     */
    public int checkShaderModel(int requestedHighest, MemorySegment highestOut) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment s = D3D12FeatureDataShaderModel.allocate(arena);
            D3D12FeatureDataShaderModel.setHighestShaderModel(s, requestedHighest);
            int hr = this.checkFeatureSupport(Enums.Feature.SHADER_MODEL, s, (int) D3D12FeatureDataShaderModel.SIZE);

            if (hr >= 0 && highestOut != null) {
                highestOut.set(JAVA_INT, 0, D3D12FeatureDataShaderModel.getHighestShaderModel(s));
            }

            return hr;
        }
    }

    /**
     * @return the highest shader model ({@code 0x51} = 5.1, {@code 0x60} = 6.0 ... {@code 0x69} = 6.9) the device
     * supports, or 0 if the query is not supported at all (very old runtimes only know 5.1)
     */
    public int checkHighestShaderModel() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(JAVA_INT);

            // the runtime answers E_INVALIDARG for models it does not know, so walk down from the newest
            for (int model = SHADER_MODEL_6_9; model >= SHADER_MODEL_6_0; model--) {
                if (this.checkShaderModel(model, out) >= 0) {
                    return out.get(JAVA_INT, 0);
                }
            }

            if (this.checkShaderModel(SHADER_MODEL_5_1, out) >= 0) {
                return out.get(JAVA_INT, 0);
            }

            return 0;
        }
    }
}
