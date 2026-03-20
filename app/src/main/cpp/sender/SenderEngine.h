#pragma once

#include "../common/Protocol.h"
#include "../FirFilter.h"
#include "../BiquadFilter.h"
#include <aaudio/AAudio.h>

#include <string>
#include <vector>
#include <thread>
#include <mutex>
#include <atomic>
#include <functional>
#include <cstdint>
#include <memory>
#include "MediaCodecDecoder.h"


// ─── Role enum ────────────────────────────────────────────────────────────────
enum SenderRole { SROLE_FULL = 0, SROLE_BASS, SROLE_MID, SROLE_TREBLE };

static inline SenderRole senderRoleFromString(const std::string& s) {
    if (s == ROLE_BASS)   return SROLE_BASS;
    if (s == ROLE_MID)    return SROLE_MID;
    if (s == ROLE_TREBLE) return SROLE_TREBLE;
    return SROLE_FULL;
}

// ─── Per-client state ─────────────────────────────────────────────────────────
struct ClientState {
    int         fd    = -1;
    bool        ready = false;
    bool        dead  = false;
    SenderRole  role  = SROLE_FULL;
    std::string addr;
    std::thread handshakeThread;
    std::atomic<float> gain{1.0f};

    // Per-client FIR filter — null for SROLE_FULL (no filtering needed).
    // Built in handshakeClient() using the crossover params below.
    // Protected by clientsMutex_ for rebuild; read by streamingLoop under same lock.
    std::unique_ptr<FirFilter> filter;

    // Crossover frequencies. Defaults match the old global values.
    // bass role:   LP  @ bassCutHz
    // mid  role:   BP  @ bassCutHz – trebleCutHz
    // treble role: HP  @ trebleCutHz
    float bassCutHz   = 250.0f;
    float trebleCutHz = 4000.0f;

    // Parametric EQ (2-band biquad: peak + high shelf).
    // null = flat / no processing (zero-cost path in streaming loop).
    float eqPeakHz  = 1000.0f;
    float eqPeakDb  = 0.0f;
    float eqPeakQ   = 1.0f;
    float eqShelfHz = 8000.0f;
    float eqShelfDb = 0.0f;
    std::unique_ptr<BiquadFilter> eq;

    // Role override set by the sender UI. SROLE_FULL means "no override —
    // use whatever the receiver requested". Receiver-requested role always
    // wins when it is not SROLE_FULL.
    SenderRole overrideRole = SROLE_FULL;

    // Ping RTT — measured during clock sync, exposed to UI
    std::atomic<int64_t> lastPingRttNs{0};
};
// ─── Status callback ──────────────────────────────────────────────────────────
struct SenderStatus {
    std::string addr;
    SenderRole  role;
    bool        connected;
};
using StatusCallback = std::function<void(SenderStatus)>;

// ─── Local playback types ─────────────────────────────────────────────────────
struct LocalChunk {
    int16_t* data     = nullptr;
    int      frames   = 0;
    int      offset   = 0;
    // When this chunk should exit the sender's speaker (sender clock).
    // = nextPlayAtNs + senderHwLatencyNs_
    int64_t  playAtNs = 0;
};

class LocalRing {
public:
    explicit LocalRing(int capacity) : cap_(capacity + 1), buf_(capacity + 1) {}

    bool push(LocalChunk* c) {
        int w = write_.load(std::memory_order_relaxed);
        int next = (w + 1) % cap_;
        if (next == read_.load(std::memory_order_acquire)) return false;
        buf_[w] = c;
        write_.store(next, std::memory_order_release);
        return true;
    }
    LocalChunk* peek() {
        int r = read_.load(std::memory_order_relaxed);
        if (r == write_.load(std::memory_order_acquire)) return nullptr;
        return buf_[r];
    }
    LocalChunk* pop() {
        int r = read_.load(std::memory_order_relaxed);
        if (r == write_.load(std::memory_order_acquire)) return nullptr;
        LocalChunk* c = buf_[r];
        read_.store((r + 1) % cap_, std::memory_order_release);
        return c;
    }
    int size() const {
        int w = write_.load(std::memory_order_acquire);
        int r = read_.load(std::memory_order_acquire);
        return (w - r + cap_) % cap_;
    }
    void clear() {
        read_.store(write_.load(std::memory_order_acquire),
                    std::memory_order_release);
    }

private:
    const int                cap_;
    std::vector<LocalChunk*> buf_;
    std::atomic<int>         read_{0}, write_{0};
};

class LocalPool {
public:
    explicit LocalPool(int count, int framesPerChunk) {
        ring_ = std::make_unique<LocalRing>(count);
        storage_.resize(count);
        chunks_.resize(count);
        for (int i = 0; i < count; i++) {
            storage_[i].resize(framesPerChunk);
            chunks_[i].data = storage_[i].data();
            ring_->push(&chunks_[i]);
        }
    }
    LocalChunk* acquire() { return ring_->pop(); }
    void release(LocalChunk* c) {
        c->frames   = 0;
        c->offset   = 0;
        c->playAtNs = 0;
        ring_->push(c);
    }

private:
    std::unique_ptr<LocalRing>        ring_;
    std::vector<std::vector<int16_t>> storage_;
    std::vector<LocalChunk>           chunks_;
};

// ─── SenderEngine ─────────────────────────────────────────────────────────────
class SenderEngine {
public:
    explicit SenderEngine(StatusCallback cb = nullptr);
    ~SenderEngine();

    bool start(const std::string& senderIP, bool localOnly = false);
    void stop();
    void setFd(int fd);
    void startStreaming();
    void stopStreaming();

    // ── Playback control ──────────────────────────────────────────────────────
    //
    // pause()      — atomically sets paused_, sends PAUSE control packet to all
    //                ready receivers, drains localRing so sender speaker goes
    //                silent. Streaming loop idles while paused_.
    //
    // resume()     — clears paused_, sends RESUME control packet to all ready
    //                receivers (which triggers them to break out and re-handshake),
    //                then resets sharedStartAtNs_ = 0 so the handshake thread
    //                re-runs the full IDEA-5 sequence on existing TCP connections.
    //                Streaming loop re-applies the new anchor before resuming decode.
    //
    // seekToMs(ms) — like resume but first seeks the MP3 decoder to the target
    //                frame, sends SEEK:<ms> instead of RESUME.
    //                Safe to call while paused OR while playing.
    //
    // getPositionMs() — returns the current playback position in ms.
    //                   Thread-safe: reads currentFramePosition_ (atomic).
    //
    // getDurationMs() — returns total MP3 duration in ms.
    //                   Valid after startStreaming() opens the file.
    //
    // isPaused()   — true between pause() and resume()/seekToMs().

    void    pause();
    void    resume();
    void    seekToMs(int64_t ms);
    void    setClientGain(const std::string& addr, float gain);
    void    setClientCrossover(const std::string& addr, float lowCutHz, float highCutHz);
    void    setClientEq(const std::string& addr,
                        float peakHz, float peakDb, float peakQ,
                        float shelfHz, float shelfDb);
    void        setSenderClientRole(const std::string& addr, const std::string& role);
    std::string getClientStats();
    void        setSenderLocalRole(const std::string& role,
                                   float bassCutHz   = 250.0f,
                                   float trebleCutHz = 4000.0f);
// AFTER
    void        swapTrack(int fd);
    void        setTrackInfo(const std::string& title, const std::string& artist);
    void        setPaletteHex(const std::string& hex1, const std::string& hex2);
    int64_t     getClientPingMs(const std::string& addr);
    int64_t getPositionMs() const;    int64_t getDurationMs() const;
    bool    isPaused() const { return paused_.load(std::memory_order_acquire); }

private:
    void beaconLoop();
    void acceptLoop();
    void handshakeClient(ClientState* s);
    void streamingLoop();

    bool    openDrMp3(int fd);
    void    closeDrMp3();
    bool    openAudio(int fd);    // detects format, dispatches to mp3 or aac
    void    closeAudio();
    void    seekToFrame(int64_t frame);
    bool    openLocalAudio();
    void    closeLocalAudio();
    int64_t measurePipelineDepthNs();

    static aaudio_data_callback_result_t localAudioCallback(
            AAudioStream*, void* userData, void* audioData, int32_t numFrames);
    aaudio_data_callback_result_t onLocalAudioReady(void* audioData, int32_t numFrames);

    // Send a 12-byte control header + 64-byte NUL-padded command to all ready clients.
    // Called from pause(), resume(), seekToMs() — always from the Java/UI thread,
    // protected by clientsMutex_.
    void sendControlToAll(const char* cmd);

    bool           sendRaw(int sockFd, const void* buf, int len);
    static int64_t nowNs();

    // Erase dead (fd-closed) clients from clients_. Must be called with
    // clientsMutex_ held. Called from streamingLoop(), sendControlToAll(),
    // resume(), and seekToMs() after each send pass.
    void           cleanDeadClients();

    // ── Lifecycle atomics ──────────────────────────────────────────────────────
    std::atomic<bool>    running_   {false};
    std::atomic<bool>    streaming_ {false};
    bool                 localOnly_ {false};

    // Sentinel = 0. handshakeClient() pushes this UP from 0 to the real
    // start time (latest across all receivers). streamingLoop() waits for
    // it to become non-zero before beginning to decode and send.
    // Reset to 0 on resume/seek so handshakeClient() re-runs IDEA-5 handshake.
    std::atomic<int64_t> sharedStartAtNs_{0};

    // Set to true only AFTER measurePipelineDepthNs() writes senderHwLatencyNs_.
    std::atomic<bool> measured_{false};

    // ── Pause / resume / seek state ───────────────────────────────────────────
    //
    // paused_:            streaming loop idles (no decode, no send) while true.
    // seekRequested_:     streaming loop seeks MP3 to seekTargetFrames_ before
    //                     re-applying the anchor. Cleared after seek completes.
    // seekTargetFrames_:  PCM frame index to seek to. Written by seekToMs(),
    //                     read by streamingLoop() (no race: seek clears streaming
    //                     loop's anchor so it is in idle state when it reads this).
    // currentFramePosition_: monotonically advancing PCM frame counter.
    //                     Written only by streamingLoop(), read by getPositionMs()
    //                     via atomic. Stored as atomic for thread-safe read from UI.
    // totalFrames_:       Set once by openDrMp3(). Read-only thereafter.
    std::atomic<bool>    paused_{false};
    std::atomic<bool>    seekRequested_{false};
    std::atomic<int64_t> seekTargetFrames_{0};
    std::atomic<int64_t> currentFramePosition_{0};
    int64_t              totalFrames_ = 0;

    // ── Network ───────────────────────────────────────────────────────────────
    int  tcpServerFd_ = -1;
    int  udpFd_       = -1;
    int  mp3Fd_       = -1;

    // ── Audio format ──────────────────────────────────────────────────────────
    enum class AudioFormat { MP3, AAC };
    AudioFormat audioFormat_ = AudioFormat::MP3;

    uint32_t sampleRate_   = 44100;
    int      channelCount_ = 1;

    // MP3 — dr_mp3 header-only decoder
    void*                mp3_     = nullptr;
    bool                 mp3Open_ = false;
    std::vector<uint8_t> mp3Data_;

    // AAC/M4A — MediaCodec NDK decoder
    std::unique_ptr<MediaCodecDecoder> aac_;

    // ── Clients ───────────────────────────────────────────────────────────────
    std::vector<ClientState*> clients_;
    std::mutex                clientsMutex_;

    // ── Threads ───────────────────────────────────────────────────────────────
    std::thread beaconThread_;
    std::thread acceptThread_;
    std::thread streamThread_;

    StatusCallback statusCb_;
    std::string    senderIP_;
    std::string    trackTitle_;
    std::string    trackArtist_;
    std::string    paletteHex1_;
    std::string    paletteHex2_;

// ── Local AAudio ──────────────────────────────────────────────────────────
    AAudioStream* localStream_ = nullptr;

    // ── Local playback role ───────────────────────────────────────────────────
    // Independent of what is streamed to receivers.
    // Defaults to SROLE_FULL (no filtering). Set via setSenderLocalRole().
    SenderRole                 localRole_      = SROLE_FULL;
    float                      localBassCutHz_ = 250.0f;
    float                      localTrebleCutHz_ = 4000.0f;
    std::unique_ptr<FirFilter> localFilter_;   // null when SROLE_FULL
    // Measured sender pipeline depth.
    // Written once by measurePipelineDepthNs(), then read-only.
    int64_t senderHwLatencyNs_ = 48'000'000LL;

    std::unique_ptr<LocalRing> localRing_;
    std::unique_ptr<LocalPool> localPool_;
    LocalChunk*                localCurrent_ = nullptr;



    // ── Calibration server (unchanged) ───────────────────────────────────────
    void startCalibServer();
    void stopCalibServer();
    void calibServerLoop();
    void handleCalibClient(int clientFd);
    int  calibServerFd_ = -1;
    std::thread calibServerThread_;
    std::unique_ptr<FirFilter> buildFilterForClient(ClientState* s);

};