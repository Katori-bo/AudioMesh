#pragma once
// ─────────────────────────────────────────────────────────────────────────────
//  FirFilter.h  —  header-only windowed-sinc FIR, Blackman window
//
//  Three crossover bands wired up in SenderEngine:
//    Bass   : low-pass  @ 250 Hz
//    Mid    : band-pass   250 Hz – 4 kHz
//    Treble : high-pass @ 4 kHz
//
//  numTaps must be odd (Type-I linear phase). Default = 127.
//  Input/output: int16_t PCM, mono, in-place.
// ─────────────────────────────────────────────────────────────────────────────

#include <cstdint>
#include <cmath>
#include <vector>
#include <algorithm>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

class FirFilter {
public:
    enum class Type { LowPass, HighPass, BandPass };

    // Low-pass or high-pass
    FirFilter(Type type, double cutoffHz, int sampleRate, int numTaps = 127)
            : numTaps_(numTaps | 1)   // force odd
    {
        coeffs_ = designLP(cutoffHz, sampleRate, numTaps_);
        if (type == Type::HighPass) spectralInvert(coeffs_);
        delay_.assign(numTaps_, 0.0f);
        head_ = 0;
    }

    // Band-pass: LP(highCut) − LP(lowCut)
    FirFilter(double lowCutHz, double highCutHz, int sampleRate, int numTaps = 127)
            : numTaps_(numTaps | 1)
    {
        auto lpHi = designLP(highCutHz, sampleRate, numTaps_);
        auto lpLo = designLP(lowCutHz,  sampleRate, numTaps_);
        coeffs_.resize(numTaps_);
        for (int i = 0; i < numTaps_; i++)
            coeffs_[i] = lpHi[i] - lpLo[i];
        delay_.assign(numTaps_, 0.0f);
        head_ = 0;
    }

    // Process numSamples int16_t samples in-place using circular buffer
    void process(int16_t* buf, int numSamples) {
        for (int i = 0; i < numSamples; i++) {
            // Write new sample into circular buffer at current head
            delay_[head_] = static_cast<float>(buf[i]);

            // Convolve: walk backwards from head_ through the delay line
            float acc = 0.0f;
            int   idx = head_;
            for (int j = 0; j < numTaps_; j++) {
                acc += coeffs_[j] * delay_[idx];
                if (--idx < 0) idx = numTaps_ - 1;
            }

            // Advance head (circular)
            if (++head_ >= numTaps_) head_ = 0;

            // Clamp and write back
            acc    = std::max(-32768.0f, std::min(32767.0f, acc));
            buf[i] = static_cast<int16_t>(acc);
        }
    }

    // Reset delay line — call on reconnect / track change
    void reset() {
        std::fill(delay_.begin(), delay_.end(), 0.0f);
        head_ = 0;
    }

private:
    static std::vector<float> designLP(double cutHz, int fs, int n) {
        double fc   = cutHz / static_cast<double>(fs);
        int    half = n / 2;
        std::vector<float> h(n);
        for (int i = 0; i < n; i++) {
            int    m = i - half;
            double sinc = (m == 0) ? 2.0 * fc
                                   : std::sin(2.0 * M_PI * fc * m) / (M_PI * m);
            // Blackman window
            double w = 0.42
                       - 0.5  * std::cos(2.0 * M_PI * i / (n - 1))
                       + 0.08 * std::cos(4.0 * M_PI * i / (n - 1));
            h[i] = static_cast<float>(sinc * w);
        }
        return h;
    }

    static void spectralInvert(std::vector<float>& h) {
        int half = static_cast<int>(h.size()) / 2;
        for (float& v : h) v = -v;
        h[half] += 1.0f;
    }

    int                numTaps_;
    int                head_ = 0;     // circular buffer write head
    std::vector<float> coeffs_;
    std::vector<float> delay_;
};