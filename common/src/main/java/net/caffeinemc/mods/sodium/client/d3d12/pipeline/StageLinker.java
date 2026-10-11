// Owner: WP3 (pipelines + render pass) - implemented
package net.caffeinemc.mods.sodium.client.d3d12.pipeline;

import java.util.BitSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes the interface between the two translated stages of a pipeline identical, so that no Direct3D implementation has
 * to link a pixel shader that reads only some of the vertex shader's outputs.
 *
 * <p>GLSL linking is by {@code location}, so a fragment shader may leave outputs of the vertex shader unread
 * (vanilla's {@code animate_sprite} writes the animation progress at location 0 and the texture coordinate at location 1,
 * and the plain blit fragment shader only reads location 1). SPIRV-Cross turns every location {@code n} into the semantic
 * {@code TEXCOORD<n>}, and the HLSL compiler gives every stage's signature its own consecutive registers: the vertex
 * stage then has {@code TEXCOORD0 -> o0, TEXCOORD1 -> o1} while the pixel stage has {@code TEXCOORD1 -> v0}. Direct3D
 * drivers link by semantic and cope; vkd3d (Wine) links by register, so the pixel shader read the wrong vertex output
 * and every sprite of the texture atlas got a constant {@code v} coordinate (observed in the game: the block atlas came out
 * as vertical streaks).
 *
 * <p>The fix is the usual dead-varying elimination: members of {@code SPIRV_Cross_Output} (vertex stage) whose
 * {@code TEXCOORD<n>} the pixel stage's {@code SPIRV_Cross_Input} does not declare are removed together with their
 * assignment in {@code main}. Both signatures are then the same list of semantics in the same (location) order, hence the
 * same registers. Members that are not a plain scalar/vector {@code TEXCOORD<n>} (arrays, matrices, built-ins) are never
 * touched.
 */
public final class StageLinker {
    private static final Pattern INPUT_STRUCT = Pattern.compile("struct\\s+SPIRV_Cross_Input\\s*\\{(.*?)\\};", Pattern.DOTALL);
    private static final Pattern OUTPUT_STRUCT = Pattern.compile("struct\\s+SPIRV_Cross_Output\\s*\\{(.*?)\\};", Pattern.DOTALL);
    private static final Pattern TEXCOORD = Pattern.compile(":\\s*TEXCOORD(\\d+)");
    /** {@code [interpolation modifiers] type name : TEXCOORD<n>;} with a plain (non array, non matrix) member. */
    private static final Pattern PLAIN_OUTPUT = Pattern.compile(
            "^[ \\t]*(?:(?:nointerpolation|noperspective|centroid|sample|linear)\\s+)*[A-Za-z_]\\w*\\s+([A-Za-z_]\\w*)\\s*:\\s*TEXCOORD(\\d+)\\s*;[ \\t]*\\r?$");

    private StageLinker() {
    }

    /**
     * @return {@code vertexHlsl} without the vertex outputs that {@code fragmentHlsl} does not read (the same instance if
     * there is nothing to remove or the text has an unexpected shape)
     */
    public static String pruneUnreadVertexOutputs(String vertexHlsl, String fragmentHlsl) {
        Matcher output = OUTPUT_STRUCT.matcher(vertexHlsl);

        if (!output.find()) {
            return vertexHlsl;
        }

        BitSet read = new BitSet();
        Matcher input = INPUT_STRUCT.matcher(fragmentHlsl);

        if (input.find()) {
            Matcher semantics = TEXCOORD.matcher(input.group(1));

            while (semantics.find()) {
                read.set(Integer.parseInt(semantics.group(1)));
            }
        }

        String body = output.group(1);
        StringBuilder kept = new StringBuilder();
        java.util.List<String> removed = new java.util.ArrayList<>();

        for (String line : body.split("\n", -1)) {
            Matcher member = PLAIN_OUTPUT.matcher(line);

            if (member.matches() && !read.get(Integer.parseInt(member.group(2)))) {
                removed.add(member.group(1));
            } else {
                kept.append(line).append('\n');
            }
        }

        if (removed.isEmpty()) {
            return vertexHlsl;
        }

        // the split added one trailing newline too many
        kept.setLength(kept.length() - 1);

        String result = vertexHlsl.substring(0, output.start(1)) + kept + vertexHlsl.substring(output.end(1));

        for (String name : removed) {
            result = result.replaceAll("(?m)^[ \\t]*stage_output\\." + Pattern.quote(name) + "\\s*=\\s*" + Pattern.quote(name) + "\\s*;[ \\t]*\\r?\\n", "");
        }

        return result;
    }
}
