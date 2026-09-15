package fabscreen.platform.base.service.machine;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TelemetryFreshnessTest {
    @Test
    public void acceptsExactMaximumAgeBoundary() {
        assertTrue(TelemetryFreshness.isFresh(true, 1_000L, 31_000L, 30_000L));
    }

    @Test
    public void rejectsStatusOlderThanMaximumAge() {
        assertFalse(TelemetryFreshness.isFresh(true, 1_000L, 31_001L, 30_000L));
    }

    @Test
    public void rejectsDefaultFutureAndUnpublishedTimestamps() {
        assertFalse(TelemetryFreshness.isFresh(true, 0L, 5_000L, 30_000L));
        assertFalse(TelemetryFreshness.isFresh(true, 5_001L, 5_000L, 30_000L));
        assertFalse(TelemetryFreshness.isFresh(false, 1_000L, 1_000L, 30_000L));
    }
}
