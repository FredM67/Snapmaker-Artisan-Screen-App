package fabscreen.platform.base.lib.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Assume;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;

import fabscreen.platform.base.model.ModelBoundary;
import fabscreen.platform.base.service.IMachine;

public class GcodeParserOrcaFastPathTest {
    @Test
    public void fastPathSkipsGenericExecutableParsingAndKeepsSafetyMetadata() {
        String gcode = validOrca("\n", false, true);
        GcodeParser parser = parse(gcode);

        assertEquals(GcodeParser.ParseMode.ORCA_FAST, parser.getLastParseModeForTest());
        assertTrue(parser.getLastExecutableSafetyLineCountForTest() > 5);
        assertEquals(physicalLines(gcode), parser.getTotalLinesCount());
        assertEquals(2, parser.getLayerNumber());
        assertEquals(3661f, parser.getEstimatedTime(), 0.001f);
        assertEquals(2.5f, parser.getMaterialLength(), 0.0001f);
        assertEquals(7.5f, parser.getMaterialWeight(), 0.0001f);
        assertEquals(0.2f, parser.getLayerHeight(), 0.0001f);
        assertEquals(0.4f, parser.getNozzle_0_Diameter(), 0.0001f);
        assertEquals(0.6f, parser.getNozzle_1_Diameter(), 0.0001f);
        assertEquals(215f, parser.getNozzleTargetTemperature(), 0.0001f);
        assertEquals(225f, parser.getNozzleTarget_1_Temperature(), 0.0001f);
        assertEquals(65f, parser.getBedTargetTemperature(), 0.0001f);
        assertEquals("PLA", parser.getMaterial_0());
        assertEquals("PETG", parser.getMaterial_1());
        assertEquals(0.8f, parser.getExtruder0RetractionDistance(), 0.0001f);
        assertEquals(0.8f, parser.getExtruder1RetractionDistance(), 0.0001f);
        assertTrue(parser.isToolUsageConfirmed());
        assertTrue(parser.isTool0Used());
        assertTrue(parser.isTool1Used());
        assertTrue(parser.isApplyMultiExtruder());
        assertEquals(2, parser.getCustomPrintMode());
        assertEquals(digest("SHA-256", gcode), parser.getLastSha256ForAnalysis());
        assertEquals(digest("MD5", gcode), parser.getLastMd5ForAnalysis());

        ModelBoundary boundary = parser.getBoundary();
        assertEquals(10f, boundary.getMinX(), 0.001f);
        // Compact G1 and an extruding G0 must both remain visible to the safety boundary.
        assertEquals(405f, boundary.getMaxX(), 0.001f);
        // The clockwise semicircle from X20 to X30 around X25 reaches Y15.
        assertEquals(10f, boundary.getMinY(), 0.001f);
        assertEquals(15f, boundary.getMaxY(), 0.001f);
        parser.destroy();
    }

    @Test
    public void observedRetractionCannotBeHiddenBySlightlySmallerConfigValue() {
        String gcode = validOrca("\n", false, false)
                .replace("G1E-0.8", "G1E-2.3")
                .replace("; retraction_length = 0.8,0.8",
                        "; retraction_length = 1.9,0.8");
        GcodeParser parser = parse(gcode);

        assertEquals(GcodeParser.ParseMode.ORCA_FAST, parser.getLastParseModeForTest());
        assertEquals(2.3f, parser.getExtruder0RetractionDistance(), 0.0001f);
        parser.destroy();
    }

    @Test
    public void singleToolPlateDeclarationHidesUnusedSideAfterCompleteScan() {
        String gcode = validOrca("\n", false, false)
                .replace("M605 S2", "M605 S0");
        GcodeParser parser = parse(gcode);

        assertEquals(GcodeParser.ParseMode.ORCA_FAST, parser.getLastParseModeForTest());
        assertTrue(parser.isToolUsageConfirmed());
        assertTrue(parser.isTool0Used());
        assertFalse(parser.isTool1Used());
        assertFalse(parser.isApplyMultiExtruder());
        assertEquals(0.8f, parser.getExtruder0RetractionDistance(), 0.0001f);
        assertEquals(0f, parser.getExtruder1RetractionDistance(), 0.0001f);
        parser.destroy();
    }

    @Test
    public void rightOnlyJobKeepsRightTemperatureAndMaterialIndex() {
        String gcode = validOrca("\n", false, false)
                .replace("M605 S2", "M605 S0")
                .replace("; --- initial_extruder: 0", "; --- initial_extruder: 1")
                .replace("; --- T0: true", "; --- T0: false")
                .replace("; --- T1: false", "; --- T1: true")
                .replace("\nT0\nG28", "\nT1\nG28");
        GcodeParser parser = parse(gcode);

        assertEquals(GcodeParser.ParseMode.ORCA_FAST, parser.getLastParseModeForTest());
        assertFalse(parser.isTool0Used());
        assertTrue(parser.isTool1Used());
        assertFalse(parser.isApplyMultiExtruder());
        // The right-only target remains array index 1; it must never be remapped to left.
        assertEquals(215f, parser.getNozzleTargetTemperature(), 0.0001f);
        assertEquals(225f, parser.getNozzleTarget_1_Temperature(), 0.0001f);
        assertEquals("PLA", parser.getMaterial_0());
        assertEquals("PETG", parser.getMaterial_1());
        assertEquals(0f, parser.getExtruder0RetractionDistance(), 0.0001f);
        assertEquals(0.8f, parser.getExtruder1RetractionDistance(), 0.0001f);
        parser.destroy();
    }

    @Test
    public void toolSelectionWithoutPositiveExtrusionDoesNotCountAsUse() {
        String gcode = validOrca("\n", false, false)
                .replace("M605 S2", "M605 S0")
                .replace("; executable remains valid", "T1\nT0");
        GcodeParser parser = parse(gcode);

        assertEquals(GcodeParser.ParseMode.ORCA_FAST, parser.getLastParseModeForTest());
        assertTrue(parser.isTool0Used());
        assertFalse(parser.isTool1Used());
        parser.destroy();
    }

    @Test
    public void startupPurgeOutsideObjectMarkersCountsAsWholeJobUse() {
        String gcode = validOrca("\n", false, false)
                .replace("M605 S2", "M605 S0")
                .replace("; --- T1: false", "; --- T1: true")
                .replace(";LAYER_CHANGE", "T1\nG0 X5 Y-1 Z0.2\nG1 X10 E1\nT0\n;LAYER_CHANGE");
        GcodeParser parser = parse(gcode);

        assertEquals(GcodeParser.ParseMode.ORCA_FAST, parser.getLastParseModeForTest());
        assertTrue(parser.isTool0Used());
        assertTrue(parser.isTool1Used());
        parser.destroy();
    }

    @Test
    public void plateContradictionFallsBackAndNeverHidesDeclaredTool() {
        String gcode = validOrca("\n", false, false)
                .replace("M605 S2", "M605 S0")
                .replace("; --- T1: false", "; --- T1: true");
        GcodeParser parser = parse(gcode);

        assertEquals(GcodeParser.ParseMode.ORCA_FALLBACK, parser.getLastParseModeForTest());
        assertTrue("fallback should inspect the complete executable",
                parser.isToolUsageConfirmed());
        assertTrue(parser.isTool0Used());
        assertTrue(parser.isTool1Used());
        assertEquals(digest("SHA-256", gcode), parser.getLastSha256ForAnalysis());
        assertEquals(digest("MD5", gcode), parser.getLastMd5ForAnalysis());
        parser.destroy();
    }

    @Test
    public void arcToolUseSurvivesFallbackWhenPlateIncorrectlySaysUnused() {
        for (String arc : new String[]{"G2", "G3"}) {
            String gcode = validOrca("\n", false, false)
                    .replace("M605 S2", "M605 S0")
                    .replace("G2X30Y10I5J0E1",
                            "T1\nG0X20Y10Z0.2\n" + arc
                                    + "X30Y10I5J0E1\nT0\nG0X30Y10Z0.2");
            // Keep the producer declaration exactly false: executable evidence must win.
            assertTrue(gcode.contains("; --- T1: false"));
            GcodeParser parser = parse(gcode);

            assertEquals(GcodeParser.ParseMode.ORCA_FALLBACK,
                    parser.getLastParseModeForTest());
            assertTrue(parser.isToolUsageConfirmed());
            assertTrue(parser.isTool0Used());
            assertTrue("physical T1 use from " + arc + " must survive fallback",
                    parser.isTool1Used());
            parser.destroy();
        }
    }

    @Test
    public void unmodelledExtrusionFormsNeverConfirmAnUnusedNozzle() {
        String base = validOrca("\n", false, false).replace("M605 S2", "M605 S0");
        String[] unmodelled = new String[]{
                base.replace("; executable remains valid",
                        "T1\nG5 X12 Y12 I1 J1 P1 Q1 E1\nT0"),
                base.replace("; executable remains valid",
                        "T1\nG92 E0\nX12 Y12 E1\nT0"),
                base.replace("; executable remains valid",
                        "N10 T1*0\nN20 G5 X12 Y12 I1 J1 P1 Q1 E1*0\nN30 T0*0")
        };

        for (String gcode : unmodelled) {
            assertTrue(gcode.contains("; --- T1: false"));
            GcodeParser parser = parse(gcode);

            assertEquals(GcodeParser.ParseMode.ORCA_FALLBACK,
                    parser.getLastParseModeForTest());
            assertFalse("an incomplete executable model cannot prove T1 unused",
                    parser.isToolUsageConfirmed());
            parser.destroy();
        }
    }

    @Test
    public void missingOrMalformedPlateFlagsUseExecutableEvidence() {
        String base = validOrca("\n", false, false).replace("M605 S2", "M605 S0");
        String[] variants = new String[]{
                base.replace("; --- T0: true\n; --- T1: false\n", ""),
                base.replace("; --- T0: true", "; --- T0: maybe")
                        .replace("; --- T1: false", "; --- T1: unknown")
        };
        for (String gcode : variants) {
            GcodeParser parser = parse(gcode);
            assertEquals(GcodeParser.ParseMode.ORCA_FAST, parser.getLastParseModeForTest());
            assertTrue(parser.isTool0Used());
            assertFalse(parser.isTool1Used());
            parser.destroy();
        }
    }

    @Test
    public void backupCloneAndMirrorMakeBothPhysicalNozzlesOperational() {
        for (int mode = 1; mode <= 3; mode++) {
            GcodeParser parser = parse(validOrca("\n", false, false)
                    .replace("M605 S2", "M605 S" + mode));
            assertEquals(GcodeParser.ParseMode.ORCA_FAST, parser.getLastParseModeForTest());
            assertTrue("left tool in mode " + mode, parser.isTool0Used());
            assertTrue("right tool in mode " + mode, parser.isTool1Used());
            assertTrue(parser.isApplyMultiExtruder());
            parser.destroy();
        }
    }

    @Test
    public void physicalDualModeUseStaysStickyAfterReturningToNormalMode() {
        for (int mode = 1; mode <= 3; mode++) {
            String fastGcode = validOrca("\n", false, false)
                    .replace("M605 S2", "M605 S" + mode)
                    .replace("G1X140E8", "G1X140E8\nM605 S0");
            GcodeParser fastParser = parse(fastGcode);
            assertEquals(GcodeParser.ParseMode.ORCA_FAST,
                    fastParser.getLastParseModeForTest());
            assertEquals(0, fastParser.getCustomPrintMode());
            assertTrue(fastParser.isTool0Used());
            assertTrue("fast physical right use in mode " + mode,
                    fastParser.isTool1Used());
            fastParser.destroy();

            String fallbackGcode = fastGcode.replace(
                    "; estimated printing time (normal mode) = 1h 1m 1s\n", "");
            GcodeParser fallbackParser = parse(fallbackGcode);
            assertEquals(GcodeParser.ParseMode.ORCA_FALLBACK,
                    fallbackParser.getLastParseModeForTest());
            assertEquals(0, fallbackParser.getCustomPrintMode());
            assertTrue(fallbackParser.isTool0Used());
            assertTrue("legacy physical right use in mode " + mode,
                    fallbackParser.isTool1Used());
            fallbackParser.destroy();
        }
    }

    @Test
    public void g90AndG91AlsoChangeExtruderModeLikeMarlin() {
        String gcode = validOrca("\n", false, false).replace(
                "G1X20Y10E1",
                "M82\nG92 E0\nG91\nG1X10E0.1\nG1X500E0.1\n"
                        + "G90\nM83\nG1X20Y10E1");
        GcodeParser parser = parse(gcode);

        assertEquals(GcodeParser.ParseMode.ORCA_FAST, parser.getLastParseModeForTest());
        assertEquals(520f, parser.getBoundary().getMaxX(), 0.001f);
        parser.destroy();
    }

    @Test
    public void absoluteExtrusionCoordinateIsGlobalAcrossToolChanges() {
        String gcode = validOrca("\n", false, false).replace(
                "G1X20Y10E1",
                "M82\nG92 E0\nT0\nG1 E100\nT1\nG92 E0\n"
                        + "G0 X10 Y10 Z0.2\nG1 E1\nT0\nG0 X10 Y10 Z0.2\n"
                        + "G1 X500 Y10 Z0.2 E2\n"
                        + "M83\nG1X20Y10E1")
                .replace("; --- T1: false", "; --- T1: true");
        GcodeParser parser = parse(gcode);

        assertEquals(GcodeParser.ParseMode.ORCA_FAST, parser.getLastParseModeForTest());
        assertEquals(500f, parser.getBoundary().getMaxX(), 0.001f);
        parser.destroy();
    }

    @Test
    public void positiveArcExtrusionRequiresExplicitToolSelection() {
        String gcode = validOrca("\n", false, false)
                .replace("\nT0\nG28", "\nG28")
                .replace("G1X140E8", "G0X140")
                .replace("G1X20Y10E1", "G0X20Y10")
                .replace("G1X401Y10E1", "G0X401Y10")
                .replace("G0X405Y10E1", "G0X405Y10")
                .replace("G1E-0.8", "G1 F100");
        GcodeParser parser = parse(gcode);

        assertEquals(GcodeParser.ParseMode.ORCA_FALLBACK,
                parser.getLastParseModeForTest());
        parser.destroy();
    }

    @Test
    public void physicalLineCountIsExactForCrLfBlankUtf8AndNoFinalNewline() {
        String gcode = validOrca("\r\n", false, false)
                .replace("; HEADER_BLOCK_END\r\n", "; note: café\r\n\r\n; HEADER_BLOCK_END\r\n");
        GcodeParser parser = parse(gcode);

        assertEquals(GcodeParser.ParseMode.ORCA_FAST, parser.getLastParseModeForTest());
        assertEquals(physicalLines(gcode), parser.getTotalLinesCount());
        parser.destroy();
    }

    @Test
    public void malformedOrcaBlocksFallBackToLegacyParser() {
        String[] malformed = new String[]{
                validOrca("\n", true, false).replace("; CONFIG_BLOCK_END\n", ""),
                validOrca("\n", true, false).replace(
                        "; THUMBNAIL_BLOCK_END\n\n; EXECUTABLE_BLOCK_START",
                        "; EXECUTABLE_BLOCK_START\n; THUMBNAIL_BLOCK_END"),
                validOrca("\n", true, false).replace(
                        "; HEADER_BLOCK_END\n", "; HEADER_BLOCK_END\nG1 X999 Y10 E1\n"),
                validOrca("\n", true, false).replace(
                        "; THUMBNAIL_BLOCK_END\n", "; THUMBNAIL_BLOCK_END\nG1 X999 Y10 E1\n"),
                validOrca("\n", true, false).replace(
                        "; CONFIG_BLOCK_START\n", "; CONFIG_BLOCK_START\nG1 X999 Y10 E1\n"),
                validOrca("\n", true, false).replace(
                        "; CONFIG_BLOCK_END\n", "; CONFIG_BLOCK_END\nG1 X999 Y10 E1\n")
        };

        for (String gcode : malformed) {
            GcodeParser parser = parse(gcode);
            assertEquals(GcodeParser.ParseMode.ORCA_FALLBACK,
                    parser.getLastParseModeForTest());
            parser.destroy();
        }
    }

    @Test
    public void missingOrUntrustedSafetyMetadataFallsBack() {
        String[] unsafe = new String[]{
                validOrca("\n", true, false).replace(
                        "; retraction_length = 0.8,0.8\n", ""),
                validOrca("\n", true, false).replace(
                        "; retraction_length = 0.8,0.8",
                        "; retraction_length = NaN,0.8"),
                validOrca("\n", true, false).replace(";LAYER_CHANGE", ";TYPE:Custom"),
                validOrca("\n", true, false).replace(
                        "; executable remains valid", "X999Y10E1"),
                validOrca("\n", true, false).replace(
                        "G1X20Y10E1", "G28\nG91\nG1X1E1\nG90\nG1X20Y10E1"),
                validOrca("\n", true, false).replace(
                        "; executable remains valid", "G60\nG61"),
                validOrca("\n", true, false).replace(
                        "G1X20Y10E1", "T1\nG1 X500 E1\nT0\nG1X20Y10E1"),
                validOrca("\n", true, false).replace(
                        "; executable remains valid",
                        "G90\nG2 X100\nG91\nG1 X-1 E1"),
                validOrca("\n", true, false).replace("G1E-0.8", "G1E-10"),
                validOrca("\n", true, false).replace("G28\nG90", "G91"),
                validOrca("\n", true, false).replace("\nM83\n", "\nM82\n"),
                validOrca("\n", true, false).replace("\nT0\nG28", "\nG28"),
                validOrca("\n", true, false).replace("\nG90\n", "\n"),
                validOrca("\n", true, false).replace("\nG21\n", "\n")
        };

        for (String gcode : unsafe) {
            GcodeParser parser = parse(gcode);
            assertEquals(GcodeParser.ParseMode.ORCA_FALLBACK,
                    parser.getLastParseModeForTest());
            parser.destroy();
        }
    }

    @Test
    public void lubanHeaderStillUsesLegacyEarlyExit() {
        String luban = ";Header Start\n"
                + ";file_total_lines:123\n"
                + ";Extruder 0 Retraction Distance:0.8\n"
                + ";Header End\n"
                + "M104 Snot-a-number\n";
        GcodeParser parser = parse(luban);

        assertEquals(GcodeParser.ParseMode.LEGACY, parser.getLastParseModeForTest());
        assertEquals(123, parser.getTotalLinesCount());
        assertEquals(0.8f, parser.getExtruder0RetractionDistance(), 0.0001f);
        assertFalse(parser.isToolUsageConfirmed());
        assertEquals(digest("SHA-256", luban), parser.getLastSha256ForAnalysis());
        assertEquals(digest("MD5", luban), parser.getLastMd5ForAnalysis());
        parser.destroy();
    }

    @Test
    public void nonReplayableInputStreamSafelyUsesLegacyParser() {
        String gcode = validOrca("\n", false, false);
        InputStream stream = new NonReplayableInputStream(
                gcode.getBytes(StandardCharsets.UTF_8));
        GcodeParser parser = new GcodeParser();
        parser.startParse(stream, IMachine.WorkType.FDM);
        int result = parser.getParseProgressObservable()
                .filter(progress -> progress == 100 || progress < 0)
                .timeout(10, TimeUnit.SECONDS)
                .blockingFirst();

        assertEquals(100, result);
        assertEquals(GcodeParser.ParseMode.LEGACY, parser.getLastParseModeForTest());
        assertEquals(physicalLines(gcode), parser.getTotalLinesCount());
        parser.destroy();
    }

    @Test
    public void supersededParseClosesItsOwnedInput() {
        TrackingByteArrayInputStream first = new TrackingByteArrayInputStream(
                validOrca("\n", true, false).getBytes(StandardCharsets.UTF_8));
        GcodeParser parser = new GcodeParser();
        parser.startParse(first, IMachine.WorkType.FDM);
        String second = validOrca("\n", true, false)
                .replace("1h 1m 1s", "2h 2m 2s")
                .replace("; HEADER_BLOCK_END", "; unique second parse\n; HEADER_BLOCK_END");
        parser.startParse(new ByteArrayInputStream(second.getBytes(StandardCharsets.UTF_8)),
                IMachine.WorkType.FDM);

        int result = parser.getParseProgressObservable()
                .filter(progress -> progress == 100 || progress < 0)
                .timeout(10, TimeUnit.SECONDS)
                .blockingFirst();
        assertEquals(100, result);
        assertTrue(first.closed);
        assertEquals(physicalLines(second), parser.getTotalLinesCount());
        assertEquals(7322f, parser.getEstimatedTime(), 0.001f);
        parser.destroy();
    }

    @Test
    public void optionalRealFixtureUsesFastModeAndFullObjectBounds() throws IOException {
        String fixturePath = System.getenv("ORCA_GCODE_FIXTURE");
        Assume.assumeTrue(fixturePath != null && !fixturePath.trim().isEmpty());
        File fixture = new File(fixturePath);
        Assume.assumeTrue(fixture.isFile());

        GcodeParser parser = new GcodeParser();
        parser.startParse(new FileInputStream(fixture), IMachine.WorkType.FDM);
        int result = parser.getParseProgressObservable()
                .filter(progress -> progress == 100 || progress < 0)
                .timeout(30, TimeUnit.SECONDS)
                .blockingFirst();

        assertEquals(100, result);
        assertEquals(GcodeParser.ParseMode.ORCA_FAST, parser.getLastParseModeForTest());
        assertEquals(211_548, parser.getTotalLinesCount());
        assertEquals(300, parser.getLayerNumber());
        assertEquals(4741f, parser.getEstimatedTime(), 0.001f);
        assertEquals(4.12184f, parser.getMaterialLength(), 0.00001f);
        assertEquals(12.29f, parser.getMaterialWeight(), 0.001f);
        assertEquals(0.16f, parser.getLayerHeight(), 0.0001f);
        assertEquals("PLA", parser.getMaterial_0());
        assertEquals("PLA", parser.getMaterial_1());
        assertEquals(0.4f, parser.getNozzle_0_Diameter(), 0.0001f);
        assertEquals(0.4f, parser.getNozzle_1_Diameter(), 0.0001f);
        assertEquals(220f, parser.getNozzleTargetTemperature(), 0.001f);
        assertEquals(220f, parser.getNozzleTarget_1_Temperature(), 0.001f);
        assertEquals(70f, parser.getBedTargetTemperature(), 0.001f);
        assertEquals(0.8f, parser.getExtruder0RetractionDistance(), 0.001f);
        assertEquals(0f, parser.getExtruder1RetractionDistance(), 0.001f);
        assertTrue(parser.isToolUsageConfirmed());
        assertTrue(parser.isTool0Used());
        assertFalse(parser.isTool1Used());
        assertEquals(170.210f, parser.getBoundary().getMinX(), 0.002f);
        assertEquals(229.775f, parser.getBoundary().getMaxX(), 0.002f);
        // Arc extrema are a few microns wider than the legacy endpoint-only boundary.
        assertEquals(184.708f, parser.getBoundary().getMinY(), 0.01f);
        assertEquals(215.292f, parser.getBoundary().getMaxY(), 0.01f);
        assertEquals(0.2f, parser.getBoundary().getMinZ(), 0.002f);
        assertEquals(48.04f, parser.getBoundary().getMaxZ(), 0.002f);
        assertTrue("fixture fast parse should remain interactive, duration="
                        + parser.getLastParseDurationMsForTest() + "ms",
                parser.getLastParseDurationMsForTest() < 10_000L);
        parser.destroy();
    }

    private static GcodeParser parse(String gcode) {
        GcodeParser parser = new GcodeParser();
        parser.startParse(new ByteArrayInputStream(gcode.getBytes(StandardCharsets.UTF_8)),
                IMachine.WorkType.FDM);
        int result = parser.getParseProgressObservable()
                .filter(progress -> progress == 100 || progress < 0)
                .timeout(10, TimeUnit.SECONDS)
                .blockingFirst();
        assertEquals(100, result);
        return parser;
    }

    private static String validOrca(String newline, boolean finalNewline,
                                    boolean executablePoison) {
        String[] lines = new String[]{
                "; HEADER_BLOCK_START",
                "; generated by Snapmaker Orca 2.3.1",
                "; total layer number: 2",
                "; HEADER_BLOCK_END",
                "",
                "; THUMBNAIL_BLOCK_START",
                "; THUMBNAIL_BLOCK_END",
                "",
                "; EXECUTABLE_BLOCK_START",
                "M605 S2",
                "; Plate   : test plate",
                "; --- initial_extruder: 0",
                "; --- has_wipe_tower: 0",
                "; --- total_toolchanges: 0",
                "; --- T0: true",
                "; --- T1: false",
                "T0",
                "G28",
                "G90",
                "G21",
                "M83",
                ";TYPE:Custom",
                "G0X0Y-0.5Z0.2",
                "G1X140E8",
                ";LAYER_CHANGE",
                "G0X10Y10Z0.2",
                "G1X20Y10E1",
                "G2X30Y10I5J0E1",
                "G1X401Y10E1",
                "G0X405Y10E1",
                "G1E-0.8",
                executablePoison ? "M104 Snot-a-number" : "; executable remains valid",
                "; EXECUTABLE_BLOCK_END",
                "; filament used [mm] = 2500,0",
                "; filament used [g] = 7.5,0",
                "; estimated printing time (normal mode) = 1h 1m 1s",
                "; CONFIG_BLOCK_START",
                "; filament_type = PLA;PETG",
                "; layer_height = 0.2",
                "; nozzle_diameter = 0.4,0.6",
                "; nozzle_temperature = 215,225",
                "; first_layer_bed_temperature = 65",
                "; retraction_length = 0.8,0.8",
                "; retract_length_toolchange = 16,16",
                "; CONFIG_BLOCK_END"
        };
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < lines.length; index++) {
            if (index > 0) result.append(newline);
            result.append(lines[index]);
        }
        if (finalNewline) result.append(newline);
        return result.toString();
    }

    private static int physicalLines(String value) {
        if (value.isEmpty()) return 0;
        int lines = 0;
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) == '\n') lines++;
        }
        return value.charAt(value.length() - 1) == '\n' ? lines : lines + 1;
    }

    private static String digest(String algorithm, String value) {
        try {
            byte[] bytes = MessageDigest.getInstance(algorithm)
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) result.append(String.format("%02x", item & 0xff));
            return result.toString();
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static final class TrackingByteArrayInputStream extends ByteArrayInputStream {
        volatile boolean closed;

        TrackingByteArrayInputStream(byte[] buffer) {
            super(buffer);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }

    private static final class NonReplayableInputStream extends InputStream {
        private final ByteArrayInputStream delegate;

        NonReplayableInputStream(byte[] buffer) {
            delegate = new ByteArrayInputStream(buffer);
        }

        @Override
        public int read() {
            return delegate.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            return delegate.read(buffer, offset, length);
        }

        @Override
        public int available() {
            return delegate.available();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
