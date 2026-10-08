package io.github.sakurajay.lyra.domain.lyric

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricTextSizeTest {

    @Test
    fun boundsStopInsteadOfWrappingAround() {
        // 到顶还往上升会绕回最小档，用户会觉得"点错了"
        assertEquals(LyricTextSize.LARGE, LyricTextSize.LARGE.larger())
        assertEquals(LyricTextSize.SMALL, LyricTextSize.SMALL.smaller())
        assertEquals(LyricTextSize.MEDIUM, LyricTextSize.SMALL.larger())
        assertEquals(LyricTextSize.MEDIUM, LyricTextSize.LARGE.smaller())
    }

    @Test
    fun activeSizeIsAlwaysLargerThanInactive() {
        LyricTextSize.entries.forEach {
            assertEquals("${it.name} 当前行要比别的行大", true, it.activeSp > it.inactiveSp)
        }
    }

    @Test
    fun unknownStoredNameFallsBackToMedium() {
        // 设置里存的是枚举名，将来加档位/改名时旧值必须落到一个能用的档上
        assertEquals(LyricTextSize.MEDIUM, LyricTextSize.fromName(null))
        assertEquals(LyricTextSize.MEDIUM, LyricTextSize.fromName("HUGE"))
        assertEquals(LyricTextSize.LARGE, LyricTextSize.fromName("LARGE"))
    }
}
