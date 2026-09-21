package fabscreen.platform.base.obico;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import fabscreen.platform.base.camera.UvcCameraManager;

/**
 * Bridges Obico's Janus signaling and the shared USB/IP MJPEG camera to an Android WebRTC peer.
 * The peer runs from the APK's extracted native-library directory, never from writable app data.
 * AI-eligible HTTP snapshots use a separate camera lease and do not depend on this viewer.
 */
public final class ObicoWebRtcBridge implements ObicoLiveStream {
    private static final String LIBRARY_NAME = "libobicopeer.so";
    private static final String CAMERA_CLIENT_PREFIX = "obico-webrtc-";
    private static final int MAX_LINE_CHARS = 2 * 1024 * 1024;
    private static final int MAX_FRAME_BYTES = 512 * 1024;
    private static final int TARGET_JPEG_BYTES = 80 * 1024;
    private static final int TARGET_WIDTH = 640;
    private static final int TARGET_HEIGHT = 360;
    private static final long FRAME_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(200);
    private static final Pattern SAFE_DIAGNOSTIC = Pattern.compile(
            "(?:janus_(?:create|attach|destroy|stream_info|watch)"
                    + "|peer_(?:create_failed|offer_failed|offer_sent|answer_failed|answer_accepted)"
                    + "|data_channel_(?:open|closed)"
                    + "|ice_(?:checking|connected|disconnected|failed)"
                    + "|frame_(?:backpressure_timeout|send_failed)"
                    + "|frames_sent=[0-9]{1,10}"
                    + "|frames_in=[0-9]{1,10},queued=[0-9]{1,10},viewers=[1-4])");

    private final Context context;
    private final UvcCameraManager camera;
    private final ObicoConnector.LogSink logger;
    private final SecureRandom random = new SecureRandom();

    private volatile boolean running;
    private volatile boolean peerReady;
    private volatile boolean streaming;
    private volatile long generation;
    private volatile Listener listener;
    private volatile Transport transport;
    private volatile Socket socket;
    private volatile ServerSocket server;
    private volatile Process process;
    private volatile Thread worker;
    private volatile Thread captureWorker;
    private long nextCameraClient;

    public ObicoWebRtcBridge(Context context, UvcCameraManager camera,
                             ObicoConnector.LogSink logger) {
        this.context = context.getApplicationContext();
        this.camera = camera;
        this.logger = logger == null ? ignored -> { } : logger;
    }

    @Override
    public synchronized void start(String authToken, Listener callback) {
        stop();
        if (callback == null || authToken == null || authToken.isEmpty()) return;
        running = true;
        listener = callback;
        long current = ++generation;
        worker = new Thread(() -> runPeer(current, authToken), "obico-webrtc-peer");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public synchronized void stop() {
        running = false;
        generation++;
        peerReady = false;
        streaming = false;
        listener = null;
        Transport previousTransport = transport;
        transport = null;
        if (previousTransport != null) previousTransport.close();
        closeQuietly(socket);
        closeQuietly(server);
        Process oldProcess = process;
        process = null;
        if (oldProcess != null) oldProcess.destroy();
        Thread oldWorker = worker;
        worker = null;
        if (oldWorker != null) oldWorker.interrupt();
        Thread oldCapture = captureWorker;
        captureWorker = null;
        if (oldCapture != null) oldCapture.interrupt();
    }

    @Override
    public boolean isReady() {
        return running && peerReady && camera.getSettings().isEnabled();
    }

    @Override
    public boolean isStreaming() {
        return running && peerReady && streaming;
    }

    @Override
    public void sendJanus(String payload) {
        if (payload == null || payload.length() > MAX_LINE_CHARS / 2 || !isReady()) return;
        JsonObject message = new JsonObject();
        message.addProperty("type", "janus");
        message.addProperty("payload", payload);
        Transport current = transport;
        if (current != null) current.enqueueControl(message.toString());
    }

    private void runPeer(long current, String authToken) {
        File binary = new File(context.getApplicationInfo().nativeLibraryDir, LIBRARY_NAME);
        if (!binary.isFile() || !binary.canExecute()) {
            logger.log("Obico live stream unavailable: Android WebRTC peer is not executable");
            return;
        }
        while (isCurrent(current)) {
            try {
                runSession(current, authToken, binary);
            } catch (IOException | RuntimeException failure) {
                if (isCurrent(current)) {
                    logger.log("Obico live stream disconnected; retrying");
                }
            } finally {
                setPeerState(current, false, false);
            }
            if (!isCurrent(current)) break;
            try {
                Thread.sleep(5_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void runSession(long current, String authToken, File binary) throws IOException {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        ServerSocket acceptor = new ServerSocket(0, 1, loopback);
        Process child = null;
        Socket accepted = null;
        Transport activeTransport = null;
        try {
            synchronized (this) {
                if (!isCurrent(current)) return;
                server = acceptor;
            }
            acceptor.setSoTimeout(8_000);
            byte[] nonceBytes = new byte[24];
            random.nextBytes(nonceBytes);
            String nonce = Base64.encodeToString(nonceBytes, Base64.NO_WRAP | Base64.URL_SAFE);
            child = new ProcessBuilder(binary.getAbsolutePath(),
                    Integer.toString(acceptor.getLocalPort()), nonce)
                    .redirectErrorStream(true)
                    .start();
            synchronized (this) {
                if (!isCurrent(current)) return;
                process = child;
            }
            drainProcessOutput(child);

            accepted = acceptor.accept();
            synchronized (this) {
                if (!isCurrent(current)) return;
                socket = accepted;
            }
            accepted.setTcpNoDelay(true);
            accepted.setSoTimeout(5_000);
            BufferedReader input = new BufferedReader(new InputStreamReader(
                    accepted.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                    accepted.getOutputStream(), StandardCharsets.UTF_8));
            JsonObject hello = parseObject(readLineBounded(input));
            if (hello == null || !"hello".equals(string(hello, "type"))
                    || !nonce.equals(string(hello, "nonce"))) {
                throw new IOException("Invalid WebRTC peer handshake");
            }
            JsonObject reply = new JsonObject();
            reply.addProperty("type", "hello");
            reply.addProperty("nonce", nonce);
            writer.write(reply.toString());
            writer.newLine();
            writer.flush();
            JsonObject init = new JsonObject();
            init.addProperty("type", "init");
            init.addProperty("auth_token", authToken);
            writer.write(init.toString());
            writer.newLine();
            writer.flush();
            activeTransport = new Transport(accepted, writer);
            synchronized (this) {
                if (!isCurrent(current)) return;
                transport = activeTransport;
            }
            activeTransport.start();
            accepted.setSoTimeout(0);
            while (isCurrent(current)) {
                String line = readLineBounded(input);
                if (line == null) break;
                JsonObject response = parseObject(line);
                if (response == null) continue;
                String type = string(response, "type");
                if ("status".equals(type)) {
                    String state = string(response, "state");
                    if ("streaming".equals(state)) {
                        setPeerState(current, true, true);
                    } else if ("idle".equals(state) || "ready".equals(state)) {
                        setPeerState(current, true, false);
                    } else if ("error".equals(state)) {
                        setPeerState(current, false, false);
                    } else if ("diagnostic".equals(state)) {
                        String code = string(response, "message");
                        if (isCurrent(current) && isSafeDiagnostic(code)) {
                            logger.log("Obico live stream: " + code);
                        }
                    }
                } else if ("janus".equals(type)) {
                    String payload = string(response, "payload");
                    Listener callback = listener;
                    if (isCurrent(current) && callback != null && !payload.isEmpty()
                            && payload.length() <= MAX_LINE_CHARS / 2) {
                        callback.onJanusMessage(payload);
                    }
                }
            }
        } finally {
            synchronized (this) {
                if (transport == activeTransport) transport = null;
                if (socket == accepted) socket = null;
                if (server == acceptor) server = null;
                if (process == child) process = null;
            }
            if (activeTransport != null) activeTransport.close();
            closeQuietly(accepted);
            closeQuietly(acceptor);
            if (child != null) child.destroy();
        }
    }

    private void setPeerState(long current, boolean ready, boolean active) {
        if (!isCurrent(current)) return;
        boolean changed = peerReady != ready || streaming != active;
        peerReady = ready;
        streaming = active;
        if (active) startCapture(current);
        Listener callback = listener;
        if (changed && callback != null) callback.onStateChanged();
    }

    private synchronized void startCapture(long current) {
        if (captureWorker != null && captureWorker.isAlive()) return;
        captureWorker = new Thread(() -> captureFrames(current), "obico-webrtc-camera");
        captureWorker.setDaemon(true);
        captureWorker.start();
    }

    private void captureFrames(long current) {
        final String cameraClient;
        synchronized (this) {
            cameraClient = CAMERA_CLIENT_PREFIX + (++nextCameraClient);
        }
        long lastSequence = -1L;
        long nextFrame = 0L;
        boolean cameraLeaseAcquired = false;
        try {
            while (isCurrent(current) && streaming) {
                if (!camera.getSettings().isEnabled()) {
                    Thread.sleep(250L);
                    continue;
                }
                long delay = nextFrame - System.nanoTime();
                if (delay > 0L) TimeUnit.NANOSECONDS.sleep(delay);
                UvcCameraManager.Frame frame = camera.awaitFrame(
                        cameraClient, lastSequence, 1_000L);
                cameraLeaseAcquired = true;
                if (frame == null || frame.getJpeg() == null) continue;
                lastSequence = frame.getSequence();
                byte[] jpeg = prepareJpeg(frame.getJpeg());
                if (jpeg != null && isCurrent(current) && streaming) {
                    JsonObject packet = new JsonObject();
                    packet.addProperty("type", "frame");
                    packet.addProperty("jpeg", Base64.encodeToString(jpeg, Base64.NO_WRAP));
                    Transport currentTransport = transport;
                    if (currentTransport != null) currentTransport.replaceFrame(packet.toString());
                }
                nextFrame = System.nanoTime() + FRAME_INTERVAL_NANOS;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException failure) {
            logger.log("Obico live camera capture stopped; waiting for stream restart");
        } finally {
            if (cameraLeaseAcquired) releaseCamera(cameraClient);
            synchronized (this) {
                if (captureWorker == Thread.currentThread()) captureWorker = null;
                if (isCurrent(current) && streaming && captureWorker == null) startCapture(current);
            }
        }
    }

    private static byte[] prepareJpeg(byte[] source) {
        if (source == null || source.length == 0 || source.length > 4 * MAX_FRAME_BYTES) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(source, 0, source.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        if (bounds.outWidth <= TARGET_WIDTH && bounds.outHeight <= TARGET_HEIGHT
                && source.length <= TARGET_JPEG_BYTES) return source;
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= TARGET_WIDTH
                && bounds.outHeight / (sample * 2) >= TARGET_HEIGHT) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        Bitmap decoded = null;
        Bitmap scaled = null;
        try {
            decoded = BitmapFactory.decodeByteArray(source, 0, source.length, options);
            if (decoded == null) return null;
            double ratio = Math.min(1.0, Math.min((double) TARGET_WIDTH / decoded.getWidth(),
                    (double) TARGET_HEIGHT / decoded.getHeight()));
            int width = Math.max(1, (int) Math.round(decoded.getWidth() * ratio));
            int height = Math.max(1, (int) Math.round(decoded.getHeight() * ratio));
            scaled = width == decoded.getWidth() && height == decoded.getHeight()
                    ? decoded : Bitmap.createScaledBitmap(decoded, width, height, true);
            ByteArrayOutputStream out = new ByteArrayOutputStream(TARGET_JPEG_BYTES);
            scaled.compress(Bitmap.CompressFormat.JPEG, 65, out);
            if (out.size() > TARGET_JPEG_BYTES) {
                out.reset();
                scaled.compress(Bitmap.CompressFormat.JPEG, 45, out);
            }
            return out.size() <= MAX_FRAME_BYTES ? out.toByteArray() : null;
        } catch (OutOfMemoryError ignored) {
            return null;
        } finally {
            if (scaled != null && scaled != decoded) scaled.recycle();
            if (decoded != null) decoded.recycle();
        }
    }

    private boolean isCurrent(long current) {
        return running && generation == current && !Thread.currentThread().isInterrupted();
    }

    private void releaseCamera(String cameraClient) {
        try {
            camera.releaseClient(cameraClient);
        } catch (RuntimeException ignored) {
            // The HTTP snapshot lease is independent and must not be released here.
        }
    }

    private static JsonObject parseObject(String line) {
        try {
            if (line == null) return null;
            return new JsonParser().parse(line).getAsJsonObject();
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private static String string(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) return "";
        try {
            return object.get(key).getAsString();
        } catch (RuntimeException malformed) {
            return "";
        }
    }

    static boolean isSafeDiagnostic(String code) {
        return code != null && code.length() <= 96
                && SAFE_DIAGNOSTIC.matcher(code).matches();
    }

    private static String readLineBounded(BufferedReader input) throws IOException {
        StringBuilder line = new StringBuilder();
        int value;
        while ((value = input.read()) >= 0) {
            if (value == '\n') return line.toString();
            if (value != '\r') line.append((char) value);
            if (line.length() > MAX_LINE_CHARS) throw new IOException("Oversized peer message");
        }
        return line.length() == 0 ? null : line.toString();
    }

    private static void drainProcessOutput(Process child) {
        Thread drain = new Thread(() -> {
            byte[] buffer = new byte[1024];
            try {
                while (child.getInputStream().read(buffer) >= 0) {
                    // Native output can contain diagnostics; never forward it with a secret token.
                }
            } catch (IOException ignored) { }
        }, "obico-webrtc-output");
        drain.setDaemon(true);
        drain.start();
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException ignored) { }
    }

    /** Signaling has priority; at most one unsent camera frame is retained under backpressure. */
    private static final class Transport {
        private final Socket socket;
        private final BufferedWriter writer;
        private final BlockingQueue<String> signaling = new ArrayBlockingQueue<>(128);
        private final AtomicReference<String> latestFrame = new AtomicReference<>();
        private volatile boolean closed;
        private Thread writerThread;

        Transport(Socket socket, BufferedWriter writer) {
            this.socket = socket;
            this.writer = writer;
        }

        void start() {
            writerThread = new Thread(this::writeLoop, "obico-webrtc-writer");
            writerThread.setDaemon(true);
            writerThread.start();
        }

        void enqueueControl(String line) {
            if (closed || line.length() > MAX_LINE_CHARS || !signaling.offer(line)) {
                if (!closed) close();
            }
        }

        void replaceFrame(String line) {
            if (!closed && line.length() <= MAX_LINE_CHARS) latestFrame.set(line);
        }

        void close() {
            closed = true;
            closeQuietly(socket); // Unblock a writer stuck in a native socket flush.
            Thread oldWriter = writerThread;
            if (oldWriter != null) oldWriter.interrupt();
            signaling.clear();
            latestFrame.set(null);
        }

        private void writeLoop() {
            try {
                while (!closed) {
                    String line = signaling.poll(50L, TimeUnit.MILLISECONDS);
                    if (line == null) line = latestFrame.getAndSet(null);
                    if (line == null) continue;
                    writer.write(line);
                    writer.newLine();
                    writer.flush();
                }
            } catch (IOException failure) {
                close();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
