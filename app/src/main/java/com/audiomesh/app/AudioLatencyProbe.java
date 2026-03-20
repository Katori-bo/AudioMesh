package com.audiomesh.app;

// ─────────────────────────────────────────────────────────────────────────────
//  AudioLatencyProbe.java
//
//  Measures the true end-to-end output latency of this Android device using
//  AudioTrack.getLatency() — a hidden @hide method that Android's own
//  MediaPlayer uses internally.  Unlike AAudioStream_getTimestamp(), this
//  works in SHARED mode on virtually every Android device.
//
//  What it measures:
//    AudioTrack write pointer → AudioFlinger mixer → HAL → DSP/codec → DAC
//    i.e. the FULL pipeline depth, including the parts invisible to AAudio.
//
//  This value is used directly as rxHw in the sync formula:
//    playAtReceiverNs = senderExitNs - clockOffset - rxHw + sliderNs
//
//  Usage:
//    long latencyMs = AudioLatencyProbe.measure(context, sampleRate);
//    // returns 0 if reflection fails (safe fallback)
// ─────────────────────────────────────────────────────────────────────────────

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.util.Log;

import java.lang.reflect.Method;

public final class AudioLatencyProbe {

    private static final String TAG = "AudioLatencyProbe";

    /**
     * Measures the full output pipeline latency of this device.
     *
     * @param context    Any context (used to read AudioManager properties)
     * @param sampleRate The sample rate your AAudio stream uses (e.g. 44100)
     * @return           Latency in milliseconds, or 0 if measurement failed.
     *                   A return of 0 means the caller should use geometry fallback.
     */
    public static long measure(Context context, int sampleRate) {

        // ── Step 1: get native buffer size so AudioTrack matches our AAudio config ──
        // AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER is the HAL period size.
        // We use 2× as the AudioTrack buffer size — same as AAudio SHARED mode uses.
        int halFrames = getHalFrames(context, sampleRate);
        int bufferFrames = halFrames * 2;
        int bufferBytes  = bufferFrames * 2; // PCM_16BIT = 2 bytes per frame

        AudioTrack track = null;
        try {
            // ── Step 2: create a minimal AudioTrack matching our audio config ──────
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setSampleRate(sampleRate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .build())
                        .setBufferSizeInBytes(bufferBytes)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build();
            } else {
                track = new AudioTrack(
                        AudioManager.STREAM_MUSIC,
                        sampleRate,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferBytes,
                        AudioTrack.MODE_STREAM);
            }

            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack failed to initialize");
                return 0L;
            }

            // ── Step 3: start the track (getLatency() returns 0 until started) ───
            track.play();

            // Write a buffer of silence so AudioFlinger actually pipelines data
            // and the latency measurement is representative of real playback.
            byte[] silence = new byte[bufferBytes];
            track.write(silence, 0, silence.length);

            // Brief wait for AudioFlinger to stabilise (~2 mixing periods)
            Thread.sleep(40);

            // ── Step 4: call getLatency() via reflection ───────────────────────
            Method getLatency = AudioTrack.class.getDeclaredMethod("getLatency");
            getLatency.setAccessible(true);
            int latencyMs = (int) getLatency.invoke(track);

            Log.i(TAG, "AudioTrack.getLatency() = " + latencyMs + " ms  "
                    + "(sampleRate=" + sampleRate + "  halFrames=" + halFrames + ")");

            // Sanity check — reject obviously wrong values
            if (latencyMs <= 0 || latencyMs > 2000) {
                Log.e(TAG, "getLatency() returned implausible value: " + latencyMs + " ms");
                return 0L;
            }

            return (long) latencyMs;

        } catch (NoSuchMethodException e) {
            Log.e(TAG, "getLatency() not found via reflection — not available on this device");
            return 0L;
        } catch (Exception e) {
            Log.e(TAG, "AudioLatencyProbe failed: " + e.getMessage());
            return 0L;
        } finally {
            if (track != null) {
                try { track.stop(); } catch (Exception ignored) {}
                track.release();
            }
        }
    }

    // ── Read the HAL buffer period size from AudioManager ────────────────────

    private static int getHalFrames(Context context, int sampleRate) {
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            String prop = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER);
            if (prop != null) {
                int frames = Integer.parseInt(prop);
                if (frames > 0) return frames;
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not read PROPERTY_OUTPUT_FRAMES_PER_BUFFER: " + e.getMessage());
        }
        // Fallback: 256 frames is common for low-latency Android devices
        return 256;
    }

    private AudioLatencyProbe() {}
}