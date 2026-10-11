package net.caffeinemc.mods.sodium.client.d3d12.interop;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.StructLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds struct and union layouts following the Windows x64 C ABI (natural alignment, tail padding), inserting the
 * padding automatically so that layouts can be written exactly as the C declaration reads.
 */
public final class Struct {
    private Struct() {
    }

    /**
     * Declares a struct. Members must be named ({@code JAVA_INT.withName("Format")}).
     */
    public static StructLayout of(String name, MemoryLayout... members) {
        List<MemoryLayout> layout = new ArrayList<>(members.length * 2);
        long offset = 0;
        long maxAlignment = 1;

        for (MemoryLayout member : members) {
            long alignment = member.byteAlignment();
            long padding = (alignment - (offset % alignment)) % alignment;

            if (padding > 0) {
                layout.add(MemoryLayout.paddingLayout(padding));
            }

            layout.add(member);
            offset += padding + member.byteSize();
            maxAlignment = Math.max(maxAlignment, alignment);
        }

        long tail = (maxAlignment - (offset % maxAlignment)) % maxAlignment;

        if (tail > 0) {
            layout.add(MemoryLayout.paddingLayout(tail));
        }

        return MemoryLayout.structLayout(layout.toArray(MemoryLayout[]::new)).withName(name);
    }

    /**
     * Declares a union, padded to a multiple of its alignment like a C union.
     */
    public static MemoryLayout union(String name, MemoryLayout... members) {
        long size = 0;
        long maxAlignment = 1;

        for (MemoryLayout member : members) {
            size = Math.max(size, member.byteSize());
            maxAlignment = Math.max(maxAlignment, member.byteAlignment());
        }

        long tail = (maxAlignment - (size % maxAlignment)) % maxAlignment;
        MemoryLayout[] all = members;

        if (tail > 0) {
            // A union is padded by adding an (unnamed) member that is large enough to round the size up.
            all = new MemoryLayout[members.length + 1];
            System.arraycopy(members, 0, all, 0, members.length);
            all[members.length] = MemoryLayout.paddingLayout(size + tail);
        }

        return MemoryLayout.unionLayout(all).withName(name);
    }

    /**
     * @return the byte offset of the (possibly nested) named member
     */
    public static long offset(MemoryLayout layout, String... path) {
        PathElement[] elements = new PathElement[path.length];

        for (int i = 0; i < path.length; i++) {
            elements[i] = PathElement.groupElement(path[i]);
        }

        return layout.byteOffset(elements);
    }
}
