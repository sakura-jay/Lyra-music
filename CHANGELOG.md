# Changelog

Lyra 的变更记录。格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循语义化版本；一期版本全部落在 `0.x.y`，其中 `0.<里程碑>.0` 对应一个里程碑收口。

进度权威是 tag 序列，每个里程碑一节，重点写"现在能做什么了"。

## 版本索引

| 版本 | 里程碑 | 记录位置 |
| --- | --- | --- |
| `0.1.0` – `0.8.0` | M0 – M7 | 本文件下方各节（历史） |
| `0.9.0` | M8 | [`docs/releases/v0.9.0/`](docs/releases/v0.9.0/) · [计划](docs/releases/v0.9.0/PLAN.md) · [变更](docs/releases/v0.9.0/CHANGELOG.md) · [审查记录](docs/releases/v0.9.0/REVIEW.md) |
| `0.9.1` | M8 补丁 | [`docs/releases/v0.9.1/`](docs/releases/v0.9.1/) · [计划](docs/releases/v0.9.1/PLAN.md) · [变更](docs/releases/v0.9.1/CHANGELOG.md) |
| `0.9.2` | M9 | [`docs/releases/v0.9.2/`](docs/releases/v0.9.2/) · [计划](docs/releases/v0.9.2/PLAN.md) · [变更](docs/releases/v0.9.2/CHANGELOG.md) · [实现提示词](docs/releases/v0.9.2/PROMPTS.md) · [ADR-0009](docs/adr/0009-动效语言自建缩放与层级表.md) |
| `0.9.3` | M10 | [`docs/releases/v0.9.3/`](docs/releases/v0.9.3/) · [计划](docs/releases/v0.9.3/PLAN.md) · [变更](docs/releases/v0.9.3/CHANGELOG.md) · [工单](docs/releases/v0.9.3/tickets/) · [实现提示词](docs/releases/v0.9.3/PROMPTS.md) · [ADR-0010](docs/adr/0010-共享元素转场.md) |

> **约定**：自 `v0.9.0` 起，每个版本的变更记录写在 `docs/releases/vX.Y.Z/CHANGELOG.md`；本文件只保留总索引与 M0–M7 历史。一个里程碑一个 minor，`1.0` 保留给"全部完善"的终点里程碑；分支与提交约定见 `CONTEXT.md`。

## [Unreleased]

> `v0.9.0` 起的版本记录不再写在本文件，见 [`docs/releases/`](docs/releases/)。本节保留 `v0.8.0` 之后的收尾历史。

### v0.8.0 后真机收尾

- 全量 instrumented tests 在 PLK110 release 包上跑通：**114 tests，0 failed，2 skipped**
- Jank 初测（`dumpsys gfxinfo framestats`）：
  - 歌词页逐字扫光：0.84%（新指标）/ 2.07%（legacy），10 秒 4398 帧
  - 歌单广场滚动：0.32% / 0.96%
  - 曲目列表滚动：1.20% / 2.16%（含异步图片加载首次入屏）
- `fix(ui)`：歌词扫光已唱部分从 `onSurface` 改为 `primary`，解决扫光边界不明显的问题

### M0 工程基线 — 已完成（真机验收通过）

- 确立术语表（`CONTEXT.md`）与五条架构决策记录（`docs/adr/0001`–`0005`）
- 确立 Git 工程管理约定：分支模型、提交规范、tag 规则、忽略清单
- Gradle 骨架：Gradle 8.14.5 / AGP 8.13.2 / Kotlin 2.4.20 / Compose BOM 2026.06.01（Compose 1.11.4）+ core-ktx 1.18.0，`compileSdk 36`、`minSdk 31`
- 版本组合以 `compileSdk 36` 为上限：BOM 2026.09.00（Compose 1.12.1）与 core 1.19.x 的 AAR metadata 要求 compileSdk 37 + AGP 9.1.0，已回退锁定；待工具链升到 AGP 9.1.0 时再抬回
- 三 Tab 骨架（首页 / 搜索 / 音乐库）+ 底部常驻迷你播放条静态占位
- 主题：系统动态取色优先，两套预设（光谱 / 余烬）明暗变体兜底

**验收记录（2026-10-07，真机 PLK110 / Android 17）**

- 冷编译：`./gradlew --offline --no-build-cache clean assembleDebug` 通过，19s，
  `compileDebugKotlin` 与 `dexBuilderDebug` 实际执行（非 build cache 恢复），产出 283KB `app-debug.apk`
- 真机安装：`adb install -r -t` → Success；`dumpsys package` 显示 `versionName=0.1.0`、`minSdk=31`、`targetSdk=36`
- 三 Tab 可点：`音乐库` 切换后显示"收藏、歌单与本地音乐将在这里落地（M4、M7）。"，
  `搜索` 切换后显示"多音源聚合搜索与来源标签将在这里落地（M1、M6）。"；导航栏标签 bounds 落在 y≈2604，未被手势区裁切
- 冷启动：`am start -W` 在 force-stop 后 TotalTime = **515ms**（AS 产物）／**414ms**（冷编译产物），门槛 1.5s

**Jank 已初测**：v0.8.0 后在 PLK110 上用 `gfxinfo framestats` 量了歌词页扫光、歌单广场与曲目列表滚动，详见上面 "v0.8.0 后真机收尾"；歌词页新指标 0.84%，满足 <1% 目标。

### M1 音源跑通 — 已完成（`v0.2.0`）

**真机验收（PLK110）**：搜索「jay」→ 点「晴天 · 无损」→ 系统音频层出现本进程
`AudioTrack`、`state:started`、`USAGE_MEDIA`、44.1kHz、已路由输出设备；
界面显示"正在播放 · 无损 · 经 星海音乐源"。

过程中修掉三个只有真机能暴露的问题：
- `musicInfo` 写了 `hash:""`，音源用 `??` 链取 id 时停在空串上 → 报"缺少 songId"
- 音源播放地址是 `http://` CDN，被 Android 明文策略拦 → `CLEARTEXT_NOT_PERMITTED`；
  改为按域名无法枚举的现实放开 base-config，并补浏览器 UA 与跨协议重定向
- 错误只报 "Source error" 无法定位 → 现在带 `errorCodeName` 与 cause 链

**DoD 偏差（如实记）**：原计划写"3 个真实音源搜索/取址/歌词通过"。实际是
1 个真实音源（星海 v2.3.12）完成取址与播放，搜索由内置适配器提供——因为
洛雪音源格式本身通常不声明 search，这是分工而非缺陷。后续拿到更多音源时
按同一套能力清单跑回归即可。

- `feat(source)` QuickJS 引擎线程 + Promise 排空真机验证（`d529fcd`）：
  绑定库选 `wang.harlon.quickjs:wrapper-android:3.2.3`，与官方移动端同源
- `feat(source)` lx.* 宿主契约、OkHttp 请求桥、crypto/buffer/zlib 原生桥（`3d7d868`）：
  协议逐字对齐官方 preload（`__lx_native__` / `__lx_native_call__` / key 校验 / Uint8Array 语义），
  并补上官方移动版未实现的 zlib、DES、SHA、AES 解密，成为契约超集
- 12 条 instrumentation 测试在真机 PLK110 全绿；真实音源（星海 v2.3.12）完成
  `inited` 握手、来源能力解析与错误归一化
- 补 `INTERNET` 权限；未开 cleartext

- `feat(ui)` 音源管理页（`fdaea10`）：文件 / https 链接导入，**导入即试跑**——
  脚本必须真的 `send(inited)` 才入库，`status:false` 与坏源当场拒绝；非 http(s) 方案直接拒
- `feat(player)` ExoPlayer 播放执行器（`080d225`）：测试内合成 PCM WAV 经 MockWebServer
  供流，断言进入 READY 且播放位置推进；不可达地址快速失败不挂住
- 手写 ServiceLocator 注入 + Navigation Compose；依赖版本天花板写进 `libs.versions.toml`
  注释（navigation 2.10 / lifecycle 2.11 / okhttp 5 / compose-bom 2026.09 都要 compileSdk 37）
- 测试规模 12 → 24 → 26 条，PLK110 真机通过

### M2 播放体系 — 已完成（`v0.3.0`）

后台播放、队列、四种播放模式、音质降级、crossfade、流体云封面，全部真机验收（PLK110）。

- 决策变更（ADR-0006）：删掉自研 `PlayQueue`（含 15 条测试），队列所有权交给 Media3 播放器；
  播放模式映射到 `repeatMode`/`shuffleModeEnabled`，避免两套状态对"下一首是谁"理解不一致
- `feat(player)` MediaSessionService 后台播放：isForeground=true、types=mediaPlayback、
  通知 category=transport；退后台 8s/14s 仍 state:started，会话位置持续推进；锁屏/蓝牙媒体键走同一会话
- `feat(player)` 队列下发与惰性解析（`95836ea`）：搜索结果整队下发，当前曲带真地址、其余带
  `lyra://` 占位；真地址在播到那一刻才向音源要（`ResolvingDataSource`），避免提前过期
- `feat(player)` 播放元数据与封面（`41b7ada`）：流体云缺图的根因是 MediaMetadata 全空；
  补标题/艺术家/专辑/时长与封面字节后，胶囊带封面出现
- `feat(player)` 播放模式切换入口与转场动效（`73fe6a9`）：列表循环/单曲循环/随机/单曲一次
  四种模式真机行为验证 4/4；页面切换统一走 `LyraMotion` 的 spring 规格
- `feat(player)` crossfade（ADR-0007）：双播放器 + 共享 SimpleCache + 等功率斜坡，会话播放器
  不换人。真机实测重叠 4012ms 后由会话播放器接管；instrumentation 断言两路确实同时出声、
  斜坡走完全程、交接点不存在两路皆静音的采样点、手动切歌会取消淡入并交回满音量
- `fix(source)` 取址方不再关掉借来的音源运行时：`playUrlFor` 曾在 finally 里 close 池化的
  SourceRuntime，第一首之后每首报「音源已卸载」；回归测试在修复前的代码上会失败

**RuntimePool 收益实测**：合成音源冷启动 9–12ms / 缓存命中 0ms；真音源（星海 v2.3.12）
冷启动 12ms / 命中 0ms。如实记：池子省的是引擎重建（十毫秒级）；用户感知的
"点下一首要等一下"大头是音源 musicUrl 的网络往返，池子并不消除它。

**动效观感取证（12 次 Tab 切换）**：`gfxinfo framestats` 682 帧、Janky 28（4.11%）、
90/95/99 分位 10/18/44ms，另存转场录屏供目视（未入库）。**4.11% 高于项目 1% 的目标**：
jank 集中在每次切换的首帧合成（120Hz 帧预算仅 8.3ms），归 M7 性能收口
（baseline profile / 预合成）处理，不在 M2 里假装达标。

### M3 歌词沉浸页 — 已完成（`v0.4.0`）：逐字扫光、逐行退化、封面取色模糊背景、60fps 标准

切片 1–4 先只过了编译与 JVM 测试；上机后按用户反馈返工一轮，现在歌词页是真能用的。

- `feat(lyric)` 内置酷我歌词适配器：星海 v3.2.12 连取 8 首，`lyric` action **全部回 null**
  （它的词是"取地址时顺手缓存"，缓存里没有就没有）。洛雪用同一条音源却有歌词，是因为词由
  App 内置适配器补——ADR-0003 的双轨因此补上第二条。接口形状在真机探测确认：
  `{"data":{"lrclist":[{"lineLyric","time"}]}}`，且会偶发返回空 body，故同一 id 重试一次
- `fix(lyric)` 音源回 `null` 归一化成"没有歌词"而不是 `Invalid lyric info`；取词每个环节
  都记日志（哪个音源、三个字段各多少字、内置轨道拿到多少），只说"暂无歌词"谁都定位不了
- `feat(player)` 播放页返工：封面在上、进度/播停/档位在下，向左滑是整屏歌词页；
  迷你播放条补上封面缩略图；从详情页退回 Tab 不再让底下的 Tab 页重播入场动画
- `feat(player)` 档位切换：`PlaybackQualityStore` 让服务按新档位重新取址并回到原位置，
  界面显示"本次实际音质"，降级时明说
- `fix(lyric)` 扫光不再手写 `withFrameNanos` 帧循环——真机实测它不自持（20 秒只画 45 帧，
  ≈轮询频率的 2fps）：它等帧，而只有值变了才会有帧。改成 `animateFloatAsState` + 线性 tween
- 当前行居中改为"上下各留半个视口"的 contentPadding，不再猜行高
- `test` 音源播种：`SeedTestSourceTest` 走与界面相同的"导入即试跑"路径，把 gitignore 掉的
  真实音源种回应用；跑完 `connectedDebugAndroidTest` 应用会被卸掉、手工导入的音源跟着没，
  之后改用 `am instrument` 单跑，不再触发卸载

**上机后第二轮返工（5 项，均未上机复核——用户要求暂停真机测试）**

- 扫光越行回扫：视图要提前一个轮询间隔取进度，行末会取到下一行的 0，表现为"扫完一行又
  瞬间退回开头"。`LyricTimeline.ratioForLine` 把进度夹在行内，换行时 `snapTo(0)` 而不是动画归零
- 折行同时扫：原来整块按 x 裁，上下两行会在同一时刻一起变亮。改成按视觉行逐行裁——
  先按字符数把总进度分给每行，再只在该行的矩形里裁（用 `TextLayoutResult` 的行几何）
- 状态栏/手势条适配：播放页背景改为铺满整窗（外层 NavHost 不再统一吃内边距），
  内容自己 `windowInsetsPadding(safeDrawing)`；状态栏与导航条图标明暗跟随当前配色
- 播放顺序只留在播放详情页下部（改为四个模式 chip），搜索页顶栏那个入口删除
- 退出详情页动画：整页向下落回（原来只位移 1/6 屏 + 淡出，看着像被抽走），
  底下的 Tab 页本就不动

**帧预算实测（PLK110，歌词页扫光中）**：20s 内 1220 帧、Jank **0.33%**（FrameTimeline）
／1.32%（legacy）、90/95/99 分位 8/11/14ms、Missed Vsync 0。
Compose UI 测试在本机跑不起来（androidx.test 反射 `InputManager.getInstance` 在该系统不存在，
镜像里没有更新的 runner），歌词视图只有 JVM 侧的解析/时间线测试兜底。

**帧率标准改为 60fps（仅限歌词页，2026-10-09 由用户定）**：这台机器的面板虽然支持
90/120/144/165Hz，但第三方应用被系统按内容策略压到 60Hz（`activeMode` 静止时报 60，
动画中会升到 120）。原来那条"120Hz 下 Jank<1%"因此作废——`cmd display
set-user-preferred-display-mode` 在 ColorOS 上被 `SecurityException: Package android does not
belong to 2000` 挡住，`app_extreme_high_refresh_switch` 打开后实测也不改变本应用的档位，
所以标准按能真实达到的 60fps 定。

**第三轮返工：换行动画**（用户指出的重点，参数取自 lx-music-mobile 的播放页歌词
`src/screens/PlayDetail/Vertical/Lyric.tsx` + `src/utils/scroll.ts`）

- 根因不是"动画不够好"而是**行高在动画中途变化**：非当前行原来用更小的 `fontSize`，
  切换瞬间该行排版高度突变，而滚动目标正是"滚到这一行"，目标自己会动 → 看着像抖一下。
  现在所有行按当前行那一档排版（行高恒定），视觉大小用 `graphicsLayer.scale` 插值，
  缩放只在 GPU 上做
- 强调值 0→1 走 320ms `FastOutSlowInEasing`，同时驱动缩放与整行透明度（0.55→1）。
  之前是颜色与字号各切各的、同一帧完成，所以是"啪"地换过去
- 行内间距不再参与动画（`animateDpAsState` 的 10dp/4dp 会触发重排，与行高同源）

**帧预算复测（同一台，换行 + 扫光 21s）**：1371 帧、Jank **0.22%**（FrameTimeline）
／4.52%（legacy）、Missed Vsync 1。legacy 数字变差是因为它按 16.6ms 判帧，而这段时间面板
在 60/120 之间切换，实际以 FrameTimeline 的 0.22% 为准——**没有因为多出的图层回退**。

### M5 榜单与详情页 — 已完成（`v0.6.0`）：榜单 / 专辑 / 歌单 + 分页与整页滚动（歌手页移到二期）

**切片1 内置酷我榜单适配器**（`cdab6e2`）：接口与签名取自 lx-music-mobile 的
`musicSdk/kw/leaderboard.js`，但**照抄不通**——它先 base64 再 AES(NoPadding)，服务端只回
`DECRYPT_ERROR`；四种组合实测下来只有"原始 JSON + AES/ECB/PKCS5Padding"拿得到数据。
`musiclist[]` 的字段映射复用搜索适配器的 `decodeName/splitArtists/parseQualities`，
不允许同一份 `N_MINFO` 存在两套判定表。

**切片2 首页榜单 UI**：12 个常用榜的 chip 横滑 + 排名列表（前三位高亮）+ 点歌进队列。

- 把"点一首歌到真的出声"抽成 `SongQueueLauncher` 共享。搜索页原来自己抄了一遍，
  漏过一次 `recordEffective`（播放页的实际音质永远停在"取址中"）；抄第二遍就会漏第二次
- 搜索页顺带改走 `ServiceLocator.playUseCase`（池化运行时）。它原来用私有 loader 每次点歌
  新建一个 QuickJS 运行时且从不关闭——白付重建成本还漏线程
- 切榜取消在途请求：两个请求并发时晚到的响应会把列表写成上一个榜的
- 队列快照在点击时取，取址期间列表被换掉则只播这一首，而不是夹到 0 变成"点 A 播 B"

**验收记录（2026-10-08，Pixel_8 模拟器）**：飙升榜 `total=298`、点第 1 首 →
`dumpsys media_session` 显示 `state=PLAYING position=35857 buffered=87381`，
界面"正在播放 · 无损 · 经 星海音乐源"，迷你条出现封面；切到热歌榜再切回飙升榜，
标题与列表一致；搜索页回归（"jay" → 晴天）仍能出声，档位仍为无损。

**切片3 内置专辑适配器与专辑详情页**：端点取自 lx-music-mobile 的 `musicSdk/kw/album.js`
（同一个 `r.s` 网关换 `stype=albuminfo`，一次请求同时给专辑信息与曲目）。
档位这里来自 `formats`（`MP3128|MP3H|ALFLAC|HIRFLAC`），不是搜索接口的 `N_MINFO` 串——
两个接口给的是两种格式，所以判定表各一份，并单独写了 JVM 测试钉住顺序。

- 入口是行内那一段**专辑名**（主色文字）：整行点击是"播这首歌"，"看这张专辑"是另一个意图，
  合并成一个点击就只能二选一。榜单页与搜索页都给了这个入口，专辑页内部不再重复
- 曲目行抽成 `ui/common/TrackRow`（序号 + 曲名 + 次行），榜单页与专辑页共用
- 多歌手用 `、` 连接：接口给的是 `周深&郁可唯&…`，直接显示出来就是一串 &

**验收记录（2026-10-09，Pixel_8 模拟器）**：`KuwoAlbumProviderTest` 实测「叶惠美」
`songnum=11 取回 11 首，第一首=以父之名 档位=[flac, 320k, 128k]`；首页第 1 首的专辑名
→「兰香如故 影视剧原声带」68 首（多歌手、长简介、封面正常）；搜索页「叶惠美」→ 同一页；
专辑页点第 1 首 → `state=PLAYING position=11638`、"正在播放 · 无损 · 经 星海音乐源"；
返回键回到榜单，选中榜与滚动位置都还在。

**切片4 推荐歌单 + 详情页合并成一个页面**：`musicSdk/kw/songList.js` 里推荐列表与详情
是两个端点，而详情的端点由列表给的 `digest` 决定（8 直接查 `pl.svc`，13 其实是专辑，
5 与其他要先经 `q.k` 换 sourceid）。这条分派照抄，专辑那一支复用切片3 的适配器——
同一张专辑不允许有第二条取数路径。

- 专辑页与歌单页在界面上是同一件事（一张封面 + 一行归属 + 一串曲目），
  所以合并成 `ui/tracklist/TrackListScreen` + 一个收 loader 闭包的 `TrackListViewModel`，
  平台字段差异由 `toTrackList()` 映射吸收。两份 130 行的页面不是"三份相似代码再抽象"，
  第二份出现时就该合
- 内置适配器改为 `ServiceLocator` 单例：每个自带 OkHttpClient（连接池 + 线程池），
  每次进页面新建一个就是白建一套池子
- 列表封面走 `rememberRemoteArtwork`：按需下载 + 降采样，不为几个 110dp 的方块引图片库
- `kw` + 纯数字的作者名是机器账号，认出来就留空；播放量按中文单位（万/亿）显示

**验收记录（2026-10-09，Pixel_8 模拟器，三个适配器一起跑）**：推荐歌单 6 个 →
「国风热歌来袭」`total=102 取回 102 首，第一首=牵丝戏，档位=[flac, 320k, 128k]，专辑=444778`；
专辑「叶惠美」11 首；榜单「飙升榜」298 首。首页歌单卡片带封面，点进详情页
（共 102 首 · 101.2万播放），点歌 → `state=PLAYING`、"正在播放 · 无损 · 经 星海音乐源"，
点行内专辑名 → 「十二新作」12 首。

**当时未验证、现已补**：榜单分页（见"真机验收第三轮"，`PAGE_SIZE` 抬到 100 + 触底续取）。
**仍未验证**：`digest-5` 那一支的真实歌单（推荐列表这次给的都是 `digest-8`，代码路径没有上机跑到）；
超过 200 首的歌单仍只取第一页（`rn=200`），长歌单会截断。

### M6 聚合搜索与自动换源 — 已完成（`v0.7.0`）：多路聚合、取址失败换源、换源开关与取址预算

**聚合搜索**（`core/search/AggregateSearch`）：内置适配器与所有自报声明了 `search` 的音源
**并行**打，每家一路、各自 12s 超时。结果**按供给方分组**保留，不摊平去重——同一首歌在
酷我内置与某条音源里各出现一次是两条供给路径，换源要用到它们，摊平就把备选丢了。
失败的那一路带着原因显示成"这一路没结果：…"，不静悄悄少一块结果。

**自动换源**（`core/source/SwitchingPlayUseCase`）：本平台的音源全试过仍拿不到地址时，
用 标题+艺术家 去其他启用音源声明的平台重新搜，命中后按同一档位链取址。
匹配条件除归一化 `matchKey` 相等外，还要求**时长差 ≤10 秒**——同名翻唱与现场版很多，
光靠歌名会把人换到另一首歌上去。换源结果写进 `PlayUrl.switchedFrom`，界面那句播放说明
变成"正在播放 · 无损 · 换源自 kw · 经 假源丙 · tx"，与 CONTEXT.md 的"降级必须可见"一致。

- `PlayUrl` 加 `switchedFrom`；`LaunchOutcome.describe()` 成为三个列表页共用的措辞
  （档位、降级、换源三件事都要说出来，之前每页各写一份字符串）
- 音源的 `musicInfo` 反向解析补上（`fromMusicInfo`）：`singer` 可能是 `[{name}]` 也可能是
  `"a/b"`，`types` 可能是 id 数组也可能是带 size 的对象——不同音源给的是不同那一种
- 界面侧与服务侧的惰性解析器改走同一个换源入口，否则队列里下一首失败时不会换源

**测试（假音源，不碰真实平台）**：`AggregateSearchTest` 5 条——分组与标签、一家挂掉
不拖累别家、失败后换平台成功、换不到等价歌时抛回原始原因、时长差 3 分钟不认作同一首。

**验收记录（2026-10-09，Pixel_8 模拟器）**：搜"jay" → 分组标题"酷我"、行内带
`周杰伦 · kw` 与专辑链接、点标题出声（`state=PLAYING`、"正在播放 · 无损 · 经 星海音乐源"）、
点专辑名进详情页；JVM 单测与 21 条相关仪器测试全绿。

**未验证**：**真实多音源下的换源路径**——手上这条星海源只声明了 `musicUrl/lyric/pic`，
没有 `search`，所以聚合只有内置一路、换源在真机上不会被触发。要在 release 前拿一条声明
`search` 的音源在真机复验，否则 M6 的这两条能力只有假源证据。

### M4 收藏与音乐库 — 已完成（收藏、歌单管理、JSON 备份导入导出）

**为什么现在能做**：M4 当初被判定"工具链做不了"是因为 Room 2.8.5 的 kapt 处理器读不了
Kotlin 2.4 的 metadata，而 KSP 还没有配对版本。这张表只有一列主键加一个编码串，
用 `SQLiteOpenHelper` 手写就完全不需要注解处理器——阻塞的是依赖，不是需求。

- 曲目整条塞进 `SongCodec.encode` 的紧凑串存一列：以后 `Song` 加字段不用改表结构
- 收藏 key 是 `source:id`——同一首歌在酷我与网易是**两条**收藏，不能按歌名合
- v2 加了 `playlists` / `playlist_items` 两张表，`onUpgrade` 从"删表重建"改成只建新增的表：
  沿用删表会把所有老用户的收藏清空，这条由 `upgradeFromV1KeepsFavoritesAndAddsTables` 钉住
- 三个列表页共用的 `TrackRow` 加了可选序号与心形；音乐库页的序号留空

**验收记录（2026-10-09，Pixel_8 模拟器）**：`FavoritesRepositoryTest` 3 条（增删、
跨实例读回且字段不丢、跨平台算两条）+ `AggregateSearchTest` 5 条全绿；界面上播放页点
♥ → 文案变"已收藏"，音乐库出现"收藏 1 首 · 大梦归"，点行内 ♥ → "已移出收藏" 并回到空态，
`am force-stop` 后重进收藏仍在。

**未验证**：真机上的收藏（同一份代码，风险低但没有）、上百条收藏时的滚动表现。

**返工：详情页丢了迷你播放条**（`LyraApp` 的 bottomBar 条件）
：`if (onTopLevelDestination)` 把播放条和导航栏一起藏了，结果是**在专辑/歌单页点了歌之后，
没有任何入口回到播放页**——只能在列表里来回切。改成"除播放页自身外都显示播放条，
导航栏仍只在顶层 Tab 显示"。这条是上机点按时发现界面无反应才暴露的，
静态看代码只会觉得"详情页干净一点"很合理。

**切片2a：自建歌单**（`PlaylistRepository` + 音乐库页 + 长按选择器）

- 歌单成员按加入顺序排，`PRIMARY KEY(playlist_id, key)` 就是"一首歌在一个歌单里只有一行"
  这条约束本身，重复加入不产生第二行
- 入口是**长按任意曲目**：收藏与歌单这两个动作四个列表页都要，每行再挂两个按钮就把曲名
  挤没了；选择器状态挂在应用级一份，四个页面共用
- 歌单详情复用专辑/平台歌单那一份 `TrackListScreen`（三种列表在界面上是同一件事），
  成员变化由仓库的计数驱动重读——在歌单页里长按移出，不能看起来没反应
- 音乐库页改成一条 LazyColumn：收藏区 + 我的歌单区（新建 / 长按改名或删除）

**验收记录（2026-10-10，Pixel_8 模拟器）**：`PlaylistRepositoryTest` 7 条（建/改名/删、
加入顺序与字段往返、成员按歌单隔离、导入去重、删歌单不动收藏、v1 库升级保住收藏）；
全量 instrumented 88 条通过。界面上长按"自由的你"→ 新建「shenye」并加入 → 选择器打勾、
提示"已新建「shenye」并加入"，音乐库出现"我的歌单 1 个 · shenye 1 首"，点进去是
"共 1 首"的详情页。

**切片2b：JSON 备份导出/导入**（`LibraryBackup` + 设置页数据区）

- 备份格式是 Lyra 自己的、带 `app:"lyra"` 与 `version`：曲目原样存 `SongCodec` 的紧凑串，
  不再发明第二套字段表——字段表一旦和 `Song` 漂移，备份就变成"看起来能恢复、其实丢东西"
- **导入永远是合并，绝不覆盖**：拿旧备份恢复时最不能接受的是"这一下把新收藏清了"。
  收藏按主键自然跳过已有，歌单**按名字并入**同一个（否则同一份备份导两次会得到两个"深夜"），
  所以 `importPlaylists` 进门先 `refresh()`——同名并入靠的是内存里的名字表，没刷过就是空的
- 读不出的行只跳过并计数（`skippedSongs`），不整份失败；但"全是读不出的行"要报
  "有 N 条曲目，但一条都解不开"，而不是"这个备份里没有内容"——两者对用户是两回事
- 文件读写走 SAF（`CreateDocument` / `OpenDocument`）：应用没有写公共目录的权限了，
  让用户自己挑文件比偷偷塞进 Downloads 诚实，也不受厂商存储策略差异影响
- 结果话报在 snackbar 而不是页面顶部：这两个按钮在长列表的最底下，顶部那行字看不见等于没反馈

**验收记录（2026-10-10，Pixel_8 模拟器）**：`LibraryBackupTest` 6 条（字段往返、导出是真
JSON、拒外来/坏文件/版本过新、坏行只跳过并计数、"空"与"解不开"分别报错、
导出→清空→导入原样恢复且重导不翻倍）；全量 instrumented 95 条通过。界面上长按曲目
→ 收藏 + 新建「fengge」→ 导出得到 `lyra-backup-20261010-0427.json`（1137 字节，
`/sdcard/Download` 里能读到原文）→ 连导两次报"新增收藏 0 首；歌单新建 0 个、并入 1 个、
加进 0 首"，音乐库仍是"收藏 1 首 / 我的歌单 1 个"；挑一个 `app:"lxmusic"` 的文件报
"这不是 Lyra 导出的备份（缺 app=lyra 标记）"。

**未验证**：真机上的 SAF 读写（模拟器 DocumentsUI 冷启动要 5 秒以上，真机更快但没跑过）。

### M7 设置页 — 切片完成（外观 / 播放默认值 / 缓存 / 音源入口）

设置以前散在三处（歌词字号在歌词页、音质在播放页、音源管理在音乐库空态里），
**空态那个按钮只在没有收藏时出现**——收藏一首之后音源管理就没有入口了。现在统一到一个设置页：

- 外观：深色模式（跟随系统 / 浅色 / 深色）、动态取色开关、预设配色
  **开关开着时预设 chip 跟着禁用**，否则用户点了没反应会以为坏了
- 播放：默认音质、歌词字号。**默认音质以前只活在内存里**，重开应用就回到无损
- 存储：播放缓存实际占用 / 上限，清除走 `SimpleCache.removeResource`
  而不是删目录——播放器可能正开着这些文件，从磁盘抹掉会让索引与文件失配
- 数据：音源管理入口（不再依赖空态）

`AppSettings` 存的是**枚举名而不是中文标签**，标签会随文案改，名字不会。

**验收记录（2026-10-09，Pixel_8 模拟器）**：设置页各区块渲染正确；点深色 → 整应用立刻变暗
（状态栏图标跟着走）；关掉动态取色 → 预设 chip 由灰变亮，点余烬 → 主色立刻变橙；
默认音质改"标准"后 `force-stop` 重进仍是"标准"；缓存显示 127.8 MB，点清除后变 2 KB。

**未验证**：浅色模式下两套预设的可读性、真机上的动态取色差异。

### M7 本地音乐库 — 切片完成（扫描、播放、收藏与备份都能带上本地曲目）

音源是会挂的（野花那个 `flower.tempqcics.tk` 已经解析不动了），手机里的文件不会。
这一片的意义是：**Lyra 不再只是一个音源前端**。

- 只读 MediaStore，不自己走文件系统：分区存储后枚举 `/sdcard` 需要
  MANAGE_EXTERNAL_STORAGE（上架会被问为什么），而 MediaStore 给的正好是"用户能听到的那些歌"
- 本地曲目映射成 `Song`（`source=local`、`id` 就是 `_id`、`localUri` 存 `content://`），
  **不另立一种类型**：列表行、长按选择器、收藏、歌单、备份、播放链全都已经认 Song，
  开一条平行通路等于把这些能力在本地那一半全部重做
- 取址在 `SwitchingPlayUseCase` 最上面短路。不短路的话：音源都不声明 `local`，直取必然失败，
  失败又触发换源——**用户点的是手机里这个文件，却给他播了别家曲库的"同一首"**
- 数据源从纯 HTTP 换成 `DefaultDataSource`（它自己认 `content://`/`file://`，
  http 仍走我们调过 UA 与超时的工厂）。`NowPlayingSpec` 的封面白名单放进 `content://`
  但**仍不放 `file://`**——别的进程拿到路径也打不开，只会显示占位图
- 未授权与"扫过但一首没有"是两种空态，必须分开：前者点"授权"能立刻重扫，
  后者是实话。混成一句"本地没有音乐"就是在骗人
- 档位是按容器与码率给的近似（flac/wav→无损，其余看码率），只影响显示；
  `ogg` 不算无损

**已知代价**：本地文件也过 `CacheDataSource`，等于把手机里已有的字节再抄一份进 128MB 播放缓存。
不在这一片修是因为按 scheme 分派要在 `open(DataSpec)` 时决定，得自己写一个委托 DataSource，
风险落在 crossfade 与 seek 上；缓存可见化那一片本来就要逐条统计，届时一并处理。

**顺手修的既有缺陷**：迷你播放条副标题以前是"艺术家为空 → 显示「导入音源后即可播放」"，
本地文件常常没有标签，于是**正在播本地歌也写着"导入音源后即可播放"**。现在只有真没在播
才用这句，有歌但没艺术家时显示"未知歌手"（与列表行一致）。

**验收记录（2026-10-10，Pixel_8 模拟器）**：`LocalMusicRepositoryTest` 4 条（MediaStore 行
映射成 Song、IS_MUSIC=0 不收、无专辑图不给假地址、档位近似）、`AggregateSearchTest` 新增
2 条（本地曲目不碰任何音源、`localUri` 为空时报"文件"而不是"没有音源声明 local"）、
`SongCodecTest` 新增 2 条（`localUri` 往返、**加字段之前存出去的旧串仍能解开**）；
全量 instrumented 101 条通过。界面上推两个 WAV → 音乐库"本地音乐 2 首" → 查看全部 →
点第一首 → `LyraPlay: 起播 local/53 用时 227ms，档位 flac（请求 flac）经「本地文件」`
且 `AudioTrack state:started sampleRate=44100`；长按收藏 → 导出备份里读到
`content://media/external/audio/media/53`；`pm revoke` 后变"需要音频文件权限…点右上角授权"，
点授权 → 系统弹窗 → 允许 → 立刻回到"本地音乐 2 首"。

**未验证**：真机上的 MediaStore 扫描（文件很多时的耗时）、带 ID3 标签的 MP3/FLAC 的标题与封面、
上百首本地曲目时的列表滚动。

**顺带**：`ArtworkProbeTest`（真音源 pic 探针）在全量跑时随机报红——它要第三方后端在 10s 内
回话，而全量跑时设备正忙。单跑必过、全量偶发失败就是典型的"诊断当回归钉子"。改成后端不回话
时 `assumeTrue` 跳过，只有真拿到非 http 地址才判失败。

### M7 流式缓存可见化 — 切片完成（每条缓存是谁的、占多少、能单条删）

设置页原来只有一个总字节数 + "清除缓存"。想腾出某一首的位置只能全清，
把另外几十首的下载一起赔掉——所以这一片的目标不是"显示得更细"，是**让"删一首"这个动作存在**。

- 缓存的键就是 `lyra://song/<编码>` 那个占位地址：`CacheDataSource` 套在惰性解析器**上面**，
  它看到的永远是占位地址而不是每次都不一样的 CDN 直链。键稳定，重播才谈得上命中，
  也才认得出这条缓存是谁的——`entries()` 直接 `SongCodec.fromLazyUri` 反解回曲目即可
- **认不出的键照样列出来**（标"认不出是哪首歌" + 键前缀）：那笔占用是真实的，
  因为反解不回就当它不存在，等于谎报总量
- 总量改从 `SimpleCache` 的索引读，不再 walk 目录——目录里还有 `index.bin`，
  按文件数出来比真正缓存的媒体字节要大
- 删除走 `removeResource(key)` 逐条删，不碰目录：播放器可能正开着这些文件。
  正在播的那条删不动，所以 `remove` 返回成功与否，界面说"这条删不掉，可能正在播放"

**这一片当场揪出的一个 bug**：队列的**起始曲**以前直接带已解析地址，所以它的缓存键是
`content://…`（本地）或那条一次性 CDN 直链（在线）——本地那首在详情页显示成"认不出是哪首歌"。
本地这条已修：起始曲也走占位地址，而本地"解析"不花钱（不打网络），键因此稳定。
**在线起始曲仍是那个形状**（键是一次性直链，下次播同一首命中不了，等于白下一遍），
没一起改是因为把解析器挪到缓存之上才能让键变稳定，代价是每次播放都要重打音源——
那比现在的浪费更糟。记在下面的"已知代价"里。

**已知代价（两条，都在上面那条改动之后仍然成立）**

1. 本地文件也会被抄进播放缓存：实测 `CacheDataSource` 完全不看 URI 的 scheme
   （只认 `FLAG_IGNORE_CACHE_FOR_UNSET_LENGTH_REQUESTS`），所以"手机里本来就有的字节"
   会再存一份到 128MB 配额里。要按 scheme 绕开得自己写一个在 `open(DataSpec)` 时分派的
   DataSource，风险落在 crossfade 与 seek 上；现在至少**看得见、删得掉**
2. 在线起始曲的缓存键是一次性直链（见上一段）

**验收记录（2026-10-10，Pixel_8 模拟器）**：`LyraCacheTest` 4 条（写两条真缓存后能列出且
字节对、能反解出 Song、按大小倒排；单条删除只影响那一条；认不回的键仍被列出；
清空后归零且删不存在的键报失败）；全量 instrumented 105 条通过。界面上播一首本地
WAV → 详情页"共 2 条 · 1.9 MB"，本地那条显示"缓存探针 / 未知歌手 · 本地文件 / 1.9 MB"，
在线那条显示"霍元甲 / 周杰伦 / 林志玲 · kw / 3 KB"；点单条删除 → "共 1 条 · 1.9 MB · 已释放 1.9 MB"；
全部清除 → "共 0 条 · 0 KB"、空态文案、按钮转灰。

**未验证**：上百条缓存时的详情页滚动、正在播的那条删不掉时的提示文案（构造成本高，
逻辑由 `remove` 的返回值保证）。

### M7 lint 首跑与图标收口 — 切片完成（0 error，图标语义补齐）

lint 从来没跑过。第一次跑：**18 errors / 37 warnings / 1 hint**。分诊原则是
"能改就改，不该改的要留下为什么不该改"，不是把报告清零。

**真问题，改了**

- `UnsafeOptInUsageError` ×18：根因是我们自己的 `LyraCache` 挂着 `@UnstableApi`，
  把这个标记**传染**给了每个调用方。改成它自己 `@androidx.annotation.OptIn` 吞掉，
  调用方的注解全部消失。踩过的坑：写 `@OptIn(UnstableApi::class)` 会解析到
  **Kotlin 自带的那个 OptIn**，压不住 AndroidX 的实验性标记，错误反而从 18 涨到 28
- `FrequentlyChangingValue`：首页回顶按钮的可见性直接在 composition 里读
  `listState.firstVisibleItemIndex`——那是每滚一行就把整块重组一遍。换成 `derivedStateOf`
- `AutoboxingStateCreation`：本地列表的 `mutableStateOf(songs.size)` → `mutableIntStateOf`
- 通知权限以前在 31/32 上也会去 `launch()` 一次：那两个版本上它根本不是运行时权限，
  只会拿到"拒绝"。加了 SDK_INT 门槛
- 采纳 KTX：`prefs.edit {}` ×5、`url.toUri()` ×3（`SQLiteDatabase.transaction` 没采纳，
  见下）
- `StaticFieldLeak`：`LocalMusicRepository` 以前存 Context 却被进程级单例持有。
  改成只收 `ContentResolver`——它本来就只需要解析器，顺带把"没授权也查一次"的
  冗余判断收归调用方

**有意为之，压掉并写明理由**（`@SuppressLint` / `tools:ignore`，每条都带原因）

- `GetInstance`：AES/ECB 是酷那侧只认这一种，换 CBC/GCM 就再也解不开它的响应
- `InsecureBaseConfiguration`：播放地址由第三方音源给，域名枚举不完（配置文件里本来就写着）
- `ExportedService`：MediaSession 的锁屏/通知/蓝牙/车载全都要从进程外绑定
- `LockedOrientationActivity` / `DiscouragedApi`：锁竖屏是有意的，平板双栏在二期之外
- `DataExtractionRules`：`allowBackup=false` 已经同时关掉云备份与设备迁移
- `GradleDependency`：在 `lint {}` 里整条关掉——compileSdk 被 AGP 卡在 36，
  报"有新版本"只会让人以为该升而没升
- `UseKtx`（`db.transaction {}`）：那是 `androidx.sqlite:sqlite-ktx` 里的扩展，
  为一条风格建议引一个新依赖不划算

**试过又回退的一条**：`ObsoleteSdkInt` 说 `mipmap-anydpi-v26` 在 minSdk 31 下可以并进
`mipmap-anydpi`——真改了 AAPT 直接报 `resource mipmap/ic_launcher not found`。
零收益、真风险，回退，这条警告留着。

**图标与无障碍收口**

- `SourcesScreen` 里有一份**私有的 `BackGlyph`**，和 `ui/common` 那份长得不一样。
  删掉，统一用共享那份——"这里一个播放键那里一个"正是这个文件开头说要避免的事
- 8 个 Canvas 直绘图标全部加 `label: String?`，非空时挂 `semantics { contentDescription }`。
  直绘的东西对读屏是不存在的，不给一句话，TalkBack 只会念"按钮"
- 那 7 条 `UnusedResources`（`action_play`/`action_pause`/`action_previous`/`action_next`/
  `action_back`/`player_favorite`/`player_favorite_on`）**没有删**：它们是早就备好、
  但一直没挂到按钮上的文案。这一片把它们挂上了，警告因此自然消失

**验收记录（2026-10-10，Pixel_8 模拟器）**：`:app:lintDebug` **0 errors / 1 warning**
（剩的就是上面那条回退掉的 mipmap 建议）；JVM 测试 + 全量 instrumented 105 条通过；
播放页截图确认返回/收藏/播放顺序/上一首/播放/下一首六个图标位置与外观无变化。

**未验证**：读屏软件实际念出的文案（本机没有 TalkBack 环境，语义是按构造正确性接的，
不是听出来的）；`derivedStateOf` 那处改动的帧率收益没有量化。

### M7 图标正式化与排版走查 — 切片完成（M7 收口）

**图标：从"自己画"换成官方 Material 几何**

先试的是官方依赖，结论是不引：`material-icons-core` 只有 48 个通用图标，
**播放、暂停、上下曲、循环、随机一个都没有**；要凑齐只能引 `material-icons-extended`
（六千多个 ImageVector、几十 MB 的 AAR），而这两个 artifact 在 Compose Material 1.7.0
就被官方废弃、BOM 永久钉在 1.7.8。Google 现在对新项目的指引本身就是"把要用的图标
导成自己的矢量资源"。

所以改为从 `google/material-design-icons`（Apache 2.0）逐个取官方 SVG，转成
`res/drawable/ic_*.xml`（16 个），**每个文件头部写着来源路径与许可证**；转换只做了
换容器格式 + 删掉 24×24 的透明占位路径，`pathData` 一个字符没动。图标从此是项目自有的
设计资产：Compose 升级不影响它，换图标是改代码而不是等一个停更的库。

- `ModeGlyph` 以前四种播放模式画成**同一个转圈**，看不出当前模式；现在
  顺序=format_list_bulleted、列表循环=repeat、单曲循环=repeat_one、随机=shuffle
- 歌词校准行原来借用播放模式的转圈图标，改成 `tune`
- Tab 图标：首页=equalizer、搜索=search、音乐库=queue_music

**排版走查**

- **区块标题在五个文件里是五种写法**：设置页与搜索页是 `titleSmall + SemiBold`，
  音乐库与首页是 `titleMedium + SemiBold`。统一到 `titleMedium`，并且**删掉手工
  `fontWeight` 覆写**——M3 的 titleMedium 本身就是 500，字重该由字阶给，不该每处再手拧一遍
- 有意保留的手工字重：歌词当前行、播放页歌名（headlineSmall 是 400，展示位需要更重）、
  榜单前三名的序号——这三处是"强调"而不是"区块标题"，不属于同一件事
- **触控命中区 48dp**：列表行的收藏心形原来是 `size(36.dp)` 的裸 clickable，迷你播放条的
  播放键原来是 `size(38.dp)`——都低于 Material 下限。改成 IconButton 承担 48dp 命中，
  视觉尺寸不变（心形 20dp、圆面 38dp 仍是原样）
- 已知未改的两处：内联的"专辑名"文字链接（在双行列表里做不到 48dp 高，除非把行撑坏，
  属于 Material 认可的 inline link 例外）；回顶按钮用 M3 的 Small FAB，规范尺寸就是 40dp

**验证（2026-10-11，Pixel_8 模拟器）**：`:app:lintDebug` 0 error / 1 warning；
JVM + 全量 instrumented 105 条通过；逐屏截图确认首页/播放页/专辑页/本地列表/迷你条
的图标与字阶正常，播放模式图标已能区分；本地文件起播 47ms；
**迷你条按钮实测 PLAYING → PAUSED → PLAYING**（结构动过，必须点一次才算数）；
副标题在有歌但无标签时显示"未知歌手"而不是"导入音源后即可播放"。

**未验证**：读屏软件实际朗读的图标文案（本机无 TalkBack 环境，`contentDescription`
是按构造正确性接的）；在线播放这一轮没重验——星海源后端此刻超时（界面正确报了
"星海音乐源: 音源超时无响应"，正是失败可见那条设计在起作用），播放链的代码路径
与上一轮相同，只有列表行尾控件与迷你条结构动过。

### 真机验收第一轮（PLK110，用户验收）— 8 项问题全部定位并修复

| 现象 | 根因 | 修法 |
|---|---|---|
| 酷狗的歌没有封面 | 只读了 `AlbumImage`，这一版接口常回空串；图其实在 `Image`/`union_cover`，且带 `{size}` 占位 | 依次取 `Image`→`union_cover`→`AlbumImage`，占位换成 480，http 升 https |
| 用预设配色时顶部状态栏那条没适配 | 预设只覆盖了 `surface`，**没覆盖 `background`**：外层 Scaffold 画 background、顶栏画 surface，于是状态栏那条留着 Material 基线色 | 四套预设都补 `background = surface`，并把这个约束写进注释 |
| 二级界面底部没抬高，播放条被手势条/大圆角切 | 详情页没有 NavigationBar，而它本来是吃系统安全区的那一层；播放条自己没垫 | 无导航栏时给底栏 `windowInsetsPadding(navigationBars)` |
| 点播放条后播放页已展开却还能看见原页面 | 入场动画是"升起 + 淡入 200ms"，而底下的 Tab 页入场期间不动（那是上一轮定的），半透明就等于透视 | 入场去掉淡入，播放页自己铺一层不透明 `background` |
| 太多歌曲没有歌词 | 内置歌词只有酷我一轨，且只认 kw 的 id；酷狗平台的歌没有任何兜底 | 新增 `KuGouLyricProvider`（`lyrics.kugou.com` search→download），**只要关键词+时长，不需要 hash**，因此能跨平台兜底；`LyricLookup` 加第三轨 |
| 播放顺序按钮没了 | 上一轮把收藏做成独立一行塞进详情页，那一列不可滚，最下面的模式行被挤出屏幕 | 收藏移到顶栏右侧（正好填上原来占位的 48dp），详情列加 `verticalScroll` |
| 播放页/歌词页宽度没顶满 | 整页吃了 `safeDrawing` 的**左右** inset，大力角机器把圆角也算进去，于是内容比屏幕窄 | 只吃上下（`only(WindowInsetsSides.Vertical)`），左右仍由 20dp 控制 |
| 音乐库空态的音源管理按钮 | 入口重复，且收藏一首之后它就消失（反而没了入口） | 空态只留提示文案，入口统一在设置页 |

**测试**：新增 `KuGouLyricProviderTest`（3 条，含"时长缺失就跳过"这条真实约束）与
封面断言（必须 https、不能留着 `{size}`）。模拟器实测：预设余烬下状态栏与页面同色、
详情页播放条抬到手势条之上、模式行回来了、收藏心形在顶栏。

**未验证**：#4 的入场透视与 #7 的左右 inset 都依赖真机的大圆角与真实动画时序，
模拟器上只能看到代码层面的修正，需要下一轮真机确认。

### 真机验收第二轮（PLK110，用户验收）— 1 个闪退 + 4 项收口

**闪退：酷狗搜索"许嵩"必崩，搜"Jay"不崩。** 崩溃日志是
`IllegalArgumentException: Key "酷狗-kg-328926" was already used`。根因分两层：

- 酷狗一首歌会带出多个 `FileHash`（`Grp` 里的现场版/DJ 版），而上一版按 `Audioid+FileHash` 去重，
  于是同一条 `source:id` 出现两次
- 列表用 `key = "${source}-${id}"` 给 LazyColumn，重复身份在测量阶段直接抛异常

修法两边都堵：适配器改按 `source:id` 去重（一首歌只进一次），三个远端列表**不再传 key**——
同一首歌在一份结果里出现两次是合法数据，不该由 UI 层假设它唯一。
回归测试 `neverReturnsDuplicateSongIdentity` 用真机同款关键词"许嵩"，模拟器实测 41 条、身份 41 条唯一。

其余 4 项：

| 反馈 | 改动 |
|---|---|
| 播放顺序不要把四种模式全铺出来，点一下换一个、还在底部 | 删掉整条模式行，改成控件行左侧一个循环按钮（图标 + 小字标签），右侧放等宽占位，否则传输三键会被挤偏 |
| 退出动画要像缩回迷你播放条 | `collapseToBar`：向下滑 1/4 屏 + 以**底边为原点**缩到 0.9 + 延迟淡出；入场是它的逆（`expandFromBar`，仍不带淡入以免透视到 Tab 页） |
| 播放页宽度还是不对，去看返回按钮 | 顶栏只吃 8dp、正文吃 20dp：`IconButton` 的图标在 48dp 触摸区里自带 12dp 内缩，两层都给 20dp 时返回箭头的视觉左边缘会比正文多缩 12dp，看着就是"右边空一截" |
| 主题配色参考系统设置那套 | 预设从手写的两套扩到 8 套（青/蓝/靛蓝/紫罗兰/粉红/黄/橙/深橙），**不再逐个手写 ColorScheme**，改由 `schemeFrom(accent, dark)` 从单个色相推出全部 M3 角色；设置页的配色选择器换成色板（三段色圆 + 选中打勾 + 色块底），中性面饱和度压到 0.08 上限，否则整屏像蒙了一层色 |

**验收记录（2026-10-09，Pixel_8 模拟器）**：`KuGouSearchProviderTest`（2 条，含去重回归）、
`KuGouLyricProviderTest`（3 条）、`AggregateSearchTest`（6 条）、`FavoritesRepositoryTest`（3 条）全绿；
设置页色板 8 项渲染正确，关动态取色 → 色板由灰变亮，点"橙色" → 整应用（含状态栏、输入框描边、
筛选芯片）立刻跟变，重启后仍是橙色。

**未验证**：播放页的三项（模式按钮位置、退出动画、返回箭头对齐）模拟器上进不去播放页
（没导入音源，点歌不起播），只能靠代码层修正，需真机确认。

### 真机验收第三轮（用户验收）— 取址无限等待、音源兼容性、榜单分页

**点歌后一直转圈、没有提示、点别的也没反应。** 真机取证：19:45 点"大梦归"，到 19:50
媒体会话仍是 `state=NONE`，那一行的转轮一直在转；采样线程表里有 `lyra-source-a18`
（QuickJS 线程）和两个以域名命名的在途 HTTP 线程，20 秒后换成了别的调用——
音源脚本在反复问它自己的后端，一次一次拿不到地址。

根因是**纯串行的重试链没有预算**：单源沿档位链 4 档、每档 `invoke` 超时 30s = 120s，
全失败后抛 `PlayUrlException`，换源循环再拿每个候选走一遍同样的链——一次点击最坏是几十分钟。
而 `HomeViewModel.play()` 开头那句 `if (loadingSongId != null) return` 会把这期间所有其它点击静默吞掉。

| 改动 | 说明 |
|---|---|
| 单次音源调用超时 30s → **10s** | 正常回话是 1–3s，30s 只在于"后端死了也要等满" |
| **档位链按音源自报能力过滤** | 与 lx 的 host 一致：野花只有 128k，就不该先问它要 FLAC/320k。为此把 `qualitys` 一起存进 `sources.json`（旧格式仍能读） |
| 区分"超时"与"这一档没有" | 后端不回话时换下一个源，不再沿档位链空等三轮 |
| 一次点歌总预算 **20s** | 10s 直取 + 8s 换源落点；到点明确报"取址 20s 没有结果，已停止（音源后端多半无响应）" |
| 每一步回写提示 | `正在向「星海音乐源」取址（无损）…` / `「x」没有 无损 的地址，正在试 高品质…` / `直取不到地址，正在换源找同一首（1/2：kw → kg）…` |
| 新点击取消上一次在途取址 | 抽成 `SongPlayController`，四个列表页共用一份（以前每页抄一份，`recordEffective` 就曾被漏掉过） |
| 加 `LyraPlay` 日志 | 起播耗时、实际档位、经哪个源、是否换源 |
| 封面加载限时 3s | 拿不到图不该挡住起播 |

**导入其它音源提示校验失败（同一份脚本在 lx 里能正常导入）。** 根因是我们把
`send(inited, {status:true})` 当门禁，而野花系脚本发的是 `send(inited, {sources})`，
压根不表态，`optBoolean("status", false)` 直接判失败。对照 lx
`user-api-preload.js` 的 `handleInit`：**status 判断被官方注释掉了**，只看 `sources`，
并按 `allSources` / `supportQualitys` / `supportActions` 白名单归一化。照此实现
（归一化放在 preload 里，Kotlin 侧契约不变），只有一处差别：官方 actions 只留 musicUrl，
Lyra 额外认 `lyric`/`pic`——歌词与封面确实有源在实现它们，砍掉等于自废功能。
能力表为空才算失败，并且优先用脚本自己说的原因（"服务器异常"不该被换成通用文案）。

**自动换源开关**：设置 → 播放 → "取不到地址时自动换源"，默认开；关掉后直取失败立刻报错，不再问第二家。

**榜单分页 + 整页滚动 + 回顶**：

- `PAGE_SIZE` 30 → **100**（实测服务端 `rn=300` 也只回 100），触底续取，页脚"已显示全部 300 / 300 首"；
  跨页与页内重复都不接成两行
- 整页一条 `LazyColumn`：推荐歌单跟着滚走，**换榜那一排芯片吸顶**（滚到底也能换榜），
  吸顶行必须有不透明底色，否则列表会从芯片缝里透出来
- 下滑超过 10 行淡入回顶按钮，点回列表顶部
- 踩到的坑：`LaunchedEffect` 里直接读 `listState.layoutInfo` 只在 Effect 重启时读一次，
  滚动过程中根本不检查——表现为"滚到底也不翻页"。必须 `snapshotFlow { layoutInfo }` 观察

**验收记录（2026-10-09，Pixel_8 模拟器，已导入星海 v3.2.12 + 野花 1.0.0）**：
全量 instrumented 81 条通过；只启用野花点歌 <1s 就报错并显示
`野花🌺: Unable to resolve host "flower.tempmusics.tk"`、转圈停止（这条源自己的域名已解析不了）；
只启用星海点歌 7.2s 起播（`LyraPlay: 档位 flac 经「星海音乐源」`），第 6 秒界面上写着
"正在向「星海音乐源」取址（无损）…"；榜单滚到 300 行、页脚与吸顶正确；
播放页三项（循环按钮、宽度、返回箭头对齐）这次在模拟器上看到了；
设置页 8 个色板与自动换源开关渲染正确，色板关动态取色前保持禁用态。

**未验证**：换源**成功**路径仍没有真机证据（模拟器上星海直取就成功了，走不到换源）；
退场"缩回播放条"的观感要人眼确认（截图取不到中间帧）。

### M7 用户验收五问 — 缓存署名、配色名实、加入歌单入口、提示位置、歌单广场

**1. 缓存详情页显示"认不出是哪首歌"**

根因是缓存键不稳定：队列起始曲直接带了已解析地址（在线那首是一次性 CDN 直链、本地那首是
`content://…`），`CacheDataSource` 拿它当键，`entries()` 再用 `SongCodec.fromLazyUri` 反解
自然认不回。两处修：

- `MediaItemFactory` 给每个 `MediaItem` 打上 `setCustomCacheKey(songUri)`，键从此恒为
  `lyra://song/<编码>`，与实际取到的地址解耦——**解析器仍留在缓存之下**（每次播放仍只打
  一次音源，不为了键稳定而让每首歌都重取址）
- 反解不回的老条目**照样列出**，但换一个诚实的说法：`旧版缓存（认不出曲目）`——那笔占用是
  真实的，装作没有就是谎报总量。旧键格式一次性直链的那批缓存因此不会被误删

**2. 预设配色名实不符**

`TonalPalette.hsl()` 里色相归一只减了一次 `1f`：`hue` 落在 `[0,360)` 之外时（深橙那套用到
的负相位）减一次仍为负，`Color.hsv` 拿到负色相就绕回别的颜色——"深橙"画成了别的色。
改成取模 `((h % 360) + 360) % 360`。

**3. 无法添加歌曲到歌单**

**诊断**：不是"功能没有"，是**入口不可发现**——四个列表页的"加入歌单"都挂在长按上，
不给长按提示就没有人知道。另外查出一个真 bug：`TrackListScreen` 的 `onMore` 以前是
带默认值的非空参数，专辑页/平台歌单页的行尾因此渲染了一个**点了没反应的"…"按钮**。

**方案与落地**：

- 统一入口语义——每行行尾给一个 48dp 的"…"按钮（`MoreGlyph`，官方 `more_vert` 几何），
  与长按是**同一个入口的两种触发方式**；只有长按等于没有入口，所以两者都给
- `TrackListScreen` 的 `onMore` 改成 `((Song) -> Unit)? = null`，默认为空即不渲染按钮，
  死按钮随之消失；专辑页、平台歌单页、搜索页、首页、音乐库、本地列表全部接上

**4. 点歌提示位置**

提示原来挂在列表顶部：点下面的行看不到，而且它占一行会把列表往下挤。改成
`ui/common/MessageSnackbar` 的 `rememberMessageHost` 挂到 `Scaffold(snackbarHost = …)`，
渲染在迷你播放条正上方——不占布局高度、不随内容滚动，取址这种长提示用 sticky（`Indefinite`）
并在结束时自动收掉。四个列表页与设置页统一走这一份。

**5. 推荐歌单无法收藏 + 歌单就那几个**

**规划**：平台歌单存**名刺**（id/名字/作者/封面/曲目数/播放量/简介），**不存曲目快照**——
平台歌单天天在变，存了快照就会"收藏之后看到的还是当天那版"。打开收藏的歌单时按 id 现取，
和从首页点进去是同一条路径。

- **持久化（DB v3）**：`FavoritePlaylistRepository` + `favorite_playlists` 表
  （list_id 主键，ON CONFLICT 忽略即天然幂等）；`FavoritesDatabase` 的 `onUpgrade` 只
  `CREATE TABLE IF NOT EXISTS`，**绝不删表重建**（那会清空老用户的收藏），由
  `upgradeFromV2KeepsEverythingAndAddsTheNewTable` 钉住
- **歌单广场**（`ui/playlist/`）：`LazyVerticalGrid` 两列无限滚 + 最热/最新两组排序
  （以前首页固定"最热"那一版，所以**每天都一样**）；RCM 端点不给总数，`noMore` 据"这一页
  没带来任何新 id"判定；卡片右下角一颗可直接收藏的心
- **入口**：推荐歌单区标题右侧加"更多"（`action_more`）→ 歌单广场；**标题行恒显示**，
  推荐列表取失败时入口也还在
- **收藏入口三处**：首页卡片、广场卡片、平台歌单详情页顶栏（`TrackListViewModel` 新建
  `PlaylistSummary` 再 toggle——两者本来就是同一份数据映射出来的）；音乐库加"收藏的歌单"
  区块，可就地移出
- **备份 v2**：新增 `favoritePlaylists` 数组，仍能读 v1（`readsV1BackupWithoutFavoritePlaylists`）；
  导入照旧是**合并**，收藏的歌单按 list_id 跳过已有的

**测试与验证（2026-10-12，Pixel_8 模拟器 + 真机 PLK110）**

- 新增 `FavoritePlaylistRepositoryTest` 4 条：字段往返且 toggle 能翻转回来、按 id 移出、
  `addAll` 幂等且只计新增、**v2 库升级到 v3 保住原有收藏与歌单并建出新表**
- `LibraryBackupTest` 扩到 8 条：字段往返含收藏的歌单、v1 备份仍能读、缺 listId 的行跳过并计数、
  导出→清空→导入后收藏/歌单/收藏的歌单都回来且重导不翻倍
- 全量 instrumented **112 条通过**（上一轮 105，本轮 +7）
- `:app:lintDebug` **0 errors / 1 warning**（剩的那条仍是回退掉的 mipmap 建议）。
  过程中 lint 揪出一处：`setCustomCacheKey` 是 Media3 的实验性 API，
  `MediaItemFactory.toMediaItem` 依项目既有做法自己吞掉 opt-in，不再向调用方传染

**真机验收（2026-10-12，PLK110，release 包）**：`assembleRelease` 走出 R8（debug 46MB → release 10MB），
安装后冷启动 240ms、无 FATAL。这台机器上**升库前已有的 3 首收藏一首没丢**（v2→v3 迁移在真机
兑现，不只是测试里）。逐项点过：

- 首页推荐歌单标题右侧「更多」→ 歌单广场（最热/最新两组、双列 6 张卡、每张右上角一颗心）
- 点一张的心 → 该卡变"已收藏"（同一屏其余 5 张仍是"收藏"）；回音乐库出现
  「收藏的歌单 1 个」，条目与播放量正确，右侧是可移出的心
- `force-stop` 重进 → 收藏的歌单仍在（真库持久化）
- 从音乐库点进这张歌单 → 详情页标题/简介/曲目正确，顶栏心为"已收藏这个歌单"，
  每行行尾都有「更多操作」（issue 3 的入口）
- 音乐库的收藏曲目行同时带「已收藏」与「更多操作」，两处入口都在
- 用户真机上点歌正常出声（迷你条显示曲名+歌手），R8 没有把音源/播放链剪坏

**补验（2026-10-12，用户真机手动）**：歌单广场的滚动观感与无限翻页到底、备份导出/导入
在真机 SAF 下的往返，均已手工验过；本节先前挂着的两处"仍未验证"至此清零。

**顺手发现（本期不做）**：真机 logcat 仍提示 `OnBackInvokedCallback is not enabled for the
application`，但 manifest 里 `MainActivity` 已启用 `android:enableOnBackInvokedCallback`
（`fbaa98c`）。该警告成因可能涉及系统层或厂商定制，用户确认本期不再追加预测式返回
动画相关改动。

## 计划中的里程碑

- **M0 `v0.1.0`** — 工程骨架：三 Tab 可点、M3 动态取色与明暗、R8 配置
- **M1 `v0.2.0`** — 音源跑通：导入音源 → 搜索 → 点击 → 出声
- **M2 `v0.3.0`** — 播放体系：MediaSessionService 后台播放、锁屏、通知栏、蓝牙媒体键、
  队列模型、crossfade；**并接入 ColorOS 流体云胶囊**

**流体云假设已验证成立**（真机）：接入 MediaSessionService 后，ColorOS 流体云
自动出现播放胶囊，无需任何厂商 SDK——它就是读标准 MediaSession + 媒体通知。
曾经胶囊**没有封面**，根因是播放时只给了 MediaItem.fromUri(url)，MediaMetadata 全空；
该缺口已在 M2 内修复（`41b7ada`）。

M2 切片1 已完成（`8cb322b`）：真机验证 isForeground=true、types=mediaPlayback、
通知 category=transport；退后台 8s/14s 仍 state:started，会话位置持续推进。
- **M3 `v0.4.0`** — 歌词沉浸页：逐字扫光与逐行退化、封面取色模糊背景 — **已收口**
- **M4 `v0.5.0`** — 收藏与音乐库：歌曲收藏、自建歌单、JSON 备份导入导出 — **已完成**
  （平台歌单的收藏于 M7 补上：存名刺、按 id 现取，见"用户验收五问"；是否要离线副本另论）
- **M5 `v0.6.0`** — 榜单与详情页 — **已收口**（歌手页移到二期，分页在第三轮验收补上）
- **M6 `v0.7.0`** — 聚合搜索与自动换源 — **已收口**
- **M7 `v0.8.0`** — 本地音乐库、流式缓存、性能与视觉收口 — **已收口**
  （设置页、本地音乐库、缓存可见化、lint 分诊、图标正式化与排版走查五片；
  用户验收五问补上歌单广场与平台歌单收藏，release 包已在真机验收）
- **发版前置（已完成）**：`assembleRelease` 已走出 R8（debug 46MB → release 10MB）
  并在真机安装运行，音源/播放链未被剪坏；`:app:lintDebug` 0 error / 1 warning

## 明确不做（二期）

评论区（需平台登录态）、**歌手页**（一期榜单/专辑/歌单已够用，从 M5 移出）、手动下载与下载管理、gapless / 倍速 / 跳过静音 / 均衡器、悬浮歌词窗、桌面小组件、多端同步、完整皮肤系统、平板与折叠屏双栏、Windows 端。
