package io.github.sakurajay.lyra.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 共享元素的键。
 *
 * 011 之后端点是一张按路径登记的表，而"卡片那端与详情那端算出的键必须对上"是整件事成立的
 * 前提。它错起来的表现是"封面就是不飞"——不崩、没有中间帧、截图也看不出来，肉眼最难发现的
 * 那一种，所以这层推导单独在 JVM 上钉死。
 */
class CoverKeysTest {

    @Test
    fun bothEndsCarryTheSameDestinationPath() {
        // 两端共用同一段目的地路径；来源页只挂在卡片那一端
        assertEquals("home>playlist/digest-8__123|#list", CoverKeys.source("home", "playlist/digest-8__123"))
        assertEquals("playlist/digest-8__123|#detail", CoverKeys.detail("playlist/digest-8__123"))
    }

    @Test
    fun theSameDestinationOnTwoPagesIsTwoDifferentSources() {
        // 同一个歌单会同时出现在首页推荐排与歌单广场上。少了来源页这一层，
        // "从首页点进去、退回首页"就可能飞到广场那张卡的旧坐标上——而广场此刻根本不在栈里
        assertNotEquals(
            CoverKeys.source("home", "playlist/x"),
            CoverKeys.source("playlists", "playlist/x"),
        )
    }

    @Test
    fun cardEndAndDetailEndNeverCollide() {
        assertNotEquals(CoverKeys.source("playlist/x", "playlist/x"), CoverKeys.detail("playlist/x"))
    }

    @Test
    fun fixedEndsUseTheirOwnNamespace() {
        // 固定端点各占一段不可能的路径：路由不会以 # 开头，真卡片永远占不到这两把位子
        assertEquals(CoverKeys.source("#player", "#mini"), CoverKeys.MINI)
        assertEquals(CoverKeys.detail("#player"), CoverKeys.PLAYER)
        assertNotEquals(CoverKeys.MINI, CoverKeys.source("player", "mini"))
        assertNotEquals(CoverKeys.PLAYER, CoverKeys.detail("player"))
    }
}