package fabscreen.platform.base.camera;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Reads JPEG frames from one persistent, LAN-only multipart MJPEG response.
 *
 * <p>One capture thread calls {@link #readFrame()} repeatedly. {@link #close()}
 * is safe from another thread and cancels a blocked connect or read. A failed
 * stream is terminal; create a new reader to reconnect. This class deliberately
 * does not accept URL-embedded credentials, forward redirects, or connect to
 * public, loopback, link-local, or multicast addresses.</p>
 */
public final class MjpegStreamReader implements AutoCloseable {
    public static final int MAX_FRAME_BYTES = 8 * 1024 * 1024;
    private static final int CONNECT_TIMEOUT_SECONDS = 15;
    private static final int READ_TIMEOUT_SECONDS = 15;
    private static final int MAX_URL_LENGTH = 2048;
    private static final int MAX_HTML_BYTES = 32 * 1024;
    private static final int MAX_HTML_READ_SECONDS = 25;
    private static final int MAX_BOUNDARY_LENGTH = 70;
    private static final int MAX_LINE_BYTES = 1024;
    private static final int MAX_HEADER_BYTES = 8192;
    private static final int MAX_HEADER_LINES = 40;
    private static final Pattern IMAGE_TAG = Pattern.compile("<img\\b[^>]{0,2048}>", Pattern.CASE_INSENSITIVE);
    private static final Pattern IMAGE_SOURCE = Pattern.compile(
            "(?:^|\\s)src\\s*=\\s*(?:\"([^\"]{1,2048})\"|'([^']{1,2048})'|([^\\s>]{1,2048}))",
            Pattern.CASE_INSENSITIVE);

    private final HttpUrl url;
    private final OkHttpClient client;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean reading = new AtomicBoolean();
    private final AtomicBoolean resourcesClosed = new AtomicBoolean();
    private volatile Call call;
    private volatile Response response;
    private volatile MultipartParser parser;

    public MjpegStreamReader(String streamUrl) {
        this.url = HttpUrl.parse(validateUrl(streamUrl));
        this.client = new OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                // Bypass system proxies so every destination passes through LanOnlyDns.
                .proxy(Proxy.NO_PROXY)
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .dns(new LanOnlyDns())
                .build();
    }

    /**
     * Validates settings without performing DNS on the caller's thread.
     * DNS answers are independently checked and pinned for the HTTP call.
     */
    public static String validateUrl(String value) {
        if (value == null || value.length() == 0 || value.length() > MAX_URL_LENGTH
                || !value.equals(value.trim())) {
            throw new IllegalArgumentException("Enter a valid IP camera stream URL");
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("Enter a valid IP camera stream URL", error);
        }
        if (uri.getRawUserInfo() != null || uri.getRawFragment() != null
                || uri.isOpaque() || uri.getHost() == null) {
            throw new IllegalArgumentException("Camera URL must not contain credentials or a fragment");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("Camera URL must use HTTP or HTTPS");
        }
        HttpUrl parsed = HttpUrl.parse(value);
        if (parsed == null || !parsed.username().isEmpty() || !parsed.password().isEmpty()) {
            throw new IllegalArgumentException("Enter a valid IP camera stream URL");
        }
        String host = parsed.host();
        if (isNumericHost(host)) {
            try {
                if (!isAllowedLanAddress(InetAddress.getByName(host))) {
                    throw new IllegalArgumentException("Camera URL must point to a private LAN address");
                }
            } catch (UnknownHostException error) {
                throw new IllegalArgumentException("Invalid camera address", error);
            }
        }
        return parsed.toString();
    }

    /** Returns the next complete JPEG, or throws on malformed or interrupted streams. */
    public byte[] readFrame() throws IOException {
        if (closed.get()) throw new IOException("Camera stream is closed");
        if (!reading.compareAndSet(false, true)) {
            throw new IllegalStateException("Only one thread may read the camera stream");
        }
        try {
            MultipartParser current = parser;
            if (current == null) current = connect();
            byte[] frame = current.readFrame();
            if (closed.get()) throw new IOException("Camera stream is closed");
            return frame;
        } catch (IOException error) {
            close();
            throw error;
        } finally {
            reading.set(false);
            if (closed.get()) closeResources();
        }
    }

    private MultipartParser connect() throws IOException {
        if (closed.get()) throw new IOException("Camera stream is closed");
        HttpUrl destination = url;
        for (int hop = 0; hop < 2; hop++) {
            Request request = new Request.Builder()
                    .url(destination)
                    .header("Accept", "multipart/x-mixed-replace, text/html")
                    .header("Cache-Control", "no-cache")
                    .build();
            Call pending = client.newCall(request);
            call = pending;
            if (closed.get()) pending.cancel();
            Response opened = pending.execute();
            if (closed.get()) {
                opened.close();
                throw new IOException("Camera stream is closed");
            }
            if (!opened.isSuccessful()) {
                int code = opened.code();
                opened.close();
                throw new IOException("IP camera returned HTTP " + code);
            }
            ResponseBody body = opened.body();
            if (body == null) {
                opened.close();
                throw new IOException("IP camera response has no body");
            }
            String contentType = opened.header("Content-Type");
            if (isHtml(contentType)) {
                if (hop != 0) {
                    opened.close();
                    throw new IOException("IP camera viewer did not lead to an MJPEG stream");
                }
                try {
                    destination = sameOriginImageUrl(destination, readBoundedHtml(body));
                } finally {
                    opened.close();
                }
                if (destination == null) {
                    throw new IOException("IP camera viewer has no same-origin MJPEG image URL");
                }
                if (closed.get()) throw new IOException("Camera stream is closed");
                continue;
            }
            String boundary;
            try {
                boundary = multipartBoundary(contentType);
            } catch (IOException error) {
                opened.close();
                throw error;
            }
            response = opened;
            MultipartParser created = new MultipartParser(body.byteStream(), boundary);
            parser = created;
            if (closed.get()) {
                opened.close();
                throw new IOException("Camera stream is closed");
            }
            return created;
        }
        throw new IOException("IP camera viewer did not lead to an MJPEG stream");
    }

    private static boolean isHtml(String contentType) {
        return contentType != null && contentType.split(";", 2)[0].trim().equalsIgnoreCase("text/html");
    }

    private String readBoundedHtml(ResponseBody body) throws IOException {
        if (body.contentLength() > MAX_HTML_BYTES) {
            throw new IOException("IP camera viewer page is too large");
        }
        long deadlineNanos = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(MAX_HTML_READ_SECONDS);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        InputStream input = body.byteStream();
        for (int count; (count = input.read(chunk)) != -1;) {
            if (closed.get()) throw new IOException("Camera stream is closed");
            if (System.nanoTime() >= deadlineNanos) {
                throw new IOException("IP camera viewer page timed out");
            }
            if (output.size() + count > MAX_HTML_BYTES) {
                throw new IOException("IP camera viewer page is too large");
            }
            output.write(chunk, 0, count);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Only follows an image reference on the original camera's scheme, host, and port. */
    static HttpUrl sameOriginImageUrl(HttpUrl viewerUrl, String html) {
        if (viewerUrl == null || html == null || html.length() > MAX_HTML_BYTES) return null;
        Matcher tags = IMAGE_TAG.matcher(html);
        while (tags.find()) {
            Matcher attribute = IMAGE_SOURCE.matcher(tags.group());
            if (!attribute.find()) continue;
            String source = attribute.group(1) != null ? attribute.group(1)
                    : attribute.group(2) != null ? attribute.group(2) : attribute.group(3);
            if (source == null || source.isEmpty() || source.length() > MAX_URL_LENGTH) continue;
            HttpUrl candidate = viewerUrl.resolve(source.replace("&amp;", "&"));
            if (candidate != null && candidate.scheme().equals(viewerUrl.scheme())
                    && candidate.host().equals(viewerUrl.host())
                    && candidate.port() == viewerUrl.port()
                    && candidate.username().isEmpty() && candidate.password().isEmpty()
                    && candidate.fragment() == null
                    && candidate.toString().length() <= MAX_URL_LENGTH) {
                return candidate;
            }
        }
        return null;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        Call pending = call;
        if (pending != null) pending.cancel();
        // Okio's response body must not be closed concurrently with readFrame().
        if (!reading.get()) closeResources();
    }

    private void closeResources() {
        if (!resourcesClosed.compareAndSet(false, true)) return;
        Response opened = response;
        try {
            if (opened != null) opened.close();
        } catch (RuntimeException ignored) {
            // Call.cancel() already closed the socket; cleanup must not fail a source switch.
        } finally {
            client.dispatcher().executorService().shutdown();
            client.connectionPool().evictAll();
        }
    }

    static String multipartBoundary(String contentType) throws IOException {
        if (contentType == null) throw new IOException("IP camera did not return an MJPEG stream");
        String[] parts = contentType.split(";");
        if (parts.length == 0 || !"multipart/x-mixed-replace".equalsIgnoreCase(parts[0].trim())) {
            throw new IOException("IP camera did not return an MJPEG stream");
        }
        for (int index = 1; index < parts.length; index++) {
            String part = parts[index].trim();
            int equals = part.indexOf('=');
            if (equals < 0 || !"boundary".equalsIgnoreCase(part.substring(0, equals).trim())) continue;
            String value = part.substring(equals + 1).trim();
            if (value.length() >= 2 && value.charAt(0) == '"'
                    && value.charAt(value.length() - 1) == '"') {
                value = value.substring(1, value.length() - 1);
            }
            if (value.length() == 0 || value.length() > MAX_BOUNDARY_LENGTH) break;
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c < 0x21 || c > 0x7e || c == '"' || c == ';') {
                    throw new IOException("IP camera sent an invalid MJPEG boundary");
                }
            }
            return value;
        }
        throw new IOException("IP camera did not specify an MJPEG boundary");
    }

    private static boolean isNumericHost(String host) {
        if (host.indexOf(':') >= 0) return true;
        if (!host.matches("[0-9.]+")) return false;
        return true;
    }

    static boolean isAllowedLanAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isMulticastAddress()) return false;
        if (address instanceof Inet6Address) {
            byte[] bytes = address.getAddress();
            return (bytes[0] & 0xfe) == 0xfc; // IPv6 unique-local fc00::/7.
        }
        return address.isSiteLocalAddress(); // RFC1918 IPv4 ranges.
    }

    private static final class LanOnlyDns implements Dns {
        @Override
        public List<InetAddress> lookup(String hostname) throws UnknownHostException {
            List<InetAddress> resolved = Dns.SYSTEM.lookup(hostname);
            List<InetAddress> accepted = new ArrayList<>(resolved.size());
            for (InetAddress address : resolved) {
                if (!isAllowedLanAddress(address)) {
                    throw new UnknownHostException("Camera hostname does not resolve only to private LAN addresses");
                }
                accepted.add(address);
            }
            if (accepted.isEmpty()) throw new UnknownHostException("Camera hostname has no LAN address");
            return accepted;
        }
    }

    /** Separated from HTTP to permit exhaustive deterministic parser tests. */
    static final class MultipartParser {
        private final PushbackInputStream input;
        private final String declaredDelimiter;
        private final String alternativeDelimiter;
        private String activeDelimiter;

        MultipartParser(InputStream input, String boundary) {
            this.input = new PushbackInputStream(
                    new BufferedInputStream(input, 32 * 1024), MAX_BOUNDARY_LENGTH + 8);
            this.declaredDelimiter = "--" + boundary;
            // Some cameras incorrectly include the leading "--" in boundary=.
            this.alternativeDelimiter = boundary.startsWith("--") ? boundary : null;
        }

        byte[] readFrame() throws IOException {
            seekBoundary();
            int contentLength = readPartHeaders();
            byte[] jpeg = contentLength >= 0 ? readLengthFrame(contentLength) : readBoundaryFrame();
            if (jpeg.length < 4 || (jpeg[0] & 0xff) != 0xff || (jpeg[1] & 0xff) != 0xd8
                    || (jpeg[jpeg.length - 2] & 0xff) != 0xff
                    || (jpeg[jpeg.length - 1] & 0xff) != 0xd9) {
                throw new IOException("IP camera returned an invalid JPEG frame");
            }
            return jpeg;
        }

        private void seekBoundary() throws IOException {
            int bytes = 0;
            while (bytes < MAX_HEADER_BYTES) {
                String line = readLine();
                bytes += line.length() + 2;
                if (line.equals(declaredDelimiter) || line.equals(alternativeDelimiter)) {
                    activeDelimiter = line;
                    return;
                }
                if (line.equals(declaredDelimiter + "--")
                        || (alternativeDelimiter != null && line.equals(alternativeDelimiter + "--"))) {
                    throw new IOException("IP camera stream ended");
                }
            }
            throw new IOException("IP camera MJPEG boundary was not found");
        }

        private int readPartHeaders() throws IOException {
            int total = 0;
            int count = 0;
            int contentLength = -1;
            while (count++ < MAX_HEADER_LINES && total < MAX_HEADER_BYTES) {
                String line = readLine();
                total += line.length() + 2;
                if (line.length() == 0) return contentLength;
                int colon = line.indexOf(':');
                if (colon <= 0) throw new IOException("Malformed MJPEG frame header");
                String name = line.substring(0, colon).trim().toLowerCase(Locale.US);
                String value = line.substring(colon + 1).trim();
                if ("content-type".equals(name) && !value.toLowerCase(Locale.US).startsWith("image/jpeg")) {
                    throw new IOException("IP camera returned a non-JPEG frame");
                }
                if ("content-length".equals(name)) {
                    try {
                        contentLength = Integer.parseInt(value);
                    } catch (NumberFormatException error) {
                        throw new IOException("Invalid MJPEG frame length", error);
                    }
                    if (contentLength < 4 || contentLength > MAX_FRAME_BYTES) {
                        throw new IOException("MJPEG frame exceeds the size limit");
                    }
                }
            }
            throw new IOException("MJPEG frame headers exceed the size limit");
        }

        private byte[] readLengthFrame(int length) throws IOException {
            byte[] bytes = new byte[length];
            int offset = 0;
            while (offset < length) {
                int count = input.read(bytes, offset, length - offset);
                if (count < 0) throw new IOException("IP camera truncated a JPEG frame");
                if (count == 0) continue;
                offset += count;
            }
            return bytes;
        }

        private byte[] readBoundaryFrame() throws IOException {
            byte[] delimiter = ("\r\n" + activeDelimiter).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            ByteArrayOutputStream frame = new ByteArrayOutputStream(64 * 1024);
            byte[] probe = new byte[delimiter.length + 2];
            while (frame.size() <= MAX_FRAME_BYTES) {
                int next = input.read();
                if (next < 0) throw new IOException("IP camera truncated a JPEG frame");
                if (next != '\r') {
                    frame.write(next);
                    continue;
                }
                probe[0] = (byte) next;
                int read = 1;
                while (read < probe.length) {
                    int value = input.read();
                    if (value < 0) break;
                    probe[read++] = (byte) value;
                }
                boolean matches = read == probe.length;
                if (matches) {
                    for (int i = 0; i < delimiter.length; i++) {
                        if (probe[i] != delimiter[i]) {
                            matches = false;
                            break;
                        }
                    }
                    matches = matches && ((probe[delimiter.length] == '\r'
                            && probe[delimiter.length + 1] == '\n')
                            || (probe[delimiter.length] == '-'
                            && probe[delimiter.length + 1] == '-'));
                }
                if (matches) {
                    input.unread(probe, 0, read);
                    return frame.toByteArray();
                }
                frame.write('\r');
                if (read > 1) input.unread(probe, 1, read - 1);
            }
            throw new IOException("MJPEG frame exceeds the size limit");
        }

        private String readLine() throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream(64);
            while (line.size() < MAX_LINE_BYTES) {
                int value = input.read();
                if (value < 0) throw new IOException("IP camera stream ended");
                if (value == '\n') {
                    byte[] bytes = line.toByteArray();
                    int length = bytes.length;
                    if (length > 0 && bytes[length - 1] == '\r') length--;
                    return new String(bytes, 0, length, java.nio.charset.StandardCharsets.US_ASCII);
                }
                line.write(value);
            }
            throw new IOException("MJPEG header line exceeds the size limit");
        }
    }
}
