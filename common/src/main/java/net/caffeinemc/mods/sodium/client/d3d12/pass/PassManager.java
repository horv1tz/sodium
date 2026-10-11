// Owner: WP3 (pipelines + render pass) - implemented
package net.caffeinemc.mods.sodium.client.d3d12.pass;

import net.caffeinemc.mods.sodium.client.d3d12.command.ClearEngine;
import net.caffeinemc.mods.sodium.client.d3d12.command.CommandRecorder;
import net.caffeinemc.mods.sodium.client.d3d12.core.D3D12Log;
import net.caffeinemc.mods.sodium.client.d3d12.core.DeviceContext;
import net.caffeinemc.mods.sodium.client.d3d12.interop.D3D12Exception;
import net.caffeinemc.mods.sodium.client.d3d12.interop.abi.Enums;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * What {@code D3D12CommandEncoder} talks to: creates one {@link D3D12RenderPass} per pass and owns the single reusable
 * {@code PassBinder} and the {@code FanIndexBuffer} (decision D14: a thin pass object per pass for use-after-end
 * detection, a pooled binder for allocation-free state).
 *
 * <p>{@link #begin} performs the pass-begin sequence ("the only place where state is established", invariant I5):
 * <pre>
 *  validate the spec (pure)                                   // before the list is touched; errors are fatal, see below
 *  tracker.require(colour[i], RT) for non-null i; tracker.require(depth, DW); tracker.flush(list)
 *  list.setDescriptorHeaps(srvRing.currentHeap(), samplerRing.currentHeap()); list.setGraphicsRootSignature(global)
 *  binder state := all dirty, uniforms cleared, push-constant scratch zeroed, boundSrvEpoch/boundSmpEpoch := the ring epochs
 *                  (the heaps just set), bound-table keys forgotten
 *  list.omSetRenderTargets(n, rtvHandles (null RTV for holes), false, dsv or null)
 *  for attachments with a clear value: encoder.clears().clearRtv / clearDsv (rect = render area unless it fills the mip)
 *  list.rsSetViewport(0, 0, viewportWidth, viewportHeight, 0, 1)   // whole attachment of the last colour view, NOT the render area
 *  list.rsSetScissorRect(area)                                     // L=x, T=y, R=x+w, B=y+h
 *  list.omSetBlendFactor(0, 0, 0, 0)                               // D3D12 defaults to 1,1,1,1
 *  if labels: list.beginEvent(label)
 * </pre>
 * No {@code BeginRenderPass} API is used (area-scoped clear semantics are unverified; explicit rect clears + implicit
 * preserve are exact). The attachments stay in {@code RT} / {@code DW} after the pass until the next user transitions them.
 *
 * <p><b>Construction.</b> Created inside the {@code D3D12CommandEncoder} constructor, i.e. <em>before</em>
 * {@code ctx.attachEncoder(..)}: the manager (and the binder it creates) therefore receive the {@link CommandRecorder}
 * and the {@link ClearEngine} explicitly and must never call {@code ctx.encoder()} (it throws "not fully constructed").
 * The list is obtained per use with {@code recorder.list()} (it is the same object for the life of the device, but do not
 * cache a native pointer across {@code Reset}s).
 *
 * <p><b>Lifetime.</b> {@link #close()} closes the {@code FanIndexBuffer} (an UPLOAD committed buffer); its single caller is
 * {@code D3D12CommandEncoder.shutdown()} (order: pass manager, transient memory, recorder).
 *
 * <p>Threading: render thread.
 */
public final class PassManager implements AutoCloseable {
    private static final int MAX_COLOR_TARGETS = 8;

    private final DeviceContext ctx;
    private final PassBinder binder;
    private final FanIndexBuffer fan;

    /**
     * Touches nothing native (the fan buffer is created on first use).
     *
     * @param recorder the owner of the command list
     * @param clears   the engine of the load-op clears
     */
    public PassManager(DeviceContext ctx, CommandRecorder recorder, ClearEngine clears) {
        this.ctx = ctx;
        this.fan = new FanIndexBuffer(ctx);
        this.binder = new PassBinder(ctx, recorder, clears, this.fan);
    }

    /** Test seam: a manager over a binder with a fake native side. */
    PassManager(DeviceContext ctx, PassBinder binder, FanIndexBuffer fan) {
        this.ctx = ctx;
        this.fan = fan;
        this.binder = binder;
    }

    /**
     * @return the open pass; valid until {@link D3D12RenderPass#end()}
     * @throws RuntimeException only for fatal conditions (the frontend cannot unwind its {@code isInRenderPass} flag)
     */
    public D3D12RenderPass begin(PassSpec spec) {
        validate(spec);

        if (this.binder.isOpen()) {
            throw new IllegalStateException("A render pass is already open");
        }

        this.ctx.errors().checkHealthy();

        D3D12RenderPass pass = new D3D12RenderPass(this.ctx, this.binder, spec);

        try {
            this.binder.begin(spec, pass);
        } catch (D3D12Exception e) {
            // the tracker and the list were already mutated: the stream cannot be repaired
            throw this.ctx.errors().fail(e);
        }

        return pass;
    }

    /** Pure checks before anything is recorded; the frontend validated the descriptor, this guards the other callers. */
    private static void validate(PassSpec spec) {
        List<PassSpec.@Nullable Color> colors = spec.colors();
        boolean attachment = spec.depth() != null;

        if (colors.size() > MAX_COLOR_TARGETS) {
            throw new IllegalArgumentException("A render pass has at most " + MAX_COLOR_TARGETS + " colour attachments, not " + colors.size());
        }

        for (PassSpec.Color color : colors) {
            if (color != null) {
                attachment = true;

                if (color.target().dxgiFormat() == Enums.Format.UNKNOWN) {
                    throw new IllegalArgumentException("A colour attachment has no render target format");
                }
            }
        }

        if (!attachment) {
            throw new IllegalArgumentException("A render pass needs at least one attachment");
        }

        if (spec.areaWidth() <= 0 || spec.areaHeight() <= 0 || spec.viewportWidth() <= 0 || spec.viewportHeight() <= 0) {
            throw new IllegalArgumentException("The render area " + spec.areaWidth() + "x" + spec.areaHeight() + " / viewport "
                    + spec.viewportWidth() + "x" + spec.viewportHeight() + " is empty");
        }
    }

    DeviceContext context() {
        return this.ctx;
    }

    PassBinder binder() {
        return this.binder;
    }

    /** Idempotent, never throws; called only by {@code D3D12CommandEncoder.shutdown()}. */
    @Override
    public void close() {
        try {
            this.binder.close();
        } catch (RuntimeException e) {
            D3D12Log.error("Failed to release the pass binder", e);
        }

        this.fan.close();
    }
}
