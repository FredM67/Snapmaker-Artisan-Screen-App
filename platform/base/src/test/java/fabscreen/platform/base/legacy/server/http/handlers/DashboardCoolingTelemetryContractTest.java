package fabscreen.platform.base.legacy.server.http.handlers;

import org.junit.Test;

import fabscreen.platform.base.service.machine.entity.Module;

import static org.junit.Assert.assertEquals;

public class DashboardCoolingTelemetryContractTest {
    @Test
    public void mapsArtisanDualExtruderFanChannels() {
        int headType = Module.ModuleType.HEAD_3DP_DOUBLE_EXTRUDER;

        assertFan(
                headType,
                0,
                "partCoolingLeft",
                "partCooling",
                "left",
                "Left Part-cooling Fan"
        );
        assertFan(
                headType,
                1,
                "partCoolingRight",
                "partCooling",
                "right",
                "Right Part-cooling Fan"
        );
        assertFan(
                headType,
                2,
                "toolheadHeatDissipation",
                "heatDissipation",
                "",
                "Heat Dissipation Fan"
        );
    }

    @Test
    public void mapsSingleExtruderAndFallsBackWithoutInventingSemantics() {
        int single = Module.ModuleType.HEAD_3DP;
        assertFan(single, 0, "partCooling", "partCooling", "", "Part-cooling Fan");
        assertFan(
                single,
                1,
                "toolheadHeatDissipation",
                "heatDissipation",
                "",
                "Heat Dissipation Fan"
        );

        assertFan(99, 3, "toolheadFan3", "other", "", "Fan 4");
    }

    private void assertFan(
            int headType,
            int id,
            String seriesKey,
            String kind,
            String side,
            String name
    ) {
        assertEquals(seriesKey, OrcaRequestHandler.fanSeriesKey(headType, id));
        assertEquals(kind, OrcaRequestHandler.fanKind(headType, id));
        assertEquals(side, OrcaRequestHandler.fanSide(headType, id));
        assertEquals(name, OrcaRequestHandler.fanName(headType, id));
    }
}
