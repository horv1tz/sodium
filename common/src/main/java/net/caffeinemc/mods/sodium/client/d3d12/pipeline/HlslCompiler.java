// Owner: WP3 (pipelines + render pass)
package net.caffeinemc.mods.sodium.client.d3d12.pipeline;

import net.caffeinemc.mods.sodium.client.d3d12.backend.DeviceOptions;

/**
 * HLSL text -> DXBC through {@code d3dcompiler_47.dll} ({@code D3DCompiler.compile}), thread-safe, with the
 * {@link ShaderCache}.
 *
 * <ul>
 *     <li>Targets {@code vs_5_0} / {@code ps_5_0}, entry {@code main}, no macros, no includes, no matrix pack flags
 *     (SPIRV-Cross writes explicit {@code row_major}/{@code column_major}); flags
 *     {@code D3DCOMPILE_OPTIMIZATION_LEVEL<n>} with {@code n = options.fxcOptimizationLevel()} (default 1: FXC's own
 *     default; the driver re-optimises DXBC and O3 dominates cold-start time and sometimes fails to unroll) or
 *     {@code DEBUG | SKIP_OPTIMIZATION} when labels/validation are on. No {@code WARNINGS_ARE_ERRORS}.</li>
 *     <li><b>Retry once</b> with {@code SKIP_OPTIMIZATION} when the compiler <em>rejects</em> the text
 *     ({@code D3DShaderCompileException}): FXC optimiser "X" errors must not fail a required pipeline.</li>
 *     <li>A {@code Semaphore(options.compileParallelism())} bounds concurrent compiles; warnings are logged with the
 *     pipeline name; the blob magic {@code DXBC} is verified.</li>
 *     <li>Cache: {@code ShaderCache.key(hlsl, profile, entryPoint, flags, compilerIdentity())} before compiling, store after.</li>
 * </ul>
 * Failures propagate as {@code D3DShaderCompileException} (message + compiler output) to
 * {@code D3D12PipelineCompiler}, which logs the HLSL and returns {@code Pending.NULL}.
 */
public final class HlslCompiler {
    private final DeviceOptions options;
    private final ShaderCache cache;

    public HlslCompiler(DeviceOptions options, ShaderCache cache) {
        this.options = options;
        this.cache = cache;
    }

    /**
     * @param entryPoint {@code "main"} for translated game shaders, the entry name for built-in pipelines
     * @param profile    {@code "vs_5_0"} or {@code "ps_5_0"}
     * @param sourceName used in diagnostics, e.g. {@code "<pipeline>/vertex"}
     * @return the DXBC bytes
     */
    public byte[] compile(String hlsl, String entryPoint, String profile, String sourceName) {
        throw new UnsupportedOperationException("WP3: cache lookup, semaphore, D3DCompiler.compile, retry with SKIP_OPTIMIZATION, magic check");
    }

    /** @return identity of the installed compiler for cache keys: {@code d3dcompiler_47.dll} size + modification time */
    public String compilerIdentity() {
        throw new UnsupportedOperationException("WP3");
    }

    DeviceOptions options() {
        return this.options;
    }

    ShaderCache cache() {
        return this.cache;
    }
}
