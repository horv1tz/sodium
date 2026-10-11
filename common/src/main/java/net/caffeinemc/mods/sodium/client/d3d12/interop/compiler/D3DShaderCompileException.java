package net.caffeinemc.mods.sodium.client.d3d12.interop.compiler;

import net.caffeinemc.mods.sodium.client.d3d12.interop.D3D12Exception;

/**
 * Thrown by {@link D3DCompiler} when the HLSL compiler rejects a shader. {@link #getCompilerOutput()} is the
 * compiler's own error text, with one {@code file(line,column): error Xnnnn: message} entry per diagnostic.
 */
public class D3DShaderCompileException extends D3D12Exception {
    private final String compilerOutput;
    private final String sourceName;
    private final String entryPoint;
    private final String target;

    public D3DShaderCompileException(int hresult, String compilerOutput, String sourceName, String entryPoint, String target) {
        super("Shader compilation failed for " + describe(sourceName, entryPoint, target), hresult);

        this.compilerOutput = compilerOutput;
        this.sourceName = sourceName;
        this.entryPoint = entryPoint;
        this.target = target;
    }

    private static String describe(String sourceName, String entryPoint, String target) {
        return (sourceName == null ? "<unnamed source>" : sourceName) + " (entry point " + entryPoint + ", target " + target + ")";
    }

    /**
     * @return the compiler's error and warning text (never null, empty if the compiler produced none)
     */
    public String getCompilerOutput() {
        return this.compilerOutput;
    }

    /**
     * @return the name passed to the compiler for the source, or null
     */
    public String getSourceName() {
        return this.sourceName;
    }

    public String getEntryPoint() {
        return this.entryPoint;
    }

    public String getTarget() {
        return this.target;
    }

    /**
     * @return the failure summary followed by the compiler output on separate lines
     */
    @Override
    public String getMessage() {
        return this.compilerOutput.isEmpty() ? super.getMessage() : super.getMessage() + "\n" + this.compilerOutput;
    }
}
