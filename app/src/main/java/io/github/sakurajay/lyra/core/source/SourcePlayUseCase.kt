package io.github.sakurajay.lyra.core.source

import io.github.sakurajay.lyra.core.source.repo.SourceRepository
import io.github.sakurajay.lyra.core.source.repo.StoredSource
import io.github.sakurajay.lyra.domain.model.Quality
import io.github.sakurajay.lyra.domain.model.Song
import io.github.sakurajay.lyra.domain.model.toMusicInfo
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import org.json.JSONObject

class PlayUrlException(message: String) : Exception(message)

/**
 * @param effective 真正拿到的档位。请求 FLAC 只拿到 320k 时它是 HIGH，
 * UI 必须显示这个而不是 [requested]——见 ADR 的"自动降级 + 标注实际音质"。
 * @param switchedFrom 这首是从哪个平台换过来的（null 表示没换）。换源必须可见，
 * 否则用户点的是酷我的歌，听到的却是别家曲库的版本。
 */
data class PlayUrl(
    val url: String,
    val requested: Quality,
    val effective: Quality,
    val viaSourceName: String,
    /** 音源 pic action 给的封面地址；拿不到不影响播放 */
    val artworkUrl: String? = null,
    val switchedFrom: String? = null,
)

/** 能执行音源 action 的东西。真身是 SourceRuntime，测试里可以换成假的。 */
interface ActionInvoker {
    suspend fun invoke(source: String, action: String, info: JSONObject): JSONObject
}

/**
 * 借用语义：invoker 的生命周期归实现方。池化实现（[RuntimePool]）每次返回同一个
 * SourceRuntime，调用方用完就 close 等于把整条音源打死，下一首必然报「音源已卸载」。
 */
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

    /**
     * @param onStage 每次"再试一次"都要说一声。静默重试是真机反馈的坑：
     *   界面只有一圈转轮，用户不知道是在等网络还是在等第四个档位，只能一直等下去。
     */
    suspend fun playUrlFor(song: Song, requested: Quality, onStage: (String) -> Unit = {}): PlayUrl {
        val candidates = repository.enabledSources().filter { stored ->
            stored.capabilities[song.source]?.actions?.contains(ACTION_MUSIC_URL) == true
        }
        if (candidates.isEmpty()) {
            throw PlayUrlException("没有启用的音源声明支持「${song.source}」的播放地址获取，请先导入能播该平台的音源")
        }

        var lastError: String? = null

        for ((sourceIndex, stored) in candidates.withIndex()) {
            if (sourceIndex > 0) onStage("「${candidates[sourceIndex - 1].name}」给不出地址，正在试「${stored.name}」…")
            val invoker = try {
                runtimeLoader.load(stored)
            } catch (e: Exception) {
                lastError = "${stored.name}: ${e.message ?: e.javaClass.simpleName}"
                continue
            }

            val chain = qualityChain(requested, stored.capabilities[song.source]?.qualitys.orEmpty())
            var previous: Quality? = null
            for (quality in chain) {
                onStage(
                    previous?.let { "「${stored.name}」没有 ${it.label} 的地址，正在试 ${quality.label}…" }
                        ?: "正在向「${stored.name}」取址（${quality.label}）…",
                )
                previous = quality
                val info = JSONObject().put("musicInfo", song.toMusicInfo(quality)).put("type", quality.id)
                val outcome = try {
                    Result.success(invoker.invoke(song.source, ACTION_MUSIC_URL, info))
                } catch (e: TimeoutCancellationException) {
                    // 外层总预算到点时会把协程一起取消，那时不能把"超时"记成这个源的毛病
                    if (!currentCoroutineContext().isActive) throw e
                    lastError = "${stored.name}: 音源超时无响应"
                    // 后端无响应时沿档位链再等几轮是纯浪费：它不是"这一档没有"，是"它不回话"
                    break
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
                    return PlayUrl(
                        url = url,
                        requested = requested,
                        effective = quality,
                        viaSourceName = stored.name,
                        artworkUrl = runCatching {
                            resolveArtwork(invoker, song, supportsPic = supportsAction(stored, song.source, ACTION_PIC))
                        }.getOrNull(),
                    )
                }
                lastError = "${stored.name}: 未返回有效地址"
            }
        }

        throw PlayUrlException(lastError ?: "所有音源都拿不到播放地址")
    }

    /**
     * 封面走音源的 pic action：只有自报声明了 pic 的源才会去调。
     * 失败一律吞掉返回 null——封面缺失不该让已经拿到地址的歌放不出来。
     */
    suspend fun resolveArtwork(invoker: ActionInvoker, song: Song, supportsPic: Boolean): String? {
        if (!supportsPic) return null
        val info = JSONObject().put("musicInfo", song.toMusicInfo(Quality.STANDARD))
        val result = runCatching { invoker.invoke(song.source, ACTION_PIC, info) }.getOrNull() ?: return null
        return result.optJSONObject("data")?.optString("url")
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    private fun supportsAction(stored: StoredSource, source: String, action: String): Boolean =
        stored.capabilities[source]?.actions?.contains(action) == true

    /**
     * 请求档位及其下位，从高到低：FLAC 拿不到就退 320k 再退 128k。
     *
     * [supported] 是该音源自报有的档位（与 lx 的 host 一样按白名单过滤）。不传或
     * 过滤后为空时退回完整链——音源报了一堆我们不认识的档位字符串，不该因此播不了。
     */
    fun qualityChain(requested: Quality, supported: List<String> = emptyList()): List<Quality> {
        val full = Quality.entries.filter { it.ordinal <= requested.ordinal }.sortedByDescending { it.ordinal }
        if (supported.isEmpty()) return full
        return full.filter { it.id in supported }.ifEmpty { full }
    }

    private companion object {
        const val ACTION_MUSIC_URL = "musicUrl"
        const val ACTION_PIC = "pic"
    }
}
