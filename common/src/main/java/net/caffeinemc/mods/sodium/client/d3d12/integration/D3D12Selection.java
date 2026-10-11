// Owner: lead (WP5)
package net.caffeinemc.mods.sodium.client.d3d12.integration;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.GpuBackend;
import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.caffeinemc.mods.sodium.client.compatibility.environment.OsUtils;
import net.caffeinemc.mods.sodium.client.console.Console;
import net.caffeinemc.mods.sodium.client.console.message.MessageLevel;
import net.caffeinemc.mods.sodium.client.d3d12.backend.D3D12Backend;
import net.caffeinemc.mods.sodium.client.gui.SodiumOptions;
import net.caffeinemc.mods.sodium.client.services.PlatformRuntimeInformation;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Decides whether Minecraft should try the Direct3D 12 backend, and creates it.
 *
 * <p>Every method is safe to call on every platform and never throws: the backend is an opt-in experiment, so a
 * problem here must at worst leave the game on the backends it would have used anyway. No class that touches a native
 * library is referenced from here other than {@link D3D12Backend}, whose construction does nothing native.
 *
 * <p>The backend is only created when <b>all</b> of these hold:
 * <ol>
 *     <li>the OS is Windows (Wine reports Windows, which the test environment relies on);</li>
 *     <li>the kill switch {@code -Dsodium.graphics.d3d12=false} is absent;</li>
 *     <li>it was requested, by {@code -Dsodium.graphics.d3d12=true} or by the "Use Direct3D 12" option;</li>
 *     <li>the platform has no early loading screen that owns the window (NeoForge), unless the system property
 *     explicitly forces it;</li>
 *     <li>the previous attempt did not die during start-up (see {@link #consumeStartupMarker}).</li>
 * </ol>
 */
public final class D3D12Selection {
    private static final Logger LOGGER = LoggerFactory.getLogger("Sodium-D3D12");

    /** {@code true} forces the backend on, {@code false} forces it off, regardless of the option. */
    public static final String PROPERTY = "sodium.graphics.d3d12";

    private static final String MARKER_FILE_NAME = "sodium-d3d12-startup.marker";
    private static final long SHADER_CACHE_LIMIT_BYTES = 256L << 20;
    private static final long SHADER_CACHE_TRIM_TO_BYTES = 128L << 20;

    /** Set once the backend has been handed to Minecraft, so that the outcome can be reported after start-up. */
    private static volatile boolean attempted;

    private D3D12Selection() {
    }

    /**
     * @return the backend to try before Minecraft's own list, or {@code null} if Direct3D 12 is not selected
     */
    public static @Nullable GpuBackend createBackendIfSelected() {
        try {
            return select();
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError error) {
                throw error;
            }

            LOGGER.error("Could not set up the Direct3D 12 renderer; using the default graphics API", t);

            return null;
        }
    }

    private static @Nullable GpuBackend select() {
        if (OsUtils.getOs() != OsUtils.OperatingSystem.WIN) {
            return null;
        }

        String property = System.getProperty(PROPERTY);

        if ("false".equalsIgnoreCase(property)) {
            return null;
        }

        boolean forced = "true".equalsIgnoreCase(property);

        if (!forced && !isOptionEnabled()) {
            return null;
        }

        if (!forced && PlatformRuntimeInformation.getInstance().platformHasEarlyLoadingScreen()) {
            LOGGER.info("Not trying Direct3D 12: this platform creates its window before Sodium can take part. Use -D{}=true to force it", PROPERTY);

            return null;
        }

        Path configDirectory = PlatformRuntimeInformation.getInstance().getConfigDirectory();

        if (!consumeStartupMarker(configDirectory)) {
            return null;
        }

        attempted = true;

        Path cacheDirectory = PlatformRuntimeInformation.getInstance().getGameDirectory()
                .resolve(".sodium").resolve("d3d12").resolve("dxbc");

        LOGGER.info("Trying the experimental Direct3D 12 renderer before the default graphics API");

        return new D3D12Backend(options -> {
            if (System.getProperty("sodium.d3d12.shaderCache") != null) {
                return options; // an explicit setting wins over the default location
            }

            trimShaderCache(cacheDirectory);

            return options.withShaderCacheDir(cacheDirectory);
        });
    }

    private static boolean isOptionEnabled() {
        try {
            return SodiumClientMod.options().advanced.useD3d12;
        } catch (IllegalStateException e) {
            return false; // the configuration is not loaded yet; only the system property can enable the backend
        }
    }

    /**
     * Start-up guard. A driver crash inside device creation or the first present would repeat on every launch, and
     * vanilla's own guard knows nothing about this option. The marker exists from the moment the backend is handed
     * out until the game finishes loading; finding it at the next launch means the previous attempt never got there.
     *
     * @return {@code false} if the marker was found (the option is switched off and the backend must not be tried)
     */
    private static boolean consumeStartupMarker(Path configDirectory) {
        Path marker = configDirectory.resolve(MARKER_FILE_NAME);

        if (Files.exists(marker)) {
            try {
                Files.deleteIfExists(marker);
            } catch (IOException e) {
                LOGGER.warn("Could not delete {}", marker, e);
            }

            LOGGER.warn("The previous start with the Direct3D 12 renderer did not finish; it is switched off. Enable it again in the video settings to retry");
            disableOption();

            return false;
        }

        try {
            Files.createDirectories(configDirectory);
            Files.writeString(marker, "Created while the Direct3D 12 renderer starts. Deleted when the game has finished loading.\n");
        } catch (IOException e) {
            // Without a marker there is no crash protection, so do not take the risk
            LOGGER.warn("Not trying Direct3D 12: cannot write the start-up marker {}", marker, e);

            return false;
        }

        return true;
    }

    private static void disableOption() {
        try {
            SodiumOptions options = SodiumClientMod.options();

            if (options.advanced.useD3d12 && !options.isReadOnly()) {
                options.advanced.useD3d12 = false;
                SodiumOptions.writeToDisk(options);
            }
        } catch (IllegalStateException | IOException e) {
            LOGGER.warn("Could not switch off the Direct3D 12 option", e);
        }
    }

    /**
     * Called when the game has finished loading: the start-up was clean, so the marker goes away, and a fallback to
     * another graphics API is reported to the player.
     */
    public static void onGameLoadFinished() {
        try {
            Path marker = PlatformRuntimeInformation.getInstance().getConfigDirectory().resolve(MARKER_FILE_NAME);
            Files.deleteIfExists(marker);

            if (attempted) {
                String backend = RenderSystem.getDevice().getDeviceInfo().backendName();

                if (backend.contains("Direct3D 12")) {
                    LOGGER.info("Rendering with Direct3D 12");
                } else {
                    LOGGER.warn("Direct3D 12 could not be used; the game is rendering with {}. See the log above for the reason", backend);
                    Console.instance().logMessage(MessageLevel.WARN, "sodium.console.d3d12_fallback", true, 10.0);
                }
            }
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError error) {
                throw error;
            }

            LOGGER.warn("Could not finish the Direct3D 12 start-up bookkeeping", t);
        }
    }

    /**
     * Keeps the compiled-shader cache bounded: when it exceeds its limit, the least recently modified files are
     * deleted until it is back under the target size. Failures are ignored; the cache is an optimisation.
     */
    private static void trimShaderCache(Path directory) {
        if (!Files.isDirectory(directory)) {
            return;
        }

        try (Stream<Path> files = Files.walk(directory)) {
            List<Path> all = new ArrayList<>(files.filter(Files::isRegularFile).toList());
            long total = 0;

            for (Path file : all) {
                total += Files.size(file);
            }

            if (total <= SHADER_CACHE_LIMIT_BYTES) {
                return;
            }

            all.sort(Comparator.comparing(file -> {
                try {
                    return Files.getLastModifiedTime(file);
                } catch (IOException e) {
                    return java.nio.file.attribute.FileTime.fromMillis(0);
                }
            }));

            for (Path file : all) {
                if (total <= SHADER_CACHE_TRIM_TO_BYTES) {
                    break;
                }

                long size = Files.size(file);

                if (Files.deleteIfExists(file)) {
                    total -= size;
                }
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not trim the shader cache in {}", directory, e);
        }
    }
}
