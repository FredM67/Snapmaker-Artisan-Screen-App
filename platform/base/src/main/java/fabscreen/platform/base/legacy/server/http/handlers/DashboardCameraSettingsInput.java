package fabscreen.platform.base.legacy.server.http.handlers;

/** Strict parser for the printer-scoped camera settings endpoint. */
final class DashboardCameraSettingsInput {
    private static final int MAX_SOURCE_ID_LENGTH = 256;
    private static final int MAX_STREAM_URL_LENGTH = 2048;

    private DashboardCameraSettingsInput() {
    }

    static Result parse(
            String enabledValue,
            String sourceValue,
            String widthValue,
            String heightValue,
            String fpsValue
    ) {
        return parse(enabledValue, sourceValue, "", widthValue, heightValue, fpsValue);
    }

    static Result parse(
            String enabledValue,
            String sourceValue,
            String streamUrlValue,
            String widthValue,
            String heightValue,
            String fpsValue
    ) {
        if (!"true".equals(enabledValue) && !"false".equals(enabledValue)) {
            return Result.error("Parameter enabled must be true or false.");
        }
        String sourceId = sourceValue == null ? "" : sourceValue.trim();
        if (sourceId.length() > MAX_SOURCE_ID_LENGTH || containsControlCharacter(sourceId)) {
            return Result.error("The selected USB camera identifier is invalid.");
        }
        String streamUrl = streamUrlValue == null ? "" : streamUrlValue.trim();
        if (streamUrl.length() > MAX_STREAM_URL_LENGTH || containsControlCharacter(streamUrl)) {
            return Result.error("The IP camera URL is invalid.");
        }
        Integer width = parseInteger(widthValue);
        Integer height = parseInteger(heightValue);
        if (width == null || height == null || !isAllowedResolution(width, height)) {
            return Result.error("Resolution must be 640x360, 640x480, 1280x720, or 1920x1080.");
        }
        Integer fps = parseInteger(fpsValue);
        if (fps == null || fps < 1 || fps > 10) {
            return Result.error("Frame rate must be between 1 and 10 FPS.");
        }
        return Result.success("true".equals(enabledValue), sourceId, streamUrl, width, height, fps);
    }

    private static boolean containsControlCharacter(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) return true;
        }
        return false;
    }

    private static Integer parseInteger(String value) {
        if (value == null || value.isEmpty()) return null;
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isDigit(value.charAt(index))) return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean isAllowedResolution(int width, int height) {
        return (width == 640 && (height == 360 || height == 480))
                || (width == 1280 && height == 720)
                || (width == 1920 && height == 1080);
    }

    static final class Result {
        final boolean valid;
        final boolean enabled;
        final String sourceId;
        final String streamUrl;
        final int width;
        final int height;
        final int fps;
        final String error;

        private Result(
                boolean valid,
                boolean enabled,
                String sourceId,
                String streamUrl,
                int width,
                int height,
                int fps,
                String error
        ) {
            this.valid = valid;
            this.enabled = enabled;
            this.sourceId = sourceId;
            this.streamUrl = streamUrl;
            this.width = width;
            this.height = height;
            this.fps = fps;
            this.error = error;
        }

        static Result success(boolean enabled, String sourceId, String streamUrl, int width, int height, int fps) {
            return new Result(true, enabled, sourceId, streamUrl, width, height, fps, "");
        }

        static Result error(String message) {
            return new Result(false, false, "", "", 0, 0, 0, message);
        }
    }
}
