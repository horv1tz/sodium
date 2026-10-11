// Owner: WP3 (pipelines + render pass) - fully implemented value type
package net.caffeinemc.mods.sodium.client.d3d12.pipeline;

import com.mojang.renderpearl.api.GpuFormat;
import org.jspecify.annotations.Nullable;

/**
 * Binder-facing facts about one uniform slot (index == SPIR-V binding == {@code setUniform} index).
 *
 * @param name            the uniform name (part of the layout identity: equal layouts imply equal name -> slot maps)
 * @param kind            how the slot is bound
 * @param register        {@code b<n>} for UBO, {@code t<n>}/{@code s<n>} for TEXTURE and TEXEL (see {@link SlotAssignment})
 * @param active          some stage really references the resource (from {@code get_active_interface_variables}, NOT from
 *                        {@code hlsl_is_resource_used}); inactive slots are stored but never bound or transitioned
 * @param cube            the shader declares a cube sampler (selects the cube SRV / cube null descriptor)
 * @param sampledBaseType {@code Formats.BASE_*} of what the shader fetches from a TEXEL slot (null-descriptor selection);
 *                        {@code BASE_FLOAT} for textures
 * @param texelFormat     the typed-buffer SRV format source for TEXEL slots, null otherwise
 */
public record SlotInfo(String name, SlotKind kind, int register, boolean active, boolean cube, int sampledBaseType,
                       @Nullable GpuFormat texelFormat) {
}
