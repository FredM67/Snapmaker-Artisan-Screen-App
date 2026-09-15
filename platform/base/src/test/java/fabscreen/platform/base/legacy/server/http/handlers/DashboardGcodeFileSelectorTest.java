package fabscreen.platform.base.legacy.server.http.handlers;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DashboardGcodeFileSelectorTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void acceptsExactSourcesAndNestedGcodePaths() {
        DashboardGcodeFileSelector.Result local =
                DashboardGcodeFileSelector.parse("local", "jobs/Benchy.GCODE");
        DashboardGcodeFileSelector.Result usb =
                DashboardGcodeFileSelector.parse("usb", "folder one/cube.gcode");

        assertTrue(local.valid);
        assertEquals("Benchy.GCODE", local.selection.name);
        assertTrue(usb.valid);
        assertEquals("folder one/cube.gcode", usb.selection.relativePath);
    }

    @Test
    public void rejectsUnknownSourcesAbsolutePathsAndTraversal() {
        assertFalse(DashboardGcodeFileSelector.parse("LOCAL", "cube.gcode").valid);
        assertFalse(DashboardGcodeFileSelector.parse("network", "cube.gcode").valid);
        assertFalse(DashboardGcodeFileSelector.parse("local", "/cube.gcode").valid);
        assertFalse(DashboardGcodeFileSelector.parse("local", "../cube.gcode").valid);
        assertFalse(DashboardGcodeFileSelector.parse("local", "jobs/./cube.gcode").valid);
        assertFalse(DashboardGcodeFileSelector.parse("local", "jobs\\cube.gcode").valid);
    }

    @Test
    public void rejectsEmptySegmentsControlsAndNonGcodeFiles() {
        assertFalse(DashboardGcodeFileSelector.parse("local", "jobs//cube.gcode").valid);
        assertFalse(DashboardGcodeFileSelector.parse("local", "jobs/cube.gcode/").valid);
        assertFalse(DashboardGcodeFileSelector.parse("local", "jobs/cu\u0001be.gcode").valid);
        assertFalse(DashboardGcodeFileSelector.parse("local", "jobs/readme.txt").valid);
        assertFalse(DashboardGcodeFileSelector.parse(
                "local", ".dashboard-staging/request-1/cube.gcode").valid);
    }

    @Test
    public void canonicalResolutionStaysInsideRoot() throws IOException {
        File root = temporaryFolder.newFolder("root");
        File nested = new File(root, "jobs");
        assertTrue(nested.mkdir());
        File file = new File(nested, "cube.gcode");
        assertTrue(file.createNewFile());
        DashboardGcodeFileSelector.Selection selection =
                DashboardGcodeFileSelector.parse("local", "jobs/cube.gcode").selection;

        assertEquals(file.getCanonicalFile(),
                DashboardGcodeFileSelector.resolveContainedFile(root, selection));
    }

    @Test
    public void fingerprintChangesWhenFileRevisionChanges() {
        DashboardGcodeFileSelector.Selection selection =
                DashboardGcodeFileSelector.parse("usb", "cube.gcode").selection;

        String first = DashboardGcodeFileSelector.fingerprint(selection, 100L, 200L);
        String changedSize = DashboardGcodeFileSelector.fingerprint(selection, 101L, 200L);
        String changedTime = DashboardGcodeFileSelector.fingerprint(selection, 100L, 201L);

        assertFalse(first.equals(changedSize));
        assertFalse(first.equals(changedTime));
    }
}
