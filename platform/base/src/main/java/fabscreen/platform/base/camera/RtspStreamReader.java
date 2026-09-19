package fabscreen.platform.base.camera;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.source.rtsp.RtspMediaSource;
import com.google.android.exoplayer2.video.VideoSize;

/**
 * Decodes a private-LAN RTSP stream through ExoPlayer's RTSP media source.
 * A headless EGL surface converts decoded video frames
 * into the JPEG format shared by dashboard, snapshots, and Obico WebRTC.
 * One capture thread owns this reader; {@link #close()} may run on another.
 */
public final class RtspStreamReader implements AutoCloseable {
    private static final String TAG = "FabScreenUVC";
    private static final int MAX_URL_LENGTH = 2048;
    private static final int OPEN_TIMEOUT_MS = 12000;
    private static final int FRAME_TIMEOUT_MS = 12000;
    private static final int JPEG_QUALITY = 78;

    private static final String VERTEX_SHADER =
            "attribute vec4 position; attribute vec2 coordinate;"
                    + "uniform mat4 textureMatrix; varying vec2 uv;"
                    + "void main(){gl_Position=position;"
                    + "uv=(textureMatrix*vec4(coordinate,0.0,1.0)).xy;}";
    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n"
                    + "precision mediump float; varying vec2 uv;"
                    + "uniform samplerExternalOES video;"
                    + "void main(){gl_FragColor=texture2D(video,uv);}";
    private static final float[] QUAD = {
            -1f, -1f, 0f, 0f,
             1f, -1f, 1f, 0f,
            -1f,  1f, 0f, 1f,
             1f,  1f, 1f, 1f
    };

    private final String streamUrl;
    private final Context applicationContext;
    private final int width;
    private final int height;
    private final Object eventLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean playerReleased = new AtomicBoolean();
    private final AtomicBoolean firstFrameLogged = new AtomicBoolean();
    private volatile ExoPlayer player;
    private HandlerThread playerThread;
    private Handler playerHandler;
    private volatile boolean prepared;
    private volatile boolean failed;
    private volatile boolean frameAvailable;
    private volatile String failureMessage = "RTSP camera connection failed";
    private volatile int sourceWidth;
    private volatile int sourceHeight;
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private SurfaceTexture texture;
    private Surface surface;
    private int textureId;
    private int program;
    private ByteBuffer pixels;
    private int[] colors;
    private Bitmap bitmap;

    public RtspStreamReader(Context context, String url, int requestedWidth, int requestedHeight) {
        this.applicationContext = context.getApplicationContext();
        this.streamUrl = validateUrl(url);
        int[] size = boundedOutputSize(requestedWidth, requestedHeight);
        this.width = size[0];
        this.height = size[1];
    }

    /** Output size, not a request to change the camera's encoded RTSP stream. */
    static int[] boundedOutputSize(int requestedWidth, int requestedHeight) {
        return new int[]{
                Math.min(UvcCameraManager.MAX_WIDTH, Math.max(2, requestedWidth)) & ~1,
                Math.min(UvcCameraManager.MAX_HEIGHT, Math.max(2, requestedHeight)) & ~1
        };
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }

    /** Require a literal private address: this also prevents DNS rebinding. */
    public static String validateUrl(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_URL_LENGTH
                || !value.equals(value.trim())) {
            throw new IllegalArgumentException("Enter a valid RTSP camera URL");
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw new IllegalArgumentException("Enter a valid RTSP camera URL");
            }
        }
        final URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException failure) {
            throw new IllegalArgumentException("Enter a valid RTSP camera URL");
        }
        if (!"rtsp".equalsIgnoreCase(uri.getScheme()) || uri.isOpaque()
                || uri.getHost() == null || uri.getRawFragment() != null
                || uri.getRawPath() == null || uri.getRawPath().isEmpty()
                || uri.getPort() == 0 || uri.getPort() > 65535) {
            throw new IllegalArgumentException("RTSP URL must have a camera address and path");
        }
        String host = uri.getHost();
        if (!isPrivateNumericHost(host)) {
            throw new IllegalArgumentException("RTSP camera must use a private LAN IP address");
        }
        String userInfo = uri.getRawUserInfo();
        if (userInfo != null && (userInfo.isEmpty() || userInfo.length() > 256)) {
            throw new IllegalArgumentException("RTSP camera credentials are invalid");
        }
        return uri.toASCIIString();
    }

    static boolean isPrivateNumericHost(String host) {
        if (host == null || host.isEmpty()) return false;
        if (host.indexOf(':') < 0) {
            String[] octets = host.split("\\.", -1);
            if (octets.length != 4) return false;
            byte[] addressBytes = new byte[4];
            for (int position = 0; position < octets.length; position++) {
                String octet = octets[position];
                if (octet.isEmpty() || octet.length() > 3
                        || (octet.length() > 1 && octet.charAt(0) == '0')) return false;
                for (int index = 0; index < octet.length(); index++) {
                    if (!Character.isDigit(octet.charAt(index))) return false;
                }
                int value = Integer.parseInt(octet);
                if (value > 255) return false;
                addressBytes[position] = (byte) value;
            }
            try {
                return MjpegStreamReader.isAllowedLanAddress(
                        InetAddress.getByAddress(addressBytes));
            } catch (Exception ignored) {
                return false;
            }
        }
        if (host.indexOf('%') >= 0) return false;
        try {
            InetAddress address = InetAddress.getByName(host);
            return MjpegStreamReader.isAllowedLanAddress(address);
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Returns the newest complete JPEG. Every wait is bounded for recovery. */
    public byte[] readFrame() throws IOException {
        if (closed.get()) throw new IOException("RTSP camera stream is closed");
        try {
            if (player == null) open();
            awaitFrame();
            texture.updateTexImage();
            drawFrame();
            return encodeFrame();
        } catch (IOException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new IOException("RTSP camera frame could not be decoded");
        }
    }

    private void open() throws IOException {
        try {
            Log.i(TAG, "RTSP initializing EGL renderer");
            createGl();
            playerThread = new HandlerThread("FabScreen-RTSP-Player");
            playerThread.start();
            playerHandler = new Handler(playerThread.getLooper());
            if (!playerHandler.post(this::startPlayer)) {
                throw new IOException("RTSP camera player could not start");
            }
            Log.i(TAG, "RTSP awaiting prepare");
            awaitPrepared();
            Log.i(TAG, "RTSP prepare finished");
        } catch (IOException failure) {
            close();
            throw failure;
        } catch (Throwable failure) {
            close();
            throw new IOException("RTSP camera could not be opened");
        }
    }

    /** ExoPlayer's API and callbacks are confined to its own Looper thread. */
    private void startPlayer() {
        if (closed.get()) return;
        try {
            // ExoPlayer's internal errors can include the credential-bearing URI.
            // Our own diagnostics below report only stage and numeric error code.
            com.google.android.exoplayer2.util.Log.setLogLevel(
                    com.google.android.exoplayer2.util.Log.LOG_LEVEL_OFF);
            DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(250, 750, 100, 150).build();
            ExoPlayer opened = new ExoPlayer.Builder(applicationContext)
                    .setLooper(playerThread.getLooper())
                    .setLoadControl(loadControl)
                    .build();
            player = opened;
            Log.i(TAG, "RTSP decoder created");
            opened.addListener(new Player.Listener() {
                @Override public void onPlaybackStateChanged(int playbackState) {
                    if (playbackState == Player.STATE_READY) {
                        Log.i(TAG, "RTSP decoder prepared");
                        synchronized (eventLock) {
                            prepared = true;
                            eventLock.notifyAll();
                        }
                    } else if (playbackState == Player.STATE_ENDED) {
                        markFailed();
                    }
                }

                @Override public void onPlayerError(PlaybackException error) {
                    // Never log error messages or causes: RTSP libraries may echo URLs.
                    Log.w(TAG, "RTSP decoder error code " + error.errorCode);
                    if (error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                            || error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES) {
                        failureMessage = "RTSP camera decoder could not start; try H264 at 1920x1080 or lower";
                    }
                    markFailed();
                }

                @Override public void onVideoSizeChanged(VideoSize videoSize) {
                    sourceWidth = videoSize.width;
                    sourceHeight = videoSize.height;
                }
            });
            opened.setVolume(0f);
            opened.setVideoSurface(surface);
            RtspMediaSource source = new RtspMediaSource.Factory()
                    .setForceUseRtpTcp(true)
                    .setTimeoutMs(10_000)
                    .createMediaSource(MediaItem.fromUri(streamUrl));
            opened.setMediaSource(source);
            opened.prepare();
            opened.play();
            Log.i(TAG, "RTSP playback requested");
        } catch (RuntimeException failure) {
            Log.w(TAG, "RTSP player initialization failed: "
                    + failure.getClass().getSimpleName());
            markFailed();
        }
    }

    private void markFailed() {
        synchronized (eventLock) {
            failed = true;
            eventLock.notifyAll();
        }
    }

    private void awaitPrepared() throws IOException {
        waitForEvent(false, OPEN_TIMEOUT_MS);
        if (!prepared) throw new IOException("RTSP camera connection failed");
    }

    private void awaitFrame() throws IOException {
        waitForEvent(true, FRAME_TIMEOUT_MS);
        synchronized (eventLock) { frameAvailable = false; }
    }

    private void waitForEvent(boolean frame, long timeoutMs) throws IOException {
        long deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs;
        synchronized (eventLock) {
            while (!closed.get() && !failed && !(frame ? frameAvailable : prepared)) {
                long remaining = deadline - android.os.SystemClock.elapsedRealtime();
                if (remaining <= 0) break;
                try {
                    eventLock.wait(remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("RTSP camera stream was interrupted");
                }
            }
            if (closed.get()) throw new IOException("RTSP camera stream is closed");
            if (failed) throw new IOException(failureMessage);
            if (!(frame ? frameAvailable : prepared)) {
                throw new IOException(frame ? "RTSP camera frame timed out"
                        : "RTSP camera connection timed out");
            }
        }
    }

    private void createGl() throws IOException {
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] version = new int[2];
            if (display == EGL14.EGL_NO_DISPLAY
                    || !EGL14.eglInitialize(display, version, 0, version, 1)) {
                throw new IllegalStateException();
            }
            int[] attributes = {
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] count = new int[1];
            if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0)
                    || count[0] == 0) throw new IllegalStateException();
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            eglSurface = EGL14.eglCreatePbufferSurface(display, configs[0],
                    new int[]{EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height,
                            EGL14.EGL_NONE}, 0);
            if (context == EGL14.EGL_NO_CONTEXT || eglSurface == EGL14.EGL_NO_SURFACE
                    || !EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
                throw new IllegalStateException();
            }
            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            textureId = textures[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            texture = new SurfaceTexture(textureId);
            texture.setDefaultBufferSize(width, height);
            texture.setOnFrameAvailableListener(ignored -> {
                if (firstFrameLogged.compareAndSet(false, true)) {
                    Log.i(TAG, "RTSP first decoded frame arrived");
                }
                synchronized (eventLock) {
                    frameAvailable = true;
                    eventLock.notifyAll();
                }
            });
            surface = new Surface(texture);
            program = linkProgram(compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER),
                    compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER));
            pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder());
            colors = new int[width * height];
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        } catch (RuntimeException failure) {
            throw new IOException("RTSP video renderer is unavailable");
        }
    }

    private static int compileShader(int kind, String source) {
        int shader = GLES20.glCreateShader(kind);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) throw new IllegalStateException();
        return shader;
    }

    private static int linkProgram(int vertex, int fragment) {
        int linked = GLES20.glCreateProgram();
        GLES20.glAttachShader(linked, vertex);
        GLES20.glAttachShader(linked, fragment);
        GLES20.glLinkProgram(linked);
        GLES20.glDeleteShader(vertex);
        GLES20.glDeleteShader(fragment);
        int[] status = new int[1];
        GLES20.glGetProgramiv(linked, GLES20.GL_LINK_STATUS, status, 0);
        if (status[0] == 0) throw new IllegalStateException();
        return linked;
    }

    private void drawFrame() throws IOException {
        FloatBuffer quad = ByteBuffer.allocateDirect(QUAD.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(QUAD).position(0);
        float[] matrix = new float[16];
        texture.getTransformMatrix(matrix);
        // Letterbox rather than stretch when the camera and selected JPEG size
        // have different aspect ratios. The RTSP source itself stays unchanged.
        GLES20.glViewport(0, 0, width, height);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        int videoWidth = sourceWidth;
        int videoHeight = sourceHeight;
        if (videoWidth > 0 && videoHeight > 0) {
            double scale = Math.min((double) width / videoWidth, (double) height / videoHeight);
            int frameWidth = Math.max(1, (int) Math.round(videoWidth * scale));
            int frameHeight = Math.max(1, (int) Math.round(videoHeight * scale));
            GLES20.glViewport((width - frameWidth) / 2, (height - frameHeight) / 2,
                    frameWidth, frameHeight);
        }
        GLES20.glUseProgram(program);
        int position = GLES20.glGetAttribLocation(program, "position");
        int coordinate = GLES20.glGetAttribLocation(program, "coordinate");
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "textureMatrix"),
                1, false, matrix, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "video"), 0);
        quad.position(0);
        GLES20.glEnableVertexAttribArray(position);
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, quad);
        quad.position(2);
        GLES20.glEnableVertexAttribArray(coordinate);
        GLES20.glVertexAttribPointer(coordinate, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(position);
        GLES20.glDisableVertexAttribArray(coordinate);
        pixels.position(0);
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE, pixels);
        if (GLES20.glGetError() != GLES20.GL_NO_ERROR) {
            throw new IOException("RTSP video frame could not be captured");
        }
    }

    private byte[] encodeFrame() throws IOException {
        pixels.position(0);
        for (int y = 0; y < height; y++) {
            int row = (height - 1 - y) * width;
            for (int x = 0; x < width; x++) {
                int red = pixels.get() & 255;
                int green = pixels.get() & 255;
                int blue = pixels.get() & 255;
                pixels.get(); // alpha
                colors[row + x] = 0xff000000 | red << 16 | green << 8 | blue;
            }
        }
        bitmap.setPixels(colors, 0, width, 0, 0, width, height);
        ByteArrayOutputStream output = new ByteArrayOutputStream(width * height / 8);
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
            throw new IOException("RTSP video frame could not be encoded");
        }
        return output.toByteArray();
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        synchronized (eventLock) { eventLock.notifyAll(); }
    }

    /** Must run on the capture thread, never under the camera manager lock. */
    private void releasePlayer() {
        Handler handler = playerHandler;
        HandlerThread thread = playerThread;
        if (handler == null || thread == null || !playerReleased.compareAndSet(false, true)) return;
        CountDownLatch released = new CountDownLatch(1);
        if (!handler.post(() -> {
            try {
                ExoPlayer current = player;
                if (current != null) {
                    Log.i(TAG, "RTSP releasing decoder");
                    current.release();
                    player = null;
                    Log.i(TAG, "RTSP decoder released");
                }
            } catch (RuntimeException ignored) {
                Log.w(TAG, "RTSP decoder cleanup failed");
            } finally {
                thread.quitSafely();
                released.countDown();
            }
        })) {
            thread.quitSafely();
            return;
        }
        boolean interrupted = Thread.interrupted();
        try {
            if (!released.await(3, TimeUnit.SECONDS)) {
                Log.w(TAG, "RTSP decoder cleanup timed out");
            }
        } catch (InterruptedException ignored) {
            interrupted = true;
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /** Must be called by the capture thread after close, while its EGL context is current. */
    public void releaseRenderer() {
        close();
        releasePlayer();
        if (surface != null) { surface.release(); surface = null; }
        if (texture != null) { texture.release(); texture = null; }
        if (bitmap != null) { bitmap.recycle(); bitmap = null; }
        if (program != 0) { GLES20.glDeleteProgram(program); program = 0; }
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, new int[]{textureId}, 0);
            textureId = 0;
        }
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface);
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
            EGL14.eglTerminate(display);
            display = EGL14.EGL_NO_DISPLAY;
        }
    }
}
