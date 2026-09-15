package fabscreen.platform.base.legacy.server.http.handlers;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import fabscreen.platform.base.lib.parser.OrcaThumbnailBlockParser;

/**
 * Reads only the bounded, comment-only prefix needed to locate an embedded G-code thumbnail.
 * It deliberately does not invoke the full G-code parser or inspect executable commands.
 */
final class DashboardGcodeThumbnailExtractor {
    static final int MAX_SCAN_BYTES = 2 * 1024 * 1024;
    static final int MAX_ENCODED_CHARACTERS = 2 * 1024 * 1024;
    static final long MAX_SCAN_TIME_MS = 2_500L;

    private static final int BUFFER_BYTES = 32 * 1024;

    private DashboardGcodeThumbnailExtractor() {
    }

    static Result extract(InputStream source) throws IOException {
        if (source == null) throw new IOException("Missing thumbnail source.");
        BoundedAsciiLineReader reader = new BoundedAsciiLineReader(
                source,
                MAX_SCAN_BYTES,
                MAX_SCAN_TIME_MS
        );
        OrcaThumbnailBlockParser orca = new OrcaThumbnailBlockParser();
        Candidate best = null;
        String line;
        boolean firstLine = true;
        while ((line = reader.readLine()) != null) {
            String trimmed = line.trim();
            if (firstLine && !trimmed.isEmpty() && trimmed.charAt(0) == '\ufeff') {
                trimmed = trimmed.substring(1).trim();
                line = trimmed;
            }
            firstLine = false;
            if ("; EXECUTABLE_BLOCK_START".equalsIgnoreCase(trimmed)) break;

            if (orca.consumeLine(line)) {
                OrcaThumbnailBlockParser.Result completed = orca.takeCompleted();
                if (completed != null
                        && completed.getEncodedData().length() <= MAX_ENCODED_CHARACTERS) {
                    Candidate candidate = new Candidate(
                            completed.getEncodedData(),
                            completed.getWidth(),
                            completed.getHeight()
                    );
                    if (best == null || candidate.rank > best.rank) best = candidate;
                }
                continue;
            }

            Candidate legacy = parseLegacyThumbnail(line);
            if (legacy != null && (best == null || legacy.rank > best.rank)) best = legacy;
            // Legacy/Luban thumbnails live in their comment header. Once real G-code starts,
            // the Files list must not walk the executable body looking for a late comment.
            if (!trimmed.isEmpty() && !trimmed.startsWith(";")) break;
        }
        return new Result(best, reader.limitReached, reader.timedOut, reader.bytesRead);
    }

    private static Candidate parseLegacyThumbnail(String line) {
        if (line == null) return null;
        String trimmed = line.trim();
        if (!trimmed.startsWith(";")) return null;
        String comment = trimmed.substring(1).trim();
        int delimiter = comment.indexOf(':');
        if (delimiter <= 0
                || !"thumbnail".equals(comment.substring(0, delimiter).trim()
                .toLowerCase(Locale.US))) return null;

        String payload = comment.substring(delimiter + 1).trim();
        int comma = payload.indexOf(',');
        if (comma >= 0) {
            String descriptor = payload.substring(0, comma).trim().toLowerCase(Locale.US);
            if (!descriptor.isEmpty()
                    && !(descriptor.endsWith(";base64")
                    && (descriptor.contains("image/png")
                    || descriptor.contains("image/jpeg")
                    || descriptor.contains("image/jpg")))) return null;
            payload = payload.substring(comma + 1).trim();
        }
        if (payload.isEmpty()
                || payload.length() > MAX_ENCODED_CHARACTERS
                || !isBase64(payload)) return null;
        return new Candidate(payload, 0, 0);
    }

    private static boolean isBase64(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!((character >= 'A' && character <= 'Z')
                    || (character >= 'a' && character <= 'z')
                    || (character >= '0' && character <= '9')
                    || character == '+'
                    || character == '/'
                    || character == '=')) return false;
        }
        return true;
    }

    static final class Result {
        final String encodedData;
        final int declaredWidth;
        final int declaredHeight;
        final boolean limitReached;
        final boolean timedOut;
        final int scannedBytes;

        Result(Candidate candidate, boolean limitReached, boolean timedOut, int scannedBytes) {
            encodedData = candidate == null ? null : candidate.encodedData;
            declaredWidth = candidate == null ? 0 : candidate.width;
            declaredHeight = candidate == null ? 0 : candidate.height;
            this.limitReached = limitReached;
            this.timedOut = timedOut;
            this.scannedBytes = scannedBytes;
        }

        boolean found() {
            return encodedData != null && !encodedData.isEmpty();
        }
    }

    private static final class Candidate {
        final String encodedData;
        final int width;
        final int height;
        final long rank;

        Candidate(String encodedData, int width, int height) {
            this.encodedData = encodedData;
            this.width = width;
            this.height = height;
            long area = (long) width * height;
            // Legacy one-line thumbnails do not declare dimensions; encoded size is still a
            // useful deterministic preference if a malformed file contains more than one.
            rank = area > 0L ? area : encodedData.length();
        }
    }

    private static final class BoundedAsciiLineReader {
        private final BufferedInputStream input;
        private final int maximumBytes;
        private final long deadlineNanos;
        int bytesRead;
        boolean limitReached;
        boolean timedOut;

        BoundedAsciiLineReader(InputStream input, int maximumBytes, long timeoutMs) {
            this.input = new BufferedInputStream(input, BUFFER_BYTES);
            this.maximumBytes = maximumBytes;
            deadlineNanos = System.nanoTime() + timeoutMs * 1_000_000L;
        }

        String readLine() throws IOException {
            if (limitReached || timedOut) return null;
            ByteArrayOutputStream line = new ByteArrayOutputStream(256);
            while (true) {
                if (bytesRead >= maximumBytes) {
                    limitReached = true;
                    return null;
                }
                if ((bytesRead & 0xfff) == 0 && System.nanoTime() > deadlineNanos) {
                    timedOut = true;
                    return null;
                }
                int value = input.read();
                if (value < 0) {
                    return line.size() == 0 ? null
                    : new String(line.toByteArray(), StandardCharsets.UTF_8);
                }
                bytesRead++;
                if (value == '\n') {
                    return new String(line.toByteArray(), StandardCharsets.UTF_8);
                }
                if (value != '\r') line.write(value);
            }
        }
    }
}
