package fabscreen.platform.base.legacy.server.http.handlers;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DashboardListThumbnailContractTest {
    @Test
    public void listThumbnailIsAuthorizedContentBoundAndIndependentOfDetailParser()
            throws Exception {
        String handler = handlerSource();
        String endpoint = between(
                handler,
                "void getDashboardListThumbnail(HttpRequest request, HttpResponse response)",
                "void startDashboardFile(HttpRequest request, HttpResponse response)"
        );

        assertTrue(endpoint.contains("isDashboardRequestAuthorized(request)"));
        assertTrue(endpoint.contains("request.getParameter(\"source\")"));
        assertTrue(endpoint.contains("request.getParameter(\"path\")"));
        assertTrue(endpoint.contains("resolveDashboardFile(selectorResult.selection)"));
        assertTrue(endpoint.contains("DashboardGcodeThumbnailExtractor.extract(input)"));
        assertTrue(endpoint.contains("mListThumbnailCache.acquire(resolved.fingerprint"));
        assertTrue(endpoint.contains("decodeDashboardListThumbnail(extracted.encodedData)"));
        assertTrue(endpoint.contains("StatusCode.SC_ACCEPTED"));
        assertTrue(endpoint.contains("429"));

        assertFalse(endpoint.contains("mFileDetailState"));
        assertFalse(endpoint.contains("startDashboardFileDetail"));
        assertFalse(endpoint.contains("GcodeParser"));
        assertFalse(endpoint.contains("cancelPendingDashboardFileDetailForNewOpen"));
    }

    @Test
    public void filesResponseAdvertisesAuthenticatedFetchPolicy() throws Exception {
        String handler = handlerSource();
        String policy = between(
                handler,
                "private JSONObject dashboardListThumbnailPolicyJson()",
                "private boolean isSafeGcodeFilename("
        );

        assertTrue(policy.contains("URI_DASHBOARD_FILES_LIST_THUMBNAIL"));
        assertTrue(policy.contains("authenticatedFetchRequired"));
        assertTrue(policy.contains("MAX_LIST_THUMBNAIL_EDGE_PX"));
        assertTrue(policy.contains("MAX_CONCURRENT_LIST_THUMBNAIL_SCANS"));
        assertTrue(policy.contains("LIST_THUMBNAIL_RETRY_AFTER_MS"));
        assertTrue(policy.contains("no-store"));
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

    private static String between(String source, String start, String end) {
        int startIndex = source.indexOf(start);
        int endIndex = source.indexOf(end, startIndex + start.length());
        if (startIndex < 0 || endIndex < 0) {
            throw new AssertionError("Unable to locate source contract boundaries.");
        }
        return source.substring(startIndex, endIndex);
    }
}
