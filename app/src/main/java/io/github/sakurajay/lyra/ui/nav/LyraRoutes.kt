package io.github.sakurajay.lyra.ui.nav

/**
 * 路由表与路由分类。
 *
 * 单独成一个纯 Kotlin 文件（不碰 Compose）是为了能把分类这件事写成 JVM 单测：
 * 转场本身测不了，但"哪条路由属于哪一类"错了就等于整页动效挂错地方，而它以前
 * 只存在于 `LyraApp.kt` 里一份手抄的 `DETAIL_ROUTES` 集合——13 处注册各抄一遍参数，
 * 少一条就是一处"底下的 Tab 页跟着滑"的错误动画。
 */
enum class RouteKind {
    /** 顶层 Tab：进出都横向滑动，且切进详情页时不能跟着动 */
    TAB,

    /** 压在 Tab 之上的详情页：固定用"从右进入、向右退出" */
    DETAIL,

    /** 播放页：从迷你播放条长出来、缩回那条里，与详情页是两套观感 */
    PLAYER,

    /** 认不出来的路由：按详情页处理，宁可动效保守也不要让两页同时滑 */
    UNKNOWN,
}

/** 详情页带参数的路由：参数名与类型在这里钉死，注册处就不必再各写一遍 navArgument */
data class Arg(val name: String, val isInt: Boolean = false)

object LyraRoutes {

    const val HOME = "home"
    const val SEARCH = "search"
    const val LIBRARY = "library"
    const val PLAYER = "player"
    const val SOURCES = "sources"
    const val SETTINGS = "settings"
    const val ALBUM = "album/{albumId}"
    const val PLAYLIST = "playlist/{listId}"
    const val PLAYLIST_SQUARE = "playlists"
    const val MY_PLAYLIST = "my-playlist/{playlistId}"
    const val LOCAL_MUSIC = "local-music"
    const val CACHE = "cache"
    const val LIKED = "liked"
    const val MY_PLAYLISTS = "my-playlists"
    const val SAVED_PLAYLISTS = "saved-playlists"
    const val LEADERBOARD_SQUARE = "leaderboards"
    const val LEADERBOARD_DETAIL = "leaderboard/{boardId}"

    /** 顶层 Tab，顺序即导航栏顺序 */
    val TABS = listOf(HOME, SEARCH, LIBRARY)

    /**
     * 详情页路由 → 参数名。
     *
     * 参数直接拼进路径而不转义是有前提的：专辑 id 只有数字，歌单 id 是
     * `digest-8__123` 这种只含字母数字与 -_ 的形式。
     */
    val DETAILS: Map<String, Arg?> = linkedMapOf(
        SOURCES to null,
        SETTINGS to null,
        CACHE to null,
        LIKED to null,
        MY_PLAYLISTS to null,
        SAVED_PLAYLISTS to null,
        LOCAL_MUSIC to null,
        PLAYLIST_SQUARE to null,
        LEADERBOARD_SQUARE to null,
        ALBUM to Arg("albumId"),
        PLAYLIST to Arg("listId"),
        LEADERBOARD_DETAIL to Arg("boardId"),
        MY_PLAYLIST to Arg("playlistId", isInt = true),
    )

    /** 认一条注册用的路由 pattern 属于哪一类 */
    fun kindOf(route: String?): RouteKind = when {
        route == null -> RouteKind.UNKNOWN
        route == PLAYER -> RouteKind.PLAYER
        route in TABS -> RouteKind.TAB
        DETAILS.containsKey(route) -> RouteKind.DETAIL
        else -> RouteKind.UNKNOWN
    }

    /** 详情页之间互跳、或 Tab 被详情页压住时，底下的那一页不能跟着动 */
    fun isDetail(route: String?): Boolean = kindOf(route) == RouteKind.DETAIL

    /** 实际跳转用的路径（把参数拼进去） */
    fun album(albumId: String) = "album/$albumId"
    fun playlist(listId: String) = "playlist/$listId"
    fun board(boardId: String) = "leaderboard/$boardId"
    fun myPlaylist(playlistId: Int) = "my-playlist/$playlistId"
}
