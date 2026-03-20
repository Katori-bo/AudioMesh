#pragma once

#include <cstdint>   // int16_t, int64_t — must be first

// ─────────────────────────────────────────────────────────────────────────────
//  Protocol.h  — shared constants for AudioMesh
//
//  Handshake sequence (receiver initiates after TCP connect):
//
//  R → S:  "ROLE:bass\n"          (or "mid" / "treble" / "full")
//  S → R:  "SAMPLERATE:44100\n"
//  [10 rounds of clock sync]
//  R → S:  "PING:<t1_ns>\n"
//  S → R:  "PONG:<t1_ns>:<t2_ns>\n"
//  R → S:  "READY\n"
//  S → R:  "START:<sender_ns>\n"
//  [binary audio stream]
//
//  Binary packet format:
//    [8 bytes] playAtNs  — big-endian int64, sender clock (CLOCK_MONOTONIC)
//    [4 bytes] dataLen   — big-endian int32, byte count of PCM payload
//    [dataLen] PCM data  — int16_t, mono, little-endian
// ─────────────────────────────────────────────────────────────────────────────

// ── Network ──────────────────────────────────────────────────────────────────
static constexpr int     MESH_TCP_PORT       = 5100;
static constexpr int     MESH_UDP_PORT       = 5101;   // discovery beacon
static constexpr int     MESH_UDP_RECV_PORT  = 5102;   // receivers listen here

// ── Discovery beacon ─────────────────────────────────────────────────────────
static constexpr int     BEACON_INTERVAL_MS  = 2000;
static constexpr char    BEACON_PREFIX[]     = "AUDIOMESH:";

// ── Audio ────────────────────────────────────────────────────────────────────
static constexpr int     DEFAULT_SAMPLE_RATE = 44100;
// FIX: Reduced from 4096 (92.9ms) → 1024 (23.2ms) per chunk.
// WiFi TCP bursts arrive ~13ms late in alternating groups. With 4096-frame
// chunks, a late burst infects an entire 92.9ms unit of audio, causing a
// step-change in sync every ~9 seconds that is audible as flamming/doubling.
// At 1024 frames, the same 13ms burst only spans 1-2 chunks (23-46ms total),
// reducing the audible artifact by 4× and giving the PLL 4× more correction
// opportunities per second.
// MAX_JITTER_CHUNKS raised proportionally so the 500ms pre-roll still fits.
static constexpr int     CHUNK_FRAMES        = 1024;
static constexpr int     CHUNK_BYTES         = CHUNK_FRAMES * (int)sizeof(int16_t);
static constexpr int     HEADER_BYTES        = 12;     // 8 playAt + 4 len

// ── Jitter buffer ────────────────────────────────────────────────────────────
// Raised from 150 → 600 to keep ~500ms of pre-roll at the smaller chunk size.
// (150 * 92.9ms = 13.9s  →  600 * 23.2ms = 13.9s — same total buffer depth)
static constexpr int     MAX_JITTER_CHUNKS   = 600;
static constexpr int     POOL_SIZE           = MAX_JITTER_CHUNKS + 64;
static constexpr int64_t JITTER_BUFFER_NS    = 500'000'000LL;

// ── Clock sync ───────────────────────────────────────────────────────────────
static constexpr int     SYNC_ROUNDS         = 30;
static constexpr int64_t MAX_WAIT_NS         = 5'000'000'000LL;

// ── Fade ─────────────────────────────────────────────────────────────────────
static constexpr int     FADE_IN_SAMPLES     = 2205;   // 50 ms @ 44100 Hz

// ── Roles (wire format strings) ───────────────────────────────────────────────
static constexpr char ROLE_FULL[]   = "full";
static constexpr char ROLE_BASS[]   = "bass";
static constexpr char ROLE_MID[]    = "mid";
static constexpr char ROLE_TREBLE[] = "treble";