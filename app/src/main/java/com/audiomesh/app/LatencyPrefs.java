package com.audiomesh.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

public final class LatencyPrefs {

    private static final String TAG       = "LatencyPrefs";
    private static final String PREFS_NAME = "audiomesh_latency";

    // How large a change (ms) triggers an auto-update of the saved measured value
    public static final long SESSION_UPDATE_THRESHOLD_MS = 20L;

    private static String deviceSuffix() {
        return Build.MODEL.replace(' ', '_') + "__api" + Build.VERSION.SDK_INT;
    }

    // ── Key helpers ───────────────────────────────────────────────────────────

    /** AudioTrack reflection / C++ geometry measurement — most reliable baseline */
    private static String measuredKey() {
        return "hw_latency_ms__" + deviceSuffix();
    }

    /** Mic loopback calibration result — may be lower than measured */
    private static String calibKey() {
        return "calib_hw_latency_ms__" + deviceSuffix();
    }

    private static String sliderKey() {
        return "slider_offset_ms__rx_" + deviceSuffix();
    }

    private static final String KEY_SAMPLE_RATE = "sample_rate";

    // ── Measured hw latency (AudioTrack probe / C++ geometry) ─────────────────

    public static void saveMeasuredHwLatency(Context context, long ms) {
        prefs(context).edit().putLong(measuredKey(), ms).apply();
        Log.i(TAG, "Saved measured hw latency: " + ms + " ms");
    }

    public static long getSavedHwLatency(Context context) {
        return prefs(context).getLong(measuredKey(), 0L);
    }

    /**
     * Called at end of each receiver session with the EMA-converged drift.
     * If the implied true rxHw differs from the stored measured value by more
     * than SESSION_UPDATE_THRESHOLD_MS, we update the stored value automatically.
     *
     * @param context  app context
     * @param currentRxHwMs  the rxHw value the engine was using this session
     * @param emaDriftMs     the final EMA drift (negative = tablet late = rxHw too low)
     */
    public static void updateMeasuredFromSessionDrift(Context context,
                                                      long currentRxHwMs,
                                                      long emaDriftMs) {
        if (currentRxHwMs <= 0) return;

        // drift is negative when tablet plays late (rxHw too small)
        // corrected = currentRxHw + |drift| when drift negative
        long corrected = currentRxHwMs + emaDriftMs; // emaDriftMs is signed
        long stored    = getSavedHwLatency(context);

        long delta = Math.abs(corrected - stored);
        if (delta > SESSION_UPDATE_THRESHOLD_MS) {
            Log.i(TAG, "Session drift update: stored=" + stored
                    + " ms → corrected=" + corrected
                    + " ms (delta=" + delta + " ms, drift=" + emaDriftMs + " ms)");
            saveMeasuredHwLatency(context, corrected);
        } else {
            Log.i(TAG, "Session drift within threshold (" + delta
                    + " ms < " + SESSION_UPDATE_THRESHOLD_MS + " ms) — no update");
        }
    }

    // ── Calibration hw latency (mic loopback) ─────────────────────────────────

    public static void saveCalibHwLatency(Context context, long ms) {
        prefs(context).edit().putLong(calibKey(), ms).apply();
        Log.i(TAG, "Saved calib hw latency: " + ms + " ms");
    }

    public static long getSavedCalibHwLatency(Context context) {
        return prefs(context).getLong(calibKey(), 0L);
    }

    public static boolean hasCalibValue(Context context) {
        return prefs(context).contains(calibKey());
    }

    // ── Slider offset ─────────────────────────────────────────────────────────

    public static void saveSlider(Context context, long ms) {
        prefs(context).edit().putLong(sliderKey(), ms).apply();
    }

    public static long getSavedSlider(Context context) {
        return prefs(context).getLong(sliderKey(), 0L);
    }

    // ── Sample rate ───────────────────────────────────────────────────────────

    public static void saveSampleRate(Context context, int hz) {
        prefs(context).edit().putInt(KEY_SAMPLE_RATE, hz).apply();
    }

    public static long getSavedSampleRate(Context context) {
        return prefs(context).getInt(KEY_SAMPLE_RATE, 0);
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private LatencyPrefs() {}
}