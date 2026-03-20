// ─────────────────────────────────────────────────────────────────────────────
//  AudioMeshJNI.cpp
//  JNI bridge — Java NativeEngine ↔ C++ SenderEngine / ReceiverEngine / CalibEngine
// ─────────────────────────────────────────────────────────────────────────────

#include "SenderEngine.h"
#include "ReceiverEngine.h"
#include "calib/CalibEngine.h"
#include <jni.h>
#include <android/log.h>
#include <map>
#include <mutex>

#define TAG  "AudioMeshJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static std::string jstr(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string r(c);
    env->ReleaseStringUTFChars(s, c);
    return r;
}

// Per-engine progress storage (polled from Java every 300ms)
static std::map<CalibEngine*, std::string> gCalibProgress;
static std::mutex                           gCalibProgressMutex;

extern "C" {

// ── Sender ────────────────────────────────────────────────────────────────────

JNIEXPORT jlong JNICALL
Java_com_audiomesh_app_NativeEngine_senderCreate(JNIEnv*, jclass) {
    auto* e = new SenderEngine();
    LOGI("senderCreate → %p", e);
    return (jlong)e;
}

JNIEXPORT jboolean JNICALL
Java_com_audiomesh_app_NativeEngine_senderStart(
        JNIEnv* env, jclass, jlong handle, jstring ip, jboolean localOnly) {
    auto* e = reinterpret_cast<SenderEngine*>(handle);
    if (!e) return JNI_FALSE;
    return e->start(jstr(env, ip), localOnly == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_audiomesh_app_NativeEngine_senderStop(JNIEnv*, jclass, jlong handle) {
    auto* e = reinterpret_cast<SenderEngine*>(handle);
    if (e) e->stop();
}

JNIEXPORT void JNICALL
Java_com_audiomesh_app_NativeEngine_senderDestroy(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<SenderEngine*>(handle);
    LOGI("senderDestroy");
}

JNIEXPORT void JNICALL
Java_com_audiomesh_app_NativeEngine_senderSetFd(
        JNIEnv*, jclass, jlong handle, jint fd) {
    auto* e = reinterpret_cast<SenderEngine*>(handle);
    if (e) e->setFd(fd);
}

JNIEXPORT void JNICALL
Java_com_audiomesh_app_NativeEngine_senderStartStreaming(
        JNIEnv*, jclass, jlong handle) {
    auto* e = reinterpret_cast<SenderEngine*>(handle);
    if (e) e->startStreaming();
}

JNIEXPORT void JNICALL
Java_com_audiomesh_app_NativeEngine_senderStopStreaming(
        JNIEnv*, jclass, jlong handle) {
    auto* e = reinterpret_cast<SenderEngine*>(handle);
    if (e) e->stopStreaming();
}

JNIEXPORT void JNICALL
Java_com_audiomesh_app_NativeEngine_senderPause(
        JNIEnv*, jclass, jlong handle) {
    auto* e = reinterpret_cast<SenderEngine*>(handle);
    if (e) e->pause();
}

JNIEXPORT void JNICALL
Java_com_audiomesh_app_NativeEngine_senderResume(
        JNIEnv*, jclass, jlong handle) {
    auto* e = reinterpret_cast<SenderEngine*>(handle);
    if (e) e->resume();
}

JNIEXPORT jboolean JNICALL
Java_com_audiomesh_app_NativeEngine_senderIsPaused(
        JNIEnv*, jclass, jlong handle) {
    auto* e = reinterpret_cast<SenderEngine*>(handle);
    if (!e) return JNI_FALSE;
    return e->isPaused() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_audiomesh_app_NativeEngine_senderSeekToMs(
        JNIEnv*, jclass, jlong handle, jlong positionMs) {
    auto* e = reinterpret_cast<SenderEngine*>(handle);
    if (e) e->seekToMs((int64_t)positionMs);
}

JNIEXPORT jlong JNICALL
Java_com_audiomesh_app_NativeEngine_senderGetPositionMs(
        JNIEnv*, jclass, jlong handle) {
    auto* e = reinterpret_cast<SenderEngine*>(handle);
    if (!e) return 0L;
    return (jlong)e->getPositionMs();
}

JNIEXPORT jlong JNICALL
Java_com_audiomesh_app_NativeEngine_senderGetDurationMs(
        JNIEnv*, jclass, jlong handle) {
    auto* e = reinterpret_cast<SenderEngine*>(handle);
    if (!e) return 0L;
    return (jlong)e->getDurationMs();
}

// ── Receiver ──────────────────────────────────────────────────────────────────

JNIEXPORT jlong JNICALL
Java_com_audiomesh_app_NativeEngine_receiverCreate(JNIEnv*, jclass) {
    auto* e = new ReceiverEngine();
    LOGI("receiverCreate → %p", e);
    return (jlong)e;
}

JNIEXPORT jboolean JNICALL
Java_com_audiomesh_app_NativeEngine_receiverStart(
        JNIEnv* env, jclass, jlong handle, jstring role, jlong latencyNs) {
    auto* e = reinterpret_cast<ReceiverEngine*>(handle);
    if (!e) return JNI_FALSE;
    return e->start(jstr(env, role), (int64_t)latencyNs) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_audiomesh_app_NativeEngine_receiverStop(JNIEnv*, jclass, jlong handle) {
    auto* e = reinterpret_cast<ReceiverEngine*>(handle);
    if (e) e->stop();
}

JNIEXPORT void JNICALL
Java_com_audiomesh_app_NativeEngine_receiverDestroy(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<ReceiverEngine*>(handle);
    LOGI("receiverDestroy");
}

JNIEXPORT void JNICALL
Java_com_audiomesh_app_NativeEngine_receiverSetLatency(
        JNIEnv*, jclass, jlong handle, jint ms) {
    auto* e = reinterpret_cast<ReceiverEngine*>(handle);
    if (e) {
        int64_t ns = (int64_t)ms * 1'000'000LL;
        e->setLatencyNs(ns);
        LOGI("receiverSetLatency: %d ms", ms);
    }
}

JNIEXPORT jlong JNICALL
Java_com_audiomesh_app_NativeEngine_receiverGetMeasuredHwLatencyMs(
        JNIEnv*, jclass, jlong handle) {
    auto* e = reinterpret_cast<ReceiverEngine*>(handle);
    if (!e) return 0L;
    return (jlong)(e->getMeasuredHwLatencyNs() / 1'000'000LL);
}

JNIEXPORT void JNICALL
Java_com_audiomesh_app_NativeEngine_receiverSetSavedHwLatencyMs(
        JNIEnv*, jclass, jlong handle, jlong savedMs) {
    auto* e = reinterpret_cast<ReceiverEngine*>(handle);
    if (!e || savedMs <= 0) return;
    e->setHwLatencyNs((int64_t)savedMs * 1'000'000LL);
    LOGI("receiverSetSavedHwLatencyMs: pre-loaded %lld ms", (long long)savedMs);
}

JNIEXPORT jstring JNICALL
Java_com_audiomesh_app_NativeEngine_receiverGetSenderIP(
        JNIEnv* env, jclass, jlong handle) {
    auto* e = reinterpret_cast<ReceiverEngine*>(handle);
    if (!e) return env->NewStringUTF("");
    return env->NewStringUTF(e->getSenderIP().c_str());
}

JNIEXPORT jlong JNICALL
Java_com_audiomesh_app_NativeEngine_receiverGetClockOffsetNs(
        JNIEnv*, jclass, jlong handle) {
    auto* e = reinterpret_cast<ReceiverEngine*>(handle);
    if (!e) return 0LL;
    return (jlong)e->getClockOffsetNs();
}

JNIEXPORT jlong JNICALL
Java_com_audiomesh_app_NativeEngine_receiverGetEmaDriftMs(
        JNIEnv*, jclass, jlong handle) {
    auto* e = reinterpret_cast<ReceiverEngine*>(handle);
    if (!e) return 0LL;
    return (jlong)(e->getEmaDriftNs() / 1'000'000LL);
}

JNIEXPORT void JNICALL
Java_com_audiomesh_app_NativeEngine_receiverFlushAndSilence(
        JNIEnv*, jclass, jlong handle) {
    auto* e = reinterpret_cast<ReceiverEngine*>(handle);
    if (e) e->flushAndSilence();
}

// ── Calib ─────────────────────────────────────────────────────────────────────

JNIEXPORT jlong JNICALL
Java_com_audiomesh_app_NativeEngine_calibCreate(JNIEnv*, jclass) {
    auto* e = new CalibEngine();
    LOGI("calibCreate → %p", e);
    return (jlong)e;
}

JNIEXPORT jboolean JNICALL
Java_com_audiomesh_app_NativeEngine_calibStart(
        JNIEnv* env, jclass, jlong handle, jstring senderIPStr, jlong clockOffsetNs) {
    auto* e = reinterpret_cast<CalibEngine*>(handle);
    if (!e) return JNI_FALSE;

    std::string ip = jstr(env, senderIPStr);

    {
        std::lock_guard<std::mutex> lk(gCalibProgressMutex);
        gCalibProgress[e] = "Starting...";
    }

    bool ok = e->start(ip, (int64_t)clockOffsetNs,
                       [e](const std::string& msg) {
                           std::lock_guard<std::mutex> lk(gCalibProgressMutex);
                           gCalibProgress[e] = msg;
                       });

    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_audiomesh_app_NativeEngine_calibStop(JNIEnv*, jclass, jlong handle) {
    auto* e = reinterpret_cast<CalibEngine*>(handle);
    if (e) e->stop();
}

JNIEXPORT void JNICALL
Java_com_audiomesh_app_NativeEngine_calibDestroy(JNIEnv*, jclass, jlong handle) {
    auto* e = reinterpret_cast<CalibEngine*>(handle);
    if (e) {
        {
            std::lock_guard<std::mutex> lk(gCalibProgressMutex);
            gCalibProgress.erase(e);
        }
        delete e;
    }
    LOGI("calibDestroy");
}

JNIEXPORT jboolean JNICALL
Java_com_audiomesh_app_NativeEngine_calibIsRunning(JNIEnv*, jclass, jlong handle) {
    auto* e = reinterpret_cast<CalibEngine*>(handle);
    if (!e) return JNI_FALSE;
    return e->isRunning() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_audiomesh_app_NativeEngine_calibGetResultMs(JNIEnv*, jclass, jlong handle) {
    auto* e = reinterpret_cast<CalibEngine*>(handle);
    if (!e) return 0L;
    return (jlong)(e->getResultNs() / 1'000'000LL);
}

JNIEXPORT jstring JNICALL
Java_com_audiomesh_app_NativeEngine_calibGetLastProgress(
        JNIEnv* env, jclass, jlong handle) {
    auto* e = reinterpret_cast<CalibEngine*>(handle);
    if (!e) return env->NewStringUTF("");
    std::lock_guard<std::mutex> lk(gCalibProgressMutex);
    auto it = gCalibProgress.find(e);
    std::string msg = (it != gCalibProgress.end()) ? it->second : "";
    return env->NewStringUTF(msg.c_str());
}

JNIEXPORT void JNICALL
Java_com_audiomesh_app_NativeEngine_senderSetClientGain(
        JNIEnv* env, jclass, jlong handle, jstring addr, jfloat gain) {
    auto* e = reinterpret_cast<SenderEngine*>(handle);
    if (e) e->setClientGain(jstr(env, addr), (float)gain);
}

JNIEXPORT void JNICALL
Java_com_audiomesh_app_NativeEngine_senderSetClientCrossover(
        JNIEnv* env, jclass, jlong handle,
        jstring addr, jfloat lowCutHz, jfloat highCutHz) {
    auto* e = reinterpret_cast<SenderEngine*>(handle);
    if (e) e->setClientCrossover(jstr(env, addr),
                                 (float)lowCutHz, (float)highCutHz);
}

JNIEXPORT void JNICALL
Java_com_audiomesh_app_NativeEngine_senderSetClientEq(
        JNIEnv* env, jclass, jlong handle,
        jstring addr,
        jfloat peakHz, jfloat peakDb, jfloat peakQ,
        jfloat shelfHz, jfloat shelfDb) {
    auto* e = reinterpret_cast<SenderEngine*>(handle);
    if (e) e->setClientEq(jstr(env, addr),
                          (float)peakHz, (float)peakDb, (float)peakQ,
                          (float)shelfHz, (float)shelfDb);
}

JNIEXPORT void JNICALL
Java_com_audiomesh_app_NativeEngine_senderSetClientRole(
        JNIEnv* env, jclass, jlong handle, jstring addr, jstring role) {
    auto* e = reinterpret_cast<SenderEngine*>(handle);
    if (e) e->setSenderClientRole(jstr(env, addr), jstr(env, role));
}

JNIEXPORT jstring JNICALL
Java_com_audiomesh_app_NativeEngine_senderGetClientStats(
        JNIEnv* env, jclass, jlong handle) {
    auto* e = reinterpret_cast<SenderEngine*>(handle);
    if (!e) return env->NewStringUTF("");
    return env->NewStringUTF(e->getClientStats().c_str());
}

JNIEXPORT void JNICALL
Java_com_audiomesh_app_NativeEngine_senderSetLocalRole(
        JNIEnv* env, jclass, jlong handle,
        jstring role, jfloat bassCutHz, jfloat trebleCutHz) {
    auto* e = reinterpret_cast<SenderEngine*>(handle);
    if (e) e->setSenderLocalRole(jstr(env, role),
                                 (float)bassCutHz, (float)trebleCutHz);
}

JNIEXPORT jstring JNICALL
Java_com_audiomesh_app_NativeEngine_receiverGetAssignedRole(
        JNIEnv* env, jclass, jlong handle) {
    auto* e = reinterpret_cast<ReceiverEngine*>(handle);
    if (!e) return env->NewStringUTF("full");
    return env->NewStringUTF(e->getAssignedRole().c_str());
}

JNIEXPORT void JNICALL
Java_com_audiomesh_app_NativeEngine_receiverSwitchSender(
        JNIEnv* env, jclass, jlong handle, jstring newIP) {
    auto* e = reinterpret_cast<ReceiverEngine*>(handle);
    if (e) e->switchSender(jstr(env, newIP));
}

JNIEXPORT jstring JNICALL
Java_com_audiomesh_app_NativeEngine_receiverGetConnectionStatus(
        JNIEnv* env, jclass, jlong handle) {
    auto* e = reinterpret_cast<ReceiverEngine*>(handle);
    if (!e) return env->NewStringUTF("");
    return env->NewStringUTF(e->getConnectionStatus().c_str());
}

// ── Track info + palette (sender) ─────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_com_audiomesh_app_NativeEngine_senderSetTrackInfo(
        JNIEnv* env, jclass, jlong handle, jstring title, jstring artist) {
    auto* e = reinterpret_cast<SenderEngine*>(handle);
    if (e) e->setTrackInfo(jstr(env, title), jstr(env, artist));
}

JNIEXPORT void JNICALL
Java_com_audiomesh_app_NativeEngine_senderSetPaletteHex(
        JNIEnv* env, jclass, jlong handle, jstring hex1, jstring hex2) {
    auto* e = reinterpret_cast<SenderEngine*>(handle);
    if (e) e->setPaletteHex(jstr(env, hex1), jstr(env, hex2));
}

JNIEXPORT void JNICALL
Java_com_audiomesh_app_NativeEngine_senderSwapTrack(
        JNIEnv*, jclass, jlong handle, jint fd) {
    auto* e = reinterpret_cast<SenderEngine*>(handle);
    if (e) e->swapTrack((int)fd);
}

JNIEXPORT jlong JNICALL
Java_com_audiomesh_app_NativeEngine_senderGetClientPingMs(
        JNIEnv* env, jclass, jlong handle, jstring addr) {
    auto* e = reinterpret_cast<SenderEngine*>(handle);
    if (!e) return -1L;
    return (jlong)e->getClientPingMs(jstr(env, addr));
}

// ── Track info + palette (receiver) ──────────────────────────────────────────

JNIEXPORT jstring JNICALL
Java_com_audiomesh_app_NativeEngine_receiverGetTrackTitle(
        JNIEnv* env, jclass, jlong handle) {
    auto* e = reinterpret_cast<ReceiverEngine*>(handle);
    if (!e) return env->NewStringUTF("");
    return env->NewStringUTF(e->getTrackTitle().c_str());
}

JNIEXPORT jstring JNICALL
Java_com_audiomesh_app_NativeEngine_receiverGetTrackArtist(
        JNIEnv* env, jclass, jlong handle) {
    auto* e = reinterpret_cast<ReceiverEngine*>(handle);
    if (!e) return env->NewStringUTF("");
    return env->NewStringUTF(e->getTrackArtist().c_str());
}

JNIEXPORT jstring JNICALL
Java_com_audiomesh_app_NativeEngine_receiverGetPaletteHex1(
        JNIEnv* env, jclass, jlong handle) {
    auto* e = reinterpret_cast<ReceiverEngine*>(handle);
    if (!e) return env->NewStringUTF("");
    return env->NewStringUTF(e->getPaletteHex1().c_str());
}

JNIEXPORT jstring JNICALL
Java_com_audiomesh_app_NativeEngine_receiverGetPaletteHex2(
        JNIEnv* env, jclass, jlong handle) {
    auto* e = reinterpret_cast<ReceiverEngine*>(handle);
    if (!e) return env->NewStringUTF("");
    return env->NewStringUTF(e->getPaletteHex2().c_str());
}
JNIEXPORT jlong JNICALL
Java_com_audiomesh_app_NativeEngine_receiverGetCurrentPositionMs(
        JNIEnv*, jclass, jlong handle) {
    auto* e = reinterpret_cast<ReceiverEngine*>(handle);
    if (!e) return 0L;
    return (jlong)e->getCurrentPositionMs();
}

JNIEXPORT jlong JNICALL
Java_com_audiomesh_app_NativeEngine_receiverGetTrackDurationMs(
        JNIEnv*, jclass, jlong handle) {
    auto* e = reinterpret_cast<ReceiverEngine*>(handle);
    if (!e) return 0L;
    return (jlong)e->getTrackDurationMs();
}
// ── Multi-client Ping Fetcher ────────────────────────────────────────────────

JNIEXPORT jstring JNICALL
Java_com_audiomesh_app_NativeEngine_senderGetAllClientPings(
        JNIEnv* env, jclass, jlong handle, jobjectArray addresses) {

    auto* e = reinterpret_cast<SenderEngine*>(handle);
    if (!e || !addresses) return env->NewStringUTF("");

    // Use stringstream to build a delimited string: "IP:Ping|IP:Ping"
    // This is efficient and prevents multiple JNI calls
    std::string result = "";
    int len = env->GetArrayLength(addresses);

    for (int i = 0; i < len; i++) {
        jstring js = (jstring)env->GetObjectArrayElement(addresses, i);
        if (!js) continue;

        std::string addr = jstr(env, js);

        // Call your EXISTING SenderEngine method
        long ping = e->getClientPingMs(addr);

        result += addr + ":" + std::to_string(ping);
        if (i < len - 1) result += "|";

        env->DeleteLocalRef(js);
    }

    return env->NewStringUTF(result.c_str());
}

} // extern "C"