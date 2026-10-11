// Owner: WP2 (commands + transient memory) - fully implemented pure logic; WP2 owner adds TransientAllocatorTest
package net.caffeinemc.mods.sodium.client.d3d12.memory;

import net.caffeinemc.mods.sodium.client.d3d12.core.Alignment;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The per-frame bump-block algorithm behind {@code TransientMemory}, written independently from the specification in
 * spec 03 section 10.2 because it defines <em>observable</em> behaviour:
 *
 * <ol>
 *     <li>{@code alignment > maxAlignment} -> {@link IllegalArgumentException} (checked first); {@code alignment < 1} too.</li>
 *     <li>{@code size == blockSize} -> a whole fresh block at offset 0; the bump block is not touched.</li>
 *     <li>{@code size > blockSize} -> a dedicated <em>special</em> block of exactly {@code size}, always freed at rotation.</li>
 *     <li>otherwise bump-allocate in the current block (taking a block if there is none); if {@code size} does not fit
 *     but {@code minimumAllocation} does, return a <em>shorter</em> allocation of
 *     {@code (blockSize - aligned) / elementSize * elementSize} bytes that consumes the rest of the block (callers must
 *     use the returned size); else take a new block and return {@code (newBlock, 0, size)}, which becomes the bump block
 *     only if the old one had {@code currentOffset > size}.</li>
 *     <li>{@link #rotate()} (end of frame): forget the bump block, free every block nobody asked for this frame (shrink
 *     policy), and return the action that moves the blocks used this frame back to the free list and frees the special
 *     blocks. <b>When that action runs is the caller's synchronisation decision</b>: for GPU-visible memory it must be
 *     deferred until the GPU finished the frame (never recycle immediately on Direct3D 12).</li>
 * </ol>
 *
 * <p>Differential tests run this class against {@code com.mojang.renderpearl.backend.util.TransientBlockAllocator} as an
 * oracle (the reference class is on the classpath). Not thread-safe (render thread).
 *
 * @param <B> block type
 */
public final class BlockAllocator<B> {
    /** Creates and destroys blocks. */
    public interface Source<B> {
        B alloc(long size);

        void free(B block);
    }

    /** Result of {@link #allocate}: {@code size} may be smaller than requested (see rule 4). */
    public record Allocation<B>(B block, long offset, long size) {
    }

    private final long blockSize;
    private final long maxAlignment;
    private final Source<B> source;
    private final Consumer<B> onBlockUse;

    private final List<B> specialBlocks = new ArrayList<>();
    private final List<B> freeBlocks = new ArrayList<>();
    private final List<B> usedBlocks = new ArrayList<>();
    private @Nullable B currentBlock;
    private long currentOffset;

    public BlockAllocator(long blockSize, long maxAlignment, Source<B> source, Consumer<B> onBlockUse) {
        this.blockSize = blockSize;
        this.maxAlignment = maxAlignment;
        this.source = source;
        this.onBlockUse = onBlockUse;
        this.currentOffset = blockSize;
    }

    public long blockSize() {
        return this.blockSize;
    }

    public boolean canAllocateInBlock(long size, long alignment) {
        return size <= this.blockSize && alignment <= this.maxAlignment;
    }

    public boolean canAllocateInCurrentBlock(long size, long alignment) {
        if (this.currentBlock == null && this.canAllocateInBlock(size, alignment)) {
            return true;
        }

        long aligned = Alignment.alignUp(this.currentOffset, alignment);

        return size <= this.blockSize - aligned && alignment <= this.maxAlignment;
    }

    public Allocation<B> allocate(long size, long alignment, long minimumAllocation, long elementSize) {
        if (alignment > this.maxAlignment) {
            throw new IllegalArgumentException("Alignment requirement over maximum supported alignment");
        }

        if (alignment < 1L) {
            throw new IllegalArgumentException("Alignment must be at least 1: " + alignment);
        }

        if (size == this.blockSize) {
            return new Allocation<>(this.takeBlock(), 0L, this.blockSize);
        }

        if (!this.canAllocateInBlock(size, alignment)) {
            B special = this.source.alloc(size);

            this.onBlockUse.accept(special);
            this.specialBlocks.add(special);

            return new Allocation<>(special, 0L, size);
        }

        if (this.currentBlock == null) {
            this.currentBlock = this.takeBlock();
            this.currentOffset = 0L;
        }

        if (this.canAllocateInCurrentBlock(size, alignment)) {
            long aligned = Alignment.alignUp(this.currentOffset, alignment);

            this.currentOffset = aligned + size;

            return new Allocation<>(this.currentBlock, aligned, size);
        }

        if (this.canAllocateInCurrentBlock(minimumAllocation, alignment)) {
            long aligned = Alignment.alignUp(this.currentOffset, alignment);
            long shorter = (this.blockSize - aligned) / elementSize * elementSize;

            this.currentOffset = aligned + shorter;

            return new Allocation<>(this.currentBlock, aligned, shorter);
        }

        B fresh = this.takeBlock();

        if (this.currentOffset > size) {
            this.currentBlock = fresh;
            this.currentOffset = size;
        }

        return new Allocation<>(fresh, 0L, size);
    }

    private B takeBlock() {
        if (this.freeBlocks.isEmpty()) {
            this.freeBlocks.add(this.source.alloc(this.blockSize));
        }

        B block = this.freeBlocks.remove(this.freeBlocks.size() - 1);

        this.onBlockUse.accept(block);
        this.usedBlocks.add(block);

        return block;
    }

    /**
     * Ends the frame. Blocks that were not used at all this frame are freed immediately; the returned action recycles the
     * used blocks and frees the special blocks. The action must run exactly once, after the GPU finished with the blocks.
     */
    public Runnable rotate() {
        this.currentBlock = null;
        this.currentOffset = this.blockSize;

        for (B idle : this.freeBlocks) {
            this.source.free(idle);
        }

        this.freeBlocks.clear();

        if (this.usedBlocks.isEmpty() && this.specialBlocks.isEmpty()) {
            return () -> {
            };
        }

        List<B> used = new ArrayList<>(this.usedBlocks);
        List<B> special = new ArrayList<>(this.specialBlocks);

        this.usedBlocks.clear();
        this.specialBlocks.clear();

        return () -> {
            this.freeBlocks.addAll(used);

            for (B block : special) {
                this.source.free(block);
            }
        };
    }

    /** Frees everything (two rotations: used -> free, free -> freed). Call only when the GPU is idle. */
    public void close() {
        this.rotate().run();
        this.rotate().run();
    }
}
