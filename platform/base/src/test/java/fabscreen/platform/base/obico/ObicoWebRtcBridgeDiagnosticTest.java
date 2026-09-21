package fabscreen.platform.base.obico;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ObicoWebRtcBridgeDiagnosticTest {
    @Test
    public void streamDefaultsToInactiveUntilAViewerOpens() {
        ObicoLiveStream idle = new ObicoLiveStream() {
            @Override public void start(String authToken, Listener listener) { }
            @Override public void stop() { }
            @Override public void sendJanus(String payload) { }
            @Override public boolean isReady() { return true; }
        };
        assertFalse(idle.isStreaming());
    }

    @Test
    public void acceptsOnlyKnownNonSensitiveStageCodes() {
        assertTrue(ObicoWebRtcBridge.isSafeDiagnostic("janus_watch"));
        assertTrue(ObicoWebRtcBridge.isSafeDiagnostic("ice_connected"));
        assertTrue(ObicoWebRtcBridge.isSafeDiagnostic("frames_sent=50"));
        assertTrue(ObicoWebRtcBridge.isSafeDiagnostic("frames_in=75,queued=75,viewers=1"));
        assertFalse(ObicoWebRtcBridge.isSafeDiagnostic(""));
        assertFalse(ObicoWebRtcBridge.isSafeDiagnostic("token=secret"));
        assertFalse(ObicoWebRtcBridge.isSafeDiagnostic("candidate:1 1 udp 1 192.0.2.1"));
        assertFalse(ObicoWebRtcBridge.isSafeDiagnostic("ice_connected\nsecret"));
    }
}
