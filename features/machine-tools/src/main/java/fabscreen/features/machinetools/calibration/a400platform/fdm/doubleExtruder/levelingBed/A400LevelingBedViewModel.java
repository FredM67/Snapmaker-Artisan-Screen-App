package fabscreen.features.machinetools.calibration.a400platform.fdm.doubleExtruder.levelingBed;

import android.annotation.SuppressLint;

import com.orhanobut.logger.Logger;

import java.util.ArrayList;

import fabscreen.platform.base.instantiation.ServiceContainer;
import fabscreen.platform.base.service.IMachine;
import fabscreen.platform.base.service.IPreferences;
import fabscreen.platform.base.service.machine.controller.FDMController;
import fabscreen.platform.base.service.machine.controller.MachineController;
import fabscreen.platform.base.service.machine.entity.module.HeatedBed;
import fabscreen.platform.base.service.machine.entity.toolhead.FdmToolhead;
import fabscreen.platform.base.service.machine.structure.BaseStructure;
import fabscreen.platform.base.service.machine.structure.ResponseStructure;
import fabscreen.platform.base.view.BaseViewModel;
import fabscreen.platform.core.ui.data.MoveController;
import fabscreen.platform.core.ui.data.calibration.CalibrationPoint;
import fabscreen.platform.lib.LogHelper;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.subjects.BehaviorSubject;
import io.reactivex.subjects.PublishSubject;

/**
 * We manage the lifecycle of this ViewModel by ourself, instead of AutoDispose.
 */
@SuppressLint("AutoDispose")
public class A400LevelingBedViewModel extends BaseViewModel {

    /** Highest bed temperature the calibration may set, same range as the selection screen input. */
    public final static int MAX_BED_CALIBRATION_TEMPERATURE = 80;

    /** Degrees within which the bed is considered to have reached its calibration target, heating up or cooling down. */
    private final static float BED_TEMPERATURE_READY_TOLERANCE = 1f;

    private final static int[][] mDirection = {{0, 1}, {-1, 0}, {0, -1}, {1, 0}}; // right, up, left, down, rotate with counterclockwise
    private int mGrid = 0;
    private boolean mIsAutoMode = false;
    private ArrayList<CalibrationPoint> mPoints;
    private BehaviorSubject<Boolean> mIsMovingSubject = BehaviorSubject.createDefault(false);
    private BehaviorSubject<Boolean> mIsMovePopUpSubject = BehaviorSubject.createDefault(false);
    public int HEATING_SPEED = 5;
    public int LEVELING_POINT_TIME = 5;
    private int mBedCalibrationBedTemperature;
    private int calibrationMode;
    private FDMController fdmController;
    private MachineController machineController;
    private BehaviorSubject<Integer> mHeatTimeSubject = BehaviorSubject.createDefault(0);
    private PublishSubject<Integer> mCalibrationPointSubject = PublishSubject.create();
    private int wholeCalculateTime = 0;
    private BehaviorSubject<Boolean> mTodoNext = BehaviorSubject.createDefault(false);
    private BehaviorSubject<Boolean> mToolheadStatusState = BehaviorSubject.createDefault(false);
    private BehaviorSubject<Boolean> mBedStatusState = BehaviorSubject.createDefault(false);
    /** Bed targets ({zoneIndex, targetTemperature}) as they were before the calibration heated the bed. */
    private final ArrayList<int[]> mBedSnapshotZoneTargets = new ArrayList<>();
    private int mBedSnapshotWorkMode = HeatedBed.HeatedBedStatus.HEATED_BED_STATUS_WORK_MODE_WHOLE;
    private boolean mHasBedSnapshot = false;
    /** The pre-heat question is asked once per run; a recreated view must not ask it again. */
    private boolean mPreheatChoiceMade = false;

    public A400LevelingBedViewModel() {
        super();
        IPreferences.Helper helper = ServiceContainer.getInstance().getService(IPreferences.class).getHelper();
        fdmController = ServiceContainer.getInstance().getService(IMachine.class).getFDMController();
        machineController = ServiceContainer.getInstance().getService(IMachine.class).getMachineController();

        calibrationMode = helper.getA400LevelingBedCalibrationMode();
        mBedCalibrationBedTemperature = helper.getA400LevelingBedCalibrationBedTemperature();

        mIsAutoMode = (calibrationMode == 0);

        mGrid = helper.getA400LevelingBedCalibrationGrid();

        fdmController.unWatchGridCalibrationStatus();

        fdmController.watchGridCalibrationStatus()
                .observeOn(AndroidSchedulers.mainThread())
                .as(bindToLifecycle())
                .subscribe(heatedBedStatus -> {
                    if (!heatedBedStatus.isSuccess()) {
                        return;
                    }
                    BaseStructure returnStruct = (BaseStructure) heatedBedStatus.dataProp;
                    int index = (int) returnStruct.getProp("index").getValue();
                    int status = (int) returnStruct.getProp("status").getValue();
                    if (status != 0) {
                        // Error
                        Logger.e("Grid Calibration return error on %d, status %d", index, status);
                    } else {
                        if (mPoints != null) {
                            mCalibrationPointSubject.onNext(index);
                        }
                    }
                }, LogHelper::log);
        Observable.zip(mToolheadStatusState, mBedStatusState, (toolState, bedState) -> toolState && bedState)
                .as(bindToLifecycle())
                .subscribe(aBoolean -> mTodoNext.onNext(aBoolean), LogHelper::log);
        float currentTemperature = machineController.getHeatedBed().getHeatedBedStatusSubjectHolder().getValue().getZoneList().get(0).getCurrentTemperature();
        wholeCalculateTime = (int) Math.abs(currentTemperature - mBedCalibrationBedTemperature) * HEATING_SPEED + mGrid * mGrid * LEVELING_POINT_TIME;
    }

    public int getWholeCalculateTime() {
        return wholeCalculateTime;
    }

    public int CalculateHeatingTime() {
        // FIXME:
        float currentTemperature = machineController.getHeatedBed().getHeatedBedStatusSubjectHolder().getValue().getZoneList().get(0).getCurrentTemperature();
        return (int) Math.abs(currentTemperature - mBedCalibrationBedTemperature) * HEATING_SPEED;
    }

    public Observable<ResponseStructure> startCalibration() {
        initPoints();
        return fdmController.startGridCalibration(mGrid);
    }

    public Observable<Boolean> getIsMovingObservable() {
        return mIsMovingSubject.hide();
    }

    public Observable<Boolean> getIsMovePopUpSubject() {
        return mIsMovePopUpSubject.hide();
    }

    public int getGridCount() {
        return mGrid;
    }

    public boolean isAutoMode() {
        return mIsAutoMode;
    }

    public ArrayList<CalibrationPoint> initPoints() {
        final int[][] orders = generateViewOrders();
        mPoints = new ArrayList<>();
        for (int i = 1; i <= mGrid; i++) {
            for (int j = 1; j <= mGrid; j++) {
                int coordinateOrder = j + (mGrid * (mGrid - i));
                mPoints.add(new CalibrationPoint(i, j, orders[i - 1][j - 1], coordinateOrder));
            }
        }

        return mPoints;
    }

    private int[][] generateViewOrders() {
        int[][] orders = new int[mGrid][mGrid];
        int order = 1;
        int direction = 0;

        // init first element
        int currentRow = mGrid - 1;
        int currentColumn = 0;
        orders[currentRow][currentColumn] = order;
        order++;

        while (order <= mGrid * mGrid) {
            int nextRow = currentRow + mDirection[direction][0];
            int nextColumn = currentColumn + mDirection[direction][1];

            if (nextRow > mGrid - 1 || nextRow < 0 || nextColumn > mGrid - 1 || nextColumn < 0 || orders[nextRow][nextColumn] != 0) {
                // change direction
                direction = (direction + 1) % 4;
            } else {
                orders[nextRow][nextColumn] = order;
                currentRow = nextRow;
                currentColumn = nextColumn;
                order++;
            }
        }
        return orders;
    }

    private CalibrationPoint getPoint(int coordinateOrder) {
        if (coordinateOrder > mPoints.size() || coordinateOrder < 1) {
            return null;
        }

        for (CalibrationPoint point : mPoints) {
            if (point.getCoordinateOrder() == coordinateOrder) {
                return point;
            }
        }

        return null;
    }

    private CalibrationPoint getPointByViewOrder(int viewOrder) {
        for (int i = 0; i < mPoints.size(); i++) {
            if (mPoints.get(i).getViewOrder() == viewOrder) {
                return mPoints.get(i);
            }
        }
        return null;
    }

    private CalibrationPoint getPoint(int row, int column) {
        if (row > mGrid || row < 1 || column > mGrid || column < 1) {
            return null;
        }

        int position = column + mGrid * (row - 1);
        if (position > mPoints.size() || position < 1) {
            return null;
        }

        return mPoints.get(position - 1);
    }

    public void move(MoveController.Direction direction, float stepWidth) {
        mIsMovingSubject.onNext(true);
        MoveController.getInstance()
                .stepToPosition(direction, stepWidth)
                .as(bindToLifecycle())
                .subscribe(responseStructure -> {
                    if (responseStructure.isSuccess()) {
                        mIsMovingSubject.onNext(false);
                    } else {
                        mIsMovingSubject.onNext(false);
                    }
                }, e -> {
                    LogHelper.log(e);
                    mIsMovingSubject.onNext(false);
                });
    }

    /**
     * Remembers the bed targets and work mode the user had set, before the calibration overwrites
     * them with its own temperature. Only the first call in a calibration run is taken into
     * account, so re-entering a step cannot snapshot the calibration temperature itself.
     */
    public void snapshotBedState() {
        if (mHasBedSnapshot) return;
        HeatedBed heatedBed = machineController.getHeatedBed();
        if (heatedBed == null) return;
        HeatedBed.HeatedBedStatus status = heatedBed.getHeatedBedStatusSubjectHolder().getValue();
        if (status == null || status.getZoneList() == null || status.getZoneList().isEmpty()) return;

        mBedSnapshotZoneTargets.clear();
        for (HeatedBed.ZoneInfo zone : status.getZoneList()) {
            mBedSnapshotZoneTargets.add(new int[]{zone.getZoneIndex(), zone.getTargetTemperature()});
        }
        mBedSnapshotWorkMode = status.getWorkMode();
        mHasBedSnapshot = true;
    }

    /**
     * The temperature auto bed leveling would use if the pre-heat already running were adopted: the
     * configured one, raised to that pre-heat. Without adopting it, a bed pre-heated to 80 °C gets
     * its target pulled back down to the configured value and slowly drifts towards it for the
     * whole run, so the points are not all probed at the same temperature.
     * <p>
     * A pre-heat lower than the configured temperature changes nothing, and an explicit
     * "no heating" (0 °C) configuration is left alone.
     */
    private static int effectiveBedCalibrationTemperature(int configuredTemperature, int preheatTemperature) {
        if (configuredTemperature <= 0) return configuredTemperature;
        return Math.max(configuredTemperature, Math.min(preheatTemperature, MAX_BED_CALIBRATION_TEMPERATURE));
    }

    /**
     * Raises this run's temperature to the pre-heat captured by {@link #snapshotBedState()}, after
     * the operator has chosen to keep that pre-heat rather than wait for the bed to cool down. The
     * pre-heat comes from the snapshot rather than a second read, so it cannot pick up a target
     * this calibration set itself. Call it before anything heats or shows an estimated duration.
     */
    public void adoptPreheatTemperature() {
        if (!mHasBedSnapshot) return;
        mPreheatChoiceMade = true;

        int adopted = effectiveBedCalibrationTemperature(mBedCalibrationBedTemperature, snapshotPreheatTemperature());
        if (adopted == mBedCalibrationBedTemperature) return;

        mBedCalibrationBedTemperature = adopted;
        // The estimate was computed in the constructor, against the configured temperature.
        wholeCalculateTime = CalculateHeatingTime() + mGrid * mGrid * LEVELING_POINT_TIME;
    }

    /**
     * Records that the operator chose to calibrate at the configured temperature and wait for the
     * bed to cool down to it, so the choice is not asked again for this run.
     */
    public void declinePreheatTemperature() {
        mPreheatChoiceMade = true;
    }

    /**
     * Whether the operator still has to be asked about a running pre-heat, because adopting it
     * would raise the configured temperature. Probing above the configured temperature is their
     * call rather than a silent override, and it is only asked once per calibration run.
     */
    public boolean wouldAdoptPreheatTemperature() {
        if (!mHasBedSnapshot || mPreheatChoiceMade) return false;
        return effectiveBedCalibrationTemperature(mBedCalibrationBedTemperature, snapshotPreheatTemperature()) != mBedCalibrationBedTemperature;
    }

    private int snapshotPreheatTemperature() {
        int preheat = 0;
        for (int[] zone : mBedSnapshotZoneTargets) {
            preheat = Math.max(preheat, zone[1]);
        }
        return preheat;
    }

    /**
     * Puts the bed back into the state captured by {@link #snapshotBedState()}: the user's own
     * pre-heat is kept, and a bed that was off before the calibration is switched off again.
     * Does nothing when no snapshot was taken. The snapshot is kept until the restore actually
     * succeeds, so a failed attempt can simply be retried by calling this again.
     * <p>
     * Only the bed/center target and the work mode are independently settable on the firmware;
     * the chamber/outer target is derived from them (mirrored in WHOLE mode, forced towards 0 in
     * INNER mode) and the firmware's per-zone command ({@code 0x14/0x02}) actually applies to
     * every zone regardless of the index it is sent with. So the snapshot is restored as a single
     * target-and-mode command rather than as independent per-zone targets.
     */
    public Observable<ResponseStructure> restoreBedState() {
        if (!mHasBedSnapshot) return Observable.just(new ResponseStructure());

        HeatedBed heatedBed = machineController.getHeatedBed();
        if (heatedBed == null) return Observable.just(new ResponseStructure());

        int highestTarget = 0;
        for (int[] zone : mBedSnapshotZoneTargets) {
            highestTarget = Math.max(highestTarget, zone[1]);
        }

        // The mode is restored from what was captured at snapshot time rather than re-derived
        // from the targets: INNER mode can still report a positive chamber/outer target above
        // DOUBLE_ZONE_BED_TOGETHER_ALLOW_MAX_TEMP (see Temperature::setTargetBed in the
        // firmware), so "more than one zone has a positive target" does not reliably mean WHOLE.
        // This also restores the right mode when the bed was simply off (both targets 0).
        Observable<ResponseStructure> restore = heatedBed.setTargetTemperatureAndMode(highestTarget, mBedSnapshotWorkMode);
        return restore.doOnNext(responseStructure -> {
            if (responseStructure.isSuccess()) mHasBedSnapshot = false;
        });
    }

    public Observable<ResponseStructure> startHeating() {
        return machineController.getHeatedBed().setTargetTemperatureAndMode(mBedCalibrationBedTemperature, HeatedBed.HeatedBedStatus.HEATED_BED_STATUS_WORK_MODE_WHOLE)
                .flatMap(responseStructure -> responseStructure.isSuccess() ? fdmController.setExtruderTemperature(0, 0, 150) : Observable.just(responseStructure));
    }

    public int getViewOrder(int index) {
        return getPoint(index).getViewOrder();
    }

    public int getCoordinateOrder(int index) {
        return getPoint(index).getCoordinateOrder();
    }

    public int getCoordinateOrderByViewOrder(int index) {
        return getPointByViewOrder(index).getCoordinateOrder();
    }

    public int getBedCalibrationBedTemperature() {
        return mBedCalibrationBedTemperature;
    }

    public Observable<Integer> updateHeatedTime() {
        return mHeatTimeSubject.hide();
    }

    public Observable<Boolean> todoNext() {
        return mTodoNext.hide();
    }

    public Observable<Integer> getCalibrationObservable() {
        return mCalibrationPointSubject.hide();
    }

    public void subscribeTemperatureChange() {
        machineController.getHeatedBed().subscribeTemperatureChange();
        fdmController.subscribeExtruderChange();
    }

    public void unsubscribeTemperatureChange() {
        machineController.getHeatedBed().unsubscribeTemperatureChange();
        fdmController.unSubscribeExtruderChange();
    }

    public void subscribeGridCalibrationStatus() {
        fdmController.subscribeGridCalibrationStatus();
    }

    public void unsubscribeGridCalibrationStatus() {
        fdmController.unSubscribeGridCalibrationStatus();
    }

    public Observable<Boolean> checkHome() {
        IMachine service = ServiceContainer.getInstance().getService(IMachine.class);
        if (!service.getMachineStatusSubjectHolder().getValue().isHomed) {
            mIsMovePopUpSubject.onNext(true);
            return service.getMachineController().updateCoordinateSystem(0)
                    .flatMap(machineStatus -> service.getMachineController().home(0))
                    .doOnNext(machineStatus -> {
                        mIsMovePopUpSubject.onNext(false);
                    })
                    .flatMap(integer -> Observable.just(integer == 0));
        } else {
            return Observable.just(true);
        }
    }

    public void setCalibrationPoint(int i) {
        mIsMovePopUpSubject.onNext(true);
        fdmController.gridCalibration(getPointByViewOrder(i).getCoordinateOrder())
                .observeOn(AndroidSchedulers.mainThread())
                .as(bindToLifecycle())
                .subscribe(responseStructure -> {
                    if (responseStructure.isSuccess()) {
                        mIsMovePopUpSubject.onNext(false);
                        //因为外面是从1开始算，自定义的棋盘是index是从0开始算的，所以这边减1
                        mCalibrationPointSubject.onNext(getPointByViewOrder(i - 1).getCoordinateOrder());
                    }
                }, LogHelper::log);
    }

    public Observable<FdmToolhead.FdmToolheadStatus> getExtruderChangeObservable() {
        return fdmController.getToolheadStatusSubjectHolder(0).getObservable()
                .doOnNext(toolheadStatus -> {
                    float currentTemperature = toolheadStatus.getExtruderList().get(0).getTemperature();
                    if (currentTemperature >= 150) {
                        mToolheadStatusState.onNext(true);
                    }
                });
    }

    public Observable<HeatedBed.HeatedBedStatus> getBedChangeObservable() {
        return machineController.getHeatedBed().getHeatedBedStatusSubjectHolder().getObservable()
                .doOnNext(heatedBedStatus -> {
                    int targetTemperature = heatedBedStatus.getZoneList().get(0).getTargetTemperature();
                    float currentTemperature = heatedBedStatus.getZoneList().get(0).getCurrentTemperature();
                    int time = (int) (Math.abs(targetTemperature - currentTemperature) * HEATING_SPEED) + mGrid * mGrid * LEVELING_POINT_TIME;
                    mHeatTimeSubject.onNext(time);
                    // Tolerance-based rather than "current >= target" so a bed that must cool
                    // down to a lower configured temperature (operator declined the pre-heat)
                    // is also recognized as ready, not just one that is still heating up.
                    if (mBedCalibrationBedTemperature != 0 && Math.abs(currentTemperature - mBedCalibrationBedTemperature) <= BED_TEMPERATURE_READY_TOLERANCE) {
                        mBedStatusState.onNext(true);
                    }
                });
    }

    public Observable<ResponseStructure> getInterruptAutoLevelingObservable() {
        return machineController.getInterruptAutoLevelingObservable();
    }

    public Observable<ResponseStructure> setCalibrationMode(int i) {
        return ServiceContainer.getInstance().getService(IMachine.class)
                .getFDMController()
                .setCalibrationMode(i);
    }
}
