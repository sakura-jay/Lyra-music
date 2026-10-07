package io.github.sakurajay.lyra.player

import io.github.sakurajay.lyra.domain.model.Song
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 队列状态机的纯逻辑测试（JVM，不需要设备）。
 *
 * 播放模式的坑全在边界上：最后一曲之后去哪、删掉正在播的那首要落在哪、
 * 拖拽之后"当前曲"跟不跟着走。这些都必须能在没有真机时被钉住。
 */
class PlayQueueTest {

    private fun song(name: String) = Song(id = name, source = "kw", name = name, artists = listOf("A"))
    private val songs = listOf(song("s1"), song("s2"), song("s3"))

    @Test
    fun sequentialStopsAtEnd() {
        val queue = PlayQueue(songs, PlayMode.SEQUENTIAL)
        assertEquals("s2", queue.next()?.name)
        assertEquals("s3", queue.next()?.name)
        assertNull("顺序模式到队尾必须停住", queue.next())
        assertEquals("s3", queue.current()?.name)
    }

    @Test
    fun loopAllWrapsAround() {
        val queue = PlayQueue(songs, PlayMode.LOOP_ALL)
        queue.next(); queue.next()
        assertEquals("s1", queue.next()?.name)
        assertEquals("s3", queue.previous()?.name)
    }

    @Test
    fun loopOneKeepsCurrent() {
        val queue = PlayQueue(songs, PlayMode.LOOP_ONE)
        assertEquals("s1", queue.next()?.name)
        assertEquals("s1", queue.previous()?.name)
    }

    @Test
    fun shuffleVisitsEveryEntryOncePerCycle() {
        val queue = PlayQueue(songs, PlayMode.SHUFFLE, random = Random(7))
        val seen = mutableListOf(queue.current()?.name)
        repeat(2) { seen.add(queue.next()?.name) }

        assertEquals("一轮内不应重复", 3, seen.toSet().size)
        assertEquals("一轮应覆盖全部三首", setOf("s1", "s2", "s3"), seen.toSet())
    }

    @Test
    fun shuffleReshufflesAfterCompletingCycle() {
        val queue = PlayQueue(songs, PlayMode.SHUFFLE, random = Random(3))
        repeat(3) { queue.next() }
        val wrapped = queue.next()
        assertNotEquals("游标越界后应重新洗牌而不是卡住", null, wrapped)
        assertEquals(0, queue.currentIndex.let { if (it >= 0) 0 else 1 })
    }

    @Test
    fun switchingToShuffleBuildsOrderImmediately() {
        val queue = PlayQueue(songs, PlayMode.SEQUENTIAL)
        queue.mode = PlayMode.SHUFFLE
        val next = queue.next()
        assertEquals(true, next != null && songs.any { it.name == next.name })
    }

    @Test
    fun removingCurrentAdvancesToFollowingSong() {
        val queue = PlayQueue(songs, PlayMode.SEQUENTIAL)
        queue.jumpTo(1)
        assertEquals("s2", queue.current()?.name)
        queue.removeAt(1)
        assertEquals("删掉当前曲应落到原来的下一首", "s3", queue.current()?.name)
    }

    @Test
    fun removingEarlierEntryKeepsPlayingSong() {
        val queue = PlayQueue(songs, PlayMode.SEQUENTIAL)
        queue.jumpTo(2)
        queue.removeAt(0)
        assertEquals("s3", queue.current()?.name)
        assertEquals(1, queue.currentIndex)
    }

    @Test
    fun removingLastEntryFallsBack() {
        val queue = PlayQueue(songs, PlayMode.SEQUENTIAL)
        queue.jumpTo(2)
        queue.removeAt(2)
        assertEquals("s2", queue.current()?.name)
    }

    @Test
    fun removingOnlyEntryEmptiesQueue() {
        val queue = PlayQueue(listOf(song("solo")), PlayMode.SEQUENTIAL)
        queue.removeAt(0)
        assertNull(queue.current())
        assertEquals(-1, queue.currentIndex)
        assertEquals(0, queue.size)
    }

    @Test
    fun insertNextDoesNotStealCurrentPosition() {
        val queue = PlayQueue(songs, PlayMode.SEQUENTIAL)
        queue.insertNext(song("soon"))
        assertEquals("s1", queue.current()?.name)
        assertEquals("soon", queue.next()?.name)
        assertEquals("s2", queue.next()?.name)
    }

    @Test
    fun moveKeepsPlayingSongIdentified() {
        val queue = PlayQueue(songs, PlayMode.SEQUENTIAL)
        queue.jumpTo(0)
        queue.move(0, 2)
        assertEquals("拖拽后当前曲应跟着走", "s1", queue.current()?.name)
        assertEquals(listOf("s2", "s3", "s1"), queue.entries.map { it.song.name })
    }

    @Test
    fun replaceAllResetsToGivenStart() {
        val queue = PlayQueue(songs, PlayMode.SEQUENTIAL)
        queue.replaceAll(listOf(song("b1"), song("b2")), startIndex = 1)
        assertEquals("b2", queue.current()?.name)
        assertNull(queue.next())
    }

    @Test
    fun jumpToInvalidIndexIsRejected() {
        val queue = PlayQueue(songs, PlayMode.SEQUENTIAL)
        assertNull(queue.jumpTo(9))
        assertEquals("s1", queue.current()?.name)
    }

    @Test
    fun clearResetsState() {
        val queue = PlayQueue(songs, PlayMode.SEQUENTIAL)
        queue.clear()
        assertTrue(queue.isEmpty())
        assertNull(queue.current())
        queue.append(song("after"))
        assertEquals("after", queue.current()?.name)
    }
}
