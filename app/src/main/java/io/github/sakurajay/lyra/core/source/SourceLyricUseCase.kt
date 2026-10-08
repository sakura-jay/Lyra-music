package io.github.sakurajay.lyra.core.source

import android.util.Log
import io.github.sakurajay.lyra.core.source.repo.SourceRepository
import io.github.sakurajay.lyra.domain.lyric.LyricDoc
import io.github.sakurajay.lyra.domain.lyric.LyricParser
import io.github.sakurajay.lyra.domain.model.Quality
import io.github.sakurajay.lyra.domain.model.Song
import io.github.sakurajay.lyra.domain.model.toMusicInfo
import org.json.JSONObject

/**
 * 用音源的 lyric action 取歌词。
 *
 * 拿不到就返回空文档而不是抛异常：没有歌词的歌是多数，歌词缺失不该打扰播放。
 * 逐字只能来自 lxlyric——只有它带 `<起,耗>` 标签，lyric 那份一般是干净 LRC，
 * 所以三个字段一起交给解析器，由它决定用哪份。
 */
class SourceLyricUseCase(
    private val repository: SourceRepository,
    private val runtimeLoader: RuntimeLoader,
) {

    suspend fun lyricFor(song: Song): LyricDoc {
        val candidates = repository.enabledSources().filter { stored ->
            stored.capabilities[song.source]?.contains(ACTION_LYRIC) == true
        }
        if (candidates.isEmpty()) {
            Log.i(TAG, "没有启用中的音源声明 ${song.source} 的 lyric 能力，跳过取词")
            return LyricDoc(emptyList())
        }
        for (stored in candidates) {
            val invoker = runCatching { runtimeLoader.load(stored) }
                .onFailure { Log.w(TAG, "${stored.name}: 运行时加载失败 ${it.message}", it) }
                .getOrNull() ?: continue
            val info = JSONObject().put("musicInfo", song.toMusicInfo(Quality.STANDARD))
            val outcome = runCatching { invoker.invoke(song.source, ACTION_LYRIC, info) }
            if (outcome.isFailure) {
                Log.w(TAG, "${stored.name}: lyric action 失败 ${outcome.exceptionOrNull()?.message}")
                continue
            }
            val data = outcome.getOrThrow().optJSONObject("data")
            if (data == null) {
                Log.w(TAG, "${stored.name}: 返回里没有 data 字段，整份=${outcome.getOrThrow()}")
                continue
            }
            val doc = LyricParser.parse(
                lrc = data.optString("lyric"),
                translation = data.optString("tlyric"),
                wordLrc = data.optString("lxlyric"),
            )
            Log.i(
                TAG,
                "${stored.name}: lyric=${data.optString("lyric").length} 字 / tlyric=${data.optString("tlyric").length} 字 / " +
                    "lxlyric=${data.optString("lxlyric").length} 字 → 解析出 ${doc.lines.size} 行",
            )
            if (!doc.isEmpty) return doc
        }
        return LyricDoc(emptyList())
    }

    private companion object {
        const val TAG = "LyraLyric"
        const val ACTION_LYRIC = "lyric"
    }
}
