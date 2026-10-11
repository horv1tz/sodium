// Owner: WP3 (pipelines + render pass) - implemented
package net.caffeinemc.mods.sodium.client.d3d12.pipeline;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import net.caffeinemc.mods.sodium.client.d3d12.core.Formats;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.Spvc;
import org.lwjgl.util.spvc.SpvcHLSLResourceBinding;
import org.lwjgl.util.spvc.SpvcReflectedResource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.BitSet;
import java.util.List;

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
    private static final int MODEL_VERTEX = 0;
    private static final int MODEL_FRAGMENT = 4;
    private static final int HLSL_SHADER_MODEL_5_0 = 50;

    /** {@code SpvDecorationBinding} / {@code SpvDecorationDescriptorSet}. */
    private static final int DECORATION_BINDING = 33;
    private static final int DECORATION_DESCRIPTOR_SET = 34;

    /** {@code SpvDim} values of an image type. */
    private static final int DIM_2D = 1;
    private static final int DIM_CUBE = 3;
    private static final int DIM_RECT = 4;
    private static final int DIM_BUFFER = 5;

    /**
     * Resource lists that must be empty: nothing but UBOs, combined samplers and one push constant block is supported.
     * (The ray-tracing and tensor lists are not queried: the bundled SPIRV-Cross rejects those types, and the frontend's
     * Vulkan 1.2 GLSL cannot produce them.)
     */
    private static final int[] UNSUPPORTED_RESOURCE_TYPES = {
            Spvc.SPVC_RESOURCE_TYPE_STORAGE_BUFFER, Spvc.SPVC_RESOURCE_TYPE_SUBPASS_INPUT, Spvc.SPVC_RESOURCE_TYPE_STORAGE_IMAGE,
            Spvc.SPVC_RESOURCE_TYPE_ATOMIC_COUNTER, Spvc.SPVC_RESOURCE_TYPE_SEPARATE_IMAGE, Spvc.SPVC_RESOURCE_TYPE_SEPARATE_SAMPLERS};

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
        ShaderType stage = shader.module().type();
        List<BindGroupLayout.UniformDescription> uniforms = info.uniforms();

        if (uniforms.size() != slots.size()) {
            throw new IllegalArgumentException("slot assignment has " + slots.size() + " slots for " + uniforms.size() + " uniforms");
        }

        ByteBuffer module = shader.module().spv();
        ByteBuffer staged = null;
        long context = 0L;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer words;

            if (module.isDirect()) {
                words = module.duplicate().order(ByteOrder.nativeOrder()).asIntBuffer();
            } else {
                staged = MemoryUtil.memAlloc(module.remaining());
                staged.put(module.duplicate()).flip();
                words = staged.order(ByteOrder.nativeOrder()).asIntBuffer();
            }

            if (words.remaining() < 5) {
                throw new IllegalStateException("SPIR-V module of " + describe(info, stage) + " is too small (" + module.remaining() + " bytes)");
            }

            if (module.remaining() % 4 != 0) {
                throw new IllegalStateException("SPIR-V module of " + describe(info, stage) + " is not a whole number of words");
            }

            PointerBuffer out = stack.callocPointer(1);

            int rc = Spvc.spvc_context_create(out);

            if (rc != 0) {
                throw new IllegalStateException("Cannot create a SPIRV-Cross context (" + errorName(rc) + ")");
            }

            context = out.get(0);

            check(context, Spvc.spvc_context_parse_spirv(context, words, words.remaining(), out), "parse the SPIR-V of " + describe(info, stage));

            long ir = out.get(0);

            check(context, Spvc.spvc_context_create_compiler(context, Spvc.SPVC_BACKEND_HLSL, ir, Spvc.SPVC_CAPTURE_MODE_COPY, out),
                    "create the HLSL compiler for " + describe(info, stage));

            long compiler = out.get(0);
            int model = stage == ShaderType.VERTEX ? MODEL_VERTEX : MODEL_FRAGMENT;

            check(context, Spvc.spvc_compiler_set_entry_point(compiler, shader.entryPoint(), model), "select entry point '"
                    + shader.entryPoint() + "' of " + describe(info, stage));

            Facts facts = declared(info, stage, slots, context, compiler, out, stack);

            registerBindings(context, compiler, model, slots, stack);
            setOptions(context, compiler, stage, out);
            check(context, Spvc.spvc_compiler_compile(compiler, out), "translate " + describe(info, stage) + " to HLSL");

            // the C string belongs to the context: copy it before the context is destroyed in the finally block
            String hlsl = MemoryUtil.memUTF8(out.get(0));

            markActive(context, compiler, slots, facts, out, stack);
            facts.checkTexelFormats(info, stage);

            boolean usesVertexInfo = stage == ShaderType.VERTEX && HlslAssertions.declaresVertexInfo(hlsl);

            if (usesVertexInfo) {
                hlsl = HlslAssertions.patchVertexInfo(hlsl);
            }

            try {
                HlslAssertions.verify(hlsl, uniforms, slots);
            } catch (IllegalStateException e) {
                throw new IllegalStateException(describe(info, stage) + ": " + e.getMessage(), e);
            }

            return new TranslatedStage(stage, hlsl, facts.active, facts.cube, facts.texelBaseTypes, usesVertexInfo, facts.pushConstantBytes);
        } finally {
            if (context != 0L) {
                Spvc.spvc_context_destroy(context);
            }

            if (staged != null) {
                MemoryUtil.memFree(staged);
            }
        }
    }

    /** What the declared (and later the active) resources of one stage say about the uniform slots. */
    private static final class Facts {
        final BitSet active = new BitSet();
        final BitSet cube = new BitSet();
        final int[] texelBaseTypes;
        /** Binding of every declared resource, by resource id, for the active-variables pass. */
        final java.util.HashMap<Integer, Integer> bindingById = new java.util.HashMap<>();
        int pushConstantBytes;

        Facts(int slotCount) {
            this.texelBaseTypes = new int[slotCount];
        }

        void checkTexelFormats(BackendRenderPipeline.CreateInfo info, ShaderType stage) {
            for (int slot = this.active.nextSetBit(0); slot >= 0; slot = this.active.nextSetBit(slot + 1)) {
                int fetched = this.texelBaseTypes[slot];

                if (fetched == 0) {
                    continue;
                }

                GpuFormat format = info.uniforms().get(slot).gpuFormat();

                if (format == null || Formats.of(format).sampledBaseType() != fetched) {
                    throw new IllegalStateException(describe(info, stage) + ": texel buffer '" + info.uniforms().get(slot).name()
                            + "' is declared as " + format + " but the shader fetches " + baseTypeName(fetched));
                }
            }
        }
    }

    /**
     * Lists every declared resource and validates it against the uniform list: kind, set 0, binding in range, no
     * descriptor arrays, image dimensions, sampled element class. Read-only.
     */
    private static Facts declared(BackendRenderPipeline.CreateInfo info, ShaderType stage, SlotAssignment slots, long context,
                                  long compiler, PointerBuffer out, MemoryStack stack) {
        Facts facts = new Facts(slots.size());
        PointerBuffer count = stack.callocPointer(1);

        check(context, Spvc.spvc_compiler_create_shader_resources(compiler, out), "list the resources of " + describe(info, stage));

        long resources = out.get(0);

        for (int type : UNSUPPORTED_RESOURCE_TYPES) {
            check(context, Spvc.spvc_resources_get_resource_list_for_type(resources, type, out, count), "list resources");

            if (count.get(0) != 0L) {
                SpvcReflectedResource first = SpvcReflectedResource.create(out.get(0));

                throw new IllegalStateException(describe(info, stage) + " declares the unsupported resource '" + first.nameString()
                        + "' (resource type " + type + "); only uniform buffers, combined image samplers and one push constant block are supported");
            }
        }

        check(context, Spvc.spvc_resources_get_resource_list_for_type(resources, Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, out, count),
                "list the uniform buffers");

        SpvcReflectedResource.Buffer ubos = SpvcReflectedResource.create(out.get(0), (int) count.get(0));

        for (int i = 0; i < ubos.remaining(); i++) {
            SpvcReflectedResource resource = ubos.get(i);
            int slot = slotOf(info, stage, slots, compiler, resource, "uniform buffer");

            if (slots.kind(slot) != SlotKind.UBO) {
                throw new IllegalStateException(describe(info, stage) + ": '" + resource.nameString() + "' is a uniform buffer in the shader but "
                        + slots.kind(slot) + " slot " + slot + " ('" + info.uniforms().get(slot).name() + "') in the uniform list");
            }

            rejectArray(info, stage, compiler, resource);
            facts.bindingById.put(resource.id(), slot);
        }

        check(context, Spvc.spvc_resources_get_resource_list_for_type(resources, Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE, out, count),
                "list the sampled images");

        SpvcReflectedResource.Buffer images = SpvcReflectedResource.create(out.get(0), (int) count.get(0));

        for (int i = 0; i < images.remaining(); i++) {
            SpvcReflectedResource resource = images.get(i);
            int slot = slotOf(info, stage, slots, compiler, resource, "sampled image");
            String name = resource.nameString();
            long type = Spvc.spvc_compiler_get_type_handle(compiler, resource.type_id());
            int dimension = Spvc.spvc_type_get_image_dimension(type);

            if (slots.kind(slot) == SlotKind.UBO) {
                throw new IllegalStateException(describe(info, stage) + ": '" + name + "' is a sampled image in the shader but a uniform buffer slot "
                        + slot + " in the uniform list");
            }

            rejectArray(info, stage, compiler, resource);

            if (Spvc.spvc_type_get_image_arrayed(type) || Spvc.spvc_type_get_image_multisampled(type) || Spvc.spvc_type_get_image_is_depth(type)) {
                throw new IllegalStateException(describe(info, stage) + ": sampler '" + name
                        + "' is arrayed, multisampled or a depth-compare sampler, which the backend does not support");
            }

            if (slots.kind(slot) == SlotKind.TEXEL) {
                if (dimension != DIM_BUFFER) {
                    throw new IllegalStateException(describe(info, stage) + ": '" + name + "' is a texel buffer slot but the shader declares an image of dimension " + dimension);
                }

                long sampled = Spvc.spvc_compiler_get_type_handle(compiler, Spvc.spvc_type_get_image_sampled_type(type));
                int base = Spvc.spvc_type_get_basetype(sampled);

                if (base != Formats.BASE_INT && base != Formats.BASE_UINT && base != Formats.BASE_FLOAT) {
                    throw new IllegalStateException(describe(info, stage) + ": texel buffer '" + name + "' has the unsupported element base type " + base);
                }

                facts.texelBaseTypes[slot] = base;
            } else if (dimension == DIM_CUBE) {
                facts.cube.set(slot);
            } else if (dimension != DIM_2D && dimension != DIM_RECT) {
                throw new IllegalStateException(describe(info, stage) + ": sampler '" + name + "' has the unsupported image dimension " + dimension
                        + " (only 2D, cube and texel buffers are supported)");
            }

            facts.bindingById.put(resource.id(), slot);
        }

        check(context, Spvc.spvc_resources_get_resource_list_for_type(resources, Spvc.SPVC_RESOURCE_TYPE_PUSH_CONSTANT, out, count),
                "list the push constants");

        if (count.get(0) > 1L) {
            throw new IllegalStateException(describe(info, stage) + " declares more than one push constant block");
        }

        if (count.get(0) == 1L) {
            SpvcReflectedResource block = SpvcReflectedResource.create(out.get(0));
            long type = Spvc.spvc_compiler_get_type_handle(compiler, block.type_id());

            check(context, Spvc.spvc_compiler_get_declared_struct_size(compiler, type, out), "measure the push constant block");

            long size = out.get(0);

            if (size < 0L || size > info.pushConstantsSize()) {
                throw new IllegalStateException(describe(info, stage) + ": the push constant block is " + size
                        + " bytes but the pipeline declares " + info.pushConstantsSize());
            }

            facts.pushConstantBytes = (int) size;
        }

        return facts;
    }

    private static int slotOf(BackendRenderPipeline.CreateInfo info, ShaderType stage, SlotAssignment slots, long compiler,
                              SpvcReflectedResource resource, String what) {
        int set = Spvc.spvc_compiler_get_decoration(compiler, resource.id(), DECORATION_DESCRIPTOR_SET);
        int binding = Spvc.spvc_compiler_get_decoration(compiler, resource.id(), DECORATION_BINDING);

        if (set != 0 || binding < 0 || binding >= slots.size()) {
            throw new IllegalStateException(describe(info, stage) + ": " + what + " '" + resource.nameString() + "' is at set " + set + ", binding "
                    + binding + " but the pipeline has " + slots.size() + " uniform slots (the frontend rewrites every binding to set 0, binding = uniform index)");
        }

        return binding;
    }

    private static void rejectArray(BackendRenderPipeline.CreateInfo info, ShaderType stage, long compiler, SpvcReflectedResource resource) {
        long type = Spvc.spvc_compiler_get_type_handle(compiler, resource.type_id());

        if (Spvc.spvc_type_get_num_array_dimensions(type) > 0) {
            throw new IllegalStateException(describe(info, stage) + ": '" + resource.nameString() + "' is a descriptor array, which the backend does not support");
        }
    }

    /** B1 compaction: registers EVERY slot (active or not) and the push constants on this stage's compiler. */
    private static void registerBindings(long context, long compiler, int model, SlotAssignment slots, MemoryStack stack) {
        SpvcHLSLResourceBinding binding = SpvcHLSLResourceBinding.calloc(stack);

        for (int slot = 0; slot < slots.size(); slot++) {
            int register = slots.register(slot);

            Spvc.spvc_hlsl_resource_binding_init(binding);
            binding.stage(model).desc_set(0).binding(slot);

            switch (slots.kind(slot)) {
                case UBO -> binding.cbv(mapping -> mapping.register_space(0).register_binding(register));
                case TEXTURE -> {
                    binding.srv(mapping -> mapping.register_space(0).register_binding(register));
                    binding.sampler(mapping -> mapping.register_space(0).register_binding(register));
                }
                case TEXEL -> binding.srv(mapping -> mapping.register_space(0).register_binding(register));
            }

            check(context, Spvc.spvc_compiler_hlsl_add_resource_binding(compiler, binding), "register the HLSL binding of slot " + slot);
        }

        Spvc.spvc_hlsl_resource_binding_init(binding);
        binding.stage(model).desc_set(Spvc.SPVC_HLSL_PUSH_CONSTANT_DESC_SET).binding(Spvc.SPVC_HLSL_PUSH_CONSTANT_BINDING);
        binding.cbv(mapping -> mapping.register_space(0).register_binding(SlotAssignment.PUSH_CONSTANT_REGISTER));

        check(context, Spvc.spvc_compiler_hlsl_add_resource_binding(compiler, binding), "register the push constant binding");
    }

    private static void setOptions(long context, long compiler, ShaderType stage, PointerBuffer out) {
        check(context, Spvc.spvc_compiler_create_compiler_options(compiler, out), "create the compiler options");

        long options = out.get(0);
        boolean vertex = stage == ShaderType.VERTEX;

        Spvc.spvc_compiler_options_set_uint(options, Spvc.SPVC_COMPILER_OPTION_HLSL_SHADER_MODEL, HLSL_SHADER_MODEL_5_0);
        // Vulkan-compatible image orientation: the vertex stage negates gl_Position.y, the fragment stage is untouched
        Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_FLIP_VERTEX_Y, vertex);
        Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_HLSL_POINT_SIZE_COMPAT, true);
        Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_HLSL_POINT_COORD_COMPAT, true);
        Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_FORCE_ZERO_INITIALIZED_VARIABLES, true);
        Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_FIXUP_DEPTH_CONVENTION, false);
        Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_EMIT_LINE_DIRECTIVES, false);
        Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_HLSL_USE_ENTRY_POINT_NAME, false);
        Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_HLSL_FLATTEN_MATRIX_VERTEX_INPUT_SEMANTICS, true);
        // a vertex shader that does not read the built-ins gets no cbuffer even with the option on
        Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_HLSL_SUPPORT_NONZERO_BASE_VERTEX_BASE_INSTANCE, vertex);

        check(context, Spvc.spvc_compiler_install_compiler_options(compiler, options), "install the compiler options");
    }

    /**
     * Slot activity from the active-variables API - never from {@code hlsl_is_resource_used}, which is true for every
     * registered binding.
     */
    private static void markActive(long context, long compiler, SlotAssignment slots, Facts facts, PointerBuffer out, MemoryStack stack) {
        PointerBuffer count = stack.callocPointer(1);

        check(context, Spvc.spvc_compiler_get_active_interface_variables(compiler, out), "query the active variables");

        long active = out.get(0);

        check(context, Spvc.spvc_compiler_create_shader_resources_for_active_variables(compiler, out, active), "list the active resources");

        long resources = out.get(0);

        for (int type : new int[]{Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE}) {
            check(context, Spvc.spvc_resources_get_resource_list_for_type(resources, type, out, count), "list the active resources");

            SpvcReflectedResource.Buffer list = SpvcReflectedResource.create(out.get(0), (int) count.get(0));

            for (int i = 0; i < list.remaining(); i++) {
                Integer slot = facts.bindingById.get(list.get(i).id());

                if (slot != null) {
                    facts.active.set(slot);
                }
            }
        }
    }

    private static void check(long context, int result, String what) {
        if (result != 0) {
            throw new IllegalStateException("SPIRV-Cross failed to " + what + " (" + errorName(result) + "): " + Spvc.spvc_context_get_last_error_string(context));
        }
    }

    private static String errorName(int result) {
        return switch (result) {
            case Spvc.SPVC_ERROR_INVALID_SPIRV -> "invalid SPIR-V";
            case Spvc.SPVC_ERROR_UNSUPPORTED_SPIRV -> "unsupported SPIR-V";
            case Spvc.SPVC_ERROR_OUT_OF_MEMORY -> "out of memory";
            case Spvc.SPVC_ERROR_INVALID_ARGUMENT -> "invalid argument";
            default -> "error " + result;
        };
    }

    private static String baseTypeName(int base) {
        return switch (base) {
            case Formats.BASE_INT -> "int";
            case Formats.BASE_UINT -> "uint";
            case Formats.BASE_FLOAT -> "float";
            default -> "base type " + base;
        };
    }

    private static String describe(BackendRenderPipeline.CreateInfo info, ShaderType stage) {
        return "the " + stage.getName() + " shader of pipeline '" + info.name() + "'";
    }
}
