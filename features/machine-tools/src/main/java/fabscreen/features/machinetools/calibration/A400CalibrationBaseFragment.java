package fabscreen.features.machinetools.calibration;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.progressindicator.LinearProgressIndicator;

import butterknife.BindView;
import fabscreen.features.machinetools.R;
import fabscreen.platform.base.R2;
import fabscreen.platform.base.instantiation.ServiceContainer;
import fabscreen.platform.base.service.IMachine;
import fabscreen.platform.base.service.machine.controller.MachineController;
import fabscreen.platform.base.service.machine.structure.ResponseStructure;
import fabscreen.platform.base.service.machine.structure.prop.UInt8Prop;
import fabscreen.platform.base.view.BaseFragment;
import fabscreen.platform.base.view.DecisionDialog;
import fabscreen.platform.core.ui.view.FileParsingDialog;
import fabscreen.platform.lib.LogHelper;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;

public abstract class A400CalibrationBaseFragment extends BaseFragment {
    @Nullable
    @BindView(R2.id.top_bar_content)
    protected TextView mTvTopBarContent;

    @BindView(R2.id.top_bar_title)
    protected TextView mTvTopBarTitle;
    @Nullable
    @BindView(R2.id.top_bar_ico)
    protected ImageView mIvIco;
    @Nullable
    @BindView(R2.id.view_guide_progress_bar)
    protected LinearProgressIndicator mGuideProgressBar;
    public FileParsingDialog fabLoading;
    protected DecisionDialog fabBackConfirm;

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        fabLoading = FileParsingDialog.create(getActivity())
                .setContent(R.string.all_move_show);
    }

    protected String getTitle() {
        if (mTvTopBarTitle != null) {
            return mTvTopBarTitle.getText().toString();
        }
        return "current process";
    }

    @Override
    protected void back() {
        IMachine.WorkType workType = ServiceContainer.getInstance().getService(IMachine.class).getMachineInfoSubjectHolder().getValue().workType;
        fabBackConfirm = DecisionDialog.create(getContext())
                .setType(DecisionDialog.WARMING_TYPE)
                .setDialogStatus(DecisionDialog.BTN_TWO, true, false, true, true)
                .setPic(R.drawable.pic_a400_warning_112x112)
                .setFirstTv(getContext().getResources().getString(R.string.all_cancel), R.color.select_dialog_white_txt, ((dialog, which) -> {
                    dialog.dismiss();
                }))
                .setSecondTv(getContext().getResources().getString(R.string.all_stop), R.color.select_dialog_yellow_txt, ((dialog, which) -> {
                    fabBackConfirm.mCancelBtn.setEnabled(false);
                    fabBackConfirm.mSecondBtn.setEnabled(false);
                    exit()
                            .flatMap(responseStructure -> responseStructure.isSuccess() ? coolDownBedIfHave() : Observable.just(responseStructure))
                            .observeOn(AndroidSchedulers.mainThread())
                            .as(bindToLifecycle())
                            .subscribe(success -> {
                                fabBackConfirm.mCancelBtn.setEnabled(true);
                                fabBackConfirm.mSecondBtn.setEnabled(true);
                                if (success.isSuccess()) {
                                    dialog.dismiss();
                                    // FIXME: 2022/4/19 Cool down shouldn't be done int the base fragment, it's not a common feature.
                                    requireActivity().setResult(Activity.RESULT_CANCELED);
                                    requireActivity().finish();
                                }
                            }, e -> {
                                LogHelper.log(e);
                                fabBackConfirm.mCancelBtn.setEnabled(true);
                                fabBackConfirm.mSecondBtn.setEnabled(true);
                            });
                }));

        switch (workType) {
            case CNC:
                if (getTitle().equals(getString(R.string.a400_manual_tool_title))) {
                    fabBackConfirm.setTitle(getString(R.string.a400_manual_tool_stop_title))
                            .setContent(getString(R.string.a400_calibration_assistant_back_notice, getTitle()));
                } else if (getTitle().equals(getString(R.string.a400_calibration_cnc_tool_change))) {
                    fabBackConfirm.setTitle(getString(R.string.a400_calibration_dialog_stop_title) + getString(R.string.a400_calibration_cnc_tool_change_stop_title))
                            .setContent(getString(R.string.a400_calibration_assistant_back_notice, getString(R.string.a400_calibration_cnc_tool_change_stop_title)));
                } else {
                    fabBackConfirm.setTitle(getString(R.string.a400_calibration_dialog_stop_title) + getString(R.string.calibration_cnc_origin_assistant_stop_title))
                            .setContent(getString(R.string.a400_calibration_assistant_back_notice, getString(R.string.calibration_cnc_origin_assistant_stop_title)));
                }

                break;
            case LASER:
            case FDM:
                fabBackConfirm.setTitle(getString(R.string.a400_calibration_stop_calibration))
                        .setContent(getString(R.string.a400_calibration_assistant_back_notice, getTitle()));
                break;
        }
        fabBackConfirm.show();
    }

    protected void backOnShow() {
        exit()
                .flatMap(responseStructure -> responseStructure.isSuccess() ? coolDownBedIfHave() : Observable.just(responseStructure))
                .observeOn(AndroidSchedulers.mainThread())
                .as(bindToLifecycle())
                .subscribe(success -> {
                    if (success.isSuccess()) {
                        // FIXME: 2022/4/19 Cool down shouldn't be done int the base fragment, it's not a common feature.
                        requireActivity().setResult(Activity.RESULT_CANCELED);
                        requireActivity().finish();
                    }
                }, e -> {
                    LogHelper.log(e);
                });
    }

    protected Observable<ResponseStructure> exit() {
        IMachine.WorkType workType = ServiceContainer.getInstance().getService(IMachine.class).getMachineInfoSubjectHolder().getValue().workType;
        Observable<ResponseStructure> responseStructureObservable = null;
        switch (workType) {
            case FDM:
                responseStructureObservable = ServiceContainer.getInstance().getService(IMachine.class).getFDMController().exitCalibration(false);
                break;
            case LASER:
                responseStructureObservable = ServiceContainer.getInstance().getService(IMachine.class).getLaserController().exitCalibration(false);
                break;
            case CNC:
                responseStructureObservable = ServiceContainer.getInstance().getService(IMachine.class).getCNCController().exitCalibration(false);
                break;
            default:
                ResponseStructure responseStructure = new ResponseStructure();
                responseStructure.resultProp = new UInt8Prop(-1);
                break;
        }
        return responseStructureObservable;
    }

    /**
     * Whether leaving this calibration step should switch the heated bed off.
     * <p>
     * Only calibrations that actually print something (XY calibration) need it, so the user can
     * take the printed parts off a cold bed. Bed leveling and Z-offset calibration must leave the
     * bed as it is, otherwise a bed the user pre-heated on purpose gets silently switched off.
     */
    protected boolean shouldCoolDownBedOnExit() {
        return false;
    }

    protected Observable<ResponseStructure> coolDownBedIfHave() {
        if (!shouldCoolDownBedOnExit())
            return Observable.just(new ResponseStructure());
        if (!IMachine.WorkType.FDM.equals(ServiceContainer.getInstance().getService(IMachine.class).getMachineInfoSubjectHolder().getValue().workType))
            return Observable.just(new ResponseStructure());
        MachineController machineController = ServiceContainer.getInstance().getService(IMachine.class).getMachineController();
        return (machineController.getHeatedBed() != null) ? machineController.getHeatedBed().setAllTargetTemperature(0) : Observable.just(new ResponseStructure());
    }

    protected void setContent(CharSequence title) {
        if (mTvTopBarContent != null) {
            mTvTopBarContent.setText(title);
        }
    }

    protected void setContent(String title) {
        if (mTvTopBarContent != null) {
            mTvTopBarContent.setText(title);
        }
    }

    protected void setContent(int resid) {
        if (mTvTopBarContent != null) {
            mTvTopBarContent.setText(resid);
        }
    }

    protected void setTitle(CharSequence title) {
        if (mTvTopBarTitle != null) {
            mTvTopBarTitle.setText(title);
        }
    }

    protected void setTitle(String title) {
        if (mTvTopBarTitle != null) {
            mTvTopBarTitle.setText(title);
        }
    }

    protected void setTitle(int resid) {
        if (mTvTopBarTitle != null) {
            mTvTopBarTitle.setText(resid);
        }
    }
}
