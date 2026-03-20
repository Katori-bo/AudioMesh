# AudioMesh

**Synchronized multi-room audio for Android — no internet, no cloud, no latency guessing.**

AudioMesh turns a group of Android phones into a synchronized speaker mesh over a local Wi-Fi hotspot. One phone acts as the **Sender** (plays and streams music), while any number of phones act as **Receivers** (play audio in sync). The result: a coherent, room-filling sound from devices you already own.

> Built entirely in Kotlin + Jetpack Compose on the UI side, with a C++17 native audio engine (AAudio + MediaCodec NDK) for sub-millisecond playback timing.

---

## Download

Grab the latest APK from the [Releases](https://github.com/Katori-bo/AudioMesh/releases) page.

### Install steps
1. Download `app-release.apk`
2. On your Android phone go to **Settings → Install unknown apps** → allow your browser
3. Open the downloaded APK and install

**Requirements:** Android 8.0 (API 26) or higher — all devices must be on the same Wi-Fi network.

---

## What it does

| Feature | Detail |
|--------------------------------|-------------------------------------------------------------------|
| **Synchronized playback**      | Custom NTP-style clock sync keeps all receivers within ±5 ms of the sender |
| **Frequency splitting**        | FULL / BASS / MID / TREBLE roles — each receiver plays only its band via a biquad crossover |
| **Auto-discovery**       | UDP beacon broadcast — receivers find the sender automatically on the same subnet |
| **Queue + skip**         | Build a play queue; Next / Prev skip tracks across all receivers simultaneously |
| **Local mode**           | Play music through the sender phone's own speaker without any receivers |
| **Palette-synced UI**    | Album art color extracted via Palette API; UI tints to match the current track |
| **Animated cassette UI** | Receiver screen shows a spinning cassette that reacts to playback state |
| **Live device list**     | Sender can see connected receivers with real-time ping RTTs |
| **AudioFocus aware**     | Pauses on phone calls and navigation prompts, resumes automatically |
| **Offline-first**        | Works entirely on a local hotspot — no internet required at all |

---

## How to use

### Sender (the phone with the music)
1. Open AudioMesh → tap any song → choose **SEND TO MESH** or **PLAY LOCALLY**
2. Select a role for this phone — FULL plays everything, or pick BASS / MID / TREBLE to split frequencies across devices
3. Tap **GO LIVE** — the sender starts broadcasting and accepts connections from receivers

### Receiver (any other phone on the same hotspot)
1. Open AudioMesh on the second phone
2. When the nearby sender banner appears at the bottom of the library, tap it to join
3. The animated cassette screen appears — the phone syncs within ~2 seconds and starts playing

### Roles explained
| Role | Frequency band |
|------  |-------------------------------------|
| FULL   | Unfiltered — plays the complete mix |
| BASS   | Below ~250 Hz                       |
| MID    | 250 Hz – 4 kHz                      |
| TREBLE | Above ~4 kHz                        |

---

## Architecture
```
┌──────────────────────────────────────────────────────┐
│                    Android App                       │
│                                                      │
│  Jetpack Compose UI  ←──→  NowPlayingViewModel       │
│        │                        │                    │
│  AppNavigation            SenderService              │
│  (NavHost)                ReceiverService            │
│                           (Foreground services)      │
│                                 │                    │
│                           NativeEngine (JNI)         │
└─────────────────────────────────┬────────────────────┘
                                  │ JNI
         ┌────────────────────────▼──────────────────┐
         │            C++ Native Layer               │
         │                                           │
         │  SenderEngine        ReceiverEngine       │
         │  ├── MP3/AAC decode   ├── Clock sync      │
         │  ├── TCP server       ├── Jitter buffer   │
         │  ├── UDP beacon       ├── AAudio output   │
         │  ├── IDEA-5 anchor    └── EMA drift fix   │
         │  └── Biquad EQ                            │
         └───────────────────────────────────────────┘
```

### The sync protocol (IDEA-5)

The hard problem: how do you make speakers separated by network jitter play the same sample at the same real-world millisecond?

1. **Clock sync** — receiver sends `PING:<t1_ns>`, sender replies `PONG:<t1_ns>:<t2_ns>`. Receiver computes `offset = t2 - t1 - rtt/2`. Done 30 times, trimmed median taken.
2. **Silence prebuffer** — receiver fills its AAudio pipeline with silence to prime the hardware before real audio arrives.
3. **Shared anchor** — sender waits for all receivers to report their pipeline-ready timestamp, then sets a single `sharedStartAtNs` at least 1.1s in the future so all chunks are in-flight before playback begins.
4. **Timestamped chunks** — every audio chunk carries a `speakerExitNs` timestamp. Receiver computes `playAtReceiverNs = speakerExitNs - clockOffset + sliderAdjust`.
5. **EMA drift correction** — receiver tracks timing drift with an exponential moving average and applies small nudges to correct long-term clock drift without audible glitches.

---

## Building from source

### Requirements
- Android Studio Hedgehog or later
- NDK r25c or later
- CMake 3.22+
- minSdk 26 (Android 8.0)

### Steps
```bash
git clone https://github.com/Katori-bo/AudioMesh.git
cd AudioMesh
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

### Permissions used
| Permission | Why |
|----------------------------------|-------------------------------------|
| `READ_MEDIA_AUDIO`               | Read music files from MediaStore |
| `RECORD_AUDIO`                   | Required by AAudio in shared mode |
| `FOREGROUND_SERVICE`             | Keep audio running when backgrounded |
| `ACCESS_WIFI_STATE`              | Detect hotspot IP for beacon |
| `CHANGE_WIFI_MULTICAST_STATE`    | UDP broadcast on Samsung devices |
| `POST_NOTIFICATIONS`             | Foreground service notification on API 33+ |

---

## Known limitations

- **Android only** — uses AAudio and MediaCodec NDK APIs
- **Same subnet required** — UDP broadcast discovery only works on the same Wi-Fi hotspot
- **MP3 and AAC only** — FLAC and WAV not yet supported
- **Mono streaming** — stereo is downmixed to mono on the sender for lowest latency
- **No DRM** — protected/downloaded streaming content cannot be opened

---

## Contributing

PRs welcome. Open an issue first for major changes. Good areas to contribute:

- FLAC / WAV decoder support
- Stereo streaming
- iOS receiver app (protocol is plain TCP/UDP — very portable)
- LAN support beyond hotspot subnets

---

## License

MIT — see [LICENSE](LICENSE).
