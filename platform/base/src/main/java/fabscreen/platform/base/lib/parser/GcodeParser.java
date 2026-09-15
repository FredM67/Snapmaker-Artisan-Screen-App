package fabscreen.platform.base.lib.parser;

import static fabscreen.platform.base.service.IMachine.WorkType.CNC;
import static fabscreen.platform.base.service.IMachine.WorkType.FDM;
import static fabscreen.platform.base.service.IMachine.WorkType.LASER;
import static fabscreen.platform.base.service.IMachine.WorkType.NONE;
import static fabscreen.platform.base.service.machine.entity.Module.ModuleType.HEAD_3DP;
import static fabscreen.platform.base.service.machine.entity.Module.ModuleType.HEAD_3DP_DOUBLE_EXTRUDER;
import static fabscreen.platform.base.service.machine.entity.Module.ModuleType.HEAD_CNC;
import static fabscreen.platform.base.service.machine.entity.Module.ModuleType.HEAD_CNC_200W;
import static fabscreen.platform.base.service.machine.entity.Module.ModuleType.HEAD_LASER;
import static fabscreen.platform.base.service.machine.entity.Module.ModuleType.HEAD_LASER_10W;
import static fabscreen.platform.base.service.machine.entity.Module.ModuleType.HEAD_LASER_20W;
import static fabscreen.platform.base.service.machine.entity.Module.ModuleType.HEAD_LASER_2W_INFRARED;
import static fabscreen.platform.base.service.machine.entity.Module.ModuleType.HEAD_LASER_40W;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import androidx.annotation.NonNull;

import com.orhanobut.logger.Logger;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Locale;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.DigestInputStream;

import fabscreen.platform.base.R;
import fabscreen.platform.base.helper.StringHelper;
import fabscreen.platform.base.instantiation.IServiceIdentifier;
import fabscreen.platform.base.instantiation.ServiceContainer;
import fabscreen.platform.base.lib.file.IFile;
import fabscreen.platform.base.model.ModelBoundary;
import fabscreen.platform.base.service.IAppService;
import fabscreen.platform.base.service.IFileManagerService;
import fabscreen.platform.base.service.IMachine;
import fabscreen.platform.lib.LogHelper;
import io.reactivex.Observable;
import io.reactivex.Scheduler;
import io.reactivex.schedulers.Schedulers;
import io.reactivex.subjects.BehaviorSubject;
import okio.BufferedSource;
import okio.Okio;

public class GcodeParser implements IGcodeParser, IServiceIdentifier {
    private static final String BOUND_HEADER_START_MARK_CURA = ";START_OF_HEADER";
    private static final String BOUND_HEADER_END_MARK_CURA = ";END_OF_HEADER";
    private static final String BOUND_HEADER_START_MARK_SNAPMAKER = ";Header Start";
    private static final String BOUND_HEADER_END_MARK_SNAPMAKER = ";Header End";
    private static String TAG = "GcodeParser";
    // comment marker
    private static final String BOUNDS_START_MARK = ";Start GCode end";
    private static final String BOUNDS_END_MARK = ";End GCode begin";
    private static final String BOUNDS_END_MARK_V1 = ";--- End G-code Begin ---";
    private boolean mIsParsingHeader = false;
    private int mHeaderVersion = 0;

    // Reader
    private BufferedSource source;
    private ParseSession mActiveSession;
    private volatile int mParseGeneration;
    private String[] lineArgs = new String[20];
    private int lineArgCount;

    // G-code state
    private boolean mIsCurrentGcodeStateWorking = false;
    @NonNull
    private Position currentPosition;

    private boolean mShouldUpdateAttribute = true;
    private boolean isAbsoluteCoordinate = true;
    private boolean mObjectBoundaryStarted = false;
    private boolean mObjectBoundaryActive = false;

    private float mBedTargetTemperature = 0;
    private float mNozzleTarget_0_Temperature = 0;
    private float mNozzleTarget_1_Temperature = 0;

    private float mCurrentLineFeedRate = 0;
    private double mFeedRateAmount = 0;
    private int mFeedRateCount = 0;

    private float extrusionAmount = 0;

    private float mPower = 0;

    private float mSpindleSpeed = 0;

    private IMachine.WorkType mFileType = NONE;
    private int mParseProgress = 0;

    private float mEstimateTime = 0;
    private int mTotalLinesCount = 0;

    private float mWorkSpeed = 0;
    private float mJogSpeed = 0;

    private float mDiameter = 0;

    private int mCustomPrintMode = 0;
    private boolean mIsDefineT0 = false;
    private boolean mIsDefineT1 = false;
    private boolean mTool0Used = false;
    private boolean mTool1Used = false;
    private boolean mToolUsageConfirmed = false;

    private Bitmap mGcodeThumbnail;
    private byte[] mGcodeThumbnailBytes;
    private long mGcodeThumbnailArea = -1;
    private final OrcaThumbnailBlockParser mOrcaThumbnailParser = new OrcaThumbnailBlockParser();
    private final OrcaMetadataCommentParser mOrcaMetadataParser =
            new OrcaMetadataCommentParser();
    private ModelBoundary mModelBoundary;
    private ArrayList<Position> mToolPath = new ArrayList<>();

    private BehaviorSubject<Integer> mParseProgressSubject = BehaviorSubject.createDefault(0);
    private Scheduler.Worker mParseWorker;

    private int mToolHead = -1;
    private float mNozzle_0_Diameter = -1;
    private float mNozzle_1_Diameter = -1;
    private int mLayerNumber = -1;
    private float mLayerHeight = -1;
    private float mMaterialWeight = -1;
    private float mMaterialLength = -1;
    private String mNozzle_0_Material = null;
    private String mNozzle_1_Material = null;
    private String mRenderMethod = null;
    private int mIsRotate = -1;
    private float mWorkSizeX = -1;
    private float mWorkSizeY = -1;
    private String mOrigin = null;
    private String mMachine = null;
    private int mToolHeadNameID = -1;

    private int mCurrentExtruder = 0;
    private int mT0RetractionCount = 0;
    private int mT1RetractionCount = 0;
    private float mLastEAxisPosition = 0;
    private boolean mFDMRetractionParamAcquired = false;
    private float mExtruder0RetractionDistance = 0;
    private float mExtruder0SwitchRetractionDistance = 0;
    private float mExtruder1RetractionDistance = 0;
    private float mExtruder1SwitchRetractionDistance = 0;

    private final HeaderParamsChecker mHeaderChecker = new HeaderParamsChecker();

    enum ParseMode {
        LEGACY,
        ORCA_FAST,
        ORCA_FALLBACK
    }

    private volatile ParseMode mLastParseMode = ParseMode.LEGACY;
    private volatile long mLastParseDurationMs;
    private volatile int mLastExecutableSafetyLineCount;
    private volatile String mLastSha256ForAnalysis;
    private volatile String mLastMd5ForAnalysis;

    public GcodeParser() {
        mModelBoundary = new ModelBoundary();
        currentPosition = new Position(0, 0, 0);
    }

    @Override
    public void destroy() {
        synchronized (GcodeParser.this) {
            mParseGeneration++;
            closeSession(mActiveSession);
            mActiveSession = null;
            source = null;
            if (mParseWorker != null) {
                mParseWorker.dispose();
                mParseWorker = null;
            }
        }

        if (mToolPath != null) {
            mToolPath.clear();
        }
    }

    @Override
    public void startParse(String filePath, boolean isLocal, IMachine.WorkType fileType) {
        IFile search = ServiceContainer.getInstance().getService(IFileManagerService.class)
                .getDevice(isLocal)
                .search(filePath);

        startParse(search, fileType);
    }

    @Override
    public void startParse(IFile file, IMachine.WorkType fileType) {
        synchronized (GcodeParser.this) {
            final int generation = ++mParseGeneration;
            closeSession(mActiveSession);
            mActiveSession = null;
            source = null;
            mFileType = fileType;
            mLastSha256ForAnalysis = null;
            mLastMd5ForAnalysis = null;
            final long fileLength = file.length();
            final long fileLastModified = file.lastModified();
            final BufferedSource opened;
            final MessageDigest sha256;
            final MessageDigest md5;
            try {
                sha256 = newDigest("SHA-256");
                md5 = newDigest("MD5");
                opened = digestingSource(file.getInputStream(), sha256, md5);
            } catch (IOException | IllegalStateException e) {
                LogHelper.log(e);
                mParseProgressSubject.onNext(-1);
                return;
            }
            ParseSession session = new ParseSession(generation, opened, fileLength,
                    file, fileLastModified, null, false, false, sha256, md5);
            mActiveSession = session;
            source = opened;
            // Create parse worker if not exist
            if (mParseWorker == null) {
                mParseWorker = Schedulers.computation().createWorker();
            }

            mParseProgressSubject.onNext(0);
            // Start parse here in computation scheduler
            mParseWorker.schedule(() -> parse(session));
        }
    }

    @Override
    public void startParse(InputStream io, IMachine.WorkType fileType) {
        synchronized (GcodeParser.this) {
            final int generation = ++mParseGeneration;
            closeSession(mActiveSession);
            mActiveSession = null;
            source = null;
            mFileType = fileType;
            mLastSha256ForAnalysis = null;
            mLastMd5ForAnalysis = null;
            // ByteArrayInputStream resets without retaining another copy. FileInputStream can
            // seek its channel. Other mark implementations may buffer the entire large file,
            // so keep them on the safe legacy path instead.
            final boolean replayByMark = io instanceof ByteArrayInputStream;
            final boolean replayByFileChannel = io instanceof FileInputStream;
            final BufferedSource opened;
            final long streamLength;
            final MessageDigest sha256;
            final MessageDigest md5;
            try {
                if (replayByMark) io.mark(Integer.MAX_VALUE);
                streamLength = io.available();
                sha256 = newDigest("SHA-256");
                md5 = newDigest("MD5");
                opened = digestingSource(io, sha256, md5);
            } catch (IOException | IllegalStateException e) {
                LogHelper.log(e);
                mParseProgressSubject.onNext(-1);
                return;
            }
            ParseSession session = new ParseSession(generation, opened, streamLength,
                    null, 0L, io, replayByMark, replayByFileChannel, sha256, md5);
            mActiveSession = session;
            source = opened;
            // Create parse worker if not exist
            if (mParseWorker == null) {
                mParseWorker = Schedulers.computation().createWorker();
            }

            mParseProgressSubject.onNext(0);
            // Start parse here in computation scheduler
            mParseWorker.schedule(() -> parse(session));
        }
    }

    private void parse(ParseSession session) {
        try {
            parseOwned(session);
        } finally {
            closeSession(session);
            synchronized (GcodeParser.this) {
                if (mActiveSession == session) {
                    mActiveSession = null;
                    source = null;
                }
            }
        }
    }

    private void parseOwned(ParseSession session) {
        final long startedAtNanos = System.nanoTime();
        String line;
        int linesCount = 0;
        int newLineBytes = 1;
        boolean legacyToolUsageComprehensive = false;
        BufferedSource parseSource = session.currentSource;
        if (parseSource == null || isCancelled(session)) return;

        // Peek to count total lines
        try {
            BufferedSource peek = parseSource.peek();
            // https://github.com/square/okio/blob/master/okio/jvm/src/main/java/okio/Buffer.kt#L648
            // Check if the file uses "\n" or "\r\n", which will affect our byte calculation
            long carriage = peek.indexOf((byte) ('\r'), 0, 64);
            if (carriage != -1) {
                newLineBytes = 2;
            }
            peek.close();
        } catch (IOException e) {
            if (!isCancelled(session)) {
                e.printStackTrace();
                emitTerminal(session, -1);
            }
            return;
        }

        // Reset progress
        synchronized (GcodeParser.this) {
            if (isCancelled(session)) return;
            resetResult();
            mParseProgressSubject.onNext(0);
            mTotalLinesCount = 0;
            mIsParsingHeader = false;
            mHeaderChecker.reset();
            mLastParseMode = ParseMode.LEGACY;
            mLastExecutableSafetyLineCount = 0;
        }

        if (mFileType == FDM && canReplaySource(session) && looksLikeOrca(parseSource)) {
            OrcaFastPathScanner fastScanner = new OrcaFastPathScanner();
            boolean fastComplete = tryParseOrcaFast(
                    parseSource, fastScanner, newLineBytes, session);
            if (isCancelled(session)) return;
            if (fastComplete) {
                synchronized (GcodeParser.this) {
                    if (isCancelled(session)) return;
                    applyOrcaMetadata();
                    applyOrcaFastSafety(fastScanner);
                    checkPathClose();
                    mLastParseMode = ParseMode.ORCA_FAST;
                    mLastExecutableSafetyLineCount = fastScanner.getExecutableLineCount();
                    mLastParseDurationMs = elapsedMillis(startedAtNanos);
                    completeAnalysisDigests(session);
                    Logger.i("G-code parse mode=%s lines=%d executableSafetyLines=%d durationMs=%d",
                            mLastParseMode, mTotalLinesCount, mLastExecutableSafetyLineCount,
                            mLastParseDurationMs);
                    mParseProgressSubject.onNext(100);
                }
                return;
            }

            // Even when bounds/retraction/structure make the fast result untrusted, positive-E
            // physical tool use is monotonic evidence. The legacy tokenizer does not understand
            // every depositing primitive (notably G2/G3), so retain only these conservative OR
            // bits across reset; never carry untrusted geometry or retraction values.
            boolean fastObservedTool0 = fastScanner.isTool0Used();
            boolean fastObservedTool1 = fastScanner.isTool1Used();
            legacyToolUsageComprehensive = fastScanner.hasComprehensiveToolUsageResult();
            mLastParseMode = ParseMode.ORCA_FALLBACK;
            try {
                parseSource = reopenSourceForFallback(session, parseSource);
            } catch (IOException e) {
                if (!isCancelled(session)) {
                    LogHelper.log(e);
                    emitTerminal(session, -1);
                }
                return;
            }
            if (parseSource == null || isCancelled(session)) return;
            synchronized (GcodeParser.this) {
                if (isCancelled(session)) return;
                resetResult();
                mTool0Used = fastObservedTool0;
                mTool1Used = fastObservedTool1;
                mTotalLinesCount = 0;
                mIsParsingHeader = false;
                mHeaderChecker.reset();
                session.readBytes = 0;
                mParseProgress = 0;
                // Emit a new phase boundary so subscribers/watchdogs continue receiving
                // activity while the full legacy fallback scans from the beginning. UI/backend
                // progress remains monotonic by retaining its previously observed maximum.
                mParseProgressSubject.onNext(0);
            }
        }

        boolean reachedEndOfFile = false;
        while (true) {
            try {
                if (isCancelled(session)) return;
                line = parseSource.readUtf8Line();

                if (line == null) {
                    reachedEndOfFile = true;
                    break;
                }

                // stop parsing if header exists and get totalLinesCount
                if (checkParamsRequired() && !mIsParsingHeader) {
                    break;
                }

                // Update progress
                session.readBytes += line.length() + newLineBytes;
                // 100 is reserved for terminal success after metadata, safety and digests have
                // been finalized. Byte-based progress may otherwise unblock callers early.
                updateParseProgress(session, 99);

                // Fixme: Need to refactor format number for parsing args instead of throwing exception
                parseLine(line);
                linesCount++;
            } catch (Exception e) {
                if (isCancelled(session)) return;
                e.printStackTrace();
                LogHelper.log(e);
                emitTerminal(session, -1);
                return;
            }
        }
        boolean completeByteStreamRead = reachedEndOfFile;
        if (!reachedEndOfFile && !isCancelled(session)) {
            // Luban/header-based parsing intentionally stops semantic inspection early. Continue
            // consuming the owned source without tokenizing it so the analysis digest still
            // covers the exact complete file while preserving the established fast header path.
            byte[] drainBuffer = new byte[64 * 1024];
            try {
                int drained;
                while (!isCancelled(session)
                        && (drained = parseSource.read(drainBuffer)) != -1) {
                    if (drained > 0) {
                        session.readBytes += drained;
                        updateParseProgress(session, 99);
                    }
                }
                completeByteStreamRead = !isCancelled(session);
            } catch (IOException e) {
                if (!isCancelled(session)) LogHelper.log(e);
            }
        }
        try {
            if (parseSource != null) {
                parseSource.close();
            }
        } catch (IOException e) {
            LogHelper.log(e);
        }

        synchronized (GcodeParser.this) {
            if (isCancelled(session)) return;
            if (!mHeaderChecker.isTotalLinesCheck()) {
                mTotalLinesCount = (mTotalLinesCount == 0) ? linesCount : mTotalLinesCount;
            }
            applyOrcaMetadata();
            if (reachedEndOfFile && mFileType == FDM) {
                finalizeLegacyToolUsage(legacyToolUsageComprehensive);
            }
            if (completeByteStreamRead) {
                completeAnalysisDigests(session);
            }
            checkPathClose();

            mLastParseDurationMs = elapsedMillis(startedAtNanos);
            Logger.i("G-code parse mode=%s lines=%d durationMs=%d",
                    mLastParseMode, mTotalLinesCount, mLastParseDurationMs);
            mParseProgressSubject.onNext(100);
        }
    }

    private boolean tryParseOrcaFast(BufferedSource parseSource,
                                     OrcaFastPathScanner scanner,
                                     int newLineBytes,
                                     ParseSession session) {
        while (true) {
            if (isCancelled(session)) return false;
            final String fastLine;
            try {
                fastLine = parseSource.readUtf8Line();
            } catch (IOException e) {
                if (!isCancelled(session)) LogHelper.log(e);
                return false;
            }
            if (fastLine == null) break;

            OrcaFastPathScanner.Action action = scanner.consumeLine(fastLine);
            if (action == OrcaFastPathScanner.Action.INVALID) return false;
            if (action == OrcaFastPathScanner.Action.EXECUTABLE) {
                // Orca's compact Plate/T0/T1 declaration lives near the beginning of the
                // executable block. Collect comment metadata without invoking the general
                // command tokenizer for millions of motion lines.
                mOrcaMetadataParser.consumeLine(fastLine);
            }
            if (action == OrcaFastPathScanner.Action.DETAIL) {
                try {
                    parseLine(fastLine);
                } catch (RuntimeException e) {
                    LogHelper.log(e);
                    return false;
                }
            }

            session.readBytes += fastLine.length() + newLineBytes;
            updateParseProgress(session, 99);
        }

        applyOrcaMetadata();
        if (!scanner.hasTrustedSafetyResult() || !hasTrustedOrcaDetailMetadata(scanner)
                || !isReplaySourceStable(session)) {
            return false;
        }
        mTotalLinesCount = scanner.getPhysicalLineCount();
        return true;
    }

    private boolean hasTrustedOrcaDetailMetadata(OrcaFastPathScanner scanner) {
        OrcaMetadataCommentParser.Result metadata = mOrcaMetadataParser.getResult();
        if (metadata.plateToolUsageConflict
                || (metadata.plateTool0Used != null
                && metadata.plateTool0Used != scanner.isTool0ExplicitlyUsed())
                || (metadata.plateTool1Used != null
                && metadata.plateTool1Used != scanner.isTool1ExplicitlyUsed())) {
            // Plate flags are useful for an early preview, but the executable is authoritative.
            // A contradiction takes the established full-parser fallback rather than allowing
            // an unused declaration to suppress a physical-tool safety check.
            return false;
        }
        if (metadata.estimatedTimeSeconds == null || metadata.estimatedTimeSeconds <= 0f
                || metadata.layerCount == null || metadata.layerCount <= 0
                || metadata.layerHeightMm == null || metadata.layerHeightMm <= 0f
                || metadata.materialLengthMeters == null || metadata.materialLengthMeters < 0f
                || metadata.materialWeightGrams == null || metadata.materialWeightGrams < 0f
                || metadata.bedTemperatureC == null || metadata.bedTemperatureC < 0f
                || (!scanner.isTool0Used() && !scanner.isTool1Used())) {
            return false;
        }
        if (scanner.isTool0Used()
                && (metadata.nozzleDiameterLeftMm == null
                || metadata.nozzleDiameterLeftMm <= 0f
                || metadata.nozzleTemperatureLeftC == null
                || metadata.nozzleTemperatureLeftC < 0f
                || metadata.materialLeft == null
                || metadata.materialLeft.trim().isEmpty())) {
            return false;
        }
        if (scanner.isTool1Used()) {
            return metadata.nozzleDiameterRightMm != null
                    && metadata.nozzleDiameterRightMm > 0f
                    && metadata.nozzleTemperatureRightC != null
                    && metadata.nozzleTemperatureRightC >= 0f
                    && metadata.materialRight != null
                    && !metadata.materialRight.trim().isEmpty();
        }
        return true;
    }

    private void applyOrcaFastSafety(OrcaFastPathScanner scanner) {
        mModelBoundary.setMinX(scanner.getMinX());
        mModelBoundary.setMaxX(scanner.getMaxX());
        mModelBoundary.setMinY(scanner.getMinY());
        mModelBoundary.setMaxY(scanner.getMaxY());
        mModelBoundary.setMinZ(scanner.getMinZ());
        mModelBoundary.setMaxZ(scanner.getMaxZ());
        mHeaderChecker.setBoundaryCheck(true);

        mIsDefineT0 = scanner.isTool0Defined();
        mIsDefineT1 = scanner.isTool1Defined();
        mTool0Used = scanner.isTool0Used();
        mTool1Used = scanner.isTool1Used();
        mToolUsageConfirmed = true;
        mExtruder0RetractionDistance = scanner.getRetraction0();
        mExtruder1RetractionDistance = scanner.getRetraction1();
        mExtruder0SwitchRetractionDistance = scanner.isTool0Used()
                ? scanner.getSwitchRetraction0() : 0f;
        mExtruder1SwitchRetractionDistance = scanner.isTool1Used()
                ? scanner.getSwitchRetraction1() : 0f;
        mHeaderChecker.setExtruder0RetractionCheck(scanner.isTool0Used());
        mHeaderChecker.setExtruder1RetractionCheck(scanner.isTool1Used());
        mFDMRetractionParamAcquired = true;
        mCustomPrintMode = scanner.getCustomPrintMode();
    }

    private void updateParseProgress(ParseSession session, int maximum) {
        if (session.totalBytes <= 0) return;
        int progress = (int) Math.min(maximum,
                100L * session.readBytes / session.totalBytes);
        synchronized (GcodeParser.this) {
            if (isCancelled(session)) return;
            mParseProgress = progress;
            Integer previous = mParseProgressSubject.getValue();
            if (progress > 0 && (previous == null || progress > previous)) {
                mParseProgressSubject.onNext(progress);
            }
        }
    }

    private boolean looksLikeOrca(BufferedSource parseSource) {
        BufferedSource peek = null;
        try {
            peek = parseSource.peek();
            for (int count = 0; count < 32; count++) {
                String candidate = peek.readUtf8Line();
                if (candidate == null) return false;
                if (count == 0 && !candidate.isEmpty() && candidate.charAt(0) == '\ufeff') {
                    candidate = candidate.substring(1);
                }
                if (candidate.trim().isEmpty()) continue;
                return "; HEADER_BLOCK_START".equalsIgnoreCase(candidate.trim());
            }
        } catch (IOException e) {
            LogHelper.log(e);
        } finally {
            if (peek != null) {
                try {
                    peek.close();
                } catch (IOException e) {
                    LogHelper.log(e);
                }
            }
        }
        return false;
    }

    private boolean canReplaySource(ParseSession session) {
        return session.replayFile != null || session.replayInputByMark
                || session.replayInputByFileChannel;
    }

    private boolean isReplaySourceStable(ParseSession session) {
        return session.replayFile == null
                || (session.replayFile.length() == session.totalBytes
                && session.replayFile.lastModified() == session.replayFileLastModified);
    }

    private BufferedSource reopenSourceForFallback(ParseSession session,
                                                   BufferedSource previous) throws IOException {
        resetAnalysisDigests(session);
        if (session.replayFile != null) {
            previous.close();
            BufferedSource reopened = digestingSource(
                    session.replayFile.getInputStream(), session.sha256Digest, session.md5Digest);
            setCurrentSource(session, reopened);
            return reopened;
        }
        if (session.replayInputByMark) {
            session.replayInputStream.reset();
            BufferedSource reopened = digestingSource(
                    session.replayInputStream, session.sha256Digest, session.md5Digest);
            setCurrentSource(session, reopened);
            return reopened;
        }
        if (session.replayInputByFileChannel
                && session.replayInputStream instanceof FileInputStream) {
            ((FileInputStream) session.replayInputStream).getChannel().position(0L);
            BufferedSource reopened = digestingSource(
                    session.replayInputStream, session.sha256Digest, session.md5Digest);
            setCurrentSource(session, reopened);
            return reopened;
        }
        return null;
    }

    private boolean isCancelled(ParseSession session) {
        return session.generation != mParseGeneration || mActiveSession != session;
    }

    private void setCurrentSource(ParseSession session, BufferedSource current) {
        synchronized (GcodeParser.this) {
            if (isCancelled(session)) {
                closeQuietly(current);
                return;
            }
            session.currentSource = current;
            source = current;
        }
    }

    private void emitTerminal(ParseSession session, int progress) {
        synchronized (GcodeParser.this) {
            if (!isCancelled(session)) mParseProgressSubject.onNext(progress);
        }
    }

    private void closeSession(ParseSession session) {
        if (session == null) return;
        synchronized (session) {
            closeQuietly(session.currentSource);
            if (session.initialSource != session.currentSource) {
                closeQuietly(session.initialSource);
            }
        }
    }

    private static void closeQuietly(BufferedSource target) {
        if (target == null) return;
        try {
            target.close();
        } catch (IOException e) {
            LogHelper.log(e);
        }
    }

    private static MessageDigest newDigest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Required message digest is unavailable: " + algorithm, e);
        }
    }

    private static BufferedSource digestingSource(InputStream input,
                                                  MessageDigest sha256,
                                                  MessageDigest md5) {
        InputStream digested = new DigestInputStream(
                new DigestInputStream(input, sha256), md5);
        return Okio.buffer(Okio.source(digested));
    }

    private static void resetAnalysisDigests(ParseSession session) {
        session.sha256Digest.reset();
        session.md5Digest.reset();
        session.digestFinalized = false;
    }

    private void completeAnalysisDigests(ParseSession session) {
        synchronized (GcodeParser.this) {
            if (isCancelled(session) || session.digestFinalized) return;
            mLastSha256ForAnalysis = hex(session.sha256Digest.digest());
            mLastMd5ForAnalysis = hex(session.md5Digest.digest());
            session.digestFinalized = true;
        }
    }

    private static String hex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xff;
            result[index * 2] = digits[value >>> 4];
            result[index * 2 + 1] = digits[value & 0x0f];
        }
        return new String(result);
    }

    private static final class ParseSession {
        final int generation;
        final BufferedSource initialSource;
        volatile BufferedSource currentSource;
        final long totalBytes;
        long readBytes;
        final IFile replayFile;
        final long replayFileLastModified;
        final InputStream replayInputStream;
        final boolean replayInputByMark;
        final boolean replayInputByFileChannel;
        final MessageDigest sha256Digest;
        final MessageDigest md5Digest;
        boolean digestFinalized;

        ParseSession(int generation, BufferedSource source, long totalBytes,
                     IFile replayFile, long replayFileLastModified,
                     InputStream replayInputStream, boolean replayInputByMark,
                     boolean replayInputByFileChannel, MessageDigest sha256Digest,
                     MessageDigest md5Digest) {
            this.generation = generation;
            this.initialSource = source;
            this.currentSource = source;
            this.totalBytes = totalBytes;
            this.replayFile = replayFile;
            this.replayFileLastModified = replayFileLastModified;
            this.replayInputStream = replayInputStream;
            this.replayInputByMark = replayInputByMark;
            this.replayInputByFileChannel = replayInputByFileChannel;
            this.sha256Digest = sha256Digest;
            this.md5Digest = md5Digest;
        }
    }

    private static long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }

    ParseMode getLastParseModeForTest() {
        return mLastParseMode;
    }

    long getLastParseDurationMsForTest() {
        return mLastParseDurationMs;
    }

    int getLastExecutableSafetyLineCountForTest() {
        return mLastExecutableSafetyLineCount;
    }

    private void resetResult() {
        if (mModelBoundary != null) {
            mModelBoundary = new ModelBoundary();
        }
        mToolHead = -1;
        mGcodeThumbnail = null;
        mGcodeThumbnailBytes = null;
        mGcodeThumbnailArea = -1;
        mOrcaThumbnailParser.reset();
        mOrcaMetadataParser.reset();
        mTotalLinesCount = 0;
        mEstimateTime = 0;
        mNozzleTarget_0_Temperature = 0;
        mNozzleTarget_1_Temperature = 0;
        mBedTargetTemperature = 0;
        mPower = 0;
        mWorkSpeed = 0;
        mJogSpeed = 0;
        mDiameter = 0;
        mNozzle_0_Diameter = -1;
        mNozzle_1_Diameter = -1;
        mLayerNumber = -1;
        mLayerHeight = -1;
        mMaterialWeight = -1;
        mMaterialLength = -1;
        mNozzle_0_Material = null;
        mNozzle_1_Material = null;
        mRenderMethod = null;
        mIsRotate = -1;
        mWorkSizeX = -1;
        mWorkSizeY = -1;
        mOrigin = null;
        mMachine = null;
        mCustomPrintMode = 0;
        mIsDefineT0 = false;
        mIsDefineT1 = false;
        mTool0Used = false;
        mTool1Used = false;
        mToolUsageConfirmed = false;
        mToolHeadNameID = -1;
        mHeaderVersion = 0;
        mFDMRetractionParamAcquired = false;
        mLastEAxisPosition = 0;
        mT0RetractionCount = 0;
        mT1RetractionCount = 0;
        mExtruder0RetractionDistance = 0;
        mExtruder0SwitchRetractionDistance = 0;
        mExtruder1RetractionDistance = 0;
        mExtruder1SwitchRetractionDistance = 0;
        mCurrentExtruder = 0;
        mShouldUpdateAttribute = true;
        mObjectBoundaryStarted = false;
        mObjectBoundaryActive = false;
        isAbsoluteCoordinate = true;
        extrusionAmount = 0;
        currentPosition = new Position(0, 0, 0);
    }

    private boolean checkParamsRequired() {
        return mTotalLinesCount != 0 && (mFileType != IMachine.WorkType.FDM || mFDMRetractionParamAcquired);
    }

    private void parseLine(final String line) throws NumberFormatException {
        if (line.isEmpty()) {
            return;
        }

        mOrcaMetadataParser.consumeLine(line);

        if (mOrcaThumbnailParser.consumeLine(line)) {
            OrcaThumbnailBlockParser.Result result = mOrcaThumbnailParser.takeCompleted();
            if (result != null) {
                decodeAndStoreThumbnail(result.getEncodedData());
            }
            return;
        }

        // Straight comment
        if (line.charAt(0) == ';') {
            if (!mIsParsingHeader && mFileType == FDM && !mHeaderChecker.isBoundaryCheck()) {
                if (isPrintableObjectBoundaryStop(line)) {
                    mObjectBoundaryActive = false;
                } else if (isPrintableObjectBoundaryStart(line)) {
                    if (!mObjectBoundaryStarted) {
                        // Startup scripts can purge outside the printable object area. Once
                        // the slicer identifies the object/layer section, discard those setup
                        // moves and derive the safety boundary only from deposited model paths.
                        mModelBoundary = new ModelBoundary();
                    }
                    mObjectBoundaryStarted = true;
                    mObjectBoundaryActive = true;
                }
            }
            if (containsEndGcodeMark(line)) {
                mShouldUpdateAttribute = false;
            }

            //  parse header markers
            if (mIsParsingHeader) {
                if (line.equals(BOUND_HEADER_END_MARK_SNAPMAKER) || line.equals(BOUND_HEADER_END_MARK_CURA)) {
                    if (line.equals(BOUND_HEADER_END_MARK_CURA)) {
                        mFileType = FDM;
                    }
                    mIsParsingHeader = false;
                } else {
                    if (mHeaderVersion > 0) {
                        // Parse header according to the header version.
                        parseHeader(line, mHeaderVersion);
                    } else {
                        // Parse header that using former format(before 2023)
                        parseHeader(line);
                    }
                }
                return;
            } else {
                if (line.equals(BOUND_HEADER_START_MARK_SNAPMAKER) || line.equals(BOUND_HEADER_START_MARK_CURA)) {
                    mIsParsingHeader = true;
                }
                return;
            }
        }

        // Parse line to separate arguments
        final int length = line.length();

        lineArgCount = 0;
        int pos = 0;
        while (pos < length) {
            while (pos < length && line.charAt(pos) == ' ') pos++;

            if (pos == length) break;
            if (line.charAt(pos) == ';') break;

            int start = pos;
            pos++;
            while (pos < length) {
                char c = line.charAt(pos);
                if (c == ' ' || c == ';' || StringHelper.isAlphabetic(c)) break;
                pos++;
            }

            lineArgs[lineArgCount++] = line.substring(start, pos);
            if (lineArgCount >= 20) break;
        }

        if (lineArgCount == 0) return;

        // Parse arguments based on G-code command
        switch (lineArgs[0]) {
            case "G0":
            case "G1": {
                parseG0G1();
                break;
            }
            case "G4": {
                parseG4();
                break;
            }
            case "G20":
            case "G21": {
                break;
            }
            case "G28": {
//                parseG28();
                break;
            }
            case "G90": {
                // absolute position
                isAbsoluteCoordinate = true;
                break;
            }
            case "G91": {
                // relative position
                isAbsoluteCoordinate = false;
                break;
            }
            case "G92": {
                parseG92();
                break;
            }
            case "M3":
            case "M5": {
                if (mHeaderChecker.isLaserPowerCheck()) break;

                parseM3M5();
                break;
            }
            case "M83": {
                isAbsoluteCoordinate = false;
                break;
            }
            case "M82": {
                isAbsoluteCoordinate = true;
                break;
            }
            case "M140":
            case "M190": {
                if (mHeaderChecker.isHeatedBedTempCheck()) break;
                // heated bed
                parseHeatedBedTemperature();
                break;
            }
            case "M104":
            case "M109": {
                if (mHeaderChecker.isNozzleTempCheck()) break;
                // nozzle
                parseNozzleTemperature();
                break;
            }
            case "T0":
                mIsDefineT0 = true;
                mCurrentExtruder = 0;
                break;
            case "T1":
                mIsDefineT1 = true;
                mCurrentExtruder = 1;
                break;
            case "M605": {
                if (mHeaderChecker.isPrintModeCheck()) break;

                parseM605();
                break;
            }
            default:
                break;
        }
    }

    private void checkPathStart() {
        if (!mIsCurrentGcodeStateWorking) {
            mIsCurrentGcodeStateWorking = true;

            currentPosition.setAsStartPoint();
//            mToolPath.add(currentPosition);
        }
    }

    private void checkPathClose() {
        if (mIsCurrentGcodeStateWorking) {
            mIsCurrentGcodeStateWorking = false;

            currentPosition.setAsEndPoint();
        }
    }

    private void parseHeader(String line) throws NumberFormatException {
        final int length = line.length();

        lineArgCount = 0;
        // skip comment mark
        int pos = 1;

        while (pos < length) {
            while (pos < length && line.charAt(pos) == ' ') pos++;

            if (pos == length) break;

            int start = pos;
            while (pos < length && line.charAt(pos) != ':') pos++;

            lineArgs[lineArgCount++] = line.substring(start, pos);

            pos++;
        }

        if (lineArgCount == 0) return;
        if ("null".equals(lineArgs[1])) return;
        // Parse arguments based on G-code command
        switch (lineArgs[0]) {
            case "Version": {
                mHeaderVersion = Integer.parseInt(lineArgs[1]);
                break;
            }
            case "header_type": {
                switch (lineArgs[1]) {
                    case "3dp":
                        mFileType = FDM;
                        break;
                    case "laser":
                        mFileType = LASER;
                        break;
                    case "cnc":
                        mFileType = CNC;
                        break;
                    default:
                        break;
                }
                break;
            }
            case "tool_head": {
                switch (lineArgs[1]) {
                    case "singleExtruderToolheadForOriginal":
                        break;
                    case "singleExtruderToolheadForSM2":
                        mToolHead = HEAD_3DP;
                        mToolHeadNameID = R.string.all_tool_head_3dp;
                        break;
                    case "dualExtruderToolheadForSM2":
                        mToolHead = HEAD_3DP_DOUBLE_EXTRUDER;
                        mToolHeadNameID = R.string.all_tool_head_dual_extruder;
                        break;
                    case "levelOneLaserToolheadForOriginal":
                        break;
                    case "levelTwoLaserToolheadForOriginal":
                        break;
                    case "levelOneLaserToolheadForSM2":
                        mToolHead = HEAD_LASER;
                        mToolHeadNameID = R.string.all_tool_head_laser;
                        break;
                    case "levelTwoLaserToolheadForSM2":
                        mToolHead = HEAD_LASER_10W;
                        mToolHeadNameID = R.string.all_tool_head_laser_10w;
                        break;
                    case "standardCNCToolheadForOriginal":
                        break;
                    case "standardCNCToolheadForSM2":
                        mToolHead = HEAD_CNC;
                        mToolHeadNameID = R.string.all_tool_head_cnc;
                        break;
                    case "levelTwoCNCToolheadForSM2":
                        mToolHead = HEAD_CNC_200W;
                        mToolHeadNameID = R.string.all_tool_head_cnc_200w;
                        break;
                    case "20W Laser Module":
                        mToolHead = HEAD_LASER_20W;
                        mToolHeadNameID = R.string.all_tool_head_laser_20w;
                        break;
                    case "40W Laser Module":
                        mToolHead = HEAD_LASER_40W;
                        mToolHeadNameID = R.string.all_tool_head_laser_40w;
                        break;
                    case "2W Laser Module":
                        mToolHead = HEAD_LASER_2W_INFRARED;
                        mToolHeadNameID = R.string.all_tool_head_laser_2w_infrared;
                        break;
                    default:
                        break;
                }
                break;
            }
            case "thumbnail": {
                decodeAndStoreThumbnail(lineArgs[2]);
                break;
            }
            case "file_total_lines": {
                mTotalLinesCount = Integer.valueOf(lineArgs[1]);
                mHeaderChecker.setTotalLinesCheck(true);
                break;
            }
            case "estimated_time(s)":
            case "PRINT.TIME": {
                mEstimateTime = Float.valueOf(lineArgs[1]);
                mHeaderChecker.setEstimatedTimeCheck(true);
                break;
            }
            case "min_x(mm)":
            case "PRINT.SIZE.MIN.X": {
                mModelBoundary.setMinX(Float.valueOf(lineArgs[1]));
                mHeaderChecker.setBoundaryCheck(true);
                break;
            }
            case "max_x(mm)":
            case "PRINT.SIZE.MAX.X": {
                mModelBoundary.setMaxX(Float.valueOf(lineArgs[1]));
                mHeaderChecker.setBoundaryCheck(true);
                break;
            }
            case "min_y(mm)":
            case "PRINT.SIZE.MIN.Y": {
                mModelBoundary.setMinY(Float.valueOf(lineArgs[1]));
                mHeaderChecker.setBoundaryCheck(true);
                break;
            }
            case "max_y(mm)":
            case "PRINT.SIZE.MAX.Y": {
                mModelBoundary.setMaxY(Float.valueOf(lineArgs[1]));
                mHeaderChecker.setBoundaryCheck(true);
                break;
            }
            case "min_z(mm)":
            case "PRINT.SIZE.MIN.Z": {
                mModelBoundary.setMinZ(Float.valueOf(lineArgs[1]));
                mHeaderChecker.setBoundaryCheck(true);
                break;
            }
            case "max_z(mm)":
            case "PRINT.SIZE.MAX.Z": {
                mModelBoundary.setMaxZ(Float.valueOf(lineArgs[1]));
                mHeaderChecker.setBoundaryCheck(true);
                break;
            }
            case "min_b(mm)": {
                mModelBoundary.setMinB(Float.valueOf(lineArgs[1]));
                mHeaderChecker.setBoundaryCheck(true);
                break;
            }
            case "max_b(mm)": {
                mModelBoundary.setMaxB(Float.valueOf(lineArgs[1]));
                mHeaderChecker.setBoundaryCheck(true);
                break;
            }
            case "nozzle_temperature(°C)":
            case "nozzle_0_temperature(°C)":
            case "EXTRUDER_TRAIN.0.INITIAL_TEMPERATURE": {
                mNozzleTarget_0_Temperature = Float.valueOf(lineArgs[1]);
                mHeaderChecker.setNozzleTempCheck(true);
                break;
            }
            case "nozzle_1_temperature(°C)":
            case "EXTRUDER_TRAIN.1.INITIAL_TEMPERATURE": {
                if (lineArgs[1].equals("null")) return;
                mNozzleTarget_1_Temperature = Float.valueOf(lineArgs[1]);
                mHeaderChecker.setNozzleTempCheck(true);
                break;
            }
            case "build_plate_temperature(°C)":
            case "BUILD_PLATE.INITIAL_TEMPERATURE": {
                mBedTargetTemperature = Float.valueOf(lineArgs[1]);
                mHeaderChecker.setHeatedBedTempCheck(true);
                break;
            }
            case "spindle_speed(mm/minute)": {
                mSpindleSpeed = Float.valueOf(lineArgs[1]);
                break;
            }
            case "power(%)": {
                mPower = Float.valueOf(lineArgs[1]);
                mHeaderChecker.setLaserPowerCheck(true);
                break;
            }
            case "work_speed(mm/minute)": {
                mWorkSpeed = Float.valueOf(lineArgs[1]);
                break;
            }
            case "jog_speed(mm/minute)": {
                mJogSpeed = Float.valueOf(lineArgs[1]);
                break;
            }
            case "diameter": {
                mDiameter = Float.valueOf(lineArgs[1]);
                break;
            }
            case "nozzle_0_diameter":
            case "nozzle_0_diameter(mm)": {
                if ("null".equals(lineArgs[1])) return;
                mNozzle_0_Diameter = Float.valueOf(lineArgs[1]);
                break;
            }
            case "nozzle_1_diameter":
            case "nozzle_1_diameter(mm)": {
                if ("null".equals(lineArgs[1])) return;
                mNozzle_1_Diameter = Float.valueOf(lineArgs[1]);
                break;
            }
            case "layer_number": {
                mLayerNumber = Integer.valueOf(lineArgs[1]);
                break;
            }
            case "layer_height": {
                mLayerHeight = Float.parseFloat(lineArgs[1]);
                break;
            }
            //FIXME :Remove this code after filming.
            case "matierial_weight": {
                mMaterialWeight = Float.parseFloat(lineArgs[1]);
                break;
            }
            //FIXME :Remove this code after filming.
            case "matierial_length": {
                mMaterialLength = Float.parseFloat(lineArgs[1]);
                break;
            }
            case "nozzle_0_material": {
                mNozzle_0_Material = lineArgs[1];
                break;
            }
            case "nozzle_1_material": {
                mNozzle_1_Material = lineArgs[1];
                break;
            }
            case "renderMethod": {
                mRenderMethod = lineArgs[1];
                break;
            }
            case "is_rotate": {
                mIsRotate = lineArgs[1].equals("true") ? 1 : 0;
                mModelBoundary.setDimension((mIsRotate > 0) ? ModelBoundary.DIMENSION_BY : ModelBoundary.DIMENSION_XY);
                break;
            }
            case "work_size_x": {
                mWorkSizeX = Float.valueOf(lineArgs[1]);
                break;
            }
            case "work_size_y": {
                mWorkSizeY = Float.valueOf(lineArgs[1]);
                break;
            }
            case "origin": {
                mOrigin = lineArgs[1];
                Context context = ServiceContainer.getInstance().getService(IAppService.class).getAppContext();
                switch (mOrigin) {
                    case "center":
                        mOrigin = context.getString(R.string.all_gcode_parser_work_origin_center);
                        break;
                    case "top-left":
                        mOrigin = context.getString(R.string.all_gcode_parser_work_origin_top_left);
                        break;
                    case "top-right":
                        mOrigin = context.getString(R.string.all_gcode_parser_work_origin_top_right);
                        break;
                    case "bottom-left":
                        mOrigin = context.getString(R.string.all_gcode_parser_work_origin_bottom_left);
                        break;
                    case "bottom-right":
                        mOrigin = context.getString(R.string.all_gcode_parser_work_origin_bottom_right);
                        break;
                    case "positionA":
                        mOrigin = context.getString(R.string.all_gcode_parser_work_origin_bottom_position_a);
                        break;
                    case "positionB":
                        mOrigin = context.getString(R.string.all_gcode_parser_work_origin_bottom_position_b);
                        break;
                    default:
                        break;
                }
                break;
            }
            case "machine": {
                mMachine = lineArgs[1];
                break;
            }
            case "Extruder 0 Retraction Distance": {
                mExtruder0RetractionDistance = Float.parseFloat(lineArgs[1]);
                mFDMRetractionParamAcquired = true;
                mHeaderChecker.setExtruder0RetractionCheck(true);
                break;
            }
            case "Extruder 0 Switch Retraction Distance": {
                mExtruder0SwitchRetractionDistance = Float.parseFloat(lineArgs[1]);
                break;
            }
            case "Extruder 1 Retraction Distance": {
                mExtruder1RetractionDistance = Float.parseFloat(lineArgs[1]);
                mFDMRetractionParamAcquired = true;
                mHeaderChecker.setExtruder1RetractionCheck(true);
                break;
            }
            case "Extruder 1 Switch Retraction Distance": {
                mExtruder1SwitchRetractionDistance = Float.parseFloat(lineArgs[1]);
                break;
            }
            default:
                break;
        }
    }

    private void parseHeader(String line, int headerVersion) throws NumberFormatException {
        final int length = line.length();
        lineArgCount = 0;

        switch (headerVersion) {
            case 1:
            default:
                // skip comment mark
                int pos = 1;

                while (pos < length) {
                    while (pos < length && line.charAt(pos) == ' ') pos++;

                    if (pos == length) break;

                    int start = pos;
                    while (pos < length && line.charAt(pos) != ':') pos++;

                    lineArgs[lineArgCount++] = line.substring(start, pos);

                    pos++;
                }

                if (lineArgCount == 0) return;
                if ("null".equals(lineArgs[1])) return;
                // Parse arguments based on G-code command
                switch (lineArgs[0]) {
                    case "Slicer":
                        break;
                    case "Printer":
                        break;
                    case "Estimated Print Time":
                        mEstimateTime = Integer.parseInt(lineArgs[1]);
                        mHeaderChecker.setEstimatedTimeCheck(true);
                        break;
                    case "Lines":
                        mTotalLinesCount = Integer.parseInt(lineArgs[1]);
                        mHeaderChecker.setTotalLinesCheck(true);
                        break;
                    case "Extruder Mode":
                        break;
                    case "Extruder 0 Nozzle Size":
                        mNozzle_0_Diameter = Float.parseFloat(lineArgs[1]);
                        break;
                    case "Extruder 0 Material":
                        mNozzle_0_Material = lineArgs[1];
                        break;
                    case "Extruder 0 Print Temperature":
                        mNozzleTarget_0_Temperature = Float.parseFloat(lineArgs[1]);
                        mHeaderChecker.setNozzleTempCheck(true);
                        break;
                    case "Extruder 0 Retraction Distance":
                        mExtruder0RetractionDistance = Float.parseFloat(lineArgs[1]);
                        mFDMRetractionParamAcquired = true;
                        mHeaderChecker.setExtruder0RetractionCheck(true);
                        break;
                    case "Extruder 0 Switch Retraction Distance":
                        mExtruder0SwitchRetractionDistance = Float.parseFloat(lineArgs[1]);
                        break;
                    case "Extruder 1 Nozzle Size":
                        mNozzle_1_Diameter = Float.parseFloat(lineArgs[1]);
                        break;
                    case "Extruder 1 Material":
                        mNozzle_1_Material = lineArgs[1];
                        break;
                    case "Extruder 1 Print Temperature":
                        mNozzleTarget_1_Temperature = Float.parseFloat(lineArgs[1]);
                        mHeaderChecker.setNozzleTempCheck(true);
                        break;
                    case "Extruder 1 Retraction Distance":
                        mExtruder1RetractionDistance = Float.parseFloat(lineArgs[1]);
                        mFDMRetractionParamAcquired = true;
                        mHeaderChecker.setExtruder1RetractionCheck(true);
                        break;
                    case "Extruder 1 Switch Retraction Distance":
                        mExtruder1SwitchRetractionDistance = Float.parseFloat(lineArgs[1]);
                        break;
                    case "Bed Temperature":
                        mBedTargetTemperature = Float.parseFloat(lineArgs[1]);
                        mHeaderChecker.setHeatedBedTempCheck(true);
                        break;
                    case "Extruder(s) Used":
                        break;
                    case "Work Range - Min X":
                        mModelBoundary.setMinX(Float.parseFloat(lineArgs[1]));
                        mHeaderChecker.setBoundaryCheck(true);
                        break;
                    case "Work Range - Min Y":
                        mModelBoundary.setMinY(Float.parseFloat(lineArgs[1]));
                        mHeaderChecker.setBoundaryCheck(true);
                        break;
                    case "Work Range - Min Z":
                        mModelBoundary.setMinZ(Float.parseFloat(lineArgs[1]));
                        mHeaderChecker.setBoundaryCheck(true);
                        break;
                    case "Work Range - Max X":
                        mModelBoundary.setMaxX(Float.parseFloat(lineArgs[1]));
                        mHeaderChecker.setBoundaryCheck(true);
                        break;
                    case "Work Range - Max Y":
                        mModelBoundary.setMaxY(Float.parseFloat(lineArgs[1]));
                        mHeaderChecker.setBoundaryCheck(true);
                        break;
                    case "Work Range - Max Z":
                        mModelBoundary.setMaxZ(Float.parseFloat(lineArgs[1]));
                        mHeaderChecker.setBoundaryCheck(true);
                        break;
                    case "Thumbnail":
                        decodeAndStoreThumbnail(lineArgs[2]);
                        break;
                }
                break;
        }
    }

    /**
     * Parse G0 and G1 command from lineArgs.
     * <p>
     * G0-G1 : Linear Move, add a straight line movement to the planer.
     * Movements can be set in Relative Mode or Absolute Mode using <b>G90</b> or <b>G91</b> command.
     * <p>
     * Also <b>M83</b> command can set "E coordinate", which is interpreted as relative.
     * <p>
     * `X`,`Y`,`Z` represent the coordinate on the axis.
     * <p>
     * `A`,`B`,`C` represent the rotation axis.
     * <p>
     * `E` for the extruder axis, it describes the position of the filament in terms of the extruder feeder.
     * <p>
     * `F` represent the maximum movement rate of the move.
     * <p>
     * Examples:
     * <p>
     * G1 F1500
     * <p>
     * G1 X90.6 Y13.8 E22.4 F3000
     * <p>
     * G1 X80 Y20 E36 F1500
     * <p>
     * G0 F2400 X49.071 Y22.466 E0.43903
     */
    private void parseG0G1() throws NumberFormatException {
        float x = 0, y = 0, z = 0, e, feedRate;
        boolean hasPositiveExtrusion = false;

        x = currentPosition.x;
        y = currentPosition.y;
        z = currentPosition.z;

        for (int i = 1; i < lineArgCount; i++) {
            switch (lineArgs[i].charAt(0)) {
                case 'X': {
                    x = Float.parseFloat(lineArgs[i].substring(1));
                    break;
                }
                case 'Y': {
                    y = Float.parseFloat(lineArgs[i].substring(1));
                    break;
                }
                case 'Z': {
                    z = Float.parseFloat(lineArgs[i].substring(1));
                    break;
                }
                case 'E': {
                    if (mShouldUpdateAttribute) {
                        e = Float.parseFloat(lineArgs[i].substring(1));
                        float extrusionDelta = isAbsoluteCoordinate
                                ? e - extrusionAmount : e;
                        extrusionAmount = isAbsoluteCoordinate ? (e) : (extrusionAmount + e);
                        hasPositiveExtrusion = hasPositiveExtrusion || extrusionDelta > 0.000001f;
                        if (mFileType == IMachine.WorkType.FDM && (!mHeaderChecker.isExtruder0RetractionCheck() || !mHeaderChecker.isExtruder1RetractionCheck())) {
                            calculateRetraction(e);
                        }
                    }
                    break;
                }
                case 'F': {
                    if (mShouldUpdateAttribute) {
                        feedRate = Float.parseFloat(lineArgs[i].substring(1));
                        if (feedRate != 0 && feedRate != mCurrentLineFeedRate) {
                            mCurrentLineFeedRate = feedRate;
                        }
                    }
                    break;
                }
            }
        }

        if (mFileType == IMachine.WorkType.FDM && hasPositiveExtrusion) {
            if (mCurrentExtruder == 1) mTool1Used = true;
            else mTool0Used = true;
            if (printModeUsesBothPhysicalNozzles()) {
                // Persist physical operation at the extrusion point. A later M605 S0 changes
                // future behavior but cannot undo that both hotends were already used.
                mTool0Used = true;
                mTool1Used = true;
            }
        }

        boolean pointMoved = (x != currentPosition.x || y != currentPosition.y || z != currentPosition.z);
        if (!pointMoved) {
            return;
        }

        Position position = new Position(x, y, z);
        Position previousPosition = currentPosition;

        if (lineArgs[0].equals("G0")) {
            checkPathClose();

            // Calculate ETA
            if (mCurrentLineFeedRate != 0 && !mHeaderChecker.isEstimatedTimeCheck()) {
                final float segmentTime = currentPosition.distanceTo(position) / mCurrentLineFeedRate * 60;
                mEstimateTime += segmentTime;
            }

            // Update current position
            currentPosition = position;

//            mModelBoundary.updateBoundary(position);
        } else if (lineArgs[0].equals("G1")) {
            checkPathStart();

            if (mCurrentLineFeedRate != 0 && !mHeaderChecker.isEstimatedTimeCheck()) {
                final float segmentTime = currentPosition.distanceTo(position) / mCurrentLineFeedRate * 60;
                mEstimateTime += segmentTime;

                // Calculate average work speed FeedRate
                mFeedRateAmount += mCurrentLineFeedRate;
                mFeedRateCount++;
            }

            currentPosition = position;

//            mToolPath.add(position);
            if (!mHeaderChecker.isBoundaryCheck()
                    && mObjectBoundaryActive
                    && hasPositiveExtrusion
                    && mShouldUpdateAttribute) {
                mModelBoundary.updateBoundary(previousPosition);
                mModelBoundary.updateBoundary(position);
            }
        }
    }

    private void calculateRetraction(float ePosition) {
        // Absolute mode
        float delta = isAbsoluteCoordinate ? ePosition - mLastEAxisPosition : ePosition;
        if (delta < 0 && delta > -8.0f) {
//            Logger.d("retraction detected T%d %.4f", mCurrentExtruder, delta);
//            Logger.d("gcode %s", Arrays.toString(lineArgs));
            if (mCurrentExtruder == 0) {
                if (mT0RetractionCount == 0) {
                    mExtruder0RetractionDistance = -delta;
                }

                if (mExtruder0RetractionDistance != -delta) {
                    mT0RetractionCount = 1;
                }

                if (mT0RetractionCount > 3 && mExtruder0RetractionDistance > -delta) {
                    mExtruder0RetractionDistance = -delta;
                }
                mT0RetractionCount++;
            } else {
                if (mT1RetractionCount == 0) {
                    mExtruder1RetractionDistance = -delta;
                }

                if (mExtruder1RetractionDistance != -delta) {
                    mT1RetractionCount = 1;
                }

                if (mT1RetractionCount > 3 && mExtruder1RetractionDistance > -delta) {
                    mExtruder1RetractionDistance = -delta;
                }
                mT1RetractionCount++;
            }
//            Logger.d("T0 retracted %.2f at %d times, T1 retracted %.2f at %d times",
//                    mExtruder0RetractionDistance, mT0RetractionCount,
//                    mExtruder1RetractionDistance, mT1RetractionCount);
        }
        mLastEAxisPosition = ePosition;
    }

    /**
     * Parse G4 G-code command.
     * <p>
     * G4: Dwell, pause the command queue and waits for a period of time.
     * <p>
     * Using `S` or `P` parameters, if both included, `S` takes precedence.
     * <p>
     * P[time(ms)]
     * <p>
     * S[time(sec)]
     */
    private void parseG4() throws NumberFormatException {
        for (int i = 1; i < lineArgCount; i++) {
            switch (lineArgs[i].charAt(0)) {
                case 'S': {
                    final float dwellTime = Float.parseFloat(lineArgs[i].substring(1));
                    mEstimateTime += dwellTime;
                    return;
                }
                case 'P': {
                    final float dwellTime = Float.parseFloat(lineArgs[i].substring(1));
                    mEstimateTime += dwellTime * 0.001;
                    return;
                }
            }
        }
    }

    private void parseG28() {
        // TODO: implement an offset for absolute position
        if (lineArgCount < 2) {
            checkPathClose();
            currentPosition = new Position(0, 0, 0);
        } else {
            float x = currentPosition.x;
            float y = currentPosition.y;
            float z = currentPosition.z;

            for (int i = 1; i < lineArgCount; i++) {
                switch (lineArgs[i].charAt(0)) {
                    case 'X': {
                        x = 0;
                        break;
                    }
                    case 'Y': {
                        y = 0;
                        break;
                    }
                    case 'Z': {
                        z = 0;
                        break;
                    }
                }
            }

            checkPathClose();
            currentPosition = new Position(x, y, z);
        }
    }

    /**
     * G92
     */
    private void parseG92() {
        // TODO: implement an offset for absolute position
        for (int i = 1; i < lineArgCount; i++) {
            if (lineArgs[i].charAt(0) == 'E') {
                float resetPosition = Float.parseFloat(lineArgs[i].substring(1));
                mLastEAxisPosition = resetPosition;
                extrusionAmount = resetPosition;
            }
        }
    }

    static boolean isPrintableObjectBoundaryStart(String line) {
        if (line == null || line.length() < 2 || line.charAt(0) != ';') return false;
        String marker = line.substring(1).trim().toLowerCase(Locale.US);
        if (marker.equals("layer_change")
                || marker.startsWith("layer:")
                || marker.startsWith("layer_num:")
                || marker.startsWith("layer num/total_layer_count:")
                || marker.startsWith("printing object ")
                || marker.startsWith("mesh:")) {
            return true;
        }
        return marker.startsWith("type:") && !marker.equals("type:custom");
    }

    static boolean isPrintableObjectBoundaryStop(String line) {
        if (line == null || line.length() < 2 || line.charAt(0) != ';') return false;
        String marker = line.substring(1).trim().toLowerCase(Locale.US);
        return marker.startsWith("stop printing object ") || marker.equals("type:custom");
    }

    private void parseM3M5() throws NumberFormatException {
        for (int i = 1; i < lineArgCount; i++) {
            switch (lineArgs[i].charAt(0)) {
                case 'S': {
                    mPower = Float.parseFloat(lineArgs[i].substring(1)) / 255 * 100;
                    break;
                }
                case 'P': {
                    mPower = Float.parseFloat(lineArgs[i].substring(1));
                    break;
                }
            }
        }
    }

    private void parseHeatedBedTemperature() throws NumberFormatException {
        for (int i = 1; i < lineArgCount; i++) {
            if (lineArgs[i].charAt(0) == 'S') {
                float temperature = Float.parseFloat(lineArgs[i].substring(1));
                if (mBedTargetTemperature < temperature) {
                    mBedTargetTemperature = temperature;
                }
            }
        }
    }

    private void parseNozzleTemperature() throws NumberFormatException {
        for (int i = 1; i < lineArgCount; i++) {
            if (lineArgs[i].charAt(0) == 'S') {
                float temperature = Float.parseFloat(lineArgs[i].substring(1));
                if (mNozzleTarget_0_Temperature < temperature) {
                    mNozzleTarget_0_Temperature = temperature;
                }
            }
        }
    }

    /**
     * M605: Set behavior print mode for dual extruder or "IDEX" machine.
     * <p>
     * [S] parameter represent print mode in dual x
     */
    private void parseM605() throws NumberFormatException {
        for (int i = 1; i < lineArgCount; i++) {
            switch (lineArgs[i].charAt(0)) {
                case 'S': {
                    mCustomPrintMode = Integer.parseInt(lineArgs[i].substring(1));
                }
            }
        }
    }

    private boolean containsEndGcodeMark(String line) {
        return line.contains(BOUNDS_END_MARK) || line.contains(BOUNDS_END_MARK_V1);
    }

    public ArrayList<Position> getToolPath() {
        return mToolPath;
    }

    private void decodeAndStoreThumbnail(String encodedImage) {
        try {
            int commaIndex = encodedImage.indexOf(',');
            String encodedData = commaIndex >= 0
                    ? encodedImage.substring(commaIndex + 1)
                    : encodedImage;
            if (encodedData.isEmpty()
                    || encodedData.length() > OrcaThumbnailBlockParser.MAX_ENCODED_CHARACTERS) {
                return;
            }
            byte[] bitmapArray = Base64.decode(encodedData, Base64.DEFAULT);
            if (bitmapArray.length == 0) {
                return;
            }

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bitmapArray, 0, bitmapArray.length, bounds);
            long area = (long) bounds.outWidth * bounds.outHeight;
            if (bounds.outWidth <= 0
                    || bounds.outHeight <= 0
                    || bounds.outWidth > OrcaThumbnailBlockParser.MAX_IMAGE_DIMENSION
                    || bounds.outHeight > OrcaThumbnailBlockParser.MAX_IMAGE_DIMENSION
                    || area <= 0
                    || area > OrcaThumbnailBlockParser.MAX_IMAGE_PIXELS
                    || area <= mGcodeThumbnailArea) {
                return;
            }

            Bitmap bitmap = BitmapFactory.decodeByteArray(bitmapArray, 0, bitmapArray.length);
            if (bitmap != null) {
                mGcodeThumbnailBytes = bitmapArray.clone();
                mGcodeThumbnail = bitmap;
                mGcodeThumbnailArea = area;
            }
        } catch (Exception e) {
            LogHelper.log(e);
        }
    }

    private void applyOrcaMetadata() {
        OrcaMetadataCommentParser.Result metadata = mOrcaMetadataParser.getResult();
        if (metadata.estimatedTimeSeconds != null) {
            mEstimateTime = metadata.estimatedTimeSeconds;
            mHeaderChecker.setEstimatedTimeCheck(true);
        }
        if (metadata.layerCount != null) mLayerNumber = metadata.layerCount;
        if (metadata.layerHeightMm != null) mLayerHeight = metadata.layerHeightMm;
        if (metadata.materialLengthMeters != null) {
            mMaterialLength = metadata.materialLengthMeters;
        }
        if (metadata.materialWeightGrams != null) {
            mMaterialWeight = metadata.materialWeightGrams;
        }
        if (metadata.materialLeft != null) mNozzle_0_Material = metadata.materialLeft;
        if (metadata.materialRight != null) mNozzle_1_Material = metadata.materialRight;
        if (metadata.nozzleDiameterLeftMm != null) {
            mNozzle_0_Diameter = metadata.nozzleDiameterLeftMm;
        }
        if (metadata.nozzleDiameterRightMm != null) {
            mNozzle_1_Diameter = metadata.nozzleDiameterRightMm;
        }
        if (!mHeaderChecker.isNozzleTempCheck()) {
            if (metadata.nozzleTemperatureLeftC != null) {
                mNozzleTarget_0_Temperature = metadata.nozzleTemperatureLeftC;
            }
            if (metadata.nozzleTemperatureRightC != null) {
                mNozzleTarget_1_Temperature = metadata.nozzleTemperatureRightC;
            }
        }
        if (!mHeaderChecker.isHeatedBedTempCheck() && metadata.bedTemperatureC != null) {
            mBedTargetTemperature = metadata.bedTemperatureC;
        }
    }

    private void finalizeLegacyToolUsage(boolean comprehensiveExecutableScan) {
        OrcaMetadataCommentParser.Result metadata = mOrcaMetadataParser.getResult();
        // The complete executable scan is the primary source. Plate declarations are retained
        // as a conservative fallback for commands the legacy tokenizer cannot model (for
        // example an unsupported extrusion primitive) and can only add a required nozzle.
        if (Boolean.TRUE.equals(metadata.plateTool0Used)) mTool0Used = true;
        if (Boolean.TRUE.equals(metadata.plateTool1Used)) mTool1Used = true;
        if (!mTool0Used && !mTool1Used) {
            // Non-Orca legacy files may declare tools without slicer Plate metadata. Preserve the
            // prior fail-closed behaviour instead of declaring both nozzles unused.
            mTool0Used = mIsDefineT0;
            mTool1Used = mIsDefineT1;
        }
        // The legacy tokenizer deliberately ignores several valid G-code forms (for example G5,
        // modal coordinate-only records and N/checksum framing). It may add known positive use,
        // but only a complete fast executable scan can prove that an unobserved side is unused.
        mToolUsageConfirmed = comprehensiveExecutableScan;
    }

    private boolean printModeUsesBothPhysicalNozzles() {
        return mCustomPrintMode >= 1 && mCustomPrintMode <= 3;
    }

    private boolean effectivelyUsesTool0() {
        return mTool0Used;
    }

    private boolean effectivelyUsesTool1() {
        return mTool1Used;
    }

    @Override
    public IMachine.WorkType getFileType() {
        return mFileType;
    }

    @Override
    public Bitmap getGcodeThumbnail() {
        return mGcodeThumbnail;
    }

    public byte[] getGcodeThumbnailBytes() {
        return mGcodeThumbnailBytes;
    }

    public synchronized void adoptRemotePrintThumbnail(Bitmap bitmap, byte[] bytes) {
        mGcodeThumbnail = bitmap;
        mGcodeThumbnailBytes = bytes == null ? null : bytes.clone();
        mGcodeThumbnailArea = bitmap == null
                ? -1L
                : (long) bitmap.getWidth() * bitmap.getHeight();
    }

    @Override
    public int getTotalLinesCount() {
        return mTotalLinesCount;
    }

    @Override
    public float getBedTargetTemperature() {
        return mBedTargetTemperature;
    }

    @Override
    public float getNozzleTargetTemperature() {
        return mNozzleTarget_0_Temperature;
    }

    @Override
    public float getPower() {
        return mPower;
    }

//    @Override
//    public float getCNCPower() {
//        return mCNCPower;
//    }

    @Override
    public float getWorkSpeed() {
        if (mWorkSpeed != 0) {
            return mWorkSpeed;
        } else {
            if (mFeedRateCount == 0) return 0;

            return (float) mFeedRateAmount / mFeedRateCount;
        }
    }

    @Override
    public float getJogSpeed() {
        return mJogSpeed;
    }

    @Override
    public float getDiameter() {
        return mDiameter;
    }

    @Override
    public float getSpindleSpeed() {
        return mSpindleSpeed;
    }

    public float getAverageFeedRate() {
        if (mFeedRateCount == 0) return 0;

        return (float) mFeedRateAmount / mFeedRateCount;
    }

    @Override
    public float getEstimatedTime() {
        return mEstimateTime;
    }

    @Override
    public ModelBoundary getBoundary() {
        return mModelBoundary;
    }

    @Override
    public Observable<Integer> getParseProgressObservable() {
        return mParseProgressSubject;
    }

    public int getHeaderType() {
        return mToolHead;
    }

    @Override
    public int getHeaderNameID() {
        return mToolHeadNameID;
    }

    public float getNozzle_0_Diameter() {
        return mNozzle_0_Diameter;
    }

    public float getNozzle_1_Diameter() {
        return mNozzle_1_Diameter;
    }

    public int getLayerNumber() {
        return mLayerNumber;
    }

    public float getLayerHeight() {
        return mLayerHeight;
    }

    public float getMaterialWeight() {
        return mMaterialWeight;
    }

    public float getMaterialLength() {
        return mMaterialLength;
    }

    public String getMaterial_0() {
        return mNozzle_0_Material;
    }

    public String getMaterial_1() {
        return mNozzle_1_Material;
    }

    public String getRenderMethod() {
        return mRenderMethod;
    }

    public int isContainRotation() {
        return mIsRotate;
    }

    public float getWorkSizeX() {
        return mWorkSizeX;
    }

    public float getWorkSizeY() {
        return mWorkSizeY;
    }

    public String getOrigin() {
        return mOrigin;
    }

    public float getNozzleTarget_1_Temperature() {
        return mNozzleTarget_1_Temperature;
    }

    @Override
    public float getExtruder0RetractionDistance() {
        return mExtruder0RetractionDistance;
    }

    @Override
    public float getExtruder1RetractionDistance() {
        return mExtruder1RetractionDistance;
    }

    @Override
    public float getExtruder0SwitchRetractionDistance() {
        return mExtruder0SwitchRetractionDistance;
    }

    @Override
    public float getExtruder1SwitchRetractionDistance() {
        return mExtruder1SwitchRetractionDistance;
    }

    @Override
    public int getCustomPrintMode() {
        return mCustomPrintMode;
    }

    @Override
    public String getLastSha256ForAnalysis() {
        return mLastSha256ForAnalysis;
    }

    @Override
    public String getLastMd5ForAnalysis() {
        return mLastMd5ForAnalysis;
    }

    @Override
    public boolean isToolUsageConfirmed() {
        return mToolUsageConfirmed;
    }

    @Override
    public boolean isTool0Used() {
        return effectivelyUsesTool0() || (!mToolUsageConfirmed && mIsDefineT0);
    }

    @Override
    public boolean isTool1Used() {
        return effectivelyUsesTool1() || (!mToolUsageConfirmed && mIsDefineT1);
    }

    @Override
    public boolean isApplyMultiExtruder() {
        return mToolUsageConfirmed
                ? effectivelyUsesTool0() && effectivelyUsesTool1()
                : mIsDefineT0 && mIsDefineT1;
    }
}
