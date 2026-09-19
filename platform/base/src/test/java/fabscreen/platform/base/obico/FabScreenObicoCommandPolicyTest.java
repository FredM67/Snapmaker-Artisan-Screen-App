package fabscreen.platform.base.obico;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FabScreenObicoCommandPolicyTest {
    @Test
    public void permitsOnlyCommandMatchingExactPrintState() {
        assertTrue(decision(ObicoRemoteCommand.Type.PAUSE, 2).isAccepted());
        assertTrue(decision(ObicoRemoteCommand.Type.RESUME, 4).isAccepted());
        assertTrue(decision(ObicoRemoteCommand.Type.CANCEL, 2).isAccepted());
        assertTrue(decision(ObicoRemoteCommand.Type.CANCEL, 4).isAccepted());

        assertFalse(decision(ObicoRemoteCommand.Type.PAUSE, 4).isAccepted());
        assertFalse(decision(ObicoRemoteCommand.Type.RESUME, 2).isAccepted());
        assertFalse(decision(ObicoRemoteCommand.Type.CANCEL, 0).isAccepted());
        assertFalse(decision(ObicoRemoteCommand.Type.PAUSE, 3).isAccepted());
    }

    @Test
    public void blocksControlsUnlessExplicitlyEnabled() {
        assertFalse(FabScreenObicoCommandPolicy.evaluate(
                ObicoRemoteCommand.Type.PAUSE,
                false,
                true,
                true,
                false,
                false,
                2
        ).isAccepted());
    }

    @Test
    public void blocksDisconnectedEmergencyNonFdmAndConcurrentCommands() {
        assertFalse(evaluate(false, true, false, false).isAccepted());
        assertFalse(evaluate(true, false, false, false).isAccepted());
        assertFalse(evaluate(true, true, true, false).isAccepted());
        assertFalse(evaluate(true, true, false, true).isAccepted());
    }

    private static FabScreenObicoCommandPolicy.Decision decision(
            ObicoRemoteCommand.Type command,
            int machineStatus
    ) {
        return FabScreenObicoCommandPolicy.evaluate(
                command,
                true,
                true,
                true,
                false,
                false,
                machineStatus
        );
    }

    private static FabScreenObicoCommandPolicy.Decision evaluate(
            boolean connected,
            boolean fdmMode,
            boolean emergencyStop,
            boolean commandPending
    ) {
        return FabScreenObicoCommandPolicy.evaluate(
                ObicoRemoteCommand.Type.PAUSE,
                true,
                connected,
                fdmMode,
                emergencyStop,
                commandPending,
                2
        );
    }
}
