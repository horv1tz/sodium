// Owner: WP4 (StartupProbes measures it) / WP3 (PassBinder consumes it) - fully implemented value type
package net.caffeinemc.mods.sodium.client.d3d12.core;

/**
 * What {@code SV_VertexID} contains on the adapter in use. Vulkan's {@code gl_VertexIndex} (which the shaders are
 * written against) always includes {@code firstVertex} (non-indexed) resp. {@code vertexOffset} (indexed). SPIRV-Cross
 * emulates that by adding {@code SPIRV_Cross_BaseVertex} to {@code SV_VertexID}, so the value we have to push into the
 * root constants depends on whether the hardware already included the base.
 *
 * <p>Measured under Wine/vkd3d: neither is included ({@link #DEFAULT}). Native D3D12 is not known; {@code StartupProbes}
 * measures it at device creation and stores the result in {@code DeviceContext}.
 *
 * @param nonIndexedIncludesStart {@code SV_VertexID} of {@code DrawInstanced} already contains {@code StartVertexLocation}
 * @param indexedIncludesBase     {@code SV_VertexID} of {@code DrawIndexedInstanced} already contains {@code BaseVertexLocation}
 */
public record VertexIdSemantics(boolean nonIndexedIncludesStart, boolean indexedIncludesBase) {
    /** The vkd3d measurement; also the fallback when the probe fails. */
    public static final VertexIdSemantics DEFAULT = new VertexIdSemantics(false, false);

    /**
     * @param indexed {@code true} for {@code DrawIndexedInstanced}
     * @param base    {@code firstVertex} (non-indexed) or {@code vertexOffset} (indexed) of the draw call
     * @return the value for the {@code BaseVertex} root constant
     */
    public int rootBaseVertex(boolean indexed, int base) {
        boolean included = indexed ? this.indexedIncludesBase : this.nonIndexedIncludesStart;
        return included ? 0 : base;
    }
}
