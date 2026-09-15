package fabscreen.platform.base.lib.print;

import android.content.Context;
import android.os.SystemClock;

import com.orhanobut.logger.Logger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;

import fabscreen.platform.base.helper.Md5Util;
import fabscreen.platform.base.instantiation.IServiceIdentifier;
import fabscreen.platform.base.instantiation.ServiceContainer;
import fabscreen.platform.base.lib.file.FabLocalFile;
import fabscreen.platform.base.lib.file.FabUsbFile;
import fabscreen.platform.base.lib.file.IFile;
import fabscreen.platform.base.lib.file.IPartition;
import fabscreen.platform.base.model.ModelBoundary;
import fabscreen.platform.base.service.IAppService;
import fabscreen.platform.base.service.IFileManagerService;
import fabscreen.platform.base.service.IMachine;
import fabscreen.platform.base.service.IPreferences;
import fabscreen.platform.lib.LogHelper;
import io.reactivex.Observable;
import io.reactivex.Scheduler;
import io.reactivex.schedulers.Schedulers;
import io.reactivex.subjects.BehaviorSubject;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.Okio;

public class BasePrintWorkspace implements IPrintWorkspace, IServiceIdentifier {

    private IPreferences mPreferences;

    //    private String mWorkspaceDirPath;
    private String mFilesDirPath;
    private IFile mSourceFile;
    private IFile mPrintFile = null;
    private String mPrintFileMD5Value;

    private Scheduler.Worker mCopyFileWorker;
    private BehaviorSubject<Boolean> mCopyResultSubject = BehaviorSubject.create();
    private int mPrintMode = PRINT_MODE_NORMAL;
    private boolean mApplyMultiExtruder;

    private float[] mExtruderTargetTemperature;
    private float mPrintModeXOffset = 0f;

    private ModelBoundary mModelBoundary;

    public BasePrintWorkspace(IPreferences preferences) {
        mPreferences = preferences;
        // Should not place in cache directory.
        Context context = ServiceContainer.getInstance().getService(IAppService.class).getAppContext();

//        mWorkspaceDirPath = context.getFilesDir() + "/.workspace";
        mFilesDirPath = context.getFilesDir().getAbsolutePath();
    }

    @Override
    public void initLastPrintFile() {
        String printFilePath = mPreferences.getHelper().getPrintFilePath();
        if (printFilePath == null) {
            mPrintFile = null;
        } else {
            mPrintFile = new FabLocalFile(new File(printFilePath));
        }

    }

    @Override
    public IFile getPrintFile() {
        return mPrintFile;
    }

    @Override
    public void setPrintFile(IFile file) {
        runWorkspaceMutation(() -> mPrintFile = file);
    }


    @Override
    public String getFileMD5Value() {
        if (mPrintFileMD5Value == null || (mPrintFileMD5Value.isEmpty() && mPrintFile != null)) {
            return Md5Util.fileToMD5(mPrintFile.getPath());
        }
        return mPrintFileMD5Value;
    }

    @Override
    public void setFileMD5Value(String value) {
        runWorkspaceMutation(() -> mPrintFileMD5Value = value);
    }

    @Override
    public int getPrintMode() {
        return mPrintMode;
    }

    @Override
    public void setPrintMode(int printMode) {
        runWorkspaceMutation(() -> mPrintMode = printMode);
    }

    @Override
    public float getEstimatedTime() {
        return mPreferences.getHelper().getPrintFileEstimatedTime();
    }

    @Override
    public void setEstimatedTime(float estimatedTime) {
        runWorkspaceMutation(() ->
                mPreferences.getHelper().setPrintFileEstimatedTime(estimatedTime));
    }

    @Override
    public int getFileTotalLineCount() {
        return mPreferences.getHelper().getPrintFileTotalLines();
    }

    @Override
    public void setFileTotalLineCount(int totalCount) {
        runWorkspaceMutation(() -> {
            Logger.d("Set total line %d in workspace", totalCount);
            mPreferences.getHelper().setPrintFileTotalLines(totalCount);
        });
    }

    @Override
    public int getPrintSource() {
        return mPreferences.getHelper().getPrintSource();
    }

    @Override
    public void setPrintSource(int source) {
        runWorkspaceMutation(() -> mPreferences.getHelper().setPrintSource(source));
    }

    @Override
    public String getFileName() {
        if (mPrintFile == null) {
            return "";
        }
        return mPrintFile.getName();
    }

    @Override
    public Observable<Boolean> addFileToWorkspace(IFile sourceFile) {
        if (isPrintPreparationReserved()) {
            return Observable.just(false);
        }
        // Reset
        if (mCopyFileWorker != null) {
            mCopyFileWorker.dispose();
        }
        if (mCopyResultSubject != null) {
            mCopyResultSubject = BehaviorSubject.create();
        }
//        clearWorkspaceFile();
        mSourceFile = sourceFile;

        Logger.d("Start copy file %s into workspace…", sourceFile.getName());

        // Create worker for copy file into workspace
        mCopyFileWorker = Schedulers.io().createWorker();
        mCopyFileWorker.schedule(this::startCopyFile);

        return mCopyResultSubject.hide();
    }

    private void clearWorkspaceFile() {
        File workspaceDir = getWorkspaceDir();
        File[] files = workspaceDir.listFiles();
        if (files != null) {
            for (File file : files) {
                boolean ret = file.delete();
            }
        }
    }

    private void startCopyFile() {
        if (isPrintPreparationReserved()) {
            mCopyResultSubject.onNext(false);
            return;
        }
        if (mSourceFile instanceof FabUsbFile) {
            IPartition device = ServiceContainer.getInstance().getService(IFileManagerService.class).getDevice(true);
            if (mSourceFile.length() >= device.getTotalSpace()) {
                Logger.d("The file is too large.\nFile Size:" + mSourceFile.length() + "\nDevice TotalSpace:" + device.getTotalSpace());
                mCopyResultSubject.onNext(false);
                return;
            } else if (mSourceFile.length() >= device.getFreeSpace()) {
                Logger.d("Start deleting files to occur space " + SystemClock.elapsedRealtime());
                try {
                    while (mSourceFile.length() >= device.getFreeSpace()) {
                        ArrayList<IFile> iFiles = device.getRootFile().listFiles();
                        iFiles.sort(Comparator.comparingLong(IFile::lastModified));
                        device.removeFile(iFiles.get(0));
                    }
                } catch (Exception e) {
                    LogHelper.log(e);
                    mCopyResultSubject.onNext(false);
                    return;
                }
                Logger.d("Files deleted " + SystemClock.elapsedRealtime());
            }
//            mPrintFile = new FabLocalFile(new File(getWorkspaceDir(), mSourceFile.getName()));
            BufferedSource bufferedSource = null;
            BufferedSink bufferedSink = null;
            IFile destinationFile = null;
            try {
                destinationFile = device.createFile(device.getRootFile(), mSourceFile.getName());
                bufferedSource = Okio.buffer(Okio.source(mSourceFile.getInputStream()));
                bufferedSink = Okio.buffer(Okio.sink(destinationFile.getOutputStream()));
                // copy file from source with buffer
                int len;
                byte[] buffer = new byte[20480];
                while ((len = bufferedSource.read(buffer)) > 0) {
                    bufferedSink.write(buffer, 0, len);
                }
                bufferedSink.close();
                bufferedSource.close();
                IFile completedFile = destinationFile;
                if (runWorkspaceMutation(() -> {
                    mPrintFile = completedFile;
                    mPreferences.getHelper().setPrintFilePath(completedFile.getPath());
                })) {
                    mCopyResultSubject.onNext(true);
                } else {
                    destinationFile.removeFile();
                    mCopyResultSubject.onNext(false);
                }
            } catch (Exception e) {
                try {
                    if (destinationFile != null) destinationFile.removeFile();
                } catch (Exception e1) {

                }
                mCopyResultSubject.onNext(false);
                LogHelper.log(e);
            } finally {
                // TODO: close the device when  input stream destroyed
//            iPartition.deviceHang();
                try {
                    if (bufferedSink != null) {
                        bufferedSink.close();
                    }
                    if (bufferedSource != null) {
                        bufferedSource.close();
                    }
                } catch (IOException e) {
                    LogHelper.log(e);
                }
                try {
                    if (destinationFile != null) {
                        destinationFile.setLastModified(mSourceFile.lastModified());
                    }
                } catch (Exception ignored) {

                }
            }
        } else if (mSourceFile instanceof FabLocalFile) {
            if (isPrintPreparationReserved()) {
                mCopyResultSubject.onNext(false);
                return;
            }
            if (runWorkspaceMutation(() -> {
                mPrintFile = mSourceFile;
                mPreferences.getHelper().setPrintFilePath(mPrintFile.getPath());
            })) {
                mCopyResultSubject.onNext(true);
            } else {
                mCopyResultSubject.onNext(false);
            }
        } else {
            mCopyResultSubject.onNext(false);
        }
    }

    private boolean isPrintPreparationReserved() {
        try {
            return ServiceContainer.getInstance()
                    .getService(IMachine.class)
                    .getNewPrintController()
                    .isPrintPreparationReserved();
        } catch (Exception error) {
            LogHelper.log(error);
            return true;
        }
    }

    private boolean runWorkspaceMutation(Runnable mutation) {
        try {
            return ServiceContainer.getInstance()
                    .getService(IMachine.class)
                    .getNewPrintController()
                    .runPrintWorkspaceMutation(mutation);
        } catch (Exception error) {
            LogHelper.log(error);
            return false;
        }
    }

    @Override
    public File getWorkspaceDir() {
        File fileDir = new File(mFilesDirPath);
        if (!fileDir.exists()) {
            boolean ret = fileDir.mkdir();
            if (!ret) {
                return null;
            }
        }
        return fileDir;
    }

    @Override
    public void setModelBoundary(ModelBoundary boundary) {
        runWorkspaceMutation(() -> mModelBoundary = boundary);
    }

    @Override
    public ModelBoundary getModelBoundary() {
        return mModelBoundary;
    }

    @Override
    public float getWorkTemperature(int index) {
        if (mExtruderTargetTemperature == null || index <= mExtruderTargetTemperature.length) {
            return 0;
        }
        return mExtruderTargetTemperature[index];
    }

    @Override
    public void setWorkTemperature(float[] extruderTargetTemperature) {
        runWorkspaceMutation(() -> mExtruderTargetTemperature = extruderTargetTemperature);
    }

    @Override
    public void setPrintModeXOffset(float xOffset) {
        runWorkspaceMutation(() -> {
            Logger.d("set mode xOffset " + xOffset);
            mPrintModeXOffset = xOffset;
        });
    }

    @Override
    public float getPrintModeXOffset() {
        return mPrintModeXOffset;
    }

    public void dispose() {
        if (mCopyFileWorker != null && !mCopyFileWorker.isDisposed()) {
            mCopyFileWorker.dispose();
        }
    }

    @Override
    public void clearAllWorkSpaceFiles() {
        File fileDir = new File(mFilesDirPath);
        if (fileDir.exists()) {
            clearWorkspaceFile();
            fileDir.delete();
        }
    }

    @Override
    public boolean isApplyMultiExtruder() {
        return mApplyMultiExtruder;
    }

    @Override
    public void setApplyMultiExtruder(boolean applyMultiExtruder) {
        runWorkspaceMutation(() -> mApplyMultiExtruder = applyMultiExtruder);
    }

}
