package fabscreen.platform.base.obico;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ObicoPassthruProtocolTest {
    @Test
    public void parsesJogHomeAndTemperatureFromOfficialOctoPrintShape() {
        ObicoPassthruRequest jog = accepted(
                envelope("_printer", "jog", "[{\"x\":-10}]", "0.123"));
        assertEquals(ObicoPassthruRequest.Type.JOG, jog.getType());
        assertEquals("x", jog.getJogAxis());
        assertEquals(-10.0d, jog.getJogDistanceMm(), 0.0001d);

        ObicoPassthruRequest home = accepted(
                envelope("_printer", "home", "[[\"x\",\"y\",\"z\"]]", "home-1"));
        assertEquals(ObicoPassthruRequest.Type.HOME, home.getType());
        assertEquals(Arrays.asList("x", "y", "z"), home.getHomeAxes());
        try {
            home.getHomeAxes().add("x");
            fail("Home axes must be immutable");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }

        ObicoPassthruRequest temperature = accepted(envelope(
                "_printer",
                "set_temperature",
                "[\"tool1\",180]",
                "temp:1"));
        assertEquals(ObicoPassthruRequest.Type.SET_TEMPERATURE, temperature.getType());
        assertEquals("tool1", temperature.getHeaterName());
        assertEquals(180, temperature.getTargetTemperatureC());
    }

    @Test
    public void parsesOnlyExactObicoExtrusionAndTuneCommands() {
        ObicoPassthruRequest extrude = accepted(envelope(
                "_printer",
                "commands",
                "[\"M83\\nT1\\nG1 E-50 F300\"]",
                "extrude-1"));
        assertEquals(ObicoPassthruRequest.Type.EXTRUDE, extrude.getType());
        assertEquals(1, extrude.getExtruderIndex());
        assertEquals(-50.0d, extrude.getExtrusionDistanceMm(), 0.0001d);
        assertEquals(300, extrude.getExtrusionFeedrateMmPerMinute());

        ObicoPassthruRequest printSpeed = accepted(envelope(
                "_printer", "commands", "[\"M220 S125\"]", "speed-1"));
        assertEquals(ObicoPassthruRequest.Type.SET_PRINT_SPEED, printSpeed.getType());
        assertEquals(125, printSpeed.getPercentage());

        ObicoPassthruRequest flow = accepted(envelope(
                "_printer", "commands", "[\"M221 S95\"]", "flow-1"));
        assertEquals(ObicoPassthruRequest.Type.SET_FLOW_RATE, flow.getType());
        assertEquals(95, flow.getPercentage());

        ObicoPassthruRequest fan = accepted(envelope(
                "_printer", "commands", "[\"M106 S128\"]", "fan-1"));
        assertEquals(ObicoPassthruRequest.Type.SET_FAN_SPEED, fan.getType());
        assertEquals(128, fan.getFanSpeed());

        ObicoPassthruRequest fanOff = accepted(envelope(
                "_printer", "commands", "[\"M107\"]", "fan-2"));
        assertEquals(0, fanOff.getFanSpeed());
    }

    @Test
    public void parsesBoundedHttpsCloudFileDownloadWithoutLoggingSignedUrl() {
        String args = "[{"
                + "\"id\":42,"
                + "\"url\":\"https://files.example.test/model.gcode?signature=secret\","
                + "\"filename\":\"3dbenchy 2.gcode\","
                + "\"safe_filename\":\"3dbenchy_2.gcode\"}]";
        ObicoPassthruRequest request = accepted(
                envelope("file_downloader", "download", args, "download-42"));
        assertEquals(ObicoPassthruRequest.Type.DOWNLOAD_FILE, request.getType());
        assertEquals("42", request.getDownloadFile().getId());
        assertEquals("3dbenchy 2.gcode", request.getDownloadFile().getFilename());
        assertEquals("3dbenchy_2.gcode", request.getDownloadFile().getSafeFilename());
        assertFalse(request.toString().contains("signature"));
        assertFalse(request.getDownloadFile().toString().contains("secret"));
    }

    @Test
    public void parsesPrinterLocalFileBrowseAndSearchRequests() {
        ObicoPassthruRequest root = accepted(envelopeWithKwargs(
                "_file_manager", "list_files", "[]",
                "{\"path\":null,\"recursive\":false,\"level\":1}", "files-root"));
        assertEquals(ObicoPassthruRequest.Type.LIST_FILES, root.getType());
        assertNull(root.getListPath());
        assertFalse(root.isListRecursive());
        assertEquals(Integer.valueOf(1), root.getListLevel());
        assertNull(root.getListFilter());

        ObicoPassthruRequest subfolder = accepted(envelopeWithKwargs(
                "_file_manager", "list_files", "[]",
                "{\"path\":\"Projects/2026\",\"recursive\":false,\"level\":1}",
                "files-folder"));
        assertEquals("Projects/2026", subfolder.getListPath());

        ObicoPassthruRequest search = accepted(envelopeWithKwargs(
                "_file_manager", "list_files", "[]",
                "{\"filter\":\"benchy\",\"recursive\":true}", "files-search"));
        assertTrue(search.isListRecursive());
        assertEquals("benchy", search.getListFilter());
        assertNull(search.getListLevel());
    }

    @Test
    public void parsesLocalFileSelectionAndPreservesPrintIntent() {
        ObicoPassthruRequest start = accepted(envelopeWithKwargs(
                "_printer", "select_file", "[\"Projects/benchy.gcode\",null]",
                "{\"printAfterSelect\":\"true\"}", "select-1"));
        assertEquals(ObicoPassthruRequest.Type.SELECT_FILE, start.getType());
        assertEquals("Projects/benchy.gcode", start.getSelectedFilePath());
        assertTrue(start.isPrintAfterSelect());

        ObicoPassthruRequest selectOnly = accepted(envelopeWithKwargs(
                "_printer", "select_file", "[\"benchy.gcode\",null]",
                "{\"printAfterSelect\":false}", "select-2"));
        assertFalse(selectOnly.isPrintAfterSelect());
        assertTrue(accepted(envelopeWithKwargs(
                "_printer", "select_file", "[\"benchy.gcode\",null]",
                "{\"printAfterSelect\":true}", "select-3")).isPrintAfterSelect());
    }

    @Test
    public void rejectsUnsafeOrUnexpectedPrinterLocalFileRequests() {
        assertRejected(envelopeWithKwargs("_file_manager", "list_files", "[]",
                "{\"path\":\"../secret\"}", "bad-list-1"));
        assertRejected(envelopeWithKwargs("_file_manager", "list_files", "[]",
                "{\"path\":\"/absolute\"}", "bad-list-2"));
        assertRejected(envelopeWithKwargs("_file_manager", "list_files", "[]",
                "{\"recursive\":\"true\"}", "bad-list-3"));
        assertRejected(envelopeWithKwargs("_file_manager", "list_files", "[]",
                "{\"level\":100}", "bad-list-4"));
        assertRejected(envelopeWithKwargs("_file_manager", "list_files", "[1]",
                "{}", "bad-list-5"));
        assertRejected(envelopeWithKwargs("_file_manager", "list_files", "[]",
                "{\"extra\":1}", "bad-list-6"));
        assertRejected(envelopeWithKwargs("_printer", "select_file",
                "[\"../benchy.gcode\",null]", "{\"printAfterSelect\":true}",
                "bad-select-1"));
        assertRejected(envelopeWithKwargs("_printer", "select_file",
                "[\"benchy.gcode\",\"local\"]", "{\"printAfterSelect\":true}",
                "bad-select-2"));
        assertRejected(envelopeWithKwargs("_printer", "select_file",
                "[\"benchy.gcode\",null]", "{\"printAfterSelect\":1}",
                "bad-select-3"));
        assertRejected(envelopeWithKwargs("_printer", "select_file",
                "[\"benchy.gcode\",null]", "{\"printAfterSelect\":true,\"delete\":true}",
                "bad-select-4"));
        assertRejected(envelopeWithKwargs("_printer", "select_file",
                "[\"notes.txt\",null]", "{\"printAfterSelect\":true}",
                "bad-select-5"));
    }

    @Test
    public void rejectsReflectiveTargetsArbitraryGcodeAndMalformedArguments() {
        assertRejected(envelope("runtime", "exec", "[\"reboot\"]", "bad-1"));
        assertRejected(envelope("_printer", "commands", "[\"G28\"]", "bad-2"));
        assertRejected(envelope(
                "_printer",
                "commands",
                "[\"M220 S100\\nM112\"]",
                "bad-3"));
        assertRejected(envelope(
                "_printer",
                "commands",
                "[\"M83\\nT0\\nG1 E1.0 F300\"]",
                "bad-3b"));
        assertRejected(envelope("_printer", "jog", "[{\"x\":10,\"y\":10}]", "bad-4"));
        assertRejected(envelope("_printer", "jog", "[{\"x\":101}]", "bad-5"));
        assertRejected(envelope("_printer", "jog", "[{\"x\":\"10\"}]", "bad-5b"));
        assertRejected(envelope("_printer", "home", "[[\"x\",\"x\"]]", "bad-6"));
        assertRejected(envelope(
                "_printer",
                "set_temperature",
                "[\"tool0\",500]",
                "bad-7"));
        assertRejected(envelope(
                "file_downloader",
                "download",
                "[{\"id\":1,\"url\":\"http://example.test/a.gcode\","
                        + "\"filename\":\"a.gcode\",\"safe_filename\":\"a.gcode\"}]",
                "bad-8"));
        assertRejected(envelope(
                "file_downloader",
                "download",
                "[{\"id\":1,\"url\":\"https://example.test/a.gcode\","
                        + "\"filename\":\"../a.gcode\",\"safe_filename\":\"a.gcode\"}]",
                "bad-9"));
    }

    @Test
    public void distinguishesAbsentPassthruAndRejectsInvalidReference() {
        ObicoPassthruParseResult absent =
                ObicoProtocol.parsePassthruRequest("{\"commands\":[]}");
        assertFalse(absent.isPresent());
        assertFalse(absent.isAccepted());
        assertNull(absent.getRequest());

        ObicoPassthruParseResult invalid = ObicoProtocol.parsePassthruRequest(
                envelope("_printer", "jog", "[{\"x\":10}]", "bad/ref"));
        assertTrue(invalid.isPresent());
        assertFalse(invalid.isAccepted());
        assertTrue(invalid.getReference().isEmpty());

        ObicoPassthruParseResult wrongPrimitiveType =
                ObicoProtocol.parsePassthruRequest(
                        "{\"passthru\":{\"target\":7,\"func\":\"jog\","
                                + "\"args\":[{\"x\":10}],\"ref\":\"bad-primitive\"}}");
        assertTrue(wrongPrimitiveType.isPresent());
        assertFalse(wrongPrimitiveType.isAccepted());
    }

    @Test
    public void buildsObicoCompatibleAckDownloadAndErrorEnvelopes() {
        JsonObject ack = parsePassthru(ObicoProtocol.passthruAckPayload("ack-1"));
        assertEquals("ack-1", ack.get("ref").getAsString());
        assertTrue(ack.get("ret").isJsonNull());

        JsonObject download = parsePassthru(
                ObicoProtocol.passthruDownloadAckPayload("ack-2", "part.gcode"));
        assertEquals(
                "part.gcode",
                download.getAsJsonObject("ret").get("target_path").getAsString());

        JsonObject error = parsePassthru(
                ObicoProtocol.passthruErrorPayload("ack-3", "Printer is busy"));
        assertEquals("Printer is busy", error.get("error").getAsString());
        assertFalse(error.has("ret"));

        JsonObject inventory = new JsonObject();
        inventory.add("local", new JsonObject());
        JsonObject fileList = parsePassthru(
                ObicoProtocol.passthruJsonAckPayload("ack-4", inventory));
        assertEquals("ack-4", fileList.get("ref").getAsString());
        assertTrue(fileList.getAsJsonObject("ret").has("local"));
    }

    private static ObicoPassthruRequest accepted(String message) {
        ObicoPassthruParseResult result = ObicoProtocol.parsePassthruRequest(message);
        assertTrue(result.getError(), result.isPresent());
        assertTrue(result.getError(), result.isAccepted());
        return result.getRequest();
    }

    private static void assertRejected(String message) {
        ObicoPassthruParseResult result = ObicoProtocol.parsePassthruRequest(message);
        assertTrue(result.isPresent());
        assertFalse(result.isAccepted());
        assertFalse(result.getError().isEmpty());
    }

    private static JsonObject parsePassthru(String payload) {
        return new JsonParser().parse(payload)
                .getAsJsonObject()
                .getAsJsonObject("passthru");
    }

    private static String envelope(String target, String function, String args, String reference) {
        return "{\"passthru\":{"
                + "\"target\":\"" + target + "\","
                + "\"func\":\"" + function + "\","
                + "\"args\":" + args + ","
                + "\"ref\":\"" + reference + "\"}}";
    }

    private static String envelopeWithKwargs(
            String target,
            String function,
            String args,
            String kwargs,
            String reference) {
        return "{\"passthru\":{"
                + "\"target\":\"" + target + "\","
                + "\"func\":\"" + function + "\","
                + "\"args\":" + args + ","
                + "\"kwargs\":" + kwargs + ","
                + "\"ref\":\"" + reference + "\"}}";
    }
}
