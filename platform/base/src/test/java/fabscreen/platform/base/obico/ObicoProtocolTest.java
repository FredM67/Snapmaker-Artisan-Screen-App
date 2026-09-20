package fabscreen.platform.base.obico;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ObicoProtocolTest {
    @Test
    public void canonicalizesSecureServerAndBuildsWebSocketUrl() {
        assertEquals(
                "https://app.obico.io",
                ObicoProtocol.canonicalServerUrl("https://APP.OBICO.IO/", false));
        assertEquals(
                "wss://app.obico.io/ws/dev/",
                ObicoProtocol.websocketUrl("app.obico.io", false));
        assertEquals(
                "https://example.test/obico/api/v1/octo/printer/",
                ObicoProtocol.endpointUrl(
                        "https://example.test/obico/",
                        false,
                        "/api/v1/octo/printer/"));
    }

    @Test
    public void rejectsInsecureOrCredentialBearingServerByDefault() {
        assertInvalid("http://192.168.1.4:3334", false);
        assertInvalid("https://user:secret@example.test", false);
        assertInvalid("ftp://example.test", true);
        assertInvalid("https://example.test/?redirect=evil", false);

        assertEquals(
                "http://192.168.1.4:3334",
                ObicoProtocol.canonicalServerUrl("http://192.168.1.4:3334/", true));
        assertEquals(
                "ws://192.168.1.4:3334/ws/dev/",
                ObicoProtocol.websocketUrl("http://192.168.1.4:3334", true));
    }

    @Test
    public void buildsStatusPayloadWithoutLeakingSettingsSecrets() {
        ObicoPrinterSnapshot snapshot = new ObicoPrinterSnapshot(
                ObicoPrintState.PRINTING,
                "part.gcode",
                25.0d,
                100L,
                300L,
                250L,
                1.2d,
                900_000L,
                Arrays.asList(
                        new ObicoTemperature("tool0", 205.0d, 210.0d),
                        new ObicoTemperature("bed", 58.0d, 60.0d)),
                null)
                .withRemoteState(42L, 1.25d, 0.95d, 0.5d);

        String payload = ObicoProtocol.statusPayload(
                snapshot,
                "PrintStarted",
                true,
                true);
        JsonObject root = new JsonParser().parse(payload).getAsJsonObject();

        assertEquals(
                "Printing",
                root.getAsJsonObject("status")
                        .getAsJsonObject("state")
                        .get("text")
                        .getAsString());
        assertEquals(
                25.0d,
                root.getAsJsonObject("status")
                        .getAsJsonObject("progress")
                        .get("completion")
                        .getAsDouble(),
                0.001d);
        assertEquals("PrintStarted", root.getAsJsonObject("event").get("event_type").getAsString());
        assertEquals(
                42L,
                root.getAsJsonObject("status")
                        .getAsJsonObject("job")
                        .getAsJsonObject("file")
                        .get("obico_g_code_file_id")
                        .getAsLong());
        assertEquals(
                1.25d,
                root.getAsJsonObject("status").get("currentFeedRate").getAsDouble(),
                0.001d);
        assertEquals(
                0.95d,
                root.getAsJsonObject("status").get("currentFlowRate").getAsDouble(),
                0.001d);
        assertEquals(
                0.5d,
                root.getAsJsonObject("status").get("currentFanSpeed").getAsDouble(),
                0.001d);
        assertEquals(1, root.getAsJsonObject("settings").getAsJsonArray("webcams").size());
        JsonObject fallbackWebcam = root.getAsJsonObject("settings")
                .getAsJsonArray("webcams").get(0).getAsJsonObject();
        assertEquals("jpeg", fallbackWebcam.get("stream_mode").getAsString());
        assertFalse(fallbackWebcam.has("stream_id"));
        assertFalse(payload.contains("auth_token"));
        assertFalse(payload.contains("Authorization"));
    }

    @Test
    public void advertisesMjpegWebRtcOnlyWhenCameraAndTransportAreReady() {
        String readyPayload = ObicoProtocol.statusPayload(
                ObicoPrinterSnapshot.offline(), null, true, true, true);
        JsonObject readySettings = new JsonParser().parse(readyPayload).getAsJsonObject()
                .getAsJsonObject("settings");
        JsonObject agent = readySettings.getAsJsonObject("agent");
        assertEquals("octoprint_obico", agent.get("name").getAsString());
        assertEquals("2.3.0", agent.get("version").getAsString());
        assertEquals("fabscreen-artisan", ObicoProtocol.AGENT_NAME);
        JsonObject readyWebcam = readySettings.getAsJsonArray("webcams")
                .get(0).getAsJsonObject();
        assertEquals("mjpeg_webrtc", readyWebcam.get("stream_mode").getAsString());
        assertEquals(ObicoProtocol.PRIMARY_MJPEG_STREAM_ID,
                readyWebcam.get("stream_id").getAsInt());
        assertFalse(readyWebcam.get("data_channel_available").getAsBoolean());
        assertTrue(readySettings.get("data_channel_id").isJsonNull());

        String fallbackPayload = ObicoProtocol.statusPayload(
                ObicoPrinterSnapshot.offline(), null, true, true, false);
        JsonObject fallbackWebcam = new JsonParser().parse(fallbackPayload).getAsJsonObject()
                .getAsJsonObject("settings").getAsJsonArray("webcams")
                .get(0).getAsJsonObject();
        assertEquals("jpeg", fallbackWebcam.get("stream_mode").getAsString());
        assertFalse(fallbackWebcam.has("stream_id"));

        String noCameraPayload = ObicoProtocol.statusPayload(
                ObicoPrinterSnapshot.offline(), null, true, false, true);
        assertEquals(0, new JsonParser().parse(noCameraPayload).getAsJsonObject()
                .getAsJsonObject("settings").getAsJsonArray("webcams").size());
    }

    @Test
    public void parsesOnlyAllowlistedBoundedCommandsAndRemoteStatus() {
        String message = "{\"commands\":["
                + "{\"cmd\":\"pause\",\"id\":\"p1\"},"
                + "{\"cmd\":\"start\"},"
                + "{\"cmd\":\"resume\",\"command_id\":42},"
                + "{\"cmd\":\"cancel\"}],"
                + "\"remote_status\":{\"viewing\":true,\"should_watch\":false}}";

        List<ObicoRemoteCommand> commands = ObicoProtocol.parseRemoteCommands(message);
        assertEquals(3, commands.size());
        assertEquals(ObicoRemoteCommand.Type.PAUSE, commands.get(0).getType());
        assertEquals("p1", commands.get(0).getServerId());
        assertEquals(ObicoRemoteCommand.Type.RESUME, commands.get(1).getType());
        assertEquals(ObicoRemoteCommand.Type.CANCEL, commands.get(2).getType());

        ObicoProtocol.RemoteViewingStatus status =
                ObicoProtocol.parseRemoteViewingStatus(message);
        assertTrue(status.isViewing());
        assertFalse(status.isShouldWatch());
        assertTrue(status.hasViewing());
        assertTrue(status.hasShouldWatch());
        assertTrue(ObicoProtocol.parseRemoteCommands("not json").isEmpty());
        assertNull(ObicoProtocol.parseRemoteViewingStatus("not json"));
    }

    @Test
    public void viewerOnlyUpdatesPreserveAnExistingAiWatchRequest() {
        ObicoProtocol.RemoteViewingStatus watchOn = ObicoProtocol.parseRemoteViewingStatus(
                "{\"remote_status\":{\"should_watch\":true}}");
        assertFalse(watchOn.hasViewing());
        assertTrue(watchOn.hasShouldWatch());
        boolean viewing = watchOn.resolveViewing(false);
        boolean shouldWatch = watchOn.resolveShouldWatch(false);
        assertFalse(viewing);
        assertTrue(shouldWatch);

        ObicoProtocol.RemoteViewingStatus viewerOn = ObicoProtocol.parseRemoteViewingStatus(
                "{\"remote_status\":{\"viewing\":true}}");
        assertTrue(viewerOn.hasViewing());
        assertFalse(viewerOn.hasShouldWatch());
        viewing = viewerOn.resolveViewing(viewing);
        shouldWatch = viewerOn.resolveShouldWatch(shouldWatch);
        assertTrue(viewing);
        assertTrue(shouldWatch);

        ObicoProtocol.RemoteViewingStatus viewerOff = ObicoProtocol.parseRemoteViewingStatus(
                "{\"remote_status\":{\"viewing\":false}}");
        viewing = viewerOff.resolveViewing(viewing);
        shouldWatch = viewerOff.resolveShouldWatch(shouldWatch);
        assertFalse(viewing);
        assertTrue(shouldWatch);
    }

    @Test
    public void aiWatchOnlyUpdatesPreserveAnExistingViewer() {
        ObicoProtocol.RemoteViewingStatus viewerOn = ObicoProtocol.parseRemoteViewingStatus(
                "{\"remote_status\":{\"viewing\":true}}");
        boolean viewing = viewerOn.resolveViewing(false);
        boolean shouldWatch = viewerOn.resolveShouldWatch(false);

        ObicoProtocol.RemoteViewingStatus watchOn = ObicoProtocol.parseRemoteViewingStatus(
                "{\"remote_status\":{\"should_watch\":true}}");
        viewing = watchOn.resolveViewing(viewing);
        shouldWatch = watchOn.resolveShouldWatch(shouldWatch);
        assertTrue(viewing);
        assertTrue(shouldWatch);

        ObicoProtocol.RemoteViewingStatus watchOff = ObicoProtocol.parseRemoteViewingStatus(
                "{\"remote_status\":{\"should_watch\":false}}");
        assertFalse(watchOff.hasViewing());
        assertTrue(watchOff.hasShouldWatch());
        viewing = watchOff.resolveViewing(viewing);
        shouldWatch = watchOff.resolveShouldWatch(shouldWatch);
        assertTrue(viewing);
        assertFalse(shouldWatch);
    }

    @Test
    public void emptyOrInvalidRemoteStatusDoesNotOverwriteKnownFlags() {
        assertNull(ObicoProtocol.parseRemoteViewingStatus("{\"remote_status\":{}}"));
        assertNull(ObicoProtocol.parseRemoteViewingStatus(
                "{\"remote_status\":{\"viewing\":null,\"should_watch\":\"false\"}}"));
        ObicoProtocol.RemoteViewingStatus validPart = ObicoProtocol.parseRemoteViewingStatus(
                "{\"remote_status\":{\"viewing\":false,\"should_watch\":\"false\"}}");
        assertTrue(validPart.hasViewing());
        assertFalse(validPart.hasShouldWatch());
        assertFalse(validPart.resolveViewing(true));
        assertTrue(validPart.resolveShouldWatch(true));
    }

    @Test
    public void parsesAutomaticFailureDetectionPauseWithoutCommandId() {
        String message = "{\"commands\":[{\"cmd\":\"pause\",\"args\":{"
                + "\"retract\":6.5,\"lift_z\":2.5,\"tools_off\":true,"
                + "\"bed_off\":false},\"initiator\":\"system\"}]}";

        List<ObicoRemoteCommand> commands = ObicoProtocol.parseRemoteCommands(message);
        assertEquals(1, commands.size());
        assertEquals(ObicoRemoteCommand.Type.PAUSE, commands.get(0).getType());
        assertEquals("", commands.get(0).getServerId());
        assertFalse(commands.get(0).getFingerprint().isEmpty());
    }

    @Test
    public void mapsPrintLifecycleEvents() {
        assertEquals(
                "PrintStarted",
                ObicoProtocol.transitionEvent(
                        ObicoPrintState.OPERATIONAL,
                        ObicoPrintState.PRINTING,
                        false));
        assertEquals(
                "PrintPaused",
                ObicoProtocol.transitionEvent(
                        ObicoPrintState.PRINTING,
                        ObicoPrintState.PAUSED,
                        false));
        assertEquals(
                "PrintCancelled",
                ObicoProtocol.transitionEvent(
                        ObicoPrintState.PAUSED,
                        ObicoPrintState.OPERATIONAL,
                        true));
        assertEquals(
                "PrintFailed",
                ObicoProtocol.transitionEvent(
                        ObicoPrintState.PRINTING,
                        ObicoPrintState.OPERATIONAL,
                        true,
                        true));
        assertNull(ObicoProtocol.transitionEvent(null, ObicoPrintState.OPERATIONAL, false));
    }

    @Test
    public void defaultsAreDisabledAndPrivacyPreserving() {
        ObicoSettings defaults = ObicoSettings.defaults();
        assertFalse(defaults.isEnabled());
        assertFalse(defaults.isLinked());
        assertFalse(defaults.isRemoteControlEnabled());
        assertFalse(defaults.isCameraUploadsEnabled());
        assertFalse(defaults.isAllowInsecureServer());
        assertFalse(defaults.toString().contains("authToken"));
    }

    private static void assertInvalid(String value, boolean allowInsecure) {
        try {
            ObicoProtocol.canonicalServerUrl(value, allowInsecure);
            fail("Expected URL to be rejected: " + value);
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
