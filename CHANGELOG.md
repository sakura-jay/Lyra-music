# Changelog

Lyra 的变更记录。格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循语义化版本；一期版本全部落在 `0.x.y`，其中 `0.<里程碑>.0` 对应一个里程碑收口。

进度权威是 tag 序列，每个里程碑一节，重点写"现在能做什么了"。

## [Unreleased]

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

**Jank <1% 尚未测量**：M0 无可滚动长列表与动效，留到 M3 歌词页一并做（Perfetto + `gfxinfo framestats`）。

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

### M3 歌词沉浸页 — 代码合入并真机跑通，帧预算达标（60fps 标准），待用户看过换行动画后打 `v0.4.0`

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

### M5 榜单与详情页 — 进行中（榜单 / 专辑 / 歌单已跑通，歌手页与分页未开始）

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

**未验证**：分页（歌单详情上限 200 首、榜单 30 首）、`digest-5` 那一支的真实歌单
（推荐列表这次给的都是 `digest-8`，代码路径没有上机跑到）。

### M6 聚合搜索与自动换源 — 代码合入并上机跑通，等待真机多音源验证

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

### M4 收藏与音乐库 — 进行中（切片1 收藏已跑通；歌单管理与 JSON 备份未做）

**为什么现在能做**：M4 当初被判定"工具链做不了"是因为 Room 2.8.5 的 kapt 处理器读不了
Kotlin 2.4 的 metadata，而 KSP 还没有配对版本。这张表只有一列主键加一个编码串，
用 `SQLiteOpenHelper` 手写就完全不需要注解处理器——阻塞的是依赖，不是需求。

- 曲目整条塞进 `SongCodec.encode` 的紧凑串存一列：以后 `Song` 加字段不用改表结构
- 收藏 key 是 `source:id`——同一首歌在酷我与网易是**两条**收藏，不能按歌名合
- `onUpgrade` 目前是删表重建（只有 v1，没有需要保数据的升级）。**出现 v2 时必须换成迁移**，
  那一步会直接清掉用户收藏
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
- **M3 `v0.4.0`** — 歌词沉浸页：逐字扫光与逐行退化、封面取色模糊背景
- **M4 `v0.5.0`** — 收藏与音乐库：四类收藏、歌单管理、JSON 备份
- **M5 `v0.6.0`** — 榜单与详情页
- **M6 `v0.7.0`** — 聚合搜索与自动换源
- **M7 `v0.8.0`** — 本地音乐库、流式缓存、性能与视觉收口

## 明确不做（二期）

评论区（需平台登录态）、手动下载与下载管理、gapless / 倍速 / 跳过静音 / 均衡器、悬浮歌词窗、桌面小组件、多端同步、完整皮肤系统、平板与折叠屏双栏、Windows 端。
