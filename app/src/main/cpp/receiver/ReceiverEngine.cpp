// ─────────────────────────────────────────────────────────────────────────────
//  ReceiverEngine.cpp  —  VERSION: v17-idea5-prebuffer-silence + pause/seek
// ─────────────────────────────────────────────────────────────────────────────
#include <ifaddrs.h>
#include "ReceiverEngine.h"
#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <cerrno>
#include <time.h>
#include <netinet/tcp.h>

#define TAG  "ReceiverEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

static constexpr int64_t REMEASURE_THRESHOLD_NS = 10'000'000LL;

// ─────────────────────────────────────────────────────────────────────────────
//  Control message protocol
//
//  The sender can send control messages (PAUSE, RESUME, SEEK) on the same TCP
//  connection as audio chunks. To distinguish them from binary audio headers,
//  control messages use a magic header:
//
//    header[0..7]  = 0x00 … 0x00  (zero timestamp)
//    header[8..11] = 0xFF 0xFF 0xFF 0xFF  (magic dataLen = CTRL_MAGIC_DATALEN)
//
//  After this 12-byte header the sender sends a 64-byte NUL-padded ASCII string.
//
//  PAUSE:   silence speaker, drain jitter buffer, stay in feed loop.
//           Sender streaming loop idles. TCP connection stays open.
//  RESUME:  flush buffer, set rehandshakeInPlace_=true, break to re-handshake.
//           Sender will immediately send ROLE: on the SAME existing TCP fd.
//  SEEK:N:  identical to RESUME on the receiver side. The sender seeked the MP3
//           decoder to N ms before spawning the re-handshake thread.
// ─────────────────────────────────────────────────────────────────────────────

static constexpr uint32_t CTRL_MAGIC_DATALEN = 0xFFFFFFFFu;

// ─────────────────────────────────────────────────────────────────────────────
//  Constructor / Destructor
// ─────────────────────────────────────────────────────────────────────────────

ReceiverEngine::ReceiverEngine()
        : jitterBuffer_(std::make_unique<ChunkRing>(MAX_JITTER_CHUNKS)),
          pool_(std::make_unique<BufferPool>(POOL_SIZE, CHUNK_BYTES))
{
    LOGD("ReceiverEngine constructed");
}

ReceiverEngine::~ReceiverEngine() { stop(); }

static std::string detectOwnIP() {
    struct ifaddrs* ifaddr = nullptr;
    if (::getifaddrs(&ifaddr) != 0) return "";
    std::string result;
    for (struct ifaddrs* ifa = ifaddr; ifa != nullptr; ifa = ifa->ifa_next) {
        if (!ifa->ifa_addr || ifa->ifa_addr->sa_family != AF_INET) continue;
        auto* sin = reinterpret_cast<struct sockaddr_in*>(ifa->ifa_addr);
        char buf[INET_ADDRSTRLEN];
        ::inet_ntop(AF_INET, &sin->sin_addr, buf, sizeof(buf));
        std::string ip(buf);
        if (ip.rfind("192.168.", 0) == 0 || ip.rfind("10.", 0) == 0) {
            result = ip;
            break;
        }
    }
    ::freeifaddrs(ifaddr);
    return result;
}
// ─────────────────────────────────────────────────────────────────────────────
//  start / stop
// ─────────────────────────────────────────────────────────────────────────────

bool ReceiverEngine::start(const std::string& role, int64_t latencyNs) {
    if (running_.load()) stop();
    role_ = role;
    connectionStatus_ = "CONNECTING";
    // Detect own IP so discoverSender() can ignore our own beacon
    selfIP_ = detectOwnIP();
    latencyNs_.store(latencyNs, std::memory_order_relaxed);

    running_.store(true);
    streamReady_.store(false);
    playing_.store(false);

    senderHwLatencyNs_ = 0;
    stampOffsetNs_.store(0, std::memory_order_relaxed);
    chunksPlayed_.store(0, std::memory_order_relaxed);
    pipelineFullNs_           = 0;
    silencePrebufferMsActual_ = 0;

    emaDrift_      = 0.0;
    emaSeeded_     = false;
    longEmaDrift_  = 0.0;
    longEmaSeeded_ = false;
    emaCooldown_   = 0;
    std::fill(fastStartSamples_, fastStartSamples_ + FAST_START_CHUNKS, 0LL);
    fastStartCount_    = 0;
    fastStartDone_     = false;
    settlingDone_      = false;
    startAtReceiverNs_ = 0;
    rehandshakeInPlace_ = false;

// Cache every status event so Java can poll it without a callback
    statusCb_ = [this](const std::string& event, const std::string& detail) {
        connectionStatus_ = event + (detail.empty() ? "" : ":" + detail);
        LOGI("[STATUS] %s %s", event.c_str(), detail.c_str());
    };

    networkThread_ = std::thread(&ReceiverEngine::networkLoop, this);    sched_param sp{};
    sp.sched_priority = sched_get_priority_max(SCHED_FIFO);
    pthread_setschedparam(networkThread_.native_handle(), SCHED_FIFO, &sp);

    LOGI("[IDEA5] started — role=%s  slider=%lld ms  silence=%d ms",
         role_.c_str(),
         (long long)(latencyNs / 1'000'000LL),
         SILENCE_PREBUFFER_MS);
    return true;
}

void ReceiverEngine::stop() {
    running_.store(false);
    streamReady_.store(false);
    playing_.store(false);
    stampOffsetNs_.store(0, std::memory_order_relaxed);

    if (udpListenFd_ != -1) { ::close(udpListenFd_); udpListenFd_ = -1; }
    if (tcpFd_ != -1) {
        ::shutdown(tcpFd_, SHUT_RDWR);
        ::close(tcpFd_);
        tcpFd_ = -1;
    }

    if (remeasureThread_.joinable()) remeasureThread_.join();
    if (resyncThread_.joinable())    resyncThread_.join();
    if (networkThread_.joinable())   networkThread_.join();

    closeAAudioStream();

    AudioChunk* c;
    while ((c = jitterBuffer_->pop()) != nullptr) pool_->release(c);
    if (currentChunk_) { pool_->release(currentChunk_); currentChunk_ = nullptr; }

    LOGI("stopped.");
}

// ─────────────────────────────────────────────────────────────────────────────
//  switchSender
//
//  Forces an immediate reconnect to a new sender IP (or re-discovery if empty).
//  Just closes the TCP fd — the reconnect loop wakes up on the next recv() error
//  and retries with the new senderIP_ value.
// ─────────────────────────────────────────────────────────────────────────────

void ReceiverEngine::switchSender(const std::string& newIP) {
    LOGI("[SWITCH] Switching sender → '%s'", newIP.empty() ? "(re-discover)" : newIP.c_str());
    senderIP_ = newIP;           // empty = re-discover via UDP beacon
    pipelineFullNs_ = 0;        // force full prebuffer on next connect
    rehandshakeInPlace_ = false; // force full reconnect, not in-place re-handshake

    // Close TCP so the blocking recv() in the feed loop returns immediately,
    // causing a session-end and triggering the reconnect loop.
    if (tcpFd_ != -1) {
        ::shutdown(tcpFd_, SHUT_RDWR);
        ::close(tcpFd_);
        tcpFd_ = -1;
    }
    if (statusCb_) statusCb_("RECONNECTING", newIP.empty() ? "re-discover" : newIP);
}
// ─────────────────────────────────────────────────────────────────────────────
//  Discovery
// ─────────────────────────────────────────────────────────────────────────────

std::string ReceiverEngine::discoverSender() {
    udpListenFd_ = ::socket(AF_INET, SOCK_DGRAM, 0);
    if (udpListenFd_ < 0) { LOGE("UDP socket: %s", strerror(errno)); return {}; }

    int reuse = 1;
    ::setsockopt(udpListenFd_, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));
    struct timeval tv{2, 0};
    ::setsockopt(udpListenFd_, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    sockaddr_in addr{};
    addr.sin_family      = AF_INET;
    addr.sin_port        = htons(MESH_UDP_RECV_PORT);
    addr.sin_addr.s_addr = INADDR_ANY;

    if (::bind(udpListenFd_, (sockaddr*)&addr, sizeof(addr)) < 0) {
        LOGE("UDP bind: %s", strerror(errno));
        ::close(udpListenFd_); udpListenFd_ = -1; return {};
    }

    LOGI("Listening for beacon on UDP %d…", MESH_UDP_RECV_PORT);
    if (statusCb_) statusCb_("DISCOVERING", "Waiting for sender beacon...");

    char buf[256]{};
    while (running_.load()) {
        sockaddr_in src{}; socklen_t slen = sizeof(src);
        int n = ::recvfrom(udpListenFd_, buf, sizeof(buf)-1, 0,
                           (sockaddr*)&src, &slen);
        if (n <= 0) continue;
        buf[n] = '\0';
        std::string msg(buf);
        while (!msg.empty() && (msg.back()=='\n'||msg.back()=='\r')) msg.pop_back();
        if (msg.rfind(BEACON_PREFIX, 0) != 0) continue;
        std::string rest = msg.substr(strlen(BEACON_PREFIX));
        size_t colon = rest.rfind(':');
        if (colon == std::string::npos) continue;
        std::string ip = rest.substr(0, colon);
// Ignore our own beacon — don't connect to ourselves
        if (ip == selfIP_) {
            LOGI("Ignoring own beacon from %s", ip.c_str());
            continue;
        }
        LOGI("Sender at %s", ip.c_str());
        ::close(udpListenFd_); udpListenFd_ = -1;
        return ip;
    }
    ::close(udpListenFd_); udpListenFd_ = -1;
    return {};
}

// ─────────────────────────────────────────────────────────────────────────────
//  resetForReconnect
//  Called on error drops and initial reconnect. NOT called on rehandshakeInPlace_
//  paths (RESUME/SEEK) — those reuse the existing tcpFd_ and AAudio stream.
// ─────────────────────────────────────────────────────────────────────────────

void ReceiverEngine::resetForReconnect() {
    streamReady_.store(false);
    playing_.store(false);

    if (tcpFd_ != -1) {
        ::shutdown(tcpFd_, SHUT_RDWR);
        ::close(tcpFd_);
        tcpFd_ = -1;
    }

    closeAAudioStream();

    AudioChunk* c;
    while ((c = jitterBuffer_->pop()) != nullptr) pool_->release(c);
    if (currentChunk_) {
        pool_->release(currentChunk_);
        currentChunk_ = nullptr;
    }
    currentOffset_ = 0;

    stampOffsetNs_.store(0, std::memory_order_relaxed);
    chunksPlayed_.store(0, std::memory_order_relaxed);
    pipelineFullNs_           = 0;
    silencePrebufferMsActual_ = 0;

    emaDrift_      = 0.0;
    emaSeeded_     = false;
    longEmaDrift_  = 0.0;
    longEmaSeeded_ = false;
    emaCooldown_   = 0;
    std::fill(fastStartSamples_, fastStartSamples_ + FAST_START_CHUNKS, 0LL);
    fastStartCount_ = 0;
    fastStartDone_  = false;
    settlingDone_      = false;
    startAtReceiverNs_ = 0;
}

// ─────────────────────────────────────────────────────────────────────────────
//  resetForRehandshake
//  Called on RESUME/SEEK paths. Keeps tcpFd_ and AAudio stream open.
//  Resets only the playback-state fields needed for a clean re-handshake.
// ─────────────────────────────────────────────────────────────────────────────

void ReceiverEngine::resetForRehandshake() {
    streamReady_.store(false);
    playing_.store(false);

    // Drain jitter buffer — stale chunks from before pause/seek are useless
    {
        std::lock_guard<std::mutex> lk(jitterMutex_);
        AudioChunk* c;
        while ((c = jitterBuffer_->pop()) != nullptr) pool_->release(c);
    }
    if (currentChunk_) {
        pool_->release(currentChunk_);
        currentChunk_ = nullptr;
    }
    currentOffset_ = 0;

    stampOffsetNs_.store(0, std::memory_order_relaxed);
    chunksPlayed_.store(0, std::memory_order_relaxed);

    emaDrift_      = 0.0;
    emaSeeded_     = false;
    longEmaDrift_  = 0.0;
    longEmaSeeded_ = false;
    emaCooldown_   = 0;
    std::fill(fastStartSamples_, fastStartSamples_ + FAST_START_CHUNKS, 0LL);
    fastStartCount_ = 0;
    fastStartDone_  = false;
    settlingDone_      = false;
    startAtReceiverNs_ = 0;

    // Mark pipeline as "running but needs re-anchoring" (-1 sentinel).
    // Step 6 checks this to skip prebufferSilence() but still use nowNs() as anchor.
    // Value 0 means "never primed" → full prebuffer.
    // Value -1 means "rehandshake in place" → skip prebuffer, use nowNs().
    // Value >0 means "primed timestamp" (set by prebufferSilence).
    pipelineFullNs_ = -1;

    // NOTE: tcpFd_, aaStream_, clockOffsetNs_, senderHwLatencyNs_, sampleRate_
    //       are all preserved — the re-handshake updates them via handshake sequence.
    LOGI("[REHANDSHAKE] State reset (TCP + AAudio preserved)");
}

// ─────────────────────────────────────────────────────────────────────────────
//  Network loop
// ─────────────────────────────────────────────────────────────────────────────

void ReceiverEngine::networkLoop() {
    LOGI("networkLoop started.");

    static constexpr int     MAX_RETRIES         = 10;
    static constexpr int64_t RECONNECT_DELAY_MS  = 2000;

    int  attempt         = 0;
    bool needsRediscover = senderIP_.empty();

    while (running_.load()) {

        // ── Re-handshake-in-place path (RESUME / SEEK) ────────────────────────
        // tcpFd_ is still open. The sender already sent ROLE: on it.
        // Skip discovery, TCP connect, AAudio open, prebuffer.
        // Run clock sync + READY/START exchange directly.
        if (rehandshakeInPlace_) {
            rehandshakeInPlace_ = false;
            LOGI("[REHANDSHAKE] Re-handshaking on existing connection (RESUME/SEEK)");
            resetForRehandshake();
            // Fall through to Step 3 below (skip Steps 0, 1, 2)
            goto step3_handshake;
        }

        // ── Step 0: back-off wait ─────────────────────────────────────────────
        if (attempt > 0) {
            LOGI("[RECONNECT] Attempt %d — waiting %lldms before retry...",
                 attempt, (long long)RECONNECT_DELAY_MS);
            if (statusCb_) statusCb_("RECONNECTING",
                                     "Attempt " + std::to_string(attempt));

            int64_t waitUntil = nowNs() + RECONNECT_DELAY_MS * 1'000'000LL;
            while (running_.load() && nowNs() < waitUntil) {
                struct timespec ts{0, 100'000'000LL};
                nanosleep(&ts, nullptr);
            }
            if (!running_.load()) break;

            if (needsRediscover || attempt >= 3) {
                LOGI("[RECONNECT] Re-discovering sender...");
                senderIP_.clear();
                needsRediscover = false;
            }

            resetForReconnect();
        }
        attempt++;

        // ── Step 1: discover ──────────────────────────────────────────────────
        if (senderIP_.empty()) {
            senderIP_ = discoverSender();
            if (senderIP_.empty()) {
                if (!running_.load()) break;
                LOGE("[RECONNECT] Discovery failed — will retry");
                needsRediscover = true;
                continue;
            }
            if (statusCb_) statusCb_("DISCOVERED", senderIP_);
        }

        // ── Step 2: TCP connect ───────────────────────────────────────────────
        if (!connectTCP(senderIP_)) {
            LOGE("[RECONNECT] TCP connect to %s failed", senderIP_.c_str());
            continue;
        }
        if (statusCb_) statusCb_("CONNECTED", senderIP_);

        // ── Step 3: Handshake ─────────────────────────────────────────────────
        step3_handshake:

        sendLine("ROLE:" + role_);

        // Read ASSIGNEDROLE: — sender tells us which role we were actually given.
        // Receiver's explicit choice always wins (sender echoes it back unchanged),
        // but if we sent "full" the sender may have applied its own override.
        // Falls back gracefully: if an old sender doesn't send this line and
        // sends SAMPLERATE: directly, we handle that in the srLine block below.
        {
            std::string arLine = readLine();
            if (arLine.rfind("ASSIGNEDROLE:", 0) == 0) {
                assignedRole_ = arLine.substr(13);
                LOGI("Assigned role: %s", assignedRole_.c_str());
                if (statusCb_) statusCb_("ROLE", assignedRole_);
            } else {
                // Old sender — no ASSIGNEDROLE line. Treat whatever came as srLine.
                // Re-parse it as the sample rate line below.
                assignedRole_ = role_;
                if (arLine.rfind("SAMPLERATE:", 0) == 0) {
                    sampleRate_ = std::stoi(arLine.substr(11));
                    LOGI("Sample rate (fallback path): %d Hz", sampleRate_);
                    goto step4_audio;
                }
            }
        }

        {
            std::string srLine = readLine();
            if (srLine.rfind("SAMPLERATE:", 0) == 0)
                sampleRate_ = std::stoi(srLine.substr(11));
            else
                sampleRate_ = DEFAULT_SAMPLE_RATE;
            LOGI("Sample rate: %d Hz", sampleRate_);
        }
        // Optional lines: TRACKINFO and PALETTE
        // Sender sends these after SAMPLERATE if set.
        // Read lines until we find SENDERLATENCY.
        {
            std::string nextLine = readLine();

            // Check for TRACKINFO
            if (nextLine.rfind("TRACKINFO:", 0) == 0) {
                std::string info = nextLine.substr(10);
                size_t sep1 = info.find('|');
                if (sep1 != std::string::npos) {
                    trackTitle_ = info.substr(0, sep1);
                    std::string rest = info.substr(sep1 + 1);
                    size_t sep2 = rest.find('|');
                    if (sep2 != std::string::npos) {
                        trackArtist_    = rest.substr(0, sep2);
                        try { trackDurationMs_ = std::stoll(rest.substr(sep2 + 1)); }
                        catch (...) { trackDurationMs_ = 0; }
                    } else {
                        trackArtist_   = rest;
                        trackDurationMs_ = 0;
                    }
                } else {
                    trackTitle_  = info;
                    trackArtist_ = "";
                    trackDurationMs_ = 0;
                }
                LOGI("TrackInfo: '%s' by '%s'",
                     trackTitle_.c_str(), trackArtist_.c_str());
                if (statusCb_) statusCb_("TRACKINFO",
                                         trackTitle_ + "|" + trackArtist_);
                nextLine = readLine();
            }

            // Check for PALETTE
            if (nextLine.rfind("PALETTE:", 0) == 0) {
                std::string pal = nextLine.substr(8);
                size_t comma = pal.find(',');
                if (comma != std::string::npos) {
                    paletteHex1_ = pal.substr(0, comma);
                    paletteHex2_ = pal.substr(comma + 1);
                }
                LOGI("Palette: %s  %s",
                     paletteHex1_.c_str(), paletteHex2_.c_str());
                if (statusCb_) statusCb_("PALETTE",
                                         paletteHex1_ + "," + paletteHex2_);
                nextLine = readLine();
            }

            // nextLine must now be SENDERLATENCY
            if (nextLine.rfind("SENDERLATENCY:", 0) == 0) {
                senderHwLatencyNs_ = std::stoll(nextLine.substr(14));
                LOGI("Sender HW latency: %lld ms",
                     (long long)(senderHwLatencyNs_ / 1'000'000LL));
            } else {
                senderHwLatencyNs_ = 0;
                LOGE("Expected SENDERLATENCY:, got '%s' — defaulting to 0ms",
                     nextLine.c_str());
            }
        }

        step4_audio:
        // ── Step 4: Open AAudio stream (skip on rehandshake) ──────────────────
        // On rehandshake, aaStream_ is still open and running — reuse it.
        // The hardware pipeline is already primed; no prebuffer needed.
        if (!aaStream_) {
            if (!openAAudioStream()) {
                LOGE("[RECONNECT] AAudio open failed — retrying");
                continue;
            }
        } else {
            LOGI("[REHANDSHAKE] AAudio stream preserved — skipping open");
        }

        // ── Step 5: Clock sync ────────────────────────────────────────────────
        int64_t offset = performClockSync();
        clockOffsetNs_.store(offset, std::memory_order_release);
        LOGI("Clock offset: %lld ms", (long long)(offset / 1'000'000LL));

        // ── Step 6: Pre-fill pipeline with silence (skip on rehandshake) ──────
        // On rehandshake the pipeline is already primed — skip prebuffer.
        // Send READY with the current time as a placeholder; the sender will
        // compute a fresh startAt from it.
        int64_t pfNs = 0;
        if (pipelineFullNs_ == 0) {
            // Normal path: pipeline has never been primed — do full prebuffer.
            pfNs = prebufferSilence();
            if (pfNs == 0) {
                LOGE("[RECONNECT] prebufferSilence failed — retrying");
                continue;
            }
        } else if (pipelineFullNs_ == -1) {
            // Rehandshake path: pipeline is running (seek/resume), skip prebuffer.
            pfNs = nowNs();
            LOGI("[REHANDSHAKE] Skipping prebuffer — using nowNs as pfNs anchor");
        } else {
            // Fallback: pipelineFullNs_ has a real timestamp but we ended up here
            // somehow — treat as rehandshake and use nowNs() safely.
            pfNs = nowNs();
            LOGI("[REHANDSHAKE] Fallback path — pipelineFullNs_=%lld ms, using nowNs",
                 (long long)(pipelineFullNs_ / 1'000'000LL));
        }
        int64_t pfNs_senderClock = pfNs + offset;
        sendLine("READY:" + std::to_string(pfNs_senderClock));
        LOGI("[IDEA5] Sent READY — pipelineFullNs=%lld ms in sender clock  silence=%d ms",
             (long long)(pfNs_senderClock / 1'000'000LL),
             SILENCE_PREBUFFER_MS);

        // ── Step 7: Read START ────────────────────────────────────────────────
        std::string startLine = readLine();
        if (startLine.rfind("START:", 0) != 0) {
            LOGE("[RECONNECT] Expected START:, got '%s' — retrying", startLine.c_str());
            continue;
        }

        int64_t startAtSenderNs   = std::stoll(startLine.substr(6));
        int64_t startAtReceiverNs = startAtSenderNs - offset;

        {
            int64_t now = nowNs();
            if (startAtReceiverNs < now) {
                LOGE("[IDEA5] start %lld ms in past — clamping to now+600ms",
                     (long long)((now - startAtReceiverNs) / 1'000'000LL));
                startAtReceiverNs = now + 600'000'000LL;
            } else if (startAtReceiverNs > now + 12'000'000'000LL) {
                LOGE("[IDEA5] start too far ahead — clamping to 12s");
                startAtReceiverNs = now + 12'000'000'000LL;
            }
            LOGI("[IDEA5] Playback starts in %lld ms  (silence=%d ms  slider=%lld ms)",
                 (long long)((startAtReceiverNs - now) / 1'000'000LL),
                 SILENCE_PREBUFFER_MS,
                 (long long)(latencyNs_.load() / 1'000'000LL));
        }

        startAtReceiverNs_ = startAtReceiverNs;

        if (statusCb_) statusCb_("HANDSHAKE_OK", role_);

        // ── Step 8: Read first chunk ──────────────────────────────────────────
        {
            AudioChunk* chunk = pool_->acquire();
            while (!chunk && running_.load()) {
                struct timespec ts{0, 500'000}; nanosleep(&ts, nullptr);
                chunk = pool_->acquire();
            }
            if (!running_.load()) break;

            uint8_t header[HEADER_BYTES];
            if (!readFully(tcpFd_, header, HEADER_BYTES)) {
                pool_->release(chunk);
                LOGE("[RECONNECT] Lost connection reading first chunk header");
                continue;
            }

            uint32_t firstDataLen =
                    ((uint32_t)header[8]<<24)|((uint32_t)header[9]<<16)|
                    ((uint32_t)header[10]<<8)| (uint32_t)header[11];
            if (firstDataLen == CTRL_MAGIC_DATALEN) {
                pool_->release(chunk);
                char ctrlBuf[65]{};
                readFully(tcpFd_, ctrlBuf, 64);
                LOGI("[CTRL] Control message before first chunk: '%s' — retrying", ctrlBuf);
                continue;
            }

            int64_t senderExitNs =
                    (int64_t)header[0]<<56|(int64_t)header[1]<<48|
                    (int64_t)header[2]<<40|(int64_t)header[3]<<32|
                    (int64_t)header[4]<<24|(int64_t)header[5]<<16|
                    (int64_t)header[6]<< 8|(int64_t)header[7];
            int dataLen = (int)header[8]<<24|(int)header[9]<<16|
                          (int)header[10]<<8|(int)header[11];

            if (dataLen <= 0 || dataLen > CHUNK_BYTES) {
                pool_->release(chunk);
                LOGE("[RECONNECT] Bad first chunk dataLen=%d", dataLen);
                continue;
            }
            if (!readFully(tcpFd_, chunk->data, dataLen)) {
                pool_->release(chunk);
                LOGE("[RECONNECT] Lost connection reading first chunk data");
                continue;
            }

            chunk->senderExitNs     = senderExitNs;
            chunk->playAtReceiverNs = senderExitNs
                                      - clockOffsetNs_.load(std::memory_order_relaxed)
                                      + latencyNs_.load(std::memory_order_relaxed);
            chunk->length = dataLen;
            fadeIn(chunk->data, dataLen);

            {
                int64_t now  = nowNs();
                int64_t lead = chunk->playAtReceiverNs - now;
                stampOffsetNs_.store(0, std::memory_order_release);
                LOGI("[IDEA5] Chunk#1 lead=%lld ms  silence=%d ms  txHw=%lld ms  (no clamp)",
                     (long long)(lead / 1'000'000LL),
                     SILENCE_PREBUFFER_MS,
                     (long long)(senderHwLatencyNs_ / 1'000'000LL));
            }

            {
                std::lock_guard<std::mutex> lk(jitterMutex_);
                jitterBuffer_->push(chunk);
            }
            LOGI("[IDEA5] First chunk — plays in %lld ms  opening gate.",
                 (long long)((chunk->playAtReceiverNs - nowNs()) / 1'000'000LL));
        }

        if (!running_.load()) break;

        // ── Step 9: Playback ──────────────────────────────────────────────────
        attempt = 0;
        chunksPlayed_.store(0, std::memory_order_relaxed);
        emaDrift_      = 0.0;
        emaSeeded_     = false;
        longEmaDrift_  = 0.0;
        longEmaSeeded_ = false;
        emaCooldown_   = 0;
        std::fill(fastStartSamples_, fastStartSamples_ + FAST_START_CHUNKS, 0LL);
        fastStartCount_ = 0;
        fastStartDone_  = false;
        settlingDone_   = false;
        streamReady_.store(true,  std::memory_order_release);
        playing_.store(true,      std::memory_order_release);
        LOGI("[IDEA5] Playback gate open. silence=%d ms  stampOffset=0 ms",
             SILENCE_PREBUFFER_MS);
        if (statusCb_) statusCb_("PLAYING", role_);

        // ── Keep feeding ──────────────────────────────────────────────────────
        bool sessionEnded = false;
        while (running_.load()) {
            AudioChunk* chunk = pool_->acquire();
            if (!chunk) {
                uint8_t hdr[HEADER_BYTES];
                if (!readFully(tcpFd_, hdr, HEADER_BYTES)) { sessionEnded = true; break; }
                uint32_t dl32 = ((uint32_t)hdr[8]<<24)|((uint32_t)hdr[9]<<16)|
                                ((uint32_t)hdr[10]<<8)| (uint32_t)hdr[11];
                if (dl32 == CTRL_MAGIC_DATALEN) {
                    char ctrlBuf[65]{};
                    readFully(tcpFd_, ctrlBuf, 64);
                    handleControlMessage(ctrlBuf);
                    continue;
                }
                int dl = (int)dl32;
                if (dl > 0 && dl <= CHUNK_BYTES) {
                    uint8_t discard[CHUNK_BYTES];
                    readFully(tcpFd_, discard, dl);
                }
                continue;
            }

            uint8_t header[HEADER_BYTES];
            if (!readFully(tcpFd_, header, HEADER_BYTES)) {
                pool_->release(chunk);
                sessionEnded = true;
                break;
            }

            // ── Check for control message ─────────────────────────────────────
            uint32_t dl32 = ((uint32_t)header[8]<<24)|((uint32_t)header[9]<<16)|
                            ((uint32_t)header[10]<<8)| (uint32_t)header[11];
            if (dl32 == CTRL_MAGIC_DATALEN) {
                pool_->release(chunk);
                char ctrlBuf[65]{};
                readFully(tcpFd_, ctrlBuf, 64);
                bool shouldBreak = handleControlMessage(ctrlBuf);
                if (shouldBreak) {
                    // RESUME or SEEK — break to re-handshake in place
                    sessionEnded = false;
                    break;
                }
                continue; // PAUSE handled inline
            }

            int64_t senderExitNs =
                    (int64_t)header[0]<<56|(int64_t)header[1]<<48|
                    (int64_t)header[2]<<40|(int64_t)header[3]<<32|
                    (int64_t)header[4]<<24|(int64_t)header[5]<<16|
                    (int64_t)header[6]<< 8|(int64_t)header[7];

            int dataLen = (int)dl32;

            if (dataLen <= 0 || dataLen > CHUNK_BYTES) { pool_->release(chunk); sessionEnded = true; break; }
            if (!readFully(tcpFd_, chunk->data, dataLen)) { pool_->release(chunk); sessionEnded = true; break; }

            chunk->senderExitNs     = senderExitNs;
            chunk->playAtReceiverNs = senderExitNs
                                      - clockOffsetNs_.load(std::memory_order_relaxed)
                                      + latencyNs_.load(std::memory_order_relaxed)
                                      + stampOffsetNs_.load(std::memory_order_acquire);
            chunk->length = dataLen;

            {
                int64_t nudge = pendingNudgeNs_.exchange(0, std::memory_order_acquire);
                if (nudge != 0) {
                    std::lock_guard<std::mutex> lk(jitterMutex_);
                    jitterBuffer_->restampAll(nudge);
                    LOGI("[IDEA5] nudge=%+lld ms applied to %d buffered chunks",
                         (long long)(nudge / 1'000'000LL), jitterBuffer_->size());
                }
            }

            // AFTER
            {
                std::lock_guard<std::mutex> lk(jitterMutex_);

                if (!jitterBuffer_->push(chunk)) {
                    // Buffer full — spin with a timeout before evicting.
                    static constexpr int     SPIN_LOG_MS  = 20;   // log after 20ms
                    static constexpr int     EVICT_MS     = 200;  // evict after 200ms
                    int spunMs = 0;
                    bool logged = false;

                    while (!jitterBuffer_->push(chunk) && running_.load()) {
                        jitterMutex_.unlock();
                        struct timespec ts{0, 1'000'000}; nanosleep(&ts, nullptr);
                        jitterMutex_.lock();
                        spunMs++;

                        if (!logged && spunMs >= SPIN_LOG_MS) {
                            LOGE("[JITTER] Buffer full (%d/%d chunks) — "
                                 "network burst or callback stall",
                                 jitterBuffer_->size(), MAX_JITTER_CHUNKS);
                            logged = true;
                        }

                        if (spunMs >= EVICT_MS) {
                            // Oldest-drop eviction: pop the front chunk (most
                            // stale, already closest to or past its play time),
                            // release it to the pool, then push the new chunk.
                            AudioChunk* oldest = jitterBuffer_->pop();
                            if (oldest) {
                                pool_->release(oldest);
                                LOGE("[JITTER] Evicted oldest chunk after %d ms spin — "
                                     "dropped 1 chunk to make room", spunMs);
                            }
                            // push must succeed now — we just freed a slot
                            if (!jitterBuffer_->push(chunk)) {
                                // Should never happen, but if it does release
                                // the new chunk rather than leak it
                                pool_->release(chunk);
                                LOGE("[JITTER] Push failed even after eviction — "
                                     "releasing chunk");
                            }
                            break;
                        }
                    }
                }
            }
        }

        if (running_.load() && sessionEnded) {
            LOGI("[RECONNECT] Session dropped after %d chunks — will reconnect",
                 chunksPlayed_.load());
            if (statusCb_) statusCb_("RECONNECTING", "Session dropped");
        }

    } // end reconnect loop

    LOGI("networkLoop exited.");
    running_.store(false);
    if (statusCb_) statusCb_("STOPPED", "");
}

// ─────────────────────────────────────────────────────────────────────────────
//  handleControlMessage
// ─────────────────────────────────────────────────────────────────────────────

bool ReceiverEngine::handleControlMessage(const char* cmd) {
    LOGI("[CTRL] Received control: '%s'", cmd);

    if (strcmp(cmd, "PAUSE") == 0) {
        // Silence local speaker. Audio callback sees playing_=false → outputs silence.
        // Drain jitter buffer — timestamps won't be valid after resume.
        playing_.store(false, std::memory_order_release);
        streamReady_.store(false, std::memory_order_release);
        {
            std::lock_guard<std::mutex> lk(jitterMutex_);
            AudioChunk* c;
            while ((c = jitterBuffer_->pop()) != nullptr) pool_->release(c);
        }
        LOGI("[CTRL] PAUSE: audio silenced, jitter buffer drained");
        return false; // stay in feed loop
    }

    if (strcmp(cmd, "RESUME") == 0 || strncmp(cmd, "SEEK:", 5) == 0) {
        playing_.store(false, std::memory_order_release);
        streamReady_.store(false, std::memory_order_release);
        {
            std::lock_guard<std::mutex> lk(jitterMutex_);
            AudioChunk* c;
            while ((c = jitterBuffer_->pop()) != nullptr) pool_->release(c);
        }
        // Force full reconnect path (close AAudio, redo prebuffer).
        // Reusing the AAudio stream across seek causes the jitter buffer to
        // flood because the stream's internal frame counter keeps accumulating
        // from the original start time, making chunk playAtReceiverNs
        // timestamps appear twice as close together as they should be.
        rehandshakeInPlace_ = true;
        LOGI("[CTRL] %s: flushed, rehandshakeInPlace_=true, breaking to re-handshake", cmd);
        return true;
    }

    if (strncmp(cmd, "POSITION:", 9) == 0) {
        try {
            int64_t ms = std::stoll(std::string(cmd + 9));
            currentPositionMs_.store(ms, std::memory_order_relaxed);
        } catch (...) {}
        return false;
    }

    // Live track-info push from swapTrack — update title/artist/duration in-place
    // without a full re-handshake. Receiver UI polls these via JNI getters.
    if (strncmp(cmd, "TRACKINFO:", 10) == 0) {
        std::string info(cmd + 10);
        size_t sep1 = info.find('|');
        if (sep1 != std::string::npos) {
            trackTitle_ = info.substr(0, sep1);
            std::string rest = info.substr(sep1 + 1);
            size_t sep2 = rest.find('|');
            if (sep2 != std::string::npos) {
                trackArtist_ = rest.substr(0, sep2);
                try { trackDurationMs_ = std::stoll(rest.substr(sep2 + 1)); }
                catch (...) {}
            } else {
                trackArtist_ = rest;
            }
        }
        LOGI("[CTRL] TRACKINFO updated: '%s' by '%s'",
             trackTitle_.c_str(), trackArtist_.c_str());
        return false;
    }

    LOGI("[CTRL] Unknown control command: '%s' — ignoring", cmd);
    return false;}

// ─────────────────────────────────────────────────────────────────────────────
//  [IDEA-5] prebufferSilence
// ─────────────────────────────────────────────────────────────────────────────

int64_t ReceiverEngine::prebufferSilence() {
    if (!aaStream_) { LOGE("[IDEA5] prebufferSilence: no stream"); return 0; }

    aaudio_result_t r = AAudioStream_requestStart(aaStream_);
    if (r != AAUDIO_OK) {
        LOGE("[IDEA5] prebufferSilence: requestStart failed: %s",
             AAudio_convertResultToText(r));
        return 0;
    }
    LOGI("[IDEA5] AAudio stream started at %d Hz", sampleRate_);

    const int64_t targetFrames =
            (int64_t)sampleRate_ * SILENCE_PREBUFFER_MS / 1000;

    int64_t framesAtStart = AAudioStream_getFramesRead(aaStream_);
    if (framesAtStart < 0) framesAtStart = 0;

    LOGI("[IDEA5] prebufferSilence: waiting for %lld frames (%d ms) via getFramesRead...",
         (long long)targetFrames, SILENCE_PREBUFFER_MS);

    while (running_.load()) {
        int64_t framesNow = AAudioStream_getFramesRead(aaStream_);
        if (framesNow < 0) framesNow = framesAtStart;

        int64_t framesElapsed = framesNow - framesAtStart;
        if (framesElapsed >= targetFrames) break;

        struct timespec ts{0, 5'000'000};
        nanosleep(&ts, nullptr);
    }

    if (!running_.load()) return 0;

    pipelineFullNs_           = nowNs();
    silencePrebufferMsActual_ = SILENCE_PREBUFFER_MS;

    LOGI("[IDEA5] prebufferSilence complete: %d ms pushed  pipelineFullNs=%lld ms (receiver clock)",
         silencePrebufferMsActual_,
         (long long)(pipelineFullNs_ / 1'000'000LL));

    return pipelineFullNs_;
}

// ─────────────────────────────────────────────────────────────────────────────
//  Disabled loops
// ─────────────────────────────────────────────────────────────────────────────

void ReceiverEngine::remeasureLoop() { LOGI("remeasureLoop: disabled."); }
void ReceiverEngine::clockResyncLoop() {}

// ─────────────────────────────────────────────────────────────────────────────
//  AAudio open / close
// ─────────────────────────────────────────────────────────────────────────────

bool ReceiverEngine::openAAudioStream() {
    AAudioStreamBuilder* builder = nullptr;
    if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK) return false;

    AAudioStreamBuilder_setDirection(builder,       AAUDIO_DIRECTION_OUTPUT);
    // Always request 44100 Hz — universally supported by Android hardware.
    // Requesting unusual rates (22050, 32000) may be silently ignored by the OS
    // in SHARED mode, causing PCM data to play at the wrong speed.
    // The sender's timestamps encode wall-clock play times, so sync is unaffected.
    // PCM data at non-44100 rates will be slightly pitched if the sender file
    // differs, but this is far better than 0.5x speed from a rate mismatch.
    // The read-back below catches if the OS gives us something else (e.g. 48000).
    AAudioStreamBuilder_setSampleRate(builder,      44100);
    AAudioStreamBuilder_setChannelCount(builder,    1);
    AAudioStreamBuilder_setFormat(builder,          AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setDataCallback(builder,    ReceiverEngine::audioCallback, this);
    AAudioStreamBuilder_setFramesPerDataCallback(builder, 256);
#if __ANDROID_API__ >= 28
    AAudioStreamBuilder_setUsage(builder,       AAUDIO_USAGE_MEDIA);
    AAudioStreamBuilder_setContentType(builder, AAUDIO_CONTENT_TYPE_MUSIC);
#endif

    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_EXCLUSIVE);
    aaudio_result_t r = AAudioStreamBuilder_openStream(builder, &aaStream_);

    if (r != AAUDIO_OK) {
        LOGI("EXCLUSIVE unavailable (%s) — falling back to SHARED",
             AAudio_convertResultToText(r));
        AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
        r = AAudioStreamBuilder_openStream(builder, &aaStream_);
    }

    AAudioStreamBuilder_delete(builder);

    if (r != AAUDIO_OK) {
        LOGE("openStream failed: %s", AAudio_convertResultToText(r));
        aaStream_ = nullptr; return false;
    }

    // Read back the ACTUAL sample rate the OS granted.
    // In SHARED mode AAudio ignores the requested rate and opens at
    // the device native rate (commonly 48000 Hz). If we leave sampleRate_
    // at the sender's value (e.g. 44100 Hz) the audio callback feeds
    // wrong-sized chunks → playback runs at the wrong speed (0.5x, 0.9x etc).
    // sampleRate_ is set from the sender's SAMPLERATE: line and used as-is.
    // Do not read back the OS-granted rate — the sender stamps timestamps
    // based on the file's sample rate, so receiver timing must match that.

    aaudio_sharing_mode_t mode = AAudioStream_getSharingMode(aaStream_);
    bool isExclusive = (mode == AAUDIO_SHARING_MODE_EXCLUSIVE);
    LOGI("[IDEA5] AAudio stream opened — mode: %s  sampleRate=%d",
         isExclusive ? "EXCLUSIVE" : "SHARED", (int)sampleRate_);

    diagnosePipeline();

    LOGI("[IDEA5] Skipping hwLatency measurement — silence prebuffer handles pipeline depth.");

    return true;
}

void ReceiverEngine::closeAAudioStream() {
    if (aaStream_) {
        AAudioStream_requestStop(aaStream_);
        AAudioStream_close(aaStream_);
        aaStream_ = nullptr;
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Pipeline diagnostics
// ─────────────────────────────────────────────────────────────────────────────

void ReceiverEngine::diagnosePipeline() {
    if (!aaStream_) { LOGE("DIAG: no stream"); return; }

    int32_t sampleRate    = AAudioStream_getSampleRate(aaStream_);
    int32_t bufSize       = AAudioStream_getBufferSizeInFrames(aaStream_);
    int32_t bufCapacity   = AAudioStream_getBufferCapacityInFrames(aaStream_);
    int32_t burst         = AAudioStream_getFramesPerBurst(aaStream_);
    aaudio_sharing_mode_t sharing = AAudioStream_getSharingMode(aaStream_);

    LOGI("DIAG ══════════════════════════════════════════════════");
    LOGI("DIAG  sampleRate    = %d Hz", sampleRate);
    LOGI("DIAG  bufSizeFrames = %d  (%.1f ms)", bufSize,  bufSize  * 1000.0f / sampleRate);
    LOGI("DIAG  bufCapFrames  = %d  (%.1f ms)", bufCapacity, bufCapacity * 1000.0f / sampleRate);
    LOGI("DIAG  burstFrames   = %d  (%.1f ms)", burst, burst * 1000.0f / sampleRate);
    LOGI("DIAG  sharingMode   = %s",
         sharing == AAUDIO_SHARING_MODE_EXCLUSIVE ? "EXCLUSIVE" : "SHARED");
    LOGI("DIAG  [IDEA5] rxHw measurement skipped — silence prebuffer=%d ms owns pipeline depth",
         SILENCE_PREBUFFER_MS);
    LOGI("DIAG ══════════════════════════════════════════════════");
}

// ─────────────────────────────────────────────────────────────────────────────
//  measurePipelineDepthNs — retained for Java API compatibility
// ─────────────────────────────────────────────────────────────────────────────

int64_t ReceiverEngine::measurePipelineDepthNs() {
    if (!aaStream_) return RECEIVER_HW_LATENCY_FALLBACK_NS;
    int32_t bufSize = AAudioStream_getBufferSizeInFrames(aaStream_);
    int32_t burst   = AAudioStream_getFramesPerBurst(aaStream_);
    if (bufSize > 0 && burst > 0) {
        int64_t geomNs = ((int64_t)bufSize + burst) * 1'000'000'000LL / sampleRate_;
        LOGI("[IDEA5] measurePipelineDepthNs (geometry only, not used): %lld ms",
             (long long)(geomNs / 1'000'000LL));
        return geomNs;
    }
    return RECEIVER_HW_LATENCY_FALLBACK_NS;
}

// ─────────────────────────────────────────────────────────────────────────────
//  AAudio callback
// ─────────────────────────────────────────────────────────────────────────────

aaudio_data_callback_result_t ReceiverEngine::audioCallback(
        AAudioStream*, void* userData, void* audioData, int32_t numFrames) {
    return static_cast<ReceiverEngine*>(userData)->onAudioReady(audioData, numFrames);
}

aaudio_data_callback_result_t ReceiverEngine::onAudioReady(
        void* audioData, int32_t numFrames) {
    int16_t* out       = static_cast<int16_t*>(audioData);
    int      remaining = numFrames;

    if (!streamReady_.load(std::memory_order_acquire) ||
        !playing_.load(std::memory_order_acquire)) {
        std::memset(out, 0, numFrames * sizeof(int16_t));
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    while (remaining > 0) {
        if (!currentChunk_) {
            AudioChunk* next = jitterBuffer_->peek();
            if (!next) {
                std::memset(out, 0, remaining * sizeof(int16_t));
                return AAUDIO_CALLBACK_RESULT_CONTINUE;
            }

            int64_t effectivePlayAt = next->playAtReceiverNs;
            if (effectivePlayAt > nowNs()) {
                std::memset(out, 0, remaining * sizeof(int16_t));
                return AAUDIO_CALLBACK_RESULT_CONTINUE;
            }

            // Snapshot time BEFORE pop() and the atomic increment.
            // pop() + fetch_add can take 500µs-2ms on a loaded CPU, inflating
            // every EMA drift sample and biasing the correction negatively.
            int64_t arrivalNs = nowNs();

            currentChunk_  = jitterBuffer_->pop();
            currentOffset_ = 0;

            int32_t n = chunksPlayed_.fetch_add(1, std::memory_order_relaxed) + 1;

            if (n <= 5 || n % 50 == 0) {
                LOGI("[STAMP] chunk#%d senderExit=%lld ms  playAt=%lld ms  "
                     "clockOff=%lld ms  stampOff=%lld ms",
                     (int)n,
                     (long long)(currentChunk_->senderExitNs                    / 1'000'000LL),
                     (long long)(currentChunk_->playAtReceiverNs                / 1'000'000LL),
                     (long long)(clockOffsetNs_.load(std::memory_order_relaxed) / 1'000'000LL),
                     (long long)(stampOffsetNs_.load(std::memory_order_relaxed) / 1'000'000LL));
            }

            {
                int64_t drift = effectivePlayAt - arrivalNs;

                // ── Short EMA (display / diagnostics only, α=0.05) ───────────
                if (n > 20) {
                    constexpr double EMA_ALPHA = 0.05;
                    if (!emaSeeded_) {
                        emaDrift_  = (double)drift;
                        emaSeeded_ = true;
                    } else {
                        emaDrift_ = EMA_ALPHA * (double)drift
                                    + (1.0 - EMA_ALPHA) * emaDrift_;
                    }
                }

                // ── Long EMA (α=0.02, ~50-chunk window) ──────────────────────
                if (!longEmaSeeded_) {
                    longEmaDrift_ = (double)drift;
                    longEmaSeeded_ = true;
                } else {
                    longEmaDrift_ = ALPHA_LONG * (double)drift
                                    + (1.0 - ALPHA_LONG) * longEmaDrift_;
                }

                // ── Fast-start calibration ────────────────────────────────────
                if (!fastStartDone_) {
                    if (fastStartCount_ < FAST_START_CHUNKS) {
                        fastStartSamples_[fastStartCount_++] = drift;
                    }
                    if (fastStartCount_ == FAST_START_CHUNKS) {
                        fastStartDone_ = true;

                        int64_t sorted[FAST_START_CHUNKS];
                        std::copy(fastStartSamples_,
                                  fastStartSamples_ + FAST_START_CHUNKS, sorted);
                        std::sort(sorted, sorted + FAST_START_CHUNKS);
                        int64_t median = sorted[FAST_START_CHUNKS / 2];
                        int64_t absMedian = median < 0 ? -median : median;

                        if (absMedian >= FAST_START_THRESHOLD_NS
                            && absMedian <= FAST_START_MAX_CORRECT_NS) {
                            stampOffsetNs_.fetch_add(-median, std::memory_order_relaxed);
                            longEmaDrift_  = 0.0;
                            LOGI("[IDEA5] FAST-START chunk#%d: "
                                 "median=%lld ms  correction=%lld ms  "
                                 "newStampOffset=%lld ms",
                                 (int)n,
                                 (long long)(median / 1'000'000LL),
                                 (long long)(median / 1'000'000LL),
                                 (long long)(stampOffsetNs_.load() / 1'000'000LL));
                        } else {
                            LOGI("[IDEA5] FAST-START chunk#%d: "
                                 "median=%lld ms — outside correction range [%lld, %lld] ms, no correction",
                                 (int)n,
                                 (long long)(median / 1'000'000LL),
                                 (long long)(FAST_START_THRESHOLD_NS / 1'000'000LL),
                                 (long long)(FAST_START_MAX_CORRECT_NS / 1'000'000LL));
                        }
                    }
                }

                // ── Long-EMA runtime correction ───────────────────────────────
                if (fastStartDone_ && n > EMA_SETTLE_CHUNKS) {
                    if (emaCooldown_ > 0) emaCooldown_--;

                    int64_t longEmaNs  = (int64_t)longEmaDrift_;
                    int64_t absLongEma = longEmaNs < 0 ? -longEmaNs : longEmaNs;
                    int64_t absDeltaFromEma = drift - longEmaNs;
                    if (absDeltaFromEma < 0) absDeltaFromEma = -absDeltaFromEma;

                    bool spikeActive = (absDeltaFromEma > SPIKE_GUARD_NS);

                    if (!spikeActive
                        && absLongEma >= EMA_THRESHOLD_NS
                        && absLongEma <= EMA_MAX_CORRECT_NS
                        && emaCooldown_ == 0) {

                        stampOffsetNs_.fetch_add(-longEmaNs, std::memory_order_relaxed);
                        LOGI("[IDEA5] EMA-CORRECT chunk#%d: "
                             "longEMA=%lld ms  correction=%lld ms  "
                             "newStampOffset=%lld ms",
                             (int)n,
                             (long long)(longEmaNs / 1'000'000LL),
                             (long long)(longEmaNs / 1'000'000LL),
                             (long long)(stampOffsetNs_.load() / 1'000'000LL));

                        longEmaDrift_  = 0.0;
                        emaCooldown_   = EMA_COOLDOWN_CHUNKS;
                    }
                }

                settlingDone_ = (n >= SETTLING_CHUNKS);

                if (n <= 3 || n % 50 == 0) {
                    int64_t longEmaNs = (int64_t)longEmaDrift_;
                    LOGI("[IDEA5] SYNC chunk#%d: "
                         "drift=%lld ms  shortEMA=%lld ms  longEMA=%lld ms  "
                         "cd=%d  silence=%d ms  txHw=%lld ms  stampOffset=%lld ms",
                         (int)n,
                         (long long)(drift        / 1'000'000LL),
                         (long long)((int64_t)emaDrift_ / 1'000'000LL),
                         (long long)(longEmaNs    / 1'000'000LL),
                         emaCooldown_,
                         SILENCE_PREBUFFER_MS,
                         (long long)(senderHwLatencyNs_ / 1'000'000LL),
                         (long long)(stampOffsetNs_.load() / 1'000'000LL));
                }
            }
        }

        int avail  = (currentChunk_->length - currentOffset_) / (int)sizeof(int16_t);
        int frames = std::min(remaining, avail);

        std::memcpy(out, currentChunk_->data + currentOffset_,
                    frames * sizeof(int16_t));
        out            += frames;
        currentOffset_ += frames * (int)sizeof(int16_t);
        remaining      -= frames;

        if (currentOffset_ >= currentChunk_->length) {
            pool_->release(currentChunk_);
            currentChunk_ = nullptr;
        }
    }

    // ── getTimestamp() hardware correction ───────────────────────────────────
    {
        static constexpr int     TS_CORRECT_INTERVAL = 200;
        static constexpr int64_t TS_MIN_CORRECT_NS   = 2'000'000LL;
        static constexpr int64_t TS_MAX_CORRECT_NS   = 30'000'000LL;

        int32_t cp = chunksPlayed_.load(std::memory_order_relaxed);
        if (settlingDone_ && aaStream_ && (cp % TS_CORRECT_INTERVAL == 0) && cp > 0) {
            struct timespec tsMono{}, tsBoot{};
            clock_gettime(CLOCK_MONOTONIC, &tsMono);
            clock_gettime(CLOCK_BOOTTIME,  &tsBoot);
            int64_t monoNs = tsMono.tv_sec * 1'000'000'000LL + tsMono.tv_nsec;
            int64_t bootNs = tsBoot.tv_sec * 1'000'000'000LL + tsBoot.tv_nsec;
            int64_t monoToBootOffset = bootNs - monoNs;

            int64_t hwFrame = 0, hwTimeMonoNs = 0;
            aaudio_result_t r = AAudioStream_getTimestamp(
                    aaStream_, CLOCK_MONOTONIC, &hwFrame, &hwTimeMonoNs);
            int64_t hwTimeNs = (r == AAUDIO_OK) ? hwTimeMonoNs + monoToBootOffset : 0;

            if (r == AAUDIO_OK && hwFrame > 0 && hwTimeNs > 0) {
                if (startAtReceiverNs_ > 0) {
                    int64_t expectedTimeNs = startAtReceiverNs_
                                             + hwFrame * 1'000'000'000LL / (int64_t)sampleRate_;
                    int64_t error      = expectedTimeNs - hwTimeNs;
                    int64_t absError   = error < 0 ? -error : error;

                    if (absError >= TS_MIN_CORRECT_NS && absError <= TS_MAX_CORRECT_NS) {
                        stampOffsetNs_.fetch_add(error, std::memory_order_relaxed);
                        LOGI("[IDEA5] TS-CORRECT chunk#%d: "
                             "hwFrame=%lld  hwTime=%lld ms  "
                             "expected=%lld ms  error=%lld ms  "
                             "newStampOffset=%lld ms",
                             (int)cp,
                             (long long)hwFrame,
                             (long long)(hwTimeNs       / 1'000'000LL),
                             (long long)(expectedTimeNs / 1'000'000LL),
                             (long long)(error          / 1'000'000LL),
                             (long long)(stampOffsetNs_.load() / 1'000'000LL));
                    }
                }
            }
        }
    }

    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

// ─────────────────────────────────────────────────────────────────────────────
//  TCP helpers
// ─────────────────────────────────────────────────────────────────────────────

bool ReceiverEngine::connectTCP(const std::string& ip) {
    tcpFd_ = ::socket(AF_INET, SOCK_STREAM, 0);
    if (tcpFd_ < 0) { LOGE("socket: %s", strerror(errno)); return false; }
    int flag = 1;
    ::setsockopt(tcpFd_, IPPROTO_TCP, TCP_NODELAY, &flag, sizeof(flag));
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port   = htons(MESH_TCP_PORT);
    ::inet_pton(AF_INET, ip.c_str(), &addr.sin_addr);
    if (::connect(tcpFd_, (sockaddr*)&addr, sizeof(addr)) < 0) {
        LOGE("connect %s:%d: %s", ip.c_str(), MESH_TCP_PORT, strerror(errno));
        ::close(tcpFd_); tcpFd_ = -1; return false;
    }
    LOGI("TCP connected to %s:%d", ip.c_str(), MESH_TCP_PORT);
    return true;
}

bool ReceiverEngine::readFully(int fd, void* buf, int len) {
    uint8_t* p = static_cast<uint8_t*>(buf);
    int done = 0;
    while (done < len) {
        int n = ::recv(fd, p + done, len - done, 0);
        if (n <= 0) return false;
        done += n;
    }
    return true;
}

void ReceiverEngine::sendLine(const std::string& line) {
    std::string msg = line + "\n";
    ::send(tcpFd_, msg.c_str(), msg.size(), MSG_NOSIGNAL);
}

std::string ReceiverEngine::readLine() {
    std::string result;
    char c;
    while (running_.load()) {
        int n = ::recv(tcpFd_, &c, 1, 0);
        if (n <= 0) break;
        if (c == '\n') break;
        if (c != '\r') result += c;
    }
    return result;
}

// ─────────────────────────────────────────────────────────────────────────────
//  Clock sync
// ─────────────────────────────────────────────────────────────────────────────

int64_t ReceiverEngine::performClockSync() {
    std::vector<int64_t> offsets;
    offsets.reserve(SYNC_ROUNDS);

    for (int i = 0; i < SYNC_ROUNDS && running_.load(); i++) {
        int64_t t1 = nowNs();
        sendLine("PING:" + std::to_string(t1));
        std::string pong = readLine();
        int64_t t3 = nowNs();

        if (pong.rfind("PONG:", 0) != 0) continue;
        size_t c2 = pong.rfind(':');
        if (c2 == std::string::npos || c2 <= 4) continue;

        int64_t t2     = std::stoll(pong.substr(c2 + 1));
        int64_t rtt    = t3 - t1;
        int64_t offset = t2 - t1 - rtt / 2;
        offsets.push_back(offset);

        LOGD("sync[%d]: rtt=%lld ms  offset=%lld ms",
             i, (long long)(rtt/1'000'000LL), (long long)(offset/1'000'000LL));
        struct timespec ts{0, 10'000'000}; nanosleep(&ts, nullptr);
    }

    if (offsets.empty()) { LOGE("performClockSync: no samples"); return 0LL; }

    std::sort(offsets.begin(), offsets.end());
    int trim = (int)(offsets.size() * 0.2f);
    int s = trim, e = (int)offsets.size() - trim;
    if (s >= e) { s = 0; e = (int)offsets.size(); }

    int64_t median = offsets[(s + e) / 2];
    LOGI("Clock sync done: offset=%lld ms (%d samples)",
         (long long)(median/1'000'000LL), (int)offsets.size());
    return median;
}

// ─────────────────────────────────────────────────────────────────────────────
//  Timing
// ─────────────────────────────────────────────────────────────────────────────

int64_t ReceiverEngine::nowNs() {
    struct timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1'000'000'000LL + ts.tv_nsec;
}

void ReceiverEngine::waitUntil(int64_t targetNs) {
    const int64_t deadline = nowNs() + MAX_WAIT_NS;
    while (running_.load()) {
        int64_t now = nowNs(), rem = targetNs - now;
        if (rem <= 0) return;
        if (now >= deadline) { LOGE("waitUntil timeout"); return; }
        if (rem > 2'000'000LL) {
            int64_t sleepNs = std::min(rem - 2'000'000LL, deadline - now - 2'000'000LL);
            if (sleepNs > 0) {
                struct timespec ts{};
                ts.tv_sec  = sleepNs / 1'000'000'000LL;
                ts.tv_nsec = sleepNs % 1'000'000'000LL;
                nanosleep(&ts, nullptr);
            }
        }
    }
}

void ReceiverEngine::fadeIn(uint8_t* buf, int len) {
    int fade = std::min(FADE_IN_SAMPLES, len / 2);
    for (int i = 0; i < fade; i++) {
        int byteIdx = i * 2;
        int16_t s = (int16_t)((buf[byteIdx+1]<<8)|(buf[byteIdx]&0xFF));
        int16_t f = (int16_t)(s * (float)i / (float)fade);
        buf[byteIdx]   = (uint8_t)( f     & 0xFF);
        buf[byteIdx+1] = (uint8_t)((f>>8) & 0xFF);
    }
}