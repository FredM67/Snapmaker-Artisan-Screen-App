package fabscreen.platform.base.obico;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Long-lived Obico cloud connector for FabScreen.
 *
 * <p>The connector parses only the explicitly supported Obico command envelopes. Supplied
 * handlers remain responsible for checking the live machine state immediately before a command
 * reaches the controller.</p>
 */
public final class ObicoConnector {
    public interface SnapshotProvider {
        ObicoPrinterSnapshot getSnapshot() throws Exception;
    }

    public interface JpegProvider {
        byte[] getJpeg() throws Exception;
    }

    public interface RemoteCommandHandler {
        boolean handle(ObicoRemoteCommand.Type command);
    }

    public interface PassthruHandler {
        void handle(ObicoPassthruRequest request, PassthruCallback callback);
    }

    public interface PassthruCallback {
        void onSuccess();

        void onDownloadAccepted(String targetPath);

        void onJsonResult(JsonElement result);

        void onError(String error);
    }

    public interface StatusListener {
        void onStatus(ObicoConnectionStatus status);
    }

    public interface ResultCallback<T> {
        void onResult(T result);
    }

    public interface LogSink {
        void log(String message);
    }

    private static final MediaType JPEG = MediaType.parse("image/jpeg");
    private static final MediaType EMPTY_MEDIA_TYPE = MediaType.parse("application/octet-stream");

    private static final int MAX_HTTP_RESPONSE_BYTES = 64 * 1024;
    private static final int MAX_JPEG_BYTES = 4 * 1024 * 1024;
    private static final int MAX_OUTBOUND_MESSAGE_CHARS = 512 * 1024;
    private static final long TELEMETRY_POLL_MILLIS = 2_000L;
    // Obico's browser normally receives two-second status updates over its data channel.  This
    // Android agent does not have that secondary channel, so keep the device WebSocket at the
    // same cadence; otherwise target-temperature changes can sit behind the old 30-second
    // heartbeat.
    private static final long TELEMETRY_HEARTBEAT_MILLIS = 2_000L;
    // Snapshot upload is still a fallback rather than WebRTC.  Viewer-boosted images refresh
    // the browser, but Obico excludes those images from failure analysis. Keep an independent
    // unboosted cadence so opening the viewer cannot starve AI-eligible images.
    private static final long SNAPSHOT_WATCHING_MILLIS = 1_000L;
    private static final long SNAPSHOT_VIEWER_DURING_PRINT_MILLIS = 10_000L;
    // Once the cloud rate-limits snapshots, reserve upload capacity for AI-eligible images.
    private static final long SNAPSHOT_VIEWER_AFTER_RATE_LIMIT_MILLIS = 20_000L;
    // Obico counts rejected POSTs too; wait past a full minute bucket to avoid a 429 loop.
    private static final long SNAPSHOT_RATE_LIMIT_COOLDOWN_MILLIS = 65_000L;
    private static final long SNAPSHOT_AI_WATCHING_MILLIS = 10_000L;
    // Obico measures its minimum detection interval from the previous prediction, which can
    // happen after the HTTP upload. Leave a small margin after an accepted image.
    private static final long SNAPSHOT_AI_AFTER_ACCEPTED_MILLIS = 12_000L;
    private static final long SNAPSHOT_PRINTING_MILLIS = 60_000L;
    private static final long LIVE_STREAM_STARTUP_DIAGNOSTIC_MILLIS = 10_000L;
    // A stalled still-image POST must not hold the only snapshot slot for the general
    // client's 30-second read/write timeout. This timeout covers the complete POST.
    private static final long SNAPSHOT_POST_TIMEOUT_MILLIS = 4_000L;
    private static final long CANCEL_EVENT_WINDOW_MILLIS = 2L * 60L * 1000L;
    private static final int DUPLICATE_TOKEN_CLOSE_CODE = 4321;
    private static final int PASSTHRU_RESPONSE_CACHE_SIZE = 128;

    private final SnapshotProvider snapshotProvider;
    private final JpegProvider jpegProvider;
    private final ObicoLiveStream liveStream;
    private final RemoteCommandHandler remoteCommandHandler;
    private final PassthruHandler passthruHandler;
    private final StatusListener statusListener;
    private final LogSink logSink;
    private final OkHttpClient client;
    private final OkHttpClient snapshotClient;
    private final ScheduledExecutorService executor;
    // Camera capture may wait for a new frame. Keep it away from telemetry, remote commands,
    // and the WebSocket message executor even when the camera becomes unavailable.
    private final ScheduledExecutorService snapshotExecutor;
    private final ObicoCommandGate commandGate = new ObicoCommandGate();
    private final AtomicLong generation = new AtomicLong();
    private final AtomicReference<SnapshotAttempt> snapshotUploadInFlight = new AtomicReference<>();
    private final Object passthruLock = new Object();
    private final Set<String> passthruInFlight = new HashSet<>();
    private final LinkedHashMap<String, String> passthruResponses =
            new LinkedHashMap<String, String>(PASSTHRU_RESPONSE_CACHE_SIZE + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > PASSTHRU_RESPONSE_CACHE_SIZE;
                }
            };
    private final AtomicReference<ObicoConnectionStatus> status =
            new AtomicReference<>(ObicoConnectionStatus.disabled());
    private volatile ObicoSettings settings = ObicoSettings.defaults();
    private volatile WebSocket webSocket;
    private volatile ScheduledFuture<?> telemetryTask;
    private volatile ScheduledFuture<?> snapshotTask;
    private volatile Future<?> snapshotCaptureTask;
    private volatile ScheduledFuture<?> reconnectTask;
    private volatile Call snapshotCall;
    private volatile boolean stopped;
    private volatile boolean remoteViewing;
    private volatile boolean remoteShouldWatch;
    private volatile boolean cancelRequested;
    private volatile long cancelRequestedAtNanos;
    private volatile boolean terminalFailureReported;
    private volatile long terminalFailureAtNanos;
    private volatile int reconnectAttempt;
    private volatile long lastStatusSentAtMillis;
    private volatile long lastSnapshotAttemptAtMillis;
    private volatile long lastAiSnapshotAttemptAtMillis;
    private volatile boolean lastAiSnapshotAccepted;
    private volatile long snapshotRateLimitedUntilMillis;
    private volatile boolean viewerSnapshotBackoff;
    private volatile boolean lastAdvertisedLiveStreamReady;
    private volatile boolean initialSettingsSent;
    private volatile ObicoPrintState previousPrintState;

    public ObicoConnector(
            SnapshotProvider snapshotProvider,
            JpegProvider jpegProvider,
            RemoteCommandHandler remoteCommandHandler,
            PassthruHandler passthruHandler,
            StatusListener statusListener,
            LogSink logSink) {
        this(snapshotProvider, jpegProvider, remoteCommandHandler, passthruHandler,
                statusListener, logSink, null);
    }

    public ObicoConnector(
            SnapshotProvider snapshotProvider,
            JpegProvider jpegProvider,
            RemoteCommandHandler remoteCommandHandler,
            PassthruHandler passthruHandler,
            StatusListener statusListener,
            LogSink logSink,
            ObicoLiveStream liveStream) {
        this(
                snapshotProvider,
                jpegProvider,
                remoteCommandHandler,
                passthruHandler,
                statusListener,
                logSink,
                defaultHttpClient(),
                Executors.newScheduledThreadPool(2, new ObicoThreadFactory()),
                liveStream);
    }

    ObicoConnector(
            SnapshotProvider snapshotProvider,
            JpegProvider jpegProvider,
            RemoteCommandHandler remoteCommandHandler,
            PassthruHandler passthruHandler,
            StatusListener statusListener,
            LogSink logSink,
            OkHttpClient client,
            ScheduledExecutorService executor) {
        this(snapshotProvider, jpegProvider, remoteCommandHandler, passthruHandler,
                statusListener, logSink, client, executor, null);
    }

    ObicoConnector(
            SnapshotProvider snapshotProvider,
            JpegProvider jpegProvider,
            RemoteCommandHandler remoteCommandHandler,
            PassthruHandler passthruHandler,
            StatusListener statusListener,
            LogSink logSink,
            OkHttpClient client,
            ScheduledExecutorService executor,
            ObicoLiveStream liveStream) {
        if (snapshotProvider == null || remoteCommandHandler == null || passthruHandler == null) {
            throw new IllegalArgumentException("Snapshot and remote-command providers are required");
        }
        this.snapshotProvider = snapshotProvider;
        this.jpegProvider = jpegProvider;
        this.liveStream = liveStream;
        this.remoteCommandHandler = remoteCommandHandler;
        this.passthruHandler = passthruHandler;
        this.statusListener = statusListener == null ? ignored -> { } : statusListener;
        this.logSink = logSink == null ? ignored -> { } : logSink;
        this.client = client;
        this.snapshotClient = client.newBuilder()
                .callTimeout(SNAPSHOT_POST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                .build();
        this.executor = executor;
        this.snapshotExecutor = Executors.newSingleThreadScheduledExecutor(new ObicoThreadFactory());
    }

    private static OkHttpClient defaultHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(15L, TimeUnit.SECONDS)
                .readTimeout(30L, TimeUnit.SECONDS)
                .writeTimeout(30L, TimeUnit.SECONDS)
                .pingInterval(25L, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .build();
    }

    public synchronized void applySettings(ObicoSettings nextSettings) {
        ensureRunning();
        if (nextSettings == null) {
            throw new IllegalArgumentException("Obico settings are required");
        }
        validateAuthToken(nextSettings.getAuthToken());
        String canonicalServer = ObicoProtocol.canonicalServerUrl(
                nextSettings.getServerUrl(),
                nextSettings.isAllowInsecureServer());
        if (!settings.getServerUrl().equals(canonicalServer)
                || !settings.getAuthToken().equals(nextSettings.getAuthToken())) {
            snapshotRateLimitedUntilMillis = 0L;
            viewerSnapshotBackoff = false;
        }
        settings = new ObicoSettings(
                nextSettings.isEnabled(),
                canonicalServer,
                nextSettings.getAuthToken(),
                nextSettings.isRemoteControlEnabled(),
                nextSettings.isCameraUploadsEnabled(),
                nextSettings.isAllowInsecureServer());
        restartConnection();
    }

    /** Internal consumers only. Never serialize this object because it contains the token. */
    public ObicoSettings getSettings() {
        return settings;
    }

    public ObicoConnectionStatus getStatus() {
        return status.get();
    }

    /** Sends a fresh status promptly after a locally confirmed cloud control. */
    public void requestImmediateTelemetry() {
        long runGeneration = generation.get();
        executor.execute(() -> {
            if (!isCurrent(runGeneration)) return;
            WebSocket socket = webSocket;
            if (socket != null) sendStatus(socket, safeSnapshot(), null, false);
        });
    }

    public void notifyPrintCancelled() {
        ObicoPrintState lastState = previousPrintState;
        if (lastState == null || !lastState.isActive()) {
            return;
        }
        cancelRequested = true;
        cancelRequestedAtNanos = System.nanoTime();
    }

    /** Clears a queued cancellation marker when FabScreen reports that stop did not succeed. */
    public void clearPendingPrintCancellation() {
        cancelRequested = false;
        cancelRequestedAtNanos = 0L;
    }

    /** Records the controller-confirmed outcome of a command previously accepted into the queue. */
    public void notifyRemoteCommandOutcome(ObicoRemoteCommand.Type command, String result) {
        if (command == null) {
            return;
        }
        auditCommand(
                command.getWireName(),
                result == null ? "finished" : result,
                System.currentTimeMillis()
        );
    }

    /** Marks a controller-reported abnormal stop so Obico receives PrintFailed, not PrintDone. */
    public void notifyPrintFailed() {
        ObicoPrintState lastState = previousPrintState;
        if (lastState == null || !lastState.isActive()) {
            return;
        }
        clearPendingPrintCancellation();
        terminalFailureReported = true;
        terminalFailureAtNanos = System.nanoTime();
    }

    public synchronized void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        generation.incrementAndGet();
        cancelConnectionTasks();
        cancelSnapshotUpload();
        remoteViewing = false;
        remoteShouldWatch = false;
        updateStatus(current -> current.withRemoteWatch(false, false, 0L));
        WebSocket socket = webSocket;
        webSocket = null;
        if (liveStream != null) liveStream.stop();
        if (socket != null) {
            socket.close(1000, "FabScreen stopped");
        }
        executor.shutdownNow();
        snapshotExecutor.shutdownNow();
        client.dispatcher().cancelAll();
        client.connectionPool().evictAll();
        publishConnection(ObicoConnectionStatus.State.STOPPED, "Stopped", 0, false);
    }

    /**
     * Manual fallback for the six-digit verification code displayed by Obico.
     */
    public void link(
            String serverUrl,
            boolean allowInsecureServer,
            String sixDigitCode,
            ResultCallback<ObicoLinkResult> callback) {
        ensureRunning();
        String code = sixDigitCode == null ? "" : sixDigitCode.trim();
        if (!code.matches("\\d{6}")) {
            deliver(callback, ObicoLinkResult.failure("Enter the six-digit verification code"));
            return;
        }
        final String canonical;
        try {
            canonical = ObicoProtocol.canonicalServerUrl(serverUrl, allowInsecureServer);
        } catch (IllegalArgumentException exception) {
            deliver(callback, ObicoLinkResult.failure(exception.getMessage()));
            return;
        }
        exchangeVerificationCode(canonical, allowInsecureServer, code, callback);
    }

    public void testConnection(
            String serverUrl,
            boolean allowInsecureServer,
            String authToken,
            ResultCallback<String> callback) {
        ensureRunning();
        if (authToken == null || authToken.trim().isEmpty()) {
            deliver(callback, "Not linked");
            return;
        }
        try {
            validateAuthToken(authToken);
        } catch (IllegalArgumentException exception) {
            deliver(callback, "Stored credentials are invalid; link again");
            return;
        }

        final String endpoint;
        try {
            endpoint = ObicoProtocol.endpointUrl(
                    serverUrl,
                    allowInsecureServer,
                    "/api/v1/octo/printer/");
        } catch (IllegalArgumentException exception) {
            deliver(callback, exception.getMessage());
            return;
        }
        Request request = new Request.Builder()
                .url(endpoint)
                .header("Authorization", "Token " + authToken.trim())
                .header("Accept", "application/json")
                .get()
                .build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException exception) {
                deliver(callback, networkFailureMessage("Connection failed", exception));
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (Response ignored = response) {
                    if (!response.isSuccessful()) {
                        deliver(callback, "Connection failed: HTTP " + response.code());
                        return;
                    }
                    String body = readBounded(response.body(), MAX_HTTP_RESPONSE_BYTES);
                    String printerName = parsePrinterName(body);
                    deliver(callback, printerName.isEmpty()
                            ? "Connection successful"
                            : "Connected to " + printerName);
                } catch (IOException | RuntimeException exception) {
                    deliver(callback, networkFailureMessage("Connection failed", exception));
                }
            }
        });
    }

    private synchronized void restartConnection() {
        long runGeneration = generation.incrementAndGet();
        cancelConnectionTasks();
        cancelSnapshotUpload();
        WebSocket oldSocket = webSocket;
        webSocket = null;
        if (liveStream != null) liveStream.stop();
        if (oldSocket != null) {
            oldSocket.close(1000, "FabScreen configuration changed");
        }
        commandGate.reset();
        synchronized (passthruLock) {
            passthruInFlight.clear();
            passthruResponses.clear();
        }
        remoteViewing = false;
        remoteShouldWatch = false;
        updateStatus(current -> current.withRemoteWatch(false, false, 0L));
        cancelRequested = false;
        cancelRequestedAtNanos = 0L;
        terminalFailureReported = false;
        terminalFailureAtNanos = 0L;
        reconnectAttempt = 0;
        previousPrintState = null;
        lastStatusSentAtMillis = 0L;
        lastSnapshotAttemptAtMillis = 0L;
        lastAiSnapshotAttemptAtMillis = 0L;
        lastAiSnapshotAccepted = false;
        lastAdvertisedLiveStreamReady = false;
        initialSettingsSent = false;

        ObicoSettings current = settings;
        if (!current.isEnabled()) {
            publishConnection(ObicoConnectionStatus.State.DISABLED, "Disabled", 0, false);
            return;
        }
        if (!current.isLinked()) {
            publishConnection(ObicoConnectionStatus.State.NOT_LINKED, "Not linked", 0, false);
            return;
        }

        telemetryTask = executor.scheduleWithFixedDelay(
                () -> telemetryTick(runGeneration),
                TELEMETRY_POLL_MILLIS,
                TELEMETRY_POLL_MILLIS,
                TimeUnit.MILLISECONDS);
        snapshotTask = snapshotExecutor.scheduleWithFixedDelay(
                () -> snapshotTick(runGeneration),
                SNAPSHOT_WATCHING_MILLIS,
                SNAPSHOT_WATCHING_MILLIS,
                TimeUnit.MILLISECONDS);
        executor.execute(() -> connectWebSocket(runGeneration));
    }

    private synchronized void cancelConnectionTasks() {
        ScheduledFuture<?> telemetry = telemetryTask;
        telemetryTask = null;
        if (telemetry != null) {
            telemetry.cancel(false);
        }
        ScheduledFuture<?> camera = snapshotTask;
        snapshotTask = null;
        if (camera != null) {
            camera.cancel(false);
        }
        ScheduledFuture<?> reconnect = reconnectTask;
        reconnectTask = null;
        if (reconnect != null) {
            reconnect.cancel(false);
        }
    }

    private void connectWebSocket(long runGeneration) {
        if (!isCurrent(runGeneration)) {
            return;
        }
        if (liveStream != null && settings.isCameraUploadsEnabled() && jpegProvider != null) {
            liveStream.start(settings.getAuthToken(), new ObicoLiveStream.Listener() {
                @Override
                public void onJanusMessage(String payload) {
                    WebSocket current = webSocket;
                    if (current != null) sendJanusResponse(runGeneration, current, payload);
                }

                @Override
                public void onStateChanged() {
                    executor.execute(() -> {
                        WebSocket current = webSocket;
                        if (current != null) liveStreamStatusChanged(runGeneration, current);
                    });
                }
            });
        }
        ObicoSettings current = settings;
        publishConnection(
                reconnectAttempt == 0
                        ? ObicoConnectionStatus.State.CONNECTING
                        : ObicoConnectionStatus.State.RETRYING,
                reconnectAttempt == 0 ? "Connecting" : "Reconnecting",
                reconnectAttempt,
                false);

        Request request;
        try {
            request = new Request.Builder()
                    .url(ObicoProtocol.websocketUrl(
                            current.getServerUrl(),
                            current.isAllowInsecureServer()))
                    // Obico's WebSocket consumer treats this scheme case-sensitively.
                    .header("Authorization", "bearer " + current.getAuthToken())
                    .build();
        } catch (RuntimeException exception) {
            publishConnection(
                    ObicoConnectionStatus.State.ERROR,
                    "Invalid Obico connection settings",
                    reconnectAttempt,
                    false);
            return;
        }

        AtomicBoolean finished = new AtomicBoolean();
        client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket socket, Response response) {
                if (!isCurrent(runGeneration)) {
                    socket.close(1000, "Stale FabScreen connection");
                    return;
                }
                webSocket = socket;
                reconnectAttempt = 0;
                publishConnection(
                        ObicoConnectionStatus.State.CONNECTED,
                        "Connected",
                        0,
                        true);
                ObicoPrinterSnapshot snapshot = safeSnapshot();
                // Obico creates one player per webcam name and does not replace a JPEG-only
                // player when the settings later change. Until the local peer is ready, send
                // no webcam entry. Obico can still display the last uploaded image, and the
                // ready transition below can add the live webcam to an already-open page.
                sendStatus(socket, snapshot, null, true);
                if (liveStreamConfigured() && !liveStreamReady()) {
                    executor.schedule(() -> {
                        if (isCurrent(runGeneration) && webSocket == socket
                                && !liveStreamReady()) {
                            log("Obico live stream is still unavailable; showing snapshot "
                                    + "fallback until the camera peer is ready");
                        }
                    }, LIVE_STREAM_STARTUP_DIAGNOSTIC_MILLIS, TimeUnit.MILLISECONDS);
                }
                previousPrintState = snapshot.getState();
                log("Obico connected");
            }

            @Override
            public void onMessage(WebSocket socket, String text) {
                if (!isCurrent(runGeneration) || socket != webSocket) {
                    return;
                }
                if (text == null || text.length() > ObicoProtocol.MAX_INBOUND_MESSAGE_CHARS) {
                    log("Obico message rejected: payload too large");
                    socket.close(1009, "Message too large");
                    return;
                }
                executor.execute(() -> handleServerMessage(runGeneration, text));
            }

            @Override
            public void onMessage(WebSocket socket, ByteString bytes) {
                if (bytes != null && bytes.size() > ObicoProtocol.MAX_INBOUND_MESSAGE_CHARS) {
                    socket.close(1009, "Message too large");
                } else {
                    // FabScreen's bounded connector supports the JSON command channel only.
                    socket.close(1003, "Binary messages are unsupported");
                }
            }

            @Override
            public void onClosing(WebSocket socket, int code, String reason) {
                socket.close(code, "");
            }

            @Override
            public void onClosed(WebSocket socket, int code, String reason) {
                finishSocket(socket, runGeneration, code, null, finished);
            }

            @Override
            public void onFailure(WebSocket socket, Throwable throwable, Response response) {
                finishSocket(socket, runGeneration, 0, throwable, finished);
            }
        });
    }

    private synchronized void finishSocket(
            WebSocket socket,
            long runGeneration,
            int closeCode,
            Throwable failure,
            AtomicBoolean finished) {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        if (webSocket == socket) {
            webSocket = null;
            initialSettingsSent = false;
            lastAdvertisedLiveStreamReady = false;
            remoteViewing = false;
            remoteShouldWatch = false;
            updateStatus(current -> current.withRemoteWatch(false, false, 0L));
        }
        if (!isCurrent(runGeneration)) {
            return;
        }
        if (webSocket == null && liveStream != null) liveStream.stop();
        if (closeCode == DUPLICATE_TOKEN_CLOSE_CODE) {
            publishConnection(
                    ObicoConnectionStatus.State.ERROR,
                    "This printer token is already in use; reconnect stopped",
                    reconnectAttempt,
                    false);
            log("Obico reconnect stopped: duplicate printer token");
            return;
        }
        scheduleReconnect(runGeneration, failure);
    }

    private synchronized void scheduleReconnect(long runGeneration, Throwable failure) {
        if (!isCurrent(runGeneration) || reconnectTask != null) {
            return;
        }
        reconnectAttempt = Math.min(reconnectAttempt + 1, 30);
        long baseSeconds = Math.min(120L, 2L << Math.min(6, reconnectAttempt - 1));
        long jitterMillis = (long) (Math.random() * Math.min(5_000L, baseSeconds * 200L));
        long delayMillis = baseSeconds * 1000L + jitterMillis;
        publishConnection(
                ObicoConnectionStatus.State.RETRYING,
                failure == null ? "Connection closed; retrying" : "Connection interrupted; retrying",
                reconnectAttempt,
                false);
        reconnectTask = executor.schedule(() -> {
            synchronized (ObicoConnector.this) {
                reconnectTask = null;
            }
            connectWebSocket(runGeneration);
        }, delayMillis, TimeUnit.MILLISECONDS);
    }

    private void telemetryTick(long runGeneration) {
        if (!isCurrent(runGeneration)) {
            return;
        }
        WebSocket socket = webSocket;
        if (socket == null) {
            return;
        }
        long now = System.currentTimeMillis();
        ObicoPrinterSnapshot snapshot = safeSnapshot();
        long cancelAgeNanos = cancelRequestedAtNanos <= 0L
                ? Long.MAX_VALUE
                : System.nanoTime() - cancelRequestedAtNanos;
        boolean recentCancelRequest = cancelRequested
                && cancelAgeNanos >= 0L
                && TimeUnit.NANOSECONDS.toMillis(cancelAgeNanos) <= CANCEL_EVENT_WINDOW_MILLIS;
        long failureAgeNanos = terminalFailureAtNanos <= 0L
                ? Long.MAX_VALUE
                : System.nanoTime() - terminalFailureAtNanos;
        boolean recentTerminalFailure = terminalFailureReported
                && failureAgeNanos >= 0L
                && TimeUnit.NANOSECONDS.toMillis(failureAgeNanos) <= CANCEL_EVENT_WINDOW_MILLIS;
        String event = ObicoProtocol.transitionEvent(
                previousPrintState,
                snapshot.getState(),
                recentCancelRequest,
                recentTerminalFailure);
        boolean stateChanged = previousPrintState != snapshot.getState();
        boolean liveChanged = liveStreamReady() != lastAdvertisedLiveStreamReady;
        if (stateChanged || event != null || now - lastStatusSentAtMillis >= TELEMETRY_HEARTBEAT_MILLIS) {
            sendStatus(socket, snapshot, event, liveChanged);
        } else if (liveChanged) {
            sendStatus(socket, snapshot, null, true);
        }
        previousPrintState = snapshot.getState();
        if ("PrintCancelled".equals(event)
                || "PrintFailed".equals(event)
                || (cancelRequested && !recentCancelRequest)
                || snapshot.getState() == ObicoPrintState.ERROR) {
            cancelRequested = false;
            cancelRequestedAtNanos = 0L;
        }
        if ("PrintFailed".equals(event)
                || (terminalFailureReported && !recentTerminalFailure)
                || snapshot.getState() == ObicoPrintState.ERROR) {
            terminalFailureReported = false;
            terminalFailureAtNanos = 0L;
        }
    }

    void snapshotTick(long runGeneration) {
        if (!isCurrent(runGeneration) || webSocket == null
                || !settings.isCameraUploadsEnabled() || jpegProvider == null) {
            return;
        }
        boolean printing = previousPrintState == ObicoPrintState.PRINTING;
        boolean viewing = remoteViewing;
        boolean shouldWatch = remoteShouldWatch;
        if (!printing && !viewing && !shouldWatch) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < snapshotRateLimitedUntilMillis) {
            return;
        }
        // Obico's viewing_boost=true images are for the live viewer only. Always prioritize an
        // unboosted image when due, then use otherwise-idle upload slots for viewer refreshes.
        // Without an AI watch request, preserve the sparse print fallback even with a viewer.
        long aiInterval = shouldWatch
                ? (lastAiSnapshotAccepted
                        ? SNAPSHOT_AI_AFTER_ACCEPTED_MILLIS : SNAPSHOT_AI_WATCHING_MILLIS)
                : printing ? SNAPSHOT_PRINTING_MILLIS : Long.MAX_VALUE;
        if (aiInterval != Long.MAX_VALUE
                && now - lastAiSnapshotAttemptAtMillis >= aiInterval) {
            queueSnapshotUpload(runGeneration, false);
        } else if (viewing && !liveStreamIsStreaming()
                && now - lastSnapshotAttemptAtMillis
                        >= viewerSnapshotIntervalMillis(printing, shouldWatch)) {
            queueSnapshotUpload(runGeneration, true);
        }
    }

    private void sendStatus(
            WebSocket socket,
            ObicoPrinterSnapshot snapshot,
            String event,
            boolean withSettings) {
        boolean liveReady = liveStreamReady();
        boolean cameraConfigured = settings.isCameraUploadsEnabled() && jpegProvider != null;
        // When a live peer is configured but unready, an empty webcam list is recoverable by
        // Obico's already-open page. Advertising "jpeg" here would permanently pin that page
        // to the snapshot player, even if the peer becomes ready moments later.
        boolean advertiseWebcam = cameraConfigured && (!liveStreamConfigured() || liveReady);
        String payload = ObicoProtocol.statusPayload(
                snapshot,
                event,
                withSettings,
                advertiseWebcam,
                liveReady);
        if (payload.length() > MAX_OUTBOUND_MESSAGE_CHARS) {
            log("Obico telemetry rejected: payload too large");
            return;
        }
        if (socket.send(payload)) {
            long now = System.currentTimeMillis();
            lastStatusSentAtMillis = now;
            if (withSettings) {
                lastAdvertisedLiveStreamReady = liveReady;
                initialSettingsSent = true;
            }
            updateStatus(current -> current.withTelemetry(now));
        }
    }

    private boolean liveStreamReady() {
        return liveStreamConfigured() && liveStream.isReady();
    }

    private boolean liveStreamIsStreaming() {
        return liveStreamConfigured() && liveStream.isStreaming();
    }

    private long viewerSnapshotIntervalMillis(boolean printing, boolean shouldWatch) {
        if (viewerSnapshotBackoff) return SNAPSHOT_VIEWER_AFTER_RATE_LIMIT_MILLIS;
        // Boosted frames share Obico's POST bucket with failure-detection frames. During a
        // print, let unboosted AI images use most of that budget; WebRTC is the live path.
        return printing || shouldWatch
                ? SNAPSHOT_VIEWER_DURING_PRINT_MILLIS : SNAPSHOT_WATCHING_MILLIS;
    }

    private boolean liveStreamConfigured() {
        return liveStream != null && settings.isCameraUploadsEnabled() && jpegProvider != null;
    }

    private void liveStreamStatusChanged(long runGeneration, WebSocket socket) {
        if (!isCurrent(runGeneration) || webSocket != socket) return;
        boolean ready = liveStreamReady();
        if (ready && (!initialSettingsSent || ready != lastAdvertisedLiveStreamReady)) {
            sendStatus(socket, safeSnapshot(), null, true);
        }
    }

    private void sendJanusResponse(long runGeneration, WebSocket socket, String payload) {
        if (!isCurrent(runGeneration) || webSocket != socket || payload == null
                || payload.length() > MAX_OUTBOUND_MESSAGE_CHARS / 2) return;
        JsonObject envelope = new JsonObject();
        envelope.addProperty("janus", payload);
        socket.send(envelope.toString());
    }

    private void handleServerMessage(long runGeneration, String message) {
        if (!isCurrent(runGeneration)) {
            return;
        }
        if (liveStreamReady()) {
            try {
                JsonObject envelope = new JsonParser().parse(message).getAsJsonObject();
                if (envelope.has("janus") && envelope.get("janus").isJsonPrimitive()) {
                    String janus = envelope.get("janus").getAsString();
                    if (janus.length() <= MAX_OUTBOUND_MESSAGE_CHARS / 2) {
                        liveStream.sendJanus(janus);
                    }
                    return;
                }
            } catch (RuntimeException ignored) {
                // Other Obico messages are handled by the existing bounded parsers below.
            }
        }
        ObicoProtocol.RemoteViewingStatus remoteStatus =
                ObicoProtocol.parseRemoteViewingStatus(message);
        if (remoteStatus != null) {
            boolean startedViewing;
            synchronized (this) {
                // A message queued before a settings restart must not restore stale watch flags.
                if (!isCurrent(runGeneration)) {
                    return;
                }
                boolean updatedViewing = remoteStatus.resolveViewing(remoteViewing);
                startedViewing = updatedViewing && !remoteViewing;
                remoteViewing = updatedViewing;
                remoteShouldWatch = remoteStatus.resolveShouldWatch(remoteShouldWatch);
                updateStatus(current -> current.withRemoteWatch(
                        remoteViewing, remoteShouldWatch, System.currentTimeMillis()));
            }
            if (startedViewing && settings.isCameraUploadsEnabled()) {
                queueSnapshotUpload(runGeneration, true);
            }
        }

        List<ObicoRemoteCommand> commands = ObicoProtocol.parseRemoteCommands(message);
        for (ObicoRemoteCommand command : commands) {
            handleRemoteCommand(runGeneration, command);
        }

        ObicoPassthruParseResult passthru = ObicoProtocol.parsePassthruRequest(message);
        if (passthru.isPresent()) {
            handlePassthru(runGeneration, passthru);
        }
    }

    private void handlePassthru(
            long runGeneration,
            ObicoPassthruParseResult parsed
    ) {
        if (!isCurrent(runGeneration)) return;
        String reference = parsed.getReference();
        if (reference == null || reference.isEmpty()) {
            log("Obico passthrough request rejected: invalid reference");
            return;
        }

        synchronized (passthruLock) {
            String cached = passthruResponses.get(reference);
            if (cached != null) {
                WebSocket socket = webSocket;
                if (socket != null && isCurrent(runGeneration)) socket.send(cached);
                return;
            }
            if (passthruInFlight.contains(reference)) return;
        }

        if (!parsed.isAccepted()) {
            completePassthru(
                    runGeneration,
                    reference,
                    null,
                    null,
                    false,
                    parsed.getError());
            return;
        }

        ObicoPassthruRequest request = parsed.getRequest();
        String commandName = "passthru:" + request.getType().name().toLowerCase(Locale.US);
        long now = System.currentTimeMillis();
        if (!settings.isRemoteControlEnabled()
                && request.getType() != ObicoPassthruRequest.Type.LIST_FILES) {
            auditCommand(commandName, "disabled", now);
            completePassthru(
                    runGeneration,
                    reference,
                    null,
                    null,
                    false,
                    "Remote printer control is disabled in FabScreen");
            return;
        }

        synchronized (passthruLock) {
            // Recheck after authorization work so two copies cannot both pass the first lookup.
            String cached = passthruResponses.get(reference);
            if (cached != null) {
                WebSocket socket = webSocket;
                if (socket != null && isCurrent(runGeneration)) socket.send(cached);
                return;
            }
            if (!passthruInFlight.add(reference)) return;
        }

        AtomicBoolean completed = new AtomicBoolean();
        PassthruCallback callback = new PassthruCallback() {
            @Override
            public void onSuccess() {
                finish(null, null, true, null);
            }

            @Override
            public void onDownloadAccepted(String targetPath) {
                finish(targetPath, null, true, null);
            }

            @Override
            public void onJsonResult(JsonElement result) {
                finish(null, result, true, null);
            }

            @Override
            public void onError(String error) {
                finish(null, null, false, error);
            }

            private void finish(
                    String targetPath,
                    JsonElement jsonResult,
                    boolean success,
                    String error
            ) {
                if (!completed.compareAndSet(false, true)) return;
                executor.execute(() -> {
                    completePassthru(
                            runGeneration,
                            reference,
                            targetPath,
                            jsonResult,
                            success,
                            error);
                    auditCommand(
                            commandName,
                            success ? "succeeded" : "rejected",
                            System.currentTimeMillis());
                });
            }
        };

        try {
            passthruHandler.handle(request, callback);
            auditCommand(commandName, "queued", now);
        } catch (RuntimeException failure) {
            callback.onError("FabScreen could not execute this request");
        }
    }

    private void completePassthru(
            long runGeneration,
            String reference,
            String targetPath,
            JsonElement jsonResult,
            boolean success,
            String error
    ) {
        String payload;
        try {
            payload = !success
                    ? ObicoProtocol.passthruErrorPayload(reference, error)
                    : jsonResult != null
                    ? ObicoProtocol.passthruJsonAckPayload(reference, jsonResult)
                    : targetPath != null
                    ? ObicoProtocol.passthruDownloadAckPayload(reference, targetPath)
                    : ObicoProtocol.passthruAckPayload(reference);
            if (payload.length() > MAX_OUTBOUND_MESSAGE_CHARS) {
                payload = ObicoProtocol.passthruErrorPayload(
                        reference, "Artisan file list is too large to send to Obico");
            }
        } catch (RuntimeException invalidResponse) {
            synchronized (passthruLock) {
                passthruInFlight.remove(reference);
            }
            log("Obico passthrough response could not be encoded");
            return;
        }

        synchronized (passthruLock) {
            passthruInFlight.remove(reference);
            passthruResponses.put(reference, payload);
        }
        WebSocket socket = webSocket;
        if (socket != null && isCurrent(runGeneration)) socket.send(payload);
    }

    private void handleRemoteCommand(long runGeneration, ObicoRemoteCommand command) {
        long now = System.currentTimeMillis();
        String commandName = command.getWireName();
        if (!isCurrent(runGeneration) || !settings.isRemoteControlEnabled()) {
            auditCommand(commandName, "disabled", now);
            log("Obico remote command blocked: remote control is disabled");
            return;
        }

        ObicoCommandGate.Decision decision = commandGate.evaluate(command, now);
        if (decision == ObicoCommandGate.Decision.DUPLICATE) {
            auditCommand(commandName, "duplicate", now);
            return;
        }
        if (decision == ObicoCommandGate.Decision.RATE_LIMITED) {
            auditCommand(commandName, "rate-limited", now);
            log("Obico remote command rate-limited: " + commandName);
            return;
        }

        boolean cancelCommand = command.getType() == ObicoRemoteCommand.Type.CANCEL;
        if (cancelCommand) {
            notifyPrintCancelled();
        }
        boolean accepted;
        try {
            accepted = remoteCommandHandler.handle(command.getType());
        } catch (RuntimeException exception) {
            accepted = false;
            log("Obico remote command failed locally: " + commandName);
        }
        if (cancelCommand && !accepted) {
            clearPendingPrintCancellation();
        }
        auditCommand(commandName, accepted ? "queued" : "rejected", now);
        log("Obico remote command " + (accepted ? "accepted: " : "rejected: ") + commandName);
    }

    private void auditCommand(String command, String result, long timestampMillis) {
        updateStatus(current -> current.withRemoteCommand(command, result, timestampMillis));
    }

    void queueSnapshotUpload(long runGeneration, boolean viewingBoost) {
        if (jpegProvider == null || !settings.isCameraUploadsEnabled()) {
            return;
        }
        SnapshotAttempt attempt = new SnapshotAttempt(viewingBoost);
        synchronized (this) {
            if (!isCurrent(runGeneration)) {
                return;
            }
            long now = System.currentTimeMillis();
            if (now < snapshotRateLimitedUntilMillis
                    || (viewingBoost && (liveStreamIsStreaming()
                    || now - lastSnapshotAttemptAtMillis
                            < viewerSnapshotIntervalMillis(
                                    previousPrintState == ObicoPrintState.PRINTING,
                                    remoteShouldWatch)))) {
                return;
            }
            SnapshotAttempt active = snapshotUploadInFlight.get();
            if (active != null) {
                if (viewingBoost || !active.viewingBoost) {
                    return;
                }
                // A due AI image is more valuable than a stuck viewer-only image.
                // Cancel the old work before reserving the sole capture/upload slot.
                cancelSnapshotUpload();
            }
            if (!snapshotUploadInFlight.compareAndSet(null, attempt)) {
                return;
            }
            lastSnapshotAttemptAtMillis = now;
            if (!viewingBoost) {
                lastAiSnapshotAttemptAtMillis = lastSnapshotAttemptAtMillis;
                lastAiSnapshotAccepted = false;
            }
            try {
                snapshotCaptureTask = snapshotExecutor.submit(
                        () -> uploadSnapshot(attempt, runGeneration, viewingBoost));
            } catch (RuntimeException exception) {
                snapshotUploadInFlight.compareAndSet(attempt, null);
                if (isCurrent(runGeneration)) {
                    log("Obico camera snapshot worker is unavailable");
                }
            }
        }
    }

    private void uploadSnapshot(
            SnapshotAttempt attempt, long runGeneration, boolean viewingBoost) {
        if (!isActiveSnapshotAttempt(attempt, runGeneration)) {
            snapshotUploadInFlight.compareAndSet(attempt, null);
            return;
        }
        final byte[] jpeg;
        try {
            jpeg = jpegProvider.getJpeg();
        } catch (Exception exception) {
            snapshotUploadInFlight.compareAndSet(attempt, null);
            log("Obico camera snapshot failed locally");
            return;
        }
        if (jpeg == null || jpeg.length == 0 || jpeg.length > MAX_JPEG_BYTES) {
            snapshotUploadInFlight.compareAndSet(attempt, null);
            if (jpeg != null && jpeg.length > MAX_JPEG_BYTES) {
                log("Obico camera snapshot rejected: image exceeds 4 MiB");
            }
            return;
        }
        if (!isActiveSnapshotAttempt(attempt, runGeneration)) {
            snapshotUploadInFlight.compareAndSet(attempt, null);
            return;
        }

        ObicoSettings current = settings;
        MultipartBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("is_primary_camera", "true")
                .addFormDataPart("is_nozzle_camera", "false")
                .addFormDataPart("camera_name", "FabScreen Camera")
                .addFormDataPart("viewing_boost", Boolean.toString(viewingBoost))
                .addFormDataPart("pic", "snapshot.jpg", RequestBody.create(JPEG, jpeg))
                .build();
        Request request;
        try {
            request = new Request.Builder()
                    .url(ObicoProtocol.endpointUrl(
                            current.getServerUrl(),
                            current.isAllowInsecureServer(),
                            "/api/v1/octo/pic/"))
                    .header("Authorization", "Token " + current.getAuthToken())
                    .post(requestBody)
                    .build();
        } catch (RuntimeException exception) {
            snapshotUploadInFlight.compareAndSet(attempt, null);
            log("Obico camera snapshot request could not be created");
            return;
        }

        Call call;
        try {
            call = snapshotClient.newCall(request);
        } catch (RuntimeException exception) {
            snapshotUploadInFlight.compareAndSet(attempt, null);
            log("Obico camera snapshot request could not be queued");
            return;
        }
        synchronized (this) {
            if (!isActiveSnapshotAttempt(attempt, runGeneration)) {
                snapshotUploadInFlight.compareAndSet(attempt, null);
                return;
            }
            snapshotCall = call;
        }
        try {
            call.enqueue(new Callback() {
                @Override
                public void onFailure(Call failedCall, IOException exception) {
                    if (finishSnapshotUpload(failedCall, attempt)) {
                        log("Obico camera snapshot upload failed");
                    }
                }

                @Override
                public void onResponse(Call completedCall, Response response) {
                    try (Response ignored = response) {
                        if (response.isSuccessful()
                                && isActiveSnapshotAttempt(attempt, runGeneration)) {
                            long acceptedAtMillis = System.currentTimeMillis();
                            if (!viewingBoost) {
                                synchronized (ObicoConnector.this) {
                                    if (isCurrent(runGeneration)
                                            && snapshotUploadInFlight.get() == attempt) {
                                        lastAiSnapshotAttemptAtMillis = acceptedAtMillis;
                                        lastAiSnapshotAccepted = true;
                                    }
                                }
                            }
                            updateStatus(current -> current.withSnapshot(acceptedAtMillis));
                        } else if (isActiveSnapshotAttempt(attempt, runGeneration)) {
                            if (response.code() == 429) {
                                synchronized (ObicoConnector.this) {
                                    if (isActiveSnapshotAttempt(attempt, runGeneration)) {
                                        snapshotRateLimitedUntilMillis = System.currentTimeMillis()
                                                + SNAPSHOT_RATE_LIMIT_COOLDOWN_MILLIS;
                                        viewerSnapshotBackoff = true;
                                    }
                                }
                                log("Obico snapshot rate limit (HTTP 429); pausing uploads "
                                        + "for 65 seconds, then prioritizing AI images");
                            } else {
                                log("Obico camera snapshot rejected: HTTP " + response.code());
                            }
                        }
                    } finally {
                        finishSnapshotUpload(completedCall, attempt);
                    }
                }
            });
        } catch (RuntimeException enqueueFailure) {
            finishSnapshotUpload(call, attempt);
            log("Obico camera snapshot upload could not be queued");
        }
    }

    private synchronized void cancelSnapshotUpload() {
        Future<?> capture = snapshotCaptureTask;
        snapshotCaptureTask = null;
        if (capture != null) {
            capture.cancel(true);
        }
        Call call = snapshotCall;
        snapshotCall = null;
        if (call != null) {
            call.cancel();
        }
        snapshotUploadInFlight.set(null);
    }

    private synchronized boolean finishSnapshotUpload(
            Call completedCall, SnapshotAttempt attempt) {
        if (snapshotCall == completedCall) {
            snapshotCall = null;
            return snapshotUploadInFlight.compareAndSet(attempt, null);
        }
        return false;
    }

    private boolean isActiveSnapshotAttempt(SnapshotAttempt attempt, long runGeneration) {
        return isCurrent(runGeneration) && snapshotUploadInFlight.get() == attempt;
    }

    private void exchangeVerificationCode(
            String canonicalServerUrl,
            boolean allowInsecureServer,
            String verificationCode,
            ResultCallback<ObicoLinkResult> callback) {
        HttpUrl base = HttpUrl.parse(ObicoProtocol.endpointUrl(
                canonicalServerUrl,
                allowInsecureServer,
                "/api/v1/octo/verify/"));
        if (base == null) {
            deliver(callback, ObicoLinkResult.failure("Invalid Obico verification endpoint"));
            return;
        }
        HttpUrl endpoint = base.newBuilder()
                .addQueryParameter("code", verificationCode)
                .build();
        Request request = new Request.Builder()
                .url(endpoint)
                .header("Accept", "application/json")
                .post(RequestBody.create(EMPTY_MEDIA_TYPE, new byte[0]))
                .build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call failedCall, IOException exception) {
                deliver(callback, ObicoLinkResult.failure(networkFailureMessage("Link failed", exception)));
            }

            @Override
            public void onResponse(Call completedCall, Response response) {
                try (Response ignored = response) {
                    if (!response.isSuccessful()) {
                        deliver(callback, ObicoLinkResult.failure(
                                "Link failed: HTTP " + response.code()));
                        return;
                    }
                    String body = readBounded(response.body(), MAX_HTTP_RESPONSE_BYTES);
                    String authToken = parseAuthToken(body);
                    if (authToken.isEmpty()) {
                        deliver(callback, ObicoLinkResult.failure(
                                "Link failed: server returned no printer token"));
                    } else {
                        deliver(callback, ObicoLinkResult.success(authToken));
                    }
                } catch (IOException | RuntimeException exception) {
                    deliver(callback, ObicoLinkResult.failure(
                            networkFailureMessage("Link failed", exception)));
                }
            }
        });
    }

    private ObicoPrinterSnapshot safeSnapshot() {
        try {
            ObicoPrinterSnapshot snapshot = snapshotProvider.getSnapshot();
            return snapshot == null ? ObicoPrinterSnapshot.offline() : snapshot;
        } catch (Exception exception) {
            log("Obico telemetry snapshot failed locally");
            return ObicoPrinterSnapshot.offline();
        }
    }

    private boolean isCurrent(long runGeneration) {
        return !stopped
                && generation.get() == runGeneration
                && settings.isEnabled()
                && settings.isLinked();
    }

    private void publishConnection(
            ObicoConnectionStatus.State state,
            String message,
            int attempt,
            boolean markConnected) {
        updateStatus(current -> current.withConnection(state, message, attempt, markConnected));
    }

    private synchronized void updateStatus(UnaryOperator<ObicoConnectionStatus> update) {
        ObicoConnectionStatus next = update.apply(status.get());
        status.set(next);
        try {
            statusListener.onStatus(next);
        } catch (RuntimeException ignored) {
            // A dashboard listener must not terminate the networking worker.
        }
    }

    private void log(String message) {
        try {
            logSink.log(message);
        } catch (RuntimeException ignored) {
            // Logging is non-critical and messages never contain tokens or link codes.
        }
    }

    private void ensureRunning() {
        if (stopped) {
            throw new IllegalStateException("Obico connector is stopped");
        }
    }

    private static void validateAuthToken(String authToken) {
        if (authToken == null || authToken.isEmpty()) {
            return;
        }
        if (authToken.length() > 4096
                || !authToken.matches("[A-Za-z0-9._~+/=-]+")) {
            throw new IllegalArgumentException("Invalid Obico printer token");
        }
    }

    private static String readBounded(ResponseBody body, int maximumBytes) throws IOException {
        if (body == null) {
            return "";
        }
        long contentLength = body.contentLength();
        if (contentLength > maximumBytes) {
            throw new IOException("Response exceeds size limit");
        }
        try (InputStream input = body.byteStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream(
                     contentLength > 0L ? (int) contentLength : Math.min(4096, maximumBytes))) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maximumBytes) {
                    throw new IOException("Response exceeds size limit");
                }
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static JsonObject parseJsonObject(String body) {
        try {
            JsonElement element = new JsonParser().parse(body);
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static String parseAuthToken(String body) {
        JsonObject root = parseJsonObject(body);
        if (root == null || !root.has("printer") || !root.get("printer").isJsonObject()) {
            return "";
        }
        String token = boundedJsonString(root.getAsJsonObject("printer"), "auth_token", 4096);
        try {
            validateAuthToken(token);
            return token;
        } catch (IllegalArgumentException exception) {
            return "";
        }
    }

    private static String parsePrinterName(String body) {
        JsonObject root = parseJsonObject(body);
        if (root == null || !root.has("printer") || !root.get("printer").isJsonObject()) {
            return "";
        }
        return boundedJsonString(root.getAsJsonObject("printer"), "name", 128);
    }

    private static String boundedJsonString(JsonObject object, String property, int maximum) {
        if (object == null || !object.has(property)) {
            return "";
        }
        JsonElement value = object.get(property);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return "";
        }
        try {
            String clean = value.getAsString().replace('\u0000', ' ').trim();
            return clean.length() <= maximum ? clean : clean.substring(0, maximum);
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private static String networkFailureMessage(String prefix, Throwable throwable) {
        String type = throwable == null ? "network error" : throwable.getClass().getSimpleName();
        if (type == null || type.isEmpty()) {
            type = "network error";
        }
        // Throwable messages may contain credential-bearing URLs, so do not return them.
        return prefix + ": " + type;
    }

    private static <T> void deliver(ResultCallback<T> callback, T value) {
        if (callback != null) {
            try {
                callback.onResult(value);
            } catch (RuntimeException ignored) {
                // Network lifecycle is independent of UI callback lifecycle.
            }
        }
    }

    private static final class SnapshotAttempt {
        // Identity token: an old capture/callback must not clear a newer upload after restart.
        final boolean viewingBoost;

        SnapshotAttempt(boolean viewingBoost) {
            this.viewingBoost = viewingBoost;
        }
    }

    private static final class ObicoThreadFactory implements ThreadFactory {
        private int nextId;

        @Override
        public synchronized Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "fabscreen-obico-" + (++nextId));
            thread.setDaemon(true);
            return thread;
        }
    }
}
