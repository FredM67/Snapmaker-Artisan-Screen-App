package fabscreen.platform.base.obico;

/** Pure machine-state policy applied immediately before an Obico command reaches FabScreen. */
public final class FabScreenObicoCommandPolicy {
    private FabScreenObicoCommandPolicy() {
    }

    public static Decision evaluate(
            ObicoRemoteCommand.Type command,
            boolean remoteControlEnabled,
            boolean connected,
            boolean fdmMode,
            boolean emergencyStop,
            boolean commandPending,
            int machineStatus
    ) {
        if (command == null) return Decision.reject("Unsupported remote command");
        if (!remoteControlEnabled) return Decision.reject("Obico remote control is disabled");
        if (!connected) return Decision.reject("Printer is disconnected");
        if (!fdmMode) return Decision.reject("3D printing mode is not active");
        if (emergencyStop || machineStatus == 11) {
            return Decision.reject("Emergency stop is active");
        }
        if (commandPending) return Decision.reject("Another print command is in progress");

        switch (command) {
            case PAUSE:
                return machineStatus == 2
                        ? Decision.accept()
                        : Decision.reject("The job is not in a pausable state");
            case RESUME:
                return machineStatus == 4
                        ? Decision.accept()
                        : Decision.reject("The job is not paused");
            case CANCEL:
                return machineStatus == 2 || machineStatus == 4
                        ? Decision.accept()
                        : Decision.reject("The job is not in a cancellable state");
            default:
                return Decision.reject("Unsupported remote command");
        }
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
