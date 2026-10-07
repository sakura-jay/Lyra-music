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

### M1 音源跑通 — 进行中（契约已立，未打 tag）

- `feat(source)` QuickJS 引擎线程 + Promise 排空真机验证（`d529fcd`）：
  绑定库选 `wang.harlon.quickjs:wrapper-android:3.2.3`，与官方移动端同源
- `feat(source)` lx.* 宿主契约、OkHttp 请求桥、crypto/buffer/zlib 原生桥（`3d7d868`）：
  协议逐字对齐官方 preload（`__lx_native__` / `__lx_native_call__` / key 校验 / Uint8Array 语义），
  并补上官方移动版未实现的 zlib、DES、SHA、AES 解密，成为契约超集
- 12 条 instrumentation 测试在真机 PLK110 全绿；真实音源（星海 v2.3.12）完成
  `inited` 握手、来源能力解析与错误归一化
- 补 `INTERNET` 权限；未开 cleartext

**M1 剩余**：`feat/source-import`（音源导入与管理页）、`feat/player-first-sound`
（搜索 → 取址 → 出声）。DoD 要求的"3 个真实音源搜索/取址/歌词通过"尚未达成——
现有这份音源自己就不声明 `search`，故 DoD 需按能力清单逐源验证。因此不打 tag。

## 计划中的里程碑

- **M0 `v0.1.0`** — 工程骨架：三 Tab 可点、M3 动态取色与明暗、R8 配置
- **M1 `v0.2.0`** — 音源跑通：导入音源 → 搜索 → 点击 → 出声
- **M2 `v0.3.0`** — 播放体系：后台/锁屏/通知/媒体键、队列、音质降级、crossfade
- **M3 `v0.4.0`** — 歌词沉浸页：逐字扫光与逐行退化、封面取色模糊背景
- **M4 `v0.5.0`** — 收藏与音乐库：四类收藏、歌单管理、JSON 备份
- **M5 `v0.6.0`** — 榜单与详情页
- **M6 `v0.7.0`** — 聚合搜索与自动换源
- **M7 `v0.8.0`** — 本地音乐库、流式缓存、性能与视觉收口

## 明确不做（二期）

评论区（需平台登录态）、手动下载与下载管理、gapless / 倍速 / 跳过静音 / 均衡器、悬浮歌词窗、桌面小组件、多端同步、完整皮肤系统、平板与折叠屏双栏、Windows 端。
