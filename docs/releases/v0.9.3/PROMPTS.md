# v0.9.3 实现提示词（逐片）

本文件按 [`PLAN.md`](PLAN.md) 的五个切片，各给四段：**Selected interaction** /
**Why this fits** / **Interaction behavior** / **Copyable prompt**。写法与
[`../v0.9.2/PROMPTS.md`](../v0.9.2/PROMPTS.md) 一致。

010 / 011 / 012 / 013 / 014 五段都是**实现之后**补写的，与代码一致，可以当 review 依据。
其中 013 与 014 各有一段"与提示词不同"，那两段是 review 时最该先看的。

用法：把 **Copyable prompt** 里那段英文整段复制给任何实现方（人或 AI）。

M10 只有一处真的用上高级模式：**013 = Velocity-Based Slider Snap（模式 4）**，
而且与模式定义有一处关键偏离（过冲即真实落点、不弹回），理由写在 013 那段。
共享元素与 pager 视差、换曲文字都判定为"没有匹配模式 → 普通实现"。

---

## 010 迷你播放条 ↔ 播放页封面共享

### Selected interaction

No matching pattern（没有匹配模式）

### Why this fits

十个模式描述的是"变化从哪里开始、谁跟着让位、操作停在哪里"，没有一种描述
"同一个元素跨两个界面保持连续"。它离 `Radial Theme Transition` 最近（都关心"变化从哪儿起"），
但那一套的前提是两层同尺寸同位置只裁切，这里两端尺寸差 5 倍。按普通实现处理，走 ADR-0010 方案 A。

### Interaction behavior

触发方式：路由变成播放页（点迷你条、点播放入口都算）。开始状态：迷你条上 44dp 的封面、圆角 12dp。
变化过程：覆盖层 350ms（large 档进入）内把位置从迷你条矩形插值到播放页大图矩形、尺寸 44dp → 230dp、
圆角 12dp → 22dp，两端原地那一份 alpha 归 0 但留在树上。最终状态：大图到位，覆盖层不再画。
返回方向 450ms。取消 / 失败状态：图没到就不飞；系统动画缩放 0 时整件事不发生。
触控区域：覆盖层不吃任何触摸，迷你条的点击目标不变。

### Copyable prompt

```text
Use the selected interaction pattern in my existing interface. Preserve the current visual style, layout language, and component structure. Do not rewrite unrelated components. Implement the trigger, state changes, start and end states, motion behavior, responsive behavior, keyboard accessibility, touch support, and reduced-motion fallback described below:

Add a shared-element cover transition between the mini player bar and the full player screen of an existing Jetpack Compose Android app, WITHOUT touching the existing route transition. Keep every current route-transition value exactly as it is (280 ms slide-in and 240 ms collapse-out for the player route, plus the existing low-bounce and no-bounce springs): the route owns how the page arrives, and only the cover artwork is handed to a separate overlay.

Two endpoints: a 44 dp square cover with 12 dp corners in the bottom mini bar, and a 230 dp cover with 22 dp corners on the player screen. Both endpoints register their own rect, corner radius, and current bitmap into one app-level state object. The trigger reads the source rect as a snapshot and the target rect live.

Motion: one full-size Canvas overlay, rendered above the scaffold and OUTSIDE the NavHost so it survives the navigation animation. Over 350 ms forward and 450 ms back (the app large motion tier, multiplied by its motion-scale value) interpolate left, top, width, height, and the corner radius, and crop the source bitmap the same way ContentScale.Crop would. While flying, both endpoints hide their own copy by setting alpha to 0 through a graphicsLayer lambda: the nodes must STAY in the tree, otherwise the target rect can no longer be measured and the flight collapses onto the start rect.

Do not animate any layout-participating property: no size, width, height, or padding animation anywhere in this feature. Position, size, and corner radius live only in the overlay draw parameters.

Hard rules: if the artwork bitmap is not loaded yet, do not start the flight at all, because falling back to today's hard cut is better than interpolating a placeholder. When the system animator duration scale is 0, the flight must not happen either. Trigger on the route change rather than on the click callback, so gesture-back, system-back, and button-back all fly.

Responsive and input: the overlay must not consume pointer events, must not affect measurement, must not change focus order, keyboard navigation, or screen-reader output, and must not move any existing hit target.

Do not restyle the player screen, the mini bar, the navigation bar, or any other component, and do not switch the app to a framework shared-transition API: that one requires both endpoints to be two states of one AnimatedVisibility scope, which a scaffold bottom bar and a NavHost route are not.

Verify with pixel-level instrumented tests, not semantic matchers: the overlay is a single Canvas with no semantics node, so the only assertion is which pixels are the cover colour and where their bounding rect sits. Mid-flight it must be strictly between the two endpoints, both endpoints must be hidden, after landing both must draw again, and at scale 0 nothing may fly.
```

---

## 011 歌单 / 榜单卡片 ↔ 详情页头封面共享

### Selected interaction

No matching pattern（没有匹配模式）

### Why this fits

与 010 同一个理由。这一片多出来的难点不是交互而是**身份**：010 是两处固定端点，这里是一条卡片与
四条详情路由的任意组合，所以要先把"两端是不是同一个元素"变成可判定的键。键的设计是普通工程决策，
不属于任何模式，也不该被包装成模式。

### Interaction behavior

触发方式：点一张带封面的卡片进入详情（专辑 / 平台歌单 / 榜单 / 自建歌单），或从那一页返回。
开始状态：卡片上的封面（首页与两个广场 110dp / 12dp，收藏的歌单行 48dp / 10dp）。
变化过程：覆盖层 350ms 内插值到页头 96dp / 12dp 的矩形与圆角。最终状态：页头画回自己那一份。
返回方向 450ms，落点是**来路那一页上那张卡**的矩形。取消 / 失败状态：键对不上、图没到、
落点从未登记过、系统动画缩放 0——四种都不飞。触控区域：不变，覆盖层不吃触摸。

### Copyable prompt

```text
Use the selected interaction pattern in my existing interface. Preserve the current visual style, layout language, and component structure. Do not rewrite unrelated components. Implement the trigger, state changes, start and end states, motion behavior, responsive behavior, keyboard accessibility, touch support, and reduced-motion fallback described below:

Extend an existing shared-element cover flight (already working between the mini player bar and the player screen) so that cover cards on list screens fly into the 96 dp header artwork of the detail screen they open, and fly back to the exact card they came from on return. Do not change the route transition durations, easings, or springs, and do not reuse the two-slot enum design: this needs an endpoint registry keyed by cover identity.

Keying rules (these are the feature):
- A card endpoint is keyed by (the page it is on, the destination route path of that card). A detail endpoint is keyed by the route path of its own page. Both sides derive their key independently from the same route builders, so no id is ever passed from a card into a page.
- The page must be part of the card key. The same playlist appears both in the home recommendation row and in the playlist square, so keying only by destination lets "enter from home, go back to home" land on the stale rect of the square card, a page that is not even on the stack.
- Key by route path, never by cover URL. A card and its detail header can receive differently sized image URLs for the same cover, which would make a legitimate flight stop happening.
- Only routes whose header artwork IS that page own cover may participate: album, platform playlist, leaderboard detail, my playlist. The liked-songs and local-music headers show the first track artwork as a proxy, so flying a card cover into them flies the wrong thing; those must not register a detail endpoint at all.

Motion: reuse the existing overlay. 350 ms forward, 450 ms back, large motion tier multiplied by the app motion scale, interpolating rect and corner radius (cards are 12 dp, saved-playlist rows 10 dp, headers 12 dp), cropping the bitmap like ContentScale.Crop. Hide both endpoints with a graphicsLayer alpha lambda for the duration and keep them in the tree.

Read the target rect live through its LayoutCoordinates while it is attached, and fall back to the last snapshot only after it detaches. A route transition only changes a graphicsLayer, so layout placement does not re-fire: without the live read the flight would target where the header sits after its slide-in finished and would arrive early.

Performance constraints (this app has a scroll-jank budget under 1 percent):
- The registry must be a plain map, not snapshot state. Endpoints report their rect from onGloballyPositioned, which fires for every visible item on every scroll frame; an observable map would turn one scrolled row into a whole-page recomposition.
- Hide endpoints by reading the flight state at DRAW time (graphicsLayer lambda), never at composition time, otherwise a single flight recomposes every card on the page during the transition first frame.
- onGloballyPositioned is the ONLY writer of the registry. Do not add a SideEffect or LaunchedEffect that re-reports the bitmap: when the captured bitmap changes a new lambda node is installed and placement re-runs, so a swapped cover (song change with no layout change) is already covered.

Degradation rules: no flight when the key has no match; no flight when the artwork is null; no flight when going back if the landing key was never registered (that case would look like the header cover fading in place for no reason, a ghost); no flight at all when the system animator duration scale is 0. Read nav arguments with arguments.get(name).toString(), not getString(): the my-playlist id is an Int navArgument and getString returns null, which would silently disable the flight on that page.

Responsive and input: the overlay consumes no pointer events and takes no part in measurement; grid columns, row heights, card sizes, and all hit targets stay exactly as they are; keyboard and screen-reader behaviour must be identical to the non-animated case.

Do not restyle PlaylistCard beyond adding one optional parameter for its destination path, do not wire up library entry cards or search-result group headers, and do not rewrite any unrelated component.

Verify with pixel-level instrumented tests that pin the keying: a matching path must produce a real mid-flight frame; a mismatched path must produce zero flight frames; returning to a different page must not reuse another page card rect; an unloaded cover must never fly; a card whose cover was swapped must fly the NEW cover; and scale 0 must never fly.
```
---

## 012 pager 视差与页码跟手

### Selected interaction

No matching pattern（没有匹配模式）· 辅助：延续 **Expanding Tag Selection**（模式 10）

### Why this fits

视差是"同一页里两块内容以不同速度移动"，十个模式里没有对应项。页码跟手不是新模式：
006 判定为模式 10 时明确把 `currentPageOffsetFraction` 留给本片，所以这里改的是**驱动源**
（从"翻完才跳"到"跟手"），不是交互语义。

### Interaction behavior

落地与设计一致，两处按实现改了说法：**视差只给封面**（计划里歌词页那点位移没做，理由见
Why this fits 下面那段），以及"跟手"不是缓动而是**每帧现算**。

触发方式：在播放页左右拖动翻页，或程序化换页。开始状态：静止，页码为 006 的形态
（当前点 18dp 药丸、其余 7dp 圆），封面就在它的布局位置上（视差为 0）。
变化过程：封面按连续位置做 `translationX`，方向与页面移动相同、幅度只有页宽的 8%，
所以读起来是"落后于页面"；药丸宽度与邻居让位按同一连续位置现算，手指停在哪就是哪一档。
最终状态：落位后与 006 完全一致。取消 / 失败状态：中途反向拖动立刻跟着回退，不许停在两态之间；
两端橡皮筋 overscroll 会把连续位置推出 `0..pageCount-1`，一律夹回；
`scale ≤ 0.1`（`MotionSpec.shouldAnimate` 为 false）时不读 fraction，位置退回离散的
`currentPage`，直接落终态。触控区域：翻页手势本身不变。

**为什么歌词页不给视差**：那一页唯一要读的就是字，文字以一个与页面不同的速度漂，
读到的是"字在晃"而不是深度。整页一起位移也没有意义——`verticalScroll` 的可视高度不变，
用户看到的还是"一整页在动"，却多了一层重绘。

**为什么颜色仍由离散的 `currentPage` 驱动**：几何回答"这块图现在多像当前页"（连续量），
颜色回答"哪一页是当前页"（身份）。半程时把两枚都染成中间色，读起来是"两页都是当前页"，
那不是信息。这条是本片唯一的设计判断。

### Copyable prompt

```text
Use the selected interaction pattern in my existing interface. Preserve the current visual style, layout language, and component structure. Do not rewrite unrelated components. Implement the trigger, state changes, start and end states, motion behavior, responsive behavior, keyboard accessibility, touch support, and reduced-motion fallback described below:

On an existing Jetpack Compose Android player screen there is a two-page HorizontalPager (page 0 = cover and controls, page 1 = lyrics) and a page indicator below it. Today the indicator jumps only when the pager settles. Make both the indicator geometry and a small cover parallax follow the drag continuously, driven by the pager offset fraction.

Architecture (this is the part that matters):
- Put all the arithmetic in a separate pure Kotlin object with no Compose imports: continuous position, per-page "activity" (1 when the position sits exactly on that page, 0 a full page away, linear between), dot scale, neighbour shift ratio, and parallax translation in pixels. This is what makes the behaviour unit-testable on the JVM.
- Provide a composable rememberPagerPosition(pagerState) that RETURNS a () -> Float lambda, and take () -> Float parameters in the consumers. Do not return a Float and do not read currentPageOffsetFraction during composition: it changes every frame, so reading it at composition time recomposes the indicator row and the whole cover column on every drag frame, and dragging sits squarely inside the app's scroll and transition-first-frame jank budget. Read it only inside graphicsLayer lambdas, at draw time.
- Clamp the continuous position into 0..pageCount-1. Rubber-band overscroll at either end otherwise pushes it past the range, which makes the activity negative and shrinks the pill below "not the current page at all".

Page indicator: keep the existing 006 design (each dot is laid out 18 dp wide; the resting dot is drawn as a 7 dp circle with scaleX = 7/18; neighbours translateX by up to 5.5 dp to close the visual gap). Replace the animated spring that chases a discrete target with a per-frame value computed from the continuous position, so a half-way drag draws a half-way pill and dragging back to the origin returns the pill exactly to its start state with nothing left between two states. Do not stack a second easing on top of the pager's own: while following the finger, two curves will fight, and the pill width must equal the arithmetic value within one frame of the position changing. Note that a spring chasing a discrete target also agrees at the endpoints and the midpoint, so verify linearity with a pixel measurement taken exactly one frame after a position change, not with unit tests of the pure functions.

Colour stays discrete: the dot colour keeps being driven by pagerState.currentPage, not by the continuous position. Geometry is "how much like the current page is this one right now", colour is identity; tinting both dots to a mid colour at half way reads as "both pages are current", which is not information. So the component needs two inputs from two sources, one read at composition time (current) and one read at draw time (position). Do not merge them.

Invariant to preserve: through the whole drag the visible gap between the two dots must stay exactly the 6 dp of the row's spacedBy, even though both the width and the shift are changing. Derive the shift from how pill-like the neighbour side is (right neighbour's activity pushes you right, left neighbour's pushes you left), which equals the old hardcoded +/-5.5 dp at rest and splits evenly mid-drag.

Parallax: shift ONLY the cover artwork Box of page 0, by 8 percent of the page width multiplied by (position - page), i.e. it moves in the same direction as the page but lags behind it. Give the lyrics page no parallax: text drifting at a different speed from the page reads as "the words are wobbling", not as depth, and shifting the whole page changes nothing visible because the scroll viewport height stays fixed. Define the amount as a percentage of page width, never a hardcoded dp, so fold and landscape widths do not distort the depth. Measure the page width once with onSizeChanged. At rest the offset is exactly 0, so the cover never sits a pixel away from its layout position. Cap the magnitude: the worst frame must stay inside the cover's horizontal slack (cover 230 dp, page padding 20 dp), and keep the constant inside a JVM test that bounds it between 5 and 12 percent of the page width, so a future edit that makes it too big fails a test instead of looking wrong on a device.

Order with the existing shared-element cover anchor: put the parallax modifier BEFORE lyraCoverAnchor, so the rect reported to the flight registry already includes the shift.

Constraints: do not animate any layout-participating property (size, width, height, padding, arrangement) - scale, translation, and alpha on graphicsLayer only; the indicator row must never change height or reflow; do not touch the existing snap-to-end spring constants; do not change any route transition duration or easing; keep every duration on the app's shared motion tokens.

Interaction: finger drag, fling, tap, and programmatic page changes all drive the same continuous value, and a fling crossing both pages must not leave the indicator between states. A drag that reverses direction mid-flight must show the reversed value on the next frame.

Responsive and input: no new hit targets, nothing focusable, screen readers keep announcing only the page change, and the parallax must not move anything outside its own clipped bounds.

Reduced motion: when the app motion scale is snapped (system animator duration scale at or below 0.1), stop reading the offset fraction and fall back to the discrete currentPage, so the indicator snaps to a full step instead of slowly following the finger. Gate this in the one place that computes the continuous value so the indicator and the parallax cannot diverge, and test it through the real pager state, because the gate lives in that helper - feeding a raw fraction straight into the indicator proves nothing about scale 0.

Do not restyle the cover page, the lyrics page, or any other component, and do not rewrite the existing PageIndicator visuals beyond swapping its driver.

Verify: JVM tests for continuity, both-end clamping, the constant-gap identity, and the parallax bounds; device tests that measure ink pixels and count recompositions. Include one negative-control device test that reads the offset at composition time and asserts the recomposition counter DOES increase - without it, an assertion that the counter stays at zero during 80 drag frames is indistinguishable from a broken counter. Never compute a test expectation from the constant under test: the parallax assertion must hardcode the expected pixels, otherwise setting the ratio to zero still passes.
```

---

## 013 seek 惯性吸附（已落地，模式 4，四处与提示词不同）

### Selected interaction

**Velocity-Based Slider Snap**（滑杆惯性吸附）· 模式 4

### Why this fits

这是全项目唯一一处真正匹配模式 4 的交互：用户在滑杆上选一个目标值，而**释放速度里带着意图**
（快速一甩 = 想跳更远）。M9 判定"无匹配"就是因为这一处需要的是投影而不是插值。

**一处必须写明的偏离**：模式 4 的原始定义是"过冲之后用弹簧弹回最近刻度"。这里的过冲**就是真实落点**
——松手后按释放速度投影出目标秒数并 `seekTo` 到它。因为播放进度轴上没有刻度可回弹，"弹回"要么落回
手指处（那惯性就是假的），要么落在一个与投影无关的位置。所以保留模式 4 的两条硬约束
（记录释放速度、结果 clamp 在合法区间），把"回弹"换成"过冲即落点"。

### Interaction behavior

触发方式：拖动 seek 条后松手。开始状态：拇指在手指处，气泡显示"若现在松手会落到哪"。
变化过程：松手瞬间用**最近 100ms（窗口右端 = 抬起事件的时刻）**的位移估速度，投影偏移 =
速度 x 12ms，先夹 ±3s、再夹进 `0..duration`，`seekTo` 到那个值。最终状态：拇指与播放位置一致，
左下角时间显示落点（播放器报告出新值之前不会先跳回旧位置）。
取消 / 失败状态：慢松手，或"甩完停一下再抬手"⇒ 窗口凑不满两点 ⇒ 速度 0 ⇒ 落在手指处；
`scale = 0`（动效关闭）时**跳过投影**直接落手指处，但照样夹进合法区间。
不是手指的两条路径**一律不投影**：键盘方向键保持自己的 1% 步长，TalkBack 的 `SetProgress`
保持它播报的那个值——判据是"有没有 down 事件"，不是猜这是不是键盘。
触控区域：沿用现有 Slider 的 44dp（`minimumInteractiveComponentSize`），本片一个尺寸都没改。

### Copyable prompt

```text
Use the selected interaction pattern in my existing interface. Preserve the current visual style, layout language, and component structure. Do not rewrite unrelated components. Implement the trigger, state changes, start and end states, motion behavior, responsive behavior, keyboard accessibility, touch support, and reduced-motion fallback described below:

Add velocity-based inertia to the seek bar of an existing Jetpack Compose Android player screen. Today releasing the thumb seeks to exactly where the finger was.

Interaction: while the user drags, record (event timestamp, playback position) samples and estimate release velocity from the displacement inside a roughly 100 ms window whose RIGHT EDGE IS THE UP-EVENT TIMESTAMP, not the newest sample. Project an offset as velocity times a carry constant of 12 ms, clamp the offset to plus or minus 3 seconds first, then clamp the resulting position into 0..duration. Seek the player to that projected position so the thumb, the time label, and the actual playback position always agree.

One deliberate deviation from the classic pattern: the overshoot IS the real landing point. There is no spring-back to a tick, because a timeline has no ticks to spring back to, and springing back to the finger position would make the inertia a lie. Keep the two constraints that matter here: the release velocity is measured, and the final value is clamped to the legal range.

Why the carry constant is 12 and not larger: the cap must bind only for a real fling. On this device the usable track is about 974 px and a 4-minute song maps 1 px to about 246 ms, so a 12 ms carry saturates at roughly 1 px/ms (about 1000 px/s), while 0.5 px/ms adds about 1.5 s and 0.2 px/ms about 0.6 s. A carry of 120 saturates at about 0.1 px/ms, which makes EVERY drag jump exactly 3 seconds and turns the pattern into a binary switch instead of a graded one. Guard this with a test asserting distinct offsets for several velocities.

Why the window is anchored on the release time: "flick fast, hold still, release slowly" must not project. Release velocity means how fast the finger was moving at the moment of release, and a pause already means zero.

Affordance during the drag: a small time bubble above the thumb shows the position the release would seek to, including the projected offset while the finger is still moving. Measure the bubble width at composition time with rememberTextMeasurer (measuring via onSizeChanged shows it at the wrong place for one frame at the start of every drag), position it with graphicsLayer translationX, and clamp its left edge so it stays inside the row at both ends of the timeline. It must not change the layout height of the row, must not be focusable or tappable, and must be drawn inside the slider's own 44 dp touch band rather than inserted into the column.

Do NOT add a thumb press scale. Material3 1.4.0's default thumb is already a 4 dp x 44 dp bar that narrows to 2 dp while pressed (SliderTokens.PressedHandleWidth), so a 1.00 to 1.12 scale would be a 0.45 dp change on a 4 dp-wide pill: invisible, and fighting the library. Shipping the stable value-based Slider overload keeps this off the experimental API; the bubble is the real feedback.

Edge cases: a slow release, or a fast drag followed by a pause before release, must behave exactly like today with no visible projection. A release at either end must clamp and must not seek past 0 or past duration. A drag that reverses direction before release must use the reversed velocity. Rapid repeated drags must always end at a real seek, never at a stale preview value, and samples must be cleared at the end of every gesture. Keep the existing rule that during a drag the UI shows a local value and does not seek every frame, otherwise the playback position drags the thumb back. After release, hold the displayed value at the landing point until the player reports ANY different value: compare against the position recorded at release, never against a tolerance around the target, otherwise a backward drag gets cleared by the stale lagged position and the thumb jumps back before jumping forward.

Constraints: do not animate layout-participating properties; the bubble position must be a graphicsLayer and draw-time effect. Do not change the slider track colours, thickness, or hit target size. Do not touch the play or pause controls, and do not rewrite unrelated components.

Detecting "this ended because a pointer lifted" cannot use SliderState.isDragging: drag() clears it BEFORE onValueChangeFinished runs, and Material3's own comment ("check isDragging in case the change is still in progress") says so. collectIsDraggedAsState is also too late because DragInteraction.Stop is emitted through a channel collected on another coroutine. Instead attach a pointerInput observer to the modifier passed to Slider, observe only PointerEventPass.Initial and never consume, and use the event uptimeMillis as the clock for samples (not SystemClock, which is a different clock and cannot be fed from a device test). SliderImpl applies this modifier outside its own drag, and the Initial pass precedes every node's Main pass dispatch, so down and each move are seen before that gesture's onValueChange and the up is seen before onValueChangeFinished.

Accessibility: the slider must keep its existing range semantics and step behaviour for keyboard and screen-reader users, and the projection must apply only to pointer drags. Keyboard arrows must land on exactly 1 percent of the range and TalkBack SetProgress must land on exactly the value it announced; both are guaranteed by the absence of a pointer-down sample, not by a velocity that happens to compute to zero. A known, deliberate deviation from the earlier brief: the bubble is clearAndSetSemantics so TalkBack reads the slider's own range info (the finger position) during the drag rather than the projected target. Making the projected value a live region would announce every frame mid-drag, which is worse; after release the slider's own announcement becomes the real landing point. Touch targets stay at least 44 dp.

Reduced motion: when the animation scale resolves to 0 (MotionScale.isSnapped), skip the velocity projection entirely and seek to the finger position, with no bubble travel. The projection is motion-derived feedback, and users who turned animation off did not ask for it. Clamping still applies: landing on the finger is not licence to land outside the timeline.

Extract the arithmetic into a pure object (velocity window estimate, projection with two-stage clamping, bubble left edge from fraction / row width / bubble width / handle inset) with JVM unit tests covering zero velocity, slow release, both directions, graded non-saturating speeds, the cap, a reverse velocity above the cap, clamping at both ends, a degenerate duration, the reduced-motion bypass, non-finite velocity, window membership, and every bubble clamp case. Device tests must assert landing points DIFFERENTIALLY: tap once at the same finger position to measure what "the finger position" actually is, then compare the fling against that number, so the test never encodes the library's internal pixel-to-value mapping.

Test plan: 20 JVM tests plus 8 device tests. The device harness uses a 30-second track on purpose: the 3 s cap is only 1.25 percent of a 4-minute timeline while the thumb-centre arithmetic itself carries about 0.5 percent error, so the pass/fail margin would be the same size as the measurement error. On a 30 s track the cap is 10 percent of the axis, twenty times the error.
```

---

## 014 迷你条换曲文字（已落地，两处与提示词不同）

### Selected interaction

No matching pattern（没有匹配模式）

### Why this fits

"标题变了要有个过渡"是状态交替，不是十个模式里的任何一种（模式 6 步骤条回弹要求真实步骤不变、
模式 10 标签挤开要求邻居让位，这里都只有一行文字）。按普通实现处理，`AnimatedContent` + micro 档。

### Interaction behavior

触发方式：播放列表换到另一首歌。开始状态：旧的两行字（曲名 + 艺术家），alpha 1、位移 0。
变化过程：旧的向上滑出并淡出（100ms）、新的从下方五分之一行高处滑入并淡入（120ms），
均乘 `LocalMotionScale`，无过冲；容器尺寸不插值（`using(null)`）。最终状态：只剩新的两行字，
位置、字号、`maxLines`、省略号策略与原来逐项相同。
取消 / 失败状态：**同一首歌**补封面 / 时长 / 清晰度，或播放暂停翻转 ⇒ 交替键不变 ⇒ 一次都不动；
连点两首 ⇒ 只往最新那首交替，中间那首不排队重播。触控区域：迷你条仍是同一个点击目标，没改。

不是手指的两条路径不适用（这里没有输入路径）。动效关闭（`scale = 0`）时**不进 `AnimatedContent`**：
见下面第一处偏离。

### 两处与提示词不同

**1. 交替键是"身份 + 两行文字"，不是"歌名"**
提示词只说"keyed on the song identity"。落地的键是 `SongLineText(identity = (source, id), title, artist)`
三项：跨音源的同数字 id 算两首歌（`id` 只在单个音源内是主键，口径与 `FavoritesRepository.keyOf` 一致）；
同名同艺术家但换了 id 照样交替（旁边封面同时换了，不动会读成"这行还属于上一首"）；
同一 id 改了曲名（本地文件改标签后重扫）文字必须换过去。

**2. `scale = 0` 不能靠 `EnterTransition.None togetherWith ExitTransition.None`**
提示词写的是"changes in the same frame with no fade and no offset"，第一版按字面用了那对 `None`。
实测**做不到**：`AnimatedContent` 仍会把旧、新两份内容同画一帧，迷你条闪一下叠字
（像素差分：静止↔第 0 帧 1863px、第 0 帧↔稳定态 1800px、第 1 帧↔稳定态 0px）。
现在改成关掉动画时直接渲染终态内容，压根不进 `AnimatedContent`；同一条错误在 005 的
播放/暂停图标上一起修了，两处的判据各是一条像素测试。详见 [`CHANGELOG.md`](CHANGELOG.md) 决策 8。

取证也不是截图：这一片要判的东西在 unmerged 语义树上直接数得出来（交替中有两份内容、
没发生时只剩一份），比截图稳，也不依赖字体度量。

### Copyable prompt

```text
Use the selected interaction pattern in my existing interface. Preserve the current visual style, layout language, and component structure. Do not rewrite unrelated components. Implement the trigger, state changes, start and end states, motion behavior, responsive behavior, keyboard accessibility, touch support, and reduced-motion fallback described below:

Animate only the title and artist lines of the mini player bar of an existing Jetpack Compose Android app when the playing song changes. Use AnimatedContent keyed on song identity (source plus id) together with the two rendered strings, never on the playback state and never on the whole song object.

Start state: the previous two lines at alpha 1 and offset 0. Change: the old lines fade out while sliding up and the new lines fade in from about one fifth of the line height below, over the app micro motion tier (100 ms out, 120 ms in, multiplied by the app motion scale), no overshoot. End state: only the new lines, at exactly the same position, font size, maxLines 1 and ellipsis behaviour as before.

Trigger rules, these are the point of the change: the transition plays when the song identity changes and must NOT play when the same song starts, pauses, or gets its artwork, duration or quality filled in. Without that rule the bar replays the animation on every metadata update, which is noise and not information. Nothing else in the bar (cover, progress, buttons) may animate as a side effect.

Rapid changes: if the song changes again before the previous transition finished, animate to the newest value only and never queue the older ones. The text shown must always match the currently playing song at the end of the sequence.

Layout guardrail: disable the default size transform of AnimatedContent (call using(null) on the ContentTransform). Do not animate any layout-participating property; the slide must stay on the draw layer so the row height cannot change mid-transition.

Reduced motion: when the app motion scale is 0, do not use AnimatedContent at all for that frame and render the end content directly. Do not rely on EnterTransition.None togetherWith ExitTransition.None for this: AnimatedContent still keeps the outgoing content and paints both copies for one frame, which shows as a visible double-draw. A pixel diff of frame zero against the settled frame must be exactly zero.

Cover it with instrumented tests that (1) count how many copies of the title exist in the unmerged semantics tree, so metadata updates on the same song must show one copy while an in-flight swap shows two, and (2) diff two screenshots at motion scale 0 to prove no frame ever paints both copies. Assert frame counts, not wall-clock time, and never assert on intermediate frame values.
```
