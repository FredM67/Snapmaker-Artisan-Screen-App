package fabscreen.platform.base.legacy.server.http.handlers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the human-readable mesh emitted by Marlin's {@code M420 V}. */
final class DashboardBedMeshParser {
    private static final Pattern GRID_ROW = Pattern.compile("^\\s*(\\d+)\\s+(.+?)\\s*$");
    private static final Pattern UNSIGNED_INTEGER = Pattern.compile("\\d+");
    private static final Pattern NUMBER = Pattern.compile(
            "[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?"
    );
    private static final int MAX_DIMENSION = 11;
    /** Matches Artisan firmware's {@code BILINEAR_SUBDIVISIONS}. */
    private static final int SURFACE_SUBDIVISIONS = 3;

    private DashboardBedMeshParser() {
    }

    static Result parse(String response) {
        String safeResponse = response == null ? "" : response;
        String normalized = safeResponse.replace("\r\n", "\n").replace('\r', '\n');
        String lower = normalized.toLowerCase(Locale.US);
        if (lower.contains("invalid mesh")) {
            return Result.unavailable("The printer does not have a valid bed mesh.", safeResponse);
        }

        String source;
        int marker;
        marker = lower.indexOf("compensated bilinear leveling grid:");
        if (marker >= 0) {
            source = "compensated";
        } else {
            marker = lower.indexOf("raw bilinear leveling grid:");
            if (marker >= 0) {
                source = "raw";
            } else {
                marker = lower.indexOf("mesh bed level data:");
                if (marker >= 0) {
                    source = "mesh";
                } else {
                    marker = lower.indexOf("bed level correction matrix:");
                    if (marker >= 0) {
                        source = "matrix";
                    } else {
                        return Result.unavailable("No mesh matrix was found in the M420 V response.", safeResponse);
                    }
                }
            }
        }

        String body = normalized.substring(marker);
        String[] lines = body.split("\n", -1);
        List<List<Double>> rows = "matrix".equals(source)
                ? parseMatrix(lines)
                : parseGrid(lines);
        rows = rectangularRows(rows);
        if (rows.size() < 2 || rows.get(0).size() < 2) {
            return Result.unavailable("The M420 V response did not contain a complete mesh matrix.", safeResponse);
        }

        double minimum = Double.POSITIVE_INFINITY;
        double maximum = Double.NEGATIVE_INFINITY;
        int values = 0;
        for (List<Double> row : rows) {
            for (Double value : row) {
                if (value == null || value.isNaN() || value.isInfinite()) continue;
                minimum = Math.min(minimum, value);
                maximum = Math.max(maximum, value);
                values++;
            }
        }
        if (values == 0) {
            return Result.unavailable("The mesh contains no finite measurements.", safeResponse);
        }

        Boolean levelingActive = null;
        if (lower.contains("bed leveling on")) levelingActive = true;
        else if (lower.contains("bed leveling off")) levelingActive = false;
        List<List<Double>> surfaceValues = catmullRomSurface(rows, SURFACE_SUBDIVISIONS);
        return new Result(
                true,
                "",
                source,
                rows,
                surfaceValues,
                "derived",
                source,
                "catmull-rom",
                SURFACE_SUBDIVISIONS,
                minimum,
                maximum,
                levelingActive,
                safeResponse
        );
    }

    private static List<List<Double>> parseGrid(String[] lines) {
        List<List<Double>> rows = new ArrayList<>();
        int headingLine = -1;
        for (int lineIndex = 1; lineIndex < lines.length; lineIndex++) {
            String trimmed = lines[lineIndex].trim();
            if (trimmed.isEmpty()) continue;
            // A heading belongs to this section only when it is the first
            // non-blank line after the section marker. Searching farther can
            // accidentally consume the following subdivided/compensated grid
            // when firmware log lines were dropped.
            headingLine = lineIndex;
            break;
        }
        if (headingLine < 0) return rows;

        String[] headingTokens = lines[headingLine].trim().split("\\s+");
        if (headingTokens.length < 2 || headingTokens.length > MAX_DIMENSION) return rows;
        for (int column = 0; column < headingTokens.length; column++) {
            if (!UNSIGNED_INTEGER.matcher(headingTokens[column]).matches()
                    || Integer.parseInt(headingTokens[column]) != column) {
                return rows;
            }
        }
        int columns = headingTokens.length;

        for (int lineIndex = headingLine + 1;
            lineIndex < lines.length && rows.size() < columns;
             lineIndex++) {
            String line = lines[lineIndex];
            String trimmedLine = line.trim();
            // Firmware log messages commonly end in a newline; when streamed
            // message-by-message this leaves a blank line between every row.
            if (trimmedLine.isEmpty()) continue;
            if (isAnotherSection(trimmedLine)) break;
            // SACP StringProp payloads can retain a trailing NUL/control byte.
            // String.trim() removes that framing without changing numeric data.
            Matcher rowMatcher = GRID_ROW.matcher(trimmedLine);
            if (!rowMatcher.matches()
                    || Integer.parseInt(rowMatcher.group(1)) != rows.size()) return Collections.emptyList();

            String[] tokens = rowMatcher.group(2).trim().split("\\s+");
            if (tokens.length != columns) return Collections.emptyList();
            List<Double> row = new ArrayList<>();
            for (String token : tokens) {
                if ("nan".equalsIgnoreCase(token) || token.matches("=+")) {
                    row.add(null);
                } else if (NUMBER.matcher(token).matches()) {
                    row.add(Double.parseDouble(token));
                } else {
                    return Collections.emptyList();
                }
            }
            rows.add(row);
        }
        return rows.size() == columns ? rows : Collections.emptyList();
    }

    private static List<List<Double>> parseMatrix(String[] lines) {
        List<List<Double>> rows = new ArrayList<>();
        for (int lineIndex = 1; lineIndex < lines.length && rows.size() < 3; lineIndex++) {
            String line = lines[lineIndex].trim();
            if (line.isEmpty()) {
                if (!rows.isEmpty()) break;
                continue;
            }
            Matcher matcher = NUMBER.matcher(line);
            List<Double> row = new ArrayList<>();
            while (matcher.find() && row.size() < 4) {
                row.add(Double.parseDouble(matcher.group()));
            }
            if (row.size() == 3) rows.add(row);
        }
        return rows;
    }

    private static boolean isAnotherSection(String line) {
        String lower = line.toLowerCase(Locale.US);
        return lower.contains("leveling grid:")
                || lower.startsWith("echo:")
                || lower.startsWith("error:");
    }

    private static List<List<Double>> rectangularRows(List<List<Double>> candidates) {
        if (candidates.isEmpty()) return Collections.emptyList();
        int columns = candidates.get(0).size();
        List<List<Double>> result = new ArrayList<>();
        for (List<Double> row : candidates) {
            if (row.size() != columns) break;
            result.add(Collections.unmodifiableList(new ArrayList<>(row)));
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Reproduces Marlin's {@code bed_level_virt_interpolate()} surface from the
     * parsed controller grid (normally the authoritative compensated grid).
     * The controller emits this surface after the 9x9 grids, but its 25 rows
     * cannot be captured reliably through the bounded firmware-log queue, so
     * deriving it locally avoids a partial 3D surface while retaining the
     * controller grid unchanged for the 2D view.
     */
    private static List<List<Double>> catmullRomSurface(
            List<List<Double>> source,
            int subdivisions
    ) {
        if (source.size() < 2 || source.get(0).size() < 2 || subdivisions < 1) {
            return Collections.emptyList();
        }

        int sourceRows = source.size();
        int sourceColumns = source.get(0).size();
        int surfaceRows = (sourceRows - 1) * subdivisions + 1;
        int surfaceColumns = (sourceColumns - 1) * subdivisions + 1;
        List<List<Double>> mutableSurface = new ArrayList<>(surfaceRows);
        for (int row = 0; row < surfaceRows; row++) {
            mutableSurface.add(new ArrayList<>(Collections.nCopies(surfaceColumns, (Double) null)));
        }

        for (int sourceY = 0; sourceY < sourceRows; sourceY++) {
            for (int sourceX = 0; sourceX < sourceColumns; sourceX++) {
                for (int offsetY = 0; offsetY < subdivisions; offsetY++) {
                    if (offsetY > 0 && sourceY == sourceRows - 1) continue;
                    for (int offsetX = 0; offsetX < subdivisions; offsetX++) {
                        if (offsetX > 0 && sourceX == sourceColumns - 1) continue;
                        Double value = catmullRom2d(
                                source,
                                sourceX + 1,
                                sourceY + 1,
                                (double) offsetX / subdivisions,
                                (double) offsetY / subdivisions
                        );
                        mutableSurface.get(sourceY * subdivisions + offsetY)
                                .set(sourceX * subdivisions + offsetX, value);
                    }
                }
            }
        }
        return rectangularRows(mutableSurface);
    }

    /** Mirrors Marlin's separable {@code bed_level_virt_2cmr()}. */
    private static Double catmullRom2d(
            List<List<Double>> source,
            int x,
            int y,
            double tx,
            double ty
    ) {
        Double[] row = new Double[4];
        for (int i = 0; i < 4; i++) {
            Double[] column = new Double[4];
            for (int j = 0; j < 4; j++) {
                column[j] = virtualCoordinate(source, i + x - 1, j + y - 1);
            }
            row[i] = catmullRom(column, ty);
        }
        return catmullRom(row, tx);
    }

    /** Mirrors Marlin's edge extrapolation in {@code bed_level_virt_coord()}. */
    private static Double virtualCoordinate(List<List<Double>> source, int x, int y) {
        int sourceColumns = source.get(0).size();
        int sourceRows = source.size();
        if (x > sourceColumns + 1 || y > sourceRows + 1) return 0.0;

        if (x == 0 || x == sourceColumns + 1) {
            int edgeX = x == 0 ? 0 : sourceColumns - 1;
            int innerX = x == 0 ? 1 : sourceColumns - 2;
            if (y >= 1 && y <= sourceRows) {
                return linearExtrapolation(
                        source.get(y - 1).get(edgeX),
                        source.get(y - 1).get(innerX)
                );
            }
            return linearExtrapolation(
                    virtualCoordinate(source, edgeX + 1, y),
                    virtualCoordinate(source, innerX + 1, y)
            );
        }

        if (y == 0 || y == sourceRows + 1) {
            int edgeY = y == 0 ? 0 : sourceRows - 1;
            int innerY = y == 0 ? 1 : sourceRows - 2;
            if (x >= 1 && x <= sourceColumns) {
                return linearExtrapolation(
                        source.get(edgeY).get(x - 1),
                        source.get(innerY).get(x - 1)
                );
            }
            return linearExtrapolation(
                    virtualCoordinate(source, x, edgeY + 1),
                    virtualCoordinate(source, x, innerY + 1)
            );
        }
        return finiteOrNull(source.get(y - 1).get(x - 1));
    }

    private static Double linearExtrapolation(Double edge, Double inner) {
        if (!isFinite(edge) || !isFinite(inner)) return null;
        return edge * 2.0 - inner;
    }

    /** Mirrors Marlin's {@code bed_level_virt_cmr()} polynomial. */
    private static Double catmullRom(Double[] points, double t) {
        for (Double point : points) {
            if (!isFinite(point)) return null;
        }
        double squared = t * t;
        return (
                points[0] * -t * (1.0 - t) * (1.0 - t)
                        + points[1] * (2.0 - 5.0 * squared + 3.0 * t * squared)
                        + points[2] * t * (1.0 + 4.0 * t - 3.0 * squared)
                        - points[3] * squared * (1.0 - t)
        ) * 0.5;
    }

    private static Double finiteOrNull(Double value) {
        return isFinite(value) ? value : null;
    }

    private static boolean isFinite(Double value) {
        return value != null && !value.isNaN() && !value.isInfinite();
    }

    static final class Result {
        final boolean available;
        final String message;
        final String source;
        final List<List<Double>> values;
        final List<List<Double>> surfaceValues;
        final String surfaceSource;
        final String surfaceDerivedFrom;
        final String surfaceInterpolation;
        final int surfaceSubdivisions;
        final double minimum;
        final double maximum;
        final Boolean levelingActive;
        final String rawResponse;

        private Result(
                boolean available,
                String message,
                String source,
                List<List<Double>> values,
                List<List<Double>> surfaceValues,
                String surfaceSource,
                String surfaceDerivedFrom,
                String surfaceInterpolation,
                int surfaceSubdivisions,
                double minimum,
                double maximum,
                Boolean levelingActive,
                String rawResponse
        ) {
            this.available = available;
            this.message = message;
            this.source = source;
            this.values = values;
            this.surfaceValues = surfaceValues;
            this.surfaceSource = surfaceSource;
            this.surfaceDerivedFrom = surfaceDerivedFrom;
            this.surfaceInterpolation = surfaceInterpolation;
            this.surfaceSubdivisions = surfaceSubdivisions;
            this.minimum = minimum;
            this.maximum = maximum;
            this.levelingActive = levelingActive;
            this.rawResponse = rawResponse;
        }

        static Result unavailable(String message, String rawResponse) {
            return new Result(
                    false,
                    message,
                    "",
                    Collections.emptyList(),
                    Collections.emptyList(),
                    "",
                    "",
                    "",
                    0,
                    Double.NaN,
                    Double.NaN,
                    null,
                    rawResponse
            );
        }
    }
}
