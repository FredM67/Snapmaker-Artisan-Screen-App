package fabscreen.platform.base.obico;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.WebSocket;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ObicoSnapshotPipelineTest {
    @Test
    public void rateLimitPausesAllUploadsThenResumesAiBeforeViewer() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(429));
            server.enqueue(new MockResponse().setResponseCode(200));
            server.start();
            ObicoConnector connector = connectorForMockServer(server);
            try {
                setField(connector, "previousPrintState", ObicoPrintState.PRINTING);
                setField(connector, "remoteViewing", true);
                setField(connector, "remoteShouldWatch", true);
                assertViewingBoost(awaitRequest(server, connector, 1), false);

                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
                while ((Long) getField(connector, "snapshotRateLimitedUntilMillis")
                        <= System.currentTimeMillis() && System.nanoTime() < deadline) {
                    Thread.sleep(10L);
                }
                assertTrue("HTTP 429 did not activate a full-minute cooldown",
                        (Long) getField(connector, "snapshotRateLimitedUntilMillis")
                                > System.currentTimeMillis() + 60_000L);
                assertTrue((Boolean) getField(connector, "viewerSnapshotBackoff"));
                setField(connector, "lastAiSnapshotAttemptAtMillis",
                        System.currentTimeMillis() - 12_001L);
                for (int index = 0; index < 10; index++) connector.snapshotTick(0L);
                assertEquals("Rate-limited retries must not consume more server budget",
                        1, server.getRequestCount());

                setField(connector, "snapshotRateLimitedUntilMillis",
                        System.currentTimeMillis() - 1L);
                assertViewingBoost(awaitRequest(server, connector, 2), false);
            } finally {
                connector.stop();
            }
        }
    }

    @Test
    public void viewerFallbackSlowsAfterRateLimit() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(429));
            server.enqueue(new MockResponse().setResponseCode(200));
            server.start();
            ObicoConnector connector = connectorForMockServer(server);
            try {
                setField(connector, "remoteViewing", true);
                assertViewingBoost(awaitRequest(server, connector, 1), true);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
                while (!(Boolean) getField(connector, "viewerSnapshotBackoff")
                        && System.nanoTime() < deadline) {
                    Thread.sleep(10L);
                }
                assertTrue((Boolean) getField(connector, "viewerSnapshotBackoff"));
                setField(connector, "snapshotRateLimitedUntilMillis",
                        System.currentTimeMillis() - 1L);
                setField(connector, "lastSnapshotAttemptAtMillis",
                        System.currentTimeMillis() - 20_001L);
                assertViewingBoost(awaitRequest(server, connector, 2), true);
                for (int index = 0; index < 10; index++) connector.snapshotTick(0L);
                assertEquals("Viewer-only POSTs must respect the reduced cadence",
                        2, server.getRequestCount());
            } finally {
                connector.stop();
            }
        }
    }

    @Test
    public void streamingDataChannelSuppressesBoostedSnapshotsButNotAiImages()
            throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200));
            server.start();
            ObicoLiveStream stream = new ObicoLiveStream() {
                @Override public void start(String token, Listener listener) { }
                @Override public void stop() { }
                @Override public void sendJanus(String payload) { }
                @Override public boolean isReady() { return true; }
                @Override public boolean isStreaming() { return true; }
            };
            ObicoConnector connector = connectorForMockServer(server, stream);
            try {
                setField(connector, "remoteViewing", true);
                for (int index = 0; index < 10; index++) connector.snapshotTick(0L);
                assertEquals("WebRTC viewer must not trigger redundant boosted POSTs",
                        0, server.getRequestCount());
                setField(connector, "previousPrintState", ObicoPrintState.PRINTING);
                setField(connector, "remoteShouldWatch", true);
                assertViewingBoost(awaitRequest(server, connector, 1), false);
            } finally {
                connector.stop();
            }
        }
    }

    @Test
    public void activeViewerStillSendsAiEligibleSnapshotsWhenMonitoringRequested()
            throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            for (int index = 0; index < 4; index++) {
                server.enqueue(new MockResponse().setResponseCode(200));
            }
            server.start();
            ObicoConnector connector = connectorForMockServer(server);
            try {
                setField(connector, "previousPrintState", ObicoPrintState.PRINTING);
                setField(connector, "remoteViewing", true);
                setField(connector, "remoteShouldWatch", true);

                assertViewingBoost(awaitRequest(server, connector, 1), false);
                setField(connector, "lastSnapshotAttemptAtMillis",
                        System.currentTimeMillis() - 10_001L);
                assertViewingBoost(awaitRequest(server, connector, 2), true);

                setField(connector, "lastAiSnapshotAttemptAtMillis",
                        System.currentTimeMillis() - 12_001L);
                assertViewingBoost(awaitRequest(server, connector, 3), false);
                setField(connector, "lastSnapshotAttemptAtMillis",
                        System.currentTimeMillis() - 10_001L);
                assertViewingBoost(awaitRequest(server, connector, 4), true);
            } finally {
                connector.stop();
            }
        }
    }

    @Test
    public void activeViewerWithoutWatchRequestKeepsSparseUnboostedFallback()
            throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            for (int index = 0; index < 3; index++) {
                server.enqueue(new MockResponse().setResponseCode(200));
            }
            server.start();
            ObicoConnector connector = connectorForMockServer(server);
            try {
                setField(connector, "previousPrintState", ObicoPrintState.PRINTING);
                setField(connector, "remoteViewing", true);
                setField(connector, "remoteShouldWatch", false);
                setField(connector, "lastAiSnapshotAttemptAtMillis", System.currentTimeMillis());

                assertViewingBoost(awaitRequest(server, connector, 1), true);
                setField(connector, "lastAiSnapshotAttemptAtMillis",
                        System.currentTimeMillis() - 10_001L);
                setField(connector, "lastSnapshotAttemptAtMillis",
                        System.currentTimeMillis() - 10_001L);
                assertViewingBoost(awaitRequest(server, connector, 2), true);
                setField(connector, "lastAiSnapshotAttemptAtMillis",
                        System.currentTimeMillis() - 60_001L);
                assertViewingBoost(awaitRequest(server, connector, 3), false);
            } finally {
                connector.stop();
            }
        }
    }

    @Test
    public void slowAiUploadDoesNotQueueViewerFramesBehindIt() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200)
                    .setHeadersDelay(2L, TimeUnit.SECONDS));
            server.enqueue(new MockResponse().setResponseCode(200));
            server.start();
            ObicoConnector connector = connectorForMockServer(server);
            try {
                setField(connector, "previousPrintState", ObicoPrintState.PRINTING);
                setField(connector, "remoteViewing", true);
                setField(connector, "remoteShouldWatch", true);

                assertViewingBoost(awaitRequest(server, connector, 1), false);
                setField(connector, "lastSnapshotAttemptAtMillis",
                        System.currentTimeMillis() - 10_001L);
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1_250L);
                while (System.nanoTime() < deadline) {
                    connector.snapshotTick(0L);
                    Thread.sleep(25L);
                }
                assertEquals("Blocked upload must not queue viewer images", 1,
                        server.getRequestCount());
                assertViewingBoost(awaitRequest(server, connector, 2), true);
            } finally {
                connector.stop();
            }
        }
    }

    @Test
    public void dueAiImagePreemptsSlowViewerOnlyUpload() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200)
                    .setHeadersDelay(5L, TimeUnit.SECONDS));
            server.enqueue(new MockResponse().setResponseCode(200));
            server.start();
            ObicoConnector connector = connectorForMockServer(server);
            try {
                setField(connector, "previousPrintState", ObicoPrintState.PRINTING);
                setField(connector, "remoteViewing", true);
                setField(connector, "remoteShouldWatch", true);
                setField(connector, "lastAiSnapshotAttemptAtMillis",
                        System.currentTimeMillis());

                assertViewingBoost(awaitRequest(server, connector, 1), true);
                setField(connector, "lastAiSnapshotAttemptAtMillis",
                        System.currentTimeMillis() - 12_001L);

                long startedAt = System.nanoTime();
                assertViewingBoost(awaitRequest(server, connector, 2), false);
                assertTrue("Due AI image waited for the stalled viewer request",
                        System.nanoTime() - startedAt < TimeUnit.SECONDS.toNanos(3L));
            } finally {
                connector.stop();
            }
        }
    }

    @Test
    public void stalledViewerPostTimesOutAndAllowsNextImage() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200)
                    .setHeadersDelay(5L, TimeUnit.SECONDS));
            server.enqueue(new MockResponse().setResponseCode(200));
            server.start();
            ObicoConnector connector = connectorForMockServer(server);
            try {
                setField(connector, "remoteViewing", true);
                assertViewingBoost(awaitRequest(server, connector, 1), true);

                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(7L);
                while (server.getRequestCount() < 2 && System.nanoTime() < deadline) {
                    connector.snapshotTick(0L);
                    Thread.sleep(40L);
                }
                assertEquals("A stalled POST held the slot past the bounded timeout",
                        2, server.getRequestCount());
                assertViewingBoost(server.takeRequest(1L, TimeUnit.SECONDS), true);
            } finally {
                connector.stop();
            }
        }
    }

    @Test
    public void successfulDelayedAiResponseResetsDueClockAtAcceptance() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200)
                    .setHeadersDelay(1L, TimeUnit.SECONDS));
            server.enqueue(new MockResponse().setResponseCode(200));
            server.start();
            ObicoConnector connector = connectorForMockServer(server);
            try {
                setField(connector, "previousPrintState", ObicoPrintState.PRINTING);
                setField(connector, "remoteShouldWatch", true);
                assertViewingBoost(awaitRequest(server, connector, 1), false);

                long requestStartedAt = (Long) getField(connector,
                        "lastAiSnapshotAttemptAtMillis");
                // Simulate the original ten-second due time passing while HTTP is in flight.
                setField(connector, "lastAiSnapshotAttemptAtMillis",
                        requestStartedAt - 10_001L);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
                while ((connector.getStatus().getLastSnapshotAtMillis() == 0L
                        || ((AtomicReference<?>) getField(connector,
                                "snapshotUploadInFlight")).get() != null)
                        && System.nanoTime() < deadline) {
                    connector.snapshotTick(0L);
                    Thread.sleep(20L);
                }
                long acceptedAt = connector.getStatus().getLastSnapshotAtMillis();
                assertTrue("Delayed upload was not accepted", acceptedAt > requestStartedAt);
                assertTrue("AI due clock must start at accepted response, not request start",
                        (Long) getField(connector, "lastAiSnapshotAttemptAtMillis") >= acceptedAt);

                deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300L);
                while (System.nanoTime() < deadline) {
                    connector.snapshotTick(0L);
                    Thread.sleep(20L);
                }
                assertEquals("A just-accepted AI image must not immediately retrigger",
                        1, server.getRequestCount());
            } finally {
                connector.stop();
            }
        }
    }

    @Test
    public void blockedCameraCaptureDoesNotBlockTelemetryOrQueueOverlappingCaptures()
            throws Exception {
        ScheduledExecutorService telemetryExecutor = Executors.newSingleThreadScheduledExecutor();
        CountDownLatch captureEntered = new CountDownLatch(1);
        CountDownLatch releaseCapture = new CountDownLatch(1);
        CountDownLatch captureInterrupted = new CountDownLatch(1);
        AtomicInteger captures = new AtomicInteger();
        ObicoConnector connector = new ObicoConnector(
                ObicoPrinterSnapshot::offline,
                () -> {
                    captures.incrementAndGet();
                    captureEntered.countDown();
                    try {
                        releaseCapture.await(5L, TimeUnit.SECONDS);
                    } catch (InterruptedException exception) {
                        captureInterrupted.countDown();
                        Thread.currentThread().interrupt();
                    }
                    return null;
                },
                command -> false,
                (request, callback) -> { },
                status -> { },
                message -> { },
                new OkHttpClient(),
                telemetryExecutor);
        try {
            Field settingsField = ObicoConnector.class.getDeclaredField("settings");
            settingsField.setAccessible(true);
            settingsField.set(connector, new ObicoSettings(
                    true, "https://example.test", "test-token", false, true, false));

            connector.queueSnapshotUpload(0L, true);
            assertTrue("Camera capture did not begin", captureEntered.await(2L, TimeUnit.SECONDS));
            Future<Integer> telemetry = telemetryExecutor.submit(() -> 42);
            assertEquals("Telemetry executor was blocked by camera capture",
                    Integer.valueOf(42), telemetry.get(1L, TimeUnit.SECONDS));

            for (int index = 0; index < 10; index++) {
                connector.queueSnapshotUpload(0L, true);
            }
            assertEquals("Only one camera capture may run or queue", 1, captures.get());

            connector.stop();
            assertTrue("Stopping must interrupt a blocked camera capture",
                    captureInterrupted.await(2L, TimeUnit.SECONDS));
        } finally {
            releaseCapture.countDown();
            connector.stop();
        }
    }

    @Test
    public void staleCaptureCannotClearNewSessionUploadGuard() throws Exception {
        ScheduledExecutorService telemetryExecutor = Executors.newSingleThreadScheduledExecutor();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        CountDownLatch thirdEntered = new CountDownLatch(1);
        AtomicInteger captures = new AtomicInteger();
        ObicoConnector connector = new ObicoConnector(
                ObicoPrinterSnapshot::offline,
                () -> {
                    int count = captures.incrementAndGet();
                    if (count == 1) {
                        firstEntered.countDown();
                        // Simulate a camera driver that ignores a cancelled blocking read.
                        while (releaseFirst.getCount() != 0L) {
                            try {
                                releaseFirst.await(50L, TimeUnit.MILLISECONDS);
                            } catch (InterruptedException ignored) {
                                // The old capture must still not affect the new generation.
                            }
                        }
                    } else if (count == 2) {
                        secondEntered.countDown();
                        releaseSecond.await(2L, TimeUnit.SECONDS);
                    } else {
                        thirdEntered.countDown();
                    }
                    return null;
                },
                command -> false,
                (request, callback) -> { },
                status -> { },
                message -> { },
                new OkHttpClient(),
                telemetryExecutor);
        try {
            Field settingsField = ObicoConnector.class.getDeclaredField("settings");
            settingsField.setAccessible(true);
            ObicoSettings enabled = new ObicoSettings(
                    true, "https://example.test", "test-token", false, true, false);
            settingsField.set(connector, enabled);
            connector.queueSnapshotUpload(0L, true);
            assertTrue(firstEntered.await(2L, TimeUnit.SECONDS));

            connector.applySettings(ObicoSettings.defaults());
            settingsField.set(connector, enabled);
            connector.queueSnapshotUpload(1L, true);
            releaseFirst.countDown();
            assertTrue(secondEntered.await(2L, TimeUnit.SECONDS));

            Field guardField = ObicoConnector.class.getDeclaredField("snapshotUploadInFlight");
            guardField.setAccessible(true);
            AtomicReference<?> guard = (AtomicReference<?>) guardField.get(connector);
            assertNotNull("Old capture must not clear the new upload", guard.get());
            connector.queueSnapshotUpload(1L, true);
            releaseSecond.countDown();
            assertFalse("No third capture should be queued", thirdEntered.await(300L, TimeUnit.MILLISECONDS));
            assertEquals(2, captures.get());
        } finally {
            releaseFirst.countDown();
            releaseSecond.countDown();
            connector.stop();
        }
    }

    @Test
    public void remoteViewerCanReceiveThreeFastSnapshotPostsWithinThreeAndHalfSeconds()
            throws Exception {
        AtomicInteger uploads = new AtomicInteger();
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    uploads.incrementAndGet();
                    return okResponse(chain.request());
                })
                .build();
        ScheduledExecutorService telemetryExecutor = Executors.newSingleThreadScheduledExecutor();
        ObicoConnector connector = new ObicoConnector(
                ObicoPrinterSnapshot::offline,
                () -> new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9},
                command -> false,
                (request, callback) -> { },
                status -> { },
                message -> { },
                client,
                telemetryExecutor);
        try {
            enableRemoteViewingForTest(connector);
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(3_500L);
            while (uploads.get() < 3 && System.nanoTime() < deadline) {
                connector.snapshotTick(0L);
                Thread.sleep(40L);
            }
            assertTrue("Viewer cadence remained at 0.5 FPS or slower", uploads.get() >= 3);
        } finally {
            connector.stop();
        }
    }

    @Test
    public void slowSnapshotPostDoesNotQueueAnotherUpload() throws Exception {
        AtomicInteger uploads = new AtomicInteger();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    int number = uploads.incrementAndGet();
                    if (number == 1) {
                        firstEntered.countDown();
                        try {
                            releaseFirst.await(5L, TimeUnit.SECONDS);
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Test upload interrupted", exception);
                        }
                    }
                    return okResponse(chain.request());
                })
                .build();
        ScheduledExecutorService telemetryExecutor = Executors.newSingleThreadScheduledExecutor();
        ObicoConnector connector = new ObicoConnector(
                ObicoPrinterSnapshot::offline,
                () -> new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9},
                command -> false,
                (request, callback) -> { },
                status -> { },
                message -> { },
                client,
                telemetryExecutor);
        try {
            enableRemoteViewingForTest(connector);
            connector.snapshotTick(0L);
            assertTrue(firstEntered.await(2L, TimeUnit.SECONDS));
            Thread.sleep(1_100L);
            for (int index = 0; index < 10; index++) {
                connector.snapshotTick(0L);
            }
            assertEquals("A slow upload must not build a request queue", 1, uploads.get());
            releaseFirst.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
            while (uploads.get() < 2 && System.nanoTime() < deadline) {
                connector.snapshotTick(0L);
                Thread.sleep(40L);
            }
            assertEquals("Uploads should resume after the slow request finishes", 2, uploads.get());
        } finally {
            releaseFirst.countDown();
            connector.stop();
        }
    }

    private static void enableRemoteViewingForTest(ObicoConnector connector) throws Exception {
        Field settings = ObicoConnector.class.getDeclaredField("settings");
        settings.setAccessible(true);
        settings.set(connector, new ObicoSettings(
                true, "https://example.test", "test-token", false, true, false));
        Field viewing = ObicoConnector.class.getDeclaredField("remoteViewing");
        viewing.setAccessible(true);
        viewing.setBoolean(connector, true);
        Field socket = ObicoConnector.class.getDeclaredField("webSocket");
        socket.setAccessible(true);
        socket.set(connector, Proxy.newProxyInstance(
                WebSocket.class.getClassLoader(),
                new Class<?>[] {WebSocket.class},
                (proxy, method, arguments) -> method.getReturnType() == boolean.class
                        ? false : null));
    }

    private static ObicoConnector connectorForMockServer(MockWebServer server) throws Exception {
        return connectorForMockServer(server, null);
    }

    private static ObicoConnector connectorForMockServer(
            MockWebServer server, ObicoLiveStream liveStream) throws Exception {
        ObicoConnector connector = new ObicoConnector(
                ObicoPrinterSnapshot::offline,
                () -> new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9},
                command -> false,
                (request, callback) -> { },
                status -> { },
                message -> { },
                new OkHttpClient(),
                Executors.newSingleThreadScheduledExecutor(),
                liveStream);
        setField(connector, "settings", new ObicoSettings(
                true, server.url("/").toString(), "test-token", false, true, true));
        setField(connector, "webSocket", Proxy.newProxyInstance(
                WebSocket.class.getClassLoader(),
                new Class<?>[] {WebSocket.class},
                (proxy, method, arguments) -> method.getReturnType() == boolean.class
                        ? false : null));
        return connector;
    }

    private static RecordedRequest awaitRequest(
            MockWebServer server, ObicoConnector connector, int expectedCount) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
        while (server.getRequestCount() < expectedCount && System.nanoTime() < deadline) {
            connector.snapshotTick(0L);
            Thread.sleep(20L);
        }
        assertTrue("Snapshot upload " + expectedCount + " did not arrive",
                server.getRequestCount() >= expectedCount);
        RecordedRequest request = server.takeRequest(1L, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("/api/v1/octo/pic/", request.getPath());
        return request;
    }

    private static void assertViewingBoost(RecordedRequest request, boolean boosted) {
        String body = request.getBody().readUtf8();
        int field = body.indexOf("name=\"viewing_boost\"");
        assertTrue("Missing viewing_boost form field", field >= 0);
        String value = body.substring(field, Math.min(field + 160, body.length()));
        assertTrue("Unexpected viewing_boost value: " + value,
                value.contains("\r\n\r\n" + boosted + "\r\n"));
    }

    private static void setField(ObicoConnector connector, String name, Object value)
            throws Exception {
        Field field = ObicoConnector.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(connector, value);
    }

    private static Object getField(ObicoConnector connector, String name) throws Exception {
        Field field = ObicoConnector.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(connector);
    }

    private static Response okResponse(okhttp3.Request request) {
        return new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create(null, ""))
                .build();
    }
}
