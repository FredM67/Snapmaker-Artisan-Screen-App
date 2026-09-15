package fabscreen.platform.base.lib.parser;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import fabscreen.platform.base.model.ModelBoundary;
import fabscreen.platform.base.service.IMachine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class GcodeParserBoundaryTest {
    @Test
    public void artisanOrcaStartupPurgeIsExcludedFromPrintableObjectBoundary() {
        String gcode = ";TYPE:Custom\n"
                + "M83\n"
                + "G0 X0 Y-0.5 Z0.2\n"
                + "G1 X140 E8.73079\n"
                + "G1 E-0.8\n"
                + "G0 Y20\n"
                + ";LAYER_CHANGE\n"
                + ";Z:0.2\n"
                + "G1 X196.585 Y202.112 Z0.2\n"
                + "G1 X196.694 Y202.063 E0.00444\n"
                + "G1 X197.066 Y202.275 E0.00450\n"
                + "; stop printing object 3dbenchy.stl id:15 copy 0\n"
                + ";TYPE:Custom\n"
                + "G1 X450 Y450 E2\n";

        ModelBoundary boundary = parseBoundary(gcode);

        assertTrue(boundary.getMinX() >= 0f);
        assertTrue(boundary.getMinY() >= 0f);
        assertTrue(boundary.getMinZ() >= 0f);
        assertTrue(boundary.getMaxX() <= 400f);
        assertTrue(boundary.getMaxY() <= 400f);
        assertTrue(boundary.getMaxZ() <= 400f);
        assertEquals(196.585f, boundary.getMinX(), 0.001f);
        assertEquals(202.063f, boundary.getMinY(), 0.001f);
    }

    @Test
    public void outOfVolumeObjectExtrusionRemainsVisibleToSafetyValidation() {
        String gcode = "M83\n"
                + ";LAYER_CHANGE\n"
                + "G0 X399 Y10 Z0.2\n"
                + "G1 X401 Y10 E1\n";

        ModelBoundary boundary = parseBoundary(gcode);

        assertEquals(401f, boundary.getMaxX(), 0.001f);
        assertTrue(boundary.getMaxX() > 400f);
    }

    private ModelBoundary parseBoundary(String gcode) {
        GcodeParser parser = new GcodeParser();
        parser.startParse(
                new ByteArrayInputStream(gcode.getBytes(StandardCharsets.UTF_8)),
                IMachine.WorkType.FDM
        );
        int result = parser.getParseProgressObservable()
                .filter(progress -> progress == 100 || progress < 0)
                .blockingFirst();
        assertEquals(100, result);
        ModelBoundary boundary = parser.getBoundary();
        parser.destroy();
        return boundary;
    }
}
