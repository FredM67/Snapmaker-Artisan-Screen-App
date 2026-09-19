package fabscreen.platform.base.obico;

import org.junit.Test;

import java.util.Arrays;

import fabscreen.platform.base.service.machine.Vector;
import fabscreen.platform.base.service.machine.entity.parts.Extruder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ObicoNativeCommandExecutorTest {
    @Test
    public void relativeJogBuildsAnXyzOnlyAbsoluteTarget() {
        Vector current = new Vector(10.0f, 20.0f, 30.0f, 45.0f, 90.0f);

        Vector x = ObicoNativeCommandExecutor.buildJogTarget(current, "x", -2.5d);
        assertEquals(7.5f, x.getX(), 0.0001f);
        assertEquals(20.0f, x.getY(), 0.0001f);
        assertEquals(30.0f, x.getZ(), 0.0001f);
        assertTrue(x.isxChange());
        assertTrue(x.isyChange());
        assertTrue(x.iszChange());
        assertFalse(x.isbChange());
        assertFalse(x.isx2Change());

        Vector z = ObicoNativeCommandExecutor.buildJogTarget(current, "z", 10.0d);
        assertEquals(10.0f, z.getX(), 0.0001f);
        assertEquals(20.0f, z.getY(), 0.0001f);
        assertEquals(40.0f, z.getZ(), 0.0001f);
    }

    @Test(expected = RuntimeException.class)
    public void relativeJogRejectsUnsupportedAxis() {
        ObicoNativeCommandExecutor.buildJogTarget(new Vector(), "b", 10.0d);
    }

    @Test
    public void nozzleAliasesAreStrict() {
        assertEquals(0, ObicoNativeCommandExecutor.nozzleIndex("tool0"));
        assertEquals(0, ObicoNativeCommandExecutor.nozzleIndex("extruder0"));
        assertEquals(1, ObicoNativeCommandExecutor.nozzleIndex("tool1"));
        assertEquals(1, ObicoNativeCommandExecutor.nozzleIndex("extruder1"));
        assertEquals(-1, ObicoNativeCommandExecutor.nozzleIndex("tool2"));
        assertEquals(-1, ObicoNativeCommandExecutor.nozzleIndex("extruder"));
    }

    @Test
    public void homeRequiresTheKnownAllAxisFirmwareForm() {
        assertTrue(ObicoNativeCommandExecutor.isWholeMachineHome(
                Arrays.asList("x", "y", "z")));
        assertTrue(ObicoNativeCommandExecutor.isWholeMachineHome(
                Arrays.asList("z", "x", "y")));
        assertFalse(ObicoNativeCommandExecutor.isWholeMachineHome(
                Arrays.asList("x", "y")));
        assertFalse(ObicoNativeCommandExecutor.isWholeMachineHome(
                Arrays.asList("x", "x", "z")));
    }

    @Test
    public void extrusionRouteHonorsTheRequestedT0OrT1() {
        Extruder t0 = extruder(0, 1, 205, 205);
        Extruder t1 = extruder(1, 0, 205, 205);

        assertEquals(
                ObicoNativeCommandExecutor.ExtrusionRoute.ALREADY_ACTIVE,
                ObicoNativeCommandExecutor.extrusionRoute(Arrays.asList(t0, t1), 0));
        assertEquals(
                ObicoNativeCommandExecutor.ExtrusionRoute.SWITCH_REQUIRED,
                ObicoNativeCommandExecutor.extrusionRoute(Arrays.asList(t0, t1), 1));
        assertEquals(
                ObicoNativeCommandExecutor.ExtrusionRoute.UNAVAILABLE,
                ObicoNativeCommandExecutor.extrusionRoute(Arrays.asList(t0, t1), 2));

        t0.setState(0);
        t1.setState(1);
        assertEquals(
                ObicoNativeCommandExecutor.ExtrusionRoute.ALREADY_ACTIVE,
                ObicoNativeCommandExecutor.extrusionRoute(Arrays.asList(t0, t1), 1));
        assertEquals(
                ObicoNativeCommandExecutor.ExtrusionRoute.SWITCH_REQUIRED,
                ObicoNativeCommandExecutor.extrusionRoute(Arrays.asList(t0, t1), 0));
    }

    @Test
    public void extrusionTemperatureIsValidatedAfterRouting() {
        Extruder requested = extruder(1, 1, 198, 200);
        assertTrue(ObicoNativeCommandExecutor.isExtrusionTemperatureReady(requested));

        requested.setTemperature(196.9f);
        assertFalse(ObicoNativeCommandExecutor.isExtrusionTemperatureReady(requested));

        requested.setTemperature(200f);
        requested.setTargetTemperature(0f);
        assertFalse(ObicoNativeCommandExecutor.isExtrusionTemperatureReady(requested));

        requested.setTargetTemperature(Float.NaN);
        assertFalse(ObicoNativeCommandExecutor.isExtrusionTemperatureReady(requested));
    }

    private static Extruder extruder(int id, int state, int temperature, int target) {
        return new Extruder(id, state, 0.4f, temperature, target);
    }
}
