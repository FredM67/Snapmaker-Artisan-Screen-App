package fabscreen.platform.base.legacy.server.http.handlers;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Guards parity between Dashboard FDM controls and the continuable stock A400 flow. */
public class DashboardNativeFdmPolicyContractTest {
    @Test
    public void readyDetailPollingKeepsTheReviewedNozzleSnapshot() throws Exception {
        String handler = handlerSource();
        String detail = between(
                handler,
                "void getDashboardFileDetail(HttpRequest request, HttpResponse response)",
                "void getDashboardFileThumbnail(HttpRequest request, HttpResponse response)"
        );

        assertTrue(detail.contains("mFileDetailState.fingerprint.equals(resolved.fingerprint)"));
        assertTrue(detail.contains("!mFileDetailState.consumed"));
        assertFalse(detail.contains("inspectDashboardNozzleDiameterMismatch"));
        assertFalse(detail.contains("safetyEquivalentTo"));
    }

    @Test
    public void startKeepsCoreMachineGuardsButNotOptionalTelemetryOrCompatibilityBlocks()
            throws Exception {
        String handler = handlerSource();
        String preflight = between(
                handler,
                "private String dashboardFileStartPreflight(\n            IMachine machine",
                "private List<DashboardCompatibilityIssue> buildDashboardFileWarnings("
        );

        assertTrue(preflight.contains("Printer is disconnected."));
        assertTrue(preflight.contains("info.workType != IMachine.WorkType.FDM"));
        assertTrue(preflight.contains("SYSTEM_STATUS_IDLE"));
        assertTrue(preflight.contains("Emergency stop is active."));
        assertTrue(preflight.contains("mActionPending"));
        assertTrue(preflight.contains("mConsoleCommandPending"));
        assertTrue(preflight.contains("expectedMismatch.detected"));
        assertTrue(preflight.contains("nozzleDiameterMismatchConfirmed"));

        assertFalse(preflight.contains("getFdmStatus"));
        assertFalse(preflight.contains("getFDMController"));
        assertFalse(preflight.contains("extruder0RetractionMm"));
        assertFalse(preflight.contains("extruder1RetractionMm"));
        assertFalse(preflight.contains("boundary"));
        assertFalse(preflight.contains("filament"));
        assertFalse(handler.contains(
                "Resolve the filament condition and wait for live extruder telemetry before starting."));
        assertFalse(handler.contains("Wait for live left nozzle telemetry before starting."));
        assertFalse(handler.contains("Wait for live right nozzle telemetry before starting."));
    }

    @Test
    public void stockContinuableFindingsAreWarningsAndOnlyNonFdmIsACompatibilityBlock()
            throws Exception {
        String handler = handlerSource();
        String warnings = between(
                handler,
                "private List<DashboardCompatibilityIssue> buildDashboardFileWarnings(",
                "private List<DashboardCompatibilityIssue> buildDashboardFileBlockingIssues("
        );
        String blockers = between(
                handler,
                "private List<DashboardCompatibilityIssue> buildDashboardFileBlockingIssues(",
                "private boolean hasDashboardOwnedRemoteStartGate("
        );

        assertTrue(warnings.contains("clean_bed_and_nozzles"));
        assertTrue(warnings.contains("toolhead_mismatch"));
        assertTrue(warnings.contains("NOZZLE_DIAMETER_MISMATCH_ID"));
        assertTrue(warnings.contains("retraction_over_2mm"));
        assertFalse(warnings.contains("unknown_model_bounds"));
        assertFalse(warnings.contains("unknown_gcode_toolhead"));
        assertFalse(warnings.contains("unknown_left_nozzle_diameter"));
        assertFalse(warnings.contains("unknown_right_nozzle_diameter"));

        assertTrue(blockers.contains("not_fdm_gcode"));
        assertFalse(blockers.contains("model_out_of_bounds"));
        assertFalse(blockers.contains("toolhead_mismatch"));
        assertFalse(blockers.contains("retraction_over_2mm"));
    }

    @Test
    public void resumeUsesMachineStateAndControllerWithoutFilamentPreflight() throws Exception {
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

        assertTrue(actions.contains("SYSTEM_STATUS_PAUSED"));
        assertTrue(actions.contains("controller.resume()"));
        assertFalse(actions.contains("isResumeFilamentSafe"));
        assertFalse(actions.contains("filament condition"));
        assertTrue(controls.contains("controls.put(\"canResume\""));
        assertFalse(controls.contains("filamentRunout"));
        assertFalse(handler.contains("Resolve filament runout to resume"));
        assertTrue(handler.contains("safety.put(\"filamentRunout\""));
        assertTrue(handler.contains("safety.put(\"filamentTelemetryAvailable\""));
    }

    @Test
    public void bedModeAckIsReportedButNeverBlocksControllerLaunch() throws Exception {
        String handler = handlerSource();
        String bedMode = between(
                handler,
                "private void setDashboardPrintBedModeAndLaunch(",
                "private void recordDashboardBedModeResult("
        );
        String record = between(
                handler,
                "private void recordDashboardBedModeResult(",
                "private void launchDashboardPrint("
        );

        assertTrue(bedMode.contains("setHeatedBedWorkMode(controllerBedMode)"));
        assertTrue(bedMode.contains("recordDashboardBedModeResult"));
        assertTrue(bedMode.contains("launchDashboardPrint(requestId, metadata)"));
        assertFalse(bedMode.contains("bed_mode_failed"));
        assertFalse(bedMode.contains("A live Artisan heated bed is required."));
        assertTrue(record.contains("bedModeResult = result"));
        assertTrue(handler.contains("bedMode.put(\"result\", bedModeResult)"));
        assertTrue(handler.contains(
                "bedMode.put(\"controllerErrorCode\", bedModeControllerErrorCode)"));
    }

    @Test
    public void unchangedPreviewIsConsumedOnlyWhenControllerStartIsCommitted() throws Exception {
        String handler = handlerSource();
        String endpoint = between(
                handler,
                "void startDashboardFile(HttpRequest request, HttpResponse response)",
                "void getDashboardFileStartStatus(HttpRequest request, HttpResponse response)"
        );
        String launch = between(
                handler,
                "private void launchDashboardPrint(",
                "private void markDashboardControllerStartCommitted("
        );
        String commit = between(
                handler,
                "private void markDashboardControllerStartCommitted(",
                "private void routeDashboardToActivePrint("
        );

        assertFalse(endpoint.contains("mFileDetailState.consumed = true"));
        assertTrue(endpoint.indexOf("dashboardFileStartPreflight(")
                < endpoint.indexOf("tryAcquirePrintPreparationLease()"));
        assertTrue(launch.indexOf("controller.start(preparationLease)")
                < launch.indexOf("markDashboardControllerStartCommitted(requestId)"));
        assertTrue(commit.contains(
                "mFileDetailState.requestId == mFileStartState.previewRequestId"));
        assertTrue(commit.contains("mFileDetailState.consumed = true"));
        assertTrue(handler.contains("confirmation.put(\"consumed\", consumed)"));
        assertTrue(handler.contains("json.put(\"controllerStartIssued\", controllerStartIssued)"));
        assertTrue(handler.contains("json.put(\"outcomeUncertain\", outcomeUncertain)"));
        assertTrue(handler.contains("json.put(\"retryable\""));
        assertTrue(endpoint.contains("JSONObject failure = queueFailure.toJson()"));
        assertTrue(endpoint.contains("failure.put(\"accepted\", false)"));
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
