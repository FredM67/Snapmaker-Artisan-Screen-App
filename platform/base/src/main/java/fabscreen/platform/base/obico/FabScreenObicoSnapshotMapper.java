package fabscreen.platform.base.obico;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Converts a small, immutable view of FabScreen state into Obico's transport model.
 *
 * <p>This class deliberately has no Android or machine-controller dependencies. The process-level
 * Obico service can take one coherent snapshot from the existing machine services and hand it to
 * this mapper, while local JVM tests keep state and time-estimation behavior deterministic.</p>
 */
public final class FabScreenObicoSnapshotMapper {
    private static final long MIN_MEASURED_ELAPSED_SECONDS = 45L;
    private static final double MIN_MEASURED_PROGRESS = 0.02d;

    private FabScreenObicoSnapshotMapper() {
    }

    public static ObicoPrinterSnapshot map(Input input) {
        if (input == null || !input.connected) {
            return ObicoPrinterSnapshot.offline();
        }

        ObicoPrintState state = stateFor(
                input.connected,
                input.fdmMode,
                input.emergencyStop,
                input.machineStatus
        );
        boolean jobActive = input.fdmMode && isActiveMachineStatus(input.machineStatus);
        // Preserve the interrupted job identity for an emergency-stop or power-loss report, but do
        // not leak the workspace's stale last filename while the machine is simply idle.
        boolean includeJob = jobActive
                || (state == ObicoPrintState.ERROR && !clean(input.fileName).isEmpty());
        double progress = boundedProgress(input.progressRatio);
        Long elapsed = includeJob ? nonNegative(input.elapsedSeconds) : null;
        Long startedAt = includeJob
                ? resolvedStartedAt(input.startedAtMillis, elapsed, input.capturedAtMillis)
                : null;
        Long remaining = includeJob
                ? remainingSeconds(
                        progress,
                        elapsed,
                        input.estimatedTotalSeconds,
                        input.currentLine,
                        input.totalLines
                )
                : null;
        String error = state == ObicoPrintState.ERROR
                ? errorFor(input.emergencyStop, input.machineStatus, input.error)
                : null;

        return new ObicoPrinterSnapshot(
                state,
                includeJob ? input.fileName : null,
                includeJob ? progress * 100.0d : null,
                elapsed,
                remaining,
                includeJob ? Math.max(0L, input.currentLine) : 0L,
                input.currentZ,
                startedAt,
                input.temperatures,
                error
        );
    }

    static ObicoPrintState stateFor(
            boolean connected,
            boolean fdmMode,
            boolean emergencyStop,
            int machineStatus
    ) {
        if (!connected) return ObicoPrintState.OFFLINE;
        if (emergencyStop || machineStatus == 11 || machineStatus == 12) {
            return ObicoPrintState.ERROR;
        }
        // Obico's controls model a 3D-printer job. Never expose a laser or CNC operation as a
        // remotely controllable Obico print merely because the controller reuses status code 2.
        if (!fdmMode) return ObicoPrintState.OPERATIONAL;
        if (machineStatus == 4) return ObicoPrintState.PAUSED;
        return isActiveMachineStatus(machineStatus)
                ? ObicoPrintState.PRINTING
                : ObicoPrintState.OPERATIONAL;
    }

    /**
     * Transitional controller states stay active until a terminal state is observed. This avoids
     * emitting a false PrintDone event while the Artisan is pausing, stopping, or recovering.
     */
    public static boolean isActiveMachineStatus(int machineStatus) {
        switch (machineStatus) {
            case 1:  // starting
            case 2:  // printing
            case 3:  // pausing
            case 4:  // paused
            case 5:  // stopping
            case 7:  // finishing
            case 9:  // recovering
            case 10: // resuming
                return true;
            default:
                return false;
        }
    }

    static Long remainingSeconds(
            double progress,
            Long elapsedSeconds,
            Long estimatedTotalSeconds,
            long currentLine,
            long totalLines
    ) {
        double lineProgress = totalLines > 0L
                ? clamp(currentLine / (double) totalLines, 0.0d, 1.0d)
                : progress;
        if (lineProgress >= 1.0d) return 0L;

        Long estimated = positive(estimatedTotalSeconds);
        if (estimated != null) {
            if (lineProgress > 0.0d) {
                return Math.max(0L, Math.round(estimated * (1.0d - lineProgress)));
            }
            return Math.max(0L, estimated - valueOrZero(elapsedSeconds));
        }

        long elapsed = valueOrZero(elapsedSeconds);
        if (elapsed < MIN_MEASURED_ELAPSED_SECONDS || lineProgress < MIN_MEASURED_PROGRESS) {
            return null;
        }
        return Math.max(0L, Math.round(elapsed / lineProgress - elapsed));
    }

    private static Long resolvedStartedAt(Long explicit, Long elapsed, long capturedAtMillis) {
        Long safeExplicit = nonNegative(explicit);
        if (safeExplicit != null) return safeExplicit;
        if (elapsed == null || capturedAtMillis <= 0L) return null;
        long elapsedMillis;
        try {
            elapsedMillis = Math.multiplyExact(elapsed, 1_000L);
        } catch (ArithmeticException ignored) {
            return null;
        }
        return Math.max(0L, capturedAtMillis - elapsedMillis);
    }

    private static String errorFor(boolean emergencyStop, int machineStatus, String supplied) {
        if (emergencyStop || machineStatus == 11) return "Emergency stop";
        if (machineStatus == 12) return "Power-loss recovery";
        String clean = clean(supplied);
        return clean.isEmpty() ? "Printer error" : clean;
    }

    private static double boundedProgress(Double value) {
        if (value == null || value.isNaN() || value.isInfinite()) return 0.0d;
        return clamp(value, 0.0d, 1.0d);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static Long nonNegative(Long value) {
        return value == null ? null : Math.max(0L, value);
    }

    private static Long positive(Long value) {
        return value != null && value > 0L ? value : null;
    }

    private static long valueOrZero(Long value) {
        return value == null ? 0L : Math.max(0L, value);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    /** A coherent point-in-time value copied from FabScreen's mutable machine services. */
    public static final class Input {
        private final boolean connected;
        private final boolean fdmMode;
        private final boolean emergencyStop;
        private final int machineStatus;
        private final String fileName;
        private final Double progressRatio;
        private final Long elapsedSeconds;
        private final Long estimatedTotalSeconds;
        private final long currentLine;
        private final long totalLines;
        private final Double currentZ;
        private final Long startedAtMillis;
        private final long capturedAtMillis;
        private final List<ObicoTemperature> temperatures;
        private final String error;

        private Input(Builder builder) {
            connected = builder.connected;
            fdmMode = builder.fdmMode;
            emergencyStop = builder.emergencyStop;
            machineStatus = builder.machineStatus;
            fileName = builder.fileName;
            progressRatio = builder.progressRatio;
            elapsedSeconds = builder.elapsedSeconds;
            estimatedTotalSeconds = builder.estimatedTotalSeconds;
            currentLine = builder.currentLine;
            totalLines = builder.totalLines;
            currentZ = builder.currentZ;
            startedAtMillis = builder.startedAtMillis;
            capturedAtMillis = builder.capturedAtMillis;
            temperatures = Collections.unmodifiableList(new ArrayList<>(builder.temperatures));
            error = builder.error;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {
            private boolean connected;
            private boolean fdmMode;
            private boolean emergencyStop;
            private int machineStatus;
            private String fileName;
            private Double progressRatio;
            private Long elapsedSeconds;
            private Long estimatedTotalSeconds;
            private long currentLine;
            private long totalLines;
            private Double currentZ;
            private Long startedAtMillis;
            private long capturedAtMillis = System.currentTimeMillis();
            private List<ObicoTemperature> temperatures = Collections.emptyList();
            private String error;

            public Builder connected(boolean value) {
                connected = value;
                return this;
            }

            public Builder fdmMode(boolean value) {
                fdmMode = value;
                return this;
            }

            public Builder emergencyStop(boolean value) {
                emergencyStop = value;
                return this;
            }

            public Builder machineStatus(int value) {
                machineStatus = value;
                return this;
            }

            public Builder fileName(String value) {
                fileName = value;
                return this;
            }

            public Builder progressRatio(Double value) {
                progressRatio = value;
                return this;
            }

            public Builder elapsedSeconds(Long value) {
                elapsedSeconds = value;
                return this;
            }

            public Builder estimatedTotalSeconds(Long value) {
                estimatedTotalSeconds = value;
                return this;
            }

            public Builder currentLine(long value) {
                currentLine = value;
                return this;
            }

            public Builder totalLines(long value) {
                totalLines = value;
                return this;
            }

            public Builder currentZ(Double value) {
                currentZ = value;
                return this;
            }

            public Builder startedAtMillis(Long value) {
                startedAtMillis = value;
                return this;
            }

            public Builder capturedAtMillis(long value) {
                capturedAtMillis = value;
                return this;
            }

            public Builder temperatures(List<ObicoTemperature> value) {
                temperatures = value == null
                        ? Collections.<ObicoTemperature>emptyList()
                        : value;
                return this;
            }

            public Builder error(String value) {
                error = value;
                return this;
            }

            public Input build() {
                return new Input(this);
            }
        }
    }
}
