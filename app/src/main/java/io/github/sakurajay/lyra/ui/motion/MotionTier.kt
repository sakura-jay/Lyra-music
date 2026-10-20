package io.github.sakurajay.lyra.ui.motion

/**
 * 动效层级（见 ADR-0009）。
 *
 * 只有这四档 + 一档路由转场。任何一页都不该再自己写 `tween(230)` 这种数：
 * 参数散在 20 个文件里时，"这里弹一下那里滑一下"的割裂感没法收敛，
 * 而且系统动画缩放要生效就必须逐处改——那正是本次要消灭的东西。
 *
 * 每个数字都是**基准值**，真实时长由 [MotionScale] 乘算并夹紧：
 * 用户把系统动画时长调到 0 时全部退化为瞬时（不是"很短的动画"）。
 */
enum class MotionTier(val enterMs: Int, val exitMs: Int) {

    /** 触觉级反馈：按压形变、图标 morph、勾选弹跳。手指还在屏幕上，所以必须比眨眼快 */
    MICRO(100, 120),

    /** 单个元素的小幅状态变化：页码指示器、文字交替、chip */
    SMALL(150, 200),

    /** 涉及布局让位或成组揭示：列表增删、Snackbar、加载错峰、主题涟漪 */
    MEDIUM(250, 300),

    /** 跨页面的连续性表达，本版只给 M10 的共享元素；封面模糊交叉的 450 沿用现值 */
    LARGE(350, 450),

    /**
     * 路由转场的既有数值。
     *
     * 单独一档而不是归进 LARGE：这组数是三轮真机验收调出来的（播放页升起 280、
     * 缩回 240），本版不动它。层级表把它钉在这里，是为了让"不动"这件事
     * 有地方记录，而不是靠注释散在三个文件里各自解释一遍。
     */
    ROUTE(280, 240),
}

/**
 * 系统动画时长的可用档位（`Settings.Global` 的 duration scale）。
 *
 * 只认到 1：设成 1.5/2.0 的人要的是"系统动画更慢"，不是"整个应用变慢"，
 * 而我们大量动画是反馈类动画——放大只会让手感变迟钝（ADR-0009 的取舍）。
 */
object MotionScale {

    /** 夹紧后的上限：比 1 大的系统缩放一律按 1 处理 */
    const val MAX = 1f

    /** 低于这个值就当"关掉动画"：0.1x 的动画在视觉上与瞬时没有区别，却仍要付一帧的代价 */
    const val SNAP_EPSILON = 0.1f

    /**
     * 两个系统缩放取较小值，再夹紧到 `[0, 1]`。
     *
     * 取较小而不是平均：`ANIMATOR_DURATION_SCALE`（属性动画）与
     * `TRANSITION_ANIMATION_SCALE`（窗口/过渡动画）是两个独立开关，
     * 用户把任一个调到 0 都是明确的"别动"表态，平均会把这个表态洗掉。
     * 读不到（负值 / NaN）时按 1 处理——不能因为一个解析异常就把界面变成幻灯片。
     */
    fun clamp(animatorScale: Float, transitionScale: Float): Float {
        val candidates = listOf(animatorScale, transitionScale).filter { it.isFinite() && it >= 0f }
        val raw = candidates.minOrNull() ?: return 1f
        return raw.coerceIn(0f, MAX)
    }

    /** 0 与极小值一律算"关掉" */
    fun isSnapped(scale: Float): Boolean = scale <= SNAP_EPSILON

    /**
     * 平台**自己**会乘的那一份。
     *
     * 真机量出来的事实（见工单 002 的"实测结论"）：`animation-core` 的
     * `doAnimationFrameWithScale` 把每一帧的墙钟时间除以 `MotionDurationScale.scaleFactor`
     * 再喂给动画，而这个 factor 由 `WindowRecomposer` 在创建时读
     * `Settings.Global.animator_duration_scale` 填进去。也就是说 **tween 与 spring 都会被
     * 平台按这一档缩放一次**，我们不需要（也不能）再乘一遍。
     *
     * 平台只读 `animator_duration_scale`：`transition_animation_scale` 那一档它不管，
     * 上限也不夹紧（2.0 就是两倍长）。这两件事才是本对象真正要补的。
     * 读不到时按 1 处理，与 [clamp] 同一条理由。
     */
    fun platformFactor(animatorScale: Float): Float =
        if (animatorScale.isFinite() && animatorScale > 0f) animatorScale else 1f

    /**
     * 该写进 spec 的乘数 = 我们想要的倍率 ÷ 平台已经乘过的那一份。
     *
     * 不除掉就是**双重缩放**：`animator = 0.5` 会变成 0.25 倍速，`animator = 2.0`
     * 会变成 2 倍长（我们夹到 1.0，但那一份是平台乘的，夹不掉）——正好把"1.5/2.0 不放大"
     * 这条表态反着执行了。实测见工单 002：同一动作 950ms → 1750ms。
     *
     * 三个性质值得写下来，因为它们是这个除法能安全存在的原因：
     * - [intent] 已夹紧到 `[0, 1]` 且 ≤ `animator`，所以比值天然 ≤ 1，不需要再夹上限；
     *   比值也不会是负数（[clamp] 与 [platformFactor] 都不放行负值 / `NaN`）。
     * - `intent` 为 0（含极小值）时直接给 0，让上层走 snap 而不是"除完还剩一个很小的数"；
     *   这条早退同时挡住了 `animator = 0` 时的除零。
     * - 结果喂给 [MotionSpec.durationMsOf] 之后，墙钟时长恰好等于 `基准 × intent`：
     *   平台那一份被除掉，又被平台乘回来。
     */
    fun specMultiplier(animatorScale: Float, transitionScale: Float): Float {
        val intent = clamp(animatorScale, transitionScale)
        if (isSnapped(intent)) return 0f
        return intent / platformFactor(animatorScale)
    }

    /**
     * 弹簧该用的刚度倍率：`1 / scale²`。
     *
     * 弹簧没有"时长"可乘，但**有刚度可改**，而平台缩放的是帧循环里的时间轴
     * （`doAnimationFrameWithScale` 是 tween 与 spring 共用的一条路），所以弹簧一样会被
     * 平台按 `animator` 缩放一次。简谐振动周期 ∝ `1/√stiffness`，把刚度乘 `1/scale²`
     * 就等价于把时长乘 `scale`：再被平台乘回 `factor`，墙钟时长恰好是 `基准 × intent`，
     * 与 tween 走的是同一个等式。
     *
     * 代入几个档（`scale` 是 [specMultiplier] 的结果，恒 ≤ 1，所以倍率只增不减）：
     * `1` → 1（不动，默认档下弹簧与改动前一字不差）；`0.5` → 4 倍刚度；
     * `0.1`（还能播的下限）→ 100 倍，即"一帧到位"。
     */
    fun springStiffnessScale(scale: Float): Float = when {
        // 读不到、0 与负值到这里只可能是"关掉动画"，那条路径上根本不会用到弹簧；
        // 给 1 免得除零，也让 NaN 不会顺着刚度流进动画
        !scale.isFinite() || scale <= 0f -> 1f
        scale >= 1f -> 1f
        else -> 1f / (scale * scale)
    }
}

/**
 * 把层级表换算成真实时长。
 *
 * 拆成纯函数是为了能在 JVM 上直接断言（不引 Robolectric，也不靠截图反推）：
 * 观感测不了，但"scale=0 时必须是 0ms、scale=0.5 时 250 必须变 125"是可以钉死的。
 */
object MotionSpec {

    /**
     * [enter] 取进入档，false 取退出档——退出普遍比进入长一档的说法不成立，见 enum 注释。
     *
     * [scale] 传的是 [MotionScale.specMultiplier] 的结果（已除掉平台那一份），
     * 不是系统设置的原始值。这条约定是 `LocalMotionScale` 的契约，见其文档。
     */
    fun durationMs(tier: MotionTier, scale: Float, enter: Boolean = true): Int =
        durationMsOf(if (enter) tier.enterMs else tier.exitMs, scale)

    /**
     * 层级表之外的基准时长换算（路由转场那几条既有淡入淡出）。
     *
     * 与 [durationMs] 是同一套语义：乘缩放、关掉动画给 0。放在这里而不是各写一遍
     * `(180 * scale).toInt()`，是因为"0 就是 0"这条规则一旦有第二份实现就会漂移。
     */
    fun durationMsOf(baseMs: Int, scale: Float): Int {
        // 关掉动画时给 0，而不是给一个 1ms 的 tween：前者是 snap 到终态，后者还要排一帧
        if (baseMs <= 0 || MotionScale.isSnapped(scale)) return 0
        return (baseMs * scale).toInt().coerceAtLeast(1)
    }

    /** 这条动画还需不需要播 */
    fun shouldAnimate(scale: Float): Boolean = !MotionScale.isSnapped(scale)

    /**
     * 错峰延迟：`min(index * step, cap)`。
     *
     * 必须有 cap：榜单一屏二十来项，没有上限时最后一项要等 800ms 才出现，
     * 用户会以为卡住了。总量上限约 240ms 之后，后面的项同时开始。
     */
    fun staggerOffset(index: Int, step: Int = STAGGER_STEP_MS, cap: Int = STAGGER_CAP_MS): Int {
        if (index <= 0) return 0
        val product = index.toLong() * step
        return (if (product > cap.toLong()) cap else product.toInt())
    }

    const val STAGGER_STEP_MS = 40
    const val STAGGER_CAP_MS = 240
}
