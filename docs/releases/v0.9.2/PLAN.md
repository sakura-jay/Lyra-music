# Lyra M9 = v0.9.2 计划（动效语言与微交互）

> 本文件是本版的**规格说明书与切片计划**。结果记录写在同目录 `CHANGELOG.md`
> （决策 1–8、验证、真机验收补录、未完成）；下面第 5 节的状态列已按那一轮的结果订正。
> 术语以 `CONTEXT.md` 为准（本版新增：动效层级 / 动效缩放 / 共享元素）；决策见
> [`../../adr/0009-动效语言自建缩放与层级表.md`](../../adr/0009-动效语言自建缩放与层级表.md)。
> 每片的实现提示词（可直接复制给其他 AI 的英文版）在 [`PROMPTS.md`](PROMPTS.md)。

---

## 1. 本版是什么

M9 只做两件事：**建动效地基**、**给已有的交互补质感**。因此本版

- **不新增任何用户可见功能**；
- **不动** Shapes / Typography / 任何布局尺寸；
- **不推翻** 已验收的三轮真机转场成果（播放页 280/240、`BlurBackdrop` 450）。

换句话说：这一版之后，用户不会看到新页面、新按钮、新数据，只会觉得"这个应用变顺了、有回应了"。
播放体验的连续性（共享元素封面、pager 视差、seek 惯性）留给 M10 = `v0.9.3`，见
[`../v0.9.3/PLAN.md`](../v0.9.3/PLAN.md)。

## 2. 动效地基（ADR-0009）

四件已经在代码里落地的事：

1. **自建动效缩放**：读 `Settings.Global` 的 `ANIMATOR_DURATION_SCALE` 与
   `TRANSITION_ANIMATION_SCALE`，取较小值并夹紧到 `[0,1]`，经 `MotionScaleProvider` 挂成
   `LocalMotionScale`。`0` 时全部 spec 走 `snap()`（不是"很短的 tween"）。
   **计划原文说"Compose 的 `AnimationSpec` 不会自动跟随系统缩放"——这条实测是错的**：平台一直在按
   `animator_duration_scale` 缩放所有 spec 动画（含 spring）。所以 `LocalMotionScale` 的语义是
   "该写进 spec 的乘数"= 想要的倍率 ÷ 平台那份，见 ADR-0009 与 CHANGELOG 的"决策 1"。
2. **四层时长表 + 一档路由**：micro 100/120、small 150/200、medium 250/300、large 350/450；
   路由转场保留现值 280 入 / 240 出与既有 spring。所有基准值乘 `LocalMotionScale`。
3. **两条硬护栏**：① 全屏路由位移禁用会过冲的 spatial spec，过冲只给小元素；② 不动画任何参与
   布局的属性（`size`/`width`/`height`/`padding`/`verticalArrangement`），只走 `graphicsLayer`。
4. **转场注册去重**：`LyraApp.kt` 的 13 份复制收成 `lyraDetailRoute` + `LyraRoutes.RouteKind`
   一处，行为不变，播放页独立注册。

两条与计划原文不同的地方，按实测修正并记录在此：

- 计划原写"`LyraTheme` 改调 `MaterialTheme(colorScheme, MotionScheme.Expressive, …)`"——
  本版锁的 material3 1.4.0 里 `MotionScheme` 与那个重载都是 `internal`（`javap` 实测），
  挂不上。弹簧常量改为从 `ExpressiveMotionTokens` 抄进 `LyraMotion`，并在注释里标明
  工具链升级后的唯一替换点。
- **平台确实已经替我们乘过一次**（初稿列为"已知风险"，本版量实并修掉）：它只读
  `animator_duration_scale` 一档、且不夹紧上限。本层补的正是这两件事——同时读
  `transition_animation_scale`、夹紧到 `[0,1]`，并把平台那一份除掉。
  证据是反编译 + 实测（`animator` 1.0 → 950ms、2.0 → 1750ms，而我们的夹住在 2.0 必然输出 1.0）。
  默认档 `animator = 1.0` 时乘数是 1.0，所有动画与改动前一字不差。

## 3. 十种高级交互模式的判定

本版按"先判断落点、再选模式"的路子过了一遍十个模式，结论是**只有三个真正匹配**，其余七个
判定为"没有匹配模式 → 普通实现"，不强行套用：

| # | 模式 | 判定 | 落在哪 |
| --- | --- | --- | --- |
| 1 | Radial Theme Transition｜圆形主题切换 | **部分采用（降级形态）** | 主题涟漪覆盖层：从点击点扩散新主色再淡掉，不做双主题树、不做快照裁切 |
| 2 | Drag-to-Reorder｜拖拽排序 | 无匹配 | 队列 UI 本版不做，列表也没有拖拽需求 |
| 3 | Staggered Bulk Selection｜批量勾选 | **只借机制（辅助）** | 借"错峰"这一半：`lyraReveal` 的 40ms 步进、240ms 上限 |
| 4 | Velocity-Based Slider Snap｜滑杆惯性吸附 | 无匹配（留 M10） | 本版无滑杆落点；M10 的 seek 惯性吸附才是它 |
| 5 | Animated Text Disclosure｜文本展开 | 无匹配 | 唯一落点是 `TrackListScreen` 的 `maxLines = 3`，不值得为它引入一套展开机制 |
| 6 | Spring Stepper Progress｜步骤条回弹 | 无匹配 | 本应用没有步骤条 |
| 7 | Ripple Feedback for Related Switches｜开关联动涟漪 | 无匹配（明确不套） | ♥ 变化时**相邻行与卡片不参与**：把局部状态说成全局事件是错的 |
| 8 | Curved Card Deletion｜卡片曲线删除 | 无匹配 | 没有"删除去向"的回收区域，时序也与模式相反（数据先删、视觉后收） |
| 9 | Stacked Card Scroll｜卡片堆叠滚动 | 无匹配 | 队列 UI 不做，无落点 |
| 10 | Expanding Tag Selection｜标签挤开 | **主交互** | 页码指示器：当前点 7dp 圆 → 18dp 药丸，其余点让位 |

## 4. 微交互清单

每一项都写清状态定义；括号里是它借用的模式（没有就写"无匹配"）。

- **按压形变**（无匹配，`lyraPressable` / `lyraPressScale`）：起 1.00，按下 0.98（行 / 卡片）
  或 0.94（图标 / 点），100ms 无过冲；抬起 120ms 回 1.00。ripple 沿用 `LocalIndication`，
  不重复实现。落点：`TrackRow`、`PlaylistCard`、`LibraryEntryCard`、`SourceCard`、`MiniPlayerBar`。
- **♥ 收藏形变**（无匹配，`FavoriteHeartButton`）：`border → filled` 交叉淡出（small 档）
  + 0.9 → 1.0 弹性放大（`DampingRatioMediumBouncy`，380 刚度），点击点带一层 ripple。
  **真实状态在点击瞬间写库**，视觉只表达过渡。四处共用：播放页顶栏、曲目行尾、歌单封面角、
  歌单详情页顶栏。`SavedPlaylistsScreen` 那行的心**不换**（它只有"已收藏"一态，没有可过渡的
  另一个状态，换成形变组件只会多一层没意义的交叉淡出）。
- **播放 / 暂停**（无匹配，`PlayPauseButton`）：`AnimatedContent` 图标纵向 slide-through
  （新图标从下方进、旧的从上方出，micro 档）+ 按钮 0.92 → 1.00 的 one-shot pop。
  **决定不加常驻呼吸 / 进度环**：常驻动画每帧自绘，`CHANGELOG.md` 已实测它把刷新率从 60 拉到
  120，与歌词扫光叠加会污染 Jank 预算，换来的只是"这个按钮在闪"。
- **页码指示器**（主交互 = Expanding Tag Selection，模式 10）：当前点由 7dp 圆变 18dp 药丸，
  用 `scaleX`（0.39 → 1.0）而不是动画 `size()`；其余点 `translationX` 让位（≤ 5.5dp），
  邻居不重叠、不跳位。由 `PagerState.currentPage` 驱动；`currentPageOffsetFraction` 本版不接
  （留给 M10 的页码跟手）。
- **列表增删让位**（模式 8 明确不适用，`lyraAnimatedItem`）：用于我喜欢、我的歌单、收藏的歌单、
  音源页（本地数据、key 唯一）；搜索分组也在内。远端搜索结果与榜 / 专辑列表**只让位不传 key**。
  **时序与模式 8 相反**：数据先删、视觉后收（`FavoritesRepository.toggle` 不等动画），
  因为没有"删除去向"的回收区域。
- **Snackbar 过渡**（无匹配，**偏差**）：material3 1.4.0 的 `SnackbarHost` 只有
  `hostState` / `modifier` / `snackbar` 三个参数，过渡**不可参数化**，所以本版沿用 material3
  自带的 fade + scale 进出，没有改成 medium 档的 fade + slide。它的时长也不跟随自建缩放。
  为它自己重写一个 `SnackbarHost` 不划算，记为已知缺口。
- **加载揭示错峰**（辅助 = 模式 3 的错峰机制）：`lyraReveal(index)`，step 40ms、
  总上限 `min(index × 40, 240)ms`；落点：`PanelHint`、首页两排、榜详情头部与页脚、搜索分组。
  只动 `alpha` 与 `translationY`（8dp 抬升），**不阻塞数据可见**，也不推列表的滚动锚点。
- **主题切换涟漪**（模式 1 的降级形态，`ThemeRippleOverlay`）：新 `primary` 色圆盘从色板点击坐标
  扩散，半径 = 点到覆盖层四角的**最远距离**，250ms（medium 档），alpha 0.35 → 0，纯覆盖层反馈。
  **真实配色在 t = 0 就生效**；不做双主题树、不做 `PixelCopy` 快照裁切——主题只换色不换布局，
  快照裁切的收益不抵它在首帧上加的工作量。色盘的涟漪色取当前预设的 `primary`（明暗由
  `surface.luminance()` 判）。
- **Stretch（本版不做，M9 提前收口才补）**：播放模式切换标签交替、FilterChip 选中态过渡、
  简介文本折叠展开、NavigationBar indicator 弹性。

## 5. 切片

| 片 | 内容 | 取证 | 状态 |
| --- | --- | --- | --- |
| 001 | 首帧 jank 定位与收口（**前置**） | `gfxinfo framestats` 新指标 + Perfetto | 收口=结局 ③（改门槛），真机对照组 X/Y/Z/A/B 已补 |
| 002 | 动效地基：MotionScale + 四层表 + `ADR-0009` | JVM 单测 + 模拟器 + 真机三档缩放 | 完成，**但真机只跑了 1.0 一档**（三档复核在「未完成」） |
| 003 | 导航转场去重（`lyraDetailRoute` + `RouteKind`） | 单测覆盖 13 条路由 + `gfxinfo` | 完成（分类表有 JVM 断言；转场首帧走的是 001 那两轮真机场景 A，去重之后 1.92%，**没有单独为 003 量过一次**） |
| 004 | 按压形变与反馈统一 | 截图 + 真机目视 | 完成（真机**量过**：0.979、下 100ms / 上 92–120ms） |
| 005 | ♥ 与播放 / 暂停状态形变 | 录屏看中间帧 + 真机目视 | **一半**：♥ 真机录屏看到中间帧；播放 / 暂停只有模拟器像素测，真机未录 |
| 006 | 页码指示器弹簧 | 截图 + 真机目视 | 完成（真机 12 帧竖条，身份在中点交换） |
| 007 | 列表增删让位 `lyraAnimatedItem` | ui-test + 真机目视 | 完成（真机 21 个中间值、266ms、无过冲） |
| 008 | Snackbar（沿用）与加载揭示错峰 | 截图 + 真机目视 | 完成（真机进 116 / 出 117ms；详情页那 28px 上移**未定位**） |
| 009 | 主题切换涟漪覆盖层 | 真机录屏（前后帧截图判不了扩散） | 完成（真机录屏：单圆盘、真实配色 t=0 生效、连点在新点重启） |

> 状态列在真机验收那一轮之后改写过一次，逐条数字与判据强度见
> [`CHANGELOG.md`](CHANGELOG.md) 的「真机验收补录」表——**"量过"与"只读了源码"分得很清**。
> 两处**没做到**的：002 的三档缩放真机复核（只跑了 1.0）、005 的播放 / 暂停真机录屏。

001 是前置片：先把 4.11% 的首帧合成 jank 定位并收口，否则任何新转场都会被它污染。
它允许三种结局——抑制转场期间重组、内容延迟订阅、或结论为"120Hz 首帧不可达标"并把门槛改为
"60Hz 稳态 < 1% 且转场不掉出 120Hz"。**改门槛必须在 `CHANGELOG.md` 记决策，不得静默放宽。**

## 6. 完成长什么样

- `:app:compileDebugKotlin` + `:app:compileDebugAndroidTestKotlin` + JVM 单测（80 条）绿。
- 模拟器（Pixel_8 / Android 17）既有 instrumented 全量跑通；本版新增
  `MotionScaleUiTest` 6 条，`tests=6 / failures=0`。
- `:app:lintDebug` 0 error。
- **Jank 硬门槛 < 1% 只限两个场景**：列表 / 广场滚动、转场首帧。微交互（按压、勾选、图标切换）
  **不参与** Jank 考核，但受两条硬护栏约束。
- 手动：模拟器逐屏截图（首页两排、榜广场 / 详情、我喜欢、播放页两页、设置色板、主题切换前后）；
  PLK110 真机每片目视一次；005 / 009 / M10-010 强制录屏（中间帧截图取不到，
  `CHANGELOG.md:635` 同因）。
- 真机复核系统动画缩放三档（0 / 0.5 / 2.0）下的实际观感，见 ADR-0009 的"已知风险"。

## 7. 明确不做

本版**没有**为用户新增任何功能，以下五项更是明确划到本期之外：

- 播放队列 UI；
- 手势拖拽展开 / 收起播放页；
- 预测式返回转场（`enableOnBackInvokedCallback` 保持现状；navigation-compose 2.9.6 无相关 API，已核实）；
- 滑动删除 / 拖拽排序；
- 逐字歌词渐变软边。

另外两条结构性的"不做"：

- **队列 UI 仍不做**，所以卡片堆叠滚动与拖拽排序两种模式在当前项目里**无落点**，本版不为其预留接口；
- **不引 Robolectric**：本版新增的断言都落在纯 JVM 纯函数与 Compose 仪器测试两侧，中间那层不需要。

## 8. 版本号

`0.9.2` / `versionCode 7`，分支 `dev/v0.9.2`，收口时整体 squash 合回 `main` 留一条记录。
一期仍是"一里程碑一 minor"，`1.0` 留给终点里程碑。
