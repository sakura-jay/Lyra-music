package io.github.sakurajay.lyra.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 覆盖层的终点能不能"跟着滑动中的页面走"——这条事实决定 010 / 011 的实现形状，
 * 所以必须钉死，而不是靠真目录屏反推。
 *
 * 路由转场（`slideInVertically` / `slideInHorizontally`）走的是 graphicsLayer 位移：
 * 它只改图层矩阵，**不重量、不重排**。如果 `onGloballyPositioned` 因此不再回调，
 * 那"终点读实时"就是假的——覆盖层会飞向页面滑动**之前**量到的矩形，
 * 落位那一刻再跳一次，正好是 ADR-0010 说的"两套位移打架"。
 */
@RunWith(AndroidJUnit4::class)
class CoverAnchorTracksLayerTranslationTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun positionedCallbackFiresWhenAncestorTranslatesByGraphicsLayer() {
        val rects = mutableListOf<Rect>()
        var offset by mutableStateOf(0f)
        rule.setContent {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationY = offset },
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .onGloballyPositioned { rects += it.boundsInRoot() },
                )
            }
        }
        rule.waitForIdle()
        val before = rects.toList()
        assertTrue("首帧就该量到一次", before.isNotEmpty())

        // 只改图层位移：被观察的节点本身不重组、不重量
        rule.runOnUiThread { offset = 320f }
        rule.waitForIdle()

        val after = rects.last()
        assertNotEquals("回调必须跟着图层位移再来一次", before.last(), after)
        assertTrue("回调给出的 top 必须含图层位移（$after）", after.top > before.last().top + 100f)
    }

    @Test
    fun layoutCoordinatesReadLaterReflectCurrentLayerTranslation() {
        var coords: LayoutCoordinates? = null
        var offset by mutableStateOf(0f)
        rule.setContent {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationY = offset },
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .onGloballyPositioned { coords = it },
                )
            }
        }
        rule.waitForIdle()
        val atZero = coords!!.boundsInRoot().top

        rule.runOnUiThread { offset = 240f }
        rule.waitForIdle()
        // 绘制期再读一次（覆盖层真正需要的就是"此刻在哪"）
        var now = Rect.Zero
        rule.runOnUiThread { now = coords!!.boundsInRoot() }
        rule.waitForIdle()

        assertTrue("事后读到的矩形必须含图层位移：$atZero -> $now", now.top > atZero + 100f)
    }
}