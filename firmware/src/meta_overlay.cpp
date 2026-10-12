// Guidance overlay for meta-only mode: when image frames stop arriving (e.g. the phone's
// renderer is starved) the display still shows the essentials from the ~10 Hz metadata
// stream — next-turn arrow, distance to the turn, remaining distance and ETA.

#include <TFT_eSPI.h>
#include <ctime>

#include "board_config.h"
#include "frame_protocol.h"

namespace {

// Compact arrow glyphs per wire maneuver code (see FrameProtocol MANEUVER_* constants).
const char* maneuverGlyph(uint8_t maneuver) {
  switch (maneuver) {
    case 1: return "^";   // depart
    case 2: return "|";   // straight
    case 3: return "\\";  // slight left
    case 4: return "/";   // slight right
    case 5: return "<";   // left
    case 6: return ">";   // right
    case 7: return "[";   // sharp left
    case 8: return "]";   // sharp right
    case 9: return "U";   // u-turn
    case 10: return "O";  // roundabout
    case 11: return "X";  // arrive
    default: return "?";
  }
}

const char* navStateText(uint8_t state) {
  switch (state) {
    case hnv::kNavNavigating: return "";
    case hnv::kNavRerouting: return "REROUTING";
    case hnv::kNavArrived: return "ARRIVED";
    case hnv::kNavError: return "GPS LOST";
    default: return "READY";
  }
}

}  // namespace

void drawMetaOverlay(TFT_eSPI& tft, const hnv::NavMeta& meta) {
  tft.fillScreen(TFT_BLACK);
  const int16_t w = tft.width();
  const int16_t h = tft.height();

  const char* state = navStateText(meta.navState);
  if (state[0] != '\0') {
    tft.setTextColor(TFT_YELLOW, TFT_BLACK);
    tft.setTextDatum(MC_DATUM);
    tft.drawString(state, w / 2, h / 2 - 10, 4);
  } else {
    // Maneuver + distance to turn (top half).
    tft.setTextColor(TFT_WHITE, TFT_BLACK);
    tft.setTextDatum(MC_DATUM);
    tft.drawString(maneuverGlyph(meta.maneuver), w / 2, h / 4, 6);
    if (meta.distanceToManeuverM >= 0) {
      char dist[16];
      if (meta.distanceToManeuverM >= 1000) {
        snprintf(dist, sizeof(dist), "%d.%d km", meta.distanceToManeuverM / 1000,
                 (meta.distanceToManeuverM % 1000) / 100);
      } else {
        snprintf(dist, sizeof(dist), "%d m", meta.distanceToManeuverM);
      }
      tft.drawString(dist, w / 2, h / 2 - 6, 4);
    }
    // Remaining distance + ETA clock (bottom).
    tft.setTextColor(TFT_CYAN, TFT_BLACK);
    char bottom[32];
    if (meta.remainingM >= 0) {
      char rem[16];
      if (meta.remainingM >= 1000) {
        snprintf(rem, sizeof(rem), "%d.%d km", meta.remainingM / 1000, (meta.remainingM % 1000) / 100);
      } else {
        snprintf(rem, sizeof(rem), "%d m", meta.remainingM);
      }
      if (meta.etaEpochSec > 0) {
        // ETA rendered in local time (device timezone is set by the phone's epoch value).
        time_t eta = static_cast<time_t>(meta.etaEpochSec);
        struct tm tmv;
        localtime_r(&eta, &tmv);
        snprintf(bottom, sizeof(bottom), "%s  %02d:%02d", rem, tmv.tm_hour, tmv.tm_min);
      } else {
        snprintf(bottom, sizeof(bottom), "%s", rem);
      }
      tft.drawString(bottom, w / 2, h - h / 6, 2);
    }
  }
}
