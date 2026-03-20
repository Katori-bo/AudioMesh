#include "MediaCodecDecoder.h"

#include <android/log.h>
#include <unistd.h>
#include <cstring>
#include <algorithm>
#include <cmath>

#define TAG  "MediaCodecDecoder"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

// ─────────────────────────────────────────────────────────────────────────────
//  open
// ─────────────────────────────────────────────────────────────────────────────

bool MediaCodecDecoder::open(int fd) {
    close(); // ensure clean state

    // ── Step 1: Create extractor and set data source from fd ──────────────────
    extractor_ = AMediaExtractor_new();
    if (!extractor_) {
        LOGE("AMediaExtractor_new() failed");
        return false;
    }

    // Get file size for the extractor
    off_t fileSize = lseek(fd, 0, SEEK_END);
    lseek(fd, 0, SEEK_SET);
    if (fileSize <= 0) {
        LOGE("open: lseek failed or empty file");
        close();
        return false;
    }

    media_status_t status = AMediaExtractor_setDataSourceFd(
            extractor_, fd, 0, (off64_t)fileSize);
    if (status != AMEDIA_OK) {
        LOGE("AMediaExtractor_setDataSourceFd failed: %d", status);
        close();
        return false;
    }

    // ── Step 2: Find the first audio track ───────────────────────────────────
    int audioTrack = -1;
    size_t trackCount = AMediaExtractor_getTrackCount(extractor_);

    for (size_t i = 0; i < trackCount; i++) {
        AMediaFormat* fmt = AMediaExtractor_getTrackFormat(extractor_, i);
        const char* mime  = nullptr;
        AMediaFormat_getString(fmt, AMEDIAFORMAT_KEY_MIME, &mime);

        if (mime && strncmp(mime, "audio/", 6) == 0) {
            audioTrack = (int)i;
            // Read format metadata while we have the format object
            int32_t sr = 0, ch = 0;
            AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_SAMPLE_RATE,   &sr);
            AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_CHANNEL_COUNT,  &ch);
            sampleRate_   = sr > 0 ? (uint32_t)sr : 44100;
            channelCount_ = ch > 0 ? ch : 2;

            // Try to get duration and convert to PCM frames
            int64_t durationUs = 0;
            AMediaFormat_getInt64(fmt, AMEDIAFORMAT_KEY_DURATION, &durationUs);
            if (durationUs > 0) {
                totalFrames_ = (int64_t)((double)durationUs / 1e6 * sampleRate_);
            }

            format_ = fmt; // keep it — we pass it to the codec
            LOGI("Audio track %d: mime=%s  sr=%u  ch=%d  duration=%lld ms",
                 audioTrack, mime, sampleRate_, channelCount_,
                 (long long)(durationUs / 1000));
            break;
        }
        AMediaFormat_delete(fmt);
    }

    if (audioTrack < 0) {
        LOGE("No audio track found in file");
        close();
        return false;
    }

    AMediaExtractor_selectTrack(extractor_, (size_t)audioTrack);

    // ── Step 3: Create and configure MediaCodec ───────────────────────────────
    const char* mime = nullptr;
    AMediaFormat_getString(format_, AMEDIAFORMAT_KEY_MIME, &mime);
    if (!mime) {
        LOGE("Could not get MIME from format");
        close();
        return false;
    }

    codec_ = AMediaCodec_createDecoderByType(mime);
    if (!codec_) {
        LOGE("AMediaCodec_createDecoderByType(%s) failed", mime);
        close();
        return false;
    }

    status = AMediaCodec_configure(codec_, format_, nullptr, nullptr, 0);
    if (status != AMEDIA_OK) {
        LOGE("AMediaCodec_configure failed: %d", status);
        close();
        return false;
    }

    status = AMediaCodec_start(codec_);
    if (status != AMEDIA_OK) {
        LOGE("AMediaCodec_start failed: %d", status);
        close();
        return false;
    }

    open_        = true;
    eosSent_     = false;
    eosReached_  = false;
    overflowOffset_ = 0;
    overflow_.clear();

    LOGI("MediaCodecDecoder open: %u Hz  %d ch  totalFrames=%lld",
         sampleRate_, channelCount_, (long long)totalFrames_);
    return true;
}

// ─────────────────────────────────────────────────────────────────────────────
//  close
// ─────────────────────────────────────────────────────────────────────────────

void MediaCodecDecoder::close() {
    if (codec_) {
        AMediaCodec_stop(codec_);
        AMediaCodec_delete(codec_);
        codec_ = nullptr;
    }
    if (format_) {
        AMediaFormat_delete(format_);
        format_ = nullptr;
    }
    if (extractor_) {
        AMediaExtractor_delete(extractor_);
        extractor_ = nullptr;
    }
    open_           = false;
    eosSent_        = false;
    eosReached_     = false;
    overflowOffset_ = 0;
    overflow_.clear();
    totalFrames_    = 0;
}

// ─────────────────────────────────────────────────────────────────────────────
//  feedInputBuffer
//
//  Dequeues one codec input buffer, fills it from the extractor, and queues it.
//  Returns false once EOS has been sent to the codec.
// ─────────────────────────────────────────────────────────────────────────────

bool MediaCodecDecoder::feedInputBuffer() {
    if (eosSent_) return false;

    ssize_t inIdx = AMediaCodec_dequeueInputBuffer(codec_, CODEC_TIMEOUT_US);
    if (inIdx < 0) return true; // no buffer available yet — not an error

    size_t   bufSize = 0;
    uint8_t* buf     = AMediaCodec_getInputBuffer(codec_, (size_t)inIdx, &bufSize);
    if (!buf) return true;

    ssize_t sampleSize = AMediaExtractor_readSampleData(extractor_, buf, bufSize);

    if (sampleSize < 0) {
        // End of stream — send EOS flag with empty buffer
        AMediaCodec_queueInputBuffer(codec_, (size_t)inIdx, 0, 0, 0,
                                     AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
        eosSent_ = true;
        LOGD("feedInputBuffer: EOS sent to codec");
        return false;
    }

    int64_t presentationUs = AMediaExtractor_getSampleTime(extractor_);
    AMediaCodec_queueInputBuffer(codec_, (size_t)inIdx, 0,
                                 (size_t)sampleSize, presentationUs, 0);
    AMediaExtractor_advance(extractor_);
    return true;
}

// ─────────────────────────────────────────────────────────────────────────────
//  drainOutputBuffer
//
//  Dequeues one output buffer from the codec and appends its int16 samples
//  to overflow_. Returns number of int16 samples added, 0 if no output
//  available yet, -1 on EOS.
// ─────────────────────────────────────────────────────────────────────────────

int MediaCodecDecoder::drainOutputBuffer() {
    AMediaCodecBufferInfo info{};
    ssize_t outIdx = AMediaCodec_dequeueOutputBuffer(codec_, &info, CODEC_TIMEOUT_US);

    if (outIdx == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
        // Sample rate or channel count changed mid-stream (rare for AAC)
        AMediaFormat* fmt = AMediaCodec_getOutputFormat(codec_);
        int32_t sr = 0, ch = 0;
        AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_SAMPLE_RATE,  &sr);
        AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &ch);
        if (sr > 0) sampleRate_   = (uint32_t)sr;
        if (ch > 0) channelCount_ = ch;
        AMediaFormat_delete(fmt);
        LOGI("drainOutputBuffer: output format changed — sr=%u ch=%d",
             sampleRate_, channelCount_);
        return 0;
    }

    if (outIdx == AMEDIACODEC_INFO_TRY_AGAIN_LATER ||
        outIdx == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED) {
        return 0;
    }

    if (outIdx < 0) return 0;

    if (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) {
        AMediaCodec_releaseOutputBuffer(codec_, (size_t)outIdx, false);
        eosReached_ = true;
        LOGD("drainOutputBuffer: EOS reached");
        return -1;
    }

    size_t   outSize = 0;
    uint8_t* outBuf  = AMediaCodec_getOutputBuffer(codec_, (size_t)outIdx, &outSize);

    if (outBuf && info.size > 0) {
        // Output is PCM_16BIT interleaved
        int sampleCount = info.size / sizeof(int16_t);
        size_t oldSize  = overflow_.size();
        overflow_.resize(oldSize + sampleCount);
        memcpy(overflow_.data() + oldSize,
               outBuf + info.offset,
               info.size);
    }

    AMediaCodec_releaseOutputBuffer(codec_, (size_t)outIdx, false);
    return (int)(info.size / sizeof(int16_t));
}

// ─────────────────────────────────────────────────────────────────────────────
//  readFramesF32
//
//  Fills dst with up to maxFrames interleaved float PCM frames.
//  Drives the codec pump loop internally — feeds input and drains output
//  until the caller's buffer is full or EOS is reached.
//
//  Returns number of PCM frames written to dst.
//  Returns 0 at end of stream.
// ─────────────────────────────────────────────────────────────────────────────

uint64_t MediaCodecDecoder::readFramesF32(int maxFrames, float* dst) {
    if (!open_ || eosReached_) return 0;

    int samplesNeeded = maxFrames * channelCount_;
    int samplesWritten = 0;

    // ── Drain any leftover samples from previous call first ───────────────────
    while (samplesWritten < samplesNeeded && overflowOffset_ < (int)overflow_.size()) {
        int available = (int)overflow_.size() - overflowOffset_;
        int toCopy    = std::min(samplesNeeded - samplesWritten, available);
        for (int i = 0; i < toCopy; i++) {
            dst[samplesWritten + i] =
                    (float)overflow_[overflowOffset_ + i] / 32768.0f;
        }
        samplesWritten  += toCopy;
        overflowOffset_ += toCopy;
    }

    // If overflow is fully consumed, reset it
    if (overflowOffset_ >= (int)overflow_.size()) {
        overflow_.clear();
        overflowOffset_ = 0;
    }

    // ── Pump codec until buffer is full or EOS ────────────────────────────────
    int stallCount = 0;
    while (samplesWritten < samplesNeeded && !eosReached_) {

        // Feed compressed data into codec
        feedInputBuffer();

        // Drain one output buffer into overflow_
        int drained = drainOutputBuffer();

        if (drained < 0) {
            // EOS
            break;
        }

        if (drained == 0) {
            // No output yet — codec is buffering. Avoid a tight spin.
            stallCount++;
            if (stallCount > 200) {
                // Genuinely stalled — return what we have
                LOGE("readFramesF32: codec stalled after 200 attempts — returning partial");
                break;
            }
            continue;
        }
        stallCount = 0;

        // Copy from overflow_ into dst
        while (samplesWritten < samplesNeeded &&
               overflowOffset_ < (int)overflow_.size()) {
            int available = (int)overflow_.size() - overflowOffset_;
            int toCopy    = std::min(samplesNeeded - samplesWritten, available);
            for (int i = 0; i < toCopy; i++) {
                dst[samplesWritten + i] =
                        (float)overflow_[overflowOffset_ + i] / 32768.0f;
            }
            samplesWritten  += toCopy;
            overflowOffset_ += toCopy;
        }

        if (overflowOffset_ >= (int)overflow_.size()) {
            overflow_.clear();
            overflowOffset_ = 0;
        }
    }

    // Return number of complete PCM frames written
    return (uint64_t)(samplesWritten / channelCount_);
}

// ─────────────────────────────────────────────────────────────────────────────
//  seekToFrame
//
//  Seeks the extractor to the nearest sync sample at or before the target
//  frame. Flushes the codec so it decodes fresh from the seek point.
//  AAC seek is approximate — seek lands on an I-frame, not an exact sample.
// ─────────────────────────────────────────────────────────────────────────────

void MediaCodecDecoder::seekToFrame(int64_t frame) {
    if (!open_) return;

    // Convert frame index to microseconds
    int64_t seekUs = (int64_t)((double)frame / sampleRate_ * 1e6);

    AMediaExtractor_seekTo(extractor_, seekUs, AMEDIAEXTRACTOR_SEEK_PREVIOUS_SYNC);
    AMediaCodec_flush(codec_);

    eosSent_        = false;
    eosReached_     = false;
    overflowOffset_ = 0;
    overflow_.clear();

    LOGD("seekToFrame: frame=%lld → seekUs=%lld ms",
         (long long)frame, (long long)(seekUs / 1000));
}