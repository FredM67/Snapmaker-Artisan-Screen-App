package fabscreen.platform.base.legacy.server.http.handlers;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import fabscreen.platform.base.instantiation.ServiceContainer;
import fabscreen.platform.base.service.IMachine;
import fabscreen.platform.lib.LogHelper;

/**
 * Process-owned, bounded dashboard graph history. The sampler is started with the app, not by a
 * browser request, so closing or backgrounding a browser does not stop the temperature timeline.
 */
public final class DashboardTelemetryHistory {
    private static final long SAMPLE_INTERVAL_MS = 2_000L;
    private static final long HISTORY_DURATION_MS = 30L * 60L * 1_000L;
    private static final int MAX_SAMPLES = 1_800;
    private static final DashboardTelemetryHistory INSTANCE = new DashboardTelemetryHistory();

    private final Object mStartLock = new Object();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final OrcaRequestHandler mTelemetryBuilder = new OrcaRequestHandler();
    private final DashboardTelemetryBuffer<JSONObject> mSamples =
            new DashboardTelemetryBuffer<>(HISTORY_DURATION_MS, MAX_SAMPLES);
    private boolean mStarted;
    private volatile boolean mEnclosureControlPending;

    private final Runnable mSample = new Runnable() {
        @Override
        public void run() {
            try {
                IMachine machine = ServiceContainer.getInstance().getService(IMachine.class);
                if (machine != null) {
                    mTelemetryBuilder.maybeRefreshTelemetry(machine);
                    JSONObject sample = mTelemetryBuilder.buildDashboardTelemetrySample(machine);
                    mSamples.add(sample, SystemClock.elapsedRealtime());
                }
            } catch (Exception error) {
                LogHelper.log(error);
            } finally {
                mMainHandler.postDelayed(this, SAMPLE_INTERVAL_MS);
            }
        }
    };

    private DashboardTelemetryHistory() {
    }

    public static DashboardTelemetryHistory getInstance() {
        return INSTANCE;
    }

    /** Starts once for the lifetime of the FabScreen process. */
    public void start() {
        synchronized (mStartLock) {
            if (mStarted) return;
            mStarted = true;
        }
        mMainHandler.post(mSample);
    }

    void setEnclosureControlPending(boolean pending) {
        mEnclosureControlPending = pending;
    }

    boolean isEnclosureControlPending() {
        return mEnclosureControlPending;
    }

    JSONObject snapshot() throws JSONException {
        JSONArray samples = new JSONArray();
        for (JSONObject sample : mSamples.snapshot(SystemClock.elapsedRealtime())) {
            samples.put(sample);
        }
        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("samples", samples);
        return result;
    }

}
