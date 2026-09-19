package fabscreen.platform.base.camera;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class RtspStreamReaderTest {
    @Test public void rtspOutputSizeHonorsHighResolutionSelection() {
        assertArrayEquals(new int[]{640, 360}, RtspStreamReader.boundedOutputSize(640, 360));
        assertArrayEquals(new int[]{1280, 720}, RtspStreamReader.boundedOutputSize(1280, 720));
        assertArrayEquals(new int[]{1920, 1080}, RtspStreamReader.boundedOutputSize(1920, 1080));
        assertArrayEquals(new int[]{1920, 1080}, RtspStreamReader.boundedOutputSize(4096, 2160));
    }

    @Test public void acceptsPrivateLanRtspWithUserCredentials() {
        assertEquals("rtsp://user:password@192.168.1.41/stream2",
                RtspStreamReader.validateUrl(
                        "rtsp://user:password@192.168.1.41/stream2"));
        assertEquals("rtsp://10.0.0.2:8554/video?channel=1",
                RtspStreamReader.validateUrl(
                        "rtsp://10.0.0.2:8554/video?channel=1"));
        assertTrue(RtspStreamReader.isPrivateNumericHost("172.20.1.2"));
        assertTrue(RtspStreamReader.isPrivateNumericHost("192.168.1.41"));
    }

    @Test public void rejectsPublicAndRebindingCapableDestinations() {
        reject("rtsp://8.8.8.8/stream");
        reject("rtsp://127.0.0.1/stream");
        reject("rtsp://169.254.1.1/stream");
        reject("rtsp://camera.local/stream");
        reject("rtsp://192.168.1.41.evil.example/stream");
        reject("rtsp://192.168.001.41/stream");
        reject("rtsp://192.168.1.41:0/stream");
        assertFalse(RtspStreamReader.isPrivateNumericHost("224.0.0.1"));
    }

    @Test public void rejectsMalformedOrNonRtspUrlsWithoutEchoingCredentials() {
        reject("http://user:password@192.168.1.41/stream2");
        reject("rtsp://user:password@192.168.1.41/stream2#fragment");
        reject("rtsp://user:password@192.168.1.41");
        reject("rtsp://user:password@192.168.1.41/stream2\nInjected");
        reject(" rtsp://user:password@192.168.1.41/stream2");
        reject("rtsp://user:password@192.168.1.41/" + repeat('x', 2048));
    }

    private static void reject(String value) {
        try {
            RtspStreamReader.validateUrl(value);
            fail("Expected URL rejection");
        } catch (IllegalArgumentException expected) {
            assertFalse(expected.getMessage().contains("password"));
            assertFalse(expected.getMessage().contains("192.168.1.41"));
        }
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) result.append(value);
        return result.toString();
    }
}
