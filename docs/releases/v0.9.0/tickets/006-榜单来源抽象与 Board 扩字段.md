# 榜单来源抽象 + Board 扩字段 + 酷我对齐

**Blocked by**: 001

## 目标
榜单从"单一酷我适配器"变成"可多来源"的形状；**酷我行为保持不变**（扩展式，不破坏首页）。补齐榜封面。

## 涉及层
- [ ] 数据层：`Board` 增 `source: String` 与 `coverUrl: String`（现定义在 `KuwoLeaderboardProvider.kt:19`）；新增榜单来源抽象（接口/目录：`source`、`boards`、`songs(board, page, limit)`）；`KuwoLeaderboardProvider` 实现之，并**实测**拿到榜封面 URL。
- [ ] 逻辑层：`ServiceLocator` 提供"全部榜单来源"；首页取数仍只走酷我（行为不变）。
- [ ] UI 层：无（本工单不改首页）。
- [ ] 测试：设备测试——酷我各榜仍能取到曲目 + 封面 URL 非空（对照 `KuwoLeaderboardProviderTest`）；纯 JVM——来源目录分组逻辑。

## 验收标准
- `Board` 带 `source` 与 `coverUrl`；酷我全部榜的封面 URL 实测非空（取不到则记录，并回退占位策略）。
- 榜单来源抽象可容纳 kw/kg/wy 三家；酷我现有行为**无回归**。
- 首页榜单展示与分页行为与改前一致。
- 端点/封面来源以真机实测为准（不照抄 lx），踩坑记入注释 / changelog。
