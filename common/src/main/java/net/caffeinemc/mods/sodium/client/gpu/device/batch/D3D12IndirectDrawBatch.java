package net.caffeinemc.mods.sodium.client.gpu.device.batch;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.caffeinemc.mods.sodium.api.memory.MemoryIntrinsics;
import net.caffeinemc.mods.sodium.client.gpu.device.context.DrawContext;
import net.caffeinemc.mods.sodium.client.gpu.device.context.IndirectDrawContext;
import net.caffeinemc.mods.sodium.client.util.UInt32;
import org.lwjgl.system.MemoryUtil;

public final class D3D12IndirectDrawBatch extends MultiDrawBatch {
    // The layout of D3D12_DRAW_INDEXED_ARGUMENTS, which is what ExecuteIndirect consumes for an indexed draw.
    private static final int INDEX_COUNT_OFFSET = 0;
    private static final int INSTANCE_COUNT_OFFSET = 4;
    private static final int START_INDEX_OFFSET = 8;
    private static final int BASE_VERTEX_OFFSET = 12;
    private static final int START_INSTANCE_OFFSET = 16;
    private static final int COMMAND_SIZE = 20;

    private final long pCommands;
    private long offset;

    public D3D12IndirectDrawBatch(int capacity) {
        this.pCommands = MemoryUtil.nmemAlignedAlloc(32, (long) COMMAND_SIZE * capacity);
        MemoryUtil.memSet(this.pCommands, 0x0, (long) COMMAND_SIZE * capacity);

        for (int i = 0; i < capacity; i++) {
            MemoryIntrinsics.putInt(this.pCommands + ((long) i * COMMAND_SIZE) + INSTANCE_COUNT_OFFSET, 1);
            MemoryIntrinsics.putInt(this.pCommands + ((long) i * COMMAND_SIZE) + START_INSTANCE_OFFSET, 0);
        }
    }

    @Override
    public void put(int size, int elementCount, int baseVertex, long elementOffset) {
        MemoryIntrinsics.putInt(this.pCommands + ((long) size * COMMAND_SIZE) + INDEX_COUNT_OFFSET, elementCount);
        MemoryIntrinsics.putInt(this.pCommands + ((long) size * COMMAND_SIZE) + BASE_VERTEX_OFFSET, UInt32.uncheckedDowncast(baseVertex));
        MemoryIntrinsics.putInt(this.pCommands + ((long) size * COMMAND_SIZE) + START_INDEX_OFFSET, UInt32.uncheckedDowncast(elementOffset));

        this.updateMaxElementCount(elementCount);
    }

    @Override
    public void draw(DrawContext dc) {
        IndirectDrawContext context = (IndirectDrawContext) dc;
        var byteSize = this.size * COMMAND_SIZE;

        GpuBufferSlice commands = context.mappedView.slice().slice(this.offset, byteSize);

        context.getPass().drawIndexedIndirect(commands, this.size);
    }

    @Override
    public void prepare(DrawContext dc) {
        IndirectDrawContext context = (IndirectDrawContext) dc;
        var byteSize = this.size * COMMAND_SIZE;

        var offset = context.addCommand(byteSize);

        MemoryUtil.memCopy(this.pCommands, MemoryUtil.memAddress(context.mappedView.data()) + offset, byteSize);

        this.offset = offset;
    }

    @Override
    public void delete() {
        MemoryUtil.nmemAlignedFree(this.pCommands);
    }
}
