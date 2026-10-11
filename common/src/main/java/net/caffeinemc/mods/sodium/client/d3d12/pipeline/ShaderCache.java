// Owner: WP3 (pipelines + render pass) - fully implemented; WP3 owner adds CacheKeyTest (key sensitivity, corrupt files)
package net.caffeinemc.mods.sodium.client.d3d12.pipeline;

import net.caffeinemc.mods.sodium.client.d3d12.core.D3D12Log;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32;

/**
 * Cache of the expensive step only: HLSL text -> DXBC ({@code D3DCompile}). Translation (SPIRV-Cross, milliseconds)
 * always runs, so slot facts never need to be stored and no translator option can be forgotten in a key: the HLSL text
 * <em>is</em> the result of all of them (decision D11).
 *
 * <ul>
 *     <li>Key = SHA-256 over {HLSL text, target profile, entry point, compile flags, compiler identity (the
 *     {@code d3dcompiler_47.dll} file size and last-modified time)} -> {@link #key}.</li>
 *     <li>Memory map always; disk directory optional ({@code null} = memory only). Disk files are
 *     {@code <key>.dxbc} = {@code magic, version, length, crc32} (little endian ints) + payload; any mismatch (magic,
 *     version, length, CRC, unreadable) is a miss and the file is deleted. Writes go to a temp file in the same directory
 *     and are moved into place atomically, so concurrent workers and crashes cannot leave a torn file.</li>
 *     <li>The PSO {@code CachedBlob} is deliberately <b>not</b> cached (decision D15).</li>
 * </ul>
 * Thread-safe.
 */
public final class ShaderCache {
    private static final int MAGIC = 0x43584453; // "SDXC"
    private static final int VERSION = 1;
    private static final int HEADER = 16;

    private final @Nullable Path directory;
    private final ConcurrentHashMap<String, byte[]> memory = new ConcurrentHashMap<>();

    /** @param directory disk cache directory (created on demand) or null */
    public ShaderCache(@Nullable Path directory) {
        this.directory = directory;
    }

    /** @return lower-case hex SHA-256 of the complete compile input */
    public static String key(String hlsl, String profile, String entryPoint, int flags, String compilerIdentity) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            update(digest, profile);
            update(digest, entryPoint);
            update(digest, Integer.toString(flags));
            update(digest, compilerIdentity);
            update(digest, hlsl);

            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }

    private static void update(MessageDigest digest, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);

        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
    }

    /** @return the cached DXBC or null on a miss (memory first, then disk) */
    public byte @Nullable [] get(String key) {
        byte[] cached = this.memory.get(key);

        if (cached != null) {
            return cached;
        }

        if (this.directory == null) {
            return null;
        }

        Path file = this.directory.resolve(key + ".dxbc");

        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }

            byte[] raw = Files.readAllBytes(file);
            byte[] payload = decode(raw);

            if (payload == null) {
                D3D12Log.warn("Discarding corrupt shader cache file {}", file);
                Files.deleteIfExists(file);

                return null;
            }

            this.memory.put(key, payload);

            return payload;
        } catch (IOException e) {
            D3D12Log.warn("Cannot read shader cache file {}: {}", file, e.toString());

            return null;
        }
    }

    /** Stores a compiled shader (memory, and disk if configured; disk failures are logged and ignored). */
    public void put(String key, byte[] dxbc) {
        this.memory.put(key, dxbc);

        if (this.directory == null) {
            return;
        }

        Path temp = null;

        try {
            Files.createDirectories(this.directory);

            temp = Files.createTempFile(this.directory, key.substring(0, 16), ".tmp");
            Files.write(temp, encode(dxbc));
            Files.move(temp, this.directory.resolve(key + ".dxbc"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            D3D12Log.warn("Cannot write shader cache entry {}: {}", key, e.toString());

            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }
    }

    /** @return the number of entries in the memory map */
    public int memorySize() {
        return this.memory.size();
    }

    static byte[] encode(byte[] payload) {
        CRC32 crc = new CRC32();

        crc.update(payload);

        ByteBuffer buffer = ByteBuffer.allocate(HEADER + payload.length).order(ByteOrder.LITTLE_ENDIAN);

        buffer.putInt(MAGIC).putInt(VERSION).putInt(payload.length).putInt((int) crc.getValue()).put(payload);

        return buffer.array();
    }

    /** @return the payload, or null if the bytes are not a valid cache file */
    static byte @Nullable [] decode(byte[] raw) {
        if (raw.length < HEADER) {
            return null;
        }

        ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);

        if (buffer.getInt() != MAGIC || buffer.getInt() != VERSION) {
            return null;
        }

        int length = buffer.getInt();
        int expectedCrc = buffer.getInt();

        if (length < 0 || length != raw.length - HEADER) {
            return null;
        }

        byte[] payload = new byte[length];

        buffer.get(payload);

        CRC32 crc = new CRC32();

        crc.update(payload);

        return (int) crc.getValue() == expectedCrc ? payload : null;
    }
}
