package fabscreen.platform.base.obico;

/** Token-free state for the Obico verification-code exchange. */
public final class ObicoLinkStatus {
    public enum State {
        IDLE,
        VERIFYING,
        LINKED,
        FAILED
    }

    private final State state;
    private final String message;
    private final long updatedAtMillis;

    public ObicoLinkStatus(
            State state,
            String message,
            long updatedAtMillis) {
        this.state = state == null ? State.IDLE : state;
        this.message = bounded(message, 256);
        this.updatedAtMillis = Math.max(0L, updatedAtMillis);
    }

    private static String bounded(String value, int maximum) {
        if (value == null) {
            return "";
        }
        String clean = value.replace('\u0000', ' ').trim();
        return clean.length() <= maximum ? clean : clean.substring(0, maximum);
    }

    public State getState() {
        return state;
    }

    public String getMessage() {
        return message;
    }

    public long getUpdatedAtMillis() {
        return updatedAtMillis;
    }
}
