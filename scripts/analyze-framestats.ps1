<#
.SYNOPSIS
    把 `dumpsys gfxinfo framestats` 的逐帧数据按"忙帧 / 冷帧"分开统计。

.DESCRIPTION
    为什么需要这个脚本：`Janky frames` 那一个百分数**不区分两种性质完全不同的掉帧**。
    一次转场里"动画中途画不完"和用户能感觉到的卡顿是同一件事；
    而"应用静止了几百毫秒之后画的第一帧"慢，主要是 GPU 在空闲时降了频，
    跟这帧要画的动画没关系——它慢完，后面的帧立刻回到正常节奏。
    两者混在一个百分数里，就会把"这条路由本来就一直比别的转场贵"这种结论
    建立在一批根本不属于转场的帧上。

    分法只看一个量：**这一帧的 `IntendedVsync` 与上一帧相差多少**。
    小于 -BusyGapMs（默认 50ms）说明上一帧刚画完不久，应用正在连续渲染，算忙帧；
    大于它就中间冷了一段，这一帧是冷启动帧。这个判据不需要知道平台的算法。

    每帧的墙钟成本取 `FrameCompleted - HandleInputStart`，超预算判据取
    `FrameCompleted > FrameDeadline`（平台自己那一列）与 `-BudgetMs`（默认 16.6667，
    也就是 60Hz 一档的预算）两种都算，因为**平台那个 `Janky frames` 的谓词没能复现**：
    在 `phone-player` 那份数据上，把 deadline 提前 7ms 才凑出平台报的 37 帧，
    而同一套 slack 在另外两份上凑不出来。所以这里不冒充平台口径，只报自己的口径。

.NOTES
    用法：
        powershell -File scripts/analyze-framestats.ps1 -Path build\phone-player\jank-player-flight.txt
        powershell -File scripts/analyze-framestats.ps1 -Path build\phone*\*.txt   # 一次比多份

    只读文件，不碰设备。历史采集可以直接复分析，这也是它能补上
    `v0.9.2` / `v0.9.3` 那两处结论修正的原因。
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, ValueFromPipeline = $true)]
    [string[]] $Path,

    # 上一帧到这一帧的间隔小于它就视为"连续渲染中"
    [double] $BusyGapMs = 50,

    # 一帧的墙钟预算；默认是 60Hz 的一档（16.67ms）
    [double] $BudgetMs = 16.6667
)

function Percentile([double[]] $Values, [double] $P) {
    $sorted = @($Values | Sort-Object)
    if ($sorted.Count -eq 0) { return 0 }
    $k = [int][Math]::Ceiling(($P / 100) * $sorted.Count) - 1
    if ($k -lt 0) { $k = 0 }
    if ($k -ge $sorted.Count) { $k = $sorted.Count - 1 }
    return $sorted[$k]
}

$rowsOut = New-Object System.Collections.Generic.List[object]

foreach ($file in $Path) {
        $text = Get-Content -LiteralPath $file
        $headerLine = ($text | Select-String -Pattern '^Flags,FrameTimelineVsyncId' | Select-Object -First 1)
        if (-not $headerLine) {
            Write-Warning "$file 里没有 framestats 表头，跳过"
            continue
        }
        $header = $headerLine.Line -split ','
        $ix = @{}
        for ($i = 0; $i -lt $header.Count; $i++) { $ix[$header[$i].Trim()] = $i }
        foreach ($needed in @('IntendedVsync', 'HandleInputStart', 'IssueDrawCommandsStart', 'FrameDeadline', 'FrameCompleted')) {
            if (-not $ix.ContainsKey($needed)) {
                Write-Warning "$file 缺少列 $needed，跳过"
                $headerLine = $null
                break
            }
        }
        if (-not $headerLine) { continue }

        $frames = @($text | Select-String -Pattern '^\s*\d+,\d+,\d+,\d+' | ForEach-Object { , ($_.Line -split ',') })
        if ($frames.Count -lt 2) {
            Write-Warning "$file 只有 $($frames.Count) 帧，样本太小，跳过"
            continue
        }

        $busyCost = New-Object System.Collections.Generic.List[double]
        $coldCost = New-Object System.Collections.Generic.List[double]
        $busyGpu = New-Object System.Collections.Generic.List[double]
        $coldGpu = New-Object System.Collections.Generic.List[double]
        $busyMiss = 0; $coldMiss = 0
        $busyOver = 0; $coldOver = 0

        for ($i = 0; $i -lt $frames.Count; $i++) {
            $f = $frames[$i]
            $cost = ([double] $f[$ix['FrameCompleted']] - [double] $f[$ix['HandleInputStart']]) / 1e6
            $gpu = ([double] $f[$ix['FrameCompleted']] - [double] $f[$ix['IssueDrawCommandsStart']]) / 1e6
            $missedDeadline = ([double] $f[$ix['FrameCompleted']] -gt [double] $f[$ix['FrameDeadline']])
            # 第一帧没有前帧可比，只能按冷帧处理：它一定是采集窗口打开后的第一帧
            $gap = if ($i -gt 0) { ([double] $f[$ix['IntendedVsync']] - [double] $frames[$i - 1][$ix['IntendedVsync']]) / 1e6 } else { [double]::MaxValue }
            $isBusy = $gap -lt $BusyGapMs
            if ($isBusy) {
                $busyCost.Add($cost); $busyGpu.Add($gpu)
                if ($missedDeadline) { $busyMiss++ }
                if ($cost -gt $BudgetMs) { $busyOver++ }
            }
            else {
                $coldCost.Add($cost); $coldGpu.Add($gpu)
                if ($missedDeadline) { $coldMiss++ }
                if ($cost -gt $BudgetMs) { $coldOver++ }
            }
        }

        $summary = Select-String -Path -LiteralPath $file -Pattern 'Total frames rendered|Janky frames:' -ErrorAction SilentlyContinue
        $reported = ''
        if ($summary) {
            $total = ($summary | Where-Object { $_.Line -match 'Total frames' } | Select-Object -First 1)
            $janky = ($summary | Where-Object { $_.Line -match 'Janky frames:' } | Select-Object -First 1)
            if ($total -and $janky) {
                $reported = '{0} / {1}' -f (($janky.Line -replace '[^0-9.%()]*', '').Trim()), (($total.Line -replace '\D', ''))
            }
        }

        $rowsOut.Add([pscustomobject]@{
                Source     = $file
                Dumped     = $frames.Count
                Reported   = $reported
                Busy       = $busyCost.Count
                BusyOver   = $busyOver
                BusyMiss   = $busyMiss
                BusyP50    = [Math]::Round((Percentile $busyCost.ToArray() 50), 2)
                BusyMax    = [Math]::Round(($busyCost | Measure-Object -Maximum).Maximum, 2)
                BusyGpuP50 = [Math]::Round((Percentile $busyGpu.ToArray() 50), 2)
                Cold       = $coldCost.Count
                ColdOver   = $coldOver
                ColdMiss   = $coldMiss
                ColdP50    = [Math]::Round((Percentile $coldCost.ToArray() 50), 2)
                ColdMax    = if ($coldCost.Count) { [Math]::Round(($coldCost | Measure-Object -Maximum).Maximum, 2) } else { 0 }
            })
}

$rowsOut | Format-Table -AutoSize | Out-String -Width 200 | Write-Output

Write-Output '列的含义：'
Write-Output ("  Busy / Cold   连续渲染中的帧数 / 冷了一段之后的第一帧数（分界 {0}ms）" -f $BusyGapMs)
Write-Output ("  *Over         墙钟成本超过 {0}ms 的帧数；*Miss 是 FrameCompleted 超过 FrameDeadline 的帧数" -f $BudgetMs)
Write-Output '  BusyP50/Max   忙帧墙钟成本的中位 / 最大值；BusyGpuP50 是其中 GPU 段的中位'
Write-Output '  Reported      平台自己那两行（Janky frames / Total frames rendered），仅供对照，口径与上面不同'
