# v0.9.7 变更记录（返回路径收口 + 骨架同构 + 转场统一）

三片：[PLAN.md](PLAN.md) 定计划，[tickets/](tickets/) 逐条取证。

- **021 快速返回鬼影 + 骨架整行**：`slideUnderDetail` 的 60ms 底页淡出与详情页
  popExit 叠加，快速返回时底页整段停在半透明（视频 f158–f160）。本版把底页
  退回 `ExitTransition.None`，入场淡入只对 push 侧可见性负责。骨架行从"序号条 +
  两行字 + 右侧点"三块拼盘收敛为一整行一块（内容高 38dp + 上下 6dp，与
  `TrackRow` 同高），shimmer 改为页级共享相位的同步扫光，扫光带宽度 = 块宽 1/3。
- **022 广场封面扫光 + bottomBar 节奏**：`rememberRemoteArtworkState` 暴露
  `loading`，卡片封面在途时给骨架而不是空块；页级 provider 没覆盖时退化为
  单块自扫。首页重载入口从顶栏改为下拉刷新，音乐库设置入口从顶栏挪进列表卡。
  播放页收起的位移/缩放收紧到 120ms，淡出仍走 160ms + 60ms。
- **023 Tab 切换与返回重做**：Tab 切换改为按序号定向滑入，被切走的旧 Tab 定住，
  白底不再露出；详情页返回改为整页向右划走（240ms，不带淡出），`popEnter`
  在任何来路下都不再播入场；bottomBar 包进 `AnimatedVisibility`（140ms 淡入 +
  1/3 高度位移）。**详情页不再显示迷你播放条**，条只属于三个 Tab 层。

## 验证

- JVM：146 tests，0 failed（`LyraSkeletonTest` 新增 `skeletonRowMatchesTrackRowGeometry`
  与 `shimmerBandScalesWithBlockWidthAndStaysOutsideAtEnds`）。
- 真机验收见各工单"验收"节。
