// Owner: WP3 (pipelines + render pass) - implemented
package net.caffeinemc.mods.sodium.client.d3d12.pass;

import net.caffeinemc.mods.sodium.client.d3d12.state.TrackedResource;

/**
 * How the {@link PassBinder} reads the textures and samplers the frontend hands in as {@code TextureViewAndSampler}
 * values. The production implementation ({@link FrontendValues}) casts to {@code D3D12TextureView} / {@code D3D12Sampler};
 * the Linux tests of the binder substitute their own, because those classes need a device to create their descriptors.
 * Every method is allocation-free; all of them are called with a value for which {@link #isTexture} returned true.
 */
interface ValueAccess {
    /** @return true if {@code value} is a texture view + sampler pair (of whatever backend) */
    boolean isTexture(Object value);

    /** @return true if both belong to this backend and neither is closed (so its descriptors are alive) */
    boolean textureUsable(Object value);

    /** @return CPU handle of the SRV staging descriptor of the view */
    long srv(Object value);

    /** @return CPU handle of the sampler staging descriptor */
    long samplerHandle(Object value);

    /** @return the state record of the texture behind the view */
    TrackedResource tracked(Object value);

    /** @return number of mip levels of the whole texture */
    int textureMipLevels(Object value);

    /** @return first mip of the view */
    int baseMip(Object value);

    /** @return number of mips of the view */
    int mipCount(Object value);

    /** @return number of array layers of the texture (6 for a cube) */
    int layers(Object value);

    /** @return true if the view is a cube view */
    boolean cube(Object value);
}
