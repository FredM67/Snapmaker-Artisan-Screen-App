package fabscreen.platform.base.legacy.server.http.handlers;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Strict parser for printer-scoped Obico settings and linking requests. */
final class DashboardObicoSettingsInput {
    private static final int MAX_SERVER_URL_LENGTH = 2048;

    private DashboardObicoSettingsInput() {
    }

    static Result parse(
            String enabledValue,
            String serverUrlValue,
            String allowInsecureValue,
            String remoteControlValue,
            String cameraUploadsValue
    ) {
        Boolean enabled = parseBoolean(enabledValue);
        Boolean allowInsecure = parseBoolean(allowInsecureValue);
        Boolean remoteControlEnabled = parseBoolean(remoteControlValue);
        Boolean cameraUploadsEnabled = parseBoolean(cameraUploadsValue);
        if (enabled == null
                || allowInsecure == null
                || remoteControlEnabled == null
                || cameraUploadsEnabled == null) {
            return Result.error("Obico switches must be true or false.");
        }

        String serverUrl = serverUrlValue == null ? "" : serverUrlValue.trim();
        if (serverUrl.isEmpty()) {
            return Result.error("Obico server URL is required.");
        }
        if (serverUrl.length() > MAX_SERVER_URL_LENGTH || containsControlCharacter(serverUrl)) {
            return Result.error("Obico server URL is invalid.");
        }

        final URI uri;
        try {
            uri = new URI(serverUrl);
        } catch (URISyntaxException ignored) {
            return Result.error("Enter a valid Obico server URL.");
        }
        String scheme = uri.getScheme() == null
                ? ""
                : uri.getScheme().toLowerCase(Locale.US);
        if (!"https".equals(scheme)
                || uri.getHost() == null
                || uri.getHost().isEmpty()
                || uri.getUserInfo() != null
                || uri.getFragment() != null
                || uri.getQuery() != null) {
            return Result.error("Obico server must be an HTTPS base URL without credentials, query, or fragment.");
        }
        if (allowInsecure) {
            return Result.error("Insecure HTTP is not supported on this Artisan build.");
        }

        String normalizedUrl = serverUrl;
        while (normalizedUrl.endsWith("/") && normalizedUrl.length() > scheme.length() + 3) {
            normalizedUrl = normalizedUrl.substring(0, normalizedUrl.length() - 1);
        }
        return Result.success(
                enabled,
                normalizedUrl,
                allowInsecure,
                remoteControlEnabled,
                cameraUploadsEnabled
        );
    }

    static LinkCodeResult parseLinkCode(String codeValue) {
        String code = codeValue == null ? "" : codeValue.trim();
        if (code.length() != 6) {
            return LinkCodeResult.error("Verification code must contain exactly six digits.");
        }
        for (int index = 0; index < code.length(); index++) {
            if (code.charAt(index) < '0' || code.charAt(index) > '9') {
                return LinkCodeResult.error("Verification code must contain exactly six digits.");
            }
        }
        return LinkCodeResult.success(code);
    }

    private static Boolean parseBoolean(String value) {
        if ("true".equals(value)) return true;
        if ("false".equals(value)) return false;
        return null;
    }

    private static boolean containsControlCharacter(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) return true;
        }
        return false;
    }

    static final class Result {
        final boolean valid;
        final boolean enabled;
        final String serverUrl;
        final boolean allowInsecureServer;
        final boolean remoteControlEnabled;
        final boolean cameraUploadsEnabled;
        final String error;

        private Result(
                boolean valid,
                boolean enabled,
                String serverUrl,
                boolean allowInsecureServer,
                boolean remoteControlEnabled,
                boolean cameraUploadsEnabled,
                String error
        ) {
            this.valid = valid;
            this.enabled = enabled;
            this.serverUrl = serverUrl;
            this.allowInsecureServer = allowInsecureServer;
            this.remoteControlEnabled = remoteControlEnabled;
            this.cameraUploadsEnabled = cameraUploadsEnabled;
            this.error = error;
        }

        static Result success(
                boolean enabled,
                String serverUrl,
                boolean allowInsecureServer,
                boolean remoteControlEnabled,
                boolean cameraUploadsEnabled
        ) {
            return new Result(
                    true,
                    enabled,
                    serverUrl,
                    allowInsecureServer,
                    remoteControlEnabled,
                    cameraUploadsEnabled,
                    ""
            );
        }

        static Result error(String message) {
            return new Result(false, false, "", false, false, false, message);
        }
    }

    static final class LinkCodeResult {
        final boolean valid;
        final String code;
        final String error;

        private LinkCodeResult(boolean valid, String code, String error) {
            this.valid = valid;
            this.code = code;
            this.error = error;
        }

        static LinkCodeResult success(String code) {
            return new LinkCodeResult(true, code, "");
        }

        static LinkCodeResult error(String message) {
            return new LinkCodeResult(false, "", message);
        }
    }
}
