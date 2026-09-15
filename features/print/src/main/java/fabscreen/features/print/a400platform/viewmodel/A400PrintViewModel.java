package fabscreen.features.print.a400platform.viewmodel;

import static fabscreen.platform.base.service.machine.controller.MachineOperationStatus.SYSTEM_STATUS_COMPLETED;
import static fabscreen.platform.base.service.machine.entity.Module.ModuleType.HEAD_3DP_DOUBLE_EXTRUDER;
import static fabscreen.platform.base.service.machine.entity.Module.ModuleType.HEAD_CNC_200W;

import com.orhanobut.logger.Logger;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import fabscreen.platform.base.instantiation.ServiceContainer;
import fabscreen.platform.base.lib.file.IFile;
import fabscreen.platform.base.lib.print.IPrintWorkspace;
import fabscreen.platform.base.service.IMachine;
import fabscreen.platform.base.service.machine.IStructure;
import fabscreen.platform.base.service.machine.controller.MachineOperationStatus;
import fabscreen.platform.base.service.machine.controller.NewPrintController;
import fabscreen.platform.base.service.machine.controller.PrintEvent;
import fabscreen.platform.base.service.machine.entity.module.HeatedBed;
import fabscreen.platform.base.service.machine.entity.toolhead.CNCToolhead;
import fabscreen.platform.base.service.machine.entity.toolhead.FdmToolhead;
import fabscreen.platform.base.service.machine.entity.toolhead.LaserToolhead;
import fabscreen.platform.base.service.machine.structure.ResponseStructure;
import fabscreen.platform.base.view.BaseViewModel;
import fabscreen.platform.core.ui.data.FilamentState;
import fabscreen.platform.core.ui.data.PrintProgress;
import fabscreen.platform.lib.LogHelper;
import fabscreen.platform.lib.SubjectHolder;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.subjects.BehaviorSubject;

public class A400PrintViewModel extends BaseViewModel {
    private final IMachine mA400Machine;
    private final IPrintWorkspace mWorkspace;

    private final NewPrintController mNewPrintController;

    private final IMachine.WorkType mWorkType;
    private float mEstimatedTime = 0f;

    private final CompositeDisposable mCompositeDisposable = new CompositeDisposable();

    private final BehaviorSubject<Boolean> mWaitingSubject = BehaviorSubject.createDefault(false);
    private final BehaviorSubject<PrintProgress> mPrintProgressSubject = BehaviorSubject.createDefault(new PrintProgress());
    private final BehaviorSubject<FilamentState> mFilamentStateSubject = BehaviorSubject.createDefault(new FilamentState());

    public A400PrintViewModel() {
        super();
        mA400Machine = ServiceContainer.getInstance().getService(IMachine.class);
        mWorkspace = ServiceContainer.getInstance().getService(IPrintWorkspace.class);
        mNewPrintController = mA400Machine.getNewPrintController();
        mWorkType = mA400Machine.getMachineInfoSubjectHolder().getValue().workType;
    }

    public IMachine.WorkType getWorkType() {
        return mWorkType;
    }

    public boolean isIsFdm() {
        return mWorkType == IMachine.WorkType.FDM;
    }

    public boolean is200WattCNC() {
        return mA400Machine.getCNCController().getHeadType() == HEAD_CNC_200W;
    }

    public boolean isDoubleExtruder() {
        return mA400Machine.getFDMController().getHeadType() == HEAD_3DP_DOUBLE_EXTRUDER;
    }

    public void onPause() {
        mNewPrintController.unSubscribeTookHeadSpeed();
        if (mA400Machine.getMachineInfoSubjectHolder().getValue().isEnclosureAvailable) {
            mA400Machine.getMachineController().getEnclosure().unsubscribeEnclosureInfo();
        }
        if (mA400Machine.getMachineInfoSubjectHolder().getValue().isAirPurifierAvailable) {
            mA400Machine.getMachineController().getAirPurifier().unsubscribeAirPurifierStatusChange();
        }
        switch (mWorkType) {
            case FDM:
                mA400Machine.getFDMController().unSubscribeExtruderChange();
                mA400Machine.getMachineController().getHeatedBed().unsubscribeTemperatureChange();
                break;
            case LASER:
                mA400Machine.getLaserController().unSubscribeLaserTubeStatus()
                        .as(bindToLifecycle())
                        .subscribe(responseStructure -> {
                        }, LogHelper::log);
                break;
            case CNC:
                mA400Machine.getCNCController().unSubscribeCNCInfo();
                break;
            default:
                break;
        }
    }

    public void onResume() {
        mNewPrintController.subscribeTookHeadSpeed();
        if (mA400Machine.getMachineInfoSubjectHolder().getValue().isEnclosureAvailable) {
            mA400Machine.getMachineController().getEnclosure().subscribeEnclosureInfo();
        }
        if (mA400Machine.getMachineInfoSubjectHolder().getValue().isAirPurifierAvailable) {
            mA400Machine.getMachineController().getAirPurifier().subscribeAirPurifierStatusChange();
        }
        switch (mWorkType) {
            case FDM:
                mA400Machine.getFDMController().subscribeExtruderChange();
                mA400Machine.getMachineController().getHeatedBed().subscribeTemperatureChange();
                break;
            case LASER:
                mA400Machine.getLaserController().subscribeLaserTubeStatus().as(bindToLifecycle()).subscribe(responseStructure -> {
                }, LogHelper::log);
                break;
            case CNC:
                mA400Machine.getCNCController().subscribeCNCInfo();
                break;
            default:
                break;
        }
    }

    public void initPrint() {
        mEstimatedTime = mWorkspace.getEstimatedTime();
        boolean isPrinting = MachineOperationStatus.isPrinting(mNewPrintController.getPrintState());
        boolean attachStartedRemotePrint = mNewPrintController.consumeAttachStartedRemotePrint();
        if (isPrinting || attachStartedRemotePrint) {
            // Initializing from last printing
            setTimeToUpdateProgress();
        } else {
            mNewPrintController.reset();
            IFile file1 = mWorkspace.getPrintFile();
            mNewPrintController.setFile(file1);
            mNewPrintController.setTotalLines(mWorkspace.getFileTotalLineCount());
            Logger.d("Setting up file %s, total lines %d.", file1.getName(), mWorkspace.getFileTotalLineCount());
            startPrint();
        }
        updateProgress();
    }

    public void startPrint() {
        mWaitingSubject.onNext(true);
        mCompositeDisposable.clear();
        // Power Panic
        boolean powerOutageFlag = mNewPrintController.getRecoveryFlag();
        if (powerOutageFlag) {
            Logger.d("Try Power Loss recovering..");
            mNewPrintController.recover();
        } else {
            mNewPrintController.start();
        }
        // Update
        setTimeToUpdateProgress();
    }

    public void requestMachineResume() {
        Logger.i("Requesting print resume.");
        mWaitingSubject.onNext(true);
        mNewPrintController.resume();
    }

    public void requestMachineStop() {
        Logger.i("Requesting print stop.");
        mWaitingSubject.onNext(true);
        mNewPrintController.stop();
    }

    public void requestMachinePause() {
        Logger.i("Requesting print pause.");
        mWaitingSubject.onNext(true);
        mNewPrintController.pause();
    }

    private void setTimeToUpdateProgress() {
        Disposable subscribe = Observable.interval(0, 2, TimeUnit.SECONDS)
                .takeUntil(tick -> mNewPrintController.getPrintState() == SYSTEM_STATUS_COMPLETED.value())
                .filter(tick -> MachineOperationStatus.isPrinting(mNewPrintController.getPrintState()))
                .observeOn(AndroidSchedulers.mainThread())
                .as(bindToLifecycle())
                .subscribe(tick -> {
                    updateProgress();
                }, Throwable::printStackTrace);
        mCompositeDisposable.add(subscribe);
    }

    private void updateProgress() {
        float p = mNewPrintController.getProgress();
        // formula: remaining = (1 - p) * p * elapsed / p + (1 - p) * (1 - p) * ETA
        int elapsed = mNewPrintController.getTickCounter().getCount();
        int remaining = (int) ((1 - p) * elapsed + (1 - p) * (1 - p) * mEstimatedTime);
        final int percentage = (int) (100 * p);
        mPrintProgressSubject.onNext(new PrintProgress(remaining, percentage));
    }

    public Observable<ResponseStructure<IStructure>> setLaserPower(int i, float parseInt) {
        return mA400Machine.getLaserController().setLaserPower(i, parseInt);
    }

    public Observable<LaserToolhead.LaserToolheadInfo> getLaserToolHeadInfoObservable(int i) {
        return mA400Machine.getLaserController().getLaserToolHeadInfoObservable(i);
    }

    public Observable<ResponseStructure> setPrintWorkSpeed(IMachine.WorkType workType, int headIndex, int extruderIndex, int workspeed) {
        return mNewPrintController.setPrintWorkSpeed(workType, headIndex, extruderIndex, workspeed);
    }

    public Observable<ArrayList<Integer>> getTookHeadSpeedObservable() {
        return mNewPrintController.getTookHeadSpeedObservable();
    }

    public Observable<CNCToolhead.CNCToolheadInfo> getCncToolHeadInfoObservable(int index) {
        return mA400Machine.getCNCController().getCncToolHeadInfoObservable(index);
    }

    public Observable<ResponseStructure> setCNCTarget(int index, int speed) {
        return is200WattCNC() ? mA400Machine.getCNCController().setTargetSpeed(index, speed) : mA400Machine.getCNCController().setSpindlePower(index, speed);
    }

    public Observable<ResponseStructure> setExtruderTemperature(int toolheadIndex, int extruderIndex, int temperature) {
        return mA400Machine.getFDMController().setExtruderTemperature(toolheadIndex, extruderIndex, temperature);
    }

    public SubjectHolder<FdmToolhead.FdmToolheadStatus> getToolheadStatusSubjectHolder(int toolheadIndex) {
        return mA400Machine.getFDMController().getToolheadStatusSubjectHolder(toolheadIndex);
    }

    public Observable<ResponseStructure> setAllTargetTemperature(int temperature) {
        return mA400Machine.getMachineController()
                .getHeatedBed().setAllTargetTemperature(temperature);
    }

    public Observable<ResponseStructure> setDefaultModeTargetTemperature(int temperature) {
        return mA400Machine.getMachineController().getHeatedBed().setTargetTemperatureAndMode(temperature);
    }

    public SubjectHolder<HeatedBed.HeatedBedStatus> getHeatedBedStatusSubjectHolder() {
        return mA400Machine.getMachineController()
                .getHeatedBed()
                .getHeatedBedStatusSubjectHolder();
    }

    public Observable<Integer> getPrintStateObservable() {
        Observable<Integer> printStateObservable = mNewPrintController.getPrintStateObservable();
        printStateObservable
                .as(bindToLifecycle())
                .subscribe(integer -> mWaitingSubject.onNext(MachineOperationStatus.isPrintChange(integer)), LogHelper::log);
        return printStateObservable;
    }

    public Integer getPrintStateValue() {
        mWaitingSubject.onNext(MachineOperationStatus.isPrintChange(mNewPrintController.getPrintState()));
        return mNewPrintController.getPrintState();
    }

    public Observable<Boolean> getWaitingObservable() {
        return mWaitingSubject.hide();
    }

    public boolean getWaitingValue() {
        return mWaitingSubject.getValue();
    }

    public Observable<Boolean> getFilamentSubjectObservable() {
        return mNewPrintController.getFilamentSubjectObservable();
    }

    public void setFilament(boolean state) {
        mNewPrintController.setFilament(state);
    }

    public Observable<Boolean> getEnclosureSubjectObservable() {
        return mNewPrintController.getEnclosureSubjectObservable();
    }

    public void setEnclosure(boolean state) {
        mNewPrintController.setEnclosure(state);
    }

    public Observable<PrintProgress> getUpdateProgressObservable() {
        return mPrintProgressSubject.hide();
    }

    public FilamentState getFilamentStateValue() {
        return mFilamentStateSubject.getValue();
    }

    public void setFilamentState(FilamentState filamentState) {
        mFilamentStateSubject.onNext(filamentState);
    }

    public Observable<FilamentState> getFilamentStateObservable() {
        return mFilamentStateSubject.hide();
    }

    public Observable<FilamentState> checkoutExtruderTemperature() {
        FilamentState value = mFilamentStateSubject.getValue();
        // Product definition: if the current temperature is 0, it will be heated to 210 (default value) when resuming heating after cutting off material
        return setExtruderTemperature(0, 0, (int) (((int) value.getLeftTarget()) == 0 ? 210 : value.getLeftTarget()))
                .flatMap(structure -> value.getExtruderNum() == 2 ?
                        setExtruderTemperature(0, 1, (int) (((int) value.getRightTarget()) == 0 ? 210 : value.getRightTarget()))
                        : Observable.just(structure))
                .flatMap(structure -> getFilamentStateObservable());
    }

    public Observable<ResponseStructure> requestActivatedExtrusion(int type, float lengthIn, float speedIn, float lengthOut, float speedOut) {
        return mA400Machine.getFDMController().requestActivatedExtrusion(type, lengthIn, speedIn, lengthOut, speedOut);
    }

    public Observable<PrintEvent> getPrintEventObservable() {
        Observable<PrintEvent> printEventObservable = mNewPrintController.getPrintEventObservable();
        printEventObservable
                .as(bindToLifecycle())
                .subscribe(printEvent -> mWaitingSubject.onNext(false), LogHelper::log);
        return printEventObservable;
    }

    public void setPowerOutageFlag(boolean flag) {
        mNewPrintController.setPowerOutageFlag(flag);
    }

}
