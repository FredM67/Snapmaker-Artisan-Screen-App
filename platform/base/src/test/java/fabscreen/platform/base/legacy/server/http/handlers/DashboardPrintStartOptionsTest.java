package fabscreen.platform.base.legacy.server.http.handlers;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DashboardPrintStartOptionsTest {
    @Test
    public void innerBedDoesNotRequireAnAcknowledgement() {
        DashboardPrintStartOptions options = DashboardPrintStartOptions.parse(
                "inner", null, false);

        assertTrue(options.valid);
        assertEquals("inner", options.bedModeId);
        assertEquals(0, options.controllerBedMode);
        assertFalse(options.nozzleDiameterMismatchConfirmed);
    }

    @Test
    public void wholeBedIsAnIndependentSelection() {
        DashboardPrintStartOptions options = DashboardPrintStartOptions.parse(
                "whole", "false", false);

        assertTrue(options.valid);
        assertEquals("whole", options.bedModeId);
        assertEquals(1, options.controllerBedMode);
    }

    @Test
    public void mismatchNeedsAnExplicitContinue() {
        DashboardPrintStartOptions missing = DashboardPrintStartOptions.parse(
                "inner", null, true);
        DashboardPrintStartOptions continued = DashboardPrintStartOptions.parse(
                "inner", "true", true);

        assertFalse(missing.valid);
        assertEquals(409, missing.errorStatus);
        assertTrue(continued.valid);
        assertTrue(continued.nozzleDiameterMismatchConfirmed);
    }

    @Test
    public void staleMismatchContinueIsRejected() {
        DashboardPrintStartOptions options = DashboardPrintStartOptions.parse(
                "whole", "true", false);

        assertFalse(options.valid);
        assertEquals(409, options.errorStatus);
    }

    @Test
    public void unknownBedModeAndConfirmationValuesAreRejected() {
        assertEquals(400, DashboardPrintStartOptions.parse(
                "all", null, false).errorStatus);
        assertEquals(400, DashboardPrintStartOptions.parse(
                "inner", "yes", true).errorStatus);
    }

    @Test
    public void previewSnapshotCapturesMismatchForKnownDiameters() {
        OrcaRequestHandler.DashboardNozzleDiameterMismatch preview =
                OrcaRequestHandler.DashboardNozzleDiameterMismatch.create(
                        0.4f, 0.4f, 0.4f, 0.6f);

        assertTrue(preview.detected);
        assertFalse(preview.leftMismatch);
        assertTrue(preview.rightMismatch);
        assertEquals(0.4f, preview.fileRightMm, 0f);
        assertEquals(0.6f, preview.machineRightMm, 0f);
    }

    @Test
    public void unknownNozzleValuesDoNotCreateAnOverrideableMismatch() {
        OrcaRequestHandler.DashboardNozzleDiameterMismatch snapshot =
                OrcaRequestHandler.DashboardNozzleDiameterMismatch.create(
                        -1f, 0.4f, -1f, 0.4f);

        assertFalse(snapshot.detected);
    }

    @Test
    public void unusedNozzleIsExcludedFromTheContentBoundMismatchSnapshot() {
        OrcaRequestHandler.DashboardNozzleDiameterMismatch unusedSideDiffers =
                OrcaRequestHandler.DashboardNozzleDiameterMismatch.create(
                        0.4f, -1f, 0.4f, 0.8f);
        OrcaRequestHandler.DashboardNozzleDiameterMismatch usedSideDiffers =
                OrcaRequestHandler.DashboardNozzleDiameterMismatch.create(
                        0.4f, -1f, 0.6f, -1f);

        assertFalse(unusedSideDiffers.detected);
        assertTrue(usedSideDiffers.detected);
    }
}
