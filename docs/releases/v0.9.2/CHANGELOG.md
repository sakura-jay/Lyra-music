# v0.9.2 变更记录（M9 动效语言与微交互）

本版**不新增任何用户可见功能**：建动效地基（层级表、动效缩放、转场注册去重）+ 给已有交互补质感
（按压形变、♥ 与播放/暂停状态形变、页码指示器、列表让位、错峰揭示、主题涟漪）。
规格与切片见 [`PLAN.md`](PLAN.md)，决策见
[`../../adr/0009-动效语言自建缩放与层级表.md`](../../adr/0009-动效语言自建缩放与层级表.md)。

状态：**代码与文档已收口，真机逐片目视未完成**（设备中途掉出 adb）。分支 `dev/v0.9.2`，
版本 `0.9.2` / `versionCode 7`。

## 摘要（面向用户）

- 列表行、卡片、入口卡、迷你播放条按下去会**自己缩一下**（2% / 6%），不再是只有涟漪。
- ♥ 收藏与播放/暂停有了形变过程（交叉淡出 + 弹性放大），不再是瞬间换图。
- 播放页两页之间的页码指示器：当前点用 `scaleX` 从圆长成药丸，邻居让位而不是跳位。
- 我喜欢、我的歌单、收藏的歌单、音源页的增删有让位动画。
- 首页两排、榜单详情头脚、搜索分组按 40ms 依次出现（总上限 240ms）。
- 换配色时新主色从色板点击点扩散一圈（纯反馈覆盖层，真实配色当帧就生效）。
- **系统的"动画时长缩放"第一次真正生效**：调到 0 时全部瞬时到位，而不是"很慢地播完"。

## 决策 1：动效缩放必须除掉平台那一份（双重缩放）

`ADR-0009` 把"平台可能已经自己乘过一次"列为**已知风险**，并写明落点是
`MotionSpec.durationMsOf` 一处。本版把它量实了，并修掉。

**证据（字节码 + 实测）**

- 反编译 `androidx.compose.animation:animation-core` 1.11.4：
  `SuspendAnimationKt.doAnimationFrameWithScale` 里
  `playTimeNanos = if (durationScale == 0f) anim.durationNanos else (frameTimeNanos - startTimeNanos) / durationScale`，
  而 `durationScale = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f`。
  这是 **tween 与 spring 共用**的那条帧循环，所以两类动画都会被缩放。
- 反编译 `androidx.compose.ui:ui` 1.11.4：`WindowRecomposer_androidKt.getAnimationScaleFlowFor`
  读 `Settings.Global.animator_duration_scale` 并注册 `ContentObserver`，喂给
  `MotionDurationScaleImpl`。也就是说**平台确实乘过**。
- 实测（Pixel_8 模拟器上跑真应用窗口，同一动作"切页后持续渲染时长"，各 3 次）：
  `animator_duration_scale` 1.0 → 980 / 963 / 928ms，2.0 → 1787 / 1751 / 1738ms。
  我们的 `clamp` 在 2.0 时**必然**输出 1.0（夹紧上限），所以那 ~1.85 倍只可能来自平台——
  正是"不放大"这条表态被反着执行。

  为什么这条**可以**用模拟器量：它量的是"改一个系统设置，动画墙钟怎么变"，走的是真应用的
  真窗口 recomposer，与真机是同一条链；仪器测试量不到是因为测试框架自带 recomposer 与时钟。
  反过来，**jank 的绝对值仍然只认真机**（模拟器 60Hz、无真机 GPU），下面那两张表的数都来自 PLK110。

**改法**

`LocalMotionScale` 的语义从"用户想要的倍率"改成"**该写进 spec 的乘数**"：

```kotlin
multiplier = clamp(animator, transition) / platformFactor(animator)   // 即 intent / animator
```

除掉再让平台乘回来，墙钟时长恰好等于 `基准 × intent`。弹簧没有时长可乘，用等价的刚度换算
`stiffness ×= 1 / multiplier²`（周期 ∝ `1/√stiffness`），与 tween 走同一个等式。

平台真正缺的只有两件事，而这两件才是本层要补的：**它只读 animator 一档**
（`transition_animation_scale` 完全不理）、**它不夹紧上限**。

**默认档零风险**：`animator = 1.0` 时 `multiplier = 1.0`、刚度倍率 = 1，
所有动画与本次改动之前**一字不差**。只有用户动过系统缩放才有差别。

**改后复测（同一隔离场景：音乐库 → 我喜欢，只有一条路由转场在动，1400ms 窗口内数帧）**

| `animator` | 想要的倍率 | 乘进 spec 的数 | 预测墙钟 | 实测帧数（4 次） |
| --- | --- | --- | --- | --- |
| 1.0 | 1.0 | 1.0 | 1.0× | 42 / 43 / 44 / 43 |
| **2.0** | 1.0（夹住） | **0.5** | 0.5 × 2.0 = **1.0×** | **44 / 40 / 43 / 44** |
| **0.5** | 0.5 | 1.0 | **0.5×** | **25 / 22 / 24 / 25** |
| 1.0（回到起点复量） | 1.0 | 1.0 | 1.0× | 41 / 44 / 44 / 43 |

三档全部落在预测上：`2.0` 与 `1.0` **完全一样**（改之前是 1.85 倍长），`0.5` 是半速
（24 ≈ 43/2，多出的两三帧是点击与首帧的固定开销）。

这条复测必须在**真应用窗口**上做，不能在仪器测试里做：只有真窗口的 recomposer 才带着
平台的 `MotionDurationScale`。数帧之所以够用，是因为静止时应用一帧都不画。

**已知残余**：`animateItem()` 内部那两条 spring 由 foundation 自己构造，我们改不到刚度，
所以它在 `animator = 2.0` 时仍会被平台放大到 2 倍。`lyraAnimatedItem` 只保住了
"关掉动画时整条不播"。要根治得给 `animateItem` 传显式 spec，本版不做。

## 决策 2：Jank 门槛按场景拆开，转场首帧改为"60Hz 稳态"口径

工单 001 允许三种结局，实测后走**第 3 种**，理由与数据都在
[`tickets/001-首帧-jank-定位与收口.md`](tickets/001-首帧-jank-定位与收口.md)。这里只记决策本身。

**实测（PLK110，release 包，M9 全部改动、不含 001 的修复）**

| 场景 | 帧数 | Janky（新指标） | legacy | p50/p90/p95/p99 |
| --- | --- | --- | --- | --- |
| A：12 次 Tab 切换 | 675 | **14（2.07%）** | 30（4.44%） | 5 / 7 / 9 / 21 ms |
| B：榜单广场连甩 24 次 | 865 | **0（0.00%）** | 0（0.00%） | 5 / 5 / 5 / 6 ms |

滚动这一半的 <1% **本来就达标**，而且是零帧。转场这一半 2.07%。

**为什么判定"120Hz 转场首帧不可达标"，而不是继续找代码的锅**

逐帧拆 `framestats`：正常帧墙钟 4.71ms，其中 UI 线程在 `PerformTraversalsStart → DrawStart`
之间只花 **~1ms**。慢帧的两种签名都不在遍历期里：

1. `PerformTraversalsStart - IntendedVsync` = 4–9.4ms —— 帧**起跑就晚**，预算在开工前已被吃掉；
2. `FrameCompleted - SwapBuffers` = 8.4–11.8ms —— 画完提交了，合成侧滑过一个 vsync。

允许结局里的 ①「抑制转场期间的重组」和 ②「内容延迟订阅」治的都是"首帧太重"。
而首帧不重（1ms），所以两条都无从下手。**①已经在模拟器上用对照组证伪**：
有错峰揭示的首页与没有的音乐库，700 / 1500 / 3000ms 三个窗口下帧数完全一样。

**新门槛**

- 列表/广场滚动：`Janky frames < 1%`（维持，已达标）。
- 转场：`60Hz 稳态 < 1%` **且** 不掉出 120Hz（帧间隔 p50 8.31ms 即视为稳住 120Hz）。

**重新收紧的条件**（任一成立就回来重量）：① 换图片库或改封面解码路径；
② M10 共享元素转场落地之后；③ 系统侧 vsync / 刷新率策略变化。

## 验证

- JVM 单测 84 条全绿（本版新增：`MotionScaleTest` 11 条，含双重缩放的墙钟等式断言）。
- `:app:lintDebug` 0 error。
- instrumented **135 条 `OK`** 在 `emulator-5554` 全绿（`Time: 60.5s`）。
  跑法是 `adb -s emulator-5554 shell am instrument -w`，**不用** `connectedDebugAndroidTest`
  ——后者会对所有已连接设备执行，而真机上一旦跑起那几条会清库的测试就是生产数据事故。
- 采集脚本收进仓库：`scripts/measure-motion.ps1`（jank 两场景 + 三个对照组）、
  `scripts/measure-transition-duration.ps1`（用帧计数量动画时长）。

## 未完成

- 真机逐片目视（工单 004–009）与 005 / 009 的强制录屏：设备中途掉出 adb，未跑完。
- 工单 001 的三个真机对照组与 Perfetto trace：同一原因。
- 决策 1 的三档复核（0 / 0.5 / 2.0）在真机上目视确认：数学与字节码都成立，
  但"观感对不对"仍然只有真机说了才算。
