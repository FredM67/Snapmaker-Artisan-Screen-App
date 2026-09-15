package fabscreen.platform.base.lib.parser;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class OrcaMetadataCommentParserTest {
    @Test
    public void parsesOrcaFooterMetadata() {
        OrcaMetadataCommentParser parser = new OrcaMetadataCommentParser();
        parser.consumeLine("; total layer number: 300");
        parser.consumeLine("; filament used [mm] = 4121.84, 0.00");
        parser.consumeLine("; filament used [g] = 12.29, 0.00");
        parser.consumeLine("; estimated printing time (normal mode) = 1h 19m 1s");
        parser.consumeLine("; filament_type = PLA;PLA");
        parser.consumeLine("; layer_height = 0.16");
        parser.consumeLine("; nozzle_diameter = 0.4,0.4");
        parser.consumeLine("; nozzle_temperature = 220,220");
        parser.consumeLine("; first_layer_bed_temperature = 70");

        OrcaMetadataCommentParser.Result result = parser.getResult();
        assertEquals(Integer.valueOf(300), result.layerCount);
        assertEquals(4.12184f, result.materialLengthMeters, 0.00001f);
        assertEquals(12.29f, result.materialWeightGrams, 0.00001f);
        assertEquals(4741f, result.estimatedTimeSeconds, 0.001f);
        assertEquals("PLA", result.materialLeft);
        assertEquals("PLA", result.materialRight);
        assertEquals(0.16f, result.layerHeightMm, 0.00001f);
        assertEquals(0.4f, result.nozzleDiameterLeftMm, 0.00001f);
        assertEquals(0.4f, result.nozzleDiameterRightMm, 0.00001f);
        assertEquals(220f, result.nozzleTemperatureLeftC, 0.001f);
        assertEquals(220f, result.nozzleTemperatureRightC, 0.001f);
        assertEquals(70f, result.bedTemperatureC, 0.001f);
    }

    @Test
    public void malformedOptionalValuesAreIgnoredAndResetClearsState() {
        OrcaMetadataCommentParser parser = new OrcaMetadataCommentParser();
        parser.consumeLine("; layer_height = not-a-number");
        parser.consumeLine("; total layer number: 20");
        parser.reset();

        assertNull(parser.getResult().layerHeightMm);
        assertNull(parser.getResult().layerCount);
    }

    @Test
    public void similarlyPrefixedTemperatureSettingsDoNotOverwriteExactNozzleTarget() {
        OrcaMetadataCommentParser parser = new OrcaMetadataCommentParser();
        parser.consumeLine("; nozzle_temperature = 220,220");
        parser.consumeLine("; nozzle_temperature_initial_layer = 225,225");
        parser.consumeLine("; nozzle_temperature_range_high = 290,290");
        parser.consumeLine("; nozzle_temperature_range_low = 190,190");

        OrcaMetadataCommentParser.Result result = parser.getResult();
        assertEquals(220f, result.nozzleTemperatureLeftC, 0.001f);
        assertEquals(220f, result.nozzleTemperatureRightC, 0.001f);
    }

    @Test
    public void parsesPlateToolUsageDeclaration() {
        OrcaMetadataCommentParser parser = new OrcaMetadataCommentParser();
        parser.consumeLine("; Plate   : large part");
        parser.consumeLine("; --- initial_extruder: 1");
        parser.consumeLine("; --- has_wipe_tower: 0");
        parser.consumeLine("; --- total_toolchanges: 0");
        parser.consumeLine("; --- T0: false");
        parser.consumeLine("; --- T1: TRUE");

        OrcaMetadataCommentParser.Result result = parser.getResult();
        assertEquals(Integer.valueOf(1), result.initialExtruder);
        assertEquals(Boolean.FALSE, result.hasWipeTower);
        assertEquals(Integer.valueOf(0), result.totalToolChanges);
        assertEquals(Boolean.FALSE, result.plateTool0Used);
        assertEquals(Boolean.TRUE, result.plateTool1Used);
        assertFalse(result.plateToolUsageConflict);
    }

    @Test
    public void contradictoryPlateFlagsFailClosedAndResetClearsThem() {
        OrcaMetadataCommentParser parser = new OrcaMetadataCommentParser();
        parser.consumeLine("; --- T0: false");
        parser.consumeLine("; --- T0: true");

        assertEquals(Boolean.TRUE, parser.getResult().plateTool0Used);
        assertTrue(parser.getResult().plateToolUsageConflict);

        parser.reset();
        assertNull(parser.getResult().plateTool0Used);
        assertFalse(parser.getResult().plateToolUsageConflict);
    }
}
