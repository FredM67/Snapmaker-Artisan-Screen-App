package fabscreen.platform.base.obico;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A transport-neutral snapshot supplied by FabScreen's printer services. */
public final class ObicoPrinterSnapshot {
    private final ObicoPrintState state;
    private final String fileName;
    private final Double completion;
    private final Long printTimeSeconds;
    private final Long printTimeLeftSeconds;
    private final long filePosition;
    private final Double currentZ;
    private final Long startedAtMillis;
    private final List<ObicoTemperature> temperatures;
    private final String error;
    private final Long obicoGcodeFileId;
    private final Double currentFeedRate;
    private final Double currentFlowRate;
    private final Double currentFanSpeed;

    public ObicoPrinterSnapshot(
            ObicoPrintState state,
            String fileName,
            Double completion,
            Long printTimeSeconds,
            Long printTimeLeftSeconds,
            long filePosition,
            Double currentZ,
            Long startedAtMillis,
            List<ObicoTemperature> temperatures,
            String error) {
        this(
                state,
                fileName,
                completion,
                printTimeSeconds,
                printTimeLeftSeconds,
                filePosition,
                currentZ,
                startedAtMillis,
                temperatures,
                error,
                null,
                null,
                null,
                null);
    }

    private ObicoPrinterSnapshot(
            ObicoPrintState state,
            String fileName,
            Double completion,
            Long printTimeSeconds,
            Long printTimeLeftSeconds,
            long filePosition,
            Double currentZ,
            Long startedAtMillis,
            List<ObicoTemperature> temperatures,
            String error,
            Long obicoGcodeFileId,
            Double currentFeedRate,
            Double currentFlowRate,
            Double currentFanSpeed) {
        this.state = state == null ? ObicoPrintState.OFFLINE : state;
        this.fileName = cleanNullable(fileName, 512);
        this.completion = completion == null ? null : clamp(completion, 0.0d, 100.0d);
        this.printTimeSeconds = nonNegative(printTimeSeconds);
        this.printTimeLeftSeconds = nonNegative(printTimeLeftSeconds);
        this.filePosition = Math.max(0L, filePosition);
        this.currentZ = finiteNullable(currentZ);
        this.startedAtMillis = nonNegative(startedAtMillis);
        List<ObicoTemperature> safeTemperatures = temperatures == null
                ? Collections.<ObicoTemperature>emptyList()
                : new ArrayList<>(temperatures);
        this.temperatures = Collections.unmodifiableList(safeTemperatures);
        this.error = cleanNullable(error, 1024);
        this.obicoGcodeFileId = nonNegative(obicoGcodeFileId);
        this.currentFeedRate = boundedNullable(currentFeedRate, 0.0d, 5.0d);
        this.currentFlowRate = boundedNullable(currentFlowRate, 0.0d, 2.0d);
        this.currentFanSpeed = boundedNullable(currentFanSpeed, 0.0d, 1.0d);
    }

    public static ObicoPrinterSnapshot offline() {
        return new ObicoPrinterSnapshot(
                ObicoPrintState.OFFLINE,
                null,
                null,
                null,
                null,
                0L,
                null,
                null,
                Collections.<ObicoTemperature>emptyList(),
                null);
    }

    private static String cleanNullable(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String clean = value.replace('\u0000', ' ').trim();
        if (clean.isEmpty()) {
            return null;
        }
        return clean.length() <= maxLength ? clean : clean.substring(0, maxLength);
    }

    private static Long nonNegative(Long value) {
        return value == null ? null : Math.max(0L, value);
    }

    private static Double finiteNullable(Double value) {
        if (value == null || value.isNaN() || value.isInfinite()) {
            return null;
        }
        return value;
    }

    private static Double boundedNullable(Double value, double minimum, double maximum) {
        Double finite = finiteNullable(value);
        return finite == null ? null : clamp(finite, minimum, maximum);
    }

    private static double clamp(double value, double minimum, double maximum) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return minimum;
        }
        return Math.max(minimum, Math.min(maximum, value));
    }

    public ObicoPrintState getState() {
        return state;
    }

    public String getFileName() {
        return fileName;
    }

    public Double getCompletion() {
        return completion;
    }

    public Long getPrintTimeSeconds() {
        return printTimeSeconds;
    }

    public Long getPrintTimeLeftSeconds() {
        return printTimeLeftSeconds;
    }

    public long getFilePosition() {
        return filePosition;
    }

    public Double getCurrentZ() {
        return currentZ;
    }

    public Long getStartedAtMillis() {
        return startedAtMillis;
    }

    public List<ObicoTemperature> getTemperatures() {
        return temperatures;
    }

    public String getError() {
        return error;
    }

    public Long getObicoGcodeFileId() {
        return obicoGcodeFileId;
    }

    public Double getCurrentFeedRate() {
        return currentFeedRate;
    }

    public Double getCurrentFlowRate() {
        return currentFlowRate;
    }

    public Double getCurrentFanSpeed() {
        return currentFanSpeed;
    }

    public ObicoPrinterSnapshot withRemoteState(
            Long gcodeFileId,
            Double feedRate,
            Double flowRate,
            Double fanSpeed) {
        return new ObicoPrinterSnapshot(
                state,
                fileName,
                completion,
                printTimeSeconds,
                printTimeLeftSeconds,
                filePosition,
                currentZ,
                startedAtMillis,
                temperatures,
                error,
                gcodeFileId,
                feedRate,
                flowRate,
                fanSpeed);
    }

    public boolean isPrinting() {
        return state == ObicoPrintState.PRINTING;
    }
}
