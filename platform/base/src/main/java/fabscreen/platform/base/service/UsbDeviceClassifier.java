package fabscreen.platform.base.service;

/** Classifies USB peripherals without treating every USB host device as storage. */
final class UsbDeviceClassifier {
    static final int USB_CLASS_MASS_STORAGE = 8;

    private UsbDeviceClassifier() {
    }

    static boolean isMassStorage(int deviceClass, int... interfaceClasses) {
        if (deviceClass == USB_CLASS_MASS_STORAGE) return true;
        if (interfaceClasses == null) return false;

        for (int interfaceClass : interfaceClasses) {
            if (interfaceClass == USB_CLASS_MASS_STORAGE) return true;
        }
        return false;
    }
}
