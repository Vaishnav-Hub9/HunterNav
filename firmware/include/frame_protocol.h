// HNV1 wire protocol — C++ mirror of the Android contract in
// app/src/main/java/com/hunternav/display/transport/FrameProtocol.kt.
// Cross-checked against the Kotlin implementation by test/protocol_test.cpp
// (byte-for-byte vectors are shared between both test suites).
//
// One WebSocket binary message = one protocol message. Little-endian throughout:
//
//   offset size field            type
//   0      4    magic            u32  = 0x484E5631 ("HNV1")
//   4      2    version          u16  = 1
//   6      2    type             u16  (1 = IMAGE_FRAME, 2 = META_ONLY)
//   8      4    sequence         u32  (monotonic per connection)
//   12     2    width            u16  px
//   14     2    height           u16  px
//   16     1    navState         u8
//   17     1    maneuver         u8
//   18     2    flags            u16  (bit0 simulated, bit1 degradedTiles, bit2 headingValid)
//   20     4    distToManeuverM  i32  (-1 unknown)
//   24     4    remainingM       i32  (-1 unknown)
//   28     4    etaEpochSec      u32  (0 unknown)
//   32     4    payloadLen       u32  bytes following the header (JPEG for IMAGE_FRAME)
//   36     ..   payload
//
// A failed decode returns false; the receiver counts and skips, never crashes.

#pragma once

#include <cstdint>
#include <cstddef>

namespace hnv {

constexpr uint32_t kMagic = 0x484E5631u;  // "HNV1" little-endian
constexpr uint16_t kVersion = 1;
constexpr size_t kHeaderSize = 36;
constexpr uint16_t kTypeImageFrame = 1;
constexpr uint16_t kTypeMetaOnly = 2;

// navState codes (mirror domain NavPhase)
enum NavState : uint8_t {
  kNavIdle = 0,
  kNavNavigating = 1,
  kNavRerouting = 2,
  kNavArrived = 3,
  kNavError = 4,
};

// flags
constexpr uint16_t kFlagSimulated = 1u << 0;
constexpr uint16_t kFlagDegradedTiles = 1u << 1;
constexpr uint16_t kFlagHeadingValid = 1u << 2;

struct NavMeta {
  uint8_t navState = kNavIdle;
  uint8_t maneuver = 0;
  uint16_t flags = 0;
  int32_t distanceToManeuverM = -1;
  int32_t remainingM = -1;
  uint32_t etaEpochSec = 0;
};

struct Message {
  uint16_t type = 0;
  uint32_t sequence = 0;
  uint16_t width = 0;
  uint16_t height = 0;
  NavMeta meta;
  const uint8_t* payload = nullptr;  ///< points into the caller's buffer; never freed here
  uint32_t payloadLen = 0;
};

namespace detail {
inline uint16_t rd16(const uint8_t* p) {
  return static_cast<uint16_t>(p[0] | (p[1] << 8));
}
inline uint32_t rd32(const uint8_t* p) {
  return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) |
         (static_cast<uint32_t>(p[2]) << 16) | (static_cast<uint32_t>(p[3]) << 24);
}
inline int32_t rd32s(const uint8_t* p) { return static_cast<int32_t>(rd32(p)); }
}  // namespace detail

/// Decodes one protocol message from [len] bytes. Returns false on ANY malformation
/// (short buffer, wrong magic/version/type, payload length mismatch) so the caller can
/// count and skip without crashing — the contract the Android encoder guarantees.
inline bool decode(const uint8_t* data, size_t len, Message& out) {
  if (data == nullptr || len < kHeaderSize) return false;
  if (detail::rd32(data) != kMagic) return false;
  if (detail::rd16(data + 4) != kVersion) return false;

  const uint16_t type = detail::rd16(data + 6);
  if (type != kTypeImageFrame && type != kTypeMetaOnly) return false;

  out.type = type;
  out.sequence = detail::rd32(data + 8);
  out.width = detail::rd16(data + 12);
  out.height = detail::rd16(data + 14);
  out.meta.navState = data[16];
  out.meta.maneuver = data[17];
  out.meta.flags = detail::rd16(data + 18);
  out.meta.distanceToManeuverM = detail::rd32s(data + 20);
  out.meta.remainingM = detail::rd32s(data + 24);
  out.meta.etaEpochSec = detail::rd32(data + 28);

  const uint32_t payloadLen = detail::rd32(data + 32);
  if (payloadLen != len - kHeaderSize) return false;  // truncated or oversized message
  out.payloadLen = payloadLen;
  out.payload = payloadLen > 0 ? data + kHeaderSize : nullptr;
  return true;
}

/// Cheap JPEG sanity check: SOI marker at the start, EOI at the end.
inline bool looksLikeJpeg(const uint8_t* data, size_t len) {
  return data != nullptr && len > 3 && data[0] == 0xFF && data[1] == 0xD8 &&
         data[len - 2] == 0xFF && data[len - 1] == 0xD9;
}

}  // namespace hnv
