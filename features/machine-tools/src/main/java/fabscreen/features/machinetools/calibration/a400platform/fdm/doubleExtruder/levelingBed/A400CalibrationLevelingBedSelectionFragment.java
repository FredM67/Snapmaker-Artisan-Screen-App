package fabscreen.features.machinetools.calibration.a400platform.fdm.doubleExtruder.levelingBed;

import static fabscreen.features.machinetools.calibration.a400platform.fdm.doubleExtruder.levelingBed.A400LevelingBedCalibrationInfoFragment.A400_LEVELING_BED_CALIBRATION_AUTO;
import static fabscreen.features.machinetools.calibration.a400platform.fdm.doubleExtruder.levelingBed.A400LevelingBedCalibrationInfoFragment.A400_LEVELING_BED_CALIBRATION_MANUAL;

import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import butterknife.BindView;
import butterknife.OnClick;
import fabscreen.features.machinetools.R;
import fabscreen.features.machinetools.R2;
import fabscreen.platform.base.helper.DimensUtils;
import fabscreen.platform.base.helper.StringToValueUtils;
import fabscreen.platform.base.service.IMachine;
import fabscreen.platform.base.service.IPreferences;
import fabscreen.platform.base.service.machine.entity.Module;
import fabscreen.platform.base.view.BaseFragment;
import fabscreen.platform.base.view.MenuAdapter;
import fabscreen.platform.core.ui.common.VerticalSpaceItemDecoration;
import fabscreen.platform.core.ui.common.selection.CalibrationModeSelectionAdapter;
import fabscreen.platform.core.ui.common.selection.CalibrationModeSelectionItem;
import fabscreen.platform.core.ui.view.PullDownMenu;
import fabscreen.platform.core.ui.view.customkeyboard.CustomKeyboardUtil;

public class A400CalibrationLevelingBedSelectionFragment extends BaseFragment {
    @BindView(R2.id.rv_sections)
    RecyclerView mRvSections;
    @BindView(R2.id.li_calibration_right_select)
    LinearLayout mlySections;
    @BindView(R2.id.rl_calibration_leveling_bed_selection_temperature)
    RelativeLayout mRlTemperature;
    @BindView(R2.id.et_calibration_leveling_bed_selection_temperature)
    TextView mEtBedTemperature;
    @BindView(R2.id.tv_a400_calibration_leveling_bed_selection_title)
    TextView mTvLevelingBedSelectionTitle;
    @BindView(R2.id.tv_select_type)
    TextView mTvSelectType;
    @BindView(R2.id.rl_alibration_leveling_bed)
    RelativeLayout mRlAlibrationLevelingBed;

    @BindView(R2.id.cl_left_content)
    ConstraintLayout mClLeftContent;
    @BindView(R2.id.ly_calibration_right_select)
    ConstraintLayout mCLRightSelect;

    protected final List<CalibrationModeSelectionItem> mSectionItems = new ArrayList<>();
    protected List<CalibrationModeSelectionItem> items = new ArrayList<>();
    protected IPreferences.Helper helper;

    private int mGrid;
    private int mTemperature;
    private int mHeadType;
    private MenuAdapter mMenuAdapter;
    private int mCurrentPosition = 0;
    private CustomKeyboardUtil mCustomKeyboardUtil;

    public static Fragment newInstance() {
        return new A400CalibrationLevelingBedSelectionFragment();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        helper = getServiceContainer().getService(IPreferences.class).getHelper();
        mHeadType = getServiceContainer().getService(IMachine.class).getFDMController().getHeadType();
        mCustomKeyboardUtil = new CustomKeyboardUtil(requireContext());
        initView();
    }

    private void initView() {
        Animation animLeftIn = AnimationUtils.loadAnimation(requireContext(),
                R.anim.push_in_left);
        mClLeftContent.setAnimation(animLeftIn);

        Animation animRightIn = AnimationUtils.loadAnimation(requireContext(),
                R.anim.push_in_right);
        mCLRightSelect.startAnimation(animRightIn);

        if (mSectionItems.size() > 0) {
            mSectionItems.clear();
            items.clear();
        }
        mGrid = gridGeoIndex(helper.getA400LevelingBedCalibrationGrid());
        ArrayList<String> menuItems = new ArrayList<>();
        menuItems.add(getString(R.string.a400_control_there_matrix_title));
        menuItems.add(getString(R.string.a400_control_five_matrix_title));
        menuItems.add(getString(R.string.a400_control_nine_matrix_title));
        mMenuAdapter = new MenuAdapter(getContext(), menuItems);
        mTvSelectType.setText(menuItems.get(mGrid));
        mMenuAdapter.setOnItemClickListener((view, position) -> {
            playNormalClickSound();
            mGrid = position;
            mTvSelectType.setText(menuItems.get(position));
            PullDownMenu.dismiss();
        });
        mlySections.setVisibility(View.VISIBLE);
        mSectionItems.addAll(getSections());
        mRvSections.setLayoutManager(new LinearLayoutManager(requireContext()));

        CalibrationModeSelectionAdapter adapter = new CalibrationModeSelectionAdapter(mSectionItems);
        mRvSections.setAdapter(adapter);

        VerticalSpaceItemDecoration decoration = new VerticalSpaceItemDecoration(DimensUtils.dp2px(12));
        mRvSections.addItemDecoration(decoration);

        adapter.setOnSectionSelectedListener(position -> {
            playNormalClickSound();

            if (mCurrentPosition == position) {
                return;
            } else {
                mCurrentPosition = position;
            }

            onSectionSelected(position);

            Animation animRightOut = AnimationUtils.loadAnimation(requireContext(),
                    R.anim.push_out_left);
            animRightOut.setDuration(150);
            mClLeftContent.setAnimation(animRightOut);
            animRightOut.setAnimationListener(new Animation.AnimationListener() {
                @Override
                public void onAnimationStart(Animation animation) {

                }

                @Override
                public void onAnimationEnd(Animation animation) {
                    Animation animLeftIn = AnimationUtils.loadAnimation(requireContext(),
                            R.anim.push_in_left);
                    animLeftIn.setDuration(150);
                    mClLeftContent.setAnimation(animLeftIn);
                }

                @Override
                public void onAnimationRepeat(Animation animation) {

                }
            });
        });

        mCustomKeyboardUtil.bindKeyboardListener(mEtBedTemperature, new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {

            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {

            }

            @Override
            public void afterTextChanged(Editable s) {
                if (!TextUtils.isEmpty(s.toString())) {
                    mTemperature = getInputValue(StringToValueUtils.parseInt(s.toString()), 0, A400LevelingBedViewModel.MAX_BED_CALIBRATION_TEMPERATURE);
                    mEtBedTemperature.setText("" + mTemperature);
                }

            }
        });
        mEtBedTemperature.setOnClickListener(v -> {
            mCustomKeyboardUtil.setPreInputText(String.valueOf(mEtBedTemperature.getText()));
            mCustomKeyboardUtil.showKeyboard(v, CustomKeyboardUtil.INPUT_TYPE_NUMBER_DECIMAL);
            mCustomKeyboardUtil.setNumberInputType(InputType.TYPE_CLASS_NUMBER);
        });
        mTemperature = helper.getA400LevelingBedCalibrationBedTemperature();


        if (mHeadType == Module.ModuleType.HEAD_3DP) {
            mCurrentPosition = 0;
        } else {
            mCurrentPosition = helper.getA400LevelingBedCalibrationMode();
        }
        adapter.setSelection(mCurrentPosition);
        onSectionSelected(mCurrentPosition);
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

    private int gridGeoIndex(int a400LevelingBedCalibrationGrid) {
        switch (a400LevelingBedCalibrationGrid) {
            case 3:
                return 0;
            case 9:
                return 2;
            default:
                return 1;
        }

    }

    private void onSectionSelected(int position) {
        mTvLevelingBedSelectionTitle.setText(position == 0 ? R.string.calibration_auto_mode : R.string.calibration_manual_mode);
        helper.setA400LevelingBedCalibrationMode(position);
        if (position == 0) {
            if (mHeadType == Module.ModuleType.HEAD_3DP) {
                mRlTemperature.setVisibility(View.INVISIBLE);
                helper.setA400LevelingBedCalibrationMode(A400_LEVELING_BED_CALIBRATION_MANUAL);
            } else {
                mRlTemperature.setVisibility(View.VISIBLE);
                // mTemperature, not the stored preference: it keeps a value the user typed before
                // toggling the mode, which is only written back on confirm.
                mEtBedTemperature.setText(String.valueOf(mTemperature));
            }
        } else {
            mRlTemperature.setVisibility(View.INVISIBLE);
        }
    }

    private List<CalibrationModeSelectionItem> getSections() {
        if (mHeadType == Module.ModuleType.HEAD_3DP_DOUBLE_EXTRUDER) {
            items.add(new CalibrationModeSelectionItem(R.string.calibration_auto_mode, R.string.a400_calibration_heated_bed_leveling_auto_content));
        }
        items.add(new CalibrationModeSelectionItem(R.string.calibration_manual_mode, R.string.a400_calibration_heated_bed_leveling_manual_content));
        return items;
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.fragment_a400_calibration_leveling_bed_selection;
    }

    @OnClick(R2.id.tv_air_purifier_error_power_off_calibration_right_select_exit)
    public void onClickExit() {
        playNormalClickSound();
        onExit();
    }

    @OnClick(R2.id.bt_calibration_right_select_cancel)
    public void onClickCancel() {
        playNormalClickSound();
        onExit();
    }

    @OnClick(R2.id.bt_calibration_right_select_determine)
    public void onClickDetermine() {
        playNormalClickSound();
        onDetermine();
        onExit();
    }

    @OnClick(R2.id.rl_alibration_leveling_bed)
    public void onClickCalibrationPoints() {
        playNormalClickSound();
        PullDownMenu.create(getContext(), mMenuAdapter)
                .showBelowView(mRlAlibrationLevelingBed, 0, 10);
        if (mMenuAdapter != null) {
            mMenuAdapter.setSelectPosition(mGrid);
        }
    }

    private void onDetermine() {
        int a400LevelingBedCalibrationMode = helper.getA400LevelingBedCalibrationMode();
        if (a400LevelingBedCalibrationMode == A400_LEVELING_BED_CALIBRATION_AUTO) {
            helper.setA400LevelingBedCalibrationBedTemperature(mTemperature);
        }
        helper.setA400LevelingBedCalibrationGrid(IndexToGrid(mGrid));
    }

    private int IndexToGrid(int grid) {
        switch (grid) {
            case 0:
                return 3;
            case 1:
                return 5;
            case 2:
                return 9;
            default:
                return 5;
        }
    }

    private void onExit() {
        if (getActivity() != null) {
            Animation animLeftOut = AnimationUtils.loadAnimation(requireContext(),
                    R.anim.push_out_left);
            mClLeftContent.setAnimation(animLeftOut);

            Animation animRightOut = AnimationUtils.loadAnimation(requireContext(),
                    R.anim.push_out_right);
            mCLRightSelect.startAnimation(animRightOut);
            getActivity().finish();
        }
    }
}
