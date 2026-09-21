package fabscreen.platform.base.legacy.server.http.handlers;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DashboardObicoContractTest {
    @Test
    public void everyObicoEndpointRequiresDashboardAuthorization() throws Exception {
        String handler = handlerSource();
        String endpoints = between(
                handler,
                "void getDashboardObicoConfig(HttpRequest request, HttpResponse response)",
                "private void collectLocalGcodeFiles("
        );

        assertTrue(count(endpoints, "isDashboardRequestAuthorized(request)") >= 5);
        assertTrue(endpoints.contains("DashboardObicoSettingsInput.parse("));
        assertTrue(endpoints.contains("DashboardObicoSettingsInput.parseLinkCode("));
        assertTrue(endpoints.contains("StatusCode.SC_SERVICE_UNAVAILABLE"));
        assertTrue(handler.contains("sanitizeObicoObject(result)"));
        assertTrue(handler.contains("normalized.contains(\"token\")"));
    }

    @Test
    public void settingsUiSupportsSecureLinkingAndExplicitCapabilities() throws Exception {
        String dashboard = dashboardSource();

        assertTrue(dashboard.contains("id=\"setting-obico-server\" value=\"https://app.obico.io\""));
        assertFalse(dashboard.contains("id=\"setting-obico-insecure\""));
        assertTrue(dashboard.contains("id=\"setting-obico-remote-control\""));
        assertTrue(dashboard.contains("id=\"setting-obico-camera\""));
        assertTrue(dashboard.contains("id=\"setting-obico-code\""));
        assertTrue(dashboard.contains("Link Obico to Artisan Web"));
        assertTrue(dashboard.contains("Link New Printer"));
        assertTrue(dashboard.contains("Switch to Manual Setup"));
        assertTrue(dashboard.contains("six-digit verification code"));
        assertTrue(dashboard.contains("Verify code"));
        assertFalse(dashboard.contains("id=\"setting-obico-generate\""));
        assertFalse(dashboard.contains("operation.oneTimePasscode"));
        assertFalse(dashboard.contains("function obicoWebLink("));
        assertFalse(dashboard.contains("one_time_passcode"));
        assertTrue(dashboard.contains("/api/dashboard/obico/config"));
        assertTrue(dashboard.contains("/api/dashboard/obico/link"));
        assertTrue(dashboard.contains("/api/dashboard/obico/test"));
        assertTrue(dashboard.contains("/api/dashboard/obico/disconnect"));
        assertTrue(dashboard.contains("id=\"setting-obico-last-image\""));
        assertTrue(dashboard.contains("id=\"setting-obico-watch-request\""));
        assertTrue(dashboard.contains("Obico requests monitoring: "));
        assertTrue(dashboard.contains("does not confirm that Obico failure detection is enabled"));
        assertFalse(dashboard.contains("authToken"));
    }

    @Test
    public void publicStateReportsOnlyWatchRequestAndAcceptedSnapshotTiming() throws Exception {
        String service = serviceSource();
        assertTrue(service.contains("status.put(\"remoteShouldWatch\", currentStatus.isRemoteShouldWatch())"));
        assertTrue(service.contains("status.put(\"remoteViewing\", currentStatus.isRemoteViewing())"));
        assertTrue(service.contains("status.put(\"lastRemoteWatchAtMillis\", currentStatus.getLastRemoteWatchAtMillis())"));
        assertTrue(service.contains("status.put(\"lastSnapshotAgeMillis\""));
        assertFalse(service.contains("status.put(\"aiEnabled\""));
        assertFalse(service.contains("connector.startOneTimeLink("));
        assertFalse(service.contains("operation.put(\"oneTimePasscode\""));
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

    private static String serviceSource() throws Exception {
        Path path = Paths.get("src/main/java/fabscreen/platform/base/service/ObicoService.java");
        if (!Files.isRegularFile(path)) {
            path = Paths.get("platform/base/src/main/java/fabscreen/platform/base/service/ObicoService.java");
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
