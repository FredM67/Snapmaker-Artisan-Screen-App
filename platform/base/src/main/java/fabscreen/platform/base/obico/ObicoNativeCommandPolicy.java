package fabscreen.platform.base.obico;

import fabscreen.platform.base.service.IMachine;

/**
 * Pure, side-effect-free authorization policy for Obico passthrough operations.
 *
 * <p>The protocol parser only proves that an inbound message has an allowed shape. This policy is
 * deliberately evaluated again immediately before dispatch so a queued command cannot outlive an
 * unlink, permission change, emergency stop, or machine-state transition.</p>
 */
public final class ObicoNativeCommandPolicy {
    private ObicoNativeCommandPolicy() {
    }

    public static Decision evaluate(
            ObicoPassthruRequest.Type command,
            boolean machineControlsEnabled,
            boolean fileOperationsEnabled,
            boolean connected,
            boolean fdmMode,
            boolean emergencyStop,
            boolean commandPending,
            boolean homed,
            boolean homing,
            int machineStatus) {
        if (command == null) return Decision.reject("Unsupported Obico operation");
        if (command == ObicoPassthruRequest.Type.LIST_FILES) {
            return fileOperationsEnabled
                    ? Decision.accept()
                    : Decision.reject("Obico file browsing is disabled");
        }
        if (command == ObicoPassthruRequest.Type.DOWNLOAD_FILE) {
            if (!fileOperationsEnabled || !machineControlsEnabled) {
                return Decision.reject("Obico remote file operations are disabled");
            }
        } else if (!machineControlsEnabled) {
            return Decision.reject("Obico advanced machine controls are disabled");
        }
        if (!connected) return Decision.reject("Printer is disconnected");
        if (!fdmMode) return Decision.reject("3D printing mode is not active");
        if (emergencyStop || machineStatus == 11) {
            return Decision.reject("Emergency stop is active");
        }
        if (commandPending) return Decision.reject("Another Obico operation is in progress");
        if (homing) return Decision.reject("The printer is homing");

        switch (command) {
            case JOG:
                if (!homed) return Decision.reject("Home the printer before jogging");
                return requireIdle(machineStatus, "Jogging is only available while idle");
            case HOME:
                return requireIdle(machineStatus, "Homing is only available while idle");
            case SET_TEMPERATURE:
                return isStableFdmState(machineStatus)
                        ? Decision.accept()
                        : Decision.reject("Temperature changes are unavailable in this state");
            case EXTRUDE:
                return requireIdle(machineStatus, "Extrusion is only available while idle");
            case SET_PRINT_SPEED:
            case SET_FLOW_RATE:
            case SET_FAN_SPEED:
                return machineStatus == IMachine.WorkStatus.WORK_STATUS_PRINTING
                        || machineStatus == IMachine.WorkStatus.WORK_STATUS_PAUSED
                        ? Decision.accept()
                        : Decision.reject("Print tuning requires a printing or paused job");
            case DOWNLOAD_FILE:
                return requireIdle(
                        machineStatus,
                        "Remote files can only be prepared while the printer is idle");
            case SELECT_FILE:
                return requireIdle(
                        machineStatus,
                        "Artisan files can only be started while the printer is idle");
            default:
                return Decision.reject("Unsupported Obico operation");
        }
    }

    private static Decision requireIdle(int machineStatus, String message) {
        return machineStatus == IMachine.WorkStatus.WORK_STATUS_IDLE
                ? Decision.accept()
                : Decision.reject(message);
    }

    private static boolean isStableFdmState(int machineStatus) {
        return machineStatus == IMachine.WorkStatus.WORK_STATUS_IDLE
                || machineStatus == IMachine.WorkStatus.WORK_STATUS_PRINTING
                || machineStatus == IMachine.WorkStatus.WORK_STATUS_PAUSED;
    }

    public static final class Decision {
        private static final Decision ACCEPTED = new Decision(true, "");

        private final boolean accepted;
        private final String reason;

        private Decision(boolean accepted, String reason) {
            this.accepted = accepted;
            this.reason = reason == null ? "" : reason;
        }

        static Decision accept() {
            return ACCEPTED;
        }

        static Decision reject(String reason) {
            return new Decision(false, reason);
        }

        public boolean isAccepted() {
            return accepted;
        }

        public String getReason() {
            return reason;
        }
    }
}
