// Owner: WP1 (device core) - fully implemented value type
package net.caffeinemc.mods.sodium.client.d3d12.resource;

import net.caffeinemc.mods.sodium.client.d3d12.state.TrackedResource;

/**
 * Everything the render-pass begin and the clear engine need to bind or clear one attachment: it can describe a
 * mip of a {@link D3D12Texture} (colour or depth) as well as a swap-chain back buffer (no texture object involved).
 *
 * @param resource    state record of the resource ({@code ResourceStateTracker.require(resource, subresource, RT|DW)})
 * @param subresource the subresource that is attached: {@code mip + layer * mipLevels} (layer 0 for attachments)
 * @param cpuHandle   CPU descriptor handle of the RTV or DSV (valid until the owner of the descriptor is destroyed)
 * @param width       width in texels of the attached mip ({@code max(1, size >> mip)})
 * @param height      height in texels of the attached mip
 * @param dxgiFormat  the RTV / DSV format (also the pipeline RTV/DSV format it must match)
 * @param mip         mip level of the underlying texture that is attached
 */
public record RenderTargetRef(TrackedResource resource, int subresource, long cpuHandle, int width, int height,
                              int dxgiFormat, int mip) {
}
