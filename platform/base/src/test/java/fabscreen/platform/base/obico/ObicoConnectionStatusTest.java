package fabscreen.platform.base.obico;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ObicoConnectionStatusTest {
    @Test
    public void watchRequestAndSuccessfulSnapshotRemainIndependent() {
        ObicoConnectionStatus initial = ObicoConnectionStatus.disabled()
                .withConnection(ObicoConnectionStatus.State.CONNECTED, "Connected", 0, true);
        ObicoConnectionStatus watching = initial.withRemoteWatch(true, true, 1234L);

        assertTrue(watching.isRemoteViewing());
        assertTrue(watching.isRemoteShouldWatch());
        assertEquals(1234L, watching.getLastRemoteWatchAtMillis());
        assertEquals(0L, watching.getLastSnapshotAtMillis());

        ObicoConnectionStatus uploaded = watching.withSnapshot(5678L);
        assertEquals(5678L, uploaded.getLastSnapshotAtMillis());
        assertTrue(uploaded.isRemoteShouldWatch());

        ObicoConnectionStatus disconnected = uploaded.withRemoteWatch(false, false, 0L);
        assertFalse(disconnected.isRemoteViewing());
        assertFalse(disconnected.isRemoteShouldWatch());
        assertEquals(0L, disconnected.getLastRemoteWatchAtMillis());
        assertEquals(5678L, disconnected.getLastSnapshotAtMillis());
    }
}
