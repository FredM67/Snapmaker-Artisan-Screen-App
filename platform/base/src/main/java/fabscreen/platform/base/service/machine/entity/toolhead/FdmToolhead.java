package fabscreen.platform.base.service.machine.entity.toolhead;

import android.os.SystemClock;

import com.orhanobut.logger.Logger;

import java.io.IOException;
import java.util.List;

import fabscreen.platform.base.R;
import fabscreen.platform.base.service.IMachine;
import fabscreen.platform.base.service.machine.IStructure;
import fabscreen.platform.base.service.machine.MachineConnectionController;
import fabscreen.platform.base.service.machine.TelemetryFreshness;
import fabscreen.platform.base.service.machine.entity.Toolhead;
import fabscreen.platform.base.service.machine.entity.parts.Extruder;
import fabscreen.platform.base.service.machine.entity.parts.Fan;
import fabscreen.platform.base.service.machine.structure.BaseStructure;
import fabscreen.platform.base.service.machine.structure.ResponseStructure;
import fabscreen.platform.base.service.machine.structure.SubscribeStructure;
import fabscreen.platform.base.service.machine.structure.prop.ArrayProp;
import fabscreen.platform.base.service.machine.structure.prop.BoolProp;
import fabscreen.platform.base.service.machine.structure.prop.UInt8Prop;
import fabscreen.platform.lib.LogHelper;
import fabscreen.platform.lib.SubjectHolder;
import io.reactivex.Observable;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.subjects.BehaviorSubject;
import okio.Buffer;

public class FdmToolhead extends Toolhead {
    private BehaviorSubject<FdmToolheadStatus> mToolHeadStatusSubject = BehaviorSubject.createDefault(new FdmToolheadStatus());
    private SubjectHolder<FdmToolheadStatus> mToolheadStatusSubjectHolder = new SubjectHolder<>(mToolHeadStatusSubject);
    private CompositeDisposable mDisposables = new CompositeDisposable();
    private ModuleInfo mModuleInfo;
    private volatile boolean mHasLiveExtruderStatus;
    private volatile long mExtruderStatusUpdatedAt;
    private volatile long mExtruderStatusUpdatedAtElapsedRealtime;
    private volatile boolean mHasLiveFanStatus;
    private volatile long mFanStatusUpdatedAt;
    private volatile long mFanStatusUpdatedAtElapsedRealtime;

    public FdmToolhead(ModuleInfo moduleInfo, IMachine mc, MachineConnectionController cc) {
        super(moduleInfo, mc, cc);
        mModuleInfo = moduleInfo;
    }

    @Override
    public void init() {
        Disposable subscribe = requestInfo().subscribe(response -> Logger.d("fdmth init result, %s", response), LogHelper::log);
        mDisposables.add(subscribe);

        BaseStructure baseStructure = new BaseStructure() {
            @Override
            protected void init() {
                addProp("key", new UInt8Prop());
                addProp("extruders", new ArrayProp<>(new Extruder()));
            }
        };

        ResponseStructure iStructureResponseStructure = new ResponseStructure<>();
        iStructureResponseStructure.dataProp = baseStructure;

        subscribe = mConnectionController.watch(0x10, 0xa0, iStructureResponseStructure).subscribe(response -> {
            if (response == null || !response.isSuccess() || response.dataProp == null) return;
            BaseStructure responseStructure = (BaseStructure) response.dataProp;
            int key = ((UInt8Prop) responseStructure.getProp("key")).getValue();
            if (key == mModuleInfo.getKey()) {
                FdmToolheadStatus value = mToolHeadStatusSubject.getValue();
                value.setId(key);
                value.setExtruderList(((ArrayProp<Extruder>) responseStructure.getProp("extruders")).getValue());
                publishExtruderStatus(value);
            }
        });
        mDisposables.add(subscribe);

        BaseStructure baseStructure1 = new BaseStructure() {
            @Override
            protected void init() {
                addProp("key", new UInt8Prop());
                addProp("fans", new ArrayProp<>(new Fan()));
            }
        };

        ResponseStructure iStructureResponseStructure1 = new ResponseStructure<>();
        iStructureResponseStructure1.dataProp = baseStructure1;

        subscribe = mConnectionController.watch(0x10, 0xa3, iStructureResponseStructure1).subscribe(response -> {
            if (response == null || !response.isSuccess() || response.dataProp == null) return;
            BaseStructure responseStructure = (BaseStructure) response.dataProp;
            int key = ((UInt8Prop) responseStructure.getProp("key")).getValue();
            if (key == mModuleInfo.getKey()) {
                FdmToolheadStatus value = mToolHeadStatusSubject.getValue();
                value.setId(key);
                value.setFanList(((ArrayProp<Fan>) responseStructure.getProp("fans")).getValue());
                publishFanStatus(value);
            }
        });
        mDisposables.add(subscribe);
    }

    @Override
    public String getDisplayName() {
        int headType = mModuleInfo.getModuleId();
        if (headType == ModuleType.HEAD_3DP) {
            return getAppContext().getString(R.string.all_tool_head_3dp);
        } else if (headType == ModuleType.HEAD_3DP_DOUBLE_EXTRUDER) {
            return getAppContext().getString(R.string.all_tool_head_dual_extruder);
        } else {
            return "unknown 3dp toolhead";
        }
    }

    @Override
    public Observable<ResponseStructure<FdmToolheadStatus>> requestInfo() {
        BaseStructure fdmRequest = new BaseStructure() {
            @Override
            protected void init() {
                addProp("key", new UInt8Prop());
            }
        };
        fdmRequest.getProp("key").setValue(getModuleInfo().getKey());

        ResponseStructure<FdmToolheadStatus> fdmResponse = new ResponseStructure<>();
        fdmResponse.dataProp = new FdmToolheadStatus();
        return mConnectionController.request(0x10, 0x01, fdmRequest, fdmResponse)
                .doOnNext(this::publishStatus);
    }


    // TODO: 2022/1/28 Who need this result Observable ???
    public Observable<ResponseStructure> subscribeExtruderChange() {
        SubscribeStructure subscribeStructure = new SubscribeStructure(0x10, 0xa0, 500);
        return mConnectionController.request(0x01, 0x00, subscribeStructure, new ResponseStructure());
    }

    public void unSubscribeExtruderChange() {
        SubscribeStructure subscribeStructure = new SubscribeStructure(0x10, 0xa0, 0);
        mDisposables.add(mConnectionController.request(0x01, 0x01, subscribeStructure, new ResponseStructure<>())
                .subscribe(result -> {
                }, LogHelper::log));
    }

    public Observable<ResponseStructure> subscribeFanChange() {
        SubscribeStructure subscribeStructure = new SubscribeStructure(0x10, 0xa3, 1000);
        return mConnectionController.request(0x01, 0x00, subscribeStructure, new ResponseStructure());
    }

    public void unSubscribeFanChange() {
        SubscribeStructure subscribeStructure = new SubscribeStructure(0x10, 0xa3, 0);
        mDisposables.add(mConnectionController.request(0x01, 0x01, subscribeStructure, new ResponseStructure<>())
                .subscribe(result -> {
                }, LogHelper::log));
    }

    public SubjectHolder<FdmToolheadStatus> getToolheadStatusSubjectHolder() {
        return mToolheadStatusSubjectHolder;
    }

    /** True only after telemetry was received successfully from this physical toolhead. */
    public boolean hasLiveToolheadStatus() {
        return mHasLiveExtruderStatus || mHasLiveFanStatus;
    }

    public long getToolheadStatusUpdatedAt() {
        return Math.max(mExtruderStatusUpdatedAt, mFanStatusUpdatedAt);
    }

    public boolean hasLiveExtruderStatus() {
        return mHasLiveExtruderStatus;
    }

    public long getExtruderStatusUpdatedAt() {
        return mExtruderStatusUpdatedAt;
    }

    /** Uses the monotonic clock so wall-clock changes cannot make stale telemetry look fresh. */
    public boolean isExtruderStatusFresh(long maximumAgeMs) {
        return isFresh(
                mHasLiveExtruderStatus,
                mExtruderStatusUpdatedAtElapsedRealtime,
                maximumAgeMs
        );
    }

    public boolean hasLiveFanStatus() {
        return mHasLiveFanStatus;
    }

    public long getFanStatusUpdatedAt() {
        return mFanStatusUpdatedAt;
    }

    /** Uses the monotonic clock so wall-clock changes cannot make stale telemetry look fresh. */
    public boolean isFanStatusFresh(long maximumAgeMs) {
        return isFresh(mHasLiveFanStatus, mFanStatusUpdatedAtElapsedRealtime, maximumAgeMs);
    }

    /** True if at least one toolhead telemetry stream is fresh. */
    public boolean isToolheadStatusFresh(long maximumAgeMs) {
        return isExtruderStatusFresh(maximumAgeMs) || isFanStatusFresh(maximumAgeMs);
    }

    private boolean isFresh(boolean hasLiveStatus, long updatedAt, long maximumAgeMs) {
        return TelemetryFreshness.isFresh(
                hasLiveStatus,
                updatedAt,
                SystemClock.elapsedRealtime(),
                maximumAgeMs
        );
    }

    private boolean publishStatus(ResponseStructure<FdmToolheadStatus> response) {
        if (response == null || !response.isSuccess() || response.dataProp == null) {
            return false;
        }
        if (response.dataProp.getId() != mModuleInfo.getKey()) {
            return false;
        }
        FdmToolheadStatus status = response.dataProp;
        long wallTime = System.currentTimeMillis();
        long elapsedTime = SystemClock.elapsedRealtime();
        if (status.getExtruderList() != null) {
            mExtruderStatusUpdatedAt = wallTime;
            mExtruderStatusUpdatedAtElapsedRealtime = elapsedTime;
            mHasLiveExtruderStatus = true;
        }
        if (status.getFanList() != null) {
            mFanStatusUpdatedAt = wallTime;
            mFanStatusUpdatedAtElapsedRealtime = elapsedTime;
            mHasLiveFanStatus = true;
        }
        mToolHeadStatusSubject.onNext(status);
        return true;
    }

    private void publishExtruderStatus(FdmToolheadStatus status) {
        if (status == null) return;
        mExtruderStatusUpdatedAt = System.currentTimeMillis();
        mExtruderStatusUpdatedAtElapsedRealtime = SystemClock.elapsedRealtime();
        mHasLiveExtruderStatus = true;
        mToolHeadStatusSubject.onNext(status);
    }

    private void publishFanStatus(FdmToolheadStatus status) {
        if (status == null) return;
        mFanStatusUpdatedAt = System.currentTimeMillis();
        mFanStatusUpdatedAtElapsedRealtime = SystemClock.elapsedRealtime();
        mHasLiveFanStatus = true;
        mToolHeadStatusSubject.onNext(status);
    }

    @Override
    public void reset() {
        mDisposables.clear();
        mHasLiveExtruderStatus = false;
        mExtruderStatusUpdatedAt = 0L;
        mExtruderStatusUpdatedAtElapsedRealtime = 0L;
        mHasLiveFanStatus = false;
        mFanStatusUpdatedAt = 0L;
        mFanStatusUpdatedAtElapsedRealtime = 0L;
    }

    public static class FdmToolheadStatus implements IStructure {
        private UInt8Prop keyProp = new UInt8Prop();
        private UInt8Prop headStatusProp = new UInt8Prop();
        private BoolProp headActiveProp = new BoolProp();
        private ArrayProp<Extruder> extruderListProp = new ArrayProp<>(new Extruder());
        private ArrayProp<Fan> fansListProp = new ArrayProp<>(new Fan());

        @Override
        public byte[] toByteArray() {
            Buffer buffer = new Buffer();
            buffer.write(keyProp.toByteArray());
            buffer.write(headStatusProp.toByteArray());
            buffer.write(headActiveProp.toByteArray());
            buffer.write(extruderListProp.toByteArray());
            buffer.write(fansListProp.toByteArray());
            return buffer.readByteArray();
        }

        @Override
        public Buffer readBuffer(Buffer buffer) throws IOException {
            keyProp.readBuffer(buffer);
            headStatusProp.readBuffer(buffer);
            headActiveProp.readBuffer(buffer);
            extruderListProp.readBuffer(buffer);
            fansListProp.readBuffer(buffer);
            return buffer;
        }

        @Override
        public String toString() {
            return "FdmToolheadStatus{" +
                    "\nidProp=" + keyProp +
                    ",\n headStatusProp=" + headStatusProp +
                    ",\n headActiveProp=" + headActiveProp +
                    ",\n extruderListProp=" + extruderListProp +
                    ",\n fansListProp=" + fansListProp +
                    '}';
        }

        public int getId() {
            return keyProp.getValue();
        }

        public void setId(int id) {
            keyProp.setValue(id);
        }

        public void setHeadStatus(int headStatus) {
            this.headStatusProp.setValue(headStatus);
        }

        public boolean isActive() {
            return headActiveProp.getValue();
        }

        public void setHeadActive(boolean headActive) {
            this.headActiveProp.setValue(headActive);
        }

        public List<Extruder> getExtruderList() {
            return extruderListProp.getValue();
        }

        public void setExtruderList(List<Extruder> extruderList) {
            this.extruderListProp.setValue(extruderList);
        }

        public void setExtruder(Extruder extruder) {
            extruderListProp.addElement(extruder);
        }

        public List<Fan> getFanList() {
            return fansListProp.getValue();
        }

        public void setFanList(List<Fan> fanList) {
            fansListProp.setValue(fanList);
        }
    }
}
