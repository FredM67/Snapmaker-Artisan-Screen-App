package fabscreen.platform.base.legacy.server.http.handlers;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Iterator;

/**
 * Small in-memory cache for completed, immutable G-code analyses.
 *
 * <p>The content cache is keyed only by a strong identity: SHA-256, byte length and the parser
 * schema. A filesystem fingerprint is kept solely as a lookup hint. Callers must still compare
 * the SHA-256 of a staged print snapshot with the cached key before reusing safety results.</p>
 */
final class DashboardFileAnalysisCache<V> {
    static final int CURRENT_SCHEMA = 2;

    private final int maximumEntries;
    private final LinkedHashMap<Key, V> entries;
    private final Map<String, Key> fingerprintHints = new HashMap<>();

    DashboardFileAnalysisCache(int maximumEntries) {
        if (maximumEntries <= 0) throw new IllegalArgumentException("maximumEntries <= 0");
        this.maximumEntries = maximumEntries;
        entries = new LinkedHashMap<Key, V>(maximumEntries, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, V> eldest) {
                boolean remove = size() > DashboardFileAnalysisCache.this.maximumEntries;
                if (remove) removeHintsFor(eldest.getKey());
                return remove;
            }
        };
    }

    synchronized void put(String fingerprint, String sha256, long sizeBytes, V value) {
        Key key = Key.create(sha256, sizeBytes, CURRENT_SCHEMA);
        if (key == null || value == null) return;
        entries.put(key, value);
        if (fingerprint != null && !fingerprint.isEmpty()) fingerprintHints.put(fingerprint, key);
    }

    synchronized V get(String sha256, long sizeBytes) {
        Key key = Key.create(sha256, sizeBytes, CURRENT_SCHEMA);
        return key == null ? null : entries.get(key);
    }

    /**
     * Returns a candidate selected by stable filesystem metadata. This is deliberately a hint,
     * not proof of content identity; print-start must verify the staged snapshot with {@link #get}.
     */
    synchronized Candidate<V> findByFingerprint(String fingerprint, long sizeBytes) {
        Key key = fingerprintHints.get(fingerprint);
        if (key == null || key.sizeBytes != sizeBytes || key.schema != CURRENT_SCHEMA) return null;
        V value = entries.get(key);
        if (value == null) {
            fingerprintHints.remove(fingerprint);
            return null;
        }
        return new Candidate<>(key.sha256, key.sizeBytes, value);
    }

    synchronized int size() {
        return entries.size();
    }

    private void removeHintsFor(Key key) {
        Iterator<Map.Entry<String, Key>> iterator = fingerprintHints.entrySet().iterator();
        while (iterator.hasNext()) {
            if (key.equals(iterator.next().getValue())) iterator.remove();
        }
    }

    static final class Candidate<V> {
        final String sha256;
        final long sizeBytes;
        final V value;

        Candidate(String sha256, long sizeBytes, V value) {
            this.sha256 = sha256;
            this.sizeBytes = sizeBytes;
            this.value = value;
        }
    }

    private static final class Key {
        final String sha256;
        final long sizeBytes;
        final int schema;

        private Key(String sha256, long sizeBytes, int schema) {
            this.sha256 = sha256;
            this.sizeBytes = sizeBytes;
            this.schema = schema;
        }

        static Key create(String sha256, long sizeBytes, int schema) {
            if (sha256 == null || sha256.length() != 64 || sizeBytes < 0L || schema <= 0) {
                return null;
            }
            String normalized = sha256.toLowerCase(Locale.US);
            for (int index = 0; index < normalized.length(); index++) {
                char value = normalized.charAt(index);
                if (!((value >= '0' && value <= '9') || (value >= 'a' && value <= 'f'))) {
                    return null;
                }
            }
            return new Key(normalized, sizeBytes, schema);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Key)) return false;
            Key key = (Key) other;
            return sizeBytes == key.sizeBytes
                    && schema == key.schema
                    && sha256.equals(key.sha256);
        }

        @Override
        public int hashCode() {
            int result = sha256.hashCode();
            result = 31 * result + (int) (sizeBytes ^ (sizeBytes >>> 32));
            result = 31 * result + schema;
            return result;
        }
    }
}
