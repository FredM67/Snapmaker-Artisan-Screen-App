package fabscreen.platform.base.legacy.server.http.handlers;

import org.junit.Test;

import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DashboardBedMeshParserTest {
    @Test
    public void parsesCompensatedArtisanGrid() {
        String response = "Raw Bilinear Leveling Grid:\r\n"
                + "        0      1      2\r\n"
                + " 0 +0.110 -0.020 +0.030\r\n"
                + " 1 +0.040 +0.050 +0.060\r\n"
                + " 2 +0.070 +0.080 +0.090\r\n\r\n"
                + "compensated Bilinear Leveling Grid:\r\n"
                + "        0      1      2\r\n"
                + " 0 -0.100 +0.000 +0.100\r\n"
                + " 1 -0.050 +0.020 +0.120\r\n"
                + " 2 -0.025 +0.050 +0.150\r\n\r\n"
                + "echo:Bed Leveling ON\r\n";

        DashboardBedMeshParser.Result result = DashboardBedMeshParser.parse(response);

        assertTrue(result.available);
        assertEquals("compensated", result.source);
        assertEquals(3, result.values.size());
        assertEquals(3, result.values.get(0).size());
        assertEquals(7, result.surfaceValues.size());
        assertEquals(7, result.surfaceValues.get(0).size());
        assertEquals("derived", result.surfaceSource);
        assertEquals("compensated", result.surfaceDerivedFrom);
        assertEquals("catmull-rom", result.surfaceInterpolation);
        assertEquals(3, result.surfaceSubdivisions);
        assertEquals(-0.100, result.minimum, 0.0001);
        assertEquals(0.150, result.maximum, 0.0001);
        assertEquals(Boolean.TRUE, result.levelingActive);
    }

    @Test
    public void rejectsInvalidMesh() {
        DashboardBedMeshParser.Result result = DashboardBedMeshParser.parse(
                "echo:Invalid mesh.\necho:Bed Leveling OFF\n"
        );

        assertFalse(result.available);
        assertTrue(result.message.contains("valid bed mesh"));
    }

    @Test
    public void rejectsPartialCompensatedGrid() {
        DashboardBedMeshParser.Result result = DashboardBedMeshParser.parse(
                "compensated Bilinear Leveling Grid:\n"
                        + "       0      1      2\n"
                        + " 0 +0.100 +0.200 +0.300\n"
                        + " 1 +0.400 +0.500 +0.600\n"
        );

        assertFalse(result.available);
        assertTrue(result.message.contains("complete mesh"));
    }

    @Test
    public void doesNotCrossIntoSubdividedGridWhenCompensatedGridIsIncomplete() {
        DashboardBedMeshParser.Result result = DashboardBedMeshParser.parse(
                "compensated Bilinear Leveling Grid:\n"
                        + "Subdivided with CATMULL ROM Leveling Grid:\n"
                        + "       0      1      2      3      4      5      6\n"
                        + " 0 +0.1 +0.1 +0.1 +0.1 +0.1 +0.1 +0.1\n"
                        + " 1 +0.1 +0.1 +0.1 +0.1 +0.1 +0.1 +0.1\n"
                        + " 2 +0.1 +0.1 +0.1 +0.1 +0.1 +0.1 +0.1\n"
                        + " 3 +0.1 +0.1 +0.1 +0.1 +0.1 +0.1 +0.1\n"
                        + " 4 +0.1 +0.1 +0.1 +0.1 +0.1 +0.1 +0.1\n"
                        + " 5 +0.1 +0.1 +0.1 +0.1 +0.1 +0.1 +0.1\n"
                        + " 6 +0.1 +0.1 +0.1 +0.1 +0.1 +0.1 +0.1\n"
        );

        assertFalse(result.available);
        assertTrue(result.message.contains("complete mesh"));
    }

    @Test
    public void derivesTwentyFiveByTwentyFiveSurfaceFromArtisanGrid() {
        double[][] grid = new double[9][9];
        for (int y = 0; y < grid.length; y++) {
            for (int x = 0; x < grid[y].length; x++) {
                grid[y][x] = y * 10.0 + x;
            }
        }

        DashboardBedMeshParser.Result result = DashboardBedMeshParser.parse(
                gridResponse("compensated Bilinear Leveling Grid:", grid)
        );

        assertTrue(result.available);
        assertEquals(9, result.values.size());
        assertEquals(9, result.values.get(0).size());
        assertEquals(25, result.surfaceValues.size());
        assertEquals(25, result.surfaceValues.get(0).size());
        assertEquals(0.0, result.surfaceValues.get(0).get(0), 0.0000001);
        assertEquals(88.0, result.surfaceValues.get(24).get(24), 0.0000001);
        assertEquals(11.0, result.surfaceValues.get(3).get(3), 0.0000001);
        assertEquals(11.0 / 3.0, result.surfaceValues.get(1).get(1), 0.0000001);
        assertEquals(4.0, result.surfaceValues.get(1).get(2), 0.0000001);
        assertEquals(7.0, result.surfaceValues.get(2).get(1), 0.0000001);
    }

    @Test
    public void matchesFirmwareCatmullRomPolynomialAndEdgeExtrapolation() {
        double[][] impulse = {
                {0.0, 0.0, 0.0},
                {0.0, 1.0, 0.0},
                {0.0, 0.0, 0.0}
        };

        DashboardBedMeshParser.Result result = DashboardBedMeshParser.parse(
                gridResponse("compensated Bilinear Leveling Grid:", impulse)
        );

        assertTrue(result.available);
        assertEquals(7, result.surfaceValues.size());
        // These exact fractions exercise Marlin's linear edge extrapolation
        // and its bed_level_virt_cmr polynomial at t=1/3 and t=2/3.
        assertEquals(121.0 / 729.0, result.surfaceValues.get(1).get(1), 0.000000001);
        assertEquals(484.0 / 729.0, result.surfaceValues.get(2).get(2), 0.000000001);
        assertEquals(1.0, result.surfaceValues.get(3).get(3), 0.000000001);
    }

    @Test
    public void ignoresTruncatedFirmwareSurfaceAfterCompleteCompensatedGrid() {
        double[][] grid = {
                {0.0, 0.1, 0.2},
                {0.3, 0.4, 0.5},
                {0.6, 0.7, 0.8}
        };
        String response = gridResponse("compensated Bilinear Leveling Grid:", grid)
                + "Subdivided with CATMULL ROM Leveling Grid:\n"
                + "0 1 2 3 4 5 6\n"
                + "0 +0.00000 +0.01000 +0.02000 +0.03000 +0.04000 +0.05000 +0.06000\n";

        DashboardBedMeshParser.Result result = DashboardBedMeshParser.parse(response);

        assertTrue(result.available);
        assertEquals("compensated", result.source);
        assertEquals(3, result.values.size());
        assertEquals(7, result.surfaceValues.size());
        assertEquals(0.8, result.surfaceValues.get(6).get(6), 0.0000001);
    }

    @Test
    public void toleratesSacpNullFramingOnGridRows() {
        DashboardBedMeshParser.Result result = DashboardBedMeshParser.parse(
                "compensated Bilinear Leveling Grid:\n"
                        + " 0 1 2\u0000\n"
                        + "0 +0.100 +0.200 +0.300\u0000\n"
                        + "1 +0.400 +0.500 +0.600\u0000\n"
                        + "2 +0.700 +0.800 +0.900\u0000\n"
        );

        assertTrue(result.available);
        assertEquals("compensated", result.source);
        assertEquals(0.900, result.maximum, 0.0001);
    }

    @Test
    public void toleratesBlankLinesBetweenStreamedFirmwareRows() {
        DashboardBedMeshParser.Result result = DashboardBedMeshParser.parse(
                "compensated Bilinear Leveling Grid:\n\n"
                        + "0 1 2\n\n"
                        + "0 +0.100 +0.200 +0.300\n\n"
                        + "1 +0.400 +0.500 +0.600\n\n"
                        + "2 +0.700 +0.800 +0.900\n\n"
        );

        assertTrue(result.available);
        assertEquals(3, result.values.size());
    }

    private static String gridResponse(String marker, double[][] values) {
        StringBuilder response = new StringBuilder(marker).append('\n').append("   ");
        for (int column = 0; column < values[0].length; column++) {
            response.append(' ').append(column);
        }
        response.append('\n');
        for (int row = 0; row < values.length; row++) {
            response.append(row);
            for (int column = 0; column < values[row].length; column++) {
                response.append(' ').append(String.format(Locale.US, "%+.6f", values[row][column]));
            }
            response.append('\n');
        }
        return response.toString();
    }
}
