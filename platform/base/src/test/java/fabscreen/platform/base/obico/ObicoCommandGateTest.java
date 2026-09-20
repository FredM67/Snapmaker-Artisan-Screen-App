package fabscreen.platform.base.obico;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ObicoCommandGateTest {
    @Test
    public void suppressesReplayByServerId() {
        ObicoCommandGate gate = new ObicoCommandGate();
        ObicoRemoteCommand first = new ObicoRemoteCommand(
                ObicoRemoteCommand.Type.PAUSE,
                "server-command-1",
                "id:server-command-1");
        ObicoRemoteCommand replay = new ObicoRemoteCommand(
                ObicoRemoteCommand.Type.PAUSE,
                "server-command-1",
                "id:server-command-1");

        assertEquals(ObicoCommandGate.Decision.ALLOWED, gate.evaluate(first, 10_000L));
        assertEquals(ObicoCommandGate.Decision.DUPLICATE, gate.evaluate(replay, 11_000L));
    }

    @Test
    public void rateLimitsSameCommandTypeButAllowsDifferentTypes() {
        ObicoCommandGate gate = new ObicoCommandGate();
        assertEquals(
                ObicoCommandGate.Decision.ALLOWED,
                gate.evaluate(command(ObicoRemoteCommand.Type.PAUSE, "one"), 10_000L));
        assertEquals(
                ObicoCommandGate.Decision.RATE_LIMITED,
                gate.evaluate(command(ObicoRemoteCommand.Type.PAUSE, "two"), 11_000L));
        assertEquals(
                ObicoCommandGate.Decision.ALLOWED,
                gate.evaluate(command(ObicoRemoteCommand.Type.CANCEL, "three"), 11_100L));
        assertEquals(
                ObicoCommandGate.Decision.ALLOWED,
                gate.evaluate(command(ObicoRemoteCommand.Type.PAUSE, "four"), 12_100L));
    }

    @Test
    public void permitsSameFingerprintAfterReplayWindow() {
        ObicoCommandGate gate = new ObicoCommandGate();
        ObicoRemoteCommand command = command(ObicoRemoteCommand.Type.RESUME, "resume-1");
        assertEquals(ObicoCommandGate.Decision.ALLOWED, gate.evaluate(command, 10_000L));
        assertEquals(ObicoCommandGate.Decision.ALLOWED, gate.evaluate(command, 40_000L));
    }

    private static ObicoRemoteCommand command(ObicoRemoteCommand.Type type, String id) {
        return new ObicoRemoteCommand(type, id, "id:" + id);
    }
}
