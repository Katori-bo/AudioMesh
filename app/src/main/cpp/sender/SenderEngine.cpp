#include "SenderEngine.h"

#define DR_MP3_IMPLEMENTATION
#include "../third_party/dr_mp3.h"
#include <android/log.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>
#include <time.h>

#include <algorithm>
#include <vector>
#include <cstring>
#include <ctime>

#define TAG "SenderEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

static constexpr int64_t SENDER_HW_LATENCY_FALLBACK_NS = 48'000'000LL;  // 48 ms

// ── IDEA-5 constants ──────────────────────────────────────────────────────────
// IDEA5_SILENCE_DRAIN_NS must equal SILENCE_PREBUFFER_MS in ReceiverEngine.h
// (both 800 ms). If you change one, change both.
static constexpr int64_t IDEA5_SILENCE_DRAIN_NS  = 800'000'000LL; // 800 ms
static constexpr int64_t IDEA5_NETWORK_MARGIN_NS = 200'000'000LL; // 200 ms
// ─────────────────────────────────────────────────────────────────────────────

// ── Control message protocol ──────────────────────────────────────────────────
// Sent on the same TCP stream as audio chunks. Receivers detect the magic
// dataLen (0xFFFFFFFF) and dispatch the NUL-terminated command string.
//
//  header[0..7]  = 0x00 0x00 … 0x00  (zero timestamp — not a real audio packet)
//  header[8..11] = 0xFF 0xFF 0xFF 0xFF (magic: CTRL_MAGIC_DATALEN)
//  body[0..63]   = NUL-terminated ASCII command, zero-padded to 64 bytes
//
// Commands:
//   PAUSE   — receiver silences speaker, drains jitter buffer, stays connected.
//             Sender streaming loop idles until resume() is called.
//   RESUME  — receiver flushes buffer, breaks to re-handshake on existing TCP.
//             Sender re-runs IDEA-5 handshake (ROLE→SYNC→READY→START) then resumes.
//   SEEK:N  — like RESUME but sender first seeks MP3 decoder to the frame at N ms.
//             Receiver treats it identically to RESUME (flush + re-handshake).
static constexpr uint32_t CTRL_MAGIC_DATALEN = 0xFFFFFFFFu;
static constexpr int      CTRL_BODY_LEN      = 64;
static constexpr int      HEADER_CTRL_LEN    = 12 + CTRL_BODY_LEN; // 76 bytes total

// ─────────────────────────────────────────────────────────────────────────────
//  Helpers
// ─────────────────────────────────────────────────────────────────────────────

int64_t SenderEngine::nowNs() {
    struct timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1'000'000'000LL + ts.tv_nsec;
}

bool SenderEngine::sendRaw(int sockFd, const void* buf, int len) {
    const auto* p = static_cast<const uint8_t*>(buf);
    int sent = 0;
    while (sent < len) {
        int n = ::send(sockFd, p + sent, len - sent, MSG_NOSIGNAL);
        if (n <= 0) return false;
        sent += n;
    }
    return true;
}

// ─────────────────────────────────────────────────────────────────────────────
//  cleanDeadClients — call with clientsMutex_ HELD
//
//  Sweeps clients_ for entries with dead=true, closes their fd, and erases
//  them. Uses mark-then-sweep because the streaming loop cannot safely erase
//  while iterating (iterator invalidation). dead=true is set whenever
//  sendRaw() fails in the streaming loop or in sendControlToAll().
// ─────────────────────────────────────────────────────────────────────────────

void SenderEngine::cleanDeadClients() {
    auto it = clients_.begin();
    while (it != clients_.end()) {
        ClientState* s = *it;
        if (!s->dead) { ++it; continue; }
        LOGI("[CLEANUP] Removing dead client %s (fd=%d)", s->addr.c_str(), s->fd);
        if (s->fd >= 0) {
            ::shutdown(s->fd, SHUT_RDWR);
            ::close(s->fd);
            s->fd = -1;
        }
        if (s->handshakeThread.joinable()) s->handshakeThread.detach();
        delete s;
        it = clients_.erase(it);
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  buildFilterForClient
//
//  Constructs a FirFilter for the given client based on its role and current
//  crossover parameters. Returns nullptr for SROLE_FULL (no filtering needed).
//  Called from handshakeClient() at connect time and from setClientCrossover()
//  when the UI changes crossover frequencies mid-session.
//  Must be called with clientsMutex_ held OR before the client is ready
//  (i.e. from handshakeClient before s->ready = true).
// ─────────────────────────────────────────────────────────────────────────────

std::unique_ptr<FirFilter> SenderEngine::buildFilterForClient(ClientState* s) {
    switch (s->role) {
        case SROLE_BASS:
            return std::make_unique<FirFilter>(
                    FirFilter::Type::LowPass,
                    (double)s->bassCutHz,
                    (int)sampleRate_);
        case SROLE_MID:
            return std::make_unique<FirFilter>(
                    (double)s->bassCutHz,
                    (double)s->trebleCutHz,
                    (int)sampleRate_);
        case SROLE_TREBLE:
            return std::make_unique<FirFilter>(
                    FirFilter::Type::HighPass,
                    (double)s->trebleCutHz,
                    (int)sampleRate_);
        default:
            return nullptr;
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  sendControlToAll
//
//  Sends a 76-byte control packet (12-byte header + 64-byte body) to every
//  ready client. Called from pause(), resume(), seekToMs() — all on the
//  Java/UI thread — protected by clientsMutex_.
//
//  After sending, marks s->ready = false so the streaming loop does NOT send
//  audio packets to this client while the re-handshake is in progress.
//  handshakeClient() sets s->ready = true again when the new START: is sent.
// ─────────────────────────────────────────────────────────────────────────────

void SenderEngine::sendControlToAll(const char* cmd) {
    // Build 12-byte header: zero timestamp + magic dataLen
    uint8_t packet[12 + CTRL_BODY_LEN];
    std::memset(packet, 0, sizeof(packet));

    // header[8..11] = 0xFF 0xFF 0xFF 0xFF
    packet[8]  = 0xFF;
    packet[9]  = 0xFF;
    packet[10] = 0xFF;
    packet[11] = 0xFF;

    // body: NUL-terminated command, zero-padded to CTRL_BODY_LEN
    std::strncpy(reinterpret_cast<char*>(packet + 12), cmd, CTRL_BODY_LEN - 1);

    std::lock_guard<std::mutex> lk(clientsMutex_);
    for (auto* s : clients_) {
        if (!s->ready) continue;
        if (!sendRaw(s->fd, packet, sizeof(packet))) {
            LOGE("[%s] sendControlToAll('%s'): send failed — marking dead",
                 s->addr.c_str(), cmd);
            s->dead = true;
        } else {
            LOGI("[CTRL][%s] Sent '%s'", s->addr.c_str(), cmd);
        }
        // Mark not-ready: streaming loop must not send audio while re-handshaking.
        // handshakeClient() re-sets ready=true after the new START: exchange.
        s->ready = false;
    }
    cleanDeadClients();
}

// ─────────────────────────────────────────────────────────────────────────────
//  setClientGain
//
//  Set per-device playback gain. gain: 0.0 = mute, 1.0 = unity, 2.0 = +6 dB.
//  Thread-safe: gain field is std::atomic<float>, written here under mutex,
//  read in streaming loop under the same mutex.
// ─────────────────────────────────────────────────────────────────────────────

void SenderEngine::setClientGain(const std::string& addr, float gain) {
    if (gain < 0.0f) gain = 0.0f;
    if (gain > 2.0f) gain = 2.0f;

    std::lock_guard<std::mutex> lk(clientsMutex_);
    for (auto* s : clients_) {
        if (s->addr == addr) {
            s->gain.store(gain, std::memory_order_relaxed);
            LOGI("[GAIN] %s → %.2f", addr.c_str(), gain);
            return;
        }
    }
    LOGI("[GAIN] setClientGain: no client with addr=%s", addr.c_str());
}

// ─────────────────────────────────────────────────────────────────────────────
//  setClientCrossover
//
//  Set per-device crossover frequencies. Rebuilds the client's FIR filter
//  immediately with the new cutoffs. Safe to call while streaming — the
//  rebuild is protected by clientsMutex_ which the streaming loop also holds
//  during the send pass.
//
//  lowCutHz:  bass/mid boundary  (default 250 Hz, range  20–800 Hz)
//  highCutHz: mid/treble boundary (default 4000 Hz, range 500–18000 Hz)
// ─────────────────────────────────────────────────────────────────────────────

void SenderEngine::setClientCrossover(const std::string& addr,
                                      float lowCutHz, float highCutHz) {
    // Sanity clamp — prevent degenerate or inverted filter bands
    if (lowCutHz   <    20.0f) lowCutHz   =    20.0f;
    if (lowCutHz   >   800.0f) lowCutHz   =   800.0f;
    if (highCutHz  <   500.0f) highCutHz  =   500.0f;
    if (highCutHz  > 18000.0f) highCutHz  = 18000.0f;
    if (lowCutHz >= highCutHz) {
        LOGE("[CROSSOVER] Invalid: lowCut=%.0f >= highCut=%.0f — ignoring",
             lowCutHz, highCutHz);
        return;
    }

    std::lock_guard<std::mutex> lk(clientsMutex_);
    for (auto* s : clients_) {
        if (s->addr != addr) continue;
        s->bassCutHz   = lowCutHz;
        s->trebleCutHz = highCutHz;
        if (s->role != SROLE_FULL) {
            s->filter = buildFilterForClient(s);
            LOGI("[CROSSOVER] %s rebuilt — bass=%.0fHz  treble=%.0fHz",
                 addr.c_str(), lowCutHz, highCutHz);
        }
        return;
    }
    LOGI("[CROSSOVER] setClientCrossover: no client with addr=%s", addr.c_str());
}
// ─────────────────────────────────────────────────────────────────────────────
//  setClientEq
//
//  Set per-device 2-band parametric EQ.
//  If both dB values are 0, eq is set to nullptr (free path — zero processing).
//  Otherwise a BiquadFilter is (re)built and installed.
//  Thread-safe: protected by clientsMutex_, same as setClientCrossover.
//
//  peakHz:   centre frequency of the peak/cut band  (20–20000 Hz)
//  peakDb:   gain at centre                         (-20 to +20 dB)
//  peakQ:    Q factor of the peak band              (0.1–10)
//  shelfHz:  corner frequency of the high shelf     (1000–20000 Hz)
//  shelfDb:  shelf gain                             (-20 to +20 dB)
// ─────────────────────────────────────────────────────────────────────────────

void SenderEngine::setClientEq(const std::string& addr,
                               float peakHz, float peakDb, float peakQ,
                               float shelfHz, float shelfDb) {
    // Clamp to sane ranges
    if (peakHz   <    20.0f) peakHz   =    20.0f;
    if (peakHz   > 20000.0f) peakHz   = 20000.0f;
    if (peakDb   <   -20.0f) peakDb   =   -20.0f;
    if (peakDb   >    20.0f) peakDb   =    20.0f;
    if (peakQ    <     0.1f) peakQ    =     0.1f;
    if (peakQ    >    10.0f) peakQ    =    10.0f;
    if (shelfHz  <  1000.0f) shelfHz  =  1000.0f;
    if (shelfHz  > 20000.0f) shelfHz  = 20000.0f;
    if (shelfDb  <   -20.0f) shelfDb  =   -20.0f;
    if (shelfDb  >    20.0f) shelfDb  =    20.0f;

    std::lock_guard<std::mutex> lk(clientsMutex_);
    for (auto* s : clients_) {
        if (s->addr != addr) continue;

        s->eqPeakHz  = peakHz;
        s->eqPeakDb  = peakDb;
        s->eqPeakQ   = peakQ;
        s->eqShelfHz = shelfHz;
        s->eqShelfDb = shelfDb;

        // Flat = free path: destroy the filter object so streaming loop skips it.
        if (peakDb == 0.0f && shelfDb == 0.0f) {
            s->eq.reset();
            LOGI("[EQ] %s → flat (null — no processing)", addr.c_str());
        } else {
            s->eq = std::make_unique<BiquadFilter>(
                    peakHz, peakDb, peakQ,
                    shelfHz, shelfDb,
                    (int)sampleRate_);
            LOGI("[EQ] %s → peak %.0fHz %+.1fdB Q%.1f  shelf %.0fHz %+.1fdB",
                 addr.c_str(), peakHz, peakDb, peakQ, shelfHz, shelfDb);
        }
        return;
    }
    LOGI("[EQ] setClientEq: no client with addr=%s", addr.c_str());
}
// ─────────────────────────────────────────────────────────────────────────────
//  setSenderClientRole
//
//  Set a role override for a specific receiver from the sender side.
//  Pass "full" to clear the override (receiver's own choice wins).
//  The new role takes effect on the NEXT re-handshake (pause/resume or
//  reconnect) — not mid-stream, because the FIR filter is built at handshake.
// ─────────────────────────────────────────────────────────────────────────────

void SenderEngine::setSenderClientRole(const std::string& addr,
                                       const std::string& role) {
    std::lock_guard<std::mutex> lk(clientsMutex_);
    for (auto* s : clients_) {
        if (s->addr != addr) continue;
        s->overrideRole = senderRoleFromString(role);
        LOGI("[ROLE] %s → sender override = %s", addr.c_str(), role.c_str());
        return;
    }
    LOGI("[ROLE] setSenderClientRole: no client with addr=%s", addr.c_str());
}
// ─────────────────────────────────────────────────────────────────────────────
//  getClientStats
//
//  Returns a snapshot of all connected clients as a plain pipe-delimited string.
//  One client per line, format:
//    <addr>|<role>|<gain>|<clockOffsetMs>
//
//  clockOffset is not stored per-client (it lives in ReceiverEngine), so we
//  expose what we do have: addr, role, gain. The Java side parses this cheaply
//  without needing a JSON library.
//  Called from the UI thread (JNI) every 5 s — clientsMutex_ held briefly.
// ─────────────────────────────────────────────────────────────────────────────

std::string SenderEngine::getClientStats() {
    static constexpr const char* roleNames[] = { "full", "bass", "mid", "treble" };
    std::string result;

    std::lock_guard<std::mutex> lk(clientsMutex_);
    for (auto* s : clients_) {
        if (s->dead) continue;
        result += s->addr;
        result += '|';
        result += roleNames[(int)s->role];
        result += '|';
        // Format gain to 2 decimal places without printf
        float g = s->gain.load(std::memory_order_relaxed);
        int   gi = (int)(g * 100.0f + 0.5f);
        result += std::to_string(gi / 100) + '.'
                  + std::to_string((gi % 100) / 10)
                  + std::to_string(gi % 10);
        result += '\n';
    }
    return result;
}
// ─────────────────────────────────────────────────────────────────────────────
//  setSenderLocalRole
//
//  Sets the role for the sender's own local speaker output.
//  Independent of what is streamed to receivers — receivers always get raw PCM
//  and apply their own per-client filters.
//
//  Pass "full" to restore unfiltered local playback (default).
//  The new filter takes effect on the next decoded chunk — no restart needed.
//  Safe to call while streaming (localFilter_ is only touched here and in
//  streamingLoop; both run on separate threads but localFilter_ writes are
//  atomic pointer replacements guarded by the streaming loop's own check).
//
//  NOTE: uses a relaxed unique_ptr swap — acceptable because the worst case
//  is one chunk plays with the old filter. A mutex would be overkill here.
// ─────────────────────────────────────────────────────────────────────────────

void SenderEngine::setSenderLocalRole(const std::string& role,
                                      float bassCutHz, float trebleCutHz) {
    localRole_        = senderRoleFromString(role);
    localBassCutHz_   = bassCutHz;
    localTrebleCutHz_ = trebleCutHz;

    switch (localRole_) {
        case SROLE_BASS:
            localFilter_ = std::make_unique<FirFilter>(
                    FirFilter::Type::LowPass,
                    (double)localBassCutHz_,
                    (int)sampleRate_);
            break;
        case SROLE_MID:
            localFilter_ = std::make_unique<FirFilter>(
                    (double)localBassCutHz_,
                    (double)localTrebleCutHz_,
                    (int)sampleRate_);
            break;
        case SROLE_TREBLE:
            localFilter_ = std::make_unique<FirFilter>(
                    FirFilter::Type::HighPass,
                    (double)localTrebleCutHz_,
                    (int)sampleRate_);
            break;
        default: // SROLE_FULL
            localFilter_.reset();
            break;
    }

    static constexpr const char* roleNames[] = { "full", "bass", "mid", "treble" };
    LOGI("[LOCAL ROLE] Sender local role → %s  (bass=%.0fHz  treble=%.0fHz)",
         roleNames[(int)localRole_], localBassCutHz_, localTrebleCutHz_);
}

// ─────────────────────────────────────────────────────────────────────────────
//  pause
//
//  1. Set paused_ = true  →  streaming loop idles immediately (no more audio)
//  2. Send PAUSE to all receivers  →  they silence and drain their jitter buffer
//  3. Drain localRing  →  sender speaker goes silent
//
//  The streaming loop remains running — it just spins on the paused_ check.
//  The TCP connections stay open. No re-handshake needed for PAUSE.
// ─────────────────────────────────────────────────────────────────────────────

void SenderEngine::pause() {
    if (!streaming_.load()) { LOGI("pause(): not streaming — ignored"); return; }
    if (paused_.load(std::memory_order_acquire)) { LOGI("pause(): already paused"); return; }

    LOGI("[CTRL] pause() — setting paused_, sending PAUSE to all receivers");

    // Step 1: idle the streaming loop BEFORE sending so no audio goes out after PAUSE
    paused_.store(true, std::memory_order_release);

    // Step 2: send PAUSE to all receivers
    sendControlToAll("PAUSE");

    // Step 3: drain localRing so sender speaker stops immediately.
    // IMPORTANT: must pop each chunk and release it back to localPool_.
    // localRing_->clear() only moves the read pointer — it silently discards
    // the LocalChunk* pointers without returning them to the pool. On resume
    // the pool would be empty, localPool_->acquire() returns null for every
    // chunk, nothing gets pushed to the ring, and the callback outputs silence
    // forever (except the ~5.8ms burst from whatever localCurrent_ finishes).
    // NOTE: localCurrent_ is intentionally NOT touched here — it is owned by
    // the audio callback thread. Touching it here is a data race. The callback
    // finishes draining it (≤5.8ms) then releases it to the pool itself.
    if (localRing_ && localPool_) {
        LocalChunk* lc;
        while ((lc = localRing_->pop()) != nullptr)
            localPool_->release(lc);
    }

    LOGI("[CTRL] pause() complete — position=%lld ms",
         (long long)getPositionMs());
}

// ─────────────────────────────────────────────────────────────────────────────
//  resume
//
//  1. Clear paused_ flag
//  2. Reset sharedStartAtNs_ = 0  →  re-enables the IDEA-5 handshake anchor
//  3. Send RESUME to all receivers  →  they flush jitter buffer and break to
//     re-handshake. They will then re-send ROLE: on the existing TCP connection.
//  4. Spawn handshakeClient() on each existing fd to handle the re-handshake.
// ─────────────────────────────────────────────────────────────────────────────

void SenderEngine::resume() {
    if (!streaming_.load()) { LOGI("resume(): not streaming — ignored"); return; }
    if (!paused_.load(std::memory_order_acquire)) { LOGI("resume(): not paused"); return; }

    LOGI("[CTRL] resume() — sending RESUME, re-running handshake on existing connections");

    // Step 1: clear seek state (resume resumes from current position)
    seekRequested_.store(false, std::memory_order_release);

    // Step 2: reset anchor so streaming loop re-waits for handshakeClient()
    sharedStartAtNs_.store(0, std::memory_order_release);

    // Step 3: clear paused_ BEFORE sending RESUME so streaming loop enters
    // the anchor-wait state (not the paused-idle state) immediately
    paused_.store(false, std::memory_order_release);

    // Step 4: send RESUME to all receivers, spawn re-handshake threads
    {
        std::lock_guard<std::mutex> lk(clientsMutex_);
        uint8_t packet[12 + CTRL_BODY_LEN];
        std::memset(packet, 0, sizeof(packet));
        packet[8] = 0xFF; packet[9] = 0xFF; packet[10] = 0xFF; packet[11] = 0xFF;
        std::strncpy(reinterpret_cast<char*>(packet + 12), "RESUME", CTRL_BODY_LEN - 1);

        cleanDeadClients(); // remove any stale entries before spawning threads
        for (auto* s : clients_) {
            if (s->fd < 0 || s->dead) continue;
            if (!sendRaw(s->fd, packet, sizeof(packet))) {
                LOGE("[%s] resume(): RESUME send failed — marking dead", s->addr.c_str());
                s->dead = true;
                continue;
            }
            LOGI("[CTRL][%s] Sent RESUME — spawning re-handshake thread", s->addr.c_str());
            if (s->handshakeThread.joinable()) s->handshakeThread.detach();
            s->handshakeThread = std::thread(&SenderEngine::handshakeClient, this, s);
            s->handshakeThread.detach();
        }
        cleanDeadClients(); // sweep any newly-dead entries
    }

    LOGI("[CTRL] resume() complete — waiting for receiver re-handshakes");
}

// ─────────────────────────────────────────────────────────────────────────────
//  seekToMs
//
//  Safe to call while playing OR while paused.
//  1. Set seekTargetFrames_ = ms * sampleRate / 1000
//  2. Set seekRequested_ = true  →  streaming loop will seek before re-anchoring
//  3. If currently playing (not paused): silence the loop, drain local ring
//  4. Send SEEK:<ms> to all receivers
//  5. Reset sharedStartAtNs_ = 0 and spawn re-handshake threads
//  6. Clear paused_ so streaming loop enters anchor-wait after seek
// ─────────────────────────────────────────────────────────────────────────────

void SenderEngine::seekToMs(int64_t ms) {
    if (!streaming_.load()) { LOGI("seekToMs(%lld): not streaming — ignored", (long long)ms); return; }
    if (ms < 0) ms = 0;

    int64_t targetFrames = ms * (int64_t)sampleRate_ / 1000LL;
    if (totalFrames_ > 0 && targetFrames >= totalFrames_)
        targetFrames = totalFrames_ > 0 ? totalFrames_ - 1 : 0;

    LOGI("[CTRL] seekToMs(%lld ms) — targetFrame=%lld  totalFrames=%lld",
         (long long)ms, (long long)targetFrames, (long long)totalFrames_);

    // Step 1: store seek target BEFORE setting seekRequested_
    seekTargetFrames_.store(targetFrames, std::memory_order_release);
    seekRequested_.store(true, std::memory_order_release);

    // Step 2: silence the streaming loop and drain local speaker ring
    bool wasAlreadyPaused = paused_.load(std::memory_order_acquire);
    if (!wasAlreadyPaused) {
        paused_.store(true, std::memory_order_release);
        if (localRing_ && localPool_) {
            LocalChunk* lc;
            while ((lc = localRing_->pop()) != nullptr)
                localPool_->release(lc);
        }
    }

    // Step 3: reset anchor
    sharedStartAtNs_.store(0, std::memory_order_release);

    // Step 4: send SEEK:<ms> to all receivers and spawn re-handshake threads
    {
        std::string seekCmd = "SEEK:" + std::to_string(ms);

        uint8_t packet[12 + CTRL_BODY_LEN];
        std::memset(packet, 0, sizeof(packet));
        packet[8] = 0xFF; packet[9] = 0xFF; packet[10] = 0xFF; packet[11] = 0xFF;
        std::strncpy(reinterpret_cast<char*>(packet + 12),
                     seekCmd.c_str(), CTRL_BODY_LEN - 1);

        std::lock_guard<std::mutex> lk(clientsMutex_);
        cleanDeadClients();
        for (auto* s : clients_) {
            if (s->fd < 0 || s->dead) continue;
            s->ready = false;
            if (!sendRaw(s->fd, packet, sizeof(packet))) {
                LOGE("[%s] seekToMs(): send failed — marking dead", s->addr.c_str());
                s->dead = true;
                continue;
            }
            LOGI("[CTRL][%s] Sent '%s' — spawning re-handshake thread",
                 s->addr.c_str(), seekCmd.c_str());
            if (s->handshakeThread.joinable()) s->handshakeThread.detach();
            s->handshakeThread = std::thread(&SenderEngine::handshakeClient, this, s);
            s->handshakeThread.detach();
        }
        cleanDeadClients();
    }

    // Step 5: clear paused_ so streaming loop enters anchor-wait (not paused-idle)
    paused_.store(false, std::memory_order_release);

    LOGI("[CTRL] seekToMs(%lld ms) complete — streaming loop will seek + re-anchor",
         (long long)ms);
}

// ─────────────────────────────────────────────────────────────────────────────
//  getPositionMs / getDurationMs
// ─────────────────────────────────────────────────────────────────────────────

int64_t SenderEngine::getPositionMs() const {
    if (sampleRate_ == 0) return 0;
    return currentFramePosition_.load(std::memory_order_relaxed)
           * 1000LL / (int64_t)sampleRate_;
}

int64_t SenderEngine::getDurationMs() const {
    if (sampleRate_ == 0 || totalFrames_ == 0) return 0;
    return totalFrames_ * 1000LL / (int64_t)sampleRate_;
}

// ── New methods ───────────────────────────────────────────────────────────────
void SenderEngine::swapTrack(int fd) {
    stopStreaming();
    setFd(fd);
    startStreaming();

    // Push updated TRACKINFO to all already-connected receivers.
    // stopStreaming() does NOT disconnect receivers — their TCP sockets stay open.
    // But startStreaming() resets sharedStartAtNs_ and spawns a new stream thread,
    // so handshakeClient() won't run for existing connections.
    // Send TRACKINFO as a control packet so receiver UI updates immediately.
    if (!trackTitle_.empty()) {
        std::string ti = "TRACKINFO:" + trackTitle_ + "|" + trackArtist_
                         + "|" + std::to_string(getDurationMs());
        uint8_t packet[12 + CTRL_BODY_LEN];
        std::memset(packet, 0, sizeof(packet));
        packet[8] = 0xFF; packet[9] = 0xFF; packet[10] = 0xFF; packet[11] = 0xFF;
        std::strncpy(reinterpret_cast<char*>(packet + 12),
                     ti.c_str(), CTRL_BODY_LEN - 1);
        std::lock_guard<std::mutex> lk(clientsMutex_);
        for (auto* s : clients_) {
            if (s->fd >= 0 && !s->dead)
                sendRaw(s->fd, packet, sizeof(packet));
        }
        LOGI("swapTrack: pushed TRACKINFO '%s' to %zu receivers",
             trackTitle_.c_str(), clients_.size());
    }

    LOGI("swapTrack: fd=%d — engine kept alive, receivers stay connected", fd);
}

void SenderEngine::setTrackInfo(const std::string& title, const std::string& artist) {
    trackTitle_  = title;
    trackArtist_ = artist;
    LOGI("setTrackInfo: '%s' by '%s'", title.c_str(), artist.c_str());
}

void SenderEngine::setPaletteHex(const std::string& hex1, const std::string& hex2) {
    paletteHex1_ = hex1;
    paletteHex2_ = hex2;
    LOGI("setPaletteHex: %s  %s", hex1.c_str(), hex2.c_str());
}

int64_t SenderEngine::getClientPingMs(const std::string& addr) {
    std::lock_guard<std::mutex> lk(clientsMutex_);
    for (auto* s : clients_) {
        if (s->addr == addr)
            return s->lastPingRttNs.load(std::memory_order_relaxed) / 1'000'000LL;
    }
    return -1;
}

// ─────────────────────────────────────────────────────────────────────────────
//  Lifecycle
// ─────────────────────────────────────────────────────────────────────────────

SenderEngine::SenderEngine(StatusCallback cb) : statusCb_(std::move(cb)) {}
SenderEngine::~SenderEngine() { stop(); }

bool SenderEngine::start(const std::string& senderIP, bool localOnly) {
    senderIP_  = senderIP;
    localOnly_ = localOnly;

    if (!localOnly_) {
        tcpServerFd_ = ::socket(AF_INET, SOCK_STREAM, 0);
        if (tcpServerFd_ < 0) { LOGE("socket() failed"); return false; }

        int yes = 1;
        ::setsockopt(tcpServerFd_, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof(yes));
        ::setsockopt(tcpServerFd_, SOL_SOCKET, SO_REUSEPORT, &yes, sizeof(yes));

        sockaddr_in addr{};
        addr.sin_family      = AF_INET;
        addr.sin_port        = htons(MESH_TCP_PORT);
        addr.sin_addr.s_addr = INADDR_ANY;
        if (::bind(tcpServerFd_, (sockaddr*)&addr, sizeof(addr)) < 0) {
            LOGE("bind() failed"); ::close(tcpServerFd_); return false;
        }
        ::listen(tcpServerFd_, 8);

        udpFd_ = ::socket(AF_INET, SOCK_DGRAM, 0);
        ::setsockopt(udpFd_, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof(yes));
        int bcast = 1;
        ::setsockopt(udpFd_, SOL_SOCKET, SO_BROADCAST, &bcast, sizeof(bcast));
    }

    running_ = true;

    if (!localOnly_) {
        beaconThread_ = std::thread(&SenderEngine::beaconLoop, this);
        acceptThread_ = std::thread(&SenderEngine::acceptLoop, this);
        LOGI("Started (MESH) — TCP %d  UDP %d  IP %s",
             MESH_TCP_PORT, MESH_UDP_PORT, senderIP_.c_str());
    } else {
        LOGI("Started (LOCAL-ONLY) — beacon + accept skipped");
    }

    return true;
}

void SenderEngine::stop() {
    stopStreaming();
    running_ = false;

    if (tcpServerFd_ >= 0) {
        ::shutdown(tcpServerFd_, SHUT_RDWR);
        ::close(tcpServerFd_);
        tcpServerFd_ = -1;
    }
    if (udpFd_ >= 0) { ::close(udpFd_); udpFd_ = -1; }

    if (!localOnly_) {
        if (beaconThread_.joinable()) beaconThread_.join();
        if (acceptThread_.joinable()) acceptThread_.join();
    }

    std::lock_guard<std::mutex> lk(clientsMutex_);
    for (auto* s : clients_) {
        if (s->fd >= 0) ::close(s->fd);
        if (s->handshakeThread.joinable()) s->handshakeThread.detach();
        delete s;
    }
    clients_.clear();
}

// ─────────────────────────────────────────────────────────────────────────────
//  Beacon
// ─────────────────────────────────────────────────────────────────────────────

void SenderEngine::beaconLoop() {
    std::string msg = std::string(BEACON_PREFIX) + senderIP_ +
                      ":" + std::to_string(MESH_TCP_PORT) + "\n";

    auto sendTo = [&](const char* dst) {
        sockaddr_in d{};
        d.sin_family = AF_INET;
        d.sin_port   = htons(MESH_UDP_RECV_PORT);
        ::inet_pton(AF_INET, dst, &d.sin_addr);
        ::sendto(udpFd_, msg.c_str(), msg.size(), 0, (sockaddr*)&d, sizeof(d));
    };

    while (running_) {
        std::string subnet = senderIP_.substr(0, senderIP_.rfind('.')) + ".255";
        sendTo(subnet.c_str());
        sendTo("255.255.255.255");
        LOGI("Beacon → %s", msg.c_str());
        for (int i = 0; i < BEACON_INTERVAL_MS && running_; i += 100)
            usleep(100'000);
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Accept
// ─────────────────────────────────────────────────────────────────────────────

void SenderEngine::acceptLoop() {
    while (running_) {
        sockaddr_in peer{};
        socklen_t   len = sizeof(peer);
        int cfd = ::accept(tcpServerFd_, (sockaddr*)&peer, &len);
        if (cfd < 0) { if (running_) LOGE("accept() error"); break; }

        char ip[INET_ADDRSTRLEN];
        ::inet_ntop(AF_INET, &peer.sin_addr, ip, sizeof(ip));
        LOGI("New connection from %s", ip);

        auto* s = new ClientState{};
        s->fd   = cfd;
        s->addr = ip;
// Do NOT add to clients_ yet — handshakeClient adds it only on success
// This prevents ghost entries from failed/duplicate handshakes
        s->handshakeThread = std::thread(&SenderEngine::handshakeClient, this, s);
        s->handshakeThread.detach();
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Handshake
//
//  This runs for:
//    (a) New connections: spawned by acceptLoop()
//    (b) Re-handshake after RESUME/SEEK: spawned by resume() / seekToMs()
//        on an EXISTING fd. In this case the receiver re-sends ROLE: on the
//        same TCP connection immediately after receiving RESUME/SEEK.
//
//  The logic is identical in both cases — the fd is already connected.
// ─────────────────────────────────────────────────────────────────────────────

static bool recvLine(int fd, std::string& out, int timeoutMs = 5000) {
    out.clear();
    struct timeval tv{ timeoutMs / 1000, (timeoutMs % 1000) * 1000 };
    ::setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    char c;
    while (true) {
        int n = ::recv(fd, &c, 1, 0);
        if (n <= 0) return false;
        if (c == '\n') return true;
        if (c != '\r') out += c;
    }
}

void SenderEngine::handshakeClient(ClientState* s) {
    std::string line;

    // 1. Role
    if (!recvLine(s->fd, line) || line.substr(0, 5) != "ROLE:") {
        LOGE("[%s] Bad/no ROLE line: '%s'", s->addr.c_str(), line.c_str());
        if (s->fd >= 0) { ::shutdown(s->fd, SHUT_RDWR); ::close(s->fd); s->fd = -1; }
        delete s;
        return;
    }
    SenderRole requestedRole = senderRoleFromString(line.substr(5));

    // ── Role resolution: receiver wins if it picked a non-full role ───────────
    // Priority: receiver explicit (non-full) > sender override > full fallback.
    if (requestedRole != SROLE_FULL) {
        // Receiver explicitly chose a band — honour it, ignore any sender override.
        s->role = requestedRole;
    } else if (s->overrideRole != SROLE_FULL) {
        // Receiver said full but sender has an override set — apply it.
        s->role = s->overrideRole;
    } else {
        // No override from either side — stay full.
        s->role = SROLE_FULL;
    }

    // Tell the receiver which role it was actually assigned.
    // It uses this only for display — the actual filtering happens here.
    {
        static constexpr const char* roleNames[] = { "full", "bass", "mid", "treble" };
        std::string ar = "ASSIGNEDROLE:" +
                         std::string(roleNames[(int)s->role]) + "\n";
        sendRaw(s->fd, ar.c_str(), (int)ar.size());
        LOGI("[%s] Role: requested=%d  override=%d  assigned=%d",
             s->addr.c_str(), (int)requestedRole,
             (int)s->overrideRole, (int)s->role);
    }

    // ── Build per-client FIR filter ───────────────────────────────────────────
    // SROLE_FULL receives unfiltered PCM — no filter needed.
    // Band roles get a filter built from the client's crossover params.
    // On re-handshake (RESUME/SEEK), the existing crossover params are reused
    // so the user's custom crossover settings survive a pause/resume cycle.
    if (s->role != SROLE_FULL) {
        s->filter = buildFilterForClient(s);
        LOGI("[%s] Filter built — role=%d  bass=%.0fHz  treble=%.0fHz",
             s->addr.c_str(), (int)s->role, s->bassCutHz, s->trebleCutHz);
    }

    // 2. Sample rate — always tell receivers we send 44100 Hz PCM
    // (streaming loop resamples to TARGET_RATE=44100 if file differs)
    std::string sr = "SAMPLERATE:44100\n";    sendRaw(s->fd, sr.c_str(), (int)sr.size());
    // Track info — sent if set (wired from Java before streaming starts)
    if (!trackTitle_.empty()) {
        std::string ti = "TRACKINFO:" + trackTitle_ + "|" + trackArtist_
                         + "|" + std::to_string(getDurationMs()) + "\n";
        sendRaw(s->fd, ti.c_str(), (int)ti.size());
        LOGI("[%s] Sent TRACKINFO: %s | %s",
             s->addr.c_str(), trackTitle_.c_str(), trackArtist_.c_str());
    }

    // Palette — sent if set
    if (!paletteHex1_.empty()) {
        std::string pal = "PALETTE:" + paletteHex1_ + "," + paletteHex2_ + "\n";
        sendRaw(s->fd, pal.c_str(), (int)pal.size());
        LOGI("[%s] Sent PALETTE: %s,%s",
             s->addr.c_str(), paletteHex1_.c_str(), paletteHex2_.c_str());
    }

    // 3. Sender hardware latency — wait for measurement gate
    {
        int waitMs = 0;
        while (!measured_.load(std::memory_order_acquire) && running_.load()) {
            struct timespec ts{0, 5'000'000L};
            nanosleep(&ts, nullptr);
            waitMs += 5;
            if (waitMs >= 2000) {
                LOGE("[%s] Timed out waiting for measurement — using fallback",
                     s->addr.c_str());
                break;
            }
        }
        if (waitMs > 0)
            LOGI("[%s] Waited %d ms for pipeline measurement", s->addr.c_str(), waitMs);

        std::string sl = "SENDERLATENCY:" + std::to_string(senderHwLatencyNs_) + "\n";
        sendRaw(s->fd, sl.c_str(), (int)sl.size());
        LOGI("[%s] SENDERLATENCY: %lld ms",
             s->addr.c_str(), (long long)(senderHwLatencyNs_ / 1'000'000LL));
    }

    // AFTER
    // 4. Clock sync — measure RTT per round, store median in lastPingRttNs
    {
        std::vector<int64_t> rtts;
        rtts.reserve(SYNC_ROUNDS);
        for (int i = 0; i < SYNC_ROUNDS; i++) {
            if (!recvLine(s->fd, line) || line.substr(0, 5) != "PING:") continue;
            std::string t1str = line.substr(5);
            int64_t t2  = nowNs();
            std::string pong = "PONG:" + t1str + ":" + std::to_string(t2) + "\n";
            sendRaw(s->fd, pong.c_str(), (int)pong.size());
            int64_t t1 = std::stoll(t1str);
            int64_t rtt = t2 - t1;
            if (rtt > 0 && rtt < 1'000'000'000LL) rtts.push_back(rtt);
        }
        if (!rtts.empty()) {
            std::sort(rtts.begin(), rtts.end());
            s->lastPingRttNs.store(rtts[rtts.size() / 2], std::memory_order_relaxed);
            LOGI("[%s] Ping RTT median: %lld ms",
                 s->addr.c_str(),
                 (long long)(s->lastPingRttNs.load() / 1'000'000LL));
        }
    }

    // ── 5. [IDEA-5] Read READY:<pipelineFullNs_senderClock> ──────────────────
    if (!recvLine(s->fd, line, 10000)) {
        LOGE("[%s] Timed out waiting for READY — marking dead", s->addr.c_str());
        s->dead = true;
        return;
    }

    int64_t pfNs_senderClock = 0;

    if (line.rfind("READY:", 0) == 0) {
        pfNs_senderClock = std::stoll(line.substr(6));
        LOGI("[IDEA5][%s] Receiver pipeline full at %lld ms (sender clock)",
             s->addr.c_str(),
             (long long)(pfNs_senderClock / 1'000'000LL));
    } else if (line == "READY") {
        pfNs_senderClock = nowNs();
        LOGI("[IDEA5][%s] Old receiver sent plain READY — using nowNs() as anchor",
             s->addr.c_str());
    } else {
        LOGE("[%s] Expected READY or READY:<ns>, got: '%s'",
             s->addr.c_str(), line.c_str());
        s->dead = true;
        return;
    }

    // Safety: if pfNs is more than 3s in the past, clamp to now
    {
        int64_t now = nowNs();
        int64_t age = now - pfNs_senderClock;
        if (age > 3'000'000'000LL) {
            LOGE("[IDEA5][%s] pfNs is %lld ms old — clamping to now",
                 s->addr.c_str(), (long long)(age / 1'000'000LL));
            pfNs_senderClock = now;
        }
    }

    // ── 6. [IDEA-5] Compute this receiver's required start time ──────────────
    int64_t startAtSenderNs = pfNs_senderClock
                              + IDEA5_SILENCE_DRAIN_NS
                              + IDEA5_NETWORK_MARGIN_NS;

    // Enforce a minimum 1.1s from now so packets are always in-flight
    {
        int64_t now      = nowNs();
        int64_t minStart = now + 1'100'000'000LL;
        if (startAtSenderNs < minStart) {
            LOGI("[IDEA5][%s] startAt bumped: computed=%lld ms → using min=%lld ms from now",
                 s->addr.c_str(),
                 (long long)((startAtSenderNs - now) / 1'000'000LL),
                 (long long)((minStart        - now) / 1'000'000LL));
            startAtSenderNs = minStart;
        }
    }

    // CAS: push sharedStartAtNs_ to the MAXIMUM (latest required start time).
    {
        int64_t expected = sharedStartAtNs_.load(std::memory_order_acquire);
        while (startAtSenderNs > expected) {
            if (sharedStartAtNs_.compare_exchange_weak(
                    expected, startAtSenderNs,
                    std::memory_order_release,
                    std::memory_order_acquire)) {
                LOGI("[IDEA5][%s] sharedStartAtNs_ pushed to %lld ms from now  (was %lld ms)",
                     s->addr.c_str(),
                     (long long)((startAtSenderNs - nowNs()) / 1'000'000LL),
                     (long long)((expected        - nowNs()) / 1'000'000LL));
                break;
            }
        }
    }

    // ── 7. Wait for anchor to stabilise, then send the shared START ───────────
    {
        struct timespec ts{0, 100'000'000L};
        nanosleep(&ts, nullptr);
    }

    int64_t finalStartAtSenderNs = sharedStartAtNs_.load(std::memory_order_acquire);

    {
        int64_t now = nowNs();
        if (finalStartAtSenderNs <= 0 || (finalStartAtSenderNs - now) > 8'000'000'000LL) {
            LOGE("[IDEA5][%s] sharedStartAtNs_ still at sentinel — using own startAt",
                 s->addr.c_str());
            finalStartAtSenderNs = startAtSenderNs;
        }
    }

    {
        int64_t now = nowNs();
        LOGI("[IDEA5][%s] Sending START:%lld — receiver plays in %lld ms from now  "
             "(pfNs=%lld ms ago  silence=%lld ms  margin=%lld ms)",
             s->addr.c_str(),
             (long long)finalStartAtSenderNs,
             (long long)((finalStartAtSenderNs - now) / 1'000'000LL),
             (long long)((now - pfNs_senderClock)     / 1'000'000LL),
             (long long)(IDEA5_SILENCE_DRAIN_NS        / 1'000'000LL),
             (long long)(IDEA5_NETWORK_MARGIN_NS       / 1'000'000LL));
    }

    std::string start = "START:" + std::to_string(finalStartAtSenderNs) + "\n";
    sendRaw(s->fd, start.c_str(), (int)start.size());

    // Add to clients_ only now — handshake fully complete, safe to stream
    {
        std::lock_guard<std::mutex> lk(clientsMutex_);
        // Check if s is already in clients_ (re-handshake path reuses same pointer).
        // If so, don't add it again — just set ready and return.
        bool alreadyPresent = false;
        for (auto* c : clients_) {
            if (c == s) { alreadyPresent = true; break; }
        }
        if (!alreadyPresent) {
            // New connection: remove any stale entry for this IP, then add.
            clients_.erase(
                    std::remove_if(clients_.begin(), clients_.end(),
                                   [&](ClientState* c) {
                                       if (c->addr == s->addr && c != s) {
                                           if (c->fd >= 0) { ::shutdown(c->fd, SHUT_RDWR); ::close(c->fd); c->fd = -1; }
                                           delete c;
                                           return true;
                                       }
                                       return false;
                                   }),
                    clients_.end()
            );
            clients_.push_back(s);
        }
    }
    s->ready = true;
    LOGI("[%s] Handshake complete — role=%d", s->addr.c_str(), (int)s->role);
    if (statusCb_) statusCb_({ s->addr, s->role, true });
}

// ─────────────────────────────────────────────────────────────────────────────
//  dr_mp3
// ─────────────────────────────────────────────────────────────────────────────

bool SenderEngine::openDrMp3(int fd) {
    FILE* fp = fdopen(fd, "rb");
    if (!fp) { LOGE("fdopen(%d) failed: %s", fd, strerror(errno)); return false; }

    auto* mp3 = new drmp3{};
    fseek(fp, 0, SEEK_END);
    long fileSize = ftell(fp);
    fseek(fp, 0, SEEK_SET);

    if (fileSize <= 0) {
        LOGE("ftell returned %ld", fileSize);
        fclose(fp); delete mp3; return false;
    }

    mp3Data_.resize((size_t)fileSize);
    size_t bytesRead = fread(mp3Data_.data(), 1, (size_t)fileSize, fp);
    fclose(fp);

    if (bytesRead != (size_t)fileSize) {
        LOGE("fread: expected %ld got %zu", fileSize, bytesRead);
        mp3Data_.clear(); delete mp3; return false;
    }

    if (!drmp3_init_memory(mp3, mp3Data_.data(), mp3Data_.size(), nullptr)) {
        LOGE("drmp3_init_memory failed");
        mp3Data_.clear(); delete mp3; return false;
    }
    mp3_          = mp3;
    sampleRate_   = mp3->sampleRate;
    channelCount_ = mp3->channels;
    mp3Open_      = true;
    // Warn if file is not 44100 Hz — receiver will need to handle resampling
    if (sampleRate_ != 44100 && sampleRate_ != 48000) {
        LOGI("MP3 sample rate %u Hz — receiver AAudio may not honor this rate", sampleRate_);
    }
    totalFrames_ = (int64_t)drmp3_get_pcm_frame_count(mp3);
    currentFramePosition_.store(0, std::memory_order_relaxed);

    LOGI("MP3 open: %u Hz  %d ch  %ld bytes  duration=%lld ms",
         sampleRate_, channelCount_, fileSize,
         (long long)getDurationMs());
    return true;
}

void SenderEngine::closeDrMp3() {
    if (mp3Open_ && mp3_) {
        drmp3_uninit(static_cast<drmp3*>(mp3_));
        mp3Open_ = false;
    }
    delete static_cast<drmp3*>(mp3_);
    mp3_ = nullptr;
    mp3Data_.clear();
    mp3Fd_       = -1;
    totalFrames_ = 0;
    currentFramePosition_.store(0, std::memory_order_relaxed);
}

// ─────────────────────────────────────────────────────────────────────────────
//  openAudio / closeAudio / seekToFrame
//
//  Format detection: sniff first 4 bytes, lseek back, dispatch.
//  ftyp box at byte 4 = M4A/AAC container → MediaCodecDecoder
//  Everything else → dr_mp3 (covers ID3-tagged MP3, raw MPEG sync)
// ─────────────────────────────────────────────────────────────────────────────

bool SenderEngine::openAudio(int fd) {
    // Read 12 bytes to check for ftyp box (M4A/AAC container)
    uint8_t hdr[12] = {};
    ::read(fd, hdr, 12);
    ::lseek(fd, 0, SEEK_SET);

    // M4A / AAC in MPEG-4 container: bytes 4-7 = "ftyp"
    bool isM4A = (hdr[4]=='f' && hdr[5]=='t' && hdr[6]=='y' && hdr[7]=='p');
    // Raw AAC: sync word 0xFFF? in first 2 bytes
    bool isRawAAC = (hdr[0]==0xFF && (hdr[1]&0xF0)==0xF0);

    if (isM4A || isRawAAC) {
        audioFormat_ = AudioFormat::AAC;
        LOGI("openAudio: detected AAC/M4A");
        aac_ = std::make_unique<MediaCodecDecoder>();
        if (!aac_->open(fd)) {
            LOGE("openAudio: MediaCodecDecoder::open() failed");
            aac_.reset();
            return false;
        }
        sampleRate_   = aac_->sampleRate();
        channelCount_ = aac_->channelCount();
        totalFrames_  = aac_->totalFrames();
        currentFramePosition_.store(0, std::memory_order_relaxed);
        LOGI("AAC open: %u Hz  %d ch  duration=%lld ms",
             sampleRate_, channelCount_, (long long)getDurationMs());
        return true;
    }

    audioFormat_ = AudioFormat::MP3;
    LOGI("openAudio: defaulting to MP3");
    return openDrMp3(fd);
}

void SenderEngine::closeAudio() {
    if (audioFormat_ == AudioFormat::AAC) {
        aac_.reset();
    } else {
        closeDrMp3();
    }
    audioFormat_ = AudioFormat::MP3;
}

void SenderEngine::seekToFrame(int64_t frame) {
    if (audioFormat_ == AudioFormat::AAC && aac_) {
        aac_->seekToFrame(frame);
    } else if (mp3_) {
        drmp3_seek_to_pcm_frame(static_cast<drmp3*>(mp3_), (drmp3_uint64)frame);
    }
}

void SenderEngine::setFd(int fd) {
    stopStreaming();
    closeAudio();
    mp3Fd_ = fd;
    LOGI("setFd(%d)", fd);
}

void SenderEngine::startStreaming() {
    if (streaming_.load())  { LOGI("Already streaming"); return; }
    if (mp3Fd_ < 0)         { LOGE("No fd — call setFd() first"); return; }
    if (!openAudio(mp3Fd_)) return;
    localRing_    = std::make_unique<LocalRing>(64);
    localPool_    = std::make_unique<LocalPool>(64, CHUNK_FRAMES);
    localCurrent_ = nullptr;

    // Per-client filters are now built in handshakeClient() — no global filters here.

    measured_.store(false, std::memory_order_release);
    senderHwLatencyNs_ = SENDER_HW_LATENCY_FALLBACK_NS;

    paused_.store(false, std::memory_order_release);
    seekRequested_.store(false, std::memory_order_release);
    seekTargetFrames_.store(0, std::memory_order_release);

    if (!openLocalAudio()) {
        LOGE("openLocalAudio() failed — no local audio");
        measured_.store(true, std::memory_order_release);
    }

    sharedStartAtNs_.store(0);
    LOGI("Shared start anchor: 0 (sentinel — waiting for first READY)");

    streaming_    = true;
    streamThread_ = std::thread(&SenderEngine::streamingLoop, this);
    LOGI("Streaming started");
}

void SenderEngine::stopStreaming() {
    if (!streaming_.load()) return;
    streaming_ = false;
    paused_.store(false, std::memory_order_release);
    seekRequested_.store(false, std::memory_order_release);
    if (streamThread_.joinable()) streamThread_.join();
    closeLocalAudio();
    closeAudio();
    localRing_.reset();
    localPool_.reset();
    localCurrent_ = nullptr;
    localFilter_.reset();

    // Per-client filters are owned by ClientState — they are destroyed when
    // clients disconnect (cleanDeadClients) or when stop() deletes all clients.
    // No global filter reset needed here.

    measured_.store(false, std::memory_order_release);
    sharedStartAtNs_.store(0);
    LOGI("Streaming stopped");
}

// ─────────────────────────────────────────────────────────────────────────────
//  Local AAudio open/close
// ─────────────────────────────────────────────────────────────────────────────

bool SenderEngine::openLocalAudio() {
    AAudioStreamBuilder* builder = nullptr;
    if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK) return false;

    AAudioStreamBuilder_setDirection(builder,       AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSampleRate(builder,      (int32_t)sampleRate_);
    AAudioStreamBuilder_setChannelCount(builder,    1);
    AAudioStreamBuilder_setFormat(builder,          AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setDataCallback(builder,    SenderEngine::localAudioCallback, this);
    // Do NOT force callback size — let AAudio use the natural hardware burst.

#if __ANDROID_API__ >= 28
    AAudioStreamBuilder_setUsage(builder,       AAUDIO_USAGE_MEDIA);
    AAudioStreamBuilder_setContentType(builder, AAUDIO_CONTENT_TYPE_MUSIC);
#endif

    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_EXCLUSIVE);
    aaudio_result_t r = AAudioStreamBuilder_openStream(builder, &localStream_);

    if (r != AAUDIO_OK) {
        LOGI("Sender: EXCLUSIVE unavailable (%s) — falling back to SHARED",
             AAudio_convertResultToText(r));
        AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
        r = AAudioStreamBuilder_openStream(builder, &localStream_);
    }

    AAudioStreamBuilder_delete(builder);

    if (r != AAUDIO_OK) {
        LOGE("openLocalAudio failed: %s", AAudio_convertResultToText(r));
        localStream_ = nullptr;
        return false;
    }

    aaudio_sharing_mode_t mode = AAudioStream_getSharingMode(localStream_);
    bool isExclusive = (mode == AAUDIO_SHARING_MODE_EXCLUSIVE);
    LOGI("Sender AAudio stream opened — mode: %s",
         isExclusive ? "EXCLUSIVE" : "SHARED");

    r = AAudioStream_requestStart(localStream_);
    if (r != AAUDIO_OK) {
        LOGE("requestStart failed: %s", AAudio_convertResultToText(r));
        AAudioStream_close(localStream_); localStream_ = nullptr;
        return false;
    }

    LOGI("Local AAudio stream started at %u Hz", sampleRate_);

// AFTER: measure synchronously before opening the gate
    senderHwLatencyNs_ = SENDER_HW_LATENCY_FALLBACK_NS;
// Start measuring — gate stays CLOSED until measurement completes
    std::thread([this]() {
        int64_t m = measurePipelineDepthNs();
        if (m > 0) senderHwLatencyNs_ = m;
        measured_.store(true);  // gate opens AFTER measurement
    }).detach();


    LOGI("Sender HW latency: %lld ms (%s) — gate open",
         (long long)(senderHwLatencyNs_ / 1'000'000LL),
         isExclusive ? "measured-exclusive" : "measured-shared");

    return true;
}

void SenderEngine::closeLocalAudio() {
    if (localStream_) {
        AAudioStream_requestStop(localStream_);
        AAudioStream_close(localStream_);
        localStream_ = nullptr;
        LOGI("Local AAudio stream closed");
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Pipeline depth measurement
// ─────────────────────────────────────────────────────────────────────────────

int64_t SenderEngine::measurePipelineDepthNs() {
    if (!localStream_) return SENDER_HW_LATENCY_FALLBACK_NS;

    const int WARMUP_MS       = 0;
    const int SAMPLE_COUNT    = 3;
    const int SAMPLE_SLEEP_MS = 20;
    const int64_t MIN_VALID_NS    = 5'000'000LL;
    const int64_t MAX_VALID_NS    = 200'000'000LL;

    {
        struct timespec ts{0, (long)WARMUP_MS * 1'000'000L};
        nanosleep(&ts, nullptr);
        LOGI("senderMeasure: warm-up done (%d ms), taking steady-state samples", WARMUP_MS);
    }

    std::vector<int64_t> samples;
    samples.reserve(SAMPLE_COUNT);

    for (int i = 0; i < SAMPLE_COUNT * 3 && (int)samples.size() < SAMPLE_COUNT; i++) {
        struct timespec ts{0, (long)SAMPLE_SLEEP_MS * 1'000'000L};
        nanosleep(&ts, nullptr);

        int64_t hwFrame = 0, hwTimeNs = 0;
        aaudio_result_t r = AAudioStream_getTimestamp(
                localStream_, CLOCK_MONOTONIC, &hwFrame, &hwTimeNs);

        if (r != AAUDIO_OK || hwFrame <= 0 || hwTimeNs <= 0) {
            LOGD("senderMeasure[%d]: getTimestamp failed or zero — skipping", i);
            continue;
        }

        int64_t framesWritten  = AAudioStream_getFramesWritten(localStream_);
        int64_t framesInFlight = framesWritten - hwFrame;

        if (framesInFlight < 0) {
            LOGD("senderMeasure[%d]: framesInFlight negative — skipping", i);
            continue;
        }

        int64_t depthNs = framesInFlight * 1'000'000'000LL / (int64_t)sampleRate_;

        LOGI("senderMeasure[%d]: framesWritten=%lld  hwFrame=%lld  "
             "inFlight=%lld  depth=%lld ms",
             i, (long long)framesWritten, (long long)hwFrame,
             (long long)framesInFlight, (long long)(depthNs / 1'000'000LL));

        if (depthNs < MIN_VALID_NS || depthNs > MAX_VALID_NS) {
            LOGD("senderMeasure[%d]: depth %lld ms out of [5,200] ms range — skipping",
                 i, (long long)(depthNs / 1'000'000LL));
            continue;
        }

        samples.push_back(depthNs);
    }

    if (samples.empty()) {
        LOGE("senderMeasure: no valid samples — using fallback %lld ms",
             (long long)(SENDER_HW_LATENCY_FALLBACK_NS / 1'000'000LL));
        return SENDER_HW_LATENCY_FALLBACK_NS;
    }

    std::sort(samples.begin(), samples.end());
    int64_t median = samples[samples.size() / 2];

    LOGI("Sender pipeline depth (steady-state median of %d samples): %lld ms",
         (int)samples.size(), (long long)(median / 1'000'000LL));
    return median;
}

// ─────────────────────────────────────────────────────────────────────────────
//  Local AAudio callback
// ─────────────────────────────────────────────────────────────────────────────

aaudio_data_callback_result_t SenderEngine::localAudioCallback(
        AAudioStream*, void* userData, void* audioData, int32_t numFrames) {
    return static_cast<SenderEngine*>(userData)->onLocalAudioReady(audioData, numFrames);
}

aaudio_data_callback_result_t SenderEngine::onLocalAudioReady(
        void* audioData, int32_t numFrames) {
    int16_t* out       = static_cast<int16_t*>(audioData);
    int      remaining = numFrames;

    // Output silence when paused
    if (paused_.load(std::memory_order_acquire)) {
        std::memset(out, 0, numFrames * sizeof(int16_t));
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    while (remaining > 0) {
        if (!localCurrent_) {
            LocalChunk* next = localRing_ ? localRing_->peek() : nullptr;
            if (!next) {
                std::memset(out, 0, remaining * sizeof(int16_t));
                return AAUDIO_CALLBACK_RESULT_CONTINUE;
            }
            if (next->playAtNs > 0 && nowNs() < next->playAtNs) {
                std::memset(out, 0, remaining * sizeof(int16_t));
                return AAUDIO_CALLBACK_RESULT_CONTINUE;
            }
            localCurrent_ = localRing_->pop();
            if (!localCurrent_) {
                std::memset(out, 0, remaining * sizeof(int16_t));
                return AAUDIO_CALLBACK_RESULT_CONTINUE;
            }
        }
        int available = localCurrent_->frames - localCurrent_->offset;
        int toCopy    = std::min(remaining, available);
        std::memcpy(out, localCurrent_->data + localCurrent_->offset,
                    toCopy * sizeof(int16_t));
        out                    += toCopy;
        localCurrent_->offset  += toCopy;
        remaining              -= toCopy;
        if (localCurrent_->offset >= localCurrent_->frames) {
            if (localPool_) localPool_->release(localCurrent_);
            localCurrent_ = nullptr;
        }
    }
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

// ─────────────────────────────────────────────────────────────────────────────
//  Streaming loop
//
//  States:
//    PAUSED-IDLE: paused_=true → sleep 10ms, loop. No decode, no send.
//    ANCHOR-WAIT: paused_=false, sharedStartAtNs_=0 or >8s → sleep 50ms, loop.
//                 Enters this state at start and after resume()/seekToMs().
//                 If still waiting after 15s → inject synthetic anchor to
//                 self-rescue from a situation where all re-handshakes failed.
//    PLAYING:     paused_=false, anchor valid, nextPlayAtNs set.
//                 Decode → rate-limit → send → advance.
//
//  Seek handling (inside ANCHOR-WAIT → PLAYING transition):
//    If seekRequested_=true when exiting ANCHOR-WAIT:
//      drmp3_seek_to_pcm_frame(mp3, seekTargetFrames_)
//      currentFramePosition_ = seekTargetFrames_
//      seekRequested_ = false
// ─────────────────────────────────────────────────────────────────────────────

void SenderEngine::streamingLoop() {
    // mp3 pointer used only when audioFormat_ == MP3
    drmp3* mp3 = static_cast<drmp3*>(mp3_);

    std::vector<float>   floatBuf(CHUNK_FRAMES * channelCount_);
    // pcmBuf needs room for upsampled output (up to 2x for 22050→44100)
    std::vector<int16_t> pcmBuf(CHUNK_FRAMES * 3);
    std::vector<int16_t> filteredBuf(CHUNK_FRAMES * 3);

    bool    anchorApplied     = false;
    int64_t nextPlayAtNs      = 0;
    int64_t anchorWaitStartNs = 0; // non-zero while in ANCHOR-WAIT; used for timeout

    while (streaming_.load()) {

        // ── PAUSED-IDLE state ─────────────────────────────────────────────────
        if (paused_.load(std::memory_order_acquire)) {
            struct timespec ts{0, 10'000'000}; // 10ms
            nanosleep(&ts, nullptr);
            // Reset anchor so we re-enter ANCHOR-WAIT when unpaused
            anchorApplied     = false;
            anchorWaitStartNs = 0;
            continue;
        }

        // ── ANCHOR-WAIT state ─────────────────────────────────────────────────
        if (!anchorApplied) {
            int64_t shared = sharedStartAtNs_.load(std::memory_order_acquire);
            int64_t now    = nowNs();
            if (shared <= 0 || (shared - now) > 8'000'000'000LL) {

                // Start the wait timer on first entry into this wait block
                if (anchorWaitStartNs == 0) anchorWaitStartNs = now;

                // ── Timeout guard ─────────────────────────────────────────────
                // If we have been waiting more than 15s with no handshake
                // completing (e.g. all receivers dropped mid-handshake and
                // sharedStartAtNs_ was never pushed), self-rescue by injecting
                // a synthetic anchor. 15s > the 10s recvLine timeout in
                // handshakeClient, so all stalled threads have already returned.
                if ((now - anchorWaitStartNs) > 15'000'000'000LL) {
                    int64_t synthetic = now + 1'100'000'000LL;
                    sharedStartAtNs_.store(synthetic, std::memory_order_release);
                    LOGE("[ANCHOR-WAIT] Timed out after 15s — injecting synthetic anchor "
                         "(all re-handshakes failed). Streaming will resume in ~1.1s.");
                    anchorWaitStartNs = 0;
                    continue;
                }

                struct timespec ts{0, 50'000'000};
                nanosleep(&ts, nullptr);
                continue;
            }

            // Anchor arrived — clear the wait timer
            anchorWaitStartNs = 0;

            // Anchor available — apply seek if requested
            if (seekRequested_.load(std::memory_order_acquire)) {
                int64_t targetFrame = seekTargetFrames_.load(std::memory_order_acquire);
                LOGI("[SEEK] Seeking to frame %lld (%lld ms)",
                     (long long)targetFrame,
                     (long long)(targetFrame * 1000LL / (int64_t)sampleRate_));
                seekToFrame(targetFrame);
                currentFramePosition_.store(targetFrame, std::memory_order_relaxed);                seekRequested_.store(false, std::memory_order_release);
                LOGI("[SEEK] Seek complete — resuming from %lld ms",
                     (long long)getPositionMs());
            }

            nextPlayAtNs  = shared;
            anchorApplied = true;
            LOGI("[IDEA5] streamingLoop: anchor applied, nextPlayAt %+lld ms from now",
                 (long long)((nextPlayAtNs - nowNs()) / 1'000'000LL));
        }

        // ── PLAYING state ─────────────────────────────────────────────────────

        // Re-enter ANCHOR-WAIT if sharedStartAtNs_ was reset (resume/seek fired)
        {
            int64_t shared = sharedStartAtNs_.load(std::memory_order_acquire);
            if (shared <= 0) {
                anchorApplied     = false;
                anchorWaitStartNs = 0;
                continue;
            }
        }

        // ── Decode ────────────────────────────────────────────────────────────
        uint64_t decoded = (audioFormat_ == AudioFormat::AAC && aac_)
                           ? aac_->readFramesF32(CHUNK_FRAMES, floatBuf.data())
                           : drmp3_read_pcm_frames_f32(mp3, CHUNK_FRAMES, floatBuf.data());

        if (decoded == 0) {
            // End of file — loop back to beginning
            seekToFrame(0);
            currentFramePosition_.store(0, std::memory_order_relaxed);
            nextPlayAtNs += (int64_t)CHUNK_FRAMES * 1'000'000'000LL / (int64_t)sampleRate_;
            LOGI("Audio loop — restarting from beginning");
            continue;
        }

        // Update position counter
        currentFramePosition_.fetch_add((int64_t)decoded, std::memory_order_relaxed);

// ── Stereo→mono, float→int16, normalize to TARGET_RATE ───────────────
        // If the file rate differs from 44100, linearly resample so that every
        // receiver always gets 44100 Hz PCM regardless of source file rate.
        // This fixes the 0.5x speed bug for 22050 Hz files.
        static constexpr uint32_t TARGET_RATE = 44100;
        for (int i = 0; i < (int)decoded; i++) {
            float sample = floatBuf[i * channelCount_];
            if (channelCount_ == 2)
                sample = (sample + floatBuf[i * 2 + 1]) * 0.5f;
            sample    = std::max(-1.0f, std::min(1.0f, sample));
            pcmBuf[i] = static_cast<int16_t>(sample * 32767.0f);
        }
        // Resample to TARGET_RATE if file rate differs
        // Uses nearest-neighbour for simplicity — good enough for sync.
        uint32_t fileRate = sampleRate_;
        int outFrames = (int)decoded;
        if (fileRate != TARGET_RATE && fileRate > 0) {
            outFrames = (int)((int64_t)decoded * TARGET_RATE / fileRate);
            outFrames = std::min(outFrames, (int)pcmBuf.size());
            // Nearest-neighbour resample in-place using a scratch copy
            std::vector<int16_t> src(pcmBuf.begin(), pcmBuf.begin() + decoded);
            for (int i = 0; i < outFrames; i++) {
                int srcIdx = (int)((int64_t)i * fileRate / TARGET_RATE);
                srcIdx = std::min(srcIdx, (int)decoded - 1);
                pcmBuf[i] = src[srcIdx];
            }
        }

        // ── Rate-limit — send PRE_SEND_LEAD_NS ahead of play time ─────────────
        {
            static constexpr int64_t PRE_SEND_LEAD_NS = 1'000'000'000LL; // 1000 ms
            int64_t wait = nextPlayAtNs - PRE_SEND_LEAD_NS - nowNs();
            if (wait > 0) {
                // Break wait into 10ms slices so we can react to pause/seek quickly
                while (wait > 0 && streaming_.load()
                       && !paused_.load(std::memory_order_acquire)
                       && sharedStartAtNs_.load(std::memory_order_acquire) > 0) {
                    int64_t slice = std::min(wait, (int64_t)10'000'000LL);
                    struct timespec ts{};
                    ts.tv_sec  = slice / 1'000'000'000LL;
                    ts.tv_nsec = slice % 1'000'000'000LL;
                    nanosleep(&ts, nullptr);
                    wait = nextPlayAtNs - PRE_SEND_LEAD_NS - nowNs();
                }
                // If we exited because of pause/seek, loop back
                if (paused_.load(std::memory_order_acquire)
                    || sharedStartAtNs_.load(std::memory_order_acquire) <= 0) {
                    currentFramePosition_.fetch_add(-(int64_t)decoded,
                                                    std::memory_order_relaxed);
                    int64_t curFrame = currentFramePosition_.load(std::memory_order_relaxed);
                    seekToFrame(curFrame);
                    anchorApplied = false;
                    continue;
                }
            }
        }

        // ── Local ring ────────────────────────────────────────────────────────
        //
        // Gate: only push chunks that are within LOCAL_RING_LEAD_NS of their
        // play time. Without this, the streaming loop floods the 64-slot ring
        // (~172 chunks on resume), overflows it, and drops frames 65-171,
        // causing the "constant skip" pattern: 371ms audio / 626ms silence.
        // The network send still happens PRE_SEND_LEAD_NS (1000ms) early.
        {
            static constexpr int64_t LOCAL_RING_LEAD_NS = 200'000'000LL; // 200ms
            int64_t localPlayAt      = nextPlayAtNs + senderHwLatencyNs_;
            int64_t timeUntilNeeded  = localPlayAt - LOCAL_RING_LEAD_NS - nowNs();

            if (timeUntilNeeded > 0) {
                int64_t sleepNs = std::min(timeUntilNeeded, (int64_t)10'000'000LL);
                struct timespec ts{};
                ts.tv_sec  = sleepNs / 1'000'000'000LL;
                ts.tv_nsec = sleepNs % 1'000'000'000LL;
                nanosleep(&ts, nullptr);
                if (paused_.load(std::memory_order_acquire)
                    || sharedStartAtNs_.load(std::memory_order_acquire) <= 0) {
                    currentFramePosition_.fetch_add(-(int64_t)decoded,
                                                    std::memory_order_relaxed);
                    int64_t curFrame =
                            currentFramePosition_.load(std::memory_order_relaxed);
                    seekToFrame(curFrame);                    anchorApplied = false;
                    continue;
                }
            }

// Push to local ring (either on-time or after the sleep above)
            if (localPool_ && localRing_ && localStream_) {
                LocalChunk* lc = localPool_->acquire();
                if (lc) {
                    std::memcpy(lc->data, pcmBuf.data(), outFrames * sizeof(int16_t));                    // Apply local role filter if set (independent of network sends)
                    if (localFilter_) {
                        localFilter_->process(lc->data, (int)decoded);
                    }
                    lc->frames   = outFrames;
                    lc->offset   = 0;
                    lc->playAtNs = localPlayAt;
                    if (!localRing_->push(lc)) localPool_->release(lc);
                }
            }
        }

// ── Build header — speaker-exit timestamp ─────────────────────────────
        int64_t speakerExitNs = nextPlayAtNs + senderHwLatencyNs_;
        int     dataLen       = outFrames * (int)sizeof(int16_t);
        uint8_t header[HEADER_BYTES];
        for (int i = 0; i < 8; i++)
            header[i] = (uint8_t)((speakerExitNs >> (56 - i * 8)) & 0xFF);
        header[8]  = (uint8_t)((dataLen >> 24) & 0xFF);
        header[9]  = (uint8_t)((dataLen >> 16) & 0xFF);
        header[10] = (uint8_t)((dataLen >>  8) & 0xFF);
        header[11] = (uint8_t)( dataLen        & 0xFF);

        // ── Send to clients ───────────────────────────────────────────────────
        {
            std::lock_guard<std::mutex> lk(clientsMutex_);
            for (auto* s : clients_) {
                if (!s->ready) continue;

                const int16_t* toSend = pcmBuf.data();

                // ── Per-client filtering ──────────────────────────────────────
                // filter is non-null only for band roles (bass/mid/treble).
                // SROLE_FULL clients receive unfiltered PCM directly.
                if (s->filter) {
                    std::memcpy(filteredBuf.data(), pcmBuf.data(),
                                outFrames * sizeof(int16_t));
                    s->filter->process(filteredBuf.data(), outFrames);
                    toSend = filteredBuf.data();
                }

                // ── Per-client biquad EQ ──────────────────────────────────────
                // eq is non-null only when the user has dialled in a non-flat EQ.
                // Applies to ALL roles (full, bass, mid, treble).
                // If toSend still points to pcmBuf, copy into filteredBuf first
                // so we never modify the shared decode buffer.
                if (s->eq) {
                    if (toSend == pcmBuf.data()) {
                        std::memcpy(filteredBuf.data(), pcmBuf.data(),
                                    outFrames * sizeof(int16_t));
                        toSend = filteredBuf.data();
                    }
                    s->eq->process(filteredBuf.data(), outFrames);
                }

                // ── Per-device gain ───────────────────────────────────────────
                // Skip the multiply when gain is exactly 1.0 (common case).
                // When gain != 1.0 and toSend still points to the shared pcmBuf,
                // copy into filteredBuf first to avoid modifying the shared buffer.
                float g = s->gain.load(std::memory_order_relaxed);
                if (g != 1.0f) {
                    if (toSend == pcmBuf.data()) {
                        std::memcpy(filteredBuf.data(), pcmBuf.data(),
                                    outFrames * sizeof(int16_t));
                        toSend = filteredBuf.data();
                    }
                    int16_t* gb = filteredBuf.data();
                    for (int i = 0; i < outFrames; i++) {
                        float v = gb[i] * g;
                        if      (v >  32767.0f) v =  32767.0f;
                        else if (v < -32768.0f) v = -32768.0f;
                        gb[i] = static_cast<int16_t>(v);
                    }
                }

                if (!sendRaw(s->fd, header, HEADER_BYTES) ||
                    !sendRaw(s->fd, toSend, dataLen)) {
                    LOGE("[%s] Send failed — marking dead", s->addr.c_str());
                    s->ready = false;
                    s->dead  = true;
                    if (statusCb_) statusCb_({ s->addr, s->role, false });
                }
            }
            // Sweep dead clients while mutex is still held.
            cleanDeadClients();
        }

// Advance by actual audio duration (always based on original decoded frames
        // and file rate — this is the true wall-clock duration of this chunk).
        {
            int64_t advance = (int64_t)decoded * 1'000'000'000LL / (int64_t)fileRate;
            static int stampLogCount = 0;
            if (stampLogCount < 5 || (stampLogCount % 50 == 0)) {
                LOGI("[STAMP] chunk#%d speakerExit=%lld ms  nextPlayAt=%lld ms  "
                     "hwLat=%lld ms  advance=%lld ms  decoded=%u  fileRate=%u",
                     stampLogCount,
                     (long long)(speakerExitNs        / 1'000'000LL),
                     (long long)(nextPlayAtNs          / 1'000'000LL),
                     (long long)(senderHwLatencyNs_    / 1'000'000LL),
                     (long long)(advance               / 1'000'000LL),
                     (unsigned)decoded, (unsigned)fileRate);
            }
            stampLogCount++;
            nextPlayAtNs += advance;
            static int postSeekCount = 0;
            static int64_t lastSeekTarget = -1;
            int64_t curTarget = seekTargetFrames_.load(std::memory_order_relaxed);
            if (curTarget != lastSeekTarget && !seekRequested_.load()) {
                lastSeekTarget = curTarget;
                postSeekCount = 0;
            }
            if (postSeekCount < 8) {
                LOGI("[ADVANCE-DIAG] chunk#%d: decoded=%u fileRate=%u advance=%lldms outFrames=%d",
                     postSeekCount++, (unsigned)decoded, (unsigned)fileRate,
                     (long long)(advance / 1'000'000LL), outFrames);
            }
        }
        // ── Broadcast position every ~1 second ───────────────────────────────
        // Sends POSITION:<ms> control packet to all ready clients.
        // Used by receiver mini player progress bar.
        // 15 bytes per second — negligible WiFi overhead.
        {
            static int64_t lastPositionBroadcastNs = 0;
            int64_t nowTs = nowNs();
            if (nowTs - lastPositionBroadcastNs >= 1'000'000'000LL) {
                lastPositionBroadcastNs = nowTs;
                std::string posCmd = "POSITION:" + std::to_string(getPositionMs());
                uint8_t packet[12 + CTRL_BODY_LEN];
                std::memset(packet, 0, sizeof(packet));
                packet[8] = 0xFF; packet[9] = 0xFF;
                packet[10] = 0xFF; packet[11] = 0xFF;
                std::strncpy(reinterpret_cast<char*>(packet + 12),
                             posCmd.c_str(), CTRL_BODY_LEN - 1);
                std::lock_guard<std::mutex> lk(clientsMutex_);
                for (auto* s : clients_) {
                    if (!s->ready) continue;
                    sendRaw(s->fd, packet, sizeof(packet));
                }
            }
        }
    }
}