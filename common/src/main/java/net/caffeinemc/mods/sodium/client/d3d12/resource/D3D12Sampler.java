// Owner: WP1 (device core)
package net.caffeinemc.mods.sodium.client.d3d12.resource;

import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import net.caffeinemc.mods.sodium.client.d3d12.core.DeviceContext;

import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A sampler: its creation parameters plus one CPU-only sampler descriptor (no sharing between samplers; descriptors are
 * copied by value into the shader-visible sampler ring, so closing a sampler can never invalidate a bound table).
 *
 * <p>Filter mapping (spec 04 section 8.2): the mip filter is <b>always LINEAR</b> (GL semantics; both values the game
 * uses for {@code maxLod}, "empty" and "0.0", behave identically in all backends), min/mag as given:
 * (N,N)=0x1, (N,L)=0x5, (L,N)=0x11, (L,L)=0x15, {@code ANISOTROPIC (0x55)} when {@code maxAnisotropy > 1};
 * {@code REPEAT -> WRAP}, {@code CLAMP_TO_EDGE -> CLAMP}, {@code AddressW = CLAMP}; {@code MinLOD = 0},
 * {@code MaxLOD = maxLod.isPresent() ? (float) maxLod : D3D12_FLOAT32_MAX}; {@code MipLODBias = 0}; compare NEVER;
 * border colour 0. Getters return exactly the creation parameters. {@code close()} is idempotent and deferred.
 */
public final class D3D12Sampler implements GpuSampler {
    private final DeviceContext ctx;
    private final AddressMode addressModeU;
    private final AddressMode addressModeV;
    private final FilterMode minFilter;
    private final FilterMode magFilter;
    private final int maxAnisotropy;
    private final OptionalDouble maxLod;
    private final long cpuHandle;
    private final AtomicBoolean closed = new AtomicBoolean();

    D3D12Sampler(DeviceContext ctx, AddressMode addressModeU, AddressMode addressModeV, FilterMode minFilter,
                 FilterMode magFilter, int maxAnisotropy, OptionalDouble maxLod, long cpuHandle) {
        this.ctx = ctx;
        this.addressModeU = addressModeU;
        this.addressModeV = addressModeV;
        this.minFilter = minFilter;
        this.magFilter = magFilter;
        this.maxAnisotropy = maxAnisotropy;
        this.maxLod = maxLod;
        this.cpuHandle = cpuHandle;
    }

    /** @return CPU handle of the sampler staging descriptor */
    public long cpuHandle() {
        return this.cpuHandle;
    }

    /** @return the sampler is not closed and the device is alive */
    public boolean isUsable() {
        return !this.closed.get() && !this.ctx.isClosed();
    }

    @Override
    public AddressMode getAddressModeU() {
        return this.addressModeU;
    }

    @Override
    public AddressMode getAddressModeV() {
        return this.addressModeV;
    }

    @Override
    public FilterMode getMinFilter() {
        return this.minFilter;
    }

    @Override
    public FilterMode getMagFilter() {
        return this.magFilter;
    }

    @Override
    public int getMaxAnisotropy() {
        return this.maxAnisotropy;
    }

    @Override
    public OptionalDouble getMaxLod() {
        return this.maxLod;
    }

    @Override
    public boolean isClosed() {
        return this.closed.get();
    }

    /**
     * Idempotent and thread-safe ({@code closed.compareAndSet(false, true)}: a double close from two threads must free
     * the staging descriptor exactly once); unregisters from the {@code ObjectRegistry}; defers the descriptor free.
     */
    @Override
    public void close() {
        throw new UnsupportedOperationException("WP1: if (!closed.compareAndSet(false, true)) return; unregister; defer the staging descriptor free");
    }
}
