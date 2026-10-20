package io.github.sakurajay.lyra.ui.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import io.github.sakurajay.lyra.ui.player.SeekInertia.Sample

/**
 * seek 拖动期间"手指现在在不在滑杆上、这一下是不是手指松的"（013）。
 *
 * 只回答这两件事，因为投影必须**只属于手指**：键盘与 TalkBack 设值要保持自己的步长
 * （PLAN 与 013 提示词都写死了这条约束）。
 *
 * 为什么不用现成的两个标志位：
 * - `SliderState.isDragging`：`drag { }` 返回时就写回 false，而 `onDragStopped`
 *   （也就是 `onValueChangeFinished`）在它**之后**才调。material3 1.4.0 自己的注释写着
 *   "check isDragging in case the change is still in progress (touch -> drag case)"，
 *   说的就是这件事——所以松手那一刻读到的必然是 false，拿它当门槛会时好时坏。
 * - `collectIsDraggedAsState`：`DragInteraction.Stop` 是 tryEmit 进 channel，收集跑在
 *   另一条协程上，等状态翻转时我的回调早就执行完了。
 *
 * 于是自己看手势：挂在传给 Slider 的 modifier 上、**全程只走 Initial pass 且不消费事件**。
 * `SliderImpl` 把这串 modifier 加在它自己的 `drag` **外面**（`modifier…then(drag)`），
 * 所以本层在命中路径里是 draggable 的父节点；而一次 MotionEvent 的 Initial pass 先于
 * 全体节点的 Main pass 派发、且不受消费影响。顺序因此有保证：
 * down 与每个 Move 都先于那一次的 `onValueChange`，Up 先于 `onValueChangeFinished`。
 */
@Composable
fun rememberSeekDragState(): SeekDragState = remember { SeekDragState() }

/** 手指是否在滑杆上。只有按下与抬起两次翻转，所以可以在组合期读（气泡的显隐就读它） */
val SeekDragState.isPointerDown: State<Boolean> get() = pointerDown

/** 观察滑杆上的手指，但不消费任何事件（为什么必须自己看，见 [SeekDragState]） */
fun Modifier.lyraSeekPointerObserver(state: SeekDragState): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        state.begin()
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val changes = event.changes
            if (changes.isEmpty()) continue
            // 把这一次事件的时间戳交给状态：样本与释放时刻因此共用同一条轴
            // （不用 SystemClock——那是另一个钟，而且设备测试里没法喂）
            state.markEventTime(changes[0].uptimeMillis)
            // pressed == false 同时盖住"抬起"与"取消"
            if (changes.any { !it.pressed }) {
                state.end()
                break
            }
        }
    }
}

/**
 * 一次拖动的样本面板。
 *
 * 样本在 `onValueChange` 里记（[note]）而不是在手势层记：手势层只有像素位移，
 * 而投影要的是**播放位置**。时间戳由手势层给（[markEventTime]），两边同一条轴。
 */
class SeekDragState {

    internal val pointerDown = mutableStateOf(false)

    /** 这一次结束是不是手指结束的；[finishVelocity] 据此决定投影还是放行 */
    private var releasedByPointer = false

    private val samples = ArrayList<Sample>(24)

    // 这几个不挂 State：只在事件回调里读写，挂上去等于每个指针事件发一次无效通知
    private var eventTimeMs = 0L

    /** 松手那一刻的时间戳，[finishVelocity] 用它当速度窗口的右端 */
    private var releaseTimeMs = 0L

    internal fun markEventTime(uptimeMillis: Long) {
        eventTimeMs = uptimeMillis
    }

    internal fun begin() {
        pointerDown.value = true
        releasedByPointer = false
        samples.clear()
    }

    internal fun end() {
        pointerDown.value = false
        releasedByPointer = true
        releaseTimeMs = eventTimeMs
    }

    /**
     * 记一笔：只在手指按下时记。
     *
     * 键盘与 TalkBack 那两条路径走不到这里——它们不产生 down 事件，样本永远是空的。
     * 判据不是"猜这是不是键盘"，靠的是手上有没有 down 这个事实。
     */
    fun note(positionMs: Long) {
        if (!pointerDown.value) return
        samples.add(Sample(eventTimeMs, positionMs))
        // 一次长拖动会有几百个点，而速度只看最近 100ms：留着陈旧的没有意义
        val cutoff = eventTimeMs - SeekInertia.VELOCITY_WINDOW_MS
        while (samples.size > 2 && samples.first().timeMs < cutoff) {
            samples.removeAt(0)
        }
    }

    /**
     * 拖动进行中的速度（不清样本）：气泡要显示"若现在松手会落到哪"。
     *
     * 右端取**最新样本的时刻**：气泡随每次 `onValueChange` 重算，用最新样本才与刚记下的
     * 那几笔同一口径。手指一停，新样本的位置不再变、窗口内的位移归零，速度自然回到 0、
     * 气泡回到手指处——这是白给的性质，不需要额外判据。
     */
    fun liveVelocity(): Float {
        if (!pointerDown.value) return 0f
        return SeekInertia.velocity(samples, samples.lastOrNull()?.timeMs ?: 0L)
    }

    /**
     * 取走这一次拖动的释放速度并清空（下一轮不许用陈旧样本）。
     *
     * 返回 null = **这不是手指的结束**（键盘、TalkBack 设值），调用方据此不做投影。
     * 窗口右端用抬起那一刻的 [releaseTimeMs]，于是"快速甩一下、停住、再慢松手"得到 0
     * 而不是那次甩动的速度——释放速度的定义是松手那瞬间手指有多快
     * （见 [SeekInertia.velocity]）。
     */
    fun finishVelocity(): Float? {
        val byPointer = releasedByPointer
        val v = if (byPointer) SeekInertia.velocity(samples, releaseTimeMs) else 0f
        samples.clear()
        releasedByPointer = false
        return if (byPointer) v else null
    }
}