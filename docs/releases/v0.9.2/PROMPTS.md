# v0.9.2 实现提示词（逐片）

本文件按 [`PLAN.md`](PLAN.md) 的九个切片，各给四段：**Selected interaction** /
**Why this fits** / **Interaction behavior** / **Copyable prompt**。

用法：把 **Copyable prompt** 里那段英文整段复制给任何实现方（人或 AI）。英文提示词里写清了
具体组件、触发方式、起止状态、位移 / 尺寸 / 透明度 / 裁切 / 过冲的具体量、数据状态与视觉状态
的关系、响应式与键盘 / 触控 / reduced-motion 要求，以及"不要改写无关组件"。中文段落是给
review 的人看的判定依据，不必一起复制。

十种高级模式的判定表在 `PLAN.md` §3：本版只有三个真正匹配
（Expanding Tag Selection、Staggered 的错峰机制、Radial Theme Transition 的降级形态），
其余按"没有匹配模式 → 普通实现"处理——**判定为无匹配的段落，英文提示词里照样要写全动作细节**，
"没有匹配模式"不等于"不用写清楚"。

---

## 001 转场首帧 jank 收口

### Selected interaction

No matching pattern（没有匹配模式）

### Why this fits

这不是交互问题，是转场首帧的合成预算问题。十个模式都在描述"变化从哪里发生、谁跟着让位"，
没有一个描述"这一帧的合成超了预算多少"。按普通实现处理：先定位、再收口、再复测。

### Interaction behavior

触发方式是进入详情页。开始状态：进入动画跑起来了，但首帧同时在做整页首次组合与首屏数据落地。
变化过程：把内容的组合 / 订阅推迟到转场结束之后，转场期间只有容器在动。最终状态：转场结束时
内容一次到位。取消 / 失败状态：若不成立（首帧在 120Hz 下达不到），就改门槛并在 `CHANGELOG.md`
记决策。触控区域无关，本片不动触摸行为。

### Copyable prompt

```text
Use the selected interaction pattern in my existing interface. Preserve the current visual style, layout language, and component structure. Do not rewrite unrelated components. Implement the trigger, state changes, start and end states, motion behavior, responsive behavior, keyboard accessibility, touch support, and reduced-motion fallback described below:

This is a performance task on an existing Jetpack Compose Android app, not a new interaction. Do not add or restyle any UI. Keep the existing route transition (280 ms slide-in for the player screen, 240 ms collapse back, a 180 ms fade with a 20 ms delay on entry, 140/160 ms fades on exit, and the existing low-bounce / no-bounce spring constants) exactly as it is; only change WHEN page content is composed and WHEN it subscribes to data.

Target the first frame of a route transition, measured with `adb shell dumpsys gfxinfo framestats` (the new FrameTimeline-based metric, not the legacy column) plus a Perfetto trace of one transition and one scroll.

Current behavior to fix: when a detail route is entered, the destination composable composes its full content tree and its ViewModel subscribes to its first page of data during the same frame that the transition starts, so the first transition frame does the composition work, the image decode work, and the first data write together.

Required behavior: during the transition, keep only the container and the transition running; defer composing the page content body and defer the data subscription to a point after the transition settles (for example, gate them on a transition-finished signal in a LaunchedEffect, or call the suspend entry point from a coroutine that starts after the transition). Content must appear exactly once it is ready, with no empty-state flash and no visible pop-in: if the data is already cached, the content must be present in the first frame after the transition; if it is not cached, keep the existing loading affordance rather than showing an empty list.

Constraints: do not change any duration, easing, spring constant, layout size, or color; do not animate layout-participating properties; do not rewrite unrelated components or screens; keep back navigation, predictive-back settings, and ViewModel lifetimes unchanged; make the change in one shared place if the same pattern repeats across routes rather than copying it into every screen.

Accessibility and input: no change to touch targets, focus order, keyboard navigation, or screen-reader labels; nothing in this change may depend on a pointer or hover. Reduced motion: when the system animator duration scale is 0 the transition does not run at all, so content must be composed and subscribed immediately in that case instead of waiting for a transition-finished signal that will never arrive.

Report the before and after numbers for the same scenario and the same metric, and state explicitly whether the 120 Hz first-frame target was met; if it was not, propose the threshold change and the reasoning instead of silently loosening it.
```

---

## 002 动效地基

### Selected interaction

No matching pattern（没有匹配模式：这是"动效时长从哪里来"的地基，不是某个具体交互）

### Why this fits

十个模式描述的都是"某个变化怎么演"，没有一个描述"所有变化的时长由谁统一决定"。按普通实现处理。

### Interaction behavior

触发方式：应用启动时读系统设置，或测试直接把缩放传进来。开始状态：`scale = 1`，各层用基准时长。
变化过程：`scale` 按乘算作用于基准值；`scale ≤ 0.1` 时全部走 `snap()` 到终态而不是"很短的 tween"。
最终状态：`0` 时所有 spec 动画瞬时完成，反馈级形变不发生但点击照常生效。取消 / 失败状态：
设置读不到（负值 / `NaN`）时按 `1` 处理，不能因为解析异常把界面变成幻灯片。触控区域无关。

### Copyable prompt

```text
Use the selected interaction pattern in my existing interface. Preserve the current visual style, layout language, and component structure. Do not rewrite unrelated components. Implement the trigger, state changes, start and end states, motion behavior, responsive behavior, keyboard accessibility, touch support, and reduced-motion fallback described below:

Add a motion foundation to an existing Jetpack Compose Android app. Do not change the visual design, layout, typography, or shapes; do not restyle any component.

1. A four-tier duration table plus one route tier, as the single source of every duration in the app: micro 100 ms in / 120 ms out (press feedback, icon morph, favorite pop), small 150 / 200 ms (page indicator, text swap, favorite cross-fade), medium 250 / 300 ms (list insertion and removal, snackbar, staggered reveal, theme ripple), large 350 / 450 ms (reserved for cross-screen continuity), and a separate route tier that keeps the existing hand-tuned values 280 ms in / 240 ms out (do not change those numbers). Each number is a base value.
2. Read the system animation scales `Settings.Global.ANIMATOR_DURATION_SCALE` and `Settings.Global.TRANSITION_ANIMATION_SCALE`, take the SMALLER of the two (do not average them: either switch being 0 is an explicit "do not animate" statement), and clamp the result into 0..1 (never scale up). If a value is unreadable, negative, or NaN, treat it as 1.
3. Provide that value through a composition local (name it LocalMotionScale) from a provider placed OUTSIDE the theme so that navigation transition lambdas, which are not composable, can read it too. Default the composition local to 1 so that forgetting the provider degrades to "motion does not follow the system setting" rather than "no motion".
4. Real duration = base duration × scale, rounded down to a whole millisecond with a floor of 1 ms.
5. Reduced motion: when the scale is 0 (or very small, treat <= 0.1 as 0) every animation must SNAP to its end state, not run a very short tween: emit `snap()` rather than `tween(0)` so that no extra frame is scheduled. Animations with no duration to scale — springs — must be either fully played or fully skipped; never play a spring when the scale is 0.
6. Provide a pure, unit-testable function for stagger delays: min(index * 40 ms, 240 ms).

Keep the existing route transitions' visual values and signatures unchanged; only change how those values are computed. Keep any existing blurred backdrop cross-fade at its current 450 ms with a fixed radius.

Constraints: do not animate layout-participating properties anywhere (no size, width, height, padding, or arrangement animations) — only graphicsLayer scale, alpha, and translation; do not use overshooting spatial springs for full-screen route displacement (they reveal the container edge), reserve overshoot for small elements only; do not rewrite unrelated components; do not introduce new libraries.

Accessibility and input: this layer must not change touch targets, focus order, keyboard navigation, or screen-reader output; a scale of 0 must never disable the action itself, only its animation. Add JVM unit tests for the clamp (0, 0.5, 1, 1.5, 2, negative, NaN), for each tier × scale, and for the stagger helper.
```

---

## 003 导航转场去重

### Selected interaction

No matching pattern（没有匹配模式：这是注册方式的收敛，观感一个像素都不动）

### Why this fits

模式判定表里没有"把复制了十三遍的参数收成一处"这一项。按普通实现处理，验收靠单测与逐页走查。

### Interaction behavior

触发方式：进任意详情页或 Tab。开始 / 最终状态与现在完全一致，本片**不改任何观感**。
变化过程只是取值方式的替换。取消 / 失败状态：分类错一条的表现是"某一页底下的 Tab 页跟着一起滑"，
所以要用单测把十四类路由钉死。触控区域无关。

### Copyable prompt

```text
Use the selected interaction pattern in my existing interface. Preserve the current visual style, layout language, and component structure. Do not rewrite unrelated components. Implement the trigger, state changes, start and end states, motion behavior, responsive behavior, keyboard accessibility, touch support, and reduced-motion fallback described below:

Refactor navigation transition registration in an existing Jetpack Compose app that uses androidx.navigation.compose. This is a behavior-preserving refactor: the transitions must look exactly the same before and after. Do not touch any screen's content, layout, or data flow.

Problem: four transition parameters (enterTransition, exitTransition, popEnterTransition, popExitTransition) plus the two "is this a detail route" checks are copy-pasted across thirteen route registrations. A single mismatched copy produces the wrong animation — two screens sliding at once — and is hard to spot in review.

Required change:
1. Move every route pattern string into one plain-Kotlin file (no Compose imports) and classify each route with an enum: TAB, DETAIL, PLAYER, UNKNOWN. Keep an ordered map of detail route -> optional argument descriptor (argument name plus whether it is an Int).
2. Add an extension function on the navigation graph builder that registers a detail route with the shared transitions in one place, taking the route, the motion scale, the window insets padding, an optional argument list, and the content lambda. Replace all thirteen registrations with one-line calls.
3. Register the player route separately: its transition is "grow out of the mini player bar", which is a different motion from the detail routes.
4. Top-level tab exits must ask the route table whether the target is a detail route (in which case the tab stays still) instead of consulting a hand-copied set. Treat unknown routes as detail routes so the conservative behavior wins.
5. Keep the existing motion values: tab slides one-tenth of the width with a low-bounce spring in and a no-bounce spring out, a 180 ms fade with a 20 ms delay on entry, a 140 ms fade on exit, and the player's 280 ms slide-and-scale from the bottom edge plus a 160 ms fade with a 60 ms delay on collapse.
6. When the system animator duration scale is 0, every one of these transitions must not run at all (return no transition rather than a very fast one), because springs have no duration to scale.

Constraints: do not change durations, springs, offsets, or scale values; do not rewrite unrelated components or screens; do not add new libraries.

Accessibility and input: unchanged — do not alter back handling, predictive-back configuration, focus order, or keyboard navigation; nothing depends on pointer input. Reduced motion: as in point 6, the whole transition is skipped, and the destination must still be fully usable immediately.

Add pure JVM unit tests that assert the classification of every route (all tabs, all detail routes with and without path arguments, the player route, and an unknown route) and that the player route is not in the detail table. Add a source-scanning guard test so the transition parameters cannot be copy-pasted back into call sites.
```

---

## 004 按压形变与反馈统一

### Selected interaction

No matching pattern（没有匹配模式：十个模式都在描述"内容怎么让位"，没有一个是"按下时被点的物体自己缩一下"）

### Why this fits

需求本质是"确认我点到了哪一个"，与"从哪里发生 / 落在哪里 / 谁跟着变化 / 周围是否让位"四个判断问题
都不对应。按普通实现处理。

### Interaction behavior

触发方式：手指按下可点物体。开始状态 `scale = 1.00`。变化过程：按下 100 ms 无过冲地缩到 0.98
（行 / 卡片）或 0.94（图标 / 点）。最终状态：抬起后 120 ms 回到 1.00。取消 / 失败状态：
指到一半滑出控件范围时回 1.00，点击不触发；`scale ≤ 0.1` 时完全不缩但点击照常生效。
触控区域：不小于 48dp，形变不改命中区。

### Copyable prompt

```text
Use the selected interaction pattern in my existing interface. Preserve the current visual style, layout language, and component structure. Do not rewrite unrelated components. Implement the trigger, state changes, start and end states, motion behavior, responsive behavior, keyboard accessibility, touch support, and reduced-motion fallback described below:

Add unified press feedback to the tappable rows, cards, and icon buttons of an existing Jetpack Compose Android app. The app already shows a ripple through the default indication; keep that ripple exactly as it is and add the missing scale response on top of it. Do not restyle any component and do not change any layout size.

Behavior: on press, the pressed element scales down over 100 ms with a linear-out-slow-in easing and NO overshoot: to 0.98 for rows and cards (a larger value reads as "the whole row is jumping" in a list) and to 0.94 for icons and small round buttons (a small area needs a bigger ratio to be visible). On release it returns to 1.00 over 120 ms. Pressing and then dragging the finger out of the element must return it to 1.00 without firing the click. The transform must be a graphicsLayer scale only; never animate size, width, height, padding, or arrangement, because animating layout-participating properties changes row heights mid-animation and corrupts the scroll anchor of lazy lists in this app.

Structure: provide two modifiers. One wraps the whole clickable row or card (scale plus click, or scale plus combined click when long-press is also supported, keeping the existing onClickLabel and role). The other only attaches the scale and takes the caller's interaction source, for cards that have their own background or container: on those, the scale must be attached OUTSIDE the background or clip so the container scales with the content, while the click stays INSIDE the background so the ripple is drawn above it, and both share one interaction source.

Apply it to: track rows (including their existing long-press "more" entry point), playlist cards, the library entry cards, the source cards, the mini player bar, and the saved-playlist rows. For a source card that has no click of its own (tapping the card does nothing), drive the card's scale from the interaction sources of the switch and delete button inside it, so pressing either reads as "I am pressing this card".

Responsive and input: touch, mouse, and keyboard activation must all produce the same end state; a keyboard "enter" must not leave the element stuck in the pressed scale. Do not shrink the hit area: keep every touch target at least 48 dp and keep the ripples clipped to the element's shape. Screen-reader labels, roles, and focus order must not change.

Reduced motion: when the system animation duration scale is 0, do not scale at all (not a very fast scale) and make sure the click still fires; base every duration on the app's shared motion tiers multiplied by that scale rather than on hard-coded literals.
```

---

## 005 ♥ 与播放 / 暂停的状态形变

### Selected interaction

No matching pattern（没有匹配模式：模式 7 的"开关联动涟漪"是让相邻开关跟着变，本项目明确**不**要那个语义）

### Why this fits

这是"一个局部状态变了，只让这颗心自己说清楚"，不涉及周围内容让位，也不该产生任何联动。

### Interaction behavior

触发方式：点 ♥ 或播放 / 暂停。开始状态：♥ 为描边、播放键显示 play。变化过程：描边淡出、实心淡入
（150 / 200 ms 交叉），同时 0.9 → 1.00 的弹性放大；播放键的图标纵向换位（100 / 120 ms，新的从下进、
旧的从上出）并伴随 0.92 → 1.00 的一次性 pop。最终状态：与数据状态一致。取消 / 失败状态：
动画中途反向、或同一帧内连点多次时，终态必须等于数据状态、不叠成一串、不崩。
触控区域：不小于 48dp。

### Copyable prompt

```text
Use the selected interaction pattern in my existing interface. Preserve the current visual style, layout language, and component structure. Do not rewrite unrelated components. Implement the trigger, state changes, start and end states, motion behavior, responsive behavior, keyboard accessibility, touch support, and reduced-motion fallback described below:

Replace two hard icon swaps in an existing Jetpack Compose Android music player with state transitions. Do not restyle the surrounding screens and do not change any layout size.

1. Favorite heart. Extract one shared component used in four places: the player screen top bar, the last position of a track row, the corner of a playlist cover (where it needs its own 32 dp circular backing because cover colors are unpredictable), and the playlist detail top bar. On tap, cross-fade the outlined heart out and the filled heart in (150 ms on, 200 ms off) instead of swapping images, and run a one-shot 0.9 -> 1.00 scale pop with a medium-bouncy spring (damping ratio 0.4, stiffness 380). Single tap target, at least 48 dp, with the ripple clipped to the shape. Read the state ONLY once for screen readers: expose one content description that changes with the state (e.g. "add to liked songs" / "remove from liked songs"), never both stacked icons, so a screen reader does not read the labels of both layers.
2. Play / pause button. Extract one shared component used by the full player (large circle) and the mini player bar (small circle), with the hit area taken as max(diameter, 48 dp) so the small circle still meets the touch minimum. On tap, slide the two glyphs vertically through each other (new glyph enters from below, old glyph leaves upward, 100 ms in / 120 ms out, with matching fades) and run a one-shot pop from 0.92 to 1.00 with the same medium-bouncy spring. Disable the default size animation of the animated content container so it does not participate in layout.

Data vs visual state: the real state is written to the repository on the tap frame; the animation only expresses that change and never delays it. Rapid taps must not queue a series of animations — each new round cancels the previous one (an Animatable gives this for free). Reversing mid-animation must land on the true data state, not between the two visuals. Do not make neighbouring rows or cards react to a favorite toggle: that would present a local state change as a global event. Do NOT add any always-on breathing or progress animation to the play button: an always-running animation repaints every frame and this app has measured it pushing the refresh rate from 60 to 120 Hz, which collides with the lyric sweep on the same screen.

Responsive and input: touch, keyboard, and switch-access activation must all end in the same state; the components must keep their existing semantics labels and roles, and must not rely on hover. Keep the existing "remove from saved playlists" heart on the saved-playlists row as a plain filled icon button: it only ever has one state there, so there is nothing to cross-fade.

Reduced motion: when the system animation duration scale is 0, both components must jump straight to the end state in one frame while the tap still performs its action and still writes the state.

Add instrumented tests for: five taps within a single frame landing on the true final state, a tap that reverses mid-cross-fade, and a play/pause tap that reverses while the glyphs are still travelling.
```

---

## 006 页码指示器

### Selected interaction

**Expanding Tag Selection** | 标签挤开

### Why this fits

它回答的是"周围内容要不要一起让位"：当前那一项变宽，邻居必须主动挪开且不能重叠。这是全应用里
唯一一处真正的落点，所以是本片的主交互。

### Interaction behavior

触发方式：左右翻播放页（两页）。开始状态：当前点 7dp 圆、其余点 7dp 圆、等距。变化过程：
当前点用 `scaleX` 从 0.39 长到 1.0（视觉宽 7dp → 18dp），其余点各自沿 X 平移让位，
单点位移 ≤ 5.5dp。最终状态：药丸完全展开，其余点无重叠、整行不位移。取消 / 失败状态：
翻到一半又翻回来时，尺寸与位置都跟着 `currentPage` 走，不留中间态；`scale ≤ 0.1` 时当帧到位。
触控区域无关（指示器不可点）。

### Copyable prompt

```text
Use the selected interaction pattern in my existing interface. Preserve the current visual style, layout language, and component structure. Do not rewrite unrelated components. Implement the trigger, state changes, start and end states, motion behavior, responsive behavior, keyboard accessibility, touch support, and reduced-motion fallback described below:

Turn the page indicator of an existing Jetpack Compose Android music player, which currently just recolors the active dot, into an expanding-dot indicator. The indicator sits under a two-page horizontal pager (cover page and lyrics page) and is not tappable, so touch handling does not change.

Start state: every dot is a 7 dp filled circle, evenly spaced. End state: the active dot is an 18 dp pill and the other dots have moved aside without overlapping and without the whole row shifting.

Motion: drive everything from the pager's current page. Animate the active dot's width with a graphicsLayer scaleX from 0.39 to 1.00 (never animate its size, because a layout-size animation re-measures its neighbours mid-animation and makes the row jitter), and give every other dot an explicit translationX of at most 5.5 dp so neighbours do not overlap and nothing jumps. Use a small overshoot spring for this small element (damping ratio 0.8, stiffness 800) and combine it with the app's small motion tier for any tween-based part. When the pager moves back mid-swipe, the dot must follow the current page and must not be left between states.

Do not read the pager's current-page offset fraction in this change: following the drag belongs to a later change, and mixing the two now makes both unverifiable.

Responsive and input: the row must stay centered and must not change height; nothing here is clickable, draggable, or focusable, and screen readers must not announce the indicator. The layout must not change on narrow or wide screens or in landscape beyond the existing dot spacing.

Reduced motion: when the system animation duration scale is 0, the active dot must be at its pill size and the others at their final offsets in the very first frame, with no partial state.

Do not restyle the pager, the cover page, the lyrics page, or any other component. Base all durations and springs on the app's shared motion tokens rather than local literals.
```

---

## 007 列表增删让位

### Selected interaction

No matching pattern（**明确不套模式 8 Curved Card Deletion**）

### Why this fits

模式 8 要求"卡片飞向一个回收区域、先播动画再改数据"。本项目**没有删除去向**：行是直接消失的，
所以时序必须反过来（数据先删、视觉后收），语义不成立。按普通实现处理。

### Interaction behavior

触发方式：从我喜欢 / 我的歌单 / 收藏的歌单 / 音源页 / 搜索分组里移除一项。开始状态：数据已删，
被删项还在淡出、下面的项还在原位。变化过程：下面各项 `translationY` 滑向新位置
（medium 档），被删项淡出。最终状态：列表稳定在新布局，滑动位置正确。取消 / 失败状态：
被删项的语义节点会在下一帧就消失（实测），所以中途查询它的位置没有意义；动画期间连删多项时
终态仍等于数据状态。触控区域无关。

### Copyable prompt

```text
Use the selected interaction pattern in my existing interface. Preserve the current visual style, layout language, and component structure. Do not rewrite unrelated components. Implement the trigger, state changes, start and end states, motion behavior, responsive behavior, keyboard accessibility, touch support, and reduced-motion fallback described below:

Add item insertion and removal animation to the lazy lists of an existing Jetpack Compose Android app, without changing any row content, size, or style.

Behavior: when an item is removed, the items below it must slide up to their new positions (about 250 ms) while the removed item fades out (about 300 ms); when an item is inserted, the items below must make room the same way. The data layer must not wait for the animation: remove the item from the data first, then let the list animate to the new layout, because this app has no "where the card is flying to" destination — the row simply disappears and the animation only expresses a change that already happened.

Scope: apply this to lists backed by local data with stable unique keys (liked songs, my playlists, saved playlists, sources, and the search result groups). For lists backed by remote paging where keys are not stable or not unique, animate the placement only and do not supply a key, so the list is not rebuilt wholesale.

Framework constraint: the framework's own item animation uses two springs whose durations are not scaled by the system animator duration scale. Wrap it so that when that scale is 0 (treat <= 0.1 as 0) the item animation is fully disabled — no fade in, no fade out, no placement animation — and rows simply appear at their final positions in the first frame. Do not attempt to run a very fast version of the animation.

Constraints: do not animate layout-participating properties; keep hit areas and ripple clipping as they are; do not rewrite unrelated components or screens; keep the existing snackbar that confirms the removal.

Responsive and input: scrolling during the animation must stay smooth and must not shift the scroll anchor; the animation must not change focus order, must not steal focus, and must not depend on pointer or hover input. Screen readers must still be able to read the remaining items.

Add instrumented tests that advance the clock frame by frame and compare the position of the row below the removed one: with the motion scale at 0 the row must be at its final position in the very first frame, and with the scale at 1 it must still be travelling two frames later. Do not assert on the existence of the removed item's semantics node: this app measured that the node disappears on the next frame even while its exit animation is running, so node existence cannot distinguish the two cases.
```

---

## 008 错峰揭示（辅助）与 Snackbar

### Selected interaction

主交互：No matching pattern（没有匹配模式）。辅助：借用 **Staggered Bulk Selection** 的
**错峰机制**（不是它的勾选语义）

### Why this fits

需求是"一屏里好几块内容出现时要有先后"，属于模式 3 的"落在哪里"那一半，但没有批量勾选这个
交互，所以只借机制。Snackbar 是"提示不要重排列表"，十个模式里没有对应项。

### Interaction behavior

触发方式：进入需要成组加载的页面（搜索、首页、榜详情），或触发一次提示。开始状态：元素已在树里、
`alpha = 0`、`translationY = +8dp`。变化过程：按序号错开，第 n 项延迟 `min(n × 40, 240)ms` 开始，
每项在 medium 档内把 alpha 拉到 1、把位移收回 0。最终状态：全部可见、位置与无动画时一致。
取消 / 失败状态：数据本来就没到时不揭示空壳；`scale ≤ 0.1` 时直接算"已揭示"，连一帧
`alpha = 0` 都不该有。触控区域：揭示期间元素即可点。

### Copyable prompt

```text
Use the selected interaction pattern in my existing interface. Preserve the current visual style, layout language, and component structure. Do not rewrite unrelated components. Implement the trigger, state changes, start and end states, motion behavior, responsive behavior, keyboard accessibility, touch support, and reduced-motion fallback described below:

Add a staggered reveal to the grouped content blocks of an existing Jetpack Compose Android app, and leave the snackbar behavior alone. Do not restyle or resize anything.

Reveal behavior: when a screen shows several content blocks at once (the two rows on the home screen, the pinned hint panel in the lyrics area, the header and footer of a leaderboard detail screen, the result groups of a search screen), each block animates in from alpha 0 to 1 and from a +8 dp offset to 0 over about 250 ms, starting after a per-index delay of min(index * 40 ms, 240 ms). The 240 ms cap matters: without it a list with twenty items makes the last one wait 800 ms and users read that as a freeze.

Constraints: the delay is visual only and must never block data visibility — the elements are in the tree and the data is already available from the first frame. Use only graphicsLayer alpha and translationY, never layout-participating properties, so the lazy list's scroll anchor is not pushed around. Elements must be tappable during the reveal.

Reveal must be applied per content group, not per list item, and must not re-run on every recomposition or on configuration change (only when the group first appears).

Responsive and input: no change to layout width, height, or spacing, and no dependency on pointer or hover; focus order and screen-reader output must be identical to the non-animated case.

Reduced motion: when the system animation duration scale is 0, the content must be revealed in the very first frame — not even a single frame at alpha 0, because otherwise the "do not animate" setting still makes content appear to flash in.

Snackbar: keep the framework's default snackbar host and its built-in fade and scale transition exactly as it is. Do not re-implement the snackbar host, do not add custom enter or exit transitions, and do not move the snackbar into the scrolling content: it must keep rendering above the mini player bar without reflowing the list.
```

---

## 009 主题切换涟漪

### Selected interaction

**Radial Theme Transition**（降级形态） | 圆形主题切换

### Why this fits

它回答"变化应该从哪里开始"——从用户按下色板的那一点。本版只用它的**视觉形状**（从点击点扩散的圆），
不用它的"揭示新界面"职责：真实配色在 t = 0 就生效。

### Interaction behavior

触发方式：在设置页点一个色板。开始状态：配色已经换成新色；覆盖层上从点击点出现一圈半径为 0 的
新主色圆。变化过程：250 ms 内半径从 0 长到"到覆盖层四角的最远距离"，alpha 从 0.35 线性降到 0。
最终状态：涟漪消失，覆盖层不残留任何像素、不吃触摸。取消 / 失败状态：连点两个色板时，
新的涟漪从新的点重新起跳，不接在半路的进度上；`scale ≤ 0.1` 时一圈都不画。触控区域：
覆盖层不吃任何触摸事件。

### Copyable prompt

```text
Use the selected interaction pattern in my existing interface. Preserve the current visual style, layout language, and component structure. Do not rewrite unrelated components. Implement the trigger, state changes, start and end states, motion behavior, responsive behavior, keyboard accessibility, touch support, and reduced-motion fallback described below:

Add a theme-change ripple to the settings screen of an existing Jetpack Compose Android app. The theme here is a color scheme only; layout, typography, and shapes do not change when the theme changes. Do not restyle the settings screen or any other screen.

Behavior: tapping a color swatch changes the app color scheme immediately and, at the same moment, draws one expanding circle of the NEW primary color on top of the screen. Start state: radius 0 at the exact tap position, alpha 0.35. End state: radius equal to the greatest distance from the tap point to the four corners of the overlay rect, alpha 0, then the overlay draws nothing at all. Duration about 250 ms with a fast-out-slow-in easing.

Implementation requirements: the overlay is a single full-size canvas that is ALWAYS in the composition (not added only when a ripple plays), so its position in the window is already measured before the first ripple and the first frame cannot be drawn at the wrong origin; convert the tap position into root coordinates and subtract the overlay's own root position when drawing. The overlay must not consume pointer input, must not affect measurement, and must be clipped to the rect of the settings route rather than the whole window. Use the current preset's primary color for the ripple, picking the light or dark variant from the current surface luminance.

Do not implement a true radial reveal: do not build a second composition of the new theme, do not take a PixelCopy snapshot of the old UI, and do not clip between two theme trees. The theme change is color-only, so a snapshot's cost on the first frame is not worth its benefit, and it would compete with this app's first-frame performance budget.

Responsive and input: exact tap coordinates must be used on any screen size and orientation; the ripple must be purely decorative and must not change focus order, keyboard navigation, screen-reader output, or any hit target, and it must not appear when the swatch is activated by keyboard rather than by touch (in that case just change the theme).

Reduced motion: when the system animation duration scale is 0, do not draw the ripple at all — the color change alone is the feedback. Repeated taps must restart the ripple from the new origin rather than layering two ripples.
```

---

## 与 M10 的衔接

`v0.9.3` 的五个切片（共享元素封面、pager 视差、seek 惯性吸附、迷你条换曲文字）各有自己的
`PLAN.md`；共享元素的决策在 [`../../adr/0010-共享元素转场.md`](../../adr/0010-共享元素转场.md)。
