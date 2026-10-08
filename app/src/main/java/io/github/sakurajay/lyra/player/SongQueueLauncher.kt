package io.github.sakurajay.lyra.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import io.github.sakurajay.lyra.core.source.PlayUrlException
import io.github.sakurajay.lyra.core.source.SwitchingPlayUseCase
import io.github.sakurajay.lyra.domain.model.Quality
import io.github.sakurajay.lyra.domain.model.Song

sealed interface LaunchOutcome {
    data class Playing(
        val requested: Quality,
        val effective: Quality,
        val viaSourceName: String,
        /** 非空表示听到的不是原点开那个平台的版本，而是换源后的 */
        val switchedFrom: String? = null,
    ) : LaunchOutcome

    data class Failed(val message: String) : LaunchOutcome
}

/**
 * 这次点击到底发生了什么，一句人话。
 *
 * 档位、降级、换源三件事都必须可见——静默换档或静默换源都会让用户以为
 * 自己听到的就是点的那首、那一档。三个列表页共用这一份措辞。
 */
fun LaunchOutcome.describe(): String = when (this) {
    is LaunchOutcome.Playing -> buildString {
        append("正在播放 · ").append(requested.label)
        if (effective != requested) append("（实际 ").append(effective.label).append("，已降级）")
        if (switchedFrom != null) append(" · 换源自 ").append(switchedFrom)
        append(" · 经 ").append(viaSourceName)
    }

    is LaunchOutcome.Failed -> message
}

/**
 * 从"列表里点了一首歌"到"服务真的开始播"的完整流程。
 *
 * 抽出来共享而不是每页抄一遍：中间那步 recordEffective 曾经在搜索页被漏掉，
 * 播放页的"实际音质"就永远停在"取址中"（真机反馈）。抄第二遍就会漏第二次。
 */
@OptIn(UnstableApi::class)
class SongQueueLauncher(
    private val context: Context,
    private val playUseCase: SwitchingPlayUseCase,
    private val qualityStore: PlaybackQualityStore,
) {

    private var client: PlayerClient? = null

    /**
     * @param queue 整列表一起下发，让系统媒体界面能看到上下曲；未解析的歌带 lyra:// 占位地址。
     */
    suspend fun launch(song: Song, queue: List<Song>): LaunchOutcome {
        val requested = qualityStore.requested.value
        val playUrl = try {
            playUseCase.playUrlFor(song, requested)
        } catch (e: PlayUrlException) {
            return LaunchOutcome.Failed(e.message ?: "所有音源都拿不到播放地址")
        } catch (e: Exception) {
            return LaunchOutcome.Failed("取址出错：${e.message ?: e.javaClass.simpleName}")
        }
        qualityStore.recordEffective(playUrl.effective)

        val connected = try {
            client ?: PlayerClient.connect(context).also { client = it }
        } catch (e: Exception) {
            return LaunchOutcome.Failed("连接播放服务失败：${e.message ?: e.javaClass.simpleName}")
        }

        // 列表在取址期间被换掉时，这首歌已经不在队列里了——只播它自己，
        // 而不是 coerce 到 0 变成"点了 A 播了 B"
        val index = queue.indexOfFirst { it.source == song.source && it.id == song.id }
        val effectiveQueue = if (index >= 0) queue else listOf(song)
        val artworkUrl = playUrl.artworkUrl ?: song.artworkUrl
        return when (val outcome = connected.playQueue(
            songs = effectiveQueue,
            startIndex = if (index >= 0) index else 0,
            resolvedStreamUrl = playUrl.url,
            resolvedArtworkUrl = artworkUrl,
            artworkData = ArtworkLoader.load(artworkUrl),
        )) {
            is PlaybackOutcome.Playing -> LaunchOutcome.Playing(
                requested = requested,
                effective = playUrl.effective,
                viaSourceName = playUrl.viaSourceName,
                switchedFrom = playUrl.switchedFrom,
            )

            is PlaybackOutcome.Failed -> LaunchOutcome.Failed(outcome.message)
        }
    }

    fun release() {
        client?.release()
        client = null
    }
}
