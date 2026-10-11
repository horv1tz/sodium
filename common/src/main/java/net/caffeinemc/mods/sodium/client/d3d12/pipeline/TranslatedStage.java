// Owner: WP3 (pipelines + render pass) - fully implemented value type
package net.caffeinemc.mods.sodium.client.d3d12.pipeline;

import com.mojang.renderpearl.api.pipeline.ShaderType;

import java.util.BitSet;

/**
 * Result of translating one SPIR-V stage to HLSL text, with the facts the pipeline compiler merges across the two
 * stages into a {@link SlotLayout}.
 *
 * @param stage           VERTEX or FRAGMENT
 * @param hlsl            complete HLSL (SM 5.0) with all registers explicit and already text-patched
 *                        ({@code SPIRV_Cross_VertexInfo : register(b12)})
 * @param activeSlots     bit {@code i} set if uniform slot {@code i} is really referenced by this stage (from
 *                        {@code spvc_compiler_get_active_interface_variables} +
 *                        {@code create_shader_resources_for_active_variables}; <b>never</b> from {@code hlsl_is_resource_used},
 *                        which is true for every registered binding)
 * @param cubeSlots       bit {@code i} set if slot {@code i} is declared as a cube sampler
 * @param texelBaseTypes  for TEXEL slots the base type the shader fetches ({@code Formats.BASE_*}), 0 elsewhere
 * @param usesVertexInfo  the stage reads {@code gl_VertexIndex} / {@code gl_InstanceIndex} and therefore contains
 *                        {@code cbuffer SPIRV_Cross_VertexInfo} (vertex stage only)
 * @param pushConstantBytes size in bytes of the push constant block the stage declares (0 if none)
 */
public record TranslatedStage(
        ShaderType stage,
        String hlsl,
        BitSet activeSlots,
        BitSet cubeSlots,
        int[] texelBaseTypes,
        boolean usesVertexInfo,
        int pushConstantBytes) {
}
