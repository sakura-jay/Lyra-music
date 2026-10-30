# CI

## 流水线

| 工作流 | 触发 | 内容 |
| --- | --- | --- |
| `CI`（`.github/workflows/ci.yml`） | push 到 `main`、所有 PR、手动 | 单元测试、Lint、Debug/Release 打包；上传测试报告、Lint 报告与 debug APK |
| `Instrumented tests`（`.github/workflows/instrumented.yml`） | 仅手动 | API 36 模拟器上跑 `connectedDebugAndroidTest` |

## 首次启用

1. 提交并 push `.github/workflows/` 到 `main`（或先推一个分支开 PR）。
2. 打开仓库 **Actions** 页，若出现启用提示，点击启用 workflows。
3. 进入 `CI` 工作流，手动 **Run workflow** 跑第一遍，确认 `Unit tests, lint & assemble` 为绿。
4. 需要阻止红灯合入时，在 **Settings → Rules / Branches** 添加规则，把 required status check 设为
   `CI / Unit tests, lint & assemble`。

私有仓库在免费计划下没有分支保护；升级到 Pro/Team 或把仓库公开后再加规则。Actions
在私有仓库消耗免费额度，本流水线一次约 4–8 分钟。

## 本地复现

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug assembleDebug assembleRelease
```

模拟器工作流本地等价于：

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

## 说明

- `keystore.properties` 与 `*.jks` 不入库。CI 上没有发布签名，`assembleRelease` 产出未签名包，
  只用来证明 R8 与资源收缩能过，不会被当成发布物。
- 真实音源冒烟测试用 `assumeTrue` 在没有 fixture 时跳过，干净 checkout 不会因此变红。
- 依赖缓存与 Gradle Wrapper 校验由 `gradle/actions/setup-gradle` 负责，不需要另写缓存步骤。
- 模拟器工作流刻意不默认随 PR 跑：时序类测试在 CI 模拟器上更容易抖动，先手动跑到稳定，再考虑加
  `schedule`。
- `checkout@v7` / `upload-artifact@v7` 需要较新的 runner（GitHub 托管的 `ubuntu-latest` 满足）。
  若改用自建 runner，先升级到最新 runner 版本。
