package io.github.sakurajay.lyra.ui.player

/**
 * seek 的惯性投影（013，模式 4 Velocity-Based Slider Snap）。
 *
 * 全项目只有这一处真正匹配模式 4：用户在滑杆上选一个目标值，而**释放速度里带着意图**
 * （快速一甩 = 想跳更远）。这里只收算术，手势接线在 `SeekRow` / [SeekDragState]，
 * 因为这三件事都能不碰 Compose 就判对错。
 *
 * **与模式定义的关键偏离（工单里写明，不是 bug）**：模式 4 原本要求"过冲之后用弹簧回到
 * 最近刻度"。播放进度轴上没有刻度可回弹，所以这里的过冲**就是真实落点**——投影出来的值
 * 直接 `seekTo` 过去。弹回手指处等于惯性是假的；弹回一个与投影无关的位置等于用户不知道
 * 自己在选什么。保留的是这个模式真正要紧的两条硬约束：释放速度被**测**出来，
 * 结果被 clamp 在合法区间。
 */
object SeekInertia {

    /** 一次甩动能带出去的时间上限：再快也只跳这么远，免得一甩跳到歌尾 */
    const val MAX_OFFSET_MS = 3_000L

    /**
     * 速度的"携带时长"：投影偏移 = 释放速度 x 这个数。
     *
     * 为什么是一个常数而不是一条衰减曲线：这一处要的判据是"甩得快跳得远"，不是"抛物线形状对"。
     * 一个常数能被手指调（调大更飘），衰减曲线的参数没人调得动。
     *
     * **它真正的产品含义是"多快算甩到顶"**。把单位换回手指能控制的东西：轨道约 974px
     * （`adb shell` 量到的 `[53,1388] → [1027,1504]`），一首 4 分钟的歌满程 240s
     * ⇒ 1px ≈ 246ms 时间轴。上限在
     * `v = MAX_OFFSET_MS x 974 / (DURATION x CARRY_MS) ≈ 1.0 px/ms`（约 1000 px/s）处 bind，
     * 也就是"一秒内把整条进度轴走一遍"那一档——只有真甩出去才碰得到。
     * 拿这个数反推更慢的档：0.5 px/ms 约跳 1.5s、0.2 px/ms 约跳 0.6s，**分级**因此保住了。
     *
     * 反面教材是我最初定的 120：上限会在约 0.1 px/ms（慢吞吞地拖）就 bind，
     * 于是"任何一次拖动都正好跳 3 秒"，惯性从"甩得快跳得远"退化成一个非黑即白的开关。
     *
     * **一个已知的副作用，写下来而不是藏起来**：速度是以时间轴毫秒计的，所以同样 1 px/ms
     * 的手速，在长歌上换算出的投影比短歌大（长歌每一像素代表更多毫秒）。
     * 于是 [MAX_OFFSET_MS] 在长歌上更容易 bind、在短歌上几乎从不 bind
     * （30s 的歌要 8 px/ms 才碰上限）。这是"按时间投影"这个定义自带的性质，
     * 与 ±3s 这个绝对上限放在一起读是合理的：3s 在一首 4 分钟的歌上是"微调"，
     * 在 30s 的短片上本来就是一大步，不该用同一个绝对数要求两边都"轻微"。
     */
    const val CARRY_MS = 12L

    /** 估速度只看松手前这么久之内的位移（模式 4 的实现要点：不要用整段拖动的平均速度） */
    const val VELOCITY_WINDOW_MS = 100L

    /** 一次时间采样：[timeMs] 时刻播放位置在 [positionMs] */
    data class Sample(val timeMs: Long, val positionMs: Long)

    /**
     * 用松手前最近 [windowMs] 之内的位移估释放速度（时间轴毫秒 / 墙钟毫秒）。
     *
     * 窗口是 `[nowMs - windowMs, nowMs]`，`nowMs` 由调用方给（拖动中传最新样本的时刻，
     * 松手时传抬起事件的时刻）。**不**从"最新样本往前数 100ms"推：快速拖一段、停一下、
     * 再慢松手，按最新样本会得到那次甩动的速度（于是要跳），按松手时刻会发现窗口里
     * 只有那一个静止点（于是不跳）。释放速度的定义是"松手那瞬间手指有多快"，
     * 停住就已经是 0，所以后者才对。
     *
     * 另一端取**窗口内最老的那一点**，不是倒数第二个样本：拿满 100ms 的位移除以 100ms
     * 比两个相邻帧相减稳——后者在 60Hz 下只用 16ms 的位移估速度，一帧的抖动就是 100% 的误差。
     * 拖动很短时所有点都在窗口里，用的就是整段，那也是对的（整段本来就只发生了不到 100ms）。
     *
     * **窗口里凑不出两个点就返回 0**，不退回"用窗口外那一点算个慢速度"。这一条同时办成
     * 两件必须办成的事：
     * - 慢速停一下再松手 -> 不投影（慢松手的语义就是"我要停在这"）；
     * - 键盘 / TalkBack 设值那一条路径 -> 根本没有样本（见 [SeekDragState.note]），
     *   速度 0 -> 不投影。PLAN 里"键盘保持自己的 1% 步长"靠的就是这个，
     *   不靠 `SliderState.isDragging`——那个标志位在 `onDragStopped` 之前就被 `drag()`
     *   清掉了（material3 1.4.0 自己的注释 "check isDragging in case the change is still
     *   in progress" 说的就是这件事），拿它当门槛会时好时坏。
     */
    fun velocity(samples: List<Sample>, nowMs: Long, windowMs: Long = VELOCITY_WINDOW_MS): Float {
        if (samples.size < 2) return 0f
        val cutoff = nowMs - windowMs
        val inside = samples.filter { it.timeMs >= cutoff && it.timeMs <= nowMs }
        if (inside.size < 2) return 0f
        val dt = inside.last().timeMs - inside.first().timeMs
        if (dt <= 0L) return 0f
        return (inside.last().positionMs - inside.first().positionMs).toFloat() / dt.toFloat()
    }

    /**
     * 松手时该落到哪：手指位置 + 速度投影，先夹投影幅度、再夹进合法区间。
     *
     * `animate = false`（系统动画缩放关掉）**跳过投影**直接落在手指处：惯性是动效带来的
     * 反馈，不是数值选择的一部分，关了动画的人没有要它。不投影也照样夹——
     * "落在手指处"不等于"可以落在时长之外"。
     */
    fun projectSeek(
        releaseMs: Long,
        velocityMsPerMs: Float,
        durationMs: Long,
        maxOffsetMs: Long = MAX_OFFSET_MS,
        animate: Boolean = true,
    ): Long {
        val end = durationMs.coerceAtLeast(0L)
        if (!animate || !velocityMsPerMs.isFinite()) return releaseMs.coerceIn(0L, end)
        val raw = velocityMsPerMs * CARRY_MS
        // 先夹幅度：让"最快也就跳 3 秒"这条承诺与时长无关，
        // 也让一首 30 秒的歌不至于因为区间更紧而显得"惯性突然变弱"
        val offset = if (raw > maxOffsetMs) maxOffsetMs else if (raw < -maxOffsetMs) -maxOffsetMs else raw.toLong()
        return (releaseMs + offset).coerceIn(0L, end)
    }

    /**
     * 时间气泡该摆在哪儿，返回它的**左缘**像素，使它水平居中在拇指上、又完整留在这一行里。
     *
     * 两端必须夹住：`fraction = 0` 时若只按"居中"算，气泡有半截（约 30dp）会伸出行外，
     * 而越接近歌首 / 歌尾用户越会盯着气泡看——恰好是最不能切掉的地方。
     *
     * [handleInsetPx] 是拇指中心相对行左缘的固定内缩。material3 把值域映到
     * `[thumbWidth/2, rowWidth - thumbWidth/2]`（见 `SliderState.dispatchRawDelta`），
     * 所以中心在 `inset + f x (rowWidth - 2 x inset)`；传 0 就退化成 `f x rowWidth`。
     * 行比气泡还窄时右边界夹成 0（`coerceAtLeast`），居中放但不会出现负左缘。
     */
    fun bubbleLeftPx(fraction: Float, rowPx: Float, bubblePx: Float, handleInsetPx: Float = 0f): Float {
        if (rowPx <= 0f || bubblePx <= 0f) return 0f
        val span = (rowPx - 2f * handleInsetPx).coerceAtLeast(0f)
        val center = handleInsetPx + fraction.coerceIn(0f, 1f) * span
        return (center - bubblePx / 2f).coerceIn(0f, (rowPx - bubblePx).coerceAtLeast(0f))
    }
}
