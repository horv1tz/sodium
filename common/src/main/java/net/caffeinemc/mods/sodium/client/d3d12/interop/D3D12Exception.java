package net.caffeinemc.mods.sodium.client.d3d12.interop;

/**
 * Thrown when a Direct3D 12 / DXGI / D3DCompiler call fails or the native libraries cannot be used.
 */
public class D3D12Exception extends RuntimeException {
    private final int hresult;

    public D3D12Exception(String message) {
        super(message);
        this.hresult = Hr.E_FAIL;
    }

    public D3D12Exception(String message, Throwable cause) {
        super(message, cause);
        this.hresult = Hr.E_FAIL;
    }

    public D3D12Exception(String message, int hresult) {
        super(String.format("%s (HRESULT 0x%08X)", message, hresult));
        this.hresult = hresult;
    }

    public int getHresult() {
        return this.hresult;
    }

    /**
     * @return true if the failure means the GPU device was removed, reset or hung and must be recreated
     */
    public boolean isDeviceLoss() {
        return Hr.isDeviceLoss(this.hresult);
    }

    public boolean isOutOfMemory() {
        return this.hresult == Hr.E_OUTOFMEMORY;
    }
}
