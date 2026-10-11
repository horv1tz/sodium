// Owner: WP1 (device core)
package net.caffeinemc.mods.sodium.client.d3d12.resource;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import net.caffeinemc.mods.sodium.client.d3d12.core.DeviceContext;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.OptionalDouble;
import java.util.function.Supplier;

/**
 * Creates every buffer, texture, view and sampler. The one place that knows heap types, initial states, resource flags
 * and the out-of-memory policy; {@code D3D12GpuDevice} delegates its five creation methods here, and the surface uses
 * {@link #createSampler} for the present blit's point sampler.
 *
 * <p>Guarantees for every method: arguments are validated before anything is created; a failure leaves no native object
 * and no descriptor behind (resource created last, descriptors allocated after it); {@code E_OUTOFMEMORY} and
 * over-limit sizes become {@code GpuOutOfMemoryException} in {@code createTexture} and both {@code createBuffer}
 * overloads only; labels are evaluated only when {@code DeviceContext.labels()} is true and applied with {@code SetName}.
 *
 * <p>Thread: render thread.
 */
public final class ResourceFactory {
    private final DeviceContext ctx;

    public ResourceFactory(DeviceContext ctx) {
        this.ctx = ctx;
    }

    /**
     * {@code createBuffer(label, usage, size)}: mode from {@link BufferModes#select}; contents undefined (UPLOAD and
     * READBACK memory is not zeroed by us). Size must be {@code > 0} (the frontend checks).
     */
    public D3D12DeviceBuffer createBuffer(@Nullable Supplier<String> label, int usage, long size) {
        throw new UnsupportedOperationException("WP1: committed buffer per BufferModes, persistent map, OOM policy");
    }

    /**
     * {@code createBuffer(label, usage, data)}: size {@code data.remaining()}, contents bytes {@code [position, limit)};
     * {@code usage() | COPY_DST}; the data is captured before returning (the position is not consumed). UPLOAD: memcpy
     * into the mapping. DEFAULT: created in {@code COPY_DEST}, then
     * {@code ctx.encoder().uploadInitialData(buffer, data)} records the copy inline at the call position (legal even
     * while a render pass is open; the buffer cannot have been bound yet).
     */
    public D3D12DeviceBuffer createBuffer(@Nullable Supplier<String> label, int usage, ByteBuffer data) {
        throw new UnsupportedOperationException("WP1: create with COPY_DST, UPLOAD memcpy or encoder.uploadInitialData");
    }

    /**
     * See {@link D3D12Texture} for the full creation recipe. A depth format with {@code USAGE_RENDER_ATTACHMENT} other
     * than {@code D32_FLOAT} is rejected here with {@code IllegalArgumentException("only D32_FLOAT depth attachments are
     * supported: ..")} ({@link net.caffeinemc.mods.sodium.client.d3d12.core.Formats#requireDepthAttachment}): every PSO
     * bakes {@code DSVFormat = D32_FLOAT} and a different bound DSV format is undefined behaviour. This is the safe place
     * to fail (a creation call, unlike {@code createRenderPass}, can throw without wedging the frontend).
     */
    public D3D12Texture createTexture(@Nullable String label, int usage, GpuFormat format, int width, int height,
                                      int depthOrLayers, int mipLevels) {
        throw new UnsupportedOperationException("WP1: committed texture, typeless depth, optimised clear value, COMMON");
    }

    /**
     * @param texture a {@link D3D12Texture} created by this factory (foreign objects: {@code IllegalArgumentException})
     */
    public D3D12TextureView createView(GpuTexture texture, int baseMipLevel, int mipLevels) {
        throw new UnsupportedOperationException("WP1: view object registering with the texture reference count");
    }

    /** Sampler recipe: see {@link D3D12Sampler}. */
    public D3D12Sampler createSampler(AddressMode addressModeU, AddressMode addressModeV, FilterMode minFilter,
                                      FilterMode magFilter, int maxAnisotropy, OptionalDouble maxLod) {
        throw new UnsupportedOperationException("WP1: sampler descriptor in the sampler staging allocator");
    }
}
