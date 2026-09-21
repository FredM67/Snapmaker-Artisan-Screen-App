package fabscreen.platform.base.obico;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable, already-validated Obico passthrough request.
 *
 * <p>Obico normally forwards these messages to OctoPrint objects by name. FabScreen must not
 * reproduce that reflective dispatch: only the operations represented by {@link Type} can be
 * created by {@link ObicoProtocol}.</p>
 */
public final class ObicoPassthruRequest {
    public enum Type {
        JOG,
        HOME,
        SET_TEMPERATURE,
        EXTRUDE,
        SET_PRINT_SPEED,
        SET_FLOW_RATE,
        SET_FAN_SPEED,
        DOWNLOAD_FILE,
        LIST_FILES,
        SELECT_FILE
    }

    private final Type type;
    private final String reference;
    private final String jogAxis;
    private final double jogDistanceMm;
    private final List<String> homeAxes;
    private final String heaterName;
    private final int targetTemperatureC;
    private final int extruderIndex;
    private final double extrusionDistanceMm;
    private final int extrusionFeedrateMmPerMinute;
    private final int percentage;
    private final int fanSpeed;
    private final DownloadFile downloadFile;
    private final String listPath;
    private final boolean listRecursive;
    private final Integer listLevel;
    private final String listFilter;
    private final String selectedFilePath;
    private final boolean printAfterSelect;

    private ObicoPassthruRequest(
            Type type,
            String reference,
            String jogAxis,
            double jogDistanceMm,
            List<String> homeAxes,
            String heaterName,
            int targetTemperatureC,
            int extruderIndex,
            double extrusionDistanceMm,
            int extrusionFeedrateMmPerMinute,
            int percentage,
            int fanSpeed,
            DownloadFile downloadFile) {
        this(type, reference, jogAxis, jogDistanceMm, homeAxes, heaterName,
                targetTemperatureC, extruderIndex, extrusionDistanceMm,
                extrusionFeedrateMmPerMinute, percentage, fanSpeed, downloadFile,
                null, false, null, null, null, false);
    }

    private ObicoPassthruRequest(
            Type type,
            String reference,
            String jogAxis,
            double jogDistanceMm,
            List<String> homeAxes,
            String heaterName,
            int targetTemperatureC,
            int extruderIndex,
            double extrusionDistanceMm,
            int extrusionFeedrateMmPerMinute,
            int percentage,
            int fanSpeed,
            DownloadFile downloadFile,
            String listPath,
            boolean listRecursive,
            Integer listLevel,
            String listFilter,
            String selectedFilePath,
            boolean printAfterSelect) {
        this.type = type;
        this.reference = reference;
        this.jogAxis = jogAxis;
        this.jogDistanceMm = jogDistanceMm;
        this.homeAxes = homeAxes == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(homeAxes));
        this.heaterName = heaterName;
        this.targetTemperatureC = targetTemperatureC;
        this.extruderIndex = extruderIndex;
        this.extrusionDistanceMm = extrusionDistanceMm;
        this.extrusionFeedrateMmPerMinute = extrusionFeedrateMmPerMinute;
        this.percentage = percentage;
        this.fanSpeed = fanSpeed;
        this.downloadFile = downloadFile;
        this.listPath = listPath;
        this.listRecursive = listRecursive;
        this.listLevel = listLevel;
        this.listFilter = listFilter;
        this.selectedFilePath = selectedFilePath;
        this.printAfterSelect = printAfterSelect;
    }

    static ObicoPassthruRequest jog(String reference, String axis, double distanceMm) {
        return new ObicoPassthruRequest(
                Type.JOG, reference, axis, distanceMm, null, null, 0,
                -1, 0.0d, 0, 0, 0, null);
    }

    static ObicoPassthruRequest home(String reference, List<String> axes) {
        return new ObicoPassthruRequest(
                Type.HOME, reference, null, 0.0d, axes, null, 0,
                -1, 0.0d, 0, 0, 0, null);
    }

    static ObicoPassthruRequest setTemperature(
            String reference,
            String heaterName,
            int targetTemperatureC) {
        return new ObicoPassthruRequest(
                Type.SET_TEMPERATURE, reference, null, 0.0d, null,
                heaterName, targetTemperatureC, -1, 0.0d, 0, 0, 0, null);
    }

    static ObicoPassthruRequest extrude(
            String reference,
            int extruderIndex,
            double distanceMm,
            int feedrateMmPerMinute) {
        return new ObicoPassthruRequest(
                Type.EXTRUDE, reference, null, 0.0d, null, null, 0,
                extruderIndex, distanceMm, feedrateMmPerMinute, 0, 0, null);
    }

    static ObicoPassthruRequest percentage(
            Type type,
            String reference,
            int percentage) {
        return new ObicoPassthruRequest(
                type, reference, null, 0.0d, null, null, 0,
                -1, 0.0d, 0, percentage, 0, null);
    }

    static ObicoPassthruRequest fanSpeed(String reference, int fanSpeed) {
        return new ObicoPassthruRequest(
                Type.SET_FAN_SPEED, reference, null, 0.0d, null, null, 0,
                -1, 0.0d, 0, 0, fanSpeed, null);
    }

    static ObicoPassthruRequest download(String reference, DownloadFile downloadFile) {
        return new ObicoPassthruRequest(
                Type.DOWNLOAD_FILE, reference, null, 0.0d, null, null, 0,
                -1, 0.0d, 0, 0, 0, downloadFile);
    }

    static ObicoPassthruRequest listFiles(
            String reference,
            String path,
            boolean recursive,
            Integer level,
            String filter) {
        return new ObicoPassthruRequest(
                Type.LIST_FILES, reference, null, 0.0d, null, null, 0,
                -1, 0.0d, 0, 0, 0, null,
                path, recursive, level, filter, null, false);
    }

    static ObicoPassthruRequest selectFile(
            String reference,
            String path,
            boolean printAfterSelect) {
        return new ObicoPassthruRequest(
                Type.SELECT_FILE, reference, null, 0.0d, null, null, 0,
                -1, 0.0d, 0, 0, 0, null,
                null, false, null, null, path, printAfterSelect);
    }

    public Type getType() {
        return type;
    }

    public String getReference() {
        return reference;
    }

    public String getJogAxis() {
        return jogAxis;
    }

    public double getJogDistanceMm() {
        return jogDistanceMm;
    }

    public List<String> getHomeAxes() {
        return homeAxes;
    }

    public String getHeaterName() {
        return heaterName;
    }

    public int getTargetTemperatureC() {
        return targetTemperatureC;
    }

    public int getExtruderIndex() {
        return extruderIndex;
    }

    public double getExtrusionDistanceMm() {
        return extrusionDistanceMm;
    }

    public int getExtrusionFeedrateMmPerMinute() {
        return extrusionFeedrateMmPerMinute;
    }

    public int getPercentage() {
        return percentage;
    }

    public int getFanSpeed() {
        return fanSpeed;
    }

    public DownloadFile getDownloadFile() {
        return downloadFile;
    }

    public String getListPath() {
        return listPath;
    }

    public boolean isListRecursive() {
        return listRecursive;
    }

    public Integer getListLevel() {
        return listLevel;
    }

    public String getListFilter() {
        return listFilter;
    }

    public String getSelectedFilePath() {
        return selectedFilePath;
    }

    public boolean isPrintAfterSelect() {
        return printAfterSelect;
    }

    /** Cloud-file metadata. The URL may contain a signed query and must never be logged. */
    public static final class DownloadFile {
        private final String id;
        private final String url;
        private final String filename;
        private final String safeFilename;

        DownloadFile(String id, String url, String filename, String safeFilename) {
            this.id = id;
            this.url = url;
            this.filename = filename;
            this.safeFilename = safeFilename;
        }

        public String getId() {
            return id;
        }

        public String getUrl() {
            return url;
        }

        public String getFilename() {
            return filename;
        }

        public String getSafeFilename() {
            return safeFilename;
        }

        @Override
        public String toString() {
            return "DownloadFile{id='" + id + "', filename='" + filename + "'}";
        }
    }

    @Override
    public String toString() {
        return "ObicoPassthruRequest{type=" + type + ", reference='" + reference + "'}";
    }
}
