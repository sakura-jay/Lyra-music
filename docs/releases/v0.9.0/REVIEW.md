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

## 5. 规格轴未处理项（要动 PLAN/工单，不只是动代码）

- **B7 / 工单 004「在播期间改上限不断流」没有任何验证**：PLAN §5 明写放设备测试（对照 `LyraCacheTest`），
  但 `LyraCacheTest` 本版一字未动，CHANGELOG 的"待续"没收回。ADR-0008 的全部理由就是这条，目前无人看管。
- ~~**C5 头部总数在 `total` 未知时整块消失**~~：随 P1 一起解决——头部只在平台真给了总数时显示
  "共 N 首"，页脚用 `home_end_of_list_unknown` 说"已显示 N 首"，不再拿本页条数冒充总数。
- **PLAN:126 要求"懒加载这些轻请求"，网易云那条不轻**：取一张封面要走 `n=100000` 的整榜目录（`NetEaseLeaderboardProvider.kt:63`），
  36 张榜 = 36 次整榜目录读。Jank 实测达标掩盖了它。
- **D1「滚出滚回不重复解码」在最坏情况不成立**：位图缓存 32 格 < 36 张 320px 封面（`ArtworkLoader.kt:38`），字节缓存只有 8 格。
  抬上限等于承认该换图片库，需要决策而不是改常量。
- **工单 004「调高不删除任何条目」没有用例**：`LruEvictionBudgetTest` 七例覆盖了下限读值/LRU 顺序/触碰刷新/调低即刻超限，缺"调高后 size 不变"。
- **B1/B6 只改了一半**：`strings.xml:91` 的 `cache_hint` 仍写死"上限 128 MB"。
- **C6「切榜丢弃晚到响应」不是靠丢弃逻辑实现的**：`LeaderboardDetailViewModel.kt:108` 的 `board != board` 守卫比较的是含 `coverUrl`
  的 data class，同榜重载时恒等；真正兜住的是 `:72` 的 `loadJob?.cancel()` 加 `LyraApp.kt:441` 按 boardId 建独立 VM。
  被取消的 `OkHttp.execute()` 仍会跑完（`Kuwo:54-61`）。测试覆盖的是 reload 不是切榜。
- **工单 013 没回写规格**：PLAN §4 已定决策、§7 切片清单、§5「完成长什么样」都没有它（只提了 012），按"规格有洞要回写"该补。
