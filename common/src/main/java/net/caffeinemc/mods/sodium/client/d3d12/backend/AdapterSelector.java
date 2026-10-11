// Owner: WP1 (device core) - implemented
package net.caffeinemc.mods.sodium.client.d3d12.backend;

import com.mojang.renderpearl.api.device.BackendCreationException;
import net.caffeinemc.mods.sodium.client.d3d12.core.D3D12Log;
import net.caffeinemc.mods.sodium.client.d3d12.core.DeviceCaps;
import net.caffeinemc.mods.sodium.client.d3d12.interop.D3D12Exception;
import net.caffeinemc.mods.sodium.client.d3d12.interop.Hr;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Enums;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.structs.D3D12FeatureDataFeatureLevels;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12CommandQueue;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12Device;
import net.caffeinemc.mods.sodium.client.d3d12.interop.dxgi.DxgiAdapter;
import net.caffeinemc.mods.sodium.client.d3d12.interop.dxgi.DxgiFactory;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

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
 *
 * <p>Implementation notes:
 * <ul>
 *     <li>The name filter is a case-insensitive substring match. When it selects a suitable adapter, that adapter wins
 *     whatever its rank (the user asked for it, software included). When it matches no <em>suitable</em> adapter a WARN is
 *     logged and the normal rule is applied, so a stale option never prevents the game from starting.</li>
 *     <li>A {@code Remote} adapter (Remote Desktop's display adapter) counts as a software-class adapter.</li>
 *     <li>Every device that was created for a probe and is not chosen is closed, and every adapter wrapper that is not
 *     chosen is closed, on every path including exceptions. The caller owns the two objects of the {@link Selection}.</li>
 *     <li>Timestamp frequency comes from a throw-away DIRECT queue created and closed here, so the returned
 *     {@link DeviceCaps} is complete before {@link DeviceInfoBuilder#build}.</li>
 * </ul>
 *
 * <p>Threading: the creating (render) thread; cold path, so confined arenas are fine here.
 */
public final class AdapterSelector {
    /** The chosen adapter, its device and the probed facts. The caller owns (and closes) {@code adapter} and {@code device}. */
    public record Selection(DxgiAdapter adapter, D3D12Device device, DeviceCaps caps) {
    }

    /** Test seams (see {@link #setHooksForTests}); null in production. */
    private static volatile @Nullable UnaryOperator<DxgiAdapter.Desc> descriptionHook;
    private static volatile @Nullable Predicate<String> missingCapabilityHook;

    /** Feature levels asked for when looking for the highest one the device supports, highest first. */
    private static final int[] FEATURE_LEVELS = {
            Enums.FeatureLevel._12_1, Enums.FeatureLevel._12_0, Enums.FeatureLevel._11_1, Enums.FeatureLevel._11_0
    };

    private AdapterSelector() {
    }

    /**
     * Test seams for the failure paths that a real machine rarely produces: {@code description} may rewrite the
     * {@code DXGI_ADAPTER_DESC1} of every adapter (to make it look like a software renderer, say) and
     * {@code missingCapability} may declare any capability missing by its name ({@code "D3D_FEATURE_LEVEL_11_0"},
     * {@code "ROOT_SIGNATURE_1_0"}, {@code "RESOURCE_BINDING_TIER_1"}, {@code "TYPED_BUFFER_SRV_R32_SINT"}, ...). Pass
     * nulls to remove them. Production code never sets them.
     */
    public static void setHooksForTests(@Nullable UnaryOperator<DxgiAdapter.Desc> description, @Nullable Predicate<String> missingCapability) {
        descriptionHook = description;
        missingCapabilityHook = missingCapability;
    }

    private static boolean forcedMissing(String capability) {
        Predicate<String> hook = missingCapabilityHook;

        return hook != null && hook.test(capability);
    }

    public static Selection select(DxgiFactory factory, DeviceOptions options) throws BackendCreationException {
        List<DxgiAdapter> adapters;

        try {
            adapters = factory.enumAdapters(Enums.GpuPreference.HIGH_PERFORMANCE);
        } catch (RuntimeException e) {
            throw failure("Cannot enumerate the graphics adapters: " + describe(e),
                    BackendCreationException.Reason.PLATFORM_ERROR, List.of(), e);
        }

        Candidate chosen = null;
        DxgiAdapter keep = null;

        try {
            if (adapters.isEmpty()) {
                throw failure("No graphics adapter was found", BackendCreationException.Reason.OTHER,
                        List.of("DXGI_ADAPTER"), null);
            }

            String filter = normaliseFilter(options.adapterNameFilter());
            List<Candidate> failures = new ArrayList<>();

            if (filter != null) {
                chosen = pick(adapters, filter, true, factory, failures, new Candidate[1]);

                if (chosen == null) {
                    D3D12Log.warn("No suitable Direct3D 12 adapter matches the filter '{}' (sodium.d3d12.adapter); using the default adapter selection", filter);
                    failures.clear();
                }
            }

            if (chosen == null) {
                Candidate[] disabledSoftware = new Candidate[1];

                chosen = pick(adapters, null, options.softwareFallback(), factory, failures, disabledSoftware);

                if (chosen == null && disabledSoftware[0] != null) {
                    // a suitable software adapter exists but the user turned software rendering off
                    failures.add(0, Candidate.rejected(disabledSoftware[0].name,
                            "only a software renderer is available and software rendering is disabled (sodium.d3d12.softwareFallback=false)",
                            List.of("HARDWARE_ADAPTER")));
                    disabledSoftware[0].close();
                }
            }

            if (chosen == null) {
                if (failures.isEmpty()) {
                    throw failure("No suitable graphics adapter was found", BackendCreationException.Reason.OTHER,
                            List.of("DXGI_ADAPTER"), null);
                }

                Candidate first = failures.get(0);

                for (Candidate failed : failures) {
                    D3D12Log.warn("Direct3D 12 adapter rejected: {}", failed.reason);
                }

                throw failure("No suitable Direct3D 12 adapter: " + first.reason, BackendCreationException.Reason.OTHER,
                        first.missing, null);
            }

            for (Candidate failed : failures) {
                D3D12Log.warn("Direct3D 12 adapter rejected: {}", failed.reason);
            }

            DeviceCaps caps = chosen.caps;

            D3D12Log.info("Selected Direct3D 12 adapter '{}' (vendor 0x{}, feature level {}, binding tier {}{}{})", chosen.name,
                    Integer.toHexString(caps.vendorId()), featureLevelName(caps.featureLevel()), caps.resourceBindingTier(),
                    caps.uma() ? ", UMA" : "", caps.softwareAdapter() ? ", software" : "");

            Selection selection = new Selection(chosen.adapter, chosen.device, caps);

            // ownership of the adapter and the device moved to the selection
            keep = chosen.adapter;
            chosen.device = null;
            chosen = null;

            return selection;
        } finally {
            closeCandidate(chosen);

            for (DxgiAdapter adapter : adapters) {
                if (adapter != keep) {
                    closeQuietly(adapter);
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // ranking
    // ------------------------------------------------------------------------------------------------------------

    /**
     * The decision D18 as pure logic: candidates are offered in enumeration order; the first suitable hardware adapter
     * wins and is replaced by the first suitable <em>discrete</em> (non-UMA) one; software adapters only count when no
     * hardware adapter is suitable. {@code T} is whatever owns the native objects of a candidate; every candidate that
     * loses is passed to {@code release} immediately.
     */
    static final class Ranker<T> {
        private final Consumer<T> release;
        private @Nullable T hardware;
        private @Nullable T software;

        Ranker(Consumer<T> release) {
            this.release = release;
        }

        /**
         * @return true if the scan can stop because nothing offered later can beat the current best (a discrete hardware
         * adapter was found)
         */
        boolean offer(T candidate, boolean softwareAdapter, boolean uma) {
            if (softwareAdapter) {
                if (this.software == null) {
                    this.software = candidate;
                } else {
                    this.release.accept(candidate);
                }

                return false;
            }

            if (this.hardware == null) {
                this.hardware = candidate;

                return !uma;
            }

            if (!uma) {
                // upgrade to the first suitable discrete adapter
                this.release.accept(this.hardware);
                this.hardware = candidate;

                return true;
            }

            this.release.accept(candidate);

            return false;
        }

        /** @return the winner (ownership moves to the caller), or null if there is none under {@code allowSoftware} */
        @Nullable T takeWinner(boolean allowSoftware) {
            T winner;

            if (this.hardware != null) {
                winner = this.hardware;
                this.hardware = null;
            } else if (this.software != null && allowSoftware) {
                winner = this.software;
                this.software = null;
            } else {
                winner = null;
            }

            return winner;
        }

        /** @return the suitable software adapter that was not taken (ownership moves to the caller), or null */
        @Nullable T takeSoftware() {
            T taken = this.software;

            this.software = null;

            return taken;
        }

        /** Releases whatever was neither taken nor released yet. */
        void releaseAll() {
            T h = this.hardware;
            T s = this.software;

            this.hardware = null;
            this.software = null;

            if (h != null) {
                this.release.accept(h);
            }

            if (s != null) {
                this.release.accept(s);
            }
        }
    }

    /**
     * @param software receives the best suitable software adapter that was <em>not</em> returned because
     *                 {@code allowSoftware} is false (the caller closes it); untouched otherwise
     */
    private static @Nullable Candidate pick(List<DxgiAdapter> adapters, @Nullable String filter, boolean allowSoftware,
                                            DxgiFactory factory, List<Candidate> failures, Candidate[] software) {
        Ranker<Candidate> ranker = new Ranker<>(Candidate::dropDevice);

        try {
            for (DxgiAdapter adapter : adapters) {
                if (filter != null && !matches(adapter, filter)) {
                    continue;
                }

                Candidate candidate = evaluate(adapter, factory);

                if (candidate.reason != null) {
                    failures.add(candidate);
                    candidate.dropDevice();
                    continue;
                }

                if (filter != null) {
                    // an explicitly requested adapter wins whatever its rank
                    return candidate;
                }

                if (ranker.offer(candidate, candidate.caps.softwareAdapter(), candidate.caps.uma())) {
                    break;
                }
            }

            Candidate winner = ranker.takeWinner(allowSoftware);

            if (winner == null) {
                // nothing is selected: hand the (refused) software adapter to the caller for the error message
                software[0] = ranker.takeSoftware();
            }

            return winner;
        } finally {
            ranker.releaseAll();
        }
    }

    private static void closeCandidate(@Nullable Candidate candidate) {
        if (candidate != null) {
            candidate.close();
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // one adapter
    // ------------------------------------------------------------------------------------------------------------

    /** One adapter under evaluation; holds the device while the adapter is a candidate. */
    private static final class Candidate {
        final @Nullable DxgiAdapter adapter;
        final String name;
        @Nullable D3D12Device device;
        @Nullable DeviceCaps caps;
        @Nullable String reason;
        List<String> missing = List.of();

        Candidate(@Nullable DxgiAdapter adapter, String name) {
            this.adapter = adapter;
            this.name = name;
        }

        static Candidate rejected(String name, String reason, List<String> missing) {
            Candidate candidate = new Candidate(null, name);

            candidate.reason = name + ": " + reason;
            candidate.missing = missing;

            return candidate;
        }

        /** Releases the probe device but not the adapter (the selection loop closes all adapters at the end). */
        void dropDevice() {
            D3D12Device d = this.device;

            this.device = null;

            if (d != null) {
                try {
                    d.close();
                } catch (RuntimeException e) {
                    D3D12Log.warn("Failed to release the probe device of adapter '{}': {}", this.name, e.toString());
                }
            }
        }

        void close() {
            this.dropDevice();
        }
    }

    private static Candidate evaluate(DxgiAdapter adapter, DxgiFactory factory) {
        DxgiAdapter.Desc desc;

        try {
            UnaryOperator<DxgiAdapter.Desc> hook = descriptionHook;

            desc = hook != null ? hook.apply(adapter.desc()) : adapter.desc();
        } catch (RuntimeException e) {
            return Candidate.rejected("<unknown adapter>", "cannot read the adapter description: " + describe(e),
                    List.of("DXGI_ADAPTER_DESC"));
        }

        Candidate candidate = new Candidate(adapter, desc.name());
        D3D12Device device = null;

        try {
            int support = forcedMissing("D3D_FEATURE_LEVEL_11_0") ? Hr.E_FAIL : D3D12.checkDeviceSupport(adapter, Enums.FeatureLevel._11_0);

            if (support < 0) {
                return reject(candidate, "Direct3D feature level 11_0 is not supported (HRESULT " + hex(support) + ")",
                        List.of("D3D_FEATURE_LEVEL_11_0"));
            }

            try {
                device = D3D12.createDevice(adapter, Enums.FeatureLevel._11_0);
            } catch (D3D12Exception e) {
                return reject(candidate, "cannot create a Direct3D 12 device (" + describe(e) + ")",
                        List.of("D3D_FEATURE_LEVEL_11_0"));
            }

            List<String> missing = new ArrayList<>();
            StringBuilder reasons = new StringBuilder();

            int rootSignature = device.checkRootSignatureVersion();

            if (rootSignature < Enums.RootSignatureVersion._1_0 || forcedMissing("ROOT_SIGNATURE_1_0")) {
                missing.add("ROOT_SIGNATURE_1_0");
                reasons.append("root signature 1.0 is not supported; ");
            }

            D3D12Device.FeatureOptions features = device.checkOptions();

            if (features.resourceBindingTier() < Enums.ResourceBindingTier._1 || forcedMissing("RESOURCE_BINDING_TIER_1")) {
                missing.add("RESOURCE_BINDING_TIER_1");
                reasons.append("resource binding tier 1 is not supported; ");
            }

            int bufferSrv = Enums.FormatSupport1.BUFFER | Enums.FormatSupport1.SHADER_LOAD;
            int renderTarget = Enums.FormatSupport1.RENDER_TARGET | Enums.FormatSupport1.BLENDABLE;

            requireFormat(device, Enums.Format.R8_SINT, bufferSrv, "TYPED_BUFFER_SRV_R8_SINT", "typed buffer view of R8_SINT", missing, reasons);
            requireFormat(device, Enums.Format.R32_SINT, bufferSrv, "TYPED_BUFFER_SRV_R32_SINT", "typed buffer view of R32_SINT", missing, reasons);
            requireFormat(device, Enums.Format.R16G16B16A16_FLOAT, renderTarget, "BLENDABLE_RENDER_TARGET_R16G16B16A16_FLOAT", "blendable R16G16B16A16_FLOAT render target", missing, reasons);
            requireFormat(device, Enums.Format.R32G32B32A32_FLOAT, renderTarget, "BLENDABLE_RENDER_TARGET_R32G32B32A32_FLOAT", "blendable R32G32B32A32_FLOAT render target", missing, reasons);
            requireFormat(device, Enums.Format.R8G8B8A8_UNORM, renderTarget, "BLENDABLE_RENDER_TARGET_R8G8B8A8_UNORM", "blendable R8G8B8A8_UNORM render target", missing, reasons);
            requireFormat(device, Enums.Format.D32_FLOAT, Enums.FormatSupport1.DEPTH_STENCIL, "DEPTH_STENCIL_D32_FLOAT", "D32_FLOAT depth buffer", missing, reasons);

            if (!missing.isEmpty()) {
                String text = reasons.toString();

                return reject(candidate, "missing " + text.substring(0, text.length() - 2), missing);
            }

            boolean uma = false;

            try {
                uma = device.checkArchitecture(0).uma();
            } catch (D3D12Exception e) {
                D3D12Log.debug("D3D12_FEATURE_ARCHITECTURE failed on '{}': {}", candidate.name, e.getMessage());
            }

            long timestampFrequency;

            try (D3D12CommandQueue queue = device.createCommandQueue(Enums.CommandListType.DIRECT)) {
                timestampFrequency = queue.getTimestampFrequency();
            }

            boolean tearing = false;

            try {
                tearing = factory.isTearingSupported();
            } catch (RuntimeException e) {
                D3D12Log.debug("Tearing support query failed: {}", e.getMessage());
            }

            boolean software = desc.isSoftware() || desc.isRemote() || desc.vendorId() == DxgiAdapter.Desc.VENDOR_MICROSOFT;

            candidate.caps = new DeviceCaps(highestFeatureLevel(device), features.resourceBindingTier(),
                    rootSignature >= Enums.RootSignatureVersion._1_1 ? 2 : 1, uma, tearing, timestampFrequency, software,
                    desc.name(), desc.vendorId(), desc.deviceId(), desc.dedicatedVideoMemory());
            candidate.device = device;
            device = null;

            return candidate;
        } catch (D3D12Exception e) {
            return reject(candidate, "capability query failed (" + describe(e) + ")", List.of("D3D_FEATURE_LEVEL_11_0"));
        } finally {
            if (device != null) {
                try {
                    device.close();
                } catch (RuntimeException ignored) {
                    // the probe device is gone either way
                }
            }
        }
    }

    private static Candidate reject(Candidate candidate, String reason, List<String> missing) {
        candidate.reason = candidate.name + ": " + reason;
        candidate.missing = List.copyOf(missing);

        return candidate;
    }

    private static void requireFormat(D3D12Device device, int format, int flags, String capability, String text,
                                      List<String> missing, StringBuilder reasons) {
        if (forcedMissing(capability) || !device.checkFormatSupport(format).supports1(flags)) {
            missing.add(capability);
            reasons.append(text).append("; ");
        }
    }

    /** @return the highest {@code D3D_FEATURE_LEVEL} the device supports, at least 11_0 */
    private static int highestFeatureLevel(D3D12Device device) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment levels = arena.allocate(ValueLayout.JAVA_INT, FEATURE_LEVELS.length);

            for (int i = 0; i < FEATURE_LEVELS.length; i++) {
                levels.setAtIndex(ValueLayout.JAVA_INT, i, FEATURE_LEVELS[i]);
            }

            MemorySegment data = D3D12FeatureDataFeatureLevels.allocate(arena);
            D3D12FeatureDataFeatureLevels.setNumFeatureLevels(data, FEATURE_LEVELS.length);
            D3D12FeatureDataFeatureLevels.setPFeatureLevelsRequested(data, levels);

            if (device.checkFeatureSupport(Enums.Feature.FEATURE_LEVELS, data, (int) D3D12FeatureDataFeatureLevels.SIZE) >= 0) {
                return Math.max(Enums.FeatureLevel._11_0, D3D12FeatureDataFeatureLevels.getMaxSupportedFeatureLevel(data));
            }
        } catch (RuntimeException e) {
            D3D12Log.debug("D3D12_FEATURE_FEATURE_LEVELS failed: {}", e.getMessage());
        }

        return Enums.FeatureLevel._11_0;
    }

    // ------------------------------------------------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------------------------------------------------

    private static @Nullable String normaliseFilter(@Nullable String filter) {
        if (filter == null) {
            return null;
        }

        String trimmed = filter.trim();

        return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    private static boolean matches(DxgiAdapter adapter, String lowerCaseFilter) {
        try {
            return adapter.desc().name().toLowerCase(Locale.ROOT).contains(lowerCaseFilter);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void closeQuietly(@Nullable DxgiAdapter adapter) {
        if (adapter != null) {
            try {
                adapter.close();
            } catch (RuntimeException e) {
                D3D12Log.debug("Failed to release an adapter wrapper: {}", e.getMessage());
            }
        }
    }

    private static String hex(int hresult) {
        return String.format("0x%08X", hresult);
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();

        return message == null || message.isEmpty() ? t.getClass().getSimpleName() : message;
    }

    private static String featureLevelName(int level) {
        return switch (level) {
            case Enums.FeatureLevel._12_1 -> "12_1";
            case Enums.FeatureLevel._12_0 -> "12_0";
            case Enums.FeatureLevel._11_1 -> "11_1";
            case Enums.FeatureLevel._11_0 -> "11_0";
            default -> "0x" + Integer.toHexString(level);
        };
    }

    private static BackendCreationException failure(String message, BackendCreationException.Reason reason,
                                                    List<String> missing, @Nullable Throwable cause) {
        BackendCreationException exception = new BackendCreationException(message, reason, missing);

        if (cause != null) {
            exception.initCause(cause);
        }

        return exception;
    }
}
