// Owner: WP1 (device core) - fully implemented value type
package net.caffeinemc.mods.sodium.client.d3d12.core;

/**
 * Immutable facts about the adapter, probed once by {@code D3D12GpuDevice.create}. Required features that are missing
 * make device creation fail with a {@code BackendCreationException}; this record only holds what later code branches on.
 *
 * @param featureLevel         highest {@code D3D_FEATURE_LEVEL} the device supports (at least {@code 0xB000} = 11_0)
 * @param resourceBindingTier  {@code D3D12_RESOURCE_BINDING_TIER} (1..3). Descriptor tables are always written fully, so
 *                             tier 1 hardware needs no special path
 * @param rootSignatureVersion highest supported root signature version (1 = 1.0, 2 = 1.1); the backend serialises 1.0
 * @param uma                  unified memory architecture (integrated GPU)
 * @param tearingSupported     {@code DXGI_FEATURE_PRESENT_ALLOW_TEARING}
 * @param timestampFrequency   ticks per second of {@code ID3D12CommandQueue::GetTimestampFrequency}
 * @param softwareAdapter      the adapter is WARP / a software rasteriser (also true for Wine's lavapipe)
 * @param adapterName          {@code DXGI_ADAPTER_DESC1.Description}
 * @param vendorId             PCI vendor id
 * @param deviceId             PCI device id
 * @param dedicatedVideoMemory bytes of dedicated video memory
 */
public record DeviceCaps(
        int featureLevel,
        int resourceBindingTier,
        int rootSignatureVersion,
        boolean uma,
        boolean tearingSupported,
        long timestampFrequency,
        boolean softwareAdapter,
        String adapterName,
        int vendorId,
        int deviceId,
        long dedicatedVideoMemory) {
}
