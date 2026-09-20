package fabscreen.platform.base.obico;

/**
 * Runtime configuration for the Obico connection.
 *
 * <p>The authentication token is deliberately omitted from {@link #toString()}.
 * Callers must also avoid returning it from dashboard/API DTOs.</p>
 */
public final class ObicoSettings {
    public static final String DEFAULT_SERVER_URL = "https://app.obico.io";

    private final boolean enabled;
    private final String serverUrl;
    private final String authToken;
    private final boolean remoteControlEnabled;
    private final boolean cameraUploadsEnabled;
    private final boolean allowInsecureServer;

    public ObicoSettings(
            boolean enabled,
            String serverUrl,
            String authToken,
            boolean remoteControlEnabled,
            boolean cameraUploadsEnabled,
            boolean allowInsecureServer) {
        this.enabled = enabled;
        this.serverUrl = serverUrl == null ? DEFAULT_SERVER_URL : serverUrl.trim();
        this.authToken = authToken == null ? "" : authToken.trim();
        this.remoteControlEnabled = remoteControlEnabled;
        this.cameraUploadsEnabled = cameraUploadsEnabled;
        this.allowInsecureServer = allowInsecureServer;
    }

    public static ObicoSettings defaults() {
        return new ObicoSettings(false, DEFAULT_SERVER_URL, "", false, false, false);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getServerUrl() {
        return serverUrl;
    }

    public String getAuthToken() {
        return authToken;
    }

    public boolean isLinked() {
        return !authToken.isEmpty();
    }

    public boolean isRemoteControlEnabled() {
        return remoteControlEnabled;
    }

    public boolean isCameraUploadsEnabled() {
        return cameraUploadsEnabled;
    }

    public boolean isAllowInsecureServer() {
        return allowInsecureServer;
    }

    public ObicoSettings withAuthToken(String nextAuthToken) {
        return new ObicoSettings(
                enabled,
                serverUrl,
                nextAuthToken,
                remoteControlEnabled,
                cameraUploadsEnabled,
                allowInsecureServer);
    }

    public ObicoSettings withEnabled(boolean nextEnabled) {
        return new ObicoSettings(
                nextEnabled,
                serverUrl,
                authToken,
                remoteControlEnabled,
                cameraUploadsEnabled,
                allowInsecureServer);
    }

    @Override
    public String toString() {
        return "ObicoSettings{" +
                "enabled=" + enabled +
                ", serverUrl='" + serverUrl + '\'' +
                ", linked=" + isLinked() +
                ", remoteControlEnabled=" + remoteControlEnabled +
                ", cameraUploadsEnabled=" + cameraUploadsEnabled +
                ", allowInsecureServer=" + allowInsecureServer +
                '}';
    }
}
