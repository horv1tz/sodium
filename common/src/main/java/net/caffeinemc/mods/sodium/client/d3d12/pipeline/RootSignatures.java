// Owner: WP3 (pipelines + render pass)
package net.caffeinemc.mods.sodium.client.d3d12.pipeline;

import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12CommandSignature;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12Device;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12RootSignature;

/**
 * The one global root signature of the backend and the two {@code ExecuteIndirect} command signatures.
 *
 * <h2>Layout (spec 02 section 10.1; measured on vkd3d to create 241 + 4 PSOs; 60 of 64 DWORDs)</h2>
 * Version 1.0 serialisation, flags {@code ALLOW_INPUT_ASSEMBLER_INPUT_LAYOUT | DENY_HULL/DOMAIN/GEOMETRY_SHADER_ROOT_ACCESS}.
 * <table>
 *   <caption>Root parameters</caption>
 *   <tr><th>index</th><th>constant</th><th>type</th><th>register</th><th>DWORDs</th><th>visibility</th><th>content</th></tr>
 *   <tr><td>0</td><td>{@link #PARAM_PUSH}</td><td>32 root constants</td><td>{@code b13}</td><td>32</td><td>ALL</td><td>push constants</td></tr>
 *   <tr><td>1</td><td>{@link #PARAM_VERTEX_INFO}</td><td>2 root constants</td><td>{@code b12}</td><td>2</td><td>VERTEX</td><td>{@code SPIRV_Cross_VertexInfo {int BaseVertex; int BaseInstance;}}</td></tr>
 *   <tr><td>2..13</td><td>{@link #PARAM_CBV0}{@code + k}</td><td>root CBV</td><td>{@code b0..b11}</td><td>24</td><td>ALL</td><td>UBO slots; GPU VA = buffer VA + slice offset (256 aligned)</td></tr>
 *   <tr><td>14</td><td>{@link #PARAM_SRV_TABLE}</td><td>table, 1 SRV range of 16</td><td>{@code t0..t15}</td><td>1</td><td>ALL</td><td>textures + typed texel buffers</td></tr>
 *   <tr><td>15</td><td>{@link #PARAM_SAMPLER_TABLE}</td><td>table, 1 sampler range of 16</td><td>{@code s0..s15}</td><td>1</td><td>ALL</td><td>one sampler per texture slot</td></tr>
 * </table>
 * The parameter order is the one that was measured; reordering for hypothetical register-spilling benefits is rejected
 * (decision D9). Both tables are always fully written (null descriptors for unowned entries), so the layout is valid on
 * Resource Binding Tier 1 hardware too.
 *
 * <h2>Command signatures</h2>
 * {@code DRAW_INDEXED} (stride 20, {@code D3D12_DRAW_INDEXED_ARGUMENTS}) and {@code DRAW} (stride 16), no root-signature
 * argument (no root argument changes between the records). Created once at device construction.
 *
 * <p>Thread-safe after creation (read-only); the native objects are free-threaded. Compile workers read {@link #global()}
 * concurrently, so {@code D3D12GpuDevice.close()} releases this object only after the pipeline compiler's shutdown gate
 * reported {@code isDrained()} (it is skipped, deliberately leaked, when a compile did not leave within the timeout).
 */
public final class RootSignatures implements AutoCloseable {
    public static final int PARAM_PUSH = 0;
    public static final int PARAM_VERTEX_INFO = 1;
    public static final int PARAM_CBV0 = 2;
    public static final int PARAM_SRV_TABLE = 14;
    public static final int PARAM_SAMPLER_TABLE = 15;

    public static final int MAX_PUSH_DWORDS = 32;
    public static final int VERTEX_INFO_DWORDS = 2;
    public static final int TABLE_ENTRIES = 16;
    /** Total root signature cost in DWORDs (limit 64). */
    public static final int TOTAL_DWORDS = MAX_PUSH_DWORDS + VERTEX_INFO_DWORDS + SlotAssignment.MAX_CBV * 2 + 2;

    private RootSignatures() {
    }

    /** Serialises and creates the root signature and both command signatures. */
    public static RootSignatures create(D3D12Device device) {
        throw new UnsupportedOperationException("WP3: serialize v1.0 root signature (60 DWORDs), 2 command signatures");
    }

    /** @return the global graphics root signature used by every PSO and every pass */
    public D3D12RootSignature global() {
        throw new UnsupportedOperationException("WP3");
    }

    /** @return the command signature of {@code D3D12_DRAW_INDEXED_ARGUMENTS} (stride 20) */
    public D3D12CommandSignature drawIndexed() {
        throw new UnsupportedOperationException("WP3");
    }

    /** @return the command signature of {@code D3D12_DRAW_ARGUMENTS} (stride 16) */
    public D3D12CommandSignature draw() {
        throw new UnsupportedOperationException("WP3");
    }

    @Override
    public void close() {
        throw new UnsupportedOperationException("WP3");
    }
}
