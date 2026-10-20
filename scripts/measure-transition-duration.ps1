<#
.SYNOPSIS
    用 framestats 的逐帧时间戳量一次转场的**墙钟时长**。

.DESCRIPTION
    量法：静止时应用**一帧都不画**（实测 `reset` 后静置 2s = 0 帧），
    所以"点一下之后固定窗口内渲染了多少帧"就是转场时长的直接代理量——
    帧数 × 16.7ms ≈ 动画墙钟时长。这比轮询 `Total frames` 干净：
    每次 `adb shell` 要花 150–300ms，轮询本身就成了分辨率。

    窗口取 1500ms：够容纳 2.0 档（放大不生效时最长约 950ms），
    又不至于把下一次操作的帧混进来。每个数取 5 次的中位数。

.EXAMPLE
    .\scripts\measure-transition-duration.ps1 -Scales 1.0,2.0,0.5 -Repeats 3
#>
[CmdletBinding()]
param(
    [string] $Serial = 'emulator-5554',
    [string] $Package = 'io.github.sakurajay.lyra',
    [double[]] $Scales = @(1.0, 2.0, 0.5, 0.25, 1.0),
    [int] $Repeats = 3,
    # 空闲判定阈值：超过它就认为动画已经结束
    [int] $IdleGapMs = 250
)

$ErrorActionPreference = 'Stop'
$adb = 'D:\APP\AndroidSDK\platform-tools\adb.exe'

function Sh([string] $Command) {
    # `dumpsys ... | grep` 会让 dumpsys 收到 broken pipe 并往 stderr 喊一句，
    # 那是命令成功的正常现象；$ErrorActionPreference = 'Stop' 会把它当异常抛出来
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $adb -s $Serial shell $Command 2>$null } finally { $ErrorActionPreference = $previous }
}

function Get-VsyncSeries {
    $rows = Sh "dumpsys gfxinfo $Package framestats" |
        Where-Object { $_ -match '^\d,' }
    $vs = foreach ($r in $rows) {
        $c = $r.Split(',')
        # 列 2 是 IntendedVsync（ns）；同一帧会打印两行，去重
        if ($c.Length -gt 2) { [long] $c[2] }
    }
    ($vs | Sort-Object -Unique)
}

function Get-FrameCount {
    $f = Sh "dumpsys gfxinfo $Package | grep -m1 'Total frames'"
    if ($f -match '(\d+)') { return [int] $Matches[1] }
    return -1
}

<#
    一次采样的精确做法：

    只数"紧接在点击之后的那一段连续帧"。整窗帧数会被背景工作污染——
    进首页时还有错峰揭示与图片解码在画帧，那部分与转场无关，
    所以直接拿整窗帧数比档，比值会被稀释成"看起来没生效"。

    连续的定义：相邻两帧间隔 > $IdleGapMs 就断开。静止时应用一帧不画，
    所以段与段之间一定有一个大间隔，这一段就是转场本身。
#>
function Invoke-OneSample {
    param([int] $WindowMs = 2500)
    # 音乐库没有错峰揭示（reveal 只挂在首页/搜索/榜详情），所以它是更干净的被测对象
    Sh "input tap 180 2290" | Out-Null
    Start-Sleep -Milliseconds 2000
    Sh "dumpsys gfxinfo $Package reset" | Out-Null
    Sh "input tap 900 2290" | Out-Null
    Start-Sleep -Milliseconds $WindowMs
    $series = Get-VsyncSeries
    if (@($series).Count -lt 2) { return $null }
    $start = 0
    for ($i = 1; $i -lt $series.Count; $i++) {
        if (($series[$i] - $series[$i - 1]) / 1e6 -gt $IdleGapMs) { break }
        $start = $i
    }
    $frames = $start + 1
    $spanMs = [Math]::Round(($series[$start] - $series[0]) / 1e6, 1)
    return [pscustomobject]@{ SpanMs = $spanMs; Frames = $frames; Total = @($series).Count }
}

foreach ($scale in $Scales) {
    Sh "settings put global animator_duration_scale $scale" | Out-Null
    Sh "am force-stop $Package" | Out-Null
    Sh "am start -n $Package/.MainActivity" | Out-Null
    Start-Sleep -Seconds 9
    # 预热：让首屏重组、图片解码、JIT 都先过去
    Sh "input tap 900 2290" | Out-Null; Start-Sleep -Milliseconds 2000
    Sh "input tap 180 2290" | Out-Null; Start-Sleep -Milliseconds 2000
    # 先确认这一档下静止真的不画帧，否则帧数这个代理量不成立
    Sh "dumpsys gfxinfo $Package reset" | Out-Null
    Start-Sleep -Milliseconds 1500
    $idle = Get-FrameCount
    $samples = @()
    for ($i = 0; $i -lt $Repeats; $i++) {
        $s = Invoke-OneSample
        if ($null -ne $s) { $samples += $s }
    }
    if ($samples.Count -eq 0) {
        Write-Output "animator=$scale  采不到样本"
        continue
    }
    $sorted = $samples | Sort-Object SpanMs
    $median = $sorted[[int]($sorted.Count / 2)]
    Write-Output ("animator={0,-5} idle={1,2}帧  中位跨度={2,6}ms ({3}帧)  全部={4}  窗口总帧={5}" -f `
        $scale, $idle, $median.SpanMs, $median.Frames,
        (($samples | ForEach-Object { $_.SpanMs }) -join '/'),
        (($samples | ForEach-Object { $_.Total }) -join '/'))
}
Sh 'settings put global animator_duration_scale 1.0' | Out-Null
Write-Output 'animator_duration_scale 已复原为 1.0'
