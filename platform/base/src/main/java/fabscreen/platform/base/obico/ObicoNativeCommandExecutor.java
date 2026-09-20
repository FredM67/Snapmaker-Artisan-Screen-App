package fabscreen.platform.base.obico;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import fabscreen.platform.base.service.IAppService;
import fabscreen.platform.base.service.IMachine;
import fabscreen.platform.base.service.machine.MachineInfo;
import fabscreen.platform.base.service.machine.MachineStatus;
import fabscreen.platform.base.service.machine.Vector;
import fabscreen.platform.base.service.machine.controller.ErrorController;
import fabscreen.platform.base.service.machine.controller.FDMController;
import fabscreen.platform.base.service.machine.controller.MachineController;
import fabscreen.platform.base.service.machine.controller.NewPrintController;
import fabscreen.platform.base.service.machine.entity.Module;
import fabscreen.platform.base.service.machine.entity.module.HeatedBed;
import fabscreen.platform.base.service.machine.entity.parts.Extruder;
import fabscreen.platform.base.service.machine.entity.toolhead.FdmToolhead;
import fabscreen.platform.base.service.machine.structure.ResponseStructure;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.disposables.SerialDisposable;

/**
 * Serialized adapter from validated Obico passthrough requests to FabScreen's native controllers.
 *
 * <p>This class deliberately does not parse JSON and does not know about a WebSocket. The caller
 * must pass an {@link ObicoPassthruRequest} produced by {@link ObicoProtocol}, then translate the
 * completion back into an Obico acknowledgement. Machine state and authorization are checked at
 * acceptance and once more immediately before a controller call.</p>
 *
 * <p>{@link #executeOnMainThread(ObicoPassthruRequest, Completion)} must be invoked on Android's
 * main thread. The executor serializes operations because several Artisan controller APIs expose
 * one shared firmware-result subject and cannot safely overlap.</p>
 */
public final class ObicoNativeCommandExecutor {
    private static final long TELEMETRY_MAX_AGE_MILLIS = 30_000L;
    private static final long REQUEST_TIMEOUT_SECONDS = 20L;
    private static final long MOTION_TIMEOUT_SECONDS = 120L;
    private static final long HOME_TIMEOUT_SECONDS = 180L;
    private static final long EXTRUSION_TIMEOUT_SECONDS = 180L;
    private static final int XY_JOG_FEEDRATE_MM_PER_MINUTE = 3_000;
    private static final int Z_JOG_FEEDRATE_MM_PER_MINUTE = 600;
    private static final int SINGLE_NOZZLE_MAX_C = 275;
    private static final int DUAL_NOZZLE_MAX_C = 300;
    private static final int BED_MAX_C = 110;
    private static final float EXTRUSION_READY_TOLERANCE_C = 3.0f;

    /** Must include enabled, linked, and the user's explicit remote-control consent. */
    public interface PermissionProvider {
        boolean isRemoteControlEnabled();
    }

    /**
     * File download/preparation remains in the shared remote-print coordinator rather than this
     * native-controller adapter. Implementations must invoke exactly one callback method.
     */
    public interface FileCommandDelegate {
        void download(
                ObicoPassthruRequest.DownloadFile file,
                Completion completion);
    }

    public interface Completion {
        void onSuccess();

        void onDownloadAccepted(String targetPath);

        void onError(String error);
    }

    private static final Completion NOOP_COMPLETION = new Completion() {
        @Override
        public void onSuccess() {
        }

        @Override
        public void onDownloadAccepted(String targetPath) {
        }

        @Override
        public void onError(String error) {
        }
    };

    private final IMachine machine;
    private final IAppService appService;
    private final PermissionProvider permissionProvider;
    private final FileCommandDelegate fileCommandDelegate;
    private final AtomicBoolean pending = new AtomicBoolean();
    private final AtomicLong nextOperationId = new AtomicLong();
    private final Object operationLock = new Object();

    private long activeOperationId;
    private SerialDisposable activeDisposable;
    private Completion activeCompletion;

    public ObicoNativeCommandExecutor(
            IMachine machine,
            IAppService appService,
            PermissionProvider permissionProvider,
            FileCommandDelegate fileCommandDelegate) {
        if (machine == null || appService == null || permissionProvider == null) {
            throw new IllegalArgumentException("Obico native-command dependencies are required");
        }
        this.machine = machine;
        this.appService = appService;
        this.permissionProvider = permissionProvider;
        this.fileCommandDelegate = fileCommandDelegate;
    }

    /**
     * Starts one operation, returning {@code true} only when it was accepted into the serialized
     * execution slot. Rejections are also reported through {@code completion}.
     */
    public boolean executeOnMainThread(
            ObicoPassthruRequest request,
            Completion completion) {
        Completion callback = completion == null ? NOOP_COMPLETION : completion;
        if (request == null) {
            callback.onError("Missing Obico operation");
            return false;
        }

        ObicoNativeCommandPolicy.Decision firstDecision = decision(request, pending.get());
        if (!firstDecision.isAccepted()) {
            callback.onError(firstDecision.getReason());
            return false;
        }
        if (!pending.compareAndSet(false, true)) {
            callback.onError("Another Obico operation is in progress");
            return false;
        }

        long operationId = nextOperationId.incrementAndGet();
        SerialDisposable serialDisposable = new SerialDisposable();
        synchronized (operationLock) {
            activeOperationId = operationId;
            activeDisposable = serialDisposable;
            activeCompletion = callback;
        }

        ObicoNativeCommandPolicy.Decision liveDecision = decision(request, false);
        if (!liveDecision.isAccepted()) {
            finishError(operationId, liveDecision.getReason());
            return false;
        }

        if (request.getType() == ObicoPassthruRequest.Type.DOWNLOAD_FILE) {
            executeFileDownload(operationId, request);
            return true;
        }

        final Observable<Boolean> operation;
        try {
            operation = createNativeOperation(request);
        } catch (CommandFailure failure) {
            finishError(operationId, failure.getSafeMessage());
            return false;
        } catch (RuntimeException failure) {
            finishError(operationId, "Printer controller operation could not be prepared");
            return false;
        }

        long timeoutSeconds = timeoutSeconds(request.getType());
        Disposable subscription = operation
                .switchIfEmpty(Observable.error(
                        new CommandFailure("Printer returned no operation result")))
                .timeout(timeoutSeconds, TimeUnit.SECONDS)
                .take(1)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        ignored -> finishSuccess(operationId),
                        error -> finishError(operationId, safeFailureMessage(error)));
        serialDisposable.set(subscription);
        return true;
    }

    /** Cancels local waiting/subscriptions. It does not attempt an emergency stop. */
    public void cancelPending(String reason) {
        long operationId;
        synchronized (operationLock) {
            operationId = activeOperationId;
        }
        if (operationId != 0L) {
            finishError(operationId, boundedReason(reason, "Obico operation was cancelled"));
        }
    }

    public boolean isPending() {
        return pending.get();
    }

    private void executeFileDownload(
            long operationId,
            ObicoPassthruRequest request) {
        ObicoPassthruRequest.DownloadFile file = request.getDownloadFile();
        if (fileCommandDelegate == null || file == null) {
            finishError(operationId, "Remote file support is unavailable");
            return;
        }
        ObicoNativeCommandPolicy.Decision liveDecision = decision(request, false);
        if (!liveDecision.isAccepted()) {
            finishError(operationId, liveDecision.getReason());
            return;
        }
        try {
            fileCommandDelegate.download(file, new Completion() {
                @Override
                public void onSuccess() {
                    finishError(operationId, "Remote file operation returned no target path");
                }

                @Override
                public void onDownloadAccepted(String targetPath) {
                    if (!isSafeGcodeLeafName(targetPath)) {
                        finishError(operationId, "Remote file operation returned an invalid path");
                        return;
                    }
                    finishDownloadAccepted(operationId, targetPath);
                }

                @Override
                public void onError(String error) {
                    finishError(
                            operationId,
                            boundedReason(error, "Remote file operation failed"));
                }
            });
        } catch (RuntimeException failure) {
            finishError(operationId, "Remote file operation could not be started");
        }
    }

    private Observable<Boolean> createNativeOperation(ObicoPassthruRequest request) {
        return Observable.defer(() -> {
            ObicoNativeCommandPolicy.Decision liveDecision = decision(request, false);
            if (!liveDecision.isAccepted()) return failure(liveDecision.getReason());
            switch (request.getType()) {
                case JOG:
                    return jog(request);
                case HOME:
                    return home(request);
                case SET_TEMPERATURE:
                    return setTemperature(request);
                case EXTRUDE:
                    return extrude(request);
                case SET_PRINT_SPEED:
                    return setPrintSpeed(request);
                case SET_FLOW_RATE:
                    return setFlowRate(request);
                case SET_FAN_SPEED:
                    return setFanSpeed(request);
                case DOWNLOAD_FILE:
                default:
                    return failure("Unsupported Obico operation");
            }
        });
    }

    private Observable<Boolean> jog(ObicoPassthruRequest request) {
        MachineController controller = machine.getMachineController();
        if (controller == null) return failure("Motion controller is unavailable");
        return controller.getCurrentCoordinateObservable()
                .take(1)
                .flatMap(current -> {
                    ObicoNativeCommandPolicy.Decision decision = decision(request, false);
                    if (!decision.isAccepted()) return failure(decision.getReason());
                    Vector target = buildJogTarget(
                            current,
                            request.getJogAxis(),
                            request.getJogDistanceMm());
                    int feedrate = "z".equals(request.getJogAxis())
                            ? Z_JOG_FEEDRATE_MM_PER_MINUTE
                            : XY_JOG_FEEDRATE_MM_PER_MINUTE;
                    return requireSuccess(
                            controller.gotoAbsolutePosition(target, feedrate),
                            "Printer rejected the jog request");
                });
    }

    private Observable<Boolean> home(ObicoPassthruRequest request) {
        if (!isWholeMachineHome(request.getHomeAxes())) {
            return failure("FabScreen only supports an all-axis remote home");
        }
        MachineController controller = machine.getMachineController();
        if (controller == null) return failure("Motion controller is unavailable");
        return controller.updateCoordinateSystemIfNot(0)
                .take(1)
                .flatMap(ignored -> {
                    ObicoNativeCommandPolicy.Decision decision = decision(request, false);
                    return decision.isAccepted()
                            ? controller.home(0)
                            : Observable.error(new CommandFailure(decision.getReason()));
                })
                .take(1)
                .flatMap(result -> result != null && result == 0
                        ? Observable.just(true)
                        : failure("Printer did not complete homing"));
    }

    private Observable<Boolean> setTemperature(ObicoPassthruRequest request) {
        String heater = request.getHeaterName();
        int target = request.getTargetTemperatureC();
        if ("bed".equals(heater) || "bed1".equals(heater)) {
            if (target < 0 || target > BED_MAX_C) {
                return failure("Heated-bed target is outside the supported range");
            }
            HeatedBed heatedBed = findHeatedBed();
            if (heatedBed == null) return failure("Heated bed is unavailable");
            return requireSuccess(
                    heatedBed.setTargetTemperatureAndMode(target),
                    "Printer rejected the heated-bed target")
                    .flatMap(ignored -> refreshHeatedBedBestEffort(heatedBed));
        }

        int extruderIndex = nozzleIndex(heater);
        if (extruderIndex < 0) return failure("Unsupported heater");
        FdmToolhead toolhead = primaryToolhead();
        List<Extruder> extruders = extruders(toolhead);
        Extruder extruder = findExtruder(extruders, extruderIndex);
        if (extruder == null) return failure("Requested nozzle is unavailable");
        int maximum = extruders.size() > 1 ? DUAL_NOZZLE_MAX_C : SINGLE_NOZZLE_MAX_C;
        if (target < 0 || target > maximum) {
            return failure("Nozzle target is outside the supported range");
        }
        FDMController controller = machine.getFDMController();
        if (controller == null) return failure("FDM controller is unavailable");
        return requireSuccess(
                controller.setExtruderTemperature(0, extruderIndex, target),
                "Printer rejected the nozzle target")
                .flatMap(ignored -> refreshToolheadBestEffort(toolhead));
    }

    private Observable<Boolean> extrude(ObicoPassthruRequest request) {
        FDMController controller = machine.getFDMController();
        FdmToolhead toolhead = primaryToolhead();
        if (controller == null || toolhead == null) {
            return failure("FDM controller is unavailable");
        }
        double signedDistance = request.getExtrusionDistanceMm();
        int feedrate = request.getExtrusionFeedrateMmPerMinute();
        if (!Double.isFinite(signedDistance)
                || Math.abs(signedDistance) < 0.01d
                || Math.abs(signedDistance) > 50.0d
                || feedrate < 60
                || feedrate > 600) {
            return failure("Extrusion parameters are outside the supported range");
        }
        float distance = (float) Math.abs(signedDistance);
        float speed = feedrate;
        float lengthIn = signedDistance > 0.0d ? distance : 0.0f;
        float lengthOut = signedDistance < 0.0d ? distance : 0.0f;

        int requestedExtruder = request.getExtruderIndex();
        return refreshToolheadRequired(toolhead)
                .flatMap(ignored -> {
                    ExtrusionRoute route = extrusionRoute(
                            extruders(toolhead),
                            requestedExtruder);
                    if (route == ExtrusionRoute.UNAVAILABLE) {
                        return failure("Requested nozzle is unavailable");
                    }
                    if (route == ExtrusionRoute.ALREADY_ACTIVE) {
                        return Observable.just(true);
                    }
                    ObicoNativeCommandPolicy.Decision liveDecision = decision(request, false);
                    return liveDecision.isAccepted()
                            ? requireSuccess(
                                    controller.switchExtruder(0, requestedExtruder),
                                    "Printer could not activate the requested nozzle")
                            : failure(liveDecision.getReason());
                })
                // switchExtruder already refreshes its status, but request it again so the
                // temperature check below is always based on telemetry obtained after routing.
                .flatMap(ignored -> refreshToolheadRequired(toolhead))
                .flatMap(ignored -> {
                    ObicoNativeCommandPolicy.Decision liveDecision = decision(request, false);
                    if (!liveDecision.isAccepted()) return failure(liveDecision.getReason());
                    List<Extruder> freshExtruders = extruders(toolhead);
                    Extruder active = activeExtruder(freshExtruders);
                    if (active == null || active.getId() != requestedExtruder) {
                        return failure("Printer could not activate the requested nozzle");
                    }
                    if (!isExtrusionTemperatureReady(active)) {
                        return failure(
                                "The active nozzle has not reached its target temperature");
                    }
                    return requireSuccess(
                            controller.requestActivatedExtrusion(
                                    0,
                                    0,
                                    lengthIn,
                                    speed,
                                    lengthOut,
                                    speed),
                            "Printer rejected the extrusion request");
                })
                .flatMap(ignored -> refreshToolheadBestEffort(toolhead));
    }

    private Observable<Boolean> setPrintSpeed(ObicoPassthruRequest request) {
        int percentage = request.getPercentage();
        if (percentage < 10 || percentage > 500) {
            return failure("Print speed must be between 10 and 500 percent");
        }
        NewPrintController controller = machine.getNewPrintController();
        if (controller == null) return failure("Print controller is unavailable");
        List<Integer> ids = extruderIds(extruders(primaryToolhead()));
        if (ids.isEmpty()) return failure("No nozzle is available");
        Observable<Boolean> chain = Observable.just(true);
        for (Integer id : ids) {
            final int extruderId = id;
            chain = chain.flatMap(ignored -> {
                ObicoNativeCommandPolicy.Decision liveDecision = decision(request, false);
                return liveDecision.isAccepted()
                        ? requireSuccess(
                                controller.setPrintWorkSpeed(
                                        IMachine.WorkType.FDM,
                                        0,
                                        extruderId,
                                        percentage),
                                "Printer rejected the print-speed adjustment")
                        : failure(liveDecision.getReason());
            });
        }
        return chain;
    }

    private Observable<Boolean> setFlowRate(ObicoPassthruRequest request) {
        int percentage = request.getPercentage();
        if (percentage < 10 || percentage > 200) {
            return failure("Flow rate must be between 10 and 200 percent");
        }
        NewPrintController controller = machine.getNewPrintController();
        if (controller == null) return failure("Print controller is unavailable");
        Extruder active = activeExtruder(extruders(primaryToolhead()));
        if (active == null) return failure("No active nozzle is available");
        return requireSuccess(
                controller.setFDMFlowRate(0, active.getId(), percentage),
                "Printer rejected the flow-rate adjustment");
    }

    private Observable<Boolean> setFanSpeed(ObicoPassthruRequest request) {
        int rawSpeed = request.getFanSpeed();
        if (rawSpeed < 0 || rawSpeed > 255) {
            return failure("Fan speed is outside the supported range");
        }
        FDMController controller = machine.getFDMController();
        FdmToolhead toolhead = primaryToolhead();
        if (controller == null || toolhead == null) {
            return failure("FDM controller is unavailable");
        }
        List<Integer> ids = extruderIds(extruders(toolhead));
        if (ids.isEmpty()) return failure("No nozzle is available");
        Observable<Boolean> chain = Observable.just(true);
        for (Integer id : ids) {
            final int extruderId = id;
            chain = chain.flatMap(ignored -> {
                ObicoNativeCommandPolicy.Decision liveDecision = decision(request, false);
                return liveDecision.isAccepted()
                        ? requireSuccess(
                                controller.setFanSpeed(0, extruderId, rawSpeed),
                                "Printer rejected the fan-speed adjustment")
                        : failure(liveDecision.getReason());
            });
        }
        return chain.flatMap(ignored -> refreshToolheadBestEffort(toolhead));
    }

    private ObicoNativeCommandPolicy.Decision decision(
            ObicoPassthruRequest request,
            boolean commandPending) {
        boolean remoteControlEnabled;
        try {
            remoteControlEnabled = permissionProvider.isRemoteControlEnabled();
        } catch (RuntimeException failure) {
            remoteControlEnabled = false;
        }
        MachineStatus status = machine.getMachineStatusSubjectHolder().getValue();
        MachineInfo info = machine.getMachineInfoSubjectHolder().getValue();
        NewPrintController printController = machine.getNewPrintController();
        boolean connected = status != null && status.connected;
        boolean fdmMode = info != null
                && info.moduleList != null
                && info.workType == IMachine.WorkType.FDM;
        boolean emergencyStop = appService.getEmergencyStopState()
                != ErrorController.EmergencyStopState.EMERGENCY_STOP_STATE_NORMAL;
        boolean homed = status != null && status.isHomed;
        boolean homing = status != null && status.isHoming;
        int machineStatus = printController == null
                ? (status == null ? -1 : status.status)
                : printController.getPrintState();
        return ObicoNativeCommandPolicy.evaluate(
                request.getType(),
                remoteControlEnabled,
                remoteControlEnabled,
                connected,
                fdmMode,
                emergencyStop,
                commandPending,
                homed,
                homing,
                machineStatus);
    }

    private FdmToolhead primaryToolhead() {
        FDMController controller = machine.getFDMController();
        return controller == null ? null : controller.getFdmToolhead(0);
    }

    private HeatedBed findHeatedBed() {
        MachineInfo info = machine.getMachineInfoSubjectHolder().getValue();
        if (info == null || info.moduleList == null) return null;
        for (Module module : info.moduleList) {
            if (module instanceof HeatedBed) return (HeatedBed) module;
        }
        return null;
    }

    private static List<Extruder> extruders(FdmToolhead toolhead) {
        if (toolhead == null || toolhead.getToolheadStatusSubjectHolder().getValue() == null) {
            return new ArrayList<>();
        }
        List<Extruder> values = toolhead.getToolheadStatusSubjectHolder()
                .getValue()
                .getExtruderList();
        return values == null ? new ArrayList<>() : values;
    }

    private static Extruder findExtruder(List<Extruder> extruders, int id) {
        if (extruders == null) return null;
        for (Extruder extruder : extruders) {
            if (extruder != null && extruder.getId() == id) return extruder;
        }
        return null;
    }

    private static Extruder activeExtruder(List<Extruder> extruders) {
        if (extruders == null) return null;
        for (Extruder extruder : extruders) {
            if (extruder != null && extruder.getState() == 1) return extruder;
        }
        return null;
    }

    enum ExtrusionRoute {
        UNAVAILABLE,
        ALREADY_ACTIVE,
        SWITCH_REQUIRED
    }

    static ExtrusionRoute extrusionRoute(List<Extruder> extruders, int requestedExtruder) {
        Extruder requested = findExtruder(extruders, requestedExtruder);
        if (requested == null) return ExtrusionRoute.UNAVAILABLE;
        Extruder active = activeExtruder(extruders);
        return active != null && active.getId() == requestedExtruder
                ? ExtrusionRoute.ALREADY_ACTIVE
                : ExtrusionRoute.SWITCH_REQUIRED;
    }

    static boolean isExtrusionTemperatureReady(Extruder extruder) {
        if (extruder == null) return false;
        float current = extruder.getTemperature();
        float target = extruder.getTargetTemperature();
        return isFinite(current)
                && isFinite(target)
                && target > 0.0f
                && Math.abs(current - target) <= EXTRUSION_READY_TOLERANCE_C;
    }

    private static List<Integer> extruderIds(List<Extruder> extruders) {
        List<Integer> result = new ArrayList<>();
        Set<Integer> unique = new HashSet<>();
        if (extruders == null) return result;
        for (Extruder extruder : extruders) {
            if (extruder == null) continue;
            int id = extruder.getId();
            if ((id == 0 || id == 1) && unique.add(id)) result.add(id);
        }
        return result;
    }

    private static Observable<Boolean> requireSuccess(
            Observable<? extends ResponseStructure> response,
            String rejectionMessage) {
        if (response == null) return failure("Printer controller is unavailable");
        return response.take(1).flatMap(value -> value != null && value.isSuccess()
                ? Observable.just(true)
                : failure(rejectionMessage));
    }

    private static Observable<Boolean> refreshToolheadBestEffort(FdmToolhead toolhead) {
        if (toolhead == null) return Observable.just(true);
        try {
            return toolhead.requestInfo()
                    .timeout(6L, TimeUnit.SECONDS)
                    .take(1)
                    .map(ignored -> true)
                    .onErrorReturnItem(true)
                    .defaultIfEmpty(true);
        } catch (RuntimeException ignored) {
            return Observable.just(true);
        }
    }

    private static Observable<Boolean> refreshToolheadRequired(FdmToolhead toolhead) {
        if (toolhead == null) return failure("FDM toolhead is unavailable");
        try {
            return requireSuccess(
                    toolhead.requestInfo().timeout(
                            REQUEST_TIMEOUT_SECONDS,
                            TimeUnit.SECONDS),
                    "Printer could not refresh nozzle telemetry")
                    .flatMap(ignored -> toolhead.isExtruderStatusFresh(
                            TELEMETRY_MAX_AGE_MILLIS)
                            ? Observable.just(true)
                            : failure("Nozzle telemetry is stale"));
        } catch (RuntimeException failure) {
            return failure("Printer could not refresh nozzle telemetry");
        }
    }

    private static Observable<Boolean> refreshHeatedBedBestEffort(HeatedBed heatedBed) {
        if (heatedBed == null) return Observable.just(true);
        try {
            return heatedBed.requestInfo()
                    .timeout(6L, TimeUnit.SECONDS)
                    .take(1)
                    .map(ignored -> true)
                    .onErrorReturnItem(true)
                    .defaultIfEmpty(true);
        } catch (RuntimeException ignored) {
            return Observable.just(true);
        }
    }

    private void finishSuccess(long operationId) {
        Completion callback = clearActive(operationId);
        if (callback != null) callback.onSuccess();
    }

    private void finishDownloadAccepted(long operationId, String targetPath) {
        Completion callback = clearActive(operationId);
        if (callback != null) callback.onDownloadAccepted(targetPath);
    }

    private void finishError(long operationId, String error) {
        Completion callback = clearActive(operationId);
        if (callback != null) callback.onError(boundedReason(error, "Obico operation failed"));
    }

    private Completion clearActive(long operationId) {
        SerialDisposable disposable;
        Completion callback;
        synchronized (operationLock) {
            if (!pending.get()
                    || activeOperationId == 0L
                    || activeOperationId != operationId) {
                return null;
            }
            activeOperationId = 0L;
            disposable = activeDisposable;
            activeDisposable = null;
            callback = activeCompletion;
            activeCompletion = null;
            pending.set(false);
        }
        if (disposable != null && !disposable.isDisposed()) disposable.dispose();
        return callback;
    }

    static Vector buildJogTarget(Vector current, String axis, double distanceMm) {
        if (current == null
                || axis == null
                || !Double.isFinite(distanceMm)
                || Math.abs(distanceMm) < 0.01d
                || Math.abs(distanceMm) > 100.0d) {
            throw new CommandFailure("Invalid jog target");
        }
        Vector target = new Vector();
        target.setX(current.getX());
        target.setY(current.getY());
        target.setZ(current.getZ());
        switch (axis) {
            case "x":
                target.setX(checkedCoordinate(current.getX(), distanceMm));
                break;
            case "y":
                target.setY(checkedCoordinate(current.getY(), distanceMm));
                break;
            case "z":
                target.setZ(checkedCoordinate(current.getZ(), distanceMm));
                break;
            default:
                throw new CommandFailure("Invalid jog axis");
        }
        return target;
    }

    private static float checkedCoordinate(float current, double delta) {
        double target = current + delta;
        if (!isFinite(current)
                || !Double.isFinite(target)
                || target > Float.MAX_VALUE
                || target < -Float.MAX_VALUE) {
            throw new CommandFailure("Invalid jog coordinate");
        }
        return (float) target;
    }

    static int nozzleIndex(String heater) {
        if ("tool0".equals(heater) || "extruder0".equals(heater)) return 0;
        if ("tool1".equals(heater) || "extruder1".equals(heater)) return 1;
        return -1;
    }

    private static boolean isFinite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    static boolean isWholeMachineHome(List<String> axes) {
        return axes != null
                && axes.size() == 3
                && axes.contains("x")
                && axes.contains("y")
                && axes.contains("z");
    }

    private static boolean isSafeGcodeLeafName(String value) {
        if (value == null || value.isEmpty() || value.length() > 255) return false;
        if (!value.toLowerCase(java.util.Locale.US).endsWith(".gcode")) return false;
        if (".".equals(value) || "..".equals(value)) return false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '/'
                    || character == '\\'
                    || character == 0
                    || Character.isISOControl(character)) {
                return false;
            }
        }
        return true;
    }

    private static long timeoutSeconds(ObicoPassthruRequest.Type type) {
        switch (type) {
            case JOG:
                return MOTION_TIMEOUT_SECONDS;
            case HOME:
                return HOME_TIMEOUT_SECONDS;
            case EXTRUDE:
                return EXTRUSION_TIMEOUT_SECONDS;
            default:
                return REQUEST_TIMEOUT_SECONDS;
        }
    }

    private static String safeFailureMessage(Throwable failure) {
        if (failure instanceof CommandFailure) {
            return ((CommandFailure) failure).getSafeMessage();
        }
        if (failure instanceof TimeoutException) {
            return "Printer did not confirm the operation in time";
        }
        return "Printer controller operation failed";
    }

    private static String boundedReason(String value, String fallback) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty()) clean = fallback;
        return clean.length() <= 160 ? clean : clean.substring(0, 160);
    }

    private static <T> Observable<T> failure(String message) {
        return Observable.error(new CommandFailure(message));
    }

    private static final class CommandFailure extends RuntimeException {
        private final String safeMessage;

        CommandFailure(String safeMessage) {
            super(safeMessage);
            this.safeMessage = boundedReason(safeMessage, "Obico operation failed");
        }

        String getSafeMessage() {
            return safeMessage;
        }
    }
}
