package io.github.sakurajay.lyra.ui.common

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.sakurajay.lyra.ui.motion.MotionScaleProvider
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 011 的登记表：卡片那一端与详情那一端按路径互相认出彼此。
 *
 * 这里要证的不是"飞起来好不好看"（那得录屏），而是**键对不上时整件事必须不发生**、
 * 键对上一帧都不许多画。键错的表现是"封面就是不飞"或"封面飞向别处"，两种都既不崩、
 * 截图也看不出来，所以用像素钉死：路径对得上就必须有中间帧，对不上就必须一帧飞行都不画。
 *
 * 与 `CoverFlightUiTest` 同一套办法：覆盖层是整屏 `Canvas`，语义树里没有节点也没有属性，
 * 只能靠"哪块像素是封面色"反推它画没画、画在哪。
 */
@RunWith(AndroidJUnit4::class)
class CoverDetailFlightUiTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var flight: CoverFlightState

    /** 两张纯色封面：判"飞的是哪一张"靠的就是这两个颜色差得足够远 */
    private val art: ImageBitmap by lazy { solid(ART_COLOR) }
    private val swapped: ImageBitmap by lazy { solid(SWAPPED_COLOR) }

    private fun solid(color: Color): ImageBitmap =
        Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(color.toArgb()) }
            .asImageBitmap()

    @Test
    fun cardCoverFliesIntoTheDetailHeaderWhenThePathMatches() {
        rule.setContent { Harness(cardPage = "home", cardPath = CARD, headerPath = CARD) }
        rule.waitForIdle()
        val card = boundsOf(CARD_TAG)
        val header = boundsOf(HEADER_TAG)

        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { flight.flyIntoDetail("home", CARD, 1f) }
        rule.mainClock.advanceTimeBy(175)
        val mid = capture()
        assertTrue("飞行中卡片那一份必须隐身", !mid.isCover(card.center))
        assertTrue("飞行中页头那一份不能提前出现", !mid.isCover(header.center))
        val cover = mid.coverBounds()
        assertNotNull("路径对得上就必须真有一张封面在飞", cover)
        assertTrue("中途必须已经离开卡片那一侧（$cover）", cover!!.left > card.right)
        assertTrue("中途还没贴到页头（$cover vs $header）", cover.right < header.right)

        rule.mainClock.advanceTimeBy(1_000)
        val after = capture()
        assertTrue("落位后页头画回自己那一份", after.isCover(header.center))
        assertTrue("落位后卡片也画回来", after.isCover(card.center))
    }

    @Test
    fun nothingFliesWhenThePathDoesNotMatch() {
        // 卡片是 playlist/x 的封面，进来的却是 playlist/y 的页头：两条完全不同的列表。
        // 这时必须一次飞行都不发生——否则"A 的封面飞进 B 的页头"就是最难发现的那种错
        rule.setContent { Harness(cardPage = "home", cardPath = CARD, headerPath = OTHER) }
        rule.waitForIdle()
        val card = boundsOf(CARD_TAG)
        val header = boundsOf(HEADER_TAG)

        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { flight.flyIntoDetail("home", "playlist/nope", 1f) }
        rule.mainClock.advanceTimeBy(175)
        assertNull("没有匹配的卡片端就不该有飞行", flight.flight)
        val mid = capture()
        assertTrue("卡片那一份照常画", mid.isCover(card.center))
        assertTrue("页头那一份照常画", mid.isCover(header.center))
    }

    @Test
    fun returningToADifferentPageDoesNotUseAnotherCardsRect() {
        // 首页那张卡与广场那张卡是同一个歌单：来源页不同就是两个端点。
        // 回到"广场"时，不许拿 home 上那张卡的矩形当落点
        rule.setContent { Harness(cardPage = "home", cardPath = CARD, headerPath = CARD) }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { flight.flyOutOfDetail("playlists", CARD, 1f) }
        rule.mainClock.advanceTimeBy(175)
        assertNull("回到的不是那张卡所在的那一页时不该起飞", flight.flight)
        assertTrue("没起飞时页头照常画", capture().isCover(boundsOf(HEADER_TAG).center))
    }

    @Test
    fun flyingBackLandsOnTheCardThatCameFrom() {
        rule.setContent { Harness(cardPage = "home", cardPath = CARD, headerPath = CARD) }
        rule.waitForIdle()
        val card = boundsOf(CARD_TAG)
        val header = boundsOf(HEADER_TAG)

        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { flight.flyOutOfDetail("home", CARD, 1f) }
        rule.mainClock.advanceTimeBy(225)
        val mid = capture()
        assertTrue("返回飞行中页头要隐身", !mid.isCover(header.center))
        assertTrue("返回飞行中卡片要隐身", !mid.isCover(card.center))
        val cover = mid.coverBounds()
        assertNotNull("回到有那张卡的那一页就必须飞", cover)
        // 返回是"从大图收回小方块"：中途应当已经离开页头那一侧、往卡片靠过去
        assertTrue("中途的封面不该还压在页头上（$cover vs $header）", cover!!.bottom < header.bottom)

        rule.mainClock.advanceTimeBy(1_000)
        val after = capture()
        assertTrue("落位后卡片画回来", after.isCover(card.center))
        assertTrue("落位后页头画回来", after.isCover(header.center))
    }

    @Test
    fun anUnloadedCoverNeverFlies() {
        // 图还没到就不许插值：这时端点只有矩形、没有位图
        rule.setContent { Harness(cardPage = "home", cardPath = CARD, headerPath = CARD, artwork = null) }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { flight.flyIntoDetail("home", CARD, 1f) }
        rule.mainClock.advanceTimeBy(175)
        assertNull("没图就不该有飞行", flight.flight)
    }

    /**
     * 换了封面但没换布局的那张卡，飞的必须是**新**那一张。
     *
     * 对应播放页与迷你条换曲：封面的位置与尺寸一个像素都没动，只是那张图从 A 变成 B。
     * 这条测试钉住的是"`onGloballyPositioned` 是登记表唯一的写入点"这个事实——捕获的
     * `artwork` 一变，挂上去的就是新的 lambda 节点，placement 会重跑并把新位图带进来。
     * 曾经按"换图没有布局变化，所以要额外补报一次"写过一条 `SideEffect` 路径，
     * 关掉它之后这七条全过，可见那层是多余的，别再把它加回来（判据就是这条测试）。
     */
    @Test
    fun aCardWhoseCoverWasSwappedFliesTheNewCover() {
        var current by mutableStateOf(art)
        rule.setContent { Harness(cardPage = "home", cardPath = CARD, headerPath = CARD, artwork = current) }
        rule.waitForIdle()

        // 换曲：两端都从蓝封面换成绿封面，容器不动
        rule.runOnUiThread { current = swapped }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false

        rule.runOnUiThread { flight.flyIntoDetail("home", CARD, 1f) }
        rule.mainClock.advanceTimeBy(175)
        val mid = capture()
        val cover = mid.swappedBounds()
        assertNotNull("换了封面之后必须仍然有一张在飞的封面", cover)
        assertTrue("飞的必须是换过之后的那一张，不是上一首的", mid.isSwapped(cover!!.center))
        assertTrue("旧封面不该还留在飞行轨迹上", !mid.isCover(cover.center))
    }

    @Test
    fun reducedMotionNeverStartsAFlight() {
        rule.setContent { Harness(cardPage = "home", cardPath = CARD, headerPath = CARD, scale = 0f) }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { flight.flyIntoDetail("home", CARD, 0f) }
        rule.mainClock.advanceTimeBy(500)
        assertNull("关掉系统动画时整件事不发生", flight.flight)
        val pixels = capture()
        assertTrue("卡片照常画", pixels.isCover(boundsOf(CARD_TAG).center))
        assertTrue("页头照常画", pixels.isCover(boundsOf(HEADER_TAG).center))
    }

    private fun boundsOf(tag: String): Rect = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun capture(): PixelMap = rule.onNodeWithTag(ROOT).captureToImage().toPixelMap()

    /**
     * 一张卡 + 一个页头 + 覆盖层。
     *
     * 卡片在左上（110dp 方块的观感），页头在右下（96dp 的观感，这里给 120dp
     * 让两端离得够远、中间帧能判出来）。身份由 provider 给、页面自己不带参数——
     * 与真机上的 `CoverPageHost` 同一形状。
     */
    @Composable
    private fun Harness(
        cardPage: String,
        cardPath: String,
        headerPath: String,
        scale: Float = 1f,
        artwork: ImageBitmap? = art,
    ) {
        MotionScaleProvider(scale = scale) {
            val state = rememberCoverFlightState()
            flight = state
            CoverFlightHost(state) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White)
                        .testTag(ROOT),
                ) {
                    CoverPageHost(cardPage, null) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .size(110.dp)
                                .background(Color.LightGray)
                                .lyraCoverSource(cardPath, 12.dp, artwork)
                                .testTag(CARD_TAG),
                        ) {
                            if (artwork != null) {
                                Image(bitmap = artwork, contentDescription = null, modifier = Modifier.fillMaxSize())
                            }
                        }
                    }
                    CoverPageHost(headerPath, headerPath) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .offset(x = (-40).dp, y = (-120).dp)
                                .size(120.dp)
                                .background(Color(0xFFEEEEEE))
                                .lyraCoverDetail(12.dp, artwork)
                                .testTag(HEADER_TAG),
                        ) {
                            if (artwork != null) {
                                Image(bitmap = artwork, contentDescription = null, modifier = Modifier.fillMaxSize())
                            }
                        }
                    }
                    CoverFlightOverlay(state, Modifier.matchParentSize())
                }
            }
        }
    }

    private fun PixelMap.isCover(point: Offset): Boolean = at(point) { it.near(ART_COLOR) }

    private fun PixelMap.isSwapped(point: Offset): Boolean = at(point) { it.near(SWAPPED_COLOR) }

    private fun PixelMap.at(point: Offset, check: (Color) -> Boolean): Boolean {
        val x = point.x.roundToInt()
        val y = point.y.roundToInt()
        if (x !in 0 until width || y !in 0 until height) return false
        return check(this[x, y])
    }

    /**
     * 每个通道留 0.15 的余量：纯色像素本来该完全相等，余量只用来把抗锯齿的过渡边挡在外面
     * （阈值再紧一点，量出来的边就会正好落在图形内部一两像素）。
     */
    private fun Color.near(target: Color): Boolean =
        abs(red - target.red) < 0.15f && abs(green - target.green) < 0.15f && abs(blue - target.blue) < 0.15f

    /** 一整幅图里所有封面像素的外接矩形；一像素都没有时返回 null */
    private fun PixelMap.coverBounds(): Rect? = boundsWhere { x, y -> this[x, y].near(ART_COLOR) }

    /** 同上，但判的是换过之后的那一张封面色 */
    private fun PixelMap.swappedBounds(): Rect? = boundsWhere { x, y -> this[x, y].near(SWAPPED_COLOR) }

    private fun PixelMap.boundsWhere(pred: (Int, Int) -> Boolean): Rect? {
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (!pred(x, y)) continue
                if (x < left) left = x
                if (y < top) top = y
                if (x > right) right = x
                if (y > bottom) bottom = y
            }
        }
        if (right < 0) return null
        return Rect(left.toFloat(), top.toFloat(), right + 1f, bottom + 1f)
    }

    private companion object {
        const val ROOT = "cover-detail-root"
        const val CARD_TAG = "cover-detail-card"
        const val HEADER_TAG = "cover-detail-header"
        const val CARD = "playlist/digest-8__123"
        const val OTHER = "playlist/digest-8__999"
        val ART_COLOR = Color(0xFF1565C0)
        val SWAPPED_COLOR = Color(0xFF2E7D32)
    }
}
