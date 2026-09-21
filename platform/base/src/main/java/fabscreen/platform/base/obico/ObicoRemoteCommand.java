package fabscreen.platform.base.obico;

public final class ObicoRemoteCommand {
    public enum Type {
        PAUSE("pause"),
        RESUME("resume"),
        CANCEL("cancel");

        private final String wireName;

        Type(String wireName) {
            this.wireName = wireName;
        }

        public String getWireName() {
            return wireName;
        }

        static Type fromWireName(String value) {
            if (value == null) {
                return null;
            }
            String clean = value.trim();
            for (Type type : values()) {
                if (type.wireName.equalsIgnoreCase(clean)) {
                    return type;
                }
            }
            return null;
        }
    }

    private final Type type;
    private final String serverId;
    private final String fingerprint;

    ObicoRemoteCommand(Type type, String serverId, String fingerprint) {
        this.type = type;
        this.serverId = serverId == null ? "" : serverId;
        this.fingerprint = fingerprint;
    }

    public Type getType() {
        return type;
    }

    public String getWireName() {
        return type.getWireName();
    }

    public String getServerId() {
        return serverId;
    }

    String getFingerprint() {
        return fingerprint;
    }
}
