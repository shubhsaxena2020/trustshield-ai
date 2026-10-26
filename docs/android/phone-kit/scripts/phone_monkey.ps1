[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [int]$Events = 2000,
    [int[]]$Seeds = @(4242, 7, 99),
    [string]$PackageName = 'ai.trustshield.android',
    [string]$OutputDirectory = 'logs'
)

$ErrorActionPreference = 'Stop'
if ($Events -lt 1 -or $Events -gt 10000) { throw 'Events must be in the range 1..10000.' }
$deviceRows = & adb devices
if ($LASTEXITCODE -ne 0 -or -not ($deviceRows | Where-Object { $_ -match "^$([regex]::Escape($Serial))\s+device$" })) {
    throw "ADB device '$Serial' is not connected and authorized."
}
if (-not (& adb -s $Serial shell pm path $PackageName 2>&1 | Out-String).Contains('package:')) { throw "$PackageName is not installed." }
$out = if ([System.IO.Path]::IsPathRooted($OutputDirectory)) { $OutputDirectory } else { [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot $OutputDirectory)) }
New-Item -ItemType Directory -Path $out -Force | Out-Null
foreach ($seed in $Seeds) {
    $logPath = Join-Path $out "phone-monkey-$seed-$Events.txt"
    & adb -s $Serial logcat -c
    if ($LASTEXITCODE -ne 0) { throw 'Could not clear logcat before the run.' }
    & adb -s $Serial shell monkey -p $PackageName -s $seed --throttle 40 --pct-syskeys 0 --pct-appswitch 0 $Events 2>&1 | Set-Content -LiteralPath $logPath -Encoding utf8
    if ($LASTEXITCODE -ne 0) { throw "Monkey process failed for seed $seed; see $logPath." }
    $crashPath = [System.IO.Path]::ChangeExtension($logPath, '.crashes.txt')
    $crashLines = & adb -s $Serial logcat -d -t 10000 -s AndroidRuntime:E ActivityManager:E 2>&1 |
        Select-String -Pattern 'FATAL EXCEPTION|ANR in ai\.trustshield\.android|Process: ai\.trustshield\.android|am_crash|am_anr'
    if ($crashLines) {
        $crashLines | Set-Content -LiteralPath $crashPath -Encoding utf8
        throw "FAIL seed ${seed}: crash/ANR markers found; see $crashPath."
    }
    "PASS seed $seed / $Events events / no app crash or ANR marker" | Tee-Object -FilePath $logPath -Append
}
