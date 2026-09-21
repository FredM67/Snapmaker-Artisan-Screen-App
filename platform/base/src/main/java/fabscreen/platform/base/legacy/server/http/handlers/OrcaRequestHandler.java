package fabscreen.platform.base.legacy.server.http.handlers;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Base64;

import com.orhanobut.logger.Logger;
import com.yanzhenjie.andserver.annotation.GetMapping;
import com.yanzhenjie.andserver.annotation.PostMapping;
import com.yanzhenjie.andserver.annotation.RequestParam;
import com.yanzhenjie.andserver.annotation.RestController;
import com.yanzhenjie.andserver.framework.body.JsonBody;
import com.yanzhenjie.andserver.framework.body.StreamBody;
import com.yanzhenjie.andserver.framework.body.StringBody;
import com.yanzhenjie.andserver.http.HttpRequest;
import com.yanzhenjie.andserver.http.HttpResponse;
import com.yanzhenjie.andserver.http.ResponseBody;
import com.yanzhenjie.andserver.http.multipart.MultipartFile;
import com.yanzhenjie.andserver.util.MediaType;
import com.yanzhenjie.andserver.util.StatusCode;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import fabscreen.platform.base.R;
import fabscreen.platform.base.camera.UvcCameraManager;
import fabscreen.platform.base.helper.Md5Util;
import fabscreen.platform.base.instantiation.ServiceContainer;
import fabscreen.platform.base.lib.file.FabLocalFile;
import fabscreen.platform.base.lib.file.FabUsbPartition;
import fabscreen.platform.base.lib.file.FabUsbFile;
import fabscreen.platform.base.lib.file.IFile;
import fabscreen.platform.base.lib.parser.GcodeParser;
import fabscreen.platform.base.lib.parser.IGcodeParser;
import fabscreen.platform.base.lib.print.IPrintWorkspace;
import fabscreen.platform.base.model.ModelBoundary;
import fabscreen.platform.base.model.HTTPEventBus;
import fabscreen.platform.base.service.IAppService;
import fabscreen.platform.base.service.IFileManagerService;
import fabscreen.platform.base.service.IMachine;
import fabscreen.platform.base.service.INetwork;
import fabscreen.platform.base.service.IObicoService;
import fabscreen.platform.base.service.IPreferences;
import fabscreen.platform.base.service.IRouter;
import fabscreen.platform.base.service.machine.MachineInfo;
import fabscreen.platform.base.service.machine.MachineStatus;
import fabscreen.platform.base.service.machine.Vector;
import fabscreen.platform.base.service.machine.controller.FDMController;
import fabscreen.platform.base.service.machine.controller.ErrorController;
import fabscreen.platform.base.service.machine.controller.MachineController;
import fabscreen.platform.base.service.machine.controller.MachineOperationStatus;
import fabscreen.platform.base.service.machine.controller.NewPrintController;
import fabscreen.platform.base.service.machine.controller.PrintEventState;
import fabscreen.platform.base.service.machine.entity.Module;
import fabscreen.platform.base.service.machine.entity.module.Enclosure;
import fabscreen.platform.base.service.machine.entity.module.HeatedBed;
import fabscreen.platform.base.service.machine.entity.parts.Extruder;
import fabscreen.platform.base.service.machine.entity.parts.Fan;
import fabscreen.platform.base.service.machine.entity.toolhead.FdmToolhead;
import fabscreen.platform.base.service.machine.structure.ResponseStructure;
import fabscreen.platform.base.service.machine.structure.prop.StringProp;
import fabscreen.platform.base.service.machine.structure.prop.UInt8Prop;
import fabscreen.platform.base.view.SuperToastHelper;
import fabscreen.platform.lib.LogHelper;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import io.reactivex.subjects.SingleSubject;
import okio.Buffer;

@RestController
class OrcaRequestHandler {
    private static final String URI_LOCAL_FILE = "/api/files/local";
    private static final String URI_CHECK_VERSION = "/api/version";
    private static final String URI_DASHBOARD_STATUS = "/api/dashboard/status";
    private static final String URI_DASHBOARD_TELEMETRY_HISTORY =
            "/api/dashboard/telemetry/history";
    private static final String URI_DASHBOARD_THUMBNAIL = "/api/dashboard/thumbnail";
    private static final String URI_DASHBOARD_PAUSE = "/api/dashboard/job/pause";
    private static final String URI_DASHBOARD_RESUME = "/api/dashboard/job/resume";
    private static final String URI_DASHBOARD_CANCEL = "/api/dashboard/job/cancel";
    private static final String URI_DASHBOARD_FILES = "/api/dashboard/files";
    private static final String URI_DASHBOARD_FILES_UPLOAD = "/api/dashboard/files/upload";
    private static final String URI_DASHBOARD_FILES_DETAIL = "/api/dashboard/files/detail";
    private static final String URI_DASHBOARD_FILES_THUMBNAIL = "/api/dashboard/files/thumbnail";
    private static final String URI_DASHBOARD_FILES_LIST_THUMBNAIL =
            "/api/dashboard/files/list-thumbnail";
    private static final String URI_DASHBOARD_FILES_START = "/api/dashboard/files/start";
    private static final String URI_DASHBOARD_FILES_START_STATUS =
            "/api/dashboard/files/start-status";
    private static final String URI_DASHBOARD_CONSOLE_HISTORY = "/api/dashboard/console/history";
    private static final String URI_DASHBOARD_CONSOLE_SEND = "/api/dashboard/console/send";
    private static final String URI_DASHBOARD_BED_MESH = "/api/dashboard/bed-mesh";
    private static final String URI_DASHBOARD_BED_MESH_REFRESH = "/api/dashboard/bed-mesh/refresh";
    private static final String URI_DASHBOARD_ENCLOSURE = "/api/dashboard/enclosure";
    private static final String URI_DASHBOARD_ENCLOSURE_LED = "/api/dashboard/enclosure/led";
    private static final String URI_DASHBOARD_ENCLOSURE_FAN = "/api/dashboard/enclosure/fan";
    private static final String URI_DASHBOARD_CAMERA_STATUS = "/api/dashboard/camera/status";
    private static final String URI_DASHBOARD_CAMERA_SETTINGS = "/api/dashboard/camera/settings";
    private static final String URI_DASHBOARD_CAMERA_RESCAN = "/api/dashboard/camera/rescan";
    private static final String URI_DASHBOARD_CAMERA_FRAME = "/api/dashboard/camera/frame";
    private static final String URI_DASHBOARD_CAMERA_STOP = "/api/dashboard/camera/stop";
    private static final String URI_DASHBOARD_OBICO_CONFIG = "/api/dashboard/obico/config";
    private static final String URI_DASHBOARD_OBICO_LINK = "/api/dashboard/obico/link";
    private static final String URI_DASHBOARD_OBICO_TEST = "/api/dashboard/obico/test";
    private static final String URI_DASHBOARD_OBICO_DISCONNECT = "/api/dashboard/obico/disconnect";
    private static final String URI_ROOT = "/";
    private static final String URI_FAVICON_32 = "/favicon-32.png";
    private static final String URI_APPLE_TOUCH_ICON = "/apple-touch-icon.png";
    private static final String URI_ICON_192 = "/icon-192.png";
    private static final String URI_ICON_512 = "/icon-512.png";
    private static final String URI_WEB_MANIFEST = "/manifest.webmanifest";
    private static final String DASHBOARD_REQUEST_HEADER = "X-Artisan-Dashboard";
    private static final long TELEMETRY_REFRESH_INTERVAL_MS = 2_000L;
    private static final long ACTION_DEBOUNCE_MS = 1_000L;
    private static final long ACTION_TIMEOUT_MS = 20_000L;
    private static final long CONSOLE_TIMEOUT_MS = 5_000L;
    private static final long CONSOLE_BACKPRESSURE_MS = 500L;
    private static final long MESH_TIMEOUT_MS = 15_000L;
    private static final long ENCLOSURE_CONTROL_TIMEOUT_MS = 5_000L;
    private static final long ENCLOSURE_REFRESH_TIMEOUT_MS = 3_000L;
    private static final long THERMAL_REFRESH_TIMEOUT_MS = 10_000L;
    private static final long ENCLOSURE_TELEMETRY_MAX_AGE_MS = 30_000L;
    private static final long CAMERA_FRAME_WAIT_MS = 2_000L;
    private static final long THERMAL_TELEMETRY_MAX_AGE_MS = 30_000L;
    private static final int MAX_CONSOLE_COMMAND_BYTES = 79;
    private static final int MAX_CONSOLE_RESPONSE_LENGTH = 128 * 1024;
    private static final int MAX_CONSOLE_HISTORY = 300;
    private static final int MAX_LISTED_GCODE_FILES = 2_000;
    private static final int MAX_VISITED_FILE_ENTRIES = 10_000;
    private static final int MAX_FILE_SCAN_DEPTH = 16;
    private static final long FILE_SCAN_TIMEOUT_MS = 1_500L;
    private static final int MAX_CACHED_FILE_ANALYSES = 8;
    private static final int MAX_CACHED_LIST_THUMBNAILS = 48;
    private static final long MAX_CACHED_LIST_THUMBNAIL_BYTES = 8L * 1024L * 1024L;
    private static final int MAX_LIST_THUMBNAIL_EDGE_PX = 192;
    private static final int MAX_LIST_THUMBNAIL_OUTPUT_BYTES = 512 * 1024;
    private static final int MAX_CONCURRENT_LIST_THUMBNAIL_SCANS = 2;
    private static final long LIST_THUMBNAIL_POSITIVE_TTL_MS = 5L * 60L * 1000L;
    private static final long LIST_THUMBNAIL_NEGATIVE_TTL_MS = 60L * 1000L;
    private static final int LIST_THUMBNAIL_RETRY_AFTER_MS = 350;
    private static final int MAX_ORCA_QUICK_PREFIX_BYTES = 8 * 1024 * 1024;
    private static final int MAX_ORCA_QUICK_FOOTER_BYTES = 8 * 1024 * 1024;
    private static final long STAGED_FILE_MAX_IDLE_AGE_MS = 24L * 60L * 60L * 1000L;
    private static final long MAX_GCODE_UPLOAD_BYTES = 1024L * 1024L * 1024L;
    private static final long LOCAL_STORAGE_RESERVE_BYTES = 300L * 1024L * 1024L;
    private static final String NOZZLE_DIAMETER_MISMATCH_ID =
            "inconsistent_nozzle_diameter";

    private final CompositeDisposable mDisposable = new CompositeDisposable();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final String mDashboardToken = UUID.randomUUID().toString();
    private final Object mThumbnailLock = new Object();
    private final Object mActionLock = new Object();
    private static final Object sTelemetryLock = new Object();
    private final Object mConsoleLock = new Object();
    private final Object mUploadLock = new Object();
    private final Object mFileDetailLock = new Object();
    private final Object mFileStartLock = new Object();
    private final Object mEnclosureControlLock = new Object();
    private static volatile long sLastTelemetryRefreshAt;
    private long mLastActionAt;
    private long mNextActionId;
    private long mPendingActionId;
    private boolean mActionPending;
    private String mLastAction = "";
    private String mLastActionResult = "";
    private int mLastActionErrorCode;
    private long mLastActionCompletedAt;
    private Disposable mDashboardActionDisposable;
    private Runnable mDashboardActionTimeout;
    private static Disposable sToolheadRefreshDisposable;
    private static Disposable sBedRefreshDisposable;
    private static Disposable sEnclosureRefreshDisposable;
    private Disposable mFirmwareLogDisposable;
    private Disposable mMeshCaptureDisposable;
    private Runnable mMeshTimeoutRunnable;
    private Bitmap mCachedThumbnailBitmap;
    private byte[] mCachedThumbnailBytes;
    private boolean mConsoleCommandPending;
    private long mNextConsoleEntryId;
    private final List<DashboardConsoleEntry> mConsoleHistory = new ArrayList<>();
    private volatile DashboardBedMeshParser.Result mLastBedMesh =
            DashboardBedMeshParser.Result.unavailable(
                    "Select Refresh mesh to request M420 V from the controller.",
                    ""
            );
    private volatile long mLastBedMeshAt;
    private volatile long mLastBedMeshAttemptAt;
    private volatile boolean mLastBedMeshStale;
    private volatile String mLastBedMeshError = "";
    private boolean mMeshRefreshPending;
    private long mMeshRequestId;
    private boolean mEnclosureControlPending;
    private long mNextEnclosureControlId;
    private long mEnclosureControlId;
    private String mEnclosureControlTarget = "";
    private int mEnclosureControlRequestedPercent;
    private String mEnclosureControlResult = "";
    private String mEnclosureControlError = "";
    private int mEnclosureControlErrorCode;
    private long mEnclosureControlCompletedAt;
    private long mNextFileDetailId;
    private long mFileDetailWatchdogGeneration;
    private DashboardFileDetailState mFileDetailState;
    private GcodeParser mFileDetailParser;
    private Disposable mFileDetailDigestDisposable;
    private Disposable mFileDetailParseDisposable;
    private Runnable mFileDetailTimeout;
    private InputStream mFileDetailAnalysisInput;
    private final DashboardFileAnalysisCache<DashboardCachedFileAnalysis>
            mFileAnalysisCache = new DashboardFileAnalysisCache<>(MAX_CACHED_FILE_ANALYSES);
    private final DashboardListThumbnailCache mListThumbnailCache =
            new DashboardListThumbnailCache(
                    MAX_CACHED_LIST_THUMBNAILS,
                    MAX_CACHED_LIST_THUMBNAIL_BYTES,
                    MAX_CONCURRENT_LIST_THUMBNAIL_SCANS,
                    LIST_THUMBNAIL_POSITIVE_TTL_MS,
                    LIST_THUMBNAIL_NEGATIVE_TTL_MS
            );
    private long mNextFileStartId;
    private DashboardFileStartState mFileStartState;
    private Disposable mFileStartMd5Disposable;
    private Disposable mFileStartCopyDisposable;
    private Disposable mFileStartEventDisposable;
    private Disposable mFileStartBedDisposable;
    private Disposable mFileStartCleanupDisposable;
    private Runnable mFileStartTimeout;
    private InputStream mFileStartStagingInput;
    private boolean mFileStartRemoteGateClaimed;

    @PostMapping(path = URI_LOCAL_FILE)
    void uploadFile(HttpRequest request, HttpResponse response,
                    @RequestParam(name = "file") MultipartFile file) {
        String isNeedPrint = request.getParameter("print");
        if (isNeedPrint == null) {
            response.setStatus(HttpResponse.SC_BAD_REQUEST);
            response.setBody(new StringBody("parameter \"print\" not found"));
            return;
        }
        if (!MachineOperationStatus.SYSTEM_STATUS_IDLE.valueEquals(ServiceContainer.getInstance().getService(IMachine.class).getNewPrintController().getPrintState()) && isNeedPrint.equals("true")) {
            response.setStatus(HttpResponse.SC_SERVICE_UNAVAILABLE);
            response.setBody(new StringBody("Machine is not in idle status"));
            return;
        }

        if (file.isEmpty()) {
            response.setBody(new StringBody("Empty file body"));
            response.setStatus(HttpResponse.SC_BAD_REQUEST);
            return;
        }

        if (file.getFilename() == null) {
            response.setBody(new StringBody("Empty file name"));
            response.setStatus(HttpResponse.SC_BAD_REQUEST);
            return;
        }
        String uploadFilename = file.getFilename().trim();
        if (!isSafeGcodeFilename(uploadFilename)) {
            response.setBody(new StringBody("Invalid file name"));
            response.setStatus(HttpResponse.SC_BAD_REQUEST);
            return;
        }
        long uploadSize = file.getSize();
        if (uploadSize > MAX_GCODE_UPLOAD_BYTES) {
            response.setBody(new StringBody("G-code uploads are limited to 1 GiB."));
            response.setStatus(413);
            return;
        }
        ErrorController.EmergencyStopState emergencyStopState = ServiceContainer.getInstance().getService(IAppService.class).getEmergencyStopState();
        switch (emergencyStopState) {
            case EMERGENCY_STOP_STATE_RELEASE:
            case EMERGENCY_STOP_STATE_PRESS:
                response.setStatus(HttpResponse.SC_SERVICE_UNAVAILABLE);
                response.setBody(new StringBody("Machine is not in idle status"));
                Logger.d("Current in emergency stop state, stop receiving file.");
                return;
            case EMERGENCY_STOP_STATE_NORMAL:
            default:
                break;
        }

        File targetFile;
        try {
            synchronized (mUploadLock) {
                File filesDir = ServiceContainer.getInstance()
                        .getService(IAppService.class)
                        .getFilesDir()
                        .getCanonicalFile();
                if (uploadSize > 0L
                        && filesDir.getUsableSpace() - uploadSize < LOCAL_STORAGE_RESERVE_BYTES) {
                    response.setBody(new StringBody(
                            "Not enough storage while preserving the 300 MiB reserve."
                    ));
                    response.setStatus(507);
                    return;
                }
                targetFile = new File(filesDir, uploadFilename).getCanonicalFile();
                if (!filesDir.equals(targetFile.getParentFile())) {
                    response.setBody(new StringBody("Invalid file name"));
                    response.setStatus(HttpResponse.SC_BAD_REQUEST);
                    return;
                }
                file.transferTo(targetFile);
            }
//            HTTPEventBus.getInstance().onReceiveFile(targetFile);
        } catch (IOException e) {
            response.setStatus(HttpResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }

        IAppService appService = ServiceContainer.getInstance().getService(IAppService.class);
        response.setBody(new StringBody("Upload successfully."));
        new SuperToastHelper.Builder()
                .setDrawable(R.drawable.ic_pic_a400_success_68x68)
                .setTitle(appService.getNowViewContext().getString(R.string.all_remote_toast_file_received))
                .setMessage(uploadFilename)
                .build()
                .showToast(appService.getNowViewContext());

        CountDownLatch countDownLatch = new CountDownLatch(1);
        if (isNeedPrint.equals("true")) {
            Disposable sub = startPrint(targetFile).subscribe(success -> {
                if (success) {
                    response.setStatus(StatusCode.SC_OK);
                } else {
                    response.setStatus(StatusCode.SC_SERVICE_UNAVAILABLE);
                }
                countDownLatch.countDown();
            }, e -> {
                response.setStatus(HttpResponse.SC_INTERNAL_SERVER_ERROR);
                countDownLatch.countDown();
            });
            mDisposable.add(sub);
        } else {
            countDownLatch.countDown();
        }

        try {
            countDownLatch.await();
        } catch (InterruptedException e) {
            response.setBody(new StringBody("Interrupted."));
            response.setStatus(HttpResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    @GetMapping(URI_CHECK_VERSION)
    void checkVersion(HttpResponse response) {
        JSONObject versionResponse = new JSONObject();
        try {
            versionResponse.put("api", "0.1");
            versionResponse.put("server", "1.2.3");
            versionResponse.put("text", "OctoPrint 1.2.3/Screen Dummy");
            versionResponse.put("machineConnection", true);
            final ResponseBody body = new JsonBody(versionResponse);
            response.setBody(body);
            response.setStatus(StatusCode.SC_OK);
        } catch (JSONException e) {
            LogHelper.log(e);
        }
    }

    @GetMapping(value = URI_ROOT, produces = "text/html; charset=utf-8")
    void getRoot(HttpResponse response) {
        try {
            String html = loadDashboardAsset();
            response.setHeader("Content-Type", "text/html; charset=utf-8");
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("X-Frame-Options", "DENY");
            response.setHeader("Referrer-Policy", "no-referrer");
            response.setHeader(
                    "Content-Security-Policy",
                    DashboardContentSecurityPolicy.VALUE
            );
            response.setStatus(StatusCode.SC_OK);
            response.setBody(new StringBody(html));
        } catch (IOException e) {
            LogHelper.log(e);
            response.setStatus(StatusCode.SC_INTERNAL_SERVER_ERROR);
            response.setBody(new StringBody("<!doctype html><html><body>Dashboard unavailable.</body></html>"));
        }
    }

    @GetMapping(value = URI_FAVICON_32, produces = "image/png")
    void getDashboardFavicon(HttpResponse response) {
        writeDashboardStaticAsset(response, "artisan-icon-32.png", MediaType.IMAGE_PNG);
    }

    @GetMapping(value = URI_APPLE_TOUCH_ICON, produces = "image/png")
    void getDashboardTouchIcon(HttpResponse response) {
        writeDashboardStaticAsset(response, "artisan-icon-180.png", MediaType.IMAGE_PNG);
    }

    @GetMapping(value = URI_ICON_192, produces = "image/png")
    void getDashboardIcon192(HttpResponse response) {
        writeDashboardStaticAsset(response, "artisan-icon-192.png", MediaType.IMAGE_PNG);
    }

    @GetMapping(value = URI_ICON_512, produces = "image/png")
    void getDashboardIcon512(HttpResponse response) {
        writeDashboardStaticAsset(response, "artisan-icon-512.png", MediaType.IMAGE_PNG);
    }

    @GetMapping(value = URI_WEB_MANIFEST, produces = "application/manifest+json")
    void getDashboardManifest(HttpResponse response) {
        writeDashboardStaticAsset(response, "artisan.webmanifest",
                MediaType.parseMediaType("application/manifest+json"));
    }

    private void writeDashboardStaticAsset(
            HttpResponse response, String assetName, MediaType mediaType) {
        response.setHeader("Cache-Control", "public, max-age=3600");
        response.setHeader("X-Content-Type-Options", "nosniff");
        try (InputStream input = ServiceContainer.getInstance().getService(IAppService.class)
                .getAppContext().getAssets().open(assetName)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            byte[] content = output.toByteArray();
            response.setStatus(StatusCode.SC_OK);
            response.setBody(new StreamBody(
                    new ByteArrayInputStream(content), content.length, mediaType));
        } catch (IOException e) {
            LogHelper.log(e);
            response.setStatus(StatusCode.SC_INTERNAL_SERVER_ERROR);
        }
    }

    @GetMapping(path = URI_DASHBOARD_STATUS)
    void getDashboardStatus(HttpResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        try {
            IMachine machine = ServiceContainer.getInstance().getService(IMachine.class);
            maybeRefreshTelemetry(machine);
            writeJson(response, StatusCode.SC_OK, buildDashboardStatus(machine));
        } catch (Exception e) {
            LogHelper.log(e);
            writeJsonError(response, StatusCode.SC_INTERNAL_SERVER_ERROR, "Unable to read printer status.");
        }
    }

    @GetMapping(path = URI_DASHBOARD_TELEMETRY_HISTORY)
    void getDashboardTelemetryHistory(HttpResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        try {
            writeJson(response, StatusCode.SC_OK,
                    DashboardTelemetryHistory.getInstance().snapshot());
        } catch (Exception e) {
            LogHelper.log(e);
            writeJsonError(response, StatusCode.SC_INTERNAL_SERVER_ERROR,
                    "Unable to read telemetry history.");
        }
    }

    @GetMapping(path = URI_DASHBOARD_THUMBNAIL)
    void getDashboardThumbnail(HttpResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");

        try {
            IMachine machine = ServiceContainer.getInstance().getService(IMachine.class);
            MachineInfo info = machine.getMachineInfoSubjectHolder().getValue();
            int state = machine.getNewPrintController().getPrintState();
            if (info == null || info.workType != IMachine.WorkType.FDM || !isJobState(state)) {
                response.setStatus(StatusCode.SC_NOT_FOUND);
                return;
            }

            Bitmap bitmap = ServiceContainer.getInstance().getService(IGcodeParser.class).getGcodeThumbnail();
            if (bitmap == null || bitmap.isRecycled()) {
                response.setStatus(StatusCode.SC_NOT_FOUND);
                return;
            }

            byte[] png;
            synchronized (mThumbnailLock) {
                if (bitmap != mCachedThumbnailBitmap || mCachedThumbnailBytes == null) {
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        response.setStatus(StatusCode.SC_INTERNAL_SERVER_ERROR);
                        return;
                    }
                    mCachedThumbnailBitmap = bitmap;
                    mCachedThumbnailBytes = output.toByteArray();
                }
                png = mCachedThumbnailBytes;
            }

            response.setStatus(StatusCode.SC_OK);
            response.setBody(new StreamBody(
                    new ByteArrayInputStream(png),
                    png.length,
                    MediaType.IMAGE_PNG
            ));
        } catch (Exception e) {
            LogHelper.log(e);
            response.setStatus(StatusCode.SC_INTERNAL_SERVER_ERROR);
        }
    }

    @PostMapping(path = URI_DASHBOARD_PAUSE)
    void pauseDashboardJob(HttpRequest request, HttpResponse response) {
        handleDashboardJobAction("pause", request, response);
    }

    @PostMapping(path = URI_DASHBOARD_RESUME)
    void resumeDashboardJob(HttpRequest request, HttpResponse response) {
        handleDashboardJobAction("resume", request, response);
    }

    @PostMapping(path = URI_DASHBOARD_CANCEL)
    void cancelDashboardJob(HttpRequest request, HttpResponse response) {
        handleDashboardJobAction("cancel", request, response);
    }

    @GetMapping(path = URI_DASHBOARD_FILES)
    void getDashboardFiles(HttpRequest request, HttpResponse response) {
        setDashboardApiHeaders(response);
        if (!isDashboardRequestAuthorized(request)) {
            writeJsonError(response, StatusCode.SC_FORBIDDEN, "Dashboard authorization failed.");
            return;
        }

        try {
            IAppService appService = ServiceContainer.getInstance().getService(IAppService.class);
            File localRoot = appService.getFilesDir().getCanonicalFile();
            List<DashboardFileInfo> localFiles = new ArrayList<>();
            DashboardFileScanBudget localScan = new DashboardFileScanBudget();
            collectLocalGcodeFiles(localRoot, localRoot, 0, localFiles, localScan);
            sortDashboardFiles(localFiles);

            IFileManagerService fileManager = ServiceContainer.getInstance()
                    .getService(IFileManagerService.class);
            FabUsbPartition usbPartition = fileManager.getFabUsbDevice();
            List<DashboardFileInfo> usbFiles = new ArrayList<>();
            DashboardFileScanBudget usbScan = new DashboardFileScanBudget();
            String usbError = "";
            if (usbPartition != null) {
                try {
                    IFile usbRoot = usbPartition.getRootFile();
                    if (usbRoot == null || !usbRoot.isDirectory()) {
                        throw new IOException("USB root is no longer mounted.");
                    }
                    collectUsbGcodeFiles(
                            usbRoot,
                            "",
                            0,
                            usbFiles,
                            usbScan
                    );
                    sortDashboardFiles(usbFiles);
                } catch (Exception usbFailure) {
                    LogHelper.log(usbFailure);
                    usbFiles.clear();
                    usbError = "USB storage became unavailable while it was being scanned.";
                }
            }

            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("timestamp", System.currentTimeMillis());
            result.put("local", filesToJson(localFiles));
            result.put("localTruncated", localScan.truncated);
            result.put("usb", filesToJson(usbFiles));
            result.put("usbConnected", usbPartition != null && TextUtils.isEmpty(usbError));
            result.put("usbTruncated", usbScan.truncated);
            result.put("usbError", usbError);
            result.put("thumbnailPolicy", dashboardListThumbnailPolicyJson());

            JSONObject storage = new JSONObject();
            JSONObject localStorage = new JSONObject();
            localStorage.put("freeBytes", localRoot.getFreeSpace());
            localStorage.put("totalBytes", localRoot.getTotalSpace());
            storage.put("local", localStorage);
            if (usbPartition != null && TextUtils.isEmpty(usbError)) {
                JSONObject usbStorage = new JSONObject();
                usbStorage.put("freeBytes", usbPartition.getFreeSpace());
                usbStorage.put("totalBytes", usbPartition.getTotalSpace());
                storage.put("usb", usbStorage);
            } else {
                storage.put("usb", JSONObject.NULL);
            }
            result.put("storage", storage);
            writeJson(response, StatusCode.SC_OK, result);
        } catch (Exception e) {
            LogHelper.log(e);
            writeJsonError(response, StatusCode.SC_INTERNAL_SERVER_ERROR,
                    "Unable to list G-code files.");
        }
    }

    @PostMapping(path = URI_DASHBOARD_FILES_UPLOAD)
    void uploadDashboardFile(
            HttpRequest request,
            HttpResponse response,
            @RequestParam(name = "file") MultipartFile file
    ) {
        setDashboardApiHeaders(response);
        if (!isDashboardRequestAuthorized(request)) {
            writeJsonError(response, StatusCode.SC_FORBIDDEN, "Dashboard authorization failed.");
            return;
        }
        if (file == null || file.isEmpty() || TextUtils.isEmpty(file.getFilename())) {
            writeJsonError(response, StatusCode.SC_BAD_REQUEST, "Select a non-empty G-code file.");
            return;
        }
        long uploadSize = file.getSize();
        if (uploadSize > MAX_GCODE_UPLOAD_BYTES) {
            writeJsonError(response, 413, "G-code uploads are limited to 1 GiB.");
            return;
        }

        String filename = file.getFilename().trim();
        if (!isSafeGcodeFilename(filename)) {
            writeJsonError(response, StatusCode.SC_BAD_REQUEST,
                    "Only a plain .gcode filename can be uploaded.");
            return;
        }

        synchronized (mUploadLock) {
            File temporary = null;
            try {
            File localRoot = ServiceContainer.getInstance()
                    .getService(IAppService.class)
                    .getFilesDir()
                    .getCanonicalFile();
            if (uploadSize > 0L
                    && localRoot.getUsableSpace() - uploadSize < LOCAL_STORAGE_RESERVE_BYTES) {
                writeJsonError(response, 507,
                        "Not enough local storage while preserving the 300 MiB safety reserve.");
                return;
            }
            File target = new File(localRoot, filename).getCanonicalFile();
            if (!localRoot.equals(target.getParentFile())) {
                writeJsonError(response, StatusCode.SC_BAD_REQUEST, "Invalid file name.");
                return;
            }
            if (target.exists()) {
                writeJsonError(response, StatusCode.SC_CONFLICT,
                        "A local file with this name already exists.");
                return;
            }

            temporary = new File(localRoot, ".dashboard-upload-" + UUID.randomUUID() + ".tmp")
                    .getCanonicalFile();
            if (!localRoot.equals(temporary.getParentFile())) {
                writeJsonError(response, StatusCode.SC_BAD_REQUEST, "Invalid upload target.");
                return;
            }
            file.transferTo(temporary);
            if (!temporary.isFile() || temporary.length() <= 0L) {
                writeJsonError(response, StatusCode.SC_BAD_REQUEST, "The uploaded file is empty.");
                return;
            }
            if (!temporary.renameTo(target)) {
                writeJsonError(response, StatusCode.SC_INTERNAL_SERVER_ERROR,
                        "Unable to finalize the uploaded file.");
                return;
            }
            temporary = null;

            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("file", new DashboardFileInfo(
                    "local",
                    target.getName(),
                    target.getName(),
                    target.length(),
                    target.lastModified()
            ).toJson());
            writeJson(response, StatusCode.SC_CREATED, result);
            } catch (Exception e) {
                LogHelper.log(e);
                writeJsonError(response, StatusCode.SC_INTERNAL_SERVER_ERROR,
                        "Unable to store the uploaded file.");
            } finally {
                if (temporary != null && temporary.exists() && !temporary.delete()) {
                    Logger.w("Unable to remove temporary dashboard upload: %s", temporary.getName());
                }
            }
        }
    }

    @GetMapping(path = URI_DASHBOARD_FILES_DETAIL)
    void getDashboardFileDetail(HttpRequest request, HttpResponse response) {
        setDashboardApiHeaders(response);
        if (!isDashboardRequestAuthorized(request)) {
            writeJsonError(response, StatusCode.SC_FORBIDDEN, "Dashboard authorization failed.");
            return;
        }

        DashboardGcodeFileSelector.Result selectorResult = DashboardGcodeFileSelector.parse(
                request.getParameter("source"),
                request.getParameter("path")
        );
        if (!selectorResult.valid) {
            writeJsonError(response, StatusCode.SC_BAD_REQUEST, selectorResult.error);
            return;
        }
        String detailRequestIdValue = request.getParameter("requestId");
        Long detailRequestId = parsePositiveLong(detailRequestIdValue);
        if (!TextUtils.isEmpty(detailRequestIdValue) && detailRequestId == null) {
            writeJsonError(response, StatusCode.SC_BAD_REQUEST,
                    "Parameter requestId must be a positive integer.");
            return;
        }

        try {
            ResolvedDashboardFile resolved = resolveDashboardFile(selectorResult.selection);
            if (detailRequestId == null) {
                // No id means a new modal/open lifecycle. Cancel an obsolete read promptly and
                // strong-verify this selection instead of trusting path/size/mtime metadata.
                cancelPendingDashboardFileDetailForNewOpen();
            }
            long requestId;
            boolean startParse = false;
            synchronized (mFileDetailLock) {
                boolean reusable = mFileDetailState != null
                        && DashboardFileAnalysisPolicy.canReuseDetailRequest(
                        detailRequestId,
                        mFileDetailState.requestId
                )
                        && mFileDetailState.fingerprint.equals(resolved.fingerprint)
                        && !mFileDetailState.consumed
                        && (mFileDetailState.pending
                        || (mFileDetailState.metadata != null
                        && mFileDetailState.safetyReady
                        && TextUtils.isEmpty(mFileDetailState.error)));
                if (reusable) {
                    requestId = mFileDetailState.requestId;
                } else {
                    if (mFileDetailState != null && mFileDetailState.pending) {
                        writeJsonError(response, StatusCode.SC_CONFLICT,
                                "Another file detail is still being parsed.");
                        return;
                    }
                    requestId = ++mNextFileDetailId;
                    DashboardFileAnalysisCache.Candidate<DashboardCachedFileAnalysis> cached =
                            mFileAnalysisCache.findByFingerprint(
                                    resolved.fingerprint,
                                    resolved.info.sizeBytes
                            );
                    if (cached == null) {
                        mFileDetailState = DashboardFileDetailState.parsing(requestId, resolved);
                        startParse = true;
                    } else {
                        mFileDetailState = DashboardFileDetailState.verifyingCacheHint(
                                requestId,
                                resolved,
                                cached.value
                        );
                        startParse = true;
                    }
                }
            }

            if (startParse && !mMainHandler.post(() -> startDashboardFileDetail(
                    requestId,
                    selectorResult.selection,
                    resolved.fingerprint
            ))) {
                finishDashboardFileDetail(
                        requestId,
                        null,
                        null,
                        "Unable to queue the file parser."
                );
            }

            DashboardFileDetailState state;
            synchronized (mFileDetailLock) {
                state = mFileDetailState != null && mFileDetailState.requestId == requestId
                        ? mFileDetailState.snapshot()
                        : null;
            }
            if (state == null) {
                writeJsonError(response, StatusCode.SC_INTERNAL_SERVER_ERROR,
                        "File detail state was lost.");
                return;
            }
            writeJson(response, state.pending ? StatusCode.SC_ACCEPTED : StatusCode.SC_OK,
                    state.toJson());
        } catch (DashboardFileException error) {
            writeJsonError(response, error.status, error.getMessage());
        } catch (Exception error) {
            LogHelper.log(error);
            writeJsonError(response, StatusCode.SC_INTERNAL_SERVER_ERROR,
                    "Unable to inspect the selected G-code file.");
        }
    }

    private void cancelPendingDashboardFileDetailForNewOpen() {
        long activeRequestId;
        synchronized (mFileDetailLock) {
            activeRequestId = mFileDetailState != null && mFileDetailState.pending
                    ? mFileDetailState.requestId : 0L;
        }
        if (activeRequestId <= 0L) return;
        finishDashboardFileDetail(
                activeRequestId,
                null,
                null,
                "File analysis was superseded by a new file-detail request."
        );
    }

    @GetMapping(path = URI_DASHBOARD_FILES_THUMBNAIL)
    void getDashboardFileThumbnail(HttpRequest request, HttpResponse response) {
        setDashboardApiHeaders(response);
        if (!isDashboardRequestAuthorized(request)) {
            writeJsonError(response, StatusCode.SC_FORBIDDEN, "Dashboard authorization failed.");
            return;
        }
        Long requestId = parsePositiveLong(request.getParameter("requestId"));
        if (requestId == null) {
            writeJsonError(response, StatusCode.SC_BAD_REQUEST,
                    "Parameter requestId must be a positive integer.");
            return;
        }

        byte[] thumbnail = null;
        synchronized (mFileDetailLock) {
            if (mFileDetailState != null
                    && mFileDetailState.requestId == requestId
                    && mFileDetailState.metadataReady
                    && TextUtils.isEmpty(mFileDetailState.error)) {
                thumbnail = mFileDetailState.thumbnailPng;
            }
        }
        if (thumbnail == null || thumbnail.length == 0) {
            writeJsonError(response, StatusCode.SC_NOT_FOUND,
                    "No thumbnail is available for this parsed file.");
            return;
        }
        response.setStatus(StatusCode.SC_OK);
        response.setBody(new StreamBody(
                new ByteArrayInputStream(thumbnail),
                thumbnail.length,
                MediaType.IMAGE_PNG
        ));
    }

    @GetMapping(path = URI_DASHBOARD_FILES_LIST_THUMBNAIL)
    void getDashboardListThumbnail(HttpRequest request, HttpResponse response) {
        setDashboardApiHeaders(response);
        if (!isDashboardRequestAuthorized(request)) {
            writeJsonError(response, StatusCode.SC_FORBIDDEN, "Dashboard authorization failed.");
            return;
        }

        DashboardGcodeFileSelector.Result selectorResult = DashboardGcodeFileSelector.parse(
                request.getParameter("source"),
                request.getParameter("path")
        );
        if (!selectorResult.valid) {
            writeJsonError(response, StatusCode.SC_BAD_REQUEST, selectorResult.error);
            return;
        }

        final ResolvedDashboardFile resolved;
        try {
            resolved = resolveDashboardFile(selectorResult.selection);
        } catch (DashboardFileException error) {
            writeJsonError(response, error.status, error.getMessage());
            return;
        }

        long nowElapsed = SystemClock.elapsedRealtime();
        DashboardListThumbnailCache.Acquisition acquisition =
                mListThumbnailCache.acquire(resolved.fingerprint, nowElapsed);
        if (acquisition.state == DashboardListThumbnailCache.State.CACHED) {
            response.setHeader("X-Artisan-Thumbnail-Cache", "hit");
            if (acquisition.hasImage()) {
                writeDashboardListThumbnail(response, acquisition.png);
            } else {
                writeJsonError(response, StatusCode.SC_NOT_FOUND,
                        TextUtils.isEmpty(acquisition.missingMessage)
                                ? "No supported embedded thumbnail is available for this file."
                                : acquisition.missingMessage);
            }
            return;
        }
        if (acquisition.state == DashboardListThumbnailCache.State.PENDING) {
            writeDashboardThumbnailRetry(
                    response,
                    StatusCode.SC_ACCEPTED,
                    "pending",
                    "This file thumbnail is already being prepared."
            );
            return;
        }
        if (acquisition.state == DashboardListThumbnailCache.State.BUSY) {
            writeDashboardThumbnailRetry(
                    response,
                    429,
                    "busy",
                    "The thumbnail scan limit is busy."
            );
            return;
        }

        try (InputStream input = resolved.file.getInputStream()) {
            DashboardGcodeThumbnailExtractor.Result extracted =
                    DashboardGcodeThumbnailExtractor.extract(input);
            if (extracted.timedOut) {
                mListThumbnailCache.completeFailure(resolved.fingerprint);
                writeDashboardThumbnailRetry(
                        response,
                        StatusCode.SC_SERVICE_UNAVAILABLE,
                        "timed_out",
                        "Thumbnail scanning timed out while reading the storage device."
                );
                return;
            }
            if (!extracted.found()) {
                String message = extracted.limitReached
                        ? "No supported thumbnail was found in the bounded G-code preview."
                        : "No supported embedded thumbnail is available for this file.";
                mListThumbnailCache.completeMissing(
                        resolved.fingerprint,
                        message,
                        SystemClock.elapsedRealtime()
                );
                response.setHeader("X-Artisan-Thumbnail-Cache", "miss");
                writeJsonError(response, StatusCode.SC_NOT_FOUND, message);
                return;
            }

            byte[] thumbnail = decodeDashboardListThumbnail(extracted.encodedData);
            if (thumbnail == null || thumbnail.length == 0) {
                String message = "The embedded thumbnail is invalid or unsupported.";
                mListThumbnailCache.completeMissing(
                        resolved.fingerprint,
                        message,
                        SystemClock.elapsedRealtime()
                );
                response.setHeader("X-Artisan-Thumbnail-Cache", "miss");
                writeJsonError(response, StatusCode.SC_NOT_FOUND, message);
                return;
            }
            mListThumbnailCache.completeSuccess(
                    resolved.fingerprint,
                    thumbnail,
                    SystemClock.elapsedRealtime()
            );
            response.setHeader("X-Artisan-Thumbnail-Cache", "miss");
            writeDashboardListThumbnail(response, thumbnail);
        } catch (Exception error) {
            mListThumbnailCache.completeFailure(resolved.fingerprint);
            LogHelper.log(error);
            writeJsonError(response, StatusCode.SC_INTERNAL_SERVER_ERROR,
                    "Unable to read the embedded thumbnail.");
        }
    }

    @PostMapping(path = URI_DASHBOARD_FILES_START)
    void startDashboardFile(HttpRequest request, HttpResponse response) {
        setDashboardApiHeaders(response);
        if (!isDashboardRequestAuthorized(request)) {
            writeJsonError(response, StatusCode.SC_FORBIDDEN, "Dashboard authorization failed.");
            return;
        }

        DashboardGcodeFileSelector.Result selectorResult = DashboardGcodeFileSelector.parse(
                request.getParameter("source"),
                request.getParameter("path")
        );
        if (!selectorResult.valid) {
            writeJsonError(response, StatusCode.SC_BAD_REQUEST, selectorResult.error);
            return;
        }
        Long previewRequestId = parsePositiveLong(request.getParameter("previewRequestId"));
        if (previewRequestId == null || !"true".equals(request.getParameter("confirm"))) {
            writeJsonError(response, StatusCode.SC_BAD_REQUEST,
                    "A ready previewRequestId and explicit confirm=true are required.");
            return;
        }

        try {
            ResolvedDashboardFile resolved = resolveDashboardFile(selectorResult.selection);
            DashboardFileMetadata preview = getReadyDashboardFilePreview(
                    previewRequestId,
                    resolved.fingerprint
            );
            if (preview == null) {
                writeJsonError(response, StatusCode.SC_CONFLICT,
                        "Open this file's details and wait for the unchanged preview to finish first.");
                return;
            }

            IMachine machine = ServiceContainer.getInstance().getService(IMachine.class);
            IAppService appService = ServiceContainer.getInstance().getService(IAppService.class);
            DashboardNozzleDiameterMismatch previewMismatch =
                    getReadyDashboardNozzleDiameterMismatch(previewRequestId);
            if (previewMismatch == null) {
                writeJsonError(response, StatusCode.SC_CONFLICT,
                        "The file preview compatibility result is unavailable. Refresh the file details.");
                return;
            }
            DashboardPrintStartOptions startOptions = DashboardPrintStartOptions.parse(
                    request.getParameter("bedMode"),
                    request.getParameter("confirmNozzleDiameterMismatch"),
                    previewMismatch.detected
            );
            if (!startOptions.valid) {
                writeJsonError(response, startOptions.errorStatus, startOptions.error);
                return;
            }

            String preflightError = dashboardFileStartPreflight(
                    machine,
                    appService,
                    preview,
                    previewMismatch,
                    startOptions.nozzleDiameterMismatchConfirmed
            );
            if (!TextUtils.isEmpty(preflightError)) {
                writeJsonError(response, StatusCode.SC_CONFLICT, preflightError);
                return;
            }

            final long requestId;
            synchronized (mFileDetailLock) {
                if (mFileDetailState == null
                        || mFileDetailState.requestId != previewRequestId
                        || mFileDetailState.pending
                        || mFileDetailState.consumed
                        || !mFileDetailState.safetyReady
                        || !TextUtils.isEmpty(mFileDetailState.error)
                        || !mFileDetailState.fingerprint.equals(resolved.fingerprint)
                        || mFileDetailState.metadata != preview) {
                    writeJsonError(response, StatusCode.SC_CONFLICT,
                            "The confirmed file preview is no longer available.");
                    return;
                }
                synchronized (mFileStartLock) {
                    if (mFileStartState != null && mFileStartState.pending) {
                        writeJsonError(response, StatusCode.SC_CONFLICT,
                                "Another dashboard print start is already in progress.");
                        return;
                    }
                    requestId = ++mNextFileStartId;
                    NewPrintController printController = machine.getNewPrintController();
                    long preparationLease = printController.tryAcquirePrintPreparationLease();
                    if (preparationLease == 0L) {
                        writeJsonError(response, StatusCode.SC_CONFLICT,
                                "Another print preparation already owns the shared workspace.");
                        return;
                    }
                    printController.setStartFromRemoteFlag(true);
                    mFileStartRemoteGateClaimed = true;
                    mFileStartState = DashboardFileStartState.pending(
                            requestId,
                            previewRequestId,
                            resolved.info,
                            startOptions,
                            previewMismatch,
                            preparationLease
                    );
                }
            }

            boolean posted = mMainHandler.post(() -> executeDashboardFileStart(
                    requestId,
                    selectorResult.selection,
                    resolved.fingerprint,
                    preview
            ));
            if (!posted) {
                finishDashboardFileStart(
                        requestId,
                        "failed",
                        "queue_failed",
                        "Unable to queue print preparation on the Android main thread.",
                        -1
                );
                DashboardFileStartState queueFailure;
                synchronized (mFileStartLock) {
                    queueFailure = mFileStartState != null
                            && mFileStartState.requestId == requestId
                            ? mFileStartState.snapshot() : null;
                }
                if (queueFailure == null) {
                    writeJsonError(response, StatusCode.SC_INTERNAL_SERVER_ERROR,
                            "Unable to queue print preparation.");
                } else {
                    JSONObject failure = queueFailure.toJson();
                    failure.put("ok", false);
                    failure.put("accepted", false);
                    writeJson(response, StatusCode.SC_INTERNAL_SERVER_ERROR, failure);
                }
                return;
            }

            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("accepted", true);
            result.put("requestId", requestId);
            result.put("state", "preparing");
            result.put("file", resolved.info.toJson());
            writeJson(response, StatusCode.SC_ACCEPTED, result);
        } catch (DashboardFileException error) {
            writeJsonError(response, error.status, error.getMessage());
        } catch (Exception error) {
            LogHelper.log(error);
            writeJsonError(response, StatusCode.SC_INTERNAL_SERVER_ERROR,
                    "Unable to prepare the selected G-code file.");
        }
    }

    @GetMapping(path = URI_DASHBOARD_FILES_START_STATUS)
    void getDashboardFileStartStatus(HttpRequest request, HttpResponse response) {
        setDashboardApiHeaders(response);
        if (!isDashboardRequestAuthorized(request)) {
            writeJsonError(response, StatusCode.SC_FORBIDDEN, "Dashboard authorization failed.");
            return;
        }
        Long requestId = parsePositiveLong(request.getParameter("requestId"));
        if (requestId == null) {
            writeJsonError(response, StatusCode.SC_BAD_REQUEST,
                    "Parameter requestId must be a positive integer.");
            return;
        }
        DashboardFileStartState state;
        synchronized (mFileStartLock) {
            state = mFileStartState != null && mFileStartState.requestId == requestId
                    ? mFileStartState.snapshot()
                    : null;
        }
        if (state == null) {
            writeJsonError(response, StatusCode.SC_NOT_FOUND,
                    "No dashboard print start has this requestId.");
            return;
        }
        try {
            writeJson(response, StatusCode.SC_OK, state.toJson());
        } catch (JSONException error) {
            LogHelper.log(error);
            writeJsonError(response, StatusCode.SC_INTERNAL_SERVER_ERROR,
                    "Unable to encode print start status.");
        }
    }

    @GetMapping(path = URI_DASHBOARD_CONSOLE_HISTORY)
    void getDashboardConsoleHistory(HttpRequest request, HttpResponse response) {
        setDashboardApiHeaders(response);
        if (!isDashboardRequestAuthorized(request)) {
            writeJsonError(response, StatusCode.SC_FORBIDDEN, "Dashboard authorization failed.");
            return;
        }
        try {
            long after = parseLong(request.getParameter("after"), 0L);
            JSONArray entries = new JSONArray();
            boolean pending;
            synchronized (mConsoleLock) {
                for (DashboardConsoleEntry entry : mConsoleHistory) {
                    if (entry.id > after) entries.put(entry.toJson());
                }
                pending = mConsoleCommandPending;
            }
            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("pending", pending);
            result.put("entries", entries);
            writeJson(response, StatusCode.SC_OK, result);
        } catch (Exception e) {
            LogHelper.log(e);
            writeJsonError(response, StatusCode.SC_INTERNAL_SERVER_ERROR,
                    "Unable to read console history.");
        }
    }

    @PostMapping(path = URI_DASHBOARD_CONSOLE_SEND)
    void sendDashboardGcode(
            HttpRequest request,
            HttpResponse response,
            @RequestParam(name = "command") String command
    ) {
        setDashboardApiHeaders(response);
        if (!isDashboardRequestAuthorized(request)) {
            writeJsonError(response, StatusCode.SC_FORBIDDEN, "Dashboard authorization failed.");
            return;
        }
        DashboardConsoleResult result = isMeshCommand(command)
                ? startDashboardBedMeshRefresh()
                : startDashboardGcode(command);
        if (!result.accepted) {
            writeJsonError(response, result.status, result.error);
            return;
        }
        try {
            JSONObject json = new JSONObject();
            json.put("ok", true);
            json.put("accepted", true);
            json.put("id", result.entry.id);
            json.put("state", isMeshCommand(command) ? "capturing_mesh" : "queued");
            json.put("entry", result.entry.toJson());
            writeJson(response, StatusCode.SC_ACCEPTED, json);
        } catch (JSONException e) {
            LogHelper.log(e);
            writeJsonError(response, StatusCode.SC_INTERNAL_SERVER_ERROR,
                    "Unable to encode the console response.");
        }
    }

    @GetMapping(path = URI_DASHBOARD_BED_MESH)
    void getDashboardBedMesh(HttpRequest request, HttpResponse response) {
        setDashboardApiHeaders(response);
        if (!isDashboardRequestAuthorized(request)) {
            writeJsonError(response, StatusCode.SC_FORBIDDEN, "Dashboard authorization failed.");
            return;
        }
        try {
            writeJson(response, StatusCode.SC_OK, bedMeshToJson(mLastBedMesh, mLastBedMeshAt));
        } catch (JSONException e) {
            LogHelper.log(e);
            writeJsonError(response, StatusCode.SC_INTERNAL_SERVER_ERROR,
                    "Unable to encode the bed mesh.");
        }
    }

    @PostMapping(path = URI_DASHBOARD_BED_MESH_REFRESH)
    void refreshDashboardBedMesh(HttpRequest request, HttpResponse response) {
        setDashboardApiHeaders(response);
        if (!isDashboardRequestAuthorized(request)) {
            writeJsonError(response, StatusCode.SC_FORBIDDEN, "Dashboard authorization failed.");
            return;
        }
        DashboardConsoleResult result = startDashboardBedMeshRefresh();
        if (!result.accepted) {
            writeJsonError(response, result.status, result.error);
            return;
        }
        try {
            JSONObject json = new JSONObject();
            json.put("ok", true);
            json.put("accepted", true);
            json.put("requestId", result.entry.id);
            json.put("state", "capturing");
            writeJson(response, StatusCode.SC_ACCEPTED, json);
        } catch (JSONException e) {
            LogHelper.log(e);
            writeJsonError(response, StatusCode.SC_INTERNAL_SERVER_ERROR,
                    "Unable to encode the bed mesh.");
        }
    }

    @GetMapping(path = URI_DASHBOARD_ENCLOSURE)
    void getDashboardEnclosure(HttpRequest request, HttpResponse response) {
        setDashboardApiHeaders(response);
        if (!isDashboardRequestAuthorized(request)) {
            writeJsonError(response, StatusCode.SC_FORBIDDEN, "Dashboard authorization failed.");
            return;
        }
        try {
            IMachine machine = ServiceContainer.getInstance().getService(IMachine.class);
            maybeRefreshTelemetry(machine);
            writeJson(response, StatusCode.SC_OK, dashboardEnclosureToJson(machine));
        } catch (Exception error) {
            LogHelper.log(error);
            writeJsonError(response, StatusCode.SC_INTERNAL_SERVER_ERROR,
                    "Unable to read enclosure status.");
        }
    }

    @PostMapping(path = URI_DASHBOARD_ENCLOSURE_LED)
    void setDashboardEnclosureLed(HttpRequest request, HttpResponse response) {
        handleDashboardEnclosureControl("led", request, response);
    }

    @PostMapping(path = URI_DASHBOARD_ENCLOSURE_FAN)
    void setDashboardEnclosureFan(HttpRequest request, HttpResponse response) {
        handleDashboardEnclosureControl("fan", request, response);
    }

    @GetMapping(path = URI_DASHBOARD_CAMERA_STATUS)
    void getDashboardCameraStatus(HttpRequest request, HttpResponse response) {
        setDashboardApiHeaders(response);
        if (!isDashboardRequestAuthorized(request)) {
            writeJsonError(response, StatusCode.SC_FORBIDDEN, "Dashboard authorization failed.");
            return;
        }
        try {
            writeJson(response, StatusCode.SC_OK, dashboardCameraToJson(cameraManager()));
        } catch (Exception error) {
            LogHelper.log(error);
            writeJsonError(response, StatusCode.SC_INTERNAL_SERVER_ERROR,
                    "Unable to read USB camera status.");
        }
    }

    @PostMapping(path = URI_DASHBOARD_CAMERA_SETTINGS)
    void setDashboardCameraSettings(HttpRequest request, HttpResponse response) {
        setDashboardApiHeaders(response);
        if (!isDashboardRequestAuthorized(request)) {
            writeJsonError(response, StatusCode.SC_FORBIDDEN, "Dashboard authorization failed.");
            return;
        }
        DashboardCameraSettingsInput.Result input = DashboardCameraSettingsInput.parse(
                request.getParameter("enabled"),
                request.getParameter("source"),
                request.getParameter("url"),
                request.getParameter("width"),
                request.getParameter("height"),
                request.getParameter("fps")
        );
        if (!input.valid) {
            writeJsonError(response, StatusCode.SC_BAD_REQUEST, input.error);
            return;
        }
        try {
            UvcCameraManager manager = cameraManager();
            UvcCameraManager.ApplyResult result = manager.applySettings(
                    input.enabled,
                    input.sourceId,
                    input.streamUrl,
                    input.width,
                    input.height,
                    input.fps
            );
            if (!result.isSuccess()) {
                writeJsonError(response, StatusCode.SC_BAD_REQUEST, result.getMessage());
                return;
            }
            writeJson(response, StatusCode.SC_OK, dashboardCameraToJson(manager));
        } catch (Exception error) {
            LogHelper.log(error);
            writeJsonError(response, StatusCode.SC_INTERNAL_SERVER_ERROR,
                    "Unable to apply camera settings.");
        }
    }

    @PostMapping(path = URI_DASHBOARD_CAMERA_RESCAN)
    void rescanDashboardCameras(HttpRequest request, HttpResponse response) {
        setDashboardApiHeaders(response);
        if (!isDashboardRequestAuthorized(request)) {
            writeJsonError(response, StatusCode.SC_FORBIDDEN, "Dashboard authorization failed.");
            return;
        }
        try {
            UvcCameraManager manager = cameraManager();
            manager.rescan();
            writeJson(response, StatusCode.SC_OK, dashboardCameraToJson(manager));
        } catch (Exception error) {
            LogHelper.log(error);
            writeJsonError(response, StatusCode.SC_INTERNAL_SERVER_ERROR,
                    "Unable to scan USB video devices.");
        }
    }

    @GetMapping(path = URI_DASHBOARD_CAMERA_FRAME)
    void getDashboardCameraFrame(HttpRequest request, HttpResponse response) {
        setDashboardApiHeaders(response);
        if (!isDashboardRequestAuthorized(request)) {
            writeJsonError(response, StatusCode.SC_FORBIDDEN, "Dashboard authorization failed.");
            return;
        }
        Long afterSequence = parseNonNegativeLong(request.getParameter("after"));
        if (afterSequence == null) {
            writeJsonError(response, StatusCode.SC_BAD_REQUEST,
                    "Parameter after must be a non-negative integer.");
            return;
        }
        String clientId = parseDashboardCameraClientId(request.getParameter("client"));
        if (clientId == null) {
            writeJsonError(response, StatusCode.SC_BAD_REQUEST,
                    "Parameter client must be a valid camera session identifier.");
            return;
        }
        try {
            UvcCameraManager.Frame frame = cameraManager().awaitFrame(
                    clientId,
                    afterSequence,
                    CAMERA_FRAME_WAIT_MS
            );
            if (frame == null || frame.getJpeg() == null || frame.getJpeg().length == 0) {
                response.setStatus(StatusCode.SC_NO_CONTENT);
                return;
            }
            byte[] jpeg = frame.getJpeg();
            response.setHeader("X-Artisan-Camera-Sequence", String.valueOf(frame.getSequence()));
            response.setHeader("X-Artisan-Camera-Timestamp", String.valueOf(frame.getTimestampMs()));
            response.setStatus(StatusCode.SC_OK);
            response.setBody(new StreamBody(
                    new ByteArrayInputStream(jpeg),
                    jpeg.length,
                    MediaType.IMAGE_JPEG
            ));
        } catch (Exception error) {
            LogHelper.log(error);
            writeJsonError(response, StatusCode.SC_SERVICE_UNAVAILABLE,
                    "Camera frame is temporarily unavailable.");
        }
    }

    @PostMapping(path = URI_DASHBOARD_CAMERA_STOP)
    void stopDashboardCamera(HttpRequest request, HttpResponse response) {
        setDashboardApiHeaders(response);
        if (!isDashboardRequestAuthorized(request)) {
            writeJsonError(response, StatusCode.SC_FORBIDDEN, "Dashboard authorization failed.");
            return;
        }
        String clientId = parseDashboardCameraClientId(request.getParameter("client"));
        if (clientId == null) {
            writeJsonError(response, StatusCode.SC_BAD_REQUEST,
                    "Parameter client must be a valid camera session identifier.");
            return;
        }
        try {
            UvcCameraManager manager = cameraManager();
            manager.releaseClient(clientId);
            writeJson(response, StatusCode.SC_OK, dashboardCameraToJson(manager));
        } catch (Exception error) {
            LogHelper.log(error);
            writeJsonError(response, StatusCode.SC_INTERNAL_SERVER_ERROR,
                    "Unable to release the camera.");
        }
    }

    @GetMapping(path = URI_DASHBOARD_OBICO_CONFIG)
    void getDashboardObicoConfig(HttpRequest request, HttpResponse response) {
        setDashboardApiHeaders(response);
        if (!isDashboardRequestAuthorized(request)) {
            writeJsonError(response, StatusCode.SC_FORBIDDEN, "Dashboard authorization failed.");
            return;
        }
        try {
            IObicoService service = obicoService();
            if (service == null) {
                writeJsonError(response, StatusCode.SC_SERVICE_UNAVAILABLE,
                        "Obico integration is unavailable in this build.");
                return;
            }
            writeObicoResult(
                    response,
                    service.getPublicStateJson(),
                    StatusCode.SC_INTERNAL_SERVER_ERROR
            );
        } catch (Exception error) {
            LogHelper.log(error);
            writeJsonError(response, StatusCode.SC_INTERNAL_SERVER_ERROR,
                    "Unable to read Obico configuration.");
        }
    }

    @PostMapping(path = URI_DASHBOARD_OBICO_CONFIG)
    void setDashboardObicoConfig(HttpRequest request, HttpResponse response) {
        setDashboardApiHeaders(response);
        if (!isDashboardRequestAuthorized(request)) {
            writeJsonError(response, StatusCode.SC_FORBIDDEN, "Dashboard authorization failed.");
            return;
        }

        DashboardObicoSettingsInput.Result parsed = DashboardObicoSettingsInput.parse(
                request.getParameter("enabled"),
                request.getParameter("serverUrl"),
                request.getParameter("allowInsecureServer"),
                request.getParameter("remoteControlEnabled"),
                request.getParameter("cameraUploadsEnabled")
        );
        if (!parsed.valid) {
            writeJsonError(response, StatusCode.SC_BAD_REQUEST, parsed.error);
            return;
        }

        try {
            IObicoService service = obicoService();
            if (service == null) {
                writeJsonError(response, StatusCode.SC_SERVICE_UNAVAILABLE,
                        "Obico integration is unavailable in this build.");
                return;
            }
            JSONObject input = new JSONObject();
            input.put("enabled", parsed.enabled);
            input.put("serverUrl", parsed.serverUrl);
            input.put("allowInsecureServer", parsed.allowInsecureServer);
            input.put("remoteControlEnabled", parsed.remoteControlEnabled);
            input.put("cameraUploadsEnabled", parsed.cameraUploadsEnabled);
            writeObicoResult(
                    response,
                    service.applyConfiguration(input),
                    StatusCode.SC_BAD_REQUEST
            );
        } catch (Exception error) {
            LogHelper.log(error);
            writeJsonError(response, StatusCode.SC_INTERNAL_SERVER_ERROR,
                    "Unable to save Obico configuration.");
        }
    }

    @PostMapping(path = URI_DASHBOARD_OBICO_LINK)
    void linkDashboardObico(HttpRequest request, HttpResponse response) {
        setDashboardApiHeaders(response);
        if (!isDashboardRequestAuthorized(request)) {
            writeJsonError(response, StatusCode.SC_FORBIDDEN, "Dashboard authorization failed.");
            return;
        }
        DashboardObicoSettingsInput.LinkCodeResult parsed =
                DashboardObicoSettingsInput.parseLinkCode(request.getParameter("code"));
        if (!parsed.valid) {
            writeJsonError(response, StatusCode.SC_BAD_REQUEST, parsed.error);
            return;
        }
        try {
            IObicoService service = obicoService();
            if (service == null) {
                writeJsonError(response, StatusCode.SC_SERVICE_UNAVAILABLE,
                        "Obico integration is unavailable in this build.");
                return;
            }
            writeObicoResult(
                    response,
                    service.beginLink(parsed.code),
                    StatusCode.SC_BAD_REQUEST
            );
        } catch (Exception error) {
            LogHelper.log(error);
            writeJsonError(response, StatusCode.SC_INTERNAL_SERVER_ERROR,
                    "Unable to start Obico linking.");
        }
    }

    @PostMapping(path = URI_DASHBOARD_OBICO_TEST)
    void testDashboardObico(HttpRequest request, HttpResponse response) {
        setDashboardApiHeaders(response);
        if (!isDashboardRequestAuthorized(request)) {
            writeJsonError(response, StatusCode.SC_FORBIDDEN, "Dashboard authorization failed.");
            return;
        }
        try {
            IObicoService service = obicoService();
            if (service == null) {
                writeJsonError(response, StatusCode.SC_SERVICE_UNAVAILABLE,
                        "Obico integration is unavailable in this build.");
                return;
            }
            writeObicoResult(
                    response,
                    service.testConnection(),
                    StatusCode.SC_SERVICE_UNAVAILABLE
            );
        } catch (Exception error) {
            LogHelper.log(error);
            writeJsonError(response, StatusCode.SC_INTERNAL_SERVER_ERROR,
                    "Unable to test the Obico connection.");
        }
    }

    @PostMapping(path = URI_DASHBOARD_OBICO_DISCONNECT)
    void disconnectDashboardObico(HttpRequest request, HttpResponse response) {
        setDashboardApiHeaders(response);
        if (!isDashboardRequestAuthorized(request)) {
            writeJsonError(response, StatusCode.SC_FORBIDDEN, "Dashboard authorization failed.");
            return;
        }
        try {
            IObicoService service = obicoService();
            if (service == null) {
                writeJsonError(response, StatusCode.SC_SERVICE_UNAVAILABLE,
                        "Obico integration is unavailable in this build.");
                return;
            }
            writeObicoResult(
                    response,
                    service.disconnect(),
                    StatusCode.SC_INTERNAL_SERVER_ERROR
            );
        } catch (Exception error) {
            LogHelper.log(error);
            writeJsonError(response, StatusCode.SC_INTERNAL_SERVER_ERROR,
                    "Unable to unlink FabScreen from Obico.");
        }
    }

    private void collectLocalGcodeFiles(
            File root,
            File directory,
            int depth,
            List<DashboardFileInfo> result,
            DashboardFileScanBudget budget
    ) throws IOException {
        if (depth > MAX_FILE_SCAN_DEPTH || result.size() >= MAX_LISTED_GCODE_FILES) {
            budget.truncated = true;
            return;
        }
        if (!budget.hasTime()) return;
        File canonicalDirectory = directory.getCanonicalFile();
        String rootPath = root.getCanonicalPath();
        String directoryPath = canonicalDirectory.getCanonicalPath();
        if (!directoryPath.equals(rootPath)
                && !directoryPath.startsWith(rootPath + File.separator)) return;

        File[] children = canonicalDirectory.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (!budget.visit() || result.size() >= MAX_LISTED_GCODE_FILES) {
                budget.truncated = true;
                return;
            }
            File canonicalChild = child.getCanonicalFile();
            String childPath = canonicalChild.getCanonicalPath();
            if (!childPath.startsWith(rootPath + File.separator)) continue;
            if (canonicalChild.isDirectory()) {
                if (".dashboard-staging".equals(canonicalChild.getName())) continue;
                collectLocalGcodeFiles(root, canonicalChild, depth + 1, result, budget);
            } else if (canonicalChild.isFile() && isGcodeName(canonicalChild.getName())) {
                String relative = childPath.substring(rootPath.length() + 1)
                        .replace(File.separatorChar, '/');
                result.add(new DashboardFileInfo(
                        "local",
                        canonicalChild.getName(),
                        relative,
                        canonicalChild.length(),
                        canonicalChild.lastModified()
                ));
            }
        }
    }

    private void collectUsbGcodeFiles(
            IFile directory,
            String relativeDirectory,
            int depth,
            List<DashboardFileInfo> result,
            DashboardFileScanBudget budget
    ) throws IOException {
        if (directory == null
                || !directory.isDirectory()
                || depth > MAX_FILE_SCAN_DEPTH
                || result.size() >= MAX_LISTED_GCODE_FILES) {
            if (depth > MAX_FILE_SCAN_DEPTH || result.size() >= MAX_LISTED_GCODE_FILES) {
                budget.truncated = true;
            }
            return;
        }
        if (!budget.hasTime()) return;
        List<IFile> children = directory.listFiles();
        if (children == null) return;
        for (IFile child : children) {
            if (!budget.visit() || result.size() >= MAX_LISTED_GCODE_FILES) {
                budget.truncated = true;
                return;
            }
            if (child == null) continue;
            String childRelative = relativeDirectory.isEmpty()
                    ? child.getName()
                    : relativeDirectory + "/" + child.getName();
            if (child.isDirectory()) {
                collectUsbGcodeFiles(child, childRelative, depth + 1, result, budget);
            } else if (child.exists() && isGcodeName(child.getName())) {
                result.add(new DashboardFileInfo(
                        "usb",
                        child.getName(),
                        childRelative,
                        child.length(),
                        child.lastModified()
                ));
            }
        }
    }

    private void sortDashboardFiles(List<DashboardFileInfo> files) {
        Collections.sort(files, Comparator.comparing(
                file -> file.relativePath.toLowerCase(Locale.US)
        ));
    }

    private JSONArray filesToJson(List<DashboardFileInfo> files) throws JSONException {
        JSONArray result = new JSONArray();
        for (DashboardFileInfo file : files) result.put(file.toJson());
        return result;
    }

    private JSONObject dashboardListThumbnailPolicyJson() throws JSONException {
        JSONObject result = new JSONObject();
        result.put("endpoint", URI_DASHBOARD_FILES_LIST_THUMBNAIL);
        result.put("selector", "source+path");
        result.put("authenticatedFetchRequired", true);
        result.put("maxEdgePx", MAX_LIST_THUMBNAIL_EDGE_PX);
        result.put("maxConcurrentScans", MAX_CONCURRENT_LIST_THUMBNAIL_SCANS);
        result.put("retryAfterMs", LIST_THUMBNAIL_RETRY_AFTER_MS);
        result.put("cacheControl", "no-store");
        return result;
    }

    private boolean isSafeGcodeFilename(String filename) {
        if (!isGcodeName(filename)
                || filename.getBytes(StandardCharsets.UTF_8).length > 255
                || filename.equals(".")
                || filename.equals("..")
                || filename.indexOf('/') >= 0
                || filename.indexOf('\\') >= 0
                || filename.indexOf('\0') >= 0) return false;
        for (int index = 0; index < filename.length(); index++) {
            if (Character.isISOControl(filename.charAt(index))) return false;
        }
        return true;
    }

    private boolean isGcodeName(String name) {
        return name != null && name.toLowerCase(Locale.US).endsWith(".gcode");
    }

    private ResolvedDashboardFile resolveDashboardFile(
            DashboardGcodeFileSelector.Selection selection
    ) throws DashboardFileException {
        try {
            IFile selectedFile;
            File storageRoot;
            String storageIdentity;
            if ("local".equals(selection.source)) {
                storageRoot = ServiceContainer.getInstance()
                        .getService(IAppService.class)
                        .getFilesDir()
                        .getCanonicalFile();
                File candidate = DashboardGcodeFileSelector.resolveContainedFile(
                        storageRoot,
                        selection
                );
                if (!candidate.isFile()) {
                    throw new DashboardFileException(
                            StatusCode.SC_NOT_FOUND,
                            "The selected local G-code file no longer exists."
                    );
                }
                selectedFile = new FabLocalFile(candidate);
                storageIdentity = storageRoot.getCanonicalPath();
            } else {
                FabUsbPartition partition = ServiceContainer.getInstance()
                        .getService(IFileManagerService.class)
                        .getFabUsbDevice();
                if (partition == null) {
                    throw new DashboardFileException(
                            StatusCode.SC_NOT_FOUND,
                            "USB storage is not connected."
                    );
                }
                IFile rootFile = partition.getRootFile();
                if (rootFile == null || !rootFile.isDirectory()) {
                    throw new DashboardFileException(
                            StatusCode.SC_NOT_FOUND,
                            "USB storage is no longer mounted."
                    );
                }
                storageRoot = new File(rootFile.getAbsolutePath()).getCanonicalFile();
                File candidate = DashboardGcodeFileSelector.resolveContainedFile(
                        storageRoot,
                        selection
                );
                if (!candidate.isFile()) {
                    throw new DashboardFileException(
                            StatusCode.SC_NOT_FOUND,
                            "The selected USB G-code file no longer exists."
                    );
                }
                selectedFile = new FabUsbFile(candidate);
                storageIdentity = emptyIfNull(partition.getUuid())
                        + "\n" + storageRoot.getCanonicalPath();
            }

            long size = selectedFile.length();
            long modifiedAt = selectedFile.lastModified();
            if (size <= 0L) {
                throw new DashboardFileException(
                        StatusCode.SC_BAD_REQUEST,
                        "The selected G-code file is empty."
                );
            }
            if (size > MAX_GCODE_UPLOAD_BYTES) {
                throw new DashboardFileException(
                        413,
                        "G-code files are limited to 1 GiB."
                );
            }
            DashboardFileInfo info = new DashboardFileInfo(
                    selection.source,
                    selection.name,
                    selection.relativePath,
                    size,
                    modifiedAt
            );
            String fingerprint = DashboardGcodeFileSelector.fingerprint(
                    selection,
                    size,
                    modifiedAt
            ) + "\n" + storageIdentity;
            return new ResolvedDashboardFile(selectedFile, info, fingerprint);
        } catch (DashboardFileException error) {
            throw error;
        } catch (IOException error) {
            throw new DashboardFileException(
                    StatusCode.SC_NOT_FOUND,
                    "The selected storage path is no longer available."
            );
        }
    }

    private void startDashboardFileDetail(
            long requestId,
            DashboardGcodeFileSelector.Selection selection,
            String expectedFingerprint
    ) {
        synchronized (mFileDetailLock) {
            if (mFileDetailState == null
                    || mFileDetailState.requestId != requestId
                    || !mFileDetailState.pending) return;
        }

        try {
            ResolvedDashboardFile resolved = resolveDashboardFile(selection);
            if (!expectedFingerprint.equals(resolved.fingerprint)) {
                finishDashboardFileDetail(
                        requestId,
                        null,
                        null,
                        "The selected file changed before parsing started."
                );
                return;
            }
            if (!scheduleDashboardFileDetailWatchdog(
                    requestId,
                    resolved.info.sizeBytes
            )) return;
            boolean verifyCacheHint;
            synchronized (mFileDetailLock) {
                verifyCacheHint = mFileDetailState != null
                        && mFileDetailState.requestId == requestId
                        && mFileDetailState.pending
                        && mFileDetailState.metadataReady;
            }
            if (!verifyCacheHint) {
                beginDashboardFileQuickPreview(
                        requestId,
                        selection,
                        expectedFingerprint,
                        ""
                );
                return;
            }
            Disposable digestDisposable = Observable.fromCallable(
                            () -> sha256(requestId, resolved.file)
                    )
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(digest -> handleDashboardFileDetailDigest(
                            requestId,
                            selection,
                            expectedFingerprint,
                            digest
                    ), error -> {
                        LogHelper.log(error);
                        finishDashboardFileDetail(
                                requestId,
                                null,
                                null,
                                "Unable to fingerprint the selected file."
                        );
                    });
            synchronized (mFileDetailLock) {
                if (mFileDetailState != null
                        && mFileDetailState.requestId == requestId
                        && mFileDetailState.pending) {
                    mFileDetailDigestDisposable = digestDisposable;
                } else {
                    digestDisposable.dispose();
                }
            }
            mDisposable.add(digestDisposable);
        } catch (Exception error) {
            LogHelper.log(error);
            finishDashboardFileDetail(
                    requestId,
                    null,
                    null,
                    "Unable to open the selected file."
            );
        }
    }

    private void handleDashboardFileDetailDigest(
            long requestId,
            DashboardGcodeFileSelector.Selection selection,
            String expectedFingerprint,
            String digest
    ) {
        if (TextUtils.isEmpty(digest)) {
            finishDashboardFileDetail(
                    requestId,
                    null,
                    null,
                    "Unable to fingerprint the selected file."
            );
            return;
        }
        long sizeBytes = dashboardFileDetailSize(requestId);
        DashboardCachedFileAnalysis cached = mFileAnalysisCache.get(digest, sizeBytes);
        if (cached != null) {
            finishDashboardFileDetail(
                    requestId,
                    cached.metadata,
                    cached.thumbnailPng(),
                    ""
            );
            return;
        }
        synchronized (mFileDetailLock) {
            if (mFileDetailState != null
                    && mFileDetailState.requestId == requestId
                    && mFileDetailState.pending) {
                // The filesystem hint pointed at different bytes. Never keep presenting that
                // candidate while the current content is analyzed.
                mFileDetailState.metadata = null;
                mFileDetailState.thumbnailPng = null;
                mFileDetailState.metadataReady = false;
                mFileDetailState.safetyReady = false;
                mFileDetailState.progress = 0;
            }
        }
        beginDashboardFileQuickPreview(
                requestId,
                selection,
                expectedFingerprint,
                digest
        );
    }

    private void beginDashboardFileQuickPreview(
            long requestId,
            DashboardGcodeFileSelector.Selection selection,
            String expectedFingerprint,
            String digest
    ) {
        Disposable quickRead = Observable.fromCallable(() -> {
                    ResolvedDashboardFile resolved = resolveDashboardFile(selection);
                    if (!expectedFingerprint.equals(resolved.fingerprint)) {
                        throw new IOException(
                                "The selected file changed while its quick preview was read."
                        );
                    }
                    return readOrcaQuickPreview(requestId, resolved.file);
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(previewBytes -> {
                    if (previewBytes == null || previewBytes.length == 0) {
                        beginDashboardFileDetailParse(
                                requestId,
                                selection,
                                expectedFingerprint,
                                digest
                        );
                        return;
                    }
                    startDashboardQuickPreviewParser(
                            requestId,
                            selection,
                            expectedFingerprint,
                            digest,
                            previewBytes
                    );
                }, error -> {
                    LogHelper.log(error);
                    // Quick metadata is an optional latency optimization. The content-bound full
                    // analysis remains authoritative and reports any real read/format failure.
                    beginDashboardFileDetailParse(
                            requestId,
                            selection,
                            expectedFingerprint,
                            digest
                    );
                });
        replaceDashboardFileDetailDigestDisposable(requestId, quickRead);
    }

    private void startDashboardQuickPreviewParser(
            long requestId,
            DashboardGcodeFileSelector.Selection selection,
            String expectedFingerprint,
            String digest,
            byte[] previewBytes
    ) {
        GcodeParser parser = new GcodeParser();
        AtomicBoolean parseStarted = new AtomicBoolean(false);
        Disposable parseDisposable = parser.getParseProgressObservable()
                .distinctUntilChanged()
                .filter(progress -> {
                    if (progress == 0) parseStarted.set(true);
                    return parseStarted.get();
                })
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(progress -> {
                    if (progress < 0) {
                        finishDashboardQuickPreview(
                                requestId,
                                selection,
                                expectedFingerprint,
                                digest,
                                parser,
                                false
                        );
                    } else if (progress >= 100) {
                        finishDashboardQuickPreview(
                                requestId,
                                selection,
                                expectedFingerprint,
                                digest,
                                parser,
                                true
                        );
                    }
                }, error -> {
                    LogHelper.log(error);
                    finishDashboardQuickPreview(
                            requestId,
                            selection,
                            expectedFingerprint,
                            digest,
                            parser,
                            false
                    );
                });
        synchronized (mFileDetailLock) {
            if (mFileDetailState == null
                    || mFileDetailState.requestId != requestId
                    || !mFileDetailState.pending) {
                parseDisposable.dispose();
                parser.destroy();
                return;
            }
            mFileDetailParser = parser;
            mFileDetailParseDisposable = parseDisposable;
        }
        mDisposable.add(parseDisposable);
        parser.startParse(new ByteArrayInputStream(previewBytes), IMachine.WorkType.FDM);
    }

    private void finishDashboardQuickPreview(
            long requestId,
            DashboardGcodeFileSelector.Selection selection,
            String expectedFingerprint,
            String digest,
            GcodeParser parser,
            boolean parsed
    ) {
        Disposable disposable;
        synchronized (mFileDetailLock) {
            if (mFileDetailState == null
                    || mFileDetailState.requestId != requestId
                    || !mFileDetailState.pending
                    || mFileDetailParser != parser) {
                parser.destroy();
                return;
            }
            disposable = mFileDetailParseDisposable;
            mFileDetailParseDisposable = null;
            mFileDetailParser = null;
            if (parsed) {
                mFileDetailState.metadata = DashboardFileMetadata.previewFromParser(parser);
                mFileDetailState.thumbnailPng = bitmapToPng(parser.getGcodeThumbnail());
                mFileDetailState.metadataReady = true;
                mFileDetailState.safetyReady = false;
                // Reserve 100 for the complete content-bound analysis.
                mFileDetailState.progress = Math.max(mFileDetailState.progress, 1);
            }
        }
        disposeAndForget(disposable);
        parser.destroy();
        beginDashboardFileDetailParse(
                requestId,
                selection,
                expectedFingerprint,
                digest
        );
    }

    private void replaceDashboardFileDetailDigestDisposable(
            long requestId,
            Disposable replacement
    ) {
        Disposable previous = null;
        synchronized (mFileDetailLock) {
            if (mFileDetailState != null
                    && mFileDetailState.requestId == requestId
                    && mFileDetailState.pending) {
                previous = mFileDetailDigestDisposable;
                mFileDetailDigestDisposable = replacement;
            } else {
                replacement.dispose();
            }
        }
        disposeAndForget(previous);
        mDisposable.add(replacement);
    }

    private byte[] readOrcaQuickPreview(long requestId, IFile file) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(256 * 1024);
        boolean formatConfirmed = false;
        boolean executableReached = false;
        int nextWatchdogRefresh = 4 * 1024 * 1024;
        FileInputStream prefixInput = file.getInputStream();
        registerDashboardFileDetailInput(requestId, prefixInput);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                prefixInput,
                StandardCharsets.UTF_8
        ), 64 * 1024)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!isDashboardFileDetailPending(requestId)) {
                    throw new InterruptedIOException("File analysis was cancelled.");
                }
                String normalized = line;
                if (!formatConfirmed) {
                    normalized = normalized.trim();
                    if (normalized.isEmpty()) continue;
                    if (!normalized.isEmpty() && normalized.charAt(0) == '\ufeff') {
                        normalized = normalized.substring(1).trim();
                    }
                    if (!"; HEADER_BLOCK_START".equalsIgnoreCase(normalized)) return null;
                    formatConfirmed = true;
                }
                if (isQuickPreviewLineCountHeader(line)) {
                    // Prevent the legacy fallback used for this synthetic prefix+footer stream
                    // from terminating immediately after the header. The authoritative scan
                    // supplies the exact physical count; quick preview must reach the thumbnail
                    // and footer configuration instead.
                    continue;
                }
                byte[] encoded = (line + "\n").getBytes(StandardCharsets.UTF_8);
                if (output.size() + encoded.length > MAX_ORCA_QUICK_PREFIX_BYTES) return null;
                output.write(encoded);
                if (output.size() >= nextWatchdogRefresh) {
                    nextWatchdogRefresh += 4 * 1024 * 1024;
                    scheduleDashboardFileDetailWatchdog(requestId, file.length());
                }
                if ("; EXECUTABLE_BLOCK_START".equalsIgnoreCase(line.trim())) {
                    executableReached = true;
                    break;
                }
            }
        } finally {
            unregisterDashboardFileDetailInput(prefixInput);
        }
        if (!formatConfirmed || !executableReached) return null;

        // Orca stores important slicer configuration after EXECUTABLE_BLOCK_END. Seek to a
        // bounded tail window instead of reading millions of motion lines a second time.
        long tailStart = Math.max(0L, file.length() - MAX_ORCA_QUICK_FOOTER_BYTES);
        FileInputStream tailInput = file.getInputStream();
        registerDashboardFileDetailInput(requestId, tailInput);
        try {
            tailInput.getChannel().position(tailStart);
            try (BufferedReader tailReader = new BufferedReader(new InputStreamReader(
                    tailInput,
                    StandardCharsets.UTF_8
            ), 64 * 1024)) {
                if (tailStart > 0L) tailReader.readLine(); // discard a potentially partial line
                boolean footerReached = false;
                int footerBytes = 0;
                String line;
                while ((line = tailReader.readLine()) != null) {
                    if (!isDashboardFileDetailPending(requestId)) {
                        throw new InterruptedIOException("File analysis was cancelled.");
                    }
                    if (!footerReached) {
                        if (!"; EXECUTABLE_BLOCK_END".equalsIgnoreCase(line.trim())) continue;
                        footerReached = true;
                    }
                    byte[] encoded = (line + "\n").getBytes(StandardCharsets.UTF_8);
                    footerBytes += encoded.length;
                    if (footerBytes > MAX_ORCA_QUICK_FOOTER_BYTES) break;
                    output.write(encoded);
                    if (output.size() >= nextWatchdogRefresh) {
                        nextWatchdogRefresh += 4 * 1024 * 1024;
                        scheduleDashboardFileDetailWatchdog(requestId, file.length());
                    }
                }
            }
        } finally {
            unregisterDashboardFileDetailInput(tailInput);
        }
        return output.toByteArray();
    }

    private boolean isQuickPreviewLineCountHeader(String line) {
        if (line == null) return false;
        String normalized = line.trim();
        if (!normalized.startsWith(";")) return false;
        normalized = normalized.substring(1).trim();
        int separator = normalized.indexOf(':');
        if (separator >= 0) normalized = normalized.substring(0, separator).trim();
        int whitespace = normalized.indexOf(' ');
        if (whitespace >= 0) normalized = normalized.substring(0, whitespace).trim();
        return "Lines".equalsIgnoreCase(normalized)
                || "file_total_lines".equalsIgnoreCase(normalized);
    }

    private void beginDashboardFileDetailParse(
            long requestId,
            DashboardGcodeFileSelector.Selection selection,
            String expectedFingerprint,
            String digest
    ) {
        try {
            ResolvedDashboardFile resolved = resolveDashboardFile(selection);
            if (!expectedFingerprint.equals(resolved.fingerprint)) {
                finishDashboardFileDetail(
                        requestId,
                        null,
                        null,
                        "The selected file changed while its preview was prepared."
                );
                return;
            }

            GcodeParser parser = new GcodeParser();
            synchronized (mFileDetailLock) {
                if (mFileDetailState == null
                        || mFileDetailState.requestId != requestId
                        || !mFileDetailState.pending) {
                    parser.destroy();
                    return;
                }
                mFileDetailParser = parser;
                mFileDetailState.sha256 = "";
            }
            Disposable parseDisposable = parser.getParseProgressObservable()
                    .distinctUntilChanged()
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(progress -> handleDashboardFileDetailProgress(
                            requestId,
                            selection,
                            expectedFingerprint,
                            parser,
                            progress
                    ), error -> {
                        LogHelper.log(error);
                        finishDashboardFileDetail(
                                requestId,
                                null,
                                null,
                                "Unable to parse the selected G-code file."
                        );
                    });
            synchronized (mFileDetailLock) {
                if (mFileDetailState != null
                        && mFileDetailState.requestId == requestId
                        && mFileDetailState.pending) {
                    mFileDetailParseDisposable = parseDisposable;
                } else {
                    parseDisposable.dispose();
                    parser.destroy();
                    return;
                }
            }
            mDisposable.add(parseDisposable);
            parser.startParse(resolved.file, IMachine.WorkType.FDM);
        } catch (Exception error) {
            LogHelper.log(error);
            finishDashboardFileDetail(
                    requestId,
                    null,
                    null,
                    "Unable to parse the selected G-code file."
            );
        }
    }

    private void handleDashboardFileDetailProgress(
            long requestId,
            DashboardGcodeFileSelector.Selection selection,
            String expectedFingerprint,
            GcodeParser parser,
            int progress
    ) {
        if (progress < 0) {
            finishDashboardFileDetail(
                    requestId,
                    null,
                    null,
                    "The selected file is not valid readable G-code."
            );
            return;
        }
        synchronized (mFileDetailLock) {
            if (mFileDetailState == null
                    || mFileDetailState.requestId != requestId
                    || !mFileDetailState.pending) return;
            mFileDetailState.progress = Math.max(
                    mFileDetailState.progress,
                    Math.min(100, progress)
            );
        }
        if (!scheduleDashboardFileDetailWatchdog(
                requestId,
                dashboardFileDetailSize(requestId)
        )) return;
        if (progress < 100) return;

        try {
            ResolvedDashboardFile resolved = resolveDashboardFile(selection);
            if (!expectedFingerprint.equals(resolved.fingerprint)) {
                finishDashboardFileDetail(
                        requestId,
                        null,
                        null,
                        "The selected file changed while it was parsed."
                );
                return;
            }
            String digest = parser.getLastSha256ForAnalysis();
            String md5 = parser.getLastMd5ForAnalysis();
            if (!isHexDigest(digest, 64) || !isHexDigest(md5, 32)) {
                finishDashboardFileDetail(
                        requestId,
                        null,
                        null,
                        "Unable to bind the safety analysis to the complete file contents."
                );
                return;
            }
            DashboardFileMetadata metadata = DashboardFileMetadata.fromParser(
                    parser,
                    digest,
                    md5
            );
            byte[] thumbnail = bitmapToPng(parser.getGcodeThumbnail());
            finishDashboardFileDetail(requestId, metadata, thumbnail, "");
        } catch (Exception error) {
            LogHelper.log(error);
            finishDashboardFileDetail(
                    requestId,
                    null,
                    null,
                    "Unable to finalize the parsed file details."
            );
        }
    }

    private void finishDashboardFileDetail(
            long requestId,
            DashboardFileMetadata metadata,
            byte[] thumbnail,
            String error
    ) {
        Disposable digestDisposable;
        Disposable parseDisposable;
        Runnable timeout;
        GcodeParser parser;
        InputStream analysisInput;
        synchronized (mFileDetailLock) {
            if (mFileDetailState == null
                    || mFileDetailState.requestId != requestId
                    || !mFileDetailState.pending) return;
            mFileDetailState.pending = false;
            mFileDetailState.progress = metadata == null ? mFileDetailState.progress : 100;
            mFileDetailState.metadata = metadata;
            mFileDetailState.sha256 = metadata == null ? "" : metadata.sha256;
            mFileDetailState.thumbnailPng = thumbnail;
            mFileDetailState.error = emptyIfNull(error);
            mFileDetailState.metadataReady = metadata != null;
            if (metadata != null && TextUtils.isEmpty(error)) {
                mFileDetailState.safetyReady = true;
                refreshDashboardFileCompatibilityLocked(mFileDetailState);
                mFileAnalysisCache.put(
                        mFileDetailState.fingerprint,
                        metadata.sha256,
                        mFileDetailState.file.sizeBytes,
                        new DashboardCachedFileAnalysis(metadata, thumbnail)
                );
            } else {
                mFileDetailState.safetyReady = false;
            }
            digestDisposable = mFileDetailDigestDisposable;
            parseDisposable = mFileDetailParseDisposable;
            timeout = mFileDetailTimeout;
            parser = mFileDetailParser;
            analysisInput = mFileDetailAnalysisInput;
            mFileDetailDigestDisposable = null;
            mFileDetailParseDisposable = null;
            mFileDetailTimeout = null;
            mFileDetailParser = null;
            mFileDetailAnalysisInput = null;
            mFileDetailWatchdogGeneration++;
        }
        disposeAndForget(digestDisposable);
        disposeAndForget(parseDisposable);
        if (timeout != null) mMainHandler.removeCallbacks(timeout);
        closeQuietly(analysisInput);
        if (parser != null) parser.destroy();
    }

    private void refreshDashboardFileCompatibilityLocked(DashboardFileDetailState state) {
        if (state == null || state.metadata == null) return;
        IMachine machine = ServiceContainer.getInstance().getService(IMachine.class);
        state.nozzleDiameterMismatch = inspectDashboardNozzleDiameterMismatch(
                state.metadata,
                machine
        );
        state.warnings = buildDashboardFileWarnings(
                state.metadata,
                machine,
                state.nozzleDiameterMismatch
        );
        state.blockingIssues = buildDashboardFileBlockingIssues(state.metadata);
    }

    private long dashboardFileDetailSize(long requestId) {
        synchronized (mFileDetailLock) {
            return mFileDetailState != null && mFileDetailState.requestId == requestId
                    ? mFileDetailState.file.sizeBytes : 0L;
        }
    }

    private boolean scheduleDashboardFileDetailWatchdog(long requestId, long sizeBytes) {
        final Runnable previous;
        final Runnable watchdog;
        synchronized (mFileDetailLock) {
            if (mFileDetailState == null
                    || mFileDetailState.requestId != requestId
                    || !mFileDetailState.pending) return false;
            long generation = ++mFileDetailWatchdogGeneration;
            previous = mFileDetailTimeout;
            watchdog = () -> timeoutDashboardFileDetail(requestId, generation);
            mFileDetailTimeout = watchdog;
        }
        if (previous != null) mMainHandler.removeCallbacks(previous);
        long delay = DashboardFileAnalysisPolicy.analysisStallTimeoutMs(sizeBytes);
        if (mMainHandler.postDelayed(watchdog, delay)) return true;
        finishDashboardFileDetail(
                requestId,
                null,
                null,
                "Unable to schedule the file analysis watchdog."
        );
        return false;
    }

    private void timeoutDashboardFileDetail(long requestId, long generation) {
        synchronized (mFileDetailLock) {
            if (mFileDetailState == null
                    || mFileDetailState.requestId != requestId
                    || !mFileDetailState.pending
                    || generation != mFileDetailWatchdogGeneration) return;
        }
        finishDashboardFileDetail(
                requestId,
                null,
                null,
                "File analysis stopped making progress. Check the storage device and try again."
        );
    }

    private DashboardFileMetadata getReadyDashboardFilePreview(
            long requestId,
            String fingerprint
    ) {
        synchronized (mFileDetailLock) {
            if (mFileDetailState == null
                    || mFileDetailState.requestId != requestId
                    || mFileDetailState.pending
                    || mFileDetailState.consumed
                    || !mFileDetailState.safetyReady
                    || !TextUtils.isEmpty(mFileDetailState.error)
                    || !mFileDetailState.fingerprint.equals(fingerprint)
                    || mFileDetailState.metadata == null
                    || TextUtils.isEmpty(mFileDetailState.metadata.sha256)) {
                return null;
            }
            return mFileDetailState.metadata;
        }
    }

    private DashboardNozzleDiameterMismatch getReadyDashboardNozzleDiameterMismatch(
            long requestId
    ) {
        synchronized (mFileDetailLock) {
            if (mFileDetailState == null
                    || mFileDetailState.requestId != requestId
                    || mFileDetailState.pending
                    || mFileDetailState.consumed
                    || mFileDetailState.nozzleDiameterMismatch == null) return null;
            return mFileDetailState.nozzleDiameterMismatch;
        }
    }

    private byte[] bitmapToPng(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return null;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        return bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                ? output.toByteArray()
                : null;
    }

    private byte[] decodeDashboardListThumbnail(String encodedData) {
        if (TextUtils.isEmpty(encodedData)
                || encodedData.length()
                > DashboardGcodeThumbnailExtractor.MAX_ENCODED_CHARACTERS) return null;
        final byte[] imageBytes;
        try {
            imageBytes = Base64.decode(encodedData, Base64.DEFAULT);
        } catch (IllegalArgumentException invalidBase64) {
            return null;
        }
        if (imageBytes.length == 0
                || imageBytes.length > DashboardGcodeThumbnailExtractor.MAX_ENCODED_CHARACTERS) {
            return null;
        }

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length, bounds);
        long pixels = (long) bounds.outWidth * bounds.outHeight;
        if (bounds.outWidth <= 0
                || bounds.outHeight <= 0
                || bounds.outWidth > 2048
                || bounds.outHeight > 2048
                || pixels <= 0L
                || pixels > 4_000_000L) return null;

        BitmapFactory.Options decode = new BitmapFactory.Options();
        decode.inSampleSize = 1;
        while (bounds.outWidth / (decode.inSampleSize * 2)
                >= MAX_LIST_THUMBNAIL_EDGE_PX
                || bounds.outHeight / (decode.inSampleSize * 2)
                >= MAX_LIST_THUMBNAIL_EDGE_PX) {
            decode.inSampleSize *= 2;
        }

        Bitmap decoded = null;
        Bitmap reduced = null;
        try {
            decoded = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length, decode);
            if (decoded == null || decoded.isRecycled()) return null;
            int width = decoded.getWidth();
            int height = decoded.getHeight();
            float scale = Math.min(
                    1f,
                    Math.min(
                            (float) MAX_LIST_THUMBNAIL_EDGE_PX / width,
                            (float) MAX_LIST_THUMBNAIL_EDGE_PX / height
                    )
            );
            int targetWidth = Math.max(1, Math.round(width * scale));
            int targetHeight = Math.max(1, Math.round(height * scale));
            reduced = targetWidth == width && targetHeight == height
                    ? decoded
                    : Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true);
            if (reduced == null || reduced.isRecycled()) return null;
            ByteArrayOutputStream output = new ByteArrayOutputStream(64 * 1024);
            if (!reduced.compress(Bitmap.CompressFormat.PNG, 100, output)
                    || output.size() <= 0
                    || output.size() > MAX_LIST_THUMBNAIL_OUTPUT_BYTES) return null;
            return output.toByteArray();
        } finally {
            if (reduced != null && reduced != decoded && !reduced.isRecycled()) reduced.recycle();
            if (decoded != null && !decoded.isRecycled()) decoded.recycle();
        }
    }

    private void writeDashboardListThumbnail(HttpResponse response, byte[] png) {
        response.setStatus(StatusCode.SC_OK);
        response.setBody(new StreamBody(
                new ByteArrayInputStream(png),
                png.length,
                MediaType.IMAGE_PNG
        ));
    }

    private void writeDashboardThumbnailRetry(
            HttpResponse response,
            int status,
            String state,
            String message
    ) {
        response.setHeader("Retry-After", "1");
        try {
            JSONObject result = new JSONObject();
            result.put("ok", false);
            result.put("state", state);
            result.put("retryable", true);
            result.put("retryAfterMs", LIST_THUMBNAIL_RETRY_AFTER_MS);
            result.put("error", message);
            writeJson(response, status, result);
        } catch (JSONException error) {
            writeJsonError(response, status, message);
        }
    }

    private String sha256(long requestId, IFile file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        InputStream input = file.getInputStream();
        registerDashboardFileDetailInput(requestId, input);
        try {
            byte[] buffer = new byte[64 * 1024];
            long sinceWatchdogRefresh = 0L;
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read <= 0) continue;
                if (!isDashboardFileDetailPending(requestId)) {
                    throw new InterruptedIOException("File analysis was cancelled.");
                }
                digest.update(buffer, 0, read);
                sinceWatchdogRefresh += read;
                if (sinceWatchdogRefresh >= 4L * 1024L * 1024L) {
                    sinceWatchdogRefresh = 0L;
                    scheduleDashboardFileDetailWatchdog(requestId, file.length());
                }
            }
        } finally {
            unregisterDashboardFileDetailInput(input);
            closeQuietly(input);
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) {
            result.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private void registerDashboardFileDetailInput(long requestId, InputStream input)
            throws InterruptedIOException {
        synchronized (mFileDetailLock) {
            if (mFileDetailState == null
                    || mFileDetailState.requestId != requestId
                    || !mFileDetailState.pending) {
                closeQuietly(input);
                throw new InterruptedIOException("File analysis was cancelled.");
            }
            mFileDetailAnalysisInput = input;
        }
    }

    private void unregisterDashboardFileDetailInput(InputStream input) {
        synchronized (mFileDetailLock) {
            if (mFileDetailAnalysisInput == input) mFileDetailAnalysisInput = null;
        }
    }

    private boolean isDashboardFileDetailPending(long requestId) {
        synchronized (mFileDetailLock) {
            return mFileDetailState != null
                    && mFileDetailState.requestId == requestId
                    && mFileDetailState.pending;
        }
    }

    private boolean isHexDigest(String value, int expectedLength) {
        if (value == null || value.length() != expectedLength) return false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (!((current >= '0' && current <= '9')
                    || (current >= 'a' && current <= 'f')
                    || (current >= 'A' && current <= 'F'))) return false;
        }
        return true;
    }

    private void closeQuietly(InputStream input) {
        if (input == null) return;
        try {
            input.close();
        } catch (IOException error) {
            LogHelper.log(error);
        }
    }

    private Long parsePositiveLong(String value) {
        if (TextUtils.isEmpty(value)) return null;
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isDigit(value.charAt(index))) return null;
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0L ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Long parseNonNegativeLong(String value) {
        if (TextUtils.isEmpty(value)) return 0L;
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isDigit(value.charAt(index))) return null;
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed >= 0L ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String parseDashboardCameraClientId(String value) {
        if (TextUtils.isEmpty(value) || value.length() > 64) return null;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            boolean allowed = (current >= 'a' && current <= 'z')
                    || (current >= 'A' && current <= 'Z')
                    || (current >= '0' && current <= '9')
                    || current == '.' || current == '_' || current == '~' || current == '-';
            if (!allowed) return null;
        }
        return value;
    }

    private String dashboardFileStartPreflight(
            long requestId,
            IMachine machine,
            IAppService appService,
            DashboardFileMetadata metadata
    ) {
        DashboardNozzleDiameterMismatch expectedMismatch;
        boolean mismatchConfirmed;
        synchronized (mFileStartLock) {
            if (mFileStartState == null
                    || mFileStartState.requestId != requestId
                    || !mFileStartState.pending) {
                return "The dashboard print-start request is no longer active.";
            }
            expectedMismatch = mFileStartState.nozzleDiameterMismatch;
            mismatchConfirmed = mFileStartState.nozzleDiameterMismatchConfirmed;
        }
        return dashboardFileStartPreflight(
                machine,
                appService,
                metadata,
                expectedMismatch,
                mismatchConfirmed
        );
    }

    private String dashboardFileStartPreflight(
            IMachine machine,
            IAppService appService,
            DashboardFileMetadata metadata,
            DashboardNozzleDiameterMismatch expectedMismatch,
            boolean nozzleDiameterMismatchConfirmed
    ) {
        if (metadata.fileType != IMachine.WorkType.FDM) {
            return "The selected file is not 3D-printing G-code.";
        }
        MachineStatus machineStatus = machine.getMachineStatusSubjectHolder().getValue();
        if (machineStatus == null || !machineStatus.connected) {
            return "Printer is disconnected.";
        }
        MachineInfo info = machine.getMachineInfoSubjectHolder().getValue();
        if (info == null || info.moduleList == null || info.workType != IMachine.WorkType.FDM) {
            return "3D printing mode and live module information are required.";
        }
        if (machine.getNewPrintController().getPrintState()
                != MachineOperationStatus.SYSTEM_STATUS_IDLE.value()) {
            return "Printer must be exactly idle before starting another print.";
        }
        if (machine.getNewPrintController().getStartFromRemoteFlag()
                && !hasDashboardOwnedRemoteStartGate()) {
            return "Another remote print start is still settling.";
        }
        if (appService.getEmergencyStopState()
                != ErrorController.EmergencyStopState.EMERGENCY_STOP_STATE_NORMAL) {
            return "Emergency stop is active.";
        }
        synchronized (mActionLock) {
            if (mActionPending) return "Another job control is in progress.";
        }
        synchronized (mConsoleLock) {
            if (mConsoleCommandPending) return "A G-code console command is in progress.";
        }

        if (expectedMismatch == null) {
            return "The confirmed nozzle compatibility result is unavailable.";
        }
        if (expectedMismatch.detected && !nozzleDiameterMismatchConfirmed) {
            return "Confirm the inconsistent nozzle diameter warning to continue, or cancel.";
        }
        return "";
    }

    private List<DashboardCompatibilityIssue> buildDashboardFileWarnings(
            DashboardFileMetadata metadata,
            IMachine machine,
            DashboardNozzleDiameterMismatch nozzleDiameterMismatch
    ) {
        List<DashboardCompatibilityIssue> warnings = new ArrayList<>();
        warnings.add(DashboardCompatibilityIssue.warning(
                "clean_bed_and_nozzles",
                "Clean the heated bed and every nozzle used by this job before starting."
        ));
        if (metadata.toolheadType >= 0) {
            FDMController fdmController = machine == null ? null : machine.getFDMController();
            int machineHeadType = fdmController == null ? -1 : fdmController.getHeadType();
            if (machineHeadType >= 0 && metadata.toolheadType != machineHeadType) {
                warnings.add(DashboardCompatibilityIssue.warning(
                        "toolhead_mismatch",
                        "The G-code toolhead does not match the installed 3D-printing toolhead."
                ));
            }
        }
        if (nozzleDiameterMismatch != null && nozzleDiameterMismatch.detected) {
            warnings.add(DashboardCompatibilityIssue.warning(
                    NOZZLE_DIAMETER_MISMATCH_ID,
                    "The nozzle diameter defined by the G-code file is inconsistent with "
                            + "the machine, which may cause problems."
            ));
        }
        if ((metadata.requiresLeftNozzle() && metadata.extruder0RetractionMm > 2f)
                || (metadata.requiresRightNozzle()
                && metadata.extruder1RetractionMm > 2f)) {
            warnings.add(DashboardCompatibilityIssue.warning(
                    "retraction_over_2mm",
                    "G-code retraction exceeds 2 mm. The original Artisan HMI allows "
                            + "the operator to continue after reviewing this warning."
            ));
        }
        return warnings;
    }

    private List<DashboardCompatibilityIssue> buildDashboardFileBlockingIssues(
            DashboardFileMetadata metadata
    ) {
        List<DashboardCompatibilityIssue> issues = new ArrayList<>();
        if (metadata.fileType != IMachine.WorkType.FDM) {
            issues.add(DashboardCompatibilityIssue.error(
                    "not_fdm_gcode", "The selected file is not 3D-printing G-code."
            ));
        }
        return issues;
    }

    private boolean hasDashboardOwnedRemoteStartGate() {
        synchronized (mFileStartLock) {
            return mFileStartRemoteGateClaimed
                    && mFileStartState != null
                    && mFileStartState.pending;
        }
    }

    private DashboardNozzleDiameterMismatch inspectDashboardNozzleDiameterMismatch(
            DashboardFileMetadata metadata,
            IMachine machine
    ) {
        FdmToolhead.FdmToolheadStatus fdmStatus = machine == null
                ? null : getFdmStatus(machine, true);
        List<Extruder> extruders = fdmStatus == null || fdmStatus.getExtruderList() == null
                ? Collections.emptyList()
                : snapshotList(fdmStatus.getExtruderList());
        return inspectDashboardNozzleDiameterMismatch(
                metadata,
                findExtruder(extruders, Extruder.EXTRUDER_LEFT),
                findExtruder(extruders, Extruder.EXTRUDER_RIGHT)
        );
    }

    private DashboardNozzleDiameterMismatch inspectDashboardNozzleDiameterMismatch(
            DashboardFileMetadata metadata,
            Extruder left,
            Extruder right
    ) {
        return DashboardNozzleDiameterMismatch.create(
                metadata.requiresLeftNozzle() ? metadata.nozzleDiameterLeftMm : -1f,
                metadata.requiresRightNozzle() ? metadata.nozzleDiameterRightMm : -1f,
                metadata.requiresLeftNozzle() && isExtruderTelemetryReady(left)
                        ? left.getDiameter() : -1f,
                metadata.requiresRightNozzle() && isExtruderTelemetryReady(right)
                        ? right.getDiameter() : -1f
        );
    }

    private void executeDashboardFileStart(
            long requestId,
            DashboardGcodeFileSelector.Selection selection,
            String expectedFingerprint,
            DashboardFileMetadata preview
    ) {
        synchronized (mFileStartLock) {
            if (mFileStartState == null
                    || mFileStartState.requestId != requestId
                    || !mFileStartState.pending) return;
        }

        Runnable timeout = () -> timeoutDashboardFileStart(requestId);
        long fileSizeBytes;
        synchronized (mFileStartLock) {
            mFileStartTimeout = timeout;
            fileSizeBytes = mFileStartState == null
                    || mFileStartState.requestId != requestId
                    ? 0L : mFileStartState.file.sizeBytes;
        }
        if (!mMainHandler.postDelayed(
                timeout,
                DashboardFileAnalysisPolicy.printStartTimeoutMs(fileSizeBytes)
        )) {
            finishDashboardFileStart(
                    requestId,
                    "failed",
                    "queue_failed",
                    "Unable to schedule the print preparation timeout.",
                    -1
            );
            return;
        }

        try {
            IMachine machine = ServiceContainer.getInstance().getService(IMachine.class);
            IAppService appService = ServiceContainer.getInstance().getService(IAppService.class);
            String safetyError = dashboardFileStartPreflight(
                    requestId, machine, appService, preview);
            if (!TextUtils.isEmpty(safetyError)) {
                finishDashboardFileStart(
                        requestId,
                        "failed",
                        "preflight_failed",
                        safetyError,
                        -1
                );
                return;
            }
            ResolvedDashboardFile resolved = resolveDashboardFile(selection);
            if (!expectedFingerprint.equals(resolved.fingerprint)) {
                finishDashboardFileStart(
                        requestId,
                        "failed",
                        "file_changed",
                        "The selected file changed after its preview was confirmed.",
                        -1
                );
                return;
            }

            startDashboardFileStaging(
                    requestId,
                    selection,
                    expectedFingerprint,
                    preview,
                    resolved
            );
        } catch (Exception error) {
            LogHelper.log(error);
            finishDashboardFileStart(
                    requestId,
                    "failed",
                    "preflight_failed",
                    "Unable to revalidate the selected file.",
                    -1
            );
        }
    }

    private void startDashboardFileStaging(
            long requestId,
            DashboardGcodeFileSelector.Selection selection,
            String expectedFingerprint,
            DashboardFileMetadata preview,
            ResolvedDashboardFile resolved
    ) {
        Disposable prepareDisposable = Observable.fromCallable(() ->
                        prepareDashboardPrintFile(requestId, resolved)
                )
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(prepared -> {
                        if (!preview.sha256.equals(prepared.sha256)) {
                            finishDashboardFileStart(
                                    requestId,
                                    "failed",
                                    "file_changed",
                                    "The selected file no longer matches the confirmed preview.",
                                    -1
                            );
                            return;
                        }
                        applyCachedDashboardPrintAnalysis(
                                requestId,
                                preview,
                                prepared
                        );
                    }, error -> {
                        LogHelper.log(error);
                        finishDashboardFileStart(
                                requestId,
                                "failed",
                                "staging_failed",
                                error.getMessage() == null
                                        ? "Unable to safely stage the selected file."
                                        : error.getMessage(),
                                -1
                        );
                    });
            synchronized (mFileStartLock) {
                if (mFileStartState != null
                        && mFileStartState.requestId == requestId
                        && mFileStartState.pending) {
                    mFileStartMd5Disposable = prepareDisposable;
                } else {
                    prepareDisposable.dispose();
                }
            }
            mDisposable.add(prepareDisposable);
    }

    private void applyCachedDashboardPrintAnalysis(
            long requestId,
            DashboardFileMetadata preview,
            PreparedDashboardPrintFile prepared
    ) {
        DashboardCachedFileAnalysis cached = mFileAnalysisCache.get(
                prepared.sha256,
                prepared.file.length()
        );
        if (cached == null
                || cached.metadata == null
                || !preview.sha256.equals(cached.metadata.sha256)
                || !isHexDigest(cached.metadata.md5, 32)
                || !cached.metadata.md5.equalsIgnoreCase(prepared.md5)) {
            finishDashboardFileStart(
                    requestId,
                    "failed",
                    "analysis_cache_lost",
                    "The content-bound safety analysis is no longer available. Open the file details and try again.",
                    -1
            );
            return;
        }

        DashboardFileMetadata analyzed = cached.metadata;
        byte[] thumbnail = cached.thumbnailPng();
        IGcodeParser sharedParser = ServiceContainer.getInstance().getService(IGcodeParser.class);
        if (sharedParser instanceof GcodeParser) {
            Bitmap bitmap = thumbnail == null ? null
                    : BitmapFactory.decodeByteArray(thumbnail, 0, thumbnail.length);
            ((GcodeParser) sharedParser).adoptRemotePrintThumbnail(bitmap, thumbnail);
        }

        updateDashboardFileStartState(requestId, "validating_snapshot");
        applyDashboardParsedPrint(requestId, analyzed, prepared);
    }

    private PreparedDashboardPrintFile prepareDashboardPrintFile(
            long requestId,
            ResolvedDashboardFile resolved
    ) throws Exception {
        synchronized (mUploadLock) {
            IAppService appService = ServiceContainer.getInstance().getService(IAppService.class);
            File filesRoot = appService.getFilesDir().getCanonicalFile();
            long size = resolved.file.length();
            if (filesRoot.getUsableSpace() - size < LOCAL_STORAGE_RESERVE_BYTES) {
                throw new IOException(
                        "Not enough local storage while preserving the 300 MiB safety reserve."
                );
            }
            File stagingRoot = new File(filesRoot, ".dashboard-staging").getCanonicalFile();
            if (!stagingRoot.getPath().startsWith(filesRoot.getPath() + File.separator)) {
                throw new IOException("Invalid private staging root.");
            }
            if (!stagingRoot.exists() && !stagingRoot.mkdir()) {
                throw new IOException("Unable to create the private staging root.");
            }
            cleanStaleDashboardStaging(stagingRoot);
            File requestDirectory = new File(
                    stagingRoot,
                    "request-" + requestId + "-" + UUID.randomUUID()
            ).getCanonicalFile();
            if (!requestDirectory.getPath().startsWith(stagingRoot.getPath() + File.separator)
                    || !requestDirectory.mkdir()) {
                throw new IOException("Unable to create a collision-safe staging directory.");
            }
            File target = new File(requestDirectory, resolved.info.name).getCanonicalFile();
            if (!requestDirectory.equals(target.getParentFile()) || target.exists()) {
                throw new IOException("Invalid or colliding staging target.");
            }
            if (!registerDashboardStagingFile(requestId, target)) {
                deleteDashboardStagingDirectory(requestDirectory);
                throw new IOException("The print-start request ended before staging began.");
            }

            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            long copied = 0L;
            InputStream stagingInput = null;
            try {
                stagingInput = resolved.file.getInputStream();
                registerDashboardStagingInput(requestId, stagingInput);
                try (InputStream input = stagingInput;
                     FileOutputStream output = new FileOutputStream(target, false)) {
                    byte[] buffer = new byte[64 * 1024];
                    while (true) {
                        if (!isDashboardFileStartPending(requestId)) {
                            throw new InterruptedIOException(
                                    "The print-start request ended during staging."
                            );
                        }
                        int read = input.read(buffer);
                        if (read == -1) break;
                        if (read <= 0) continue;
                        // A timeout can race with a successful read. Do not write another chunk
                        // after the request has been cancelled.
                        if (!isDashboardFileStartPending(requestId)) {
                            throw new InterruptedIOException(
                                    "The print-start request ended during staging."
                            );
                        }
                        output.write(buffer, 0, read);
                        sha256.update(buffer, 0, read);
                        md5.update(buffer, 0, read);
                        copied += read;
                    }
                    output.flush();
                    output.getFD().sync();
                }
            } catch (Exception error) {
                if (target.exists() && !target.delete()) {
                    Logger.w("Unable to remove failed dashboard staging file: %s", target);
                }
                if (requestDirectory.exists() && !requestDirectory.delete()) {
                    Logger.w("Unable to remove failed dashboard staging directory: %s",
                            requestDirectory);
                }
                throw error;
            } finally {
                unregisterDashboardStagingInput(stagingInput);
                closeQuietly(stagingInput);
            }
            if (copied != size || target.length() != size) {
                deleteDashboardStagingFile(target);
                throw new IOException("The staged USB file size did not match its source.");
            }
            target.setLastModified(resolved.info.modifiedAt);
            if (!isDashboardFileStartPending(requestId)) {
                deleteDashboardStagingFile(target);
                throw new IOException("The print-start request ended during staging.");
            }
            return new PreparedDashboardPrintFile(
                    new FabLocalFile(target),
                    hexDigest(sha256.digest()),
                    hexDigest(md5.digest()),
                    target
            );
        }
    }

    private boolean registerDashboardStagingFile(long requestId, File stagedFile) {
        synchronized (mFileStartLock) {
            if (mFileStartState == null
                    || mFileStartState.requestId != requestId
                    || !mFileStartState.pending) return false;
            mFileStartState.stagedFile = stagedFile;
            return true;
        }
    }

    private void registerDashboardStagingInput(long requestId, InputStream input)
            throws InterruptedIOException {
        synchronized (mFileStartLock) {
            if (mFileStartState == null
                    || mFileStartState.requestId != requestId
                    || !mFileStartState.pending) {
                closeQuietly(input);
                throw new InterruptedIOException(
                        "The print-start request ended before staging began."
                );
            }
            mFileStartStagingInput = input;
        }
    }

    private void unregisterDashboardStagingInput(InputStream input) {
        synchronized (mFileStartLock) {
            if (mFileStartStagingInput == input) mFileStartStagingInput = null;
        }
    }

    private boolean isDashboardFileStartPending(long requestId) {
        synchronized (mFileStartLock) {
            return mFileStartState != null
                    && mFileStartState.requestId == requestId
                    && mFileStartState.pending;
        }
    }

    private void cleanStaleDashboardStaging(File stagingRoot) {
        try {
            IMachine machine = ServiceContainer.getInstance().getService(IMachine.class);
            if (machine.getNewPrintController().getPrintState()
                    != MachineOperationStatus.SYSTEM_STATUS_IDLE.value()) return;
            File[] entries = stagingRoot.listFiles();
            if (entries == null) return;
            long cutoff = System.currentTimeMillis() - STAGED_FILE_MAX_IDLE_AGE_MS;
            for (File entry : entries) {
                if (entry != null && entry.isDirectory() && entry.lastModified() < cutoff) {
                    deleteDashboardStagingDirectory(entry);
                }
            }
        } catch (Exception error) {
            LogHelper.log(error);
        }
    }

    private void deleteDashboardStagingFile(File stagedFile) {
        if (stagedFile == null) return;
        try {
            IAppService appService = ServiceContainer.getInstance().getService(IAppService.class);
            File filesRoot = appService.getFilesDir().getCanonicalFile();
            File stagingRoot = new File(filesRoot, ".dashboard-staging").getCanonicalFile();
            File target = stagedFile.getCanonicalFile();
            File requestDirectory = target.getParentFile();
            if (requestDirectory == null
                    || !requestDirectory.getPath().startsWith(
                    stagingRoot.getPath() + File.separator)) return;
            deleteDashboardStagingDirectory(requestDirectory);
        } catch (Exception error) {
            LogHelper.log(error);
        }
    }

    private void deleteDashboardStagingDirectory(File directory) {
        if (directory == null || !directory.isDirectory()) return;
        File[] children = directory.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    deleteDashboardStagingDirectory(child);
                } else if (!child.delete()) {
                    Logger.w("Unable to remove dashboard staging file: %s", child);
                }
            }
        }
        if (!directory.delete()) {
            Logger.w("Unable to remove dashboard staging directory: %s", directory);
        }
    }

    private DigestPair digestFile(IFile file) throws Exception {
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        try (InputStream input = file.getInputStream()) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read <= 0) continue;
                sha256.update(buffer, 0, read);
                md5.update(buffer, 0, read);
            }
        }
        return new DigestPair(hexDigest(sha256.digest()), hexDigest(md5.digest()));
    }

    private String hexDigest(byte[] digest) {
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            result.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private void applyDashboardParsedPrint(
            long requestId,
            DashboardFileMetadata parsed,
            PreparedDashboardPrintFile prepared
    ) {
        try {
            IMachine machine = ServiceContainer.getInstance().getService(IMachine.class);
            IAppService appService = ServiceContainer.getInstance().getService(IAppService.class);
            String safetyError = dashboardFileStartPreflight(
                    requestId, machine, appService, parsed);
            if (!TextUtils.isEmpty(safetyError)) {
                finishDashboardFileStart(
                        requestId, "failed", "preflight_failed", safetyError, -1);
                return;
            }
            IPrintWorkspace workspace = ServiceContainer.getInstance()
                    .getService(IPrintWorkspace.class);
            long preparationLease;
            synchronized (mFileStartLock) {
                preparationLease = mFileStartState != null
                        && mFileStartState.requestId == requestId
                        ? mFileStartState.preparationLease : 0L;
            }
            boolean workspaceApplied = machine.getNewPrintController()
                    .runWithPrintPreparationLease(preparationLease, () -> {
                        populateDashboardPrintWorkspace(parsed, prepared.md5);
                        workspace.setPrintFile(prepared.file);
                    });
            if (!workspaceApplied) {
                finishDashboardFileStart(
                        requestId, "failed", "workspace_busy",
                        "The shared print workspace reservation was lost.", -1);
                return;
            }
            setDashboardPrintBedModeAndLaunch(requestId, parsed);
        } catch (Exception error) {
            LogHelper.log(error);
            finishDashboardFileStart(
                    requestId, "failed", "workspace_failed",
                    "Unable to prepare the print workspace.", -1);
        }
    }

    private void populateDashboardPrintWorkspace(
            DashboardFileMetadata metadata,
            String md5
    ) {
        IPrintWorkspace workspace = ServiceContainer.getInstance()
                .getService(IPrintWorkspace.class);
        workspace.setPrintMode(metadata.printMode);
        workspace.setPrintSource(0);
        workspace.setFileTotalLineCount(metadata.totalLines);
        workspace.setEstimatedTime(metadata.estimatedTimeSeconds);
        workspace.setFileMD5Value(md5);
        workspace.setModelBoundary(metadata.copyBoundary());
        workspace.setApplyMultiExtruder(metadata.applyMultiExtruder);
        workspace.setPrintModeXOffset(0f);
        boolean includeRightTarget = metadata.toolUsageConfirmed
                ? metadata.usesRight : metadata.nozzleTargetRightC > 0f;
        float leftTarget = metadata.toolUsageConfirmed && !metadata.usesLeft
                ? 0f : metadata.nozzleTargetLeftC;
        float rightTarget = metadata.toolUsageConfirmed && !metadata.usesRight
                ? 0f : metadata.nozzleTargetRightC;
        if (includeRightTarget) {
            workspace.setWorkTemperature(new float[]{
                    leftTarget,
                    rightTarget
            });
        } else {
            workspace.setWorkTemperature(new float[]{leftTarget});
        }
    }

    private void setDashboardPrintBedModeAndLaunch(
            long requestId,
            DashboardFileMetadata metadata
    ) {
        IMachine machine = ServiceContainer.getInstance().getService(IMachine.class);
        IAppService appService = ServiceContainer.getInstance().getService(IAppService.class);
        String safetyError = dashboardFileStartPreflight(
                requestId, machine, appService, metadata);
        if (!TextUtils.isEmpty(safetyError)) {
            finishDashboardFileStart(
                    requestId,
                    "failed",
                    "preflight_failed",
                    safetyError,
                    -1
            );
            return;
        }
        HeatedBed heatedBed = findModule(
                machine.getMachineInfoSubjectHolder().getValue(),
                HeatedBed.class
        );
        if (heatedBed == null) {
            recordDashboardBedModeResult(requestId, "unavailable", -1);
            launchDashboardPrint(requestId, metadata);
            return;
        }
        final int controllerBedMode;
        synchronized (mFileStartLock) {
            if (mFileStartState == null
                    || mFileStartState.requestId != requestId
                    || !mFileStartState.pending) return;
            controllerBedMode = mFileStartState.controllerBedMode;
        }
        final Disposable bedDisposable;
        try {
            bedDisposable = heatedBed.setHeatedBedWorkMode(controllerBedMode)
                    .timeout(ENCLOSURE_CONTROL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .take(1)
                    .switchIfEmpty(Observable.error(
                            new IllegalStateException("Heated-bed mode returned no response.")
                    ))
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(response -> {
                        boolean confirmed = response != null && response.isSuccess();
                        int errorCode = response == null || response.resultProp == null
                                ? -1 : response.resultProp.getValue();
                        recordDashboardBedModeResult(
                                requestId,
                                confirmed ? "confirmed" : "rejected",
                                confirmed ? 0 : errorCode
                        );
                        launchDashboardPrint(requestId, metadata);
                    }, error -> {
                        LogHelper.log(error);
                        recordDashboardBedModeResult(requestId, "unavailable", -1);
                        launchDashboardPrint(requestId, metadata);
                    });
        } catch (Exception error) {
            LogHelper.log(error);
            recordDashboardBedModeResult(requestId, "unavailable", -1);
            launchDashboardPrint(requestId, metadata);
            return;
        }
        synchronized (mFileStartLock) {
            if (mFileStartState != null
                    && mFileStartState.requestId == requestId
                    && mFileStartState.pending) {
                mFileStartBedDisposable = bedDisposable;
            } else {
                bedDisposable.dispose();
            }
        }
        mDisposable.add(bedDisposable);
    }

    private void recordDashboardBedModeResult(
            long requestId,
            String result,
            int controllerErrorCode
    ) {
        synchronized (mFileStartLock) {
            if (mFileStartState == null
                    || mFileStartState.requestId != requestId
                    || !mFileStartState.pending) return;
            mFileStartState.bedModeResult = result;
            mFileStartState.bedModeConfirmed = "confirmed".equals(result);
            mFileStartState.bedModeControllerErrorCode = controllerErrorCode;
        }
    }

    private void launchDashboardPrint(long requestId, DashboardFileMetadata metadata) {
        IMachine machine = ServiceContainer.getInstance().getService(IMachine.class);
        IAppService appService = ServiceContainer.getInstance().getService(IAppService.class);
        String safetyError = dashboardFileStartPreflight(
                requestId, machine, appService, metadata);
        if (!TextUtils.isEmpty(safetyError)) {
            finishDashboardFileStart(
                    requestId,
                    "failed",
                    "preflight_failed",
                    safetyError,
                    -1
            );
            return;
        }

        try {
            NewPrintController controller = machine.getNewPrintController();
            IPrintWorkspace workspace = ServiceContainer.getInstance()
                    .getService(IPrintWorkspace.class);
            IFile printFile = workspace.getPrintFile();
            if (printFile == null) {
                finishDashboardFileStart(
                        requestId,
                        "failed",
                        "workspace_failed",
                        "The immutable print snapshot is no longer available.",
                        -1
                );
                return;
            }
            long preparationLease;
            synchronized (mFileStartLock) {
                preparationLease = mFileStartState != null
                        && mFileStartState.requestId == requestId
                        ? mFileStartState.preparationLease : 0L;
            }
            Observable<fabscreen.platform.base.service.machine.controller.PrintEvent>
                    startEvents = controller.preparePrintStart(
                    preparationLease,
                    printFile,
                    workspace.getFileTotalLineCount()
            );
            if (startEvents == null) {
                finishDashboardFileStart(
                        requestId, "failed", "workspace_busy",
                        "The controller preparation lease was lost.", -1);
                return;
            }
            Disposable eventDisposable = startEvents
                    .observeOn(AndroidSchedulers.mainThread())
                    .filter(event -> event.getPrintEventState() == PrintEventState.STATE_SUCCESS
                            || event.getPrintEventState() == PrintEventState.START_FAIL
                            || event.getPrintEventState() == PrintEventState.FINISH_FAIL)
                    .take(1)
                    .subscribe(event -> {
                        if (event.getPrintEventState() == PrintEventState.STATE_SUCCESS) {
                            controller.markAttachStartedRemotePrint();
                            registerDashboardStagingCleanup(requestId, controller);
                            finishDashboardFileStart(
                                    requestId,
                                    "started",
                                    "succeeded",
                                    "",
                                    0
                            );
                            routeDashboardToActivePrint(appService);
                        } else {
                            finishDashboardFileStart(
                                    requestId,
                                    "failed",
                                    event.getPrintEventState() == PrintEventState.START_FAIL
                                            ? "controller_rejected" : "controller_result_lost",
                                    event.getPrintEventState() == PrintEventState.START_FAIL
                                            ? "The printer controller rejected the print start."
                                            : "The firmware start result was not received. Verify the printer state, then restart the HMI before another start.",
                                    event.getErrorCode()
                            );
                        }
                    }, error -> {
                        LogHelper.log(error);
                        finishDashboardFileStart(
                                requestId,
                                "failed",
                                "controller_failed",
                                "Unable to observe the printer start result.",
                                -1
                        );
                    });
            synchronized (mFileStartLock) {
                if (mFileStartState == null
                        || mFileStartState.requestId != requestId
                        || !mFileStartState.pending) {
                    eventDisposable.dispose();
                    return;
                }
                mFileStartEventDisposable = eventDisposable;
                mFileStartState.state = "launching";
            }
            mDisposable.add(eventDisposable);

            boolean controllerStartIssued = controller.start(preparationLease);
            if (controllerStartIssued) {
                markDashboardControllerStartCommitted(requestId);
            } else {
                if (controller.isPrintStartOutcomeUncertain()) {
                    markDashboardControllerStartCommitted(requestId);
                    finishDashboardFileStart(
                            requestId,
                            "failed",
                            "controller_result_lost",
                            "The firmware start result was not received. Verify the printer state, then restart the HMI before another start.",
                            -1
                    );
                } else {
                    finishDashboardFileStart(
                            requestId,
                            "failed",
                            "controller_busy",
                            "The print controller could not acquire its start action gate. If an earlier start result was lost, verify printer state, then restart the HMI.",
                            -1
                    );
                }
            }
        } catch (Exception error) {
            LogHelper.log(error);
            finishDashboardFileStart(
                    requestId,
                    "failed",
                    "launch_failed",
                    "Unable to start the prepared print.",
                    -1
            );
        }
    }

    private void markDashboardControllerStartCommitted(long requestId) {
        // Match the established lock order used by the start endpoint: detail, then start.
        synchronized (mFileDetailLock) {
            synchronized (mFileStartLock) {
                if (mFileStartState == null || mFileStartState.requestId != requestId) return;
                mFileStartState.controllerStartIssued = true;
                if (mFileDetailState != null
                        && mFileDetailState.requestId == mFileStartState.previewRequestId) {
                    mFileDetailState.consumed = true;
                }
            }
        }
    }

    private void routeDashboardToActivePrint(IAppService appService) {
        try {
            Context context = appService.getNowViewContext();
            if (context != null) {
                ServiceContainer.getInstance()
                        .getService(IRouter.class)
                        .routeToPrintPage()
                        .start(context);
            } else {
                ServiceContainer.getInstance()
                        .getService(IRouter.class)
                        .routeToPrintPage()
                        .start(appService.getAppContext(), Intent.FLAG_ACTIVITY_NEW_TASK);
            }
        } catch (Exception error) {
            LogHelper.log(error);
        }
    }

    private void registerDashboardStagingCleanup(
            long requestId,
            NewPrintController controller
    ) {
        File stagedFile;
        synchronized (mFileStartLock) {
            if (mFileStartState == null || mFileStartState.requestId != requestId) return;
            stagedFile = mFileStartState.stagedFile;
        }
        if (stagedFile == null) return;
        Disposable cleanup = controller.getPrintEventObservable()
                .observeOn(AndroidSchedulers.mainThread())
                .filter(event -> event.getPrintEventState() == PrintEventState.STOP_SUCCESS
                        || event.getPrintEventState() == PrintEventState.FINISH_SUCCESS
                        || event.getPrintEventState() == PrintEventState.FINISH_FAIL)
                .take(1)
                .subscribe(event -> deleteDashboardStagingFile(stagedFile), LogHelper::log);
        Disposable previousCleanup = null;
        boolean keepCleanup = false;
        synchronized (mFileStartLock) {
            if (mFileStartState != null && mFileStartState.requestId == requestId) {
                previousCleanup = mFileStartCleanupDisposable;
                mFileStartCleanupDisposable = cleanup;
                keepCleanup = true;
            } else {
                cleanup.dispose();
            }
        }
        disposeAndForget(previousCleanup);
        if (keepCleanup) mDisposable.add(cleanup);
    }

    private void updateDashboardFileStartState(long requestId, String state) {
        synchronized (mFileStartLock) {
            if (mFileStartState != null
                    && mFileStartState.requestId == requestId
                    && mFileStartState.pending) {
                mFileStartState.state = state;
            }
        }
    }

    private void timeoutDashboardFileStart(long requestId) {
        boolean startIssued;
        synchronized (mFileStartLock) {
            if (mFileStartState == null
                    || mFileStartState.requestId != requestId
                    || !mFileStartState.pending) return;
            startIssued = mFileStartState.controllerStartIssued;
        }
        finishDashboardFileStart(
                requestId,
                "failed",
                startIssued ? "controller_result_lost" : "timed_out",
                startIssued
                        ? "The firmware start result was not received. Verify the printer state, then restart the HMI before another start."
                        : "Print preparation timed out; verify the printer state before retrying.",
                -1
        );
    }

    private void finishDashboardFileStart(
            long requestId,
            String state,
            String result,
            String error,
            int controllerErrorCode
    ) {
        Disposable md5Disposable;
        Disposable copyDisposable;
        Disposable eventDisposable;
        Disposable bedDisposable;
        Runnable timeout;
        InputStream stagingInput;
        File stagedFile;
        long preparationLease;
        boolean releaseRemoteGate;
        boolean retainAmbiguousStaging;
        synchronized (mFileStartLock) {
            if (mFileStartState == null
                    || mFileStartState.requestId != requestId
                    || !mFileStartState.pending) return;
            mFileStartState.pending = false;
            mFileStartState.state = state;
            mFileStartState.result = result;
            mFileStartState.error = emptyIfNull(error);
            mFileStartState.controllerErrorCode = controllerErrorCode;
            mFileStartState.completedAt = System.currentTimeMillis();
            md5Disposable = mFileStartMd5Disposable;
            copyDisposable = mFileStartCopyDisposable;
            eventDisposable = mFileStartEventDisposable;
            bedDisposable = mFileStartBedDisposable;
            timeout = mFileStartTimeout;
            stagingInput = mFileStartStagingInput;
            stagedFile = mFileStartState.stagedFile;
            preparationLease = mFileStartState.preparationLease;
            releaseRemoteGate = mFileStartRemoteGateClaimed;
            retainAmbiguousStaging = "controller_result_lost".equals(result)
                    || (mFileStartState.controllerStartIssued
                    && !"succeeded".equals(result)
                    && !"controller_rejected".equals(result));
            mFileStartMd5Disposable = null;
            mFileStartCopyDisposable = null;
            mFileStartEventDisposable = null;
            mFileStartBedDisposable = null;
            mFileStartTimeout = null;
            mFileStartStagingInput = null;
            mFileStartRemoteGateClaimed = false;
        }
        disposeAndForget(md5Disposable);
        disposeAndForget(copyDisposable);
        disposeAndForget(eventDisposable);
        disposeAndForget(bedDisposable);
        if (timeout != null) mMainHandler.removeCallbacks(timeout);
        // Rx disposal alone cannot reliably interrupt a blocking USB FileInputStream read.
        closeQuietly(stagingInput);
        NewPrintController controller = ServiceContainer.getInstance()
                .getService(IMachine.class)
                .getNewPrintController();
        retainAmbiguousStaging = retainAmbiguousStaging
                || controller.isPrintStartOutcomeUncertain();
        if (retainAmbiguousStaging) {
            controller.markPrintStartOutcomeUncertain();
        }
        controller.abortPrintPreparation(preparationLease);
        if (releaseRemoteGate) {
            controller.setStartFromRemoteFlag(false);
        }
        if (retainAmbiguousStaging) {
            // The firmware may have accepted START even though its response was lost.
            // GcodePlayer opens this immutable snapshot lazily on the first batch request,
            // so deleting it here could corrupt a real job. A terminal print event removes
            // it; otherwise the exact-idle stale staging pass handles it after 24 hours.
            registerDashboardStagingCleanup(requestId, controller);
        } else if (!"succeeded".equals(result)) {
            deleteDashboardStagingFile(stagedFile);
        }
    }

    private void disposeAndForget(Disposable disposable) {
        if (disposable == null) return;
        if (!disposable.isDisposed()) disposable.dispose();
        mDisposable.delete(disposable);
    }

    private DashboardConsoleResult startDashboardGcode(String rawCommand) {
        String command = normalizeDashboardCommand(rawCommand);
        DashboardConsoleResult validation = validateDashboardCommand(command);
        if (validation != null) return validation;

        IMachine machine = ServiceContainer.getInstance().getService(IMachine.class);
        IAppService appService = ServiceContainer.getInstance().getService(IAppService.class);
        if (machine.getNewPrintController().isPrintPreparationReserved()) {
            return DashboardConsoleResult.rejected(StatusCode.SC_CONFLICT,
                    "A print is being prepared; wait before sending G-code.");
        }
        int state = machine.getNewPrintController().getPrintState();
        if (!isCommandAllowedForState(command, state)) {
            return DashboardConsoleResult.rejected(StatusCode.SC_CONFLICT,
                    "Arbitrary G-code is allowed only while idle; this command is not read-only.");
        }

        synchronized (mConsoleLock) {
            if (machine.getNewPrintController().isPrintPreparationReserved()) {
                return DashboardConsoleResult.rejected(StatusCode.SC_CONFLICT,
                        "A print is being prepared; wait before sending G-code.");
            }
            if (mConsoleCommandPending) {
                return DashboardConsoleResult.rejected(StatusCode.SC_CONFLICT,
                        "Another console or mesh command is still running.");
            }
            mConsoleCommandPending = true;
        }
        ensureFirmwareLogCapture(machine);
        long queuedAt = System.currentTimeMillis();
        DashboardConsoleEntry txEntry = recordConsoleEntry(new DashboardConsoleEntry(
                0L,
                command,
                "",
                true,
                queuedAt,
                0L
        ));

        boolean posted = mMainHandler.post(() -> {
            MachineStatus latestStatus = machine.getMachineStatusSubjectHolder().getValue();
            MachineInfo latestInfo = machine.getMachineInfoSubjectHolder().getValue();
            int latestState = machine.getNewPrintController().getPrintState();
            if (latestStatus == null
                    || !latestStatus.connected
                    || latestInfo == null
                    || latestInfo.workType != IMachine.WorkType.FDM
                    || appService.getEmergencyStopState()
                    != ErrorController.EmergencyStopState.EMERGENCY_STOP_STATE_NORMAL
                    || machine.getNewPrintController().isPrintPreparationReserved()
                    || !isCommandAllowedForState(command, latestState)) {
                finishDashboardGcodeCommand(
                        "Command was not sent because printer safety or operating state changed.",
                        false,
                        queuedAt
                );
                return;
            }

            try {
                Disposable disposable = machine.getMachineController()
                        .sendGcode(command)
                        .timeout(CONSOLE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .observeOn(AndroidSchedulers.mainThread())
                        .take(1)
                        .subscribe(result -> {
                            int resultCode = result == null ? -1 : result.resultProp.getValue();
                            finishDashboardGcodeCommand(
                                    result != null && result.isSuccess()
                                            ? "Controller accepted the command for execution."
                                            : "Controller rejected the command (code " + resultCode + ").",
                                    result != null && result.isSuccess(),
                                    queuedAt
                            );
                        }, error -> {
                            LogHelper.log(error);
                            finishDashboardGcodeCommand(
                                    "The controller did not acknowledge the command.",
                                    false,
                                    queuedAt
                            );
                        });
                mDisposable.add(disposable);
            } catch (Exception e) {
                LogHelper.log(e);
                finishDashboardGcodeCommand(
                        "Unable to queue the command on the controller connection.",
                        false,
                        queuedAt
                );
            }
        });
        if (!posted) {
            synchronized (mConsoleLock) {
                mConsoleCommandPending = false;
            }
            recordConsoleEntry(new DashboardConsoleEntry(
                    0L,
                    "",
                    "Unable to queue the command on the Android main thread.",
                    false,
                    System.currentTimeMillis(),
                    0L
            ));
            return DashboardConsoleResult.rejected(StatusCode.SC_INTERNAL_SERVER_ERROR,
                    "Unable to queue the G-code command.");
        }
        return DashboardConsoleResult.accepted(txEntry);
    }

    private DashboardConsoleResult startDashboardBedMeshRefresh() {
        String command = "M420 V";
        DashboardConsoleResult validation = validateDashboardCommand(command);
        if (validation != null) return validation;

        IMachine machine = ServiceContainer.getInstance().getService(IMachine.class);
        if (machine.getNewPrintController().isPrintPreparationReserved()) {
            return DashboardConsoleResult.rejected(StatusCode.SC_CONFLICT,
                    "A print is being prepared; wait before refreshing the bed mesh.");
        }
        int state = machine.getNewPrintController().getPrintState();
        if (!isCommandAllowedForState(command, state)) {
            return DashboardConsoleResult.rejected(StatusCode.SC_CONFLICT,
                    "The printer is not in a state where the mesh can be read safely.");
        }

        synchronized (mConsoleLock) {
            if (machine.getNewPrintController().isPrintPreparationReserved()) {
                return DashboardConsoleResult.rejected(StatusCode.SC_CONFLICT,
                        "A print is being prepared; wait before refreshing the bed mesh.");
            }
            if (mConsoleCommandPending || mMeshRefreshPending) {
                return DashboardConsoleResult.rejected(StatusCode.SC_CONFLICT,
                        "Another console or mesh command is still running.");
            }
            mConsoleCommandPending = true;
            mMeshRefreshPending = true;
        }
        ensureFirmwareLogCapture(machine);
        long queuedAt = System.currentTimeMillis();
        DashboardConsoleEntry txEntry = recordConsoleEntry(new DashboardConsoleEntry(
                0L,
                command,
                "",
                true,
                queuedAt,
                0L
        ));
        final long requestId = txEntry.id;
        final String beginMarker = "ARTISAN_MESH_BEGIN_" + requestId;
        final String endMarker = "ARTISAN_MESH_END_" + requestId;
        final StringBuilder captured = new StringBuilder();
        final Object captureLock = new Object();
        final AtomicBoolean captureStarted = new AtomicBoolean(false);
        MachineController machineController = machine.getMachineController();
        synchronized (mConsoleLock) {
            mMeshRequestId = requestId;
        }

        Disposable captureDisposable;
        try {
            captureDisposable = machineController.getFirmwareLogObservable()
                    .subscribe(log -> {
                    String normalized = normalizeFirmwareLog(log.getMessage());
                    String[] lines = normalized.split("\n", -1);
                    synchronized (captureLock) {
                        for (String line : lines) {
                            String trimmed = line.trim();
                            if (isFirmwareMarker(trimmed, beginMarker)) {
                                captureStarted.set(true);
                                captured.setLength(0);
                                continue;
                            }
                            if (!captureStarted.get()
                                    && trimmed.toLowerCase(Locale.US)
                                    .contains("compensated bilinear leveling grid:")) {
                                // The begin log can be dropped during a reconnect. The unique
                                // compensated heading is still a safe point to start recovery.
                                captureStarted.set(true);
                            }
                            if (!captureStarted.get()) continue;
                            if (isFirmwareMarker(trimmed, endMarker)) {
                                finishDashboardBedMeshRefresh(
                                        requestId,
                                        DashboardBedMeshParser.parse(captured.toString()),
                                        queuedAt
                                );
                                return;
                            }
                            captured.append(line).append('\n');
                            DashboardBedMeshParser.Result candidate =
                                    DashboardBedMeshParser.parse(captured.toString());
                            // M420 V prints RAW before compensated. Do not stop on a
                            // complete raw grid; wait for the grid the UI promises.
                            if ((candidate.available
                                    && "compensated".equals(candidate.source))
                                    || trimmed.toLowerCase(Locale.US).contains("invalid mesh")) {
                                finishDashboardBedMeshRefresh(requestId, candidate, queuedAt);
                                return;
                            }
                        }
                    }
                    }, error -> {
                        LogHelper.log(error);
                        String capturedResponse;
                        synchronized (captureLock) {
                            capturedResponse = captured.toString();
                        }
                        finishDashboardBedMeshRefresh(
                                requestId,
                                DashboardBedMeshParser.Result.unavailable(
                                        "The firmware log stream stopped during mesh capture.",
                                        capturedResponse
                                ),
                                queuedAt
                        );
                    });
        } catch (Exception error) {
            LogHelper.log(error);
            finishDashboardBedMeshRefresh(
                    requestId,
                    DashboardBedMeshParser.Result.unavailable(
                            "Unable to subscribe to the firmware log stream.",
                            ""
                    ),
                    queuedAt
            );
            return DashboardConsoleResult.rejected(StatusCode.SC_INTERNAL_SERVER_ERROR,
                    "Unable to start mesh capture.");
        }
        boolean keepCaptureDisposable;
        synchronized (mConsoleLock) {
            keepCaptureDisposable = mMeshRefreshPending && mMeshRequestId == requestId;
            if (keepCaptureDisposable) mMeshCaptureDisposable = captureDisposable;
        }
        if (!keepCaptureDisposable && !captureDisposable.isDisposed()) captureDisposable.dispose();

        Runnable timeout = () -> {
            String capturedResponse;
            synchronized (captureLock) {
                capturedResponse = captured.toString();
            }
            finishDashboardBedMeshRefresh(
                    requestId,
                    DashboardBedMeshParser.Result.unavailable(
                            "Timed out while waiting for a complete compensated mesh.",
                            capturedResponse
                    ),
                    queuedAt
            );
        };
        synchronized (mConsoleLock) {
            mMeshTimeoutRunnable = timeout;
        }
        if (!mMainHandler.postDelayed(timeout, MESH_TIMEOUT_MS)) {
            finishDashboardBedMeshRefresh(
                    requestId,
                    DashboardBedMeshParser.Result.unavailable(
                            "Unable to schedule the mesh capture timeout.",
                            ""
                    ),
                    queuedAt
            );
            return DashboardConsoleResult.rejected(StatusCode.SC_INTERNAL_SERVER_ERROR,
                    "Unable to start mesh capture.");
        }

        boolean posted = mMainHandler.post(() -> sendMeshCommandSequence(
                machine,
                requestId,
                queuedAt,
                new String[]{
                        "M118 E1 " + beginMarker,
                        command,
                        "M118 E1 " + endMarker
                },
                0
        ));
        if (!posted) {
            finishDashboardBedMeshRefresh(
                    requestId,
                    DashboardBedMeshParser.Result.unavailable(
                            "Unable to queue the mesh request.",
                            ""
                    ),
                    queuedAt
            );
            return DashboardConsoleResult.rejected(StatusCode.SC_INTERNAL_SERVER_ERROR,
                    "Unable to queue the mesh request.");
        }
        return DashboardConsoleResult.accepted(txEntry);
    }

    private void sendMeshCommandSequence(
            IMachine machine,
            long requestId,
            long queuedAt,
            String[] commands,
            int index
    ) {
        synchronized (mConsoleLock) {
            if (!mMeshRefreshPending || mMeshRequestId != requestId) return;
        }
        MachineStatus status = machine.getMachineStatusSubjectHolder().getValue();
        MachineInfo info = machine.getMachineInfoSubjectHolder().getValue();
        IAppService appService = ServiceContainer.getInstance().getService(IAppService.class);
        if (status == null
                || !status.connected
                || info == null
                || info.workType != IMachine.WorkType.FDM
                || appService.getEmergencyStopState()
                != ErrorController.EmergencyStopState.EMERGENCY_STOP_STATE_NORMAL
                || machine.getNewPrintController().isPrintPreparationReserved()
                || !isCommandAllowedForState("M420 V", machine.getNewPrintController().getPrintState())) {
            finishDashboardBedMeshRefresh(
                    requestId,
                    DashboardBedMeshParser.Result.unavailable(
                            "Printer safety or operating state changed before mesh capture.",
                            ""
                    ),
                    queuedAt
            );
            return;
        }
        if (index >= commands.length) return;

        try {
            // sendGcode starts eagerly, so each request is created only after the
            // previous ACK callback reaches this method.
            Disposable disposable = machine.getMachineController()
                    .sendGcode(commands[index])
                    .timeout(CONSOLE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .observeOn(AndroidSchedulers.mainThread())
                    .take(1)
                    .subscribe(result -> {
                        if (result == null || !result.isSuccess()) {
                            int code = result == null ? -1 : result.resultProp.getValue();
                            finishDashboardBedMeshRefresh(
                                    requestId,
                                    DashboardBedMeshParser.Result.unavailable(
                                            "Controller rejected the mesh request (code " + code + ").",
                                            ""
                                    ),
                                    queuedAt
                            );
                        } else {
                            sendMeshCommandSequence(
                                    machine,
                                    requestId,
                                    queuedAt,
                                    commands,
                                    index + 1
                            );
                        }
                    }, error -> {
                        LogHelper.log(error);
                        finishDashboardBedMeshRefresh(
                                requestId,
                                DashboardBedMeshParser.Result.unavailable(
                                        "Controller did not acknowledge the mesh request.",
                                        ""
                                ),
                                queuedAt
                        );
                    });
            mDisposable.add(disposable);
        } catch (Exception e) {
            LogHelper.log(e);
            finishDashboardBedMeshRefresh(
                    requestId,
                    DashboardBedMeshParser.Result.unavailable(
                            "Unable to send the mesh request.",
                            ""
                    ),
                    queuedAt
            );
        }
    }

    private void finishDashboardBedMeshRefresh(
            long requestId,
            DashboardBedMeshParser.Result result,
            long queuedAt
    ) {
        Disposable captureDisposable;
        Runnable timeout;
        synchronized (mConsoleLock) {
            if (!mMeshRefreshPending || mMeshRequestId != requestId) return;
            mMeshRefreshPending = false;
            mMeshRequestId = 0L;
            mConsoleCommandPending = false;
            captureDisposable = mMeshCaptureDisposable;
            mMeshCaptureDisposable = null;
            timeout = mMeshTimeoutRunnable;
            mMeshTimeoutRunnable = null;
        }
        if (captureDisposable != null && !captureDisposable.isDisposed()) {
            captureDisposable.dispose();
        }
        if (timeout != null) mMainHandler.removeCallbacks(timeout);

        long now = System.currentTimeMillis();
        mLastBedMeshAttemptAt = now;
        if (result.available) {
            mLastBedMesh = result;
            mLastBedMeshAt = now;
            mLastBedMeshStale = false;
            mLastBedMeshError = "";
        } else {
            if (!mLastBedMesh.available) mLastBedMesh = result;
            mLastBedMeshStale = mLastBedMesh.available;
            mLastBedMeshError = result.message;
        }
        recordConsoleEntry(new DashboardConsoleEntry(
                0L,
                "",
                result.available
                        ? "Bed mesh captured: " + result.values.get(0).size() + " × "
                        + result.values.size() + " " + result.source + " grid; "
                        + result.surfaceValues.get(0).size() + " × "
                        + result.surfaceValues.size() + " Catmull-Rom surface."
                        : "Bed mesh refresh failed: " + result.message,
                result.available,
                now,
                Math.max(0L, now - queuedAt)
        ));
    }

    private void finishDashboardGcodeCommand(String message, boolean success, long queuedAt) {
        long now = System.currentTimeMillis();
        recordConsoleEntry(new DashboardConsoleEntry(
                0L,
                "",
                message,
                success,
                now,
                Math.max(0L, now - queuedAt)
        ));
        if (!mMainHandler.postDelayed(() -> {
            synchronized (mConsoleLock) {
                mConsoleCommandPending = false;
            }
        }, CONSOLE_BACKPRESSURE_MS)) {
            synchronized (mConsoleLock) {
                mConsoleCommandPending = false;
            }
        }
    }

    private DashboardConsoleResult validateDashboardCommand(String command) {
        if (TextUtils.isEmpty(command)) {
            return DashboardConsoleResult.rejected(StatusCode.SC_BAD_REQUEST,
                    "Enter a G-code command.");
        }
        if (command.getBytes(StandardCharsets.UTF_8).length > MAX_CONSOLE_COMMAND_BYTES) {
            return DashboardConsoleResult.rejected(StatusCode.SC_BAD_REQUEST,
                    "G-code commands are limited to 79 UTF-8 bytes.");
        }
        for (int index = 0; index < command.length(); index++) {
            if (Character.isISOControl(command.charAt(index))) {
                return DashboardConsoleResult.rejected(StatusCode.SC_BAD_REQUEST,
                        "Send one printable G-code command at a time.");
            }
        }

        IMachine machine = ServiceContainer.getInstance().getService(IMachine.class);
        IAppService appService = ServiceContainer.getInstance().getService(IAppService.class);
        MachineStatus status = machine.getMachineStatusSubjectHolder().getValue();
        MachineInfo info = machine.getMachineInfoSubjectHolder().getValue();
        if (status == null || !status.connected) {
            return DashboardConsoleResult.rejected(StatusCode.SC_CONFLICT,
                    "Printer is disconnected.");
        }
        if (info == null || info.workType != IMachine.WorkType.FDM) {
            return DashboardConsoleResult.rejected(StatusCode.SC_CONFLICT,
                    "3D printing mode is not active.");
        }
        if (appService.getEmergencyStopState()
                != ErrorController.EmergencyStopState.EMERGENCY_STOP_STATE_NORMAL) {
            return DashboardConsoleResult.rejected(StatusCode.SC_CONFLICT,
                    "Emergency stop is active.");
        }
        return null;
    }

    private String normalizeDashboardCommand(String rawCommand) {
        return rawCommand == null ? "" : rawCommand.trim();
    }

    private boolean isCommandAllowedForState(String command, int state) {
        if (state == MachineOperationStatus.SYSTEM_STATUS_IDLE.value()) return true;
        boolean stableJobState = state == MachineOperationStatus.SYSTEM_STATUS_PRINTING.value()
                || state == MachineOperationStatus.SYSTEM_STATUS_PAUSED.value();
        return stableJobState && isReadOnlyGcode(command);
    }

    private boolean isReadOnlyGcode(String command) {
        String canonical = command == null
                ? ""
                : command.split(";", 2)[0].trim().toUpperCase(Locale.US)
                .replaceAll("\\s+", " ");
        return canonical.equals("M105")
                || canonical.equals("M114")
                || canonical.equals("M115")
                || canonical.equals("M119")
                || canonical.equals("M420 V")
                || canonical.equals("M420 V1");
    }

    private boolean isMeshCommand(String command) {
        String canonical = command == null
                ? ""
                : command.split(";", 2)[0].trim().toUpperCase(Locale.US)
                .replaceAll("\\s+", " ");
        return canonical.equals("M420 V") || canonical.equals("M420 V1");
    }

    private void ensureFirmwareLogCapture(IMachine machine) {
        synchronized (mConsoleLock) {
            if (mFirmwareLogDisposable != null && !mFirmwareLogDisposable.isDisposed()) return;
            mFirmwareLogDisposable = machine.getMachineController()
                    .getFirmwareLogObservable()
                    .subscribe(this::recordFirmwareLog, LogHelper::log);
            mDisposable.add(mFirmwareLogDisposable);
        }
    }

    private void recordFirmwareLog(MachineController.FirmwareLog firmwareLog) {
        String message = normalizeFirmwareLog(firmwareLog.getMessage());
        if (TextUtils.isEmpty(message)) return;
        StringBuilder visible = new StringBuilder();
        for (String line : message.split("\n", -1)) {
            if (line.contains("ARTISAN_MESH_BEGIN_")
                    || line.contains("ARTISAN_MESH_END_")) continue;
            if (visible.length() > 0) visible.append('\n');
            visible.append(line);
        }
        String output = visible.toString().trim();
        if (output.isEmpty()) return;
        if (output.length() > MAX_CONSOLE_RESPONSE_LENGTH) {
            output = output.substring(0, MAX_CONSOLE_RESPONSE_LENGTH)
                    + "\n[firmware output truncated by dashboard]";
        }
        recordConsoleEntry(new DashboardConsoleEntry(
                0L,
                "",
                output,
                !output.toLowerCase(Locale.US).contains("error:"),
                firmwareLog.getTimestamp(),
                0L
        ));
    }

    private String normalizeFirmwareLog(String log) {
        return log == null
                ? ""
                : log.replace("\u0000", "")
                .replace("\r\n", "\n")
                .replace('\r', '\n');
    }

    private boolean isFirmwareMarker(String line, String marker) {
        return line.equals(marker)
                || line.equals("echo:" + marker)
                || line.endsWith(" " + marker);
    }

    private DashboardConsoleEntry recordConsoleEntry(DashboardConsoleEntry entry) {
        synchronized (mConsoleLock) {
            DashboardConsoleEntry stored = entry.id > 0L
                    ? entry
                    : entry.withId(++mNextConsoleEntryId);
            mConsoleHistory.add(stored);
            while (mConsoleHistory.size() > MAX_CONSOLE_HISTORY) {
                mConsoleHistory.remove(0);
            }
            return stored;
        }
    }

    private JSONObject bedMeshToJson(
            DashboardBedMeshParser.Result mesh,
            long capturedAt
    ) throws JSONException {
        boolean capturing;
        long requestId;
        synchronized (mConsoleLock) {
            capturing = mMeshRefreshPending;
            requestId = mMeshRequestId;
        }
        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("available", mesh.available);
        result.put("capturing", capturing);
        result.put("requestId", requestId);
        result.put("stale", mLastBedMeshStale);
        result.put("error", mLastBedMeshError);
        result.put("message", mesh.available ? "" : mesh.message);
        result.put("source", mesh.source);
        result.put("surfaceSource", mesh.surfaceSource);
        result.put("surfaceDerivedFrom", mesh.surfaceDerivedFrom);
        result.put("surfaceInterpolation", mesh.surfaceInterpolation);
        result.put("surfaceSubdivisions", mesh.surfaceSubdivisions);
        result.put("capturedAt", capturedAt);
        result.put("lastAttemptAt", mLastBedMeshAttemptAt);
        result.put("rows", mesh.values.size());
        result.put("columns", mesh.values.isEmpty() ? 0 : mesh.values.get(0).size());
        result.put("surfaceRows", mesh.surfaceValues.size());
        result.put("surfaceColumns", mesh.surfaceValues.isEmpty()
                ? 0
                : mesh.surfaceValues.get(0).size());
        result.put("minimumMm", mesh.available ? rounded((float) mesh.minimum, 4) : JSONObject.NULL);
        result.put("maximumMm", mesh.available ? rounded((float) mesh.maximum, 4) : JSONObject.NULL);
        result.put("rangeMm", mesh.available
                ? rounded((float) (mesh.maximum - mesh.minimum), 4)
                : JSONObject.NULL);
        result.put("levelingActive", mesh.levelingActive == null
                ? JSONObject.NULL
                : mesh.levelingActive);
        JSONArray rows = new JSONArray();
        for (List<Double> meshRow : mesh.values) {
            JSONArray row = new JSONArray();
            for (Double value : meshRow) {
                row.put(value == null || value.isNaN() || value.isInfinite()
                        ? JSONObject.NULL
                        : rounded(value.floatValue(), 4));
            }
            rows.put(row);
        }
        result.put("values", rows);
        JSONArray surfaceRows = new JSONArray();
        for (List<Double> meshRow : mesh.surfaceValues) {
            JSONArray row = new JSONArray();
            for (Double value : meshRow) {
                row.put(value == null || value.isNaN() || value.isInfinite()
                        ? JSONObject.NULL
                        : rounded(value.floatValue(), 5));
            }
            surfaceRows.put(row);
        }
        result.put("surfaceValues", surfaceRows);
        return result;
    }

    private JSONObject dashboardEnclosureToJson(IMachine machine) throws JSONException {
        MachineStatus machineStatus = machine.getMachineStatusSubjectHolder().getValue();
        MachineInfo info = machine.getMachineInfoSubjectHolder().getValue();
        IAppService appService = ServiceContainer.getInstance().getService(IAppService.class);
        Enclosure enclosure = findModule(info, Enclosure.class);
        boolean connected = machineStatus != null && machineStatus.connected;
        boolean fdmMode = info != null && info.workType == IMachine.WorkType.FDM;
        boolean present = info != null
                && info.isEnclosureAvailable
                && enclosure != null;
        boolean emergencyStop = appService.getEmergencyStopState()
                != ErrorController.EmergencyStopState.EMERGENCY_STOP_STATE_NORMAL;
        Enclosure.EnclosureStatus enclosureStatus = enclosure == null
                ? null
                : enclosure.getEnclosureStatusValue();
        long now = System.currentTimeMillis();
        long telemetryUpdatedAt = enclosure == null
                ? 0L
                : enclosure.getEnclosureStatusUpdatedAt();
        long telemetryAgeMs = telemetryUpdatedAt <= 0L
                ? Long.MAX_VALUE
                : Math.max(0L, now - telemetryUpdatedAt);
        boolean hasLiveTelemetry = enclosure != null
                && enclosure.hasLiveEnclosureStatus()
                && enclosureStatus != null
                && telemetryUpdatedAt > 0L;
        boolean telemetryAvailable = connected
                && present
                && isEnclosureTelemetryFresh(enclosure, now);

        boolean controlPending;
        long controlId;
        String controlTarget;
        int requestedPercent;
        String controlResult;
        String controlError;
        int controlErrorCode;
        long controlCompletedAt;
        synchronized (mEnclosureControlLock) {
            controlPending = mEnclosureControlPending;
            controlId = mEnclosureControlId;
            controlTarget = mEnclosureControlTarget;
            requestedPercent = mEnclosureControlRequestedPercent;
            controlResult = mEnclosureControlResult;
            controlError = mEnclosureControlError;
            controlErrorCode = mEnclosureControlErrorCode;
            controlCompletedAt = mEnclosureControlCompletedAt;
        }

        boolean available = connected && fdmMode && present;
        boolean controlAvailable = available && !emergencyStop && !controlPending;
        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("timestamp", now);
        result.put("connected", connected);
        result.put("fdmMode", fdmMode);
        result.put("present", present);
        result.put("available", available);
        result.put("controlAvailable", controlAvailable);
        result.put("blockedReason", enclosureBlockedReason(
                connected,
                fdmMode,
                present,
                emergencyStop,
                controlPending
        ));
        result.put("emergencyStop", emergencyStop);
        result.put("telemetryAvailable", telemetryAvailable);
        result.put("telemetryStale", present && hasLiveTelemetry && !telemetryAvailable);
        result.put("telemetryUpdatedAt", hasLiveTelemetry ? telemetryUpdatedAt : 0L);
        result.put("telemetryAgeMs", hasLiveTelemetry ? telemetryAgeMs : JSONObject.NULL);
        result.put("moduleStatus", telemetryAvailable
                ? enclosureStatus.getStatus()
                : JSONObject.NULL);
        result.put("doorOpen", telemetryAvailable
                ? enclosureStatus.isDoorOpen()
                : JSONObject.NULL);
        result.put("doorState", telemetryAvailable
                ? enclosureStatus.isDoorOpen() ? "open" : "closed"
                : "unknown");
        result.put("led", enclosureOutputToJson(
                telemetryAvailable ? enclosureStatus.getLedValue() : null
        ));
        result.put("fan", enclosureOutputToJson(
                telemetryAvailable ? enclosureStatus.getFanSpeed() : null
        ));

        JSONObject control = new JSONObject();
        control.put("pending", controlPending);
        control.put("requestId", controlId);
        control.put("target", controlTarget);
        control.put("requestedPercent", controlId > 0L
                ? requestedPercent
                : JSONObject.NULL);
        control.put("result", controlResult);
        control.put("error", controlError);
        control.put("controllerErrorCode", controlErrorCode);
        control.put("completedAt", controlCompletedAt);
        result.put("control", control);
        return result;
    }

    private boolean isEnclosureTelemetryFresh(Enclosure enclosure, long now) {
        if (enclosure == null
                || !enclosure.hasLiveEnclosureStatus()
                || enclosure.getEnclosureStatusValue() == null) {
            return false;
        }
        return enclosure.isEnclosureStatusFresh(ENCLOSURE_TELEMETRY_MAX_AGE_MS);
    }

    private JSONObject enclosureOutputToJson(Integer level) throws JSONException {
        JSONObject output = new JSONObject();
        output.put("enabled", level == null ? JSONObject.NULL : level > 0);
        output.put("percent", level == null
                ? JSONObject.NULL
                : Math.max(0, Math.min(100, level)));
        output.put("minimumPercent", 0);
        output.put("maximumPercent", 100);
        return output;
    }

    private String enclosureBlockedReason(
            boolean connected,
            boolean fdmMode,
            boolean present,
            boolean emergencyStop,
            boolean pending
    ) {
        if (!connected) return "Printer is disconnected.";
        if (!fdmMode) return "3D printing mode is not active.";
        if (!present) return "Artisan enclosure is not available.";
        if (emergencyStop) return "Emergency stop is active.";
        if (pending) return "Another enclosure command is in progress.";
        return "";
    }

    private void handleDashboardEnclosureControl(
            String target,
            HttpRequest request,
            HttpResponse response
    ) {
        setDashboardApiHeaders(response);
        if (!isDashboardRequestAuthorized(request)) {
            writeJsonError(response, StatusCode.SC_FORBIDDEN, "Dashboard authorization failed.");
            return;
        }

        DashboardEnclosureControlInput.Result input = DashboardEnclosureControlInput.parse(
                request.getParameter("enabled"),
                request.getParameter("percent")
        );
        if (!input.valid) {
            writeJsonError(response, StatusCode.SC_BAD_REQUEST, input.error);
            return;
        }
        final int effectivePercent = input.effectivePercent;

        try {
            IMachine machine = ServiceContainer.getInstance().getService(IMachine.class);
            IAppService appService = ServiceContainer.getInstance().getService(IAppService.class);
            String safetyError = enclosureControlSafetyError(machine, appService);
            if (!TextUtils.isEmpty(safetyError)) {
                writeJsonError(response, StatusCode.SC_CONFLICT, safetyError);
                return;
            }

            final long requestId;
            synchronized (mEnclosureControlLock) {
                if (mEnclosureControlPending) {
                    writeJsonError(response, StatusCode.SC_CONFLICT,
                            "Another enclosure command is in progress.");
                    return;
                }
                mEnclosureControlPending = true;
                DashboardTelemetryHistory.getInstance().setEnclosureControlPending(true);
                requestId = ++mNextEnclosureControlId;
                mEnclosureControlId = requestId;
                mEnclosureControlTarget = target;
                mEnclosureControlRequestedPercent = effectivePercent;
                mEnclosureControlResult = "pending";
                mEnclosureControlError = "";
                mEnclosureControlErrorCode = 0;
                mEnclosureControlCompletedAt = 0L;
            }

            boolean posted;
            try {
                posted = mMainHandler.post(() -> executeDashboardEnclosureControl(
                        machine,
                        appService,
                        requestId,
                        target,
                        effectivePercent
                ));
            } catch (RuntimeException queueError) {
                finishDashboardEnclosureControl(
                        requestId,
                        "queue_failed",
                        "Unable to queue the enclosure command on the Android main thread.",
                        -1
                );
                throw queueError;
            }
            if (!posted) {
                finishDashboardEnclosureControl(
                        requestId,
                        "queue_failed",
                        "Unable to queue the enclosure command on the Android main thread.",
                        -1
                );
                writeJsonError(response, StatusCode.SC_INTERNAL_SERVER_ERROR,
                        "Unable to queue the enclosure command.");
                return;
            }

            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("accepted", true);
            result.put("requestId", requestId);
            result.put("target", target);
            result.put("enabled", effectivePercent > 0);
            result.put("percent", effectivePercent);
            result.put("state", "queued");
            writeJson(response, StatusCode.SC_ACCEPTED, result);
        } catch (Exception error) {
            LogHelper.log(error);
            writeJsonError(response, StatusCode.SC_INTERNAL_SERVER_ERROR,
                    "Unable to control the enclosure.");
        }
    }

    private String enclosureControlSafetyError(IMachine machine, IAppService appService) {
        MachineStatus machineStatus = machine.getMachineStatusSubjectHolder().getValue();
        if (machineStatus == null || !machineStatus.connected) {
            return "Printer is disconnected.";
        }
        MachineInfo info = machine.getMachineInfoSubjectHolder().getValue();
        if (info == null || info.workType != IMachine.WorkType.FDM) {
            return "3D printing mode is not active.";
        }
        if (!info.isEnclosureAvailable || findModule(info, Enclosure.class) == null) {
            return "Artisan enclosure is not available.";
        }
        if (appService.getEmergencyStopState()
                != ErrorController.EmergencyStopState.EMERGENCY_STOP_STATE_NORMAL) {
            return "Emergency stop is active.";
        }
        return "";
    }

    private void executeDashboardEnclosureControl(
            IMachine machine,
            IAppService appService,
            long requestId,
            String target,
            int percent
    ) {
        synchronized (mEnclosureControlLock) {
            if (!mEnclosureControlPending || mEnclosureControlId != requestId) return;
        }

        String safetyError = enclosureControlSafetyError(machine, appService);
        if (!TextUtils.isEmpty(safetyError)) {
            finishDashboardEnclosureControl(
                    requestId,
                    "safety_changed",
                    "Command was not sent because " + safetyError.toLowerCase(Locale.US),
                    -1
            );
            return;
        }

        MachineInfo latestInfo = machine.getMachineInfoSubjectHolder().getValue();
        Enclosure enclosure = findModule(latestInfo, Enclosure.class);
        if (enclosure == null) {
            finishDashboardEnclosureControl(
                    requestId,
                    "safety_changed",
                    "Command was not sent because the enclosure became unavailable.",
                    -1
            );
            return;
        }

        try {
            Observable<ResponseStructure> operation = "led".equals(target)
                    ? enclosure.setEnclosureLedLevelByUser(percent)
                    : enclosure.setEnclosureFanLevel(percent);
            Disposable disposable = operation
                    .timeout(ENCLOSURE_CONTROL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .take(1)
                    .switchIfEmpty(Observable.error(
                            new IllegalStateException("Enclosure command completed without a response.")
                    ))
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(result -> {
                        int resultCode = result == null ? -1 : result.resultProp.getValue();
                        if (result == null || !result.isSuccess()) {
                            finishDashboardEnclosureControl(
                                    requestId,
                                    "rejected",
                                    "The enclosure controller rejected the command.",
                                    resultCode
                            );
                            return;
                        }
                        refreshDashboardEnclosureAfterControl(enclosure, requestId);
                    }, error -> {
                        LogHelper.log(error);
                        finishDashboardEnclosureControl(
                                requestId,
                                error instanceof java.util.concurrent.TimeoutException
                                        ? "timed_out"
                                        : "failed",
                                "The enclosure controller did not acknowledge the command.",
                                -1
                        );
                    });
            mDisposable.add(disposable);
        } catch (Exception error) {
            LogHelper.log(error);
            finishDashboardEnclosureControl(
                    requestId,
                    "failed",
                    "Unable to queue the command on the enclosure controller.",
                    -1
            );
        }
    }

    private void refreshDashboardEnclosureAfterControl(Enclosure enclosure, long requestId) {
        try {
            Disposable disposable = enclosure.requestInfo()
                    .timeout(ENCLOSURE_REFRESH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .take(1)
                    .switchIfEmpty(Observable.error(
                            new IllegalStateException("Enclosure status refresh returned no response.")
                    ))
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(response -> {
                        boolean refreshed = response != null
                                && response.isSuccess()
                                && response.dataProp != null
                                && response.dataProp.getKey() == enclosure.getModuleInfo().getKey();
                        finishDashboardEnclosureControl(
                                requestId,
                                refreshed ? "succeeded" : "succeeded_status_stale",
                                refreshed
                                        ? ""
                                        : "Control was applied, but live enclosure status could not be refreshed.",
                                refreshed || response == null
                                        ? 0
                                        : response.resultProp.getValue()
                        );
                    }, error -> {
                        LogHelper.log(error);
                        finishDashboardEnclosureControl(
                                requestId,
                                "succeeded_status_stale",
                                "Control was applied, but live enclosure status could not be refreshed.",
                                0
                        );
                    });
            mDisposable.add(disposable);
        } catch (Exception error) {
            LogHelper.log(error);
            finishDashboardEnclosureControl(
                    requestId,
                    "succeeded_status_stale",
                    "Control was applied, but live enclosure status could not be refreshed.",
                    0
            );
        }
    }

    private void finishDashboardEnclosureControl(
            long requestId,
            String result,
            String error,
            int errorCode
    ) {
        synchronized (mEnclosureControlLock) {
            if (!mEnclosureControlPending || mEnclosureControlId != requestId) return;
            mEnclosureControlPending = false;
            DashboardTelemetryHistory.getInstance().setEnclosureControlPending(false);
            mEnclosureControlResult = result;
            mEnclosureControlError = error;
            mEnclosureControlErrorCode = errorCode;
            mEnclosureControlCompletedAt = System.currentTimeMillis();
        }
    }

    private long parseLong(String value, long fallback) {
        if (TextUtils.isEmpty(value)) return fallback;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private void setDashboardApiHeaders(HttpResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
    }

    private UvcCameraManager cameraManager() {
        Context context = ServiceContainer.getInstance()
                .getService(IAppService.class)
                .getAppContext();
        return UvcCameraManager.getInstance(context);
    }

    private IObicoService obicoService() {
        return ServiceContainer.getInstance().getService(IObicoService.class);
    }

    /**
     * Treats the manager response as untrusted at this HTTP boundary. Even if a
     * future manager DTO accidentally includes a credential, it must not reach
     * a browser or server log through the dashboard API.
     */
    private void writeObicoResult(
            HttpResponse response,
            JSONObject result,
            int failureStatus
    ) throws JSONException {
        if (result == null) {
            writeJsonError(response, failureStatus, "Obico did not return a response.");
            return;
        }
        JSONObject publicResult = sanitizeObicoObject(result);
        writeJson(
                response,
                publicResult.optBoolean("ok", true) ? StatusCode.SC_OK : failureStatus,
                publicResult
        );
    }

    private JSONObject sanitizeObicoObject(JSONObject source) throws JSONException {
        JSONObject sanitized = new JSONObject();
        Iterator<String> keys = source.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (isSensitiveObicoKey(key)) continue;
            Object value = source.opt(key);
            if (value instanceof JSONObject) {
                value = sanitizeObicoObject((JSONObject) value);
            } else if (value instanceof JSONArray) {
                value = sanitizeObicoArray((JSONArray) value);
            }
            sanitized.put(key, value == null ? JSONObject.NULL : value);
        }
        return sanitized;
    }

    private JSONArray sanitizeObicoArray(JSONArray source) throws JSONException {
        JSONArray sanitized = new JSONArray();
        for (int index = 0; index < source.length(); index++) {
            Object value = source.opt(index);
            if (value instanceof JSONObject) {
                value = sanitizeObicoObject((JSONObject) value);
            } else if (value instanceof JSONArray) {
                value = sanitizeObicoArray((JSONArray) value);
            }
            sanitized.put(value == null ? JSONObject.NULL : value);
        }
        return sanitized;
    }

    private boolean isSensitiveObicoKey(String key) {
        if (key == null) return false;
        String normalized = key.toLowerCase(Locale.US);
        return normalized.contains("token")
                || normalized.contains("secret")
                || normalized.contains("password")
                || normalized.contains("credential");
    }

    private JSONObject dashboardCameraToJson(UvcCameraManager manager) throws JSONException {
        UvcCameraManager.Settings settings = manager.getSettings();
        UvcCameraManager.Status status = manager.getStatus();
        List<UvcCameraManager.Source> sources = manager.getSources();

        JSONObject root = new JSONObject();
        root.put("ok", true);
        root.put("timestamp", System.currentTimeMillis());
        root.put("nativeAvailable", status.isNativeAvailable());

        JSONObject settingsJson = new JSONObject();
        settingsJson.put("enabled", settings.isEnabled());
        settingsJson.put("sourceId", emptyIfNull(settings.getSourceId()));
        // Stream URLs may contain credentials. Return presence only, never the saved URL.
        settingsJson.put("streamUrlConfigured", settings.hasStreamUrl());
        settingsJson.put("mjpegUrlConfigured",
                manager.hasSavedStreamUrl(UvcCameraManager.MJPEG_SOURCE_ID));
        settingsJson.put("rtspUrlConfigured",
                manager.hasSavedStreamUrl(UvcCameraManager.RTSP_SOURCE_ID));
        settingsJson.put("width", settings.getWidth());
        settingsJson.put("height", settings.getHeight());
        settingsJson.put("fps", settings.getFps());
        root.put("settings", settingsJson);

        JSONObject statusJson = new JSONObject();
        statusJson.put("nativeAvailable", status.isNativeAvailable());
        statusJson.put("enabled", status.isEnabled());
        statusJson.put("active", status.isActive());
        statusJson.put("running", status.isRunning());
        statusJson.put("state", emptyIfNull(status.getState()));
        statusJson.put("message", emptyIfNull(status.getMessage()));
        statusJson.put("selectedSourceId", emptyIfNull(status.getSelectedSourceId()));
        statusJson.put("activeSourceId", emptyIfNull(status.getActiveSourceId()));
        statusJson.put("activeSourceName", emptyIfNull(status.getActiveSourceName()));
        statusJson.put("requestedWidth", status.getRequestedWidth());
        statusJson.put("requestedHeight", status.getRequestedHeight());
        statusJson.put("requestedFps", status.getRequestedFps());
        statusJson.put("actualWidth", status.getActualWidth());
        statusJson.put("actualHeight", status.getActualHeight());
        statusJson.put("actualFps", status.getActualFps());
        statusJson.put("format", emptyIfNull(status.getFormat()));
        statusJson.put("sequence", status.getSequence());
        statusJson.put("lastFrameAt", status.getLastFrameAt());
        statusJson.put("error", emptyIfNull(status.getError()));
        statusJson.put("clientCount", status.getClientCount());
        root.put("status", statusJson);

        JSONArray sourceJson = new JSONArray();
        for (UvcCameraManager.Source source : sources) {
            JSONObject item = new JSONObject();
            item.put("id", emptyIfNull(source.getStableId()));
            item.put("stableId", emptyIfNull(source.getStableId()));
            item.put("name", emptyIfNull(source.getDisplayName()));
            item.put("displayName", emptyIfNull(source.getDisplayName()));
            item.put("width", source.getWidth());
            item.put("height", source.getHeight());
            item.put("format", emptyIfNull(source.getFormat()));
            item.put("compressed", source.isCompressed());
            sourceJson.put(item);
        }
        root.put("sources", sourceJson);
        return root;
    }

    /** Graph-only snapshot: does not inspect print files, build job details, or use network APIs. */
    JSONObject buildDashboardTelemetrySample(IMachine machine) throws JSONException {
        long sampledAt = System.currentTimeMillis();
        MachineInfo info = machine.getMachineInfoSubjectHolder().getValue();
        MachineStatus status = machine.getMachineStatusSubjectHolder().getValue();
        boolean connected = status != null && status.connected;
        boolean isFdm = info != null && info.moduleList != null
                && info.workType == IMachine.WorkType.FDM;
        FdmToolhead.FdmToolheadStatus fdmStatus = getFdmStatus(machine, isFdm);
        HeatedBed heatedBed = findModule(info, HeatedBed.class);
        Enclosure enclosure = findModule(info, Enclosure.class);

        JSONObject sample = new JSONObject();
        sample.put("timestamp", sampledAt);
        JSONObject machineJson = new JSONObject();
        machineJson.put("connected", connected);
        machineJson.put("isFdm", isFdm);
        sample.put("machine", machineJson);
        JSONObject toolhead = buildToolheadJson(machine, fdmStatus, isFdm,
                connected, sampledAt);
        sample.put("toolhead", toolhead);
        sample.put("bed", buildBedJson(info, heatedBed, connected, sampledAt));
        sample.put("cooling", buildCoolingJson(toolhead, info, enclosure,
                connected, sampledAt));
        return sample;
    }

    private JSONObject buildDashboardStatus(IMachine machine) throws JSONException {
        MachineInfo info = machine.getMachineInfoSubjectHolder().getValue();
        MachineStatus machineStatus = machine.getMachineStatusSubjectHolder().getValue();
        NewPrintController printController = machine.getNewPrintController();
        IAppService appService = ServiceContainer.getInstance().getService(IAppService.class);
        IPrintWorkspace workspace = ServiceContainer.getInstance().getService(IPrintWorkspace.class);
        IGcodeParser parser = ServiceContainer.getInstance().getService(IGcodeParser.class);

        boolean machineInfoReady = info != null && info.moduleList != null;
        boolean isFdm = machineInfoReady && info.workType == IMachine.WorkType.FDM;
        boolean connected = machineStatus != null && machineStatus.connected;
        int state = printController.getPrintState();
        FdmToolhead.FdmToolheadStatus fdmStatus = getFdmStatus(machine, isFdm);
        HeatedBed heatedBed = findModule(info, HeatedBed.class);
        Enclosure enclosure = findModule(info, Enclosure.class);
        boolean enclosureDoorTelemetryAvailable = connected
                && isEnclosureTelemetryFresh(enclosure, System.currentTimeMillis());
        boolean enclosureDoorOpen = enclosureDoorTelemetryAvailable
                && enclosure.getEnclosureStatusValue().isDoorOpen();
        boolean activeJob = isJobState(state);
        boolean filamentRunout = connected
                && activeJob
                && (printController.isFilamentRunout()
                || hasActiveFilamentRunout(fdmStatus, true));
        ErrorController.EmergencyStopState emergencyStopState = appService.getEmergencyStopState();
        boolean emergencyStop = emergencyStopState != ErrorController.EmergencyStopState.EMERGENCY_STOP_STATE_NORMAL;

        long sampledAt = System.currentTimeMillis();
        JSONObject root = new JSONObject();
        root.put("timestamp", sampledAt);

        JSONObject machineJson = new JSONObject();
        String machineName = ServiceContainer.getInstance()
                .getService(IPreferences.class)
                .getHelper()
                .getMachineName();
        machineJson.put("name", emptyIfNull(machineName));
        machineJson.put("infoReady", machineInfoReady);
        machineJson.put("model", machineInfoReady ? emptyIfNull(info.getModelName()) : "");
        machineJson.put("workType", info == null || info.workType == null ? "NONE" : info.workType.name());
        machineJson.put("isFdm", isFdm);
        machineJson.put("connected", connected);
        machineJson.put("homed", machineStatus != null && machineStatus.isHomed);
        machineJson.put("homing", machineStatus != null && machineStatus.isHoming);
        machineJson.put("state", connected ? stateName(state) : "Disconnected");
        machineJson.put("stateCode", state);
        machineJson.put("ip", getIPAddress());
        machineJson.put("mac", emptyIfNull(getMacAddr()));
        machineJson.put("controllerFirmware", machineInfoReady ? emptyIfNull(info.controllerFWVersion) : "");
        machineJson.put("packageVersion", emptyIfNull(getCurrentVersion()));
        machineJson.put("appVersion", emptyIfNull(appService.getApp().getAppVersionName()));
        machineJson.put("serial", !machineInfoReady
                ? ""
                : firstNonEmpty(info.productSerialNumber, info.burnSerialNumber));
        machineJson.put("productId", machineInfoReady ? info.productId : JSONObject.NULL);
        machineJson.put("headType", machineInfoReady ? info.headType : JSONObject.NULL);

        JSONObject position = new JSONObject();
        Vector currentPosition = machineStatus == null ? null : machineStatus.currentPosition;
        position.put("x", currentPosition == null ? JSONObject.NULL : rounded(currentPosition.getX(), 2));
        position.put("y", currentPosition == null ? JSONObject.NULL : rounded(currentPosition.getY(), 2));
        position.put("z", currentPosition == null ? JSONObject.NULL : rounded(currentPosition.getZ(), 2));
        machineJson.put("position", position);
        machineJson.put("modules", buildModulesJson(info));
        root.put("machine", machineJson);

        JSONObject toolheadJson = buildToolheadJson(
                machine,
                fdmStatus,
                isFdm,
                connected,
                sampledAt
        );
        root.put("toolhead", toolheadJson);
        root.put("bed", buildBedJson(info, heatedBed, connected, sampledAt));
        root.put("cooling", buildCoolingJson(
                toolheadJson,
                info,
                enclosure,
                connected,
                sampledAt
        ));
        root.put("job", buildJobJson(isFdm, state, printController, workspace, parser));

        JSONObject safety = new JSONObject();
        safety.put("emergencyStop", emergencyStop);
        safety.put("emergencyStopState", emergencyStopName(emergencyStopState));
        safety.put("enclosureDoorOpen", enclosureDoorTelemetryAvailable
                ? enclosureDoorOpen
                : JSONObject.NULL);
        safety.put("enclosureDoorTelemetryAvailable", enclosureDoorTelemetryAvailable);
        safety.put("filamentRunout", filamentRunout);
        safety.put("filamentTelemetryAvailable", hasActiveExtruderTelemetry(fdmStatus));
        root.put("safety", safety);

        boolean actionPending;
        String lastAction;
        String lastActionResult;
        int lastActionErrorCode;
        long lastActionCompletedAt;
        synchronized (mActionLock) {
            actionPending = mActionPending;
            lastAction = mLastAction;
            lastActionResult = mLastActionResult;
            lastActionErrorCode = mLastActionErrorCode;
            lastActionCompletedAt = mLastActionCompletedAt;
        }

        boolean baseControlsAvailable = connected && isFdm && !emergencyStop && !actionPending;
        JSONObject controls = new JSONObject();
        controls.put("canPause", baseControlsAvailable
                && state == MachineOperationStatus.SYSTEM_STATUS_PRINTING.value());
        controls.put("canResume", baseControlsAvailable
                && state == MachineOperationStatus.SYSTEM_STATUS_PAUSED.value());
        controls.put("canCancel", baseControlsAvailable
                && (state == MachineOperationStatus.SYSTEM_STATUS_PRINTING.value()
                || state == MachineOperationStatus.SYSTEM_STATUS_PAUSED.value()));
        controls.put("commandPending", actionPending);
        controls.put("blockedReason", actionPending
                ? "Waiting for the printer"
                : controlBlockedReason(
                connected,
                isFdm,
                emergencyStop,
                state
        ));
        JSONObject lastCommand = new JSONObject();
        lastCommand.put("action", lastAction);
        lastCommand.put("result", lastActionResult);
        lastCommand.put("errorCode", lastActionErrorCode);
        lastCommand.put("completedAt", lastActionCompletedAt);
        controls.put("lastCommand", lastCommand);
        root.put("controls", controls);

        return root;
    }

    private JSONObject buildToolheadJson(
            IMachine machine,
            FdmToolhead.FdmToolheadStatus fdmStatus,
            boolean isFdm,
            boolean connected,
            long sampledAt
    ) throws JSONException {
        JSONObject toolhead = new JSONObject();
        FDMController fdmController = machine.getFDMController();
        FdmToolhead fdmToolhead = isFdm
                && fdmController != null
                && fdmController.getToolHeadCounts() > 0
                ? fdmController.getFdmToolhead(0)
                : null;
        boolean available = fdmToolhead != null;
        int headType = available
                ? fdmController.getHeadType()
                : Module.ModuleType.HEAD_UNPLUGGED;

        List<Extruder> extruders = fdmStatus == null || fdmStatus.getExtruderList() == null
                ? Collections.emptyList()
                : snapshotList(fdmStatus.getExtruderList());
        Extruder left = findExtruder(extruders, Extruder.EXTRUDER_LEFT);
        Extruder right = findExtruder(extruders, Extruder.EXTRUDER_RIGHT);
        boolean hasLoadedExtruderTelemetry = isExtruderTelemetryReady(left)
                || isExtruderTelemetryReady(right);
        List<Fan> fanList = fdmStatus == null || fdmStatus.getFanList() == null
                ? Collections.emptyList()
                : snapshotList(fdmStatus.getFanList());
        boolean hasLoadedFanTelemetry = !fanList.isEmpty();
        boolean hasLiveExtruderTelemetry = available && fdmToolhead.hasLiveExtruderStatus();
        boolean hasLiveFanTelemetry = available && fdmToolhead.hasLiveFanStatus();
        long extruderUpdatedAt = hasLiveExtruderTelemetry
                ? fdmToolhead.getExtruderStatusUpdatedAt()
                : 0L;
        long fanUpdatedAt = hasLiveFanTelemetry
                ? fdmToolhead.getFanStatusUpdatedAt()
                : 0L;
        boolean extruderTelemetryAvailable = connected
                && hasLoadedExtruderTelemetry
                && fdmToolhead.isExtruderStatusFresh(THERMAL_TELEMETRY_MAX_AGE_MS);
        boolean fanTelemetryAvailable = connected
                && hasLoadedFanTelemetry
                && fdmToolhead.isFanStatusFresh(THERMAL_TELEMETRY_MAX_AGE_MS);
        // Preserve the existing toolhead telemetry contract for nozzle consumers. Fan freshness is
        // deliberately separate because the controller publishes the two streams independently.
        boolean telemetryAvailable = extruderTelemetryAvailable;
        boolean stale = available
                && hasLiveExtruderTelemetry
                && !extruderTelemetryAvailable;

        toolhead.put("available", available);
        toolhead.put("telemetryAvailable", telemetryAvailable);
        toolhead.put("stale", stale);
        toolhead.put("fanTelemetryAvailable", fanTelemetryAvailable);
        toolhead.put("fanTelemetryStale", available
                && hasLiveFanTelemetry
                && !fanTelemetryAvailable);
        toolhead.put("telemetryUpdatedAt", extruderUpdatedAt);
        toolhead.put("telemetryAgeMs", telemetryAgeJson(extruderUpdatedAt, sampledAt));
        toolhead.put("fanTelemetryUpdatedAt", fanUpdatedAt);
        toolhead.put("fanTelemetryAgeMs", telemetryAgeJson(fanUpdatedAt, sampledAt));
        toolhead.put("active", extruderTelemetryAvailable
                && fdmStatus != null
                && fdmStatus.isActive());
        toolhead.put("type", available ? headType : JSONObject.NULL);

        JSONArray nozzles = new JSONArray();
        nozzles.put(buildNozzleJson(
                left,
                Extruder.EXTRUDER_LEFT,
                "Left",
                extruderTelemetryAvailable,
                hasLiveExtruderTelemetry,
                extruderUpdatedAt
        ));
        nozzles.put(buildNozzleJson(
                right,
                Extruder.EXTRUDER_RIGHT,
                "Right",
                extruderTelemetryAvailable,
                hasLiveExtruderTelemetry,
                extruderUpdatedAt
        ));
        toolhead.put("nozzles", nozzles);

        JSONArray fans = new JSONArray();
        JSONArray fanChannels = new JSONArray();
        for (Fan fan : fanList) {
            if (fan == null) continue;
            int speedPwm = fan.getSpeedLevel();
            int id = fan.getId();
            String side = fanSide(headType, id);
            JSONObject fanJson = new JSONObject();
            fanJson.put("seriesKey", fanSeriesKey(headType, id));
            fanJson.put("source", "toolhead");
            fanJson.put("kind", fanKind(headType, id));
            fanJson.put("side", TextUtils.isEmpty(side) ? JSONObject.NULL : side);
            fanJson.put("id", fan.getId());
            fanJson.put("type", fan.getType());
            fanJson.put("name", fanName(headType, id));
            fanJson.put("available", true);
            fanJson.put("telemetryAvailable", fanTelemetryAvailable);
            fanJson.put("stale", hasLiveFanTelemetry && !fanTelemetryAvailable);
            fanJson.put("updatedAt", fanUpdatedAt);
            fanJson.put("speedPwm", fanTelemetryAvailable ? speedPwm : JSONObject.NULL);
            fanJson.put("speedPercent", fanTelemetryAvailable
                    ? clampPercent(Math.round(speedPwm / 255f * 100f))
                    : JSONObject.NULL);
            fanJson.put("speedRaw", fanTelemetryAvailable ? speedPwm : JSONObject.NULL);
            fanJson.put("rawMaximum", 255);
            fanChannels.put(fanJson);

            // Preserve the pre-chart API: legacy fans exist only with fresh telemetry and always
            // contain numeric speed fields. Availability definitions live in fanChannels/cooling.
            if (fanTelemetryAvailable) {
                JSONObject legacyFan = new JSONObject();
                legacyFan.put("id", id);
                legacyFan.put("type", fan.getType());
                legacyFan.put("name", legacyFanName(id));
                legacyFan.put("speedPwm", speedPwm);
                legacyFan.put("speedPercent", clampPercent(
                        Math.round(speedPwm / 255f * 100f)
                ));
                fans.put(legacyFan);
            }
        }
        toolhead.put("fans", fans);
        toolhead.put("fanChannels", fanChannels);
        return toolhead;
    }

    private JSONObject buildNozzleJson(
            Extruder extruder,
            int id,
            String side,
            boolean streamTelemetryAvailable,
            boolean hasLiveStreamTelemetry,
            long updatedAt
    ) throws JSONException {
        JSONObject nozzle = new JSONObject();
        boolean available = extruder != null
                && (extruder.getDiameter() > 0f
                || extruder.getTemperature() > 0f
                || extruder.getTargetTemperature() > 0f);
        boolean telemetryAvailable = available && streamTelemetryAvailable;
        nozzle.put("id", id);
        nozzle.put("side", side);
        nozzle.put("available", available);
        nozzle.put("telemetryAvailable", telemetryAvailable);
        nozzle.put("stale", available && hasLiveStreamTelemetry && !telemetryAvailable);
        nozzle.put("updatedAt", hasLiveStreamTelemetry ? updatedAt : 0L);

        if (!telemetryAvailable) {
            nozzle.put("diameterMm", JSONObject.NULL);
            nozzle.put("currentC", JSONObject.NULL);
            nozzle.put("targetC", JSONObject.NULL);
            nozzle.put("heating", false);
            nozzle.put("atTarget", false);
            nozzle.put("active", false);
            nozzle.put("model", "");
            nozzle.put("modelCode", JSONObject.NULL);
            nozzle.put("filamentMissing", false);
            nozzle.put("filamentRunout", false);
            nozzle.put("detectionEnabled", false);
            nozzle.put("state", JSONObject.NULL);
            return nozzle;
        }

        float current = extruder.getTemperature();
        float target = extruder.getTargetTemperature();
        boolean detectionEnabled = extruder.getFilamentDetectionStatus() == 0;
        nozzle.put("diameterMm", extruder.getDiameter() > 0f
                ? rounded(extruder.getDiameter(), 2)
                : JSONObject.NULL);
        nozzle.put("currentC", rounded(current, 1));
        nozzle.put("targetC", rounded(target, 1));
        nozzle.put("heating", target > 0f && current + 2f < target);
        nozzle.put("atTarget", target > 0f && Math.abs(current - target) <= 5f);
        nozzle.put("active", extruder.getState() == 1);
        nozzle.put("model", extruderModelName(extruder.getModel()));
        nozzle.put("modelCode", extruder.getModel());
        boolean filamentMissing = detectionEnabled && extruder.getFilamentStatus();
        nozzle.put("filamentMissing", filamentMissing);
        nozzle.put("filamentRunout", extruder.getState() == 1 && filamentMissing);
        nozzle.put("detectionEnabled", detectionEnabled);
        nozzle.put("state", extruder.getState());
        return nozzle;
    }

    private JSONObject buildBedJson(
            MachineInfo info,
            HeatedBed heatedBed,
            boolean connected,
            long sampledAt
    ) throws JSONException {
        JSONObject bedJson = new JSONObject();
        boolean available = info != null && info.isHeatedBedAvailable && heatedBed != null;
        HeatedBed.HeatedBedStatus status = available
                ? heatedBed.getHeatedBedStatusSubjectHolder().getValue()
                : null;
        List<HeatedBed.ZoneInfo> zones = status == null || status.getZoneList() == null
                ? Collections.emptyList()
                : snapshotList(status.getZoneList());
        HeatedBed.ZoneInfo inner = findBedZone(zones, 0);
        HeatedBed.ZoneInfo outer = findBedZone(zones, 1);
        boolean hasLoadedTelemetry = isBedZoneTelemetryReady(inner)
                || isBedZoneTelemetryReady(outer);
        boolean hasLiveTelemetry = available && heatedBed.hasLiveHeatedBedStatus();
        long telemetryUpdatedAt = hasLiveTelemetry
                ? heatedBed.getHeatedBedStatusUpdatedAt()
                : 0L;
        boolean telemetryAvailable = connected
                && hasLoadedTelemetry
                && heatedBed.isHeatedBedStatusFresh(THERMAL_TELEMETRY_MAX_AGE_MS);
        int mode = status == null ? -1 : status.getWorkMode();
        boolean modeValid = mode == HeatedBed.HeatedBedStatus.HEATED_BED_STATUS_WORK_MODE_INNER
                || mode == HeatedBed.HeatedBedStatus.HEATED_BED_STATUS_WORK_MODE_WHOLE;
        boolean wholeBed = telemetryAvailable
                && mode == HeatedBed.HeatedBedStatus.HEATED_BED_STATUS_WORK_MODE_WHOLE;

        bedJson.put("available", available);
        bedJson.put("telemetryAvailable", telemetryAvailable);
        bedJson.put("stale", available && hasLiveTelemetry && !telemetryAvailable);
        bedJson.put("telemetryUpdatedAt", telemetryUpdatedAt);
        bedJson.put("telemetryAgeMs", telemetryAgeJson(telemetryUpdatedAt, sampledAt));
        bedJson.put("modeCode", telemetryAvailable && modeValid ? mode : JSONObject.NULL);
        bedJson.put("mode", !available
                ? "Unavailable"
                : !telemetryAvailable || !modeValid
                ? "Unknown"
                : wholeBed ? "Whole bed (inner + outer)" : "Inner zone");

        JSONArray zonesJson = new JSONArray();
        zonesJson.put(buildBedZoneJson(
                inner,
                0,
                "Inner",
                telemetryAvailable,
                hasLiveTelemetry,
                telemetryUpdatedAt,
                telemetryAvailable
        ));
        zonesJson.put(buildBedZoneJson(
                outer,
                1,
                "Outer",
                telemetryAvailable && wholeBed,
                hasLiveTelemetry,
                telemetryUpdatedAt,
                telemetryAvailable
        ));
        bedJson.put("zones", zonesJson);
        return bedJson;
    }

    private JSONObject buildBedZoneJson(
            HeatedBed.ZoneInfo zone,
            int id,
            String name,
            boolean selected,
            boolean hasLiveStreamTelemetry,
            long updatedAt,
            boolean streamTelemetryAvailable
    ) throws JSONException {
        JSONObject zoneJson = new JSONObject();
        boolean available = zone != null;
        boolean telemetryAvailable = available && streamTelemetryAvailable;
        zoneJson.put("id", id);
        zoneJson.put("name", name);
        zoneJson.put("available", available);
        zoneJson.put("telemetryAvailable", telemetryAvailable);
        zoneJson.put("stale", available && hasLiveStreamTelemetry && !telemetryAvailable);
        zoneJson.put("updatedAt", hasLiveStreamTelemetry ? updatedAt : 0L);
        zoneJson.put("selected", selected);
        if (!telemetryAvailable) {
            zoneJson.put("currentC", JSONObject.NULL);
            zoneJson.put("targetC", JSONObject.NULL);
            zoneJson.put("heating", false);
        } else {
            float current = zone.getCurrentTemperature();
            int target = zone.getTargetTemperature();
            zoneJson.put("currentC", rounded(current, 1));
            zoneJson.put("targetC", target);
            zoneJson.put("heating", selected && target > 0 && current + 2f < target);
        }
        return zoneJson;
    }

    private JSONObject buildCoolingJson(
            JSONObject toolheadJson,
            MachineInfo info,
            Enclosure enclosure,
            boolean connected,
            long sampledAt
    ) throws JSONException {
        JSONObject cooling = new JSONObject();
        cooling.put("sampledAt", sampledAt);
        cooling.put("recommendedPollIntervalMs", TELEMETRY_REFRESH_INTERVAL_MS);

        JSONArray fans = new JSONArray();
        boolean telemetryAvailable = false;
        boolean stale = false;
        JSONArray toolheadFans = toolheadJson == null
                ? null
                : toolheadJson.optJSONArray("fanChannels");
        if (toolheadFans != null) {
            for (int index = 0; index < toolheadFans.length(); index++) {
                JSONObject fan = toolheadFans.optJSONObject(index);
                if (fan == null) continue;
                fans.put(fan);
                telemetryAvailable |= fan.optBoolean("telemetryAvailable", false);
                stale |= fan.optBoolean("stale", false);
            }
        }

        boolean enclosurePresent = info != null
                && info.isEnclosureAvailable
                && enclosure != null;
        if (enclosurePresent) {
            Enclosure.EnclosureStatus status = enclosure.getEnclosureStatusValue();
            boolean hasLiveTelemetry = enclosure.hasLiveEnclosureStatus()
                    && status != null
                    && enclosure.getEnclosureStatusUpdatedAt() > 0L;
            boolean enclosureTelemetryAvailable = connected
                    && isEnclosureTelemetryFresh(enclosure, sampledAt);
            long updatedAt = hasLiveTelemetry
                    ? enclosure.getEnclosureStatusUpdatedAt()
                    : 0L;
            boolean enclosureStale = hasLiveTelemetry && !enclosureTelemetryAvailable;

            JSONObject fan = new JSONObject();
            fan.put("seriesKey", "enclosureExhaust");
            fan.put("source", "enclosure");
            fan.put("kind", "exhaust");
            fan.put("side", JSONObject.NULL);
            fan.put("id", 0);
            fan.put("type", JSONObject.NULL);
            fan.put("name", "Enclosure Exhaust Fan");
            fan.put("available", true);
            fan.put("telemetryAvailable", enclosureTelemetryAvailable);
            fan.put("stale", enclosureStale);
            fan.put("updatedAt", updatedAt);
            fan.put("speedPwm", JSONObject.NULL);
            fan.put("speedPercent", enclosureTelemetryAvailable
                    ? clampPercent(status.getFanSpeed())
                    : JSONObject.NULL);
            fan.put("speedRaw", enclosureTelemetryAvailable
                    ? clampPercent(status.getFanSpeed())
                    : JSONObject.NULL);
            fan.put("rawMaximum", 100);
            fans.put(fan);

            telemetryAvailable |= enclosureTelemetryAvailable;
            stale |= enclosureStale;
        }

        cooling.put("available", fans.length() > 0);
        cooling.put("telemetryAvailable", telemetryAvailable);
        cooling.put("stale", stale);
        cooling.put("fans", fans);
        return cooling;
    }

    private JSONObject buildJobJson(
            boolean isFdm,
            int state,
            NewPrintController controller,
            IPrintWorkspace workspace,
            IGcodeParser parser
    ) throws JSONException {
        JSONObject job = new JSONObject();
        boolean active = isFdm && isJobState(state);
        if (!active) {
            job.put("active", false);
            job.put("state", stateName(state));
            job.put("filename", "");
            job.put("fileSizeBytes", JSONObject.NULL);
            job.put("progressPercent", 0);
            job.put("progressRatio", 0);
            job.put("elapsedSeconds", 0);
            job.put("estimatedSeconds", 0);
            job.put("remainingSeconds", 0);
            job.put("totalLines", 0);
            job.put("currentLine", 0);
            job.put("printModeCode", JSONObject.NULL);
            job.put("printMode", "");
            job.put("thumbnailAvailable", false);
            job.put("layers", JSONObject.NULL);
            job.put("layerHeightMm", JSONObject.NULL);
            job.put("materialWeightG", JSONObject.NULL);
            job.put("materialLengthM", JSONObject.NULL);
            job.put("nozzleLeftMm", JSONObject.NULL);
            job.put("nozzleRightMm", JSONObject.NULL);
            job.put("materials", new JSONArray());
            JSONObject emptyWorkSize = new JSONObject();
            emptyWorkSize.put("x", JSONObject.NULL);
            emptyWorkSize.put("y", JSONObject.NULL);
            job.put("workSize", emptyWorkSize);
            return job;
        }

        float rawProgress = controller.getProgress();
        float progress = Float.isNaN(rawProgress) || Float.isInfinite(rawProgress)
                ? 0f
                : Math.max(0f, Math.min(1f, rawProgress));
        int elapsedSeconds = Math.max(0, controller.getTickCounter().getCount());
        float rawEstimated = workspace.getEstimatedTime();
        int estimatedSeconds = Float.isNaN(rawEstimated) || Float.isInfinite(rawEstimated)
                ? 0
                : Math.max(0, Math.round(rawEstimated));
        int remainingSeconds = active
                ? Math.max(0, Math.round(
                (1f - progress) * elapsedSeconds
                        + (1f - progress) * (1f - progress) * estimatedSeconds
        ))
                : 0;
        int totalLines = controller.getTotalLines();
        if (totalLines <= 0) totalLines = workspace.getFileTotalLineCount();
        int currentLine = totalLines > 0 ? Math.round(progress * totalLines) : 0;
        IFile printFile = workspace.getPrintFile();

        job.put("active", active);
        job.put("state", stateName(state));
        job.put("filename", emptyIfNull(workspace.getFileName()));
        job.put("fileSizeBytes", printFile == null ? JSONObject.NULL : printFile.length());
        job.put("progressPercent", rounded(progress * 100f, 1));
        job.put("progressRatio", rounded(progress, 4));
        job.put("elapsedSeconds", elapsedSeconds);
        job.put("estimatedSeconds", estimatedSeconds);
        job.put("remainingSeconds", remainingSeconds);
        job.put("totalLines", Math.max(0, totalLines));
        job.put("currentLine", currentLine);
        job.put("printModeCode", workspace.getPrintMode());
        job.put("printMode", printModeName(workspace.getPrintMode()));
        job.put("thumbnailAvailable", active && parser.getGcodeThumbnail() != null);

        job.put("layers", optionalParserNumber(parser.getLayerNumber()));
        job.put("layerHeightMm", optionalParserNumber(parser.getLayerHeight()));
        job.put("materialWeightG", optionalParserNumber(parser.getMaterialWeight()));
        job.put("materialLengthM", optionalParserNumber(parser.getMaterialLength()));
        job.put("nozzleLeftMm", optionalParserNumber(parser.getNozzle_0_Diameter()));
        job.put("nozzleRightMm", optionalParserNumber(parser.getNozzle_1_Diameter()));

        JSONArray materials = new JSONArray();
        addNonEmpty(materials, parser.getMaterial_0());
        addNonEmpty(materials, parser.getMaterial_1());
        job.put("materials", materials);

        JSONObject workSize = new JSONObject();
        workSize.put("x", optionalParserNumber(parser.getWorkSizeX()));
        workSize.put("y", optionalParserNumber(parser.getWorkSizeY()));
        job.put("workSize", workSize);
        return job;
    }

    private JSONArray buildModulesJson(MachineInfo info) throws JSONException {
        JSONArray result = new JSONArray();
        if (info == null || info.moduleList == null) return result;

        List<Module> modules = snapshotList(info.moduleList);
        for (Module module : modules) {
            if (module == null || module.getModuleInfo() == null) continue;
            Module.ModuleInfo moduleInfo = module.getModuleInfo();
            JSONObject json = new JSONObject();
            json.put("id", moduleInfo.getModuleId());
            json.put("index", moduleInfo.getModuleIndex());
            json.put("state", moduleInfo.getModuleState());
            json.put("serial", moduleInfo.getSn());
            json.put("hardwareVersion", moduleInfo.hardwareVersionProp.getValue());
            json.put("firmwareVersion", emptyIfNull(moduleInfo.getFirmwareVersion()));
            result.put(json);
        }
        return result;
    }

    private void handleDashboardJobAction(
            final String action,
            HttpRequest request,
            HttpResponse response
    ) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");

        if (!isDashboardRequestAuthorized(request)) {
            writeJsonError(response, StatusCode.SC_FORBIDDEN, "Dashboard authorization failed.");
            return;
        }

        try {
            IMachine machine = ServiceContainer.getInstance().getService(IMachine.class);
            IAppService appService = ServiceContainer.getInstance().getService(IAppService.class);
            MachineInfo info = machine.getMachineInfoSubjectHolder().getValue();
            MachineStatus machineStatus = machine.getMachineStatusSubjectHolder().getValue();
            NewPrintController controller = machine.getNewPrintController();
            int state = controller.getPrintState();

            if (machineStatus == null || !machineStatus.connected) {
                writeJsonError(response, StatusCode.SC_CONFLICT, "Printer is disconnected.");
                return;
            }
            if (info == null || info.workType != IMachine.WorkType.FDM) {
                writeJsonError(response, StatusCode.SC_CONFLICT, "3D printing mode is not active.");
                return;
            }
            if (appService.getEmergencyStopState()
                    != ErrorController.EmergencyStopState.EMERGENCY_STOP_STATE_NORMAL) {
                writeJsonError(response, StatusCode.SC_CONFLICT, "Emergency stop is active.");
                return;
            }

            if ("pause".equals(action)
                    && state != MachineOperationStatus.SYSTEM_STATUS_PRINTING.value()) {
                writeJsonError(response, StatusCode.SC_CONFLICT, "The job is not in a pausable state.");
                return;
            }
            if ("resume".equals(action)) {
                if (state != MachineOperationStatus.SYSTEM_STATUS_PAUSED.value()) {
                    writeJsonError(response, StatusCode.SC_CONFLICT, "The job is not paused.");
                    return;
                }
            }
            if ("cancel".equals(action)
                    && state != MachineOperationStatus.SYSTEM_STATUS_PRINTING.value()
                    && state != MachineOperationStatus.SYSTEM_STATUS_PAUSED.value()) {
                writeJsonError(response, StatusCode.SC_CONFLICT, "The job is not in a cancellable state.");
                return;
            }

            final long actionId;
            long now = SystemClock.elapsedRealtime();
            synchronized (mActionLock) {
                if (mActionPending) {
                    writeJsonError(response, StatusCode.SC_CONFLICT, "Another job command is in progress.");
                    return;
                }
                if (now - mLastActionAt < ACTION_DEBOUNCE_MS) {
                    writeJsonError(response, StatusCode.SC_CONFLICT, "Another job command is still settling.");
                    return;
                }
                mLastActionAt = now;
                mActionPending = true;
                actionId = ++mNextActionId;
                mPendingActionId = actionId;
                mLastAction = action;
                mLastActionResult = "pending";
                mLastActionErrorCode = 0;
            }

            final Runnable timeoutRunnable =
                    () -> finishDashboardAction(actionId, action, "timed_out", -1);
            synchronized (mActionLock) {
                mDashboardActionTimeout = timeoutRunnable;
            }
            if (!mMainHandler.postDelayed(timeoutRunnable, ACTION_TIMEOUT_MS)) {
                finishDashboardAction(actionId, action, "queue_failed", -1);
                writeJsonError(response, StatusCode.SC_INTERNAL_SERVER_ERROR, "Unable to queue the job command.");
                return;
            }

            boolean posted = mMainHandler.post(() -> {
                try {
                    synchronized (mActionLock) {
                        if (!mActionPending || mPendingActionId != actionId) return;
                    }

                    MachineStatus latestMachineStatus = machine.getMachineStatusSubjectHolder().getValue();
                    MachineInfo latestInfo = machine.getMachineInfoSubjectHolder().getValue();
                    if (latestMachineStatus == null
                            || !latestMachineStatus.connected
                            || latestInfo == null
                            || latestInfo.moduleList == null
                            || latestInfo.workType != IMachine.WorkType.FDM
                            || appService.getEmergencyStopState()
                            != ErrorController.EmergencyStopState.EMERGENCY_STOP_STATE_NORMAL) {
                        finishDashboardAction(actionId, action, "safety_changed", -1);
                        return;
                    }

                    int latestState = controller.getPrintState();
                    boolean stateStillValid = ("pause".equals(action)
                            && latestState == MachineOperationStatus.SYSTEM_STATUS_PRINTING.value())
                            || ("resume".equals(action)
                            && latestState == MachineOperationStatus.SYSTEM_STATUS_PAUSED.value())
                            || ("cancel".equals(action)
                            && (latestState == MachineOperationStatus.SYSTEM_STATUS_PRINTING.value()
                            || latestState == MachineOperationStatus.SYSTEM_STATUS_PAUSED.value()));
                    if (!stateStillValid) {
                        finishDashboardAction(actionId, action, "state_changed", -1);
                        return;
                    }

                    Disposable eventDisposable = controller.getPrintEventObservable()
                            .observeOn(AndroidSchedulers.mainThread())
                            .filter(event -> eventMatchesAction(action, event.getPrintEventState()))
                            .take(1)
                            .subscribe(event -> {
                                PrintEventState eventState = event.getPrintEventState();
                                boolean success = eventState == PrintEventState.PAUSE_SUCCESS
                                        || eventState == PrintEventState.RESUME_SUCCESS
                                        || eventState == PrintEventState.STOP_SUCCESS;
                                finishDashboardAction(
                                        actionId,
                                        action,
                                        success ? "succeeded" : "failed",
                                        event.getErrorCode()
                                );
                            }, error -> {
                                LogHelper.log(error);
                                finishDashboardAction(actionId, action, "failed", -1);
                            });
                    synchronized (mActionLock) {
                        if (mActionPending && mPendingActionId == actionId) {
                            mDashboardActionDisposable = eventDisposable;
                        } else {
                            eventDisposable.dispose();
                        }
                    }

                    boolean commandStarted;
                    if ("pause".equals(action)) {
                        commandStarted = controller.pause();
                    } else if ("resume".equals(action)) {
                        commandStarted = controller.resume();
                    } else {
                        commandStarted = controller.stop();
                    }
                    if (!commandStarted) {
                        finishDashboardAction(actionId, action, "busy", 254);
                    }
                } catch (Exception e) {
                    LogHelper.log(e);
                    finishDashboardAction(actionId, action, "failed", -1);
                }
            });

            if (!posted) {
                finishDashboardAction(actionId, action, "queue_failed", -1);
                writeJsonError(response, StatusCode.SC_INTERNAL_SERVER_ERROR, "Unable to queue the job command.");
                return;
            }

            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("accepted", true);
            result.put("action", action);
            writeJson(response, StatusCode.SC_ACCEPTED, result);
        } catch (Exception e) {
            LogHelper.log(e);
            writeJsonError(response, StatusCode.SC_INTERNAL_SERVER_ERROR, "Unable to control the print job.");
        }
    }

    private boolean eventMatchesAction(String action, PrintEventState state) {
        if ("pause".equals(action)) {
            return state == PrintEventState.PAUSE_SUCCESS || state == PrintEventState.PAUSE_FAIL;
        }
        if ("resume".equals(action)) {
            return state == PrintEventState.RESUME_SUCCESS || state == PrintEventState.RESUME_FAIL;
        }
        return state == PrintEventState.STOP_SUCCESS || state == PrintEventState.STOP_FAIL;
    }

    private void finishDashboardAction(
            long actionId,
            String action,
            String result,
            int errorCode
    ) {
        Disposable disposable = null;
        Runnable timeout = null;
        synchronized (mActionLock) {
            if (!mActionPending || mPendingActionId != actionId) return;
            mActionPending = false;
            mPendingActionId = 0L;
            mLastAction = action;
            mLastActionResult = result;
            mLastActionErrorCode = errorCode;
            mLastActionCompletedAt = System.currentTimeMillis();
            disposable = mDashboardActionDisposable;
            mDashboardActionDisposable = null;
            timeout = mDashboardActionTimeout;
            mDashboardActionTimeout = null;
        }
        if (disposable != null && !disposable.isDisposed()) disposable.dispose();
        if (timeout != null) mMainHandler.removeCallbacks(timeout);
    }

    void maybeRefreshTelemetry(IMachine machine) {
        MachineInfo info = machine.getMachineInfoSubjectHolder().getValue();
        MachineStatus status = machine.getMachineStatusSubjectHolder().getValue();
        if (info == null
                || info.workType != IMachine.WorkType.FDM
                || status == null
                || !status.connected) {
            return;
        }

        long now = SystemClock.elapsedRealtime();
        synchronized (sTelemetryLock) {
            if (now - sLastTelemetryRefreshAt < TELEMETRY_REFRESH_INTERVAL_MS) return;
            sLastTelemetryRefreshAt = now;
        }

        mMainHandler.post(() -> {
            try {
                FDMController fdmController = machine.getFDMController();
                if (fdmController != null && fdmController.getToolHeadCounts() > 0) {
                    FdmToolhead toolhead = fdmController.getFdmToolhead(0);
                    if (toolhead != null
                            && (sToolheadRefreshDisposable == null
                            || sToolheadRefreshDisposable.isDisposed())) {
                        sToolheadRefreshDisposable = toolhead.requestInfo()
                                .timeout(THERMAL_REFRESH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                                .take(1)
                                .subscribe(ignored -> {
                                }, LogHelper::log);
                    }
                }

                MachineInfo latestInfo = machine.getMachineInfoSubjectHolder().getValue();
                HeatedBed heatedBed = findModule(latestInfo, HeatedBed.class);
                if (heatedBed != null
                        && (sBedRefreshDisposable == null || sBedRefreshDisposable.isDisposed())) {
                    sBedRefreshDisposable = heatedBed.requestInfo()
                            .timeout(THERMAL_REFRESH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            .take(1)
                            .subscribe(ignored -> {
                            }, LogHelper::log);
                }

                boolean enclosureControlPending = DashboardTelemetryHistory.getInstance()
                        .isEnclosureControlPending();
                Enclosure enclosure = findModule(latestInfo, Enclosure.class);
                if (enclosure != null
                        && !enclosureControlPending
                        && (sEnclosureRefreshDisposable == null
                        || sEnclosureRefreshDisposable.isDisposed())) {
                    sEnclosureRefreshDisposable = enclosure.requestInfo()
                            .timeout(ENCLOSURE_REFRESH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            .take(1)
                            .subscribe(ignored -> {
                            }, LogHelper::log);
                }
            } catch (Exception e) {
                LogHelper.log(e);
            }
        });
    }

    private FdmToolhead.FdmToolheadStatus getFdmStatus(IMachine machine, boolean isFdm) {
        if (!isFdm) return null;
        FDMController controller = machine.getFDMController();
        if (controller == null || controller.getToolHeadCounts() <= 0) return null;
        FdmToolhead toolhead = controller.getFdmToolhead(0);
        if (toolhead == null || toolhead.getToolheadStatusSubjectHolder() == null) return null;
        return toolhead.getToolheadStatusSubjectHolder().getValue();
    }

    private boolean hasActiveFilamentRunout(
            FdmToolhead.FdmToolheadStatus status,
            boolean jobActive
    ) {
        if (!jobActive || status == null || status.getExtruderList() == null) return false;
        List<Extruder> extruders = snapshotList(status.getExtruderList());
        for (Extruder extruder : extruders) {
            if (extruder != null
                    && extruder.getState() == 1
                    && extruder.getFilamentDetectionStatus() == 0
                    && extruder.getFilamentStatus()) {
                return true;
            }
        }
        return false;
    }

    private boolean hasActiveExtruderTelemetry(FdmToolhead.FdmToolheadStatus status) {
        if (status == null || status.getExtruderList() == null) return false;
        for (Extruder extruder : snapshotList(status.getExtruderList())) {
            if (extruder != null
                    && extruder.getState() == 1
                    && isExtruderTelemetryReady(extruder)) {
                return true;
            }
        }
        return false;
    }

    private boolean isJobState(int state) {
        return state == MachineOperationStatus.SYSTEM_STATUS_STARTING.value()
                || state == MachineOperationStatus.SYSTEM_STATUS_PRINTING.value()
                || state == MachineOperationStatus.SYSTEM_STATUS_PAUSING.value()
                || state == MachineOperationStatus.SYSTEM_STATUS_PAUSED.value()
                || state == MachineOperationStatus.SYSTEM_STATUS_STOPING.value()
                || state == MachineOperationStatus.SYSTEM_STATUS_FINISHING.value()
                || state == MachineOperationStatus.SYSTEM_STATUS_RECOVERING.value()
                || state == MachineOperationStatus.SYSTEM_STATUS_RESUMING.value();
    }

    private String stateName(int state) {
        MachineOperationStatus status = MachineOperationStatus.valueOf(state);
        if (status == null) return "Unknown (" + state + ")";

        switch (status) {
            case SYSTEM_STATUS_IDLE:
                return "Ready";
            case SYSTEM_STATUS_STARTING:
                return "Starting";
            case SYSTEM_STATUS_PRINTING:
                return "Printing";
            case SYSTEM_STATUS_PAUSING:
                return "Pausing";
            case SYSTEM_STATUS_PAUSED:
                return "Paused";
            case SYSTEM_STATUS_STOPING:
                return "Cancelling";
            case SYSTEM_STATUS_STOPED:
                return "Cancelled";
            case SYSTEM_STATUS_FINISHING:
                return "Finishing";
            case SYSTEM_STATUS_COMPLETED:
                return "Completed";
            case SYSTEM_STATUS_RECOVERING:
                return "Recovering";
            case SYSTEM_STATUS_RESUMING:
                return "Resuming";
            case SYSTEM_STATUS_EMERGENCY_STOP:
                return "Emergency stop";
            case SYSTEM_STATUS_POWER_LOSS:
                return "Power-loss recovery";
            case SYSTEM_STATUS_REPLACE_MODE:
                return "Tool replacement";
            case SYSTEM_STATUS_XY_CALIBRATING:
            case SYSTEM_STATUS_XY_CALIBRATING_PRINTING:
            case SYSTEM_STATUS_AUTO_BEDLEVEL:
            case SYSTEM_STATUS_MANUAL_BEDLEVEL:
            case SYSTEM_STATUS_AUTO_BED_DETECTION:
            case SYSTEM_STATUS_MANUAL_BED_DETECTION:
            case SYSTEM_STATUS_PROBE_SENSOR_CALIBRATION:
                return "Calibration";
            case SYSTEM_STATUS_APP_UPGRADE:
            case SYSTEM_STATUS_MODULE_UPGRADE:
                return "Updating";
            default:
                return "Busy";
        }
    }

    private String controlBlockedReason(
            boolean connected,
            boolean isFdm,
            boolean emergencyStop,
            int state
    ) {
        if (!connected) return "Printer disconnected";
        if (!isFdm) return "3D printing mode is not active";
        if (emergencyStop) return "Emergency stop active";
        if (MachineOperationStatus.isPrintChange(state)) return stateName(state);
        if (!isJobState(state)) return "No active print job";
        return "";
    }

    private String printModeName(int mode) {
        switch (mode) {
            case IPrintWorkspace.PRINT_MODE_DUAL_EXTRUDER_BACK_UP:
                return "Backup";
            case IPrintWorkspace.PRINT_MODE_CLONE:
                return "Copy";
            case IPrintWorkspace.PRINT_MODE_MIRROR:
                return "Mirror";
            case IPrintWorkspace.PRINT_MODE_NORMAL:
            default:
                return "Normal";
        }
    }

    private String extruderModelName(int model) {
        switch (model) {
            case Extruder.EXTRUDER_MATERIAL_BRASS_NTC:
                return "Brass (NTC)";
            case Extruder.EXTRUDER_MATERIAL_BRASS_PT100:
                return "Brass (PT100)";
            case Extruder.EXTRUDER_MATERIAL_HARDENED_STEEL_PT100:
                return "Hardened steel (PT100)";
            default:
                return "Unknown";
        }
    }

    private String emergencyStopName(ErrorController.EmergencyStopState state) {
        if (state == null) return "Unknown";
        switch (state) {
            case EMERGENCY_STOP_STATE_PRESS:
                return "Pressed";
            case EMERGENCY_STOP_STATE_RELEASE:
                return "Released; reset required";
            case EMERGENCY_STOP_STATE_NORMAL:
            default:
                return "Normal";
        }
    }

    static String fanName(int headType, int id) {
        if (headType == Module.ModuleType.HEAD_3DP_DOUBLE_EXTRUDER) {
            if (id == 0) return "Left Part-cooling Fan";
            if (id == 1) return "Right Part-cooling Fan";
            if (id == 2) return "Heat Dissipation Fan";
        } else if (headType == Module.ModuleType.HEAD_3DP) {
            if (id == 0) return "Part-cooling Fan";
            if (id == 1) return "Heat Dissipation Fan";
        }
        return "Fan " + (id + 1);
    }

    private String legacyFanName(int id) {
        if (id == 0) return "Part cooling";
        if (id == 1) return "Right cooling";
        if (id == 2) return "Heat sink";
        return "Fan " + (id + 1);
    }

    static String fanSeriesKey(int headType, int id) {
        if (headType == Module.ModuleType.HEAD_3DP_DOUBLE_EXTRUDER) {
            if (id == 0) return "partCoolingLeft";
            if (id == 1) return "partCoolingRight";
            if (id == 2) return "toolheadHeatDissipation";
        } else if (headType == Module.ModuleType.HEAD_3DP) {
            if (id == 0) return "partCooling";
            if (id == 1) return "toolheadHeatDissipation";
        }
        return "toolheadFan" + Math.max(0, id);
    }

    static String fanKind(int headType, int id) {
        if (headType == Module.ModuleType.HEAD_3DP_DOUBLE_EXTRUDER) {
            if (id == 0 || id == 1) return "partCooling";
            if (id == 2) return "heatDissipation";
        } else if (headType == Module.ModuleType.HEAD_3DP) {
            if (id == 0) return "partCooling";
            if (id == 1) return "heatDissipation";
        }
        return "other";
    }

    static String fanSide(int headType, int id) {
        if (headType == Module.ModuleType.HEAD_3DP_DOUBLE_EXTRUDER) {
            if (id == 0) return "left";
            if (id == 1) return "right";
        }
        return "";
    }

    private int clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private Object telemetryAgeJson(long updatedAt, long sampledAt) {
        if (updatedAt <= 0L) return JSONObject.NULL;
        return Math.max(0L, sampledAt - updatedAt);
    }

    private Extruder findExtruder(List<Extruder> extruders, int id) {
        for (Extruder extruder : extruders) {
            if (extruder != null && extruder.getId() == id) return extruder;
        }
        return null;
    }

    private boolean isExtruderTelemetryReady(Extruder extruder) {
        return extruder != null && extruder.getDiameter() > 0f;
    }

    private HeatedBed.ZoneInfo findBedZone(List<HeatedBed.ZoneInfo> zones, int id) {
        for (HeatedBed.ZoneInfo zone : zones) {
            if (zone != null && zone.getZoneIndex() == id) return zone;
        }
        return null;
    }

    private boolean isBedZoneTelemetryReady(HeatedBed.ZoneInfo zone) {
        return zone != null
                && (zone.getCurrentTemperature() > 0f || zone.getTargetTemperature() > 0);
    }

    private <T extends Module> T findModule(MachineInfo info, Class<T> type) {
        if (info == null || info.moduleList == null) return null;
        List<Module> modules = snapshotList(info.moduleList);
        for (Module module : modules) {
            if (type.isInstance(module)) return type.cast(module);
        }
        return null;
    }

    private <T> List<T> snapshotList(List<T> source) {
        if (source == null || source.isEmpty()) return Collections.emptyList();
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return new ArrayList<>(source);
            } catch (RuntimeException ignored) {
            }
        }
        return Collections.emptyList();
    }

    private Object optionalParserNumber(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value) || value < 0f) return JSONObject.NULL;
        return rounded(value, 2);
    }

    private Object optionalParserNumber(int value) {
        return value < 0 ? JSONObject.NULL : value;
    }

    private double rounded(float value, int places) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return 0d;
        double factor = Math.pow(10d, places);
        return Math.round(value * factor) / factor;
    }

    private void addNonEmpty(JSONArray array, String value) {
        if (!TextUtils.isEmpty(value) && !"null".equalsIgnoreCase(value)) array.put(value);
    }

    private String firstNonEmpty(String first, String second) {
        return !TextUtils.isEmpty(first) ? first : emptyIfNull(second);
    }

    private String emptyIfNull(String value) {
        return value == null ? "" : value;
    }

    private String loadDashboardAsset() throws IOException {
        IAppService appService = ServiceContainer.getInstance().getService(IAppService.class);
        StringBuilder html = new StringBuilder();
        try (InputStream input = appService.getAppContext().getAssets().open("artisan_dashboard.html");
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(input, StandardCharsets.UTF_8)
             )) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                html.append(buffer, 0, read);
            }
        }
        return html.toString().replace("__ARTISAN_DASHBOARD_TOKEN__", mDashboardToken);
    }

    private boolean isDashboardRequestAuthorized(HttpRequest request) {
        if (!mDashboardToken.equals(request.getHeader(DASHBOARD_REQUEST_HEADER))) return false;

        String fetchSite = request.getHeader("Sec-Fetch-Site");
        if (!TextUtils.isEmpty(fetchSite) && !"same-origin".equalsIgnoreCase(fetchSite)) {
            return false;
        }

        String host = request.getHeader("Host");
        if (TextUtils.isEmpty(host)) return false;
        String hostName = host;
        int portSeparator = hostName.lastIndexOf(':');
        if (portSeparator > 0) hostName = hostName.substring(0, portSeparator);
        String localAddress = getIPAddress();
        if (!hostName.equals(localAddress)
                && !hostName.equals("127.0.0.1")
                && !hostName.equalsIgnoreCase("localhost")) {
            return false;
        }

        String origin = request.getHeader("Origin");
        return TextUtils.isEmpty(origin) || origin.equalsIgnoreCase("http://" + host);
    }

    private void writeJson(HttpResponse response, int status, JSONObject json) {
        response.setStatus(status);
        response.setBody(new JsonBody(json));
    }

    private void writeJsonError(HttpResponse response, int status, String message) {
        JSONObject error = new JSONObject();
        try {
            error.put("ok", false);
            error.put("error", message);
        } catch (JSONException ignored) {
        }
        writeJson(response, status, error);
    }

    public Observable<Boolean> startPrint(File file) {
        SingleSubject<Boolean> resultSubject = SingleSubject.create();
        if (!MachineOperationStatus.SYSTEM_STATUS_IDLE.valueEquals(ServiceContainer.getInstance().getService(IMachine.class).getNewPrintController().getPrintState())) {
            // Return result.
            return Observable.just(false);
        } else {
            String filename = file.getName();
            if (!file.exists() || filename.isEmpty()) {
                return Observable.just(false);
            } else {
                IGcodeParser mParser = ServiceContainer.getInstance().getService(IGcodeParser.class);
                IAppService appService = ServiceContainer.getInstance().getService(IAppService.class);
                mParser.startParse(appService.getFilesDir() + "/" + file.getName(), true, ServiceContainer.getInstance().getService(IMachine.class).getMachineInfoSubjectHolder().getValue().workType);
                Disposable ParserSub = mParser.getParseProgressObservable()
                        .throttleLast(100, TimeUnit.MILLISECONDS)
                        .distinctUntilChanged()
                        .takeUntil(progress -> progress == 100)
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(progress -> {
                            if (progress == -1) {
                                resultSubject.onSuccess(false);
                            } else if (progress == 100) {
                                IPrintWorkspace workspace = ServiceContainer.getInstance().getService(IPrintWorkspace.class);
                                workspace.setPrintMode(mParser.getCustomPrintMode());
                                workspace.setPrintSource(0);
                                workspace.setFileTotalLineCount(mParser.getTotalLinesCount());
                                workspace.setEstimatedTime(mParser.getEstimatedTime());
                                workspace.setFileMD5Value(Md5Util.fileToMD5(file));
                                NewPrintController printController = ServiceContainer.getInstance().getService(IMachine.class).getNewPrintController();
                                IFile file2 = new FabLocalFile(file);
                                Disposable sub = workspace.addFileToWorkspace(file2)
                                        .observeOn(AndroidSchedulers.mainThread())
                                        .subscribe(success -> {
                                            if (success) {
                                                printController.setStartFromRemoteFlag(true);
                                                ServiceContainer.getInstance().getService(IRouter.class).routeToPrintPage().start(appService.getNowViewContext());
                                            } else {
                                                printController.setStartFromRemoteFlag(false);
                                                resultSubject.onSuccess(false);
                                            }
                                        }, e -> {
                                            LogHelper.log(e);
                                            printController.setStartFromRemoteFlag(false);
                                            resultSubject.onSuccess(false);
                                        });
                                mDisposable.add(sub);
                                sub = ServiceContainer.getInstance().getService(IMachine.class).getNewPrintController().getPrintEventObservable()
                                        .observeOn(AndroidSchedulers.mainThread())
                                        .subscribe(printEvent -> {
                                            if (printEvent.getPrintEventState() == PrintEventState.STATE_SUCCESS) {
                                                resultSubject.onSuccess(true);
                                            } else if (printEvent.getPrintEventState() == PrintEventState.START_FAIL) {
                                                resultSubject.onSuccess(false);
                                            }
                                        });
                                mDisposable.add(sub);
                            }
                        }, e -> {
                            Logger.e(e.toString());
                            resultSubject.onSuccess(false);
                        });
                mDisposable.add(ParserSub);
            }
        }
        return resultSubject.toObservable();
    }

    private static final class DashboardFileException extends Exception {
        final int status;

        DashboardFileException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private static final class ResolvedDashboardFile {
        final IFile file;
        final DashboardFileInfo info;
        final String fingerprint;

        ResolvedDashboardFile(IFile file, DashboardFileInfo info, String fingerprint) {
            this.file = file;
            this.info = info;
            this.fingerprint = fingerprint;
        }
    }

    private static final class DigestPair {
        final String sha256;
        final String md5;

        DigestPair(String sha256, String md5) {
            this.sha256 = sha256;
            this.md5 = md5;
        }
    }

    private static final class PreparedDashboardPrintFile {
        final IFile file;
        final String sha256;
        final String md5;
        final File stagedFile;

        PreparedDashboardPrintFile(
                IFile file,
                String sha256,
                String md5,
                File stagedFile
        ) {
            this.file = file;
            this.sha256 = sha256;
            this.md5 = md5;
            this.stagedFile = stagedFile;
        }
    }

    private static JSONArray issuesToJson(List<DashboardCompatibilityIssue> issues)
            throws JSONException {
        JSONArray json = new JSONArray();
        for (DashboardCompatibilityIssue issue : issues) json.put(issue.toJson());
        return json;
    }

    private static JSONObject dashboardBedModeOptionJson(
            String id,
            int controllerMode,
            String label
    ) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("controllerMode", controllerMode);
        json.put("label", label);
        return json;
    }

    private static JSONObject dashboardBedModePreviewJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("defaultId", DashboardPrintStartOptions.BED_MODE_WHOLE);
        json.put("willBeSetBeforeStart", true);
        json.put("acknowledgementRequired", false);
        JSONArray options = new JSONArray();
        options.put(dashboardBedModeOptionJson(
                DashboardPrintStartOptions.BED_MODE_INNER,
                0,
                "Inner heated bed"
        ));
        options.put(dashboardBedModeOptionJson(
                DashboardPrintStartOptions.BED_MODE_WHOLE,
                1,
                "Whole heated bed"
        ));
        json.put("options", options);
        return json;
    }

    private static JSONObject dashboardSelectedBedModeJson(
            String id,
            int controllerMode,
            boolean confirmed
    ) throws JSONException {
        JSONObject json = dashboardBedModeOptionJson(
                id,
                controllerMode,
                DashboardPrintStartOptions.BED_MODE_WHOLE.equals(id)
                        ? "Whole heated bed" : "Inner heated bed"
        );
        json.put("selected", true);
        // Kept as an additive compatibility field; this records receipt of a selection,
        // not a required acknowledgement checkbox.
        json.put("acknowledged", true);
        json.put("confirmed", confirmed);
        return json;
    }

    private static final class DashboardCompatibilityIssue {
        final String id;
        final String text;
        final String severity;
        final boolean acknowledgementRequired;

        private DashboardCompatibilityIssue(
                String id,
                String text,
                String severity,
                boolean acknowledgementRequired
        ) {
            this.id = id;
            this.text = text;
            this.severity = severity;
            this.acknowledgementRequired = acknowledgementRequired;
        }

        static DashboardCompatibilityIssue warning(String id, String text) {
            return new DashboardCompatibilityIssue(id, text, "warning", false);
        }

        static DashboardCompatibilityIssue error(String id, String text) {
            return new DashboardCompatibilityIssue(id, text, "error", false);
        }

        JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("id", id);
            json.put("text", text);
            json.put("severity", severity);
            json.put("acknowledgementRequired", acknowledgementRequired);
            return json;
        }
    }

    static final class DashboardNozzleDiameterMismatch {
        final float fileLeftMm;
        final float fileRightMm;
        final float machineLeftMm;
        final float machineRightMm;
        final boolean leftMismatch;
        final boolean rightMismatch;
        final boolean detected;

        private DashboardNozzleDiameterMismatch(
                float fileLeftMm,
                float fileRightMm,
                float machineLeftMm,
                float machineRightMm
        ) {
            this.fileLeftMm = normalizedDiameter(fileLeftMm);
            this.fileRightMm = normalizedDiameter(fileRightMm);
            this.machineLeftMm = normalizedDiameter(machineLeftMm);
            this.machineRightMm = normalizedDiameter(machineRightMm);
            leftMismatch = diametersMismatch(this.fileLeftMm, this.machineLeftMm);
            rightMismatch = diametersMismatch(this.fileRightMm, this.machineRightMm);
            detected = leftMismatch || rightMismatch;
        }

        static DashboardNozzleDiameterMismatch create(
                float fileLeftMm,
                float fileRightMm,
                float machineLeftMm,
                float machineRightMm
        ) {
            return new DashboardNozzleDiameterMismatch(
                    fileLeftMm,
                    fileRightMm,
                    machineLeftMm,
                    machineRightMm
            );
        }

        JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("detected", detected);
            json.put("warningId", NOZZLE_DIAMETER_MISMATCH_ID);
            json.put("title", "Inconsistent Nozzle Diameter");
            json.put("message", "The nozzle diameter defined by the G-code file is "
                    + "inconsistent with that of the machine, which may cause problems. "
                    + "Would you like to continue?");

            JSONObject file = new JSONObject();
            putDiameter(file, "leftMm", fileLeftMm);
            putDiameter(file, "rightMm", fileRightMm);
            json.put("file", file);

            JSONObject machine = new JSONObject();
            putDiameter(machine, "leftMm", machineLeftMm);
            putDiameter(machine, "rightMm", machineRightMm);
            json.put("machine", machine);

            JSONArray sides = new JSONArray();
            if (leftMismatch) sides.put("left");
            if (rightMismatch) sides.put("right");
            json.put("mismatchedSides", sides);
            return json;
        }

        private static float normalizedDiameter(float value) {
            return value > 0f && !Float.isNaN(value) && !Float.isInfinite(value)
                    ? value : -1f;
        }

        private static boolean diametersMismatch(float required, float installed) {
            return required > 0f && installed > 0f
                    && Math.abs(required - installed) > 0.001f;
        }

        private static void putDiameter(JSONObject json, String key, float value)
                throws JSONException {
            json.put(key, value > 0f ? value : JSONObject.NULL);
        }
    }

    private static final class DashboardFileDetailState {
        final long requestId;
        final DashboardFileInfo file;
        final String fingerprint;
        boolean pending;
        int progress;
        String sha256;
        DashboardFileMetadata metadata;
        byte[] thumbnailPng;
        String error;
        boolean consumed;
        boolean metadataReady;
        boolean safetyReady;
        List<DashboardCompatibilityIssue> warnings = Collections.emptyList();
        List<DashboardCompatibilityIssue> blockingIssues = Collections.emptyList();
        DashboardNozzleDiameterMismatch nozzleDiameterMismatch =
                DashboardNozzleDiameterMismatch.create(-1f, -1f, -1f, -1f);

        private DashboardFileDetailState(
                long requestId,
                DashboardFileInfo file,
                String fingerprint
        ) {
            this.requestId = requestId;
            this.file = file;
            this.fingerprint = fingerprint;
            pending = true;
            progress = 0;
            sha256 = "";
            error = "";
            metadataReady = false;
            safetyReady = false;
        }

        static DashboardFileDetailState parsing(
                long requestId,
                ResolvedDashboardFile resolved
        ) {
            return new DashboardFileDetailState(
                    requestId,
                    resolved.info,
                    resolved.fingerprint
            );
        }

        static DashboardFileDetailState verifyingCacheHint(
                long requestId,
                ResolvedDashboardFile resolved,
                DashboardCachedFileAnalysis cached
        ) {
            DashboardFileDetailState result = new DashboardFileDetailState(
                    requestId,
                    resolved.info,
                    resolved.fingerprint
            );
            result.pending = true;
            result.progress = 0;
            result.sha256 = "";
            result.metadata = cached.metadata;
            result.thumbnailPng = cached.thumbnailPng();
            result.metadataReady = true;
            result.safetyReady = false;
            return result;
        }

        DashboardFileDetailState snapshot() {
            DashboardFileDetailState result = new DashboardFileDetailState(
                    requestId,
                    file,
                    fingerprint
            );
            result.pending = pending;
            result.progress = progress;
            result.sha256 = sha256;
            result.metadata = metadata;
            result.thumbnailPng = thumbnailPng;
            result.error = error;
            result.consumed = consumed;
            result.metadataReady = metadataReady;
            result.safetyReady = safetyReady;
            result.warnings = new ArrayList<>(warnings);
            result.blockingIssues = new ArrayList<>(blockingIssues);
            result.nozzleDiameterMismatch = nozzleDiameterMismatch;
            return result;
        }

        JSONObject toJson() throws JSONException {
            JSONObject result = new JSONObject();
            boolean succeeded = !pending && error.isEmpty() && metadata != null && safetyReady;
            result.put("ok", pending || succeeded);
            result.put("state", pending
                    ? metadataReady ? "analyzing" : "parsing"
                    : succeeded ? "ready" : "failed");
            result.put("requestId", requestId);
            result.put("progress", progress);
            result.put("file", file.toJson());
            result.put("error", error);
            result.put("metadata", metadataReady && metadata != null
                    ? metadata.toJson() : JSONObject.NULL);
            result.put("revision", succeeded ? metadata.sha256 : JSONObject.NULL);
            JSONObject analysis = new JSONObject();
            analysis.put("schema", DashboardFileAnalysisCache.CURRENT_SCHEMA);
            analysis.put("metadataReady", metadataReady);
            analysis.put("safetyReady", safetyReady);
            analysis.put("progress", progress);
            result.put("analysis", analysis);
            JSONObject compatibility = new JSONObject();
            compatibility.put("startAllowed", succeeded && blockingIssues.isEmpty());
            compatibility.put("blockingIssues", issuesToJson(blockingIssues));
            compatibility.put("warnings", issuesToJson(warnings));
            result.put("compatibility", compatibility);
            result.put("bedMode", dashboardBedModePreviewJson());
            result.put("nozzleDiameterMismatch", nozzleDiameterMismatch.toJson());
            JSONObject confirmation = new JSONObject();
            confirmation.put("oneUse", true);
            confirmation.put("available", succeeded && !consumed);
            confirmation.put("consumed", consumed);
            confirmation.put("reusableUntilControllerStart", true);
            confirmation.put("previewRequestId", requestId);
            confirmation.put("nozzleDiameterMismatchRequired",
                    nozzleDiameterMismatch.detected);
            result.put("confirmation", confirmation);
            boolean thumbnailAvailable = metadataReady
                    && thumbnailPng != null
                    && thumbnailPng.length > 0;
            result.put("thumbnailAvailable", thumbnailAvailable);
            result.put("thumbnailUrl", thumbnailAvailable
                    ? URI_DASHBOARD_FILES_THUMBNAIL + "?requestId=" + requestId
                    : JSONObject.NULL);
            return result;
        }
    }

    private static final class DashboardCachedFileAnalysis {
        final DashboardFileMetadata metadata;
        private final byte[] thumbnailPng;

        DashboardCachedFileAnalysis(DashboardFileMetadata metadata, byte[] thumbnailPng) {
            this.metadata = metadata;
            this.thumbnailPng = thumbnailPng == null ? null : thumbnailPng.clone();
        }

        byte[] thumbnailPng() {
            return thumbnailPng == null ? null : thumbnailPng.clone();
        }
    }

    private static final class DashboardFileStartState {
        final long requestId;
        final long previewRequestId;
        final DashboardFileInfo file;
        boolean pending;
        String state;
        String result;
        String error;
        int controllerErrorCode;
        long completedAt;
        File stagedFile;
        final String bedModeId;
        final int controllerBedMode;
        final DashboardNozzleDiameterMismatch nozzleDiameterMismatch;
        final boolean nozzleDiameterMismatchConfirmed;
        final long preparationLease;
        boolean bedModeConfirmed;
        String bedModeResult;
        int bedModeControllerErrorCode;
        boolean controllerStartIssued;

        private DashboardFileStartState(
                long requestId,
                long previewRequestId,
                DashboardFileInfo file,
                DashboardPrintStartOptions startOptions,
                DashboardNozzleDiameterMismatch nozzleDiameterMismatch,
                long preparationLease
        ) {
            this.requestId = requestId;
            this.previewRequestId = previewRequestId;
            this.file = file;
            bedModeId = startOptions.bedModeId;
            controllerBedMode = startOptions.controllerBedMode;
            this.nozzleDiameterMismatch = nozzleDiameterMismatch;
            nozzleDiameterMismatchConfirmed =
                    startOptions.nozzleDiameterMismatchConfirmed;
            this.preparationLease = preparationLease;
            pending = true;
            state = "preparing";
            result = "pending";
            error = "";
            bedModeResult = "pending";
            bedModeControllerErrorCode = 0;
        }

        static DashboardFileStartState pending(
                long requestId,
                long previewRequestId,
                DashboardFileInfo file,
                DashboardPrintStartOptions startOptions,
                DashboardNozzleDiameterMismatch nozzleDiameterMismatch,
                long preparationLease
        ) {
            return new DashboardFileStartState(
                    requestId,
                    previewRequestId,
                    file,
                    startOptions,
                    nozzleDiameterMismatch,
                    preparationLease
            );
        }

        DashboardFileStartState snapshot() {
            DashboardPrintStartOptions startOptions = DashboardPrintStartOptions.parse(
                    bedModeId,
                    nozzleDiameterMismatchConfirmed ? "true" : "false",
                    nozzleDiameterMismatch.detected
            );
            DashboardFileStartState copy = new DashboardFileStartState(
                    requestId,
                    previewRequestId,
                    file,
                    startOptions,
                    nozzleDiameterMismatch,
                    preparationLease
            );
            copy.pending = pending;
            copy.state = state;
            copy.result = result;
            copy.error = error;
            copy.controllerErrorCode = controllerErrorCode;
            copy.completedAt = completedAt;
            copy.stagedFile = stagedFile;
            copy.bedModeConfirmed = bedModeConfirmed;
            copy.bedModeResult = bedModeResult;
            copy.bedModeControllerErrorCode = bedModeControllerErrorCode;
            copy.controllerStartIssued = controllerStartIssued;
            return copy;
        }

        JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("ok", true);
            json.put("requestId", requestId);
            json.put("previewRequestId", previewRequestId);
            json.put("pending", pending);
            json.put("state", state);
            json.put("result", result);
            json.put("error", error);
            json.put("controllerErrorCode", controllerErrorCode);
            json.put("completedAt", completedAt);
            json.put("controllerStartIssued", controllerStartIssued);
            boolean outcomeUncertain = "controller_result_lost".equals(result);
            json.put("outcomeUncertain", outcomeUncertain);
            json.put("retryable", !pending
                    && !controllerStartIssued
                    && !outcomeUncertain
                    && !"succeeded".equals(result)
                    && !"file_changed".equals(result)
                    && !"analysis_cache_lost".equals(result));
            json.put("file", file.toJson());
            JSONObject bedMode = dashboardSelectedBedModeJson(
                    bedModeId,
                    controllerBedMode,
                    bedModeConfirmed
            );
            bedMode.put("result", bedModeResult);
            bedMode.put("controllerErrorCode", bedModeControllerErrorCode);
            json.put("bedMode", bedMode);
            JSONObject mismatch = nozzleDiameterMismatch.toJson();
            mismatch.put("required", nozzleDiameterMismatch.detected);
            mismatch.put("confirmed", nozzleDiameterMismatchConfirmed);
            json.put("nozzleDiameterMismatch", mismatch);
            return json;
        }
    }

    private static final class DashboardFileMetadata {
        final IMachine.WorkType fileType;
        final int totalLines;
        final float estimatedTimeSeconds;
        final int layerCount;
        final float layerHeightMm;
        final float materialLengthMeters;
        final float materialWeightGrams;
        final float bedTargetC;
        final float nozzleTargetLeftC;
        final float nozzleTargetRightC;
        final float nozzleDiameterLeftMm;
        final float nozzleDiameterRightMm;
        final String materialLeft;
        final String materialRight;
        final float workSizeXmm;
        final float workSizeYmm;
        final int toolheadType;
        final int printMode;
        final boolean applyMultiExtruder;
        final boolean toolUsageConfirmed;
        final boolean usesLeft;
        final boolean usesRight;
        final float extruder0RetractionMm;
        final float extruder1RetractionMm;
        final ModelBoundary boundary;
        final String sha256;
        final String md5;

        private DashboardFileMetadata(
                IGcodeParser parser,
                String sha256,
                String md5,
                boolean safetyAnalyzed
        ) {
            fileType = parser.getFileType();
            totalLines = safetyAnalyzed ? Math.max(0, parser.getTotalLinesCount()) : 0;
            estimatedTimeSeconds = parser.getEstimatedTime();
            layerCount = parser.getLayerNumber();
            layerHeightMm = parser.getLayerHeight();
            materialLengthMeters = parser.getMaterialLength();
            materialWeightGrams = parser.getMaterialWeight();
            bedTargetC = parser.getBedTargetTemperature();
            nozzleTargetLeftC = parser.getNozzleTargetTemperature();
            nozzleTargetRightC = parser.getNozzleTarget_1_Temperature();
            nozzleDiameterLeftMm = parser.getNozzle_0_Diameter();
            nozzleDiameterRightMm = parser.getNozzle_1_Diameter();
            materialLeft = parser.getMaterial_0();
            materialRight = parser.getMaterial_1();
            boundary = safetyAnalyzed
                    ? copyBoundary(parser.getBoundary()) : new ModelBoundary();
            float parsedWorkSizeX = parser.getWorkSizeX();
            float parsedWorkSizeY = parser.getWorkSizeY();
            workSizeXmm = parsedWorkSizeX > 0f
                    ? parsedWorkSizeX
                    : boundaryRange(boundary.getMinX(), boundary.getMaxX());
            workSizeYmm = parsedWorkSizeY > 0f
                    ? parsedWorkSizeY
                    : boundaryRange(boundary.getMinY(), boundary.getMaxY());
            toolheadType = parser.getHeaderType();
            printMode = parser.getCustomPrintMode();
            applyMultiExtruder = safetyAnalyzed && parser.isApplyMultiExtruder();
            toolUsageConfirmed = safetyAnalyzed && parser.isToolUsageConfirmed();
            usesLeft = safetyAnalyzed && parser.isTool0Used();
            usesRight = safetyAnalyzed && parser.isTool1Used();
            extruder0RetractionMm = safetyAnalyzed
                    ? parser.getExtruder0RetractionDistance() : -1f;
            extruder1RetractionMm = safetyAnalyzed
                    ? parser.getExtruder1RetractionDistance() : -1f;
            this.sha256 = sha256;
            this.md5 = md5;
        }

        static DashboardFileMetadata fromParser(
                IGcodeParser parser,
                String sha256,
                String md5
        ) {
            return new DashboardFileMetadata(parser, sha256, md5, true);
        }

        static DashboardFileMetadata previewFromParser(IGcodeParser parser) {
            return new DashboardFileMetadata(parser, "", "", false);
        }

        ModelBoundary copyBoundary() {
            return copyBoundary(boundary);
        }

        boolean requiresLeftNozzle() {
            // Unknown usage must retain the established fail-closed behavior.
            return !toolUsageConfirmed || usesLeft;
        }

        boolean requiresRightNozzle() {
            return !toolUsageConfirmed || usesRight;
        }

        boolean hasKnownBoundary() {
            return isFiniteBoundaryValue(boundary.getMinX())
                    && isFiniteBoundaryValue(boundary.getMaxX())
                    && isFiniteBoundaryValue(boundary.getMinY())
                    && isFiniteBoundaryValue(boundary.getMaxY())
                    && isFiniteBoundaryValue(boundary.getMinZ())
                    && isFiniteBoundaryValue(boundary.getMaxZ())
                    && boundary.getMinX() <= boundary.getMaxX()
                    && boundary.getMinY() <= boundary.getMaxY()
                    && boundary.getMinZ() <= boundary.getMaxZ();
        }

        boolean safetyEquivalentTo(DashboardFileMetadata other) {
            if (other == null
                    || fileType != other.fileType
                    || toolheadType != other.toolheadType
                    || printMode != other.printMode
                    || applyMultiExtruder != other.applyMultiExtruder
                    || toolUsageConfirmed != other.toolUsageConfirmed
                    || usesLeft != other.usesLeft
                    || usesRight != other.usesRight
                    || Float.compare(nozzleDiameterLeftMm, other.nozzleDiameterLeftMm) != 0
                    || Float.compare(nozzleDiameterRightMm, other.nozzleDiameterRightMm) != 0
                    || Float.compare(extruder0RetractionMm, other.extruder0RetractionMm) != 0
                    || Float.compare(extruder1RetractionMm, other.extruder1RetractionMm) != 0) {
                return false;
            }
            return Float.compare(boundary.getMinX(), other.boundary.getMinX()) == 0
                    && Float.compare(boundary.getMaxX(), other.boundary.getMaxX()) == 0
                    && Float.compare(boundary.getMinY(), other.boundary.getMinY()) == 0
                    && Float.compare(boundary.getMaxY(), other.boundary.getMaxY()) == 0
                    && Float.compare(boundary.getMinZ(), other.boundary.getMinZ()) == 0
                    && Float.compare(boundary.getMaxZ(), other.boundary.getMaxZ()) == 0;
        }

        JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("fileType", fileType == null ? "NONE" : fileType.name());
            putPositiveInteger(json, "totalLines", totalLines);
            putPositive(json, "estimatedTimeSeconds", estimatedTimeSeconds);
            putPositiveInteger(json, "layerCount", layerCount);
            putPositive(json, "layerHeightMm", layerHeightMm);
            putNonNegative(json, "materialLengthMeters", materialLengthMeters);
            putNonNegative(json, "materialWeightGrams", materialWeightGrams);
            putPositive(json, "bedTargetC", bedTargetC);
            putPositive(json, "nozzleTargetLeftC", nozzleTargetLeftC);
            putPositive(json, "nozzleTargetRightC", nozzleTargetRightC);
            putPositive(json, "nozzleDiameterLeftMm", nozzleDiameterLeftMm);
            putPositive(json, "nozzleDiameterRightMm", nozzleDiameterRightMm);
            json.put("materialLeft", TextUtils.isEmpty(materialLeft)
                    ? JSONObject.NULL : materialLeft);
            json.put("materialRight", TextUtils.isEmpty(materialRight)
                    ? JSONObject.NULL : materialRight);
            putPositive(json, "workSizeXmm", workSizeXmm);
            putPositive(json, "workSizeYmm", workSizeYmm);
            json.put("toolheadType", toolheadType >= 0 ? toolheadType : JSONObject.NULL);
            json.put("printMode", printMode);
            json.put("applyMultiExtruder", applyMultiExtruder);
            json.put("toolUsageConfirmed", toolUsageConfirmed);
            json.put("usesLeft", usesLeft);
            json.put("usesRight", usesRight);
            putNonNegative(json, "extruder0RetractionMm", extruder0RetractionMm);
            putNonNegative(json, "extruder1RetractionMm", extruder1RetractionMm);
            return json;
        }

        private static ModelBoundary copyBoundary(ModelBoundary source) {
            ModelBoundary copy = new ModelBoundary();
            if (source == null) return copy;
            copy.setDimension(source.getDimension());
            copy.setMinX(source.getMinX());
            copy.setMaxX(source.getMaxX());
            copy.setMinY(source.getMinY());
            copy.setMaxY(source.getMaxY());
            copy.setMinZ(source.getMinZ());
            copy.setMaxZ(source.getMaxZ());
            copy.setMinB(source.getMinB());
            copy.setMaxB(source.getMaxB());
            return copy;
        }

        private static float boundaryRange(float minimum, float maximum) {
            if (minimum == Float.MAX_VALUE
                    || maximum == -Float.MAX_VALUE
                    || Float.isNaN(minimum)
                    || Float.isNaN(maximum)
                    || Float.isInfinite(minimum)
                    || Float.isInfinite(maximum)) return -1f;
            float range = maximum - minimum;
            return range > 0f ? range : -1f;
        }

        private static boolean isFiniteBoundaryValue(float value) {
            return value != Float.MAX_VALUE
                    && value != -Float.MAX_VALUE
                    && !Float.isNaN(value)
                    && !Float.isInfinite(value);
        }

        private static void putPositive(JSONObject json, String key, float value)
                throws JSONException {
            json.put(key, value > 0f && !Float.isNaN(value) && !Float.isInfinite(value)
                    ? value : JSONObject.NULL);
        }

        private static void putNonNegative(JSONObject json, String key, float value)
                throws JSONException {
            json.put(key, value >= 0f && !Float.isNaN(value) && !Float.isInfinite(value)
                    ? value : JSONObject.NULL);
        }

        private static void putPositiveInteger(JSONObject json, String key, int value)
                throws JSONException {
            json.put(key, value > 0 ? value : JSONObject.NULL);
        }
    }

    private static final class DashboardFileScanBudget {
        final long deadlineElapsed = SystemClock.elapsedRealtime() + FILE_SCAN_TIMEOUT_MS;
        int visited;
        boolean truncated;

        boolean hasTime() {
            if (SystemClock.elapsedRealtime() <= deadlineElapsed) return true;
            truncated = true;
            return false;
        }

        boolean visit() {
            if (!hasTime() || visited >= MAX_VISITED_FILE_ENTRIES) {
                truncated = true;
                return false;
            }
            visited++;
            return true;
        }
    }

    private static final class DashboardFileInfo {
        final String source;
        final String name;
        final String relativePath;
        final long sizeBytes;
        final long modifiedAt;

        DashboardFileInfo(
                String source,
                String name,
                String relativePath,
                long sizeBytes,
                long modifiedAt
        ) {
            this.source = source;
            this.name = name;
            this.relativePath = relativePath;
            this.sizeBytes = sizeBytes;
            this.modifiedAt = modifiedAt;
        }

        JSONObject toJson() throws JSONException {
            JSONObject result = new JSONObject();
            result.put("source", source);
            result.put("name", name);
            result.put("relativePath", relativePath);
            result.put("sizeBytes", sizeBytes);
            result.put("modifiedAt", modifiedAt);
            return result;
        }
    }

    private static final class DashboardConsoleEntry {
        final long id;
        final String command;
        final String response;
        final boolean success;
        final long sentAt;
        final long durationMs;

        DashboardConsoleEntry(
                long id,
                String command,
                String response,
                boolean success,
                long sentAt,
                long durationMs
        ) {
            this.id = id;
            this.command = command;
            this.response = response;
            this.success = success;
            this.sentAt = sentAt;
            this.durationMs = durationMs;
        }

        DashboardConsoleEntry withId(long assignedId) {
            return new DashboardConsoleEntry(
                    assignedId,
                    command,
                    response,
                    success,
                    sentAt,
                    durationMs
            );
        }

        JSONObject toJson() throws JSONException {
            JSONObject result = new JSONObject();
            result.put("id", id);
            result.put("command", command);
            result.put("response", response);
            result.put("success", success);
            result.put("sentAt", sentAt);
            result.put("durationMs", durationMs);
            return result;
        }
    }

    private static final class DashboardConsoleResult {
        final boolean accepted;
        final int status;
        final String error;
        final DashboardConsoleEntry entry;

        private DashboardConsoleResult(
                boolean accepted,
                int status,
                String error,
                DashboardConsoleEntry entry
        ) {
            this.accepted = accepted;
            this.status = status;
            this.error = error;
            this.entry = entry;
        }

        static DashboardConsoleResult rejected(int status, String error) {
            return new DashboardConsoleResult(false, status, error, null);
        }

        static DashboardConsoleResult accepted(DashboardConsoleEntry entry) {
            return new DashboardConsoleResult(true, StatusCode.SC_OK, "", entry);
        }
    }

    private String getIPAddress() {
        // check ip address
        String addressString = "Not Connected";
        try {
            List<NetworkInterface> interfaceList = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface networkInterface : interfaceList) {
                List<InetAddress> addresses = Collections.list(networkInterface.getInetAddresses());
                for (InetAddress address : addresses) {
                    if (!address.isLoopbackAddress()) {
                        String sAddr = address.getHostAddress();
                        boolean isIPv4 = sAddr.indexOf(':') < 0;

                        if (isIPv4) {
                            addressString = sAddr;
                        }
                    }
                }
            }
        } catch (SocketException e) {
            LogHelper.log(e);
        }
        return addressString;
    }

    private String getMacAddr() {
        return ServiceContainer.getInstance().getService(INetwork.class).getMacAddress();
    }

    public String getCurrentVersion() {
        String versionFull = ServiceContainer.getInstance().getService(IPreferences.class).getHelper().getLastUpdatePackageVersion();
        String curVersion;
        if (TextUtils.isEmpty(versionFull)) {
            curVersion = ServiceContainer.getInstance().getService(IAppService.class).getApp().getAppVersionName();
        } else {
            curVersion = versionFull;
            String[] versionSplits = versionFull.split("_");
            if (versionSplits.length > 1) {
                curVersion = versionSplits[1];
            }
        }
        return curVersion;
    }
}
