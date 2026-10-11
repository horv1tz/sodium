package net.caffeinemc.mods.sodium.mixin.d3d12;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.renderpearl.api.device.GpuBackend;
import net.caffeinemc.mods.sodium.client.d3d12.integration.D3D12Selection;
import net.minecraft.client.PreferredGraphicsApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Minecraft's list of graphics backends is hard-coded in {@link PreferredGraphicsApi}, an enum that cannot get new
 * constants. When the player opted in to the experimental Direct3D 12 renderer, it is put in front of the list; if it
 * cannot be created, the game falls through to exactly the backends it would have tried anyway.
 *
 * <p>Nothing in the handler's signature or in {@link D3D12Selection} touches a native library, so this mixin applies
 * safely on every platform.
 */
@Mixin(PreferredGraphicsApi.class)
public class PreferredGraphicsApiMixin {
    @ModifyReturnValue(method = "getBackendsToTry", at = @At("RETURN"))
    private GpuBackend[] sodium$prependDirect3D12(GpuBackend[] original) {
        GpuBackend direct3d12 = D3D12Selection.createBackendIfSelected();

        if (direct3d12 == null) {
            return original;
        }

        GpuBackend[] result = new GpuBackend[original.length + 1];
        result[0] = direct3d12;
        System.arraycopy(original, 0, result, 1, original.length);

        return result;
    }
}
