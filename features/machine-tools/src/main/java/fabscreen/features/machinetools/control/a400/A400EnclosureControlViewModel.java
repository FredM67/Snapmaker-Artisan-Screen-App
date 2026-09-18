package fabscreen.features.machinetools.control.a400;

import fabscreen.platform.base.instantiation.ServiceContainer;
import fabscreen.platform.base.service.IMachine;
import fabscreen.platform.base.service.IPreferences;
import fabscreen.platform.base.service.machine.entity.module.Enclosure;
import fabscreen.platform.base.service.machine.structure.ResponseStructure;
import fabscreen.platform.base.view.BaseViewModel;
import fabscreen.platform.lib.LogHelper;
import io.reactivex.Observable;

public class A400EnclosureControlViewModel extends BaseViewModel {
    private final Enclosure mEnclosure;

    public A400EnclosureControlViewModel() {
        super();
        mEnclosure = ServiceContainer.getInstance().getService(IMachine.class).getMachineController().getEnclosure();
    }

    Observable<Enclosure.EnclosureStatus> getEnclosureStatusObservable() {
        return mEnclosure.getEnclosureStatusObservable();
    }

    boolean isDoorDetectionEnabled() {
        return mEnclosure.getEnclosureStatusValue().isDoorDetectionEnabled();
    }

    boolean isEnclosureLedOn() {
        return mEnclosure.getEnclosureStatusValue().isLedOn();
    }

    boolean isEnclosureFanOn() {
        return mEnclosure.getEnclosureStatusValue().isFanOn();
    }

    Observable<ResponseStructure> setLedLevel(int value) {
        return ServiceContainer.getInstance().getService(IMachine.class).getMachineController().getEnclosure()
                .setEnclosureLedLevelByUser(value);
    }

    Observable<ResponseStructure> setFanLevel(int value) {
        return ServiceContainer.getInstance().getService(IMachine.class).getMachineController().getEnclosure()
                .setEnclosureFanLevel(value);
    }

    void setDoorDetection(boolean enabled) {
        ServiceContainer.getInstance().getService(IMachine.class).getMachineController().getEnclosure()
                .setEnclosureDoorDetection(enabled)
                .as(bindToLifecycle())
                .subscribe(ret -> {/**/}, LogHelper::log);
    }

    boolean isEnclosureAutoLighting() {
        return ServiceContainer.getInstance().getService(IPreferences.class).getHelper().getEnclosureAutoLightingOn();
    }

    void setEnclosureAutoLighting(boolean enabled) {
        ServiceContainer.getInstance().getService(IPreferences.class).getHelper().setEnclosureAutoLightingOn(enabled);
    }

    public void subscribeEnclosureStatus() {
        mEnclosure.subscribeEnclosureInfo();
    }

    public void unSubscribeEnclosureStatus() {
        mEnclosure.unsubscribeEnclosureInfo();
    }
}
