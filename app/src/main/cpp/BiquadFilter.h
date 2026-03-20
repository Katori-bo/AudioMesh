#pragma once
// ─────────────────────────────────────────────────────────────────────────────
//  BiquadFilter.h  —  header-only 2-band biquad IIR EQ
//
//  Two biquad sections chained:
//    Section 0: Parametric peak/cut  (peakHz, peakDb, peakQ)
//    Section 1: High shelf boost/cut (shelfHz, shelfDb)
//
//  Flat settings (all dB = 0) should never reach here — the streamingLoop
//  skips processing when s->eq == nullptr.
//
//  Input/output: int16_t PCM, mono, in-place.
// ─────────────────────────────────────────────────────────────────────────────

#include <cstdint>
#include <cmath>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

class BiquadFilter {
public:
    // Construct with both bands.  sampleRate in Hz.
    BiquadFilter(float peakHz,  float peakDb,  float peakQ,
                 float shelfHz, float shelfDb, int   sampleRate)
    {
        computePeak (peakHz,  peakDb, peakQ,  sampleRate, b0_p, b1_p, b2_p, a1_p, a2_p);
        computeShelf(shelfHz, shelfDb,         sampleRate, b0_s, b1_s, b2_s, a1_s, a2_s);
        reset();
    }

    // Process numSamples int16_t samples in-place.
    void process(int16_t* buf, int numSamples) {
        for (int i = 0; i < numSamples; i++) {
            float x = static_cast<float>(buf[i]);

            // Section 0: peak
            float y0 = b0_p * x + b1_p * x1_p + b2_p * x2_p
                       - a1_p * y1_p - a2_p * y2_p;
            x2_p = x1_p; x1_p = x;
            y2_p = y1_p; y1_p = y0;

            // Section 1: shelf
            float y1 = b0_s * y0 + b1_s * x1_s + b2_s * x2_s
                       - a1_s * y1_s - a2_s * y2_s;
            x2_s = x1_s; x1_s = y0;
            y2_s = y1_s; y1_s = y1;

            // Clamp and write back
            if      (y1 >  32767.0f) y1 =  32767.0f;
            else if (y1 < -32768.0f) y1 = -32768.0f;
            buf[i] = static_cast<int16_t>(y1);
        }
    }

    void reset() {
        x1_p = x2_p = y1_p = y2_p = 0.0f;
        x1_s = x2_s = y1_s = y2_s = 0.0f;
    }

private:
    // ── Biquad coefficients ───────────────────────────────────────────────────
    float b0_p, b1_p, b2_p, a1_p, a2_p; // peak section
    float b0_s, b1_s, b2_s, a1_s, a2_s; // shelf section

    // ── Delay state ───────────────────────────────────────────────────────────
    float x1_p, x2_p, y1_p, y2_p;       // peak section state
    float x1_s, x2_s, y1_s, y2_s;       // shelf section state

    // ── Parametric peak (Audio EQ Cookbook — peakingEQ) ──────────────────────
    static void computePeak(float Hz, float dB, float Q, int fs,
                            float& b0, float& b1, float& b2,
                            float& a1, float& a2) {
        float A  = std::pow(10.0f, dB / 40.0f);   // sqrt(10^(dB/20))
        float w0 = 2.0f * (float)M_PI * Hz / (float)fs;
        float alpha = std::sin(w0) / (2.0f * Q);

        float _b0 =  1.0f + alpha * A;
        float _b1 = -2.0f * std::cos(w0);
        float _b2 =  1.0f - alpha * A;
        float _a0 =  1.0f + alpha / A;
        float _a1 = -2.0f * std::cos(w0);
        float _a2 =  1.0f - alpha / A;

        b0 = _b0 / _a0;
        b1 = _b1 / _a0;
        b2 = _b2 / _a0;
        a1 = _a1 / _a0;
        a2 = _a2 / _a0;
    }

    // ── High shelf (Audio EQ Cookbook — highShelf) ────────────────────────────
    static void computeShelf(float Hz, float dB, int fs,
                             float& b0, float& b1, float& b2,
                             float& a1, float& a2) {
        float A  = std::pow(10.0f, dB / 40.0f);
        float w0 = 2.0f * (float)M_PI * Hz / (float)fs;
        float cosw = std::cos(w0);
        float sinw = std::sin(w0);
        float alpha = sinw / 2.0f * std::sqrt((A + 1.0f / A) * (1.0f / 1.0f - 1.0f) + 2.0f);
        // S=1 shelf slope form:
        alpha = sinw / 2.0f * std::sqrt((A + 1.0f / A) * (1.0f / 1.0f - 1.0f) + 2.0f);
        // Use the standard S=1 formula directly:
        float sqA = std::sqrt(A);
        alpha = sinw / 2.0f * std::sqrt( (A + 1.0f/A)*(1.0f - 1.0f) + 2.0f );
        // Simplified S=1: alpha = sin(w0)/2 * sqrt(2)
        alpha = sinw * 0.7071068f; // sin(w0) / sqrt(2)

        float _b0 =       A*((A+1.0f) + (A-1.0f)*cosw + 2.0f*sqA*alpha);
        float _b1 = -2.0f*A*((A-1.0f) + (A+1.0f)*cosw                  );
        float _b2 =       A*((A+1.0f) + (A-1.0f)*cosw - 2.0f*sqA*alpha);
        float _a0 =          (A+1.0f) - (A-1.0f)*cosw + 2.0f*sqA*alpha;
        float _a1 =  2.0f*  ((A-1.0f) - (A+1.0f)*cosw                  );
        float _a2 =          (A+1.0f) - (A-1.0f)*cosw - 2.0f*sqA*alpha;

        b0 = _b0 / _a0;
        b1 = _b1 / _a0;
        b2 = _b2 / _a0;
        a1 = _a1 / _a0;
        a2 = _a2 / _a0;
    }
};