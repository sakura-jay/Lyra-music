<#
.SYNOPSIS
    真机走查的取点工具：从 uiautomator 的界面树里按文字找坐标，不写死像素。
.DESCRIPTION
    PLK110 是 1272x2772，模拟器是 1080x2400，任何硬编码坐标换设备就废。
    所以这里一律 dump 界面树、按 text / content-desc 子串匹配、取节点矩形的中心点。
    匹配不到就返回 $null，由调用方决定是失败还是换一页再试——绝不猜坐标。
#>
param(
    [string] $Serial = '3B165C008H700000',
    [Parameter(Mandatory = $true)][string] $Match,
    [int] $Nth = 1,
    [switch] $Contains
)

function Get-UiXml([string] $dev) {
    $remote = '/sdcard/lyra-walk.xml'
    & adb -s $dev shell uiautomator dump $remote 2>&1 | Out-Null
    $local = Join-Path $env:TEMP 'lyra-walk.xml'
    & adb -s $dev pull $remote $local 2>&1 | Out-Null
    return [System.IO.File]::ReadAllText($local)
}

function Find-NodeCenter([string] $xml, [string] $needle, [int] $nth, [bool] $fuzzy) {
    $found = @()
    foreach ($m in [regex]::Matches($xml, '<node\b[^>]*>')) {
        $tag = $m.Value
        $text = [regex]::Match($tag, 'text="([^"]*)"').Groups[1].Value
        $desc = [regex]::Match($tag, 'content-desc="([^"]*)"').Groups[1].Value
        $hit = if ($fuzzy) {
            $text -like "*$needle*" -or $desc -like "*$needle*"
        } else {
            $text -eq $needle -or $desc -eq $needle
        }
        if (-not $hit) { continue }
        $b = [regex]::Match($tag, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
        if (-not $b.Success) { continue }
        $x1 = [int]$b.Groups[1].Value; $y1 = [int]$b.Groups[2].Value
        $x2 = [int]$b.Groups[3].Value; $y2 = [int]$b.Groups[4].Value
        if ($x2 -le $x1 -or $y2 -le $y1) { continue }
        $found += [pscustomobject]@{
            X = [int](($x1 + $x2) / 2); Y = [int](($y1 + $y2) / 2)
            W = $x2 - $x1; H = $y2 - $y1
            Text = if ($text) { $text } else { $desc }
        }
    }
    if ($found.Count -eq 0) { return $null }
    $pick = $found | Select-Object -Skip ($nth - 1) -First 1
    if (-not $pick) { $pick = $found[-1] }
    return $pick
}

$dev = $Serial
$xml = Get-UiXml $dev
$r = Find-NodeCenter $xml $Match $Nth ([bool]$Contains)
if (-not $r) {
    Write-Output "NOTFOUND $Match"
    exit 1
}
Write-Output ("{0},{1}  [{2}x{3}]  {4}" -f $r.X, $r.Y, $r.W, $r.H, $r.Text)