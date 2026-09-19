package fabscreen.platform.base.obico;

/** The auth token must be persisted securely and never returned by dashboard APIs. */
public final class ObicoLinkResult {
    private final boolean success;
    private final String authToken;
    private final String message;

    private ObicoLinkResult(boolean success, String authToken, String message) {
        this.success = success;
        this.authToken = authToken == null ? "" : authToken;
        this.message = message == null ? "" : message;
    }

    public static ObicoLinkResult success(String authToken) {
        return new ObicoLinkResult(true, authToken, "Linked");
    }

    public static ObicoLinkResult failure(String message) {
        return new ObicoLinkResult(false, "", message);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getAuthToken() {
        return authToken;
    }

    public String getMessage() {
        return message;
    }

    @Override
    public String toString() {
        return "ObicoLinkResult{" +
                "success=" + success +
                ", hasAuthToken=" + !authToken.isEmpty() +
                ", message='" + message + '\'' +
                '}';
    }
}
