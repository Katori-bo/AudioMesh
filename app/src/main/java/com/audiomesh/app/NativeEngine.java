package com.audiomesh.app;

public class NativeEngine {

    static {
        System.loadLibrary("audiomesh_native");
    }
    // ── Sender ────────────────────────────────────────────────────────────────
    public static native long  senderCreate();
    public static native boolean senderStart(long handle, String ip, boolean localOnly);    public static native void    senderStop(long handle);
    public static native void  senderDestroy(long handle);
    public static native void  senderSetFd(long handle, int fd);
    public static native void  senderStartStreaming(long handle);
    public static native void  senderStopStreaming(long handle);

    public static native void senderPause(long handle);
    public static native void senderResume(long handle);
    public static native boolean senderIsPaused(long handle);

    public static native void    senderSeekToMs(long handle, long positionMs);
    public static native long    senderGetPositionMs(long handle);
    public static native long    senderGetDurationMs(long handle);

    public static native void senderSetClientGain(long handle, String addr, float gain);

    // ── Receiver ──────────────────────────────────────────────────────────────
    public static native long    receiverCreate();
    public static native boolean receiverStart(long handle, String role, long latencyNs);
    public static native void    receiverStop(long handle);
    public static native void    receiverDestroy(long handle);
    public static native void    receiverSetLatency(long handle, int ms);

    public static native long receiverGetMeasuredHwLatencyMs(long handle);
    public static native void receiverSetSavedHwLatencyMs(long handle, long savedMs);
    public static native String receiverGetSenderIP(long handle);
    public static native long receiverGetClockOffsetNs(long handle);
    public static native long receiverGetEmaDriftMs(long handle);
    public static native void receiverFlushAndSilence(long handle);

    // ── Calibration ───────────────────────────────────────────────────────────
    public static native long    calibCreate();
    public static native boolean calibStart(long handle, String senderIP, long clockOffsetNs);
    public static native void    calibStop(long handle);
    public static native void    calibDestroy(long handle);
    public static native boolean calibIsRunning(long handle);
    public static native long    calibGetResultMs(long handle);
    public static native String  calibGetLastProgress(long handle);

    // ── Sender advanced ───────────────────────────────────────────────────────
    public static native void senderSetClientCrossover(long handle, String addr, float lowCutHz, float highCutHz);
    public static native void senderSetClientRole(long handle, String addr, String role);
    public static native String senderGetClientStats(long handle);
    public static native void senderSetLocalRole(long handle, String role, float bassCutHz, float trebleCutHz);
    public static native void senderSetClientEq(long handle, String addr,
                                                float peakHz, float peakDb, float peakQ,
                                                float shelfHz, float shelfDb);

    // ── Receiver advanced ─────────────────────────────────────────────────────
    public static native String receiverGetAssignedRole(long handle);
    public static native void   receiverSwitchSender(long handle, String newIP);
    public static native String receiverGetConnectionStatus(long handle);

    // ── Track swap ────────────────────────────────────────────────────────────
    public static native void senderSwapTrack(long handle, int fd);
    public static native void senderSetTrackInfo(long handle, String title, String artist);
    public static native void senderSetPaletteHex(long handle, String hex1, String hex2);
    public static native long senderGetClientPingMs(long handle, String addr);

    // ── Receiver track info + palette ─────────────────────────────────────────
    public static native String receiverGetTrackTitle(long handle);
    public static native String receiverGetTrackArtist(long handle);
    public static native String receiverGetPaletteHex1(long handle);
    public static native String receiverGetPaletteHex2(long handle);

    public static native long receiverGetCurrentPositionMs(long handle);
    public static native long receiverGetTrackDurationMs(long handle);

    public static native String senderGetAllClientPings(long handle, String[] addresses);


}