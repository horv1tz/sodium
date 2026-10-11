// Owner: WP4 (descriptors)
package net.caffeinemc.mods.sodium.client.d3d12.descriptor;

import net.caffeinemc.mods.sodium.client.d3d12.backend.DeviceOptions;
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
 */
public final class DescriptorServices implements AutoCloseable {
    private final CpuDescriptorAllocator cbvSrvUav;
    private final CpuDescriptorAllocator rtv;
    private final CpuDescriptorAllocator dsv;
    private final CpuDescriptorAllocator sampler;
    private final NullDescriptors nulls;
    private final ShaderVisibleRing srvRing;
    private final ShaderVisibleRing samplerRing;

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
        throw new UnsupportedOperationException("WP4: allocators (D3D12 heap types CBV_SRV_UAV/RTV/DSV/SAMPLER), NullDescriptors, two rings");
    }

    /**
     * Test overload with explicit shader-visible page sizes (descriptors per page, a multiple of 16): tiny pages force
     * page flips so that the binder's heap-switch protocol ({@code ShaderVisibleRing.reserve}) can be exercised with a
     * handful of draws ({@code DescriptorPagingTest}, X8/X21).
     */
    public static DescriptorServices create(D3D12Device device, DeviceOptions options, int srvPageDescriptors,
                                            int samplerPageDescriptors) {
        throw new UnsupportedOperationException("WP4: as create(device, options) with the given page sizes");
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

    @Override
    public void close() {
        throw new UnsupportedOperationException("WP4: close rings, nulls, allocators in reverse creation order");
    }
}
