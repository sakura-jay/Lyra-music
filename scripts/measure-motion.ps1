<#
.SYNOPSIS
    动效 jank 取证脚本（工单 001）。

.DESCRIPTION
    只读 `dumpsys gfxinfo`，不往应用里塞任何测量代码。两个模式共用同一套口径：

    - `Jank`：工单 001 的两个场景——转场首帧（12 次 Tab 切换）与列表滚动（榜单广场连甩）。
      这是 before / after 对照用的那一套。
    - `Probe`：三个对照组，用来把"转场机制的成本"和"内容/图片的成本"分开——
      X 都点首页（不产生路由切换）、Y 首页↔音乐库（最轻内容、无错峰揭示）、
      Z 首页↔搜索（空态）。

    读的是 `Janky frames` 与 `Number Frame deadline missed` 这两行**新指标**，
    不是并列输出的 `(legacy)` 那一列——两套算法不一样，读错列结论就错。

    动画时长（三档系统缩放复核）**不在这里量**，见 measure-transition-duration.ps1。

.NOTES
    跑之前必须满足：屏幕亮着、已解锁、应用在前台。脚本会检查并在不满足时直接退出，
    因为窗口不可见时 `dumpsys gfxinfo` 会打印 "Failure while dumping the app"，
    量出来的是空文件而不是零。

    默认坐标是 PLK110（1272x2772）上实测的 NavigationBar 中心点。换设备必须同时换
    -TabX / -TabY / -ScrollX / -ScrollTop / -ScrollBottom，否则点到的不是导航栏。

    模拟器上量得到的只有**相对**结论（哪一页更贵），绝对 jank 率只认真机：
    模拟器 60Hz、无真机 GPU。
#>
[CmdletBinding()]
param(
    [ValidateSet('Jank', 'Probe')]
    [string] $Mode = 'Probe',

    [string] $Serial = '3B165C008H700000',
    [string] $Package = 'io.github.sakurajay.lyra',
    [string] $OutDir = 'build',

    # 场景 A：12 次 Tab 切换。坐标是 PLK110（1272x2772）上 NavigationBar 三个项的中心
    [int[]] $TabX = @(188, 635, 1078),
    [int] $TabY = 2612,
    [int] $TapIntervalMs = 1200,
    [int] $TapCount = 12,

    # 场景 B 的甩动区间（同样是 PLK110 的绝对坐标；换设备要一起换）
    [int] $ScrollX = 636,
    [int] $ScrollTop = 500,
    [int] $ScrollBottom = 2050
)

$ErrorActionPreference = 'Stop'
$adb = 'D:\APP\AndroidSDK\platform-tools\adb.exe'

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]] $AdbArgs)
    & $adb -s $Serial @AdbArgs
}

function Invoke-Shell {
    param([Parameter(ValueFromRemainingArguments = $true)][string] $Command)
    & $adb -s $Serial shell $Command
}

function Get-GfxInfo([string] $Tag) {
    Invoke-Shell "dumpsys gfxinfo $Package $Tag"
}

function Get-TotalFrames {
    $line = Get-GfxInfo '' | Select-String -Pattern 'Total frames rendered:\s*(\d+)' | Select-Object -First 1
    if ($line) { return [int] $line.Matches[0].Groups[1].Value }
    return -1
}

function Assert-AppVisible {
    $focus = (Invoke-Shell "dumpsys window | grep mCurrentFocus") -join ' '
    if ($focus -notmatch $Package) {
        throw "应用不在前台（mCurrentFocus: $focus）。请先解锁并把 Lyra 切到前台。"
    }
    if ((Get-TotalFrames) -lt 0) {
        throw 'gfxinfo 读不到帧数：通常是屏幕已熄灭。请保持屏幕常亮后重跑。'
    }
}

function Wait-FirstFrame {
    # 窗口没 attach 时 gfxinfo 会抛 NPE（见工单 001 的坑 1），所以必须轮询到 > 0
    for ($i = 0; $i -lt 40; $i++) {
        if ((Get-TotalFrames) -gt 0) { return }
        Start-Sleep -Milliseconds 500
    }
    throw '等不到第一帧：应用可能没起来。'
}

function Restart-App {
    Invoke-Shell "am force-stop $Package" | Out-Null
    Invoke-Shell "am start -n $Package/.MainActivity" | Out-Null
    Wait-FirstFrame
    # 首屏的错峰揭示与图片解码都在这几秒里，等它静下来再计数
    Start-Sleep -Seconds $([Math]::Ceiling($TapIntervalMs / 1000) + 6)
}

function Measure-Scenario {
    param([string] $Name, [scriptblock] $Actions)
    Assert-AppVisible
    Invoke-Shell "dumpsys gfxinfo $Package reset" | Out-Null
    & $Actions
    Start-Sleep -Seconds 2
    $text = Get-GfxInfo 'framestats'
    $path = Join-Path $OutDir "jank-$Name.txt"
    Set-Content -Path $path -Value $text -Encoding UTF8
    $summary = $text | Select-String -Pattern 'Total frames rendered|Janky frames|Frame deadline missed|50th|90th|95th|99th'
    Write-Output "== $Name -> $path"
    $summary | ForEach-Object { Write-Output $_.Line.Trim() }
}

function Invoke-Taps {
    param([int] $Count)
    for ($i = 0; $i -lt $Count; $i++) {
        $x = $TabX[$i % $TabX.Count]
        Invoke-Shell "input tap $x $TabY" | Out-Null
        Start-Sleep -Milliseconds $TapIntervalMs
    }
}

switch ($Mode) {
    'Jank' {
        # 场景 A：转场首帧。先做 3 次预热切换，把冷启动的重组噪声排除在计数窗口之外
        Restart-App
        Invoke-Taps 3
        Measure-Scenario 'transition' { Invoke-Taps $TapCount }
        # 场景 B：列表滚动（榜单广场）
        Invoke-Shell "input tap $($TabX[2]) $TabY" | Out-Null
        Start-Sleep -Seconds 2
        Measure-Scenario 'scroll' {
            for ($i = 0; $i -lt 12; $i++) { Invoke-Shell "input swipe $ScrollX $ScrollBottom $ScrollX $ScrollTop 200" | Out-Null }
            for ($i = 0; $i -lt 12; $i++) { Invoke-Shell "input swipe $ScrollX $ScrollTop $ScrollX $ScrollBottom 200" | Out-Null }
        }
    }

    'Probe' {
        # 三个对照组：把"转场机制的成本"和"内容/图片的成本"分开
        Restart-App
        Measure-Scenario 'probe-X-same-tab' { Invoke-Taps 12 }   # 都点首页：不产生路由切换
        Measure-Scenario 'probe-Y-home-library' {
            for ($i = 0; $i -lt 6; $i++) {
                Invoke-Shell "input tap $($TabX[0]) $TabY" | Out-Null; Start-Sleep -Milliseconds $TapIntervalMs
                Invoke-Shell "input tap $($TabX[2]) $TabY" | Out-Null; Start-Sleep -Milliseconds $TapIntervalMs
            }
        }
        Measure-Scenario 'probe-Z-home-search' {
            for ($i = 0; $i -lt 6; $i++) {
                Invoke-Shell "input tap $($TabX[0]) $TabY" | Out-Null; Start-Sleep -Milliseconds $TapIntervalMs
                Invoke-Shell "input tap $($TabX[1]) $TabY" | Out-Null; Start-Sleep -Milliseconds $TapIntervalMs
            }
        }
    }

    # 三档缩放（0 / 0.5 / 2.0）的复核不在这里：它要的量是"一次动画画了多少帧"，
    # 不是 jank 率。那个量法在 measure-transition-duration.ps1 里已经做对了——
    # 静止时应用一帧都不画，所以"点一下之后固定窗口内的帧数"就是墙钟时长的代理量。
    # 本版实测的三档结论记在 ../../docs/releases/v0.9.2/CHANGELOG.md 的"决策 1"。
}
