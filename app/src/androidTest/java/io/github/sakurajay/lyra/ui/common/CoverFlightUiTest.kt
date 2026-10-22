package io.github.sakurajay.lyra.ui.common

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.sakurajay.lyra.ui.motion.MotionScaleProvider
import kotlin.math.roundToInt
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 封面共享在屏幕上到底画没画、画在哪。
 *
 * 这一段只能靠"看像素"守：覆盖层是一块 `Canvas`，语义树里既没有节点也没有属性，
 * `gfxinfo` 又只看得到掉不掉帧、看不到画在哪儿。所以这里自己造一张纯色封面，
 * 靠"哪块像素是封面色"反推它的位置——真机上要的录屏是同一件事的肉眼版本，
 * 但录屏没法进 CI，这一段可以。
 *
 * 模拟器上没有可播的曲目（`assets/sources` 不进仓库），所以**不能**用真封面测；
 * 这里的两端与覆盖层都是被测代码自己（[lyraCoverAnchor] / [CoverFlightOverlay]），
 * 只有图是伪造的。
 */
@RunWith(AndroidJUnit4::class)
class CoverFlightUiTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var flight: CoverFlightState

    /** 纯色封面：判"这块像素是不是封面"靠的就是这个颜色 */
    private val art: ImageBitmap by lazy {
        Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(Color(0xFFD32F2F).toArgb()) }
            .asImageBitmap()
    }

    @Test
    fun coverFliesBetweenTheSlotsAndHidesBothEnds() {
        rule.setContent { Harness(scale = 1f) }
        rule.waitForIdle()
        val mini = boundsOf(MINI)
        val player = boundsOf(PLAYER)

        // 起飞前：两端各画各的，走廊里什么都没有。这一条同时是"判色判得准"的证据
        val before = capture()
        assertTrue("迷你条那一端本来就有封面", before.isCover(mini.center))
        assertTrue("播放页那一端本来就有封面", before.isCover(player.center))

        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { flight.flyToPlayer(1f) }
        // 去程 017 起走 ROUTE 档 280ms，走到一半：图只该在中间，两端的自己那份必须隐身
        rule.mainClock.advanceTimeBy(175)
        val mid = capture()
        assertTrue("飞行中起点不能还留着那一份", !mid.isCover(mini.center))
        assertTrue("飞行中终点不能提前出现那一份", !mid.isCover(player.center))
        val cover = mid.coverBounds()
        assertNotNull("飞行中必须真的有一张封面被画出来", cover)
        assertTrue("起点一侧应当已经空出来（$cover vs $mini）", cover!!.left > mini.right)
        assertTrue("终点一侧上方还应当空着（$cover vs $player）", cover.top > mini.bottom)
        assertTrue("中途的封面不该已经贴到终点（$cover vs $player）", cover.right < player.right)
        assertTrue("中途的封面不该已经贴到终点（$cover vs $player）", cover.bottom < player.bottom)

        // 落位：终点自己把图画回来，覆盖层退场（否则封面会永远停在这一帧上）
        rule.mainClock.advanceTimeBy(1_000)
        val after = capture()
        assertTrue("落位后播放页那一端要画回封面", after.isCover(player.center))
        assertTrue("落位后迷你条那一端也要画回来", after.isCover(mini.center))
    }

    @Test
    fun coverStaysAtBothEndsWhenAnimationsAreOff() {
        rule.setContent { Harness(scale = 0f) }
        rule.waitForIdle()
        val mini = boundsOf(MINI)
        val player = boundsOf(PLAYER)

        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { flight.flyToPlayer(0f) }
        rule.mainClock.advanceTimeBy(1_000)
        val pixels = capture()

        // 关掉系统动画时整件事不发生：两端照常画，也不该有"当帧闪一下"的中间层
        assertTrue("关掉动画时迷你条那一端照常画", pixels.isCover(mini.center))
        assertTrue("关掉动画时播放页那一端照常画", pixels.isCover(player.center))
        val corridor = Offset(player.center.x, mini.center.y)
        assertTrue("关掉动画时不该出现飞行中的封面", !pixels.isCover(corridor))
    }

    private fun boundsOf(tag: String): Rect = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun capture(): PixelMap = rule.onNodeWithTag(ROOT).captureToImage().toPixelMap()

    /**
     * 端点 + 覆盖层的最小组合：迷你条那一端在左上角（真机上是 44dp 的小方块），
     * 播放页那一端在右下（230dp 的大图，这里缩到 150dp 让两头离得够远）。
     * 覆盖层按真机的层序放在最后——真机上它挂在 `NavHost` 外面，也在所有内容之上。
     */
    @Composable
    private fun Harness(scale: Float) {
        MotionScaleProvider(scale = scale) {
            val state = rememberCoverFlightState()
            flight = state
            // 端点从 LocalCoverFlight 读状态，与真机上 CoverFlightHost 包住整棵树同一形状
            CoverFlightHost(state) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White)
                        .testTag(ROOT),
                ) {
                    Endpoint(CoverKeys.MINI, MINI, Modifier.align(Alignment.TopStart).size(44.dp), 12.dp)
                    Endpoint(CoverKeys.PLAYER, PLAYER, Modifier.offset(x = 200.dp, y = 400.dp).size(150.dp), 22.dp)
                    CoverFlightOverlay(state, Modifier.matchParentSize())
                }
            }
        }
    }

    @Composable
    private fun BoxScope.Endpoint(key: String, tag: String, holder: Modifier, corner: Dp) {
        Box(modifier = holder.testTag(tag).lyraCoverAnchor(key, corner, art)) {
            Image(bitmap = art, contentDescription = null, modifier = Modifier.fillMaxSize())
        }
    }

    private fun PixelMap.isCover(point: Offset): Boolean = isCover(point.x.roundToInt(), point.y.roundToInt())

    /**
     * 封面色判定留了很大余量：抗锯齿的过渡像素会是对半混出来的，
     * 阈值紧一点它们就进不来，量出来的边正好落在图形内部一两像素
     */
    private fun PixelMap.isCover(x: Int, y: Int): Boolean {
        if (x !in 0 until width || y !in 0 until height) return false
        val color = this[x, y]
        return color.red > 0.7f && color.green < 0.35f && color.blue < 0.35f
    }

    /** 一整幅图里所有封面像素的外接矩形；一像素都没有时返回 null */
    private fun PixelMap.coverBounds(): Rect? {
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (!isCover(x, y)) continue
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
        const val ROOT = "cover-flight-root"
        const val MINI = "cover-flight-mini"
        const val PLAYER = "cover-flight-player"
    }
}
