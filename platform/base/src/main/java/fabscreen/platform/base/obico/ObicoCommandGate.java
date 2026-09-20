package fabscreen.platform.base.obico;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Suppresses replayed or excessively frequent cloud commands before they reach the machine.
 */
public final class ObicoCommandGate {
    public enum Decision {
        ALLOWED,
        DUPLICATE,
        RATE_LIMITED
    }

    private static final long DEDUP_WINDOW_MILLIS = 30_000L;
    private static final long PER_COMMAND_RATE_LIMIT_MILLIS = 2_000L;
    private static final int MAX_FINGERPRINTS = 64;

    private final LinkedHashMap<String, Long> fingerprints = new LinkedHashMap<>();
    private final long[] lastAcceptedByType = new long[ObicoRemoteCommand.Type.values().length];

    public synchronized Decision evaluate(ObicoRemoteCommand command, long nowMillis) {
        if (command == null) {
            return Decision.DUPLICATE;
        }
        long now = Math.max(0L, nowMillis);
        prune(now);
        Long seenAt = fingerprints.get(command.getFingerprint());
        if (seenAt != null && now - seenAt < DEDUP_WINDOW_MILLIS) {
            return Decision.DUPLICATE;
        }

        int typeIndex = command.getType().ordinal();
        long lastAccepted = lastAcceptedByType[typeIndex];
        if (lastAccepted > 0L && now - lastAccepted < PER_COMMAND_RATE_LIMIT_MILLIS) {
            fingerprints.put(command.getFingerprint(), now);
            trim();
            return Decision.RATE_LIMITED;
        }

        fingerprints.put(command.getFingerprint(), now);
        lastAcceptedByType[typeIndex] = now;
        trim();
        return Decision.ALLOWED;
    }

    public synchronized void reset() {
        fingerprints.clear();
        for (int index = 0; index < lastAcceptedByType.length; index++) {
            lastAcceptedByType[index] = 0L;
        }
    }

    private void prune(long nowMillis) {
        Iterator<Map.Entry<String, Long>> iterator = fingerprints.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            if (nowMillis - entry.getValue() >= DEDUP_WINDOW_MILLIS) {
                iterator.remove();
            }
        }
    }

    private void trim() {
        while (fingerprints.size() > MAX_FINGERPRINTS) {
            Iterator<String> iterator = fingerprints.keySet().iterator();
            if (!iterator.hasNext()) {
                return;
            }
            iterator.next();
            iterator.remove();
        }
    }
}
