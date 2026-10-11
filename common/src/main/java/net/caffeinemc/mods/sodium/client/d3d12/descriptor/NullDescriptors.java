// Owner: WP4 (descriptors)
package net.caffeinemc.mods.sodium.client.d3d12.descriptor;

import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12Device;

/**
 * The valid-but-empty descriptors that fill every table entry and root argument no resource owns, so that every root
 * parameter always holds a legal value (invariant I5) - required on Resource Binding Tier 1, harmless elsewhere, and the
 * mechanism behind the "substitute and warn once" tolerance policy.
 *
 * <p>Created once at device construction, CPU-only (they are copied into the rings like any other descriptor):
 * <ul>
 *     <li>null SRV for {@code Texture2D<float4>} (RGBA8) and {@code TextureCube};</li>
 *     <li>null typed-buffer SRVs for {@code Buffer<int4>} ({@code R32_SINT}), {@code Buffer<uint4>} ({@code R32_UINT}) and
 *     {@code Buffer<float4>} ({@code R32_FLOAT}), selected by the slot's sampled base type ({@code Formats.BASE_*});</li>
 *     <li>a null RTV ({@code R8G8B8A8_UNORM}, NULL resource) for holes in the colour attachment list;</li>
 *     <li>a default sampler (point, clamp, {@code MaxLOD 0});</li>
 *     <li>{@link #zeroCbvVa()}: the GPU virtual address of a permanent zeroed 64 KiB UPLOAD buffer that every unset or
 *     unusable root CBV points to.</li>
 * </ul>
 * Each null descriptor is a distinct <em>kind</em> ({@code DescriptorTableCache.NULL_TEXTURE_2D, NULL_TEXTURE_CUBE,
 * NULL_BUFFER_INT, NULL_BUFFER_UINT, NULL_BUFFER_FLOAT, NULL_SAMPLER}): binding a null of the wrong dimension where the
 * shader declares another resource type is undefined behaviour, so the binder substitutes by the slot's expected kind and
 * the descriptor-table cache keys null entries structurally by kind ({@code DescriptorTableCache.nullKey}).
 */
public final class NullDescriptors implements AutoCloseable {
    private NullDescriptors() {
    }

    /** Creates the descriptors and the zero buffer. */
    public static NullDescriptors create(D3D12Device device, CpuDescriptorAllocator cbvSrvUav, CpuDescriptorAllocator rtv,
                                         CpuDescriptorAllocator sampler) {
        throw new UnsupportedOperationException("WP4: null SRVs (2D, cube, 3 buffer classes), null RTV, default sampler, zero CBV buffer");
    }

    /** @return CPU handle of the null {@code Texture2D} SRV */
    public long srv2d() {
        throw new UnsupportedOperationException("WP4");
    }

    /** @return CPU handle of the null {@code TextureCube} SRV */
    public long srvCube() {
        throw new UnsupportedOperationException("WP4");
    }

    /**
     * @param sampledBaseType {@code Formats.BASE_INT}, {@code BASE_UINT} or {@code BASE_FLOAT}
     * @return CPU handle of the null typed-buffer SRV of that element class
     */
    public long srvBuffer(int sampledBaseType) {
        throw new UnsupportedOperationException("WP4");
    }

    /** @return CPU handle of the null RTV */
    public long rtv() {
        throw new UnsupportedOperationException("WP4");
    }

    /** @return CPU handle of the default sampler descriptor */
    public long sampler() {
        throw new UnsupportedOperationException("WP4");
    }

    /** @return GPU virtual address (256 aligned, 64 KiB readable) of the zero constant buffer */
    public long zeroCbvVa() {
        throw new UnsupportedOperationException("WP4");
    }

    @Override
    public void close() {
        throw new UnsupportedOperationException("WP4: free descriptors, release the zero buffer");
    }
}
