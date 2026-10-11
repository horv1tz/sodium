// Owner: WP3 (pipelines + render pass) - fully implemented
package net.caffeinemc.mods.sodium.client.d3d12.pipeline;

import com.mojang.renderpearl.api.pipeline.UniformType;

/**
 * How one entry of {@code CreateInfo.uniforms()} is bound.
 * <ul>
 *     <li>{@link #UBO}: a uniform buffer; root CBV {@code b<register>} (register 0..11), root parameter
 *     {@code PARAM_CBV0 + register}; the value is a {@code GpuBufferSlice}.</li>
 *     <li>{@link #TEXTURE}: a combined image sampler; SRV table entry {@code t<register>} <b>and</b> sampler table
 *     entry {@code s<register>} with the same index (register 0..15); the value is a {@code TextureViewAndSampler}.</li>
 *     <li>{@link #TEXEL}: a texel buffer; SRV table entry {@code t<register>} only (register 0..15, shares the numbering
 *     with TEXTURE; the sampler entry of that index holds the default sampler); the value is a {@code GpuBufferSlice}.</li>
 * </ul>
 */
public enum SlotKind {
    UBO,
    TEXTURE,
    TEXEL;

    public static SlotKind of(UniformType type) {
        return switch (type) {
            case UNIFORM_BUFFER -> UBO;
            case COMBINED_IMAGE_SAMPLER -> TEXTURE;
            case TEXEL_BUFFER -> TEXEL;
        };
    }

    /** @return true for kinds that occupy an SRV table entry */
    public boolean usesSrvTable() {
        return this != UBO;
    }
}
