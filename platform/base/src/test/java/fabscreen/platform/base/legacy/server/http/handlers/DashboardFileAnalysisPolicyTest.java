package fabscreen.platform.base.legacy.server.http.handlers;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DashboardFileAnalysisPolicyTest {
    @Test
    public void analysisWatchdogGrowsForLargeIntegerProgressSteps() {
        assertEquals(45_000L, DashboardFileAnalysisPolicy.analysisStallTimeoutMs(0L));
        assertTrue(DashboardFileAnalysisPolicy.analysisStallTimeoutMs(126_564_554L)
                > 60_000L);
        long large = DashboardFileAnalysisPolicy.analysisStallTimeoutMs(1024L * 1024L * 1024L);
        assertTrue(large > 45_000L);
        assertTrue(large <= 5L * 60L * 1000L);
    }

    @Test
    public void printStartAllowsConservativeLargeUsbCopy() {
        long small = DashboardFileAnalysisPolicy.printStartTimeoutMs(1024L);
        long canonical = DashboardFileAnalysisPolicy.printStartTimeoutMs(127L * 1024L * 1024L);

        assertTrue(small >= 120_000L);
        assertTrue(canonical > 8L * 60L * 1000L);
        assertTrue(canonical <= 60L * 60L * 1000L);
    }

    @Test
    public void onlyExactExplicitPollMayReuseDetailState() {
        assertFalse(DashboardFileAnalysisPolicy.canReuseDetailRequest(null, 17L));
        assertFalse(DashboardFileAnalysisPolicy.canReuseDetailRequest(16L, 17L));
        assertFalse(DashboardFileAnalysisPolicy.canReuseDetailRequest(0L, 17L));
        assertTrue(DashboardFileAnalysisPolicy.canReuseDetailRequest(17L, 17L));
    }
}
