package fabscreen.platform.base.legacy.server.http.handlers;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DashboardEnclosureControlInputTest {
    @Test
    public void acceptsEnabledLevelsAcrossControllerRange() {
        DashboardEnclosureControlInput.Result minimum =
                DashboardEnclosureControlInput.parse("true", "1");
        DashboardEnclosureControlInput.Result maximum =
                DashboardEnclosureControlInput.parse("TRUE", "100");

        assertTrue(minimum.valid);
        assertTrue(minimum.enabled);
        assertEquals(1, minimum.requestedPercent);
        assertEquals(1, minimum.effectivePercent);
        assertTrue(maximum.valid);
        assertEquals(100, maximum.effectivePercent);
    }

    @Test
    public void disablingAlwaysSendsZeroWhileAcceptingRememberedPercent() {
        DashboardEnclosureControlInput.Result result =
                DashboardEnclosureControlInput.parse("false", "72");

        assertTrue(result.valid);
        assertFalse(result.enabled);
        assertEquals(72, result.requestedPercent);
        assertEquals(0, result.effectivePercent);
    }

    @Test
    public void acceptsExplicitDisabledZero() {
        DashboardEnclosureControlInput.Result result =
                DashboardEnclosureControlInput.parse("false", "0");

        assertTrue(result.valid);
        assertEquals(0, result.effectivePercent);
    }

    @Test
    public void rejectsMissingOrNonBooleanEnabledValues() {
        assertFalse(DashboardEnclosureControlInput.parse(null, "50").valid);
        assertFalse(DashboardEnclosureControlInput.parse("yes", "50").valid);
        assertFalse(DashboardEnclosureControlInput.parse("1", "50").valid);
        assertFalse(DashboardEnclosureControlInput.parse(" true ", "50").valid);
    }

    @Test
    public void rejectsMalformedOrOutOfRangePercents() {
        assertFalse(DashboardEnclosureControlInput.parse("true", null).valid);
        assertFalse(DashboardEnclosureControlInput.parse("true", "").valid);
        assertFalse(DashboardEnclosureControlInput.parse("true", "-1").valid);
        assertFalse(DashboardEnclosureControlInput.parse("true", "+1").valid);
        assertFalse(DashboardEnclosureControlInput.parse("true", "1.0").valid);
        assertFalse(DashboardEnclosureControlInput.parse("true", " 50 ").valid);
        assertFalse(DashboardEnclosureControlInput.parse("true", "101").valid);
    }

    @Test
    public void rejectsEnabledZeroAsContradictory() {
        DashboardEnclosureControlInput.Result result =
                DashboardEnclosureControlInput.parse("true", "0");

        assertFalse(result.valid);
        assertTrue(result.error.contains("1 to 100"));
    }
}
