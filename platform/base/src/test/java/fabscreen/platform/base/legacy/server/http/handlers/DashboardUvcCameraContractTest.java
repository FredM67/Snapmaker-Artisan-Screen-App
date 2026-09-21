package fabscreen.platform.base.legacy.server.http.handlers;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DashboardUvcCameraContractTest {
    @Test
    public void cameraEndpointsAreAuthenticatedAndFramesAreBoundedLongPolls() throws Exception {
        String handler = handlerSource();
        String endpoints = between(
                handler,
                "void getDashboardCameraStatus(HttpRequest request, HttpResponse response)",
                "private void collectLocalGcodeFiles("
        );

        assertTrue(count(endpoints, "isDashboardRequestAuthorized(request)") >= 5);
        assertTrue(endpoints.contains("DashboardCameraSettingsInput.parse("));
        assertTrue(endpoints.contains("manager.applySettings("));
        assertTrue(endpoints.contains("manager.rescan()"));
        assertTrue(endpoints.contains("CAMERA_FRAME_WAIT_MS"));
        assertTrue(endpoints.contains("StatusCode.SC_NO_CONTENT"));
        assertTrue(endpoints.contains("MediaType.IMAGE_JPEG"));
        assertTrue(endpoints.contains("manager.releaseClient(clientId)"));
    }

    @Test
    public void browserUsesAuthenticatedBlobFramesAndReleasesCapture() throws Exception {
        String dashboard = dashboardSource();

        assertTrue(dashboard.contains("data-route=\"camera\""));
        assertTrue(dashboard.contains("id=\"page-camera\""));
        assertTrue(dashboard.contains("/api/dashboard/camera/frame?after="));
        assertTrue(dashboard.contains("\"X-Artisan-Dashboard\": TOKEN"));
        assertTrue(dashboard.contains("response.blob()"));
        assertTrue(dashboard.contains("api.revokeObjectURL"));
        assertTrue(dashboard.contains("/api/dashboard/camera/stop"));
        assertTrue(dashboard.contains("stopCameraFeed(true)"));
        assertTrue(dashboard.contains("new window.AbortController()"));
        assertTrue(dashboard.contains("keepalive: true"));
        assertTrue(dashboard.contains("cameraClientId = createCameraClientId()"));
        assertTrue(dashboard.contains("hasOwnProperty.call(configuration, \"sourceId\")"));
        // The bundled AndServer parses form values only with this exact media type.
        assertTrue(dashboard.contains("\"Content-Type\": \"application/x-www-form-urlencoded\""));
        assertFalse(dashboard.contains("application/x-www-form-urlencoded;charset"));
        assertFalse(dashboard.contains("camera.mjpg"));
    }

    @Test
    public void dashboardCameraPreviewIsOptInAndSharesTheCameraLease() throws Exception {
        String dashboard = dashboardSource();

        assertTrue(dashboard.contains("id=\"camera-show-dashboard\""));
        assertTrue(dashboard.contains("id=\"dashboard-camera-preview\""));
        assertTrue(dashboard.contains("id=\"dashboard-camera-preview\" aria-label=\"Camera preview\""));
        assertTrue(dashboard.contains("id=\"dashboard-camera-frame\""));
        assertFalse(dashboard.contains("dashboard-camera-heading"));
        assertFalse(dashboard.contains("Open camera</a>"));
        assertTrue(dashboard.contains("height: clamp(180px, 16vw, 230px)"));
        assertTrue(dashboard.contains("showCameraOnDashboard: false"));
        assertTrue(dashboard.contains("defaults.showCameraOnDashboard = stored.showCameraOnDashboard"));
        assertTrue(dashboard.contains("function cameraFeedVisible()"));
        assertTrue(dashboard.contains("activeRoute === \"dashboard\" && settings.showCameraOnDashboard"));
        assertTrue(dashboard.contains("previousCameraVisible && !cameraFeedVisible()"));
        assertTrue(dashboard.contains("if (cameraFeedActive || !cameraFeedVisible()) return;"));
        assertTrue(dashboard.contains("var prefix = activeRoute === \"dashboard\" ? \"dashboard-camera\" : \"camera\""));
        assertTrue(dashboard.contains("el(\"dashboard-camera-frame\").removeAttribute(\"src\")"));
        assertTrue(dashboard.contains("if (cameraFeedActive) stopCameraFeed(true)"));
    }

    @Test
    public void rtspSetupExplainsCredentialsAndBothResolutionLimits() throws Exception {
        String dashboard = dashboardSource();

        assertTrue(dashboard.contains("rtsp://USERNAME:PASSWORD@IP_ADDRESS:554/stream_path"));
        assertTrue(dashboard.contains("el(\"camera-resolution-row\").hidden = source === \"mjpeg\";"));
        assertTrue(dashboard.contains("el(\"setting-camera-resolution-row\").hidden = source === \"mjpeg\";"));
        assertTrue(dashboard.contains("Artisan's H.264 decoder accepts source video up to 1920 × 1088"));
        assertTrue(dashboard.contains("Output JPEG resolution, up to 1920 × 1080"));
        assertTrue(dashboard.contains("value=\"1920x1080\""));
        assertTrue(dashboard.contains("cameraDraftResolution = source === \"rtsp\""));
        assertTrue(dashboard.contains("? \"640x360\" : null;"));
    }

    @Test
    public void statusDoesNotExposeVideoDevicePaths() throws Exception {
        String handler = handlerSource();
        String serializer = between(
                handler,
                "private JSONObject dashboardCameraToJson(UvcCameraManager manager)",
                "private JSONObject buildDashboardStatus("
        );

        assertFalse(serializer.contains("getActiveNode()"));
        assertFalse(serializer.contains("/dev/video"));
        assertTrue(serializer.contains("streamUrlConfigured"));
        assertTrue(serializer.contains("mjpegUrlConfigured"));
        assertTrue(serializer.contains("rtspUrlConfigured"));
        assertFalse(serializer.contains("settingsJson.put(\"streamUrl\""));
    }

    @Test
    public void networkCameraAddressesAreSavedSeparatelyAndReusedAfterUsbSelection() throws Exception {
        String store = cameraUrlStoreSource();
        String manager = cameraManagerSource();
        String dashboard = dashboardSource();

        assertTrue(store.contains("synchronized String load(String sourceId)"));
        assertTrue(store.contains("synchronized void save(String sourceId, String url)"));
        assertTrue(store.contains("stream_url_\" + sourceId + \"_ciphertext_v2"));
        assertTrue(store.contains("sourceId.equals(formerSource)"));
        assertTrue(manager.contains("streamUrlStore.load(MJPEG_SOURCE_ID)"));
        assertTrue(manager.contains("streamUrlStore.load(RTSP_SOURCE_ID)"));
        assertTrue(manager.contains("savedUrl = network ? streamUrlStore.load(requestedSource) : \"\""));
        assertTrue(manager.contains("if (network && !savedUrl.equals(next.streamUrl))"));
        assertTrue(manager.contains("streamUrlStore.save(next.sourceId, next.streamUrl)"));
        assertFalse(manager.contains("streamUrlStore.save(next.streamUrl)"));
        assertTrue(dashboard.contains("configuration[source + \"UrlConfigured\"] === true"));
        assertTrue(dashboard.contains("el(\"camera-url\").value = \"\";"));
        assertTrue(dashboard.contains("el(\"setting-camera-url\").value = \"\";"));
    }

    @Test
    public void cameraManagerBoundsClientsAndUsesMonotonicIntervals() throws Exception {
        String manager = cameraManagerSource();

        assertTrue(manager.contains("MAX_ACTIVE_CLIENTS = 8"));
        assertTrue(manager.contains("clientTouches.size() >= MAX_ACTIVE_CLIENTS"));
        assertTrue(manager.contains("SystemClock.elapsedRealtime()"));
        assertTrue(manager.contains("V4l2Native.stride(handle)"));
        assertTrue(manager.contains("new int[]{stride}"));
    }

    @Test
    public void detachedUsbCaptureDoesNotOverwriteNewSourceStatus() throws Exception {
        String manager = cameraManagerSource();
        String ipTeardown = between(
                manager,
                "boolean ownsCapture = mjpegReader == reader;",
                "private boolean shouldCapture(MjpegStreamReader reader)"
        );
        String usbTeardown = between(
                manager,
                "boolean ownsCapture = nativeHandle == handle;",
                "private boolean shouldCapture(long handle)"
        ).replace("\r\n", "\n");

        assertTrue(usbTeardown.contains("if (ownsCapture) nativeHandle = 0L;"));
        assertTrue(usbTeardown.contains("if (ownsCapture) {\n                    activeSource = null;"));
        assertTrue(usbTeardown.contains("message = \"Reopening USB camera\";"));
        assertTrue(usbTeardown.contains("if (wasCaptureThread) captureThread = null;"));
        assertTrue(usbTeardown.contains("} else if (wasCaptureThread && settings.enabled && !clientTouches.isEmpty()) {"));
        assertTrue(usbTeardown.contains("ensureCaptureStartedLocked();"));
        assertTrue(ipTeardown.contains("if (wasCaptureThread) captureThread = null;"));
        assertTrue(ipTeardown.contains("} else if (wasCaptureThread && settings.enabled && !clientTouches.isEmpty()) {"));
        assertTrue(ipTeardown.contains("ensureCaptureStartedLocked();"));
    }

    private static int count(String source, String needle) {
        int total = 0;
        int offset = 0;
        while ((offset = source.indexOf(needle, offset)) >= 0) {
            total += 1;
            offset += needle.length();
        }
        return total;
    }

    private static String dashboardSource() throws Exception {
        Path path = Paths.get("src/main/assets/artisan_dashboard.html");
        if (!Files.isRegularFile(path)) {
            path = Paths.get("platform/base/src/main/assets/artisan_dashboard.html");
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String handlerSource() throws Exception {
        Path path = Paths.get(
                "src/main/java/fabscreen/platform/base/legacy/server/http/handlers/"
                        + "OrcaRequestHandler.java"
        );
        if (!Files.isRegularFile(path)) {
            path = Paths.get(
                    "platform/base/src/main/java/fabscreen/platform/base/legacy/server/http/"
                            + "handlers/OrcaRequestHandler.java"
            );
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String cameraManagerSource() throws Exception {
        Path path = Paths.get("src/main/java/fabscreen/platform/base/camera/UvcCameraManager.java");
        if (!Files.isRegularFile(path)) {
            path = Paths.get(
                    "platform/base/src/main/java/fabscreen/platform/base/camera/UvcCameraManager.java"
            );
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String cameraUrlStoreSource() throws Exception {
        Path path = Paths.get("src/main/java/fabscreen/platform/base/camera/CameraStreamUrlStore.java");
        if (!Files.isRegularFile(path)) {
            path = Paths.get("platform/base/src/main/java/fabscreen/platform/base/camera/CameraStreamUrlStore.java");
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String between(String source, String start, String end) {
        int startIndex = source.indexOf(start);
        int endIndex = source.indexOf(end, startIndex + start.length());
        if (startIndex < 0 || endIndex < 0) {
            throw new AssertionError("Unable to locate source contract boundaries.");
        }
        return source.substring(startIndex, endIndex);
    }
}
