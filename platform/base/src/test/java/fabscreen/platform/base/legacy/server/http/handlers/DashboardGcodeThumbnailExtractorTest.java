package fabscreen.platform.base.legacy.server.http.handlers;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DashboardGcodeThumbnailExtractorTest {
    @Test
    public void selectsLargestCompleteOrcaThumbnailBeforeExecutableBlock() throws Exception {
        String gcode = "; HEADER_BLOCK_START\n"
                + "; thumbnail begin 16x16 8\n"
                + "; QUJDRA==\n"
                + "; thumbnail end\n"
                + "; thumbnail_png begin 600x600 8\n"
                + "; RUZHSA==\n"
                + "; thumbnail_png end\n"
                + "; EXECUTABLE_BLOCK_START\n"
                + "; thumbnail begin 900x900 8\n"
                + "; SUdOT1JF\n"
                + "; thumbnail end\n";

        DashboardGcodeThumbnailExtractor.Result result = extract(gcode);

        assertTrue(result.found());
        assertEquals("RUZHSA==", result.encodedData);
        assertEquals(600, result.declaredWidth);
        assertEquals(600, result.declaredHeight);
        assertFalse(result.limitReached);
        assertFalse(result.timedOut);
    }

    @Test
    public void supportsLegacySingleLinePngDataUri() throws Exception {
        DashboardGcodeThumbnailExtractor.Result result = extract(
                ";Header Start\n"
                        + ";thumbnail: data:image/png;base64,QUJDRA==\n"
                        + "G28\n"
        );

        assertTrue(result.found());
        assertEquals("QUJDRA==", result.encodedData);
        assertEquals(0, result.declaredWidth);
        assertEquals(0, result.declaredHeight);
    }

    @Test
    public void acceptsUtf8BomBeforeFirstThumbnailComment() throws Exception {
        DashboardGcodeThumbnailExtractor.Result result = extract(
                "\ufeff;thumbnail: data:image/png;base64,QUJDRA==\nG28\n"
        );

        assertTrue(result.found());
        assertEquals("QUJDRA==", result.encodedData);
    }

    @Test
    public void rejectsUnsupportedDataUriAndNeverScansPastOrcaExecutableMarker()
            throws Exception {
        DashboardGcodeThumbnailExtractor.Result result = extract(
                ";thumbnail: data:text/html;base64,QUJDRA==\n"
                        + "; EXECUTABLE_BLOCK_START\n"
                        + ";thumbnail: data:image/png;base64,RUZHSA==\n"
        );

        assertFalse(result.found());
    }

    @Test
    public void neverScansGenericExecutableGcodeForLateThumbnailComments() throws Exception {
        DashboardGcodeThumbnailExtractor.Result result = extract(
                "; normal header\n"
                        + "G28\n"
                        + ";thumbnail: data:image/png;base64,RUZHSA==\n"
        );

        assertFalse(result.found());
    }

    @Test
    public void incompleteOrcaBlockIsNotReturned() throws Exception {
        DashboardGcodeThumbnailExtractor.Result result = extract(
                "; thumbnail begin 600x600 12\n"
                        + "; QUJDRA==\n"
                        + "; thumbnail end\n"
        );

        assertFalse(result.found());
    }

    private DashboardGcodeThumbnailExtractor.Result extract(String value) throws Exception {
        return DashboardGcodeThumbnailExtractor.extract(new ByteArrayInputStream(
                value.getBytes(StandardCharsets.UTF_8)
        ));
    }
}
