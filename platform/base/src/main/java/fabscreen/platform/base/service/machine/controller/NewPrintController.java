package fabscreen.platform.base.service.machine.controller;

import static fabscreen.platform.base.legacy.connection.MockConst.CAMERA_HEIGHT_OFFSET;
import static fabscreen.platform.base.legacy.connection.MockConst.H1_Z_POSITION;
import static fabscreen.platform.base.legacy.connection.MockConst.H2_Z_POSITION;
import static fabscreen.platform.base.service.machine.controller.PrintEventState.FINISH_FAIL;
import static fabscreen.platform.base.service.machine.controller.PrintEventState.FINISH_SUCCESS;
import static fabscreen.platform.base.service.machine.controller.PrintEventState.OPEN_DOOR_PAUSE;
import static fabscreen.platform.base.service.machine.controller.PrintEventState.PAUSE_FAIL;
import static fabscreen.platform.base.service.machine.controller.PrintEventState.PAUSE_SUCCESS;
import static fabscreen.platform.base.service.machine.controller.PrintEventState.POWER_LOSS_RESUME_FAIL;
import static fabscreen.platform.base.service.machine.controller.PrintEventState.POWER_LOSS_RESUME_SUCCESS;
import static fabscreen.platform.base.service.machine.controller.PrintEventState.RESUME_FAIL;
import static fabscreen.platform.base.service.machine.controller.PrintEventState.RESUME_SUCCESS;
import static fabscreen.platform.base.service.machine.controller.PrintEventState.START_FAIL;
import static fabscreen.platform.base.service.machine.controller.PrintEventState.STATE_SUCCESS;
import static fabscreen.platform.base.service.machine.controller.PrintEventState.STOP_FAIL;
import static fabscreen.platform.base.service.machine.controller.PrintEventState.STOP_SUCCESS;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;

import com.orhanobut.logger.Logger;

import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import fabscreen.platform.base.data.imgprocess.LaserDistanceMeasureProcess;
import fabscreen.platform.base.instantiation.IServiceIdentifier;
import fabscreen.platform.base.instantiation.ServiceContainer;
import fabscreen.platform.base.legacy.connection.MockConst;
import fabscreen.platform.base.lib.file.IFile;
import fabscreen.platform.base.lib.print.GcodePlayer;
import fabscreen.platform.base.lib.print.IPrintWorkspace;
import fabscreen.platform.base.lib.print.PrintListener;
import fabscreen.platform.base.lib.print.TickCounter;
import fabscreen.platform.base.service.IAppService;
import fabscreen.platform.base.service.IMachine;
import fabscreen.platform.base.service.IPreferences;
import fabscreen.platform.base.service.machine.MachineConnectionController;
import fabscreen.platform.base.service.machine.MachineStatus;
import fabscreen.platform.base.service.machine.Vector;
import fabscreen.platform.base.service.machine.entity.module.Enclosure;
import fabscreen.platform.base.service.machine.structure.BaseStructure;
import fabscreen.platform.base.service.machine.structure.ResponseStructure;
import fabscreen.platform.base.service.machine.structure.SubscribeStructure;
import fabscreen.platform.base.service.machine.structure.print.BatchBufferInfo;
import fabscreen.platform.base.service.machine.structure.prop.ArrayProp;
import fabscreen.platform.base.service.machine.structure.prop.BoolProp;
import fabscreen.platform.base.service.machine.structure.prop.FloatProp;
import fabscreen.platform.base.service.machine.structure.prop.Int16Prop;
import fabscreen.platform.base.service.machine.structure.prop.StringProp;
import fabscreen.platform.base.service.machine.structure.prop.UInt16Prop;
import fabscreen.platform.base.service.machine.structure.prop.UInt32Prop;
import fabscreen.platform.base.service.machine.structure.prop.UInt8Prop;
import fabscreen.platform.base.view.BaseActivity;
import fabscreen.platform.lib.LogHelper;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.subjects.BehaviorSubject;
import io.reactivex.subjects.PublishSubject;

public class NewPrintController implements IServiceIdentifier {

    private static final int ACTION_NONE = 0;
    private static final int ACTION_START = 1;
    private static final int ACTION_PAUSE = 2;
    private static final int ACTION_RESUME = 3;
    private static final int ACTION_STOP = 4;
    private static final int ACTION_RECOVER = 5;
    private static final int ACTION_COUNT = 6;
    private static final long RESULT_SEQUENCE_DEDUP_WINDOW_MS = 10_000L;

    MachineConnectionController mConnectionController;
    IMachine mMachine;

    GcodePlayer mGcodePlayer = new GcodePlayer();
    TickCounter mTickCounter = new TickCounter();

    // PrintListener was using in PrintJ1Fragment, CalibrationPrintViewModel(J1)
    //  and LevelingXYCalibrationPrintFragment(J1).
    //  We WILL NOT use this listener to call back action response, please use `PrintEvent` instead.
    @Deprecated
    PrintListener mPrintListener;

    private boolean mRecoveryFlag = false;
    private boolean mStartFromRemoteFlag = false;
    private boolean mAttachStartedRemotePrintFlag;
    private final Object mPrintPreparationReservationLock = new Object();
    private long mNextPrintPreparationLease;
    private long mPrintPreparationLease;
    private boolean mPrintPreparationStarting;
    private boolean mPrintStartAcceptedAwaitingNonIdle;
    private boolean mPrintStartOutcomeUncertain;
    private final ThreadLocal<Long> mPrintPreparationMutationLease = new ThreadLocal<>();
    private String mFileName = "";

    private final int BATCHES_NUMS = 3;
    private BatchCode[] mCacheBatches;
    private int mStartBatchNo;
    private int mEndBatchNo;
    private int mNowBatchNo;
    private BatchCode mBatchCode;
    private int mBatchesCount;

    Disposable mExtruderWorkSpeedSubscribe;
    Disposable mPrintModeStatusSubscribe;
    private Disposable mPrintDisposable;
    private final Object mActionLock = new Object();
    private boolean mActionInFlight;
    private long mNextActionGeneration;
    private long mActiveActionGeneration;
    private long mPrintDisposableGeneration;
    private int mActiveActionType = ACTION_NONE;
    private final long[] mPendingResultGenerations = new long[ACTION_COUNT];
    private final long[] mRetiredResultGenerations = new long[ACTION_COUNT];
    private final int[] mLastResultSequences = new int[ACTION_COUNT];
    private final long[] mLastResultSequenceTimes = new long[ACTION_COUNT];
    private Disposable mWatchPrintGcodeLineDisposable;
    private Disposable mUnwatchPrintGcodeLineDisposable;
    Disposable mGetMachineStatusSubscribe;
    Disposable watchPrintingLineNoSubscribe;

    PublishSubject<PrintEvent> mPrintEventSubject = PublishSubject.create();
    PublishSubject<PrintEvent> mPrintSuccessSubject = PublishSubject.create();

    PublishSubject<ResponseStructure> requestPrintStartResultSubject;
    PublishSubject<ResponseStructure> requestPrintPauseResultSubject;
    PublishSubject<ResponseStructure> requestPrintResumeResultSubject;
    PublishSubject<ResponseStructure> requestPrintStopResultSubject;
    PublishSubject<ResponseStructure> requestPrintResumeFromPowerOutageResultSubject;
    private BehaviorSubject<Integer> mMachineStatusSubject = BehaviorSubject.createDefault(0);
    private BehaviorSubject<Boolean> mEnclosureSubject = BehaviorSubject.createDefault(false);
    private BehaviorSubject<Integer> mExtruderWorkSpeedSubject = BehaviorSubject.createDefault(0);
    private BehaviorSubject<Float> mCurrentProgressSubject = BehaviorSubject.createDefault(0f);
    private BehaviorSubject<Integer> mPrintModeStatusSubject = BehaviorSubject.createDefault(-1);
    private BehaviorSubject<Boolean> mFilamentSubject = BehaviorSubject.createDefault(false);
    private BehaviorSubject<ArrayList<Integer>> mTookHeadSpeedSubject = BehaviorSubject.createDefault(new ArrayList<>());


    public NewPrintController(IMachine mc, MachineConnectionController cc) {
        mMachine = mc;
        mConnectionController = cc;

        mGetMachineStatusSubscribe = mc.getMachineStatusSubjectHolder()
                .getObservable()
                .subscribe(machineStatus -> {
                    if (machineStatus.status
                            != MachineOperationStatus.SYSTEM_STATUS_IDLE.value()) {
                        synchronized (mPrintPreparationReservationLock) {
                            mPrintStartAcceptedAwaitingNonIdle = false;
                        }
                    }
                    if (mMachineStatusSubject.getValue() != machineStatus.status) {
                        mMachineStatusSubject.onNext(machineStatus.status);
                    }
                });

        Disposable subscribe = mConnectionController.watch(0xac, 0xa4, new ResponseStructure(new ArrayProp<>(new UInt16Prop())))
                .subscribe(requestPrintPauseResultSubject -> {
                    List<UInt16Prop> uInt16PropList = ((ArrayProp<UInt16Prop>) requestPrintPauseResultSubject.dataProp).getValue();
                    ArrayList<Integer> speeds = new ArrayList<>();
                    for (int i = 0; i < uInt16PropList.size(); i++) {
                        speeds.add(uInt16PropList.get(i).getValue());
                    }
                    mTookHeadSpeedSubject.onNext(speeds);
                });

        Disposable sub = mPrintSuccessSubject
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(printEvent -> {
                    if (FINISH_SUCCESS == printEvent.getPrintEventState()) {
                        Context nowViewContext = ServiceContainer.getInstance().getService(IAppService.class).getNowViewContext();
                        if (nowViewContext != null) return;
                        ((BaseActivity) nowViewContext).onFinishSuccess(mFileName, getTickCounter().getCount());
                    }
                });
    }

    public Observable<ArrayList<Integer>> getTookHeadSpeedObservable() {
        return mTookHeadSpeedSubject.hide();
    }

    public void subscribeTookHeadSpeed() {
        SubscribeStructure subscribeStructure = new SubscribeStructure(0xac, 0xa4, 1000);
        Disposable subscribe = mConnectionController.request(0x01, 0x00, subscribeStructure, new ResponseStructure())
                .subscribe(responseStructure -> {
                }, LogHelper::log);
    }

    public void unSubscribeTookHeadSpeed() {
        SubscribeStructure subscribeStructure = new SubscribeStructure(0xac, 0xa4, 0);
        Disposable subscribe = mConnectionController.request(0x01, 0x01, subscribeStructure, new ResponseStructure())
                .subscribe(responseStructure -> {
                }, LogHelper::log);
    }

    public void onMachineReportPrintIssue(int commandSet, int commandId, int sequence, UInt8Prop printIssue) {
        int issueCode = printIssue.getValue();
        Logger.d("Print Issue %d request", issueCode);
        switch (issueCode) {
            case 0:
                Logger.d("Print finished triggered.");
                mPrintEventSubject.onNext(new PrintEvent(FINISH_SUCCESS, 0));
                mPrintSuccessSubject.onNext(new PrintEvent(FINISH_SUCCESS, 0));
                unWatchPrintingLineNo();
                logPrintResult();
                if (mPrintListener != null) {
                    Logger.i("Print Finished.");
                    mPrintListener.onFinishSuccess();
                }
                break;
            case 1:
                Logger.d("G-code paused event triggered.");
                // paused triggered by gcode
                break;
            case 2:
                Logger.d("G-code filament event triggered.");
                // filament paused triggered by gcode
                break;
            case 3:
                // filament runout event
                Logger.w("Filament runout event triggered.");
                mFilamentSubject.onNext(true);
                break;
            case 4:
                Logger.w("Extruder stuck safety triggered.");
                break;
            case 5:
                Logger.w("Print temperature abnormal triggered.");
                break;
            case 6:
                Logger.w("Print batch not match reported by controller.");
                break;
            case 7:
                Logger.w("Get G-code batch failed reported by controller.");
                break;
            case 8:
                Logger.w("Emergency Stop triggered reported by controller.");
                break;
            case 9:
                Logger.w("machine tool head recover failed reported by controller.");
                break;
            case 10:
                mPrintEventSubject.onNext(new PrintEvent(STOP_FAIL, 0));
                Logger.w("machine stop args failed reported by controller.");
                break;
            case 11:
                mPrintEventSubject.onNext(new PrintEvent(STOP_FAIL, 0));
                Logger.w("machine stop failed reported by controller.");
                break;
            case 12:
                mPrintEventSubject.onNext(new PrintEvent(STOP_SUCCESS, 0));
                Logger.w("client request stop reported by controller.");
                break;
            case 13:
                Logger.w("machine pause args failed reported by controller.");
                break;
            case 14:
                Logger.w("machine environment abnormal reported by controller.");
                break;
            case 15:
                Logger.w("machine pause failed reported by controller.");
                break;
            case 16:
                mPrintEventSubject.onNext(new PrintEvent(OPEN_DOOR_PAUSE, 0));
                Logger.w("enclosure interrupted reported by controller.");
                break;
            case 21:
                Logger.i("Print resumed.");
                mPrintEventSubject.onNext(new PrintEvent(RESUME_SUCCESS, 0));
                if (mPrintListener != null) {
                    mPrintListener.onResumeSuccess();
                }
                mTickCounter.start();
                break;
            // Because of the abnormality, the master control stopped actively
            case 22:
                Logger.i("Print Finished.");
                // Preserve the controller issue so cloud/status consumers can distinguish an
                // abnormal controller-initiated stop from an operator-requested cancellation.
                mPrintEventSubject.onNext(new PrintEvent(STOP_SUCCESS, 22));
                mCurrentProgressSubject.onNext(0f);
                unWatchPrintingLineNo();
                mTickCounter.stop();
                break;
            case 255:
                Logger.w("Controller report position failure");
            default:
                Logger.w("event triggered, issueCode %d", issueCode);
                break;
        }
        ResponseStructure responseStructure = new ResponseStructure();
        responseStructure.resultProp = new UInt8Prop(0);
        mConnectionController.sendResponse(commandSet, commandId, sequence, responseStructure);
    }

    public ResponseStructure<BaseStructure> onMachineRequestBatchBufferInfo(BatchBufferInfo info) {
        // Do business and send response.
        int requestLineNo = (int) info.getLineNo();
        int batchLength = (int) info.getBatchBufferLength();
        if (requestLineNo % 1000 == 0) {
            Logger.d("Request LineNo %s , batchLength %d", String.valueOf(requestLineNo), batchLength);
        }
        return doNext(requestLineNo, batchLength);
    }

    public boolean start() {
        return startInternal(0L);
    }

    public boolean start(long preparationLease) {
        if (preparationLease <= 0L) return false;
        return startInternal(preparationLease);
    }

    private boolean startInternal(long preparationLease) {
        Logger.i("Start print.");
        if (!claimPrintPreparationForStart(preparationLease)) return false;
        final long actionGeneration = beginAction(ACTION_START);
        if (actionGeneration == 0) {
            releasePrintPreparationStartClaim(preparationLease, false);
            return false;
        }
        consumePrintPreparationLeaseForStart(preparationLease);
        try {
            prepare();
            IPrintWorkspace workspace = ServiceContainer.getInstance().getService(IPrintWorkspace.class);
            String md5 = workspace.getFileMD5Value();
            mFileName = workspace.getFileName();
            final int workTypeIndex = mMachine.getMachineInfoSubjectHolder().getValue().workType.ordinal() - 1;
            BaseStructure gcodeFileInfo = new BaseStructure() {
                @Override
                protected void init() {
                    addProp("md5", new StringProp(md5));
                    addProp("filename", new StringProp(mFileName));
                    addProp("type", new UInt8Prop(workTypeIndex));
                }
            };

        Disposable sub = requestPrintStart(gcodeFileInfo, actionGeneration)
                .timeout(20, TimeUnit.SECONDS)
                .take(1)
                .observeOn(AndroidSchedulers.mainThread())
                .doFinally(() -> {
                    finishAction(ACTION_START, actionGeneration);
                    finishPrintPreparationStart();
                })
                .subscribe(responseStructure -> {
                    if (responseStructure.isSuccess()) {
                        Logger.i("Print started.");
                        markPrintStartAcceptedSettling();
                        // Clear the flag bit of remote start printing after the printing starts
                        mStartFromRemoteFlag = false;
                        mPrintEventSubject.onNext(new PrintEvent(STATE_SUCCESS, 0));
                        if (mPrintListener != null) {
                            mPrintListener.onStartSuccess();
                        }
                        watchPrintingLineNo();
                        mTickCounter.reset();
                        mTickCounter.start();
                    } else {
                        Logger.i("Print started Error." + responseStructure);
                        mPrintEventSubject.onNext(new PrintEvent(START_FAIL, responseStructure.resultProp.getValue()));
                        if (mPrintListener != null) {
                            mPrintListener.onStartFailed(responseStructure.resultProp.getValue());
                        }
                    }
                }, e -> {
                    LogHelper.log(e);
                    markPrintStartOutcomeUncertain();
                    // If 0 is launched, it means that there is a problem with the screen program,
                    // which has nothing to do with the nature of the machine itself
                    mPrintEventSubject.onNext(new PrintEvent(FINISH_FAIL, 0));
                    if (mPrintListener != null) {
                        mPrintListener.onStartFailed(0);
                    }
                });
        setActionDisposable(ACTION_START, actionGeneration, sub);
        return true;
        } catch (RuntimeException e) {
            markPrintStartOutcomeUncertain();
            finishPrintPreparationStart();
            return failActionSetup(ACTION_START, actionGeneration, FINISH_FAIL, e);
        }
    }

    public boolean pause() {
        Logger.i("Pause print.");
        final long actionGeneration = beginAction(ACTION_PAUSE);
        if (actionGeneration == 0) return false;
        try {
        Disposable sub = requestPrintPause(actionGeneration)
                .timeout(20, TimeUnit.SECONDS)
                .take(1)
                .observeOn(AndroidSchedulers.mainThread())
                .doFinally(() -> finishAction(ACTION_PAUSE, actionGeneration))
                .subscribe(resultStructure -> {
                    if (resultStructure.isSuccess()) {
                        mPrintEventSubject.onNext(new PrintEvent(PAUSE_SUCCESS, 0));
                        if (mPrintListener != null) {
                            mPrintListener.onPauseSuccess();
                        }
                        mTickCounter.stop();
                    } else {
                        Logger.i("Print paused Error.");
                        mPrintEventSubject.onNext(new PrintEvent(PAUSE_FAIL, resultStructure.resultProp.getValue()));
                        if (mPrintListener != null) {
                            mPrintListener.onPauseFailed(resultStructure.resultProp.getValue());
                        }
                    }
                }, e -> {
                    LogHelper.log(e);
                    // If 0 is launched, it means that there is a problem with the screen program,
                    // which has nothing to do with the nature of the machine itself
                    mPrintEventSubject.onNext(new PrintEvent(PAUSE_FAIL, 0));
                    if (mPrintListener != null) {
                        mPrintListener.onPauseFailed(0);
                    }
                });
        setActionDisposable(ACTION_PAUSE, actionGeneration, sub);
        return true;
        } catch (RuntimeException e) {
            return failActionSetup(ACTION_PAUSE, actionGeneration, PAUSE_FAIL, e);
        }
    }

    public boolean resume() {
        Logger.i("Resume print.");
        final long actionGeneration = beginAction(ACTION_RESUME);
        if (actionGeneration == 0) return false;
        try {
        Disposable sub = requestPrintResume(actionGeneration)
                .timeout(20, TimeUnit.SECONDS)
                .take(1)
                .observeOn(AndroidSchedulers.mainThread())
                .doFinally(() -> finishAction(ACTION_RESUME, actionGeneration))
                .subscribe(responseStructure -> {
                    if (responseStructure.isSuccess()) {
                        Logger.i("Print resumed.");
                        mPrintEventSubject.onNext(new PrintEvent(RESUME_SUCCESS, 0));
                        if (mPrintListener != null) {
                            mPrintListener.onResumeSuccess();
                        }
                        mTickCounter.start();
                    } else {
                        Logger.i("Print resumed Error.");
                        mPrintEventSubject.onNext(new PrintEvent(RESUME_FAIL, responseStructure.resultProp.getValue()));
                        if (mPrintListener != null) {
                            mPrintListener.onResumeFailed(responseStructure.resultProp.getValue());
                        }
                    }
                }, e -> {
                    LogHelper.log(e);
                    // If 0 is launched, it means that there is a problem with the screen program,
                    // which has nothing to do with the nature of the machine itself
                    mPrintEventSubject.onNext(new PrintEvent(RESUME_FAIL, 0));
                    if (mPrintListener != null) {
                        mPrintListener.onResumeFailed(0);
                    }
                });
        setActionDisposable(ACTION_RESUME, actionGeneration, sub);
        return true;
        } catch (RuntimeException e) {
            return failActionSetup(ACTION_RESUME, actionGeneration, RESUME_FAIL, e);
        }
    }

    public boolean stop() {
        Logger.i("Stop print.");
        final long actionGeneration = beginAction(ACTION_STOP);
        if (actionGeneration == 0) return false;
        try {
        Disposable sub = requestPrintStop(actionGeneration)
                .timeout(20, TimeUnit.SECONDS)
                .take(1)
                .observeOn(AndroidSchedulers.mainThread())
                .doFinally(() -> finishAction(ACTION_STOP, actionGeneration))
                .subscribe(resultStructure -> {
                    if (resultStructure.isSuccess()) {
                        Logger.i("Print Finished.");
                        mPrintEventSubject.onNext(new PrintEvent(STOP_SUCCESS, 0));
                        if (mPrintListener != null) {
                            mPrintListener.onStopSuccess();
                        }
                        mCurrentProgressSubject.onNext(0f);
                        unWatchPrintingLineNo();
                        mTickCounter.stop();
                    } else {
//                        Logger.w("Unable to finish printing, ret code %d", retCode);
                        mPrintEventSubject.onNext(new PrintEvent(STOP_FAIL, resultStructure.resultProp.getValue()));
                        if (mPrintListener != null) {
                            mPrintListener.onStopFailed(resultStructure.resultProp.getValue());
                        }
                    }
                }, e -> {
                    LogHelper.log(e);
                    // If 0 is launched, it means that there is a problem with the screen program,
                    // which has nothing to do with the nature of the machine itself
                    mPrintEventSubject.onNext(new PrintEvent(STOP_FAIL, 0));
                    if (mPrintListener != null) {
                        mPrintListener.onStopFailed(0);
                    }
                });
        setActionDisposable(ACTION_STOP, actionGeneration, sub);
        return true;
        } catch (RuntimeException e) {
            return failActionSetup(ACTION_STOP, actionGeneration, STOP_FAIL, e);
        }
    }

    public boolean recover() {
        Logger.i("Recover print.");
        final long actionGeneration = beginAction(ACTION_RECOVER);
        if (actionGeneration == 0) return false;
        try {
            prepare();
            IPrintWorkspace workspace = ServiceContainer.getInstance().getService(IPrintWorkspace.class);
            String md5 = workspace.getFileMD5Value();
            mFileName = workspace.getFileName();
            BaseStructure gcodeFileInfo = new BaseStructure() {
                @Override
                protected void init() {
                    addProp("md5", new StringProp(md5));
                    addProp("filename", new StringProp(mFileName));
                }
            };

        Disposable sub = requestPrintResumeFromPowerOutage(gcodeFileInfo, actionGeneration)
                .timeout(20, TimeUnit.SECONDS)
                .take(1)
                .observeOn(AndroidSchedulers.mainThread())
                .doFinally(() -> finishAction(ACTION_RECOVER, actionGeneration))
                .subscribe(responseStructure -> {
                    if (responseStructure.isSuccess()) {
                        Logger.i("Print recovered.");
                        mPrintEventSubject.onNext(new PrintEvent(POWER_LOSS_RESUME_SUCCESS, 0));
                        if (mPrintListener != null) {
                            mPrintListener.onResumeFromPowerOutageSuccess();
                        }
                        watchPrintingLineNo();
                        mTickCounter.load();
                        mTickCounter.start();
                    } else {
                        mPrintEventSubject.onNext(new PrintEvent(POWER_LOSS_RESUME_FAIL, responseStructure.resultProp.getValue()));
                        if (mPrintListener != null) {
                            mPrintListener.onResumeFromPowerOutageFailed(responseStructure.resultProp.getValue());
                        }
                    }
                }, e -> {
                    LogHelper.log(e);
                    // If 0 is launched, it means that there is a problem with the screen program,
                    // which has nothing to do with the nature of the machine itself
                    mPrintEventSubject.onNext(new PrintEvent(POWER_LOSS_RESUME_FAIL, 0));
                    if (mPrintListener != null) {
                        mPrintListener.onResumeFromPowerOutageFailed(0);
                    }
                });
        setActionDisposable(ACTION_RECOVER, actionGeneration, sub);
        return true;
        } catch (RuntimeException e) {
            return failActionSetup(ACTION_RECOVER, actionGeneration, POWER_LOSS_RESUME_FAIL, e);
        }
    }

    public void reset() {
        if (!isPrintWorkspaceMutationAllowed()) {
            Logger.w("Ignored print-controller reset while another preparation owns the lease.");
            return;
        }
        resetInternal();
    }

    private void resetInternal() {
        mCurrentProgressSubject.onNext(0f);
        mGcodePlayer.reset();
        mPrintEventSubject.onComplete();
        mPrintEventSubject = PublishSubject.create();
        mBatchesCount = 0;

        mFilamentSubject.onNext(false);
        Disposable actionDisposable;
        PublishSubject<ResponseStructure> pendingResultSubject;
        synchronized (mActionLock) {
            actionDisposable = mPrintDisposable;
            pendingResultSubject = retirePendingResultLocked(mActiveActionType, mActiveActionGeneration);
            mPrintDisposable = null;
            mPrintDisposableGeneration = 0;
            mActionInFlight = false;
            mActiveActionType = ACTION_NONE;
            mActiveActionGeneration = 0;
        }
        if (pendingResultSubject != null) {
            pendingResultSubject.onComplete();
        }
        if (actionDisposable != null && !actionDisposable.isDisposed()) {
            actionDisposable.dispose();
        }
        if (mUnwatchPrintGcodeLineDisposable != null
                && !mUnwatchPrintGcodeLineDisposable.isDisposed()) {
            mUnwatchPrintGcodeLineDisposable.dispose();
            mUnwatchPrintGcodeLineDisposable = null;
        }
    }
    /* ------------------ 内部功能实现 ----------------------- */

    private void prepare() {
        clearBatch();
    }

    /**
     * Actual next function, send G-code or resend G-code.
     * Query batch corresponding to the start line number of the output based on the line number
     * of the master control request.
     *
     * @param lineNo
     */
    private ResponseStructure<BaseStructure> doNext(int lineNo, int requestBatchLength) {
        mBatchCode = new BatchCode("", lineNo, lineNo - 1);
        /**
         *                    -->-->-->-->-->     G-code line order     -->-->-->-->-->
         *     (lineNo < mStartBatchNo)     |       (mBatches[])        |   (lineNo > mEndBatchNo)
         */
        if (mGcodePlayer.gcodeIsEmpty()) {
            return buildGcodeBatchResponseStructure();
        }

        if (lineNo < mStartBatchNo) {
            Logger.d("Abnormal: LineNo %s < mStartBatchNo %s,mEndBatchNo:%s", lineNo, mStartBatchNo, mEndBatchNo);
            // Request line was way before our batches.
            // In order to return the "pointer", clean up batches from now and start over again.
            clearBatch();
            return doNext(lineNo, requestBatchLength);
        }

        if (lineNo > mEndBatchNo) {
            // Not starting at line 0 was supported
            if (mNowBatchNo == -1) {
                mGcodePlayer.setLineno(lineNo);
                mGcodePlayer.nextLine();
            }
            mNowBatchNo = lineNo;

            while (mGcodePlayer.getLineNo() < lineNo) {
                mGcodePlayer.nextLine();
            }

            // Failed to obtain G-code, complete the task
            if (mGcodePlayer.getLine() == null) {
                return buildGcodeBatchResponseStructure();
            }

            // Get enough batch G-code(s) under requestBatchLength, start filling data.
            String tempBatch = getBatchContentFromGcode(lineNo, requestBatchLength);

            mEndBatchNo = mGcodePlayer.getLineNo() - 1;
            // Fill data into mBatchCode
            mBatchCode = new BatchCode(tempBatch, mNowBatchNo, mEndBatchNo);
            int batchIndex = (getBatchNo(lineNo - 1) + 1) % BATCHES_NUMS;
            // Cache batch into batches[]
            mCacheBatches[batchIndex] = mBatchCode;
            mBatchesCount++;
        } else {
            StringBuilder linesBuffer = new StringBuilder();
            // Reissue or resend
            int batchIndex = getBatchNo(lineNo);
            // Failed to find the required batch
            if (batchIndex == -1) {
                Logger.e("Error: Batch can't find :" + lineNo);
                clearBatch();
                return doNext(lineNo, requestBatchLength);
            }
            BatchCode tempBatch = mCacheBatches[batchIndex];
            String[] tempGcodes = tempBatch.getGcodes().split("\n");
            for (int i = lineNo - tempBatch.getStartNo(); i < tempGcodes.length; i++) {
                linesBuffer.append(tempGcodes[i]).append("\n");
            }
            mBatchCode = new BatchCode(linesBuffer.toString(), lineNo, tempBatch.getEndNo());
        }
        return buildGcodeBatchResponseStructure();
    }

    private String getBatchContentFromGcode(int lineNo, int requestBatchLength) {
        int batchLength = 0;
        StringBuilder linesBuffer = new StringBuilder();
        while (batchLength < requestBatchLength) {
            String line = mGcodePlayer.getLine();
            if (mGcodePlayer.getLine() == null) {
                // Failed to obtain G-code, complete the task, break
                Logger.w("Could not get G-codes at %d, expecting %d.",
                        mGcodePlayer.getSentCount(), mGcodePlayer.getTotalCount());
                break;
            }

            // Replace comments in G-code code
            int index = line.indexOf(";");
            if (index != -1) {
                line = line.substring(0, index + 1);
            }
            line += "\n";

            batchLength += line.getBytes().length;
            if (batchLength < requestBatchLength) {
                // Current line counts on, and we have more space to fill in.
                // Continue reading next line.
                linesBuffer.append(line);
                mGcodePlayer.nextLine();
            }
        }
        return linesBuffer.toString();
    }

    private ResponseStructure<BaseStructure> buildGcodeBatchResponseStructure() {
        mStartBatchNo = getStartBatchNo();
        long startLineNo = mBatchCode.getStartNo();
        long endLineNo = mBatchCode.getEndNo();
        ResponseStructure<BaseStructure> responseStructure = new ResponseStructure<>();
        responseStructure.dataProp = new BaseStructure() {
            @Override
            protected void init() {
                addProp("startLineNo", new UInt32Prop(startLineNo));
                addProp("endLineNo", new UInt32Prop(endLineNo));
                addProp("gcodeBatch", new StringProp(mBatchCode.getGcodes()));
            }
        };
        if (startLineNo > endLineNo) {
            // No more G-code lines
            responseStructure.resultProp = new UInt8Prop(201);
        }
        Logger.d("sendGcode startLineNo:%d,endLineNo:%d,gcodeBatch length:%d",
                startLineNo, endLineNo, mBatchCode.getGcodes().length());
        mBatchesCount++;
        return responseStructure;
    }

    private void dispose(Disposable disposable) {
        if (disposable != null && !disposable.isDisposed()) {
            disposable.dispose();
        }
    }

    private long beginAction(int actionType) {
        synchronized (mActionLock) {
            if (mActionInFlight) {
                Logger.w("Reject overlapping print command.");
                return 0;
            }
            if (mRetiredResultGenerations[actionType] != 0) {
                Logger.w("Reject %s retry while waiting to discard generation %d result.",
                        actionName(actionType), mRetiredResultGenerations[actionType]);
                return 0;
            }
            mNextActionGeneration++;
            if (mNextActionGeneration == 0) {
                mNextActionGeneration++;
            }
            mActionInFlight = true;
            mActiveActionType = actionType;
            mActiveActionGeneration = mNextActionGeneration;
            return mActiveActionGeneration;
        }
    }

    private void finishAction(int actionType, long actionGeneration) {
        PublishSubject<ResponseStructure> pendingResultSubject;
        synchronized (mActionLock) {
            if (!isCurrentActionLocked(actionType, actionGeneration)) {
                return;
            }
            pendingResultSubject = retirePendingResultLocked(actionType, actionGeneration);
            if (mPrintDisposableGeneration == actionGeneration) {
                mPrintDisposable = null;
                mPrintDisposableGeneration = 0;
            }
            mActionInFlight = false;
            mActiveActionType = ACTION_NONE;
            mActiveActionGeneration = 0;
        }
        if (pendingResultSubject != null) {
            pendingResultSubject.onComplete();
        }
    }

    private boolean failActionSetup(int actionType, long actionGeneration,
                                    PrintEventState failureEvent, RuntimeException error) {
        try {
            LogHelper.log(error);
            synchronized (mActionLock) {
                if (isCurrentActionLocked(actionType, actionGeneration)) {
                    mPrintEventSubject.onNext(new PrintEvent(failureEvent, 0));
                }
            }
        } finally {
            finishAction(actionType, actionGeneration);
        }
        return false;
    }

    private void setActionDisposable(int actionType, long actionGeneration, Disposable disposable) {
        boolean disposeNew;
        synchronized (mActionLock) {
            disposeNew = !isCurrentActionLocked(actionType, actionGeneration);
            if (!disposeNew) {
                mPrintDisposable = disposable;
                mPrintDisposableGeneration = actionGeneration;
            }
        }
        if (disposeNew) {
            dispose(disposable);
        }
    }

    private boolean isCurrentActionLocked(int actionType, long actionGeneration) {
        return mActionInFlight
                && mActiveActionType == actionType
                && mActiveActionGeneration == actionGeneration;
    }

    private Observable<ResponseStructure> registerPendingResult(int actionType, long actionGeneration) {
        PublishSubject<ResponseStructure> resultSubject = PublishSubject.create();
        PublishSubject<ResponseStructure> previousSubject;
        synchronized (mActionLock) {
            if (!isCurrentActionLocked(actionType, actionGeneration)) {
                return Observable.empty();
            }
            previousSubject = getResultSubjectLocked(actionType);
            setResultSubjectLocked(actionType, resultSubject);
            mPendingResultGenerations[actionType] = actionGeneration;
        }
        if (previousSubject != null) {
            previousSubject.onComplete();
        }
        return resultSubject.hide();
    }

    private PublishSubject<ResponseStructure> retirePendingResultLocked(int actionType,
                                                                         long actionGeneration) {
        if (actionType <= ACTION_NONE || actionType >= ACTION_COUNT
                || actionGeneration == 0
                || mPendingResultGenerations[actionType] != actionGeneration) {
            return null;
        }
        PublishSubject<ResponseStructure> resultSubject = getResultSubjectLocked(actionType);
        mPendingResultGenerations[actionType] = 0;
        setResultSubjectLocked(actionType, null);
        mRetiredResultGenerations[actionType] = actionGeneration;
        return resultSubject;
    }

    private void dispatchActionResult(int actionType, int commandSet, int commandId,
                                      int sequence, ResponseStructure value) {
        // Always acknowledge the firmware request, including stale and duplicate results.
        mConnectionController.sendResponse(commandSet, commandId, sequence, new UInt8Prop(0));

        PublishSubject<ResponseStructure> resultSubject = null;
        long retiredGeneration = 0;
        boolean duplicate = false;
        long now = SystemClock.elapsedRealtime();
        synchronized (mActionLock) {
            long lastSequenceTime = mLastResultSequenceTimes[actionType];
            duplicate = lastSequenceTime != 0
                    && mLastResultSequences[actionType] == sequence
                    && now - lastSequenceTime >= 0
                    && now - lastSequenceTime <= RESULT_SEQUENCE_DEDUP_WINDOW_MS;
            if (!duplicate) {
                mLastResultSequences[actionType] = sequence;
                mLastResultSequenceTimes[actionType] = now;
                retiredGeneration = mRetiredResultGenerations[actionType];
                if (retiredGeneration != 0) {
                    mRetiredResultGenerations[actionType] = 0;
                } else if (isCurrentActionLocked(actionType, mPendingResultGenerations[actionType])) {
                    resultSubject = getResultSubjectLocked(actionType);
                    mPendingResultGenerations[actionType] = 0;
                    setResultSubjectLocked(actionType, null);
                }
            }
        }

        if (duplicate) {
            Logger.w("Discard duplicate %s result sequence %d.", actionName(actionType), sequence);
        } else if (retiredGeneration != 0) {
            Logger.w("Discard late %s result for generation %d.",
                    actionName(actionType), retiredGeneration);
        } else if (resultSubject != null) {
            resultSubject.onNext(value);
        } else {
            Logger.w("Discard unowned %s result sequence %d.", actionName(actionType), sequence);
        }
    }

    private PublishSubject<ResponseStructure> getResultSubjectLocked(int actionType) {
        switch (actionType) {
            case ACTION_START:
                return requestPrintStartResultSubject;
            case ACTION_PAUSE:
                return requestPrintPauseResultSubject;
            case ACTION_RESUME:
                return requestPrintResumeResultSubject;
            case ACTION_STOP:
                return requestPrintStopResultSubject;
            case ACTION_RECOVER:
                return requestPrintResumeFromPowerOutageResultSubject;
            default:
                return null;
        }
    }

    private void setResultSubjectLocked(int actionType,
                                        PublishSubject<ResponseStructure> resultSubject) {
        switch (actionType) {
            case ACTION_START:
                requestPrintStartResultSubject = resultSubject;
                break;
            case ACTION_PAUSE:
                requestPrintPauseResultSubject = resultSubject;
                break;
            case ACTION_RESUME:
                requestPrintResumeResultSubject = resultSubject;
                break;
            case ACTION_STOP:
                requestPrintStopResultSubject = resultSubject;
                break;
            case ACTION_RECOVER:
                requestPrintResumeFromPowerOutageResultSubject = resultSubject;
                break;
            default:
                break;
        }
    }

    private String actionName(int actionType) {
        switch (actionType) {
            case ACTION_START:
                return "start";
            case ACTION_PAUSE:
                return "pause";
            case ACTION_RESUME:
                return "resume";
            case ACTION_STOP:
                return "stop";
            case ACTION_RECOVER:
                return "recover";
            default:
                return "unknown";
        }
    }

    private void clearBatch() {
        mCacheBatches = new BatchCode[BATCHES_NUMS];
        mEndBatchNo = -1;
        mNowBatchNo = -1;
        mStartBatchNo = -1;
    }

    private int getStartBatchNo() {
        int batchNo = -1;
        if (mCacheBatches == null || mCacheBatches.length == 0) return batchNo;
        for (int i = 0; i < mCacheBatches.length; i++) {
            if (mCacheBatches[i] == null) {
                continue;
            }
            batchNo = batchNo == -1 ? mCacheBatches[i].getStartNo() : Math.min(batchNo, mCacheBatches[i].getStartNo());
        }
        return batchNo;
    }

    float calculatePrintProgress(long lineNo) {
        // Handle boundary condition.
        int totalCount = mGcodePlayer.getTotalCount();
        if (lineNo < 0) {
            return 0f;
        } else if (lineNo > totalCount) {
            return 1f;
        } else {
            return (1.0f * lineNo / totalCount) * 10 / 10.0f;
        }
    }

    private int getBatchNo(int lineNo) {
        for (int i = 0; i < mCacheBatches.length; i++) {
            if (mCacheBatches[i] == null) {
                continue;
            }
            if (mCacheBatches[i].getStartNo() <= lineNo && mCacheBatches[i].getEndNo() >= lineNo) {
                return i;
            }
        }
        return -1;
    }

    private void watchPrintingLineNo() {
        Logger.d("start watching print line no...");
        if (mWatchPrintGcodeLineDisposable != null && !mWatchPrintGcodeLineDisposable.isDisposed()) {
            mWatchPrintGcodeLineDisposable.dispose();
            mWatchPrintGcodeLineDisposable = null;
        }
        if (watchPrintingLineNoSubscribe != null && !watchPrintingLineNoSubscribe.isDisposed()) {
            watchPrintingLineNoSubscribe.dispose();
            watchPrintingLineNoSubscribe = null;
        }

        SubscribeStructure subscribeStructure = new SubscribeStructure(0xac, 0xa0, 2000);
        watchPrintingLineNoSubscribe = mConnectionController.request(0x01, 0x00, subscribeStructure, new ResponseStructure())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(responseStructure -> {
                    if (responseStructure.isSuccess()) {
                        ResponseStructure<UInt32Prop> baseStructureResponseStructure = new ResponseStructure<>(new UInt32Prop());
                        mWatchPrintGcodeLineDisposable = mConnectionController.watch(0xac, 0xa0, baseStructureResponseStructure)
                                .subscribe(responseStructure1 -> {
                                    if (!responseStructure1.isSuccess()) {
                                        Logger.d("watch print response error, error code %d", responseStructure.resultProp.getValue());
                                    } else {
                                        long lineNo = responseStructure1.dataProp.getValue();
                                        Logger.d("Print %s gcode line now...", String.valueOf(lineNo));
                                        mCurrentProgressSubject.onNext(calculatePrintProgress(lineNo));
                                    }
                                }, LogHelper::log);
                    } else {
                        Logger.e("Subscribe print G-code line event failed, " + responseStructure);
                    }
                }, LogHelper::log);
    }

    private void unWatchPrintingLineNo() {
        Logger.d("unWatch Print line no...");
        if (mWatchPrintGcodeLineDisposable != null && !mWatchPrintGcodeLineDisposable.isDisposed()) {
            mWatchPrintGcodeLineDisposable.dispose();
            mWatchPrintGcodeLineDisposable = null;
        }
        if (watchPrintingLineNoSubscribe != null && !watchPrintingLineNoSubscribe.isDisposed()) {
            watchPrintingLineNoSubscribe.dispose();
            watchPrintingLineNoSubscribe = null;
        }
        if (mUnwatchPrintGcodeLineDisposable != null
                && !mUnwatchPrintGcodeLineDisposable.isDisposed()) {
            mUnwatchPrintGcodeLineDisposable.dispose();
        }
        mUnwatchPrintGcodeLineDisposable = mConnectionController.request(0x01, 0x01, new SubscribeStructure(0xac, 0xa0, 0), new ResponseStructure())
                .subscribe(responseStructure -> {
                    Logger.d("Unwatch line no " + responseStructure.isSuccess());
        }, LogHelper::log);
        mConnectionController.unWatch(0xac, 0xa0);
    }

    private void logPrintResult() {
        Logger.d("Log print: %d batches, lastLineNo %d, sentCount %d",
                mBatchesCount, mEndBatchNo, mGcodePlayer.getSentCount());
    }

    /* ------------------ 提供外部数据接口 ----------------------- */
    public Observable<Integer> getExtruderWorkSpeedObservable() {
        return mExtruderWorkSpeedSubject.hide();
    }

    public Observable<Integer> getPrintModeStatusObservable() {
        return mPrintModeStatusSubject.hide();
    }

    public Integer getPrintModeStatusValue() {
        return mPrintModeStatusSubject.getValue();
    }

    public boolean getStartFromRemoteFlag() {
        return mStartFromRemoteFlag;
    }

    public void setStartFromRemoteFlag(boolean removePrintFlag) {
        mStartFromRemoteFlag = removePrintFlag;
    }

    public long tryAcquirePrintPreparationLease() {
        synchronized (mPrintPreparationReservationLock) {
            if (mPrintPreparationLease != 0L
                    || mPrintPreparationStarting
                    || mPrintStartAcceptedAwaitingNonIdle
                    || mPrintStartOutcomeUncertain) return 0L;
            long lease = ++mNextPrintPreparationLease;
            if (lease <= 0L) {
                mNextPrintPreparationLease = 1L;
                lease = 1L;
            }
            mPrintPreparationLease = lease;
            return lease;
        }
    }

    public void abortPrintPreparation(long lease) {
        if (lease <= 0L) return;
        synchronized (mPrintPreparationReservationLock) {
            if (!mPrintPreparationStarting && mPrintPreparationLease == lease) {
                mPrintPreparationLease = 0L;
            }
        }
    }

    public boolean isPrintPreparationReserved() {
        synchronized (mPrintPreparationReservationLock) {
            return mPrintPreparationLease != 0L
                    || mPrintPreparationStarting
                    || mPrintStartAcceptedAwaitingNonIdle
                    || mPrintStartOutcomeUncertain;
        }
    }

    public boolean runWithPrintPreparationLease(long lease, Runnable mutation) {
        if (lease <= 0L || mutation == null) return false;
        synchronized (mPrintPreparationReservationLock) {
            if (mPrintPreparationStarting
                    || mPrintStartAcceptedAwaitingNonIdle
                    || mPrintStartOutcomeUncertain
                    || mPrintPreparationLease != lease) return false;
            mPrintPreparationMutationLease.set(lease);
            try {
                mutation.run();
                return true;
            } finally {
                mPrintPreparationMutationLease.remove();
            }
        }
    }

    public boolean isPrintWorkspaceMutationAllowed() {
        synchronized (mPrintPreparationReservationLock) {
            if (mPrintPreparationLease == 0L
                    && !mPrintPreparationStarting
                    && !mPrintStartAcceptedAwaitingNonIdle
                    && !mPrintStartOutcomeUncertain) return true;
            Long permittedLease = mPrintPreparationMutationLease.get();
            return !mPrintPreparationStarting
                    && !mPrintStartAcceptedAwaitingNonIdle
                    && !mPrintStartOutcomeUncertain
                    && permittedLease != null
                    && permittedLease == mPrintPreparationLease;
        }
    }

    public Observable<PrintEvent> preparePrintStart(
            long lease,
            IFile file,
            int totalLines
    ) {
        if (lease <= 0L || file == null || totalLines <= 0) return null;
        synchronized (mPrintPreparationReservationLock) {
            if (mPrintPreparationStarting
                    || mPrintStartAcceptedAwaitingNonIdle
                    || mPrintStartOutcomeUncertain
                    || mPrintPreparationLease != lease) return null;
            mPrintPreparationMutationLease.set(lease);
            try {
                resetInternal();
                mGcodePlayer.setGcodeFile(file);
                mGcodePlayer.setTotalCount(totalLines);
                return mPrintEventSubject.hide();
            } finally {
                mPrintPreparationMutationLease.remove();
            }
        }
    }

    public boolean runPrintWorkspaceMutation(Runnable mutation) {
        if (mutation == null) return false;
        synchronized (mPrintPreparationReservationLock) {
            if (mPrintPreparationLease != 0L
                    || mPrintPreparationStarting
                    || mPrintStartAcceptedAwaitingNonIdle
                    || mPrintStartOutcomeUncertain) {
                Long permittedLease = mPrintPreparationMutationLease.get();
                if (mPrintPreparationStarting
                        || mPrintStartAcceptedAwaitingNonIdle
                        || mPrintStartOutcomeUncertain
                        || permittedLease == null
                        || permittedLease != mPrintPreparationLease) return false;
            }
            mutation.run();
            return true;
        }
    }

    private boolean claimPrintPreparationForStart(long lease) {
        synchronized (mPrintPreparationReservationLock) {
            if (mPrintPreparationStarting
                    || mPrintStartAcceptedAwaitingNonIdle
                    || mPrintStartOutcomeUncertain) return false;
            if (lease == 0L) {
                if (mPrintPreparationLease != 0L) return false;
            } else if (mPrintPreparationLease != lease) {
                return false;
            }
            mPrintPreparationStarting = true;
            return true;
        }
    }

    private void releasePrintPreparationStartClaim(long lease, boolean consume) {
        synchronized (mPrintPreparationReservationLock) {
            if (consume && lease > 0L && mPrintPreparationLease == lease) {
                mPrintPreparationLease = 0L;
            }
            mPrintPreparationStarting = false;
        }
    }

    private void consumePrintPreparationLeaseForStart(long lease) {
        synchronized (mPrintPreparationReservationLock) {
            if (lease > 0L && mPrintPreparationLease == lease) {
                mPrintPreparationLease = 0L;
            }
        }
    }

    private void finishPrintPreparationStart() {
        synchronized (mPrintPreparationReservationLock) {
            mPrintPreparationStarting = false;
        }
    }

    private void markPrintStartAcceptedSettling() {
        synchronized (mPrintPreparationReservationLock) {
            MachineStatus latestStatus = mMachine.getMachineStatusSubjectHolder().getValue();
            // A successful START response and the independently reported machine state can
            // arrive in either order. Keep all workspace preparation/reset paths closed only
            // while the cached status is still IDLE; the status observer releases this gate
            // as soon as it sees the first non-IDLE state.
            mPrintStartAcceptedAwaitingNonIdle = latestStatus == null
                    || latestStatus.status == MachineOperationStatus.SYSTEM_STATUS_IDLE.value();
        }
    }

    public void markPrintStartOutcomeUncertain() {
        synchronized (mPrintPreparationReservationLock) {
            // A START request may already be active in firmware even when its result was
            // lost. There is no request correlation that can prove otherwise, so prevent
            // all subsequent workspace/player replacement until this controller is
            // recreated with the HMI process.
            mPrintStartOutcomeUncertain = true;
        }
    }

    public boolean isPrintStartOutcomeUncertain() {
        synchronized (mPrintPreparationReservationLock) {
            return mPrintStartOutcomeUncertain;
        }
    }

    public int getPrintState() {
        return mMachineStatusSubject.getValue();
    }

    public Observable<Integer> getPrintStateObservable() {
        return mMachineStatusSubject.hide();
    }

    public Observable<Boolean> getEnclosureSubjectObservable() {
        return mEnclosureSubject.hide();
    }

    public void setEnclosure(Boolean confirm) {
        mEnclosureSubject.onNext(!confirm);
    }

    public void setFile(IFile file) {
        runPrintWorkspaceMutation(() -> mGcodePlayer.setGcodeFile(file));
    }

    public int getTotalLines() {
        return mGcodePlayer.getTotalCount();
    }

    public void setTotalLines(int lines) {
        runPrintWorkspaceMutation(() -> mGcodePlayer.setTotalCount(lines));
    }

    public void markAttachStartedRemotePrint() {
        mAttachStartedRemotePrintFlag = true;
    }

    public boolean consumeAttachStartedRemotePrint() {
        boolean attach = mAttachStartedRemotePrintFlag;
        mAttachStartedRemotePrintFlag = false;
        return attach;
    }

    public boolean hasAttachStartedRemotePrint() {
        return mAttachStartedRemotePrintFlag;
    }

    public boolean getRecoveryFlag() {
        return mRecoveryFlag;
    }

    public void setPowerOutageFlag(boolean flag) {
        mRecoveryFlag = flag;
    }

    @Deprecated
    public void setListener(PrintListener listener) {
        mPrintListener = listener;
    }

    public Observable<PrintEvent> getPrintEventObservable() {
        return mPrintEventSubject.hide();
    }

    public TickCounter getTickCounter() {
        return mTickCounter;
    }

    public float getProgress() {
        return mCurrentProgressSubject.getValue();
    }

    public Observable<Boolean> getFilamentSubjectObservable() {
        return mFilamentSubject.hide();
    }

    public boolean isFilamentRunout() {
        return Boolean.TRUE.equals(mFilamentSubject.getValue());
    }

    public void setFilament(Boolean confirm) {
        mFilamentSubject.onNext(!confirm);
    }

    /**
     * Pass in the Gcode file inputStream.
     * Apk built-in print files are entered as input streams
     * Network input streams are supported, but the correct number of lines cannot be displayed
     *
     * @param inputStream
     */
    public void setInputStream(InputStream inputStream) {
        mGcodePlayer.setInputStream(inputStream);
    }

    /* ------------------ 流程区 ----------------------- */

    /**
     * 激光自动测厚流程
     * 返回 -200 为识别错误
     *
     * @return
     */
    public Observable<Float> getAutoThickness(float x, float y, float z, int speed) {
        Vector vector = new Vector();
        vector.setX(x);
        vector.setY(y);
        vector.setZ(z);
        if (mMachine.getMachineInfoSubjectHolder().getValue().isEnclosureAvailable) {
            Enclosure enclosure = mMachine.getMachineController().getEnclosure();
            return enclosure.setEnclosureLedLevel(0).flatMap(responseStructure -> getAutoThickness(vector, speed, enclosure.getEnclosureStatusValue().isLedOn() ? 1 : 0));
        } else {
            return getAutoThickness(vector, speed, -1);
        }
    }

    private Observable<Float> getAutoThickness(Vector vector, int speed, int enclosureAvailable) {
        return mMachine.getMachineController().updateCoordinateSystem(0)
                .flatMap(success -> mMachine.getMachineController().gotoAbsolutePosition(vector, speed))
                .flatMap(success -> mMachine.getMachineController().updateCoordinateSystem(1))
                .flatMap(success -> mMachine.getLaserController().switchFocusAssistLight(1))
                .flatMap(success -> mMachine.getLaserController().getLaserCameraController().setExposeTime(2))
                .flatMap(success -> mMachine.getLaserController().getLaserCameraController().requestCapturePhoto())
                .flatMap(success -> mMachine.getLaserController().getLaserCameraController().watchPhotoReceive())
                .flatMap(bitmap -> {
                    float autoThickness = -200;
                    FileOutputStream out = new FileOutputStream(ServiceContainer.getInstance().getService(IAppService.class).getCacheDir() + "/distance.png");
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                    float spotX = LaserDistanceMeasureProcess.process(bitmap);
                    if (spotX < -200) {
                        return Observable.just(autoThickness);
                    }
                    Logger.i("Detected spot x position is %s", spotX);
                    float mS1plus = ServiceContainer.getInstance().getService(IPreferences.class).getHelper().getLaserThicknessS1Plus();
                    float mS2plus = ServiceContainer.getInstance().getService(IPreferences.class).getHelper().getLaserThicknessS2Plus();
                    float h1 = H1_Z_POSITION + CAMERA_HEIGHT_OFFSET;
                    float h2 = H2_Z_POSITION + CAMERA_HEIGHT_OFFSET;
                    float h3 = h1 - h2;
                    autoThickness = h1 - (h1 * ((h3 * mS1plus) + ((mS2plus * h2) - (mS1plus * h1))) / (h3 * spotX + ((mS2plus * h2) - (mS1plus * h1)))) + MockConst.LASER_MATERIAL_MEASURE_CALIBRATION_OBJECT_HEIGHT;
                    return Observable.just(autoThickness);
                })
                .flatMap(success -> {
                    if (enclosureAvailable == 1) {
                        return mMachine.getMachineController().getEnclosure().setEnclosureLedLevel(100).flatMap(aBoolean -> Observable.just(success));
                    } else {
                        return Observable.just(success);
                    }
                })
                .flatMap(success -> mMachine.getLaserController().getLaserCameraController().setExposeTime(0).flatMap(aBoolean -> Observable.just(success)))
                .flatMap(success -> mMachine.getLaserController().switchFocusAssistLight(0).flatMap(aBoolean -> Observable.just(success)));
    }

    /* ------------------ 接口区 ----------------------- */
    Observable<ResponseStructure> requestPrintFileInfo() {
        return mConnectionController.request(0xac, 0x00, null, new ResponseStructure());
    }

    Observable<ResponseStructure> requestPrintStart(BaseStructure gcodeFileInfo,
                                                    long actionGeneration) {
        return mConnectionController.request(0xac, 0x03, gcodeFileInfo, new ResponseStructure())
                .flatMap(responseStructure -> {
                            if (responseStructure.isSuccess()) {
                                return registerPendingResult(ACTION_START, actionGeneration);
                            } else {
                                return Observable.just(responseStructure);
                            }
                        }
                );
    }

    public void onRequestPrintStartResult(int commandSet, int commandId, int sequence, ResponseStructure value) {
        dispatchActionResult(ACTION_START, commandSet, commandId, sequence, value);
    }

    Observable<ResponseStructure> requestPrintPause(long actionGeneration) {
        return mConnectionController.request(0xac, 0x04, null, new ResponseStructure())
                .flatMap(responseStructure -> {
                            if (responseStructure.isSuccess()) {
                                return registerPendingResult(ACTION_PAUSE, actionGeneration);
                            } else {
                                return Observable.just(responseStructure);
                            }
                        }
                );
    }

    public void onRequestPrintPauseResult(int commandSet, int commandId, int sequence, ResponseStructure value) {
        dispatchActionResult(ACTION_PAUSE, commandSet, commandId, sequence, value);
    }

    Observable<ResponseStructure> requestPrintResume(long actionGeneration) {
        return mConnectionController.request(0xac, 0x05, null, new ResponseStructure())
                .flatMap(responseStructure -> {
                            if (responseStructure.isSuccess()) {
                                return registerPendingResult(ACTION_RESUME, actionGeneration);
                            } else {
                                return Observable.just(responseStructure);
                            }
                        }
                );
    }

    public void onRequestPrintResumeResult(int commandSet, int commandId, int sequence, ResponseStructure value) {
        dispatchActionResult(ACTION_RESUME, commandSet, commandId, sequence, value);
    }

    Observable<ResponseStructure> requestPrintStop(long actionGeneration) {
        return mConnectionController.request(0xac, 0x06, null, new ResponseStructure())
                .flatMap(responseStructure -> {
                            if (responseStructure.isSuccess()) {
                                return registerPendingResult(ACTION_STOP, actionGeneration);
                            } else {
                                return Observable.just(responseStructure);
                            }
                        }
                );
    }

    public void onRequestPrintStopResult(int commandSet, int commandId, int sequence, ResponseStructure value) {
        dispatchActionResult(ACTION_STOP, commandSet, commandId, sequence, value);
    }

    public Observable<ResponseStructure> requestPowerOutageStatus() {
        ResponseStructure responseStructure = new ResponseStructure();
        BaseStructure gcodeFileInfo = new BaseStructure() {
            @Override
            protected void init() {
                addProp("md5", new StringProp(""));
                addProp("filename", new StringProp(""));
            }
        };
        responseStructure.resultProp = new UInt8Prop();
        responseStructure.dataProp = gcodeFileInfo;
        return mConnectionController.request(0xac, 0x07, null, responseStructure);
    }

    Observable<ResponseStructure> requestPrintResumeFromPowerOutage(BaseStructure gcodeFileInfo,
                                                                     long actionGeneration) {
        return mConnectionController.request(0xac, 0x08, gcodeFileInfo, new ResponseStructure())
                .flatMap(responseStructure -> {
                            if (responseStructure.isSuccess()) {
                                return registerPendingResult(ACTION_RECOVER, actionGeneration);
                            } else {
                                return Observable.just(responseStructure);
                            }
                        }
                );
    }

    public void onRequestPrintResumeFromPowerOutageResult(int commandSet, int commandId, int sequence, ResponseStructure value) {
        dispatchActionResult(ACTION_RECOVER, commandSet, commandId, sequence, value);
    }

    public Observable<ResponseStructure> requestPrintPowerLossClearMarker() {
        return mConnectionController.request(0xac, 0x09, null, new ResponseStructure());
    }

    public Observable<ResponseStructure> requestChangePrintMode(int printMode) {
        BaseStructure structure = new BaseStructure() {
            @Override
            protected void init() {
                addProp("requestPrintMode", new UInt8Prop(printMode));
            }
        };
        return mConnectionController.request(0xac, 0x0a, structure, new ResponseStructure<>());
    }

    public Observable<ResponseStructure> requestPrintModeStatus() {
        ResponseStructure<BaseStructure> responseStructure = new ResponseStructure<>();

        responseStructure.dataProp = new BaseStructure() {
            @Override
            protected void init() {
                addProp("printMode", new UInt8Prop());
            }
        };
        return mConnectionController.request(0xac, 0x0b, null, responseStructure);
    }

    public Observable<ResponseStructure> requestPrintStartOffset(float xOffset, float yOffset, float zOffset) {
        BaseStructure structure = new BaseStructure() {
            @Override
            protected void init() {
                addProp("xOffset", new FloatProp(xOffset));
                addProp("yOffset", new FloatProp(yOffset));
                addProp("zOffset", new FloatProp(zOffset));
            }
        };
        return mConnectionController.request(0xac, 0x0c, structure, new ResponseStructure());
    }

    public Observable<ResponseStructure> requestStopOneExtruder(int index) {
        int key = ServiceContainer.getInstance().getService(IMachine.class)
                .getFDMController()
                .getModuleIdFromIndex(index);

        BaseStructure requestStructure = new BaseStructure() {
            @Override
            protected void init() {
                addProp("key", new UInt8Prop(key));
                addProp("enable", new BoolProp(false));
            }
        };
        return mConnectionController.request(0xac, 0x0d, requestStructure, new ResponseStructure());
    }

//    //FIXME:  Set the working speed adjustment ratio
//    public Observable<ResponseStructure> setExtruderWorkSpeed(int id, int extruderIndex, int speed) {
//        FDMController fdmController = ServiceContainer.getInstance().getService(IMachine.class).getFDMController();
//        int key = fdmController.getModuleIdFromIndex(id);
//        BaseStructure fdmRequest = new BaseStructure() {
//            @Override
//            protected void init() {
//                addProp("key", new UInt8Prop());
//                addProp("extruderId", new UInt8Prop());
//                addProp("speed", new Int16Prop());
//            }
//        };
//        fdmRequest.getProp("key").setValue(key);
//        fdmRequest.getProp("extruderId").setValue(extruderIndex);
//        fdmRequest.getProp("speed").setValue(speed);
//        return mConnectionController.request(0xac, 0x0e, fdmRequest, new ResponseStructure());
//    }

    public Observable<ResponseStructure> setPrintWorkSpeed(IMachine.WorkType workType, int id, int extruderIndex, int speed) {
        int key = -1;
        switch (workType) {
            case FDM:
                key = ServiceContainer.getInstance().getService(IMachine.class).getFDMController().getModuleIdFromIndex(id);
                break;
            case LASER:
                key = ServiceContainer.getInstance().getService(IMachine.class).getLaserController().getLaserToolHeadInfoValue(id).getKey();
                break;
            case CNC:
                key = ServiceContainer.getInstance().getService(IMachine.class).getCNCController().getCncToolHeadInfoValue(id).getKey();
                break;
            case NONE:
            default:
        }
        BaseStructure fdmRequest = new BaseStructure() {
            @Override
            protected void init() {
                addProp("key", new UInt8Prop());
                addProp("extruderId", new UInt8Prop(extruderIndex));
                addProp("speed", new Int16Prop(speed));
            }
        };
        ((UInt8Prop) fdmRequest.getProp("key")).setValue(key);
        return mConnectionController.request(0xac, 0x0e, fdmRequest, new ResponseStructure());
    }

    public Observable<ResponseStructure> getExtruderWorkSpeed(IMachine.WorkType workType, int id) {
        int key = -1;
        ResponseStructure responseStructure = new ResponseStructure();
        switch (workType) {
            case FDM:
                key = ServiceContainer.getInstance().getService(IMachine.class).getFDMController().getModuleIdFromIndex(id);
                break;
            case LASER:
                key = ServiceContainer.getInstance().getService(IMachine.class).getLaserController().getLaserToolHeadInfoValue(id).getKey();
                break;
            case CNC:
                key = ServiceContainer.getInstance().getService(IMachine.class).getCNCController().getCncToolHeadInfoValue(id).getKey();
                break;
            case NONE:
            default:
                responseStructure.resultProp = new UInt8Prop(6);
                return Observable.just(responseStructure);
        }
        BaseStructure request = new BaseStructure() {
            @Override
            protected void init() {
                addProp("key", new UInt8Prop());
            }
        };
        ((UInt8Prop) request.getProp("key")).setValue(key);

        BaseStructure baseStructure = new BaseStructure() {
            @Override
            protected void init() {
                addProp("key", new UInt8Prop());
                addProp("workSpeed", new ArrayProp<>(new UInt16Prop()));
            }
        };
        responseStructure.dataProp = baseStructure;
        return mConnectionController.request(0xac, 0x0f, request, responseStructure);
    }

    public Observable<ResponseStructure> setFDMFlowRate(int id, int extruderIndex, int flowRate) {
        FDMController fdmController = ServiceContainer.getInstance().getService(IMachine.class).getFDMController();
        int key = fdmController.getModuleIdFromIndex(id);
        BaseStructure fdmRequest = new BaseStructure() {
            @Override
            protected void init() {
                addProp("key", new UInt8Prop());
                addProp("extruderId", new UInt8Prop());
                addProp("flowRate", new Int16Prop());
            }
        };
        fdmRequest.getProp("key").setValue(key);
        fdmRequest.getProp("extruderId").setValue(extruderIndex);
        fdmRequest.getProp("flowRate").setValue(flowRate);
        return mConnectionController.request(0xac, 0x10, fdmRequest, new ResponseStructure());
    }

    /**
     * 获取 FDM 执行头流量
     * 后续需修改为订阅接口
     *
     * @param id
     * @param extruderIndex
     *
     * @return
     */
    public Observable<ResponseStructure> getFDMFlowRate(int id, int extruderIndex) {
        FDMController fdmController = ServiceContainer.getInstance().getService(IMachine.class).getFDMController();
        int key = fdmController.getModuleIdFromIndex(id);
        BaseStructure fdmRequest = new BaseStructure() {
            @Override
            protected void init() {
                addProp("key", new UInt8Prop());
                addProp("extruderId", new UInt8Prop());
            }
        };
        fdmRequest.getProp("key").setValue(key);
        fdmRequest.getProp("extruderId").setValue(extruderIndex);

        ResponseStructure responseStructure = new ResponseStructure();

        BaseStructure baseStructure = new BaseStructure() {
            @Override
            protected void init() {
                addProp("key", new UInt8Prop());
                addProp("flowRateArray", new ArrayProp<>(new UInt16Prop()));
            }
        };

        responseStructure.resultProp = new UInt8Prop();
        responseStructure.dataProp = baseStructure;
        return mConnectionController.request(0xac, 0x11, fdmRequest, responseStructure);
    }

    public Observable<ResponseStructure> subscribePrintModeStatus() {
        SubscribeStructure subscribeStructure = new SubscribeStructure(0xac, 0xa1, 1000);
        return mConnectionController.request(0x01, 0x00, subscribeStructure, new ResponseStructure())
                .doOnNext(responseStructure -> {
                    if (mPrintModeStatusSubscribe != null && !mPrintModeStatusSubscribe.isDisposed()) {
                        mPrintModeStatusSubscribe.dispose();
                    }
                    mPrintModeStatusSubscribe = mConnectionController.watch(0xac, 0xa1, new ResponseStructure(new UInt8Prop()))
                            .subscribe(responseStructure1 -> {
                                mPrintModeStatusSubject.onNext(((UInt8Prop) responseStructure1.dataProp).getValue());
                            }, LogHelper::log);
                });
    }

    public Observable<ResponseStructure> unsubscribePrintModeStatus() {
        SubscribeStructure subscribeStructure = new SubscribeStructure(0xac, 0xa1, 0);
        return mConnectionController.request(0x01, 0x01, subscribeStructure, new ResponseStructure())
                .doOnNext(responseStructure -> {
                    mConnectionController.unWatch(0xac, 0xa1);
                    if (mPrintModeStatusSubscribe != null && !mPrintModeStatusSubscribe.isDisposed()) {
                        mPrintModeStatusSubscribe.dispose();
                    }
                });
    }

    // J1 需要单独订阅下 风扇更新数据
    public Observable<ResponseStructure> subscribeExtruderWorkSpeedObservable() {
        SubscribeStructure subscribeStructure = new SubscribeStructure(0xac, 0xa2, 1000);
        return mConnectionController.request(0x01, 0x00, subscribeStructure, new ResponseStructure())
                .doOnNext(responseStructure -> {
                    if (responseStructure.isSuccess()) {
                        if (mExtruderWorkSpeedSubscribe != null && !mExtruderWorkSpeedSubscribe.isDisposed()) {
                            mExtruderWorkSpeedSubscribe.dispose();
                        }
                        mExtruderWorkSpeedSubscribe = mConnectionController.watch(0xac, 0xa2, new ResponseStructure<>(new UInt8Prop()))
                                .subscribe(responseStructure1 -> {
                                    mExtruderWorkSpeedSubject.onNext(responseStructure1.dataProp.getValue());
                                }, LogHelper::log);
                    }
                });
    }

    public Observable<ResponseStructure> unSubscribeExtruderWorkSpeedObservable() {
        SubscribeStructure subscribeStructure = new SubscribeStructure(0xac, 0xa2, 0);
        return mConnectionController.request(0x01, 0x01, subscribeStructure, new ResponseStructure())
                .doOnNext(responseStructure -> {
                    mConnectionController.unWatch(0xac, 0xa1);
                    if (mExtruderWorkSpeedSubscribe != null && !mExtruderWorkSpeedSubscribe.isDisposed()) {
                        mExtruderWorkSpeedSubscribe.dispose();
                    }
                });
    }

}

