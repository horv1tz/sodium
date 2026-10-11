// Owner: WP4 (surface + entry)
package net.caffeinemc.mods.sodium.client.d3d12.probe;

import net.caffeinemc.mods.sodium.client.d3d12.core.DeviceContext;
import net.caffeinemc.mods.sodium.client.d3d12.core.VertexIdSemantics;

/**
 * Device-level self-tests that remove an unknown from the correctness argument by measuring it on the adapter in use.
 *
 * <h2>{@code SV_VertexID} semantics (decision D12)</h2>
 * Vulkan's {@code gl_VertexIndex} includes {@code firstVertex} / {@code vertexOffset}; HLSL's {@code SV_VertexID} does so
 * on vkd3d: <b>not</b> (measured, spec 02 section 6.6), and native D3D12 is documented but unverified. One vanilla shader
 * depends on it: {@code animate_sprite.vsh} reads the interpolation progress from {@code firstVertex}
 * ({@code draw(6, 1, progress << 3, 0)}). {@code measure} runs a micro-test on the real runtime: a 16x1 {@code R32_UINT}
 * target, a built-in pipeline (through {@code BuiltinPipelines.fromHlsl}) that writes its {@code SV_VertexID} as the point
 * position, three points drawn with {@code DrawInstanced(.., StartVertexLocation = 5)} and, indexed,
 * {@code DrawIndexedInstanced(.., BaseVertexLocation = 7)}; the result is read back and decides
 * {@link VertexIdSemantics#nonIndexedIncludesStart()} / {@link VertexIdSemantics#indexedIncludesBase()}.
 *
 * <p>Isolation rules: uses its own command allocator + list and its own fence/idle wait (the probe never touches the
 * frame list, the tracker, the rings or any frame resource; everything it creates is released before returning); runs once
 * at device construction after the root signature exists. A failure of the <em>measurement</em> (compile, PSO, readback,
 * garbage pattern) is logged and answered with {@link VertexIdSemantics#DEFAULT} (the vkd3d measurement). A <b>lost
 * device</b> is different: {@code GpuDeviceLossException} (and {@code VirtualMachineError}) are rethrown, never swallowed,
 * and {@code D3D12GpuDevice.create} additionally calls {@code errors().checkDeviceRemoved("start-up probe")} +
 * {@code checkHealthy()} outside the probe's catch (a device removed by the probe's draw can also surface only as a
 * failed fence wait or an HRESULT), so such a device never reaches the first frame.
 *
 * <p>This is the one documented exception to the rule that GPU commands are issued by a short list of classes
 * (invariant I3): the probe records on its <em>own</em> allocator + list and never touches the frame list, the tracker or
 * the rings.
 */
public final class StartupProbes {
    private StartupProbes() {
    }

    /**
     * @return the measured semantics, or {@link VertexIdSemantics#DEFAULT} if the probe could not run
     * @throws com.mojang.renderpearl.api.device.GpuDeviceLossException if the device was lost while probing
     */
    public static VertexIdSemantics measure(DeviceContext ctx) {
        throw new UnsupportedOperationException("WP4: micro-draw with StartVertexLocation 5 / BaseVertexLocation 7, read back, compare");
    }
}
