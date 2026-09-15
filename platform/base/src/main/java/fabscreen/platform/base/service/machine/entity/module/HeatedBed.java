package fabscreen.platform.base.service.machine.entity.module;

import android.os.SystemClock;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import fabscreen.platform.base.R;
import fabscreen.platform.base.service.IMachine;
import fabscreen.platform.base.service.machine.IStructure;
import fabscreen.platform.base.service.machine.MachineConnectionController;
import fabscreen.platform.base.service.machine.TelemetryFreshness;
import fabscreen.platform.base.service.machine.entity.Module;
import fabscreen.platform.base.service.machine.structure.BaseStructure;
import fabscreen.platform.base.service.machine.structure.ResponseStructure;
import fabscreen.platform.base.service.machine.structure.ResultStructure;
import fabscreen.platform.base.service.machine.structure.SubscribeStructure;
import fabscreen.platform.base.service.machine.structure.prop.ArrayProp;
import fabscreen.platform.base.service.machine.structure.prop.FloatProp;
import fabscreen.platform.base.service.machine.structure.prop.UInt16Prop;
import fabscreen.platform.base.service.machine.structure.prop.UInt8Prop;
import fabscreen.platform.lib.LogHelper;
import fabscreen.platform.lib.SubjectHolder;
import io.reactivex.Observable;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.subjects.BehaviorSubject;
import okio.Buffer;

public class HeatedBed extends Module {
    private BehaviorSubject<HeatedBedStatus> mStatusSubject = BehaviorSubject.createDefault(new HeatedBedStatus());
    private SubjectHolder<HeatedBedStatus> mHeatedBedStatusSubjectHolder = new SubjectHolder<>(mStatusSubject);
    private CompositeDisposable mDisposables = new CompositeDisposable();
    private volatile boolean mHasLiveStatus;
    private volatile long mStatusUpdatedAt;
    private volatile long mStatusUpdatedAtElapsedRealtime;

    public HeatedBed(ModuleInfo info, IMachine mc, MachineConnectionController cc) {
        super(info, mc, cc);
    }

    public SubjectHolder<HeatedBedStatus> getHeatedBedStatusSubjectHolder() {
        return mHeatedBedStatusSubjectHolder;
    }

    public HeatedBed mockHeatedBedStatus() {
        HeatedBedStatus heatedBedStatus = new HeatedBedStatus();
        heatedBedStatus.setKey(-1);
        ArrayList<ZoneInfo> zoneInfos = new ArrayList<>();
        zoneInfos.add(new ZoneInfo());
        zoneInfos.add(new ZoneInfo());
        heatedBedStatus.setZoneList(zoneInfos);
        mStatusSubject.onNext(heatedBedStatus);
        return this;
    }

    @Override
    public void init() {
        Disposable subscribe = requestInfo().subscribe();
        mDisposables.add(subscribe);

        ResponseStructure<HeatedBedStatus> responseStructure2 = new ResponseStructure<>();
        responseStructure2.resultProp = new UInt8Prop();
        responseStructure2.dataProp = new HeatedBedStatus();
        responseStructure2.dataProp.zoneInfoArrayProp.addElement(new ZoneInfo());

        subscribe = mConnectionController.watch(0x14, 0xa0, responseStructure2)
                .subscribe(this::publishStatus);
        mDisposables.add(subscribe);
    }

    @Override
    public String getDisplayName() {
        return getAppContext().getString(R.string.print_heated_bed);
    }

    @Override
    public Observable<ResponseStructure<HeatedBedStatus>> requestInfo() {
        BaseStructure heatedBedRequest = new BaseStructure() {
            @Override
            protected void init() {
                addProp("key", new UInt8Prop());
            }
        };
        heatedBedRequest.getProp("key").setValue(getModuleInfo().getKey());

        ResponseStructure<HeatedBedStatus> responseStructure = new ResponseStructure<>();
        responseStructure.dataProp = new HeatedBedStatus();
        responseStructure.dataProp.zoneInfoArrayProp.addElement(new ZoneInfo());
        // FIXME: 2022/1/27 request multiple times
        return mConnectionController.request(0x14, 0x01, heatedBedRequest, responseStructure)
                .doOnNext(this::publishStatus);
    }

    /** True only after telemetry was received successfully from this physical heated bed. */
    public boolean hasLiveHeatedBedStatus() {
        return mHasLiveStatus;
    }

    public long getHeatedBedStatusUpdatedAt() {
        return mStatusUpdatedAt;
    }

    /** Uses the monotonic clock so wall-clock changes cannot make stale telemetry look fresh. */
    public boolean isHeatedBedStatusFresh(long maximumAgeMs) {
        return TelemetryFreshness.isFresh(
                mHasLiveStatus,
                mStatusUpdatedAtElapsedRealtime,
                SystemClock.elapsedRealtime(),
                maximumAgeMs
        );
    }

    private boolean publishStatus(ResponseStructure<HeatedBedStatus> response) {
        if (response == null || !response.isSuccess() || response.dataProp == null) {
            return false;
        }
        if (response.dataProp.getKey() != getModuleInfo().getKey()) {
            return false;
        }
        mStatusUpdatedAt = System.currentTimeMillis();
        mStatusUpdatedAtElapsedRealtime = SystemClock.elapsedRealtime();
        mHasLiveStatus = true;
        mStatusSubject.onNext(response.dataProp);
        return true;
    }

    @Deprecated
    public Observable<ResponseStructure> setZoneTargetTemperature(int zoneIndex, int targetTemperature) {
        BaseStructure heatedBedRequest = new BaseStructure() {
            @Override
            protected void init() {
                addProp("key", new UInt8Prop());
                addProp("zoneIndex", new UInt8Prop());
                addProp("targetTemperature", new UInt16Prop());
            }
        };
        heatedBedRequest.getProp("key").setValue(getModuleInfo().getKey());
        heatedBedRequest.getProp("zoneIndex").setValue(zoneIndex);
        heatedBedRequest.getProp("targetTemperature").setValue(targetTemperature);

        return mConnectionController.request(0x14, 0x02, heatedBedRequest, new ResponseStructure<>());
    }

    /**
     * Set temperature for all zones, fail if one of the operation fails.
     */
    @Deprecated
    public Observable<ResponseStructure> setAllTargetTemperature(int temperature) {
        List<ZoneInfo> zoneList = mHeatedBedStatusSubjectHolder.getValue().getZoneList();
        List<Observable<ResponseStructure>> observables = new ArrayList<>();
        for (int i = 0; i < zoneList.size(); i++) {
            int zoneIndex = zoneList.get(i).getZoneIndex();
            observables.add(setZoneTargetTemperature(zoneIndex, temperature));
        }

        return Observable.zip(observables, responseStructures -> {
            for (Object structure : responseStructures) {
                if (!((ResponseStructure) structure).isSuccess()) {
                    return ((ResponseStructure) structure);
                }
            }
            return (ResponseStructure) responseStructures[0];
        });
    }

    /**
     * setHeatedBedWorkMode: set work mode for the heated bed.
     * @param workMode mode for the heated working on.
     *                 0 - only heating in inner central area(hot temperature zone)
     *                 1 - the whole heated bed area
     *                 0xFF - using current mode(not choosing any type, just using current mode)
     * @return Observable that emits the result structure
     */
    public Observable<ResponseStructure> setHeatedBedWorkMode(int workMode) {
        BaseStructure structure = new BaseStructure() {
            @Override
            protected void init() {
                addProp("key", new UInt8Prop());
                addProp("workMode", new UInt8Prop());
            }
        };
        structure.getProp("key").setValue(getModuleInfo().getKey());
        structure.getProp("workMode").setValue(workMode);

        return mConnectionController.request(0x14, 0x03, structure, new ResponseStructure<>());
    }

    public Observable<ResponseStructure> setTargetTemperatureAndMode(int targetTemperature) {
        return setTargetTemperatureAndMode(targetTemperature, 0xFF);
    }

    public Observable<ResponseStructure> setTargetTemperatureAndMode(int targetTemperature, int workMode) {
        BaseStructure heatedBedRequest = new BaseStructure() {
            @Override
            protected void init() {
                addProp("key", new UInt8Prop());
                addProp("workMode", new UInt8Prop());
                addProp("targetTemperature", new UInt16Prop());
            }
        };
        heatedBedRequest.getProp("key").setValue(getModuleInfo().getKey());
        heatedBedRequest.getProp("workMode").setValue(workMode);
        heatedBedRequest.getProp("targetTemperature").setValue(targetTemperature);

        return mConnectionController.request(0x14, 0x04, heatedBedRequest, new ResponseStructure<>());
    }

    public void subscribeTemperatureChange() {
        SubscribeStructure subscribeStructure = new SubscribeStructure(0x14, 0xa0, 500);
        Disposable subscribe = mConnectionController.request(0x01, 0x00, subscribeStructure, new ResponseStructure()).subscribe(res -> {
        }, LogHelper::log);
        mDisposables.add(subscribe);
    }

    public void unsubscribeTemperatureChange() {
        SubscribeStructure subscribeStructure = new SubscribeStructure(0x14, 0xa0, 0);
        mDisposables.add(mConnectionController.request(0x01, 0x01, subscribeStructure, new ResponseStructure<>())
                .subscribe(result -> {
                }, LogHelper::log));
    }

    public static class HeatedBedStatus implements IStructure {
        final public static int HEATED_BED_STATUS_WORK_MODE_INNER = 0;
        final public static int HEATED_BED_STATUS_WORK_MODE_WHOLE = 1;
        private UInt8Prop keyProp = new UInt8Prop();
        private ArrayProp<ZoneInfo> zoneInfoArrayProp = new ArrayProp<>();
        private UInt8Prop workModeProp = new UInt8Prop();

        @Override
        public byte[] toByteArray() {
            Buffer buffer = new Buffer();
            buffer.write(keyProp.toByteArray());
            buffer.write(zoneInfoArrayProp.toByteArray());
            buffer.write(workModeProp.toByteArray());
            return buffer.readByteArray();
        }

        @Override
        public Buffer readBuffer(Buffer buffer) throws IOException {
            keyProp.readBuffer(buffer);
            zoneInfoArrayProp.readBuffer(buffer);
            try {
                workModeProp.readBuffer(buffer);
            } catch (IOException e) {
                return buffer;
            }
            return buffer;
        }

        public int getKey() {
            return keyProp.getValue();
        }

        public void setKey(int id) {
            keyProp.setValue(id);
        }

        public List<ZoneInfo> getZoneList() {
            return zoneInfoArrayProp.getValue();
        }

        public void setZoneList(List<ZoneInfo> zoneInfoList) {
            this.zoneInfoArrayProp.setValue(zoneInfoList);
        }

        public int getWorkMode() {
            return workModeProp.getValue();
        }

        public void setWorkMode(int mode) {
            workModeProp.setValue(mode);
        }

        @Override
        public String toString() {
            return "HeatedBedStatus{" +
                    "keyProp=" + keyProp +
                    ", zoneInfoArrayProp=" + zoneInfoArrayProp +
                    ", workModeProp=" + workModeProp +
                    '}';
        }
    }

    public static class ZoneInfo implements IStructure {
        private UInt8Prop zoneIndexProp = new UInt8Prop();
        private FloatProp currentTemperatureProp = new FloatProp();
        private UInt16Prop targetTemperatureProp = new UInt16Prop();

        public ZoneInfo() {
        }

        public ZoneInfo(int zoneIndex, float currentTemperature, int targetTemperature) {
            zoneIndexProp.setValue(zoneIndex);
            currentTemperatureProp.setValue(currentTemperature);
            targetTemperatureProp.setValue(targetTemperature);
        }

        @Override
        public byte[] toByteArray() {
            Buffer buffer = new Buffer();
            buffer.write(zoneIndexProp.toByteArray());
            buffer.write(currentTemperatureProp.toByteArray());
            buffer.write(targetTemperatureProp.toByteArray());
            return buffer.readByteArray();
        }

        @Override
        public Buffer readBuffer(Buffer buffer) throws IOException {
            zoneIndexProp.readBuffer(buffer);
            currentTemperatureProp.readBuffer(buffer);
            targetTemperatureProp.readBuffer(buffer);
            return buffer;
        }

        @Override
        public String toString() {
            return "HeatedBedStatus{" +
                    "\nzoneIndexProp=" + zoneIndexProp +
                    ",\n currentTemperatureProp=" + currentTemperatureProp +
                    ",\n targetTemperatureProp=" + targetTemperatureProp +
                    '}';
        }

        public int getZoneIndex() {
            return zoneIndexProp.getValue();
        }

        public void setZoneIndex(int id) {
            zoneIndexProp.setValue(id);
        }

        public float getCurrentTemperature() {
            return currentTemperatureProp.getValue();
        }

        public void setCurrentTemperature(float temperature) {
            currentTemperatureProp.setValue(temperature);
        }

        public int getTargetTemperature() {
            return targetTemperatureProp.getValue();
        }

        public void setTargetTemperature(int targetTemperature) {
            targetTemperatureProp.setValue(targetTemperature);
        }
    }
}
