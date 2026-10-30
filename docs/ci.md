# CI 与发布

## 流水线

| 工作流 | 触发 | 内容 |
| --- | --- | --- |
| `CI / Version guard` | 推送到 `main`、`dev`、`dev/**`，所有 PR，手动 | 只在准备发新版本时校验 `versionCode` 已递增（几秒） |
| `CI / Unit tests` | 推送到 `main`、`dev`、`dev/**`，所有 PR，手动 | 编译 + 单元测试（快，约 2–3 分钟） |
| `CI / Lint` | 推送到 `main`、`dev`、`dev/**`，所有 PR，手动 | 只跑 `lintDebug`（约 3 分钟） |
| `CI / Assemble` | 只在 `main`、PR、手动 | Debug/Release 打包，验证 R8 与资源收缩（约 4–6 分钟） |
| `CI / Publish release` | `main` 上前四个 job 全绿后自动；也可手动勾选 `publish_release` | 用发布密钥重打签名包，建 tag 并发 GitHub Release |
| `Instrumented tests`（`.github/workflows/instrumented.yml`） | 仅手动 | API 36 模拟器上跑 `connectedDebugAndroidTest` |

`dev` 分支推送只跑单测与 Lint，用来在切片提交时尽早发现回归、且不把 Lint 问题攒到合并时；
合入 `main` 后再跑打包与发布。只改 `docs/` 或 `*.md` 的推送不跑。

## 首次启用

1. 提交并 push `.github/workflows/` 到 `main`（或先推一个分支开 PR）。
2. 打开仓库 **Actions** 页，若出现启用提示，点击启用 workflows。
3. 进入 `CI` 工作流，手动 **Run workflow** 跑第一遍，确认 `Version guard`、`Unit tests`、`Lint`、
   `Assemble` 都为绿。
4. 需要阻止红灯合入时，在 **Settings → Rules / Branches** 添加规则，把 required status check 设为
   `CI / Version guard`、`CI / Unit tests`、`CI / Lint`、`CI / Assemble`。

私有仓库在免费计划下没有分支保护；升级到 Pro/Team 或把仓库公开后再加规则。Actions
在私有仓库消耗免费额度，本流水线一次约 4–8 分钟。

## 本地复现

```powershell
# 切片开发时：单测 + Lint（对应 CI 的第一层）
.\gradlew.bat testDebugUnitTest lintDebug

# 收口推 main 前：再加打包（对应 CI 的 Assemble）
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleRelease
```

模拟器工作流本地等价于：

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

## 自动发布

`main` 上的 `Version guard`、`Unit tests`、`Lint`、`Assemble` 全部通过后，`Publish release` 会自动：

1. 从 `app/build.gradle.kts` 读 `versionName`，拼出 tag `vX.Y.Z`；
2. 若该 tag 已存在于远端，直接跳过（所以重复推 `main` 不会重复发版）；
3. 用 secrets 里的发布密钥重新打 **签名** release 包，并用 `apksigner verify` 校验；
4. 打 annotated tag 并推送，建 GitHub Release，附 `lyra-vX.Y.Z.apk`；
5. 发布说明取 `docs/releases/vX.Y.Z/CHANGELOG.md`（相对链接会改写成绝对链接）；
   该文件缺失时任务直接失败，不会发出没有说明的 Release。

**发版顺序因此变成**：先提升 `versionCode` 与 `versionName`，写好
`docs/releases/vX.Y.Z/CHANGELOG.md`，再 squash 合入 `main`。版本升了 tag 才不存在，
才会真正发布。

### 需要配置的 secrets

在 **Settings → Secrets and variables → Actions → New repository secret** 添加：

| Secret | 内容 |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | `local-release.jks` 的 base64 文本，**2980 字符单行** |
| `RELEASE_STORE_PASSWORD` | keystore 口令（本机 `keystore.properties` 的 `storePassword`） |
| `RELEASE_KEY_ALIAS` | 密钥别名（本机为 `lyra`） |
| `RELEASE_KEY_PASSWORD` | 密钥口令（本机 `keystore.properties` 的 `keyPassword`） |

只加 **Repository secrets**：workflow 里的 job 没有声明 `environment:`，读不到
Environment secrets；加错层级不会报错，只会拿到空值。

生成 base64（PowerShell）：

```powershell
$b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes('local-release.jks'))
$b64.Length                      # v0.9.7 时为 2980
$b64.Substring(0, 12)            # /u3+7QAAAAIA
$b64.Substring($b64.Length - 12) # UOfbDBsa8Tj3
Set-Clipboard -Value $b64        # 直接粘到 GitHub，不会有换行
```

在 Linux/macOS 上：

```bash
base64 -w0 local-release.jks   # -w0 是必须的，否则会折行
```

**v0.9.7 首次发布踩过的坑**：secret 里填了错误的 base64，`base64 -d` 报
`invalid input`。常见来源是用在线工具或 `base64` 不带 `-w0` 生成了多行文本、
给值加了 `data:...;base64,` 前缀、或把 `+`／`/` 删掉。粘完后核对长度与前缀
两项，比肉眼检查整串可靠。

workflow 侧已经容错：`Restore keystore` 会先 `tr -d '\r\n\t '` 剔除空白，所以
CRLF 不再致命；但**内容本身错**（长度不对、字符被替换）仍会在
`Assemble signed release` 之前的 `[ ! -s release.jks ]` 或签名阶段暴露。

密钥只落到 runner 的 workspace，用完随机器销毁；`release.jks` 与 `keystore.properties`
都在 `.gitignore` 里，不会被提交。**keystore 一旦丢失就无法给已发布版本做增量更新**，
务必另存备份。

### 手动发布

**Actions → CI → Run workflow**，在 `main` 分支上勾选 `publish_release`。tag 已存在时同样会跳过。

## 版本与发版纪律

下面几条不是建议，是踩过就会返工的硬约定。

### 1. 发新版必须同时提升 `versionCode` 与 `versionName`

```kotlin
versionCode = 12          // 整数，每次发版必须比历史最大值大
versionName = "0.9.7"     // 字符串，决定 tag 名
```

Android 只允许用 **不小于** 已安装版本 `versionCode` 的包覆盖安装。只改 `versionName` 时，
新版能打包、能发布，但已经装了旧版的用户点安装会直接失败，而且这个错误要等到下一个版本
才能补救。

`Version guard` 会在准备发新版本时（`versionName` 对应的 tag 还不存在）拿历史所有 tag 里
最大的 `versionCode` 作基线比较，不满足就报错，并直接给出该填的数值：

```text
::error::准备发布 v0.9.7，但 versionCode=11 未超过 v0.9.6 的 11。
::error::修法：把 app/build.gradle.kts 的 versionCode 改成 12 或更大。
```

只改文档、不改版本号的日常推送不会被这条拦住。

### 2. 发布说明必须先于合入写好

`docs/releases/vX.Y.Z/CHANGELOG.md` 缺失时 `Publish release` 直接失败。它不会退化成
自动生成的提交列表——版本说明是这个项目文档的一部分，不是附属产物。

### 3. 不要手动推 tag

tag 由 `Publish release` 在签名包验证通过后创建。如果先手动 `git push origin v0.9.7`，
CI 会判定“该版本已发布”并跳过，结果是有 tag、没 Release、没 APK。

需要手动控制发布时机时，用 **Actions → CI → Run workflow** 勾选 `publish_release`。

### 4. 只改文档的推送不会触发 CI

`docs/**` 与 `**/*.md` 在 `paths-ignore` 里。所以：

- 只补 `CHANGELOG.md` 的推送不会跑 CI，也不会发版，这符合预期；
- 发版那次推送必须带代码改动（至少是 `versionCode`／`versionName`），否则不触发；
- 如果某分支上一次推送是红的，下一次只改文档不会产生新运行，界面上仍显示上次的状态。
  判断以 Actions 页面里的运行记录为准。

### 5. keystore 必须异地备份

`local-release.jks` 丢了就再也无法给已发布版本做增量更新，只能换包名重新上架。请把它和
口令放进密码管理器或加密存储，不要只留在这台开发机上。

### 6. `versionCode` 只能增不能减

回退版本时（例如发布 v0.9.7 后发现严重问题、想退回 v0.9.6），**不能把 `versionCode` 改回 11**。
正确做法是把修好的代码用更大的 `versionCode` 再发一个补丁版（如 `versionCode = 13`、
`versionName = "0.9.8"`）。`Version guard` 会拦住下调。

### 7. 发一个版本的完整清单

```text
[ ] app/build.gradle.kts: versionCode +1
[ ] app/build.gradle.kts: versionName 改成新版本
[ ] docs/releases/vX.Y.Z/CHANGELOG.md 写好
[ ] CHANGELOG.md（仓库根的版本总账）补一条
[ ] 本地 .\gradlew.bat testDebugUnitTest lintDebug assembleRelease
[ ] squash 合入 main 并 push（不要手动打 tag）
[ ] 等 Publish release 变绿，确认 Release 页有 APK
```

## 说明

- `keystore.properties` 与 `*.jks` 不入库。`Assemble` 里没有发布签名，`assembleRelease`
  产出未签名包，只用来证明 R8 与资源收缩能过；签名与发布只发生在 `Publish release`。
- 真实音源冒烟测试用 `assumeTrue` 在没有 fixture 时跳过，干净 checkout 不会因此变红。
- 依赖缓存与 Gradle Wrapper 校验由 `gradle/actions/setup-gradle` 负责，不需要另写缓存步骤。
- 模拟器工作流刻意不默认随 PR 跑：时序类测试在 CI 模拟器上更容易抖动，先手动跑到稳定，再考虑加
  `schedule`。
- `checkout@v7` / `upload-artifact@v7` 需要较新的 runner（GitHub 托管的 `ubuntu-latest` 满足）。
  若改用自建 runner，先升级到最新 runner 版本。
