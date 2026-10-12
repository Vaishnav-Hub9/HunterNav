// HunterNav handlebar display receiver — ESP32-S3.
//
// Receives HNV1 protocol messages over WebSocket from the phone (which runs the whole
// navigation stack) and paints the newest JPEG frame plus optional guidance metadata onto
// the SPI TFT. Design rules:
//   - newest frame wins: a slow JPEG decode never queues behind stale frames;
//   - malformed messages are counted and skipped, never fatal;
//   - Wi-Fi: joins the configured network when credentials are set, otherwise falls back
//     to a soft-AP so the phone can connect directly (ws://192.168.4.1:81/display).
//
// Board wiring (display controller, pins, BL) is configured in platformio.ini /
// include/board_config.h — fill them in once the actual panel is identified.

#include <Arduino.h>
#include <WebSocketsServer.h>
#include <JPEGDEC.h>
#include <TFT_eSPI.h>
#include <WiFi.h>
#include <freertos/FreeRTOS.h>
#include <freertos/semphr.h>
#include <freertos/task.h>

#include "board_config.h"
#include "frame_protocol.h"

// ---------------------------------------------------------------- state

static TFT_eSPI tft;
static JPEGDEC jpeg;
static WebSocketsServer webSocket(kWebSocketPort);

// Newest-frame mailbox: the WebSocket task overwrites, the render task consumes.
// Bounded to exactly one pending frame — obsolete frames are dropped by construction.
static uint8_t* g_jpegBuf = nullptr;
static size_t g_jpegLen = 0;
static uint16_t g_frameW = 0;
static uint16_t g_frameH = 0;
static hnv::NavMeta g_meta;  // latest guidance metadata (from IMAGE or META messages)
static SemaphoreHandle_t g_frameMutex;

// Diagnostics counters (reported over serial once per second).
static uint32_t g_framesRendered = 0;
static uint32_t g_framesDropped = 0;   // arrived while a decode was still running
static uint32_t g_malformed = 0;       // failed protocol/JPEG validation
static uint32_t g_metaOnly = 0;
static uint32_t g_seqGaps = 0;
static uint32_t g_lastSeq = 0;
static bool g_haveSeq = false;
static bool g_haveFrame = false;
static uint32_t g_lastFrameMs = 0;

// ---------------------------------------------------------------- JPEG → TFT

// JPEGDEC draw callback: one MCU row block at a time, pushed straight to the TFT.
static int onJpegDraw(JPEGDRAW* draw) {
  tft.pushImage(draw->x, draw->y, draw->iWidth, draw->iHeight, draw->pPixels);
  return 1;  // continue decoding
}

static bool renderJpeg(const uint8_t* jpegBytes, size_t len, uint16_t w, uint16_t h) {
  if (jpeg.openRAM(const_cast<uint8_t*>(jpegBytes), static_cast<int32_t>(len), onJpegDraw) != JPEG_SUCCESS) {
    return false;
  }
  // Decode at the panel's native orientation; the phone already renders exactly the
  // configured display resolution, so scale factor 0 = 1:1.
  // Explicit little-endian RGB565: the native uint16_t layout TFT_eSPI::pushImage consumes
  // on the ESP32 (JPEGDEC's default — stated here so a future library upgrade can't silently
  // flip the byte order and render garbage colors).
  jpeg.setPixelType(RGB565_LITTLE_ENDIAN);
  const bool ok = jpeg.decode(0, 0, 0) == JPEG_SUCCESS;
  jpeg.close();
  (void)w;
  (void)h;
  return ok;
}

// ---------------------------------------------------------------- render task

static void renderTask(void*) {
  hnv::NavMeta lastDrawnMeta;
  for (;;) {
    if (xSemaphoreTake(g_frameMutex, pdMS_TO_TICKS(50)) == pdTRUE) {
      if (g_jpegLen == 0) {
        xSemaphoreGive(g_frameMutex);
      } else if (!hnv::looksLikeJpeg(g_jpegBuf, g_jpegLen)) {
        g_malformed++;
        g_jpegLen = 0;  // never let a non-JPEG payload clog the mailbox
        xSemaphoreGive(g_frameMutex);
      } else {
        // Decode while STILL HOLDING the lock: the socket task acquires this mutex with a
        // 0-tick timeout, so it can neither tear the buffer mid-decode nor queue behind a
        // slow decode — frames arriving here simply fail the take and count as dropped,
        // which is exactly the newest-frame-wins rule.
        const size_t len = g_jpegLen;
        const uint16_t fw = g_frameW;
        const uint16_t fh = g_frameH;
        const uint32_t t0 = millis();
        const bool ok = renderJpeg(g_jpegBuf, len, fw, fh);
        g_jpegLen = 0;  // consumed either way; a corrupt frame keeps the last good picture
        xSemaphoreGive(g_frameMutex);
        if (ok) {
          g_framesRendered++;
          g_lastFrameMs = millis();
          Serial.printf("[frame] %ux%u %u bytes %lu ms\n", fw, fh,
                        static_cast<unsigned>(len),
                        static_cast<unsigned long>(millis() - t0));
        } else {
          g_malformed++;  // corrupt JPEG body — keep the last good picture
        }
      }
    }

    // Guidance metadata can arrive without an image (META_ONLY at ~10 Hz). The JPEG already
    // contains the guidance bands, so the overlay is only drawn between frames when the
    // phone sends meta-only updates and no picture has arrived for a while (degraded mode).
    if (!g_haveFrame || millis() - g_lastFrameMs > kMetaOnlyFallbackMs) {
      if (memcmp(&lastDrawnMeta, &g_meta, sizeof(hnv::NavMeta)) != 0) {
        lastDrawnMeta = g_meta;
        drawMetaOverlay(tft, g_meta);
      }
    }
    vTaskDelay(pdMS_TO_TICKS(30));
  }
}

// ---------------------------------------------------------------- WebSocket

// Decodes and dispatches one protocol message.
static void onWsEvent(uint8_t num, WStype_t type, uint8_t* payload, size_t len) {
  switch (type) {
    case WStype_CONNECTED:
      Serial.printf("[ws] client %u connected\n", num);
      g_haveSeq = false;  // sequences are per-connection by protocol contract
      break;
    case WStype_DISCONNECTED:
      Serial.printf("[ws] client %u disconnected\n", num);
      break;
    case WStype_BIN: {
      hnv::Message msg;
      if (!hnv::decode(payload, len, msg)) {
        g_malformed++;
        return;
      }
      // Sequence-gap diagnostics (per connection; receiver uses gaps only as a signal).
      if (g_haveSeq && msg.sequence > g_lastSeq + 1) g_seqGaps++;
      g_lastSeq = msg.sequence;
      g_haveSeq = true;

      g_meta = msg.meta;

      if (msg.type == hnv::kTypeMetaOnly) {
        g_metaOnly++;
        return;
      }
      // IMAGE_FRAME: validate, then publish to the mailbox (newest wins, drop if busy).
      // The size bound is load-bearing: g_jpegBuf is a fixed kMaxJpegBytes allocation and
      // an over-long payload must never reach memcpy.
      if (msg.payloadLen == 0 || msg.payloadLen > kMaxJpegBytes ||
          !hnv::looksLikeJpeg(msg.payload, msg.payloadLen)) {
        g_malformed++;
        return;
      }
      if (xSemaphoreTake(g_frameMutex, 0) == pdTRUE) {
        if (g_jpegLen > 0) g_framesDropped++;  // previous frame never rendered
        memcpy(g_jpegBuf, msg.payload, msg.payloadLen);
        g_jpegLen = msg.payloadLen;
        g_frameW = msg.width;
        g_frameH = msg.height;
        g_haveFrame = true;
        xSemaphoreGive(g_frameMutex);
      } else {
        g_framesDropped++;
      }
      break;
    }
    default:
      break;
  }
}

// ---------------------------------------------------------------- Wi-Fi

static void startWifi() {
  if (strlen(kWifiSsid) > 0) {
    WiFi.mode(WIFI_STA);
    WiFi.begin(kWifiSsid, kWifiPassword);
    Serial.printf("[wifi] joining \"%s\"", kWifiSsid);
    for (int i = 0; i < 60 && WiFi.status() != WL_CONNECTED; i++) {
      delay(500);
      Serial.print('.');
    }
    Serial.println();
    if (WiFi.status() == WL_CONNECTED) {
      Serial.printf("[wifi] connected, display endpoint ws://%s:%u/display\n",
                    WiFi.localIP().toString().c_str(), kWebSocketPort);
      return;
    }
    Serial.println("[wifi] join failed, falling back to soft-AP");
  }
  // Soft-AP fallback: the phone connects to the display directly (default wsUrl in the app
  // already points at 192.168.4.1:81/display).
  WiFi.mode(WIFI_AP);
  WiFi.softAP(kSoftApSsid, kSoftApPassword);
  Serial.printf("[wifi] soft-AP \"%s\", display endpoint ws://%s:%u/display\n",
                kSoftApSsid, WiFi.softAPIP().toString().c_str(), kWebSocketPort);
}

// ---------------------------------------------------------------- setup/loop

void setup() {
  Serial.begin(115200);
  Serial.println("\nHunterNav display receiver (" HNV_BOARD_NAME ")");

  tft.init();
  tft.setRotation(kDisplayRotation);
  tft.fillScreen(TFT_BLACK);
  tft.setTextDatum(MC_DATUM);
  tft.drawString("Waiting for phone…", tft.width() / 2, tft.height() / 2, 2);

  g_frameMutex = xSemaphoreCreateMutex();
  // Max JPEG payload the phone will ever send (JpegEncoder caps at 96 KB + header slack).
  g_jpegBuf = static_cast<uint8_t*>(heap_caps_malloc(kMaxJpegBytes, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT));
  if (g_jpegBuf == nullptr) {
    g_jpegBuf = static_cast<uint8_t*>(malloc(kMaxJpegBytes));  // fall back to internal RAM
  }
  if (g_jpegBuf == nullptr) {
    Serial.println("[fatal] cannot allocate frame buffer, halting");
    while (true) delay(1000);
  }

  startWifi();
  webSocket.begin();
  webSocket.onEvent(onWsEvent);
  // Large binary frames arrive fragmented; allow generous payload + client count.
  webSocket.setAuthorization(nullptr, nullptr);
  xTaskCreatePinnedToCore(renderTask, "hnv-render", 8192, nullptr, 1, nullptr, 1);
}

void loop() {
  webSocket.loop();
  // 1 Hz diagnostics line.
  static uint32_t lastDiag = 0;
  const uint32_t now = millis();
  if (now - lastDiag >= 1000) {
    lastDiag = now;
    Serial.printf("[diag] rendered=%lu dropped=%lu malformed=%lu meta=%lu gaps=%lu clients=%u\n",
                  static_cast<unsigned long>(g_framesRendered),
                  static_cast<unsigned long>(g_framesDropped),
                  static_cast<unsigned long>(g_malformed),
                  static_cast<unsigned long>(g_metaOnly),
                  static_cast<unsigned long>(g_seqGaps),
                  webSocket.connectedClients());
  }
  delay(2);  // feed the watchdog without busy-spinning
}
