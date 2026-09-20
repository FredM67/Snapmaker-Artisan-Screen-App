package fabscreen.platform.base.camera;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.ImageFormat;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.os.SystemClock;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import fabscreen.platform.lib.uvc.V4l2Native;

/**
 * On-demand USB Video Class camera capture for the FabScreen web dashboard.
 *
 * <p>The manager only accepts stable IDs returned by {@link #getSources()} and
 * never accepts a caller-provided device node. A long-poll/streaming handler
 * calls {@link #awaitFrame(String, long, long)} repeatedly with an opaque,
 * page-scoped client ID and calls {@link #releaseClient(String)} when that page
 * stops viewing the camera. Capture starts for the first client and stops after
 * the final client is released (or its lease expires).</p>
 */
public final class UvcCameraManager {
    private static final String TAG = "FabScreenUVC";
    private static final String PREFERENCES = "artisan_uvc_camera";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_SOURCE_ID = "source_id";
    private static final String KEY_WIDTH = "width";
    private static final String KEY_HEIGHT = "height";
    private static final String KEY_FPS = "fps";
    public static final String MJPEG_SOURCE_ID = "mjpeg";
    public static final String RTSP_SOURCE_ID = "rtsp";

    public static final int DEFAULT_WIDTH = 1280;
    public static final int DEFAULT_HEIGHT = 720;
    public static final int DEFAULT_FPS = 5;
    public static final int MAX_WIDTH = 1920;
    public static final int MAX_HEIGHT = 1080;
    public static final int MAX_FPS = 10;

    private static final int MIN_WIDTH = 320;
    private static final int MIN_HEIGHT = 240;
    private static final int YUYV_MAX_WIDTH = 640;
    private static final int YUYV_MAX_HEIGHT = 480;
    private static final int YUYV_MAX_FPS = 5;
    private static final int JPEG_QUALITY = 75;
    private static final int MAX_JPEG_BYTES = 8 * 1024 * 1024;
    private static final int MAX_CLIENT_ID_LENGTH = 64;
    private static final int MAX_ACTIVE_CLIENTS = 8;
    private static final int MAX_VIDEO_NODE = 64;
    private static final int CAPTURE_READ_TIMEOUT_MS = 750;
    private static final long SOURCE_CACHE_MS = 10_000L;
    private static final long CLIENT_LEASE_MS = 30_000L;
    private static final long CLIENT_RELEASE_TOMBSTONE_MS = 10_000L;
    private static final long INVALID_FRAME_GRACE_MS = 10_000L;
    private static final long RETRY_DELAY_MS = 1_500L;
    private static final int MAX_RELEASED_CLIENTS = 64;

    private static volatile UvcCameraManager instance;

    private final SharedPreferences preferences;
    private final Context applicationContext;
    private final CameraStreamUrlStore streamUrlStore;
    private volatile boolean mjpegUrlConfigured;
    private volatile boolean rtspUrlConfigured;
    private final Object stateLock = new Object();
    private final Object sourceLock = new Object();
    private final Object frameLock = new Object();
    private final Map<String, Long> clientTouches = new HashMap<>();
    private final Map<String, Long> releasedClients = new LinkedHashMap<>();

    private volatile Settings settings;
    private volatile List<Source> cachedSources = Collections.emptyList();
    private volatile long nextSourceScanAt;
    private volatile Frame latestFrame;
    private volatile long nextSequence;

    private volatile long nativeHandle;
    private volatile MjpegStreamReader mjpegReader;
    private volatile RtspStreamReader rtspReader;
    private volatile Thread captureThread;
    private volatile Source activeSource;
    private volatile int actualWidth;
    private volatile int actualHeight;
    private volatile int actualFps;
    private volatile String activeFormat = "";
    private volatile String state = "idle";
    private volatile String message = "Camera idle";
    private volatile String lastError = "";
    private volatile long nextStartAt;

    public static UvcCameraManager getInstance(Context context) {
        UvcCameraManager current = instance;
        if (current != null) return current;
        synchronized (UvcCameraManager.class) {
            current = instance;
            if (current == null) {
                current = new UvcCameraManager(context.getApplicationContext());
                instance = current;
            }
            return current;
        }
    }

    UvcCameraManager(Context context) {
        applicationContext = context.getApplicationContext();
        preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        streamUrlStore = new CameraStreamUrlStore(context);
        String sourceId = preferences.getString(KEY_SOURCE_ID, "");
        String mjpegUrl = streamUrlStore.load(MJPEG_SOURCE_ID);
        String rtspUrl = streamUrlStore.load(RTSP_SOURCE_ID);
        mjpegUrlConfigured = !mjpegUrl.isEmpty();
        rtspUrlConfigured = !rtspUrl.isEmpty();
        settings = new Settings(
                preferences.getBoolean(KEY_ENABLED, true),
                sourceId,
                MJPEG_SOURCE_ID.equals(sourceId) ? mjpegUrl
                        : RTSP_SOURCE_ID.equals(sourceId) ? rtspUrl : "",
                boundedWidth(preferences.getInt(KEY_WIDTH, DEFAULT_WIDTH)),
                boundedHeight(preferences.getInt(KEY_HEIGHT, DEFAULT_HEIGHT)),
                boundedFps(preferences.getInt(KEY_FPS, DEFAULT_FPS))
        );
        if (!V4l2Native.AVAILABLE && !settings.isNetwork()) {
            state = "unavailable";
            message = "Native V4L2 support is unavailable";
            lastError = message;
        } else if (!settings.enabled) {
            state = "disabled";
            message = "USB camera is disabled";
        }
        if (V4l2Native.AVAILABLE) scanSources(true);
    }

    public Settings getSettings() {
        return settings;
    }

    /** Exposes only whether a saved address exists, never the credential-bearing URL. */
    public boolean hasSavedStreamUrl(String sourceId) {
        return MJPEG_SOURCE_ID.equals(sourceId) ? mjpegUrlConfigured
                : RTSP_SOURCE_ID.equals(sourceId) && rtspUrlConfigured;
    }

    public Status getStatus() {
        synchronized (stateLock) {
            pruneExpiredClientsLocked(SystemClock.elapsedRealtime());
            Source selected = selectSource(cachedSources, settings.sourceId);
            return new Status(
                    V4l2Native.AVAILABLE || settings.isNetwork(),
                    settings.enabled,
                    !clientTouches.isEmpty(),
                    (nativeHandle != 0L || ((mjpegReader != null || rtspReader != null)
                            && "streaming".equals(state)))
                            && captureThread != null && captureThread.isAlive(),
                    state,
                    message,
                    settings.isNetwork() ? settings.sourceId
                            : selected == null ? settings.sourceId : selected.stableId,
                    settings.isNetwork() && (mjpegReader != null || rtspReader != null)
                            ? settings.sourceId
                            : activeSource == null ? "" : activeSource.stableId,
                    settings.isNetwork() ? "IP camera"
                            : activeSource == null ? "" : activeSource.displayName,
                    activeSource == null ? "" : activeSource.node,
                    settings.width,
                    settings.height,
                    settings.fps,
                    actualWidth,
                    actualHeight,
                    actualFps,
                    activeFormat,
                    latestFrame == null ? 0L : latestFrame.sequence,
                    latestFrame == null ? 0L : latestFrame.timestampMs,
                    lastError,
                    clientTouches.size()
            );
        }
    }

    /** Returns the immutable cached source list without touching device nodes. */
    public List<Source> getSources() {
        return cachedSources;
    }

    /** Forces V4L2/sysfs discovery and returns the new immutable source list. */
    public List<Source> rescan() {
        return scanSources(true);
    }

    /**
     * Persists camera settings. A non-empty source ID must be present in the
     * current enumerated source list; raw /dev paths are never accepted.
     */
    public ApplyResult applySettings(
            boolean enabled,
            String stableSourceId,
            int width,
            int height,
            int fps) {
        return applySettings(enabled, stableSourceId, "", width, height, fps);
    }

    public ApplyResult applySettings(
            boolean enabled,
            String stableSourceId,
            String streamUrl,
            int width,
            int height,
            int fps) {
        String requestedSource = stableSourceId == null ? "" : stableSourceId.trim();
        boolean mjpeg = MJPEG_SOURCE_ID.equals(requestedSource);
        boolean rtsp = RTSP_SOURCE_ID.equals(requestedSource);
        boolean network = mjpeg || rtsp;
        final String validatedUrl;
        final String savedUrl;
        try {
            String suppliedUrl = streamUrl == null ? "" : streamUrl.trim();
            savedUrl = network ? streamUrlStore.load(requestedSource) : "";
            if (suppliedUrl.isEmpty() && network) {
                suppliedUrl = requestedSource.equals(settings.sourceId)
                        && !settings.streamUrl.isEmpty() ? settings.streamUrl : savedUrl;
            }
            validatedUrl = suppliedUrl.isEmpty() ? "" : rtsp
                    ? RtspStreamReader.validateUrl(suppliedUrl)
                    : MjpegStreamReader.validateUrl(suppliedUrl);
        } catch (IllegalArgumentException invalidUrl) {
            return new ApplyResult(false, invalidUrl.getMessage(), settings);
        }
        if (mjpeg && validatedUrl.isEmpty()) {
            return new ApplyResult(false, "Enter the IP camera MJPEG URL", settings);
        }
        if (rtsp && validatedUrl.isEmpty()) {
            return new ApplyResult(false, "Enter the RTSP camera URL", settings);
        }
        List<Source> sources = network ? cachedSources : rescan();
        Source selected = null;
        if (!requestedSource.isEmpty() && !network) {
            selected = findSource(sources, requestedSource);
            if (selected == null) {
                return new ApplyResult(false, "Selected USB camera is not connected", settings);
            }
            requestedSource = selected.stableId;
        }

        Settings next = new Settings(
                enabled,
                requestedSource,
                validatedUrl,
                boundedWidth(width),
                boundedHeight(height),
                boundedFps(fps)
        );
        Settings previous;
        synchronized (stateLock) {
            previous = settings;
            // Preserve each network source's encrypted URL when switching to
            // another camera. A Keystore failure leaves the old source running.
            if (network && !savedUrl.equals(next.streamUrl)) {
                streamUrlStore.save(next.sourceId, next.streamUrl);
            }
            if (mjpeg) mjpegUrlConfigured = true;
            if (rtsp) rtspUrlConfigured = true;
            settings = next;
            preferences.edit()
                    .putBoolean(KEY_ENABLED, next.enabled)
                    .putString(KEY_SOURCE_ID, next.sourceId)
                    .putInt(KEY_WIDTH, next.width)
                    .putInt(KEY_HEIGHT, next.height)
                    .putInt(KEY_FPS, next.fps)
                    .apply();

            boolean captureChanged = previous.enabled != next.enabled
                    || !previous.sourceId.equals(next.sourceId)
                    || !previous.streamUrl.equals(next.streamUrl)
                    || previous.width != next.width
                    || previous.height != next.height
                    || previous.fps != next.fps;
            if (captureChanged) requestCaptureStopLocked();
            lastError = "";
            nextStartAt = 0L;
            if (!next.enabled) {
                state = "disabled";
                message = "Camera is disabled";
            } else if (network) {
                if (!clientTouches.isEmpty()) ensureCaptureStartedLocked();
                else {
                    state = "idle";
                    message = "IP camera ready";
                }
            } else if (!V4l2Native.AVAILABLE) {
                state = "unavailable";
                message = "Native V4L2 support is unavailable";
                lastError = message;
            } else if (sources.isEmpty()) {
                state = "unavailable";
                message = "No compatible USB camera is connected";
            } else if (!clientTouches.isEmpty()) {
                ensureCaptureStartedLocked();
            } else {
                state = "idle";
                message = "Camera ready";
            }
        }
        signalFrameWaiters();
        return new ApplyResult(true, enabled ? "Camera settings saved" : "Camera disabled", next);
    }

    /**
     * Waits for a JPEG newer than {@code afterSequence}. The opaque client ID
     * owns a renewable streaming lease until {@link #releaseClient(String)} is
     * called. IDs are deliberately restricted so untrusted HTTP input cannot
     * create unbounded or ambiguous map keys.
     */
    public Frame awaitFrame(String clientId, long afterSequence, long timeoutMs) {
        String safeClientId = validatedClientId(clientId);
        Settings current = settings;
        if (!current.enabled || (!current.isNetwork() && !V4l2Native.AVAILABLE)) return null;
        if (!current.isNetwork() && (cachedSources.isEmpty()
                || SystemClock.elapsedRealtime() >= nextSourceScanAt)) {
            scanSources(false);
        }

        long safeTimeout = Math.max(100L, Math.min(timeoutMs, 30_000L));
        long deadline = SystemClock.elapsedRealtime() + safeTimeout;
        synchronized (stateLock) {
            long now = SystemClock.elapsedRealtime();
            pruneExpiredClientsLocked(now);
            Long releasedUntil = releasedClients.get(safeClientId);
            if (releasedUntil != null && releasedUntil >= now) return null;
            releasedClients.remove(safeClientId);
            if (!clientTouches.containsKey(safeClientId)
                    && clientTouches.size() >= MAX_ACTIVE_CLIENTS) {
                throw new IllegalStateException("Too many active camera viewers");
            }
            clientTouches.put(safeClientId, now);
            ensureCaptureStartedLocked();
        }
        while (SystemClock.elapsedRealtime() < deadline) {
            synchronized (stateLock) {
                if (!clientTouches.containsKey(safeClientId)) return null;
                ensureCaptureStartedLocked();
            }

            Frame frame = latestFrame;
            if (frame != null && frame.sequence > afterSequence) return frame;

            long remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining <= 0L) break;
            synchronized (frameLock) {
                try {
                    frameLock.wait(Math.min(remaining, 250L));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return null;
    }

    /** Releases the streaming lease for an opaque page-scoped client ID. */
    public void releaseClient(String clientId) {
        String safeClientId = validatedClientId(clientId);
        synchronized (stateLock) {
            long now = SystemClock.elapsedRealtime();
            pruneExpiredClientsLocked(now);
            clientTouches.remove(safeClientId);
            rememberReleasedClientLocked(safeClientId, now);
            if (clientTouches.isEmpty()) {
                requestCaptureStopLocked();
                if (settings.enabled) {
                    state = "idle";
                    message = settings.isNetwork() ? "IP camera ready" : cachedSources.isEmpty() ?
                            "No compatible USB camera is connected" : "Camera ready";
                }
            }
        }
        signalFrameWaiters();
    }

    /** Stops all consumers and releases the camera without changing settings. */
    public void stop() {
        synchronized (stateLock) {
            clientTouches.clear();
            requestCaptureStopLocked();
            state = settings.enabled ? "idle" : "disabled";
            message = settings.enabled ? "Camera stopped" : "Camera is disabled";
        }
        signalFrameWaiters();
    }

    private List<Source> scanSources(boolean force) {
        synchronized (sourceLock) {
            long now = SystemClock.elapsedRealtime();
            if (!force && now < nextSourceScanAt) return cachedSources;
            if (!V4l2Native.AVAILABLE) {
                cachedSources = Collections.emptyList();
                nextSourceScanAt = now + SOURCE_CACHE_MS;
                return cachedSources;
            }

            Source runningSource = activeSource;
            List<Source> discovered = new ArrayList<>();
            Map<String, Integer> stableIdOccurrences = new LinkedHashMap<>();
            for (int index = 0; index <= MAX_VIDEO_NODE; index++) {
                String path = "/dev/video" + index;
                File node = new File(path);
                if (!node.exists() || !isUvcVideoNode(path)) continue;

                Source source;
                if (runningSource != null && runningSource.node.equals(path) && nativeHandle != 0L) {
                    source = runningSource;
                } else {
                    source = probeSource(path);
                }
                if (source == null) continue;

                int occurrence = stableIdOccurrences.containsKey(source.stableId)
                        ? stableIdOccurrences.get(source.stableId) + 1 : 1;
                stableIdOccurrences.put(source.stableId, occurrence);
                if (occurrence > 1) {
                    source = source.withStableId(source.stableId + ":capture" + occurrence);
                }
                discovered.add(source);
            }
            cachedSources = Collections.unmodifiableList(discovered);
            nextSourceScanAt = now + SOURCE_CACHE_MS;

            synchronized (stateLock) {
                if (settings.enabled && !settings.isNetwork()
                        && discovered.isEmpty() && nativeHandle == 0L) {
                    state = "unavailable";
                    message = "No compatible USB camera is connected";
                } else if (settings.enabled && !settings.isNetwork() && !discovered.isEmpty()
                        && nativeHandle == 0L && clientTouches.isEmpty()) {
                    state = "idle";
                    message = "Camera ready";
                }
            }
            return cachedSources;
        }
    }

    private Source probeSource(String path) {
        String probe;
        try {
            probe = V4l2Native.probe(path, MAX_WIDTH, MAX_HEIGHT);
        } catch (Throwable failure) {
            Log.w(TAG, "V4L2 probe failed for " + path, failure);
            return null;
        }
        if (probe == null || probe.isEmpty()) return null;
        String[] parts = probe.split("\\|", 5);
        if (parts.length < 5) return null;
        int width = parsePositive(parts[2], 0);
        int height = parsePositive(parts[3], 0);
        if (width <= 0 || height <= 0 || width > MAX_WIDTH || height > MAX_HEIGHT) return null;
        String name = parts[1].trim().isEmpty() ? "USB Camera" : parts[1].trim();
        String format = parts[4].trim().toUpperCase(Locale.US);
        return new Source(
                stableIdFor(path),
                path,
                name,
                width,
                height,
                format
        );
    }

    private void ensureCaptureStartedLocked() {
        long now = SystemClock.elapsedRealtime();
        pruneExpiredClientsLocked(now);
        if (clientTouches.isEmpty()) return;
        if (!settings.enabled) {
            state = "disabled";
            message = "Camera is disabled";
            return;
        }
        if (settings.isMjpeg()) {
            ensureMjpegStartedLocked(now);
            return;
        }
        if (settings.isRtsp()) {
            ensureRtspStartedLocked(now);
            return;
        }
        if (!V4l2Native.AVAILABLE) {
            state = "unavailable";
            message = "Native V4L2 support is unavailable";
            lastError = message;
            return;
        }
        if (nativeHandle != 0L && captureThread != null && captureThread.isAlive()) return;
        if (captureThread != null && captureThread.isAlive()) {
            state = "recovering";
            message = "Waiting for the previous camera stream to stop";
            return;
        }
        captureThread = null;
        if (now < nextStartAt) return;

        Source source = selectSource(cachedSources, settings.sourceId);
        if (source == null) {
            state = "unavailable";
            message = "Selected USB camera is not connected";
            return;
        }

        int requestedWidth = settings.width;
        int requestedHeight = settings.height;
        int requestedFps = settings.fps;
        if ("YUYV".equalsIgnoreCase(source.format)) {
            requestedWidth = Math.min(requestedWidth, YUYV_MAX_WIDTH);
            requestedHeight = Math.min(requestedHeight, YUYV_MAX_HEIGHT);
            requestedFps = Math.min(requestedFps, YUYV_MAX_FPS);
        }

        state = "starting";
        message = "Opening " + source.displayName;
        long handle = openSource(source, requestedWidth, requestedHeight, requestedFps);
        if (handle == 0L) return;

        String format = safeNativeString(V4l2Native.format(handle));
        int openedWidth = V4l2Native.width(handle);
        int openedHeight = V4l2Native.height(handle);
        int openedStride = V4l2Native.stride(handle);
        int openedFps = V4l2Native.fps(handle);
        if ("YUYV".equalsIgnoreCase(format)
                && (openedWidth > YUYV_MAX_WIDTH || openedHeight > YUYV_MAX_HEIGHT
                || requestedFps > YUYV_MAX_FPS)) {
            V4l2Native.close(handle);
            handle = openSource(
                    source,
                    Math.min(settings.width, YUYV_MAX_WIDTH),
                    Math.min(settings.height, YUYV_MAX_HEIGHT),
                    Math.min(settings.fps, YUYV_MAX_FPS)
            );
            if (handle == 0L) return;
            format = safeNativeString(V4l2Native.format(handle));
            openedWidth = V4l2Native.width(handle);
            openedHeight = V4l2Native.height(handle);
            openedStride = V4l2Native.stride(handle);
            openedFps = V4l2Native.fps(handle);
        }

        if (openedWidth <= 0 || openedHeight <= 0
                || openedWidth > MAX_WIDTH || openedHeight > MAX_HEIGHT) {
            V4l2Native.close(handle);
            failStartLocked("Camera returned an invalid or oversized resolution");
            return;
        }

        nativeHandle = handle;
        activeSource = source;
        actualWidth = openedWidth;
        actualHeight = openedHeight;
        actualFps = openedFps;
        activeFormat = format;
        lastError = "";
        nextStartAt = 0L;
        state = "streaming";
        message = "Streaming " + source.displayName;
        final long threadHandle = handle;
        final int threadWidth = openedWidth;
        final int threadHeight = openedHeight;
        final int threadStride = openedStride;
        final int publishFps = "YUYV".equalsIgnoreCase(format)
                ? Math.min(settings.fps, YUYV_MAX_FPS) : Math.min(settings.fps, MAX_FPS);
        final String threadFormat = format;
        Thread thread = new Thread(
                () -> captureLoop(
                        threadHandle,
                        threadWidth,
                        threadHeight,
                        threadStride,
                        publishFps,
                        threadFormat
                ),
                "FabScreen-UVC-Capture"
        );
        captureThread = thread;
        thread.start();
    }

    private void ensureMjpegStartedLocked(long now) {
        if (mjpegReader != null && captureThread != null && captureThread.isAlive()) return;
        if (captureThread != null && captureThread.isAlive()) {
            state = "recovering";
            message = "Waiting for the previous camera stream to stop";
            return;
        }
        captureThread = null;
        if (now < nextStartAt) return;
        final MjpegStreamReader reader;
        try {
            reader = new MjpegStreamReader(settings.streamUrl);
        } catch (IllegalArgumentException invalidUrl) {
            failStartLocked("Invalid IP camera URL");
            return;
        }
        mjpegReader = reader;
        activeSource = null;
        actualWidth = 0;
        actualHeight = 0;
        actualFps = 0;
        activeFormat = "MJPEG";
        latestFrame = null;
        lastError = "";
        state = "starting";
        message = "Connecting to IP camera";
        Thread thread = new Thread(() -> mjpegCaptureLoop(reader), "FabScreen-MJPEG-Capture");
        captureThread = thread;
        thread.start();
    }

    private void ensureRtspStartedLocked(long now) {
        if (rtspReader != null && captureThread != null && captureThread.isAlive()) return;
        if (captureThread != null && captureThread.isAlive()) {
            state = "recovering";
            message = "Waiting for the previous camera stream to stop";
            return;
        }
        captureThread = null;
        if (now < nextStartAt) return;
        final RtspStreamReader reader;
        try {
            reader = new RtspStreamReader(applicationContext, settings.streamUrl,
                    settings.width, settings.height);
        } catch (IllegalArgumentException invalidUrl) {
            failStartLocked("Invalid RTSP camera URL");
            return;
        }
        rtspReader = reader;
        activeSource = null;
        actualWidth = 0;
        actualHeight = 0;
        actualFps = 0;
        activeFormat = "RTSP";
        latestFrame = null;
        lastError = "";
        state = "starting";
        message = "Connecting to RTSP camera";
        Thread thread = new Thread(() -> rtspCaptureLoop(reader), "FabScreen-RTSP-Capture");
        captureThread = thread;
        thread.start();
    }

    private void rtspCaptureLoop(RtspStreamReader reader) {
        String terminalError = "";
        long nextPublishAtNanos = 0L;
        long lastPublishAtNanos = 0L;
        try {
            while (!Thread.currentThread().isInterrupted() && shouldCapture(reader)) {
                // Consume only at the configured rate. The SurfaceTexture keeps
                // the newest decoded frame while we wait, avoiding JPEG encode
                // work on every 25/30 FPS frame from the IP camera.
                while (nextPublishAtNanos > System.nanoTime() && shouldCapture(reader)) {
                    long remainingMs = (nextPublishAtNanos - System.nanoTime()) / 1_000_000L;
                    try {
                        Thread.sleep(Math.max(1L, Math.min(remainingMs, 100L)));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                if (Thread.currentThread().isInterrupted() || !shouldCapture(reader)) break;
                byte[] jpeg = reader.readFrame();
                if (!shouldCapture(reader)) break;
                long nowNanos = System.nanoTime();
                jpeg = normalizeJpeg(jpeg);
                if (jpeg == null) continue;
                nextPublishAtNanos = nowNanos
                        + 1_000_000_000L / Math.max(1, settings.fps);
                synchronized (stateLock) {
                    if (rtspReader != reader || !settings.isRtsp()) break;
                    actualWidth = reader.getWidth();
                    actualHeight = reader.getHeight();
                    if (lastPublishAtNanos != 0L) {
                        long delta = nowNanos - lastPublishAtNanos;
                        actualFps = delta > 0L
                                ? (int) Math.min(MAX_FPS, 1_000_000_000L / delta)
                                : settings.fps;
                    }
                    lastPublishAtNanos = nowNanos;
                    lastError = "";
                    state = "streaming";
                    message = "Streaming RTSP camera";
                    latestFrame = new Frame(++nextSequence, System.currentTimeMillis(), jpeg);
                }
                signalFrameWaiters();
            }
        } catch (IOException failure) {
            // Never pass native exception text through to status or logcat.
            terminalError = safeRtspFailureMessage(failure);
        } catch (RuntimeException failure) {
            terminalError = "RTSP camera stream could not be decoded";
        } finally {
            try {
                reader.releaseRenderer();
            } catch (RuntimeException ignored) {
                // Capture state still needs resetting after EGL cleanup fails.
            }
            synchronized (stateLock) {
                boolean ownsCapture = rtspReader == reader;
                boolean wasCaptureThread = captureThread == Thread.currentThread();
                if (wasCaptureThread) captureThread = null;
                if (ownsCapture) {
                    rtspReader = null;
                    latestFrame = null;
                    actualWidth = 0;
                    actualHeight = 0;
                    actualFps = 0;
                    activeFormat = "";
                    if (settings.enabled && settings.isRtsp() && !clientTouches.isEmpty()) {
                        state = "recovering";
                        lastError = terminalError.isEmpty()
                                ? "RTSP camera stream disconnected" : terminalError;
                        message = lastError;
                        nextStartAt = SystemClock.elapsedRealtime() + RETRY_DELAY_MS;
                    } else {
                        state = settings.enabled ? "idle" : "disabled";
                        message = settings.enabled ? "IP camera ready" : "Camera is disabled";
                    }
                } else if (wasCaptureThread && settings.enabled && !clientTouches.isEmpty()) {
                    ensureCaptureStartedLocked();
                }
            }
            signalFrameWaiters();
        }
    }

    private boolean shouldCapture(RtspStreamReader reader) {
        synchronized (stateLock) {
            pruneExpiredClientsLocked(SystemClock.elapsedRealtime());
            return settings.enabled && settings.isRtsp()
                    && rtspReader == reader && !clientTouches.isEmpty();
        }
    }

    private static String safeRtspFailureMessage(IOException failure) {
        String candidate = failure.getMessage();
        if (candidate != null && candidate.startsWith("RTSP camera ")
                && candidate.indexOf(':') < 0 && candidate.indexOf('@') < 0) {
            return candidate;
        }
        return "RTSP camera connection failed";
    }

    private void mjpegCaptureLoop(MjpegStreamReader reader) {
        String terminalError = "";
        long nextPublishAtNanos = 0L;
        long lastPublishAtNanos = 0L;
        try {
            while (!Thread.currentThread().isInterrupted() && shouldCapture(reader)) {
                byte[] jpeg = reader.readFrame();
                if (!shouldCapture(reader)) break;
                long nowNanos = System.nanoTime();
                if (nowNanos < nextPublishAtNanos) continue;
                jpeg = normalizeJpeg(jpeg);
                if (jpeg == null) continue;
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, bounds);
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0
                        || bounds.outWidth > 4096 || bounds.outHeight > 4096
                        || (long) bounds.outWidth * bounds.outHeight > 16_000_000L) {
                    throw new IOException("IP camera frame dimensions are invalid or too large");
                }
                nextPublishAtNanos = nowNanos
                        + 1_000_000_000L / Math.max(1, settings.fps);
                synchronized (stateLock) {
                    if (mjpegReader != reader || !settings.isMjpeg()) break;
                    actualWidth = bounds.outWidth;
                    actualHeight = bounds.outHeight;
                    if (lastPublishAtNanos != 0L) {
                        long delta = nowNanos - lastPublishAtNanos;
                        actualFps = delta > 0L
                                ? (int) Math.min(MAX_FPS, 1_000_000_000L / delta)
                                : settings.fps;
                    }
                    lastPublishAtNanos = nowNanos;
                    lastError = "";
                    state = "streaming";
                    message = "Streaming IP camera";
                    latestFrame = new Frame(++nextSequence, System.currentTimeMillis(), jpeg);
                }
                signalFrameWaiters();
            }
        } catch (IOException failure) {
            terminalError = safeMjpegFailureMessage(failure);
        } catch (RuntimeException failure) {
            terminalError = "IP camera stream could not be decoded";
            Log.w(TAG, terminalError, failure);
        } finally {
            reader.close();
            synchronized (stateLock) {
                boolean ownsCapture = mjpegReader == reader;
                boolean wasCaptureThread = captureThread == Thread.currentThread();
                if (wasCaptureThread) captureThread = null;
                if (ownsCapture) {
                    mjpegReader = null;
                    latestFrame = null;
                    actualWidth = 0;
                    actualHeight = 0;
                    actualFps = 0;
                    activeFormat = "";
                    if (settings.enabled && settings.isMjpeg() && !clientTouches.isEmpty()) {
                        state = "recovering";
                        lastError = terminalError.isEmpty()
                                ? "IP camera stream disconnected" : terminalError;
                        message = lastError;
                        nextStartAt = SystemClock.elapsedRealtime() + RETRY_DELAY_MS;
                    } else {
                        state = settings.enabled ? "idle" : "disabled";
                        message = settings.enabled ? "IP camera ready" : "Camera is disabled";
                    }
                } else if (wasCaptureThread && settings.enabled && !clientTouches.isEmpty()) {
                    // The previous IP stream has fully stopped; start the newly
                    // selected source without waiting for another frame request.
                    ensureCaptureStartedLocked();
                }
            }
            signalFrameWaiters();
        }
    }

    private boolean shouldCapture(MjpegStreamReader reader) {
        synchronized (stateLock) {
            pruneExpiredClientsLocked(SystemClock.elapsedRealtime());
            return settings.enabled && settings.isMjpeg()
                    && mjpegReader == reader && !clientTouches.isEmpty();
        }
    }

    private static String safeMjpegFailureMessage(IOException failure) {
        String message = failure.getMessage();
        if (message != null && (message.startsWith("IP camera ")
                || message.startsWith("MJPEG "))) {
            return message;
        }
        if (failure instanceof java.net.SocketTimeoutException) {
            return "IP camera connection or frame timed out";
        }
        return "IP camera connection failed";
    }

    private long openSource(Source source, int width, int height, int fps) {
        long handle;
        try {
            handle = V4l2Native.open(
                    source.node,
                    Math.min(MAX_WIDTH, Math.max(MIN_WIDTH, width)),
                    Math.min(MAX_HEIGHT, Math.max(MIN_HEIGHT, height)),
                    Math.min(MAX_FPS, Math.max(1, fps))
            );
        } catch (Throwable failure) {
            failStartLocked("Could not open USB camera: " + failure.getClass().getSimpleName());
            return 0L;
        }
        if (handle == 0L) {
            String error = safeNativeString(V4l2Native.lastError());
            failStartLocked(error.isEmpty() ? "Could not open USB camera" : error);
        }
        return handle;
    }

    private void failStartLocked(String error) {
        lastError = error;
        state = "error";
        message = humanCameraError(error);
        nextStartAt = SystemClock.elapsedRealtime() + RETRY_DELAY_MS;
        Log.w(TAG, message);
    }

    private void captureLoop(
            long handle,
            int width,
            int height,
            int stride,
            int publishFps,
            String format) {
        long nextPublishAtNanos = 0L;
        long invalidFrameSince = 0L;
        final long publishIntervalNanos = 1_000_000_000L / Math.max(1, publishFps);
        String terminalError = "";
        try {
            while (!Thread.currentThread().isInterrupted() && shouldCapture(handle)) {
                byte[] captured = V4l2Native.readFrame(handle, CAPTURE_READ_TIMEOUT_MS);
                if (captured == null || captured.length == 0) {
                    String error = safeNativeString(V4l2Native.lastError());
                    if (!error.isEmpty()) {
                        terminalError = error;
                        break;
                    }
                    continue;
                }
                if (!shouldCapture(handle)) break;

                long nowNanos = System.nanoTime();
                if (nowNanos < nextPublishAtNanos) continue;
                nextPublishAtNanos = nowNanos + publishIntervalNanos;

                byte[] jpeg;
                if ("YUYV".equalsIgnoreCase(format)) {
                    jpeg = yuyvToJpeg(captured, width, height, stride);
                } else {
                    jpeg = captured;
                }
                jpeg = normalizeJpeg(jpeg);
                if (jpeg == null) {
                    long now = SystemClock.elapsedRealtime();
                    if (invalidFrameSince == 0L) invalidFrameSince = now;
                    if (now - invalidFrameSince >= INVALID_FRAME_GRACE_MS) {
                        synchronized (stateLock) {
                            lastError = captured.length > MAX_JPEG_BYTES
                                    ? "Camera JPEG exceeds the 8 MB frame limit"
                                    : "Camera has not returned a valid JPEG frame";
                        }
                    }
                    continue;
                }
                invalidFrameSince = 0L;
                synchronized (stateLock) {
                    lastError = "";
                    state = "streaming";
                    message = activeSource == null
                            ? "Streaming USB camera"
                            : "Streaming " + activeSource.displayName;
                }
                Frame frame = new Frame(++nextSequence, System.currentTimeMillis(), jpeg);
                latestFrame = frame;
                signalFrameWaiters();
            }
        } catch (Throwable failure) {
            terminalError = failure.getMessage();
            if (terminalError == null || terminalError.isEmpty()) {
                terminalError = failure.getClass().getSimpleName();
            }
            Log.w(TAG, "USB camera capture stopped", failure);
        } finally {
            try {
                V4l2Native.close(handle);
            } catch (Throwable failure) {
                Log.w(TAG, "Could not close USB camera", failure);
            }
            synchronized (stateLock) {
                boolean ownsCapture = nativeHandle == handle;
                if (ownsCapture) nativeHandle = 0L;
                boolean wasCaptureThread = captureThread == Thread.currentThread();
                if (wasCaptureThread) captureThread = null;
                // A source change or client stop detaches this handle before its read
                // loop exits. Only the still-selected USB stream may update shared
                // status; otherwise it can overwrite the new IP camera's state.
                if (ownsCapture) {
                    activeSource = null;
                    actualWidth = 0;
                    actualHeight = 0;
                    actualFps = 0;
                    activeFormat = "";
                    latestFrame = null;
                    if (!terminalError.isEmpty() && settings.enabled && !clientTouches.isEmpty()) {
                        lastError = terminalError;
                        state = "recovering";
                        message = humanCameraError(terminalError);
                        nextStartAt = SystemClock.elapsedRealtime() + RETRY_DELAY_MS;
                    } else if (settings.enabled && !clientTouches.isEmpty()) {
                        state = "recovering";
                        message = "Reopening USB camera";
                        nextStartAt = SystemClock.elapsedRealtime() + RETRY_DELAY_MS;
                    } else {
                        state = settings.enabled ? "idle" : "disabled";
                        message = settings.enabled ? "Camera ready" : "USB camera is disabled";
                    }
                } else if (wasCaptureThread && settings.enabled && !clientTouches.isEmpty()) {
                    // A source switch detached this handle. Its replacement can
                    // start now that the old V4L2 thread has fully stopped.
                    ensureCaptureStartedLocked();
                }
            }
            signalFrameWaiters();
        }
    }

    private boolean shouldCapture(long handle) {
        synchronized (stateLock) {
            pruneExpiredClientsLocked(SystemClock.elapsedRealtime());
            boolean keepRunning = settings.enabled
                    && nativeHandle == handle
                    && !clientTouches.isEmpty();
            if (!keepRunning && nativeHandle == handle) nativeHandle = 0L;
            return keepRunning;
        }
    }

    private void requestCaptureStopLocked() {
        nativeHandle = 0L;
        MjpegStreamReader reader = mjpegReader;
        mjpegReader = null;
        if (reader != null) {
            try {
                reader.close();
            } catch (RuntimeException failure) {
                Log.w(TAG, "IP camera cleanup failed: " + failure.getClass().getSimpleName());
            }
        }
        RtspStreamReader rtsp = rtspReader;
        rtspReader = null;
        if (rtsp != null) rtsp.close();
        Thread running = captureThread;
        if (running != null) running.interrupt();
        latestFrame = null;
        activeSource = null;
        actualWidth = 0;
        actualHeight = 0;
        actualFps = 0;
        activeFormat = "";
    }

    private void pruneExpiredClientsLocked(long now) {
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, Long> entry : clientTouches.entrySet()) {
            if (now - entry.getValue() > CLIENT_LEASE_MS) expired.add(entry.getKey());
        }
        boolean clientsExpired = !expired.isEmpty();
        for (String id : expired) clientTouches.remove(id);
        expired.clear();
        for (Map.Entry<String, Long> entry : releasedClients.entrySet()) {
            if (entry.getValue() < now) expired.add(entry.getKey());
        }
        for (String id : expired) releasedClients.remove(id);
        if (clientsExpired && clientTouches.isEmpty()) {
            requestCaptureStopLocked();
            if (settings.enabled) {
                state = "idle";
                message = settings.isNetwork() ? "IP camera ready" : "Camera ready";
            }
        }
    }

    private void rememberReleasedClientLocked(String clientId, long now) {
        if (!releasedClients.containsKey(clientId)
                && releasedClients.size() >= MAX_RELEASED_CLIENTS) {
            String oldest = releasedClients.keySet().iterator().next();
            releasedClients.remove(oldest);
        }
        releasedClients.put(clientId, now + CLIENT_RELEASE_TOMBSTONE_MS);
    }

    private static String validatedClientId(String clientId) {
        if (clientId == null || clientId.length() < 1 || clientId.length() > MAX_CLIENT_ID_LENGTH) {
            throw new IllegalArgumentException("Camera client ID must contain 1 to 64 characters");
        }
        for (int index = 0; index < clientId.length(); index++) {
            char value = clientId.charAt(index);
            boolean valid = value >= 'a' && value <= 'z'
                    || value >= 'A' && value <= 'Z'
                    || value >= '0' && value <= '9'
                    || value == '-' || value == '_' || value == '.' || value == '~';
            if (!valid) throw new IllegalArgumentException("Camera client ID contains invalid characters");
        }
        return clientId;
    }

    private static byte[] normalizeJpeg(byte[] jpeg) {
        if (jpeg == null || jpeg.length < 4 || jpeg.length > MAX_JPEG_BYTES
                || (jpeg[0] & 0xff) != 0xff || (jpeg[1] & 0xff) != 0xd8) {
            return null;
        }
        for (int index = jpeg.length - 2; index >= 2; index--) {
            if ((jpeg[index] & 0xff) == 0xff && (jpeg[index + 1] & 0xff) == 0xd9) {
                int jpegLength = index + 2;
                return jpegLength == jpeg.length ? jpeg : Arrays.copyOf(jpeg, jpegLength);
            }
        }
        return null;
    }

    private byte[] yuyvToJpeg(byte[] bytes, int width, int height, int reportedStride) {
        int packedStride = width * 2;
        int stride = Math.max(packedStride, reportedStride);
        long required = (long) stride * (long) height;
        if (required <= 0L || required > Integer.MAX_VALUE || bytes.length < required) {
            synchronized (stateLock) {
                lastError = "YUYV frame is shorter than the negotiated resolution";
                message = lastError;
            }
            return null;
        }
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            int[] strides = stride == packedStride ? null : new int[]{stride};
            YuvImage image = new YuvImage(bytes, ImageFormat.YUY2, width, height, strides);
            if (!image.compressToJpeg(new Rect(0, 0, width, height), JPEG_QUALITY, output)) {
                return null;
            }
            return output.toByteArray();
        } catch (RuntimeException failure) {
            synchronized (stateLock) {
                lastError = "YUYV conversion failed: " + failure.getMessage();
                message = lastError;
            }
            return null;
        }
    }

    private static Source selectSource(List<Source> sources, String stableId) {
        if (sources == null || sources.isEmpty()) return null;
        if (stableId != null && !stableId.isEmpty()) {
            Source selected = findSource(sources, stableId);
            if (selected != null) return selected;
            return null;
        }
        return sources.get(0);
    }

    private static Source findSource(List<Source> sources, String stableId) {
        if (sources == null || stableId == null) return null;
        for (Source source : sources) {
            if (stableId.equals(source.stableId)) return source;
        }
        return null;
    }

    private static boolean isUvcVideoNode(String path) {
        String nodeName = new File(path).getName();
        try {
            File driver = new File("/sys/class/video4linux/" + nodeName + "/device/driver");
            return "uvcvideo".equalsIgnoreCase(driver.getCanonicalFile().getName());
        } catch (IOException ignored) {
            return false;
        }
    }

    private static String stableIdFor(String path) {
        String nodeName = new File(path).getName();
        try {
            File videoNode = new File("/sys/class/video4linux/" + nodeName).getCanonicalFile();
            File cursor = videoNode;
            File interfaceNode = null;
            while (cursor != null) {
                if (interfaceNode == null && cursor.getName().contains(":")) interfaceNode = cursor;
                File vendorFile = new File(cursor, "idVendor");
                File productFile = new File(cursor, "idProduct");
                if (vendorFile.isFile() && productFile.isFile()) {
                    String vendor = safeIdentity(readText(vendorFile));
                    String product = safeIdentity(readText(productFile));
                    String port = safeIdentity(readText(new File(cursor, "devpath")));
                    String usbInterface = interfaceNode == null ? "video" : safeIdentity(interfaceNode.getName());
                    String captureIndex = safeIdentity(readText(
                            new File("/sys/class/video4linux/" + nodeName + "/index")));
                    if (!vendor.isEmpty() && !product.isEmpty()) {
                        return "uvc:" + vendor + ':' + product + ':'
                                + (port.isEmpty() ? safeIdentity(cursor.getName()) : port) + ':'
                                + usbInterface + ':' + (captureIndex.isEmpty() ? "0" : captureIndex);
                    }
                    break;
                }
                cursor = cursor.getParentFile();
            }
            return "uvc:" + safeIdentity(videoNode.getCanonicalPath());
        } catch (IOException ignored) {
            return "uvc:" + safeIdentity(path);
        }
    }

    private static String readText(File file) {
        if (file == null || !file.isFile()) return "";
        java.io.FileInputStream input = null;
        try {
            input = new java.io.FileInputStream(file);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[128];
            int count;
            while ((count = input.read(buffer)) > 0 && output.size() < 512) {
                output.write(buffer, 0, count);
            }
            return output.toString("UTF-8").trim();
        } catch (IOException ignored) {
            return "";
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException ignored) {
                    // Ignore best-effort sysfs cleanup.
                }
            }
        }
    }

    private static String safeIdentity(String value) {
        if (value == null) return "";
        return value.trim().replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static int boundedWidth(int width) {
        return Math.min(MAX_WIDTH, Math.max(MIN_WIDTH, width));
    }

    private static int boundedHeight(int height) {
        return Math.min(MAX_HEIGHT, Math.max(MIN_HEIGHT, height));
    }

    private static int boundedFps(int fps) {
        return Math.min(MAX_FPS, Math.max(1, fps));
    }

    private static int parsePositive(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String safeNativeString(String value) {
        return value == null ? "" : value.trim();
    }

    private static String humanCameraError(String error) {
        if (error == null || error.isEmpty()) return "USB camera error";
        String lower = error.toLowerCase(Locale.US);
        if (lower.contains("permission denied")) {
            return "USB camera access was denied by Android";
        }
        if (lower.contains("device or resource busy")) {
            return "USB camera is in use by another application";
        }
        if (lower.contains("disconnected") || lower.contains("no such device")) {
            return "USB camera disconnected; waiting to reconnect";
        }
        return error;
    }

    private void signalFrameWaiters() {
        synchronized (frameLock) {
            frameLock.notifyAll();
        }
    }

    public static final class Settings {
        private final boolean enabled;
        private final String sourceId;
        private final String streamUrl;
        private final int width;
        private final int height;
        private final int fps;

        private Settings(boolean enabled, String sourceId, String streamUrl,
                         int width, int height, int fps) {
            this.enabled = enabled;
            this.sourceId = sourceId == null ? "" : sourceId;
            this.streamUrl = streamUrl == null ? "" : streamUrl;
            this.width = width;
            this.height = height;
            this.fps = fps;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public String getSourceId() {
            return sourceId;
        }

        public boolean isMjpeg() {
            return MJPEG_SOURCE_ID.equals(sourceId);
        }

        public boolean isRtsp() {
            return RTSP_SOURCE_ID.equals(sourceId);
        }

        public boolean isNetwork() {
            return isMjpeg() || isRtsp();
        }

        public boolean hasStreamUrl() {
            return !streamUrl.isEmpty();
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }

        public int getFps() {
            return fps;
        }
    }

    public static final class Source {
        private final String stableId;
        private final String node;
        private final String displayName;
        private final int width;
        private final int height;
        private final String format;

        private Source(
                String stableId,
                String node,
                String displayName,
                int width,
                int height,
                String format) {
            this.stableId = stableId;
            this.node = node;
            this.displayName = displayName;
            this.width = width;
            this.height = height;
            this.format = format;
        }

        private Source withStableId(String value) {
            return new Source(value, node, displayName, width, height, format);
        }

        public String getStableId() {
            return stableId;
        }

        public String getDisplayName() {
            return displayName;
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }

        public String getFormat() {
            return format;
        }

        public boolean isCompressed() {
            return "MJPG".equalsIgnoreCase(format) || "JPEG".equalsIgnoreCase(format);
        }
    }

    public static final class Status {
        private final boolean nativeAvailable;
        private final boolean enabled;
        private final boolean active;
        private final boolean running;
        private final String state;
        private final String message;
        private final String selectedSourceId;
        private final String activeSourceId;
        private final String activeSourceName;
        private final String activeNode;
        private final int requestedWidth;
        private final int requestedHeight;
        private final int requestedFps;
        private final int actualWidth;
        private final int actualHeight;
        private final int actualFps;
        private final String format;
        private final long sequence;
        private final long lastFrameAt;
        private final String error;
        private final int clientCount;

        private Status(
                boolean nativeAvailable,
                boolean enabled,
                boolean active,
                boolean running,
                String state,
                String message,
                String selectedSourceId,
                String activeSourceId,
                String activeSourceName,
                String activeNode,
                int requestedWidth,
                int requestedHeight,
                int requestedFps,
                int actualWidth,
                int actualHeight,
                int actualFps,
                String format,
                long sequence,
                long lastFrameAt,
                String error,
                int clientCount) {
            this.nativeAvailable = nativeAvailable;
            this.enabled = enabled;
            this.active = active;
            this.running = running;
            this.state = state;
            this.message = message;
            this.selectedSourceId = selectedSourceId;
            this.activeSourceId = activeSourceId;
            this.activeSourceName = activeSourceName;
            this.activeNode = activeNode;
            this.requestedWidth = requestedWidth;
            this.requestedHeight = requestedHeight;
            this.requestedFps = requestedFps;
            this.actualWidth = actualWidth;
            this.actualHeight = actualHeight;
            this.actualFps = actualFps;
            this.format = format;
            this.sequence = sequence;
            this.lastFrameAt = lastFrameAt;
            this.error = error;
            this.clientCount = clientCount;
        }

        public boolean isNativeAvailable() { return nativeAvailable; }

        public boolean isEnabled() { return enabled; }

        public boolean isActive() { return active; }

        public boolean isRunning() { return running; }

        public String getState() { return state; }

        public String getMessage() { return message; }

        public String getSelectedSourceId() { return selectedSourceId; }

        public String getActiveSourceId() { return activeSourceId; }

        public String getActiveSourceName() { return activeSourceName; }

        public String getActiveNode() { return activeNode; }

        public int getRequestedWidth() { return requestedWidth; }

        public int getRequestedHeight() { return requestedHeight; }

        public int getRequestedFps() { return requestedFps; }

        public int getActualWidth() { return actualWidth; }

        public int getActualHeight() { return actualHeight; }

        public int getActualFps() { return actualFps; }

        public String getFormat() { return format; }

        public long getSequence() { return sequence; }

        public long getLastFrameAt() { return lastFrameAt; }

        public String getError() { return error; }

        public int getClientCount() { return clientCount; }
    }

    public static final class Frame {
        private final long sequence;
        private final long timestampMs;
        private final byte[] jpeg;

        private Frame(long sequence, long timestampMs, byte[] jpeg) {
            this.sequence = sequence;
            this.timestampMs = timestampMs;
            this.jpeg = jpeg;
        }

        public byte[] getJpeg() {
            return jpeg;
        }

        public long getSequence() {
            return sequence;
        }

        public long getTimestampMs() {
            return timestampMs;
        }
    }

    public static final class ApplyResult {
        private final boolean success;
        private final String message;
        private final Settings settings;

        private ApplyResult(boolean success, String message, Settings settings) {
            this.success = success;
            this.message = message;
            this.settings = settings;
        }

        public boolean isSuccess() { return success; }

        public String getMessage() { return message; }

        public Settings getSettings() { return settings; }
    }
}
