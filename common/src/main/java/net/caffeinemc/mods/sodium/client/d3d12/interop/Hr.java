package net.caffeinemc.mods.sodium.client.d3d12.interop;

/**
 * HRESULT constants (values taken from winerror.h) and failure checking.
 */
public final class Hr {
    public static final int S_OK = 0;
    public static final int S_FALSE = 1;

    public static final int E_ABORT = 0x80004004;
    public static final int E_ACCESSDENIED = 0x80070005;
    public static final int E_FAIL = 0x80004005;
    public static final int E_INVALIDARG = 0x80070057;
    public static final int E_NOINTERFACE = 0x80004002;
    public static final int E_NOTIMPL = 0x80004001;
    public static final int E_OUTOFMEMORY = 0x8007000E;
    public static final int E_POINTER = 0x80004003;

    public static final int DXGI_STATUS_OCCLUDED = 0x087A0001;
    public static final int DXGI_STATUS_MODE_CHANGED = 0x087A0007;
    public static final int DXGI_STATUS_UNOCCLUDED = 0x087A0009;

    public static final int DXGI_ERROR_INVALID_CALL = 0x887A0001;
    public static final int DXGI_ERROR_NOT_FOUND = 0x887A0002;
    public static final int DXGI_ERROR_MORE_DATA = 0x887A0003;
    public static final int DXGI_ERROR_UNSUPPORTED = 0x887A0004;
    public static final int DXGI_ERROR_DEVICE_REMOVED = 0x887A0005;
    public static final int DXGI_ERROR_DEVICE_HUNG = 0x887A0006;
    public static final int DXGI_ERROR_DEVICE_RESET = 0x887A0007;
    public static final int DXGI_ERROR_WAS_STILL_DRAWING = 0x887A000A;
    public static final int DXGI_ERROR_DRIVER_INTERNAL_ERROR = 0x887A0020;
    public static final int DXGI_ERROR_NOT_CURRENTLY_AVAILABLE = 0x887A0022;
    public static final int DXGI_ERROR_WAIT_TIMEOUT = 0x887A0027;

    private Hr() {
    }

    public static boolean succeeded(int hr) {
        return hr >= 0;
    }

    public static boolean failed(int hr) {
        return hr < 0;
    }

    public static boolean isDeviceLoss(int hr) {
        return hr == DXGI_ERROR_DEVICE_REMOVED
                || hr == DXGI_ERROR_DEVICE_HUNG
                || hr == DXGI_ERROR_DEVICE_RESET
                || hr == DXGI_ERROR_DRIVER_INTERNAL_ERROR;
    }

    /**
     * Throws a {@link D3D12Exception} if the HRESULT indicates failure.
     *
     * @return the (non-failing) HRESULT, so that S_FALSE / status codes can still be inspected
     */
    public static int check(int hr, String what) {
        if (hr < 0) {
            throw new D3D12Exception(what + " failed", hr);
        }

        return hr;
    }
}
