// Owner: WP3 (pipelines + render pass)
package net.caffeinemc.mods.sodium.client.d3d12.pipeline;

import com.mojang.renderpearl.backend.api.BackendRenderPipeline;

/**
 * SPIR-V (Vulkan flavour, already patched to {@code set 0, binding = uniform index} by the frontend) -> HLSL SM 5.0 with
 * LWJGL's SPIRV-Cross ({@code lwjgl-spvc}). One independent spvc context per stage per call, destroyed in a
 * {@code finally}; never the frontend's own context. Runs on worker threads, so it keeps no shared state.
 *
 * <h2>Call sequence (spec 02 section 6.1)</h2>
 * {@code spvc_context_create} -> {@code parse_spirv(post-patch words from module.spv(), remaining()/4)} ->
 * {@code create_compiler(HLSL)} -> {@code set_entry_point("main", model)} -> options -> resource bindings ->
 * {@code compile} -> <b>copy the C string before destroying the context</b>. Failure text comes from
 * {@code spvc_context_get_last_error_string} before the destroy.
 *
 * <h2>Options (spec 02 section 6.2; exact IDs in the spec)</h2>
 * {@code HLSL_SHADER_MODEL = 50}; {@code FLIP_VERTEX_Y = true} on the <b>vertex stage only</b> (Vulkan-compatible image
 * orientation, decision D5); {@code HLSL_POINT_SIZE_COMPAT = true}; {@code HLSL_POINT_COORD_COMPAT = true};
 * {@code FORCE_ZERO_INITIALIZED_VARIABLES = true}; {@code FIXUP_DEPTH_CONVENTION = false};
 * {@code EMIT_LINE_DIRECTIVES = false}; {@code HLSL_USE_ENTRY_POINT_NAME = false};
 * {@code HLSL_FLATTEN_MATRIX_VERTEX_INPUT_SEMANTICS = true};
 * {@code HLSL_SUPPORT_NONZERO_BASE_VERTEX_BASE_INSTANCE = true} for vertex stages (a vertex shader that does not read the
 * built-ins gets no such cbuffer even with the option on).
 *
 * <h2>Registers (B1 compaction, always on - decision D7)</h2>
 * For every slot of the {@link SlotAssignment}, on <b>both</b> stage compilers:
 * {@code spvc_compiler_hlsl_add_resource_binding(stage, set 0, binding i, ...)} with {@code cbv b<reg>} for UBO,
 * {@code srv t<reg>} + {@code sampler s<reg>} for TEXTURE, {@code srv t<reg>} for TEXEL. Push constants:
 * {@code desc_set = SPVC_HLSL_PUSH_CONSTANT_DESC_SET (-1), binding = SPVC_HLSL_PUSH_CONSTANT_BINDING (0)} ->
 * {@code cbv b13}. The generated {@code cbuffer SPIRV_Cross_VertexInfo} (the C API has no setter) is text-patched to
 * {@code register(b12)}: exactly one occurrence, on its own line, followed by {@code {}.
 * <b>Assertions without {@code D3DReflect}</b>: before returning, regex-scan the HLSL and fail with
 * {@link IllegalStateException} if any {@code cbuffer} / {@code Texture*} / {@code Buffer<>} / {@code SamplerState}
 * declaration lacks the {@code register(..)} the assignment names, or if a cbuffer other than {@code PC} /
 * {@code SPIRV_Cross_VertexInfo} has no explicit register (the silent failure of an auto-assigned {@code PC} on
 * {@code cb1} is what this guards against).
 *
 * <h2>Slot facts</h2>
 * Activity from the active-variables API; cube-ness from the image dimension; the sampled base type of texel buffers
 * from {@code spvc_type_get_image_sampled_type} -> {@code spvc_compiler_get_type_handle} -> {@code spvc_type_get_basetype}
 * (the first call returns a type <em>id</em>, not a base type; 7 = int32, 8 = uint32, 13 = fp32). A texel buffer whose
 * {@code gpuFormat} class does not match what the shader fetches fails the pipeline. Descriptor arrays and
 * {@code gl_DrawID} fail the pipeline.
 */
public final class ShaderTranslator {
    private ShaderTranslator() {
    }

    /**
     * @param info   the full create info (uniform list for the register table, push constant size, vertex format)
     * @param shader the stage to translate ({@code shader.module().type()} tells VERTEX / FRAGMENT; never rely on list order)
     * @param slots  the register assignment shared by both stages
     * @throws RuntimeException with the SPIRV-Cross error text or the assertion that failed; the caller logs it and
     *                          returns {@code Pending.NULL}
     */
    public static TranslatedStage translate(BackendRenderPipeline.CreateInfo info,
                                            BackendRenderPipeline.CreateInfo.Shader shader, SlotAssignment slots) {
        throw new UnsupportedOperationException("WP3: spvc translation, B1 bindings, text patches, register assertions");
    }
}
