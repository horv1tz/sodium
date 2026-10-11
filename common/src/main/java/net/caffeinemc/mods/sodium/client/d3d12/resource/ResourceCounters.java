// Owner: WP1 (device core) - implemented
package net.caffeinemc.mods.sodium.client.d3d12.resource;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Number of live (created, not yet destroyed) objects per kind. The device logs the survivors at shutdown: buffers
 * and textures are owned by the frontend and not force-closed (the device release frees them).
 */
public final class ResourceCounters {
    private final AtomicInteger buffers = new AtomicInteger();
    private final AtomicInteger textures = new AtomicInteger();
    private final AtomicInteger views = new AtomicInteger();
    private final AtomicInteger samplers = new AtomicInteger();

    public int buffers() {
        return this.buffers.get();
    }

    public int textures() {
        return this.textures.get();
    }

    public int views() {
        return this.views.get();
    }

    public int samplers() {
        return this.samplers.get();
    }

    void bufferCreated() {
        this.buffers.incrementAndGet();
    }

    void bufferDestroyed() {
        this.buffers.decrementAndGet();
    }

    void textureCreated() {
        this.textures.incrementAndGet();
    }

    void textureDestroyed() {
        this.textures.decrementAndGet();
    }

    void viewCreated() {
        this.views.incrementAndGet();
    }

    void viewClosed() {
        this.views.decrementAndGet();
    }

    void samplerCreated() {
        this.samplers.incrementAndGet();
    }

    void samplerDestroyed() {
        this.samplers.decrementAndGet();
    }
}
