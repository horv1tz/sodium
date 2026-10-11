package net.caffeinemc.mods.sodium.client.gpu.device.backend;

import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import net.caffeinemc.mods.sodium.mixin.core.GpuDeviceAccessor;

import java.util.Locale;

public enum DrawBackend {
    OPENGL,
    VK_MULTIDRAW,
    VK_INDIRECT,
    /**
     * Terrain rendering through a Direct3D 12 device. The game does not ship one, so it has to come from a backend
     * provided elsewhere, which must:
     * <ul>
     *     <li>report a backend name (or have a device class name) containing "D3D12", "Direct3D 12" or "DirectX 12".
     *     Devices reporting OpenGL or Vulkan are never selected, even when translated onto Direct3D 12;</li>
     *     <li>expose {@code multiDrawIndirect} in its device features;</li>
     *     <li>implement {@code RenderPass#drawIndexedIndirect} as an {@code ExecuteIndirect} over
     *     {@code D3D12_DRAW_INDEXED_ARGUMENTS} commands, and {@code RenderPass#pushConstants} as root constants.</li>
     * </ul>
     */
    D3D12_INDIRECT;

    public static final DrawBackend BACKEND = chooseBackend();

    private static DrawBackend chooseBackend() {
        GpuDevice device = RenderSystem.getDevice();

        if (((GpuDeviceAccessor) device).sodium$getBackend() instanceof VulkanDevice) {
            if (device.getDeviceInfo().features().multiDrawDirectInterleaved()) {
                return DrawBackend.VK_MULTIDRAW;
            } else if (device.getDeviceInfo().features().multiDrawIndirect()) {
                return DrawBackend.VK_INDIRECT;
            } else {
                throw new IllegalStateException("Somehow, none of the multidraw backends are supported by this device.");
            }
        } else if (isD3D12(device)) {
            // ExecuteIndirect is a core part of Direct3D 12, but the device must still expose it through the abstraction.
            if (device.getDeviceInfo().features().multiDrawIndirect()) {
                return DrawBackend.D3D12_INDIRECT;
            } else {
                throw new IllegalStateException("The Direct3D 12 device does not support indirect multi-draw.");
            }
        } else {
            return DrawBackend.OPENGL;
        }
    }

    // The game does not ship a Direct3D 12 backend, so there is no class to test against. Backends provided by other
    // mods are identified by the name they report, or failing that, by the name of their device class.
    private static boolean isD3D12(GpuDevice device) {
        String apiName = normalizeName(device.getDeviceInfo().backendName());

        // An OpenGL or Vulkan device which only runs on top of Direct3D 12 (through a translation layer) is still
        // an OpenGL or Vulkan device as far as the draw path is concerned.
        if (apiName.contains("opengl") || apiName.contains("vulkan")) {
            return false;
        }

        String deviceClassName = normalizeName(((GpuDeviceAccessor) device).sodium$getBackend().getClass().getSimpleName());

        return isD3D12Name(apiName) || isD3D12Name(deviceClassName);
    }

    private static String normalizeName(String name) {
        return name.toLowerCase(Locale.ROOT).replace(" ", "").replace("_", "").replace("-", "");
    }

    private static boolean isD3D12Name(String normalizedName) {
        return normalizedName.contains("d3d12")
                || normalizedName.contains("direct3d12")
                || normalizedName.contains("directx12")
                || normalizedName.contains("dx12");
    }

    public String getName() {
        return switch (this) {
            case OPENGL -> "gl_multidraw (sodium)";
            case VK_MULTIDRAW -> "ext_multidraw (sodium)";
            case VK_INDIRECT -> "indirect (sodium)";
            case D3D12_INDIRECT -> "d3d12_indirect (sodium)";
        };
    }
}
