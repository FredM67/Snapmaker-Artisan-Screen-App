package fabscreen.features.machinetools.calibration.a400platform;

import com.orhanobut.logger.Logger;

import fabscreen.platform.base.instantiation.ServiceContainer;
import fabscreen.platform.base.service.IMachine;
import fabscreen.platform.base.service.machine.controller.MachineController;
import fabscreen.platform.base.service.machine.structure.ResponseStructure;
import fabscreen.platform.base.view.BaseViewModel;
import fabscreen.platform.lib.LogHelper;
import io.reactivex.Observable;
import io.reactivex.subjects.BehaviorSubject;

public class CalibrationCompleteViewModel extends BaseViewModel {
    BehaviorSubject<Boolean> mIsExitingSubj = BehaviorSubject.createDefault(true);

    /**
     * @param calibrationType one of {@link A400CalibrationActivity.CalibrationType}; only the XY
     *                        calibration prints a part and therefore cools the bed down on exit.
     *                        Bed leveling and Z-offset calibration leave the bed as it is.
     */
    public Observable<Boolean> saveAndExitCalibration(int calibrationType) {
        //noinspection rawtypes
        Observable<ResponseStructure> responseStructureObservable = null;
        boolean coolDownBed = calibrationType == A400CalibrationActivity.CalibrationType.DUAL_EXTRUDER_XY;
        IMachine.WorkType workType = ServiceContainer.getInstance().getService(IMachine.class).getMachineInfoSubjectHolder().getValue().workType;
        switch (workType) {
            case FDM:
                responseStructureObservable = ServiceContainer.getInstance().getService(IMachine.class).getFDMController().exitCalibration(true)
                        .flatMap(responseStructure -> (responseStructure.isSuccess() && coolDownBed) ? coolDownBedIfHave() : Observable.just(responseStructure));
                break;
            case LASER:
                responseStructureObservable = ServiceContainer.getInstance().getService(IMachine.class).getLaserController().exitCalibration(true);
                break;
            case CNC:
                responseStructureObservable = ServiceContainer.getInstance().getService(IMachine.class).getCNCController().exitCalibration(true);
                break;
            default:
                break;
        }
        if (responseStructureObservable == null) {
            mIsExitingSubj.onNext(false);
            return mIsExitingSubj.hide();
        }

        responseStructureObservable
                .doOnSubscribe(disposable -> mIsExitingSubj.onNext(true))
                .doOnNext(response -> mIsExitingSubj.onNext(false))
                .doOnError(e -> mIsExitingSubj.onNext(false))
                .as(bindToLifecycle())
                .subscribe(success -> {
                    if (!success.isSuccess()) {
                        Logger.d("Exit Calibration: " + success);
                    }
                }, LogHelper::log);

        ;
        return mIsExitingSubj.hide();
    }

    private Observable<ResponseStructure> coolDownBedIfHave() {
        MachineController machineController = ServiceContainer.getInstance().getService(IMachine.class).getMachineController();
        return (machineController.getHeatedBed() != null) ? machineController.getHeatedBed().setZoneTargetTemperature(0, 0) : Observable.just(new ResponseStructure());
    }
}
