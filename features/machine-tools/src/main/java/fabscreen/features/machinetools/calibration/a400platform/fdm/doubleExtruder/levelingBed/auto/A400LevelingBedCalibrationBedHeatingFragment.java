package fabscreen.features.machinetools.calibration.a400platform.fdm.doubleExtruder.levelingBed.auto;

import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.orhanobut.logger.Logger;

import butterknife.BindView;
import fabscreen.features.machinetools.R;
import fabscreen.features.machinetools.R2;
import fabscreen.features.machinetools.calibration.A400CalibrationBaseFragment;
import fabscreen.features.machinetools.calibration.a400platform.fdm.doubleExtruder.levelingBed.A400LevelingBedViewModel;
import fabscreen.platform.base.service.machine.entity.module.HeatedBed;
import fabscreen.platform.base.service.machine.entity.parts.Extruder;
import fabscreen.platform.base.service.machine.structure.ResponseStructure;
import fabscreen.platform.base.view.DecisionDialog;
import fabscreen.platform.lib.LogHelper;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;

public class A400LevelingBedCalibrationBedHeatingFragment extends A400CalibrationBaseFragment {

    @BindView(R2.id.iv_a400_leveling_bed_calibration_data)
    TextView mTvHeatingBedTime;
    @BindView(R2.id.tv_nozzle_temp_current_temperature)
    TextView mTvNozzleTempCurrentTemp;
    @BindView(R2.id.tv_nozzle_temp_target_temperature)
    TextView mTvNozzleTempTargetTemp;
    @BindView(R2.id.tv_heated_temp_current_temperature)
    TextView mTvHeatedTempCurrentTemp;
    @BindView(R2.id.tv_heated_temp_target_temperature)
    TextView mTvHeatedTempTargetTemp;
    @BindView(R2.id.top_bar_ico)
    ImageView mIvProProblemIcon;

    Disposable subscribe;
    private A400LevelingBedViewModel mViewModel;
    private int mCalculateHeatingTime;


    public static Fragment newInstance() {
        return new A400LevelingBedCalibrationBedHeatingFragment();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mViewModel = getViewModel();

    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // Taken before anything of this calibration touches the bed, so the user's own pre-heat
        // (or a bed that was simply off) can be handed back untouched when the flow ends.
        mViewModel.snapshotBedState();
        if (mViewModel.wouldAdoptPreheatTemperature()) {
            // The bed is already preheating above the configured temperature: probing at the
            // configured value would mean doing it while the bed is still physically hotter and
            // drifting down, so this is left to the operator instead of silently overridden.
            DecisionDialog.create(getContext())
                    .setTitle(R.string.a400_calibration_heated_bed_preheat_title)
                    .setContent(getString(R.string.a400_calibration_heated_bed_preheat_content))
                    .setType(DecisionDialog.WARMING_TYPE)
                    .setDialogStatus(DecisionDialog.BTN_TWO, true, false, true, true)
                    .setPic(R.drawable.pic_a400_warning_112x112)
                    .setFirstTv(getString(R.string.a400_calibration_heated_bed_preheat_wait_to_cool), R.color.select_dialog_white_txt, (dialog, which) -> {
                        mViewModel.declinePreheatTemperature();
                        dialog.dismiss();
                        startCalibration();
                    })
                    .setSecondTv(getString(R.string.a400_calibration_heated_bed_preheat_use_current), R.color.select_dialog_yellow_txt, (dialog, which) -> {
                        mViewModel.adoptPreheatTemperature();
                        dialog.dismiss();
                        startCalibration();
                    })
                    .show();
        } else {
            startCalibration();
        }
    }

    private void startCalibration() {
        initView();
        mViewModel.checkHome()
                .flatMap(aBoolean -> mViewModel.setCalibrationMode(2))
                .observeOn(AndroidSchedulers.mainThread())
                .as(bindToLifecycle())
                .subscribe(order -> {
                    if (order.isSuccess()) {
                        if (mViewModel.getBedCalibrationBedTemperature() != 0) {
                            mViewModel.startHeating()
                                    .observeOn(AndroidSchedulers.mainThread())
                                    .as(bindToLifecycle())
                                    .subscribe(responseStructure -> {
                                        if (responseStructure.isSuccess()) {
                                            subscribeHeatingResult();
                                        } else {
                                            backOnShow();
                                        }
                                    }, log -> {
                                        LogHelper.log(log);
                                        backOnShow();
                                    });
                        } else {
                            ((A400LevelingBedCalibrationAutoActivity) requireActivity()).gotoLevelingBedCalibrationAuto();
                        }
                    } else {
                        Logger.e(order.toString());
                        requireActivity().finish();
                    }
                }, LogHelper::log);
    }

    private void subscribeHeatingResult() {
        subscribe = mViewModel.todoNext()
                .distinctUntilChanged()
                .observeOn(AndroidSchedulers.mainThread())
                .as(bindToLifecycle())
                .subscribe(bool -> {
                    try {
                        if (!fabBackConfirm.mCancelBtn.isEnabled()) return;
                    } catch (Exception ignored) {

                    }
                    if (bool) {
                        if (fabBackConfirm != null && fabBackConfirm.isShowing())
                            fabBackConfirm.dismiss();
                        ((A400LevelingBedCalibrationAutoActivity) requireActivity()).gotoLevelingBedCalibrationAuto();
                    }
                }, LogHelper::log);
    }

    private void initView() {
        mIvProProblemIcon.setVisibility(View.GONE);
        setTitle(R.string.calibration_heated_bed_leveling_title);
        setContent(R.string.a400_calibration_heated_bed_leveling_content_1);
        mGuideProgressBar.setMax(2);
        mGuideProgressBar.setProgress(1);
        mGuideProgressBar.invalidate();
        mGuideProgressBar.setVisibility(View.VISIBLE);
        mCalculateHeatingTime = mViewModel.CalculateHeatingTime();
        mViewModel.getExtruderChangeObservable()
                .observeOn(AndroidSchedulers.mainThread())
                .as(bindToLifecycle())
                .subscribe(fdmToolheadStatus -> {
                    Extruder extruder = fdmToolheadStatus.getExtruderList().get(0);
                    mTvNozzleTempCurrentTemp.setText(String.valueOf((int) extruder.getTemperature()));
                    mTvNozzleTempTargetTemp.setText(getString(R.string.a400_calibration_leveling_bed_heating_temperature_format, (int) extruder.getTargetTemperature()));
                }, LogHelper::log);
        mViewModel.getBedChangeObservable()
                .observeOn(AndroidSchedulers.mainThread())
                .as(bindToLifecycle())
                .subscribe(bedStatus -> {
                    HeatedBed.ZoneInfo zoneInfo = bedStatus.getZoneList().get(0);
                    mTvHeatedTempCurrentTemp.setText(String.valueOf((int) zoneInfo.getCurrentTemperature()));
                    mTvHeatedTempTargetTemp.setText(getString(R.string.a400_calibration_leveling_bed_heating_temperature_format, (int) zoneInfo.getTargetTemperature()));
                }, LogHelper::log);
//        mViewModel.updateHeatedTime()
//                .observeOn(AndroidSchedulers.mainThread())
//                .as(bindToLifecycle())
//                .subscribe(times -> mTvHeatingBedTime.setText(getString(R.string.a400_leveling_bed_calibration_heat_bed, mViewModel.getWholeCalculateTime(), times)), LogHelper::log);
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.fragment_a400_leveling_bed_calibration_heating;
    }

    /** Bed leveling heats the bed itself, so leaving it must hand the previous state back. */
    @Override
    protected Observable<ResponseStructure> applyBedStateOnExit() {
        return mViewModel.restoreBedState();
    }

    @Override
    protected A400LevelingBedViewModel getViewModel() {
        return getViewModelProvider().get(A400LevelingBedViewModel.class);
    }

    @Override
    public void onResume() {
        super.onResume();
        mViewModel.subscribeTemperatureChange();
        if ((subscribe != null && subscribe.isDisposed()) && mViewModel.getBedCalibrationBedTemperature() != 0) {
            subscribeHeatingResult();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        mViewModel.unsubscribeTemperatureChange();
        if (subscribe != null) subscribe.dispose();
    }
}
