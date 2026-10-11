// Owner: WP1 (device core) - fully implemented tables (spec 04 section 8.2, spec 01 section 3.3)
package net.caffeinemc.mods.sodium.client.d3d12.resource;

import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Enums;

/**
 * {@code GpuSampler} parameters to {@code D3D12_SAMPLER_DESC} values. The mip filter is always LINEAR (GL semantics, see
 * {@link D3D12Sampler}); anisotropy replaces min/mag/mip filtering with {@code ANISOTROPIC}.
 */
public final class SamplerMapping {
    private SamplerMapping() {
    }

    /** @return the {@code D3D12_FILTER} for the given min/mag filters (mip filter LINEAR) or ANISOTROPIC */
    public static int filter(FilterMode min, FilterMode mag, int maxAnisotropy) {
        if (maxAnisotropy > 1) {
            return Enums.Filter.ANISOTROPIC;
        }

        if (min == FilterMode.NEAREST) {
            return mag == FilterMode.NEAREST ? Enums.Filter.MIN_MAG_POINT_MIP_LINEAR : Enums.Filter.MIN_POINT_MAG_MIP_LINEAR;
        }

        return mag == FilterMode.NEAREST ? Enums.Filter.MIN_LINEAR_MAG_POINT_MIP_LINEAR : Enums.Filter.MIN_MAG_MIP_LINEAR;
    }

    /** @return {@code WRAP} for REPEAT, {@code CLAMP} for CLAMP_TO_EDGE */
    public static int address(AddressMode mode) {
        return mode == AddressMode.REPEAT ? Enums.TextureAddressMode.WRAP : Enums.TextureAddressMode.CLAMP;
    }
}
