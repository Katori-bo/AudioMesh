    package com.audiomesh.app;

    import android.app.*;
    import android.content.Context;
    import android.content.Intent;
    import android.media.AudioAttributes;
    import android.media.AudioFocusRequest;
    import android.media.AudioManager;
    import android.os.*;
    import android.util.Log;

    import java.net.InetAddress;
    import java.net.NetworkInterface;
    import java.util.Arrays;
    import java.util.Collections;
    import java.util.List;

    public class SenderService extends Service {

        private static final String TAG     = "SenderService";
        private static final String CHANNEL = "audiomesh_sender";
        private static final int    NOTIF   = 1;

        private long nativeHandle = 0;

        // ── WakeLock — keeps CPU alive when screen turns off ──────────────────────
        // Without this, Android throttles the CPU and kills network within ~60s of
        // the screen turning off, breaking audio for all connected receivers.
        private PowerManager.WakeLock wakeLock_;

        // ── AudioFocus — plays nicely with phone calls, other apps ────────────────
        // Without this, music keeps playing under phone calls, or gets killed
        // unexpectedly when another app requests audio focus.
        private AudioManager        audioManager_;
        private AudioFocusRequest   audioFocusRequest_;  // API 26+
        private boolean             audioFocusHeld_ = false;

        // ─────────────────────────────────────────────────────────────────────────

        // ── Local binder — exposes pause/resume/seek to MainActivity ─────────────
        private static SenderService sInstance = null;

        private static Runnable onStoppedCallback = null;

        public static void setOnStoppedCallback(Runnable cb) {
            onStoppedCallback = cb;
        }

        public static LocalBinder getBinder() {
            return sInstance != null ? sInstance.binder : null;
        }

        private final LocalBinder binder = new LocalBinder();

        public class LocalBinder extends android.os.Binder {
            public void pause() {
                if (nativeHandle == 0) return;
                new Thread(() -> NativeEngine.senderPause(nativeHandle), "SenderPause").start();
            }
            public void resume() {
                if (nativeHandle == 0) return;
                new Thread(() -> NativeEngine.senderResume(nativeHandle), "SenderResume").start();
            }
            public boolean isPaused() {
                return nativeHandle != 0 && NativeEngine.senderIsPaused(nativeHandle);
            }
            public void seekToMs(long ms) {
                if (nativeHandle == 0) return;
                new Thread(() -> NativeEngine.senderSeekToMs(nativeHandle, ms), "SenderSeek").start();
            }
            public long getPositionMs()  { return nativeHandle != 0 ? NativeEngine.senderGetPositionMs(nativeHandle) : 0L; }
            public long getDurationMs()  { return nativeHandle != 0 ? NativeEngine.senderGetDurationMs(nativeHandle) : 0L; }

            public String getClientStats() {
                if (nativeHandle == 0) return "";
                return NativeEngine.senderGetClientStats(nativeHandle);
            }

            public void setLocalRole(String role, float bassCutHz, float trebleCutHz) {
                if (nativeHandle == 0) return;
                NativeEngine.senderSetLocalRole(nativeHandle, role, bassCutHz, trebleCutHz);
            }

            // AFTER
            public long getNativeHandle() {
                return nativeHandle;
            }

            public void swapTrack(String mp3Uri, String title, String artist,
                                  String paletteHex1, String paletteHex2) {
                if (nativeHandle == 0) return;
                int fd = MainActivity.openFdFromUri(SenderService.this, mp3Uri);
                if (fd < 0) {
                    Log.e(TAG, "swapTrack: failed to open fd for " + mp3Uri);
                    return;
                }
                new Thread(() -> {
                    NativeEngine.senderSetTrackInfo(nativeHandle, title, artist);
                    NativeEngine.senderSetPaletteHex(nativeHandle, paletteHex1, paletteHex2);
                    NativeEngine.senderSwapTrack(nativeHandle, fd);
                    updateNotification("Now playing — " + title);
                    Log.i(TAG, "swapTrack complete: " + title);
                }, "SenderSwapTrack").start();
            }

            public long getClientPingMs(String addr) {
                if (nativeHandle == 0) return -1L;
                return NativeEngine.senderGetClientPingMs(nativeHandle, addr);
            }
            public java.util.Map<String, Long> getReceiverPings() {
                java.util.Map<String, Long> result = new java.util.HashMap<>();
                if (nativeHandle == 0) return result;
                String stats = NativeEngine.senderGetClientStats(nativeHandle);
                Log.d("PING_DEBUG", "raw stats: " + stats); // ← ADD THIS

                if (stats == null || stats.isEmpty()) return result;
                for (String line : stats.split("\n")) {
                    if (line.trim().isEmpty()) continue;
                    String[] parts = line.trim().split("\\|");
                    if (parts.length >= 1) {
                        String ip = parts[0].trim();
                        // ping via existing JNI call per client
                        long ping = NativeEngine.senderGetClientPingMs(nativeHandle, ip);
                        if (!ip.isEmpty()) result.put(ip, ping);
                    }
                }
                return result;
            }

        }
        @Override
        public void onCreate() {
            super.onCreate();
            sInstance = this;

            // ── Acquire WakeLock ──────────────────────────────────────────────────
            // PARTIAL_WAKE_LOCK: keeps CPU running but lets screen turn off.
            // This is the correct lock for background audio — we don't need the
            // screen on, just the CPU and WiFi.
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock_ = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "AudioMesh:SenderWakeLock");
            wakeLock_.setReferenceCounted(false);
            wakeLock_.acquire();
            Log.i(TAG, "WakeLock acquired");

            // ── Request AudioFocus ────────────────────────────────────────────────
            audioManager_ = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            requestAudioFocus();
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            startForeground(NOTIF, buildNotification("Sender starting…"));

            if (nativeHandle != 0) {
                Log.w(TAG, "Engine already running — ignoring duplicate start");
                return START_NOT_STICKY;
            }

// AFTER
            final String  mp3Uri    = intent != null ? intent.getStringExtra("mp3Uri")    : null;
            final String  title     = intent != null ? intent.getStringExtra("title")     : "";
            final String  artist    = intent != null ? intent.getStringExtra("artist")    : "";
            final String  palHex1   = intent != null ? intent.getStringExtra("palHex1")   : "";
            final String  palHex2   = intent != null ? intent.getStringExtra("palHex2")   : "";
            final boolean localOnly = intent != null && intent.getBooleanExtra("localOnly", false);
            new Thread(() -> {
                String ip = "127.0.0.1"; // fallback for local-only mode

                if (!localOnly) {
                    ip = null;
                    for (int i = 0; i < 10; i++) {
                        ip = detectHotspotIP();
                        if (ip != null) break;
                        Log.w(TAG, "IP detection attempt " + (i+1) + " failed, retrying…");
                        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                    }

                    if (ip == null) {
                        Log.e(TAG, "Could not detect hotspot IP after 10 attempts — stopping");
                        updateNotification("No hotspot IP found — enable hotspot first");
                        stopSelf();
                        return;
                    }
                }

                Log.i(TAG, "Using IP: " + ip + " (localOnly=" + localOnly + ")");

                nativeHandle = NativeEngine.senderCreate();
                boolean ok   = NativeEngine.senderStart(nativeHandle, ip, localOnly);
                if (!ok) {
                    Log.e(TAG, "senderStart() failed");
                    NativeEngine.senderDestroy(nativeHandle);
                    nativeHandle = 0;
                    stopSelf();
                    return;
                }

                Log.i(TAG, "Sender engine started on " + ip);
                updateNotification("Sender running — " + ip);

                // AFTER
                if (mp3Uri != null) {
                    int fd = MainActivity.openFdFromUri(SenderService.this, mp3Uri);
                    if (fd >= 0) {
                        if (title  != null && !title.isEmpty())
                            NativeEngine.senderSetTrackInfo(nativeHandle, title, artist);
                        if (palHex1 != null && !palHex1.isEmpty())
                            NativeEngine.senderSetPaletteHex(nativeHandle, palHex1, palHex2);
                        NativeEngine.senderSetFd(nativeHandle, fd);
                        NativeEngine.senderStartStreaming(nativeHandle);
                        Log.i(TAG, "Streaming started — localOnly=" + localOnly);
                        updateNotification("Now playing — " + (title != null && !title.isEmpty() ? title : ip));
                    } else {
                        Log.e(TAG, "Failed to open fd for URI: " + mp3Uri);
                        updateNotification("Sender running — could not open MP3");
                    }
                } else {
                    Log.w(TAG, "No MP3 URI — handshake-only mode");
                    updateNotification("Sender running — no audio (pick MP3)");
                }
            }, "SenderStartThread").start();

            return START_NOT_STICKY;
        }

        @Override
        public void onDestroy() {
            sInstance = null;

            if (nativeHandle != 0) {
                NativeEngine.senderStopStreaming(nativeHandle);
                NativeEngine.senderStop(nativeHandle);
                NativeEngine.senderDestroy(nativeHandle);
                nativeHandle = 0;
                Log.i(TAG, "Sender engine destroyed");
            }

            // ── Release AudioFocus ────────────────────────────────────────────────
            abandonAudioFocus();

            // ── Release WakeLock ──────────────────────────────────────────────────
            if (wakeLock_ != null && wakeLock_.isHeld()) {
                wakeLock_.release();
                Log.i(TAG, "WakeLock released");
            }


            super.onDestroy();
            if (onStoppedCallback != null) {
                Runnable cb = onStoppedCallback;
                onStoppedCallback = null;
                new android.os.Handler(android.os.Looper.getMainLooper()).post(cb);
            }
        }
        @Override public IBinder onBind(Intent intent) { return binder; }

        // ── AudioFocus helpers ────────────────────────────────────────────────────

        private void requestAudioFocus() {
            if (audioManager_ == null) return;

            // AudioFocusRequest (API 26+) is the modern way.
            // We request GAIN (long-term focus for music playback) and tell the
            // system we play music so it ducks other apps properly.
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();

            audioFocusRequest_ = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .setAcceptsDelayedFocusGain(true)
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener(focusChange -> {
                        switch (focusChange) {
                            case AudioManager.AUDIOFOCUS_GAIN:
                                // We regained focus (e.g. after a phone call ended).
                                // Resume playback if we were paused due to focus loss.
                                Log.i(TAG, "AudioFocus: GAIN — resuming if paused");
                                if (nativeHandle != 0 && NativeEngine.senderIsPaused(nativeHandle)) {
                                    new Thread(() -> NativeEngine.senderResume(nativeHandle),
                                            "SenderFocusResume").start();
                                }
                                break;

                            case AudioManager.AUDIOFOCUS_LOSS:
                                // Permanent loss (e.g. another music app took over).
                                // Pause our stream — the user can resume manually.
                                Log.i(TAG, "AudioFocus: LOSS — pausing");
                                if (nativeHandle != 0 && !NativeEngine.senderIsPaused(nativeHandle)) {
                                    new Thread(() -> NativeEngine.senderPause(nativeHandle),
                                            "SenderFocusPause").start();
                                }
                                break;

                            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                                // Short loss (e.g. navigation prompt, notification).
                                // Pause — we'll resume on GAIN.
                                Log.i(TAG, "AudioFocus: LOSS_TRANSIENT — pausing");
                                if (nativeHandle != 0 && !NativeEngine.senderIsPaused(nativeHandle)) {
                                    new Thread(() -> NativeEngine.senderPause(nativeHandle),
                                            "SenderFocusPause").start();
                                }
                                break;

                            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                                // Notification sound — do nothing, let the system mix both.
                                // Pausing here breaks sync on all connected receivers, which is
                                // worse than a brief volume overlap.
                                Log.i(TAG, "AudioFocus: LOSS_TRANSIENT_CAN_DUCK — ignoring, continuing playback");
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

        // ── IP detection ──────────────────────────────────────────────────────────

        private static String detectHotspotIP() {
            try {
                List<NetworkInterface> ifaces =
                        Collections.list(NetworkInterface.getNetworkInterfaces());

                List<String> hotspotNames = Arrays.asList("wlan_ap","swlan0","ap0","wlan1");
                for (NetworkInterface iface : ifaces) {
                    if (!iface.isUp() || iface.isLoopback()) continue;
                    if (!hotspotNames.contains(iface.getName())) continue;
                    String ip = firstIPv4(iface);
                    if (ip != null) { Log.i(TAG, "IP via " + iface.getName() + ": " + ip); return ip; }
                }
                for (NetworkInterface iface : ifaces) {
                    if (!iface.isUp() || iface.isLoopback()) continue;
                    String ip = firstIPv4(iface);
                    if (ip != null && ip.startsWith("192.168.43.")) return ip;
                }
                for (NetworkInterface iface : ifaces) {
                    if (!iface.isUp() || iface.isLoopback()) continue;
                    String ip = firstIPv4(iface);
                    if (ip != null && ip.startsWith("192.168.49.")) return ip;
                }
                for (NetworkInterface iface : ifaces) {
                    if (!iface.isUp() || iface.isLoopback()) continue;
                    String ip = firstIPv4(iface);
                    if (ip != null && ip.startsWith("192.168.")
                            && !ip.startsWith("192.168.100.")) return ip;
                }
            } catch (Exception e) {
                Log.e(TAG, "detectHotspotIP error: " + e.getMessage());
            }
            return null;
        }

        private static String firstIPv4(NetworkInterface iface) {
            for (InetAddress a : Collections.list(iface.getInetAddresses()))
                if (!a.isLoopbackAddress() && a.getAddress().length == 4)
                    return a.getHostAddress();
            return null;
        }

        // ── Notification ──────────────────────────────────────────────────────────

        private Notification buildNotification(String text) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(new NotificationChannel(
                        CHANNEL, "AudioMesh Sender", NotificationManager.IMPORTANCE_LOW));
            }
            return new Notification.Builder(this, CHANNEL)
                    .setContentTitle("AudioMesh — Sender")
                    .setContentText(text)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .build();
        }

        private void updateNotification(String text) {
            getSystemService(NotificationManager.class).notify(NOTIF, buildNotification(text));
        }
    }