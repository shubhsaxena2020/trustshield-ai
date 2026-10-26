[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [string]$PackageName = 'ai.trustshield.android',
    [string]$OutputDirectory = 'logs'
)

$ErrorActionPreference = 'Stop'
$deviceRows = & adb devices
if ($LASTEXITCODE -ne 0 -or -not ($deviceRows | Where-Object { $_ -match "^$([regex]::Escape($Serial))\s+device$" })) {
    throw "ADB device '$Serial' is not connected and authorized."
}
$out = if ([System.IO.Path]::IsPathRooted($OutputDirectory)) { $OutputDirectory } else { [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot $OutputDirectory)) }
New-Item -ItemType Directory -Path $out -Force | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$reportPath = Join-Path $out "phone-report-$stamp.txt"
$report = [System.Collections.Generic.List[string]]::new()
$report.Add("Collected: $(Get-Date -Format o)")
$report.Add("Package: $PackageName")
$report.Add("ABI: $((& adb -s $Serial shell getprop ro.product.cpu.abi).Trim())")
$report.Add("Android: $((& adb -s $Serial shell getprop ro.build.version.release).Trim()) (API $((& adb -s $Serial shell getprop ro.build.version.sdk).Trim()))")
$report.Add('')
$report.Add('Package path and version:')
$report.AddRange([string[]](& adb -s $Serial shell dumpsys package $PackageName | Select-String 'versionName=|versionCode=|codePath=|primaryCpuAbi=|pkgFlags=' | ForEach-Object { $_.Line }))
$apkPaths = & adb -s $Serial shell pm path $PackageName
$installedBytes = 0L
foreach ($entry in $apkPaths) {
    if ($entry -match '^package:(.+)$') {
        $remotePath = $Matches[1].Trim()
        $sizeText = (& adb -s $Serial shell stat -c %s $remotePath 2>$null | Out-String).Trim()
        if ($LASTEXITCODE -eq 0 -and $sizeText -match '^\d+$') {
            $size = [long]$sizeText
            $installedBytes += $size
            $report.Add("APK split bytes: $sizeText")
        } else {
            $report.Add('APK split bytes: unavailable on this Android build')
        }
    }
}
if ($installedBytes -gt 0) { $report.Add("Installed APK bytes total: $installedBytes") }
$report.Add('')
$report.Add('App memory:')
$report.AddRange([string[]](& adb -s $Serial shell dumpsys meminfo $PackageName | Select-String 'Native Heap|Dalvik Heap|TOTAL PSS|TOTAL RSS|TOTAL:' | ForEach-Object { $_.Line }))
$report.Add('')
$report.Add('Graphics frame summary:')
$report.AddRange([string[]](& adb -s $Serial shell dumpsys gfxinfo $PackageName | Select-String 'Total frames rendered|Janky frames|90th percentile|95th percentile|99th percentile' | ForEach-Object { $_.Line }))
$report.Add('')
$report.Add('App crash/ANR markers only (logcat message text is not copied):')
$markers = & adb -s $Serial logcat -d -t 10000 -s AndroidRuntime:E ActivityManager:E 2>&1 |
    Select-String -Pattern 'FATAL EXCEPTION|ANR in ai\.trustshield\.android|Process: ai\.trustshield\.android|am_crash|am_anr'
if ($markers) {
    foreach ($marker in $markers) { $report.Add($marker.Line) }
} else {
    $report.Add('None found in captured buffer.')
}
$report | Set-Content -LiteralPath $reportPath -Encoding utf8
Write-Output "Saved privacy-filtered device report: $reportPath"
