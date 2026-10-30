# v0.10.0 变更记录（玻璃材质与应用内更新）

四片：[PLAN.md](PLAN.md) 定计划，[tickets/](tickets/) 逐条取证。主题与更新链彼此独立，
材质不达标可退回半透明，更新只读 GitHub 正式 Release。

- **024 玻璃材质与控件**：主题层新增唯一取色点 `GlassPalette`、`GlassSurface`、
  `LyraTextField` 与主/次按钮。设置区块、音乐库入口、搜索结果卡、音源卡、歌单封面
  与两个弹窗接入；只做半透明表面、顶部高光与细边缘，不给每张卡片建离屏模糊层。
  选中、按下、禁用、错误与搜索高亮仍走 Material 语义。
- **025 顶栏与连续浮层模糊**：标准页面的 `TopAppBar` 换成覆盖式 `GlassScaffold`。
  内容滚动容器铺满整页，只把原顶栏高度改成 `contentPadding`，首项初始位置不变。
  顶栏把页面图层按 1/4 边长降采样后做 32dp 固定半径 `RenderEffect`，前景标题
  与图标独立绘制；纱层加厚到足以压住滚过的封面大字。首页、音乐库、榜单广场/详情、
  曲目列表、缓存与设置等滚动页启用；搜索、音源与歌单广场没有内容从顶栏后经过，
  退回纯半透明。底部导航与迷你播放条复用页面图层采样，但整页录制只在 Tab 页需要
  浮层时进行；Tab 页 Snackbar 会抬到这两条浮层上方。播放页原有封面模糊不变。
- **026 更新检测与设置**：`UpdateClient` 只读 GitHub 最新正式 Release，严格接受
  `vX.Y.Z`、排除草稿/预发布、要求 APK asset；6/12/20 秒连接/读取/整体超时，
  页面销毁时同时取消在途 HTTP 调用。
  自动检测在首屏可交互后延迟 700ms 触发，每进程最多一次，失败静默；手动检测始终
  展示最新、失败原因与重试。设置新增“关于与更新”，`SharedPreferences` 保存自动
  开关与精确忽略版本；忽略只抑制自动弹窗，更高版本与手动检查仍会提示。更新弹窗
  支持最小 Markdown（标题、列表、粗体、行内代码与链接）。
- **027 APK 下载校验与安装**：WorkManager 唯一任务（expedited）在切页/后台继续，
  支持百分比、取消与重试；写入缓存 `updates/` 临时文件，完成后原子重命名。装前用
  `getPackageArchiveInfo` 校验包名、目标版本、不降级与 SHA-256 签名证书，任一不符
  都拒绝并删除文件。配置缓存专用 `FileProvider`、`REQUEST_INSTALL_PACKAGES` 与
  未知来源引导，只调起系统安装确认页；启动器打不开时保留下载结果并明确报错。
  从安装器返回后重新读取当前版本决定状态。

## 验证

- JVM：`testDebugUnitTest` 162 tests / 0 failed，新增版本三段比较、非 Release 标签、
  忽略/手动语义与 MockWebServer 覆盖（最新、无更新、草稿/预发布、缺 APK、404、403、
  超时、坏 JSON、协程取消）。
- `lintDebug` 通过：0 errors；保留 8 条既有/依赖提示。
- 模拟器（Pixel_8 / API 37）：`connectedDebugAndroidTest` 178 tests / 0 failed /
  2 skipped；`ApkVerifierTest`、`UpdateDialogTest`、`UpdateDownloadWorkerTest`
  覆盖包校验、手动检测弹窗与本地 MockWebServer 下载/原子重命名/签名校验整条链路；
  另覆盖 404 失败终态与失败后不残留安装包。
- 模拟器人工冒烟：首页、音乐库、榜单广场、设置；滚动过顶栏时可见背景模糊，标题
  清晰、无崩溃。

## 已知边界

- 更新元数据依赖公开的 GitHub Releases API。仓库处于私有状态时，未认证请求
  返回 404；应用会明确显示“仓库可能尚未公开”。仓库公开且带正式 Release 后，
  自动与手动检测才可用。
- 真机 PLK110 的顶栏滚动 A/B、Jank 门槛与签名一致的覆盖安装需在发布密钥环境里
  人工复验；本机没有连接真机。
