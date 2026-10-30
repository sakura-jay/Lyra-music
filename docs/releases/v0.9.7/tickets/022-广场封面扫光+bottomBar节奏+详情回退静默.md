# 022 广场封面扫光 + bottomBar 节奏 + 详情回退静默

## 问题（真机反馈）

1. **榜单广场 / 歌单广场的封面加载没有扫光**：021 的页级 `ProvideSkeletonShimmer`
   只包住了"整页还没数据"的骨架分支。广场页有数据后封面按需懒取，卡片里的
   `LyraSkeletonShape` 读不到页级相位——provider 没覆盖 → 静态灰块。
2. **音乐库二级页返回"慢半拍"**：bottomBar（mini 条 + 导航栏）挂在 `Scaffold`
   的插槽上，`if (currentRoute != PLAYER)` 直接出现/消失，没有转场。返回时条
   先"蹦"出来、内容还在滑，两段节奏对不上。
3. **退出二级界面仍播主页面切换动画**：Tab 注册处已有
   `isDetail(initialState) → EnterTransition.None`，但详情页统一注册
   （`lyraDetailRoute`）没有——歌单广场 → 具体歌单 → 返回时，下层广场页重播
   滑动入场。

## 修法

- `LyraSkeletonShape`：页级 provider 没覆盖时退化为**单块自扫**
  （`rememberLocalShimmerPhase`），同一帧组合出的卡相位天然同步，图一到位
  块离场即释放；系统动画关闭仍是静态灰块。页级 provider 的行为不变，
  详情页骨架区照旧共享一道光。
- `LyraApp`：bottomBar 包进 `AnimatedVisibility`（淡入淡出 + 轻微上下位移，
  140ms），可见性判断收进 `BottomBarWithTransitions`；详情页只剩播放条时
  改用 `navigationBarsPadding()`（语义与原 `windowInsetsPadding(WindowInsets
  .navigationBars)` 等价）。
- `lyraDetailRoute` 的 `popEnterTransition` 补上与 Tab 注册处一致的判断：
  从详情页退回本页（下层也是详情页）时 `EnterTransition.None`。

## 验收

- `:app:compileDebugKotlin` + `:app:testDebugUnitTest` 绿。
- 真机：弱网进两个广场页，封面在途时出现扫光（同一批卡同步扫，先到图先停）；
  音乐库二级页返回时 mini 条随页面 pop 同步滑出；歌单广场 → 具体歌单 → 返回，
  广场页不再重播滑动。
