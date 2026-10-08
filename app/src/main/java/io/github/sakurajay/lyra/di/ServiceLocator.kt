package io.github.sakurajay.lyra.di

import android.content.Context
import io.github.sakurajay.lyra.core.source.RuntimePool
import io.github.sakurajay.lyra.core.search.AggregateSearch
import io.github.sakurajay.lyra.core.source.LyricLookup
import io.github.sakurajay.lyra.core.source.SourceHosting
import io.github.sakurajay.lyra.core.source.SourceLyricUseCase
import io.github.sakurajay.lyra.core.source.SourcePlayUseCase
import io.github.sakurajay.lyra.core.source.SourceSearchUseCase
import io.github.sakurajay.lyra.core.source.SwitchingPlayUseCase
import io.github.sakurajay.lyra.core.source.RuntimeLoader
import io.github.sakurajay.lyra.core.source.repo.SourceRepository
import io.github.sakurajay.lyra.core.source.runtime.SourceRuntime
import io.github.sakurajay.lyra.data.provider.builtin.KuwoSearchProvider
import java.io.File

/**
 * 手写依赖注入。
 *
 * 项目还没有引入 Hilt：M1 只有一个跨页共享依赖（音源存储）。按 Google 的架构指南，
 * 依赖少时用 ServiceLocator 比引入注解处理器更划算；等 M4 的 Room 与各仓库对象出现，
 * 再评估是否换成 Hilt，届时这里是唯一的替换点。
 */
object ServiceLocator {

    @Volatile
    private var repository: SourceRepository? = null

    fun sourceRepository(context: Context): SourceRepository {
        return repository ?: synchronized(this) {
            repository ?: SourceRepository(File(context.filesDir, "source-store")).also { repository = it }
        }
    }

    @Volatile
    private var pool: RuntimePool? = null

    fun runtimePool(context: Context): RuntimePool {
        return pool ?: synchronized(this) {
            pool ?: RuntimePool { stored ->
                val repo = sourceRepository(context)
                SourceRuntime.load(
                    preloadScript = SourceHosting.preload(context),
                    sourceScript = repo.scriptText(stored.id),
                    scriptId = stored.id,
                    requests = SourceHosting.newRequestExecutor(),
                )
            }.also { pool = it }
        }
    }

    fun playUseCase(context: Context): SourcePlayUseCase =
        SourcePlayUseCase(sourceRepository(context), RuntimeLoader { runtimePool(context).get(it) })

    fun sourceSearchUseCase(context: Context): SourceSearchUseCase =
        SourceSearchUseCase(sourceRepository(context), RuntimeLoader { runtimePool(context).get(it) })

    /** 取址失败会自动去别的平台找同一首歌，所以界面与服务两侧都要走这一个入口 */
    fun switchingPlayUseCase(context: Context): SwitchingPlayUseCase =
        SwitchingPlayUseCase(playUseCase(context), sourceSearchUseCase(context))

    fun aggregateSearch(context: Context): AggregateSearch =
        AggregateSearch(listOf(KuwoSearchProvider()), sourceSearchUseCase(context))

    @Volatile
    private var settings: io.github.sakurajay.lyra.data.settings.AppSettings? = null

    fun settings(context: Context): io.github.sakurajay.lyra.data.settings.AppSettings = settings
        ?: synchronized(this) {
            settings ?: io.github.sakurajay.lyra.data.settings.AppSettings(context).also { settings = it }
        }

    @Volatile
    private var qualityStore: io.github.sakurajay.lyra.player.PlaybackQualityStore? = null

    fun qualityStore(): io.github.sakurajay.lyra.player.PlaybackQualityStore = qualityStore
        ?: synchronized(this) {
            qualityStore ?: io.github.sakurajay.lyra.player.PlaybackQualityStore().also { qualityStore = it }
        }

    @Volatile
    private var favorites: io.github.sakurajay.lyra.data.local.FavoritesRepository? = null

    fun favoritesRepository(context: Context): io.github.sakurajay.lyra.data.local.FavoritesRepository =
        favorites ?: synchronized(this) {
            favorites ?: io.github.sakurajay.lyra.data.local.FavoritesRepository(
                io.github.sakurajay.lyra.data.local.FavoritesDatabase(context),
            ).also { favorites = it }
        }

    fun lyricLookup(context: Context): LyricLookup =
        LyricLookup(
            SourceLyricUseCase(sourceRepository(context), RuntimeLoader { runtimePool(context).get(it) }),
        )

    /**
     * 内置适配器各一个实例：每个都自带 OkHttpClient（连接池 + 线程池），
     * 每次进页面新建一个就是白建一套池子。
     */
    @Volatile
    private var leaderboardProvider: io.github.sakurajay.lyra.data.provider.builtin.KuwoLeaderboardProvider? = null

    fun leaderboardProvider(): io.github.sakurajay.lyra.data.provider.builtin.KuwoLeaderboardProvider =
        leaderboardProvider
            ?: synchronized(this) {
                leaderboardProvider ?: io.github.sakurajay.lyra.data.provider.builtin.KuwoLeaderboardProvider().also { leaderboardProvider = it }
            }

    @Volatile
    private var albumProvider: io.github.sakurajay.lyra.data.provider.builtin.KuwoAlbumProvider? = null

    fun albumProvider(): io.github.sakurajay.lyra.data.provider.builtin.KuwoAlbumProvider = albumProvider
        ?: synchronized(this) {
            albumProvider ?: io.github.sakurajay.lyra.data.provider.builtin.KuwoAlbumProvider().also { albumProvider = it }
        }

    @Volatile
    private var playlistProvider: io.github.sakurajay.lyra.data.provider.builtin.KuwoPlaylistProvider? = null

    fun playlistProvider(): io.github.sakurajay.lyra.data.provider.builtin.KuwoPlaylistProvider = playlistProvider
        ?: synchronized(this) {
            playlistProvider ?: io.github.sakurajay.lyra.data.provider.builtin.KuwoPlaylistProvider(
                albumProvider = albumProvider(),
            ).also { playlistProvider = it }
        }
}
