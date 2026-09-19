package fabscreen.platform.base.camera;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.HttpUrl;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MjpegStreamReaderTest {
    private static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, 1, 2, (byte) 0xff, (byte) 0xd9};

    @Test
    public void acceptsPrivateLanUrlWithQueryAndRejectsUserInfoOrNonLanUrls() {
        assertEquals("http://192.168.1.24:81/stream",
                MjpegStreamReader.validateUrl("http://192.168.1.24:81/stream"));
        assertEquals("http://192.168.1.24:81/stream?raw=1",
                MjpegStreamReader.validateUrl("http://192.168.1.24:81/stream?raw=1"));
        assertRejected("http://127.0.0.1:81/stream");
        assertRejected("http://169.254.1.1/stream");
        assertRejected("http://8.8.8.8/stream");
        assertRejected("http://224.0.0.1/stream");
        assertRejected("http://user:pass@192.168.1.24/stream");
        assertRejected("http://user:pass@192.168.1.24/stream?raw=1");
        assertRejected("http://192.168.1.24/stream#fragment");
        assertRejected("http://192.168.1.24/stream?raw=1#fragment");
        assertRejected("file:///tmp/stream");
        assertRejected(" http://192.168.1.24/stream");
        assertRejected("http://192.168.1.24/stream?" + repeat('x', 2048));
        assertFalse(MjpegStreamReader.isAllowedLanAddress(address("127.0.0.1")));
        assertFalse(MjpegStreamReader.isAllowedLanAddress(address("8.8.8.8")));
        assertTrue(MjpegStreamReader.isAllowedLanAddress(address("10.1.2.3")));
        assertTrue(MjpegStreamReader.isAllowedLanAddress(address("172.16.0.1")));
        assertTrue(MjpegStreamReader.isAllowedLanAddress(address("192.168.1.24")));
        assertTrue(MjpegStreamReader.isAllowedLanAddress(address("fd00::1")));
        assertFalse(MjpegStreamReader.isAllowedLanAddress(address("fe80::1")));
    }

    @Test
    public void findsSameOriginImageInCameraHtmlViewer() {
        HttpUrl viewer = HttpUrl.parse("http://192.168.1.24:81/stream");
        assertEquals("http://192.168.1.24:81/stream?raw=1",
                MjpegStreamReader.sameOriginImageUrl(viewer,
                        "<!doctype html><img src=\"/stream?raw=1\">").toString());
        assertEquals("http://192.168.1.24:81/stream?raw=1&size=large",
                MjpegStreamReader.sameOriginImageUrl(viewer,
                        "<IMG alt='Camera' SRC='/stream?raw=1&amp;size=large'>").toString());
    }

    @Test
    public void htmlViewerCannotSendReaderToAnotherOriginOrNonHttpUrl() {
        HttpUrl viewer = HttpUrl.parse("http://192.168.1.24:81/stream");
        assertNull(MjpegStreamReader.sameOriginImageUrl(viewer,
                "<img src='http://192.168.1.25:81/stream'>"));
        assertNull(MjpegStreamReader.sameOriginImageUrl(viewer,
                "<img src='http://192.168.1.24:82/stream'>"));
        assertNull(MjpegStreamReader.sameOriginImageUrl(viewer,
                "<img src='https://192.168.1.24:81/stream'>"));
        assertNull(MjpegStreamReader.sameOriginImageUrl(viewer,
                "<img src='http://user:pass@192.168.1.24:81/stream'>"));
        assertNull(MjpegStreamReader.sameOriginImageUrl(viewer,
                "<img src='/stream?raw=1#fragment'>"));
        assertNull(MjpegStreamReader.sameOriginImageUrl(viewer,
                "<img src='javascript:alert(1)'>"));
        assertNull(MjpegStreamReader.sameOriginImageUrl(viewer,
                "<img data-src='/stream?raw=1'>"));
        assertEquals("http://192.168.1.24:81/stream?raw=1",
                MjpegStreamReader.sameOriginImageUrl(viewer,
                        "<img src='http://192.168.1.25:81/stream'>"
                                + "<img src='/stream?raw=1'>").toString());
        assertNull(MjpegStreamReader.sameOriginImageUrl(viewer,
                "<img src='/stream?raw=1'>" + repeat('x', 32 * 1024)));
        assertNull(MjpegStreamReader.sameOriginImageUrl(viewer,
                "<img src='/" + repeat('x', 2040) + "'>"));
    }

    @Test
    public void requiresBoundedMultipartContentTypeAndBoundary() throws Exception {
        assertEquals("frame", MjpegStreamReader.multipartBoundary(
                "multipart/x-mixed-replace; boundary=frame"));
        assertEquals("frame", MjpegStreamReader.multipartBoundary(
                "Multipart/X-Mixed-Replace; boundary=\"frame\""));
        assertIOException(() -> MjpegStreamReader.multipartBoundary("image/jpeg"));
        assertIOException(() -> MjpegStreamReader.multipartBoundary(
                "multipart/x-mixed-replace; boundary="));
        assertIOException(() -> MjpegStreamReader.multipartBoundary(
                "multipart/x-mixed-replace; boundary=a b"));
    }

    @Test
    public void readsMultipleLengthDelimitedJpegsOnOneStream() throws Exception {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        write(wire, "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: 6\r\n\r\n");
        wire.write(JPEG);
        write(wire, "\r\n--frame\r\nContent-Length: 6\r\n\r\n");
        wire.write(JPEG);
        MjpegStreamReader.MultipartParser parser = parser(wire, "frame");
        assertArrayEquals(JPEG, parser.readFrame());
        assertArrayEquals(JPEG, parser.readFrame());
    }

    @Test
    public void readsJpegWithoutContentLengthUntilValidatedBoundary() throws Exception {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        write(wire, "--frame\r\nContent-Type: image/jpeg\r\n\r\n");
        wire.write(JPEG);
        write(wire, "\r\n--frame\r\nContent-Type: image/jpeg\r\n\r\n");
        wire.write(JPEG);
        write(wire, "\r\n--frame--\r\n");
        MjpegStreamReader.MultipartParser parser = parser(wire, "frame");
        assertArrayEquals(JPEG, parser.readFrame());
        assertArrayEquals(JPEG, parser.readFrame());
        assertIOException(parser::readFrame);
    }

    @Test
    public void boundaryLikeBytesInsideJpegDoNotEndFrame() throws Exception {
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        jpeg.write(0xff);
        jpeg.write(0xd8);
        write(jpeg, "\r\n--frameXstill-jpeg");
        jpeg.write(0xff);
        jpeg.write(0xd9);
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        write(wire, "--frame\r\nContent-Type: image/jpeg\r\n\r\n");
        wire.write(jpeg.toByteArray());
        write(wire, "\r\n--frame--\r\n");
        assertArrayEquals(jpeg.toByteArray(), parser(wire, "frame").readFrame());
    }

    @Test
    public void toleratesLegacyBoundaryParameterWithLeadingHyphens() throws Exception {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        write(wire, "--frame\r\nContent-Length: 6\r\n\r\n");
        wire.write(JPEG);
        MjpegStreamReader.MultipartParser parser = parser(wire, "--frame");
        assertArrayEquals(JPEG, parser.readFrame());
    }

    @Test
    public void rejectsOversizedHeaderLengthBeforeAllocatingFrame() throws Exception {
        MjpegStreamReader.MultipartParser parser = parser(
                "--frame\r\nContent-Length: 8388609\r\n\r\n", "frame");
        assertIOException(parser::readFrame);
    }

    @Test
    public void rejectsTruncatedOrNonJpegFrame() throws Exception {
        assertIOException(parser("--frame\r\nContent-Length: 6\r\n\r\nabc", "frame")::readFrame);
        assertIOException(parser("--frame\r\nContent-Length: 6\r\n\r\nabcdef", "frame")::readFrame);
        assertIOException(parser("--frame\r\nContent-Type: text/plain\r\n\r\nabcdef", "frame")::readFrame);
    }

    @Test
    public void closeBeforeReadPreventsNetworkRequest() throws Exception {
        MjpegStreamReader reader = new MjpegStreamReader("http://192.168.1.24:81/stream");
        reader.close();
        reader.close();
        assertIOException(reader::readFrame);
    }

    @Test
    public void concurrentCloseDefersResponseCleanupUntilReadEnds() throws Exception {
        MjpegStreamReader reader = new MjpegStreamReader("http://192.168.1.24:81/stream");
        PipedInputStream input = new PipedInputStream();
        PipedOutputStream output = new PipedOutputStream(input);
        write(output, "--frame\r\nContent-Length: 6\r\n\r\n");
        Field parserField = MjpegStreamReader.class.getDeclaredField("parser");
        parserField.setAccessible(true);
        parserField.set(reader, new MjpegStreamReader.MultipartParser(input, "frame"));
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                reader.readFrame();
            } catch (Throwable error) {
                outcome.set(error);
            }
        });
        worker.start();
        try {
            AtomicBoolean reading = readerFlag(reader, "reading");
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (!reading.get() && System.nanoTime() < deadline) Thread.yield();
            assertTrue("Reader did not start", reading.get());

            reader.close();
            assertFalse("Response cleanup raced the active read", readerFlag(reader, "resourcesClosed").get());
            output.write(JPEG);
            output.flush();
            worker.join(2000L);
            assertFalse("Reader did not stop", worker.isAlive());
            assertTrue(outcome.get() instanceof IOException);
            assertTrue(readerFlag(reader, "resourcesClosed").get());
        } finally {
            output.close();
            reader.close();
            worker.join(1000L);
            input.close();
        }
    }

    private static AtomicBoolean readerFlag(MjpegStreamReader reader, String name) throws Exception {
        Field field = MjpegStreamReader.class.getDeclaredField(name);
        field.setAccessible(true);
        return (AtomicBoolean) field.get(reader);
    }

    private static MjpegStreamReader.MultipartParser parser(String wire, String boundary) {
        return new MjpegStreamReader.MultipartParser(
                new ByteArrayInputStream(wire.getBytes(StandardCharsets.US_ASCII)), boundary);
    }

    private static MjpegStreamReader.MultipartParser parser(ByteArrayOutputStream wire, String boundary) {
        return new MjpegStreamReader.MultipartParser(
                new ByteArrayInputStream(wire.toByteArray()), boundary);
    }

    private static void write(java.io.OutputStream stream, String value) throws IOException {
        stream.write(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static String repeat(char value, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int i = 0; i < count; i++) builder.append(value);
        return builder.toString();
    }

    private static InetAddress address(String value) {
        try {
            return InetAddress.getByName(value);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static void assertRejected(String url) {
        try {
            MjpegStreamReader.validateUrl(url);
            fail("Expected camera URL to be rejected: " + url);
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private interface IoOperation {
        void run() throws IOException;
    }

    private static void assertIOException(IoOperation operation) {
        try {
            operation.run();
            fail("Expected IOException");
        } catch (IOException expected) {
            // Expected.
        }
    }
}
