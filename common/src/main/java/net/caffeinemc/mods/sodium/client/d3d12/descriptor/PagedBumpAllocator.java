// Owner: WP4 (descriptors) - fully implemented pure logic; WP4 owner adds RingTest
package net.caffeinemc.mods.sodium.client.d3d12.descriptor;

/**
 * The bump allocation policy of a shader-visible descriptor ring for one frame slot (decision D8 of ARCHITECTURE.md):
 * descriptors are handed out linearly from the current <em>page</em> (one shader-visible heap); when a request does not
 * fit, the next page is started. {@link #reset()} rewinds to page 0 once the slot's previous frame completed.
 *
 * <p>{@link #epoch()} increases whenever the <em>current page changes</em> (page switch or a reset that returns to page
 * 0 from another page). It is a per-allocator counter for tests and pure logic; the consumer-facing, ring-global
 * "heap generation" (which also changes when another frame slot is selected) lives in {@code ShaderVisibleRing.epoch()}.
 *
 * <p><b>Page flips must happen before the descriptors are written, never during.</b> A descriptor table handed to
 * {@code SetGraphicsRootDescriptorTable} must lie inside the heap that is currently set with {@code SetDescriptorHeaps};
 * if the page flipped inside {@link #allocate} while the previous heap is still set, the bound handle would point into a
 * heap that is not set. The consumer therefore calls {@link #reserve(int)} first (this is where a flip may happen),
 * compares the generation, re-sets the heaps if it changed, and only then calls {@link #allocateFitting(int)}, which
 * never flips and throws if the reservation was forgotten.
 *
 * <p>Pages are created lazily through {@link PageSource#ensurePage}; they are retained across frames (the high-water mark
 * is the steady-state size). Exceeding {@code maxPages} throws {@link IllegalStateException} - at 32 pages of 32768
 * descriptors (1M descriptors in a single frame) that is a hard error, not an expected condition.
 *
 * <p>Not thread-safe (render thread).
 */
public final class PagedBumpAllocator {
    /** Creates the shader-visible heap of a page on demand. */
    @FunctionalInterface
    public interface PageSource {
        /** Makes sure page {@code index} exists (idempotent). */
        void ensurePage(int index);
    }

    private final int pageCapacity;
    private final int maxPages;
    private final PageSource source;

    private int page;
    private int cursor;
    private int epoch;

    /**
     * @param pageCapacity descriptors per page; every allocation must fit in one page
     * @param maxPages     hard limit on pages
     */
    public PagedBumpAllocator(int pageCapacity, int maxPages, PageSource source) {
        if (pageCapacity < 1 || maxPages < 1) {
            throw new IllegalArgumentException("invalid ring geometry");
        }

        this.pageCapacity = pageCapacity;
        this.maxPages = maxPages;
        this.source = source;
        source.ensurePage(0);
    }

    /**
     * Allocation-free: returns the first index; the page it belongs to is {@link #page()} (read it right after the call,
     * a later allocation may switch pages). <b>May start a new page</b>; callers that bind the result as a descriptor
     * table use {@link #reserve} + {@link #allocateFitting} instead (see the class comment).
     *
     * @param count descriptors needed, {@code 1..pageCapacity}
     * @return the first index inside {@link #page()}; indices {@code index .. index + count - 1} are exclusively owned
     * @throws IllegalStateException if the ring is exhausted ({@code maxPages} pages)
     */
    public int allocate(int count) {
        this.reserve(count);

        int index = this.cursor;
        this.cursor += count;

        return index;
    }

    /**
     * Makes sure {@code count} descriptors fit into the current page, starting the next page if they do not (the only
     * place besides {@link #allocate} where the page - and with it {@link #epoch()} - changes). Allocates nothing.
     * Afterwards {@link #allocateFitting(int)} of up to {@code count} descriptors cannot flip.
     *
     * @param count descriptors the caller is about to allocate, {@code 1..pageCapacity}
     * @return true if a new page was started
     * @throws IllegalStateException if the ring is exhausted ({@code maxPages} pages)
     */
    public boolean reserve(int count) {
        if (count < 1 || count > this.pageCapacity) {
            throw new IllegalArgumentException("cannot allocate " + count + " descriptors from pages of " + this.pageCapacity);
        }

        if (this.cursor + count <= this.pageCapacity) {
            return false;
        }

        if (this.page + 1 >= this.maxPages) {
            throw new IllegalStateException("Direct3D 12 descriptor ring exhausted (" + this.maxPages + " pages of "
                    + this.pageCapacity + " descriptors in one frame)");
        }

        this.page++;
        this.source.ensurePage(this.page);
        this.cursor = 0;
        this.epoch++;

        return true;
    }

    /**
     * Like {@link #allocate} but <b>never starts a page</b>: the descriptors must fit in the current page (call
     * {@link #reserve} first).
     *
     * @throws IllegalStateException if the current page has no room for {@code count} descriptors (a missing reserve)
     */
    public int allocateFitting(int count) {
        if (count < 1 || count > this.pageCapacity) {
            throw new IllegalArgumentException("cannot allocate " + count + " descriptors from pages of " + this.pageCapacity);
        }

        if (this.cursor + count > this.pageCapacity) {
            throw new IllegalStateException("descriptor page flipped between reserve and allocate (missing reserve of " + count + ")");
        }

        int index = this.cursor;
        this.cursor += count;

        return index;
    }

    /** Rewinds to the start of page 0. Only legal after the slot's previous frame completed on the GPU. */
    public void reset() {
        if (this.page != 0) {
            this.epoch++;
        }

        this.page = 0;
        this.cursor = 0;
    }

    public int page() {
        return this.page;
    }

    public int cursor() {
        return this.cursor;
    }

    public int epoch() {
        return this.epoch;
    }

    public int pageCapacity() {
        return this.pageCapacity;
    }
}
