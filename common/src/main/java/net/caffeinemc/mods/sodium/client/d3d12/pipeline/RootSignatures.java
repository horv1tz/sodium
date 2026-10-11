// Owner: WP3 (pipelines + render pass) - implemented
package net.caffeinemc.mods.sodium.client.d3d12.pipeline;

import net.caffeinemc.mods.sodium.client.d3d12.core.D3D12Log;
import net.caffeinemc.mods.sodium.client.d3d12.interop.ComObject;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Enums;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12CommandSignatureDesc;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12DescriptorRange;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12DrawArguments;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12DrawIndexedArguments;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12IndirectArgumentDesc;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12RootConstants;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12RootDescriptor;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12RootDescriptorTable;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12RootParameter;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12RootSignatureDesc;
import net.caffeinemc.mods.sodium.client.d3d12.interop.compiler.D3DBlob;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12CommandSignature;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12Device;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12RootSignature;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicBoolean;

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

    /** Number of root parameters of the global signature ({@code PARAM_SAMPLER_TABLE + 1}). */
    static final int PARAMETER_COUNT = PARAM_SAMPLER_TABLE + 1;
    /** D3D12 limit of a root signature, in DWORDs. */
    private static final int MAX_ROOT_DWORDS = 64;
    /** {@code D3D12_DESCRIPTOR_RANGE_OFFSET_APPEND}. */
    private static final int OFFSET_APPEND = 0xFFFFFFFF;
    /** {@code sizeof(D3D12_DRAW_INDEXED_ARGUMENTS)}. */
    static final int DRAW_INDEXED_STRIDE = (int) D3D12DrawIndexedArguments.SIZE;
    /** {@code sizeof(D3D12_DRAW_ARGUMENTS)}. */
    static final int DRAW_STRIDE = (int) D3D12DrawArguments.SIZE;

    static {
        // the 60-DWORD budget is the measured layout of spec 02 section 10.1; a change of any constant must be deliberate
        if (TOTAL_DWORDS != 60) {
            throw new ExceptionInInitializerError("The global root signature must cost 60 DWORDs, not " + TOTAL_DWORDS);
        }
    }

    private final D3D12RootSignature global;
    private final D3D12CommandSignature drawIndexed;
    private final D3D12CommandSignature draw;
    private final AtomicBoolean closed = new AtomicBoolean();

    private RootSignatures(D3D12RootSignature global, D3D12CommandSignature drawIndexed, D3D12CommandSignature draw) {
        this.global = global;
        this.drawIndexed = drawIndexed;
        this.draw = draw;
    }

    /**
     * Serialises and creates the root signature and both command signatures. Everything created before a failure is
     * released again (no COM object leaks); the exception of the failing step propagates.
     */
    public static RootSignatures create(D3D12Device device) {
        D3D12RootSignature global = null;
        D3D12CommandSignature drawIndexed = null;
        D3D12CommandSignature draw = null;

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment desc = describeGlobal(arena);
            int cost = dwordCost(desc);

            if (cost != TOTAL_DWORDS || cost > MAX_ROOT_DWORDS) {
                throw new IllegalStateException("The global root signature costs " + cost + " DWORDs, expected " + TOTAL_DWORDS);
            }

            try (D3DBlob blob = D3D12.serializeRootSignature(desc, Enums.RootSignatureVersion._1_0)) {
                global = device.createRootSignature(blob);
            }

            // no root-signature argument changes between the records, so the command signatures take no root signature
            drawIndexed = device.createCommandSignature(describeCommandSignature(arena, Enums.IndirectArgumentType.DRAW_INDEXED,
                    DRAW_INDEXED_STRIDE), null);
            draw = device.createCommandSignature(describeCommandSignature(arena, Enums.IndirectArgumentType.DRAW, DRAW_STRIDE), null);

            return new RootSignatures(global, drawIndexed, draw);
        } catch (Throwable t) {
            releaseQuietly(draw);
            releaseQuietly(drawIndexed);
            releaseQuietly(global);

            throw t;
        }
    }

    /**
     * Builds the {@code D3D12_ROOT_SIGNATURE_DESC} of the global signature in {@code arena} (pure memory, no native call:
     * the layout test runs on Linux). The ranges and the parameter array live in the same arena.
     */
    static MemorySegment describeGlobal(Arena arena) {
        MemorySegment parameters = D3D12RootParameter.allocateArray(arena, PARAMETER_COUNT);
        MemorySegment ranges = D3D12DescriptorRange.allocateArray(arena, 2);

        // 0: push constants b13, visible to every stage
        rootConstants(parameters, PARAM_PUSH, SlotAssignment.PUSH_CONSTANT_REGISTER, MAX_PUSH_DWORDS, Enums.ShaderVisibility.ALL);
        // 1: SPIRV_Cross_VertexInfo b12, vertex stage only
        rootConstants(parameters, PARAM_VERTEX_INFO, SlotAssignment.VERTEX_INFO_REGISTER, VERTEX_INFO_DWORDS,
                Enums.ShaderVisibility.VERTEX);

        // 2..13: root CBVs b0..b11
        for (int register = 0; register < SlotAssignment.MAX_CBV; register++) {
            MemorySegment parameter = parameter(parameters, PARAM_CBV0 + register);

            D3D12RootParameter.setParameterType(parameter, Enums.RootParameterType.CBV);
            D3D12RootParameter.setShaderVisibility(parameter, Enums.ShaderVisibility.ALL);

            MemorySegment descriptor = D3D12RootParameter.sliceDescriptor(parameter);

            D3D12RootDescriptor.setShaderRegister(descriptor, register);
            D3D12RootDescriptor.setRegisterSpace(descriptor, 0);
        }

        // 14: SRV table t0..t15; 15: sampler table s0..s15
        MemorySegment srvRange = ranges.asSlice(0, D3D12DescriptorRange.SIZE);
        MemorySegment samplerRange = ranges.asSlice(D3D12DescriptorRange.SIZE, D3D12DescriptorRange.SIZE);

        tableRange(srvRange, Enums.DescriptorRangeType.SRV, SlotAssignment.MAX_SRV);
        tableRange(samplerRange, Enums.DescriptorRangeType.SAMPLER, SlotAssignment.MAX_SAMPLER);

        descriptorTable(parameter(parameters, PARAM_SRV_TABLE), srvRange);
        descriptorTable(parameter(parameters, PARAM_SAMPLER_TABLE), samplerRange);

        MemorySegment desc = D3D12RootSignatureDesc.allocate(arena);

        D3D12RootSignatureDesc.setNumParameters(desc, PARAMETER_COUNT);
        D3D12RootSignatureDesc.setPParameters(desc, parameters);
        D3D12RootSignatureDesc.setNumStaticSamplers(desc, 0);
        D3D12RootSignatureDesc.setPStaticSamplers(desc, MemorySegment.NULL);
        D3D12RootSignatureDesc.setFlags(desc, Enums.RootSignatureFlags.ALLOW_INPUT_ASSEMBLER_INPUT_LAYOUT
                | Enums.RootSignatureFlags.DENY_HULL_SHADER_ROOT_ACCESS
                | Enums.RootSignatureFlags.DENY_DOMAIN_SHADER_ROOT_ACCESS
                | Enums.RootSignatureFlags.DENY_GEOMETRY_SHADER_ROOT_ACCESS);

        return desc;
    }

    /**
     * The cost of a root signature description in DWORDs, as D3D12 counts it: 1 per descriptor table, 2 per root
     * descriptor, 1 per 32-bit constant.
     */
    static int dwordCost(MemorySegment desc) {
        int count = D3D12RootSignatureDesc.getNumParameters(desc);
        MemorySegment parameters = D3D12RootSignatureDesc.getPParameters(desc).reinterpret(D3D12RootParameter.SIZE * count);
        int cost = 0;

        for (int i = 0; i < count; i++) {
            MemorySegment parameter = parameter(parameters, i);

            cost += switch (D3D12RootParameter.getParameterType(parameter)) {
                case Enums.RootParameterType.DESCRIPTOR_TABLE -> 1;
                case Enums.RootParameterType._32BIT_CONSTANTS ->
                        D3D12RootConstants.getNum32BitValues(D3D12RootParameter.sliceConstants(parameter));
                default -> 2;
            };
        }

        return cost;
    }

    /**
     * Builds a {@code D3D12_COMMAND_SIGNATURE_DESC} with a single argument of the given type. The one-element argument
     * array lives in {@code arena}.
     */
    static MemorySegment describeCommandSignature(Arena arena, int argumentType, int byteStride) {
        MemorySegment argument = D3D12IndirectArgumentDesc.allocate(arena);

        D3D12IndirectArgumentDesc.setType(argument, argumentType);

        MemorySegment desc = D3D12CommandSignatureDesc.allocate(arena);

        D3D12CommandSignatureDesc.setByteStride(desc, byteStride);
        D3D12CommandSignatureDesc.setNumArgumentDescs(desc, 1);
        D3D12CommandSignatureDesc.setPArgumentDescs(desc, argument);
        D3D12CommandSignatureDesc.setNodeMask(desc, 0);

        return desc;
    }

    private static MemorySegment parameter(MemorySegment parameters, int index) {
        return parameters.asSlice(index * D3D12RootParameter.SIZE, D3D12RootParameter.SIZE);
    }

    private static void rootConstants(MemorySegment parameters, int index, int register, int dwords, int visibility) {
        MemorySegment parameter = parameter(parameters, index);

        D3D12RootParameter.setParameterType(parameter, Enums.RootParameterType._32BIT_CONSTANTS);
        D3D12RootParameter.setShaderVisibility(parameter, visibility);

        MemorySegment constants = D3D12RootParameter.sliceConstants(parameter);

        D3D12RootConstants.setShaderRegister(constants, register);
        D3D12RootConstants.setRegisterSpace(constants, 0);
        D3D12RootConstants.setNum32BitValues(constants, dwords);
    }

    private static void tableRange(MemorySegment range, int type, int count) {
        D3D12DescriptorRange.setRangeType(range, type);
        D3D12DescriptorRange.setNumDescriptors(range, count);
        D3D12DescriptorRange.setBaseShaderRegister(range, 0);
        D3D12DescriptorRange.setRegisterSpace(range, 0);
        D3D12DescriptorRange.setOffsetInDescriptorsFromTableStart(range, OFFSET_APPEND);
    }

    private static void descriptorTable(MemorySegment parameter, MemorySegment range) {
        D3D12RootParameter.setParameterType(parameter, Enums.RootParameterType.DESCRIPTOR_TABLE);
        D3D12RootParameter.setShaderVisibility(parameter, Enums.ShaderVisibility.ALL);

        MemorySegment table = D3D12RootParameter.sliceDescriptorTable(parameter);

        D3D12RootDescriptorTable.setNumDescriptorRanges(table, 1);
        D3D12RootDescriptorTable.setPDescriptorRanges(table, range);
    }

    private static void releaseQuietly(ComObject object) {
        if (object == null) {
            return;
        }

        try {
            object.release();
        } catch (Throwable t) {
            D3D12Log.error("Failed to release a root signature object", t);
        }
    }

    /** @return the global graphics root signature used by every PSO and every pass */
    public D3D12RootSignature global() {
        return this.global;
    }

    /** @return the command signature of {@code D3D12_DRAW_INDEXED_ARGUMENTS} (stride 20) */
    public D3D12CommandSignature drawIndexed() {
        return this.drawIndexed;
    }

    /** @return the command signature of {@code D3D12_DRAW_ARGUMENTS} (stride 16) */
    public D3D12CommandSignature draw() {
        return this.draw;
    }

    /** Idempotent, never throws. The caller guarantees the GPU and the compile workers no longer use the objects. */
    @Override
    public void close() {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }

        releaseQuietly(this.draw);
        releaseQuietly(this.drawIndexed);
        releaseQuietly(this.global);
    }
}
