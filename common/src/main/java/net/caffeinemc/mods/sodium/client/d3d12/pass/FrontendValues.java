// Owner: WP3 (pipelines + render pass) - implemented
package net.caffeinemc.mods.sodium.client.d3d12.pass;

import com.mojang.renderpearl.util.TextureViewAndSampler;
import net.caffeinemc.mods.sodium.client.d3d12.resource.D3D12Sampler;
import net.caffeinemc.mods.sodium.client.d3d12.resource.D3D12Texture;
import net.caffeinemc.mods.sodium.client.d3d12.resource.D3D12TextureView;
import net.caffeinemc.mods.sodium.client.d3d12.state.TrackedResource;

/** The production {@link ValueAccess}: {@code TextureViewAndSampler} of {@code D3D12TextureView} and {@code D3D12Sampler}. */
final class FrontendValues implements ValueAccess {
    @Override
    public boolean isTexture(Object value) {
        return value instanceof TextureViewAndSampler;
    }

    @Override
    public boolean textureUsable(Object value) {
        return value instanceof TextureViewAndSampler pair && pair.view() instanceof D3D12TextureView view && !view.isClosed()
                && pair.sampler() instanceof D3D12Sampler sampler && sampler.isUsable();
    }

    private static D3D12TextureView view(Object value) {
        return (D3D12TextureView) ((TextureViewAndSampler) value).view();
    }

    @Override
    public long srv(Object value) {
        return view(value).srv();
    }

    @Override
    public long samplerHandle(Object value) {
        return ((D3D12Sampler) ((TextureViewAndSampler) value).sampler()).cpuHandle();
    }

    @Override
    public TrackedResource tracked(Object value) {
        return view(value).d3dTexture().tracked();
    }

    @Override
    public int textureMipLevels(Object value) {
        return view(value).d3dTexture().getMipLevels();
    }

    @Override
    public int baseMip(Object value) {
        return view(value).baseMipLevel();
    }

    @Override
    public int mipCount(Object value) {
        return view(value).mipLevels();
    }

    @Override
    public int layers(Object value) {
        D3D12Texture texture = view(value).d3dTexture();

        return texture.getDepthOrLayers();
    }

    @Override
    public boolean cube(Object value) {
        return view(value).isCube();
    }
}
