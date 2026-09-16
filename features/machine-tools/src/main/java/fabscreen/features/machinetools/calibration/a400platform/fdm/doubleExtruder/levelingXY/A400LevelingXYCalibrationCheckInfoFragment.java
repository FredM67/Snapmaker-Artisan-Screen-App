package fabscreen.features.machinetools.calibration.a400platform.fdm.doubleExtruder.levelingXY;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestOptions;

import butterknife.BindView;
import butterknife.OnClick;
import fabscreen.features.machinetools.R;
import fabscreen.features.machinetools.R2;
import fabscreen.features.machinetools.calibration.A400CalibrationBaseFragment;
import fabscreen.platform.base.instantiation.ServiceContainer;
import fabscreen.platform.base.service.IMachine;
import fabscreen.platform.lib.LogHelper;

public class A400LevelingXYCalibrationCheckInfoFragment extends A400CalibrationBaseFragment {

    /** XY calibration prints a part, so the bed is cooled down to let the user take it off. */
    @Override
    protected boolean shouldCoolDownBedOnExit() {
        return true;
    }

    @BindView(R2.id.tv_a400_leveling_xy_adjust_title)
    TextView mAdjustSubTitle;
    @BindView(R2.id.tv_a400_leveling_xy_adjust_content)
    TextView mAdjustContent;
    @BindView(R2.id.btn_next)
    Button mBtNext;
    @BindView(R2.id.iv_a400_leveling_xy_adjust)
    ImageView mIvShow;

    public static Fragment newInstance() {
        return new A400LevelingXYCalibrationCheckInfoFragment();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        initView();
    }

    private void initView() {
        setTitle(R.string.calibration_a400_leveling_xy_title);
        mTvTopBarContent.setText(R.string.calibration_a400_leveling_xy_prepare_check);
        mAdjustSubTitle.setText(R.string.calibration_a400_leveling_xy_prepare_check_sub_title);
        mAdjustContent.setText(R.string.calibration_a400_leveling_xy_prepare_check_content);
        mGuideProgressBar.setMax(6);
        mGuideProgressBar.setProgress(4);
        mGuideProgressBar.setVisibility(View.VISIBLE);
        RequestOptions options = new RequestOptions().transform(new RoundedCorners(18));
        Glide.with(requireContext())
                .load(R.drawable.pic_xy_offset_calibration_material_pla_prepare_calibration_check_578x434)
                .apply(options)
                .into(mIvShow);
    }

    @OnClick(R2.id.btn_next)
    void onClickNext() {
        playNormalClickSound();
        ServiceContainer.getInstance().getService(IMachine.class).getFDMController()
                .exitCalibration(true)
                .as(bindToLifecycle())
                .subscribe(success -> {
                    if (getActivity() != null) {
                        ((A400LevelingXYCalibrationActivity) getActivity()).gotoCheckPrint();
                    }
                }, LogHelper::log);
    }

    @OnClick(R2.id.btn_a400_leveling_xy_no_check)
    void onClickNoCheck() {
        playNormalClickSound();
        getServiceContainer().getService(IMachine.class).getFDMController().exitCalibration(true).as(bindToLifecycle()).subscribe();
        if (getActivity() != null) {
            finishActivityWithResultOk();
//            ((A400LevelingXYCalibrationActivity) getActivity()).gotoLevelingXYCalibrationComplete();
        }
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.fragment_a400_leveling_xy_calibration_check_info;
    }

}
