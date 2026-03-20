package com.audiomesh.app;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.*;
import android.util.Log;
import androidx.core.app.NotificationCompat;

public class ReceiverService extends Service {

    private static final String TAG        = "ReceiverService";
    private static final String CHANNEL_ID = "audiomesh_receiver";
    private static final int    NOTIF_ID   = 1002;

    private long nativeHandle = 0;

    private boolean explicitStop_ = false;

    // ── WakeLock — keeps CPU alive when screen turns off ──────────────────────
    private PowerManager.WakeLock wakeLock_;

    // ── AudioFocus — plays nicely with phone calls, other apps ────────────────
    private AudioManager      audioManager_;
    private AudioFocusRequest audioFocusRequest_;
    private boolean           audioFocusHeld_ = false;

    private final Handler hwPollHandler_ = new Handler(Looper.getMainLooper());
    private boolean       hwLatencySaved_ = false;
    private static ReceiverService sInstance = null;

    public static LocalBinder getBinder() {
        return sInstance != null ? (LocalBinder) sInstance.binder : null;
    }
// ─────────────────────────────────────────────────────────────────────────────    // ─────────────────────────────────────────────────────────────────────────

    // ── Binder — lets MainActivity push live latency updates ──────────────────
    public class LocalBinder extends Binder {

        public void setLatencyMs(int ms) {
            if (nativeHandle != 0)
                NativeEngine.receiverSetLatency(nativeHandle, ms);
        }

        public long getMeasuredHwLatencyMs() {
            if (nativeHandle == 0) return 0L;
            return NativeEngine.receiverGetMeasuredHwLatencyMs(nativeHandle);
        }

        public void setHwLatencyMs(int ms) {
            if (nativeHandle != 0 && ms > 0)
                NativeEngine.receiverSetSavedHwLatencyMs(nativeHandle, (long) ms);
        }

        public String getSenderIP() {
            if (nativeHandle == 0) return "";
            return NativeEngine.receiverGetSenderIP(nativeHandle);
        }

        public long getClockOffsetNs() {
            if (nativeHandle == 0) return 0L;
            return NativeEngine.receiverGetClockOffsetNs(nativeHandle);
        }

        public long getEmaDriftMs() {
            if (nativeHandle == 0) return 0L;
            return NativeEngine.receiverGetEmaDriftMs(nativeHandle);
        }

        public String getAssignedRole() {
            if (nativeHandle == 0) return "full";
            return NativeEngine.receiverGetAssignedRole(nativeHandle);
        }

        public void switchSender(String newIP) {
            if (nativeHandle != 0)
                NativeEngine.receiverSwitchSender(nativeHandle, newIP);
        }

        public String getConnectionStatus() {
            if (nativeHandle == 0) return "";
            return NativeEngine.receiverGetConnectionStatus(nativeHandle);
        }
        // After getConnectionStatus():

        public void reconnect(String newIp) {
            if (nativeHandle != 0)
                NativeEngine.receiverSwitchSender(nativeHandle, newIp);
        }

        public boolean isConnected() {
            if (nativeHandle == 0) return false;
            String status = NativeEngine.receiverGetConnectionStatus(nativeHandle);
            return status != null && status.startsWith("HANDSHAKE_OK");
        }

        public void leaveAndStop() {
            explicitStop_ = true;
            stopSelf();
        }

        public String getTrackTitle() {
            if (nativeHandle == 0) return "";
            return NativeEngine.receiverGetTrackTitle(nativeHandle);
        }
        public String getTrackArtist() {
            if (nativeHandle == 0) return "";
            return NativeEngine.receiverGetTrackArtist(nativeHandle);
        }
        public String getPaletteHex1() {
            if (nativeHandle == 0) return "";
            return NativeEngine.receiverGetPaletteHex1(nativeHandle);
        }
        public String getPaletteHex2() {
            if (nativeHandle == 0) return "";
            return NativeEngine.receiverGetPaletteHex2(nativeHandle);
        }
        public long getPositionMs() {
            if (nativeHandle == 0) return 0L;
            return NativeEngine.receiverGetCurrentPositionMs(nativeHandle);
        }
        public long getDurationMs() {
            if (nativeHandle == 0) return 0L;
            return NativeEngine.receiverGetTrackDurationMs(nativeHandle);
        }

    }


    private final IBinder binder = new LocalBinder();

    @Override public IBinder onBind(Intent intent) { return binder; }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
        public void onCreate() {
        super.onCreate();
        sInstance = this;
        createNotificationChannel();
        // ── Acquire WakeLock ──────────────────────────────────────────────────
        // PARTIAL_WAKE_LOCK: keeps CPU + network alive, screen can turn off.
        // Essential for the receiver — without it Android kills the network
        // thread within ~60s of the screen turning off.
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock_ = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "AudioMesh:ReceiverWakeLock");
        wakeLock_.setReferenceCounted(false);
        wakeLock_.acquire();
        Log.i(TAG, "WakeLock acquired");

        // ── Request AudioFocus ────────────────────────────────────────────────
        audioManager_ = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        requestAudioFocus();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Never run receiver on the same device as an active sender
        if (SenderService.getBinder() != null) {
            Log.w(TAG, "SenderService is running on this device — refusing to start receiver");
            stopSelf();
            return START_NOT_STICKY;
        }
        explicitStop_ = false;   // ← reset on every start/restart
        startForeground(NOTIF_ID, buildNotification("Receiver starting…"));

        String role      = intent != null ? intent.getStringExtra("role")        : "full";
        long   latencyNs = intent != null ? intent.getLongExtra("latencyNs", 0L) : 0L;
        if (role == null) role = "full";

        final String finalRole   = role;
        final long   finalLatNs  = latencyNs;
        final Intent finalIntent = intent;

        new Thread(() -> {
            if (nativeHandle == 0)
                nativeHandle = NativeEngine.receiverCreate();

            long rxHwMs = determineRxHwMs(finalIntent);
            if (rxHwMs > 0) {
                NativeEngine.receiverSetSavedHwLatencyMs(nativeHandle, rxHwMs);
                Log.i(TAG, "rxHw pre-loaded: " + rxHwMs + " ms");
            }

            boolean ok = NativeEngine.receiverStart(nativeHandle, finalRole, finalLatNs);
            if (!ok) {
                updateNotification("Receiver failed to start");
                stopSelf();
            } else {
                updateNotification("Discovering sender… role=" + finalRole);

                // Check if we already have a reliable calibration or saved value
                hwLatencySaved_ = (LatencyPrefs.getSavedHwLatency(this) > 0
                        || LatencyPrefs.hasCalibValue(this));

                // If this is a first-boot (no saved data), start the geometry poll
                if (!hwLatencySaved_) {
                    scheduleHwLatencyPoll();
                }
            }
        }, "ReceiverStartThread").start();

        return START_STICKY;
    }

    /*
     * Determines the best rxHw estimate using this priority:
     *
     * 1. AudioTrack.getLatency() via reflection  — full pipeline, most accurate
     * 2. Previously saved value from SharedPreferences — instant, no measurement
     * 3. savedHwLatencyMs from Intent (passed by MainActivity from SharedPrefs)
     * 4. Zero — C++ engine will fall back to geometry + getTimestamp warmup
     */
    /*
     * Determines the best rxHw estimate using this priority:
     *
     * 1. Saved mic-loopback calibration (CalibEngine result) — most accurate, user-verified
     * 2. Saved measured value from a prior session (geometry or EMA-corrected)
     * 3. savedHwLatencyMs extra from Intent (passed by MainActivity from SharedPrefs)
     * 4. AudioTrack.getLatency() via reflection — full pipeline probe, no user action needed
     * 5. AudioManager geometry estimate — rough (framesPerBuffer / sampleRate * 3 buffers)
     * 6. Return 0 — C++ engine runs getTimestamp geometry + EMA corrects at runtime
     *
     * The per-device SharedPreferences key includes Build.MODEL + API level, so each
     * Android model gets its own saved value and never pollutes another device's record.
     */
    private long determineRxHwMs(Intent intent) {

        // ── Source 1: mic-loopback calibration result ─────────────────────────────
        // Written by CalibEngine on completion via LatencyPrefs.saveCalibHwLatency().
        // Most accurate (±1 ms). Use it unconditionally when present.
        if (LatencyPrefs.hasCalibValue(this)) {
            long calibMs = LatencyPrefs.getSavedCalibHwLatency(this);
            if (calibMs > 0) {
                Log.i(TAG, "rxHw source=calib  value=" + calibMs + " ms");
                return calibMs;
            }
        }

        // ── Source 2: saved measured value from a prior session ───────────────────
        // Written either by pollForMeasuredHwLatency() (geometry probe) or by
        // LatencyPrefs.updateMeasuredFromSessionDrift() at session end.
        long savedMs = LatencyPrefs.getSavedHwLatency(this);
        if (savedMs > 0) {
            Log.i(TAG, "rxHw source=saved  value=" + savedMs + " ms");
            return savedMs;
        }

        // ── Source 3: value passed via Intent from MainActivity ───────────────────
        // MainActivity reads SharedPrefs and puts the value in the Intent extra so
        // the service has it even before SharedPrefs is available on this thread.
        if (intent != null) {
            long intentMs = intent.getLongExtra("savedHwLatencyMs", 0L);
            if (intentMs > 0) {
                Log.i(TAG, "rxHw source=intent  value=" + intentMs + " ms");
                return intentMs;
            }
        }

        // ── Source 4: AudioTrack.getLatency() via reflection ──────────────────────
        // Creates a minimal silent AudioTrack, queries its hidden getLatency() method
        // which returns the full output pipeline depth in ms including the HAL.
        // Stable across Android 5–14. Skipped on failure — reflection can throw on
        // some OEM builds with strict reflection policies.
        try {
            android.media.AudioTrack probe = new android.media.AudioTrack.Builder()
                    .setAudioAttributes(new android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new android.media.AudioFormat.Builder()
                            .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(44100)
                            .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(
                            android.media.AudioTrack.getMinBufferSize(
                                    44100,
                                    android.media.AudioFormat.CHANNEL_OUT_MONO,
                                    android.media.AudioFormat.ENCODING_PCM_16BIT))
                    .setTransferMode(android.media.AudioTrack.MODE_STREAM)
                    .build();

            java.lang.reflect.Method m = android.media.AudioTrack.class
                    .getMethod("getLatency");
            int reflectedMs = (int) m.invoke(probe);
            probe.release();

            if (reflectedMs > 10 && reflectedMs < 2000) {
                Log.i(TAG, "rxHw source=reflection  value=" + reflectedMs + " ms");
                // Save it so we don't need reflection on the next boot
                LatencyPrefs.saveMeasuredHwLatency(this, reflectedMs);
                return reflectedMs;
            }
            Log.w(TAG, "rxHw reflection returned implausible value: " + reflectedMs + " ms — skipping");
        } catch (Exception e) {
            Log.w(TAG, "rxHw reflection probe failed: " + e.getMessage());
        }

        // ── Source 5: AudioManager geometry estimate ──────────────────────────────
        // framesPerBuffer / sampleRate * N_BUFFERS. N_BUFFERS is unknown; we use 3
        // as a conservative estimate. Accurate to ±50% but gives a usable ballpark
        // for a first-boot device where nothing else is available.
        try {
            android.media.AudioManager am =
                    (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                String framesStr = am.getProperty(
                        android.media.AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER);
                String rateStr   = am.getProperty(
                        android.media.AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
                if (framesStr != null && rateStr != null) {
                    int frames = Integer.parseInt(framesStr);
                    int rate   = Integer.parseInt(rateStr);
                    if (frames > 0 && rate > 0) {
                        long geometryMs = (long) frames * 3 * 1000L / rate;
                        if (geometryMs > 10 && geometryMs < 500) {
                            Log.i(TAG, "rxHw source=geometry  frames=" + frames
                                    + "  rate=" + rate + "  estimate=" + geometryMs + " ms");
                            return geometryMs;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "rxHw geometry estimate failed: " + e.getMessage());
        }

        // ── Source 6: give up — let C++ handle it ─────────────────────────────────
        // ReceiverEngine will run its own getTimestamp() geometry probe after stream
        // open, and EMA drift correction will converge the remaining error at runtime.
        Log.i(TAG, "rxHw source=none — C++ engine will self-measure at runtime");
        return 0L;
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        if (explicitStop_ && nativeHandle != 0) {
            // ── EMA drift write-back ──────────────────────────────────────────────
            // Read the EMA-converged drift BEFORE stopping the engine (the native
            // handle is still valid here). If the drift is large enough, update the
            // saved measured value so the next session starts from a better estimate.
            long currentRxHwMs = NativeEngine.receiverGetMeasuredHwLatencyMs(nativeHandle);
            long emaDriftMs     = NativeEngine.receiverGetEmaDriftMs(nativeHandle);
            if (currentRxHwMs > 0) {
                LatencyPrefs.updateMeasuredFromSessionDrift(this, currentRxHwMs, emaDriftMs);
            }

            NativeEngine.receiverStop(nativeHandle);
            NativeEngine.receiverDestroy(nativeHandle);
            nativeHandle = 0;
        }

        // ── Release AudioFocus ────────────────────────────────────────────────
        abandonAudioFocus();

        // ── Release WakeLock ──────────────────────────────────────────────────
        if (wakeLock_ != null && wakeLock_.isHeld()) {
            wakeLock_.release();
            Log.i(TAG, "WakeLock released");
        }
        hwPollHandler_.removeCallbacksAndMessages(null);

        super.onDestroy();
    }

    // ── AudioFocus helpers ────────────────────────────────────────────────────

    private void requestAudioFocus() {
        if (audioManager_ == null) return;

        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();

        audioFocusRequest_ = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(focusChange -> {
                    switch (focusChange) {
                        case AudioManager.AUDIOFOCUS_GAIN:
                            // Regained focus — nothing to do on the receiver side.
                            // The receiver doesn't control play/pause independently;
                            // the sender drives it. Just log it.
                            Log.i(TAG, "AudioFocus: GAIN");
                            break;

                        case AudioManager.AUDIOFOCUS_LOSS:
                            // Permanent focus loss (e.g. another music app).
                            // Stop the receiver service — the user will restart it.
                            Log.i(TAG, "AudioFocus: LOSS — stopping receiver");
                            stopSelf();
                            break;

                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                            Log.i(TAG, "AudioFocus: LOSS_TRANSIENT — flushing receiver immediately");
                            if (nativeHandle != 0)
                                NativeEngine.receiverFlushAndSilence(nativeHandle);
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                            Log.i(TAG, "AudioFocus: LOSS_TRANSIENT_CAN_DUCK — flushing receiver (no duck)");
                            if (nativeHandle != 0)
                                NativeEngine.receiverFlushAndSilence(nativeHandle);
                            break;
                    }
                })
                .build();

        int result = audioManager_.requestAudioFocus(audioFocusRequest_);
        audioFocusHeld_ = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        Log.i(TAG, "AudioFocus request result: "
                + (audioFocusHeld_ ? "GRANTED" : "DELAYED/FAILED"));
    }

    private void abandonAudioFocus() {
        if (audioManager_ != null && audioFocusRequest_ != null && audioFocusHeld_) {
            audioManager_.abandonAudioFocusRequest(audioFocusRequest_);
            audioFocusHeld_ = false;
            Log.i(TAG, "AudioFocus abandoned");
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "AudioMesh Receiver", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("AudioMesh — Receiver")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
    }
    // ── Geometry poll — only runs on first-boot devices ───────────────────────
    private void scheduleHwLatencyPoll() {
        hwPollHandler_.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (nativeHandle == 0 || hwLatencySaved_) return;

                // Ask C++ for the measured hardware delay
                long measuredMs = NativeEngine.receiverGetMeasuredHwLatencyMs(nativeHandle);

                if (measuredMs > 0) {
                    LatencyPrefs.saveMeasuredHwLatency(ReceiverService.this, measuredMs);
                    hwLatencySaved_ = true;
                    Log.i(TAG, "First-boot geometry measurement saved: " + measuredMs + " ms");
                } else {
                    // C++ engine still warming up, check again in 500ms
                    hwPollHandler_.postDelayed(this, 500);
                }
            }
        }, 2500); // Wait 2.5s initially for the AAudio stream to stabilize
    }
}