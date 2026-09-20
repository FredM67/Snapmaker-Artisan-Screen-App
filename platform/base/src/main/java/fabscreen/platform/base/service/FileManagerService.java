package fabscreen.platform.base.service;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.storage.StorageManager;

import com.orhanobut.logger.Logger;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

import fabscreen.platform.base.R;
import fabscreen.platform.base.helper.FileHelper;
import fabscreen.platform.base.instantiation.IServiceIdentifier;
import fabscreen.platform.base.instantiation.ServiceContainer;
import fabscreen.platform.base.lib.file.FabLocalPartition;
import fabscreen.platform.base.lib.file.FabUsbPartition;
import fabscreen.platform.base.lib.file.IPartition;
import fabscreen.platform.base.receiver.UsbBroadcastReceiver;
import fabscreen.platform.base.view.SuperToastHelper;
import fabscreen.platform.lib.LogHelper;
import fabscreen.platform.lib.SubjectHolder;
import io.reactivex.Observable;
import io.reactivex.disposables.Disposable;
import io.reactivex.subjects.BehaviorSubject;

public class FileManagerService implements IFileManagerService, UsbBroadcastReceiver.UsbListener, IServiceIdentifier {
    private Context mContext;

    Method mGetFsUuidMethod;
    Method mGetStateMethod;
    Field mFsLabelField;
    Field mPathField;
    Field mInternalPath;

    private List<IPartition> mFabUsbPartitions = new ArrayList<>();
    private StorageManager mStorageManager;
    private UsbBroadcastReceiver mUSBReceiver;

    private BehaviorSubject<Boolean> mIsHaveUsbDevices = BehaviorSubject.createDefault(false);
    private SubjectHolder<Boolean> mUSBStateEventsSubject = new SubjectHolder<>(mIsHaveUsbDevices);

    public FileManagerService(IPreferences preferences) {
        mContext = ServiceContainer.getInstance().getService(IAppService.class).getAppContext();
        if (!preferences.getHelper().getFactoryUSBOFF()) {
            mStorageManager = ServiceContainer.getInstance().getService(IAppService.class).getAppContext().getSystemService(StorageManager.class);
            try {
                Class volumeInfoClazz = Class.forName("android.os.storage.VolumeInfo");
                mGetFsUuidMethod = volumeInfoClazz.getMethod("getFsUuid");
                mGetStateMethod = volumeInfoClazz.getMethod("getState");
                mFsLabelField = volumeInfoClazz.getDeclaredField("fsLabel");
                mPathField = volumeInfoClazz.getDeclaredField("path");
                mInternalPath = volumeInfoClazz.getDeclaredField("internalPath");
            } catch (Exception e) {
                LogHelper.log(e);
            }
            registerReceiver();
            // StorageManager.registerListener is hide ,
            Disposable sub = mIsHaveUsbDevices
                    .doOnNext(usbDevice -> Logger.d("mIsHaveUsbDevices " + usbDevice))
                    .filter(aBoolean -> aBoolean)
                    .flatMap(aBoolean -> Observable.intervalRange(1, 20, 500, 500, TimeUnit.MILLISECONDS))
                    .subscribe(time -> {
                        if (mFabUsbPartitions.isEmpty()) {
                            checkUSBPartitions(addUsbPartition());
                        }
                    }, LogHelper::log);
        }
    }

    @Override
    public void isHaveUsbDevices() {
        UsbManager manager = (UsbManager) mContext.getSystemService(Context.USB_SERVICE);
        if (manager == null) {
            updateMassStoragePresence(false);
            return;
        }
        HashMap<String, UsbDevice> deviceList = manager.getDeviceList();
        boolean hasMassStorage = false;
        for (UsbDevice device : deviceList.values()) {
            if (isMassStorageDevice(device)) {
                hasMassStorage = true;
                break;
            }
        }
        Logger.d("mass storage device present " + hasMassStorage);
        updateMassStoragePresence(hasMassStorage);
    }

    private void updateMassStoragePresence(boolean hasMassStorage) {
        if (hasMassStorage) {
            mIsHaveUsbDevices.onNext(true);
            return;
        }
        if (!mFabUsbPartitions.isEmpty()) {
            showDeviceState(false);
        }
        mFabUsbPartitions.clear();
        mIsHaveUsbDevices.onNext(false);
    }

    private boolean isMassStorageDevice(UsbDevice usbDevice) {
        if (usbDevice == null) return false;
        int[] interfaceClasses = new int[usbDevice.getInterfaceCount()];
        for (int i = 0; i < usbDevice.getInterfaceCount(); i++) {
            UsbInterface usbInterface = usbDevice.getInterface(i);
            interfaceClasses[i] = usbInterface == null ? -1 : usbInterface.getInterfaceClass();
        }
        return UsbDeviceClassifier.isMassStorage(usbDevice.getDeviceClass(), interfaceClasses);
    }

    private void checkUSBPartitions(List<IPartition> iPartitions) {
        Logger.d("check USB Partitions " + iPartitions.isEmpty());
        if (!iPartitions.isEmpty()) {
            for (int i = 0; i < mFabUsbPartitions.size(); i++) {
                FabUsbPartition usbPartition = (FabUsbPartition) mFabUsbPartitions.get(i);
                for (int j = 0; j < iPartitions.size(); j++) {
                    FabUsbPartition usbIPartition = (FabUsbPartition) iPartitions.get(j);
                    if (usbPartition.getUuid().equals(usbIPartition.getUuid())) {
                        iPartitions.set(j, usbPartition);
                    }
                }
            }
        }
        clearThumbnailToCache();
        if (mFabUsbPartitions.size() > iPartitions.size()) {
            showDeviceState(false);
        } else if (mFabUsbPartitions.size() < iPartitions.size()) {
            showDeviceState(true);
        }
        mFabUsbPartitions = iPartitions;
    }

    private List<IPartition> addUsbPartition() throws Exception {
        List<IPartition> usbPartitionList = new ArrayList<>();
        List<?> volumes = (List<?>) mStorageManager.getClass().getMethod("getVolumes").invoke(mStorageManager);
        if (volumes != null && volumes.size() >= 3) {
            for (int i = 0; i < volumes.size(); i++) {
                Object volumeInfo = volumes.get(i);
                String uuid = (String) mGetFsUuidMethod.invoke(volumeInfo);
                int state = (int) mGetStateMethod.invoke(volumeInfo);
                // https://cs.android.com/android/platform/superproject/+/master:system/vold/binder/android/os/IVold.aidl?q=VOLUME_STATE_UNMOUNTED
                if ((uuid != null && !uuid.isEmpty()) && (state == 2 || state == 3)) {
                    String fsLabelString = (String) mFsLabelField.get(volumeInfo);
                    String internalPathString = (String) mInternalPath.get(volumeInfo);
                    FabUsbPartition fabUsbPartition = new FabUsbPartition(mContext, internalPathString, uuid);
                    usbPartitionList.add(fabUsbPartition);
                }
            }
        }
        return usbPartitionList;
    }

    private void registerReceiver() {
        mUSBReceiver = new UsbBroadcastReceiver();
        mUSBReceiver.setUsbListener(this);

        IntentFilter usbDeviceStateFilter = new IntentFilter();
        usbDeviceStateFilter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        usbDeviceStateFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        usbDeviceStateFilter.addAction(UsbBroadcastReceiver.ACTION_USB_PERMISSION);
        mContext.registerReceiver(mUSBReceiver, usbDeviceStateFilter);
    }

    @Override
    public FabLocalPartition getFabLocalStorageDevice() {
        return new FabLocalPartition(mContext.getFilesDir().getPath());
    }

    // should use IPartition
    @Override
    public FabUsbPartition getFabUsbDevice() {
        Logger.d("getFabUsbDevice");
        if (mFabUsbPartitions == null || mFabUsbPartitions.size() == 0) {
            return null;
        } else {
            return (FabUsbPartition) mFabUsbPartitions.get(0);
        }
    }

    @Override
    public IPartition getDevice(boolean isLocal) {
        return isLocal ? getFabLocalStorageDevice() : getFabUsbDevice();
    }

    @Override
    public void deviceAttached(UsbDevice usbDevice) throws Exception {
        Logger.d("USB deviceAttached: " + usbDevice);
        if (!isMassStorageDevice(usbDevice)) {
            Logger.d("Ignoring non-storage USB device attachment");
            return;
        }
        UsbManager usbManager = (UsbManager) mContext.getSystemService(Context.USB_SERVICE);
        if (usbManager == null) {
            return;
        }
        if (usbManager.hasPermission(usbDevice)) {
            isHaveUsbDevices();
        } else {
            PendingIntent pendingIntent = PendingIntent.getBroadcast(mContext, 0, new Intent(UsbBroadcastReceiver.ACTION_USB_PERMISSION), 0);
            Logger.d("Start request USB permission...");
            usbManager.requestPermission(usbDevice, pendingIntent);
        }
    }

    @Override
    public void deviceDetached(UsbDevice usbDevice) {
        Logger.d("Device detached " + usbDevice);
        if (!isMassStorageDevice(usbDevice)) {
            Logger.d("Ignoring non-storage USB device detachment");
            return;
        }
        isHaveUsbDevices();
    }

    /**
     * TODO: Different USB partitions are deleted independently
     */
    private void clearThumbnailToCache() {
        File cacheDir = ServiceContainer.getInstance().getService(IAppService.class).getAppContext().getCacheDir();
        String folderPath = cacheDir.getAbsolutePath() + "/gcode_thumbnail/USB";
        File file = new File(folderPath);
        if (file.exists()) {
            FileHelper.removeFile(file);
        }
    }

    private void showDeviceState(boolean isAttach) {
        try {
            for (int i = 0; i < mFabUsbPartitions.size(); i++) {
                Logger.d("Partitions index:" + i + "\t" + ((FabUsbPartition) mFabUsbPartitions.get(i)).toString());
            }
        } catch (Exception e) {
        }
        Context nowViewContext = ServiceContainer.getInstance().getService(IAppService.class).getNowViewContext();
        int mSeriesId = ServiceContainer.getInstance().getService(IMachine.class).getMachineInfoSubjectHolder().getValue().seriesId;
        if (IMachine.MachineSeries.J == mSeriesId) {
            new SuperToastHelper.Builder()
                    .setToastForSingleLogo(isAttach ? R.drawable.pic_j1_usb_insert_normal_160x160 : R.drawable.pic_j1_usb_pull_out_normal_160x160)
                    .build()
                    .showToast(nowViewContext);
        } else {
            new SuperToastHelper.Builder()
                    .setDrawable(isAttach ? R.drawable.ic_usb_detected : R.drawable.ic_usb_unplugged)
                    .setMessage(nowViewContext.getString(isAttach ? R.string.toast_usb_device_detected : R.string.toast_usb_device_unpuggled))
                    .build()
                    .showToast(nowViewContext);
        }
    }

    @Override
    public void devicePermissionGranted(UsbDevice usbDevice) {
        Logger.d("USB devicePermissionGranted");
        if (!isMassStorageDevice(usbDevice)) {
            Logger.d("Ignoring permission result for non-storage USB device");
            return;
        }
        isHaveUsbDevices();
    }

    @Override
    public void devicePermissionDenied(UsbDevice usbDevice) {
        //FIXME:NOT TO DO EVENING
        Logger.d("USB devicePermissionDenied");
    }

    //TODO: redefine filemanagerstate
    @Override
    public SubjectHolder<Boolean> getFileManagerStateSubjHolder() {
        return mUSBStateEventsSubject;
    }

    @Override
    public void closeFabUsbDevices() {
        // TODO: Consider canceling the mount operation
    }
}

