# v0.9.1 变更记录（M8 补丁）

本版是 `v0.9.0` 双轴代码审查的收口：标准轴 P0/P1/P2、规格轴里能靠代码补的、以及模拟器走查抓到的
观感问题，全部落在这一版。**没有新增用户可见功能**，审查的完整结论与逐条现状在
[`../v0.9.0/REVIEW.md`](../v0.9.0/REVIEW.md)（那份审的是 `v0.8.0..v0.9.0` 这个 diff）。

状态：**待真机验收后打 tag `v0.9.1`**。这批直接在 `main` 上按主题提交（13 条），没走
`dev/v0.9.1` 分支——它是审查驱动的补丁，不是一次里程碑构建。版本 `0.9.1` / `versionCode 6`。

## 摘要（面向用户）

- 榜单取不到时会说"网络不通，连不上榜单接口"并给「重试」，不再显示英文异常，也不再和
  "这个榜本来就是空的"混成同一句话。
- 列表页头部不再出现"共 0 首"；我喜欢页空态只剩引导。
- 空态文案里的 ♥ 不再被系统渲染成红色 emoji。
- 缓存说明不再写死"上限 128 MB"；"调高上限不删任何条目"这条有了测试钉住。
- 网易云每次进榜少下几百 KB 无用数据；被取消的榜单请求现在真的会断掉。
- 缓存上限页、我喜欢页、榜单广场与详情、首页两排在模拟器上逐屏走查过。

## P0：明文端点证据回写、两条空断言转真、失效注释改正（2026-10-13）

发布后按双轴审查跑了标准轴的 P0 三条，都在 `v0.9.0` 之上修掉；**没有任何播放/取数行为改动**，
用户可感知的变化只有"两条测试现在抓得住它们声称要抓的陷阱"。

- **酷狗榜的明文端点经实测无法改 https**：`mobilecdnbj.kugou.com` 挂在网宿 CDN 的默认站点上，
  证书 subject 是 `CN=default.chinanetcenter.com`，SAN 里不含这个域，换 https 必在 TLS 名称校验
  阶段失败（`SEC_E_WRONG_PRINCIPAL`；`mobilecdn` / `exrank` / `api` 同样握手失败，`www` 与
  `songsearch` TLS 正常但没有这条路径）。工单 007 第 60 行的决定成立。改的是**文档失实**：
  `network_security_config.xml` 原写"App 自身的接口请求仍走 https"，现补上这条例外与理由，
  适配器端点注释记下证据，免得下一个人重趟。
- **两条"看陷阱"的测试其实是瞎的**：`KuGouLeaderboardProviderTest` 只断言非空，而参数被拒时
  接口照样 200 只回 0–4 首——现在断言单页 ≥20 首且 total>100（`assumeTrue` 收窄为"请求根本没
  回来"才跳）。红色形态已取证：同一端点喂 lx 老参数得 `total=4 / 4 首`，旧断言全绿、新断言当场判红。
  `NetEaseLeaderboardProviderTest` 原来的 `all { it in Quality.entries }` 恒真（适配器只可能构造
  那四档），换成新增的 `qualitiesMatchRawTiers`：自己再打一次明文 `song/detail`，逐档核对
  "报出的可用"与"原始节点 size>0"是否一致——192k（`m`）被塞成 320k 时唯一会红的地方。
- **三处注释在讲代码没做的事**：酷我签名的"先 base64 再 AES-ECB(NoPadding)"早已实测证伪（原始
  JSON + PKCS5），旧句删除并指向 `encrypt`；榜单广场封面并非"每一片内部并发"，实为片间并发、
  片内顺序（12 张榜同时在飞是 3 路）；`ArtworkLoader` 的 32 格位图缓存装不下三家各 12 张共 36 张
  榜封面，被挤出去那几张滚回来要重解一次——这条同时是规格轴 D1 的缺口，抬上限属换图片库的决策，
  本版不动。

验证：`compileDebugKotlin` + `compileDebugAndroidTestKotlin` + JVM 单测全绿；Pixel_8 模拟器实跑
两条 provider 测试 `tests=5 / failures=0 / skipped=0`（新测试真联网 2.8s，没被 assume 跳过）；
`lintDebug` 0 error。

## P1：三家榜单失败语义与 `total`（标准轴 P1，解规格轴 C7）

上一节末尾列的"失败折叠成空榜"与"`total` 缺失时冒充总数"两条一起处理了。**这是行为变更**，
所以单独一片做，不与纯注释/纯测试的 P0 混提交。

- **取数失败不再是空榜**：新增 `LeaderboardException`（形状对齐同包的 `AlbumException`/`SearchException`），
  三家把"HTTP 非 2xx / 解密失败 / 响应不是 JSON / 平台错误码（`code`!=200、`errcode`!=0）"一律抛出；
  只有"接口正常回来而 `musiclist`/`info`/`songs` 是空数组"才算空榜。共同的 GET→解密→JSON 形状抽到
  `fetchBoardJson`，顺手把三条没人写的守卫补齐：酷我原来缺 `has("code")`，与另两家不一致；
  协程取消不再被 `runCatching` 吞掉（provider 与详情页翻页各补一处显式上抛）。
- **界面上的错误态因此可达了**：详情页 `UiState` 增 `failed`，首屏失败显示原因 + 「重试」
  （形状照抄歌单广场的 `GridFooter`），不再只会说"这个榜暂时没有内容"；翻页失败保留已取到的曲目，
  且**不再把 `noMore` 置真**——"这一次没取到"不是"后面没有了"。
  空榜与失败从此是两条分支：`leaderboard_detail_empty` 只说真的没歌。
- **`BoardPage.total` 改为可空**（null＝平台没给总数），三家不再拿"本页条数"兜底。
  详情页判到底改用双条件：总数已知按总数，总数未知按"这一页没取满一页"。
  新增文案 `home_end_of_list_unknown`（"已显示 N 首"），未知总数时不再说"已显示全部 N/0 首"。
- **网易云重载失败不再端着旧目录**：原本重读返回 null 就静默复用缓存的旧名单（"重载"看着成功、
  榜其实没更新），现在直接抛出。取封面的 `ifBlank` 保护补齐（此前翻页越界那支会把封面糊成空串）。
- **测试**：`LeaderboardDetailPagingTest` 加两条——`keepsPagingWhenTotalUnknownUntilShortPage`
  （总数未知且本页取满时不许判到底，短页才停）、`surfacesFailureAndRecoversOnRetry`
  （抛异常进 `failed` 且不判到底，重载能拉回第一页）。三家 provider 测试的"跳过"判定点从
  `songs.isEmpty()` 挪到捕获 `LeaderboardException`：断网仍然跳过不判红，但"榜是空的"重新归断言管。

验证：main + androidTest 编译 + JVM 单测绿；Pixel_8 模拟器实跑 16 条（三家 provider、
`LeaderboardDetailPagingTest` 六条、`LeaderboardSquareViewModelTest` 四条）
`tests=16 / failures=0 / skipped=0`；`lintDebug` 0 error。Jank 不在这片影响面内。

**标准轴剩下的 P2 与规格轴其余条目见 `REVIEW.md`。**

## P2：结构性异味（提交 `06e4a91` / `818e563` / `699c3a2`）

不改任何用户可见行为的一批清理。**没有新增功能，也没有删除功能**，逐条落点与理由记在
`REVIEW.md` 第 4 节，这里只说三件值得知道的事：

- **公共 HTTP 面收成一处 `BuiltinHttp`**：UA 原本有七份逐字相同的字面量、`defaultClient` 九份、
  "把图片链接抬成 https"三份，而网易云适配器还在跨平台引用酷狗搜索的 Mb 格式化函数。
  各家超时秒数原样保留（榜单/歌词 10/15s，搜索/专辑/歌单 15/20s），只收样板不改数值。
- **两处会真出错的东西修好了**：`LyraCache` 之前自己 new 了一份 `AppSettings`，同一份
  SharedPreferences 上并存两个实例，设置页改了缓存侧读不到；网易云取曲目详情的 `c` 参数
  把接口给的 id 原样拼进 JSON，混进非数字就整批变坏 JSON，且注释写的"一次最多 100"代码没兜——
  现在按 100 分批、只认数字 id。
- **一份白跑的扫描停掉了**：「我的歌单」「收藏的歌单」两页各持一份 `LibraryViewModel`，
  而它 `init` 每次都扫 MediaStore，尽管这两页根本不显示本地音乐。扫描移到真正显示它的音乐库页。

**P2 第 6 组（把两份分页状态机抽成共享件）没做，推到 M9**：P1 只改了榜单详情的分页语义
（`total` 可空、失败不再等于到底、`failed` 与空榜分开），歌单广场那份还是老语义，两边现在
语义不同构——这时候抽取不是清理，是又一次设计。M9 先对齐两页语义再抽，
`LeaderboardDetailPagingTest` 那六条就是抽取时的回归线。

验证：JVM 单测绿（新增 `raisingLimitLeavesEverythingInPlace` 钉住工单 004 的"调高不删条目"）；
模拟器全量 instrumented **126 条 0 失败 0 错误**，仅既有两条酷狗歌词网络探针跳过；`lintDebug` 0 error。

**模拟器截图走查已补做（2026-10-13，Pixel_8 / Android 17）**：上面压着的两处观感都验过了——
断网点重载出的是"失败原因 + 重试"页脚（头部保留榜名与封面、总数消失），恢复网络点重试列表与总数
一起回来；本地音乐卡片仍显示"没有找到可播放的音频文件"+「重新扫描」，说明扫描移到音乐库页之后
这条链没断。顺带把「我喜欢」整链路重走了一遍（P2 归组过 `RowFavorites`，行内 ♥ 属回归面）：
头图 / "共 1 首" / 无序号 / 行内实心 ♥ / 行尾 ⋯ / 移出后行消失并给页内引导 / 库卡片计数当场回到空态，
全部正常。走查另抓到三处新的观感问题（异常文案直接给用户看、我喜欢页空态仍显示"共 0 首"、
`♥` 被系统渲染成红色 emoji），逐条记在 `REVIEW.md` 第 6 节，**未擅自修改**。

## 走查：抓到的三处观感（提交 `6bd25d3` / `1532047`）

- **失败提示改说人话**：断网时榜详情原先显示
  `榜单加载失败：kw 榜单请求失败：UnknownHostException Unable to resolve host "wbd.kuwo.cn"…`。
  现在 `LeaderboardException` 带 `cause`，界面按原因翻译——网络类失败说"网络不通，连不上榜单接口"，
  其余说"接口没给回数据，稍后再试"；原始异常与堆栈交给 logcat（标签 `LyraBoardDetail`），排查的人在那里看。
- **空态引导里的 ♥ 不再变红 emoji**：`library_empty` 与 `library_no_favorite_playlists` 里的 U+2665
  补了文本变体选择符（`&#x2665;&#xFE0E;`），渲染回正文字色。
- **列表页头部不再报"共 0 首"**：`TrackListScreen` 的计数行加了空列表分支（`songCount > 0` 才渲染），
  与工单 012 对卡片定的"不出现 0 计数"对齐。这条落在共享页上，所以专辑 / 平台歌单 / 自建歌单 /
  本地音乐的空态头部同样不再报 0；非空时"共 N 首"照常。

验证：模拟器复验三处（断网 → 失败文案与「重试」；我喜欢空态的心形字形与"无 0 计数"；
加回一首后"共 1 首"回来）；`LeaderboardDetailPagingTest` + `LeaderboardSquareViewModelTest` 全绿；
JVM 单测绿；`lintDebug` 0 error。

## 规格轴：能靠代码收的都收了（提交 `28392d9` / `8110dd3`）

- **B7「在播期间改上限不断流」有了设备测试**（工单 004 验收标准 4/5 与 PLAN §5 欠的那条）：
  `LyraCacheTest` 加两条——调低上限当场淘汰最久没听的、`assertSame` 钉住 `SimpleCache` 没被重建；
  以及开一条真在读的流再把上限压到 1 字节，断言在播那条继续读得完、改上限这个动作不把异常抛出来。
  第二条顺带证明了淘汰循环遇到"正被打开、删不动"的条目会停下而不是崩。
- **`cache_hint` 不再写死"上限 128 MB"**：改成"超过上面的上限就自动淘汰"，数字只由上限段与
  「当前 X」两处给——帮助文案里重复一个会过期的数字正是这次审查抓到的病根（B1/B6 的收尾）。
- **位图缓存 32 → 48 张**：按 320px ≈0.4MB/张算是约 19MB，装得下榜单广场 36 张封面再留 12 张，
  D1「滚出去再滚回来不重解」不再在最坏那一段落空；注释里的内存账同步改正。
- **工单 013 回写规格**：PLAN §5 补"仪器化测试一律走独立数据库"这条硬约束（含它在真机上的后果），
  §7 切片清单补 S6（`012` + `013`）。

**本节结束时只剩两项未做**：网易云"轻请求"与 C6 的丢弃逻辑——见下一节，同日做完。

验证：模拟器全量 instrumented **130 条 0 失败 0 错误**（仅既有两条酷狗歌词网络探针跳过）；
JVM 单测绿；`lintDebug` 0 error。

## 最后两项：网易云目录省流量 + 请求可取消（提交 `39ebf29` / `76bb42b`）

- **网易云取目录不再白下整榜曲目**，而且诊断要更正：这个端点的 `n` 控制的不是 `trackIds` 的长度，
  而是**顺带内嵌多少个 `tracks` 完整对象**。实测四张榜（200/100/50/100 首）：`n=0` 与 `n=100000`
  回的 `trackIds` 条目数一模一样，体积是 38KB 对 485KB、12KB 对 129KB——而目录只用 `trackIds`
  （曲目详情另外打 `v3/song/detail`），等于每次进榜白下几百 KB 再整个丢掉。改成 `n=0` 就好，
  **不需要给 `LeaderboardProvider` 加"只要封面"的方法**（原先的判断是错的，记在这里免得下次又照它做）。
- **C6「切榜/重载丢弃晚到响应」现在真有丢弃逻辑**：`fetchBoardJson` 从阻塞 `execute()` 换成
  `enqueue` + `invokeOnCancellation { call.cancel() }`——原先取消协程只是不写状态，请求照跑完、
  字节白花，现在取消即断；`LeaderboardDetailViewModel` 再加一个加载代次号，管"响应已经回到协程、
  正要写状态"那一瞬。顺带删掉原先那个恒等的 `board != board` 假守卫（`Board` 含 `coverUrl`，
  同榜重载时两边相等，它一直什么都没做）。新增 `dropsLatePageFromSupersededLoadMore` 钉住行为。

验证：模拟器全量 instrumented **129 条 0 失败 0 错误**（1 条既有歌词探针跳过；三家真接口跑的就是
新的可取消路径，网易云目录已是 `n=0`）；JVM 单测绿；`lintDebug` 0 error。
**Jank 未复测**：这两条只减少流量与请求数，不新增主线程工作，且本轮没有 UI 层改动。
