package fabscreen.platform.base.lib.parser;

import android.graphics.Bitmap;

import java.io.InputStream;

import fabscreen.platform.base.lib.file.IFile;
import fabscreen.platform.base.model.ModelBoundary;
import fabscreen.platform.base.service.IMachine;
import io.reactivex.Observable;


public interface IGcodeParser {

    void startParse(String filePath, boolean isLocal, IMachine.WorkType fileType);

    void startParse(IFile file, IMachine.WorkType fileType);

    /**
     * Pass in the Gcode file inputStream
     */
    void startParse(InputStream io, IMachine.WorkType fileType);

    void destroy();

    IMachine.WorkType getFileType();

    Bitmap getGcodeThumbnail();

    int getTotalLinesCount();

    float getEstimatedTime();

    float getBedTargetTemperature();

    float getNozzleTargetTemperature();

    float getPower();

//    float getCNCPower();

    float getSpindleSpeed();

    float getWorkSpeed();

    float getJogSpeed();

    float getDiameter();

    ModelBoundary getBoundary();

    Observable<Integer> getParseProgressObservable();

    int getHeaderType();

    int getHeaderNameID();

    float getNozzle_0_Diameter();

    float getNozzle_1_Diameter();

    int getLayerNumber();

    float getLayerHeight();

    float getMaterialWeight();

    float getMaterialLength();

    String getMaterial_0();

    String getMaterial_1();

    String getRenderMethod();

    int isContainRotation();

    float getWorkSizeX();

    float getWorkSizeY();

    String getOrigin();

    float getNozzleTarget_1_Temperature();

    int getCustomPrintMode();

    /** Exact SHA-256 of the complete byte stream, or null until a full successful scan ends. */
    String getLastSha256ForAnalysis();

    /** Exact MD5 of the complete byte stream, or null until a full successful scan ends. */
    String getLastMd5ForAnalysis();

    /** True only after the complete executable has been inspected for physical nozzle use. */
    boolean isToolUsageConfirmed();

    /** Whether the complete job can operate the physical left nozzle. */
    boolean isTool0Used();

    /** Whether the complete job can operate the physical right nozzle. */
    boolean isTool1Used();

    boolean isApplyMultiExtruder();

    float getExtruder0RetractionDistance();

    float getExtruder1RetractionDistance();

    float getExtruder0SwitchRetractionDistance();

    float getExtruder1SwitchRetractionDistance();
}
