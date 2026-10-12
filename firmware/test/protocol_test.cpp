// HNV1 protocol parity test — host-compilable, no hardware needed.
//
//   g++ -std=c++17 -Wall -Wextra -I include test/protocol_test.cpp -o /tmp/hnv_test && /tmp/hnv_test
//
// The byte vectors below are the exact encodings the Android `FrameProtocol.encodeFrame` /
// `encodeMeta` produce for the same inputs (see app/src/test/.../FrameProtocolTest.kt — the
// Kotlin suite asserts the same field values round-trip). If either side's layout changes,
// one of the two suites fails, which is the point: this file and frame_protocol.h must be
// updated together with FrameProtocol.kt.

#include <cstdio>
#include <cstdlib>
#include <cstring>

#include "frame_protocol.h"

static int g_failures = 0;

#define CHECK(cond)                                                     \
  do {                                                                  \
    if (!(cond)) {                                                      \
      std::printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond);       \
      g_failures++;                                                     \
    }                                                                   \
  } while (0)

// FrameProtocolTest's shared JPEG fixture: SOI + 2 body bytes + EOI.
static const uint8_t kJpeg[] = {0xFF, 0xD8, 0x12, 0x34, 0xFF, 0xD9};

// encodeFrame(sequence=42, width=480, height=320,
//             meta(navState=NAVIGATING, maneuver=LEFT, flags=HEADING_VALID|SIMULATED,
//                  dist=320, remaining=12450, eta=1800000000), jpeg)
static const uint8_t kImageFrame[] = {
    0x31, 0x56, 0x4E, 0x48,        // magic "HNV1" (LE u32 0x484E5631)
    0x01, 0x00,                    // version 1
    0x01, 0x00,                    // type = IMAGE_FRAME
    0x2A, 0x00, 0x00, 0x00,        // sequence 42
    0xE0, 0x01,                    // width 480
    0x40, 0x01,                    // height 320
    0x01,                          // navState NAVIGATING
    0x05,                          // maneuver LEFT
    0x05, 0x00,                    // flags = simulated(1) | headingValid(4)
    0x40, 0x01, 0x00, 0x00,        // distToManeuverM 320
    0xA2, 0x30, 0x00, 0x00,        // remainingM 12450
    0x00, 0xD2, 0x49, 0x6B,        // etaEpochSec 1800000000
    0x06, 0x00, 0x00, 0x00,        // payloadLen 6
    0xFF, 0xD8, 0x12, 0x34, 0xFF, 0xD9,
};

// encodeMeta(sequence=7, width=480, height=320,
//            meta(navState=REROUTING, maneuver=ROUNDABOUT, dist=-1, remaining=-1, eta=0))
static const uint8_t kMetaOnly[] = {
    0x31, 0x56, 0x4E, 0x48,
    0x01, 0x00,
    0x02, 0x00,                    // type = META_ONLY
    0x07, 0x00, 0x00, 0x00,        // sequence 7
    0xE0, 0x01,                    // width 480
    0x40, 0x01,                    // height 320
    0x02,                          // navState REROUTING
    0x0A,                          // maneuver ROUNDABOUT
    0x00, 0x00,                    // flags 0
    0xFF, 0xFF, 0xFF, 0xFF,        // distToManeuverM -1
    0xFF, 0xFF, 0xFF, 0xFF,        // remainingM -1
    0x00, 0x00, 0x00, 0x00,        // etaEpochSec 0
    0x00, 0x00, 0x00, 0x00,        // payloadLen 0
};

static void testImageFrameRoundTrip() {
  hnv::Message msg;
  CHECK(hnv::decode(kImageFrame, sizeof(kImageFrame), msg));
  CHECK(msg.type == hnv::kTypeImageFrame);
  CHECK(msg.sequence == 42u);
  CHECK(msg.width == 480u);
  CHECK(msg.height == 320u);
  CHECK(msg.meta.navState == hnv::kNavNavigating);
  CHECK(msg.meta.maneuver == 5u);  // MANEUVER_LEFT
  CHECK(msg.meta.flags == (hnv::kFlagSimulated | hnv::kFlagHeadingValid));
  CHECK(msg.meta.distanceToManeuverM == 320);
  CHECK(msg.meta.remainingM == 12450);
  CHECK(msg.meta.etaEpochSec == 1800000000u);
  CHECK(msg.payloadLen == 6u);
  CHECK(msg.payload != nullptr);
  CHECK(std::memcmp(msg.payload, kJpeg, sizeof(kJpeg)) == 0);
  CHECK(hnv::looksLikeJpeg(msg.payload, msg.payloadLen));
}

static void testMetaOnlyHasNoPayload() {
  hnv::Message msg;
  CHECK(hnv::decode(kMetaOnly, sizeof(kMetaOnly), msg));
  CHECK(msg.type == hnv::kTypeMetaOnly);
  CHECK(msg.sequence == 7u);
  CHECK(msg.meta.navState == hnv::kNavRerouting);
  CHECK(msg.meta.maneuver == 10u);  // MANEUVER_ROUNDABOUT
  CHECK(msg.meta.distanceToManeuverM == -1);
  CHECK(msg.meta.remainingM == -1);
  CHECK(msg.meta.etaEpochSec == 0u);
  CHECK(msg.payloadLen == 0u);
  CHECK(msg.payload == nullptr);
}

static void testMalformedInputsRejected() {
  hnv::Message msg;
  CHECK(!hnv::decode(nullptr, sizeof(kMetaOnly), msg));
  CHECK(!hnv::decode(kMetaOnly, 0, msg));
  CHECK(!hnv::decode(kMetaOnly, hnv::kHeaderSize - 1, msg));

  // Right size, wrong magic.
  uint8_t wrongMagic[sizeof(kMetaOnly)];
  std::memcpy(wrongMagic, kMetaOnly, sizeof(kMetaOnly));
  wrongMagic[0] = 0x00;
  CHECK(!hnv::decode(wrongMagic, sizeof(wrongMagic), msg));

  // Wrong version.
  uint8_t wrongVersion[sizeof(kMetaOnly)];
  std::memcpy(wrongVersion, kMetaOnly, sizeof(kMetaOnly));
  wrongVersion[4] = 99;
  CHECK(!hnv::decode(wrongVersion, sizeof(wrongVersion), msg));

  // Unknown type.
  uint8_t wrongType[sizeof(kMetaOnly)];
  std::memcpy(wrongType, kMetaOnly, sizeof(kMetaOnly));
  wrongType[6] = 77;
  CHECK(!hnv::decode(wrongType, sizeof(wrongType), msg));

  // Declared payload longer than the actual bytes (truncated frame).
  CHECK(!hnv::decode(kImageFrame, sizeof(kImageFrame) - 3, msg));

  // The intact frame must still decode after the mutations above.
  CHECK(hnv::decode(kImageFrame, sizeof(kImageFrame), msg));

  // Declared payload shorter than the actual bytes (trailing garbage).
  uint8_t oversized[sizeof(kImageFrame) + 2] = {0};
  std::memcpy(oversized, kImageFrame, sizeof(kImageFrame));
  // payloadLen still says 6 but 8 bytes follow ⇒ must be rejected.
  CHECK(!hnv::decode(oversized, sizeof(oversized), msg));
}

static void testJpegSanity() {
  CHECK(hnv::looksLikeJpeg(kJpeg, sizeof(kJpeg)));
  const uint8_t notJpeg[] = {0xFF, 0xD8, 1, 2, 3, 4};  // SOI but no EOI
  CHECK(!hnv::looksLikeJpeg(notJpeg, sizeof(notJpeg)));
  CHECK(!hnv::looksLikeJpeg(nullptr, 4));
}

int main() {
  testImageFrameRoundTrip();
  testMetaOnlyHasNoPayload();
  testMalformedInputsRejected();
  testJpegSanity();
  if (g_failures == 0) {
    std::printf("OK — all HNV1 C++ decoder checks passed\n");
    return 0;
  }
  std::printf("%d failure(s)\n", g_failures);
  return 1;
}
