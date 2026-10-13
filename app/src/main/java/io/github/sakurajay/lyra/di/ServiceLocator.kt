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
import io.github.sakurajay.lyra.data.provider.builtin.KuGouLeaderboardProvider
import io.github.sakurajay.lyra.data.provider.builtin.KuwoLeaderboardProvider
import io.github.sakurajay.lyra.data.provider.builtin.KuwoSearchProvider
import io.github.sakurajay.lyra.data.provider.builtin.LeaderboardProvider
import io.github.sakurajay.lyra.data.provider.builtin.NetEaseLeaderboardProvider
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
    fun switchingPlayUseCase(context: Context): SwitchingPlayUseCase {
        val settings = settings(context)
        return SwitchingPlayUseCase(
            playUseCase(context),
            sourceSearchUseCase(context),
            builtinSearchProviders(),
            // 读的是 StateFlow 当前值：设置里关掉之后，下一次点歌立刻生效
            autoSwitch = { settings.autoSwitch.value },
        )
    }

    /** 内置搜索适配器：每加一家就多一路聚合结果，也多为换源多一个落点 */
    @Volatile
    private var builtinSearchProviders: List<io.github.sakurajay.lyra.data.provider.builtin.BuiltinSearchProvider>? = null

    fun builtinSearchProviders(): List<io.github.sakurajay.lyra.data.provider.builtin.BuiltinSearchProvider> =
        builtinSearchProviders ?: synchronized(this) {
            builtinSearchProviders ?: listOf(
                io.github.sakurajay.lyra.data.provider.builtin.KuwoSearchProvider(),
                io.github.sakurajay.lyra.data.provider.builtin.KuGouSearchProvider(),
            ).also { builtinSearchProviders = it }
        }

    fun aggregateSearch(context: Context): AggregateSearch =
        AggregateSearch(builtinSearchProviders(), sourceSearchUseCase(context))

    @Volatile
    private var settings: io.github.sakurajay.lyra.data.settings.AppSettings? = null

    fun settings(context: Context): io.github.sakurajay.lyra.data.settings.AppSettings = settings
        ?: synchronized(this) {
            settings ?: io.github.sakurajay.lyra.data.settings.AppSettings(context).also { settings = it }
        }

    @Volatile
    private var qualityStore: io.github.sakurajay.lyra.player.PlaybackQualityStore? = null

    /** 默认音质从设置里读，改档位要写回去——否则重开应用永远回到无损 */
    fun qualityStore(context: Context): io.github.sakurajay.lyra.player.PlaybackQualityStore = qualityStore
        ?: synchronized(this) {
            qualityStore ?: io.github.sakurajay.lyra.player.PlaybackQualityStore(
                initialQuality = settings(context).requestedQuality.value,
                persist = { settings(context).setRequestedQuality(it) },
            ).also { qualityStore = it }
        }

    /**
     * 收藏与自建歌单共用一个 SQLiteOpenHelper。
     *
     * 同一个 db 文件开两个 helper 实例是合法的但没必要：两边都持有同一个连接池与
     * 版本升级路径，两个实例会让 onUpgrade 有被跑两次的机会。
     */
    @Volatile
    private var libraryDb: io.github.sakurajay.lyra.data.local.FavoritesDatabase? = null

    private fun libraryDb(context: Context): io.github.sakurajay.lyra.data.local.FavoritesDatabase =
        libraryDb ?: synchronized(this) {
            libraryDb ?: io.github.sakurajay.lyra.data.local.FavoritesDatabase(context).also { libraryDb = it }
        }

    @Volatile
    private var favorites: io.github.sakurajay.lyra.data.local.FavoritesRepository? = null

    fun favoritesRepository(context: Context): io.github.sakurajay.lyra.data.local.FavoritesRepository =
        favorites ?: synchronized(this) {
            favorites ?: io.github.sakurajay.lyra.data.local.FavoritesRepository(libraryDb(context))
                .also { favorites = it }
        }

    @Volatile
    private var playlists: io.github.sakurajay.lyra.data.local.PlaylistRepository? = null

    fun playlistRepository(context: Context): io.github.sakurajay.lyra.data.local.PlaylistRepository =
        playlists ?: synchronized(this) {
            playlists ?: io.github.sakurajay.lyra.data.local.PlaylistRepository(libraryDb(context))
                .also { playlists = it }
        }

    @Volatile
    private var favoritePlaylists: io.github.sakurajay.lyra.data.local.FavoritePlaylistRepository? = null

    fun favoritePlaylistRepository(context: Context): io.github.sakurajay.lyra.data.local.FavoritePlaylistRepository =
        favoritePlaylists ?: synchronized(this) {
            favoritePlaylists ?: io.github.sakurajay.lyra.data.local.FavoritePlaylistRepository(libraryDb(context))
                .also { favoritePlaylists = it }
        }

    @Volatile
    private var localMusic: io.github.sakurajay.lyra.data.local.LocalMusicRepository? = null

    /** 全进程一份：扫一次 MediaStore 不便宜，界面来回切不该来回扫 */
    fun localMusicRepository(context: Context): io.github.sakurajay.lyra.data.local.LocalMusicRepository =
        localMusic ?: synchronized(this) {
            localMusic ?: io.github.sakurajay.lyra.data.local.LocalMusicRepository(
                context.applicationContext.contentResolver,
            ).also { localMusic = it }
        }

    fun lyricLookup(context: Context): LyricLookup =
        LyricLookup(
            SourceLyricUseCase(sourceRepository(context), RuntimeLoader { runtimePool(context).get(it) }),
        )

    /**
     * 全部榜单来源：首页的精选预览与榜单广场的分段都从这里取。
     *
     * 酷我 / 酷狗 / 网易云三家，一家一个实例——每个都自带 OkHttpClient，每次进页面新建一个
     * 就是白建一套连接池。新增一家就是往这里追加一项，接口与页面都不必跟着改，
     * 这正是 [LeaderboardProvider] 存在的理由。
     *
     * `lazy` 而不是三份"字段 + volatile 双检锁"：这张表是只读的，第一次取就定型，
     * 没有"半个实例被人看见"的窗口。
     */
    fun leaderboardProviders(): List<LeaderboardProvider> = leaderboardProviders

    private val leaderboardProviders: List<LeaderboardProvider> by lazy {
        listOf(
            KuwoLeaderboardProvider(),
            KuGouLeaderboardProvider(),
            NetEaseLeaderboardProvider(),
        )
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
