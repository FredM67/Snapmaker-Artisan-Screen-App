package fabscreen.platform.base.obico;

import org.junit.Test;

import fabscreen.platform.base.service.IMachine;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ObicoNativeCommandPolicyTest {
    @Test
    public void requiresExplicitPermissionAndHealthyFdmConnection() {
        assertFalse(decision(ObicoPassthruRequest.Type.JOG, false, true,
                true, true, false, false, true, false, 0).isAccepted());
        assertFalse(decision(ObicoPassthruRequest.Type.DOWNLOAD_FILE, true, false,
                true, true, false, false, true, false, 0).isAccepted());
        assertFalse(decision(ObicoPassthruRequest.Type.HOME, true, true,
                false, true, false, false, false, false, 0).isAccepted());
        assertFalse(decision(ObicoPassthruRequest.Type.HOME, true, true,
                true, false, false, false, false, false, 0).isAccepted());
        assertFalse(decision(ObicoPassthruRequest.Type.HOME, true, true,
                true, true, true, false, false, false, 0).isAccepted());
        assertFalse(decision(ObicoPassthruRequest.Type.HOME, true, true,
                true, true, false, true, false, false, 0).isAccepted());
    }

    @Test
    public void motionUsesOriginalIdleAndHomingRules() {
        assertTrue(decision(ObicoPassthruRequest.Type.JOG, true, true,
                true, true, false, false, true, false, 0).isAccepted());
        assertFalse(decision(ObicoPassthruRequest.Type.JOG, true, true,
                true, true, false, false, false, false, 0).isAccepted());
        assertFalse(decision(ObicoPassthruRequest.Type.JOG, true, true,
                true, true, false, false, true, false, 2).isAccepted());

        assertTrue(decision(ObicoPassthruRequest.Type.HOME, true, true,
                true, true, false, false, false, false, 0).isAccepted());
        assertFalse(decision(ObicoPassthruRequest.Type.HOME, true, true,
                true, true, false, false, false, true, 0).isAccepted());
    }

    @Test
    public void extrusionDoesNotInventAnXyzHomingBlocker() {
        assertTrue(decision(ObicoPassthruRequest.Type.EXTRUDE, true, true,
                true, true, false, false, false, false, 0).isAccepted());
        assertFalse(decision(ObicoPassthruRequest.Type.EXTRUDE, true, true,
                true, true, false, false, true, false, 2).isAccepted());
    }

    @Test
    public void temperatureAndTuneCommandsOnlyUseStableNativeStates() {
        assertTrue(decision(ObicoPassthruRequest.Type.SET_TEMPERATURE, true, true,
                true, true, false, false, false, false, 0).isAccepted());
        assertTrue(decision(ObicoPassthruRequest.Type.SET_TEMPERATURE, true, true,
                true, true, false, false, false, false, 2).isAccepted());
        assertTrue(decision(ObicoPassthruRequest.Type.SET_TEMPERATURE, true, true,
                true, true, false, false, false, false, 4).isAccepted());
        assertFalse(decision(ObicoPassthruRequest.Type.SET_TEMPERATURE, true, true,
                true, true, false, false, false, false, 3).isAccepted());

        for (ObicoPassthruRequest.Type type : new ObicoPassthruRequest.Type[]{
                ObicoPassthruRequest.Type.SET_PRINT_SPEED,
                ObicoPassthruRequest.Type.SET_FLOW_RATE,
                ObicoPassthruRequest.Type.SET_FAN_SPEED}) {
            assertTrue(decision(type, true, true, true, true,
                    false, false, false, false,
                    IMachine.WorkStatus.WORK_STATUS_PRINTING).isAccepted());
            assertTrue(decision(type, true, true, true, true,
                    false, false, false, false,
                    IMachine.WorkStatus.WORK_STATUS_PAUSED).isAccepted());
            assertFalse(decision(type, true, true, true, true,
                    false, false, false, false,
                    IMachine.WorkStatus.WORK_STATUS_IDLE).isAccepted());
        }
    }

    @Test
    public void remoteFilePreparationRequiresIdle() {
        assertTrue(decision(ObicoPassthruRequest.Type.DOWNLOAD_FILE, true, true,
                true, true, false, false, false, false, 0).isAccepted());
        assertFalse(decision(ObicoPassthruRequest.Type.DOWNLOAD_FILE, true, true,
                true, true, false, false, false, false, 2).isAccepted());
        assertTrue(decision(ObicoPassthruRequest.Type.SELECT_FILE, true, true,
                true, true, false, false, false, false, 0).isAccepted());
        assertFalse(decision(ObicoPassthruRequest.Type.SELECT_FILE, true, true,
                true, true, false, false, false, false, 2).isAccepted());
    }

    @Test
    public void fileBrowsingIsReadOnlyAndAvailableWhilePrinting() {
        assertTrue(decision(ObicoPassthruRequest.Type.LIST_FILES, false, true,
                false, false, false, true, false, true, 2).isAccepted());
        assertFalse(decision(ObicoPassthruRequest.Type.LIST_FILES, true, false,
                true, true, false, false, false, false, 0).isAccepted());
    }

    private static ObicoNativeCommandPolicy.Decision decision(
            ObicoPassthruRequest.Type type,
            boolean machineControls,
            boolean files,
            boolean connected,
            boolean fdm,
            boolean emergencyStop,
            boolean pending,
            boolean homed,
            boolean homing,
            int status) {
        return ObicoNativeCommandPolicy.evaluate(
                type,
                machineControls,
                files,
                connected,
                fdm,
                emergencyStop,
                pending,
                homed,
                homing,
                status);
    }
}
