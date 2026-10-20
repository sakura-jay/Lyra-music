package io.github.sakurajay.lyra.ui.nav

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 路由分类表。
 *
 * 转场本身测不了（要看真机），但"哪条路由属于哪一类"错了就等于整页动效挂错地方：
 * 详情页不算详情，底下的 Tab 页就会跟着一起滑。这份表以前是 `LyraApp.kt` 里手抄的
 * 集合，13 处注册各抄一遍参数；现在只此一份，并且由源码扫描守住"注册了但没分类"。
 */
class LyraRoutesTest {

    @Test
    fun everyDetailRouteIsClassifiedAsDetail() {
        assertEquals(13, LyraRoutes.DETAILS.size)
        LyraRoutes.DETAILS.keys.forEach { route ->
            assertEquals("$route 必须是详情页", RouteKind.DETAIL, LyraRoutes.kindOf(route))
            assertTrue(LyraRoutes.isDetail(route))
        }
    }

    @Test
    fun tabsAndPlayerAreNotDetails() {
        assertEquals(listOf("home", "search", "library"), LyraRoutes.TABS)
        LyraRoutes.TABS.forEach { route ->
            assertEquals(RouteKind.TAB, LyraRoutes.kindOf(route))
            assertFalse("Tab 页不能当详情页", LyraRoutes.isDetail(route))
        }
        assertEquals(RouteKind.PLAYER, LyraRoutes.kindOf(LyraRoutes.PLAYER))
        // 播放页是另一套观感（从迷你条长出来），不能被当成详情页滑进来
        assertFalse(LyraRoutes.isDetail(LyraRoutes.PLAYER))
        assertFalse(LyraRoutes.DETAILS.containsKey(LyraRoutes.PLAYER))
    }

    @Test
    fun unknownAndMissingRoutesFallBackToTheConservativeKind() {
        assertEquals(RouteKind.UNKNOWN, LyraRoutes.kindOf(null))
        assertEquals(RouteKind.UNKNOWN, LyraRoutes.kindOf("nope"))
        assertEquals(RouteKind.UNKNOWN, LyraRoutes.kindOf("album"))
        assertFalse(LyraRoutes.isDetail("nope"))
    }

    @Test
    fun placeholdersMatchTheDeclaredArguments() {
        LyraRoutes.DETAILS.forEach { (route, arg) ->
            if (arg == null) {
                assertFalse("$route 没声明参数就不该有占位符", route.contains('{'))
            } else {
                assertTrue("$route 的路径里必须有 {${arg.name}}", route.contains("{${arg.name}}"))
            }
        }
        // 歌单 id 是整数：注册处要用 navArgument(Int) 取，取成 String 会静默拿到 null
        assertEquals(Arg("playlistId", isInt = true), LyraRoutes.DETAILS[LyraRoutes.MY_PLAYLIST])
    }

    @Test
    fun pathBuildersFillThePatterns() {
        assertEquals("album/42", LyraRoutes.album("42"))
        assertEquals("playlist/digest-8__123", LyraRoutes.playlist("digest-8__123"))
        assertEquals("leaderboard/kuwo-1", LyraRoutes.board("kuwo-1"))
        assertEquals("my-playlist/7", LyraRoutes.myPlaylist(7))
        // 拼出来的路径必须落在注册用的那段静态前缀上，否则导航根本找不到目的地
        listOf(
            LyraRoutes.ALBUM to LyraRoutes.album("42"),
            LyraRoutes.PLAYLIST to LyraRoutes.playlist("digest-8__123"),
            LyraRoutes.LEADERBOARD_DETAIL to LyraRoutes.board("kuwo-1"),
            LyraRoutes.MY_PLAYLIST to LyraRoutes.myPlaylist(7),
        ).forEach { (pattern, path) ->
            assertTrue("$path 的前缀不是 $pattern", path.startsWith(pattern.substringBefore('{')))
        }
    }

    /**
     * 源码扫描：`LyraApp.kt` 里每一处 `lyraDetailRoute(...)` 引用的常量都必须在
     * [LyraRoutes.DETAILS] 里，且两边数量一致。
     *
     * 这条守的是"新增一个详情页，注册时忘了往表里加一行"——那种错误编译器不管，
     * 表现为那一页的转场悄悄变成默认行为。
     */
    @Test
    fun lyraAppRegistersExactlyTheClassifiedDetailRoutes() {
        val app = sourceFile("src/main/java/io/github/sakurajay/lyra/ui/LyraApp.kt").readText()
        val constants = routeConstants()

        val registered = Regex("""lyraDetailRoute\(([\s\S]{0,400}?)LyraRoutes\.([A-Z_]+)""")
            .findAll(app)
            .map { match -> constants[match.groupValues[2]] ?: error("未知路由常量 ${match.groupValues[2]}") }
            .toList()

        assertEquals("注册的详情页数量要和 DETAILS 一致", LyraRoutes.DETAILS.size, registered.size)
        assertEquals(LyraRoutes.DETAILS.keys.toSet(), registered.toSet())
        // 播放页单独注册，且只注册一次
        assertEquals(1, Regex("""composable\(\s*LyraRoutes\.PLAYER""").findAll(app).count())
    }

    /** 底部导航的三项必须与 [LyraRoutes.TABS] 一致，否则 Tab 会被当成详情页滑 */
    @Test
    fun topDestinationsMatchTheTabList() {
        val app = sourceFile("src/main/java/io/github/sakurajay/lyra/ui/LyraApp.kt").readText()
        val enumBlock = Regex("""private enum class TopDestination\(([\s\S]*?)\n\}""")
            .find(app)
            ?.groupValues
            ?.get(1)
            ?: error("在 LyraApp.kt 里找不到 TopDestination")
        val constants = routeConstants()
        val declared = Regex("""LyraRoutes\.([A-Z_]+)""")
            .findAll(enumBlock)
            .map { constants.getValue(it.groupValues[1]) }
            .toList()
        assertEquals(LyraRoutes.TABS, declared)
    }

    /**
     * 共享元素白名单：哪些路由有"详情大图那一端"。
     *
     * 认错的后果不是不好看而是飞错东西：「我喜欢」的页头是第一首曲目的图（一份代理），
     * 广场与设置根本没有页头，这两类一旦给出路径就会出现"从别处的卡片飞向一张不相干的图"。
     */
    @Test
    fun onlyTheFourCoverBearingDetailsHaveAPath() {
        assertEquals("album/42", LyraRoutes.coverPath(LyraRoutes.ALBUM, "42"))
        assertEquals("playlist/digest-8__123", LyraRoutes.coverPath(LyraRoutes.PLAYLIST, "digest-8__123"))
        assertEquals("leaderboard/kw__93", LyraRoutes.coverPath(LyraRoutes.LEADERBOARD_DETAIL, "kw__93"))
        assertEquals("my-playlist/7", LyraRoutes.coverPath(LyraRoutes.MY_PLAYLIST, "7"))

        // 页头是代理图 / 根本没有页头的那些：不给路径，也就自然不参与共享元素
        listOf(LyraRoutes.LIKED, LyraRoutes.LOCAL_MUSIC, LyraRoutes.PLAYLIST_SQUARE, LyraRoutes.LEADERBOARD_SQUARE, LyraRoutes.SETTINGS, LyraRoutes.SOURCES, LyraRoutes.CACHE, LyraRoutes.MY_PLAYLISTS, LyraRoutes.SAVED_PLAYLISTS, LyraRoutes.PLAYER, LyraRoutes.HOME).forEach {
            assertNull("$it 不该有详情页头那一端", LyraRoutes.coverPath(it, "whatever"))
        }
    }

    /**
     * 空与非法的参数不能拼出键。
     *
     * `album/`（空 id）与 `my-playlist/0`（navArgument 的默认值）都不是任何一条真路径，
     * 却让两端的键看起来"算出来了"——表现是飞行发生了、终点却对一个谁也没登记的键，
     * 封面飞到屏幕左上角去。
     */
    @Test
    fun blankOrPlaceholderIdsYieldNoPath() {
        assertNull(LyraRoutes.coverPath(LyraRoutes.ALBUM, ""))
        assertNull(LyraRoutes.coverPath(LyraRoutes.ALBUM, null))
        assertNull(LyraRoutes.coverPath(LyraRoutes.PLAYLIST, "  "))
        assertNull(LyraRoutes.coverPath(LyraRoutes.MY_PLAYLIST, "0"))
        assertNull(LyraRoutes.coverPath(LyraRoutes.MY_PLAYLIST, "not-a-number"))
        assertNull(LyraRoutes.coverPath(LyraRoutes.MY_PLAYLIST, null))
        assertNull(LyraRoutes.coverPath(null, "42"))
    }

    /** `const val NAME = "pattern"` 的常量名 → 值。表只有一处，扫出来比在测试里再抄一遍稳 */
    private fun routeConstants(): Map<String, String> {
        val source = sourceFile("src/main/java/io/github/sakurajay/lyra/ui/nav/LyraRoutes.kt").readText()
        return Regex("""const val ([A-Z_]+) = "([^"]*)"""")
            .findAll(source)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    /** 单测的工作目录是模块目录，但从 IDE 里跑可能是仓库根；两种都认 */
    private fun sourceFile(relativeToModule: String): File {
        val candidates = listOf(relativeToModule, "app/$relativeToModule")
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            candidates.forEach { candidate ->
                val file = File(dir, candidate)
                if (file.isFile) return file
            }
            dir = dir.parentFile
        }
        error("找不到 $relativeToModule（工作目录 ${File("").absolutePath}）")
    }
}
