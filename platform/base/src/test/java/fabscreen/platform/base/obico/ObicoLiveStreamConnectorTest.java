package fabscreen.platform.base.obico;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Contract tests for the optional live stream without starting an Android/native camera peer. */
public class ObicoLiveStreamConnectorTest {
    @Test
    public void peerReadyBeforeCloudConnectAdvertisesLiveOnFirstSettings() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            SocketCapture capture = enqueueWebSocket(server);
            server.start();
            FakeLiveStream stream = new FakeLiveStream();
            stream.readyOnStart = true;
            ObicoConnector connector = connector(stream);
            try {
                connect(connector, server);
                JsonObject firstSettings = awaitMessage(capture.inbound, root -> root.has("settings"));
                assertEquals("mjpeg_webrtc", webcam(firstSettings)
                        .get("stream_mode").getAsString());
            } finally {
                connector.stop();
            }
        }
    }

    @Test
    public void readyStreamUpdatesSettingsAndRelaysJanusInBothDirections() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            SocketCapture capture = enqueueWebSocket(server);
            server.start();
            FakeLiveStream stream = new FakeLiveStream();
            ObicoConnector connector = connector(stream);
            try {
                connect(connector, server);
                assertTrue("Live stream was not started", stream.started.await(3L, TimeUnit.SECONDS));
                assertEquals("test-token", stream.authToken);
                assertEquals("/ws/dev/", server.takeRequest(3L, TimeUnit.SECONDS).getPath());

                JsonObject fallback = awaitMessage(capture.inbound,
                        ObicoLiveStreamConnectorTest::hasNoWebcam);
                assertEquals(0, fallback.getAsJsonObject("settings")
                        .getAsJsonArray("webcams").size());

                stream.setReady(true);
                JsonObject live = awaitMessage(capture.inbound,
                        root -> hasWebcamMode(root, "mjpeg_webrtc"));
                assertEquals(ObicoProtocol.PRIMARY_MJPEG_STREAM_ID,
                        webcam(live).get("stream_id").getAsInt());

                WebSocket serverSocket = capture.openSocket.get();
                assertNotNull(serverSocket);
                String inboundJanus = "{\"janus\":\"create\",\"transaction\":\"request-1\"}";
                JsonObject inboundEnvelope = new JsonObject();
                inboundEnvelope.addProperty("janus", inboundJanus);
                serverSocket.send(inboundEnvelope.toString());
                assertEquals(inboundJanus, stream.inboundJanus.poll(3L, TimeUnit.SECONDS));

                String outboundJanus = "{\"janus\":\"success\",\"transaction\":\"request-1\"}";
                stream.emitJanus(outboundJanus);
                JsonObject outboundEnvelope = awaitMessage(capture.inbound,
                        root -> root.has("janus"));
                assertEquals(outboundJanus, outboundEnvelope.get("janus").getAsString());

                // Malformed or non-string Janus fields must not reach the native peer.
                serverSocket.send("not-json");
                serverSocket.send("{\"janus\":{\"janus\":\"create\"}}");
                String finalJanus = "{\"janus\":\"keepalive\",\"transaction\":\"request-2\"}";
                JsonObject finalEnvelope = new JsonObject();
                finalEnvelope.addProperty("janus", finalJanus);
                serverSocket.send(finalEnvelope.toString());
                assertEquals(finalJanus, stream.inboundJanus.poll(3L, TimeUnit.SECONDS));
                assertTrue("Malformed messages were forwarded to the native peer",
                        stream.inboundJanus.isEmpty());

                stream.setReady(false);
                JsonObject fallbackAgain = awaitMessage(capture.inbound,
                        ObicoLiveStreamConnectorTest::hasNoWebcam);
                assertEquals(0, fallbackAgain.getAsJsonObject("settings")
                        .getAsJsonArray("webcams").size());
            } finally {
                connector.stop();
            }
        }
    }

    @Test
    public void latePeerReadinessDoesNotPinAnOpenPageToJpegPlayer() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            SocketCapture capture = enqueueWebSocket(server);
            server.start();
            FakeLiveStream stream = new FakeLiveStream();
            ObicoConnector connector = connector(stream);
            try {
                connect(connector, server);
                awaitMessage(capture.inbound, ObicoLiveStreamConnectorTest::hasNoWebcam);
                // The old startup timer advertised a JPEG-only webcam after 1.5 seconds.
                Thread.sleep(1_750L);
                String message;
                while ((message = capture.inbound.poll()) != null) {
                    JsonObject root = new JsonParser().parse(message).getAsJsonObject();
                    assertFalse("Startup must not advertise an un-upgradeable JPEG player",
                            hasWebcamMode(root, "jpeg"));
                }
                stream.setReady(true);
                awaitMessage(capture.inbound,
                        root -> hasWebcamMode(root, "mjpeg_webrtc"));
            } finally {
                connector.stop();
            }
        }
    }

    @Test
    public void disconnectAndDisableStopTheLivePeer() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            SocketCapture capture = enqueueWebSocket(server);
            server.start();
            FakeLiveStream stream = new FakeLiveStream();
            ObicoConnector connector = connector(stream);
            try {
                connect(connector, server);
                assertTrue(stream.started.await(3L, TimeUnit.SECONDS));
                int beforeDisconnect = stream.stops.get();
                assertTrue(waitFor(() -> capture.openSocket.get() != null));
                capture.openSocket.get().close(1000, "Viewer disconnected");
                assertTrue("Disconnected Obico socket left the peer running",
                        waitFor(() -> stream.stops.get() > beforeDisconnect));

                int beforeDisable = stream.stops.get();
                connector.applySettings(new ObicoSettings(
                        false, server.url("/").toString(), "test-token", false, true, true));
                assertTrue("Disabling Obico did not stop the peer",
                        stream.stops.get() > beforeDisable);
            } finally {
                connector.stop();
            }
        }
    }

    private static void connect(ObicoConnector connector, MockWebServer server) {
        connector.applySettings(new ObicoSettings(
                true, server.url("/").toString(), "test-token", false, true, true));
    }

    private static ObicoConnector connector(FakeLiveStream stream) {
        return new ObicoConnector(
                ObicoPrinterSnapshot::offline,
                () -> null,
                command -> false,
                (request, callback) -> { },
                status -> { },
                message -> { },
                new OkHttpClient(),
                Executors.newScheduledThreadPool(2),
                stream);
    }

    private static SocketCapture enqueueWebSocket(MockWebServer server) {
        SocketCapture capture = new SocketCapture();
        server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                capture.openSocket.set(webSocket);
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                capture.inbound.add(text);
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                webSocket.close(code, reason);
            }
        }));
        return capture;
    }

    private static JsonObject awaitMessage(
            BlockingQueue<String> messages,
            Predicate<JsonObject> matches) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
        while (System.nanoTime() < deadline) {
            String text = messages.poll(100L, TimeUnit.MILLISECONDS);
            if (text == null) continue;
            JsonObject root = new JsonParser().parse(text).getAsJsonObject();
            if (matches.test(root)) return root;
        }
        throw new AssertionError("Matching Obico WebSocket message did not arrive");
    }

    private static JsonObject webcam(JsonObject root) {
        return root.getAsJsonObject("settings").getAsJsonArray("webcams")
                .get(0).getAsJsonObject();
    }

    private static boolean hasWebcamMode(JsonObject root, String mode) {
        return root.has("settings")
                && root.getAsJsonObject("settings").getAsJsonArray("webcams").size() == 1
                && mode.equals(webcam(root).get("stream_mode").getAsString());
    }

    private static boolean hasNoWebcam(JsonObject root) {
        return root.has("settings")
                && root.getAsJsonObject("settings").getAsJsonArray("webcams").size() == 0;
    }

    private static boolean waitFor(Check condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
        while (System.nanoTime() < deadline) {
            if (condition.evaluate()) return true;
            Thread.sleep(20L);
        }
        return condition.evaluate();
    }

    private interface Check {
        boolean evaluate();
    }

    private static final class SocketCapture {
        final AtomicReference<WebSocket> openSocket = new AtomicReference<>();
        final BlockingQueue<String> inbound = new LinkedBlockingQueue<>();
    }

    private static final class FakeLiveStream implements ObicoLiveStream {
        final CountDownLatch started = new CountDownLatch(1);
        final AtomicInteger stops = new AtomicInteger();
        final BlockingQueue<String> inboundJanus = new LinkedBlockingQueue<>();
        volatile Listener listener;
        volatile boolean ready;
        volatile boolean readyOnStart;
        volatile String authToken;

        @Override
        public void start(String token, Listener callback) {
            authToken = token;
            listener = callback;
            ready = readyOnStart;
            started.countDown();
        }

        @Override
        public void stop() {
            ready = false;
            listener = null;
            stops.incrementAndGet();
        }

        @Override
        public void sendJanus(String payload) {
            inboundJanus.add(payload);
        }

        @Override
        public boolean isReady() {
            return ready;
        }

        void setReady(boolean next) {
            ready = next;
            Listener callback = listener;
            if (callback != null) callback.onStateChanged();
        }

        void emitJanus(String payload) {
            Listener callback = listener;
            if (callback != null) callback.onJanusMessage(payload);
        }
    }
}
