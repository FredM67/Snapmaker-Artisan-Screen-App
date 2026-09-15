package fabscreen.platform.base.legacy.server.http.handlers;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DashboardListThumbnailCacheTest {
    @Test
    public void deduplicatesSameFileAndCapsDifferentConcurrentScans() {
        DashboardListThumbnailCache cache = cache(4, 100L, 2, 1_000L, 100L);

        assertEquals(DashboardListThumbnailCache.State.STARTED,
                cache.acquire("a", 0L).state);
        assertEquals(DashboardListThumbnailCache.State.PENDING,
                cache.acquire("a", 0L).state);
        assertEquals(DashboardListThumbnailCache.State.STARTED,
                cache.acquire("b", 0L).state);
        assertEquals(DashboardListThumbnailCache.State.BUSY,
                cache.acquire("c", 0L).state);
        assertEquals(2, cache.inFlightCount());

        cache.completeFailure("a");
        assertEquals(DashboardListThumbnailCache.State.STARTED,
                cache.acquire("c", 0L).state);
    }

    @Test
    public void cachesDefensiveImageCopyUntilPositiveTtlBoundary() {
        DashboardListThumbnailCache cache = cache(4, 100L, 1, 1_000L, 100L);
        byte[] image = {1, 2, 3};
        assertEquals(DashboardListThumbnailCache.State.STARTED,
                cache.acquire("a", 10L).state);
        cache.completeSuccess("a", image, 10L);
        image[0] = 9;

        DashboardListThumbnailCache.Acquisition hit = cache.acquire("a", 1_009L);
        assertEquals(DashboardListThumbnailCache.State.CACHED, hit.state);
        assertTrue(hit.hasImage());
        assertArrayEquals(new byte[]{1, 2, 3}, hit.png);

        assertEquals(DashboardListThumbnailCache.State.STARTED,
                cache.acquire("a", 1_010L).state);
    }

    @Test
    public void negativeEntryExpiresIndependentlyAndDoesNotInventZeroByteImage() {
        DashboardListThumbnailCache cache = cache(4, 100L, 1, 1_000L, 100L);
        assertEquals(DashboardListThumbnailCache.State.STARTED,
                cache.acquire("missing", 5L).state);
        cache.completeMissing("missing", "No thumbnail.", 5L);

        DashboardListThumbnailCache.Acquisition hit = cache.acquire("missing", 104L);
        assertEquals(DashboardListThumbnailCache.State.CACHED, hit.state);
        assertFalse(hit.hasImage());
        assertEquals("No thumbnail.", hit.missingMessage);
        assertEquals(DashboardListThumbnailCache.State.STARTED,
                cache.acquire("missing", 105L).state);
    }

    @Test
    public void lruHonorsEntryAndByteLimits() {
        DashboardListThumbnailCache cache = cache(2, 5L, 1, 1_000L, 100L);
        put(cache, "a", new byte[]{1, 2}, 0L);
        put(cache, "b", new byte[]{3, 4}, 0L);
        assertEquals(DashboardListThumbnailCache.State.CACHED,
                cache.acquire("a", 1L).state); // make a most recently used
        put(cache, "c", new byte[]{5, 6, 7}, 1L);

        assertEquals(2, cache.size());
        assertEquals(DashboardListThumbnailCache.State.STARTED,
                cache.acquire("b", 2L).state);
    }

    private void put(DashboardListThumbnailCache cache, String key, byte[] value, long now) {
        assertEquals(DashboardListThumbnailCache.State.STARTED,
                cache.acquire(key, now).state);
        cache.completeSuccess(key, value, now);
    }

    private DashboardListThumbnailCache cache(
            int entries,
            long bytes,
            int concurrent,
            long positiveTtl,
            long negativeTtl
    ) {
        return new DashboardListThumbnailCache(
                entries,
                bytes,
                concurrent,
                positiveTtl,
                negativeTtl
        );
    }
}
