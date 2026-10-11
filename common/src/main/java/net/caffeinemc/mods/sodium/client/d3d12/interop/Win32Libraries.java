package net.caffeinemc.mods.sodium.client.d3d12.interop;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lazy loader for the Windows system libraries the Direct3D 12 backend depends on. Nothing in this class touches a
 * native library until a method asks for one, so merely loading the class is safe on every platform.
 */
public final class Win32Libraries {
    private static final ConcurrentHashMap<String, SymbolLookup> LIBRARIES = new ConcurrentHashMap<>();

    private Win32Libraries() {
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    public static SymbolLookup d3d12() {
        return load("d3d12.dll");
    }

    public static SymbolLookup dxgi() {
        return load("dxgi.dll");
    }

    public static SymbolLookup d3dCompiler() {
        return load("d3dcompiler_47.dll");
    }

    public static SymbolLookup kernel32() {
        return load("kernel32.dll");
    }

    public static SymbolLookup load(String name) {
        if (!isWindows()) {
            throw new D3D12Exception("Cannot load " + name + ": not running on Windows");
        }

        return LIBRARIES.computeIfAbsent(name, n -> {
            try {
                return SymbolLookup.libraryLookup(n, Arena.global());
            } catch (IllegalArgumentException | UnsupportedOperationException e) {
                throw new D3D12Exception("Cannot load " + n, e);
            } catch (IllegalCallerException e) {
                throw new D3D12Exception("Native access is not enabled for this module (add --enable-native-access=ALL-UNNAMED)", e);
            }
        });
    }

    /**
     * Looks up an exported function and links it. The returned handle takes the arguments of {@code descriptor}.
     */
    public static MethodHandle function(SymbolLookup library, String symbol, FunctionDescriptor descriptor) {
        MemorySegment address = library.find(symbol)
                .orElseThrow(() -> new D3D12Exception("Missing native symbol " + symbol));

        return Com.linker().downcallHandle(address, descriptor);
    }

    /**
     * @return the linker used for all native calls
     */
    static Linker linker() {
        return Com.linker();
    }
}
