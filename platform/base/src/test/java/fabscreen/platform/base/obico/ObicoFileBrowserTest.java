package fabscreen.platform.base.obico;

import com.google.gson.JsonObject;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ObicoFileBrowserTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void listsArtisanAndUsbInObicosOctoPrintShape() throws Exception {
        File local = temp.newFolder("local");
        File usb = temp.newFolder("usb");
        File localFile = write(new File(local, "benchy.gcode"));
        write(new File(local, "notes.txt"));
        File designs = new File(local, "Designs");
        assertTrue(designs.mkdir());
        write(new File(designs, "part.GCODE"));
        write(new File(usb, "usb-job.gcode"));
        localFile.setLastModified(1_600_000_000_000L);

        JsonObject root = ObicoFileBrowser.listFiles(local, usb, null, false, 1, null)
                .getAsJsonObject("local");
        assertTrue(root.has("artisan"));
        assertTrue(root.has("usb"));
        assertEquals("Artisan storage", root.getAsJsonObject("artisan")
                .get("display").getAsString());

        JsonObject artisan = ObicoFileBrowser.listFiles(local, usb, "artisan", false, 1, null)
                .getAsJsonObject("local");
        JsonObject benchy = artisan.getAsJsonObject("benchy.gcode");
        assertEquals("artisan/benchy.gcode", benchy.get("path").getAsString());
        assertEquals("machinecode", benchy.get("type").getAsString());
        assertEquals(localFile.length(), benchy.get("size").getAsLong());
        assertEquals(1_600_000_000L, benchy.get("date").getAsLong());
        assertFalse(artisan.has("notes.txt"));
        assertTrue(artisan.getAsJsonObject("Designs")
                .getAsJsonObject("children").has("part.GCODE"));

        JsonObject usbFiles = ObicoFileBrowser.listFiles(local, usb, "usb", false, 1, null)
                .getAsJsonObject("local");
        assertEquals("usb/usb-job.gcode", usbFiles.getAsJsonObject("usb-job.gcode")
                .get("path").getAsString());
    }

    @Test
    public void searchReturnsOnlyMatchingGcodeAcrossStorage() throws Exception {
        File local = temp.newFolder("local");
        File usb = temp.newFolder("usb");
        File designs = new File(local, "Designs");
        assertTrue(designs.mkdir());
        write(new File(designs, "spiral.gcode"));
        write(new File(local, "other.gcode"));
        write(new File(usb, "SPIRAL-USB.GCODE"));

        JsonObject files = ObicoFileBrowser.listFiles(local, usb, null, true, null, "spiral")
                .getAsJsonObject("local");
        assertEquals(2, files.entrySet().size());
        assertTrue(files.has("artisan/Designs/spiral.gcode"));
        assertTrue(files.has("usb/SPIRAL-USB.GCODE"));
    }

    @Test
    public void selectedPathsAreStrictAndStayWithinStorage() throws Exception {
        File local = temp.newFolder("local");
        File file = write(new File(local, "good.gcode"));
        assertEquals(file.getCanonicalFile(),
                ObicoFileBrowser.resolve(local, "good.gcode"));
        assertEquals("good.gcode", ObicoFileBrowser.parsePath(
                "artisan/good.gcode", true).relativePath);

        String[] invalid = {
                "artisan/../outside.gcode",
                "artisan//good.gcode",
                "artisan/./good.gcode",
                "artisan/hidden/../../good.gcode",
                "artisan\\good.gcode",
                "/artisan/good.gcode",
                "remote/good.gcode",
                "artisan/.dashboard-staging/good.gcode",
                "artisan/.hidden.gcode",
                "artisan/not-a-gcode.txt",
                "artisan/good.gcode/",
                "artisan/good\ngcode"
        };
        for (String path : invalid) {
            try {
                ObicoFileBrowser.parsePath(path, true);
                fail("Accepted unsafe path: " + path);
            } catch (IOException expected) {
                // Expected.
            }
        }
    }

    private static File write(File file) throws IOException {
        Files.write(file.toPath(), "G1 X1\n".getBytes(StandardCharsets.UTF_8));
        return file;
    }
}
