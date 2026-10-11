// Owner: WP1 (device core) - fully implemented shared helper
package net.caffeinemc.mods.sodium.client.d3d12.core;

/**
 * Geometry of a Direct3D 12 buffer footprint that carries {@code rows} rows of texels: the API works with tightly
 * packed rows at arbitrary byte offsets, D3D12 needs a 256 byte row pitch and a 512 byte aligned footprint offset.
 *
 * @param rowBytes   bytes of one row of texels (tightly packed)
 * @param rowPitch   bytes between the starts of two rows in the footprint ({@code rowBytes} rounded up to 256)
 * @param rows       number of rows
 * @param totalBytes bytes the footprint occupies in the buffer: {@code rowPitch * (rows - 1) + rowBytes}
 *                   (the last row needs no padding), 0 when {@code rows == 0}
 */
public record CopyLayout(int rowBytes, int rowPitch, int rows, long totalBytes) {
    public static CopyLayout of(int widthTexels, int bytesPerTexel, int rows) {
        if (widthTexels < 0 || bytesPerTexel <= 0 || rows < 0) {
            throw new IllegalArgumentException("invalid copy layout " + widthTexels + "x" + rows + "x" + bytesPerTexel);
        }

        int rowBytes = Math.multiplyExact(widthTexels, bytesPerTexel);
        int pitch = Alignment.rowPitch(rowBytes);
        long total = rows == 0 ? 0L : (long) pitch * (rows - 1) + rowBytes;

        return new CopyLayout(rowBytes, pitch, rows, total);
    }

    /** @return true if source rows and footprint rows have the same stride, so one bulk copy moves the whole image */
    public boolean tightlyPacked() {
        return this.rowBytes == this.rowPitch;
    }

    /**
     * @return the number of bytes to reserve in an upload arena for this footprint when the next footprint must start
     * on a 512 byte boundary (the footprint size rounded up to the placement alignment)
     */
    public long reservedBytes() {
        return Alignment.alignUp(this.totalBytes, Alignment.TEXTURE_PLACEMENT);
    }
}
