package fabscreen.platform.base.legacy.server.http.handlers;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/** Bounded positive/negative LRU and in-flight coordinator for Files-list thumbnails. */
final class DashboardListThumbnailCache {
    enum State { CACHED, STARTED, PENDING, BUSY }

    private final int maximumEntries;
    private final long maximumBytes;
    private final int maximumConcurrent;
    private final long positiveTtlMs;
    private final long negativeTtlMs;
    private final LinkedHashMap<String, Entry> entries =
            new LinkedHashMap<>(16, 0.75f, true);
    private final Set<String> inFlight = new HashSet<>();
    private long cachedBytes;

    DashboardListThumbnailCache(
            int maximumEntries,
            long maximumBytes,
            int maximumConcurrent,
            long positiveTtlMs,
            long negativeTtlMs
    ) {
        if (maximumEntries <= 0 || maximumBytes <= 0L || maximumConcurrent <= 0
                || positiveTtlMs <= 0L || negativeTtlMs <= 0L) {
            throw new IllegalArgumentException("Invalid thumbnail cache limits.");
        }
        this.maximumEntries = maximumEntries;
        this.maximumBytes = maximumBytes;
        this.maximumConcurrent = maximumConcurrent;
        this.positiveTtlMs = positiveTtlMs;
        this.negativeTtlMs = negativeTtlMs;
    }

    synchronized Acquisition acquire(String key, long nowMs) {
        if (key == null || key.isEmpty()) throw new IllegalArgumentException("Missing key.");
        Entry cached = entries.get(key);
        if (cached != null) {
            if (nowMs < cached.expiresAtMs) {
                return Acquisition.cached(cached);
            }
            removeEntry(key, cached);
        }
        if (inFlight.contains(key)) return Acquisition.pending();
        if (inFlight.size() >= maximumConcurrent) return Acquisition.busy();
        inFlight.add(key);
        return Acquisition.started();
    }

    synchronized void completeSuccess(String key, byte[] png, long nowMs) {
        inFlight.remove(key);
        if (png == null || png.length == 0 || png.length > maximumBytes) return;
        put(key, new Entry(png, "", nowMs + positiveTtlMs));
    }

    synchronized void completeMissing(String key, String message, long nowMs) {
        inFlight.remove(key);
        put(key, new Entry(null, message, nowMs + negativeTtlMs));
    }

    synchronized void completeFailure(String key) {
        inFlight.remove(key);
    }

    synchronized int size() {
        return entries.size();
    }

    synchronized int inFlightCount() {
        return inFlight.size();
    }

    private void put(String key, Entry value) {
        Entry previous = entries.put(key, value);
        if (previous != null) cachedBytes -= previous.byteCount();
        cachedBytes += value.byteCount();
        trim();
    }

    private void trim() {
        Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
        while ((entries.size() > maximumEntries || cachedBytes > maximumBytes)
                && iterator.hasNext()) {
            Entry entry = iterator.next().getValue();
            cachedBytes -= entry.byteCount();
            iterator.remove();
        }
    }

    private void removeEntry(String key, Entry entry) {
        entries.remove(key);
        cachedBytes -= entry.byteCount();
    }

    static final class Acquisition {
        final State state;
        final byte[] png;
        final String missingMessage;

        private Acquisition(State state, byte[] png, String missingMessage) {
            this.state = state;
            this.png = png == null ? null : png.clone();
            this.missingMessage = missingMessage == null ? "" : missingMessage;
        }

        static Acquisition cached(Entry entry) {
            return new Acquisition(State.CACHED, entry.png, entry.missingMessage);
        }

        static Acquisition started() {
            return new Acquisition(State.STARTED, null, "");
        }

        static Acquisition pending() {
            return new Acquisition(State.PENDING, null, "");
        }

        static Acquisition busy() {
            return new Acquisition(State.BUSY, null, "");
        }

        boolean hasImage() {
            return png != null && png.length > 0;
        }
    }

    private static final class Entry {
        final byte[] png;
        final String missingMessage;
        final long expiresAtMs;

        Entry(byte[] png, String missingMessage, long expiresAtMs) {
            this.png = png == null ? null : png.clone();
            this.missingMessage = missingMessage == null ? "" : missingMessage;
            this.expiresAtMs = expiresAtMs;
        }

        int byteCount() {
            return png == null ? 0 : png.length;
        }
    }
}
