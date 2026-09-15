package fabscreen.platform.base.lib.parser;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Collects Prusa/Orca footer metadata without depending on Android classes. */
final class OrcaMetadataCommentParser {
    private static final Pattern DURATION_PART = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*([dhms])");

    private final Result result = new Result();

    void reset() {
        result.reset();
    }

    void consumeLine(String line) {
        if (line == null || line.isEmpty() || line.charAt(0) != ';') return;
        String comment = line.substring(1).trim();
        String key = keyBeforeDelimiter(comment).toLowerCase(Locale.US);
        try {
            if (key.equals("total layer number")) {
                result.layerCount = parseInteger(valueAfterDelimiter(comment));
            } else if (key.equals("filament used [mm]")) {
                Float millimeters = sumNumbers(valueAfterDelimiter(comment));
                result.materialLengthMeters = millimeters == null ? null : millimeters / 1000f;
            } else if (key.equals("filament used [g]")) {
                result.materialWeightGrams = sumNumbers(valueAfterDelimiter(comment));
            } else if (key.equals("estimated printing time")
                    || key.startsWith("estimated printing time (")) {
                result.estimatedTimeSeconds = parseDurationSeconds(valueAfterDelimiter(comment));
            } else if (key.equals("filament_type")) {
                String[] values = splitValues(valueAfterDelimiter(comment), ";");
                result.materialLeft = valueAt(values, 0);
                result.materialRight = valueAt(values, 1);
            } else if (key.equals("layer_height")) {
                result.layerHeightMm = parseFloat(valueAfterDelimiter(comment));
            } else if (key.equals("nozzle_diameter")) {
                Float[] values = parseNumberList(valueAfterDelimiter(comment));
                result.nozzleDiameterLeftMm = valueAt(values, 0);
                result.nozzleDiameterRightMm = valueAt(values, 1);
            } else if (key.equals("nozzle_temperature")) {
                Float[] values = parseNumberList(valueAfterDelimiter(comment));
                result.nozzleTemperatureLeftC = valueAt(values, 0);
                result.nozzleTemperatureRightC = valueAt(values, 1);
            } else if (key.equals("first_layer_bed_temperature")) {
                Float[] values = parseNumberList(valueAfterDelimiter(comment));
                result.bedTemperatureC = valueAt(values, 0);
            } else if (key.equals("--- initial_extruder")) {
                Integer initialExtruder = parseInteger(valueAfterDelimiter(comment));
                if (initialExtruder != null && initialExtruder >= 0) {
                    result.initialExtruder = initialExtruder;
                }
            } else if (key.equals("--- has_wipe_tower")) {
                result.hasWipeTower = mergeBoolean(
                        result.hasWipeTower,
                        parseBoolean(valueAfterDelimiter(comment))
                );
            } else if (key.equals("--- total_toolchanges")) {
                Integer toolChanges = parseInteger(valueAfterDelimiter(comment));
                if (toolChanges != null && toolChanges >= 0) {
                    result.totalToolChanges = toolChanges;
                }
            } else if (key.equals("--- t0")) {
                result.plateTool0Used = mergeToolUsage(
                        result.plateTool0Used,
                        parseBoolean(valueAfterDelimiter(comment))
                );
            } else if (key.equals("--- t1")) {
                result.plateTool1Used = mergeToolUsage(
                        result.plateTool1Used,
                        parseBoolean(valueAfterDelimiter(comment))
                );
            }
        } catch (NumberFormatException ignored) {
            // A malformed optional footer field must not fail the complete G-code parse.
        }
    }

    Result getResult() {
        return result;
    }

    private String keyBeforeDelimiter(String comment) {
        int delimiter = delimiterIndex(comment);
        return delimiter < 0 ? comment.trim() : comment.substring(0, delimiter).trim();
    }

    private String valueAfterDelimiter(String comment) {
        int delimiter = delimiterIndex(comment);
        if (delimiter < 0 || delimiter + 1 >= comment.length()) return "";
        return comment.substring(delimiter + 1).trim();
    }

    private int delimiterIndex(String comment) {
        int equals = comment.indexOf('=');
        int colon = comment.indexOf(':');
        if (equals < 0) return colon;
        if (colon < 0) return equals;
        return Math.min(equals, colon);
    }

    private Integer parseInteger(String value) {
        if (value.isEmpty()) return null;
        return Integer.parseInt(value.trim());
    }

    private Float parseFloat(String value) {
        if (value.isEmpty()) return null;
        float parsed = Float.parseFloat(value.trim());
        return Float.isNaN(parsed) || Float.isInfinite(parsed) ? null : parsed;
    }

    private Boolean parseBoolean(String value) {
        if (value == null) return null;
        String normalized = value.trim().toLowerCase(Locale.US);
        if (normalized.equals("true") || normalized.equals("1")
                || normalized.equals("yes")) return Boolean.TRUE;
        if (normalized.equals("false") || normalized.equals("0")
                || normalized.equals("no")) return Boolean.FALSE;
        return null;
    }

    private Boolean mergeBoolean(Boolean current, Boolean candidate) {
        return candidate == null ? current : candidate;
    }

    private Boolean mergeToolUsage(Boolean current, Boolean candidate) {
        if (candidate == null) return current;
        if (current != null && current.booleanValue() != candidate.booleanValue()) {
            result.plateToolUsageConflict = true;
            // A contradictory producer hint must never hide a potentially used tool.
            return current || candidate;
        }
        return candidate;
    }

    private Float sumNumbers(String value) {
        Float[] values = parseNumberList(value);
        if (values.length == 0) return null;
        float total = 0f;
        boolean found = false;
        for (Float item : values) {
            if (item == null) continue;
            total += item;
            found = true;
        }
        return found ? total : null;
    }

    private Float[] parseNumberList(String value) {
        String[] parts = splitValues(value, ",");
        Float[] result = new Float[parts.length];
        for (int index = 0; index < parts.length; index++) {
            result[index] = parseFloat(parts[index]);
        }
        return result;
    }

    private String[] splitValues(String value, String delimiter) {
        if (value == null || value.trim().isEmpty()) return new String[0];
        String[] parts = value.split(Pattern.quote(delimiter), -1);
        for (int index = 0; index < parts.length; index++) parts[index] = parts[index].trim();
        return parts;
    }

    private Float parseDurationSeconds(String value) {
        Matcher matcher = DURATION_PART.matcher(value.toLowerCase(Locale.US));
        float seconds = 0f;
        boolean found = false;
        while (matcher.find()) {
            float amount = Float.parseFloat(matcher.group(1));
            char unit = matcher.group(2).charAt(0);
            if (unit == 'd') seconds += amount * 86_400f;
            else if (unit == 'h') seconds += amount * 3_600f;
            else if (unit == 'm') seconds += amount * 60f;
            else seconds += amount;
            found = true;
        }
        return found ? seconds : null;
    }

    private <T> T valueAt(T[] values, int index) {
        return index >= 0 && index < values.length ? values[index] : null;
    }

    static final class Result {
        Integer layerCount;
        Float layerHeightMm;
        Float materialLengthMeters;
        Float materialWeightGrams;
        Float estimatedTimeSeconds;
        String materialLeft;
        String materialRight;
        Float nozzleDiameterLeftMm;
        Float nozzleDiameterRightMm;
        Float nozzleTemperatureLeftC;
        Float nozzleTemperatureRightC;
        Float bedTemperatureC;
        Integer initialExtruder;
        Boolean hasWipeTower;
        Integer totalToolChanges;
        Boolean plateTool0Used;
        Boolean plateTool1Used;
        boolean plateToolUsageConflict;

        void reset() {
            layerCount = null;
            layerHeightMm = null;
            materialLengthMeters = null;
            materialWeightGrams = null;
            estimatedTimeSeconds = null;
            materialLeft = null;
            materialRight = null;
            nozzleDiameterLeftMm = null;
            nozzleDiameterRightMm = null;
            nozzleTemperatureLeftC = null;
            nozzleTemperatureRightC = null;
            bedTemperatureC = null;
            initialExtruder = null;
            hasWipeTower = null;
            totalToolChanges = null;
            plateTool0Used = null;
            plateTool1Used = null;
            plateToolUsageConflict = false;
        }
    }
}
