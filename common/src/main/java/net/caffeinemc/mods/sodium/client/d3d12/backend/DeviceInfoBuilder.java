// Owner: WP1 (device core) - fully implemented
package net.caffeinemc.mods.sodium.client.d3d12.backend;

import com.mojang.renderpearl.api.device.DeviceFeatures;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.DeviceLimits;
import com.mojang.renderpearl.api.device.DeviceType;
import com.mojang.renderpearl.api.device.HintsAndWorkarounds;
import net.caffeinemc.mods.sodium.client.d3d12.core.DeviceCaps;

import java.util.Set;

/**
 * Builds the immutable {@link DeviceInfo} (spec 01 section 3.12, spec 03 section 12). It is complete before the first
 * use - {@code FrontendGpuDevice}'s constructor and the shader compiler worker threads read it - and is never modified.
 *
 * <ul>
 *     <li>{@code backendName = "Direct3D 12"}: must normalise to {@code direct3d12} for Sodium's {@code DrawBackend} and
 *     must never contain {@code OpenGL} or {@code Vulkan}; the device class is called {@code D3D12GpuDevice} (second
 *     detection path).</li>
 *     <li>{@code isZZeroToOne = true}; {@code timestampPeriod = 1e9f / timestampFrequency}.</li>
 *     <li>Limits: {@code maxAnisotropy 16}, {@code minUniformOffsetAlignment 256}, {@code maxTextureSize 16384},
 *     {@code maxMemoryAllocationSize Long.MAX_VALUE}, {@code maxMultiDrawDirectInterleavedDrawCount 0},
 *     {@code maxColorAttachments 8}, {@code maxDrawIndirectDrawCount Integer.MAX_VALUE} (must be &gt; 0 or vanilla's
 *     indirect chunk path is disabled).</li>
 *     <li>Features: {@code wireframeFillMode true}, {@code shaderDrawParameters false} (the instance index emulation is
 *     not provided), {@code multiDrawDirectInterleaved false}, {@code multiDrawDirectSeparate false},
 *     {@code multiDrawIndirect true} (Sodium refuses to start without it), {@code drawIndirect true},
 *     {@code nonZeroFirstInstance true}, {@code persistentMapping true}.</li>
 *     <li>Hints: all {@code false}; {@code writeToBufferIsSlow} only through the {@code sodium.d3d12.writeToBufferIsSlow}
 *     test property (decision D13: Vulkan and GL report false, so the CPU staging path through {@code writeToBuffer} is the
 *     well-trodden one).</li>
 *     <li>Type: {@code CPU} for software adapters, {@code INTEGRATED} for UMA, else {@code DISCRETE}.</li>
 * </ul>
 */
public final class DeviceInfoBuilder {
    private DeviceInfoBuilder() {
    }

    public static DeviceInfo build(DeviceCaps caps, DeviceOptions options, String driverInfo, Set<String> extensions) {
        float period = caps.timestampFrequency() > 0L ? 1.0e9f / (float) caps.timestampFrequency() : 1.0f;

        return new DeviceInfo(
                caps.adapterName(),
                vendorName(caps.vendorId()),
                driverInfo,
                true,
                "Direct3D 12",
                period,
                new DeviceLimits(16, 256, 16384, Long.MAX_VALUE, 0, 8, Integer.MAX_VALUE),
                new DeviceFeatures(true, false, false, false, true, true, true, true),
                Set.copyOf(extensions),
                new HintsAndWorkarounds(options.writeToBufferIsSlow(), false, false, false),
                type(caps));
    }

    /** PCI vendor id to the names the Vulkan backend uses (spec 03 section 2.4); unknown ids are lower-case hex. */
    public static String vendorName(int vendorId) {
        return switch (vendorId) {
            case 0x1002, 0x1022 -> "AMD";
            case 0x1010 -> "IMGTEC";
            case 0x106B -> "APPLE";
            case 0x10DE, 0x12D2 -> "NVIDIA";
            case 0x13B5 -> "ARM";
            case 0x1414 -> "MICROSOFT";
            case 0x14E4 -> "BROADCOM";
            case 0x168C, 0x17CB, 0x1969, 0x5143 -> "QUALCOMM";
            case 0x8086 -> "INTEL";
            default -> "0x" + Integer.toHexString(vendorId);
        };
    }

    public static DeviceType type(DeviceCaps caps) {
        if (caps.softwareAdapter()) {
            return DeviceType.CPU;
        }

        return caps.uma() ? DeviceType.INTEGRATED : DeviceType.DISCRETE;
    }
}
