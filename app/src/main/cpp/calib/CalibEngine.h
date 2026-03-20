#pragma once
// ─────────────────────────────────────────────────────────────────────────────
//  CalibEngine.h
//
//  Microphone loopback calibration.
//
//  HOW IT WORKS:
//  1. CalibEngine connects to sender on CALIB_TCP_PORT and does a quick
//     clock sync (ping-pong) to get a fresh clockOffsetNs.
//  2. It opens an AAudio INPUT stream to record the tablet microphone
//     continuously into a ring buffer.
//  3. It tells the sender "CALIB_READY" — the sender plays a sharp click
//     on its local speaker and sends back "CALIB_CLICK:<senderNs>".
//  4. After recording for ~2 s more (to capture the acoustic path), CalibEngine
//     cross-correlates the mic buffer against a click template, finds the
//     peak offset, and converts to rxHw:
//
//       clickArrivalNs  = (peakSample / sampleRate) * 1e9  (receiver clock)
//       clickSenderNs   = value from CALIB_CLICK message (sender clock)
//       rxHw            = clockOffsetNs + clickArrivalNs - clickSenderNs
//
//     Intuitively: we measured how long after the sender played the click until
//     the microphone heard it.  That acoustic delay = speaker pipeline + air.
//     That IS rxHw — exactly what the receiver must subtract from senderExitNs
//     to schedule AAudio.
//
//  Accuracy: ±1 ms (cross-correlation peak resolution at 44100 Hz ≈ 0.023 ms)
//  Air travel: ~1 ms per 30 cm — put devices within 1 m for best results.
//
//  Usage (Java side):
//    long h = NativeEngine.calibCreate();
//    NativeEngine.calibStart(h, senderIP, clockOffsetNs);
//    // poll NativeEngine.calibIsRunning(h) until false
//    long rxHwMs = NativeEngine.calibGetResultMs(h);  // 0 = failed
//    NativeEngine.calibDestroy(h);
// ─────────────────────────────────────────────────────────────────────────────

#include <aaudio/AAudio.h>
#include <atomic>
#include <thread>
#include <vector>
#include <string>
#include <cstdint>
#include <functional>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>

// ── Constants ──────────────────────────────────────────────────────────────

static constexpr int     CALIB_SAMPLE_RATE     = 44100;
static constexpr int     CALIB_RECORD_SECS     = 4;
static constexpr int     CALIB_MAX_SAMPLES     = CALIB_SAMPLE_RATE * CALIB_RECORD_SECS;
static constexpr int     CALIB_SYNC_ROUNDS     = 10;
static constexpr int     CALIB_TCP_PORT        = 5001;   // separate from audio port 5000

// Click template length (samples). Must match SenderEngine::playCalibClick.
static constexpr int     CALIB_CLICK_LEN       = 128;

// ─────────────────────────────────────────────────────────────────────────────

class CalibEngine {
public:
    using ProgressCallback = std::function<void(const std::string& msg)>;

    CalibEngine();
    ~CalibEngine();

    // Start: connect to sender, sync clocks, record mic, detect click.
    // clockOffsetNs: pass the existing clock offset from ReceiverEngine if
    // available, or 0 to let CalibEngine measure its own.
    bool start(const std::string& senderIP,
               int64_t            clockOffsetNs,
               ProgressCallback   cb = nullptr);

    void stop();

    bool    isRunning()  const { return running_.load(); }
    bool    succeeded()  const { return resultNs_.load() > 0; }
    int64_t getResultNs() const { return resultNs_.load(); }

private:
    // ── AAudio input ─────────────────────────────────────────────────────────
    bool openMicStream();
    void closeMicStream();

    static aaudio_data_callback_result_t micCallback(
            AAudioStream*, void* userData, void* audioData, int32_t numFrames);
    aaudio_data_callback_result_t onMicData(const void* audioData, int32_t numFrames);

    // ── TCP ──────────────────────────────────────────────────────────────────
    bool        connectTCP(const std::string& ip, int port);
    bool        readFully(int fd, void* buf, int len);
    void        sendLine(int fd, const std::string& line);
    std::string readLine(int fd);

    // ── Clock sync ───────────────────────────────────────────────────────────
    int64_t     performClockSync(int fd);

    // ── Worker ───────────────────────────────────────────────────────────────
    void        calibLoop();

    // ── Signal processing ────────────────────────────────────────────────────
    // Build the expected click template (must mirror what SenderEngine plays).
    void        buildClickTemplate();
    // Cross-correlate template against recording; return peak sample index or -1.
    int         findClickPeak(const std::vector<int16_t>& rec,
                              int searchStart, int searchEnd = -1) const;

    // ── Timing ───────────────────────────────────────────────────────────────
    static int64_t nowNs();

    // ── State ─────────────────────────────────────────────────────────────────
    std::atomic<bool>    running_{false};
    std::atomic<int64_t> resultNs_{0};

    std::string          senderIP_;
    int64_t              providedOffsetNs_ = 0;  // from caller (0 = measure fresh)
    ProgressCallback     progressCb_;

    int                  tcpFd_ = -1;

    // AAudio mic stream
    AAudioStream*        micStream_ = nullptr;
    int                  actualSampleRate_ = CALIB_SAMPLE_RATE;

    // Mic ring buffer — written by AAudio callback, read in calibLoop.
    std::vector<int16_t> recBuf_;   // fixed size CALIB_MAX_SAMPLES
    std::atomic<int>     recHead_{0};   // next-write index (circular, never wraps during calib)
    bool                 recFull_  = false;

    // Mic-start absolute time (receiver clock nanoseconds).
    // Set just before AAudioStream_requestStart so we can map sample indices to ns.
    int64_t              micStartNs_ = 0;

    // Click template
    std::vector<int16_t> tmpl_;

    std::thread          calibThread_;
};