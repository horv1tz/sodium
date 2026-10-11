// Owner: WP3 (pipelines + render pass) - implemented
package net.caffeinemc.mods.sodium.client.d3d12.pipeline;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The text assertions that replace {@code D3DReflect} (spec 02 section 3.5 / 6.3): the HLSL that SPIRV-Cross produced is
 * scanned and the translation fails if a declaration does not carry the register the {@link SlotAssignment} names. The
 * silent failure this guards against is a {@code cbuffer} without an explicit register: the HLSL compiler then
 * auto-assigns one (measured: the push constant block {@code PC} landed on {@code cb1}, a root-CBV slot), and nothing
 * else - not the compiler, not PSO creation - complains.
 *
 * <p>Rules (all declarations are matched at the start of a line, so function parameters are never mistaken for resources):
 * <ul>
 *     <li>{@code cbuffer PC} must be {@code register(b13)}, {@code cbuffer SPIRV_Cross_VertexInfo} {@code register(b12)};
 *     any other {@code cbuffer} must have an explicit register that a UBO slot owns (and, when the block name is one of
 *     the pipeline's uniform names, exactly that slot's register);</li>
 *     <li>{@code Texture*} must be {@code register(t<n>)} owned by a TEXTURE slot, {@code Buffer<..>} by a TEXEL slot;
 *     {@code SamplerState} {@code register(s<n>)} owned by a TEXTURE slot;</li>
 *     <li>unordered-access resources and structured/byte-address buffers are not supported (no UAV register exists in
 *     the root signature) and fail.</li>
 * </ul>
 * Pure, thread-safe.
 */
final class HlslAssertions {
    private static final Pattern CBUFFER = Pattern.compile(
            "(?m)^[ \\t]*cbuffer[ \\t]+(\\w+)[ \\t]*(?::[ \\t]*register\\([ \\t]*(\\w+)[ \\t]*\\))?");
    private static final Pattern TEXTURE = Pattern.compile(
            "(?m)^[ \\t]*(Texture\\w*|Buffer)[ \\t]*(?:<[^>\\n]*>)?[ \\t]+(\\w+)[ \\t]*(?::[ \\t]*register\\([ \\t]*(\\w+)[ \\t]*\\))?[ \\t]*;");
    private static final Pattern SAMPLER = Pattern.compile(
            "(?m)^[ \\t]*(SamplerState|SamplerComparisonState)[ \\t]+(\\w+)[ \\t]*(?::[ \\t]*register\\([ \\t]*(\\w+)[ \\t]*\\))?[ \\t]*;");
    private static final Pattern UNSUPPORTED = Pattern.compile(
            "(?m)^[ \\t]*(RW\\w+|\\w*StructuredBuffer\\w*|\\w*ByteAddressBuffer)\\b|register\\([ \\t]*u\\d");
    private static final Pattern VERTEX_INFO_DECLARATION = Pattern.compile("(?m)^([ \\t]*cbuffer[ \\t]+SPIRV_Cross_VertexInfo)[ \\t]*$");

    private HlslAssertions() {
    }

    /**
     * @throws IllegalStateException naming the offending declaration
     */
    static void verify(String hlsl, List<BindGroupLayout.UniformDescription> uniforms, SlotAssignment slots) {
        Map<String, Integer> slotByName = new HashMap<>();

        for (int i = 0; i < uniforms.size(); i++) {
            slotByName.putIfAbsent(uniforms.get(i).name(), i);
        }

        Matcher unsupported = UNSUPPORTED.matcher(hlsl);

        if (unsupported.find()) {
            throw failure("unsupported resource declaration '" + unsupported.group().strip() + "' (no UAV / structured buffer in the root signature)");
        }

        Matcher cbuffers = CBUFFER.matcher(hlsl);

        while (cbuffers.find()) {
            String name = cbuffers.group(1);
            int register = registerNumber(cbuffers.group(2), 'b', "cbuffer " + name);

            if (name.equals("PC")) {
                if (register != SlotAssignment.PUSH_CONSTANT_REGISTER) {
                    throw failure("cbuffer PC is at b" + register + ", expected b" + SlotAssignment.PUSH_CONSTANT_REGISTER);
                }
            } else if (name.equals("SPIRV_Cross_VertexInfo")) {
                if (register != SlotAssignment.VERTEX_INFO_REGISTER) {
                    throw failure("cbuffer SPIRV_Cross_VertexInfo is at b" + register + ", expected b" + SlotAssignment.VERTEX_INFO_REGISTER);
                }
            } else {
                Integer slot = slotByName.get(name);

                if (slot != null) {
                    if (slots.kind(slot) != SlotKind.UBO || slots.register(slot) != register) {
                        throw failure("cbuffer " + name + " is at b" + register + ", expected " + describe(slots, slot));
                    }
                } else if (register >= SlotAssignment.MAX_CBV || slots.cbvOwner(register) < 0) {
                    throw failure("cbuffer " + name + " is at b" + register + " which no uniform buffer slot owns");
                }
            }
        }

        Matcher textures = TEXTURE.matcher(hlsl);

        while (textures.find()) {
            String type = textures.group(1);
            String name = textures.group(2);
            int register = registerNumber(textures.group(3), 't', type + " " + name);
            SlotKind expected = type.equals("Buffer") ? SlotKind.TEXEL : SlotKind.TEXTURE;
            Integer slot = slotByName.get(name);

            if (slot != null) {
                if (slots.kind(slot) != expected || slots.register(slot) != register) {
                    throw failure(type + " " + name + " is at t" + register + ", expected " + describe(slots, slot));
                }
            } else {
                int owner = register < SlotAssignment.MAX_SRV ? slots.srvOwner(register) : -1;

                if (owner < 0 || slots.kind(owner) != expected) {
                    throw failure(type + " " + name + " is at t" + register + " which no " + expected + " slot owns");
                }
            }
        }

        Matcher samplers = SAMPLER.matcher(hlsl);

        while (samplers.find()) {
            String name = samplers.group(2);
            int register = registerNumber(samplers.group(3), 's', "sampler " + name);
            Integer slot = null;

            if (name.startsWith("_") && name.endsWith("_sampler")) {
                slot = slotByName.get(name.substring(1, name.length() - "_sampler".length()));
            }

            if (slot != null) {
                if (slots.kind(slot) != SlotKind.TEXTURE || slots.register(slot) != register) {
                    throw failure("sampler " + name + " is at s" + register + ", expected " + describe(slots, slot));
                }
            } else {
                int owner = register < SlotAssignment.MAX_SAMPLER ? slots.srvOwner(register) : -1;

                if (owner < 0 || slots.kind(owner) != SlotKind.TEXTURE) {
                    throw failure("sampler " + name + " is at s" + register + " which no texture slot owns");
                }
            }
        }
    }

    /**
     * Pins {@code cbuffer SPIRV_Cross_VertexInfo} to {@code register(b12)}: the C API has no setter, so the declaration
     * line is rewritten. Exactly one occurrence is required.
     *
     * @return the patched text
     * @throws IllegalStateException if the declaration does not occur exactly once as its own line
     */
    static String patchVertexInfo(String hlsl) {
        Matcher matcher = VERTEX_INFO_DECLARATION.matcher(hlsl);

        if (!matcher.find()) {
            throw failure("cbuffer SPIRV_Cross_VertexInfo is not declared on its own line");
        }

        int start = matcher.start();
        int end = matcher.end();
        String declaration = matcher.group(1);

        if (matcher.find()) {
            throw failure("cbuffer SPIRV_Cross_VertexInfo is declared more than once");
        }

        return hlsl.substring(0, start) + declaration + " : register(b" + SlotAssignment.VERTEX_INFO_REGISTER + ")"
                + hlsl.substring(end);
    }

    /** @return true if the text declares {@code cbuffer SPIRV_Cross_VertexInfo} at all */
    static boolean declaresVertexInfo(String hlsl) {
        return hlsl.contains("cbuffer SPIRV_Cross_VertexInfo");
    }

    private static int registerNumber(String register, char kind, String what) {
        if (register == null) {
            throw failure(what + " has no explicit register (the HLSL compiler would auto-assign one)");
        }

        if (register.length() < 2 || register.charAt(0) != kind) {
            throw failure(what + " has register(" + register + "), expected a " + kind + "# register");
        }

        try {
            return Integer.parseInt(register.substring(1));
        } catch (NumberFormatException e) {
            throw failure(what + " has register(" + register + "), expected a " + kind + "# register");
        }
    }

    private static String describe(SlotAssignment slots, int slot) {
        return slots.kind(slot) + " slot " + slot + " at register " + slots.register(slot);
    }

    private static IllegalStateException failure(String message) {
        return new IllegalStateException("HLSL register assertion failed: " + message);
    }
}
