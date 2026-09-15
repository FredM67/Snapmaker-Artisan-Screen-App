package fabscreen.platform.base.legacy.server.http.handlers;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Strict, platform-independent validation for dashboard local/USB file selectors. */
final class DashboardGcodeFileSelector {
    private static final int MAX_RELATIVE_PATH_BYTES = 4096;
    private static final int MAX_SEGMENT_BYTES = 255;

    private DashboardGcodeFileSelector() {
    }

    static Result parse(String source, String relativePath) {
        if (!"local".equals(source) && !"usb".equals(source)) {
            return Result.invalid("Parameter source must be local or usb.");
        }
        if (relativePath == null
                || relativePath.isEmpty()
                || relativePath.getBytes(StandardCharsets.UTF_8).length > MAX_RELATIVE_PATH_BYTES
                || relativePath.startsWith("/")
                || relativePath.endsWith("/")
                || relativePath.indexOf('\\') >= 0
                || relativePath.indexOf('\0') >= 0) {
            return Result.invalid("Parameter path must be a safe relative .gcode path.");
        }

        String[] segments = relativePath.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty()
                    || ".".equals(segment)
                    || "..".equals(segment)
                    || ("local".equals(source) && ".dashboard-staging".equals(segment))
                    || segment.getBytes(StandardCharsets.UTF_8).length > MAX_SEGMENT_BYTES) {
                return Result.invalid("Parameter path must not contain empty, dot, or oversized segments.");
            }
            for (int index = 0; index < segment.length(); index++) {
                if (Character.isISOControl(segment.charAt(index))) {
                    return Result.invalid("Parameter path contains control characters.");
                }
            }
        }

        String name = segments[segments.length - 1];
        if (!name.toLowerCase(Locale.US).endsWith(".gcode")) {
            return Result.invalid("Only .gcode files can be selected.");
        }
        return Result.valid(new Selection(source, relativePath, name));
    }

    static File resolveContainedFile(File root, Selection selection) throws IOException {
        File canonicalRoot = root.getCanonicalFile();
        File candidate = new File(
                canonicalRoot,
                selection.relativePath.replace('/', File.separatorChar)
        ).getCanonicalFile();
        String rootPath = canonicalRoot.getPath();
        String candidatePath = candidate.getPath();
        if (!candidatePath.startsWith(rootPath + File.separator)) {
            throw new IOException("Selected file resolves outside its storage root.");
        }
        return candidate;
    }

    static String fingerprint(Selection selection, long sizeBytes, long modifiedAt) {
        return selection.source + "\n"
                + selection.relativePath + "\n"
                + sizeBytes + "\n"
                + modifiedAt;
    }

    static final class Selection {
        final String source;
        final String relativePath;
        final String name;

        Selection(String source, String relativePath, String name) {
            this.source = source;
            this.relativePath = relativePath;
            this.name = name;
        }
    }

    static final class Result {
        final boolean valid;
        final Selection selection;
        final String error;

        private Result(boolean valid, Selection selection, String error) {
            this.valid = valid;
            this.selection = selection;
            this.error = error;
        }

        static Result valid(Selection selection) {
            return new Result(true, selection, "");
        }

        static Result invalid(String error) {
            return new Result(false, null, error);
        }
    }
}
