// ─────────────────────────────────────────────────────────────────────────────
//  CalibEngine.cpp
//
//  Microphone loopback calibration — measures true rxHw by recording the
//  sender's calibration click through the tablet's built-in microphone and
//  finding the exact sample offset via cross-correlation.
// ─────────────────────────────────────────────────────────────────────────────

#include "CalibEngine.h"
#include <android/log.h>
#include <algorithm>
#include <cstring>
#include <cmath>
#include <netinet/tcp.h>
#include <time.h>

#define TAG "CalibEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

// ─────────────────────────────────────────────────────────────────────────────
//  Construction
// ─────────────────────────────────────────────────────────────────────────────

CalibEngine::CalibEngine() {
    recBuf_.resize(CALIB_MAX_SAMPLES, 0);
    buildClickTemplate();
    LOGD("CalibEngine constructed — template %d samples", (int)tmpl_.size());
}

CalibEngine::~CalibEngine() {
    stop();
}

// ─────────────────────────────────────────────────────────────────────────────
//  Click template
//
//  The sender plays the EXACT same shape (SenderEngine::playCalibClick).
//  Shape: a half-Hann-windowed raised-cosine burst — broadband but compact.
//  Length CALIB_CLICK_LEN (128 samples ≈ 2.9 ms at 44100 Hz).
// ─────────────────────────────────────────────────────────────────────────────

void CalibEngine::buildClickTemplate() {
    tmpl_.resize(CALIB_CLICK_LEN);
    for (int i = 0; i < CALIB_CLICK_LEN; i++) {
        // Half-Hann window
        float w = 0.5f * (1.0f - std::cos(float(M_PI) * i / (CALIB_CLICK_LEN - 1)));
        // 4-cycle burst within the window
        float s = w * std::sin(2.0f * float(M_PI) * 4.0f * i / CALIB_CLICK_LEN);
        tmpl_[i] = static_cast<int16_t>(s * 32767.0f);
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  start / stop
// ─────────────────────────────────────────────────────────────────────────────

bool CalibEngine::start(const std::string& senderIP,
                        int64_t            clockOffsetNs,
                        ProgressCallback   cb) {
    if (running_.load()) stop();

    senderIP_         = senderIP;
    providedOffsetNs_ = clockOffsetNs;
    progressCb_       = std::move(cb);
    resultNs_.store(0);
    recHead_.store(0);
    recFull_  = false;
    running_.store(true);

    calibThread_ = std::thread(&CalibEngine::calibLoop, this);
    return true;
}

void CalibEngine::stop() {
    running_.store(false);

    if (tcpFd_ != -1) {
        ::shutdown(tcpFd_, SHUT_RDWR);
        ::close(tcpFd_);
        tcpFd_ = -1;
    }
    closeMicStream();

    if (calibThread_.joinable()) calibThread_.join();
}

// ─────────────────────────────────────────────────────────────────────────────
//  calibLoop — main worker thread
// ─────────────────────────────────────────────────────────────────────────────

void CalibEngine::calibLoop() {
    auto progress = [&](const std::string& msg) {
        LOGI("%s", msg.c_str());
        if (progressCb_) progressCb_(msg);
    };

    progress("Calibration started — connecting to sender...");

    // ── Step 1: Connect to sender calib port ─────────────────────────────────
    if (!connectTCP(senderIP_, CALIB_TCP_PORT)) {
        progress("ERROR: Could not connect to sender (port 5001). Is sender running?");
        running_.store(false);
        return;
    }
    progress("Connected.");

    // ── Step 2: Clock sync ────────────────────────────────────────────────────
    int64_t clockOffset = (providedOffsetNs_ != 0)
                          ? providedOffsetNs_
                          : performClockSync(tcpFd_);
    LOGI("Clock offset: %lld ms", (long long)(clockOffset / 1'000'000LL));
    progress("Clock sync done. Offset: "
             + std::to_string(clockOffset / 1'000'000LL) + " ms");

    // ── Step 3: Open microphone ───────────────────────────────────────────────
    progress("Opening microphone...");
    if (!openMicStream()) {
        progress("ERROR: Could not open microphone. Check RECORD_AUDIO permission.");
        running_.store(false);
        return;
    }

    // Record the mic-start wall-clock time so we can map sample→ns later.
    micStartNs_ = nowNs();
    progress("Microphone open. Waiting for sender click...");

    // ── Step 4: Send CALIB_READY — trigger sender to play the click ───────────
    sendLine(tcpFd_, "CALIB_READY");

    // ── Step 5: Receive CALIB_CLICK:<senderNs> ────────────────────────────────
    std::string clickLine = readLine(tcpFd_);
    int64_t     clickReceivedAtNs = nowNs();

    if (clickLine.rfind("CALIB_CLICK:", 0) != 0) {
        progress("ERROR: Expected CALIB_CLICK, got: '" + clickLine + "'");
        closeMicStream();
        running_.store(false);
        return;
    }

    int64_t clickSenderNs = std::stoll(clickLine.substr(12));
    // Convert sender timestamp to receiver clock
    int64_t clickSenderInReceiverNs = clickSenderNs - clockOffset;

    LOGI("CALIB_CLICK received. senderNs=%lld  inReceiverClock=%lld  receivedAt=%lld",
         (long long)clickSenderNs,
         (long long)clickSenderInReceiverNs,
         (long long)clickReceivedAtNs);

    // ── Step 6: Record for ~2 more seconds to capture the acoustic path ───────
    // The click travels through: sender DAC → speaker → air → tablet mic → ADC.
    // Worst case air + pipeline ≈ 500 ms, so 2 s is very safe.
    progress("Recording... (please do not speak or make noise)");

    int targetSamples = std::min(CALIB_MAX_SAMPLES,
                                 (int)(CALIB_SAMPLE_RATE * 2.5f));

    // Poll until we have enough samples or timeout
    int64_t timeout = nowNs() + 3'000'000'000LL;  // 3 s absolute timeout
    while (running_.load() && recHead_.load() < targetSamples && nowNs() < timeout) {
        struct timespec ts{0, 10'000'000L};  // 10 ms poll
        nanosleep(&ts, nullptr);
    }

    int samplesRecorded = recHead_.load();
    LOGI("Recording complete: %d samples (%.1f ms)",
         samplesRecorded, samplesRecorded * 1000.0f / CALIB_SAMPLE_RATE);

    closeMicStream();

    if (!running_.load()) {
        progress("Cancelled.");
        return;
    }

    if (samplesRecorded < CALIB_CLICK_LEN * 4) {
        progress("ERROR: Not enough mic data recorded.");
        running_.store(false);
        return;
    }

    // ── Step 7: Cross-correlate ────────────────────────────────────────────────
    progress("Analysing recording...");

    // We expect the click to have arrived roughly at clickSenderInReceiverNs.
    // Convert to a sample index as a search-centre hint.
    int64_t expectedArrivalNs = clickSenderInReceiverNs - micStartNs_;
    int     expectedSample    = (int)(expectedArrivalNs * CALIB_SAMPLE_RATE / 1'000'000'000LL);

    // Search ±500 ms around the expected arrival.
    // If expectedSample is unreliable (clockOffset was 0), search everything.
    int searchStart = 0;
    int searchEnd   = -1; // -1 means search to end of buffer
    if (providedOffsetNs_ != 0 && expectedSample > 0) {
        searchStart = std::max(0, expectedSample - CALIB_SAMPLE_RATE / 2);
        searchEnd   = std::min((int)recBuf_.size(), expectedSample + CALIB_SAMPLE_RATE / 2);
        LOGI("Search window: [%d, %d] samples (expected at %d, ±500ms)",
             searchStart, searchEnd, expectedSample);
    }

    // Snapshot the recording buffer (thread-safe: mic stream already closed)
    std::vector<int16_t> rec(recBuf_.begin(),
                             recBuf_.begin() + samplesRecorded);

    int peakSample = findClickPeak(rec, searchStart, searchEnd);
    if (peakSample < 0) {
        progress("ERROR: Could not detect click in mic recording. "
                 "Please try again — place devices within 1 m.");
        running_.store(false);
        return;
    }

    // ── Step 8: Compute rxHw ──────────────────────────────────────────────────
    // peakSample = sample index in the mic recording where the click arrived.
    // micStartNs_ = wall-clock time (receiver) when we started recording.
    // clickSenderNs = sender clock timestamp when click was PLAYED.
    //
    // clickArrivalNs (receiver clock) = micStartNs_ + peakSample / sampleRate
    // rxHw = clickArrivalNs - (clickSenderNs - clockOffset)
    //      = clickArrivalNs - clickSenderInReceiverNs
    //
    // This is the one-way acoustic delay: sender pipeline + air + mic ADC.
    // That IS the value the receiver needs to schedule AAudio in advance.

    int64_t clickArrivalNs     = micStartNs_
                                 + (int64_t)peakSample * 1'000'000'000LL / CALIB_SAMPLE_RATE;
    int64_t rxHwNs             = clickArrivalNs - clickSenderInReceiverNs;

    LOGI("peakSample=%d  clickArrivalNs=%lld ms  clickSenderInReceiver=%lld ms  rxHw=%lld ms",
         peakSample,
         (long long)(clickArrivalNs / 1'000'000LL),
         (long long)(clickSenderInReceiverNs / 1'000'000LL),
         (long long)(rxHwNs / 1'000'000LL));

    // Sanity check: rxHw should be between 10 ms and 2000 ms.
    if (rxHwNs < 10'000'000LL || rxHwNs > 2'000'000'000LL) {
        progress("ERROR: Result out of range (" + std::to_string(rxHwNs / 1'000'000LL)
                 + " ms). Please retry closer to the sender speaker.");
        running_.store(false);
        return;
    }

    resultNs_.store(rxHwNs);
    progress("Done! rxHw = " + std::to_string(rxHwNs / 1'000'000LL) + " ms");
    LOGI("=== CALIBRATION RESULT: rxHw = %lld ms ===", (long long)(rxHwNs / 1'000'000LL));

    running_.store(false);
}

// ─────────────────────────────────────────────────────────────────────────────
//  Cross-correlation
//
//  Normalised cross-correlation of tmpl_ against rec starting at searchStart.
//  Returns the sample index of the highest-confidence peak, or -1.
// ─────────────────────────────────────────────────────────────────────────────

int CalibEngine::findClickPeak(const std::vector<int16_t>& rec,
                               int searchStart, int searchEnd) const {
    const int tLen   = (int)tmpl_.size();
    const int rLen   = (int)rec.size();
    const int maxLag = rLen - tLen;

    // Clamp searchEnd to valid range
    int endLag = (searchEnd > 0) ? std::min(searchEnd, maxLag) : maxLag;

    if (endLag <= searchStart) {
        LOGE("findClickPeak: searchStart=%d >= endLag=%d", searchStart, endLag);
        return -1;
    }

    // Pre-compute template energy (normalisation denominator)
    double tEnergy = 0.0;
    for (int i = 0; i < tLen; i++) tEnergy += (double)tmpl_[i] * tmpl_[i];
    if (tEnergy < 1.0) { LOGE("findClickPeak: template is silent"); return -1; }
    double tNorm = std::sqrt(tEnergy);

    double bestScore = 0.0;
    int    bestLag   = -1;

    for (int lag = searchStart; lag <= endLag; lag++) {
        double corr   = 0.0;
        double rEnergy = 0.0;
        for (int i = 0; i < tLen; i++) {
            double r = rec[lag + i];
            double t = tmpl_[i];
            corr    += r * t;
            rEnergy += r * r;
        }
        if (rEnergy < 1.0) continue;
        double score = corr / (tNorm * std::sqrt(rEnergy));  // in [-1, 1]
        if (score > bestScore) {
            bestScore = score;
            bestLag   = lag;
        }
    }

    LOGI("Cross-correlation peak: lag=%d  score=%.4f", bestLag, bestScore);

    // Require reasonable confidence — 0.15 is very conservative, helps with noisy rooms.
    if (bestScore < 0.15) {
        LOGE("findClickPeak: peak score %.4f below threshold 0.15 — click not found", bestScore);
        return -1;
    }

    return bestLag;
}

// ─────────────────────────────────────────────────────────────────────────────
//  AAudio input stream
// ─────────────────────────────────────────────────────────────────────────────

bool CalibEngine::openMicStream() {
    AAudioStreamBuilder* builder = nullptr;
    aaudio_result_t r = AAudio_createStreamBuilder(&builder);
    if (r != AAUDIO_OK) { LOGE("createStreamBuilder: %s", AAudio_convertResultToText(r)); return false; }

    AAudioStreamBuilder_setDirection(builder,    AAUDIO_DIRECTION_INPUT);
    AAudioStreamBuilder_setSampleRate(builder,   CALIB_SAMPLE_RATE);
    AAudioStreamBuilder_setChannelCount(builder, 1);
    AAudioStreamBuilder_setFormat(builder,       AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setDataCallback(builder, CalibEngine::micCallback, this);
    AAudioStreamBuilder_setFramesPerDataCallback(builder, 512);

    r = AAudioStreamBuilder_openStream(builder, &micStream_);
    AAudioStreamBuilder_delete(builder);

    if (r != AAUDIO_OK) {
        LOGE("openStream (INPUT): %s", AAudio_convertResultToText(r));
        micStream_ = nullptr;
        return false;
    }

    actualSampleRate_ = AAudioStream_getSampleRate(micStream_);
    LOGI("Mic stream opened at %d Hz", actualSampleRate_);

    r = AAudioStream_requestStart(micStream_);
    if (r != AAUDIO_OK) {
        LOGE("requestStart (mic): %s", AAudio_convertResultToText(r));
        closeMicStream();
        return false;
    }

    LOGI("Mic stream started.");
    return true;
}

void CalibEngine::closeMicStream() {
    if (micStream_) {
        AAudioStream_requestStop(micStream_);
        AAudioStream_close(micStream_);
        micStream_ = nullptr;
    }
}

aaudio_data_callback_result_t CalibEngine::micCallback(
        AAudioStream*, void* userData, void* audioData, int32_t numFrames) {
    return static_cast<CalibEngine*>(userData)->onMicData(audioData, numFrames);
}

aaudio_data_callback_result_t CalibEngine::onMicData(const void* audioData, int32_t numFrames) {
    // Drop frames once buffer is full
    int head = recHead_.load(std::memory_order_relaxed);
    if (head + numFrames > CALIB_MAX_SAMPLES) {
        recFull_ = true;
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    std::memcpy(recBuf_.data() + head,
                static_cast<const int16_t*>(audioData),
                numFrames * sizeof(int16_t));

    recHead_.store(head + numFrames, std::memory_order_release);
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

// ─────────────────────────────────────────────────────────────────────────────
//  TCP helpers
// ─────────────────────────────────────────────────────────────────────────────

bool CalibEngine::connectTCP(const std::string& ip, int port) {
    tcpFd_ = ::socket(AF_INET, SOCK_STREAM, 0);
    if (tcpFd_ < 0) { LOGE("socket: %s", strerror(errno)); return false; }

    int flag = 1;
    ::setsockopt(tcpFd_, IPPROTO_TCP, TCP_NODELAY, &flag, sizeof(flag));

    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port   = htons(port);
    ::inet_pton(AF_INET, ip.c_str(), &addr.sin_addr);

    if (::connect(tcpFd_, (sockaddr*)&addr, sizeof(addr)) < 0) {
        LOGE("connect %s:%d: %s", ip.c_str(), port, strerror(errno));
        ::close(tcpFd_);
        tcpFd_ = -1;
        return false;
    }
    LOGI("TCP connected to %s:%d", ip.c_str(), port);
    return true;
}

bool CalibEngine::readFully(int fd, void* buf, int len) {
    uint8_t* p = static_cast<uint8_t*>(buf);
    int done = 0;
    while (done < len) {
        int n = ::recv(fd, p + done, len - done, 0);
        if (n <= 0) return false;
        done += n;
    }
    return true;
}

void CalibEngine::sendLine(int fd, const std::string& line) {
    std::string msg = line + "\n";
    ::send(fd, msg.c_str(), msg.size(), MSG_NOSIGNAL);
}

std::string CalibEngine::readLine(int fd) {
    std::string result;
    char c;
    while (running_.load()) {
        int n = ::recv(fd, &c, 1, 0);
        if (n <= 0) break;
        if (c == '\n') break;
        if (c != '\r') result += c;
    }
    return result;
}

// ─────────────────────────────────────────────────────────────────────────────
//  Clock sync (NTP-style ping-pong)
// ─────────────────────────────────────────────────────────────────────────────

int64_t CalibEngine::performClockSync(int fd) {
    std::vector<int64_t> offsets;
    offsets.reserve(CALIB_SYNC_ROUNDS);

    for (int i = 0; i < CALIB_SYNC_ROUNDS && running_.load(); i++) {
        int64_t t1 = nowNs();
        sendLine(fd, "PING:" + std::to_string(t1));

        std::string pong = readLine(fd);
        int64_t     t3   = nowNs();

        if (pong.rfind("PONG:", 0) != 0) continue;
        size_t colon2 = pong.rfind(':');
        if (colon2 <= 4) continue;

        int64_t t2     = std::stoll(pong.substr(colon2 + 1));
        int64_t rtt    = t3 - t1;
        int64_t offset = t2 - t1 - rtt / 2;
        offsets.push_back(offset);

        LOGD("sync[%d]: rtt=%lld ms  offset=%lld ms",
             i, (long long)(rtt/1'000'000LL), (long long)(offset/1'000'000LL));

        struct timespec ts{0, 5'000'000L};
        nanosleep(&ts, nullptr);
    }

    if (offsets.empty()) { LOGE("performClockSync: no samples"); return 0LL; }

    std::sort(offsets.begin(), offsets.end());
    int trim  = (int)(offsets.size() * 0.2f);
    int start = trim, end = (int)offsets.size() - trim;
    if (start >= end) { start = 0; end = (int)offsets.size(); }

    int64_t median = offsets[(start + end) / 2];
    LOGI("CalibEngine clock sync: offset=%lld ms", (long long)(median / 1'000'000LL));
    return median;
}

// ─────────────────────────────────────────────────────────────────────────────
//  Timing
// ─────────────────────────────────────────────────────────────────────────────

int64_t CalibEngine::nowNs() {
    struct timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1'000'000'000LL + ts.tv_nsec;
}