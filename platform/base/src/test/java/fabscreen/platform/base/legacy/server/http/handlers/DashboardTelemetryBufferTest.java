package fabscreen.platform.base.legacy.server.http.handlers;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

public class DashboardTelemetryBufferTest {
    @Test
    public void retainsExactThirtyMinuteBoundaryAndPrunesOlderSamples() {
        DashboardTelemetryBuffer<String> buffer = new DashboardTelemetryBuffer<>(1_800_000L, 1_800);
        buffer.add("first", 10L);
        buffer.add("second", 1_000L);
        assertEquals(Arrays.asList("first", "second"), buffer.snapshot(1_800_010L));
        assertEquals(Collections.singletonList("second"), buffer.snapshot(1_800_011L));
    }

    @Test
    public void capsSampleCountEvenWhenClockDoesNotAdvance() {
        DashboardTelemetryBuffer<Integer> buffer = new DashboardTelemetryBuffer<>(1_800_000L, 1_800);
        for (int index = 0; index < 1_820; index++) buffer.add(index, 50L);
        assertEquals(1_800, buffer.snapshot(50L).size());
        assertEquals(Integer.valueOf(20), buffer.snapshot(50L).get(0));
        assertEquals(Integer.valueOf(1_819), buffer.snapshot(50L).get(1_799));
    }

    @Test
    public void prunesWhenReadWithoutFurtherSamples() {
        DashboardTelemetryBuffer<String> buffer = new DashboardTelemetryBuffer<>(1_800_000L, 1_800);
        buffer.add("old", 10L);
        assertEquals(Collections.emptyList(), buffer.snapshot(1_800_011L));
    }
}
