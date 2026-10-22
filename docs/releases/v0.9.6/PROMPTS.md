# v0.9.6 实现提示词（英文，四段式）

## 019 快速返回时序闭环

**Selected interaction**: Returning from any cover-bearing detail page (mini
player→player, card→detail) while the page-transition is still running.

**Why this fits**: Not one of the ten catalogued patterns — this is a bug fix in
the shared-element handoff contract (ADR-0010). Flutter Hero couples hero flight
to route progress; our overlay is an independent timeline, so the landing
destination must become visible no later than the cover touches down.

**Interaction behavior**: On pop, the destination page fades in over 60ms with
no delay (was 180ms + 20ms delay). The tab page being covered fades out 60ms in
parallel (was fully opaque). Route slide values 280/240ms and all cover-flight
geometry stay untouched. Reduced-motion (motion scale 0): both fades snap, no
delay, first frame shows final state.

**Copyable prompt**:

```text
In the Compose app, edit LyraMotion.kt: add a private const DETAIL_FADE_MS = 60.
In slideInto(), replace the fadeIn spec (180ms with 20ms delay) with a snapOrTween
of DETAIL_FADE_MS and no delay. Add slideUnderDetail(scale): slideOutHorizontally
with the existing spring (NoBouncy, StiffnessMediumLow, targetOffsetX = -it/10)
plus a fadeOut tween of DETAIL_FADE_MS with FastOutLinearInEasing; return
ExitTransition.None when MotionSpec.shouldAnimate(scale) is false.
In LyraApp.kt, in the tab composable's exitTransition, when
LyraRoutes.isDetail(targetState.destination.route) use LyraMotion.slideUnderDetail
instead of ExitTransition.None. Do NOT change MotionTier.ROUTE values, the cover
flight overlay, or any other transition. Keep graphicsLayer-only animation (no
layout properties). Kotlin only.
```

## 020 骨架修正

**Selected interaction**: Loading placeholders on song-list screens
(playlist detail, leaderboard detail rows, search results).

**Why this fits**: No catalogued pattern — ordinary implementation. The skeleton
must mirror the real row's anatomy (rank number, two text lines, trailing menu
dot) instead of implying a thumbnail that does not exist.

**Interaction behavior**: Playlist detail shows 8 full-width skeleton rows while
`loading && list == null`. Leaderboard rows use a rank-bar + two lines + 16dp dot;
search rows use two text lines only. Shimmer highlight is derived from the base
color's luminance (65% toward white on light themes, 16% on dark), gradient band
0.5 step wide; reduced-motion renders a static block.

**Copyable prompt**:

```text
In LyraSkeleton.kt, keep LyraSkeletonListRow's fade/stagger logic but rename it
to LyraSkeletonRankRow and change its content: remove the 44dp square; use a
26x24dp bar (corner 6dp), a weight(1f) column with a 0.55f-width line and a
0.35f-width line 6dp below, and a 16dp dot (corner 4dp) at the end. Add
LyraSkeletonTextRow(index, modifier) that delegates to LyraSkeletonRankRow.
In LyraSkeleton(), compute highlight = lerp(base, White, 0.65f) when
base.luminance() > 0.5f else lerp(base, White, 0.16f), and narrow the gradient
band: start = Offset(phase * 1500f, 0), end = Offset((phase + 0.5f) * 1500f, 600f).
In TrackListScreen.kt, inside the existing LazyColumn, when state.loading &&
list == null emit items(8) { LyraSkeletonRankRow(index = it) } before
itemsIndexed; keep the empty-state text only when not loadingRows.
In LeaderboardDetailScreen.kt and SearchScreen.kt, swap the skeleton call to the
new function names. Do not touch motion tiers, stagger constants, or any other
screens. Kotlin only.
```
