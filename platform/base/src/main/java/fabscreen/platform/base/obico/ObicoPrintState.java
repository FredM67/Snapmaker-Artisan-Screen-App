package fabscreen.platform.base.obico;

public enum ObicoPrintState {
    OFFLINE("Offline"),
    OPERATIONAL("Operational"),
    PRINTING("Printing"),
    PAUSED("Paused"),
    ERROR("Error");

    private final String wireName;

    ObicoPrintState(String wireName) {
        this.wireName = wireName;
    }

    public String getWireName() {
        return wireName;
    }

    public boolean isActive() {
        return this == PRINTING || this == PAUSED;
    }
}
