package fabscreen.platform.base.service.machine;

/** Pure monotonic freshness policy shared by live machine telemetry publishers. */
public final class TelemetryFreshness {
    private TelemetryFreshness() {
    }

    public static boolean isFresh(
            boolean hasLiveStatus,
            long updatedAtElapsedRealtime,
            long nowElapsedRealtime,
            long maximumAgeMs
    ) {
        if (!hasLiveStatus
                || updatedAtElapsedRealtime <= 0L
                || nowElapsedRealtime < updatedAtElapsedRealtime
                || maximumAgeMs < 0L) {
            return false;
        }
        return nowElapsedRealtime - updatedAtElapsedRealtime <= maximumAgeMs;
    }
}
