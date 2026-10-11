// Owner: WP1 (device core) - fully implemented shared helper
package net.caffeinemc.mods.sodium.client.d3d12.core;

import com.mojang.renderpearl.api.GpuFormat;

/**
 * Everything the backend needs to know about one {@link GpuFormat}; produced by {@link Formats#of(GpuFormat)}.
 * All DXGI values are {@code DXGI_FORMAT} integers ({@code Enums.Format.*}); {@code 0} (UNKNOWN) means "not available".
 *
 * @param format          the Mojang format
 * @param bytesPerTexel   {@code GpuFormat.blockSize()}
 * @param resourceFormat  format of the committed texture (typeless for depth)
 * @param srvFormat       format of a shader resource view of a texture of this format
 * @param rtvFormat       format of render target views / pipeline RTV format (colour formats only)
 * @param dsvFormat       format of depth stencil views (depth formats only)
 * @param copyFormat      format used in {@code D3D12_SUBRESOURCE_FOOTPRINT} when copying this texture to or from a buffer
 * @param vertexFormat    format used in an input layout element (RGB8/RGB16 map to the 4-component format of the same type)
 * @param depth           the format has a depth aspect
 * @param textureOk       a texture of this format can be created
 * @param vertexOk        the format can be a vertex attribute
 * @param sampledBaseType SPIRV-Cross base type code of what a shader sees when sampling/fetching it:
 *                        {@link Formats#BASE_INT}, {@link Formats#BASE_UINT} or {@link Formats#BASE_FLOAT}
 */
public record FormatInfo(
        GpuFormat format,
        int bytesPerTexel,
        int resourceFormat,
        int srvFormat,
        int rtvFormat,
        int dsvFormat,
        int copyFormat,
        int vertexFormat,
        boolean depth,
        boolean textureOk,
        boolean vertexOk,
        int sampledBaseType) {
}
