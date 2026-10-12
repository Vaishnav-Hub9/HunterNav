// Board-level configuration for the HunterNav display receiver.
//
// DISPLAY CONTROLLER + PINS ARE PLACEHOLDERS: the actual TFT controller and wiring have not
// been supplied yet. The defaults target a 240×240 ST7789 panel on an ESP32-S3 DevKitC
// (the initial resolution target), wired via platformio.ini build flags (TFT_eSPI). Once
// the real panel is identified, set the driver/pins in platformio.ini to match — nothing in
// src/ needs to change.
//
// Wi-Fi credentials: pass them at build time, never commit them:
//   pio run -e esp32s3 --build-flag '-DKWifiSsid=\"myssid\"' --build-flag '-DKWifiPassword=\"secret\"'
// or set them in a local (git-ignored) wifi_config.h placed next to this file:
//   #define kWifiSsid     "myssid"
//   #define kWifiPassword "secret"
// Without credentials the firmware starts a soft-AP ("HunterNav-Display") so the phone can
// connect directly at ws://192.168.4.1:81/display.

#pragma once

// The guidance-overlay prototype below takes hnv::NavMeta, so the wire contract must be
// visible here regardless of the includer's include order.
#include "frame_protocol.h"

#define HNV_BOARD_NAME "esp32-s3 + ST7789 240x240 (placeholder wiring)"

// ---------------------------------------------------------------- Wi-Fi

#if defined(__has_include)
#if __has_include("wifi_config.h")
#include "wifi_config.h"
#endif
#endif

#ifndef kWifiSsid
#define kWifiSsid ""
#endif
#ifndef kWifiPassword
#define kWifiPassword ""
#endif

#define kSoftApSsid "HunterNav-Display"
#define kSoftApPassword "hunt3rn4v"  // change once on the bench; printed on boot otherwise

// ---------------------------------------------------------------- display

#define kDisplayRotation 0      // panel rotation 0–3; match the mount orientation
#define kMetaOnlyFallbackMs 3000  // draw meta overlay if no image frame for this long

// ---------------------------------------------------------------- protocol

#define kWebSocketPort 81
// Largest payload the mailbox will accept. Matches the phone-side JpegEncoder cap (96 KB)
// plus protocol header slack; platformio.ini passes it as -D HNV_MAX_JPEG_BYTES so the two
// stay a single source of truth.
#ifndef kMaxJpegBytes
#ifdef HNV_MAX_JPEG_BYTES
#define kMaxJpegBytes (HNV_MAX_JPEG_BYTES)
#else
#define kMaxJpegBytes (100u * 1024u)
#endif
#endif

// Guidance overlay (used only in meta-only fallback mode; the JPEG frames already contain
// the full guidance UI rendered by the phone). Declared with an elaborated type specifier so
// it works even when this header is included before TFT_eSPI.h.
void drawMetaOverlay(class TFT_eSPI& tft, const hnv::NavMeta& meta);
