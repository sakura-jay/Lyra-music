# 首帧 jank 定位与收口（前置片）

**Blocked by**: 无（本片必须最先做）

## 目标

`v0.9.0` 收口后实测到的 **4.11% 首帧合成 jank** 必须在本版开工时先处理掉。理由很直接：
转场首帧是本次改动的主战场，如果底噪里本来就压着 4.11%，之后任何一个新转场都会被它污染，
量出来的数没法归因。

## 涉及层

- [ ] 采集：`dumpsys gfxinfo framestats` 的新指标（不是 legacy 那一列），配合 Perfetto 抓一条
  转场 + 一次滚动。两边的口径要对齐，否则"修好了"没有证据。
- [ ] 定位：优先怀疑首帧的**同步重组**（进页面那一刻把整页内容一次组合出来，包括图片解码与
  列表首屏），以及**数据订阅时机**（ViewModel 在组合期就发起首屏请求，响应回来又砸在同一帧）。
- [ ] 收口：按定位结论二选一或都做——抑制转场期间的重组（内容先挂起、转场结束后再组合），
  或把内容订阅推迟到转场结束（`LaunchedEffect` + 转场完成信号）。
- [ ] 回归：改完必须复测**同一场景同一口径**，并把 before / after 两个数写进 `CHANGELOG.md`。

## 允许的三种结局

1. 抑制转场期间重组，指标落进门槛；
2. 内容延迟订阅，指标落进门槛；
3. **结论是"120Hz 首帧不可达标"**——那就把门槛改成"60Hz 稳态 < 1% 且转场不掉出 120Hz"。

第 3 条是允许的，但**必须在 `CHANGELOG.md` 记下这个决策与理由，不得静默放宽**。改门槛和修代码
的区别只在于诚实与否：门槛是给自己定的，不写下来就等于没有门槛。

## 验收标准

- 转场首帧的合成 jank 有新指标的口径数据；改动前后各一份。
- 若走第 3 条，`CHANGELOG.md` 里有这条决策与它的理由、以及"在什么条件下重新收紧"。
- 本片不改任何用户可见行为；若确实需要改行为（例如延后内容可见），在工单与 `CHANGELOG.md` 里点名。

## 采集口径（已在模拟器上干跑验证命令，**模拟器的数不可用**）

PLK110 上两个场景各跑一套，口径必须完全一致。`framestats` 里要读的新指标是
`Janky frames` 与 `Number Frame deadline missed`——它们和 `Janky frames (legacy)` /
`Number Frame deadline missed (legacy)` 并列输出，两套算法不一样，**别读错列**。

两条干跑时踩到的坑，必须先处理，否则量出来的尽是假数：

1. **等窗口画出第一帧再 `reset`。** 窗口还没 attach 时，`dumpsys gfxinfo` 不但是
   `Total frames rendered: 0`（直方图全零、百分位印成 4950ms 这种"最大桶"值），
   应用侧还会在 `WindowManagerGlobal.dumpGfxInfo` 抛
   `NullPointerException: View$AttachInfo.mThreadedRenderer`。做法：启动后轮询
   `Total frames rendered` 直到 > 0，再 reset 开始计数。
2. **冷启动的数不算。** 干跑时 debug 包在模拟器上冷启动到首帧花了一分钟（逐类校验，
   logcat 里全是 `Verification of ... took 100–500ms`，还有 `Skipped 92 frames`）——
   这些都是构建形态的噪声，与动效无关。真机上同样要先手动走一次冷启动，再开始计数。

```powershell
adb shell am force-stop io.github.sakurajay.lyra
adb shell am start -n io.github.sakurajay.lyra/.MainActivity
# 轮询到 Total frames rendered > 0（真机上通常几秒），然后开始计数：
adb shell dumpsys gfxinfo io.github.sakurajay.lyra reset
# 只做被测的那一个场景，别的操作一概不做
# 场景 A（转场首帧）：从列表点进详情，退出，重复 5 次
# 场景 B（滚动）    ：同一条列表连划 3 次
adb shell dumpsys gfxinfo io.github.sakurajay.lyra framestats > before-<场景>.txt
```

读四个数：`Total frames rendered`、`Janky frames (x.xx%)`、`Number Frame deadline missed`、
50/90/95/99th 百分位，连同 `%` 一起抄进 `CHANGELOG.md`。改动后同口径再取一份 after。

> 干跑（模拟器、debug 包）结果是 8 帧里 8 帧 janky、中位 500ms——**这组数只能证明命令
> 跑得通，不能当基线**：它量的是不合格的构建形态，不是动效。

## 实测结果（PLK110，release 包，2026-10-18）

被测包 = `dev/v0.9.2` 的 `assembleRelease`（M9 全部改动，**不含**本片可能的修复）。
签名与机上原有的 0.9.0 release 包一致（`CN=Lyra local verification`），所以是 `install -r`
覆盖安装、用户数据保留。**必须用 release 量**：debug 包的逐类校验噪声会把结论整个带走。

### 场景 A：12 次 Tab 切换（转场首帧）

冷启动 → 等 12s → 预热 3 次切换 → `reset` → 12 次点击（间隔 1200ms）。
原始输出存在 `build/jank-before-transition.txt`。

| 指标 | 值 |
| --- | --- |
| Total frames rendered | 675 |
| **Janky frames（新指标）** | **14（2.07%）** |
| Janky frames (legacy) | 30（4.44%） |
| Number Frame deadline missed | 14 |
| 50 / 90 / 95 / 99th 百分位 | 5 / 7 / 9 / 21 ms |
| Number Slow UI thread | 13 |

`v0.9.0` 那条 4.11% 基线来自同一场景的 682 帧，对应的是 **legacy 那一列**；
同口径现在是 4.44%（新指标 2.07%）。两数在噪声范围内，也就是说
**M9 的十二处微交互没有把转场 jank 推高**。

### 场景 B：榜单广场连甩（滚动）

12 下 + 12 上（`input swipe 636 2050 636 500 200`，无间歇）。
原始输出存在 `build/jank-before-scroll.txt`。

| 指标 | 值 |
| --- | --- |
| Total frames rendered | 865 |
| **Janky frames（新指标）** | **0（0.00%）** |
| 50 / 90 / 95 / 99th 百分位 | 5 / 5 / 5 / 6 ms |

**滚动这一半的硬门槛本来就过**，而且是零帧 jank。所以本片的战场只剩转场。

### 定位：慢帧不是我们的组合/布局成本

逐帧拆 `framestats`（PLK110 的 `presDeadline` 是 11.33ms，帧周期 8.33ms @120Hz）：

- 正常帧墙钟 **4.71ms**，UI 线程在 `PerformTraversalsStart → DrawStart` 之间只花 **~1ms**。
- 慢帧有两种签名，且都不在 UI 线程的遍历期里：
  1. `PerformTraversalsStart - IntendedVsync` = **4–9.4ms** —— 帧**开始得晚**，
     遍历还没起跑，预算已经被吃掉一半以上；
  2. `FrameCompleted - SwapBuffers` = **8.4–11.8ms** —— 我们已经画完提交，
     合成侧滑过了下一个 vsync。
- 帧间隔 p50 = 8.31ms（120Hz 稳住了），其中 6 个 >20ms 的间隔就是掉帧点。

这条结论直接决定了结局怎么选：**① 抑制转场期间的重组、② 内容延迟订阅，治的都是
"首帧太重"**。而量出来首帧不重（1ms 遍历 / 4.71ms 总墙钟），重的是"起跑晚 + 提交后滑走"，
那两件事在 surface flinger 与调度器那一侧。把内容订阅推迟，只是让那 1ms 换个时间花，
慢帧的两种签名一个都不会消失。

## 结论：走允许的第 3 种结局

**120Hz 下的转场首帧 <1% 不可达标**，且不可达标的原因不是本应用的代码成本。
门槛改为：**滚动 <1%（已达标，实测 0.00%）+ 转场维持 60Hz 稳态 <1% 且不掉出 120Hz**。
决策与理由记在 [`../CHANGELOG.md`](../CHANGELOG.md)，没有静默放宽。

重新收紧的条件（任一成立就要回来重量）：

1. 换图片库或改封面解码路径（`ArtworkLoader` 的 32 格位图缓存装不下 36 张榜封面，
   `v0.9.1` 审查里记过这条）——那会改变"提交后滑走"这一类的分布；
2. M10 的共享元素转场落地之后——overlay 形变会引入新的合成工作，
   本片量的是"只有路由转场"的底噪，不能替 M10 背书；
3. 系统侧升级（Android / OEM ROM 的 vsync 与刷新率策略）。

## 未做完的部分

- **三个对照组（X：同页点击 / Y：首页↔音乐库 / Z：首页↔搜索）没跑成**——量到一半设备
  进了安全锁屏，之后掉出 adb。这三个数是用来把"转场机制的成本"和"内容/图片的成本"分开的，
  缺了它们，"慢帧两种签名都不归我们管"这一条只有帧时间戳的证据、没有对照组的证据。
  脚本已经收进 [`../../../../scripts/measure-motion.ps1`](../../../../scripts/measure-motion.ps1)
  （`-Mode Probe`），下次真机在手直接跑。
- Perfetto 那条 trace 同样没抓（同一原因）。
- 本片**不改代码**：定位结论是"没有可收的口"，所以 `LyraApp` / `LyraMotion` 一行未动。

### 补：对照组在模拟器上跑通了（结论不变，但候选 ① 被证伪）

真机那三个对照组没跑成，改用 Pixel_8 模拟器做**相对**对照（绝对值不可比：模拟器 60Hz、
无真机 GPU；这里只问"有错峰揭示的页 vs 没有的页，差多少帧"）。
`lyraReveal` 只挂在首页/搜索/榜详情，音乐库没有，所以这两页正好是一组对照：

| 窗口 | 切到首页（`lyraReveal` 两排） | 切到音乐库（无揭示） |
| --- | --- | --- |
| 700ms | 48 / 48 / 46 | 41 / 46 / 46 |
| 1500ms | 50 / 50 / 51 | 49 / 50 / 50 |
| 3000ms | 50 / 51 / 50 | 47 / 50 / 51 |

**两页在每个窗口上都一样**，也就是错峰揭示（每次回首页都会重播，因为 `revealed`
用的是普通 `remember`）成本量不出来。这一条把候选 **①「抑制转场期间的重组」证伪**：
被怀疑的那段动画本身不花钱，与真机上"UI 线程遍历期只有 ~1ms"是同一个结论的两种量法。

顺带量到一件与 jank 无关但值得记的事：**静止时一帧都不画**（`reset` 后静置 1500ms = 0 帧），
所以"点一下之后数帧"是动画时长的有效代理量——这个量法已经写进
[`../../../../scripts/measure-transition-duration.ps1`](../../../../scripts/measure-transition-duration.ps1)。
