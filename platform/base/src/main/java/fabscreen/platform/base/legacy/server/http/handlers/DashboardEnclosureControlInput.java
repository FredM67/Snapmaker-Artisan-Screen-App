package fabscreen.platform.base.legacy.server.http.handlers;

/** Strict query validation shared by the enclosure LED and fan endpoints. */
final class DashboardEnclosureControlInput {
    private DashboardEnclosureControlInput() {
    }

    static Result parse(String enabledValue, String percentValue) {
        Boolean enabled = parseBoolean(enabledValue);
        if (enabled == null) {
            return Result.invalid("Parameter enabled must be true or false.");
        }
        Integer requestedPercent = parsePercent(percentValue);
        if (requestedPercent == null) {
            return Result.invalid("Parameter percent must be an integer from 0 to 100.");
        }
        if (enabled && requestedPercent == 0) {
            return Result.invalid("Parameter percent must be from 1 to 100 when enabled is true.");
        }
        return Result.valid(enabled, requestedPercent, enabled ? requestedPercent : 0);
    }

    private static Boolean parseBoolean(String value) {
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        return null;
    }

    private static Integer parsePercent(String value) {
        if (value == null || value.isEmpty() || value.length() > 3) return null;
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isDigit(value.charAt(index))) return null;
        }
        try {
            int percent = Integer.parseInt(value);
            return percent >= 0 && percent <= 100 ? percent : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static final class Result {
        final boolean valid;
        final boolean enabled;
        final int requestedPercent;
        final int effectivePercent;
        final String error;

        private Result(
                boolean valid,
                boolean enabled,
                int requestedPercent,
                int effectivePercent,
                String error
        ) {
            this.valid = valid;
            this.enabled = enabled;
            this.requestedPercent = requestedPercent;
            this.effectivePercent = effectivePercent;
            this.error = error;
        }

        static Result valid(boolean enabled, int requestedPercent, int effectivePercent) {
            return new Result(true, enabled, requestedPercent, effectivePercent, "");
        }

        static Result invalid(String error) {
            return new Result(false, false, 0, 0, error);
        }
    }
}
