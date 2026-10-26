[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [string]$ApkPath = (Join-Path $PSScriptRoot '..\..\..\..\apps\android\app\build\outputs\apk\release\app-arm64-v8a-release.apk'),
    [string]$PackageName = 'ai.trustshield.android'
)

$ErrorActionPreference = 'Stop'
$resolvedApk = (Resolve-Path -LiteralPath $ApkPath).Path
$deviceRows = & adb devices
if ($LASTEXITCODE -ne 0 -or -not ($deviceRows | Where-Object { $_ -match "^$([regex]::Escape($Serial))\s+device$" })) {
    throw "ADB device '$Serial' is not connected and authorized."
}
$abi = (& adb -s $Serial shell getprop ro.product.cpu.abi).Trim()
if ($LASTEXITCODE -ne 0 -or $abi -ne 'arm64-v8a') {
    throw "Expected an arm64-v8a device; detected '$abi'."
}
& adb -s $Serial install -r $resolvedApk
if ($LASTEXITCODE -ne 0) { throw 'APK install failed.' }
& adb -s $Serial shell monkey -p $PackageName 1
if ($LASTEXITCODE -ne 0) { throw 'App launch failed.' }
Write-Output "Installed and launched $PackageName on $Serial ($abi)."
