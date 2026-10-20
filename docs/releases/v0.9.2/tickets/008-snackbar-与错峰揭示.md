# Snackbar 过渡（沿用）与加载揭示错峰

**Blocked by**: 002

## 目标

载入时的成组内容不要"整屏闪一下"；提示条进出不要重排列表。

## 涉及层

- [x] `ui/common/LyraReveal.kt`：`Modifier.lyraReveal(index)`。step **40ms**、
  总上限 **`min(index × 40, 240)ms`**；只动 `alpha` 与 `translationY`（**8dp** 抬升），
  全在 `graphicsLayer` 上。关掉动画时直接算"已揭示"（连一帧 `alpha = 0` 都不该有，
  否则"别动"这个表态仍会让内容先隐身再出现）。
- [x] 落点：`LyricPanel` 的 `PanelHint`、首页两排、榜详情头部与页脚、搜索分组。

这条取的是模式 3（Staggered Bulk Selection）的**错峰机制**那一半：本应用没有批量勾选这个交互，
所以主交互判定为"没有匹配模式"，只借用它的时序。

## 为什么必须有上限

没有上限时，榜单一屏二十来项，最后一项要等 800ms 才出现，用户只会以为卡住了。240ms 之后
后面的项同时开始。另外必须保证**错峰不阻塞数据可见**：元素从第一帧就在树里、数据已经可见，
错峰只是视觉上的先后，也不会推列表的滚动锚点（只动 alpha 与 translationY）。

## 偏差记录：Snackbar 没有自定义过渡

计划原写"Snackbar 进出走 medium 层级的 fade + slide，替换时就地交替不重排列表"。实测本版锁的
material3 1.4.0：

```java
public static final void SnackbarHost(SnackbarHostState, Modifier, Function3<SnackbarData, …>, Composer, int, int)
```

**只有三个参数**（`hostState` / `modifier` / `snackbar`），过渡不可参数化。它自带的是
fade + scale，并且**不跟随自建缩放**。为它自己重写一个 `SnackbarHost` 的成本不划算
（要接管进出场、队列、超时与手势），所以本版沿用 material3 自带过渡，记为已知缺口。

"替换时就地交替不重排列表"这一条本来就成立：`SnackbarHost` 渲染在迷你播放条正上方，
不参与列表布局，也不会被滚走（`MessageSnackbar.kt` 的注释里已经写着）。

## 验收标准

- 模拟器截图 + 真机目视：进搜索页 / 榜详情，成组内容依次出现而不是整屏一起闪。
  **真机看过**（PLK110 release，`build/walk/rev008*.mp4`、`snk008*.mp4`）。
  但要说清量到了什么：**错峰揭示本身（40ms step）在真机上没有被逐帧证实**——
  手机 `screenrecord` 动画期约 119fps，40ms 的间隔只有约 5 帧，
  而这几段录像的可用帧太少，只能看出"成组内容不是一起闪"，数不出 step。
  step 的数值由 JVM 单测 `staggerOffset(step = 40, cap = 240)` 的边界断言负责。
- 第 5 项之后不再逐项等待（240ms 封顶能看到）。
  同上：这是单测判的，真机没有数到第 5 项。
- 触发一次提示（如移出我喜欢），列表不重排，提示条进出不打断滚动。
  **首页成立、榜详情不成立**。首页量了：提示条在场期间列表行 `dy=0` 连续 84 帧。
  榜详情那一处提示条在场期间列表整体上移 28px（=8dp），离场后回落。
  成因没定位（同一轮里"点歌开始播放"也会改迷你条状态，两个变量没分开），
  所以只记现象。要定位得用一个既不切页也不起播的触发。
  另外提示条自身的进出量到 **进 116ms / 出 117ms**——那是 material3 自带的 fade+scale，
  **不是**本版的 medium 档（250/300ms）。工单原文"Snackbar 没有自定义过渡"这条偏差记录是对的，
  现在有了数。
- `MotionScaleProvider(0f)` 下内容直接可见，没有"先隐身再出现"。
  由 `MotionScaleUiTest` 判（注进去的是应用内部的 scale，与设备那一档无关，模拟器与真机等价）。
