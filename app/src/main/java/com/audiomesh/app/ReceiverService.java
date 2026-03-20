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

    // ── explicitStop_ — set ONLY via binder.stopAndLeave() ───────────────────
    // Gates whether onDestroy() tears down the native engine.
    //
    // The two valid stop paths are:
    //   1. User presses LEAVE MESH → binder.stopAndLeave() → sets flag → stopSelf()
    //   2. App finishes → AudioMeshActivity.onDestroy(isFinishing=true) calls
    //      binder.stopAndLeave() before stopService(), so flag is already true.
    //
    // Calling stopService() from the UI directly (without the binder) will NOT
    // stop the audio — the engine keeps running. This is intentional: it prevents
    // accidental engine teardown on navigation events.
    private boolean explicitStop_ = false;

    // ── WakeLock — keeps CPU alive when screen turns off ──────────────────────
    private PowerManager.WakeLock wakeLock_;

    // ── AudioFocus ────────────────────────────────────────────────────────────
    private AudioManager      audioManager_;
    private AudioFocusRequest audioFocusRequest_;
    private boolean           audioFocusHeld_ = false;

    private final Handler hwPollHandler_ = new Handler(Looper.getMainLooper());
    private boolean       hwLatencySaved_ = false;

    private static ReceiverService sInstance = null;

    public static LocalBinder getBinder() {
        return sInstance != null ? (LocalBinder) sInstance.binder : null;
    }

    // ── Binder ────────────────────────────────────────────────────────────────

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

        // ── switchSender ──────────────────────────────────────────────────────
        // Triggers rehandshakeInPlace_ in ReceiverEngine — seamless reconnect
        // without tearing down the AAudio stream. Used for both "switch sender"
        // and "role change" flows. Pass "" to auto-discover.
        public void switchSender(String newIP) {
            if (nativeHandle != 0)
                NativeEngine.receiverSwitchSender(nativeHandle, newIP);
        }

        public String getConnectionStatus() {
            if (nativeHandle == 0) return "";
            return NativeEngine.receiverGetConnectionStatus(nativeHandle);
        }

        public void reconnect(String newIp) {
            if (nativeHandle != 0)
                NativeEngine.receiverSwitchSender(nativeHandle, newIp);
        }

        public boolean isConnected() {
            if (nativeHandle == 0) return false;
            String status = NativeEngine.receiverGetConnectionStatus(nativeHandle);
            return status != null && status.startsWith("HANDSHAKE_OK");
        }

        // ── stopAndLeave — THE correct way to stop from the UI ────────────────
        // Sets explicitStop_ = true so onDestroy() tears down the native engine,
        // then calls stopSelf() to trigger the service lifecycle.
        //
        // Do NOT call stopService() from the UI directly — it bypasses this flag
        // and the engine keeps running (audio keeps playing).
        public void stopAndLeave() {
            Log.i(TAG, "stopAndLeave() called — tearing down receiver");
            explicitStop_ = true;
            stopSelf();
        }

        // Legacy alias — kept so MainActivity.java still compiles
        public void leaveAndStop() {
            stopAndLeave();
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

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        createNotificationChannel();

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock_ = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "AudioMesh:ReceiverWakeLock");
        wakeLock_.setReferenceCounted(false);
        wakeLock_.acquire();
        Log.i(TAG, "WakeLock acquired");

        audioManager_ = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        requestAudioFocus();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Never run receiver on the same device as an active sender
        if (SenderService.getBinder() != null) {
            Log.w(TAG, "SenderService is running — refusing to start receiver");
            stopSelf();
            return START_NOT_STICKY;
        }

        // Reset on every fresh start so a restarted service doesn't carry
        // over a stale true value from a previous session.
        explicitStop_ = false;

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

                hwLatencySaved_ = (LatencyPrefs.getSavedHwLatency(this) > 0
                        || LatencyPrefs.hasCalibValue(this));

                if (!hwLatencySaved_) {
                    scheduleHwLatencyPoll();
                }
            }
        }, "ReceiverStartThread").start();

        // ── START_NOT_STICKY ──────────────────────────────────────────────────
        // Do NOT restart automatically if killed by Android.
        //
        // WHY: START_STICKY caused a reconnect storm on low-RAM devices.
        // When Android killed the service under memory pressure, it restarted
        // it immediately. The restart tore down and rebuilt the native engine,
        // which the sender saw as a dead client drop. The receiver reconnected.
        // Android killed it again. This loop made the audio completely inaudible.
        //
        // With START_NOT_STICKY the service stops cleanly when killed. The
        // MiniPlayerBar dims (isConnected() returns false) and the user can tap
        // it to rejoin. This is far better than invisible thrashing.
        return START_NOT_STICKY;
    }

    private long determineRxHwMs(Intent intent) {
        // Source 1: mic-loopback calibration — most accurate
        if (LatencyPrefs.hasCalibValue(this)) {
            long calibMs = LatencyPrefs.getSavedCalibHwLatency(this);
            if (calibMs > 0) {
                Log.i(TAG, "rxHw source=calib  value=" + calibMs + " ms");
                return calibMs;
            }
        }

        // Source 2: saved measured value from a prior session
        long savedMs = LatencyPrefs.getSavedHwLatency(this);
        if (savedMs > 0) {
            Log.i(TAG, "rxHw source=saved  value=" + savedMs + " ms");
            return savedMs;
        }

        // Source 3: value passed via Intent from MainActivity
        if (intent != null) {
            long intentMs = intent.getLongExtra("savedHwLatencyMs", 0L);
            if (intentMs > 0) {
                Log.i(TAG, "rxHw source=intent  value=" + intentMs + " ms");
                return intentMs;
            }
        }

        // Source 4: AudioTrack.getLatency() via reflection
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

            java.lang.reflect.Method m = android.media.AudioTrack.class.getMethod("getLatency");
            int reflectedMs = (int) m.invoke(probe);
            probe.release();

            if (reflectedMs > 10 && reflectedMs < 2000) {
                Log.i(TAG, "rxHw source=reflection  value=" + reflectedMs + " ms");
                LatencyPrefs.saveMeasuredHwLatency(this, reflectedMs);
                return reflectedMs;
            }
            Log.w(TAG, "rxHw reflection returned implausible value: " + reflectedMs + " ms");
        } catch (Exception e) {
            Log.w(TAG, "rxHw reflection probe failed: " + e.getMessage());
        }

        // Source 5: AudioManager geometry estimate
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
                            Log.i(TAG, "rxHw source=geometry  estimate=" + geometryMs + " ms");
                            return geometryMs;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "rxHw geometry estimate failed: " + e.getMessage());
        }

        // Source 6: give up — C++ engine self-measures at runtime
        Log.i(TAG, "rxHw source=none — C++ engine will self-measure at runtime");
        return 0L;
    }

    @Override
    public void onDestroy() {
        sInstance = null;

        if (nativeHandle != 0) {
            if (explicitStop_) {
                // User explicitly left — write back EMA drift for future sessions
                long currentRxHwMs = NativeEngine.receiverGetMeasuredHwLatencyMs(nativeHandle);
                long emaDriftMs    = NativeEngine.receiverGetEmaDriftMs(nativeHandle);
                if (currentRxHwMs > 0) {
                    LatencyPrefs.updateMeasuredFromSessionDrift(this, currentRxHwMs, emaDriftMs);
                }
                Log.i(TAG, "Engine torn down (explicit leave)");
            } else {
                Log.i(TAG, "Engine torn down (system kill or implicit stop)");
            }
            NativeEngine.receiverStop(nativeHandle);
            NativeEngine.receiverDestroy(nativeHandle);
            nativeHandle = 0;
        }

        abandonAudioFocus();

        if (wakeLock_ != null && wakeLock_.isHeld()) {
            wakeLock_.release();
            Log.i(TAG, "WakeLock released");
        }
        hwPollHandler_.removeCallbacksAndMessages(null);

        super.onDestroy();
    }

    // ── AudioFocus ────────────────────────────────────────────────────────────

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
                            Log.i(TAG, "AudioFocus: GAIN");
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS:
                            Log.i(TAG, "AudioFocus: LOSS — stopping receiver");
                            LocalBinder b = getBinder();
                            if (b != null) b.stopAndLeave();
                            else stopSelf();
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                            Log.i(TAG, "AudioFocus: LOSS_TRANSIENT — flushing");
                            if (nativeHandle != 0)
                                NativeEngine.receiverFlushAndSilence(nativeHandle);
                            break;
                    }
                })
                .build();

        int result = audioManager_.requestAudioFocus(audioFocusRequest_);
        audioFocusHeld_ = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        Log.i(TAG, "AudioFocus: " + (audioFocusHeld_ ? "GRANTED" : "DELAYED/FAILED"));
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

    // ── Geometry poll — first-boot devices only ───────────────────────────────

    private void scheduleHwLatencyPoll() {
        hwPollHandler_.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (nativeHandle == 0 || hwLatencySaved_) return;
                long measuredMs = NativeEngine.receiverGetMeasuredHwLatencyMs(nativeHandle);
                if (measuredMs > 0) {
                    LatencyPrefs.saveMeasuredHwLatency(ReceiverService.this, measuredMs);
                    hwLatencySaved_ = true;
                    Log.i(TAG, "First-boot geometry measurement saved: " + measuredMs + " ms");
                } else {
                    hwPollHandler_.postDelayed(this, 500);
                }
            }
        }, 2500);
    }
}