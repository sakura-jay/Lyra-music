package io.github.sakurajay.lyra.core.source

import io.github.sakurajay.lyra.core.source.repo.SourceRepository
import io.github.sakurajay.lyra.core.source.repo.StoredSource
import io.github.sakurajay.lyra.domain.model.Quality
import io.github.sakurajay.lyra.domain.model.Song
import org.json.JSONArray
import org.json.JSONObject

class PlayUrlException(message: String) : Exception(message)

/**
 * @param effective 真正拿到的档位。请求 FLAC 只拿到 320k 时它是 HIGH，
 * UI 必须显示这个而不是 [requested]——见 ADR 的"自动降级 + 标注实际音质"。
 */
data class PlayUrl(
    val url: String,
    val requested: Quality,
    val effective: Quality,
    val viaSourceName: String,
)

/** 能执行音源 action 的东西。真身是 SourceRuntime，测试里可以换成假的。 */
interface ActionInvoker {
    suspend fun invoke(source: String, action: String, info: JSONObject): JSONObject
}

fun interface RuntimeLoader {
    suspend fun load(stored: StoredSource): ActionInvoker
}

/**
 * 用已导入的音源把一首歌换成可播放地址。
 *
 * 搜索来自内置适配器，地址来自音源——这是 ADR-0003 的双轨分工。
 * 选源规则：只看 enabled 且自报声明了该平台 musicUrl 的音源，按导入顺序试。
 */
class SourcePlayUseCase(
    private val repository: SourceRepository,
    private val runtimeLoader: RuntimeLoader,
) {

    suspend fun playUrlFor(song: Song, requested: Quality): PlayUrl {
        val candidates = repository.enabledSources().filter { stored ->
            stored.capabilities[song.source]?.contains(ACTION_MUSIC_URL) == true
        }
        if (candidates.isEmpty()) {
            throw PlayUrlException("没有启用的音源声明支持「${song.source}」的播放地址获取，请先导入能播该平台的音源")
        }

        val chain = qualityChain(requested)
        var lastError: String? = null

        for (stored in candidates) {
            val invoker = try {
                runtimeLoader.load(stored)
            } catch (e: Exception) {
                lastError = "${stored.name}: ${e.message ?: e.javaClass.simpleName}"
                continue
            }

            try {
                for (quality in chain) {
                    val info = JSONObject().put("musicInfo", song.toMusicInfo(quality)).put("type", quality.id)
                    val outcome = try {
                        Result.success(invoker.invoke(song.source, ACTION_MUSIC_URL, info))
                    } catch (e: Exception) {
                        Result.failure<JSONObject>(e)
                    }
                    if (outcome.isFailure) {
                        val error = outcome.exceptionOrNull()
                        lastError = "${stored.name}: ${error?.message ?: error?.javaClass?.simpleName}"
                        // 不 break：ADR 的自动降级要求同一个源沿档位链往下试，
                        // 只有整条链都失败才换下一个音源
                        continue
                    }
                    val url = outcome.getOrThrow().optJSONObject("data")?.optString("url").orEmpty()
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        return PlayUrl(url, requested, quality, stored.name)
                    }
                    lastError = "${stored.name}: 未返回有效地址"
                }
            } finally {
                (invoker as? AutoCloseable)?.close()
            }
        }

        throw PlayUrlException(lastError ?: "所有音源都拿不到播放地址")
    }

    /** 请求档位及其下位，从高到低：FLAC 拿不到就退 320k 再退 128k。 */
    fun qualityChain(requested: Quality): List<Quality> =
        Quality.entries.filter { it.ordinal <= requested.ordinal }.sortedByDescending { it.ordinal }

    /**
     * 映射成音源期望的 musicInfo 形状（字段名对齐官方洛雪的歌曲模型）：
     * singer 是 "/" 连接的字符串，interval 是 "mm:ss"，_types 带各档位尺寸。
     *
     * 空值一律**不写键**。音源用 `musicInfo.hash ?? musicInfo.songmid ?? musicInfo.id`
     * 取值，而 ?? 只认 null/undefined——写一个 hash:"" 会让整条链停在空串上，
     * 表现为音源报"缺少 songId"（真机踩到）。
     */
    fun Song.toMusicInfo(requested: Quality): JSONObject {
        val info = JSONObject()
            .put("name", name)
            .put("singer", artists.joinToString("/"))
            .put("albumName", albumName)
            .put("songmid", id)
            .put("id", id)
            .put("interval", formatInterval(durationSec))
            .put("source", source)
        if (albumId.isNotBlank()) info.put("albumId", albumId)
        if (hash.isNotBlank()) info.put("hash", hash)
        if (mediaMid.isNotBlank()) info.put("strMediaMid", mediaMid)

        val types = JSONObject()
        qualities.forEach { types.put(it.quality.id, JSONObject().put("size", it.sizeLabel ?: "")) }
        info.put("types", JSONArray(qualities.map { it.quality.id }))
        info.put("_types", types)
        return info
    }

    private fun formatInterval(seconds: Int): String =
        if (seconds <= 0) "00:00" else "%02d:%02d".format(seconds / 60, seconds % 60)

    private companion object {
        const val ACTION_MUSIC_URL = "musicUrl"
    }
}
