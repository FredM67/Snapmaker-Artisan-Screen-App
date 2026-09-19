package fabscreen.platform.base.obico;

import org.junit.Test;

import java.net.InetAddress;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ObicoGcodeDownloaderTest {
    @Test
    public void cancellationIsIdempotentAndCompletesExactlyOnceBeforeWorkStarts() {
        AtomicReference<Runnable> queuedWork = new AtomicReference<>();
        Executor queuedExecutor = queuedWork::set;
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger errorCount = new AtomicInteger();
        AtomicReference<String> error = new AtomicReference<>();
        ObicoGcodeDownloader downloader = new ObicoGcodeDownloader(
                new OkHttpClient(),
                queuedExecutor,
                host -> new InetAddress[] { InetAddress.getByName("1.1.1.1") });

        ObicoGcodeDownloader.DownloadHandle handle = downloader.download(
                new java.io.File("unused-test-directory"),
                "https://app.obico.io",
                false,
                "https://files.example.test/model.gcode",
                "model.gcode",
                new ObicoGcodeDownloader.ResultCallback() {
                    @Override
                    public void onSuccess(java.io.File file) {
                        successCount.incrementAndGet();
                    }

                    @Override
                    public void onError(String safeMessage) {
                        errorCount.incrementAndGet();
                        error.set(safeMessage);
                    }
                });

        handle.cancel();
        handle.cancel();
        queuedWork.get().run();

        assertTrue(handle.isCancelled());
        assertEquals(0, successCount.get());
        assertEquals(1, errorCount.get());
        assertEquals("The G-code download was cancelled.", error.get());
    }

    @Test
    public void sanitizesBasenameAndAllowsOnlyGcode() {
        assertEquals(
                "benchy.gcode",
                ObicoGcodeDownloader.sanitizeGcodeFilename("../../benchy.gcode"));
        assertEquals(
                "part_name_.gcode",
                ObicoGcodeDownloader.sanitizeGcodeFilename("C:\\uploads\\part:name?.GCODE"));
        assertEquals(
                "model.gcode",
                ObicoGcodeDownloader.sanitizeGcodeFilename(" ...model.gcode "));

        assertFilenameRejected("archive.gcode.zip");
        assertFilenameRejected("../.gcode");
        assertFilenameRejected("../");
        assertFilenameRejected(null);
    }

    @Test
    public void permitsHttpsAndRestrictsInsecureDownloadsToConfiguredOrigin() {
        HttpUrl cloud = ObicoGcodeDownloader.validateServerUrl("https://app.obico.io", false);
        assertEquals(
                "https://files.example.test/job.gcode?signature=abc",
                ObicoGcodeDownloader.validateDownloadUrl(
                        cloud,
                        false,
                        "https://files.example.test/job.gcode?signature=abc").toString());
        assertUrlRejected(cloud, false, "http://files.example.test/job.gcode");
        assertUrlRejected(cloud, true, "http://app.obico.io/job.gcode");
        assertUrlRejected(cloud, false, "https://user:secret@files.example.test/job.gcode");
        assertUrlRejected(cloud, false, "https://files.example.test/job.gcode#fragment");

        HttpUrl selfHosted = ObicoGcodeDownloader.validateServerUrl(
                "http://192.168.1.20:3334",
                true);
        assertEquals(
                "http://192.168.1.20:3334/media/job.gcode",
                ObicoGcodeDownloader.validateDownloadUrl(
                        selfHosted,
                        true,
                        "http://192.168.1.20:3334/media/job.gcode").toString());
        assertUrlRejected(selfHosted, true, "http://192.168.1.20:8080/job.gcode");
        assertUrlRejected(selfHosted, true, "http://192.168.1.21:3334/job.gcode");
    }

    @Test
    public void identifiesNonPublicNetworkAddresses() throws Exception {
        assertTrue(ObicoGcodeDownloader.isNonPublicAddress(
                InetAddress.getByName("127.0.0.1")));
        assertTrue(ObicoGcodeDownloader.isNonPublicAddress(
                InetAddress.getByName("10.2.3.4")));
        assertTrue(ObicoGcodeDownloader.isNonPublicAddress(
                InetAddress.getByName("100.64.0.1")));
        assertTrue(ObicoGcodeDownloader.isNonPublicAddress(
                InetAddress.getByName("169.254.169.254")));
        assertTrue(ObicoGcodeDownloader.isNonPublicAddress(
                InetAddress.getByName("192.168.1.7")));
        assertTrue(ObicoGcodeDownloader.isNonPublicAddress(
                InetAddress.getByName("fc00::1")));
        assertFalse(ObicoGcodeDownloader.isNonPublicAddress(
                InetAddress.getByName("1.1.1.1")));
        assertFalse(ObicoGcodeDownloader.isNonPublicAddress(
                InetAddress.getByName("2606:4700:4700::1111")));
    }

    @Test
    public void rejectsPrivateTargetUnlessItIsConfiguredSelfHostedOrigin() throws Exception {
        HttpUrl server = HttpUrl.parse("http://192.168.1.20:3334");
        InetAddress[] privateAddress = { InetAddress.getByName("192.168.1.20") };

        ObicoGcodeDownloader.validateResolvedTarget(
                server,
                HttpUrl.parse("http://192.168.1.20:3334/media/job.gcode"),
                privateAddress);
        assertResolvedRejected(
                server,
                HttpUrl.parse("https://192.168.1.20/media/job.gcode"),
                privateAddress);
        assertResolvedRejected(
                server,
                HttpUrl.parse("http://192.168.1.20:8080/media/job.gcode"),
                privateAddress);

        ObicoGcodeDownloader.validateResolvedTarget(
                server,
                HttpUrl.parse("https://files.example.test/job.gcode"),
                new InetAddress[] { InetAddress.getByName("1.1.1.1") });
    }

    @Test
    public void originComparisonIncludesSchemeHostAndPort() {
        HttpUrl first = HttpUrl.parse("https://EXAMPLE.test/path");
        assertTrue(ObicoGcodeDownloader.sameOrigin(
                first,
                HttpUrl.parse("https://example.test/other")));
        assertFalse(ObicoGcodeDownloader.sameOrigin(
                first,
                HttpUrl.parse("http://example.test/other")));
        assertFalse(ObicoGcodeDownloader.sameOrigin(
                first,
                HttpUrl.parse("https://example.test:8443/other")));
    }

    private static void assertFilenameRejected(String value) {
        try {
            ObicoGcodeDownloader.sanitizeGcodeFilename(value);
            fail("Expected filename to be rejected: " + value);
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void assertUrlRejected(
            HttpUrl server,
            boolean allowInsecure,
            String value) {
        try {
            ObicoGcodeDownloader.validateDownloadUrl(server, allowInsecure, value);
            fail("Expected URL to be rejected: " + value);
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void assertResolvedRejected(
            HttpUrl server,
            HttpUrl target,
            InetAddress[] addresses) {
        try {
            ObicoGcodeDownloader.validateResolvedTarget(server, target, addresses);
            fail("Expected private target to be rejected: " + target);
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
