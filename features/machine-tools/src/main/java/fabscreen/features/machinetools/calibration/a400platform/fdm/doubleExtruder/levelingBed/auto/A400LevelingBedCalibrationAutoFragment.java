package fabscreen.features.machinetools.calibration.a400platform.fdm.doubleExtruder.levelingBed.auto;

import android.app.Activity;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.orhanobut.logger.Logger;

import java.util.ArrayList;

import butterknife.BindView;
import fabscreen.features.machinetools.R;
import fabscreen.features.machinetools.R2;
import fabscreen.features.machinetools.calibration.A400CalibrationBaseFragment;
import fabscreen.features.machinetools.calibration.a400platform.fdm.doubleExtruder.levelingBed.A400LevelingBedViewModel;
import fabscreen.platform.base.instantiation.ServiceContainer;
import fabscreen.platform.base.service.IMachine;
import fabscreen.platform.base.service.IPreferences;
import fabscreen.platform.base.service.machine.structure.ResponseStructure;
import fabscreen.platform.base.view.DecisionDialog;
import fabscreen.platform.core.ui.view.ChessboardView;
import fabscreen.platform.lib.LogHelper;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;

public class A400LevelingBedCalibrationAutoFragment extends A400CalibrationBaseFragment {

    @BindView(R2.id.tv_a400_leveling_bed_calibration_auto_title)
    TextView mTvAutoTitle;
    @BindView(R2.id.tv_a400_leveling_bed_calibration_auto_content)
    TextView mTvAutoContent;
    @BindView(R2.id.cv_a400_leveling_bed_calibration_grid)
    ChessboardView mCVCalibrationGrid;
    @BindView(R2.id.top_bar_ico)
    ImageView mIvProProblemIcon;

    int gridCount;
    private A400LevelingBedViewModel mViewModel;
    private int mLastPoint = 0;
    private IPreferences.Helper mPrefHelper;
    private boolean isFinish;
    /** Whether exitCalibration already succeeded, so a retry only redoes the bed restore. */
    private boolean mCalibrationExited;

    public static Fragment newInstance() {
        return new A400LevelingBedCalibrationAutoFragment();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mViewModel = getViewModel();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mIvProProblemIcon.setVisibility(View.GONE);
        gridCount = mViewModel.getGridCount();
        mPrefHelper = ServiceContainer.getInstance().getService(IPreferences.class).getHelper();
        initView();
        mViewModel.startCalibration()
                .as(bindToLifecycle())
                .subscribe(order -> {
                }, LogHelper::log);
    }

    private void initView() {
        setTitle(R.string.calibration_heated_bed_leveling_title);
//        mTvAutoContent.setText(getText(R.string.a400_calibration_headted_bed_leveing_wait_time));
        long machineSn = mPrefHelper.getA400MachineSn();
        if (mPrefHelper.getA400MachineStep(machineSn) < 3) {
            setContent(R.string.a400_calibration_heated_bed_leveling_content_3);
        } else {
            setContent(R.string.a400_calibration_heated_bed_leveling_content_2);
        }
        mGuideProgressBar.setMax(2);
        mGuideProgressBar.setProgress(2);
        mGuideProgressBar.invalidate();
        mGuideProgressBar.setVisibility(View.VISIBLE);
        mTvAutoTitle.setText(getString(R.string.a400_calibration_headted_bed_leveling_guide, 0, gridCount * gridCount));
        int gridCount = mViewModel.getGridCount();
        ArrayList<ChessboardView.ProcessedPiece> processedPieces = new ArrayList<>();
        for (int i = 0; i < gridCount * gridCount; i++) {
            processedPieces.add(new ChessboardView.ProcessedPiece());
        }
        processedPieces.get(0).processedPieceState = ChessboardView.ProcessedPieceState.PROCESSING;
        mLastPoint = 0;
        mCVCalibrationGrid.setData(gridCount, gridCount, processedPieces);
        mViewModel.getCalibrationObservable()
                .distinctUntilChanged()
                .observeOn(AndroidSchedulers.mainThread())
                .as(bindToLifecycle())
                .subscribe(pointIndex -> {
                    int viewOrder = mViewModel.getViewOrder(pointIndex);
                    if (viewOrder < this.gridCount * this.gridCount) {
                        mCVCalibrationGrid.setChangPieceState(mViewModel.getCoordinateOrder(pointIndex), ChessboardView.ProcessedPieceState.PROCESSED, mViewModel.getCoordinateOrderByViewOrder(viewOrder + 1), ChessboardView.ProcessedPieceState.PROCESSING);
                    }
                    mTvAutoTitle.setText(getString(R.string.a400_calibration_headted_bed_leveling_guide, viewOrder, this.gridCount * this.gridCount));
//                    mTvAutoContent.setText(getString(R.string.a400_calibration_headted_bed_leveing_time, mViewModel.getWholeCalculateTime(), Math.abs(this.gridCount * this.gridCount - viewOrder) * mViewModel.LEVELING_POINT_TIME));

                    if (viewOrder == this.gridCount * this.gridCount) {
                        saveCalibration();
                    }
                });
    }

    private void saveCalibration() {
        if (isFinish) return;
        isFinish = true;
        // A retry after a failed bed restore must not send exitCalibration again: the calibration
        // has already been left, and a second exit would answer with an error of its own.
        Observable<ResponseStructure> exit = mCalibrationExited
                ? Observable.just(new ResponseStructure())
                : ServiceContainer.getInstance().getService(IMachine.class).getFDMController()
                .exitCalibration(true)
                .doOnNext(responseStructure -> mCalibrationExited = responseStructure.isSuccess());
        exit
                .flatMap(responseStructure -> responseStructure.isSuccess() ? applyBedStateOnExit() : Observable.just(responseStructure))
                .observeOn(AndroidSchedulers.mainThread())
                .as(bindToLifecycle())
                .subscribe(response -> {
                    isFinish = false;
                    if (response.isSuccess()) {
                        finishActivityWithResultOk();
                    } else {
                        // mCalibrationExited tells the two failures apart: it is only set once the
                        // machine has confirmed the exit, so a failure while it is still false came
                        // from exitCalibration itself and the result was never saved.
                        reportSaveFailure(response);
                    }
                }, throwable -> {
                    isFinish = false;
                    LogHelper.log(throwable);
                    reportSaveFailure(null);
                });
    }

    /** Shows the dialog matching whichever half of the completion path failed. */
    private void reportSaveFailure(@Nullable ResponseStructure response) {
        if (!mCalibrationExited) {
            if (response != null) Logger.e("Exit Calibration: " + response);
            showExitFailedDialog();
        } else {
            if (response != null) Logger.e("Restore Heated Bed: " + response);
            showRestoreFailedDialog();
        }
    }

    /**
     * Saving and leaving the calibration was never confirmed by the machine, so the calibration is
     * still running and its result has not been stored. Closing must therefore report
     * {@link Activity#RESULT_CANCELED}: returning RESULT_OK here would tell the caller the bed was
     * levelled when the firmware never said so.
     */
    private void showExitFailedDialog() {
        DecisionDialog.create(getContext())
                .setTitle(R.string.a400_calibration_exit_failed_title)
                .setContent(getString(R.string.a400_calibration_exit_failed_content))
                .setType(DecisionDialog.WARMING_TYPE)
                .setDialogStatus(DecisionDialog.BTN_TWO, true, false, true, true)
                .setPic(R.drawable.pic_a400_warning_112x112)
                .setFirstTv(getString(R.string.all_close), R.color.select_dialog_white_txt, (dialog, which) -> {
                    dialog.dismiss();
                    finishActivityWithResultCanceled();
                })
                .setSecondTv(getString(R.string.all_retry), R.color.select_dialog_yellow_txt, (dialog, which) -> {
                    dialog.dismiss();
                    saveCalibration();
                })
                .show();
    }

    /**
     * The calibration itself is done and saved, but the heated bed was not handed back to the state
     * it was in before. Leaving silently would strand the bed on the calibration target, so the
     * operator is told and can retry; the snapshot is kept until a restore succeeds, so the retry is
     * meaningful. Closing still reports success, because the calibration really did save.
     */
    private void showRestoreFailedDialog() {
        DecisionDialog.create(getContext())
                .setTitle(R.string.a400_calibration_heated_bed_restore_failed_title)
                .setContent(getString(R.string.a400_calibration_heated_bed_restore_failed_content))
                .setType(DecisionDialog.WARMING_TYPE)
                .setDialogStatus(DecisionDialog.BTN_TWO, true, false, true, true)
                .setPic(R.drawable.pic_a400_warning_112x112)
                .setFirstTv(getString(R.string.all_close), R.color.select_dialog_white_txt, (dialog, which) -> {
                    dialog.dismiss();
                    finishActivityWithResultOk();
                })
                .setSecondTv(getString(R.string.all_retry), R.color.select_dialog_yellow_txt, (dialog, which) -> {
                    dialog.dismiss();
                    saveCalibration();
                })
                .show();
    }

    private void finishActivityWithResultCanceled() {
        requireActivity().setResult(Activity.RESULT_CANCELED);
        requireActivity().finish();
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.fragment_a400_leveling_bed_calibration_auto;
    }

    @Override
    protected A400LevelingBedViewModel getViewModel() {
        return getViewModelProvider().get(A400LevelingBedViewModel.class);
    }

    /** Bed leveling heats the bed itself, so leaving it must hand the previous state back. */
    @Override
    protected Observable<ResponseStructure> applyBedStateOnExit() {
        return mViewModel.restoreBedState();
    }

    @Override
    protected void back() {
        fabBackConfirm = DecisionDialog.create(getContext())
                .setTitle(R.string.a400_calibration_stop_calibration)
                .setContent(getString(R.string.a400_calibration_assistant_back_notice, getString(R.string.calibration_heated_bed_leveling_title)))
                .setType(DecisionDialog.WARMING_TYPE)
                .setDialogStatus(DecisionDialog.BTN_TWO, true, false, true, true)
                .setPic(R.drawable.pic_a400_warning_112x112)
                .setFirstTv(getContext().getResources().getString(R.string.all_cancel), R.color.select_dialog_white_txt, ((dialog, which) -> dialog.dismiss()))
                .setSecondTv(getContext().getResources().getString(R.string.all_stop), R.color.select_dialog_yellow_txt, ((dialog, which) -> {
                    // Checked before greying the buttons out: if the last point completed just as
                    // "Stop" was tapped, stopCalibration() declines and the buttons would otherwise
                    // stay disabled for good.
                    if (isFinish) return;
                    fabBackConfirm.mCancelBtn.setEnabled(false);
                    fabBackConfirm.mSecondBtn.setEnabled(false);
                    stopCalibration(dialog);
                }));
        fabBackConfirm.show();
    }

    /**
     * Leaves the calibration at the operator's request: interrupt the leveling, exit the
     * calibration and hand the heated bed back to the state it was in before.
     *
     * <p>As on the completion path, a retry after a failed bed restore must only redo the restore:
     * the leveling has already been interrupted and the calibration already left, and repeating
     * either would answer with an error of its own.
     *
     * @param dialog the dialog the request came from; dismissed once the outcome is known, so the
     *               operator is never left in front of a dialog with both buttons greyed out.
     */
    private void stopCalibration(DialogInterface dialog) {
        // The retry dialogs keep their buttons enabled while the request runs, so guard against a
        // second tap firing the sequence twice. Shared with saveCalibration(): both mean "an exit
        // sequence is already in flight".
        if (isFinish) return;
        isFinish = true;
        Observable<ResponseStructure> exit = mCalibrationExited
                ? Observable.just(new ResponseStructure())
                : mViewModel.getInterruptAutoLevelingObservable()
                .flatMap(responseStructure -> ServiceContainer.getInstance().getService(IMachine.class).getFDMController().exitCalibration(false))
                .doOnNext(responseStructure -> mCalibrationExited = responseStructure.isSuccess());
        exit
                .flatMap(responseStructure -> responseStructure.isSuccess() ? applyBedStateOnExit() : Observable.just(responseStructure))
                .observeOn(AndroidSchedulers.mainThread())
                .as(bindToLifecycle())
                .subscribe(response -> {
                    isFinish = false;
                    dialog.dismiss();
                    if (response.isSuccess()) {
                        finishActivityWithResultCanceled();
                    } else {
                        reportStopFailure(response);
                    }
                }, throwable -> {
                    isFinish = false;
                    dialog.dismiss();
                    LogHelper.log(throwable);
                    reportStopFailure(null);
                });
    }

    /** Shows the dialog matching whichever half of the stop sequence failed. */
    private void reportStopFailure(@Nullable ResponseStructure response) {
        if (!mCalibrationExited) {
            if (response != null) Logger.e("Exit Calibration: " + response);
            showStopFailedDialog();
        } else {
            if (response != null) Logger.e("Restore Heated Bed: " + response);
            showStopRestoreFailedDialog();
        }
    }

    /**
     * Interrupting or leaving the calibration was never confirmed, so it may still be running on
     * the machine. Staying on the calibration screen is the honest outcome here, which is why
     * "Cancel" simply closes the dialog rather than exiting.
     */
    private void showStopFailedDialog() {
        DecisionDialog.create(getContext())
                .setTitle(R.string.a400_calibration_stop_failed_title)
                .setContent(getString(R.string.a400_calibration_stop_failed_content))
                .setType(DecisionDialog.WARMING_TYPE)
                .setDialogStatus(DecisionDialog.BTN_TWO, true, false, true, true)
                .setPic(R.drawable.pic_a400_warning_112x112)
                .setFirstTv(getString(R.string.all_cancel), R.color.select_dialog_white_txt, (dialog, which) -> dialog.dismiss())
                .setSecondTv(getString(R.string.all_retry), R.color.select_dialog_yellow_txt, (dialog, which) -> stopCalibration(dialog))
                .show();
    }

    /**
     * The calibration has been left, but the heated bed was not handed back to its previous state.
     * Unlike {@link #showStopFailedDialog()} there is nothing left to return to — the calibration
     * is over on the machine — so "Close" leaves the screen instead of returning to a dead one.
     */
    private void showStopRestoreFailedDialog() {
        DecisionDialog.create(getContext())
                .setTitle(R.string.a400_calibration_heated_bed_restore_failed_title)
                .setContent(getString(R.string.a400_calibration_heated_bed_restore_failed_stopped_content))
                .setType(DecisionDialog.WARMING_TYPE)
                .setDialogStatus(DecisionDialog.BTN_TWO, true, false, true, true)
                .setPic(R.drawable.pic_a400_warning_112x112)
                .setFirstTv(getString(R.string.all_close), R.color.select_dialog_white_txt, (dialog, which) -> {
                    dialog.dismiss();
                    finishActivityWithResultCanceled();
                })
                .setSecondTv(getString(R.string.all_retry), R.color.select_dialog_yellow_txt, (dialog, which) -> stopCalibration(dialog))
                .show();
    }
}
