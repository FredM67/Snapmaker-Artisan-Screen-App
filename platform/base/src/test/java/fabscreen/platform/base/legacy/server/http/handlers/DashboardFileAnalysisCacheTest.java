package fabscreen.platform.base.legacy.server.http.handlers;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class DashboardFileAnalysisCacheTest {
    private static final String SHA_A = repeat('a', 64);
    private static final String SHA_B = repeat('b', 64);
    private static final String SHA_C = repeat('c', 64);

    @Test
    public void strongLookupRequiresDigestAndSize() {
        DashboardFileAnalysisCache<String> cache = new DashboardFileAnalysisCache<>(2);
        cache.put("local\nmodel.gcode", SHA_A, 123L, "analysis-a");

        assertEquals("analysis-a", cache.get(SHA_A.toUpperCase(), 123L));
        assertNull(cache.get(SHA_A, 124L));
        assertNull(cache.get(SHA_B, 123L));
    }

    @Test
    public void fingerprintIsOnlyAHintToTheStrongKey() {
        DashboardFileAnalysisCache<String> cache = new DashboardFileAnalysisCache<>(2);
        cache.put("usb\nmodel.gcode\n123", SHA_A, 123L, "analysis-a");

        DashboardFileAnalysisCache.Candidate<String> candidate =
                cache.findByFingerprint("usb\nmodel.gcode\n123", 123L);
        assertEquals(SHA_A, candidate.sha256);
        assertEquals("analysis-a", candidate.value);
        assertNull(cache.findByFingerprint("usb\nmodel.gcode\n123", 122L));
    }

    @Test
    public void evictionRemovesFingerprintHints() {
        DashboardFileAnalysisCache<String> cache = new DashboardFileAnalysisCache<>(2);
        cache.put("a", SHA_A, 1L, "a");
        cache.put("b", SHA_B, 2L, "b");
        cache.put("c", SHA_C, 3L, "c");

        assertEquals(2, cache.size());
        assertNull(cache.findByFingerprint("a", 1L));
        assertEquals("b", cache.findByFingerprint("b", 2L).value);
        assertEquals("c", cache.findByFingerprint("c", 3L).value);
    }

    @Test
    public void invalidDigestNeverEntersCache() {
        DashboardFileAnalysisCache<String> cache = new DashboardFileAnalysisCache<>(2);
        cache.put("a", "not-a-digest", 1L, "a");

        assertEquals(0, cache.size());
        assertNull(cache.findByFingerprint("a", 1L));
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) result.append(value);
        return result.toString();
    }
}
