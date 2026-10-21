package io.github.sakurajay.lyra.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.github.sakurajay.lyra.ui.motion.LocalMotionScale
import io.github.sakurajay.lyra.ui.motion.MotionSpec
import io.github.sakurajay.lyra.ui.motion.MotionTier
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 封面共享（"共享元素"在本项目的落点，见 ADR-0010）。
 *
 * 做法是**方案 A**：路由转场一个数都不动，封面这一层单独交给覆盖层。
 * 两端各自把"我这块封面现在在屏幕上的哪个矩形、圆角多大"报到 [CoverFlightState]，
 * 触发时拿一侧的矩形当快照起点、另一侧当实时终点，覆盖层按进度重画一遍。
 *
 * 三个刻意的取舍：
 *
 * 1. **不插值布局**：覆盖层是整屏 `Canvas`，位置 / 尺寸 / 圆角都只体现在绘制参数上，
 *    没有任何一帧去改 `size()`——这是 ADR-0009 护栏二（尺寸一动画，周围的东西全在动）。
 * 2. **起点是快照、终点读实时**：起飞那一侧在转场一开始就从树上消失，起点只能是触发前
 *    量到的那一份；而目标页自己还在路由转场里滑，终点必须跟着它走，否则就是
 *    ADR-0010 里那句"两套位移打架"。
 * 3. **图没到就不飞**：插值一张占位图比硬切更糟，所以端点没有位图时整件事不发生，
 *    行为退化成今天的硬切。
 */

/**
 * 端点键。
 *
 * 010 只有两处固定端点（迷你条、播放页大图），一个枚举就够；011 要接的是
 * "很多张卡片 ↔ 其中一个详情页页头"，两端的数量都是变量，所以端点改成一张
 * **以封面身份为键**的登记表。
 *
 * - 详情端只有一个身份：这一页自己。键就是这一页的路径（`playlist/digest-8__123`），
 *   它由 `lyraDetailRoute` 提供、触发侧从同一个 `NavBackStackEntry` 算出，两边各自推导却必然相等。
 * - 卡片端要再带一层"我在哪一页"。这一层不是多余的谨慎：同一个歌单会同时出现在首页的推荐排
 *   与歌单广场上（两处都是 `provider.recommended`），同一个榜同时出现在首页预览与榜单广场。
 *   只按目的地分键的话，"从首页点进去、退回首页"可能飞到广场那张卡的旧坐标上去——
 *   而广场此刻根本不在栈里。带上来源页之后，每张卡只与它自己那一页上的自己对应。
 *
 * 用路径当键而不是用封面 URL 当键也是有原因的：同一条列表的卡片与页头可能各自拿到
 * 不同尺寸的封面地址（酷我的推荐列表给 `img`、歌单详情给 `pic`），按 URL 判同会把
 * 该飞的判成不飞；按路径判同还天然排除"A 的封面飞进 B 的页头"这种错配。
 *
 * `#` 不会出现在任何路由里，所以固定端点（迷你条、播放页）不会与路径键撞车。
 */
object CoverKeys {

    private const val LIST = "|#list"
    private const val DETAIL = "|#detail"
    private const val SEPARATOR = ">"

    /** 卡片那一端：[page] 是它所在页面的路径，[path] 是它要跳去的路径 */
    fun source(page: String, path: String): String = "$page$SEPARATOR$path$LIST"

    /** 详情那一端（详情页头、播放页大图）：只有一个身份，就是这一页自己 */
    fun detail(path: String): String = path + DETAIL

    /**
     * 迷你播放条上的那块小封面。
     *
     * 它不对应任何跳转路径，所以两端各占一段不可能的路径（来路记成 `#player`、
     * 目的地记成 `#mini`）：路由不会以 # 开头，真卡片因此永远占不到这两把位子。
     */
    val MINI: String = source("#player", "#mini")

    /** 播放页的封面大图 */
    val PLAYER: String = detail("#player")
}

/**
 * 矩形插值。夹紧到 `[0,1]`：调用方可能因为动画回调的帧序把进度给到 1.0001，
 * 那一下会让封面冲出目标一两个像素——目标边缘正好是最容易被看出来的地方。
 */
fun coverFlightRect(from: Rect, to: Rect, fraction: Float): Rect =
    lerp(from, to, fraction.coerceIn(0f, 1f))

/** 圆角插值：卡片是 12dp、播放页大图是 22dp，不插值中途就会看见方角 */
fun coverFlightCorner(fromPx: Float, toPx: Float, fraction: Float): Float =
    fromPx + (toPx - fromPx) * fraction.coerceIn(0f, 1f)

/**
 * 飞行用的缓动：两个方向都必须**减速落位**。
 *
 * 原来写的是 `if (enter) LinearOutSlowInEasing else FastOutLinearInEasing`，套的是
 * Material 那套"入场减速、出场加速"的约定。但那条约定的前提是**出场的东西会消失**——
 * 加速离开屏幕是对的，因为没人会去看它落在哪儿。共享元素的回程不消失：它要精确落回
 * 来路上那张卡片，而卡片就在用户眼前。用加速曲线飞回程，等于**以最高速撞进终点**，
 * 观感就是"慢慢起、越走越快、到位置一脚刹车没有"。真机逐帧量出来（PLK110，同一次采集，
 * 帧距 16.61ms 已核实）：
 *
 * | 方向 | 前 10% 时间走完 | 半程 | 后 10% 时间走完 |
 * | --- | --- | --- | --- |
 * | 去程（卡→页头，减速） | 37% | 80% | 2% |
 * | 回程（页头→卡，加速） | 5% | 39% | 22% |
 *
 * 也就是回程最后六分之一秒里还在走全程的五分之一，而这一段正是眼睛盯着的那一段。
 *
 * 换成 `FastOutSlowInEasing`（标准减速落位，无过冲，符合 ADR-0009 护栏一：过冲只给小元素）：
 * 起步有一小段加速所以不像被拽走，中段最快，尾段减速落位。去程保持原曲线不动——
 * 那是被认可的那一条，且它本来就是减速落位。
 */
fun coverFlightEasing(enter: Boolean): Easing =
    if (enter) LinearOutSlowInEasing else FastOutSlowInEasing

/** 源位图里要画的那一块 */
data class CoverSourceRect(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * `ContentScale.Crop` 的算法，只是提前算成源矩形。
 *
 * `drawImage` 只会把整块源图拉进目标矩形，直接用它画非正方形封面就会把图压扁。
 * 参与的封面框都是正方形，所以这里按"短边对齐、长边居中裁掉"取一块，与两端的
 * `ContentScale.Crop` 完全一致。
 */
fun coverCropSource(srcWidth: Int, srcHeight: Int, dstWidth: Float, dstHeight: Float): CoverSourceRect {
    if (srcWidth <= 0 || srcHeight <= 0) return CoverSourceRect(0, 0, max(srcWidth, 0), max(srcHeight, 0))
    if (dstWidth <= 0f || dstHeight <= 0f) return CoverSourceRect(0, 0, srcWidth, srcHeight)
    val scale = max(dstWidth / srcWidth, dstHeight / srcHeight)
    val width = (dstWidth / scale).roundToInt().coerceIn(1, srcWidth)
    val height = (dstHeight / scale).roundToInt().coerceIn(1, srcHeight)
    return CoverSourceRect((srcWidth - width) / 2, (srcHeight - height) / 2, width, height)
}

/**
 * 端点最后一次量到的矩形、圆角与位图。
 *
 * 量到的是**布局**矩形，不含路由转场的图层位移——所以端点还挂在树上时一律现量
 * （见 [Entry.rectNow]），这一份只在端点已经离场之后当历史用。
 */
internal data class CoverAnchor(val rect: Rect, val cornerPx: Float, val artwork: ImageBitmap?)

/**
 * 一个端点：量到的快照 + 当时那个布局节点。
 *
 * 离场之后坐标对象就 detach 了（`isAttached == false`），这时退回快照——
 * 快照恰好是"离开那一刻它在屏幕哪儿"，而返回飞行的落点正是那里。
 * 页面回来时 `onGloballyPositioned` 会再报一次，同一份记录被新值覆盖。
 */
internal data class Entry(
    val snapshot: CoverAnchor,
    val coordinates: LayoutCoordinates?,
) {
    /** 这个端点此刻真正在哪 */
    fun rectNow(): Rect = coordinates?.takeIf { it.isAttached }?.boundsInRoot() ?: snapshot.rect
}

/** 一次飞行：起点是触发那一刻的快照，终点读目标端点的实时测量值 */
internal data class CoverFlight(
    val id: Int,
    val artwork: ImageBitmap,
    val fromKey: String,
    val toKey: String,
    val from: Rect,
    val fromCornerPx: Float,
    val enter: Boolean,
)

@Stable
class CoverFlightState {

    private var seq = 0

    /**
     * 全部端点的登记表：**普通 Map，不是快照状态**。
     *
     * 这是这一片最容易踩的性能坑：端点靠 `onGloballyPositioned` 报矩形，而列表滚动时
     * 每一个可见项每一帧都在报。这张表如果是 observable，写入就会失效掉所有读它的组合节点，
     * 于是"滚一格 = 整页重组"，正好撞在 v0.9.2 定下的滚动 Jank 门槛（< 1%）上。
     * 表只在飞行开始那一刻被读一次，飞行期间的实时矩形由覆盖层在绘制里现量，
     * 没有一处组合期读它——所以它不需要被观察。
     *
     * 项不清理：键带来源页，数量由"用户真去过的 (页, 目的地) 组合"界定，
     * 而位图本来就是 `ArtworkLoader` 那张 48 项 LRU 里的引用。
     */
    private val entries = HashMap<String, Entry>()

    /**
     * 落点那一端。只有飞行期间非空，所以只有覆盖层订阅它；覆盖层本来就按
     * [CoverFlight.toKey] 在飞行开始时重组一次，拿得到当前的那一份。
     */
    internal var landing: Entry? by mutableStateOf(null)
        private set

    /** 当前飞行的落点键。普通字段：布局回调不该去订阅 [flight]，否则滚动会被飞行状态失效 */
    private var landingKey: String? = null

    internal var flight: CoverFlight? by mutableStateOf(null)
        private set

    /**
     * 端点每次布局报一次自己。覆盖层要的就是"现在在哪"。
     *
     * [artwork] 可以为 null（图还没到）：矩形照样登记，这样"图后到"的那一次报值
     * 会把位图补进同一份记录里。
     */
    internal fun measure(
        key: String,
        rect: Rect,
        cornerPx: Float,
        artwork: ImageBitmap?,
        coordinates: LayoutCoordinates?,
    ) {
        val entry = Entry(CoverAnchor(rect, cornerPx, artwork), coordinates)
        entries[key] = entry
        if (key == landingKey) landing = entry
    }

    /**
     * 飞行期间端点上的那一份要隐身，否则同一个封面会同时出现在两个地方。
     * 端点始终留在树上（只是 alpha=0）：一撤掉就量不到矩形，终点会退化成起点。
     */
    internal fun coverHidden(key: String): Boolean {
        val current = flight ?: return false
        return current.fromKey == key || current.toKey == key
    }

    /** 进播放页：从迷你条那一小块飞到大图（010） */
    fun flyToPlayer(scale: Float) = begin(CoverKeys.MINI, CoverKeys.PLAYER, enter = true, scale = scale)

    /** 退回：从大图飞回迷你条（010） */
    fun flyToMini(scale: Float) = begin(CoverKeys.PLAYER, CoverKeys.MINI, enter = false, scale = scale)

    /**
     * 卡片 → 详情页头（011）。[cardPage] 是那张卡所在页面的路径，[path] 是详情页自己的路径。
     *
     * 来路为空（这一页不是详情页、或导航还没认出来）时不起飞。用块体而不是表达式体：
     * `?: return` 在表达式体里是不合法的，而这里要的正是"没有来路就当这事没发生"。
     */
    fun flyIntoDetail(cardPage: String?, path: String, scale: Float) {
        val from = cardPage?.let { CoverKeys.source(it, path) } ?: return
        begin(from, CoverKeys.detail(path), enter = true, scale = scale)
    }

    /** 详情页头 → 来路那张卡片（011 的返回方向）。[cardPage] 是回到哪一页 */
    fun flyOutOfDetail(cardPage: String?, path: String, scale: Float) {
        val to = cardPage?.let { CoverKeys.source(it, path) } ?: return
        begin(CoverKeys.detail(path), to, enter = false, scale = scale)
    }

    /**
     * 起一次飞行。
     *
     * 往回飞时落点必须登记过：返回的目的地本来就在下层，那一刻量得到。连落点都没有时
     * （比如从专辑页退回歌单页——进那张专辑页的入口本来就不是封面卡片），飞起来只会是
     * "页头封面在原地淡出一下"，那是无意义的鬼影，不如不飞。
     * 进场反过来允许落点暂时为空：目标页正在进来，覆盖层第一帧停在起点、随后跟上它的实时矩形。
     */
    private fun begin(fromKey: String, toKey: String, enter: Boolean, scale: Float) {
        // 关掉系统动画时当帧到位：飞行本身也不该发生
        if (!MotionSpec.shouldAnimate(scale)) return
        val source = entries[fromKey] ?: return
        val artwork = source.snapshot.artwork ?: return
        val rect = source.rectNow()
        if (rect.width <= 0f || rect.height <= 0f) return
        if (!enter && entries[toKey] == null) return
        seq += 1
        landingKey = toKey
        landing = entries[toKey]
        flight = CoverFlight(
            id = seq,
            artwork = artwork,
            fromKey = fromKey,
            toKey = toKey,
            from = rect,
            fromCornerPx = source.snapshot.cornerPx,
            enter = enter,
        )
    }

    internal fun finish(id: Int) {
        if (flight?.id == id) {
            flight = null
            landing = null
            landingKey = null
        }
    }
}

@Composable
fun rememberCoverFlightState(): CoverFlightState = remember { CoverFlightState() }

/**
 * 当前那份封面共享状态；没挂 provider 时端点登记与飞行都是空操作。
 *
 * 为什么用 CompositionLocal 而不是给每张卡片加一个参数：卡片有六个调用点、分布在四个页面上，
 * 而"这一张封面是不是共享元素"是**应用级**的事（覆盖层只有一份、挂在 `NavHost` 外面）。
 * 显式传要穿过 `HomeScreen` 的私有 section、两个广场页的网格、`SavedPlaylistsScreen` 的行，
 * 那几层管道没有一处 own 这件事。漏挂 provider 的代价只是退化成硬切，不会崩。
 */
val LocalCoverFlight: ProvidableCompositionLocal<CoverFlightState?> = staticCompositionLocalOf { null }

/**
 * 这一页的路径（`playlist/digest-8__123`、`home`、`playlists`），由导航在注册处提供。
 *
 * 两处都靠它当"我是谁"：卡片拿它拼自己那一端的键，详情页头拿它拼另一端的键。
 * 详情页头那一端还额外要求这一页在 [io.github.sakurajay.lyra.ui.nav.LyraRoutes.coverPath]
 * 的白名单里（见 [LocalCoverDetailPath]），不然「我喜欢」那种页头代理图也会参与共享。
 */
val LocalCoverPage: ProvidableCompositionLocal<String?> = staticCompositionLocalOf { null }

/** 这一页有没有"详情大图那一端"的封面；没有时为 null，[lyraCoverDetail] 就不登记 */
val LocalCoverDetailPath: ProvidableCompositionLocal<String?> = staticCompositionLocalOf { null }

/** 提供封面共享状态。与 `CoverFlightOverlay` 放在一起是为了让"覆盖层 + provider"是一个动作，不会只挂一半 */
@Composable
fun CoverFlightHost(state: CoverFlightState, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalCoverFlight provides state, content = content)
}

/**
 * 把一页登记成"某条路径 = 这一页"。
 *
 * [detailPath] 只在这一页的页头封面就是它自己的封面时才给（专辑 / 平台歌单 / 榜单 / 自建歌单），
 * 由 `lyraDetailRoute` 按路由参数算好；不给就是这一页不参与详情页那一端。
 */
@Composable
fun CoverPageHost(page: String?, detailPath: String?, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalCoverPage provides page,
        LocalCoverDetailPath provides detailPath,
        content = content,
    )
}

/**
 * 把一块封面登记成**卡片那一端**（小图）。
 *
 * [path] 是这张卡要跳去的路径；为空或这一页没登记身份时不登记——
 * 音乐库入口那种"页头不是目的地封面"的卡片就不该飞。
 */
@Composable
fun Modifier.lyraCoverSource(path: String?, corner: Dp, artwork: ImageBitmap?): Modifier {
    val page = LocalCoverPage.current
    if (path.isNullOrEmpty() || page.isNullOrEmpty()) return this
    return this.lyraCoverAnchor(CoverKeys.source(page, path), corner, artwork)
}

/** 把一块封面登记成**详情那一端**（页头大图）。这一页不在白名单里时是空操作 */
@Composable
fun Modifier.lyraCoverDetail(corner: Dp, artwork: ImageBitmap?): Modifier {
    val path = LocalCoverDetailPath.current
    if (path.isNullOrEmpty()) return this
    return this.lyraCoverAnchor(CoverKeys.detail(path), corner, artwork)
}

/**
 * 把一块封面登记成共享元素的端点，键由调用方给。
 *
 * 两个常规入口是 [lyraCoverSource] / [lyraCoverDetail]（键从"我在哪一页 / 我要去哪"推导），
 * 这一个留给迷你条与播放页那种固定端点。
 *
 * 挂在封面**容器**上而不是图片上：容器在没图时也在，矩形因此始终有效；图片只是它里面的内容。
 * 没挂 [CoverFlightHost] 时是空操作——预览与不参与共享元素的调用点因此不必造一份状态。
 */
@Composable
fun Modifier.lyraCoverAnchor(key: String, corner: Dp, artwork: ImageBitmap?): Modifier {
    val state = LocalCoverFlight.current ?: return this
    val cornerPx = with(LocalDensity.current) { corner.toPx() }
    return this
        // 坐标一起交进去：飞行期间要的是"这一端此刻在哪"，而转场的位移只在图层上，
        // 只靠这个回调会把终点量成滑到位之后的位置
        //
        // 这个回调同时也是**唯一的写入点**，位图的两种变化它都覆盖得到：
        // 1. 图后到（从无到有）：`if (artwork != null)` 的分支多出子节点，父容器重新 placement。
        // 2. 换了图但布局一个像素没动（换曲时的迷你条与播放页）：这一行读的 `artwork` 是本次
        //    重组捕获的那个值，所以挂上去的是一个新的 lambda 节点，Compose 会重跑 placement。
        // 第 2 种不是想当然：把位图补报单独拆出去之后，换封面那一条 ui 测试
        // （`CoverDetailFlightUiTest.aCardWhoseCoverWasSwappedFliesTheNewCover`）仍然过，
        // 实测成立才只留这一处写入。少了它才会飞旧封面——而它只有几百毫秒，最难发现。
        .onGloballyPositioned { state.measure(key, it.boundsInRoot(), cornerPx, artwork, it) }
        // 用 graphicsLayer 的 lambda 版而不是 alpha(value)：飞行状态要在**绘制期**读，
        // 不能在组合期读。写成 `alpha(if (state.coverHidden(key)) 0f else 1f)` 一样能动，
        // 但那样每一张卡片都订阅了飞行状态——一次起飞与落位会让整页的卡重组一遍，
        // 而那正好压在转场首帧的预算上（工单 001 量的就是这个）。
        .graphicsLayer { alpha = if (state.coverHidden(key)) 0f else 1f }
}

/**
 * 覆盖层本体。挂在 `NavHost` 外面（页面走了它还在），不吃触摸，不参与测量。
 *
 * 时长走 large 档的进入 / 退出两值：350ms 飞过去、450ms 飞回来，再乘 [LocalMotionScale]。
 * 进度只在 `Canvas` 的绘制里读，所以整段飞行不需要重组，只需要重绘。
 */
@Composable
fun CoverFlightOverlay(state: CoverFlightState, modifier: Modifier = Modifier) {
    val flight = state.flight ?: return
    val scale = LocalMotionScale.current
    val durationMs = MotionSpec.durationMs(MotionTier.LARGE, scale, enter = flight.enter)
    val progress = remember { Animatable(0f) }

    LaunchedEffect(flight.id) {
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = durationMs.coerceAtLeast(1),
                easing = coverFlightEasing(flight.enter),
            ),
        )
        state.finish(flight.id)
    }

    Canvas(modifier = modifier) {
        val current = state.flight ?: return@Canvas
        val target = state.landing
        val rect = coverFlightRect(current.from, target?.rectNow() ?: current.from, progress.value)
        val corner = coverFlightCorner(
            fromPx = current.fromCornerPx,
            toPx = target?.snapshot?.cornerPx ?: current.fromCornerPx,
            fraction = progress.value,
        )
        if (rect.width < 1f || rect.height < 1f) return@Canvas
        val src = coverCropSource(current.artwork.width, current.artwork.height, rect.width, rect.height)
        val path = Path().apply {
            // 圆角不许超过短边的一半：尺寸插值到很小时，超出的角会让形状翻出去
            val r = corner.coerceIn(0f, minOf(rect.width, rect.height) / 2f)
            addRoundRect(
                RoundRect(rect.left, rect.top, rect.right, rect.bottom, r, r),
            )
        }
        clipPath(path) {
            drawImage(
                image = current.artwork,
                srcOffset = IntOffset(src.x, src.y),
                srcSize = IntSize(src.width, src.height),
                dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
                dstSize = IntSize(
                    width = rect.width.roundToInt().coerceAtLeast(1),
                    height = rect.height.roundToInt().coerceAtLeast(1),
                ),
            )
        }
    }
}
