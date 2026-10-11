package net.caffeinemc.mods.sodium.client.d3d12.interop.compiler;

import net.caffeinemc.mods.sodium.client.d3d12.interop.Com;
import net.caffeinemc.mods.sodium.client.d3d12.interop.D3D12Exception;
import net.caffeinemc.mods.sodium.client.d3d12.interop.Hr;
import net.caffeinemc.mods.sodium.client.d3d12.interop.Win32Libraries;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Defines;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Static entry points of {@code d3dcompiler_47.dll}: the HLSL to DXBC compiler and the DXBC disassembler.
 *
 * <p>DXBC (shader model 5.1 and below) is what the Direct3D 12 runtime accepts without the DXC compiler. The library
 * is loaded on first use, so loading this class is safe on every platform.
 *
 * <p>Thread safety: the compiler is stateless; all methods may be called concurrently from any thread.
 */
public final class D3DCompiler {
    /**
     * Linked on first use (not in a static initialiser), so that a failure to load {@code d3dcompiler_47.dll}
     * surfaces as a {@link D3D12Exception} at the call site every time instead of as a class initialisation error.
     */
    private static volatile MethodHandle d3dCompile;
    private static volatile MethodHandle d3dDisassemble;

    private static final FunctionDescriptor D3D_COMPILE_DESCRIPTOR = FunctionDescriptor.of(JAVA_INT,
            ADDRESS, JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS);
    private static final FunctionDescriptor D3D_DISASSEMBLE_DESCRIPTOR = FunctionDescriptor.of(JAVA_INT,
            ADDRESS, JAVA_LONG, JAVA_INT, ADDRESS, ADDRESS);

    /** Size of a {@code D3D_SHADER_MACRO}: two pointers (Name, Definition). */
    private static final long MACRO_SIZE = 16;

    private D3DCompiler() {
    }

    /**
     * {@code D3DCOMPILE_*} flags for the {@code flags1} parameter of {@code compile}. The values come from
     * {@link Defines}; see the Microsoft documentation of {@code D3DCompile} for their meaning. Combine with
     * {@code |}.
     */
    public static final class Flags {
        public static final int DEBUG = Defines.D3DCOMPILE_DEBUG;
        public static final int SKIP_VALIDATION = Defines.D3DCOMPILE_SKIP_VALIDATION;
        public static final int SKIP_OPTIMIZATION = Defines.D3DCOMPILE_SKIP_OPTIMIZATION;
        public static final int PACK_MATRIX_ROW_MAJOR = Defines.D3DCOMPILE_PACK_MATRIX_ROW_MAJOR;
        public static final int PACK_MATRIX_COLUMN_MAJOR = Defines.D3DCOMPILE_PACK_MATRIX_COLUMN_MAJOR;
        public static final int PARTIAL_PRECISION = Defines.D3DCOMPILE_PARTIAL_PRECISION;
        public static final int FORCE_VS_SOFTWARE_NO_OPT = Defines.D3DCOMPILE_FORCE_VS_SOFTWARE_NO_OPT;
        public static final int FORCE_PS_SOFTWARE_NO_OPT = Defines.D3DCOMPILE_FORCE_PS_SOFTWARE_NO_OPT;
        public static final int NO_PRESHADER = Defines.D3DCOMPILE_NO_PRESHADER;
        public static final int AVOID_FLOW_CONTROL = Defines.D3DCOMPILE_AVOID_FLOW_CONTROL;
        public static final int PREFER_FLOW_CONTROL = Defines.D3DCOMPILE_PREFER_FLOW_CONTROL;
        public static final int ENABLE_STRICTNESS = Defines.D3DCOMPILE_ENABLE_STRICTNESS;
        public static final int ENABLE_BACKWARDS_COMPATIBILITY = Defines.D3DCOMPILE_ENABLE_BACKWARDS_COMPATIBILITY;
        public static final int IEEE_STRICTNESS = Defines.D3DCOMPILE_IEEE_STRICTNESS;
        public static final int OPTIMIZATION_LEVEL0 = Defines.D3DCOMPILE_OPTIMIZATION_LEVEL0;
        public static final int OPTIMIZATION_LEVEL1 = Defines.D3DCOMPILE_OPTIMIZATION_LEVEL1;
        public static final int OPTIMIZATION_LEVEL2 = Defines.D3DCOMPILE_OPTIMIZATION_LEVEL2;
        public static final int OPTIMIZATION_LEVEL3 = Defines.D3DCOMPILE_OPTIMIZATION_LEVEL3;
        public static final int WARNINGS_ARE_ERRORS = Defines.D3DCOMPILE_WARNINGS_ARE_ERRORS;
        public static final int RESOURCES_MAY_ALIAS = Defines.D3DCOMPILE_RESOURCES_MAY_ALIAS;
        public static final int ENABLE_UNBOUNDED_DESCRIPTOR_TABLES = Defines.D3DCOMPILE_ENABLE_UNBOUNDED_DESCRIPTOR_TABLES;
        public static final int ALL_RESOURCES_BOUND = Defines.D3DCOMPILE_ALL_RESOURCES_BOUND;
        public static final int DEBUG_NAME_FOR_SOURCE = Defines.D3DCOMPILE_DEBUG_NAME_FOR_SOURCE;
        public static final int DEBUG_NAME_FOR_BINARY = Defines.D3DCOMPILE_DEBUG_NAME_FOR_BINARY;

        private Flags() {
        }
    }

    /**
     * {@code D3DCOMPILE_FLAGS2_*} values for the {@code flags2} parameter of {@code compile}.
     */
    public static final class Flags2 {
        public static final int FORCE_ROOT_SIGNATURE_LATEST = Defines.D3DCOMPILE_FLAGS2_FORCE_ROOT_SIGNATURE_LATEST;
        public static final int FORCE_ROOT_SIGNATURE_1_0 = Defines.D3DCOMPILE_FLAGS2_FORCE_ROOT_SIGNATURE_1_0;
        public static final int FORCE_ROOT_SIGNATURE_1_1 = Defines.D3DCOMPILE_FLAGS2_FORCE_ROOT_SIGNATURE_1_1;

        private Flags2() {
        }
    }

    /**
     * {@code D3D_DISASM_*} flags for {@link #disassemble}.
     */
    public static final class DisassembleFlags {
        public static final int ENABLE_COLOR_CODE = Defines.D3D_DISASM_ENABLE_COLOR_CODE;
        public static final int ENABLE_DEFAULT_VALUE_PRINTS = Defines.D3D_DISASM_ENABLE_DEFAULT_VALUE_PRINTS;
        public static final int ENABLE_INSTRUCTION_NUMBERING = Defines.D3D_DISASM_ENABLE_INSTRUCTION_NUMBERING;
        public static final int ENABLE_INSTRUCTION_CYCLE = Defines.D3D_DISASM_ENABLE_INSTRUCTION_CYCLE;
        public static final int DISABLE_DEBUG_INFO = Defines.D3D_DISASM_DISABLE_DEBUG_INFO;
        public static final int ENABLE_INSTRUCTION_OFFSET = Defines.D3D_DISASM_ENABLE_INSTRUCTION_OFFSET;
        public static final int INSTRUCTION_ONLY = Defines.D3D_DISASM_INSTRUCTION_ONLY;
        public static final int PRINT_HEX_LITERALS = Defines.D3D_DISASM_PRINT_HEX_LITERALS;

        private DisassembleFlags() {
        }
    }

    /**
     * A preprocessor definition ({@code #define name definition}) passed to the compiler.
     *
     * @param name       the macro name
     * @param definition the replacement text; null or empty defines the macro as empty
     */
    public record Macro(String name, String definition) {
        public Macro {
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("Macro name must not be empty");
            }
        }
    }

    /**
     * The outcome of a successful compilation. Close it to release the bytecode blob; the bytes returned by
     * {@link #toByteArray()} stay valid after that.
     *
     * @param bytecode the DXBC bytecode blob; owned by this result
     * @param warnings the compiler's warning text, empty if there were no warnings
     */
    public record Result(D3DBlob bytecode, String warnings) implements AutoCloseable {
        /**
         * @return a view of the bytecode, valid until the result is closed; its size is the bytecode length, so it can
         * be used directly as a {@code D3D12_SHADER_BYTECODE} (address and length)
         */
        public MemorySegment data() {
            return this.bytecode.data();
        }

        public long size() {
            return this.bytecode.size();
        }

        /**
         * @return a copy of the bytecode that outlives this result (for caching or to compile once and create many
         * pipeline states)
         */
        public byte[] toByteArray() {
            return this.bytecode.toByteArray();
        }

        public boolean hasWarnings() {
            return !this.warnings.isEmpty();
        }

        @Override
        public void close() {
            this.bytecode.release();
        }
    }

    /**
     * @return true if this process can call into {@code d3dcompiler_47.dll} (Windows, library present, native access
     * enabled)
     */
    public static boolean isAvailable() {
        try {
            return d3dCompile() != null;
        } catch (D3D12Exception e) {
            return false;
        }
    }

    /**
     * Compiles HLSL text with default flags and no macros.
     *
     * @see #compile(String, String, String, String, int, int, List)
     */
    public static Result compile(String source, String entryPoint, String target) {
        return compile(source, null, entryPoint, target, 0, 0, List.of());
    }

    /**
     * Compiles HLSL text without macros.
     *
     * @see #compile(String, String, String, String, int, int, List)
     */
    public static Result compile(String source, String sourceName, String entryPoint, String target, int flags1, int flags2) {
        return compile(source, sourceName, entryPoint, target, flags1, flags2, List.of());
    }

    /**
     * Compiles HLSL text.
     *
     * <p>{@code #include} is not supported: no include handler is installed, so an {@code #include} directive is a
     * compilation error. Resolve includes before calling.
     *
     * @param source     the HLSL source; encoded as UTF-8
     * @param sourceName name shown in diagnostics (for example "chunk_opaque.hlsl"), or null
     * @param entryPoint name of the entry point function, for example "main"
     * @param target     the target profile, for example "vs_5_0", "ps_5_1" or "cs_5_0"
     * @param flags1     {@link Flags}
     * @param flags2     {@link Flags2}
     * @param macros     preprocessor definitions, may be empty
     * @return the bytecode and warnings; close it when done
     * @throws D3DShaderCompileException if the compiler rejects the shader; carries the compiler's error text
     * @throws D3D12Exception            if the compiler cannot be loaded or fails for another reason
     */
    public static Result compile(String source, String sourceName, String entryPoint, String target,
                                 int flags1, int flags2, List<Macro> macros) {
        try (Arena arena = Arena.ofConfined()) {
            byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
            MemorySegment text = arena.allocate(bytes.length);
            MemorySegment.copy(bytes, 0, text, JAVA_BYTE, 0, bytes.length);

            return compile(arena, text, sourceName, entryPoint, target, flags1, flags2, macros);
        }
    }

    /**
     * Compiles HLSL text held in memory.
     *
     * @param source the HLSL source bytes (its whole {@link MemorySegment#byteSize()}); heap segments are copied
     * @see #compile(String, String, String, String, int, int, List)
     */
    public static Result compile(MemorySegment source, String sourceName, String entryPoint, String target,
                                 int flags1, int flags2, List<Macro> macros) {
        try (Arena arena = Arena.ofConfined()) {
            return compile(arena, nativeCopy(arena, source), sourceName, entryPoint, target, flags1, flags2, macros);
        }
    }

    /**
     * Compiles HLSL text held in a buffer.
     *
     * @param source the HLSL source bytes between the buffer's position and limit; the buffer is not modified
     * @see #compile(String, String, String, String, int, int, List)
     */
    public static Result compile(ByteBuffer source, String sourceName, String entryPoint, String target,
                                 int flags1, int flags2, List<Macro> macros) {
        return compile(MemorySegment.ofBuffer(source), sourceName, entryPoint, target, flags1, flags2, macros);
    }

    private static Result compile(Arena arena, MemorySegment source, String sourceName, String entryPoint, String target,
                                  int flags1, int flags2, List<Macro> macros) {
        MethodHandle compile = d3dCompile();

        MemorySegment name = sourceName == null ? MemorySegment.NULL : arena.allocateFrom(sourceName);
        MemorySegment entry = entryPoint == null ? MemorySegment.NULL : arena.allocateFrom(entryPoint);
        MemorySegment profile = arena.allocateFrom(target);
        MemorySegment defines = macros.isEmpty() ? MemorySegment.NULL : allocateMacros(arena, macros);

        MemorySegment codeOut = arena.allocate(ADDRESS);
        MemorySegment errorsOut = arena.allocate(ADDRESS);

        int hr;

        try {
            hr = (int) compile.invokeExact(source, source.byteSize(), name, defines, MemorySegment.NULL, entry, profile,
                    flags1, flags2, codeOut, errorsOut);
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }

        D3DBlob code = wrapBlob(codeOut);
        D3DBlob errors = wrapBlob(errorsOut);

        try {
            String text = errors == null ? "" : errors.asString();

            if (hr < 0 || code == null) {
                throw new D3DShaderCompileException(hr < 0 ? hr : Hr.E_FAIL, text, sourceName, entryPoint, target);
            }

            Result result = new Result(code, text);
            code = null;

            return result;
        } finally {
            if (errors != null) {
                errors.release();
            }

            if (code != null) {
                code.release();
            }
        }
    }

    /**
     * Disassembles compiled bytecode to the textual form that fxc prints, for debugging and logging.
     *
     * @param bytecode a compiled shader (DXBC container)
     * @param flags    {@link DisassembleFlags}
     * @param comments text emitted at the top of the listing, or null
     * @return the disassembly
     * @throws D3D12Exception if the bytecode cannot be disassembled
     */
    public static String disassemble(MemorySegment bytecode, int flags, String comments) {
        MethodHandle disassemble = d3dDisassemble();

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment text = comments == null ? MemorySegment.NULL : arena.allocateFrom(comments);
            MemorySegment out = arena.allocate(ADDRESS);
            int hr = (int) disassemble.invokeExact(nativeCopy(arena, bytecode), bytecode.byteSize(), flags, text, out);
            Hr.check(hr, "D3DDisassemble");

            D3DBlob blob = wrapBlob(out);

            try {
                return blob.asString();
            } finally {
                blob.release();
            }
        } catch (Throwable t) {
            throw Com.rethrow(t);
        }
    }

    /**
     * Disassembles compiled bytecode with default flags.
     *
     * @see #disassemble(MemorySegment, int, String)
     */
    public static String disassemble(MemorySegment bytecode) {
        return disassemble(bytecode, 0, null);
    }

    /**
     * Disassembles the bytecode of a compilation result.
     *
     * @see #disassemble(MemorySegment, int, String)
     */
    public static String disassemble(Result result, int flags, String comments) {
        return disassemble(result.data(), flags, comments);
    }

    /**
     * Disassembles the bytecode of a compilation result with default flags.
     *
     * @see #disassemble(MemorySegment, int, String)
     */
    public static String disassemble(Result result) {
        return disassemble(result.data(), 0, null);
    }

    private static D3DBlob wrapBlob(MemorySegment out) {
        MemorySegment pointer = out.get(ADDRESS, 0);

        return pointer.address() == 0L ? null : new D3DBlob(pointer);
    }

    private static MemorySegment nativeCopy(Arena arena, MemorySegment segment) {
        return segment.isNative() ? segment : arena.allocate(segment.byteSize()).copyFrom(segment);
    }

    /**
     * Builds the {@code D3D_SHADER_MACRO} array: {name, definition} pairs ended by a {NULL, NULL} entry.
     */
    private static MemorySegment allocateMacros(Arena arena, List<Macro> macros) {
        MemorySegment array = arena.allocate(MACRO_SIZE * (macros.size() + 1));

        for (int i = 0; i < macros.size(); i++) {
            Macro macro = macros.get(i);
            array.set(ADDRESS, MACRO_SIZE * i, arena.allocateFrom(macro.name()));
            array.set(ADDRESS, MACRO_SIZE * i + 8, arena.allocateFrom(macro.definition() == null ? "" : macro.definition()));
        }

        return array;
    }

    private static MethodHandle d3dCompile() {
        MethodHandle handle = d3dCompile;

        if (handle == null) {
            synchronized (D3DCompiler.class) {
                handle = d3dCompile;

                if (handle == null) {
                    handle = Win32Libraries.function(Win32Libraries.d3dCompiler(), "D3DCompile", D3D_COMPILE_DESCRIPTOR);
                    d3dCompile = handle;
                }
            }
        }

        return handle;
    }

    private static MethodHandle d3dDisassemble() {
        MethodHandle handle = d3dDisassemble;

        if (handle == null) {
            synchronized (D3DCompiler.class) {
                handle = d3dDisassemble;

                if (handle == null) {
                    handle = Win32Libraries.function(Win32Libraries.d3dCompiler(), "D3DDisassemble", D3D_DISASSEMBLE_DESCRIPTOR);
                    d3dDisassemble = handle;
                }
            }
        }

        return handle;
    }
}
