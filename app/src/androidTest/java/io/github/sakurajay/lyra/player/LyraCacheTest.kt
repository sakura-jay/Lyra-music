package io.github.sakurajay.lyra.player

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.test.core.app.ApplicationProvider
import io.github.sakurajay.lyra.domain.model.Quality
import io.github.sakurajay.lyra.domain.model.QualityAvailability
import io.github.sakurajay.lyra.domain.model.Song
import io.github.sakurajay.lyra.domain.model.SongCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * 缓存条目的枚举与按条删除。
 *
 * 写缓存走真 CacheDataSource + CacheWriter，不用替身：这一层要钉的就是
 * "键是不是那首歌"与"删得掉删不掉"，换内存实现等于把被测的东西换掉了。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class LyraCacheTest {

    private val remote = Song(
        id = "268702",
        source = "kw",
        name = "霍元甲",
        artists = listOf("周杰伦", "林志玲"),
        durationSec = 278,
        qualities = listOf(QualityAvailability(Quality.LOSSLESS)),
    )
    private val local = Song(
        id = "42",
        source = Song.LOCAL_SOURCE,
        name = "手机里的歌",
        artists = emptyList(),
        durationSec = 9,
        localUri = "content://media/external/audio/media/42",
    )

    private lateinit var context: Context

    @Before
    fun emptyCache() {
        context = ApplicationProvider.getApplicationContext()
        LyraCache.clear(context)
        assertEquals(0, LyraCache.entries(context).size)
    }

    /** 把 [bytes] 当成"已经缓存了这首歌"写进缓存，走的就是播放时那条上游 */
    private fun prime(song: Song, bytes: Int) {
        val payload = ByteArray(bytes) { (it % 251).toByte() }
        val upstream = DataSource.Factory { ByteArrayDataSource(payload) }
        val source = LyraCache.factory(context, upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .createDataSource()
        val key = Uri.parse(SongCodec.toLazyUri(song))
        CacheWriter(source, DataSpec(key, 0, payload.size.toLong()), payload, null).cache()
        source.close()
    }

    @Test
    fun listsEachCachedSongWithItsSize() {
        prime(remote, 5_000)
        prime(local, 12_000)

        val entries = LyraCache.entries(context)
        assertEquals(2, entries.size)
        // 大的排前面：用户要看的是"谁把缓存吃了"
        assertEquals(12_000L, entries.first().bytes)
        assertEquals("手机里的歌", entries.first().song?.name)
        assertTrue("本地条目要认得出是本地文件", entries.first().song!!.isLocal)
        assertEquals("霍元甲", entries[1].song?.name)
        assertEquals(listOf("周杰伦", "林志玲"), entries[1].song?.artists)
    }

    @Test
    fun removesOneEntryAndKeepsTheRest() {
        prime(remote, 4_000)
        prime(local, 6_000)
        val localKey = SongCodec.toLazyUri(local)

        assertTrue(LyraCache.remove(context, localKey))

        val left = LyraCache.entries(context)
        assertEquals(listOf("霍元甲"), left.mapNotNull { it.song?.name })
        assertEquals(4_000L, left.single().bytes)
    }

    /** 认不回的键也必须列出来：那笔占用是真实的，藏起来就是谎报 */
    @Test
    fun keepsUnrecognizableKeys() {
        val stale = "lyra://song/%E6%97%A7%E6%A0%BC%E5%BC%8F"
        val payload = ByteArray(3_000)
        val source = LyraCache.factory(context, DataSource.Factory { ByteArrayDataSource(payload) })
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .createDataSource()
        CacheWriter(source, DataSpec(Uri.parse(stale), 0, payload.size.toLong()), payload, null).cache()
        source.close()

        val entry = LyraCache.entries(context).single()
        assertEquals(stale, entry.key)
        assertNull(entry.song)
        assertEquals(3_000L, entry.bytes)
    }

    /**
     * 起始曲播的是当场解析出来的 CDN 直链（每次点歌都不一样），但缓存的键必须是这首歌
     * 自己的地址，否则重播永远命不中、详情页也认不出这条是谁的。
     *
     * 钉的就是这条链路：键取自 MediaItem 的 customCacheKey，而不是播放地址。
     */
    @Test
    fun cacheKeyIsTheSongNotThePlaybackUrl() {
        val cdn = "https://kwcdn.example/stream/268702.flac?token=expires-in-5m"
        val item = remote.toNowPlayingSpec().toMediaItem(cdn)

        assertEquals("播放地址仍应是解析出的直链", cdn, item.localConfiguration?.uri.toString())
        val key = item.localConfiguration?.customCacheKey
        assertEquals(SongCodec.toLazyUri(remote), key)
        assertEquals("键必须能反解回这首歌", "霍元甲", SongCodec.fromLazyUri(key!!)?.name)
    }

    @Test
    fun clearEmptiesEverything() {
        prime(remote, 2_000)
        prime(local, 3_000)

        LyraCache.clear(context)

        assertTrue(LyraCache.entries(context).isEmpty())
        assertFalse("删不存在的键不该报成功", LyraCache.remove(context, SongCodec.toLazyUri(remote)))
    }
}
