package fabscreen.platform.base.service;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UsbDeviceClassifierTest {
    @Test
    public void acceptsDeviceLevelMassStorageClass() {
        assertTrue(UsbDeviceClassifier.isMassStorage(
                UsbDeviceClassifier.USB_CLASS_MASS_STORAGE
        ));
    }

    @Test
    public void acceptsCompositeDeviceWithMassStorageInterface() {
        assertTrue(UsbDeviceClassifier.isMassStorage(0, 3, 8));
    }

    @Test
    public void rejectsUvcVideoAndAudioInterfaces() {
        assertFalse(UsbDeviceClassifier.isMassStorage(239, 14, 14, 1));
    }

    @Test
    public void rejectsUnknownDeviceWithoutInterfaces() {
        assertFalse(UsbDeviceClassifier.isMassStorage(0));
        assertFalse(UsbDeviceClassifier.isMassStorage(0, (int[]) null));
    }
}
