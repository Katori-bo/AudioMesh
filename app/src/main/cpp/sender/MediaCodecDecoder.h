#pragma once

// ─────────────────────────────────────────────────────────────────────────────
//  MediaCodecDecoder
//
//  Decodes AAC/M4A files to interleaved float PCM using Android's NDK
//  MediaExtractor + MediaCodec APIs. No JVM involvement — pure NDK C++.
//
//  Interface mirrors dr_mp3 usage in SenderEngine:
//    open(fd)          → sets sampleRate, channelCount, totalFrames
//    readFramesF32()   → fills caller's float buffer, returns frames decoded
//    seekToFrame()     → seeks to PCM frame index
//    close()           → releases all MediaCodec/Extractor resources
//
//  Thread safety: NOT thread-safe. All calls must come from the same thread
//  (the streaming loop thread), same as dr_mp3.
//
//  Supported input: AAC-LC, HE-AAC in .m4a / .aac containers.
//  Output: interleaved float PCM, native sample rate + channel count.
// ─────────────────────────────────────────────────────────────────────────────

#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>

#include <string>
#include <vector>
#include <cstdint>

class MediaCodecDecoder {
public:
    MediaCodecDecoder()  = default;
    ~MediaCodecDecoder() { close(); }

    // Opens the file descriptor and initialises extractor + codec.
    // Returns true on success. On failure, object is left in closed state.
    bool open(int fd);

    // Reads up to maxFrames PCM frames into dst (interleaved floats).
    // Returns number of frames actually written. Returns 0 at end-of-stream.
    uint64_t readFramesF32(int maxFrames, float* dst);

    // Seeks to the given PCM frame index. Approximate — AAC seeks to the
    // nearest sync frame, then the decoder discards leading samples.
    void seekToFrame(int64_t frame);

    // Releases all resources. Safe to call multiple times.
    void close();

    // ── Metadata — valid after open() returns true ────────────────────────────
    uint32_t sampleRate()    const { return sampleRate_; }
    int      channelCount()  const { return channelCount_; }
    int64_t  totalFrames()   const { return totalFrames_; }
    bool     isOpen()        const { return open_; }

private:
    // Drains one output buffer from the codec into pcmBuffer_.
    // Returns number of int16 samples written, or 0 if no output yet.
    // Returns -1 on end-of-stream.
    int drainOutputBuffer();

    // Feeds the next compressed input buffer from the extractor to the codec.
    // Returns false when extractor is exhausted (EOS sent to codec).
    bool feedInputBuffer();

    AMediaExtractor* extractor_  = nullptr;
    AMediaCodec*     codec_      = nullptr;
    AMediaFormat*    format_     = nullptr;

    uint32_t sampleRate_   = 44100;
    int      channelCount_ = 2;
    int64_t  totalFrames_  = 0;
    bool     open_         = false;
    bool     eosSent_      = false;  // EOS sent to codec input
    bool     eosReached_   = false;  // EOS received from codec output

    // Leftover decoded int16 samples from the last output buffer that
    // didn't fit into the caller's float buffer. Drained first on next call.
    std::vector<int16_t> overflow_;
    int                  overflowOffset_ = 0;

    // Timeout for codec operations in microseconds
    static constexpr int64_t CODEC_TIMEOUT_US = 5000; // 5ms
};