package fabscreen.platform.base.legacy.server.http.handlers;

/**
 * Parses the safety-sensitive choices attached to a one-use dashboard file preview.
 *
 * <p>This class deliberately accepts only the two controller bed modes and an explicit
 * nozzle-mismatch continuation. Generic preview warnings are informational and are not part
 * of this contract.</p>
 */
final class DashboardPrintStartOptions {
    static final String BED_MODE_INNER = "inner";
    static final String BED_MODE_WHOLE = "whole";

    final boolean valid;
    final int errorStatus;
    final String error;
    final String bedModeId;
    final int controllerBedMode;
    final boolean nozzleDiameterMismatchConfirmed;

    private DashboardPrintStartOptions(
            boolean valid,
            int errorStatus,
            String error,
            String bedModeId,
            int controllerBedMode,
            boolean nozzleDiameterMismatchConfirmed
    ) {
        this.valid = valid;
        this.errorStatus = errorStatus;
        this.error = error;
        this.bedModeId = bedModeId;
        this.controllerBedMode = controllerBedMode;
        this.nozzleDiameterMismatchConfirmed = nozzleDiameterMismatchConfirmed;
    }

    static DashboardPrintStartOptions parse(
            String rawBedMode,
            String rawNozzleDiameterMismatchConfirmation,
            boolean nozzleDiameterMismatchRequired
    ) {
        final int controllerBedMode;
        if (BED_MODE_INNER.equals(rawBedMode)) {
            controllerBedMode = 0;
        } else if (BED_MODE_WHOLE.equals(rawBedMode)) {
            controllerBedMode = 1;
        } else {
            return invalid(400, "Parameter bedMode must be exactly inner or whole.");
        }

        boolean mismatchConfirmed = false;
        if (rawNozzleDiameterMismatchConfirmation != null
                && !rawNozzleDiameterMismatchConfirmation.isEmpty()) {
            if ("true".equals(rawNozzleDiameterMismatchConfirmation)) {
                mismatchConfirmed = true;
            } else if (!"false".equals(rawNozzleDiameterMismatchConfirmation)) {
                return invalid(400,
                        "Parameter confirmNozzleDiameterMismatch must be true or false.");
            }
        }

        if (nozzleDiameterMismatchRequired && !mismatchConfirmed) {
            return invalid(409,
                    "Confirm the inconsistent nozzle diameter warning to continue, or cancel.");
        }
        if (!nozzleDiameterMismatchRequired && mismatchConfirmed) {
            return invalid(409,
                    "No nozzle diameter mismatch is bound to this preview. Reopen the file details.");
        }
        return new DashboardPrintStartOptions(
                true,
                0,
                "",
                rawBedMode,
                controllerBedMode,
                mismatchConfirmed
        );
    }

    private static DashboardPrintStartOptions invalid(int status, String error) {
        return new DashboardPrintStartOptions(false, status, error, "", -1, false);
    }
}
