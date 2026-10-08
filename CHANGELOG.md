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

### M3 歌词沉浸页 — 代码合入并真机跑通，`v0.4.0` 仍不打

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
**但这一轮还没达成 M3 DoD**：这段时间应用实际只拿到约 60Hz（面板支持 120/144Hz），
"120Hz 下 Jank<1%"没有被真正测到；且 Compose UI 测试在本机跑不起来
（androidx.test 反射 `InputManager.getInstance` 在该系统不存在，镜像里没有更新的 runner），
相关依赖已回退，歌词视图目前只有 JVM 侧的解析/时间线测试兜底。

### M5 榜单与详情页 — 进行中（首页榜单已跑通，歌单/专辑/歌手详情页未开始）

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

**未验证**：榜单封面缩略图（当前列表无图，与搜索页一致）、分页加载（只取前 30 首）、
歌单/专辑/歌手详情页。

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
