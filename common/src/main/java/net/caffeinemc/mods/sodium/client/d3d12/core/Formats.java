// Owner: WP1 (device core) - fully implemented shared helper
package net.caffeinemc.mods.sodium.client.d3d12.core;

import com.mojang.renderpearl.api.GpuFormat;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Enums;

/**
 * {@link GpuFormat} to DXGI mapping tables (spec 02 section 7.1, spec 01 sections 2.2/2.3, spec 04 section 6).
 *
 * <p>Rules:
 * <ul>
 *     <li>Colour formats map to the DXGI format of the same name. {@code RGB8_*}, {@code RGB16_*} (including
 *     {@code RGB16_FLOAT}) have no DXGI texture format: {@code textureOk == false}, and as a vertex attribute they use
 *     the 4-component format of the same type (the input assembler reads one extra component).
 *     {@code RGB32_*} are vertex-only ({@code textureOk == false}).</li>
 *     <li>Depth formats are always created typeless so one resource can be attached (DSV), sampled (SRV) and copied:
 *     {@code D32_FLOAT} = {@code R32_TYPELESS}/{@code D32_FLOAT}/{@code R32_FLOAT}; {@code D16_UNORM} likewise with 16
 *     bits. {@code D24_UNORM_S8_UINT}, {@code D32_FLOAT_S8_UINT} and {@code S8_UINT} are rejected (the game has no
 *     stencil and references only {@code D32_FLOAT}).</li>
 *     <li>Packed formats: {@code RGB10A2_UNORM/UINT} and {@code RG11B10_FLOAT} map one to one.</li>
 * </ul>
 *
 * <p>Immutable tables, thread-safe, no native access.
 */
public final class Formats {
    /** SPIRV-Cross {@code SpvcBasetype} codes (spec 02 section 9.1 item 9). */
    public static final int BASE_INT = 7;
    public static final int BASE_UINT = 8;
    public static final int BASE_FLOAT = 13;

    /**
     * The DSV format baked into the pipeline state objects (variant A of every pipeline, {@code D3D12PipelineStateBuilder}).
     * D3D12 requires the PSO's {@code DSVFormat} to equal the format of the bound depth-stencil view, so this is also the
     * only format a depth <em>attachment</em> may have ({@link #requireDepthAttachment}).
     */
    public static final int PSO_DSV_FORMAT = Enums.Format.D32_FLOAT;

    private static final FormatInfo[] TABLE = buildTable();

    private Formats() {
    }

    /**
     * Guard for texture creation with {@code USAGE_RENDER_ATTACHMENT} and for {@code PassSpec.from}: a depth attachment
     * must be {@code D32_FLOAT} (the Vulkan reference hard-codes the same format). {@code D16_UNORM} stays legal for
     * textures that are only sampled or copied.
     *
     * @throws IllegalArgumentException {@code "only D32_FLOAT depth attachments are supported: <format>"}
     */
    public static void requireDepthAttachment(GpuFormat format) {
        FormatInfo info = of(format);

        if (!info.depth() || info.dsvFormat() != PSO_DSV_FORMAT) {
            throw new IllegalArgumentException("only D32_FLOAT depth attachments are supported: " + format);
        }
    }

    /** @return the (shared, immutable) description of {@code format} */
    public static FormatInfo of(GpuFormat format) {
        return TABLE[format.ordinal()];
    }

    /**
     * @return the info of a format that must be usable as a texture
     * @throws IllegalArgumentException {@code "<format> format cannot be used to create textures"} otherwise (the GL
     *                                  precedent; the message is part of the contract)
     */
    public static FormatInfo requireTexture(GpuFormat format) {
        FormatInfo info = of(format);

        if (!info.textureOk()) {
            throw new IllegalArgumentException(format + " format cannot be used to create textures");
        }

        return info;
    }

    /** @return the input layout format for a vertex attribute of {@code format} */
    public static int vertexFormat(GpuFormat format) {
        FormatInfo info = of(format);

        if (!info.vertexOk()) {
            throw new IllegalArgumentException(format + " format cannot be used as a vertex attribute");
        }

        return info.vertexFormat();
    }

    private static FormatInfo[] buildTable() {
        GpuFormat[] all = GpuFormat.values();
        FormatInfo[] table = new FormatInfo[all.length];

        for (GpuFormat format : all) {
            table[format.ordinal()] = build(format);
        }

        return table;
    }

    private static FormatInfo build(GpuFormat f) {
        int bytes = f.blockSize();

        switch (f) {
            case D32_FLOAT:
                return new FormatInfo(f, bytes, Enums.Format.R32_TYPELESS, Enums.Format.R32_FLOAT, Enums.Format.UNKNOWN,
                        Enums.Format.D32_FLOAT, Enums.Format.R32_FLOAT, Enums.Format.UNKNOWN, true, true, false, BASE_FLOAT);
            case D16_UNORM:
                return new FormatInfo(f, bytes, Enums.Format.R16_TYPELESS, Enums.Format.R16_UNORM, Enums.Format.UNKNOWN,
                        Enums.Format.D16_UNORM, Enums.Format.R16_UNORM, Enums.Format.UNKNOWN, true, true, false, BASE_FLOAT);
            case D24_UNORM_S8_UINT:
            case D32_FLOAT_S8_UINT:
            case S8_UINT:
                return new FormatInfo(f, bytes, 0, 0, 0, 0, 0, 0, f.hasDepthAspect(), false, false, BASE_FLOAT);
            case RGB10A2_UNORM:
                return color(f, Enums.Format.R10G10B10A2_UNORM, Enums.Format.R10G10B10A2_UNORM, BASE_FLOAT);
            case RGB10A2_UINT:
                return color(f, Enums.Format.R10G10B10A2_UINT, Enums.Format.R10G10B10A2_UINT, BASE_UINT);
            case RG11B10_FLOAT:
                return color(f, Enums.Format.R11G11B10_FLOAT, Enums.Format.R11G11B10_FLOAT, BASE_FLOAT);
            default:
                break;
        }

        int count = f.componentCount();
        int exact = lookup(f.componentType(), count);
        int four = lookup(f.componentType(), 4);
        int vertex = exact != 0 ? exact : four;
        boolean textureOk = exact != 0 && !(count == 3 && f.componentType().byteSize() == 4);
        int base = baseType(f.componentType());

        return new FormatInfo(f, bytes, textureOk ? exact : 0, textureOk ? exact : 0, textureOk ? exact : 0,
                Enums.Format.UNKNOWN, textureOk ? exact : 0, vertex, false, textureOk, vertex != 0, base);
    }

    private static FormatInfo color(GpuFormat f, int resource, int vertex, int base) {
        return new FormatInfo(f, f.blockSize(), resource, resource, resource, Enums.Format.UNKNOWN, resource, vertex,
                false, true, true, base);
    }

    private static int baseType(GpuFormat.ComponentType type) {
        return switch (type) {
            case SINT_8, SINT_16, SINT_32 -> BASE_INT;
            case UINT_8, UINT_16, UINT_32 -> BASE_UINT;
            default -> BASE_FLOAT;
        };
    }

    /** @return the DXGI format of an exactly matching colour format, 0 if DXGI has none (3-component 8/16 bit) */
    private static int lookup(GpuFormat.ComponentType type, int count) {
        return switch (type) {
            case UNORM_8 -> pick(count, Enums.Format.R8_UNORM, Enums.Format.R8G8_UNORM, 0, Enums.Format.R8G8B8A8_UNORM);
            case SNORM_8 -> pick(count, Enums.Format.R8_SNORM, Enums.Format.R8G8_SNORM, 0, Enums.Format.R8G8B8A8_SNORM);
            case UINT_8 -> pick(count, Enums.Format.R8_UINT, Enums.Format.R8G8_UINT, 0, Enums.Format.R8G8B8A8_UINT);
            case SINT_8 -> pick(count, Enums.Format.R8_SINT, Enums.Format.R8G8_SINT, 0, Enums.Format.R8G8B8A8_SINT);
            case UNORM_16 -> pick(count, Enums.Format.R16_UNORM, Enums.Format.R16G16_UNORM, 0, Enums.Format.R16G16B16A16_UNORM);
            case SNORM_16 -> pick(count, Enums.Format.R16_SNORM, Enums.Format.R16G16_SNORM, 0, Enums.Format.R16G16B16A16_SNORM);
            case UINT_16 -> pick(count, Enums.Format.R16_UINT, Enums.Format.R16G16_UINT, 0, Enums.Format.R16G16B16A16_UINT);
            case SINT_16 -> pick(count, Enums.Format.R16_SINT, Enums.Format.R16G16_SINT, 0, Enums.Format.R16G16B16A16_SINT);
            case FLOAT_16 -> pick(count, Enums.Format.R16_FLOAT, Enums.Format.R16G16_FLOAT, 0, Enums.Format.R16G16B16A16_FLOAT);
            case UINT_32 -> pick(count, Enums.Format.R32_UINT, Enums.Format.R32G32_UINT, Enums.Format.R32G32B32_UINT, Enums.Format.R32G32B32A32_UINT);
            case SINT_32 -> pick(count, Enums.Format.R32_SINT, Enums.Format.R32G32_SINT, Enums.Format.R32G32B32_SINT, Enums.Format.R32G32B32A32_SINT);
            case FLOAT_32 -> pick(count, Enums.Format.R32_FLOAT, Enums.Format.R32G32_FLOAT, Enums.Format.R32G32B32_FLOAT, Enums.Format.R32G32B32A32_FLOAT);
            default -> 0;
        };
    }

    private static int pick(int count, int one, int two, int three, int four) {
        return switch (count) {
            case 1 -> one;
            case 2 -> two;
            case 3 -> three;
            case 4 -> four;
            default -> 0;
        };
    }
}
