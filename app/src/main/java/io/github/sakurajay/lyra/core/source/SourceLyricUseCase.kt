package io.github.sakurajay.lyra.core.source

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
        for (stored in candidates) {
            val invoker = runCatching { runtimeLoader.load(stored) }.getOrNull() ?: continue
            val info = JSONObject().put("musicInfo", song.toMusicInfo(Quality.STANDARD))
            val data = runCatching { invoker.invoke(song.source, ACTION_LYRIC, info).optJSONObject("data") }.getOrNull()
                ?: continue
            val doc = LyricParser.parse(
                lrc = data.optString("lyric"),
                translation = data.optString("tlyric"),
                wordLrc = data.optString("lxlyric"),
            )
            if (!doc.isEmpty) return doc
        }
        return LyricDoc(emptyList())
    }

    private companion object {
        const val ACTION_LYRIC = "lyric"
    }
}
