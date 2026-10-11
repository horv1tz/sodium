// Owner: WP4 (descriptors) - implemented; WP4 owner adds CpuDescriptorAllocatorTest (fake PageFactory)
package net.caffeinemc.mods.sodium.client.d3d12.descriptor;

import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12DescriptorHeap;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12Device;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Allocator of CPU-only (non-shader-visible) descriptors: one per texture view (SRV), per attached texture mip
 * (RTV/DSV), per sampler, per swap-chain back buffer. Pages of heaps are added on demand; freed slots are recycled.
 *
 * <p>Descriptors are <em>copied by value</em> into the shader-visible rings at draw time, so no ring entry ever refers
 * back to a staging slot, and a slot may be reused as soon as the owning object's deferred destroy ran.
 *
 * <p>Recommended page capacities: CBV/SRV/UAV 4096, RTV 512, DSV 128, SAMPLER 1024 (limit 2048). Thread-safe
 * ({@code synchronized}; creation paths are not hot). {@link #free} must only be called from deferral entries or after
 * a GPU idle.
 */
public final class CpuDescriptorAllocator implements AutoCloseable {
    /** One non-shader-visible heap; unit tests provide fakes. */
    public interface HeapPage {
        /** @return the CPU descriptor handle of the first descriptor of the page */
        long cpuStart();

        void close();
    }

    @FunctionalInterface
    public interface PageFactory {
        HeapPage create(int capacity);
    }

    private static final class Page {
        final HeapPage heap;
        final int capacity;
        int nextUnused;
        int[] recycled = new int[16];
        int recycledCount;

        Page(HeapPage heap, int capacity) {
            this.heap = heap;
            this.capacity = capacity;
        }
    }

    private final PageFactory factory;
    private final int incrementSize;
    private final int pageCapacity;
    private final List<Page> pages = new ArrayList<>();
    private boolean closed;

    /** Production constructor: pages are non-shader-visible heaps of {@code heapType} ({@code Enums.DescriptorHeapType}). */
    public CpuDescriptorAllocator(D3D12Device device, int heapType, int pageCapacity) {
        this(capacity -> {
            D3D12DescriptorHeap heap = device.createDescriptorHeap(heapType, capacity, false);

            return new HeapPage() {
                @Override
                public long cpuStart() {
                    return heap.getCPUDescriptorHandleForHeapStart();
                }

                @Override
                public void close() {
                    heap.close();
                }
            };
        }, device.getDescriptorHandleIncrementSize(heapType), pageCapacity);
    }

    /** Test constructor. */
    public CpuDescriptorAllocator(PageFactory factory, int incrementSize, int pageCapacity) {
        if (pageCapacity < 1 || incrementSize < 1) {
            throw new IllegalArgumentException("invalid descriptor allocator geometry");
        }

        this.factory = factory;
        this.incrementSize = incrementSize;
        this.pageCapacity = pageCapacity;
    }

    /** @return the size in bytes of one descriptor of this heap type */
    public int incrementSize() {
        return this.incrementSize;
    }

    /** @return the CPU handle of a free descriptor slot */
    public synchronized long allocate() {
        if (this.closed) {
            throw new IllegalStateException("descriptor allocator is closed");
        }

        for (Page page : this.pages) {
            if (page.recycledCount > 0) {
                return this.handle(page, page.recycled[--page.recycledCount]);
            }

            if (page.nextUnused < page.capacity) {
                return this.handle(page, page.nextUnused++);
            }
        }

        Page page = new Page(this.factory.create(this.pageCapacity), this.pageCapacity);

        this.pages.add(page);

        return this.handle(page, page.nextUnused++);
    }

    /**
     * Returns a slot to the allocator. Freeing a handle twice or a foreign handle is a programming error and is
     * reported with {@link IllegalArgumentException} (foreign) or silently corrupts the free list (double free): owners
     * must free exactly once.
     */
    public synchronized void free(long handle) {
        for (Page page : this.pages) {
            long start = page.heap.cpuStart();
            long end = start + (long) page.capacity * this.incrementSize;

            if (handle >= start && handle < end) {
                if (page.recycledCount == page.recycled.length) {
                    page.recycled = Arrays.copyOf(page.recycled, page.recycledCount * 2);
                }

                page.recycled[page.recycledCount++] = (int) ((handle - start) / this.incrementSize);

                return;
            }
        }

        throw new IllegalArgumentException("descriptor handle 0x" + Long.toHexString(handle) + " does not belong to this allocator");
    }

    /** @return the number of descriptors currently allocated (for leak tests) */
    public synchronized int liveCount() {
        int live = 0;

        for (Page page : this.pages) {
            live += page.nextUnused - page.recycledCount;
        }

        return live;
    }

    @Override
    public synchronized void close() {
        this.closed = true;

        for (Page page : this.pages) {
            page.heap.close();
        }

        this.pages.clear();
    }

    private long handle(Page page, int index) {
        return page.heap.cpuStart() + (long) index * this.incrementSize;
    }
}
