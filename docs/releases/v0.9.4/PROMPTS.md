# v0.9.4 实现提示词（逐片）

本文件只有两片（015 / 016），写法与 [`../v0.9.3/PROMPTS.md`](../v0.9.3/PROMPTS.md) 一致：
每片四段 **Selected interaction / Why this fits / Interaction behavior / Copyable prompt**。

两片都是**实现之后**补写的，与代码一致，可以当 review 依据。

两片都判定为**没有匹配模式 → 普通实现**：015 是渲染层降采样，
016 是缓动曲线选择，十个高级模式（圆形主题切换、拖拽排序、批量勾选、滑杆惯性吸附、
文本展开、步骤条回弹、开关联动涟漪、卡片曲线删除、卡片堆叠滚动、标签挤开）
描述的都是"用户操作引发的状态变化"，没有一种描述"同一层绘制怎么更便宜"
或"同一段位移的速度曲线长什么样"。

---

## 015 `BlurBackdrop` 降采样

### Selected interaction

No matching pattern（没有匹配模式）

### Why this fits

这是纯渲染层的优化：模糊发生在更小的图层上，再放大画回全屏。
用户看到的交互行为一个字不变，所以不存在"从哪个交互模式里选"的问题。

### Interaction behavior

触发方式：进入播放页 / 换曲换封面（`source` 变化）。
开始状态：两层位图交叉淡出中，全屏 `RenderEffect` 模糊 26dp。
变化过程：模糊层改为 1/4 边长（`fillMaxSize(0.25)`）、半径同除（`blur(radius × 0.25)`），
外面套一层 `graphicsLayer` 以 `TransformOrigin(0, 0)` 放大 4 倍画回全屏。
最终状态：观感不变（高斯模糊与缩放近似可交换），被处理的像素降到 1/16。
触控区域：不吃触摸，播放页控件层级不变。
reduced-motion：交叉淡出接 `LocalMotionScale`；scale=0 时整条交叉不播（`snapTo(1f)`）。
不做：radius 动画、布局尺寸变化、任何依赖新增。

### Copyable prompt

```text
Use the selected interaction pattern in my existing interface. Preserve the current visual style, layout language, and component structure. Do not rewrite unrelated components. Implement the trigger, state changes, start and end states, motion behavior, responsive behavior, keyboard accessibility, touch support, and reduced-motion fallback described below:

Target component: BlurBackdrop in app/src/main/java/io/github/sakurajay/lyra/ui/player/BlurBackdrop.kt — a full-screen blurred artwork backdrop on the player screen. It draws two bitmap layers (bottom = outgoing artwork, top = incoming artwork) with a 450ms alpha crossfade; blur radius stays fixed at 26dp for the whole animation.

Problem to solve: the full-screen RenderEffect blur is the dominant GPU cost of busy frames on the player screen. Measured on PLK110 release build: busy-frame GPU p50 7.82ms with 62.0% of busy frames over the 8.33ms 120Hz budget.

Change to make:
1. Render the blur at 1/4 size. Each BlurLayer fills only 25% of the container size (Modifier.fillMaxSize(0.25f)) and applies blur(radius * 0.25f). Keep radius at its current default 26dp — only the internal scaled value changes.
2. Wrap the two BlurLayer calls in a parent Box with Modifier.fillMaxSize().graphicsLayer { transformOrigin = TransformOrigin(0f, 0f); scaleX = 1f / 0.25f; scaleY = 1f / 0.25f }. The scaling must be on the parent layer and the blur on the child layer. If you put the scale inside the blurred child, RenderEffect applies to already-scaled content and the cost returns to the original.
3. Rationale: Gaussian blur and scaling approximately commute — blurring at 1/4 size then scaling up 4x looks equivalent to blurring at full size. Pixel count drops to about 1/16. The source bitmap is already downsampled to ~256px before reaching this layer, so the extra bilinear upscaling is an order of magnitude finer than the 26dp blur.
4. Wire the 450ms crossfade to the motion scale system: read LocalMotionScale.current and compute the duration with MotionSpec.durationMs(MotionTier.LARGE, scale, enter = false). The LARGE exit tier base is 450ms, so at scale = 1 behavior is unchanged. When MotionSpec.shouldAnimate(scale) is false, call crossfade.snapTo(1f) instead of animateTo — do not animate when motion is off.
5. Do not change: the two-layer crossfade structure, the fixed blur radius, any layout dimensions, the semi-transparent surface scrim on top, or any other component.

Reduced-motion fallback: when system animation scale is 0 (or below the snap epsilon), skip the crossfade entirely and snap the incoming layer to full alpha on the first frame.

Verification expectations: busy-frame GPU p50 should drop substantially (measured: 7.82ms to 4.03ms, 48% reduction; over-budget busy frames 62.0% to 2.8% on the 8.33ms 120Hz budget). Same-song same-artwork screenshots should show background bands pixel-identical within quantization noise (measured: meanAbsDiff < 1, gradient ratio 1.000).
```

---

## 016 共享元素回程缓动

### Selected interaction

No matching pattern（没有匹配模式）

### Why this fits

十个模式描述的都是"变化从哪里开始、谁跟着让位、操作停在哪里"，
没有一种描述"同一段位移的速度剖面应该长什么样"。
本片改的是飞行曲线的选择，交互结构（点卡片起飞、返回键飞回）一个字不变。

### Interaction behavior

触发方式：详情页按返回（011）/ 播放页收回到迷你条（010）。
开始状态：封面在页头大图位（96dp 或 230dp）。
变化过程：350/450ms 内飞回来路那张卡片，位置、尺寸、圆角插值方式全部不变，
只换缓动——回程从 `FastOutLinearInEasing`（加速）换成 `FastOutSlowInEasing`（减速落位）。
最终状态：精确落回卡片原矩形，无过冲。
去程（卡片 → 页头）保持 `LinearOutSlowInEasing` 不动。
触控区域：覆盖层不吃触摸，卡片与返回键的点击目标不变。
reduced-motion：`begin()` 在 `!shouldAnimate` 时直接 return，整件事不发生（既有行为）。
不做：时长改动、过冲、spring、路由转场数值改动。

### Copyable prompt

```text
Use the selected interaction pattern in my existing interface. Preserve the current visual style, layout language, and component structure. Do not rewrite unrelated components. Implement the trigger, state changes, start and end states, motion behavior, responsive behavior, keyboard accessibility, touch support, and reduced-motion fallback described below:

Target component: CoverFlight.kt in app/src/main/java/io/github/sakurajay/lyra/ui/common/ — a hand-written shared-element overlay. When the user taps a playlist/leaderboard card (or the mini player bar), the cover flies from the card's small rectangle to the detail page's larger rectangle; pressing back flies it back to the card it came from. Position, size, and corner radius are interpolated linearly by an eased progress value.

Problem to solve: the return flight uses FastOutLinearInEasing (an accelerating curve), so the cover reaches the destination card at peak speed. The user reports the return "looks bad, like constant speed" — frame-by-frame measurement shows it is actually worse: accelerating into the landing. Before the fix, the peak speed occurred at 93% of the return flight and the last 10% of time covered 12.6% of the distance.

Why Material's convention is wrong here: the "exits accelerate" convention assumes the exiting thing disappears — accelerating off-screen is fine because nobody watches where it lands. A shared element's return does not disappear; it must land precisely on a card that is in front of the user. The final segment is exactly the segment the eye is watching.

Change to make:
1. Extract the easing choice into a public pure function: fun coverFlightEasing(enter: Boolean): Easing = if (enter) LinearOutSlowInEasing else FastOutSlowInEasing. The forward direction keeps LinearOutSlowInEasing unchanged; only the return direction changes from FastOutLinearInEasing to FastOutSlowInEasing.
2. FastOutSlowInEasing is a cubic-bezier with no overshoot, which satisfies the guardrail that full-screen displacement must never overshoot (overshoot is reserved for small elements only). The slight acceleration at the start prevents the cover from feeling yanked; the middle is fastest; the tail decelerates into the landing.
3. Do not change: flight duration (LARGE tier, 350ms enter / 450ms exit base), position/size/corner interpolation, registration or live target tracking, the route transition values, or any other component.

Verification expectations: on-device frame-by-frame tracking should show the return peak speed moving from near the end (93%) to the middle (33%), and the last-10%-of-time distance share dropping from 12.6% to about 5%. The forward direction should be unchanged (measured control: half-way progress 77–84%, last-10% share 0–3% before and after).

Unit tests to add: (1) both directions must satisfy "distance in the last tenth of the curve < distance in the first tenth" — decelerate into the target; (2) the return direction must no longer be the accelerating one, with a regression guard that fails if changed back to FastOutLinearInEasing; (3) both curves must start at exactly 0 and end at exactly 1.

Reduced-motion fallback: when system animation scale is 0, the existing begin() early-return already prevents the flight entirely; keep that behavior unchanged.
```
