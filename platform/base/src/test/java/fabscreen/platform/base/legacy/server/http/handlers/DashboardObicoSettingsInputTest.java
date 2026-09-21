package fabscreen.platform.base.legacy.server.http.handlers;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DashboardObicoSettingsInputTest {
    @Test
    public void acceptsSecureConfigurationAndNormalizesTrailingSlash() {
        DashboardObicoSettingsInput.Result result = DashboardObicoSettingsInput.parse(
                "true",
                "https://app.obico.io/",
                "false",
                "true",
                "false"
        );

        assertTrue(result.valid);
        assertTrue(result.enabled);
        assertEquals("https://app.obico.io", result.serverUrl);
        assertFalse(result.allowInsecureServer);
        assertTrue(result.remoteControlEnabled);
        assertFalse(result.cameraUploadsEnabled);
    }

    @Test
    public void rejectsPlainHttpEvenWhenExplicitlyRequested() {
        assertFalse(DashboardObicoSettingsInput.parse(
                "true", "http://obico.local", "false", "false", "false"
        ).valid);

        assertFalse(DashboardObicoSettingsInput.parse(
                "true", "http://obico.local", "true", "false", "false"
        ).valid);
    }

    @Test
    public void rejectsCredentialsQueryFragmentAndMalformedBooleans() {
        assertFalse(DashboardObicoSettingsInput.parse(
                "true", "https://user:password@example.com", "false", "false", "false"
        ).valid);
        assertFalse(DashboardObicoSettingsInput.parse(
                "true", "https://example.com?token=secret", "false", "false", "false"
        ).valid);
        assertFalse(DashboardObicoSettingsInput.parse(
                "yes", "https://example.com", "false", "false", "false"
        ).valid);
    }

    @Test
    public void requiresSixDigitLinkCode() {
        assertFalse(DashboardObicoSettingsInput.parseLinkCode("").valid);
        assertFalse(DashboardObicoSettingsInput.parseLinkCode(null).valid);
        assertEquals("123456", DashboardObicoSettingsInput.parseLinkCode(" 123456 ").code);
        assertFalse(DashboardObicoSettingsInput.parseLinkCode("12345").valid);
        assertFalse(DashboardObicoSettingsInput.parseLinkCode("12A456").valid);
        assertFalse(DashboardObicoSettingsInput.parseLinkCode("１２３４５６").valid);
    }
}
