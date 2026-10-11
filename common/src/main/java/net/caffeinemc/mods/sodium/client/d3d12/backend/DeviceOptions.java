// Owner: WP1 (device core) - fully implemented value type
package net.caffeinemc.mods.sodium.client.d3d12.backend;

import com.mojang.renderpearl.api.device.GpuDebugOptions;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;

/**
 * Every tunable of the backend, read once at device creation (system properties are never re-read).
 *
 * <p>System properties (all optional):
 * <ul>
 *     <li>{@code sodium.d3d12.strict=true}: missing uniforms / vertex buffers / indices, invalid pipelines in a pass and
 *     other "tolerated" caller bugs throw {@link IllegalStateException} instead of substituting and warning once;
 *     debug-layer errors fail the next {@code submit()}. Default off in the game, on in tests.</li>
 *     <li>{@code sodium.d3d12.gbv=true}: GPU-based validation (very slow; needs {@code useValidationLayers}).</li>
 *     <li>{@code sodium.d3d12.softwareFallback=false}: never use a software adapter (default: used as a last resort).</li>
 *     <li>{@code sodium.d3d12.adapter=<substring>}: pick the first adapter whose description contains the text.</li>
 *     <li>{@code sodium.d3d12.optimizedClear=false}: create render targets without an optimised clear value.</li>
 *     <li>{@code sodium.d3d12.writeToBufferIsSlow=true}: report the hint that steers vanilla to its persistently
 *     mapped staging buffer (A/B measurements only).</li>
 *     <li>{@code sodium.d3d12.fxcLevel=0..3}: {@code D3DCOMPILE_OPTIMIZATION_LEVELn} (default 1).</li>
 *     <li>{@code sodium.d3d12.compileThreads=n}: parallel {@code D3DCompile} calls (default min(4, cores)).</li>
 *     <li>{@code sodium.d3d12.shaderCache=off|<dir>}: DXBC disk cache; {@code off} disables the disk part.</li>
 *     <li>{@code sodium.d3d12.waitableSwapChain=true}: experimental frame-latency waitable object (default off).</li>
 * </ul>
 *
 * @param logLevel             {@code GpuDebugOptions.logLevel}: 0 none, 1 error, 2 +warning, 3 +info, 4 +message
 * @param useLabels            {@code SetName} / PIX events
 * @param useValidationLayers  enable the D3D12 debug layer before device creation
 * @param gpuBasedValidation   additionally GPU-based validation
 * @param strict               see above
 * @param softwareFallback     allow a software adapter if no hardware adapter is usable
 * @param adapterNameFilter    adapter selection override, nullable
 * @param optimizedClearValues render targets carry optimised clear values (colour 0,0,0,0 / depth 0.0)
 * @param writeToBufferIsSlow  value of {@code HintsAndWorkarounds.writeToBufferIsSlow}
 * @param fxcOptimizationLevel 0..3
 * @param compileParallelism   maximum concurrent {@code D3DCompile} calls
 * @param shaderCacheDir       disk cache directory, null = memory cache only
 * @param waitableSwapChain    use {@code DXGI_SWAP_CHAIN_FLAG_FRAME_LATENCY_WAITABLE_OBJECT}
 * @param frameWaitTimeoutNs   cap of the wait for the previous frame in {@code submit()} (default 5 s)
 */
public record DeviceOptions(
        int logLevel,
        boolean useLabels,
        boolean useValidationLayers,
        boolean gpuBasedValidation,
        boolean strict,
        boolean softwareFallback,
        @Nullable String adapterNameFilter,
        boolean optimizedClearValues,
        boolean writeToBufferIsSlow,
        int fxcOptimizationLevel,
        int compileParallelism,
        @Nullable Path shaderCacheDir,
        boolean waitableSwapChain,
        long frameWaitTimeoutNs) {

    public static final long DEFAULT_FRAME_WAIT_TIMEOUT_NS = 5_000_000_000L;

    /** Options for the game: Minecraft's debug options plus the system properties. */
    public static DeviceOptions from(GpuDebugOptions debug) {
        String cache = System.getProperty("sodium.d3d12.shaderCache");
        Path cacheDir = cache == null || cache.equalsIgnoreCase("off") || cache.isBlank() ? null : Path.of(cache);

        return new DeviceOptions(
                debug.logLevel(),
                debug.useLabels(),
                debug.useValidationLayers(),
                Boolean.getBoolean("sodium.d3d12.gbv"),
                Boolean.getBoolean("sodium.d3d12.strict"),
                !"false".equalsIgnoreCase(System.getProperty("sodium.d3d12.softwareFallback")),
                System.getProperty("sodium.d3d12.adapter"),
                !"false".equalsIgnoreCase(System.getProperty("sodium.d3d12.optimizedClear")),
                Boolean.getBoolean("sodium.d3d12.writeToBufferIsSlow"),
                Math.max(0, Math.min(3, Integer.getInteger("sodium.d3d12.fxcLevel", 1))),
                Math.max(1, Integer.getInteger("sodium.d3d12.compileThreads", Math.min(4, Runtime.getRuntime().availableProcessors()))),
                cacheDir,
                Boolean.getBoolean("sodium.d3d12.waitableSwapChain"),
                DEFAULT_FRAME_WAIT_TIMEOUT_NS);
    }

    /** Options for the test harness: strict, labels on, software adapter allowed, no disk cache. */
    public static DeviceOptions forTests() {
        return new DeviceOptions(
                0, true, false, false, true, true, System.getProperty("sodium.d3d12.adapter"), true, false, 1,
                Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors())), null, false,
                DEFAULT_FRAME_WAIT_TIMEOUT_NS);
    }

    /** @return a copy with another shader cache directory (the Sodium integration passes its game directory here) */
    public DeviceOptions withShaderCacheDir(@Nullable Path dir) {
        return new DeviceOptions(this.logLevel, this.useLabels, this.useValidationLayers, this.gpuBasedValidation,
                this.strict, this.softwareFallback, this.adapterNameFilter, this.optimizedClearValues,
                this.writeToBufferIsSlow, this.fxcOptimizationLevel, this.compileParallelism, dir,
                this.waitableSwapChain, this.frameWaitTimeoutNs);
    }

    /** @return a copy with another strictness (tests flip it per test) */
    public DeviceOptions withStrict(boolean strict) {
        return new DeviceOptions(this.logLevel, this.useLabels, this.useValidationLayers, this.gpuBasedValidation,
                strict, this.softwareFallback, this.adapterNameFilter, this.optimizedClearValues,
                this.writeToBufferIsSlow, this.fxcOptimizationLevel, this.compileParallelism, this.shaderCacheDir,
                this.waitableSwapChain, this.frameWaitTimeoutNs);
    }

    /** @return a copy that enables the D3D12 debug layer (and labels, as Vulkan does) */
    public DeviceOptions withValidation(boolean gpuBased) {
        return new DeviceOptions(this.logLevel > 0 ? this.logLevel : 2, true, true, gpuBased,
                this.strict, this.softwareFallback, this.adapterNameFilter, this.optimizedClearValues,
                this.writeToBufferIsSlow, this.fxcOptimizationLevel, this.compileParallelism, this.shaderCacheDir,
                this.waitableSwapChain, this.frameWaitTimeoutNs);
    }

    /** {@code GpuDeviceBackend.isDebuggingEnabled()}: gates label evaluation in the frontend. */
    public boolean debuggingEnabled() {
        return this.logLevel > 0 || this.useLabels || this.useValidationLayers;
    }
}
