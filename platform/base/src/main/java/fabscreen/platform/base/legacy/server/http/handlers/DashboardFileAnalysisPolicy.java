package fabscreen.platform.base.legacy.server.http.handlers;

/** Size-aware watchdog policy for G-code analysis and immutable staging. */
final class DashboardFileAnalysisPolicy {
    private static final long MIB = 1024L * 1024L;
    private static final long MIN_ANALYSIS_STALL_MS = 45_000L;
    private static final long MAX_ANALYSIS_STALL_MS = 5L * 60L * 1000L;
    private static final long MIN_START_TIMEOUT_MS = 120_000L;
    private static final long MAX_START_TIMEOUT_MS = 60L * 60L * 1000L;

    private DashboardFileAnalysisPolicy() {
    }

    /**
     * Progress is reported in whole percentages. Allow one percent to take at least ten seconds
     * at 1 MiB/s, plus a fixed allowance for storage stalls and scheduler contention.
     */
    static long analysisStallTimeoutMs(long sizeBytes) {
        long onePercentBytes = Math.max(0L, sizeBytes) / 100L;
        long sizeAllowance = multiplySaturated(divideRoundingUp(onePercentBytes, MIB), 10_000L);
        return clamp(MIN_ANALYSIS_STALL_MS + sizeAllowance,
                MIN_ANALYSIS_STALL_MS, MAX_ANALYSIS_STALL_MS);
    }

    /**
     * Staging has no integer progress callback, so budget for a conservative 256 KiB/s copy and
     * retain the existing two-minute controller/preflight allowance.
     */
    static long printStartTimeoutMs(long sizeBytes) {
        long copySeconds = divideRoundingUp(Math.max(0L, sizeBytes), 256L * 1024L);
        long copyAllowance = multiplySaturated(copySeconds, 1_000L);
        return clamp(MIN_START_TIMEOUT_MS + copyAllowance,
                MIN_START_TIMEOUT_MS, MAX_START_TIMEOUT_MS);
    }

    /**
     * A filesystem fingerprint is not a content identity. Completed detail state can therefore
     * be reused only by an explicit poll of the request that produced it. A modal reopen has no
     * request id and must start a fresh strong-content verification.
     */
    static boolean canReuseDetailRequest(Long requestedId, long activeId) {
        return requestedId != null && requestedId > 0L && requestedId == activeId;
    }

    private static long divideRoundingUp(long value, long divisor) {
        if (value <= 0L) return 0L;
        return 1L + (value - 1L) / divisor;
    }

    private static long multiplySaturated(long left, long right) {
        if (left <= 0L || right <= 0L) return 0L;
        return left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
    }

    private static long clamp(long value, long minimum, long maximum) {
        if (value < minimum) return minimum;
        return Math.min(value, maximum);
    }
}
