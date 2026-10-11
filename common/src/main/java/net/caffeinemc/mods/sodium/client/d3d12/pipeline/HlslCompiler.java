// Owner: WP3 (pipelines + render pass) - implemented
package net.caffeinemc.mods.sodium.client.d3d12.pipeline;

import net.caffeinemc.mods.sodium.client.d3d12.backend.DeviceOptions;
import net.caffeinemc.mods.sodium.client.d3d12.core.D3D12Log;
import net.caffeinemc.mods.sodium.client.d3d12.interop.Win32Libraries;
import net.caffeinemc.mods.sodium.client.d3d12.interop.compiler.D3DCompiler;
import net.caffeinemc.mods.sodium.client.d3d12.interop.compiler.D3DShaderCompileException;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Semaphore;

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
    private static final String UNKNOWN_IDENTITY = "d3dcompiler_47:unknown";

    /** The native step, replaceable in tests ({@code D3DCompiler.compile} in production). */
    @FunctionalInterface
    interface Backend {
        /**
         * @throws D3DShaderCompileException when the compiler rejects the text
         */
        Output compile(String hlsl, String sourceName, String entryPoint, String profile, int flags);
    }

    /** DXBC bytes plus the compiler's warning text. */
    record Output(byte[] dxbc, String warnings) {
    }

    private final DeviceOptions options;
    private final ShaderCache cache;
    private final Backend backend;
    private final Semaphore parallelism;
    private volatile String identity;

    public HlslCompiler(DeviceOptions options, ShaderCache cache) {
        this(options, cache, HlslCompiler::compileNative);
    }

    HlslCompiler(DeviceOptions options, ShaderCache cache, Backend backend) {
        this.options = options;
        this.cache = cache;
        this.backend = backend;
        this.parallelism = new Semaphore(Math.max(1, options.compileParallelism()), true);
    }

    private static Output compileNative(String hlsl, String sourceName, String entryPoint, String profile, int flags) {
        try (D3DCompiler.Result result = D3DCompiler.compile(hlsl, sourceName, entryPoint, profile, flags, 0)) {
            return new Output(result.toByteArray(), result.warnings());
        }
    }

    /**
     * @param entryPoint {@code "main"} for translated game shaders, the entry name for built-in pipelines
     * @param profile    {@code "vs_5_0"} or {@code "ps_5_0"}
     * @param sourceName used in diagnostics, e.g. {@code "<pipeline>/vertex"}
     * @return the DXBC bytes
     * @throws D3DShaderCompileException when the compiler rejects the text even without optimisation
     */
    public byte[] compile(String hlsl, String entryPoint, String profile, String sourceName) {
        int flags = this.baseFlags();
        String key = ShaderCache.key(hlsl, profile, entryPoint, flags, this.compilerIdentity());
        byte[] cached = this.cache.get(key);

        if (cached != null) {
            if (hasMagic(cached)) {
                return cached;
            }

            D3D12Log.warn("Ignoring a cached shader of {} that is not a DXBC container", sourceName);
        }

        this.parallelism.acquireUninterruptibly();

        Output output;

        try {
            try {
                output = this.backend.compile(hlsl, sourceName, entryPoint, profile, flags);
            } catch (D3DShaderCompileException first) {
                if ((flags & D3DCompiler.Flags.SKIP_OPTIMIZATION) != 0) {
                    throw first;
                }

                // FXC's optimiser reports "X" errors (failed unrolling, ...) that must not fail a required pipeline
                D3D12Log.warn("The HLSL compiler rejected {}; retrying without optimisation: {}", sourceName, firstLine(first.getCompilerOutput()));

                output = this.backend.compile(hlsl, sourceName, entryPoint, profile, D3DCompiler.Flags.SKIP_OPTIMIZATION);
            }
        } finally {
            this.parallelism.release();
        }

        if (!hasMagic(output.dxbc())) {
            throw new IllegalStateException("The HLSL compiler returned something that is not a DXBC container for " + sourceName);
        }

        if (!output.warnings().isBlank()) {
            if (this.options.logLevel() >= 2) {
                D3D12Log.warn("HLSL compiler warnings for {}:\n{}", sourceName, output.warnings().strip());
            } else {
                D3D12Log.debug("HLSL compiler warnings for {}:\n{}", sourceName, output.warnings().strip());
            }
        }

        // stored under the key of the FIRST attempt: a later run asks the same question and must not retry again
        this.cache.put(key, output.dxbc());

        return output.dxbc();
    }

    /** @return the {@code D3DCOMPILE_*} flags of the first attempt */
    int baseFlags() {
        if (this.options.useLabels() || this.options.useValidationLayers()) {
            return D3DCompiler.Flags.DEBUG | D3DCompiler.Flags.SKIP_OPTIMIZATION;
        }

        return switch (this.options.fxcOptimizationLevel()) {
            case 0 -> D3DCompiler.Flags.OPTIMIZATION_LEVEL0;
            case 2 -> D3DCompiler.Flags.OPTIMIZATION_LEVEL2;
            case 3 -> D3DCompiler.Flags.OPTIMIZATION_LEVEL3;
            default -> D3DCompiler.Flags.OPTIMIZATION_LEVEL1;
        };
    }

    /**
     * @return identity of the installed compiler for cache keys: {@code d3dcompiler_47.dll} size + modification time, or
     * a constant when the library cannot be located (not Windows, library not loadable): a constant is safe because
     * without the library nothing can be compiled and every hit comes from a cache written by a machine that had it
     */
    public String compilerIdentity() {
        String known = this.identity;

        if (known != null) {
            return known;
        }

        String resolved = resolveIdentity();

        // a compiler that could not be located yet (not loaded) must not freeze the fallback
        if (!resolved.equals(UNKNOWN_IDENTITY)) {
            this.identity = resolved;
        }

        return resolved;
    }

    private static String resolveIdentity() {
        try {
            if (!Win32Libraries.isWindows() || !D3DCompiler.isAvailable()) {
                return UNKNOWN_IDENTITY;
            }

            Path dll = locateCompiler();

            if (dll == null || !Files.isRegularFile(dll)) {
                return UNKNOWN_IDENTITY;
            }

            return "d3dcompiler_47:" + Files.size(dll) + ":" + Files.getLastModifiedTime(dll).toMillis();
        } catch (IOException | RuntimeException e) {
            D3D12Log.debug("Cannot identify d3dcompiler_47.dll: {}", e.toString());

            return UNKNOWN_IDENTITY;
        }
    }

    /** {@code GetModuleHandleW} + {@code GetModuleFileNameW} of the library the process really loaded, else System32. */
    private static Path locateCompiler() {
        try (Arena arena = Arena.ofConfined()) {
            MethodHandle moduleHandle = Win32Libraries.function(Win32Libraries.kernel32(), "GetModuleHandleW",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MethodHandle moduleFileName = Win32Libraries.function(Win32Libraries.kernel32(), "GetModuleFileNameW",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

            MemorySegment name = arena.allocateFrom("d3dcompiler_47.dll", StandardCharsets.UTF_16LE);
            MemorySegment module = (MemorySegment) moduleHandle.invokeExact(name);

            if (module.address() != 0L) {
                MemorySegment buffer = arena.allocate(2L * 1024);
                int length = (int) moduleFileName.invokeExact(module, buffer, 1024);

                if (length > 0 && length < 1024) {
                    return Path.of(new String(buffer.asSlice(0, 2L * length).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_16LE));
                }
            }
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError error) {
                throw error;
            }

            D3D12Log.debug("GetModuleFileNameW failed: {}", t.toString());
        }

        String root = System.getenv("SystemRoot");

        return root == null ? null : Path.of(root, "System32", "d3dcompiler_47.dll");
    }

    private static boolean hasMagic(byte[] dxbc) {
        return dxbc.length >= 4 && dxbc[0] == 'D' && dxbc[1] == 'X' && dxbc[2] == 'B' && dxbc[3] == 'C';
    }

    private static String firstLine(String text) {
        int end = text.indexOf('\n');

        return end < 0 ? text.strip() : text.substring(0, end).strip();
    }

    DeviceOptions options() {
        return this.options;
    }

    ShaderCache cache() {
        return this.cache;
    }
}
