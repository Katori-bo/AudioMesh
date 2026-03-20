#pragma once

#include <aaudio/AAudio.h>
#include <atomic>
#include <thread>
#include <mutex>
#include <vector>
#include <string>
#include <cstdint>
#include <memory>
#include <functional>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include "../common/Protocol.h"

// ─────────────────────────────────────────────────────────────────────────────
//  IDEA-5: Prebuffer-to-Silence
// ─────────────────────────────────────────────────────────────────────────────

static constexpr int SILENCE_PREBUFFER_MS         = 800;
static constexpr int SILENCE_WRITE_CHUNK_FRAMES   = 256;

static constexpr int64_t RECEIVER_HW_LATENCY_FALLBACK_NS = 200'000'000LL;
static constexpr int64_t REMEASURE_INTERVAL_NS           = 5'000'000'000LL;

// ─────────────────────────────────────────────────────────────────────────────

struct AudioChunk {
    int64_t  senderExitNs     = 0;
    int64_t  playAtReceiverNs = 0;
    uint8_t* data             = nullptr;
    int      length           = 0;
};

// ─────────────────────────────────────────────────────────────────────────────
//  ChunkRing — lock-free SPSC ring buffer
// ─────────────────────────────────────────────────────────────────────────────

class ChunkRing {
public:
    explicit ChunkRing(int capacity)
            : cap_(capacity + 1), buf_(capacity + 1) {}

    bool push(AudioChunk* c) {
        int w = write_.load(std::memory_order_relaxed);
        int next = (w + 1) % cap_;
        if (next == read_.load(std::memory_order_acquire)) return false;
        buf_[w] = c;
        write_.store(next, std::memory_order_release);
        return true;
    }

    AudioChunk* pop() {
        int r = read_.load(std::memory_order_relaxed);
        if (r == write_.load(std::memory_order_acquire)) return nullptr;
        AudioChunk* c = buf_[r];
        read_.store((r + 1) % cap_, std::memory_order_release);
        return c;
    }

    AudioChunk* peek() const {
        int r = read_.load(std::memory_order_relaxed);
        if (r == write_.load(std::memory_order_acquire)) return nullptr;
        return buf_[r];
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

    void restampAll(int64_t clockOffsetNs, int64_t newHwLatencyNs,
                    int64_t stampOffsetNs = 0, int64_t latencyNs = 0) {
        int r = read_.load(std::memory_order_acquire);
        int w = write_.load(std::memory_order_acquire);
        while (r != w) {
            buf_[r]->playAtReceiverNs =
                    buf_[r]->senderExitNs - clockOffsetNs - newHwLatencyNs
                    + stampOffsetNs + latencyNs;
            r = (r + 1) % cap_;
        }
    }

    void restampAll(int64_t deltaNs) {
        int r = read_.load(std::memory_order_acquire);
        int w = write_.load(std::memory_order_acquire);
        while (r != w) {
            buf_[r]->playAtReceiverNs += deltaNs;
            r = (r + 1) % cap_;
        }
    }

private:
    const int                cap_;
    std::vector<AudioChunk*> buf_;
    std::atomic<int>         read_{0}, write_{0};
};

// ─────────────────────────────────────────────────────────────────────────────
//  BufferPool
// ─────────────────────────────────────────────────────────────────────────────

class BufferPool {
public:
    explicit BufferPool(int count, int chunkSize) {
        ring_ = std::make_unique<ChunkRing>(count);
        storage_.resize(count);
        chunks_.resize(count);
        for (int i = 0; i < count; i++) {
            storage_[i].resize(chunkSize);
            chunks_[i].data = storage_[i].data();
            ring_->push(&chunks_[i]);
        }
    }

    AudioChunk* acquire() { return ring_->pop(); }

    void release(AudioChunk* c) {
        c->length           = 0;
        c->playAtReceiverNs = 0;
        c->senderExitNs     = 0;
        ring_->push(c);
    }

private:
    std::unique_ptr<ChunkRing>        ring_;
    std::vector<std::vector<uint8_t>> storage_;
    std::vector<AudioChunk>           chunks_;
};

// ─────────────────────────────────────────────────────────────────────────────
//  ReceiverEngine
// ─────────────────────────────────────────────────────────────────────────────

class ReceiverEngine {
public:
    using StatusCallback = std::function<void(const std::string& event,
                                              const std::string& detail)>;

    ReceiverEngine();
    ~ReceiverEngine();

    bool start(const std::string& role, int64_t latencyNs);
    void stop();

    bool isRunning() const { return running_.load(); }
    bool isPlaying() const { return playing_.load(); }

    void setLatencyNs(int64_t ns) {
        latencyNs_.store(ns, std::memory_order_relaxed);
    }

    void setHwLatencyNs(int64_t ns) {
        if (ns > 0) hwLatencyNs_.store(ns, std::memory_order_release);
    }

    int64_t getMeasuredHwLatencyNs() const {
        return measuredHwLatencyNs_.load(std::memory_order_acquire);
    }

    void setStatusCallback(StatusCallback cb) { statusCb_ = std::move(cb); }

    std::string getSenderIP()     const { return senderIP_; }
    std::string getAssignedRole() const { return assignedRole_; }

    // Force a switch to a new sender IP. Closes the current TCP connection
    // so the reconnect loop re-discovers / reconnects immediately.
    // Pass an empty string to force full re-discovery via UDP beacon.
    void switchSender(const std::string& newIP);

// AFTER
    std::string getConnectionStatus() const { return connectionStatus_; }
    std::string getTrackTitle()       const { return trackTitle_; }
    std::string getTrackArtist()      const { return trackArtist_; }
    std::string getPaletteHex1()      const { return paletteHex1_; }
    std::string getPaletteHex2()      const { return paletteHex2_; }
    int64_t getCurrentPositionMs() const {
        return currentPositionMs_.load(std::memory_order_relaxed);
    }

    int64_t getTrackDurationMs() const { return trackDurationMs_; }
    int64_t getClockOffsetNs() const {
        return clockOffsetNs_.load(std::memory_order_acquire);
    }

    int64_t getEmaDriftNs() const {
        return (int64_t)emaDrift_;
    }

    int getSilencePrebufferMs() const { return silencePrebufferMsActual_; }

    // Flush the jitter buffer and silence audio output immediately.
    // Called from Java AudioFocus listener on AUDIOFOCUS_LOSS_TRANSIENT /
    // AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK (phone calls, navigation prompts).
    //
    // Does NOT touch the TCP connection or trigger a re-handshake — those
    // are driven by the sender's PAUSE/RESUME TCP messages as normal.
    // This call provides immediate silence before the TCP PAUSE arrives,
    // and also drains up to 800ms of pre-buffered audio that would otherwise
    // play out while the phone call is active.
    //
    // Safe to call from any thread (playing_/streamReady_ are atomics;
    // jitterMutex_ protects the buffer drain).
    void flushAndSilence() {
        playing_.store(false, std::memory_order_release);
        streamReady_.store(false, std::memory_order_release);
        std::lock_guard<std::mutex> lk(jitterMutex_);
        AudioChunk* c;
        while ((c = jitterBuffer_->pop()) != nullptr) pool_->release(c);
    }


private:
    std::string discoverSender();
    bool        connectTCP(const std::string& ip);
    void        networkLoop();
    bool        readFully(int fd, void* buf, int len);

    int64_t     performClockSync();
    void        sendLine(const std::string& line);
    std::string readLine();

    bool    openAAudioStream();
    void    closeAAudioStream();

    int64_t prebufferSilence();
    void    resetForReconnect();
    void    resetForRehandshake();

    // Handle a control message received inline during the audio feed loop.
    // Returns true if the session loop should break (RESUME/SEEK → re-handshake).
    // Returns false if handled inline (PAUSE → continue feeding).
    bool    handleControlMessage(const char* cmd);

    int64_t measurePipelineDepthNs();
    void    diagnosePipeline();
    void    remeasureLoop();
    void    clockResyncLoop();

    static aaudio_data_callback_result_t audioCallback(
            AAudioStream*, void* userData, void* audioData, int32_t numFrames);
    aaudio_data_callback_result_t onAudioReady(void* audioData, int32_t numFrames);

    static int64_t nowNs();
    void           waitUntil(int64_t targetNs);
    void           fadeIn(uint8_t* buf, int len);

    // ── Atomics ───────────────────────────────────────────────────────────────
    std::atomic<bool>    running_{false};
    std::atomic<bool>    streamReady_{false};
    std::atomic<bool>    playing_{false};
    std::atomic<int64_t> clockOffsetNs_{0};
    std::atomic<int64_t> pendingNudgeNs_{0};
    std::atomic<int64_t> latencyNs_{0};
    std::atomic<int64_t> hwLatencyNs_{RECEIVER_HW_LATENCY_FALLBACK_NS};
    std::atomic<int64_t> measuredHwLatencyNs_{0};
    std::atomic<int64_t> stampOffsetNs_{0};
    std::atomic<int32_t> chunksPlayed_{0};

    // ── EMA drift (audio callback thread only) ────────────────────────────────
    double emaDrift_  = 0.0;
    bool   emaSeeded_ = false;

    // ── Fast-start calibration ────────────────────────────────────────────────
    static constexpr int     FAST_START_CHUNKS       = 40;
    static constexpr int64_t FAST_START_THRESHOLD_NS = 15'000'000LL; // 15ms min to correct

    int64_t fastStartSamples_[FAST_START_CHUNKS] = {};
    int     fastStartCount_ = 0;
    bool    fastStartDone_  = false;

// ── Long-EMA runtime drift correction ────────────────────────────────────
    static constexpr double  ALPHA_LONG                = 0.03;
    static constexpr int64_t SPIKE_GUARD_NS             = 15'000'000LL;  // 15ms
    static constexpr int64_t EMA_THRESHOLD_NS           = 15'000'000LL;  // 15ms accumulated
    static constexpr int64_t EMA_MAX_CORRECT_NS         = 50'000'000LL;  // 50ms — runtime EMA cap
    static constexpr int64_t FAST_START_MAX_CORRECT_NS  = 500'000'000LL; // 500ms — fast-start cap
    static constexpr int     EMA_SETTLE_CHUNKS          = 150;
    static constexpr int     EMA_COOLDOWN_CHUNKS        = 500;
    double  longEmaDrift_   = 0.0;
    bool    longEmaSeeded_  = false;
    int     emaCooldown_    = 0;

    // ── Auto-calibration (audio callback thread only) ─────────────────────────
    static constexpr int SETTLING_CHUNKS = 200;
    bool    settlingDone_       = false;
    int64_t startAtReceiverNs_  = 0;

    // ── Re-handshake-in-place flag ────────────────────────────────────────────
    //
    // Set to true by handleControlMessage() when RESUME or SEEK is received.
    // When true, the reconnect loop's next iteration:
    //   - skips resetForReconnect() (which would close tcpFd_ and AAudio stream)
    //   - skips Steps 1+2 (discovery + TCP connect) — tcpFd_ is still open
    //   - skips prebufferSilence() — hardware pipeline is already primed
    //   - goes directly to Step 3 (clock sync) on the existing connection
    //
    // This is the key fix that prevents the previous bug where resume caused
    // the receiver to hang on discoverSender() waiting for a beacon that never
    // came (because the sender reused the existing connection, not a new one).
    //
    // Cleared to false at the end of each successful re-handshake iteration.
    bool rehandshakeInPlace_ = false;

    // ── Config ────────────────────────────────────────────────────────────────
    std::string  role_;
    std::string  senderIP_;
    std::string  selfIP_;
    std::string  assignedRole_;     // role confirmed by sender during last handshake
    std::string  connectionStatus_; // last status event for UI polling
    int          sampleRate_        = DEFAULT_SAMPLE_RATE;
// AFTER
    int64_t      senderHwLatencyNs_ = 0;
    std::string  trackTitle_;
    std::string  trackArtist_;
    std::string  paletteHex1_;
    std::string  paletteHex2_;
    std::atomic<int64_t> currentPositionMs_{0};
    int64_t trackDurationMs_ = 0;

    int tcpFd_= -1;
    int udpListenFd_ = -1;

    int64_t pipelineFullNs_ = 0;
    int silencePrebufferMsActual_ = 0;

    // ── AAudio ────────────────────────────────────────────────────────────────
    AAudioStream* aaStream_ = nullptr;

    // ── Jitter buffer + pool ──────────────────────────────────────────────────
    std::unique_ptr<ChunkRing>  jitterBuffer_;
    std::unique_ptr<BufferPool> pool_;
    std::mutex                  jitterMutex_;

    // ── Playback state (callback thread only) ─────────────────────────────────
    AudioChunk* currentChunk_  = nullptr;
    int         currentOffset_ = 0;

    // ── Threads ───────────────────────────────────────────────────────────────
    std::thread    networkThread_;
    std::thread    remeasureThread_;
    std::thread    resyncThread_;
    StatusCallback statusCb_;
};