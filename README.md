<div align="center">

# Lyra

**把音乐还给播放器。**

一款专注播放体验的 Android 原生音乐播放器：多音源、沉浸歌词、流畅动效，
以及完全留在本地的音乐库。

[![Release](https://img.shields.io/github/v/release/sakura-jay/Lyra-music?label=release&color=0f766e)](https://github.com/sakura-jay/Lyra-music/releases/latest)
[![CI](https://img.shields.io/github/actions/workflow/status/sakura-jay/Lyra-music/ci.yml?label=CI)](https://github.com/sakura-jay/Lyra-music/actions/workflows/ci.yml)
[![Android](https://img.shields.io/badge/Android-12%2B-3ddc84?logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.x-7f52ff?logo=kotlin&logoColor=white)](https://kotlinlang.org)

[下载最新版本](https://github.com/sakura-jay/Lyra-music/releases/latest) ·
[更新日志](CHANGELOG.md) ·
[项目文档](docs/) ·
[问题反馈](https://github.com/sakura-jay/Lyra-music/issues)

</div>

## 关于 Lyra

Lyra 是一款基于 Android 原生技术栈构建的第三方音乐播放器。它不绑定单一平台，
通过可导入的「音源」扩展内容与播放能力，同时把喜欢、歌单、本地音乐和个人设置
留在你自己的设备上。

> **音源**是可导入的 JavaScript 脚本，用于为 Lyra 补充搜索、播放地址、歌词、
> 歌单或榜单等能力。Lyra 本身不提供、不存储、不分发音频内容。

## 为什么是 Lyra

- **不只依赖一个平台**：同时启用多个音源，聚合搜索，并在结果中标注来源。
- **完整而不将就的播放体验**：后台播放、系统媒体控制、无损音质、自动降级与换源。
- **认真做的歌词页**：逐字扫光、逐行歌词、封面取色模糊背景与歌词校准。
- **音乐属于你自己**：无需账号，收藏与歌单保存在本机，并可随时导出备份。

## 功能

### 发现音乐

- 首页推荐歌单与多来源榜单预览
- 歌单广场、榜单广场与榜单详情
- 聚合搜索：并行查询所有启用的搜索来源，结果按来源分组
- 支持收藏平台歌单，曲目按需获取

### 播放

- 后台播放、通知栏、锁屏与蓝牙媒体键控制
- 顺序播放、列表循环、单曲循环与随机播放
- 128k / 320k / FLAC / Hi-Res 四档音质
- 自动降级并显示**本次实际音质**，不拿请求档位冒充结果
- 取不到播放地址时自动换源，换源过程对用户可见
- 歌曲切换支持交叉淡入
- 播放缓存可调上限，可查看并单独删除缓存曲目

### 沉浸歌词

- 逐字歌词扫光，逐行歌词自动退化为整行高亮
- 封面取色与模糊背景
- 歌词字号可调、歌词时间轴可校准
- 没有歌词时不打扰播放，只给出简洁提示

### 音乐库

- 「我喜欢」独立收藏列表
- 创建、改名和删除自建歌单
- 收藏的平台歌单
- 扫描并播放手机中的本地音乐
- JSON 备份导出与导入；导入为合并模式，不会覆盖现有收藏

### 外观与个性化

- 跟随系统、浅色、深色三种模式
- Android 12+ 动态取色
- 8 套预设配色
- 顶栏、卡片与浮层统一玻璃材质，标准顶栏局部模糊背后内容
- 共享元素转场、骨架屏与统一的动效节奏

### 更新

- 启动时静默检查 GitHub 最新正式 Release，设置页可手动检查
- 更新弹窗展示版本日志，支持关闭本次提示或忽略该版本的后续自动提示
- 应用内下载 APK，校验包名、版本与签名后交给系统安装确认页

## 快速开始

### 系统要求

- Android 12（API 31）或更高版本
- 一台手机（当前版本锁定竖屏布局）

### 安装与使用

1. 前往 [Releases](https://github.com/sakura-jay/Lyra-music/releases/latest)
   下载最新 APK。
2. 在手机上安装。若系统提示，请允许当前文件管理器或浏览器安装未知来源应用。
3. 打开 Lyra，进入 **音乐库 → 设置 → 音源管理**。
4. 从文件导入 `.js` 音源，或粘贴 HTTPS 链接导入。导入时会自动校验，不可用的
   音源不会入库。
5. 回到首页或搜索页，选择歌曲即可开始播放。

首次播放在线歌曲前需要先导入可用音源。手机中的本地音乐无需音源即可直接播放。

### 常用操作

- 播放页点击 ♥ 加入「我喜欢」。
- 长按任意曲目，可加入自建歌单或执行更多操作。
- 下拉首页可刷新推荐歌单与榜单。
- 在设置中调整默认音质、歌词字号、主题与自动换源；缓存详情页可调整缓存上限。
- 在设置中导出备份；更换设备或重装应用后可导入恢复。

> 通知权限用于显示播放控制；本地音乐需要音频读取权限。Lyra 没有云端账号，
> 不会将收藏、歌单或本地文件上传到自有服务器。

## 从源码构建

需要 JDK 17 与 Android SDK 36。

```powershell
# Windows
.\gradlew.bat assembleDebug
```

```bash
# macOS / Linux
./gradlew assembleDebug
```

构建产物位于 `app/build/outputs/apk/debug/app-debug.apk`。

## 技术栈

| 部分 | 技术 |
| --- | --- |
| 语言 | Kotlin |
| 界面 | Jetpack Compose + Material 3 |
| 播放 | AndroidX Media3（ExoPlayer / MediaSession） |
| 音源运行时 | QuickJS |
| 网络 | OkHttp |

Lyra 只面向 Android 原生平台，当前没有 iOS、Windows 或 Web 版本。

## 项目状态

当前版本为 **v0.10.0**，项目仍在持续迭代。

- 已实现：音源、播放体系、歌词、收藏与歌单、本地音乐、榜单与歌单广场、
  聚合搜索、自动换源、缓存管理与数据备份。
- 当前聚焦：Android 手机竖屏体验。
- 后续计划：评论区、歌手页、下载管理、更多播放能力、悬浮歌词、桌面小组件、
  多端同步与更完整的个性化能力。

更详细的变化记录见 [CHANGELOG.md](CHANGELOG.md) 与 [版本发布说明](docs/releases/)。

## 常见问题

**为什么导入音源后才能播放？**

Lyra 是播放器与音源宿主，本身不自带音频内容。在线播放地址由你导入的音源提供。

**歌曲显示但播放失败怎么办？**

先确认音源已启用且网络正常；可以在设置中开启自动换源。部分歌曲受音源能力、
地区或版权限制，可能仍然无法播放。

**备份包含音源吗？**

不包含。备份只保存「我喜欢」、自建歌单和收藏的平台歌单。重装应用、更换签名或
换机后，需要重新导入音源文件。

**支持 Android 11 吗？**

不支持。Lyra 最低要求 Android 12。

**数据会上传到哪里？**

Lyra 没有账号系统，收藏、歌单和设置保存在本机。联网请求用于访问音源、内置发现
服务及其对应的平台。

## 参与贡献

欢迎提交 Issue 和 Pull Request。开始修改前，建议先阅读
[CONTEXT.md](CONTEXT.md) 中的术语约定与 [docs/adr/](docs/adr/) 中的设计决策；
较大的功能改动请先开 Issue 讨论。

## 免责声明

Lyra 仅提供播放器与音源承载能力，不提供、不存储、不分发任何音频内容。音源及其
返回的内容由第三方提供，请仅导入和使用你有权访问的内容，并遵守所在地法律及各
平台的服务条款。

---

<div align="center">

如果 Lyra 对你有帮助，欢迎点一个 Star。

</div>
