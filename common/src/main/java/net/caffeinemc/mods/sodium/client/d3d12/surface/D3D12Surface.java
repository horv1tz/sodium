// Owner: WP4 (surface + entry)
package net.caffeinemc.mods.sodium.client.d3d12.surface;

import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.device.SurfaceException;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import net.caffeinemc.mods.sodium.client.d3d12.core.DeviceContext;

import java.util.Collection;
import java.util.function.BooleanSupplier;

/**
 * The swap-chain surface (spec 01 section 6, spec 05 sections 5.x). Created by {@code D3D12GpuDevice.createSurface} once
 * the window exists; creates <b>no</b> swap chain (that happens in the first {@code configure}). Owner of the
 * {@link SwapChainHolder} and the {@link PresentBlit}.
 *
 * <h2>Behaviour</h2>
 * <ul>
 *     <li>{@code configure} (called routinely with an unchanged size: every restore-from-minimise, vsync toggle, failed
 *     acquire): {@code width/height} outside {@code 1..16384} -> {@code SurfaceException} (never {@code ResizeBuffers(0, 0)}).
 *     Store the present mode; <b>fast path</b> (swap chain exists, healthy, same size): clear {@code suboptimal}, any
 *     deferred error and the stale flag and return without touching the GPU. Otherwise create/resize
 *     ({@code SwapChainHolder.configure}), then ensure the blit pipeline. A native failure sets {@code stale = suboptimal
 *     = true} and throws {@code SurfaceException}; device loss throws {@code GpuDeviceLossException}.</li>
 *     <li>{@code isSuboptimal()} is a field: false after every successful configure (including the fast path), true only
 *     after a failed configure or {@code DXGI_STATUS_MODE_CHANGED}; never computed from window sizes (the borderless
 *     window is legitimately one pixel wider); valid in every state, never throws.</li>
 *     <li>{@code acquireNextTexture}: (1) {@code isIconified} -> {@code SurfaceException("Cannot acquire minimized
 *     window")}; (2) a stored deferred {@code present()} error is cleared and thrown; (3) no/stale swap chain ->
 *     {@code SurfaceException}; (4) optional frame-latency wait (250 ms cap, default off); (5)
 *     {@code index = GetCurrentBackBufferIndex()}, mark acquired. No GPU work is recorded.</li>
 *     <li>{@code blitFromTexture}: see {@link PresentBlit}; the encoder argument is the backend singleton
 *     ({@code (D3D12CommandEncoder)} cast), the view a {@code D3D12TextureView}.</li>
 *     <li>{@code present}: {@code Present(FIFO ? 1 : 0, IMMEDIATE &amp;&amp; tearing ? ALLOW_TEARING : 0)}; any
 *     {@code hr >= 0} is success ({@code S_OK}, {@code DXGI_STATUS_OCCLUDED}); {@code MODE_CHANGED} sets suboptimal;
 *     device-loss codes throw {@code GpuDeviceLossException} (with the removed reason); {@code INVALID_CALL} and other
 *     swap-chain errors are stored as a {@code SurfaceException} for the next {@code acquireNextTexture} and the chain is
 *     marked stale; anything else throws unchecked. It never waits on the GPU and never throws {@code SurfaceException}.</li>
 *     <li>{@code supportedPresentModes()} = {@code {FIFO, IMMEDIATE}}, constant, no DXGI call.</li>
 *     <li>{@code close()}: idle the GPU, free RTVs, release back buffers and swap chain (before the queue), close the
 *     blit (its point sampler only: the blit pipeline is the shared device-wide instance); idempotent; never touches a closed device (it is closed <em>before</em> the device).</li>
 * </ul>
 * Render thread only.
 */
public final class D3D12Surface implements GpuSurfaceBackend {
    private final DeviceContext ctx;
    private final long hwnd;
    private final BooleanSupplier isIconified;

    public D3D12Surface(DeviceContext ctx, long hwnd, BooleanSupplier isIconified) {
        this.ctx = ctx;
        this.hwnd = hwnd;
        this.isIconified = isIconified;
    }

    @Override
    public void configure(GpuSurface.Configuration config) throws SurfaceException {
        throw new UnsupportedOperationException("WP4");
    }

    @Override
    public boolean isSuboptimal() {
        throw new UnsupportedOperationException("WP4");
    }

    @Override
    public void acquireNextTexture() throws SurfaceException {
        throw new UnsupportedOperationException("WP4");
    }

    @Override
    public void blitFromTexture(CommandEncoderBackend commandEncoder, GpuTextureView textureView) {
        throw new UnsupportedOperationException("WP4");
    }

    @Override
    public void present() {
        throw new UnsupportedOperationException("WP4");
    }

    @Override
    public Collection<GpuSurface.PresentMode> supportedPresentModes() {
        return java.util.EnumSet.of(GpuSurface.PresentMode.FIFO, GpuSurface.PresentMode.IMMEDIATE);
    }

    @Override
    public void close() {
        throw new UnsupportedOperationException("WP4");
    }

    DeviceContext context() {
        return this.ctx;
    }

    long hwnd() {
        return this.hwnd;
    }

    BooleanSupplier isIconified() {
        return this.isIconified;
    }
}
