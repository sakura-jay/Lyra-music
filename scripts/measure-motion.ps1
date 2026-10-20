<#
.SYNOPSIS
    动效 jank 取证脚本（工单 001）。

.DESCRIPTION
    只读 `dumpsys gfxinfo`，不往应用里塞任何测量代码。两个模式共用同一套口径：

    - `Jank`：工单 001 的两个场景——转场首帧（12 次 Tab 切换）与列表滚动（榜单广场连甩）。
      这是 before / after 对照用的那一套。
    - `Player`：迷你条 ↔ 播放页这一条路由，量的是 **M10 共享元素**那条转场的首帧。
      `Jank` 的场景 A 只切 Tab，走不到 `CoverFlight`，所以"重新收紧条件 ②：共享元素落地之后"
      必须用这个模式才量得到。
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
    [ValidateSet('Jank', 'Probe', 'Player', 'Detail')]
    [string] $Mode = 'Jank',

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

# 输出目录不存在时 Set-Content 会在跑完整套场景之后才报错，白等一两分钟，所以先建
New-Item -ItemType Directory -Force $OutDir | Out-Null

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

function Get-UiNodeCenter {
    # 按可见文字取坐标，而不是写死像素：换设备（PLK110 1272x2772 / 模拟器 1080x2400）就不用改脚本。
    # 取不到就返回 $null，由调用方决定是失败还是换页重试——绝不猜坐标。
    param([string] $Needle, [int] $Nth = 1)
    Invoke-Shell 'uiautomator dump /sdcard/lyra-probe.xml' | Out-Null
    $local = Join-Path $env:TEMP 'lyra-probe.xml'
    # 先删再拉：拉失败时留着上一次的树会让 Test-Path 通过，于是按**旧界面**取坐标
    Remove-Item $local -Force -ErrorAction SilentlyContinue
    Invoke-Adb pull /sdcard/lyra-probe.xml $local | Out-Null
    if (-not (Test-Path $local)) { return $null }
    $xml = [System.IO.File]::ReadAllText($local)
    $found = @()
    foreach ($m in [regex]::Matches($xml, '<node\b[^>]*>')) {
        $tag = $m.Value
        $text = [regex]::Match($tag, 'text="([^"]*)"').Groups[1].Value
        $desc = [regex]::Match($tag, 'content-desc="([^"]*)"').Groups[1].Value
        if ($text -notlike "*$Needle*" -and $desc -notlike "*$Needle*") { continue }
        $b = [regex]::Match($tag, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
        if (-not $b.Success) { continue }
        $x1 = [int]$b.Groups[1].Value; $y1 = [int]$b.Groups[2].Value
        $x2 = [int]$b.Groups[3].Value; $y2 = [int]$b.Groups[4].Value
        if ($x2 -le $x1 -or $y2 -le $y1) { continue }
        $found += [pscustomobject]@{
            X = [int](($x1 + $x2) / 2); Y = [int](($y1 + $y2) / 2)
        }
    }
    if ($found.Count -lt $Nth) { return $null }
    return $found[$Nth - 1]
}

function Get-ScreenSize {
    # 迷你条的条体上没有文字（封面与文字都取不到节点中心），所以按屏宽算一个落在条体上的 X；
    # 屏高用来判"这颗键是不是在屏幕底部"。现读 `wm size` 而不是写死：换设备不用改脚本。
    $line = (Invoke-Shell 'wm size') -join ' '
    $m = [regex]::Match($line, '(\d+)x(\d+)')
    if (-not $m.Success) { throw "读不到屏幕尺寸：$line" }
    return [pscustomobject]@{ W = [int] $m.Groups[1].Value; H = [int] $m.Groups[2].Value }
}

switch ($Mode) {
    'Jank' {
        # 场景 A：转场首帧。先做 3 次预热切换，把冷启动的重组噪声排除在计数窗口之外
        Restart-App
        Invoke-Taps 3
        Measure-Scenario 'transition' { Invoke-Taps $TapCount }
        # 场景 B：列表滚动（榜单广场）。
        # 原来这里点 TabX[2]（音乐库）再甩 24 次——音乐库是一屏放得下的短页，**没有可滚内容**，
        # 静止时应用一帧都不画，于是量到 `Total frames rendered: 0`，看着像"滚动零帧达标"，
        # 其实是根本没滚。改成从首页点"榜单"那一栏的第二个「更多」进广场（按文字取点，不写死坐标）。
        Invoke-Shell "input tap $($TabX[0]) $TabY" | Out-Null
        Start-Sleep -Seconds 2
        $more = Get-UiNodeCenter -Needle '更多' -Nth 2
        if (-not $more) { throw '找不到榜单那一栏的「更多」：首页结构变了或界面没加载完，场景 B 不能瞎跑。' }
        Invoke-Shell "input tap $($more.X) $($more.Y)" | Out-Null
        Start-Sleep -Seconds 2
        $plaza = Get-UiNodeCenter -Needle '广场'
        if (-not $plaza) { throw '点完「更多」没进到榜单广场，场景 B 的坐标口径不成立。' }
        Measure-Scenario 'scroll' {
            for ($i = 0; $i -lt 12; $i++) { Invoke-Shell "input swipe $ScrollX $ScrollBottom $ScrollX $ScrollTop 200" | Out-Null }
            for ($i = 0; $i -lt 12; $i++) { Invoke-Shell "input swipe $ScrollX $ScrollTop $ScrollX $ScrollBottom 200" | Out-Null }
        }
    }

    'Player' {
        # 迷你条 ↔ 播放页：M10 共享元素（CoverFlight）走的就是这一条路由。
        # 这里**不能** Restart-App：force-stop 会把播放器一起杀掉，迷你条回到"未在播放"，
        # 没有封面就没有起飞，量到的只是普通路由转场——那正是条件 ② 要排除的情形。
        Assert-AppVisible
        # 先回到首页：迷你条在首页与播放页上的 Y 不一样（2576 / 2296），
        # 不固定起点就会在播放页上点到它自己的暂停键，量到的根本不是转场。
        Invoke-Shell "input tap $($TabX[0]) $TabY" | Out-Null
        Start-Sleep -Milliseconds 1200
        if (Get-UiNodeCenter -Needle '未在播放') {
            throw '迷你条显示"未在播放"：Player 场景要先在手机上播一首歌（有封面）再跑。'
        }
        # 迷你条右侧那颗键：在播时 desc 是「暂停」、暂停时是「播放」，两者位置相同，
        # 这里只要它的 Y 用来定位条体。两种都要认，否则一首歌唱完自动暂停就量不成了。
        $pause = Get-UiNodeCenter -Needle '暂停'
        if (-not $pause) { $pause = Get-UiNodeCenter -Needle '播放' }
        if (-not $pause) { throw '找不到迷你条那颗播放/暂停键，无法定位条体。' }
        # 不能点这颗键（那只会把歌停下/放回去），所以取它的 Y、X 落在屏幕左侧 1/3 命中条体
        $sz = Get-ScreenSize
        $barX = [int]($sz.W * 0.30)
        $barY = $pause.Y
        Write-Output "mini bar hit: $barX,$barY"
        # 迷你条那颗键在屏幕底部；播放页自己的暂停键在屏幕中段。取到的 Y 偏上就说明现在
        # 不在列表页，继续跑会点到播放页的暂停键（量到的就不是转场）——宁可停下来报错。
        # 实测：首页的迷你键中心 Y=2296，播放页的暂停键中心 Y≈1466，屏高 2772。
        $h = $sz.H
        if ($barY -lt [int]($h * 0.75)) {
            throw "迷你条的键取到 Y=$barY（屏高 $h 的 75% 以下才算底部），不像列表页上的迷你条，Player 场景不跑。"
        }
        Invoke-Shell "input tap $barX $barY" | Out-Null
        Start-Sleep -Milliseconds 1500
        $back = Get-UiNodeCenter -Needle '返回'
        if (-not $back) { throw '点迷你条没进播放页（找不到播放页的返回键）。' }
        Measure-Scenario 'player-flight' {
            for ($i = 0; $i -lt 6; $i++) {
                Invoke-Shell "input tap $barX $barY" | Out-Null
                Start-Sleep -Milliseconds 1600
                Invoke-Shell "input tap $($back.X) $($back.Y)" | Out-Null
                Start-Sleep -Milliseconds 1600
            }
        }
        Invoke-Shell "input keyevent 4" | Out-Null
    }

    'Detail' {
        # 歌单卡片 ↔ 歌单详情：这一条路由**有**共享元素（011）但**没有** BlurBackdrop。
        # 与 Player 场景对照就能把"共享元素覆盖层的成本"和"播放页模糊背景的代价"分开——
        # 两者的帧数都记在转场首帧上，只看 Player 一个数分不出来。
        Assert-AppVisible
        Invoke-Shell "input tap $($TabX[0]) $TabY" | Out-Null
        Start-Sleep -Milliseconds 1500
        $card = Get-UiNodeCenter -Needle '推荐歌单'
        if (-not $card) { throw '找不到「推荐歌单」那一栏，Detail 场景不跑。' }
        # 点推荐排第一张卡的标题（实测卡片标题在封面上方约 280px）
        $tapX = $card.X
        $tapY = $card.Y + 567
        Invoke-Shell "input tap $tapX $tapY" | Out-Null
        Start-Sleep -Milliseconds 2000
        $back = Get-UiNodeCenter -Needle '返回'
        if (-not $back) { throw "点推荐歌单第一张卡（$tapX,$tapY）没进详情页：找不到返回键。" }
        Measure-Scenario 'detail-flight' {
            for ($i = 0; $i -lt 6; $i++) {
                Invoke-Shell "input tap $tapX $tapY" | Out-Null
                Start-Sleep -Milliseconds 1600
                Invoke-Shell "input tap $($back.X) $($back.Y)" | Out-Null
                Start-Sleep -Milliseconds 1600
            }
        }
        Invoke-Shell "input keyevent 4" | Out-Null
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
