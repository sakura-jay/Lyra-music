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
- 第 5 项之后不再逐项等待（240ms 封顶能看到）。
- 触发一次提示（如移出我喜欢），列表不重排，提示条进出不打断滚动。
- `MotionScaleProvider(0f)` 下内容直接可见，没有"先隐身再出现"。
