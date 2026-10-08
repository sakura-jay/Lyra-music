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

### M3 进行中 — 歌词沉浸页（代码已合入，未上机）

按用户要求本轮暂停真机测试：下面四条切片只过了编译与 39 条 JVM 测试，
**观感、手感、帧预算一律未验证**，`v0.4.0` 因此不打。

- `feat(lyric)` 域模型与逐字解析（`8a586d0`）：LRC / lxlyric 逐字 / tlyric 翻译三条路径，
  `[offset:]` 按"正数提前"惯例作用到时间上，翻译按 1s 内就近配对；
  当前行与扫光进度单独放 `LyricTimeline`，视图不自己算时间。写测试时抓到一条真 bug：
  只有一个 `<起,耗>` 标签的行没剥标签，屏幕上会露出 `<0,400>`
- `feat(player)` 播放页与活的迷你播放条（`69b7ba2`）：迷你条从 M0 的静态占位变成真状态；
  播放 URI 可能是已解析的 CDN 直链，认不出是哪首歌，所以把 `lyra://` 歌曲地址放进
  `RequestMetadata.mediaUri` 供反解
- `feat(lyric)` 逐字扫光（`12465f8`）：底字 + 裁到进度处的亮字两层，进度放在只有绘制阶段
  读取的 `FloatState`，每帧只重绘一行、不触发列表重组；轮询只当锚点，帧间用
  `withFrameNanos` 自己推。目前是硬边推进，软渐变边待上机看过再定
- `feat(lyric)` 封面取色模糊背景（`dd3d68b`）：降采样到 ~256px 再 blur，换封面走两层
  alpha 交叉而不是每帧改 radius（ADR-0005）；`ArtworkLoader` 加 8 张内存缓存，
  同一 URL 不再给通知和播放页各下一遍
- `fix(ui)` 嵌套 Scaffold 重复吃状态栏 inset（`7b990cb`）：搜索页/音源管理页的顶栏标题
  被顶下一个状态栏高度，即用户反馈的"字体意外下沉"

**待做**：`perf/lyric-frame-budget` —— 120Hz 下歌词页滚动与扫光的 Jank <1%（M3 DoD），
以及模糊浓度、纱的透明度、交叉时长、扫光软边这些必须用眼睛定的参数。

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
