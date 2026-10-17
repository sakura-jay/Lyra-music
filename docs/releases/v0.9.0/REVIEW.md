# v0.9.0 代码审查记录与后续修复计划

> 双轴审查（标准轴 / 规格轴）的结论落在这里，避免下次从头再查一遍。
> 规格原文见同目录 `PLAN.md`，结果记录见 `CHANGELOG.md`（其「发版后修订」一节即本文件第 2 节的执行结果）。
> 审查基准：`git diff v0.8.0..HEAD`（61 文件，+3510/−830）。

## 1. 两轴结论摘要

- **标准轴**：结构与状态机干净（分段、懒取、在途去重、分页测试迁移都到位），但三处注释在讲代码没做的事，
  三家适配器的错误语义各自一套，两条本版新写的"防陷阱"断言其实是空的。
- **规格轴**：A/B/C 三组用户故事基本逐条落地，PLAN §6 的"明确不做"全部守住；
  缺口集中在 B7 唯一的验收手段没建、C7 的"错误态"在实现里不可达、工单 013 没回写规格。

两轴互不覆盖：标准轴的注释/断言问题与 PLAN 无关，规格照跑也照样该修；
规格轴的 B7、C7 则是代码完全合规、但需求最关键的验证从没建过。

## 2. 已完成（P0，提交 `d5a2e1e`）

| 条目 | 落点 |
| --- | --- |
| 酷狗榜明文端点不能改 https 的证据回写 | `KuGouLeaderboardProvider.kt` 端点注释 + `network_security_config.xml` 边界段。实测：证书 `CN=default.chinanetcenter.com`、SAN 不含该域、`SEC_E_WRONG_PRINCIPAL`；`mobilecdn`/`exrank`/`api` 同样失败，`www`/`songsearch` TLS 正常但无此路径 |
| 酷狗测试的空断言转真 | `KuGouLeaderboardProviderTest.kt`：加"单页 ≥20 首""total>100"，`assumeTrue` 收窄为"请求根本没回来"。红色形态已取证：喂 lx 老参数得 `200 / total=4 / 4 首` |
| 网易云恒真断言换掉 | `NetEaseLeaderboardProviderTest.kt` 新增 `qualitiesMatchRawTiers`：自己再打一次明文 `song/detail`，逐档核对"报出的可用"与"原始节点 size>0"一致 |
| 三处失效注释 | `KuwoLeaderboardProvider.kt` 类注释（base64/NoPadding → 原始 JSON+PKCS5，指向 `encrypt`）；`LeaderboardSquareViewModel.kt`（片间并发、片内顺序，12 张榜同时在飞是 3 路）；`ArtworkLoader.kt`（32 格装不下 36 张榜封面，被挤出去的滚回来重解） |

## 3. P1：三家失败语义统一 + `total` 不再假装知道 —— 已完成

> 执行结果见 `CHANGELOG.md` 的「发版后修订（二）」。下面是当初定的做法，留着是为了说明
> 为什么改成这个样子；与实际实现的偏差只有三处：
> ① 共同的 GET→解密→JSON 形状抽成了 `fetchBoardJson`（本来只打算加异常类型）；
> ② 网易云"重载失败"从静默复用旧目录改成抛出；③ 翻页失败不再置 `noMore`，并新增
> `home_end_of_list_unknown` 文案给未知总数用。**这一片同时解掉规格轴的 C7 与 C5 的总数分支。**

**这一片会改行为**，所以单独一片做，别和 P0 那种纯注释/纯测试混在一起。它同时解掉规格轴的 C7。

### 3.1 范围与调用面（已核实，改动只碰这些）

`songs()` 的调用方只有三处：`HomeViewModel.kt:98`（`runCatching`，封面取失败即跳过）、
`LeaderboardSquareViewModel.kt:79`（同上）、`LeaderboardDetailViewModel.kt:197`（经注入的 `fetchBoard`）。
`BoardPage` 的构造点：三个 provider + `Leaderboard.kt` + 两个 androidTest。
抛异常不会波及前两者——它们的 `runCatching` 本来就为失败准备。

### 3.2 数据层

1. `Leaderboard.kt` 新增 `class LeaderboardException(message: String) : Exception(message)`，
   形状对齐兄弟适配器（`AlbumException` `KuwoAlbumProvider.kt:25`、`SearchException` `BuiltinSearchProvider.kt:23`）。
   三家共用一个类型，消息里带来源与状态码。
2. 三家把"传输失败 / 解密失败 / 不是 JSON / 平台错误码非 200(errcode!=0)"改为 **抛** `LeaderboardException`；
   只有"接口正常回来但 `musiclist`/`info`/`songs` 是空数组"才返回空列表。
   - 酷我：`KuwoLeaderboardProvider.kt:62,66,69,72`；`:70` 补 `has("code")` 守卫，与 `KuGouLeaderboardProvider.kt:62`、`NetEaseLeaderboardProvider.kt:103` 对齐。
   - 酷狗：`KuGouLeaderboardProvider.kt:56,64,66,67`。
   - 网易云：`NetEaseLeaderboardProvider.kt:48,82,83,84` 与 `get()` 的三处 `return null`（`:97,:101,:105`）——`get()` 改成要么返回 payload 要么抛，别再回 null。
3. **不要吞 `CancellationException`**：现在 `runCatching`（`Kuwo:53`、`KuGou:46`、`NetEase:88`）把它一起吞了。
   捕获处显式重抛，写法照 `LeaderboardDetailViewModel.kt:91-92`。
4. `BoardPage.total` 改成 `Int?`，**null 表示平台没给总数**：
   - 酷我 `KuwoLeaderboardProvider.kt:83` 去掉 `?: songs.size`；
   - 酷狗 `KuGouLeaderboardProvider.kt:74` 同理；
   - 网易云的 `trackIds` 是整榜，`catalog.ids.size` 是真实条数，可以留作兜底（`NetEaseLeaderboardProvider.kt:59,69`）。

### 3.3 详情页（`LeaderboardDetailViewModel.kt` / `LeaderboardDetailScreen.kt`）

5. `noMore` 判定：`total != null` 时用 `songs.size >= total`；`total == null` 时用"这一页取满 `PAGE_SIZE` 就还能翻"。
   落点 `:88` 与 `:118`。省得"只翻一页却宣布已显示全部 100/100"。
6. 加真正的失败态：`UiState` 增 `failed: String?`（对齐 `PlaylistSquareViewModel` 的 `failed`），
   首屏失败置 `failed`、`songs` 清空；`:93-95` 的 catch 从"几乎不可达"变成这一片的主路径。
   翻页失败保留 `songs` 并置 `failed`，**不要**再无条件 `noMore = true`（`:126`）。
7. UI：页脚照抄 `PlaylistSquareScreen.kt:175-199` 的 `GridFooter` 形状（转圈 / 失败+重试 / 到底），
   头部总数只在 `total != null` 时显示（`:134`）；`total == null` 时页脚说"已显示 N 首"而不是"已显示全部 N/0 首"。
   新增字符串：`leaderboard_detail_failed`、`home_end_of_list_unknown`（现有 `home_end_of_list` 需要两个参数，撑不住未知总数）。
   `leaderboard_detail_empty` 那句"可能是接口没返回歌曲"归给"真的空榜"，别再当错误态用。

### 3.4 测试

8. `LeaderboardDetailPagingTest.kt`：`fakePage` 的 `total` 改可空；补两条——
   **total 缺失时不许在第一页就判到底**（短页才停），**fetch 抛异常时进入 `failed` 且能被重试拉回**。
9. 三家 provider 的设备侧测试：把"参数被拒"这类失败从"返回空"改判为"抛 `LeaderboardException`"。
   注意 `KuwoLeaderboardProviderTest` / `KuGou…Test` / `NetEase…Test` 里的 `assumeTrue(不可达)` 语义要跟着收窄——
   现在网络不可达会走异常，`assume` 得改成捕异常后再 assume，否则回归在断网机器上变红。
10. 验收口径：`compileDebugAndroidTestKotlin` + JVM 单测 + `lintDebug` 0 error，模拟器跑
    三家 provider 测试 + `LeaderboardDetailPagingTest` + `LeaderboardSquareViewModelTest`。Jank 不在这片影响面里。

## 4. P2 结构性异味 —— 前五组已完成，第六组（共享翻页件）推到 M9

| # | 条目 | 结果 |
| --- | --- | --- |
| 1 | 三份 `USER_AGENT` + 九份 `defaultClient` + 三份 https 归一 + Mb 格式化跨平台引用 | ✅ `06e4a91` 新建 `BuiltinHttp`。**超时秒数故意留在各家调用点**：榜单/歌词是轻响应（10/15s），搜索/专辑/歌单要翻整页（15/20s），只收样板不改数值。三家档位表那个 `(quality to 字段名)` 形状**没合**——每家的键名与是否带独立 hash 不同，硬抽就要把平台差异塞进参数团 |
| 2 | `TrackListScreen` 十二个参数 | ✅ `699c3a2` 行内 ♥ 那一对（读状态 + 切换）总是一起动，捏成 `RowFavorites`（12→11）；`Song` 全限定名换 import |
| 3 | `ServiceLocator` 三份双检锁样板 + 只差一个 s 的两个方法 | ✅ `818e563` 换成一份 `lazy` 列表；三个单独访问器经查**没有外部调用方**，删掉 |
| 4 | `LyraCache` 自造 `AppSettings`；两次 `entries()` 扫描算释放量 | ✅ `818e563` 走 `ServiceLocator.settings`；释放量由 `AdjustableLruCacheEvictor.setLimit` 记账返回 |
| 5 | 小项包 | ✅ `699c3a2`：未用 import、`home_more` 取代借用的 `home_playlists_more`、`boardRoute()`、榜 id 查不到时不再冒充 `bangId`、网易云 `c` 参数按 100 分批且只认数字 id、`LibraryViewModel` 的 MediaStore 扫描移到真正展示本地音乐的 `LibraryScreen` |
| 6 | 分页状态机两份（`LeaderboardDetailViewModel` 与 `PlaylistSquareViewModel`）+ 触底探针块两份 | ⏳ **未做**，见下 |

**第 6 组为什么不在这片做**：P1 只改了榜单详情的翻页语义（`total` 可空、失败不再等于到底、
`failed` 与空榜分开），歌单广场那份还是老语义——两边现在**语义不同构**。这时候抽"共享 Pager"
要么把差异塞进参数、要么顺手改掉歌单广场的行为，两者都不是清理，是又一次设计。
该做的是先在 M9 把两页的分页语义对齐（`total` 未知怎么判、失败怎么给重试），再抽。
`LeaderboardDetailPagingTest` 已经把语义钉住了，抽取时那六条测试就是回归线。

另外两处审查记的问题**明确不改**：`navArgument(...) { defaultValue = "" }` 是这一族详情页的
统一写法（专辑/歌单/自建歌单都这样），不是榜单的疏忽；VM 消息里直接写中文（`"正在取址…"` 等）
是仓库既有分工——界面文案进 `strings.xml`，运行时提示留在 VM。

## 5. 规格轴条目现状（2026-10-13 更新）

- ✅ **B7 / 工单 004「在播期间改上限不断流」已补设备测试**：`LyraCacheTest` 加两条——
  `loweringLimitEvictsAtOnceWithoutRebuildingTheCache`（调低当场淘汰最久没听的，`assertSame` 钉住
  `SimpleCache` 没被重建）与 `changingLimitWhileAStreamIsOpenDoesNotBreakTheStream`（开一条真在读的流
  再把上限压到 1 字节：在播那条删不掉是合法的，但改上限不许打断它、也不许把异常抛给调用方）。
  写第一条时我把上限设得比另一条资源本身还小，结果两条都该腾掉——**产品是对的、断言错了**，已改。
  CHANGELOG 里那句"待续"到此收回。
- ✅ ~~C5 头部总数在 `total` 未知时整块消失~~：随 P1 一起解决——头部只在平台真给了总数时显示
  "共 N 首"，页脚用 `home_end_of_list_unknown` 说"已显示 N 首"，不再拿本页条数冒充总数。
- ✅ **已修（同日）网易云取封面的"轻请求"**，且诊断要更正：这个端点的 `n` 控制的**不是** `trackIds`
  的长度，而是**顺带内嵌多少个 `tracks` 完整对象**。实测四张榜（200/100/50/100 首）：`n=0` 与
  `n=100000` 回的 `trackIds` 条目数一模一样，体积是 38KB 对 485KB、12KB 对 129KB。
  而 `loadCatalog` 只用 `trackIds`（曲目详情另外打 `v3/song/detail`），等于每次进榜白下几百 KB 再丢掉。
  改成 `n=0` 就好，**不需要给 `LeaderboardProvider` 加"只要封面"的方法**——原以为要动接口形状，是错的。
- ✅ **D1「滚出滚回不重复解码」已修**：位图缓存从 32 抬到 48（`ArtworkLoader.kt`），按 320px ≈0.4MB/张
  算满打满算约 19MB，装得下广场 36 张再留 12 张给详情头图与首页。注释里的内存账同步改了。
  字节缓存 8 格没动：位图命中就不回字节层，它只在"同 URL 多处共用一次下载"那一层起作用。
- ✅ **工单 004「调高不删除任何条目」已有用例**：`raisingLimitLeavesEverythingInPlace`（P2 那批补的）。
- ✅ **B1/B6 补全**：`cache_hint` 不再写死"上限 128 MB"，改成"超过上面的上限"——数字由页顶的
  上限段与「当前 X」给，帮助文案里重复一个会过期的数字正是这次审查抓到的病根。
- ✅ **C6「切榜丢弃晚到响应」现在真有丢弃逻辑了**（同日）：两层。
  ① `fetchBoardJson` 从阻塞 `execute()` 换成 `enqueue` + `invokeOnCancellation { call.cancel() }`——
  原先取消协程只是不写状态，请求照跑完、字节白花；现在取消即断。
  ② `LeaderboardDetailViewModel` 加代次号（每次 `load()` 自增，写状态前认号），管的是取消来不及的
  那一瞬：响应已经回到协程、正要写列表。同时删掉了原先那个恒等的 `board != board` 假守卫
  （`Board` 是含 `coverUrl` 的 data class，同榜重载时两边相等，它一直什么都没做）。
  新增 `dropsLatePageFromSupersededLoadMore` 钉住行为（第二页在途时重载，不许接进新列表、
  不许把新加载标成正在翻页）。诚实一句：这条测试今天靠取消也能过，代次号独有的那条时序在
  测试里逼不出来——它钉的是"不许串"这个结果，不是"谁去丢"。
- ✅ **工单 013 已回写规格**：PLAN §5 加了"仪器化测试一律走独立数据库"这条硬约束与其后果，
  §7 切片清单补上 S6（`012` + `013`）。

## 6. 模拟器截图走查（2026-10-13，P1/P2 之后）

走查对象是"只验到状态机、没用人眼看过"的两处：P1 的失败态页脚，P2 挪动本地扫描后的音乐库；
顺带把「我喜欢」整链路重走了一遍（P2 归组了 `RowFavorites`，行内 ♥ 属于回归面）。

**通过**：首页两排 + 两个「更多」（`home_more` 改名后两处都对）；榜广场分段双列网格、封面真图；
榜详情头部"飙升榜 / 共 298 首"+ 榜封面、序号前三加重；**断网点重载 → 页脚给失败原因 + 「重试」，
头部保留榜名与封面、总数消失**（P1 的 `total=null` 分支在真机上就是这个样子）；
**恢复网络点重试 → 列表与总数一起回来**；音乐库四卡片与短空态；「我的歌单」「收藏的歌单」两页正常
（不再各扫一遍 MediaStore 之后，本地音乐卡片仍显示"没有找到可播放的音频文件"+「重新扫描」，说明扫描
由音乐库页发起这条链没断）；我喜欢：更多操作面板点**文字标签**即命中 → 库卡片「我喜欢 · 1 首」+ 该曲
真实专辑图 → 我喜欢页头图 / "共 1 首" / 无序号列 / 行内实心 ♥ / 行尾 ⋯ 全在 → 点 ♥ 行立即消失并给页内
引导 → 回音乐库卡片回到"还没有喜欢的歌"。

**新抓到三处观感问题**：
1. ✅ **已修（同日）**失败文案把 Java 异常原样给用户看：`榜单加载失败：kw 榜单请求失败：UnknownHostException Unable to
   resolve host "wbd.kuwo.cn": No address associated with hostname`。改法是两边各取所需：
   `LeaderboardException` 带上 `cause`，界面按 `cause` 翻译（`IOException` → "网络不通，连不上榜单接口"，
   其余 → "接口没给回数据，稍后再试"），原始异常与堆栈交给 `Log.w("LyraBoardDetail", …)`。
   模拟器复验：断网点重载显示"榜单加载失败：网络不通，连不上榜单接口"+「重试」，
   logcat 里该标签仍有完整堆栈可查。
2. ✅ **已修（同日，改法 1）**我喜欢页在收藏为 0 时头部显示「共 0 首」+ 一个 `♪` 空块。工单 012 对**卡片**明确写了"不再出现 0 首
   这种计数"，页头没同步这条判断（`TrackListScreen` 的头部总数无空态分支）。
   改法是空列表时整行计数不渲染（`list?.takeIf { it.songCount > 0 }`），`♪` 封面占位保留。
   模拟器复验：空我喜欢页只剩标题 + 占位 + 页内引导，"共 0 首"不再出现；加回一首后"共 1 首"照常显示。
   注意这条判断落在共享页上，专辑/平台歌单/自建歌单/本地音乐的空态头部同样不再报 0。
3. ✅ **已修（同日）**`strings.xml` 里的 `♥`（U+2665）在系统字体下渲染成**红色 emoji ❤**，跳出整套配色——出现在
   `library_empty` 与 `library_no_favorite_playlists` 两条空态引导里。改成 `&#x2665;&#xFE0E;`
   （补文本变体选择符），模拟器复验：♥ 已按正文字色渲染，不再是红色 emoji。
