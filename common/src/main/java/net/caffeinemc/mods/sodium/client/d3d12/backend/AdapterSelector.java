// Owner: WP1 (device core)
package net.caffeinemc.mods.sodium.client.d3d12.backend;

import com.mojang.renderpearl.api.device.BackendCreationException;
import net.caffeinemc.mods.sodium.client.d3d12.core.DeviceCaps;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12Device;
import net.caffeinemc.mods.sodium.client.d3d12.interop.dxgi.DxgiAdapter;
import net.caffeinemc.mods.sodium.client.d3d12.interop.dxgi.DxgiFactory;

/**
 * Chooses the adapter and creates the {@code ID3D12Device} on it (spec 03 section 2.4/2.5, decision D18).
 *
 * <p>Rule (the Vulkan rule "in spirit"): enumerate with {@code enumAdapters(HIGH_PERFORMANCE)}; for each adapter compute a
 * failure reason or null - {@code D3D12.checkDeviceSupport(adapter, FL 11_0)} must report S_FALSE-success, the device must
 * be creatable, and the required capabilities must hold (feature level 11_0, {@code ResourceBindingTier >= 1}, root
 * signature 1.0, typed buffer SRV support for {@code R8_SINT}/{@code R32_SINT}, RTV + blendable
 * {@code R16G16B16A16_FLOAT}/{@code R32G32B32A32_FLOAT}/{@code R8G8B8A8_UNORM}, {@code DXGI_FORMAT_D32_FLOAT} as DSV).
 * The first suitable <em>hardware</em> adapter wins, upgraded to the first suitable <em>discrete</em> one (not UMA);
 * software adapters (WARP, vendor 0x1414, {@code DXGI_ADAPTER_FLAG_SOFTWARE}) only if nothing else is suitable and
 * {@code options.softwareFallback()} (Wine's lavapipe appears as a software adapter, so tests need it).
 * {@code options.adapterNameFilter()} overrides the choice (substring of the description). The first failure reason is
 * remembered and reported when nothing is suitable.
 *
 * <p>No adapter, or none suitable: {@link BackendCreationException} with {@code Reason.OTHER} and
 * {@code missingCapabilities} such as {@code "D3D_FEATURE_LEVEL_11_0"} or {@code "RESOURCE_BINDING_TIER_1"}; the message
 * is short and human readable (adapter name, HRESULT in hex) because Minecraft shows it to the user.
 */
public final class AdapterSelector {
    /** The chosen adapter, its device and the probed facts. The caller owns (and closes) {@code adapter} and {@code device}. */
    public record Selection(DxgiAdapter adapter, D3D12Device device, DeviceCaps caps) {
    }

    private AdapterSelector() {
    }

    public static Selection select(DxgiFactory factory, DeviceOptions options) throws BackendCreationException {
        throw new UnsupportedOperationException("WP1: enumerate, probe, rank, create the device, fill DeviceCaps");
    }
}
