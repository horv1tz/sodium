// Owner: WP3 (pipelines + render pass) - fully implemented
package net.caffeinemc.mods.sodium.client.d3d12.pipeline;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;

import java.util.List;

/**
 * The "B1 compaction" register assignment of one pipeline (spec 02 section 6.3, decision D7): the pure function from
 * {@code CreateInfo.uniforms()} to HLSL registers that BOTH the translator (which registers every slot with
 * {@code spvc_compiler_hlsl_add_resource_binding} on both stage compilers) and the binder (which fills root parameters
 * and tables) read.
 *
 * <ul>
 *     <li>UBO slots take {@code b0, b1, ...} in uniform order (at most {@link #MAX_CBV} = 12);</li>
 *     <li>TEXTURE and TEXEL slots take {@code t0, t1, ...} in uniform order, sharing one counter (at most
 *     {@link #MAX_SRV} = 16); a TEXTURE slot's sampler uses {@code s<same index>};</li>
 *     <li>push constants are pinned to {@code b13} and {@code SPIRV_Cross_VertexInfo} to {@code b12} (outside this table).</li>
 * </ul>
 * <b>Every</b> slot is registered, active or not, so a declaration that survives in the HLSL can never name a register
 * outside the root signature. More than 12 UBOs or 16 texture-like slots make the pipeline fail cleanly
 * ({@link IllegalArgumentException}, caught by the compiler which returns {@code Pending.NULL}).
 */
public final class SlotAssignment {
    public static final int MAX_CBV = 12;
    public static final int MAX_SRV = 16;
    public static final int MAX_SAMPLER = 16;
    /** HLSL register of the push constant block ({@code b13}). */
    public static final int PUSH_CONSTANT_REGISTER = 13;
    /** HLSL register of {@code SPIRV_Cross_VertexInfo} ({@code b12}). */
    public static final int VERTEX_INFO_REGISTER = 12;

    private final SlotKind[] kinds;
    private final int[] registers;
    private final int cbvCount;
    private final int srvCount;
    private final int[] cbvToSlot = new int[MAX_CBV];
    private final int[] srvToSlot = new int[MAX_SRV];

    private SlotAssignment(SlotKind[] kinds, int[] registers, int cbvCount, int srvCount) {
        this.kinds = kinds;
        this.registers = registers;
        this.cbvCount = cbvCount;
        this.srvCount = srvCount;

        java.util.Arrays.fill(this.cbvToSlot, -1);
        java.util.Arrays.fill(this.srvToSlot, -1);

        for (int slot = 0; slot < kinds.length; slot++) {
            if (kinds[slot] == SlotKind.UBO) {
                this.cbvToSlot[registers[slot]] = slot;
            } else {
                this.srvToSlot[registers[slot]] = slot;
            }
        }
    }

    /**
     * @param uniforms {@code CreateInfo.uniforms()}; the position is the SPIR-V binding and the {@code setUniform} index
     * @throws IllegalArgumentException if the pipeline needs more registers than the global root signature offers
     */
    public static SlotAssignment assign(List<BindGroupLayout.UniformDescription> uniforms) {
        SlotKind[] kinds = new SlotKind[uniforms.size()];
        int[] registers = new int[uniforms.size()];
        int cbv = 0;
        int srv = 0;

        for (int i = 0; i < kinds.length; i++) {
            kinds[i] = SlotKind.of(uniforms.get(i).type());

            if (kinds[i] == SlotKind.UBO) {
                if (cbv >= MAX_CBV) {
                    throw new IllegalArgumentException("pipeline needs more than " + MAX_CBV + " uniform buffers");
                }

                registers[i] = cbv++;
            } else {
                if (srv >= MAX_SRV) {
                    throw new IllegalArgumentException("pipeline needs more than " + MAX_SRV + " textures / texel buffers");
                }

                registers[i] = srv++;
            }
        }

        return new SlotAssignment(kinds, registers, cbv, srv);
    }

    /** @return the number of uniform slots */
    public int size() {
        return this.kinds.length;
    }

    public SlotKind kind(int slot) {
        return this.kinds[slot];
    }

    /** @return {@code b<n>} for UBO slots, {@code t<n>} (and {@code s<n>}) for TEXTURE / TEXEL slots */
    public int register(int slot) {
        return this.registers[slot];
    }

    public int cbvCount() {
        return this.cbvCount;
    }

    public int srvCount() {
        return this.srvCount;
    }

    /** @return the uniform slot that owns root CBV register {@code register}, or -1 */
    public int cbvOwner(int register) {
        return this.cbvToSlot[register];
    }

    /** @return the uniform slot that owns SRV table entry {@code register}, or -1 */
    public int srvOwner(int register) {
        return this.srvToSlot[register];
    }
}
