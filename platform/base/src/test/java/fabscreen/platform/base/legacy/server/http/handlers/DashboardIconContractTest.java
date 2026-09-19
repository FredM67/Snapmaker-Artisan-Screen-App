package fabscreen.platform.base.legacy.server.http.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DashboardIconContractTest {
    @Test
    public void dashboardHeadLinksToBrowserAndHomeScreenIcons() throws Exception {
        String html = readAsset("artisan_dashboard.html");
        assertTrue(html.contains("rel=\"icon\" type=\"image/png\" sizes=\"32x32\" href=\"/favicon-32.png\""));
        assertTrue(html.contains("rel=\"apple-touch-icon\" sizes=\"180x180\" href=\"/apple-touch-icon.png\""));
        assertTrue(html.contains("rel=\"manifest\" href=\"/manifest.webmanifest\""));
    }

    @Test
    public void manifestStartsAtDashboardAndReferencesBundledIcons() throws Exception {
        JsonObject manifest = new JsonParser().parse(readAsset("artisan.webmanifest")).getAsJsonObject();
        assertEquals("/", manifest.get("id").getAsString());
        assertEquals("/#/dashboard", manifest.get("start_url").getAsString());
        assertEquals("/", manifest.get("scope").getAsString());
        assertEquals("standalone", manifest.get("display").getAsString());
        assertTrue(manifest.get("name").getAsString().length() > 0);
        assertTrue(manifest.get("short_name").getAsString().length() > 0);

        JsonArray icons = manifest.getAsJsonArray("icons");
        assertEquals(2, icons.size());
        assertManifestIcon(icons.get(0).getAsJsonObject(), "/icon-192.png", 192);
        assertManifestIcon(icons.get(1).getAsJsonObject(), "/icon-512.png", 512);
    }

    @Test
    public void allIconFilesHaveExpectedPngDimensions() throws Exception {
        assertPngSize("artisan-icon-32.png", 32);
        assertPngSize("artisan-icon-180.png", 180);
        assertPngSize("artisan-icon-192.png", 192);
        assertPngSize("artisan-icon-512.png", 512);
    }

    @Test
    public void requestHandlerServesEveryLinkedAssetAtItsExpectedPath() throws Exception {
        String handler = readHandler();
        assertRoute(handler, "URI_FAVICON_32", "/favicon-32.png", "artisan-icon-32.png", "image/png");
        assertRoute(handler, "URI_APPLE_TOUCH_ICON", "/apple-touch-icon.png", "artisan-icon-180.png", "image/png");
        assertRoute(handler, "URI_ICON_192", "/icon-192.png", "artisan-icon-192.png", "image/png");
        assertRoute(handler, "URI_ICON_512", "/icon-512.png", "artisan-icon-512.png", "image/png");
        assertRoute(handler, "URI_WEB_MANIFEST", "/manifest.webmanifest", "artisan.webmanifest", "application/manifest+json");
    }

    private static void assertManifestIcon(JsonObject icon, String src, int size) {
        assertEquals(src, icon.get("src").getAsString());
        assertEquals(size + "x" + size, icon.get("sizes").getAsString());
        assertEquals("image/png", icon.get("type").getAsString());
        assertTrue(icon.get("purpose").getAsString().contains("maskable"));
    }

    private static void assertPngSize(String fileName, int size) throws Exception {
        Path path = assetPath(fileName);
        assertTrue("Missing " + fileName, Files.isRegularFile(path));
        byte[] png = Files.readAllBytes(path);
        assertTrue("Invalid PNG header " + fileName, png.length >= 24
                && (png[0] & 0xff) == 137 && png[1] == 80 && png[2] == 78
                && png[3] == 71 && png[4] == 13 && png[5] == 10
                && png[6] == 26 && png[7] == 10);
        assertEquals(fileName + " width", size, pngInt(png, 16));
        assertEquals(fileName + " height", size, pngInt(png, 20));
    }

    private static int pngInt(byte[] png, int offset) {
        return ((png[offset] & 0xff) << 24) | ((png[offset + 1] & 0xff) << 16)
                | ((png[offset + 2] & 0xff) << 8) | (png[offset + 3] & 0xff);
    }

    private static void assertRoute(
            String handler, String constant, String path, String asset, String mediaType) {
        assertTrue(handler.contains("private static final String " + constant + " = \"" + path + "\""));
        assertTrue(handler.contains("@GetMapping(value = " + constant + ", produces = \"" + mediaType + "\")"));
        assertTrue(handler.contains("writeDashboardStaticAsset(response, \"" + asset + "\""));
    }

    private static String readAsset(String fileName) throws Exception {
        return new String(Files.readAllBytes(assetPath(fileName)), StandardCharsets.UTF_8);
    }

    private static Path assetPath(String fileName) {
        Path path = Paths.get("src/main/assets", fileName);
        if (!Files.isRegularFile(path)) {
            path = Paths.get("platform/base/src/main/assets", fileName);
        }
        return path;
    }

    private static String readHandler() throws Exception {
        Path path = Paths.get("src/main/java/fabscreen/platform/base/legacy/server/http/handlers/OrcaRequestHandler.java");
        if (!Files.isRegularFile(path)) {
            path = Paths.get("platform/base/src/main/java/fabscreen/platform/base/legacy/server/http/handlers/OrcaRequestHandler.java");
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
