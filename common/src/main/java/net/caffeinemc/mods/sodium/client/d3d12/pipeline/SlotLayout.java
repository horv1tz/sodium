// Owner: WP3 (pipelines + render pass) - fully implemented
package net.caffeinemc.mods.sodium.client.d3d12.pipeline;

import java.util.Arrays;
import java.util.List;

/**
 * The part of a pipeline that decides how {@code setUniform} values map to root parameters and descriptor tables:
 * the slots (name, kind, register, activity, dimension, element class), the number of push-constant DWORDs and whether
 * the pipeline reads {@code SPIRV_Cross_VertexInfo}. Immutable.
 *
 * <p><b>Interning (decision D17).</b> {@link SlotLayouts#intern} gives structurally identical layouts the same
 * {@link #id()}. {@code PassBinder.setPipeline} keeps all stored uniform values when the new pipeline has the same layout
 * id as the old one: the frontend's full uniform replay then stores identical values, nothing becomes dirty, and
 * switching between sibling pipelines (Sodium's opaque / cutout / translucent, most vanilla entity pipelines) rebuilds no
 * table and rebinds no root CBV. Safety: the names are part of the key, so equal ids imply the same name -> slot map, and
 * a layout change clears the stored values, so a value can never survive into a pipeline that lacks its name.
 */
public final class SlotLayout {
    private final int id;
    private final List<SlotInfo> slots;
    private final int cbvCount;
    private final int srvCount;
    private final boolean hasTexel;
    private final int pushDwords;
    private final boolean usesVertexInfo;
    private final int[] cbvOwner = new int[SlotAssignment.MAX_CBV];
    private final int[] srvOwner = new int[SlotAssignment.MAX_SRV];

    private SlotLayout(int id, List<SlotInfo> slots, int pushDwords, boolean usesVertexInfo) {
        this.id = id;
        this.slots = List.copyOf(slots);
        this.pushDwords = pushDwords;
        this.usesVertexInfo = usesVertexInfo;

        Arrays.fill(this.cbvOwner, -1);
        Arrays.fill(this.srvOwner, -1);

        int cbvs = 0;
        int srvs = 0;
        boolean texel = false;

        for (int i = 0; i < this.slots.size(); i++) {
            SlotInfo slot = this.slots.get(i);

            if (slot.kind() == SlotKind.UBO) {
                this.cbvOwner[slot.register()] = i;
                cbvs++;
            } else {
                this.srvOwner[slot.register()] = i;
                srvs++;
                texel |= slot.kind() == SlotKind.TEXEL;
            }
        }

        this.cbvCount = cbvs;
        this.srvCount = srvs;
        this.hasTexel = texel;
    }

    /** @return a layout with id -1 ("not interned yet") */
    public static SlotLayout of(List<SlotInfo> slots, int pushDwords, boolean usesVertexInfo) {
        if (pushDwords < 0 || pushDwords > 32) {
            throw new IllegalArgumentException("push constants must fit 32 DWORDs: " + pushDwords);
        }

        return new SlotLayout(-1, slots, pushDwords, usesVertexInfo);
    }

    SlotLayout withId(int id) {
        return new SlotLayout(id, this.slots, this.pushDwords, this.usesVertexInfo);
    }

    /** @return the interned id, or -1 before {@link SlotLayouts#intern} */
    public int id() {
        return this.id;
    }

    public List<SlotInfo> slots() {
        return this.slots;
    }

    public int size() {
        return this.slots.size();
    }

    public SlotInfo slot(int index) {
        return this.slots.get(index);
    }

    public int cbvCount() {
        return this.cbvCount;
    }

    public int srvCount() {
        return this.srvCount;
    }

    /** @return DWORDs of the push constant block to send ({@code ceil(pushConstantsSize / 4)}) */
    public int pushDwords() {
        return this.pushDwords;
    }

    public boolean usesVertexInfo() {
        return this.usesVertexInfo;
    }

    /**
     * @return true if some slot is a {@link SlotKind#TEXEL} buffer. Its SRV is created straight in the shader-visible
     * ring and has no staging handle, so the 16-handle key of the SRV table cannot see it: the binder must never look up,
     * insert or skip-by-key the SRV table of such a layout (a recreated buffer would keep the same key).
     */
    public boolean hasTexel() {
        return this.hasTexel;
    }

    /** @return the slot that owns root CBV register {@code register}, or -1 */
    public int cbvOwner(int register) {
        return this.cbvOwner[register];
    }

    /** @return the slot that owns SRV/sampler table entry {@code register}, or -1 */
    public int srvOwner(int register) {
        return this.srvOwner[register];
    }

    /** @return the structural identity used for interning */
    String key() {
        StringBuilder key = new StringBuilder(64 + this.slots.size() * 32);

        for (SlotInfo slot : this.slots) {
            key.append(slot.name()).append('|').append(slot.kind()).append('|').append(slot.register()).append('|')
                    .append(slot.active()).append('|').append(slot.cube()).append('|').append(slot.sampledBaseType()).append('|')
                    .append(slot.texelFormat()).append(';');
        }

        return key.append("push=").append(this.pushDwords).append(",vi=").append(this.usesVertexInfo).toString();
    }
}
