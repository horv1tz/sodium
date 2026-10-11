// Owner: WP1 (device core)
package net.caffeinemc.mods.sodium.client.d3d12.core;

import net.caffeinemc.mods.sodium.client.d3d12.interop.D3D12Exception;
import net.caffeinemc.mods.sodium.client.d3d12.interop.Win32Libraries;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * {@code QueryPerformanceFrequency}, needed to turn the CPU tick of {@code ID3D12CommandQueue::GetClockCalibration}
 * (QPC ticks) into nanoseconds for {@code GpuDeviceBackend.getTimestampCalibrationOffset()}. The interop layer is frozen
 * and does not wrap it, so this is a tiny own binding; nothing is loaded before the first call (safe on Linux/macOS).
 *
 * <p>{@code System.nanoTime()} is QPC based on Windows, so {@code cpuNs = ticks * 1e9 / frequency} is in the
 * {@code nanoTime} time base the profiler expects (documented as UNVERIFIED in spec 01 section 9.2; the calibration test
 * on real Windows checks it).
 */
public final class Qpc {
    private static volatile long frequency;

    private Qpc() {
    }

    /** @return QPC ticks per second */
    public static long frequency() {
        long cached = frequency;

        if (cached != 0L) {
            return cached;
        }

        try (Arena arena = Arena.ofConfined()) {
            MethodHandle handle = Win32Libraries.function(Win32Libraries.kernel32(), "QueryPerformanceFrequency",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            MemorySegment out = arena.allocate(ValueLayout.JAVA_LONG);
            int ok = (int) handle.invokeExact(out);

            if (ok == 0) {
                throw new D3D12Exception("QueryPerformanceFrequency failed");
            }

            frequency = out.get(ValueLayout.JAVA_LONG, 0L);

            return frequency;
        } catch (D3D12Exception e) {
            throw e;
        } catch (Throwable t) {
            throw new D3D12Exception("QueryPerformanceFrequency is not available", t);
        }
    }

    /**
     * @param qpcTicks a CPU tick value from {@code GetClockCalibration}
     * @return the same instant in nanoseconds
     */
    public static long toNanos(long qpcTicks) {
        long f = frequency();

        return (qpcTicks / f) * 1_000_000_000L + (qpcTicks % f) * 1_000_000_000L / f;
    }
}
