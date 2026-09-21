package fabscreen.platform.lib.uvc;

/**
 * Minimal JNI bridge to the Linux V4L2 API used by USB Video Class cameras.
 *
 * <p>The native backend intentionally accepts only MJPEG, JPEG, and YUYV
 * capture formats. Callers should check {@link #AVAILABLE} before probing
 * devices so builds without the native library fail closed.</p>
 */
public final class V4l2Native {
    public static final boolean AVAILABLE;

    static {
        boolean loaded;
        try {
            System.loadLibrary("uvc-v4l2");
            loaded = true;
        } catch (Throwable ignored) {
            loaded = false;
        }
        AVAILABLE = loaded;
    }

    private V4l2Native() {
    }

    public static native String probe(String path, int maxWidth, int maxHeight);

    public static native long open(String path, int width, int height, int fps);

    public static native String format(long handle);

    public static native int width(long handle);

    public static native int height(long handle);

    public static native int stride(long handle);

    public static native int fps(long handle);

    public static native byte[] readFrame(long handle, int timeoutMs);

    public static native void close(long handle);

    public static native String lastError();
}
