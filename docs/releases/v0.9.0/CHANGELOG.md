# v0.9.0 变更记录（M8）

本文件是本版的**结果记录**，发版时直接取用。沿用根 `CHANGELOG.md` 的风格：重点写"现在能做什么了"，
随每个切片完成即记一笔，不留到最后补。

状态：**已发布 `v0.9.0`** — 工单 13/13 完成（001–013）。模拟器全量回归 123 tests / 0 failed /
2 skipped；真机 PLK110 手工走查 + Jank 实测（榜单广场热滚 0.19%、歌单广场 0.39%、歌词页 0.74%，
`<1%` 达标）；**release 包（R8）真机冒烟通过**：音源取址、播放、歌词模糊背景、榜卡片封面、
备份导入导出均正常。版本 `0.9.0` / `versionCode 5`。

## 计划范围

- 我喜欢独立成页（音乐库顶部入口 + 独立列表页）
- 播放缓存上限可调（预设档位 + 自定义滑块，调低立即淘汰）
- 榜单多来源（酷我 / 酷狗 / 网易云）+ 新建「榜单广场」页
- 榜单封面图片性能优化
- 文档结构改为每版本一目录（本目录），根 `CHANGELOG.md` 转总索引

## 变更

### 文档底座（工单 001）

- 根 `CHANGELOG.md` 转为总索引，顶部加版本索引表；自 `v0.9.0` 起每个版本的记录写在
  `docs/releases/vX.Y.Z/CHANGELOG.md`，M0–M7 历史保留在根文件。

### 播放缓存上限可调 — 逻辑层（工单 004）

- 缓存上限不再写死 128MB：新增可调上限的 LRU 淘汰器（`AdjustableLruCacheEvictor`，策略本体抽成
  不依赖 Media3 的纯类 `LruEvictionBudget`）。可在**不重建 `SimpleCache`**（即不断开在播）的前提下
  改上限；调低到当前占用以下会立即按最久未听淘汰，并返回释放的字节数。
- 上限值经 `AppSettings.cacheLimitBytes` 持久化，默认 128MB、范围 64MB–4GB。决策见 ADR-0008。
- 单测：`LruEvictionBudgetTest`（JVM，7 例，含"调小上限即刻超限""触碰刷新新鲜度"）。
- **待续**：设置 / 缓存页的上限调节控件（工单 005）；"在播时改上限不断流"的设备侧验证。

### 「我喜欢」独立页（工单 002）

- 新增「我喜欢」页（`LikedRoute`），与专辑 / 平台歌单同一个曲目列表页：头部标题 + 曲目总数；
  点行即播（队列 = 整页）；**行内 ♥ 直接移出**；行尾「更多操作」可加入自建歌单；序号列关闭。
- `TrackListScreen` 增「行内 ♥」转发口与可关的序号列（其余列表页不传、行为不变）。
- 新路由 `liked`，归入详情页——进出不带动底部 Tab 一起滑。
- 移出后列表刷新沿用"观察收藏数变化 → 重读"（与自建歌单页同一做法）。
- **待续**：音乐库顶部入口卡片与"歌曲语境 收藏 → 我喜欢"的全量文案（工单 003）；
  真机 / 模拟器手动验收（该项目无 Compose UI 测试基建，UI 切片历来手动验）。

### 音乐库入口卡片 + 段落重排 + 文案改「我喜欢」（工单 003）

- 音乐库顶部换成「我喜欢」入口卡片（❤ + 计数），点它进独立页；收藏为 0 时显示引导，
  不再出现"计数为 0"的卡片。原先内联的单曲长列表移除——收藏再多也只占一行。
- 库内段落顺序改为：**我喜欢 → 我的歌单 → 收藏的歌单 → 本地音乐**。
- 歌曲语境文案统一改「我喜欢」：音乐库计数、播放页 ♥ 的读屏标签、加入 / 移出提示、
  备份导出 / 导入文案、缓存说明；**"收藏的歌单"保持"收藏"**（歌单语境不动）。
- 清理：`LibraryViewModel` 去掉已无用的播放链与单曲收藏开关（库内不再就地播放）。
- **待续**：真机 / 模拟器手动验收。

### 缓存上限调节控件（工单 005）

- 缓存详情页顶部新增「上限」段：预设芯片（128MB / 256MB / 512MB / 1GB / 2GB）+「自定义」
  滑块（64MB–4GB）；当前上限不在预设时自动落到「自定义」。
- 上限持久化（SharedPreferences），重开应用保持；设置页「存储」段的"上限"数字改为当前生效值
  （原先硬编码 `128`）。
- 调低到当前占用以下 → 立即按最久未听淘汰，并提示「已释放 X」；调高不删任何条目。
- **至此 S3（播放缓存上限可调）收口。** 待设备侧手动验收。

### 音乐库全卡片化 + 我喜欢封面（工单 012，验收中发现）

- 音乐库改为**只有四张入口卡片**：我喜欢 / 我的歌单 / 收藏的歌单 / 本地音乐，各点进自己的列表页；
  库里不再内联曲目与歌单行。
- 新增「我的歌单」页（顶栏新建、长按改名/删除）与「收藏的歌单」页（行内 ♥ 移出）。
- 「我喜欢」页与入口卡片显示**最近添加那一首歌**的封面（不再只是 `♪` 占位）；收藏的歌单取第一张
  收藏名片的封面，本地音乐取第一首本地曲的封面，我的歌单用列表图标（自建歌单没有封面）；
  任一封面取不到一律退回图标，不留纯色空块。
- 本地音乐卡片保留「授权 / 重新扫描」入口；空态改短文案（"还没有喜欢的歌""还没建歌单"…），
  不再出现"0 首"这种计数。
- 文案清理：删除卡片化后无用的 6 条库内字符串；`library_manage_hint` 的"收藏不受影响"改
  "我喜欢不受影响"；空收藏的引导文案移到「我喜欢」页里。
- 验证：编译 + JVM 套件 + `lintDebug` 全通过；UI 行为待设备侧手动验。

### 榜单来源抽象（工单 006，S4 前置）

- `Board` 扩 `source` 与 `coverUrl`，`id` 改为"来源前缀 + 平台 id"以保证多来源下唯一
  （`bangId` 才是接口认的原始 id）；新增 `LeaderboardProvider` 接口（`source` / `displayName` /
  `boards` / `songs`），`Board` 与 `BoardPage` 从酷我适配器里挪到共享的 `Leaderboard.kt`。
- `KuwoLeaderboardProvider` 实现该接口，**行为与首页观感不变**（扩展式，未动调用方）。
- 实测确认：榜封面只随 `bang_info` 的 `data.pic` 带回，酷我 12 张榜**全部非空**；酷我没有
  "榜列表"接口（`bang_list` 路由存在但参数一律 `PARAM_ERROR`，lx 亦只有静态表），
  因此静态表里 `coverUrl` 留空、由 `songs()` 返回带图那一份。已把这条约束写进 PLAN 供 009 用。
- `ServiceLocator` 新增 `leaderboardProviders()` 来源目录（现只含酷我）；设备测试加一条封面断言。
- 验证：main + androidTest 编译通过、JVM 套件绿；封面断言待设备侧跑。

### 酷狗榜单适配器（工单 007）

- 新增内置 `KuGouLeaderboardProvider`（12 张榜），实现 `LeaderboardProvider` 并注册进
  `ServiceLocator.leaderboardProviders()`——榜单来源目录现在有两家。
- 端点参数以真接口实测为准：`version=9108` + 短 `bangid`（8888/6666…）+ `with_res_tag=0&show_portrait_mv=1`
  才给满数据（TOP500 total=500、单页 30、翻页零重叠）；换用 `rank/list` 的 `rank_cid` 或 lx 老 rankid，
  接口照样 200 却只回 0–4 首。酷狗榜**出错也回 200**，标志键是 `errcode`（非搜索那套的 `error_code`）。
- 档位按酷狗榜单的四组独立 hash+size 映射（`hash/320hash/sqhash/hash_high` + 各自 filesize）。
- 榜封面：`rank/song` 不给榜级图，改用**榜首歌曲的专辑图**；`v5/rank/list` 虽有 `img_9`，但其 id 体系
  与 `rank/song` 认的 `rankid` 不同套，按名配对不稳，先不引（已写进适配器注释）。
- 新增设备测试 `KuGouLeaderboardProviderTest`（含"200 但空榜"这条陷阱断言与翻页不重叠探针）。
- 验证：main + androidTest 编译通过、JVM 套件绿；真接口断言待设备侧跑。

### 网易云榜单适配器（工单 008）— 三家来源齐了

- 新增内置 `NetEaseLeaderboardProvider`（12 张榜，榜即歌单），注册进
  `ServiceLocator.leaderboardProviders()`——**榜单来源目录现为酷我 / 酷狗 / 网易云三家**。
- **不需要 weapi 加密**：实测 `api/v6/playlist/detail` + `api/v3/song/detail` 两个明文端点无需加密、
  无需 cookie 就能拿到榜名、榜封面与曲目，工单量因此从"实现无填充 RSA"降到与酷狗同级。
  lx 的 weapi 常量与流程已记入工单 008，作为明文端点哪天失效时的备用路线。
- 档位只收 Lyra 认的四档（`l`→128k、`h`→320k、`sq`→flac、`hr`→flac24bit）；网易云的 192k（`m`）
  **没有对应档位，直接不收**——硬标成 320k 等于让音源拿低码率文件去交差。`size > 0` 过滤保证
  不写出根本不存在的档位。榜目录按 `board.id` 缓存：第一页重读（否则"重载"拿不到新榜）、翻页复用。
- 12 张榜逐张实测（`code=200`、`trackCount` 50–200、封面齐、详情可取）；`c` 数组实测一次 100 个 id 全回。
  曾把 `3812895` 误标成"影视金曲榜"，核对后换成实测过的国风榜。
- 新增设备测试 `NetEaseLeaderboardProviderTest`（档位合法性、翻页不重叠、封面断言）。
- **发版需写明**：内置适配器不提供 `musicUrl`，网易云榜的歌同样靠用户音源取址——
  **音源不支持 wy 时这些歌播不出来**。

### 榜单广场 + 首页榜单区（工单 009 与 010，一起提交）

两张页互相依赖：广场没有入口就进不去，而入口正是首页那一排——分开提交会留下一个
"编译过但没人能走到"的中间状态，所以合成一片。

- 新增「**榜单广场**」（`ui/leaderboard/LeaderboardSquareScreen`）：按来源分段（酷我 / 酷狗 /
  网易云），段内双列网格列出该来源全部榜。网格沿用歌单广场那套 `LazyVerticalGrid` +
  横跨整行的 header，卡片复用 `PlaylistCard`——不做第二套界面。
- 新增「**榜单详情**」：头部（榜封面 + 榜名 + 总数）+ 带序号的曲目行，触底翻页、到 total 停、
  跨页与页内都按 `source:id` 去重。
- **规格更正落地**：`TrackListScreen` 是一次性取数、无分页，做不了榜详情。详情页因此自带
  `LeaderboardDetailViewModel`（沿用 `fetchBoard` 注入接缝）。原 `HomePagingTest` **随逻辑一起
  迁成 `LeaderboardDetailPagingTest`**，覆盖没有因为代码挪位置而消失；另加一条
  `dropsLateResponseFromSupersededLoad`（重载时上一次还在途，晚到的响应不许把列表接成两倍）。
- 首页重做：删掉吸顶换榜芯片与拍平长列表，改为「推荐歌单 + 榜单预览」两排横滑
  （每来源前 3 张榜）。`HomeViewModel` 因此不再持有播放链与分页状态机，瘦成两块预览。
- 封面懒取的真实形状：榜名与榜 id 来自静态表，**整页一进来就有内容**；封面在分段标题进入组合时
  才取那一段（分片并发 4、按来源一次性去重，滚出滚回不重发），整段取不到只在该段标题下说明
  并给重试——缺图不影响这一段可用。新增 `LeaderboardSquareViewModelTest`（假 provider，4 例）。
- 新增 strings：`home_boards` / `leaderboard_square_title` / `leaderboard_covers_failed` /
  `leaderboard_detail_empty`；`home_empty` 的文案归位给详情页的空榜。

### 榜单封面图片性能（工单 011）

- 图片的唯一入口收敛到 `ArtworkLoader`：新增**解码位图 LRU 缓存**（键含尺寸，上限 32 张
  ≈13MB）、**同一 URL 的在途请求去重**（后来者等第一个下完，不再各发一次）；
  `rememberRemoteArtwork` 改为初值直接读位图缓存、加载走 `ArtworkLoader.bitmap()`——
  滚回来第一帧就是图，不再先闪一下空白再等协程。
- 新增 `ArtworkLoaderTest`（MockWebServer 确定性计数，3 例）：8 路并发同一 URL **只发 1 次请求**；
  同尺寸二次取用返回**同一个位图对象**；不同尺寸各留一份位图但字节只下一次。
- 模拟器实测（`dumpsys gfxinfo`，广场 34 次连续滑动）：冷缓存首滚 Jank **5.29%**（p50 28ms）
  → 热滚 **2.60%**（p50 18ms），缓存把两者都砍掉约一半。
  **`<1%` 的门槛没在真机复测**——那是 PLK110 上的标准，模拟器数字不可比，不拿它冒充达标。
- 顺手修掉一条自己写的"看运气"断言：`withTimeout { assertTrue(...) }` 只判定一次，
  重试请求还没发出就会假失败；改成轮询等待，连跑两遍稳定。

## 验收记录

**2026-10-12，模拟器 Pixel_8(AVD) / Android 17 全量 instrumented 套件**：
**120 tests，0 failed，0 errors，3 skipped**。

被跳过的 3 条与本版无关，是既有的网络探针：`ArtworkProbeTest.probePicActionAfterResolvingStreamUrl`、
`KuGouLyricProviderTest.fetchesLyricByKeywordAndDuration`、`KuGouLyricProviderTest.coversSongsFromAnotherPlatform`。

本版新增/涉及的断言都是**实跑非跳过**：三家榜适配器（曲目、档位、封面、翻页零重叠）、
`LyraCacheTest` ×5、`LeaderboardDetailPagingTest` ×4、`LeaderboardSquareViewModelTest` ×4。
JVM 单测套件与 `lintDebug`（0 error）同样通过。

**仍未验收**：新版式的观感（广场网格、首页两块横排、音乐库四卡片、我喜欢封面）需要人眼看；
以及 Jank 实测（工单 011）。

**2026-10-13，真机 PLK110 / Android 17 Jank 实测**（`dumpsys gfxinfo`，40 次连续滑动/约 1000 帧一轮）：

| 场景 | 冷滚（首次下图） | 热滚（缓存已建） |
|---|---|---|
| 榜单广场（本版新增） | 0.59%（6/1013，p50 5ms，p99 13ms） | **0.19%**（2/1030，p99 10ms） |
| 歌单广场（既有基线） | 2.67%（26/972） | **0.39%**（4/1025） |
| 歌词页逐字扫光（既有门槛） | — | **0.74%**（7/949，p50 8ms，p99 19ms） |

- 歌词页 0.74% 优于 M7 记录的 0.84% → **本版改动的共享图片路径没有影响播放页模糊背景那条链**。
- 热滚 0.39% 与 M7 记录的 0.32% 同量级 → **本版改动的共享图片路径没有让既有页面退化**。
- 广场冷滚 0.59% 明显好于歌单广场冷滚 2.67%，与"分段懒取 + 并发上限 4 + 在途去重"的设计一致：
  一次只压少量下载，而不是整屏一起发。
- 方法学说明：M7 的基线是 10 秒采样，这里是 40 次连甩（无间歇），**绝对值不可直接对比**；
  本版内部冷/热与两个广场之间的横向对比才有效。`<1%` 门槛在真机上达标（冷、热都过）。

**真机跑测的破坏性陷阱（本版发现，本版已修）**：**四个**测试用**生产数据库** `lyra-library.db`
（`FavoritesRepositoryTest`、`PlaylistRepositoryTest`、`FavoritePlaylistRepositoryTest`、
`LibraryBackupTest`——最后这个最初被我漏数了一次），且 setup/teardown 各调一次
`deleteDatabase()` 直接删该文件。它们靠"删库重建"断言持久化，在模拟器上无害，
但**在真机上跑 instrumented 套件会当场清空用户的收藏、自建歌单与收藏的歌单**。

本版真机因此只做"装包 + 手工走查 + gfxinfo"，没跑套件；装包前后用
`run-as … ls -l databases/` 核对过库文件时间戳未变（数据完好）。
修法见工单 013：`FavoritesDatabase` 增可选库名，测试一律走 `lyra-library-test.db`，
生产库名不再出现在测试代码里。

**2026-10-12 模拟器手动走查（截图逐屏看过，上述观感项已清）**：

- 首页：推荐歌单 + 榜单预览两排横滑，榜封面是真图（酷我榜图本身带榜名）。
- 榜单广场：按来源分段、双列网格；滚到网易云那一段时封面才出现——**分段懒取封面在真机上按预期工作**。
- 榜单详情：头部榜封面 + "共 298 首"，序号前三加重；连翻到
  **"已显示全部 298 / 298 首"** 收尾，分页与到底都正确。
- 音乐库：四张卡片（❤ / 列表 / 队列 / 均衡器图标）+ 短空态文案 + 本地音乐行内「授权」。
- 我喜欢：从榜上"更多操作 → 我喜欢"加入一首后，库卡片显示「我喜欢 · 1 首」，
  进页**头部显示该曲真实专辑封面**（不再是 `♪` 占位），行内实心 ♥ 与 ⋯ 均在。

**走查中发现并当场修掉一处**：「更多操作」面板里"我喜欢"那一行**只有左侧方框可点，点文字没反应**
（我自己点空了两次才命中）。`PickerRow` 现在整行可点——歌单勾选行同样受益。已在模拟器上复验：
点文字标签 `checked` 由 false 翻到 true。

### release 包真机冒烟（2026-10-13，PLK110 / Android 17）

发版前用 `app-release.apk`（9.6MB，R8 已裁剪）在真机上跑了一遍，**目的是确认 R8 没把
音源与播放链剪坏**——这是本项目历史上真出过问题的地方。

| 检查 | 结果 |
|---|---|
| 安装 | ✅ release 签名（`flags=0x0`，非 debuggable），`versionName=0.9.0` |
| 冷启动 | ✅ 进程存活，logcat 无 `FATAL` / `ClassNotFound` / `NoSuchMethod` |
| 首页 / 榜单广场 | ✅ 截图确认；三家来源榜卡片**封面全部加载** → 网络 + AES 签名 + 图片解码在 R8 下正常 |
| 备份导入 | ✅ `lyra-backup-20261013-1008.json` 导入后库恢复为「我喜欢 1 首 / 我的歌单 1 个」 |
| **播放链** | ✅ 播恢复出来的「素颜」：`state=PLAYING`，进度真实推进（10.7s → 0:41），实际音质「无损」 |
| 歌词页 | ✅ 当前行高亮 + **封面取色模糊背景正常**；Jank **0.10%**（1/1033，p50 5ms） |

结论：**R8 未破坏音源 / 播放 / 歌词 / 图片任一条链**，v0.9.0 可发布。

**清库换签名的代价（实测确认）**：release 与 debug 签名不同，覆盖安装报
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`，只能先卸载——应用数据（含 `files/source-store/`
里的音源）随之清空，而 **JSON 导出不含音源**，所以音源必须自己留好文件重新导入。
本次由用户事先导出备份并备好音源文件，两步恢复均已验证可用。

## 发版后修订（2026-10-13，代码审查标准轴 P0 三条）

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

## 发版后修订（二）：三家榜单失败语义与 `total`（标准轴 P1，解规格轴 C7）

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

**标准轴剩下的 P1/P2 与规格轴各条尚未处理**：三家适配器把失败折叠成空榜（C7 的"错误态"因此在
UI 上不可达）、`total` 缺失时兜底成页大小导致提前判到底、工单 004 的"在播改上限不断流"仍无设备
测试、`cache_hint` 还写着死值"上限 128 MB"、工单 013 未回写 PLAN。
