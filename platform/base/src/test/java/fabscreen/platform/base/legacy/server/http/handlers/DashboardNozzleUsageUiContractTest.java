package fabscreen.platform.base.legacy.server.http.handlers;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Static contract checks for the self-contained dashboard asset. */
public class DashboardNozzleUsageUiContractTest {
    @Test
    public void partialMetadataNeverClaimsUnusedBeforeSafetyIsReady() throws Exception {
        String dashboard = dashboardSource();

        assertTrue(dashboard.contains("analysis.safetyReady === true"));
        assertTrue(dashboard.contains(
                "safetyReady && declared && metadata.toolUsageConfirmed === true"));
        assertTrue(dashboard.contains("Whole-job usage is being verified"));
        assertTrue(dashboard.contains("complete executable and whole-job nozzle use"));
    }

    @Test
    public void terminalUsageRendersExplicitUnusedSideAndFiltersNozzleDetails() throws Exception {
        String dashboard = dashboardSource();

        assertTrue(dashboard.contains("metadata.usesLeft"));
        assertTrue(dashboard.contains("metadata.usesRight"));
        assertTrue(dashboard.contains("Will not be used"));
        assertTrue(dashboard.contains("usage.confirmed && usage.left"));
        assertTrue(dashboard.contains("usage.confirmed && usage.right"));
        assertTrue(dashboard.contains("fileNozzlePairText(mismatch.file, mismatch.mismatchedSides)"));
    }

    private String dashboardSource() throws Exception {
        Path asset = Paths.get("src", "main", "assets", "artisan_dashboard.html");
        return new String(Files.readAllBytes(asset), StandardCharsets.UTF_8);
    }

}
