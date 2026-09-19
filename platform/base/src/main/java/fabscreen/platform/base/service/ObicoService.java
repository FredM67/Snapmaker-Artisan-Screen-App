package fabscreen.platform.base.service;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import fabscreen.platform.base.camera.UvcCameraManager;
import fabscreen.platform.base.instantiation.IServiceIdentifier;
import fabscreen.platform.base.instantiation.ServiceContainer;
import fabscreen.platform.base.lib.file.FabLocalFile;
import fabscreen.platform.base.lib.file.IFile;
import fabscreen.platform.base.lib.parser.GcodeParser;
import fabscreen.platform.base.lib.parser.IGcodeParser;
import fabscreen.platform.base.lib.print.IPrintWorkspace;
import fabscreen.platform.base.obico.FabScreenObicoCommandPolicy;
import fabscreen.platform.base.obico.FabScreenObicoSnapshotMapper;
import fabscreen.platform.base.obico.ObicoConnectionStatus;
import fabscreen.platform.base.obico.ObicoConnector;
import fabscreen.platform.base.obico.ObicoWebRtcBridge;
import fabscreen.platform.base.obico.ObicoGcodeDownloader;
import fabscreen.platform.base.obico.ObicoFileBrowser;
import fabscreen.platform.base.obico.ObicoLinkResult;
import fabscreen.platform.base.obico.ObicoLinkStatus;
import fabscreen.platform.base.obico.ObicoPassthruRequest;
import fabscreen.platform.base.obico.ObicoNativeCommandPolicy;
import fabscreen.platform.base.obico.ObicoNativeCommandExecutor;
import fabscreen.platform.base.obico.ObicoPrinterSnapshot;
import fabscreen.platform.base.obico.ObicoProtocol;
import fabscreen.platform.base.obico.ObicoRemoteCommand;
import fabscreen.platform.base.obico.ObicoSettings;
import fabscreen.platform.base.obico.ObicoSettingsStore;
import fabscreen.platform.base.obico.ObicoTemperature;
import fabscreen.platform.base.service.machine.MachineInfo;
import fabscreen.platform.base.service.machine.MachineStatus;
import fabscreen.platform.base.service.machine.controller.MachineOperationStatus;
import fabscreen.platform.base.service.machine.entity.Module;
import fabscreen.platform.base.service.machine.entity.module.HeatedBed;
import fabscreen.platform.base.service.machine.entity.parts.Extruder;
import fabscreen.platform.base.service.machine.entity.toolhead.FdmToolhead;
import fabscreen.platform.base.service.machine.controller.ErrorController;
import fabscreen.platform.base.service.machine.controller.FDMController;
import fabscreen.platform.base.service.machine.controller.NewPrintController;
import fabscreen.platform.base.service.machine.controller.PrintEventState;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

/**
 * Process-lifetime bridge between the Artisan's native machine services and Obico.
 *
 * <p>The network connector never receives a machine controller directly. This service supplies
 * immutable telemetry snapshots and applies the same live state checks as the local dashboard
 * immediately before a remote pause, resume, or cancel reaches the controller.</p>
 */
public final class ObicoService implements IObicoService, IServiceIdentifier {
    private static final String TAG = "FabScreenObico";
    private static final String CAMERA_CLIENT_ID = "obico-snapshot";
    private static final long CAMERA_WAIT_MILLIS = 3_000L;
    private static final long TELEMETRY_FRESH_MILLIS = 30_000L;
    private static final long TELEMETRY_REFRESH_MILLIS = 2_000L;
    private static final long TELEMETRY_REQUEST_TIMEOUT_SECONDS = 6L;
    private static final long COMMAND_TIMEOUT_MILLIS = 25_000L;
    private static final long CLOUD_PARSE_TIMEOUT_MINUTES = 20L;
    private static final long CLOUD_START_TIMEOUT_SECONDS = 30L;

    private final IAppService appService;
    private final IMachine machine;
    private final IPrintWorkspace workspace;
    private final ObicoSettingsStore settingsStore;
    private final UvcCameraManager cameraManager;
    private final ObicoGcodeDownloader gcodeDownloader;
    private final ObicoNativeCommandExecutor nativeCommandExecutor;
    private final ObicoConnector connector;
    private final Handler mainHandler;
    private final Disposable printEventSubscription;
    private final AtomicBoolean commandPending = new AtomicBoolean();
    private final AtomicBoolean cloudPrintPending = new AtomicBoolean();
    private final AtomicBoolean telemetryRefreshPending = new AtomicBoolean();
    private final AtomicLong nextCommandId = new AtomicLong();
    private final AtomicLong linkAttempt = new AtomicLong();
    private final AtomicLong connectionTestAttempt = new AtomicLong();
    private final Object commandLock = new Object();
    private final Object cloudPrintLock = new Object();
    private final Object jobClockLock = new Object();

    private volatile ObicoSettings settings = ObicoSettings.defaults();
    private volatile ObicoConnectionStatus connectionStatus = ObicoConnectionStatus.disabled();
    private volatile ObicoLinkStatus linkStatus = idleLinkStatus("");
    private volatile boolean testPending;
    private volatile String testMessage = "";
    private volatile long lastTelemetryRefreshElapsed;
    private volatile long lastCameraSequence = -1L;
    private volatile long cloudPrintGeneration;
    private volatile Long activeObicoGcodeFileId;
    private volatile double currentFeedRate = 1.0d;
    private volatile double currentFlowRate = 1.0d;
    private volatile Double currentFanSpeed;

    private long activeCommandId;
    private ObicoRemoteCommand.Type activeCommandType;
    private Disposable activeCommandDisposable;
    private Runnable activeCommandTimeout;
    private ObicoGcodeDownloader.DownloadHandle activeCloudDownload;
    private GcodeParser activeCloudParser;
    private Disposable activeCloudParseDisposable;
    private Disposable activeCloudStartDisposable;
    private long activeCloudPreparationLease;
    private boolean activeCloudStartIssued;
    private String clockFileName = "";
    private long clockStartedAtMillis;

    /** Constructor parameters are resolved by FabScreen's service container. */
    public ObicoService(IAppService appService, IMachine machine, IPrintWorkspace workspace) {
        if (appService == null || machine == null || workspace == null) {
            throw new IllegalArgumentException("Obico service dependencies are unavailable");
        }
        this.appService = appService;
        this.machine = machine;
        this.workspace = workspace;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.settingsStore = new ObicoSettingsStore(appService.getAppContext());
        this.cameraManager = UvcCameraManager.getInstance(appService.getAppContext());
        this.gcodeDownloader = new ObicoGcodeDownloader();
        this.nativeCommandExecutor = new ObicoNativeCommandExecutor(
                machine,
                appService,
                () -> {
                    ObicoSettings current = settings;
                    return current.isEnabled()
                            && current.isLinked()
                            && current.isRemoteControlEnabled();
                },
                null);
        this.connector = new ObicoConnector(
                this::getPrinterSnapshot,
                this::getCameraJpeg,
                this::handleRemoteCommand,
                this::handlePassthruCommand,
                status -> connectionStatus = status,
                this::logSafe,
                new ObicoWebRtcBridge(appService.getAppContext(), cameraManager, this::logSafe)
        );
        NewPrintController printController = machine.getNewPrintController();
        this.printEventSubscription = printController == null
                ? null
                : printController.getPrintEventObservable()
                        .filter(event -> event.getPrintEventState() == PrintEventState.STOP_SUCCESS
                                || event.getPrintEventState() == PrintEventState.FINISH_SUCCESS
                                || event.getPrintEventState() == PrintEventState.FINISH_FAIL)
                        .subscribe(
                                event -> {
                                    activeObicoGcodeFileId = null;
                                    if (event.getPrintEventState() == PrintEventState.FINISH_FAIL
                                            || event.getErrorCode() == 22) {
                                        connector.notifyPrintFailed();
                                    } else if (event.getPrintEventState()
                                            == PrintEventState.STOP_SUCCESS) {
                                        connector.notifyPrintCancelled();
                                    }
                                },
                                ignored -> logSafe("Obico local print-event subscription stopped")
                        );
        initializeStoredSettings();
    }

    private void initializeStoredSettings() {
        ObicoSettings loaded = settingsStore.load();
        try {
            String canonical = ObicoProtocol.canonicalServerUrl(
                    loaded.getServerUrl(),
                    loaded.isAllowInsecureServer()
            );
            ObicoSettings normalized = new ObicoSettings(
                    loaded.isEnabled(),
                    canonical,
                    loaded.getAuthToken(),
                    loaded.isRemoteControlEnabled(),
                    loaded.isCameraUploadsEnabled(),
                    loaded.isAllowInsecureServer()
            );
            settings = normalized;
            connector.applySettings(normalized);
            connectionStatus = connector.getStatus();
        } catch (RuntimeException invalidStoredConfiguration) {
            // A damaged preference must never prevent FabScreen from starting or send a token to
            // an unvalidated endpoint. The user can replace it from the authenticated dashboard.
            settings = ObicoSettings.defaults();
            connector.applySettings(settings);
            connectionStatus = connector.getStatus();
            testMessage = "Stored Obico settings are invalid; configure the connection again";
            logSafe("Stored Obico settings were rejected");
        }
    }

    @Override
    public JSONObject getPublicStateJson() {
        return publicState(true, "", "");
    }

    @Override
    public synchronized JSONObject applyConfiguration(JSONObject input) {
        if (input == null
                || !(input.opt("enabled") instanceof Boolean)
                || !(input.opt("serverUrl") instanceof String)
                || !(input.opt("allowInsecureServer") instanceof Boolean)
                || !(input.opt("remoteControlEnabled") instanceof Boolean)
                || !(input.opt("cameraUploadsEnabled") instanceof Boolean)) {
            return publicState(false, "Invalid Obico configuration", "");
        }

        boolean enabled = input.optBoolean("enabled");
        String serverUrl = input.optString("serverUrl", "").trim();
        boolean allowInsecure = input.optBoolean("allowInsecureServer");
        boolean remoteControl = input.optBoolean("remoteControlEnabled");
        boolean cameraUploads = input.optBoolean("cameraUploadsEnabled");
        final String canonical;
        try {
            canonical = ObicoProtocol.canonicalServerUrl(serverUrl, allowInsecure);
        } catch (IllegalArgumentException invalidUrl) {
            return publicState(false, safeMessage(invalidUrl.getMessage(),
                    "Invalid Obico server URL"), "");
        }

        ObicoSettings previous = settings;
        boolean serverChanged = !canonical.equals(previous.getServerUrl());
        // Printer tokens are scoped to an Obico server. Never forward an existing credential to
        // a newly entered host, even if that host came through the authenticated local dashboard.
        String authToken = serverChanged ? "" : previous.getAuthToken();
        ObicoSettings next = new ObicoSettings(
                enabled,
                canonical,
                authToken,
                remoteControl,
                cameraUploads,
                allowInsecure
        );

        try {
            settingsStore.save(next);
            settings = next;
            linkAttempt.incrementAndGet();
            connectionTestAttempt.incrementAndGet();
            if (!enabled || !remoteControl || serverChanged) {
                cancelCloudPrintPreparation("Obico configuration changed");
                nativeCommandExecutor.cancelPending("Obico configuration changed");
            }
            linkStatus = idleLinkStatus("");
            testPending = false;
            testMessage = "";
            connector.applySettings(next);
            connectionStatus = connector.getStatus();
            if (!enabled || !next.isLinked() || !cameraUploads) releaseCamera();
            return publicState(
                    true,
                    "",
                    serverChanged && previous.isLinked()
                            ? "Obico server changed; link this printer again"
                            : "Obico settings saved"
            );
        } catch (RuntimeException failure) {
            return publicState(false, settingsFailureMessage(), "");
        }
    }

    @Override
    public synchronized JSONObject beginLink(String sixDigitCode) {
        if (settings.isLinked()) {
            return publicState(false, "This printer is already linked to Obico", "");
        }
        String code = sixDigitCode == null ? "" : sixDigitCode.trim();
        if (!code.matches("\\d{6}")) {
            return publicState(false, "Enter the six-digit verification code", "");
        }

        long attempt = linkAttempt.incrementAndGet();
        testPending = false;
        testMessage = "";
        linkStatus = new ObicoLinkStatus(
                ObicoLinkStatus.State.VERIFYING,
                "Verifying the Obico code",
                System.currentTimeMillis()
        );
        connector.link(
                settings.getServerUrl(),
                settings.isAllowInsecureServer(),
                code,
                result -> finishLink(attempt, result)
        );
        return publicState(true, "", "");
    }

    private synchronized void finishLink(long attempt, ObicoLinkResult result) {
        if (linkAttempt.get() != attempt || result == null) return;
        if (!result.isSuccess()) {
            linkStatus = new ObicoLinkStatus(
                    ObicoLinkStatus.State.FAILED,
                    safeMessage(result.getMessage(), "Obico linking failed"),
                    System.currentTimeMillis()
            );
            return;
        }

        ObicoSettings linked = settings.withAuthToken(result.getAuthToken());
        try {
            settingsStore.save(linked);
            settings = linked;
            connector.applySettings(linked);
            connectionStatus = connector.getStatus();
            linkStatus = new ObicoLinkStatus(
                    ObicoLinkStatus.State.LINKED,
                    "Linked to Obico",
                    System.currentTimeMillis()
            );
        } catch (RuntimeException secureStoreFailure) {
            linkStatus = new ObicoLinkStatus(
                    ObicoLinkStatus.State.FAILED,
                    settingsFailureMessage(),
                    System.currentTimeMillis()
            );
            logSafe("Obico link credential could not be stored securely");
        }
    }

    @Override
    public synchronized JSONObject testConnection() {
        if (!settings.isLinked()) {
            return publicState(false, "Link this printer to Obico first", "");
        }
        if (testPending) {
            return publicState(false, "An Obico connection test is already running", "");
        }
        testPending = true;
        testMessage = "Testing the Obico connection";
        long attempt = connectionTestAttempt.incrementAndGet();
        ObicoSettings current = settings;
        connector.testConnection(
                current.getServerUrl(),
                current.isAllowInsecureServer(),
                current.getAuthToken(),
                result -> {
                    if (connectionTestAttempt.get() != attempt) return;
                    testMessage = safeMessage(result, "Obico connection test finished");
                    testPending = false;
                }
        );
        return publicState(true, "", "");
    }

    @Override
    public synchronized JSONObject disconnect() {
        linkAttempt.incrementAndGet();
        connectionTestAttempt.incrementAndGet();
        ObicoSettings previous = settings;
        ObicoSettings unlinked = new ObicoSettings(
                false,
                previous.getServerUrl(),
                "",
                previous.isRemoteControlEnabled(),
                previous.isCameraUploadsEnabled(),
                previous.isAllowInsecureServer()
        );
        try {
            settingsStore.clearToken();
            settings = unlinked;
            cancelCloudPrintPreparation("Obico was unlinked");
            nativeCommandExecutor.cancelPending("Obico was unlinked");
            connector.applySettings(unlinked);
            connectionStatus = connector.getStatus();
            linkStatus = idleLinkStatus("");
            testPending = false;
            testMessage = "";
            releaseCamera();
            return publicState(true, "", "Printer unlinked from Obico");
        } catch (RuntimeException failure) {
            return publicState(false, settingsFailureMessage(), "");
        }
    }

    private ObicoPrinterSnapshot getPrinterSnapshot() {
        MachineStatus machineStatus = machine.getMachineStatusSubjectHolder().getValue();
        MachineInfo machineInfo = machine.getMachineInfoSubjectHolder().getValue();
        NewPrintController printController = machine.getNewPrintController();
        boolean connected = machineStatus != null && machineStatus.connected;
        boolean fdmMode = machineInfo != null
                && machineInfo.moduleList != null
                && machineInfo.workType == IMachine.WorkType.FDM;
        boolean emergencyStop = appService.getEmergencyStopState()
                != ErrorController.EmergencyStopState.EMERGENCY_STOP_STATE_NORMAL;
        int state = printController == null
                ? (machineStatus == null ? 0 : machineStatus.status)
                : printController.getPrintState();

        if (connected && fdmMode) refreshTelemetryIfDue();
        List<ObicoTemperature> temperatures = connected && fdmMode
                ? readTemperatures(machineInfo)
                : new ArrayList<>();
        float rawProgress = printController == null ? 0f : printController.getProgress();
        double progress = Float.isNaN(rawProgress) || Float.isInfinite(rawProgress)
                ? 0.0d
                : Math.max(0.0d, Math.min(1.0d, rawProgress));
        long elapsedSeconds = printController == null
                ? 0L
                : Math.max(0, printController.getTickCounter().getCount());
        int totalLines = printController == null ? 0 : printController.getTotalLines();
        if (totalLines <= 0) totalLines = Math.max(0, workspace.getFileTotalLineCount());
        long currentLine = totalLines > 0 ? Math.round(progress * totalLines) : 0L;
        float rawEstimate = workspace.getEstimatedTime();
        Long estimatedSeconds = Float.isNaN(rawEstimate)
                || Float.isInfinite(rawEstimate)
                || rawEstimate <= 0f
                ? null
                : (long) Math.round(rawEstimate);
        Double currentZ = machineStatus == null || machineStatus.currentPosition == null
                ? null
                : finiteDouble(machineStatus.currentPosition.getZ());
        long now = System.currentTimeMillis();
        String fileName = workspace.getFileName();
        Long startedAt = resolveJobStartedAt(
                fdmMode && FabScreenObicoSnapshotMapper.isActiveMachineStatus(state),
                fileName,
                elapsedSeconds,
                now
        );

        ObicoPrinterSnapshot snapshot = FabScreenObicoSnapshotMapper.map(
                FabScreenObicoSnapshotMapper.Input.builder()
                        .connected(connected)
                        .fdmMode(fdmMode)
                        .emergencyStop(emergencyStop)
                        .machineStatus(state)
                        .fileName(fileName)
                        .progressRatio(progress)
                        .elapsedSeconds(elapsedSeconds)
                        .estimatedTotalSeconds(estimatedSeconds)
                        .currentLine(currentLine)
                        .totalLines(totalLines)
                        .currentZ(currentZ)
                        .startedAtMillis(startedAt)
                        .capturedAtMillis(now)
                        .temperatures(temperatures)
                        .build()
        );
        Long cloudFileId = snapshot.getState().isActive()
                ? activeObicoGcodeFileId
                : null;
        return snapshot.withRemoteState(
                cloudFileId,
                currentFeedRate,
                currentFlowRate,
                currentFanSpeed);
    }

    private Long resolveJobStartedAt(
            boolean active,
            String fileName,
            long elapsedSeconds,
            long capturedAtMillis
    ) {
        synchronized (jobClockLock) {
            if (!active) {
                clockFileName = "";
                clockStartedAtMillis = 0L;
                return null;
            }
            String cleanFileName = fileName == null ? "" : fileName.trim();
            if (clockStartedAtMillis <= 0L || !cleanFileName.equals(clockFileName)) {
                long elapsedMillis;
                try {
                    elapsedMillis = Math.multiplyExact(Math.max(0L, elapsedSeconds), 1_000L);
                } catch (ArithmeticException ignored) {
                    elapsedMillis = 0L;
                }
                clockStartedAtMillis = Math.max(0L, capturedAtMillis - elapsedMillis);
                clockFileName = cleanFileName;
            }
            return clockStartedAtMillis;
        }
    }

    private void refreshTelemetryIfDue() {
        long now = SystemClock.elapsedRealtime();
        synchronized (this) {
            if (now - lastTelemetryRefreshElapsed < TELEMETRY_REFRESH_MILLIS
                    || !telemetryRefreshPending.compareAndSet(false, true)) return;
            lastTelemetryRefreshElapsed = now;
        }
        boolean posted = mainHandler.post(() -> {
            AtomicInteger outstanding = new AtomicInteger(1);
            try {
                MachineStatus liveStatus = machine.getMachineStatusSubjectHolder().getValue();
                MachineInfo liveInfo = machine.getMachineInfoSubjectHolder().getValue();
                if (liveStatus == null
                        || !liveStatus.connected
                        || liveInfo == null
                        || liveInfo.workType != IMachine.WorkType.FDM) {
                    return;
                }
                FDMController fdmController = machine.getFDMController();
                if (fdmController != null) {
                    int count = Math.max(0, fdmController.getToolHeadCounts());
                    for (int index = 0; index < count; index++) {
                        FdmToolhead toolhead = fdmController.getFdmToolhead(index);
                        if (toolhead != null) {
                            trackTelemetryRefresh(
                                    toolhead.requestInfo(),
                                    outstanding,
                                    "Obico toolhead telemetry refresh failed");
                        }
                    }
                }
                HeatedBed heatedBed = findHeatedBed(liveInfo);
                if (heatedBed != null) {
                    trackTelemetryRefresh(
                            heatedBed.requestInfo(),
                            outstanding,
                            "Obico heated-bed telemetry refresh failed");
                }
            } catch (RuntimeException failure) {
                logSafe("Obico telemetry refresh could not be queued");
            } finally {
                if (outstanding.decrementAndGet() == 0) {
                    telemetryRefreshPending.set(false);
                }
            }
        });
        if (!posted) {
            synchronized (this) {
                lastTelemetryRefreshElapsed = 0L;
            }
            telemetryRefreshPending.set(false);
        }
    }

    private void trackTelemetryRefresh(
            io.reactivex.Observable<?> request,
            AtomicInteger outstanding,
            String failureMessage
    ) {
        if (request == null) return;
        outstanding.incrementAndGet();
        try {
            request.timeout(TELEMETRY_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .take(1)
                    .doFinally(() -> {
                        if (outstanding.decrementAndGet() == 0) {
                            telemetryRefreshPending.set(false);
                        }
                    })
                    .subscribe(ignored -> { }, error -> logSafe(failureMessage));
        } catch (RuntimeException failure) {
            if (outstanding.decrementAndGet() == 0) {
                telemetryRefreshPending.set(false);
            }
            logSafe(failureMessage);
        }
    }

    private List<ObicoTemperature> readTemperatures(MachineInfo machineInfo) {
        List<ObicoTemperature> result = new ArrayList<>();
        Set<String> names = new HashSet<>();
        FDMController fdmController = machine.getFDMController();
        if (fdmController != null) {
            int count = Math.max(0, fdmController.getToolHeadCounts());
            int fallbackIndex = 0;
            for (int toolheadIndex = 0; toolheadIndex < count; toolheadIndex++) {
                FdmToolhead toolhead = fdmController.getFdmToolhead(toolheadIndex);
                if (toolhead == null || !toolhead.isExtruderStatusFresh(TELEMETRY_FRESH_MILLIS)) {
                    continue;
                }
                FdmToolhead.FdmToolheadStatus status =
                        toolhead.getToolheadStatusSubjectHolder().getValue();
                if (status == null || status.getExtruderList() == null) continue;
                for (Extruder extruder : status.getExtruderList()) {
                    if (extruder == null) continue;
                    String preferred = "tool" + Math.max(0, extruder.getId());
                    String name = names.add(preferred)
                            ? preferred
                            : nextTemperatureName(names, "tool", fallbackIndex);
                    fallbackIndex++;
                    result.add(new ObicoTemperature(
                            name,
                            extruder.getTemperature(),
                            (double) extruder.getTargetTemperature()
                    ));
                }
            }
        }

        HeatedBed heatedBed = findHeatedBed(machineInfo);
        if (heatedBed != null && heatedBed.isHeatedBedStatusFresh(TELEMETRY_FRESH_MILLIS)) {
            HeatedBed.HeatedBedStatus status =
                    heatedBed.getHeatedBedStatusSubjectHolder().getValue();
            if (status != null && status.getZoneList() != null) {
                for (HeatedBed.ZoneInfo zone : status.getZoneList()) {
                    if (zone == null) continue;
                    String preferred = zone.getZoneIndex() == 0
                            ? "bed"
                            : "bed" + zone.getZoneIndex();
                    String name = names.add(preferred)
                            ? preferred
                            : nextTemperatureName(names, "bed", zone.getZoneIndex());
                    result.add(new ObicoTemperature(
                            name,
                            zone.getCurrentTemperature(),
                            (double) zone.getTargetTemperature()
                    ));
                }
            }
        }
        return result;
    }

    private static String nextTemperatureName(Set<String> names, String prefix, int start) {
        int suffix = Math.max(0, start);
        String candidate;
        do {
            candidate = prefix + suffix++;
        } while (!names.add(candidate));
        return candidate;
    }

    private static HeatedBed findHeatedBed(MachineInfo machineInfo) {
        if (machineInfo == null || machineInfo.moduleList == null) return null;
        for (Module module : machineInfo.moduleList) {
            if (module instanceof HeatedBed) return (HeatedBed) module;
        }
        return null;
    }

    private byte[] getCameraJpeg() {
        ObicoSettings current = settings;
        if (!current.isEnabled() || !current.isLinked() || !current.isCameraUploadsEnabled()) {
            releaseCamera();
            return null;
        }
        UvcCameraManager.Frame frame = cameraManager.awaitFrame(
                CAMERA_CLIENT_ID,
                lastCameraSequence,
                CAMERA_WAIT_MILLIS
        );
        if (frame == null || frame.getJpeg() == null) return null;
        lastCameraSequence = frame.getSequence();
        return frame.getJpeg();
    }

    private void releaseCamera() {
        lastCameraSequence = -1L;
        try {
            cameraManager.releaseClient(CAMERA_CLIENT_ID);
        } catch (RuntimeException ignored) {
            logSafe("Obico camera lease could not be released");
        }
    }

    private boolean handleRemoteCommand(ObicoRemoteCommand.Type command) {
        FabScreenObicoCommandPolicy.Decision firstDecision = commandDecision(
                command,
                commandPending.get()
        );
        if (!firstDecision.isAccepted()) {
            logSafe("Obico remote command rejected: " + firstDecision.getReason());
            return false;
        }
        if (!commandPending.compareAndSet(false, true)) return false;

        long commandId = nextCommandId.incrementAndGet();
        synchronized (commandLock) {
            activeCommandId = commandId;
            activeCommandType = command;
        }
        Runnable timeout = () -> finishRemoteCommand(commandId, "timed out");
        synchronized (commandLock) {
            activeCommandTimeout = timeout;
        }
        if (!mainHandler.postDelayed(timeout, COMMAND_TIMEOUT_MILLIS)) {
            finishRemoteCommand(commandId, "queue failed");
            return false;
        }

        boolean posted = mainHandler.post(() -> executeRemoteCommandOnMain(commandId, command));
        if (!posted) {
            finishRemoteCommand(commandId, "queue failed");
            return false;
        }
        return true;
    }

    private void handlePassthruCommand(
            ObicoPassthruRequest request,
            ObicoConnector.PassthruCallback callback
    ) {
        if (request == null || callback == null) return;
        if (request.getType() == ObicoPassthruRequest.Type.LIST_FILES) {
            handleLocalFileList(request, callback);
            return;
        }
        if (request.getType() == ObicoPassthruRequest.Type.SELECT_FILE) {
            handleLocalFileSelection(request, callback);
            return;
        }
        if (request.getType() == ObicoPassthruRequest.Type.DOWNLOAD_FILE) {
            handleCloudFileRequest(request, callback);
            return;
        }
        ObicoNativeCommandPolicy.Decision decision = passthruDecision(request.getType(), false);
        if (!decision.isAccepted()) {
            callback.onError(decision.getReason());
            return;
        }
        boolean posted = mainHandler.post(() -> {
            ObicoNativeCommandPolicy.Decision liveDecision = passthruDecision(
                    request.getType(),
                    false);
            if (!liveDecision.isAccepted()) {
                callback.onError(liveDecision.getReason());
                return;
            }
            nativeCommandExecutor.executeOnMainThread(
                    request,
                    new ObicoNativeCommandExecutor.Completion() {
                        @Override
                        public void onSuccess() {
                            updateReportedTuneState(request);
                            connector.requestImmediateTelemetry();
                            callback.onSuccess();
                        }

                        @Override
                        public void onDownloadAccepted(String targetPath) {
                            callback.onError("Unexpected Obico file response");
                        }

                        @Override
                        public void onError(String error) {
                            callback.onError(error);
                        }
                    });
        });
        if (!posted) callback.onError("FabScreen could not queue this printer control");
    }

    private void handleLocalFileList(
            ObicoPassthruRequest request,
            ObicoConnector.PassthruCallback callback
    ) {
        ObicoNativeCommandPolicy.Decision decision = passthruDecision(request.getType(), false);
        if (!decision.isAccepted()) {
            callback.onError(decision.getReason());
            return;
        }
        Schedulers.io().scheduleDirect(() -> {
            try {
                ObicoNativeCommandPolicy.Decision liveDecision = passthruDecision(
                        request.getType(), false);
                if (!liveDecision.isAccepted()) {
                    callback.onError(liveDecision.getReason());
                    return;
                }
                callback.onJsonResult(ObicoFileBrowser.listFiles(
                        appService,
                        ServiceContainer.getInstance().getService(IFileManagerService.class),
                        request.getListPath(),
                        request.isListRecursive(),
                        request.getListLevel(),
                        request.getListFilter()));
            } catch (IOException | RuntimeException failure) {
                callback.onError("Artisan G-code files could not be listed");
            }
        });
    }

    private void handleLocalFileSelection(
            ObicoPassthruRequest request,
            ObicoConnector.PassthruCallback callback
    ) {
        if (!request.isPrintAfterSelect()) {
            callback.onError("Select a file with Print to start it on Artisan");
            return;
        }
        ObicoNativeCommandPolicy.Decision decision = passthruDecision(request.getType(), false);
        if (!decision.isAccepted()) {
            callback.onError(decision.getReason());
            return;
        }
        if (!cloudPrintPending.compareAndSet(false, true)) {
            callback.onError("Another Obico file is already being prepared");
            return;
        }
        final long generation;
        synchronized (cloudPrintLock) {
            generation = ++cloudPrintGeneration;
            activeCloudPreparationLease = 0L;
            activeCloudStartIssued = false;
        }
        boolean posted = mainHandler.post(() -> {
            ObicoNativeCommandPolicy.Decision liveDecision = passthruDecision(
                    request.getType(), true);
            if (!liveDecision.isAccepted()) {
                callback.onError(liveDecision.getReason());
                finishCloudPrintPreparation(generation, false, false);
                return;
            }
            final IFile file;
            try {
                file = ObicoFileBrowser.resolveFile(
                        appService,
                        ServiceContainer.getInstance().getService(IFileManagerService.class),
                        request.getSelectedFilePath());
            } catch (IOException | RuntimeException failure) {
                callback.onError("Selected Artisan G-code file is unavailable");
                finishCloudPrintPreparation(generation, false, false);
                return;
            }
            // Match cloud downloads: the passthrough acknowledgement means preparation was
            // accepted; PrintStarted confirms that the controller actually began the job.
            callback.onSuccess();
            beginCloudFileParse(generation, file, null,
                    ObicoPassthruRequest.Type.SELECT_FILE);
        });
        if (!posted) {
            callback.onError("FabScreen could not queue this file");
            finishCloudPrintPreparation(generation, false, false);
        }
    }

    private void updateReportedTuneState(ObicoPassthruRequest request) {
        switch (request.getType()) {
            case SET_PRINT_SPEED:
                currentFeedRate = request.getPercentage() / 100.0d;
                break;
            case SET_FLOW_RATE:
                currentFlowRate = request.getPercentage() / 100.0d;
                break;
            case SET_FAN_SPEED:
                currentFanSpeed = request.getFanSpeed() / 255.0d;
                break;
            default:
                break;
        }
    }

    private void handleCloudFileRequest(
            ObicoPassthruRequest request,
            ObicoConnector.PassthruCallback callback
    ) {
        ObicoNativeCommandPolicy.Decision decision = passthruDecision(request.getType(), false);
        if (!decision.isAccepted()) {
            callback.onError(decision.getReason());
            return;
        }
        if (!cloudPrintPending.compareAndSet(false, true)) {
            callback.onError("Another Obico file is already being prepared");
            return;
        }

        ObicoPassthruRequest.DownloadFile download = request.getDownloadFile();
        if (download == null) {
            cloudPrintPending.set(false);
            callback.onError("Obico did not supply a G-code file");
            return;
        }
        final long generation;
        synchronized (cloudPrintLock) {
            generation = ++cloudPrintGeneration;
            activeCloudPreparationLease = 0L;
            activeCloudStartIssued = false;
        }
        ObicoSettings current = settings;
        try {
            ObicoGcodeDownloader.DownloadHandle handle = gcodeDownloader.download(
                    appService.getFilesDir(),
                    current.getServerUrl(),
                    current.isAllowInsecureServer(),
                    download.getUrl(),
                    download.getSafeFilename(),
                    new ObicoGcodeDownloader.ResultCallback() {
                        @Override
                        public void onSuccess(File file) {
                            boolean posted = mainHandler.post(() -> beginCloudFileParse(
                                    generation,
                                    new FabLocalFile(file),
                                    download.getId(),
                                    ObicoPassthruRequest.Type.DOWNLOAD_FILE));
                            if (!posted) {
                                callback.onError("FabScreen could not prepare the downloaded file");
                                finishCloudPrintPreparation(generation, false, false);
                            }
                        }

                        @Override
                        public void onError(String safeMessage) {
                            callback.onError(safeMessage);
                            finishCloudPrintPreparation(generation, false, false);
                            logSafe("Obico cloud G-code download failed");
                        }
                    });
            synchronized (cloudPrintLock) {
                if (cloudPrintPending.get() && cloudPrintGeneration == generation) {
                    activeCloudDownload = handle;
                } else {
                    handle.cancel();
                    return;
                }
            }
            // Match Obico's official downloader: acknowledge once the bounded background transfer
            // is accepted. The eventual PrintStarted transition confirms the actual controller job.
            callback.onDownloadAccepted(download.getSafeFilename());
        } catch (RuntimeException failure) {
            finishCloudPrintPreparation(generation, false, false);
            callback.onError("The Obico G-code download could not be started");
        }
    }

    private void beginCloudFileParse(
            long generation,
            IFile file,
            String cloudFileId,
            ObicoPassthruRequest.Type fileRequestType
    ) {
        if (!isCurrentCloudPrint(generation)) return;
        ObicoNativeCommandPolicy.Decision decision = passthruDecision(
                fileRequestType,
                true);
        if (!decision.isAccepted()) {
            logSafe("Obico cloud print stopped before parsing: " + decision.getReason());
            finishCloudPrintPreparation(generation, false, false);
            return;
        }
        if (file == null || !file.exists() || file.isDirectory() || file.length() <= 0L) {
            logSafe("Obico cloud print stopped: downloaded file is empty");
            finishCloudPrintPreparation(generation, false, false);
            return;
        }

        GcodeParser parser = new GcodeParser();
        Disposable parseDisposable = parser.getParseProgressObservable()
                .filter(progress -> progress == 100 || progress == -1)
                .take(1)
                .timeout(CLOUD_PARSE_TIMEOUT_MINUTES, TimeUnit.MINUTES)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(progress -> {
                    if (progress == 100) {
                        prepareAndStartCloudPrint(
                                generation, file, cloudFileId, fileRequestType, parser);
                    } else {
                        logSafe("Obico cloud print stopped: G-code analysis failed");
                        finishCloudPrintPreparation(generation, false, false);
                    }
                }, error -> {
                    logSafe("Obico cloud print stopped: G-code analysis timed out or failed");
                    finishCloudPrintPreparation(generation, false, false);
                });
        synchronized (cloudPrintLock) {
            if (!cloudPrintPending.get() || cloudPrintGeneration != generation) {
                parseDisposable.dispose();
                parser.destroy();
                return;
            }
            activeCloudDownload = null;
            activeCloudParser = parser;
            activeCloudParseDisposable = parseDisposable;
        }
        try {
            parser.startParse(file, IMachine.WorkType.FDM);
        } catch (RuntimeException failure) {
            logSafe("Obico cloud print stopped: G-code analysis could not start");
            finishCloudPrintPreparation(generation, false, false);
        }
    }

    private void prepareAndStartCloudPrint(
            long generation,
            IFile file,
            String cloudFileId,
            ObicoPassthruRequest.Type fileRequestType,
            GcodeParser parser
    ) {
        if (!isCurrentCloudPrint(generation)) return;
        ObicoNativeCommandPolicy.Decision decision = passthruDecision(
                fileRequestType,
                true);
        if (!decision.isAccepted()) {
            logSafe("Obico cloud print stopped before start: " + decision.getReason());
            finishCloudPrintPreparation(generation, false, false);
            return;
        }
        int totalLines = parser.getTotalLinesCount();
        String md5 = parser.getLastMd5ForAnalysis();
        if (parser.getFileType() != IMachine.WorkType.FDM
                || totalLines <= 0
                || md5 == null
                || md5.trim().isEmpty()) {
            logSafe("Obico cloud print stopped: invalid G-code analysis result");
            finishCloudPrintPreparation(generation, false, false);
            return;
        }

        NewPrintController controller = machine.getNewPrintController();
        if (controller == null) {
            finishCloudPrintPreparation(generation, false, false);
            return;
        }
        long lease = controller.tryAcquirePrintPreparationLease();
        if (lease == 0L) {
            logSafe("Obico cloud print stopped: print workspace is busy");
            finishCloudPrintPreparation(generation, false, false);
            return;
        }
        synchronized (cloudPrintLock) {
            if (!cloudPrintPending.get() || cloudPrintGeneration != generation) {
                controller.abortPrintPreparation(lease);
                return;
            }
            activeCloudPreparationLease = lease;
        }

        IFile printFile = file;
        boolean workspaceApplied;
        try {
            workspaceApplied = controller.runWithPrintPreparationLease(lease, () -> {
                populateCloudPrintWorkspace(parser, md5);
                workspace.setPrintFile(printFile);
            });
        } catch (RuntimeException failure) {
            workspaceApplied = false;
        }
        if (!workspaceApplied) {
            logSafe("Obico cloud print stopped: print workspace reservation was lost");
            finishCloudPrintPreparation(generation, false, false);
            return;
        }

        adoptCloudPrintThumbnail(parser);
        Observable<fabscreen.platform.base.service.machine.controller.PrintEvent> events =
                controller.preparePrintStart(lease, printFile, totalLines);
        if (events == null) {
            finishCloudPrintPreparation(generation, false, false);
            return;
        }
        Disposable startDisposable = events
                .filter(event -> event.getPrintEventState() == PrintEventState.STATE_SUCCESS
                        || event.getPrintEventState() == PrintEventState.START_FAIL
                        || event.getPrintEventState() == PrintEventState.FINISH_FAIL)
                .take(1)
                .timeout(CLOUD_START_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(event -> {
                    if (event.getPrintEventState() == PrintEventState.STATE_SUCCESS) {
                        controller.markAttachStartedRemotePrint();
                        finishCloudPrintPreparation(generation, true, true);
                        routeToCloudPrintPage();
                    } else if (controller.isPrintStartOutcomeUncertain()) {
                        // NewPrintController reports a lost START result as FINISH_FAIL after
                        // marking the outcome uncertain. The firmware may still have accepted the
                        // immutable file, so retain its Obico id and the controller's workspace
                        // gate exactly as the dashboard remote-start path does.
                        logSafe("Obico cloud print start result was not received");
                        finishCloudPrintPreparation(generation, true, false);
                    } else {
                        logSafe("Obico cloud print start was rejected by the controller");
                        finishCloudPrintPreparation(generation, false, false);
                    }
                }, error -> {
                    // The START packet may have reached firmware even when its reply was lost.
                    // Keep the immutable file and close the shared workspace gate until restart.
                    controller.markPrintStartOutcomeUncertain();
                    logSafe("Obico cloud print start result was not received");
                    finishCloudPrintPreparation(generation, true, false);
                });
        synchronized (cloudPrintLock) {
            if (!cloudPrintPending.get() || cloudPrintGeneration != generation) {
                startDisposable.dispose();
                controller.abortPrintPreparation(lease);
                return;
            }
            activeCloudStartDisposable = startDisposable;
        }

        boolean issued = false;
        String dispatchRejection = "";
        synchronized (this) {
            // Configuration changes and unlink are synchronized on the same service instance.
            // Linearize the last authorization/state check with the irreversible controller
            // dispatch so revocation cannot cancel local tracking while START is being issued.
            ObicoNativeCommandPolicy.Decision dispatchDecision = passthruDecision(
                    fileRequestType,
                    true);
            if (!dispatchDecision.isAccepted()) {
                dispatchRejection = dispatchDecision.getReason();
            } else {
                synchronized (cloudPrintLock) {
                    if (!cloudPrintPending.get() || cloudPrintGeneration != generation) return;
                }
                try {
                    activeObicoGcodeFileId = Long.valueOf(cloudFileId);
                } catch (RuntimeException ignored) {
                    activeObicoGcodeFileId = null;
                }
                controller.setStartFromRemoteFlag(true);
                try {
                    issued = controller.start(lease);
                } catch (RuntimeException failure) {
                    issued = false;
                }
                synchronized (cloudPrintLock) {
                    if (cloudPrintGeneration == generation) activeCloudStartIssued = issued;
                }
            }
        }
        if (!dispatchRejection.isEmpty()) {
            logSafe("Obico cloud print stopped before dispatch: " + dispatchRejection);
            finishCloudPrintPreparation(generation, false, false);
            return;
        }
        if (!issued) {
            activeObicoGcodeFileId = null;
            controller.setStartFromRemoteFlag(false);
            logSafe("Obico cloud print start could not be issued");
            finishCloudPrintPreparation(generation, false, false);
        }
    }

    private void populateCloudPrintWorkspace(GcodeParser parser, String md5) {
        workspace.setPrintMode(parser.getCustomPrintMode());
        workspace.setPrintSource(0);
        workspace.setFileTotalLineCount(parser.getTotalLinesCount());
        workspace.setEstimatedTime(parser.getEstimatedTime());
        workspace.setFileMD5Value(md5);
        workspace.setModelBoundary(parser.getBoundary());
        workspace.setApplyMultiExtruder(parser.isApplyMultiExtruder());
        workspace.setPrintModeXOffset(0f);

        boolean usageKnown = parser.isToolUsageConfirmed();
        boolean usesLeft = parser.isTool0Used();
        boolean usesRight = parser.isTool1Used();
        float left = usageKnown && !usesLeft ? 0f : parser.getNozzleTargetTemperature();
        float right = usageKnown && !usesRight ? 0f : parser.getNozzleTarget_1_Temperature();
        if ((usageKnown && usesRight) || (!usageKnown && right > 0f)) {
            workspace.setWorkTemperature(new float[]{left, right});
        } else {
            workspace.setWorkTemperature(new float[]{left});
        }
    }

    private void adoptCloudPrintThumbnail(GcodeParser parser) {
        try {
            IGcodeParser shared = ServiceContainer.getInstance().getService(IGcodeParser.class);
            if (shared instanceof GcodeParser) {
                ((GcodeParser) shared).adoptRemotePrintThumbnail(
                        parser.getGcodeThumbnail(),
                        parser.getGcodeThumbnailBytes());
            }
        } catch (RuntimeException ignored) {
            logSafe("Obico cloud-print thumbnail could not be attached to the print screen");
        }
    }

    private void routeToCloudPrintPage() {
        try {
            if (appService.getNowViewContext() != null) {
                ServiceContainer.getInstance()
                        .getService(IRouter.class)
                        .routeToPrintPage()
                        .start(appService.getNowViewContext());
            }
        } catch (RuntimeException ignored) {
            logSafe("Obico print started headlessly; the local print page was not opened");
        }
    }

    private boolean isCurrentCloudPrint(long generation) {
        synchronized (cloudPrintLock) {
            return cloudPrintPending.get() && cloudPrintGeneration == generation;
        }
    }

    private ObicoNativeCommandPolicy.Decision passthruDecision(
            ObicoPassthruRequest.Type type,
            boolean ignoreCloudPending
    ) {
        MachineStatus status = machine.getMachineStatusSubjectHolder().getValue();
        MachineInfo info = machine.getMachineInfoSubjectHolder().getValue();
        NewPrintController controller = machine.getNewPrintController();
        int machineState = controller == null
                ? status == null ? 0 : status.status
                : controller.getPrintState();
        boolean controlsEnabled = settings.isEnabled()
                && settings.isLinked()
                && settings.isRemoteControlEnabled();
        boolean fileBrowsingEnabled = settings.isEnabled() && settings.isLinked();
        return ObicoNativeCommandPolicy.evaluate(
                type,
                controlsEnabled,
                fileBrowsingEnabled,
                status != null && status.connected,
                info != null && info.moduleList != null
                        && info.workType == IMachine.WorkType.FDM,
                appService.getEmergencyStopState()
                        != ErrorController.EmergencyStopState.EMERGENCY_STOP_STATE_NORMAL,
                commandPending.get()
                        || nativeCommandExecutor.isPending()
                        || (!ignoreCloudPending && cloudPrintPending.get()),
                status != null && status.isHomed,
                status != null && status.isHoming,
                machineState);
    }

    private void cancelCloudPrintPreparation(String reason) {
        final long generation;
        final boolean startIssued;
        synchronized (cloudPrintLock) {
            if (!cloudPrintPending.get()) return;
            generation = cloudPrintGeneration;
            startIssued = activeCloudStartIssued;
        }
        // Once START has been accepted into the native controller it cannot be recalled safely.
        // Keep observing its definitive success/rejection (or timeout) instead of disposing that
        // evidence and turning a real firmware job into an untracked, headless print.
        if (startIssued) {
            logSafe(reason + "; the print start is already settling");
            return;
        }
        logSafe(reason);
        ObicoGcodeDownloader.DownloadHandle handle;
        synchronized (cloudPrintLock) {
            handle = activeCloudDownload;
        }
        if (handle != null) handle.cancel();
        finishCloudPrintPreparation(generation, false, false);
    }

    private void finishCloudPrintPreparation(
            long generation,
            boolean keepRemoteFileId,
            boolean startedSuccessfully
    ) {
        ObicoGcodeDownloader.DownloadHandle download;
        GcodeParser parser;
        Disposable parseDisposable;
        Disposable startDisposable;
        long lease;
        boolean startIssued;
        synchronized (cloudPrintLock) {
            if (cloudPrintGeneration != generation) return;
            cloudPrintGeneration++;
            cloudPrintPending.set(false);
            download = activeCloudDownload;
            activeCloudDownload = null;
            parser = activeCloudParser;
            activeCloudParser = null;
            parseDisposable = activeCloudParseDisposable;
            activeCloudParseDisposable = null;
            startDisposable = activeCloudStartDisposable;
            activeCloudStartDisposable = null;
            lease = activeCloudPreparationLease;
            activeCloudPreparationLease = 0L;
            startIssued = activeCloudStartIssued;
            activeCloudStartIssued = false;
        }
        if (download != null && !startedSuccessfully) download.cancel();
        if (parseDisposable != null && !parseDisposable.isDisposed()) parseDisposable.dispose();
        if (startDisposable != null && !startDisposable.isDisposed()) startDisposable.dispose();
        if (parser != null) parser.destroy();
        NewPrintController controller = machine.getNewPrintController();
        if (controller != null) {
            if (lease > 0L && !startIssued) controller.abortPrintPreparation(lease);
            // The controller clears this flag itself on success. Clear it again for every
            // terminal cleanup so a rejected or uncertain remote START cannot permanently block
            // later local/dashboard print preparation.
            controller.setStartFromRemoteFlag(false);
        }
        if (!keepRemoteFileId) activeObicoGcodeFileId = null;
    }

    private void executeRemoteCommandOnMain(
            long commandId,
            ObicoRemoteCommand.Type command
    ) {
        synchronized (commandLock) {
            if (!commandPending.get() || activeCommandId != commandId) return;
        }
        FabScreenObicoCommandPolicy.Decision liveDecision = commandDecision(command, false);
        if (!liveDecision.isAccepted()) {
            finishRemoteCommand(commandId, "state changed");
            return;
        }

        NewPrintController controller = machine.getNewPrintController();
        if (controller == null) {
            finishRemoteCommand(commandId, "controller unavailable");
            return;
        }
        Disposable disposable = controller.getPrintEventObservable()
                .filter(event -> matches(command, event.getPrintEventState()))
                .take(1)
                .subscribe(
                        event -> finishRemoteCommand(
                                commandId,
                                isSuccess(
                                        event.getPrintEventState(),
                                        event.getErrorCode()
                                ) ? "succeeded" : "failed"
                        ),
                        error -> finishRemoteCommand(commandId, "failed")
                );
        synchronized (commandLock) {
            if (commandPending.get() && activeCommandId == commandId) {
                activeCommandDisposable = disposable;
            } else {
                disposable.dispose();
                return;
            }
        }

        final boolean started;
        synchronized (this) {
            // Configuration changes and unlink are synchronized on the same service instance.
            // Linearize revocation with the final state check and controller dispatch so an
            // already-queued cloud command cannot slip through after access is removed.
            FabScreenObicoCommandPolicy.Decision dispatchDecision =
                    commandDecision(command, false);
            if (!dispatchDecision.isAccepted()) {
                finishRemoteCommand(commandId, "authorization changed");
                return;
            }
            try {
                switch (command) {
                    case PAUSE:
                        started = controller.pause();
                        break;
                    case RESUME:
                        started = controller.resume();
                        break;
                    case CANCEL:
                        started = controller.stop();
                        break;
                    default:
                        finishRemoteCommand(commandId, "unsupported");
                        return;
                }
            } catch (RuntimeException failure) {
                finishRemoteCommand(commandId, "failed");
                return;
            }
        }
        if (!started) finishRemoteCommand(commandId, "busy");
    }

    private FabScreenObicoCommandPolicy.Decision commandDecision(
            ObicoRemoteCommand.Type command,
            boolean pending
    ) {
        MachineStatus machineStatus = machine.getMachineStatusSubjectHolder().getValue();
        MachineInfo machineInfo = machine.getMachineInfoSubjectHolder().getValue();
        NewPrintController controller = machine.getNewPrintController();
        boolean connected = machineStatus != null && machineStatus.connected;
        boolean fdmMode = machineInfo != null
                && machineInfo.moduleList != null
                && machineInfo.workType == IMachine.WorkType.FDM;
        boolean emergencyStop = appService.getEmergencyStopState()
                != ErrorController.EmergencyStopState.EMERGENCY_STOP_STATE_NORMAL;
        int state = controller == null
                ? (machineStatus == null ? 0 : machineStatus.status)
                : controller.getPrintState();
        return FabScreenObicoCommandPolicy.evaluate(
                command,
                settings.isEnabled()
                        && settings.isLinked()
                        && settings.isRemoteControlEnabled(),
                connected,
                fdmMode,
                emergencyStop,
                pending || nativeCommandExecutor.isPending() || cloudPrintPending.get(),
                state
        );
    }

    private void finishRemoteCommand(long commandId, String result) {
        Disposable disposable;
        Runnable timeout;
        ObicoRemoteCommand.Type commandType;
        synchronized (commandLock) {
            if (!commandPending.get() || activeCommandId != commandId) return;
            activeCommandId = 0L;
            commandPending.set(false);
            commandType = activeCommandType;
            activeCommandType = null;
            disposable = activeCommandDisposable;
            activeCommandDisposable = null;
            timeout = activeCommandTimeout;
            activeCommandTimeout = null;
        }
        if (timeout != null) mainHandler.removeCallbacks(timeout);
        if (disposable != null && !disposable.isDisposed()) disposable.dispose();
        if (commandType == ObicoRemoteCommand.Type.CANCEL && !"succeeded".equals(result)) {
            connector.clearPendingPrintCancellation();
        }
        connector.notifyRemoteCommandOutcome(commandType, result);
        logSafe("Obico remote command " + result);
    }

    private static boolean matches(
            ObicoRemoteCommand.Type command,
            PrintEventState state
    ) {
        if (command == ObicoRemoteCommand.Type.PAUSE) {
            return state == PrintEventState.PAUSE_SUCCESS || state == PrintEventState.PAUSE_FAIL;
        }
        if (command == ObicoRemoteCommand.Type.RESUME) {
            return state == PrintEventState.RESUME_SUCCESS || state == PrintEventState.RESUME_FAIL;
        }
        return state == PrintEventState.STOP_SUCCESS || state == PrintEventState.STOP_FAIL;
    }

    private static boolean isSuccess(PrintEventState state, int errorCode) {
        return state == PrintEventState.PAUSE_SUCCESS
                || state == PrintEventState.RESUME_SUCCESS
                || (state == PrintEventState.STOP_SUCCESS && errorCode != 22);
    }

    private JSONObject publicState(boolean ok, String error, String transientMessage) {
        JSONObject root = new JSONObject();
        try {
            root.put("ok", ok);
            if (!ok) root.put("error", safeMessage(error, "Obico request failed"));

            ObicoSettings currentSettings = settings;
            JSONObject config = new JSONObject();
            config.put("enabled", currentSettings.isEnabled());
            config.put("serverUrl", currentSettings.getServerUrl());
            config.put("allowInsecureServer", currentSettings.isAllowInsecureServer());
            config.put("remoteControlEnabled", currentSettings.isRemoteControlEnabled());
            config.put("cameraUploadsEnabled", currentSettings.isCameraUploadsEnabled());
            config.put("linked", currentSettings.isLinked());
            root.put("config", config);

            ObicoConnectionStatus currentStatus = connectionStatus == null
                    ? connector.getStatus()
                    : connectionStatus;
            JSONObject status = new JSONObject();
            status.put("state", currentStatus.getState().name());
            status.put("message", currentStatus.getMessage());
            status.put("updatedAtMillis", currentStatus.getUpdatedAtMillis());
            status.put("connectedAtMillis", currentStatus.getConnectedAtMillis());
            status.put("lastTelemetryAtMillis", currentStatus.getLastTelemetryAtMillis());
            status.put("lastSnapshotAtMillis", currentStatus.getLastSnapshotAtMillis());
            long snapshotAt = currentStatus.getLastSnapshotAtMillis();
            status.put("lastSnapshotAgeMillis", snapshotAt > 0L
                    ? Math.max(0L, System.currentTimeMillis() - snapshotAt) : JSONObject.NULL);
            status.put("remoteViewing", currentStatus.isRemoteViewing());
            status.put("remoteShouldWatch", currentStatus.isRemoteShouldWatch());
            status.put("lastRemoteWatchAtMillis", currentStatus.getLastRemoteWatchAtMillis());
            status.put("lastRemoteCommandAtMillis", currentStatus.getLastRemoteCommandAtMillis());
            status.put("lastRemoteCommand", currentStatus.getLastRemoteCommand());
            status.put("lastRemoteCommandResult", currentStatus.getLastRemoteCommandResult());
            status.put("reconnectAttempt", currentStatus.getReconnectAttempt());
            root.put("status", status);

            ObicoLinkStatus currentLinkStatus = linkStatus;
            boolean linking = currentLinkStatus.getState() == ObicoLinkStatus.State.VERIFYING;
            JSONObject operation = new JSONObject();
            operation.put("pending", testPending || linking);
            operation.put("state", testPending ? "TESTING" : currentLinkStatus.getState().name());
            String message = transientMessage;
            if (message == null || message.isEmpty()) {
                message = testMessage;
            }
            if ((message == null || message.isEmpty())
                    && currentLinkStatus.getState() != ObicoLinkStatus.State.IDLE) {
                message = currentLinkStatus.getMessage();
            }
            if ((message == null || message.isEmpty())
                    && !settingsStore.getLastSecurityError().isEmpty()) {
                message = settingsStore.getLastSecurityError();
            }
            operation.put("message", message == null ? "" : message);
            operation.put("updatedAtMillis", currentLinkStatus.getUpdatedAtMillis());
            root.put("operation", operation);
        } catch (JSONException impossible) {
            Log.w(TAG, "Unable to create public Obico state");
        }
        return root;
    }

    private String settingsFailureMessage() {
        String secureError = settingsStore.getLastSecurityError();
        return secureError == null || secureError.isEmpty()
                ? "Obico settings could not be saved"
                : secureError;
    }

    private static ObicoLinkStatus idleLinkStatus(String message) {
        return new ObicoLinkStatus(
                ObicoLinkStatus.State.IDLE,
                message,
                System.currentTimeMillis()
        );
    }

    private static Double finiteDouble(float value) {
        return Float.isNaN(value) || Float.isInfinite(value) ? null : (double) value;
    }

    private static String safeMessage(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) return fallback;
        String clean = value.replace('\u0000', ' ').trim();
        return clean.length() <= 256 ? clean : clean.substring(0, 256);
    }

    private void logSafe(String message) {
        // Connector and service messages are fixed strings and deliberately omit URLs, link codes,
        // tokens, and exception bodies.
        Log.i(TAG, safeMessage(message, "Obico event"));
    }
}
