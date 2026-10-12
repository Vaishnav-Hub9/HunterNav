# HunterNav display receiver (ESP32-S3)

Firmware for the handlebar display. It runs a WebSocket **server**, waits for the phone's
HunterNav app to connect (`ws://<device-ip>:81/display`), and paints the newest navigation
frame the phone renders — real street map, route line, position pointer and guidance — onto
an SPI TFT. The phone does all navigation and rendering; this device is a thin, reliable
viewer.

```
Phone (HunterNav app)                     ESP32-S3 receiver
─────────────────────                     ─────────────────
NavigationDataProvider                    WebSocket server :81 /display
  → DisplayNavigationState                  ├─ HNV1 decode (validate, skip malformed)
  → FrameRenderer (240×240 map frames)      ├─ newest-frame mailbox (drop stale, 1 deep)
  → JpegEncoder (≤96 KB)                    ├─ JPEGDEC → RGB565 → TFT_eSPI
  → WebSocket binary "HNV1" frames   ────▶  └─ meta overlay fallback (~10 Hz guidance)
```

The wire protocol is the single contract between
[`app/src/main/java/com/hunternav/display/transport/FrameProtocol.kt`](../app/src/main/java/com/hunternav/display/transport/FrameProtocol.kt)
and [`include/frame_protocol.h`](include/frame_protocol.h). Change both together — the shared
test vectors in [`test/protocol_test.cpp`](test/protocol_test.cpp) and the Android
`FrameProtocolTest` keep them honest.

## What you must configure (hardware not yet identified)

The display controller, SPI pins and backlight pin are **placeholders** targeting a 240×240
ST7789 panel on an ESP32-S3 DevKitC. Once the actual board is known, edit the build flags in
[`platformio.ini`](platformio.ini) (TFT_eSPI driver + pins) and, if needed,
[`include/board_config.h`](include/board_config.h) (rotation, fallback timings). No changes
are needed in `src/` — the receiver logic is panel-agnostic.

Wi-Fi credentials are build-time only and never committed:

```bash
# Option A: build flags
pio run -e esp32s3 --build-flag '-DKWifiSsid="myssid"' --build-flag '-DKWifiPassword="secret"'

# Option B: local wifi_config.h next to board_config.h (already listed in .gitignore —
# it must never be committed)
```

With no credentials the firmware starts a soft-AP `HunterNav-Display` (password
`hunt3rn4v` — change it) and the phone connects to `ws://192.168.4.1:81/display`, which is
the app's default `display.wsUrl`.

## Build & flash

```bash
cd firmware
pio run -e esp32s3            # build
pio run -e esp32s3 -t upload  # flash over USB
pio device monitor            # 115200 baud diagnostics
```

Toolchain: [PlatformIO Core](https://platformio.org/) (espressif32 platform, Arduino
framework). The receiver has no hard dependency on PSRAM — the 100 KB frame buffer falls
back to internal SRAM if PSRAM is absent.

## Runtime behavior

- **Newest frame wins.** The mailbox holds exactly one pending JPEG; a newer frame replaces
  an older still-pending one (the old one counted as `dropped`), and a frame arriving while
  a decode is running is dropped too — nothing is ever queued behind stale frames.
- **Malformed input never crashes.** Bad magic/version/length ⇒ message counted and skipped;
  a corrupt JPEG body keeps the last good picture on screen.
- **Meta-only fallback.** Guidance metadata (~10 Hz) is always tracked. If image frames stop
  for 3 s (renderer starved / link degraded), a compact overlay shows turn arrow, distance,
  remaining km and ETA from the metadata alone; the next image frame replaces it.
- **Diagnostics.** A 1 Hz serial line reports `rendered / dropped / malformed / meta / gaps`
  so bench testing shows link quality immediately.

## Verified / not verified

- `pio run -e esp32s3` compiles and links cleanly against the real ESP32-S3 toolchain
  (RAM 19.6%, Flash 23.7% at the time of writing).
- `test/protocol_test.cpp` compiles on the host (`g++ -std=c++17 -I include test/protocol_test.cpp`)
  and asserts the C++ decoder against the same byte vectors the Android `FrameProtocolTest`
  uses — protocol parity is machine-checked without hardware.
- **Not verified without hardware:** actual Wi-Fi throughput at 4 FPS, JPEG decode time on
  the ESP32-S3, TFT wiring/pins (still placeholder — see above), and behavior with the phone
  screen off (the app-side display foreground service exists precisely for that case but
  needs an on-bike test).
