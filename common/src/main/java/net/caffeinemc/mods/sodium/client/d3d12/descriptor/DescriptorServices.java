// Owner: WP4 (descriptors) - implemented
package net.caffeinemc.mods.sodium.client.d3d12.descriptor;

import net.caffeinemc.mods.sodium.client.d3d12.backend.DeviceOptions;
import net.caffeinemc.mods.sodium.client.d3d12.core.D3D12Log;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Enums;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12Device;

/**
 * Bundle of every descriptor facility, created once by {@code D3D12GpuDevice.create} and exposed through
 * {@code DeviceContext.descriptors()}.
 *
 * <ul>
 *     <li>CPU staging allocators: {@link #cbvSrvUav()} (page 4096), {@link #rtv()} (512), {@link #dsv()} (128),
 *     {@link #sampler()} (1024, hardware limit 2048). Owners: {@code D3D12TextureView} (SRV), {@code D3D12Texture}
 *     (RTV/DSV per mip), {@code D3D12Sampler}, the swap chain (RTV per back buffer).</li>
 *     <li>{@link #nulls()}: substitutes for everything unset.</li>
 *     <li>{@link #srvRing()} / {@link #samplerRing()}: the two shader-visible rings (2 frame slots each). Each ring owns
 *     its {@code DescriptorTableCache} and its heap generation ({@code epoch()}); nothing else clears the caches.</li>
 * </ul>
 *
 * <p>Sizes (ARCHITECTURE 2.d): shader-visible CBV/SRV/UAV pages of {@value #SRV_PAGE_DESCRIPTORS} descriptors (2048 tables of
 * 16), SAMPLER pages of {@value #SAMPLER_PAGE_DESCRIPTORS} (the hardware limit, 128 tables), at most
 * {@value #MAX_RING_PAGES} pages per frame slot, 2 frame slots. {@link #close()} releases everything in reverse creation
 * order and must only run when the GPU is idle; it is idempotent and never throws.
 */
public final class DescriptorServices implements AutoCloseable {
    /** Descriptors per shader-visible CBV/SRV/UAV page (2048 tables of 16). */
    public static final int SRV_PAGE_DESCRIPTORS = 32768;
    /** Descriptors per shader-visible sampler page: the hardware limit of a sampler heap (128 tables of 16). */
    public static final int SAMPLER_PAGE_DESCRIPTORS = 2048;
    /** Hard limit on pages per frame slot and ring. */
    public static final int MAX_RING_PAGES = 32;
    /** Frame slots (frames in flight). */
    public static final int FRAME_SLOTS = 2;

    private static final int CPU_PAGE_CBV_SRV_UAV = 4096;
    private static final int CPU_PAGE_RTV = 512;
    private static final int CPU_PAGE_DSV = 128;
    private static final int CPU_PAGE_SAMPLER = 1024;
    /** Largest shader-visible CBV/SRV/UAV heap that every resource binding tier supports (tier 1: 1,000,000). */
    private static final int MAX_SRV_PAGE_DESCRIPTORS = 1_000_000;
    /** D3D12_MAX_SHADER_VISIBLE_SAMPLER_HEAP_SIZE. */
    private static final int MAX_SAMPLER_PAGE_DESCRIPTORS = 2048;

    private final CpuDescriptorAllocator cbvSrvUav;
    private final CpuDescriptorAllocator rtv;
    private final CpuDescriptorAllocator dsv;
    private final CpuDescriptorAllocator sampler;
    private final NullDescriptors nulls;
    private final ShaderVisibleRing srvRing;
    private final ShaderVisibleRing samplerRing;
    private boolean closed;

    private DescriptorServices(CpuDescriptorAllocator cbvSrvUav, CpuDescriptorAllocator rtv, CpuDescriptorAllocator dsv,
                               CpuDescriptorAllocator sampler, NullDescriptors nulls, ShaderVisibleRing srvRing,
                               ShaderVisibleRing samplerRing) {
        this.cbvSrvUav = cbvSrvUav;
        this.rtv = rtv;
        this.dsv = dsv;
        this.sampler = sampler;
        this.nulls = nulls;
        this.srvRing = srvRing;
        this.samplerRing = samplerRing;
    }

    /** Builds everything; on failure releases what was built and rethrows. */
    public static DescriptorServices create(D3D12Device device, DeviceOptions options) {
        return create(device, options, SRV_PAGE_DESCRIPTORS, SAMPLER_PAGE_DESCRIPTORS);
    }

    /**
     * Test overload with explicit shader-visible page sizes (descriptors per page, a multiple of 16): tiny pages force
     * page flips so that the binder's heap-switch protocol ({@code ShaderVisibleRing.reserve}) can be exercised with a
     * handful of draws ({@code DescriptorPagingTest}, X8/X21).
     *
     * @param srvPageDescriptors     descriptors per CBV/SRV/UAV page, {@code 16..1,000,000}, a multiple of 16
     * @param samplerPageDescriptors descriptors per sampler page, {@code 16..2048}, a multiple of 16
     * @throws IllegalArgumentException for a page size outside these limits (nothing was created)
     */
    public static DescriptorServices create(D3D12Device device, DeviceOptions options, int srvPageDescriptors,
                                            int samplerPageDescriptors) {
        requirePageSize("CBV/SRV/UAV", srvPageDescriptors, MAX_SRV_PAGE_DESCRIPTORS);
        requirePageSize("sampler", samplerPageDescriptors, MAX_SAMPLER_PAGE_DESCRIPTORS);

        CpuDescriptorAllocator cbvSrvUav = null;
        CpuDescriptorAllocator rtv = null;
        CpuDescriptorAllocator dsv = null;
        CpuDescriptorAllocator sampler = null;
        NullDescriptors nulls = null;
        ShaderVisibleRing srvRing = null;
        ShaderVisibleRing samplerRing = null;

        try {
            cbvSrvUav = new CpuDescriptorAllocator(device, Enums.DescriptorHeapType.CBV_SRV_UAV, CPU_PAGE_CBV_SRV_UAV);
            rtv = new CpuDescriptorAllocator(device, Enums.DescriptorHeapType.RTV, CPU_PAGE_RTV);
            dsv = new CpuDescriptorAllocator(device, Enums.DescriptorHeapType.DSV, CPU_PAGE_DSV);
            sampler = new CpuDescriptorAllocator(device, Enums.DescriptorHeapType.SAMPLER, CPU_PAGE_SAMPLER);

            nulls = NullDescriptors.create(device, cbvSrvUav, rtv, sampler);

            if (options.debuggingEnabled()) {
                nulls.label();
            }

            srvRing = new ShaderVisibleRing(device, Enums.DescriptorHeapType.CBV_SRV_UAV, srvPageDescriptors, MAX_RING_PAGES,
                    FRAME_SLOTS);
            samplerRing = new ShaderVisibleRing(device, Enums.DescriptorHeapType.SAMPLER, samplerPageDescriptors,
                    MAX_RING_PAGES, FRAME_SLOTS);

            return new DescriptorServices(cbvSrvUav, rtv, dsv, sampler, nulls, srvRing, samplerRing);
        } catch (Throwable t) {
            // reverse creation order; the GPU has not seen any of these objects
            closeQuietly(samplerRing);
            closeQuietly(srvRing);
            closeQuietly(nulls);
            closeQuietly(sampler);
            closeQuietly(dsv);
            closeQuietly(rtv);
            closeQuietly(cbvSrvUav);

            throw t;
        }
    }

    private static void requirePageSize(String what, int descriptors, int maximum) {
        if (descriptors < 16 || descriptors > maximum || descriptors % 16 != 0) {
            throw new IllegalArgumentException("The " + what + " descriptor page size must be a multiple of 16 in 16.." + maximum
                    + " but is " + descriptors);
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }

        try {
            closeable.close();
        } catch (Exception e) {
            D3D12Log.warn("Could not release a descriptor facility: {}", e.toString());
        }
    }

    public CpuDescriptorAllocator cbvSrvUav() {
        return this.cbvSrvUav;
    }

    public CpuDescriptorAllocator rtv() {
        return this.rtv;
    }

    public CpuDescriptorAllocator dsv() {
        return this.dsv;
    }

    public CpuDescriptorAllocator sampler() {
        return this.sampler;
    }

    public NullDescriptors nulls() {
        return this.nulls;
    }

    public ShaderVisibleRing srvRing() {
        return this.srvRing;
    }

    public ShaderVisibleRing samplerRing() {
        return this.samplerRing;
    }

    /** Rewinds both rings for the frame about to be recorded; called by {@code CommandRecorder.beginFrame}. */
    public void beginFrame(int slot) {
        this.srvRing.beginFrame(slot);
        this.samplerRing.beginFrame(slot);
    }

    /**
     * Releases the rings, the null descriptors and the allocators in reverse creation order. The GPU must be idle (the
     * caller is {@code D3D12GpuDevice.close} after the encoder marked it so). Idempotent; never throws.
     */
    @Override
    public void close() {
        if (this.closed) {
            return;
        }

        this.closed = true;

        closeQuietly(this.samplerRing);
        closeQuietly(this.srvRing);
        closeQuietly(this.nulls);
        closeQuietly(this.sampler);
        closeQuietly(this.dsv);
        closeQuietly(this.rtv);
        closeQuietly(this.cbvSrvUav);
    }
}
