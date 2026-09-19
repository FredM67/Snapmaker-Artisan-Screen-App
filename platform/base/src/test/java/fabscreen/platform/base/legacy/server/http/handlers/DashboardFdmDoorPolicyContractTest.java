package fabscreen.platform.base.legacy.server.http.handlers;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Guards the dashboard-only FDM policy without changing native or firmware safety paths. */
public class DashboardFdmDoorPolicyContractTest {
    @Test
    public void doorTelemetryRemainsInformationalInDashboardStatus() throws Exception {
        String handler = handlerSource();

        assertTrue(handler.contains("safety.put(\"enclosureDoorOpen\""));
        assertTrue(handler.contains("safety.put(\"enclosureDoorTelemetryAvailable\""));
        assertTrue(handler.contains("result.put(\"doorOpen\""));
        assertTrue(handler.contains("result.put(\"doorState\""));
    }

    @Test
    public void dashboardStartPreflightDoesNotConsumeOrBlockForDoorState() throws Exception {
        String handler = handlerSource();
        String endpoint = between(
                handler,
                "void startDashboardFile(HttpRequest request, HttpResponse response)",
                "void getDashboardFileStartStatus(HttpRequest request, HttpResponse response)"
        );
        String preflight = between(
                handler,
                "private String dashboardFileStartPreflight(\n            IMachine machine",
                "private List<DashboardCompatibilityIssue> buildDashboardFileWarnings("
        );

        assertTrue(endpoint.indexOf("dashboardFileStartPreflight(")
                < endpoint.indexOf("tryAcquirePrintPreparationLease()"));
        assertFalse(endpoint.contains("mFileDetailState.consumed = true"));
        assertFalse(preflight.contains("Enclosure"));
        assertFalse(preflight.toLowerCase().contains("door"));
        assertFalse(handler.contains("Close the enclosure door before starting."));
        assertFalse(handler.contains("Wait for live enclosure door telemetry before starting."));
    }

    @Test
    public void dashboardResumeAndStatusControlsDoNotUseDoorAsABlocker() throws Exception {
        String handler = handlerSource();
        String actions = between(
                handler,
                "private void handleDashboardJobAction(",
                "private boolean eventMatchesAction("
        );
        String controls = between(
                handler,
                "boolean baseControlsAvailable = connected",
                "JSONObject lastCommand = new JSONObject()"
        );
        String blockedReason = between(
                handler,
                "private String controlBlockedReason(",
                "private String printModeName("
        );

        assertFalse(actions.contains("isEnclosureTelemetryFresh"));
        assertFalse(actions.contains("isDoorOpen"));
        assertFalse(actions.contains("Close the enclosure door"));
        assertFalse(controls.toLowerCase().contains("door"));
        assertFalse(blockedReason.toLowerCase().contains("door"));
        assertFalse(handler.contains("Wait for live enclosure door telemetry before resuming."));
        assertFalse(handler.contains("Close the enclosure door before resuming."));
        assertFalse(handler.contains("Close the enclosure door to resume"));
    }

    private String handlerSource() throws Exception {
        Path source = Paths.get(
                "src",
                "main",
                "java",
                "fabscreen",
                "platform",
                "base",
                "legacy",
                "server",
                "http",
                "handlers",
                "OrcaRequestHandler.java"
        );
        return new String(Files.readAllBytes(source), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
    }

    private String between(String source, String start, String end) {
        int startIndex = source.indexOf(start);
        int endIndex = source.indexOf(end, startIndex + start.length());
        assertTrue("Missing start marker: " + start, startIndex >= 0);
        assertTrue("Missing end marker: " + end, endIndex > startIndex);
        return source.substring(startIndex, endIndex);
    }
}
