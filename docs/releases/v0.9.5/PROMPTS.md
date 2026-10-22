# v0.9.5 Implementation Prompts

## 017 — Unify fast-back timing (cover flight × route collapse)

**Selected interaction**: Shared-element cover flight aligned with the route
collapse transition, plus in-flight re-entry (redirect) semantics.

**Why this fits**: No catalogued pattern matches; this is a correction of two
independent timelines (450 ms flight vs 240 ms route exit) into one — the same
guarantee Flutter's Hero gives by construction ("redirected to its new
destination").

**Interaction behavior**: Cover flight duration reads `MotionTier.ROUTE`
(280 ms enter / 240 ms exit) × motion scale. When a new flight begins while one
is running, cancel the running job, clear flight/landing state, and restart in
the new direction. Return-flight easing stays `FastOutSlowIn` (decelerated
landing, accepted in 016); page collapse keeps its accelerating exit.

**Copyable prompt**:

```text
In the Compose app, make the shared-element cover flight use the same motion
duration tier as route transitions: enter 280 ms, exit 240 ms, multiplied by
the app's MotionScale; do not change the ROUTE tier values themselves. In the
cover-flight state's begin() function, if a flight animation job is currently
running, cancel that job, clear the flight and landing state, and start the new
flight from the new source rectangle; the overlay must never freeze mid-flight.
Keep the return-flight easing as FastOutSlowIn and keep page collapse as-is.
Do not modify any unrelated components, do not animate layout-affecting
properties, and keep reduced-motion (scale 0) behavior as snap-to-end.
```

## 018 — Skeleton loading with shimmer

**Selected interaction**: Skeleton placeholders with a shimmer sweep (Kazumi-style,
self-built lightweight implementation).

**Why this fits**: No catalogued pattern matches — judged "no matching pattern,
ordinary implementation". Shimmer is pure draw-layer motion, satisfying the
layout-property guardrail.

**Interaction behavior**: Rounded gray block, linear-gradient highlight sweeping
every 1200 ms (linear easing), multiplied by motion scale; scale 0 renders a
static block. List rows fade in with SMALL tier (200 ms) and a 40 ms stagger
capped at 240 ms. Skeletons appear only while `items.isEmpty() && loading`;
real content replaces them in place without a crossfade.

**Copyable prompt**:

```text
In the Compose app, add a LyraSkeleton composable: a rounded-corner gray block
whose background is a linear gradient sweeping diagonally every 1200 ms with
linear easing, implemented only in the draw/graphicsLayer layer — never animate
layout-affecting properties. Multiply the duration by the app's MotionScale and
render a static gray block when motion is disabled. Provide three presets: a
card row (110 dp card shapes with two short text bars below), a two-column
grid, and a list row (44 dp square plus two text bars) that fades in over
200 ms with a 40 ms per-row stagger capped at 240 ms. Use them for the home
playlist row, the playlist-square grid, the leaderboard detail list (8 rows),
and the search screen (3 rows), showing skeletons only when the item list is
empty and loading; real content replaces them in place with no crossfade.
Do not add third-party skeleton libraries and do not modify unrelated screens.
```
