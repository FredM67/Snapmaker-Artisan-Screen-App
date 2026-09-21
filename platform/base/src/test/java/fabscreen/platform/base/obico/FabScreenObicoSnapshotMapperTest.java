package fabscreen.platform.base.obico;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class FabScreenObicoSnapshotMapperTest {
    @Test
    public void disconnectedMachineIsAlwaysOfflineAndDoesNotExposeStaleJob() {
        ObicoPrinterSnapshot snapshot = FabScreenObicoSnapshotMapper.map(
                input()
                        .connected(false)
                        .machineStatus(2)
                        .fileName("stale.gcode")
                        .build()
        );

        assertEquals(ObicoPrintState.OFFLINE, snapshot.getState());
        assertNull(snapshot.getFileName());
        assertNull(snapshot.getCompletion());
    }

    @Test
    public void mapsControllerTransitionsWithoutPrematurePrintDoneState() {
        int[] active = {1, 2, 3, 5, 7, 9, 10};
        for (int status : active) {
            assertEquals(
                    "status " + status,
                    ObicoPrintState.PRINTING,
                    FabScreenObicoSnapshotMapper.stateFor(true, true, false, status)
            );
        }
        assertEquals(
                ObicoPrintState.PAUSED,
                FabScreenObicoSnapshotMapper.stateFor(true, true, false, 4)
        );
        assertEquals(
                ObicoPrintState.OPERATIONAL,
                FabScreenObicoSnapshotMapper.stateFor(true, true, false, 8)
        );
    }

    @Test
    public void reportsEmergencyAndPowerLossAsErrors() {
        assertEquals(
                ObicoPrintState.ERROR,
                FabScreenObicoSnapshotMapper.stateFor(true, true, true, 2)
        );
        ObicoPrinterSnapshot powerLoss = FabScreenObicoSnapshotMapper.map(
                input()
                        .machineStatus(12)
                        .fileName("recover.gcode")
                        .build()
        );
        assertEquals(ObicoPrintState.ERROR, powerLoss.getState());
        assertEquals("Power-loss recovery", powerLoss.getError());
        assertEquals("recover.gcode", powerLoss.getFileName());
    }

    @Test
    public void doesNotExposeLaserOrCncStatusAsAnObicoPrint() {
        ObicoPrinterSnapshot snapshot = FabScreenObicoSnapshotMapper.map(
                input()
                        .fdmMode(false)
                        .machineStatus(2)
                        .fileName("laser.nc")
                        .build()
        );

        assertEquals(ObicoPrintState.OPERATIONAL, snapshot.getState());
        assertNull(snapshot.getFileName());
    }

    @Test
    public void mapsProgressTemperaturesAndStableDerivedStartTime() {
        ObicoPrinterSnapshot snapshot = FabScreenObicoSnapshotMapper.map(
                input()
                        .machineStatus(2)
                        .fileName("part.gcode")
                        .progressRatio(0.25d)
                        .elapsedSeconds(600L)
                        .estimatedTotalSeconds(2_400L)
                        .currentLine(250L)
                        .totalLines(1_000L)
                        .currentZ(4.2d)
                        .capturedAtMillis(1_700_000_600_000L)
                        .temperatures(Arrays.asList(
                                new ObicoTemperature("tool0", 205.0d, 210.0d),
                                new ObicoTemperature("bed", 58.0d, 60.0d)
                        ))
                        .build()
        );

        assertEquals(ObicoPrintState.PRINTING, snapshot.getState());
        assertEquals(25.0d, snapshot.getCompletion(), 0.001d);
        assertEquals(Long.valueOf(600L), snapshot.getPrintTimeSeconds());
        assertEquals(Long.valueOf(1_800L), snapshot.getPrintTimeLeftSeconds());
        assertEquals(Long.valueOf(1_700_000_000_000L), snapshot.getStartedAtMillis());
        assertEquals(2, snapshot.getTemperatures().size());
    }

    @Test
    public void measuredEstimateWaitsForUsefulProgress() {
        assertNull(FabScreenObicoSnapshotMapper.remainingSeconds(
                0.01d,
                600L,
                null,
                10L,
                1_000L
        ));
        assertEquals(Long.valueOf(1_800L), FabScreenObicoSnapshotMapper.remainingSeconds(
                0.25d,
                600L,
                null,
                250L,
                1_000L
        ));
    }

    @Test
    public void idleMachineDoesNotExposeWorkspaceFilenameOrProgress() {
        ObicoPrinterSnapshot snapshot = FabScreenObicoSnapshotMapper.map(
                input()
                        .machineStatus(0)
                        .fileName("last-job.gcode")
                        .progressRatio(0.8d)
                        .elapsedSeconds(90L)
                        .build()
        );

        assertEquals(ObicoPrintState.OPERATIONAL, snapshot.getState());
        assertNull(snapshot.getFileName());
        assertNull(snapshot.getCompletion());
        assertNull(snapshot.getPrintTimeSeconds());
    }

    private static FabScreenObicoSnapshotMapper.Input.Builder input() {
        return FabScreenObicoSnapshotMapper.Input.builder()
                .connected(true)
                .fdmMode(true)
                .capturedAtMillis(1_700_000_600_000L);
    }
}
