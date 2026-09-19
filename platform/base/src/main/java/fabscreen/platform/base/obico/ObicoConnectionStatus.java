package fabscreen.platform.base.obico;

/**
 * Public, token-free connection and audit status suitable for the dashboard.
 */
public final class ObicoConnectionStatus {
    public enum State {
        DISABLED,
        NOT_LINKED,
        CONNECTING,
        CONNECTED,
        RETRYING,
        ERROR,
        STOPPED
    }

    private final State state;
    private final String message;
    private final long updatedAtMillis;
    private final long connectedAtMillis;
    private final long lastTelemetryAtMillis;
    private final long lastSnapshotAtMillis;
    private final long lastRemoteCommandAtMillis;
    private final String lastRemoteCommand;
    private final String lastRemoteCommandResult;
    private final int reconnectAttempt;
    private final boolean remoteViewing;
    private final boolean remoteShouldWatch;
    private final long lastRemoteWatchAtMillis;

    public ObicoConnectionStatus(
            State state,
            String message,
            long updatedAtMillis,
            long connectedAtMillis,
            long lastTelemetryAtMillis,
            long lastSnapshotAtMillis,
            long lastRemoteCommandAtMillis,
            String lastRemoteCommand,
            String lastRemoteCommandResult,
            int reconnectAttempt) {
        this(state, message, updatedAtMillis, connectedAtMillis, lastTelemetryAtMillis,
                lastSnapshotAtMillis, lastRemoteCommandAtMillis, lastRemoteCommand,
                lastRemoteCommandResult, reconnectAttempt, false, false, 0L);
    }

    private ObicoConnectionStatus(
            State state,
            String message,
            long updatedAtMillis,
            long connectedAtMillis,
            long lastTelemetryAtMillis,
            long lastSnapshotAtMillis,
            long lastRemoteCommandAtMillis,
            String lastRemoteCommand,
            String lastRemoteCommandResult,
            int reconnectAttempt,
            boolean remoteViewing,
            boolean remoteShouldWatch,
            long lastRemoteWatchAtMillis) {
        this.state = state == null ? State.ERROR : state;
        this.message = safe(message, 256, "Unknown");
        this.updatedAtMillis = Math.max(0L, updatedAtMillis);
        this.connectedAtMillis = Math.max(0L, connectedAtMillis);
        this.lastTelemetryAtMillis = Math.max(0L, lastTelemetryAtMillis);
        this.lastSnapshotAtMillis = Math.max(0L, lastSnapshotAtMillis);
        this.lastRemoteCommandAtMillis = Math.max(0L, lastRemoteCommandAtMillis);
        this.lastRemoteCommand = safe(lastRemoteCommand, 32, "");
        this.lastRemoteCommandResult = safe(lastRemoteCommandResult, 64, "");
        this.reconnectAttempt = Math.max(0, reconnectAttempt);
        this.remoteViewing = remoteViewing;
        this.remoteShouldWatch = remoteShouldWatch;
        this.lastRemoteWatchAtMillis = Math.max(0L, lastRemoteWatchAtMillis);
    }

    public static ObicoConnectionStatus disabled() {
        return new ObicoConnectionStatus(
                State.DISABLED,
                "Disabled",
                System.currentTimeMillis(),
                0L,
                0L,
                0L,
                0L,
                "",
                "",
                0);
    }

    private static String safe(String value, int maxLength, String fallback) {
        if (value == null) {
            return fallback;
        }
        String clean = value.replace('\u0000', ' ').trim();
        if (clean.isEmpty()) {
            return fallback;
        }
        return clean.length() <= maxLength ? clean : clean.substring(0, maxLength);
    }

    public State getState() {
        return state;
    }

    public boolean isConnected() {
        return state == State.CONNECTED;
    }

    public String getMessage() {
        return message;
    }

    public long getUpdatedAtMillis() {
        return updatedAtMillis;
    }

    public long getConnectedAtMillis() {
        return connectedAtMillis;
    }

    public long getLastTelemetryAtMillis() {
        return lastTelemetryAtMillis;
    }

    public long getLastSnapshotAtMillis() {
        return lastSnapshotAtMillis;
    }

    public long getLastRemoteCommandAtMillis() {
        return lastRemoteCommandAtMillis;
    }

    public String getLastRemoteCommand() {
        return lastRemoteCommand;
    }

    public String getLastRemoteCommandResult() {
        return lastRemoteCommandResult;
    }

    public int getReconnectAttempt() {
        return reconnectAttempt;
    }

    /** Whether Obico currently reports an active remote human viewer. */
    public boolean isRemoteViewing() {
        return remoteViewing;
    }

    /** Obico's image-watch request, not proof that its failure detector is enabled. */
    public boolean isRemoteShouldWatch() {
        return remoteShouldWatch;
    }

    public long getLastRemoteWatchAtMillis() {
        return lastRemoteWatchAtMillis;
    }

    ObicoConnectionStatus withConnection(
            State nextState,
            String nextMessage,
            int nextReconnectAttempt,
            boolean markConnected) {
        long now = System.currentTimeMillis();
        return new ObicoConnectionStatus(
                nextState,
                nextMessage,
                now,
                markConnected ? now : connectedAtMillis,
                lastTelemetryAtMillis,
                lastSnapshotAtMillis,
                lastRemoteCommandAtMillis,
                lastRemoteCommand,
                lastRemoteCommandResult,
                nextReconnectAttempt,
                remoteViewing,
                remoteShouldWatch,
                lastRemoteWatchAtMillis);
    }

    ObicoConnectionStatus withTelemetry(long timestampMillis) {
        return new ObicoConnectionStatus(
                state,
                message,
                System.currentTimeMillis(),
                connectedAtMillis,
                timestampMillis,
                lastSnapshotAtMillis,
                lastRemoteCommandAtMillis,
                lastRemoteCommand,
                lastRemoteCommandResult,
                reconnectAttempt,
                remoteViewing,
                remoteShouldWatch,
                lastRemoteWatchAtMillis);
    }

    ObicoConnectionStatus withSnapshot(long timestampMillis) {
        return new ObicoConnectionStatus(
                state,
                message,
                System.currentTimeMillis(),
                connectedAtMillis,
                lastTelemetryAtMillis,
                timestampMillis,
                lastRemoteCommandAtMillis,
                lastRemoteCommand,
                lastRemoteCommandResult,
                reconnectAttempt,
                remoteViewing,
                remoteShouldWatch,
                lastRemoteWatchAtMillis);
    }

    ObicoConnectionStatus withRemoteCommand(String command, String result, long timestampMillis) {
        return new ObicoConnectionStatus(
                state,
                message,
                System.currentTimeMillis(),
                connectedAtMillis,
                lastTelemetryAtMillis,
                lastSnapshotAtMillis,
                timestampMillis,
                command,
                result,
                reconnectAttempt,
                remoteViewing,
                remoteShouldWatch,
                lastRemoteWatchAtMillis);
    }

    ObicoConnectionStatus withRemoteWatch(
            boolean viewing,
            boolean shouldWatch,
            long timestampMillis) {
        return new ObicoConnectionStatus(
                state,
                message,
                System.currentTimeMillis(),
                connectedAtMillis,
                lastTelemetryAtMillis,
                lastSnapshotAtMillis,
                lastRemoteCommandAtMillis,
                lastRemoteCommand,
                lastRemoteCommandResult,
                reconnectAttempt,
                viewing,
                shouldWatch,
                timestampMillis);
    }
}
