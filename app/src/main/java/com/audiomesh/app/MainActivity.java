package com.audiomesh.app;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.Manifest;
import android.content.pm.PackageManager;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    // Slider: 0–400, centre 200 = 0 ms.  Range ±200 ms in 1 ms steps.
    private static final int SEEK_CENTRE = 500;

    private static final int REQUEST_RECORD_AUDIO = 1001;

    // ── UI ────────────────────────────────────────────────────────────────────
    private Button       btnSender, btnBass, btnMid, btnTreble, btnFull, btnStop;
    private Button       btnPickMp3;
    private Button       btnCalibrate;   // [CALIB] microphone loopback calibration
    private TextView     tvStatus, tvMp3Name, tvLatencyLabel;
    private TextView     tvCalibStatus;  // [CALIB] progress/result display
    private TextView     tvAssignedRole; // role confirmed by sender during handshake
    private LinearLayout layoutSwitchSender;
    private Button       btnSwitchSender, btnSwitchSenderManual;
    private LinearLayout layoutLatency;
    private SeekBar      seekLatency;
    // ── Playback controls (sender) ────────────────────────────────────────────
    private LinearLayout layoutPlaybackControls;
    private LinearLayout layoutDevices;
    private TextView     tvDeviceList;
    private SeekBar      seekTrack;
    private TextView     tvTrackPosition;
    private Button       btnPauseResume;
    private final Handler playbackPollHandler = new Handler(Looper.getMainLooper());
    private boolean      seekTrackDragging = false;

    // ── [CALIB] Calibration state ─────────────────────────────────────────────
    private long    calibHandle     = 0;    // CalibEngine native handle
    private boolean calibRunning    = false;
    private final Handler calibPollHandler = new Handler(Looper.getMainLooper());

    // ── State ─────────────────────────────────────────────────────────────────
    private boolean engineRunning = false;
    private String  selectedRole  = null;
    private Uri     selectedMp3Uri;
    private int     latencyTrimMs = 0;

    // ── Live link to ReceiverService ──────────────────────────────────────────
    private ReceiverService.LocalBinder receiverBinder = null;

    // ── [ADDED] Handler for polling measured hw latency after stream warms up ─
    private final Handler hwPollHandler = new Handler(Looper.getMainLooper());
    private boolean hwLatencySaved = false;

    private final ServiceConnection receiverConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            receiverBinder = (ReceiverService.LocalBinder) service;
            // Push current slider value in case it was moved before bind completed
            receiverBinder.setLatencyMs(latencyTrimMs);
            Log.i(TAG, "ReceiverService bound");
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            receiverBinder = null;
        }
    };

    // ── MP3 picker ────────────────────────────────────────────────────────────
    private final ActivityResultLauncher<Intent> mp3Picker =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK
                                && result.getData() != null) {
                            Uri uri = result.getData().getData();
                            if (uri == null) return;
                            getContentResolver().takePersistableUriPermission(
                                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            selectedMp3Uri = uri;
                            String name = uri.getLastPathSegment();
                            tvMp3Name.setText(name != null ? name : uri.toString());
                            tvMp3Name.setVisibility(View.VISIBLE);
                        }
                    });

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnSender      = findViewById(R.id.btnSender);
        btnBass        = findViewById(R.id.btnBass);
        btnMid         = findViewById(R.id.btnMid);
        btnTreble      = findViewById(R.id.btnTreble);
        btnFull        = findViewById(R.id.btnFull);
        btnStop        = findViewById(R.id.btnStop);
        btnPickMp3     = findViewById(R.id.btnPickMp3);
        tvStatus       = findViewById(R.id.statusText);
        tvMp3Name      = findViewById(R.id.tvMp3Name);
        layoutLatency  = findViewById(R.id.layoutLatency);
        seekLatency    = findViewById(R.id.seekLatency);
        tvLatencyLabel = findViewById(R.id.tvLatencyLabel);
// [CALIB]
        btnCalibrate   = findViewById(R.id.btnCalibrate);
        tvCalibStatus  = findViewById(R.id.tvCalibStatus);
        tvAssignedRole        = findViewById(R.id.tvAssignedRole);
        layoutSwitchSender    = findViewById(R.id.layoutSwitchSender);
        btnSwitchSender       = findViewById(R.id.btnSwitchSender);
        btnSwitchSenderManual = findViewById(R.id.btnSwitchSenderManual);

        btnSwitchSender.setOnClickListener(v -> {
            if (receiverBinder == null) return;
            // Auto re-discovery: pass empty string, receiver drops connection
            // and listens for UDP beacons from any sender
            receiverBinder.switchSender("");
            tvAssignedRole.setText("Role: —  [searching...]");
            Toast.makeText(this, "Searching for sender...", Toast.LENGTH_SHORT).show();
        });

        btnSwitchSenderManual.setOnClickListener(v -> showManualIPDialog());        layoutPlaybackControls = findViewById(R.id.layoutPlaybackControls);
        layoutDevices          = findViewById(R.id.layoutDevices);
        tvDeviceList           = findViewById(R.id.tvDeviceList);        seekTrack              = findViewById(R.id.seekTrack);
        tvTrackPosition        = findViewById(R.id.tvTrackPosition);
        btnPauseResume         = findViewById(R.id.btnPauseResume);

        btnSender.setOnClickListener(v -> {
            if (selectedRole != null && selectedRole.equals("sender") && !engineRunning)
                startEngine();
            else
                selectRole("sender");
        });
        btnBass  .setOnClickListener(v -> selectRole("bass"));
        btnMid   .setOnClickListener(v -> selectRole("mid"));
        btnTreble.setOnClickListener(v -> selectRole("treble"));
        btnFull  .setOnClickListener(v -> selectRole("full"));

        btnPickMp3.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("audio/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            mp3Picker.launch(i);
        });

        // ── [ADDED] Restore saved slider value from SharedPreferences ─────────
        // This runs before the slider listener is attached, so it won't trigger
        // a spurious save — it just sets the UI position silently.
        latencyTrimMs = (int) LatencyPrefs.getSavedSlider(this);
        seekLatency.setProgress(latencyTrimMs + SEEK_CENTRE);
        String initSign = latencyTrimMs >= 0 ? "+" : "";
        // tvLatencyLabel may not be visible yet but the value is ready for when it is

        // ── Latency slider ────────────────────────────────────────────────────
        seekLatency.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                latencyTrimMs = progress - SEEK_CENTRE;
                String sign = latencyTrimMs >= 0 ? "+" : "";
                tvLatencyLabel.setText("Latency trim: " + sign + latencyTrimMs + " ms");
                if (receiverBinder != null)
                    receiverBinder.setLatencyMs(latencyTrimMs);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                // [ADDED] Save slider value when the user lifts their finger
                LatencyPrefs.saveSlider(MainActivity.this, latencyTrimMs);
                Log.i(TAG, "Latency settled and saved: " + latencyTrimMs + " ms");
            }
        });

        btnStop.setOnClickListener(v -> stopEngine());

        // ── Pause / Resume ────────────────────────────────────────────────────
        btnPauseResume.setOnClickListener(v -> {
            SenderService.LocalBinder sb = SenderService.getBinder();
            if (sb == null) return;
            // Disable immediately to prevent double-tap while background thread runs
            btnPauseResume.setEnabled(false);
            if (sb.isPaused()) {
                sb.resume();
                btnPauseResume.setText("Pause");
                btnPauseResume.setBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(0xFF388E3C));
            } else {
                sb.pause();
                btnPauseResume.setText("Resume");
                btnPauseResume.setBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(0xFFF57C00));
            }
            // Re-enable after 2s — enough time for the background op to start
            btnPauseResume.postDelayed(() -> btnPauseResume.setEnabled(true), 2000);
        });

        // ── Track seek bar ────────────────────────────────────────────────────
        seekTrack.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                SenderService.LocalBinder binder = SenderService.getBinder();
                if (binder == null) return;
                long durationMs = binder.getDurationMs();
                long posMs      = (long) progress * durationMs / 1000L;
                tvTrackPosition.setText(formatMs(posMs) + " / " + formatMs(durationMs));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {
                seekTrackDragging = true;
            }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                seekTrackDragging = false;
                SenderService.LocalBinder binder = SenderService.getBinder();
                if (binder == null) return;
                long durationMs = binder.getDurationMs();
                long posMs      = (long) sb.getProgress() * durationMs / 1000L;
                binder.seekToMs(posMs);
            }
        });

        // [CALIB] Calibrate button — measure rxHw using mic loopback
        btnCalibrate.setOnClickListener(v -> {
            if (calibRunning) {
                stopCalibration();
            } else {
                // Need RECORD_AUDIO permission
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this,
                            new String[]{Manifest.permission.RECORD_AUDIO},
                            REQUEST_RECORD_AUDIO);
                } else {
                    startCalibration();
                }
            }
        });

        updateUI();
    }

    @Override
    protected void onDestroy() {
        // [ADDED] Cancel any pending hw-latency polls
        hwPollHandler.removeCallbacksAndMessages(null);
        playbackPollHandler.removeCallbacksAndMessages(null);
        // [CALIB]
        calibPollHandler.removeCallbacksAndMessages(null);
        stopCalibration();
        unbindReceiver();
        super.onDestroy();
    }

    // ─────────────────────────────────────────────────────────────────────────

    private void selectRole(String role) {
        if (engineRunning) return;
        selectedRole = role;
        btnPickMp3.setVisibility(role.equals("sender") ? View.VISIBLE : View.GONE);
        if (!role.equals("sender")) tvMp3Name.setVisibility(View.GONE);
        if (role.equals("sender")) {
            btnSender.setText("▶ Tap again to start");
            tvStatus.setText("Pick an MP3 (optional) then tap Start as Sender again");
        } else {
            startEngine();
        }
    }

    private void startEngine() {
        if (selectedRole == null) {
            Toast.makeText(this, "Choose a role first", Toast.LENGTH_SHORT).show();
            return;
        }

        if (selectedRole.equals("sender")) {
            String ip = getSenderIP();
            if (ip.equals("0.0.0.0")) {
                Toast.makeText(this,
                        "Could not detect hotspot IP — enable hotspot first.",
                        Toast.LENGTH_LONG).show();
                return;
            }
            Intent svc = new Intent(this, SenderService.class);
            svc.putExtra("ip", ip);
            if (selectedMp3Uri != null)
                svc.putExtra("mp3Uri", selectedMp3Uri.toString());
            startForegroundService(svc);
            tvStatus.setText("Sender running — " + ip);
            layoutPlaybackControls.setVisibility(View.VISIBLE);
            layoutDevices.setVisibility(View.VISIBLE);
            startPlaybackPolling();
            startDeviceStatsPolling();

        } else {
            long latencyNs = (long) latencyTrimMs * 1_000_000L;
            Intent svc = new Intent(this, ReceiverService.class);
            svc.putExtra("role", selectedRole);
            svc.putExtra("latencyNs", latencyNs);

            // [ADDED] Pre-load saved hw latency so the first chunks use the
            // correct pipeline depth before the 1.5 s warm-up finishes.
            long savedHwMs = LatencyPrefs.getSavedHwLatency(this);
            if (savedHwMs > 0) {
                svc.putExtra("savedHwLatencyMs", savedHwMs);
                Log.i(TAG, "Pre-loading saved hw latency: " + savedHwMs + " ms");
            }

            startForegroundService(svc);
            bindService(new Intent(this, ReceiverService.class),
                    receiverConn, Context.BIND_AUTO_CREATE);

            tvStatus.setText("Receiver running — " + selectedRole);
            layoutLatency.setVisibility(View.VISIBLE);
            String sign = latencyTrimMs >= 0 ? "+" : "";
            tvLatencyLabel.setText("Latency trim: " + sign + latencyTrimMs + " ms");

            // pollForMeasuredHwLatency() DISABLED — rxHw is now hardcoded in
            // ReceiverService.determineRxHwMs(). Auto-polling overwrites SharedPrefs
            // with the geometry value each session, corrupting subsequent reads.
            hwLatencySaved = true; // prevent any stale poll from firing
            startRoleLabelPolling();
            layoutSwitchSender.setVisibility(View.VISIBLE);        }

        engineRunning = true;
        updateUI();
    }

    // ── [ADDED] Poll until the C++ engine reports a measured hw latency ───────
    private void pollForMeasuredHwLatency() {
        hwPollHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!engineRunning || hwLatencySaved) return;
                if (receiverBinder == null) {
                    // Service not bound yet — retry shortly
                    hwPollHandler.postDelayed(this, 500);
                    return;
                }
                long measuredMs = receiverBinder.getMeasuredHwLatencyMs();
                if (measuredMs > 0) {
                    LatencyPrefs.saveMeasuredHwLatency(MainActivity.this, measuredMs);
                    hwLatencySaved = true;
                    Log.i(TAG, "Saved measured hw latency: " + measuredMs + " ms");
                } else {
                    // Not ready yet — the warm-up is still running
                    hwPollHandler.postDelayed(this, 500);
                }
            }
        }, 2500); // First poll at 2.5 s (after 1.5 s warmup + 8×50 ms samples + margin)
    }

    private void stopEngine() {
        if (!engineRunning) return;
        // [ADDED] Cancel any pending hw-latency polls
        hwPollHandler.removeCallbacksAndMessages(null);
        unbindReceiver();
        stopService(new Intent(this, SenderService.class));
        stopService(new Intent(this, ReceiverService.class));
        engineRunning = false;
        selectedRole  = null;
        btnSender.setText("Start as Sender");
        tvStatus.setText("Stopped");
        btnPickMp3.setVisibility(View.GONE);
        tvMp3Name.setVisibility(View.GONE);
        layoutLatency.setVisibility(View.GONE);
        layoutPlaybackControls.setVisibility(View.GONE);
        layoutDevices.setVisibility(View.GONE);
        if (tvDeviceList != null) tvDeviceList.setText("—");
        if (tvAssignedRole != null) tvAssignedRole.setText("Role: —");
        if (layoutSwitchSender != null) layoutSwitchSender.setVisibility(View.GONE);        updateUI();
    }

    private void unbindReceiver() {
        if (receiverBinder != null) {
            unbindService(receiverConn);
            receiverBinder = null;
        }
    }

    private void updateUI() {
        float a = engineRunning ? 0.4f : 1.0f;
        btnSender.setAlpha(a); btnBass.setAlpha(a); btnMid.setAlpha(a);
        btnTreble.setAlpha(a); btnFull.setAlpha(a);
        btnStop.setEnabled(engineRunning);
    }

    public void onStartSenderTapped(View v) {
        if (selectedRole != null && selectedRole.equals("sender") && !engineRunning)
            startEngine();
    }

    // ── Playback polling ──────────────────────────────────────────────────────

    private void startPlaybackPolling() {
        playbackPollHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!engineRunning) return;
                SenderService.LocalBinder binder = SenderService.getBinder();
                if (binder != null && !seekTrackDragging) {
                    long posMs      = binder.getPositionMs();
                    long durationMs = binder.getDurationMs();
                    if (durationMs > 0) {
                        int progress = (int)(posMs * 1000L / durationMs);
                        seekTrack.setProgress(progress);
                        tvTrackPosition.setText(
                                formatMs(posMs) + " / " + formatMs(durationMs));
                    }
                }
                playbackPollHandler.postDelayed(this, 250);
            }
        }, 250);
    }

    private void startRoleLabelPolling() {
        playbackPollHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!engineRunning) return;
                if (receiverBinder != null && tvAssignedRole != null) {
                    String role   = receiverBinder.getAssignedRole();
                    String status = receiverBinder.getConnectionStatus();
                    String label  = "Role: " + (role != null && !role.isEmpty() ? role : "—");
                    if (status != null && !status.isEmpty()
                            && !status.startsWith("HANDSHAKE_OK")) {
                        label += "  [" + status + "]";
                    }
                    tvAssignedRole.setText(label);
                }
                playbackPollHandler.postDelayed(this, 1000);
            }
        }, 1000);
    }
    private void startDeviceStatsPolling() {
        playbackPollHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!engineRunning) return;
                SenderService.LocalBinder binder = SenderService.getBinder();
                if (binder != null && tvDeviceList != null) {
                    String raw = binder.getClientStats();
                    if (raw == null || raw.isEmpty()) {
                        tvDeviceList.setText("No devices connected");
                    } else {
                        // Each line: "addr|role|gain"
                        StringBuilder sb = new StringBuilder();
                        for (String line : raw.split("\n")) {
                            if (line.isEmpty()) continue;
                            String[] parts = line.split("\\|");
                            if (parts.length >= 3) {
                                sb.append(parts[0])          // IP
                                        .append("  role=").append(parts[1])
                                        .append("  gain=").append(parts[2])
                                        .append("\n");
                            }
                        }
                        tvDeviceList.setText(sb.toString().trim());
                    }
                }
                playbackPollHandler.postDelayed(this, 5000);
            }
        }, 1000); // first poll after 1s so it shows quickly on connect
    }

    private void showManualIPDialog() {
        // Build a simple EditText input dialog — no external dependencies needed
        android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("e.g. 192.168.1.5");
        input.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        input.setPadding(40, 20, 40, 20);

        new AlertDialog.Builder(this)
                .setTitle("Connect to Sender")
                .setMessage("Enter the sender's IP address, or leave blank to auto-discover.")
                .setView(input)
                .setPositiveButton("Connect", (dialog, which) -> {
                    String ip = input.getText().toString().trim();
                    if (receiverBinder == null) return;
                    if (ip.isEmpty()) {
                        // Blank = auto re-discover
                        receiverBinder.switchSender("");
                        tvAssignedRole.setText("Role: —  [searching...]");
                        Toast.makeText(this,
                                "Auto-discovering sender...",
                                Toast.LENGTH_SHORT).show();
                    } else {
                        // Validate it looks roughly like an IP
                        if (!ip.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")) {
                            Toast.makeText(this,
                                    "Invalid IP address format",
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        receiverBinder.switchSender(ip);
                        tvAssignedRole.setText("Role: —  [connecting to " + ip + "...]");
                        Toast.makeText(this,
                                "Connecting to " + ip + "...",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static String formatMs(long ms) {
        long totalSec = ms / 1000;
        long min = totalSec / 60;
        long sec = totalSec % 60;
        return String.format(java.util.Locale.US, "%d:%02d", min, sec);
    }

    // ── IP detection ──────────────────────────────────────────────────────────

    private String getSenderIP() {
        try {
            List<NetworkInterface> ifaces =
                    Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface i : ifaces) {
                if (!i.isUp() || i.isLoopback()) continue;
                if (!Arrays.asList("wlan_ap","swlan0","ap0","wlan1")
                        .contains(i.getName())) continue;
                String ip = firstIPv4(i); if (ip != null) return ip;
            }
            for (NetworkInterface i : ifaces) {
                if (!i.isUp() || i.isLoopback()) continue;
                String ip = firstIPv4(i);
                if (ip != null && ip.startsWith("192.168.43.")) return ip;
            }
            for (NetworkInterface i : ifaces) {
                if (!i.isUp() || i.isLoopback()) continue;
                String ip = firstIPv4(i);
                if (ip != null && ip.startsWith("192.168.49.")) return ip;
            }
            for (NetworkInterface i : ifaces) {
                if (!i.isUp() || i.isLoopback()) continue;
                String ip = firstIPv4(i);
                if (ip != null && ip.startsWith("192.168.")
                        && !ip.startsWith("192.168.100.")) return ip;
            }
        } catch (Exception e) { Log.e(TAG, "getSenderIP", e); }
        return "0.0.0.0";
    }

    private static String firstIPv4(NetworkInterface iface) {
        for (InetAddress a : Collections.list(iface.getInetAddresses()))
            if (!a.isLoopbackAddress() && a.getAddress().length == 4)
                return a.getHostAddress();
        return null;
    }

    // ── [CALIB] Microphone loopback calibration ───────────────────────────────

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCalibration();
            } else {
                Toast.makeText(this, "Microphone permission required for calibration",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    /**
     * Starts the microphone loopback calibration.
     *
     * Requirements:
     *  - The sender must be running (so port 5001 is open)
     *  - Place this device within 1 m of the sender speaker
     *  - Keep room quiet for ~3 seconds
     *
     * Workflow:
     *  1. Creates a CalibEngine native object
     *  2. Gets the sender IP from the sender service (or from SenderService discovery)
     *  3. Passes the existing clockOffset from ReceiverEngine (if a receiver session
     *     is active) so we reuse the already-measured offset; otherwise 0 = measure fresh
     *  4. Polls for progress every 300 ms
     *  5. On completion, auto-applies rxHw to the running receiver (if active)
     *     and updates the slider label to show the calibrated value
     */
    private void startCalibration() {
        if (calibRunning) return;

        // Discover sender IP — try ReceiverService first (it discovered the sender),
        // otherwise fall back to getSenderIP() for when running on sender device.
        String senderIP = discoverSenderIPForCalib();
        if (senderIP == null || senderIP.equals("0.0.0.0") || senderIP.isEmpty()) {
            Toast.makeText(this,
                    "Could not determine sender IP. Start receiver first or check network.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        // Get existing clock offset from ReceiverEngine (0 if not running)
        long clockOffsetNs = 0L;
        if (receiverBinder != null) {
            clockOffsetNs = receiverBinder.getClockOffsetNs();
        }

        // Create and start CalibEngine
        calibHandle  = NativeEngine.calibCreate();
        calibRunning = true;
        btnCalibrate.setText("Stop Calibrating");
        tvCalibStatus.setVisibility(View.VISIBLE);
        tvCalibStatus.setText("Starting calibration... Place this device within 1 m of the sender speaker and keep room quiet.");

        Log.i(TAG, "Starting calibration — senderIP=" + senderIP
                + "  clockOffset=" + clockOffsetNs / 1_000_000L + " ms");

        boolean started = NativeEngine.calibStart(calibHandle, senderIP, clockOffsetNs);
        if (!started) {
            tvCalibStatus.setText("ERROR: Failed to start calibration.");
            cleanupCalibEngine();
            return;
        }

        // Poll for progress and completion every 300 ms
        calibPollHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!calibRunning) return;

                // Update status message
                String progress = NativeEngine.calibGetLastProgress(calibHandle);
                if (progress != null && !progress.isEmpty()) {
                    tvCalibStatus.setText(progress);
                }

                boolean stillRunning = NativeEngine.calibIsRunning(calibHandle);
                if (!stillRunning) {
                    // Done (succeeded or failed)
                    long resultMs = NativeEngine.calibGetResultMs(calibHandle);
                    onCalibrationDone(resultMs);
                    return;
                }

                calibPollHandler.postDelayed(this, 300);
            }
        }, 300);
    }

    private void stopCalibration() {
        calibPollHandler.removeCallbacksAndMessages(null);
        if (calibRunning && calibHandle != 0) {
            NativeEngine.calibStop(calibHandle);
        }
        cleanupCalibEngine();
        if (btnCalibrate != null) btnCalibrate.setText("Calibrate (Mic)");
    }

    private void cleanupCalibEngine() {
        if (calibHandle != 0) {
            NativeEngine.calibDestroy(calibHandle);
            calibHandle = 0;
        }
        calibRunning = false;
    }

    /**
     * Called when calibration finishes.
     * @param resultMs measured rxHw in ms, or 0 if failed
     */
    private void onCalibrationDone(long resultMs) {
        cleanupCalibEngine();
        btnCalibrate.setText("Calibrate (Mic)");

        if (resultMs <= 0) {
            tvCalibStatus.setText("Calibration failed. Check logcat for details. "
                            + "Try: move devices closer, reduce background noise, retry.");
            Log.e(TAG, "Calibration failed (resultMs=0)");
            return;
        }

        Log.i(TAG, "Calibration done: rxHw = " + resultMs + " ms");

        // Always save the calib result separately
        LatencyPrefs.saveCalibHwLatency(this, resultMs);

        long measuredMs = LatencyPrefs.getSavedHwLatency(this);

        if (measuredMs > 0 && resultMs < measuredMs) {
            // Mic result is lower than baseline — ask user which to trust
            long diff = measuredMs - resultMs;
            String message = "Calibration result:  " + resultMs + " ms "
                    + "Measured baseline:   " + measuredMs + " ms "
                    + "The mic result is " + diff + " ms lower. "
                    + "This can happen if the mic picked up early reflections. "
                    + "Which value should be used for playback sync?";

            new AlertDialog.Builder(this)
                    .setTitle("Which latency value to use?")
                    .setMessage(message)
                    .setPositiveButton("Use Measured (" + measuredMs + " ms)", (d, w) -> {
                        applyRxHw(measuredMs, "measured");
                    })
                    .setNegativeButton("Use Calibrated (" + resultMs + " ms)", (d, w) -> {
                        applyRxHw(resultMs, "calibrated");
                    })
                    .setNeutralButton("Cancel", null)
                    .show();
        } else {
            // Calib >= measured (or no baseline yet) — use calib, update baseline
            LatencyPrefs.saveMeasuredHwLatency(this, resultMs);
            applyRxHw(resultMs, "calibrated");
        }
    }

    /** Apply rxHw to the running receiver and update the UI label. */
    private void applyRxHw(long ms, String source) {
        if (receiverBinder != null) {
            receiverBinder.setHwLatencyMs((int) ms);
            Log.i(TAG, "Applied " + source + " rxHw=" + ms + " ms to receiver");
        }
        String sign = latencyTrimMs >= 0 ? "+" : "";
        tvLatencyLabel.setText("Latency trim: " + sign + latencyTrimMs
                + " ms  [rxHw=" + ms + " ms, src=" + source + "]");
        tvCalibStatus.setText("\u2713 Applied rxHw = " + ms + " ms  (" + source + ")");
        Toast.makeText(this, "rxHw = " + ms + " ms (" + source + ")",
                Toast.LENGTH_LONG).show();
    }

    /**
     * Try to find the sender's IP address for the calibration connection.
     * Priority:
     *  1. From ReceiverService (it already discovered the sender)
     *  2. From LatencyPrefs (if a sender IP was cached)
     *  3. Prompt user (not implemented here — add a text field if needed)
     */
    private String discoverSenderIPForCalib() {
        // Option 1: ask the ReceiverService binder for the sender IP it connected to
        if (receiverBinder != null) {
            String ip = receiverBinder.getSenderIP();
            if (ip != null && !ip.isEmpty()) return ip;
        }
        // Option 2: if this device IS the sender, use its own IP
        String selfIP = getSenderIP();
        if (!selfIP.equals("0.0.0.0")) return selfIP;
        return null;
    }

    public static int openFdFromUri(Context ctx, String uriStr) {
        try {
            Uri uri = Uri.parse(uriStr);
            ParcelFileDescriptor pfd =
                    ctx.getContentResolver().openFileDescriptor(uri, "r");
            if (pfd == null) return -1;
            return pfd.detachFd();
        } catch (IOException e) {
            Log.e(TAG, "openFdFromUri: " + e.getMessage()); return -1;
        }
    }
}