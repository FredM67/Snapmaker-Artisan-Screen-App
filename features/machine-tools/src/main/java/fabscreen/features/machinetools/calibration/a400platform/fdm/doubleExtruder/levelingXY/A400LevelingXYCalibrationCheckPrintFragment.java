package fabscreen.features.machinetools.calibration.a400platform.fdm.doubleExtruder.levelingXY;

import static android.app.Activity.RESULT_FIRST_USER;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.orhanobut.logger.Logger;

import butterknife.BindView;
import butterknife.OnClick;
import fabscreen.features.machinetools.R;
import fabscreen.features.machinetools.R2;
import fabscreen.features.machinetools.calibration.A400CalibrationBaseFragment;
import fabscreen.platform.base.helper.StringToValueUtils;
import fabscreen.platform.base.instantiation.ServiceContainer;
import fabscreen.platform.base.service.IAppService;
import fabscreen.platform.base.service.IRouter;
import fabscreen.platform.base.service.machine.controller.PrintEvent;
import fabscreen.platform.base.service.machine.controller.PrintEventState;
import fabscreen.platform.base.service.machine.entity.parts.Extruder;
import fabscreen.platform.base.view.DecisionDialog;
import fabscreen.platform.core.ui.data.FilamentState;
import fabscreen.platform.core.ui.view.FileParsingDialog;
import fabscreen.platform.core.ui.view.PrintDetailCard;
import fabscreen.platform.core.ui.view.StepIntroductionDialog;
import fabscreen.platform.core.ui.view.customkeyboard.CustomKeyboardUtil;
import fabscreen.platform.lib.LogHelper;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;

public class A400LevelingXYCalibrationCheckPrintFragment extends A400CalibrationBaseFragment {

    /** XY calibration prints a part, so the bed is cooled down to let the user take it off. */
    @Override
    protected boolean shouldCoolDownBedOnExit() {
        return true;
    }

    @BindView(R2.id.iv_print_file_diagram)
    ImageView mIvPrintFileDiagram;
    @BindView(R2.id.iv_print_base_show)
    ImageView mIvPrintBaseShow;
    @BindView(R2.id.tv_a400_print_filename)
    TextView mTvFilename;
    @BindView(R2.id.tv_print_remaining_time)
    TextView mTvRemainingTime;
    @BindView(R2.id.tv_a400_print_progress)
    TextView mTvPrintProgress;
    @BindView(R2.id.pb_print_progress)
    CircularProgressIndicator mPvPrintProgress;
    @BindView(R2.id.btn_a400_print_stop)
    ImageView mBtnStop;
    @BindView(R2.id.li_print_detail)
    LinearLayout mLiPrintDetail;
    @BindView(R2.id.top_bar_title)
    TextView mTvTitle;
    @BindView(R2.id.top_bar_content)
    TextView mTvSubTitle;
    @BindView(R2.id.top_bar)
    RelativeLayout mTopBar;

    private IRouter mRouter;
    Disposable mDisCheckoutExtruderTemperature;
    Disposable mDisCheckFilamentProcess;
    public FileParsingDialog fabWorkingChangeDialog;
    private DecisionDialog mQuitDialog;
    StepIntroductionDialog checkFilamentProcessTipDialog;
    DecisionDialog mDecisionDialog;
    Disposable subscribe;
    private A400LevelingXYCalibrationCheckPrintViewModel mViewModel;
    private CustomKeyboardUtil mCustomKeyboardUtil;
    private boolean isStateFail = false;
    private boolean isDoubleExtruder = false;
    private DecisionDialog mIsShowFilamentDialog;
    private boolean isStop;

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mRouter = ServiceContainer.getInstance().getService(IRouter.class);
        mViewModel = getFragmentScopeViewModel(A400LevelingXYCalibrationCheckPrintViewModel.class);
        mCustomKeyboardUtil = new CustomKeyboardUtil(requireContext());
        isDoubleExtruder = mViewModel.isDoubleExtruder();
        initView();
        mViewModel.getFileParserObservable()
                .observeOn(AndroidSchedulers.mainThread())
                .as(bindToLifecycle())
                .subscribe(aBoolean -> {
                    mTvFilename.setText(mViewModel.getFileName());
                    mIvPrintFileDiagram.setImageBitmap(mViewModel.getGcodeThumbnail());
                }, LogHelper::log);

        subscribe = mViewModel.init()
                .distinctUntilChanged()
                .observeOn(AndroidSchedulers.mainThread())
                .as(bindToLifecycle())
                .subscribe(xyCheckPrintState -> {
                    switch (xyCheckPrintState) {
                        case READY_SUCCESS:
                            getPrintControllerCallback();
                            initPrint();
                            subscribe.dispose();
                            break;
                        default:
                            Logger.w("An error occurred: " + xyCheckPrintState);
                            coolAndFinish();
                            break;
                    }
                }, log -> {
                    LogHelper.log(log);
                    coolAndFinish();
                });

        mQuitDialog = DecisionDialog.create(getActivity())
                .setDialogStatus(DecisionDialog.BTN_TWO, true, false, false, true)
                .setType(DecisionDialog.WARMING_TYPE)
                .setPic(R.drawable.pic_a400_warning_112x112)
                .setTitle(R.string.a400_calibration_stop_calibration)
                .setContent(getString(R.string.a400_calibration_assistant_back_notice, getString(R.string.calibration_a400_leveling_xy_title)))
                .setFirstTv(getContext().getResources().getString(R.string.all_cancel), R.color.select_dialog_white_txt, ((dialog, which) -> {
                    dialog.dismiss();
                }))
                .setSecondTv(getContext().getResources().getString(R.string.all_stop), R.color.select_dialog_yellow_txt, (dialog, which) -> {
                    mQuitDialog.mCancelBtn.setEnabled(false);
                    mQuitDialog.mSecondBtn.setEnabled(false);
                    if (mViewModel.isPrinting()) {
                        mViewModel.requestMachineStop();
                    } else if (mViewModel.isCalibrationMode()) {
                        mViewModel.exitCalibrationMode()
                                .observeOn(AndroidSchedulers.mainThread())
                                .as(bindToLifecycle())
                                .subscribe(responseStructure -> {
                                    mQuitDialog.dismiss();
                                    if (getActivity() instanceof A400LevelingXYCalibrationActivity) {
                                        ((A400LevelingXYCalibrationActivity) getActivity()).setCancelResult();
                                    }
                                }, LogHelper::log);
                    } else {
                        mQuitDialog.dismiss();
                        coolAndFinish();
                    }
                });
    }

    public int getInputValue(int value, int min, int max) {
        if (value < min) {
            return min;
        } else if (value > max) {
            return max;
        } else {
            return value;
        }
    }

    void init3DPPanel() {
        int width = 24;
        GradientDrawable drawable = new GradientDrawable();
        drawable.setSize(width, 1);
        mLiPrintDetail.setDividerDrawable(drawable);
        mLiPrintDetail.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);

        mViewModel.setFilamentState(mViewModel.getFilamentStateValue().setExtruderNum(2));
        // Init FDM Left Extruder (as No.0 Extruder) Card.
        PrintDetailCard ExtruderLeftCard = new PrintDetailCard(getContext())
                .setIcon(R.drawable.icon_nozzle_left_gray_64x64)
                .setDetailsName(R.string.all_left_nozzle_temp_abbr);
        ExtruderLeftCard.setClickable(true);
        mCustomKeyboardUtil.bindKeyboardListener(ExtruderLeftCard, new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {

            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {

            }

            @Override
            public void afterTextChanged(Editable s) {
                if (!TextUtils.isEmpty(s.toString())) {
                    int temperature = getInputValue(StringToValueUtils.parseInt(s.toString()), 0, isDoubleExtruder ? 300 : 275);
                    mViewModel.setExtruderTemperature(0, 0, temperature)
                            .observeOn(AndroidSchedulers.mainThread())
                            .as(bindToLifecycle())
                            .subscribe(responseStructure -> {
                            }, LogHelper::log);
                }
            }
        });
        ExtruderLeftCard.setOnClickListener(v -> mCustomKeyboardUtil.showKeyboard(v, CustomKeyboardUtil.INPUT_TYPE_NUMBER_DECIMAL));
        mLiPrintDetail.addView(ExtruderLeftCard);


        // Init FDM Right Extruder (as No.1 Extruder) Card.
        // Only Init when right extruder exists(as it works with FDM Dual Extruder).
        PrintDetailCard extruderRightCard = new PrintDetailCard(getContext())
                .setIcon(R.drawable.icon_nozzle_right_gray_64x64)
                .setDetailsName(R.string.all_right_nozzle_temp_abbr);
        extruderRightCard.setClickable(true);
        mCustomKeyboardUtil.bindKeyboardListener(extruderRightCard, new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {

            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {

            }

            @Override
            public void afterTextChanged(Editable s) {
                if (!TextUtils.isEmpty(s.toString())) {
                    int temperature = getInputValue(StringToValueUtils.parseInt(s.toString()), 0, isDoubleExtruder ? 300 : 275);
                    mViewModel.setExtruderTemperature(0, 1, temperature)
                            .observeOn(AndroidSchedulers.mainThread())
                            .as(bindToLifecycle())
                            .subscribe(responseStructure -> {
                            }, LogHelper::log);
                }
            }
        });
        extruderRightCard.setOnClickListener(v -> mCustomKeyboardUtil.showKeyboard(v, CustomKeyboardUtil.INPUT_TYPE_NUMBER_DECIMAL));
        mLiPrintDetail.addView(extruderRightCard);

        // Subscribe FDM extruder data, including extruder(s) temperature info, filament sensor state.
        mViewModel.getToolheadStatusSubjectHolder(0)
                .getObservable()
                .observeOn(AndroidSchedulers.mainThread())
                .as(bindToLifecycle())
                .subscribe(fdmToolheadStatus -> {
                    Extruder leftExtruder = fdmToolheadStatus.getExtruderList().get(0);
                    float leftExtruderTemp = leftExtruder.getTemperature();
                    float leftExtruderTargetTemp = leftExtruder.getTargetTemperature();
                    boolean leftExtruderFilamentStatus = leftExtruder.getFilamentStatus();
                    boolean hasMultipleExtruders = fdmToolheadStatus.getExtruderList().size() > 1;
                    ExtruderLeftCard.setDetailsCurrentValue((int) leftExtruderTemp);
                    ExtruderLeftCard.setDetailsTargetValue((int) leftExtruderTargetTemp);
                    ExtruderLeftCard.setProgressValue((int) (leftExtruderTemp / (hasMultipleExtruders ? 300f : 275f) * 100));

                    mViewModel.setFilamentState(mViewModel.getFilamentStateValue().setFilamentState(0,
                            leftExtruderFilamentStatus,
                            leftExtruderTargetTemp,
                            leftExtruderTargetTemp != 0 && leftExtruderTargetTemp - 5 <= leftExtruderTemp,
                            leftExtruder.getState() == 1
                    ));

                    // If FDM ToolHead has two or more extruders
                    if (hasMultipleExtruders) {
                        Extruder rightExtruder = fdmToolheadStatus.getExtruderList().get(1);
                        float rightExtruderTemp = rightExtruder.getTemperature();
                        float rightExtruderTargetTemp = rightExtruder.getTargetTemperature();
                        boolean rightExtruderFilamentStatus = rightExtruder.getFilamentStatus();
                        extruderRightCard.setDetailsCurrentValue((int) rightExtruderTemp);
                        extruderRightCard.setDetailsTargetValue((int) rightExtruderTargetTemp);
                        extruderRightCard.setProgressValue((int) (rightExtruderTemp / (fdmToolheadStatus.getExtruderList().size() > 1 ? 300f : 275f) * 100));

                        mViewModel.setFilamentState(mViewModel.getFilamentStateValue().setFilamentState(1,
                                rightExtruderFilamentStatus,
                                rightExtruderTargetTemp,
                                rightExtruderTargetTemp != 0 && rightExtruderTargetTemp - 5 <= rightExtruderTemp,
                                rightExtruder.getState() == 1));
                    }
                }, LogHelper::log);

        PrintDetailCard extruderBedCard = new PrintDetailCard(getContext())
                .setIcon(R.drawable.icon_heated_bed_gray_64x64)
                .setDetailsName(R.string.print_heated_bed_temp);
        extruderBedCard.setClickable(true);

        mCustomKeyboardUtil.bindKeyboardListener(extruderBedCard, new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {

            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {

            }

            @Override
            public void afterTextChanged(Editable s) {
                if (!TextUtils.isEmpty(s.toString())) {
                    int temperature = getInputValue(StringToValueUtils.parseInt(s.toString()), 0, 110);
                    mViewModel.setAllTargetTemperature(temperature)
                            .observeOn(AndroidSchedulers.mainThread())
                            .as(bindToLifecycle())
                            .subscribe(responseStructure -> {
                            }, LogHelper::log);
                }
            }
        });
        extruderBedCard.setOnClickListener(v -> mCustomKeyboardUtil.showKeyboard(v, CustomKeyboardUtil.INPUT_TYPE_NUMBER_DECIMAL));
        mViewModel.getHeatedBedStatusSubjectHolder()
                .getObservable()
                .observeOn(AndroidSchedulers.mainThread())
                .as(bindToLifecycle())
                .subscribe(heatedBedStatus -> {
                    extruderBedCard.setDetailsCurrentValue((int) heatedBedStatus.getZoneList().get(0).getCurrentTemperature());
                    extruderBedCard.setDetailsTargetValue(heatedBedStatus.getZoneList().get(0).getTargetTemperature());
                    extruderBedCard.setProgressValue((int) ((heatedBedStatus.getZoneList().get(0).getCurrentTemperature() / 110f) * 100));
                }, LogHelper::log);
        mLiPrintDetail.addView(extruderBedCard);

        // Material breaking occurs
        mViewModel.getFilamentSubjectObservable()
                .observeOn(AndroidSchedulers.mainThread())
                .as(bindToLifecycle())
                .subscribe(triggered -> {
                    if (triggered) {
                        if (mIsShowFilamentDialog != null && mIsShowFilamentDialog.isShowing())
                            return;
                        mIsShowFilamentDialog = DecisionDialog.create(getContext())
                                .setTitle(R.string.a400_print_dialog_warning_filament_run_out_title)
                                .setType(DecisionDialog.WARMING_TYPE)
                                .setContent(getString(R.string.a400_print_dialog_warning_filament_run_out_triggered_content,
                                        mViewModel.getFilamentStateValue().getFailureFilamentIndex() == 0 ?
                                                getString(R.string.all_left_extruder_abbr) :
                                                getString(R.string.all_right_extruder_abbr)))
                                .setDialogStatus(DecisionDialog.BTN_TWO, true, false, true, true)
                                .setPic(R.drawable.pic_a400_warning_112x112)
                                .setFirstTv(getString(R.string.all_cancel), R.color.select_dialog_white_txt, ((dialog, which) -> {
                                    mViewModel.setFilament(true);
                                    dialog.dismiss();
                                }))
                                .setSecondTv(getString(R.string.a400_print_dialog_warning_filament_run_out_action_load_filament), R.color.select_dialog_yellow_txt, (dialog, which) -> {
                                    dialog.dismiss();
                                    enterFilamentRefillProcedure();
                                });
                        mIsShowFilamentDialog.show();
                    }
                });
    }

    // filament refill procedure
    // 1. Checkout which extruder should refill filament;
    // 2. Checkout extruder temperature, heated up if target is 0;
    // 3. Checkout filament sensor is on or off, start filling filament process;
    // 4. extrude a certain length filament and ask user if ready. If not, continue extruding;
    // 5. user click continue printing, leave refill procedure and resume print.
    void enterFilamentRefillProcedure() {
        if (mDisCheckoutExtruderTemperature != null && !mDisCheckoutExtruderTemperature.isDisposed()) {
            mDisCheckoutExtruderTemperature.dispose();
        }
        FileParsingDialog loadingDialog = FileParsingDialog.create(requireContext()).setContent(R.string.a400_print_dialog_warning_filament_run_out_action_heating_nozzle);
        loadingDialog.show();
        mDisCheckoutExtruderTemperature = mViewModel.checkoutExtruderTemperature()
                .observeOn(AndroidSchedulers.mainThread())
                .as(bindToLifecycle())
                .subscribe(filamentState -> {
                    if (filamentState.isTemperatureReached()) {
                        loadingDialog.dismiss();
                        CheckFilamentProcess();
                    }
                }, LogHelper::log);
    }

    private void CheckFilamentProcess() {
        if (mDisCheckoutExtruderTemperature != null && !mDisCheckoutExtruderTemperature.isDisposed()) {
            mDisCheckoutExtruderTemperature.dispose();
        }
        if (mDisCheckFilamentProcess != null && !mDisCheckFilamentProcess.isDisposed()) {
            mDisCheckFilamentProcess.dispose();
        }

        FilamentState mFilamentState = mViewModel.getFilamentStateValue();
        if (checkFilamentProcessTipDialog != null) {
            checkFilamentProcessTipDialog.dismiss();
        }
        checkFilamentProcessTipDialog = StepIntroductionDialog.create(requireContext());
        checkFilamentProcessTipDialog.setCanceledOnTouchOutSide(false);
        checkFilamentProcessTipDialog.setOnClickBack(v -> {
            checkFilamentProcessTipDialog.dismiss();
            mViewModel.setFilament(true);
            mDisCheckoutExtruderTemperature.dispose();
        });
        if (!mFilamentState.getNowFilamentState()) {
            checkFilamentProcessTipDialog.setImage(R.drawable.pic_a400_3dp_double_extruder_pluck_ilament_644x362);
            checkFilamentProcessTipDialog.setTitle(R.string.a400_print_dialog_warning_filament_run_out_pull_out_filament_title);
            checkFilamentProcessTipDialog.setContent(R.string.a400_print_dialog_warning_filament_run_out_pull_out_filament_content);
            checkFilamentProcessTipDialog.show();
            mDisCheckFilamentProcess = mViewModel.getFilamentStateObservable()
                    .as(bindToLifecycle())
                    .subscribe(filamentState -> {
                        if (mFilamentState.getNowFilamentState()) {
                            checkFilamentProcessTipDialog.dismiss();
                            CheckFilamentProcess();
                        }
                    }, LogHelper::log);
        } else {
            checkFilamentProcessTipDialog.setImage(R.drawable.pic_a400_3dp_double_extruder_insert_ilament_1065x388);
            checkFilamentProcessTipDialog.setTitle(R.string.a400_print_dialog_warning_filament_run_out_insert_filament_title);
            checkFilamentProcessTipDialog.setContent(R.string.a400_print_dialog_warning_filament_run_out_insert_filament_content);
            checkFilamentProcessTipDialog.show();
            mDisCheckFilamentProcess = mViewModel.getFilamentStateObservable()
                    .as(bindToLifecycle())
                    .subscribe(filamentState -> {
                        if (!mFilamentState.getNowFilamentState()) {
                            checkFilamentProcessTipDialog.dismiss();
                            fillingFilamentProcess();
                        }
                    }, LogHelper::log);
        }
    }

    private void fillingFilamentProcess() {
        if (mDisCheckFilamentProcess != null && !mDisCheckFilamentProcess.isDisposed()) {
            mDisCheckFilamentProcess.dispose();
        }
        FileParsingDialog loadingDialog = FileParsingDialog.create(requireContext()).setContent(R.string.a400_print_dialog_warning_filament_run_out_action_start_loading);
        loadingDialog.show();
        mViewModel.requestActivatedExtrusion(0, 100, 240, 0, 0)
                .observeOn(AndroidSchedulers.mainThread())
                .as(bindToLifecycle())
                .subscribe(structure -> {
                    loadingDialog.dismiss();
                    if (structure.isSuccess()) {
                        Logger.d("Filament loaded.");
                        checkFilamentComplete();
                    } else {
                        Logger.d("Filament load fail.");
                        showFilamentExtruderFailedDialog(structure.resultProp.getValue());
                    }
                }, e -> {
                    loadingDialog.dismiss();
                    mViewModel.setFilament(true);
                    LogHelper.log(e);
                    showFilamentExtruderFailedDialog(10086);
                });
    }

    void checkFilamentComplete() {
        DecisionDialog.create(getContext())
                .setTitle(R.string.control_load_filament_success)
                .setContent(R.string.control_load_filament_success_content)
                .setType(DecisionDialog.NOTIFICATION_TYPE)
                .setDialogStatus(DecisionDialog.BTN_TWO, true, false, true, false)
                .setPic(R.drawable.pic_a400_success_112x112)
                .setFirstTv(R.string.control_load_filament, R.color.select_dialog_white_txt, ((dialog, which) -> {
                    dialog.dismiss();
                    mViewModel.setFilament(true);
                    fillingFilamentProcess();
                }))
                .setSecondTv(R.string.all_continue_printing, R.color.select_a400_dialog_success_txt, (dialog, which) -> {
                    dialog.dismiss();
                    mViewModel.setFilament(true);
                    if (isStateFail) {
                        mViewModel.startPrint();
                    } else {
                        mViewModel.requestMachineResume();
                    }
                }).show();
    }

    void showFilamentExtruderFailedDialog(int value) {
        DecisionDialog.create(getContext())
                .setContent(String.format("An unknown exception occurred, error code %d", value))
                .setType(DecisionDialog.WARMING_TYPE)
                .setDialogStatus(DecisionDialog.BTN_ONE, true, false, false, true)
                .setPic(R.drawable.pic_a400_warning_112x112)
                .setFirstTv(getString(R.string.all_cancel), R.color.select_dialog_white_txt, ((dialog, which) -> {
                    mViewModel.setFilament(true);
                    dialog.dismiss();
                })).show();
    }

    private void initView() {
        //ui tar bar is transparent
        mTopBar.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.transparent));
        mGuideProgressBar.setMax(6);
        mGuideProgressBar.setVisibility(View.VISIBLE);
        mGuideProgressBar.setProgress(5);
        setTitle(R.string.calibration_a400_leveling_xy_title);
        mTvTopBarContent.setText(R.string.calibration_a400_leveling_xy_print_check_mode);
        mBtnStop.setVisibility(Button.VISIBLE);
        init3DPPanel();
    }

    Disposable mPrintControllerCallbacksub;

    private void getPrintControllerCallback() {
        Disposable subscribe = mViewModel.getPrintEventObservable()
                .observeOn(AndroidSchedulers.mainThread())
                .as(bindToLifecycle())
                .subscribe(this::onPrintEventCallback, LogHelper::log);
        if (!subscribe.isDisposed()) {
            if (mPrintControllerCallbacksub != null && !mPrintControllerCallbacksub.isDisposed()) {
                mPrintControllerCallbacksub.dispose();
            }
            mPrintControllerCallbacksub = subscribe;
        }
    }

    private void onPrintEventCallback(PrintEvent printEvent) {
        DecisionDialog decisionDialog = DecisionDialog.create(getContext());
        int retCode = printEvent.getErrorCode();
        // TODO: Split out into method.
        switch (printEvent.getPrintEventState()) {
            case STATE_SUCCESS:
                // oh we started
                Logger.i("Print started.");
                isStateFail = false;
                break;
            case START_FAIL:
                Logger.w("Unable to start printing, ret code %d", retCode);
                isStateFail = true;
                if (retCode == 17) return;
                if (retCode == 222) {
                    mViewModel.setFilament(false);
                } else {
                    decisionDialog = DecisionDialog.create(getContext())
                            .setDialogStatus(DecisionDialog.BTN_ONE, false, false, false, true)
                            .setContent(retCode == 227 ? getString(R.string.a400_print_enclosure_operation_trigger) : (getString(R.string.print_warning_start_unable) + "\nretCode:" + retCode))
                            .setContentColor(R.color.palette_white_pure)
                            .setType(DecisionDialog.WARMING_TYPE)
                            .setFirstTv(R.string.all_btn_got_it, R.color.select_dialog_blue_txt, ((dialog, which) -> dialog.dismiss()));
                    decisionDialog.show();
                }
                break;
            case PAUSE_SUCCESS:
                Logger.i("Print paused.");
                if (fabWorkingChangeDialog != null && fabWorkingChangeDialog.isShowing())
                    fabWorkingChangeDialog.dismiss();
                break;
            case PAUSE_FAIL:
                Logger.w("Unable to pause printing, ret code %d", retCode);
                if (fabWorkingChangeDialog != null && fabWorkingChangeDialog.isShowing())
                    fabWorkingChangeDialog.dismiss();
                if (retCode == 17) return;
                // Just a confirm
                decisionDialog = DecisionDialog.create(getContext())
                        .setType(DecisionDialog.WARMING_TYPE)
                        .setContentColor(R.color.palette_white_pure)
                        .setDialogStatus(DecisionDialog.BTN_ONE, false, false, false, true)
                        .setFirstTv(R.string.all_btn_got_it, R.color.select_dialog_blue_txt, ((dialog, which) -> dialog.dismiss()));
                switch (retCode) {
                    case 222: {
                        mViewModel.setFilament(false);
                        if (mDecisionDialog != null && mDecisionDialog.isShowing()) {
                            mDecisionDialog.dismiss();
                        }
                        return;
                    }
                    case 227:
                        decisionDialog.setContent(getString(R.string.a400_print_enclosure_operation_trigger));
                        break;
                    default:
                        decisionDialog.setContent(getString(R.string.print_warning_pause_unable) + "\nretCode:" + retCode);
                        break;
                }
                decisionDialog.show();
                break;
            case RESUME_SUCCESS:
                Logger.i("Print resumed.");
                break;
            case RESUME_FAIL:
                Logger.w("Unable to resume printing, ret code %d", retCode);
                if (retCode == 17) return;
                decisionDialog = DecisionDialog.create(getContext())
                        .setType(DecisionDialog.WARMING_TYPE)
                        .setContentColor(R.color.palette_white_pure)
                        .setDialogStatus(DecisionDialog.BTN_ONE, false, false, false, true)
                        .setFirstTv(R.string.all_btn_got_it, R.color.select_dialog_blue_txt, ((dialog, which) -> dialog.dismiss()));
                switch (retCode) {
                    case 222: {
                        mViewModel.setFilament(false);
                        if (mDecisionDialog != null && mDecisionDialog.isShowing()) {
                            mDecisionDialog.dismiss();
                        }
                        return;
                    }
                    case 227: {
                        decisionDialog.setContent(getString(R.string.a400_print_enclosure_operation_trigger));
                        break;
                    }
                    default: {
                        decisionDialog.setContent(getString(R.string.print_warning_resume_unable) + "\nretCode:" + retCode);
                        break;
                    }
                }
                decisionDialog.show();
                break;
            case POWER_LOSS_RESUME_SUCCESS:
                Logger.i("Print recovered.");
                // we resumed from power outage
                mViewModel.setPowerOutageFlag(false);
                break;
            case POWER_LOSS_RESUME_FAIL:
                Logger.w("Failed to recover from power loss, ret code %d", retCode);
                if (retCode == 17) return;
                decisionDialog = DecisionDialog.create(getContext())
                        .setType(DecisionDialog.WARMING_TYPE)
                        .setContentColor(R.color.palette_white_pure)
                        .setDialogStatus(DecisionDialog.BTN_ONE, false, false, false, true)
                        .setFirstTv(R.string.all_btn_got_it, R.color.select_dialog_blue_txt, ((dialog, which) -> dialog.dismiss()));
                switch (retCode) {
                    case 222: {
                        mViewModel.setFilament(false);
                        if (mDecisionDialog != null && mDecisionDialog.isShowing()) {
                            mDecisionDialog.dismiss();
                        }
                        return;
                    }
                    case 227: {
                        //ResumeFromPowerOutage
                        decisionDialog.setContent(getString(R.string.a400_print_enclosure_operation_trigger));
                        break;
                    }
                    default: {
                        decisionDialog.setContent(R.string.print_warning_resume_unable);
                        break;
                    }
                }
                decisionDialog.show();
                break;
            case STOP_SUCCESS:
                if (isStop) return;
                isStop = true;
                if (mQuitDialog != null && mQuitDialog.isShowing()) {
                    mQuitDialog.dismiss();
                    mQuitDialog.mCancelBtn.setEnabled(true);
                    mQuitDialog.mSecondBtn.setEnabled(true);
                }
                Logger.i("print stopped.");
                if (fabWorkingChangeDialog != null && fabWorkingChangeDialog.isShowing())
                    fabWorkingChangeDialog.dismiss();
                if (mViewModel.isCalibrationMode()) {
                    mViewModel.exitCalibrationMode()
                            .observeOn(AndroidSchedulers.mainThread())
                            .as(bindToLifecycle())
                            .subscribe(responseStructure -> {
                                if (getActivity() instanceof A400LevelingXYCalibrationActivity) {
                                    ((A400LevelingXYCalibrationActivity) getActivity()).setCancelResult();
                                }
                            }, LogHelper::log);
                } else {
                    coolAndFinish();
                }
                break;
            case STOP_FAIL:
                if (mQuitDialog != null && mQuitDialog.isShowing()) {
                    mQuitDialog.dismiss();
                    mQuitDialog.mCancelBtn.setEnabled(true);
                    mQuitDialog.mSecondBtn.setEnabled(true);
                }
                Logger.w("Unable to stop printing, ret code %d", retCode);
                if (fabWorkingChangeDialog != null && fabWorkingChangeDialog.isShowing())
                    fabWorkingChangeDialog.dismiss();
                if (retCode == 17) return;
                decisionDialog = DecisionDialog.create(getContext())
                        .setDialogStatus(DecisionDialog.BTN_ONE, false, false, false, true)
                        .setType(DecisionDialog.WARMING_TYPE)
                        .setContentColor(R.color.palette_white_pure)
                        .setContent(R.string.print_warning_stop_unable)
                        .setFirstTv(R.string.all_btn_got_it, R.color.select_dialog_blue_txt, ((dialog, which) -> dialog.dismiss()));
                decisionDialog.show();
                break;
            case FINISH_SUCCESS:
                if (mDecisionDialog != null && mDecisionDialog.isShowing()) {
                    mDecisionDialog.dismiss();
                }
                Logger.i("Print Finished.");
                ((A400LevelingXYCalibrationActivity) requireActivity()).gotoVerifyResults();
                break;
            case FINISH_FAIL:
                Logger.w("Unable to finish printing, ret code %d", retCode);
                if (retCode == 17) return;
                decisionDialog = DecisionDialog.create(getContext())
                        .setDialogStatus(DecisionDialog.BTN_ONE, false, false, false, true)
                        .setType(DecisionDialog.WARMING_TYPE)
                        .setContentColor(R.color.palette_white_pure)
                        .setContent(R.string.print_warning_finish_unable)
                        .setFirstTv(R.string.all_btn_got_it, R.color.select_dialog_blue_txt, ((dialog, which) -> dialog.dismiss()));
                decisionDialog.show();
                break;
            case OPEN_DOOR_PAUSE:
                decisionDialog = DecisionDialog.create(getContext())
                        .setDialogStatus(DecisionDialog.BTN_ONE, false, false, false, true)
                        .setType(DecisionDialog.WARMING_TYPE)
                        .setContentColor(R.color.palette_white_pure)
                        .setContent(getString(R.string.a400_dialog_print_enclosure_open_desc, getString(R.string.calibration_a400_leveling_xy_title)))
                        .setFirstTv(R.string.all_btn_got_it, R.color.select_dialog_blue_txt, ((dialog, which) -> {
                            if (mViewModel.isPrinting()) {
                                mViewModel.requestMachineStop();
                            } else if (mViewModel.isCalibrationMode()) {
                                mViewModel.exitCalibrationMode()
                                        .observeOn(AndroidSchedulers.mainThread())
                                        .as(bindToLifecycle())
                                        .subscribe(responseStructure -> {
                                            dialog.dismiss();
                                            if (getActivity() instanceof A400LevelingXYCalibrationActivity) {
                                                ((A400LevelingXYCalibrationActivity) getActivity()).setCancelResult();
                                            }
                                        }, LogHelper::log);
                            } else {
                                dialog.dismiss();
                                coolAndFinish();
                            }
                        }));
                decisionDialog.show();
                break;
            default:
                break;
        }
        if (decisionDialog != null && decisionDialog.isShowing()) {
            if (mDecisionDialog != null && mDecisionDialog.isShowing()) {
                mDecisionDialog.dismiss();
            }
            mDecisionDialog = decisionDialog;
        }
    }

    void initPrint() {
        mViewModel.initPrint();
        mBtnStop.setVisibility(Button.VISIBLE);
        mViewModel
                .getPrintStateObservable()
                .observeOn(AndroidSchedulers.mainThread())
                .as(bindToLifecycle())
                .subscribe(integer -> {
                }, LogHelper::log);
        // enable or disable buttons
        mViewModel.getWaitingObservable()
                .observeOn(AndroidSchedulers.mainThread())
                .as(bindToLifecycle())
                .subscribe(waiting -> {
                    mBtnStop.setEnabled(!waiting);
                });

        mViewModel.getUpdateProgressObservable()
                .observeOn(AndroidSchedulers.mainThread())
                .as(bindToLifecycle())
                .subscribe(printProgress -> {
                    mTvRemainingTime.setText(getString(R.string.print_remaining_time) + printProgress.formatTime(requireContext()));
                    mTvPrintProgress.setText(printProgress.percentage + "%");
                    mPvPrintProgress.setProgress(printProgress.percentage);
                });
    }

    private void coolAndFinish() {
        mViewModel.cool()
                .observeOn(AndroidSchedulers.mainThread())
                .as(bindToLifecycle())
                .subscribe(responseStructure1 -> {
                    if (getActivity() instanceof A400LevelingXYCalibrationActivity) {
                        ((A400LevelingXYCalibrationActivity) getActivity()).setCancelResult();
                    }
                }, log -> {
                    LogHelper.log(log);
                    if (getActivity() instanceof A400LevelingXYCalibrationActivity) {
                        ((A400LevelingXYCalibrationActivity) getActivity()).setCancelResult();
                    }
                });
    }

    public static String formatTime(double time) {
        int hour = (int) (time) / 3600;
        int minute = ((int) (time) % 3600) / 60;
        int second = ((int) (time) % 60);

        if (hour < 1) {
            return ServiceContainer.getInstance().getService(IAppService.class).getApp().getString(fabscreen.platform.base.R.string.date_helper_format_time_minute_second, minute, second);
        } else {
            return ServiceContainer.getInstance().getService(IAppService.class).getApp().getString(fabscreen.platform.base.R.string.date_helper_format_time_hour_minute, hour, minute);
        }
    }

    @OnClick(R2.id.btn_a400_print_stop)
    void onClickControlStop() {
        playNormalClickSound();
        if (mQuitDialog.isShowing()) {
            return;
        } else {
            mQuitDialog.show();
        }
    }

    public static Fragment newInstance() {
        return new A400LevelingXYCalibrationCheckPrintFragment();
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.fragment_a400_leveling_xy_calibration_print;
    }


    @Override
    protected void back() {
        if (mQuitDialog.isShowing()) return;
        mQuitDialog.show();
    }

    @Override
    public void onResume() {
        super.onResume();
        mViewModel.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
        mViewModel.onPause();
    }

    @OnClick(R2.id.btn_a400_print_setting)
    public void goToSetting() {
        playNormalClickSound();
        mRouter.routeToPrintSetting().startForResult(requireActivity(), 2);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_FIRST_USER) {
            if (requestCode == 2) {
                // update file got, copy and update
                if (data == null) return;
                int printEventState = data.getIntExtra("print_event_state", -1);
                int error_code = data.getIntExtra("error_code", -1);
                if (printEventState == -1) return;
                onPrintEventCallback(new PrintEvent(PrintEventState.values()[printEventState], error_code));
            }
        }
    }
}
