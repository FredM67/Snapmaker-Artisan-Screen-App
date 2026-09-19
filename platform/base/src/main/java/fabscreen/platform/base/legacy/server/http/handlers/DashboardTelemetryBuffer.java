package fabscreen.platform.base.legacy.server.http.handlers;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** A bounded rolling buffer whose retention uses monotonic time, not the wall clock. */
final class DashboardTelemetryBuffer<T> {
    private final long mMaximumAgeMs;
    private final int mMaximumSamples;
    private final Deque<Entry<T>> mEntries = new ArrayDeque<>();

    DashboardTelemetryBuffer(long maximumAgeMs, int maximumSamples) {
        if (maximumAgeMs <= 0 || maximumSamples <= 0) {
            throw new IllegalArgumentException("History bounds must be positive.");
        }
        mMaximumAgeMs = maximumAgeMs;
        mMaximumSamples = maximumSamples;
    }

    synchronized void add(T sample, long elapsedAt) {
        mEntries.addLast(new Entry<>(sample, elapsedAt));
        prune(elapsedAt);
    }

    synchronized List<T> snapshot(long elapsedAt) {
        prune(elapsedAt);
        List<T> result = new ArrayList<>(mEntries.size());
        for (Entry<T> entry : mEntries) result.add(entry.sample);
        return result;
    }

    private void prune(long elapsedAt) {
        while (!mEntries.isEmpty() && (mEntries.size() > mMaximumSamples
                || elapsedAt - mEntries.peekFirst().elapsedAt > mMaximumAgeMs)) {
            mEntries.removeFirst();
        }
    }

    private static final class Entry<T> {
        final T sample;
        final long elapsedAt;

        Entry(T sample, long elapsedAt) {
            this.sample = sample;
            this.elapsedAt = elapsedAt;
        }
    }
}
