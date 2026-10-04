# Future Hardware: ESP32 Display

**Status: preparation only. Nothing here is implemented in V1.**

HunterNav is designed so the navigation state can later be transmitted to an external
handlebar display (ESP32 + small TFT/LCD) without rewriting the navigation system.

## Target architecture

```
NavigationEngine
      │  publishes DisplayNavigationState
      ▼
NavigationOutput            ← interface (exists today)
      │
      └── BluetoothNavigationOutput   ← future: serializes state over BLE/Classic
                │
                ▼
            ESP32 device
                │
                ▼
        small TFT/LCD handlebar display
```

## What exists today

`domain/repository/NavigationOutput.kt`:

```kotlin
fun interface NavigationOutput {
    fun onNavigationStateChanged(state: DisplayNavigationState)
}
```

`DisplayNavigationState` is a plain, Android-free model:

- `latitude`, `longitude`, `bearing`, `speedMps`
- `maneuver` (normalized `ManeuverType`), `distanceToManeuverMeters`, `roadName`
- `remainingDistanceMeters`, `remainingDurationSeconds`
- `routeGeometry` (list of coordinates)
- `offRoute`, `rerouting`, `destinationReached`

The engine mirrors every state update to all registered outputs (failures in one sink never
affect navigation).

## What the future `BluetoothNavigationOutput` will do

1. Maintain a BLE GATT server (or Classic SPP) connection to the ESP32.
2. Serialize `DisplayNavigationState` compactly — e.g. a small binary frame or protobuf/flat
   struct (a few dozen bytes per update, throttled to ~1–2 Hz plus immediate maneuver-change
   pushes).
3. Fall back to store-and-forward when disconnected; never block navigation on link state.

## ESP32 side (planned)

- ESP32-S3 with a 2–4" TFT, driven by LVGL or plain TFT_eSPI.
- Renders: next maneuver glyph, distance to maneuver, remaining distance, ETA, off-route /
  rerouting / arrived indicators — mirroring the phone UI's information hierarchy.
- The phone remains the navigation brain; the display is a dumb terminal.

## Explicitly out of scope for V1

No Bluetooth permissions, no BLE code, no serial protocol, no ESP32 firmware is included —
only the interface and data model that make it possible later. See the project README's
"Future ESP32 integration" section.
