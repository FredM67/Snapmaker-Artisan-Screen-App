package fabscreen.platform.base.legacy.server.http.handlers;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DashboardContentSecurityPolicyTest {
    @Test
    public void permitsAuthenticatedThumbnailBlobUrls() {
        assertTrue(DashboardContentSecurityPolicy.VALUE.contains(
                "img-src 'self' data: blob:;"
        ));
    }
}
