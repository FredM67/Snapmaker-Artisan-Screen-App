package fabscreen.platform.base.legacy.server.http.handlers;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DashboardCameraSettingsInputTest {
    @Test
    public void acceptsSupportedAutomaticConfiguration() {
        DashboardCameraSettingsInput.Result result = DashboardCameraSettingsInput.parse(
                "true", "", "1280", "720", "5"
        );

        assertTrue(result.valid);
        assertTrue(result.enabled);
        assertEquals("", result.sourceId);
        assertEquals(1280, result.width);
        assertEquals(720, result.height);
        assertEquals(5, result.fps);
    }

    @Test
    public void acceptsOpaqueStableSourceIdentifier() {
        DashboardCameraSettingsInput.Result result = DashboardCameraSettingsInput.parse(
                "false", "046d:0825@1-1.2/video1", "640", "480", "1"
        );

        assertTrue(result.valid);
        assertFalse(result.enabled);
        assertEquals("046d:0825@1-1.2/video1", result.sourceId);
    }

    @Test
    public void rejectsUnsupportedResolutionAndFrameRate() {
        assertFalse(DashboardCameraSettingsInput.parse(
                "true", "", "3840", "2160", "5"
        ).valid);
        assertFalse(DashboardCameraSettingsInput.parse(
                "true", "", "1280", "720", "11"
        ).valid);
    }

    @Test
    public void rejectsMalformedBooleanAndControlCharacters() {
        assertFalse(DashboardCameraSettingsInput.parse(
                "yes", "", "1280", "720", "5"
        ).valid);
        assertFalse(DashboardCameraSettingsInput.parse(
                "true", "camera\nnode", "1280", "720", "5"
        ).valid);
    }

    @Test
    public void acceptsNetworkCameraUrlsAndBlankKeepExistingRequest() {
        DashboardCameraSettingsInput.Result result = DashboardCameraSettingsInput.parse(
                "true", "mjpeg", "http://192.168.1.24:81/stream", "1280", "720", "5"
        );
        assertTrue(result.valid);
        assertEquals("mjpeg", result.sourceId);
        assertEquals("http://192.168.1.24:81/stream", result.streamUrl);
        DashboardCameraSettingsInput.Result rtsp = DashboardCameraSettingsInput.parse(
                "true", "rtsp", "rtsp://user:password@camera.local/stream2", "1280", "720", "5"
        );
        assertTrue(rtsp.valid);
        assertEquals("rtsp", rtsp.sourceId);
        assertTrue(DashboardCameraSettingsInput.parse(
                "true", "rtsp", "", "640", "360", "5"
        ).valid);
        assertTrue(DashboardCameraSettingsInput.parse(
                "true", "mjpeg", "", "1280", "720", "5"
        ).valid);
        assertTrue(DashboardCameraSettingsInput.parse(
                "true", "rtsp", "", "1280", "720", "5"
        ).valid);
        assertFalse(DashboardCameraSettingsInput.parse(
                "true", "mjpeg", "http://camera.local/st\nream", "1280", "720", "5"
        ).valid);
    }
}
