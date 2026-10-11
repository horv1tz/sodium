// Owner: WP4 (descriptors) - implemented over PagedBumpAllocator; WP4 owner tests it under Wine 11 (tiny pages force paging)
package net.caffeinemc.mods.sodium.client.d3d12.descriptor;

import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12DescriptorHeap;
import net.caffeinemc.mods.sodium.client.d3d12.interop.d3d12.D3D12Device;

import java.util.ArrayList;
import java.util.List;

/**
 * A per-frame-slot ring of shader-visible descriptor heap pages for one heap type (CBV/SRV/UAV or SAMPLER). The binder
 * copies the staging descriptors of the bound textures/samplers into consecutive entries and binds them as a descriptor
 * table.
 *
 * <p>Design (proposal A section 2.d, decision D8): every frame slot owns a list of pages (shader-visible heaps) and a
 * {@link PagedBumpAllocator}. {@link #beginFrame} rewinds the slot - legal only after the slot's previous frame
 * completed ({@code submit()} guarantees it). A request that does not fit starts the next page (created on demand,
 * retained afterwards).
 *
 * <p><b>Heap generation ({@link #epoch()}).</b> A ring-global, strictly increasing counter that is <em>never reset</em>.
 * It changes whenever the heap a table handle would point into changes: on every {@link #beginFrame} (another frame
 * slot's heap is selected, or - with the same heap - the page was rewound and its contents will be overwritten) and on
 * every page flip. A consumer that bound heaps stores the value; if it differs, the consumer must
 * {@code SetDescriptorHeaps} again, re-dirty both tables and forget everything it remembers about bound tables.
 * (An earlier design used a per-slot page counter that did not change when {@code beginFrame} selected the other slot's
 * heap: a content-keyed table cache then returned a handle of the previous frame's heap.)
 *
 * <p><b>Table cache.</b> The ring owns the {@link DescriptorTableCache} of its tables ({@link #tableCache()}) and clears
 * it inside the same code path that bumps the generation, so no consumer can forget to.
 *
 * <p><b>Call protocol (heap switch before fills).</b> The descriptor-table GPU handle of a draw must lie inside the heap
 * that is <em>currently set</em>; a page flip in the middle of a table fill would hand out a handle of a heap that is not
 * set yet. So the binder, before filling any table of a draw, calls {@link #reserve(int) reserve(16)} on <b>both</b>
 * rings (this is the only place a flip happens), compares both {@link #epoch()} values with the ones it last set heaps
 * for, re-sets the heaps and re-dirties the tables if either changed, and only then fills with
 * {@link #allocateReserved(int)} + immediately {@link #gpuHandle(long)}, which never flips (it throws if the reserve was
 * forgotten). {@link #allocate(int)} remains for callers that do not bind the result in the same breath.
 *
 * <p>Sizing: CBV/SRV/UAV pages of 32768 descriptors (2048 tables of 16), SAMPLER pages of 2048 (the hardware limit, 128
 * tables). 32 pages maximum. The first page of every slot is created eagerly so that {@code currentHeap()} is valid
 * before the first allocation (the pass begin needs it for {@code SetDescriptorHeaps}).
 *
 * <p>The GPU handle is derived from the page of the <em>most recent</em> allocation or reservation. No allocation of Java
 * objects on the hot path.
 *
 * <p>Threading: render thread only.
 */
public final class ShaderVisibleRing implements AutoCloseable {
    /** One shader-visible heap page; unit tests provide fakes ({@link #heap()} may then return null). */
    public interface HeapPage {
        /** @return the native heap to pass to {@code SetDescriptorHeaps} (null only in fakes) */
        D3D12DescriptorHeap heap();

        /** @return the CPU descriptor handle of the first descriptor of the page */
        long cpuStart();

        /** @return the GPU descriptor handle of the first descriptor of the page */
        long gpuStart();

        void close();
    }

    @FunctionalInterface
    public interface PageFactory {
        HeapPage create(int pageDescriptors);
    }

    private static final int TABLE_DESCRIPTORS = DescriptorTableCache.TABLE_SIZE;
    private static final int MAX_CACHE_SLOTS = 2048;

    private static final class SlotRing {
        final List<HeapPage> pages = new ArrayList<>();
        PagedBumpAllocator allocator;
    }

    private final int heapType;
    private final int pageDescriptors;
    private final int incrementSize;
    private final SlotRing[] slots;
    private final DescriptorTableCache tableCache;

    private SlotRing current;
    private int currentSlot;
    private int selectedSlot = -1;
    private int selectedPage = -1;
    private int generation;
    private long lastCpuStart;
    private long lastGpuStart;

    /** Production constructor: pages are shader-visible heaps of {@code heapType} ({@code Enums.DescriptorHeapType}). */
    public ShaderVisibleRing(D3D12Device device, int heapType, int pageDescriptors, int maxPages, int slotCount) {
        this(descriptors -> {
            D3D12DescriptorHeap heap = device.createDescriptorHeap(heapType, descriptors, true);

            return new HeapPage() {
                @Override
                public D3D12DescriptorHeap heap() {
                    return heap;
                }

                @Override
                public long cpuStart() {
                    return heap.getCPUDescriptorHandleForHeapStart();
                }

                @Override
                public long gpuStart() {
                    return heap.getGPUDescriptorHandleForHeapStart();
                }

                @Override
                public void close() {
                    heap.close();
                }
            };
        }, heapType, device.getDescriptorHandleIncrementSize(heapType), pageDescriptors, maxPages, slotCount);
    }

    /** Test constructor (fake pages). */
    public ShaderVisibleRing(PageFactory factory, int heapType, int incrementSize, int pageDescriptors, int maxPages,
                             int slotCount) {
        this.heapType = heapType;
        this.pageDescriptors = pageDescriptors;
        this.incrementSize = incrementSize;
        this.slots = new SlotRing[slotCount];
        this.tableCache = new DescriptorTableCache(Math.min(MAX_CACHE_SLOTS, 2 * (pageDescriptors / TABLE_DESCRIPTORS)));

        for (int i = 0; i < slotCount; i++) {
            SlotRing slot = new SlotRing();

            slot.allocator = new PagedBumpAllocator(pageDescriptors, maxPages, index -> {
                while (slot.pages.size() <= index) {
                    slot.pages.add(factory.create(pageDescriptors));
                }
            });

            this.slots[i] = slot;
        }

        this.current = this.slots[0];
        this.currentSlot = 0;
        this.selectPage(true);
    }

    /**
     * Selects and rewinds the slot of the frame about to be recorded. Always bumps {@link #epoch()} and clears the table
     * cache: even when the same heap is selected again its contents are about to be overwritten.
     */
    public void beginFrame(int slot) {
        this.current = this.slots[slot];
        this.currentSlot = slot;
        this.current.allocator.reset();
        this.selectPage(true);
    }

    /**
     * Makes sure {@code count} descriptors fit in the current page, starting the next page (and bumping
     * {@link #epoch()}) if they do not. Allocates nothing. Call before comparing {@link #epoch()} and before any fill of
     * a draw; see the class comment.
     *
     * @param count descriptors the caller is about to take (16 per table)
     * @throws IllegalStateException when the frame exhausted all pages
     */
    public void reserve(int count) {
        if (this.current.allocator.reserve(count)) {
            this.selectPage(false);
        }
    }

    /**
     * Takes {@code count} descriptors that {@link #reserve} guaranteed: <b>never starts a page</b>, so the page (and the
     * heap set by the binder) is the one the previous {@code reserve} selected.
     *
     * @return the CPU descriptor handle of the first of {@code count} consecutive descriptors in the current page
     * @throws IllegalStateException when the current page has no room (a missing {@link #reserve})
     */
    public long allocateReserved(int count) {
        int index = this.current.allocator.allocateFitting(count);

        return D3D12DescriptorHeap.handleAt(this.lastCpuStart, index, this.incrementSize);
    }

    /**
     * May start a new page (bumping {@link #epoch()}); the binder uses {@link #reserve} + {@link #allocateReserved}.
     *
     * @param count descriptors needed (16 per table)
     * @return the CPU descriptor handle of the first of {@code count} consecutive descriptors in the current page
     * @throws IllegalStateException when the frame exhausted all pages
     */
    public long allocate(int count) {
        int index = this.current.allocator.allocate(count);

        if (this.current.allocator.page() != this.selectedPage) {
            this.selectPage(false);
        }

        return D3D12DescriptorHeap.handleAt(this.lastCpuStart, index, this.incrementSize);
    }

    /** @return the GPU handle for a CPU handle returned by the most recent {@link #allocate} / {@link #allocateReserved} */
    public long gpuHandle(long cpuHandle) {
        return this.lastGpuStart + (cpuHandle - this.lastCpuStart);
    }

    /** @return the heap of the current page: pass it to {@code SetDescriptorHeaps} */
    public D3D12DescriptorHeap currentHeap() {
        return this.current.pages.get(this.current.allocator.page()).heap();
    }

    /**
     * @return the ring-global heap generation: strictly increasing, never reset; changes on every {@link #beginFrame} and
     * every page flip (see the class comment)
     */
    public int epoch() {
        return this.generation;
    }

    /** @return the cache of built tables of the current generation; cleared by the ring itself */
    public DescriptorTableCache tableCache() {
        return this.tableCache;
    }

    /** @return the current page object (tests; {@code heap()} is null in fakes) */
    public HeapPage currentPage() {
        return this.current.pages.get(this.current.allocator.page());
    }

    /** @return the frame slot whose pages are current (tests) */
    public int slot() {
        return this.currentSlot;
    }

    /** @return the page index inside the current slot (tests) */
    public int page() {
        return this.current.allocator.page();
    }

    /** @return the cursor inside the current page (tests) */
    public int cursor() {
        return this.current.allocator.cursor();
    }

    public int incrementSize() {
        return this.incrementSize;
    }

    public int heapType() {
        return this.heapType;
    }

    public int pageDescriptors() {
        return this.pageDescriptors;
    }

    /** Releases every page. Call only after the GPU is idle. */
    @Override
    public void close() {
        for (SlotRing slot : this.slots) {
            for (HeapPage page : slot.pages) {
                page.close();
            }

            slot.pages.clear();
        }
    }

    private void selectPage(boolean rewound) {
        int page = this.current.allocator.page();
        HeapPage heap = this.current.pages.get(page);

        this.lastCpuStart = heap.cpuStart();
        this.lastGpuStart = heap.gpuStart();

        if (rewound || this.currentSlot != this.selectedSlot || page != this.selectedPage) {
            this.selectedSlot = this.currentSlot;
            this.selectedPage = page;
            this.generation++;
            this.tableCache.clear();
        }
    }
}
