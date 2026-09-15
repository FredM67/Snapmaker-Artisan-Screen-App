package fabscreen.features.filemanager;

import static fabscreen.platform.base.service.machine.entity.Module.ModuleType.HEAD_3DP;
import static fabscreen.platform.base.service.machine.entity.Module.ModuleType.HEAD_3DP_DOUBLE_EXTRUDER;

import android.content.Context;
import android.graphics.Bitmap;

import com.orhanobut.logger.Logger;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

import fabscreen.features.filemanager.entity.BrowseShowFile;
import fabscreen.platform.base.instantiation.ServiceContainer;
import fabscreen.platform.base.lib.file.IFile;
import fabscreen.platform.base.lib.parser.IGcodeParser;
import fabscreen.platform.base.lib.print.IPrintWorkspace;
import fabscreen.platform.base.model.ModelBoundary;
import fabscreen.platform.base.service.IAppService;
import fabscreen.platform.base.service.IFileManagerService;
import fabscreen.platform.base.service.IMachine;
import fabscreen.platform.base.service.machine.MachineInfo;
import fabscreen.platform.base.service.machine.Vector;
import fabscreen.platform.base.service.machine.entity.Module;
import fabscreen.platform.base.service.machine.structure.ResponseStructure;
import fabscreen.platform.base.view.BaseViewModel;
import fabscreen.platform.lib.LogHelper;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.subjects.BehaviorSubject;

public class BrowseFileDetailViewModel extends BaseViewModel {
    public static final int MODE_NORMAL = 101;
    public static final int MODE_disable_dual_extrusion = 102;
    public static final int MODE_OUT_OF_RANGE = 103;

    private IGcodeParser mParser;
    private IPrintWorkspace mPrintWorkspace;
    private IFile mFile;
    private IFileManagerService mFileManagerService;
    private final IMachine mMachine;

    private final IMachine.WorkType mWorkType;
    private final MachineInfo mMachineInfo;
    private int mPrintMode = 0;
    private int mHeadToolType = -1;

    private boolean mIsJ1;
    private int mHeatedBedMode = 1;

    private Context mContext;
    private BrowseShowFile mBrowseShowFile;

    private BehaviorSubject<Boolean> mIsHaveUSBState = BehaviorSubject.createDefault(false);

    public BrowseFileDetailViewModel() {
        super();
        mParser = ServiceContainer.getInstance().getService(IGcodeParser.class);
        mPrintWorkspace = ServiceContainer.getInstance().getService(IPrintWorkspace.class);
        mMachine = getServiceContainer().getService(IMachine.class);
        mMachineInfo = mMachine.getMachineInfoSubjectHolder().getValue();
        mContext = getServiceContainer().getService(IAppService.class).getAppContext();
        mWorkType = mMachineInfo.workType;
        mPrintWorkspace.setPrintModeXOffset(0);
        mIsJ1 = ServiceContainer.getInstance().getService(IMachine.class).getMachineInfoSubjectHolder().getValue().seriesId == IMachine.MachineSeries.J;
        mFileManagerService = ServiceContainer.getInstance().getService(IFileManagerService.class);

        //init mFileManagerService value
        mIsHaveUSBState.onNext(mFileManagerService.getFileManagerStateSubjHolder()
                .getValue());
        mFileManagerService.getFileManagerStateSubjHolder()
                .getObservable()
                .observeOn(AndroidSchedulers.mainThread())
                .as(bindToLifecycle())
                .subscribe(aBoolean -> {
                    mIsHaveUSBState.onNext(aBoolean);
                }, LogHelper::log);
    }

    public int getPrintMode() {
        return mPrintMode;
    }

    public void setPrintMode(int mPrintMode) {
        this.mPrintMode = mPrintMode;
    }

    public IGcodeParser getGcodeInfo() {
        return mParser;
    }

    public Observable<Boolean> getUsbStateObservable() {
        return mIsHaveUSBState.hide();
    }

    public int getFilePrintMode() {
        return mParser.getCustomPrintMode();
    }

    public BrowseShowFile getBrowseShowFile() {
        return mBrowseShowFile;
    }

    public void setHeatedBedMode(int mode) {
        mHeatedBedMode = mode;
    }

    public void setFile(String filePath, boolean isLocal) {
        mFile = ServiceContainer.getInstance().getService(IFileManagerService.class).getDevice(isLocal).search(filePath);
    }

    public void setFile(BrowseShowFile browseShowFile) {
        mBrowseShowFile = browseShowFile;
        mFile = mBrowseShowFile.getIFile();
    }

    public Bitmap getGcodeThumbnail() {
        return mParser.getGcodeThumbnail();
    }

    public String getFileName() {
        if (mFile == null) {
            return "NULL";
        } else {
            return mFile.getName();
        }
    }

    public String getFileInfo() {
        if (mFile == null) {
            return "NULL";
        } else {
            long fileLength = mFile.length();
            SimpleDateFormat sdf = new SimpleDateFormat("MM-dd-yyyy  hh:mm", Locale.getDefault());
            String lastModified = sdf.format(new Date(mFile.lastModified()));
            String fileLengthUnit = "bytes";
            if (fileLength > 1024) {
                fileLength /= 1024;
                fileLengthUnit = "KB";
                if (fileLength > 1024) {
                    fileLength /= 1024;
                    fileLengthUnit = "MB";
                }
            }
            return String.format(Locale.ENGLISH, "%s %s  %s", fileLength, fileLengthUnit, lastModified);
        }
    }

    // TODO: Need to get real machine heated bed work mode status.
    public int getHeatedBedMode() {
        return mHeatedBedMode;
    }

    public ArrayList<DetailDesc> getShowData() {
        ArrayList<DetailDesc> detailDescs = new ArrayList<>();
        String str = "";
        if (!mIsJ1) {
            if (mParser.getHeaderType() != -1) {
                mHeadToolType = mParser.getHeaderType();
                str = mContext.getString(mParser.getHeaderNameID());
                detailDescs.add(new DetailDesc(mContext.getString(R.string.a400_file_detail_tool_head_title), str));
            }

            if (mParser.isContainRotation() != -1) {
                str = mParser.isContainRotation() == 0 ? mContext.getString(R.string.a400_file_detail_3_axis_title) : mContext.getString(R.string.a400_file_detail__4_axis_title);
                detailDescs.add(new DetailDesc(mContext.getString(R.string.a400_file_detail_job_type_title), str));
            }

            if (mParser.getWorkSizeX() != -1 && mParser.getWorkSizeY() != -1) {
                str = mParser.getWorkSizeX() + " × " + mParser.getWorkSizeY() + mContext.getString(R.string.all_unit_mm);
                detailDescs.add(new DetailDesc(mContext.getString(R.string.a400_file_detail_work_size_title), str));
            }

            if (mParser.getOrigin() != null) {
                str = mParser.getOrigin();
                detailDescs.add(new DetailDesc(mContext.getString(R.string.a400_file_detail_work_origin_title), str));
            }
        }

        boolean showLeftNozzle = !mParser.isToolUsageConfirmed() || mParser.isTool0Used();
        boolean showRightNozzle = !mParser.isToolUsageConfirmed() || mParser.isTool1Used();

        if ((showLeftNozzle && mParser.getMaterial_0() != null)
                || (showRightNozzle && mParser.getMaterial_1() != null)) {
            str = "";
            if (showLeftNozzle && mParser.getMaterial_0() != null) {
                str = "L: " + mParser.getMaterial_0();
            }
            if (showRightNozzle && mParser.getMaterial_1() != null) {
                str += (str.isEmpty() ? "" : " ") + "R: " + mParser.getMaterial_1();
            }
            detailDescs.add(new DetailDesc(mContext.getString(R.string.all_file_details_filament), str));
        }

        if (mHeadToolType == HEAD_3DP || mHeadToolType == HEAD_3DP_DOUBLE_EXTRUDER) {
            if ((showLeftNozzle && mParser.getNozzleTargetTemperature() != 0)
                    || (showRightNozzle && mParser.getNozzleTarget_1_Temperature() != 0)) {
                str = "";
                if (showLeftNozzle && mParser.getNozzleTargetTemperature() != 0) {
                    str = "L: " + (int) mParser.getNozzleTargetTemperature()
                            + mContext.getString(R.string.all_unit_temperature);
                }
                if (showRightNozzle && mParser.getNozzleTarget_1_Temperature() != 0) {
                    str += (str.isEmpty() ? "" : " ") + "R: "
                            + (int) mParser.getNozzleTarget_1_Temperature()
                            + mContext.getString(R.string.all_unit_temperature);
                }
                detailDescs.add(new DetailDesc(mContext.getString(R.string.all_file_details_nozzle_temp), str));
            }
        }

        if ((showLeftNozzle && mParser.getNozzle_0_Diameter() != -1)
                || (showRightNozzle && mParser.getNozzle_1_Diameter() != -1)) {
            str = "";
            if (showLeftNozzle && mParser.getNozzle_0_Diameter() != -1) {
                str = "L: " + mParser.getNozzle_0_Diameter()
                        + mContext.getString(R.string.all_unit_mm);
            }
            if (showRightNozzle && mParser.getNozzle_1_Diameter() != -1) {
                str += (str.isEmpty() ? "" : " ") + "R: "
                        + mParser.getNozzle_1_Diameter()
                        + mContext.getString(R.string.all_unit_mm);
            }
            detailDescs.add(new DetailDesc(mContext.getString(R.string.all_file_details_nozzle_diameter), str));
        }

        if (mParser.getBedTargetTemperature() != 0) {
            str = (int) mParser.getBedTargetTemperature() + mContext.getString(R.string.all_unit_temperature);
            detailDescs.add(new DetailDesc(mContext.getString(R.string.all_file_details_heated_bed_temp), str));
        }

        if (!mIsJ1) {
            if (mParser.getLayerNumber() != -1 || mParser.getLayerHeight() != -1) {
                str = "";
                if (mParser.getLayerNumber() != -1) {
                    str += mParser.getLayerNumber();
                } else {
                    str += " - ";
                }

                if (mParser.getLayerHeight() != -1) {
                    str += " / " + mParser.getLayerHeight() + mContext.getString(R.string.all_unit_mm);
                } else {
                    str += " / - ";
                }

                detailDescs.add(new DetailDesc(mContext.getString(R.string.a400_file_detail_layer_number_layer_ht_title), str));
            }
        }

        if (mParser.getEstimatedTime() != 0) {
            str = formatTime(mParser.getEstimatedTime());
            detailDescs.add(new DetailDesc(mContext.getString(R.string.all_file_details_estimated_time), str));
        }

        if (!mIsJ1) {
            if (mParser.getMaterialWeight() != -1 || mParser.getLayerHeight() != -1) {
                str = "";
                if (mParser.getMaterialLength() != -1) {
                    str += String.format(Locale.ENGLISH, "%.1f", mParser.getMaterialLength()) + mContext.getString(R.string.all_unit_meter);
                } else {
                    str += " -" + mContext.getString(R.string.all_unit_meter);
                }

                if (mParser.getMaterialWeight() != -1) {
                    str += " / " + String.format(Locale.ENGLISH, "%.1f", mParser.getMaterialWeight()) + mContext.getString(R.string.all_unit_gram);
                } else {
                    str += " / -" + mContext.getString(R.string.all_unit_gram);
                }

                detailDescs.add(new DetailDesc(mContext.getString(R.string.a400_file_detail_material_required_title), str));
            }
        }

        return detailDescs;
    }

    public Observable<Boolean> handleResult() {
        mPrintWorkspace.setPrintMode(mPrintMode);
        mPrintWorkspace.setPrintSource(0);
        mPrintWorkspace.setFileTotalLineCount((int) mParser.getTotalLinesCount());
        mPrintWorkspace.setEstimatedTime(mParser.getEstimatedTime());
//        mPrintWorkspace.setFileMD5Value("c319528c5c360d46031b69d39e01ceb3");
        mPrintWorkspace.setModelBoundary(mParser.getBoundary());

        mPrintWorkspace.setApplyMultiExtruder(mParser.isApplyMultiExtruder());
        if (mParser.getFileType() == IMachine.WorkType.FDM) {
            if (mParser.getHeaderType() == HEAD_3DP) {
                mPrintWorkspace.setWorkTemperature(new float[]{mParser.getNozzleTargetTemperature()});
            } else if (mParser.getHeaderType() == HEAD_3DP_DOUBLE_EXTRUDER) {
                mPrintWorkspace.setWorkTemperature(new float[]{mParser.getNozzleTargetTemperature(), mParser.getNozzleTarget_1_Temperature()});
            }
        }
        // FIXME:Temporarily add : the copied file when clicking the file
        return mPrintWorkspace.addFileToWorkspace(mFile);
    }

    public Observable<Boolean> checkToolhead() {

        int gcodeHeadType = mParser.getHeaderType();
        int machineHeadType = -1;
        if (mWorkType == IMachine.WorkType.FDM) {
            machineHeadType = mMachine.getFDMController().getHeadType();
        } else if (mWorkType == IMachine.WorkType.LASER) {
            machineHeadType = mMachine.getLaserController().getHeadType();
        } else if (mWorkType == IMachine.WorkType.CNC) {
            machineHeadType = mMachine.getCNCController().getHeadType();
        }
        return Observable.just(machineHeadType != -1 && (machineHeadType == gcodeHeadType || gcodeHeadType == -1));
    }

    public Observable<Boolean> checkExtruder() {
        if (mWorkType == IMachine.WorkType.FDM && mMachine.getFDMController().getHeadType() == Module.ModuleType.HEAD_3DP_DOUBLE_EXTRUDER) {
            return Observable.just(extruderMatch());
        } else {
            return Observable.just(true);
        }
    }

    public Observable<Boolean> checkFileExtruderRetractionDistance() {
        if (mWorkType == IMachine.WorkType.FDM && mMachine.getFDMController().getHeadType() == Module.ModuleType.HEAD_3DP_DOUBLE_EXTRUDER) {
            final float extruder0Retraction = mParser.getExtruder0RetractionDistance();
            final float extruder1Retraction = mParser.getExtruder1RetractionDistance();
            Logger.d("e0 retraction %.2f, e1 retraction %.2f", extruder0Retraction, extruder1Retraction);
            boolean usageConfirmed = mParser.isToolUsageConfirmed();
            boolean checkLeft = !usageConfirmed || mParser.isTool0Used();
            boolean checkRight = !usageConfirmed || mParser.isTool1Used();
            boolean isRetractionOverLimit = (checkLeft && extruder0Retraction > 2f)
                    || (checkRight && extruder1Retraction > 2f);

            return Observable.just(!isRetractionOverLimit);
        } else {
            return Observable.just(true);
        }
    }

    public Observable<Boolean> checkLockingBlockOrigin() {
        String origin = mParser.getOrigin();
        if (origin == null) {
            return Observable.just(true);
        }
        final String positionA = mContext.getString(R.string.all_gcode_parser_work_origin_bottom_position_a);
        final String positionB = mContext.getString(R.string.all_gcode_parser_work_origin_bottom_position_b);
        float lockingBlockPositionX;
        float lockingBlockPositionY;
        if (origin.equals(positionA)) {
            // X 30 Y 8
            lockingBlockPositionX = 30;
            lockingBlockPositionY = 8;
            return mMachine.getMachineController().updateCoordinateSystem(0)
                    .flatMap(status -> mMachine.getMachineController().getCachedCoordinateObservable())
                    .flatMap(machineStatus -> {
                        Vector vector2 = new Vector();
                        float machineX = (machineStatus.currentPosition.getX() - machineStatus.originOffset.getX());
                        float machineY = (machineStatus.currentPosition.getY() - machineStatus.originOffset.getY());
                        vector2.setX(machineX - lockingBlockPositionX);
                        vector2.setY(machineY - lockingBlockPositionY);
                        Logger.d(vector2.toString());
                        return mMachine.getMachineController().updateCoordinateSystem(1)
                                .flatMap(status2 -> mMachine.getMachineController().setWorkOrigin(vector2));
                    })
                    .flatMap(structure -> Observable.just(structure.isSuccess()));
        } else if (origin.equals(positionB)) {
            // X 115 Y 99
            lockingBlockPositionX = 115;
            lockingBlockPositionY = 99;
            return mMachine.getMachineController().updateCoordinateSystem(0)
                    .flatMap(status -> mMachine.getMachineController().getCachedCoordinateObservable())
                    .flatMap(machineStatus -> {
                        float machineX = (machineStatus.currentPosition.getX() - machineStatus.originOffset.getX());
                        float machineY = (machineStatus.currentPosition.getY() - machineStatus.originOffset.getY());

                        Vector vector2 = new Vector();
                        vector2.setX(machineX - lockingBlockPositionX);
                        vector2.setY(machineY - lockingBlockPositionY);
                        Logger.d(vector2.toString());
                        return mMachine.getMachineController().updateCoordinateSystem(1)
                                .flatMap(status2 -> mMachine.getMachineController().setWorkOrigin(vector2));
                    })
                    .flatMap(structure -> Observable.just(structure.isSuccess()));
        } else {
            return Observable.just(true);
        }
    }

    private boolean extruderMatch() {
        try {
            float diameter0 = mMachine.getFDMController().getToolheadStatusSubjectHolder(0).getValue().getExtruderList().get(0).getDiameter();
            float diameter1 = mMachine.getFDMController().getToolheadStatusSubjectHolder(0).getValue().getExtruderList().get(1).getDiameter();
            float gcodeDiameter0 = mParser.getNozzle_0_Diameter();
            float gcodeDiameter1 = mParser.getNozzle_1_Diameter();
            boolean usageConfirmed = mParser.isToolUsageConfirmed();
            boolean checkLeft = !usageConfirmed || mParser.isTool0Used();
            boolean checkRight = !usageConfirmed || mParser.isTool1Used();
            return (!checkLeft || diameter0 == gcodeDiameter0 || gcodeDiameter0 == -1)
                    && (!checkRight || diameter1 == gcodeDiameter1 || gcodeDiameter1 == -1);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public int checkPrintModeAvailable(int selectMode) {
        int modeType = MODE_NORMAL;
        ModelBoundary boundary = mParser.getBoundary();
        float modelXRange = boundary.getMaxX() - boundary.getMinX();
        switch (selectMode) {
            case IPrintWorkspace.PRINT_MODE_NORMAL:
                break;
            case IPrintWorkspace.PRINT_MODE_DUAL_EXTRUDER_BACK_UP:
                if (mParser.isApplyMultiExtruder()) {
                    modeType = MODE_disable_dual_extrusion;
                }
                break;
            case IPrintWorkspace.PRINT_MODE_CLONE:
                // Not available when multiple Extruder is already use in G-code.
                if (mParser.isApplyMultiExtruder()) {
                    modeType = MODE_disable_dual_extrusion;
                } else {
                    // Print Model out of range
                    modeType = modelXRange < 160 ? MODE_NORMAL : MODE_OUT_OF_RANGE;
                }
                break;
            case IPrintWorkspace.PRINT_MODE_MIRROR:
                // Not available when multiple extruder is already use in G-code.
                if (mParser.isApplyMultiExtruder()) {
                    modeType = MODE_disable_dual_extrusion;
                } else {
                    // Print Model out of range
                    modeType = modelXRange < 150 ? MODE_NORMAL : MODE_OUT_OF_RANGE;
                }
                break;
        }
        return modeType;
    }

    public Observable<ResponseStructure> setFDMHeatedBedWorkMode(int mode) {
        return mMachine.getMachineController().getHeatedBed().setHeatedBedWorkMode(mode)
                .doOnNext(response -> Logger.d("Set Heated Bed work mode %d %b.",
                        mode, response.isSuccess()));
    }

    public void setXOffsetWithMode(int selectMode) {
        int filePrintMode = mParser.getCustomPrintMode();
        ModelBoundary boundary = mParser.getBoundary();
        float modelXRange = boundary.getMaxX() - boundary.getMinX();
        // print area x is 300mm as default in J1
        int printAreaCenterX;
        switch (filePrintMode) {
            case IPrintWorkspace.PRINT_MODE_NORMAL:
            case IPrintWorkspace.PRINT_MODE_DUAL_EXTRUDER_BACK_UP:
                if (selectMode == IPrintWorkspace.PRINT_MODE_NORMAL || selectMode == IPrintWorkspace.PRINT_MODE_DUAL_EXTRUDER_BACK_UP) {
                    // No offset is needed.
                    mPrintWorkspace.setPrintModeXOffset(0);
                } else {
                    // print area minus half
                    printAreaCenterX = 75;
                    mPrintWorkspace.setPrintModeXOffset(printAreaCenterX - (modelXRange * 0.5f + boundary.getMinX()));
                }
                break;

            case IPrintWorkspace.PRINT_MODE_CLONE:
            case IPrintWorkspace.PRINT_MODE_MIRROR:
                if (selectMode == IPrintWorkspace.PRINT_MODE_CLONE || selectMode == IPrintWorkspace.PRINT_MODE_MIRROR) {
                    mPrintWorkspace.setPrintModeXOffset(0);
                } else {
                    printAreaCenterX = 150;
                    mPrintWorkspace.setPrintModeXOffset(printAreaCenterX - (modelXRange * 0.5f + boundary.getMinX()));
                }
                break;
        }
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
}
