# Navigation Engine

The engine (`domain/navigation/NavigationEngine.kt`) is the navigation brain: provider- and
UI-independent, deterministic, and unit-testable on the JVM.

## Responsibilities

1. Receive continuous smoothed GPS fixes (`onLocationUpdate`).
2. Snap the rider to the active route geometry (`RouteProgressTracker`).
3. Compute progress: remaining distance, remaining duration, current step, next step.
4. Compute distance to the next maneuver (geometry suffix distances per step boundary).
5. Smooth bearing (exponential, shortest-arc) for camera/arrow stability.
6. Detect off-route deviation with hysteresis + sustained-deviation rules.
7. Trigger rerouting through `RoutingProvider` — one in-flight request, cooldown, stale
   cancellation.
8. Detect arrival and latch it (no reroutes after arrival).
9. Publish `NavigationState` and mirror `DisplayNavigationState` to `NavigationOutput` sinks.

## Off-route / rerouting logic

Configurable via `NavigationEngineConfig` (defaults are the spec's suggested values):

| Parameter                     | Default | Meaning                                   |
|-------------------------------|---------|-------------------------------------------|
| `offRouteEnterMeters`         | 40 m    | deviation to start an off-route candidate |
| `offRouteExitMeters`          | 25 m    | deviation to clear the off-route latch    |
| `arrivalRadiusMeters`         | 20 m    | destination arrival radius                |
| `maxAccuracyMeters`           | 50 m    | fixes worse than this never trigger reroute |
| `offRouteSustainMs`           | 4 s     | deviation must persist this long          |
| `minDeviationMovementMeters`  | 30 m    | rider must actually move while deviating  |
| `rerouteCooldownMs`           | 15 s    | minimum gap between reroute requests      |

A reroute fires only when **all** hold:

```
accuracy acceptable
AND deviation > enter threshold for ≥ sustain window
AND moved ≥ min movement while deviating (or deviation > 2× threshold)
AND cooldown expired
AND no reroute already in flight
```

Hysteresis: while latched off-route, returning to within `exit` threshold clears the latch;
values between exit and enter keep the current state (no flicker). Poor-accuracy fixes freeze
evaluation — a single noisy GPS point can never trigger a reroute.

When a reroute request succeeds: the new route **replaces** the active route, progress and
steps are recalculated, the off-route latch resets, `rerouting` returns to false, and the map
/UI update from the new `NavigationState`. On failure the old route is kept, `rerouting`
clears, and a `NavigationEvent.RerouteFailed(kind)` is surfaced for a human-readable message.

## Arrival

Arrival latches when distance to destination ≤ `arrivalRadius + min(accuracy, 30 m)`. Once
latched, off-route/reroute logic is disabled until navigation restarts — post-arrival GPS
jitter cannot kick off a new route.

## Step / maneuver computation

Each route step's maneuver location is mapped to the nearest geometry vertex. For the snapped
vertex index `i`:

- current step = step whose end boundary ≥ `i`
- distance to next maneuver = `suffixDistance[i] - suffixDistance[stepEnd]`
- remaining duration = sum of step durations from the current step

`RouteProgressTracker` searches a **local vertex window** around the previous snap (with a
global fallback) so long routes stay cheap per fix.

## Events

`NavigationEvent`: `ArrivedAtDestination`, `RerouteFailed(kind)`, `RouteRequestFailed(kind)`.
Errors are domain `AppErrorKind` values — never raw exceptions — so the UI can render clear
messages ("No internet connection…", "The routing service took too long…") without crashing.

## Tests

`NavigationEngineTest` covers: single noisy fix (no reroute), sustained deviation → exactly
one reroute + route replacement, poor accuracy never reroutes, single-flight gating, cooldown
blocking then allowing a later request, arrival latching, progress advancement, and
hysteresis band behavior. Time is injected (`nowMs`) so cooldown/sustain are deterministic.
