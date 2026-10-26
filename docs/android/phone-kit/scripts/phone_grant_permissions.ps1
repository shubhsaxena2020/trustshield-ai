[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [string]$PackageName = 'ai.trustshield.android'
)

$ErrorActionPreference = 'Stop'
$deviceRows = & adb devices
if ($LASTEXITCODE -ne 0 -or -not ($deviceRows | Where-Object { $_ -match "^$([regex]::Escape($Serial))\s+device$" })) {
    throw "ADB device '$Serial' is not connected and authorized."
}
$package = (& adb -s $Serial shell pm path $PackageName 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0 -or $package -notmatch 'package:') { throw "$PackageName is not installed on $Serial." }
foreach ($permission in @('android.permission.RECORD_AUDIO', 'android.permission.CAMERA')) {
    & adb -s $Serial shell pm grant $PackageName $permission
    if ($LASTEXITCODE -ne 0) { throw "Could not grant $permission." }
}
& adb -s $Serial shell dumpsys package $PackageName | Select-String 'android.permission.(RECORD_AUDIO|CAMERA)'
